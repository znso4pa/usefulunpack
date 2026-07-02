# UsefulUnpack

[**中文**](README-zh.md) | [**English**](README.md)

A lightweight Android file manager and archive extraction tool for visual novel game files.

Supports **XP3** (Kirikiri), **PFS** (Artemis), **NSA/SAR** (NScripter), **YPF** (YU-RIS), **ZIP**, **7z**, and **ISO 9660** disc images — with native Rust-powered extraction.

---

## Features

| Feature | Description |
|---------|-------------|
| 📁 **XP3** | Unpack Kirikiri `.xp3` archives |
| 📦 **PFS** | Unpack Artemis `.pfs` / `.pf6` / `.pf8` archives |
| 📜 **NSA/SAR** | Unpack NScripter `.nsa` / `.sar` archives (incl. zlib-compressed) |
| 📦 **YPF** | Unpack YU-RIS `.ypf` archives with adaptive boundary detection |
| 💿 **ISO 9660** | Browse and extract ISO disc images (CD/DVD/BD) via isomage |
| 🔍 **Archive Preview** | Browse archive contents as a collapsible tree with checkboxes for selective extraction |
| 📊 **Preview Statistics** | Real-time count/size of total and selected files |
| 🔎 **Global Search** | Filename search + content search (30+ text formats), match highlighting with prev/next navigation, progressive scanning |
| 📦 **In-Archive Search** | One-click unpack text files from preview and open the full global search interface on extracted content |
| 🖼️ **File Preview** | Image (JPG/PNG), audio (MP3/OGG), video (MP4), text/code — jump to matching line on search results |
| 📂 **Local File Preview** | Tap any previewable file in the browser to view directly |
| 🗂 **File Browser** | ZArchiver-style UI with path breadcrumb, fast scroll, folder ⭐ bookmarks |
| 📌 **Bookmarks** | Quick-access paths via star button on folders or slide-out drawer |
| 🏠 **Root Navigation** | One-tap home button to jump to `/storage/emulated/0` |
| 🛡️ **Tap Debounce** | 800ms cooldown prevents accidental duplicate dialogs |
| 🌙 **Dark Theme** | Eye-friendly dark theme matching ZArchiver's color scheme |
| 🦀 **Rust Core** | JNI-powered native `.so` — one per format for isolation |
| 🔒 **Minimal Permissions** | Only requests storage access |

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

This builds each format as a separate `.so` via the Cargo workspace, then assembles the APK via Gradle.

## Architecture (v4.0+)

```
User taps file → Kotlin UI calls format-specific JNI
                         ↓
          libarchive_xp3_core.so  → XP3
          libarchive_pfs_core.so  → PFS
          libarchive_nsa_core.so  → NSA/SAR
          libarchive_iso_core.so  → ISO 9660
          libarchive_ypf_core.so
	          libarchive_zip_core.so  → **ZIP**
	          libarchive_sevenz_core.so → **7z**  → YPF (YU-RIS)
                         ↓
              Files written to selected directory
```

Each format lives in `crates/<format>-core/` as an independent `cdylib`. Shared utilities (SyncIo, oneshot_async, JSON escaping) live in `crates/common/`.

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
| **PFS / PF8** | [pf8 crate](https://crates.io/crates/pf8) | See [crates.io/pf8](https://crates.io/crates/pf8) |
| **NSA / SAR** | [NScripter NSA format](https://orin.page/w/index.php?title=NSA) | Public spec |
| … NSA zlib | [flate2 crate](https://crates.io/crates/flate2) | MIT / Apache-2.0 |
| **YPF** | [python-YU-RIS-unpacker](https://github.com/mwzzhang/python-YU-RIS-package-file-unpacker) (Kaitai) | Public spec |
| … YPF SwapTable | [GARbro](https://github.com/morkt/GARbro) (ArcYPF.cs) | MIT |
| … YPF filenames | XOR + Shift-JIS via [encoding_rs](https://crates.io/crates/encoding_rs) | (Apache-2.0 OR MIT) AND BSD-3-Clause |
| … YPF zlib | [flate2 crate](https://crates.io/crates/flate2) | MIT / Apache-2.0 |
| **ISO 9660** | [isomage crate](https://crates.io/crates/isomage) | MIT |

### Core Dependencies

| Crate | License | Usage |
|-------|---------|-------|
| `jni` 0.21 | MIT / Apache-2.0 | Android JNI bridge |
| `xp3` 0.4 | MIT / Apache-2.0 | XP3 extraction |
| `pf8` 0.1 | — | PFS/PF6/PF8 extraction |
| `isomage` 0.1 | MIT | ISO 9660 / UDF |
| `flate2` 1 | MIT / Apache-2.0 | zlib (NSA + YPF) |
| `encoding_rs` 0.8 | (Apache-2.0 OR MIT) AND BSD-3-Clause | Shift-JIS (YPF) |
| `tokio` 1 | MIT | Async I/O (XP3) |

## License

This project: **MIT License** — see [LICENSE](LICENSE).

All third-party dependencies retain their respective licenses as listed above.

## Author

**znso4pa (锌帕)**

GitHub: [github.com/znso4pa/usefulunpack](https://github.com/znso4pa/usefulunpack)

---

## Disclaimer

This tool is provided for personal use with legally owned files.
The author assumes no responsibility for any misuse.
