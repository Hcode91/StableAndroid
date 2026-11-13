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

# --- GPU (Vulkan) backend --------------------------------------------------
# glslc comes from the NDK, vulkan.hpp from Vulkan-Headers, libvulkan from the sysroot.
set(TP_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party)
if(NOT EXISTS ${TP_DIR}/Vulkan-Headers/include/vulkan/vulkan.hpp)
    message(FATAL_ERROR "Vulkan-Headers not found. Run scripts/fetch_sdcpp.sh first.")
endif()
if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
    set(NDK_HOST_TAG darwin-x86_64)
elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
    set(NDK_HOST_TAG windows-x86_64)
else()
    set(NDK_HOST_TAG linux-x86_64)
endif()
find_library(ANDROID_VULKAN_LIB vulkan REQUIRED)
set(Vulkan_INCLUDE_DIR ${TP_DIR}/Vulkan-Headers/include CACHE PATH "" FORCE)
set(Vulkan_LIBRARY ${ANDROID_VULKAN_LIB} CACHE FILEPATH "" FORCE)
set(Vulkan_GLSLC_EXECUTABLE ${ANDROID_NDK}/shader-tools/${NDK_HOST_TAG}/glslc CACHE FILEPATH "" FORCE)
set(SPIRV-Headers_DIR ${TP_DIR}/spirv-headers/share/cmake/SPIRV-Headers CACHE PATH "" FORCE)
set(SD_VULKAN ON CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)

add_subdirectory(${SDCPP_DIR} sdcpp)
# ggml-vulkan finds SPIRV-Headers but doesn't add its include dir itself.
target_include_directories(ggml-vulkan PRIVATE ${TP_DIR}/spirv-headers/include)

# libadrenotools: optional custom GPU driver (Turnip). Its hook libraries are shared
# and must be packaged (and extracted) with the app.
add_subdirectory(${TP_DIR}/libadrenotools adrenotools)

add_library(stableandroid SHARED sd_jni.cpp vk_shim.cpp gif_writer.cpp)
add_dependencies(stableandroid main_hook file_redirect_hook gsl_alloc_hook hook_impl)
target_include_directories(stableandroid PRIVATE ${SDCPP_DIR}/include)
target_link_libraries(stableandroid PRIVATE stable-diffusion adrenotools android log)
target_compile_options(stableandroid PRIVATE -O3)
