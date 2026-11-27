# Terraria runtime integration — current status

## What the Android app actually does

The current app is a standalone launcher and FREDL package manager:

- It imports a user-selected Terraria APK through Android's Storage Access Framework.
- It checks the package name, version, ARM64 `libil2cpp.so` and `libunity.so`, and the presence of `global-metadata.dat`.
- It performs a read-only ELF64 scan of `libunity.so` to detect a direct dynamic dependency on `libpairipcore.so`.
- It can ask Android's PackageInstaller to install the original, unmodified APK.
- It launches the installed Terraria package through its normal Android launch intent. Terraria runs as a separate application process.
- It validates and extracts FREDL packages into Freedom Loader's private app storage.

## PairIP compatibility inspection

The new APK-import check reads the ELF64 program and dynamic tables from `lib/arm64-v8a/libunity.so`. If a `DT_NEEDED` entry resolves to `libpairipcore.so`, the UI reports PairIP as detected and warns that the known external loader path requiring a non-PairIP Unity binary cannot use this build.

The check is intentionally read-only. It does not remove, patch, rename, replace, or bypass PairIP, signature verification, or licensing. If the ELF cannot be parsed, the result is “unknown”, not “compatible”.

Absence of a direct PairIP dependency is not proof that the game is compatible with Freedom Loader or with any other mod loader. Exact library, metadata, ABI and runtime validation are still required.

## What is not implemented

The current Android source does not contain a `GameActivity` or an in-process Unity host. The app does not inject or preload a library into the separately launched Terraria process. The C++ file `freedom_runtime_inspector.cpp` is a read-only native diagnostic experiment; the app does not load that library into the game.

There is no managed runtime, C# mod execution, Terraria Mod API bridge, FREDL lifecycle, dependency resolver, or verified in-game sample mod. A `mod.dll` inside an extracted FREDL package is only stored data.

## Required path to real in-game mods

1. Select a lawful game build whose exact native libraries and metadata are supported by a loader architecture.
2. Confirm that the build is not rejected by the loader's documented compatibility checks. Do not bypass PairIP or license checks.
3. Implement and device-test the in-process host using a documented, compatible runtime architecture.
4. Add a managed runtime and IL2CPP API bridge for that exact build.
5. Connect enabled FREDL packages to the runtime and define the entrypoint/API contract.
6. Prove the first harmless test mod changes a visible in-game value on a physical ARM64 device.

Until those tests pass, Freedom Loader must label FREDL packages as locally installed, not active or running.
