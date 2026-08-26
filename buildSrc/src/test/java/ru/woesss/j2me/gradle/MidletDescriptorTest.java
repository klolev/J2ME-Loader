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

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MidletDescriptorTest {

	@Test
	public void readsAttributes() {
		MidletDescriptor descriptor = MidletDescriptor.parse(""
				+ "Manifest-Version: 1.0\r\n"
				+ "MIDlet-Name: Snake\r\n"
				+ "MIDlet-Vendor: Acme\r\n"
				+ "MIDlet-Version: 1.2.3\r\n"
				+ "MIDlet-1: Snake, /icon.png, com.acme.Snake\r\n");
		assertEquals("Snake", descriptor.getName());
		assertEquals("Acme", descriptor.getVendor());
		assertEquals("1.2.3", descriptor.getVersion());
		assertEquals("icon.png", descriptor.getIconPath());

		List<MidletDescriptor.Midlet> midlets = descriptor.getMidlets();
		assertEquals(1, midlets.size());
		assertEquals("Snake", midlets.get(0).title);
		assertEquals("com.acme.Snake", midlets.get(0).mainClass);
	}

	@Test
	public void joinsWrappedValuesAndSkipsByteOrderMark() {
		// A manifest wraps on a byte count, mid-token, and the leading space of the
		// continuation is padding: joining must not insert a space of its own.
		MidletDescriptor descriptor = MidletDescriptor.parse("\uFEFF"
				+ "Manifest-Version: 1.0\r\n"
				+ "MIDlet-Name: A very long suite name that the pack\r\n"
				+ " ager wrapped\r\n"
				+ "MIDlet-Vendor: Acme\r\n"
				+ "MIDlet-Version: 1.0\r\n"
				+ "MIDlet-1: Game, , Main\r\n");
		assertEquals("A very long suite name that the packager wrapped", descriptor.getName());
	}

	@Test
	public void fallsBackToTheIconOfTheFirstMidlet() {
		MidletDescriptor descriptor = MidletDescriptor.parse(""
				+ "MIDlet-Name: Game\r\n"
				+ "MIDlet-Vendor: Acme\r\n"
				+ "MIDlet-Version: 1.0\r\n"
				+ "MIDlet-1: Game, /gfx/game.png, Main\r\n");
		assertEquals("gfx/game.png", descriptor.getIconPath());
	}

	@Test
	public void reportsNoIconWhenTheMidletDeclaresNone() {
		MidletDescriptor descriptor = MidletDescriptor.parse(""
				+ "MIDlet-Name: Game\r\n"
				+ "MIDlet-Vendor: Acme\r\n"
				+ "MIDlet-Version: 1.0\r\n"
				+ "MIDlet-1: Game, , Main\r\n");
		assertNull(descriptor.getIconPath());
	}

	@Test
	public void jadAttributesWinOverTheJarManifest() {
		MidletDescriptor manifest = MidletDescriptor.parse(""
				+ "MIDlet-Name: Game\r\n"
				+ "MIDlet-Vendor: Acme\r\n"
				+ "MIDlet-Version: 1.0\r\n"
				+ "MIDlet-1: Game, , Main\r\n");
		MidletDescriptor jad = MidletDescriptor.parse(""
				+ "MIDlet-Version: 2.0\r\n"
				+ "Server-URL: http://example.invalid\r\n");
		manifest.merge(jad);

		assertEquals("2.0", manifest.getVersion());
		assertEquals("Game", manifest.getName());
		assertEquals("http://example.invalid", manifest.get("Server-URL"));
	}

	@Test
	public void namesEveryMissingRequiredAttributeAtOnce() {
		try {
			MidletDescriptor.parse("MIDlet-Name: Game\r\n").verify();
			fail("Expected the incomplete descriptor to be rejected");
		} catch (IllegalArgumentException e) {
			String message = e.getMessage();
			assertTrue(message, message.contains("MIDlet-Vendor"));
			assertTrue(message, message.contains("MIDlet-Version"));
			assertTrue(message, message.contains("MIDlet-1"));
		}
	}

	@Test
	public void roundTripsThroughManifestText() {
		String source = ""
				+ "MIDlet-Name: Game\r\n"
				+ "MIDlet-Vendor: Acme\r\n"
				+ "MIDlet-Version: 1.0\r\n"
				+ "MIDlet-1: Game, , Main\r\n"
				+ "Server-URL: http://example.invalid/path\r\n";
		MidletDescriptor descriptor = MidletDescriptor.parse(source);
		MidletDescriptor reparsed = MidletDescriptor.parse(descriptor.toManifestText());

		assertEquals(descriptor.getAttributes(), reparsed.getAttributes());
		// A value with a colon in it must survive: the parser reads colon-less lines as
		// continuations, so writing must never wrap one.
		assertEquals("http://example.invalid/path", reparsed.get("Server-URL"));
	}
}
