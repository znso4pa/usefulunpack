package com.usefulunpacker

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CancellationException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

internal class McpTask(val trace: OperationTrace, val total: Int) {
    private var state = "QUEUED"
    @Volatile private var cancelled = false
    private var cancelNative: (() -> Unit)? = null
    private val outputs = mutableListOf<String>()
    private var error: String? = null
    private var cleanupFailed = false

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

    @Synchronized fun publish(output: String, move: () -> Unit) {
        checkCancelled()
        move()
        outputs.add(output)
    }

    @Synchronized fun cleanupFailed() { cleanupFailed = true }

    @Synchronized fun finish(failure: String? = null) {
        error = failure
        state = when {
            cancelled -> "CANCELLED"
            failure != null -> "FAILED"
            cleanupFailed -> "PARTIAL_FAILURE"
            else -> "SUCCESS"
        }
        when (state) {
            "CANCELLED" -> trace.cancelled()
            "FAILED" -> trace.fail(FailureCode.COMMAND, failure ?: "Task failed")
            "PARTIAL_FAILURE" -> Unit // Cleanup logged its own failure.
            else -> trace.success()
        }
        trace.release()
    }

    @Synchronized fun done(): Boolean = state in setOf("SUCCESS", "PARTIAL_FAILURE", "FAILED", "CANCELLED")

    @Synchronized fun snapshot(): JSONObject = JSONObject()
        .put("taskId", trace.id).put("chainId", trace.id).put("state", state)
        .put("completedSteps", outputs.size).put("totalSteps", total)
        .put("outputs", JSONArray(outputs)).put("cleanupFailed", cleanupFailed)
        .apply { error?.let { put("error", it) } }
}

/** One worker per listener, bounded queue and retained receipts. JNI still uses the global scheduler. */
internal class McpTasks(private val execute: (McpTask, McpCommand) -> Unit) : AutoCloseable {
    private val records = LinkedHashMap<String, McpTask>()
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4))
    private var closed = false

    @Synchronized fun submit(commands: List<McpCommand>, script: Boolean): JSONObject {
        if (closed) throw McpInputException("MCP listener is stopping")
        val trace = AppDiagnostics.startChain(if (script) "mcp_uut" else "mcp_uu", commands.map { it.password })
        trace.retain()
        val task = McpTask(trace, commands.size)
        records[trace.id] = task
        try {
            worker.execute(QueuedTask(task, commands))
        } catch (e: RejectedExecutionException) {
            task.finish("The MCP task queue is full")
        }
        while (records.size > 32) {
            val oldest = records.entries.first { it.value.done() }
            records.remove(oldest.key)
        }
        return task.snapshot()
    }

    private inner class QueuedTask(val task: McpTask, private val commands: List<McpCommand>) : Runnable {
        override fun run() = runTask(task, commands)
    }

    private fun runTask(task: McpTask, commands: List<McpCommand>) {
        if (!task.start()) return
        val thread = Thread.currentThread()
        val previousName = thread.name
        thread.name = "uu-${task.trace.id}"
        try {
            AppDiagnostics.withTrace(task.trace) {
                var failure: String? = null
                try {
                    for ((index, command) in commands.withIndex()) {
                        task.checkCancelled()
                        val step = AppDiagnostics.begin("${index + 1}_${command.action.cliName}")
                        AppDiagnostics.withTrace(step) {
                            try {
                                execute(task, command)
                                step.success()
                            } catch (e: McpInputException) {
                                step.fail(FailureCode.INVALID_RESULT, e.message ?: "Invalid input")
                                throw e
                            } catch (e: CancellationException) {
                                step.cancelled()
                                throw e
                            } catch (e: Exception) {
                                step.fail(FailureCode.IO, "Command operation failed")
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
                }
            }
        } finally {
            thread.name = previousName
        }
    }

    @Synchronized fun get(id: String): McpTask =
        records[id] ?: throw McpInputException("Unknown or expired task ID")

    fun cancel(id: String): JSONObject {
        val task = get(id)
        if (task.cancel()) worker.queue.removeIf { it is QueuedTask && it.task === task }
        return task.snapshot()
    }

    @Synchronized override fun close() {
        closed = true
        records.values.forEach { it.cancel() }
        worker.queue.clear()
        worker.shutdown()
    }
}
