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
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;

import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ru.playsoftware.j2meloader.R;
import ru.woesss.j2me.jar.Descriptor;

/**
 * What a built port actually is, read back from the APK itself.
 *
 * <p>Everything here comes from Android's own parser rather than from what the build meant to
 * produce, so what the user is shown is what will be installed. That matters most for the
 * permissions: a game asking for the camera or the microphone deserves saying out loud before
 * the install begins rather than after.
 *
 * <p>The build narrows that list to what the suite was found to use, so how much it left out
 * is worth showing too - it is the difference between an app that asks for the camera and an
 * emulator that always did.
 */
public class PortSummary {
	public final String label;
	public final String packageName;
	public final String versionName;
	public final long apkSize;
	public final List<String> permissions;
	public final List<String> midlets;
	/** How many the build left out because nothing in the suite reached for them. */
	public final int trimmedPermissions;

	private PortSummary(String label, String packageName, String versionName, long apkSize,
						List<String> permissions, List<String> midlets, int trimmedPermissions) {
		this.label = label;
		this.packageName = packageName;
		this.versionName = versionName;
		this.apkSize = apkSize;
		this.permissions = permissions;
		this.midlets = midlets;
		this.trimmedPermissions = trimmedPermissions;
	}

	/**
	 * Reads {@code apk} back the way the installer will.
	 *
	 * @return the summary, or null if Android cannot parse the APK - which is itself worth
	 * knowing before handing it over
	 */
	public static PortSummary read(Context context, PortBuilder.Result built,
								   Descriptor descriptor) {
		File apk = built.apk;
		PackageManager pm = context.getPackageManager();
		PackageInfo info = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_PERMISSIONS);
		if (info == null) {
			return null;
		}
		List<String> permissions = new ArrayList<>();
		if (info.requestedPermissions != null) {
			for (String permission : info.requestedPermissions) {
				permissions.add(describePermission(pm, permission));
			}
			Collections.sort(permissions);
		}
		List<String> midlets = new ArrayList<>();
		for (int i = 1; ; i++) {
			String value = descriptor.getAttrs().get("MIDlet-" + i);
			if (value == null) {
				break;
			}
			int comma = value.indexOf(',');
			midlets.add(comma == -1 ? value.trim() : value.substring(0, comma).trim());
		}
		return new PortSummary(descriptor.getName(), info.packageName, info.versionName,
				apk.length(), permissions, midlets, built.removedPermissions.size());
	}

	/** The wording Android itself uses for a permission, falling back to its bare name. */
	private static String describePermission(PackageManager pm, String permission) {
		try {
			PermissionInfo info = pm.getPermissionInfo(permission, 0);
			CharSequence label = info.loadLabel(pm);
			if (label != null && label.length() > 0) {
				return label.toString();
			}
		} catch (Exception ignored) {
			// An unknown permission is still worth naming, just not in words.
		}
		int dot = permission.lastIndexOf('.');
		return dot == -1 ? permission : permission.substring(dot + 1);
	}

	/** The whole of it, laid out for the dialog that asks whether to go ahead. */
	public CharSequence describe(Context context) {
		SpannableStringBuilder text = new SpannableStringBuilder();
		append(text, context.getString(R.string.port_app_name), label);
		append(text, context.getString(R.string.port_package), packageName);
		if (versionName != null) {
			append(text, context.getString(R.string.port_version), versionName);
		}
		append(text, context.getString(R.string.port_size), formatSize(apkSize));
		if (!midlets.isEmpty()) {
			append(text, context.getString(R.string.port_midlets),
					android.text.TextUtils.join(", ", midlets));
		}
		if (!permissions.isEmpty()) {
			text.append('\n');
			int start = text.length();
			text.append(context.getString(R.string.port_permissions));
			text.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, text.length(),
					Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			text.append('\n');
			for (String permission : permissions) {
				text.append("• ").append(permission).append('\n');
			}
		}
		if (trimmedPermissions > 0) {
			text.append('\n').append(context.getResources().getQuantityString(
					R.plurals.port_permissions_trimmed, trimmedPermissions, trimmedPermissions));
		}
		return text;
	}

	private static void append(SpannableStringBuilder text, String label, String value) {
		int start = text.length();
		text.append(label);
		text.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, text.length(),
				Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		text.append(' ').append(value).append('\n');
	}

	private static String formatSize(long bytes) {
		DecimalFormat format = new DecimalFormat("#.#");
		if (bytes >= 1024 * 1024) {
			return format.format(bytes / (1024f * 1024f)) + " MB";
		}
		if (bytes >= 1024) {
			return format.format(bytes / 1024f) + " KB";
		}
		return bytes + " B";
	}
}
