package com.usefulunpacker.fileops

import android.content.Context
import android.content.SharedPreferences
import com.usefulunpacker.RECYCLE_BIN_CLEAN_INTERVAL_MS
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread
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

    /**
     * Copy-fallback progress for [moveToRecycleBin], scoped to the CURRENT target.
     *
     * [doneBytes]/[totalBytes] drive the bar so it moves in proportion to actual
     * work: a folder of many tiny files would otherwise crawl while gigabytes
     * stream past. [done]/[total] (file counts) ride along for the label, because
     * "12/40 files" is what the user actually recognizes.
     *
     * `scanTree()` has always computed the byte total ([ScanResult.size]); it
     * simply wasn't reaching the UI.
     */
    fun interface MoveProgress {
        fun onProgress(done: Int, total: Int, doneBytes: Long, totalBytes: Long)
    }

    private class ScanResult(val size: Long, val fileCount: Int)

    private fun scanTree(file: File): ScanResult {
        if (file.isFile) return ScanResult(file.length(), 1)
        if (!file.isDirectory) return ScanResult(0, 0)
        var size = 0L
        var count = 0
        file.walkBottomUp().forEach { f ->
            if (f.isFile) { size += runCatching { f.length() }.getOrDefault(0L); count++ }
        }
        return ScanResult(size, count)
    }

    fun moveToRecycleBin(context: Context, file: File, onProgress: MoveProgress? = null): Boolean {
        val dir = recycleDir(context)
        val id = UUID.randomUUID().toString()
        val entryDir = File(dir, id)
        if (!entryDir.mkdirs() && !entryDir.isDirectory) return false

        try {
            val dest = File(entryDir, file.name)
            fun baseMeta(): JSONObject = JSONObject().apply {
                put("id", id)
                put("originalPath", file.absolutePath)
                put("originalName", file.name)
                put("deletedAt", System.currentTimeMillis())
                put("isDirectory", file.isDirectory)
            }

            // FAST PATH FIRST: a same-volume rename is instant — no prescan.
            // (The old order walked the whole tree up front, which read as a
            // multi-second "stuck" bar on big folders.) Size is backfilled in
            // the background afterwards; the copy fallback below scans for
            // totals because its progress bar needs them anyway.
            if (file.renameTo(dest)) {
                val meta = baseMeta().apply { put("size", 0L) }
                File(entryDir, "_meta.json").writeText(meta.toString(2))
                updateManifest(dir) { manifest ->
                    manifest.getJSONArray("entries").put(meta)
                }
                thread {
                    runCatching {
                        val s = scanTree(dest)
                        // _meta.json 用独立副本写：meta 已被别名进 manifest（裸
                        // HashMap），在锁外再 put 会与并发 updateManifest/listEntries
                        // 竞态。manifest 内那份在下方 updateManifest 的锁内更新。
                        File(entryDir, "_meta.json").writeText(
                            JSONObject(meta.toString()).put("size", s.size).toString(2)
                        )
                        updateManifest(dir) { m ->
                            val e = m.getJSONArray("entries")
                            for (i in 0 until e.length()) {
                                val o = e.getJSONObject(i)
                                if (o.optString("id") == id) { o.put("size", s.size); break }
                            }
                        }
                    }
                }
                return true
            }

            // renameTo失败（跨盘）→ copy+delete：先扫描拿总数驱动进度条。
            // 复制失败必须抛出：吞掉错误再删原树 = 用户文件永久丢失。
            return try {
                val scan = scanTree(file)
                val meta = baseMeta().apply { put("size", scan.size) }
                File(entryDir, "_meta.json").writeText(meta.toString(2))

                var copyFailed: Exception? = null
                var done = 0
                // Accumulates copied bytes so the bar advances by volume, not by
                // file count. A failed copy contributes nothing (it didn't land).
                var doneBytes = 0L
                if (file.isDirectory) {
                    file.walkTopDown().forEach { f ->
                        if (copyFailed != null) return@forEach
                        val rel = try { f.relativeTo(file) } catch (e: Exception) { return@forEach }
                        val out = File(dest, rel.path)
                        if (f.isDirectory) out.mkdirs()
                        else {
                            out.parentFile?.mkdirs()
                            val ok = runCatching { f.copyTo(out, overwrite = false) }.isSuccess
                            done++
                            if (ok) doneBytes += runCatching { f.length() }.getOrDefault(0L)
                            onProgress?.onProgress(done, scan.fileCount, doneBytes, scan.size)
                            if (!ok && copyFailed == null) {
                                copyFailed = java.io.IOException("copy failed: ${f.path}")
                            }
                        }
                    }
                    if (copyFailed != null) throw copyFailed!!
                    file.deleteRecursively()
                } else {
                    file.copyTo(dest, overwrite = false)
                    // scanTree already measured it; report the same number rather
                    // than re-stat'ing, so the bar lands exactly on 100%.
                    onProgress?.onProgress(1, 1, scan.size, scan.size)
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
        } catch (e: Exception) {
            // Scan/meta-write failures must honor the Boolean contract too.
            entryDir.deleteRecursively()
            return false
        }
    }

    fun restore(context: Context, entryId: String): Boolean {
        val dir = recycleDir(context)
        val entryDir = File(dir, entryId)
        val metaFile = File(entryDir, "_meta.json")
        if (!metaFile.exists()) return false

        // A truncated/corrupted meta file must not kill the caller's worker
        // thread (restore runs on a bare thread{} — an uncaught throw there
        // takes down the process); report as a failed restore instead. The
        // getString reads can also throw (missing key) — same treatment.
        val meta = try { JSONObject(metaFile.readText()) } catch (e: Exception) { return false }
        val originalPath: File
        val originalName: String
        try {
            originalPath = File(meta.getString("originalPath"))
            originalName = meta.getString("originalName")
        } catch (e: Exception) { return false }

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
                // 跨盘 rename 必失败（bin 在 filesDir，原目录在外部存储），目录
                // 还原走的是这里：copyTo 对目录直接抛异常 → 文件夹永远还原不
                // 回来。目录用 copyRecursively；失败时清掉半成品再报失败。
                return try {
                    if (source.isDirectory) source.copyRecursively(dest, overwrite = false)
                    else source.copyTo(dest, overwrite = false)
                    entryDir.deleteRecursively()
                    removeFromManifest(context, entryId)
                    true
                } catch (e: Exception) {
                    dest.deleteRecursively()
                    false
                }
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
            // autoClean runs on a bare thread{} (onResume) — a malformed
            // manifest entry must skip, not kill the process.
            val entry = entries.optJSONObject(i) ?: continue
            val id = runCatching { entry.getString("id") }.getOrNull() ?: continue
            val deletedAt = runCatching { entry.getLong("deletedAt") }.getOrDefault(0L)
            if (deletedAt < cutoff) {
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
        return try {
            (0 until entries.length()).map { i ->
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
        } catch (e: Exception) { emptyList() }
    }

    fun getUsedSize(context: Context): Long {
        // Sum the recorded per-entry sizes instead of walking the tree — the
        // bin regularly holds fully-extracted multi-GB packages, and a full
        // walk read as "recycle bin stuck" in settings.
        val dir = recycleDir(context)
        if (!dir.exists()) return 0
        val manifest = readManifest(dir)
        if (manifest != null) {
            val entries = manifest.getJSONArray("entries")
            var sum = 0L
            for (i in 0 until entries.length()) {
                sum += entries.optJSONObject(i)?.optLong("size", 0) ?: 0L
            }
            return sum
        }
        // No manifest: fall back to per-entry meta files (still no tree walk).
        var sum = 0L
        dir.listFiles()?.filter { it.isDirectory && it.name != "." && it.name != ".." }?.forEach { entryDir ->
            val metaFile = File(entryDir, "_meta.json")
            if (metaFile.exists()) {
                runCatching { sum += JSONObject(metaFile.readText()).optLong("size", 0) }
            }
        }
        return sum
    }

    fun getItemCount(context: Context): Int {
        val dir = recycleDir(context)
        val manifest = readManifest(dir) ?: return dir.listFiles()?.count { it.isDirectory && it.name != "." && it.name != ".." } ?: 0
        return manifest.getJSONArray("entries").length()
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
