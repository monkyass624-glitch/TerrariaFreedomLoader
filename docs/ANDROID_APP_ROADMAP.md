# Android APK plan and implementation status

Target: Android 14+, ARM64, Terraria Mobile 1.4.5.6.4 first.

## Phase 1 — APK shell and FREDL package management

- [x] Add Gradle Android application module.
- [x] Add a basic Arabic Android interface.
- [x] Import `.fredl` files through Android's document picker.
- [x] Bridge the native FREDL validator and installer to Java using JNI.
- [x] Validate the package before enabling installation.
- [x] Install extracted files into the APK's private `files/mods/<mod-id>` directory.
- [ ] Complete the first GitHub Actions APK build and resolve any build errors.
- [ ] Install the APK on an Android 14 ARM64 device and test import/validation/install against good and malformed packages.

This phase manages packages only. The current app's private storage is not Terraria's storage, so a successful local install does not make the mod active in the game.

## Phase 2 — User-selected storage integration

- [ ] Add an explicit user-selected destination using Android Storage Access Framework (SAF).
- [ ] Check which Terraria directories are visible and writable on the target Android release and device.
- [ ] Export validated package contents to a user-selected accessible location, without requesting broad storage permissions.
- [ ] Keep the app-private copy as a safe staging/backup copy.

Android restricts access to some shared-storage locations (including other apps' private data directories), so SAF may not expose Terraria's internal location. Do not promise direct installation there until tested on the user's device.

## Phase 3 — Runtime integration in Terraria

- [ ] Determine a supported, reproducible way to load the native component in the target Terraria process.
- [ ] Confirm exact game build, process ABI, IL2CPP exports, and runtime initialization.
- [ ] Add a small, read-only in-game bridge and diagnostics before enabling mod code.
- [ ] Design and test mod lifecycle/API boundaries.

The standalone APK process cannot inspect or modify Terraria merely because it contains the same native library. A separate, legitimate in-process loading/embedding mechanism is required.

## Phase 4 — Managed mod execution

- [ ] Select and integrate a managed runtime compatible with Android ARM64 and IL2CPP.
- [ ] Implement a constrained API bridge between managed mods and Terraria.
- [ ] Add dependency resolution, version constraints, logging, crash isolation, enable/disable, and safe unload/restart behavior.
- [ ] Build a sample FREDL mod and verify it changes a harmless in-game property.

An ordinary `mod.dll` does not execute in an IL2CPP game on its own. C# support must remain marked unavailable until the runtime and bridge pass an actual in-game test.

## Current deliverables

- Android module: `app/`
- JNI bridge: `app/src/main/cpp/fredl_jni.cpp`
- FREDL native parser/installer: `freedom_fredl.cpp`
- APK workflow: `.github/workflows/build-apk.yml`
- FREDL specification: `docs/FREDL_FORMAT.md`

## Current limitations

No APK has been confirmed installed on a physical device yet. The first workflow must pass before an APK artifact can be offered as installable. No in-game loader, C# runtime, or actual mod execution is included in this first APK milestone.
