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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Stamps one MIDlet suite into a template APK, producing a standalone app.
 *
 * <p>Building an APK from nothing needs a resource compiler, which is a native binary and so
 * out of reach on a device. This takes the other route: a template APK built earlier holds
 * the emulator, a compiled manifest and a resource table already, and everything that has to
 * change about it can be changed without recompiling any of that.
 *
 * <ul>
 *   <li>Identity - the application id, label and version - lives in the compiled manifest's
 *       string pool, which {@link BinaryXml} can rewrite.
 *   <li>The suite's classes go in as a second dex. Android loads every {@code classesN.dex}
 *       an APK holds, so nothing has to merge them.
 *   <li>The suite's files go in as ordinary entries, which is where the emulator looks for
 *       them when it is built as a port.
 *   <li>The icon is written over the bytes of the template's own icon files. The resource
 *       table maps a name to a path and neither changes, so the table is left alone.
 * </ul>
 *
 * <p>What comes out is unsigned: an APK has to be signed after its contents are final, and
 * whoever calls this decides with which key.
 */
public final class ApkRepackager {
	/** Where the emulator reads the suite descriptor from when it runs as a port. */
	public static final String MANIFEST_RESOURCE = "MIDLET-META-INF/MANIFEST.MF";

	/** Where the emulator reads settings chosen at packaging time. */
	public static final String CONFIG_RESOURCE = "MIDLET-META-INF/config.json";

	private static final String ANDROID_MANIFEST = "AndroidManifest.xml";

	/** The identity a template is built with, and which every port replaces. */
	public static final String TEMPLATE_ID = "ru.playsoftware.j2meloader.port";
	public static final String TEMPLATE_LABEL = "J2ME_PORT_LABEL";
	public static final String TEMPLATE_VERSION = "0.0.0-template";

	/** Uncompressed entries an APK must keep aligned for Android to map them in place. */
	private static final int ALIGNMENT = 4;
	private static final int SO_ALIGNMENT = 4096;

	private final File template;
	private final List<String> warnings = new ArrayList<>();

	public ApkRepackager(File template) {
		this.template = template;
	}

	public List<String> getWarnings() {
		return warnings;
	}

	/** Everything that distinguishes one port from another. */
	public static final class Port {
		public String applicationId;
		public String label;
		public String versionName;

		/** The suite's classes, already dexed. */
		public File dex;

		/** The suite's own jar; its non-class entries become the port's resources. */
		public File suiteJar;

		/** The suite descriptor, as the emulator writes it. */
		public byte[] descriptor;

		/** Settings to start with, as a JSON object, or null to ask on first launch. */
		public byte[] settings;

		/** The suite icon, as a PNG, or null to keep the emulator's own. */
		public byte[] icon;
	}

	/**
	 * Writes the port described by {@code port} to {@code target}.
	 *
	 * @return the unsigned APK, which still has to be signed to be installable
	 */
	public File repackage(Port port, File target) throws IOException {
		if (port.applicationId == null || port.applicationId.isEmpty()) {
			throw new IOException("A port needs an application id");
		}
		if (port.dex == null || !port.dex.isFile()) {
			throw new IOException("A port needs the suite's dex: " + port.dex);
		}
		Map<String, byte[]> replacements = new LinkedHashMap<>();
		Map<String, byte[]> additions = new LinkedHashMap<>();

		try (ZipFile zip = new ZipFile(template)) {
			replacements.put(ANDROID_MANIFEST, patchManifest(read(zip, ANDROID_MANIFEST), port));
			if (port.icon != null) {
				replacements.putAll(replaceIcons(zip, port.icon));
			}
			additions.put(nextDexName(zip), readFile(port.dex));
			additions.put(MANIFEST_RESOURCE, port.descriptor);
			if (port.settings != null) {
				additions.put(CONFIG_RESOURCE, port.settings);
			}
			if (port.suiteJar != null) {
				addSuiteFiles(port.suiteJar, additions, zip);
			}
			write(zip, target, replacements, additions);
		}
		return target;
	}

	// --- manifest ----------------------------------------------------------------------

	private byte[] patchManifest(byte[] manifest, Port port) throws IOException {
		BinaryXml xml = BinaryXml.parse(manifest);
		String templateId = xml.getPackageName();
		if (templateId == null) {
			throw new IOException("The template's manifest has no package name");
		}
		if (xml.renamePackage(templateId, port.applicationId) == 0) {
			throw new IOException("Nothing in the template's manifest carried its id "
					+ templateId + "; is this a template?");
		}
		if (port.label != null && xml.replace(TEMPLATE_LABEL, port.label) == 0) {
			// A port built from a template always has one; anything else was built to be a
			// finished app and its label lives in the resource table, out of reach here.
			warnings.add("The template has no label placeholder, so the port keeps its label");
		}
		if (port.versionName != null) {
			xml.replace(TEMPLATE_VERSION, port.versionName);
		}
		return xml.toByteArray();
	}

	// --- icons -------------------------------------------------------------------------

	/**
	 * Points the template's icon files at the suite's icon by overwriting them where they
	 * are. Every density gets the same image: it is one small bitmap to begin with, and
	 * writing it once per density keeps the resource table honest without resizing anything.
	 */
	private Map<String, byte[]> replaceIcons(ZipFile zip, byte[] icon) throws IOException {
		Map<String, byte[]> replacements = new HashMap<>();
		for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
			String name = e.nextElement().getName();
			if (isLauncherIcon(name)) {
				replacements.put(name, icon);
			}
		}
		if (replacements.isEmpty()) {
			// Silently leaving the emulator's own icon on someone's game would be the worst
			// outcome: it looks like the export worked. The cause is always the same, so say
			// so rather than making them work it out from an icon that looks wrong.
			throw new IOException("No launcher icon found in the template. Its resource files"
					+ " were renamed, which happens when it is built with resource optimization"
					+ " on; build it with -Pandroid.enableResourceOptimizations=false.");
		}
		return replacements;
	}

	/**
	 * Whether an entry is one of the template's launcher bitmaps.
	 *
	 * <p>This goes by path, which only works while the template keeps its resource names.
	 * A release build normally shortens every one of them to something like {@code res/BW.xml},
	 * and then nothing about a path says what it holds - hence the flag a template is built
	 * with. Resolving the manifest's icon reference through {@code resources.arsc} would not
	 * need that, at the cost of parsing the resource table.
	 */
	private static boolean isLauncherIcon(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return lower.startsWith("res/") && lower.endsWith(".png")
				&& (lower.contains("mipmap") || lower.contains("ic_launcher"));
	}

	// --- suite content -----------------------------------------------------------------

	/** The next free {@code classesN.dex} slot; Android loads them in order until one is missing. */
	private static String nextDexName(ZipFile zip) {
		int index = 2;
		while (zip.getEntry("classes" + index + ".dex") != null) {
			index++;
		}
		return "classes" + index + ".dex";
	}

	/**
	 * Copies the suite's non-class entries in, so that the emulator finds them exactly where
	 * it looks for a port's resources.
	 */
	private void addSuiteFiles(File suiteJar, Map<String, byte[]> additions, ZipFile template)
			throws IOException {
		try (ZipFile zip = new ZipFile(suiteJar)) {
			for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
				ZipEntry entry = e.nextElement();
				String name = entry.getName();
				if (entry.isDirectory() || name.endsWith(".class")) {
					continue;
				}
				if (name.toUpperCase(Locale.ROOT).startsWith("META-INF/")) {
					// The suite's signature no longer covers anything, and its manifest is
					// written separately where the emulator expects to read it.
					continue;
				}
				if (template.getEntry(name) != null || additions.containsKey(name)) {
					warnings.add("Suite file '" + name + "' clashes with the emulator's own and was skipped");
					continue;
				}
				try (InputStream in = zip.getInputStream(entry)) {
					additions.put(name, readAll(in));
				}
			}
		}
	}

	// --- writing -----------------------------------------------------------------------

	/**
	 * Copies the template out with the given entries replaced and added.
	 *
	 * <p>Entries the template stored uncompressed stay uncompressed and keep their alignment:
	 * Android maps those straight out of the file, so compressing one, or letting it land at
	 * an odd offset, would stop it being usable where it lies.
	 */
	private void write(ZipFile zip, File target, Map<String, byte[]> replacements,
					   Map<String, byte[]> additions) throws IOException {
		try (CountingStream counter = new CountingStream(new FileOutputStream(target));
			 ZipOutputStream out = new ZipOutputStream(counter)) {
			for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
				ZipEntry entry = e.nextElement();
				String name = entry.getName();
				if (entry.isDirectory() || isSignature(name)) {
					// The template's signature does not survive its contents changing.
					continue;
				}
				byte[] replacement = replacements.get(name);
				if (replacement != null) {
					writeEntry(out, counter, name, replacement, entry.getMethod());
				} else {
					try (InputStream in = zip.getInputStream(entry)) {
						writeEntry(out, counter, name, readAll(in), entry.getMethod());
					}
				}
			}
			for (Map.Entry<String, byte[]> addition : additions.entrySet()) {
				writeEntry(out, counter, addition.getKey(), addition.getValue(), ZipEntry.DEFLATED);
			}
		}
	}

	private static boolean isSignature(String name) {
		String upper = name.toUpperCase(Locale.ROOT);
		return upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".RSA")
				|| upper.endsWith(".DSA") || upper.endsWith(".EC") || upper.equals("META-INF/MANIFEST.MF"));
	}

	private void writeEntry(ZipOutputStream out, CountingStream counter, String name,
							byte[] data, int method) throws IOException {
		ZipEntry entry = new ZipEntry(name);
		entry.setTime(0L);
		entry.setMethod(method);
		if (method == ZipEntry.STORED) {
			entry.setSize(data.length);
			entry.setCompressedSize(data.length);
			CRC32 crc = new CRC32();
			crc.update(data, 0, data.length);
			entry.setCrc(crc.getValue());
			entry.setExtra(padding(counter.count(), name, data.length));
		}
		out.putNextEntry(entry);
		out.write(data);
		out.closeEntry();
	}

	/**
	 * Extra-field bytes that push an uncompressed entry's data onto its alignment boundary.
	 *
	 * <p>The local header is 30 bytes plus the name plus this field, so padding the field is
	 * what moves the data. Shared objects are mapped a page at a time and need a page
	 * boundary; everything else needs a word.
	 */
	private static byte[] padding(long headerStart, String name, int size) {
		int alignment = name.endsWith(".so") ? SO_ALIGNMENT : ALIGNMENT;
		long dataStart = headerStart + 30 + name.getBytes(StandardCharsets.UTF_8).length;
		int pad = (int) ((alignment - (dataStart % alignment)) % alignment);
		return pad == 0 ? null : new byte[pad];
	}

	// --- helpers -----------------------------------------------------------------------

	private static byte[] read(ZipFile zip, String name) throws IOException {
		ZipEntry entry = zip.getEntry(name);
		if (entry == null) {
			throw new IOException("The template has no " + name);
		}
		try (InputStream in = zip.getInputStream(entry)) {
			return readAll(in);
		}
	}

	private static byte[] readFile(File file) throws IOException {
		try (InputStream in = new FileInputStream(file)) {
			return readAll(in);
		}
	}

	private static byte[] readAll(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, in.available()));
		byte[] buffer = new byte[8192];
		int read;
		while ((read = in.read(buffer)) > 0) {
			out.write(buffer, 0, read);
		}
		return out.toByteArray();
	}

	/** Tracks how far into the file the next entry begins, which is what alignment needs. */
	private static final class CountingStream extends OutputStream {
		private final OutputStream out;
		private long count;

		CountingStream(OutputStream out) {
			this.out = out;
		}

		long count() {
			return count;
		}

		@Override
		public void write(int b) throws IOException {
			out.write(b);
			count++;
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			out.write(b, off, len);
			count += len;
		}

		@Override
		public void close() throws IOException {
			out.close();
		}
	}
}
