package com.usefulunpacker

import org.junit.Assert.*
import org.junit.Test

class DialogFirstDrawGateTest {
    @Test fun repeatedLayoutRequestsCannotKeepAWindowUndrawn() {
        val gate = DialogFirstDrawGate()
        assertTrue(gate.defer(true))
        repeat(1000) { assertFalse(gate.defer(true)) }
    }

    @Test fun anAlreadySettledWindowCanDrawImmediately() {
        assertFalse(DialogFirstDrawGate().defer(false))
    }
}
