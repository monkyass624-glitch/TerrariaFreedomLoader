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


// List installed package metadata and the dependency-safe startup order.
// Selected means queued for a future compatible runtime; it does not execute mod.dll.
FREDL_EXPORT int FreedomLoaderGetInstalledModsReport(
    const char* mods_directory, const char* game_version, const char* platform,
    char* report_json, size_t report_capacity);

// Select or deselect an installed mod for startup planning.
// A selection is rejected if dependencies are missing, incompatible or cyclic.
FREDL_EXPORT int FreedomLoaderSetModSelected(
    const char* mods_directory, const char* mod_id, int selected,
    const char* game_version, const char* platform,
    char* error_buffer, size_t error_capacity);

#ifdef __cplusplus
}
#endif
