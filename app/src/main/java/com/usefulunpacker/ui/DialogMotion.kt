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
            setTag(R.id.dialogMotionCovered, null)
            present(covered = false, animated = false)
        }
    }

    fun settle(dialog: Dialog) {
        panel(dialog)?.apply { present(getTag(R.id.dialogMotionCovered) == true, animated = false) }
    }

    fun setCovered(dialog: Dialog, covered: Boolean) {
        val panel = panel(dialog) ?: return
        if ((panel.getTag(R.id.dialogMotionCovered) == true) == covered) return
        panel.setTag(R.id.dialogMotionCovered, covered)
        panel.present(covered, animated = ValueAnimator.areAnimatorsEnabled())
    }

    private fun View.present(covered: Boolean, animated: Boolean) {
        animate().cancel()
        val opacity = if (covered) 0.76f else 1f
        val scale = if (covered) 0.96f else 1f
        val offset = if (covered) -context.dp(6).toFloat() else 0f
        if (animated) {
            animate().alpha(opacity).scaleX(scale).scaleY(scale).translationY(offset)
                .setDuration(240L).setInterpolator(easing).start()
        } else {
            alpha = opacity
            scaleX = scale
            scaleY = scale
            translationY = offset
        }
    }

    fun enter(dialog: Dialog, menu: Boolean, sheet: Boolean) {
        val panel = panel(dialog) ?: return
        if (!ValueAnimator.areAnimatorsEnabled() || panel.getTag(R.id.dialogMotionCovered) == true ||
            panel.width <= 0 || panel.height <= 0) return
        panel.alpha = 0.32f
        panel.translationY = panel.context.dp(if (menu) -4 else if (sheet) 14 else 5).toFloat()
        val scale = if (sheet) 1f else 0.98f
        panel.scaleX = scale
        panel.scaleY = scale
        panel.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(if (sheet) 390L else 330L).setInterpolator(easing).start()
    }
}
