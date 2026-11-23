package com.usefulunpacker

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URI
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class McpListenerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun clientDiscoversToolsAndRunsAWorkspaceScriptOverHttp() {
        val root = tmp.newFolder()
        val workspace = McpWorkspace(root)
        val tasks = McpTasks { task, step ->
            val src = workspace.resolve(step.source)
            val dst = workspace.resolve(step.output)
            task.publish(step.output) { Files.copy(src.toPath(), dst.toPath()) }
        }
        McpListener(McpProtocol(workspace, tasks, "test"), tasks, root).use { listener ->
            val endpoint = URI(listener.url)
            val init = request(endpoint, listener.token, "initialize",
                JSONObject().put("protocolVersion", "2025-11-25")).rpcResult()
            assertEquals("2025-11-25", init.getString("protocolVersion"))
            assertTrue(init.getString("instructions").contains(".uut"))
            val tools = request(endpoint, listener.token, "tools/list").rpcResult().getJSONArray("tools")
            val toolNames = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }.toSet()
            assertTrue("run_uut" in toolNames)
            assertTrue("archive_list" in toolNames)

            val rejected = call(listener, "run_uu", JSONObject().put("argv", JSONArray(listOf("cp", "../outside", "copy"))))
            assertTrue(rejected.getBoolean("isError"))

            call(listener, "write_text", JSONObject().put("path", "in.txt").put("text", "翻訳"))
            call(listener, "write_text", JSONObject().put("path", "run.uut").put("text", "uu cp in.txt out.txt"))
            val job = call(listener, "run_uut", JSONObject().put("path", "run.uut"))
                .getJSONObject("structuredContent")
            val status = awaitTask(listener, job.getString("taskId"))
            assertEquals("SUCCESS", status.getString("state"))
            assertEquals("翻訳", root.resolve("out.txt").readText())
        }
    }

    @Test fun commandHelpExposesAllUuCommandsUutSyntaxAndFormats() {
        val root = tmp.newFolder()
        val workspace = McpWorkspace(root)
        val worker = McpArchiveWorker(workspace)
        val tasks = McpTasks(worker::execute)
        McpListener(McpProtocol(workspace, tasks, "test"), tasks, root).use { listener ->
            val help = call(listener, "command_help", JSONObject()).getJSONObject("structuredContent")
            val cmdNames = help.getJSONArray("commandNames").toStringSet()
            assertEquals(UuCommands.cmdCount + 1, cmdNames.size)
            for (expected in listOf("x", "c", "l", "cat", "grep", "sed", "enc", "tr", "guess", "scan", "set", "add", "rmd", "run")) {
                assertTrue("missing $expected", expected in cmdNames)
            }
            val extractFmts = help.getJSONArray("extractFormats").toStringSet()
            for (fmt in listOf("xp3", "pfs", "nsa", "rpa", "int", "bgi", "lpk", "rgss", "rpgmv", "zip", "7z", "rar")) {
                assertTrue("missing extract format $fmt", fmt in extractFmts)
            }
            val packFmts = help.getJSONArray("packFormats").toStringSet()
            for (fmt in listOf("xp3", "pfs", "pf6", "nsa", "rpa", "int", "lpk", "rgss3a", "zip", "7z")) {
                assertTrue("missing pack format $fmt", fmt in packFmts)
            }
            assertTrue(help.getJSONArray("uutSyntax").length() >= 6)
        }
    }

    @Test fun executesAllUuFileCommandsAndFullUutScriptWithArgsLoopsAndCapture() {
        val root = tmp.newFolder()
        val workspace = McpWorkspace(root)
        val worker = McpArchiveWorker(workspace)
        val tasks = McpTasks(worker::execute)
        McpListener(McpProtocol(workspace, tasks, "test"), tasks, root).use { listener ->
            call(listener, "write_text", JSONObject().put("path", "s1.ks").put("text", "Hello world\nLine two"))
            call(listener, "write_text", JSONObject().put("path", "s2.ks").put("text", "Hello galgame"))

            // Direct uu command execution (uu hash) with stdout returned in task_status.
            val hashJob = call(listener, "run_uu", JSONObject().put("argv", JSONArray(listOf("hash", "s1.ks"))))
                .getJSONObject("structuredContent")
            val hashStatus = awaitTask(listener, hashJob.getString("taskId"))
            assertEquals("SUCCESS", hashStatus.getString("state"))
            assertTrue(hashStatus.getString("stdout").contains("MD5"))
            assertTrue(hashStatus.getString("stdout").contains("SHA-256"))

            // Full UUT script with positional args ($1, $2), set + $(...) capture, if/else,
            // for-glob loop, while + arithmetic, uu sed -i, pipe and redirection.
            val uut = """
                # Full UUT workflow
                uu mkdir ${'$'}1
                for f in *.ks
                  uu cp ${'$'}f ${'$'}1/${'$'}f -f
                  uu sed "Hello" "${'$'}2" ${'$'}1/${'$'}f -i
                end
                set hits = ${'$'}(uu grep "${'$'}2" ${'$'}1 | wc -l)
                if ${'$'}hits != "0"
                  set n = 0
                  while n < 2
                    set n = ${'$'}n + 1
                  end
                  echo "done:${'$'}n" > ${'$'}1/summary.txt
                else
                  return 1
                end
            """.trimIndent()
            val valid = call(listener, "validate_uut", JSONObject().put("script", uut))
                .getJSONObject("structuredContent")
            assertTrue(valid.getBoolean("valid"))
            assertEquals(4, valid.getInt("steps"))

            val uutJob = call(listener, "run_uut", JSONObject()
                .put("script", uut)
                .put("args", JSONArray(listOf("out_dir", "你好"))))
                .getJSONObject("structuredContent")
            val uutStatus = awaitTask(listener, uutJob.getString("taskId"))
            assertEquals("SUCCESS", uutStatus.getString("state"))
            assertEquals(0, uutStatus.getInt("exitCode"))
            assertEquals("你好 world\nLine two", root.resolve("out_dir/s1.ks").readText())
            assertEquals("你好 galgame", root.resolve("out_dir/s2.ks").readText())
            assertEquals("done:2\n", root.resolve("out_dir/summary.txt").readText())
        }
    }

    @Test fun readAndWriteTextHandleShiftJisUtf16BomOverwriteAndPagination() {
        val root = tmp.newFolder()
        val workspace = McpWorkspace(root)
        val tasks = McpTasks(McpArchiveWorker(workspace)::execute)
        McpListener(McpProtocol(workspace, tasks, "test"), tasks, root).use { listener ->
            // Write Shift-JIS script and read back with auto-detection.
            call(listener, "write_text", JSONObject()
                .put("path", "script_sjis.ks")
                .put("text", "さくら咲く")
                .put("encoding", "SHIFT-JIS"))
            val sjisRead = call(listener, "read_text", JSONObject().put("path", "script_sjis.ks"))
                .getJSONObject("structuredContent")
            assertEquals("さくら咲く", sjisRead.getString("text"))
            assertEquals("SHIFT-JIS", sjisRead.getString("encoding"))
            assertFalse(sjisRead.getBoolean("bom"))

            // Write UTF-16LE with BOM, then overwrite in place with encoding="auto" (must preserve UTF-16 + BOM).
            call(listener, "write_text", JSONObject()
                .put("path", "utf16.ks")
                .put("text", "第一話")
                .put("encoding", "UTF-16")
                .put("bom", true))
            val rawBefore = root.resolve("utf16.ks").readBytes()
            assertEquals(0xFF.toByte(), rawBefore[0])
            assertEquals(0xFE.toByte(), rawBefore[1])

            call(listener, "write_text", JSONObject()
                .put("path", "utf16.ks")
                .put("text", "第二話・改")
                .put("overwrite", true))
            val utf16Read = call(listener, "read_text", JSONObject()
                .put("path", "utf16.ks")
                .put("offset", 0)
                .put("limit", 3)).getJSONObject("structuredContent")
            assertEquals("UTF-16", utf16Read.getString("encoding"))
            assertTrue(utf16Read.getBoolean("bom"))
            assertEquals("第二話", utf16Read.getString("text"))
            assertTrue(utf16Read.getBoolean("truncated"))
            assertEquals(3, utf16Read.getInt("nextOffset"))
            assertEquals(5, utf16Read.getInt("totalChars"))
        }
    }

    @Test fun archiveListPaginatesEntriesAndReportsFormatAndEncryption() {
        val root = tmp.newFolder()
        // Write real XP3 11-byte magic so magic sniffing identifies it even with a generic name.
        root.resolve("data.xp3").writeBytes(
            byteArrayOf(0x58, 0x50, 0x33, 0x0D, 0x0A, 0x20, 0x0A, 0x1A, 0x8B.toByte(), 0x67, 0x01)
        )
        val workspace = McpWorkspace(root)
        val tasks = McpTasks { _, _ -> }
        val protocol = McpProtocol(workspace, tasks, "test", archiveLister = { fmt, _, _ ->
            assertEquals("xp3", fmt)
            """[{"n":"a.ks","s":120,"d":false,"e":true},{"n":"b.ks","s":280,"d":false,"e":true}]""" to "cxdec:TestScheme"
        })
        McpListener(protocol, tasks, root).use { listener ->
            val listed = call(listener, "archive_list", JSONObject().put("path", "data.xp3"))
                .getJSONObject("structuredContent")
            assertEquals("xp3", listed.getString("format"))
            assertEquals("cxdec:TestScheme", listed.getString("encryption"))
            assertEquals(2, listed.getInt("totalEntries"))
            assertEquals(400L, listed.getLong("totalBytes"))
            val page2 = call(listener, "archive_list", JSONObject().put("path", "data.xp3").put("after", "a.ks"))
                .getJSONObject("structuredContent").getJSONArray("entries")
            assertEquals(1, page2.length())
            assertEquals("b.ks", page2.getJSONObject(0).getString("name"))
        }
    }

    @Test fun rejectsMissingTokenAndBrowserOrigin() {
        val root = tmp.newFolder()
        val tasks = McpTasks { _, _ -> fail("Unauthorised request must not run a task") }
        McpListener(McpProtocol(McpWorkspace(root), tasks, "test"), tasks, root).use { listener ->
            val endpoint = URI(listener.url)
            assertEquals(401, request(endpoint, "", "tools/list").first)
            assertEquals(403, request(endpoint, listener.token, "tools/list", origin = "https://example.test").first)
        }
    }

    @Test fun cancellingQueuedTaskIsTerminalAndStopsLaterScriptSteps() {
        val entered = CountDownLatch(1)
        val unblocked = CountDownLatch(1)
        val first = McpCommand.parse(listOf("cp", "input", "first"))
        val second = McpCommand.parse(listOf("cp", "input", "second"))
        val tasks = McpTasks { task, command ->
            check(command == first)
            entered.countDown()
            check(unblocked.await(3, TimeUnit.SECONDS))
            task.checkCancelled()
            throw McpInputException("First step failed")
        }
        tasks.use {
            val firstId = tasks.submit(listOf(first, second), script = true).getString("taskId")
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val queuedId = tasks.submit(listOf(second), script = false).getString("taskId")
            assertEquals("CANCELLED", tasks.cancel(queuedId).getString("state"))
            unblocked.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!tasks.get(firstId).done() && System.nanoTime() < deadline) Thread.yield()
            assertEquals("FAILED", tasks.get(firstId).snapshot().getString("state"))
            assertEquals(0, tasks.get(firstId).snapshot().getInt("completedSteps"))
        }
    }

    @Test fun workspaceRejectsSymlinksAndScriptRejectsShellCommands() {
        val workspace = McpWorkspace(tmp.newFolder())
        val outside = tmp.newFolder()
        Files.createSymbolicLink(workspace.root.resolve("link").toPath(), outside.toPath())
        assertThrows(McpInputException::class.java) { workspace.resolve("link/sub") }
        assertThrows(McpInputException::class.java) { workspace.resolveFrom(workspace.root, "../outside") }
        assertThrows(McpInputException::class.java) { McpCommand.script("sh -c \"id\"") }
        assertThrows(McpInputException::class.java) { McpCommand.script("rm -rf /") }
        assertThrows(McpInputException::class.java) { McpCommand.script("uu unknown_cmd a b") }
        // Real UUT constructs (chaining, set, if/for) are accepted.
        assertEquals(1, McpCommand.script("uu cp a b && uu cp c d").size)
        assertEquals(2, McpCommand.script("set f = test.zip\nuu x \$f out").size)
    }

    @Test fun defaultAndRootSelectionStayInsideDedicatedWorkspace() {
        val storage = tmp.newFolder()
        val default = openMcpWorkspace(storage, null)
        assertEquals(storage.canonicalFile.resolve(DEFAULT_MCP_WORKSPACE), default.root)
        assertEquals(default.root, openMcpWorkspace(storage, storage).root)
        val custom = storage.resolve("project").apply { mkdir() }
        assertEquals(custom.canonicalFile, openMcpWorkspace(storage, custom).root)
        assertThrows(McpInputException::class.java) { openMcpWorkspace(storage, tmp.newFolder()) }
    }

    @Test fun lpkPreservesEngineFilename() {
        assertEquals("DATA.LPK", lpkPackedName("DATA.LPK_extracted"))
        assertEquals("folder.lpk", lpkPackedName("folder"))
    }

    private fun awaitTask(listener: McpListener, taskId: String): JSONObject {
        val id = JSONObject().put("taskId", taskId)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        var status: JSONObject
        do {
            status = call(listener, "task_status", id).getJSONObject("structuredContent")
        } while (status.getString("state") in setOf("QUEUED", "RUNNING") && System.nanoTime() < deadline)
        return status
    }

    private fun JSONArray.toStringSet(): Set<String> =
        (0 until length()).map { getString(it) }.toSet()

    private fun call(listener: McpListener, tool: String, args: JSONObject): JSONObject =
        request(URI(listener.url), listener.token, "tools/call",
            JSONObject().put("name", tool).put("arguments", args)).rpcResult()

    private fun Pair<Int, String>.rpcResult(): JSONObject {
        assertEquals(second, 200, first)
        return JSONObject(second).getJSONObject("result")
    }

    private fun request(url: URI, token: String, method: String, params: JSONObject = JSONObject(),
                        origin: String? = null): Pair<Int, String> {
        val payload = JSONObject().put("jsonrpc", "2.0").put("id", 1)
            .put("method", method).put("params", params).toString().toByteArray()
        return Socket(url.host, url.port).use { socket ->
            socket.soTimeout = 3000
            val header = buildString {
                append("POST /mcp HTTP/1.1\r\nHost: ${url.authority}\r\n")
                append("Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n")
                if (token.isNotEmpty()) append("Authorization: Bearer $token\r\n")
                if (origin != null) append("Origin: $origin\r\n")
                append("Content-Length: ${payload.size}\r\n\r\n")
            }
            socket.getOutputStream().write(header.toByteArray())
            socket.getOutputStream().write(payload)
            val response = socket.getInputStream().bufferedReader().readText()
            response.lineSequence().first().split(' ')[1].toInt() to response.substringAfter("\r\n\r\n")
        }
    }
}
