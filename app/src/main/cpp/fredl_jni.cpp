#include <jni.h>
#include "freedom_fredl.h"

#include <string>

namespace {
std::string from_java(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring to_java(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_freedomloader_terraria_MainActivity_nativeValidate(
    JNIEnv* env, jclass, jstring package_path, jstring game_version, jstring platform) {
    const std::string path = from_java(env, package_path);
    const std::string game = from_java(env, game_version);
    const std::string abi = from_java(env, platform);
    char error[1024]{};
    if (FreedomLoaderValidateFredlFile(path.c_str(), game.c_str(), abi.c_str(),
                                       error, sizeof(error))) {
        return to_java(env, "VALID");
    }
    return to_java(env, std::string("INVALID: ") + error);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_freedomloader_terraria_MainActivity_nativeInstall(
    JNIEnv* env, jclass, jstring package_path, jstring mods_directory,
    jstring game_version, jstring platform) {
    const std::string path = from_java(env, package_path);
    const std::string destination = from_java(env, mods_directory);
    const std::string game = from_java(env, game_version);
    const std::string abi = from_java(env, platform);
    char error[1024]{};
    if (FreedomLoaderInstallFredlFile(path.c_str(), destination.c_str(),
                                      game.c_str(), abi.c_str(),
                                      error, sizeof(error))) {
        return to_java(env, "INSTALLED");
    }
    return to_java(env, std::string("INSTALL FAILED: ") + error);
}
