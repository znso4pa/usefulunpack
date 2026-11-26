package com.usefulunpacker

import android.view.View
import android.widget.*
internal fun MainActivity.showGeneralSettings() {
        val langTags = arrayOf("zh-CN", "zh-TW", "ja", "en")
        val langLabels = arrayOf(getString(R.string.language_zhcn), getString(R.string.language_zhtw), getString(R.string.language_ja), getString(R.string.language_en))
        val langCurrent = prefs.getString("app_lang", "zh-CN") ?: "zh-CN"
        val textEncCurrent = prefs.getString("text_encoding", "UTF-8") ?: "UTF-8"
        var zipEncCurrent = prefs.getString("zip_encoding", "UTF-8") ?: "UTF-8"
        val zipEncLabels = arrayOf(getString(R.string.encoding_utf8), getString(R.string.encoding_sjis), getString(R.string.encoding_gbk))
        val ZIP_ENC_VALS = arrayOf("UTF-8", "SHIFT-JIS", "GBK")

        fun row(title: String, value: String, onClick: () -> Unit) = UuActionRow(this).apply {
            bind(title, value, navigation = true, onClick = onClick)
        }
        fun divider() = View(this).apply {
            setBackgroundColor(C["divider_subtle"]!!)
            layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { setMargins(resources.getDimensionPixelSize(R.dimen.space_lg), 0, resources.getDimensionPixelSize(R.dimen.space_lg), 0) }
        }
        fun pick(title: String, labels: Array<String>, vals: Array<String>, current: String, onPick: (String) -> Unit) {
            com.usefulunpacker.UuDialogBuilder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, vals.indexOf(current).coerceAtLeast(0)) { d, which ->
                    onPick(vals[which])
                    d.dismiss()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show().also { keepTabBarTappable(it) }
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C["surface"]!!)
            addView(row(
                getString(R.string.settings_language),
                langLabels[langTags.indexOf(langCurrent).coerceAtLeast(0)],
            ) {
                com.usefulunpacker.UuDialogBuilder(this@showGeneralSettings)
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
                    .setNegativeButton(R.string.action_cancel, null)
                    .show().also { keepTabBarTappable(it) }
            })
            addView(divider())
            addView(textEncodingRow(this@showGeneralSettings, textEncCurrent) { value ->
                prefs.edit().putString("text_encoding", value).apply()
                toast(getString(R.string.msg_encoding_applied, value))
            })
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
                com.usefulunpacker.UuDialogBuilder(this@showGeneralSettings)
                    .setTitle(getString(R.string.settings_parallel_threads))
                    .setSingleChoiceItems(parallelLabels, parallelVals.indexOf(parallelThreads).coerceAtLeast(0)) { d, w ->
                        parallelThreads = parallelVals[w]
                        prefs.edit().putInt("parallel_threads", parallelThreads).apply()
                        RarCore.setParallelThreads(parallelThreads)
                        ZipCore.setParallelThreads(parallelThreads)
                        parallelRow.text = getString(R.string.settings_parallel_threads) + "\n" + parallelLabels[w]
                        d.dismiss()
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show().also { keepTabBarTappable(it) }
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
                com.usefulunpacker.UuDialogBuilder(this@showGeneralSettings)
                    .setTitle(getString(R.string.settings_sort))
                    .setSingleChoiceItems(sortDisplayLabels, sortVals.indexOf(sortMode).coerceAtLeast(0)) { d, w ->
                        sortMode = sortVals[w]
                        prefs.edit().putString("sort_mode", sortMode).apply()
                        sortRow.text = getString(R.string.settings_sort) + "\n" + sortDisplayLabels[w]
                        nav(currentDir)
                        d.dismiss()
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show().also { keepTabBarTappable(it) }
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
                com.usefulunpacker.UuDialogBuilder(this@showGeneralSettings)
                    .setTitle(getString(R.string.settings_picker_mode))
                    .setSingleChoiceItems(pickerLabels, pickerVals.indexOf(pickerMode).coerceAtLeast(0)) { d, w ->
                        pickerMode = pickerVals[w]
                        prefs.edit().putString("picker_mode", pickerMode).apply()
                        pickerRow.text = getString(R.string.settings_picker_mode) + "\n" + pickerLabels[w]
                        d.dismiss()
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show().also { keepTabBarTappable(it) }
            }
            addView(pickerRow)
            addView(divider())
            // Live memory badge toggle: applies immediately so the overlay can
            // appear/disappear while the dialog stays open.
            val memSwitch = com.usefulunpacker.UuSwitch(this@showGeneralSettings).apply {
                isChecked = prefs.getBoolean("show_mem", false)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean("show_mem", checked).apply()
                    applyMemoryBadge()
                }
            }
            addView(UuToggleRow(this@showGeneralSettings).apply { bind(getString(R.string.settings_show_memory), memSwitch) })
            addView(divider())
            addView(UuToggleRow(this@showGeneralSettings).apply {
                bind(getString(R.string.restore_session), UuSwitch(context).apply {
                    isChecked = prefs.getBoolean("restore_session", false)
                    setOnCheckedChangeListener { _, checked ->
                        prefs.edit().putBoolean("restore_session", checked).apply()
                        if (checked) saveSession()
                    }
                })
            })
            addView(TextView(context).apply {
                UuMetrics.text(this)
                text = getString(R.string.restore_session_desc)
            })
        }
        com.usefulunpacker.UuDialogBuilder(this)
            .setTitle(getString(R.string.settings_general))
            .setView(com.usefulunpacker.UuScrollView(this).apply { addView(body) })
            .setPositiveButton(getString(R.string.action_close), null)
            .show().also { keepTabBarTappable(it) }
    }
