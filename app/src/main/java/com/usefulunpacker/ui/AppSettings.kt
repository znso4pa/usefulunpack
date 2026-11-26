package com.usefulunpacker

import android.app.AlertDialog
import android.widget.*

internal fun MainActivity.settings() {
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val actions = listOf<Pair<Int, () -> Unit>>(
        R.string.settings_ui to { showUISettings() },
        R.string.settings_general to { showGeneralSettings() },
        R.string.settings_compress to { showCompressionSettings() },
        R.string.disk_usage to { showFolderPicker(this, android.os.Environment.getExternalStorageDirectory()) { showDiskUsage(it) } },
        R.string.title_recycle_bin to { showRecycleBinSettings() },
        R.string.task_center_title to { showTaskCenter() },
        R.string.diagnostics_title to { showDiagnostics() },
        R.string.components_title to { showComponentCatalog() },
        R.string.mcp_title to { showLocalMcp() },
        R.string.settings_help to { showHelpDialog() },
        R.string.settings_disclaimer to { showDisclaimer(fromSettings = true) }
    )
    lateinit var dialog: android.app.AlertDialog
    actions.forEach { (label, action) -> body.addView(UuActionRow(this).apply {
        bind(getString(label), navigation = true, onClick = { dialog.dismiss(); action() })
    }) }
    val scroll = com.usefulunpacker.UuScrollView(this).apply {
        isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false; addView(body)
    }
    dialog = UuDialogBuilder(this).setTitle(getString(R.string.settings)).setView(scroll)
        .setNegativeButton(R.string.action_close, null).create()
    dialog.show()
    dialog.belowTabs(fitContent = true)
}
