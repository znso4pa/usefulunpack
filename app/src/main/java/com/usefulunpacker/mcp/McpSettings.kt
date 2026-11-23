package com.usefulunpacker

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import org.json.JSONObject

internal const val PREF_MCP_PORT = "mcp_port"
internal const val PREF_MCP_TOKEN = "mcp_token"
internal const val PREF_MCP_WORKSPACE = "mcp_workspace_path"
internal const val PREF_MCP_ENABLED = "mcp_enabled"

internal fun getOrCreateMcpToken(prefs: SharedPreferences): String {
    val existing = prefs.getString(PREF_MCP_TOKEN, null)?.trim().orEmpty()
    if (existing.length >= 32) return existing
    return rotateMcpToken(prefs)
}

internal fun rotateMcpToken(prefs: SharedPreferences): String {
    val fresh = generateMcpToken()
    prefs.edit().putString(PREF_MCP_TOKEN, fresh).apply()
    return fresh
}

internal fun savedMcpPort(prefs: SharedPreferences): Int =
    prefs.getInt(PREF_MCP_PORT, DEFAULT_MCP_PORT).takeIf { it in 1024..65535 } ?: DEFAULT_MCP_PORT

internal fun savedMcpWorkspace(prefs: SharedPreferences): File? =
    prefs.getString(PREF_MCP_WORKSPACE, null)?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }

internal fun MainActivity.showLocalMcp() {
    val listener = McpForegroundService.currentListener?.takeIf { it.running }
    if (listener == null) {
        val remembered = savedMcpWorkspace(prefs)
        UuDialogBuilder(this).setTitle(R.string.mcp_title)
            .setMessage(getString(R.string.mcp_explain, DEFAULT_MCP_WORKSPACE))
            .setNegativeButton(R.string.action_close, null)
            .setNeutralButton(R.string.mcp_choose) { _, _ ->
                showFolderPicker(this, remembered ?: Environment.getExternalStorageDirectory()) { directory ->
                    startLocalMcp(directory)
                }
            }
            .setPositiveButton(R.string.mcp_start_default) { _, _ -> startLocalMcp(remembered) }
            .show().also { it.keepTabsTappable() }
        return
    }

    var dialog: android.app.AlertDialog? = null
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val gutter = resources.getDimensionPixelSize(R.dimen.space_xl)
        setPadding(gutter, gutter / 2, gutter, gutter / 2)
        addView(TextView(context).apply {
            UuMetrics.text(this)
            text = getString(R.string.mcp_endpoint, listener.url, listener.directory.path) +
                "\nADB: adb forward tcp:${listener.port} tcp:${listener.port}"
            setTextIsSelectable(true)
        })
        addView(UuActionRow(context).apply {
            bind(getString(R.string.mcp_copy), onClick = {
                val config = JSONObject()
                    .put("url", listener.url)
                    .put("port", listener.port)
                    .put("adbForward", "adb forward tcp:${listener.port} tcp:${listener.port}")
                    .put("headers", JSONObject().put("Authorization", "Bearer ${listener.token}"))
                val clip = ClipData.newPlainText(getString(R.string.mcp_title), config.toString())
                if (Build.VERSION.SDK_INT >= 33) clip.description.extras =
                    android.os.PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            })
        })
        addView(UuActionRow(context).apply {
            bind(getString(R.string.mcp_choose), onClick = {
                dialog?.dismiss()
                showFolderPicker(this@showLocalMcp, listener.directory) { directory ->
                    stopLocalMcp(clearEnabledPref = false)
                    startLocalMcp(directory)
                }
            })
        })
        addView(UuActionRow(context).apply {
            bind(getString(R.string.mcp_rotate_token), onClick = {
                dialog?.dismiss()
                val dir = listener.directory
                rotateMcpToken(prefs)
                stopLocalMcp(clearEnabledPref = false)
                startLocalMcp(dir)
            })
        })
    }
    dialog = UuDialogBuilder(this, keepChainOpen = false).setTitle(R.string.mcp_title).setView(UuScrollView(this).apply { addView(body) })
        .setNegativeButton(R.string.action_close, null)
        .setNeutralButton(R.string.mcp_stop) { _, _ -> stopLocalMcp() }
        .show().also { it.keepTabsTappable() }
}

internal fun MainActivity.startLocalMcp(directory: File? = null, showDialogOnStart: Boolean = true) {
    if (isFinishing || isDestroyed || localMcpStarting || McpForegroundService.currentListener?.running == true) return
    localMcpStarting = true
    var stateCallback: (() -> Unit)? = null
    stateCallback = {
        runFeatureOnUiThread {
            if (isFinishing || isDestroyed) return@runFeatureOnUiThread
            val active = McpForegroundService.currentListener?.running == true
            val err = McpForegroundService.lastStartError
            if (active || err != null) {
                localMcpStarting = false
                stateCallback?.let { McpForegroundService.removeStateListener(it) }
                if (active) {
                    if (showDialogOnStart) showLocalMcp()
                } else if (err != null) {
                    val traceId = McpForegroundService.lastStartTraceId.orEmpty()
                    UuNotice.persistent(findViewById(R.id.root), "$err [$traceId]", R.string.diagnostics_view) {
                        showDiagnostics(traceId)
                    }
                }
            }
        }
    }
    McpForegroundService.addStateListener(stateCallback)
    McpForegroundService.start(this, directory ?: savedMcpWorkspace(prefs))
}

internal fun MainActivity.stopLocalMcp(clearEnabledPref: Boolean = true) {
    if (clearEnabledPref) prefs.edit().putBoolean(PREF_MCP_ENABLED, false).apply()
    McpForegroundService.stop(this)
}
