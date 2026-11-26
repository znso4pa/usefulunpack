package com.usefulunpacker

import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.CancellationException

enum class FailureCode { IO, INVALID_RESULT, PARTIAL_FAILURE, UNSUPPORTED, CLEANUP, COMMAND, UNEXPECTED, INTERRUPTED }

data class AppFailure(val code: FailureCode, val stage: String, val detail: String, val logId: String)
data class DiagnosticEntry(
    val id: String, val feature: String, val text: String, val failed: Boolean, val live: Boolean = true,
    val outputs: List<String> = emptyList(),
) {
    val status: String get() = Regex("(?m)^\\d+ CHAIN_END chain=${Regex.escape(id)} state=(\\w+)").findAll(text).lastOrNull()
        ?.groupValues?.get(1) ?: if (id == "application") "DIAGNOSTIC" else if (live) "RUNNING" else "UNFINISHED"
    val cleanupFailed: Boolean get() = text.contains("cleanup_failed=true") || text.contains("code=CLEANUP")
}

/** File I/O is serialized off the UI thread. The bounded memory copy survives a disk failure. */
class DiagnosticStore(
    private val directory: File,
    private val maxFiles: Int = 40,
    private val maxChars: Int = 64 * 1024,
    private val onNewFailure: (String, String) -> Unit = { _, _ -> },
    private val onStorageFailure: (String, String?) -> Unit = { _, _ -> },
) {
    init { require(maxFiles > 0 && maxChars > 0) }
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "uu-diagnostics").apply { isDaemon = true } }
    private val entries = LinkedHashMap<String, DiagnosticEntry>()
    @Volatile var storageError: String? = null
        private set

    private val dirty = LinkedHashSet<String>()
    private val retiredAgentSessions = mutableSetOf<String>()
    private var draining = false

    fun append(id: String, stage: String, message: String, failed: Boolean = false, output: String? = null) {
        require(id.matches(Regex("[A-Za-z0-9_-]+")))
        synchronized(entries) {
            if (stage in retiredAgentSessions) return
            val previous = entries[id]
            val outputs = previous?.outputs.orEmpty()
            if (output != null && (output in outputs || outputs.size >= 64)) return
            val combined = (previous?.text ?: "") + "${System.currentTimeMillis()} $message\n"
            val marker = "[earlier diagnostic entries truncated]\n".take(maxChars)
            val text = if (combined.length <= maxChars) combined else marker + combined.takeLast(maxChars - marker.length)
            entries[id] = DiagnosticEntry(id, stage, text, failed || previous?.failed == true,
                outputs = if (output == null) outputs else outputs + output)
            if (failed && previous?.failed != true) onNewFailure(id, stage)
            while (entries.size > maxFiles) {
                val expired = entries.keys.first()
                entries.remove(expired); dirty.remove(expired)
            }
            dirty.add(id)
            if (!draining) {
                draining = true
                writer.submit { drain() }
            }
        }
    }

    /** Coalesce frequent updates; at most the retained records wait for disk, not one queued copy per event. */
    private fun drain() {
        while (true) {
            val entry = synchronized(entries) {
                val id = dirty.firstOrNull() ?: run { draining = false; return }
                dirty.remove(id)
                entries.getValue(id)
            }
            try {
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create diagnostic directory")
                File(directory, "${entry.id}.log").writeText("feature=${entry.feature}\nfailed=${entry.failed}\n${entry.text}", Charsets.UTF_8)
                if (entry.outputs.isNotEmpty()) writeTaskOutputs(directory, entry.id, entry.outputs)
                val files = directory.listFiles { f -> f.extension == "log" } ?: throw IOException("Cannot list diagnostic directory")
                files.sortedByDescending { it.lastModified() }.drop(maxFiles).forEach {
                    deleteTaskOutputs(directory, it.nameWithoutExtension)
                    if (!it.delete() && it.exists()) throw IOException("Cannot rotate diagnostic log")
                }
                storageError = null
            } catch (e: Exception) {
                // Never recurse into diagnostics while its own storage is failing.
                storageFailed(e, entry.id)
                System.err.println("UsefulUnpack: diagnostic storage unavailable: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun storageFailed(cause: Exception, id: String? = null) {
        val first = storageError == null
        storageError = cause.javaClass.simpleName
        if (first) onStorageFailure(cause.javaClass.simpleName, id)
    }

    /** Call from a worker: waits for earlier writes, then reads bounded previews. */
    fun snapshot(includeOutputs: Boolean = false): List<DiagnosticEntry> {
        try { flush() } catch (e: Exception) { storageFailed(e) }
        val memory = synchronized(entries) { entries.toMap() }
        val disk = try {
            directory.listFiles { f -> f.extension == "log" && f.nameWithoutExtension !in memory }
                ?.sortedByDescending { it.lastModified() }
                ?.take(maxFiles - memory.size)
                ?.map { readDiskEntry(it, includeOutputs) }.orEmpty()
        } catch (e: Exception) {
            storageFailed(e)
            emptyList()
        }
        return memory.values.reversed() + disk
    }

    private fun readDiskEntry(file: File, includeOutputs: Boolean = false): DiagnosticEntry {
        val text = file.inputStream().reader(Charsets.UTF_8).use { reader ->
            val chars = CharArray(maxChars + 256)
            var count = 0
            while (count < chars.size) {
                val n = reader.read(chars, count, chars.size - count)
                if (n < 0) break
                count += n
            }
            String(chars, 0, count)
        }
        val first = text.lineSequence().firstOrNull().orEmpty()
        val outputs = if (includeOutputs) try {
            readTaskOutputs(directory, file.nameWithoutExtension)
        } catch (e: IOException) {
            storageFailed(e, file.nameWithoutExtension)
            emptyList()
        } else emptyList()
        return DiagnosticEntry(file.nameWithoutExtension, first.removePrefix("feature=").removePrefix("stage="),
            text, text.lineSequence().drop(1).firstOrNull() == "failed=true", live = false, outputs = outputs)
    }

    /** A restarted client can fetch a single retained chain without reading all logs. */
    internal fun readEntry(id: String): DiagnosticEntry? {
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) return null
        synchronized(entries) { entries[id] }?.let { return it }
        val file = File(directory, "$id.log")
        return try {
            if (file.isFile) readDiskEntry(file) else null
        } catch (e: IOException) {
            storageFailed(e, id)
            throw e
        }
    }

    fun flush() { writer.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS) }

    /** Remove a session's live and on-disk traces, including writes already queued for disk. */
    internal fun deleteAgentSession(feature: String) {
        require(feature.matches(Regex("agent_session_[a-f0-9]{32}")))
        val deletion = synchronized(entries) {
            retiredAgentSessions.add(feature)
            val owned = entries.values.filter { it.feature == feature }.map { it.id }
            owned.forEach { id -> entries.remove(id); dirty.remove(id) }
            writer.submit {
                if (directory.exists()) {
                    val files = directory.listFiles { file -> file.extension == "log" }
                        ?: throw IOException("Cannot list diagnostic directory")
                    files.forEach { file ->
                        val header = file.bufferedReader(Charsets.UTF_8).use { it.readLine() }
                        if (header == "feature=$feature") {
                            deleteTaskOutputs(directory, file.nameWithoutExtension)
                            java.nio.file.Files.deleteIfExists(file.toPath())
                        }
                    }
                }
            }
        }
        deletion.get()
    }

    fun close() { flush(); writer.shutdown() }
}

enum class ChainStatus { RUNNING, SUCCESS, PARTIAL_FAILURE, FAILED, CANCELLED }

internal class IntentChain(val id: String, val feature: String) {
    private val refs = java.util.concurrent.atomic.AtomicInteger()
    val sequence = java.util.concurrent.atomic.AtomicInteger()
    private val started = System.nanoTime()
    private var ended = false
    private var cancelled = false
    private var failed = false
    private var partial = false
    private var cleanupFailed = false
    private val errorCodes = LinkedHashSet<FailureCode>()
    private val failedSteps = LinkedHashSet<String>()
    private var firstFailure: Pair<FailureCode, String>? = null
    @Synchronized fun firstFailure(): Pair<FailureCode, String>? = firstFailure
    @Synchronized fun failureStatus(): ChainStatus? = when {
        failed -> ChainStatus.FAILED
        partial || cleanupFailed -> ChainStatus.PARTIAL_FAILURE
        else -> null
    }
    @Synchronized fun isEnded(): Boolean = ended
    @Synchronized fun isCancelled(): Boolean = cancelled
    @Synchronized fun onlyCleanupFailed(): Boolean = cleanupFailed && !failed
    @Synchronized fun retain() { refs.incrementAndGet() }
    @Synchronized fun failure(code: FailureCode, stage: String) {
        if (firstFailure == null) firstFailure = code to stage
        errorCodes.add(code)
        if (failedSteps.size < 16) failedSteps.add(stage)
        when (code) {
            FailureCode.CLEANUP -> cleanupFailed = true
            FailureCode.PARTIAL_FAILURE -> partial = true
            else -> failed = true
        }
    }
    @Synchronized fun cancel() { cancelled = true }
    @Synchronized fun release() {
        if (refs.decrementAndGet() != 0 || ended) return
        ended = true
        val state = when {
            cancelled -> ChainStatus.CANCELLED
            failed -> ChainStatus.FAILED
            partial || cleanupFailed -> ChainStatus.PARTIAL_FAILURE
            else -> ChainStatus.SUCCESS
        }
        AppDiagnostics.store?.append(id, feature,
            "CHAIN_END chain=$id state=$state elapsed_ms=${(System.nanoTime() - started) / 1_000_000} cleanup_failed=$cleanupFailed error_codes=${errorCodes.joinToString(",").ifEmpty { "NONE" }} failed_steps=${failedSteps.joinToString(",").ifEmpty { "NONE" }}",
            failed || partial || cleanupFailed)
    }
}

class OperationTrace internal constructor(
    val id: String,
    val feature: String,
    private val stage: String,
    private val secrets: List<String>,
    val spanId: String = UUID.randomUUID().toString().take(8),
    val parentSpan: String? = null,
    internal val chain: IntentChain? = null,
    val sequence: Int = 0,
) {
    private val started = System.nanoTime()
    private fun safe(text: String): String = AppDiagnostics.redact(text, secrets)
    private fun line(message: String) = message.lineSequence().joinToString("\n") {
        "[chain=$id seq=$sequence span=$spanId parent=${parentSpan ?: "root"} step=$stage] $it"
    }
    fun note(message: String) { event(safe(message)) }
    internal fun publishOutput(file: File) {
        if (chain == null || chain.isEnded() || !file.isAbsolute) return
        AppDiagnostics.store?.append(id, feature, line("OUTPUT_PUBLISHED"), output = file.absolutePath)
    }
    /** Typed metadata is trusted: secret redaction must never mutate chain IDs, states or codes. */
    internal fun event(message: String, failed: Boolean = false) { AppDiagnostics.store?.append(id, feature, line(message), failed) }
    fun fail(
        code: FailureCode, detail: String, cause: Throwable? = null,
        notifyUser: Boolean = true,
    ): AppFailure {
        val failure = AppFailure(code, stage, safe(detail), id)
        if (code != FailureCode.CLEANUP && AppDiagnostics.isCancellationRequested()) {
            cancelled()
            return failure
        }
        chain?.failure(code, stage)
        val state = if (code == FailureCode.PARTIAL_FAILURE) "PARTIAL_FAILURE" else "FAILED"
        event("STEP_END state=$state elapsed_ms=${(System.nanoTime() - started) / 1_000_000} code=$code: ${safe(detail)}\n${safe(cause?.stackTraceToString().orEmpty())}", true)
        // MCP returns the failure to the caller and uses one persistent reminder in Settings.
        if (notifyUser && !feature.startsWith("mcp_")) AppDiagnostics.publish(failure)
        return failure
    }
    fun cancelled() { chain?.cancel(); event("STEP_END state=CANCELLED elapsed_ms=${(System.nanoTime() - started) / 1_000_000} code=CANCELLED") }
    fun success() = event("STEP_END state=SUCCESS elapsed_ms=${(System.nanoTime() - started) / 1_000_000} code=OK")
    internal fun rejectForRetry(code: FailureCode, detail: String) = event(
        "STEP_END state=FAILED elapsed_ms=${(System.nanoTime() - started) / 1_000_000} code=$code retryable=true: ${safe(detail)}",
        failed = true,
    )
    fun skipped() = event("STEP_END state=SKIPPED elapsed_ms=${(System.nanoTime() - started) / 1_000_000} code=NOT_APPLICABLE")
    fun retain() { chain?.retain() }
    fun release() { chain?.release() }
}

data class IntentScope internal constructor(val trace: OperationTrace, internal val previous: OperationTrace?)

/** No raw argv, passwords, keys or document contents should be passed to the logger. */
internal fun redactDiagnostic(text: String, secrets: List<String>): String {
    var result = text
    secrets.filter { it.isNotEmpty() }.sortedByDescending { it.length }.forEach { result = result.replace(it, "[redacted]") }
    result = result.replace(Regex("(?i)(password|key|secret|token)\\s*[:=]\\s*[^\\s,;]+"), "$1=[redacted]")
    result = result.replace(Regex("""(?i)(?<!\S)(-p|--password|-k|--key)(?:=|\s+)(?:"[^"]*"|'[^']*'|\S+)""")) { "${it.groupValues[1]} [redacted]" }
    return result.replace(Regex("/(?:[^/\\s]+/)+([^/\\s]+)")) { "…/" + it.groupValues[1] }
}

object AppDiagnostics {
    @Volatile var store: DiagnosticStore? = null
    private val listeners = CopyOnWriteArrayList<(AppFailure) -> Unit>()
    private val pending = ArrayDeque<AppFailure>()
    private val knownSecrets = LinkedHashSet<String>()
    private val current = ThreadLocal<OperationTrace?>()
    fun currentTrace(): OperationTrace? = current.get()?.takeUnless { it.chain?.isEnded() == true }
    fun <T> withTrace(trace: OperationTrace, block: () -> T): T {
        val previous = current.get()
        current.set(trace)
        try { return block() } finally { current.set(previous) }
    }
    fun <T> invokeCallback(trace: OperationTrace?, block: () -> T): T {
        fun invoke(): T = try { block() }
        catch (e: CancellationException) { currentTrace()?.cancelled(); throw e }
        catch (e: Exception) { failure("ui_callback", e); throw e }
        return if (trace == null) invoke() else withTrace(trace) { invoke() }
    }
    private val cancellation = ThreadLocal<(() -> Boolean)?>()
    fun bindCancellation(check: (() -> Boolean)?) { cancellation.set(check) }
    fun isCancellationRequested(): Boolean = cancellation.get()?.invoke() == true || currentTrace()?.chain?.isCancelled() == true
    internal fun redact(text: String, extra: List<String> = emptyList()): String =
        redactDiagnostic(text, synchronized(knownSecrets) { knownSecrets.toList() } + extra)
    private val ids = java.util.concurrent.atomic.AtomicLong()
    private val application by lazy { OperationTrace("application", "application", "unscoped", emptyList()) }
    private fun registerSecrets(secrets: List<String>) = synchronized(knownSecrets) {
        knownSecrets.addAll(secrets.filter { it.isNotEmpty() })
        while (knownSecrets.size > 128) knownSecrets.remove(knownSecrets.first())
    }

    /** Only user-intent boundaries open a chain. Helpers and IO never do. */
    fun startChain(feature: String, secrets: List<String> = emptyList()): OperationTrace {
        registerSecrets(secrets)
        val id = "${System.currentTimeMillis().toString(36)}-${ids.incrementAndGet().toString(36)}"
        val chain = IntentChain(id, feature)
        return OperationTrace(id, feature, feature, secrets, chain = chain).also {
            it.event("CHAIN_START state=RUNNING")
        }
    }
    fun enterIntent(feature: String, secrets: List<String> = emptyList(), newChain: Boolean = false): IntentScope {
        registerSecrets(secrets)
        val previous = current.get()
        val trace = if (newChain || previous?.chain?.isEnded() != false) startChain(feature, secrets) else previous
        trace.retain()
        current.set(trace)
        return IntentScope(trace, previous)
    }
    fun leaveIntent(scope: IntentScope) { current.set(scope.previous); scope.trace.release() }

    /** Starts a fallible step under the current chain, or an application diagnostic when unscoped. */
    fun begin(stage: String, secrets: List<String> = emptyList()): OperationTrace {
        registerSecrets(secrets)
        val parent = currentTrace() ?: application
        return OperationTrace(parent.id, parent.feature, stage, secrets,
            parentSpan = parent.spanId, chain = parent.chain,
            sequence = parent.chain?.sequence?.incrementAndGet() ?: 0).also {
            it.event("STEP_START state=RUNNING")
        }
    }
    fun <T> step(name: String, block: () -> T): T {
        val trace = begin(name)
        return withTrace(trace) {
            try { block().also { if (isCancellationRequested()) trace.cancelled() else trace.success() } }
            catch (e: CancellationException) { trace.cancelled(); throw e }
            catch (e: Exception) { trace.fail(FailureCode.IO, e.message ?: e.javaClass.simpleName, e); throw e }
        }
    }

    fun failure(stage: String, cause: Throwable): AppFailure {
        if (cause is CancellationException) throw cause
        if (cause is InterruptedException) { Thread.currentThread().interrupt(); throw CancellationException("Interrupted").apply { initCause(cause) } }
        return begin(if (current.get() == null) "$stage:${diagnosticCaller()}" else stage).fail(FailureCode.UNEXPECTED, cause.message ?: cause.javaClass.simpleName, cause)
    }

    internal fun publish(failure: AppFailure) {
        synchronized(pending) {
            pending.addLast(failure)
            while (pending.size > 40) pending.removeFirst()
        }
        listeners.forEach { it(failure) }
    }
    fun addListener(listener: (AppFailure) -> Unit) { listeners.add(listener) }
    fun removeListener(listener: (AppFailure) -> Unit) { listeners.remove(listener) }
    fun takePending(): List<AppFailure> = synchronized(pending) { pending.toList().also { pending.clear() } }
    fun notice(text: String) {
        val trace = currentTrace() ?: application
        trace.note("NOTICE ${redact(text)}")
    }
}

/** Fallback identity for UI/file operations that do not yet need an explicit public feature key. */
internal fun diagnosticCaller(): String {
    val caller = Throwable().stackTrace.firstOrNull {
        it.className.startsWith("com.usefulunpacker.") &&
            !it.className.startsWith("com.usefulunpacker.DiagnosticsKt") &&
            !it.className.startsWith("com.usefulunpacker.AppDiagnostics") &&
            !it.className.startsWith("com.usefulunpacker.UuNotice") &&
            !(it.className == "com.usefulunpacker.MainActivity" && it.methodName == "toast")
    } ?: return "application"
    val parts = caller.className.substringAfterLast('.').split('$')
    val method = if (caller.methodName.startsWith("invoke")) parts.drop(1).firstOrNull { it.isNotEmpty() && !it.all(Char::isDigit) } ?: caller.methodName else caller.methodName
    return "${parts.first().removeSuffix("Kt")}.$method"
}

/** Captures trace identity when work is scheduled, so sibling threads cannot share ambient state. */
fun featureThread(start: Boolean = true, trace: OperationTrace? = AppDiagnostics.currentTrace(), block: () -> Unit): Thread {
    trace?.retain()
    return kotlin.concurrent.thread(start = start, name = trace?.let { "uu-${it.id}" } ?: "uu-background") {
        fun run() {
            try { block() }
            catch (e: CancellationException) { trace?.cancelled() }
            catch (e: InterruptedException) { Thread.currentThread().interrupt(); trace?.cancelled() }
            catch (e: Exception) {
                (trace ?: AppDiagnostics.begin("background")).fail(FailureCode.UNEXPECTED, e.message ?: e.javaClass.simpleName, e)
                throw e
            } finally { trace?.release() }
        }
        if (trace == null) run() else AppDiagnostics.withTrace(trace) { run() }
    }
}

/** Pool tasks need the same context lifetime as dedicated feature threads. */
fun executeFeature(executor: java.util.concurrent.Executor, trace: OperationTrace? = AppDiagnostics.currentTrace(), block: () -> Unit) {
    trace?.retain()
    val started = java.util.concurrent.atomic.AtomicBoolean()
    try {
        executor.execute {
            started.set(true)
            val worker = Thread.currentThread()
            val previousName = worker.name
            worker.name = trace?.let { "uu-${it.id}" } ?: "uu-background"
            try { AppDiagnostics.invokeCallback(trace, block) }
            finally { worker.name = previousName; trace?.release() }
        }
    } catch (e: Exception) {
        if (started.get()) throw e
        // Rejection happens before a task can release its retained reference.
        try { AppDiagnostics.invokeCallback(trace) { throw e } }
        finally { trace?.release() }
    }
}

/** Inline so existing early returns still run the scope's finally block. */
inline fun <T> userIntent(feature: String, block: () -> T): T {
    val scope = AppDiagnostics.enterIntent(feature)
    try { return block() }
    catch (e: CancellationException) { scope.trace.cancelled(); throw e }
    catch (e: Exception) { scope.trace.fail(FailureCode.UNEXPECTED, e.message ?: e.javaClass.simpleName, e); throw e }
    finally { AppDiagnostics.leaveIntent(scope) }
}

/** Cleanup problems are independent failures, even after a user cancelled the main task. */
fun deleteOutputChecked(file: File, recursive: Boolean = false, cleanup: Boolean = true): Boolean {
    val trace = AppDiagnostics.begin(if (cleanup) "cleanup" else "delete")
    val code = if (cleanup) FailureCode.CLEANUP else FailureCode.IO
    return try {
        val ok = if (!file.exists()) true else if (recursive) file.deleteRecursively() else file.delete()
        if (!ok) trace.fail(code, "Cannot remove ${file.path}") else trace.success()
        ok
    } catch (e: Exception) {
        trace.fail(code, e.message ?: e.javaClass.simpleName, e)
        false
    }
}

/** For a fallible operation whose caller deliberately continues with a fallback. */
inline fun <T> observedAttempt(stage: String, block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: Exception) {
    AppDiagnostics.failure(stage, e)
    Result.failure(e)
}
