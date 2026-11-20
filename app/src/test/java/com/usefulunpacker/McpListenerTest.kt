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
            val tools = request(endpoint, listener.token, "tools/list").rpcResult().getJSONArray("tools")
            assertTrue((0 until tools.length()).any { tools.getJSONObject(it).getString("name") == "run_uut" })

            val rejected = call(listener, "run_uu", JSONObject().put("argv", JSONArray(listOf("cp", "../outside", "copy"))))
            assertTrue(rejected.getBoolean("isError"))

            call(listener, "write_text", JSONObject().put("path", "in.txt").put("text", "翻訳"))
            call(listener, "write_text", JSONObject().put("path", "run.uut").put("text", "uu cp in.txt out.txt"))
            val job = call(listener, "run_uut", JSONObject().put("path", "run.uut"))
                .getJSONObject("structuredContent")
            val id = JSONObject().put("taskId", job.getString("taskId"))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var state: String
            do {
                state = call(listener, "task_status", id).getJSONObject("structuredContent").getString("state")
            } while (state in setOf("QUEUED", "RUNNING") && System.nanoTime() < deadline)
            assertEquals("SUCCESS", state)
            assertEquals("翻訳", root.resolve("out.txt").readText())
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

    @Test fun workspaceRejectsSymlinksAndScriptRejectsShellSyntax() {
        val workspace = McpWorkspace(tmp.newFolder())
        val outside = tmp.newFolder()
        Files.createSymbolicLink(workspace.root.resolve("link").toPath(), outside.toPath())
        assertThrows(McpInputException::class.java) { workspace.resolve("link/sub") }
        assertThrows(McpInputException::class.java) { McpCommand.script("uu cp a b && uu cp c d") }
        assertThrows(McpInputException::class.java) { McpCommand.script("set f = test\nuu x \$f out") }
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
