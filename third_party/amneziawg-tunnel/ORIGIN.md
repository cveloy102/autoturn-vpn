Source: https://github.com/amnezia-vpn/amneziawg-android
Tag: v3.1.20260814 (commit 5c16489e2cd9ed3a0a7a27c7445bba5238132f86)

The Java tunnel sources and manifest are from the official Apache-2.0 project.
The native libraries in `src/main/jniLibs` were extracted from the official
APK asset `AmneziaWG-3.1.202060814.apk` for that tag. The Gradle build file is
local to AutoTurn so the module uses those version-matched prebuilt libraries.
AutoTurn also promotes the upstream GoBackend VpnService to a foreground service
on creation, so Android can keep the selected tunnel active in the background.
