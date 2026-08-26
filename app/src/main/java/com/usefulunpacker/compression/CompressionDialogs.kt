package com.usefulunpacker

import android.content.SharedPreferences
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread
import java.io.File

fun showCompressFormatPicker(
    activity: AppCompatActivity, dir: File, prefs: SharedPreferences, currentDir: File,
    ownerTab: TabState? = (activity as? MainActivity)?.activeTab,
    onComplete: () -> Unit
) {
    showFormatPicker(activity, "${activity.getString(R.string.msg_compress_title)} ${dir.name}",
        onPick = { fmt ->
            // 单文件压缩格式不能压缩文件夹
            if (dir.isDirectory && fmt in SINGLE_FILE_COMPRESS) {
                Toast.makeText(activity, activity.getString(R.string.msg_single_file_compress), Toast.LENGTH_SHORT).show()
                return@showFormatPicker
            }
            // KSD 打包只接受 .txt 文本文件
            if (fmt == "ksd" && !dir.name.lowercase().endsWith(".txt")) {
                Toast.makeText(activity, activity.getString(R.string.msg_ksd_need_txt), Toast.LENGTH_SHORT).show()
                return@showFormatPicker
            }
            showCompressOptionsDialog(activity, prefs, fmt) { level, split ->
                runCompress(activity, dir, currentDir, prefs, fmt, level, split, onComplete, ownerTab)
                true
            }
        },
        groups = COMPRESS_GROUPS,
        labels = COMPRESS_LABELS
    )
}

/**
 * Inline compress options (level + split) shown after the format picker —
 * per-operation overrides that default to the settings values, so the user
 * doesn't have to visit Settings first. Shared by single-file and batch flows.
 * The resolved (level, splitBytes) is handed to [onResolved]; returning false
 * there cancels (e.g. batch format guards) without running the compress.
 */
fun showCompressOptionsDialog(
    activity: AppCompatActivity, prefs: SharedPreferences,
    fmt: String, onResolved: (level: Int, splitBytes: Long) -> Boolean
) {
    val isZip = fmt == "zip"
    val isSz = fmt == "7z"
    val levelVals = when {
        isZip -> intArrayOf(0, 3, 5, 7, 9)
        isSz -> intArrayOf(0, 3, 6, 9, 12)
        else -> intArrayOf(0, 3, 5, 7, 9)
    }
    val levelLabels = arrayOf(
        activity.getString(R.string.level_store), activity.getString(R.string.level_low),
        activity.getString(R.string.level_medium), activity.getString(R.string.level_high),
        activity.getString(R.string.level_extreme))
    val defaultLevel = when {
        isZip -> prefs.getInt("zip_level", 5)
        isSz -> prefs.getInt("sz_level", 6)
        else -> prefs.getInt("generic_level", 6)
    }
    var level = levelVals.indexOf(defaultLevel).let { if (it < 0) 2 else it }
    val splitVals = longArrayOf(0L, 1024L * 1024, 100L * 1024 * 1024, 1024L * 1024 * 1024)
    // index 4 = custom size (matches the Settings dialog's 5-entry list).
    val splitLabels = arrayOf(
        activity.getString(R.string.split_none)) + activity.resources.getStringArray(R.array.split_size_labels) + arrayOf(activity.getString(R.string.split_custom))
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
    // Byte-split volumes are named `.001/.002/…` (7-Zip `-v` semantics) — never
    // PKWARE `.z01` true disks, which this writer does not produce. Show the
    // suffix on the row so a user isn't left guessing which split scheme they got.
    fun splitRowLabel(v: Long): String =
        if (v <= 0) activity.getString(R.string.split_none)
        else "${activity.getString(R.string.split_title)}: ${splitLabel(v)} (.001/.002)"

    // Row views, refreshed when the user changes a value (the pick handlers
    // update the var AND the row label so the dialog reflects the choice).
    var levelRow: android.widget.TextView? = null
    var splitRow: android.widget.TextView? = null

    // Custom split size input (mirrors Settings): MB/GB toggle, 1..2048
    // validation. Writes into [chosenSplit] and refreshes the split row.
    fun showCustomSplitDialog() {
        var unitIdx = if (chosenSplit > 0 && chosenSplit % (1024L * 1024 * 1024) == 0L) 1 else 0
        val unitLabels = arrayOf(activity.getString(R.string.unit_mb), activity.getString(R.string.unit_gb))
        val inp = android.widget.EditText(activity).apply {
            setTextColor(C["primary"]!!); setHintTextColor(C["hint"]!!)
            setBackgroundColor(C["surface_dark"]!!); setPadding(12, 8, 12, 8); textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(if (chosenSplit > 0) (chosenSplit / (if (unitIdx == 1) 1024L * 1024 * 1024 else 1024L * 1024)).toString() else "")
            hint = "64"
        }
        val btnUnit = android.widget.TextView(activity).apply {
            text = unitLabels[unitIdx]; setTextColor(C["accent"]!!); textSize = 15f
            setPadding(12, 8, 4, 8)
            setOnClickListener { unitIdx = (unitIdx + 1) % 2; text = unitLabels[unitIdx] }
        }
        val unitRow = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; setPadding(28, 8, 28, 0)
            addView(inp, android.widget.LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(btnUnit)
        }
        android.app.AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.split_custom))
            .setMessage(activity.getString(R.string.split_min_note))
            .setView(unitRow)
            .setPositiveButton(activity.getString(R.string.action_confirm)) { _, _ ->
                val n = inp.text.toString().trim().toLongOrNull()
                val factor = if (unitIdx == 1) 1024L * 1024 * 1024 else 1024L * 1024
                if (n != null && n >= 1 && n <= 2048) {
                    chosenSplit = n * factor
                    splitRow?.text = splitRowLabel(chosenSplit)
                } else Toast.makeText(activity, activity.getString(R.string.split_invalid), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(activity.getString(R.string.action_cancel), null)
            .show()
    }

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
        levelRow = row("${activity.getString(R.string.title_level)}: ${levelLabels[level]}") {
            android.app.AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.title_level))
                .setSingleChoiceItems(levelLabels, level) { d, w ->
                    level = w
                    levelRow?.text = "${activity.getString(R.string.title_level)}: ${levelLabels[level]}"
                    d.dismiss()
                }
                .setNegativeButton(activity.getString(R.string.action_cancel), null)
                .show()
        }
        addView(levelRow)
        if (canSplit) {
            addView(android.view.View(activity).apply {
                setBackgroundColor(C["divider_subtle"]!!)
                layoutParams = android.widget.LinearLayout.LayoutParams(MATCH, 1).apply { setMargins(24, 0, 24, 0) }
            })
            splitRow = row(splitRowLabel(chosenSplit)) {
                // A custom (settings-set) split must highlight the Custom entry,
                // not be silently shown as 不分卷.
                val checked = when (chosenSplit) {
                    0L -> 0
                    1024L * 1024 -> 1
                    100L * 1024 * 1024 -> 2
                    1024L * 1024 * 1024 -> 3
                    else -> 4
                }
                android.app.AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.split_title))
                    .setSingleChoiceItems(splitLabels, checked) { d, w ->
                        when (w) {
                            0 -> chosenSplit = 0L
                            4 -> showCustomSplitDialog()
                            else -> chosenSplit = splitVals[w]
                        }
                        splitRow?.text = splitRowLabel(chosenSplit)
                        d.dismiss()
                    }
                    .setNegativeButton(activity.getString(R.string.action_cancel), null)
                    .show()
            }
            addView(splitRow)
        }
    }
    android.app.AlertDialog.Builder(activity)
        .setTitle(activity.getString(R.string.title_compress_options))
        .setView(body)
        .setPositiveButton(activity.getString(R.string.action_confirm)) { _, _ ->
            if (!onResolved(levelVals[level], chosenSplit)) return@setPositiveButton
        }
        .setNegativeButton(activity.getString(R.string.action_cancel), null)
        .show()
}

private fun runCompress(
    activity: AppCompatActivity, dir: File, currentDir: File, prefs: SharedPreferences,
    fmt: String, level: Int, splitSize: Long, onComplete: () -> Unit,
    ownerTab: TabState? = null
) {
    val ext = COMPRESS_EXT[fmt] ?: fmt
    // KSD 输出替换源扩展名（save.txt → save.ksd），其余格式保留源扩展名（save.txt → save.txt.gz）
    val outName = if (fmt == "ksd") "${dir.nameWithoutExtension}.$ext" else "${dir.name}.$ext"
    val outFile = uniqueFile(dir.parentFile ?: currentDir, outName)
    val pwEnabled = prefs.getBoolean("compress_password_enabled", false)
    val password = if (pwEnabled) prefs.getString("compress_password", "") ?: "" else ""
    val splitEnabled = splitSize > 0 && fmt in setOf("zip", "7z")
    // Acquire a scheduler slot BEFORE showing the progress dialog — a queued
    // op surfaces position/ETA in that dialog instead of being refused.
    val opH = tryStartOperation(activity, fmt)
    var cancelled = false
    val accessors = compressAccessors(fmt)
    val prog = PollingProgressDialog(
        activity,
        "${activity.getString(R.string.msg_compress_title)} — $ext",
        accessors,
        { n, b, t -> compressProgressMessage(activity, n, b, t) },
        activity.getString(R.string.action_cancel),
        { cancelled = true; accessors.cancel() },
        opH,
        ownerTab
    )
    prog.start()
    thread {
        if (!opH.await()) return@thread
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
            opH.release()
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
            "nsa" -> NsaCore.nsaCreateArchive("", src.path, outFile.path, if (level > 0) "2" else "0") != null
            "iso" -> IsoCore.isoCreateArchive("", src.path, outFile.path) != null
            "ypf" -> YpfCore.ypfCreateArchive("", src.path, outFile.path, level.toString()) != null
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
            "br" -> BrotliCore.brotliCompress("", src.path, outFile.path, level.toString())
            else -> false
        }
    } catch (_: Exception) { false }
}
