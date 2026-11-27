# Android APK plan and implementation status

Target: Android 14+, ARM64, Terraria Mobile 1.4.5.6.4 first.

## Phase 1 — APK shell and FREDL package management

- [x] Add Gradle Android application module.
- [x] Add a basic Arabic Android interface.
- [x] Import `.fredl` files through Android's document picker.
- [x] Bridge the native FREDL validator and installer to Java using JNI.
- [x] Validate the package before enabling installation.
- [x] Install extracted files into the APK's private `files/mods/<mod-id>` directory.
- [x] Build the debug APK through GitHub Actions.
- [ ] Install the APK on an Android 14 ARM64 device and test import/validation/install against good and malformed packages.

This phase manages packages only. The app-private directory is not Terraria's storage and an extracted `mod.dll` is not executed.

## Phase 2 — APK import, install and compatibility inspection

- [x] Import a user-selected standalone Terraria APK using the Storage Access Framework.
- [x] Validate package name, ARM64 libraries, version name/code and metadata presence.
- [x] Parse the ELF64 dynamic dependency table of ARM64 `libunity.so` to detect a direct `DT_NEEDED` dependency on `libpairipcore.so`.
- [x] Ask Android's PackageInstaller to install the original APK without changing its signature or contents.
- [x] Launch the installed Terraria package as a separate Android application.
- [ ] Test APK import, Android installation and launch on a physical Android 14 ARM64 device.
- [ ] Verify PairIP detection against the exact user-provided APK binary.

PairIP detection is only a compatibility warning. A build without a direct PairIP dependency is not automatically supported by a mod runtime.

## Phase 3 — In-process Unity host (not implemented)

- [ ] Add a real game-host Activity and process lifecycle.
- [ ] Implement a supported way to host the user's installed game libraries and Unity assets in the app process.
- [ ] Apply an explicit compatibility gate for the exact game binary and ABI.
- [ ] Start the read-only IL2CPP inspector only after Unity and IL2CPP are actually initialized in that process.
- [ ] Verify the Terraria title screen and IL2CPP inspector on a physical Android device.

The current app does **not** contain a `GameActivity`, does **not** embed Unity, and does **not** load the inspector library into Terraria. The existing `freedom_runtime_inspector.cpp` is a separate diagnostic experiment built by the native CI workflow; it is not an active in-game runtime.

## Phase 4 — Managed mod execution (not implemented)

- [ ] Select a managed runtime compatible with Android ARM64 and the exact supported Terraria build.
- [ ] Implement and test the IL2CPP-to-managed bridge and mod lifecycle.
- [ ] Define a trusted FREDL entrypoint contract and validate all mod dependencies.
- [ ] Add enable/disable, load order, logging, crash handling and safe restart behavior.
- [ ] Build a harmless sample FREDL mod and verify an observable in-game change.

An ordinary `mod.dll` does not execute in an IL2CPP game by itself. Do not display an enabled/running state until a sample mod has actually changed the game during a device test.

## Current deliverables

- Android module: `app/`
- JNI bridge: `app/src/main/cpp/fredl_jni.cpp`
- FREDL native parser/installer: `freedom_fredl.cpp`
- ELF PairIP detector: `app/src/main/java/com/freedomloader/terraria/ElfDependencyInspector.java`
- APK workflow: `.github/workflows/build-apk.yml`
- FREDL specification: `docs/FREDL_FORMAT.md`

## Current limitations

The app can import and request installation of a standalone Terraria APK, launch the installed game separately, and validate/extract FREDL packages into its own private storage. It cannot currently activate a mod inside Terraria. No C# runtime, Terraria Mod API bridge, in-game hook, or verified sample mod is included.
