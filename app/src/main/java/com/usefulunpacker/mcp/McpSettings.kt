package com.usefulunpacker

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import org.json.JSONObject

internal const val PREF_MCP_PORT = "mcp_port"
internal const val PREF_MCP_TOKEN = "mcp_token"
internal const val PREF_MCP_WORKSPACE = "mcp_workspace_path"
internal const val PREF_MCP_ENABLED = "mcp_enabled"

private const val MCP_CREDENTIAL_PREFS = "mcp_credentials"
private val tokenLock = Any()

internal fun getOrCreateMcpToken(context: Context, prefs: SharedPreferences): String = synchronized(tokenLock) {
    val credentials = context.getSharedPreferences(MCP_CREDENTIAL_PREFS, Context.MODE_PRIVATE)
    val stored = credentials.getString(PREF_MCP_TOKEN, null)?.trim()?.takeIf { it.length >= 32 }
    val legacy = prefs.getString(PREF_MCP_TOKEN, null)?.trim()?.takeIf { it.length >= 32 }
    val token = stored ?: legacy ?: generateMcpToken()
    if (stored == null && !credentials.edit().putString(PREF_MCP_TOKEN, token).commit()) {
        throw java.io.IOException("Could not persist MCP credentials")
    }
    if (prefs.contains(PREF_MCP_TOKEN) && !prefs.edit().remove(PREF_MCP_TOKEN).commit()) {
        throw java.io.IOException("Could not remove the legacy MCP credential")
    }
    token
}

internal fun rotateMcpToken(context: Context) = synchronized(tokenLock) {
    val fresh = generateMcpToken()
    val credentials = context.getSharedPreferences(MCP_CREDENTIAL_PREFS, Context.MODE_PRIVATE)
    if (!credentials.edit().putString(PREF_MCP_TOKEN, fresh).commit()) {
        throw java.io.IOException("Could not persist MCP credentials")
    }
}

internal fun savedMcpPort(prefs: SharedPreferences): Int =
    prefs.getInt(PREF_MCP_PORT, DEFAULT_MCP_PORT).takeIf { it in 1024..65535 } ?: DEFAULT_MCP_PORT

internal fun savedMcpWorkspace(prefs: SharedPreferences): File? =
    prefs.getString(PREF_MCP_WORKSPACE, null)?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }

internal fun MainActivity.showLocalMcp(ownerTabId: Int? = null) {
    val lastFailure = prefs.getString(PREF_MCP_FAILURE, null)
    if (lastFailure != null && lastFailure != prefs.getString(PREF_MCP_FAILURE_SHOWN, null)) {
        UuNotice.makeText(this, R.string.mcp_previous_error, Toast.LENGTH_LONG, logId = lastFailure).show()
        prefs.edit().putString(PREF_MCP_FAILURE_SHOWN, lastFailure).apply()
    }
    val listener = McpForegroundService.currentListener?.takeIf { it.running }
    if (listener == null) {
        val remembered = savedMcpWorkspace(prefs)
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val gutter = resources.getDimensionPixelSize(R.dimen.space_xl)
            setPadding(gutter, gutter / 2, gutter, gutter / 2)
            addView(TextView(context).apply {
                UuMetrics.text(this)
                text = getString(R.string.mcp_explain, DEFAULT_MCP_WORKSPACE)
            })
            if (lastFailure != null) addView(UuActionRow(context).apply {
                bind(getString(R.string.diagnostics_view), onClick = { showDiagnostics(lastFailure, mcpOnly = true) })
            })
        }
        UuDialogBuilder(this).setTitle(R.string.mcp_title)
            .setView(UuScrollView(this).apply { addView(details) })
            .setNegativeButton(R.string.action_close, null)
            .setNeutralButton(R.string.mcp_choose) { _, _ ->
                showFolderPicker(this, remembered ?: Environment.getExternalStorageDirectory()) { directory ->
                    startLocalMcp(directory)
                }
            }
            .setPositiveButton(R.string.mcp_start_default) { _, _ -> startLocalMcp(remembered) }
            .create().also {
                rootDialogToTab(it, ownerTabId)
                it.show()
                it.keepTabsTappable()
            }
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
        if (lastFailure != null) addView(UuActionRow(context).apply {
            bind(getString(R.string.diagnostics_view), onClick = { showDiagnostics(lastFailure, mcpOnly = true) })
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
                    startLocalMcp(directory, replaceListener = true)
                }
            })
        })
        addView(UuActionRow(context).apply {
            bind(getString(R.string.mcp_rotate_token), onClick = {
                try {
                    rotateMcpToken(this@showLocalMcp)
                } catch (e: Exception) {
                    val failure = recordMcpFailure("rotate_token", FailureCode.IO, "Could not save the new MCP token", e.javaClass.simpleName)
                    UuNotice.persistent(this@showLocalMcp.findViewById(R.id.root), "${getString(R.string.mcp_start_error)} [${failure.logId}]",
                        R.string.diagnostics_view) { showDiagnostics(failure.logId, mcpOnly = true) }
                    return@bind
                }
                dialog?.dismiss()
                val dir = listener.directory
                startLocalMcp(dir, replaceListener = true)
            })
        })
    }
    dialog = UuDialogBuilder(this, keepChainOpen = false).setTitle(R.string.mcp_title).setView(UuScrollView(this).apply { addView(body) })
        .setNegativeButton(R.string.action_close, null)
        .setNeutralButton(R.string.mcp_stop) { _, _ -> stopLocalMcp() }
        .create().also {
            rootDialogToTab(it, ownerTabId)
            it.show()
            it.keepTabsTappable()
        }
}

internal fun MainActivity.startLocalMcp(
    directory: File? = null,
    showDialogOnStart: Boolean = true,
    replaceListener: Boolean = false,
) {
    if (isFinishing || isDestroyed || localMcpStarting ||
        (!replaceListener && McpForegroundService.currentListener?.running == true)) return
    val ownerTabId = tabs.getOrNull(activeTabIndex)?.tabId
    localMcpStarting = true
    var stateCallback: (() -> Unit)? = null
    fun clearStarting() {
        localMcpStarting = false
        stateCallback?.let { McpForegroundService.removeStateListener(it) }
    }
    stateCallback = {
        runFeatureOnUiThread(onUnavailable = ::clearStarting) {
            if (isFinishing || isDestroyed) return@runFeatureOnUiThread
            if (!localMcpStarting) return@runFeatureOnUiThread
            clearStarting()
            val active = McpForegroundService.currentListener?.running == true
            val err = McpForegroundService.lastStartError
            if (active) {
                if (showDialogOnStart && tabs.any { it.tabId == ownerTabId }) showLocalMcp(ownerTabId)
            } else if (err != null) {
                val traceId = McpForegroundService.lastStartTraceId.orEmpty()
                UuNotice.persistent(findViewById(R.id.root), "$err [$traceId]", R.string.diagnostics_view) {
                    showDiagnostics(traceId, mcpOnly = true)
                }
            }
        }
    }
    McpForegroundService.addStateListener(stateCallback)
    try {
        McpForegroundService.start(this, directory ?: savedMcpWorkspace(prefs), replaceListener)
    } catch (e: Exception) {
        clearStarting()
        val failure = recordMcpFailure("start", FailureCode.IO, "Could not launch the MCP foreground service", e.javaClass.simpleName)
        UuNotice.persistent(findViewById(R.id.root), "${getString(R.string.mcp_start_error)} [${failure.logId}]", R.string.diagnostics_view) {
            showDiagnostics(failure.logId, mcpOnly = true)
        }
    }
}

internal fun MainActivity.stopLocalMcp(clearEnabledPref: Boolean = true) {
    if (clearEnabledPref) prefs.edit().putBoolean(PREF_MCP_ENABLED, false).apply()
    try {
        McpForegroundService.stop(this)
    } catch (e: Exception) {
        val failure = recordMcpFailure("stop", FailureCode.IO, "Could not stop the MCP foreground service", e.javaClass.simpleName)
        UuNotice.persistent(findViewById(R.id.root), "${getString(R.string.mcp_stop_error)} [${failure.logId}]", R.string.diagnostics_view) {
            showDiagnostics(failure.logId, mcpOnly = true)
        }
    }
}
