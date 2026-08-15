package com.usefulunpacker
object IsoCore {
    init { System.loadLibrary("archive_iso_core") }
    external fun isoExtract(tool: String, input: String, output: String): String?
    external fun isoExtractSelected(tool: String, input: String, output: String, selected: String): String?
    external fun isoListEntries(input: String): String?
    external fun isoExtractProgressCount(): Long
    external fun isoExtractProgressTotal(): Long
    external fun isoExtractProgressFileCount(): Long
    external fun isoExtractProgressFileTotal(): Long
    external fun isoExtractProgressName(): String?
    external fun isoExtractCancel()
    external fun isoCreateArchive(tool: String, input: String, output: String): Boolean
    external fun isoCompressProgressCount(): Long
    external fun isoCompressProgressTotal(): Long
    external fun isoCompressProgressFileCount(): Long
    external fun isoCompressProgressFileTotal(): Long
    external fun isoCompressProgressName(): String?
    external fun isoCompressCancel()
}
