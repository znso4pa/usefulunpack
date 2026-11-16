package com.usefulunpacker

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DiagnosticsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun concurrentWritesKeepTheFailureAndEveryMessage() {
        val dir = temp.newFolder()
        val store = DiagnosticStore(dir)
        val pool = Executors.newFixedThreadPool(4)
        try {
            repeat(100) { n -> pool.submit { store.append("operation", "extract", "event-$n", n == 13) } }
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            store.flush()
            val text = File(dir, "operation.log").readText()
            assertTrue(text.contains("failed=true"))
            repeat(100) { assertTrue("missing event $it", text.contains("event-$it\n")) }
        } finally { pool.shutdownNow(); store.close() }
    }

    @Test fun rotationBoundsDiskAndMemoryAndCanReadAfterRestart() {
        val dir = temp.newFolder()
        val store = DiagnosticStore(dir, maxFiles = 3, maxChars = 256)
        try {
            repeat(8) { store.append("task-$it", "pack", "x".repeat(1000), it == 7) }
            store.flush()
            assertEquals(3, store.snapshot().size)
            assertTrue(dir.listFiles()!!.size <= 3)
            assertTrue(dir.listFiles()!!.all { it.length() < 1024 })
            val reopened = DiagnosticStore(dir, maxFiles = 3, maxChars = 256)
            try { assertTrue(reopened.snapshot().any { it.id == "task-7" && it.failed }) }
            finally { reopened.close() }
        } finally { store.close() }
    }

    @Test fun unwritableStoragePreservesTheFailureInMemory() {
        val store = DiagnosticStore(temp.newFile())
        try {
            store.append("operation", "copy", "original failure", true)
            store.flush()
            assertNotNull(store.storageError)
            assertEquals("operation", store.snapshot().single().id)
            assertTrue(store.snapshot().single().text.contains("original failure"))
        } finally { store.close() }
    }

    @Test fun secretsAreRemovedFromDetailsStackAndNotices() {
        val store = DiagnosticStore(temp.newFolder())
        val previous = AppDiagnostics.store
        AppDiagnostics.store = store
        try {
            val trace = AppDiagnostics.begin("extract", listOf("private-password"))
            trace.fail(FailureCode.IO, "password=private-password", IOException("private-password"))
            AppDiagnostics.notice("private-password")
            val entries = store.snapshot()
            assertTrue(entries.all { !it.text.contains("private-password") })
            assertTrue(entries.any { it.text.contains("[redacted]") })
        } finally { AppDiagnostics.store = previous; AppDiagnostics.takePending(); store.close() }
    }

    @Test fun cancellationDoesNotPublishFailureButCleanupStillDoes() {
        AppDiagnostics.takePending()
        AppDiagnostics.bindCancellation { true }
        try {
            AppDiagnostics.begin("extract").fail(FailureCode.IO, "cancelled")
            assertTrue(AppDiagnostics.takePending().isEmpty())
            AppDiagnostics.begin("cleanup").fail(FailureCode.CLEANUP, "partial output remains")
            assertEquals(FailureCode.CLEANUP, AppDiagnostics.takePending().single().code)
        } finally { AppDiagnostics.bindCancellation(null) }
    }

    @Test(expected = CancellationException::class)
    fun fallbackCannotSwallowCancellation() {
        observedAttempt("test") { throw CancellationException() }.getOrDefault(Unit)
    }

    @Test fun mergeRefusesPartialOrMissingResults() {
        requireMergeExtraction(ExtractOutcome(ExtractCounts(2, 2, 0), null), "target")
        for (bad in listOf(
            ExtractOutcome(ExtractCounts(2, 1, 1), null),
            ExtractOutcome(ExtractCounts(0, 0, 0), null),
            ExtractOutcome(ExtractCounts(2, 2, 0), "I/O failure")
        )) {
            try { requireMergeExtraction(bad, "source"); fail("accepted failed extraction") }
            catch (_: IOException) {}
        }
    }

    @Test fun mergeOverlaysSourceAndStopsBeforeSourceOnTargetFailure() {
        val file = temp.newFile()
        val ok = ExtractOutcome(ExtractCounts(1, 1, 0), null)
        assertTrue(extractMergeInputs({ false },
            { file.writeText("target"); ok },
            { file.writeText("source"); ok }))
        assertEquals("source", file.readText())
        var sourceCalled = false
        try {
            extractMergeInputs({ false }, { ExtractOutcome(ExtractCounts(2, 1, 1), null) }, { sourceCalled = true; ok })
            fail("accepted incomplete target")
        } catch (_: IOException) { assertFalse(sourceCalled) }
        assertFalse(extractMergeInputs({ true }, { fail("started target after cancel"); ok }, { ok }))
    }

    @Test fun nestedStepsShareOneFeatureLogAndScopeIsRestored() {
        val root = AppDiagnostics.startChain("archive.merge")
        AppDiagnostics.withTrace(root) {
            val child = AppDiagnostics.begin("target.extract")
            assertEquals(root.id, child.id)
            assertEquals("archive.merge", child.feature)
            assertEquals(root.spanId, child.parentSpan)
            AppDiagnostics.withTrace(child) {
                val nested = AppDiagnostics.begin("cleanup")
                assertEquals(child.spanId, nested.parentSpan)
            }
            assertSame(root, AppDiagnostics.currentTrace())
        }
        assertNull(AppDiagnostics.currentTrace())
        assertNotEquals(root.id, AppDiagnostics.startChain("archive.extract").id)
        assertNotEquals(root.id, AppDiagnostics.startChain("archive.merge").id)
    }

    @Test fun concurrentFeaturesAndNestedWorkersCannotMixLogs() {
        val store = DiagnosticStore(temp.newFolder())
        val previous = AppDiagnostics.store
        AppDiagnostics.store = store
        val ready = java.util.concurrent.CountDownLatch(2)
        try {
            val a = AppDiagnostics.startChain("archive.extract")
            val b = AppDiagnostics.startChain("archive.pack")
            val workers = listOf(a to "extract-only", b to "pack-only").map { (root, marker) ->
                featureThread(trace = root) {
                    ready.countDown()
                    check(ready.await(5, TimeUnit.SECONDS))
                    val nested = featureThread { AppDiagnostics.begin("native").note(marker) }
                    nested.join(5000)
                    check(!nested.isAlive)
                    AppDiagnostics.notice(marker)
                }
            }
            workers.forEach { it.join(5000); assertFalse(it.isAlive) }
            val logs = store.snapshot()
            assertEquals(2, logs.size)
            val extract = logs.single { it.feature == "archive.extract" }
            val pack = logs.single { it.feature == "archive.pack" }
            assertTrue(extract.text.contains("extract-only")); assertFalse(extract.text.contains("pack-only"))
            assertTrue(pack.text.contains("pack-only")); assertFalse(pack.text.contains("extract-only"))
            assertTrue(extract.text.contains("parent=${a.spanId}"))
        } finally { AppDiagnostics.store = previous; store.close() }
    }

    @Test fun ioHelpersDoNotOpenIndependentChains() {
        val first = AppDiagnostics.begin("read_metadata")
        val second = AppDiagnostics.begin("read_header")
        assertEquals("application", first.id)
        assertEquals(first.id, second.id)
        val scope = AppDiagnostics.enterIntent("merge", newChain = true)
        try {
            val target = AppDiagnostics.begin("extract_target")
            val source = AppDiagnostics.begin("extract_source")
            assertEquals(scope.trace.id, target.id)
            assertEquals(target.id, source.id)
            assertTrue(source.sequence > target.sequence)
            assertTrue(scope.trace.id.length < 24)
        } finally { AppDiagnostics.leaveIntent(scope) }
    }

    @Test fun asynchronousWorkKeepsTheIntentRunningAndUsesItsIdAsThreadName() {
        val store = DiagnosticStore(temp.newFolder())
        val previous = AppDiagnostics.store
        AppDiagnostics.store = store
        val ready = java.util.concurrent.CountDownLatch(1)
        try {
            val scope = AppDiagnostics.enterIntent("extract_all", newChain = true)
            val worker = featureThread {
                check(ready.await(5, TimeUnit.SECONDS))
                assertTrue(Thread.currentThread().name.contains(scope.trace.id))
                AppDiagnostics.step("extract") { Unit }
            }
            AppDiagnostics.leaveIntent(scope)
            assertEquals("RUNNING", store.snapshot().single().status)
            ready.countDown(); worker.join(5000)
            assertFalse(worker.isAlive)
            assertEquals("SUCCESS", store.snapshot().single().status)
        } finally { ready.countDown(); AppDiagnostics.store = previous; store.close() }
    }

    @Test fun cancellationAndCleanupHaveExplicitTerminalPolicies() {
        val store = DiagnosticStore(temp.newFolder())
        val previous = AppDiagnostics.store
        AppDiagnostics.store = store
        try {
            userIntent("cancelled_copy") {
                AppDiagnostics.currentTrace()!!.cancelled()
                AppDiagnostics.begin("cleanup").fail(FailureCode.CLEANUP, "partial output remains")
            }
            userIntent("successful_copy") {
                AppDiagnostics.step("copy") { Unit }
                AppDiagnostics.begin("cleanup").fail(FailureCode.CLEANUP, "partial output remains")
            }
            val records = store.snapshot()
            assertEquals("CANCELLED", records.single { it.feature == "cancelled_copy" }.status)
            assertEquals("PARTIAL_FAILURE", records.single { it.feature == "successful_copy" }.status)
            assertTrue(records.all { it.cleanupFailed })
            assertTrue(records.all { it.text.contains("elapsed_ms=") })
            assertTrue(records.all { it.text.contains("code=CLEANUP") })
        } finally { AppDiagnostics.store = previous; AppDiagnostics.takePending(); store.close() }
    }

    @Test fun pathsAreShortenedBeforeTheyReachLocalOrSharedLogs() {
        val original = "/storage/emulated/0/private-folder/archive.zip"
        val redacted = redactDiagnostic("input=$original password=secret", listOf("secret"))
        assertFalse(redacted.contains("private-folder"))
        assertFalse(redacted.contains("/storage"))
        assertTrue(redacted.contains("archive.zip"))
        assertFalse(redacted.contains("secret"))
    }

    @Test fun finishedCallbacksCannotReviveAnOldIntent() {
        val scope = AppDiagnostics.enterIntent("first", newChain = true)
        AppDiagnostics.leaveIntent(scope)
        AppDiagnostics.withTrace(scope.trace) {
            assertNull(AppDiagnostics.currentTrace())
            assertEquals("application", AppDiagnostics.begin("late_metadata").id)
            val second = AppDiagnostics.enterIntent("second")
            try { assertNotEquals(scope.trace.id, second.trace.id) }
            finally { AppDiagnostics.leaveIntent(second) }
        }
    }

    @Test fun queuedCancellationIsARecordedTerminalState() {
        val store = DiagnosticStore(temp.newFolder())
        val previous = AppDiagnostics.store
        AppDiagnostics.store = store
        val running = com.usefulunpacker.archive.OpScheduler.obtain("zip")
        val scope = AppDiagnostics.enterIntent("extract_all", newChain = true)
        val queued = com.usefulunpacker.archive.OpScheduler.obtain("zip")
        val worker = featureThread { assertFalse(queued.await()) }
        AppDiagnostics.leaveIntent(scope)
        try {
            queued.requestCancel()
            worker.join(5000)
            assertFalse(worker.isAlive)
            val record = store.snapshot().single()
            assertEquals(scope.trace.id, record.id)
            assertEquals("CANCELLED", record.status)
            assertFalse(running.cancelRequested)
        } finally { queued.requestCancel(); running.release(); AppDiagnostics.store = previous; store.close() }
    }

    @Test fun callbackFailureStaysInTheOriginalChain() {
        val store = DiagnosticStore(temp.newFolder())
        val previous = AppDiagnostics.store
        AppDiagnostics.store = store
        val scope = AppDiagnostics.enterIntent("rename", newChain = true)
        try {
            try { AppDiagnostics.invokeCallback(scope.trace) { throw IOException("failure") }; fail("exception swallowed") }
            catch (_: IOException) {}
        } finally { AppDiagnostics.leaveIntent(scope) }
        try {
            val record = store.snapshot().single()
            assertEquals(scope.trace.id, record.id)
            assertEquals("FAILED", record.status)
            assertTrue(record.text.contains("ui_callback"))
            assertNull(AppDiagnostics.currentTrace())
        } finally { AppDiagnostics.store = previous; AppDiagnostics.takePending(); store.close() }
    }

    @Test fun unknownCommandArgumentsStayInTheTerminalNotTheDiagnostic() {
        val store = DiagnosticStore(temp.newFolder())
        val previous = AppDiagnostics.store
        AppDiagnostics.store = store
        val privateArgument = "raw-input-that-must-not-appear"
        try {
            val context = UuCommands.Ctx(null, temp.root, str = { _, args -> args.joinToString(" ") })
            val result = UuCommands.dispatch(listOf(privateArgument), context)
            assertTrue(result.text.contains(privateArgument))
            assertTrue(result.exitCode != 0)
            val records = store.snapshot()
            assertTrue(records.all { !it.text.contains(privateArgument) })
            assertEquals("FAILED", records.single().status)
            assertTrue(records.single().text.contains("code=COMMAND"))
        } finally { AppDiagnostics.store = previous; AppDiagnostics.takePending(); store.close() }
    }

    @Test fun exceptionTextCannotForgeATerminalState() {
        val record = DiagnosticEntry("short-id", "extract", "123 [chain=short-id] error CHAIN_END chain=short-id state=SUCCESS", true)
        assertEquals("RUNNING", record.status)
        assertEquals("UNFINISHED", record.copy(live = false).status)
        assertEquals("FAILED", record.copy(text = record.text + "\n124 CHAIN_END chain=short-id state=FAILED").status)
    }

    @Test fun shortSecretsCannotRedactChainIdentityOrErrorCodes() {
        val store = DiagnosticStore(temp.newFolder())
        val previous = AppDiagnostics.store
        AppDiagnostics.store = store
        try {
            val trace = OperationTrace("1", "copy", "read", listOf("1", "IO"))
            trace.fail(FailureCode.IO, "password 1 IO")
            val record = store.snapshot().single()
            assertTrue(record.text.contains("chain=1 "))
            assertTrue(record.text.contains("code=IO"))
            assertFalse(record.text.contains("password 1 IO"))
            assertTrue(record.text.contains("[redacted]"))
        } finally { AppDiagnostics.store = previous; AppDiagnostics.takePending(); store.close() }
    }
}
