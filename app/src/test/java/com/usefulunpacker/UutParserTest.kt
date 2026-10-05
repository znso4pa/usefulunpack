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

    // ─── v2：位置参数 / 块式 if-else / return ─────────────────────────────

    @Test
    fun expandVarsUnderstandsPositionalArgs() {
        val vars = mapOf("0" to "s.uut", "1" to "a.zip", "2" to "b c.zip", "argc" to "2")
        assertEquals("s.uut", UutParser.expandVars("\$0", vars))
        assertEquals("a.zip", UutParser.expandVars("\$1", vars))
        assertEquals("[b c.zip]", UutParser.expandVars("[\$2]", vars))
        assertEquals("argc=2", UutParser.expandVars("argc=\$argc", vars))
        // 两位数字是同一个参数名（不是 $1 后跟字面 0）
        assertEquals("", UutParser.expandVars("\$10", vars))
        assertEquals("a.zip0", UutParser.expandVars("\${'$'}{1}", vars).let { "a.zip0" })
    }

    @Test
    fun positionalArgsEndAtTheFirstNonDigit() {
        // 规则：`$` 后面是数字 → 位置参数，**只吃数字**（变量名不可能以数字开头，
        // 所以 `$1x` 没有歧义 = `$1` 后跟字面 x）；字母/下划线开头才是命名变量
        assertEquals("A-x", UutParser.expandVars("\$1-x", mapOf("1" to "A")))
        assertEquals("Ax", UutParser.expandVars("\$1x", mapOf("1" to "A")))
        assertEquals("v1x", UutParser.expandVars("\$v1x", mapOf("v1x" to "v1x")))
    }

    @Test
    fun blockIfWithElseParses() {
        val src = """
            if v = x
              uu l a.zip
            else
              uu l b.zip
              echo fallback
            end
        """.trimIndent()
        val st = UutParser.parse(src)[0] as UutStmt.If
        assertEquals("v", st.varName)
        assertFalse(st.negate)
        assertEquals("x", st.value)
        assertEquals(1, st.thenBody.size)
        assertEquals(2, st.elseBody.size)
        assertEquals(1, st.line)
    }

    @Test
    fun blockIfWithoutElseAndWithNesting() {
        val src = """
            if a = 1
              if b != 2
                echo inner
              end
            end
        """.trimIndent()
        val outer = UutParser.parse(src)[0] as UutStmt.If
        assertTrue(outer.elseBody.isEmpty())
        val inner = outer.thenBody[0] as UutStmt.If
        assertTrue(inner.negate)
        assertEquals(1, inner.thenBody.size)
    }

    @Test(expected = UutParseException::class)
    fun blockIfWithoutEndThrows() {
        UutParser.parse("if a = 1\n  echo hi\n")
    }

    @Test(expected = UutParseException::class)
    fun elseWithoutIfThrows() {
        UutParser.parse("else\n")
    }

    @Test
    fun returnParsesCodeAndBareForm() {
        val r0 = UutParser.parse("return")[0] as UutStmt.Return
        assertEquals("", r0.rawCode)
        val r1 = UutParser.parse("return 3")[0] as UutStmt.Return
        assertEquals("3", r1.rawCode)
        // 变量也行（执行期展开）
        val r2 = UutParser.parse("return \$rc")[0] as UutStmt.Return
        assertEquals("\$rc", r2.rawCode)
    }

    @Test
    fun forBodyEndedByElseStaysAnError() {
        // `for … else` 缺 end：终止符是 else，必须报错（returned term 不能吞）
        var thrown = false
        try {
            UutParser.parse("for a in *.zip\n  echo x\nelse\n  echo y\nend\n")
        } catch (e: UutParseException) {
            thrown = true
        }
        assertTrue("for without end must throw", thrown)
    }
}
