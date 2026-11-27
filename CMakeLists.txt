cmake_minimum_required(VERSION 3.22.1)
project(TerrariaFreedomLoader LANGUAGES C CXX)

option(FREEDOM_ENABLE_EXPERIMENTAL_HOOK "Build the disabled-by-default Dobby hook experiment" OFF)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(freedom_runtime_inspector SHARED
    freedom_runtime_inspector.cpp
    freedom_fredl.cpp)

target_include_directories(freedom_runtime_inspector PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(freedom_runtime_inspector PRIVATE -Wall -Wextra -Wpedantic -fvisibility=hidden)
target_link_libraries(freedom_runtime_inspector PRIVATE ${CMAKE_DL_LIBS})

if(ANDROID)
    find_library(log-lib log)
    find_library(z-lib z)
    if(NOT z-lib)
        message(FATAL_ERROR "Android NDK zlib library was not found")
    endif()
    target_link_libraries(freedom_runtime_inspector PRIVATE ${log-lib} ${z-lib})
else()
    find_package(ZLIB REQUIRED)
    target_link_libraries(freedom_runtime_inspector PRIVATE ZLIB::ZLIB)

    enable_testing()
    find_package(Python3 COMPONENTS Interpreter REQUIRED)
    add_executable(fredl_validate_cli
        fredl_validate_cli.cpp
        freedom_fredl.cpp)
    target_include_directories(fredl_validate_cli PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_compile_options(fredl_validate_cli PRIVATE -Wall -Wextra -Wpedantic)
    target_link_libraries(fredl_validate_cli PRIVATE ZLIB::ZLIB)
    add_test(NAME fredl_package_tests
        COMMAND ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/tests/test_fredl.py
                $<TARGET_FILE:fredl_validate_cli>)
endif()

# Experimental hooks remain opt-in until exact-build runtime validation is complete.
if(FREEDOM_ENABLE_EXPERIMENTAL_HOOK)
    include(FetchContent)
    set(DOBBY_DEBUG OFF CACHE BOOL "" FORCE)
    FetchContent_Declare(Dobby
        GIT_REPOSITORY https://github.com/jmpews/Dobby.git
        GIT_TAG master
        GIT_SHALLOW TRUE)
    FetchContent_MakeAvailable(Dobby)
    target_include_directories(freedom_runtime_inspector PRIVATE ${dobby_SOURCE_DIR}/include)
    target_compile_definitions(freedom_runtime_inspector PRIVATE FREEDOM_ENABLE_EXPERIMENTAL_HOOK=1)
    target_link_libraries(freedom_runtime_inspector PRIVATE dobby_static)
endif()
