# UsefulUnpack

[**中文**](README-zh.md) | [**English**](README.md)

轻量级 Android 文件管理器 & **视觉小说游戏资源解包工具**

支持 **XP3**（吉里吉里/Kirikiri）、**PFS**（Artemis）、**NSA/SAR**（NScripter）、**YPF**（YU-RIS）和 **ISO 9660** 光盘镜像，Rust 原生核心，性能强劲。

---

## 功能

| 功能 | 说明 |
|------|------|
| ✂️ **XP3 解包** | 解压吉里吉里 `.xp3` 封包 |
| 📦 **PFS 解包** | 解压 Artemis `.pfs` / `.pf6` / `.pf8` 封包 |
| 📜 **NSA/SAR 解包** | 解压 NScripter `.nsa` / `.sar` 封包（含 zlib 压缩） |
| 📦 **YPF 解包** | 解压 YU-RIS `.ypf` 封包 |
| 💿 **ISO 提取** | 浏览和提取 ISO 9660 光盘镜像 |
| 🔍 **归档预览** | 预览归档内容树，可折叠/展开，选择性解压 |
| 📊 **预览统计** | 实时显示文件总数/总大小，以及已选文件统计 |
| 🖼️ **文件预览** | 图片（JPG/PNG）、音频（MP3/OGG）、视频（MP4）、文本/代码（TXT/JSON/INI/KS/LUA/PY/JS/HTML/CSS/XML） |
| 📂 **本地预览** | 在浏览器中直接点击可预览文件 |
| 🗂 **文件浏览器** | 类 ZArchiver 界面，路径面包屑、快速滚动、文件夹 ⭐ 星标收藏 |
| 📌 **书签** | 文件夹星标 + 侧滑抽屉，快速跳转常用目录 |
| 🏠 **根目录导航** | 一键回到 `/storage/emulated/0` 根目录 |
| 🛡️ **防连点** | 800ms 冷却防止误触弹出多个对话框 |
| 🌙 **深色主题** | 护眼暗色主题，配色参照 ZArchiver |
| 🦀 **Rust 核心** | JNI 调用原生 `.so`，解压更快更稳 |
| 🔒 **最小权限** | 仅请求文件存储权限 |

## 截图

<p align="middle">
  <img src="screenshots/screenshot_01.jpg" width="45%" />
  <img src="screenshots/screenshot_02.jpg" width="45%" />
</p>
<p align="middle">
  <img src="screenshots/screenshot_03.jpg" width="45%" />
  <img src="screenshots/screenshot_04.jpg" width="45%" />
</p>

## 安装

从 [Releases](https://github.com/znso4pa/usefulunpack/releases) 下载最新 APK。

最低 Android 8.0（API 26），Android 11+ 需要授予「所有文件访问」权限。

## 从源码构建

### 前置依赖

- [Rust](https://rustup.rs) 并添加 Android 目标平台：
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
  ```
- [Android NDK](https://developer.android.com/ndk)（r28+）
- [cargo-ndk](https://github.com/bbqsrc/cargo-ndk)：`cargo install cargo-ndk`
- Android SDK（API 34+）

### 构建

```bash
# 一键构建
bash build.sh

# 或手动：
ANDROID_NDK_HOME=/path/to/ndk cargo ndk --target aarch64-linux-android --platform 26 build --release
cp target/aarch64-linux-android/release/libarchive_core.so app/src/main/jniLibs/arm64-v8a/
ANDROID_HOME=/path/to/sdk ./gradlew assembleRelease
# 输出：app/build/outputs/apk/release/app-release.apk
```

## 架构

```
用户操作 → Kotlin UI → JNI 桥
                  ↓
         libarchive_core.so (Rust)
           ├── xp3 crate  → XP3 解包
           ├── pf8 crate  → PFS 解包
           ├── nsa parser → NSA/SAR 解包
           ├── ypf parser → YPF 解包
           └── isomage    → ISO 9660 提取
                  ↓
          文件写入目标目录
```

| 层 | 技术 |
|----|------|
| UI | Kotlin + AndroidX + Material Design |
| 桥接 | JNI（Java Native Interface） |
| 核心 | Rust（`xp3` v0.4, `pf8` v0.1, `isomage` v0.1, `flate2` v1, `encoding_rs` v0.8） |
| 文件 API | `std::fs::File` + `SyncIo` + `oneshot_async` |

## 源码来源与致谢

### 格式解析器

| 格式 | 来源 / 参考 | 协议 |
|------|-----------|------|
| **XP3** | [xp3 crate](https://crates.io/crates/xp3)（基于 [xp3-tool](https://github.com/storycraft/xp3-tool)） | MIT / Apache-2.0 |
| **PFS / PF8** | [pf8 crate](https://crates.io/crates/pf8) | 见 [crates.io/pf8](https://crates.io/crates/pf8) |
| **NSA / SAR** | [NScripter NSA 格式规范](https://orin.page/w/index.php?title=NSA)（Game Research Wiki） | 公开规范 |
| … NSA zlib | zlib 解压基于 [flate2 crate](https://crates.io/crates/flate2) | MIT / Apache-2.0 |
| **YPF** | [YU-RIS package format](https://github.com/mwzzhang/python-YU-RIS-package-file-unpacker)（Kaitai Struct 规范） | 公开规范 |
| … YPF 文件名 | XOR-201 去混淆 + Shift-JIS 解码（[encoding_rs](https://crates.io/crates/encoding_rs)） | (Apache-2.0 OR MIT) AND BSD-3-Clause |
| … YPF zlib | zlib 解压基于 [flate2 crate](https://crates.io/crates/flate2) | MIT / Apache-2.0 |
| **ISO 9660** | [isomage crate](https://crates.io/crates/isomage)（解析 ISO + UDF） | MIT |

### 核心依赖

| Crate | 版本 | 协议 | 用途 |
|-------|------|------|------|
| `jni` | 0.21 | MIT / Apache-2.0 | Android JNI 桥接 |
| `xp3` | 0.4 | MIT / Apache-2.0 | XP3 封包提取 |
| `pf8` | 0.1 | — | PFS/PF6/PF8 提取 |
| `isomage` | 0.1 | MIT | ISO 9660 / UDF 解析 |
| `flate2` | 1 | MIT / Apache-2.0 | zlib 解压（NSA + YPF） |
| `encoding_rs` | 0.8 | (Apache-2.0 OR MIT) AND BSD-3-Clause | Shift-JIS 解码（YPF 文件名） |
| `tokio` | 1 | MIT | XP3 读取器的异步 I/O |

### 编译说明

- 需要启用 `android.useAndroidX=true`
- minSdk 26 确保 Android 8.0+ 兼容
- `opt-level = "s"` 和 `lto = true` 使 `.so` 体积最小化
- Release 构建需签名 keystore — 配置见 `app/build.gradle`

## 许可证

本项目：**MIT License** — 详见 [LICENSE](LICENSE)。

所有第三方依赖保留各自协议，已在上方列出。

## 作者

**znso4pa（锌帕）**

GitHub：[github.com/znso4pa/usefulunpack](https://github.com/znso4pa/usefulunpack)

---

## 免责声明

本工具仅供对您拥有合法权利的文件使用。开发者（znso4pa）不对任何不当使用承担责任。
