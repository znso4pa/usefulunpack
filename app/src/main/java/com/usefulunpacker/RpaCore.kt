package com.usefulunpacker

/** JNI surface of `libarchive_rpa_core` — Ren'Py archives (`.rpa`, `.rpi`). */
object RpaCore {
    init { System.loadLibrary("archive_rpa_core") }
    external fun rpaExtract(tool: String, input: String, output: String): String?
    external fun rpaExtractSelected(tool: String, input: String, output: String, selected: String): String?
    external fun rpaListEntries(input: String): String?
    external fun rpaExtractProgressCount(): Long
    external fun rpaExtractProgressTotal(): Long
    external fun rpaExtractProgressFileCount(): Long
    external fun rpaExtractProgressFileTotal(): Long
    external fun rpaExtractProgressName(): String?
    external fun rpaExtractCancel()
    external fun rpaCreateArchive(tool: String, input: String, output: String, level: String): Boolean
    external fun rpaCompressProgressCount(): Long
    external fun rpaCompressProgressTotal(): Long
    external fun rpaCompressProgressFileCount(): Long
    external fun rpaCompressProgressFileTotal(): Long
    external fun rpaCompressProgressName(): String?
    external fun rpaCompressCancel()
}
