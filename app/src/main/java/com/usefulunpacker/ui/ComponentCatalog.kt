package com.usefulunpacker

import android.widget.LinearLayout
import android.widget.ScrollView

/** Real shared controls, reachable from settings for device QA in every locale and orientation. */
internal fun MainActivity.showComponentCatalog() {
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    for ((label, style) in listOf(
        R.string.components_primary to ActionStyle.PRIMARY,
        R.string.components_secondary to ActionStyle.SECONDARY,
        R.string.components_danger to ActionStyle.DANGER,
        R.string.components_disabled to ActionStyle.SECONDARY,
    )) body.addView(UuButton(this).apply {
        text = getString(label); actionStyle = style
        isEnabled = label != R.string.components_disabled
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
    })
    body.addView(UuTextField(this).apply { hint = getString(R.string.components_input) })
    body.addView(UuToggleRow(this).apply {
        bind(getString(R.string.components_switch), UuSwitch(this@showComponentCatalog))
    })
    body.addView(UuCheckBox(this).apply { text = getString(R.string.components_action); isChecked = true })
    body.addView(UuRadioButton(this).apply { text = getString(R.string.components_action); isChecked = true })
    body.addView(UuSlider(this).apply { contentDescription = getString(R.string.components_slider); max = 100; progress = 40 })
    body.addView(UuActionBar(this).apply {
        addView(UuIconButton(this@showComponentCatalog).apply {
            setImageResource(R.drawable.ic_search)
            contentDescription = getString(R.string.msg_search_global)
        })
        addView(UuColorButton(this@showComponentCatalog).apply { color = getColor(R.color.accent) })
    })
    body.addView(UuChoiceCell(this).apply { bind(getString(R.string.components_primary), selected = true) {} })
    body.addView(UuChoiceCell(this).apply { bind(getString(R.string.components_disabled), available = false) {} })
    body.addView(UuActionRow(this).apply { bind(getString(R.string.components_navigation), getString(R.string.settings_general), navigation = true) { showGeneralSettings() } })
    body.addView(UuActionRow(this).apply { bind(getString(R.string.components_action), getString(R.string.diagnostics_title)) { showDiagnostics() } })
    val scroll = com.usefulunpacker.UuScrollView(this).apply { isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false; addView(body) }
    UuDialogBuilder(this).setTitle(R.string.components_title).setView(scroll)
        .setNegativeButton(R.string.action_close, null).show().also { it.belowTabs(fitContent = true) }
}
