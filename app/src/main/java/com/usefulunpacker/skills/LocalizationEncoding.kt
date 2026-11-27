package com.usefulunpacker

import java.nio.charset.Charset

/** Exact charset names for validation; this catalog deliberately does not alter global editor settings. */
internal val LOCALIZATION_ENCODINGS = listOf("UTF-8", "SHIFT-JIS", "CP932", "GBK", "GB18030", "UTF-16", "UTF-16LE", "UTF-16BE")

internal fun localizationCharset(encoding: String, bytes: ByteArray = ByteArray(0)): Charset {
    if (encoding !in LOCALIZATION_ENCODINGS) throw LocalizationCheckException("UNSUPPORTED_ENCODING")
    if (encoding == "UTF-16") return when {
        bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> Charsets.UTF_16BE
        else -> Charsets.UTF_16LE
    }
    return try {
        Charset.forName(if (encoding == "CP932") "windows-31j" else encoding)
    } catch (_: java.nio.charset.UnsupportedCharsetException) {
        throw LocalizationCheckException("UNSUPPORTED_ENCODING")
    }
}

internal fun localizationBomSize(encoding: String, bytes: ByteArray): Int {
    val bom = detectBomEncoding(bytes) ?: return 0
    if (encoding !in setOf("UTF-8", "UTF-16", "UTF-16LE", "UTF-16BE")) return 0
    if (encoding == "UTF-16LE" || encoding == "UTF-16BE") {
        if (bom != "UTF-16" || (bytes[0] == 0xfe.toByte()) != (encoding == "UTF-16BE")) {
            throw LocalizationCheckException("BOM_ENCODING_CONFLICT")
        }
        return 2
    }
    if (encoding == "UTF-8" && bom == "UTF-8") return 3
    if (encoding == "UTF-16" && bom == "UTF-16") return 2
    // Explicit Unicode byte orders must not silently consume a conflicting BOM.
    throw LocalizationCheckException("BOM_ENCODING_CONFLICT")
}
