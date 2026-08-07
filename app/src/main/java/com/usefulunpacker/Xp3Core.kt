package com.usefulunpacker
object Xp3Core {
    init { System.loadLibrary("archive_xp3_core") }
    external fun xp3Extract(tool: String, input: String, output: String): String?
    external fun xp3ExtractSelected(tool: String, input: String, output: String, selected: String): String?
    external fun xp3ListEntries(input: String): String?
    external fun xp3ExtractProgressCount(): Long
    external fun xp3ExtractProgressTotal(): Long
    external fun xp3ExtractProgressFileCount(): Long
    external fun xp3ExtractProgressFileTotal(): Long
    external fun xp3ExtractProgressName(): String?
    external fun xp3ExtractCancel()
}
