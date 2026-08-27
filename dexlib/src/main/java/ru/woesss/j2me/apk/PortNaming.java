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

package ru.woesss.j2me.apk;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * How a suite's name becomes an application id, a version code and a file name.
 *
 * <p>A port can be built two ways - by Gradle on a desktop, or by the emulator on a device -
 * and the two have to agree. Android tells apps apart by their id alone, so if the same suite
 * came out with different ids, the second build would install beside the first instead of
 * updating it, and the player would end up with two copies and one set of saves.
 */
public final class PortNaming {
	/** Prefix for a derived id, keeping ports clear of anything a real vendor would use. */
	private static final String PACKAGE_PREFIX = "com.example.androidlet.";

	private PortNaming() {
	}

	/**
	 * Derives an application id from the suite name.
	 *
	 * <p>Suite names are free text - spaces, punctuation, any script - so the last package
	 * segment is reduced to what a Java identifier allows, falling back to a digest when
	 * nothing usable survives.
	 */
	public static String derivePackageName(String name) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c >= 'A' && c <= 'Z') {
				sb.append((char) (c - 'A' + 'a'));
			} else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
				sb.append(c);
			} else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') {
				sb.append('_');
			}
		}
		while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '_') {
			sb.setLength(sb.length() - 1);
		}
		String segment = sb.toString();
		if (segment.isEmpty() || !Character.isLetter(segment.charAt(0)) || isJavaKeyword(segment)) {
			// Names in a non-Latin script leave nothing to transliterate; a digest of the
			// name still gives every suite its own stable id.
			segment = "midlet_" + shortDigest(name);
		}
		return PACKAGE_PREFIX + segment;
	}

	/**
	 * Maps {@code MIDlet-Version} onto an Android version code, keeping the ordering between
	 * releases of the same suite. Each component is capped so a wild version can't overflow.
	 */
	public static int deriveVersionCode(String version) {
		if (version == null) {
			return 1;
		}
		String[] parts = version.trim().split("\\.");
		int[] values = new int[3];
		for (int i = 0; i < 3; i++) {
			if (i >= parts.length) {
				break;
			}
			try {
				values[i] = Math.max(0, Math.min(999, Integer.parseInt(parts[i].trim())));
			} catch (NumberFormatException ignored) {
				// A non-numeric component contributes nothing rather than failing the build.
			}
		}
		int code = values[0] * 1_000_000 + values[1] * 1_000 + values[2];
		return code == 0 ? 1 : code;
	}

	/** Strips what a file name can't hold, matching how the emulator names its APK output. */
	public static String sanitizeFileName(String name) {
		String cleaned = name.replaceAll("[/\\\\:*?\"<>|]", "").trim().replaceAll("\\s+", "_");
		return cleaned.isEmpty() ? "midlet" : cleaned;
	}

	private static boolean isJavaKeyword(String value) {
		switch (value) {
			case "abstract": case "assert": case "boolean": case "break": case "byte": case "case":
			case "catch": case "char": case "class": case "const": case "continue": case "default":
			case "do": case "double": case "else": case "enum": case "extends": case "final":
			case "finally": case "float": case "for": case "goto": case "if": case "implements":
			case "import": case "instanceof": case "int": case "interface": case "long":
			case "native": case "new": case "package": case "private": case "protected":
			case "public": case "return": case "short": case "static": case "strictfp":
			case "super": case "switch": case "synchronized": case "this": case "throw":
			case "throws": case "transient": case "try": case "void": case "volatile":
			case "while": case "_":
				return true;
			default:
				return false;
		}
	}

	private static String shortDigest(String value) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-1").digest(value.getBytes("UTF-8"));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 4; i++) {
				sb.append(String.format("%02x", hash[i]));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
			throw new IllegalStateException("SHA-1 and UTF-8 are required everywhere", e);
		}
	}
}
