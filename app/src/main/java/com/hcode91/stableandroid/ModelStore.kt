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
     * and returns the local file. With [keepOthers] the folder acts as a library; otherwise
     * other files there are deleted so only one is kept.
     */
    fun import(ctx: Context, uri: Uri, folder: String = "models", keepOthers: Boolean = false,
               onProgress: (copied: Long, total: Long) -> Unit): File {
        val (rawName, size) = queryName(ctx, uri)
        val name = rawName.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val dir = dir(ctx, folder)
        val dest = File(dir, name)
        if (dest.exists() && size > 0 && dest.length() == size) return dest

        if (size > 0) {
            val free = StatFs(dir.path).availableBytes
            val reclaim = if (keepOthers) 0L else dir.listFiles()?.filter { it != dest }?.sumOf { it.length() } ?: 0L
            if (free + reclaim < size + 200L * 1024 * 1024) {
                throw IllegalStateException(
                    "Not enough free storage to copy this (needs ${gb(size)}, ${gb(free + reclaim)} available)" +
                        if (keepOthers) ". Delete a copy under Checkpoints → Delete a copy, or the original after copying." else "")
            }
        }
        if (!keepOthers) dir.listFiles()?.forEach { if (it != dest) it.delete() }

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

    class Entry(val name: String, val size: Long, val uri: Uri?, val local: File?)

    private fun isCheckpointName(n: String) =
        n.endsWith(".safetensors", true) || n.endsWith(".ckpt", true) || n.endsWith(".gguf", true)

    /** Checkpoints already copied into the app. */
    fun library(ctx: Context): List<File> =
        dir(ctx, "models").listFiles()?.filter { it.isFile && isCheckpointName(it.name) }?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    /**
     * Checkpoint-sized files directly inside the folder the user picked (not sub-folders),
     * merged with the app's own copies. Files under 900 MB are skipped: those are LoRAs,
     * VAEs and ControlNets, not checkpoints.
     */
    fun listCheckpoints(ctx: Context, tree: Uri?): List<Entry> {
        val local = library(ctx).associateBy { it.name }
        val out = LinkedHashMap<String, Entry>()
        if (tree != null) {
            try {
                val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
                ctx.contentResolver.query(children, arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        val size = if (c.isNull(2)) 0L else c.getLong(2)
                        if (!isCheckpointName(name) || size < 900L * 1024 * 1024) continue
                        val key = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
                        val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                        out[key] = Entry(name, size, uri, local[key]?.takeIf { it.length() == size })
                    }
                }
            } catch (_: Throwable) { /* folder gone or permission revoked: show the app's copies only */ }
        }
        for ((name, f) in local) if (name !in out) out[name] = Entry(name, f.length(), null, f)
        return out.values.sortedBy { it.name.lowercase() }
    }

    fun gb(bytes: Long) = "%.2f GB".format(bytes / 1_073_741_824.0)
}
