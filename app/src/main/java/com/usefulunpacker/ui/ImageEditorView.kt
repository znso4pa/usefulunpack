package com.usefulunpacker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class EditorTool { BRUSH, CROP, EYEDROP }

private enum class GestureMode { NONE, TOOL, PINCH }

/**
 * Custom editor view: displays a working Bitmap (base) plus a transparent
 * overlay that brush strokes are painted onto. Supports three tools:
 *  - BRUSH  : draw with a selectable color / alpha (watercolor/highlighter)
 *  - CROP   : drag a rectangle, then confirm via performCrop()
 *  - EYEDROP: tap a pixel to sample its color (reports via onColorSampled)
 * Two-finger pinch zooms (1x–8x) and one-finger panning work in every tool;
 * all tools convert through the invertible fit matrix so coordinates stay
 * exact even when zoomed.
 */
class ImageEditorView(context: Context) : View(context) {

    var baseBitmap: Bitmap? = null
        private set
    private var overlay: Bitmap? = null
    private var overlayCanvas: Canvas? = null
    private val strokes = mutableListOf<MutableList<PointF>>()
    private var currentStroke: MutableList<PointF>? = null
    private var lastPoint: PointF? = null

    private val displayMatrix = Matrix()
    private val invMatrix = Matrix()

    var tool: EditorTool = EditorTool.BRUSH

    var brushColor: Int = 0xFFFFE066.toInt()
    var brushAlpha: Int = 120
    var brushWidthPx: Int = 14

    private var cropStart: PointF? = null
    private var cropEnd: PointF? = null

    private var samplePoint: PointF? = null
    var sampledColor: Int = 0
    var onColorSampled: ((Int) -> Unit)? = null

    var onAutosave: (() -> Unit)? = null

    // Zoom / pan
    private var scaleFactor = 1f
    private var panX = 0f
    private var panY = 0f
    private var mode = GestureMode.NONE
    private var startDist = 0f
    private var startScale = 1f
    private var lastMidX = 0f
    private var lastMidY = 0f

    private val brushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val cropPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = 0xFF35acc6.toInt()
        strokeWidth = 2f
    }
    private val maskPaint = Paint().apply { color = 0x66000000.toInt() }
    private val crossPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = 0xFFFF5252.toInt()
        strokeWidth = 1.5f
    }

    private val autosaveHandler = Handler(Looper.getMainLooper())
    private var autosavePending = false

    /** Replaces the working image, resetting all edits and the zoom. */
    fun setBitmap(bmp: Bitmap) {
        baseBitmap = bmp
        overlay = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        overlayCanvas = Canvas(overlay!!)
        strokes.clear(); currentStroke = null; lastPoint = null
        cropStart = null; cropEnd = null; samplePoint = null
        scaleFactor = 1f; panX = 0f; panY = 0f; mode = GestureMode.NONE
        rebuildMatrix()
        invalidate()
    }

    /** Resets zoom/pan to fit (used when entering CROP so the rect maps 1:1). */
    fun resetView() {
        scaleFactor = 1f; panX = 0f; panY = 0f
        rebuildMatrix()
        invalidate()
    }

    private fun rebuildMatrix() {
        val bmp = baseBitmap ?: return
        displayMatrix.reset()
        val vw = width.toFloat().coerceAtLeast(1f)
        val vh = height.toFloat().coerceAtLeast(1f)
        val base = min(vw / bmp.width, vh / bmp.height)
        val dx = (vw - bmp.width * base) / 2f
        val dy = (vh - bmp.height * base) / 2f
        displayMatrix.setScale(base, base)
        displayMatrix.postTranslate(dx, dy)
        displayMatrix.postScale(scaleFactor, scaleFactor, vw / 2f, vh / 2f)
        displayMatrix.postTranslate(panX, panY)
        displayMatrix.invert(invMatrix)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildMatrix()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = baseBitmap ?: return
        canvas.drawBitmap(bmp, displayMatrix, null)
        overlay?.let { canvas.drawBitmap(it, displayMatrix, null) }
        if (tool == EditorTool.CROP) {
            val s = cropStart; val e = cropEnd
            if (s != null && e != null) {
                val rect = RectF(min(s.x, e.x), min(s.y, e.y), max(s.x, e.x), max(s.y, e.y))
                drawMask(canvas, rect)
                canvas.drawRect(rect, cropPaint)
            }
        }
        if (tool == EditorTool.EYEDROP && samplePoint != null) {
            val p = samplePoint!!
            val r = 10f
            canvas.drawLine(p.x - r, p.y, p.x + r, p.y, crossPaint)
            canvas.drawLine(p.x, p.y - r, p.x, p.y + r, crossPaint)
        }
    }

    private fun drawMask(canvas: Canvas, rect: RectF) {
        val vw = width.toFloat(); val vh = height.toFloat()
        canvas.drawRect(0f, 0f, vw, rect.top, maskPaint)
        canvas.drawRect(0f, rect.bottom, vw, vh, maskPaint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, maskPaint)
        canvas.drawRect(rect.right, rect.top, vw, rect.bottom, maskPaint)
    }

    private fun toBitmap(p: PointF): PointF {
        val pts = floatArrayOf(p.x, p.y)
        invMatrix.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }

    private fun dispatchTool(e: MotionEvent) {
        when (tool) {
            EditorTool.BRUSH -> handleBrush(e)
            EditorTool.CROP -> handleCrop(e)
            EditorTool.EYEDROP -> handleEyedrop(e)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Single finger ALWAYS drives the active tool (draw / crop /
                // eyedrop) — even when zoomed, so tools stay pixel-exact.
                mode = GestureMode.TOOL
                dispatchTool(e)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Finalize an in-progress brush stroke before pinching.
                endStroke()
                mode = GestureMode.PINCH
                startDist = dist(e)
                startScale = scaleFactor
                lastMidX = midX(e); lastMidY = midY(e)
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                GestureMode.PINCH -> {
                    val d = dist(e)
                    if (d > 0f) {
                        scaleFactor = (startScale * d / startDist).coerceIn(1f, 8f)
                    }
                    // Two-finger drag pans (midpoint movement).
                    val mx = midX(e); val my = midY(e)
                    panX += mx - lastMidX; panY += my - lastMidY
                    lastMidX = mx; lastMidY = my
                    rebuildMatrix(); invalidate()
                }
                GestureMode.TOOL -> dispatchTool(e)
                GestureMode.NONE -> {}
            }
            MotionEvent.ACTION_POINTER_UP -> {
                mode = GestureMode.TOOL
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == GestureMode.TOOL) dispatchTool(e)
                mode = GestureMode.NONE
            }
        }
        return true
    }

    private fun dist(e: MotionEvent): Float {
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun midX(e: MotionEvent) = (e.getX(0) + e.getX(1)) / 2f
    private fun midY(e: MotionEvent) = (e.getY(0) + e.getY(1)) / 2f

    private fun handleBrush(e: MotionEvent) {
        val bp = toBitmap(PointF(e.x, e.y))
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                currentStroke = mutableListOf(bp)
                strokes.add(currentStroke!!)
                lastPoint = bp
            }
            MotionEvent.ACTION_MOVE -> {
                val lp = lastPoint ?: return
                val c = overlayCanvas ?: return
                brushPaint.color = brushColor
                brushPaint.alpha = brushAlpha
                brushPaint.strokeWidth = brushWidthPx.toFloat()
                c.drawLine(lp.x, lp.y, bp.x, bp.y, brushPaint)
                currentStroke?.add(bp)
                lastPoint = bp
                invalidate()
            }
            MotionEvent.ACTION_UP -> endStroke()
        }
    }

    private fun endStroke() {
        lastPoint = null
        currentStroke = null
        scheduleAutosave()
    }

    private fun handleCrop(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { cropStart = PointF(e.x, e.y); cropEnd = PointF(e.x, e.y); invalidate() }
            MotionEvent.ACTION_MOVE -> { cropEnd = PointF(e.x, e.y); invalidate() }
            else -> {}
        }
    }

    private fun handleEyedrop(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> sampleAt(e)
            else -> {}
        }
    }

    private fun sampleAt(e: MotionEvent) {
        val bmp = baseBitmap ?: return
        val bp = toBitmap(PointF(e.x, e.y))
        val x = bp.x.toInt().coerceIn(0, bmp.width - 1)
        val y = bp.y.toInt().coerceIn(0, bmp.height - 1)
        sampledColor = bmp.getPixel(x, y)
        samplePoint = PointF(e.x, e.y)
        onColorSampled?.invoke(sampledColor)
        invalidate()
    }

    /** The selected crop rectangle in bitmap coordinates, or null. */
    fun cropRectBitmap(): Rect? {
        val s = cropStart ?: return null
        val e = cropEnd ?: return null
        val bmp = baseBitmap ?: return null
        val bs = toBitmap(s); val be = toBitmap(e)
        val x = min(bs.x, be.x).toInt().coerceIn(0, bmp.width - 1)
        val y = min(bs.y, be.y).toInt().coerceIn(0, bmp.height - 1)
        val x2 = max(bs.x, be.x).toInt().coerceIn(0, bmp.width - 1)
        val y2 = max(bs.y, be.y).toInt().coerceIn(0, bmp.height - 1)
        if (x2 - x < 1 || y2 - y < 1) return null
        return Rect(x, y, x2 + 1, y2 + 1)
    }

    /** Crops to the selected rectangle; returns false if no valid selection. */
    fun performCrop(): Boolean {
        val rect = cropRectBitmap() ?: return false
        val bmp = baseBitmap ?: return false
        val cropped = Bitmap.createBitmap(bmp, rect.left, rect.top, rect.width(), rect.height())
        setBitmap(cropped)
        scheduleAutosave()
        return true
    }

    /** Resamples the image to the target aspect ratio (distort to fill). */
    fun stretchTo(ratioW: Int, ratioH: Int) {
        val bmp = baseBitmap ?: return
        val area = bmp.width.toDouble() * bmp.height
        val scale = sqrt(area / (ratioW.toDouble() * ratioH))
        val newW = (ratioW * scale).toInt().coerceAtLeast(1)
        val newH = (ratioH * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bmp, newW, newH, true)
        setBitmap(scaled)
        scheduleAutosave()
    }

    fun undo() {
        if (strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1)
        redrawOverlay()
        invalidate()
        scheduleAutosave()
    }

    fun clearEdits() {
        overlayCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        strokes.clear(); currentStroke = null; lastPoint = null
        cropStart = null; cropEnd = null; samplePoint = null
        invalidate()
        scheduleAutosave()
    }

    private fun redrawOverlay() {
        val c = overlayCanvas ?: return
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        brushPaint.color = brushColor
        brushPaint.alpha = brushAlpha
        brushPaint.strokeWidth = brushWidthPx.toFloat()
        for (stroke in strokes) {
            if (stroke.size < 2) continue
            var prev = stroke[0]
            for (i in 1 until stroke.size) {
                val cur = stroke[i]
                c.drawLine(prev.x, prev.y, cur.x, cur.y, brushPaint)
                prev = cur
            }
        }
    }

    /** Composites base + overlay into a new Bitmap for saving. */
    fun composeResult(): Bitmap? {
        val bmp = baseBitmap ?: return null
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(bmp, 0f, 0f, null)
        overlay?.let { c.drawBitmap(it, 0f, 0f, null) }
        return out
    }

    private fun scheduleAutosave() {
        if (autosavePending) return
        autosavePending = true
        autosaveHandler.postDelayed({
            autosavePending = false
            onAutosave?.invoke()
        }, 400)
    }
}