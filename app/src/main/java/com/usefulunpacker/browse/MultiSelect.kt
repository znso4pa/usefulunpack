package com.usefulunpacker

import android.app.AlertDialog
import android.view.View
import android.widget.*
import java.io.File

internal fun MainActivity.enterMultiSelect(f: File) {
        multiSelectMode = true; multiSelected.add(f); refreshMultiSelectUI()
        val compressMode = prefs.getInt("work_mode", 0) == 1
        syncMultiBar()
    }

internal fun MainActivity.toggleMultiSelect(f: File) { if (multiSelected.contains(f)) multiSelected.remove(f) else multiSelected.add(f); refreshMultiSelectUI() }

internal fun MainActivity.exitMultiSelect() { multiSelectMode = false; multiSelected.clear(); syncMultiBar() }

internal fun MainActivity.syncMultiBar() {
        val bar = (findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.root)).let { r ->
            for (i in 0 until r.childCount) { val c = r.getChildAt(i); if (c is LinearLayout && c.childCount >= 6) return@let c as LinearLayout }
            return
        }
        val tv = bar.getChildAt(0) as? TextView ?: return
        val isCompress = prefs.getInt("work_mode", 0) == 1
        tv.text = getString(R.string.multi_selected_count, multiSelected.size)
        bar.visibility = if (multiSelectMode) View.VISIBLE else View.GONE
        if (multiSelectMode) {
            bar.post { listFiles.setPadding(listFiles.paddingLeft, listFiles.paddingTop, listFiles.paddingRight, bar.height) }
        } else {
            listFiles.setPadding(listFiles.paddingLeft, listFiles.paddingTop, listFiles.paddingRight, 0)
        }
        if (bottomBar != null) bottomBar.visibility = if (multiSelectMode) View.GONE else bottomBar.visibility
        // Sync adapter selection state and force full redraw
        (listFiles.adapter as? FileAdapter)?.multiSelected_ = if (multiSelectMode) multiSelected else emptySet()
        listFiles.invalidateViews()
        // Show/hide extract/compress based on mode (use startsWith for emoji safety)
        for (i in 0 until bar.childCount) {
            val btn = bar.getChildAt(i) as? Button ?: continue
            val t = btn.text.toString()
            if (t.startsWith("📂")) btn.visibility = if (isCompress) View.GONE else View.VISIBLE
            if (t.startsWith("📦")) btn.visibility = if (isCompress) View.VISIBLE else View.GONE
        }
    }

internal fun MainActivity.refreshMultiSelectUI() { syncMultiBar() }

internal fun MainActivity.confirmBatchDelete() {
        val sel = multiSelected.toList(); if (sel.isEmpty()) return
                AlertDialog.Builder(this).setTitle(getString(R.string.title_batch_delete)).setMessage(getString(R.string.confirm_delete_batch_msg, sel.size))
            .setPositiveButton(getString(R.string.action_delete)) { _, _ ->
                deleteWithProgress(this, sel) { del, fail ->
                    if (fail > 0) toast(getString(R.string.msg_delete_result, del, fail)) else toast(getString(R.string.msg_deleted))
                    pruneBookmarksForDeleted(sel)
                    exitMultiSelect(); nav(currentDir)
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null).show()
    }

internal fun MainActivity.startBatchMove() {
        val sel = multiSelected.toList(); if (sel.isEmpty()) return
        // Copy all selected files to a temp list for multi-move; use first file as UI indicator
        MultiFiles = sel
        fileToMove = sel[0]; multiSelected.clear()
        updatePasteButton(); exitMultiSelect()
                toast(getString(R.string.msg_selected_nav_multi, sel.size))
    }
