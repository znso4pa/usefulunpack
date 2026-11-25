package com.usefulunpacker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Hosts the local MCP HTTP listener and task queue outside [MainActivity]'s lifecycle,
 * keeping long-running archive jobs alive in the background with a persistent notification
 * and an on-demand [PowerManager.PARTIAL_WAKE_LOCK] while tasks are active.
 */
internal class McpForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val startupLock = Any()
    private var startupGeneration = 0L
    @Volatile private var starting = false

    companion object {
        private const val CHANNEL_ID = "uu_local_mcp"
        private const val NOTIFICATION_ID = 17380
        internal const val ACTION_START = "com.usefulunpacker.action.MCP_START"
        internal const val ACTION_STOP = "com.usefulunpacker.action.MCP_STOP"
        internal const val EXTRA_WORKSPACE = "workspace_path"
        private const val EXTRA_REPLACE_LISTENER = "replace_listener"

        @Volatile var currentListener: McpListener? = null
            private set
        @Volatile var lastStartError: String? = null
            private set
        @Volatile var lastStartTraceId: String? = null
            private set

        private val stateListeners = CopyOnWriteArrayList<() -> Unit>()

        fun addStateListener(listener: () -> Unit) {
            stateListeners.add(listener)
        }

        fun removeStateListener(listener: () -> Unit) {
            stateListeners.remove(listener)
        }

        private fun notifyStateChanged() {
            stateListeners.forEach { it() }
        }

        fun start(context: Context, directory: File?, replaceListener: Boolean = false) {
            val intent = Intent(context, McpForegroundService::class.java).apply {
                action = ACTION_START
                if (directory != null) putExtra(EXTRA_WORKSPACE, directory.path)
                if (replaceListener) putExtra(EXTRA_REPLACE_LISTENER, true)
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, McpForegroundService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListener()
            getSharedPreferences("bm", MODE_PRIVATE).edit().putBoolean(PREF_MCP_ENABLED, false).apply()
            runFeatureStop()
            return START_NOT_STICKY
        }
        if (intent?.getBooleanExtra(EXTRA_REPLACE_LISTENER, false) == true && !starting) {
            stopListener(notify = false)
        }

        try {
            ensureChannel()
            val initialText = currentListener?.let {
                getString(R.string.mcp_notif_running, it.url, 0)
            } ?: getString(R.string.mcp_notif_starting)
            startForegroundCompat(buildNotification(initialText))
        } catch (e: Exception) {
            val failure = recordMcpFailure("start_foreground", FailureCode.IO,
                "Could not start the MCP foreground notification", e.javaClass.simpleName)
            lastStartError = getString(R.string.mcp_start_error)
            lastStartTraceId = failure.logId
            notifyStateChanged()
            stopSelf()
            return START_NOT_STICKY
        }

        val explicitDir = intent?.getStringExtra(EXTRA_WORKSPACE)?.takeIf { it.isNotBlank() }?.let(::File)
        startListenerAsync(explicitDir)
        return START_STICKY
    }

    private fun startListenerAsync(explicitDir: File?) {
        if (starting || currentListener?.running == true) {
            notifyStateChanged()
            return
        }
        val generation = synchronized(startupLock) {
            starting = true
            lastStartError = null
            lastStartTraceId = null
            ++startupGeneration
        }
        val prefs = getSharedPreferences("bm", MODE_PRIVATE)
        val scope = AppDiagnostics.enterIntent("mcp_start", newChain = true)
        try {
            featureThread {
                try {
                    val targetDir = explicitDir ?: savedMcpWorkspace(prefs)
                    val workspace = openMcpWorkspace(Environment.getExternalStorageDirectory(), targetDir)
                    val worker = McpArchiveWorker(workspace, prefs, strFnOf(this))
                    val journalFile = File(workspace.root, MCP_JOURNAL_FILE)
                    val token = getOrCreateMcpToken(this, prefs)
                    val tasks = McpTasks(
                        journalFile = journalFile,
                        onActiveCountChanged = ::onActiveTaskCountChanged,
                        execute = worker::execute,
                    )
                    val listener = try {
                        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
                        synchronized(startupLock) {
                            if (generation != startupGeneration) null
                            else McpListener(
                                McpProtocol(WorkspaceSkillRuntime(workspace, tasks, prefs), version),
                                tasks,
                                workspace.root,
                                preferredPort = savedMcpPort(prefs),
                                fixedToken = token,
                            ).also {
                                prefs.edit()
                                    .putString(PREF_MCP_WORKSPACE, workspace.root.path)
                                    .putInt(PREF_MCP_PORT, it.port)
                                    .putBoolean(PREF_MCP_ENABLED, true)
                                    .apply()
                                currentListener = it
                                starting = false
                            }
                        }
                    } catch (e: Exception) {
                        tasks.close()
                        throw e
                    }
                    if (listener == null) {
                        tasks.close()
                        scope.trace.cancelled()
                        return@featureThread
                    }
                    scope.trace.success()
                    synchronized(startupLock) {
                        if (currentListener === listener) {
                            try { updateNotification(getString(R.string.mcp_notif_running, listener.url, 0)) }
                            catch (e: Exception) {
                                recordMcpFailure("background_status", FailureCode.IO,
                                    "Could not update MCP background status", e.javaClass.simpleName)
                            }
                        }
                    }
                    notifyStateChanged()
                } catch (e: Exception) {
                    val didFail = synchronized(startupLock) {
                        if (generation != startupGeneration) false
                        else {
                            val invalid = e is McpInputException
                            scope.trace.fail(
                                if (invalid) FailureCode.INVALID_RESULT else FailureCode.IO,
                                if (invalid) "Cannot use the selected MCP workspace" else "Could not start local MCP listener",
                                e,
                                notifyUser = false,
                            )
                            lastStartError = getString(if (invalid) R.string.mcp_choose_error else R.string.mcp_start_error)
                            lastStartTraceId = scope.trace.id
                            starting = false
                            runFeatureStop()
                            true
                        }
                    }
                    if (didFail) notifyStateChanged() else scope.trace.cancelled()
                }
            }
        } finally {
            AppDiagnostics.leaveIntent(scope)
        }
    }

    private fun runFeatureStop() {
        try { stopForegroundCompat() }
        catch (e: Exception) {
            recordMcpFailure("stop_foreground", FailureCode.CLEANUP,
                "Could not remove the MCP foreground notification", e.javaClass.simpleName)
        } finally { stopSelf() }
    }

    private fun onActiveTaskCountChanged(activeCount: Int) {
        try {
            updateWakeLock(activeCount > 0)
            val listener = currentListener ?: return
            updateNotification(getString(R.string.mcp_notif_running, listener.url, activeCount))
        } catch (e: Exception) {
            recordMcpFailure("background_status", FailureCode.IO,
                "Could not update MCP background status", e.javaClass.simpleName)
        }
    }

    @Synchronized private fun updateWakeLock(acquire: Boolean) {
        if (acquire) {
            if (wakeLock == null) {
                val pm = getSystemService(PowerManager::class.java)
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UsefulUnpack:McpTask")
                    ?.apply { setReferenceCounted(false) }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(60 * 60 * 1000L)
            }
        } else {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
    }

    private fun stopListener(notify: Boolean = true) {
        val listener = synchronized(startupLock) {
            startupGeneration++
            starting = false
            currentListener.also { currentListener = null }
        }
        try { updateWakeLock(false) }
        catch (e: Exception) {
            recordMcpFailure("stop_wake_lock", FailureCode.CLEANUP, "Could not release the MCP wake lock", e.javaClass.simpleName)
        }
        if (listener != null) {
            try { listener.close() }
            catch (e: Exception) {
                recordMcpFailure("stop_listener", FailureCode.CLEANUP, "Could not close the MCP listener", e.javaClass.simpleName)
            }
        }
        if (notify) notifyStateChanged()
    }

    override fun onDestroy() {
        stopListener()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.mcp_title),
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAction = PendingIntent.getService(
            this,
            1,
            Intent(this, McpForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.mcp_title))
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.mcp_stop), stopAction).build())
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
