package com.usefulunpacker

val C = mapOf(
    "accent" to 0xFF35acc6.toInt(),
    "primary" to 0xFFe0f9ff.toInt(),
    "secondary" to 0xFFb0b0b0.toInt(),
    "tertiary" to 0xFF888888.toInt(),
    "tertiary_light" to 0xFFaaaaaa.toInt(),
    "hint" to 0xFF8f8f8f.toInt(),
    "surface" to 0xFF303030.toInt(),
    "surface_dim" to 0xFF252525.toInt(),
    "surface_dark" to 0xFF1a1a1a.toInt(),
    "nav_bg" to 0xFF222222.toInt(),
    "divider" to 0xFF1a1a1a.toInt(),
    "divider_subtle" to 0xFF555555.toInt(),
    "toggle_on" to 0xFF3a3a3a.toInt(),
    "warning" to 0xFFffa726.toInt(),
    "error" to 0xFFff5252.toInt(),
    "success" to 0xFF69f0ae.toInt(),
    "search_hilite_sel" to 0x88FFAA00.toInt(),
    "search_hilite_oth" to 0x33FFAA00.toInt(),
)

val ARCHIVE_EXTS = setOf(
    "xp3", "pfs", "pf6", "pf8", "nsa", "sar", "iso", "ypf", "zip", "7z", "rar", "lz4",
    "gz", "bz2", "xz", "zst", "lzma", "tar", "tgz", "tbz2", "txz",
)

// 归档模式格式选择器：格式 key → 显示标签
val FORMAT_LABELS = mapOf(
    "xp3" to "XP3", "pfs" to "PFS/PF6/PF8", "nsa" to "NSA/SAR", "iso" to "ISO", "ypf" to "YPF",
    "zip" to "ZIP", "7z" to "7z", "rar" to "RAR", "lz4" to "LZ4",
    "tar" to "TAR (.tar/.tgz/.tar.gz/.tbz2/.txz)", "gz" to "GZIP (.gz)", "bz2" to "BZIP2 (.bz2)",
    "xz" to "XZ (.xz)", "zst" to "ZSTD (.zst)", "lzma" to "LZMA (.lzma)",
)

// 归档模式格式选择器分组顺序（表头用 string 资源，格式 key 列表）
val FORMAT_GROUPS = listOf(
    Pair(R.string.format_group_galgame, listOf("xp3", "pfs", "nsa", "iso", "ypf")),
    Pair(R.string.format_group_generic, listOf("zip", "7z", "rar", "lz4", "tar", "gz", "bz2", "xz", "zst", "lzma")),
)

// 压缩模式格式选择器分组（zip/7z + tar 变体 = 归档打包；单流 = 单文件压缩）
val COMPRESS_GROUPS = listOf(
    Pair(R.string.format_group_generic, listOf("zip", "7z", "tar", "tgz", "tbz2", "txz", "tzst")),
    Pair(R.string.format_group_single, listOf("gz", "bz2", "xz", "zst", "lzma", "lz4")),
)

// 压缩输出扩展名（dir.name + 该扩展名）
val COMPRESS_EXT = mapOf(
    "zip" to "zip", "7z" to "7z",
    "tar" to "tar", "tgz" to "tar.gz", "tbz2" to "tar.bz2", "txz" to "tar.xz", "tzst" to "tar.zst",
    "gz" to "gz", "bz2" to "bz2", "xz" to "xz", "zst" to "zst", "lzma" to "lzma", "lz4" to "lz4",
)

// 单文件压缩格式（选中文件夹时不可用）
val SINGLE_FILE_COMPRESS = setOf("gz", "bz2", "xz", "zst", "lzma", "lz4")

// 压缩模式格式选择器：格式 key → 显示标签
val COMPRESS_LABELS = mapOf(
    "zip" to "ZIP (.zip)", "7z" to "7z (.7z)",
    "tar" to "TAR (.tar)", "tgz" to "TAR.GZ (.tar.gz)", "tbz2" to "TAR.BZ2 (.tar.bz2)",
    "txz" to "TAR.XZ (.tar.xz)", "tzst" to "TAR.ZST (.tar.zst)",
    "gz" to "GZIP (.gz)", "bz2" to "BZIP2 (.bz2)", "xz" to "XZ (.xz)",
    "zst" to "ZSTD (.zst)", "lzma" to "LZMA (.lzma)", "lz4" to "LZ4 (.lz4)",
)

// 当前文件小于该字节数时隐藏底部"当前文件"进度条，避免大量小文件时快速跳变闪烁
val PROGRESS_FILE_BAR_MIN = 1024L * 1024L

val PREVIEW_EXTS = setOf("jpg", "jpeg", "png", "mp3", "ogg", "mp4",
    "txt", "json", "ini", "ks", "lua", "py", "js", "html", "css", "xml", "cfg", "log")

val TEXT_SEARCH_EXTS = setOf(
    "txt", "json", "ini", "ks", "lua", "py", "js", "html", "css", "xml", "cfg", "log",
    "rtf", "md", "yaml", "yml", "toml", "conf", "properties", "sh", "java", "kt", "rs",
    "c", "cpp", "h", "hpp", "swift", "rb", "php", "pl", "sql", "tsv",
    "srt", "ass", "lrc", "bat", "cmd", "ps1", "go", "dart", "r", "csv"
)
