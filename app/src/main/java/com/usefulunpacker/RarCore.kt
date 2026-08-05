package com.usefulunpacker
object RarCore {
    init { System.loadLibrary("archive_rar_core") }
    external fun rarListEntries(input: String): String?
    external fun rarExtract(tool: String, input: String, output: String): String?
    external fun rarExtractSelected(tool: String, input: String, output: String, selected: String): String?
    external fun rarExtractWithPassword(tool: String, input: String, output: String, password: String): String?
    external fun rarNeedsPassword(input: String): Boolean
    external fun rarExtractSelectedWithPassword(tool: String, input: String, output: String, selected: String, password: String): String?
    external fun rarListEntriesVolumes(volumes: String): String?
    external fun rarExtractVolumes(tool: String, volumes: String, output: String): String?
    external fun rarExtractSelectedVolumes(tool: String, volumes: String, output: String, selected: String): String?
    external fun rarExtractVolumesWithPassword(tool: String, volumes: String, output: String, password: String): String?
    external fun rarExtractSelectedVolumesWithPassword(tool: String, volumes: String, output: String, selected: String, password: String): String?
    external fun rarVolumesNeedsPassword(volumes: String): Boolean
    external fun rarExtractProgressCount(): Long
    external fun rarExtractProgressTotal(): Long
    external fun rarExtractProgressName(): String?
    external fun rarExtractCancel()
}
