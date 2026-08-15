package com.usefulunpacker
object NsaCore {
    init { System.loadLibrary("archive_nsa_core") }
    external fun nsaExtract(tool: String, input: String, output: String): String?
    external fun nsaExtractSelected(tool: String, input: String, output: String, selected: String): String?
    external fun nsaListEntries(input: String): String?
    external fun nsaExtractProgressCount(): Long
    external fun nsaExtractProgressTotal(): Long
    external fun nsaExtractProgressFileCount(): Long
    external fun nsaExtractProgressFileTotal(): Long
    external fun nsaExtractProgressName(): String?
    external fun nsaExtractCancel()
    external fun nsaCreateArchive(tool: String, input: String, output: String, level: String): Boolean
    external fun nsaCompressProgressCount(): Long
    external fun nsaCompressProgressTotal(): Long
    external fun nsaCompressProgressFileCount(): Long
    external fun nsaCompressProgressFileTotal(): Long
    external fun nsaCompressProgressName(): String?
    external fun nsaCompressCancel()
}
