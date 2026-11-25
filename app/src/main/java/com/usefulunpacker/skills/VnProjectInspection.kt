package com.usefulunpacker

import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CancellationException

private const val VN_ENTRY_LIMIT = 640
private const val VN_DIRECTORY_ENTRY_LIMIT = 128
private const val VN_EXAMPLES_PER_KIND = 4
private const val VN_MAX_DEPTH = 3
private val vnDirectories = setOf("www", "game", "data", "scenario", "scripts", "script", "tl", "localization", "lang")
private val translationDirectories = setOf("tl", "localization", "lang")
private val vnPriorityDirectories = listOf("game/tl", "game", "data/scenario", "www/data/scenario", "www/data")
private val vnArchives = setOf("xp3", "pfs", "nsa", "ypf", "rgss", "rpa", "int", "lpk", "bgi", "zip", "7z", "rar")
private val vnSources = setOf("ks", "rpy")
private val vnCompiledData = setOf("rpyc", "rvdata2", "rvdata", "rxdata", "tjs2")
private val vnTextCandidates = setOf("tjs", "json", "csv", "tsv", "po", "txt")

private enum class VnCandidateKind(val wireName: String) {
    TRANSLATION("existing_translation"), SOURCE("script_source"), ARCHIVE("archive"),
    COMPILED("compiled_or_binary"), DATA("text_candidate"),
}

private data class VnCandidate(val path: String, val kind: VnCandidateKind, val format: String)

/** View the names of likely localization inputs without opening their contents or any archive. */
internal fun inspectVnProject(workspace: McpWorkspace, path: String): JSONObject {
    val root = workspace.resolve(path, allowRoot = true).toPath()
    val engine = detectGameEngine(root)
    val sample = ArrayDeque<Path>()
    val queued = mutableSetOf<Path>()
    fun enqueue(directory: Path) {
        if (queued.add(directory)) sample.add(directory)
    }
    enqueue(root)
    vnPriorityDirectories.mapNotNull(root::vnDirectory).forEach(::enqueue)
    val counts = mutableMapOf<VnCandidateKind, Int>()
    val candidates = mutableMapOf<VnCandidateKind, MutableList<VnCandidate>>()
    var examined = 0
    var truncated = false
    while (sample.isNotEmpty() && examined < VN_ENTRY_LIMIT) {
        val directory = sample.removeFirst()
        val relative = workspace.relativePath(directory.toFile())
        val checked = workspace.resolve(relative, allowRoot = true).toPath()
        val inTranslation = root.relativize(directory).any {
            it.toString().lowercase(Locale.ROOT) in translationDirectories
        }
        Files.newDirectoryStream(checked).use { stream ->
            val entries = stream.iterator()
            var directoryEntries = 0
            while (examined < VN_ENTRY_LIMIT && directoryEntries < VN_DIRECTORY_ENTRY_LIMIT && entries.hasNext()) {
                if (Thread.currentThread().isInterrupted) throw CancellationException()
                val entry = entries.next()
                examined++
                directoryEntries++
                val name = entry.fileName.toString()
                if (name.startsWith(".") || name.any { it == '\\' || it.isISOControl() }) continue
                val depth = root.relativize(entry).nameCount
                if ((name.lowercase(Locale.ROOT) in vnDirectories || inTranslation) &&
                    Files.isDirectory(entry, NOFOLLOW_LINKS)) {
                    if (depth <= VN_MAX_DEPTH) enqueue(entry)
                    else truncated = true
                    continue
                }
                val candidate = vnCandidate(workspace.relativePath(entry.toFile()), name, inTranslation) ?: continue
                if (!Files.isRegularFile(entry, NOFOLLOW_LINKS)) continue
                counts.merge(candidate.kind, 1, Int::plus)
                val examples = candidates.getOrPut(candidate.kind) { ArrayList() }
                if (examples.size < VN_EXAMPLES_PER_KIND) examples.add(candidate)
            }
            if (entries.hasNext()) truncated = true
        }
    }
    val next = when {
        VnCandidateKind.TRANSLATION in counts -> "inspect_existing_translation"
        VnCandidateKind.SOURCE in counts -> "inspect_source_candidates"
        VnCandidateKind.ARCHIVE in counts -> "list_archive_entries"
        VnCandidateKind.DATA in counts -> "inspect_text_candidates"
        VnCandidateKind.COMPILED in counts -> "find_source_or_supported_tool"
        else -> "ask_for_more_evidence"
    }
    val examples = VnCandidateKind.entries.flatMap { kind -> candidates[kind].orEmpty().sortedBy { it.path } }
    return JSONObject()
        .put("engine", engine)
        .put("next", next)
        .put("truncated", truncated || sample.isNotEmpty() || engine.optBoolean("truncated") ||
            counts.values.any { it > VN_EXAMPLES_PER_KIND })
        .put("counts", JSONObject().apply { counts.forEach { (kind, count) -> put(kind.wireName, count) } })
        .put("candidates", JSONArray(examples.map {
            JSONObject().put("path", it.path).put("kind", it.kind.wireName).put("format", it.format)
        }))
}

private fun Path.vnDirectory(relative: String): Path? {
    var directory = this
    for (part in relative.split('/')) {
        directory = directory.resolve(part)
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) return null
    }
    return directory
}

private fun vnCandidate(path: String, name: String, inTranslation: Boolean): VnCandidate? {
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (inTranslation && (extension in vnSources || extension in vnTextCandidates)) {
        return VnCandidate(path, VnCandidateKind.TRANSLATION, extension)
    }
    if (extension in vnSources) {
        return VnCandidate(path, VnCandidateKind.SOURCE, extension)
    }
    if (extension in vnCompiledData) {
        return VnCandidate(path, VnCandidateKind.COMPILED, extension)
    }
    val archive = formatOfName(name)
    if (archive != null && archive in vnArchives) return VnCandidate(path, VnCandidateKind.ARCHIVE, archive)
    return if (extension in vnTextCandidates) {
        VnCandidate(path, VnCandidateKind.DATA, extension)
    } else null
}
