package com.usefulunpacker

data class ArchiveEntry(
    val path: String,
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
    val isEncrypted: Boolean,
    val depth: Int
)
