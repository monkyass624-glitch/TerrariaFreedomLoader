#pragma once

#if defined(__GNUC__)
#define FL_EXPORT __attribute__((visibility("default")))
#else
#define FL_EXPORT
#endif

#ifdef __cplusplus
extern "C" {
#endif

// Start a read-only IL2CPP reflection pass inside the Terraria process.
// It does not modify game state or install hooks.
FL_EXPORT int FreedomLoaderStartInspector(void);

#ifdef __cplusplus
}
#endif
