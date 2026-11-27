package com.usefulunpacker

import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Row boundaries index the editor snapshot. Only visible or edited rows need their own strings. */
internal class LineEditDocument(private val source: String) {
    private val ends: IntArray

    init {
        var offsets = IntArray(64)
        var count = 0
        var start = 0
        while (start < source.length) {
            var end = start
            while (end < source.length && source[end] != '\r' && source[end] != '\n') end++
            if (end < source.length) {
                val first = source[end++]
                if (first == '\r' && end < source.length && source[end] == '\n') end++
            }
            if (count == offsets.size) offsets = offsets.copyOf(count * 2)
            offsets[count++] = end
            start = end
        }
        ends = offsets.copyOf(count)
    }

    val size: Int get() = ends.size

    private fun start(row: Int) = if (row == 0) 0 else ends[row - 1]

    private fun contentEnd(row: Int): Int {
        var end = ends[row]
        val start = start(row)
        if (end > start && source[end - 1] == '\n') end--
        if (end > start && source[end - 1] == '\r') end--
        return end
    }

    fun original(row: Int): String = source.substring(start(row), contentEnd(row))

    fun validate(draft: Map<Int, String>, encoding: LineEditEncoding): LineEditIssue? {
        val encoder = encoding.charset.newEncoder()
        for ((row, edit) in draft.toSortedMap()) {
            val problem = when {
                '\n' in edit || '\r' in edit -> LineEditProblem.LINE_BREAK
                markers(original(row)) != markers(edit) -> LineEditProblem.PLACEHOLDER_MISMATCH
                !encoder.canEncode(edit) -> LineEditProblem.UNMAPPABLE_TEXT
                else -> null
            }
            if (problem != null) return LineEditIssue(row, problem)
        }
        return null
    }

    fun rebuild(draft: Map<Int, String>): String = buildString(source.length) {
        var copied = 0
        for ((row, edit) in draft.toSortedMap()) {
            append(source, copied, start(row))
            append(edit)
            copied = contentEnd(row)
        }
        append(source, copied, source.length)
    }

    companion object {
        // Android ICU requires both literal braces to be escaped; the host JDK accepts a bare closing brace.
        private val protected = Regex("""\\\\|\\[nr]|\{[A-Za-z_][\w.]*\}""")
        private fun markers(text: String) = protected.findAll(text).map { it.value }.sorted().toList()
    }
}

internal enum class LineEditProblem { LINE_BREAK, PLACEHOLDER_MISMATCH, UNMAPPABLE_TEXT }
internal data class LineEditIssue(val row: Int, val problem: LineEditProblem)

internal class LineEditEncoding private constructor(val charset: Charset, private val prefix: ByteArray) {
    val description: String get() = charset.name() + if (prefix.isNotEmpty()) " · BOM" else ""

    fun verifySource(bytes: ByteArray) {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, prefix.size, bytes.size - prefix.size))
    }

    @Throws(CharacterCodingException::class)
    fun encode(text: String): ByteArray {
        val body = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text))
        return ByteArray(prefix.size + body.remaining()).also {
            prefix.copyInto(it)
            body.get(it, prefix.size, body.remaining())
        }
    }

    companion object {
        /** Keep the source byte order when UTF-16 is retained, including big-endian files. */
        fun forSnapshot(encoding: String, original: ByteArray, bomOverride: Boolean? = null): LineEditEncoding {
            val bom = bomOverride ?: (detectBomEncoding(original) != null)
            val charset = when {
                encoding == "UTF-16" && detectBomEncoding(original) == "UTF-16" &&
                    original[0] == 0xfe.toByte() -> Charsets.UTF_16BE
                encoding == "UTF-16" -> Charsets.UTF_16LE
                else -> if (encoding in LOCALIZATION_ENCODINGS) localizationCharset(encoding, original) else Charset.forName(encoding)
            }
            val prefix = if (bom) when (charset) {
                Charsets.UTF_16BE -> byteArrayOf(0xfe.toByte(), 0xff.toByte())
                Charsets.UTF_16LE -> byteArrayOf(0xff.toByte(), 0xfe.toByte())
                Charsets.UTF_8 -> byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
                else -> ByteArray(0)
            } else ByteArray(0)
            return LineEditEncoding(charset, prefix)
        }
    }
}

internal fun lineEditCopyName(file: File): String =
    if (file.extension.isEmpty() || file.nameWithoutExtension.isEmpty()) "${file.name}.edited"
    else "${file.nameWithoutExtension}.edited.${file.extension}"
