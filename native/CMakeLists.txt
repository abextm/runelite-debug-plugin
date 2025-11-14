cmake_minimum_required(VERSION 3.21)

cmake_policy(SET CMP0091 NEW)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded")

project(Profiler)


set(CMAKE_BUILD_TYPE Release)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 14)

find_package(JNI REQUIRED)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_OSX_ARCHITECTURES "arm64;x86_64")

set(CMAKE_SKIP_RPATH TRUE)
set(CMAKE_OSX_DEPLOYMENT_TARGET "10.9")

add_subdirectory(abseil-cpp)

set(ZSTD_BUILD_PROGRAMS OFF)
set(ZSTD_BUILD_SHARED OFF)
add_subdirectory(zstd/build/cmake)

include_directories(${JNI_INCLUDE_DIRS} zstd/lib/)

add_library(Profiler SHARED src/profiler.cpp src/jnizstd.cpp src/misc.cpp)

if(NOT MSVC)
	add_compile_options(-Wall -Wno-multichar -O3)
else()
	add_compile_options(/O2 /W4)
endif()

target_link_libraries(Profiler absl::flat_hash_map absl::time libzstd_static)
