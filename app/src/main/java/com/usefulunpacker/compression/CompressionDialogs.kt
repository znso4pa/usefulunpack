package com.usefulunpacker

import android.content.SharedPreferences
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread
import java.io.File

fun showCompressFormatPicker(activity: AppCompatActivity, dir: File, prefs: SharedPreferences, currentDir: File, onComplete: () -> Unit) {
    showFormatPicker(activity, "${activity.getString(R.string.msg_compress_title)} ${dir.name}",
        onPick = { fmt -> showCompressOptionsDialog(activity, dir, currentDir, prefs, fmt, onComplete) },
        groups = COMPRESS_GROUPS,
        labels = COMPRESS_LABELS
    )
}

/** Inline compress options (level + split) shown after the format picker —
 *  per-operation overrides that default to the settings values, so the user
 *  doesn't have to visit Settings first. */
private fun showCompressOptionsDialog(
    activity: AppCompatActivity, dir: File, currentDir: File, prefs: SharedPreferences,
    fmt: String, onComplete: () -> Unit
) {
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
    val isZip = fmt == "zip"
    val isSz = fmt == "7z"
    val levelVals = if (isZip) intArrayOf(0, 3, 5, 7, 9) else if (isSz) intArrayOf(0, 3, 6, 9, 12) else intArrayOf(0, 3, 5, 7, 9)
    val levelLabels = arrayOf(
        activity.getString(R.string.level_store), activity.getString(R.string.level_low),
        activity.getString(R.string.level_medium), activity.getString(R.string.level_high),
        activity.getString(R.string.level_extreme))
    val defaultLevel = if (isZip) prefs.getInt("zip_level", 5) else if (isSz) prefs.getInt("sz_level", 6) else prefs.getInt("generic_level", 6)
    var level = levelVals.indexOf(defaultLevel).let { if (it < 0) 2 else it }
    val splitVals = longArrayOf(0L, 1024L * 1024, 100L * 1024 * 1024, 1024L * 1024 * 1024)
    val splitLabels = arrayOf(
        activity.getString(R.string.split_none)) + activity.resources.getStringArray(R.array.split_size_labels)
    val defaultSplit = prefs.getLong("compress_split_size", 0L)
    // Track the CHOSEN value (bytes), starting at the settings value. A custom
    // split (not in the presets) must be preserved unless the user picks a
    // preset — indexOf-based selection would silently downgrade it to 不分卷.
    var chosenSplit: Long = defaultSplit
    fun splitLabel(v: Long): String = when (v) {
        0L -> activity.getString(R.string.split_none)
        1024L * 1024 -> activity.getString(R.string.unit_mb).let { "1$it" }
        100L * 1024 * 1024 -> activity.getString(R.string.unit_mb).let { "100$it" }
        1024L * 1024 * 1024 -> activity.getString(R.string.unit_gb).let { "1$it" }
        else -> fmt(v) // custom value from settings
    }
    val canSplit = isZip || isSz

    fun row(text: String, onClick: () -> Unit) = android.widget.TextView(activity).apply {
        this.text = text
        setTextColor(C["accent"]!!)
        textSize = 15f
        setPadding(24, 16, 24, 16)
        setOnClickListener { onClick() }
    }
    val body = android.widget.LinearLayout(activity).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setBackgroundColor(C["surface"]!!)
        addView(row("${activity.getString(R.string.title_level)}: ${levelLabels[level]}") {
            android.app.AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.title_level))
                .setSingleChoiceItems(levelLabels, level) { d, w -> level = w; d.dismiss() }
                .setNegativeButton(activity.getString(R.string.action_cancel), null)
                .show()
        })
        if (canSplit) {
            addView(android.view.View(activity).apply {
                setBackgroundColor(C["divider_subtle"]!!)
                layoutParams = android.widget.LinearLayout.LayoutParams(MATCH, 1).apply { setMargins(24, 0, 24, 0) }
            })
            addView(row("${activity.getString(R.string.split_title)}: ${splitLabel(chosenSplit)}") {
                android.app.AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.split_title))
                    .setSingleChoiceItems(splitLabels, splitVals.indexOf(chosenSplit).coerceAtLeast(0)) { d, w ->
                        chosenSplit = splitVals[w]; d.dismiss()
                    }
                    .setNegativeButton(activity.getString(R.string.action_cancel), null)
                    .show()
            })
        }
    }
    android.app.AlertDialog.Builder(activity)
        .setTitle(activity.getString(R.string.title_compress_options))
        .setView(body)
        .setPositiveButton(activity.getString(R.string.action_confirm)) { _, _ ->
            runCompress(activity, dir, currentDir, prefs, fmt, levelVals[level], chosenSplit, onComplete)
        }
        .setNegativeButton(activity.getString(R.string.action_cancel), null)
        .show()
}

private fun runCompress(
    activity: AppCompatActivity, dir: File, currentDir: File, prefs: SharedPreferences,
    fmt: String, level: Int, splitSize: Long, onComplete: () -> Unit
) {
    val ext = COMPRESS_EXT[fmt] ?: fmt
    // KSD 输出替换源扩展名（save.txt → save.ksd），其余格式保留源扩展名（save.txt → save.txt.gz）
    val outName = if (fmt == "ksd") "${dir.nameWithoutExtension}.$ext" else "${dir.name}.$ext"
    val outFile = uniqueFile(dir.parentFile ?: currentDir, outName)
    val pwEnabled = prefs.getBoolean("compress_password_enabled", false)
    val password = if (pwEnabled) prefs.getString("compress_password", "") ?: "" else ""
    val splitEnabled = splitSize > 0 && fmt in setOf("zip", "7z")
    // Acquire the lock BEFORE showing the progress dialog — otherwise a busy
    // lock leaves the dialog spinning forever with the work silently dropped.
    if (!tryStartOperation(activity)) return
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
        try {
            var ok = compressDispatch(dir, outFile, fmt, level, password, prefs, splitSize)
            if (cancelled || !ok) {
                // split_volumes already removed the original; remove the
                // `.001/.002/...` parts too, not just outFile.
                outFile.parentFile?.listFiles()?.filter { f ->
                    f.name.startsWith(outFile.name + ".") &&
                    f.name.substringAfterLast('.').isNotEmpty() &&
                    f.name.substringAfterLast('.').all { it.isDigit() }
                }?.forEach { it.delete() }
                var deleted = false; for (i in 0..10) { deleted = outFile.delete(); if (deleted) break else Thread.sleep(200) }
            }
            activity.runOnUiThread {
                prog.dismiss()
                if (cancelled) { Toast.makeText(activity, activity.getString(R.string.msg_cancelled), Toast.LENGTH_SHORT).show() }
                else if (ok) {
                    val shown = if (splitEnabled) "${outFile.name}.001" else outFile.name
                    Toast.makeText(activity, "${activity.getString(R.string.msg_extract_complete)} $shown", Toast.LENGTH_SHORT).show()
                    onComplete()
                }
                else Toast.makeText(activity, activity.getString(R.string.title_compress_failed), Toast.LENGTH_SHORT).show()
            }
        } finally {
            OperationLock.release()
        }
    }
}

/** 通用压缩派发：任何来源（单文件/目录/临时合并目录）→ 指定格式，供单文件、批量合并、批量分别共用。 */
fun compressDispatch(src: File, outFile: File, fmt: String, level: Int, password: String, prefs: SharedPreferences, splitOverride: Long? = null): Boolean {
    // 分卷大小（字节），仅 zip/7z 支持；0 = 不分卷。inline 压缩选项可传显式值。
    val split = splitOverride ?: prefs.getLong("compress_split_size", 0L)
    val splitStr = if (split > 0 && fmt in setOf("zip", "7z")) split.toString() else "0"
    return try {
        when (fmt) {
            "xp3" -> Xp3Core.xp3CreateArchive("", src.path, outFile.path, level.toString()) != null
            "pfs" -> PfsCore.pfsCreateArchive("", src.path, outFile.path) != null
            "ksd" -> KsdCore.ksdCompress("", src.path, outFile.path, level.toString()) != null
            "zip" -> ZipCore.zipCompress("", src.path, outFile.path, level.toString(), password, splitStr)
            "7z" -> SevenZCore.szCompress("", src.path, outFile.path, level.toString(), password, splitStr)
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
