# UsefulUnpack

[**中文**](README-zh.md) | [**English**](README.md)

轻量级 Android 文件管理器 & **视觉小说游戏资源解包工具**

支持 **XP3**（吉里吉里）、**PFS**（Artemis）、**NSA/SAR**（NScripter）、**YPF**（YU-RIS）和 **ISO 9660** 光盘镜像，Rust 原生核心。

---

## 功能

| 功能 | 说明 |
|------|------|
| ✂️ **XP3** | 解压吉里吉里 `.xp3` 封包 |
| 📦 **PFS** | 解压 Artemis `.pfs` / `.pf6` / `.pf8` 封包 |
| 📜 **NSA/SAR** | 解压 NScripter `.nsa` / `.sar` 封包（含 zlib 压缩） |
| 📦 **YPF** | 解压 YU-RIS `.ypf` 封包，三层自适应边界检测 |
| 💿 **ISO 9660** | 浏览和提取 ISO 光盘镜像 |
| 🔍 **归档预览** | 树形预览归档内容，可折叠/展开，复选框选择性解压 |
| 📊 **预览统计** | 实时文件总数/总大小 + 已选统计 |
| 🖼️ **文件预览** | 图片、音频、视频、文本/代码直接预览 |
| 📂 **本地预览** | 浏览器中直接点击可预览文件 |
| 🗂 **文件浏览器** | 类 ZArchiver 界面，路径面包屑，文件夹 ⭐ 星标 |
| 📌 **书签** | 文件夹星标 + 侧滑抽屉 |
| 🏠 **根目录** | 一键回到 `/storage/emulated/0` |
| 🛡️ **防连点** | 800ms 冷却 |
| 🌙 **深色主题** | 护眼暗色 |
| 🦀 **Rust 核心** | 每种格式独立 `.so`，互不干扰 |
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
| **PFS / PF8** | [pf8 crate](https://crates.io/crates/pf8) | 见 crates.io |
| **NSA / SAR** | [NSA 格式规范](https://orin.page/w/index.php?title=NSA) | 公开规范 |
| … NSA zlib | [flate2 crate](https://crates.io/crates/flate2) | MIT / Apache-2.0 |
| **YPF** | [python-YU-RIS-unpacker](https://github.com/mwzzhang/python-YU-RIS-package-file-unpacker) (Kaitai) | 公开规范 |
| … YPF SwapTable | [GARbro](https://github.com/morkt/GARbro) (ArcYPF.cs) | MIT |
| … YPF 文件名 | XOR + Shift-JIS / [encoding_rs](https://crates.io/crates/encoding_rs) | 见 crates.io |
| … YPF zlib | [flate2 crate](https://crates.io/crates/flate2) | MIT / Apache-2.0 |
| **ISO 9660** | [isomage crate](https://crates.io/crates/isomage) | MIT |

## 许可证

本项目：**MIT License** — 详见 [LICENSE](LICENSE)。

所有第三方依赖保留各自协议。

## 作者

**znso4pa（锌帕）**

GitHub：[github.com/znso4pa/usefulunpack](https://github.com/znso4pa/usefulunpack)

---

## 免责声明

本工具仅供对您拥有合法权利的文件使用。开发者不对不当使用承担责任。
