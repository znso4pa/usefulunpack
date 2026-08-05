package com.usefulunpacker
object YpfCore {
    init { System.loadLibrary("archive_ypf_core") }
    external fun ypfExtract(tool: String, input: String, output: String): String?
    external fun ypfExtractSelected(tool: String, input: String, output: String, selected: String): String?
    external fun ypfListEntries(input: String): String?
    external fun ypfExtractProgressCount(): Long
    external fun ypfExtractProgressTotal(): Long
    external fun ypfExtractProgressName(): String?
    external fun ypfExtractCancel()
}
