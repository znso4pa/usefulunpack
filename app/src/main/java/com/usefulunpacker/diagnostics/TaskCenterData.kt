package com.usefulunpacker

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONException

/** Private metadata lives beside the diagnostic chain, outside exported log text. */
internal fun writeTaskOutputs(directory: File, id: String, outputs: List<String>) {
    val temp = File(directory, "$id.outputs.tmp").toPath()
    try {
        Files.write(temp, JSONArray(outputs).toString().toByteArray(Charsets.UTF_8))
        Files.move(temp, File(directory, "$id.outputs").toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temp)
    }
}

internal fun readTaskOutputs(directory: File, id: String): List<String> {
    val file = File(directory, "$id.outputs")
    if (!file.isFile) return emptyList()
    if (file.length() > 512 * 1024) throw IOException("Task output receipt is oversized")
    return try {
        val entries = JSONArray(file.readText(Charsets.UTF_8))
        List(minOf(64, entries.length()), entries::getString)
    } catch (e: JSONException) {
        throw IOException("Invalid task output receipt", e)
    }
}

internal fun deleteTaskOutputs(directory: File, id: String) {
    Files.deleteIfExists(File(directory, "$id.outputs").toPath())
}

internal fun recordTaskOutput(file: File) {
    AppDiagnostics.currentTrace()?.publishOutput(file)
}

internal fun recordPackedTaskOutput(file: File) {
    recordTaskOutput(packedTaskOutput(file))
}

internal fun packedTaskOutput(file: File): File {
    val firstVolume = File(file.parentFile, "${file.name}.001")
    return if (!file.exists() && firstVolume.isFile) firstVolume else file
}

/** Recheck storage and symbolic links when an old receipt is opened. */
internal fun taskOutputInStorage(root: File, path: String): File? {
    val requested = File(path)
    if (!requested.isAbsolute) return null
    if (requested.toPath().any { it.toString() == ".." }) return null
    val lexical = requested.toPath().toAbsolutePath().normalize()
    val lexicalRoot = root.toPath().toAbsolutePath().normalize()
    val canonicalRoot = root.canonicalFile.toPath()
    val matchingRoot = if (lexical.startsWith(lexicalRoot)) lexicalRoot else canonicalRoot
    if (lexical == matchingRoot || !lexical.startsWith(matchingRoot)) return null
    var at = canonicalRoot
    for (segment in matchingRoot.relativize(lexical)) {
        at = at.resolve(segment)
        if (Files.isSymbolicLink(at)) return null
    }
    return at.toFile().takeIf { Files.exists(at, NOFOLLOW_LINKS) }
}

/** Dispatches to the owner of each task's existing queue and cancellation path. */
internal object TaskCenterControls {
    private data class Control(val state: () -> String?, val cancel: () -> Unit)
    private val active = ConcurrentHashMap<String, Control>()

    fun register(id: String, state: () -> String?, cancel: () -> Unit): AutoCloseable {
        val control = Control(state, cancel)
        active[id] = control
        return AutoCloseable { active.remove(id, control) }
    }

    fun state(id: String): String? = active[id]?.state?.invoke()
    private fun cancellable(state: String?) = state == "QUEUED" || state == "RUNNING"
    fun canCancel(id: String): Boolean = cancellable(state(id))

    fun cancel(id: String) {
        val control = active[id] ?: return
        if (cancellable(control.state())) control.cancel()
    }
}
