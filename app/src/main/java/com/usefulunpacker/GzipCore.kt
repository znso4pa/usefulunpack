package com.usefulunpacker
object GzipCore {
    init { System.loadLibrary("archive_gzip_core") }
    external fun gzListEntries(input: String): String?
    external fun gzExtract(tool: String, input: String, output: String): String?
    external fun gzExtractProgressCount(): Long
    external fun gzExtractProgressTotal(): Long
    external fun gzExtractProgressFileCount(): Long
    external fun gzExtractProgressFileTotal(): Long
    external fun gzExtractProgressName(): String?
    external fun gzExtractCancel()
    external fun gzCompress(tool: String, input: String, output: String, level: String): Boolean
    external fun gzCompressProgressCount(): Long
    external fun gzCompressProgressTotal(): Long
    external fun gzCompressProgressFileCount(): Long
    external fun gzCompressProgressFileTotal(): Long
    external fun gzCompressProgressName(): String?
    external fun gzCompressCancel()
}
