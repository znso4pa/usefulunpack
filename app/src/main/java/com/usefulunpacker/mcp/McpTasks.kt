package com.usefulunpacker

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal const val MCP_MAX_WAIT_MS = 15_000
internal const val MCP_JOURNAL_FILE = ".uu-mcp-tasks.json"

internal class McpTask(
    val trace: OperationTrace?,
    val total: Int,
    val idempotencyKey: String = "",
    val fingerprint: String = "",
    val summary: String = "",
    val createdAtMs: Long = System.currentTimeMillis(),
    val taskId: String = trace?.id ?: "",
) {
    private var state = "QUEUED"
    @Volatile private var cancelled = false
    private var cancelNative: (() -> Unit)? = null
    private var progressSupplier: (() -> JSONObject?)? = null
    private val doneLatch = CountDownLatch(1)
    private val outputs = mutableListOf<String>()
    private val stdout = StringBuilder()
    private var completedSteps = 0
    private var exitCode = 0
    private var error: String? = null
    private var restoredFailure: Pair<String, String>? = null
    private var restoredLogId: String? = null
    val logId: String get() = restoredLogId ?: taskId
    private var cleanupFailed = false
    internal var uutState: Any? = null

    companion object {
        fun fromJournal(obj: JSONObject): McpTask? {
            val id = obj.optString("taskId").takeIf { it.isNotEmpty() } ?: return null
            val total = obj.optInt("totalSteps", 0)
            val task = McpTask(
                trace = null,
                total = total,
                idempotencyKey = obj.optString("idempotencyKey"),
                fingerprint = obj.optString("fingerprint"),
                summary = obj.optString("summary"),
                createdAtMs = obj.optLong("createdAtMs", 0L),
                taskId = id,
            )
            val rawState = obj.optString("state", "FAILED")
            val wasActive = rawState in setOf("QUEUED", "RUNNING", "CANCELLING")
            task.state = if (wasActive) "INTERRUPTED" else rawState
            task.completedSteps = obj.optInt("completedSteps", 0)
            task.exitCode = if (wasActive) 1 else obj.optInt("exitCode", 0)
            task.cleanupFailed = obj.optBoolean("cleanupFailed", false)
            task.error = if (wasActive) {
                "Task was interrupted because the host process terminated"
            } else {
                obj.optString("error").takeIf { it.isNotEmpty() }
            }
            obj.optJSONArray("outputs")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotEmpty() }?.let(task.outputs::add)
                }
            }
            val code = obj.optString("errorCode")
            val stage = obj.optString("stage")
            if (wasActive) {
                val failure = recordMcpFailure("task_recovery", FailureCode.INTERRUPTED,
                    "A previous MCP task was interrupted when the host process terminated", ownChain = true)
                task.restoredFailure = failure.code.name to failure.stage
                task.restoredLogId = failure.logId
            } else if (code.isNotEmpty() && stage.isNotEmpty()) {
                task.restoredFailure = code to stage
                task.restoredLogId = obj.optString("logId").takeIf { it.isNotEmpty() }
            }
            task.stdout.append(obj.optString("stdout"))
            task.doneLatch.countDown()
            return task
        }
    }

    @Synchronized fun start(): Boolean {
        if (cancelled) return false
        state = "RUNNING"
        return true
    }

    @Synchronized fun cancel(): Boolean {
        if (state != "QUEUED" && state != "RUNNING") return false
        val queued = state == "QUEUED"
        cancelled = true
        state = "CANCELLING"
        if (queued) finish()
        else cancelNative?.invoke()
        return queued
    }

    fun checkCancelled() { if (cancelled) throw CancellationException() }

    @Synchronized fun bindNativeCancel(action: (() -> Unit)?) {
        cancelNative = action
        if (cancelled) action?.invoke()
    }

    @Synchronized fun bindProgress(supplier: (() -> JSONObject?)?) {
        progressSupplier = supplier
    }

    @Synchronized fun publish(output: String, move: () -> Unit) {
        checkCancelled()
        move()
        if (output.isNotEmpty() && output !in outputs) outputs.add(output)
    }

    @Synchronized fun recordOutput(output: String) {
        if (output.isNotEmpty() && output !in outputs) outputs.add(output)
    }

    @Synchronized fun appendStdout(text: String) {
        if (text.isEmpty() || stdout.length >= MCP_REQUEST_LIMIT) return
        if (stdout.isNotEmpty() && !stdout.endsWith("\n")) stdout.append('\n')
        val remaining = (MCP_REQUEST_LIMIT - stdout.length).coerceAtLeast(0)
        stdout.append(text.take(remaining))
    }

    @Synchronized fun markStepDone() {
        completedSteps++
    }

    @Synchronized fun recordExitCode(code: Int) {
        exitCode = code
    }

    @Synchronized fun cleanupFailed() { cleanupFailed = true }

    @Synchronized fun finish(failure: String? = null) {
        error = failure
        state = when {
            cancelled -> "CANCELLED"
            failure != null -> "FAILED"
            trace?.chain?.failureStatus() == ChainStatus.FAILED -> "FAILED"
            cleanupFailed -> "PARTIAL_FAILURE"
            else -> trace?.chain?.failureStatus()?.name ?: "SUCCESS"
        }
        if (state == "FAILED" && exitCode == 0) exitCode = 1
        when (state) {
            "CANCELLED" -> trace?.cancelled()
            "FAILED" -> if (trace?.chain?.firstFailure() == null) {
                trace?.fail(FailureCode.COMMAND, "Task failed")
            }
            "PARTIAL_FAILURE" -> Unit // Cleanup logged its own failure.
            else -> trace?.success()
        }
        progressSupplier = null
        uutState = null
        trace?.release()
    }

    fun markSettled() { doneLatch.countDown() }

    fun awaitDone(waitMs: Int) {
        if (waitMs <= 0) return
        try {
            doneLatch.await(waitMs.coerceAtMost(MCP_MAX_WAIT_MS).toLong(), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    @Synchronized fun done(): Boolean =
        state in setOf("SUCCESS", "PARTIAL_FAILURE", "FAILED", "CANCELLED", "INTERRUPTED")

    @Synchronized fun snapshot(deduplicated: Boolean = false): JSONObject {
        val liveProgress = if (state == "RUNNING") progressSupplier?.invoke() else null
        return JSONObject()
            .put("taskId", taskId).put("chainId", taskId).put("state", state)
            .put("completedSteps", maxOf(completedSteps, outputs.size)).put("totalSteps", total)
            .put("outputs", JSONArray(outputs))
            .put("stdout", stdout.toString())
            .put("exitCode", exitCode)
            .put("cleanupFailed", cleanupFailed)
            .put("deduplicated", deduplicated)
            .put("createdAtMs", createdAtMs)
            .apply {
                if (summary.isNotEmpty()) put("summary", summary)
                if (idempotencyKey.isNotEmpty()) put("idempotencyKey", idempotencyKey)
                if (liveProgress != null) put("progress", liveProgress)
                error?.let { put("error", AppDiagnostics.redact(it)) }
                val fault = trace?.chain?.firstFailure()?.let { (code, stage) -> code.name to stage } ?: restoredFailure
                if (fault != null) {
                    put("errorCode", fault.first).put("stage", fault.second).put("logId", logId)
                }
            }
    }

    @Synchronized fun toJournalJson(): JSONObject =
        snapshot().put("fingerprint", fingerprint)
}

/** One worker per listener, bounded queue and retained receipts. JNI still uses the global scheduler. */
internal class McpTasks(
    private val journalFile: File? = null,
    private val onActiveCountChanged: ((Int) -> Unit)? = null,
    private val agentFeature: String? = null,
    private val execute: (McpTask, McpCommand) -> Unit,
) : AutoCloseable {
    constructor(execute: (McpTask, McpCommand) -> Unit) : this(null, null, null, execute)
    private val records = LinkedHashMap<String, McpTask>()
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4))
    private var closed = false
    @Volatile private var journalFailure: AppFailure? = null

    init {
        if (journalFile != null) {
            cleanupStaleStaging(journalFile.parentFile)
            loadJournal()
        }
    }

    private fun cleanupStaleStaging(root: File?) {
        if (root == null || !root.isDirectory) return
        val entries = root.listFiles()
        if (entries == null) {
            recordMcpFailure("staging_cleanup", FailureCode.CLEANUP, "Could not scan for interrupted task staging files")
            return
        }
        val stale = entries.filter { it.name.startsWith(".uu-mcp-") && it.name != journalFile?.name }
        if (stale.isEmpty()) return
        val scope = AppDiagnostics.enterIntent("mcp_staging_cleanup",
            newChain = AppDiagnostics.currentTrace()?.feature?.startsWith("mcp_") != true)
        try {
            stale.forEach { deleteOutputChecked(it, recursive = it.isDirectory) }
        } finally {
            AppDiagnostics.leaveIntent(scope)
        }
    }

    @Synchronized private fun loadJournal() {
        val file = journalFile ?: return
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            var changed = false
            var invalidEntries = 0
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i)
                val restored = obj?.let { McpTask.fromJournal(it) }
                if (obj == null || restored == null) {
                    invalidEntries++
                    continue
                }
                if (obj.optString("state") != restored.snapshot().optString("state")) changed = true
                records[restored.taskId] = restored
            }
            if (invalidEntries > 0) {
                journalFailure = recordMcpFailure("journal_read", FailureCode.INVALID_RESULT,
                    "Skipped $invalidEntries invalid task history records", ownChain = true)
            }
            if (changed) persistJournalLocked()
        } catch (e: Exception) {
            journalFailure = recordMcpFailure("journal_read", FailureCode.IO,
                "Task history could not be loaded", e.javaClass.simpleName, ownChain = true)
        }
    }

    @Synchronized private fun persistJournalLocked() {
        val file = journalFile ?: return
        val parent = file.parentFile ?: return
        try {
            if (!parent.isDirectory) throw java.io.IOException("Journal directory is unavailable")
            val arr = JSONArray()
            records.values.forEach { arr.put(it.toJournalJson()) }
            val temp = Files.createTempFile(parent.toPath(), ".uu-mcp-journal-", ".tmp").toFile()
            try {
                temp.writeText(arr.toString(), Charsets.UTF_8)
                Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temp.toPath())
            }
            if (journalFailure?.stage?.endsWith("journal_write") == true) journalFailure = null
        } catch (e: Exception) {
            // Keep the worker usable and report each continuous storage failure once.
            if (journalFailure?.stage?.endsWith("journal_write") != true) {
                journalFailure = recordMcpFailure("journal_write", FailureCode.IO,
                    "Task history could not be saved", e.javaClass.simpleName, ownChain = true)
            }
        }
    }

    @Synchronized private fun notifyActive() {
        onActiveCountChanged?.invoke(records.values.count { !it.done() })
    }

    fun submit(
        commands: List<McpCommand>,
        script: Boolean,
        idempotencyKey: String = "",
        waitMs: Int = 0,
    ): JSONObject {
        val cleanKey = idempotencyKey.trim()
        if (cleanKey.length > 128 || cleanKey.any { it.isISOControl() }) {
            throw mcpInputError("idempotencyKey must be at most 128 printable characters")
        }
        val secrets = commands.flatMap { it.secrets + listOfNotNull(it.password.takeIf(String::isNotEmpty)) }.distinct()
        val (fingerprint, summary) = fingerprintAndSummarize(commands, script, secrets)
        val (task, deduplicated) = synchronized(this) {
            if (closed) throw mcpInputError("MCP listener is stopping")
            // 1. Explicit idempotencyKey deduplicates across both active and finished tasks.
            val byKey = if (cleanKey.isNotEmpty()) {
                records.values.lastOrNull { it.idempotencyKey == cleanKey }
            } else null
            if (byKey != null) return@synchronized byKey to true

            // 2. Implicit fingerprint deduplication attaches to an identical QUEUED or RUNNING task
            // so client-side timeout retries never spawn duplicate concurrent executions.
            val byActiveFingerprint = records.values.lastOrNull { !it.done() && it.fingerprint == fingerprint }
            if (byActiveFingerprint != null) return@synchronized byActiveFingerprint to true

            val trace = AppDiagnostics.startChain(agentFeature ?: if (script) "mcp_uut" else "mcp_uu", secrets)
            trace.retain()
            val fresh = McpTask(
                trace = trace,
                total = commands.size,
                idempotencyKey = cleanKey,
                fingerprint = fingerprint,
                summary = summary,
            )
            records[trace.id] = fresh
            val rejected = try {
                worker.execute(QueuedTask(fresh, commands))
                false
            } catch (_: RejectedExecutionException) {
                trace.fail(FailureCode.COMMAND, "The MCP task queue is full")
                fresh.finish("The MCP task queue is full")
                true
            }
            while (records.size > 32) {
                val oldest = records.entries.firstOrNull { it.value.done() } ?: break
                records.remove(oldest.key)
            }
            persistJournalLocked()
            if (rejected) fresh.markSettled() else notifyActive()
            fresh to false
        }
        if (waitMs > 0) task.awaitDone(waitMs)
        return task.snapshot(deduplicated = deduplicated)
    }

    @Synchronized fun list(stateFilter: String = "", limit: Int = 32): JSONObject {
        val filter = stateFilter.trim().uppercase()
        val matched = records.values.reversed()
            .filter { filter.isEmpty() || it.snapshot().optString("state") == filter }
            .take(limit.coerceIn(1, 32))
            .map { it.snapshot() }
        return JSONObject()
            .put("totalTracked", records.size)
            .put("tasks", JSONArray(matched))
    }

    private inner class QueuedTask(val task: McpTask, private val commands: List<McpCommand>) : Runnable {
        override fun run() = runTask(task, commands)
    }

    private fun runTask(task: McpTask, commands: List<McpCommand>) {
        if (!task.start()) return
        synchronized(this) { persistJournalLocked() }
        val trace = task.trace ?: return
        val thread = Thread.currentThread()
        val previousName = thread.name
        thread.name = "uu-${task.taskId}"
        try {
            AppDiagnostics.withTrace(trace) {
                var failure: String? = null
                try {
                    for ((index, command) in commands.withIndex()) {
                        task.checkCancelled()
                        val stepLabel = command.name.ifEmpty { command.action?.cliName ?: "step" }
                        val step = AppDiagnostics.begin("${index + 1}_$stepLabel")
                        AppDiagnostics.withTrace(step) {
                            try {
                                execute(task, command)
                                task.markStepDone()
                                synchronized(this) { persistJournalLocked() }
                                step.success()
                            } catch (e: McpInputException) {
                                val line = Regex("^Line (\\d+):").find(e.message.orEmpty())?.groupValues?.get(1)
                                val detail = e.diagnosticDetail
                                    ?: if (line == null) "Command rejected or failed" else "UUT command failed at line $line"
                                step.fail(e.errorCode, detail)
                                throw e
                            } catch (e: CancellationException) {
                                step.cancelled()
                                throw e
                            } catch (e: Exception) {
                                step.fail(FailureCode.IO, "Command operation failed (${e.javaClass.simpleName})")
                                throw e
                            } catch (e: LinkageError) {
                                step.fail(FailureCode.UNSUPPORTED, "Native archive library unavailable")
                                throw e
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    task.cancel()
                } catch (e: McpInputException) {
                    failure = e.message
                } catch (e: Exception) {
                    failure = "Command operation failed. See diagnostic steps."
                } catch (e: LinkageError) {
                    failure = "Native archive library unavailable."
                } finally {
                    task.finish(failure)
                    synchronized(this) { persistJournalLocked() }
                    try { notifyActive() } finally { task.markSettled() }
                }
            }
        } finally {
            thread.name = previousName
        }
    }

    fun historyFailure(): AppFailure? = journalFailure

    @Synchronized fun get(id: String): McpTask =
        records[id] ?: throw mcpInputError("Unknown or expired task ID")

    @Synchronized fun canReadLog(id: String): Boolean =
        id in records || records.values.any { it.logId == id }

    fun cancel(id: String): JSONObject {
        val task = get(id)
        val queued = task.cancel()
        if (queued) worker.queue.removeIf { it is QueuedTask && it.task === task }
        synchronized(this) { persistJournalLocked() }
        try { notifyActive() } finally { if (queued) task.markSettled() }
        return task.snapshot()
    }

    @Synchronized override fun close() {
        closed = true
        val queued = records.values.filter { it.cancel() }
        persistJournalLocked()
        queued.forEach { it.markSettled() }
        worker.queue.clear()
        worker.shutdown()
    }
}

private fun fingerprintAndSummarize(
    commands: List<McpCommand>,
    script: Boolean,
    secrets: List<String>,
): Pair<String, String> {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(if (script) 1.toByte() else 0.toByte())
    for (c in commands) {
        val raw = buildString {
            append(c.action?.cliName.orEmpty()).append('|')
            append(c.source).append('|').append(c.output).append('|')
            append(c.password).append('|').append(c.overwrite).append('|')
            append(c.format).append('|').append(c.enc).append('|')
            append(c.entries.joinToString("\n")).append('|')
            append(c.name).append('|').append(c.argv.joinToString("\u0000")).append('|')
            append(c.scriptArgs.joinToString("\u0000")).append('|').append(c.keepGoing).append('|')
            c.uutStmt?.let { append(serializeUutStmt(it)) }
        }
        digest.update(raw.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
    }
    val hex = digest.digest().joinToString("") { "%02x".format(it) }
    var summary = if (!script && commands.size == 1) {
        val cmd = commands[0]
        if (cmd.argv.isNotEmpty()) "uu " + cmd.argv.joinToString(" ") else "uu ${cmd.name}"
    } else {
        "uut (${commands.size} steps): " + commands.take(3).joinToString("; ") { it.name }
    }
    for (secret in secrets) {
        if (secret.isNotEmpty()) summary = summary.replace(secret, "***")
    }
    return hex to summary.take(160)
}

private fun serializeUutStmt(stmt: UutStmt): String = when (stmt) {
    is UutStmt.Cmd -> "cmd:${stmt.raw}"
    is UutStmt.Set -> "set:${stmt.name}=${stmt.rawValue}"
    is UutStmt.IfInline -> "if1:${serializeCond(stmt.cond)}->${stmt.rawCmd}"
    is UutStmt.If -> "if:${serializeCond(stmt.cond)}{${stmt.thenBody.joinToString(";", transform = ::serializeUutStmt)}}else{${stmt.elseBody.joinToString(";", transform = ::serializeUutStmt)}}"
    is UutStmt.For -> "for:${stmt.varName}:${stmt.rawPatterns}{${stmt.body.joinToString(";", transform = ::serializeUutStmt)}}"
    is UutStmt.While -> "while:${serializeCond(stmt.cond)}{${stmt.body.joinToString(";", transform = ::serializeUutStmt)}}"
    is UutStmt.Break -> "break"
    is UutStmt.Continue -> "continue"
    is UutStmt.Return -> "return:${stmt.rawCode}"
}

private fun serializeCond(cond: UutCond): String = when (cond) {
    is UutCond.VarCmp -> "${cond.name}${cond.op}${cond.value}"
    is UutCond.Exists -> "${if (cond.negate) "!" else ""}exist:${cond.rawPath}"
}
