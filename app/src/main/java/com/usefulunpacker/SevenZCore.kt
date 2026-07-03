package com.usefulunpacker
object SevenZCore {
    init { System.loadLibrary("archive_sevenz_core") }
    external fun szExtract(tool: String, input: String, output: String): Boolean
    external fun szExtractSelected(tool: String, input: String, output: String, selected: String): Boolean
    external fun szListEntries(input: String): String?
    external fun szExtractWithPassword(tool: String, input: String, output: String, password: String): Boolean
    external fun szCompress(tool: String, input: String, output: String, level: String, password: String): Boolean
}
