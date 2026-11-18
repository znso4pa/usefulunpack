package com.usefulunpacker

/**
 * Lucifen Easy Game System `.lpk`。
 *
 * 密钥由**文件名**派生，所以归档改名就解不开 —— 这是格式本身的设计。
 * 数据包（`*.LPK` 里除 `SCRIPT.LPK` 外的那些）还需要同目录的 `SCRIPT.LPK`
 * 提供逐文件密钥；没有它时原生层会先在游戏目录里暴力找密钥对，再不行才报错。
 *
 * 封包支持，但**不含 LZSS 压缩**：引擎的编码器从未公开，写错会静默产出坏文件，
 * 所以一律按未压缩条目写出（`flag1` 头），引擎照常能读。密钥对（`aux`）用
 * `"0x12345678:0x9abcdef0"` 形式传入，只有被 `SCRIPT.LPK` 索引的归档才需要；
 * `SCRIPT.LPK` 自己留空即可。
 */
object LpkCore {
    init { System.loadLibrary("archive_lpk_core") }
    external fun lpkExtract(tool: String, input: String, output: String): String?
    external fun lpkExtractSelected(tool: String, input: String, output: String, selected: String): String?
    external fun lpkListEntries(input: String): String?
    external fun lpkCreateArchive(tool: String, input: String, output: String, aux: String): Boolean
    external fun lpkExtractProgressCount(): Long
    external fun lpkExtractProgressTotal(): Long
    external fun lpkExtractProgressFileCount(): Long
    external fun lpkExtractProgressFileTotal(): Long
    external fun lpkExtractProgressName(): String?
    external fun lpkExtractCancel()
    external fun lpkCompressProgressCount(): Long
    external fun lpkCompressProgressTotal(): Long
    external fun lpkCompressProgressFileCount(): Long
    external fun lpkCompressProgressFileTotal(): Long
    external fun lpkCompressProgressName(): String?
    external fun lpkCompressCancel()
}
