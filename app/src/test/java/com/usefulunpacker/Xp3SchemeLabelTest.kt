package com.usefulunpacker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * XP3 加密状态标签的契约。
 *
 * 四态 token 由原生探测（`xp3SchemeToken`）给出，转文案只有 `xp3SchemeLabel`
 * 一处 —— 预览标题 / 扫描结果 / `uu l` 共用它，所以这里钉住「哪个 token 给
 * 哪个资源、未知 token 不给话」。token 集合本身在 Rust 侧定义，本测试只覆盖
 * Kotlin 侧的映射（原生探测的分支由 cxdec-core / xp3-core 的测试覆盖）。
 */
class Xp3SchemeLabelTest {

    /** str stub 把资源 id 与参数拼进文本，便于断言。 */
    private fun str(): StrFn = { id, args -> "!str:$id" + args.joinToString("") { "[$it]" } }

    private fun label(token: String) = xp3SchemeLabel(str(), token)

    @Test
    fun eachTokenMapsToItsOwnString() {
        assertEquals("!str:${R.string.xp3_enc_plain}", label("plain"))
        assertEquals("!str:${R.string.xp3_enc_cxdec}", label("cxdec:cxdec feng template"))
        assertEquals("!str:${R.string.xp3_enc_unknown}", label("cxdec:?"))
        assertEquals("!str:${R.string.xp3_enc_suspect}", label("suspect"))
    }

    /** An empty token (probe failed / not xp3) must say nothing at all — a
     *  wrong label is worse than no label, and the title falls back to the
     *  bare file name. */
    @Test
    fun emptyAndUnknownTokensSayNothing() {
        assertEquals("", label(""))
        assertEquals("", label("whatever"))
        assertEquals("", label("cxde"))
    }

    /** Every token the native probe can emit is covered above; if a new one is
     *  added, the label map must grow with it (this is the reminder). */
    @Test
    fun labelAlwaysNamesAStringForKnownTokens() {
        for (token in listOf("plain", "cxdec:x", "cxdec:?", "suspect")) {
            assertTrue("token $token must map to a string", label(token).startsWith("!str:"))
        }
    }
}
