package com.usefulunpacker

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import kotlin.concurrent.thread

internal fun MainActivity.enterMultiSelect(f: File) = enterMultiSelect(activeTab, f)
internal fun MainActivity.enterMultiSelect(tab: TabState, f: File) {
        tab.multiSelectMode = true; tab.multiSelected.add(f); refreshMultiSelectUI(tab)
        syncMultiBar(tab)
        syncAllTabAdapters()
    }

internal fun MainActivity.toggleMultiSelect(f: File) = toggleMultiSelect(activeTab, f)
internal fun MainActivity.toggleMultiSelect(tab: TabState, f: File) { if (tab.multiSelected.contains(f)) tab.multiSelected.remove(f) else tab.multiSelected.add(f); refreshMultiSelectUI(tab); syncAllTabAdapters() }

internal fun MainActivity.exitMultiSelect() = exitMultiSelect(activeTab)
internal fun MainActivity.exitMultiSelect(tab: TabState) { tab.multiSelectMode = false; tab.multiSelected.clear(); syncMultiBar(tab) }

/** 清空所有 tab 的多选状态。 */
internal fun MainActivity.exitAllMultiSelect() {
    for (t in tabs) { t.multiSelectMode = false; t.multiSelected.clear() }
    syncMultiBar()
    tabAdapter.notifyDataSetChanged()
}

/** 聚合所有 tab 的选中文件：Map<TabState, Set<File>>。 */
internal fun MainActivity.allSelectedFiles(): Map<TabState, Set<File>> =
    tabs.associateWith { it.multiSelected }.filter { it.value.isNotEmpty() }

/** 全局选中文件总数。 */
internal fun MainActivity.totalSelectedCount(): Int = tabs.sumOf { it.multiSelected.size }

internal fun MainActivity.syncMultiBar() = syncMultiBar(activeTab)
internal fun MainActivity.syncMultiBar(tab: TabState) {
        val bar = tab.batchBar ?: return
        val tv = bar.getChildAt(0) as? TextView ?: return
        val isCompress = prefs.getInt("work_mode", 0) == 1
        // 跨 tab 计数：显示全局选中数（多 tab 有选中时标注跨窗口数）
        val globalCount = totalSelectedCount()
        val tabsWithSelection = allSelectedFiles().size
        tv.text = if (tabsWithSelection > 1) {
            getString(R.string.multi_selected_cross_tab, globalCount, tabsWithSelection)
        } else {
            getString(R.string.multi_selected_count, tab.multiSelected.size)
        }
        bar.visibility = if (tab.multiSelectMode) View.VISIBLE else View.GONE
        if (tab.multiSelectMode) {
            bar.post { tab.listFiles.setPadding(tab.listFiles.paddingLeft, tab.listFiles.paddingTop, tab.listFiles.paddingRight, bar.height) }
        } else {
            tab.listFiles.setPadding(tab.listFiles.paddingLeft, tab.listFiles.paddingTop, tab.listFiles.paddingRight, 0)
        }
        // bottomBar (compress-mode selection bar) shares the bottom edge too —
        // same save/restore dance as the FAB, otherwise leaving multi-select
        // leaves it GONE forever (it was hidden on entry and nothing reset it).
        if (tab.multiSelectMode) {
            if (tab.bottomBar.tag == null) tab.bottomBar.tag = tab.bottomBar.visibility
            tab.bottomBar.visibility = View.GONE
        } else {
            val priorBar = tab.bottomBar.tag
            if (priorBar is Int) tab.bottomBar.visibility = priorBar
            tab.bottomBar.tag = null
        }
        // Global add-folder button also floats at the bottom — hide it during
        // multi-select so it can't overlap the batch bar on the active tab.
        btnAddFolder?.visibility = if (tab === activeTab && tab.multiSelectMode) View.GONE else View.VISIBLE
        // The FAB floats at the same bottom edge as the batch bar — hide it in
        // multi-select mode so it doesn't overlap the cancel/delete buttons, and
        // restore its prior visibility when leaving the mode.
        if (tab.multiSelectMode) {
            if (tab.fabExtract.tag == null) tab.fabExtract.tag = tab.fabExtract.visibility
            tab.fabExtract.visibility = View.GONE
        } else {
            val prior = tab.fabExtract.tag
            if (prior is Int) tab.fabExtract.visibility = prior
            tab.fabExtract.tag = null
        }
        // Sync adapter selection state for all tabs and force full redraw
        syncAllTabAdapters()
        // Show/hide extract/preview/compress based on work mode. Buttons carry a
        // semantic tag set in buildBatchBar — never match on display text: the
        // strings embed emojis that changed across releases and the old
        // startsWith matching ended up hiding EXTRACT in extract mode.
        fun walk(v: View) {
            if (v is Button) {
                when (v.tag) {
                    "extract", "preview" -> v.visibility = if (isCompress) View.GONE else View.VISIBLE
                    "compress" -> v.visibility = if (isCompress) View.VISIBLE else View.GONE
                }
            } else if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(bar)
    }

/** 同步所有 tab 的 adapter 选择状态（非活跃 tab 也刷新 checkbox）。 */
internal fun MainActivity.syncAllTabAdapters() {
    for (t in tabs) {
        (t.listFiles.adapter as? FileAdapter)?.multiSelected_ = if (t.multiSelectMode) t.multiSelected else emptySet()
        t.listFiles.invalidateViews()
    }
    tabAdapter.notifyDataSetChanged()
}

internal fun MainActivity.refreshMultiSelectUI() = syncMultiBar()
internal fun MainActivity.refreshMultiSelectUI(tab: TabState) = syncMultiBar(tab)

internal fun MainActivity.confirmBatchDelete() {
        val tab = activeTab
        // 跨 tab：聚合所有 tab 的选中文件
        val sel = allSelectedFiles().values.flatten().toList(); if (sel.isEmpty()) return
        val recycleEnabled = com.usefulunpacker.fileops.RecycleBin.isEnabled(prefs)
        AlertDialog.Builder(this).setTitle(getString(R.string.title_batch_delete)).setMessage(getString(if (recycleEnabled) R.string.confirm_recycle_batch_msg else R.string.confirm_delete_batch_msg, sel.size))
            .setPositiveButton(getString(R.string.action_delete)) { _, _ ->
                deleteWithProgress(this, sel, prefs) { del, fail ->
                    if (fail > 0) toast(getString(R.string.msg_delete_result, del, fail)) else toast(getString(if (recycleEnabled) R.string.msg_moved_to_recycle else R.string.msg_deleted))
                    pruneBookmarksForDeleted(sel)
                    exitAllMultiSelect(); navTab(tab, tab.currentDir)
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null).show()
    }

internal fun MainActivity.startBatchMove() {
        val tab = activeTab
        // 跨 tab：聚合所有 tab 的选中文件
        val sel = allSelectedFiles().values.flatten().toList(); if (sel.isEmpty()) return
        // Copy all selected files to a temp list for multi-move; use first file as UI indicator
        tab.multiFiles = sel
        tab.fileToMove = sel[0]; exitAllMultiSelect()
        updatePasteButton()
                toast(getString(R.string.msg_selected_nav_multi, sel.size))
    }

internal fun MainActivity.startBatchCopy() {
        val tab = activeTab
        // 跨 tab：聚合所有 tab 的选中文件
        val sel = allSelectedFiles().values.flatten().toList(); if (sel.isEmpty()) return
        // Snapshot the destination directory: `currentDir` is UI-thread-owned and
        // the user may navigate away mid-copy — copying into a changed directory
        // would scatter files unpredictably.
        val targetDir = tab.currentDir
        thread {
            var copied = 0
            var failed = 0
            for (src in sel) {
                try {
                    val dest = File(targetDir, getCopyFileName(src, targetDir))
                    if (src.isDirectory) {
                        src.copyRecursively(dest, overwrite = false)
                    } else {
                        src.copyTo(dest, overwrite = false)
                    }
                    copied++
                } catch (e: Exception) {
                    android.util.Log.e("MultiSelect", "Copy failed: ${src.name}", e)
                    failed++
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (failed > 0) toast(getString(R.string.msg_copy_result, copied, failed)) else toast(getString(R.string.msg_copied))
                exitAllMultiSelect()
                navTab(tab, tab.currentDir)
            }
        }
    }

internal fun MainActivity.getCopyFileName(src: File, targetDir: File): String {
        val name = src.nameWithoutExtension
        val ext = src.extension
        var candidate = File(targetDir, src.name)
        var i = 1
        while (candidate.exists()) {
            candidate = if (ext.isNotEmpty()) File(targetDir, "$name($i).$ext") else File(targetDir, "$name($i)")
            i++
        }
        return candidate.name
    }
