package com.usefulunpacker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * tokenizer 的行为契约。这些断言同时充当**规格说明** —— 拆分的边界在哪里、
 * 引号和转义怎么算，都在测试里钉死，改实现时不必回头读实现。
 */
class CliTokenizerTest {

    private fun tok(input: String): List<String> = when (val o = CliTokenizer().tokenize(input)) {
        is CliTokenizer.Out.Ok -> o.tokens
        is CliTokenizer.Out.Bad -> throw AssertionError("unexpected Bad for [$input]: ${o.reason}")
    }

    private fun bad(input: String): CliTokenizer.Reason = when (val o = CliTokenizer().tokenize(input)) {
        is CliTokenizer.Out.Ok -> throw AssertionError("expected Bad for [$input], got ${o.tokens}")
        is CliTokenizer.Out.Bad -> o.reason
    }

    @Test
    fun splitsOnWhitespace() {
        assertEquals(listOf("uu", "x", "a.zip"), tok("uu x a.zip"))
        // 连续/首尾空白都不产生空 token
        assertEquals(listOf("uu", "x"), tok("   uu    x   "))
        assertEquals(emptyList<String>(), tok("   "))
        assertEquals(listOf("uu"), tok("uu"))
    }

    /**
     * 这是 tokenizer 存在的**唯一理由**。`String.split("\\s+")` 会把带空格的
     * 路径拆成两段，而 UU 的归档路径带空格是常态（用户目录名里就有）。
     */
    @Test
    fun doubleQuotesKeepSpacesTogether() {
        assertEquals(listOf("uu", "x", "my game.zip"), tok("""uu x "my game.zip""""))
        assertEquals(
            listOf("uu", "x", "data.xp3", "out", "Data/Actor.rvdata2", "Data/Skill.rvdata2"),
            tok("""uu x data.xp3 out "Data/Actor.rvdata2" "Data/Skill.rvdata2""""),
        )
    }

    /** `'` 也当引号：Windows 习惯如此，UI 输入里两种都会出现。 */
    @Test
    fun singleQuotesKeepSpacesTogether() {
        assertEquals(listOf("uu", "x", "my game.zip"), tok("""uu x 'my game.zip'"""))
    }

    /**
     * 引号**不产生 token 边界**，只改变「空白是否被当作分隔符」：
     * `a"b c"d` 里的空格在引号内，所以整段是**一个** token。
     * （早先这里期望成 3 个 token，是我把语义写反了。）
     */
    @Test
    fun quotesDoNotCreateTokenBoundaries() {
        assertEquals(listOf("ab cd"), tok("a\"b c\"d"))
    }

    /** `""` 是合法的**空参数**，不能被当成「无 token」。 */
    @Test
    fun emptyQuotesAreAnEmptyArgument() {
        // 不用 raw string：结尾的 `""""` 会被 Kotlin 解析成三重引号的一部分。
        assertEquals(listOf("uu", "x", "a.zip", ""), tok("uu x a.zip \"\""))
        assertEquals(listOf(""), tok("\"\""))
    }

    /**
     * 反斜杠在引号**外**转义下一个字符。引号内保持字面 —— Android 上
     * 反斜杠是合法文件名字符，转义它会破坏真实路径。
     */
    @Test
    fun backslashEscapesOutsideQuotesOnly() {
        assertEquals(listOf("a b"), tok("""a\ b"""))
        assertEquals(listOf("a\"b"), tok("""a\"b"""))
        // 引号内的反斜杠原样保留
        assertEquals(listOf("""C:\dir\file.zip"""), tok(""""C:\dir\file.zip""""))
    }

    @Test
    fun unclosedQuoteIsRejected() {
        val r = bad("""uu x "unclosed""")
        assertTrue(r is CliTokenizer.Reason.UnclosedQuote)
        assertEquals('"', (r as CliTokenizer.Reason.UnclosedQuote).quote)
    }

    @Test
    fun unclosedSingleQuoteIsRejected() {
        val r = bad("""uu x 'oops""")
        assertEquals('\'', (r as CliTokenizer.Reason.UnclosedQuote).quote)
    }

    /** 非 ASCII 原样保留 —— 中文路径是常态，不能被任何转义规则破坏。 */
    @Test
    fun cjkPathsSurvive() {
        assertEquals(listOf("uu", "l", "崩坏 3.zip"), tok("""uu l "崩坏 3.zip""""))
        assertEquals(listOf("崩坏"), tok("崩坏"))
    }

    /**
     * 相邻两段引号（`"a1""b2"`）应拼成**一个** token。
     * 不用 raw string 写这个输入：它的结尾 `""""` 会被 Kotlin 解析成三重引号
     * 的一部分，实际内容会变成未闭合的 `"a1""b2`，测的就不是同一件事了。
     */
    @Test
    fun adjacentQuotedSpansJoinIntoOneToken() {
        assertEquals(listOf("a1b2"), tok("\"a1\"\"b2\""))
    }
    // ── CliGlob 线性匹配（含病态模式不再指数回溯）──

    @Test
    fun globMatchesBasicWildcards() {
        assertTrue(CliGlob.matches("1.zip", "*.zip"))
        assertTrue(CliGlob.matches("bg01.png", "bg??.png"))
        assertTrue(CliGlob.matches("anything.txt", "*"))
        assertFalse(CliGlob.matches("a.zip", "*.rar"))
        assertFalse(CliGlob.matches("ac", "a?c"))   // ? 必须恰好吃一个字符
        assertTrue(CliGlob.matches("aXc", "a?c"))
        assertTrue(CliGlob.matches("abc", "a?c"))   // ? 匹配任意字符,包括 b 本身
        assertTrue(CliGlob.matches("", ""))
        assertFalse(CliGlob.matches("abc", ""))
    }

    @Test
    fun globPathologicalPatternTerminates() {
        // 旧正则版在这种模式下指数回溯；线性匹配器必须立即返回
        val pathological = "*a" .repeat(8) + "b"
        val name = "z".repeat(255)
        assertEquals(false, CliGlob.matches(name, pathological))
        assertTrue(CliGlob.matches("a" + "x".repeat(50) + "b", "a*b"))
    }

}
