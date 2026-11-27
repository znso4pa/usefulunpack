package com.usefulunpacker

import android.view.View

/** WindowManager dimming affects the tab strip too. Keep the scrim inside the content viewport. */
internal fun MainActivity.setDialogDimmed(owner: Any, dimmed: Boolean) {
    if (dimmed) dialogDimOwners.add(owner) else dialogDimOwners.remove(owner)
    val dim = findViewById<View>(R.id.dialogDim) ?: return
    if (dialogDimOwners.isNotEmpty()) {
        dim.bringToFront()
        findViewById<View>(R.id.touchShield)?.bringToFront()
    }
    val motion = dialogScrimMotion ?: DialogScrimMotion(dim).also { dialogScrimMotion = it }
    motion.setDimmed(dialogDimOwners.isNotEmpty(), animate = !isFinishing && !isDestroyed)
}

internal fun MainActivity.configureTabSafeDrawer() {
    drawer.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
        override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
            if (!syncingDrawerTab && slideOffset > 0f && tabs.isNotEmpty()) drawerOpenTabs.add(activeTab.tabId)
            setDialogDimmed(drawer, slideOffset > 0f)
        }
        override fun onDrawerOpened(drawerView: View) {
            if (!syncingDrawerTab && tabs.isNotEmpty()) drawerOpenTabs.add(activeTab.tabId)
        }
        override fun onDrawerClosed(drawerView: View) {
            if (!syncingDrawerTab && tabs.isNotEmpty()) drawerOpenTabs.remove(activeTab.tabId)
            setDialogDimmed(drawer, false)
        }
    })
    findViewById<View>(R.id.root)?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        val sidebar = findViewById<View>(R.id.drawerContent) ?: return@addOnLayoutChangeListener
        val params = sidebar.layoutParams as? androidx.drawerlayout.widget.DrawerLayout.LayoutParams ?: return@addOnLayoutChangeListener
        val top = findViewById<View>(R.id.dividerTabBar)?.bottom ?: 0
        if (params.topMargin != top) { params.topMargin = top; sidebar.layoutParams = params }
    }
}

internal fun MainActivity.syncDrawerToTab() {
    if (tabs.isEmpty()) return
    syncingDrawerTab = true
    try {
        drawerOpenTabs.retainAll(tabs.map { it.tabId }.toSet())
        if (activeTab.tabId in drawerOpenTabs) drawer.openDrawer(androidx.core.view.GravityCompat.START, false)
        else drawer.closeDrawer(androidx.core.view.GravityCompat.START, false)
    } finally { syncingDrawerTab = false }
}

/** Prepare before show(): the first visible frame must already have its final placement. */
internal fun prepareDialogWindow(dlg: android.app.Dialog) {
    val activity = dlg.context.findMainActivity() ?: return
    val window = dlg.window ?: return
    val decor = window.decorView
    if (decor.getTag(R.id.dialogPresentation) == true) return
    decor.setTag(R.id.dialogPresentation, true)
    val openingTabId = activity.tabs.getOrNull(activity.activeTabIndex)?.tabId?.let { activeId ->
        AppDiagnostics.currentTrace()?.chain?.let { activity.dialogChainTabs.getOrPut(it) { activeId } } ?: activeId
    }
    window.setWindowAnimations(0)
    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
    window.setDimAmount(0f)
    dlg.setCanceledOnTouchOutside(false)
    // Never keep a touch-modal window invisible while waiting for layout.
    decor.alpha = 1f
    var frames = 0
    var lastViewport: Pair<Int, Int>? = null
    var attachmentGeneration = 0
    var root = activity.findViewById<View>(R.id.root)
    val viewportListener = View.OnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            val generation = attachmentGeneration
            view.post {
                if (generation == attachmentGeneration && !activity.isFinishing && !activity.isDestroyed &&
                    dlg.isShowing && decor.isAttachedToWindow && view.width > 0 && view.height > 0) {
                    lastViewport = view.width to view.height
                    DialogMotion.settle(dlg)
                    activity.applyPresentationGeometry(dlg)
                }
            }
        }
    }
    var firstDraw = DialogFirstDrawGate()
    val firstFrame = object : android.view.ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            if (activity.isFinishing || activity.isDestroyed || !dlg.isShowing) {
                decor.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
            frames++
            val old = window.attributes.let { listOf(it.width, it.height, it.gravity, it.y) }
            if (frames == 1) activity.applyPresentationGeometry(dlg)
            val root = activity.findViewById<View>(R.id.root)
            lastViewport = root?.let { it.width to it.height }
            val attrs = window.attributes
            val geometryChanged = old != listOf(attrs.width, attrs.height, attrs.gravity, attrs.y)
            if (firstDraw.defer(geometryChanged || decor.isLayoutRequested)) {
                decor.postInvalidateOnAnimation()
                return false
            }
            decor.viewTreeObserver.removeOnPreDrawListener(this)
            if (activity.rootedDialogs.none { it.dlg === dlg && it.hidden }) {
                DialogMotion.enter(dlg, anchoredMenus.containsKey(dlg), window.attributes.gravity == android.view.Gravity.BOTTOM)
            }
            val xy = IntArray(2)
            decor.getLocationOnScreen(xy)
            android.util.Log.d("UuDialogHost", "phase=first_visible frames=$frames bounds=${xy[0]},${xy[1]},${decor.width},${decor.height} dim=content_only")
            return true
        }
    }
    decor.setOnApplyWindowInsetsListener { view, insets ->
        val generation = attachmentGeneration
        view.post {
            val root = activity.findViewById<View>(R.id.root)
            val viewport = root?.let { it.width to it.height }
            if (generation == attachmentGeneration && !activity.isFinishing && !activity.isDestroyed && dlg.isShowing && view.isAttachedToWindow && viewport != lastViewport) {
                lastViewport = viewport
                DialogMotion.settle(dlg)
                activity.applyPresentationGeometry(dlg)
            }
        }
        insets
    }
    decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            attachmentGeneration++
            styleDialogSurface(dlg)
            root = activity.findViewById(R.id.root)
            root?.addOnLayoutChangeListener(viewportListener)
            frames = 0
            firstDraw = DialogFirstDrawGate()
            lastViewport = null
            v.viewTreeObserver.addOnPreDrawListener(firstFrame)
            // Plain alerts must obey the same ownership and input policy as sheets.
            activity.rootDialogToTab(dlg, ownerTabId = openingTabId)
            activity.syncRootedDialogs()
            if (openingTabId != null && activity.tabs.none { it.tabId == openingTabId }) {
                val generation = attachmentGeneration
                v.post {
                    if (generation == attachmentGeneration && dlg.isShowing && v.isAttachedToWindow) dlg.dismiss()
                }
            }
            val entry = activity.rootedDialogs.firstOrNull { it.dlg === dlg }
            if (entry?.hidden != true) {
                activity.blockContentBehind(dlg)
                activity.setDialogDimmed(dlg, true)
            }
            android.util.Log.d("UuDialogHost", "phase=attached window=${System.identityHashCode(dlg).toString(16)} owner=${entry?.ownerTabId} shield=${entry?.hidden != true}")
        }
        override fun onViewDetachedFromWindow(v: View) {
            attachmentGeneration++
            root?.removeOnLayoutChangeListener(viewportListener)
            if (v.viewTreeObserver.isAlive) v.viewTreeObserver.removeOnPreDrawListener(firstFrame)
            DialogMotion.reset(dlg)
            activity.setDialogDimmed(dlg, false)
            activity.releaseContentBlock(dlg)
            android.util.Log.d("UuDialogHost", "phase=detached window=${System.identityHashCode(dlg).toString(16)}")
            // Keep this listener: a dismissed Dialog may be shown again.
        }
    })
}

/** AlertController disables IME when show() initially contains no editors (e.g. async RecyclerView rows).
 * A late editor enables IME on its own window only; tab/nested-modal input shields stay authoritative. */
internal fun View.enableOwningDialogEditorInput() {
    val activity = context.findMainActivity() ?: return
    if (activity.isFinishing || activity.isDestroyed) return
    val window = activity.rootedDialogs.lastOrNull { it.dlg.window?.decorView === rootView }?.dlg?.window ?: return
    if (window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM != 0) {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
    }
}

private val anchoredMenus = java.util.WeakHashMap<android.app.Dialog, java.lang.ref.WeakReference<View>>()

internal fun MainActivity.anchorMenu(dialog: android.app.Dialog, anchor: View) {
    anchoredMenus[dialog] = java.lang.ref.WeakReference(anchor)
    placeDialog(dialog, null)
}

internal fun MainActivity.dismissAnchoredMenuFromBackground() {
    rootedDialogs.lastOrNull { it.ownerTabId == activeTab.tabId && it.dlg.window?.decorView?.isAttachedToWindow == true }
        ?.dlg?.takeIf { anchoredMenus.containsKey(it) }?.dismiss()
}

private fun MainActivity.applyPresentationGeometry(dialog: android.app.Dialog) {
    val anchor = anchoredMenus[dialog]?.get()
    if (anchor != null) {
        val window = dialog.window ?: return
        if (isFinishing || isDestroyed || (dialog.isShowing && !window.decorView.isAttachedToWindow)) return
        val frame = android.graphics.Rect()
        window.decorView.getWindowVisibleDisplayFrame(frame)
        if (frame.width() <= 0 || frame.height() <= 0) return
        val xy = IntArray(2)
        anchor.getLocationOnScreen(xy)
        val density = resources.displayMetrics.density
        val margin = (8 * density).toInt()
        val divider = findViewById<View>(R.id.dividerTabBar)
        val tabPos = IntArray(2)
        divider?.getLocationOnScreen(tabPos)
        val safeTop = tabPos[1] + (divider?.height ?: 0) - frame.top
        // Measure natural content again so a keyboard-capped menu can grow back.
        val desiredWidth = resources.getDimensionPixelSize(R.dimen.menu_width)
        val measureWidth = desiredWidth.coerceAtMost(frame.width()).coerceAtLeast(1)
        window.decorView.measure(View.MeasureSpec.makeMeasureSpec(measureWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val bounds = popupBounds(frame.width(), frame.height(), safeTop,
            xy[0] + anchor.width - frame.left, xy[1] + anchor.height - frame.top,
            desiredWidth, window.decorView.measuredHeight, margin)
        val width = bounds.width
        val height = bounds.height
        val x = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) frame.width() - bounds.x - width else bounds.x
        val y = bounds.y
        val attrs = window.attributes
        val gravity = android.view.Gravity.TOP or android.view.Gravity.START
        if (attrs.gravity != gravity || attrs.x != x || attrs.y != y || attrs.width != width || attrs.height != height) {
            window.attributes = attrs.apply { this.gravity = gravity; this.x = x; this.y = y; this.width = width; this.height = height }
        }
        return
    }
    val placement = placedDialogs.firstOrNull { it.first.get() === dialog }
    if (placement != null) applyDialogGeometry(dialog, placement.second, placement.third)
    else applyCenteredDialogGeometry(dialog)
}

private val centeredSizes = java.util.WeakHashMap<android.app.Dialog, Pair<Int, Int>>()

private const val DIALOG_CENTERED_MAX_W_DP = 440

private fun MainActivity.applyCenteredDialogGeometry(dlg: android.app.Dialog) {
    if (isFinishing || isDestroyed) return
    val window = dlg.window ?: return
    if (dlg.isShowing && !window.decorView.isAttachedToWindow) return
    val root = findViewById<View>(R.id.root) ?: return
    if (root.width <= 0 || root.height <= 0) return
    val safeTop = findViewById<View>(R.id.dividerTabBar)?.bottom ?: 0
    if (root.height <= safeTop) return
    val attrs = window.attributes
    val original = centeredSizes.getOrPut(dlg) { attrs.width to attrs.height }
    val y = safeTop / 2
    if (attrs.gravity != android.view.Gravity.CENTER || attrs.y != y) {
        window.attributes = attrs.apply { gravity = android.view.Gravity.CENTER; this.y = y }
    }
    fun pixels(value: Int) = (value * resources.displayMetrics.density).toInt()
    val maxHeight = (root.height - safeTop - pixels(32)).coerceAtLeast(1)
    val maxWidth = minOf(
        root.width - pixels(32),
        pixels(DIALOG_CENTERED_MAX_W_DP),
        resources.getDimensionPixelSize(R.dimen.dialog_max_width),
    ).coerceAtLeast(1)
    val width = if (original.first > 0) original.first.coerceAtMost(maxWidth) else maxWidth
    val decor = window.decorView
    // Undo any previous capped-height panel mutation before re-measuring natural content
    // at the target width (EXACTLY width prevents DecorView's 65%/95% minWidth hijack).
    resetDialogPanelForWrap(dlg)
    decor.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
    )
    val cappedHeight = original.second == android.view.ViewGroup.LayoutParams.MATCH_PARENT ||
        (original.second > 0 && original.second >= maxHeight) ||
        decor.measuredHeight >= maxHeight
    val height = when {
        original.second > 0 -> original.second.coerceAtMost(maxHeight)
        cappedHeight -> maxHeight
        else -> android.view.ViewGroup.LayoutParams.WRAP_CONTENT
    }
    if (window.attributes.width != width || window.attributes.height != height) window.setLayout(width, height)
    if (cappedHeight) fitDialogPanel(dlg)
}

/**
 * Shared presentation owner: bounded first-frame placement, content-only dimming,
 * touch shields, tab ownership and dismissal cleanup. Window geometry writes must
 * be idempotent: changing attributes on every insets dispatch can starve drawing.
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
) {
    dlg.setOnDismissListener {
        try { also?.invoke() }
        finally {
            releaseContentBlock(dlg)
            setDialogDimmed(dlg, false)
            viewPager.setUserInputEnabled(false)
            viewPager.setUserInputEnabled(true)
        }
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
) {
    var hidden = false
}

/** Pin [dlg] to the tab that is active right now. Safe before or after `show()`. */
internal fun MainActivity.rootDialogToTab(dlg: android.app.Dialog, ownerTabId: Int? = null) {
    if (tabs.isEmpty()) return
    if (rootedDialogs.any { it.dlg === dlg }) return
    val owner = ownerTabId ?: AppDiagnostics.currentTrace()?.chain?.let { dialogChainTabs[it] } ?: activeTab.tabId
    val entry = RootedDialog(dlg, owner)
    rootedDialogs.add(entry)
    dlg.window?.decorView?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) {
            rootedDialogs.remove(entry)
            syncRootedDialogs()
            v.removeOnAttachStateChangeListener(this)
        }
    })
}

/** Hide rooted dialogs whose tab is no longer active and re-show the ones
 *  whose tab just came back. Called from the pager's onPageSelected. */
internal fun MainActivity.syncRootedDialogs() {
    if (tabs.isEmpty() || isFinishing || isDestroyed) return
    val activeId = activeTab.tabId
    val top = rootedDialogs.lastOrNull {
        it.ownerTabId == activeId && it.dlg.window?.decorView?.isAttachedToWindow == true
    }
    for (entry in rootedDialogs) {
        val attached = entry.dlg.window?.decorView?.isAttachedToWindow == true
        val presentation = tabModalPresentation(entry.ownerTabId, activeId, attached, entry === top)
        val shouldShow = presentation.visible
        val visibility = if (shouldShow) View.VISIBLE else View.GONE
        if (attached && entry.dlg.window?.decorView?.visibility != visibility) {
            setRootedDialogHidden(entry.dlg, !shouldShow)
        }
        entry.hidden = !shouldShow
        if (attached) {
            if (shouldShow) DialogMotion.setCovered(entry.dlg, !presentation.acceptsInput)
            val window = entry.dlg.window ?: continue
            val blocked = android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            // A nested modal must not pass an outside touch to its parent window.
            val flags = if (presentation.acceptsInput) window.attributes.flags and blocked.inv()
                else window.attributes.flags or blocked
            if (flags != window.attributes.flags) window.attributes = window.attributes.apply { this.flags = flags }
            setDialogDimmed(entry.dlg, shouldShow)
        }
        if (presentation.blocksBackground) blockContentBehind(entry.dlg)
        else releaseContentBlock(entry.dlg)
    }
    android.util.Log.d("UuDialogHost", "phase=tab_sync active=$activeId top=${top?.dlg?.let { System.identityHashCode(it).toString(16) }} visible=${rootedDialogs.count { it.ownerTabId == activeId }} shield=${shieldHolders.size}")
}

internal fun MainActivity.setRootedDialogHidden(dlg: android.app.Dialog, hidden: Boolean) {
    val w = dlg.window ?: return
    val decor = w.decorView
    DialogMotion.reset(dlg)
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
    setDialogDimmed(dlg, !hidden)
}

/** Dismiss every dialog rooted to a closed tab. */
internal fun MainActivity.dismissRootedDialogsFor(tabId: Int) {
    val doomed = rootedDialogs.filter { it.ownerTabId == tabId }
    rootedDialogs.removeAll { it.ownerTabId == tabId }
    doomed.forEach { entry ->
        try { com.usefulunpacker.observedAttempt("DialogHost") { entry.dlg.dismiss() } }
        finally {
            // A framework dismissal failure must not leave the closed tab's Activity shield.
            releaseContentBlock(entry.dlg)
            setDialogDimmed(entry.dlg, false)
        }
    }
    syncRootedDialogs()
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
 *  2. 所有模态窗口都阻挡背景触摸，只有最上层窗口和 tab 栏可交互；
 *  3. 遮罩/输入护盾跟随所属 tab，关闭与切换均配对释放。
 */
private fun MainActivity.placeDialog(
    dlg: android.app.Dialog,
    also: (() -> Unit)?,
) {
    if (isFinishing || isDestroyed) return
    if (dlg.isShowing && dlg.window?.decorView?.isAttachedToWindow != true) return
    prepareDialogWindow(dlg)
    rootDialogToTab(dlg)
    // All modal windows block content; other tabs remain independently usable.
    if (rootedDialogs.none { it.dlg === dlg && it.hidden }) blockContentBehind(dlg)
    resetPagerInputOnDialogDismiss(dlg, also)
    syncRootedDialogs()
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
 *  Every dialog belongs to its opening tab and blocks the content underneath. */
internal fun MainActivity.keepTabBarTappable(
    dlg: android.app.Dialog,
    also: (() -> Unit)? = null,
) {
    placeDialog(dlg, also = also)
}

/** Bottom-anchored variant of [keepTabBarTappable] for **tall** dialogs.
 *
 *  A centered dialog capped at `dialog_max_height` can still reach and cover
 *  the tab strip on shorter screens, so `FLAG_NOT_TOUCH_MODAL` alone is not
 *  enough — the window has to be physically kept below the strip. This moves
 *  the dialog to the bottom and sizes it to the space under
 *  tabs, with a local scrim instead of the system dim (which
 *  would darken the strip too). Mirrors what `PreviewFlow`/`BatchExtract`
 *  used to hand-roll; those two now call this so the geometry cannot drift.
 *
 *  `swipeArea` keeps some of the pager visible above the sheet so the user
 *  can still swipe between windows. */
internal fun MainActivity.belowTabBar(
    dlg: android.app.Dialog,
    swipeAreaDp: Float = 130f,
    fitContent: Boolean = false,
    fillViewport: Boolean = false,
    also: (() -> Unit)? = null,
) {
    if (fillViewport) dlg.window?.decorView?.setTag(R.id.dialogFillViewport, true)
    registerPlacement(dlg, swipeAreaDp, fitContent)
    applyDialogGeometry(dlg, swipeAreaDp, fitContent)
    // 弹窗一律要护盾（除 tab 栏外全挡）；配对全在 placeDialog 里。
    placeDialog(dlg, also = also)
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
    // ⚠️ 目前**不参与计算**：它只被登记下来，实际可用高度由 `applySize()` 按
    // `R.id.root` 的**当前**高度算（键盘弹出时 root 会被压矮，那个值才是真的）。
    // 早先这里用它算过一个 `sheetH`，但那个变量从头到尾没人用，已删。
    @Suppress("UNUSED_PARAMETER") swipeAreaDp: Float,
    fitContent: Boolean,
) {
    if (isFinishing || isDestroyed) return
    val w = dlg.window ?: return
    // During dismiss(), isShowing may remain true after WindowManager removed the decor.
    if (dlg.isShowing && !w.decorView.isAttachedToWindow) return
    val dm = resources.displayMetrics
    val density = dm.density
    val topInset = findViewById<View>(R.id.dividerTabBar)?.bottom
        ?: (50f * density).toInt()
    val rootW = findViewById<View>(R.id.root)?.width?.takeIf { it > 0 } ?: dm.widthPixels
    val landscape = resources.configuration.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val fillViewport = w.decorView.getTag(R.id.dialogFillViewport) == true
    val sheetW = if (fillViewport) {
        rootW
    } else if (landscape) {
        (DIALOG_LANDSCAPE_W_DP * density).toInt()
            .coerceAtMost(rootW - (2 * 16f * density).toInt())
    } else {
        minOf(rootW, resources.getDimensionPixelSize(R.dimen.dialog_max_width))
    }.coerceAtLeast(1)

    // Window dim/animation policy is configured once by prepareDialogWindow.
    // Reapplying window attributes from pre-draw/insets would keep requesting another layout.
    val gravity = when {
        fillViewport -> android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
        landscape -> android.view.Gravity.CENTER
        else -> android.view.Gravity.BOTTOM
    }
    val offset = if (fillViewport) topInset else if (landscape) topInset / 2 else 0
    if (w.attributes.gravity != gravity || w.attributes.y != offset) {
        w.attributes = w.attributes.apply { this.gravity = gravity; y = offset }
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
        // Attach/insets posts can outlive a dismissed dialog or its Activity.
        if (isFinishing || isDestroyed || !dlg.isShowing || !w.decorView.isAttachedToWindow) return
        // 可用高度取**父窗口当前的实际高度**（root 会被输入法压矮），不是屏幕高度 ——
        // 拿 displayMetrics 算出来的高度在键盘弹出后就是超的，超出的那截收不到触摸。
        val rootH = findViewById<View>(R.id.root)?.height ?: 0
        // Startup may show a dialog before the Activity's first layout. Keep natural size;
        // the root layout listener will size it when the viewport actually exists.
        if (rootH <= topInset) return
        val margin = if (fillViewport) 0 else (2 * 20f * density).toInt()
        val availH = (rootH - topInset - margin).coerceAtLeast(1)
        if (!fitContent) {
            // 长内容：给**面板**（AlertDialog 的 android.R.id.content 第一层子）设精确高度，
            // 不碰窗口。窗口是 WRAP，会跟着面板量；面板内部列表带权重，自然分到剩余高度。
            // （给窗口设死高度不行：AlertController 的 customPanel 是 WRAP，内容不会撑开，
            // 列表会塌成 0 高 —— 真机踩过。）
            // 宽高都由窗口定死（show 之后 setLayout 有效）。只定宽不定高的话窗口会比面板
            // 高出装饰层那点内边距，面板居中 → 底部露出一条窗口底色，看着像从底部呼出的。
            if (w.attributes.width != sheetW || w.attributes.height != availH) w.setLayout(sheetW, availH)
            fitDialogPanel(dlg)
            return
        }
        // 短内容贴自身高度（1 条的回收站不该开成 near-full-screen）。
        // 先还原成 WRAP_CONTENT 再以 EXACTLY(sheetW) 测真实高度：
        // 1. 避免 AT_MOST 下被 DecorView 的 windowMinWidthMajor/Minor（65%/95% 屏宽）劫持；
        // 2. 避免从横屏（曾被 availH 封顶并 fitDialogPanel）转回竖屏时留在 MATCH_PARENT。
        resetDialogPanelForWrap(dlg)
        val decor = w.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(sheetW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(availH, View.MeasureSpec.AT_MOST))
        val measured = decor.measuredHeight
        val height = if (measured in 1 until availH) measured else availH
        if (w.attributes.width != sheetW || w.attributes.height != height) w.setLayout(sheetW, height)
        fitDialogPanel(dlg)
    }
    // The shared pre-draw gate calls this again before revealing a newly attached window.
    applySize()

}
/** 记录「几何与方向相关」的弹窗，转屏时按新方向重算（[reapplyDialogPlacements]）。 */
private val placedDialogs =
    mutableListOf<Triple<java.lang.ref.WeakReference<android.app.Dialog>, Float, Boolean>>()

private fun registerPlacement(dlg: android.app.Dialog, swipeAreaDp: Float, fitContent: Boolean) {
    // ⚠️ 只清「已被 GC」和「同一个弹窗的旧登记」，**不要**顺手清 `!isShowing` 的：
    // `belowTabs` 有一半调用点在 `show()` 之前，此时弹窗还没显示。若在 A 注册、尚未
    // show 时注册 B，按 isShowing 清就会把 A 误剔掉 —— A 显示后再转屏就不重算了。
    // 表里积累的弱引用条目由 [reapplyDialogPlacementsNow] 在转屏时统一回收。
    placedDialogs.removeAll { it.first.get() == null || it.first.get() === dlg }
    placedDialogs.add(Triple(java.lang.ref.WeakReference(dlg), swipeAreaDp, fitContent))
}

/**
 * Reflow visible sheets and centered dialogs when the content viewport changes.
 *
 * ⚠️ **必须 post 到下一帧**：`onConfigurationChanged` 是在新方向还没走完布局时回调的，
 * 此刻 `R.id.root.height` 还是旧方向的值 —— 直接算等于拿旧尺寸又设了一遍。
 */
internal fun MainActivity.reapplyDialogPlacements() {
    window?.decorView?.post { reapplyDialogPlacementsNow() }
}

private fun MainActivity.reapplyDialogPlacementsNow() {
    if (isFinishing || isDestroyed) return
    val iter = placedDialogs.iterator()
    while (iter.hasNext()) {
        val (ref, swipeAreaDp, fitContent) = iter.next()
        val d = ref.get()
        if (d == null || !d.isShowing) { iter.remove(); continue }
        if (d.context.findMainActivity() !== this) continue
        DialogMotion.settle(d)
        applyDialogGeometry(d, swipeAreaDp, fitContent)
    }
    dialogDimOwners.filterIsInstance<android.app.Dialog>().forEach { dialog ->
        if (dialog.isShowing && placedDialogs.none { it.first.get() === dialog }) {
            DialogMotion.settle(dialog)
            applyPresentationGeometry(dialog)
        }
    }
}

/**
 * 横屏居中对话框的宽度（dp）—— 竖屏比例，像一台手机立在内容区正中
 * （用户定稿：「横屏在除掉 tab 栏的窗口里面居中」，宽 ~480dp）。
 * 竖屏仍走 dialog_max_width 的贴底大窗，见 [belowTabBar]。
 */
private const val DIALOG_LANDSCAPE_W_DP = 480f

/**
 * 测量自然内容高度前，把 [fitDialogPanel] 改过的 MATCH_PARENT / weight=1 还原为 WRAP_CONTENT，
 * 同时清掉对话框主题的 minimumWidth，保证转屏或键盘收起后能重新测出真实的自然高度。
 */
private fun MainActivity.resetDialogPanelForWrap(dlg: android.app.Dialog) {
    val decor = dlg.window?.decorView ?: return
    if (decor.minimumWidth != 0) decor.minimumWidth = 0
    val content = decor.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
    (content.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { clp ->
        if (clp.height != android.view.ViewGroup.LayoutParams.WRAP_CONTENT) {
            clp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            content.layoutParams = clp
        }
    }
    val panel = content.getChildAt(0) ?: return
    (panel.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
        if (lp.width != android.view.ViewGroup.LayoutParams.MATCH_PARENT ||
            lp.height != android.view.ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            lp.gravity = android.view.Gravity.CENTER
            panel.layoutParams = lp
        }
    }
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
        (c.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { clp ->
            if (clp.weight != 0f || clp.height != android.view.ViewGroup.LayoutParams.WRAP_CONTENT) {
                clp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                clp.weight = 0f
                c.layoutParams = clp
            }
        }
        val holder = c as? android.view.ViewGroup ?: return@let
        for (k in 0 until holder.childCount) {
            val v = holder.getChildAt(k)
            val vlp = v.layoutParams as? android.widget.FrameLayout.LayoutParams ?: continue
            if (vlp.height != android.view.ViewGroup.LayoutParams.WRAP_CONTENT) {
                vlp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                v.layoutParams = vlp
            }
        }
    }
}

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
    also: (() -> Unit)? = null,
) {
    context.findMainActivity()?.keepTabBarTappable(this, also)
}

/**
 * Bottom-anchored variant for tall dialogs; see `MainActivity.belowTabBar`.
 * `fitContent = true` makes the sheet hug short content (capped below the tab
 * strip) instead of always taking the full available height. Every dialog is
 * pinned to the tab that opened it.
 */
internal fun android.app.Dialog.belowTabs(
    swipeAreaDp: Float = 130f,
    fitContent: Boolean = false,
    fillViewport: Boolean = false,
    also: (() -> Unit)? = null,
) {
    context.findMainActivity()?.belowTabBar(this, swipeAreaDp, fitContent, fillViewport, also)
}
