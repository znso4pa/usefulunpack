package com.usefulunpacker

import org.junit.Assert.*
import org.junit.Test

class PopupBoundsTest {
    @Test fun toolbarMenuAlignsToAnchorWithoutCoveringTabs() {
        assertEquals(PopupBounds(752, 100, 240, 240), popupBounds(1000, 700, 50, 992, 100, 240, 240, 8))
    }
    @Test fun longMenuScrollsInsideSpaceBelowTabs() {
        assertEquals(PopupBounds(752, 50, 240, 642), popupBounds(1000, 700, 50, 992, 100, 240, 2000, 8))
    }
    @Test fun tinyKeyboardViewportNeverProducesNegativeDimensionsOrInvalidClamp() {
        assertEquals(PopupBounds(0, 0, 1, 1), popupBounds(1, 1, 50, 200, 100, 240, 500, 8))
    }
    @Test fun oversizedMenuAndOffscreenAnchorStayInsideFrame() {
        val b = popupBounds(180, 300, 60, -40, 900, 240, 100, 8)
        assertEquals(PopupBounds(8, 192, 164, 100), b)
    }
}
