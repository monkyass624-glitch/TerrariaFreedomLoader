#include "freedom_runtime_inspector.h"

#include <atomic>
#include <cinttypes>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <link.h>
#include <pthread.h>
#include <string>
#include <unistd.h>

#if defined(FREEDOM_ENABLE_EXPERIMENTAL_HOOK)
#include "dobby.h"
#endif

#if defined(__ANDROID__)
#include <android/log.h>
#define FL_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "FreedomLoader", __VA_ARGS__)
#define FL_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "FreedomLoader", __VA_ARGS__)
#else
#define FL_LOGI(...) do { std::fprintf(stdout, "[FreedomLoader] "); std::fprintf(stdout, __VA_ARGS__); std::fprintf(stdout, "\n"); } while (0)
#define FL_LOGE(...) do { std::fprintf(stderr, "[FreedomLoader][ERR] "); std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#endif

namespace {
struct Il2CppDomain;
struct Il2CppThread;
struct Il2CppAssembly;
struct Il2CppImage;
struct Il2CppClass;
struct Il2CppMethod;
struct Il2CppType;

struct Api {
    Il2CppDomain* (*domain_get)() = nullptr;
    Il2CppThread* (*thread_attach)(Il2CppDomain*) = nullptr;
    void (*thread_detach)(Il2CppThread*) = nullptr;
    const Il2CppAssembly** (*domain_get_assemblies)(const Il2CppDomain*, size_t*) = nullptr;
    const Il2CppImage* (*assembly_get_image)(const Il2CppAssembly*) = nullptr;
    const char* (*image_get_name)(const Il2CppImage*) = nullptr;
    Il2CppClass* (*class_from_name)(const Il2CppImage*, const char*, const char*) = nullptr;
    const Il2CppMethod* (*class_get_method_from_name)(Il2CppClass*, const char*, int) = nullptr;
    const char* (*method_get_name)(const Il2CppMethod*) = nullptr;
    uint32_t (*method_get_param_count)(const Il2CppMethod*) = nullptr;
    const char* (*method_get_param_name)(const Il2CppMethod*, uint32_t) = nullptr;
    const Il2CppType* (*method_get_param)(const Il2CppMethod*, uint32_t) = nullptr;
    const Il2CppType* (*method_get_return_type)(const Il2CppMethod*) = nullptr;
    char* (*type_get_name)(const Il2CppType*) = nullptr;
    void (*il2cpp_free)(void*) = nullptr;
    bool (*method_is_instance)(const Il2CppMethod*) = nullptr;
    uint32_t (*method_get_flags)(const Il2CppMethod*, uint32_t*) = nullptr;
};

struct ImageSearch {
    uintptr_t base = 0;
    std::string path;
};

int find_il2cpp(struct dl_phdr_info* info, size_t, void* data) {
    auto* out = static_cast<ImageSearch*>(data);
    if (!info->dlpi_name) return 0;
    const char* name = std::strrchr(info->dlpi_name, '/');
    name = name ? name + 1 : info->dlpi_name;
    if (std::strcmp(name, "libil2cpp.so") == 0) {
        out->base = static_cast<uintptr_t>(info->dlpi_addr);
        out->path = info->dlpi_name;
        return 1;
    }
    return 0;
}

bool resolve_api(void* handle, Api& a) {
#define RESOLVE(field, symbol) a.field = reinterpret_cast<decltype(a.field)>(dlsym(handle, symbol)); if (!a.field) { FL_LOGE("Missing IL2CPP export: %s", symbol); return false; }
    RESOLVE(domain_get, "il2cpp_domain_get")
    RESOLVE(thread_attach, "il2cpp_thread_attach")
    RESOLVE(domain_get_assemblies, "il2cpp_domain_get_assemblies")
    RESOLVE(assembly_get_image, "il2cpp_assembly_get_image")
    RESOLVE(image_get_name, "il2cpp_image_get_name")
    RESOLVE(class_from_name, "il2cpp_class_from_name")
    RESOLVE(class_get_method_from_name, "il2cpp_class_get_method_from_name")
    RESOLVE(method_get_name, "il2cpp_method_get_name")
    RESOLVE(method_get_param_count, "il2cpp_method_get_param_count")
    RESOLVE(method_get_param_name, "il2cpp_method_get_param_name")
    RESOLVE(method_get_param, "il2cpp_method_get_param")
    RESOLVE(method_get_return_type, "il2cpp_method_get_return_type")
    RESOLVE(type_get_name, "il2cpp_type_get_name")
    RESOLVE(il2cpp_free, "il2cpp_free")
    RESOLVE(method_is_instance, "il2cpp_method_is_instance")
    RESOLVE(method_get_flags, "il2cpp_method_get_flags")
#undef RESOLVE
    a.thread_detach = reinterpret_cast<decltype(a.thread_detach)>(dlsym(handle, "il2cpp_thread_detach"));
    return true;
}


#if defined(FREEDOM_ENABLE_EXPERIMENTAL_HOOK)
using ResetEffectsFunction = void (*)(void*, const void*);
static ResetEffectsFunction g_original_reset_effects = nullptr;
static std::atomic<uint64_t> g_reset_effects_calls{0};

static void freedom_reset_effects_detour(void* self, const void* method_info) {
    const uint64_t calls = g_reset_effects_calls.fetch_add(1, std::memory_order_relaxed) + 1;
    if (calls == 1) FL_LOGI("Experimental observer: Player.ResetEffects hook entered");
    if (g_original_reset_effects) g_original_reset_effects(self, method_info);
}

static bool install_experimental_reset_hook(Api& a, const Il2CppMethod* method,
                                            uintptr_t image_base) {
    // This hook is opt-in and only forwards to the original method.
    // It is refused unless reflection and the exact ARM64 code fingerprint agree.
    constexpr uintptr_t kRva = 0x1606BD4;
    static const uint8_t kExpectedPrologue[16] = {
        0xff, 0x43, 0x01, 0xd1, 0xe9, 0x23, 0x01, 0x6d,
        0xf6, 0x13, 0x00, 0xf9, 0xf5, 0x53, 0x03, 0xa9
    };
    if (!method || !image_base) return false;
    if (!a.method_is_instance || !a.method_is_instance(method)) {
        FL_LOGE("Experimental hook refused: ResetEffects is not an instance method at runtime");
        return false;
    }
    if (a.method_get_param_count(method) != 0) {
        FL_LOGE("Experimental hook refused: ResetEffects parameter count mismatch");
        return false;
    }
    const std::string result_type = type_name(a, a.method_get_return_type(method));
    if (result_type != "System.Void") {
        FL_LOGE("Experimental hook refused: unexpected return type %s", result_type.c_str());
        return false;
    }
    void* reflected_pointer = nullptr;
    std::memcpy(&reflected_pointer, method, sizeof(reflected_pointer));
    void* target = reinterpret_cast<void*>(image_base + kRva);
    if (reflected_pointer != target) {
        FL_LOGE("Experimental hook refused: MethodInfo pointer %p != expected %p",
                reflected_pointer, target);
        return false;
    }
    if (std::memcmp(target, kExpectedPrologue, sizeof(kExpectedPrologue)) != 0) {
        FL_LOGE("Experimental hook refused: ARM64 prologue fingerprint mismatch");
        return false;
    }
    void* original = nullptr;
    const int result = DobbyHook(target, reinterpret_cast<dobby_dummy_func_t>(freedom_reset_effects_detour),
                                 reinterpret_cast<dobby_dummy_func_t*>(&original));
    if (result != 0 || !original) {
        FL_LOGE("Experimental hook install failed: Dobby result=%d original=%p", result, original);
        return false;
    }
    g_original_reset_effects = reinterpret_cast<ResetEffectsFunction>(original);
    FL_LOGI("Experimental no-behavior hook installed: Player.ResetEffects @ %p", target);
    return true;
}
#endif

std::string type_name(Api& a, const Il2CppType* type) {
    if (!type) return "<null-type>";
    char* raw = a.type_get_name(type);
    if (!raw) return "<type-name-failed>";
    std::string value(raw);
    if (a.il2cpp_free) a.il2cpp_free(raw);
    return value;
}

void dump_method(Api& a, Il2CppClass* klass, const char* class_name,
                 const char* method_name, int arity, uintptr_t image_base,
                 uintptr_t expected_rva) {
    const Il2CppMethod* method = a.class_get_method_from_name(klass, method_name, arity);
    if (!method) {
        FL_LOGE("Method not found: Terraria.%s::%s/%d", class_name, method_name, arity);
        return;
    }
    const uint32_t pcount = a.method_get_param_count(method);
    uint32_t iflags = 0;
    const uint32_t flags = a.method_get_flags ? a.method_get_flags(method, &iflags) : 0;
    const bool instance = a.method_is_instance ? a.method_is_instance(method) : false;
    const std::string ret = type_name(a, a.method_get_return_type(method));

    std::string signature = ret + " Terraria." + class_name + "::" + method_name + "(";
    for (uint32_t i = 0; i < pcount; ++i) {
        if (i) signature += ", ";
        const char* pname = a.method_get_param_name(method, i);
        signature += (pname && *pname) ? pname : ("arg" + std::to_string(i));
        signature += ": ";
        signature += type_name(a, a.method_get_param(method, i));
    }
    signature += instance ? ") [instance]" : ") [static]";

    // Common IL2CPP MethodInfo layouts place methodPointer first. This read is
    // diagnostic only: it is NOT used to patch code in this inspector build.
    void* first_word = nullptr;
    std::memcpy(&first_word, method, sizeof(first_word));
    void* expected = expected_rva ? reinterpret_cast<void*>(image_base + expected_rva) : nullptr;
    const bool pointer_match = expected && first_word == expected;
    FL_LOGI("%s | flags=0x%04x impl=0x%04x | methodPtr=%p expected=%p match=%s",
            signature.c_str(), flags, iflags, first_word, expected, pointer_match ? "YES" : "NO");
#if defined(FREEDOM_ENABLE_EXPERIMENTAL_HOOK)
    if (std::strcmp(class_name, "Player") == 0 && std::strcmp(method_name, "ResetEffects") == 0) {
        install_experimental_reset_hook(a, method, image_base);
    }
#endif
}

void inspect_once() {
    void* handle = dlopen("libil2cpp.so", RTLD_NOW);
    if (!handle) {
        FL_LOGE("dlopen libil2cpp.so failed: %s", dlerror());
        return;
    }

    Api api;
    if (!resolve_api(handle, api)) { dlclose(handle); return; }

    Il2CppDomain* domain = api.domain_get();
    if (!domain) { FL_LOGE("IL2CPP domain is not initialized yet"); dlclose(handle); return; }
    Il2CppThread* attached = api.thread_attach(domain);

    ImageSearch image_search;
    dl_iterate_phdr(find_il2cpp, &image_search);
    FL_LOGI("IL2CPP image path=%s base=%p", image_search.path.c_str(), reinterpret_cast<void*>(image_search.base));

    size_t assembly_count = 0;
    const Il2CppAssembly** assemblies = api.domain_get_assemblies(domain, &assembly_count);
    const Il2CppImage* game_image = nullptr;
    for (size_t i = 0; assemblies && i < assembly_count; ++i) {
        const Il2CppImage* image = api.assembly_get_image(assemblies[i]);
        const char* name = image ? api.image_get_name(image) : nullptr;
        if (name && (std::strcmp(name, "Assembly-CSharp") == 0 || std::strcmp(name, "Assembly-CSharp.dll") == 0)) {
            game_image = image;
            FL_LOGI("Found game assembly: %s", name);
            break;
        }
    }
    if (!game_image) {
        FL_LOGE("Assembly-CSharp image not found (assemblies=%zu)", assembly_count);
    } else {
        struct Target { const char* klass; const char* method; int arity; uintptr_t rva; };
        // Targets are chosen for signature validation; this pass is read-only.
        const Target targets[] = {
            {"Player", "ResetEffects", 0, 0x1606BD4},
            {"Player", "UpdateBuffs", 1, 0x1602628},
            {"Player", "UpdateEquips", 1, 0x1603454},
            {"Main", "DoUpdate", 1, 0x1345F14},
            {"NPC", "SetDefaults", 2, 0x1510660},
            {"Projectile", "AI", 0, 0x16B9138},
            {"NetMessage", "SendData", 11, 0x14E3744},
            {"WorldGen", "PlaceTile", 7, 0x1971668},
        };
        for (const auto& target : targets) {
            Il2CppClass* klass = api.class_from_name(game_image, "Terraria", target.klass);
            if (!klass) {
                FL_LOGE("Class not found: Terraria.%s", target.klass);
                continue;
            }
            dump_method(api, klass, target.klass, target.method, target.arity,
                        image_search.base, target.rva);
        }
    }

    if (attached && api.thread_detach) api.thread_detach(attached);
    dlclose(handle);
}

std::atomic<bool> g_started{false};
void* inspector_thread(void*) {
    // IL2CPP may be loaded before its domain is ready. Retry for up to 90 s.
    for (int attempt = 0; attempt < 90; ++attempt) {
        void* handle = dlopen("libil2cpp.so", RTLD_NOW);
        if (handle) {
            auto domain_get = reinterpret_cast<Il2CppDomain*(*)()>(dlsym(handle, "il2cpp_domain_get"));
            Il2CppDomain* domain = domain_get ? domain_get() : nullptr;
            dlclose(handle);
            if (domain) { inspect_once(); return nullptr; }
        }
        sleep(1);
    }
    FL_LOGE("Timed out waiting for IL2CPP domain");
    return nullptr;
}
} // namespace

extern "C" FL_EXPORT int FreedomLoaderStartInspector(void) {
    bool expected = false;
    if (!g_started.compare_exchange_strong(expected, true)) return 1;
    pthread_t thread{};
    const int rc = pthread_create(&thread, nullptr, inspector_thread, nullptr);
    if (rc != 0) {
        g_started.store(false);
        FL_LOGE("pthread_create failed: %d", rc);
        return -rc;
    }
    pthread_detach(thread);
    return 0;
}

extern "C" __attribute__((visibility("default"))) int JNI_OnLoad(void*, void*) {
    FreedomLoaderStartInspector();
    return 0x00010006; // JNI_VERSION_1_6
}

__attribute__((constructor)) static void freedom_loader_constructor() {
    // Safe, read-only diagnostic startup. The process must already contain IL2CPP.
    FreedomLoaderStartInspector();
}
