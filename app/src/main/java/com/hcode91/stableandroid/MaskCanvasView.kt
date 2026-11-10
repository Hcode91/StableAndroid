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

    var erasing = false
    /** Brush diameter in screen pixels. */
    var brushSizePx = 60f
        set(value) { field = value; invalidate() }

    var onMaskChanged: (() -> Unit)? = null

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

    fun setImage(bmp: Bitmap, keepMask: Boolean = false) {
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

    fun clearMask() {
        pushUndo()
        mask?.eraseColor(Color.TRANSPARENT)
        invalidate(); onMaskChanged?.invoke()
    }

    fun invertMask() {
        val m = mask ?: return
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
        val m = mask ?: return
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
        val m = mask ?: return
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
        val img = image
        if (img == null) {
            canvas.drawText("Tap “Image” to open a picture", width / 2f, height / 2f, hintPaint)
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
        if (image == null || showResult) return false
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
    }

    private fun startStroke(x: Float, y: Float) {
        painting = true
        configureStrokePaint()
        val p = toImage(x, y)
        lastImgX = p[0]; lastImgY = p[1]
        // a dot for single taps
        val dot = Paint(strokePaint).apply { style = Paint.Style.FILL }
        maskCanvas?.drawCircle(p[0], p[1], strokePaint.strokeWidth / 2f, dot)
        cursorX = x; cursorY = y
        invalidate()
    }

    private fun continueStroke(x: Float, y: Float) {
        val p = toImage(x, y)
        strokePath.reset()
        strokePath.moveTo(lastImgX, lastImgY)
        strokePath.lineTo(p[0], p[1])
        maskCanvas?.drawPath(strokePath, strokePaint)
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
