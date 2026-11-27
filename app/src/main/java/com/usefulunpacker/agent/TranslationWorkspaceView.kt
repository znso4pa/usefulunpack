package com.usefulunpacker

import android.text.InputType
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** The translation table replaces the conversation viewport, never the tab strip or dialog host. */
internal class TranslationWorkspaceView(
    private val activity: MainActivity,
    val draft: TranslationDraft,
    private val onChanged: () -> Unit,
    private val onTranslate: () -> Unit,
    private val onSave: () -> Unit,
) : LinearLayout(activity) {
    private var generating = false
    private var showingPreview = false
    private val summary = lineLabel(activity, "", R.color.text_secondary).apply { maxLines = 2 }
    private val agreed = UuSwitch(activity)
    private val acknowledgement = UuToggleRow(activity).apply { bind(activity.getString(R.string.translation_ack), agreed) }
    private val first = UuTextField(activity).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText("1"); setHint(R.string.translation_start); contentDescription = activity.getString(R.string.translation_start) }
    private val count = UuTextField(activity).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(minOf(8, draft.document.size).toString()); setHint(R.string.translation_count); contentDescription = activity.getString(R.string.translation_count) }
    private fun button(label: Int, action: () -> Unit) = UuButton(activity).apply { actionStyle = ActionStyle.TEXT; setText(label); setOnClickListener { action() } }
    private val next = button(R.string.translation_next) {
        safely {
            val (start, size) = range()
            val nextRow = start + size
            if (nextRow >= draft.document.size) throw TranslationProblem("END_OF_FILE")
            first.setText((nextRow + 1).toString()); count.setText(minOf(size, draft.document.size - nextRow).toString())
            table.scrollToPosition(nextRow)
        }
    }
    private val generate = button(R.string.translation_generate) {
        safely { if (!agreed.isChecked) throw TranslationProblem("CONFIRM_PLAIN_TEXT"); range(); onTranslate() }
    }
    private val rangeBar = UuActionBar(activity).apply {
        fun field(label: Int, input: View) = LinearLayout(activity).apply {
            orientation = VERTICAL
            addView(lineLabel(activity, activity.getString(label), R.color.text_secondary).apply { maxLines = 1 })
            addView(input)
        }
        addView(field(R.string.translation_start_short, first), LayoutParams(0, WRAP, 1f))
        addView(field(R.string.translation_count_short, count), LayoutParams(0, WRAP, 1f)); addView(next); addView(generate)
    }
    private val undo = button(R.string.translation_undo) { safely { clearFocus(); draft.undo(); changed() } }
    private val reset = button(R.string.translation_reset_all) {
        UuDialogBuilder(activity, keepChainOpen = false).setTitle(R.string.translation_reset_all)
            .setMessage(R.string.translation_reset_notice).setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.translation_reset_all) { _, _ -> clearFocus(); draft.reset(); changed() }.show()
    }
    private val preview = button(R.string.translation_preview) { preview() }
    private val save = button(R.string.line_edit_save_copy, onSave)
    private val actions = UuActionBar(activity).apply {
        for (view in listOf(undo, reset, preview, save)) addView(view, LayoutParams(0, WRAP, 1f))
    }
    private val columns = lineColumns(activity, lineLabel(activity, activity.getString(R.string.translation_original), R.color.text_secondary),
        lineLabel(activity, activity.getString(R.string.translation_target), R.color.accent))
    private val adapter = LineEditAdapter(activity, draft.document, emptyMap(), { null }, { row, _, text ->
        safely { draft.edit(row, text); onChanged(); refresh(false) }
    }, draft::value, { row -> safely { clearFocus(); draft.reset(row); changed() } }, { row ->
        val from = (first.text.toString().toIntOrNull() ?: 0) - 1
        val size = count.text.toString().toIntOrNull() ?: 0
        row >= from && row.toLong() < from.toLong() + size
    })
    private val table = RecyclerView(activity).apply {
        layoutManager = LinearLayoutManager(activity); itemAnimator = null
        isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
        adapter = this@TranslationWorkspaceView.adapter
    }
    private val previewText = TextView(activity).apply { UuMetrics.text(this); setTextIsSelectable(true) }
    private val previewScroll = UuScrollView(activity).apply { addView(previewText); isVisible = false }
    init {
        orientation = VERTICAL
        setBackgroundColor(activity.getColor(R.color.bg_surface_dark))
        val gap = UuMetrics.px(activity, R.dimen.space_sm)
        setPadding(gap, 0, gap, 0)
        addView(summary); addView(acknowledgement); addView(rangeBar); addView(columns)
        addView(FrameLayout(activity).apply {
            addView(table, FrameLayout.LayoutParams(MATCH, MATCH)); addView(previewScroll, FrameLayout.LayoutParams(MATCH, MATCH))
        }, LayoutParams(MATCH, 0, 1f))
        addView(actions)
        agreed.setOnCheckedChangeListener { _, _ -> refresh(false) }
        first.doAfterTextChanged {
            refresh()
            first.text.toString().toIntOrNull()?.minus(1)?.takeIf { it in 0 until draft.document.size }?.let(table::scrollToPosition)
        }
        count.doAfterTextChanged { refresh() }
        addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val ime = ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            val compact = ime && bottom - top < UuMetrics.px(activity, R.dimen.touch_target) * 6
            rangeBar.isVisible = !compact
            acknowledgement.isVisible = !compact && !agreed.isChecked
            actions.isVisible = !compact
            summary.isVisible = !compact
        }
        refresh(false)
    }
    fun begin(changed: () -> Unit): TranslationTurn {
        if (!agreed.isChecked) throw TranslationProblem("CONFIRM_PLAIN_TEXT")
        val (start, size) = range()
        return draft.begin(start, size, changed)
    }
    private fun range(): Pair<Int, Int> {
        val start = (first.text.toString().toIntOrNull() ?: 0) - 1
        val size = count.text.toString().toIntOrNull() ?: 0
        if (start !in 0 until draft.document.size || size !in 1..8 || start + size > draft.document.size) throw TranslationProblem("INVALID_RANGE")
        return start to size
    }
    private fun changed() { onChanged(); refresh() }
    fun setGenerating(value: Boolean) {
        generating = value
        first.isEnabled = !value; count.isEnabled = !value; next.isEnabled = !value; agreed.isEnabled = !value
        refresh(false)
    }
    fun refresh(rows: Boolean = true) {
        val changed = draft.changedCount()
        summary.text = activity.getString(R.string.translation_summary, visibleAgentText(draft.source.name), changed, draft.document.size, draft.outputEncoding)
        val start = first.text.toString().toIntOrNull()
        val size = count.text.toString().toIntOrNull()
        val valid = start != null && size != null && start >= 1 && size in 1..8 && start.toLong() - 1 + size <= draft.document.size
        generate.isEnabled = !generating && agreed.isChecked && valid
        next.isEnabled = !generating && valid && checkNotNull(start).toLong() - 1 + checkNotNull(size) < draft.document.size
        undo.isEnabled = draft.canUndo(); reset.isEnabled = changed > 0
        save.isEnabled = !generating && changed > 0
        acknowledgement.isVisible = !agreed.isChecked
        if (rows) {
            if (showingPreview) { showingPreview = false; previewScroll.isVisible = false; table.isVisible = true; columns.isVisible = true; preview.setText(R.string.translation_preview) }
            adapter.notifyDataSetChanged()
        }
    }
    fun backToTable(): Boolean { if (!showingPreview) return false; refresh(); return true }
    fun refreshRange() {
        if (showingPreview) { refresh(); return }
        refresh(false)
        val (start, size) = range()
        adapter.notifyItemRangeChanged(start, size)
    }
    private fun preview() {
        if (showingPreview) { refresh(); return }
        val snapshot = draft.snapshot()
        featureThread {
            try {
                val text = TranslationDraft(snapshot).preview()
                activity.runFeatureOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runFeatureOnUiThread
                    if (!isAttachedToWindow) return@runFeatureOnUiThread
                    if (draft.revision != snapshot.revision) { failure(TranslationProblem("STALE_DRAFT")); return@runFeatureOnUiThread }
                    val prefix = if (text.length > 65536) activity.getString(R.string.translation_preview_limit) + "\n\n" else ""
                    previewText.text = prefix + text.take(65536).dropLastWhile(Char::isHighSurrogate)
                    showingPreview = true; previewScroll.isVisible = true; table.isVisible = false; columns.isVisible = false
                    preview.setText(R.string.translation_table)
                }
            } catch (e: Exception) {
                activity.runFeatureOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runFeatureOnUiThread
                    if (isAttachedToWindow) failure(e)
                }
            }
        }
    }
    private fun safely(action: () -> Unit) { try { action() } catch (e: Exception) { failure(e) } }
    private fun failure(e: Exception) {
        val reason = (e as? TranslationProblem)?.reason ?: e.javaClass.simpleName
        val failure = AppDiagnostics.begin("translation_draft").fail(FailureCode.INVALID_RESULT, reason, notifyUser = false)
        UuNotice.failure(activity, activity.getString(R.string.translation_failed, reason, failure.logId), android.widget.Toast.LENGTH_LONG).show()
    }
}

internal fun MainActivity.showTranslationAgent(file: java.io.File, sourceEncoding: String) {
    val ownerTabId = activeTab.tabId
    fun chooseEncoding() {
    UuDialogBuilder(this).setTitle(R.string.translation_output)
        .setItems(LOCALIZATION_ENCODINGS.toTypedArray()) { _, which ->
            val scope = AppDiagnostics.enterIntent("translation_open", newChain = true)
            try {
                featureThread {
                    try {
                        val source = file.canonicalFile
                        val storage = android.os.Environment.getExternalStorageDirectory().canonicalFile
                        if (!source.toPath().startsWith(storage.toPath()) || source.parentFile == storage || java.nio.file.Files.isSymbolicLink(file.toPath())) {
                            throw TranslationProblem("CHOOSE_SHARED_SUBDIRECTORY")
                        }
                        val draft = TranslationDraft.open(source, sourceEncoding, LOCALIZATION_ENCODINGS[which])
                        runFeatureOnUiThread(onUnavailable = { scope.trace.cancelled() }) {
                            if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                            if (tabs.none { it.tabId == ownerTabId }) { scope.trace.cancelled(); return@runFeatureOnUiThread }
                            showAgent(draft, ownerTabId)
                            scope.trace.success()
                        }
                    } catch (e: Exception) {
                        val reason = (e as? TranslationProblem)?.reason ?: e.javaClass.simpleName
                        val failure = AppDiagnostics.begin("translation_open").fail(FailureCode.INVALID_RESULT, reason, notifyUser = false)
                        runFeatureOnUiThread {
                            if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                            UuNotice.failure(this, getString(R.string.translation_failed, reason, failure.logId), android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } finally { AppDiagnostics.leaveIntent(scope) }
        }.setNegativeButton(R.string.action_cancel, null).show()
    }
    UuDialogBuilder(this).setTitle(R.string.translation_title).setMessage(R.string.translation_notice)
        .setNegativeButton(R.string.action_cancel, null)
        .setPositiveButton(R.string.translation_output) { _, _ -> chooseEncoding() }.show()
}
