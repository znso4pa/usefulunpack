package com.usefulunpacker

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
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
    // BOM-less UTF-16LE first: its ASCII runs are [printable, 0x00] pairs, which
    // is *valid UTF-8* (0x00 is an ordinary code point), so the strict-UTF-8 test
    // below used to claim every such script and decode it byte-wise — a
    // Kirikiri2 .tjs previewed as "l a b e l   s t a r t". A single-byte text
    // file never has that pair shape, so nothing else is affected.
    detectBomlessUtf16Le(data)?.let { return it }
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

/** Detects BOM-less UTF-16LE text — the standard encoding of Kirikiri2
 *  `.tjs`/`.ks` scripts (Windows origin, often saved without a BOM). The
 *  fingerprint: ASCII code appears as [printable, 0x00] pairs, a shape
 *  SJIS/GBK text never has (they contain no 0x00 bytes at all), and binary
 *  NUL runs are [0x00, 0x00] pairs (low byte 0x00 is not printable). Only LE
 *  is auto-detected — krkr2 scripts are LE; BE files stay on the manual
 *  encoding switch. Returns "UTF-16" (the BOM-less branch of
 *  [decodeTextStrict] decodes LE) or null when the shape doesn't match. */
fun detectBomlessUtf16Le(data: ByteArray): String? {
    if (data.size < 16 || data.size % 2 != 0) return null
    val pairs = data.size / 2
    var asciiLe = 0
    var i = 0
    while (i < data.size) {
        val lo = data[i].toInt() and 0xFF
        val hi = data[i + 1].toInt() and 0xFF
        // U+0000 — a [0, 0] pair — never appears in real text, while a binary's
        // NUL runs (a PE header's aligned zeros, a .so's padding) are exactly
        // that shape. Without this the 5%-of-pairs heuristic below claimed the
        // first 8 KiB of every PE was UTF-16LE, and `looksLikeText` skips its
        // NUL check for UTF-16 — so tapping an .exe previewed it as garbled
        // text while the signature scan correctly called it a PE binary.
        if (lo == 0 && hi == 0) return null
        if (hi == 0 && lo in 0x20..0x7E) asciiLe++
        i += 2
    }
    // 5% of pairs: far above any non-UTF-16 noise, far below the actual share
    // in code-bearing scripts (keywords/brackets alone usually exceed 20%).
    return if (asciiLe * 20 >= pairs) "UTF-16" else null
}

/**
 * True when a head sample looks like decodable text, so a file whose extension
 * nobody listed can still be previewed instead of being refused.
 *
 * The decision reuses the project's own encoding detection ([detectBestEncoding])
 * rather than inventing a second heuristic, and adds the two signals that
 * separate "binary that happens to decode" from real text:
 *  - NUL bytes: text never contains them. The one legitimate shape that does —
 *    BOM-less UTF-16LE — is recognised first and left to the detector.
 *  - control characters: a decoded binary is full of them. Tab/newline/CR/FF
 *    don't count, and the threshold is 1% of the sample.
 */
fun looksLikeText(data: ByteArray): Boolean {
    if (data.isEmpty()) return false
    if (detectBomEncoding(data) == "UTF-16") {
        val charset = if (data[0] == 0xfe.toByte()) Charsets.UTF_16BE else Charsets.UTF_16LE
        val decoded = try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, 2, data.size - 2))
        } catch (_: CharacterCodingException) { return false }
        if (decoded.any { it == '\u0000' }) return false
        val controls = decoded.count { it < ' ' && it !in "\t\n\r\u000c" }
        return !tooManyTextControls(controls, decoded.length)
    }
    if (detectBomlessUtf16Le(data) == null) {
        var ctrl = 0
        for (b in data) {
            val c = b.toInt() and 0xFF
            if (c == 0x00) return false
            if (c < 0x20 && c != 0x09 && c != 0x0A && c != 0x0D && c != 0x0C) ctrl++
        }
        // Two thresholds, because one ratio cannot serve both sizes: a single
        // ESC (colour codes are real in scripts and logs) is 2.7% of a 37-byte
        // file but 0.0015% of a 64 KiB one, so a bare 1% rule rejected short
        // text outright. Short samples therefore need an absolute floor as well;
        // long ones keep the ratio, which is what catches a decoded binary.
        if (tooManyTextControls(ctrl, data.size)) return false
    }
    return detectBestEncoding(data) != null
}

private fun tooManyTextControls(count: Int, length: Int): Boolean =
    if (length < 64) count >= 4 else count * 100 > length

/**
 * The ONE text/binary gate for callers that need an encoding rather than a
 * yes/no answer.
 *
 * It runs [looksLikeText] first (NUL bytes and control-character density) and
 * only then the encoding detector. `uu cat` used to call the detector alone, so
 * a binary entry — 16 bytes of `0x00..0x0f` — decoded as "valid UTF-8" and the
 * terminal printed control bytes as if they were text. Every text/binary
 * decision in the app goes through here so the preview, `uu cat` and `uu grep`
 * cannot disagree.
 */
fun textEncodingOf(data: ByteArray): String? =
    if (looksLikeText(data)) detectBestEncoding(data) ?: "UTF-8" else null

/** [looksLikeText] on the file's first [maxBytes] — never reads it whole, and
 *  never throws (an unreadable file simply isn't text). */
fun looksLikeTextFile(f: File, maxBytes: Int = 64 * 1024): Boolean =
    com.usefulunpacker.observedAttempt("FileUtils") { readPrefix(f, maxBytes.toLong()) }.getOrNull()?.let { looksLikeText(it) } ?: false

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
