package com.usefulunpacker
object CsoCore {
    init { System.loadLibrary("archive_cso_core") }
    external fun csoToIso(tool: String, input: String, output: String): Boolean
    external fun isoToCso(tool: String, input: String, output: String, blockSize: String): Boolean
    external fun csoProgressCount(): Long
    external fun csoProgressTotal(): Long
    external fun csoProgressFileCount(): Long
    external fun csoProgressFileTotal(): Long
    external fun csoProgressName(): String?
    external fun csoCancel()
}
