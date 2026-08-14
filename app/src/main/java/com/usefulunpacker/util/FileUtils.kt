package com.usefulunpacker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Global text-encoding pref values (general settings). UTF-16 auto-detects
 *  the BOM and defaults to little-endian without one (galgame convention). */
val TEXT_ENCODINGS = arrayOf("UTF-8", "SHIFT-JIS", "GBK", "UTF-16")

/** Decodes [data] STRICTLY as [encoding]: BOM handling for UTF-8/UTF-16,
 *  REPLACE for malformed/unmappable bytes — never silent loss, never errors.
 *  Every text preview and content search goes through here. */
fun decodeTextStrict(data: ByteArray, encoding: String): String {
    return when (encoding) {
        "UTF-16" -> {
            val (bytes, cs) = when {
                data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte() ->
                    data.copyOfRange(2, data.size) to Charsets.UTF_16LE
                data.size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte() ->
                    data.copyOfRange(2, data.size) to Charsets.UTF_16BE
                else -> data to Charsets.UTF_16LE
            }
            decodeReplace(bytes, cs)
        }
        "UTF-8" -> {
            val bytes = if (data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte())
                data.copyOfRange(3, data.size) else data
            decodeReplace(bytes, Charsets.UTF_8)
        }
        else -> decodeReplace(data, Charset.forName(encoding))
    }
}

private fun decodeReplace(bytes: ByteArray, cs: Charset): String {
    val decoder = cs.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        String(bytes, cs)
    }
}

/** True when the decoded text carries an unusual number of replacement
 *  characters (U+FFFD) — the file was likely decoded with the wrong
 *  encoding. Threshold: >= 2% of the text, with a floor of 8 to avoid
 *  noise on tiny files. */
fun textLooksGarbled(text: String): Boolean {
    if (text.isEmpty()) return false
    var bad = 0
    for (c in text) if (c == '\uFFFD') { bad++; if (bad >= 8 && bad * 50 >= text.length) return true }
    return bad >= 8 && bad * 50 >= text.length
}

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
private val SZ_VOL_RE = Regex("""^(.+)\.(?:7z\.)?(\d{2,})$""", RegexOption.IGNORE_CASE)
private val ZIP_VOL_RE = Regex("""^(.+\.zip)\.(\d{2,})$""", RegexOption.IGNORE_CASE)
private val PKW_Z_RE = Regex("""^(.+)\.z(\d{2})$""", RegexOption.IGNORE_CASE)
private val SZ_MAGIC = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
private val PK_MAGIC = byteArrayOf(0x50, 0x4B)

fun isRarVolumeName(name: String): Boolean = PART_RAR_RE.containsMatchIn(name) || OLD_RAR_RE.containsMatchIn(name)
fun isSevenZVolumeName(name: String): Boolean = SZ_VOL_RE.containsMatchIn(name)
fun isZipVolumeName(name: String): Boolean = ZIP_VOL_RE.containsMatchIn(name) || PKW_Z_RE.containsMatchIn(name)

fun startsWithZipMagic(f: File): Boolean = try {
    val sig = ByteArray(4)
    FileInputStream(f).use { it.read(sig) }
    sig[0] == PK_MAGIC[0] && sig[1] == PK_MAGIC[1]
} catch (_: Exception) { false }

/**
 * Returns "rar"/"7z"/"zip" when the file is a volume part (by name + the
 * first part's magic), else null. `.zip.001` also matches the 7z volume-name
 * regex, so zip must be checked first and each branch must resolve the set
 * (a `when` branch that yields null would short-circuit the others).
 */
fun isVolumeFile(f: File): String? {
    if (isRarVolumeName(f.name)) return "rar"
    if (isZipVolumeName(f.name) && resolveZipVolumes(f).size > 1) return "zip"
    if (isSevenZVolumeName(f.name) && resolveSevenZVolumes(f).size > 1) return "7z"
    return null
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
    // Prefer the partN scheme when `foo.part1.rar` exists — a plain `foo.rar`
    // next to `foo.part1.rar` is ambiguous (often a duplicate/first copy), so
    // don't prepend it and misorder the set.
    val part1 = siblings.firstOrNull { it.name == "$base.part1.rar" }
    val first = if (part1 != null) listOf(part1)
                else siblings.filter { it.name == "$base.rar" }
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

/**
 * Resolves the split parts of a zip archive:
 * - `name.zip.001/.002` (7-Zip byte-split) or
 * - `name.z01/.z02/.zip` (PKWARE true split).
 * Only returns a set when multiple parts exist and the first part carries the
 * zip magic; otherwise returns the selected file alone.
 */
fun resolveZipVolumes(f: File): List<File> {
    val dir = f.parentFile ?: return listOf(f)
    val siblings = dir.listFiles()?.toList().orEmpty()

    val byteMatch = ZIP_VOL_RE.find(f.name)
    if (byteMatch != null) {
        val base = byteMatch.groupValues[1]
        val parts = siblings.mapNotNull { f2 ->
            val m = ZIP_VOL_RE.find(f2.name) ?: return@mapNotNull null
            if (m.groupValues[1] != base) return@mapNotNull null
            m.groupValues[2].toIntOrNull()?.let { it to f2 }
        }.sortedBy { it.first }.map { it.second }
        if (parts.size >= 2 && startsWithZipMagic(parts.first())) return parts
        return listOf(f)
    }

    val pkMatch = PKW_Z_RE.find(f.name)
    if (pkMatch != null) {
        val base = pkMatch.groupValues[1]
        val zParts = siblings.mapNotNull { f2 ->
            val m = PKW_Z_RE.find(f2.name) ?: return@mapNotNull null
            if (m.groupValues[1] != base) return@mapNotNull null
            m.groupValues[2].toIntOrNull()?.let { it to f2 }
        }.sortedBy { it.first }.map { it.second }
        val final = siblings.firstOrNull { it.name == "$base.zip" }
        val all = if (final != null) zParts + final else zParts
        if (all.size >= 2 && startsWithZipMagic(all.first())) return all
        return listOf(f)
    }

    // Tapping the FINAL `.zip` of a PKWARE true split should resolve the whole
    // set too (its `.z01/.z02...` siblings carry the leading magic).
    if (f.name.lowercase().endsWith(".zip")) {
        val base = f.name.dropLast(4)
        val zParts = siblings.mapNotNull { f2 ->
            val m = PKW_Z_RE.find(f2.name) ?: return@mapNotNull null
            if (m.groupValues[1] != base) return@mapNotNull null
            m.groupValues[2].toIntOrNull()?.let { it to f2 }
        }.sortedBy { it.first }.map { it.second }
        val all = zParts + f
        if (all.size >= 2 && startsWithZipMagic(all.first())) return all
    }
    return listOf(f)
}

fun volumePathList(src: File, fmt: String): List<File> = when (fmt) {
    "rar" -> resolveRarVolumes(src)
    "7z" -> resolveSevenZVolumes(src)
    "zip" -> resolveZipVolumes(src)
    else -> listOf(src)
}

fun volumeJoin(vols: List<File>): String = vols.joinToString("\n") { it.path }

private fun passwordFormatOf(f: File): String? {
    val n = f.name.lowercase()
    return when {
        n.endsWith(".rar") || isRarVolumeName(f.name) -> "rar"
        n.endsWith(".7z") || isSevenZVolumeName(f.name) -> "7z"
        n.endsWith(".zip") || isZipVolumeName(f.name) -> "zip"
        else -> null
    }
}

/**
 * Detects whether an archive (or volume set) needs a password. Covers the
 * password-capable formats zip/7z/rar (single or split). Exceptions are
 * swallowed — a broken file is simply "not password protected".
 */
fun isPasswordProtected(f: File): Boolean {
    return try {
        when (passwordFormatOf(f) ?: return false) {
            "rar" -> rarVolumesNeedsPassword(f.path)
            "7z" -> szVolumesNeedsPassword(f.path)
            else -> zipVolumesNeedsPassword(f.path)
        }
    } catch (_: Exception) { false }
}

/**
 * Detects the archive format from a file name (delegates to the shared
 * [formatOfName] mapping). Volume parts (`foo.7z.001`) are not handled here —
 * callers should prefer the result of [isVolumeFile] and only fall back to
 * this. Returns null when the extension is unknown, so the caller can fall
 * back to the manual format picker.
 */
fun detectFormat(f: File): String? = formatOfName(f.name)

/**
 * Sniffs a file's magic bytes to identify archives that lost their extension
 * (e.g. `game.xp3` renamed to `game`). Returns a format key or null. Only
 * covers formats with a stable leading signature; unknown magic files are
 * left to the normal file handling.
 */
fun detectFormatByMagic(f: File): String? {
    if (!f.isFile) return null
    val sig = try {
        val buf = ByteArray(9)
        val n = FileInputStream(f).use { it.read(buf) }
        if (n <= 0) return null else buf.copyOf(n)
    } catch (_: Exception) { return null }
    fun has(prefix: ByteArray): Boolean =
        sig.size >= prefix.size && sig.copyOf(prefix.size).contentEquals(prefix)
    return when {
        has(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)) -> "7z"
        has(byteArrayOf(0x50, 0x4B)) -> "zip"
        has(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)) -> "rar"
        has(byteArrayOf(0x1F, 0x8B.toByte())) -> "gz"
        has(byteArrayOf(0x42, 0x5A, 0x68)) -> "bz2"
        has(byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)) -> "xz"
        has(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())) -> "zst"
        has(byteArrayOf(0x04, 0x22, 0x4D, 0x18)) -> "lz4"
        has("XP3".toByteArray(Charsets.US_ASCII)) -> "xp3"
        else -> null
    }
}
