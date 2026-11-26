package com.usefulunpacker

import android.widget.*
import com.usefulunpacker.featureThread as thread
internal fun MainActivity.showRecycleBinSettings() {
    val recycleEnabled = com.usefulunpacker.fileops.RecycleBin.isEnabled(prefs)
    var autoCleanDays = com.usefulunpacker.fileops.RecycleBin.autoCleanDays(prefs)

    val switchEnabled = com.usefulunpacker.UuSwitch(this).apply {
        isChecked = recycleEnabled
    }

    val tvInfo = TextView(this).apply {
        // Filled in by the background stat pass below (walking a large bin
        // synchronously would freeze this dialog).
        text = getString(R.string.recycle_loading)
        setTextColor(C["tertiary_light"]!!)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_sm))
        setPadding(0, resources.getDimensionPixelSize(R.dimen.space_sm), 0, 0)
    }

    val tvAutoClean = UuActionRow(this)
    fun bindAutoClean() {
        val value = if (autoCleanDays == 0) getString(R.string.recycle_auto_clean_never)
            else getString(R.string.recycle_days_format, autoCleanDays)
        tvAutoClean.bind(getString(R.string.recycle_auto_clean), value, navigation = true) {
            val daysOptions = intArrayOf(7, 14, 30, 60, 90, 0)
            val daysLabels = daysOptions.map {
                if (it == 0) getString(R.string.recycle_auto_clean_never) else getString(R.string.recycle_days_format, it)
            }.toTypedArray()
            UuDialogBuilder(this).setTitle(R.string.recycle_auto_clean)
                .setSingleChoiceItems(daysLabels, daysOptions.indexOf(autoCleanDays).coerceAtLeast(0)) { _, w ->
                    autoCleanDays = daysOptions[w]
                    bindAutoClean()
                }
                .setPositiveButton(R.string.action_confirm, null)
                .show().also { keepTabBarTappable(it) }
        }
    }
    bindAutoClean()

    val btnEmpty = com.usefulunpacker.UuButton(this).apply {
        actionStyle = ActionStyle.DANGER
        text = getString(R.string.recycle_empty_now)
        isEnabled = false  // enabled once the async stat pass finds entries
    }

    // Stat pass off the main thread: count + size walk the whole bin.
    thread(start = true) {
        val itemCount = com.usefulunpacker.fileops.RecycleBin.getItemCount(this)
        val usedSize = com.usefulunpacker.fileops.RecycleBin.getUsedSize(this)
        runFeatureOnUiThread {
            if (isFinishing || isDestroyed) return@runFeatureOnUiThread
            tvInfo.text = getString(R.string.recycle_items, itemCount, fmt(usedSize))
            btnEmpty.isEnabled = itemCount > 0
        }
    }

    val act = this
    val container = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(resources.getDimensionPixelSize(R.dimen.space_lg), resources.getDimensionPixelSize(R.dimen.space_lg), resources.getDimensionPixelSize(R.dimen.space_lg), resources.getDimensionPixelSize(R.dimen.space_lg))
        addView(UuToggleRow(act).apply { bind(getString(R.string.recycle_enable), switchEnabled) })
        addView(tvAutoClean)
        addView(tvInfo)
        addView(btnEmpty)
    }

    btnEmpty.setOnClickListener {
        com.usefulunpacker.UuDialogBuilder(this)
            .setTitle(getString(R.string.recycle_empty_now))
            .setMessage(getString(R.string.recycle_empty_confirm))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                // deleteRecursively over a big bin — offload it; the dialog
                // closes instantly and the refresh happens when done.
                thread(start = true) {
                    val emptied = com.usefulunpacker.fileops.RecycleBin.emptyRecycleBin(this)
                    runFeatureOnUiThread {
                        if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                        if (emptied) toast(getString(R.string.recycle_empty))
                        else failureNotice(getString(R.string.err_file_error))
                        nav(currentDir)
                        showRecycleBinSettings()
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show().also { keepTabBarTappable(it) }
    }

    com.usefulunpacker.UuDialogBuilder(this)
        .setTitle(getString(R.string.title_recycle_bin))
        .setView(com.usefulunpacker.UuScrollView(this).apply { addView(container) })
        .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
            prefs.edit().putBoolean("recycle_bin_enabled", switchEnabled.isChecked).apply()
            prefs.edit().putInt("recycle_bin_auto_clean_days", autoCleanDays).apply()
        }
        .setNegativeButton(R.string.action_cancel, null)
        .show().also { keepTabBarTappable(it) }
}
