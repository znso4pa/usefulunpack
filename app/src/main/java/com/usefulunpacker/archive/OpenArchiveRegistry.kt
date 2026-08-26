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

    /**
     * Takeover variant for session restore: on rotation the dying activity's
     * tab may still own the entry when the new instance restores its preview.
     * The restoring tab is authoritative, so overwrite unconditionally —
     * combined with onDestroy's clearFor(dyingTabs) the registry converges to
     * the new owner regardless of which side runs first.
     */
    fun forceRegister(canonical: String, tab: TabState) {
        map[canonical] = tab
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

    /**
     * Drops registrations owned by the given tabs only. Used on activity
     * destroy: a rotation destroys the old activity AFTER the new one has
     * started restoring previews, so a blanket clearAll() would wipe the new
     * instance's freshly-registered archives and silently kill the same-
     * archive mutex. TabState instances are per-activity, so ownership is a
     * precise generation marker.
     */
    fun clearFor(tabs: Collection<TabState>) {
        map.entries.removeAll { entry -> tabs.any { it === entry.value } }
    }
}