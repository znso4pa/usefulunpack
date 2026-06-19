# UsefulUnpack

[**中文**](README-zh.md) | [**English**](README.md)

轻量级 Android 文件管理器 & **视觉小说游戏资源解包工具**

支持 **XP3**（吉里吉里/Kirikiri 引擎）和 **PFS**（Artemis 引擎）封包格式，Rust 原生核心，性能强劲。

---

## 功能

| 功能 | 说明 |
|------|------|
| ✂️ **XP3 解包** | 解压吉里吉里 `.xp3` 封包（基于 `xp3` crate） |
| 📦 **PFS 解包** | 解压 Artemis `.pfs` / `.pf6` / `.pf8` 封包（基于 `pf8` crate） |
| 📂 **文件浏览器** | 类 ZArchiver 双栏界面，路径面包屑、快速滚动 |
| 📌 **书签** | 收藏常用目录，快速跳转 |
| 💻 **内置终端** | 支持 `ls`/`cd`/`pwd` 及 shell 命令透传 |
| 🌙 **深色主题** | 护眼暗色主题，配色参照 ZArchiver |
| 🔒 **最小权限** | 仅请求文件存储权限 |
| 🦀 **Rust 核心** | JNI 调用原生 `.so`，解压更快更稳 |

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
# 1. 编译原生 .so
ANDROID_NDK_HOME=/path/to/ndk cargo ndk --target aarch64-linux-android --platform 26 build --release

# 2. 复制到 jniLibs
cp target/aarch64-linux-android/release/libarchive_core.so app/src/main/jniLibs/arm64-v8a/

# 3. 构建 APK
ANDROID_HOME=/path/to/sdk ./gradlew assembleRelease

# 输出：app/build/outputs/apk/release/app-release.apk
```

或一键构建：
```bash
bash build.sh
```

## 架构

```
用户操作 → Kotlin UI → JNI 桥
                  ↓
         libarchive_core.so (Rust)
           ├── xp3 crate → XP3 解包
           └── pf8 crate → PFS 解包
                  ↓
          文件写入目标目录
```

| 层 | 技术 |
|----|------|
| UI | Kotlin + AndroidX + Material Design |
| 桥接 | JNI（Java Native Interface） |
| 核心 | Rust（`xp3` v0.4, `pf8` v0.1） |
| 文件 API | `std::fs::File` + `SyncIo` + `oneshot_async` |

### CLI 工具

项目还附带命令行工具 `upk`，可直接在终端中使用：

```bash
upk info <file>       # 查看封包信息
upk list <file>       # 列出封包内文件
upk x <file> <dir>    # 解压到目录
```

## 免责声明

本工具仅供对您拥有合法权利的文件使用。开发者（znso4pa）不对任何不当使用承担责任。

## 许可证

MIT License — 详见 [LICENSE](LICENSE)。

第三方依赖：
- `xp3` crate — MIT / Apache-2.0
- `pf8` crate — 见 [crates.io/pf8](https://crates.io/crates/pf8)
- `jni` crate — MIT / Apache-2.0

## 作者

**znso4pa（锌帕）**
