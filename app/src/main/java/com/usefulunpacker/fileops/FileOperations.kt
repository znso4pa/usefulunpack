package com.usefulunpacker

import android.app.AlertDialog
import android.app.ProgressDialog
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

fun showRenameDialog(activity: AppCompatActivity, f: File, currentDir: File, bookmarks: MutableList<String>, onSaved: () -> Unit) {
    val inp = EditText(activity).apply {
        setText(f.name); selectAll()
        setTextColor(C["primary"]!!); setHintTextColor(C["hint"]!!)
        setBackgroundColor(C["surface"]!!); setPadding(12, 8, 12, 8)
    }
    AlertDialog.Builder(activity)
        .setTitle(activity.getString(R.string.action_rename))
        .setView(inp)
        .setPositiveButton(activity.getString(R.string.action_confirm)) { _, _ ->
            val newName = inp.text.toString().trim()
            if (newName.isEmpty() || newName == f.name) return@setPositiveButton
            val dst = File(f.parentFile ?: return@setPositiveButton, newName)
            if (!dst.exists()) { f.renameTo(dst); Toast.makeText(activity, activity.getString(R.string.action_rename), Toast.LENGTH_SHORT).show(); return@setPositiveButton }
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.msg_target_exists))
                .setItems(arrayOf(activity.getString(R.string.action_replace), activity.getString(R.string.action_keep_both), activity.getString(R.string.action_compare))) { _, which ->
                    when (which) {
                        0 -> { dst.delete(); f.renameTo(dst); Toast.makeText(activity, activity.getString(R.string.action_replace), Toast.LENGTH_SHORT).show() }
                        1 -> { val u = uniqueFile(f.parentFile!!, newName); f.renameTo(u); Toast.makeText(activity, activity.getString(R.string.msg_renamed_to, u.name), Toast.LENGTH_SHORT).show() }
                        2 -> { compareFiles(activity, f, dst, bookmarks, currentDir, onSaved) }
                    }
                }.setNegativeButton(activity.getString(R.string.action_cancel), null).show()
        }
        .setNegativeButton(activity.getString(R.string.action_cancel), null).show()
}

fun compareFiles(activity: AppCompatActivity, a: File, b: File, bookmarks: MutableList<String>, currentDir: File, onSaved: () -> Unit) {
    val info = "${a.name}\n大小: ${fmt(fileSize(a))}\n时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(a.lastModified()))}\n\n${b.name}\n大小: ${fmt(fileSize(b))}\n时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(b.lastModified()))}\n\n"
    val extA = a.name.lowercase().substringAfterLast('.')
    val extB = b.name.lowercase().substringAfterLast('.')
    val previewable = setOf("jpg","jpeg","png","txt","json","ini","ks","lua","py","js","html","css","xml","cfg","log","md")
    val canPreview = extA in previewable && extB in previewable
    AlertDialog.Builder(activity)
        .setTitle(activity.getString(R.string.title_compare))
        .setMessage(info + if (canPreview) activity.getString(R.string.msg_both_files_previewable) else activity.getString(R.string.msg_same_size, (fileSize(a) == fileSize(b)).toString()))
        .setPositiveButton(if (canPreview) activity.getString(R.string.preview_both) else "确定") { _, _ ->
            if (canPreview) { previewLocalFile(activity, a); previewLocalFile(activity, b) }
        }
        .setNegativeButton(activity.getString(R.string.action_cancel), null).show()
}

fun calcDirSize(activity: AppCompatActivity, dir: File) {
    val pd = ProgressDialog(activity).apply {
        setTitle(activity.getString(R.string.msg_calculating))
        setMessage(dir.name)
        setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        max = 100
        setCancelable(false)
        show()
    }
    val fileCount = dir.listFiles()?.size ?: 0
    thread {
        var total = 0L
        var processed = 0
        dir.walkTopDown().forEach { f ->
            if (f.isFile) total += runCatching { f.length() }.getOrDefault(0L)
            processed++
            if (processed % 50 == 0) activity.runOnUiThread { pd.progress = (processed * 100 / fileCount).coerceAtMost(100) }
        }
        activity.runOnUiThread {
            pd.dismiss()
            AlertDialog.Builder(activity)
                .setTitle(dir.name)
                .setMessage(activity.getString(R.string.msg_calc_result, fmt(total), processed))
                .setPositiveButton(activity.getString(R.string.action_confirm), null)
                .show()
        }
    }
}
