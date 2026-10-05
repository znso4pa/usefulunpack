package com.usefulunpacker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UUT 解析层单测（纯 JVM，无 Android 依赖）。
 *
 * 解析层的错误必须以 [UutParseException] 带行号抛出——脚本是给用户写的，
 * 静默吞掉一个 `end` 会让整段脚本跑飞却"看起来成功"。
 */
class UutParserTest {

    // ─── parse ──────────────────────────────────────────────────────────

    @Test
    fun commentsAndBlanksAreSkippedWithRealLineNumbers() {
        val ast = UutParser.parse("# c\n\nuu l a.zip\n   \nls\n")
        assertEquals(2, ast.size)
        val c0 = ast[0] as UutStmt.Cmd
        assertEquals(3, c0.line)
        assertEquals("uu l a.zip", c0.raw)
        assertEquals(5, (ast[1] as UutStmt.Cmd).line)
    }

    @Test
    fun setParsesNameAndRawValue() {
        val st = UutParser.parse("set f = game.xp3")[0] as UutStmt.Set
        assertEquals("f", st.name)
        assertEquals("game.xp3", st.rawValue)
        assertEquals(1, st.line)
    }

    @Test
    fun setKeepsCaptureFormIntact() {
        val st = UutParser.parse("set a = \$(uu info \$f)")[0] as UutStmt.Set
        assertEquals("\$(uu info \$f)", st.rawValue)
    }

    @Test(expected = UutParseException::class)
    fun setWithoutEqualsThrows() {
        UutParser.parse("set f")
    }

    @Test(expected = UutParseException::class)
    fun setWithBadNameThrows() {
        UutParser.parse("set 1x = y")
    }

    @Test
    fun ifInlineEquality() {
        val st = UutParser.parse("if answer = xp3 then uu x a.xp3")[0] as UutStmt.IfInline
        assertEquals("answer", st.varName)
        assertFalse(st.negate)
        assertEquals("xp3", st.value)
        assertEquals("uu x a.xp3", st.rawCmd)
    }

    @Test
    fun ifInlineNegatedAcceptsDollarPrefixAndQuotes() {
        val st = UutParser.parse("if \$v != \"0\" then echo hi")[0] as UutStmt.IfInline
        assertEquals("v", st.varName)
        assertTrue(st.negate)
        assertEquals("0", st.value)
    }

    @Test(expected = UutParseException::class)
    fun ifWithoutThenThrows() {
        UutParser.parse("if a = b uu l")
    }

    @Test(expected = UutParseException::class)
    fun ifWithoutCommandThrows() {
        UutParser.parse("if a = b then   ")
    }

    @Test
    fun forLoopNestsAndKeepsLineNumbers() {
        val src = """
            for a in *.zip
              uu l ${'$'}a
              for b in *.rar
                uu l ${'$'}b
              end
            end
        """.trimIndent()
        val outer = UutParser.parse(src)[0] as UutStmt.For
        assertEquals("a", outer.varName)
        assertEquals("*.zip", outer.rawPatterns)
        assertEquals(1, outer.line)
        assertEquals(2, outer.body.size)
        val inner = outer.body[1] as UutStmt.For
        assertEquals(3, inner.line)
        assertEquals("*.rar", inner.rawPatterns)
        assertEquals(1, inner.body.size)
    }

    @Test(expected = UutParseException::class)
    fun forWithoutEndThrows() {
        UutParser.parse("for a in *.zip\n  uu l \$a\n")
    }

    @Test(expected = UutParseException::class)
    fun strayEndThrows() {
        UutParser.parse("uu l a.zip\nend\n")
    }

    @Test(expected = UutParseException::class)
    fun forWithoutInThrows() {
        UutParser.parse("for a *.zip\nend\n")
    }

    // ─── expandVars ─────────────────────────────────────────────────────

    @Test
    fun expandVarsReplacesKnownAndBlanksUnknown() {
        val vars = mapOf("f" to "game.xp3", "out" to "o.txt")
        assertEquals("game.xp3 -> o.txt", UutParser.expandVars("\$f -> \$out", vars))
        assertEquals("[]", UutParser.expandVars("[\$missing]", vars))
    }

    @Test
    fun expandVarsKeepsCaptureSyntaxAndExpandsItsArgs() {
        // `$(` 本身不是变量（后面是括号），替换层保留它；捕获**内部**的
        // `$f` 必须照常展开 —— 那正是捕获能带参数的原因。
        assertEquals("\$(uu info a.zip)", UutParser.expandVars("\$(uu info \$f)", mapOf("f" to "a.zip")))
        assertEquals("cost\$ 5", UutParser.expandVars("cost\$ 5", emptyMap()))
        // `$` 结尾不能越界
        assertEquals("a\$", UutParser.expandVars("a\$", emptyMap()))
    }

    // ─── captureInner ───────────────────────────────────────────────────

    @Test
    fun captureInnerExtractsTrimmedCommand() {
        assertEquals("uu info a.zip", UutParser.captureInner("\$(uu info a.zip)"))
        assertEquals("uu l f0", UutParser.captureInner("  \$( uu l f0 )  "))
    }

    @Test
    fun captureInnerRejectsNonCaptureForms() {
        assertNull(UutParser.captureInner("plain"))
        assertNull(UutParser.captureInner("\$("))
        assertNull(UutParser.captureInner("\$()"))
        assertNull(UutParser.captureInner("uu info a.zip)"))
    }

    // ─── commandAllowed ─────────────────────────────────────────────────

    @Test
    fun whitelistAllowsTerminalCommandsOnly() {
        for (ok in listOf("uu", "ls", "cd", "pwd", "help", "echo")) {
            assertTrue(ok, UutParser.commandAllowed(ok))
        }
        for (bad in listOf("rm", "sh", "su", "curl", "cat", "uuu")) {
            assertFalse(bad, UutParser.commandAllowed(bad))
        }
    }
}
