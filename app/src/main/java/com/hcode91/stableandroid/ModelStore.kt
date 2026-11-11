package com.hcode91.stableandroid

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import java.io.File
import java.io.RandomAccessFile

/**
 * Copies picked models into the app's own storage so stable-diffusion.cpp
 * can open them by a real file path (SAF file descriptors often can't be
 * reopened or seeked by native code).
 */
object ModelStore {

    /** [folder] is "models" for checkpoints or "loras" for LoRA add-ons; each keeps one file. */
    fun dir(ctx: Context, folder: String = "models"): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, folder).apply { mkdirs() }

    fun queryName(ctx: Context, uri: Uri): Pair<String, Long> {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: "model.safetensors"
                val size = if (!c.isNull(1)) c.getLong(1) else -1L
                return name to size
            }
        }
        return (uri.lastPathSegment?.substringAfterLast('/') ?: "model.safetensors") to -1L
    }

    /**
     * Copies [uri] into the models folder (skipping if an identical-size copy exists)
     * and deletes other models there to save space. Returns the local file.
     */
    fun import(ctx: Context, uri: Uri, folder: String = "models",
               onProgress: (copied: Long, total: Long) -> Unit): File {
        val (rawName, size) = queryName(ctx, uri)
        val name = rawName.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val dir = dir(ctx, folder)
        val dest = File(dir, name)
        if (dest.exists() && size > 0 && dest.length() == size) return dest

        if (size > 0) {
            val free = StatFs(dir.path).availableBytes
            val reclaim = dir.listFiles()?.filter { it != dest }?.sumOf { it.length() } ?: 0L
            if (free + reclaim < size + 200L * 1024 * 1024) {
                throw IllegalStateException(
                    "Not enough free storage to copy the model (needs ${gb(size)}, ${gb(free + reclaim)} available)")
            }
        }
        // Only one model is kept to save space.
        dir.listFiles()?.forEach { if (it != dest) it.delete() }

        val tmp = File(dir, "$name.part")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(4 * 1024 * 1024)
                    var copied = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (copied - lastReport > 32L * 1024 * 1024) {
                            lastReport = copied
                            onProgress(copied, size)
                        }
                    }
                    onProgress(copied, size)
                }
            } ?: throw IllegalStateException("Can't open the chosen file")
            if (size > 0 && tmp.length() != size) {
                throw IllegalStateException("Copy incomplete (${tmp.length()} of $size bytes)")
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IllegalStateException("Couldn't finalise the copied model")
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        return dest
    }

    /** Returns null if the file looks loadable, otherwise a human-readable reason. */
    fun checkFile(f: File): String? {
        if (!f.exists()) return "File not found"
        val len = f.length()
        if (len < 1024 * 1024) return "File is only ${len} bytes. It's not a full model (maybe a failed or placeholder download)."
        RandomAccessFile(f, "r").use { raf ->
            val head = ByteArray(16)
            raf.readFully(head)
            // zip-based .ckpt / .pt
            if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) return null
            // gguf
            if (String(head, 0, 4, Charsets.US_ASCII) == "GGUF") return null
            // safetensors: u64 little-endian header length, then a JSON object
            var headerLen = 0L
            for (i in 7 downTo 0) headerLen = (headerLen shl 8) or (head[i].toLong() and 0xFF)
            if (headerLen <= 2 || headerLen > len - 8 || headerLen > 100L * 1024 * 1024) {
                val preview = head.joinToString(" ") { "%02x".format(it) }
                return "Not a valid .safetensors file (header length $headerLen; first bytes $preview)"
            }
            if (head[8] != '{'.code.toByte()) return "Not a valid .safetensors file (header doesn't start with '{')"
        }
        return null
    }

    fun gb(bytes: Long) = "%.2f GB".format(bytes / 1_073_741_824.0)
}
