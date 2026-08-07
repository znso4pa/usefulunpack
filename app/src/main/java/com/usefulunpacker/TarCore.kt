package com.usefulunpacker
object TarCore {
    init { System.loadLibrary("archive_tar_core") }
    external fun tarListEntries(input: String): String?
    external fun tarExtract(tool: String, input: String, output: String): String?
    external fun tarExtractSelected(tool: String, input: String, output: String, selected: String): String?
    external fun tarExtractProgressCount(): Long
    external fun tarExtractProgressTotal(): Long
    external fun tarExtractProgressFileCount(): Long
    external fun tarExtractProgressFileTotal(): Long
    external fun tarExtractProgressName(): String?
    external fun tarExtractCancel()
    external fun tarCompress(tool: String, input: String, output: String, format: String, level: String): Boolean
    external fun tarCompressProgressCount(): Long
    external fun tarCompressProgressTotal(): Long
    external fun tarCompressProgressFileCount(): Long
    external fun tarCompressProgressFileTotal(): Long
    external fun tarCompressProgressName(): String?
    external fun tarCompressCancel()
}
