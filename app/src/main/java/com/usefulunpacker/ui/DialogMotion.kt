package com.usefulunpacker

import android.animation.ValueAnimator
import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator

/** Animate the measured panel, never the native window or its touch region. */
internal object DialogMotion {
    private val easing = PathInterpolator(0.2f, 0f, 0f, 1f)

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
        panel.pivotX = if (menu) {
            if (panel.layoutDirection == View.LAYOUT_DIRECTION_RTL) 0f else panel.width.toFloat()
        } else panel.width / 2f
        panel.pivotY = if (menu) 0f else panel.height / 2f
        // Remain visible even if a ROM cancels an animator without an end callback.
        panel.alpha = 0.92f
        panel.scaleX = if (menu) 0.97f else 0.985f
        panel.scaleY = panel.scaleX
        panel.translationY = (if (menu) -4f else if (sheet) 12f else 6f) * panel.resources.displayMetrics.density
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(if (menu) 140L else 200L).setInterpolator(easing).start()
    }
}
