package com.usefulunpacker

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 通用格式 / 目标选择器：分组 + 卡片式网格（默认每行 3 个）+ 可滚动。
 * 点击任意项即回调 onPick(key)。
 *
 * 视觉：每个可选项是一张圆角卡片（细边框 + 涟漪），组标题用 accent 小字，
 * 组间留白 —— 取代早前「一片无边框文字墙」的三列布局。
 *
 * `columns = 1` 退化为左对齐单列列表：合并目标选择器用它，因为那儿的标签是
 * 归档文件名，塞进三列只会被省略成看不懂的残字。
 */
fun showFormatPicker(
    activity: AppCompatActivity,
    title: String,
    groups: List<Pair<Int, List<String>>> = FORMAT_GROUPS,
    labels: Map<String, String> = FORMAT_LABELS,
    columns: Int = 3,
    onPick: (String) -> Unit,
) {
    val density = activity.resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density).toInt()
    var dialogRef: AlertDialog? = null

    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    groups.forEachIndexed { gi, (labelRes, fmts) ->
        root.addView(TextView(activity).apply {
            text = activity.getString(labelRes)
            setTextColor(C["accent"]!!)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(6), if (gi == 0) dp(4) else dp(18), dp(6), dp(6))
        })
        fmts.chunked(columns).forEach { rowItems ->
            val rowView = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            rowItems.forEach { fmt ->
                val label = labels[fmt] ?: fmt.uppercase()
                rowView.addView(
                    formatCell(activity, label, columns, density) {
                        dialogRef?.dismiss()
                        onPick(fmt)
                    },
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                        val m = dp(3)
                        marginStart = m; marginEnd = m; topMargin = m; bottomMargin = m
                    },
                )
            }
            // Fill the trailing empty slots so a short last row keeps its columns
            // aligned instead of stretching its cells across the width.
            repeat(columns - rowItems.size) {
                rowView.addView(View(activity), LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            root.addView(rowView, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    val scroller = ScrollView(activity).apply {
        addView(root)
        // Honor/EMUI NPEs drawing scrollbars on custom views — disable.
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }

    val dialog = AlertDialog.Builder(activity)
        .setTitle(title)
        .setView(scroller)
        .setNegativeButton(activity.getString(R.string.action_cancel), null)
        .create()
    dialogRef = dialog
    // Fit-to-content and bottom-anchored: a short list hugs its own height, a
    // long one is capped below the tab strip and scrolls (see belowTabBar).
    dialog.belowTabs(fitContent = true)
    // belowTabs paints the window with a *translucent* scrim (its trick for
    // darkening the backdrop without dimming the tab strip). That reads as a
    // see-through sheet here — the file list showed through the card gaps and
    // the title bar. Restore the theme's solid rounded panel so the picker is
    // fully opaque.
    androidx.appcompat.content.res.AppCompatResources
        .getDrawable(activity, R.drawable.dialog_bg_rounded)
        ?.let { dialog.window?.setBackgroundDrawable(it) }
    dialog.show()
    // belowTabs rooted the picker to the tab that opened it: switching tabs
    // hides it and coming back restores it, so a choice can never land on a
    // window it wasn't opened for ("不能跨 tab").
}

/** One selectable card. Grid mode: centered, up to 2 lines. List mode: a single
 *  left-aligned ellipsized line. */
private fun formatCell(
    activity: AppCompatActivity,
    label: String,
    columns: Int,
    density: Float,
    onClick: () -> Unit,
): TextView {
    val content = GradientDrawable().apply {
        setColor(C["surface_raised"]!!)
        cornerRadius = 10f * density
        setStroke((1f * density).toInt(), C["divider_subtle"]!!)
    }
    val bg = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null)
    fun dp(v: Int): Int = (v * density).toInt()
    return TextView(activity).apply {
        text = label
        setTextColor(C["primary"]!!)
        background = bg
        isClickable = true
        setOnClickListener { onClick() }
        if (columns == 1) {
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            minHeight = dp(44)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        } else {
            textSize = 12.5f
            maxLines = 2
            minLines = 2
            gravity = Gravity.CENTER
            minHeight = dp(54)
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
    }
}
