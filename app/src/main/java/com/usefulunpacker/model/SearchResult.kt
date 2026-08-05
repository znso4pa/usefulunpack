package com.usefulunpacker

import java.io.File

data class SearchResult(
    val file: File,
    val snippet: String = "",
    val lineNumber: Int = 0,
    val matchCount: Int = 0
)
