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
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * What a MIDlet suite is going to ask the phone for, worked out before it is packaged.
 *
 * <p>The emulator declares every permission any MIDlet might ever want, because it does not
 * know which MIDlet it will be asked to run. A port does know: there is exactly one suite in
 * it, and it is sitting right there to be read. So a port has no business asking for the
 * camera because some other game might have wanted one.
 *
 * <p>Two things are read. The first is what the suite says about itself - MIDP has the
 * author declare {@code MIDlet-Permissions} in the descriptor - and the second is what the
 * suite's own bytecode gives away. The second matters more: the declaration is optional and
 * routinely left out, so plenty of real suites say nothing at all while plainly opening
 * sockets. Every type a class mentions and every string constant it holds is spelled out in
 * its constant pool, which is where a call to {@code Connector.open("btspp://...")} or a
 * reference to {@code javax.microedition.location} becomes visible without running anything.
 *
 * <p>Obfuscation does not hide any of this. A suite's own class names get shortened, but a
 * reference to a platform class it does not own cannot be renamed and still resolve.
 *
 * <p>Detection is deliberately lopsided. Reading a suite as needing something it never uses
 * costs a permission that goes unexercised; missing one costs a feature. So a match is
 * anything that so much as mentions the API, and only the permissions Android puts to the
 * user in a dialog are ever dropped on the strength of it - see {@link #TRIMMABLE}. The
 * install-time ones stay whatever the suite looks like, because they cost the user nothing
 * to carry and a wrong guess about them would be silent.
 */
public final class PortPermissions {
	private static final String P = "android.permission.";

	public static final String CAMERA = P + "CAMERA";
	public static final String RECORD_AUDIO = P + "RECORD_AUDIO";
	public static final String FINE_LOCATION = P + "ACCESS_FINE_LOCATION";
	public static final String COARSE_LOCATION = P + "ACCESS_COARSE_LOCATION";
	public static final String BLUETOOTH_CONNECT = P + "BLUETOOTH_CONNECT";
	public static final String BLUETOOTH_SCAN = P + "BLUETOOTH_SCAN";
	public static final String BLUETOOTH_ADVERTISE = P + "BLUETOOTH_ADVERTISE";
	public static final String WRITE_EXTERNAL_STORAGE = P + "WRITE_EXTERNAL_STORAGE";
	public static final String READ_EXTERNAL_STORAGE = P + "READ_EXTERNAL_STORAGE";
	public static final String POST_NOTIFICATIONS = P + "POST_NOTIFICATIONS";
	public static final String INTERNET = P + "INTERNET";
	public static final String VIBRATE = P + "VIBRATE";

	/**
	 * The permissions a port may drop when nothing in the suite points at them.
	 *
	 * <p>These are the ones Android stops and asks about, so carrying a spurious one is a
	 * question put to the user that the suite was never going to answer for. Dropping one
	 * wrongly is survivable: the emulator asks for its permissions at the point of use and
	 * already copes with being told no, and an undeclared permission fails that request the
	 * same way a declined one does.
	 *
	 * <p>Everything outside this set is left alone however the scan comes out. The
	 * install-time permissions - {@code INTERNET}, {@code VIBRATE}, the legacy Bluetooth
	 * pair - are never shown to anyone, so trimming them would buy nothing and could quietly
	 * cost a suite its network.
	 */
	public static final Set<String> TRIMMABLE = Collections.unmodifiableSet(new LinkedHashSet<>(
			Arrays.asList(CAMERA, RECORD_AUDIO, FINE_LOCATION, COARSE_LOCATION,
					BLUETOOTH_CONNECT, BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE,
					WRITE_EXTERNAL_STORAGE, READ_EXTERNAL_STORAGE, POST_NOTIFICATIONS)));

	/**
	 * Permissions that belong to the emulator's own shell rather than to any MIDlet.
	 *
	 * <p>A port has no app list, no file picker and nothing to install, because the build
	 * removes those activities. What is left cannot reach the code these guard, so they go
	 * whatever the suite turns out to need.
	 */
	public static final Set<String> SHELL_ONLY = Collections.unmodifiableSet(new LinkedHashSet<>(
			Arrays.asList(P + "REQUEST_INSTALL_PACKAGES", P + "MANAGE_DOCUMENTS")));

	/** Everything Bluetooth needs, which on current Android includes finding devices by radio. */
	private static final String[] BLUETOOTH = {BLUETOOTH_CONNECT, BLUETOOTH_SCAN,
			BLUETOOTH_ADVERTISE, FINE_LOCATION};

	/**
	 * What a string in a suite's constant pool implies, if it contains {@code needle}.
	 *
	 * <p>A class name appears in the pool both bare ({@code javax/microedition/location/Criteria})
	 * and inside descriptors ({@code (L...;)V}), so matching a package prefix as a substring
	 * catches both without having to take the pool apart any further. Literals passed to
	 * {@code Connector.open} and {@code Player.getControl} sit in the same pool and are
	 * matched the same way.
	 */
	private static final String[][] SIGNALS = {
			// Networking. Detected for the record rather than to act on: INTERNET is not
			// trimmable, so a suite that builds its URLs a piece at a time loses nothing.
			rule("javax/microedition/io/HttpConnection", INTERNET),
			rule("javax/microedition/io/HttpsConnection", INTERNET),
			rule("javax/microedition/io/SocketConnection", INTERNET),
			rule("javax/microedition/io/ServerSocketConnection", INTERNET),
			rule("javax/microedition/io/UDPDatagramConnection", INTERNET),
			rule("http://", INTERNET),
			rule("https://", INTERNET),
			rule("socket://", INTERNET),
			rule("ssl://", INTERNET),
			rule("datagram://", INTERNET),

			// Bluetooth and OBEX, both of which run over the same radio.
			rule("javax/bluetooth/", BLUETOOTH),
			rule("javax/obex/", BLUETOOTH),
			rule("btspp://", BLUETOOTH),
			rule("btl2cap://", BLUETOOTH),
			rule("btgoep://", BLUETOOTH),

			// JSR-179. Nothing else in MIDP reaches a fix.
			rule("javax/microedition/location/", FINE_LOCATION, COARSE_LOCATION),

			// The camera is reached by opening a capture locator; a VideoControl on its own
			// is just as likely to be playing a video file, so it is not evidence.
			rule("capture://video", CAMERA),
			rule("capture://image", CAMERA),
			rule("javax/microedition/amms/control/camera/", CAMERA),

			// The microphone, likewise, plus the control that reads from it. The needle
			// covers both the class reference and the string getControl is given.
			rule("capture://audio", RECORD_AUDIO),
			rule("RecordControl", RECORD_AUDIO),

			// JSR-75 file access, and Siemens' older take on the same idea.
			rule("FileConnection", WRITE_EXTERNAL_STORAGE),
			rule("javax/microedition/io/file/", WRITE_EXTERNAL_STORAGE),
			rule("com/siemens/mp/io/file/", WRITE_EXTERNAL_STORAGE),
			rule("file://", WRITE_EXTERNAL_STORAGE),

			// Nokia's soft notifications are the only thing in the emulator that posts one.
			rule("com/nokia/mid/ui/SoftNotification", POST_NOTIFICATIONS),

			// Display.vibrate, and the vendor equivalents that predate it.
			rule("vibrate", VIBRATE),
			rule("startVibra", VIBRATE),
			rule("com/nokia/mid/ui/DeviceControl", VIBRATE),
			rule("com/samsung/util/Vibration", VIBRATE),
	};

	/**
	 * A locator built at runtime, where the scheme is a constant but the rest is not. Worth
	 * treating as both kinds of capture, but only when neither specific form was found.
	 */
	private static final String CAPTURE = "capture://";

	/** What the descriptor's own {@code MIDlet-Permissions} names mean, matched by prefix. */
	private static final String[][] DECLARED = {
			rule("javax.microedition.io.Connector.http", INTERNET),
			rule("javax.microedition.io.Connector.https", INTERNET),
			rule("javax.microedition.io.Connector.socket", INTERNET),
			rule("javax.microedition.io.Connector.serversocket", INTERNET),
			rule("javax.microedition.io.Connector.ssl", INTERNET),
			rule("javax.microedition.io.Connector.datagram", INTERNET),
			rule("javax.microedition.io.Connector.file", WRITE_EXTERNAL_STORAGE),
			rule("javax.microedition.io.file.FileConnection", WRITE_EXTERNAL_STORAGE),
			rule("javax.microedition.io.Connector.bluetooth", BLUETOOTH),
			rule("javax.microedition.io.Connector.obex", BLUETOOTH),
			rule("javax.bluetooth.", BLUETOOTH),
			rule("javax.obex.", BLUETOOTH),
			rule("javax.microedition.location.", FINE_LOCATION, COARSE_LOCATION),
			rule("javax.microedition.media.control.RecordControl", RECORD_AUDIO),
			rule("javax.microedition.media.control.VideoControl.getSnapshot", CAMERA),
			rule("javax.microedition.amms.control.camera", CAMERA),
	};

	/** One table row: what to look for, and what it means if found. */
	private static String[] rule(String needle, String... permissions) {
		String[] row = new String[permissions.length + 1];
		row[0] = needle;
		System.arraycopy(permissions, 0, row, 1, permissions.length);
		return row;
	}

	private PortPermissions() {
	}

	/** An Android permission the suite gave a reason to keep, and what that reason was. */
	public static final class Need {
		/** The Android permission name, in full. */
		public final String permission;
		/** What turned up in the suite: an API package, a locator, or a declared name. */
		public final Set<String> evidence;

		Need(String permission, Set<String> evidence) {
			this.permission = permission;
			this.evidence = Collections.unmodifiableSet(evidence);
		}
	}

	/** What a scan concluded, and what a manifest should do about it. */
	public static final class Detection {
		/** Every permission something in the suite pointed at. */
		public final Set<String> permissions;
		/** The same, each with the evidence behind it, in the order they were reached. */
		public final List<Need> needs;
		/** True when the suite declared {@code MIDlet-Permissions} of its own. */
		public final boolean declaredItsOwn;

		Detection(List<Need> needs, boolean declaredItsOwn) {
			this.needs = Collections.unmodifiableList(needs);
			this.declaredItsOwn = declaredItsOwn;
			Set<String> permissions = new LinkedHashSet<>();
			for (Need need : needs) {
				permissions.add(need.permission);
			}
			this.permissions = Collections.unmodifiableSet(permissions);
		}

		/**
		 * Of the permissions a manifest declares, the ones this port should not.
		 *
		 * <p>That is the prompted permissions nothing in the suite reached for, plus the ones
		 * only the emulator's own shell ever used. Anything this scan has no opinion about is
		 * left where it is.
		 */
		public List<String> removableFrom(Collection<String> declared) {
			List<String> removable = new ArrayList<>();
			for (String permission : declared) {
				if (SHELL_ONLY.contains(permission)
						|| (TRIMMABLE.contains(permission) && !permissions.contains(permission))) {
					removable.add(permission);
				}
			}
			return removable;
		}

		/**
		 * Everything a port could drop, without reference to any particular manifest.
		 *
		 * <p>For a build that states its removals up front rather than editing a manifest it
		 * can read: naming a permission that was not there costs nothing.
		 */
		public List<String> removable() {
			List<String> all = new ArrayList<>(TRIMMABLE);
			all.addAll(SHELL_ONLY);
			return removableFrom(all);
		}

		/**
		 * Permissions the suite needs that the manifest does not declare. A port cannot add
		 * one - that would need a resource compiler - so this is worth saying out loud rather
		 * than leaving as a feature that quietly does nothing.
		 */
		public List<String> missingFrom(Collection<String> declared) {
			List<String> missing = new ArrayList<>();
			for (String permission : permissions) {
				if (!declared.contains(permission)) {
					missing.add(permission);
				}
			}
			return missing;
		}
	}

	/**
	 * Works out what the suite in {@code suiteJar} will ask for.
	 *
	 * @param declared    the descriptor's {@code MIDlet-Permissions}, or null
	 * @param optional    its {@code MIDlet-Permissions-Opt}, or null
	 * @param suiteJar    the suite's own jar, as installed
	 */
	public static Detection detect(String declared, String optional, File suiteJar)
			throws IOException {
		Map<String, Set<String>> found = new LinkedHashMap<>();
		boolean declaredItsOwn = addDeclared(found, declared) | addDeclared(found, optional);
		scanJar(suiteJar, found);
		return toDetection(found, declaredItsOwn);
	}

	/** As {@link #detect(String, String, File)}, over class files already in hand. */
	public static Detection detect(String declared, String optional, Iterable<byte[]> classes) {
		Map<String, Set<String>> found = new LinkedHashMap<>();
		boolean declaredItsOwn = addDeclared(found, declared) | addDeclared(found, optional);
		for (byte[] classData : classes) {
			scanClass(classData, found);
		}
		return toDetection(found, declaredItsOwn);
	}

	private static Detection toDetection(Map<String, Set<String>> found, boolean declaredItsOwn) {
		List<Need> needs = new ArrayList<>(found.size());
		for (Map.Entry<String, Set<String>> entry : found.entrySet()) {
			needs.add(new Need(entry.getKey(), entry.getValue()));
		}
		return new Detection(needs, declaredItsOwn);
	}

	/** @return whether anything was declared at all, which is worth knowing on its own */
	private static boolean addDeclared(Map<String, Set<String>> found, String value) {
		if (value == null || value.trim().isEmpty()) {
			return false;
		}
		for (String name : value.split(",")) {
			name = name.trim();
			if (name.isEmpty()) {
				continue;
			}
			for (String[] rule : DECLARED) {
				if (name.startsWith(rule[0])) {
					record(found, rule, name);
				}
			}
		}
		return true;
	}

	private static void scanJar(File suiteJar, Map<String, Set<String>> found) throws IOException {
		if (suiteJar == null || !suiteJar.isFile()) {
			return;
		}
		try (ZipFile zip = new ZipFile(suiteJar)) {
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
					continue;
				}
				scanClass(readEntry(zip, entry), found);
			}
		}
	}

	private static byte[] readEntry(ZipFile zip, ZipEntry entry) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(
				entry.getSize() > 0 ? (int) entry.getSize() : 4096);
		try (InputStream in = zip.getInputStream(entry)) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = in.read(buffer)) > 0) {
				out.write(buffer, 0, read);
			}
		}
		return out.toByteArray();
	}

	private static void scanClass(byte[] classData, Map<String, Set<String>> found) {
		List<String> strings;
		try {
			strings = ConstantPool.read(classData).strings;
		} catch (RuntimeException e) {
			// A pool that won't parse is one class's worth of blind spot, not a reason to
			// give up on the rest - and the permissions it might have argued for are the
			// ones that stay by default anyway.
			return;
		}
		for (String value : strings) {
			boolean captured = false;
			for (String[] rule : SIGNALS) {
				if (value.contains(rule[0])) {
					record(found, rule, rule[0]);
					captured |= rule[0].startsWith(CAPTURE);
				}
			}
			if (!captured && value.contains(CAPTURE)) {
				// The scheme is a constant but what follows it is not, so this could be
				// either. Asking for both beats guessing wrong about one.
				record(found, rule(CAPTURE, CAMERA, RECORD_AUDIO), CAPTURE);
			}
		}
	}

	/** Files every permission of {@code rule} under {@code evidence}. */
	private static void record(Map<String, Set<String>> found, String[] rule, String evidence) {
		for (int i = 1; i < rule.length; i++) {
			Set<String> reasons = found.get(rule[i]);
			if (reasons == null) {
				reasons = new TreeSet<>();
				found.put(rule[i], reasons);
			}
			reasons.add(evidence);
		}
	}
}
