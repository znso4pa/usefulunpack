package com.usefulunpacker

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.usefulunpacker.fileops.RecentsStore
import com.usefulunpacker.fileops.RecycleBin
import com.usefulunpacker.fileops.showRecycleBinDialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 每个 tab 的「主界面」（hub）—— 用户从四版 HTML 设计稿里选定的 **C2「命令中心」**。
 *
 * ```text
 *   tab ─┬─ 主界面（hub，盖住 pathBar+panel）─┬─ 命令面板（点击 → 全局搜索）
 *        │                                   ├─ 两张 hero 卡（文件浏览 / 终端）
 *        │                                   ├─ 快捷入口（搜索 / 回收站 / 根目录 / 设置）
 *        │                                   ├─ 最近打开的归档 │ 书签（宽屏双栏）
 *        │                                   └─ 页脚
 *        └─ 文件浏览器（navTab 渲染的列表）
 * ```
 *
 * 实现与归档内预览（`previewRoot`）同一套路：`folder_view.xml` 里最后声明的覆盖层，
 * 盖住 pathBar + panel；`TabState.homeVisible` 决定谁在上面。
 *
 * 新 tab 默认落在主界面（`addTab` 置 true）；恢复会话的 tab 不落（恢复的是「你上次
 * 在哪儿」）—— 所以 `homeVisible` 不进 session，默认 false 即可。
 *
 * ## 两套布局（[isRoomy] 一分为二）
 *
 * | | 判定 | 骨架 |
 * |---|---|---|
 * | **宽屏**（横屏） | **横屏** 且 `screenWidthDp ≥ 600` | 单列纵向；hero 并排 2 张（**横向内容**，`weight=1` 吃剩余高度撑满整屏）、快捷 1×4、最近/书签**双栏且各自定高**（[LIST_CARD_H]） |
 * | **手机竖屏**（含平板竖屏） | 其余（竖屏一律走这套） | 单列；hero **上下两张横向紧凑卡**、快捷 **2×2**、最近/书签各占**一整行**；**每个块都按设计稿的硬编码比例定高**（`PW_*`），卡片内部独立滚动 |
 *
 * 判据见 [isRoomy]：**竖屏一律走手机那套**（平板竖屏 812dp 宽也只按宽度判会被误判成宽屏，
 * 竖屏就变成横屏的骨架 —— 用户否掉过）；横屏再按宽度区分，因为手机横屏宽度通常 640–900dp，
 * 只看 `is_wide`/`sw600dp`（与方向无关）会把手机横屏误判成窄屏、双栏被压成上下堆叠。
 *
 * **撑满 / 一屏装下的做法**：`homeRoot` 是 `fillViewport="true"` 的 ScrollView，内容矮时
 * `homeGrid` 会被撑到 viewport 高（EXACTLY），于是内部 `weight` 就能分配到「剩余高度」。
 * 内容真的超一屏时 fillViewport 不生效，自然回退成滚动 —— 不会把内容压扁。
 *
 * - **宽屏**：**只有 hero 区** `height=0 + weight=1`（吃剩余高度撑满整屏，定稿的样子）；
 *   两个列表窗口**定高**（[LIST_CARD_H]），条目多了窗口内滚动。
 * - **手机竖屏**：**整列每一块都带权重**，权重 = 设计稿里的自然高度 dp（见 `PW_*`）。
 *   于是各块尺寸是一个确定值（不随内容多少、也不随剩余空间乱分），整列等比铺满、不溢出；
 *   在设计稿画布（412×915）上逐块还原设计稿。列表卡内部滚动。
 *   ⚠️ 早期版本只给两张列表卡 `weight=1`，结果卡片被拉到 300dp（设计稿 153dp）——
 *   一屏里塞满空白的「冗余」，用户明确否掉。改成全列按比例后，卡片回到设计稿的比例。
 *
 * ⚠️ **尾部间距必须计入子项自身高度（`paddingBottom`），不能靠 `marginBottom` 或父容器
 * 的纵向 `padding`**：`LinearLayout` 第一遍测量只累加 `marginTop + childHeight` ——
 * `marginBottom` 与父容器纵向 `padding` 都**不计**，而权重块拿的是
 * `heightSize - mTotalLength` → 弹性块把这些间距一起吃掉、自己长高，排在它后面的页脚
 * 整块溢出容器被裁掉（横屏真机实测：页脚根本没被布局）。`marginTop` 和子项自身
 * `padding` 才计入。所以：首项用 `marginTop`、末项用自身 `paddingBottom`（宽屏）或直接
 * 算进权重（竖屏，见 [PW_FOOTER]）。
 * **注意**：横屏刻意**不限宽**（用户要「覆盖整个屏幕」），所以没用 `content_max_width`。
 *
 * ⚠️ **旋转后必须重拼卡片**：清单声明了 `configChanges`，Activity 不重建，而 hub 的骨架是
 * 拼一次就留着的 → 不重拼会一直是旧方向那套。`MainActivity.onConfigurationChanged` 里调
 * [refreshHubs]（见那里）。
 *
 * ⚠️ **页面底色 = 透 `R.id.root` 的壁纸（有壁纸）/ 实底 #303030（无壁纸）；卡片永远实底**：
 * hub 是覆盖层，**后面就是文件列表** —— 页面无条件透明会把那份列表透出来（第一版
 * 「全部透了过来」的真正来源，不只是壁纸）。所以 [showHome] 显示 hub 时把 `panel`
 * （文件列表那一层）一并 `GONE`，页面才敢透明：有壁纸时 `homeGrid` **不设底**，直接
 * 透出 root 上的壁纸 —— 与文件浏览逐像素一致；无壁纸时铺实底 #303030（与 `homeRoot`
 * 的 XML 底 `bg_file_list` 同色，所以切壁纸 / 清壁纸都不会闪）。**卡片永远实底**：
 * 文字不直接压在壁纸上（第一版教训）。
 *
 * 曾经的写法是「把 root 的壁纸位图复制一份贴到这一层」（`constantState.newDrawable()`）——
 * 但壁纸是 `applyBackgroundImage` **异步解码**的，首屏那一刻位图还没就绪，只能先铺实底
 * （真机反馈：一开始看不见背景，进一次文件浏览再退回来才出现）。透明 + 藏 `panel`
 * 就没有这个时间差，也省掉一份位图。
 *
 * ⚠️ **派生，不存储**（不变式 8）：最近归档 / 书签 / 回收站条数都是 [showHome] 时
 * 现查现画的 —— 不缓存，用户解包完回到主界面看到的就是新的。
 */
/** 切到主界面。视图未绑时（启动期 addTab）只记状态，fragment 建视图时按它渲染。 */
internal fun MainActivity.showHome(tab: TabState) {
    if (!tab.viewsBound) {
        tab.homeVisible = true
        return
    }
    tab.homeVisible = true
    val root = tab.homeRoot ?: return
    buildHomeCards(this, root.findViewById(R.id.homeGrid), tab)
    root.visibility = View.VISIBLE
    tab.pathBar?.visibility = View.GONE
    // `panel`（文件列表）必须一起藏：hub 页面在有壁纸时是**透明**的（透 root 的壁纸，
    // 见类注释），列表还画在下面就会透出来 —— 第一版「全都透了过来」的根因。
    tab.panel?.visibility = View.GONE
    tab.previewRoot.visibility = View.GONE
    tab.bottomBar.visibility = View.GONE
    tab.fabExtract.visibility = View.GONE
    tab.batchBar?.visibility = View.GONE
    // 不动 previewActive：hub 只是盖在上面，重建视图时预览还能恢复；
    // 真正进文件浏览器（enterBrowser → navTab）才把它作废。
}

/** 进文件浏览器（当前目录）。 */
internal fun MainActivity.enterBrowser(tab: TabState) = enterBrowserAt(tab, tab.currentDir)

/** 进文件浏览器并直接落在 [dir]（hub 的「存储根目录」卡 / 最近书签用）。 */
internal fun MainActivity.enterBrowserAt(tab: TabState, dir: File) {
    tab.homeVisible = false
    tab.homeRoot?.visibility = View.GONE
    tab.pathBar?.visibility = View.VISIBLE
    // 把 hub 藏起来的文件列表放回来（showHome 里 GONE 的，必须在这里配平）。
    tab.panel?.visibility = View.VISIBLE
    // navTab 会清掉多选 / 预览态、重启 FileObserver、刷新列表 —— 全套进浏览器。
    navTab(tab, dir)
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
 */
private fun isRoomy(act: MainActivity): Boolean {
    val cfg = act.resources.configuration
    return cfg.screenWidthDp >= 600 &&
        cfg.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
}

/**
 * 列表窗口（最近归档 / 书签）在**宽屏下的固定高度**（dp）—— 约 4 行书签（行高 ~42dp）。
 *
 * ⚠️ 宽屏这两个窗口是**定高**的，**不随剩余空间延伸**：窗口大小固定、条目多了在窗口内部
 * 滚动。曾经用 `weight=1` 让它们吃满剩余高度，结果窗口一直长到屏幕底部、条目反而被切掉
 * （真机反例），而且「只有 1 条时被拉成一条巨行」也是同一类问题。
 * 剩余高度交给上面的 hero 区吸收（它仍是 `weight=1`），整页照样铺满、页脚不被挤掉。
 *
 * 手机竖屏**不能**定高：那边要求「刚好一屏装下」，矮屏上定高会装不下，所以走 `weight=1`。
 */
private const val LIST_CARD_H = 184

/*
 * ── 手机竖屏：整列按设计稿的**硬编码比例**分高度 ──────────────────────────
 *
 * 设计稿画布是 **412 × 915 dp**（`~/Desktop/1/主界面-手机竖屏.html`，`.device` 的
 * `width/height`）。扣掉状态栏 26 / 标签栏 50 / 工具栏 56，内容区 783dp；再扣 `.hub` 的
 * 上下内边距 16 + 14 → **hub 可用高度 753dp**。各块在设计稿里的自然高度（dp）就是下面这些
 * 常量，加起来 681dp，余下的 72dp 是各块的间距（首项 16、块间 12/14/9/14/7…）。
 *
 * **为什么用「比例」而不是「绝对 dp」**：用户要求「竖屏的比例严格按照原生比例，确保能占满
 * 整个屏幕同时不会溢出，每个格子都是硬编码大小、内部独立滚动」。绝对 dp 做不到 —— 把每块
 * 钉死在设计稿的 dp 上，本机平板竖屏（可用 ~1036dp）会在底部空出 280dp；而只让列表卡吃剩余
 * 高度，卡片又会被拉成 300dp（真机反例，正是用户说的「冗余」）。改成**权重 = 设计稿 dp**
 * 之后：在设计稿画布上逐块还原设计稿，在任何屏幕上整列等比铺满、不溢出，且每块的尺寸是
 * 一个确定的像素值（不随内容多少变化），列表卡内部独立滚动。
 *
 * 行高**不**跟着缩放（沿用自然高度）—— 缩放行高只会让文字与大留白错位，且卡片本来就能滚。
 */
private const val PW_PALETTE = 44f    // 命令面板
private const val PW_HERO = 72f       // 一张 hero 卡
private const val PW_QUICK = 58f      // 快捷入口一行（2×2 的其中一行）
private const val PW_CAP = 20f        // 「最近打开的归档」/「书签」标题
private const val PW_CARD = 154f      // 列表卡（设计稿 753 − 固定块 → 每张 ~153dp）
private const val PW_FOOTER = 29f     // 页脚 15 + hub 底部内边距 14（纵向内边距不能靠容器 padding）

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
 * `homeRoot` 是 `fillViewport` 的 ScrollView：内容矮时 `homeGrid` 被撑到 viewport 高，
 * 权重照新方向重新分配；所以只要重拼卡片，尺寸就跟着对了。
 */
internal fun MainActivity.refreshHubs() {
    tabs.forEach { tab ->
        if (!tab.viewsBound || !tab.homeVisible) return@forEach
        val grid = tab.homeRoot?.findViewById<LinearLayout>(R.id.homeGrid) ?: return@forEach
        buildHomeCards(this, grid, tab)
    }
}

internal fun buildHomeCards(act: MainActivity, container: LinearLayout, tab: TabState) {
    val dm = act.resources.displayMetrics
    fun d(v: Int) = (v * dm.density).toInt()
    // 页面底色跟随文件浏览器（见类注释）：有壁纸 → 透明（壁纸透出）；无壁纸 → 实底。
    // 卡片（cardBg/dimBg）永远实底 #252525 —— 文字不直接压在壁纸上。
    val cardBg = C["surface_dim"]!!
    val dimBg = C["surface_dim"]!!
    val roomy = isRoomy(act)

    container.removeAllViews()
    container.orientation = LinearLayout.VERTICAL
    // 页面底色（见类注释）：有壁纸 → 透 root 的壁纸；无壁纸 → 实底 #303030。
    container.background = hubPageBg(act)
    // ⚠️ 纵向间距见类注释那条：首项 `marginTop`、末项自身 `paddingBottom`，**不要**用
    // `container` 的纵向 padding 或任何 `marginBottom` —— 它们不计入 `mTotalLength`，
    // 会被权重块吃掉、把后面的页脚挤出容器。横向 padding 无此问题（权重只吃纵向）。
    container.setPadding(d(if (roomy) 40 else 18), 0, d(if (roomy) 40 else 18), 0)

    // ── 命令面板：点击整条 → 全局搜索 ─────────────────────────────
    val palette = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (roomy) setPadding(d(20), d(14), d(18), d(14)) else setPadding(d(14), d(11), d(12), d(11))
        background = GradientDrawable().apply {
            cornerRadius = d(if (roomy) 16 else 14).toFloat()
            setColor(dimBg)
            setStroke(d(1), 0x5935ACC6)
        }
        // 首项的上间距：原来在 container 的 padding-top 里，见上面的注释（纵向只能用 margin）。
        // 手机竖屏**按设计稿比例定高**（[PW_PALETTE]），不再是 `wrap_content`。
        layoutParams = LinearLayout.LayoutParams(
            MATCH, if (roomy) WRAP else 0, if (roomy) 0f else PW_PALETTE,
        ).apply {
            topMargin = d(if (roomy) 24 else 16)
        }
        foreground = RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), null, null)
        isClickable = true
        isFocusable = true
        setOnClickListener { act.globalSearch() }
    }
    palette.addView(homeIcon(act, R.drawable.ic_search, d(if (roomy) 22 else 19), C["accent"]!!))
    palette.addView(homeText(
        act, act.getString(R.string.home_palette_hint), if (roomy) 15f else 13.5f, C["hint"]!!,
    ).apply {
        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = d(if (roomy) 14 else 11) }
        // 搜索框惯例：单行，窄屏截断而不是折行把整条撑高。
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    })
    palette.addView(homeText(
        act, act.getString(R.string.msg_search_global), if (roomy) 12f else 10.5f, C["accent"]!!,
    ).apply {
        background = GradientDrawable().apply {
            cornerRadius = d(if (roomy) 7 else 6).toFloat(); setStroke(d(1), 0x33FFFFFF)
        }
        setPadding(d(if (roomy) 11 else 8), d(4), d(if (roomy) 11 else 8), d(4))
    })
    container.addView(palette)

    // ── 两张 hero 卡 ─────────────────────────────────────────────
    // 宽屏：并排两张、横向内容，`weight=1` 吃剩余高度把整屏撑起来（定稿的样子）。
    // 手机竖屏：上下两张横向紧凑卡，**按设计稿比例定高**（[PW_HERO]）—— 412dp 宽并排只剩
    // ~150dp/卡、描述会折 4–5 行；定高后卡片尺寸不再随内容或屏幕剩余空间漂。
    val browseTag = if (tab.currentDir == Environment.getExternalStorageDirectory()) {
        act.getString(R.string.home_storage_root)
    } else {
        act.getString(R.string.home_last_dir, tab.currentDir.name)
    }
    val browseHero = homeHero(act, R.drawable.ic_folder, act.getString(R.string.home_browse),
        act.getString(R.string.home_browse_desc), browseTag, false, cardBg, roomy) { act.enterBrowser(tab) }
    val termHero = homeHero(act, R.drawable.ic_terminal, act.getString(R.string.home_terminal),
        act.getString(R.string.home_terminal_desc),
        act.getString(R.string.home_cmds_tag, UuCommands.cmdCount), true, cardBg, roomy) { act.terminalPanel.show() }
    if (roomy) {
        container.addView(homeRow(act, browseHero, termHero, gap = d(18)),
            LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = d(22) })
    } else {
        // 手机竖屏：两张上下排、**按设计稿比例定高**（[PW_HERO]），不再 `wrap_content`。
        container.addView(browseHero, LinearLayout.LayoutParams(MATCH, 0, PW_HERO).apply { topMargin = d(12) })
        container.addView(termHero, LinearLayout.LayoutParams(MATCH, 0, PW_HERO).apply { topMargin = d(12) })
    }

    // ── 快捷入口：搜索 / 回收站 / 根目录 / 设置 ───────────────────
    // 手机竖屏 4 张挤一行会把说明文字压没（每卡只剩 ~3dp）→ 拆成 2×2；宽屏一行 4 张。
    val recycleN = RecycleBin.recycleDir(act).listFiles()?.size ?: 0
    val recycleSub = if (recycleN == 0) act.getString(R.string.home_recycle_empty)
    else act.getString(R.string.home_recycle_sub, recycleN)
    val minis = listOf(
        homeMini(act, R.drawable.ic_search, act.getString(R.string.msg_search_global),
            act.getString(R.string.home_q_search_sub), dimBg, roomy) { act.globalSearch() },
        homeMini(act, R.drawable.ic_recycle, act.getString(R.string.title_recycle_bin),
            recycleSub, dimBg, roomy) { showRecycleBinDialog(act) },
        homeMini(act, R.drawable.ic_home, act.getString(R.string.home_storage_root),
            Environment.getExternalStorageDirectory().absolutePath, dimBg, roomy) {
            act.enterBrowserAt(tab, Environment.getExternalStorageDirectory())
        },
        homeMini(act, R.drawable.ic_settings, act.getString(R.string.settings),
            act.getString(R.string.home_q_settings_sub), dimBg, roomy) { act.settings() },
    )
    fun miniRow(cells: List<View>, topMarginDp: Int) = container.addView(
        homeRow(act, *cells.toTypedArray(), gap = d(if (roomy) 14 else 9)),
        // 手机竖屏按设计稿比例定高（[PW_QUICK]）；宽屏仍是一整行 `wrap_content`。
        LinearLayout.LayoutParams(MATCH, if (roomy) WRAP else 0, if (roomy) 0f else PW_QUICK)
            .apply { topMargin = d(topMarginDp) },
    )
    if (roomy) {
        miniRow(minis, 20)
    } else {
        miniRow(minis.subList(0, 2), 14)
        miniRow(minis.subList(2, 4), 9)
    }

    // ── 最近打开的归档 │ 书签 ───────────────────────────────────
    // 宽屏：双栏并排，两个窗口**定高**（[LIST_CARD_H]）。
    // 手机竖屏：各占一整行，**标题与卡片各自按设计稿比例定高**（[PW_CAP] / [PW_CARD]），
    // 卡片内部独立滚动 —— 所以标题和卡片必须是 `container` 的**两个直接子项**，
    // 不能再包一层 `col`（那样卡片的实际高度会被标题的固定高度吃掉一截，比例就不准了）。
    val (recentsCap, recentsCard) = homeRecentsParts(act, tab, cardBg, roomy)
    val (bmsCap, bmsCard) = homeBookmarksParts(act, tab, cardBg, roomy)
    if (roomy) {
        // 两个列表窗口**定高**（见 [LIST_CARD_H]）→ 整块 `wrap_content`，剩余高度留给上面的
        // hero 区吸收（它仍是 `weight=1`），整页照样铺满整屏。
        // 两列用 `WRAP` 高而不是 `MATCH`：本行是 wrap，若子项全 `MATCH` 会把行高算成 0。
        fun col(cap: View, card: View) = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(cap)   // 沿用 homeCap 自己的 layoutParams（左右各 2dp 的微调）
            addView(card, LinearLayout.LayoutParams(MATCH, d(LIST_CARD_H)).apply { topMargin = d(10) })
        }
        val cols = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        cols.addView(col(recentsCap, recentsCard), LinearLayout.LayoutParams(0, WRAP, 1.5f).apply { marginEnd = d(20) })
        cols.addView(col(bmsCap, bmsCard), LinearLayout.LayoutParams(0, WRAP, 1f))
        container.addView(cols, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = d(22) })
    } else {
        // 手机竖屏：标题 20 / 卡片 154（设计稿比例），间距走各自的 topMargin（权重子项的
        // `marginTop` 计入 `mTotalLength`）。左右 2dp 是 homeCap 原有的微调，覆盖时要带上。
        container.addView(recentsCap, LinearLayout.LayoutParams(MATCH, 0, PW_CAP).apply {
            setMargins(d(2), d(14), d(2), 0)
        })
        container.addView(recentsCard, LinearLayout.LayoutParams(MATCH, 0, PW_CARD).apply { topMargin = d(7) })
        container.addView(bmsCap, LinearLayout.LayoutParams(MATCH, 0, PW_CAP).apply {
            setMargins(d(2), d(14), d(2), 0)
        })
        container.addView(bmsCard, LinearLayout.LayoutParams(MATCH, 0, PW_CARD).apply { topMargin = d(7) })
    }

    // ── 页脚 ─────────────────────────────────────────────────────
    container.addView(homeText(
        act,
        act.getString(R.string.home_foot_fmt, ARCHIVE_EXTS.size) + " · " +
            act.getString(R.string.home_foot_local),
        if (roomy) 12f else 11f, C["tertiary"]!!,
    ).apply {
        gravity = Gravity.CENTER
        // ⚠️ 末项的下间距**必须做成自己的 paddingBottom，不能是 marginBottom**：
        // `LinearLayout` 第一遍测量只累加 `marginTop + childHeight`，`marginBottom`
        // 不计入 → 权重块把它吃掉 → 页脚整块溢出容器被裁（横屏真机实测）。
        // 写成自身 padding 就是 childHeight 的一部分，一定被计入。
        // 手机竖屏：整列都是权重子项，页脚的高度由权重给定，下间距直接算进 [PW_FOOTER]，
        // 不能再叠一层 paddingBottom（那会撑破权重给的高度）。
        setPadding(0, 0, 0, if (roomy) d(20) else 0)
        layoutParams = LinearLayout.LayoutParams(
            MATCH, if (roomy) WRAP else 0, if (roomy) 0f else PW_FOOTER,
        ).apply {
            topMargin = d(if (roomy) 16 else 12)
        }
    })
}

// ── 区块：最近打开的归档 ─────────────────────────────────────────────

/**
 * 「最近打开的归档」区块 = **标题 + 卡片两块**（[Pair]），由调用处决定怎么摆。
 *
 * 拆开返回是因为手机竖屏下这两块的**高度是各自独立的权重**（[PW_CAP] / [PW_CARD]）：
 * 若包一层 `col`，卡片能拿到的就是「col 的高度 − 标题的自然高度」，标题那 ~27dp 不跟着
 * 缩放、比例就不准了。宽屏则由调用处把两块塞进一个 `col` 再并排。
 */
private fun homeRecentsParts(act: MainActivity, tab: TabState, cardBg: Int, roomy: Boolean): Pair<View, View> {
    fun d(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    val entries = RecentsStore.list(act)
    // 尾注 = 条数（派生，现查现画）；空列表不摆尾注，提示只在卡片里出现一次。
    val cap = homeCap(act, act.getString(R.string.home_recents),
        if (entries.isEmpty()) null else entries.size.toString(), roomy)
    // 卡片**定高**（宽屏 [LIST_CARD_H]、竖屏按设计稿比例 [PW_CARD]）、**行高固定**，
    // 装不下时在卡片内滚动 —— 窗口尺寸不随内容/剩余空间变（行若用 weight 平分：1 条时
    // 被拉成巨行、10 条时被压扁）。高度由调用处给。
    val card = FrameLayout(act).apply {
        background = GradientDrawable().apply {
            cornerRadius = d(if (roomy) 16 else 14).toFloat(); setColor(cardBg); setStroke(d(1), 0x14E0F9FF)
        }
        clipToOutline = true
    }
    val scroll = ScrollView(act).apply {
        // Honor/EMUI ROM：自定义 ScrollView 一开原生滚动条就会在 onDrawScrollBars 里 NPE。
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }
    card.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
    val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    scroll.addView(list)
    if (entries.isEmpty()) {
        list.addView(homeText(act, act.getString(R.string.home_recents_empty), 12f, C["tertiary"]!!).apply {
            gravity = Gravity.CENTER
            setPadding(0, d(20), 0, d(20))
        })
    } else {
        entries.forEachIndexed { i, e ->
            if (i > 0) list.addView(View(act).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, d(1)).apply {
                    marginStart = d(14); marginEnd = d(14)
                }
                setBackgroundColor(0x14E0F9FF)
            })
            list.addView(homeRecentRow(act, tab, e, roomy))
        }
    }
    return cap to card
}

private fun homeRecentRow(act: MainActivity, tab: TabState, e: RecentsStore.Entry, roomy: Boolean): LinearLayout {
    fun d(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    val f = e.file
    val ext = f.extension.lowercase()
    val row = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(
            d(if (roomy) 16 else 13), d(if (roomy) 12 else 10),
            d(if (roomy) 16 else 13), d(if (roomy) 12 else 10),
        )
        foreground = RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), null, null)
        isClickable = true
        isFocusable = true
        // v1 先落到归档所在目录（直接在此开预览要接一遍 openKey/互斥，后续再做）。
        setOnClickListener { f.parentFile?.let { act.enterBrowserAt(tab, it) } }
    }
    val fmtColor = FORMAT_COLORS[ext] ?: 0xFF5A5A5A.toInt()
    row.addView(homeText(act, ext.uppercase(), if (roomy) 11f else 10f, 0xF0FFFFFF.toInt()).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { cornerRadius = d(if (roomy) 8 else 7).toFloat(); setColor(fmtColor) }
        layoutParams = LinearLayout.LayoutParams(d(if (roomy) 54 else 46), d(if (roomy) 28 else 25))
    })
    val names = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = d(if (roomy) 14 else 11) }
    }
    names.addView(homeText(act, f.name, if (roomy) 14f else 12.5f, C["primary"]!!, bold = true).apply {
        setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
    })
    names.addView(homeText(act, f.parent ?: "", if (roomy) 11.5f else 10f, C["tertiary"]!!).apply {
        setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
    })
    row.addView(names)
    row.addView(homeText(
        act,
        fmt(fileSize(f)) + " · " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(e.time)),
        if (roomy) 12f else 10.5f, C["tertiary"]!!,
    ))
    return row
}

// ── 区块：书签 ───────────────────────────────────────────────────────

/** 「书签」区块 = **标题 + 卡片两块**，理由同 [homeRecentsParts]。 */
private fun homeBookmarksParts(act: MainActivity, tab: TabState, cardBg: Int, roomy: Boolean): Pair<View, View> {
    fun d(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    val bookmarks = act.bookmarks
    // 尾注 = 条数；空列表不摆尾注 —— 原来恒显示 home_bookmarks_empty：有书签时也在误导
    // 用户「长按可加书签」，空时又和卡片内的同一句提示重复。
    val cap = homeCap(act, act.getString(R.string.home_bookmarks),
        if (bookmarks.isEmpty()) null else bookmarks.size.toString(), roomy)
    // 与「最近」同一套：卡片定高（宽屏 [LIST_CARD_H] / 竖屏 [PW_CARD]）、行高固定、
    // 装不下时在卡片内滚动。高度由调用处给。
    val card = FrameLayout(act).apply {
        background = GradientDrawable().apply {
            cornerRadius = d(if (roomy) 16 else 14).toFloat(); setColor(cardBg); setStroke(d(1), 0x14E0F9FF)
        }
        clipToOutline = true
    }
    val scroll = ScrollView(act).apply {
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }
    card.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
    val box = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(d(6), d(6), d(6), d(6))
    }
    scroll.addView(box)
    if (bookmarks.isEmpty()) {
        box.addView(homeText(act, act.getString(R.string.home_bookmarks_empty), 12f, C["tertiary"]!!).apply {
            gravity = Gravity.CENTER
            setPadding(0, d(16), 0, d(16))
        })
    } else {
        bookmarks.forEach { path ->
            box.addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    d(if (roomy) 14 else 11), d(if (roomy) 12 else 10),
                    d(if (roomy) 14 else 11), d(if (roomy) 12 else 10),
                )
                foreground = RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), null, null)
                isClickable = true
                isFocusable = true
                setOnClickListener { act.enterBrowserAt(tab, File(path)) }
                addView(homeIcon(act, R.drawable.ic_folder, d(if (roomy) 17 else 15), C["accent"]!!))
                addView(homeText(act, File(path).name.ifEmpty { path },
                    if (roomy) 13.5f else 12.5f, C["secondary"]!!).apply {
                    layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = d(if (roomy) 12 else 10) }
                    setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                })
            })
        }
    }
    return cap to card
}

// ── 小构件 ───────────────────────────────────────────────────────────

/** 「标题 …… 尾注」的区块头。 */
private fun homeCap(act: MainActivity, title: String, tail: String?, roomy: Boolean): LinearLayout {
    fun d(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    val cap = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.BOTTOM
        // ⚠️ 这里**不放纵向 margin**：`LinearLayout` 第一遍测量不计子项的 `marginBottom`，
        // 权重卡片会把那 10dp 也吃掉、自己长高 10dp 并被裁。标题与卡片的间距改由**卡片自己
        // 的 `topMargin`** 出（权重子项的 margin 是计入的）—— 见调用处。
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            .apply { setMargins(d(2), 0, d(2), 0) }
    }
    cap.addView(homeText(act, title, if (roomy) 11.5f else 10.5f, C["tertiary"]!!).apply { letterSpacing = 0.12f })
    if (tail != null) {
        cap.addView(homeText(act, tail, if (roomy) 12f else 11f, C["tertiary"]!!).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = d(10) }
            gravity = Gravity.END
        })
    }
    return cap
}

/** 一行 n 个等重子项，[gap] 为间距（最后一个不加）。 */
private fun homeRow(act: MainActivity, vararg cells: View, gap: Int): LinearLayout {
    fun d(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
    cells.forEachIndexed { i, v ->
        val lp = LinearLayout.LayoutParams(0, MATCH, 1f)
        if (i < cells.size - 1) lp.marginEnd = d(gap)
        row.addView(v, lp)
    }
    return row
}

/**
 * hero 卡：**横向内容** —— 图标盒在左、标题+描述居中、角标在右。
 *
 * 两张卡并排时单卡在宽屏约 560dp、手机竖屏约 380dp，纵向内容（图标在上）会让右侧
 * 空掉一大片，所以统一横向。卡高由外部决定：宽屏 `weight=1` 撑满、手机竖屏 `wrap_content`。
 */
private fun homeHero(
    act: MainActivity,
    iconRes: Int,
    title: String,
    desc: String,
    tag: String?,
    teal: Boolean,
    fallbackBg: Int,
    roomy: Boolean,
    onClick: () -> Unit,
): View {
    fun d(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    val bg = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        if (teal) intArrayOf(0xFF1F3A41.toInt(), fallbackBg)
        else intArrayOf(0xFF2E3D42.toInt(), fallbackBg),
    ).apply {
        cornerRadius = d(if (roomy) 20 else 17).toFloat()
    }
    val box = if (roomy) d(66) else d(46)
    val glyph = if (roomy) d(34) else d(24)

    val row = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (roomy) setPadding(d(30), 0, d(30), 0) else setPadding(d(14), d(13), d(12), d(13))
    }
    row.addView(homeIcon(act, iconRes, glyph, C["accent"]!!).apply {
        background = GradientDrawable().apply {
            cornerRadius = d(if (roomy) 18 else 13).toFloat(); setColor(0x2E35ACC6)
        }
        layoutParams = LinearLayout.LayoutParams(box, box)
    })
    val textCol = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = d(if (roomy) 22 else 14) }
    }
    textCol.addView(homeText(act, title, if (roomy) 20f else 16.5f, C["primary"]!!, bold = true))
    textCol.addView(homeText(act, desc, if (roomy) 13.5f else 12f, C["secondary"]!!).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = d(if (roomy) 9 else 5) }
        // 保险：极端窄屏也不让描述把卡片撑成 5 行（横向内容通常 1–2 行）。
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    })
    row.addView(textCol)
    if (tag != null) {
        row.addView(homeText(act, tag, if (roomy) 11f else 10f, C["accent"]!!).apply {
            background = GradientDrawable().apply {
                cornerRadius = d(99).toFloat(); setColor(0x2E35ACC6)
            }
            setPadding(d(if (roomy) 11 else 9), d(5), d(if (roomy) 11 else 9), d(5))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = d(14) }
        })
    }

    return FrameLayout(act).apply {
        background = bg
        foreground = RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), null, null)
        isClickable = true
        isFocusable = true
        layoutParams = FrameLayout.LayoutParams(MATCH, WRAP)
        addView(row)
        setOnClickListener { onClick() }
    }
}

/** 快捷入口小卡：图标盒 + 标题 / 一行说明。 */
private fun homeMini(
    act: MainActivity,
    iconRes: Int,
    title: String,
    sub: String,
    bg: Int,
    roomy: Boolean,
    onClick: () -> Unit,
): LinearLayout {
    fun d(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    return LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (roomy) setPadding(d(16), d(18), d(16), d(18)) else setPadding(d(11), d(11), d(11), d(11))
        background = GradientDrawable().apply {
            cornerRadius = d(if (roomy) 16 else 14).toFloat(); setColor(bg); setStroke(d(1), 0x14E0F9FF)
        }
        foreground = RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), null, null)
        isClickable = true
        isFocusable = true
        layoutParams = FrameLayout.LayoutParams(MATCH, WRAP)
        addView(homeIcon(act, iconRes, d(if (roomy) 22 else 18), C["accent"]!!).apply {
            background = GradientDrawable().apply {
                cornerRadius = d(if (roomy) 13 else 11).toFloat(); setColor(0x2E35ACC6)
            }
            layoutParams = LinearLayout.LayoutParams(d(if (roomy) 44 else 36), d(if (roomy) 44 else 36))
                .apply { gravity = Gravity.CENTER }
        })
        val names = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        names.addView(homeText(act, title, if (roomy) 14.5f else 13f, C["primary"]!!, bold = true))
        names.addView(homeText(act, sub, if (roomy) 11f else 10f, C["tertiary"]!!).apply {
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = d(2) }
            setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        addView(names, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = d(if (roomy) 14 else 11) })
        setOnClickListener { onClick() }
    }
}

private fun homeIcon(act: MainActivity, res: Int, sizePx: Int, tint: Int): ImageView =
    ImageView(act).apply {
        setImageResource(res)
        setColorFilter(tint)
        layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
    }

private fun homeText(
    act: MainActivity,
    text: String,
    sp: Float,
    color: Int,
    bold: Boolean = false,
): TextView = TextView(act).apply {
    this.text = text
    textSize = sp
    setTextColor(color)
    if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
}
