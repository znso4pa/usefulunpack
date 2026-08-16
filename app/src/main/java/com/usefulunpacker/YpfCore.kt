package com.usefulunpacker
object YpfCore {
    init { System.loadLibrary("archive_ypf_core") }
    external fun ypfExtract(tool: String, input: String, output: String): String?
    external fun ypfExtractSelected(tool: String, input: String, output: String, selected: String): String?
    external fun ypfListEntries(input: String): String?
    external fun ypfExtractProgressCount(): Long
    external fun ypfExtractProgressTotal(): Long
    external fun ypfExtractProgressFileCount(): Long
    external fun ypfExtractProgressFileTotal(): Long
    external fun ypfExtractProgressName(): String?
    external fun ypfExtractCancel()
    external fun ypfCreateArchive(tool: String, input: String, output: String, level: String): Boolean
    external fun ypfCompressProgressCount(): Long
    external fun ypfCompressProgressTotal(): Long
    external fun ypfCompressProgressFileCount(): Long
    external fun ypfCompressProgressFileTotal(): Long
    external fun ypfCompressProgressName(): String?
    external fun ypfCompressCancel()
}
