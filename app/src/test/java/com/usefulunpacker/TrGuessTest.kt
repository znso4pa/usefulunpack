package com.usefulunpacker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `uu guess` 的猜测器。
 *
 * 最要紧的一组是**噪声不许命中**：猜测器的全部风险就是「把巧合当证据」，而
 * 「变换后前几字节能对上魔数」本身就是同义反复（密钥是从魔数反推的），所以
 * 门槛只能靠**约束数**和**结构检查**撑起来。
 */
class TrGuessTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 一个最小的真 zip（含本地头 + 数据 + 中央目录 + EOCD）。 */
    private fun realZip(): ByteArray = java.io.ByteArrayOutputStream().also { bos ->
        java.util.zip.ZipOutputStream(bos).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("a.txt"))
            z.write("hello".toByteArray())
            z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("b/c.txt"))
            z.write("world!!".toByteArray())
            z.closeEntry()
        }
    }.toByteArray()

    private fun guess(bytes: ByteArray): List<TrCandidate> =
        TrGuesser.guess(TrProbe(bytes.copyOf(minOf(bytes.size, TrGuesser.HEAD_PROBE)), bytes.size.toLong()))

    private fun pipelines(hits: List<TrCandidate>) = hits.map { it.pipeline }
    private fun formats(hits: List<TrCandidate>) = hits.map { it.format }.toSet()

    // ── 魔数表 ──

    @Test
    fun magicTableCoversTheFormatsItClaims() {
        assertTrue("zip 认不出来", detectFormatByMagic(realZip()) == "zip")
        // XP3 的 11 字节魔数要能整条对上（别退化成只认 "XP3"）
        val xp3 = "XP3\r\n \n\u001A\u008B\u0067\u0001".toByteArray(Charsets.ISO_8859_1)
        assertEquals("xp3", detectFormatByMagic(xp3))
        assertEquals("xp3", detectFormatByMagic(xp3.copyOf(11)))
        // 只写 "XP3" 三个字节不算数 —— 那正是要避免的弱化
        assertEquals(null, detectFormatByMagic("XP3".toByteArray()))
        // 7z 的 8 字节（6 签名 + 版本 00 04）
        val sz = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0x00, 0x04)
        assertEquals("7z", detectFormatByMagic(sz))
    }

    // ── 正例 ──

    @Test
    fun findsASingleByteXor() {
        val raw = realZip()
        val hits = guess(ByteArray(raw.size) { (raw[it].toInt() xor 0xAA).toByte() })
        assertTrue("没找到 xor 0xAA: ${pipelines(hits)}", pipelines(hits).contains("xor AA"))
        assertEquals(setOf("zip"), formats(hits))
        // zip 的本地头链是结构检查，所以证据应该是 struct 而不是光靠魔数
        val top = hits.first { it.pipeline == "xor AA" }
        assertTrue("证据不足: $top", top.evidence is TrEvidence.Structure)
    }

    @Test
    fun findsARepeatingKey() {
        val raw = realZip()
        val hits = guess(ByteArray(raw.size) { (raw[it].toInt() xor (if (it % 2 == 0) 0xAA else 0x37)).toByte() })
        assertTrue("没找到 xor AA37: ${pipelines(hits)}", pipelines(hits).contains("xor AA37"))
        assertEquals(setOf("zip"), formats(hits))
    }

    @Test
    fun findsAddAndRotAndNot() {
        val raw = realZip()
        // 注意方向：猜出来的是**逆变换**。加了 0x2B 的密文要靠 `sub 0x2B`（或等价的
        // `add 0xD5`）还原；右旋 3 位的要靠 `rot l 3`（或等价的 `rot r 5`）还原。
        assertTrue(pipelines(guess(ByteArray(raw.size) { (raw[it] + 0x2B).toByte() }))
            .any { it == "sub 0x2B" || it == "add 0xD5" })
        assertTrue(pipelines(guess(ByteArray(raw.size) { raw[it].rotateRight(3) }))
            .any { it == "rot l 3" || it == "rot r 5" })
        assertTrue(pipelines(guess(ByteArray(raw.size) { raw[it].toInt().inv().toByte() })).contains("not"))
    }

    @Test
    fun findsAPrependedStubWithNoTransform() {
        val raw = realZip()
        val hits = guess(ByteArray(16) + raw)
        assertTrue("没找到 skip 16: ${pipelines(hits)}", pipelines(hits).contains("skip 16"))
        assertEquals(setOf("zip"), formats(hits))
    }

    @Test
    fun findsASkipCombinedWithAKey() {
        val raw = realZip()
        val hits = guess(ByteArray(8) + ByteArray(raw.size) { (raw[it].toInt() xor 0x5A).toByte() })
        assertTrue("没找到 skip 8 | xor 5A: ${pipelines(hits)}", pipelines(hits).contains("skip 8 | xor 5A"))
    }

    // ── 反例：噪声不许命中 ──

    @Test
    fun randomLookingDataYieldsNothing() {
        // 0..255 斜坡：任何一个 (magic, skip, op) 都对不上第二个字节
        val ramp = ByteArray(512) { it.toByte() }
        val hits = guess(ramp)
        assertTrue("噪声里报出了候选: ${pipelines(hits)}", hits.isEmpty())
    }

    @Test
    fun aPlainTextFileYieldsNothing() {
        val text = ("The quick brown fox jumps over the lazy dog. ".repeat(20)).toByteArray()
        assertTrue("普通文本里报出了候选: ${pipelines(guess(text))}", guess(text).isEmpty())
    }

    // ── 候选渲染：必须能直接粘回 uu tr ──

    @Test
    fun everyCandidateRendersIntoAParseablePipeline() {
        val raw = realZip()
        val hits = guess(ByteArray(8) + ByteArray(raw.size) { (raw[it].toInt() xor 0xA5).toByte() })
        assertTrue(hits.isNotEmpty())
        for (h in hits) {
            // 渲染出来的文本必须能被解析器读回去 —— 否则「试试这条命令」就是假的
            val parsed = TrParser.parse(h.pipeline)
            assertTrue("候选渲染不回去: ${h.pipeline}", parsed is TrParser.Out.Ok)
            assertEquals(h.pipeline, (parsed as TrParser.Out.Ok).pipeline.text())
        }
    }
}
