# FREDL package format v1

**File extension:** `.fredl`  
**Container:** Standard ZIP archive (rename the ZIP file to `.fredl`).  
**Game target in this milestone:** Terraria Mobile `1.4.5.6.4`, Android `arm64-v8a`.

The repository now contains a native FREDL package reader and installer in `freedom_fredl.cpp`. It validates archives and manifests, scans package files, and safely extracts accepted packages. This is not yet a mod execution runtime: `mod.dll` is stored as managed payload and is **not executed** by the current build.

## Archive layout

```
ExampleMod.fredl
├── manifest.json              # required, at archive root
├── mod.dll                    # optional managed assembly; if named as entrypoint it must exist
├── assets/                    # optional data files
│   ├── textures/
│   └── sounds/
└── other-data/                # optional
```

There must be exactly one root `manifest.json`. The package may contain arbitrary asset/data files. A managed entry assembly is optional at the package-format level; the current native reader does not execute it.

## manifest.json v1

Required fields:
- `format`: exactly `FREDL`
- `formatVersion`: number `1`
- `id`: lowercase reverse-DNS-like identifier, e.g. `com.example.firstmod`
- `name`: non-empty display name (maximum 128 bytes)
- `version`: semantic version, e.g. `1.0.0`
- `game`: object with `id: "terraria-mobile"` and a non-empty `versions` array
- `platforms`: non-empty array; Android ARM64 uses `"android-arm64"`

Optional fields:
- `author`, `description`, `license`
- `entrypoint`: safe relative path to an existing `.dll` file in the package
- `dependencies`: object mapping mod IDs to non-empty version constraint strings
- `assetsDirectory`: safe relative path; defaults to `assets`

Example:

```json
{
  "format": "FREDL",
  "formatVersion": 1,
  "id": "com.example.firstmod",
  "name": "First Mod",
  "version": "1.0.0",
  "author": "Example Author",
  "description": "A sample Terraria Freedom Loader package.",
  "game": {
    "id": "terraria-mobile",
    "versions": ["1.4.5.6.4"]
  },
  "platforms": ["android-arm64"],
  "assetsDirectory": "assets",
  "dependencies": {}
}
```

When creating the archive, place `manifest.json` at the ZIP root (not inside an enclosing folder), then rename the resulting ZIP to `ExampleMod.fredl`.

## Native reader and installer API

Declared in `freedom_fredl.h` and compiled into `libfreedom_runtime_inspector.so`:

- `FreedomLoaderValidateFredlFile(path, gameVersion, platform, error, errorCapacity)`: returns 1 if accepted, 0 if rejected.
- `FreedomLoaderInstallFredlFile(path, modsDirectory, gameVersion, platform, error, errorCapacity)`: validates and extracts to `modsDirectory/<manifest.id>/` using a staging directory and rename-based replacement. It does not execute code.
- `FreedomLoaderScanFredlDirectory(directory, gameVersion, platform, reportJson, reportCapacity)`: scans top-level `*.fredl` files and returns a JSON report.

For the first target, pass game version `1.4.5.6.4` and platform `android-arm64`. Compatibility matching is exact; no version wildcards are currently accepted for the game version.

## ZIP support and safety limits

The reader:
- supports ZIP `stored` and `deflate` entries;
- verifies the central directory, local headers, decompressed sizes, and CRC32 for every file;
- rejects encrypted entries, multi-disk archives, ZIP64, symbolic links, duplicate paths, absolute paths, backslashes, drive paths, and `.` / `..` path components;
- extracts only under the selected mod directory;
- limits archives to 128 MiB, expanded contents to 96 MiB total, each file to 32 MiB, the manifest to 256 KiB, and the archive to 512 entries.

## Current implementation boundary

Implemented: FREDL ZIP parsing, JSON manifest parsing, metadata/compatibility validation, CRC checking, directory scanning, and safe extraction.

Implemented in the 0.4.0 native core: installed-manifest inspection, selection state, dependency version checks (`*`, exact version, `>=x.y.z`, and `^x.y.z`), missing/disabled dependency detection, cycle detection, and dependency-first startup ordering. The Android UI can select mods for a future startup profile. Selection is planning state only; it does not execute mod code.

Not implemented yet: C# assembly execution, the Terraria mod API bridge, in-game activation/unload, and in-game verification. IL2CPP does not execute an ordinary .NET DLL by itself. Never treat a validated or installed `mod.dll` as running code until a compatible runtime is integrated and tested.

Native shared libraries are not accepted as mod entrypoints. Do not add an arbitrary native `.so` loader to the package format.
