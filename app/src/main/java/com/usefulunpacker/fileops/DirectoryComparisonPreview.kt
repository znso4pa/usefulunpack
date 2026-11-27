package com.usefulunpacker

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes

/** Preview only an unchanged comparison snapshot. Unknown encodings/large files keep a metadata receipt. */
internal fun directoryComparisonPreview(report: DirectoryComparisonReport, entry: DirectoryComparisonEntry,
    active: () -> Unit): AgentFileChange {
    fun text(root: java.io.File, stamp: ComparedFileStamp?): String? {
        active()
        val file = DirectoryComparator.safeFile(root, entry.path)
        val attributes = try { Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
            catch (_: java.nio.file.NoSuchFileException) { null }
        if (attributes?.let(ComparedFileStamp::of) != stamp || attributes?.isSymbolicLink == true) throw IOException("Comparison snapshot changed")
        if (stamp == null) return ""
        if (stamp.size > 64 * 1024) return null
        val data = Channels.newInputStream(Files.newByteChannel(file.toPath(), setOf(READ, NOFOLLOW_LINKS))).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                active()
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > 64 * 1024) throw IOException("Comparison snapshot changed")
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
        if (ComparedFileStamp.of(Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)) != stamp) throw IOException("Comparison snapshot changed")
        val encoding = detectBomEncoding(data) ?: "UTF-8".takeIf { looksLikeUtf8(data) } ?: return null
        return try {
            val value = localizationCharset(encoding, data).newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, localizationBomSize(encoding, data), data.size - localizationBomSize(encoding, data))).toString()
            value.takeIf { it.none { char -> char == '\u0000' || char < ' ' && char !in "\t\r\n\u000c" } }
        } catch (_: java.nio.charset.CharacterCodingException) { null }
    }
    val previewable = !entry.directory && entry.state !in setOf(DirectoryChange.UNREADABLE, DirectoryChange.SKIPPED)
    val before = if (previewable) text(report.source, entry.sourceStamp) else null
    val after = if (previewable) text(report.target, entry.targetStamp) else null
    val lines = if (before != null && after != null) agentTextDiff(before, after) else null
    return AgentFileChange(entry.path, entry.sourceStamp != null, entry.targetStamp != null,
        entry.sourceStamp?.size ?: 0, entry.targetStamp?.size ?: 0, lines.orEmpty(), lines == null)
}
