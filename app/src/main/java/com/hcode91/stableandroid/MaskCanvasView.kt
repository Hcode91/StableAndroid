package com.hcode91.stableandroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Shows an image and lets the user paint an inpainting mask over it.
 * One finger paints / erases; two fingers pan and zoom.
 */
class MaskCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var image: Bitmap? = null
        private set
    /** ALPHA_8 bitmap, same size as [image]; opaque = repaint. */
    var mask: Bitmap? = null
        private set
    var result: Bitmap? = null
        set(value) { field = value; invalidate() }
    var showResult = false
        set(value) { field = value; invalidate() }
    /** When set, the view just shows this bitmap (Create tab): no mask, swipe to browse. */
    var preview: Bitmap? = null
        set(value) { field = value; invalidate() }
    var previewHint = "Tap Photo to choose a picture"
    /** Shown instead of [preview] while the user presses and holds (compare). */
    var previewOriginal: Bitmap? = null
    private var holdingOriginal = false
    private val showOriginalRunnable = Runnable { holdingOriginal = true; invalidate() }
    private val previewMatrix = Matrix()

    var erasing = false
    /** Brush diameter in screen pixels. */
    var brushSizePx = 60f
        set(value) { field = value; invalidate() }

    var onMaskChanged: (() -> Unit)? = null
    /** Called with -1 / +1 when the user swipes left/right while a result is shown. */
    var onSwipe: ((Int) -> Unit)? = null
    private var swipeStartX = 0f
    private var swipeStartY = 0f

    private val viewMatrix = Matrix()
    private val inverse = Matrix()
    private var userScale = 1f
    private var panX = 0f
    private var panY = 0f

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val maskOverlayPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        color = Color.argb(140, 255, 60, 60)
    }
    private val brushOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textSize = 42f
        textAlign = Paint.Align.CENTER
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val clearMode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)

    private val undoStack = ArrayDeque<Bitmap>()
    private val maxUndo = 6

    private var maskCanvas: Canvas? = null
    private val strokePath = Path()
    private var painting = false
    private var multiTouchThisGesture = false
    private var lastImgX = 0f
    private var lastImgY = 0f
    private var cursorX = -1f
    private var cursorY = -1f
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val old = userScale
                userScale = (userScale * d.scaleFactor).coerceIn(1f, 12f)
                val f = userScale / old
                // keep the focus point stable while zooming
                panX = d.focusX - (d.focusX - panX) * f
                panY = d.focusY - (d.focusY - panY) * f
                updateMatrix()
                return true
            }
        })

    /** Create tab: show [preview] only. */
    var previewMode = false
        set(value) { field = value; invalidate() }

    fun setImage(bmp: Bitmap, keepMask: Boolean = false) {
        if (restoring) finishRestore()?.recycle()   // a new image ends any restore in progress
        val old = mask
        image = bmp
        val newMask = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ALPHA_8)
        if (keepMask && old != null && old.width == bmp.width && old.height == bmp.height) {
            Canvas(newMask).drawBitmap(old, 0f, 0f, null)
        }
        mask = newMask
        maskCanvas = Canvas(newMask)
        undoStack.clear()
        result = null
        showResult = false
        userScale = 1f; panX = 0f; panY = 0f
        updateMatrix()
        onMaskChanged?.invoke()
    }

    fun hasMask(): Boolean {
        val m = mask ?: return false
        val bounds = ImageOps.maskBounds(m)
        return bounds != null
    }

    // ------------------------------------------------------------ restore-original brush

    /** True while the user is painting the original back over an edited image. */
    var restoring = false
        private set
    private var restoreBase: Bitmap? = null
    private var restoreOrig: Bitmap? = null
    private var restoreMask: Bitmap? = null
    private var restoreCanvas: Canvas? = null
    // Drawing an ALPHA_8 bitmap with a shader paints the shader's pixels wherever the bitmap is
    // opaque. With the original photo as the shader, the restore mask reveals the original.
    private val revealPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    /** Share of the picture restored by the last finishRestore(), 0–100. */
    var lastRestoredPercent = 0
        private set

    /** The bitmap and canvas the brush currently paints into (inpaint mask or restore mask). */
    private fun targetMask(): Bitmap? = if (restoring) restoreMask else mask
    private fun targetCanvas(): Canvas? = if (restoring) restoreCanvas else maskCanvas

    /**
     * Starts painting [original] back over [base]. Both must be the size of the open image.
     * The view keeps references; the caller must not recycle them until restore ends.
     */
    fun startRestore(base: Bitmap, original: Bitmap): Boolean {
        val img = image ?: return false
        if (base.width != img.width || base.height != img.height ||
            original.width != base.width || original.height != base.height) return false
        val m = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ALPHA_8)
        restoreBase = base; restoreOrig = original
        restoreMask = m; restoreCanvas = Canvas(m)
        revealPaint.shader = android.graphics.BitmapShader(original,
            android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        undoStack.forEach { it.recycle() }; undoStack.clear()
        erasing = false
        restoring = true
        invalidate()
        return true
    }

    /** Ends restore mode and returns the combined image, or null if nothing was painted. */
    fun finishRestore(): Bitmap? {
        val base = restoreBase; val orig = restoreOrig; val m = restoreMask
        var out: Bitmap? = null
        lastRestoredPercent = 0
        if (base != null && orig != null && m != null) {
            // Blend row by row from the mask's alpha, so the result doesn't depend on how the
            // graphics system treats alpha-only bitmaps.
            val w = base.width; val h = base.height
            val alpha = ImageOps.alphaOf(m)
            var touched = 0L
            for (a in alpha) if (a.toInt() != 0) touched++
            if (touched > 0) {
                val result = base.copy(Bitmap.Config.ARGB_8888, true)
                val brow = IntArray(w); val orow = IntArray(w)
                for (y in 0 until h) {
                    var any = false
                    val off = y * w
                    for (x in 0 until w) if (alpha[off + x].toInt() != 0) { any = true; break }
                    if (!any) continue
                    result.getPixels(brow, 0, w, 0, y, w, 1)
                    orig.getPixels(orow, 0, w, 0, y, w, 1)
                    for (x in 0 until w) {
                        val a = alpha[off + x].toInt() and 0xFF
                        if (a == 0) continue
                        val o = orow[x]
                        if (a == 255) { brow[x] = o or (0xFF shl 24); continue }
                        val b = brow[x]
                        val r = (((o shr 16) and 0xFF) * a + ((b shr 16) and 0xFF) * (255 - a)) / 255
                        val g = (((o shr 8) and 0xFF) * a + ((b shr 8) and 0xFF) * (255 - a)) / 255
                        val bl = ((o and 0xFF) * a + (b and 0xFF) * (255 - a)) / 255
                        brow[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                    }
                    result.setPixels(brow, 0, w, 0, y, w, 1)
                }
                out = result
                lastRestoredPercent = maxOf(1, (touched * 100 / alpha.size).toInt())
            }
        }
        revealPaint.shader = null
        restoring = false
        restoreMask?.recycle()
        restoreBase = null; restoreOrig = null; restoreMask = null; restoreCanvas = null
        undoStack.forEach { it.recycle() }; undoStack.clear()
        erasing = false
        invalidate()
        return out
    }

    fun clearMask() {
        pushUndo()
        targetMask()?.eraseColor(Color.TRANSPARENT)
        invalidate(); onMaskChanged?.invoke()
    }

    fun invertMask() {
        val m = targetMask() ?: return
        pushUndo()
        val inv = ImageOps.invertMask(m)
        Canvas(m).apply {
            drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            drawBitmap(inv, 0f, 0f, null)
        }
        inv.recycle()
        invalidate(); onMaskChanged?.invoke()
    }

    fun undo() {
        val m = targetMask() ?: return
        val prev = undoStack.removeLastOrNull() ?: return
        Canvas(m).apply {
            drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            drawBitmap(prev, 0f, 0f, null)
        }
        prev.recycle()
        invalidate(); onMaskChanged?.invoke()
    }

    fun resetView() {
        userScale = 1f; panX = 0f; panY = 0f
        updateMatrix()
    }

    private fun pushUndo() {
        val m = targetMask() ?: return
        undoStack.addLast(m.copy(Bitmap.Config.ALPHA_8, false))
        while (undoStack.size > maxUndo) undoStack.removeFirst().recycle()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
    }

    private fun updateMatrix() {
        val img = image
        viewMatrix.reset()
        if (img != null && width > 0 && height > 0) {
            val fit = min(width.toFloat() / img.width, height.toFloat() / img.height)
            val dx = (width - img.width * fit) / 2f
            val dy = (height - img.height * fit) / 2f
            viewMatrix.postScale(fit, fit)
            viewMatrix.postTranslate(dx, dy)
            viewMatrix.postScale(userScale, userScale)
            viewMatrix.postTranslate(panX, panY)
        }
        viewMatrix.invert(inverse)
        invalidate()
    }

    private fun currentScale(): Float {
        val v = FloatArray(9)
        viewMatrix.getValues(v)
        return v[Matrix.MSCALE_X]
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (previewMode) {
            val p = if (holdingOriginal && previewOriginal != null) previewOriginal else preview
            if (p == null) {
                canvas.drawText(previewHint, width / 2f, height / 2f, hintPaint)
            } else {
                val fit = min(width.toFloat() / p.width, height.toFloat() / p.height)
                previewMatrix.reset()
                previewMatrix.postScale(fit, fit)
                previewMatrix.postTranslate((width - p.width * fit) / 2f, (height - p.height * fit) / 2f)
                canvas.drawBitmap(p, previewMatrix, bitmapPaint)
            }
            return
        }
        val img = image
        if (img == null) {
            canvas.drawText("Tap “Image” to open a picture", width / 2f, height / 2f, hintPaint)
            return
        }
        if (restoring) {
            // Edited image underneath; the original shows through wherever the user has painted.
            restoreBase?.let { canvas.drawBitmap(it, viewMatrix, bitmapPaint) }
            restoreMask?.let { canvas.drawBitmap(it, viewMatrix, revealPaint) }
            if (cursorX >= 0) {
                brushOutline.color = if (erasing) Color.CYAN else Color.GREEN
                canvas.drawCircle(cursorX, cursorY, brushSizePx / 2f, brushOutline)
            }
            return
        }
        val shown = if (showResult && result != null) result!! else img
        canvas.drawBitmap(shown, viewMatrix, bitmapPaint)
        if (!showResult) {
            mask?.let { canvas.drawBitmap(it, viewMatrix, maskOverlayPaint) }
            if (cursorX >= 0) {
                brushOutline.color = if (erasing) Color.CYAN else Color.WHITE
                canvas.drawCircle(cursorX, cursorY, brushSizePx / 2f, brushOutline)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (previewMode) {
            if (preview == null) return false
        } else if (image == null) return false
        if ((showResult || previewMode) && !restoring) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeStartX = event.x; swipeStartY = event.y
                    if (previewMode && previewOriginal != null) postDelayed(showOriginalRunnable, 150)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.x - swipeStartX) > 30) removeCallbacks(showOriginalRunnable)
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(showOriginalRunnable)
                    if (holdingOriginal) { holdingOriginal = false; invalidate() }
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(showOriginalRunnable)
                    if (holdingOriginal) { holdingOriginal = false; invalidate() }
                    val dx = event.x - swipeStartX
                    val dy = event.y - swipeStartY
                    if (kotlin.math.abs(dx) > 100 * resources.displayMetrics.density / 2 &&
                        kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        onSwipe?.invoke(if (dx < 0) 1 else -1)
                    }
                }
            }
            return true
        }
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multiTouchThisGesture = false
                pushUndo()
                startStroke(event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger: this gesture is a pan/zoom, revert any paint.
                if (!multiTouchThisGesture) {
                    multiTouchThisGesture = true
                    if (painting) {
                        painting = false
                        undo()
                    }
                }
                lastFocusX = focusX(event); lastFocusY = focusY(event)
                cursorX = -1f
            }
            MotionEvent.ACTION_MOVE -> {
                if (multiTouchThisGesture) {
                    if (event.pointerCount >= 2) {
                        val fx = focusX(event); val fy = focusY(event)
                        panX += fx - lastFocusX; panY += fy - lastFocusY
                        lastFocusX = fx; lastFocusY = fy
                        updateMatrix()
                    }
                } else if (painting) {
                    for (i in 0 until event.historySize) {
                        continueStroke(event.getHistoricalX(i), event.getHistoricalY(i))
                    }
                    continueStroke(event.x, event.y)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Re-anchor focus so remaining finger doesn't jump.
                val skip = event.actionIndex
                var sx = 0f; var sy = 0f; var n = 0
                for (i in 0 until event.pointerCount) if (i != skip) { sx += event.getX(i); sy += event.getY(i); n++ }
                if (n > 0) { lastFocusX = sx / n; lastFocusY = sy / n }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (painting) {
                    painting = false
                    onMaskChanged?.invoke()
                } else if (multiTouchThisGesture) {
                    // nothing painted; drop the snapshot we pushed on DOWN
                }
                cursorX = -1f
                invalidate()
            }
        }
        return true
    }

    private fun focusX(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getX(i); return s / e.pointerCount }
    private fun focusY(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getY(i); return s / e.pointerCount }

    private fun toImage(x: Float, y: Float): FloatArray {
        val p = floatArrayOf(x, y)
        inverse.mapPoints(p)
        return p
    }

    private fun configureStrokePaint() {
        strokePaint.strokeWidth = max(1f, brushSizePx / currentScale())
        strokePaint.xfermode = if (erasing) clearMode else null
        // The restore brush has a soft edge so the original blends into the edit.
        strokePaint.maskFilter = if (restoring)
            android.graphics.BlurMaskFilter(max(1f, strokePaint.strokeWidth * 0.3f), android.graphics.BlurMaskFilter.Blur.NORMAL)
        else null
    }

    private fun startStroke(x: Float, y: Float) {
        painting = true
        configureStrokePaint()
        val p = toImage(x, y)
        lastImgX = p[0]; lastImgY = p[1]
        // a dot for single taps
        val dot = Paint(strokePaint).apply { style = Paint.Style.FILL }
        targetCanvas()?.drawCircle(p[0], p[1], strokePaint.strokeWidth / 2f, dot)
        cursorX = x; cursorY = y
        invalidate()
    }

    private fun continueStroke(x: Float, y: Float) {
        val p = toImage(x, y)
        strokePath.reset()
        strokePath.moveTo(lastImgX, lastImgY)
        strokePath.lineTo(p[0], p[1])
        targetCanvas()?.drawPath(strokePath, strokePaint)
        lastImgX = p[0]; lastImgY = p[1]
        cursorX = x; cursorY = y
        invalidate()
    }

    /** Visible image rect in view coordinates (for debugging/UI). */
    fun imageRectOnScreen(): RectF? {
        val img = image ?: return null
        val r = RectF(0f, 0f, img.width.toFloat(), img.height.toFloat())
        viewMatrix.mapRect(r)
        return r
    }
}
