package com.usefulunpacker

import android.app.AlertDialog
import android.content.Context
import android.content.Context.INPUT_METHOD_SERVICE
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.nio.charset.CharacterCodingException

internal fun showLineEditor(
    activity: AppCompatActivity,
    file: File,
    snapshot: String,
    sourceBytes: ByteArray,
    sourceEncoding: String,
    outputEncoding: String,
) {
    val scope = AppDiagnostics.enterIntent("text_line_edit", newChain = true)
    try {
        LineEditDialog(activity, file, snapshot, sourceBytes, sourceEncoding, outputEncoding, scope.trace).show()
    } catch (e: Exception) {
        scope.trace.fail(FailureCode.UNEXPECTED, "Cannot open line editor (${e.javaClass.simpleName})")
        UuNotice.failure(activity, activity.getString(R.string.line_edit_open_failed), android.widget.Toast.LENGTH_LONG).show()
    } finally {
        AppDiagnostics.leaveIntent(scope)
    }
}

private val lineCopyLock = Any()

private class LineEditDialog(
    private val activity: AppCompatActivity,
    private val file: File,
    private val snapshot: String,
    private val sourceBytes: ByteArray,
    private val sourceEncoding: String,
    private val outputEncoding: String,
    private val trace: OperationTrace,
) {
    private enum class Phase { READY, EXTRACTING, EDITING, CHECKING, SAVING, SAVED }
    private var phase = Phase.READY
    private var document: LineEditDocument? = null
    private var encoding: LineEditEncoding? = null
    private val draft = mutableMapOf<Int, String>()
    private var issue: LineEditIssue? = null
    private var errorStage: Int? = null
    private var notice: CharSequence? = null
    private var traceOpen = false
    @Volatile private var hostClosed = false
    private var editStep: OperationTrace? = null
    private lateinit var dialog: AlertDialog
    private val host = activity as? MainActivity
    private val owner = host?.activeTab?.tabId
    private val pad = UuMetrics.px(activity, R.dimen.space_md)
    private val stageIds = intArrayOf(R.string.line_edit_read, R.string.line_edit_extract, R.string.action_edit,
        R.string.line_edit_validate, R.string.action_save)

    private val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        isFocusableInTouchMode = true
    }
    private val columnHeaders = lineColumns(activity,
        lineLabel(activity, activity.getString(R.string.line_edit_original), R.color.text_secondary),
        lineLabel(activity, activity.getString(R.string.line_edit_modified), R.color.accent)).apply {
        setPadding(pad, pad / 2, pad, pad / 2)
        setBackgroundColor(activity.getColor(R.color.bg_surface))
    }
    private val stages = LinearLayout(activity).apply {
        setPadding(pad, pad / 2, pad, pad / 2)
        setBackgroundColor(activity.getColor(R.color.bg_surface))
    }
    private val stageViews = stageIds.map {
        lineLabel(activity, "", R.color.text_secondary).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            stages.addView(this)
        }
    }
    private val status = lineLabel(activity, "", R.color.text_secondary).apply {
        setPadding(pad, pad / 2, pad, pad / 2)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val placeholder = lineLabel(activity, "", R.color.text_primary).apply {
        gravity = Gravity.CENTER
        setPadding(pad, pad, pad, pad)
    }
    private val table = RecyclerView(activity).apply {
        layoutManager = LinearLayoutManager(activity)
        itemAnimator = null
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        setPadding(pad, 0, pad, 0)
        clipToPadding = false
    }
    private val mainAction = UuButton(activity).apply { actionStyle = ActionStyle.PRIMARY }
    private val close = UuButton(activity).apply { actionStyle = ActionStyle.TEXT; setText(R.string.action_close) }
    private val chainLabel = lineLabel(activity, activity.getString(R.string.line_edit_chain, trace.id), R.color.text_secondary)
    private val controls = UuActionBar(activity)

    fun show() {
        val heading = lineLabel(activity, activity.getString(R.string.line_edit_title), R.color.text_primary).apply {
            UuMetrics.text(this, title = true)
        }
        val filename = lineLabel(activity, visibleAgentText(file.name), R.color.text_secondary).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val header = UuActionBar(activity).apply {
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, 0, pad / 2)
                addView(heading)
                addView(filename)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(close)
        }
        controls.apply {
            setBackgroundColor(activity.getColor(R.color.bg_surface))
            host?.let { ownerActivity ->
                addView(UuButton(activity).apply {
                    actionStyle = ActionStyle.TEXT
                    setText(R.string.diagnostics_view)
                    setOnClickListener { ownerActivity.showDiagnostics(trace.id, ownerTabId = owner) }
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            addView(mainAction, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        root.addView(header)
        root.addView(chainLabel.apply {
            setPadding(pad, 0, pad, pad / 2)
            setTextIsSelectable(true)
        })
        root.addView(stages)
        root.addView(status)
        root.addView(columnHeaders)
        root.addView(FrameLayout(activity).apply {
            addView(table, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(placeholder, FrameLayout.LayoutParams(MATCH, MATCH))
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(controls)
        root.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) reflowChrome()
        }
        dialog = UuDialogBuilder(activity, keepChainOpen = false).setView(root).create()
        dialog.setCancelable(false)
        dialog.setOnKeyListener { _, key, event ->
            if (key != KeyEvent.KEYCODE_BACK) false
            else {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) requestClose()
                true
            }
        }
        close.setOnClickListener { requestClose() }
        mainAction.setOnClickListener {
            when (phase) {
                Phase.READY -> extractLines()
                Phase.EDITING -> saveCopy()
                Phase.SAVED -> dialog.dismiss()
                else -> Unit
            }
        }
        trace.retain()
        traceOpen = true
        try {
            dialog.window?.decorView?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) = onDismiss()
            })
            host?.rootDialogToTab(dialog, owner)
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            if (host != null) dialog.belowTabs(fillViewport = true, also = ::onDismiss)
            else dialog.setOnDismissListener { onDismiss() }
            render()
            dialog.show()
            root.requestFocus()
        } catch (e: Exception) {
            finishTrace()
            throw e
        }
    }

    private fun extractLines() {
        phase = Phase.EXTRACTING
        errorStage = null
        notice = null
        render()
        featureThread(trace = trace) {
            val decoding = AppDiagnostics.begin("read_text")
            val target = try {
                LineEditEncoding.forSnapshot(sourceEncoding, sourceBytes).verifySource(sourceBytes)
                LineEditEncoding.forSnapshot(outputEncoding, sourceBytes).also { decoding.success() }
            } catch (e: Exception) {
                decoding.rejectForRetry(FailureCode.INVALID_RESULT, "INVALID_TEXT_ENCODING ${e.javaClass.simpleName}")
                null
            }
            val lines = target?.let {
                val extract = AppDiagnostics.begin("extract_lines")
                LineEditDocument(snapshot).also { lines ->
                    extract.note("rows=${lines.size}")
                    extract.success()
                }
            }
            activity.runFeatureOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || !dialog.isShowing) return@runFeatureOnUiThread
                if (lines == null) {
                    phase = Phase.READY
                    errorStage = 0
                    notice = activity.getString(R.string.line_edit_decode_failed)
                } else {
                    document = lines
                    encoding = target
                    table.adapter = LineEditAdapter(activity, lines, draft, { issue }, ::changeRow)
                    phase = Phase.EDITING
                    editStep = AppDiagnostics.withTrace(trace) { AppDiagnostics.begin("edit_lines") }
                }
                render()
            }
        }
    }

    private fun changeRow(row: Int, original: String, modified: String) {
        if (phase != Phase.EDITING) return
        val previousCount = draft.size
        val hadNotice = notice != null
        if (original == modified) draft.remove(row) else draft[row] = modified
        if (issue?.row == row) issue = null
        if (issue == null) {
            errorStage = null
            notice = null
        }
        if (previousCount != draft.size || hadNotice != (notice != null)) refreshStatus()
    }

    private fun saveCopy() {
        val lines = document ?: return
        val target = encoding ?: return
        if (draft.isEmpty()) return
        val edits = draft.toMap()
        editStep?.let { step -> step.note("changed_lines=${edits.size}"); step.success() }
        editStep = null
        phase = Phase.CHECKING
        issue = null
        errorStage = null
        notice = null
        closeKeyboard()
        render()
        featureThread(trace = trace) {
            val check = AppDiagnostics.begin("validate_patch")
            val invalid = lines.validate(edits, target)
            val bytes = if (invalid == null) try {
                target.encode(lines.rebuild(edits))
            } catch (_: CharacterCodingException) { null } else null
            if (hostClosed) {
                check.cancelled()
                return@featureThread
            }
            if (bytes == null) {
                check.rejectForRetry(FailureCode.INVALID_RESULT,
                    "reason=${invalid?.problem ?: LineEditProblem.UNMAPPABLE_TEXT} row=${invalid?.row?.plus(1) ?: "unknown"}")
                activity.runFeatureOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed || !dialog.isShowing) return@runFeatureOnUiThread
                    restoreEditing(3, invalid, invalid?.message(activity) ?: activity.getString(R.string.line_edit_unmappable))
                }
                return@featureThread
            }
            check.success()
            activity.runFeatureOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || !dialog.isShowing) return@runFeatureOnUiThread
                phase = Phase.SAVING
                render()
            }
            val output = try {
                synchronized(lineCopyLock) {
                    val parent = file.absoluteFile.parentFile ?: throw java.io.IOException("Missing text destination")
                    uniqueFile(parent, lineEditCopyName(file)).also { destination ->
                        writeOutputAtomically(destination, replaceExisting = false) { it.writeBytes(bytes); true }
                    }
                }
            } catch (e: Exception) {
                AppDiagnostics.begin("save_copy").rejectForRetry(FailureCode.IO, "OUTPUT_WRITE_FAILED ${e.javaClass.simpleName}")
                null
            }
            activity.runFeatureOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || !dialog.isShowing) return@runFeatureOnUiThread
                if (output == null) {
                    restoreEditing(4, null, activity.getString(R.string.line_edit_save_failed))
                } else {
                    phase = Phase.SAVED
                    notice = activity.getString(R.string.line_edit_receipt, visibleAgentText(output.name), edits.size)
                    finishTrace()
                    render()
                }
            }
        }
    }

    private fun restoreEditing(stage: Int, invalid: LineEditIssue?, message: String) {
        phase = Phase.EDITING
        errorStage = stage
        issue = invalid
        notice = message
        editStep = AppDiagnostics.withTrace(trace) { AppDiagnostics.begin("edit_lines") }
        render()
        invalid?.let {
            table.adapter?.notifyItemChanged(it.row)
            table.scrollToPosition(it.row)
        }
    }

    private fun closeKeyboard() {
        val focused = dialog.currentFocus ?: return
        (activity.getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(focused.windowToken, 0)
        focused.clearFocus()
        root.requestFocus()
    }

    private fun requestClose() {
        when {
            isBusy() -> return
            phase == Phase.EDITING && draft.isNotEmpty() -> {
                closeKeyboard()
                UuDialogBuilder(activity, keepChainOpen = false)
                    .setTitle(R.string.editor_unsaved_changes)
                    .setMessage(R.string.line_edit_discard_prompt)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.editor_discard) { _, _ -> dialog.dismiss() }
                    .create().also {
                        host?.rootDialogToTab(it, owner)
                        it.belowTabs(fitContent = true)
                        it.show()
                    }
            }
            else -> dialog.dismiss()
        }
    }

    private fun onDismiss() {
        hostClosed = true
        if (traceOpen && phase != Phase.SAVED && phase != Phase.CHECKING && phase != Phase.SAVING) {
            if (trace.chain?.failureStatus() == null) (editStep ?: trace).cancelled()
            else editStep?.skipped()
        }
        finishTrace()
    }

    private fun finishTrace() {
        if (!traceOpen) return
        traceOpen = false
        trace.release()
    }

    private fun isBusy() = phase == Phase.EXTRACTING || phase == Phase.CHECKING || phase == Phase.SAVING

    private fun reflowChrome() {
        // Keep room for the focused row when a landscape IME consumes most of the window.
        val compact = phase == Phase.EDITING && root.height in 1 until (5 * close.minimumHeight)
        val keyboard = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        chainLabel.isVisible = !compact
        stages.isVisible = !compact
        controls.isVisible = !compact || !keyboard
        status.isVisible = !compact || notice != null
    }

    private fun render() {
        val hasRows = (document?.size ?: 0) > 0
        val showTable = phase == Phase.EDITING && hasRows
        table.isVisible = showTable
        columnHeaders.isVisible = showTable
        placeholder.isVisible = !showTable
        placeholder.text = when (phase) {
            Phase.READY -> activity.getString(R.string.line_edit_start)
            Phase.EXTRACTING -> activity.getString(R.string.msg_loading)
            Phase.EDITING -> activity.getString(R.string.line_edit_empty)
            Phase.CHECKING, Phase.SAVING -> activity.getString(R.string.line_edit_saving)
            Phase.SAVED -> notice
        }
        close.isEnabled = !isBusy()
        mainAction.setText(when (phase) {
            Phase.READY, Phase.EXTRACTING -> R.string.line_edit_translate
            Phase.SAVED -> R.string.action_close
            else -> R.string.line_edit_save_copy
        })
        refreshStatus()
    }

    private fun refreshStatus() {
        reflowChrome()
        mainAction.isEnabled = phase == Phase.READY || phase == Phase.SAVED || phase == Phase.EDITING && draft.isNotEmpty()
        status.setTextColor(activity.getColor(if (errorStage == null) R.color.text_secondary else R.color.state_error))
        status.text = when {
            notice != null && phase != Phase.SAVED -> notice
            document != null -> activity.getString(R.string.line_edit_summary, document?.size ?: 0, draft.size, encoding?.description.orEmpty())
            else -> outputEncoding
        }
        val done = when (phase) {
            Phase.READY, Phase.EXTRACTING -> 0
            Phase.EDITING -> if (errorStage == 4) 4 else 2
            Phase.CHECKING -> 3
            Phase.SAVING -> 4
            Phase.SAVED -> 5
        }
        stageViews.forEachIndexed { index, label ->
            val (symbol, color, state) = when {
                index == errorStage -> Triple("!", R.color.state_error, R.string.task_center_failed)
                index < done -> Triple("✓", R.color.state_success, R.string.task_center_success)
                index == done && phase != Phase.READY ->
                    Triple("•", R.color.accent, R.string.task_center_running)
                else -> Triple("·", R.color.text_secondary, R.string.line_edit_pending)
            }
            val step = activity.getString(stageIds[index])
            label.text = "$symbol $step"
            label.contentDescription = activity.getString(R.string.line_edit_stage_description, step, activity.getString(state))
            label.setTextColor(activity.getColor(color))
        }
    }
}

private class LineEditAdapter(
    private val activity: AppCompatActivity,
    private val document: LineEditDocument,
    private val draft: Map<Int, String>,
    private val issue: () -> LineEditIssue?,
    private val onEdit: (Int, String, String) -> Unit,
) : RecyclerView.Adapter<LineEditAdapter.Row>() {
    class Row(val root: View, val number: TextView, val source: TextView, val input: UuTextField, val mark: TextView) :
        RecyclerView.ViewHolder(root) {
        var watcher: android.text.TextWatcher? = null
    }

    override fun getItemCount() = document.size

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Row {
        val number = lineLabel(activity, "", R.color.text_secondary).apply { typeface = Typeface.MONOSPACE }
        val source = lineLabel(activity, "", R.color.text_primary).apply { setTextIsSelectable(true) }
        val original = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, UuMetrics.px(activity, R.dimen.space_sm), 0, 0)
            addView(number)
            addView(source)
        }
        val input = UuTextField(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            setHorizontallyScrolling(false)
            setHint(R.string.line_edit_blank)
        }
        val mark = lineLabel(activity, "", R.color.state_success)
        val edits = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(input, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(mark)
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            addView(lineColumns(activity, original, edits).apply {
                val gap = UuMetrics.px(activity, R.dimen.space_sm)
                setPadding(0, gap, 0, gap)
            })
            addView(View(activity).apply { setBackgroundColor(activity.getColor(R.color.hairline)) },
                LinearLayout.LayoutParams(MATCH, 1))
        }
        return Row(row, number, source, input, mark)
    }

    override fun onBindViewHolder(holder: Row, position: Int) {
        holder.watcher?.let(holder.input::removeTextChangedListener)
        val original = document.original(position)
        holder.number.text = activity.getString(R.string.line_edit_row, position + 1)
        holder.source.text = original.ifEmpty { activity.getString(R.string.line_edit_blank) }
        holder.source.setTextColor(activity.getColor(if (original.isEmpty()) R.color.text_hint else R.color.text_primary))
        holder.input.contentDescription = activity.getString(R.string.line_edit_row_input, position + 1)
        holder.input.setText(draft[position] ?: original)
        fun paint(value: String) {
            val rowIssue = issue()?.takeIf { it.row == position }
            holder.mark.isVisible = rowIssue != null || value != original
            holder.mark.setTextColor(activity.getColor(if (rowIssue != null) R.color.state_error else R.color.state_success))
            holder.mark.text = rowIssue?.message(activity) ?: activity.getString(
                if (value.isEmpty()) R.string.line_edit_cleared else R.string.line_edit_changed)
        }
        paint(draft[position] ?: original)
        holder.watcher = holder.input.doAfterTextChanged {
            val value = it?.toString().orEmpty()
            onEdit(position, original, value)
            paint(value)
        }
    }
}

private fun LineEditIssue.message(context: Context): String = context.getString(R.string.line_edit_row_error,
    row + 1, context.getString(when (problem) {
        LineEditProblem.LINE_BREAK -> R.string.line_edit_line_break
        LineEditProblem.PLACEHOLDER_MISMATCH -> R.string.line_edit_marker_error
        LineEditProblem.UNMAPPABLE_TEXT -> R.string.line_edit_unmappable
    }))

private fun lineLabel(context: Context, label: CharSequence, color: Int) = TextView(context).apply {
    UuMetrics.text(this)
    setTextColor(context.getColor(color))
    text = label
}

private fun lineColumns(context: Context, original: View, edits: View) = LinearLayout(context).apply {
    gravity = Gravity.TOP
    addView(original, LinearLayout.LayoutParams(0, WRAP, 1f))
    addView(edits, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
        marginStart = UuMetrics.px(context, R.dimen.space_md)
    })
}
