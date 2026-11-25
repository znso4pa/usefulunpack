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
internal const val AGENT_SESSION_NAME_LIMIT = 80
private const val SESSION_LIMIT = 8 * 1024 * 1024
private const val AGENT_HISTORY_LIMIT = 8
private const val AGENT_HISTORY_TRIM = 4
private const val SESSION_SUMMARY_CACHE_LIMIT = 256
private val SESSION_ID = Regex("[a-f0-9]{32}")

internal data class AgentExchange(val question: String, val answer: String = "")

internal data class AgentSessionSummary(val id: String, val revision: Long, val title: String)

internal data class AgentSessionCatalog(val sessions: List<AgentSessionSummary>, val unreadableCount: Int)

internal data class AgentSession(
    val id: String = UUID.randomUUID().toString().replace("-", ""),
    val revision: Long = 0,
    val workspace: String? = null,
    val permission: AgentPermission = AgentPermission.READ_ONLY,
    val context: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val turns: List<AgentExchange> = emptyList(),
    val customName: String? = null,
) {
    val title: String get() = customName ?: turns.firstOrNull()?.question?.lineSequence()?.firstOrNull().orEmpty().take(70)
    val feature: String get() = "agent_session_$id"
    val summary: AgentSessionSummary get() = AgentSessionSummary(id, revision, title)

    fun withName(name: String): AgentSession = copy(customName = name.lineSequence().joinToString(" ").trim()
        .take(AGENT_SESSION_NAME_LIMIT).dropLastWhile(Char::isHighSurrogate).trimEnd().takeIf(String::isNotBlank))

    fun historyWindow(): List<AgentExchange> {
        val completed = turns.filter { it.answer.isNotEmpty() }
        val overflow = (completed.size - AGENT_HISTORY_LIMIT).coerceAtLeast(0)
        val first = ((overflow + AGENT_HISTORY_TRIM - 1) / AGENT_HISTORY_TRIM) * AGENT_HISTORY_TRIM
        return completed.drop(first)
    }

    fun changesModelEndpoint(url: String): Boolean {
        val previousEndpoint = agentEndpoint(baseUrl)?.toExternalForm() ?: return false
        return previousEndpoint != agentEndpoint(url)?.toExternalForm()
    }

    fun withModelSettings(url: String, modelName: String): AgentSession {
        if (changesModelEndpoint(url)) {
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
    private fun sessionFiles(): List<File> {
        if (!directory.exists()) return emptyList()
        val files = directory.listFiles { file ->
            file.extension == "gz" && SESSION_ID.matches(file.nameWithoutExtension)
        } ?: throw IOException("Cannot list Agent sessions")
        return files.sortedByDescending { it.lastModified() }
    }

    fun list(): List<AgentSession> = synchronized(lock) { sessionFiles().map(::read) }

    fun catalog(): AgentSessionCatalog = synchronized(lock) {
        var unreadable = 0
        val sessions = sessionFiles().mapNotNull { file ->
            val stamp = file.stamp()
            val key = file.absoluteFile
            summaries[key]?.takeIf { it.stamp == stamp }?.summary ?: try {
                read(file).summary.also { summaries[key] = CachedSummary(stamp, it) }
            } catch (_: Exception) {
                summaries.remove(key)
                unreadable++
                null
            }
        }
        AgentSessionCatalog(sessions, unreadable)
    }

    fun open(id: String): AgentSession = synchronized(lock) {
        val file = file(id)
        if (!file.isFile) throw IOException("Agent session was deleted")
        read(file).also { summaries[file.absoluteFile] = CachedSummary(file.stamp(), it.summary) }
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
            .put("t", saved.customName)
            .toString().toByteArray(Charsets.UTF_8)
        if (data.size > SESSION_LIMIT) throw IOException("Agent session exceeds the storage limit")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create Agent session directory")
        val temp = temporaryFile(session.id)
        try {
            GZIPOutputStream(temp.outputStream()).use { it.write(data) }
            Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            summaries[file.absoluteFile] = CachedSummary(file.stamp(), saved.summary)
        } finally {
            Files.deleteIfExists(temp.toPath())
        }
        saved
    }

    fun beginTurn(session: AgentSession, question: String): AgentSession = synchronized(lock) {
        if (!active.add(session.id)) throw IOException("Agent session is running")
        var saved = false
        try {
            save(session.copy(turns = session.turns + AgentExchange(question)), inFlight = true).also { saved = true }
        } finally {
            if (!saved) active.remove(session.id)
        }
    }

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
        summaries.remove(file.absoluteFile)
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
        return AgentSession(
            id = id,
            revision = data.getLong("r"),
            workspace = data.opt("p") as? String,
            permission = permission,
            context = data.optString("c"),
            baseUrl = data.optString("u"),
            model = data.optString("n"),
            turns = turns,
            customName = (data.opt("t") as? String)?.takeIf(String::isNotBlank),
        )
    }

    private fun file(id: String): File {
        require(SESSION_ID.matches(id))
        return File(directory, "$id.gz")
    }

    private fun temporaryFile(id: String) = File(directory, "$id.tmp")

    private data class FileStamp(val modified: Long, val length: Long)
    private data class CachedSummary(val stamp: FileStamp, val summary: AgentSessionSummary)
    private fun File.stamp() = FileStamp(lastModified(), length())

    private companion object {
        val lock = Any()
        val active = mutableSetOf<String>()
        val summaries = object : LinkedHashMap<File, CachedSummary>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<File, CachedSummary>?): Boolean =
                size > SESSION_SUMMARY_CACHE_LIMIT
        }
    }
}
