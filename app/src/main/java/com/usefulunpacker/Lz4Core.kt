package com.usefulunpacker
object Lz4Core {
    init { System.loadLibrary("archive_lz4_core") }
    external fun lz4ListEntries(input: String): String?
    external fun lz4Extract(tool: String, input: String, output: String): String?
    external fun lz4ExtractProgressCount(): Long
    external fun lz4ExtractProgressTotal(): Long
    external fun lz4ExtractProgressFileCount(): Long
    external fun lz4ExtractProgressFileTotal(): Long
    external fun lz4ExtractProgressName(): String?
    external fun lz4ExtractCancel()
    external fun lz4Compress(tool: String, input: String, output: String, level: String): Boolean
    external fun lz4CompressProgressCount(): Long
    external fun lz4CompressProgressTotal(): Long
    external fun lz4CompressProgressFileCount(): Long
    external fun lz4CompressProgressFileTotal(): Long
    external fun lz4CompressProgressName(): String?
    external fun lz4CompressCancel()
}
