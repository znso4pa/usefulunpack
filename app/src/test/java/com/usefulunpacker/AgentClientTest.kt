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
    @Test fun taskModesNarrowSchemasAndRejectUnlistedCallsBeforeRuntime() {
        val runtime = object : AppSkillRuntime {
            override fun skills() = listOf(AppSkillDefinition("WORKSPACE_LIST", "List", JSONObject()),
                AppSkillDefinition("DETECT_GAME_ENGINE", "Engine", JSONObject()))
            override fun invokeSkill(name: String, input: JSONObject): AppSkillResult = error("No call should run")
        }
        val requests = java.util.concurrent.LinkedBlockingQueue<JSONObject>()
        ModelServer { _, body ->
            requests.add(body)
            val call = JSONObject().put("id", "denied").put("type", "function").put("function",
                JSONObject().put("name", "DETECT_GAME_ENGINE").put("arguments", """{"path":"."}"""))
            val message = if (requests.size == 1) JSONObject().put("tool_calls", JSONArray().put(call))
                else JSONObject().put("content", "Done")
            Reply("200 OK", JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message))))
        }.use { server ->
            val endpoint = checkNotNull(agentEndpoint("http://127.0.0.1:${server.port}/v1"))
            val result = AgentClient(endpoint, "model", "", AgentRequest())
                .complete(emptyList(), "Check the workspace", runtime, mode = AgentTaskMode.ARCHIVES,
                    onTool = {})
            assertEquals("Done", result)
            val schemas = requests.remove().getJSONArray("tools")
            assertEquals(1, schemas.length())
            assertEquals("WORKSPACE_LIST", schemas.getJSONObject(0).getJSONObject("function").getString("name"))
            assertTrue(requests.remove().getJSONArray("messages").toString().contains("TOOL_NOT_ALLOWED"))
        }
    }
    @Test fun singleFactorWireAcceptanceMatrix() {
        val baseline = WireInput()
        val shortHistory = listOf(AgentClient.message("user", "Preserve script tags"),
            AgentClient.message("assistant", "I will preserve them"))
        val oldAnswer = AgentClient.message("assistant", "OK")
        val historyBoundary = 12 * 1024 - AgentClient.message("user", "").bytes() - oldAnswer.bytes()
        fun historyAt(size: Int) = listOf(AgentClient.message("user", "A".repeat(size)), oldAnswer)
        val list = baseline.copy(batches = listOf(listOf(readCall("."))))
        val write = baseline.copy(permission = AgentPermission.EDIT_FILES, batches = listOf(listOf(
            WireCall("DELETE_FILE", JSONObject().put("path", "discard.txt")))))
        val fixtureSecret = "fixture-authentication-marker"
        val pairs = listOf(
            WirePair("output_limit", baseline.copy(budget = 2048), baseline) { a, b ->
                assertEquals(2048, a.first.getInt("max_tokens"))
                assertEquals(4096, b.first.getInt("max_tokens"))
                assertEquals(a.first.getJSONArray("messages").toString(), b.first.getJSONArray("messages").toString())
            },
            WirePair("history_pair", baseline, baseline.copy(history = shortHistory)) { a, b ->
                assertEquals(2, a.first.getJSONArray("messages").length())
                assertEquals(4, b.first.getJSONArray("messages").length())
            },
            WirePair("history_boundary", baseline.copy(history = historyAt(historyBoundary)),
                baseline.copy(history = historyAt(historyBoundary + 1))) { a, b ->
                assertEquals(4, a.first.getJSONArray("messages").length())
                assertEquals(2, b.first.getJSONArray("messages").length())
            },
            WirePair("standing_context", baseline, baseline.copy(context = "X".repeat(1024))) { a, b ->
                assertEquals(3, b.first.getJSONArray("messages").length())
                assertTrue(b.first.bytes() - a.first.bytes() > 1024)
            },
            WirePair("utf8_bytes", baseline.copy(context = "A".repeat(256)),
                baseline.copy(context = "字".repeat(256))) { a, b ->
                assertEquals(512, b.first.bytes() - a.first.bytes())
            },
            WirePair("attached_guide", list, list.copy(guide = AgentReferenceDocument.RENPY)) { a, b ->
                assertTrue(b.first.bytes() > a.first.bytes())
                assertEquals(a.bodies[1].bytes(), b.bodies[1].bytes())
                assertEquals(a.bodies[1].getJSONArray("messages").toString(), b.bodies[1].getJSONArray("messages").toString())
            },
            WirePair("permission", baseline.copy(permission = AgentPermission.EDIT_FILES),
                baseline.copy(permission = AgentPermission.FULL_LOCAL)) { a, b ->
                assertEquals(11, a.first.getJSONArray("tools").length())
                assertEquals(13, b.first.getJSONArray("tools").length())
                assertFalse(a.first.getJSONArray("tools").toString().contains("RUN_UUT"))
                assertTrue(b.first.getJSONArray("tools").toString().contains("RUN_UUT"))
            },
            WirePair("listing_page", list.copy(entries = 24), list.copy(entries = 25)) { a, b ->
                assertEquals(24, a.receipt.getJSONArray("entries").length())
                assertEquals(24, b.receipt.getJSONArray("entries").length())
                assertFalse(a.receipt.has("nextAfter"))
                assertEquals("file_24.txt", b.receipt.getString("nextAfter"))
            },
            WirePair("duplicate_read", list.copy(batches = list.batches + listOf(listOf(readCall(".")))),
                list.copy(batches = list.batches + listOf(listOf(readCall("different"))))) { a, b ->
                assertEquals(3, a.bodies.size)
                assertEquals(3, b.bodies.size)
                assertEquals(1, a.invocations)
                assertEquals(2, b.invocations)
                assertEquals("call-0-0", a.receipt.getString("reusedFrom"))
            },
            WirePair("tool_rounds", baseline, baseline.copy(batches = listOf(listOf(readCall(".")), listOf(readCall("different"))))) { a, b ->
                assertEquals(a.first.bytes(), b.first.bytes())
                assertEquals(1, a.bodies.size)
                assertEquals(3, b.bodies.size)
                assertTrue(b.bodies[2].bytes() > b.bodies[1].bytes())
            },
            WirePair("approval", write, write.copy(allowWrite = true)) { a, b ->
                assertEquals("cancelled", a.outcome)
                assertEquals(1, a.bodies.size)
                assertEquals(0, a.invocations)
                assertEquals("ok", b.outcome)
                assertEquals(2, b.bodies.size)
                assertEquals(1, b.invocations)
            },
            WirePair("finish_reason", baseline, baseline.copy(finishReason = "length")) { a, b ->
                assertEquals("ok", a.outcome)
                assertEquals("model_budget", b.outcome)
                assertEquals(1, b.usage.size)
            },
            WirePair("batch_cap", baseline.copy(batches = listOf(List(4) { readCall(".") })),
                baseline.copy(batches = listOf(List(5) { readCall(".") }))) { a, b ->
                assertEquals("ok", a.outcome)
                assertEquals("model_response", b.outcome)
                assertEquals(0, b.invocations)
            },
            WirePair("secret_in_result", list.copy(filename = "plain", secret = fixtureSecret),
                list.copy(filename = fixtureSecret, secret = fixtureSecret)) { a, b ->
                assertFalse(a.bodies.any { fixtureSecret in it.toString() })
                assertFalse(b.bodies.any { fixtureSecret in it.toString() })
                assertEquals("[redacted]", b.receipt.getJSONArray("entries").getJSONObject(0).getString("name"))
            },
            WirePair("http_status", baseline, baseline.copy(status = "503 Service Unavailable")) { a, b ->
                assertEquals("ok", a.outcome)
                assertEquals("model_http", b.outcome)
                assertTrue(b.usage.isEmpty())
                assertEquals(1, b.bodies.size)
            },
            WirePair("usage_presence", baseline, baseline.copy(usageIncluded = false)) { a, b ->
                assertEquals(1100L, a.usage.single().input)
                assertNull(b.usage.single().input)
                assertNull(b.usage.single().cachedInput)
            },
        )
        val repeatDeltas = mutableMapOf<String, List<Int>>()
        for (repetition in 1..3) {
            for (pair in pairs) {
                val prompt = "Check the synthetic fixture $repetition."
                val a: WireResult
                val b: WireResult
                if (repetition % 2 == 1) {
                    a = recordWire(pair.a, prompt)
                    b = recordWire(pair.b, prompt)
                } else {
                    b = recordWire(pair.b, prompt)
                    a = recordWire(pair.a, prompt)
                }
                pair.verify(a, b)
                val totals = listOf(a.first.bytes(), b.first.bytes(), a.totalBytes, b.totalBytes,
                    a.bodies.size, b.bodies.size, a.invocations, b.invocations)
                val previous = repeatDeltas.putIfAbsent(pair.factor, totals)
                if (previous != null) assertEquals("Repeatability for ${pair.factor}", previous, totals)
                println("WIRE_MATRIX,${pair.factor},$repetition,${totals.joinToString(",")},${a.outcome},${b.outcome}")
            }
        }
    }

    private data class WireCall(val name: String, val args: JSONObject)
    private fun readCall(path: String) = WireCall("WORKSPACE_LIST", JSONObject().put("path", path))

    private data class WireInput(
        val budget: Int = 4096,
        val history: List<JSONObject> = emptyList(),
        val context: String = "",
        val guide: AgentReferenceDocument? = null,
        val permission: AgentPermission = AgentPermission.READ_ONLY,
        val batches: List<List<WireCall>> = emptyList(),
        val entries: Int = 1,
        val allowWrite: Boolean = false,
        val finishReason: String = "stop",
        val status: String = "200 OK",
        val usageIncluded: Boolean = true,
        val filename: String? = null,
        val secret: String = "",
    )

    private data class WirePair(
        val factor: String, val a: WireInput, val b: WireInput, val verify: (WireResult, WireResult) -> Unit,
    )

    private data class WireResult(
        val bodies: List<JSONObject>, val usage: List<AgentModelUsage>, val invocations: Int, val outcome: String,
    ) {
        val first: JSONObject get() = bodies.first()
        val totalBytes: Int get() = bodies.sumOf { it.toString().toByteArray(Charsets.UTF_8).size }
        val receipt: JSONObject get() {
            val messages = bodies.last().getJSONArray("messages")
            return JSONObject(messages.getJSONObject(messages.length() - 1).getString("content"))
        }
    }

    private fun JSONObject.bytes() = toString().toByteArray(Charsets.UTF_8).size

    private fun recordWire(input: WireInput, prompt: String): WireResult {
        val root = java.nio.file.Files.createTempDirectory("uu-wire-matrix").toFile()
        val bodies = java.util.concurrent.LinkedBlockingQueue<JSONObject>()
        val usage = mutableListOf<AgentModelUsage>()
        var invocations = 0
        try {
            McpTasks { _, _ -> error("No local jobs should run in the wire matrix") }.use { tasks ->
                val definitions = WorkspaceSkillRuntime(McpWorkspace(root), tasks).skills()
                val runtime = object : AppSkillRuntime {
                    override fun skills() = definitions
                    override fun invokeSkill(name: String, inputArgs: JSONObject): AppSkillResult {
                        assertEquals("WORKSPACE_LIST", name)
                        invocations++
                        val entries = JSONArray(List(input.entries) { index -> JSONObject()
                            .put("name", input.filename ?: "file_${index + 1}.txt").put("directory", false) })
                        return AppSkillResult(JSONObject().put("entries", entries))
                    }
                }
                ModelServer { _, body ->
                    bodies.add(body)
                    val round = bodies.size - 1
                    val batch = input.batches.getOrNull(round)
                    val message = if (batch == null) JSONObject().put("content", "OK") else JSONObject()
                        .put("tool_calls", JSONArray(batch.mapIndexed { index, call -> JSONObject()
                            .put("id", "call-$round-$index").put("type", "function").put("function", JSONObject()
                                .put("name", call.name).put("arguments", call.args.toString())) }))
                    val response = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message)
                        .put("finish_reason", if (batch == null) input.finishReason else "tool_calls")))
                    if (input.usageIncluded) response.put("usage", JSONObject().put("prompt_tokens", 1100)
                        .put("completion_tokens", 17).put("prompt_cache_hit_tokens", 128))
                    Reply(input.status, response)
                }.use { server ->
                    val endpoint = checkNotNull(agentEndpoint("http://127.0.0.1:${server.port}/v1"))
                    val outcome = try {
                        val result = AgentClient(endpoint, "matrix-model", input.secret, AgentRequest()).complete(
                            input.history, prompt, runtime, input.permission, context = input.context, reference = input.guide,
                            maxOutputTokens = input.budget, confirm = { input.allowWrite },
                            deleteFile = { invocations++; AppSkillResult(JSONObject().put("state", "SUCCESS")) },
                            onUsage = { usage.add(it) }, onTool = {},
                        )
                        assertEquals("OK", result)
                        "ok"
                    } catch (e: AgentClientException) {
                        e.stage
                    } catch (_: java.util.concurrent.CancellationException) {
                        "cancelled"
                    }
                    return WireResult(bodies.toList(), usage, invocations, outcome)
                }
            }
        } finally {
            assertTrue(root.deleteRecursively())
        }
    }

    @Test fun reasoningAndToolEventsKeepTheirResponseOrder() {
        val runtime = object : AppSkillRuntime {
            override fun skills() = listOf(AppSkillDefinition("WORKSPACE_LIST", "List", JSONObject()))
            override fun invokeSkill(name: String, input: JSONObject) =
                AppSkillResult(JSONObject().put("entries", JSONArray()))
        }
        var requestCount = 0
        ModelServer { _, _ ->
            val round = ++requestCount
            val message = JSONObject().put("reasoning_content", "reasoning $round")
            if (round < 3) {
                val function = JSONObject().put("name", "WORKSPACE_LIST")
                    .put("arguments", JSONObject().put("path", "dir$round").toString())
                message.put("tool_calls", JSONArray().put(JSONObject()
                    .put("id", "call$round").put("type", "function").put("function", function)))
            } else {
                message.put("content", "Done")
            }
            Reply("200 OK", JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message))))
        }.use { server ->
            val endpoint = checkNotNull(agentEndpoint("http://127.0.0.1:${server.port}/v1"))
            val events = mutableListOf<String>()
            val result = AgentClient(endpoint, "test-model", "", AgentRequest())
                .complete(emptyList(), "Compare two directories", runtime, onReasoning = { events.add(it) }) {
                    events.add("${it.callIndex}:${it.stage.name}")
                }
            assertEquals("Done", result)
            assertEquals(listOf("reasoning 1", "0:RUNNING", "0:SUCCESS", "reasoning 2",
                "1:RUNNING", "1:SUCCESS", "reasoning 3"), events)
        }
    }

    @Test fun requestFootprintMatrixKeepsStablePrefixAndBoundsHistoryAndTools() {
        val runtime = object : AppSkillRuntime {
            override fun skills() = AgentReadTool.entries.map {
                AppSkillDefinition(it.name, it.description, JSONObject())
            }
            override fun invokeSkill(name: String, input: JSONObject): AppSkillResult = error("Unexpected tool call")
        }
        val recorded = java.util.concurrent.LinkedBlockingQueue<JSONObject>()
        ModelServer { _, body ->
            recorded.add(body)
            Reply("200 OK", JSONObject().put("choices", JSONArray().put(
                JSONObject().put("message", JSONObject().put("content", "OK")))))
        }.use { server ->
            val endpoint = checkNotNull(agentEndpoint("http://127.0.0.1:${server.port}/v1"))
            val history = listOf(
                AgentClient.message("user", "exclude-old-" + "旧".repeat(5000)),
                AgentClient.message("assistant", "old answer"),
                AgentClient.message("user", "recent question"),
                AgentClient.message("assistant", "recent answer"),
            )
            for (includeHistory in listOf(false, true)) {
                for (reference in listOf(null, AgentReferenceDocument.RENPY)) {
                    val client = AgentClient(endpoint, "model", "", AgentRequest())
                    client.complete(if (includeHistory) history else emptyList(), "List project files", runtime,
                        context = "Standing preferences", reference = reference, onTool = {})
                }
                val plain = recorded.remove()
                val guide = recorded.remove()
                val stable = plain.getJSONArray("messages")
                val attached = guide.getJSONArray("messages")
                assertEquals(if (includeHistory) 5 else 3, stable.length())
                assertEquals(stable.length() + 1, attached.length())
                for (index in 0 until stable.length() - 1) {
                    assertEquals(stable.getJSONObject(index).toString(), attached.getJSONObject(index).toString())
                }
                assertFalse(attached.toString().contains("exclude-old-"))
                assertEquals(7, plain.getJSONArray("tools").length())
                assertEquals(plain.getJSONArray("tools").toString(), guide.getJSONArray("tools").toString())
                assertEquals(4096, guide.getInt("max_tokens"))
                assertTrue(guide.toString().toByteArray().size > plain.toString().toByteArray().size)
            }
        }
    }

    @Test fun modelRequestsUseABudgetAndNeverReplayKnownCredentialsOrDuplicateReads() {
        val key = "test-\"provider-key"
        val pairingToken = "test-local-pairing-token-12345678901"
        val requests = java.util.concurrent.LinkedBlockingQueue<JSONObject>()
        var reads = 0
        val runtime = object : AppSkillRuntime {
            override fun skills() = listOf(AppSkillDefinition("WORKSPACE_LIST", "List", JSONObject()))
            override fun invokeSkill(name: String, input: JSONObject): AppSkillResult {
                reads++
                assertEquals(".", input.getString("path"))
                return AppSkillResult(JSONObject().put("entries", JSONArray().put(JSONObject()
                    .put("name", "entry-$pairingToken").put("directory", false))))
            }
        }
        fun tool(id: String, path: String) = JSONObject().put("tool_calls", JSONArray().put(JSONObject()
            .put("id", id).put("type", "function").put("function", JSONObject().put("name", "WORKSPACE_LIST")
                .put("arguments", JSONObject().put("path", path).toString()))))
        ModelServer { _, request ->
            requests.add(request)
            val reply = when (requests.size) {
                1 -> tool("list", ".")
                2 -> tool("repeat", ".")
                3 -> tool("credential", key)
                else -> JSONObject().put("content", "Done $pairingToken")
            }
            val usage = JSONObject().put("prompt_tokens", 1000).put("completion_tokens", 25)
                .put("completion_tokens_details", JSONObject().put("reasoning_tokens", 10))
            if (requests.size == 1) usage.put("prompt_cache_hit_tokens", 128)
            else usage.put("prompt_tokens_details", JSONObject().put("cached_tokens", 512))
            Reply("200 OK", JSONObject().put("usage", usage)
                .put("choices", JSONArray().put(JSONObject().put("message", reply))))
        }.use { server ->
            val endpoint = checkNotNull(agentEndpoint("http://127.0.0.1:${server.port}/v1"))
            val history = listOf(
                AgentClient.message("user", "old-history-" + "旧".repeat(5000)),
                AgentClient.message("assistant", "older answer"),
                AgentClient.message("user", "recent-history $key"),
                AgentClient.message("assistant", "recent answer"),
            )
            val usage = mutableListOf<AgentModelUsage>()
            val result = AgentClient(endpoint, "model", key, AgentRequest(), listOf(pairingToken))
                .complete(history, "List files $pairingToken", runtime, context = "Preferences $key",
                    maxOutputTokens = 8192, onUsage = { usage.add(it) }, onTool = {})
            assertEquals("Done [redacted]", result)
            assertEquals(1, reads)
            assertEquals(4, requests.size)
            val bodies = List(4) { requests.remove() }
            assertTrue(bodies.all { it.getInt("max_tokens") == 8192 })
            assertEquals(4, usage.size)
            assertTrue(usage.all { it.input == 1000L && it.output == 25L && it.reasoning == 10L })
            assertEquals(listOf(128L, 512L, 512L, 512L), usage.map { it.cachedInput })
            val payloads = bodies.map { it.toString() }
            assertTrue(payloads.all { !it.contains(pairingToken) && !it.contains("provider-key") })
            assertFalse(payloads[0].contains("old-history"))
            assertTrue(payloads[0].contains("recent-history"))
            assertTrue(payloads[2].contains("reusedFrom"))
            assertTrue(payloads[3].contains("TOOL_NOT_ALLOWED"))
        }
    }

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
            assertEquals(256, probe.getInt("max_tokens"))
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
            val body = requests.remove()
            assertEquals(4096, body.getInt("max_tokens"))
            val messages = body.getJSONArray("messages")
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

    @Test fun partialDirectoryComparisonsKeepTheirCoverageAndPathsInTheModelReply() {
        val runtime = object : AppSkillRuntime {
            override fun skills() = listOf(AppSkillDefinition("COMPARE_DIRECTORY", "Compare", JSONObject()))
            override fun invokeSkill(name: String, input: JSONObject): AppSkillResult {
                assertEquals("COMPARE_DIRECTORY", name)
                return AppSkillResult(JSONObject().put("status", "INCOMPLETE").put("complete", false)
                    .put("errorCode", "PARTIAL_FAILURE").put("logId", "synthetic-log")
                    .put("entries", JSONArray().put(JSONObject().put("path", "large.bin")
                        .put("state", "SKIPPED").put("reason", "SCAN_BUDGET"))))
            }
        }
        var round = 0
        var payload: JSONObject? = null
        val events = mutableListOf<AgentToolEvent>()
        ModelServer { _, body ->
            val message = if (round++ == 0) JSONObject().put("tool_calls", JSONArray().put(JSONObject()
                .put("id", "compare-1").put("type", "function").put("function", JSONObject()
                    .put("name", "COMPARE_DIRECTORY").put("arguments", """{"source":"a","target":"b"}"""))))
            else {
                val messages = body.getJSONArray("messages")
                payload = JSONObject(messages.getJSONObject(messages.length() - 1).getString("content"))
                JSONObject().put("content", "Incomplete comparison")
            }
            Reply("200 OK", JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message))))
        }.use { server ->
            AgentClient(checkNotNull(agentEndpoint("http://127.0.0.1:${server.port}/v1")), "model", "", AgentRequest())
                .complete(emptyList(), "Compare directories", runtime, mode = AgentTaskMode.LOOKUP, onTool = events::add)
        }
        assertEquals("INCOMPLETE", payload!!.getString("status"))
        assertFalse(payload!!.getBoolean("complete"))
        assertEquals("large.bin", payload!!.getJSONArray("entries").getJSONObject(0).getString("path"))
        assertTrue(events.any { it.failure == "PARTIAL_FAILURE" })
    }

    @Test fun translationTurnExposesOnlyDraftToolsAndSendsSelectedTextOnce() {
        val root=java.nio.file.Files.createTempDirectory("translation-wire-").toFile().canonicalFile
        try {
            val file=root.resolve("script.txt").apply { writeText("private other row\nHello {name}\r\n") }
            val draft=TranslationDraft.open(file,"UTF-8","UTF-8")
            val turn=draft.begin(1,1)
            val requests=mutableListOf<JSONObject>();val events=mutableListOf<String>()
            val runtime=object: AppSkillRuntime {
                override fun skills()=emptyList<AppSkillDefinition>()
                override fun invokeSkill(name:String,input:JSONObject):AppSkillResult=error("No workspace tools permitted")
            }
            fun call(name:String,input:JSONObject)=JSONObject().put("tool_calls",JSONArray().put(JSONObject().put("id","c${requests.size}").put("type","function")
                .put("function",JSONObject().put("name",name).put("arguments",input.toString()))))
            ModelServer { _,body ->
                requests.add(JSONObject(body.toString()))
                val message=when(requests.size) {
                    1 -> call(TranslationTurn.READ,JSONObject()).put("reasoning_content","Read selected rows")
                    2 -> call(TranslationTurn.PROPOSE,JSONObject().put("revision",turn.token).put("translations",JSONArray(listOf("你好 {name}"))))
                        .put("reasoning_content","Fill draft only")
                    else -> JSONObject().put("content","Draft ready")
                }
                Reply("200 OK",JSONObject().put("choices",JSONArray().put(JSONObject().put("message",message))))
            }.use { server ->
                val client=AgentClient(checkNotNull(agentEndpoint("http://127.0.0.1:${server.port}/v1")),"model","",AgentRequest())
                assertEquals("Draft ready",client.complete(emptyList(),"Translate",runtime,permission=AgentPermission.FULL_LOCAL,
                    translation=turn,onReasoning={events.add("reason:$it")},confirm={error("Draft proposal needs no file-write approval")},
                    onTool={events.add("${it.name}:${it.stage}")}))
            }
            assertEquals("你好 {name}",draft.value(1));assertEquals("private other row\nHello {name}\r\n",file.readText())
            assertEquals(3,requests.size)
            for (request in requests) {
                val tools=request.getJSONArray("tools")
                assertEquals(2,tools.length());assertFalse(tools.toString().contains("WRITE_TEXT"))
                assertFalse(request.toString().contains("private other row"));assertFalse(request.toString().contains(file.path))
            }
            assertTrue(requests[1].toString().contains("Hello {name}"))
            assertFalse(requests[2].toString().contains("Hello {name}"))
            assertEquals(listOf("reason:Read selected rows","${TranslationTurn.READ}:RUNNING","${TranslationTurn.READ}:SUCCESS",
                "reason:Fill draft only","${TranslationTurn.PROPOSE}:RUNNING","${TranslationTurn.PROPOSE}:SUCCESS"),events)
        } finally { root.deleteRecursively() }
    }

    @Test fun staleTranslationStopsWithoutRetryingOrOverwritingHumanEdits() {
        val root=java.nio.file.Files.createTempDirectory("translation-stale-").toFile().canonicalFile
        try {
            val file=root.resolve("script.txt").apply { writeText("Original") }
            val draft=TranslationDraft.open(file,"UTF-8","UTF-8");val turn=draft.begin(0,1)
            var requests=0
            val runtime=object: AppSkillRuntime {
                override fun skills()=emptyList<AppSkillDefinition>()
                override fun invokeSkill(name:String,input:JSONObject):AppSkillResult=error("No file tools")
            }
            ModelServer { _,_ ->
                requests++
                val name=if(requests==1) TranslationTurn.READ else TranslationTurn.PROPOSE
                if(requests==2) draft.edit(0,"Human edit")
                val input=if(requests==1) JSONObject() else JSONObject().put("revision",turn.token).put("translations",JSONArray(listOf("AI edit")))
                Reply("200 OK",JSONObject().put("choices",JSONArray().put(JSONObject().put("message",JSONObject().put("tool_calls",JSONArray().put(
                    JSONObject().put("id","c$requests").put("type","function").put("function",JSONObject().put("name",name).put("arguments",input.toString()))))))))
            }.use { server ->
                val client=AgentClient(checkNotNull(agentEndpoint("http://127.0.0.1:${server.port}/v1")),"model","",AgentRequest())
                val error=assertThrows(AgentClientException::class.java) { client.complete(emptyList(),"Translate",runtime,translation=turn,onTool={}) }
                assertEquals("translation_batch",error.stage);assertEquals("STALE_DRAFT",error.message)
            }
            assertEquals(2,requests);assertEquals("Human edit",draft.value(0));assertEquals("Original",file.readText())
        } finally { root.deleteRecursively() }
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
