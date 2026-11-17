package com.usefulunpacker

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/** Visual state only: ownership and touch blocking never wait for this fade. */
internal class DialogScrimMotion(private val scrim: View) {
    private var dimmed = false
    private var generation = 0
    private var disposed = false
    private val easing = AccelerateDecelerateInterpolator()
    private val render = Runnable {
        val expectedGeneration = generation
        if (dimmed) {
            if (scrim.visibility != View.VISIBLE) {
                scrim.alpha = 0f
                scrim.visibility = View.VISIBLE
            }
            scrim.animate().alpha(1f).setStartDelay(0L).setDuration(320L)
                .setInterpolator(easing).start()
        } else if (scrim.visibility == View.VISIBLE) {
            // A menu can detach before its destination attaches to WindowManager.
            // Preserve the current shade across that short handoff, then fade from it.
            scrim.animate().alpha(0f).setStartDelay(80L).setDuration(280L)
                .setInterpolator(easing).withEndAction {
                    if (!disposed && generation == expectedGeneration && !dimmed) {
                        scrim.visibility = View.GONE
                    }
                }.start()
        }
    }

    init { scrim.alpha = 0f }

    fun setDimmed(value: Boolean, animate: Boolean = true) {
        if (disposed) return
        val animated = animate && ValueAnimator.areAnimatorsEnabled()
        if (value == dimmed && animated) return // sync/slide callbacks must not restart a fade.
        dimmed = value
        generation++
        scrim.removeCallbacks(render)
        scrim.animate().withEndAction(null).cancel()
        if (!animated) {
            scrim.alpha = if (value) 1f else 0f
            scrim.visibility = if (value) View.VISIBLE else View.GONE
        } else {
            // Coalesce same-frame remove/add updates into one presentation.
            scrim.postOnAnimation(render)
        }
    }

    fun dispose() {
        disposed = true
        generation++
        scrim.removeCallbacks(render)
        scrim.animate().withEndAction(null).cancel()
        scrim.alpha = 0f
        scrim.visibility = View.GONE
    }
}
