package com.usefulunpacker

import java.io.File
import java.io.IOException
import java.nio.channels.Channels
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal enum class DirectoryChange { ADDED, REMOVED, MODIFIED, TYPE_CHANGED, UNREADABLE, SKIPPED }
internal data class ComparedFileStamp(val size: Long, val modified: java.nio.file.attribute.FileTime, val key: Any?) {
    companion object { fun of(attributes: BasicFileAttributes) = ComparedFileStamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey()) }
}
internal data class DirectoryComparisonEntry(val path: String, val state: DirectoryChange, val directory: Boolean,
    val sourceStamp: ComparedFileStamp? = null, val targetStamp: ComparedFileStamp? = null, val reason: String = "") {
    fun json() = JSONObject().put("path", path).put("state", state.name).put("directory", directory)
        .put("sourceSize", sourceStamp?.size ?: 0).put("targetSize", targetStamp?.size ?: 0)
        .apply { if (reason.isNotEmpty()) put("reason", reason) }
}
internal data class DirectoryComparisonReport(val source: File, val target: File,
    val entries: List<DirectoryComparisonEntry>, val counts: Map<DirectoryChange, Int>, val unchanged: Int,
    val visited: Int, val bytesRead: Long, val limited: Boolean) {
    val differences: Int get() = listOf(DirectoryChange.ADDED, DirectoryChange.REMOVED, DirectoryChange.MODIFIED, DirectoryChange.TYPE_CHANGED).sumOf { counts[it] ?: 0 }
    val complete: Boolean get() = !limited && (counts[DirectoryChange.UNREADABLE] ?: 0) == 0 && (counts[DirectoryChange.SKIPPED] ?: 0) == 0
    val status: String get() = if (!complete) "INCOMPLETE" else if (differences > 0) "DIFFERENT" else "IDENTICAL"
    fun json(offset: Int = 0, limit: Int = 20): JSONObject {
        require(offset >= 0 && limit in 1..50)
        val end = (offset.toLong() + limit).coerceAtMost(entries.size.toLong()).toInt()
        return JSONObject().put("status", status).put("complete", complete).put("limited", limited)
            .put("visited", visited).put("bytesRead", bytesRead).put("unchanged", unchanged)
            .put("counts", JSONObject().apply { DirectoryChange.entries.forEach { put(it.name, counts[it] ?: 0) } })
            .put("entries", JSONArray(entries.drop(offset).take(limit).map(DirectoryComparisonEntry::json)))
            .apply { if (end < entries.size) put("nextAfter", end.toString()) }
    }
}

/** Read-only, bounded union traversal. Equal sizes still require a byte comparison; dates never imply equality. */
internal object DirectoryComparator {
    private const val MAX_NODES = 50_000
    private const val MAX_DETAILS = 2000
    private const val MAX_DEPTH = 64
    private const val MAX_PATH = 1024
    fun compare(source: File, target: File, cancelled: () -> Boolean = { false }, maxReadBytes: Long = Long.MAX_VALUE,
        maxDurationMs: Long = Long.MAX_VALUE, progress: (Int, Long) -> Unit = { _, _ -> }): DirectoryComparisonReport {
        require(maxReadBytes >= 0 && maxDurationMs >= 0)
        val started = System.nanoTime()
        fun expired() = maxDurationMs != Long.MAX_VALUE && (System.nanoTime() - started) / 1_000_000 >= maxDurationMs
        fun active() { if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException() }
        active()
        val a = source.canonicalFile
        val b = target.canonicalFile
        if (!a.isDirectory || !b.isDirectory) throw IOException("Choose two existing directories")
        val rows = ArrayList<DirectoryComparisonEntry>()
        val counts = DirectoryChange.entries.associateWith { 0 }.toMutableMap()
        val directories = linkedMapOf<File, Pair<String, ComparedFileStamp>>()
        val pending = ArrayDeque<String>().apply { add("") }
        var unchanged = 0
        var visited = 0
        var bytes = 0L
        var limited = false
        var lastProgress = 0L
        fun publish() {
            val now = System.nanoTime()
            if (now - lastProgress > 100_000_000) { lastProgress = now; progress(visited, bytes) }
        }
        fun record(path: String, state: DirectoryChange, isDirectory: Boolean, left: BasicFileAttributes? = null,
            right: BasicFileAttributes? = null, reason: String = "") {
            counts[state] = counts.getValue(state) + 1
            val row = DirectoryComparisonEntry(path.ifEmpty { "." }, state, isDirectory,
                left?.let(ComparedFileStamp::of), right?.let(ComparedFileStamp::of), reason)
            if (rows.size < MAX_DETAILS) rows.add(row)
            else if (state in setOf(DirectoryChange.UNREADABLE, DirectoryChange.SKIPPED)) {
                val slot = rows.indexOfLast { it.state !in setOf(DirectoryChange.UNREADABLE, DirectoryChange.SKIPPED) }
                if (slot >= 0) rows[slot] = row
            }
        }
        fun attributes(root: File, path: String): BasicFileAttributes? = try {
            val file = safeFile(root, path)
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) { null }
        fun children(root: File, path: String, attr: BasicFileAttributes?): Set<String> {
            if (attr?.isDirectory != true) return emptySet()
            val file = safeFile(root, path)
            if (file.canonicalFile != file) throw IOException("Comparison path changed")
            directories[file] = path to ComparedFileStamp.of(attr)
            val names = sortedSetOf<String>()
            Files.newDirectoryStream(file.toPath()).use { stream ->
                for (child in stream) {
                    active()
                    if (expired()) throw ComparisonLimit()
                    if (names.size >= MAX_NODES) throw ComparisonLimit()
                    names.add(child.fileName.toString())
                }
            }
            return names
        }
        while (pending.isNotEmpty()) {
            active()
            if (visited >= MAX_NODES || expired()) {
                limited = true
                record(pending.first(), DirectoryChange.SKIPPED, false, reason = "SCAN_BUDGET")
                break
            }
            val path = pending.removeFirst()
            visited++
            publish()
            if (path.length > MAX_PATH || path.count { it == '/' } >= MAX_DEPTH || path.any { it.isISOControl() || it == '\\' }) {
                record(path.take(MAX_PATH), DirectoryChange.SKIPPED, false, reason = "PATH_LIMIT")
                continue
            }
            try {
                val left = attributes(a, path)
                val right = attributes(b, path)
                if (left?.isSymbolicLink == true || right?.isSymbolicLink == true) {
                    record(path, DirectoryChange.SKIPPED, false, reason = "SYMLINK")
                    continue
                }
                if ((left != null && !left.isDirectory && !left.isRegularFile) || (right != null && !right.isDirectory && !right.isRegularFile)) {
                    record(path, DirectoryChange.SKIPPED, false, reason = "SPECIAL_FILE")
                    continue
                }
                val directory = left?.isDirectory == true || right?.isDirectory == true
                if (left != null && right != null && left.isDirectory != right.isDirectory) {
                    record(path, DirectoryChange.TYPE_CHANGED, true, left, right)
                    continue
                }
                if (directory) {
                    // Do not infer absent children from an incomplete directory listing.
                    val names = children(a, path, left) + children(b, path, right)
                    if (names.size + pending.size + visited > MAX_NODES) throw ComparisonLimit()
                    if (path.isNotEmpty()) when {
                        left == null -> record(path, DirectoryChange.ADDED, true, left, right)
                        right == null -> record(path, DirectoryChange.REMOVED, true, left, right)
                        left.isDirectory != right.isDirectory -> record(path, DirectoryChange.TYPE_CHANGED, true, left, right)
                    }
                    for (name in names.sorted()) pending.add(if (path.isEmpty()) name else "$path/$name")
                } else when {
                    left == null && right == null -> record(path, DirectoryChange.UNREADABLE, false, reason = "FILE_CHANGED")
                    left == null -> record(path, DirectoryChange.ADDED, false, left, right)
                    right == null -> record(path, DirectoryChange.REMOVED, false, left, right)
                    else -> {
                        val same = left.size() == right.size() && equalBytes(safeFile(a, path), safeFile(b, path), ::active) { count ->
                            bytes += count
                            if (bytes > maxReadBytes || expired()) throw ComparisonLimit()
                            publish()
                        }
                        if (ComparedFileStamp.of(left) != attributes(a, path)?.let(ComparedFileStamp::of) ||
                            ComparedFileStamp.of(right) != attributes(b, path)?.let(ComparedFileStamp::of)) {
                            record(path, DirectoryChange.UNREADABLE, false, reason = "FILE_CHANGED")
                        } else if (same) unchanged++ else record(path, DirectoryChange.MODIFIED, false, left, right)
                    }
                }
            } catch (_: ComparisonLimit) { limited = true; record(path, DirectoryChange.SKIPPED, false, reason = "SCAN_BUDGET"); break }
            catch (_: IOException) { record(path, DirectoryChange.UNREADABLE, false, reason = "IO") }
            catch (_: DirectoryIteratorException) { record(path, DirectoryChange.UNREADABLE, false, reason = "IO") }
            catch (_: SecurityException) { record(path, DirectoryChange.UNREADABLE, false, reason = "ACCESS_DENIED") }
        }
        var treeChanged = false
        for ((file, snapshot) in directories) {
            active()
            if (expired()) { limited = true; record(snapshot.first, DirectoryChange.SKIPPED, true, reason = "VERIFY_BUDGET"); break }
            val stable = try { ComparedFileStamp.of(Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)) == snapshot.second }
                catch (_: IOException) { false }
                catch (_: SecurityException) { false }
            if (!stable) { treeChanged = true; record(snapshot.first, DirectoryChange.UNREADABLE, true, reason = "DIRECTORY_CHANGED") }
        }
        if (treeChanged) {
            // A live tree is not a snapshot. Withdraw ordinary findings rather than publishing guessed deletions.
            rows.removeAll { it.state !in setOf(DirectoryChange.UNREADABLE, DirectoryChange.SKIPPED) }
            counts.keys.filter { it !in setOf(DirectoryChange.UNREADABLE, DirectoryChange.SKIPPED) }.forEach { counts[it] = 0 }
            unchanged = 0
        }
        active()
        progress(visited, bytes)
        return DirectoryComparisonReport(a, b, rows, counts, unchanged, visited, bytes, limited || counts.values.sum() > rows.size)
    }

    internal fun safeFile(root: File, relative: String): File {
        val base = root.canonicalFile
        if (base != root || !base.isDirectory) throw IOException("Comparison directory changed")
        var file = base
        if (relative.isNotEmpty()) for (part in relative.split('/')) {
            if (part.isEmpty() || part == "." || part == "..") throw IOException("Invalid comparison path")
            file = File(file, part)
            if (file.parentFile?.canonicalFile != file.parentFile) throw IOException("Comparison path changed")
        }
        return file
    }
    private fun equalBytes(a: File, b: File, active: () -> Unit, read: (Long) -> Unit): Boolean {
        fun chunk(input: java.io.InputStream, buffer: ByteArray): Int {
            var size = 0
            while (size < buffer.size) { active(); val n = input.read(buffer, size, buffer.size - size); if (n < 0) break; size += n; read(n.toLong()) }
            return size
        }
        return Channels.newInputStream(Files.newByteChannel(a.toPath(), setOf(READ, NOFOLLOW_LINKS))).use { left ->
            Channels.newInputStream(Files.newByteChannel(b.toPath(), setOf(READ, NOFOLLOW_LINKS))).use rightUse@ { right ->
                val x = ByteArray(64 * 1024)
                val y = ByteArray(x.size)
                while (true) {
                    val n = chunk(left, x); val m = chunk(right, y)
                    if (n != m) return@rightUse false
                    if (n == 0) return@rightUse true
                    for (i in 0 until n) if (x[i] != y[i]) return@rightUse false
                }
                @Suppress("UNREACHABLE_CODE") false
            }
        }
    }
    private class ComparisonLimit : RuntimeException()
}
