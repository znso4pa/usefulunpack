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
        val c = st.cond as UutCond.VarCmp
        assertEquals("answer", c.name)
        assertEquals("=", c.op)
        assertEquals("xp3", c.value)
        assertEquals("uu x a.xp3", st.rawCmd)
    }

    @Test
    fun ifInlineNegatedAcceptsDollarPrefixAndQuotes() {
        val st = UutParser.parse("if \$v != \"0\" then echo hi")[0] as UutStmt.IfInline
        val c = st.cond as UutCond.VarCmp
        assertEquals("v", c.name)
        assertTrue(c.negate)
        assertEquals("0", c.value)
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
        for (ok in listOf("uu", "ls", "cd", "pwd", "help", "echo", "break", "return")) {
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
        val c = st.cond as UutCond.VarCmp
        assertEquals("v", c.name)
        assertEquals("=", c.op)
        assertEquals("x", c.value)
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
        assertEquals("!=", (inner.cond as UutCond.VarCmp).op)
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

    // ─── v3：exist 条件 / $? / 计数区间 ────────────────────────────────────

    @Test
    fun existConditionInlineAndBlock() {
        val inline = UutParser.parse("if exist out.txt then uu x a.zip")[0] as UutStmt.IfInline
        val c1 = inline.cond as UutCond.Exists
        assertEquals("out.txt", c1.rawPath)
        assertFalse(c1.negate)

        val blk = UutParser.parse("if not exist out/\n  uu mkdir out/\nend\n")[0] as UutStmt.If
        val c2 = blk.cond as UutCond.Exists
        assertEquals("out/", c2.rawPath)
        assertTrue(c2.negate)
        assertEquals(1, blk.thenBody.size)
    }

    @Test(expected = UutParseException::class)
    fun existWithoutPathThrows() {
        UutParser.parse("if exist\nend\n")
    }

    @Test
    fun dollarQuestionExpandsToLastExitCode() {
        assertEquals("0", UutParser.expandVars("$?", emptyMap()))          // 未见命令 = 成功
        assertEquals("[1]", UutParser.expandVars("[${'$'}?]", mapOf("?" to "1")))
        // 与命名变量/位置参数互不干扰
        assertEquals("1-a", UutParser.expandVars("$?-$1", mapOf("?" to "1", "1" to "a")))
        assertEquals("cost$", UutParser.expandVars("cost$", emptyMap()))
    }

    @Test
    fun rangeValuesCoverAscendingDescendingAndRejects() {
        assertEquals(listOf("1", "2", "3"), UutParser.rangeValues("1..3"))
        assertEquals(listOf("3", "2", "1"), UutParser.rangeValues("3..1"))
        assertEquals(listOf("7"), UutParser.rangeValues("7..7"))
        assertNull(UutParser.rangeValues("*.zip"))
        assertNull(UutParser.rangeValues("1..a"))
        assertNull(UutParser.rangeValues("1...3"))
        // 上限保护：跨度超限 → 空（宁可不跑也不生成百万行把终端卡死）
        assertTrue(UutParser.rangeValues("1..999999")!!.isEmpty())
    }

    // ─── v3：`;` / `&&` 的原文切分（$? 逐段的前提） ────────────────────────

    @Test
    fun splitChainSeparatesSemicolonAndAndAlso() {
        assertEquals(listOf("a" to "", "b" to ";"), UutParser.splitChain("a ; b"))
        assertEquals(listOf("a" to "", "b" to "&&"), UutParser.splitChain("a && b"))
        assertEquals(
            listOf("a" to "", "b" to ";", "c" to "&&"),
            UutParser.splitChain("a ; b && c")
        )
        // 单段：连接符为空
        assertEquals(listOf("uu l a.zip" to ""), UutParser.splitChain("uu l a.zip"))
        // 空段与首尾分隔符都被丢掉
        assertEquals(listOf("a" to "", "b" to ";"), UutParser.splitChain("  ; a ;; ; b ; "))
        assertEquals(emptyList<Pair<String, String>>(), UutParser.splitChain("   ;  "))
    }

    @Test
    fun splitChainKeepsQuotedAndEscapedSeparators() {
        // 引号里的分隔符是文件名的一部分，绝不能切（期望是 (文本, 连接符) 对）
        assertEquals(listOf("echo \"a;b\"" to ""), UutParser.splitChain("echo \"a;b\""))
        assertEquals(listOf("echo 'x && y'" to ""), UutParser.splitChain("echo 'x && y'"))
        // \; 转义同理
        assertEquals(listOf("echo a\\;b" to ""), UutParser.splitChain("echo a\\;b"))
        // 单个 & 不是分隔符（留给 shell 兜底命令）
        assertEquals(listOf("echo a & b" to ""), UutParser.splitChain("echo a & b"))
    }

    // ─── v3：段尾块头（`uu mkdir d ; if exist d`） ────────────────────────

    @Test
    fun blockHeaderAfterSemicolonParsesAsCommandPlusBlock() {
        // 真机抓到：解析器只认行首的 if/for，`cmd ; if …` 会被当普通命令，
        // 随后的 `end` 报"没有块的 end"且指错行号
        val src = """
            uu mkdir d ; if exist d
              echo ok > z.txt
            end
        """.trimIndent()
        val ast = UutParser.parse(src)
        assertEquals(2, ast.size)
        assertEquals("uu mkdir d", (ast[0] as UutStmt.Cmd).raw)
        val blk = ast[1] as UutStmt.If
        assertEquals("d", (blk.cond as UutCond.Exists).rawPath)
        assertEquals(1, blk.thenBody.size)
    }

    @Test
    fun blockHeaderAfterAndAlsoParsesAsForLoop() {
        val ast = UutParser.parse("echo start && for i in 1..2\n  echo \$i\nend\n")
        assertEquals(2, ast.size)
        assertEquals("echo start", (ast[0] as UutStmt.Cmd).raw)
        assertEquals("1..2", (ast[1] as UutStmt.For).rawPatterns)
    }

    @Test
    fun commandWordInsideQuotesIsNotTreatedAsBlockHeader() {
        // 引号里的 `; if …` 只是文本（切段规则引号感知）
        val ast = UutParser.parse("echo \"a ; if b\"\n")
        assertEquals(1, ast.size)
        assertEquals("echo \"a ; if b\"", (ast[0] as UutStmt.Cmd).raw)
    }

    // ─── v4：while / break / 算术 / 数值比较 ───────────────────────────────

    @Test
    fun whileParsesWithConditionAndBody() {
        val src = """
            set n = 0
            while n < 3
              set n = ${'$'}n + 1
              if ${'$'}n = 2 then break
            end
        """.trimIndent()
        val ast = UutParser.parse(src)
        assertEquals(2, ast.size)
        val w = ast[1] as UutStmt.While
        val c = w.cond as UutCond.VarCmp
        assertEquals("n", c.name)
        assertEquals("<", c.op)
        assertEquals("3", c.value)
        assertEquals(2, w.body.size)
        assertTrue(w.body[1] is UutStmt.IfInline)
    }

    @Test
    fun breakInsideIfInlineIsRecognised() {
        val st = UutParser.parse("for a in *.zip\n  if a = stop.zip then break\nend\n")[0] as UutStmt.For
        val inline = st.body[0] as UutStmt.IfInline
        assertEquals("break", inline.rawCmd)
    }

    @Test(expected = UutParseException::class)
    fun whileWithoutEndThrows() {
        UutParser.parse("while n < 3\n  echo x\n")
    }

    @Test(expected = UutParseException::class)
    fun conditionWithoutOperatorThrows() {
        UutParser.parse("if n 3 then echo x\n")
    }

    @Test
    fun comparisonOperatorsParseTwoCharFirst() {
        val le = UutParser.parse("if n <= 5 then echo x")[0] as UutStmt.IfInline
        assertEquals("<=", (le.cond as UutCond.VarCmp).op)
        assertEquals("5", (le.cond as UutCond.VarCmp).value)
        val ge = UutParser.parse("if n >= 5 then echo x")[0] as UutStmt.IfInline
        assertEquals(">=", (ge.cond as UutCond.VarCmp).op)
        val gt = UutParser.parse("if n > 5 then echo x")[0] as UutStmt.IfInline
        assertEquals(">", (gt.cond as UutCond.VarCmp).op)
    }

    @Test
    fun conditionOperatorInsideQuotesIsNotAnOperator() {
        // `if v = "a = b"` 里的第一个 `=` 才是运算符
        val st = UutParser.parse("if v = \"a=b\" then echo x")[0] as UutStmt.IfInline
        val c = st.cond as UutCond.VarCmp
        assertEquals("=", c.op)
        assertEquals("a=b", c.value)
    }

    @Test
    fun arithAssignmentEvaluatesFourOperators() {
        assertEquals("3", UutParser.evalArith("1 + 2"))
        assertEquals("6", UutParser.evalArith(" 10 - 4 "))
        assertEquals("12", UutParser.evalArith("3 * 4"))
        assertEquals("2", UutParser.evalArith("7 / 3"))
        assertEquals("-4", UutParser.evalArith("-1 + -3"))
        // 不是算术式 → null（当普通字符串）
        assertNull(UutParser.evalArith("game.xp3"))
        assertNull(UutParser.evalArith("1 + x"))
        assertNull(UutParser.evalArith("a/b"))
    }

    @Test(expected = ArithmeticException::class)
    fun arithDivisionByZeroThrowsInsteadOfSilentZero() {
        UutParser.evalArith("5 / 0")
    }

    @Test
    fun compareValuesIsLiteralForEqAndNumericForOthers() {
        // = / != 走字符串（"10" != "9" 字面成立，数值上也成立；但 "010" vs "10" 只有字面能区分）
        assertEquals(false, UutParser.compareValues("010", "=", "10"))   // 字面
        assertEquals(true, UutParser.compareValues("010", "!=", "10"))   // 字面
        assertEquals(false, UutParser.compareValues("010", "<", "10"))  // 数值：10 < 10 假
        assertEquals(false, UutParser.compareValues("10", "<", "9"))
        assertEquals(true, UutParser.compareValues("9", "<", "10"))
        assertEquals(true, UutParser.compareValues("10", ">=", "10"))
        assertEquals(true, UutParser.compareValues("10", "<=", "10"))
        // 非整数 → null（调用方报错，不静默判假）
        assertNull(UutParser.compareValues("abc", "<", "5"))
        assertNull(UutParser.compareValues("5", ">", "x"))
    }
}
