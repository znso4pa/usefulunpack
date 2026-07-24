package com.usefulunpacker
object Lz4Core {
    init { System.loadLibrary("archive_lz4_core") }
    external fun lz4ListEntries(input: String): String?
    external fun lz4Extract(tool: String, input: String, output: String): Boolean
}
