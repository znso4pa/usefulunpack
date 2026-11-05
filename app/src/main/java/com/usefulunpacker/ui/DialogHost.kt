package com.usefulunpacker

import android.view.View

/**
 * 弹窗宿主 —— 「弹窗框架 + 触摸护盾 + 按 tab 扎根」整块，从 [MainActivity] 尾部**原样搬出来**
 * （UI 架构评价 P3）。全是 [MainActivity] 的 extension，所以调用点写法不变
 * （`dlg.belowTabs()` / `act.keepTabBarTappable(...)`）；只是因为散在 1300 行的 Activity
 * 尾部**没法测** —— 护盾跨 tab 死锁那个 bug 就藏在这里面。**没有行为改动。**
 *
 * 三块：
 * - **触摸护盾**（`@id/touchShield`）：确认类弹窗打开时挡住「tab 栏以外的一切」。按
 *   「谁申请谁释放」用集合记账（[MainActivity.shieldHolders] 仍留在 [MainActivity] 上，
 *   因为它是**按实例**的状态，扩展属性没有背后字段）。
 * - **按 tab 扎根**（[rootDialogToTab] / [syncRootedDialogs]）：弹窗是 Activity 的窗口、
 *   不属于任何 tab；切 tab 时按 [RootedDialog.wantsShield] 藏 / 放，回来时状态还在。
 * - **两种停靠**：[keepTabBarTappable]（居中，保留 tab 栏可点）与 [belowTabBar]
 *   （竖屏贴底 / 横屏居中浮动卡）。
 */

/**
 * Honor/EMUI touch-state workaround: a dismissed dialog (its fast-scroll
 * list / scrollview) can leave the ViewPager2 unable to intercept horizontal
 * swipes, so tab switching dies until restart. Re-arming the pager's input
 * state on dismiss resets the internal RecyclerView's touch handling.
 * [also] runs first if provided.
 *
 * ⚠️ **本函数独占 dismiss 监听器**：`Dialog.setOnDismissListener` 只有一个槽位，
 * 这里一设就把调用方之前设的**整个覆盖掉**。所以任何「关闭时要做的事」都必须从
 * [also] 传进来，不能自己再 `setOnDismissListener`。真机上踩过两次：
 * 全局搜索的 worker 线程永不 interrupt（关掉搜索框后继续把整个存储扫完）、
 * 音频播放对话框的 MediaPlayer 永不 release（关掉后还在响）。
 */
internal fun MainActivity.resetPagerInputOnDialogDismiss(
    dlg: android.app.Dialog,
    also: (() -> Unit)? = null,
    releaseShield: Boolean = false,
) {
    dlg.setOnDismissListener {
        also?.invoke()
        // 触摸护盾按「谁申请谁释放」配对（集合幂等，重复释放是 no-op）。
        if (releaseShield) releaseContentBlock(dlg)
        viewPager.setUserInputEnabled(false)
        viewPager.setUserInputEnabled(true)
    }
}

/** 申请挡住**除 tab 栏以外的一切**（工具栏 + 内容区）。配对释放见 [releaseContentBlock]。 */
internal fun MainActivity.blockContentBehind(dlg: android.app.Dialog) {
    if (shieldHolders.add(dlg)) {
        findViewById<View>(R.id.touchShield)?.visibility = View.VISIBLE
    }
}

/** 释放一次；集合空了护盾才真正收起（重复释放是 no-op）。 */
internal fun MainActivity.releaseContentBlock(dlg: android.app.Dialog) {
    if (shieldHolders.remove(dlg) && shieldHolders.isEmpty()) {
        findViewById<View>(R.id.touchShield)?.visibility = View.GONE
    }
}

// ── Per-tab dialog rooting ───────────────────────────────────────────
// A tab-safe dialog is a window on the *Activity*, owned by no tab, so
// without this it stayed floating over whichever tab you switched to. Every
// dialog opened from a tab is registered to that tab and hidden while a
// different tab is active; returning to its tab shows it again with its
// state (search results, editor text, ...) intact. Progress cards already
// do this via OpOverlay and are not affected.
internal class RootedDialog(
    val dlg: android.app.Dialog,
    val ownerTabId: Int,
    /** 这个弹窗要不要护盾（进度对话框不要）。切 tab 藏/放它时按这个配对。 */
    val wantsShield: Boolean,
) {
    var hidden = false
}

/** Pin [dlg] to the tab that is active right now. Safe before or after `show()`. */
internal fun MainActivity.rootDialogToTab(dlg: android.app.Dialog, wantsShield: Boolean = true) {
    if (tabs.isEmpty()) return
    val entry = RootedDialog(dlg, activeTab.tabId, wantsShield)
    rootedDialogs.add(entry)
    dlg.window?.decorView?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) {
            rootedDialogs.remove(entry)
            v.removeOnAttachStateChangeListener(this)
        }
    })
}

/** Hide rooted dialogs whose tab is no longer active and re-show the ones
 *  whose tab just came back. Called from the pager's onPageSelected. */
internal fun MainActivity.syncRootedDialogs() {
    if (tabs.isEmpty()) return
    val activeId = activeTab.tabId
    for (entry in rootedDialogs) {
        val shouldShow = entry.ownerTabId == activeId
        if (shouldShow == !entry.hidden) continue
        setRootedDialogHidden(entry.dlg, !shouldShow)
        entry.hidden = !shouldShow
        // 护盾必须跟着弹窗的可见性走：藏起来时它还在旧 tab 里，护盾却盖在新
        // tab 上 —— 新 tab 没有任何东西能关掉它，整个界面死锁（真机踩过）。
        // 按 entry.wantsShield 配对：进度对话框（不要护盾）两头都是 no-op。
        if (shouldShow) {
            if (entry.wantsShield) blockContentBehind(entry.dlg)
        } else {
            releaseContentBlock(entry.dlg)
        }
    }
}

internal fun MainActivity.setRootedDialogHidden(dlg: android.app.Dialog, hidden: Boolean) {
    val w = dlg.window ?: return
    val decor = w.decorView
    if (hidden) {
        // Hide the decor AND make the window untouchable, otherwise the
        // invisible window would still swallow taps in its frame on the tab
        // now in front of it.
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        decor.visibility = View.GONE
    } else {
        decor.visibility = View.VISIBLE
        w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
    }
}

/** Dismiss every dialog rooted to a closed tab. */
internal fun MainActivity.dismissRootedDialogsFor(tabId: Int) {
    val doomed = rootedDialogs.filter { it.ownerTabId == tabId }
    rootedDialogs.removeAll { it.ownerTabId == tabId }
    doomed.forEach { runCatching { it.dlg.dismiss() } }
}

/**
 * 弹窗落位的**唯一配对点**：窗口标志 + 触摸护盾 + 按 tab 扎根 + dismiss 收尾。
 *
 * [keepTabBarTappable] 与 [belowTabBar] 只负责各自的几何 / 明暗，配对全部走这里。
 * 这一族 bug（护盾跨 tab 死锁、切 tab 后弹窗浮在别的窗口上、dismiss 监听器被覆盖）
 * 的共同根因是：**同一件事有 3 套并行机制**（窗口标志 / 护盾视图 / rootedDialogs），
 * 配对点散在各处，谁少写一行谁出 bug。现在配对只有一份。
 *
 * 三条不变式：
 *  1. 窗口外点击必须落到 tab 栏（[FLAG_NOT_TOUCH_MODAL]）→ 所以绝不能「点外面关闭」；
 *  2. 要护盾就**当场**申请，并由 [resetPagerInputOnDialogDismiss] 配对释放
 *     （集合记账，重复释放是 no-op）；
 *  3. 扎根时的 `wantsShield` 必须与这里一致 —— 不一致就是「切 tab 后护盾盖在新 tab 上、
 *     没人能关掉」那次死锁。
 */
private fun MainActivity.placeDialog(
    dlg: android.app.Dialog,
    rootToTab: Boolean,
    shield: Boolean,
    also: (() -> Unit)?,
) {
    dlg.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
    // The whole point is that an outside tap belongs to the UI behind (the
    // tab strip), so the dialog must never treat it as "tap outside to
    // dismiss". On some ROMs (Honor/EMUI) the outside-down still raises
    // ACTION_OUTSIDE and dismisses while the tab click is lost — the dialog
    // vanished but the window never switched. Explicitly disable it.
    dlg.setCanceledOnTouchOutside(false)
    if (rootToTab) rootDialogToTab(dlg, wantsShield = shield)
    // 默认把「tab 栏以外」全挡上（触摸护盾）：确认类弹窗打开时不该还能点到背后的
    // 文件列表 —— 那是误触。**进度对话框要传 false**：本应用支持操作排队后继续
    // 浏览（批量解压/压缩/扫描跑着的时候用户照样能翻目录），挡住内容反而是退化。
    if (shield) blockContentBehind(dlg)
    resetPagerInputOnDialogDismiss(dlg, also, releaseShield = shield)
}

/** Let the tab strip stay bright and tappable while a settings dialog is open.
 *
 *  A plain `AlertDialog` is touch-modal (its window consumes pointers outside
 *  its own bounds and discards them) and the dialog theme dims the whole
 *  screen, so the tab bar cannot be tapped and every tab switch is dead while
 *  settings is up — the dialog "occupies" the tab strip.
 *
 *  Two window flags fix that without moving or restyling the dialog:
 *   * `FLAG_NOT_TOUCH_MODAL` — touches outside the dialog's content go to
 *     whatever is beneath (the tab strip), instead of being swallowed;
 *   * clear `FLAG_DIM_BEHIND` — the theme's 60% dim is also removed, so the
 *     strip stays visible rather than looking disabled.
 *  The pager input re-arm rides along, because the same dismiss can leave
 *  ViewPager2 unable to swipe (see [resetPagerInputOnDialogDismiss]).
 *
 *  `rootToTab` (default) pins the dialog to the current tab; pass false for
 *  app-global dialogs (settings / help / recycle bin / installer) that should
 *  stay put across tab switches. */
internal fun MainActivity.keepTabBarTappable(
    dlg: android.app.Dialog,
    rootToTab: Boolean = true,
    // 必须排在 `also` 之前：调用方若用尾随 lambda 传 also，排在后面会把尾记抢走。
    blockBehind: Boolean = true,
    also: (() -> Unit)? = null,
) {
    dlg.window?.let { w ->
        // 只有「居中 + 亮 tab 栏」这一件事是 keepTabBarTappable 独有的几何/明暗处理；
        // 标志 / 护盾 / 扎根 / 收尾全交给 placeDialog（配对只有一份）。
        w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.setDimAmount(0f)
    }
    placeDialog(dlg, rootToTab = rootToTab, shield = blockBehind, also = also)
}

/** Bottom-anchored variant of [keepTabBarTappable] for **tall** dialogs.
 *
 *  A centered dialog capped at `dialog_max_height` can still reach and cover
 *  the tab strip on shorter screens, so `FLAG_NOT_TOUCH_MODAL` alone is not
 *  enough — the window has to be physically kept below the strip. This moves
 *  the dialog to the bottom and sizes it to the space under
 *  status+toolbar+tabs, with a local scrim instead of the system dim (which
 *  would darken the strip too). Mirrors what `PreviewFlow`/`BatchExtract`
 *  used to hand-roll; those two now call this so the geometry cannot drift.
 *
 *  `swipeArea` keeps some of the pager visible above the sheet so the user
 *  can still swipe between windows. */
internal fun MainActivity.belowTabBar(
    dlg: android.app.Dialog,
    swipeAreaDp: Float = 130f,
    fitContent: Boolean = false,
    rootToTab: Boolean = true,
    also: (() -> Unit)? = null,
) {
    registerPlacement(dlg, swipeAreaDp, fitContent)
    applyDialogGeometry(dlg, swipeAreaDp, fitContent)
    // 弹窗一律要护盾（除 tab 栏外全挡）；配对全在 placeDialog 里。
    placeDialog(dlg, rootToTab = rootToTab, shield = true, also = also)
}

/**
 * `belowTabs` 的**几何**（方向相关：横屏居中卡片 / 竖屏贴底）。
 *
 * 单独抽出来是因为它必须能在**旋转后重算**：Activity 声明了 `configChanges`，转屏不重建
 * 视图树，没人会替开着的弹窗重算 —— 真机上横屏开着的搜索框转竖屏后仍是 480dp 宽、
 * 还按横屏的 y 偏移居中（和终端层的像素偏移、hub 的骨架是同一类坑）。
 */
private fun MainActivity.applyDialogGeometry(
    dlg: android.app.Dialog,
    swipeAreaDp: Float,
    fitContent: Boolean,
) {
    val w = dlg.window ?: return
    val dm = resources.displayMetrics
    val density = dm.density
    val topInset = viewPager?.takeIf { it.top > 0 }?.top
        ?: (24f * density).toInt() + (106f * density).toInt()  // status 24dp + toolbar 56dp + tabs 50dp
    val landscape = resources.configuration.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val sheetW = if (landscape) {
        (DIALOG_LANDSCAPE_W_DP * density).toInt()
            .coerceAtMost(dm.widthPixels - (2 * 16f * density).toInt())
    } else {
        minOf(dm.widthPixels, resources.getDimensionPixelSize(R.dimen.dialog_max_width))
    }
    val sheetH = if (landscape) {
        (dm.heightPixels - topInset - (2 * 20f * density).toInt()).coerceAtLeast(0)
    } else {
        (dm.heightPixels - topInset - (swipeAreaDp * density).toInt()).coerceAtLeast(0)
    }

    // ── 几何 / 明暗：与调用顺序无关，当场生效 ────────────────────────────
    if (landscape) {
        // 可用区 = [topInset, 屏高]；Gravity.CENTER 的 y 是**相对中心的偏移**，
        // 往下推 topInset/2 正好把中心从屏幕中心挪到可用区中心。
        w.setGravity(android.view.Gravity.CENTER)
        w.attributes = w.attributes.apply { y = topInset / 2 }
        // 卡片外面用系统压暗，不再用窗口自带的局部 scrim —— 局部 scrim 只能盖住窗口
        // 矩形，浮动卡外一圈会是亮的，没有模态感。tab 条被压暗但仍可点
        //（NOT_TOUCH_MODAL 保留）。卡片外观交给对话框主题的圆角 windowBackground。
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.setDimAmount(0.5f)
    } else {
        w.setGravity(android.view.Gravity.BOTTOM)
        // 竖屏仍是贴底大窗 + 窗口自带的局部 scrim（不动 tab 条的亮度）
        w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.setDimAmount(0f)
        w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x99000000.toInt()))
    }

    /**
     * 尺寸**必须在 show 之后设**：`AlertDialog.show()` 会走
     * onCreate → AlertController.installContent() → setContentView → installDecor，
     * 把窗口的 LayoutParams 重置成 WRAP_CONTENT —— show 之前 setLayout 设的尺寸
     * **整个丢掉**（真机实测：show 前设 1800×2344，窗口实际 1463×1111 = 内容自然尺寸）。
     *
     * 所以同一个 Placement 之前会因为调用顺序不同而几何不同：`show().also { belowTabs() }`
     * 生效，`belowTabs(); show()` 失效。现在统一在「decor 挂上窗口之后」补一次，
     * 两种写法结果一致。
     */
    fun applySize() {
        // 可用高度取**父窗口当前的实际高度**（root 会被输入法压矮），不是屏幕高度 ——
        // 拿 displayMetrics 算出来的高度在键盘弹出后就是超的，超出的那截收不到触摸。
        val rootH = findViewById<View>(R.id.root)?.height ?: 0
        val availH = (rootH - topInset - (2 * 20f * density).toInt()).coerceAtLeast((160 * density).toInt())
        if (!fitContent) {
            // 长内容：给**面板**（AlertDialog 的 android.R.id.content 第一层子）设精确高度，
            // 不碰窗口。窗口是 WRAP，会跟着面板量；面板内部列表带权重，自然分到剩余高度。
            // （给窗口设死高度不行：AlertController 的 customPanel 是 WRAP，内容不会撑开，
            // 列表会塌成 0 高 —— 真机踩过。）
            // 宽高都由窗口定死（show 之后 setLayout 有效）。只定宽不定高的话窗口会比面板
            // 高出装饰层那点内边距，面板居中 → 底部露出一条窗口底色，看着像从底部呼出的。
            w.setLayout(sheetW, availH)
            fitDialogPanel(dlg)
            return
        }
        // 短内容贴自身高度（1 条的回收站不该开成 near-full-screen）。先量再定 EXACT 高。
        val decor = w.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(sheetW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(availH, View.MeasureSpec.AT_MOST))
        val measured = decor.measuredHeight
        w.setLayout(sheetW, if (measured in 1 until availH) measured else availH)
    }
    if (dlg.isShowing) {
        applySize()
    } else {
        // 用 attach 监听而不是 setOnShowListener：那个槽位调用方也在用
        //（PreviewFlow 的按钮重绑），抢了就把它顶掉 —— 和 dismiss 监听器同一个坑。
        val decor = w.decorView
        decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                v.post { applySize() }
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })
    }

    // ⚠️ 输入法弹出时父窗口（Activity）被压矮（横屏实测 1944 → 1121px），而窗口是按
    // **当时**的可用高度量出来的、之后不会自己重算 —— 超出父窗口的那一截**收不到触摸**
    //（真机：横屏搜出结果后点列表下半部分毫无反应，探针确认 itemClick 没触发）。
    // insets 变化时请求一次重新布局，窗口就会按新的可用高度重新量。
    w.decorView.setOnApplyWindowInsetsListener { v, insets ->
        // 输入法弹出/收起 → root 高度变了 → 重新按当前可用高度算一次面板尺寸
        v.post { applySize() }
        insets
    }

}
/** 记录「几何与方向相关」的弹窗，转屏时按新方向重算（[reapplyDialogPlacements]）。 */
private val placedDialogs =
    mutableListOf<Triple<java.lang.ref.WeakReference<android.app.Dialog>, Float, Boolean>>()

private fun registerPlacement(dlg: android.app.Dialog, swipeAreaDp: Float, fitContent: Boolean) {
    placedDialogs.removeAll { it.first.get() == null || it.first.get() === dlg }
    placedDialogs.add(Triple(java.lang.ref.WeakReference(dlg), swipeAreaDp, fitContent))
}

/**
 * 方向变了 → 给所有还在显示的 `belowTabs` 弹窗重算几何
 * （`keepTabsTappable` 的居中弹窗是 WRAP + CENTER，不受方向影响）。
 *
 * ⚠️ **必须 post 到下一帧**：`onConfigurationChanged` 是在新方向还没走完布局时回调的，
 * 此刻 `R.id.root.height` 还是旧方向的值 —— 直接算等于拿旧尺寸又设了一遍。
 */
internal fun MainActivity.reapplyDialogPlacements() {
    window?.decorView?.post { reapplyDialogPlacementsNow() }
}

private fun MainActivity.reapplyDialogPlacementsNow() {
    val iter = placedDialogs.iterator()
    while (iter.hasNext()) {
        val (ref, swipeAreaDp, fitContent) = iter.next()
        val d = ref.get()
        if (d == null || !d.isShowing) { iter.remove(); continue }
        applyDialogGeometry(d, swipeAreaDp, fitContent)
    }
}

/**
 * 横屏居中对话框的宽度（dp）—— 竖屏比例，像一台手机立在内容区正中
 * （用户定稿：「横屏在除掉 tab 栏的窗口里面居中」，宽 ~480dp）。
 * 竖屏仍走 dialog_max_width 的贴底大窗，见 [belowTabBar]。
 */
private const val DIALOG_LANDSCAPE_W_DP = 480f

/**
 * 把 AlertDialog 的**面板**设成精确高度并居中（不碰窗口的 LayoutParams）。
 *
 * 为什么是面板而不是窗口：`AlertDialog.show()` 会重置窗口的 LayoutParams（show 前设的
 * 尺寸全丢），而窗口给了死高度后 `AlertController` 的 customPanel 是 WRAP、内容不会
 * 跟着撑开 —— 列表（0dp + weight=1）会塌成 0 高。面板（`android.R.id.content` 的第一
 * 层子，即 AlertDialogLayout）是普通视图，设高度不会被打回，内部权重也照常分配。
 *
 * 找不到面板就静默返回（布局结构变了也不至于崩）。
 */
private fun MainActivity.fitDialogPanel(dlg: android.app.Dialog) {
    val decor = dlg.window?.decorView ?: return
    // ⚠️ 对话框主题自带最小宽度（Material 按屏宽百分比算，横屏下约 548dp），
    // 不清掉的话面板设成 480dp 也会被它顶回去 —— 同一批弹窗宽度就对不齐了。
    if (decor.minimumWidth != 0) decor.minimumWidth = 0
    val content = decor.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
    // ⚠️ `android:id/content` 这个 FrameLayout 是 **WRAP + 顶部对齐**的：窗口定了高度，
    // 它只包裹面板那么高，下面剩下的窗口区域露的是被压暗的**背后界面** —— 看起来就是
    // 卡片下面多出一条（真机采样：卡片 1140px、窗口 1473px，底部 318px 是背景色）。
    // 先让它撑满窗口，面板再撑满它，整张卡才贴住窗口。
    (content.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { clp ->
        if (clp.height != android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
            clp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            content.layoutParams = clp
        }
    }
    val panel = content.getChildAt(0) ?: return
    val lp = panel.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
    // 面板撑满窗口：窗口已经定死尺寸，面板用 MATCH_PARENT 才贴合（用固定高度的话
    // 窗口比面板高的那部分会在底部露出一条底色）。
    if (lp.width != android.view.ViewGroup.LayoutParams.MATCH_PARENT ||
        lp.height != android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
        lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        lp.gravity = android.view.Gravity.CENTER
        panel.layoutParams = lp
    }
    // ⚠️ 平台 `AlertDialogLayout` 里有一个给 setMessage 用的**空壳** contentPanel，
    // 它带 weight=1；而放自定义视图的 customPanel 是 WRAP 且没有权重 —— 剩余高度全被
    // 那个空壳吃掉，自定义内容只占上半截，下面留一块面板底色（看着像底部又多了一条）。
    // 两个 id 都是 @hide（`android.R.id.customPanel` 编不过），所以按结构识别：
    //   * 没有任何子视图的空壳 → 不许吃权重；
    //   * 子树最大的那个（= 自定义内容）→ 拿走权重。
    val ll = panel as? android.widget.LinearLayout ?: return
    var contentChild: android.view.View? = null
    var bestCount = -1
    for (i in 0 until ll.childCount) {
        val c = ll.getChildAt(i)
        val clp = c.layoutParams as? android.widget.LinearLayout.LayoutParams ?: continue
        val n = descendants(c)
        if (n > bestCount) { bestCount = n; contentChild = c }
        if (n == 0 && clp.weight != 0f) {
            clp.weight = 0f; c.layoutParams = clp
        }
    }
    contentChild?.let { c ->
        val clp = c.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return@let
        if (clp.weight != 1f || clp.height != 0) {
            clp.height = 0; clp.weight = 1f; c.layoutParams = clp
        }
        // ⚠️ `setView()` 给自定义视图的是 MATCH_PARENT × **WRAP** —— 面板撑满了，
        // 内容却只占它的自然高度，下面那一截露面板底色（就是「底部多一条」）。
        // 把内容根也设成 MATCH_PARENT。
        val holder = c as? android.view.ViewGroup ?: return@let
        for (k in 0 until holder.childCount) {
            val v = holder.getChildAt(k)
            val vlp = v.layoutParams as? android.widget.FrameLayout.LayoutParams ?: continue
            if (vlp.height != android.view.ViewGroup.LayoutParams.MATCH_PARENT) {
                vlp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                v.layoutParams = vlp
            }
        }
    }
}

/** 子树节点数（含自身）。只用来判断「谁是真正的内容面板」。 */
private fun descendants(v: android.view.View): Int {
    if (v !is android.view.ViewGroup) return 1
    var n = 1
    for (i in 0 until v.childCount) n += descendants(v.getChildAt(i))
    return n
}

/** Unwrap `ContextWrapper`s to reach the hosting [MainActivity], if any. */
private fun android.content.Context.findMainActivity(): MainActivity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is MainActivity) return c
        c = c.baseContext
    }
    return if (c is MainActivity) c else null
}

/**
 * Tab-safe variants callable from anywhere a [android.app.Dialog] is in scope,
 * regardless of the receiver's static type. Dispatch to the [MainActivity]
 * members so a free function taking `AppCompatActivity` (e.g. FormatPicker) does
 * not have to cast. Falling back to plain [android.app.Dialog.show] semantics
 * when no MainActivity hosts the dialog keeps these safe to call unconditionally.
 */
internal fun android.app.Dialog.keepTabsTappable(
    rootToTab: Boolean = true,
    blockBehind: Boolean = true,
    also: (() -> Unit)? = null,
) {
    context.findMainActivity()?.keepTabBarTappable(this, rootToTab, blockBehind, also)
}

/**
 * Bottom-anchored variant for tall dialogs; see `MainActivity.belowTabBar`.
 * `fitContent = true` makes the sheet hug short content (capped below the tab
 * strip) instead of always taking the full available height. `rootToTab`
 * (default) pins the dialog to the tab that opened it; false keeps it global.
 */
internal fun android.app.Dialog.belowTabs(
    swipeAreaDp: Float = 130f,
    fitContent: Boolean = false,
    rootToTab: Boolean = true,
    also: (() -> Unit)? = null,
) {
    context.findMainActivity()?.belowTabBar(this, swipeAreaDp, fitContent, rootToTab, also)
}
