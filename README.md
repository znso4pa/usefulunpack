# UsefulUnpack

[**中文**](README-zh.md) | [**English**](README.md)

A lightweight Android file manager and archive extraction tool for visual novel game files.

Supports **XP3** (Kirikiri), **PFS** (Artemis), **NSA/SAR** (NScripter), **YPF** (YU-RIS), and **ISO 9660** disc images — with native Rust-powered extraction.

---

## Features

| Feature | Description |
|---------|-------------|
| 📁 **XP3 Extraction** | Unpack Kirikiri `.xp3` archives |
| 📦 **PFS Extraction** | Unpack Artemis `.pfs` / `.pf6` / `.pf8` archives |
| 📜 **NSA/SAR Extraction** | Unpack NScripter `.nsa` / `.sar` archives (incl. zlib-compressed) |
| 📦 **YPF Extraction** | Unpack YU-RIS `.ypf` archives |
| 💿 **ISO 9660 Extraction** | Browse and extract ISO disc images (CD/DVD/BD) |
| 🔍 **Archive Preview** | Browse archive contents as a collapsible tree with checkboxes for selective extraction |
| 📊 **Preview Statistics** | Real-time count/size of total and selected files |
| 🖼️ **File Preview** | Image (JPG/PNG), audio (MP3/OGG), video (MP4), text/code (TXT/JSON/INI/KS/LUA/PY/JS/HTML/CSS/XML) |
| 📂 **Local File Preview** | Tap any previewable file in the browser to view directly |
| 🗂 **File Browser** | ZArchiver-style UI with path breadcrumb, fast scroll, folder ⭐ bookmarks |
| 📌 **Bookmarks** | Quick-access paths via star button on folders or slide-out drawer |
| 🏠 **Root Navigation** | One-tap home button to jump to `/storage/emulated/0` |
| 🛡️ **Tap Debounce** | 800ms cooldown prevents accidental duplicate dialogs |
| 🌙 **Dark Theme** | Eye-friendly dark theme matching ZArchiver's color scheme |
| 🦀 **Rust Core** | JNI-powered native `.so` for high-performance extraction |
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
# One-step build
bash build.sh

# Or manually:
ANDROID_NDK_HOME=/path/to/ndk cargo ndk --target aarch64-linux-android --platform 26 build --release
cp target/aarch64-linux-android/release/libarchive_core.so app/src/main/jniLibs/arm64-v8a/
ANDROID_HOME=/path/to/sdk ./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

## Architecture

```
User taps file → Kotlin UI calls ArchiveCore JNI
                         ↓
              libarchive_core.so (Rust)
               ├── xp3 crate  → XP3 extraction
               ├── pf8 crate  → PFS extraction
               ├── nsa parser → NSA/SAR extraction
               ├── ypf parser → YPF extraction
               └── isomage    → ISO 9660 extraction
                         ↓
              Files written to selected directory
```

| Layer | Technology |
|-------|-----------|
| UI | Kotlin + AndroidX + Material Design |
| Bridge | JNI (Java Native Interface) |
| Core | Rust (`xp3` v0.4, `pf8` v0.1, `isomage` v0.1, `flate2` v1, `encoding_rs` v0.8) |
| File API | `std::fs::File` + `SyncIo` + `oneshot_async` |

## Sources & Credits

### Format Parsers

| Format | Source / Reference | License |
|--------|-------------------|---------|
| **XP3** | [xp3 crate](https://crates.io/crates/xp3) (based on [xp3-tool](https://github.com/storycraft/xp3-tool)) | MIT / Apache-2.0 |
| **PFS / PF8** | [pf8 crate](https://crates.io/crates/pf8) | See [crates.io/pf8](https://crates.io/crates/pf8) |
| **NSA / SAR** | [NScripter NSA format spec](https://orin.page/w/index.php?title=NSA) (Game Research Wiki) | Public domain specification |
| … NSA zlib | zlib decompression via [flate2 crate](https://crates.io/crates/flate2) | MIT / Apache-2.0 |
| **YPF** | [YU-RIS package format](https://github.com/mwzzhang/python-YU-RIS-package-file-unpacker) (Kaitai Struct spec) | Public domain specification |
| … YPF filenames | XOR-201 obfuscation + Shift-JIS via [encoding_rs](https://crates.io/crates/encoding_rs) | (Apache-2.0 OR MIT) AND BSD-3-Clause |
| … YPF zlib | zlib decompression via [flate2 crate](https://crates.io/crates/flate2) | MIT / Apache-2.0 |
| **ISO 9660** | [isomage crate](https://crates.io/crates/isomage) (parses ISO + UDF) | MIT |

### Core Dependencies

| Crate | Version | License | Usage |
|-------|---------|---------|-------|
| `jni` | 0.21 | MIT / Apache-2.0 | Android JNI bridge |
| `xp3` | 0.4 | MIT / Apache-2.0 | XP3 archive extraction |
| `pf8` | 0.1 | — | PFS/PF6/PF8 extraction |
| `isomage` | 0.1 | MIT | ISO 9660 / UDF parsing |
| `flate2` | 1 | MIT / Apache-2.0 | zlib decompression (NSA + YPF) |
| `encoding_rs` | 0.8 | (Apache-2.0 OR MIT) AND BSD-3-Clause | Shift-JIS decoding (YPF filenames) |
| `tokio` | 1 | MIT | Async I/O for XP3 reader |

### Compilation Notes

- `android.useAndroidX=true` is required
- minSdk 26 is chosen for Android 8.0+ compatibility
- `opt-level = "s"` and `lto = true` keep the `.so` minimal
- Release builds require a signing keystore — see `app/build.gradle` for configuration

## License

This project: **MIT License** — see [LICENSE](LICENSE) for details.

All third-party dependencies retain their respective licenses as listed above.

## Author

**znso4pa (锌帕)**

GitHub: [github.com/znso4pa/usefulunpack](https://github.com/znso4pa/usefulunpack)

---

## Disclaimer

This tool is provided for personal use with legally owned files.
The author assumes no responsibility for any misuse.
