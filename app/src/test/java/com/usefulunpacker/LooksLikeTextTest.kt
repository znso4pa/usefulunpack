package com.usefulunpacker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「只要是文本就能预览」的判定契约。
 *
 * 这些断言同时是规格说明：哪些字节序列算文本、哪些算二进制，都钉死在这里。
 * 判定复用项目已有的编码探测（[detectBestEncoding]），
 * 再补两条二进制信号（NUL 字节、控制字符比例）。
 */
class LooksLikeTextTest {

    private fun sjis(s: String) = s.toByteArray(charset("Shift_JIS"))
    private fun gbk(s: String) = s.toByteArray(charset("GBK"))
    private fun utf16le(s: String) = s.toByteArray(Charsets.UTF_16LE)

    @Test
    fun plainAsciiAndUtf8TextAreText() {
        assertTrue(looksLikeText("hello world\nsecond line\n".toByteArray()))
        assertTrue(looksLikeText("中文内容，第二行。\n".toByteArray(Charsets.UTF_8)))
        assertTrue(looksLikeText("日本語のスクリプト\r\n".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun legacyJapaneseAndChineseEncodingsAreText() {
        // 没有 BOM 的 Shift-JIS / GBK 是 galgame 脚本的常态，必须认。
        assertTrue(looksLikeText(sjis("こんにちは、世界。これはテストです。")))
        assertTrue(looksLikeText(gbk("你好，世界。这是一段测试文本，用于验证编码判定。")))
    }

    @Test
    fun bomlessUtf16LeIsText() {
        // Kirikiri2 的 .tjs/.ks 常见形态：ASCII 呈 [printable, 0x00] 对。
        assertTrue(looksLikeText(utf16le("label start:\n    \"hello\"\n    return\n")))
    }

    @Test
    fun aFewControlBytesDoNotMakeItBinary() {
        // 制表/换行/回车/换页都不算控制字符；偶发的 0x1b（ESC 着色）也在阈值内。
        val withAnsi = "\u001b[31mred\u001b[0m\n\tcolumn\tcolumn\r\n".toByteArray()
        assertTrue(looksLikeText(withAnsi))
    }

    @Test
    fun binarySignaturesAreNotText() {
        // PNG 头：既含 NUL 又含大量控制字节。
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            ByteArray(64) { (it * 37 and 0xFF).toByte() }
        assertFalse(looksLikeText(png))
        // 全 NUL
        assertFalse(looksLikeText(ByteArray(64)))
        // 纯控制字节
        assertFalse(looksLikeText(ByteArray(64) { 0x01 }))
        // 高熵随机
        val rnd = java.util.Random(7).let { r -> ByteArray(512) { r.nextInt(256).toByte() } }
        assertFalse(looksLikeText(rnd))
    }

    @Test
    fun emptyInputIsNotClaimedAsText() {
        // 空文件没有任何信号：不声称是文本（否则任何空文件都会走文本预览）。
        assertFalse(looksLikeText(ByteArray(0)))
    }

    @Test
    fun theEncodingGateAgreesWithTheTextGate() {
        // `textEncodingOf` 是 uu cat / uu grep / 预览共用的那一处判定：不是文本就
        // 返回 null（调用方据此拒绝），是文本就给出要用的编码。
        assertEquals("UTF-8", textEncodingOf("hello 世界\n".toByteArray()))
        assertEquals("SHIFT-JIS", textEncodingOf(sjis("こんにちは、世界。")))
        assertNull(textEncodingOf(ByteArray(16) { it.toByte() }))   // 0x00..0x0f：NUL + 控制字节
        assertNull(textEncodingOf(ByteArray(64)))
    }

    @Test
    fun theHeadSampleDecides() {
        // 预览只嗅探头部样本：同样是「文本头 + 二进制尾巴」，取头部就是文本、
        // 取到尾巴就是二进制。这条把「判定只看传入的样本」钉死，免得以后有人
        // 以为它会读整个文件。
        val head = "P3\n# comment\n".toByteArray()
        val tail = ByteArray(128) { (it * 7 and 0xFF).toByte() }
        assertTrue(looksLikeText(head))
        assertFalse(looksLikeText(tail))
    }
}
