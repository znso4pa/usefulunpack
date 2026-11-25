package com.usefulunpacker

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal const val AGENT_CONTEXT_LIMIT = 8192
private const val SESSION_LIMIT = 8 * 1024 * 1024
private val SESSION_ID = Regex("[a-f0-9]{32}")

internal data class AgentExchange(val question: String, val answer: String = "")

internal data class AgentSession(
    val id: String = UUID.randomUUID().toString().replace("-", ""),
    val revision: Long = 0,
    val workspace: String? = null,
    val permission: AgentPermission = AgentPermission.READ_ONLY,
    val context: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val turns: List<AgentExchange> = emptyList(),
) {
    val title: String get() = turns.firstOrNull()?.question?.lineSequence()?.firstOrNull().orEmpty().take(70)
    val feature: String get() = "agent_session_$id"

    fun withModelSettings(url: String, modelName: String): AgentSession {
        val previousEndpoint = agentEndpoint(baseUrl)?.toExternalForm()
        val nextEndpoint = agentEndpoint(url)?.toExternalForm()
        if (previousEndpoint != null && previousEndpoint != nextEndpoint) {
            return AgentSession(baseUrl = url, model = modelName)
        }
        if (turns.isNotEmpty()) {
            return AgentSession(workspace = workspace, context = context, baseUrl = url, model = modelName)
        }
        return copy(baseUrl = url, model = modelName)
    }
}

/** One compressed snapshot per session. Only the current revision can update a saved session. */
internal class AgentSessionStore(private val directory: File) {
    fun list(): List<AgentSession> = synchronized(lock) {
        if (!directory.exists()) return emptyList()
        val files = directory.listFiles { file ->
            file.extension == "gz" && SESSION_ID.matches(file.nameWithoutExtension)
        } ?: throw IOException("Cannot list Agent sessions")
        files.sortedByDescending { it.lastModified() }.map { read(it) }
    }

    fun save(session: AgentSession, inFlight: Boolean = false): AgentSession = synchronized(lock) {
        if (!inFlight && session.id in active) throw IOException("Agent session is running")
        val file = file(session.id)
        val revision = if (file.isFile) read(file).revision else 0L
        if (revision != session.revision) throw IOException("Agent session was changed or deleted")
        val saved = session.copy(revision = revision + 1)
        val data = JSONObject()
            .put("r", saved.revision)
            .put("p", saved.workspace ?: JSONObject.NULL)
            .put("m", saved.permission.name)
            .put("c", saved.context)
            .put("u", saved.baseUrl)
            .put("n", saved.model)
            .put("h", JSONArray(saved.turns.map { JSONArray().put(it.question).put(it.answer) }))
            .toString().toByteArray(Charsets.UTF_8)
        if (data.size > SESSION_LIMIT) throw IOException("Agent session exceeds the storage limit")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create Agent session directory")
        val temp = temporaryFile(session.id)
        try {
            GZIPOutputStream(temp.outputStream()).use { it.write(data) }
            Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp.toPath())
        }
        saved
    }

    fun start(id: String): Boolean = synchronized(lock) { active.add(id) }
    fun finish(id: String) { synchronized(lock) { active.remove(id) } }

    fun delete(session: AgentSession, removeLogs: (String) -> Unit) = synchronized(lock) {
        if (session.id in active) throw IOException("Agent session is running")
        val file = file(session.id)
        if (!file.isFile || read(file).revision != session.revision) {
            throw IOException("Agent session was changed or deleted")
        }
        removeLogs(session.feature)
        Files.deleteIfExists(temporaryFile(session.id).toPath())
        Files.deleteIfExists(file.toPath())
        Unit
    }

    private fun read(file: File): AgentSession {
        val id = file.nameWithoutExtension
        val bytes = java.io.ByteArrayOutputStream()
        GZIPInputStream(file.inputStream()).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                if (bytes.size() + size > SESSION_LIMIT) throw IOException("Agent session exceeds the storage limit")
                bytes.write(buffer, 0, size)
            }
        }
        val data = JSONObject(String(bytes.toByteArray(), Charsets.UTF_8))
        val records = data.getJSONArray("h")
        val turns = List(records.length()) { index ->
            val turn = records.getJSONArray(index)
            AgentExchange(turn.getString(0), turn.optString(1))
        }
        val permission = AgentPermission.entries.firstOrNull { it.name == data.optString("m") }
            ?: AgentPermission.READ_ONLY
        return AgentSession(id, data.getLong("r"), data.opt("p") as? String, permission, data.optString("c"),
            data.optString("u"), data.optString("n"), turns)
    }

    private fun file(id: String): File {
        require(SESSION_ID.matches(id))
        return File(directory, "$id.gz")
    }

    private fun temporaryFile(id: String) = File(directory, "$id.tmp")

    private companion object {
        val lock = Any()
        val active = mutableSetOf<String>()
    }
}
