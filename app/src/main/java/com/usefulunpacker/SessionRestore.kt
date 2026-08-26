package com.usefulunpacker

import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

private const val SESSION_PREF = "session_json"

/**
 * Persists the multi-window session (each tab's directory + an open archive
 * preview) and restores it on launch when "restore last session" is enabled.
 * Restoring reuses listPreviewEntries + renderPreview, so tabs come back to
 * the same folders AND the same opened archive previews (password kept).
 */
internal fun MainActivity.saveSession() {
    if (!prefs.getBoolean("restore_session", false)) return
    val arr = JSONArray()
    for (tab in tabs) {
        val o = JSONObject()
        o.put("dir", tab.currentDir.absolutePath)
        // Custom window names survive restarts too (blank = default "窗口 N").
        if (tab.title.isNotBlank()) o.put("title", tab.title)
        if (tab.previewActive && tab.previewSrc != null && tab.previewFormat != null) {
            o.put("preview", 1)
            o.put("src", tab.previewSrc!!.absolutePath)
            o.put("fmt", tab.previewFormat!!)
            o.put("pwd", tab.previewPwd)
        }
        arr.put(o)
    }
    val root = JSONObject()
    root.put("active", activeTabIndex)
    root.put("tabs", arr)
    prefs.edit().putString(SESSION_PREF, root.toString()).apply()
}

private class PendingPreview(val src: File, val fmt: String, val pwd: String)

internal fun MainActivity.restoreSession() {
    if (!prefs.getBoolean("restore_session", false)) { addTab(); return }
    val raw = prefs.getString(SESSION_PREF, null) ?: run { addTab(); return }
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: run { addTab(); return }
    val savedTabs = root.optJSONArray("tabs") ?: run { addTab(); return }
    if (savedTabs.length() == 0) { addTab(); return }

    // "如果 3 个了就弹 toast": restoring more than the window cap is clamped.
    if (savedTabs.length() > MainActivity.MAX_TABS) {
        toast(getString(R.string.msg_max_tabs))
    }
    val count = minOf(savedTabs.length(), MainActivity.MAX_TABS)
    val dirs = ArrayList<File>(count)
    val previews = arrayOfNulls<PendingPreview>(count)
    for (i in 0 until count) {
        val o = savedTabs.optJSONObject(i) ?: continue
        dirs.add(sanitizeDir(File(o.optString("dir", ""))))
        if (o.optInt("preview", 0) == 1) {
            val src = File(o.optString("src", ""))
            val fmt = o.optString("fmt", "")
            if (src.exists() && fmt.isNotEmpty()) {
                previews[i] = PendingPreview(src, fmt, o.optString("pwd", ""))
            }
        }
    }
    if (dirs.isEmpty()) { addTab(); return }

    tabs.clear()
    for (i in dirs.indices) {
        val tab = TabState(i)
        tab.currentDir = dirs[i]
        // Same 8-char cap the rename dialog enforces, so a hand-edited or
        // older-session JSON can't smuggle in an oversized name.
        tab.title = savedTabs.optJSONObject(i)?.optString("title", "")?.take(8) ?: ""
        tabs.add(tab)
    }
    activeTabIndex = root.optInt("active", 0).coerceIn(0, tabs.size - 1)
    rebuildPager()
    viewPager.post {
        viewPager.currentItem = activeTabIndex
        tabAdapter.notifyDataSetChanged()
        updateTitle()
        updatePasteButton()
        if (previews.any { it != null }) {
            // Re-open previews after the pager settles (best effort — a moved
            // or deleted archive just falls back to showing the directory).
            viewPager.postDelayed({ restorePreviews(previews) }, 80L)
        }
    }
}

private fun MainActivity.sanitizeDir(dir: File): File {
    var d = dir
    while (!d.isDirectory) {
        val p = d.parentFile ?: return Environment.getExternalStorageDirectory()
        d = p
    }
    return d
}

private fun MainActivity.restorePreviews(previews: Array<PendingPreview?>) {
    for (i in previews.indices) {
        val pending = previews[i] ?: continue
        if (!pending.src.exists()) continue
        val tab = tabs.getOrNull(i) ?: continue
        thread {
            val entries = listPreviewEntries(pending.fmt, pending.src, pending.pwd)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (entries.isNullOrEmpty()) return@runOnUiThread
                if (tab.previewActive) return@runOnUiThread
                val openKey = archiveKey(pending.src)
                // Force-takeover: the dying activity's tab may still own the
                // key during the rotation window; the restoring tab wins.
                OpenArchiveRegistry.forceRegister(openKey, tab)
                renderPreview(tab, pending.src, entries, pending.fmt, pending.pwd, openKey)
            }
        }
    }
}