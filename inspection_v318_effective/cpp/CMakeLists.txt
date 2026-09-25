cmake_minimum_required(VERSION 3.22)
project(onsamiro_transcriber LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(WHISPER_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp")
if(NOT EXISTS "${WHISPER_ROOT}/CMakeLists.txt")
    message(FATAL_ERROR "whisper.cpp source missing. Run scripts/fetch_whisper.sh first")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
add_subdirectory("${WHISPER_ROOT}" "${CMAKE_CURRENT_BINARY_DIR}/whisper")

add_library(onsamiro_whisper SHARED whisper_jni.cpp)
target_include_directories(onsamiro_whisper PRIVATE "${WHISPER_ROOT}/include")
find_library(log-lib log)
target_link_libraries(onsamiro_whisper PRIVATE whisper ${log-lib} android)
# Android 15+ 16 KiB page-size compatibility. The old binary libonsamiro_core.so had 0x1000 PT_LOAD alignment.
target_link_options(onsamiro_whisper PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
