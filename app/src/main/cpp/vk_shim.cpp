// Routes the few Vulkan entry points ggml links against directly through a
// libvulkan handle we choose at runtime: the system one, or one opened by
// libadrenotools with a custom driver (Turnip). Everything else in ggml goes
// through vkGetInstanceProcAddr, so these four cover the whole backend.

#include <dlfcn.h>
#include <android/log.h>
#include <cstdint>
#include <mutex>

#include <adrenotools/driver.h>

#define HIDDEN __attribute__((visibility("hidden")))

namespace {
std::mutex g_mutex;
void* g_vk = nullptr;
bool g_custom = false;

void* vk_handle() {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_vk) g_vk = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    return g_vk;
}

void* sym(const char* name) {
    void* h = vk_handle();
    return h ? dlsym(h, name) : nullptr;
}
}  // namespace

// Must be called before anything touches Vulkan. Returns true if the custom driver was opened.
HIDDEN bool vk_shim_use_custom_driver(const char* hookDir, const char* driverDir, const char* driverName,
                                      const char* tmpDir) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_vk) return g_custom;  // already initialised; too late to switch
    void* h = adrenotools_open_libvulkan(RTLD_NOW | RTLD_LOCAL, ADRENOTOOLS_DRIVER_CUSTOM, tmpDir, hookDir,
                                         driverDir, driverName, nullptr, nullptr);
    if (h) {
        g_vk = h;
        g_custom = true;
        __android_log_print(ANDROID_LOG_INFO, "StableAndroid", "custom GPU driver opened: %s", driverName);
    } else {
        __android_log_print(ANDROID_LOG_WARN, "StableAndroid", "custom GPU driver failed to open: %s", driverName);
    }
    return g_custom;
}

HIDDEN bool vk_shim_is_custom() { return g_custom; }

extern "C" {

typedef void (*VoidFn)(void);

HIDDEN VoidFn vkGetInstanceProcAddr(void* instance, const char* name) {
    static auto fn = (VoidFn (*)(void*, const char*))sym("vkGetInstanceProcAddr");
    return fn ? fn(instance, name) : nullptr;
}

HIDDEN VoidFn vkGetDeviceProcAddr(void* device, const char* name) {
    static auto fn = (VoidFn (*)(void*, const char*))sym("vkGetDeviceProcAddr");
    return fn ? fn(device, name) : nullptr;
}

HIDDEN void vkGetPhysicalDeviceFeatures2(void* physicalDevice, void* features) {
    static auto fn = (void (*)(void*, void*))sym("vkGetPhysicalDeviceFeatures2");
    if (fn) fn(physicalDevice, features);
}

HIDDEN void vkCmdCopyBuffer(void* commandBuffer, uint64_t src, uint64_t dst, uint32_t regionCount,
                            const void* regions) {
    static auto fn = (void (*)(void*, uint64_t, uint64_t, uint32_t, const void*))sym("vkCmdCopyBuffer");
    if (fn) fn(commandBuffer, src, dst, regionCount, regions);
}

}  // extern "C"
