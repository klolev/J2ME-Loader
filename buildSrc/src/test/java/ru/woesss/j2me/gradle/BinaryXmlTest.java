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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
	private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

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

	@Test
	public void readsThePermissionsAManifestAsksFor() throws IOException {
		byte[] xml = compiledManifest("com.example.game",
				"android.permission.INTERNET", "android.permission.CAMERA");

		assertEquals(Arrays.asList("android.permission.INTERNET", "android.permission.CAMERA"),
				BinaryXml.parse(xml).getUsesPermissions());
	}

	@Test
	public void cutsOutThePermissionsAPortWillNotUse() throws IOException {
		byte[] xml = compiledManifest("com.example.game",
				"android.permission.INTERNET", "android.permission.CAMERA",
				"android.permission.VIBRATE", "android.permission.RECORD_AUDIO");

		BinaryXml parsed = BinaryXml.parse(xml);
		assertEquals(2, parsed.removeUsesPermissions(Arrays.asList(
				"android.permission.CAMERA", "android.permission.RECORD_AUDIO")));

		// Reparsed rather than merely re-read: an element removed by cutting bytes out of a
		// chunk run has to leave the file still walkable end to end.
		BinaryXml reparsed = BinaryXml.parse(parsed.toByteArray());
		assertEquals(Arrays.asList("android.permission.INTERNET", "android.permission.VIBRATE"),
				reparsed.getUsesPermissions());
		// Everything that was not a permission is where it was.
		assertEquals("com.example.game", reparsed.getPackageName());
	}

	@Test
	public void removingNothingLeavesTheFileByteForByte() throws IOException {
		byte[] xml = compiledManifest("com.example.game", "android.permission.INTERNET");

		BinaryXml parsed = BinaryXml.parse(xml);
		assertEquals(0, parsed.removeUsesPermissions(Collections.<String>emptyList()));
		assertEquals(0, parsed.removeUsesPermissions(
				Collections.singletonList("android.permission.CAMERA")));

		assertEquals(Collections.singletonList("android.permission.INTERNET"),
				BinaryXml.parse(parsed.toByteArray()).getUsesPermissions());
	}

	@Test
	public void leavesElementsThatAreNotPermissionsAlone() throws IOException {
		// uses-feature carries an android:name too, and naming a permission in one would be
		// a coincidence worth surviving.
		byte[] xml = compiledManifest("com.example.game", "android.permission.CAMERA");

		BinaryXml parsed = BinaryXml.parse(xml);
		parsed.removeUsesPermissions(Collections.singletonList("android.hardware.camera"));

		assertEquals(Collections.singletonList("android.permission.CAMERA"),
				BinaryXml.parse(parsed.toByteArray()).getUsesPermissions());
	}

	@Test
	public void reportsNoPermissionsWhenThereAreNoElements() throws IOException {
		assertEquals(Collections.<String>emptyList(),
				BinaryXml.parse(compiledXml(false, "android")).getUsesPermissions());
	}

	// --- fixtures ----------------------------------------------------------------------

	/**
	 * Builds a compiled manifest declaring {@code permissions}, complete with the element
	 * chunks a real one carries: a resource map to be stepped over, the {@code manifest}
	 * element holding the application id, and a start/end pair per permission.
	 */
	private static byte[] compiledManifest(String applicationId, String... permissions)
			throws IOException {
		List<String> pool = new ArrayList<>(Arrays.asList(
				ANDROID_NS, "name", "package", "manifest", "uses-permission", applicationId));
		pool.addAll(Arrays.asList(permissions));

		ByteArrayOutputStream tail = new ByteArrayOutputStream();
		// A resource id map: not read here, and the walk has to step over it by size alone.
		writeShort(tail, 0x0180);
		writeShort(tail, 8);
		writeInt(tail, 12);
		writeInt(tail, 0x01010003);

		writeStartElement(tail, pool, "manifest", "package", applicationId);
		for (String permission : permissions) {
			writeStartElement(tail, pool, "uses-permission", "name", permission);
			writeEndElement(tail, pool, "uses-permission");
		}
		writeEndElement(tail, pool, "manifest");

		byte[] head = compiledXml(false, pool.toArray(new String[0]));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		writeShort(out, 0x0003);
		writeShort(out, 8);
		writeInt(out, head.length + tail.size());
		// Everything of the header fixture but its own 8-byte chunk header: the pool.
		out.write(head, 8, head.length - 8);
		out.write(tail.toByteArray());
		return out.toByteArray();
	}

	private static void writeStartElement(ByteArrayOutputStream out, List<String> pool,
										  String element, String attribute, String value)
			throws IOException {
		boolean namespaced = !"package".equals(attribute);
		writeShort(out, 0x0102);
		writeShort(out, 16);
		writeInt(out, 16 + 20 + 20); // header, attrExt, one attribute
		writeInt(out, 1);  // line number
		writeInt(out, -1); // comment
		writeInt(out, -1); // element namespace
		writeInt(out, pool.indexOf(element));
		writeShort(out, 20); // attributes start, from the end of the header
		writeShort(out, 20); // attribute size
		writeShort(out, 1);  // attribute count
		writeShort(out, 0);  // id index
		writeShort(out, 0);  // class index
		writeShort(out, 0);  // style index
		writeInt(out, namespaced ? pool.indexOf(ANDROID_NS) : -1);
		writeInt(out, pool.indexOf(attribute));
		writeInt(out, pool.indexOf(value)); // raw value
		writeShort(out, 8);  // Res_value size
		out.write(0);        // res0
		out.write(0x03);     // TYPE_STRING
		writeInt(out, pool.indexOf(value));
	}

	private static void writeEndElement(ByteArrayOutputStream out, List<String> pool,
										String element) {
		writeShort(out, 0x0103);
		writeShort(out, 16);
		writeInt(out, 16 + 8);
		writeInt(out, 1);  // line number
		writeInt(out, -1); // comment
		writeInt(out, -1); // element namespace
		writeInt(out, pool.indexOf(element));
	}

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
