package com.usefulunpacker

import java.util.concurrent.ConcurrentHashMap

/**
 * Same-archive mutex (TODO Phase 3): an archive (or a split-volume set) can be
 * open in at most ONE window at a time.
 *
 * keyed by [archiveKey] (canonical path; volume members normalize to the first
 * part, so `name.zip.001/.002/.zip` share one entry). Owned by a [TabState]
 * rather than an index — the owner survives tab deletion, so a stale index
 * after closeTab can never mis-target a jump.
 *
 * Registered when an archive is opened (preview / edit), released on dialog
 * dismiss, tab close, or activity destroy.
 */
object OpenArchiveRegistry {

    private val map = ConcurrentHashMap<String, TabState>()

    /** Registers [canonical] for [tab]. Returns false when another tab owns it. */
    fun register(canonical: String, tab: TabState): Boolean {
        val existing = map[canonical]
        if (existing != null && existing !== tab) return false
        map[canonical] = tab
        return true
    }

    /** Drops the registration for [canonical] (dialog dismissed / edit finished). */
    fun unregister(canonical: String) {
        map.remove(canonical)
    }

    /** Owner tab for [canonical], or null when not open. */
    fun owner(canonical: String): TabState? = map[canonical]

    /** Drops every registration owned by [tab] (tab closed). */
    fun releaseTab(tab: TabState) {
        map.entries.removeAll { it.value === tab }
    }

    /** Drops every registration (activity destroyed). */
    fun clearAll() {
        map.clear()
    }
}