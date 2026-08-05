package com.usefulunpacker

import android.app.AlertDialog
import android.content.SharedPreferences
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread
import java.io.File

fun showCompressFormatPicker(activity: AppCompatActivity, dir: File, prefs: SharedPreferences, currentDir: File, onComplete: () -> Unit) {
    AlertDialog.Builder(activity)
        .setTitle("${activity.getString(R.string.msg_compress_title)} ${dir.name}")
        .setItems(arrayOf(activity.getString(R.string.format_zip), activity.getString(R.string.format_7z), "📁 XP3", "📁 PFS")) { _, which ->
            val fmt = arrayOf("zip", "7z", "xp3", "pfs")[which]
            val ext = if (fmt == "7z") "7z" else fmt
            val outFile = uniqueFile(dir.parentFile ?: currentDir, "${dir.name}.$ext")
            val level = if (fmt == "zip") prefs.getInt("zip_level", 5) else prefs.getInt("sz_level", 6)
            val pwEnabled = prefs.getBoolean("compress_password_enabled", false)
            val password = if (pwEnabled) prefs.getString("compress_password", "") ?: "" else ""
            var cancelled = false
            val accessors = compressAccessors(fmt)
            val prog = PollingProgressDialog(
                activity,
                "${activity.getString(R.string.msg_compress_title)} — $ext",
                accessors,
                { n, b, t -> compressProgressMessage(activity, n, b, t) },
                activity.getString(R.string.action_cancel),
                { cancelled = true; accessors.cancel() }
            )
            prog.start()
            thread {
                var ok = false
                try {
                    ok = when (fmt) {
                        "zip" -> { ZipCore.zipSetEncoding(prefs.getString("zip_encoding", "UTF-8") ?: "UTF-8"); ZipCore.zipCompress("", dir.path, outFile.path, level.toString(), password) }
                        "7z" -> SevenZCore.szCompress("", dir.path, outFile.path, level.toString(), password)
                        else -> false
                    }
                } catch (_: Exception) { }
                if (cancelled) {
                    var deleted = false; for (i in 0..10) { deleted = outFile.delete(); if (deleted) break else Thread.sleep(200) }
                }
                activity.runOnUiThread {
                    prog.dismiss()
                    if (cancelled) { Toast.makeText(activity, activity.getString(R.string.msg_cancelled), Toast.LENGTH_SHORT).show() }
                    else if (ok) { Toast.makeText(activity, "${activity.getString(R.string.msg_extract_complete)} ${outFile.name}", Toast.LENGTH_SHORT).show(); onComplete() }
                    else Toast.makeText(activity, activity.getString(R.string.title_compress_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
        .setNegativeButton(activity.getString(R.string.action_cancel), null)
        .show()
}
