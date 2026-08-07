package com.usefulunpacker
object LzmaCore {
    init { System.loadLibrary("archive_lzma_core") }
    external fun lzmaListEntries(input: String): String?
    external fun lzmaExtract(tool: String, input: String, output: String): String?
    external fun lzmaExtractProgressCount(): Long
    external fun lzmaExtractProgressTotal(): Long
    external fun lzmaExtractProgressFileCount(): Long
    external fun lzmaExtractProgressFileTotal(): Long
    external fun lzmaExtractProgressName(): String?
    external fun lzmaExtractCancel()
    external fun lzmaCompress(tool: String, input: String, output: String, level: String): Boolean
    external fun lzmaCompressProgressCount(): Long
    external fun lzmaCompressProgressTotal(): Long
    external fun lzmaCompressProgressFileCount(): Long
    external fun lzmaCompressProgressFileTotal(): Long
    external fun lzmaCompressProgressName(): String?
    external fun lzmaCompressCancel()
}
