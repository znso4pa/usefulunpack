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
}
