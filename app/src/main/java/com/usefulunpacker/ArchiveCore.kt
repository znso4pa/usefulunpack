// ╔══════════════════════════════════════════════════════════════╗
// ║  UsefulUnpack — znso4pa (锌帕) — JNI bridge                   ║
// ╚══════════════════════════════════════════════════════════════╝
package com.usefulunpacker

object ArchiveCore {
    init { System.loadLibrary("archive_core") }

    /** tool: path to xp3-unpacker binary, input: archive path, output: extract dir */
    external fun xp3Extract(tool: String, input: String, output: String): Boolean

    /** tool: path to pfs_unpacker binary, input: archive path, output: extract dir */
    external fun pfsExtract(tool: String, input: String, output: String): Boolean

    /** List archive entries as JSON. Returns null on failure. */
    external fun listEntries(input: String): String?

    /** Extract selected entries from XP3. selected: newline-separated paths (forward slashes) */
    external fun xp3ExtractSelected(tool: String, input: String, output: String, selected: String): Boolean

    /** Extract selected entries from PFS. selected: newline-separated paths (forward slashes) */
    external fun pfsExtractSelected(tool: String, input: String, output: String, selected: String): Boolean

    /** NSA/SAR (NScripter) full extraction */
    external fun nsaExtract(tool: String, input: String, output: String): Boolean

    /** NSA/SAR selected extraction */
    external fun nsaExtractSelected(tool: String, input: String, output: String, selected: String): Boolean

    external fun isoExtract(tool: String, input: String, output: String): Boolean

    external fun isoExtractSelected(tool: String, input: String, output: String, selected: String): Boolean

    external fun ypfExtract(tool: String, input: String, output: String): Boolean

    external fun ypfExtractSelected(tool: String, input: String, output: String, selected: String): Boolean
}
