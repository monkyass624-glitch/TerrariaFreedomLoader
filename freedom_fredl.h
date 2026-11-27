#pragma once

#include <stddef.h>

#if defined(__GNUC__)
#define FREDL_EXPORT __attribute__((visibility("default")))
#else
#define FREDL_EXPORT
#endif

#ifdef __cplusplus
extern "C" {
#endif

// Validate a .fredl ZIP package, its manifest, CRCs, paths, game version and platform.
// Returns 1 when accepted, 0 when rejected. error_buffer may be null.
FREDL_EXPORT int FreedomLoaderValidateFredlFile(
    const char* package_path,
    const char* game_version,
    const char* platform,
    char* error_buffer,
    size_t error_capacity);

// Safely extract a validated package into mods_directory/<manifest.id>.
// Uses a staging directory and rename-based replacement. Does NOT execute mod.dll.
FREDL_EXPORT int FreedomLoaderInstallFredlFile(
    const char* package_path,
    const char* mods_directory,
    const char* game_version,
    const char* platform,
    char* error_buffer,
    size_t error_capacity);

// Validate all top-level *.fredl files in a directory.
// Returns number of discovered package files, -1 for bad arguments, or -2 if report buffer is too small.
// report_json is a JSON array with file, valid, id and error fields.
FREDL_EXPORT int FreedomLoaderScanFredlDirectory(
    const char* mods_directory,
    const char* game_version,
    const char* platform,
    char* report_json,
    size_t report_capacity);

#ifdef __cplusplus
}
#endif
