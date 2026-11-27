# Mobile mod-loader compatibility decision (2026-11-27)

## Decision

Do not add a fake "Enable mod" switch to the current Freedom Loader. Its current `.fredl` implementation is an archive validator/extractor, not an in-game runtime.

The most concrete Android ARM64 reference implementations found are Bunny Loader and Pylon Loader. Neither can be assumed to run against every official Google Play APK simply because the package name and Terraria version look similar.

## Findings

### Bunny Loader

The upstream README describes a real Android ARM64 loader with a native C++ core, IL2CPP hooks, a Mono/.NET mod runtime, a tModLoader-shaped C# API, mod packages, and in-game content. Its public docs say the user must install Terraria from Google Play. The project source is available under PolyForm Noncommercial 1.0.0; this is not an unrestricted permissive license. A fork/integration intended for redistribution must use a distinct app identity/branding and comply with its license and third-party notices.

Important version caveat: Bunny's own `refs/README.md` documents a validated game dump as versionName 1.4.5.8.6 / versionCode 301720 and explicitly says the Google Play build from 1.4.5.8 ships with PairIP and is not the build Bunny runs. It also records a previous 1.4.5.6.4 dump for comparison. Therefore the statement "requires Google Play Terraria" does not establish that the current Google Play APK's protected binaries are supported. The exact binary set must match the runtime's tested files.

### Pylon Loader

Pylon describes a complete Android ARM64 host, CoreCLR/.NET 10, IL2CPP interop, hooks, and a managed mod API. Its README explicitly rejects Google Play PairIP builds. Its modified GPL-3.0 license adds noncommercial restrictions and copyleft/attribution terms, and says it is not automatically compatible with standard GPL/MIT/Apache code.

## Current Freedom Loader target

The APK previously inspected for this project was Terraria Mobile 1.4.5.6.4 / versionCode 301543. The inspection found PairIP components including `libpairipcore.so` and PairIP Java bootstrap classes. That is a compatibility warning, not permission to remove or bypass those checks.

The current Freedom Loader app starts the original installed game as a separate Android app. It cannot inject its private `files/mods` directory into that process. Its FREDL validator and native inspector do not execute managed C# mods.

## Required next steps

1. Obtain a lawful, user-owned ARM64 game build whose exact binaries are explicitly supported by the chosen runtime. Do not patch, strip, re-sign, decrypt, or bypass PairIP or license/signature verification.
2. Verify exact package version, versionCode, ABI, hashes, metadata, Unity libraries, and runtime's documented PairIP policy. Version-name matching alone is insufficient.
3. Review and choose the runtime license before copying or linking any code. Bunny is PolyForm Noncommercial 1.0.0; Pylon is a modified GPL-3.0 with additional restrictions. No source or binaries from either project are copied into Freedom Loader by this decision document.
4. Only after the target is verified, implement a format adapter from FREDL to the selected runtime's package API (or formally version FREDL for that runtime). Never treat arbitrary DLLs from an archive as trusted.
5. Build a minimal sample mod that makes one reversible, visible in-game change. Verify on a physical ARM64 phone: load, disable, restart, save/load, crash recovery, and clean vanilla launch.
6. Mark only the tested game/runtime/mod combination as supported. Do not promise "100%" compatibility for arbitrary mods.

## Present status

- FREDL import/validation/extraction: implemented in Freedom Loader.
- Actual in-game execution of FREDL mods: not implemented.
- Compatible game build for the previously inspected PairIP APK: not established.
- Sample mod running in Terraria through Freedom Loader: not built or device-tested.
- No new APK is produced by this research/decision step.


## Verified PairIP finding (2026-11-27)

A source-level review of the current Bunny Loader implementation resolves the earlier ambiguity:

- Bunny's bundled game profile is Terraria 1.4.5.8.6 / versionCode 301720. Its `BundledRuntime.pairipCheck` rejects a bundled `libunity.so` whose ELF `DT_NEEDED` list contains `libpairipcore.so`.
- Bunny's `refs/README.md` explicitly says the Google Play build from 1.4.5.8 ships with PairIP and is not the binary set Bunny runs. The fact that its general README says users need Google Play Terraria must not be read as proof that the protected Play binaries are loaded by its mod runtime.
- Bunny's historical Unity-hosting investigation specifically examined Terraria 1.4.5.6.4. It records that PairIP-protected application string constants remain unavailable when the game is hosted in another app without the game's legitimate PairIP initialization. The document says the alternate design would have to operate in the original game process and notes that this is a different, privileged injection architecture.
- Pylon's current README explicitly lists Google Play PairIP builds as unsupported.
- 505 Games' support article says mods and texture packs are not supported on mobile platforms.

References:
- https://github.com/JonataOliveiraa/Bunny-Loader/blob/main/app/src/main/kotlin/dev/bunnyloader/game/BundledRuntime.kt
- https://github.com/JonataOliveiraa/Bunny-Loader/blob/main/refs/README.md
- https://github.com/JonataOliveiraa/Bunny-Loader/blob/main/docs/historico/UNITY-HOSTING.md
- https://github.com/UnrealMultiple/Pylon-Loader
- https://support.505games.com/support/solutions/articles/150000146904-can-we-get-mods-or-texture-packs-

### Consequence for Freedom Loader

For the user's inspected Terraria 1.4.5.6.4 / versionCode 301543 PairIP APK, the only verified behavior we can provide without changing or bypassing the game's protection is normal APK import, Android installation, and normal game launch. No compatible in-process mod runtime has been established for this exact protected binary. FREDL archives therefore remain locally managed packages and must not be presented as running mods.

Do not silently turn off PairIP, alter the game's signature or license checks, or claim that loading a DLL in Freedom Loader's own process affects Terraria. A true runtime requires a supported integration path from the game/protection vendor or a different user-owned game build whose exact binaries are explicitly supported by a mod runtime.
