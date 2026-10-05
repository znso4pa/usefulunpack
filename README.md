# UsefulUnpack

[**中文**](README-zh.md) | [**English**](README.md) | [**繁體中文**](README-zh-TW.md) | [**日本語**](README-ja.md)

A lightweight Android file manager and archive packing/unpacking tool.

Supports **XP3** (Kirikiri), **PFS** (Artemis), **NSA/SAR** (NScripter), **YPF** (YU-RIS), **RGSS** (RPG Maker XP/VX/VX Ace), **KSD** (Kirikiri2), **ZIP**, **7z**, **RAR**, **LZ4**, and **ISO 9660** disc images — with native Rust-powered extraction and packing.

---

## Features

| Feature | Description |
|---------|-------------|
| 📁 **XP3** | Pack & unpack Kirikiri `.xp3` archives |
| 📦 **PFS** | Pack & unpack Artemis `.pfs` / `.pf6` / `.pf8` archives |
| 📜 **NSA/SAR** | Unpack NScripter `.nsa` / `.sar` archives (LZSS + SPB), **pack** (stored / LZSS) |
| 🗜️ **ZIP** | Browse/extract/pack ZIP (AES-256, split volumes); **PKWARE multi-disk** (`.z01/.z02/.zip`) with **cross-disk entries**; **in-place edit** — replace / delete / add entries without repacking the whole archive, **AES flag preserved** for untouched encrypted entries; edits save as a `name-cn.zip` copy (original untouched) |
| 📦 **YPF** | Unpack YU-RIS `.ypf` archives with adaptive boundary detection |
| 🎮 **RGSS** | Unpack **and pack** RPG Maker encrypted archives (packed output is named `Game.rgss3a`, the only name the engine opens) — `.rgssad` (XP), `.rgss2a` (VX), `.rgss3a` (VX Ace); layout auto-detected from the header, so any of the three opens any of the three; UTF-8 / Shift-JIS names; edit scripts in-place |
| 🎮 **RPG Maker MV/MZ** | Decode **and re-obfuscate** per-file assets — `.rpgmvp` pictures, `.rpgmvo` sounds, `.rpgmvm` movies (and MZ's `.png_` / `.ogg_` / `.m4a_`); decoding needs no key because the 16-byte header is recovered from the file itself (including for files whose name carries no extension — the content decides), and packing takes the keystream you type (32 hex digits verbatim, or any text to be MD5-hashed the way RPG Maker does it). **Each key takes only its own type: `.rpgmvp` packs `.png`, `.rpgmvo` packs `.ogg`, `.rpgmvm` packs `.m4a`** — anything else is refused, because the engine picks its loader from the extension; output named with its real extension so it opens in the image / audio viewer; batch mode handles a whole selection |
| 💾 **KSD** | Pack/unpack `.ksd` files — mode 0/1/2 scrambling + UTF-16 ↔ UTF-8 |
| 💿 **ISO 9660** | Browse and extract ISO disc images (CD/DVD/BD) via isomage; **pack** (Level 1); **CSO↔ISO conversion** (PSP CISO) |
| 🗜️ **RAR** | Unpack RAR archives (RAR4/5) with password support; **header-encrypted (`-hp`) archives** prompt for a password and preview/extract with it; **members >64MB stream** (byte-level progress, bounded memory — no RAM spike on 100–500MB members); **non-solid archives extract selected files / preview with random access** (a late text file opens instantly, skipping the preceding members); **Huffman lookahead decode** (~30% faster) |
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
| 🖼️ **File Preview** | Image (JPG/PNG/**GIF/WebP animated**/BMP), audio (MP3/OGG), video (MP4), text/code (md/rtf/yaml/vtt/csv/xhtml/vsq/ksc…) — search results jump to the matching line; **Markdown/RTF rich rendering** with a render/plain toggle; large text is previewed with a bounded read (no OOM) in a bigger dialog with a **draggable scrollbar** |
| 🗜️ **Compression** | ZIP/7z + gzip/bzip2/xz/zstd/lzma/lz4 (single file) + tar (folder, 5 variants) + xp3/pfs/nsa/iso/ksd, 5 levels, AES-256 (ZIP) |
| 📚 **Multi-Volume** | Unpack `.7z.001` / `.zip.001` / `.rar` split volumes; pack zip/7z into byte-split volumes (7-Zip compatible) |
| 🎚️ **Custom Split Size** | Split zip/7z output at a custom size (MB/GB, 1MB–2GB) |
| 🔐 **Extract with Password** | Prompt-first password dialog for encrypted ZIP/7z/RAR; batch extraction asks once and reuses it |
| 🔒 **Password Badge** | Archives that need a password show a lock icon in the file browser and in the preview list |
| 📊 **Dual Progress Bar** | Top bar = overall progress, bottom bar = current file progress (extract + compress), both always visible — every extract/compress entry point (preview, batch, search, edit-repack) uses the same dialog; large (>64MB) RAR members stream so the top bar advances byte-by-byte instead of jumping per file; a cancelled extract into a fresh folder is cleaned up entirely |
| 📋 **Grouped Format Picker** | Scrollable, grouped format selection (generic / single-file / other) for extract, batch and compress |
| 📄 **Single-File Compress FAB** | In compress mode, tap any file to get a bottom-right compress button |
| 📦 **Batch Compress Picker** | Merge / separate batch compress both ask for a format; merge excludes single-file formats |
| ✂️ **File Operations** | Long-press to rename, move, delete (irreversible), create folder |
| ☑️ **Batch Multi-Select** | Multi-select mode for batch extract/compress/delete/move |
| 📂 **Batch Preview** | Preview multiple archives at once, select files across all of them |
| 📂 **Local File Preview** | Tap any previewable file in the browser to view directly |
| 🗂 **File Browser** | ZArchiver-style UI with path breadcrumb, fast scroll, folder ⭐ bookmarks |
| 🪟 **Multi-Window Tabs** | Up to 3 independent windows (ViewPager2 swipe, add/close). Each window keeps its own path, selected file, multi-select state, batch bar and paste/move state — fully isolated. **Long-press a tab label to rename it**; Chrome-style top tab bar (active tab highlighted with a rounded pill) |
| 📦 **In-Window Archive Preview** | FAB preview renders INSIDE the current window (no modal), so **you can swipe between windows while previewing**; bottom bar has Extract all / Extract selected / Merge (the merge button greys out when no merge target exists, so you never hit a dead end), top bar has Search + a **⋮ overflow menu** (Edit / ZIP-manage / ISO-convert, shown per format, toast when not applicable). Entering global search from a preview returns to the preview when closed |
| 🔀 **Cross-Archive Merge** | In a preview, select entries → "Merge" → pick a target from **this folder or an archive another window has open** (grouped list, annotated with the window name) → the source's selected entries are unpacked, the target is unpacked over the same staging dir (so matching paths are overwritten by the source), and the result is repacked into a `target-cn.ext` copy next to the target. The target is only ever read and the original is never touched, so merging into an archive another window is previewing is safe. The button is greyed out when there is nothing to merge into |
| 📍 **Path/File Picker Mode** | General settings toggle: "Open path picker dialog" (default) or "Pick in a new window" — the latter opens a new window to browse and pick, then closes it and returns to the origin window (applies to search scope / extract target / ZIP add / compress target) |
| 📌 **Bookmarks** | Quick-access paths via star button on folders or slide-out drawer |
| 🏠 **Root Navigation** | One-tap home button to jump to `/storage/emulated/0` |
| ⚡ **One-Tap Preview** | Tap an archive → FAB → direct archive preview; format auto-detected from the extension (`.tar.gz`/`.tgz`/`.pf6`/`.sar`…), no extra chooser dialogs |
| 📦 **Extract All** | One-click "Extract all" in the preview extracts to a deduped sibling folder; reuses the password entered during preview |
| 🧩 **Extensionless Detection** | Magic-byte sniffing recognizes archives that lost their extension or were mislabeled, with a manual format picker as fallback |
| 🔬 **Signature Scan** | Rust scan-core engine: **32 signatures / 83 magic patterns** at any offset, per-format header validation (real size + file counts, incl. **tar** `ustar` and **ISO 9660**), **decompression dry-runs** (gzip/xz/lzma) cutting false positives, **header-encrypted RAR5 fallback**, Aho-Corasick matching, streaming scan (no whole-file load), one tap to extract or carve (dd) the raw segment — works for archives embedded between other files |
| 🖥️ **Built-in Terminal (CLI)** | Full-screen terminal with the `uu` command set — `uu fmt / help / info / l / cat / hash / grep / cp / mv / rn / rm / mkdir / tree / du / stat / x / c / set / scan / cso / enc / mvdec / rmd / add / find / diff / hex / img / fd / b64` plus pipes (`| grep/head/tail/wc/sort`), `> file` redirection, `&&` / `;` chaining, and **UUT scripts** via `uu run <file.uut>` (variables, `$1`/`$argc` script arguments, `$(...)` output capture, inline and block `if`/`else`, `for a in *.zip ... end`, `for i in 1..5` counted loops, `while` + `break`/`continue`, `else if` chains, integer arithmetic (`set n = $n + 1`), numeric comparisons (`if n > 3`), `||` chaining, `for x in $(command)` iterating output lines, `if exist <path>` tests, `$?` (previous exit code) and `return [code]`; only `uu`/`ls`/`cd`/`pwd`/`help`/`echo` are allowed, so a script is never a shell). `am start --es uut <path>` runs a script headlessly for automation (results go to the terminal session and to `<script>.log`). Extract & pack render progress **inside the terminal** (a ~30-char ASCII bar with bytes, current file and entry counters — no dialogs) and finish with an "all set" line. Extraction selects entries with wildcards too (`uu x game.xp3 "*.png"`) `uu l -j` prints the raw entry JSON (`n/s/d/e`) for scripts and `uu l -t` renders the entries as a tree; `uu cp`/`uu mv` take `-f` to overwrite (idempotent scripts), `uu l -S` sorts by size, `uu cat` also prints plain text files, `uu rmd` takes globs, and UUT lines accept trailing `#` comments. Scan hits register as `fN` byte-range descriptors: `uu scan video.mp4` then `uu l f3` lists an embedded ZIP without carving it to disk. Built-ins `ls / pwd / cd` (cd navigates the window the terminal is rooted to); any other line runs in the system shell. Tab-rooted: switch away and it hides, switch back and the session is still there |
| 🪟 **Preview Workspace** | ⋮ menu in an archive preview → "Open in Window": the archive materializes into a normal browsable window, so multi-select / copy / move / rename / share / file-info all work for free; nested archives recurse naturally (a workspace can open another workspace); large archives (>200MB) ask first; closing offers cache cleanup; session restore reopens it while the cache exists |
| 🔤 **Text Encoding** | Global text-encoding setting (UTF-8 / Shift-JIS / GBK / UTF-16) applied strictly to every text preview and content search — BOM-aware UTF-8/UTF-16 with **auto-detection** (a UTF-16 BOM opens as UTF-16 automatically); the preview/editor have an inline encoding switch that re-renders instantly; heavy garble prompts a switch hint, and a strictly-valid-UTF-8 check flags the classic "legal-but-wrong" cross-read |
| ✏️ **Archive Text Editing** | Edit script/text files inside XP3/PFS/ISO/NSA/**7z** archives: extract the archive, pick a script (`.ks`/`.tjs`/`.csv`/…), edit it with an explicit encoding + byte-faithful BOM round-trip, then repack into a new `name-cn.xp3/pfs/iso/nsa/7z` — a full in-app edit loop; ZIP entries edit in place and save as `name-cn.zip` |
| 🎨 **Image Editor** | Open from the image preview **⋮** menu: watercolor/highlighter brush (color palette + opacity + width — each stroke keeps the opacity it was drawn at, so lowering it never restyles earlier work), **rectangular crop** (drag → tap Crop again to confirm), **stretch to 1:1 / 4:3 / 3:4 / 16:9 / 9:16**, **pixel eyedropper** (drag to auto-sample, tap the readout to copy HEX/RGB), **two-finger pinch zoom (1–8×) + pan**, undo / reset. Saves a `name-edit.png` copy — original untouched (~2048px working cap, rotation-safe autosave with restore prompt) |
| 🔄 **Image Format Conversion** | From the image preview **⋮** menu: convert static **JPG/PNG/WebP** between each other, and animated **GIF/WebP → a static first frame** (JPG/PNG/WebP). Saves a copy, original untouched |
| 📤 **Share** | Long-press any file → **Share**: hands it to another app via FileProvider + `ACTION_SEND` — no network permission, no manifest change |
| 💾 **Session Restore** | Settings → **Other settings** → "Restore last session": on launch reopen the previous windows/folders **and the archive previews that were open** for up to 3 tabs (toast if more). The Recycle-bin settings live under Other settings too |
| 🎨 **Redesigned UI** | Unified design tokens (corner radii / spacing / type / row heights), a **⋮ overflow menu** in every preview, capped dialogs on tablets/landscape, and a **wide-screen master-detail layout** (file list left + in-window preview right on ≥600dp) |
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

## Multi-Window FAQ

**1. Can three windows extract at once and risk an OOM?**

No — currently only one extract/compress runs at a time. A global single lock (`OperationLock`) serializes operations, so the 2nd/3rd extract is rejected with a "busy" toast. Memory pressure stays identical to a single-window extract, so OOM risk is very low. The real memory source is *within* a single archive's parallel decode (RAR non-solid ≤4 threads, ZIP entry-level ≤32 MiB batches); members >64 MB stream without whole-buffer buffering. Note the "parallel decode threads" setting is global, not per-window — 4/8 threads on one window already uses the full memory budget.

**2. If two windows open the same file, does a change in window 1 sync instantly to the others?**

Each window has its own directory watcher (FileObserver). **When both windows are in the same directory** (e.g. both in `/Download`), any extract/rename/delete raises directory events that both watchers receive and both refresh — **instant sync**. Windows in different directories don't affect each other.

The **same archive can never be open in two windows** (volume members like `name.zip.001/.zip` count as one archive): a built-in open-registry lock (OpenArchiveRegistry) makes the second window toast "This archive is already open in window N" and jump to it instead. The lock is held while previewing/editing and released on dialog dismiss or window close.

**3. Can I switch windows while previewing an archive?**

Yes. The archive preview renders **inside the current window** (not a modal dialog), so the ViewPager stays fully swipeable — flip between windows while previewing, each window keeps its own preview/selection state. Entering global search from a preview returns to the preview when the search dialog closes.

**4. Can path/file picking open a new window?**

Yes. General settings → "Path/file picker" toggles between **Open path picker dialog** (default) and **Pick in a new window** — the latter opens a new window to browse and pick, then auto-closes it and returns to the origin window (which keeps its state); pressing Back cancels the pick. Applies to search scope, extract target, ZIP add-entry and compress target.

Signature scan details (parser + size-skip semantics, following the approach of the MIT-licensed binwalk project):

| Design | How it works |
|--------|--------------|
| Engine | Aho-Corasick multi-pattern matching over 1 MiB streaming chunks (magics straddling a boundary still match); 32 signatures / 83 magic patterns |
| Validation | Every hit runs a per-format header parser (ZIP EOCD, RAR EOF marker + volume flags, PNG chunk walk, JPEG marker walk, gzip/bzip2/xz/zstd/lz4/lzma header checks, …) plus **decompression dry-runs for gzip/xz/lzma**; header-encrypted RAR5 falls back to a whole-file region — false positives are dropped |
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
          libarchive_rgss_core.so → RGSS (XP/VX/VX Ace) + MV/MZ loose assets
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
| **cxdec (XP3 content filter)** | [Cxdec_Tools](https://github.com/1F1E33-float32/Cxdec_Tools) (vendored cipher core + XP3 parsing, MIT), scheme table from [arc_unpacker](https://github.com/vn-tools/arc_unpacker) | MIT |
| **PFS / PF6 / PF8** | [pf8 crate](https://crates.io/crates/pf8) | See [crates.io/pf8](https://crates.io/crates/pf8) |
| **NSA / SAR** | [NSA 格式规范](https://orin.page/w/index.php?title=NSA), LZSS/SPB via [GARbro](https://github.com/morkt/GARbro) / [ONScripter](https://github.com/nscripter/nscripter) | Public spec / MIT / GPL |
| **YPF** | [YU-RIS 格式解析参考](https://github.com/mwzzhang/python-YU-RIS-package-file-unpacker) (Kaitai), [GARbro](https://github.com/morkt/GARbro) SwapTable, XOR + Shift-JIS, zlib | Public spec / MIT |
| **RPG Maker MV/MZ assets** | Format cross-checked against [Petschko's RPG-Maker-MV-Decrypter](https://gitlab.com/Petschko/RPG-Maker-MV-Decrypter) and [rpgm-asset-decrypter-lib](https://github.com/RPG-Maker-Translation-Tools/rpgm-asset-decrypter-lib) (MIT); the keystream is recovered from each file's own header, so no MD5 or `System.json` sidecar is needed | Public spec / MIT |
| **RGSS (RPG Maker)** | Layout cross-checked against [uuksu/RPGMakerDecrypter](https://github.com/uuksu/RPGMakerDecrypter) (MIT), [mkxp-z `crypto/rgssad.cpp`](https://github.com/mkxp-z/mkxp-z) (BSD-3-Clause) and [rpgm-archive-decrypter-lib](https://github.com/RPG-Maker-Translation-Tools/rpgm-archive-decrypter-lib) (Apache-2.0/MIT); no equivalent crate exists on crates.io, so the parser is self-contained | Public spec / MIT / BSD-3-Clause / Apache-2.0 |
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
| `md5` (self-implemented) | RFC 1321 | MV `encryptionKey` → keystream (format requirement, not a security control) |
| `flate2` 1 | MIT / Apache-2.0 | zlib (YPF/KSD) + gzip pack/unpack |
| `encoding_rs` 0.8 | (Apache-2.0 OR MIT) AND BSD-3-Clause | Shift-JIS (YPF, RGSS) |
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
