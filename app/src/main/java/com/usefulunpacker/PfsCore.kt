package com.usefulunpacker
object PfsCore {
    init { System.loadLibrary("archive_pfs_core") }
    external fun pfsExtract(tool: String, input: String, output: String): String?
    external fun pfsExtractSelected(tool: String, input: String, output: String, selected: String): String?
    external fun pfsListEntries(input: String): String?
    external fun pfsExtractProgressCount(): Long
    external fun pfsExtractProgressTotal(): Long
    external fun pfsExtractProgressFileCount(): Long
    external fun pfsExtractProgressFileTotal(): Long
    external fun pfsExtractProgressName(): String?
    external fun pfsExtractCancel()
    external fun pfsCreateArchive(tool: String, input: String, output: String): String?
    // PF6 封包（独立 writer：同 pf8 索引布局、pf6 魔数、无加密）
    external fun pfsCreateArchivePf6(tool: String, input: String, output: String): String?
    external fun pfsCompressProgressCount(): Long
    external fun pfsCompressProgressTotal(): Long
    external fun pfsCompressProgressFileCount(): Long
    external fun pfsCompressProgressFileTotal(): Long
    external fun pfsCompressProgressName(): String?
    external fun pfsCompressCancel()
}
