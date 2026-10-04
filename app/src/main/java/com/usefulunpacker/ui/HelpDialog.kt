package com.usefulunpacker

import android.app.AlertDialog
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * In-app usage documentation: a scrollable dialog with two sections
 * (supported formats + feature tutorials), populated from string-array
 * resources so all 4 languages are covered. Each entry's first line is the
 * bold title; the remaining lines are the body.
 */
internal fun MainActivity.showHelpDialog() {
    val act = this

    val sp = { id: Int -> resources.getDimension(id) / resources.displayMetrics.scaledDensity }
    val pd = { id: Int -> resources.getDimensionPixelSize(id) }

    fun sectionHeader(text: String): View = TextView(this).apply {
        this.text = text
        setTextColor(C["accent"]!!)
        textSize = sp(R.dimen.text_lg)
        setTypeface(null, Typeface.BOLD)
        setPadding(pd(R.dimen.space_lg), pd(R.dimen.space_lg), pd(R.dimen.space_lg), pd(R.dimen.space_md))
    }

    fun entry(title: String, body: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pd(R.dimen.space_lg), pd(R.dimen.space_md), pd(R.dimen.space_lg), pd(R.dimen.space_md))
        if (title.isNotEmpty()) addView(TextView(act).apply {
            text = title
            setTextColor(C["accent"]!!)
            textSize = sp(R.dimen.text_lg)
            setTypeface(null, Typeface.BOLD)
            setLineSpacing(0f, 1.15f)
        })
        if (body.isNotEmpty()) addView(TextView(act).apply {
            text = body
            setTextColor(C["secondary"]!!)
            textSize = sp(R.dimen.text_sm)
            setTypeface(null, Typeface.NORMAL)
            setLineSpacing(0f, 1.35f)
            setPadding(0, pd(R.dimen.space_xs), 0, 0)
        })
    }

    fun divider(): View = View(this).apply {
        setBackgroundColor(C["divider_subtle"]!!)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
            setMargins(pd(R.dimen.space_lg), pd(R.dimen.space_md), pd(R.dimen.space_lg), pd(R.dimen.space_md))
        }
    }

    fun parseItem(raw: String): Pair<String, String> {
        val idx = raw.indexOf('\n')
        if (idx >= 0) return raw.substring(0, idx) to raw.substring(idx + 1)
        // No newline (AAPT2 would have folded a literal one into a space):
        // fall back to splitting at the first " — "/" - " separator so a
        // title/body entry still renders with a distinct heading instead of
        // the whole text becoming one bold title.
        val sep = raw.indexOf(" — ")
        if (sep >= 0) return raw.substring(0, sep) to raw.substring(sep + 3)
        val hyphen = raw.indexOf(" - ")
        if (hyphen >= 0) return raw.substring(0, hyphen) to raw.substring(hyphen + 3)
        return raw to ""
    }

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    root.addView(sectionHeader(getString(R.string.help_section_formats)))
    val formats = resources.getStringArray(R.array.help_formats)
    formats.forEachIndexed { i, raw ->
        val (title, body) = parseItem(raw)
        root.addView(entry(title, body))
        if (i < formats.size - 1) root.addView(divider())
    }

    root.addView(sectionHeader(getString(R.string.help_section_tutorials)))
    val tutorials = resources.getStringArray(R.array.help_tutorials)
    tutorials.forEachIndexed { i, raw ->
        val (title, body) = parseItem(raw)
        root.addView(entry(title, body))
        if (i < tutorials.size - 1) root.addView(divider())
    }

    val scroller = ScrollView(this).apply {
        addView(root)
        // Honor/EMUI NPEs drawing scrollbars on custom views — disable.
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }

    val dlg = AlertDialog.Builder(this)
        .setTitle(getString(R.string.settings_help))
        .setView(scroller)
        .setNegativeButton(getString(R.string.action_close), null)
        .create()
    // Tall help viewer (capped ~0.88h) — bottom-anchor below the tab strip.
    // App-global: keep it visible across tab switches (rootToTab = false).
    dlg.belowTabs(rootToTab = false)
    dlg.show()
}
