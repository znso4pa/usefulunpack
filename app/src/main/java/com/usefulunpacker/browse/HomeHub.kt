package com.usefulunpacker

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.usefulunpacker.fileops.RecentsStore
import com.usefulunpacker.fileops.RecycleBin
import com.usefulunpacker.fileops.showRecycleBinDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-tab home overlay: fixed outer panel, with independently scrolling recent/bookmark lists.
 * Portrait keeps one row per list, compact action cards and two hero cards sharing spare height.
 * Landscape keeps the two-column layout. Home visibility is derived from TabState and applyLayer.
 * Wallpaper comes from the Activity root; the file/preview layers underneath must stay hidden.
 */
/** 切到主界面。视图未绑时（启动期 addTab）只记状态，fragment 建视图时按它渲染。 */
internal fun MainActivity.showHome(tab: TabState) {
    tab.homeVisible = true
    if (!tab.viewsBound) return
    val root = tab.homeRoot ?: return
    buildHomeCards(this, root.findViewById(R.id.homeGrid), tab)
    // 唯一改可见性的地方：homeRoot 亮、列表/预览/路径栏按层收起。
    // （以前这里手写 6 行 setVisibility，任何新路径漏一行就漏光 —— 见 TabLayers.kt）
    applyLayer(tab)
    // 不动 previewActive：hub 只是盖在上面，重建视图时预览还能恢复；
    // 真正进文件浏览器（enterBrowser → navTab）才把它作废。
}

/** 进文件浏览器（当前目录）。 */
internal fun MainActivity.enterBrowser(tab: TabState) = enterBrowserAt(tab, tab.currentDir)

/** 进文件浏览器并直接落在 [dir]（hub 的「存储根目录」卡 / 最近书签用）。 */
internal fun MainActivity.enterBrowserAt(tab: TabState, dir: File) {
    tab.homeVisible = false
    // navTab 内部会清预览态并 applyLayer；这里再补一次，覆盖
    // 「viewsBound=false 时 navTab 提前返回」的情形（状态已改，视图等绑定时再配平）。
    navTab(tab, dir)
    applyLayer(tab)
}

// ── 卡片构建 ─────────────────────────────────────────────────────────

/**
 * 走「宽屏」那套的条件：**横屏** 且宽度 ≥ 600dp。
 *
 * | 场景 | 判定 | 用哪套 |
 * |---|---|---|
 * | 平板 / 手机**横屏** | 宽 ≥ 600dp | 宽屏（双栏 + 快捷 1×4） |
 * | 平板**竖屏**（如本机 812dp 宽） | 竖屏 | **手机那套**（hero 上下两张、快捷 2×2、两个列表各占一整行） |
 * | 手机竖屏 | 宽 < 600dp | 手机那套 |
 *
 * ⚠️ **竖屏一律走手机那套，不能只看宽度**：只按 `screenWidthDp >= 600` 判，平板竖屏
 * （812dp）会被判成宽屏，竖屏就变成横屏那个骨架 —— 用户明确否掉过（「竖屏不是按照你之前
 * 给的 html 走的吗」）。手机横屏宽度通常 640–900dp，所以仍要保留宽度这一条，不然手机横屏
 * 会被判成窄屏、双栏被压成上下堆叠（`is_wide`/`sw600dp` 那个老判据就是这个毛病）。
 *
 * ⚠️ **磁盘查询对话框用的是同一个判据**（`ui/DiskUsageDialog.kt`）：两处一旦不一致，
 * 就会出现「主页是宽屏骨架、点开对话框却是竖屏骨架」的错位。所以它是 `internal` 而不是
 * `private`，别再复制一份。
 */
internal fun isRoomy(act: MainActivity): Boolean {
    val cfg = act.resources.configuration
    return cfg.screenWidthDp >= 560 &&
        cfg.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
}

/** 列表卡底部渐隐的最大高度（dp）。 */
private const val LIST_FADE_H = 18

/** One shared minimum height keeps a list viewport and each of its rows aligned. */
private fun homeListRowHeight(act: MainActivity) = act.resources.getDimensionPixelSize(R.dimen.home_list_row_height)

/** 无壁纸时的页面实底 #303030（= `homeRoot` 的 XML 底 `bg_file_list`，同色所以不闪）。 */
private fun solidHubBg() = GradientDrawable().apply { setColor(C["surface"]!!) }

/**
 * hub 页面的底色：**有壁纸 → `null`（透出 `R.id.root` 上的壁纸），无壁纸 → 实底 #303030**。
 *
 * 敢直接透明，是因为 [showHome] 显示 hub 时把 `panel`（文件列表层）一起 `GONE` 了 ——
 * 页面后面只剩 `R.id.root` 的壁纸，没有别的东西可透。
 *
 * 这里**不再自己贴一份壁纸位图**：那是异步解码的，首屏拿不到（真机反馈的「一开始
 * 看不见背景」）。交给 root 就没有时间差 —— root 的位图一就绪，这一层自动跟着变。
 */
private fun hubPageBg(act: MainActivity) = if (act.hasWallpaper()) null else solidHubBg()

/**
 * 让**已存在**的 hub 页面底色跟上当前壁纸状态（每个 tab 一份 `homeGrid`）。
 *
 * 换 / 清壁纸时 hub 不会重建 —— 设置对话框直接调 `applyBackgroundImage`，它只走
 * [refreshBackdrop]。这层不在那两张 backdrop 表里（底色是按 `hasWallpaper()` 现算的），
 * 不单独跟上就会停在旧底色上：真机上表现为「在设置里选了背景图片，主界面还是老样子，
 * 得进一次文件浏览再退回来才出现」。
 */
internal fun MainActivity.refreshHubPageBg() {
    tabs.forEach { tab ->
        val grid = tab.homeRoot?.findViewById<View>(R.id.homeGrid) ?: return@forEach
        grid.background = hubPageBg(this)
    }
}

/**
 * 重建**所有**正显示着 hub 的页（旋转 / 分屏尺寸变化后调用）。
 *
 * 清单里声明了 `configChanges="orientation|screenSize|screenLayout|smallestScreenSize"`
 * → Activity **不重建**，视图树原样留着。而 hub 的骨架是 [buildHomeCards] 在
 * [showHome] 时一次性拼好的（`isRoomy` 决定宽屏 / 手机两套），不重拼就一直是旧方向那套 ——
 * 真机现象：平板竖屏下看到的是横屏的双栏骨架（hero 并排、快捷 1×4），整屏被压扁。
 * 横屏 / 竖屏各自「刚好一屏」的权重也是在同一处算的，所以两件事必须一起重来。
 *
 * homeRoot is a fixed panel; rebuild cards against the new viewport after rotation.
 */
internal fun MainActivity.refreshHubs() {
    tabs.forEach { tab ->
        if (!tab.viewsBound || !tab.homeVisible) return@forEach
        val grid = tab.homeRoot?.findViewById<LinearLayout>(R.id.homeGrid) ?: return@forEach
        buildHomeCards(this, grid, tab)
    }
}

internal fun buildHomeCards(act: MainActivity, container: LinearLayout, tab: TabState) {
    // 页面底色跟随文件浏览器（见类注释）：有壁纸 → 透明（壁纸透出）；无壁纸 → 实底。
    // 卡片（cardBg/dimBg）永远实底 #252525 —— 文字不直接压在壁纸上。
    val cardBg = C["surface_dim"]!!
    val dimBg = C["surface_dim"]!!
    val roomy = isRoomy(act)
    val spacious = roomy && act.resources.configuration.screenHeightDp >= 520

    // The outer home panel never scrolls; hero cards and list viewports share vertical
    // space proportionally so neither balloons on tall portrait screens nor collapses
    // on short landscape screens, while each list scrolls its own entries internally.
    container.removeAllViews()
    container.orientation = LinearLayout.VERTICAL
    // 页面底色（见类注释）：有壁纸 → 透 root 的壁纸；无壁纸 → 实底 #303030。
    container.background = hubPageBg(act)
    // ⚠️ 纵向间距见类注释那条：首项 `marginTop`、末项自身 `paddingBottom`，**不要**用
    // `container` 的纵向 padding 或任何 `marginBottom` —— 它们不计入 `mTotalLength`，
    // 会被权重块吃掉、把后面的页脚挤出容器。横向 padding 无此问题（权重只吃纵向）。
    val hPad = act.dp(if (spacious) 36 else if (roomy) 20 else 18)
    container.setPadding(hPad, 0, hPad, 0)

    // ── Agent 入口 ───────────────────────────────────────────────
    val palette = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (spacious) setPadding(act.dp(20), act.dp(12), act.dp(18), act.dp(12))
        else setPadding(act.dp(14), act.dp(8), act.dp(12), act.dp(8))
        background = roundedBg(act.dp(if (spacious) 16 else 14), dimBg, act.dp(1), C["accent_line"]!!)
        // 首项的上间距：原来在 container 的 padding-top 里，见上面的注释（纵向只能用 margin）。
        // Keep the label at its natural text height.
        layoutParams = LinearLayout.LayoutParams(
            MATCH, WRAP,
        ).apply {
            topMargin = act.dp(if (spacious) 18 else 8)
        }
        minimumHeight = act.resources.getDimensionPixelSize(R.dimen.touch_target)
        foreground = ripple(act, C["ripple"]!!)
        isClickable = true
        isFocusable = true
        setOnClickListener { act.showAgent() }
    }
    palette.addView(iconView(act, R.drawable.ic_agent, act.dp(if (spacious) 22 else 19), C["accent"]!!))
    palette.addView(textView(
        act, act.getString(R.string.home_palette_hint), if (spacious) 15f else 13.5f, C["hint"]!!,
    ).apply {
        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = act.dp(if (spacious) 14 else 11) }
        // 搜索框惯例：单行，窄屏截断而不是折行把整条撑高。
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    })
    palette.mergeIntoA11yNode(act.getString(R.string.home_palette_hint))
    container.addView(palette)

    // ── 两张 hero 卡 ─────────────────────────────────────────────
    // 宽屏：并排两张、横向内容，与下方列表区按比例分剩余高度。
    // 竖屏：上下叠放，与下方两张列表卡按设计稿比例（1 : 1 : 1.75 : 1.75）共同分配剩余高度，
    // 避免主卡在竖屏下膨胀成巨型空盒子而列表被压成一行细缝。
    val browseTag = if (tab.currentDir == Environment.getExternalStorageDirectory()) {
        act.getString(R.string.home_storage_root)
    } else {
        act.getString(R.string.home_last_dir, tab.currentDir.name)
    }
    val browseHero = heroCard(act, R.drawable.ic_folder, act.getString(R.string.home_browse),
        act.getString(R.string.home_browse_desc), browseTag, false, cardBg, roomy) { act.enterBrowser(tab) }
    val termHero = heroCard(act, R.drawable.ic_terminal, act.getString(R.string.home_terminal),
        act.getString(R.string.home_terminal_desc),
        act.getString(R.string.home_cmds_tag, UuCommands.cmdCount), true, cardBg, roomy) { act.terminalPanel.show() }
    if (roomy) {
        container.addView(equalRow(act, browseHero, termHero, gapDp = if (spacious) 18 else 12),
            LinearLayout.LayoutParams(MATCH, 0, 1.05f).apply { topMargin = act.dp(if (spacious) 16 else 8) })
    } else {
        container.addView(browseHero, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = act.dp(8) })
        container.addView(termHero, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = act.dp(8) })
    }

    // ── 快捷入口：搜索 / 回收站 / 根目录 / 设置 ───────────────────
    // 手机竖屏 4 张挤一行会把说明文字压没（每卡只剩 ~3dp）→ 拆成 2×2；宽屏一行 4 张。
    val recycleN = RecycleBin.recycleDir(act).listFiles()?.size ?: 0
    val recycleSub = if (recycleN == 0) act.getString(R.string.home_recycle_empty)
    else act.getString(R.string.home_recycle_sub, recycleN)
    val minis = listOf(
        miniCard(act, R.drawable.ic_search, act.getString(R.string.msg_search_global),
            act.getString(R.string.home_q_search_sub), dimBg, roomy) { act.globalSearch() },
        miniCard(act, R.drawable.ic_recycle, act.getString(R.string.title_recycle_bin),
            recycleSub, dimBg, roomy) { showRecycleBinDialog(act) },
        miniCard(act, R.drawable.ic_home, act.getString(R.string.home_storage_root),
            Environment.getExternalStorageDirectory().absolutePath, dimBg, roomy) {
            act.enterBrowserAt(tab, Environment.getExternalStorageDirectory())
        },
        miniCard(act, R.drawable.ic_settings, act.getString(R.string.settings),
            act.getString(R.string.home_q_settings_sub), dimBg, roomy) { act.settings() },
    )
    fun miniRow(cells: List<View>, topMarginDp: Int) = container.addView(
        equalRow(act, *cells.toTypedArray(), gapDp = if (spacious) 14 else 9),
        // Compact rows keep their natural text height.
        LinearLayout.LayoutParams(MATCH, WRAP)
            .apply { topMargin = act.dp(topMarginDp) },
    )
    if (roomy) {
        miniRow(minis, if (spacious) 16 else 8)
    } else {
        miniRow(minis.subList(0, 2), 8)
        miniRow(minis.subList(2, 4), 8)
    }

    // ── 磁盘占用横条 ─────────────────────────────────────────────
    // 快捷入口是 2×2（竖屏）/ 1×4（宽屏），加第 5 张要么破坏对称、要么把每张压到
    // 装不下标题的宽度。改成**单独一条横条**：形状不同，也不跟那四格抢位置。
    // 顺带把「已用 / 共」直接写在条上 —— 这正是 WizTree 顶部那两个数，点进去之前
    // 就知道值不值得点。
    container.addView(
        diskUsageBar(act, roomy),
        LinearLayout.LayoutParams(MATCH, WRAP)
            .apply { topMargin = act.dp(if (spacious) 14 else 8) },
    )

    // ── 最近打开的归档 │ 书签 ───────────────────────────────────
    // 宽屏：双栏并排，与上方 heroRow 按比例共享剩余高度，每个卡片内部独立滚动。
    // 竖屏：两张列表卡各占一行，与两张 hero 卡按比例共享剩余高度，每个卡片内部独立滚动。
    val (recentsCap, recentsCard) = homeRecentsParts(act, tab, cardBg, roomy)
    val (bmsCap, bmsCard) = homeBookmarksParts(act, tab, cardBg, roomy)
    recentsCard.minimumHeight = homeListRowHeight(act)
    bmsCard.minimumHeight = homeListRowHeight(act)
    if (roomy) {
        fun col(cap: View, card: View) = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(cap)   // 沿用 sectionCap 自己的 layoutParams（左右各 2dp 的微调）
            addView(card, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = act.dp(if (spacious) 8 else 4) })
        }
        val cols = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        cols.addView(col(recentsCap, recentsCard), LinearLayout.LayoutParams(0, MATCH, 1.5f).apply { marginEnd = act.dp(if (spacious) 20 else 12) })
        cols.addView(col(bmsCap, bmsCard), LinearLayout.LayoutParams(0, MATCH, 1f))
        container.addView(cols, LinearLayout.LayoutParams(MATCH, 0, 1.15f).apply { topMargin = act.dp(if (spacious) 16 else 8) })
    } else {
        container.addView(recentsCap, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(act.dp(2), act.dp(8), act.dp(2), 0)
        })
        container.addView(recentsCard, LinearLayout.LayoutParams(MATCH, 0, 1.75f).apply { topMargin = act.dp(4) })
        container.addView(bmsCap, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(act.dp(2), act.dp(8), act.dp(2), 0)
        })
        container.addView(bmsCard, LinearLayout.LayoutParams(MATCH, 0, 1.75f).apply { topMargin = act.dp(4) })
    }

    // ── 页脚 ─────────────────────────────────────────────────────
    container.addView(textView(
        act,
        act.getString(R.string.home_foot_fmt, ARCHIVE_EXTS.size) + " · " +
            act.getString(R.string.home_foot_local),
        if (spacious) 12f else 11f, C["tertiary"]!!,
    ).apply {
        gravity = Gravity.CENTER
        // ⚠️ 末项的下间距**必须做成自己的 paddingBottom，不能是 marginBottom**：
        // `LinearLayout` 第一遍测量只累加 `marginTop + childHeight`，`marginBottom`
        // 不计入 → 权重块把它吃掉 → 页脚整块溢出容器被裁（横屏真机实测）。
        // 写成自身 padding 就是 childHeight 的一部分，一定被计入。
        setPadding(0, 0, 0, act.dp(if (spacious) 16 else 8))
        layoutParams = LinearLayout.LayoutParams(
            MATCH, WRAP,
        ).apply {
            topMargin = act.dp(if (spacious) 12 else 6)
        }
    })
}

// ── 磁盘占用横条 ─────────────────────────────────────────────────────

/**
 * 主页的「磁盘占用」入口。**横竖两套**（判据同 [isRoomy]）：
 *
 * | | 骨架 | 为什么 |
 * |---|---|---|
 * | **竖屏**（含平板竖屏） | 单行：图标 + 标题 + 「已用 / 共」+ 箭头 | 高度由 内容和内边距决定，两行文字会溢出被裁；这条横条只有一行的余地 |
 * | **横屏宽屏** | 图标 + 标题 + **内嵌容量条** + 「已用 / 共」+ 箭头，条本身 50dp 高 | 横向空间富裕、纵向反而不必省；容量条把「还剩多少」画出来，扫一眼就知道 |
 *
 * 数字来自 [externalStorageUsage]（StatFs，系统调用、微秒级），不是扫描结果 ——
 * 扫描要一分钟，主界面不该等它。真正的分解在点进去之后。
 */
private fun diskUsageBar(act: MainActivity, roomy: Boolean): View {
    val spacious = roomy && act.resources.configuration.screenHeightDp >= 520
    val (used, total) = externalStorageUsage()
    val sub = if (total > 0) {
        act.getString(R.string.home_disk_sub, fmt(used), fmt(total))
    } else {
        ""
    }
    val line = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // A full-height touch target remains available without a weighted text budget.
        minimumHeight = act.dp(if (spacious) 48 else 42)
        setPadding(
            act.dp(if (spacious) 18 else 12),
            act.dp(if (spacious) 12 else 6),
            act.dp(if (spacious) 16 else 12),
            act.dp(if (spacious) 12 else 6),
        )
    }
    line.addView(iconView(act, R.drawable.ic_disk, act.dp(if (spacious) 20 else 16), C["accent"]!!))
    line.addView(primaryText(act, act.getString(R.string.home_disk), if (spacious) 14f else 13f, bold = true).apply {
        setSingleLine(true)
    }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = act.dp(if (spacious) 12 else 9) })

    if (roomy && total > 0) {
        // 横屏专有：把「已用 / 共」画成一条双段条。竖屏没有这个余地（整条才 ~37dp 高），
        // 而且窄屏上一条被压到 100dp 出头的比例条也读不出比例，不如只给数字。
        line.addView(
            capacityStrip(act, used, total),
            LinearLayout.LayoutParams(0, act.dp(7), 1f)
                .apply { marginStart = act.dp(if (spacious) 20 else 12); marginEnd = act.dp(if (spacious) 20 else 12) },
        )
    }

    line.addView(textView(act, sub, if (spacious) 12f else 11f, C["tertiary"]!!).apply {
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.END
    }, LinearLayout.LayoutParams(if (roomy) WRAP else 0, WRAP, if (roomy) 0f else 1f)
        .apply { marginStart = act.dp(if (roomy) 0 else 10) })
    line.addView(iconView(act, R.drawable.ic_chevron_right, act.dp(14), C["tertiary"]!!))

    return FrameLayout(act).apply {
        background = roundedBg(act.dp(if (spacious) 16 else 14), C["surface_dim"]!!, act.dp(1), C["hairline"]!!)
        foreground = ripple(act, C["ripple"]!!)
        isClickable = true
        isFocusable = true
        addView(line, FrameLayout.LayoutParams(MATCH, MATCH))
        mergeIntoA11yNode(if (sub.isEmpty()) act.getString(R.string.home_disk) else "${act.getString(R.string.home_disk)}，$sub")
        setOnClickListener { act.showDiskUsage() }
    }
}

/**
 * 「已用 / 共」的双段细条（横屏专有）：左段主色 = 已用，右段深底 = 剩余。
 *
 * 两段都用 `weight` 分配，所以比例天然正确；`coerceAtLeast(1f)` 是防 0 权重 ——
 * 一个 0 宽的子项会让另一段吃掉全部宽度，看着像「已经满了」。
 */
private fun capacityStrip(act: MainActivity, used: Long, total: Long): LinearLayout =
    LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        background = roundedBg(act.dp(4), C["surface_dark"]!!)
        clipToOutline = true
        addView(
            View(act).apply { setBackgroundColor(C["accent"]!!) },
            LinearLayout.LayoutParams(0, MATCH, used.coerceAtLeast(1L).toFloat()),
        )
        addView(
            View(act),
            LinearLayout.LayoutParams(0, MATCH, (total - used).coerceAtLeast(1L).toFloat()),
        )
    }

// ── 区块：最近打开的归档 ─────────────────────────────────────────────

/**
 * 「最近打开的归档」区块 = **标题 + 卡片两块**（[Pair]），由调用处决定怎么摆。
 *
 * Header and fixed-height list viewport are separate children:
 * 若包一层 `col`，卡片能拿到的就是「col 的高度 − 标题的自然高度」，标题那 ~27dp 不跟着
 * 缩放、比例就不准了。宽屏则由调用处把两块塞进一个 `col` 再并排。
 */
private fun homeRecentsParts(act: MainActivity, tab: TabState, cardBg: Int, roomy: Boolean): Pair<View, View> {
    val entries = RecentsStore.list(act)
    // 尾注 = 条数（派生，现查现画）；空列表不摆尾注，提示只在卡片里出现一次。
    val cap = sectionCap(act, act.getString(R.string.home_recents),
        if (entries.isEmpty()) null else entries.size.toString(),
        if (roomy) 11.5f else 10.5f, if (roomy) 12f else 11f)
    // 卡片**定高**（宽屏 [LIST_CARD_H]、竖屏 [homeListRowHeight]）、**行高固定**，
    // 装不下时在卡片内滚动 —— 窗口尺寸不随内容/剩余空间变（行若用 weight 平分：1 条时
    // 被拉成巨行、10 条时被压扁）。高度由调用处给。
    val radius = act.dp(if (roomy) 16 else 14)
    val card = FadeCard(act, act.dp(LIST_FADE_H), radius).apply {
        background = roundedBg(radius, cardBg, act.dp(1), C["hairline"]!!)
        clipToOutline = true
    }
    // 空态**直接在卡片里居中**，不进 ScrollView —— 塞进 ScrollView 的话它是「内容的第一行」，
    // 只会贴着卡片上沿（真机反馈：「还没有打开过归档」不在正中心）。空态也没有可滚的东西。
    if (entries.isEmpty()) {
        card.fadeEnabled = false
        card.addView(
            textView(act, act.getString(R.string.home_recents_empty), 12f, C["tertiary"]!!).apply {
                gravity = Gravity.CENTER
            },
            FrameLayout.LayoutParams(MATCH, MATCH),
        )
        return cap to card
    }
    val scroll = com.usefulunpacker.UuScrollView(act).apply {
        // Honor/EMUI ROM：自定义 ScrollView 一开原生滚动条就会在 onDrawScrollBars 里 NPE。
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }
    card.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
    val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    scroll.addView(list)
    entries.forEachIndexed { i, e ->
        if (i > 0) list.addView(View(act).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, act.dp(1)).apply {
                marginStart = act.dp(14); marginEnd = act.dp(14)
            }
            setBackgroundColor(C["hairline"]!!)
            // 纯装饰的分隔线，别让 TalkBack 停在它上面。
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        list.addView(homeRecentRow(act, tab, e, roomy))
    }
    attachBottomFade(act, card, scroll)
    return cap to card
}

private fun homeRecentRow(act: MainActivity, tab: TabState, e: RecentsStore.Entry, roomy: Boolean): LinearLayout {
    val f = e.file
    val ext = f.extension.lowercase()
    val row = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(
            act.dp(if (roomy) 16 else 13), act.dp(if (roomy) 12 else 10),
            act.dp(if (roomy) 16 else 13), act.dp(if (roomy) 12 else 10),
        )
        foreground = ripple(act, C["ripple"]!!)
        isClickable = true
        isFocusable = true
        // v1 先落到归档所在目录（直接在此开预览要接一遍 openKey/互斥，后续再做）。
        setOnClickListener { f.parentFile?.let { act.enterBrowserAt(tab, it) } }
    }
    if (!roomy) row.layoutParams = LinearLayout.LayoutParams(MATCH, homeListRowHeight(act))
    val fmtColor = FORMAT_COLORS[ext] ?: C["icon_gray_dim"]!!
    row.addView(textView(act, ext.uppercase(), if (roomy) 11f else 10f, C["text_bright"]!!).apply {
        gravity = Gravity.CENTER
        background = roundedBg(act.dp(if (roomy) 8 else 7), fmtColor)
        layoutParams = LinearLayout.LayoutParams(act.dp(if (roomy) 54 else 46), act.dp(if (roomy) 28 else 25))
    })
    val names = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = act.dp(if (roomy) 14 else 11) }
    }
    names.addView(textView(act, f.name, if (roomy) 14f else 12.5f, C["primary"]!!, bold = true).apply {
        setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
    })
    names.addView(textView(act, f.parent ?: "", if (roomy) 11.5f else 10f, C["tertiary"]!!).apply {
        setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
    })
    row.addView(names)
    row.addView(textView(
        act,
        fmt(fileSize(f)) + " · " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(e.time)),
        if (roomy) 12f else 10.5f, C["tertiary"]!!,
    ))
    // 整行 = 一个 a11y 单元（格式块 / 文件名 / 目录 / 大小时间合成一句；格式块是装饰）。
    row.mergeIntoA11yNode("${f.name}，${f.parent ?: ""}")
    return row
}

// ── 区块：书签 ───────────────────────────────────────────────────────

/** 「书签」区块 = **标题 + 卡片两块**，理由同 [homeRecentsParts]。 */
private fun homeBookmarksParts(act: MainActivity, tab: TabState, cardBg: Int, roomy: Boolean): Pair<View, View> {
    val bookmarks = act.bookmarks
    // 尾注 = 条数；空列表不摆尾注 —— 原来恒显示 home_bookmarks_empty：有书签时也在误导
    // 用户「长按可加书签」，空时又和卡片内的同一句提示重复。
    val cap = sectionCap(act, act.getString(R.string.home_bookmarks),
        if (bookmarks.isEmpty()) null else bookmarks.size.toString(),
        if (roomy) 11.5f else 10.5f, if (roomy) 12f else 11f)
    // 与「最近」同一套：卡片定高（宽屏 [LIST_CARD_H] / 竖屏 [homeListRowHeight]）、行高固定、
    // 装不下时在卡片内滚动。高度由调用处给。
    val radius = act.dp(if (roomy) 16 else 14)
    val card = FadeCard(act, act.dp(LIST_FADE_H), radius).apply {
        background = roundedBg(radius, cardBg, act.dp(1), C["hairline"]!!)
        clipToOutline = true
    }
    // 空态与「最近」同一套：直接铺满卡片居中，不进 ScrollView（理由见 homeRecentsParts）。
    if (bookmarks.isEmpty()) {
        card.fadeEnabled = false
        card.addView(
            textView(act, act.getString(R.string.home_bookmarks_empty), 12f, C["tertiary"]!!).apply {
                gravity = Gravity.CENTER
            },
            FrameLayout.LayoutParams(MATCH, MATCH),
        )
        return cap to card
    }
    val scroll = com.usefulunpacker.UuScrollView(act).apply {
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }
    card.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
    val box = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(act.dp(6), if (roomy) act.dp(6) else 0, act.dp(6), if (roomy) act.dp(6) else 0)
    }
    scroll.addView(box)
    bookmarks.forEach { path ->
        box.addView(LinearLayout(act).apply {
            if (!roomy) layoutParams = LinearLayout.LayoutParams(MATCH, homeListRowHeight(act))
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                act.dp(if (roomy) 14 else 11), act.dp(if (roomy) 12 else 10),
                act.dp(if (roomy) 14 else 11), act.dp(if (roomy) 12 else 10),
            )
            foreground = ripple(act, C["ripple"]!!)
            isClickable = true
            isFocusable = true
            setOnClickListener { act.enterBrowserAt(tab, File(path)) }
            addView(iconView(act, R.drawable.ic_folder, act.dp(if (roomy) 17 else 15), C["accent"]!!))
            addView(textView(act, File(path).name.ifEmpty { path },
                if (roomy) 13.5f else 12.5f, C["secondary"]!!).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = act.dp(if (roomy) 12 else 10) }
                setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
            // 整行 = 一个 a11y 单元（文件夹图标 / 名字合成一句）。
            mergeIntoA11yNode(File(path).name.ifEmpty { path })
        })
    }
    attachBottomFade(act, card, scroll)
    return cap to card
}

/**
 * 定高列表卡：**在所有子视图画完之后**再盖一层底部渐隐（透明黑）。
 *
 * 为什么需要它：卡片是定高的，内容比它高时最后一行会被切在卡片边缘上 ——
 * 真机反馈「书签里第三栏跑出了栏目」，量下来那一行的文字正好压在卡片的 1dp 描边上
 * （卡片底边 y=1812，文字 1783–1812），看着像跑到框外去了。渐隐把切口收进暗部里，
 * 顺带暗示「还能往下滚」；滚到底时自动关掉。
 *
 * ⚠️ **为什么不用「往卡片里再 add 一个渐变 View」**：试过，真机上那层渐变把卡片底色压暗了、
 * 却压不暗列表里的文字（看起来像「文字浮在一块暗色上面」）—— 子视图的合成顺序不可控。
 * 走 `dispatchDraw` 的 `super` 之后画，顺序是确定的。
 *
 * ⚠️ **用透明黑而不是卡片底色**（用户指定）：卡片底色本身是深灰，拿它做渐变等于把同一个
 * 颜色再刷一遍，只有压在文字上才看得出一点点（实测第三行文字平均亮度只从 88.5 降到 81.5，
 * 等于没做）。透明黑是「压暗」不是「覆盖」，对任何卡片配色都成立。
 */
// 只在代码里 new，从不 XML 填充 —— 不需要 (Context, AttributeSet) 那套构造函数。
@SuppressLint("ViewConstructor")
private class FadeCard(
    act: MainActivity,
    private val fadePx: Int,
    private val radiusPx: Int,
) : FrameLayout(act) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private var shaderForHeight = -1

    /** 内容没超出 / 已经滚到底时关掉 —— 由 [attachBottomFade] 维护。 */
    var fadeEnabled = true

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!fadeEnabled) return
        val h = height
        val w = width
        if (h <= 0 || w <= 0) return
        val effectiveFade = minOf(fadePx, h / 3).coerceAtLeast(1)
        if (shaderForHeight != h) {
            shaderForHeight = h
            val top = (h - effectiveFade).coerceAtLeast(0).toFloat()
            paint.shader = LinearGradient(
                0f, top, 0f, h.toFloat(),
                intArrayOf(0x00000000, 0x99000000.toInt()), null, Shader.TileMode.CLAMP,
            )
        }
        canvas.save()
        // 卡片是圆角的，不裁一下会在两个下角外面糊出方角。
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        path.reset()
        path.addRoundRect(rect, radiusPx.toFloat(), radiusPx.toFloat(), Path.Direction.CW)
        canvas.clipPath(path)
        canvas.drawRect(0f, (h - effectiveFade).coerceAtLeast(0).toFloat(), w.toFloat(), h.toFloat(), paint)
        canvas.restore()
    }
}

/** 内容溢出、且还没滚到底 → 渐隐开着；否则关掉（否则「滚到底」时最后一行会被压暗）。 */
private fun attachBottomFade(act: MainActivity, card: FadeCard, scroll: ScrollView) {
    fun sync() {
        val content = scroll.getChildAt(0) ?: return
        val overflow = content.height - scroll.height
        card.fadeEnabled = overflow > 0 && scroll.scrollY < overflow - act.dp(1)
        card.invalidate()
    }
    scroll.setOnScrollChangeListener { _, _, _, _, _ -> sync() }
    // 高度要等测量完才知道。**不能用 `ViewTreeObserver.addOnGlobalLayoutListener`**：
    // 视图还没 attach 时拿到的是一个游离的 observer，实测首帧那次回调根本没来。
    // 布局回调每次布局都会来，转屏 / 重建也覆盖得到。
    card.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sync() }
}
