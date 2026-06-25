package com.usefulunpacker
object IsoCore {
    init { System.loadLibrary("archive_iso_core") }
    external fun isoExtract(tool: String, input: String, output: String): Boolean
    external fun isoExtractSelected(tool: String, input: String, output: String, selected: String): Boolean
    external fun isoListEntries(input: String): String?
}
