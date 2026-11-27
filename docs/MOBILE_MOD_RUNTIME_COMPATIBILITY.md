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
