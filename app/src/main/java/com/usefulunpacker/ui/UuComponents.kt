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
        view.setTypeface(view.typeface, if (style == ActionStyle.PRIMARY || style == ActionStyle.DANGER) Typeface.BOLD else Typeface.NORMAL)
        val accent = ctx.getColor(if (style == ActionStyle.DANGER) R.color.state_error else R.color.accent)
        val filled = style == ActionStyle.PRIMARY
        val color = if (filled) ctx.getColor(R.color.accent_on) else accent
        view.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(ctx.getColor(R.color.text_disabled), color)))
        view.backgroundTintList = null
        view.background = GradientDrawable().apply {
            cornerRadius = px(ctx, R.dimen.corner_input).toFloat()
            setColor(if (filled) accent else if (style == ActionStyle.SECONDARY) ctx.getColor(R.color.accent_soft) else android.graphics.Color.TRANSPARENT)
        }
        view.setPadding(px(ctx, R.dimen.space_lg), px(ctx, R.dimen.space_sm), px(ctx, R.dimen.space_lg), px(ctx, R.dimen.space_sm))
        view.foreground = ripple(ctx.getColor(R.color.ripple_light))
    }
}

class UuButton @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : androidx.appcompat.widget.AppCompatButton(ctx, attrs) {
    override fun setOnClickListener(listener: View.OnClickListener?) {
        val trace = AppDiagnostics.currentTrace()
        super.setOnClickListener(if (listener == null || trace == null) listener else View.OnClickListener { v ->
            AppDiagnostics.invokeCallback(trace) { listener.onClick(v) }
        })
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
    init { applyStyle() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); applyStyle(); UuMetrics.ensureTarget(this) }
    private fun applyStyle() {
        val ctx = context
        minimumWidth = UuMetrics.px(ctx, R.dimen.touch_target)
        minimumHeight = UuMetrics.px(ctx, R.dimen.touch_target)
        val padding = UuMetrics.px(ctx, R.dimen.space_md)
        setPadding(padding, padding, padding, padding)
        background = ripple(ctx.getColor(R.color.ripple_light))
        imageTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(ctx.getColor(R.color.text_disabled), ctx.getColor(R.color.accent)))
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

class UuSwitch @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : androidx.appcompat.widget.SwitchCompat(ctx, attrs) {
    init { UuMetrics.text(this); minHeight = UuMetrics.px(ctx, R.dimen.touch_target) }
}

class UuCheckBox @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : androidx.appcompat.widget.AppCompatCheckBox(ctx, attrs) {
    init { UuMetrics.text(this); minHeight = UuMetrics.px(ctx, R.dimen.touch_target) }
}

class UuRadioButton @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : androidx.appcompat.widget.AppCompatRadioButton(ctx, attrs) {
    init { UuMetrics.text(this); minHeight = UuMetrics.px(ctx, R.dimen.touch_target) }
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
    init {
        UuMetrics.text(this, title = true)
        minHeight = UuMetrics.px(ctx, R.dimen.row_setting)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
        background = ripple(ctx.getColor(R.color.ripple_light))
        isFocusable = true
    }
    fun bind(title: CharSequence, value: CharSequence? = null, navigation: Boolean = false, onClick: (() -> Unit)? = null) {
        val ownerTrace = AppDiagnostics.currentTrace()
        text = if (value.isNullOrEmpty()) title else android.text.SpannableStringBuilder().apply {
            append(title); append('\n')
            val start = length
            append(value)
            setSpan(android.text.style.ForegroundColorSpan(context.getColor(R.color.text_secondary)), start, length, 0)
            setSpan(android.text.style.AbsoluteSizeSpan(resources.getDimension(R.dimen.text_lg).toInt()), start, length, 0)
        }
        isEnabled = onClick != null
        setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, if (navigation) R.drawable.ic_chevron_right else 0, 0)
        compoundDrawablePadding = context.dp(12)
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
        if (keepChainOpen) ownerTrace?.retain()
        var released = false
        dialog.window?.decorView?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(v: View) {
                if (!released) { released = true; if (keepChainOpen) ownerTrace?.release() }
            }
            override fun onViewAttachedToWindow(v: View) {
                v.post {
                    val activity = context.hostActivity()
                    if (activity?.isFinishing == true || activity?.isDestroyed == true || !dialog.isShowing) return@post
                    styleDialog(dialog, positiveStyle)
                }
            }
        })
    }
}

/** Legacy progress semantics are preserved while IDs and cancellation use the common chain. */
@Suppress("DEPRECATION")
class UuProgressDialog(ctx: Context) : android.app.ProgressDialog(ctx) {
    private val ownerTrace = AppDiagnostics.currentTrace()
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
