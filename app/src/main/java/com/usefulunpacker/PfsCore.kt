package com.usefulunpacker
object PfsCore {
    init { System.loadLibrary("archive_pfs_core") }
    external fun pfsExtract(tool: String, input: String, output: String): String?
    external fun pfsExtractSelected(tool: String, input: String, output: String, selected: String): String?
    external fun pfsListEntries(input: String): String?
    external fun pfsExtractProgressCount(): Long
    external fun pfsExtractProgressTotal(): Long
    external fun pfsExtractProgressFileCount(): Long
    external fun pfsExtractProgressFileTotal(): Long
    external fun pfsExtractProgressName(): String?
    external fun pfsExtractCancel()
}
