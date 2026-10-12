package com.usefulunpacker

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * UI 小构件库（UI 架构评价 P4）。
 *
 * 原先这些 helper 全是 `browse/HomeHub.kt` 里的 `private fun homeXxx`，而且每个函数里都
 * 复制了一遍 `fun d(v: Int) = (v * density).toInt()`（**8 份**）。这里把它们形式化成
 * 通用构件：**HomeHub 先迁**，`PreviewFlow` / `TerminalPanel` / `AppSettings` 三个大文件
 * 渐进 adopt（它们体量大，一次全迁风险不成比例）。
 *
 * 命名：`textView` / `iconView` 带后缀，避开调用处常见的局部变量名（`text` / `icon`）。
 */

/** dp → px。收敛原先散在 8 个函数里的 `fun d(v: Int)`。 */
internal fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** 圆角实底 + 可选发丝描边（[strokePx] 为 0 时不描边）。 */
internal fun roundedBg(cornerPx: Int, fill: Int, strokePx: Int = 0, stroke: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        cornerRadius = cornerPx.toFloat()
        setColor(fill)
        if (strokePx > 0) setStroke(strokePx, stroke)
    }

/** 行 / 卡片的按压涟漪。 */
internal fun ripple(color: Int): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(color), null, null)

/** 文本。[bold] 走 `Typeface.BOLD`。 */
internal fun textView(ctx: Context, s: CharSequence, sp: Float, color: Int, bold: Boolean = false): TextView =
    TextView(ctx).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

/** 主文字（token `primary`）。 */
internal fun primaryText(ctx: Context, s: CharSequence, sp: Float, bold: Boolean = false): TextView =
    textView(ctx, s, sp, C["primary"]!!, bold)

/** 次文字（token `secondary`）。 */
internal fun secondaryText(ctx: Context, s: CharSequence, sp: Float): TextView =
    textView(ctx, s, sp, C["secondary"]!!)

/**
 * 装饰性图标：意义由旁边的文字（或所在卡片的整体描述，见 [mergeIntoA11yNode]）承担，
 * 所以标 `importantForAccessibility = NO` —— 别让 TalkBack 单独停在它上面。
 */
internal fun iconView(ctx: Context, res: Int, sizePx: Int, tint: Int): ImageView =
    ImageView(ctx).apply {
        setImageResource(res)
        setColorFilter(tint)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
    }

/**
 * 一行 n 个等重子项（最后一个不加间距）。
 *
 * ⚠️ [gap] 的处理**照搬**旧 `HomeHub.homeRow`，包括它那个**双重换算**：内部再 `ctx.dp(gap)`
 * 一次，而调用处传进来的 `act.dp(18)` 本身已经是像素 → 实际间距是 `dp(dp(18))`（≈49dp，
 * 不是 18dp）。这是**既存行为**（疑似笔误），P4 是重构**先原样保留、不趁机改版式**；
 * 存疑点已记在 TODO.md。
 */
internal fun equalRow(ctx: Context, vararg cells: View, gap: Int): LinearLayout {
    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    cells.forEachIndexed { i, v ->
        val lp = LinearLayout.LayoutParams(0, MATCH, 1f)
        if (i < cells.size - 1) lp.marginEnd = ctx.dp(gap)
        row.addView(v, lp)
    }
    return row
}

/**
 * 把一棵子树收成**一个** TalkBack 节点：根设 [desc]，所有后代标为「对无障碍不重要」。
 *
 * 卡片是「图标盒 + 标题 + 描述（+ 角标）」拼出来的、整体是一个可点区域 —— 逐个子 View 读
 * 会变成一串碎片（「浏览」「打开文件浏览器」「文件夹图标」…）。整卡只做一件事，就该被读成一句。
 */
internal fun View.mergeIntoA11yNode(desc: CharSequence) {
    contentDescription = desc
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    fun mark(v: View) {
        if (v is ViewGroup) for (i in 0 until v.childCount) mark(v.getChildAt(i))
        if (v !== this) v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    mark(this)
}

/**
 * 「标题 …… 尾注」的区块头。字号由调用处给（宽屏 / 手机两套骨架不一样，别写死在里面）。
 *
 * ⚠️ 这里**不放纵向 margin**：`LinearLayout` 第一遍测量不计子项的 `marginBottom`，权重卡片会把
 * 那 10dp 也吃掉、自己长高 10dp 并被裁。标题与卡片的间距改由**卡片自己的 `topMargin`** 出
 * （权重子项的 margin 是计入的）。
 */
internal fun sectionCap(
    ctx: Context,
    title: String,
    tail: String?,
    titleSp: Float,
    tailSp: Float,
): LinearLayout {
    val cap = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.BOTTOM
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            .apply { setMargins(ctx.dp(2), 0, ctx.dp(2), 0) }
    }
    cap.addView(textView(ctx, title, titleSp, C["tertiary"]!!).apply { letterSpacing = 0.12f })
    if (tail != null) {
        cap.addView(textView(ctx, tail, tailSp, C["tertiary"]!!).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = ctx.dp(10) }
            gravity = Gravity.END
        })
    }
    return cap
}

/**
 * hero 卡：**横向内容** —— 图标盒在左、标题+描述居中、角标在右。
 *
 * 两张卡并排时单卡在宽屏约 560dp、手机竖屏约 380dp，纵向内容（图标在上）会让右侧空掉一大片，
 * 所以统一横向。卡高由外部决定：宽屏 `weight=1` 撑满、手机竖屏 `wrap_content`。
 */
internal fun heroCard(
    ctx: Context,
    iconRes: Int,
    title: String,
    desc: String,
    tag: String?,
    teal: Boolean,
    fallbackBg: Int,
    roomy: Boolean,
    onClick: () -> Unit,
): View {
    fun d(v: Int) = ctx.dp(v)
    val bg = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        if (teal) intArrayOf(C["hero_grad_teal"]!!, fallbackBg)
        else intArrayOf(C["hero_grad_plain"]!!, fallbackBg),
    ).apply {
        cornerRadius = d(if (roomy) 20 else 17).toFloat()
    }
    val box = if (roomy) d(66) else d(46)
    val glyph = if (roomy) d(34) else d(24)

    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (roomy) setPadding(d(30), 0, d(30), 0) else setPadding(d(14), d(13), d(12), d(13))
    }
    row.addView(iconView(ctx, iconRes, glyph, C["accent"]!!).apply {
        background = GradientDrawable().apply {
            cornerRadius = d(if (roomy) 18 else 13).toFloat(); setColor(C["accent_soft"]!!)
        }
        layoutParams = LinearLayout.LayoutParams(box, box)
    })
    val textCol = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = d(if (roomy) 22 else 14) }
    }
    textCol.addView(primaryText(ctx, title, if (roomy) 20f else 16.5f, bold = true))
    textCol.addView(secondaryText(ctx, desc, if (roomy) 13.5f else 12f).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = d(if (roomy) 9 else 5) }
        // 保险：极端窄屏也不让描述把卡片撑成 5 行（横向内容通常 1–2 行）。
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    })
    row.addView(textCol)
    if (tag != null) {
        row.addView(textView(ctx, tag, if (roomy) 11f else 10f, C["accent"]!!).apply {
            background = GradientDrawable().apply {
                cornerRadius = d(99).toFloat(); setColor(C["accent_soft"]!!)
            }
            setPadding(d(if (roomy) 11 else 9), d(5), d(if (roomy) 11 else 9), d(5))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = d(14) }
        })
    }

    return FrameLayout(ctx).apply {
        background = bg
        foreground = ripple(C["ripple"]!!)
        isClickable = true
        isFocusable = true
        layoutParams = FrameLayout.LayoutParams(MATCH, WRAP)
        addView(row)
        // 整卡 = 一个 a11y 单元（图标 / 标题 / 描述 / 角标合成一句）。
        mergeIntoA11yNode(if (tag != null) "$title，$desc，$tag" else "$title，$desc")
        setOnClickListener { onClick() }
    }
}

/** 快捷入口小卡：图标盒 + 标题 / 一行说明。 */
internal fun miniCard(
    ctx: Context,
    iconRes: Int,
    title: String,
    sub: String,
    bg: Int,
    roomy: Boolean,
    onClick: () -> Unit,
): LinearLayout {
    fun d(v: Int) = ctx.dp(v)
    return LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (roomy) setPadding(d(16), d(18), d(16), d(18)) else setPadding(d(11), d(11), d(11), d(11))
        background = roundedBg(d(if (roomy) 16 else 14), bg, d(1), C["hairline"]!!)
        foreground = ripple(C["ripple"]!!)
        isClickable = true
        isFocusable = true
        layoutParams = FrameLayout.LayoutParams(MATCH, WRAP)
        addView(iconView(ctx, iconRes, d(if (roomy) 22 else 18), C["accent"]!!).apply {
            background = GradientDrawable().apply {
                cornerRadius = d(if (roomy) 13 else 11).toFloat(); setColor(C["accent_soft"]!!)
            }
            layoutParams = LinearLayout.LayoutParams(d(if (roomy) 44 else 36), d(if (roomy) 44 else 36))
                .apply { gravity = Gravity.CENTER }
        })
        val names = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        names.addView(primaryText(ctx, title, if (roomy) 14.5f else 13f, bold = true))
        names.addView(textView(ctx, sub, if (roomy) 11f else 10f, C["tertiary"]!!).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = d(2) }
            setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        addView(names, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = d(if (roomy) 14 else 11) })
        // 整卡 = 一个 a11y 单元（图标 / 标题 / 说明合成一句）。
        mergeIntoA11yNode("$title，$sub")
        setOnClickListener { onClick() }
    }
}
