package com.usefulunpacker

import com.usefulunpacker.skills.AppSkillDefinition
import com.usefulunpacker.skills.AppSkillResult
import com.usefulunpacker.skills.AppSkillRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import kotlin.concurrent.thread

class AgentClientTest {
    @Test fun catalogCoversEveryRegisteredReadAndPackFormat() {
        val documents = AgentReferenceDocument.entries
        assertTrue(documents.size > 50)
        assertEquals(FORMAT_LABELS.keys, documents.mapNotNull { it.readFormat }.toSet())
        assertEquals(COMPRESS_EXT.keys, documents.mapNotNull { it.packFormat }.toSet())
        assertEquals(documents.size, documents.map { it.id }.toSet().size)
        assertTrue(documents.all { it.readText().startsWith("# ") })
        assertTrue(AgentReferenceDocument.PARTIAL_XOR in AgentReferenceDocument.search("部分XOR"))
        assertTrue(AgentReferenceDocument.BYTE_OFFSETS in AgentReferenceDocument.search("hex offset"))
    }

    @Test fun cancellationReturnsWhileTheConnectionIsClosingAndClosesItOnce() {
        val closing = java.util.concurrent.CountDownLatch(1)
        val allowClose = java.util.concurrent.CountDownLatch(1)
        val cancelReturned = java.util.concurrent.CountDownLatch(1)
        val closes = java.util.concurrent.atomic.AtomicInteger()
        val endpoint = java.net.URL(null, "http://test.invalid", object : java.net.URLStreamHandler() {
            override fun openConnection(url: java.net.URL): java.net.URLConnection =
                object : java.net.HttpURLConnection(url) {
                    override fun connect() = Unit
                    override fun usingProxy() = false
                    override fun disconnect() {
                        closes.incrementAndGet()
                        closing.countDown()
                        allowClose.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    }
                }

            override fun openConnection(url: java.net.URL, proxy: java.net.Proxy): java.net.URLConnection =
                openConnection(url)
        })
        val request = AgentRequest()
        val connection = request.connect(endpoint)
        val caller = thread {
            request.cancel()
            cancelReturned.countDown()
        }
        try {
            assertTrue(closing.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(cancelReturned.await(3, java.util.concurrent.TimeUnit.SECONDS))
            request.close(connection)
            assertEquals(1, closes.get())
            assertThrows(java.util.concurrent.CancellationException::class.java) { request.checkActive() }
        } finally {
            allowClose.countDown()
            caller.join(3000)
        }
    }

    @Test fun modelConnectionProbeSendsOnePromptWithoutToolsAndReportsHttpStatus() {
        val requests = java.util.concurrent.LinkedBlockingQueue<JSONObject>()
        ModelServer { path, request ->
            if (path != "/v1/chat/completions") return@ModelServer Reply("404 Not Found")
            requests.add(request)
            if (request.getString("model") == "blocked") Reply("403 Forbidden")
            else Reply("200 OK", JSONObject().put("choices", JSONArray().put(
                JSONObject().put("message", JSONObject().put("content", "OK")))))
        }.use { server ->
            val endpoint = checkNotNull(agentEndpoint("http://127.0.0.1:${server.port}/v1"))
            assertTrue(AgentClient(endpoint, "probe", "", AgentRequest()).testConnection() >= 0L)
            val probe = requests.remove()
            assertEquals("probe", probe.getString("model"))
            assertFalse(probe.has("tools"))
            assertFalse(probe.getBoolean("stream"))
            assertEquals(1, probe.getJSONArray("messages").length())
            assertEquals("Reply only OK.", probe.getJSONArray("messages").getJSONObject(0).getString("content"))
            val rejected = assertThrows(AgentClientException::class.java) {
                AgentClient(endpoint, "blocked", "", AgentRequest()).testConnection()
            }
            assertEquals(403, rejected.httpStatus)
            assertEquals(1, requests.size)
        }
    }

    @Test fun approvalDisplaysInvisiblePathCharactersAndPreservesJsonLayout() {
        val supplementary = String(Character.toChars(0xE0001))
        assertEquals("dir\\u000a\\u202e\\u{e0001}/档案", visibleAgentText("dir\n\u202e$supplementary/档案"))
        assertEquals("{\n  \"path\": \"目录\\u2028文件\"\n}",
            visibleAgentText("{\n  \"path\": \"目录\u2028文件\"\n}", keepNewlines = true))
    }

    @Test fun onlyPermittedMetadataCallsAreDispatchedAndOnlyFirstRequestIncludesReference() {
        val requested = mutableListOf<String>()
        val runtime = object : AppSkillRuntime {
            override fun skills() = listOf("WORKSPACE_LIST", "SCAN", "WRITE_TEXT").map {
                AppSkillDefinition(it, it, JSONObject())
            }

            override fun invokeSkill(name: String, input: JSONObject): AppSkillResult {
                requested.add(name)
                assertEquals(".", input.getString("path"))
                return AppSkillResult(JSONObject().put("entries", JSONArray().put(
                    JSONObject().put("name", "archive.zip").put("directory", false)
                        .put("text", "private-content-marker")
                )))
            }
        }
        fun call(id: String, name: String, args: JSONObject) = JSONObject()
            .put("id", id).put("type", "function").put("function",
                JSONObject().put("name", name).put("arguments", args.toString()))
        val requests = java.util.concurrent.LinkedBlockingQueue<JSONObject>()
        ModelServer { path, request ->
            if (path != "/model/v1/chat/completions") return@ModelServer Reply("404 Not Found")
            requests.add(request)
            val message = if (request.getJSONArray("messages").length() == 3) {
                JSONObject().put("reasoning_content", "List the workspace first.").put("tool_calls", JSONArray()
                    .put(call("a", "SCAN", JSONObject().put("path", "sample.bin").put("carveHitIndex", 0)))
                    .put(call("b", "WRITE_TEXT", JSONObject().put("path", "target.txt")))
                    .put(call("c", "WORKSPACE_LIST", JSONObject().put("path", "."))))
            } else JSONObject().put("content", "Listed one archive.")
            Reply("200 OK", JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message))))
        }.use { server ->
            val endpoint = checkNotNull(agentEndpoint("http://127.0.0.1:${server.port}/model/v1"))
            val events = mutableListOf<AgentToolEvent>()
            val reasoning = mutableListOf<String>()
            val selected = AgentReferenceDocument.ARCHIVES
            var referenceDeliveries = 0
            val result = AgentClient(endpoint, "test-model", "", AgentRequest())
                .complete(emptyList(), "List my archives", runtime, reference = selected,
                    onReferenceDelivered = { referenceDeliveries++ }, onReasoning = reasoning::add, onTool = events::add)
            assertEquals("Listed one archive.", result)
            assertEquals(1, referenceDeliveries)
            assertEquals(listOf("List the workspace first."), reasoning)
            assertEquals(listOf("WORKSPACE_LIST"), requested)
            val workspaceEvents = events.filter { it.name == "WORKSPACE_LIST" }
            assertEquals(listOf(AgentToolStage.RUNNING, AgentToolStage.SUCCESS), workspaceEvents.map { it.stage })
            assertEquals(listOf(2), workspaceEvents.map { it.callIndex }.distinct())
            assertEquals(1, workspaceEvents.last().resultCount)
            assertEquals(2, events.count { it.failure != null })
            val first = requests.remove()
            val initial = first.getJSONArray("messages")
            assertEquals(3, initial.length())
            assertTrue(initial.getJSONObject(1).getString("content").endsWith(selected.readText()))
            for (other in AgentReferenceDocument.entries.filter { it != selected }) {
                assertFalse(initial.getJSONObject(1).getString("content").contains(other.readText().lineSequence().first()))
            }
            assertFalse(first.getJSONArray("tools").toString().contains("WRITE_TEXT"))
            val messages = requests.remove().getJSONArray("messages").toString()
            assertFalse(messages.contains(selected.readText().lineSequence().first()))
            assertTrue(messages.contains("archive.zip"))
            assertTrue(messages.contains("List the workspace first."))
            assertTrue(messages.contains("TOOL_NOT_ALLOWED"))
            assertFalse(messages.contains("private-content-marker"))
        }
    }

    @Test fun referencesAreOptInAndRejectedRequestsDoNotConsumeTheSelection() {
        val runtime = object : AppSkillRuntime {
            override fun skills() = emptyList<AppSkillDefinition>()
            override fun invokeSkill(name: String, input: JSONObject): AppSkillResult = error("Unexpected tool call")
        }
        val requests = java.util.concurrent.LinkedBlockingQueue<JSONObject>()
        ModelServer { _, request ->
            requests.add(request)
            if (request.getString("model") == "blocked") Reply("403 Forbidden")
            else Reply("200 OK", JSONObject().put("choices", JSONArray().put(
                JSONObject().put("message", JSONObject().put("content", "OK")))))
        }.use { server ->
            val endpoint = checkNotNull(agentEndpoint("http://127.0.0.1:${server.port}/v1"))
            val result = AgentClient(endpoint, "model", "", AgentRequest())
                .complete(emptyList(), "Hello", runtime, context = "Standing preferences", onTool = {})
            assertEquals("OK", result)
            val messages = requests.remove().getJSONArray("messages")
            assertEquals(3, messages.length())
            assertTrue(messages.getJSONObject(1).getString("content").endsWith("Standing preferences"))
            AgentReferenceDocument.entries.forEach {
                assertFalse(messages.toString().contains(it.readText().lineSequence().first()))
            }
            var delivered = false
            val selected = AgentReferenceDocument.RENPY
            assertThrows(AgentClientException::class.java) {
                AgentClient(endpoint, "blocked", "", AgentRequest()).complete(emptyList(), "Hello", runtime,
                    reference = selected, onReferenceDelivered = { delivered = true }, onTool = {})
            }
            assertTrue(requests.remove().getJSONArray("messages").getJSONObject(1).getString("content")
                .endsWith(selected.readText()))
            assertFalse(delivered)
        }
    }

    @Test fun unresolvedIssueCanFetchOneGuideWithoutReplayingItsText() {
        val guide = AgentReferenceDocument.PARTIAL_XOR
        val text = guide.readText()
        val requests = java.util.concurrent.LinkedBlockingQueue<JSONObject>()
        val calls = mutableListOf<String>()
        val runtime = object : AppSkillRuntime {
            override fun skills() = listOf("REFERENCE_SEARCH", "REFERENCE_READ").map {
                AppSkillDefinition(it, it, JSONObject())
            }

            override fun invokeSkill(name: String, input: JSONObject): AppSkillResult {
                calls.add(name)
                return if (name == "REFERENCE_SEARCH") {
                    assertEquals("partial XOR", input.getString("query"))
                    AppSkillResult(JSONObject().put("documents", JSONArray().put(JSONObject().put("documentId", guide.id))))
                } else {
                    assertEquals(guide.id, input.getString("documentId"))
                    assertTrue(input.getString("reason").isNotBlank())
                    AppSkillResult(JSONObject().put("documentId", guide.id).put("text", text))
                }
            }
        }
        fun toolReply(name: String, input: JSONObject, id: String): JSONObject = JSONObject().put("tool_calls", JSONArray()
            .put(JSONObject().put("id", id).put("type", "function")
                .put("function", JSONObject().put("name", name).put("arguments", input.toString()))))
        ModelServer { _, request ->
            requests.add(request)
            val reply = when (requests.size) {
                1 -> toolReply("REFERENCE_SEARCH", JSONObject().put("query", "partial XOR"), "search")
                2 -> toolReply("REFERENCE_READ", JSONObject().put("documentId", guide.id)
                    .put("reason", "Need to verify the offset boundary"), "read")
                3 -> toolReply("REFERENCE_READ", JSONObject().put("documentId", "xor-alignment")
                    .put("reason", "Try another guide"), "repeat")
                else -> JSONObject().put("content", "Checked the reference.")
            }
            Reply("200 OK", JSONObject().put("choices", JSONArray().put(JSONObject().put("message", reply))))
        }.use { server ->
            val endpoint = checkNotNull(agentEndpoint("http://127.0.0.1:${server.port}/v1"))
            val result = AgentClient(endpoint, "model", "", AgentRequest())
                .complete(emptyList(), "Which range should be decoded?", runtime, onTool = {})
            assertEquals("Checked the reference.", result)
            assertEquals(listOf("REFERENCE_SEARCH", "REFERENCE_READ"), calls)
            val payloads = List(4) { requests.remove().getJSONArray("messages").toString() }
            val heading = text.lineSequence().first()
            assertFalse(payloads[0].contains(heading))
            assertFalse(payloads[1].contains(heading))
            assertTrue(payloads[2].contains(heading))
            assertFalse(payloads[3].contains(heading))
            assertTrue(payloads[3].contains("consulted"))
            assertTrue(payloads[3].contains("REFERENCE_LIMIT"))
        }
    }

    @Test fun endpointRejectsRemoteHttpAndDoesNotFollowRedirects() {
        assertNull(agentEndpoint("http://model.example/v1"))
        assertNull(agentEndpoint("https://model.example/v1?destination=elsewhere"))
        assertNull(agentEndpoint("https://account:secret@model.example/v1"))
        val redirects = java.util.concurrent.atomic.AtomicInteger()
        ModelServer { path, _ ->
            if (path == "/v1/chat/completions") Reply("307 Temporary Redirect", location = "/other")
            else {
                redirects.incrementAndGet()
                Reply("404 Not Found")
            }
        }.use { server ->
            val endpoint = checkNotNull(agentEndpoint("http://127.0.0.1:${server.port}/v1"))
            val runtime = object : AppSkillRuntime {
                override fun skills() = emptyList<AppSkillDefinition>()
                override fun invokeSkill(name: String, input: JSONObject): AppSkillResult = error("Unexpected call")
            }
            val failure = assertThrows(AgentClientException::class.java) {
                AgentClient(endpoint, "test-model", "test-key", AgentRequest())
                    .complete(emptyList(), "List my archives", runtime) {}
            }
            assertEquals("model_http", failure.stage)
            assertEquals(0, redirects.get())
        }
    }

    @Test fun finalModelRoundDoesNotStartANewToolBatch() {
        val root = java.nio.file.Files.createTempDirectory("uu-agent-round-test").toFile()
        try {
            McpTasks { _, _ -> error("No asynchronous jobs expected") }.use { tasks ->
                val runtime = WorkspaceSkillRuntime(McpWorkspace(root), tasks)
                val replies = java.util.concurrent.atomic.AtomicInteger()
                ModelServer { _, _ ->
                    val final = replies.incrementAndGet() == 6
                    val name = if (final) "WRITE_TEXT" else "WORKSPACE_LIST"
                    val args = if (final) """{"path":"late.txt","text":"late write"}""" else """{"path":"."}"""
                    val call = JSONObject().put("id", "tool").put("type", "function").put("function",
                        JSONObject().put("name", name).put("arguments", args))
                    Reply("200 OK", JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
                        JSONObject().put("tool_calls", JSONArray().put(call))))))
                }.use { server ->
                    val endpoint = checkNotNull(agentEndpoint("http://127.0.0.1:${server.port}/v1"))
                    val events = mutableListOf<AgentToolEvent>()
                    val failure = assertThrows(AgentClientException::class.java) {
                        AgentClient(endpoint, "test-model", "", AgentRequest()).complete(
                            emptyList(), "Read the directory", runtime, AgentPermission.EDIT_FILES,
                            confirm = { error("The final round must not ask for approval") }, onTool = events::add,
                        )
                    }
                    assertEquals("model_rounds", failure.stage)
                    assertEquals(6, replies.get())
                    assertEquals((0..4).toList(), events.filter { it.stage == AgentToolStage.SUCCESS }.map { it.callIndex })
                    assertFalse(root.resolve("late.txt").exists())
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun permissionsAreTieredAndDecliningAnOperationStopsTheModelLoop() {
        val root = java.nio.file.Files.createTempDirectory("uu-policy-test").toFile()
        try {
            McpTasks { _, _ -> error("Declined commands must not reach the worker") }.use { tasks ->
                val runtime = WorkspaceSkillRuntime(McpWorkspace(root), tasks)
                val skills = runtime.skills()
                val allowed = AgentPermission.entries.map { it.tools(skills).keys }
                assertEquals(setOf("WORKSPACE_LIST", "ARCHIVE_LIST", "DETECT_GAME_ENGINE", "VN_INSPECT", "SCAN",
                    "REFERENCE_SEARCH", "REFERENCE_READ"), allowed[0])
                assertEquals(setOf("COMPRESS", "EXTRACT"), allowed[1] - allowed[0])
                assertEquals(setOf("WRITE_TEXT", "DELETE_FILE"), allowed[2] - allowed[1])
                assertEquals(setOf("RUN_UU", "RUN_UUT"), allowed[3] - allowed[2])
                assertFalse(AgentPermission.FULL_LOCAL.tools(skills).getValue("SCAN")
                    .accepts(JSONObject().put("path", "sample.bin").put("carveHitIndex", 0)))
                val readReference = AgentPermission.READ_ONLY.tools(skills).getValue("REFERENCE_READ")
                assertFalse(readReference.accepts(JSONObject().put("documentId", "partial-xor")))
                assertFalse(readReference.accepts(JSONObject().put("documentId", "partial-xor")
                    .put("reason", "Check the byte range").put("path", "private.txt")))
                val requests = java.util.concurrent.LinkedBlockingQueue<JSONObject>()
                ModelServer { _, request ->
                    requests.add(request)
                    val call = JSONObject().put("id", "delete").put("type", "function").put("function",
                        JSONObject().put("name", "DELETE_FILE").put("arguments", """{"path":"record.txt"}"""))
                    Reply("200 OK", JSONObject().put("choices", JSONArray().put(JSONObject()
                        .put("message", JSONObject().put("tool_calls", JSONArray().put(call))))))
                }.use { server ->
                    val endpoint = checkNotNull(agentEndpoint("http://127.0.0.1:${server.port}/v1"))
                    val approvals = mutableListOf<AgentApproval>()
                    assertThrows(java.util.concurrent.CancellationException::class.java) {
                        AgentClient(endpoint, "test-model", "", AgentRequest()).complete(
                            emptyList(), "Clean up", runtime, AgentPermission.EDIT_FILES,
                            context = "Keep project assets intact.",
                            confirm = { approvals.add(it); false },
                            deleteFile = { error("Deletion must not run") },
                        ) {}
                    }
                    assertEquals("record.txt", approvals.single().input.getString("path"))
                    assertEquals(1, requests.size)
                    assertTrue(requests.remove().getJSONArray("messages").getJSONObject(1).getString("content")
                        .contains("Keep project assets intact."))
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private data class Reply(val status: String, val body: JSONObject? = null, val location: String? = null)

    private class ModelServer(reply: (String, JSONObject) -> Reply) : AutoCloseable {
        private val listening = ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val port get() = listening.localPort

        private val worker = thread(isDaemon = true) {
            while (!listening.isClosed) {
                val socket = try { listening.accept() } catch (_: SocketException) { break }
                socket.use {
                    it.soTimeout = 5000
                    val input = BufferedInputStream(it.getInputStream())
                    val path = input.readLineAscii().split(' ')[1]
                    var length = 0
                    while (true) {
                        val header = input.readLineAscii()
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", ignoreCase = true)) {
                            length = header.substringAfter(':').trim().toInt()
                        }
                    }
                    val requestBytes = ByteArray(length)
                    java.io.DataInputStream(input).readFully(requestBytes)
                    val request = if (length == 0) JSONObject() else JSONObject(String(requestBytes, Charsets.UTF_8))
                    val response = reply(path, request)
                    val body = response.body?.toString()?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
                    val output = it.getOutputStream()
                    val headers = buildString {
                        append("HTTP/1.1 ${response.status}\r\nConnection: close\r\n")
                        append("Content-Type: application/json\r\nContent-Length: ${body.size}\r\n")
                        response.location?.let { target -> append("Location: $target\r\n") }
                        append("\r\n")
                    }
                    output.write(headers.toByteArray(Charsets.US_ASCII))
                    output.write(body)
                }
            }
        }

        override fun close() {
            listening.close()
            worker.join(1000)
        }

        private fun BufferedInputStream.readLineAscii(): String = buildString {
            while (true) {
                val byte = read()
                if (byte < 0 || byte == '\n'.code) break
                if (byte != '\r'.code) append(byte.toChar())
            }
        }
    }
}
