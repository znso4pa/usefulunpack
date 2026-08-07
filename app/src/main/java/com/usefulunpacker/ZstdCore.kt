package com.usefulunpacker
object ZstdCore {
    init { System.loadLibrary("archive_zstd_core") }
    external fun zstListEntries(input: String): String?
    external fun zstExtract(tool: String, input: String, output: String): String?
    external fun zstExtractProgressCount(): Long
    external fun zstExtractProgressTotal(): Long
    external fun zstExtractProgressFileCount(): Long
    external fun zstExtractProgressFileTotal(): Long
    external fun zstExtractProgressName(): String?
    external fun zstExtractCancel()
    external fun zstCompress(tool: String, input: String, output: String, level: String): Boolean
    external fun zstCompressProgressCount(): Long
    external fun zstCompressProgressTotal(): Long
    external fun zstCompressProgressFileCount(): Long
    external fun zstCompressProgressFileTotal(): Long
    external fun zstCompressProgressName(): String?
    external fun zstCompressCancel()
}
