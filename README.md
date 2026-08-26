# J2ME-Loader 

[![Build Status](https://app.bitrise.io/app/d9254be52c74982a/status.svg?token=DIHxcpAPIg0VXSHpeXsHHA&branch=master)](https://app.bitrise.io/app/d9254be52c74982a)
[![Crowdin](https://d322cqt584bo4o.cloudfront.net/j2me-loader/localized.svg)](https://crowdin.com/project/j2me-loader)
[![GitHub release](https://img.shields.io/github/release/nikita36078/J2ME-Loader.svg)](https://github.com/nikita36078/J2ME-Loader/releases)

J2ME-Loader is a J2ME emulator for Android. It supports most 2D and 3D games (including Mascot Capsule 3D ones). Emulator has a virtual keyboard, individual settings for each application, scaling support.
This project is a fork of [J2meLoader](https://github.com/NaikSoftware/J2meLoader).  
Special thanks to [woesss](https://github.com/woesss), the author of [JL-Mod](https://github.com/woesss/JL-Mod), for creating open-source Mascot Capsule implementation.

System requirements: Android 4.0+  
[4PDA discussion](https://4pda.to/forum/index.php?showtopic=824201)  
[XDA-Developers](https://forum.xda-developers.com/android/apps-games/app-j2me-loader-t3777889)  
[EmuGen wiki](https://emulation.gametechwiki.com/index.php/J2ME_Loader)  
[Discord](https://discord.gg/Ag4rcpz)  
[Automated builds](https://install.appcenter.ms/users/nikita36078/apps/j2me-loader/distribution_groups/testers)

<a href="https://play.google.com/store/apps/details?id=ru.playsoftware.j2meloader">
<img src="https://play.google.com/intl/en_us/badges/images/generic/en_badge_web_generic.png" height="75"></a>
<a href="https://f-droid.org/app/ru.playsoftware.j2meloader">
<img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="75"></a>

## Compatibility
[List of the tested Java Games (Touchscreen)](https://github.com/nikita36078/J2ME-Loader/wiki/List-of-Tested-Java-Games-(Touchscreen))  
[List of the tested Java Games (Non Touchscreen)](https://github.com/nikita36078/J2ME-Loader/wiki/List-of-Tested-Java-Games-(Non-Touchscreen))  
[List of the Java Games with Bugs](https://github.com/nikita36078/J2ME-Loader/wiki/List-of-Java-Games-with-Bugs)

## Exporting a MIDlet as an APK

A single MIDlet suite can be built into a standalone Android app: the emulator and the game
in one APK, carrying the game's own name and icon and launching straight into it, with no app
list and no separate install step.

```sh
./export-apk.sh game.jar             # or a .jad, next to its .jar
```

The APK is written to `app/build/outputs/apk/midlet/release/`, signed with a key generated on
first use (`midlet-export.keystore`) so that it installs. Keep that file: an exported app can
only be updated in place by an APK signed with the same key. To sign with your own key
instead, put a `keystore.properties` in the project root.

A JAD whose `MIDlet-Jar-URL` points at a remote file is downloaded, the same way the emulator
fetches it when installing a suite on a device.

```sh
./export-apk.sh game.jad --debug                        # skip shrinking; builds faster
./export-apk.sh game.jar --package com.example.mygame   # instead of a derived id
./export-apk.sh game.jar --version-code 42              # instead of one from MIDlet-Version
```

The application id, version, app label and launcher icon all come from the suite descriptor.
Gradle can be driven directly if you would rather not use the script:

```sh
./gradlew assembleMidletRelease -Pmidlet=/path/to/game.jar
```

The same `midlet` flavor still builds a port from J2ME **sources** placed in `app/src/midlet`
when no `-Pmidlet` is given.

## Tips
 - Enabling filtering in some cases can greatly reduce performance. Disable this option if game is too slow.
 - Image flickering issues can be fixed by enabling the "Immediate processing mode" option.

## Screenshots

<img src="/screenshots/screen.jpg" width="288" height="512"> <img src="/screenshots/screen2.jpg" width="288" height="512">
<img src="/screenshots/screen3.jpg" width="288" height="512"> <img src="/screenshots/screen4.jpg" width="288" height="512">
* For more screenshots check out the [wiki](https://emulation.gametechwiki.com/index.php/J2ME_Loader#Screenshots)

## License
> Copyright 2017-2024 Nikita Shakarun.
> Licensed under the [Apache License, Version 2.0.](http://www.apache.org/licenses/LICENSE-2.0)  
> (See the [LICENSE](https://github.com/nikita36078/J2ME-Loader/blob/master/LICENSE) file for the whole license text.)
