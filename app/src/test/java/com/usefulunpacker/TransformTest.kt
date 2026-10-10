package com.usefulunpacker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `uu tr` 的变换引擎。纯逻辑、无 Android 依赖，所以全部在纯 JVM 下断言。
 *
 * 这里最要紧的一组是**可逆性**：写方向（`uu c --post`）的安全性完全建立在
 * 「每个步骤都能给出逆」上面，所以「施加管道再施加逆管道必须逐字节回到原样」
 * 是这一层的核心契约，而不是顺手加的测试。
 */
class TransformTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ok(text: String): TrPipeline {
        val r = TrParser.parse(text)
        assertTrue("本该解析成功: $text", r is TrParser.Out.Ok)
        return (r as TrParser.Out.Ok).pipeline
    }

    private fun err(text: String): TrParser.Bad {
        val r = TrParser.parse(text)
        assertTrue("本该解析失败: $text", r is TrParser.Out.Err)
        return (r as TrParser.Out.Err).bad
    }

    // ── 解析 ──

    @Test
    fun parsesEveryStepInTheVocabulary() {
        assertEquals(1, ok("xor 0xAA").steps.size)
        assertEquals(1, ok("xor AABBCC").steps.size)
        assertEquals(1, ok("add 16").steps.size)
        assertEquals(1, ok("sub 0x10").steps.size)
        assertEquals(1, ok("not").steps.size)
        assertEquals(1, ok("rot r 3").steps.size)
        assertEquals(1, ok("rot l 7").steps.size)
        // 多步 + 空白容忍
        val p = ok("  skip   16 | xor 0xAA | not ")
        assertEquals(3, p.steps.size)
        assertEquals(16L, p.skip)
    }

    @Test
    fun rejectsBadPipelines() {
        assertTrue(err("nope 1") is TrParser.Bad.UnknownStep)
        assertTrue(err("xor ZZ") is TrParser.Bad.BadArg)      // 非十六进制
        assertTrue(err("xor ABC") is TrParser.Bad.BadArg)     // 奇数长度
        assertTrue(err("add 256") is TrParser.Bad.BadArg)
        assertTrue(err("rot r 0") is TrParser.Bad.BadArg)
        assertTrue(err("rot r 8") is TrParser.Bad.BadArg)
        assertTrue(err("rot x 3") is TrParser.Bad.BadArg)
        assertTrue(err("not 1") is TrParser.Bad.BadArg)
        // `skip` 放中间时「丢的是变换前还是变换后的字节」有歧义 → 直接拒
        assertEquals(TrParser.Bad.SkipNotFirst, err("xor 0xAA | skip 4"))
        assertEquals(TrParser.Bad.SkipNotFirst, err("skip 4 | skip 4"))
        assertTrue(err((1..20).joinToString(" | ") { "not" }) is TrParser.Bad.TooManySteps)
        assertTrue(err("   ") is TrParser.Bad.UnknownStep)
    }

    @Test
    fun xorKeyLengthIsBounded() {
        assertEquals(1, ok("xor " + "AA".repeat(256)).steps.size)
        assertTrue(err("xor " + "AA".repeat(257)) is TrParser.Bad.BadArg)
    }

    // ── 可逆性：写方向的地基 ──

    @Test
    fun everyStepButSkipIsInvertible() {
        for (text in listOf("xor 0xAA", "xor AABB", "add 16", "sub 7", "not", "rot r 3", "rot l 5")) {
            val p = ok(text)
            assertTrue("$text 应该可逆", p.invertible)
            assertNotNull("$text 应该有逆管道", p.inverse())
        }
        // skip 真的丢了数据 → 不可逆
        assertFalse(ok("skip 16").invertible)
        assertNull(ok("skip 16").inverse())
        // 其他步都可逆也没用：只要 skip 非 0，整条管道就不可逆
        assertNull(ok("skip 16 | xor 0xAA").inverse())
    }

    /** 逆的定义就这一条：施加管道、再施加逆管道，必须逐字节回到原样。 */
    @Test
    fun pipelineThenInverseIsTheIdentity() {
        val src = ByteArray(300) { (it * 37 + 11).toByte() }
        for (text in listOf(
            "xor 0xAA", "xor AABB", "add 16", "sub 7", "not",
            "rot r 3", "rot l 5", "xor 0xAA | add 3 | rot r 2",
        )) {
            val p = ok(text)
            val inv = p.inverse()!!
            val there = ByteArray(src.size)
            for (i in src.indices) there[i] = p.steps.fold(src[i]) { acc, s -> s.apply(i.toLong(), acc) }
            val back = ByteArray(src.size)
            for (i in src.indices) back[i] = inv.steps.fold(there[i]) { acc, s -> s.apply(i.toLong(), acc) }
            assertArrayEquals("$text 的逆不对", src, back)
        }
    }

    /** 循环密钥按偏移取相位，且相位从 0 开始（不是从别的什么位置）。 */
    @Test
    fun repeatingKeyIsPhaseLockedToTheOffset() {
        val step = ok("xor AABB").steps[0]
        assertEquals(0x00.toByte(), step.apply(0, 0xAA.toByte()))
        assertEquals(0x00.toByte(), step.apply(1, 0xBB.toByte()))
        assertEquals(0x00.toByte(), step.apply(2, 0xAA.toByte()))
    }

    // ── 流式执行 ──

    @Test
    fun transformStreamsAndHonoursSkip() {
        val dir = tmp.root.resolve("tr").apply { mkdirs() }
        val src = File(dir, "src.bin").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        val dst = File(dir, "out.bin")
        val r = runTransform(src, 0, src.length(), ok("skip 8 | xor 0xFF"), dst)
        assertTrue(r is TrResult.Ok)
        assertArrayEquals(ByteArray(56) { ((it + 8) xor 0xFF).toByte() }, dst.readBytes())
        assertEquals(56L, (r as TrResult.Ok).written)
    }

    /** 源区间内的坐标从 0 开始 —— 与 `uu dd` 的 `skip` 语义一致。 */
    @Test
    fun transformHonoursTheSourceRangeAndZeroBasesTheOffset() {
        val dir = tmp.root.resolve("trr").apply { mkdirs() }
        val src = File(dir, "h.bin").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        val dst = File(dir, "o.bin")
        runTransform(src, 16, 16, ok("xor 0xFF"), dst)
        assertArrayEquals(ByteArray(16) { ((it + 16) xor 0xFF).toByte() }, dst.readBytes())
    }

    @Test
    fun notOpInvertsEveryBit() {
        val dir = tmp.root.resolve("tr0").apply { mkdirs() }
        val src = File(dir, "s.bin").apply { writeBytes(ByteArray(8) { it.toByte() }) }
        val dst = File(dir, "d.bin")
        runTransform(src, 0, src.length(), ok("not"), dst)
        assertArrayEquals(ByteArray(8) { it.toInt().inv().toByte() }, dst.readBytes())
    }
}
