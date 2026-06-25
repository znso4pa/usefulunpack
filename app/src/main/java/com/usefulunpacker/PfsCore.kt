package com.usefulunpacker
object PfsCore {
    init { System.loadLibrary("archive_pfs_core") }
    external fun pfsExtract(tool: String, input: String, output: String): Boolean
    external fun pfsExtractSelected(tool: String, input: String, output: String, selected: String): Boolean
    external fun pfsListEntries(input: String): String?
}
