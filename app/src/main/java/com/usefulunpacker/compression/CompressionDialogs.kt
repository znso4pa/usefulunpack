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
            // MV/MZ 封包按扩展名定类型：引擎只认 .rpgmvp/.rpgmvo/.rpgmvm，
            // 装错类型（比如 jpg→rpgmvp）会产出一个游戏能加载但显示不出来的文件。
            if (mvExtMismatch(dir, fmt)) {
                Toast.makeText(activity, activity.getString(R.string.msg_mv_ext_mismatch, mvRequiredExt(fmt)), Toast.LENGTH_SHORT).show()
                return@showFormatPicker
            }
            showCompressOptionsDialog(activity, prefs, fmt) { level, split, artemisNaming, gameNaming, mvKey, xp3Enc ->
                runCompress(activity, dir, currentDir, prefs, fmt, level, split, onComplete, ownerTab, artemisNaming, gameNaming, mvKey, xp3Enc)
                true
            }
        },
        groups = COMPRESS_GROUPS,
        labels = COMPRESS_LABELS
    )
}

/**
 * Default level for a format, honouring the per-format settings. Shared so the
 * repack paths (which have no options dialog) cannot drift away from the picker.
 */
internal fun defaultCompressLevel(prefs: SharedPreferences, fmt: String): Int = when (fmt) {
    "zip" -> prefs.getInt("zip_level", 5)
    "7z" -> prefs.getInt("sz_level", 6)
    else -> prefs.getInt("generic_level", 6)
}

/**
 * Inline compress options (level + split) shown after the format picker —
 * per-operation overrides that default to the settings values, so the user
 * doesn't have to visit Settings first. Shared by single-file and batch flows.
 * The resolved (level, splitBytes, pfsArtemisNaming) is handed to [onResolved];
 * returning false there cancels (e.g. batch format guards) without running the
 * compress.
 */
fun showCompressOptionsDialog(
    activity: AppCompatActivity, prefs: SharedPreferences,
    fmt: String,
    onResolved: (level: Int, splitBytes: Long, pfsArtemisNaming: Boolean, gameNaming: Boolean, mvKey: String, xp3Enc: String) -> Boolean
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
    val defaultLevel = defaultCompressLevel(prefs, fmt)
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
    // PFS/PF6 only: Artemis layered-patch naming. On = write straight into
    // root.pfs / root.pfs.NNN (the slot the engine mounts last, i.e. the one a
    // translation patch must occupy — see resolvePfsOutName). Off = keep the
    // name the caller derived (pfs-rs's explicit `-o` behaviour).
    val isPfs = fmt == "pfs" || fmt == "pf6"
    var artemisNaming = isPfs && prefs.getBoolean("pfs_artemis_naming", true)
    // RPG Maker only loads an RGSS archive named exactly `Game.rgss3a` (or
    // .rgssad / .rgss2a) sitting next to the game .exe, so an output called
    // "my-translation.rgss3a" is silently ignored by the game. On = resolve the
    // final name to `Game.<ext>`; off = keep the caller's name.
    val isRgss = fmt == "rgssad" || fmt == "rgss2a" || fmt == "rgss3a"
    var gameNaming = isRgss && prefs.getBoolean("rgss_game_naming", true)
    // MV/MZ obfuscation key. 32 hex chars = the XOR keystream verbatim; any
    // other text = an `encryptionKey` string that RPG Maker MD5s. Empty = the
    // default key, which is what an unset `encryptionKey` produces and by far
    // the most common case.
    var mvKey = if (isMvPackKey(fmt)) (prefs.getString(PREF_MV_KEY, "") ?: "") else ""
    // XP3 only: plain (default) or cxdec. The scheme itself is not chosen here —
    // it is resolved at pack time from the game folder, and the pack is refused
    // when there is nothing to resolve it from (see resolve_writer_cipher).
    val isXp3 = fmt == "xp3"
    var xp3Enc = ""
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
    var encRow: android.widget.TextView? = null
    // Held so the positive-button handler can read the keystream the user
    // actually typed (tapping Confirm does not necessarily blur the field).
    var mvKeyField: android.widget.EditText? = null

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
            .show().also { it.keepTabsTappable() }
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
                .show().also { it.keepTabsTappable() }
        }
        addView(levelRow)
        if (isXp3) {
            addView(android.view.View(activity).apply {
                setBackgroundColor(C["divider_subtle"]!!)
                layoutParams = android.widget.LinearLayout.LayoutParams(MATCH, 1).apply { setMargins(24, 0, 24, 0) }
            })
            // The choices are (value handed to the packer, label). cxdec is a
            // bare family name because the packer discovers the concrete scheme
            // from the folder; the keyless three carry `crypt:<Name>` because
            // their key is each entry's own ADLR and there is nothing to
            // discover — which also means they are always available, sidecar or
            // not, unlike cxdec.
            val encChoices = listOf(
                "" to activity.getString(R.string.enc_none),
                "cxdec" to activity.getString(R.string.enc_cxdec),
                "crypt:HashCrypt" to activity.getString(R.string.enc_hashcrypt),
                "crypt:FateCrypt" to activity.getString(R.string.enc_fatecrypt),
                "crypt:AppliqueCrypt" to activity.getString(R.string.enc_appliquecrypt),
            )
            fun encLabel(v: String) =
                encChoices.firstOrNull { it.first == v }?.second ?: activity.getString(R.string.enc_none)
            encRow = row("${activity.getString(R.string.title_encryption)}: ${encLabel(xp3Enc)}") {
                val labels = encChoices.map { it.second }.toTypedArray()
                val checked = encChoices.indexOfFirst { it.first == xp3Enc }.coerceAtLeast(0)
                android.app.AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.title_encryption))
                    .setSingleChoiceItems(labels, checked) { d, w ->
                        xp3Enc = encChoices[w].first
                        encRow?.text = "${activity.getString(R.string.title_encryption)}: ${encLabel(xp3Enc)}"
                        d.dismiss()
                    }
                    .setNegativeButton(activity.getString(R.string.action_cancel), null)
                    .show().also { it.keepTabsTappable() }
            }
            addView(encRow)
            // The requirement, spelled out under the row — packing without the
            // sidecar / an existing encrypted archive cannot work, and a
            // half-explained failure at pack time is worse than saying it here.
            addView(android.widget.TextView(activity).apply {
                text = activity.getString(R.string.enc_cxdec_hint)
                setTextColor(C["hint"]!!)
                setTextSize(12f)
                setPadding(24, 4, 24, 8)
            })
        }
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
                    .show().also { it.keepTabsTappable() }
            }
            addView(splitRow)
        }
        if (isPfs || isRgss || isMvPackKey(fmt)) {  // xp3's divider is drawn above its row
            addView(android.view.View(activity).apply {
                setBackgroundColor(C["divider_subtle"]!!)
                layoutParams = android.widget.LinearLayout.LayoutParams(MATCH, 1).apply { setMargins(24, 0, 24, 0) }
            })
        }
        if (isPfs) {
            addView(androidx.appcompat.widget.SwitchCompat(activity).apply {
                isChecked = artemisNaming
                text = activity.getString(R.string.pfs_artemis_naming)
                setTextColor(C["primary"]!!)
                setPadding(24, 12, 24, 12)
                setOnCheckedChangeListener { _, checked ->
                    artemisNaming = checked
                    prefs.edit().putBoolean("pfs_artemis_naming", checked).apply()
                }
            })
        }
        if (isRgss) {
            addView(androidx.appcompat.widget.SwitchCompat(activity).apply {
                isChecked = gameNaming
                text = activity.getString(R.string.rgss_game_naming)
                setTextColor(C["primary"]!!)
                setPadding(24, 12, 24, 12)
                setOnCheckedChangeListener { _, checked ->
                    gameNaming = checked
                    prefs.edit().putBoolean("rgss_game_naming", checked).apply()
                }
            })
        }
        if (isMvPackKey(fmt)) {
            addView(android.widget.TextView(activity).apply {
                text = activity.getString(R.string.rpgmv_key_hint)
                setTextColor(C["hint"]!!)
                setTextSize(12f)
                setPadding(24, 4, 24, 4)
            })
            mvKeyField = android.widget.EditText(activity).apply {
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                setText(mvKey)
                hint = activity.getString(R.string.rpgmv_key_title)
                setTextColor(C["primary"]!!)
                setHintTextColor(C["hint"]!!)
                setPadding(24, 8, 24, 8)
            }
            addView(mvKeyField)
        }
    }
    android.app.AlertDialog.Builder(activity)
        .setTitle(activity.getString(R.string.title_compress_options))
        .setView(body)
        .setPositiveButton(activity.getString(R.string.action_confirm)) { _, _ ->
            // Read the field here rather than trusting a focus listener: tapping
            // Confirm does not necessarily blur it, and packing with a stale key
            // would produce an archive the game cannot read.
            mvKeyField?.let { field ->
                mvKey = field.text.toString().trim()
                prefs.edit().putString(PREF_MV_KEY, mvKey).apply()
            }
            if (!onResolved(levelVals[level], chosenSplit, artemisNaming, gameNaming, mvKey, xp3Enc)) return@setPositiveButton
        }
        .setNegativeButton(activity.getString(R.string.action_cancel), null)
        .show().also { it.keepTabsTappable() }
}

private fun runCompress(
    activity: AppCompatActivity, dir: File, currentDir: File, prefs: SharedPreferences,
    fmt: String, level: Int, splitSize: Long, onComplete: () -> Unit,
    ownerTab: TabState? = null, artemisNaming: Boolean = false,
    gameNaming: Boolean = false, mvKey: String = "", xp3Enc: String = ""
) {
    val ext = COMPRESS_EXT[fmt] ?: fmt
    // 产物名的三种规则：
    //  - MV/MZ：替换扩展名（hero.png → hero.rpgmvp）——RPG Maker 只按 .rpgm* 找
    //  - KSD：同样替换（save.txt → save.ksd）
    //  - 其余：追加（save.txt → save.txt.gz）
    val outName = when {
        isMvPackKey(fmt) -> mvPackedName(dir, fmt)
        fmt == "ksd" -> "${dir.nameWithoutExtension}.$ext"
        else -> "${dir.name}.$ext"
    }
    val outDir = dir.parentFile ?: currentDir
    val pwEnabled = prefs.getBoolean("compress_password_enabled", false)
    val password = if (pwEnabled) prefs.getString("compress_password", "") ?: "" else ""
    val splitEnabled = splitSize > 0 && fmt in setOf("zip", "7z")
    // Acquire a scheduler slot BEFORE showing the progress dialog — a queued
    // op surfaces position/ETA in that dialog instead of being refused.
    // PF6 与 PFS 共用同一 cdylib 的 compress_progress store，调度 key 归并到
    // "pfs" 才能串行——并行的 pf8/pf6 封包会互相污染对方的进度读数。
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
            // 产物名在【拿到槽位之后】解析：入队时解析的话，排队中的第二次封包
            // 会和第一次拿到同一个名字，后完成者覆盖先完成者（产物丢失）。
            val outFile = resolvePfsOutName(uniqueFile(outDir, outName), artemisNaming)
            // RPG Maker only opens `Game.<ext>`, so resolve that name here
            // rather than writing something the game silently ignores.
            val (finalFile, renamed) = resolveRgssOutName(outFile, gameNaming, ext)
            val ok = compressDispatch(dir, finalFile, fmt, level, if (isMvPackKey(fmt)) mvKey else password, prefs, splitSize, xp3Enc)
            // Which scheme an encrypted pack actually used is only known once it
            // has run; the native side reports it for the result message.
            val encNote = if (ok && xp3Enc == "cxdec") encNoteText(strFnOf(activity)) else ""
            if (cancelled || !ok) {
                // split_volumes already removed the original; remove the
                // `.001/.002/...` parts too, not just the output.
                finalFile.parentFile?.listFiles()?.filter { f ->
                    f.name.startsWith(finalFile.name + ".") &&
                    f.name.substringAfterLast('.').isNotEmpty() &&
                    f.name.substringAfterLast('.').all { it.isDigit() }
                }?.forEach { it.delete() }
                var deleted = false; for (i in 0..10) { deleted = finalFile.delete(); if (deleted) break else Thread.sleep(200) }
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                prog.dismiss()
                if (cancelled) { Toast.makeText(activity, activity.getString(R.string.msg_cancelled), Toast.LENGTH_SHORT).show() }
                else if (ok) {
                    val shown = if (splitEnabled) "${finalFile.name}.001" else finalFile.name
                    val label = if (renamed) {
                        activity.getString(R.string.msg_renamed_to, shown) + " " +
                            activity.getString(R.string.rgss_game_naming_note)
                    } else "${activity.getString(R.string.msg_extract_complete)} $shown"
                    val full = if (encNote.isEmpty()) label else "$label $encNote"
                    Toast.makeText(activity, full, if (renamed || encNote.isNotEmpty()) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                    onComplete()
                }
                else {
                    val why = PackErrors.last
                    val msg = activity.getString(R.string.title_compress_failed) +
                        if (why.isEmpty()) "" else "\n$why"
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                }
            }
        } finally {
            opH.release()
        }
    }
}

/** 通用压缩派发：任何来源（单文件/目录/临时合并目录）→ 指定格式，供单文件、批量合并、批量分别共用。 */
/**
 * Human sentence for the scheme the last xp3 pack used ("" when it was plain or
 * the native note is unreadable).
 *
 * A cxdec pack resolves its scheme from somewhere, and the note distinguishes a
 * scheme scored against a real encrypted archive from one derived from the
 * game's script — the second is not verifiable, and saying so is the point. A
 * keyless pack resolves nothing (the key is each entry's own ADLR), so it has no
 * source to name and reports the scheme itself instead.
 */
fun encNoteText(str: StrFn): String {
    val json = try { Xp3Core.xp3LastEncNote() } catch (_: Exception) { null }
    if (json.isNullOrEmpty()) return ""
    return try {
        val o = org.json.JSONObject(json)
        val source = o.optString("source", "")
        when {
            // No source = a keyless scheme the user picked: there is nothing to
            // derive it from, so the useful thing to say is which one it was.
            source.isEmpty() -> str(R.string.enc_note_scheme, arrayOf(o.optString("scheme", "")))
            o.optBoolean("verified", false) -> str(R.string.enc_note_verified, arrayOf(source))
            else -> str(R.string.enc_note_unverified, arrayOf(source))
        }
    } catch (_: Exception) {
        ""
    }
}

/**
 * Why the last [compressDispatch] failed ("" = none). The native layer explains
 * WHAT is wrong — a refused cxdec pack names what the folder is missing — and a
 * bare "failed" hides exactly the part the user needs. Same idea as the CLI's
 * `lastListError`; safe as a holder because one format slot is held for the
 * whole operation, so no two packs of the same format run at once.
 */
object PackErrors {
    @Volatile var last: String = ""
}

fun compressDispatch(src: File, outFile: File, fmt: String, level: Int, password: String, prefs: SharedPreferences, splitOverride: Long? = null, enc: String = ""): Boolean {
    PackErrors.last = ""
    // 分卷大小（字节），仅 zip/7z 支持；0 = 不分卷。inline 压缩选项可传显式值。
    val split = splitOverride ?: prefs.getLong("compress_split_size", 0L)
    val splitStr = if (split > 0 && fmt in setOf("zip", "7z")) split.toString() else "0"
    return try {
        when (fmt) {
            // enc: "" = plain, "cxdec" = cxdec-encrypted (the scheme is resolved
            // from the folder at pack time — an existing encrypted archive, else
            // the game's own xp3filter.tjs), "crypt:<Name>" = a keyless scheme
            // the user chose, which needs nothing resolved.
            // See Xp3Core.xp3CreateArchive.
            "xp3" -> Xp3Core.xp3CreateArchive("", src.path, outFile.path, level.toString(), enc) != null
            "pfs" -> PfsCore.pfsCreateArchive("", src.path, outFile.path) != null
            "pf6" -> PfsCore.pfsCreateArchivePf6("", src.path, outFile.path) != null
            "nsa" -> NsaCore.nsaCreateArchive("", src.path, outFile.path, if (level > 0) "2" else "0") != null
            // RPA stores payloads raw: `level` has nothing to act on, and the
            // 2-tuple index form this writer emits is the shape Ren'Py's own
            // reader fast-paths.
            "rpa" -> RpaCore.rpaCreateArchive("", src.path, outFile.path, level.toString())
            // INT: the variant (plain or encrypted) follows whether the game's
            // exe sits next to the output, so the archive matches what the
            // engine that shipped the original can load.
            "int" -> IntCore.intCreateArchive("", src.path, outFile.path, level.toString())
            "iso" -> IsoCore.isoCreateArchive("", src.path, outFile.path) != null
            "ypf" -> YpfCore.ypfCreateArchive("", src.path, outFile.path, level.toString()) != null
            "rgssad", "rgss2a", "rgss3a" -> RgssCore.rgssCreateArchive("", src.path, outFile.path, rgssWriteVersionOf(fmt)) != null
            // MV/MZ obfuscation. `password` carries the keystream here: the
            // format key already picked the asset type, so the only thing left
            // for the user to supply is the key.
            "rpgmvp", "rpgmvo", "rpgmvm" -> RgssCore.rgssMvEncrypt("", src.path, outFile.path, password) != null
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
    } catch (e: Exception) {
        PackErrors.last = e.message ?: ""
        false
    }
}
