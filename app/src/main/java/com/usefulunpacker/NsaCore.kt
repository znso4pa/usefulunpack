package com.usefulunpacker
object NsaCore {
    init { System.loadLibrary("archive_nsa_core") }
    external fun nsaExtract(tool: String, input: String, output: String): Boolean
    external fun nsaExtractSelected(tool: String, input: String, output: String, selected: String): Boolean
    external fun nsaListEntries(input: String): String?
}
