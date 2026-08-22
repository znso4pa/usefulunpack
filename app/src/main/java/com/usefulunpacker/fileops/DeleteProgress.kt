package com.usefulunpacker

import android.app.ProgressDialog
import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.usefulunpacker.fileops.RecycleBin
import java.io.File
import kotlin.concurrent.thread

fun deleteWithProgress(
    activity: AppCompatActivity,
    targets: List<File>,
    prefs: SharedPreferences,
    onDone: (deleted: Int, failed: Int) -> Unit
) {
    val recycleEnabled = RecycleBin.isEnabled(prefs)
    val singleFile = targets.size == 1 && targets[0].isFile
    val pd = ProgressDialog(activity).apply {
        setTitle(activity.getString(if (recycleEnabled) R.string.msg_move_to_recycle else R.string.msg_delete_progress))
        setMessage(activity.getString(R.string.msg_delete_counting))
        setProgressStyle(if (singleFile) ProgressDialog.STYLE_SPINNER else ProgressDialog.STYLE_HORIZONTAL)
        if (!singleFile) max = 100
        setCancelable(false)
        show()
    }
    thread {
        if (!OperationLock.acquire()) {
            activity.runOnUiThread {
                if (!activity.isFinishing) pd.dismiss()
                Toast.makeText(activity, activity.getString(R.string.msg_op_in_progress), Toast.LENGTH_SHORT).show()
            }
            return@thread
        }
        try {
            if (singleFile) {
                val f = targets[0]
                activity.runOnUiThread { pd.setMessage(activity.getString(R.string.msg_deleting_file, f.name)) }
                val ok = if (recycleEnabled) {
                    runCatching { RecycleBin.moveToRecycleBin(activity, f) }.getOrDefault(false)
                } else {
                    runCatching { f.delete() }.getOrDefault(false)
                }
                activity.runOnUiThread {
                    if (!activity.isFinishing) pd.dismiss()
                    onDone(if (ok) 1 else 0, if (ok) 0 else 1)
                }
            } else {
                val total = targets.size
                var deleted = 0
                var failed = 0
                var processed = 0
                for (t in targets) {
                    if (!t.exists()) { failed++; processed++; continue }
                    if (recycleEnabled) {
                        val ok = runCatching { RecycleBin.moveToRecycleBin(activity, t) }.getOrDefault(false)
                        if (ok) deleted++ else failed++
                        processed++
                        val pct = if (total > 0) (processed * 100 / total).coerceAtMost(100) else 100
                        activity.runOnUiThread {
                            pd.progress = pct
                            pd.setMessage(activity.getString(R.string.msg_deleting_file, t.name.takeLast(40)))
                        }
                    } else {
                        runCatching {
                            t.walkBottomUp().forEach { f ->
                                if (f.delete()) deleted++ else failed++
                                processed++
                            }
                        }
                        val pct = if (total > 0) (processed * 100 / total).coerceAtMost(100) else 100
                        activity.runOnUiThread {
                            pd.progress = pct
                            pd.setMessage(activity.getString(R.string.msg_deleting_file, t.name.takeLast(40)))
                        }
                    }
                }
                activity.runOnUiThread {
                    if (!activity.isFinishing) pd.dismiss()
                    onDone(deleted, failed)
                }
            }
        } finally {
            OperationLock.release()
        }
    }
}
