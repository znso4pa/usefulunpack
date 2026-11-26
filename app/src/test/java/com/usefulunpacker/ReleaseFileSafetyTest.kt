package com.usefulunpacker

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReleaseFileSafetyTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun forceCopyAndMoveRejectSameFileIncludingDestinationDirectoryAliases() {
        for (command in listOf("cp", "mv")) for (destination in listOf("original.txt", ".")) {
            val root = temp.newFolder()
            val source = root.resolve("original.txt").apply { writeText("keep original bytes") }
            val result = UuCommands.dispatch(listOf(command, "original.txt", destination, "-f"), UuCommands.Ctx(null, root))
            assertEquals(1, result.exitCode)
            assertEquals("keep original bytes", source.readText())
        }
    }

    @Test fun forceCopyAndMoveRejectAncestorTargetsWithoutDeletingSourceOrSiblings() {
        for (command in listOf("cp", "mv")) {
            val root = temp.newFolder()
            val source = root.resolve("tree/nested/tree").apply { mkdirs() }
            source.resolve("source.txt").writeText("source")
            root.resolve("tree/sibling.txt").writeText("sibling")
            val result = UuCommands.dispatch(listOf(command, "tree/nested/tree", ".", "-f"), UuCommands.Ctx(null, root))
            assertEquals(1, result.exitCode)
            assertEquals("source", source.resolve("source.txt").readText())
            assertEquals("sibling", root.resolve("tree/sibling.txt").readText())
        }
    }

    @Test fun forceCopyAndMoveRejectExistingDescendantsBeforeDeletingThem() {
        for (command in listOf("cp", "mv")) {
            val root = temp.newFolder()
            root.resolve("tree/source.txt").apply { checkNotNull(parentFile).mkdirs(); writeText("source") }
            val nested = root.resolve("tree/inside/tree/keep.txt").apply { checkNotNull(parentFile).mkdirs(); writeText("nested") }
            val result = UuCommands.dispatch(listOf(command, "tree", "tree/inside", "-f"), UuCommands.Ctx(null, root))
            assertEquals(1, result.exitCode)
            assertEquals("nested", nested.readText())
            assertEquals("source", root.resolve("tree/source.txt").readText())
        }
    }

    @Test fun forceCopyAndMoveRejectSymbolicAliasesAndAllowSeparateTargets() {
        for (command in listOf("cp", "mv")) {
            val root = temp.newFolder()
            val source = root.resolve("data.txt").apply { writeText("original") }
            val alias = root.resolve("alias.txt")
            Files.createSymbolicLink(alias.toPath(), source.toPath())
            assertEquals(1, UuCommands.dispatch(listOf(command, "data.txt", "alias.txt", "-f"), UuCommands.Ctx(null, root)).exitCode)
            assertTrue(Files.isSymbolicLink(alias.toPath()))
            assertEquals("original", source.readText())
            val output = root.resolve("data.txt.copy").apply { writeText("replace me") }
            assertEquals(0, UuCommands.dispatch(listOf(command, "data.txt", output.name, "-f"), UuCommands.Ctx(null, root)).exitCode)
            assertEquals("original", output.readText())
        }
    }

    @Test fun workspaceRootCopyIsIncludedInAgentChangeReceipt() {
        val root = temp.newFolder().canonicalFile
        root.resolve("source/input.txt").apply { checkNotNull(parentFile).mkdirs(); writeText("content") }
        val changes = AgentChangeCapture(root).apply { enable() }
        val workspace = McpWorkspace(root, changes::watch)
        val result = UuText.withWorkspace(workspace) {
            UuCommands.dispatch(listOf("cp", "source/input.txt", "."), UuCommands.Ctx(null, root))
        }
        assertEquals(0, result.exitCode)
        val file = changes.finish().single()
        assertEquals("input.txt", file.path)
        assertFalse(file.existedBefore)
        assertEquals("content", file.lines.single().text)
    }

    @Test fun skillWriteRejectsUnmappableTextAndLeavesExistingBytesUntouched() {
        val root = temp.newFolder()
        val existing = root.resolve("existing.txt").apply { writeText("original") }
        McpTasks { _, _ -> error("No task expected") }.use { tasks ->
            val runtime = WorkspaceSkillRuntime(McpWorkspace(root), tasks)
            for (path in listOf("existing.txt", "new.txt")) {
                val result = runtime.invokeSkill("WRITE_TEXT", JSONObject().put("path", path)
                    .put("text", "emoji 📝").put("encoding", "SHIFT-JIS").put("overwrite", true))
                assertTrue(result.isError)
            }
        }
        assertEquals("original", existing.readText())
        assertFalse(root.resolve("new.txt").exists())
        assertEquals(listOf("existing.txt"), root.list()?.toList())
    }

    @Test fun skillWritePreservesBigEndianBomAndSupportsExplicitBomRemoval() {
        val root = temp.newFolder()
        val bom = byteArrayOf(0xfe.toByte(), 0xff.toByte())
        val file = root.resolve("note.txt").apply { writeBytes(bom + "原文".toByteArray(Charsets.UTF_16BE)) }
        McpTasks { _, _ -> error("No task expected") }.use { tasks ->
            val runtime = WorkspaceSkillRuntime(McpWorkspace(root), tasks)
            val request = JSONObject().put("path", file.name).put("text", "修改").put("overwrite", true)
            assertFalse(runtime.invokeSkill("WRITE_TEXT", request).isError)
            assertArrayEquals(bom + "修改".toByteArray(Charsets.UTF_16BE), file.readBytes())
            assertFalse(runtime.invokeSkill("WRITE_TEXT", request.put("bom", false)).isError)
            assertArrayEquals("修改".toByteArray(Charsets.UTF_16BE), file.readBytes())
        }
    }

    @Test fun uuConversionAndInPlaceSedRejectUnmappableCharacters() {
        val root = temp.newFolder()
        val input = root.resolve("utf8.txt").apply { writeText("📝") }
        assertEquals(1, UuCommands.dispatch(listOf("enc", input.name, "UTF-8", "sjis", "bad.txt"), UuCommands.Ctx(null, root)).exitCode)
        assertFalse(root.resolve("bad.txt").exists())
        val sjis = root.resolve("sjis.txt").apply { writeBytes("日本語".toByteArray(charset("SHIFT-JIS"))) }
        val before = sjis.readBytes()
        assertEquals(1, UuCommands.dispatch(listOf("sed", "日本語", "📝", sjis.name, "-i"), UuCommands.Ctx(null, root)).exitCode)
        assertArrayEquals(before, sjis.readBytes())
    }

    @Test fun uuSedPreservesBigEndianTextWhenItCanBeEncoded() {
        val root = temp.newFolder()
        val bom = byteArrayOf(0xfe.toByte(), 0xff.toByte())
        val file = root.resolve("big-endian.txt").apply { writeBytes(bom + "原文\r\n".toByteArray(Charsets.UTF_16BE)) }
        assertEquals(0, UuCommands.dispatch(listOf("sed", "原文", "修改", file.name, "-i"), UuCommands.Ctx(null, root)).exitCode)
        assertArrayEquals(bom + "修改\r\n".toByteArray(Charsets.UTF_16BE), file.readBytes())
    }

    @Test fun utf16BomTextGateAcceptsBothByteOrdersAndRejectsMalformedAndBinaryBodies() {
        for ((charset, bom) in listOf(Charsets.UTF_16LE to byteArrayOf(0xff.toByte(), 0xfe.toByte()),
            Charsets.UTF_16BE to byteArrayOf(0xfe.toByte(), 0xff.toByte()))) {
            val text = bom + "文字\r\nhello".toByteArray(charset)
            assertTrue(looksLikeText(text))
            assertEquals("UTF-16", textEncodingOf(text))
            assertFalse(looksLikeText(bom + ByteArray(16)))
            assertFalse(looksLikeText(bom + byteArrayOf(1)))
        }
        assertFalse(looksLikeText(byteArrayOf(0xfe.toByte(), 0xff.toByte(), 0xd8.toByte(), 0)))
    }
}
