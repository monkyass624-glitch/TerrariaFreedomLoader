# APK import and launch flow

Freedom Loader uses the standalone-launch design. It does not load Terraria's Unity runtime into the loader process.

## Import
- The user selects a Terraria APK through Android's Storage Access Framework.
- The app copies it into its private imported-game/Terraria.apk directory.
- The APK manifest must declare package com.and.games505.TerrariaPaid.
- The current inspector requires lib/arm64-v8a/libil2cpp.so and lib/arm64-v8a/libunity.so.
- Version name and version code are displayed. FREDL validation currently targets Terraria 1.4.5.6.4 / version code 301543.
- The import limit is 500 MiB. A base APK that omits required ABI libraries (for example, a split-only delivery) is rejected and must not be presented as a complete install package.

## Install and launch
- Installation uses Android PackageInstaller sessions and the platform's confirmation UI.
- Android may require the user to enable Install unknown apps for Freedom Loader.
- Android verifies the APK signature and package update compatibility. Freedom Loader does not change signatures, licensing checks, PairIP, or other protections.
- Launching uses PackageManager.getLaunchIntentForPackage for the installed package. The game runs as its own Android application.

## FREDL status
FREDL packages are still validated and stored under the loader's private files/mods directory. This is local package management only. It does not make the mod active inside Terraria. A compatible in-game mod runtime and public integration API still need separate implementation and testing.

## Not included
- No Unity hosting inside Freedom Loader.
- No patching, repackaging, signing, or protection bypass for the game APK.
- No automatic support for APK bundles or split APK sets in this initial importer.
