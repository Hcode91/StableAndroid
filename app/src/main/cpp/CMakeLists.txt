cmake_minimum_required(VERSION 3.22)
project(stableandroid C CXX)

set(SDCPP_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/stable-diffusion.cpp)
if(NOT EXISTS ${SDCPP_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "stable-diffusion.cpp not found. Run scripts/fetch_sdcpp.sh first.")
endif()

# ggml / stable-diffusion.cpp options for Android arm64 CPU
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)        # avoid shipping libomp.so
set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_WEBP OFF CACHE BOOL "" FORCE)
set(SD_WEBM OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(${SDCPP_DIR} sdcpp)

add_library(stableandroid SHARED sd_jni.cpp)
target_include_directories(stableandroid PRIVATE ${SDCPP_DIR}/include)
target_link_libraries(stableandroid PRIVATE stable-diffusion android log)
target_compile_options(stableandroid PRIVATE -O3)
