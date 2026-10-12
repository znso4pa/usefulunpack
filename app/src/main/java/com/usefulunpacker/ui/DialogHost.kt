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

/** Honor/EMUI touch-state workaround: a dismissed dialog (its fast-scroll
 *  list / scrollview) can leave the ViewPager2 unable to intercept horizontal
 *  swipes, so tab switching dies until restart. Re-arming the pager's input
 *  state on dismiss resets the internal RecyclerView's touch handling.
 *  [also] runs first if provided. */
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
        w.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )
        w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.setDimAmount(0f)
    }
    // The whole point is that an outside tap belongs to the UI behind (the
    // tab strip), so the dialog must never treat it as "tap outside to
    // dismiss". On some ROMs (Honor/EMUI) the outside-down still raises
    // ACTION_OUTSIDE and dismisses while the tab click is lost — the dialog
    // vanished but the window never switched. Explicitly disable it.
    dlg.setCanceledOnTouchOutside(false)
    if (rootToTab) rootDialogToTab(dlg, wantsShield = blockBehind)
    // 默认把「tab 栏以外」全挡上（触摸护盾）：确认类弹窗打开时不该还能点到背后的
    // 文件列表 —— 那是误触。**进度对话框要传 false**：本应用支持操作排队后继续
    // 浏览（批量解压/压缩/扫描跑着的时候用户照样能翻目录），挡住内容反而是退化。
    if (blockBehind) blockContentBehind(dlg)
    resetPagerInputOnDialogDismiss(dlg, also, releaseShield = blockBehind)
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
    dlg.window?.let { w ->
        val dm = resources.displayMetrics
        val density = dm.density
        val topInset = viewPager?.takeIf { it.top > 0 }?.top
            ?: (24f * density).toInt() + (106f * density).toInt()  // status 24dp + toolbar 56dp + tabs 50dp
        val landscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE

        // 触摸语义（用户定稿）：**除了 tab 栏什么都不能点**。保留
        // NOT_TOUCH_MODAL 让 tab 栏仍可点（切窗口时弹窗跟它那个 tab 走），
        // 同时用触摸护盾盖住工具栏 + 内容区（`@id/touchShield` 顶到
        // `dividerTabBar` 之下）—— 背后一律吃不到触摸。
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        blockContentBehind(dlg) // 配对释放见 resetPagerInputOnDialogDismiss
        dlg.setCanceledOnTouchOutside(false)

        if (landscape) {
            // ── 横屏：居中的竖屏比例浮动窗（用户定稿）──────────────────
            // 「不要在底部呼出……横屏在除掉 tab 栏的窗口里面居中」。
            // 可用区 = [topInset, 屏高]；Gravity.CENTER 的 y 是**相对中心的偏移**，
            // 往下推 topInset/2 正好把中心从屏幕中心挪到可用区中心。
            // 底部不再留 swipeArea —— 卡片四周都是空的，pager 照样能滑。
            val contentH = dm.heightPixels - topInset
            val sheetW = (DIALOG_LANDSCAPE_W_DP * density).toInt()
                .coerceAtMost(dm.widthPixels - (2 * 16f * density).toInt())
            val maxH = (contentH - (2 * 20f * density).toInt()).coerceAtLeast(0)
            if (fitContent) {
                // 短内容贴自身高度（1 条的回收站不能开成 near-full-screen），
                // 长内容封顶 maxH。先量再定 EXACT 高，避免第一帧在 (0,0) 闪一下
                // （gravity 要等包裹高度算出来才解析得了 —— 竖屏分支同一条教训）。
                val decor = w.decorView
                decor.measure(
                    View.MeasureSpec.makeMeasureSpec(sheetW, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
                val measured = decor.measuredHeight
                w.setLayout(sheetW, if (measured in 1 until maxH) measured else maxH)
            } else {
                w.setLayout(sheetW, maxH)
            }
            w.setGravity(android.view.Gravity.CENTER)
            w.attributes = w.attributes.apply { y = topInset / 2 }
            // 卡片外面用系统压暗，不再用窗口自带的局部 scrim —— 局部 scrim 只能
            // 盖住窗口矩形，浮动卡外一圈会是亮的，没有模态感。tab 条被压暗但仍可点
            //（NOT_TOUCH_MODAL 保留）。decor 背景也不再覆盖：卡片外观交给对话框主题
            // 的圆角 windowBackground（`Theme.UsefulUnpack.Dialog` 的
            // `@drawable/dialog_bg_rounded`）。
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.5f)
        } else {
            // ── 竖屏：底部呼出（定稿不动）──────────────────────────────
            val swipeArea = (swipeAreaDp * density).toInt()
            val sheetW = minOf(dm.widthPixels, resources.getDimensionPixelSize(R.dimen.dialog_max_width))
            val sheetH = (dm.heightPixels - topInset - swipeArea).coerceAtLeast(0)
            w.setGravity(android.view.Gravity.BOTTOM)
            // Same as keepTabBarTappable: a bottom-anchored window is still
            // touch-MODAL by default, so taps on the tab strip above it get
            // swallowed instead of reaching the tabs. The physical offset is not
            // enough on its own — without this flag Global Search / a non-empty
            // recycle bin block window switching while the terminal (which uses
            // keepTabsTappable) did not.
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0f)
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x99000000.toInt()))
            if (fitContent) {
                // Short content must hug its own height — a 1-entry recycle bin
                // must not open a near-full-screen sheet with a big empty area —
                // while tall content still has to stop below the tab strip.
                //
                // Measure the decor BEFORE show and set an EXACT height. Leaving
                // the window at WRAP_CONTENT lets the WindowManager position it
                // at (0,0) for the first frame (gravity cannot be resolved until
                // the wrapped height is known), which reads as a top-left flash
                // and swallows tab taps for that instant. Measuring first makes
                // the very first frame the final one. AlertDialogLayout measures
                // the custom panel against windowHeight − title − buttons, so a
                // scrolling child (which MUST be wrap_content, not height=0 +
                // weight) gets the leftover height and scrolls.
                val decor = w.decorView
                val widthSpec = View.MeasureSpec.makeMeasureSpec(sheetW, View.MeasureSpec.AT_MOST)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(sheetH, View.MeasureSpec.AT_MOST)
                decor.measure(widthSpec, heightSpec)
                val measured = decor.measuredHeight
                w.setLayout(sheetW, if (measured in 1 until sheetH) measured else sheetH)
            } else {
                w.setLayout(sheetW, sheetH)
            }
        }
    }
    // Same reason as keepTabBarTappable: outside taps go to the tab strip,
    // never "dismiss this dialog".
    if (rootToTab) rootDialogToTab(dlg, wantsShield = true)
    resetPagerInputOnDialogDismiss(dlg, also, releaseShield = true)
}

/**
 * 横屏居中对话框的宽度（dp）—— 竖屏比例，像一台手机立在内容区正中
 * （用户定稿：「横屏在除掉 tab 栏的窗口里面居中」，宽 ~480dp）。
 * 竖屏仍走 dialog_max_width 的贴底大窗，见 [belowTabBar]。
 */
private const val DIALOG_LANDSCAPE_W_DP = 480f

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
