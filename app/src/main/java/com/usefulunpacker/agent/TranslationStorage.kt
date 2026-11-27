package com.usefulunpacker

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONObject

private const val TRANSLATION_STORAGE_LIMIT = 8 * 1024 * 1024

/** One live table per session. A closing table holds its lease until the final autosave finishes. */
internal object TranslationEditors {
    private val owners = mutableMapOf<String, Any>()
    @Synchronized fun claim(id: String, owner: Any): Boolean {
        if (owners[id]?.let { it !== owner } == true) return false
        owners[id] = owner; return true
    }
    @Synchronized fun release(id: String, owner: Any) { if (owners[id] === owner) owners.remove(id) }
    @Synchronized fun heldByOther(id: String, owner: Any) = owners[id]?.let { it !== owner } == true
}

internal fun readTranslationSnapshot(file: File): TranslationSnapshot {
    val output = java.io.ByteArrayOutputStream()
    GZIPInputStream(file.inputStream()).use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer); if (n < 0) break
            if (output.size() + n > TRANSLATION_STORAGE_LIMIT) throw IOException("Translation draft exceeds limit")
            output.write(buffer, 0, n)
        }
    }
    return TranslationSnapshot.read(JSONObject(output.toString("UTF-8")))
}

/** Called under the session store lock, including owner deletion and out-of-order autosaves. */
internal fun writeTranslationSnapshot(file: File, snapshot: TranslationSnapshot) {
    if (file.isFile && readTranslationSnapshot(file).revision > snapshot.revision) return
    val bytes = snapshot.json().toString().toByteArray(Charsets.UTF_8)
    if (bytes.size > TRANSLATION_STORAGE_LIMIT) throw IOException("Translation draft exceeds limit")
    val temp = File(file.parentFile, "${file.name}.tmp")
    try {
        GZIPOutputStream(temp.outputStream()).use { it.write(bytes) }
        Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temp.toPath()) }
}
