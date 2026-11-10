// JNI bridge between the Kotlin UI and stable-diffusion.cpp.
// Progress and logs are stored here and polled from Kotlin, so no JNI calls
// are ever made from native worker threads.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

#include "stable-diffusion.h"

#define TAG "StableAndroid"

namespace {

std::mutex g_ctx_mutex;
sd_ctx_t* g_ctx = nullptr;

std::mutex g_log_mutex;
std::deque<std::string> g_logs;
constexpr size_t kMaxLogs = 400;

std::atomic<int> g_step{0};
std::atomic<int> g_steps{0};
std::atomic<float> g_step_time{0.f};
std::atomic<int> g_sample_pass{0};
std::atomic<int> g_pass_base{0};
std::mutex g_stage_mutex;
std::string g_stage;

void push_log(const std::string& line) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_logs.push_back(line);
    while (g_logs.size() > kMaxLogs) g_logs.pop_front();
}

void log_cb(enum sd_log_level_t level, const char* text, void*) {
    if (!text) return;
    int prio = ANDROID_LOG_INFO;
    const char* lvl = "I";
    switch (level) {
        case SD_LOG_DEBUG: prio = ANDROID_LOG_DEBUG; lvl = "D"; break;
        case SD_LOG_VERBOSE: prio = ANDROID_LOG_VERBOSE; lvl = "V"; break;
        case SD_LOG_INFO: prio = ANDROID_LOG_INFO; lvl = "I"; break;
        case SD_LOG_WARN: prio = ANDROID_LOG_WARN; lvl = "W"; break;
        case SD_LOG_ERROR: prio = ANDROID_LOG_ERROR; lvl = "E"; break;
    }
    __android_log_print(prio, TAG, "%s", text);
    if (level == SD_LOG_DEBUG || level == SD_LOG_VERBOSE) return;
    std::string line(text);
    while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) line.pop_back();
    if (line.empty()) return;
    push_log(std::string(lvl) + " " + line);
}

void progress_cb(int step, int steps, float time, void*) {
    g_step.store(step);
    g_steps.store(steps);
    g_step_time.store(time);
}

void gen_progress_cb(const sd_generation_progress_t* p, void*) {
    if (!p) return;
    g_sample_pass.store(p->sample_pass);
    std::lock_guard<std::mutex> lock(g_stage_mutex);
    g_stage = p->stage ? p->stage : "";
    if (p->detail && p->detail[0]) {
        g_stage += ": ";
        g_stage += p->detail;
    }
}

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// Builds a Java String from arbitrary UTF-8 (NewStringUTF rejects 4-byte sequences).
jstring to_jstring(JNIEnv* env, const std::string& s) {
    jbyteArray bytes = env->NewByteArray((jsize)s.size());
    env->SetByteArrayRegion(bytes, 0, (jsize)s.size(), reinterpret_cast<const jbyte*>(s.data()));
    jclass strCls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(strCls, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto out = (jstring)env->NewObject(strCls, ctor, bytes, charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(strCls);
    return out;
}

void throw_java(JNIEnv* env, const std::string& msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls) env->ThrowNew(cls, msg.c_str());
}

std::once_flag g_init_once;
void ensure_callbacks() {
    std::call_once(g_init_once, [] {
        sd_set_log_callback(log_cb, nullptr);
        sd_set_progress_callback(progress_cb, nullptr);
    });
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_hcode91_stableandroid_SdNative_systemInfo(JNIEnv* env, jclass) {
    ensure_callbacks();
    std::string info = std::string("sd.cpp ") + sd_version() + " (" + sd_commit() + ")\n" + sd_get_system_info();
    return to_jstring(env, info);
}

// One "name<TAB>description" per line, e.g. "Vulkan0\tAdreno (TM) 740".
JNIEXPORT jstring JNICALL
Java_com_hcode91_stableandroid_SdNative_listDevices(JNIEnv* env, jclass) {
    ensure_callbacks();
    size_t n = sd_list_devices(nullptr, 0);
    std::string buf(n + 1, '\0');
    sd_list_devices(buf.data(), buf.size());
    buf.resize(strlen(buf.c_str()));
    return to_jstring(env, buf);
}

JNIEXPORT jint JNICALL
Java_com_hcode91_stableandroid_SdNative_physicalCores(JNIEnv*, jclass) {
    return sd_get_num_physical_cores();
}

// Returns the detected model version name (e.g. "SD 1.x Inpaint").
JNIEXPORT jstring JNICALL
Java_com_hcode91_stableandroid_SdNative_loadModel(JNIEnv* env, jclass, jstring jpath, jstring jvae,
                                                  jint threads, jint wtype, jboolean flashAttn,
                                                  jboolean mmap, jstring jbackend) {
    ensure_callbacks();
    std::lock_guard<std::mutex> lock(g_ctx_mutex);
    if (g_ctx) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
    }
    std::string path = jstr(env, jpath);
    std::string vae = jstr(env, jvae);
    std::string backend = jstr(env, jbackend);

    sd_ctx_params_t p;
    sd_ctx_params_init(&p);
    p.model_path = path.c_str();
    if (!vae.empty()) p.vae_path = vae.c_str();
    if (threads > 0) p.n_threads = threads;
    p.wtype = (wtype < 0) ? SD_TYPE_COUNT : (enum sd_type_t)wtype;
    p.flash_attn = flashAttn;
    p.diffusion_flash_attn = flashAttn;
    p.enable_mmap = mmap;
    p.rng_type = CUDA_RNG;  // match A1111/diffusers seeds
    if (!backend.empty()) p.backend = backend.c_str();  // "cpu" or "vulkan0"

    push_log("I loading model (" + std::to_string(p.n_threads) + " threads, backend " +
             (backend.empty() ? std::string("auto") : backend) + ")");
    g_ctx = new_sd_ctx(&p);
    if (!g_ctx) {
        throw_java(env, "Failed to load model. Check the log for details.");
        return nullptr;
    }
    sd_set_generation_progress_callback(g_ctx, gen_progress_cb, nullptr);
    if (!sd_ctx_supports_image_generation(g_ctx)) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
        throw_java(env, "This model can't generate images.");
        return nullptr;
    }
    return to_jstring(env, sd_get_model_version_name(g_ctx));
}

JNIEXPORT void JNICALL
Java_com_hcode91_stableandroid_SdNative_freeModel(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_ctx_mutex);
    if (g_ctx) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_hcode91_stableandroid_SdNative_isLoaded(JNIEnv*, jclass) {
    return g_ctx != nullptr;
}

// init: RGB bytes (w*h*3); mask: one byte per pixel (w*h), 255 = repaint.
// Generates batchCount images (seeds seed, seed+1, ...) and returns their RGB
// bytes concatenated (n * w*h*3); n can be smaller if stopped early.
JNIEXPORT jbyteArray JNICALL
Java_com_hcode91_stableandroid_SdNative_inpaint(JNIEnv* env, jclass, jbyteArray jinit, jbyteArray jmask,
                                                jint width, jint height, jstring jprompt,
                                                jstring jneg, jint steps, jfloat cfg, jfloat strength,
                                                jlong seed, jint sampler, jint scheduler, jint clipSkip,
                                                jboolean vaeTiling, jint batchCount) {
    std::lock_guard<std::mutex> lock(g_ctx_mutex);
    if (!g_ctx) {
        throw_java(env, "No model loaded.");
        return nullptr;
    }
    const size_t npx = (size_t)width * (size_t)height;
    if ((size_t)env->GetArrayLength(jinit) != npx * 3 || (size_t)env->GetArrayLength(jmask) != npx) {
        throw_java(env, "Image/mask size mismatch.");
        return nullptr;
    }
    std::vector<uint8_t> init(npx * 3), mask(npx);
    env->GetByteArrayRegion(jinit, 0, (jsize)init.size(), reinterpret_cast<jbyte*>(init.data()));
    env->GetByteArrayRegion(jmask, 0, (jsize)mask.size(), reinterpret_cast<jbyte*>(mask.data()));

    std::string prompt = jstr(env, jprompt);
    std::string neg = jstr(env, jneg);

    sd_img_gen_params_t g;
    sd_img_gen_params_init(&g);
    g.prompt = prompt.c_str();
    g.negative_prompt = neg.c_str();
    g.clip_skip = clipSkip;
    g.init_image = {(uint32_t)width, (uint32_t)height, 3, init.data()};
    g.mask_image = {(uint32_t)width, (uint32_t)height, 1, mask.data()};
    g.width = width;
    g.height = height;
    g.strength = strength;
    g.seed = seed;
    g.batch_count = batchCount < 1 ? 1 : batchCount;
    // Tiled VAE keeps the encode/decode memory peak small (avoids OOM kills).
    g.vae_tiling_params.enabled = vaeTiling;
    g.sample_params.sample_steps = steps;
    g.sample_params.guidance.txt_cfg = cfg;
    g.sample_params.sample_method =
        (sampler < 0 || sampler >= SAMPLE_METHOD_COUNT) ? SAMPLE_METHOD_COUNT : (enum sample_method_t)sampler;
    g.sample_params.scheduler =
        (scheduler < 0 || scheduler >= SCHEDULER_COUNT) ? SCHEDULER_COUNT : (enum scheduler_t)scheduler;

    g_step.store(0);
    g_steps.store(steps);
    g_pass_base.store(g_sample_pass.load());
    sd_cancel_generation(g_ctx, SD_CANCEL_RESET);

    sd_image_generation_result_t* result = nullptr;
    bool ok = generate_image(g_ctx, &g, &result);
    if (!ok || !result || result->count < 1 || !result->items[0].image.data) {
        if (result) free_sd_image_generation_result(result);
        throw_java(env, "Generation failed or was cancelled.");
        return nullptr;
    }
    std::vector<uint8_t> all;
    all.reserve(npx * 3 * result->count);
    for (int k = 0; k < result->count; k++) {
        sd_image_t& img = result->items[k].image;
        if (!img.data || (int)img.width != width || (int)img.height != height || img.channel < 3) {
            push_log("W skipping output " + std::to_string(k + 1) + " with unexpected size");
            continue;
        }
        size_t off = all.size();
        all.resize(off + npx * 3);
        for (size_t i = 0; i < npx; i++) {
            memcpy(&all[off + i * 3], &img.data[i * img.channel], 3);
        }
    }
    if (all.empty()) {
        free_sd_image_generation_result(result);
        throw_java(env, "Generation produced no usable images.");
        return nullptr;
    }
    jbyteArray out = env->NewByteArray((jsize)all.size());
    env->SetByteArrayRegion(out, 0, (jsize)all.size(), reinterpret_cast<const jbyte*>(all.data()));
    free_sd_image_generation_result(result);
    return out;
}

JNIEXPORT void JNICALL
Java_com_hcode91_stableandroid_SdNative_cancel(JNIEnv*, jclass, jboolean finishCurrent) {
    // Deliberately no ctx mutex: generation holds it.
    sd_ctx_t* ctx = g_ctx;
    if (ctx) sd_cancel_generation(ctx, finishCurrent ? SD_CANCEL_NEW_LATENTS : SD_CANCEL_ALL);
}

// [step, steps, image number (1-based, 0 before sampling)]
JNIEXPORT jintArray JNICALL
Java_com_hcode91_stableandroid_SdNative_progress(JNIEnv* env, jclass) {
    jint v[3] = {g_step.load(), g_steps.load(), g_sample_pass.load() - g_pass_base.load()};
    jintArray arr = env->NewIntArray(3);
    env->SetIntArrayRegion(arr, 0, 3, v);
    return arr;
}

JNIEXPORT jfloat JNICALL
Java_com_hcode91_stableandroid_SdNative_lastStepTime(JNIEnv*, jclass) {
    return g_step_time.load();
}

JNIEXPORT jstring JNICALL
Java_com_hcode91_stableandroid_SdNative_stage(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_stage_mutex);
    return to_jstring(env, g_stage);
}

// Returns and clears pending log lines, joined with '\n'.
JNIEXPORT jstring JNICALL
Java_com_hcode91_stableandroid_SdNative_drainLog(JNIEnv* env, jclass) {
    std::string out;
    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        for (auto& l : g_logs) {
            out += l;
            out += '\n';
        }
        g_logs.clear();
    }
    return to_jstring(env, out);
}

}  // extern "C"
