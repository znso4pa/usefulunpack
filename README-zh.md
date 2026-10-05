# UsefulUnpack

[**中文**](README-zh.md) | [**English**](README.md) | [**繁體中文**](README-zh-TW.md) | [**日本語**](README-ja.md)

轻量级 Android 文件管理器 & 归档打包/解压工具

支持 **XP3**（吉里吉里）、**PFS**（Artemis）、**NSA/SAR**（NScripter）、**YPF**（YU-RIS）、**RGSS**（RPG Maker XP/VX/VX Ace）、**KSD**（吉里吉里2）、**ISO 9660** 光盘镜像，以及 **ZIP**、**7z**、**RAR**、**TAR**、**GZIP**、**BZIP2**、**XZ**、**ZSTD**、**LZMA**、**LZ4** 等通用格式（均支持打包与解压），Rust 原生核心。

---

## 功能

| 功能 | 说明 |
|------|------|
| ✂️ **XP3** | 解压 + 封包吉里吉里 `.xp3` |
| 📦 **PFS** | 解压 + 封包 Artemis `.pfs` / `.pf6` / `.pf8` |
| 📜 **NSA/SAR** | 解压 NScripter `.nsa` / `.sar` 封包（LZSS + SPB），**封包**（stored / LZSS） |
| 📦 **YPF** | 解压 YU-RIS `.ypf` 封包，三层自适应边界检测 |
| 🎮 **RGSS** | 解压 + 打包 RPG Maker 加密归档：`.rgssad`（XP）、`.rgss2a`（VX）、`.rgss3a`（VX Ace）；布局按包头自动识别，三种扩展名互相都能打开；文件名 UTF-8 / Shift-JIS；可编辑脚本；**封包产物默认命名为 `Game.<ext>`**（RPG Maker 只认这个名字），封包选项里可关 |
| 🎮 **RPG Maker MV/MZ** | 解码 + 回封逐文件混淆素材（回封需自填密钥）：`.rpgmvp` 图片、`.rpgmvo` 音频、`.rpgmvm` 视频（以及 MZ 的 `.png_` / `.ogg_` / `.m4a_`）；16 字节文件头可从文件自身还原，**无需密钥**（文件没有扩展名时也认得出来，靠内容判断类型）；回封密钥接受 32 位十六进制（原样使用）或任意文本（按 RPG Maker 的规则做 MD5）；**每个格式只收自己的类型：`.rpgmvp` 收 `.png`、`.rpgmvo` 收 `.ogg`、`.rpgmvm` 收 `.m4a`**，其余一律拦住，因为引擎是按扩展名选解码器的；产物按真实扩展名命名，可直接进图片 / 音频预览；批量模式一次解码整个文件夹 |
| 💾 **KSD** | 解压/打包 `.ksd`（mode 0/1/2 解扰 + UTF-16→UTF-8） |
| 🗜️ **ZIP** | 浏览和提取标准 ZIP 压缩包，支持压缩、加密；**包内编辑**（替换/删除/新增条目，**未触及加密条目保留 AES**，保存为 `原名-cn.zip` 副本不破坏原归档）；**PKWARE 多盘分卷**（`.z01/.z02/.zip`，支持**跨盘条目**） |
| 📦 **7z** | 浏览和提取 7-Zip 压缩包，支持压缩、加密；**包内编辑**（编辑脚本后重压为 `原名-cn.7z` 副本） |
| 🗜️ **RAR** | 解压 RAR 封包（RAR4/5），支持密码；**头加密（-hp）归档**弹密码框后可用密码预览/解压；**>64MB 成员流式解码**（字节级进度、内存有界，100~500MB 成员不再整块缓冲）；**非 solid 归档选中解压/预览随机访问**（末尾 txt 秒开，跳过前面所有成员）；**Huffman 查表解码加速**（~30%） |
| ⚡ **LZ4** | 打包/解包 LZ4 帧压缩文件 |
| 🗜️ **TAR** | 打包/解包 `.tar`、`.tar.gz`、`.tgz`、`.tar.bz2`、`.tbz2`、`.tar.xz`、`.txz`、`.tar.zst` |
| 🗜️ **GZIP** | 打包/解包 `.gz` |
| 🗜️ **BZIP2** | 打包/解包 `.bz2` |
| 🗜️ **XZ** | 打包/解包 `.xz` |
| 🗜️ **ZSTD** | 打包/解包 `.zst` |
| 🗜️ **LZMA** | 打包/解包 `.lzma` |
| 💿 **ISO 9660** | 浏览和提取 ISO 光盘镜像，**封包**（Level 1）；**CSO↔ISO 双向转换**（PSP CISO） |
| 🔍 **归档预览** | 树形预览归档内容，可折叠/展开，复选框选择性解压 |
| 📊 **预览统计** | 实时文件总数/总大小 + 已选统计 |
| 🔎 **全局搜索** | 文件名搜索 + 内容搜索（支持 30+ 文本格式），结果高亮导航，继续扫描 |
| 📦 **归档内搜索** | 预览归档时一键解包并搜索内部文件，复刻全局搜索的完整功能 |
| 🖼️ **文件预览** | 图片（JPG/PNG/**GIF/WebP 动图**/BMP）、音频、视频、文本/代码（md/rtf/yaml/vtt/csv/xhtml/vsq/ksc…）直接预览，搜索结果自动跳转匹配行；**Markdown/RTF 富文本渲染**（渲染/纯文本切换）；大文本有界读取不 OOM，预览框更大 + **可拖滚动条** |
| 📂 **本地预览** | 浏览器中直接点击可预览文件 |
| 🗂 **文件浏览器** | 类 ZArchiver 界面，路径面包屑，文件夹 ⭐ 星标 |
| 🪟 **多窗口 Tab** | 最多 3 个独立窗口（ViewPager2 滑动切换，可新建/关闭），每窗口各自记住路径、选中文件、多选状态、批量栏与粘贴/移动状态，互不串扰；**长按标签可重命名窗口**；Chrome 式顶置 Tab 栏（活动标签圆角高亮） |
| 📦 **内嵌归档预览** | FAB 预览归档直接渲染进当前窗口（非弹窗），**预览时可滑动切换窗口**；底部一键解压全部/提取选中/合并到（无可合并目标时按钮置灰，不会点出死路），顶栏搜索 + **⋮ 溢出菜单**（编辑/ZIP 管理/ISO 转换，按格式显隐，不支持时 toast 提示）；预览中进入全局搜索后关闭搜索回到预览 |
| 🔀 **跨归档合并提取** | 预览勾选条目 → 「合并到…」→ 目标可选**本文件夹，或其他窗口正打开的归档**（分组列表，标注所属窗口）→ 把源的选中条目与目标解包到同一个暂存目录（同名路径由源覆盖），再封包成 `目标-cn.ext` 副本放在目标旁边。**目标全程只读、原归档一个字节不动**，所以合并进别人正预览着的归档也是安全的；没有可合并目标时按钮置灰 |
| 📍 **选择路径/文件方式** | 一般设置可选「直接打开路径界面」或「在新窗口中打开选择」——后者开新窗口浏览选择，选中后关闭该窗口返回原窗口（适用于搜索范围/提取目标/ZIP 加条目/压缩目标） |
| 📌 **书签** | 文件夹星标 + 侧滑抽屉 |
| 🏠 **根目录** | 一键回到 `/storage/emulated/0` |
| 🗜️ **通用压缩** | ZIP/7z + gzip/bzip2/xz/zstd/lzma/lz4（单文件）+ tar（文件夹 5 变体）+ xp3/pfs/nsa/iso/ksd，5 级压缩程度，AES-256（ZIP） |
| 📚 **分卷支持** | 解压 `.7z.001` / `.zip.001` / `.rar` 分卷；zip/7z 压缩支持按大小切分卷（兼容 7-Zip） |
| 🎚️ **自定义分卷大小** | 分卷大小自定义（MB/GB，1MB~2GB） |
| 🔐 **解压密码** | 加密的 ZIP/7z/RAR 先弹密码框再解压；批量解压只需输入一次密码，全部归档复用 |
| 🔒 **密码标记** | 文件浏览器和预览列表中，需要密码的归档显示锁图标 |
| 📊 **双层进度条** | 顶部=全量进度，底部=当前文件进度（解压+压缩）；大（>64MB）RAR 成员流式解码，顶条字节级平滑推进而非每文件跳段 |
| 📋 **分组格式选择器** | 可滚动分组格式选择（通用压缩 / 单文件 / 其他），解压/批量/压缩共用 |
| 📄 **单文件压缩按钮** | 压缩模式下点击任意文件，右下角弹出压缩按钮 |
| 📦 **批量压缩选择器** | 批量合并/分别压缩都弹格式选择器，合并排除单文件格式 |
| ✂️ **文件操作** | 长按重命名、移动、删除（不可恢复）、新建文件夹 |
| ☑️ **多选批量** | 进入多选模式后批量解压/压缩/删除/移动 |
| 📂 **批量预览** | 多选归档统一查看内容并勾选解压 |
| 🛡️ **防连点** | 800ms 冷却 |
| 🌙 **深色主题** | 护眼暗色 |
| 🔬 **签名扫描** | Rust scan-core 引擎：**32 种签名 / 83 个魔数模式**任意偏移检测（含 **tar** `ustar` 与 **ISO 9660**），逐格式头部验证（真实大小 + 文件数），**gzip/xz/lzma 解压 dry-run 降误报**，**头加密 RAR5 兜底**，AhoCorasick 多模式匹配，流式扫描（整文件不进内存），一键解压或切割（dd）原始片段——夹在其它文件中间的归档也能找出来 |
| 🖥️ **内置终端（CLI）** | 全屏终端 + `uu` 命令集——`uu fmt / help / info / l / cat / hash / grep / cp / mv / rn / rm / mkdir / tree / du / stat / x / c / set / scan / cso / enc / mvdec / rmd / add / find / diff / hex / img / fd / b64`；支持管道（`| grep/head/tail/wc/sort`）、`> 文件` 重定向、`&&` / `;` 串联，以及 `uu run <file.uut>` 的 **UUT 脚本**（变量、`$1`/`$argc` 脚本参数、`$(...)` 捕获输出、行内与块式 `if`/`else`、`for a in *.zip ... end`、`for i in 1..5` 计数循环、`while` + `break`、整数算术（`set n = $n + 1`）、数值比较（`if n > 3`）、`if exist <路径>` 文件测试、`$?`（上一条退出码）与 `return [退出码]`；只允许 `uu`/`ls`/`cd`/`pwd`/`help`/`echo`，脚本永远不会变成 shell）。`am start --es uut <路径>` 可无界面跑脚本（供自动化：结果写进终端会话，同时落一份 `<脚本>.log`）。解压/封包的进度**只写在终端里**（约 30 字符 ASCII 条 + 字节数/当前文件名/条目计数，不弹任何对话框），完成给 all set 提示。解压可按通配符挑条目（`uu x game.xp3 "*.png"`），`uu l -j` 输出原始条目 JSON（`n/s/d/e`）给脚本用；扫描命中注册为 `fN` 字节区间描述符：`uu scan video.mp4` 后 `uu l f3` 直接列内嵌 ZIP，无需先落盘；内建 `ls / pwd / cd`（cd 导航终端扎根的窗口），其余命令走系统 shell。按 tab 扎根：切走隐藏、切回会话还在 |
| 🪟 **预览工作区** | 归档预览 ⋮ →「在窗口中打开」：包内容实体化成普通浏览窗口，多选/复制/移动/重命名/分享/文件信息全部白拿；套娃归档天然递归（工作区里还能再开工作区）；大包（>200MB）先确认；关闭时可选清理缓存；缓存仍在时会话恢复直接还原 |
| 🔤 **文本编码** | 全局文本编码设置（UTF-8 / SHIFT-JIS / GBK / UTF-16）严格应用于所有文本预览与内容搜索；UTF-8/UTF-16 自动去 BOM 且 **按 BOM 自动探测**（UTF-16 脚本开箱即显）；预览/编辑器内置编码切换行，切完立即重渲染；乱码提示 + 严格 UTF-8 校验兜住"合法但错"的交叉误读 |
| ✏️ **归档文本编辑** | 在 XP3/PFS 归档里改脚本/文本：解包 → 选脚本（`.ks`/`.tjs`/`.csv`…）→ 显式编码 + 原 BOM 保真地编辑 → 重新打包成 `原名-cn.xp3/pfs`，应用内完整闭环 |
| 🎨 **图片编辑器** | 图片预览 **⋮** 菜单进入：水彩/荧光笔（色板 + 透明度 + 粗细 —— 每条笔画**记住自己画时的透明度**，所以调低不透明度不会把已画的笔迹重新着色）、**矩形裁剪**（拖框 → 再点「裁剪」确认）、**拉伸到 1:1 / 4:3 / 3:4 / 16:9 / 9:16**、**像素取色**（按住拖动自动采样，点击读数复制 HEX/RGB）、**双指缩放（1–8×）+ 平移**、撤销/还原。另存 `原名-edit.png` 副本——不覆盖原图（~2048px 工作上限，旋转安全自动保存 + 恢复提示） |
| 🔄 **图片格式转换** | 图片预览 **⋮** 菜单：静态 **JPG/PNG/WebP 互转**，以及**动图 GIF/WebP → 静态首帧**（JPG/PNG/WebP）。另存副本，不覆盖原图 |
| 📤 **分享** | 长按任意文件 → **分享**：经 FileProvider + `ACTION_SEND` 交给其他应用——无需联网权限、无需改 manifest |
| 💾 **恢复上次会话** | 设置 → **其余设置** →「恢复上次会话」：启动时重开上次的窗口/目录**以及打开着的归档预览**（最多 3 个 tab，超出弹 toast）。回收站设置也归入其余设置 |
| 🎨 **UI 全面重构** | 统一设计令牌（圆角/间距/字号/行高）、所有预览的 **⋮ 溢出菜单**、平板/横屏对话框限宽、**宽屏主从布局**（≥600dp 文件列表在左 + 窗口内预览在右） |
| 🖱️ **可拖滚动** | 长列表（归档预览/扫描结果/脚本列表/浏览器/搜索）有常显可拖快滑把手；文本预览/编辑器有自定义可拖滚动条（荣耀/EMUI 兼容） |
| 🔄 **自动刷新** | 后台监听当前目录，文件列表自动同步——重命名/移动/删除/解压/压缩及外部改动（adb push、USB）无需退出重进 |
| ✂️ **精确切割** | 签名扫描的切割/解压按验证出的归档大小（zip/rar/7z/zstd/lz4/iso）精确切取——夹在其它文件中间的归档（如 `mp4 + zip + mp4`）能干净提出，不带尾部数据，解压成功 |
| 📲 **APK 安装** | 点 APK 调系统安装器（FileProvider + PackageInstaller 兜底）；安装前可选保留副本（部分系统安装器装完会删除安装包） |
| 🦀 **Rust 核心** | 每种格式独立 `.so`，互不干扰（20 种格式，含签名扫描） |
| 🔒 **最小权限** | 仅存储权限 |

## 多窗口常见问题（FAQ）

**1. 三个窗口同时解压，会不会 OOM？**

当前版本**三个窗口不会真正同时解压**：解压/压缩由全局单锁（`OperationLock`）串行化，第 2 个解压会被拒绝并提示「操作进行中」。因此内存压力与单窗口解压一致，OOM 概率极低。真正的内存来源是**单个归档内部的并行解码**（RAR 非 solid ≤4 线程、ZIP 条目级并行 ≤32MiB batch），大成员（>64MB）已流式解码不整块缓冲。注意「并行解压线程」是全局设置而非按窗口区分——单窗口用 4/8 线程时即占满内存预算。

**2. 多窗口同时打开同一个文件，改动会同步到其他窗口吗？**

每窗口有独立的目录监听（FileObserver）。**两个窗口在同一个目录时**（如都在 `/Download`），任一窗口解压/改名/删除产生目录事件，两个监听各自收到并各自刷新——**即刻同步**。两个窗口在不同目录则互不干扰。

同一归档（含分卷，如 `name.zip.001/.zip` 视为一体）**不会同时在两个窗口打开**：内置同归档互斥锁（OpenArchiveRegistry），第二个窗口尝试打开时会提示「该归档已在『窗口 N』打开」并自动跳到那个窗口。归档预览/编辑期间占用该锁，关闭对话框或关闭窗口即释放。

**3. 预览归档时能切换窗口吗？**

能。归档预览**内嵌在当前窗口**（不是弹窗），ViewPager 全程可滑动——预览归档的同时可以左右滑动切换窗口，每个窗口的预览/勾选状态互不干扰。预览中进入全局搜索后，搜索框关闭会自动回到预览。

**4. 选择路径/文件时能开新窗口吗？**

能。一般设置 →「选择路径/文件方式」可切换：默认是**直接打开路径界面**（弹窗），也可以设为**在新窗口中打开选择**——点选择（如搜索范围/提取目标/ZIP 加条目/压缩目标）会开一个新窗口浏览，选中后自动关闭该窗口并返回原窗口（原窗口状态保留），按返回键则取消选择。

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
         libarchive_rgss_core.so → RGSS (XP/VX/VX Ace) + MV/MZ 散素材
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
| **cxdec（XP3 内容过滤解密）** | [Cxdec_Tools](https://github.com/1F1E33-float32/Cxdec_Tools)（vendored 解密核心 + XP3 解析，MIT），游戏参数表参考 [arc_unpacker](https://github.com/vn-tools/arc_unpacker) | MIT |
| **PFS / PF6 / PF8** | [pf8 crate](https://crates.io/crates/pf8) | 见 crates.io |
| **NSA / SAR** | [NSA 格式规范](https://orin.page/w/index.php?title=NSA), LZSS/SPB via [GARbro](https://github.com/morkt/GARbro) / [ONScripter](https://github.com/nscripter/nscripter) | 公开规范 / MIT / GPL |
| **YPF** | [YU-RIS 格式解析参考](https://github.com/mwzzhang/python-YU-RIS-package-file-unpacker) (Kaitai), [GARbro](https://github.com/morkt/GARbro) SwapTable, XOR + Shift-JIS, zlib | 公开规范 / MIT |
| **RPG Maker MV/MZ 素材** | 格式对照 [Petschko's RPG-Maker-MV-Decrypter](https://gitlab.com/Petschko/RPG-Maker-MV-Decrypter) 与 [rpgm-asset-decrypter-lib](https://github.com/RPG-Maker-Translation-Tools/rpgm-asset-decrypter-lib)（MIT）；密钥流从文件自身头部还原，无需 MD5 或 `System.json` 侧车文件 | 公开规范 / MIT |
| **RGSS（RPG Maker）** | 布局对照 [uuksu/RPGMakerDecrypter](https://github.com/uuksu/RPGMakerDecrypter)（MIT）、[mkxp-z `crypto/rgssad.cpp`](https://github.com/mkxp-z/mkxp-z)（BSD-3-Clause）、[rpgm-archive-decrypter-lib](https://github.com/RPG-Maker-Translation-Tools/rpgm-archive-decrypter-lib)（Apache-2.0/MIT）；crates.io 上无同类 crate，解析器为自研 | 公开规范 / MIT / BSD-3-Clause / Apache-2.0 |
| **ISO 9660** | [isomage crate](https://crates.io/crates/isomage) | MIT |
| **ZIP** | [zip crate](https://crates.io/crates/zip) | MIT |
| **7z** | [sevenz-rust crate](https://crates.io/crates/sevenz-rust) | MIT / Apache-2.0 |
| **RAR** | [rars crate](https://crates.io/crates/rars)（vendored fork，流式过滤器） | MIT / Apache-2.0 |
| **LZ4** | [lz4_flex crate](https://crates.io/crates/lz4_flex) | MIT |
| **GZIP** | [flate2 crate](https://crates.io/crates/flate2)（Rust 后端） | MIT / Apache-2.0 |
| **MD5** | 自研（约 60 行，见 TODO） | RFC 1321 | MV `encryptionKey` → 密钥流（格式要求，非安全用途） |
| **BZIP2** | [oxiarc-bzip2 crate](https://crates.io/crates/oxiarc-bzip2) | Apache-2.0 |
| **XZ / LZMA** | [xz2 crate](https://crates.io/crates/xz2)（liblzma，`.xz`）+ [lzma-sys](https://crates.io/crates/lzma-sys)（`.lzma`） | 公有领域 / 0BSD |
| **KSD** | [krkr-save-tools](https://github.com/Luv-Ray/krkr-save-tools)、[KirikiriTools](https://github.com/arcusmaximus/KirikiriTools) | MIT |
| **ZSTD** | [ruzstd crate](https://crates.io/crates/ruzstd)（解压）/ [oxiarc-zstd crate](https://crates.io/crates/oxiarc-zstd)（压缩） | MIT / Apache-2.0 |
| **TAR** | [tar crate](https://crates.io/crates/tar) | MIT / Apache-2.0 |
| **签名扫描** | 魔数定义与验证思路参考 [binwalk](https://github.com/ReFirmLabs/binwalk) | MIT |

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
