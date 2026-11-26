package com.usefulunpacker

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class AgentChangesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun diffKeepsUnchangedLinesBetweenSeparateEdits() {
        val lines = agentTextDiff("a\nb\nc\nd\ne", "a\nB\nc\nD\ne")!!
        assertEquals(listOf(2, 4), lines.filter { it.kind == -1 }.map { it.oldLine })
        assertEquals(listOf(2, 4), lines.filter { it.kind == 1 }.map { it.newLine })
        assertEquals(0, lines.single { it.text == "c" }.kind)
        assertNull(agentTextDiff("a\n".repeat(1001), "changed"))
    }

    @Test fun readsWithoutWriteApprovalDoNotCaptureContent() {
        val root = temp.newFolder().canonicalFile
        root.resolve("note.txt").writeText("before")
        val capture = AgentChangeCapture(root)
        McpWorkspace(root, capture::watch).resolve("note.txt").writeText("after")
        assertTrue(capture.finish().isEmpty())
    }

    @Test fun compressionSourcesDoNotConsumeTheOutputCaptureBudget() {
        val root = temp.newFolder().canonicalFile
        val source = root.resolve("source").apply { mkdir() }
        repeat(150) { source.resolve("$it.txt").writeText("unchanged") }
        val capture = AgentChangeCapture(root)
        capture.enable(AgentApproval("COMPRESS", JSONObject().put("output", "result.zip")))
        val workspace = McpWorkspace(root, capture::watch)
        workspace.resolve("source")
        workspace.resolve("result.zip").writeBytes(byteArrayOf(0, 1, 2))
        assertEquals(listOf("result.zip"), capture.finish().map { it.path })
        assertFalse(capture.incomplete)
    }

    @Test fun subsequentRoundComparesAgainstThePreviousResultAndRetiresOnlyItsOldLink() {
        val root = temp.newFolder().canonicalFile
        val file = root.resolve("note.txt").apply { writeText((1..12).joinToString("\n") { "line $it" }) }
        fun change(old: String, new: String): AgentFileChange {
            val capture = AgentChangeCapture(root).apply { enable() }
            val path = McpWorkspace(root, capture::watch).resolve("note.txt")
            path.writeText(path.readText().replace(old, new))
            return capture.finish().single()
        }
        val first = change("line 2", "earlier edit")
        val next = change("line 9", "newer edit")
        assertEquals(listOf(9), next.lines.filter { it.kind == 1 }.map { it.newLine })
        assertEquals(0, next.lines.single { it.text == "earlier edit" }.kind)
        val old = AgentExchange("first", "done", changes = listOf(first))
        val session = AgentSession(turns = listOf(old, AgentExchange("second")))
            .completeTurn(AgentExchange("second", "done", changes = listOf(next)))
        assertTrue(session.turns.first().changes.isEmpty())
        assertEquals(next, session.turns.last().changes.single())
        assertEquals(file.readText(), next.lines.filter { it.kind != -1 }.joinToString("\n") { it.text })
    }

    @Test fun ordinaryTextWriteAndDeletionProduceReceiptsThroughNormalEntryPoints() {
        val root = temp.newFolder().canonicalFile
        root.resolve("deleted.txt").writeText("original")
        val capture = AgentChangeCapture(root).apply { enable() }
        val workspace = McpWorkspace(root, capture::watch)
        val scope = AppDiagnostics.enterIntent("agent_session_${"a".repeat(32)}", newChain = true)
        try {
            McpTasks { _, _ -> error("No asynchronous task expected") }.use { tasks ->
                val runtime = WorkspaceSkillRuntime(workspace, tasks)
                val result = runtime.invokeSkill("WRITE_TEXT", JSONObject().put("path", "new.txt").put("text", "created"))
                assertFalse(result.isError)
                assertFalse(deleteAgentFile(workspace, JSONObject().put("path", "deleted.txt")).isError)
            }
            val files = capture.finish().associateBy { it.path }
            assertFalse(files.getValue("new.txt").existedBefore)
            assertEquals(1, files.getValue("new.txt").lines.single().kind)
            assertFalse(files.getValue("deleted.txt").existsAfter)
            assertEquals(-1, files.getValue("deleted.txt").lines.single().kind)
        } finally { AppDiagnostics.leaveIntent(scope) }
    }

    @Test fun uuInPlaceEditsUseTheSameCaptureAndUnchangedReadsDisappear() {
        val root = temp.newFolder().canonicalFile
        root.resolve("note.txt").writeText("before")
        root.resolve("unchanged.txt").writeText("read only")
        val capture = AgentChangeCapture(root).apply { enable() }
        val workspace = McpWorkspace(root, capture::watch)
        workspace.resolve("unchanged.txt").readText()
        val result = UuText.withWorkspace(workspace) {
            UuCommands.dispatch(listOf("sed", "before", "after", "note.txt", "-i"), UuCommands.Ctx(null, root))
        }
        assertEquals(0, result.exitCode)
        val file = capture.finish().single()
        assertEquals("note.txt", file.path)
        assertEquals(listOf("before", "after"), file.lines.map { it.text })
        assertEquals(file, AgentFileChange.read(file.json()))
    }

    @Test fun newDirectoryContentsAndSymbolicLinksAreHandledWithoutFollowingLinks() {
        val root = temp.newFolder().canonicalFile
        val outside = temp.newFile().apply { writeText("private") }
        val capture = AgentChangeCapture(root).apply { enable() }
        val output = McpWorkspace(root, capture::watch).resolve("output")
        output.mkdir()
        output.resolve("new.txt").writeText("created")
        Files.createSymbolicLink(output.resolve("link").toPath(), outside.toPath())
        val files = capture.finish()
        assertTrue(files.any { it.path == "output/new.txt" })
        assertFalse(files.any { it.path.contains("link") })
    }

    @Test fun binaryAndLargeTextHaveExplicitMetadataReceipts() {
        val root = temp.newFolder().canonicalFile
        val capture = AgentChangeCapture(root).apply { enable() }
        val workspace = McpWorkspace(root, capture::watch)
        workspace.resolve("payload.bin").writeBytes(byteArrayOf(0, 1, 0, 2))
        workspace.resolve("long.txt").writeText("x".repeat(40 * 1024))
        assertTrue(capture.finish().all { it.limited && it.lines.isEmpty() })
    }

    @Test fun uutRedirectionIsCapturedAfterTheWorkerSettles() {
        val root = temp.newFolder().canonicalFile
        val capture = AgentChangeCapture(root).apply { enable() }
        val workspace = McpWorkspace(root, capture::watch)
        val worker = McpArchiveWorker(workspace)
        val tasks = McpTasks(execute = worker::execute)
        try {
            val runtime = WorkspaceSkillRuntime(workspace, tasks)
            val result = runtime.invokeSkill("RUN_UUT", JSONObject().put("script", "echo created > result.txt")
                .put("waitMs", 15000))
            assertFalse(result.isError)
        } finally { tasks.closeAndAwait() }
        val change = capture.finish().single()
        assertEquals("result.txt", change.path)
        assertFalse(change.existedBefore)
        assertTrue(change.lines.any { it.kind == 1 && it.text == "created" })
    }

    @Test fun pausedReadToolsDoNotAddUnrelatedPathsToTheWriteReceipt() {
        val root = temp.newFolder().canonicalFile
        val capture = AgentChangeCapture(root).apply { enable() }
        val workspace = McpWorkspace(root, capture::watch)
        workspace.resolve("written.txt").writeText("created")
        capture.pause()
        workspace.resolve("read.txt").writeText("external write after read access")
        assertEquals(listOf("written.txt"), capture.finish().map { it.path })
    }

    @Test fun shutdownWaitsForCancellationCleanupBeforeReturning() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val cancelling = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val cleaned = java.util.concurrent.atomic.AtomicBoolean()
        val tasks = McpTasks { task, _ ->
            task.bindNativeCancel { cancelling.countDown() }
            entered.countDown()
            release.await()
            cleaned.set(true)
        }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            tasks.submit(listOf(McpCommand.parse(listOf("cp", "source", "output"))), script = false)
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val closing = executor.submit { tasks.closeAndAwait() }
            assertTrue(cancelling.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(cleaned.get())
            release.countDown()
            closing.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(cleaned.get())
        } finally {
            release.countDown()
            tasks.closeAndAwait()
            executor.shutdownNow()
        }
    }

    @Test fun diffReceiptSurvivesSessionReloadAndDoesNotEnterModelMessages() {
        val change = AgentFileChange("note.txt", true, true, 6, 5, agentTextDiff("before", "after")!!, false)
        val directory = temp.newFolder()
        val store = AgentSessionStore(directory)
        val turn = AgentExchange("edit", "done", changes = listOf(change))
        val saved = store.save(AgentSession(turns = listOf(turn)))
        val restored = AgentSessionStore(directory).open(saved.id)
        assertEquals(turn, restored.turns.single())
        assertEquals(listOf("edit", "done"), restored.historyWindow().flatMap { listOf(it.question, it.answer) })
    }

}
