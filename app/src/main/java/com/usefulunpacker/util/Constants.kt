package com.usefulunpacker

import java.io.File

// Layout params helpers (moved out of MainActivity's companion object).
internal val MATCH = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
internal val WRAP = android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
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

// 归档格式 → 文件图标着色（ZArchiver 风格，按格式区分）
val FORMAT_COLORS = mapOf(
    "xp3" to 0xFFf39c12.toInt(),
    "pfs" to 0xFFe74c3c.toInt(),
    "nsa" to 0xFF9b59b6.toInt(),
    "iso" to 0xFF1abc9c.toInt(),
    "ypf" to 0xFFe91e63.toInt(),
    "zip" to 0xFF3498db.toInt(),
    "7z" to 0xFF8e44ad.toInt(),
    "rar" to 0xFFc0392b.toInt(),
    "lz4" to 0xFF16a085.toInt(),
    "gz" to 0xFF7f8c8d.toInt(),
    "bz2" to 0xFF7f8c8d.toInt(),
    "xz" to 0xFF7f8c8d.toInt(),
    "zst" to 0xFF7f8c8d.toInt(),
    "lzma" to 0xFF7f8c8d.toInt(),
    "tar" to 0xFF7f8c8d.toInt(),
    "ksd" to 0xFF95a5a6.toInt(),
)
private val FILE_GRAY = 0xFFb0b0b0.toInt()

/**
 * Maps an archive file name to its format key (single source of truth used by
 * [detectFormat], [iconTintForName] and the format pickers). Multi-extension
 * tar variants must be checked before `substringAfterLast`, otherwise
 * `foo.tar.gz` would be misdetected as GZIP.
 */
fun formatOfName(name: String): String? {
    val n = name.lowercase()
    if (n.endsWith(".tar.gz") || n.endsWith(".tar.bz2") ||
        n.endsWith(".tar.xz") || n.endsWith(".tar.zst")) return "tar"
    return when (n.substringAfterLast('.')) {
        "xp3" -> "xp3"
        "pfs", "pf6", "pf8" -> "pfs"
        "nsa", "sar" -> "nsa"
        "iso" -> "iso"
        "ypf" -> "ypf"
        "zip" -> "zip"
        "7z" -> "7z"
        "rar" -> "rar"
        "lz4" -> "lz4"
        "gz" -> "gz"
        "bz2" -> "bz2"
        "xz" -> "xz"
        "zst" -> "zst"
        "lzma" -> "lzma"
        "tar", "tgz", "tbz2", "txz", "tzst" -> "tar"
        "ksd" -> "ksd"
        else -> null
    }
}

fun isArchiveFile(f: File): Boolean =
    formatOfName(f.name) != null || isVolumeFile(f) != null

fun iconTintForName(name: String): Int {
    val fmt = formatOfName(name) ?: return FILE_GRAY
    return FORMAT_COLORS[fmt] ?: FILE_GRAY
}

fun iconTintFor(f: File): Int {
    val fmt = formatOfName(f.name) ?: isVolumeFile(f) ?: return FILE_GRAY
    return FORMAT_COLORS[fmt] ?: FILE_GRAY
}

val ARCHIVE_EXTS = setOf(
    "xp3", "pfs", "pf6", "pf8", "nsa", "sar", "iso", "ypf", "zip", "7z", "rar", "lz4",
    "gz", "bz2", "xz", "zst", "lzma", "tar", "tgz", "tbz2", "txz", "tzst", "ksd",
)

// 归档模式格式选择器：格式 key → 显示标签
val FORMAT_LABELS = mapOf(
    "xp3" to "XP3", "pfs" to "PFS/PF6/PF8", "nsa" to "NSA/SAR", "iso" to "ISO", "ypf" to "YPF",
    "zip" to "ZIP", "7z" to "7z", "rar" to "RAR", "lz4" to "LZ4",
    "tar" to "TAR (.tar/.tgz/.tar.gz/.tbz2/.txz)", "gz" to "GZIP (.gz)", "bz2" to "BZIP2 (.bz2)",
    "xz" to "XZ (.xz)", "zst" to "ZSTD (.zst)", "lzma" to "LZMA (.lzma)",
    "ksd" to "KSD",
)

// 归档模式格式选择器分组顺序（表头用 string 资源，格式 key 列表）
val FORMAT_GROUPS = listOf(
    Pair(R.string.format_group_generic, listOf("zip", "7z", "rar", "lz4", "tar", "gz", "bz2", "xz", "zst", "lzma")),
    Pair(R.string.format_group_other, listOf("xp3", "pfs", "nsa", "iso", "ypf", "ksd")),
)

// 压缩模式格式选择器分组（zip/7z + tar 变体 = 归档打包；单流 = 单文件压缩）
val COMPRESS_GROUPS = listOf(
    Pair(R.string.format_group_generic, listOf("zip", "7z", "tar", "tgz", "tbz2", "txz", "tzst")),
    Pair(R.string.format_group_single, listOf("gz", "bz2", "xz", "zst", "lzma", "lz4", "ksd")),
    Pair(R.string.format_group_other, listOf("xp3", "pfs", "nsa", "iso")),
)

// 压缩输出扩展名（dir.name + 该扩展名）
val COMPRESS_EXT = mapOf(
    "xp3" to "xp3", "pfs" to "pfs", "nsa" to "nsa", "iso" to "iso",
    "zip" to "zip", "7z" to "7z",
    "tar" to "tar", "tgz" to "tar.gz", "tbz2" to "tar.bz2", "txz" to "tar.xz", "tzst" to "tar.zst",
    "gz" to "gz", "bz2" to "bz2", "xz" to "xz", "zst" to "zst", "lzma" to "lzma", "lz4" to "lz4",
    "ksd" to "ksd",
)

// 单文件压缩格式（选中文件夹时不可用）
val SINGLE_FILE_COMPRESS = setOf("gz", "bz2", "xz", "zst", "lzma", "lz4", "ksd")

// 批量"合并为一个压缩包"可用格式（多条目归档，排除单文件格式）
val MERGE_COMPRESS_GROUPS = listOf(
    Pair(R.string.format_group_generic, listOf("zip", "7z", "tar", "tgz", "tbz2", "txz", "tzst")),
    Pair(R.string.format_group_other, listOf("xp3", "pfs", "nsa", "iso")),
)

// 压缩模式格式选择器：格式 key → 显示标签
val COMPRESS_LABELS = mapOf(
    "xp3" to "XP3 (.xp3)", "pfs" to "PFS (.pfs/.pf8)", "nsa" to "NSA (.nsa/.sar)", "iso" to "ISO (.iso)",
    "zip" to "ZIP (.zip)", "7z" to "7z (.7z)",
    "tar" to "TAR (.tar)", "tgz" to "TAR.GZ (.tar.gz)", "tbz2" to "TAR.BZ2 (.tar.bz2)",
    "txz" to "TAR.XZ (.tar.xz)", "tzst" to "TAR.ZST (.tar.zst)",
    "gz" to "GZIP (.gz)", "bz2" to "BZIP2 (.bz2)", "xz" to "XZ (.xz)",
    "zst" to "ZSTD (.zst)", "lzma" to "LZMA (.lzma)", "lz4" to "LZ4 (.lz4)",
    "ksd" to "KSD (.ksd)",
)

val TEXT_SEARCH_EXTS = setOf(
    "txt", "json", "ini", "ks", "lua", "py", "js", "html", "css", "xml", "cfg", "log",
    "rtf", "md", "yaml", "yml", "toml", "conf", "properties", "sh", "java", "kt", "rs",
    "c", "cpp", "h", "hpp", "swift", "rb", "php", "pl", "sql", "tsv",
    "srt", "ass", "lrc", "vtt", "bat", "cmd", "ps1", "go", "dart", "r", "csv", "tjs",
    "xhtml", "vsq", "ksc"
)

val PREVIEW_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "mp3", "ogg", "mp4") + TEXT_SEARCH_EXTS

/** Script/plain-text extensions the 编辑 (localization) workflow treats as editable. */
val EDIT_SCRIPT_EXTS = setOf("ks", "tjs", "csv", "txt", "ini", "cfg", "json", "log")

/** Content search loads whole files into RAM (strict decode), so even the
 *  "extreme" size limit never lets a file above this into content search —
 *  a multi-hundred-MB text file would OOM the device. */
const val CONTENT_SEARCH_MAX = 50L * 1024 * 1024
