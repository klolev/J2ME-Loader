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
import java.util.List;

import ru.woesss.j2me.apk.PortPermissions;

/**
 * Everything the {@code midlet} flavor needs in order to build an APK for one suite.
 * Produced by {@link MidletImporter}, consumed by {@code app/build.gradle}.
 */
public final class MidletImport {
	/** The suite's attributes, with any JAD overrides already merged in. */
	public final MidletDescriptor descriptor;

	/** Instrumented classes and suite resources, ready to be dexed and packaged by AGP. */
	public final File classesJar;

	/** Generated launcher icon resources, or null when the suite declares no usable icon. */
	public final File resDir;

	/** Keep rules that stop R8 from shrinking or renaming the suite's own classes. */
	public final File proguardFile;

	/** Suite name, used as the app label. */
	public final String appName;

	/** Sanitized {@code appName}, used for the APK file name. */
	public final String archiveName;

	public final String applicationId;
	public final String versionName;
	public final int versionCode;

	/** Names of the MIDlets in the suite, for build-time logging. */
	public final List<String> midletTitles;

	/** What the import pulled out of the suite, for build-time logging. */
	public final String summary;

	/** Paths of the suite entries that will be packaged as Java resources. */
	public final List<String> resourcePaths;

	/**
	 * What the suite was found to reach for.
	 *
	 * <p>The emulator declares every permission any MIDlet might want. This one is here to be
	 * read, so the port can declare what it uses instead - see {@link PortPermissions}.
	 */
	public final PortPermissions.Detection permissions;

	MidletImport(MidletDescriptor descriptor, File classesJar, File resDir, File proguardFile,
				 String appName, String archiveName, String applicationId,
				 String versionName, int versionCode, List<String> midletTitles, String summary,
				 List<String> resourcePaths, PortPermissions.Detection permissions) {
		this.descriptor = descriptor;
		this.classesJar = classesJar;
		this.resDir = resDir;
		this.proguardFile = proguardFile;
		this.appName = appName;
		this.archiveName = archiveName;
		this.applicationId = applicationId;
		this.versionName = versionName;
		this.versionCode = versionCode;
		this.midletTitles = midletTitles;
		this.summary = summary;
		this.resourcePaths = resourcePaths;
		this.permissions = permissions;
	}
}
