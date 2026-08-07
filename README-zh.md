# UsefulUnpack

[**中文**](README-zh.md) | [**English**](README.md) | [**繁體中文**](README-zh-TW.md) | [**日本語**](README-ja.md)

轻量级 Android 文件管理器 & **视觉小说游戏资源解包工具**

支持 **XP3**（吉里吉里）、**PFS**（Artemis）、**NSA/SAR**（NScripter）、**YPF**（YU-RIS）、**ISO 9660** 光盘镜像，以及 **ZIP**、**7z**、**RAR**、**TAR**、**GZIP**、**BZIP2**、**XZ**、**ZSTD**、**LZMA**、**LZ4** 等通用格式（均支持打包与解压），Rust 原生核心。

---

## 功能

| 功能 | 说明 |
|------|------|
| ✂️ **XP3** | 解压吉里吉里 `.xp3` 封包 |
| 📦 **PFS** | 解压 Artemis `.pfs` / `.pf6` / `.pf8` 封包 |
| 📜 **NSA/SAR** | 解压 NScripter `.nsa` / `.sar` 封包（LZSS + SPB） |
| 📦 **YPF** | 解压 YU-RIS `.ypf` 封包，三层自适应边界检测 |
| 🗜️ **ZIP** | 浏览和提取标准 ZIP 压缩包，支持压缩、加密 |
| 📦 **7z** | 浏览和提取 7-Zip 压缩包，支持压缩、加密 |
| 🗜️ **RAR** | 解压 RAR 封包（RAR4/5），支持密码 |
| ⚡ **LZ4** | 打包/解包 LZ4 帧压缩文件 |
| 🗜️ **TAR** | 打包/解包 `.tar`、`.tar.gz`、`.tgz`、`.tar.bz2`、`.tbz2`、`.tar.xz`、`.txz`、`.tar.zst` |
| 🗜️ **GZIP** | 打包/解包 `.gz` |
| 🗜️ **BZIP2** | 打包/解包 `.bz2` |
| 🗜️ **XZ** | 打包/解包 `.xz` |
| 🗜️ **ZSTD** | 打包/解包 `.zst` |
| 🗜️ **LZMA** | 打包/解包 `.lzma` |
| 💿 **ISO 9660** | 浏览和提取 ISO 光盘镜像 |
| 🔍 **归档预览** | 树形预览归档内容，可折叠/展开，复选框选择性解压 |
| 📊 **预览统计** | 实时文件总数/总大小 + 已选统计 |
| 🔎 **全局搜索** | 文件名搜索 + 内容搜索（支持 30+ 文本格式），结果高亮导航，继续扫描 |
| 📦 **归档内搜索** | 预览归档时一键解包并搜索内部文件，复刻全局搜索的完整功能 |
| 🖼️ **文件预览** | 图片、音频、视频、文本/代码直接预览，搜索结果自动跳转匹配行 |
| 📂 **本地预览** | 浏览器中直接点击可预览文件 |
| 🗂 **文件浏览器** | 类 ZArchiver 界面，路径面包屑，文件夹 ⭐ 星标 |
| 📌 **书签** | 文件夹星标 + 侧滑抽屉 |
| 🏠 **根目录** | 一键回到 `/storage/emulated/0` |
| 🗜️ **通用压缩** | ZIP/7z + gzip/bzip2/xz/zstd/lzma/lz4（单文件）+ tar（文件夹 5 变体），5 级压缩程度，AES-256（ZIP） |
| 🔐 **解压密码** | 加密的 ZIP/7z 支持输入密码解压 |
| 📊 **双层进度条** | 顶部=全量进度，底部=当前文件进度（解压+压缩） |
| 📋 **分组格式选择器** | 可滚动分组格式选择（Galgame / 通用压缩），解压/批量/压缩共用 |
| ✂️ **文件操作** | 长按重命名、移动、删除（不可恢复）、新建文件夹 |
| ☑️ **多选批量** | 进入多选模式后批量解压/压缩/删除/移动 |
| 📂 **批量预览** | 多选归档统一查看内容并勾选解压 |
| 🛡️ **防连点** | 800ms 冷却 |
| 🌙 **深色主题** | 护眼暗色 |
| 🦀 **Rust 核心** | 每种格式独立 `.so`，互不干扰（15 种格式） |
| 🔒 **最小权限** | 仅存储权限 |

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

最低 Android 8.0（API 26）。

## 从源码构建

```bash
bash build.sh
```

每个格式独立编译为 `.so`，通过 Cargo workspace 管理，Gradle 打包 APK。

## 架构 (v4.0+)

```
用户操作 → Kotlin UI → 格式专属 JNI
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
                  ↓
          文件写入目标目录
```

各格式独立在 `crates/<format>-core/`，公共工具在 `crates/common/`。

### YPF 三层防线

YPF 文件名经 XOR 混淆 + Shift-JIS 编码。解析器逐层处理：

1. **GARbro SwapTable** — 配对字节查表转换 marker→长度
2. **固定 Kaitai 映射表** — 表中无匹配时回退
3. **自适应边界检测** — 扫描 `file_type`（0–6）+ `compressed`（0–1）字节对，自动重新对齐

XOR 密钥（0xFF / 0xC9）按文件首条目自动判断。

## 源码来源与致谢

| 格式 | 来源 / 参考 | 协议 |
|------|-----------|------|
| **XP3** | [xp3 crate](https://crates.io/crates/xp3) | MIT / Apache-2.0 |
| **PFS / PF6 / PF8** | [pf8 crate](https://crates.io/crates/pf8) | 见 crates.io |
| **NSA / SAR** | [NSA 格式规范](https://orin.page/w/index.php?title=NSA), LZSS/SPB via [GARbro](https://github.com/morkt/GARbro) / [ONScripter](https://github.com/nscripter/nscripter) | 公开规范 / MIT / GPL |
| **YPF** | [YU-RIS 解包工具](https://github.com/mwzzhang/python-YU-RIS-package-file-unpacker) (Kaitai), [GARbro](https://github.com/morkt/GARbro) SwapTable, XOR + Shift-JIS, zlib | 公开规范 / MIT |
| **ISO 9660** | [isomage crate](https://crates.io/crates/isomage) | MIT |
| **ZIP** | [zip crate](https://crates.io/crates/zip) | MIT |
| **7z** | [sevenz-rust crate](https://crates.io/crates/sevenz-rust) | MIT / Apache-2.0 |
| **RAR** | [rars crate](https://crates.io/crates/rars)（vendored fork，流式过滤器） | MIT / Apache-2.0 |
| **LZ4** | [lz4_flex crate](https://crates.io/crates/lz4_flex) | MIT |
| **GZIP** | [flate2 crate](https://crates.io/crates/flate2)（Rust 后端） | MIT / Apache-2.0 |
| **BZIP2** | [oxiarc-bzip2 crate](https://crates.io/crates/oxiarc-bzip2) | Apache-2.0 |
| **XZ / LZMA** | [lzma-rs crate](https://crates.io/crates/lzma-rs) | MIT |
| **ZSTD** | [ruzstd crate](https://crates.io/crates/ruzstd)（解压）/ [oxiarc-zstd crate](https://crates.io/crates/oxiarc-zstd)（压缩） | MIT / Apache-2.0 |
| **TAR** | [tar crate](https://crates.io/crates/tar) | MIT / Apache-2.0 |

## 许可证

本项目：**MIT License** — 详见 [LICENSE](LICENSE)。

所有第三方依赖保留各自协议。

## 作者

**znso4pa（锌帕）**

GitHub：[github.com/znso4pa/usefulunpack](https://github.com/znso4pa/usefulunpack)

---

## 免责声明

本工具仅用于**管理和访问您合法拥有的文件**。
- 不包含、不提供、不绕过任何数字版权管理（DRM）或复制保护机制
- 所有格式解析均基于公开的格式规范或开源参考实现
- YPF 格式使用的 XOR 键值是 YU-RIS 引擎公开格式规范的一部分，并非逆向工程所得的秘密密钥
- 请勿将本工具用于未经授权的内容提取或分发
- 开发者不对任何非法或不当使用承担责任
