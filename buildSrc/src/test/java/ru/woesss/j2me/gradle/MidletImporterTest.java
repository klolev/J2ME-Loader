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

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MidletImporterTest {
	private static final String MANIFEST = ""
			+ "Manifest-Version: 1.0\r\n"
			+ "MIDlet-Name: Space Game\r\n"
			+ "MIDlet-Vendor: Acme\r\n"
			+ "MIDlet-Version: 1.2.3\r\n"
			+ "MicroEdition-Configuration: CLDC-1.1\r\n"
			+ "MicroEdition-Profile: MIDP-2.0\r\n"
			+ "MIDlet-Icon: /icon.png\r\n"
			+ "MIDlet-1: Space Game, /icon.png, com.acme.game.Main\r\n";

	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private File outputDir;

	@Before
	public void setUp() throws IOException {
		outputDir = folder.newFolder("import");
	}

	@Test
	public void importsClassesResourcesAndDescriptor() throws IOException {
		File jar = writeSuiteJar("game.jar", MANIFEST, defaultEntries());

		MidletImport result = new MidletImporter(outputDir).importSuite(jar, null, null);

		assertEquals("Space Game", result.appName);
		assertEquals("1.2.3", result.versionName);
		assertEquals(1_002_003, result.versionCode);
		assertEquals("com.example.androidlet.space_game", result.applicationId);
		assertEquals("Space_Game", result.archiveName);
		assertEquals(1, result.midletTitles.size());
		assertEquals("Space Game", result.midletTitles.get(0));

		Map<String, byte[]> packed = readJar(result.classesJar);
		assertTrue(packed.keySet().toString(), packed.containsKey("com/acme/game/Main.class"));
		assertTrue(packed.keySet().toString(), packed.containsKey("Obfuscated.class"));
		assertEquals("level data", new String(packed.get("res/level.dat"), StandardCharsets.UTF_8));

		// The suite manifest must be readable from the APK: MicroLoader looks it up here
		// when the emulator is built without its own app list.
		byte[] descriptor = packed.get(MidletImporter.MANIFEST_RESOURCE);
		assertNotNull("suite descriptor missing from the repacked jar", descriptor);
		MidletDescriptor reparsed = MidletDescriptor.parse(new String(descriptor, StandardCharsets.UTF_8));
		assertEquals("Space Game", reparsed.getName());
		assertEquals("com.acme.game.Main", reparsed.getMidlets().get(0).mainClass);
	}

	@Test
	public void rewritesBytecodeTheSameWayTheEmulatorDoes() throws IOException {
		File jar = writeSuiteJar("game.jar", MANIFEST, defaultEntries());

		MidletImport result = new MidletImporter(outputDir).importSuite(jar, null, null);

		Map<String, byte[]> packed = readJar(result.classesJar);
		String rewritten = constantPoolText(packed.get("com/acme/game/Main.class"));
		// System.getProperty must reach the emulator's own property table, and Timer must
		// become the emulator's replacement - the same rewrites the on-device dexer applies.
		assertTrue(rewritten, rewritten.contains("javax/microedition/shell/MidletSystem"));
		assertTrue(rewritten, rewritten.contains("javax/microedition/shell/custom/Timer"));
		assertFalse(rewritten, rewritten.contains("java/util/Timer"));
	}

	@Test
	public void makesSubIntReturnsSurviveAndroidsVerifier() throws IOException {
		Map<String, byte[]> entries = defaultEntries();
		entries.put("Accessors.class", subIntReturnClass());
		File jar = writeSuiteJar("game.jar", MANIFEST, entries);

		MidletImport result = new MidletImporter(outputDir).importSuite(jar, null, null);

		// A boolean read straight out of a short[] is what Android rejects the class for;
		// after the rewrite the value goes through a comparison, so what reaches the return
		// is a plain 0 or 1 rather than a short.
		byte[] rewritten = readJar(result.classesJar).get("Accessors.class");
		assertNotNull(rewritten);
		assertEquals("[ICONST_0, SALOAD, IFEQ, ICONST_1, IRETURN, ICONST_0, IRETURN]",
				opcodesOf(rewritten, "flag", "()Z").toString());
		// The narrowing types do have a conversion that says exactly what they are.
		assertEquals("[ICONST_0, SALOAD, I2B, IRETURN]", opcodesOf(rewritten, "small", "()B").toString());
		// An int return was always fine and must not grow instructions it does not need.
		assertEquals("[ICONST_0, SALOAD, IRETURN]", opcodesOf(rewritten, "count", "()I").toString());
	}

	@Test
	public void dropsTheSuiteSignatureAndItsOriginalManifest() throws IOException {
		Map<String, byte[]> entries = defaultEntries();
		entries.put("META-INF/ACME.SF", "signature".getBytes(StandardCharsets.UTF_8));
		entries.put("META-INF/ACME.RSA", "signature".getBytes(StandardCharsets.UTF_8));
		File jar = writeSuiteJar("game.jar", MANIFEST, entries);

		MidletImport result = new MidletImporter(outputDir).importSuite(jar, null, null);

		for (String name : readJar(result.classesJar).keySet()) {
			assertFalse(name, name.startsWith("META-INF/"));
		}
	}

	@Test
	public void mergesASiblingJadOverTheJarManifest() throws IOException {
		File jar = writeSuiteJar("game.jar", MANIFEST, defaultEntries());
		Files.write(new File(jar.getParentFile(), "game.jad").toPath(), (""
				+ "MIDlet-Name: Space Game\r\n"
				+ "MIDlet-Vendor: Acme\r\n"
				+ "MIDlet-Version: 2.0.0\r\n"
				+ "MIDlet-Jar-URL: game.jar\r\n"
				+ "MIDlet-Jar-Size: 1234\r\n"
				+ "Server-URL: http://example.invalid\r\n").getBytes(StandardCharsets.UTF_8));

		MidletImport result = new MidletImporter(outputDir).importSuite(jar, null, null);

		assertEquals("2.0.0", result.versionName);
		assertEquals(2_000_000, result.versionCode);
		assertEquals("http://example.invalid", result.descriptor.get("Server-URL"));
		// The JAR is inside the APK now, so a URL pointing at it would only mislead.
		assertNull(result.descriptor.get(MidletDescriptor.MIDLET_JAR_URL));
	}

	@Test
	public void importsAJadByFindingItsJar() throws IOException {
		writeSuiteJar("game.jar", MANIFEST, defaultEntries());
		File jad = folder.newFile("descriptor.jad");
		Files.write(jad.toPath(), (""
				+ "MIDlet-Name: Space Game\r\n"
				+ "MIDlet-Vendor: Acme\r\n"
				+ "MIDlet-Version: 1.2.3\r\n"
				+ "MIDlet-Jar-URL: game.jar\r\n"
				+ "MIDlet-Jar-Size: 1234\r\n").getBytes(StandardCharsets.UTF_8));

		MidletImport result = new MidletImporter(outputDir).importSuite(jad, null, null);

		assertEquals("Space Game", result.appName);
		assertTrue(readJar(result.classesJar).containsKey("com/acme/game/Main.class"));
	}

	@Test
	public void explainsItselfWhenAJadHasNoJarToPointAt() throws IOException {
		File jad = folder.newFile("orphan.jad");
		Files.write(jad.toPath(), (""
				+ "MIDlet-Name: Space Game\r\n"
				+ "MIDlet-Vendor: Acme\r\n"
				+ "MIDlet-Version: 1.0\r\n").getBytes(StandardCharsets.UTF_8));

		try {
			new MidletImporter(outputDir).importSuite(jad, null, null);
			fail("Expected the missing JAR to be reported");
		} catch (IOException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("orphan.jar"));
			assertTrue(e.getMessage(), e.getMessage().contains(MidletDescriptor.MIDLET_JAR_URL));
		}
	}

	@Test
	public void buildsLauncherIconsFromTheSuiteIcon() throws IOException {
		File jar = writeSuiteJar("game.jar", MANIFEST, defaultEntries());

		MidletImport result = new MidletImporter(outputDir).importSuite(jar, null, null);

		assertNotNull("no launcher resources were generated", result.resDir);
		BufferedImage mdpi = ImageIO.read(new File(result.resDir, "mipmap-mdpi/ic_launcher.png"));
		assertEquals(48, mdpi.getWidth());
		assertEquals(48, mdpi.getHeight());
		BufferedImage xxxhdpi = ImageIO.read(new File(result.resDir, "mipmap-xxxhdpi/ic_launcher.png"));
		assertEquals(192, xxxhdpi.getWidth());
		// The adaptive foreground is a 108dp canvas at the same density as the 48dp icon.
		BufferedImage foreground = ImageIO.read(new File(result.resDir, "mipmap-mdpi/ic_launcher_foreground.png"));
		assertEquals(108, foreground.getWidth());
		assertTrue(new File(result.resDir, "mipmap-anydpi-v26/ic_launcher.xml").isFile());

		String colors = new String(Files.readAllBytes(
				new File(result.resDir, "values/ic_launcher_background.xml").toPath()), StandardCharsets.UTF_8);
		// The test icon is opaque red to the edges, so that is what the backdrop should be.
		assertTrue(colors, colors.contains("#FF0000"));
	}

	@Test
	public void fallsBackToTheDefaultIconWhenTheSuiteIconIsMissing() throws IOException {
		Map<String, byte[]> entries = defaultEntries();
		entries.remove("icon.png");
		File jar = writeSuiteJar("game.jar", MANIFEST, entries);

		MidletImporter importer = new MidletImporter(outputDir);
		MidletImport result = importer.importSuite(jar, null, null);

		assertNull(result.resDir);
		assertTrue(importer.getWarnings().toString(),
				importer.getWarnings().toString().contains("icon.png"));
	}

	@Test
	public void keepsTheSuiteClassesFromBeingShrunkOrRenamed() throws IOException {
		File jar = writeSuiteJar("game.jar", MANIFEST, defaultEntries());

		MidletImport result = new MidletImporter(outputDir).importSuite(jar, null, null);

		String rules = new String(Files.readAllBytes(result.proguardFile.toPath()), StandardCharsets.UTF_8);
		assertTrue(rules, rules.contains("-keep class com.acme.game.Main { *; }"));
		// Obfuscated suites put their classes in the default package.
		assertTrue(rules, rules.contains("-keep class Obfuscated { *; }"));
		// Every class is named outright. A bare '*' would read as "every class there is",
		// pinning the emulator and its libraries and defeating shrinking altogether.
		assertFalse(rules, rules.contains("-keep class * "));
		assertFalse(rules, rules.contains("-keep class ** "));
		// An unimplemented optional API must not fail the R8 step.
		assertTrue(rules, rules.contains("-dontwarn com.nokia.mid.ui.**"));
		assertFalse(rules, rules.contains("-dontwarn com.acme.game.**"));
	}

	@Test
	public void reusesAnUnchangedImport() throws IOException {
		File jar = writeSuiteJar("game.jar", MANIFEST, defaultEntries());

		MidletImport first = new MidletImporter(outputDir).importSuite(jar, null, null);
		long stamp = first.classesJar.lastModified();
		assertTrue(first.classesJar.setLastModified(stamp - 10_000L));
		long marked = first.classesJar.lastModified();

		new MidletImporter(outputDir).importSuite(jar, null, null);

		assertEquals("an unchanged suite should not be repacked", marked, first.classesJar.lastModified());
	}

	@Test
	public void rebuildsWhenTheSuiteChanges() throws IOException {
		File jar = writeSuiteJar("game.jar", MANIFEST, defaultEntries());
		MidletImport first = new MidletImporter(outputDir).importSuite(jar, null, null);
		assertFalse(readJar(first.classesJar).containsKey("res/extra.dat"));

		Map<String, byte[]> entries = defaultEntries();
		entries.put("res/extra.dat", "more".getBytes(StandardCharsets.UTF_8));
		writeSuiteJar("game.jar", MANIFEST, entries);

		MidletImport second = new MidletImporter(outputDir).importSuite(jar, null, null);

		assertTrue(readJar(second.classesJar).containsKey("res/extra.dat"));
	}

	@Test
	public void honoursExplicitPackageAndVersionCode() throws IOException {
		File jar = writeSuiteJar("game.jar", MANIFEST, defaultEntries());

		MidletImport result = new MidletImporter(outputDir).importSuite(jar, "org.acme.space", 42);

		assertEquals("org.acme.space", result.applicationId);
		assertEquals(42, result.versionCode);
	}

	@Test
	public void rejectsSomethingThatIsNotASuite() throws IOException {
		File jar = folder.newFile("empty.jar");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar.toPath()))) {
			out.putNextEntry(new ZipEntry("readme.txt"));
			out.write("nothing to see".getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}

		try {
			new MidletImporter(outputDir).importSuite(jar, null, null);
			fail("Expected a jar without a manifest to be rejected");
		} catch (IOException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("MANIFEST.MF"));
		}
	}

	@Test
	public void derivesUsablePackageNames() {
		assertEquals("com.example.androidlet.space_game", MidletImporter.derivePackageName("Space Game"));
		assertEquals("com.example.androidlet.bounce", MidletImporter.derivePackageName("  Bounce!  "));
		assertEquals("com.example.androidlet.x3d_demo", MidletImporter.derivePackageName("X3D (demo)"));
		// A name with no Latin letters leaves nothing to transliterate, and a name that
		// reduces to a Java keyword cannot be a package segment; both fall back to a digest.
		assertTrue(MidletImporter.derivePackageName("Тетрис")
				.startsWith("com.example.androidlet.midlet_"));
		assertTrue(MidletImporter.derivePackageName("2048")
				.startsWith("com.example.androidlet.midlet_"));
		assertTrue(MidletImporter.derivePackageName("new").startsWith("com.example.androidlet.midlet_"));
		// The fallback has to be stable, or every build would install a second copy.
		assertEquals(MidletImporter.derivePackageName("Тетрис"), MidletImporter.derivePackageName("Тетрис"));
	}

	@Test
	public void derivesOrderedVersionCodes() {
		assertEquals(1_002_003, MidletImporter.deriveVersionCode("1.2.3"));
		assertEquals(1_000_000, MidletImporter.deriveVersionCode("1"));
		assertEquals(1_010_000, MidletImporter.deriveVersionCode("1.10"));
		assertTrue(MidletImporter.deriveVersionCode("1.2.4") > MidletImporter.deriveVersionCode("1.2.3"));
		assertTrue(MidletImporter.deriveVersionCode("2.0") > MidletImporter.deriveVersionCode("1.99.99"));
		// Android rejects a version code of zero, and suites do carry odd version strings.
		assertEquals(1, MidletImporter.deriveVersionCode("0"));
		assertEquals(1, MidletImporter.deriveVersionCode("beta"));
		assertEquals(1, MidletImporter.deriveVersionCode(null));
	}

	@Test
	public void listsTheSuiteFilesThatGetPackagedAsResources() throws IOException {
		File jar = writeSuiteJar("game.jar", MANIFEST, defaultEntries());

		MidletImport result = new MidletImporter(outputDir).importSuite(jar, null, null);

		assertTrue(result.resourcePaths.toString(), result.resourcePaths.contains("res/level.dat"));
		assertTrue(result.resourcePaths.toString(), result.resourcePaths.contains("icon.png"));
		assertFalse(result.resourcePaths.toString(), result.resourcePaths.contains("Obfuscated.class"));
	}

	@Test
	public void reclaimsOnlyTheExclusionsThatCoverASuiteFile() {
		// The stock Android exclusion list, in the shapes that matter here.
		List<String> excludes = Arrays.asList("/*.txt", "/LICENSE", "/META-INF/MANIFEST.MF",
				"/META-INF/*.SF", "**/*.kotlin_module", "/*.properties");

		Set<String> inUse = MidletImporter.resourcePatternsInUse(excludes,
				Arrays.asList("gamedata.txt", "res/level.dat", "sounds/theme.mid"));

		// Only the pattern covering the suite's own root-level .txt is given up; the licence
		// and signature rules stay on, so a library's stray files still cannot collide.
		assertEquals(Collections.singleton("/*.txt"), inUse);
	}

	@Test
	public void anchorsExclusionPatternsTheWayAndroidDoes() {
		// A leading slash pins the pattern to the archive root...
		assertTrue(MidletImporter.resourcePatternsInUse(
				Collections.singletonList("/*.txt"), Collections.singletonList("notes.txt")).size() == 1);
		assertTrue(MidletImporter.resourcePatternsInUse(
				Collections.singletonList("/*.txt"), Collections.singletonList("docs/notes.txt")).isEmpty());
		// ...'*' stops at a separator, '**' crosses it, and an unanchored pattern floats.
		assertTrue(MidletImporter.resourcePatternsInUse(
				Collections.singletonList("/**/*.txt"), Collections.singletonList("a/b/notes.txt")).size() == 1);
		assertTrue(MidletImporter.resourcePatternsInUse(
				Collections.singletonList("*.kotlin_module"), Collections.singletonList("a/b/x.kotlin_module")).size() == 1);
		assertTrue(MidletImporter.resourcePatternsInUse(
				Collections.singletonList("/META-INF/*.SF"), Collections.singletonList("res/level.dat")).isEmpty());
	}

	@Test
	public void escapesSuiteNamesForTheAppLabel() {
		// aapt2 rejects a bare apostrophe, and treats a leading @ or ? as a reference.
		assertEquals("Bob\\'s Quest", MidletImporter.escapeStringResource("Bob's Quest"));
		assertEquals("\\\"Hero\\\"", MidletImporter.escapeStringResource("\"Hero\""));
		assertEquals("\\@Home", MidletImporter.escapeStringResource("@Home"));
		assertEquals("A\\\\B", MidletImporter.escapeStringResource("A\\B"));
		// Ampersands and angle brackets are XML, escaped by whoever writes the document.
		assertEquals("Chip & Dale", MidletImporter.escapeStringResource("Chip & Dale"));
	}

	@Test
	public void derivesUsableArchiveNames() {
		assertEquals("Space_Game", MidletImporter.sanitizeFileName("Space Game"));
		assertEquals("ACDC", MidletImporter.sanitizeFileName("AC/DC"));
		assertEquals("midlet", MidletImporter.sanitizeFileName("??"));
	}

	// --- helpers -----------------------------------------------------------------------

	private Map<String, byte[]> defaultEntries() throws IOException {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("com/acme/game/Main.class", midletClass("com/acme/game/Main"));
		entries.put("Obfuscated.class", midletClass("Obfuscated"));
		entries.put("res/level.dat", "level data".getBytes(StandardCharsets.UTF_8));
		entries.put("icon.png", iconPng());
		return entries;
	}

	private File writeSuiteJar(String name, String manifest, Map<String, byte[]> entries) throws IOException {
		File jar = new File(folder.getRoot(), name);
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar.toPath()))) {
			out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
			out.write(manifest.getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				out.putNextEntry(new ZipEntry(entry.getKey()));
				out.write(entry.getValue());
				out.closeEntry();
			}
		}
		return jar;
	}

	/**
	 * A class shaped like a real MIDlet body: it reads a system property, creates a
	 * {@link java.util.Timer} and touches an API the emulator may not implement.
	 */
	private static byte[] midletClass(String internalName) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V1_2, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();

		MethodVisitor start = cw.visitMethod(Opcodes.ACC_PUBLIC, "startApp", "()V", null, null);
		start.visitCode();
		start.visitLdcInsn("microedition.platform");
		start.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperty",
				"(Ljava/lang/String;)Ljava/lang/String;", false);
		start.visitInsn(Opcodes.POP);
		start.visitTypeInsn(Opcodes.NEW, "java/util/Timer");
		start.visitInsn(Opcodes.DUP);
		start.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/Timer", "<init>", "()V", false);
		start.visitInsn(Opcodes.POP);
		start.visitMethodInsn(Opcodes.INVOKESTATIC, "com/nokia/mid/ui/DeviceControl", "setLights",
				"(II)V", false);
		start.visitInsn(Opcodes.RETURN);
		start.visitMaxs(0, 0);
		start.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * A class in the shape old MIDlet compilers produced: a value read from a {@code short[]}
	 * handed straight back from methods declared to return narrower types.
	 */
	private static byte[] subIntReturnClass() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V1_2, Opcodes.ACC_PUBLIC, "Accessors", null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PRIVATE, "s", "[S", null, null).visitEnd();
		for (String[] method : new String[][]{{"flag", "()Z"}, {"small", "()B"}, {"count", "()I"}}) {
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method[0], method[1], null, null);
			mv.visitCode();
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitFieldInsn(Opcodes.GETFIELD, "Accessors", "s", "[S");
			mv.visitInsn(Opcodes.ICONST_0);
			mv.visitInsn(Opcodes.SALOAD);
			mv.visitInsn(Opcodes.IRETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** The opcodes of one method, from the array load onwards, named for readability. */
	private static List<String> opcodesOf(byte[] classData, String method, String descriptor) {
		List<String> opcodes = new ArrayList<>();
		new ClassReader(classData).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String desc,
											 String signature, String[] exceptions) {
				if (!name.equals(method) || !desc.equals(descriptor)) {
					return null;
				}
				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					public void visitInsn(int opcode) {
						opcodes.add(OPCODE_NAMES.get(opcode));
					}

					@Override
					public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
						opcodes.add(OPCODE_NAMES.get(opcode));
					}
				};
			}
		}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return opcodes;
	}

	private static final Map<Integer, String> OPCODE_NAMES = new HashMap<Integer, String>() {{
		put(Opcodes.SALOAD, "SALOAD");
		put(Opcodes.IFEQ, "IFEQ");
		put(Opcodes.ICONST_0, "ICONST_0");
		put(Opcodes.ICONST_1, "ICONST_1");
		put(Opcodes.IRETURN, "IRETURN");
		put(Opcodes.I2B, "I2B");
		put(Opcodes.I2C, "I2C");
		put(Opcodes.I2S, "I2S");
	}};

	/** A 16x16 opaque icon, the size and shape a MIDlet suite usually ships. */
	private static byte[] iconPng() throws IOException {
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setColor(Color.RED);
		g.fillRect(0, 0, 16, 16);
		g.setColor(Color.WHITE);
		g.fillRect(4, 4, 8, 8);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private static Map<String, byte[]> readJar(File file) throws IOException {
		Map<String, byte[]> entries = new HashMap<>();
		try (ZipFile zip = new ZipFile(file)) {
			zip.stream().forEach(entry -> {
				if (entry.isDirectory()) {
					return;
				}
				try (InputStream in = zip.getInputStream(entry)) {
					ByteArrayOutputStream out = new ByteArrayOutputStream();
					byte[] buffer = new byte[8192];
					int read;
					while ((read = in.read(buffer)) > 0) {
						out.write(buffer, 0, read);
					}
					entries.put(entry.getName(), out.toByteArray());
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
		}
		return entries;
	}

	private static String constantPoolText(byte[] classData) {
		assertNotNull("class missing from the repacked jar", classData);
		return String.join("\n", ConstantPool.read(classData).strings);
	}
}
