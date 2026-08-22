package com.usefulunpacker

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.View
import android.widget.*
import java.io.File
import kotlin.concurrent.thread

private fun MainActivity.dp(id: Int) = resources.getDimensionPixelSize(id)
private fun MainActivity.spx(id: Int) = resources.getDimension(id) / resources.displayMetrics.scaledDensity

internal fun MainActivity.showCompressionSettings() {
        var zipLevel = prefs.getInt("zip_level", 5).let { if (it !in intArrayOf(0, 3, 5, 7, 9)) 5 else it }
        var szLevel = prefs.getInt("sz_level", 6).let { if (it !in intArrayOf(0, 3, 6, 9, 12)) 6 else it }
        var genericLevel = prefs.getInt("generic_level", 6).let { if (it !in intArrayOf(0, 3, 5, 7, 9)) 6 else it }
        var splitSize = prefs.getLong("compress_split_size", 0L)
        var passwordEnabled = prefs.getBoolean("compress_password_enabled", false)

        val ZIP_LABELS = arrayOf(getString(R.string.level_store), getString(R.string.level_low), getString(R.string.level_medium), getString(R.string.level_high), getString(R.string.level_extreme))
        val ZIP_VALS = intArrayOf(0, 3, 5, 7, 9)
        val SZ_VALS = intArrayOf(0, 3, 6, 9, 12)
        val SPLIT_PRESET = longArrayOf(0L, 1024L * 1024, 100L * 1024 * 1024, 1024L * 1024 * 1024)
        val SPLIT_VALS = arrayOf(getString(R.string.split_none)) + resources.getStringArray(R.array.split_size_labels) + arrayOf(getString(R.string.split_custom))

        fun levelLabel(vals: IntArray, labels: Array<String>, v: Int): String =
            labels[vals.indexOfFirst { it == v }.coerceAtLeast(0)]
        fun fmtSplitSize(v: Long): String = when {
            v <= 0L -> getString(R.string.split_none)
            v >= 1024L * 1024 * 1024 && v % (1024L * 1024 * 1024) == 0L -> "${v / (1024L * 1024 * 1024)}GB"
            else -> "${v / (1024L * 1024)}MB"
        }

        val view = layoutInflater.inflate(R.layout.dialog_compress_settings, null)
        val switchMode = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchMode)
        val rowZip = view.findViewById<View>(R.id.rowZip)
        val rowSz = view.findViewById<View>(R.id.rowSz)
        val rowGeneric = view.findViewById<View>(R.id.rowGeneric)
        val rowSplit = view.findViewById<View>(R.id.rowSplit)
        val switchPwd = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPwd)
        val pwdWrap = view.findViewById<View>(R.id.pwdWrap)
        val etPassword = view.findViewById<EditText>(R.id.etPassword)
        val btnEye = view.findViewById<ImageButton>(R.id.btnEye)

        switchMode.isChecked = prefs.getInt("work_mode", 0) == 1
        switchPwd.isChecked = passwordEnabled
        pwdWrap.visibility = if (passwordEnabled) View.VISIBLE else View.GONE
        etPassword.setText(prefs.getString("compress_password", ""))

        fun bindRow(row: View, title: String, value: String, onClick: () -> Unit) {
            row.findViewById<TextView>(R.id.settings_row_title).text = title
            row.findViewById<TextView>(R.id.settings_row_value).text = value
            row.setOnClickListener { onClick() }
        }

        fun singleChoice(title: String, labels: Array<String>, checked: Int, onPick: (Int) -> Unit) {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked) { _, w -> onPick(w) }
                .setPositiveButton(getString(R.string.action_confirm), null)
                .show()
        }

        val zipVal = rowZip.findViewById<TextView>(R.id.settings_row_value)
        val szVal = rowSz.findViewById<TextView>(R.id.settings_row_value)
        val genericVal = rowGeneric.findViewById<TextView>(R.id.settings_row_value)
        val splitVal = rowSplit.findViewById<TextView>(R.id.settings_row_value)

        fun showCustomSplitDialog() {
            var unitIdx = if (splitSize > 0 && splitSize % (1024L * 1024 * 1024) == 0L) 1 else 0
            val unitLabels = arrayOf(getString(R.string.unit_mb), getString(R.string.unit_gb))
            val inp = EditText(this).apply {
                setTextColor(C["primary"]!!); setHintTextColor(C["hint"]!!)
                setBackgroundColor(C["surface_dark"]!!); setPadding(dp(R.dimen.space_md), dp(R.dimen.space_sm), dp(R.dimen.space_md), dp(R.dimen.space_sm)); textSize = spx(R.dimen.text_lg)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(if (splitSize > 0) (splitSize / (if (unitIdx == 1) 1024L * 1024 * 1024 else 1024L * 1024)).toString() else "")
                hint = "64"
            }
            val btnUnit = TextView(this).apply {
                text = unitLabels[unitIdx]; setTextColor(C["accent"]!!); textSize = spx(R.dimen.text_xl)
                setPadding(dp(R.dimen.space_md), dp(R.dimen.space_sm), dp(R.dimen.space_sm), dp(R.dimen.space_sm))
                setOnClickListener { unitIdx = (unitIdx + 1) % 2; text = unitLabels[unitIdx] }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(dp(R.dimen.space_xl), dp(R.dimen.space_sm), dp(R.dimen.space_xl), 0)
                addView(inp, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(btnUnit)
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.split_custom))
                .setMessage(getString(R.string.split_min_note))
                .setView(row)
                .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                    val n = inp.text.toString().trim().toLongOrNull()
                    val factor = if (unitIdx == 1) 1024L * 1024 * 1024 else 1024L * 1024
                    if (n != null && n >= 1 && n <= 2048) {
                        splitSize = n * factor
                        splitVal.text = fmtSplitSize(splitSize)
                    } else toast(getString(R.string.split_invalid))
                }
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show()
        }

        bindRow(rowZip, "ZIP " + getString(R.string.settings_compress), levelLabel(ZIP_VALS, ZIP_LABELS, zipLevel)) {
            singleChoice("ZIP " + getString(R.string.settings_compress), ZIP_LABELS, ZIP_VALS.indexOfFirst { it == zipLevel }.coerceAtLeast(0)) { w ->
                zipLevel = ZIP_VALS[w]; zipVal.text = levelLabel(ZIP_VALS, ZIP_LABELS, zipLevel)
            }
        }
        bindRow(rowSz, "7z " + getString(R.string.settings_compress), levelLabel(SZ_VALS, ZIP_LABELS, szLevel)) {
            singleChoice("7z " + getString(R.string.settings_compress), ZIP_LABELS, SZ_VALS.indexOfFirst { it == szLevel }.coerceAtLeast(0)) { w ->
                szLevel = SZ_VALS[w]; szVal.text = levelLabel(SZ_VALS, ZIP_LABELS, szLevel)
            }
        }
        bindRow(rowGeneric, getString(R.string.settings_compress_generic), levelLabel(ZIP_VALS, ZIP_LABELS, genericLevel)) {
            singleChoice(getString(R.string.settings_compress_generic), ZIP_LABELS, ZIP_VALS.indexOfFirst { it == genericLevel }.coerceAtLeast(0)) { w ->
                genericLevel = ZIP_VALS[w]; genericVal.text = levelLabel(ZIP_VALS, ZIP_LABELS, genericLevel)
            }
        }
        bindRow(rowSplit, getString(R.string.split_title), fmtSplitSize(splitSize)) {
            val checked = when (splitSize) {
                0L -> 0
                SPLIT_PRESET[1] -> 1
                SPLIT_PRESET[2] -> 2
                SPLIT_PRESET[3] -> 3
                else -> 4
            }
            singleChoice(getString(R.string.split_title), SPLIT_VALS, checked) { w ->
                when (w) {
                    0 -> { splitSize = 0L; splitVal.text = fmtSplitSize(splitSize) }
                    4 -> showCustomSplitDialog()
                    else -> { splitSize = SPLIT_PRESET[w]; splitVal.text = fmtSplitSize(splitSize) }
                }
            }
        }

        switchPwd.setOnCheckedChangeListener { _, checked ->
            passwordEnabled = checked
            pwdWrap.visibility = if (checked) View.VISIBLE else View.GONE
        }
        var pwdHidden = true
        btnEye.setOnClickListener {
            pwdHidden = !pwdHidden
            etPassword.inputType = if (pwdHidden)
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            else
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            etPassword.setSelection(etPassword.text.length)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_compress))
            .setView(view)
            .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                prefs.edit().putInt("work_mode", if (switchMode.isChecked) 1 else 0).apply()
                prefs.edit().putInt("zip_level", zipLevel).apply()
                prefs.edit().putInt("sz_level", szLevel).apply()
                prefs.edit().putInt("generic_level", genericLevel).apply()
                if (splitSize > 0) prefs.edit().putLong("compress_split_size", splitSize).apply()
                else prefs.edit().remove("compress_split_size").apply()
                prefs.edit().putBoolean("compress_password_enabled", passwordEnabled).apply()
                if (passwordEnabled) prefs.edit().putString("compress_password", etPassword.text.toString()).apply()
                else prefs.edit().remove("compress_password").apply()
                val m = if (switchMode.isChecked) 1 else 0
                findViewById<TextView>(R.id.tvTitle)?.text = "UsefulUnpack" + (if (m == 1) getString(R.string.title_mode_compress) else getString(R.string.title_mode_archive))
                if (m == 0) { bottomBar.visibility = View.GONE; btnExtract.text = getString(R.string.msg_extract_title); btnExtract.setOnClickListener { extract() }; activeTab.btnFolderNext?.visibility = View.GONE; selectedFile = null }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

internal fun MainActivity.showGeneralSettings() {
        val langTags = arrayOf("zh-CN", "zh-TW", "ja", "en")
        val langLabels = arrayOf(getString(R.string.language_zhcn), getString(R.string.language_zhtw), getString(R.string.language_ja), getString(R.string.language_en))
        val langCurrent = prefs.getString("app_lang", "zh-CN") ?: "zh-CN"
        var textEncCurrent = prefs.getString("text_encoding", "UTF-8") ?: "UTF-8"
        var zipEncCurrent = prefs.getString("zip_encoding", "UTF-8") ?: "UTF-8"
        val textEncLabels = arrayOf(getString(R.string.encoding_utf8), getString(R.string.encoding_sjis), getString(R.string.encoding_gbk), getString(R.string.encoding_utf16))
        val zipEncLabels = arrayOf(getString(R.string.encoding_utf8), getString(R.string.encoding_sjis), getString(R.string.encoding_gbk))
        val ZIP_ENC_VALS = arrayOf("UTF-8", "SHIFT-JIS", "GBK")

        fun row(title: String, value: String, onClick: () -> Unit) = TextView(this).apply {
            text = "$title\n$value"
            setTextColor(C["primary"]!!)
            textSize = spx(R.dimen.text_xl)
            setPadding(dp(R.dimen.space_lg), dp(R.dimen.space_lg), dp(R.dimen.space_lg), dp(R.dimen.space_lg))
            background = android.graphics.drawable.ColorDrawable(0x00000000)
            setOnClickListener { onClick() }
        }
        fun divider() = View(this).apply {
            setBackgroundColor(C["divider_subtle"]!!)
            layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { setMargins(dp(R.dimen.space_lg), 0, dp(R.dimen.space_lg), 0) }
        }
        fun pick(title: String, labels: Array<String>, vals: Array<String>, current: String, onPick: (String) -> Unit) {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, vals.indexOf(current).coerceAtLeast(0)) { d, which ->
                    onPick(vals[which])
                    d.dismiss()
                }
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show()
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C["surface"]!!)
            addView(row(
                getString(R.string.settings_language),
                langLabels[langTags.indexOf(langCurrent).coerceAtLeast(0)],
            ) {
                AlertDialog.Builder(this@showGeneralSettings)
                    .setTitle(getString(R.string.settings_language))
                    .setSingleChoiceItems(langLabels, langTags.indexOf(langCurrent).coerceAtLeast(0)) { d, which ->
                        val tag = langTags[which]
                        prefs.edit().putString("app_lang", tag).apply()
                        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                            androidx.core.os.LocaleListCompat.forLanguageTags(tag)
                        )
                        d.dismiss()
                        recreate()
                    }
                    .setNegativeButton(getString(R.string.action_cancel), null)
                    .show()
            })
            addView(divider())
            lateinit var textEncRow: TextView
            textEncRow = row(
                getString(R.string.settings_text_encoding),
                textEncLabels[TEXT_ENCODINGS.indexOf(textEncCurrent).coerceAtLeast(0)],
            ) {
                pick(getString(R.string.settings_text_encoding), textEncLabels, TEXT_ENCODINGS, textEncCurrent) { v ->
                    textEncCurrent = v
                    prefs.edit().putString("text_encoding", v).apply()
                    textEncRow.text = getString(R.string.settings_text_encoding) + "\n" +
                        textEncLabels[TEXT_ENCODINGS.indexOf(v).coerceAtLeast(0)]
                    toast(getString(R.string.msg_encoding_applied, v))
                }
            }
            addView(textEncRow)
            addView(divider())
            lateinit var zipEncRow: TextView
            zipEncRow = row(
                getString(R.string.settings_zip_encoding),
                zipEncLabels[ZIP_ENC_VALS.indexOf(zipEncCurrent).coerceAtLeast(0)],
            ) {
                pick(getString(R.string.settings_zip_encoding), zipEncLabels, ZIP_ENC_VALS, zipEncCurrent) { v ->
                    zipEncCurrent = v
                    prefs.edit().putString("zip_encoding", v).apply()
                    zipEncRow.text = getString(R.string.settings_zip_encoding) + "\n" +
                        zipEncLabels[ZIP_ENC_VALS.indexOf(v).coerceAtLeast(0)]
                    toast(getString(R.string.msg_encoding_applied, v))
                }
            }
            addView(zipEncRow)
            addView(divider())
            lateinit var parallelRow: TextView
            var parallelThreads = prefs.getInt("parallel_threads", 0).coerceIn(0, 8)
            val parallelLabels = arrayOf(getString(R.string.parallel_auto), "1", "2", "4", "8")
            val parallelVals = intArrayOf(0, 1, 2, 4, 8)
            parallelRow = row(
                getString(R.string.settings_parallel_threads),
                parallelLabels[parallelVals.indexOf(parallelThreads).coerceAtLeast(0)],
            ) {
                AlertDialog.Builder(this@showGeneralSettings)
                    .setTitle(getString(R.string.settings_parallel_threads))
                    .setSingleChoiceItems(parallelLabels, parallelVals.indexOf(parallelThreads).coerceAtLeast(0)) { d, w ->
                        parallelThreads = parallelVals[w]
                        prefs.edit().putInt("parallel_threads", parallelThreads).apply()
                        RarCore.setParallelThreads(parallelThreads)
                        ZipCore.setParallelThreads(parallelThreads)
                        parallelRow.text = getString(R.string.settings_parallel_threads) + "\n" + parallelLabels[w]
                        d.dismiss()
                    }
                    .setNegativeButton(getString(R.string.action_cancel), null)
                    .show()
            }
            addView(parallelRow)
            addView(divider())
            lateinit var sortRow: TextView
            var sortMode = prefs.getString("sort_mode", "name_asc") ?: "name_asc"
            val sortLabels = arrayOf(getString(R.string.sort_by_name), getString(R.string.sort_by_size), getString(R.string.sort_by_date))
            val sortVals = arrayOf("name_asc", "name_desc", "size_asc", "size_desc", "date_asc", "date_desc")
            val sortDisplayLabels = arrayOf(
                getString(R.string.sort_by_name) + " " + getString(R.string.sort_ascending),
                getString(R.string.sort_by_name) + " " + getString(R.string.sort_descending),
                getString(R.string.sort_by_size) + " " + getString(R.string.sort_ascending),
                getString(R.string.sort_by_size) + " " + getString(R.string.sort_descending),
                getString(R.string.sort_by_date) + " " + getString(R.string.sort_ascending),
                getString(R.string.sort_by_date) + " " + getString(R.string.sort_descending)
            )
            sortRow = row(
                getString(R.string.settings_sort),
                sortDisplayLabels[sortVals.indexOf(sortMode).coerceAtLeast(0)],
            ) {
                AlertDialog.Builder(this@showGeneralSettings)
                    .setTitle(getString(R.string.settings_sort))
                    .setSingleChoiceItems(sortDisplayLabels, sortVals.indexOf(sortMode).coerceAtLeast(0)) { d, w ->
                        sortMode = sortVals[w]
                        prefs.edit().putString("sort_mode", sortMode).apply()
                        sortRow.text = getString(R.string.settings_sort) + "\n" + sortDisplayLabels[w]
                        nav(currentDir)
                        d.dismiss()
                    }
                    .setNegativeButton(getString(R.string.action_cancel), null)
                    .show()
            }
            addView(sortRow)
            addView(divider())
            // Path/file picker mode: in-dialog (legacy) vs. a new window tab.
            lateinit var pickerRow: TextView
            var pickerMode = prefs.getString("picker_mode", "dialog") ?: "dialog"
            val pickerLabels = arrayOf(
                getString(R.string.picker_mode_dialog),
                getString(R.string.picker_mode_tab))
            val pickerVals = arrayOf("dialog", "tab")
            pickerRow = row(
                getString(R.string.settings_picker_mode),
                pickerLabels[pickerVals.indexOf(pickerMode).coerceAtLeast(0)],
            ) {
                AlertDialog.Builder(this@showGeneralSettings)
                    .setTitle(getString(R.string.settings_picker_mode))
                    .setSingleChoiceItems(pickerLabels, pickerVals.indexOf(pickerMode).coerceAtLeast(0)) { d, w ->
                        pickerMode = pickerVals[w]
                        prefs.edit().putString("picker_mode", pickerMode).apply()
                        pickerRow.text = getString(R.string.settings_picker_mode) + "\n" + pickerLabels[w]
                        d.dismiss()
                    }
                    .setNegativeButton(getString(R.string.action_cancel), null)
                    .show()
            }
            addView(pickerRow)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_general))
            .setView(body)
            .setPositiveButton(getString(R.string.action_close), null)
            .show()
    }

internal fun MainActivity.settings() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings))
            .setItems(arrayOf(
                getString(R.string.settings_ui), getString(R.string.settings_general), getString(R.string.settings_compress),
                getString(R.string.settings_other), getString(R.string.settings_help), getString(R.string.settings_disclaimer)
            )) { _, which ->
                when (which) {
                    0 -> showUISettings()
                    1 -> showGeneralSettings()
                    2 -> showCompressionSettings()
                    3 -> showOtherSettings()
                    4 -> showHelpDialog()
                    5 -> showDisclaimer(fromSettings = true)
                }
            }
            .setNegativeButton(getString(R.string.action_close), null)
            .show()
    }

internal fun MainActivity.showOtherSettings() {
    val act = this
    val switchRestore = androidx.appcompat.widget.SwitchCompat(act).apply {
        isChecked = prefs.getBoolean("restore_session", false)
        setTextColor(C["primary"]!!)
    }
    val container = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(R.dimen.space_lg), dp(R.dimen.space_md), dp(R.dimen.space_lg), dp(R.dimen.space_md))
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(TextView(act).apply {
                text = getString(R.string.restore_session)
                setTextColor(C["primary"]!!)
                textSize = spx(R.dimen.text_lg)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            addView(switchRestore)
        })
        addView(TextView(act).apply {
            text = getString(R.string.restore_session_desc)
            setTextColor(C["tertiary"]!!)
            textSize = spx(R.dimen.text_xs)
            setPadding(0, dp(R.dimen.space_xs), 0, dp(R.dimen.space_md))
        })
        addView(TextView(act).apply {
            text = getString(R.string.title_recycle_bin)
            setTextColor(C["primary"]!!)
            textSize = spx(R.dimen.text_lg)
            setPadding(0, dp(R.dimen.space_md), 0, 0)
            isClickable = true
            setOnClickListener { showRecycleBinSettings() }
        })
    }
    AlertDialog.Builder(act)
        .setTitle(getString(R.string.settings_other))
        .setView(container)
        .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
            prefs.edit().putBoolean("restore_session", switchRestore.isChecked).apply()
        }
        .setNegativeButton(getString(R.string.action_cancel), null)
        .show()
    }

internal fun MainActivity.showRecycleBinSettings() {
    val recycleEnabled = com.usefulunpacker.fileops.RecycleBin.isEnabled(prefs)
    var autoCleanDays = com.usefulunpacker.fileops.RecycleBin.autoCleanDays(prefs)
    val itemCount = com.usefulunpacker.fileops.RecycleBin.getItemCount(this)
    val usedSize = com.usefulunpacker.fileops.RecycleBin.getUsedSize(this)

    val switchEnabled = androidx.appcompat.widget.SwitchCompat(this).apply {
        isChecked = recycleEnabled
        setTextColor(C["primary"]!!)
    }

    val tvInfo = TextView(this).apply {
        text = getString(R.string.recycle_items, itemCount, fmt(usedSize))
        setTextColor(C["tertiary_light"]!!)
        textSize = spx(R.dimen.text_sm)
        setPadding(0, dp(R.dimen.space_sm), 0, 0)
    }

    val tvAutoClean = TextView(this).apply {
        text = getString(R.string.recycle_auto_clean, if (autoCleanDays == 0) getString(R.string.recycle_auto_clean_never) else getString(R.string.recycle_days_format, autoCleanDays))
        setTextColor(C["primary"]!!)
        textSize = spx(R.dimen.text_lg)
    }

    val btnEmpty = Button(this).apply {
        text = getString(R.string.recycle_empty_now)
        setTextColor(C["error"]!!)
        background = null
        textSize = spx(R.dimen.text_md)
        isEnabled = itemCount > 0
    }

    val act = this
    val container = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(R.dimen.space_lg), dp(R.dimen.space_lg), dp(R.dimen.space_lg), dp(R.dimen.space_lg))
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(TextView(act).apply {
                text = getString(R.string.recycle_enable)
                setTextColor(C["primary"]!!)
                textSize = spx(R.dimen.text_lg)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            addView(switchEnabled)
        })
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(R.dimen.space_lg), 0, 0)
            addView(TextView(act).apply {
                text = getString(R.string.recycle_auto_clean)
                setTextColor(C["primary"]!!)
                textSize = spx(R.dimen.text_lg)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            addView(tvAutoClean)
        })
        addView(tvInfo)
        addView(btnEmpty)
    }

    tvAutoClean.setOnClickListener {
        val daysOptions = intArrayOf(7, 14, 30, 60, 90, 0)
        val daysLabels = daysOptions.map { 
            if (it == 0) getString(R.string.recycle_auto_clean_never) 
            else getString(R.string.recycle_days_format, it) 
        }.toTypedArray()
        val currentIdx = daysOptions.indexOf(autoCleanDays).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.recycle_auto_clean))
            .setSingleChoiceItems(daysLabels, currentIdx) { _, w ->
                autoCleanDays = daysOptions[w]
                tvAutoClean.text = getString(R.string.recycle_auto_clean, daysLabels[w])
            }
            .setPositiveButton(getString(R.string.action_confirm), null)
            .show()
    }

    btnEmpty.setOnClickListener {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.recycle_empty_now))
            .setMessage(getString(R.string.recycle_empty_confirm))
            .setPositiveButton(getString(R.string.action_delete)) { _, _ ->
                com.usefulunpacker.fileops.RecycleBin.emptyRecycleBin(this)
                toast(getString(R.string.recycle_empty))
                nav(currentDir)
                showRecycleBinSettings()
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    AlertDialog.Builder(this)
        .setTitle(getString(R.string.title_recycle_bin))
        .setView(container)
        .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
            prefs.edit().putBoolean("recycle_bin_enabled", switchEnabled.isChecked).apply()
            prefs.edit().putInt("recycle_bin_auto_clean_days", autoCleanDays).apply()
        }
        .setNegativeButton(getString(R.string.action_cancel), null)
        .show()
}

internal fun MainActivity.showUISettings() {
    val act = this
        // Background image toggle row
        val hasBg = prefs.getString("bg_image_uri", null) != null
        val tvBgInfo = TextView(this).apply {
            text = if (hasBg) getString(R.string.bg_set) else getString(R.string.bg_not_set)
            setTextColor(C["tertiary_light"]!!); textSize = spx(R.dimen.text_md)
        }
        val btnPickBg = Button(this).apply {
            text = if (hasBg) getString(R.string.bg_change) else getString(R.string.bg_pick)
            setTextColor(C["accent"]!!); background = null; textSize = spx(R.dimen.text_md)
        }
        val btnClearBg = Button(this).apply {
            text = getString(R.string.bg_clear)
            setTextColor(C["error"]!!); background = null; textSize = spx(R.dimen.text_md)
            visibility = if (hasBg) View.VISIBLE else View.GONE
        }
        val bgRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(R.dimen.space_lg), 0, dp(R.dimen.space_lg), dp(R.dimen.space_sm))
            addView(tvBgInfo, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(btnClearBg)
            addView(btnPickBg, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(8, 0, 0, 0) })
        }

        val curAlpha = prefs.getInt("bg_image_alpha", 20)
        val tvAlpha = TextView(act).apply {
            text = getString(R.string.bg_alpha, curAlpha); setTextColor(C["tertiary"]!!); textSize = spx(R.dimen.text_sm)
            setPadding(dp(R.dimen.space_xl), dp(R.dimen.space_xs), dp(R.dimen.space_lg), 0)
        }
        val seekAlpha = SeekBar(act).apply {
            max = 100; progress = curAlpha
            setPadding(dp(R.dimen.space_xl), 0, dp(R.dimen.space_xl), dp(R.dimen.space_sm))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { tvAlpha.text = getString(R.string.bg_alpha, p) }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(act).apply {
                text = getString(R.string.settings_bg_image); setTextColor(C["tertiary_light"]!!); textSize = spx(R.dimen.text_sm)
                setPadding(dp(R.dimen.space_lg), dp(R.dimen.space_md), dp(R.dimen.space_lg), 0)
            })
            addView(bgRow)
            addView(tvAlpha)
            addView(seekAlpha)
        }

        btnPickBg.setOnClickListener { bgImageLauncher?.launch("image/*") }
        btnClearBg.setOnClickListener {
            prefs.edit().remove("bg_image_uri").apply()
            findViewById<View>(R.id.root)?.setBackgroundResource(R.color.bg_surface)
            findViewById<View>(R.id.toolbar)?.setBackgroundResource(R.color.bg_toolbar)
            findViewById<View>(R.id.pathBar)?.setBackgroundResource(R.color.bg_pathbar)
            findViewById<View>(R.id.panel)?.setBackgroundResource(R.color.bg_file_list)
            window?.statusBarColor = 0xBB000000.toInt()
            tvBgInfo.text = getString(R.string.bg_not_set)
            btnClearBg.visibility = View.GONE
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_ui))
            .setView(body)
            .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                prefs.edit().putInt("bg_image_alpha", seekAlpha.progress).apply()
                prefs.getString("bg_image_uri", null)?.let { applyBackgroundImage(Uri.parse(it)) }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

internal fun MainActivity.applyBackgroundImage(uri: Uri) {
        try {
            // Decode (and close the stream) off the UI thread; downsample to the
            // screen so a huge wallpaper image doesn't ANR/OOM the main thread.
            thread {
                val bmp = runCatching {
                    contentResolver.openInputStream(uri)?.use { ins ->
                        // Sample down to roughly the root size before decoding.
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeStream(ins, null, bounds)
                        if (bounds.outWidth > 0) {
                            val screenW = resources.displayMetrics.widthPixels
                            val screenH = resources.displayMetrics.heightPixels
                            var sample = 1
                            while (bounds.outWidth / (sample * 2) >= screenW && bounds.outHeight / (sample * 2) >= screenH) sample *= 2
                            val opt = BitmapFactory.Options().apply { inSampleSize = sample }
                            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
                        } else {
                            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        }
                    }
                }.getOrNull() ?: return@thread
                val root = findViewById<View>(R.id.root) ?: return@thread
                val alpha = prefs.getInt("bg_image_alpha", 20).coerceIn(1, 100)
                runOnUiThread {
                    val rw = root.width; val rh = root.height
                    if (rw <= 0 || rh <= 0) return@runOnUiThread
                    val bmpW = bmp.width; val bmpH = bmp.height
                    val scale = maxOf(rw.toFloat() / bmpW, rh.toFloat() / bmpH)
                    val sw = (bmpW * scale).toInt(); val sh = (bmpH * scale).toInt()
                    val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, sw, sh, true)
                    bmp.recycle()
                    val x = maxOf((sw - rw) / 2, 0); val y = maxOf((sh - rh) / 2, 0)
                    val cw = minOf(rw, sw); val ch = minOf(rh, sh)
                    val cropped = android.graphics.Bitmap.createBitmap(scaled, x, y, cw, ch)
                    scaled.recycle()
                    val dr = android.graphics.drawable.BitmapDrawable(resources, cropped)
                    dr.alpha = (alpha * 255 / 100).coerceIn(1, 255)
                    root.background = dr
                }
            }
            // Make surfaces transparent so the bg shows through everywhere
            findViewById<View>(R.id.toolbar)?.setBackgroundColor(0xBB000000.toInt())
            findViewById<View>(R.id.pathBar)?.setBackgroundColor(0xBB000000.toInt())
            findViewById<View>(R.id.panel)?.background = null
            // Match status bar to toolbar
            window?.statusBarColor = 0xBB000000.toInt()
        } catch (_: Exception) {}
    }
