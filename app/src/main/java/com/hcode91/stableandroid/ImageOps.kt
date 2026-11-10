package com.hcode91.stableandroid

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageOps {

    /** Decodes an image (EXIF rotation applied) with its long side capped at [maxSide]. */
    fun load(resolver: ContentResolver, uri: Uri, maxSide: Int = 2048): Bitmap {
        val src = ImageDecoder.createSource(resolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            val w = info.size.width
            val h = info.size.height
            val longSide = max(w, h)
            if (longSide > maxSide) {
                val s = maxSide.toFloat() / longSide
                decoder.setTargetSize((w * s).roundToInt().coerceAtLeast(1), (h * s).roundToInt().coerceAtLeast(1))
            }
        }
        return if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, true)
    }

    /** Bounding box of mask pixels with alpha > 127, or null if the mask is empty. */
    fun maskBounds(mask: Bitmap): Rect? {
        val alpha = alphaOf(mask)
        val w = mask.width
        val h = mask.height
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((alpha[row + x].toInt() and 0xFF) > 127) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        return if (maxX < 0) null else Rect(minX, minY, maxX + 1, maxY + 1)
    }

    /** Per-pixel alpha (w*h bytes) of any bitmap. */
    fun alphaOf(bmp: Bitmap): ByteArray {
        val argb = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
        val px = IntArray(argb.width * argb.height)
        argb.getPixels(px, 0, argb.width, 0, 0, argb.width, argb.height)
        if (argb !== bmp) argb.recycle()
        return ByteArray(px.size) { (px[it] ushr 24).toByte() }
    }

    fun invertMask(mask: Bitmap): Bitmap {
        val a = alphaOf(mask)
        val px = IntArray(a.size) { (255 - (a[it].toInt() and 0xFF)) shl 24 }
        val argb = Bitmap.createBitmap(px, mask.width, mask.height, Bitmap.Config.ARGB_8888)
        val out = argb.copy(Bitmap.Config.ALPHA_8, true)
        argb.recycle()
        return out
    }

    fun rgbBytes(bmp: Bitmap): ByteArray {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val out = ByteArray(px.size * 3)
        for (i in px.indices) {
            val c = px[i]
            out[i * 3] = (c shr 16).toByte()
            out[i * 3 + 1] = (c shr 8).toByte()
            out[i * 3 + 2] = c.toByte()
        }
        return out
    }

    fun fromRgb(rgb: ByteArray, w: Int, h: Int): Bitmap {
        val px = IntArray(w * h)
        for (i in px.indices) {
            val r = rgb[i * 3].toInt() and 0xFF
            val g = rgb[i * 3 + 1].toInt() and 0xFF
            val b = rgb[i * 3 + 2].toInt() and 0xFF
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun round64(v: Float): Int = max(64, (v / 64f).roundToInt() * 64)

    /** Everything needed to run one inpaint pass and paste the result back. */
    class Job(
        val region: Rect,
        val width: Int,
        val height: Int,
        val rgb: ByteArray,
        val mask: ByteArray,
    )

    /**
     * Picks the area to inpaint (only around the mask, or the whole image),
     * scales it so its long side is about [targetSide] (multiples of 64),
     * and packs the image + binary mask for the native side.
     */
    fun prepare(image: Bitmap, mask: Bitmap, onlyMasked: Boolean, targetSide: Int, padding: Int): Job? {
        val bounds = maskBounds(mask) ?: return null
        val region = if (onlyMasked) {
            val pad = max(padding, (max(bounds.width(), bounds.height()) * 0.15f).roundToInt())
            val r = Rect(bounds.left - pad, bounds.top - pad, bounds.right + pad, bounds.bottom + pad)
            // Give the model a reasonable amount of context: at least targetSide/2 px each way.
            val minSide = min(targetSide / 2, min(image.width, image.height))
            if (r.width() < minSide) { val d = (minSide - r.width()) / 2; r.left -= d; r.right += d }
            if (r.height() < minSide) { val d = (minSide - r.height()) / 2; r.top -= d; r.bottom += d }
            shiftInside(r, image.width, image.height)
            r
        } else {
            Rect(0, 0, image.width, image.height)
        }
        val scale = targetSide.toFloat() / max(region.width(), region.height())
        val w = round64(region.width() * scale)
        val h = round64(region.height() * scale)

        val cropImg = Bitmap.createBitmap(image, region.left, region.top, region.width(), region.height())
        val scaledImg = Bitmap.createScaledBitmap(cropImg, w, h, true)
        val rgb = rgbBytes(scaledImg)
        if (scaledImg !== cropImg) scaledImg.recycle()
        if (cropImg !== image) cropImg.recycle()

        val cropMask = Bitmap.createBitmap(mask, region.left, region.top, region.width(), region.height())
        val scaledMask = Bitmap.createScaledBitmap(cropMask, w, h, true)
        val a = alphaOf(scaledMask)
        val bin = ByteArray(a.size) { if ((a[it].toInt() and 0xFF) > 32) 255.toByte() else 0 }
        if (scaledMask !== cropMask) scaledMask.recycle()
        if (cropMask !== mask) cropMask.recycle()

        return Job(region, w, h, rgb, bin)
    }

    private fun shiftInside(r: Rect, w: Int, h: Int) {
        if (r.width() > w) { r.left = 0; r.right = w }
        if (r.height() > h) { r.top = 0; r.bottom = h }
        if (r.left < 0) r.offset(-r.left, 0)
        if (r.top < 0) r.offset(0, -r.top)
        if (r.right > w) r.offset(w - r.right, 0)
        if (r.bottom > h) r.offset(0, h - r.bottom)
    }

    /**
     * Pastes the generated image back into a copy of the original.
     * With [blend], only the (feathered) masked area is replaced, so the rest of
     * the photo keeps its full resolution and is untouched by the VAE.
     */
    fun composite(original: Bitmap, mask: Bitmap, job: Job, generated: Bitmap, blend: Boolean): Bitmap {
        val out = original.copy(Bitmap.Config.ARGB_8888, true)
        val rw = job.region.width()
        val rh = job.region.height()
        val gen = Bitmap.createScaledBitmap(generated, rw, rh, true)
        if (!blend) {
            Canvas(out).drawBitmap(gen, job.region.left.toFloat(), job.region.top.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
            if (gen !== generated) gen.recycle()
            return out
        }
        val soft = featheredMask(mask, job.region)
        val gpx = IntArray(rw * rh)
        gen.getPixels(gpx, 0, rw, 0, 0, rw, rh)
        val opx = IntArray(rw * rh)
        out.getPixels(opx, 0, rw, job.region.left, job.region.top, rw, rh)
        for (i in gpx.indices) {
            val a = soft[i].toInt() and 0xFF
            if (a == 0) continue
            if (a == 255) { opx[i] = gpx[i]; continue }
            val g = gpx[i]; val o = opx[i]
            val r = (((g shr 16) and 0xFF) * a + ((o shr 16) and 0xFF) * (255 - a)) / 255
            val gg = (((g shr 8) and 0xFF) * a + ((o shr 8) and 0xFF) * (255 - a)) / 255
            val b = ((g and 0xFF) * a + (o and 0xFF) * (255 - a)) / 255
            opx[i] = (0xFF shl 24) or (r shl 16) or (gg shl 8) or b
        }
        out.setPixels(opx, 0, rw, job.region.left, job.region.top, rw, rh)
        if (gen !== generated) gen.recycle()
        return out
    }

    /** Mask alpha for [region], dilated slightly and blurred (via down/up scaling). */
    private fun featheredMask(mask: Bitmap, region: Rect): ByteArray {
        val rw = region.width(); val rh = region.height()
        val crop = Bitmap.createBitmap(mask, region.left, region.top, rw, rh)
        val radius = max(4, min(rw, rh) / 48)
        val sw = max(1, rw / radius); val sh = max(1, rh / radius)
        val small = Bitmap.createScaledBitmap(crop, sw, sh, true)
        // dilate a little at the small scale so the blur doesn't eat into the mask
        val sa = alphaOf(small)
        val dil = ByteArray(sa.size)
        for (y in 0 until sh) for (x in 0 until sw) {
            var m = 0
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until sw && ny in 0 until sh) m = max(m, sa[ny * sw + nx].toInt() and 0xFF)
            }
            dil[y * sw + x] = m.toByte()
        }
        val dpx = IntArray(dil.size) { (dil[it].toInt() and 0xFF) shl 24 }
        val dilBmp = Bitmap.createBitmap(dpx, sw, sh, Bitmap.Config.ARGB_8888)
        val big = Bitmap.createScaledBitmap(dilBmp, rw, rh, true)
        val soft = alphaOf(big)
        // keep the user's hard mask fully opaque inside
        val hard = alphaOf(crop)
        for (i in soft.indices) {
            if ((hard[i].toInt() and 0xFF) > 127) soft[i] = 255.toByte()
        }
        if (crop !== mask) crop.recycle()
        if (small !== crop) small.recycle()
        if (big !== dilBmp) big.recycle()
        dilBmp.recycle()
        return soft
    }
}
