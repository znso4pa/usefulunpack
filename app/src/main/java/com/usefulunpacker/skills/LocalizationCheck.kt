package com.usefulunpacker

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal enum class LocalizationRule {
    PLACEHOLDERS_CHANGED, TARGET_UNMAPPABLE,
}

internal data class LocalizationIssue(val rule: LocalizationRule, val line: Int? = null,
    val original: String? = null, val translation: String? = null) {
    val error: Boolean get() = rule == LocalizationRule.TARGET_UNMAPPABLE
    fun json() = JSONObject().put("code", rule.name).put("severity", if (error) "ERROR" else "WARNING")
        .apply { line?.let { put("line", it) } }
}

internal data class LocalizationReport(
    val sourceLines: Int, val translationLines: Int,
    val sourceEncoding: String, val translationEncoding: String, val targetEncoding: String,
    val issues: List<LocalizationIssue>, val totalIssues: Int, val errors: Int,
    val profile: String, val skippedLines: Int,
) {
    val aligned: Boolean get() = sourceLines == translationLines
    val limited: Boolean get() = totalIssues > issues.size

    /** Metadata only: scripts and placeholder values never enter the model context or diagnostics. */
    fun json(offset: Int = 0, limit: Int = 20): JSONObject {
        require(offset >= 0 && limit in 1..50)
        val end = (offset.toLong() + limit).coerceAtMost(issues.size.toLong()).toInt()
        return JSONObject().put("status", when {
            errors > 0 -> "ERRORS_FOUND"
            totalIssues > 0 -> "REVIEW_REQUIRED"
            profile != "encoding" && (!aligned || skippedLines > 0) -> "INCOMPLETE"
            else -> "NO_FINDINGS"
        }).put("profile", profile).put("aligned", aligned)
            .put("lineChecks", when {
                profile == "encoding" -> "DISABLED"
                !aligned -> "SKIPPED_LINE_COUNT"
                skippedLines > 0 -> "PARTIAL_GRAMMAR"
                else -> "APPLIED"
            }).put("skippedLines", skippedLines)
            .put("sourceLines", sourceLines).put("translationLines", translationLines)
            .put("sourceEncoding", sourceEncoding).put("translationEncoding", translationEncoding)
            .put("targetEncoding", targetEncoding).put("totalIssues", totalIssues)
            .put("errors", errors).put("warnings", totalIssues - errors).put("limited", limited)
            .put("issues", JSONArray(issues.drop(offset).take(limit).map(LocalizationIssue::json)))
            .apply { if (end < issues.size) put("nextAfter", end.toString()) }
    }
}

internal class LocalizationCheckException(val reason: String) : IOException("Localization check: $reason")

/** Conservative physical-line checks, not an engine parser or a translation-quality model. */
internal object LocalizationChecker {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_LINES = 20_000
    private const val MAX_LINE_CHARS = 64 * 1024
    private const val MAX_ISSUES = 1000
    private data class Text(val document: LineEditDocument, val encoding: String, val bytes: ByteArray)

    fun check(
        source: File, translation: File, sourceEncoding: String = "auto", translationEncoding: String = "auto",
        targetEncoding: String = "translation", profile: String = "encoding",
        cancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
    ): LocalizationReport {
        if (profile !in setOf("encoding", "named", "printf")) throw LocalizationCheckException("UNSUPPORTED_PROFILE")
        fun active() { if (cancelled()) throw CancellationException() }
        val before = read(source, sourceEncoding, ::active)
        val after = read(translation, translationEncoding, ::active)
        val output = when (targetEncoding) {
            "source" -> before.encoding
            "translation" -> after.encoding
            else -> explicitEncoding(targetEncoding)
        }
        val encoder = LineEditEncoding.forSnapshot(output, before.bytes).charset.newEncoder()
        val issues = ArrayList<LocalizationIssue>()
        var total = 0
        var errors = 0
        var skipped = 0
        val aligned = before.document.size == after.document.size
        fun issue(rule: LocalizationRule, line: Int? = null) {
            total++
            if (rule == LocalizationRule.TARGET_UNMAPPABLE) errors++
            if (issues.size < MAX_ISSUES) {
                fun excerpt(document: LineEditDocument): String? = line?.let { document.original(it - 1) }
                    ?.let { if (it.length > 240) it.take(240) + "…" else it }
                issues.add(LocalizationIssue(rule, line, if (aligned) excerpt(before.document) else null, excerpt(after.document)))
            }
        }
        for (index in 0 until after.document.size) {
            active()
            val translated = after.document.original(index)
            if (!encoder.canEncode(translated)) issue(LocalizationRule.TARGET_UNMAPPABLE, index + 1)
            // After insertions/deletions, guessed line pairing would flood the report with false positives.
            if (!aligned || profile == "encoding") continue
            val original = before.document.original(index)
            val expected = localizationArguments(original, profile, ::active)
            val actual = localizationArguments(translated, profile, ::active)
            if (expected == null || actual == null) { skipped++; continue }
            if (expected != actual) issue(LocalizationRule.PLACEHOLDERS_CHANGED, index + 1)
        }
        active()
        return LocalizationReport(before.document.size, after.document.size, before.encoding, after.encoding,
            output, issues, total, errors, profile, skipped)
    }

    private fun read(file: File, selected: String, active: () -> Unit): Text {
        active()
        if (!file.isFile) throw LocalizationCheckException("NOT_A_FILE")
        val initial = java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        if (initial.size() > MAX_BYTES) throw LocalizationCheckException("FILE_TOO_LARGE")
        val bytes = file.inputStream().use { input ->
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                active()
                val count = input.read(buffer)
                if (count < 0) break
                if (result.size() + count > MAX_BYTES) throw LocalizationCheckException("FILE_TOO_LARGE")
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        }
        val current = java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        if (initial.size() != bytes.size.toLong() || current.size() != initial.size() ||
            current.lastModifiedTime() != initial.lastModifiedTime() || current.fileKey() != initial.fileKey()) {
            throw LocalizationCheckException("FILE_CHANGED")
        }
        val encoding = if (selected == "auto") {
            if (bytes.isEmpty()) "UTF-8" else detectBomEncoding(bytes) ?: "UTF-8".takeIf { looksLikeUtf8(bytes) }
                ?: throw LocalizationCheckException("CHOOSE_ENCODING")
        } else explicitEncoding(selected)
        val layout = LineEditEncoding.forSnapshot(encoding, bytes)
        val skip = when {
            encoding == "UTF-8" && detectBomEncoding(bytes) == "UTF-8" -> 3
            encoding == "UTF-16" && detectBomEncoding(bytes) == "UTF-16" -> 2
            else -> 0
        }
        val value = try {
            layout.charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, skip, bytes.size - skip)).toString()
        } catch (_: CharacterCodingException) { throw LocalizationCheckException("INVALID_ENCODING") }
        if (value.any { it == '\u0000' || it < ' ' && it !in "\t\r\n\u000c" }) {
            throw LocalizationCheckException("NOT_TEXT")
        }
        // Validate bounds before creating the line index, including files with millions of tiny lines.
        var lines = 0
        var length = 0
        var previous = '\u0000'
        for ((index, char) in value.withIndex()) {
            if (index % 4096 == 0) active()
            if (char == '\r' || char == '\n') {
                if (!(char == '\n' && previous == '\r')) lines++
                length = 0
            } else length++
            if (lines > MAX_LINES || length > MAX_LINE_CHARS) throw LocalizationCheckException("TEXT_LIMIT")
            previous = char
        }
        val document = LineEditDocument(value)
        if (document.size > MAX_LINES) throw LocalizationCheckException("TEXT_LIMIT")
        return Text(document, encoding, bytes)
    }

    /** Do not silently narrow CP932 to Shift-JIS or GB18030 to GBK in a validation tool. */
    private fun explicitEncoding(value: String): String {
        if (value !in TEXT_ENCODINGS) throw LocalizationCheckException("UNSUPPORTED_ENCODING")
        return value
    }

}
