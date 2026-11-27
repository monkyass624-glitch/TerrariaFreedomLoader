# Terraria runtime integration

## Implemented in this milestone

- The app looks for the installed package `com.and.games505.TerrariaPaid`.
- It accepts the ARM64 Terraria Mobile build `1.4.5.6.4` (version code `301543`) for this first target.
- It reads the installed APK/split APKs and copies `libmain.so`, `libunity.so` and `libil2cpp.so` into app-private storage for loading.
- It parses the ELF64 dynamic dependency table of `libunity.so`. If `libpairipcore.so` is in `DT_NEEDED`, it refuses to start the host. It does not patch or bypass PairIP.
- A separate Android process, `:game`, loads the game libraries and the game's own `UnityPlayer` class using a package context, then creates the Unity view.
- After Unity libraries are loaded, it loads `libfreedom_runtime_inspector.so`. That library is read-only and waits for IL2CPP initialization.

## Why the compatibility gate exists

Some Google Play Terraria builds include PairIP. Public loader engineering notes document that builds whose `libunity.so` depends on `libpairipcore.so` cannot be hosted by this approach; those projects reject those builds instead of bypassing the protection. This app follows the same conservative boundary. It will report that PairIP blocks the current build and stop, rather than modifying Terraria or defeating its integrity checks.

## Device test procedure

1. Install the Freedom Loader debug APK.
2. Keep the official Terraria installation and its saves untouched.
3. In Freedom Loader, tap **فحص توافق Terraria**.
4. Confirm it reports version `1.4.5.6.4`, ARM64, and no PairIP dependency.
5. Tap **تشغيل Terraria داخل Freedom Loader**.
6. If Unity opens, the first proof is only that the game is hosted in the app process. Check Android logcat for tag `FreedomLoader` and the native inspector logs.
7. Report the exact visible error or whether the Terraria title screen appears.

## Important boundary

Loading Unity and detecting IL2CPP is not the same as loading a mod. This milestone does not execute `.fredl` contents in Terraria. The following work remains:
- pass the installed/enabled FREDL mod list into the in-process core;
- implement actual mod lifecycle and dependency handling;
- integrate a managed runtime and a safe IL2CPP-to-managed bridge;
- add a harmless test mod and verify it changes game behavior in a real device session.

The package manager still installs FREDL data under the app-private `files/mods/` directory. That location is accessible to the separate `:game` process because both processes belong to this app.
