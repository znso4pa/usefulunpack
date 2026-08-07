package com.usefulunpacker
object SevenZCore {
    init { System.loadLibrary("archive_sevenz_core") }
    external fun szExtract(tool: String, input: String, output: String): String?
    external fun szExtractSelected(tool: String, input: String, output: String, selected: String): String?
    external fun szListEntries(input: String): String?
    external fun szNeedsPassword(input: String): Boolean
    external fun szExtractWithPassword(tool: String, input: String, output: String, password: String): String?
    external fun szCompress(tool: String, input: String, output: String, level: String, password: String): Boolean
    external fun szCompressCancel()
    external fun szCompressProgressCount(): Long
    external fun szCompressProgressTotal(): Long
    external fun szCompressProgressFileCount(): Long
    external fun szCompressProgressFileTotal(): Long
    external fun szCompressProgressName(): String?
    external fun szExtractProgressCount(): Long
    external fun szExtractProgressTotal(): Long
    external fun szExtractProgressFileCount(): Long
    external fun szExtractProgressFileTotal(): Long
    external fun szExtractProgressName(): String?
    external fun szExtractCancel()
    external fun szListEntriesVolumes(volumes: String): String?
    external fun szExtractVolumes(tool: String, volumes: String, output: String): String?
    external fun szExtractSelectedVolumes(tool: String, volumes: String, output: String, selected: String): String?
    external fun szExtractVolumesWithPassword(tool: String, volumes: String, output: String, password: String): String?
    external fun szVolumesNeedsPassword(volumes: String): Boolean
}
