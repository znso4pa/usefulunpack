package com.usefulunpacker

import android.content.Context
import androidx.annotation.StringRes
import java.io.IOException

internal enum class AgentReferenceDocument(
    @StringRes private val titleResource: Int?,
    @StringRes private val descriptionResource: Int?,
    val keywords: String,
    private val fileName: String,
    val readFormat: String? = null,
    val packFormat: String? = null,
    private val formatName: String? = null,
) {
    VN_GUIDE(R.string.agent_document_vn, R.string.agent_document_vn_summary,
        "VN visual novel visual-novel localization VN_INSPECT DETECT_GAME_ENGINE", "vn-guide.md"),
    RENPY(R.string.agent_document_renpy, R.string.agent_document_renpy_summary,
        "Ren'Py RenPy rpy rpyc rpa game/tl", "renpy.md"),
    TYRANO_KAG(R.string.agent_document_tyrano, R.string.agent_document_tyrano_summary,
        "TyranoScript Tyrano KAG KiriKiri kirikiri2 吉里吉里 きりきり ks tjs xp3", "tyrano-kag.md"),
    RPG_MAKER(R.string.agent_document_rpg, R.string.agent_document_rpg_summary,
        "RPG Maker RPGツクール MV MZ rgss rpgmvp JSON JS", "rpg-maker.md"),
    ARCHIVES(R.string.agent_document_archive, R.string.agent_document_archive_summary,
        "archive unpack extract repack compress scan zip xp3 rpa pfs pf6 lpk ARCHIVE_LIST", "archives.md"),
    ZIP_7Z_RAR(R.string.agent_document_zip_7z_rar, R.string.agent_document_zip_7z_rar_summary,
        "zip 7z rar split archive password volume checksum EXTRACT COMPRESS", "zip-7z-rar.md"),
    TAR_STREAMS(R.string.agent_document_tar_streams, R.string.agent_document_tar_streams_summary,
        "tar tgz tbz2 txz tzst gzip bzip2 xz zstd lzma lz4 stream single-file", "tar-streams.md"),
    XP3(R.string.agent_document_xp3, R.string.agent_document_xp3_summary,
        "xp3 cxdec hashcrypt KiriKiri crypt protected ADLR XOR UU 吉里吉里", "xp3.md", "xp3", "xp3"),
    YPF(R.string.agent_document_ypf, R.string.agent_document_ypf_summary,
        "ypf yu-ris yuris XOR Shift-JIS zlib archive filenames", "ypf.md", "ypf", "ypf"),
    VN_ARCHIVE_FORMATS(R.string.agent_document_vn_formats, R.string.agent_document_vn_formats_summary,
        "pfs pf6 pf8 rpa rgss rgssad rgss2a rgss3a lpk Artemis RenPy Lucifen", "vn-archive-formats.md"),
    ARCHIVE_WRAPPERS(R.string.agent_document_wrappers, R.string.agent_document_wrappers_summary,
        "wrapper encryption obfuscation XOR header signature hex guess --pre SCAN XP3 YPF 解密", "archive-wrappers.md"),
    UUT_TRANSFORMS(R.string.agent_document_uut_transforms, R.string.agent_document_uut_transforms_summary,
        "uut script uu tr xor rot cmp guess --pre --post RUN_UUT VALIDATE_UUT XP3 YPF 加密 解密", "uut-transforms.md"),

    ZIP("ZIP (.zip)", "format-zip.md", "zip", "zip", "PKWARE EFS CP437 UTF-8 z01 AES"),
    SEVEN_ZIP("7z (.7z)", "format-7z.md", "7z", "7z", "7-Zip LZMA2 header solid 分卷"),
    RAR("RAR (.rar)", "format-rar.md", "rar", keywords = "RAR3 RAR5 unrar volume CRC part01"),
    TAR("TAR (.tar)", "format-tar.md", "tar", "tar", "POSIX PAX ustar symlink"),
    TAR_GZIP("TAR + GZIP (.tar.gz / .tgz)", "format-tgz.md", "tar", "tgz", "gunzip tar.gz tarball"),
    TAR_BZIP2("TAR + BZIP2 (.tar.bz2 / .tbz2)", "format-tbz2.md", "tar", "tbz2", "bzip bunzip2 tar.bz2"),
    TAR_XZ("TAR + XZ (.tar.xz / .txz)", "format-txz.md", "tar", "txz", "XZ LZMA2 tar.xz"),
    TAR_ZSTD("TAR + ZSTD (.tar.zst / .tzst)", "format-tzst.md", "tar", "tzst", "zstd zstandard tar.zst"),
    GZIP("GZIP (.gz)", "format-gz.md", "gz", "gz", "gzip gunzip deflate concatenated"),
    BZIP2("BZIP2 (.bz2)", "format-bz2.md", "bz2", "bz2", "bzip2 bunzip2 BZh CRC"),
    XZ("XZ (.xz)", "format-xz.md", "xz", "xz", "xz LZMA2 stream CRC footer"),
    ZSTD("ZSTD (.zst)", "format-zst.md", "zst", "zst", "zstandard zstd frame window dictionary"),
    LZMA("LZMA (.lzma)", "format-lzma.md", "lzma", "lzma", "LZMA-Alone raw header unknown size"),
    LZ4("LZ4 (.lz4)", "format-lz4.md", "lz4", "lz4", "lz4 frame block legacy checksum"),
    BROTLI("Brotli (.br)", "format-br.md", "br", "br", "brotli raw HTTP Content-Encoding no magic"),
    PFS("PFS / PF8 (.pfs / .pf8)", "format-pfs.md", "pfs", "pfs", "Artemis SHA-1 index XOR"),
    PF6("PF6 (.pfs / .pf6)", "format-pf6.md", "pfs", "pf6", "Artemis PF6 raw payload"),
    NSA_SAR("NSA / SAR (.nsa / .sar)", "format-nsa.md", "nsa", "nsa", "NScripter ONScripter LZSS SPB"),
    ISO("ISO 9660 / UDF (.iso)", "format-iso.md", "iso", "iso", "disk image CD CSO Joliet UDF"),
    RPA("Ren’Py RPA (.rpa / .rpi)", "format-rpa.md", "rpa", "rpa", "Ren'Py archiver raw payload index"),
    INT("CatSystem2 INT/KIF (.int)", "format-int.md", "int", "int", "KIF CatSystem CatSystem2 executable key"),
    BGI("BGI / Ethornell (.arc)", "format-bgi.md", "bgi", keywords = "BGI Ethornell PackFile DSC BSE"),
    LPK("Lucifen LPK (.lpk)", "format-lpk.md", "lpk", "lpk", "Lucifen filename key script LPK"),
    KSD("KiriKiri KSD (.ksd)", "format-ksd.md", "ksd", "ksd", "KAG saved game save data zlib 吉里吉里 セーブ"),
    RGSS_XP("RPG Maker XP (.rgssad)", "format-rgssad.md", "rgss", "rgssad", "RGSS v1 RPGツクール"),
    RGSS_VX("RPG Maker VX (.rgss2a)", "format-rgss2a.md", "rgss", "rgss2a", "RGSS v2 VX RPGツクール"),
    RGSS_VX_ACE("RPG Maker VX Ace (.rgss3a)", "format-rgss3a.md", "rgss", "rgss3a", "RGSS v3 VX Ace RPGツクール"),
    RPGMV_IMAGE("RPG Maker MV/MZ PNG (.rpgmvp / .png_)", "format-rpgmvp.md", "rpgmv", "rpgmvp", "RPGMV MZ PNG encryption asset sprite"),
    RPGMV_OGG("RPG Maker MV/MZ Ogg (.rpgmvo / .ogg_)", "format-rpgmvo.md", "rpgmv", "rpgmvo", "RPGMV MZ OggS audio encryption asset"),
    RPGMV_M4A("RPG Maker MV/MZ M4A (.rpgmvm / .m4a_)", "format-rpgmvm.md", "rpgmv", "rpgmvm", "RPGMV MZ M4A ftyp moov audio encryption asset"),

    BYTE_OFFSETS(R.string.agent_document_byte_offsets, R.string.agent_document_byte_offsets_summary,
        "byte offset hex dd skip count seek absolute relative 字节偏移 字节区间", "byte-offsets.md"),
    PARTIAL_XOR(R.string.agent_document_partial_xor, R.string.agent_document_partial_xor_summary,
        "partial XOR region range header dd tr byte splice 部分异或 部分XOR 局部XOR", "partial-xor.md"),
    XOR_ALIGNMENT(R.string.agent_document_xor_alignment, R.string.agent_document_xor_alignment_summary,
        "repeating XOR key phase counter start offset skip alignment 循环异或 密钥起点", "xor-alignment.md"),
    TEXT_ENCODINGS(R.string.agent_document_text_encodings, R.string.agent_document_text_encodings_summary,
        "encoding BOM UTF-8 UTF-16 GBK Shift-JIS CP932 mojibake 乱码 编码", "text-encodings.md"),
    ENTRY_NAME_ENCODING(R.string.agent_document_entry_names, R.string.agent_document_entry_names_summary,
        "encoding zip CP437 EFS UTF-8 Shift-JIS filenames mojibake 文件名乱码", "entry-name-encoding.md"),
    FORMAT_MISMATCH(R.string.agent_document_format_mismatch, R.string.agent_document_format_mismatch_summary,
        "signature magic extension renamed .bin info guess 后缀 魔数", "format-mismatch.md"),
    CHECKSUMS(R.string.agent_document_checksums, R.string.agent_document_checksums_summary,
        "CRC checksum corrupt partial failure truncation bad output 校验 损坏", "checksum-failures.md"),
    SPLIT_VOLUMES(R.string.agent_document_split_volumes, R.string.agent_document_split_volumes_summary,
        "zip z01 001 7z part rar incomplete missing split volume 分卷 缺卷", "split-volumes.md"),
    PASSWORDS(R.string.agent_document_passwords, R.string.agent_document_passwords_summary,
        "password encrypted headers encrypted members wrong password CRC 密码 加密", "archive-passwords.md"),
    PACK_LAYOUT(R.string.agent_document_pack_layout, R.string.agent_document_pack_layout_summary,
        "archive layout root folder directory file casing repack 层级 根目录 封包", "pack-layout.md"),
    EMBEDDED_SCAN(R.string.agent_document_embedded_scan, R.string.agent_document_embedded_scan_summary,
        "binwalk scan embedded carve fd offset length 文件切割 内嵌归档", "embedded-scan.md"),
    NESTED_ARCHIVES(R.string.agent_document_nested, R.string.agent_document_nested_summary,
        "archive nested recursion layers staged container 嵌套归档 多层解包", "nested-archives.md"),
    OUTPUT_COLLISIONS(R.string.agent_document_output_collisions, R.string.agent_document_output_collisions_summary,
        "overwrite collision existing destination temporary cleanup 同名 覆盖 清理", "output-collisions.md"),
    SCRIPT_FAILURES(R.string.agent_document_script_failures, R.string.agent_document_script_failures_summary,
        "uut validate task status cancellation batch logs exit code 失败 取消 日志", "uut-failures.md");

    constructor(formatName: String, fileName: String, readFormat: String, packFormat: String? = null, keywords: String) :
        this(null, null, keywords, fileName, readFormat, packFormat, formatName)

    val id: String = fileName.removeSuffix(".md")
    val catalogTitle: String = formatName ?: id.replace('-', ' ').replaceFirstChar(Char::titlecase)
    val searchable: String = "$id $catalogTitle $keywords ${readFormat.orEmpty()} ${packFormat.orEmpty()}"

    fun title(context: Context): String = formatName ?: context.getString(checkNotNull(titleResource))

    fun description(context: Context): String = context.getString(descriptionResource ?: if (packFormat == null) {
        R.string.agent_document_format_read_summary
    } else {
        R.string.agent_document_format_pack_summary
    })

    fun readText(): String {
        val stream = AgentReferenceDocument::class.java.getResourceAsStream("/agent-documents/$fileName")
            ?: throw IOException("Packaged reference document is missing: $fileName")
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trimEnd()
        if (text.isBlank() || text.toByteArray(Charsets.UTF_8).size > MAX_REFERENCE_BYTES) {
            throw IOException("Packaged reference document has an invalid size: $fileName")
        }
        return text
    }

    companion object {
        private const val MAX_REFERENCE_BYTES = 8 * 1024
        private val wordSeparator = Regex("\\s+")

        fun matches(searchable: String, query: String): Boolean =
            query.trim().split(wordSeparator).all { searchable.contains(it, ignoreCase = true) }

        fun search(query: String): List<AgentReferenceDocument> = entries
            .filter { matches(it.searchable, query) }
            .sortedByDescending { it.id.equals(query.trim(), ignoreCase = true) || it.catalogTitle.startsWith(query.trim(), ignoreCase = true) }
            .take(8)
    }
}
