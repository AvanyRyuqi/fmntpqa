# Compendium for Android

An Android port of Compendium-NX: browse and install Persona 5 Royal mods and have them packed into one
`PATCH1.CPK` that the **Persona 5 Royal Android port** reads as a mod.

It reuses the Switch app's own core (`source/core`: CPK builder, mod-format detection, load order,
GameBanana client) unchanged, in a native library, with a new phone UI (Kotlin + Jetpack Compose).

```
android/
  app/src/main/cpp/        engine.cpp (the app's brain, JSON in/out), http_android.cpp, jni_bridge.cpp, CMakeLists.txt
  app/src/main/java/...    Native.java / NativeHttp.java (JNI + network), Kotlin UI (Compose)
  tests/                   host tests: run_host_tests.sh (no phone, no NDK needed)
```

## What it does

- **Browse** GameBanana (Switch mods and PC mods), search, sort, read a mod page, pick a file, install.
- **Install** from GameBanana, a GitHub release, a direct link, or an archive on the phone (.zip, .7z, .rar, bare .cpk).
  Variants/options are asked about; clashes with mods you already have are shown.
- **My mods**: switch mods on/off, change load order (top wins), remove, rebuild.
- Writes `…/Persona 5 Royal/mods/Compendium/romfs/CPK/PATCH1.CPK` (the folder is configurable in Settings).

### Left out (they only make sense on a Switch)
Atmosphère sysmodule check, SD-card/SimpleModManager handling, the p5rcbt mode, "phone link" QR, capturing files
from the running game, and cheats / exefs patches / Skyline plugins inside mods (reported and skipped).

## Using it
1. Install the APK and open it. Allow **access to manage all files** (needed to write into `Persona 5 Royal/mods`).
2. Settings → game version (the port runs the base game, `1.0.0`).
3. Install mods. Start the Persona 5 Royal port as usual.

> Whether the port picks up `romfs/CPK/PATCH1.CPK` from `mods/<name>/` depends on the port. Test with one small mod first.

## Building

**On GitHub:** push this repository and run the *Android APK* workflow (Actions tab). The APK is the run's artifact.
Set the optional signing secrets listed in `.github/workflows/android.yml` to keep one signing key across builds.

**Locally:** Android Studio (Ladybug or newer) → open the `android/` folder. It needs the NDK `27.2.12479018` and
CMake `3.22.1` (SDK Manager) and internet access the first time, because the native build downloads and compiles
libarchive 3.7.7 and xz 5.4.7 for the phone's architecture (arm64-v8a). Or from a terminal:

```
cd android
./gradlew assembleRelease       # app/build/outputs/apk/release/
```

## Tests without a phone
```
android/tests/run_host_tests.sh
```
Runs the Switch app's 37 unit tests and an engine test that installs mods from folders and checks the output layout.

## Licence
GNU GPL v3, like the original. See `LICENSE` and `THIRD_PARTY.md` (libarchive, xz, Coil, AndroidX are added by this port).
