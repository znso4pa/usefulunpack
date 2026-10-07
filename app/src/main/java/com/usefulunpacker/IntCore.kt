package com.usefulunpacker

/** JNI surface of `libarchive_int_core` — CatSystem2 `KIF` archives (`.int`). */
object IntCore {
    init { System.loadLibrary("archive_int_core") }
    external fun intExtract(tool: String, input: String, output: String): String?
    external fun intExtractSelected(tool: String, input: String, output: String, selected: String): String?
    external fun intListEntries(input: String): String?
    external fun intExtractProgressCount(): Long
    external fun intExtractProgressTotal(): Long
    external fun intExtractProgressFileCount(): Long
    external fun intExtractProgressFileTotal(): Long
    external fun intExtractProgressName(): String?
    external fun intExtractCancel()
    external fun intCreateArchive(tool: String, input: String, output: String, level: String): Boolean
    external fun intCompressProgressCount(): Long
    external fun intCompressProgressTotal(): Long
    external fun intCompressProgressFileCount(): Long
    external fun intCompressProgressFileTotal(): Long
    external fun intCompressProgressName(): String?
    external fun intCompressCancel()
}
