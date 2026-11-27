# FREDL package format (draft v1)

**Extension:** `.fredl`  
**Container:** ZIP archive (ordinary ZIP; the extension is changed to `.fredl`).  
**Status:** Package format specification. The current native inspector does not yet load or execute these packages.

## Required archive layout

```
ExampleMod.fredl
├── manifest.json
├── dependencies.json       # optional; [] when not needed
├── mod.dll                 # optional; managed payload, not executable until a compatible C# runtime exists
└── assets/                 # optional; arbitrary data files
    ├── textures/
    └── sounds/
```

The archive MUST contain exactly one `manifest.json` at its root. Paths MUST be relative, use forward slashes, and MUST NOT contain `..`, absolute paths, drive letters, or duplicate normalized names. A loader must reject encrypted ZIP entries, symlinks, malformed archives, and entries exceeding configured size/count limits. It must not extract files outside its private mod directory.

## manifest.json v1

Required fields:
- `format`: exactly `FREDL`
- `formatVersion`: integer `1`
- `id`: stable reverse-DNS-like identifier, e.g. `com.example.firstmod`
- `name`: display name
- `version`: semantic version
- `game`: object containing `id` (use `terraria-mobile`) and `versions` (array of supported game versions)
- `platforms`: array; use `android-arm64` for this target

Optional fields:
- `author`, `description`, `license`
- `entrypoint`: relative path to a future managed entry assembly (normally `mod.dll`)
- `dependencies`: object mapping mod IDs to version constraints
- `assetsDirectory`: defaults to `assets`

Unknown fields should be ignored for forward compatibility. Unknown `formatVersion` values must be rejected.

## Example manifest

```json
{
  "format": "FREDL",
  "formatVersion": 1,
  "id": "com.example.firstmod",
  "name": "First Mod",
  "version": "1.0.0",
  "author": "Example Author",
  "description": "A sample Terraria Freedom Loader mod package.",
  "game": {
    "id": "terraria-mobile",
    "versions": ["1.4.5.6.4"]
  },
  "platforms": ["android-arm64"],
  "entrypoint": "mod.dll",
  "assetsDirectory": "assets",
  "dependencies": {}
}
```

## Compatibility and security rules

- A package is metadata/data only until the loader has a real, tested execution runtime.
- `mod.dll` is a managed assembly payload; IL2CPP does not execute an ordinary .NET DLL by itself. Do not claim C# support until a compatible runtime and API bridge are implemented and tested.
- Never load a native library merely because a manifest names it. Native plugin support requires a separate, explicit ABI and signing/permission policy.
- Validate the game version and ABI before enabling a mod. Reject incompatible packages rather than guessing.
- Resolve dependencies by ID and version; detect missing dependencies and cycles before activation.
- Treat every archive entry and manifest string as untrusted input.
