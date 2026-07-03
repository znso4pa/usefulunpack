package com.usefulunpacker
object ZipCore {
    init { System.loadLibrary("archive_zip_core") }
    external fun zipExtract(tool: String, input: String, output: String): Boolean
    external fun zipExtractSelected(tool: String, input: String, output: String, selected: String): Boolean
    external fun zipListEntries(input: String): String?
    external fun zipExtractWithPassword(tool: String, input: String, output: String, password: String): Boolean
    external fun zipCompress(tool: String, input: String, output: String, level: String, password: String): Boolean
}
