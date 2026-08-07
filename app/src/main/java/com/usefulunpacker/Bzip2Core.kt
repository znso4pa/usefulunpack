package com.usefulunpacker
object Bzip2Core {
    init { System.loadLibrary("archive_bzip2_core") }
    external fun bz2ListEntries(input: String): String?
    external fun bz2Extract(tool: String, input: String, output: String): String?
    external fun bz2ExtractProgressCount(): Long
    external fun bz2ExtractProgressTotal(): Long
    external fun bz2ExtractProgressFileCount(): Long
    external fun bz2ExtractProgressFileTotal(): Long
    external fun bz2ExtractProgressName(): String?
    external fun bz2ExtractCancel()
    external fun bz2Compress(tool: String, input: String, output: String, level: String): Boolean
    external fun bz2CompressProgressCount(): Long
    external fun bz2CompressProgressTotal(): Long
    external fun bz2CompressProgressFileCount(): Long
    external fun bz2CompressProgressFileTotal(): Long
    external fun bz2CompressProgressName(): String?
    external fun bz2CompressCancel()
}
