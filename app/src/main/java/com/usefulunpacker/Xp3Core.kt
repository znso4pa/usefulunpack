package com.usefulunpacker
object Xp3Core {
    init { System.loadLibrary("archive_xp3_core") }
    external fun xp3Extract(tool: String, input: String, output: String): String?
    external fun xp3ExtractSelected(tool: String, input: String, output: String, selected: String): String?
    // cxdec-protected XP3 (same .so, shares the xp3 progress/cancel store)
    external fun xp3CxdecExtract(tool: String, gameDir: String, input: String, output: String): String?
    external fun xp3CxdecExtractSelected(tool: String, gameDir: String, input: String, output: String, selected: String): String?
    // Keyless XP3 schemes (HashCrypt / FateCrypt / AppliqueCrypt /
    // FlyingShineCrypt / AlteredPinkCrypt / DameganeCrypt). No game folder is
    // involved: the scheme is named by the content probe (`xp3SchemeToken`) and
    // [scheme] is that name passed straight back.
    external fun xp3CryptExtract(tool: String, input: String, output: String, scheme: String): String?
    external fun xp3CryptExtractSelected(tool: String, input: String, output: String, scheme: String, selected: String): String?
    external fun xp3ListEntries(input: String): String?
    /**
     * Encryption state of one archive, as a machine token (see
     * [com.usefulunpacker.archive.xp3SchemeToken]): `plain` / `cxdec:<scheme>` /
     * `cxdec:?` / `crypt:<scheme>` / `suspect`, or null when the probe cannot
     * say. Read-only: it does not touch the progress/cancel store, so it is safe
     * beside a running extraction in the same .so.
     */
    external fun xp3ProbeScheme(input: String): String?
    external fun xp3ExtractProgressCount(): Long
    external fun xp3ExtractProgressTotal(): Long
    external fun xp3ExtractProgressFileCount(): Long
    external fun xp3ExtractProgressFileTotal(): Long
    external fun xp3ExtractProgressName(): String?
    external fun xp3ExtractCancel()
    /** [enc] is `""` (plain), `"cxdec"`, or `"crypt:<scheme>"`.
     *  `"cxdec"` resolves the concrete scheme from the folder (an existing
     *  encrypted archive, else the game's own script) and is refused when there
     *  is nothing to resolve it from. `"crypt:<scheme>"` names a keyless scheme
     *  the caller chose — nothing has to be discovered, so it is never refused. */
    external fun xp3CreateArchive(tool: String, input: String, output: String, level: String, enc: String): String?
    /** JSON note (`scheme`/`source`/`verified`) about the scheme the last pack
     *  used, or empty. Read right after a successful [xp3CreateArchive]. */
    external fun xp3LastEncNote(): String?
    external fun xp3CompressProgressCount(): Long
    external fun xp3CompressProgressTotal(): Long
    external fun xp3CompressProgressFileCount(): Long
    external fun xp3CompressProgressFileTotal(): Long
    external fun xp3CompressProgressName(): String?
    external fun xp3CompressCancel()
}
