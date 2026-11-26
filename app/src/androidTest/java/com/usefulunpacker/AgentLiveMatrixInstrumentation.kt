package com.usefulunpacker

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Runs only in the test APK. The release app's saved provider settings stay on the device. */
class AgentLiveMatrixInstrumentation : Instrumentation() {
    private data class Variant(
        val budget: Int = 4096,
        val history: List<JSONObject> = emptyList(),
        val context: String = "",
        val guide: AgentReferenceDocument? = null,
        val permission: AgentPermission = AgentPermission.READ_ONLY,
        val entries: Int = 1,
    )

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val prefs = targetContext.getSharedPreferences("bm", Context.MODE_PRIVATE)
        val endpoint = agentEndpoint(prefs.getString("agent_base_url", "").orEmpty())
        val model = prefs.getString("agent_model", "").orEmpty()
        val key = try {
            endpoint?.let { AgentCredentials(File(targetContext.noBackupFilesDir, "agent-credentials")).read(it) }
        } catch (_: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("matrix_error", "credential_read") })
            return
        }
        if (endpoint == null || model.isBlank()) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("matrix_error", "model_settings") })
            return
        }

        val baseline = Variant()
        val pairs = linkedMapOf(
            "budget" to (baseline.copy(budget = 2048) to baseline),
            "history" to (baseline to baseline.copy(history = listOf(
                AgentClient.message("user", "Keep placeholder tags when editing synthetic dialogue."),
                AgentClient.message("assistant", "I will preserve literal placeholders and control tags."),
                AgentClient.message("user", "Use relative paths and ignore unrelated assets."),
                AgentClient.message("assistant", "I will use the current workspace and report only relevant files."),
            ))),
            "standing_context" to (baseline to baseline.copy(
                context = "Fixture note. Preserve placeholders, punctuation, and line endings. ".repeat(8).trim())),
            "attached_guide" to (baseline to baseline.copy(guide = AgentReferenceDocument.RENPY)),
            "permission" to (baseline to baseline.copy(permission = AgentPermission.FULL_LOCAL)),
            "listing_size" to (baseline to baseline.copy(entries = 24)),
        )
        val stop = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val jobs = pairs.flatMap { (factor, variants) ->
                (1..3).map { repetition ->
                    pool.submit {
                        val order = if (repetition % 2 == 0) listOf("B" to variants.second, "A" to variants.first)
                            else listOf("A" to variants.first, "B" to variants.second)
                        for ((side, variant) in order) {
                            if (stop.get()) break
                            val row = runCase(endpoint, model, key.orEmpty(), factor, repetition, side, variant)
                            val http = row.optInt("http")
                            if (http == 401 || http == 403) stop.set(true)
                            sendStatus(0, Bundle().apply { putString("matrix_row", row.toString()) })
                        }
                    }
                }
            }
            jobs.forEach { it.get() }
            finish(Activity.RESULT_OK, Bundle().apply { putBoolean("auth_stopped", stop.get()) })
        } finally {
            pool.shutdownNow()
        }
    }

    private fun runCase(
        endpoint: URL, model: String, key: String, factor: String, repetition: Int, side: String, variant: Variant,
    ): JSONObject {
        val usage = JSONArray()
        val row = JSONObject().put("factor", factor).put("repeat", repetition).put("side", side)
            .put("usage", usage).put("budget", variant.budget)
        val directory = Files.createTempDirectory(targetContext.cacheDir.toPath(), "uu-matrix-").toFile()
        try {
            File(directory, "target.txt").writeText("synthetic fixture")
            for (index in 1 until variant.entries) File(directory, "sample_$index.txt").writeText("synthetic fixture")
            McpTasks { _, _ -> error("The model matrix accepts read-only operations") }.use { tasks ->
                val runtime = WorkspaceSkillRuntime(McpWorkspace(directory), tasks)
                var reads = 0
                var otherCalls = 0
                var approvals = 0
                val prompt = "Synthetic test $factor-$repetition. Call WORKSPACE_LIST once with path '.' " +
                    "and find target.txt. If present, reply with just target.txt."
                val answer = AgentClient(endpoint, model, key, AgentRequest()).complete(
                    variant.history, prompt, runtime, variant.permission, context = variant.context, reference = variant.guide,
                    confirm = { approvals++; false }, maxOutputTokens = variant.budget, onUsage = { count ->
                        usage.put(JSONObject().put("in", count.input ?: JSONObject.NULL)
                            .put("out", count.output ?: JSONObject.NULL).put("think", count.reasoning ?: JSONObject.NULL)
                            .put("cache", count.cachedInput ?: JSONObject.NULL).put("ms", count.elapsedMs))
                    },
                ) { event ->
                    if (event.stage == AgentToolStage.SUCCESS && event.failure == null) {
                        if (event.name == "WORKSPACE_LIST") reads++ else otherCalls++
                    }
                    row.put("reads", reads).put("other_calls", otherCalls).put("approvals", approvals)
                }
                val exact = answer.trim() == "target.txt"
                row.put("state", if (exact && reads == 1 && otherCalls == 0 && approvals == 0) "pass" else "mismatch")
                    .put("exact", exact).put("reads", reads).put("other_calls", otherCalls).put("approvals", approvals)
            }
        } catch (e: AgentClientException) {
            row.put("state", e.stage).put("http", e.httpStatus)
        } catch (_: SocketTimeoutException) {
            row.put("state", "timeout")
        } catch (_: CancellationException) {
            row.put("state", "cancelled")
        } catch (e: Exception) {
            row.put("state", "error").put("type", e.javaClass.simpleName)
        } finally {
            if (!directory.deleteRecursively()) row.put("cleanup_failed", true)
        }
        return row
    }
}
