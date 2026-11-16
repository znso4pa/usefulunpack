package com.usefulunpacker

internal data class PopupBounds(val x: Int, val y: Int, val width: Int, val height: Int)

/** All coordinates are relative to the visible window frame, including keyboard insets. */
internal fun popupBounds(frameWidth: Int, frameHeight: Int, safeTop: Int, anchorRight: Int,
                         anchorBottom: Int, desiredWidth: Int, desiredHeight: Int, margin: Int): PopupBounds {
    val fw = frameWidth.coerceAtLeast(1)
    val fh = frameHeight.coerceAtLeast(1)
    val horizontalMargin = margin.coerceIn(0, (fw - 1) / 2)
    val top = safeTop.coerceIn(0, fh - 1)
    val bottomMargin = margin.coerceIn(0, fh - top - 1)
    val width = desiredWidth.coerceIn(1, fw - 2 * horizontalMargin)
    val height = desiredHeight.coerceIn(1, fh - top - bottomMargin)
    return PopupBounds((anchorRight - width).coerceIn(horizontalMargin, fw - horizontalMargin - width),
        anchorBottom.coerceIn(top, fh - bottomMargin - height), width, height)
}
