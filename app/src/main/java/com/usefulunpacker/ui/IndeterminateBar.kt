package com.usefulunpacker

import android.content.Context
import android.util.AttributeSet
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View

/**
 * Custom indeterminate progress bar that FLOWS (a bright accent bar sweeping
 * across a dim track) using a manual animation loop.
 *
 * Honor/EMUI's stock ProgressBar indeterminate animation doesn't render on some
 * ROMs (the bar shows static/full instead of moving). This view draws itself
 * and animates via a Handler tick — no ROM animation framework involved, so it
 * flows everywhere.
 */
class IndeterminateBar : View {

    constructor(context: Context) : super(context) { init() }
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) { init() }
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle) { init() }

    private fun init() {
        // Custom-drawn view; make sure onDraw is not skipped.
        setWillNotDraw(false)
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF.toInt() }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C["accent"]!! }
    private val handler = Handler(Looper.getMainLooper())
    private var phase = 0f
    private var running = false
    private var attached = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running || !attached) return
            phase += 0.02f
            if (phase > 1f) phase = 0f
            invalidate()
            handler.postDelayed(this, 16L) // ~60fps
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        if (running) handler.postDelayed(tick, 16L)
    }

    override fun onDetachedFromWindow() {
        attached = false
        running = false
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    fun startAnim() {
        running = true
        if (attached) handler.postDelayed(tick, 16L)
    }

    fun stopAnim() {
        running = false
        handler.removeCallbacks(tick)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h / 2f
        // Dim track across the full width.
        canvas.drawRoundRect(RectF(0f, 0f, w, h), radius, radius, trackPaint)
        // Flowing bar: a bright segment sweeping left→right, with a trailing
        // fade so it reads as motion rather than a static fill.
        val barW = (w * 0.28f).coerceAtLeast(40f)
        val head = phase * (w + barW) - barW
        // Gradient-free fade: draw the bright core + a translucent tail.
        val tail = (barW * 0.6f).coerceAtLeast(24f)
        canvas.drawRoundRect(RectF(head, 0f, (head + barW).coerceAtMost(w), h), radius, radius, barPaint)
        val fade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF.toInt() }
        canvas.drawRoundRect(RectF(head - tail, 0f, head, h), radius, radius, fade)
    }
}