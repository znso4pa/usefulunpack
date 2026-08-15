# UsefulUnpack

[**中文**](README-zh.md) | [**English**](README.md) | [**繁體中文**](README-zh-TW.md) | [**日本語**](README-ja.md)

A lightweight Android file manager and archive packing/unpacking tool.

Supports **XP3** (Kirikiri), **PFS** (Artemis), **NSA/SAR** (NScripter), **YPF** (YU-RIS), **KSD** (Kirikiri2), **ZIP**, **7z**, **RAR**, **LZ4**, and **ISO 9660** disc images — with native Rust-powered extraction and packing.

---

## Features

| Feature | Description |
|---------|-------------|
| 📁 **XP3** | Pack & unpack Kirikiri `.xp3` archives |
| 📦 **PFS** | Pack & unpack Artemis `.pfs` / `.pf6` / `.pf8` archives |
| 📜 **NSA/SAR** | Unpack NScripter `.nsa` / `.sar` archives (LZSS + SPB), **pack** (stored / LZSS) |
| 🗜️ **ZIP** | Browse/extract/pack ZIP (AES-256, split volumes); **PKWARE multi-disk** (`.z01/.z02/.zip`); **in-place edit** — replace / delete / add entries without repacking the whole archive |
| 📦 **YPF** | Unpack YU-RIS `.ypf` archives with adaptive boundary detection |
| 💾 **KSD** | Pack/unpack `.ksd` files — mode 0/1/2 scrambling + UTF-16 ↔ UTF-8 |
| 💿 **ISO 9660** | Browse and extract ISO disc images (CD/DVD/BD) via isomage; **pack** (Level 1); **CSO↔ISO conversion** (PSP CISO) |
| 🗜️ **RAR** | Unpack RAR archives (RAR4/5) with password support |
| 🗜️ **TAR** | Pack/unpack `.tar`, `.tar.gz`, `.tgz`, `.tar.bz2`, `.tbz2`, `.tar.xz`, `.txz`, `.tar.zst` |
| 🗜️ **GZIP** | Pack/unpack `.gz` |
| 🗜️ **BZIP2** | Pack/unpack `.bz2` |
| 🗜️ **XZ** | Pack/unpack `.xz` |
| 🗜️ **ZSTD** | Pack/unpack `.zst` |
| 🗜️ **LZMA** | Pack/unpack `.lzma` |
| ⚡ **LZ4** | Pack/unpack LZ4 frame-compressed files |
| 🔍 **Archive Preview** | Browse archive contents as a collapsible tree with checkboxes for selective extraction |
| 📊 **Preview Statistics** | Real-time count/size of total and selected files |
| 🔎 **Global Search** | Filename search + content search (30+ text formats), match highlighting with prev/next navigation, progressive scanning |
| 📦 **In-Archive Search** | One-click unpack text files from preview and open the full global search interface on extracted content |
| 🖼️ **File Preview** | Image (JPG/PNG/**GIF/WebP animated**/BMP), audio (MP3/OGG), video (MP4), text/code (md/rtf/yaml/vtt/csv/xhtml/vsq/ksc…) — search results jump to the matching line; large text is previewed with a bounded read (no OOM) in a bigger dialog with a **draggable scrollbar** |
| 🗜️ **Compression** | ZIP/7z + gzip/bzip2/xz/zstd/lzma/lz4 (single file) + tar (folder, 5 variants) + xp3/pfs/nsa/iso/ksd, 5 levels, AES-256 (ZIP) |
| 📚 **Multi-Volume** | Unpack `.7z.001` / `.zip.001` / `.rar` split volumes; pack zip/7z into byte-split volumes (7-Zip compatible) |
| 🎚️ **Custom Split Size** | Split zip/7z output at a custom size (MB/GB, 1MB–2GB) |
| 🔐 **Extract with Password** | Prompt-first password dialog for encrypted ZIP/7z/RAR; batch extraction asks once and reuses it |
| 🔒 **Password Badge** | Archives that need a password show a lock icon in the file browser and in the preview list |
| 📊 **Dual Progress Bar** | Top bar = overall progress, bottom bar = current file progress (extract + compress), both always visible — every extract/compress entry point (preview, batch, search, edit-repack) uses the same dialog; a cancelled extract into a fresh folder is cleaned up entirely |
| 📋 **Grouped Format Picker** | Scrollable, grouped format selection (generic / single-file / other) for extract, batch and compress |
| 📄 **Single-File Compress FAB** | In compress mode, tap any file to get a bottom-right compress button |
| 📦 **Batch Compress Picker** | Merge / separate batch compress both ask for a format; merge excludes single-file formats |
| ✂️ **File Operations** | Long-press to rename, move, delete (irreversible), create folder |
| ☑️ **Batch Multi-Select** | Multi-select mode for batch extract/compress/delete/move |
| 📂 **Batch Preview** | Preview multiple archives at once, select files across all of them |
| 📂 **Local File Preview** | Tap any previewable file in the browser to view directly |
| 🗂 **File Browser** | ZArchiver-style UI with path breadcrumb, fast scroll, folder ⭐ bookmarks |
| 📌 **Bookmarks** | Quick-access paths via star button on folders or slide-out drawer |
| 🏠 **Root Navigation** | One-tap home button to jump to `/storage/emulated/0` |
| ⚡ **One-Tap Preview** | Tap an archive → FAB → direct archive preview; format auto-detected from the extension (`.tar.gz`/`.tgz`/`.pf6`/`.sar`…), no extra chooser dialogs |
| 📦 **Extract All** | One-click "Extract all" in the preview extracts to a deduped sibling folder; reuses the password entered during preview |
| 🧩 **Extensionless Detection** | Magic-byte sniffing recognizes archives that lost their extension or were mislabeled, with a manual format picker as fallback |
| 🔬 **Signature Scan** | Rust scan-core engine: **22 signatures / 68 magic patterns** at any offset, per-format header validation (real size + file counts, incl. **tar** `ustar` and **ISO 9660**), Aho-Corasick matching, streaming scan (no whole-file load), one tap to extract or carve (dd) the raw segment — works for archives embedded between other files |
| 🔤 **Text Encoding** | Global text-encoding setting (UTF-8 / Shift-JIS / GBK / UTF-16) applied strictly to every text preview and content search — BOM-aware UTF-8/UTF-16 with **auto-detection** (a UTF-16 BOM opens as UTF-16 automatically); the preview/editor have an inline encoding switch that re-renders instantly; heavy garble prompts a switch hint, and a strictly-valid-UTF-8 check flags the classic "legal-but-wrong" cross-read |
| ✏️ **Archive Text Editing** | Edit script/text files inside XP3/PFS archives: extract the archive, pick a script (`.ks`/`.tjs`/`.csv`/…), edit it with an explicit encoding + byte-faithful BOM round-trip, then repack into a new `name-cn.xp3/pfs` — a full in-app edit loop |
| 🖱️ **Draggable Scroll** | Long lists (archive preview, scan results, script lists, browser, search) get an always-visible draggable fast-scroll handle; text preview/editor get a custom draggable scrollbar (Honor/EMUI-safe) |
| 🔄 **Auto-Refresh** | A background watcher on the current folder refreshes the file list automatically — rename/move/delete/extract/compress and external changes (adb push, USB) appear without re-entering the path |
| ✂️ **Exact Carve** | Signature-scan carve/extract cuts the validated archive size (zip/rar/7z/zstd/lz4/iso) exactly — an archive embedded between other files (e.g. `mp4 + zip + mp4`) is carved cleanly without the trailing data and extracts successfully |
| 📲 **APK Install** | Tap an APK to hand it to the system installer (FileProvider + PackageInstaller fallback); optional backup copy next to the original before install for ROMs that delete the APK afterwards |
| 📂 **GUI Folder Picker** | Full-screen directory picker (drill-down / parent / select-this-folder) for choosing the search scope |
| 🎛️ **Settings UI** | Standard list-style settings — general (language / text encoding / ZIP filename encoding), compression (levels / split size / password), UI; mode & password switches |
| 🎨 **Per-format Icons** | Material vector icons, color-coded per archive format (zip blue, 7z purple, rar red, xp3 orange, …) |
| 🛡️ **Tap Debounce** | 800ms cooldown prevents accidental duplicate dialogs |
| 🌙 **Dark Theme** | Eye-friendly dark theme matching ZArchiver's color scheme |
| 🦀 **Rust Core** | JNI-powered native `.so` — one per format for isolation (17 formats incl. signature scan) |
| 🔒 **Minimal Permissions** | Only requests storage access |

Signature scan details (parser + size-skip semantics, following the approach of the MIT-licensed binwalk project):

| Design | How it works |
|--------|--------------|
| Engine | Aho-Corasick multi-pattern matching over 1 MiB streaming chunks (magics straddling a boundary still match); 22 signatures / 68 magic patterns |
| Validation | Every hit runs a per-format header parser (ZIP EOCD, RAR EOF marker + volume flags, PNG chunk walk, JPEG marker walk, gzip/bzip2/xz/zstd/lz4/lzma header checks, …) — false positives are dropped |
| Size skip | Validated hits with a known size are skipped past entirely (e.g. a 5.3 GB RAR resolves in ~4 ms from its EOF marker) |
| Post-pass | Same-offset conflicts resolved by confidence; hits inside an identified region removed; unknown sizes extended to the next hit or EOF |
| Memory | Streaming scan never loads the whole file — GB-sized files scan with a fixed ~1 MiB window |

## Screenshots

<p align="middle">
  <img src="screenshots/screenshot_01.jpg" width="45%" />
  <img src="screenshots/screenshot_02.jpg" width="45%" />
</p>
<p align="middle">
  <img src="screenshots/screenshot_03.jpg" width="45%" />
  <img src="screenshots/screenshot_04.jpg" width="45%" />
</p>

## Installation

Download the latest APK from [Releases](https://github.com/znso4pa/usefulunpack/releases).

Minimum Android 8.0 (API 26). Requires "All files access" permission on Android 11+.

## Building from Source

### Prerequisites

- [Rust](https://rustup.rs) with Android targets:
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
  ```
- [Android NDK](https://developer.android.com/ndk) (r28+)
- [cargo-ndk](https://github.com/bbqsrc/cargo-ndk): `cargo install cargo-ndk`
- Android SDK with API 34+

### Build

```bash
bash build.sh
```
```
User taps file → Kotlin UI calls format-specific JNI
                         ↓
          libarchive_xp3_core.so  → XP3
          libarchive_pfs_core.so  → PFS
          libarchive_nsa_core.so  → NSA/SAR
          libarchive_iso_core.so  → ISO 9660
          libarchive_ypf_core.so  → YPF (YU-RIS)
          libarchive_zip_core.so  → ZIP
          libarchive_sevenz_core.so → 7z
          libarchive_rar_core.so  → RAR
          libarchive_lz4_core.so  → LZ4
          libarchive_gzip_core.so → GZIP
          libarchive_bzip2_core.so → BZIP2
          libarchive_xz_core.so   → XZ
          libarchive_zstd_core.so → ZSTD
          libarchive_lzma_core.so → LZMA
          libarchive_tar_core.so  → TAR (+ tgz/tbz2/txz/tzst)
          libarchive_ksd_core.so  → KSD
                         ↓
              Files written to selected directory
```

Each format lives in `crates/<format>-core/` as an independent `cdylib`. Shared utilities (progress stores, cancel checks, JSON escaping) live in `crates/common/`. The RAR5 streaming-filter fix is a vendored fork of `rars` (`crates/vendor/rars`, wired via `[patch.crates-io]`).

The Android side is split by domain — `MainActivity` (~360 lines) only wires `onCreate`; file browsing, multi-select, batch operations, extract/preview flows, settings and search each live in their own file (`browse/`, `batch/`, `extract/`, `search/`, `bookmarks/`, `ui/`, `archive/`, `fileops/`), exposing functions as `MainActivity` extensions so every entry point keeps the same signature.

### YPF (YU-RIS) Format — Three-Layer Defense

YPF uses obfuscated filenames (XOR + Shift-JIS). The parser applies three layers:

1. **GARbro SwapTable** — paired byte lookup for marker→length mapping
2. **Fixed Kaitai mapping table** — fallback for markers not in the swap table
3. **Adaptive boundary detection** — scans for `file_type` (0–6) + `compressed` (0–1) byte pairs to re-align on malformed entries

XOR key auto-detection (0xFF vs 0xC9) is done per-file on the first entry.

## Sources & Credits

### Format Parsers

| Format | Source / Reference | License |
|--------|-------------------|---------|
| **XP3** | [xp3 crate](https://crates.io/crates/xp3) | MIT / Apache-2.0 |
| **PFS / PF6 / PF8** | [pf8 crate](https://crates.io/crates/pf8) | See [crates.io/pf8](https://crates.io/crates/pf8) |
| **NSA / SAR** | [NSA 格式规范](https://orin.page/w/index.php?title=NSA), LZSS/SPB via [GARbro](https://github.com/morkt/GARbro) / [ONScripter](https://github.com/nscripter/nscripter) | Public spec / MIT / GPL |
| **YPF** | [YU-RIS 格式解析参考](https://github.com/mwzzhang/python-YU-RIS-package-file-unpacker) (Kaitai), [GARbro](https://github.com/morkt/GARbro) SwapTable, XOR + Shift-JIS, zlib | Public spec / MIT |
| **ISO 9660** | [isomage crate](https://crates.io/crates/isomage) | MIT |
| **ZIP** | [zip crate](https://crates.io/crates/zip) | MIT |
| **7z** | [sevenz-rust crate](https://crates.io/crates/sevenz-rust) | MIT / Apache-2.0 |
| **RAR** | [rars crate](https://crates.io/crates/rars) (vendored fork with streaming filters) | MIT / Apache-2.0 |
| **LZ4** | [lz4_flex crate](https://crates.io/crates/lz4_flex) | MIT |
| **GZIP** | [flate2 crate](https://crates.io/crates/flate2) (Rust backend) | MIT / Apache-2.0 |
| **BZIP2** | [oxiarc-bzip2 crate](https://crates.io/crates/oxiarc-bzip2) | Apache-2.0 |
| **XZ / LZMA** | [xz2 crate](https://crates.io/crates/xz2) (liblzma, .xz) + [lzma-sys](https://crates.io/crates/lzma-sys) (.lzma) | Public domain (liblzma) / 0BSD |
| **KSD** | [krkr-save-tools](https://github.com/Luv-Ray/krkr-save-tools), [KirikiriTools](https://github.com/arcusmaximus/KirikiriTools) | MIT |
| **ZSTD** | [ruzstd crate](https://crates.io/crates/ruzstd) (decode) / [oxiarc-zstd crate](https://crates.io/crates/oxiarc-zstd) (encode) | MIT / Apache-2.0 |
| **TAR** | [tar crate](https://crates.io/crates/tar) | MIT / Apache-2.0 |
| **Signature scan** | Magic definitions and validation approach referenced from [binwalk](https://github.com/ReFirmLabs/binwalk) | MIT |

### Core Dependencies

| Crate | License | Usage |
|-------|---------|-------|
| `jni` 0.21 | MIT / Apache-2.0 | Android JNI bridge |
| `xp3` 0.4 | MIT / Apache-2.0 | XP3 pack/unpack |
| `pf8` 0.1 | — | PFS/PF6/PF8 pack/unpack |
| `isomage` 0.1 | MIT | ISO 9660 / UDF |
| `flate2` 1 | MIT / Apache-2.0 | zlib (YPF/KSD) + gzip pack/unpack |
| `encoding_rs` 0.8 | (Apache-2.0 OR MIT) AND BSD-3-Clause | Shift-JIS (YPF) |
| `tokio` 1 | MIT | Async I/O (XP3) |
| `rars` 0.4 | MIT / Apache-2.0 | RAR extraction (vendored fork) |
| `lz4_flex` | MIT | LZ4 pack/unpack |
| `oxiarc-bzip2` | Apache-2.0 | BZIP2 pack/unpack |
| `xz2` / `lzma-sys` | 0BSD | XZ + LZMA (liblzma) pack/unpack |
| `ruzstd` | MIT | ZSTD decode |
| `oxiarc-zstd` | Apache-2.0 | ZSTD encode |
| `tar` | MIT / Apache-2.0 | TAR pack/unpack |

## License

This project: **MIT License** — see [LICENSE](LICENSE).

All third-party dependencies retain their respective licenses as listed above.

## Author

**znso4pa (锌帕)**

GitHub: [github.com/znso4pa/usefulunpack](https://github.com/znso4pa/usefulunpack)

---

## Disclaimer

This tool is intended **solely for managing and accessing files you legally own**.
- It does not contain, provide, or bypass any digital rights management (DRM) or copy protection mechanisms
- All format parsers are based on publicly available format specifications or open-source reference implementations
- The XOR values used in the YPF format parser are part of the public YU-RIS engine format specification, not reverse-engineered secrets
- Do not use this tool for unauthorized extraction or distribution of copyrighted content
- The author assumes no responsibility for any illegal or improper use
