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
        // 预览工作区 tab：wsRoot 单独存——用户可能在 ws 内导航深入，
        // currentDir 不再等于工作区根目录。
        tab.wsDir?.let { ws ->
            o.put("ws", 1)
            o.put("wsRoot", ws.absolutePath)
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
    // Parse in LOCKSTEP: one skipped malformed element must not shift later
    // previews/titles onto the wrong tab (tabs are built from this list).
    data class SavedTab(val dir: File, val title: String, val preview: PendingPreview?, val wsRoot: String?)
    val savedList = ArrayList<SavedTab>(count)
    for (i in 0 until count) {
        val o = savedTabs.optJSONObject(i) ?: continue
        // org.json quirk: optString on a JSON null returns the literal "null".
        fun str(key: String): String = o.optString(key, "").takeIf { it != "null" } ?: ""
        val dir = sanitizeDir(File(str("dir")))
        var pending: PendingPreview? = null
        if (o.optInt("preview", 0) == 1) {
            val src = File(str("src"))
            val fmt = str("fmt")
            if (src.exists() && fmt.isNotEmpty()) {
                pending = PendingPreview(src, fmt, str("pwd"))
            }
        }
        val wsRoot = str("wsRoot")
        savedList.add(SavedTab(dir, str("title"), pending, wsRoot))
    }
    if (savedList.isEmpty()) { addTab(); return }
    val previews = arrayOfNulls<PendingPreview>(savedList.size)

    tabs.clear()
    for ((i, st) in savedList.withIndex()) {
        val tab = TabState(i)
        tab.currentDir = st.dir
        // Same 8-char cap the rename dialog enforces, so a hand-edited or
        // older-session JSON can't smuggle in an oversized name. Workspace
        // tabs get a wider cap: their titles are "📦 <archive name>" and the
        // strip label ellipsizes at 96dp anyway.
        val isWs = st.wsRoot != null
        tab.title = st.title.take(if (isWs) 32 else 8)
        // 工作区身份：缓存目录仍在则完整还原（📦 标题随 title 往返 +
        // 关闭时的清理询问）；已被系统清掉则降级为普通目录 tab（sanitizeDir
        // 已把失效路径兜底到存在的祖先目录）。
        if (isWs && File(st.wsRoot).isDirectory) tab.wsDir = File(st.wsRoot)
        tabs.add(tab)
        previews[i] = st.preview
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
            // Re-probe on restore: the note is not persisted, and a probe is
            // cheaper than storing a value that could go stale if the game
            // folder changes between launches.
            val encNote = xp3SchemeToken(pending.fmt, pending.src)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (entries.isNullOrEmpty()) return@runOnUiThread
                if (tab.previewActive) return@runOnUiThread
                val openKey = archiveKey(pending.src)
                // Force-takeover: the dying activity's tab may still own the
                // key during the rotation window; the restoring tab wins.
                OpenArchiveRegistry.forceRegister(openKey, tab)
                renderPreview(tab, pending.src, entries, pending.fmt, pending.pwd, openKey, encNote)
            }
        }
    }
}