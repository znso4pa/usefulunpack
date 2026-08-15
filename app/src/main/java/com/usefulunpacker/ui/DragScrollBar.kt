package com.usefulunpacker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Draggable vertical scrollbar overlay for text previews/editors.
 *
 * Honor/EMUI-safe: it draws itself (a plain View) and drives the target's
 * scrollTo — it never touches the ROM's broken `onDrawScrollBars` path (which
 * NPEs on `mScrollBar.mutate()` when the ScrollBarDrawable is null). It also
 * gives the "grab the thumb and drag to jump" UX the default scrollbar lacks.
 */
class DragScrollBar(context: Context) : View(context) {

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE635ACC6.toInt() }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF.toInt() }

    private var target: View? = null
    private var thumbTop = 0f
    private var thumbHeight = 0f
    private var dragging = false
    private var dragStartY = 0f
    private var dragStartTop = 0f
    private var dragStartOffset = 0

    /** Attach the scrollable this bar drives (ScrollView / EditText / TextView). */
    fun attach(view: View) {
        target = view
        view.setOnScrollChangeListener { _, _, _, _, _ -> updateThumb() }
        updateThumb()
    }

    private fun metrics(): Triple<Int, Int, Int> { // range, extent, offset
        val t = target ?: return Triple(0, 0, 0)
        // computeVerticalScrollRange/Extent/Offset are protected on View —
        // derive the same numbers from public members:
        //   range  = content height (text layout / ScrollView child)
        //   extent = viewport height
        //   offset = scrollY
        val extent = t.height
        val offset = t.scrollY
        val range = when (t) {
            is android.widget.ScrollView -> t.getChildAt(0)?.height ?: t.height
            is android.widget.TextView -> t.layout?.height ?: t.height
            else -> t.height
        }
        return Triple(range, extent, offset)
    }

    fun updateThumb() {
        val (range, extent, offset) = metrics()
        val h = height.toFloat()
        if (range <= extent) {
            thumbHeight = 0f
            thumbTop = 0f
            invalidate()
            return
        }
        thumbHeight = (h * extent / range).coerceAtLeast(24f)
        val maxTop = h - thumbHeight
        val frac = offset.toFloat() / (range - extent).toFloat()
        thumbTop = (maxTop * frac).coerceIn(0f, maxTop)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val trackLeft = w * 0.3f
        val trackRight = w * 0.7f
        canvas.drawRoundRect(RectF(trackLeft, 0f, trackRight, h), w * 0.2f, w * 0.2f, trackPaint)
        if (thumbHeight > 0) {
            canvas.drawRoundRect(RectF(0f, thumbTop, w, thumbTop + thumbHeight), w / 2, w / 2, thumbPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val t = target ?: return false
                val (range, extent, _) = metrics()
                if (range <= extent) return false
                dragging = true
                dragStartY = event.y
                dragStartTop = thumbTop
                dragStartOffset = t.scrollY
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return true
                val t = target ?: return false
                val h = height.toFloat()
                val maxTop = (h - thumbHeight).coerceAtLeast(0f)
                thumbTop = (dragStartTop + (event.y - dragStartY)).coerceIn(0f, maxTop)
                val (range, extent, _) = metrics()
                if (range > extent) {
                    val maxScroll = range - extent
                    val frac = if (maxTop > 0) thumbTop / maxTop else 0f
                    t.scrollTo(0, (frac * maxScroll).toInt())
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateThumb()
    }
}
