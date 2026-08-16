package com.usefulunpacker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.ListView
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Draggable fast-scroll handle for long lists (archive preview / scan
 *  results / script lists / batch preview). Always visible so a finger can
 *  grab it and jump through thousands of entries. */
fun ListView.enableFastScroll() {
    isFastScrollEnabled = true
    isFastScrollAlwaysVisible = true
    setFastScrollStyle(R.style.FastScrollStyle)
}

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

/** On a cancelled extract: a FRESH output folder is removed entirely (done
 *  files + the in-progress partial), a pre-existing folder keeps its prior
 *  content (the Rust side cleans the in-progress file). */
fun cleanupCancelledOutput(out: File, existedBefore: Boolean) {
    if (!out.exists()) return
    if (!existedBefore) out.deleteRecursively()
}

/** Encodes [text] back to [encoding], symmetric with [decodeTextStrict]:
 *  UTF-8/UTF-16 write a BOM matching the decode-side stripping, SHIFT-JIS/GBK
 *  via the platform charset. Unmappable characters are REPLACEd (never a
 *  hard error). Pass [bom] = whether the ORIGINAL file carried a BOM, so a
 *  faithful round-trip doesn't add/remove one. */
fun encodeText(text: String, encoding: String, bom: Boolean): ByteArray {
    return when (encoding) {
        "UTF-16" -> {
            val body = text.toByteArray(Charsets.UTF_16LE)
            if (bom) byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + body else body
        }
        "UTF-8" -> {
            val body = text.toByteArray(Charsets.UTF_8)
            if (bom) byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body else body
        }
        else -> {
            val cs = Charset.forName(encoding)
            val encoder = cs.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            try {
                val bb = encoder.encode(java.nio.CharBuffer.wrap(text))
                bb.array().copyOf(bb.limit())
            } catch (_: CharacterCodingException) {
                text.toByteArray(cs)
            }
        }
    }
}

/** Detects whether [data] starts with a UTF-8/UTF-16 BOM (same rules as
 *  [decodeTextStrict]); used by the editor to preserve byte fidelity. */
fun hasBom(data: ByteArray): Boolean =
    data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte() ||
    data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte() ||
    data.size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()

/** Returns the encoding implied by a leading BOM: UTF-16 for FF FE / FE FF,
 *  UTF-8 for EF BB BF, else null. Lets previews/editors auto-open UTF-16
 *  galgame scripts correctly regardless of the global encoding pref. */
fun detectBomEncoding(data: ByteArray): String? = when {
    data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte() -> "UTF-8"
    data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte() -> "UTF-16"
    data.size >= 2 && data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte() -> "UTF-16"
    else -> null
}

/** True when [data] starts with the TJS2 compiled-script magic ("TJS2" +
 *  version bytes). These are binary bytecode, not text — previewing them as
 *  text only produces garble. */
fun isTjsBytecode(data: ByteArray): Boolean =
    data.size >= 4 && data[0] == 'T'.code.toByte() && data[1] == 'J'.code.toByte() &&
    data[2] == 'S'.code.toByte() && data[3] == '2'.code.toByte()

/** Picks the most likely text encoding for BOM-less data: strict UTF-8 wins,
 *  otherwise Shift-JIS vs GBK are both decoded and the one with fewer
 *  replacement characters is chosen. Galgame scripts are predominantly
 *  Shift-JIS, so SJIS is the default and GBK wins only when it is clearly
 *  cleaner (markedly fewer replacements) — the common SJIS↔GBK cross-read
 *  where a SJIS file "accidentally" decodes under GBK now stays SJIS.
 *  Returns null when even the best candidate is mostly garble, so the caller
 *  falls back to the user's setting. */
fun detectBestEncoding(data: ByteArray): String? {
    detectBomEncoding(data)?.let { return it }
    if (data.isEmpty()) return null
    if (looksLikeUtf8(data)) return "UTF-8"
    fun bad(s: String) = s.count { it == '\uFFFD' }
    val sjis = decodeTextStrict(data, "SHIFT-JIS")
    val gbk = decodeTextStrict(data, "GBK")
    val bS = bad(sjis); val bG = bad(gbk)
    val best = minOf(bS, bG)
    if (best > 0 && best * 20 >= data.size) return null // both candidates garble-heavy
    // SJIS is the galgame default; GBK wins only when it is clearly cleaner
    // (markedly fewer replacement chars). `bG * 2 < bS` = GBK has under half
    // the bad chars of SJIS — the common SJIS↔GBK cross-read where a SJIS file
    // "accidentally" decodes under GBK now stays SJIS.
    return if (bG * 2 < bS) "GBK" else "SHIFT-JIS"
}

/** Reads at most [maxBytes] from [file] — for preview/search of potentially
 *  huge files (a .log/.csv can be hundreds of MB) without loading it whole. */
fun readPrefix(file: File, maxBytes: Long): ByteArray {
    if (file.length() <= maxBytes) return file.readBytes()
    return FileInputStream(file).use { ins ->
        val buf = ByteArray(maxBytes.toInt())
        val n = ins.read(buf)
        if (n < buf.size) buf.copyOf(n) else buf
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

/** True when [data] is strictly valid UTF-8 (no malformed sequences). A UTF-8
 *  file read with a non-UTF-8 encoding often decodes into legal-but-wrong
 *  characters with no U+FFFD, so the garbled test can't catch it — this strict
 *  check flags that classic case. */
fun looksLikeUtf8(data: ByteArray): Boolean {
    var i = 0
    val n = data.size
    while (i < n) {
        val b = data[i].toInt() and 0xFF
        val len = when {
            b < 0x80 -> { i++; continue }
            b in 0xC2..0xDF -> 2
            b in 0xE0..0xEF -> 3
            b in 0xF0..0xF4 -> 4
            else -> return false
        }
        if (i + len > n) return false
        for (k in 1 until len) {
            val c = data[i + k].toInt() and 0xFF
            if (c !in 0x80..0xBF) return false
        }
        // Reject overlong/surrogate/out-of-range encodings.
        when (len) {
            2 -> if (data[i].toInt() and 0x1E == 0) return false
            3 -> {
                val b1 = data[i].toInt() and 0xFF
                val b2 = data[i + 1].toInt() and 0xFF
                if (b1 == 0xE0 && b2 < 0xA0) return false
                if (b1 == 0xED && b2 > 0x9F) return false
            }
            4 -> {
                val b1 = data[i].toInt() and 0xFF
                val b2 = data[i + 1].toInt() and 0xFF
                if (b1 == 0xF0 && b2 < 0x90) return false
                if (b1 == 0xF4 && b2 > 0x8F) return false
            }
        }
        i += len
    }
    return true
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
    // `.z01/.z02` matches the legacy RAR volume regex, but a PKWARE zip
    // multi-disk part resolves as a zip SET — only the first disk (.z01)
    // carries the PK magic, so the set (not the tapped file alone) must be
    // sniffed. A `.z02` mid-part would otherwise be mislabelled RAR (red).
    if (isZipVolumeName(f.name) && resolveZipVolumes(f).size > 1) return "zip"
    if (isRarVolumeName(f.name)) {
        // A PKWARE zip set whose first disk starts with PK must win over the
        // legacy RAR regex; only standalone old-style RAR parts fall through.
        if (isZipVolumeName(f.name) && resolveZipVolumes(f).size > 1) return "zip"
        if (!startsWithZipMagic(f)) return "rar"
    }
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
            val digits = m.groupValues[2].drop(1).toIntOrNull() ?: return@mapNotNull null
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
            if (!m.groupValues[1].equals(base, ignoreCase = true)) return@mapNotNull null
            m.groupValues[2].toIntOrNull()?.let { it to f2 }
        }.sortedBy { it.first }.map { it.second }
        val final = siblings.firstOrNull { it.name.equals("$base.zip", ignoreCase = true) }
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
            if (!m.groupValues[1].equals(base, ignoreCase = true)) return@mapNotNull null
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
