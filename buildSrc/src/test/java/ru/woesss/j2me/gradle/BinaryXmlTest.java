/*
 *  Copyright 2024 J2ME Loader contributors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ru.woesss.j2me.gradle;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import ru.woesss.j2me.apk.BinaryXml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Covers the string pool surgery an on-device repackager depends on. The fixtures are built
 * here rather than taken from a real APK so the encodings that matter - UTF-16 and UTF-8,
 * short strings and long - are all exercised without needing aapt2 to produce them.
 */
public class BinaryXmlTest {

	@Test
	public void readsAndRewritesAUtf16Pool() throws IOException {
		byte[] xml = compiledXml(false, "package", "com.example.game", "android");

		BinaryXml parsed = BinaryXml.parse(xml);

		assertEquals(Arrays.asList("package", "com.example.game", "android"), parsed.getStrings());
		assertEquals(1, parsed.replace("com.example.game", "org.acme.port"));
		// Rewriting a string of a different length moves everything after it, so the file has
		// to be reassembled rather than patched in place.
		BinaryXml reparsed = BinaryXml.parse(parsed.toByteArray());
		assertEquals(Arrays.asList("package", "org.acme.port", "android"), reparsed.getStrings());
	}

	@Test
	public void readsAndRewritesAUtf8Pool() throws IOException {
		byte[] xml = compiledXml(true, "package", "com.example.game", "android");

		BinaryXml parsed = BinaryXml.parse(xml);
		assertEquals(Arrays.asList("package", "com.example.game", "android"), parsed.getStrings());
		parsed.replace("com.example.game", "org.acme.port");

		assertEquals(Arrays.asList("package", "org.acme.port", "android"),
				BinaryXml.parse(parsed.toByteArray()).getStrings());
	}

	@Test
	public void survivesStringsLongerThanOneLengthUnit() throws IOException {
		// A length of 128 or more needs the two-byte escape in a UTF-8 pool; this catches an
		// encoder that only ever writes the short form.
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < 200; i++) {
			builder.append('a');
		}
		String long_ = builder.toString();
		for (boolean utf8 : new boolean[]{false, true}) {
			byte[] xml = compiledXml(utf8, "package", long_, "android");
			assertEquals("utf8=" + utf8, Arrays.asList("package", long_, "android"),
					BinaryXml.parse(xml).getStrings());
		}
	}

	@Test
	public void keepsNonLatinText() throws IOException {
		byte[] xml = compiledXml(false, "package", "Тетрис", "android");

		BinaryXml parsed = BinaryXml.parse(xml);

		assertEquals("Тетрис", parsed.getStrings().get(1));
		assertEquals(parsed.getStrings(), BinaryXml.parse(parsed.toByteArray()).getStrings());
	}

	@Test
	public void movesEverythingDerivedFromTheApplicationId() throws IOException {
		// A build turns the application id into provider authorities and private permission
		// names too. Android refuses to install an app whose authority is already taken, so
		// leaving these behind would mean only one exported app could exist at a time.
		byte[] xml = compiledXml(false,
				"com.example.game",
				"com.example.game.documentProvider",
				"com.example.game.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
				"com.example.gameplay",
				"ru.playsoftware.j2meloader.settings.SettingsActivity");

		BinaryXml parsed = BinaryXml.parse(xml);
		assertEquals(3, parsed.renamePackage("com.example.game", "org.acme.port"));

		List<String> strings = BinaryXml.parse(parsed.toByteArray()).getStrings();
		assertEquals("org.acme.port", strings.get(0));
		assertEquals("org.acme.port.documentProvider", strings.get(1));
		assertEquals("org.acme.port.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", strings.get(2));
		// A different id that merely starts with the same characters must not be dragged
		// along, and neither must the class names, which are absolute and unrelated to it.
		assertEquals("com.example.gameplay", strings.get(3));
		assertEquals("ru.playsoftware.j2meloader.settings.SettingsActivity", strings.get(4));
	}

	@Test
	public void refusesSomethingThatIsNotCompiledXml() {
		try {
			BinaryXml.parse("<manifest package=\"com.example\"/>".getBytes(StandardCharsets.UTF_8));
			fail("Expected text XML to be rejected");
		} catch (IOException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("Not a compiled XML file"));
		}
	}

	@Test
	public void reportsNoPackageWhenThereIsNoManifestElement() throws IOException {
		// The fixtures carry a string pool but no elements, so there is nothing to read from.
		assertNull(BinaryXml.parse(compiledXml(false, "android")).getPackageName());
	}

	// --- fixtures ----------------------------------------------------------------------

	/** Builds a compiled XML file holding just a string pool, in either encoding. */
	private static byte[] compiledXml(boolean utf8, String... strings) throws IOException {
		ByteArrayOutputStream data = new ByteArrayOutputStream();
		int[] offsets = new int[strings.length];
		for (int i = 0; i < strings.length; i++) {
			offsets[i] = data.size();
			if (utf8) {
				byte[] bytes = strings[i].getBytes(StandardCharsets.UTF_8);
				writeLength8(data, strings[i].length());
				writeLength8(data, bytes.length);
				data.write(bytes);
				data.write(0);
			} else {
				byte[] bytes = strings[i].getBytes(StandardCharsets.UTF_16LE);
				writeLength16(data, bytes.length / 2);
				data.write(bytes);
				writeShort(data, 0);
			}
		}
		while (data.size() % 4 != 0) {
			data.write(0);
		}
		byte[] stringData = data.toByteArray();

		int stringsStart = 28 + strings.length * 4;
		int poolSize = stringsStart + stringData.length;
		ByteArrayOutputStream pool = new ByteArrayOutputStream();
		writeShort(pool, 0x0001);
		writeShort(pool, 28);
		writeInt(pool, poolSize);
		writeInt(pool, strings.length);
		writeInt(pool, 0);
		writeInt(pool, utf8 ? 1 << 8 : 0);
		writeInt(pool, stringsStart);
		writeInt(pool, 0);
		for (int offset : offsets) {
			writeInt(pool, offset);
		}
		pool.write(stringData);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeShort(out, 0x0003);
		writeShort(out, 8);
		writeInt(out, 8 + pool.size());
		out.write(pool.toByteArray());
		return out.toByteArray();
	}

	private static void writeLength8(ByteArrayOutputStream out, int length) {
		if (length > 0x7F) {
			out.write(((length >> 8) & 0x7F) | 0x80);
		}
		out.write(length & 0xFF);
	}

	private static void writeLength16(ByteArrayOutputStream out, int length) {
		if (length > 0x7FFF) {
			writeShort(out, ((length >> 16) & 0x7FFF) | 0x8000);
		}
		writeShort(out, length & 0xFFFF);
	}

	private static void writeShort(ByteArrayOutputStream out, int value) {
		out.write(value & 0xFF);
		out.write((value >> 8) & 0xFF);
	}

	private static void writeInt(ByteArrayOutputStream out, int value) {
		out.write(value & 0xFF);
		out.write((value >> 8) & 0xFF);
		out.write((value >> 16) & 0xFF);
		out.write((value >> 24) & 0xFF);
	}
}
