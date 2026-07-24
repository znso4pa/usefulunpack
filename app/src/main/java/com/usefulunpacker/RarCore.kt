package com.usefulunpacker
object RarCore {
    init { System.loadLibrary("archive_rar_core") }
    external fun rarListEntries(input: String): String?
    external fun rarExtract(tool: String, input: String, output: String): Boolean
    external fun rarExtractSelected(tool: String, input: String, output: String, selected: String): Boolean
    external fun rarExtractWithPassword(tool: String, input: String, output: String, password: String): Boolean
    external fun rarNeedsPassword(input: String): Boolean
    external fun rarExtractSelectedWithPassword(tool: String, input: String, output: String, selected: String, password: String): Boolean
}
