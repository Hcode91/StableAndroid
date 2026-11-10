package com.hcode91.stableandroid

/** Thin wrapper around the native stable-diffusion.cpp bridge (sd_jni.cpp). */
object SdNative {
    init {
        System.loadLibrary("stableandroid")
    }

    @JvmStatic external fun systemInfo(): String
    @JvmStatic external fun physicalCores(): Int

    /** Returns the detected model version, e.g. "SD 1.x Inpaint". Throws on failure. */
    @JvmStatic external fun loadModel(
        path: String,
        vaePath: String?,
        threads: Int,
        weightType: Int,
        flashAttn: Boolean,
        mmap: Boolean,
    ): String

    @JvmStatic external fun freeModel()
    @JvmStatic external fun isLoaded(): Boolean

    /**
     * init: RGB bytes (w*h*3), mask: w*h bytes (255 = repaint).
     * Returns RGB bytes of the generated image. Throws on failure/cancel.
     */
    @JvmStatic external fun inpaint(
        init: ByteArray,
        mask: ByteArray,
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
    ): ByteArray

    @JvmStatic external fun cancel()
    @JvmStatic external fun progress(): IntArray
    @JvmStatic external fun lastStepTime(): Float
    @JvmStatic external fun stage(): String
    @JvmStatic external fun drainLog(): String
}
