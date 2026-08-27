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

import org.microemu.android.asm.AndroidProducer;

import ru.woesss.j2me.apk.ConstantPool;
import ru.woesss.j2me.apk.PortPermissions;
import ru.woesss.j2me.apk.PortNaming;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Converts a MIDlet suite — a JAR, or a JAD plus its JAR — into the inputs an Android build
 * needs to package that suite as a standalone APK.
 *
 * <p>The emulator normally does this work on the device: it dexes the JAR and reads the suite
 * from its own storage. Here the same suite is folded into the APK instead, so the classes are
 * rewritten with the very same ASM visitors the on-device dexer uses ({@link AndroidProducer}),
 * then handed to AGP as an ordinary JAR dependency. AGP dexes the classes and packages the
 * suite's other entries as Java resources, which is exactly where {@code AppClassLoader} looks
 * for them when {@code BuildConfig.FULL_EMULATOR} is false.
 */
public final class MidletImporter {
	/** Where the merged suite descriptor lives inside the APK; {@code MicroLoader} reads it. */
	static final String MANIFEST_RESOURCE = "MIDLET-META-INF/MANIFEST.MF";

	/** Where settings chosen at packaging time live; {@code ProfilesManager} reads them. */
	static final String CONFIG_RESOURCE = "MIDLET-META-INF/config.json";

	/** Declared JAR size in a JAD; used to flag a download that is not what was promised. */
	private static final String MIDLET_JAR_SIZE = "MIDlet-Jar-Size";

	/** Bump when the output layout changes, so stale imports are redone. */
	private static final int FORMAT_VERSION = 3;

	private static final String STAMP_FILE = "import.stamp";
	private static final String SUMMARY_FILE = "import.summary";
	private static final String CLASSES_JAR = "midlet-classes.jar";
	private static final String PROGUARD_FILE = "midlet-keep.pro";
	private static final String RES_DIR = "res";

	private final File outputDir;
	private final List<String> warnings = new ArrayList<>();

	public MidletImporter(File outputDir) {
		this.outputDir = outputDir;
	}

	/** Messages worth surfacing in the build log; populated by {@link #importSuite}. */
	public List<String> getWarnings() {
		return warnings;
	}

	/**
	 * Imports the suite at {@code source}, which may be a JAR or a JAD.
	 *
	 * @param source        the suite's JAR or JAD
	 * @param packageOverride application id to use, or null to derive one from the suite name
	 * @param versionCodeOverride version code to use, or null to derive one from the suite version
	 * @param configJson    a JSON object of per-app settings to package with the port, laid
	 *                      over the emulator's defaults at first launch, or null to ship none
	 *                      and let the port ask on first launch as it does today
	 */
	public MidletImport importSuite(File source, String packageOverride, Integer versionCodeOverride,
									String configJson) throws IOException {
		if (!source.isFile()) {
			throw new IOException("MIDlet not found: " + source);
		}
		File jar;
		MidletDescriptor jad = null;
		String name = source.getName().toLowerCase(Locale.ROOT);
		if (name.endsWith(".jad")) {
			jad = MidletDescriptor.read(source);
			jar = resolveJarForJad(source, jad);
		} else if (name.endsWith(".jar") || name.endsWith(".zip")) {
			jar = source;
			File sibling = siblingJad(source);
			if (sibling != null) {
				jad = MidletDescriptor.read(sibling);
			}
		} else {
			throw new IOException("Not a MIDlet suite (expected .jad or .jar): " + source);
		}

		MidletDescriptor descriptor = readJarManifest(jar);
		if (jad != null) {
			// Per the MIDP packaging rules the JAD is authoritative for the attributes it
			// repeats over the JAR manifest.
			descriptor.merge(jad);
		}
		// The suite is inside the APK now, so where its JAR once came from means nothing.
		descriptor.remove(MidletDescriptor.MIDLET_JAR_URL);
		descriptor.verify();

		String appName = descriptor.getName();
		String versionName = descriptor.getVersion();
		String archiveName = sanitizeFileName(appName);
		String applicationId = packageOverride != null && !packageOverride.trim().isEmpty()
				? packageOverride.trim()
				: derivePackageName(appName);
		int versionCode = versionCodeOverride != null ? versionCodeOverride : deriveVersionCode(versionName);

		List<String> titles = new ArrayList<>();
		for (MidletDescriptor.Midlet midlet : descriptor.getMidlets()) {
			titles.add(midlet.title.isEmpty() ? midlet.mainClass : midlet.title);
		}

		File classesJar = new File(outputDir, CLASSES_JAR);
		File resDir = new File(outputDir, RES_DIR);
		File proguardFile = new File(outputDir, PROGUARD_FILE);
		String stamp = buildStamp(jar, source, applicationId, versionCode, configJson);

		File summaryFile = new File(outputDir, SUMMARY_FILE);
		if (!isUpToDate(stamp, classesJar, proguardFile)) {
			Files.createDirectories(outputDir.toPath());
			deleteRecursively(new File(outputDir, STAMP_FILE));
			deleteRecursively(resDir);
			Repacked repacked = repack(jar, descriptor, classesJar, configJson);
			writeProguardRules(proguardFile, repacked);
			if (!writeIcon(jar, descriptor, resDir)) {
				deleteRecursively(resDir);
			}
			if (repacked.unreadableClasses > 0) {
				warnings.add(repacked.unreadableClasses + " class(es) could not be scanned for dependencies;"
						+ " if R8 reports missing classes, add the needed -dontwarn rules to app/proguard-midlet.pro.");
			}
			Files.write(summaryFile.toPath(), describe(jar, repacked).getBytes(StandardCharsets.UTF_8));
			Files.write(new File(outputDir, STAMP_FILE).toPath(), stamp.getBytes(StandardCharsets.UTF_8));
		}
		String summary = summaryFile.isFile()
				? new String(Files.readAllBytes(summaryFile.toPath()), StandardCharsets.UTF_8)
				: jar.getName();

		// Read from the suite as it arrived rather than from the repacked jar: what matters
		// is what its author wrote, not what instrumenting it added.
		PortPermissions.Detection permissions = PortPermissions.detect(
				descriptor.get(MidletDescriptor.MIDLET_PERMISSIONS),
				descriptor.get(MidletDescriptor.MIDLET_PERMISSIONS_OPT), jar);

		return new MidletImport(descriptor, classesJar, resDir.isDirectory() ? resDir : null, proguardFile,
				appName, archiveName, applicationId, versionName, versionCode, titles, summary,
				listResources(classesJar), permissions);
	}

	// --- source resolution -------------------------------------------------------------

	/**
	 * Finds the JAR a JAD describes: next to the JAD if it is there, otherwise from the
	 * {@code MIDlet-Jar-URL} the JAD exists to point at — the same place the emulator fetches
	 * it from when installing the suite on a device.
	 */
	private File resolveJarForJad(File jadFile, MidletDescriptor jad) throws IOException {
		File dir = jadFile.getAbsoluteFile().getParentFile();
		List<String> candidates = new ArrayList<>();
		String url = jad.getJarUrl();
		String urlFileName = null;
		if (url != null) {
			String path = url.replace('\\', '/');
			int query = path.indexOf('?');
			if (query != -1) {
				path = path.substring(0, query);
			}
			int slash = path.lastIndexOf('/');
			urlFileName = slash == -1 ? path : path.substring(slash + 1);
			if (!urlFileName.isEmpty() && !url.contains("://")) {
				candidates.add(urlFileName);
			}
		}
		String base = jadFile.getName();
		candidates.add(base.substring(0, base.length() - 4) + ".jar");

		for (String candidate : candidates) {
			File file = new File(dir, candidate);
			if (file.isFile()) {
				return file;
			}
		}
		if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
			String name = urlFileName == null || urlFileName.isEmpty() || !urlFileName.endsWith(".jar")
					? base.substring(0, base.length() - 4) + ".jar"
					: urlFileName;
			return download(url, new File(outputDir, "download/" + sanitizeFileName(name)),
					jad.get(MIDLET_JAR_SIZE));
		}
		throw new IOException("Can't find the JAR for '" + jadFile.getName() + "'. Looked for "
				+ String.join(", ", candidates) + " in " + dir + ", and the JAD declares no "
				+ MidletDescriptor.MIDLET_JAR_URL + " to fetch it from.");
	}

	/**
	 * Fetches the suite's JAR, keeping it so that later builds do not go back to the network.
	 * The declared size is checked only to report a surprise: what actually decides whether
	 * the download is a suite is whether it opens as a JAR with a manifest.
	 */
	private File download(String url, File target, String declaredSize) throws IOException {
		if (target.isFile() && target.length() > 0) {
			return target;
		}
		Files.createDirectories(target.getAbsoluteFile().getParentFile().toPath());
		warnings.add("Downloading the suite from " + url);
		File temp = new File(target.getPath() + ".part");
		HttpURLConnection connection;
		try {
			connection = (HttpURLConnection) new URI(url).toURL().openConnection();
		} catch (URISyntaxException | IllegalArgumentException e) {
			throw new IOException("Malformed " + MidletDescriptor.MIDLET_JAR_URL + ": " + url, e);
		}
		try {
			connection.setInstanceFollowRedirects(true);
			connection.setConnectTimeout(15_000);
			connection.setReadTimeout(180_000);
			int status = connection.getResponseCode();
			if (status != HttpURLConnection.HTTP_OK) {
				throw new IOException("Can't download " + url + ": HTTP " + status + " "
						+ connection.getResponseMessage());
			}
			try (InputStream in = connection.getInputStream();
				 OutputStream out = Files.newOutputStream(temp.toPath())) {
				copy(in, out);
			}
		} finally {
			connection.disconnect();
		}
		if (declaredSize != null) {
			try {
				long expected = Long.parseLong(declaredSize.trim());
				if (expected != temp.length()) {
					warnings.add("Downloaded " + temp.length() + " bytes but the JAD declares "
							+ MIDLET_JAR_SIZE + ": " + expected);
				}
			} catch (NumberFormatException ignored) {
				// A malformed size attribute is not a reason to refuse the download.
			}
		}
		Files.deleteIfExists(target.toPath());
		Files.move(temp.toPath(), target.toPath());
		return target;
	}

	/** A JAD sitting next to the JAR carries attributes the JAR manifest may lack. */
	private File siblingJad(File jarFile) {
		String base = jarFile.getName();
		int dot = base.lastIndexOf('.');
		String stem = dot == -1 ? base : base.substring(0, dot);
		File dir = jarFile.getAbsoluteFile().getParentFile();
		for (String ext : new String[]{".jad", ".JAD"}) {
			File candidate = new File(dir, stem + ext);
			if (candidate.isFile()) {
				return candidate;
			}
		}
		return null;
	}

	private MidletDescriptor readJarManifest(File jar) throws IOException {
		try (ZipFile zip = new ZipFile(jar)) {
			ZipEntry entry = zip.getEntry("META-INF/MANIFEST.MF");
			if (entry == null) {
				entry = zip.getEntry("meta-inf/manifest.mf");
			}
			if (entry == null) {
				throw new IOException("No META-INF/MANIFEST.MF in " + jar.getName() + " — not a MIDlet suite?");
			}
			try (InputStream in = zip.getInputStream(entry)) {
				return MidletDescriptor.parse(new String(readAll(in), StandardCharsets.UTF_8));
			}
		}
	}

	// --- repacking ---------------------------------------------------------------------

	/** Packages produced while rewriting the suite, used to build its keep rules. */
	private static final class Repacked {
		/** Binary names of the suite's classes, in the order they were packed. */
		final List<String> classNames = new ArrayList<>();
		final Set<String> ownPackages = new TreeSet<>();
		final Set<String> referencedPackages = new TreeSet<>();
		int resourceCount;
		int unreadableClasses;
	}

	private Repacked repack(File jar, MidletDescriptor descriptor, File target, String configJson)
			throws IOException {
		Repacked result = new Repacked();
		Set<String> written = new HashSet<>();
		Files.createDirectories(target.getAbsoluteFile().getParentFile().toPath());
		try (ZipFile zip = new ZipFile(jar);
			 ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target.toPath()))) {
			// A stable entry order keeps the jar — and so the build — reproducible.
			List<? extends ZipEntry> entries = sortedEntries(zip);
			for (ZipEntry entry : entries) {
				if (entry.isDirectory()) {
					continue;
				}
				String entryName = normalizeEntryName(entry.getName());
				if (entryName == null) {
					warnings.add("Skipped entry with unusable path: " + entry.getName());
					continue;
				}
				// The suite's own META-INF is replaced: signatures no longer apply to
				// repacked classes, and AGP never packages a dependency's manifest.
				if (entryName.toUpperCase(Locale.ROOT).startsWith("META-INF/")) {
					continue;
				}
				if (!written.add(entryName)) {
					warnings.add("Skipped duplicate entry: " + entryName);
					continue;
				}
				byte[] data;
				try (InputStream in = zip.getInputStream(entry)) {
					data = readAll(in);
				}
				if (entryName.endsWith(".class")) {
					collectReferences(data, result);
					int slash = entryName.lastIndexOf('/');
					if (slash > 0) {
						result.ownPackages.add(entryName.substring(0, slash).replace('/', '.'));
					}
					result.classNames.add(entryName
							.substring(0, entryName.length() - ".class".length())
							.replace('/', '.'));
					data = instrument(data, entryName);
				} else {
					result.resourceCount++;
				}
				writeEntry(out, entryName, data);
			}
			writeEntry(out, MANIFEST_RESOURCE, descriptor.toManifestText().getBytes(StandardCharsets.UTF_8));
			if (configJson != null) {
				writeEntry(out, CONFIG_RESOURCE, configJson.getBytes(StandardCharsets.UTF_8));
			}
		}
		if (result.classNames.isEmpty()) {
			throw new IOException("No classes found in " + jar.getName() + " — not a MIDlet suite?");
		}
		result.referencedPackages.removeAll(result.ownPackages);
		return result;
	}

	/**
	 * The suite entries that will be packaged as Java resources. Read back from the repacked
	 * jar rather than remembered from the repack, so it is right whether or not this build
	 * had to redo the import.
	 */
	private static List<String> listResources(File classesJar) throws IOException {
		List<String> paths = new ArrayList<>();
		try (ZipFile zip = new ZipFile(classesJar)) {
			for (ZipEntry entry : sortedEntries(zip)) {
				if (!entry.isDirectory() && !entry.getName().endsWith(".class")) {
					paths.add(entry.getName());
				}
			}
		}
		return paths;
	}

	/**
	 * Of {@code patterns}, the ones that match a file the suite ships.
	 *
	 * <p>Android's packaging step drops a stock list of paths — root-level {@code *.txt},
	 * licence files and the like — which is right for a library's stray files and wrong for a
	 * MIDlet, whose data files sit at the root of its JAR under whatever names it chose. The
	 * caller reclaims only the patterns named here, so a suite gets its files back without
	 * the rest of the list being switched off with it.
	 *
	 * <p>Patterns follow Android's syntax: {@code *} within one path segment, {@code **}
	 * across segments, and a leading {@code /} anchoring at the archive root.
	 */
	public static Set<String> resourcePatternsInUse(Collection<String> patterns, Collection<String> paths) {
		Set<String> matched = new TreeSet<>();
		for (String pattern : patterns) {
			Pattern regex = toRegex(pattern);
			for (String path : paths) {
				if (regex.matcher(path).matches()) {
					matched.add(pattern);
					break;
				}
			}
		}
		return matched;
	}

	private static Pattern toRegex(String pattern) {
		boolean anchored = pattern.startsWith("/");
		String body = anchored ? pattern.substring(1) : pattern;
		StringBuilder sb = new StringBuilder();
		if (!anchored) {
			// An unanchored pattern applies at any depth, not just at the root.
			sb.append("(?:.*/)?");
		}
		for (int i = 0; i < body.length(); i++) {
			char c = body.charAt(i);
			if (c == '*') {
				if (i + 1 < body.length() && body.charAt(i + 1) == '*') {
					sb.append(".*");
					i++;
				} else {
					sb.append("[^/]*");
				}
			} else if (c == '?') {
				sb.append("[^/]");
			} else {
				sb.append(Pattern.quote(String.valueOf(c)));
			}
		}
		return Pattern.compile(sb.toString());
	}

	private static String describe(File jar, Repacked repacked) {
		return jar.getName() + ": " + repacked.classNames.size() + " class(es), "
				+ repacked.resourceCount + " resource(s)";
	}

	private byte[] instrument(byte[] data, String entryName) {
		try {
			return AndroidProducer.instrument(data, entryName);
		} catch (RuntimeException e) {
			// Obfuscated suites sometimes carry classes whose name and path disagree. The
			// emulator's dexer rejects those; here the original bytes still dex, so the
			// suite builds and only loses the rewrites for that one class.
			warnings.add("Kept " + entryName + " unrewritten: " + e);
			return data;
		}
	}

	private static List<? extends ZipEntry> sortedEntries(ZipFile zip) {
		List<ZipEntry> entries = new ArrayList<>();
		for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
			entries.add(e.nextElement());
		}
		entries.sort(Comparator.comparing(ZipEntry::getName));
		return entries;
	}

	/**
	 * Rejects paths that would escape the archive or that Android can't package, and strips
	 * the leading slashes and backslash separators some MIDlet build tools emit.
	 */
	private static String normalizeEntryName(String name) {
		String normalized = name.replace('\\', '/');
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}
		while (normalized.contains("//")) {
			normalized = normalized.replace("//", "/");
		}
		if (normalized.isEmpty()) {
			return null;
		}
		for (String segment : normalized.split("/")) {
			if (segment.equals(".") || segment.equals("..")) {
				return null;
			}
		}
		return normalized;
	}

	private static void writeEntry(ZipOutputStream out, String name, byte[] data) throws IOException {
		ZipEntry entry = new ZipEntry(name);
		// A fixed timestamp keeps repeated imports byte-identical.
		entry.setTime(0L);
		out.putNextEntry(entry);
		out.write(data);
		out.closeEntry();
	}

	// --- keep rules --------------------------------------------------------------------

	private void writeProguardRules(File file, Repacked repacked) throws IOException {
		StringBuilder sb = new StringBuilder();
		sb.append("# Generated by the MIDlet importer. Do not edit; rerun the build instead.\n\n");
		sb.append("# MIDlets routinely reach for classes by name (Class.forName, resource-driven\n");
		sb.append("# class tables), so the suite's own classes are kept whole and unrenamed.\n");
		sb.append("# Each is named outright rather than matched by a pattern: a wildcard broad\n");
		sb.append("# enough to cover an obfuscated suite's default-package classes would pin the\n");
		sb.append("# emulator and its libraries too, and shrinking would stop happening at all.\n");
		for (String className : repacked.classNames) {
			sb.append("-keep class ").append(className).append(" { *; }\n");
		}
		sb.append("\n# A binary suite may reference optional APIs this emulator does not implement.\n");
		sb.append("# Those references are unreachable at runtime, but R8 fails the build unless the\n");
		sb.append("# missing classes are declared uninteresting.\n");
		for (String pkg : repacked.referencedPackages) {
			sb.append("-dontwarn ").append(pkg).append(".**\n");
		}
		Files.write(file.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Collects the packages a class refers to, by walking its constant pool. Any name that
	 * turns up is only used to emit a {@code -dontwarn}, so over-collecting costs nothing
	 * while missing a reference could fail the build.
	 */
	private static void collectReferences(byte[] classData, Repacked result) {
		try {
			ConstantPool pool = ConstantPool.read(classData);
			for (String utf8 : pool.strings) {
				for (String internalName : extractInternalNames(utf8)) {
					int slash = internalName.lastIndexOf('/');
					if (slash > 0) {
						result.referencedPackages.add(internalName.substring(0, slash).replace('/', '.'));
					}
				}
			}
		} catch (RuntimeException e) {
			// A pool we can't read only means a less precise -dontwarn set.
			result.unreadableClasses++;
		}
	}

	/** Pulls {@code a/b/C} names out of a constant pool string, be it a name or a descriptor. */
	private static List<String> extractInternalNames(String value) {
		List<String> names = new ArrayList<>();
		if (value.isEmpty()) {
			return names;
		}
		if (value.indexOf('(') == -1 && value.indexOf(';') == -1 && value.indexOf('/') > 0
				&& isInternalName(value)) {
			names.add(value);
			return names;
		}
		int index = 0;
		while ((index = value.indexOf('L', index)) != -1) {
			int end = value.indexOf(';', index);
			if (end == -1) {
				break;
			}
			String candidate = value.substring(index + 1, end);
			if (candidate.indexOf('/') > 0 && isInternalName(candidate)) {
				names.add(candidate);
			}
			index = end + 1;
		}
		return names;
	}

	private static boolean isInternalName(String value) {
		if (value.startsWith("/") || value.endsWith("/")) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '/' || c == '$' || c == '_' || Character.isLetterOrDigit(c)) {
				continue;
			}
			return false;
		}
		return true;
	}

	// --- icon --------------------------------------------------------------------------

	private boolean writeIcon(File jar, MidletDescriptor descriptor, File resDir) throws IOException {
		String iconPath = descriptor.getIconPath();
		if (iconPath == null) {
			return false;
		}
		byte[] data = null;
		try (ZipFile zip = new ZipFile(jar)) {
			ZipEntry entry = zip.getEntry(iconPath);
			if (entry == null) {
				// Suites are inconsistent about the case and the leading slash of icon paths.
				for (ZipEntry candidate : sortedEntries(zip)) {
					String name = normalizeEntryName(candidate.getName());
					if (name != null && name.equalsIgnoreCase(iconPath)) {
						entry = candidate;
						break;
					}
				}
			}
			if (entry != null) {
				try (InputStream in = zip.getInputStream(entry)) {
					data = readAll(in);
				}
			}
		}
		if (data == null) {
			warnings.add("Icon '" + iconPath + "' is declared but missing from the JAR; using the default icon.");
			return false;
		}
		if (!MidletIcons.generate(data, resDir)) {
			warnings.add("Icon '" + iconPath + "' could not be decoded; using the default icon.");
			return false;
		}
		return true;
	}

	// --- naming ------------------------------------------------------------------------

	/**
	 * Escapes a suite name for use as an Android string resource value.
	 *
	 * <p>Suite names are free text, and an apostrophe or a leading {@code @} in one is enough
	 * to make aapt2 reject the generated resource. Only the escapes aapt2 defines are applied:
	 * the XML entities are the responsibility of whoever writes the document.
	 */
	public static String escapeStringResource(String value) {
		String escaped = value
				.replace("\\", "\\\\")
				.replace("'", "\\'")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\t", "\\t");
		if (escaped.startsWith("@") || escaped.startsWith("?")) {
			// A leading @ or ? would otherwise read as a reference to another resource.
			escaped = "\\" + escaped;
		}
		return escaped;
	}

	/**
	 * Naming is shared with the on-device repackager: the same suite has to come out with the
	 * same application id either way, or a port rebuilt on a device would install beside the
	 * one built by Gradle rather than updating it.
	 */
	static String sanitizeFileName(String name) {
		return PortNaming.sanitizeFileName(name);
	}

	static String derivePackageName(String name) {
		return PortNaming.derivePackageName(name);
	}

	static int deriveVersionCode(String version) {
		return PortNaming.deriveVersionCode(version);
	}

	/** A short, stable fingerprint, used to notice when packaged settings have changed. */
	private static String shortDigest(String value) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 4; i++) {
				sb.append(String.format("%02x", hash[i]));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-1 is required by every JVM", e);
		}
	}

	// --- incremental support -----------------------------------------------------------

	private String buildStamp(File jar, File source, String applicationId, int versionCode,
							  String configJson) {
		return "format=" + FORMAT_VERSION
				+ "\nsource=" + source.getAbsolutePath()
				+ "\njar=" + jar.getAbsolutePath()
				+ "\nsize=" + jar.length()
				+ "\nmtime=" + jar.lastModified()
				+ "\nsourceMtime=" + source.lastModified()
				+ "\napplicationId=" + applicationId
				+ "\nversionCode=" + versionCode
				+ "\nconfig=" + (configJson == null ? "" : shortDigest(configJson))
				+ "\n";
	}

	private boolean isUpToDate(String stamp, File classesJar, File proguardFile) throws IOException {
		File stampFile = new File(outputDir, STAMP_FILE);
		if (!stampFile.isFile() || !classesJar.isFile() || !proguardFile.isFile()) {
			return false;
		}
		return stamp.equals(new String(Files.readAllBytes(stampFile.toPath()), StandardCharsets.UTF_8));
	}

	private static void deleteRecursively(File file) throws IOException {
		if (!file.exists()) {
			return;
		}
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				deleteRecursively(child);
			}
		}
		Files.deleteIfExists(file.toPath());
	}

	private static byte[] readAll(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, in.available()));
		copy(in, out);
		return out.toByteArray();
	}

	private static void copy(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[8192];
		int read;
		while ((read = in.read(buffer)) > 0) {
			out.write(buffer, 0, read);
		}
	}
}
