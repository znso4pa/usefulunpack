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

val ARCHIVE_EXTS = setOf("xp3", "pfs", "pf6", "pf8", "nsa", "sar", "iso", "ypf", "zip", "7z", "rar", "lz4")

val PREVIEW_EXTS = setOf("jpg", "jpeg", "png", "mp3", "ogg", "mp4",
    "txt", "json", "ini", "ks", "lua", "py", "js", "html", "css", "xml", "cfg", "log")

val TEXT_SEARCH_EXTS = setOf(
    "txt", "json", "ini", "ks", "lua", "py", "js", "html", "css", "xml", "cfg", "log",
    "rtf", "md", "yaml", "yml", "toml", "conf", "properties", "sh", "java", "kt", "rs",
    "c", "cpp", "h", "hpp", "swift", "rb", "php", "pl", "sql", "tsv",
    "srt", "ass", "lrc", "bat", "cmd", "ps1", "go", "dart", "r", "csv"
)
