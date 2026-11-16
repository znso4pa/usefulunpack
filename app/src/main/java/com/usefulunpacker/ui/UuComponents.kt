package com.usefulunpacker

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Shared semantic controls. Screens own state/actions; these controls own visual metrics. */
enum class ActionStyle { PRIMARY, SECONDARY, TEXT, DANGER }

private fun tracedClick(listener: View.OnClickListener?): View.OnClickListener? {
    val trace = AppDiagnostics.currentTrace()
    return if (listener == null || trace == null) listener else View.OnClickListener { view ->
        AppDiagnostics.invokeCallback(trace) { listener.onClick(view) }
    }
}

/** Menus use the same modal/tab ownership as dialogs; native PopupWindow input is separate. */
class UuPopupMenu(private val host: Context, private val anchor: View) {
    private val actions = mutableListOf<Pair<Int, () -> Unit>>()
    private var dialog: AlertDialog? = null
    fun action(@Suppress("UNUSED_PARAMETER") id: Int, title: Int, block: () -> Unit) {
        val trace = AppDiagnostics.currentTrace()
        actions.add(title to { AppDiagnostics.invokeCallback(trace, block) })
    }
    fun show() {
        val body = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        actions.forEach { (title, action) ->
            body.addView(UuActionRow(host).apply {
                bind(host.getString(title), onClick = { dialog?.dismiss(); action() })
            })
        }
        dialog = UuDialogBuilder(host).setView(UuScrollView(host).apply { addView(body) })
            .create().also {
                (host.hostActivity() as? MainActivity)?.anchorMenu(it, anchor)
                it.show()
            }
    }
    fun dismiss() { dialog?.dismiss() }
}

/** The drawer cannot intercept gestures that began on the independent tab strip. */
class UuDrawerLayout @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : androidx.drawerlayout.widget.DrawerLayout(ctx, attrs) {
    private var tabGesture = false
    init { setScrimColor(android.graphics.Color.TRANSPARENT) }
    override fun onInterceptTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            val tab = findViewById<View>(R.id.tabBar)
            val bounds = android.graphics.Rect()
            val origin = IntArray(2)
            getLocationOnScreen(origin)
            tabGesture = tab?.getGlobalVisibleRect(bounds) == true &&
                event.y + origin[1] >= bounds.top && event.y + origin[1] < bounds.bottom
        }
        val bypass = tabGesture
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP || event.actionMasked == android.view.MotionEvent.ACTION_CANCEL) tabGesture = false
        return if (bypass) false else super.onInterceptTouchEvent(event)
    }
}

/** Shared notice entry, including legacy call sites that only have a Context. */
object UuNotice {
    fun failure(context: Context, text: CharSequence, duration: Int): Toast {
        val cleanupOnly = AppDiagnostics.currentTrace()?.chain?.onlyCleanupFailed() == true
        AppDiagnostics.begin("user_feedback").fail(if (cleanupOnly) FailureCode.PARTIAL_FAILURE else FailureCode.INVALID_RESULT, text.toString())
        return makeText(context, if (cleanupOnly) context.getText(R.string.diagnostics_cleanup_failed) else text, duration)
    }
    fun makeText(context: Context, text: CharSequence, duration: Int): Toast {
        AppDiagnostics.notice(text.toString())
        val id = AppDiagnostics.currentTrace()?.takeIf { it.chain != null }?.id
        return Toast.makeText(context, if (id == null) text else "[$id] $text", duration)
    }
    fun makeText(context: Context, text: Int, duration: Int): Toast = makeText(context, context.getText(text), duration)
    fun persistent(anchor: View, message: CharSequence, actionLabel: Int, action: () -> Unit) {
        com.google.android.material.snackbar.Snackbar.make(anchor, message, com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
            .setActionTextColor(anchor.context.getColor(R.color.accent))
            .setAction(actionLabel) { action() }.show()
    }
}

internal object UuMetrics {
    fun px(ctx: Context, res: Int) = ctx.resources.getDimensionPixelSize(res)
    fun ensureTarget(view: View) {
        val target = px(view.context, R.dimen.touch_target)
        view.layoutParams?.let { params ->
            var changed = false
            if (params.width in 1 until target) { params.width = target; changed = true }
            if (params.height in 1 until target) { params.height = target; changed = true }
            if (changed) view.layoutParams = params
        }
    }
    fun text(view: TextView, title: Boolean = false) {
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
            view.resources.getDimension(if (title) R.dimen.text_xl else R.dimen.text_lg))
        view.setTextColor(view.context.getColor(R.color.text_primary))
    }
    fun action(view: TextView, style: ActionStyle) {
        val ctx = view.context
        view.minHeight = px(ctx, R.dimen.touch_target)
        view.minimumHeight = view.minHeight
        view.minWidth = px(ctx, R.dimen.touch_target)
        view.minimumWidth = view.minWidth
        view.gravity = Gravity.CENTER
        text(view)
        view.isAllCaps = false
        view.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        view.letterSpacing = 0f
        view.includeFontPadding = false
        view.setSingleLine(false)
        view.maxLines = 2
        view.ellipsize = android.text.TextUtils.TruncateAt.END
        val accent = ctx.getColor(if (style == ActionStyle.DANGER) R.color.state_error else R.color.accent)
        val filled = style == ActionStyle.PRIMARY
        val color = if (filled) ctx.getColor(R.color.accent_on) else accent
        view.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(ctx.getColor(R.color.text_disabled), color)))
        view.backgroundTintList = null
        view.background = GradientDrawable().apply {
            cornerRadius = px(ctx, R.dimen.corner_input).toFloat()
            setColor(if (filled) accent else if (style == ActionStyle.SECONDARY) ctx.getColor(R.color.accent_soft) else android.graphics.Color.TRANSPARENT)
        }
        view.setPadding(px(ctx, R.dimen.space_md), px(ctx, R.dimen.space_sm), px(ctx, R.dimen.space_md), px(ctx, R.dimen.space_sm))
        view.foreground = ripple(ctx.getColor(R.color.ripple_light))
    }
}

class UuButton @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : androidx.appcompat.widget.AppCompatButton(ctx, attrs) {
    fun selectChoice(selected: Boolean) {
        isSelected = selected
        actionStyle = if (selected) ActionStyle.PRIMARY else ActionStyle.SECONDARY
    }
    override fun setOnClickListener(listener: View.OnClickListener?) {
        super.setOnClickListener(tracedClick(listener))
    }
    var actionStyle: ActionStyle = ActionStyle.SECONDARY
        set(value) { field = value; UuMetrics.action(this, value) }
    init {
        val values = ctx.obtainStyledAttributes(attrs, R.styleable.UuButton)
        try { actionStyle = ActionStyle.entries[values.getInt(R.styleable.UuButton_uuActionStyle, 1)] }
        finally { values.recycle() }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); UuMetrics.action(this, actionStyle); UuMetrics.ensureTarget(this) }
}

class UuIconButton @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : androidx.appcompat.widget.AppCompatImageButton(ctx, attrs) {
    override fun setOnClickListener(listener: View.OnClickListener?) { super.setOnClickListener(tracedClick(listener)) }
    var actionStyle: ActionStyle = ActionStyle.TEXT
        set(value) { field = value; applyStyle() }
    init { applyStyle() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); applyStyle(); UuMetrics.ensureTarget(this) }
    private fun applyStyle() {
        val ctx = context
        minimumWidth = UuMetrics.px(ctx, R.dimen.touch_target)
        minimumHeight = UuMetrics.px(ctx, R.dimen.touch_target)
        val padding = UuMetrics.px(ctx, R.dimen.space_md)
        setPadding(padding, padding, padding, padding)
        background = if (actionStyle == ActionStyle.PRIMARY) roundedBg(ctx.dp(8), ctx.getColor(R.color.accent)) else ripple(ctx.getColor(R.color.ripple_light))
        imageTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(ctx.getColor(R.color.text_disabled), ctx.getColor(when (actionStyle) {
                ActionStyle.PRIMARY -> R.color.accent_on
                ActionStyle.DANGER -> R.color.state_error
                else -> R.color.accent
            })))
    }
}

class UuTextField @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : androidx.appcompat.widget.AppCompatEditText(ctx, attrs) {
    init { applyStyle() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); applyStyle(); UuMetrics.ensureTarget(this) }
    private fun applyStyle() {
        val ctx = context
        UuMetrics.text(this)
        minHeight = UuMetrics.px(ctx, R.dimen.touch_target)
        setHintTextColor(ctx.getColor(R.color.text_hint))
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        val p = UuMetrics.px(ctx, R.dimen.space_md)
        setPadding(p, p, p, p)
        background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_input_rounded)
    }
}

class UuFab @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : com.google.android.material.floatingactionbutton.FloatingActionButton(ctx, attrs) {
    override fun setOnClickListener(listener: View.OnClickListener?) { super.setOnClickListener(tracedClick(listener)) }
    init {
        backgroundTintList = ColorStateList.valueOf(ctx.getColor(R.color.accent))
        imageTintList = ColorStateList.valueOf(ctx.getColor(R.color.accent_on))
        minimumWidth = UuMetrics.px(ctx, R.dimen.touch_target)
        minimumHeight = minimumWidth
    }
}

class UuProgressBar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null,
    style: Int = android.R.attr.progressBarStyle) : ProgressBar(ctx, attrs, style) {
    init {
        val tint = ColorStateList.valueOf(ctx.getColor(R.color.accent))
        progressTintList = tint; indeterminateTintList = tint
    }
}

class UuSwitch @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : androidx.appcompat.widget.SwitchCompat(ctx, attrs) {
    init { UuMetrics.text(this); minHeight = UuMetrics.px(ctx, R.dimen.touch_target) }
}

class UuCheckBox @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : androidx.appcompat.widget.AppCompatCheckBox(ctx, attrs) {
    init { UuMetrics.text(this); minHeight = UuMetrics.px(ctx, R.dimen.touch_target) }
}

class UuRadioButton @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : androidx.appcompat.widget.AppCompatRadioButton(ctx, attrs) {
    init { UuMetrics.text(this); minHeight = UuMetrics.px(ctx, R.dimen.touch_target) }
}

class UuRadioGroup(ctx: Context) : RadioGroup(ctx) {
    init {
        orientation = VERTICAL
        val p = UuMetrics.px(ctx, R.dimen.space_lg)
        setPadding(p, 0, p, 0)
    }
}

/** Shared scrolling policy, including the Honor/EMUI native-scrollbar workaround. */
class UuScrollView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : ScrollView(ctx, attrs) {
    init { isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false }
}

class UuHorizontalScrollView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : HorizontalScrollView(ctx, attrs) {
    init { isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false }
}

class UuSlider @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : androidx.appcompat.widget.AppCompatSeekBar(ctx, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // ProgressBar's intrinsic-height measurement ignores View.minimumHeight.
        setMeasuredDimension(measuredWidth, View.resolveSize(maxOf(measuredHeight, UuMetrics.px(context, R.dimen.touch_target)), heightMeasureSpec))
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); UuMetrics.ensureTarget(this) }
    override fun setOnSeekBarChangeListener(listener: SeekBar.OnSeekBarChangeListener?) {
        val trace = AppDiagnostics.currentTrace()
        super.setOnSeekBarChangeListener(if (listener == null) null else object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) = AppDiagnostics.invokeCallback(trace) { listener.onProgressChanged(bar, value, fromUser) }
            override fun onStartTrackingTouch(bar: SeekBar?) = AppDiagnostics.invokeCallback(trace) { listener.onStartTrackingTouch(bar) }
            override fun onStopTrackingTouch(bar: SeekBar?) = AppDiagnostics.invokeCallback(trace) { listener.onStopTrackingTouch(bar) }
        })
    }
    init {
        minimumHeight = UuMetrics.px(ctx, R.dimen.touch_target)
        val tint = ColorStateList.valueOf(ctx.getColor(R.color.accent))
        progressTintList = tint; thumbTintList = tint
        val p = UuMetrics.px(ctx, R.dimen.space_lg)
        setPadding(p, 0, p, 0)
    }
}

/** Choice appearance belongs here; callers supply state and the action, never a drawable. */
class UuChoiceCell(ctx: Context) : androidx.appcompat.widget.AppCompatTextView(ctx) {
    fun bind(label: CharSequence, listMode: Boolean = false, available: Boolean = true,
             selected: Boolean = false, onClick: () -> Unit) {
        val trace = AppDiagnostics.currentTrace()
        text = label
        isSelected = selected
        isEnabled = available
        isFocusable = true
        UuMetrics.text(this)
        setTextColor(context.getColor(if (!available) R.color.text_disabled else if (selected) R.color.accent else R.color.text_primary))
        val shape = GradientDrawable().apply {
            setColor(context.getColor(R.color.bg_surface_raised))
            cornerRadius = UuMetrics.px(context, R.dimen.corner_input).toFloat()
            setStroke(context.dp(if (selected) 2 else 1), context.getColor(if (selected) R.color.accent else R.color.divider_subtle))
        }
        background = if (available) android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(context.getColor(R.color.ripple_light)), shape, null) else shape
        minHeight = UuMetrics.px(context, R.dimen.touch_target)
        gravity = if (listMode) Gravity.CENTER_VERTICAL or Gravity.START else Gravity.CENTER
        maxLines = if (listMode) 1 else 2
        minLines = if (listMode) 1 else 2
        ellipsize = if (listMode) android.text.TextUtils.TruncateAt.MIDDLE else android.text.TextUtils.TruncateAt.END
        val p = UuMetrics.px(context, R.dimen.space_md)
        setPadding(p, p, p, p)
        setOnClickListener { AppDiagnostics.invokeCallback(trace, onClick) }
    }
}

/** Settings label and switch share one large click target and one accessibility node. */
class UuToggleRow @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = UuMetrics.px(context, R.dimen.row_setting)
        val p = UuMetrics.px(context, R.dimen.space_lg)
        setPadding(p, context.dp(8), p, context.dp(8))
    }
    override fun onFinishInflate() {
        super.onFinishInflate()
        val control = (0 until childCount).map { getChildAt(it) }.filterIsInstance<UuSwitch>().firstOrNull() ?: return
        val label = (0 until childCount).map { getChildAt(it) }.filterIsInstance<TextView>().firstOrNull { it !== control } ?: return
        control.contentDescription = label.text
        UuMetrics.text(label, title = true)
        label.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setOnClickListener { if (control.isEnabled) control.toggle() }
    }
    fun bind(title: CharSequence, control: UuSwitch) {
        removeAllViews()
        addView(TextView(context).apply { text = title; UuMetrics.text(this, title = true) }, LayoutParams(0, WRAP, 1f))
        addView(control)
        // Keep the native switch as the accessible checked-state control.
        control.contentDescription = title
        getChildAt(0).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setOnClickListener { if (control.isEnabled) control.toggle() }
    }
}

/** Shared action-row geometry; transparent so the registered host backdrop remains visible. */
class UuActionBar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = UuMetrics.px(ctx, R.dimen.touch_target)
        val p = UuMetrics.px(ctx, R.dimen.space_xs)
        setPadding(p, 0, p, 0)
    }
}

class UuActionPanel @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {
    init {
        orientation = VERTICAL
        val p = UuMetrics.px(ctx, R.dimen.space_sm)
        setPadding(0, p, 0, p)
    }
}

/** One common swatch button, with an accessible label and a full-size touch target. */
class UuColorButton(ctx: Context) : androidx.appcompat.widget.AppCompatImageButton(ctx, null, 0) {
    override fun setOnClickListener(listener: View.OnClickListener?) { super.setOnClickListener(tracedClick(listener)) }
    var color: Int = android.graphics.Color.WHITE
        set(value) { field = value; updateColor() }
    init {
        minimumWidth = UuMetrics.px(ctx, R.dimen.touch_target)
        minimumHeight = minimumWidth
        backgroundTintList = null
        updateColor()
    }
    private fun updateColor() {
        val content = GradientDrawable().apply {
            setColor(this@UuColorButton.color); cornerRadius = context.dp(8).toFloat()
            setStroke(context.dp(1), context.getColor(R.color.text_secondary))
        }
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.ripple_light)), content, null)
        contentDescription = context.getString(R.string.components_color, String.format("#%08X", color))
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); UuMetrics.ensureTarget(this) }
}

/** The drawer owns responsive width; its background is still registered in the backdrop tables. */
class UuSidePanel @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {
    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateWidth() }
    override fun onConfigurationChanged(config: android.content.res.Configuration) {
        super.onConfigurationChanged(config); updateWidth()
    }
    private fun updateWidth() {
        layoutParams?.let {
            it.width = minOf(UuMetrics.px(context, R.dimen.drawer_width), (resources.displayMetrics.widthPixels * 0.85f).toInt())
            layoutParams = it
        }
    }
}

/** Titles, values and click geometry are shared by settings and drawer entries. */
class UuActionRow @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : androidx.appcompat.widget.AppCompatTextView(ctx, attrs) {
    override fun setOnClickListener(listener: View.OnClickListener?) { super.setOnClickListener(tracedClick(listener)) }
    init {
        UuMetrics.text(this, title = true)
        minHeight = UuMetrics.px(ctx, R.dimen.row_setting)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
        background = ripple(ctx.getColor(R.color.ripple_light))
        isFocusable = true
    }
    private var rowTitle: CharSequence = ""
    var navigation: Boolean = false
        set(value) {
            field = value
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, if (value) R.drawable.ic_chevron_right else 0, 0)
            compoundDrawablePadding = context.dp(12)
        }
    var valueText: CharSequence? = null
        set(value) { field = value; renderText() }
    private fun renderText() {
        text = if (valueText.isNullOrEmpty()) rowTitle else android.text.SpannableStringBuilder().apply {
            append(rowTitle); append('\n')
            val start = length
            append(valueText)
            setSpan(android.text.style.ForegroundColorSpan(context.getColor(R.color.text_secondary)), start, length, 0)
            setSpan(android.text.style.AbsoluteSizeSpan(resources.getDimension(R.dimen.text_lg).toInt()), start, length, 0)
        }
    }
    fun bind(title: CharSequence, value: CharSequence? = null, navigation: Boolean = false, onClick: (() -> Unit)? = null) {
        val ownerTrace = AppDiagnostics.currentTrace()
        rowTitle = title
        valueText = value
        isEnabled = onClick != null
        this.navigation = navigation
        setOnClickListener(if (onClick == null) null else View.OnClickListener {
            AppDiagnostics.invokeCallback(ownerTrace) { onClick() }
        })
    }
}

/** Owns visual treatment, without taking over onShow/onDismiss or tab/placement ownership. */
class UuDialogBuilder(ctx: Context, private val keepChainOpen: Boolean = true) : AlertDialog.Builder(ctx) {
    private val ownerTrace = AppDiagnostics.currentTrace()
    private var positiveStyle = ActionStyle.PRIMARY
    private fun titleWithId(title: CharSequence?): CharSequence? = ownerTrace?.let { "[${it.id}] ${title ?: ""}" } ?: title
    override fun setTitle(title: CharSequence?): AlertDialog.Builder = super.setTitle(titleWithId(title))
    override fun setTitle(titleId: Int): AlertDialog.Builder = super.setTitle(titleWithId(context.getText(titleId)))
    private fun wrap(listener: android.content.DialogInterface.OnClickListener?): android.content.DialogInterface.OnClickListener? =
        if (listener == null || ownerTrace == null) listener else android.content.DialogInterface.OnClickListener { dialog, which ->
            AppDiagnostics.invokeCallback(ownerTrace) { listener.onClick(dialog, which) }
        }
    init { super.setOnCancelListener { ownerTrace?.cancelled() } }
    override fun setPositiveButton(text: CharSequence?, listener: android.content.DialogInterface.OnClickListener?): AlertDialog.Builder {
        positiveStyle = ActionStyle.PRIMARY
        return super.setPositiveButton(text, wrap(listener))
    }
    override fun setPositiveButton(textId: Int, listener: android.content.DialogInterface.OnClickListener?): AlertDialog.Builder {
        positiveStyle = if (textId == R.string.action_delete || textId == R.string.recycle_empty_now) ActionStyle.DANGER else ActionStyle.PRIMARY
        return super.setPositiveButton(context.getText(textId), wrap(listener))
    }
    override fun setNegativeButton(text: CharSequence?, listener: android.content.DialogInterface.OnClickListener?): AlertDialog.Builder = super.setNegativeButton(text, wrap(listener))
    override fun setNegativeButton(textId: Int, listener: android.content.DialogInterface.OnClickListener?): AlertDialog.Builder =
        super.setNegativeButton(textId, wrap { dialog, which ->
            if (textId == R.string.action_cancel) ownerTrace?.cancelled()
            listener?.onClick(dialog, which)
        })
    override fun setNeutralButton(text: CharSequence?, listener: android.content.DialogInterface.OnClickListener?): AlertDialog.Builder = super.setNeutralButton(text, wrap(listener))
    override fun setNeutralButton(textId: Int, listener: android.content.DialogInterface.OnClickListener?): AlertDialog.Builder = super.setNeutralButton(textId, wrap(listener))
    override fun setItems(items: Array<out CharSequence>?, listener: android.content.DialogInterface.OnClickListener?): AlertDialog.Builder = super.setItems(items, wrap(listener))
    override fun setSingleChoiceItems(items: Array<out CharSequence>?, checkedItem: Int, listener: android.content.DialogInterface.OnClickListener?): AlertDialog.Builder = super.setSingleChoiceItems(items, checkedItem, wrap(listener))
    override fun create(): AlertDialog = super.create().also { dialog ->
        prepareDialogWindow(dialog)
        if (keepChainOpen) ownerTrace?.retain()
        var released = false
        dialog.window?.decorView?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(v: View) {
                if (!released) { released = true; if (keepChainOpen) ownerTrace?.release() }
            }
            override fun onViewAttachedToWindow(v: View) {
                val activity = context.hostActivity()
                if (activity?.isFinishing == true || activity?.isDestroyed == true) return
                styleDialog(dialog, positiveStyle)
            }
        })
    }
}

/** Legacy progress semantics are preserved while IDs and cancellation use the common chain. */
@Suppress("DEPRECATION")
class UuProgressDialog(ctx: Context) : android.app.ProgressDialog(ctx) {
    private val ownerTrace = AppDiagnostics.currentTrace()
    init { prepareDialogWindow(this) }
    override fun setTitle(title: CharSequence?) {
        super.setTitle(ownerTrace?.let { "[${it.id}] ${title ?: ""}" } ?: title)
    }
    override fun setOnCancelListener(listener: android.content.DialogInterface.OnCancelListener?) {
        super.setOnCancelListener { dialog ->
            ownerTrace?.cancelled()
            if (ownerTrace == null) listener?.onCancel(dialog)
            else AppDiagnostics.invokeCallback(ownerTrace) { listener?.onCancel(dialog) }
        }
    }
}

private fun styleDialog(dialog: AlertDialog, positiveStyle: ActionStyle) {
    // The host window may be a scrim; title, content and buttons need one opaque panel.
    (dialog.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0))?.background =
        roundedBg(UuMetrics.px(dialog.context, R.dimen.corner_dialog), dialog.context.getColor(R.color.bg_surface_dim))
    dialog.findViewById<TextView>(android.R.id.message)?.let { UuMetrics.text(it) }
    for (which in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
        dialog.getButton(which)?.let { UuMetrics.action(it, if (which == AlertDialog.BUTTON_POSITIVE) positiveStyle else ActionStyle.TEXT) }
    }
    dialog.listView?.let { list ->
        list.isVerticalScrollBarEnabled = false
        list.isHorizontalScrollBarEnabled = false
        list.dividerHeight = 0
    }
}

internal fun Context.hostActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
