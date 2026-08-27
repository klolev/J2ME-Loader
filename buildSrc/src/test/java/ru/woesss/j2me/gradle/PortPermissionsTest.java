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
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import ru.woesss.j2me.apk.PortPermissions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers reading a suite's permissions off the suite itself.
 *
 * <p>The suites here are compiled on the spot rather than kept as fixtures, because what is
 * being tested is what ends up in a class file's constant pool - which is exactly what
 * writing the class with a real assembler produces and what hand-written bytes would not.
 */
public class PortPermissionsTest {

	@Test
	public void findsNothingInASuiteThatOnlyDraws() {
		PortPermissions.Detection detection = detect(classCalling(
				"javax/microedition/lcdui/Graphics", "drawImage", "(Ljavax/microedition/lcdui/Image;III)V"));

		assertEquals(Collections.emptySet(), detection.permissions);
		assertFalse(detection.declaredItsOwn);
	}

	@Test
	public void readsTheApisASuiteReferences() {
		PortPermissions.Detection detection = detect(classCalling(
				"javax/microedition/location/LocationProvider", "getLocation", "(I)V"));

		assertTrue(detection.permissions.contains(PortPermissions.FINE_LOCATION));
		assertTrue(detection.permissions.contains(PortPermissions.COARSE_LOCATION));
		assertEquals(Collections.singleton("javax/microedition/location/"),
				evidenceFor(detection, PortPermissions.FINE_LOCATION));
	}

	@Test
	public void readsTheLocatorsASuitePasses() {
		PortPermissions.Detection detection = detect(classWithConstant("capture://audio"));

		assertTrue(detection.permissions.contains(PortPermissions.RECORD_AUDIO));
		// A microphone locator says nothing about a camera.
		assertFalse(detection.permissions.contains(PortPermissions.CAMERA));
	}

	@Test
	public void assumesBothWhenOnlyTheCaptureSchemeIsConstant() {
		PortPermissions.Detection detection = detect(classWithConstant("capture://"));

		assertTrue(detection.permissions.contains(PortPermissions.RECORD_AUDIO));
		assertTrue(detection.permissions.contains(PortPermissions.CAMERA));
	}

	@Test
	public void bluetoothNeedsTheRadioAndTheFixThatFindsIt() {
		PortPermissions.Detection detection = detect(classWithConstant("btspp://localhost:1101"));

		assertTrue(detection.permissions.containsAll(Arrays.asList(
				PortPermissions.BLUETOOTH_CONNECT, PortPermissions.BLUETOOTH_SCAN,
				PortPermissions.BLUETOOTH_ADVERTISE, PortPermissions.FINE_LOCATION)));
	}

	@Test
	public void readsWhatTheDescriptorDeclares() {
		PortPermissions.Detection detection = PortPermissions.detect(
				"javax.microedition.io.Connector.http, javax.microedition.location.Location",
				null, Collections.<byte[]>emptyList());

		assertTrue(detection.declaredItsOwn);
		assertTrue(detection.permissions.contains(PortPermissions.INTERNET));
		assertTrue(detection.permissions.contains(PortPermissions.FINE_LOCATION));
		assertEquals(Collections.singleton("javax.microedition.location.Location"),
				evidenceFor(detection, PortPermissions.COARSE_LOCATION));
	}

	@Test
	public void anOptionalPermissionCountsJustTheSame() {
		PortPermissions.Detection detection = PortPermissions.detect(
				null, "javax.microedition.media.control.RecordControl",
				Collections.<byte[]>emptyList());

		assertTrue(detection.declaredItsOwn);
		assertTrue(detection.permissions.contains(PortPermissions.RECORD_AUDIO));
	}

	@Test
	public void removesThePromptedPermissionsNothingAskedFor() {
		PortPermissions.Detection detection = detect(classWithConstant("http://example.com/scores"));

		List<String> declared = Arrays.asList(
				PortPermissions.INTERNET, PortPermissions.VIBRATE, PortPermissions.CAMERA,
				PortPermissions.RECORD_AUDIO, PortPermissions.FINE_LOCATION,
				"android.permission.REQUEST_INSTALL_PACKAGES");

		List<String> removable = detection.removableFrom(declared);

		assertTrue(removable.contains(PortPermissions.CAMERA));
		assertTrue(removable.contains(PortPermissions.RECORD_AUDIO));
		assertTrue(removable.contains(PortPermissions.FINE_LOCATION));
		// Only the emulator's own installer ever wanted this one.
		assertTrue(removable.contains("android.permission.REQUEST_INSTALL_PACKAGES"));
		// Install-time permissions are invisible to the user, so there is nothing to gain by
		// second-guessing them.
		assertFalse(removable.contains(PortPermissions.INTERNET));
		assertFalse(removable.contains(PortPermissions.VIBRATE));
	}

	@Test
	public void keepsAPromptedPermissionTheSuiteReachedFor() {
		PortPermissions.Detection detection = detect(classWithConstant("capture://video"));

		List<String> removable = detection.removableFrom(
				Arrays.asList(PortPermissions.CAMERA, PortPermissions.RECORD_AUDIO));

		assertFalse(removable.contains(PortPermissions.CAMERA));
		assertTrue(removable.contains(PortPermissions.RECORD_AUDIO));
	}

	@Test
	public void namesAPermissionTheManifestCannotOffer() {
		PortPermissions.Detection detection = detect(classWithConstant("capture://video"));

		assertEquals(Collections.singletonList(PortPermissions.CAMERA),
				detection.missingFrom(Collections.singletonList(PortPermissions.INTERNET)));
	}

	@Test
	public void leavesAlonePermissionsItHasNoOpinionAbout() {
		PortPermissions.Detection detection = detect(classWithConstant("nothing interesting"));

		assertEquals(Collections.emptyList(), detection.removableFrom(
				Arrays.asList("android.permission.WAKE_LOCK",
						"com.android.launcher.permission.INSTALL_SHORTCUT")));
	}

	@Test
	public void readsASuiteFromItsJar() throws IOException {
		File jar = File.createTempFile("suite", ".jar");
		jar.deleteOnExit();
		try (OutputStream out = new FileOutputStream(jar)) {
			java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out);
			zip.putNextEntry(new java.util.zip.ZipEntry("Game.class"));
			zip.write(classWithConstant("btspp://localhost:1101"));
			zip.closeEntry();
			// A resource with a class-like name must not be mistaken for one.
			zip.putNextEntry(new java.util.zip.ZipEntry("sprites.png"));
			zip.write(new byte[]{1, 2, 3, 4});
			zip.closeEntry();
			zip.finish();
		}

		PortPermissions.Detection detection = PortPermissions.detect(null, null, jar);

		assertTrue(detection.permissions.contains(PortPermissions.BLUETOOTH_CONNECT));
	}

	@Test
	public void aJarThatIsNotThereIsNotAFailure() throws IOException {
		PortPermissions.Detection detection =
				PortPermissions.detect(null, null, new File("no/such/suite.jar"));

		assertEquals(Collections.emptySet(), detection.permissions);
	}

	@Test
	public void anUnreadableClassCostsOnlyItself() throws IOException {
		List<byte[]> classes = Arrays.asList(
				new byte[]{0, 1, 2, 3}, classWithConstant("capture://audio"));

		PortPermissions.Detection detection = PortPermissions.detect(null, null, classes);

		assertTrue(detection.permissions.contains(PortPermissions.RECORD_AUDIO));
	}

	// --- helpers -----------------------------------------------------------------------

	private static PortPermissions.Detection detect(byte[]... classes) {
		return PortPermissions.detect(null, null, Arrays.asList(classes));
	}

	private static java.util.Set<String> evidenceFor(PortPermissions.Detection detection,
													 String permission) {
		for (PortPermissions.Need need : detection.needs) {
			if (need.permission.equals(permission)) {
				return need.evidence;
			}
		}
		return Collections.emptySet();
	}

	/** A class whose only distinguishing feature is one string constant it loads. */
	private static byte[] classWithConstant(String constant) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V1_2, Opcodes.ACC_PUBLIC, "Game", null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
				"run", "()Ljava/lang/String;", null, null);
		mv.visitCode();
		mv.visitLdcInsn(constant);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(1, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A class that calls one method on one type, the way a suite reaches an API. */
	private static byte[] classCalling(String owner, String name, String descriptor) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V1_2, Opcodes.ACC_PUBLIC, "Game", null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
				"run", "(L" + owner + ";)V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, name, descriptor, false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(4, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}
