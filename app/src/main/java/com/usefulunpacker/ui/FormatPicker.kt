package com.usefulunpacker

import android.app.AlertDialog
import android.graphics.Typeface
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
                    formatCell(activity, label, columns) {
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

    val scroller = com.usefulunpacker.UuScrollView(activity).apply {
        addView(root)
        // Honor/EMUI NPEs drawing scrollbars on custom views — disable.
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }

    val dialog = activity.buildFormatPickerDialog(title, scroller)
    dialogRef = dialog
}

/**
 * 格式选择器的统一外壳：贴内容高度、底边锚定，并把窗口背景换成不透明圆角面板。
 *
 * - `belowTabs(fitContent = true)`：短列表贴自身高度，长列表被压在 tab 条之下滚动
 *   （见 `belowTabBar`）；同时把选择器**拴在打开它的那个 tab 上** —— 切 tab 会隐藏、
 *   切回来恢复，所以选择永远不会落到不是为它打开的那个窗口（「不能跨 tab」）。
 * - `belowTabs` 给窗口铺的是**半透明**遮罩（它压暗背景却不压暗 tab 条的诀窍）。
 *   在这里读起来像一张透明卡片：文件列表从卡片缝和标题栏里透出来。所以换回主题的
 *   实心圆角面板，保证选择器完全不透明。
 *
 * 双栏的音视频选择器与分组网格共用这一套 —— 两处各写一份必然漂移。
 */
internal fun AppCompatActivity.buildFormatPickerDialog(title: String, content: View): AlertDialog {
    val dialog = com.usefulunpacker.UuDialogBuilder(this)
        .setTitle(title)
        .setView(content)
        .setNegativeButton(R.string.action_cancel, null)
        .create()
    dialog.belowTabs(fitContent = true)
    androidx.appcompat.content.res.AppCompatResources
        .getDrawable(this, R.drawable.dialog_bg_rounded)
        ?.let { dialog.window?.setBackgroundDrawable(it) }
    dialog.show()
    return dialog
}

/** Compatibility adapter: all choice geometry and selected/disabled states live in UuChoiceCell. */
internal fun formatCell(
    activity: AppCompatActivity,
    label: String,
    columns: Int,
    available: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
): UuChoiceCell = UuChoiceCell(activity).apply {
    bind(label, listMode = columns == 1, available = available, selected = selected, onClick = onClick)
}
