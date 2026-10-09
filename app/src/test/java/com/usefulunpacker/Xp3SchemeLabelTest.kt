package com.usefulunpacker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * XP3 加密状态标签的契约。
 *
 * token 由原生探测（`xp3SchemeToken`）给出，转文案只有 `xp3SchemeLabel`
 * 一处 —— 预览标题 / 扫描结果 / `uu l` 共用它，所以这里钉住「哪个 token 给
 * 哪个资源、方案名必须原样带出来、未知 token 不给话」。token 集合本身在 Rust
 * 侧定义，本测试只覆盖 Kotlin 侧的映射（原生探测的分支由 cxdec-core /
 * xp3crypt-core / xp3-core 的测试覆盖）。
 */
class Xp3SchemeLabelTest {

    /** str stub 把资源 id 与参数拼进文本，便于断言。 */
    private fun str(): StrFn = { id, args -> "!str:$id" + args.joinToString("") { "[$it]" } }

    private fun label(token: String) = xp3SchemeLabel(str(), token)

    @Test
    fun eachTokenMapsToItsOwnString() {
        assertEquals("!str:${R.string.xp3_enc_plain}", label("plain"))
        assertEquals("!str:${R.string.xp3_enc_unknown}", label("cxdec:?"))
        assertEquals("!str:${R.string.xp3_enc_suspect}", label("suspect"))
    }

    /**
     * 方案名必须原样进文案 ——「这是哪一种加密」正是标签要回答的问题，把
     * `cxdec:<scheme>` 塌成一句固定的「cxdec 加密」就丢掉了这个信息。
     */
    @Test
    fun schemeNamesAreCarriedThrough() {
        assertEquals(
            "!str:${R.string.xp3_enc_named}[cxdec feng template]",
            label("cxdec:cxdec feng template"),
        )
        assertEquals("!str:${R.string.xp3_enc_named}[HashCrypt]", label("crypt:HashCrypt"))
        assertEquals("!str:${R.string.xp3_enc_named}[FateCrypt]", label("crypt:FateCrypt"))
        assertEquals("!str:${R.string.xp3_enc_named}[AppliqueCrypt]", label("crypt:AppliqueCrypt"))
    }

    /**
     * `cxdec:?` 是哨兵，不是名为「?」的方案：它以前缀匹配就能被吞掉，必须
     * 先判。
     */
    @Test
    fun unknownCxdecSentinelIsNotReadAsASchemeName() {
        assertEquals("!str:${R.string.xp3_enc_unknown}", label("cxdec:?"))
    }

    /**
     * 空 token（探测失败 / 非 xp3）必须一个字都不说 —— 错的标签比没有更糟，
     * 标题会退回裸文件名。同理，只有前缀没有名字时也不说，否则会渲染出
     * 「加密：」这种半句话。
     */
    @Test
    fun emptyAndUnknownTokensSayNothing() {
        assertEquals("", label(""))
        assertEquals("", label("whatever"))
        assertEquals("", label("cxde"))
        assertEquals("", label("cxdec:"))
        assertEquals("", label("crypt:"))
    }

    /** 原生探测能给出的每个 token 都在上面覆盖到了；新增 token 时标签映射
     *  必须跟着长（这条就是提醒）。 */
    @Test
    fun labelAlwaysNamesAStringForKnownTokens() {
        for (token in listOf("plain", "cxdec:x", "cxdec:?", "crypt:x", "suspect")) {
            assertTrue("token $token must map to a string", label(token).startsWith("!str:"))
        }
    }
}
