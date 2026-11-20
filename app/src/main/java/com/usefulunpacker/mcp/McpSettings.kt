package com.usefulunpacker

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.Environment
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import org.json.JSONObject

internal fun MainActivity.showLocalMcp() {
    val listener = localMcp?.takeIf { it.running }
    if (listener == null) {
        UuDialogBuilder(this).setTitle(R.string.mcp_title)
            .setMessage(getString(R.string.mcp_explain, DEFAULT_MCP_WORKSPACE))
            .setNegativeButton(R.string.action_close, null)
            .setNeutralButton(R.string.mcp_choose) { _, _ ->
                showFolderPicker(this, Environment.getExternalStorageDirectory()) { directory ->
                    startLocalMcp(directory)
                }
            }
            .setPositiveButton(R.string.mcp_start_default) { _, _ -> startLocalMcp() }
            .show().also { it.keepTabsTappable() }
        return
    }

    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val gutter = resources.getDimensionPixelSize(R.dimen.space_xl)
        setPadding(gutter, gutter / 2, gutter, gutter / 2)
        addView(TextView(context).apply {
            UuMetrics.text(this)
            text = getString(R.string.mcp_endpoint, listener.url, listener.directory.path)
            setTextIsSelectable(true)
        })
        addView(UuActionRow(context).apply {
            bind(getString(R.string.mcp_copy), onClick = {
                val config = JSONObject().put("url", listener.url).put("headers",
                    JSONObject().put("Authorization", "Bearer ${listener.token}"))
                val clip = ClipData.newPlainText(getString(R.string.mcp_title), config.toString())
                if (Build.VERSION.SDK_INT >= 33) clip.description.extras =
                    android.os.PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            })
        })
    }
    UuDialogBuilder(this, keepChainOpen = false).setTitle(R.string.mcp_title).setView(UuScrollView(this).apply { addView(body) })
        .setNegativeButton(R.string.action_close, null)
        .setNeutralButton(R.string.mcp_stop) { _, _ -> stopLocalMcp() }
        .show().also { it.keepTabsTappable() }
}

private fun MainActivity.startLocalMcp(directory: File? = null) {
    if (isFinishing || isDestroyed || localMcpStarting || localMcp?.running == true) return
    localMcpStarting = true
    val scope = AppDiagnostics.enterIntent("mcp_start", newChain = true)
    try {
        featureThread {
            try {
                val workspace = openMcpWorkspace(Environment.getExternalStorageDirectory(), directory)
                val worker = McpArchiveWorker(workspace, prefs)
                val tasks = McpTasks(worker::execute)
                val listener = try {
                    val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
                    McpListener(McpProtocol(workspace, tasks, version), tasks, workspace.root)
                } catch (e: Exception) {
                    tasks.close()
                    throw e
                }
                scope.trace.success()
                runFeatureOnUiThread(onUnavailable = { listener.close() }) {
                    if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                    localMcpStarting = false
                    localMcp = listener
                    showLocalMcp()
                }
            } catch (e: Exception) {
                val invalid = e is McpInputException
                scope.trace.fail(if (invalid) FailureCode.INVALID_RESULT else FailureCode.IO,
                    if (invalid) "Cannot use the selected MCP workspace" else "Could not start local MCP listener",
                    e, notifyUser = false)
                runFeatureOnUiThread {
                    if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                    localMcpStarting = false
                    val message = getString(if (invalid) R.string.mcp_choose_error else R.string.mcp_start_error)
                    UuNotice.persistent(findViewById(R.id.root), "$message [${scope.trace.id}]", R.string.diagnostics_view) {
                        showDiagnostics(scope.trace.id)
                    }
                }
            }
        }
    } finally {
        AppDiagnostics.leaveIntent(scope)
    }
}

internal fun MainActivity.stopLocalMcp() {
    val listener = localMcp ?: return
    localMcp = null
    observedAttempt("mcp_stop") { listener.close() }.onFailure {
        if (!isFinishing && !isDestroyed) failureNotice(getString(R.string.mcp_stop_error))
    }
}
