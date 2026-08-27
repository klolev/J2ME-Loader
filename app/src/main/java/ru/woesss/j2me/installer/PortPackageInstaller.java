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

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Hands a built port to Android's own installer.
 *
 * <p>The APK is streamed into an install session rather than passed as a file URI: the
 * session takes the bytes directly, so nothing has to be made readable to another app first.
 * Android still shows the user its own confirmation - this asks for an install, it does not
 * perform one.
 */
public class PortPackageInstaller {
	/** Action for the result Android sends back once the user has answered. */
	public static final String ACTION_INSTALL_RESULT =
			"ru.playsoftware.j2meloader.INSTALL_RESULT";

	private final Context context;

	public PortPackageInstaller(Context context) {
		this.context = context.getApplicationContext();
	}

	/**
	 * Asks Android to install {@code apk}, showing the user its confirmation dialog.
	 *
	 * @param label what the app is called, for the session's own progress notification
	 */
	public void install(File apk, String label) throws IOException {
		PackageInstaller installer = context.getPackageManager().getPackageInstaller();
		PackageInstaller.SessionParams params =
				new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
		params.setAppLabel(label);
		int sessionId = installer.createSession(params);
		try (PackageInstaller.Session session = installer.openSession(sessionId)) {
			try (InputStream in = new FileInputStream(apk);
				 OutputStream out = session.openWrite("port", 0, apk.length())) {
				byte[] buffer = new byte[65536];
				int read;
				while ((read = in.read(buffer)) > 0) {
					out.write(buffer, 0, read);
				}
				session.fsync(out);
			}
			Intent intent = new Intent(ACTION_INSTALL_RESULT).setPackage(context.getPackageName());
			PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, intent,
					PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
			session.commit(pending.getIntentSender());
		} catch (IOException e) {
			installer.abandonSession(sessionId);
			throw e;
		}
	}
}
