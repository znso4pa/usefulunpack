package com.usefulunpacker
object YpfCore {
    init { System.loadLibrary("archive_ypf_core") }
    external fun ypfExtract(tool: String, input: String, output: String): Boolean
    external fun ypfExtractSelected(tool: String, input: String, output: String, selected: String): Boolean
    external fun ypfListEntries(input: String): String?
}
