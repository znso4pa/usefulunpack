package com.usefulunpacker
object XzCore {
    init { System.loadLibrary("archive_xz_core") }
    external fun xzListEntries(input: String): String?
    external fun xzExtract(tool: String, input: String, output: String): String?
    external fun xzExtractProgressCount(): Long
    external fun xzExtractProgressTotal(): Long
    external fun xzExtractProgressFileCount(): Long
    external fun xzExtractProgressFileTotal(): Long
    external fun xzExtractProgressName(): String?
    external fun xzExtractCancel()
    external fun xzCompress(tool: String, input: String, output: String, level: String): Boolean
    external fun xzCompressProgressCount(): Long
    external fun xzCompressProgressTotal(): Long
    external fun xzCompressProgressFileCount(): Long
    external fun xzCompressProgressFileTotal(): Long
    external fun xzCompressProgressName(): String?
    external fun xzCompressCancel()
}
