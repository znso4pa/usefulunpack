package com.usefulunpacker

import android.app.AlertDialog
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 归档模式格式选择器：分组 + 可滚动，避免格式一多窗口太大。
 * 点击任意格式即回调 onPick(fmt key)。
 */
fun showFormatPicker(
    activity: AppCompatActivity,
    title: String,
    groups: List<Pair<Int, List<String>>> = FORMAT_GROUPS,
    labels: Map<String, String> = FORMAT_LABELS,
    onPick: (String) -> Unit,
) {
    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(8, 4, 8, 4)
    }

    // ripple background for rows (selectableItemBackground)
    val ripple = android.util.TypedValue()
    activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)

    var dialogRef: AlertDialog? = null

    groups.forEach { (labelRes, fmts) ->
        root.addView(TextView(activity).apply {
            text = activity.getString(labelRes)
            setTextColor(C["accent"]!!)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(16, 14, 16, 4)
        })
        fmts.forEach { fmt ->
            val label = labels[fmt] ?: fmt.uppercase()
            root.addView(TextView(activity).apply {
                text = label
                textSize = 15f
                setTextColor(C["primary"]!!)
                setPadding(20, 14, 16, 14)
                gravity = Gravity.CENTER_VERTICAL
                if (ripple.resourceId != 0) setBackgroundResource(ripple.resourceId)
                setOnClickListener {
                    dialogRef?.dismiss()
                    onPick(fmt)
                }
            })
        }
    }

    val scroller = ScrollView(activity).apply { addView(root) }

    val dialog = AlertDialog.Builder(activity)
        .setTitle(title)
        .setView(scroller)
        .setNegativeButton(activity.getString(R.string.action_cancel), null)
        .create()
    dialogRef = dialog
    dialog.show()
}
