package com.usefulunpacker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.ListView
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
/** Draggable fast-scroll handle for long lists (archive preview / scan
 *  results / script lists / batch preview). Always visible so a finger can
 *  grab it and jump through thousands of entries. */
fun ListView.enableFastScroll() {
    isFastScrollEnabled = true
    isFastScrollAlwaysVisible = true
    setFastScrollStyle(R.style.FastScrollStyle)
}

/** On a cancelled extract: a FRESH output folder is removed entirely (done
 *  files + the in-progress partial), a pre-existing folder keeps its prior
 *  content (the Rust side cleans the in-progress file). */
fun cleanupCancelledOutput(out: File, existedBefore: Boolean) {
    if (!out.exists()) return
    if (!existedBefore) deleteOutputChecked(out, recursive = true)
}

/** Reads at most [maxBytes] from [file] — for preview/search of potentially
 *  huge files (a .log/.csv can be hundreds of MB) without loading it whole. */
fun readPrefix(file: File, maxBytes: Long): ByteArray {
    if (file.length() <= maxBytes) return file.readBytes()
    val len = maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return FileInputStream(file).use { ins ->
        val buf = ByteArray(len)
        // 单次 read(byte[]) 不保证填满缓冲（契约允许短读）——循环读满，
        // 否则预览/搜索前缀被静默截断。
        var n = 0
        while (n < buf.size) {
            val r = ins.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        if (n < buf.size) buf.copyOf(n) else buf
    }
}

fun fileSize(f: File): Long = try {
    android.system.Os.stat(f.absolutePath).st_size
} catch (e: Exception) { com.usefulunpacker.AppDiagnostics.failure("FileUtils", e);
    com.usefulunpacker.observedAttempt("FileUtils") {
        ProcessBuilder("stat", "-c%s", f.absolutePath).redirectErrorStream(true).start()
            .let { p -> val result = String(p.inputStream.readBytes()).trim().toLongOrNull() ?: 0L; p.waitFor(); result }
    }.getOrDefault(0L)
}

fun fmt(b: Long): String = when {
    b < 0 -> "?" // unknown size (e.g. LZ4 without a Content Size header)
    b >= 1_073_741_824 -> "${"%.2f".format(b / 1_073_741_824.0)} GB"
    b >= 1_048_576 -> "${"%.1f".format(b / 1_048_576.0)} MB"
    b >= 1024 -> "${"%.1f".format(b / 1024.0)} KB"
    else -> "$b B"
}

/** Rust safe_join parity for entry paths: rejects `..` components, absolute
 *  paths, drive letters (`:`) and NUL. Returns the normalized relative path
 *  or null when the entry must be skipped. Kotlin-side file creation (search
 *  placeholder files) must apply the same rules as the Rust extractors. */
fun sanitizeEntryPath(p: String): String? {
    val s = p.replace('\\', '/')
    if (s.isEmpty() || s.contains('\u0000') || s.startsWith('/') || s.contains(':')) return null
    if (s.split('/').any { it == ".." }) return null
    return s
}

fun uniqueFile(parent: File, name: String): File {
    var f = File(parent, name)
    if (!f.exists()) return f
    val dot = name.lastIndexOf('.')
    val base = if (dot >= 0) name.substring(0, dot) else name
    val ext = if (dot >= 0) name.substring(dot) else ""
    var n = 1
    while (true) {
        f = File(parent, "$base ($n)$ext")
        if (!f.exists()) return f
        n++
    }
}

/**
 * PFS/PF6 封包产物名。Artemis 分层补丁约定：游戏按 `root.pfs` → `root.pfs.000` →
 * `root.pfs.001` … 的顺序挂载，**编号越大越晚挂载、越优先**（越后面的覆盖前面的），
 * 所以翻译补丁就是往这个槽位里写。等价于 pfs-rs 不带 `-o` 的默认输出行为
 * （`-o` 显式给名则原样使用；`-f` 才覆盖，本 app 不提供覆盖）。
 *
 * 在【拿到调度槽位之后】调用：同 key 的 pfs 封包是串行的，此刻扫描不会和别人撞名。
 * 直接写最终名（而非封完再改名）——改名会失败且失败后无提示，半截 `root.pfs`
 * 会被游戏当补丁挂载，比留个 `X (1).pfs` 危险得多。
 *
 * [artemisNaming] 为 false 时原样返回（尊重调用方给的 `-o` 风格名字）。
 */
fun resolvePfsOutName(outFile: File, artemisNaming: Boolean): File {
    if (!artemisNaming) return outFile
    val dir = outFile.parentFile ?: return outFile
    val base = File(dir, "root.pfs")
    if (!base.exists()) return base
    var n = 0
    while (File(dir, "root.pfs.%03d".format(n)).exists()) n++
    return File(dir, "root.pfs.%03d".format(n))
}

/**
 * RPG Maker only loads an RGSS archive named exactly `Game.rgss3a` (or
 * `.rgssad` / `.rgss2a`) from beside the game `.exe` — anything else is simply
 * never opened, with no error the user would notice. So the packer resolves the
 * final name up front instead of writing a file the game ignores.
 *
 * Unlike the PFS layered-patch case there is NO meaningful fallback name: a
 * `Game.rgss3a.001` would not be mounted either. So if the slot is taken the
 * caller's own name is kept and the caller is expected to say so — better a
 * visible odd name than a silently useless one.
 *
 * @param gameNaming false keeps the caller's name outright (an explicit `-o`).
 * @return the file to write, and whether it differs from [outFile].
 */
fun resolveRgssOutName(outFile: File, gameNaming: Boolean, ext: String): Pair<File, Boolean> {
    if (!gameNaming) return outFile to false
    val dir = outFile.parentFile ?: return outFile to false
    val target = File(dir, "Game.$ext")
    // Already the right name, or the slot is taken: leave it to the caller.
    if (target.name == outFile.name) return outFile to false
    if (target.exists()) return outFile to false
    return target to true
}

fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

fun hashFile(f: File, algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm)
    FileInputStream(f).use { stream ->
        val buf = ByteArray(65536)
        var n: Int
        while (stream.read(buf).also { n = it } != -1) { digest.update(buf, 0, n) }
    }
    return hex(digest.digest())
}

fun copyToClipboard(context: Context, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .setPrimaryClip(ClipData.newPlainText("", text))
}

/** Best-effort MIME type for sharing a file (known map + extension table). */
fun mimeOf(f: File): String {
    val ext = f.extension.lowercase()
    android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
    return when (ext) {
        "txt", "ks", "tjs", "md", "json", "csv", "log", "ini", "cfg", "xml", "yaml", "yml", "toml",
        "html", "css", "js", "py", "lua", "sh", "java", "kt", "rs" -> "text/plain"
        else -> "application/octet-stream"
    }
}
