package com.usefulunpacker

import org.junit.Assert.*
import org.junit.Test

class TabModalPresentationTest {
    @Test fun switchingTabsRestoresEachStackWithoutGrantingInputToAnotherTab() {
        assertEquals(TabModalPresentation(true, true, true), tabModalPresentation(1, 1, true, true))
        assertEquals(TabModalPresentation(false, false, false), tabModalPresentation(1, 2, true, true))
        assertEquals(TabModalPresentation(true, true, true), tabModalPresentation(2, 2, true, true))
        assertEquals(TabModalPresentation(true, true, true), tabModalPresentation(1, 1, true, true))
    }

    @Test fun nestedParentsStillBlockContentButOnlyTopWindowAcceptsInput() {
        assertEquals(TabModalPresentation(true, false, true), tabModalPresentation(1, 1, true, false))
        assertEquals(TabModalPresentation(true, true, true), tabModalPresentation(1, 1, true, true))
    }

    @Test fun lateAttachmentAndDetachedWindowsCannotAcquireAnotherTabsShield() {
        assertEquals(TabModalPresentation(true, false, false), tabModalPresentation(1, 1, false, true))
        assertEquals(TabModalPresentation(false, false, false), tabModalPresentation(1, 2, false, true))
        assertEquals(TabModalPresentation(false, false, false), tabModalPresentation(1, 2, true, true))
    }
}
