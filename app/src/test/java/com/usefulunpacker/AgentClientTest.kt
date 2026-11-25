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
    @Test fun approvalDisplaysInvisiblePathCharactersAndPreservesJsonLayout() {
        val supplementary = String(Character.toChars(0xE0001))
        assertEquals("dir\\u000a\\u202e\\u{e0001}/档案", visibleAgentText("dir\n\u202e$supplementary/档案"))
        assertEquals("{\n  \"path\": \"目录\\u2028文件\"\n}",
            visibleAgentText("{\n  \"path\": \"目录\u2028文件\"\n}", keepNewlines = true))
    }

    @Test fun onlyPermittedMetadataCallsAreDispatched() {
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
            val message = if (request.getJSONArray("messages").length() == 2) {
                JSONObject().put("tool_calls", JSONArray()
                    .put(call("a", "SCAN", JSONObject().put("path", "sample.bin").put("carveHitIndex", 0)))
                    .put(call("b", "WRITE_TEXT", JSONObject().put("path", "target.txt")))
                    .put(call("c", "WORKSPACE_LIST", JSONObject().put("path", "."))))
            } else JSONObject().put("content", "Listed one archive.")
            Reply("200 OK", JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message))))
        }.use { server ->
            val endpoint = checkNotNull(agentEndpoint("http://127.0.0.1:${server.port}/model/v1"))
            val events = mutableListOf<AgentToolEvent>()
            val result = AgentClient(endpoint, "test-model", "", AgentRequest())
                .complete(emptyList(), "List my archives", runtime, onTool = events::add)
            assertEquals("Listed one archive.", result)
            assertEquals(listOf("WORKSPACE_LIST"), requested)
            assertEquals(2, events.count { it.failure != null })
            assertFalse(requests.remove().getJSONArray("tools").toString().contains("WRITE_TEXT"))
            val messages = requests.remove().getJSONArray("messages").toString()
            assertTrue(messages.contains("archive.zip"))
            assertTrue(messages.contains("TOOL_NOT_ALLOWED"))
            assertFalse(messages.contains("private-content-marker"))
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

    @Test fun permissionsAreTieredAndDecliningAnOperationStopsTheModelLoop() {
        val root = java.nio.file.Files.createTempDirectory("uu-policy-test").toFile()
        try {
            McpTasks { _, _ -> error("Declined commands must not reach the worker") }.use { tasks ->
                val runtime = WorkspaceSkillRuntime(McpWorkspace(root), tasks)
                val skills = runtime.skills()
                val allowed = AgentPermission.entries.map { it.tools(skills).keys }
                assertEquals(setOf("WORKSPACE_LIST", "ARCHIVE_LIST", "SCAN"), allowed[0])
                assertEquals(setOf("COMPRESS", "EXTRACT"), allowed[1] - allowed[0])
                assertEquals(setOf("WRITE_TEXT", "DELETE_FILE"), allowed[2] - allowed[1])
                assertEquals(setOf("RUN_UU", "RUN_UUT"), allowed[3] - allowed[2])
                assertFalse(AgentPermission.FULL_LOCAL.tools(skills).getValue("SCAN")
                    .accepts(JSONObject().put("path", "sample.bin").put("carveHitIndex", 0)))
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
