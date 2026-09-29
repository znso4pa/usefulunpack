package com.usefulunpacker

import java.io.File

/**
 * RPG Maker RGSS encrypted archives (`Game.rgssad` / `Game.rgss2a` /
 * `Game.rgss3a`).
 *
 * One library for all three container versions — the parser picks the layout
 * from the header, so every `.rgssad` / `.rgss2a` / `.rgss3a` reaches the same
 * entry points under the single read key `rgss`.
 *
 * Reading is deliberately ONE key: the user has nothing to choose, the header
 * decides. Packing keeps the three `rgssad` / `rgss2a` / `rgss3a` keys apart
 * (see [COMPRESS_GROUPS]) because there the target container version is a real
 * decision — the engine only loads the file whose extension matches its body.
 */
object RgssCore {
    init { System.loadLibrary("archive_rgss_core") }
    external fun rgssExtract(tool: String, input: String, output: String): String?
    external fun rgssExtractSelected(tool: String, input: String, output: String, selected: String): String?
    external fun rgssListEntries(input: String): String?
    external fun rgssExtractProgressCount(): Long
    external fun rgssExtractProgressTotal(): Long
    external fun rgssExtractProgressFileCount(): Long
    external fun rgssExtractProgressFileTotal(): Long
    external fun rgssExtractProgressName(): String?
    external fun rgssExtractCancel()
    /** `version`: "1" = XP, "3" = VX Ace. v2 (VX) uses the v1 layout and is
     *  read-only, like the engine's own packer. */
    external fun rgssCreateArchive(tool: String, input: String, output: String, version: String): String?
    external fun rgssCompressProgressCount(): Long
    external fun rgssCompressProgressTotal(): Long
    external fun rgssCompressProgressFileCount(): Long
    external fun rgssCompressProgressFileTotal(): Long
    external fun rgssCompressProgressName(): String?
    external fun rgssCompressCancel()
    // MV/MZ loose assets
    external fun rgssMvListEntries(input: String): String?
    external fun rgssMvExtract(tool: String, input: String, output: String): String?
    external fun rgssMvExtractSelected(tool: String, input: String, output: String, selected: String): String?
    external fun rgssMvProgressCount(): Long
    external fun rgssMvProgressTotal(): Long
    external fun rgssMvProgressFileCount(): Long
    external fun rgssMvProgressFileTotal(): Long
    external fun rgssMvProgressName(): String?
    external fun rgssMvCancel()
    /** [key]: 32 hex chars = the XOR keystream verbatim, anything else = an
     *  `encryptionKey` string to MD5 (empty = RPG Maker's default). */
    external fun rgssMvEncrypt(tool: String, input: String, output: String, key: String): String?
}

// ── RPG Maker MV / MZ loose assets (.rpgmvp / .rpgmvo / .rpgmvm) ──
//
// Not archives: each is one obfuscated asset, presented to the app as a
// one-entry archive whose single member is the decoded file under its true
// extension. That reuses preview / selective extract / batch extract / search
// unchanged, and the correct extension is what lets the entry open in the image
// or audio viewer.

/** The single format key for READING any RPG Maker MV/MZ asset. */
const val FMT_RPGMV = "rpgmv"

/** The single format key for READING any RGSS archive. */
const val FMT_RGSS = "rgss"

/** The three format keys the compress picker offers, one per container. */
const val FMT_RGSSAD = "rgssad"
const val FMT_RGSS2A = "rgss2a"
const val FMT_RGSS3A = "rgss3a"

/** Writer version for a compress-picker choice. `rgss2a` shares the v1 layout,
 *  so it packs v1 — matching what the engine expects of a `.rgss2a` archive. */
fun rgssWriteVersionOf(fmt: String): String = when (fmt) {
    FMT_RGSS3A -> "3"
    else -> "1"
}

/** Writer version when repacking an archive that ALREADY exists (the preview
 *  workspace's edit → repack loop, where [fmt] is always the read key and so
 *  says nothing about the container). There the right answer is "keep whatever
 *  the game is already using", which the source file's extension tells us: the
 *  Rust parser would happily read a v1 body out of `Game.rgss3a`, but the
 *  engine would not. */
fun rgssRepackVersionOf(srcName: String): String =
    if (srcName.endsWith(".$FMT_RGSS3A", ignoreCase = true)) "3" else "1"

/** The three pack keys for MV/MZ assets, one per asset type. */
val MV_PACK_KEYS = listOf("rpgmvp", "rpgmvo", "rpgmvm")

/** Prefs key holding the user's MV/XOR keystream. */
const val PREF_MV_KEY = "rpgmv_encryption_key"

/** True for any MV/MZ pack key. */
fun isMvPackKey(fmt: String): Boolean = fmt in MV_PACK_KEYS

/**
 * RPG Maker only ever loads an MV/MZ asset under its obfuscated extension, so
 * the packer must swap `.png` -> `.rpgmvp` rather than appending.
 */
fun mvPackedName(src: File, fmt: String): String {
    val stem = src.name.substringBeforeLast('.', src.name)
    return "$stem.$fmt"
}

/**
 * The plain extension a pack key accepts, or null for anything else.
 * RPG Maker picks the loader from the obfuscated extension, never from the
 * payload, so a JPEG hidden behind `foo.rpgmvp` is loaded as a picture and comes
 * out broken. The extension is therefore the gate.
 */
fun mvRequiredExt(fmt: String): String? = when (fmt) {
    "rpgmvp" -> "png"
    "rpgmvo" -> "ogg"
    "rpgmvm" -> "m4a"
    else -> null
}

/** True when [f] must not be packed with [fmt] because of its extension. */
fun mvExtMismatch(f: File, fmt: String): Boolean {
    val want = mvRequiredExt(fmt) ?: return false
    return f.extension.lowercase() != want
}

