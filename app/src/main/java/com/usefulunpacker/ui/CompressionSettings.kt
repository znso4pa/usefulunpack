package com.usefulunpacker

import android.view.View
import android.widget.*
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
        val rowZip = view.findViewById<UuActionRow>(R.id.rowZip)
        val rowSz = view.findViewById<UuActionRow>(R.id.rowSz)
        val rowGeneric = view.findViewById<UuActionRow>(R.id.rowGeneric)
        val rowSplit = view.findViewById<UuActionRow>(R.id.rowSplit)
        val switchPwd = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPwd)
        val pwdWrap = view.findViewById<View>(R.id.pwdWrap)
        val etPassword = view.findViewById<EditText>(R.id.etPassword)
        val btnEye = view.findViewById<ImageButton>(R.id.btnEye)

        switchMode.isChecked = prefs.getInt("work_mode", 0) == 1
        switchPwd.isChecked = passwordEnabled
        pwdWrap.visibility = if (passwordEnabled) View.VISIBLE else View.GONE
        etPassword.setText(prefs.getString("compress_password", ""))

        fun bindRow(row: UuActionRow, title: String, value: String, onClick: () -> Unit) {
            row.bind(title, value, navigation = true, onClick = onClick)
        }

        fun singleChoice(title: String, labels: Array<String>, checked: Int, onPick: (Int) -> Unit) {
            com.usefulunpacker.UuDialogBuilder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked) { _, w -> onPick(w) }
                .setPositiveButton(getString(R.string.action_confirm), null)
                .show().also { keepTabBarTappable(it) }
        }


        fun showCustomSplitDialog() {
            var unitIdx = if (splitSize > 0 && splitSize % (1024L * 1024 * 1024) == 0L) 1 else 0
            val unitLabels = arrayOf(getString(R.string.unit_mb), getString(R.string.unit_gb))
            val inp = com.usefulunpacker.UuTextField(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(if (splitSize > 0) (splitSize / (if (unitIdx == 1) 1024L * 1024 * 1024 else 1024L * 1024)).toString() else "")
                hint = "64"
            }
            val btnUnit = com.usefulunpacker.UuButton(this).apply {
                text = unitLabels[unitIdx];
                setOnClickListener { unitIdx = (unitIdx + 1) % 2; text = unitLabels[unitIdx] }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(resources.getDimensionPixelSize(R.dimen.space_xl), resources.getDimensionPixelSize(R.dimen.space_sm), resources.getDimensionPixelSize(R.dimen.space_xl), 0)
                addView(inp, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(btnUnit)
            }
            com.usefulunpacker.UuDialogBuilder(this)
                .setTitle(getString(R.string.split_custom))
                .setMessage(getString(R.string.split_min_note))
                .setView(row)
                .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                    val n = inp.text.toString().trim().toLongOrNull()
                    val factor = if (unitIdx == 1) 1024L * 1024 * 1024 else 1024L * 1024
                    if (n != null && n >= 1 && n <= 2048) {
                        splitSize = n * factor
                        rowSplit.valueText = fmtSplitSize(splitSize)
                    } else failureNotice(getString(R.string.split_invalid))
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show().also { keepTabBarTappable(it) }
        }

        bindRow(rowZip, "ZIP " + getString(R.string.settings_compress), levelLabel(ZIP_VALS, ZIP_LABELS, zipLevel)) {
            singleChoice("ZIP " + getString(R.string.settings_compress), ZIP_LABELS, ZIP_VALS.indexOfFirst { it == zipLevel }.coerceAtLeast(0)) { w ->
                zipLevel = ZIP_VALS[w]; rowZip.valueText = levelLabel(ZIP_VALS, ZIP_LABELS, zipLevel)
            }
        }
        bindRow(rowSz, "7z " + getString(R.string.settings_compress), levelLabel(SZ_VALS, ZIP_LABELS, szLevel)) {
            singleChoice("7z " + getString(R.string.settings_compress), ZIP_LABELS, SZ_VALS.indexOfFirst { it == szLevel }.coerceAtLeast(0)) { w ->
                szLevel = SZ_VALS[w]; rowSz.valueText = levelLabel(SZ_VALS, ZIP_LABELS, szLevel)
            }
        }
        bindRow(rowGeneric, getString(R.string.settings_compress_generic), levelLabel(ZIP_VALS, ZIP_LABELS, genericLevel)) {
            singleChoice(getString(R.string.settings_compress_generic), ZIP_LABELS, ZIP_VALS.indexOfFirst { it == genericLevel }.coerceAtLeast(0)) { w ->
                genericLevel = ZIP_VALS[w]; rowGeneric.valueText = levelLabel(ZIP_VALS, ZIP_LABELS, genericLevel)
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
                    0 -> { splitSize = 0L; rowSplit.valueText = fmtSplitSize(splitSize) }
                    4 -> showCustomSplitDialog()
                    else -> { splitSize = SPLIT_PRESET[w]; rowSplit.valueText = fmtSplitSize(splitSize) }
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

        com.usefulunpacker.UuDialogBuilder(this)
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
            .setNegativeButton(R.string.action_cancel, null)
            .show().also { keepTabBarTappable(it) }
    }
