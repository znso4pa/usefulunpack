package com.usefulunpacker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

fun fileSize(f: File): Long = try {
    android.system.Os.stat(f.absolutePath).st_size
} catch (e: Exception) {
    runCatching {
        ProcessBuilder("stat", "-c%s", f.absolutePath).redirectErrorStream(true).start()
            .let { String(it.inputStream.readBytes()).trim().toLongOrNull() ?: 0L }
    }.getOrDefault(0L)
}

fun fmt(b: Long): String = when {
    b >= 1_073_741_824 -> "${"%.2f".format(b / 1_073_741_824.0)} GB"
    b >= 1_048_576 -> "${"%.1f".format(b / 1_048_576.0)} MB"
    b >= 1024 -> "${"%.1f".format(b / 1024.0)} KB"
    else -> "$b B"
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

// ─── Multi-volume detection ───

private val PART_RAR_RE = Regex("""^(.+)\.part(\d+)\.rar$""", RegexOption.IGNORE_CASE)
private val OLD_RAR_RE = Regex("""^(.+)\.([r-z])\d{2}$""", RegexOption.IGNORE_CASE)
private val SZ_VOL_RE = Regex("""^(.+)\.(?:7z\.)?(\d{2,3})$""", RegexOption.IGNORE_CASE)
private val SZ_MAGIC = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)

fun isRarVolumeName(name: String): Boolean = PART_RAR_RE.containsMatchIn(name) || OLD_RAR_RE.containsMatchIn(name)
fun isSevenZVolumeName(name: String): Boolean = SZ_VOL_RE.containsMatchIn(name)

/** Returns "rar"/"7z" when the file is a volume part, else null. */
fun isVolumeFile(f: File): String? = when {
    isRarVolumeName(f.name) -> "rar"
    isSevenZVolumeName(f.name) -> "7z"
    else -> null
}

fun startsWith7zMagic(f: File): Boolean = try {
    val sig = ByteArray(6)
    FileInputStream(f).use { it.read(sig) }
    sig.contentEquals(SZ_MAGIC)
} catch (_: Exception) { false }

/**
 * Resolves the complete volume set for a RAR archive (any part works).
 * Supports modern `name.partN.rar` and old-style `name.rar + name.r00/r01...`.
 * Returns the selected file alone when it is not a volume set.
 */
fun resolveRarVolumes(f: File): List<File> {
    val dir = f.parentFile ?: return listOf(f)
    val name = f.name
    val partMatch = PART_RAR_RE.find(name)
    val oldMatch = OLD_RAR_RE.find(name)
    val base = when {
        partMatch != null -> partMatch.groupValues[1]
        oldMatch != null -> oldMatch.groupValues[1]
        name.lowercase().endsWith(".rar") -> name.dropLast(4)
        else -> return listOf(f)
    }
    val siblings = dir.listFiles()?.toList().orEmpty()
    val modern = siblings.filter { PART_RAR_RE.find(it.name)?.groupValues?.get(1) == base }
        .mapNotNull { f2 ->
            val n = PART_RAR_RE.find(f2.name)!!.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            n to f2
        }.sortedBy { it.first }.map { it.second }
    val old = siblings.filter { it.name.lowercase().startsWith(base.lowercase() + ".") }
        .mapNotNull { f2 ->
            val m = OLD_RAR_RE.find(f2.name) ?: return@mapNotNull null
            val letter = m.groupValues[2][0]
            val digits = m.groupValues[2].drop(1).toInt()
            (letter.code - 'r'.code) * 100 + digits to f2
        }.sortedBy { it.first }.map { it.second }
    val first = siblings.filter { it.name == "$base.rar" || it.name == "$base.part1.rar" }
    val combined = (first + modern + old).distinctBy { it.path }
    if (combined.isEmpty()) return listOf(f)
    return if (combined.none { it.path == f.path }) combined + f else combined
}

/**
 * Resolves the split parts of a 7z archive (`name.7z.001` / `name.001`).
 * Only returns the set when multiple parts exist and the first part carries
 * the 7z magic signature; otherwise returns the selected file alone.
 */
fun resolveSevenZVolumes(f: File): List<File> {
    val dir = f.parentFile ?: return listOf(f)
    val selBase = SZ_VOL_RE.find(f.name)?.groupValues?.get(1) ?: return listOf(f)
    val siblings = dir.listFiles()?.toList().orEmpty()
    val parts = siblings.mapNotNull { f2 ->
        val m = SZ_VOL_RE.find(f2.name) ?: return@mapNotNull null
        if (m.groupValues[1] != selBase) return@mapNotNull null
        val num = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
        num to f2
    }.sortedBy { it.first }
    if (parts.size < 2) return listOf(f)
    val firstPart = parts.first().second
    if (!startsWith7zMagic(firstPart)) return listOf(f)
    return parts.map { it.second }
}

fun volumePathList(src: File, fmt: String): List<File> = when (fmt) {
    "rar" -> resolveRarVolumes(src)
    "7z" -> resolveSevenZVolumes(src)
    else -> listOf(src)
}

fun volumeJoin(vols: List<File>): String = vols.joinToString("\n") { it.path }
