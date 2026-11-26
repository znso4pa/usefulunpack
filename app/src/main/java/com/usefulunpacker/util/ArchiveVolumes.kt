package com.usefulunpacker

import java.io.File
import java.io.FileInputStream
// ─── Multi-volume detection ───

private val PART_RAR_RE = Regex("""^(.+)\.part(\d+)\.rar$""", RegexOption.IGNORE_CASE)
private val OLD_RAR_RE = Regex("""^(.+)\.([r-z])\d{2}$""", RegexOption.IGNORE_CASE)
private val SZ_VOL_RE = Regex("""^(.+)\.(?:7z\.)?(\d{2,})$""", RegexOption.IGNORE_CASE)
private val ZIP_VOL_RE = Regex("""^(.+\.zip)\.(\d{2,})$""", RegexOption.IGNORE_CASE)
private val PKW_Z_RE = Regex("""^(.+)\.z(\d{2})$""", RegexOption.IGNORE_CASE)
private val SZ_MAGIC = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
private val PK_MAGIC = byteArrayOf(0x50, 0x4B)

fun isRarVolumeName(name: String): Boolean = PART_RAR_RE.containsMatchIn(name) || OLD_RAR_RE.containsMatchIn(name)
fun isSevenZVolumeName(name: String): Boolean = SZ_VOL_RE.containsMatchIn(name)
fun isZipVolumeName(name: String): Boolean = ZIP_VOL_RE.containsMatchIn(name) || PKW_Z_RE.containsMatchIn(name)

fun startsWithZipMagic(f: File): Boolean = try {
    val sig = ByteArray(4)
    FileInputStream(f).use { it.read(sig) }
    sig[0] == PK_MAGIC[0] && sig[1] == PK_MAGIC[1]
} catch (failureCause: Exception) { com.usefulunpacker.AppDiagnostics.failure("FileUtils", failureCause); false }

/**
 * Returns "rar"/"7z"/"zip" when the file is a volume part (by name + the
 * first part's magic), else null. `.zip.001` also matches the 7z volume-name
 * regex, so zip must be checked first and each branch must resolve the set
 * (a `when` branch that yields null would short-circuit the others).
 */
fun isVolumeFile(f: File): String? {
    // `.z01/.z02` matches the legacy RAR volume regex, but a PKWARE zip
    // multi-disk part resolves as a zip SET — only the first disk (.z01)
    // carries the PK magic, so the set (not the tapped file alone) must be
    // sniffed. A `.z02` mid-part would otherwise be mislabelled RAR (red).
    if (isZipVolumeName(f.name) && resolveZipVolumes(f).size > 1) return "zip"
    if (isRarVolumeName(f.name)) {
        // A PKWARE zip set whose first disk starts with PK must win over the
        // legacy RAR regex; only standalone old-style RAR parts fall through.
        if (isZipVolumeName(f.name) && resolveZipVolumes(f).size > 1) return "zip"
        if (!startsWithZipMagic(f)) return "rar"
    }
    if (isSevenZVolumeName(f.name) && resolveSevenZVolumes(f).size > 1) return "7z"
    return null
}

fun startsWith7zMagic(f: File): Boolean = try {
    val sig = ByteArray(6)
    FileInputStream(f).use { it.read(sig) }
    sig.contentEquals(SZ_MAGIC)
} catch (failureCause: Exception) { com.usefulunpacker.AppDiagnostics.failure("FileUtils", failureCause); false }

/**
 * Resolves the complete volume set for a RAR archive (any part works).
 * Supports modern `name.partN.rar` and old-style `name.rar + name.r00/r01...`.
 * Returns the selected file alone when it is not a volume set.
 */
fun resolveRarVolumes(f: File): List<File> {
    val dir = f.parentFile ?: return listOf(f)
    val name = f.name
    val partMatch = PART_RAR_RE.find(name)
    val oldMatch = OLD_RAR_RE.find(name)
    val base = when {
        partMatch != null -> partMatch.groupValues[1]
        oldMatch != null -> oldMatch.groupValues[1]
        name.lowercase().endsWith(".rar") -> name.dropLast(4)
        else -> return listOf(f)
    }
    val siblings = dir.listFiles()?.toList().orEmpty()
    val modern = siblings.filter { PART_RAR_RE.find(it.name)?.groupValues?.get(1) == base }
        .mapNotNull { f2 ->
            val n = PART_RAR_RE.find(f2.name)!!.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            n to f2
        }.sortedBy { it.first }.map { it.second }
    val old = siblings.filter { it.name.lowercase().startsWith(base.lowercase() + ".") }
        .mapNotNull { f2 ->
            val m = OLD_RAR_RE.find(f2.name) ?: return@mapNotNull null
            val letter = m.groupValues[2][0]
            val digits = m.groupValues[2].drop(1).toIntOrNull() ?: return@mapNotNull null
            (letter.code - 'r'.code) * 100 + digits to f2
        }.sortedBy { it.first }.map { it.second }
    // Prefer the partN scheme when `foo.part1.rar` exists — a plain `foo.rar`
    // next to `foo.part1.rar` is ambiguous (often a duplicate/first copy), so
    // don't prepend it and misorder the set.
    val part1 = siblings.firstOrNull { it.name == "$base.part1.rar" }
    val first = if (part1 != null) listOf(part1)
                else siblings.filter { it.name == "$base.rar" }
    val combined = (first + modern + old).distinctBy { it.path }
    if (combined.isEmpty()) return listOf(f)
    return if (combined.none { it.path == f.path }) combined + f else combined
}

/**
 * Resolves the split parts of a 7z archive (`name.7z.001` / `name.001`).
 * Only returns the set when multiple parts exist and the first part carries
 * the 7z magic signature; otherwise returns the selected file alone.
 */
fun resolveSevenZVolumes(f: File): List<File> {
    val dir = f.parentFile ?: return listOf(f)
    val selBase = SZ_VOL_RE.find(f.name)?.groupValues?.get(1) ?: return listOf(f)
    val siblings = dir.listFiles()?.toList().orEmpty()
    val parts = siblings.mapNotNull { f2 ->
        val m = SZ_VOL_RE.find(f2.name) ?: return@mapNotNull null
        if (m.groupValues[1] != selBase) return@mapNotNull null
        val num = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
        num to f2
    }.sortedBy { it.first }
    if (parts.size < 2) return listOf(f)
    val firstPart = parts.first().second
    if (!startsWith7zMagic(firstPart)) return listOf(f)
    return parts.map { it.second }
}

/**
 * Resolves the split parts of a zip archive:
 * - `name.zip.001/.002` (7-Zip byte-split) or
 * - `name.z01/.z02/.zip` (PKWARE true split).
 * Only returns a set when multiple parts exist and the first part carries the
 * zip magic; otherwise returns the selected file alone.
 */
fun resolveZipVolumes(f: File): List<File> {
    val dir = f.parentFile ?: return listOf(f)
    val siblings = dir.listFiles()?.toList().orEmpty()

    val byteMatch = ZIP_VOL_RE.find(f.name)
    if (byteMatch != null) {
        val base = byteMatch.groupValues[1]
        val parts = siblings.mapNotNull { f2 ->
            val m = ZIP_VOL_RE.find(f2.name) ?: return@mapNotNull null
            if (m.groupValues[1] != base) return@mapNotNull null
            m.groupValues[2].toIntOrNull()?.let { it to f2 }
        }.sortedBy { it.first }.map { it.second }
        if (parts.size >= 2 && startsWithZipMagic(parts.first())) return parts
        return listOf(f)
    }

    val pkMatch = PKW_Z_RE.find(f.name)
    if (pkMatch != null) {
        val base = pkMatch.groupValues[1]
        val zParts = siblings.mapNotNull { f2 ->
            val m = PKW_Z_RE.find(f2.name) ?: return@mapNotNull null
            if (!m.groupValues[1].equals(base, ignoreCase = true)) return@mapNotNull null
            m.groupValues[2].toIntOrNull()?.let { it to f2 }
        }.sortedBy { it.first }.map { it.second }
        val final = siblings.firstOrNull { it.name.equals("$base.zip", ignoreCase = true) }
        val all = if (final != null) zParts + final else zParts
        if (all.size >= 2 && startsWithZipMagic(all.first())) return all
        return listOf(f)
    }

    // Tapping the FINAL `.zip` of a PKWARE true split should resolve the whole
    // set too (its `.z01/.z02...` siblings carry the leading magic).
    if (f.name.lowercase().endsWith(".zip")) {
        val base = f.name.dropLast(4)
        val zParts = siblings.mapNotNull { f2 ->
            val m = PKW_Z_RE.find(f2.name) ?: return@mapNotNull null
            if (!m.groupValues[1].equals(base, ignoreCase = true)) return@mapNotNull null
            m.groupValues[2].toIntOrNull()?.let { it to f2 }
        }.sortedBy { it.first }.map { it.second }
        val all = zParts + f
        if (all.size >= 2 && startsWithZipMagic(all.first())) return all
    }
    return listOf(f)
}

fun volumeJoin(vols: List<File>): String = vols.joinToString("\n") { it.path }

/**
 * Canonical identity of an archive for the open-registry. Split-volume sets
 * (`name.zip.001/.002`, `name.7z.001`, `name.part1.rar`, PKWARE `.z01/.z02`)
 * are normalized to their FIRST part, so every member shares one key — opening
 * any part while another tab holds the set counts as the same archive.
 */
fun archiveKey(src: File): String {
    val vols = when {
        isRarVolumeName(src.name) || src.name.lowercase().endsWith(".rar") -> resolveRarVolumes(src)
        isSevenZVolumeName(src.name) || src.name.lowercase().endsWith(".7z") -> resolveSevenZVolumes(src)
        isZipVolumeName(src.name) || src.name.lowercase().endsWith(".zip") -> resolveZipVolumes(src)
        else -> listOf(src)
    }
    return (vols.firstOrNull() ?: src).canonicalPath
}

private fun passwordFormatOf(f: File): String? {
    val n = f.name.lowercase()
    return when {
        n.endsWith(".rar") || isRarVolumeName(f.name) -> "rar"
        n.endsWith(".7z") || isSevenZVolumeName(f.name) -> "7z"
        n.endsWith(".zip") || isZipVolumeName(f.name) -> "zip"
        else -> null
    }
}

/**
 * Password preflight is advisory. An unreadable header leaves the lock badge
 * unknown; the listing/extraction that actually needs the archive reports the error.
 */
fun isPasswordProtected(f: File): Boolean {
    return try {
        when (passwordFormatOf(f) ?: return false) {
            "rar" -> rarVolumesNeedsPassword(f.path)
            "7z" -> szVolumesNeedsPassword(f.path)
            else -> zipVolumesNeedsPassword(f.path)
        }
    } catch (e: java.util.concurrent.CancellationException) {
        throw e
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw java.util.concurrent.CancellationException("Password probe interrupted").apply { initCause(e) }
    } catch (e: Exception) {
        val trace = AppDiagnostics.begin("password_probe")
        trace.note("Password state unavailable (${e.javaClass.simpleName}): ${e.message.orEmpty()}")
        trace.skipped()
        false
    }
}
