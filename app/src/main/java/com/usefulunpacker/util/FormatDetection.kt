package com.usefulunpacker

import java.io.File
import java.io.FileInputStream
/**
 * Detects the archive format from a file name (delegates to the shared
 * [formatOfName] mapping). Volume parts (`foo.7z.001`) are not handled here —
 * callers should prefer the result of [isVolumeFile] and only fall back to
 * this. Returns null when the extension is unknown, so the caller can fall
 * back to the manual format picker.
 */
fun detectFormat(f: File): String? = formatOfName(f.name)

/**
 * Sniffs a file's magic bytes to identify archives that lost their extension
 * (e.g. `game.xp3` renamed to `game`). Returns a format key or null. Only
 * covers formats with a stable leading signature; unknown magic files are
 * left to the normal file handling.
 */
fun detectFormatByMagic(f: File): String? {
    if (!f.isFile) return null
    val sig = try {
        val buf = ByteArray(MAGIC_PROBE)
        val n = FileInputStream(f).use { it.read(buf) }
        if (n <= 0) return null else buf.copyOf(n)
    } catch (failureCause: Exception) { com.usefulunpacker.AppDiagnostics.failure("FileUtils", failureCause); return null }
    return detectFormatByMagic(sig)
}

/** 魔数探测窗口。最长的一条是 XP3 的 11 字节，留到 16 够用。 */
const val MAGIC_PROBE = 16

/**
 * 魔数表：**前缀字节 → 格式 key**。`detectFormatByMagic` 与 `uu guess` 共用这一份。
 *
 * 共用的理由：猜测器要的正是这些**字节本身**（它得从 `密文 ^ 魔数` 反推密钥），
 * 手抄第二份必然漂 —— 这个仓库已经因为「同一份清单被抄成几处」吃过亏。
 *
 * **前缀长度就是证据强度**：`uu guess` 每命中一条魔数，单参数变换就多 `len - 1` 个
 * 独立约束。所以能写长的就写长 —— XP3 的 11 字节、RPA 的 8 字节都是白送的强度。
 */
val MAGICS: List<Pair<ByteArray, String>> = listOf(
    // 7z 的魔数其实是 8 字节：6 字节签名 + 2 字节格式版本（主/次），真实文件恒为 00 04。
    byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0x00, 0x04) to "7z",
    // zip 用本地文件头签名（4 字节）。空归档只有 EOCD（PK\x05\x06）会被漏掉 ——
    // 但那种文件本来也没有内容可解，而且它的扩展名还在。
    byteArrayOf(0x50, 0x4B, 0x03, 0x04) to "zip",
    // RAR4 / RAR5 共有这 7 字节（第 7 字节才分版本）。
    byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) to "rar",
    // gzip：魔数 2 字节 + CM=8（deflate，唯一定义过的方法）。
    byteArrayOf(0x1F, 0x8B.toByte(), 0x08) to "gz",
    byteArrayOf(0x42, 0x5A, 0x68) to "bz2",
    byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00) to "xz",
    byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()) to "zst",
    byteArrayOf(0x04, 0x22, 0x4D, 0x18) to "lz4",
    // XP3 的魔数固定 11 字节（"XP3\r\n \n\x1A\x8B\x67\x01"）—— 别只写 "XP3"。
    byteArrayOf(
        0x58, 0x50, 0x33, 0x0D, 0x0A, 0x20, 0x0A,
        0x1A, 0x8B.toByte(), 0x67, 0x01,
    ) to "xp3",
    // Ren'Py archives keep their magic at offset 0 even when renamed. The
    // 8-byte probe window covers every variant; `.rpi` (RPA-1.0) is
    // deliberately absent because its only signature is the 2-byte zlib
    // header, which is far too generic to sniff on.
    "RPA-3.0 ".toByteArray(Charsets.US_ASCII) to "rpa",
    "RPA-3.2 ".toByteArray(Charsets.US_ASCII) to "rpa",
    "RPA-4.0 ".toByteArray(Charsets.US_ASCII) to "rpa",
    "RPA-2.0 ".toByteArray(Charsets.US_ASCII) to "rpa",
    "ALT-1.0 ".toByteArray(Charsets.US_ASCII) to "rpa",
    // CatSystem2's KIF archives (`.int`); the index key still comes from the
    // game's exe, which the native side looks for next to the archive.
    byteArrayOf(0x4B, 0x49, 0x46, 0x00) to "int",
    // BGI/Ethornell `.arc`: the 4-byte "Pack" alone is far too generic, so the
    // magic runs to 12 bytes ("Pack" + "File    "). The v2 layout ("BURI" +
    // "KO ARC20") is not sniffed — those files keep their extension, and a
    // 4-byte prefix would collide with unrelated data.
    "PackFile    ".toByteArray(Charsets.US_ASCII) to "bgi",
    // Lucifen `.lpk`. Four bytes is weak, but the native reader immediately
    // checks the flag byte at +4, so a false sniff fails loudly instead of
    // producing nonsense entries.
    byteArrayOf(0x4C, 0x50, 0x4B, 0x31) to "lpk",
    // RPG Maker RGSS: "RGSSAD\0" + the version byte (1 = XP, 2 = VX, 3 = VX Ace),
    // so a game using a non-standard archive name still opens. All three map to
    // the one read key — the parser reads the header.
    byteArrayOf(0x52, 0x47, 0x53, 0x53, 0x41, 0x44, 0x00) to "rgss",
    // RPG Maker MV/MZ obfuscated asset: the fixed 16-byte "RPGMV..." header.
    "RPGMV".toByteArray(Charsets.US_ASCII) to "rpgmv",
)

/**
 * 魔数判定的**字节版核心** —— 磁盘版只是它的读盘包装。
 *
 * 单独拆出来的原因：`uu tr` 变换完要先看看「变成了什么」（不必落盘就能判），
 * `uu guess` 更是要拿它当判据跑几百次候选。两处都只认 [MAGICS] 这一份前缀表。
 */
fun detectFormatByMagic(sig: ByteArray): String? =
    MAGICS.firstOrNull { m ->
        sig.size >= m.first.size && sig.copyOf(m.first.size).contentEquals(m.first)
    }?.second
