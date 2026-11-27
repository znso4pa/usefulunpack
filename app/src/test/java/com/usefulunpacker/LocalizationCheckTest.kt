package com.usefulunpacker

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalizationCheckTest {
    @get:Rule val temp = TemporaryFolder()
    private fun pair(source: String, translated: String): Pair<File, File> {
        val root = temp.newFolder()
        return root.resolve("source.txt").apply { writeText(source) } to root.resolve("translated.txt").apply { writeText(translated) }
    }
    private fun check(source: String, translated: String, profile: String = "encoding", target: String = "translation"): LocalizationReport {
        val (a, b) = pair(source, translated)
        val before = a.readBytes() to b.readBytes()
        return LocalizationChecker.check(a, b, profile = profile, targetEncoding = target).also {
            assertArrayEquals(before.first, a.readBytes())
            assertArrayEquals(before.second, b.readBytes())
        }
    }

    @Test fun defaultsDoNotFlagOrdinaryTranslationFormattingOrInvisibleCharacters() {
        val report = check("Hello {name} 100% done\\n\r\nOriginal\r\n", "你好！\n\u200d\ufffd译文\n新增行")
        assertEquals(0, report.totalIssues)
        assertEquals("DISABLED", report.json().getString("lineChecks"))
        assertEquals("NO_FINDINGS", report.json().getString("status"))
    }

    @Test fun encodingAndBomChangesAreNotErrorsAndTargetIsNotAssumedToBeSource() {
        val (a, b) = pair("original", "汉化 📝\n")
        a.writeBytes(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "原文".toByteArray(Charsets.UTF_16LE))
        assertEquals(0, LocalizationChecker.check(a, b).totalIssues)
        a.writeBytes("日本語".toByteArray(charset("SHIFT-JIS")))
        val report = LocalizationChecker.check(a, b, sourceEncoding = "SHIFT-JIS")
        assertEquals("UTF-8", report.targetEncoding)
        assertEquals(0, report.totalIssues)
    }

    @Test fun explicitTargetEncodingReportsExactUnmappableLineAndKeepsFiles() {
        val report = check("first\nsecond", "first\n📝", target = "SHIFT-JIS")
        assertEquals(1, report.errors)
        assertEquals(LocalizationRule.TARGET_UNMAPPABLE, report.issues.single().rule)
        assertEquals(2, report.issues.single().line)
        assertEquals("ERRORS_FOUND", report.json().getString("status"))
    }

    @Test fun namedFieldsAllowReorderingRepeatedReferencesAndEscapedLiterals() {
        for ((source, translation) in listOf(
            "Hello {name}, {count}" to "{count}：{name}你好",
            "{name} {name}" to "{name}",
            "{{literal}} {0}" to "{{字面量}} {0}",
            "{{{name}}}" to "{name}",
            "{player.name} {0}" to "{0} {player.name}",
            "\\{name}" to "{name}",
            "{00}" to "{0}",
        )) assertEquals("$source / $translation", 0, check(source, translation, "named").totalIssues)
    }

    @Test fun namedFieldRemovalOrRenamingIsAReviewItemNotAnEncodingError() {
        val report = check("Hello {name}", "你好 {other}", "named")
        assertEquals(0, report.errors)
        assertEquals(LocalizationRule.PLACEHOLDERS_CHANGED, report.issues.single().rule)
        assertEquals("REVIEW_REQUIRED", report.json().getString("status"))
    }

    @Test fun formatConversionsIndexesAndNestedWidthsPreserveDependenciesWithoutFalseFindings() {
        for ((source, translation) in listOf(
            "{name!r:>20}" to "{name!s:<12}",
            "{items[00].name!a}" to "{items[0].name}",
            "{0.name}" to "{00.name}",
            "{} {}" to "{1} {0}",
            "{:{}} {}" to "{2} {0:{1}}",
            "{value:{width}.{precision}f}" to "{value:{width}.{precision}g}",
            "{name:{{}}}" to "{name}",
            "{value:{{{width}}}}" to "{width} {value}",
        )) {
            val report = check(source, translation, "named")
            assertEquals(source, 0, report.totalIssues)
            assertEquals(source, 0, report.skippedLines)
            assertEquals(1, report.checkedLines)
            assertEquals(1, report.placeholderLines)
        }
    }

    @Test fun missingDynamicFormatArgumentsAreDetectedButPresentationChangesAreNot() {
        val report = check("{value:{width}.{precision}f}", "{value:.2f}", "named")
        assertEquals(LocalizationRule.PLACEHOLDERS_CHANGED, report.issues.single().rule)
        assertEquals(0, report.errors)
        assertEquals(0, report.skippedLines)
    }

    @Test fun unsupportedLinesHaveBoundedLocalExcerptsAndMetadataOnlySkipExamples() {
        val report = check("{value!q}\n{name}\nplain", "{other!q}\n{other!q}\n普通文字", "named")
        assertEquals(0, report.totalIssues)
        assertEquals(2, report.skippedLines)
        assertEquals(1, report.checkedLines)
        assertEquals(0, report.placeholderLines)
        assertEquals(listOf("BOTH", "TRANSLATION"), report.skippedExamples.map { it.side })
        assertEquals(listOf(1, 2), report.skippedExamples.map { it.line })
        assertTrue(report.skippedExamples.first().original!!.contains("value"))
        val json = report.json()
        assertFalse(json.toString().contains("{value"))
        assertFalse(json.toString().contains("{other"))
        assertEquals("INCOMPLETE", json.getString("status"))
        val allSkipped = check("{value!q}\n".repeat(100), "{other!q}\n".repeat(100), "named")
        assertEquals(20, allSkipped.skippedExamples.size)
        assertEquals(5, allSkipped.json().getJSONArray("skippedExamples").length())
        val malicious = JSONObject(json.toString())
        malicious.getJSONArray("skippedExamples").getJSONObject(0).put("original", "private text")
        assertFalse(AgentReadTool.LOCALIZATION_CHECK.metadata(malicious).toString().contains("private text"))
    }

    @Test fun clippedFindingExcerptsDoNotSplitSurrogatePairs() {
        val prefix = "x".repeat(239) + "📝"
        val excerpt = check(prefix + "{name}", prefix + "{other}", "named").issues.single().original!!
        assertEquals("x".repeat(239) + "…", excerpt)
        assertFalse(excerpt.any { it.isSurrogate() })
    }

    @Test fun unsupportedBraceGrammarSkipsWholeLinesRatherThanComparingPartialTokens() {
        for (source in listOf("{name!q} {kept}", "{name[has space]}", "{value:{width:{depth}}}", "{} {0}", "{0} {}", "broken {name", "single }")) {
            val report = check(source, "{other}", "named")
            assertEquals(source, 0, report.totalIssues)
            assertEquals(source, 1, report.skippedLines)
            assertEquals("INCOMPLETE", report.json().getString("status"))
        }
    }

    @Test fun unequalLineCountsDisableAllPairwiseChecksWithoutFalsePlaceholderFindings() {
        val report = check("{a}\n{b}", "added\n{a}\n{b}", "named")
        assertEquals(0, report.totalIssues)
        assertFalse(report.aligned)
        assertEquals("SKIPPED_LINE_COUNT", report.json().getString("lineChecks"))
        assertEquals("INCOMPLETE", report.json().getString("status"))
    }

    @Test fun encodingChecksContinueWhenLinePairingIsUnavailable() {
        val report = check("{a}", "added\n📝", "named", "SHIFT-JIS")
        assertEquals(listOf(LocalizationRule.TARGET_UNMAPPABLE), report.issues.map { it.rule })
        assertEquals(2, report.issues.single().line)
        assertNull(report.issues.single().original)
    }

    @Test fun printfAllowsPositionReorderingWidthPrecisionAndCompatiblePresentationChanges() {
        for ((source, translation) in listOf(
            "%s %d" to "%2\$d %1\$s",
            "%1\$s %1\$s" to "%1\$s",
            "%02d %.3f" to "%i %g",
            "%hhd %hd" to "%d %i",
            "%hhu %hu" to "%d %i",
            "%f %lf" to "%g %e",
            "%*.*f" to "%3\$*1\$.*2\$f",
            "%% %s" to "%s %%",
        )) {
            val report = check(source, translation, "printf")
            assertEquals("$source / $translation", 0, report.totalIssues)
            assertEquals("$source / $translation", 0, report.skippedLines)
        }
    }

    @Test fun printfDetectsChangedArgumentTypesOrderAndDynamicWidthArguments() {
        for ((source, translation) in listOf(
            "%s %d" to "%d %s", "%*s" to "%s", "%ld" to "%d",
            "%s" to "%ls", "%f" to "%Lf", "%n" to "%hn", "%u" to "%d",
        )) assertEquals("$source / $translation", 1, check(source, translation, "printf").totalIssues)
    }

    @Test fun printfRejectsMixedNumberingHolesConflictingTypesAndOtherDialectsWithoutFindings() {
        for (source in listOf("%1\$s %d", "%2\$s", "%1\$s %1\$d", "%0\$s", "%129\$s",
            "%(name)s", "%q", "%#s", "%+u", "%.2c", "%Ls", "%2\$*d", "100%", "%1.2n")) {
            val report = check(source, "%s", "printf")
            assertEquals(source, 0, report.totalIssues)
            assertEquals(source, 1, report.skippedLines)
        }
    }

    @Test fun sourceAndTranslationNewlineConventionsAreIndependentOfLineCorrespondence() {
        val report = check("{a}\r\n{b}\r\n", "{a}\n{b}\n", "named")
        assertTrue(report.aligned)
        assertEquals(0, report.totalIssues)
        assertEquals(2, report.sourceLines)
    }

    @Test fun utf16BigEndianAndLittleEndianAreStrictlyDecodedWithoutBomArtifacts() {
        val (a, b) = pair("", "")
        a.writeBytes(byteArrayOf(0xfe.toByte(), 0xff.toByte()) + "{name}\r\n".toByteArray(Charsets.UTF_16BE))
        b.writeBytes(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "{name}\n".toByteArray(Charsets.UTF_16LE))
        assertEquals(0, LocalizationChecker.check(a, b, profile = "named").totalIssues)
        b.writeBytes(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 1))
        assertEquals("INVALID_ENCODING", assertThrows(LocalizationCheckException::class.java) { LocalizationChecker.check(a, b) }.reason)
    }

    @Test fun exactVendorAndSupplementaryEncodingsUseIndependentByteVectors() {
        val (a, b) = pair("plain", "plain")
        // Byte sequences independently produced by Python codecs, not by the encoder being checked.
        a.writeBytes(byteArrayOf(0x87.toByte(), 0x40)) // CP932 extension: U+2460
        b.writeText("①")
        val vendor = LocalizationChecker.check(a, b, sourceEncoding = "CP932", targetEncoding = "CP932")
        assertEquals(0, vendor.totalIssues)
        assertEquals("INVALID_ENCODING", assertThrows(LocalizationCheckException::class.java) {
            LocalizationChecker.check(a, b, sourceEncoding = "SHIFT-JIS")
        }.reason)
        a.writeBytes(byteArrayOf(0x95.toByte(), 0x34, 0xb2.toByte(), 0x35)) // GB18030: U+20BB7
        b.writeText("𠮷")
        assertEquals(0, LocalizationChecker.check(a, b, sourceEncoding = "GB18030", targetEncoding = "GB18030").totalIssues)
        assertEquals(1, LocalizationChecker.check(a, b, sourceEncoding = "GB18030", targetEncoding = "GBK").errors)
    }

    @Test fun explicitUtf16ByteOrdersSupportBomlessTextAndRejectConflictingUnicodeBoms() {
        val (a, b) = pair("", "{name}")
        a.writeBytes("{name}".toByteArray(Charsets.UTF_16BE))
        assertEquals(0, LocalizationChecker.check(a, b, sourceEncoding = "UTF-16BE", profile = "named").totalIssues)
        a.writeBytes(byteArrayOf(0xfe.toByte(), 0xff.toByte()) + a.readBytes())
        assertEquals("BOM_ENCODING_CONFLICT", assertThrows(LocalizationCheckException::class.java) {
            LocalizationChecker.check(a, b, sourceEncoding = "UTF-16LE")
        }.reason)
        assertEquals(0, LocalizationChecker.check(a, b, sourceEncoding = "UTF-16BE", profile = "named").totalIssues)
    }

    @Test fun legacyEncodingsRequireExplicitSelectionInsteadOfHeuristicGuessing() {
        val (a, b) = pair("source", "translation")
        a.writeBytes("日本語".toByteArray(charset("SHIFT-JIS")))
        assertEquals("CHOOSE_ENCODING", assertThrows(LocalizationCheckException::class.java) { LocalizationChecker.check(a, b) }.reason)
        assertEquals(0, LocalizationChecker.check(a, b, sourceEncoding = "SHIFT-JIS").totalIssues)
        a.writeBytes(byteArrayOf(0xff.toByte()))
        assertEquals("INVALID_ENCODING", assertThrows(LocalizationCheckException::class.java) {
            LocalizationChecker.check(a, b, sourceEncoding = "UTF-8")
        }.reason)
        for (unsupported in listOf("CP1252", "unknown-encoding")) {
            assertEquals("UNSUPPORTED_ENCODING", assertThrows(LocalizationCheckException::class.java) {
                LocalizationChecker.check(a, b, sourceEncoding = unsupported)
            }.reason)
        }
    }

    @Test fun missingBinaryOversizedAndOverlongTextsNeverProduceSuccessReports() {
        val (a, b) = pair("source", "translation")
        assertEquals("NOT_A_FILE", assertThrows(LocalizationCheckException::class.java) {
            LocalizationChecker.check(File(a.parentFile, "missing"), b)
        }.reason)
        for ((payload, reason) in listOf(byteArrayOf(0, 1, 2) to "NOT_TEXT",
            ByteArray(2 * 1024 * 1024 + 1) to "FILE_TOO_LARGE", "x".repeat(65537).toByteArray() to "TEXT_LIMIT",
            "\n".repeat(20001).toByteArray() to "TEXT_LIMIT")) {
            a.writeBytes(payload)
            assertEquals(reason, assertThrows(LocalizationCheckException::class.java) { LocalizationChecker.check(a, b) }.reason)
        }
    }

    @Test fun findingStorageAndPaginationAreBoundedAndNeverIncludeTextPayloads() {
        val source = (1..1500).joinToString("\n") { "private-{name}-$it" }
        val translated = (1..1500).joinToString("\n") { "secret-{other}-$it" }
        val report = check(source, translated, "named")
        assertEquals(1500, report.totalIssues)
        assertEquals(1000, report.issues.size)
        assertTrue(report.limited)
        val json = report.json()
        assertEquals(20, json.getJSONArray("issues").length())
        assertEquals("20", json.getString("nextAfter"))
        assertFalse(json.toString().contains("private"))
        assertFalse(json.toString().contains("secret"))
        assertFalse(json.toString().contains("{name}"))
        assertEquals(21, report.json(20).getJSONArray("issues").getJSONObject(0).getInt("line"))
        assertFalse(report.json(999).has("nextAfter"))
        assertTrue(report.issues.first().original!!.contains("private"))
    }

    @Test fun emptyFilesAndIntentionallyClearedPlainTextHaveNoSpeculativeFindings() {
        assertEquals(0, check("", "").totalIssues)
        assertEquals(0, check("original", "").totalIssues)
        val report = check("{name}", "", "named")
        assertEquals(0, report.totalIssues)
        assertEquals("INCOMPLETE", report.json().getString("status"))
    }

    @Test fun cancellationIsObservedBeforeReadsAndDuringTheLinePass() {
        val (a, b) = pair("{name}\n".repeat(100), "{other}\n".repeat(100))
        assertThrows(CancellationException::class.java) { LocalizationChecker.check(a, b) { true } }
        var polls = 0
        assertThrows(CancellationException::class.java) {
            LocalizationChecker.check(a, b, profile = "named") { ++polls > 15 }
        }
        assertTrue(polls > 15)
        Thread.currentThread().interrupt()
        try {
            assertThrows(CancellationException::class.java) { LocalizationChecker.check(a, b) { false } }
        } finally { Thread.interrupted() }
    }

    @Test fun workspaceToolSharesRulesAndRejectsOutsidePathsAndInvalidParameters() {
        val root = temp.newFolder()
        root.resolve("source.txt").writeText("Hello {name}")
        root.resolve("translation.txt").writeText("你好")
        McpTasks { _, _ -> error("Read-only check must not create a write task") }.use { tasks ->
            val runtime = WorkspaceSkillRuntime(McpWorkspace(root), tasks)
            val input = JSONObject().put("source", "source.txt").put("translation", "translation.txt")
            val defaultResult = runtime.invokeSkill("LOCALIZATION_CHECK", input)
            assertFalse(defaultResult.isError)
            assertEquals(0, defaultResult.data.getInt("totalIssues"))
            val result = runtime.invokeSkill("LOCALIZATION_CHECK", input.put("profile", "named"))
            assertFalse(result.isError)
            assertEquals("REVIEW_REQUIRED", result.data.getString("status"))
            assertFalse(AgentReadTool.LOCALIZATION_CHECK.metadata(result.data).toString().contains("Hello"))
            for ((field, value) in listOf("source" to "../outside", "profile" to "unknown", "after" to "-1", "limit" to 0)) {
                assertTrue(runtime.invokeSkill("LOCALIZATION_CHECK", JSONObject(input.toString()).put(field, value)).isError)
            }
            val outside = temp.newFile()
            Files.createSymbolicLink(root.resolve("link").toPath(), outside.toPath())
            assertTrue(runtime.invokeSkill("LOCALIZATION_CHECK", JSONObject(input.toString()).put("source", "link")).isError)
            assertFalse("General conversations do not need another tool schema", "LOCALIZATION_CHECK" in AgentPermission.READ_ONLY.tools(runtime.skills()))
            val tool = AgentPermission.READ_ONLY.tools(runtime.skills(), AgentTaskMode.LOCALIZATION).getValue("LOCALIZATION_CHECK")
            assertTrue(tool.accepts(input))
            assertFalse(tool.accepts(JSONObject(input.toString()).put("overwrite", true)))
        }
    }

    @Test fun simpleFieldSignaturesAgreeWithIndependentPythonFormatterOracle() {
        val path = System.getenv("UU_LOCALIZATION_ORACLE")
        assumeTrue("Generate the optional Python Formatter corpus with scripts/localization_oracle.py", path != null)
        val rows = JSONArray(File(checkNotNull(path)).readText())
        assertTrue(rows.length() >= 1500)
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val before = localizationArguments(row.getString("source"), "named")
            val after = localizationArguments(row.getString("translation"), "named")
            assertNotNull(row.toString(), before)
            assertNotNull(row.toString(), after)
            assertEquals(row.toString(), row.getBoolean("different"), before != after)
        }
    }
}
