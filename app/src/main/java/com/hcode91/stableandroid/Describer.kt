package com.hcode91.stableandroid

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Describes an image with Florence-2 (base-ft, ONNX) entirely on the device.
 * Needs four model files chosen by the user (vision_encoder, embed_tokens,
 * encoder_model, decoder_model); the vocabulary is bundled with the app.
 */
object Describer {
    private val roles = listOf("vision_encoder", "embed_tokens", "encoder_model", "decoder_model")
    private const val DIM = 768
    private const val SIDE = 768

    // Florence-2 task prompts, already tokenised (BART BPE, with <s> ... </s>).
    private val prompts = mapOf(
        0 to longArrayOf(0, 2264, 473, 5, 2274, 6190, 116, 2),                               // short
        1 to longArrayOf(0, 47066, 21700, 11, 4617, 99, 16, 2343, 11, 5, 2274, 4, 2),        // detailed
        2 to longArrayOf(0, 47066, 21700, 19, 10, 17818, 99, 16, 2343, 11, 5, 2274, 4, 2),   // paragraph
    )

    private fun dir(ctx: Context) = File(ctx.filesDir, "describe").apply { mkdirs() }

    private fun roleOf(fileName: String): String? {
        val n = fileName.lowercase()
        if (!n.endsWith(".onnx")) return null
        if (n.startsWith("decoder_model_merged") || n.startsWith("decoder_with_past")) return null
        return roles.firstOrNull { n.startsWith(it) }
    }

    fun missing(ctx: Context): List<String> = roles.filter { !File(dir(ctx), "$it.onnx").exists() }
    fun installed(ctx: Context) = missing(ctx).isEmpty()
    fun remove(ctx: Context) { dir(ctx).deleteRecursively() }

    /** Copies the picked files in. Returns a readable summary of what was recognised. */
    fun install(ctx: Context, uris: List<Uri>): String {
        val added = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for (uri in uris) {
            val name = ModelStore.queryName(ctx, uri).first
            val role = roleOf(name)
            if (role == null) { skipped += name; continue }
            val tmp = File(dir(ctx), "$role.part")
            ctx.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
                ?: throw IllegalStateException("Can't open $name")
            val dest = File(dir(ctx), "$role.onnx")
            dest.delete()
            if (!tmp.renameTo(dest)) throw IllegalStateException("Couldn't store $name")
            added += name
        }
        val miss = missing(ctx)
        return buildString {
            if (added.isNotEmpty()) append("Added: ${added.joinToString()}. ")
            if (skipped.isNotEmpty()) append("Not used: ${skipped.joinToString()}. ")
            append(if (miss.isEmpty()) "Describe is ready." else "Still needed: ${miss.joinToString { "$it…onnx" }}.")
        }
    }

    /** [detail]: 0 short, 1 detailed, 2 paragraph. Blocking; call off the main thread. */
    fun describe(ctx: Context, image: Bitmap, detail: Int, threads: Int, onStage: (String) -> Unit): String {
        if (!installed(ctx)) throw IllegalStateException("Describe model files are missing")
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads.coerceIn(1, 8)) }
        fun open(role: String) = env.createSession(File(dir(ctx), "$role.onnx").absolutePath, opts)

        // 1. Image -> features
        onStage("Describing: looking at the image…")
        val pixels = preprocess(image)
        val feats: FloatArray
        val featTokens: Int
        open("vision_encoder").use { s ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong())).use { t ->
                s.run(mapOf("pixel_values" to t)).use { r ->
                    val out = r[0] as OnnxTensor
                    featTokens = out.info.shape[1].toInt()
                    feats = FloatArray(featTokens * DIM).also { out.floatBuffer.get(it) }
                }
            }
        }

        open("embed_tokens").use { embed ->
            fun embedIds(ids: LongArray): FloatArray =
                OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { t ->
                    embed.run(mapOf("input_ids" to t)).use { r ->
                        FloatArray(ids.size * DIM).also { (r[0] as OnnxTensor).floatBuffer.get(it) }
                    }
                }

            // 2. Image features + task prompt -> encoder
            onStage("Describing: reading…")
            val promptIds = prompts[detail] ?: prompts.getValue(1)
            val seq = featTokens + promptIds.size
            val encIn = FloatArray(seq * DIM)
            System.arraycopy(feats, 0, encIn, 0, feats.size)
            System.arraycopy(embedIds(promptIds), 0, encIn, feats.size, promptIds.size * DIM)
            val mask = LongArray(seq) { 1 }
            val hidden: FloatArray
            open("encoder_model").use { s ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(encIn), longArrayOf(1, seq.toLong(), DIM.toLong())).use { e ->
                    OnnxTensor.createTensor(env, LongBuffer.wrap(mask), longArrayOf(1, seq.toLong())).use { m ->
                        s.run(mapOf("inputs_embeds" to e, "attention_mask" to m)).use { r ->
                            hidden = FloatArray(seq * DIM).also { (r[0] as OnnxTensor).floatBuffer.get(it) }
                        }
                    }
                }
            }

            // 3. Greedy decoding (start token 2, forced first token 0, stop at 2)
            onStage("Describing: writing…")
            val out = mutableListOf(2L, 0L)
            open("decoder_model").use { dec ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(hidden), longArrayOf(1, seq.toLong(), DIM.toLong())).use { h ->
                    OnnxTensor.createTensor(env, LongBuffer.wrap(mask), longArrayOf(1, seq.toLong())).use { m ->
                        val maxNew = if (detail == 0) 40 else if (detail == 1) 90 else 160
                        for (step in 0 until maxNew) {
                            val emb = embedIds(out.toLongArray())
                            val next = OnnxTensor.createTensor(env, FloatBuffer.wrap(emb),
                                longArrayOf(1, out.size.toLong(), DIM.toLong())).use { d ->
                                dec.run(mapOf("encoder_attention_mask" to m, "encoder_hidden_states" to h,
                                    "inputs_embeds" to d), setOf("logits")).use { r ->
                                    val lt = r[0] as OnnxTensor
                                    val vocab = lt.info.shape[2].toInt()
                                    val row = FloatArray(vocab)
                                    val buf = lt.floatBuffer
                                    buf.position((out.size - 1) * vocab)
                                    buf.get(row)
                                    // never repeat a 3-token sequence
                                    if (out.size >= 3) {
                                        val a = out[out.size - 2]; val b = out[out.size - 1]
                                        for (i in 0 until out.size - 2) {
                                            if (out[i] == a && out[i + 1] == b) row[out[i + 2].toInt()] = -1e9f
                                        }
                                    }
                                    var best = 0
                                    for (i in 1 until vocab) if (row[i] > row[best]) best = i
                                    best.toLong()
                                }
                            }
                            if (next == 2L) break
                            out += next
                        }
                    }
                }
            }
            return tidy(decode(ctx, out))
        }
    }

    /** 768x768, RGB planar, ImageNet mean/std. */
    private fun preprocess(image: Bitmap): FloatArray {
        val bmp = Bitmap.createScaledBitmap(image, SIDE, SIDE, true)
        val px = IntArray(SIDE * SIDE)
        bmp.getPixels(px, 0, SIDE, 0, 0, SIDE, SIDE)
        if (bmp !== image) bmp.recycle()
        val n = SIDE * SIDE
        val out = FloatArray(3 * n)
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        for (i in 0 until n) {
            val c = px[i]
            out[i] = (((c shr 16) and 0xFF) / 255f - mean[0]) / std[0]
            out[n + i] = (((c shr 8) and 0xFF) / 255f - mean[1]) / std[1]
            out[2 * n + i] = ((c and 0xFF) / 255f - mean[2]) / std[2]
        }
        return out
    }

    /** Byte-level BPE decode using the bundled vocabulary. */
    private fun decode(ctx: Context, ids: List<Long>): String {
        val vocab = JSONObject(ctx.assets.open("florence_vocab.json").bufferedReader().use { it.readText() })
        val inv = HashMap<Int, String>(vocab.length())
        for (k in vocab.keys()) inv[vocab.getInt(k)] = k
        // GPT-2 "bytes to unicode" table, inverted
        val bs = ((33..126) + (161..172) + (174..255)).toMutableList()
        val cs = bs.toMutableList()
        var extra = 0
        for (b in 0..255) if (b !in bs) { bs += b; cs += 256 + extra; extra++ }
        val charToByte = HashMap<Char, Byte>()
        for (i in bs.indices) charToByte[cs[i].toChar()] = bs[i].toByte()
        val bytes = java.io.ByteArrayOutputStream()
        for (id in ids) {
            if (id <= 3) continue                      // <s> <pad> </s> <unk>
            val tok = inv[id.toInt()] ?: continue      // location/task tokens aren't in the vocab
            for (ch in tok) charToByte[ch]?.let { bytes.write(it.toInt()) }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8).trim()
    }

    /** Drops the model's stock lead-ins so the text reads like a prompt. */
    private fun tidy(text: String): String {
        var t = text
        val leadIns = listOf("In this image we can see ", "In this image I can see ", "In this picture we can see ",
            "In the image we can see ", "The image shows ", "The image is ", "This image shows ", "This is ")
        for (l in leadIns) if (t.startsWith(l, ignoreCase = true)) { t = t.substring(l.length); break }
        t = t.replace("we can also see ", "", ignoreCase = true).replace("we can see ", "", ignoreCase = true)
        return t.trim().trimEnd('.')
    }
}
