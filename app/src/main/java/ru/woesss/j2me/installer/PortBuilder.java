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

package ru.woesss.j2me.installer;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.IOException;

import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.woesss.j2me.apk.ApkRepackager;
import ru.woesss.j2me.apk.PortNaming;
import ru.woesss.j2me.apk.PortSigner;
import ru.woesss.j2me.jar.Descriptor;

/**
 * Turns an installed suite into an APK that can be installed like any other app.
 *
 * <p>Everything this needs, the emulator already wrote when it installed the suite: the dexed
 * classes, the suite's own jar, its descriptor and its icon. So a port is not a second
 * conversion - it is the same conversion, packaged differently.
 */
public class PortBuilder {
	/** The template an APK is stamped out of; see {@link #getTemplate}. */
	public static final String TEMPLATE_NAME = "port-template.apk";

	private static final String KEYSTORE_NAME = "port-signing.p12";
	/** A local key protects nothing, so this only has to be the same every time. */
	private static final char[] KEYSTORE_PASSWORD = "j2meloader".toCharArray();

	private final Context context;

	public PortBuilder(Context context) {
		this.context = context.getApplicationContext();
	}

	/** Whether this device can build a port at all. */
	public static boolean isSupported() {
		return Build.VERSION.SDK_INT >= PortSigner.MIN_SDK;
	}

	/**
	 * Where the template lives. It is not shipped inside the emulator, which would double the
	 * download for everyone who never exports anything; it is fetched or copied here once.
	 */
	public static File getTemplate() {
		return new File(Config.getEmulatorDir(), TEMPLATE_NAME);
	}

	/**
	 * Builds and signs a port of the suite installed at {@code appDir}.
	 *
	 * @param appDir    the suite's directory, as the emulator laid it out
	 * @param descriptor the suite descriptor, for the port's name and version
	 * @return a signed APK, ready to hand to the package installer
	 */
	public File build(File appDir, Descriptor descriptor) throws IOException {
		if (!isSupported()) {
			throw new IOException(context.getString(
					ru.playsoftware.j2meloader.R.string.port_needs_newer_android));
		}
		File template = getTemplate();
		if (!template.isFile()) {
			throw new IOException(context.getString(
					ru.playsoftware.j2meloader.R.string.port_template_missing, template.getPath()));
		}

		String name = descriptor.getName();
		ApkRepackager.Port port = new ApkRepackager.Port();
		port.applicationId = PortNaming.derivePackageName(name);
		port.label = name;
		port.versionName = descriptor.getVersion();
		port.dex = new File(appDir, Config.MIDLET_DEX_FILE);
		port.suiteJar = new File(appDir, Config.MIDLET_RES_FILE);
		port.descriptor = FileUtils.getBytes(new File(appDir, Config.MIDLET_MANIFEST_FILE));

		File config = new File(Config.getConfigsDir(), appDir.getName() + Config.MIDLET_CONFIG_FILE);
		if (config.isFile()) {
			// Whatever the player already tuned for this suite here is what the port should
			// open with, rather than asking them the same questions a second time.
			port.settings = FileUtils.getBytes(config);
		}
		File icon = new File(appDir, Config.MIDLET_ICON_FILE);
		if (icon.isFile()) {
			port.icon = FileUtils.getBytes(icon);
		}

		File workDir = new File(context.getCacheDir(), "ports");
		if (!workDir.isDirectory() && !workDir.mkdirs()) {
			throw new IOException("Can't create directory: " + workDir);
		}
		String fileName = PortNaming.sanitizeFileName(name);
		File unsigned = new File(workDir, fileName + "-unsigned.apk");
		File signed = new File(workDir, fileName + ".apk");
		//noinspection ResultOfMethodCallIgnored
		signed.delete();

		ApkRepackager repackager = new ApkRepackager(template);
		try {
			repackager.repackage(port, unsigned);
			new PortSigner(new File(context.getFilesDir(), KEYSTORE_NAME), KEYSTORE_PASSWORD)
					.sign(unsigned, signed);
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Can't build a port of " + name, e);
		} finally {
			//noinspection ResultOfMethodCallIgnored
			unsigned.delete();
		}
		return signed;
	}
}
