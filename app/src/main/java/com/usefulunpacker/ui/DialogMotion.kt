package com.usefulunpacker

import android.animation.ValueAnimator
import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator

/** Animate the measured panel, never the native window or its touch region. */
internal object DialogMotion {
    // Symmetric speed: no early rush followed by a nearly invisible long tail.
    private val easing = AccelerateDecelerateInterpolator()

    private fun panel(dialog: Dialog): View? =
        dialog.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)

    fun reset(dialog: Dialog) {
        panel(dialog)?.apply {
            animate().cancel()
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
            translationY = 0f
        }
    }

    fun enter(dialog: Dialog, menu: Boolean, sheet: Boolean) {
        reset(dialog)
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val panel = panel(dialog) ?: return
        if (panel.width <= 0 || panel.height <= 0) return
        // Stable glyph size and a single moving surface: no stretching or stationary backplate.
        // The panel stays visible even if a ROM interrupts animation callbacks.
        panel.alpha = 0.12f
        panel.translationY = (if (menu) -6f else if (sheet) 8f else 0f) * panel.resources.displayMetrics.density
        panel.animate().alpha(1f).translationY(0f)
            .setDuration(if (sheet) 360L else 320L).setInterpolator(easing).start()
    }
}
