# FREDL runtime feasibility and implementation gate

## Current product behavior

The Android app currently imports, validates, and extracts `.fredl` archives into app-private storage. It then launches the separately installed Terraria package with Android's launch intent. This is package management only: the launched Terraria process does not read the loader's private mod directory, and the launcher has no IPC or supported plugin API into that process.

## Why a real mod runtime is not a small toggle

A runtime capable of executing gameplay mods needs, at minimum:

1. A game-process host that starts the user's own game installation.
2. A managed runtime suitable for Android ARM64 (for example, a pinned .NET runtime).
3. IL2CPP interop bindings generated for the exact game build.
4. A versioned mod API, lifecycle, content registration, hook validation, dependency resolution, and crash isolation.
5. A package format that distinguishes managed code, assets, dependencies, permissions, and target game/runtime versions.
6. Device tests proving a sample mod changes gameplay, survives save/load, and does not corrupt vanilla saves.

The existing read-only native inspector and FREDL archive validator do not provide these features. A detected IL2CPP domain or a successful APK build is not proof that mods execute.

## Compatibility gate for the currently analyzed game

The supplied Terraria APK was previously inspected as version 1.4.5.6.4 and contained PairIP components (`libpairipcore.so` and PairIP Java bootstrap classes). The current standalone-launch design leaves the game's integrity and licensing checks untouched. A candidate runtime that explicitly rejects PairIP builds cannot be used with this APK. Do not patch, remove, hook around, or disable PairIP, signature verification, or licensing checks.

Before any runtime implementation, inspect the exact user-owned APK and verify its version, ABI, split-package completeness, and protection dependencies. If the target build is rejected by the runtime's documented compatibility policy, stop and report incompatibility; do not attempt a bypass.

## Candidate research (not integrated)

- Pylon Loader is a public Android ARM64 Terraria Mobile mod-loader project with an in-process host, CoreCLR, IL2CPP interop, and a managed mod API. Its README explicitly says PairIP Google Play builds are unsupported and rejected. Its license is GPL-3.0 with additional non-commercial terms, so code must not be copied or integrated without a complete license review and compliance plan.
- Bunny Loader is another unofficial mobile mod-loader project. Its packages are not automatically compatible with desktop tModLoader mods; mobile-specific APIs and IL2CPP constraints apply. Its own licensing and exact target-build compatibility must be reviewed independently.

These projects are research references only. No source code or binaries from them are copied into this repository by this document.

## FREDL roadmap once a supported target is available

- Keep the archive container, but version the manifest and add explicit `runtimeApi`, `gameBuild`, `abi`, `entryAssembly`, dependency list, and declared capabilities.
- Separate asset-only packs from executable managed mods. Never load an arbitrary DLL merely because it is present in an archive.
- Build and validate the host/runtime for one exact, supported game build first.
- Add one harmless sample mod (for example, a controlled UI label or a reversible item-stat change).
- Verify enable/disable, dependency failure, crash recovery, save isolation, and multiplayer compatibility on a physical ARM64 device.
- Only then expose an in-game "Enable" control and label that exact game/runtime/mod combination as tested.

## Current status

No gameplay mod execution is implemented. Do not describe FREDL packages as active in Terraria until the sample-mod device test above passes. No APK patching, protection bypass, or game binary redistribution is permitted by this project plan.
