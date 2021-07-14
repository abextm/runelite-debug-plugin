cmake_minimum_required(VERSION 3.13)
project(Profiler)

set(CMAKE_BUILD_TYPE Release)
set(CMAKE_CXX_STANDARD 11)

if ("${CMAKE_CXX_COMPILER_ID}" STREQUAL "Clang")
	# using Clang
	message("Clang")
elseif ("${CMAKE_CXX_COMPILER_ID}" STREQUAL "GNU")
	# using GCC
	message("GCC")
elseif ("${CMAKE_CXX_COMPILER_ID}" STREQUAL "Intel")
	# using Intel C++
	message("IntelC++")
elseif ("${CMAKE_CXX_COMPILER_ID}" STREQUAL "MSVC")
	# using Visual Studio C++
	message("Visual Studio C++")
endif ()

find_package(JNI)

if (JNI_FOUND)
	message(STATUS "JNI_INCLUDE_DIRS=${JNI_INCLUDE_DIRS}")
	message(STATUS "JNI_LIBRARIES=${JNI_LIBRARIES}")
endif ()

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set (CMAKE_OSX_ARCHITECTURES "arm64;x86_64")

add_subdirectory(abseil-cpp)

set(ZSTD_BUILD_PROGRAMS OFF)
set(ZSTD_BUILD_SHARED OFF)
add_subdirectory(zstd/build/cmake)

include_directories(${JNI_INCLUDE_DIRS} zstd/lib/)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}")
if(NOT MSVC)
	set(CMAKE_CXX_FLAGS "-Wall -Wno-multichar -O3")
else()
	set(CMAKE_CXX_FLAGS "-O2")
endif()

add_library(Profiler SHARED src/profiler.cpp)
target_link_libraries(Profiler absl::flat_hash_map absl::time libzstd_static)
