package com.usefulunpacker

import android.content.SharedPreferences
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread
import java.io.File

fun showCompressFormatPicker(activity: AppCompatActivity, dir: File, prefs: SharedPreferences, currentDir: File, onComplete: () -> Unit) {
    showFormatPicker(activity, "${activity.getString(R.string.msg_compress_title)} ${dir.name}",
        onPick = { fmt -> doCompress(activity, dir, currentDir, prefs, fmt, onComplete) },
        groups = COMPRESS_GROUPS,
        labels = COMPRESS_LABELS
    )
}

private fun doCompress(activity: AppCompatActivity, dir: File, currentDir: File, prefs: SharedPreferences, fmt: String, onComplete: () -> Unit) {
    // 单文件压缩格式不能压缩文件夹
    if (dir.isDirectory && fmt in SINGLE_FILE_COMPRESS) {
        Toast.makeText(activity, activity.getString(R.string.msg_single_file_compress), Toast.LENGTH_SHORT).show()
        return
    }
    // KSD 打包只接受 .txt 文本文件
    if (fmt == "ksd" && !dir.name.lowercase().endsWith(".txt")) {
        Toast.makeText(activity, activity.getString(R.string.msg_ksd_need_txt), Toast.LENGTH_SHORT).show()
        return
    }
    val ext = COMPRESS_EXT[fmt] ?: fmt
    // KSD 输出替换源扩展名（save.txt → save.ksd），其余格式保留源扩展名（save.txt → save.txt.gz）
    val outName = if (fmt == "ksd") "${dir.nameWithoutExtension}.$ext" else "${dir.name}.$ext"
    val outFile = uniqueFile(dir.parentFile ?: currentDir, outName)
    val level = if (fmt == "zip") prefs.getInt("zip_level", 5) else if (fmt == "7z") prefs.getInt("sz_level", 6) else prefs.getInt("generic_level", 6)
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
        if (!tryStartOperation(activity)) return@thread
        try {
            var ok = compressDispatch(dir, outFile, fmt, level, password, prefs)
            if (cancelled || !ok) {
                var deleted = false; for (i in 0..10) { deleted = outFile.delete(); if (deleted) break else Thread.sleep(200) }
            }
            activity.runOnUiThread {
                prog.dismiss()
                if (cancelled) { Toast.makeText(activity, activity.getString(R.string.msg_cancelled), Toast.LENGTH_SHORT).show() }
                else if (ok) { Toast.makeText(activity, "${activity.getString(R.string.msg_extract_complete)} ${outFile.name}", Toast.LENGTH_SHORT).show(); onComplete() }
                else Toast.makeText(activity, activity.getString(R.string.title_compress_failed), Toast.LENGTH_SHORT).show()
            }
        } finally {
            OperationLock.release()
        }
    }
}

/** 通用压缩派发：任何来源（单文件/目录/临时合并目录）→ 指定格式，供单文件、批量合并、批量分别共用。 */
fun compressDispatch(src: File, outFile: File, fmt: String, level: Int, password: String, prefs: SharedPreferences): Boolean {
    return try {
        when (fmt) {
            "xp3" -> Xp3Core.xp3CreateArchive("", src.path, outFile.path, level.toString()) != null
            "pfs" -> PfsCore.pfsCreateArchive("", src.path, outFile.path) != null
            "ksd" -> KsdCore.ksdCompress("", src.path, outFile.path, level.toString()) != null
            "zip" -> { ZipCore.zipSetEncoding(prefs.getString("zip_encoding", "UTF-8") ?: "UTF-8"); ZipCore.zipCompress("", src.path, outFile.path, level.toString(), password) }
            "7z" -> SevenZCore.szCompress("", src.path, outFile.path, level.toString(), password)
            "tar", "tgz", "tbz2", "txz", "tzst" -> TarCore.tarCompress("", src.path, outFile.path, fmt, level.toString())
            "gz" -> GzipCore.gzCompress("", src.path, outFile.path, level.toString())
            "bz2" -> Bzip2Core.bz2Compress("", src.path, outFile.path, level.toString())
            "xz" -> XzCore.xzCompress("", src.path, outFile.path, level.toString())
            "zst" -> ZstdCore.zstCompress("", src.path, outFile.path, level.toString())
            "lzma" -> LzmaCore.lzmaCompress("", src.path, outFile.path, level.toString())
            "lz4" -> Lz4Core.lz4Compress("", src.path, outFile.path, level.toString())
            else -> false
        }
    } catch (_: Exception) { false }
}
