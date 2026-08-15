package com.usefulunpacker

object ScanCore {
    init { System.loadLibrary("archive_scan_core") }
    external fun scanFile(path: String): String?
    external fun scanProgressBytes(): Long
    external fun scanProgressTotal(): Long
    external fun scanCancel()
}
