package com.usefulunpacker

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.View
import android.widget.*
import java.io.File

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
        val SPLIT_VALS = arrayOf(getString(R.string.split_none), "1MB", "100MB", "1GB", getString(R.string.split_custom))

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
            val unitLabels = arrayOf("MB", "GB")
            val inp = EditText(this).apply {
                setTextColor(C["primary"]!!); setHintTextColor(C["hint"]!!)
                setBackgroundColor(C["surface_dark"]!!); setPadding(12, 8, 12, 8); textSize = 14f
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText(if (splitSize > 0) (splitSize / (if (unitIdx == 1) 1024L * 1024 * 1024 else 1024L * 1024)).toString() else "")
                hint = "64"
            }
            val btnUnit = TextView(this).apply {
                text = unitLabels[unitIdx]; setTextColor(C["accent"]!!); textSize = 15f
                setPadding(12, 8, 4, 8)
                setOnClickListener { unitIdx = (unitIdx + 1) % 2; text = unitLabels[unitIdx] }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(28, 8, 28, 0)
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
                if (m == 0) { bottomBar.visibility = View.GONE; btnExtract.text = getString(R.string.msg_extract_title); btnExtract.setOnClickListener { extract() }; btnFolderNext.visibility = View.GONE; selectedFile = null }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

internal fun MainActivity.showGeneralSettings() {
        val langTags = arrayOf("zh-CN", "zh-TW", "ja", "en")
        val langLabels = arrayOf(getString(R.string.language_zhcn), getString(R.string.language_zhtw), getString(R.string.language_ja), getString(R.string.language_en))
        val langCurrent = prefs.getString("app_lang", "zh-CN") ?: "zh-CN"
        val textEncCurrent = prefs.getString("text_encoding", "UTF-8") ?: "UTF-8"
        val zipEncCurrent = prefs.getString("zip_encoding", "UTF-8") ?: "UTF-8"
        val textEncLabels = arrayOf(getString(R.string.encoding_utf8), getString(R.string.encoding_sjis), getString(R.string.encoding_gbk), getString(R.string.encoding_utf16))
        val zipEncLabels = arrayOf(getString(R.string.encoding_utf8), getString(R.string.encoding_sjis), getString(R.string.encoding_gbk))
        val ZIP_ENC_VALS = arrayOf("UTF-8", "SHIFT-JIS", "GBK")

        fun row(title: String, value: String, onClick: () -> Unit) = TextView(this).apply {
            text = "$title\n$value"
            setTextColor(C["primary"]!!)
            textSize = 15f
            setPadding(24, 16, 24, 16)
            background = android.graphics.drawable.ColorDrawable(0x00000000)
            setOnClickListener { onClick() }
        }
        fun divider() = View(this).apply {
            setBackgroundColor(C["divider_subtle"]!!)
            layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { setMargins(24, 0, 24, 0) }
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
            addView(row(
                getString(R.string.settings_text_encoding),
                textEncLabels[TEXT_ENCODINGS.indexOf(textEncCurrent).coerceAtLeast(0)],
            ) {
                pick(getString(R.string.settings_text_encoding), textEncLabels, TEXT_ENCODINGS, textEncCurrent) { v ->
                    prefs.edit().putString("text_encoding", v).apply()
                    toast(getString(R.string.msg_encoding_applied, v))
                }
            })
            addView(divider())
            addView(row(
                getString(R.string.settings_zip_encoding),
                zipEncLabels[ZIP_ENC_VALS.indexOf(zipEncCurrent).coerceAtLeast(0)],
            ) {
                pick(getString(R.string.settings_zip_encoding), zipEncLabels, ZIP_ENC_VALS, zipEncCurrent) { v ->
                    prefs.edit().putString("zip_encoding", v).apply()
                    toast(getString(R.string.msg_encoding_applied, v))
                }
            })
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
                getString(R.string.settings_help), getString(R.string.settings_disclaimer)
            )) { _, which ->
                when (which) {
                    0 -> showUISettings()
                    1 -> showGeneralSettings()
                    2 -> showCompressionSettings()
                    3 -> showHelpDialog()
                    4 -> showDisclaimer(fromSettings = true)
                }
            }
            .setNegativeButton(getString(R.string.action_close), null)
            .show()
    }

internal fun MainActivity.showUISettings() {
    val act = this
        // Background image toggle row
        val hasBg = prefs.getString("bg_image_uri", null) != null
        val tvBgInfo = TextView(this).apply {
            text = if (hasBg) getString(R.string.bg_set) else getString(R.string.bg_not_set)
            setTextColor(C["tertiary_light"]!!); textSize = 13f
        }
        val btnPickBg = Button(this).apply {
            text = if (hasBg) getString(R.string.bg_change) else getString(R.string.bg_pick)
            setTextColor(C["accent"]!!); background = null; textSize = 13f
        }
        val btnClearBg = Button(this).apply {
            text = getString(R.string.bg_clear)
            setTextColor(C["error"]!!); background = null; textSize = 13f
            visibility = if (hasBg) View.VISIBLE else View.GONE
        }
        val bgRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 0, 32, 8)
            addView(tvBgInfo, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(btnClearBg)
            addView(btnPickBg, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(8, 0, 0, 0) })
        }

        val curAlpha = prefs.getInt("bg_image_alpha", 20)
        val tvAlpha = TextView(act).apply {
            text = getString(R.string.bg_alpha, curAlpha); setTextColor(C["tertiary"]!!); textSize = 12f
            setPadding(40, 4, 32, 0)
        }
        val seekAlpha = SeekBar(act).apply {
            max = 100; progress = curAlpha
            setPadding(40, 0, 40, 8)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { tvAlpha.text = getString(R.string.bg_alpha, p) }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(act).apply {
                text = getString(R.string.settings_bg_image); setTextColor(C["tertiary_light"]!!); textSize = 12f
                setPadding(32, 12, 32, 0)
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
            val bmp = BitmapFactory.decodeStream(contentResolver.openInputStream(uri)) ?: return
            val root = findViewById<View>(R.id.root) ?: return
            val alpha = prefs.getInt("bg_image_alpha", 20).coerceIn(1, 100)
            root.post {
                val rw = root.width; val rh = root.height
                if (rw <= 0 || rh <= 0) return@post
                val bmpW = bmp.width; val bmpH = bmp.height
                val scale = maxOf(rw.toFloat() / bmpW, rh.toFloat() / bmpH)
                val sw = (bmpW * scale).toInt(); val sh = (bmpH * scale).toInt()
                val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, sw, sh, true)
                val x = maxOf((sw - rw) / 2, 0); val y = maxOf((sh - rh) / 2, 0)
                val cw = minOf(rw, sw); val ch = minOf(rh, sh)
                val cropped = android.graphics.Bitmap.createBitmap(scaled, x, y, cw, ch)
                val dr = android.graphics.drawable.BitmapDrawable(resources, cropped)
                dr.alpha = (alpha * 255 / 100).coerceIn(1, 255)
                root.background = dr
            }
            // Make surfaces transparent so the bg shows through everywhere
            findViewById<View>(R.id.toolbar)?.setBackgroundColor(0xBB000000.toInt())
            findViewById<View>(R.id.pathBar)?.setBackgroundColor(0xBB000000.toInt())
            findViewById<View>(R.id.panel)?.background = null
            // Match status bar to toolbar
            window?.statusBarColor = 0xBB000000.toInt()
        } catch (_: Exception) {}
    }
