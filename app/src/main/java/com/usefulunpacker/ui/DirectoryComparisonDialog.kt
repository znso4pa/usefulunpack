package com.usefulunpacker

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal fun showDirectoryComparison(activity: AppCompatActivity, source: File, target: File? = null): AlertDialog? {
    val scope = AppDiagnostics.enterIntent("directory_compare", newChain = true)
    return try { DirectoryComparisonDialog(activity, source, target).show() }
    catch (e: Exception) {
        scope.trace.fail(FailureCode.UNEXPECTED, "Cannot open directory comparison (${e.javaClass.simpleName})")
        UuNotice.failure(activity, activity.getString(R.string.directory_failed), Toast.LENGTH_LONG).show()
        null
    } finally { AppDiagnostics.leaveIntent(scope) }
}

private class DirectoryComparisonDialog(private val activity: AppCompatActivity, private var source: File, initialTarget: File?) {
    private var target: File? = initialTarget
    private var filter: DirectoryChange? = null
    private var busy = false
    private var closed = false
    private var cancellation = AtomicBoolean()
    private lateinit var dialog: AlertDialog
    private val pad = UuMetrics.px(activity, R.dimen.space_md)
    private fun label(value: CharSequence) = TextView(activity).apply {
        text = value; textSize = 13f; setTextColor(activity.getColor(R.color.text_secondary)); setPadding(pad, pad, pad, pad)
    }
    private val status = label(activity.getString(R.string.directory_hint))
    private val viewport = FrameLayout(activity)
    private val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(status, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(viewport, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }
    fun show(): AlertDialog {
        dialog = UuDialogBuilder(activity).setTitle(R.string.directory_title).setView(root)
            .setPositiveButton(R.string.localization_run, null).setNeutralButton(R.string.localization_inputs, null)
            .setNegativeButton(R.string.action_close, null).create()
        dialog.belowTabs(fillViewport = true)
        dialog.setOnDismissListener { closed = true; cancellation.set(true) }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).onUserIntent("directory_compare") { compare() }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { if (!busy) setup() }
        }
        setup()
        dialog.show()
        return dialog
    }
    private fun setup() {
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(activity.getString(R.string.directory_hint)))
            addView(UuActionRow(activity).apply {
                bind(activity.getString(R.string.localization_source, ""), visibleAgentText(source.path), navigation = true) {
                    pick(source) { source = it; setup() }
                }
            })
            addView(UuActionRow(activity).apply {
                bind(activity.getString(R.string.directory_target, ""), target?.path?.let { visibleAgentText(it) }
                    ?: activity.getString(R.string.localization_choose), navigation = true) {
                    pick(target ?: source) { target = it; setup() }
                }
            })
        }
        viewport.removeAllViews()
        viewport.addView(UuScrollView(activity).apply { addView(form) }, FrameLayout.LayoutParams(MATCH, MATCH))
    }
    private fun pick(start: File, onPick: (File) -> Unit) {
        val host = activity as? MainActivity
        val candidates = host?.tabs.orEmpty().filter { it.pickerCallback == null }.map { it.currentDir }.distinctBy { it.path }
        val labels = listOf(activity.getString(R.string.directory_browse)) + candidates.map { visibleAgentText(it.path) }
        UuDialogBuilder(activity).setTitle(R.string.directory_pick).setItems(labels.toTypedArray()) { _, which ->
            if (which == 0) showFolderPicker(activity, start, onPick = { if (!closed) onPick(it) })
            else if (!closed) onPick(candidates[which - 1])
        }.setNegativeButton(R.string.action_cancel, null).show()
    }
    private fun buttons(enabled: Boolean) {
        busy = !enabled
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = enabled
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = enabled
    }
    private fun compare() {
        if (busy) return
        val right = target ?: run {
            UuNotice.makeText(activity, activity.getString(R.string.directory_pick), Toast.LENGTH_SHORT).show(); return
        }
        val left = source
        val cancelled = AtomicBoolean().also { cancellation = it }
        buttons(false)
        viewport.removeAllViews()
        viewport.addView(label(activity.getString(R.string.msg_loading)), FrameLayout.LayoutParams(MATCH, MATCH))
        val trace = AppDiagnostics.begin("directory_compare")
        status.text = activity.getString(R.string.localization_running, trace.id)
        featureThread {
            try {
                val result = DirectoryComparator.compare(left, right, { cancelled.get() }) { n, _ ->
                    activity.runFeatureOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runFeatureOnUiThread
                        if (!closed) status.text = activity.getString(R.string.directory_progress, n)
                    }
                }
                if (cancelled.get()) throw CancellationException()
                if (result.complete) trace.success() else trace.fail(FailureCode.PARTIAL_FAILURE, "Directory comparison incomplete", notifyUser = false)
                trace.note("status=${result.status} visited=${result.visited} differences=${result.differences} limited=${result.limited}")
                activity.runFeatureOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runFeatureOnUiThread
                    if (closed) return@runFeatureOnUiThread
                    buttons(true)
                    status.text = listOf(activity.getString(if (result.complete) R.string.directory_finished else R.string.directory_incomplete),
                        activity.getString(R.string.line_edit_chain, trace.id)).joinToString("\n")
                    showList(result)
                    if (!result.complete) UuNotice.failure(activity, activity.getString(R.string.directory_incomplete), Toast.LENGTH_LONG).show()
                }
            } catch (_: CancellationException) { trace.cancelled() }
            catch (e: Exception) {
                trace.fail(FailureCode.IO, "Directory comparison failed (${e.javaClass.simpleName})")
                activity.runFeatureOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runFeatureOnUiThread
                    if (!closed) { buttons(true); status.text = activity.getString(R.string.directory_failed); setup() }
                }
            }
        }
    }
    private fun showList(result: DirectoryComparisonReport) {
        val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(UuActionRow(activity).apply {
            bind(activity.getString(R.string.directory_filter, filter?.let { activity.getString(it.label()) } ?: activity.getString(R.string.directory_all)), navigation = true) {
                val choices = listOf(null) + DirectoryChange.entries
                val labels = choices.map { it?.let { state -> activity.getString(state.label()) } ?: activity.getString(R.string.directory_all) }.toTypedArray()
                UuDialogBuilder(activity).setTitle(R.string.directory_title).setSingleChoiceItems(labels, choices.indexOf(filter)) { picker, index ->
                    filter = choices[index]; picker.dismiss(); showList(result)
                }.setNegativeButton(R.string.action_cancel, null).show()
            }
        })
        val summary = directorySummary(result) { id, args -> activity.getString(id, *args) } +
            if (result.limited) "\n" + activity.getString(R.string.directory_limited) else ""
        val rows = result.entries.filter { filter == null || it.state == filter }
        val list = ListView(activity).apply { isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false }
        list.adapter = object : BaseAdapter() {
            override fun getCount() = rows.size + 1
            override fun getItem(position: Int): Any = if (position == 0) summary else rows[position - 1]
            override fun getItemId(position: Int) = position.toLong()
            override fun getViewTypeCount() = 2
            override fun getItemViewType(position: Int) = if (position == 0) 0 else 1
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) = position > 0
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                if (position == 0) return (convertView as? TextView ?: label("")).apply { text = summary }
                val row = rows[position - 1]
                return (convertView as? UuActionRow ?: UuActionRow(activity)).apply {
                    bind(visibleAgentText(row.path), activity.getString(row.state.label()) + if (row.reason.isEmpty()) "" else " · ${row.reason}", navigation = true) { detail(result, row) }
                }
            }
        }
        panel.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
        viewport.removeAllViews(); viewport.addView(panel, FrameLayout.LayoutParams(MATCH, MATCH))
    }
    private fun detail(result: DirectoryComparisonReport, row: DirectoryComparisonEntry) {
        if (busy) return
        buttons(false)
        val cancelled = cancellation
        val trace = AppDiagnostics.begin("directory_preview")
        featureThread {
            try {
                val change = directoryComparisonPreview(result, row) { if (cancelled.get()) throw CancellationException() }
                trace.success()
                activity.runFeatureOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runFeatureOnUiThread
                    if (closed) return@runFeatureOnUiThread
                    buttons(true)
                    val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    val actions = UuActionBar(activity)
                    val usable = !row.directory && row.state !in setOf(DirectoryChange.UNREADABLE, DirectoryChange.SKIPPED)
                    if (usable) {
                        fun stale() {
                            AppDiagnostics.begin("directory_preview").fail(FailureCode.INVALID_RESULT, "Comparison snapshot changed", notifyUser = false)
                            status.setText(R.string.directory_stale)
                            UuNotice.failure(activity, activity.getString(R.string.directory_stale), Toast.LENGTH_LONG).show()
                        }
                        fun preview(root: File, expected: ComparedFileStamp?) {
                            if (expected == null) return
                            try {
                                val file = DirectoryComparator.safeFile(root, row.path)
                                if (fileStamp(file) != expected) { stale(); return }
                                previewLocalFile(activity, file)
                            } catch (_: java.io.IOException) { stale() }
                        }
                        val previewable = change.lines.isNotEmpty() || row.path.substringAfterLast('.').lowercase() in PREVIEW_EXTS
                        if (previewable && row.sourceStamp != null) actions.addView(UuButton(activity).apply { setText(R.string.directory_open_source); setOnClickListener { preview(result.source, row.sourceStamp) } })
                        if (previewable && row.targetStamp != null) actions.addView(UuButton(activity).apply { setText(R.string.directory_open_target); setOnClickListener { preview(result.target, row.targetStamp) } })
                        if (row.sourceStamp != null && row.targetStamp != null) actions.addView(UuButton(activity).apply {
                            setText(R.string.localization_title)
                            setOnClickListener {
                                try { showLocalizationCheck(activity, DirectoryComparator.safeFile(result.source, row.path), DirectoryComparator.safeFile(result.target, row.path)) }
                                catch (_: java.io.IOException) { stale() }
                            }
                        })
                    }
                    panel.addView(actions)
                    panel.addView(AgentChangesView(activity) { showList(result) }.apply {
                        show(change, if (change.lines.isNotEmpty() && change.lines.none { it.kind != 0 }) activity.getString(R.string.directory_byte_only) else null)
                    }, LinearLayout.LayoutParams(MATCH, 0, 1f))
                    viewport.removeAllViews(); viewport.addView(panel, FrameLayout.LayoutParams(MATCH, MATCH))
                }
            } catch (_: CancellationException) { trace.cancelled() }
            catch (e: Exception) {
                trace.fail(FailureCode.INVALID_RESULT, "Comparison preview unavailable (${e.javaClass.simpleName})")
                activity.runFeatureOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runFeatureOnUiThread
                    if (!closed) { buttons(true); status.setText(R.string.directory_stale) }
                }
            }
        }
    }
    private fun fileStamp(file: File): ComparedFileStamp? = try {
        val attrs = java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        if (attrs.isRegularFile) ComparedFileStamp.of(attrs) else null
    } catch (_: java.io.IOException) { null }
}

internal fun DirectoryChange.label(): Int = when (this) {
    DirectoryChange.ADDED -> R.string.directory_added
    DirectoryChange.REMOVED -> R.string.directory_removed
    DirectoryChange.MODIFIED -> R.string.directory_modified
    DirectoryChange.TYPE_CHANGED -> R.string.directory_type_changed
    DirectoryChange.UNREADABLE -> R.string.directory_unreadable
    DirectoryChange.SKIPPED -> R.string.directory_skipped
}
