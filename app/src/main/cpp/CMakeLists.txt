cmake_minimum_required(VERSION 3.22.1)
project(FredlAndroidBridge LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)
find_library(z-lib z)
if(NOT z-lib)
    message(FATAL_ERROR "Android NDK zlib library was not found")
endif()

add_library(fredl_jni SHARED
    fredl_jni.cpp
    ../../../../freedom_fredl.cpp)
target_include_directories(fredl_jni PRIVATE ../../../../)
target_compile_options(fredl_jni PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_libraries(fredl_jni PRIVATE ${log-lib} ${z-lib})

# Loaded only inside the isolated :game process, after Terraria's own
# libmain/libunity/libil2cpp. The inspector is read-only and hooks stay disabled.
add_library(freedom_runtime_inspector SHARED
    ../../../../freedom_runtime_inspector.cpp)
target_include_directories(freedom_runtime_inspector PRIVATE ../../../../)
target_compile_options(freedom_runtime_inspector PRIVATE
    -Wall -Wextra -Wpedantic -fvisibility=hidden)
target_link_libraries(freedom_runtime_inspector PRIVATE ${log-lib} ${CMAKE_DL_LIBS})
