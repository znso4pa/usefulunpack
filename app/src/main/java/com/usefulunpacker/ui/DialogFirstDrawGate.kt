package com.usefulunpacker

/** A moving layout can request one extra traversal, never hold the first surface indefinitely. */
internal class DialogFirstDrawGate {
    private var deferred = false
    fun defer(needsLayout: Boolean): Boolean {
        if (!needsLayout || deferred) return false
        deferred = true
        return true
    }
}
