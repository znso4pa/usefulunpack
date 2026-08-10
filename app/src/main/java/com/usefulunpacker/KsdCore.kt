package com.usefulunpacker
object KsdCore {
    init { System.loadLibrary("archive_ksd_core") }
    external fun ksdListEntries(input: String): String?
    external fun ksdExtract(tool: String, input: String, output: String): String?
    external fun ksdCompress(tool: String, input: String, output: String, level: String): String?
    external fun ksdExtractProgressCount(): Long
    external fun ksdExtractProgressTotal(): Long
    external fun ksdExtractProgressFileCount(): Long
    external fun ksdExtractProgressFileTotal(): Long
    external fun ksdExtractProgressName(): String?
    external fun ksdExtractCancel()
    external fun ksdCompressProgressCount(): Long
    external fun ksdCompressProgressTotal(): Long
    external fun ksdCompressProgressFileCount(): Long
    external fun ksdCompressProgressFileTotal(): Long
    external fun ksdCompressProgressName(): String?
    external fun ksdCompressCancel()
}
