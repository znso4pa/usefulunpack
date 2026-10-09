package com.usefulunpacker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 重封包时「镜像源归档的加密」这条规则。
 *
 * 三个调用点共用 [xp3RepackEnc]（GUI 重打包 / 合并进目标包 / CLI `uu set`），
 * 所以映射只在这里钉一次。搞错方向的后果不对称：漏了加密会产出游戏读不了的
 * 包，而多加了加密会把一个本该明文的包弄坏 —— 所以「拿不准就不加密」是刻意的。
 */
class Xp3RepackEncTest {

    @Test
    fun plainAndUnresolvableTokensRepackPlain() {
        assertEquals("", xp3RepackEnc(""))
        assertEquals("", xp3RepackEnc("plain"))
        assertEquals("", xp3RepackEnc("suspect"))
        // `cxdec:?` = 目录里有 cxdec 配套文件，但没有任何已知方案能解开它。
        // 没有可镜像的方案，猜一个比明文更糟。
        assertEquals("", xp3RepackEnc("cxdec:?"))
    }

    /** cxdec 传的是家族名：具体方案由打包器从目录里现查。 */
    @Test
    fun cxdecPassesTheFamilyNameSoThePackerCanResolveIt() {
        assertEquals("cxdec", xp3RepackEnc("cxdec:cxdec feng template"))
        assertEquals("cxdec", xp3RepackEnc("cxdec:cxdec karakara"))
    }

    /**
     * 无密钥方案没有可现查的东西 —— 密钥就是各条目自己的 ADLR —— 所以 token
     * 原样透传，打包器不需要目录里的任何东西。
     */
    @Test
    fun keylessSchemesPassTheirTokenThrough() {
        assertEquals("crypt:HashCrypt", xp3RepackEnc("crypt:HashCrypt"))
        assertEquals("crypt:FateCrypt", xp3RepackEnc("crypt:FateCrypt"))
        assertEquals("crypt:AppliqueCrypt", xp3RepackEnc("crypt:AppliqueCrypt"))
        assertEquals("crypt:FlyingShineCrypt", xp3RepackEnc("crypt:FlyingShineCrypt"))
        assertEquals("crypt:AlteredPinkCrypt", xp3RepackEnc("crypt:AlteredPinkCrypt"))
        assertEquals("crypt:DameganeCrypt", xp3RepackEnc("crypt:DameganeCrypt"))
        assertEquals("crypt:NatsupochiCrypt", xp3RepackEnc("crypt:NatsupochiCrypt"))
        assertEquals("crypt:OkibaCrypt", xp3RepackEnc("crypt:OkibaCrypt"))
        assertEquals("crypt:DieselmineCrypt", xp3RepackEnc("crypt:DieselmineCrypt"))
    }

    /** CLI `-e <name>` → 打包参数。大小写不敏感，落到打包器的一律是规范拼写。 */
    @Test
    fun cliSchemeNamesNormalizeToCanonicalSpelling() {
        assertEquals("", UuCommands.normalizePackEnc(""))
        assertEquals("cxdec", UuCommands.normalizePackEnc("cxdec"))
        assertEquals("cxdec", UuCommands.normalizePackEnc("CXDEC"))
        assertEquals("crypt:HashCrypt", UuCommands.normalizePackEnc("hashcrypt"))
        assertEquals("crypt:FateCrypt", UuCommands.normalizePackEnc("FateCrypt"))
        assertEquals("crypt:AppliqueCrypt", UuCommands.normalizePackEnc(" appliquecrypt "))
        assertEquals("crypt:FlyingShineCrypt", UuCommands.normalizePackEnc("flyingShineCrypt"))
        assertEquals("crypt:AlteredPinkCrypt", UuCommands.normalizePackEnc("alteredpinkcrypt"))
        assertEquals("crypt:DameganeCrypt", UuCommands.normalizePackEnc("DAMEGANECRYPT"))
        assertEquals("crypt:NatsupochiCrypt", UuCommands.normalizePackEnc("natsupochicrypt"))
        assertEquals("crypt:OkibaCrypt", UuCommands.normalizePackEnc(" OkibaCrypt "))
        assertEquals("crypt:DieselmineCrypt", UuCommands.normalizePackEnc("dieselminecrypt"))
        // `fsn` = arc_unpacker 的插件名，也是 FateCrypt 唯一接受的别名。
        assertEquals("crypt:FateCrypt", UuCommands.normalizePackEnc("fsn"))
        assertEquals("crypt:FateCrypt", UuCommands.normalizePackEnc("FSn"))
        // 其他方案没有别名：`xor` / `rebirth` 是插件内部 id，不是玩家认得的名字。
        assertNull(UuCommands.normalizePackEnc("xor"))
        assertNull(UuCommands.normalizePackEnc("rebirth"))
        // 未知名字返回 null —— 调用方据此报错，绝不静默降级成明文。
        assertNull(UuCommands.normalizePackEnc("rot13"))
    }
}
