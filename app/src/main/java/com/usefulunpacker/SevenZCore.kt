package com.usefulunpacker
object SevenZCore {
    init { System.loadLibrary("archive_sevenz_core") }
    external fun szExtract(tool: String, input: String, output: String): Boolean
    external fun szExtractSelected(tool: String, input: String, output: String, selected: String): Boolean
    external fun szListEntries(input: String): String?
}
