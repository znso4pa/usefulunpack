package com.usefulunpacker
object IsoCore {
    init { System.loadLibrary("archive_iso_core") }
    external fun isoExtract(tool: String, input: String, output: String): String?
    external fun isoExtractSelected(tool: String, input: String, output: String, selected: String): String?
    external fun isoListEntries(input: String): String?
    external fun isoExtractProgressCount(): Long
    external fun isoExtractProgressTotal(): Long
    external fun isoExtractProgressName(): String?
    external fun isoExtractCancel()
}
