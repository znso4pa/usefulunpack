package com.usefulunpacker
object Xp3Core {
    init { System.loadLibrary("archive_xp3_core") }
    external fun xp3Extract(tool: String, input: String, output: String): Boolean
    external fun xp3ExtractSelected(tool: String, input: String, output: String, selected: String): Boolean
    external fun xp3ListEntries(input: String): String?
}
