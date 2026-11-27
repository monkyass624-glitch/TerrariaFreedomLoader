cmake_minimum_required(VERSION 3.22.1)
project(FredlAndroidBridge LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(fredl_jni SHARED
    fredl_jni.cpp
    ../../../../freedom_fredl.cpp)

target_include_directories(fredl_jni PRIVATE
    ../../../../)

find_library(log-lib log)
find_library(z-lib z)
if(NOT z-lib)
    message(FATAL_ERROR "Android NDK zlib library was not found")
endif()

target_compile_options(fredl_jni PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_libraries(fredl_jni PRIVATE ${log-lib} ${z-lib})
