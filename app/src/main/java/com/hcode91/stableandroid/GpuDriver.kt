package com.hcode91.stableandroid

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Custom Adreno GPU driver (Turnip) packaged the way emulators use it: a .zip with a
 * meta.json ("libraryName", "name") and the driver .so. Installed into private app storage.
 */
object GpuDriver {
    class Info(val dir: File, val library: String, val name: String)

    private fun dir(ctx: Context) = File(ctx.filesDir, "gpu_driver")

    fun installed(ctx: Context): Info? {
        val d = dir(ctx)
        val meta = File(d, "meta.json")
        if (!meta.exists()) return null
        return try {
            val j = JSONObject(meta.readText())
            val lib = j.getString("libraryName")
            if (!File(d, lib).exists()) null
            else Info(d, lib, j.optString("name", lib) + j.optString("driverVersion", "").let { if (it.isEmpty()) "" else " ($it)" })
        } catch (_: Throwable) { null }
    }

    fun remove(ctx: Context) { dir(ctx).deleteRecursively() }

    /** Extracts the driver zip. Throws with a readable message if it isn't a driver package. */
    fun install(ctx: Context, uri: Uri): Info {
        val d = dir(ctx)
        val staging = File(ctx.filesDir, "gpu_driver.new")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            var total = 0L
            ctx.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        // Flatten paths (no folders, no traversal) and keep only what a driver needs.
                        val name = e.name.substringAfterLast('/')
                        if (e.isDirectory || !(name.endsWith(".so") || name == "meta.json")) continue
                        File(staging, name).outputStream().use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > 300L * 1024 * 1024) throw IllegalStateException("Archive is too large to be a driver")
                                out.write(buf, 0, n)
                            }
                        }
                    }
                }
            } ?: throw IllegalStateException("Can't open the chosen file")
            val meta = File(staging, "meta.json")
            if (!meta.exists()) throw IllegalStateException("No meta.json inside: this isn't an Adreno driver .zip")
            val lib = JSONObject(meta.readText()).optString("libraryName", "")
            if (lib.isEmpty() || !File(staging, lib).exists()) {
                throw IllegalStateException("The driver library named in meta.json is missing from the .zip")
            }
            d.deleteRecursively()
            if (!staging.renameTo(d)) throw IllegalStateException("Couldn't store the driver")
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
        return installed(ctx) ?: throw IllegalStateException("Driver install failed")
    }
}
