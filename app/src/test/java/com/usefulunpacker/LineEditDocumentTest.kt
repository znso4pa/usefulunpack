package com.usefulunpacker

import java.nio.charset.CharacterCodingException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LineEditDocumentTest {
    @Test fun rebuildPreservesPhysicalLinesAndMixedTerminators() {
        val input = "one\r\n\r\ntwo\nthree\rfour"
        val lines = LineEditDocument(input)
        assertEquals(listOf("one", "", "two", "three", "four"), (0 until lines.size).map(lines::original))
        assertEquals(input, lines.rebuild(emptyMap()))
        assertEquals("one\r\nnew\r\ndeux\nthree\r", lines.rebuild(mapOf(4 to "", 2 to "deux", 1 to "new")))
        assertEquals(1, LineEditDocument("\r\n").size)
        assertEquals(0, LineEditDocument("").size)
    }

    @Test fun detectsLostPlaceholdersAndRejectsActualNewlines() {
        val encoding = LineEditEncoding.forSnapshot("UTF-8", ByteArray(0))
        val lines = LineEditDocument("{name}, {name}!\\n")
        assertEquals(LineEditIssue(0, LineEditProblem.PLACEHOLDER_MISMATCH),
            lines.validate(mapOf(0 to "{name}!\\n"), encoding))
        assertEquals(LineEditIssue(0, LineEditProblem.LINE_BREAK),
            lines.validate(mapOf(0 to "{name}{name}\n"), encoding))
        assertNull(lines.validate(mapOf(0 to "「{name}、{name}」\\n"), encoding))
    }

    @Test fun reportsUnmappableEditsWithoutReplacementCharacters() {
        val encoding = LineEditEncoding.forSnapshot("SHIFT-JIS", ByteArray(0))
        val lines = LineEditDocument("text\n")
        assertEquals(LineEditIssue(0, LineEditProblem.UNMAPPABLE_TEXT),
            lines.validate(mapOf(0 to "📝"), encoding))
        assertThrows(CharacterCodingException::class.java) { encoding.encode("📝") }
    }

    @Test fun preservesUtf16BigEndianBomAndRejectsMalformedInput() {
        val bom = byteArrayOf(0xfe.toByte(), 0xff.toByte())
        val original = bom + "原文\r\n".toByteArray(Charsets.UTF_16BE)
        val encoding = LineEditEncoding.forSnapshot("UTF-16", original)
        encoding.verifySource(original)
        val output = LineEditDocument("原文\r\n").rebuild(mapOf(0 to "修改"))
        assertArrayEquals(bom + "修改\r\n".toByteArray(Charsets.UTF_16BE), encoding.encode(output))
        val broken = byteArrayOf(0xc3.toByte(), 0x28)
        assertThrows(CharacterCodingException::class.java) {
            LineEditEncoding.forSnapshot("UTF-8", broken).verifySource(broken)
        }
    }

    @Test fun copyPublicationDoesNotOverwriteExistingFilesOrLeaveStaging() {
        val directory = Files.createTempDirectory("line-edit-copy").toFile()
        try {
            val original = directory.resolve("script.txt").apply { writeText("original") }
            val occupied = directory.resolve(lineEditCopyName(original)).apply { writeText("existing") }
            assertThrows(FileAlreadyExistsException::class.java) {
                writeOutputAtomically(occupied, replaceExisting = false) { it.writeText("wrong"); true }
            }
            val copy = uniqueFile(directory, lineEditCopyName(original))
            writeOutputAtomically(copy, replaceExisting = false) { it.writeText("updated"); true }
            assertEquals("updated", copy.readText())
            assertEquals("existing", occupied.readText())
            assertEquals("original", original.readText())
            assertEquals(setOf(original, occupied, copy), directory.listFiles()?.toSet())
        } finally {
            directory.deleteRecursively()
        }
    }
}
