package com.usefulunpacker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索命中判定的行为契约。重点是**正则不能被文本降级破坏** —— 那是修掉的
 * 真 bug：`\p{Lu}` 在「大小写不敏感」模式下永远搜不到东西。
 */
class SearchMatchTest {

    // ── 纯文本 ──

    @Test
    fun plainTextIgnoresCaseWhenAsked() {
        assertTrue(textMatches("Hello World", "world", null, caseSensitive = false))
        assertFalse(textMatches("Hello World", "world", null, caseSensitive = true))
        assertTrue(textMatches("Hello World", "World", null, caseSensitive = true))
    }

    @Test
    fun plainTextHandlesCjk() {
        assertTrue(textMatches("第一话 标题", "标题", null, caseSensitive = false))
        assertFalse(textMatches("第一话 标题", "第二话", null, caseSensitive = false))
    }

    // ── 正则 ──

    @Test
    fun regexRespectsCompiledIgnoreCase() {
        val ci = Regex("error", RegexOption.IGNORE_CASE)
        assertTrue(textMatches("an ERROR happened", "error", ci, caseSensitive = false))
        val cs = Regex("error")
        assertFalse(textMatches("an ERROR happened", "error", cs, caseSensitive = true))
    }

    /**
     * **实测记录**（不是期望，是 JVM 的事实）：`IGNORE_CASE` 对 Unicode 属性类
     * 同样生效 —— `\p{Lu}` 在小写文本上也命中，`[A-Z]` 两个方向都命中。
     *
     * 当初以为「先降级文本会让 `\p{Lu}` 永远匹配不到」，写这条测试时被推翻了。
     * 留着它，免得下一个人（或下一次的我）再按那个错误前提改代码。
     */
    @Test
    fun ignoreCaseAppliesToUnicodeClassesToo() {
        val upper = Regex("\\p{Lu}", RegexOption.IGNORE_CASE)
        assertTrue(textMatches("ABC", "x", upper, caseSensitive = false))
        assertTrue(textMatches("abc", "x", upper, caseSensitive = false))
        // 不敏感被关掉时才是「只认大写」
        val strict = Regex("\\p{Lu}")
        assertTrue(textMatches("ABC", "x", strict, caseSensitive = true))
        assertFalse(textMatches("abc", "x", strict, caseSensitive = true))
    }

    /** 区分大小写的正则照样区分 —— `IGNORE_CASE` 不是无条件加上的。 */
    @Test
    fun regexWithoutIgnoreCaseStaysStrict() {
        val cs = Regex("ERROR")
        assertFalse(textMatches("an error happened", "ERROR", cs, caseSensitive = true))
        assertTrue(textMatches("an ERROR happened", "ERROR", cs, caseSensitive = true))
    }

    /** 空查询词不命中 —— 否则「还没输入就搜」会把整棵树都当结果。 */
    @Test
    fun emptyQueryMatchesNothing() {
        assertFalse(textMatches("anything", "", null, caseSensitive = false))
    }
}
