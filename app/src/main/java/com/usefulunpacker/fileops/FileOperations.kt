package com.usefulunpacker

import android.app.AlertDialog
import android.app.ProgressDialog
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import com.usefulunpacker.featureThread as thread

fun showRenameDialog(activity: AppCompatActivity, f: File, currentDir: File, bookmarks: MutableList<String>, onSaved: () -> Unit) {
    val _intentScope = com.usefulunpacker.AppDiagnostics.enterIntent("file_rename", newChain = true)
    try {
    val inp = com.usefulunpacker.UuTextField(activity).apply {
        setText(f.name); selectAll()
    }
    com.usefulunpacker.UuDialogBuilder(activity)
        .setTitle(activity.getString(R.string.action_rename))
        .setView(inp)
        .setPositiveButton(activity.getString(R.string.action_confirm)) { _, _ ->
            val newName = inp.text.toString().trim()
            if (newName.isEmpty() || newName == f.name) return@setPositiveButton
            // Reject path separators / ".." so a rename can't escape the folder
            // or descend into a subdirectory (unpredictable / unsafe paths).
            if (newName.contains('/') || newName.contains('\\') || newName == ".." || newName.contains("..")) {
                com.usefulunpacker.UuNotice.failure(activity, activity.getString(R.string.err_file_error), Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            val dst = File(f.parentFile ?: return@setPositiveButton, newName)
            // renameTo can fail (cross-device, permission, race) — never fake success.
            if (!dst.exists()) {
                val ok = f.renameTo(dst)
                if (ok) UuNotice.makeText(activity, activity.getString(R.string.action_rename), Toast.LENGTH_SHORT).show()
                else UuNotice.failure(activity, activity.getString(R.string.err_file_error), Toast.LENGTH_LONG).show()
                if (ok) onSaved()
                return@setPositiveButton
            }
            com.usefulunpacker.UuDialogBuilder(activity)
                .setTitle(activity.getString(R.string.msg_target_exists))
                .setItems(arrayOf(activity.getString(R.string.action_replace), activity.getString(R.string.action_keep_both), activity.getString(R.string.action_compare))) { _, which ->
                    when (which) {
                        0 -> {
                            if (!deleteOutputChecked(dst, cleanup = false)) return@setItems
                            val ok = f.renameTo(dst)
                            if (ok) { UuNotice.makeText(activity, activity.getString(R.string.action_rename), Toast.LENGTH_SHORT).show(); onSaved() }
                            else UuNotice.failure(activity, activity.getString(R.string.err_file_error), Toast.LENGTH_LONG).show()
                        }
                        1 -> {
                            val u = uniqueFile(f.parentFile!!, newName)
                            val ok = f.renameTo(u)
                            if (ok) { UuNotice.makeText(activity, activity.getString(R.string.msg_renamed_to, u.name), Toast.LENGTH_SHORT).show(); onSaved() }
                            else UuNotice.failure(activity, activity.getString(R.string.err_file_error), Toast.LENGTH_LONG).show()
                        }
                        2 -> { compareFiles(activity, f, dst, bookmarks, currentDir, onSaved) }
                    }
                }.setNegativeButton(R.string.action_cancel, null).show().also { it.keepTabsTappable() }
        }
        .setNegativeButton(R.string.action_cancel, null).show().also { it.keepTabsTappable() }
    } catch (intentCancelled: java.util.concurrent.CancellationException) {
        _intentScope.trace.cancelled(); throw intentCancelled
    } catch (intentFailure: Exception) {
        _intentScope.trace.fail(com.usefulunpacker.FailureCode.UNEXPECTED, intentFailure.message ?: intentFailure.javaClass.simpleName, intentFailure)
        throw intentFailure
    } finally { com.usefulunpacker.AppDiagnostics.leaveIntent(_intentScope) }
}

fun compareFiles(activity: AppCompatActivity, a: File, b: File, bookmarks: MutableList<String>, currentDir: File, onSaved: () -> Unit) {
    val _intentScope = com.usefulunpacker.AppDiagnostics.enterIntent("file_compare", newChain = true)
    try {
    val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    val info = "${a.name}\n${activity.getString(R.string.label_size, fmt(fileSize(a)))}\n${activity.getString(R.string.label_time, df.format(Date(a.lastModified())))}\n\n" +
        "${b.name}\n${activity.getString(R.string.label_size, fmt(fileSize(b)))}\n${activity.getString(R.string.label_time, df.format(Date(b.lastModified())))}\n\n"
    val extA = a.name.lowercase().substringAfterLast('.')
    val extB = b.name.lowercase().substringAfterLast('.')
    val previewable = setOf("jpg","jpeg","png","txt","json","ini","ks","lua","py","js","html","css","xml","cfg","log","md")
    val canPreview = extA in previewable && extB in previewable
    com.usefulunpacker.UuDialogBuilder(activity)
        .setTitle(activity.getString(R.string.title_compare))
        .setMessage(info + if (canPreview) activity.getString(R.string.msg_both_files_previewable) else activity.getString(R.string.msg_same_size, (fileSize(a) == fileSize(b)).toString()))
        .setPositiveButton(if (canPreview) activity.getString(R.string.preview_both) else activity.getString(R.string.action_confirm)) { _, _ ->
            if (canPreview) { previewLocalFile(activity, a); previewLocalFile(activity, b) }
        }
        .setNegativeButton(R.string.action_cancel, null).show().also { it.keepTabsTappable() }
    } catch (intentCancelled: java.util.concurrent.CancellationException) {
        _intentScope.trace.cancelled(); throw intentCancelled
    } catch (intentFailure: Exception) {
        _intentScope.trace.fail(com.usefulunpacker.FailureCode.UNEXPECTED, intentFailure.message ?: intentFailure.javaClass.simpleName, intentFailure)
        throw intentFailure
    } finally { com.usefulunpacker.AppDiagnostics.leaveIntent(_intentScope) }
}

fun calcDirSize(activity: AppCompatActivity, dir: File) {
    val _intentScope = com.usefulunpacker.AppDiagnostics.enterIntent("directory_size", newChain = true)
    try {
    val pd = com.usefulunpacker.UuProgressDialog(activity).apply {
        setTitle(activity.getString(R.string.msg_calculating))
        setMessage(dir.name)
        setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        max = 100
        setCancelable(false)
        show()
    }
    pd.keepTabsTappable(blockBehind = false)
    thread {
        var total = 0L
        var processed = 0
        dir.walkTopDown().forEach { f ->
            if (f.isFile) total += com.usefulunpacker.observedAttempt("FileOperations") { f.length() }.getOrDefault(0L)
            processed++
            if (processed % 50 == 0) {
                activity.runFeatureOnUiThread { if (activity.isFinishing || activity.isDestroyed) return@runFeatureOnUiThread; pd.setMessage(activity.getString(R.string.msg_calc_counting, processed)) }
            }
        }
        activity.runFeatureOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runFeatureOnUiThread
            pd.dismiss()
            com.usefulunpacker.UuDialogBuilder(activity)
                .setTitle(dir.name)
                .setMessage(activity.getString(R.string.msg_calc_result, fmt(total), processed))
                .setPositiveButton(activity.getString(R.string.action_confirm), null)
                .show().also { it.keepTabsTappable() }
        }
    }
    } catch (intentCancelled: java.util.concurrent.CancellationException) {
        _intentScope.trace.cancelled(); throw intentCancelled
    } catch (intentFailure: Exception) {
        _intentScope.trace.fail(com.usefulunpacker.FailureCode.UNEXPECTED, intentFailure.message ?: intentFailure.javaClass.simpleName, intentFailure)
        throw intentFailure
    } finally { com.usefulunpacker.AppDiagnostics.leaveIntent(_intentScope) }
}
