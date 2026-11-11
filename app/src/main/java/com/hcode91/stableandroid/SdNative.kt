package com.hcode91.stableandroid

/** Thin wrapper around the native stable-diffusion.cpp bridge (sd_jni.cpp). */
object SdNative {
    init {
        System.loadLibrary("stableandroid")
    }

    /** Opens a custom Adreno driver (Turnip). Must be the first native call; false if it failed. */
    @JvmStatic external fun setGpuDriver(hookLibDir: String, driverDir: String, driverName: String, tmpDir: String): Boolean
    @JvmStatic external fun systemInfo(): String
    @JvmStatic external fun physicalCores(): Int
    /** "name\tdescription" per line (e.g. CPU, Vulkan0). Touches the GPU driver. */
    @JvmStatic external fun listDevices(): String

    /** Returns the detected model version, e.g. "SD 1.x Inpaint". Throws on failure. */
    @JvmStatic external fun loadModel(
        path: String,
        vaePath: String?,
        threads: Int,
        weightType: Int,
        flashAttn: Boolean,
        mmap: Boolean,
        backend: String,
        taesdPath: String?,
    ): String

    @JvmStatic external fun freeModel()
    @JvmStatic external fun isLoaded(): Boolean

    /**
     * init: RGB bytes (w*h*3), mask: w*h bytes (255 = repaint). Both null = text-to-image.
     * Generates [batchCount] images (seed, seed+1, ...) and returns their RGB bytes
     * concatenated; fewer if stopped early. Throws on failure/cancel.
     */
    @JvmStatic external fun inpaint(
        init: ByteArray?,
        mask: ByteArray?,
        width: Int,
        height: Int,
        prompt: String,
        negativePrompt: String,
        steps: Int,
        cfg: Float,
        strength: Float,
        seed: Long,
        sampler: Int,
        scheduler: Int,
        clipSkip: Int,
        vaeTiling: Boolean,
        batchCount: Int,
        loraPath: String?,
        loraStrength: Float,
    ): ByteArray

    /** finishCurrent = stop after the image being sampled and return what's done. */
    @JvmStatic external fun cancel(finishCurrent: Boolean)
    @JvmStatic external fun progress(): IntArray
    @JvmStatic external fun lastStepTime(): Float
    @JvmStatic external fun stage(): String
    @JvmStatic external fun drainLog(): String
}
