package com.usefulunpacker

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal data class AgentDiffLine(val oldLine: Int?, val newLine: Int?, val text: String, val kind: Int) {
    fun json() = JSONArray().put(oldLine).put(newLine).put(text).put(kind)
}

internal data class AgentFileChange(
    val path: String, val existedBefore: Boolean, val existsAfter: Boolean,
    val beforeSize: Long, val afterSize: Long, val lines: List<AgentDiffLine>, val limited: Boolean,
) {
    fun json() = JSONObject().put("p", path).put("b", existedBefore).put("a", existsAfter)
        .put("s", beforeSize).put("t", afterSize).put("l", limited).put("d", JSONArray(lines.map(AgentDiffLine::json)))
    companion object {
        fun read(data: JSONObject): AgentFileChange {
            val rows = data.getJSONArray("d")
            return AgentFileChange(data.getString("p"), data.getBoolean("b"), data.getBoolean("a"),
                data.getLong("s"), data.getLong("t"), List(minOf(rows.length(), 1000)) {
                    val row = rows.getJSONArray(it)
                    AgentDiffLine((row.opt(0) as? Number)?.toInt(), (row.opt(1) as? Number)?.toInt(), row.getString(2), row.getInt(3))
                }, data.optBoolean("l"))
        }
    }
}

/** Bounded line LCS. Large comparisons report metadata instead of claiming an approximate diff. */
internal fun agentTextDiff(before: String, after: String): List<AgentDiffLine>? {
    fun split(text: String) = if (text.isEmpty()) emptyList() else text.split(Regex("\\r\\n|\\r|\\n"))
    val old = split(before)
    val new = split(after)
    if (old.size + new.size > 1000 || old.size.toLong() * new.size > 250_000) return null
    val cells = Array(old.size + 1) { IntArray(new.size + 1) }
    for (i in old.indices.reversed()) for (j in new.indices.reversed()) {
        cells[i][j] = if (old[i] == new[j]) cells[i + 1][j + 1] + 1 else maxOf(cells[i + 1][j], cells[i][j + 1])
    }
    val result = mutableListOf<AgentDiffLine>()
    var i = 0
    var j = 0
    while (i < old.size || j < new.size) {
        when {
            i < old.size && j < new.size && old[i] == new[j] -> {
                result.add(AgentDiffLine(i + 1, j + 1, old[i], 0)); i++; j++
            }
            i < old.size && (j == new.size || cells[i + 1][j] >= cells[i][j + 1]) -> {
                result.add(AgentDiffLine(i + 1, null, old[i], -1)); i++
            }
            else -> { result.add(AgentDiffLine(null, j + 1, new[j], 1)); j++ }
        }
    }
    return result
}

/** Captures only sandbox-resolved paths after write approval, shared with UU/UUT worker threads. */
internal class AgentChangeCapture(private val root: File) {
    private data class Snapshot(val exists: Boolean, val directory: Boolean, val size: Long, val stamp: Long,
        val hash: String?, val text: String?)
    private val originals = linkedMapOf<File, Snapshot>()
    private val directories = linkedSetOf<File>()
    private var textBudget = 256 * 1024
    private var enabled = false
    private var target: File? = null
    private var finished: List<AgentFileChange>? = null
    var incomplete = false
        private set

    @Synchronized fun enable(approval: AgentApproval? = null) {
        val field = when (approval?.name) {
            "WRITE_TEXT", "DELETE_FILE" -> "path"
            "EXTRACT", "COMPRESS" -> "output"
            else -> null
        }
        target = if (field == null) null else root.toPath().resolve(checkNotNull(approval).input.getString(field))
            .normalize().toFile()
        enabled = true
    }
    @Synchronized fun pause() { enabled = false }

    @Synchronized fun watch(file: File) {
        if (!enabled || finished != null) return
        val output = target
        if (output != null && file != output && !file.toPath().startsWith(output.toPath())) return
        try { capture(file) }
        catch (_: IOException) { captureFailure() }
        catch (_: java.nio.file.DirectoryIteratorException) { captureFailure() }
    }

    private fun captureFailure() {
        if (!incomplete) AppDiagnostics.begin("agent_changes").rejectForRetry(FailureCode.IO, "Change capture is incomplete")
        incomplete = true
    }

    private fun limit(reason: String) {
        if (!incomplete) AppDiagnostics.currentTrace()?.note("change_capture_limited=$reason")
        incomplete = true
    }

    private fun allowed(file: File): Boolean = file.toPath().startsWith(root.toPath()) &&
        root.toPath().relativize(file.toPath()).none { it.toString().startsWith(".uu-mcp-") } &&
        !Files.isSymbolicLink(file.toPath()) && file.canonicalFile == file

    private fun capture(file: File) {
        if (!allowed(file) || file in originals) return
        if (originals.size >= 128) { limit("PATH_COUNT"); return }
        originals[file] = snapshot(file)
        if (file.isDirectory) {
            directories.add(file)
            Files.newDirectoryStream(file.toPath()).use { children ->
                for (child in children) {
                    if (originals.size >= 128) { limit("PATH_COUNT"); break }
                    capture(child.toFile())
                }
            }
        }
    }

    private fun snapshot(file: File): Snapshot {
        if (!file.exists()) return Snapshot(false, false, 0, 0, null, "")
        if (file.isDirectory) return Snapshot(true, true, 0, 0, null, null)
        val size = file.length()
        if (size > 2 * 1024 * 1024) {
            limit("LARGE_FILE")
            return Snapshot(true, false, size, file.lastModified(), null, null)
        }
        val bytes = file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > 2 * 1024 * 1024) throw IOException("File grew during change capture")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val text = if (bytes.size <= 32 * 1024 && bytes.size <= textBudget && (bytes.isEmpty() || looksLikeText(bytes))) {
            textBudget -= bytes.size
            decodeTextStrict(bytes, detectBestEncoding(bytes) ?: "UTF-8")
        } else null
        return Snapshot(true, false, bytes.size.toLong(), file.lastModified(), hash, text)
    }

    @Synchronized fun finish(): List<AgentFileChange> {
        finished?.let { return it }
        val result = mutableListOf<AgentFileChange>()
        try {
            val pending = ArrayDeque(directories + originals.keys.filter { it.isDirectory })
            val seen = mutableSetOf<File>()
            while (pending.isNotEmpty()) {
                val dir = pending.removeFirst()
                if (!seen.add(dir) || !allowed(dir)) continue
                if (!dir.exists()) continue
                Files.newDirectoryStream(dir.toPath()).use { children ->
                    for (entry in children) {
                        val child = entry.toFile()
                        if (!allowed(child)) continue
                        if (child !in originals) {
                            if (originals.size >= 128) { limit("PATH_COUNT"); break }
                            originals[child] = Snapshot(false, false, 0, 0, null, "")
                        }
                        if (child.isDirectory) pending.add(child)
                    }
                }
            }
            for ((file, before) in originals) {
                if (!allowed(file)) { captureFailure(); continue }
                val after = snapshot(file)
                val unchanged = before.exists == after.exists && before.directory == after.directory && before.size == after.size &&
                    if (before.hash != null || after.hash != null) before.hash == after.hash else before.stamp == after.stamp
                if (unchanged) continue
                val diff = if (before.text != null && after.text != null) agentTextDiff(before.text, after.text) else null
                result.add(AgentFileChange(root.toPath().relativize(file.toPath()).toString().ifEmpty { "." }, before.exists, after.exists,
                    before.size, after.size, diff.orEmpty(), diff == null || diff.none { it.kind != 0 }))
            }
        } catch (_: IOException) { captureFailure() }
        catch (_: java.nio.file.DirectoryIteratorException) { captureFailure() }
        return result.toList().also { finished = it }
    }
}
