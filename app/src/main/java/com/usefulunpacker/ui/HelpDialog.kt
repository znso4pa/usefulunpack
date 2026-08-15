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

    fun sectionHeader(text: String): View = TextView(this).apply {
        this.text = text
        setTextColor(C["accent"]!!)
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
        setPadding(20, 18, 20, 6)
    }

    fun entry(title: String, body: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20, 8, 20, 8)
        if (title.isNotEmpty()) addView(TextView(act).apply {
            text = title
            setTextColor(C["primary"]!!)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        })
        if (body.isNotEmpty()) addView(TextView(act).apply {
            text = body
            setTextColor(C["secondary"]!!)
            textSize = 12f
            setPadding(0, 2, 0, 0)
        })
    }

    fun divider(): View = View(this).apply {
        setBackgroundColor(C["divider_subtle"]!!)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
            setMargins(20, 4, 20, 4)
        }
    }

    fun parseItem(raw: String): Pair<String, String> {
        val idx = raw.indexOf('\n')
        return if (idx < 0) raw to ""
        else raw.substring(0, idx) to raw.substring(idx + 1)
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
    val metrics = this.resources.displayMetrics
    dlg.window?.setLayout((metrics.widthPixels * 0.92).toInt(), (metrics.heightPixels * 0.88).toInt())
    dlg.show()
}
