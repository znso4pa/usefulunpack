package com.usefulunpacker

import android.app.ProgressDialog
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.concurrent.thread

fun deleteWithProgress(
    activity: AppCompatActivity,
    targets: List<File>,
    onDone: (deleted: Int, failed: Int) -> Unit
) {
    val singleFile = targets.size == 1 && targets[0].isFile
    val pd = ProgressDialog(activity).apply {
        setTitle(activity.getString(R.string.msg_delete_progress))
        setMessage(activity.getString(R.string.msg_delete_counting))
        setProgressStyle(if (singleFile) ProgressDialog.STYLE_SPINNER else ProgressDialog.STYLE_HORIZONTAL)
        if (!singleFile) max = 100
        setCancelable(false)
        show()
    }
    thread {
        // Plain acquire on the worker side: tryStartOperation toasts from a
        // background thread (dropped) and leaves the progress dialog spinning
        // on refusal — dismiss it and surface the busy toast on the UI thread.
        if (!OperationLock.acquire()) {
            activity.runOnUiThread {
                pd.dismiss()
                Toast.makeText(activity, activity.getString(R.string.msg_op_in_progress), Toast.LENGTH_SHORT).show()
            }
            return@thread
        }
        try {
            if (singleFile) {
                val f = targets[0]
                activity.runOnUiThread { pd.setMessage(activity.getString(R.string.msg_deleting_file, f.name)) }
                val ok = runCatching { f.delete() }.getOrDefault(false)
                activity.runOnUiThread {
                    pd.dismiss()
                    onDone(if (ok) 1 else 0, if (ok) 0 else 1)
                }
            } else {
                val total = targets.sumOf { t ->
                    runCatching { t.walkBottomUp().count() }.getOrDefault(if (t.exists()) 1 else 0)
                }
                var deleted = 0
                var failed = 0
                var processed = 0
                for (t in targets) {
                    if (!t.exists()) { failed++; continue }
                    runCatching {
                        t.walkBottomUp().forEach { f ->
                            if (f.delete()) deleted++ else failed++
                            processed++
                            if (processed % 25 == 0 || processed == total) {
                                val pct = if (total > 0) (processed * 100 / total).coerceAtMost(100) else 100
                                val name = f.name.ifEmpty { t.name }
                                activity.runOnUiThread {
                                    pd.progress = pct
                                    pd.setMessage(activity.getString(R.string.msg_deleting_file, name.takeLast(40)))
                                }
                            }
                        }
                    }
                }
                activity.runOnUiThread {
                    pd.dismiss()
                    onDone(deleted, failed)
                }
            }
        } finally {
            OperationLock.release()
        }
    }
}
