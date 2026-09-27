package com.usefulunpacker

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.usefulunpacker.fileops.RecycleBin
import java.io.File
import kotlin.concurrent.thread

/**
 * Moves targets to the recycle bin (or deletes them) with a dual-bar style
 * card on the shared [OpOverlay] floating layer — the same visual language as
 * extraction/compression, replacing the old system ProgressDialog (whose
 * spinner variant told the user nothing and whose modal window froze tabs).
 *
 * Bar semantics while recycling: tracks the CURRENT target's files
 * (indeterminate during its scan/copy until a total is known); the message
 * line carries the `[i/N]` target counter. No cancel button — a partially
 * recycled batch can't be undone.
 */
fun deleteWithProgress(
    activity: AppCompatActivity,
    targets: List<File>,
    prefs: SharedPreferences,
    onDone: (deleted: Int, failed: Int) -> Unit
) {
    val recycleEnabled = RecycleBin.isEnabled(prefs)
    val title = activity.getString(if (recycleEnabled) R.string.msg_move_to_recycle else R.string.msg_delete_progress)
    // Card belongs to the window that started the deletion.
    val ownerTabId = (activity as? MainActivity)?.activeTab?.tabId
    val card = OpOverlay.addCard(activity, title, cancelLabel = null, onCancelClick = { }, ownerTabId = ownerTabId)
    fun setMessage(text: String) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            card?.msg?.text = text
        }
    }

    thread {
        if (!OperationLock.acquire()) {
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                card?.let { OpOverlay.removeCard(activity, it) }
                Toast.makeText(activity, activity.getString(R.string.msg_op_in_progress), Toast.LENGTH_SHORT).show()
            }
            return@thread
        }
        try {
            // Per-target progress: indeterminate during each target's scan,
            // then real file counts once moveToRecycleBin knows its total.
            val moveCb = RecycleBin.MoveProgress { done, total ->
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    val c = card ?: return@runOnUiThread
                    if (total > 0) {
                        c.overallBar.isIndeterminate = false
                        c.overallBar.max = total
                        c.overallBar.progress = done.coerceAtMost(total)
                    }
                }
            }
            var deleted = 0
            var failed = 0
            var processed = 0
            for ((idx, t) in targets.withIndex()) {
                setMessage("[${idx + 1}/${targets.size}] ${t.name.takeLast(40)}")
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    val c = card ?: return@runOnUiThread
                    c.overallBar.isIndeterminate = true
                    c.overallBar.progress = 0
                }
                if (!t.exists()) { failed++; processed++; continue }
                if (recycleEnabled) {
                    val ok = runCatching { RecycleBin.moveToRecycleBin(activity, t, moveCb) }.getOrDefault(false)
                    if (ok) deleted++ else failed++
                    processed++
                } else {
                    // Direct delete: count per FILE (historical onDone units).
                    runCatching {
                        t.walkBottomUp().forEach { f -> if (f.delete()) deleted++ else failed++ }
                    }
                    processed++
                }
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                card?.let { OpOverlay.removeCard(activity, it) }
                onDone(deleted, failed)
            }
        } finally {
            OperationLock.release()
        }
    }
}
