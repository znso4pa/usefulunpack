package com.usefulunpacker

import android.os.Environment
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.usefulunpacker.archive.OpScheduler
import java.io.File

private data class TaskCenterItem(val id: String, val feature: String, val state: String, val outputs: List<String>)

internal fun MainActivity.showTaskCenter() {
    val tab = activeTab
    val body = taskCenterForm()
    val dialog = UuDialogBuilder(this, keepChainOpen = false).setTitle(R.string.task_center_title)
        .setView(UuScrollView(this).apply { addView(body) })
        .setNegativeButton(R.string.action_close, null).create()
    val detailText = TextView(this).apply { UuMetrics.text(this); setText(R.string.task_center_loading) }
    body.addView(detailText)
    lateinit var refresh: Runnable
    var previous: Pair<List<TaskCenterItem>, Boolean>? = null
    refresh = Runnable {
        if (isFinishing || isDestroyed || !dialog.isShowing) return@Runnable
        if (activeTab.tabId != tab.tabId) {
            body.postDelayed(refresh, 1000)
            return@Runnable
        }
        featureThread(trace = null) {
            val store = AppDiagnostics.store
            val nativeQueue = OpScheduler.queuedChains()
            val items = store?.snapshot(includeOutputs = true).orEmpty()
                .filter { it.id != "application" }
                .map {
                    val ownerState = TaskCenterControls.state(it.id) ?: it.status
                    val state = if (ownerState == "RUNNING" && it.id in nativeQueue) "QUEUED" else ownerState
                    TaskCenterItem(it.id, it.feature, state, it.outputs)
                }
                .sortedBy { it.state !in setOf("QUEUED", "RUNNING", "CANCELLING") }
            val update = items to (store?.storageError != null)
            runFeatureOnUiThread {
                if (isFinishing || isDestroyed || !dialog.isShowing) return@runFeatureOnUiThread
                if (previous != update) {
                    body.removeAllViews()
                    if (update.second || items.isEmpty()) {
                        detailText.setText(if (update.second) R.string.diagnostics_storage_failed else R.string.task_center_empty)
                        body.addView(detailText)
                    }
                    for (item in items) {
                        body.addView(UuActionRow(this).apply {
                            bind(taskLabel(item), getString(R.string.task_center_row_detail, item.id, item.outputs.size),
                                navigation = true) { showTaskReceipt(item, tab) }
                        })
                    }
                    previous = update
                }
                body.postDelayed(refresh, 1000)
            }
        }
    }
    rootDialogToTab(dialog, tab.tabId)
    dialog.window?.decorView?.setTag(R.id.taskCenterPanel, true)
    dialog.belowTabs { body.removeCallbacks(refresh) }
    dialog.show()
    body.post(refresh)
}

private fun MainActivity.showTaskReceipt(item: TaskCenterItem, tab: TabState) {
    val body = taskCenterForm()
    body.addView(TextView(this).apply {
        UuMetrics.text(this)
        setTextIsSelectable(true)
        text = getString(R.string.task_center_row_detail, item.id, item.outputs.size)
    })
    body.addView(UuActionRow(this).apply {
        bind(getString(R.string.diagnostics_title), navigation = true) { showDiagnostics(item.id, ownerTabId = tab.tabId) }
    })
    for (path in item.outputs) {
        body.addView(UuActionRow(this).apply {
            bind(visibleAgentText(File(path).name), getString(R.string.task_center_output_location), navigation = true) {
                openTaskOutput(path, tab, this)
            }
        })
    }
    val builder = UuDialogBuilder(this, keepChainOpen = false)
    builder.setTitle(taskLabel(item))
        .setView(UuScrollView(this).apply { addView(body) })
        .setNegativeButton(R.string.action_close, null)
    if (TaskCenterControls.canCancel(item.id)) {
        builder.setNeutralButton(R.string.task_center_stop) { _, _ ->
            UuDialogBuilder(this, keepChainOpen = false).setTitle(R.string.task_center_stop_confirm)
                .setMessage(item.id)
                .setNegativeButton(R.string.action_close, null)
                .setPositiveButton(R.string.action_confirm) { _, _ -> TaskCenterControls.cancel(item.id) }
                .show().keepTabsTappable()
        }
    }
    val dialog = builder.create()
    dialog.window?.decorView?.setTag(R.id.taskCenterPanel, true)
    rootDialogToTab(dialog, tab.tabId)
    dialog.keepTabsTappable()
    dialog.show()
}

private fun MainActivity.openTaskOutput(path: String, tab: TabState, anchor: View) {
    featureThread(trace = null) {
        val output = observedAttempt("task_output_lookup") {
            taskOutputInStorage(Environment.getExternalStorageDirectory(), path)
        }.getOrNull()
        val directory = output?.let { if (it.isDirectory) it else it.parentFile }
        runFeatureOnUiThread {
            if (isFinishing || isDestroyed || activeTab.tabId != tab.tabId || !anchor.isAttachedToWindow) {
                return@runFeatureOnUiThread
            }
            if (directory == null) {
                UuNotice.makeText(this, R.string.task_center_output_missing, Toast.LENGTH_LONG).show()
            } else {
                val ownerDialogs = rootedDialogs.filter { it.ownerTabId == tab.tabId }
                val taskDialogs = ownerDialogs.filter { it.dlg.window?.decorView?.getTag(R.id.taskCenterPanel) == true }
                val opensNewTab = ownerDialogs.any { it !in taskDialogs && it.dlg.isShowing }
                if (opensNewTab && tabs.size >= MainActivity.MAX_TABS) {
                    UuNotice.makeText(this, R.string.msg_max_tabs, Toast.LENGTH_SHORT).show()
                    return@runFeatureOnUiThread
                }
                taskDialogs.asReversed().forEach { it.dlg.dismiss() }
                if (opensNewTab) addTab(directory) else enterBrowserAt(tab, directory)
            }
        }
    }
}

private fun MainActivity.taskLabel(item: TaskCenterItem): String {
    val source = when {
        item.feature == "text_line_edit" -> R.string.line_edit_title
        item.feature in setOf("directory_compare", "uu_dircmp", "mcp_COMPARE_DIRECTORY") -> R.string.directory_title
        item.feature in setOf("localization_check", "uu_check", "mcp_LOCALIZATION_CHECK") -> R.string.localization_title
        item.feature.startsWith("agent_") -> R.string.agent_title
        item.feature.startsWith("mcp_") -> R.string.task_center_mcp
        item.feature.contains("extract") -> R.string.task_center_extract
        item.feature.contains("compress") || item.feature.contains("pack") -> R.string.task_center_pack
        else -> R.string.task_center_app
    }
    val state = when (item.state) {
        "QUEUED" -> R.string.task_center_queued
        "RUNNING" -> R.string.task_center_running
        "CANCELLING" -> R.string.task_center_cancelling
        "SUCCESS" -> R.string.task_center_success
        "PARTIAL_FAILURE" -> R.string.task_center_partial
        "FAILED" -> R.string.task_center_failed
        "CANCELLED" -> R.string.msg_cancelled
        else -> R.string.task_center_interrupted
    }
    return getString(R.string.task_center_row, getString(source), getString(state))
}

private fun MainActivity.taskCenterForm() = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val inset = UuMetrics.px(context, R.dimen.space_md)
    setPadding(inset, 0, inset, inset)
}
