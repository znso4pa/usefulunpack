package com.usefulunpacker
object ZipCore {
    init { System.loadLibrary("archive_zip_core") }
    external fun zipExtract(tool: String, input: String, output: String): String?
    external fun zipExtractSelected(tool: String, input: String, output: String, selected: String): String?
    external fun zipExtractSelectedWithPassword(tool: String, input: String, output: String, selected: String, password: String): String?
    external fun zipListEntries(input: String): String?
    external fun zipNeedsPassword(input: String): Boolean
    external fun zipExtractWithPassword(tool: String, input: String, output: String, password: String): String?
    external fun zipCompress(tool: String, input: String, output: String, level: String, password: String, splitSize: String): Boolean
    external fun zipSetEncoding(enc: String)
    external fun zipCompressCancel()
    external fun zipCompressProgressCount(): Long
    external fun zipCompressProgressTotal(): Long
    external fun zipCompressProgressFileCount(): Long
    external fun zipCompressProgressFileTotal(): Long
    external fun zipCompressProgressName(): String?
    external fun zipExtractProgressCount(): Long
    external fun zipExtractProgressTotal(): Long
    external fun zipExtractProgressFileCount(): Long
    external fun zipExtractProgressFileTotal(): Long
    external fun zipExtractProgressName(): String?
    external fun zipExtractCancel()
    external fun zipListEntriesVolumes(volumes: String): String?
    external fun zipExtractVolumes(tool: String, volumes: String, output: String): String?
    external fun zipExtractSelectedVolumes(tool: String, volumes: String, output: String, selected: String): String?
    external fun zipExtractSelectedVolumesWithPassword(tool: String, volumes: String, output: String, selected: String, password: String): String?
    external fun zipExtractVolumesWithPassword(tool: String, volumes: String, output: String, password: String): String?
    external fun zipVolumesNeedsPassword(volumes: String): Boolean
    external fun zipModify(tool: String, input: String, output: String, ops: String, password: String): Boolean
}
