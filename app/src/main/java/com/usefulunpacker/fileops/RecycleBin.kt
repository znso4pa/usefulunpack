package com.usefulunpacker.fileops

import android.content.Context
import android.content.SharedPreferences
import com.usefulunpacker.RECYCLE_BIN_CLEAN_INTERVAL_MS
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class RecycleEntry(
    val id: String,
    val originalPath: File,
    val originalName: String,
    val deletedAt: Long,
    val isDirectory: Boolean,
    val size: Long
)

object RecycleBin {
    private const val RECYCLE_DIR = ".recycle"
    private const val MANIFEST = "manifest.json"

    fun recycleDir(context: Context): File =
        File(context.filesDir, RECYCLE_DIR).also { it.mkdirs() }

    fun isEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean("recycle_bin_enabled", true)

    fun autoCleanDays(prefs: SharedPreferences): Int =
        prefs.getInt("recycle_bin_auto_clean_days", 30)

    fun moveToRecycleBin(context: Context, file: File): Boolean {
        val dir = recycleDir(context)
        val id = UUID.randomUUID().toString()
        val entryDir = File(dir, id)
        entryDir.mkdirs()

        val meta = JSONObject().apply {
            put("id", id)
            put("originalPath", file.absolutePath)
            put("originalName", file.name)
            put("deletedAt", System.currentTimeMillis())
            put("isDirectory", file.isDirectory)
            put("size", calculateSize(file))
        }
        File(entryDir, "_meta.json").writeText(meta.toString(2))

        val dest = File(entryDir, file.name)
        val success = file.renameTo(dest)

        if (success) {
            // renameTo成功，直接更新manifest并返回
            updateManifest(dir) { manifest ->
                manifest.getJSONArray("entries").put(meta)
            }
            return true
        }

        // renameTo失败，尝试copy+delete
        return try {
            if (file.isDirectory) {
                file.copyRecursively(dest, overwrite = false)
                file.deleteRecursively()
            } else {
                file.copyTo(dest, overwrite = false)
                file.delete()
            }
            updateManifest(dir) { manifest ->
                manifest.getJSONArray("entries").put(meta)
            }
            true
        } catch (e: Exception) {
            entryDir.deleteRecursively()
            false
        }
    }

    fun restore(context: Context, entryId: String): Boolean {
        val dir = recycleDir(context)
        val entryDir = File(dir, entryId)
        val metaFile = File(entryDir, "_meta.json")
        if (!metaFile.exists()) return false

        val meta = JSONObject(metaFile.readText())
        val originalPath = File(meta.getString("originalPath"))
        val originalName = meta.getString("originalName")

        // Proper component-level check: a path is "inside the recycle bin" only
        // if the recycle base is a real ancestor (string startsWith would mis-flag
        // a sibling like "/.recycleXXX/..." and strand the entry forever).
        val inRecycleBin = try {
            val canonicalOriginal = originalPath.canonicalPath
            val recycleBinBase = dir.canonicalPath
            canonicalOriginal == recycleBinBase ||
                canonicalOriginal.startsWith(recycleBinBase + File.separator)
        } catch (e: Exception) { false }
        if (!inRecycleBin) {
            val source = File(entryDir, originalName)
            if (!source.exists()) return false

            originalPath.parentFile?.mkdirs()

            val dest = if (originalPath.exists()) {
                val parent = originalPath.parentFile
                if (parent != null) uniqueFile(parent, originalName) else originalPath
            } else {
                originalPath
            }

            val success = source.renameTo(dest)
            if (!success) {
                return try {
                    source.copyTo(dest, overwrite = false)
                    entryDir.deleteRecursively()
                    removeFromManifest(context, entryId)
                    true
                } catch (e: Exception) { false }
            }

            entryDir.deleteRecursively()
            removeFromManifest(context, entryId)
            return true
        }
        return false
    }

    fun emptyRecycleBin(context: Context) {
        synchronized(this) {
            recycleDir(context).deleteRecursively()
        }
    }

    fun autoClean(context: Context, prefs: SharedPreferences) {
        val days = autoCleanDays(prefs)
        if (days <= 0) return

        val lastClean = prefs.getLong("recycle_bin_last_clean", 0)
        val now = System.currentTimeMillis()
        if (now - lastClean < RECYCLE_BIN_CLEAN_INTERVAL_MS) return

        prefs.edit().putLong("recycle_bin_last_clean", now).commit()

        val cutoff = now - days * RECYCLE_BIN_CLEAN_INTERVAL_MS
        val dir = recycleDir(context)
        val manifest = readManifest(dir) ?: return
        val entries = manifest.getJSONArray("entries")
        val toRemove = mutableListOf<String>()

        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            if (entry.getLong("deletedAt") < cutoff) {
                val id = entry.getString("id")
                toRemove.add(id)
                File(dir, id).deleteRecursively()
            }
        }

        if (toRemove.isNotEmpty()) {
            updateManifest(dir) { m ->
                val newEntries = JSONArray()
                val e = m.getJSONArray("entries")
                for (i in 0 until e.length()) {
                    if (e.getJSONObject(i).getString("id") !in toRemove) {
                        newEntries.put(e.getJSONObject(i))
                    }
                }
                m.put("entries", newEntries)
            }
        }
    }

    fun removeFromManifest(context: Context, entryId: String) {
        val dir = recycleDir(context)
        updateManifest(dir) { manifest ->
            val entries = manifest.getJSONArray("entries")
            val newEntries = JSONArray()
            for (i in 0 until entries.length()) {
                if (entries.getJSONObject(i).getString("id") != entryId) {
                    newEntries.put(entries.getJSONObject(i))
                }
            }
            manifest.put("entries", newEntries)
        }
    }

    fun listEntries(context: Context): List<RecycleEntry> {
        val dir = recycleDir(context)
        val manifest = readManifest(dir)

        if (manifest == null) {
            return dir.listFiles()?.filter { it.isDirectory && it.name != "." && it.name != ".." }
                ?.mapNotNull { entryDir ->
                    val metaFile = File(entryDir, "_meta.json")
                    if (metaFile.exists()) {
                        try {
                            val meta = JSONObject(metaFile.readText())
                            RecycleEntry(
                                id = meta.getString("id"),
                                originalPath = File(meta.getString("originalPath")),
                                originalName = meta.getString("originalName"),
                                deletedAt = meta.getLong("deletedAt"),
                                isDirectory = meta.getBoolean("isDirectory"),
                                size = meta.optLong("size", 0)
                            )
                        } catch (e: Exception) { null }
                    } else null
                }?.sortedByDescending { it.deletedAt } ?: emptyList()
        }

        val entries = manifest.getJSONArray("entries")
        return (0 until entries.length()).map { i ->
            val e = entries.getJSONObject(i)
            RecycleEntry(
                id = e.getString("id"),
                originalPath = File(e.getString("originalPath")),
                originalName = e.getString("originalName"),
                deletedAt = e.getLong("deletedAt"),
                isDirectory = e.getBoolean("isDirectory"),
                size = e.optLong("size", 0)
            )
        }.sortedByDescending { it.deletedAt }
    }

    fun getUsedSize(context: Context): Long {
        val dir = recycleDir(context)
        return if (dir.exists()) dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else 0
    }

    fun getItemCount(context: Context): Int {
        val dir = recycleDir(context)
        val manifest = readManifest(dir) ?: return dir.listFiles()?.count { it.isDirectory && it.name != "." && it.name != ".." } ?: 0
        return manifest.getJSONArray("entries").length()
    }

    private fun calculateSize(file: File): Long {
        return if (file.isFile) file.length()
        else if (file.isDirectory) file.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        else 0
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val ext = name.substringAfterLast('.', "")
        val base = if (ext.isNotEmpty()) name.substringBeforeLast('.') else name
        var i = 1
        while (candidate.exists()) {
            candidate = if (ext.isNotEmpty()) File(dir, "$base ($i).$ext") else File(dir, "$base ($i)")
            i++
        }
        return candidate
    }

    private fun readManifest(dir: File): JSONObject? {
        synchronized(this) {
            val file = File(dir, MANIFEST)
            if (!file.exists()) return null
            return try {
                val content = file.readText()
                if (content.isBlank()) null
                else JSONObject(content)
            } catch (e: Exception) {
                file.renameTo(File(dir, "$MANIFEST.bak"))
                null
            }
        }
    }

    private fun updateManifest(dir: File, transform: (JSONObject) -> Unit) {
        synchronized(this) {
            val file = File(dir, MANIFEST)
            val manifest = if (file.exists()) {
                val content = file.readText()
                if (content.isBlank()) createEmptyManifest()
                else try { JSONObject(content) } catch (e: Exception) { recoverManifest(dir) }
            } else {
                createEmptyManifest()
            }

            transform(manifest)
            // Atomic write: never leave a half-written manifest (a crash mid-write
            // would corrupt the recycle index and strand every entry).
            val tmp = File(dir, "$MANIFEST.tmp")
            tmp.writeText(manifest.toString(2))
            if (!tmp.renameTo(file)) {
                // rename failed (cross-device etc.) — fall back to direct write
                file.writeText(manifest.toString(2))
                tmp.delete()
            }
        }
    }

    /// When the manifest JSON is corrupt, rebuild it from the on-disk entry
    /// directories (each has a `_meta.json`) so no entry is stranded and lost.
    private fun recoverManifest(dir: File): JSONObject {
        val manifest = createEmptyManifest()
        val entries = manifest.getJSONArray("entries")
        dir.listFiles()?.filter { it.isDirectory && it.name != "." && it.name != ".." }
            ?.forEach { entryDir ->
                val metaFile = File(entryDir, "_meta.json")
                if (metaFile.exists()) {
                    try {
                        val meta = JSONObject(metaFile.readText())
                        if (meta.getString("id").isNotEmpty()) {
                            entries.put(meta)
                        }
                    } catch (e: Exception) { /* skip bad meta */ }
                }
            }
        return manifest
    }

    private fun createEmptyManifest(): JSONObject {
        return JSONObject().apply {
            put("version", 1)
            put("entries", JSONArray())
        }
    }
}
