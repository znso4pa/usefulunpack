package com.usefulunpacker
object BrotliCore {
    init { System.loadLibrary("archive_brotli_core") }
    external fun brotliListEntries(input: String): String?
    external fun brotliExtract(tool: String, input: String, output: String): String?
    external fun brotliExtractProgressCount(): Long
    external fun brotliExtractProgressTotal(): Long
    external fun brotliExtractProgressFileCount(): Long
    external fun brotliExtractProgressFileTotal(): Long
    external fun brotliExtractProgressName(): String?
    external fun brotliExtractCancel()
    external fun brotliCompress(tool: String, input: String, output: String, level: String): Boolean
    external fun brotliCompressProgressCount(): Long
    external fun brotliCompressProgressTotal(): Long
    external fun brotliCompressProgressFileCount(): Long
    external fun brotliCompressProgressFileTotal(): Long
    external fun brotliCompressProgressName(): String?
    external fun brotliCompressCancel()
}
