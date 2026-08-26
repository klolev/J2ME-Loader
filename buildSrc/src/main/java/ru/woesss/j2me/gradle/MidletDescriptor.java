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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MIDlet suite attributes, as found in a JAR manifest or in a JAD file.
 *
 * <p>The parser deliberately matches {@code ru.woesss.j2me.jar.Descriptor}, the one the
 * emulator uses at runtime: attributes are {@code Key: value} lines, a line that carries no
 * colon continues the previous value, and a single leading space of a continuation is
 * dropped. Keeping the two in step means a suite that installs in the emulator also builds
 * into an APK.
 */
public final class MidletDescriptor {
	public static final String MANIFEST_VERSION = "Manifest-Version";
	public static final String MIDLET_NAME = "MIDlet-Name";
	public static final String MIDLET_VERSION = "MIDlet-Version";
	public static final String MIDLET_VENDOR = "MIDlet-Vendor";
	public static final String MIDLET_ICON = "MIDlet-Icon";
	public static final String MIDLET_JAR_URL = "MIDlet-Jar-URL";
	public static final String MIDLET_N = "MIDlet-";
	public static final String MICROEDITION_PROFILE = "MicroEdition-Profile";
	public static final String MICROEDITION_CONFIGURATION = "MicroEdition-Configuration";

	private static final char UNICODE_BOM = '\uFEFF';

	private final Map<String, String> attributes = new LinkedHashMap<>();

	private MidletDescriptor() {
	}

	public static MidletDescriptor parse(String source) {
		MidletDescriptor descriptor = new MidletDescriptor();
		descriptor.read(source);
		return descriptor;
	}

	public static MidletDescriptor read(File file) throws IOException {
		return parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
	}

	private void read(String source) {
		String[] lines = source.split("[\\n\\r]+");
		if (lines.length == 0 || source.trim().isEmpty()) {
			throw new IllegalArgumentException("Descriptor source is empty");
		}
		if (!lines[0].isEmpty() && lines[0].charAt(0) == UNICODE_BOM) {
			lines[0] = lines[0].substring(1);
		}
		StringBuilder value = new StringBuilder("1.0");
		String key = MANIFEST_VERSION;
		for (String line : lines) {
			if (line.trim().isEmpty()) {
				continue;
			}
			int colon = line.indexOf(':');
			if (colon == -1) {
				// A wrapped value: the manifest format allows one leading space as padding.
				value.append(line.charAt(0) == ' ' ? line.substring(1) : line);
				continue;
			}
			attributes.put(key, value.toString().trim());
			value.setLength(0);
			key = line.substring(0, colon++).trim();
			if (colon < line.length() && line.charAt(colon) == ' ') {
				colon++;
			}
			value.append(line, colon, line.length());
		}
		attributes.put(key, value.toString().trim());
	}

	/** Attributes of {@code other} win, as a JAD overrides the JAR manifest it describes. */
	public void merge(MidletDescriptor other) {
		attributes.putAll(other.attributes);
	}

	public Map<String, String> getAttributes() {
		return Collections.unmodifiableMap(attributes);
	}

	public String get(String key) {
		return attributes.get(key);
	}

	public void set(String key, String value) {
		attributes.put(key, value);
	}

	public void remove(String key) {
		attributes.remove(key);
	}

	public String getName() {
		return trimToNull(attributes.get(MIDLET_NAME));
	}

	public String getVendor() {
		return trimToNull(attributes.get(MIDLET_VENDOR));
	}

	public String getVersion() {
		return trimToNull(attributes.get(MIDLET_VERSION));
	}

	public String getJarUrl() {
		return trimToNull(attributes.get(MIDLET_JAR_URL));
	}

	/**
	 * Path of the suite icon inside the JAR, taken from {@code MIDlet-Icon} or, failing that,
	 * from the icon field of the first {@code MIDlet-n} entry. Null when the suite has none.
	 */
	public String getIconPath() {
		String icon = trimToNull(attributes.get(MIDLET_ICON));
		if (icon == null) {
			String midlet = attributes.get(MIDLET_N + 1);
			if (midlet == null) {
				return null;
			}
			int start = midlet.indexOf(',');
			if (start == -1) {
				return null;
			}
			int end = midlet.indexOf(',', ++start);
			icon = trimToNull(end == -1 ? null : midlet.substring(start, end));
			if (icon == null) {
				return null;
			}
		}
		while (!icon.isEmpty() && icon.charAt(0) == '/') {
			icon = icon.substring(1);
		}
		return icon.isEmpty() ? null : icon;
	}

	/** The {@code MIDlet-n} entries of the suite, in declaration order. */
	public List<Midlet> getMidlets() {
		List<Midlet> midlets = new ArrayList<>();
		for (int i = 1; ; i++) {
			String value = attributes.get(MIDLET_N + i);
			if (value == null) {
				break;
			}
			int comma = value.indexOf(',');
			if (comma == -1) {
				throw new IllegalArgumentException("Malformed attribute '" + MIDLET_N + i + ": " + value + "'");
			}
			String title = value.substring(0, comma).trim();
			String mainClass = value.substring(value.lastIndexOf(',') + 1).trim();
			if (mainClass.isEmpty()) {
				throw new IllegalArgumentException("No MIDlet class in '" + MIDLET_N + i + ": " + value + "'");
			}
			midlets.add(new Midlet(title, mainClass));
		}
		return midlets;
	}

	/**
	 * Checks that the suite carries what both the emulator and the APK build need. Reported
	 * together so a hand-edited descriptor does not have to be fixed one attribute per run.
	 */
	public void verify() {
		List<String> missing = new ArrayList<>();
		if (getName() == null) missing.add(MIDLET_NAME);
		if (getVersion() == null) missing.add(MIDLET_VERSION);
		if (getVendor() == null) missing.add(MIDLET_VENDOR);
		if (attributes.get(MIDLET_N + 1) == null) missing.add(MIDLET_N + "1");
		if (!missing.isEmpty()) {
			throw new IllegalArgumentException("MIDlet descriptor is missing required attributes: "
					+ String.join(", ", missing));
		}
		getMidlets();
	}

	/**
	 * Renders the descriptor in manifest syntax. Values are written unwrapped: the runtime
	 * parser treats any colon-less line as a continuation, so wrapping a value that itself
	 * contains a colon would change how it reads back.
	 */
	public String toManifestText() {
		StringBuilder sb = new StringBuilder();
		String manifestVersion = attributes.get(MANIFEST_VERSION);
		sb.append(MANIFEST_VERSION).append(": ").append(manifestVersion == null ? "1.0" : manifestVersion).append("\r\n");
		for (Map.Entry<String, String> entry : attributes.entrySet()) {
			if (MANIFEST_VERSION.equals(entry.getKey())) {
				continue;
			}
			sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
		}
		return sb.toString();
	}

	public void writeTo(File file) {
		try {
			File parent = file.getParentFile();
			if (parent != null) {
				Files.createDirectories(parent.toPath());
			}
			Files.write(file.toPath(), toManifestText().getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException("Can't write descriptor to " + file, e);
		}
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	/** One {@code MIDlet-n} entry: the title shown to the user and its {@code MIDlet} subclass. */
	public static final class Midlet {
		public final String title;
		public final String mainClass;

		Midlet(String title, String mainClass) {
			this.title = title;
			this.mainClass = mainClass;
		}
	}
}
