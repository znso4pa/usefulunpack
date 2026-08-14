# TODO

## v5.9.0 (密码锁 + UI重构：交互流程 / 国际化 / 视觉)

### 已完成

- [x] **主文件列表密码锁 🔒** — `FileAdapter` 加 `passwordProtected` 集合渲染锁；`nav()` 后台线程对 zip/7z/rar（含分卷）调 `isPasswordProtected` 检测并 `notifyDataSetChanged`；`FileUtils.isPasswordProtected`（try/catch，稳健）
- [x] **预览条目锁回归 zip/7z** — zip 列表用 `by_index_raw().encrypted()` 发射真实 `"e"`；7z 按文件夹 AES coder 检测发射真实 `"e"`（此前自 v4.2.0 起硬编码 false）
- [x] **批量一次密码复用** — `batchDirectExtract`/批量预览点文件/批量选解压：预先检测→`promptPasswordSync` 弹一次→应用到所有归档；`extractByFormat` 加 `password` 参数；新增同步密码框 `promptPasswordSync`（CountDownLatch）
- [x] **预览/解压密码提示加固** — `previewFileEntry` 用稳健 `isPasswordProtected`；`extractAll` 密码归档先弹框再解压（不再"直接密码错误"）；`previewArchive` 打开即弹密码框并透传给条目预览，新增 `szListEntriesWithPassword`（头加密 7z 可列目录）
- [x] **选中+密码解压只解选中** — `extractSelected` zip/7z/rar 分支重写（预检弹框 + 失败重试框，不再直接 toast）；zip/7z 新增 4 个 selected+password JNI 组合（单文件/分卷），dispatch 优先路由选中+密码（此前密码非空忽略选中走全量解压）
- [x] **自定义分卷大小** — 设置分卷行：不分卷/1MB/100MB/1GB/**自定义**（MB/GB 切换，1~2048 校验）+ "最小 1MB" 提示；密码行标注"仅 zip/7z 生效"；卷名正则 `\d{2,3}`→`\d{2,}` 支持 >999 卷
- [x] **交互流程优化（B）** — 点归档→FAB→**直接预览**：新增 `formatOfName`/`detectFormat` 扩展名自动识别（`.tar.gz/.tar.bz2/.tar.xz/.tar.zst` 先查 endsWith 防误判成 gz，`.tgz/.tbz2/.txz/.tzst`→tar，`.pf6/.pf8`→pfs，`.sar`→nsa），跳过格式选择器+"预览/解压"选择框两层；预览对话框新增 **"解压全部"** Neutral 按钮一键解到同名去重文件夹；`extractAll` 加 `initialPwd` 复用预览已输密码不再二次弹框；未知扩展名/分卷无法识别时兜底走原 `extract()` 格式选择器
- [x] **国际化补漏（A）** — ~50 处硬编码中文/emoji 迁入 4 语言 `strings.xml`（含免责声明正文、终端对话框"内置命令"/命令列表、文件比较"大小/时间"、背景设置"更换/选择图片/清除/透明度"、搜索范围/结果统计、解压结果统计、预览统计条、书签项等）；复用已有 `msg_calculating`/`action_confirm`
- [x] **视觉现代化（C）** — 10 个 vector drawable（`ic_drawer/ic_home/ic_back/ic_add_folder/ic_search/ic_extract/ic_star_on/ic_star_off/ic_folder/ic_file/ic_archive`）替换全部 15 处 `android.R.drawable.*`；`FORMAT_COLORS` 按格式给文件/预览图标着色（zip蓝/7z紫/rar红/xp3橙/pfs红/nsa紫/iso青/ypf品红/lz4青绿，通用格式灰）；`formatOfName` 作为格式映射单一来源（`detectFormat` 委托）
- [x] 4 语言新增 `extract_all`（解压全部/Extract all/すべて解凍）等字符串
- [x] 版本号 5.9.0 — `versionCode 20` / `versionName "5.9.0"`

### 真机回归修复（v5.9.0）

- [x] **分卷 7z + 密码预览失败** — `sevenz-core` 新增 `list_7z_volumes_with_password` + JNI `szListEntriesVolumesWithPassword`（分卷列表此前从不带密码，头加密 `-mhe=on` 分卷必挂）；`previewArchive` 分卷分支按 `pwd` 透传；`.so` 三架构重编
- [x] **预览失败无兜底（改错扩展名不可恢复）** — 自动识别失败时不再仅 toast，弹"选择格式"对话框走原 `extract()` 手动选格式路径
- [x] **无扩展名归档不弹 FAB** — 新增 `detectFormatByMagic`（魔数嗅探 7z/zip/rar/gz/bz2/xz/zst/lz4/xp3），`select()` 归档分支与 FAB 识别纳入魔数；`game.xp3` 改名成 `game` 仍可进预览

### 设置/搜索 UI 重构（v5.9.0）

- [x] **压缩设置重构** — 全程序化 ~300 行 → XML `dialog_compress_settings.xml` + 复用行 `item_settings_row.xml`；ZIP/7z/通用等级、分卷大小、ZIP 编码改**点击行弹单选对话框**（右侧显示当前值，不再 5 个等宽按钮挤一行）；工作模式/密码改 `SwitchCompat`（密码框 + eye 切换，未启用隐藏）；保存模型统一为"确定"一次性写入（含工作模式，生效后刷新标题）
- [x] **全局搜索重构** — 全程序化 → XML `dialog_global_search.xml` + `item_search_result.xml`（图标按格式着色）；"换目录"按钮修复文案（原错用"✂️ Move"）→ **GUI 目录选择器**（`ui/FolderPicker.kt` 全屏对话框，目录下钻/上一级/选择此目录）；搜索改 IME 搜索键触发（原独立大按钮）；新增清除按钮
- [x] **UI 设置修复** — 背景状态无图时显示 "Store only" 的错文案 → 新增 `bg_not_set`（未设置）
- [x] 新增 strings ×4：`action_change`/`parent_dir`/`pick_this_dir`/`bg_not_set`；新 drawable：`ic_chevron_right`/`ic_eye`/`ic_clear`/`bg_input`

### MainActivity 拆分（v5.9.0）

- [x] **MainActivity 2066 → 362 行** — 成员 `private`→`internal`（单模块内安全）+ 函数搬为顶层 `internal fun MainActivity.xxx()` 扩展；`MATCH`/`WRAP` 迁 `util/Constants.kt`，`searchSource*` 迁 `search/GlobalSearch.kt`，companion 删除
- [x] 新文件（同包，按域分目录）：`bookmarks/Bookmarks.kt`、`ui/FileInfo.kt`、`ui/AppSettings.kt`（压缩/常规/UI 设置）、`browse/FileBrowser.kt`（nav/select/extract）、`browse/MultiSelect.kt`、`batch/BatchCompress.kt` + `BatchExtract.kt`（含 `showBatchPreview_all` 迁出）、`extract/ExtractAll.kt` + `PreviewFlow.kt`、`search/GlobalSearch.kt`
- [x] 每阶段编译验证；函数签名不变，调用点零改动

### Bug 审计批量修复（v5.9.0）

- [x] **全局搜索显式搜索按钮** — `dialog_global_search.xml` 输入行加搜索按钮（IME 回车键保留）
- [x] **批量预览"解压选中"补 OperationLock** — try/finally 包裹，防与其它操作并发写 Rust 进度静态量/CANCEL
- [x] **进度框先于锁启动（9 处）** — ArchiveExtractor×3 / MainActivity×5 / CompressionDialogs×1：`tryStartOperation` 提前到 `prog.start()` 之前，忙时直接 toast，不再空转对话框 + 操作被静默丢弃
- [x] **7z 选中解压 total 假成功** — `sevenz-core` `extract_7z`/`extract_7z_selected_with_password` 的 total 由 `a.files.len()` 改**选中非目录条目数**；空/无匹配选中 `total=0 → ok=false` 不再报"解压完成但什么都没写"；`.so` 三架构重编
- [x] **归档内容搜索透传密码** — 单归档预览搜索传已捕获 `pwd`、批量搜索走 `resolveBatchPwd`，密码归档内容搜索不再静默 0 命中
- [x] **friendlyExtractError 匹配驼峰** — Rust 错误是 `PasswordRequired`/`MaybeBadPassword`/`ChecksumVerificationFailed`（Debug 无空格）→ 补驼峰 token；移除裸 `checksum`（防真实 CRC/损坏误报密码错误）
- [x] **nav() 清多选** — 跨目录自动 `exitMultiSelect()`，防批量删除误删不可见目录的文件
- [x] **手动格式兜底被 extract() 拒绝** — `extract()` 守卫纳入 `detectFormatByMagic`，无扩展名/改错扩展名可进手动格式选择器
- [x] **预览父目录过滤失效** — 3 处 `!endsWith("/")`（原生 trim 后无斜杠恒真）→ 前缀判断 `none { it.startsWith(p + "/") }`，取消子项后父目录不再整解
- [x] **搜索结果并发 CME + interrupted 清位** — `results`/`seenFiles` 改同步集合 + `toList()` 加锁；`Thread.interrupted()`→`isInterrupted`（不清中断位，旧线程能真正停下）
- [x] **批量预览头加密 7z** — `batchPreview` 列出时弹密码，分卷用 `szListEntriesVolumesWithPassword`、**单文件**用 `szListEntriesWithPassword`（原单文件分支漏密码）
- [x] **resolveBatchPwd 取消缓存** — 加 `batchPwdCancelled`，取消返回 null 且不再后续弹框；三个调用点处理 null；批量"解压选中"取消 toast"已取消"
- [x] **nav() 密码检测竞态** — adapter 后台线程创建 + UI apply 前 `listFiles.adapter === adapter` 比对，慢扫描不染色已切换的新目录
- [x] **低优先 4 项** — `ARCHIVE_EXTS` + `tzst`；`resolveZipVolumes` 选中末卷 `.zip` 解析整套（`.z01` 兄弟）；`resolveRarVolumes` 有 `.part1.rar` 时优先 partN 方案；分卷压缩取消/失败清理 `.001` 残留
- [x] **OperationLock 加锁/解锁跨线程死锁** — `release()` 的 `holder == currentThread` 判断永不命中（UI 线程 acquire、worker 线程 release）→ 锁永久 busy：密码重试、删除、预览搜索等 worker 侧操作被永久拒（"操作进行中" toast），且 UI 侧并发无保护。改为**无条件 release**（纯互斥），全 app 单操作模型下安全
- [x] **归档内搜索按需解压丢密码** — 预览时捕获的 `pwd` 只传给文本预解压，点击 0 字节占位结果时 `extractByFormat` 不带密码 → 密码归档按需解压必失败。新增 `searchSourcePassword` 全局随 `searchSource*` 一起设置/使用
- [x] **批量预览搜索源归档写死 all[0]** — 批量搜索把所有缓存路径映射到第一个归档（`searchSourceArchive = all[0].first`），其他归档的结果按需解压会取错源；且批量搜索无占位阶段、不同归档同名条目缓存互相覆盖。修复：每归档独立缓存子目录 + `searchSourceResolver: Map<relPath, SearchExtractSource(归档, 内路径, 输出目录, 密码)>` 精确反查；补占位文件阶段（文件名搜索覆盖非文本文件）；`globalSearch` 入口清空旧搜索状态（主菜单搜索不再误用残留）
- [x] **预览单文件触发全量解压** — `previewFileEntry` 密码路径调 `showPasswordDialog` 不带 `sel` → 为看一个文件解压整个归档；且 `showPasswordDialog` 内 7z 分支硬编码 `sel=""` 忽略参数。两处均改为传 `entry.path`
- [x] **scan-core 真实 bug 修复（字节序/位域，靠真实样本回归兜住）** —
  - **gzip MTIME 字节序**：应为 little-endian，误用 BE → 带 FNAME 的 gzip 全部拒识；修后系统 `gzip` 产物正常识别
  - **zstd 帧头位域**：dictID 掩码错位（`(fd>>1)&3` → `fd&0b11`）、block_type 移位、magic+fd 计入 header_len、RLE 块存储 1 字节；多块 zstd 现识别并报 size
  - **crc32 init 参数**：调用方传 `0xFFFFFFFF`（双重异或）应为 `0`（与 zlib 语义一致）→ 7z/xz 头 CRC 校验修复；此前测试自洽掩盖
  - **lz4 头校验字节**：HC 字节实际总是存在（lz4_flex 行为），不应按 FLG bit1 可选判断 → 系统 lz4 产物识别并报 size
  - **xp3 头部越界**：`u64le(&h,12)` 超出 16 字节数组 → panic；修正为 28 字节头 + 完整 8 字节魔数 `XP3\r\n \x1a\n`（此前扫描必崩 → "什么都扫不出"的直接原因）
  - **rar5 头偏移**：header_size 在 offset 12（非 9）；并实现 EOF marker 全归档大小 + 分卷 volume 标志

### 设置：免责声明 + 使用文档（v5.9.0）

- [x] **设置菜单 3→5 项** — 新增「使用文档」「免责声明」（`settings()`，AppSettings.kt）
- [x] **免责声明可重复查看** — `showDisclaimer(fromSettings)`：从设置进入时 `setCancelable(true)`、关闭只 dismiss 不退出；启动首弹照旧（未接受才弹）
- [x] **使用文档（应用内帮助）** — `ui/HelpDialog.kt`：滚动双节（支持的格式 16 条 / 功能教程 9 条），首行加粗；内容来自 `help_formats`/`help_tutorials` string-array ×4 语言
- [x] 格式文档含各引擎限制：ZIP 真分卷 `.z01` best-effort 未验证、7z 头加密列目录需密码、RAR 不支持压缩（版权）+ 头加密列目录受限 + 加密多小文件慢、NSA SPB 2GB 上限、LZMA 列表不显示解压大小、KSD 压缩仅 .txt

### APK 安装（v5.9.0）

- [x] **点开 APK 调系统安装** — `select()` 加 apk 分支：**APK 完全排除在归档/魔数识别外**（`.apk` 本质是 zip，魔数嗅探会误判为 ZIP），只走系统安装；`installApk()` 双路径全系统兼容：**主路径** `ACTION_VIEW` + `FileProvider` + `package-archive` MIME + **授权同时挂 intent 与 `clipData`**（EMUI/HarmonyOS 荣耀系只挂 intent 时安装器拿不到权限会静默失败）；**兜底** `PackageInstaller` 流式提交（`openWrite`+`use` 关闭即完成 + `commit`），不依赖 ROM 的 ACTION_VIEW 支持；`SecurityException` 引导开启「安装未知应用」（`REQUEST_INSTALL_PACKAGES` 权限已加）；失败弹可见错误框（不再静默 toast）
- [x] **安装前保留副本选择** — 部分系统安装器（荣耀/EMUI）安装成功后删除安装包 → 点击 APK 先弹对话框（说明 + 「直接安装」/「保留副本并安装」/取消）；「保留副本」先把 APK **复制到原文件同目录**（`uniqueFile` 去重 → `app (1).apk`，文件浏览器可见，安装器删除原文件不影响副本）再安装并 toast 备份位置
- [x] **兜底路径移出 UI 线程** — `installViaPackageInstaller` 的字节流拷贝（`copyTo` 1MB 块）原在 UI 线程 → 大 APK ANR；改为 worker 线程 + 不可取消 spinner，异常分支（SecurityException 引导 / 其他错误弹框）回 UI 线程处理
- [x] **APK 图标深蓝** — `FileAdapter` 中 `.apk` 用箱子图标 + 深蓝 `0xFF1976D2`（区分普通文件/归档）
- [x] 新增 strings ×4：`install_apk`/`err_install_apk`/`msg_installing`/`msg_install_unknown`/`title_install_failed`/`install_warn_delete`/`install_now`/`install_keep_copy`/`install_backup_done`

### 格式扫描（Rust scan-core 大库，v5.9.0）

- [x] **长按文件菜单新增「扫描」** — 位于"文件信息"下方（`action_scan`）；文件夹提示不可扫描
- [x] **Rust scan-core 扫描引擎** — 新增 `crates/scan-core`（独立 `.so`，轻量 ~550KB，零解压依赖）：**AhoCorasick 多模式匹配**（`aho-corasick` crate）+ 逐格式头部验证 + **size 跳过**（验证通过的命中跳过整个区间，不再扫内部）+ **后处理去重**（排序 → 同偏移高置信度胜出 → 移除落在已识别区间内的命中 → size 未知的延伸到下一命中或 EOF）；1MB 流式分块 + overlap（跨块魔数正确匹配），**整文件不进内存**（大文件不 OOM）；字节级进度 + 取消
- [x] **21 种签名 / 67 个魔数模式** — 7z/zip/rar4/rar5/gzip/bzip2/xz/zstd/lz4/lzma/xp3/PNG/JPEG/GIF/TIFF×2/PDF/ELF/RIFF/MPEG/ISO9660（lzma 为 4 props × 9 dict 前缀 = 36 条，覆盖 64K–512M 全部 dict 档）
- [x] **逐格式头部验证（对齐 binwalk 公开实现的 parser 思路）** — 每个格式读头部字段验证、可算 size 的报 size：
  - zip：local header + EOCD 解析 + 中央目录校验（报 file count）
  - rar4/5：头 CRC/大小 + **EOF marker 定位全归档大小**（rar5 `1D 77 56 51 03 05 04 00`、rar4 `C4 3D 7B 00 40 07 00`，从文件尾部 64MB 反向搜，5.3GB RAR 4ms 秒出）；**分卷 volume 标志识别**（主头 HFL_VOLUME/MHD_VOLUME），非末卷无 EOF 时整卷作区间防内部误报
  - png：16 字节魔数（含 IHDR）+ chunk 链遍历到 IEND 算真实 size
  - jpeg：JFIF/EXIF/DQT 三变体魔数 + marker 链遍历到 EOI 算 size（含 SOS 特殊扫描）
  - gzip：FLG 保留位 + MTIME（LE）合理性 + OS 白名单 + FNAME/FCOMMENT 终止符
  - bzip2：**9 个完整 10 字节魔数** `BZh{1-9}1AY&SY`（随机数据误报归零）
  - 7z：头 CRC32 校验 + next header 偏移/大小
  - xz：stream flags CRC32 校验
  - zstd：帧头位域（RFC 8878 布局）+ 逐块遍历到位块算 size（RLE 块存储 1 字节）
  - lz4：FLG/BD 保留位 + 头校验字节（xxh32，始终存在）+ 块遍历到 end marker 算 size
  - lzma：props 白名单 + dict 13 值白名单（含 256K preset0）+ 解压尺寸合理性
  - rar4/5：RAR5 为 VINT 变长字段解析（HEAD_SIZE/HEAD_TYPE/HEAD_FLAGS 均非固定偏移）
  - 7z：头 CRC32 校验 + next header 偏移/大小；**超 EOF 时（截断/多卷首卷）报整个文件区间**（防卷内压缩流误报）
  - gif：GIF87a/GIF89a 双魔数 + 逻辑屏幕描述符（宽高/保留位/全局色表边界）
  - pdf：`%PDF-1.` 7 字节魔数 + 换行后 `%` 二进制标记
  - elf：class/endian/version + osabi 白名单 + 零 padding + e_type 校验
  - iso9660：`\x01CD001\x01\x00` 魔数在偏移 32768 + 主卷描述符（both-endian 一致性）算镜像大小
- [x] **真实世界样本回归测试** — 内联 macOS 系统工具生成的 gzip/bzip2/xz/zstd/lz4/lzma 样本（含带 FNAME 的 gzip、多块 zstd、带头校验的 lz4），防止自洽构造掩盖字节序/位域错误；共 12 测试全绿
- [x] **弹窗输出** — 等宽字体表格（文件名 / 分隔线 / DECIMAL·HEXADECIMAL·DESCRIPTION 表头 / 命中行 `偏移 0x偏移 描述` / 页脚"已分析 1 个文件，N 种签名，M 魔数模式，T 毫秒"）；zip 命中附 `file count + total size` 富文本；签名数/模式数动态（21/67）
- [x] **全文本本地化** — 表头/页脚/无结果/进行中/菜单项全部走 strings.xml ×4 语言，零硬编码；格式名为技术名词保持标准写法
- [x] **解压 / 切割（dd）双按钮** — 每条命中行两个按钮（`item_scan_hit.xml`）：「解压」仅归档命中可用（ZIP/浅 RAR 直接预览/解压，7z/单流格式或深 RAR/非零偏移 ZIP 先 `carveToFile` 切临时再 `extractAll`；ISO 命中直接走 `IsoCore` 预览（魔数在偏移 32768 但镜像从 0 开始）；失败提示具体原因：误报/过深/加密/需先分离；非归档命中点击提示改用「切割」）；「切割」对所有命中可用，切 `[offset..EOF]` 存独立文件，归档命中按格式后缀命名（新增 `ARCHIVE_EXT_FOR_LABEL`，非归档沿用 `EXT_FOR_LABEL`，WebP 切割为 `.webp` 可直接预览）；目标选择复用「📁 分离到（新文件夹）/分离到 当前目录」（uniqueFile 去重）；密码走现有弹框（解压码）
- [x] **dd/切割字节级进度条** — 切割 [offset..EOF] 与解压前临时分离均显示横向字节进度（总量 = 文件长 - 偏移，实时更新）
- [x] **扫描性能与内存修复** — 修复 O(n²) 主循环（无有效签名文件 42MB 从卡死降至 ~0.6s 秒扫完）、整文件读内存（改流式分块，GB 级文件不 OOM）、zip EOCD 搜索窗口 1GiB→256MB、RAR EOF 尾部反向搜索（5.3GB 74s→4ms）
- [x] 使用文档新增「格式扫描（binwalk）」教程 ×4（含分离说明）

### scan-core 真实语料回归（v5.9.0，files4testing v1.1.0 + rarfile 样本 + binwalk 3.1 对照）

- [x] **rar4 验证器 0x6152 误杀（严重）** — 原代码 `crc != 0x6152 → None` 是自造测试固定的常量，真实 RAR4 的 HEAD_CRC 任意（实测 rar3-solid=0xd03b/seektest=0x90cf）→ **所有真实 RAR4 全部误杀**。改为 `HEAD_TYPE==0x73` 判别（首个块必是 main header，RAR5 该字节是头 CRC 的一部分天然不冲突）+ 修 MHD_VOLUME 读偏移（11→10）
- [x] **rar5 验证器 VINT 布局错误（严重）** — 原按 u16le 固定偏移读 HEAD_SIZE，真实 RAR5 的 size/type/flags 全是 VINT 变长整数 → **所有真实 RAR5 全部误杀**（实测 hsize 读成 267 > 文件 169）。重写为 VINT 解析 + `HEAD_TYPE==1` 判别 + block 跨度 = 8+4+hsize
- [x] **7z 验证器偏移错误（必 panic）** — next_header_offset/size 读 20/28，正确是 12/20 → u64le 越界 **每个真实 7z 必 panic**（旧测试未覆盖 7z 验证器）。修正 + 补测试；顺带超 EOF（截断/多卷首卷）报整个文件区间
- [x] **zip EOCD 搜索三缺陷** — ① 64KB 块搜索无 overlap 携带，EOCD 魔数跨块边界即永久丢失（构造跨块 zip 复现）；② 前向搜索 256MiB 封顶 → **>256MB 的 zip 全误杀**（实测 280MB stored zip）；③ 修复后补文件尾 64KB+22 反向回退搜索（大 zip EOCD 总在归档末尾）
- [x] **zstd 单块帧误杀 + FCS 字段规则** — `MIN_BLOCK_COUNT=2` 拒绝真实单块帧（zstd CLI 小输入）；且 libzstd 对 fcs_flag=0 且非单段的帧（管道输入,内容大小未知）**不写 FCS 字段**，原 fcs_size 表把 1 字节 FCS 硬算进去 → 块游走错位。修表 + 接受单块帧
- [x] **lzma 魔数表只有 `5d 00 00 80` 一条** — dict 白名单再宽 AC 匹配器也不会对非 8MB dict 触发（preset0 256K/preset9 64M 全漏）。魔数扩展到 4 props × 9 dict 前缀 = 36 条（≥16MB 共用 `xx 00 00 00` 前缀，白名单细分）
- [x] **gzip OS 白名单过严 + FEXTRA 游走错位** — OS 放宽到 0..13+255；FEXTRA 只跳 XLEN 不跳 extra 数据 → FNAME 游走误入 extra 载荷（可能误判/漏判），修正为完整跳过
- [x] **Kotlin ISO 切割偏移** — 「切割」按钮对 ISO 命中从魔数偏移 32768 切出坏 ISO → `carveOffsetOf`：ISO 从 `offset-32768`（镜像起点）切
- [x] **语料回归 601 文件 0 FAIL** — files4testing v1.1.0 全量（normal/password/split/faults + 未进 manifest 的 tar 变体/分卷 7z）跑 scan_cli 断言预期；**对照 binwalk 3.1**：201 处语义差异全部为本库占优（binwalk 漏全部 zstd、漏截断 gz/xz/7z、对 brotli/-hp rar 内部产生 GPG/S-record/Zlib 误报洪水，本库零噪声）；唯一取舍：**-hp 头加密 RAR 不报**（无明文结构可验证，报则必伴随加密流误报洪水）
- [x] **真实样本回归** — rarfile 套件真实 RAR4×3/RAR5×1 全量内联固件 + 真实 7z（plain/mhe）/zstd 单块/单段/lzma preset0 构造；18 条单测全绿；修 tmp 同名竞态（fextra.gz 与 t.gz 冲突导致 workspace 并行偶发失败）

### 高危/中危审计批量修复（v5.9.0，16 项 + 2 意外发现）

- [x] **R1 rar 路径穿越回退（高危）** — `rar_writer` 的 `safe_join(...).unwrap_or_else(|_| out_base.join(name))` 对 `..` 条目回退到不安全拼接 → 改 fail+=1+sink（与 zip/nsa/7z/pfs 一致）；目录分支 `unwrap_or_default()` 一并收紧；新增回归测试：rars writer 构造 `../evil.txt` 条目 → 提取 total=1/fail=1、零落盘（rar13 writer 强制 ≥2 卷，测试用 2KB payload 分两卷走 `extract_rar_volumes_inner`）
- [x] **R2 ksd mode2 炸弹（高危）** — 只校验声明 `uncompressed_len`，`read_to_end` 实际输出无上限（声明 1KB 实际 GB 级膨胀）→ 解码包 `Read::take(声明大小)` 双重封顶，静默截断同 NSA/YPF；测试：声明 1KB 实际 1MiB → 解码缓冲 ==1024
- [x] **R3 nsa SPB 2GB→64MB** — u16 宽高可声明 ~12.8GB；真实 SPB 插图远小于 64MB；测试：65535×65535 报 "too large"，小尺寸过闸
- [x] **R4 ksd 整读预检** — `probe_ksd`/`extract_ksd` 的 `fs::read` 前加 `metadata.len() > 512MB` 拒绝（稀疏文件测试，513MB 零占用磁盘）
- [x] **R5 ypf 输出封顶（中危）** — `take(e.asize)` 只限输入，解压输出无界（磁盘耗尽）→ 解码器包 `take(e.usize)`；测试：声明 1KB 实际 1MiB → 输出 ==1024
- [x] **R6 nsa LZSS 尾部回引越界（中危）** — 内层 `0..=j+1`（j+2 字节）无 clamp 可越过 `out_len` → `min(j+2, remaining)`（初版写成 j+1 差一字节,回归测试当场抓住）；测试：8 字面量+越界回引 → 输出恰 == 声明大小
- [x] **R7 oneshot_async 轮询上限** — 100 万次 poll 后 panic（JNI `guarded` 转错误），杜绝永久忙等；仅 xp3 使用（同步 reader 恒 Ready）
- [x] **R8 取消语义修复（中危）** — `reset()` 不再清 CANCEL（预扫描期间按下取消必须存活到解压阶段）；新增 `clear_cancel()` 在**全部 55 个解压/压缩 JNI 入口**开头显式清（脚本插入 + 逐 crate 核对计数）；zip/sevenz/rar/iso 4 个预扫描循环（by_index_raw / files.filter / members / iso_walk）加 `cancelled()` 检查
- [x] **R9 sevenz flush/目录失败** — `let _ = create_dir_all`/`let _ = flush` 吞错，flush 失败（ENOSPC）留半成品 → 目录创建失败计 fail，flush 失败并入 copy 失败分支（删半成品 + fail+=1）
- [x] **⚠️ 意外发现：Interrupted 取消错误被 std 无限重试（高危）** — `ProgressWriter`/`ProgressReader`/`CancelReader`/`CancellableReader` 用 `ErrorKind::Interrupted` 报取消，而 std 的 `write_all`/`io::copy` 对 Interrupted **永久重试** → 取消落在拷贝中段 = 100% CPU 空转（主机 rustc 实验实证：io::copy 永不返回）。全部改 `ErrorKind::Other`（4 文件 6 处）+ 注释 + 回归测试 `cancelled_io_copy_aborts_instead_of_spinning`
- [x] **K1 promptPasswordSync 守卫（高危）** — show 前查 `isFinishing/isDestroyed` + try/catch BadTokenException + finally countDown + `await(30s)` 超时返回 null（坏窗口/卡 UI 不再永久阻塞 worker）
- [x] **K2 批量压缩复制移出 UI 线程（高危）** — 大目录 `copyRecursively` 在 UI 线程 ANR → 先拿锁（失败清理 tmpDir）→ worker 内复制 + finally 双清理 tmpDir
- [x] **K3 删除进度 worker 锁（高危）** — worker 侧改 `OperationLock.acquire()` 无 Toast（后台 Toast 无效），锁忙 dismiss pd + UI 线程 Toast
- [x] **K4 搜索线程守卫（中危）** — 完成回调加 `searchThread === t` 守卫（旧搜索不覆盖新结果）；统计线程捕获局部 `watched` 而非读可变 `searchThread` + UI 更新再守卫
- [x] **K5 重试锁序** — `prog2.start()` 先于锁（违反 9 处约定）→ 锁前置，忙时直接 return（对齐既有顺序）
- [x] **K6 预览重试 worker 锁** — 同 K3 模式：`OperationLock.acquire()` 无 Toast，失败静默（重试对话框保留，用户可再点）
- [x] **K7 占位文件路径净化（中危穿越）** — 预览/批量搜索的占位文件 `File(cacheDir, e.path)` 无净化 → 新增 `sanitizeEntryPath`（FileUtils，Rust safe_join 同款规则：拒 `..`/绝对/`:`/NUL）+ 批量路径 canonicalPath.startsWith(cache) 双重校验
- [x] **K8 视频播放 FileProvider（高危必崩）** — `Uri.fromFile` API 24+ 必抛 FileUriExposedException（min 26）→ 复用 APK 安装的 `$packageName.fileprovider` + FLAG_GRANT_READ_URI_PERMISSION
- [x] **仓库卫生** — `git rm extract.py`（零引用）；删 `opencode/crisp-wolf`/`opencode/sunny-circuit` 分支 + 2 个 opencode worktree（先 worktree remove 后 branch -d）
- [x] **验证** — workspace 23 套件全绿（新增 9 条回归：rar 穿越/ksd clamp×2/nsa clamp+SPB/ypf clamp/progress_io×2/cancel 语义）；全量 `./build.sh`（17 crate × 3 ABI + gradle）+ 强制 clean assembleRelease；APK 内 .so 为 strip 后产物（hash 与 jniLibs 不同属正常）

### 文本编码统一（v5.9.0）

- [x] **全局文本编码设置** — 新增 pref `text_encoding`（默认 UTF-8，4 选：UTF-8/SHIFT-JIS/GBK/UTF-16）；**严格按所选编码**解码（`CodingErrorAction.REPLACE`，非法字节替换不报错不静默丢弃）；UTF-8 剥 BOM；UTF-16 带 BOM 检测（FF FE→LE / FE FF→BE，无 BOM 默认 LE——galgame 惯例）
- [x] **统一解码函数** — FileUtils 新增 `decodeTextStrict(bytes, encoding)` + `textLooksGarbled`（U+FFFD ≥ 2% 且 ≥ 8 个判乱码）；`showTextPreview`（本地/归档内/搜索结果预览全汇入此）与 `GlobalSearch` 内容搜索（原硬编码 UTF-8）全部改走此函数
- [x] **乱码引导 toast** — 预览解码后检测到大量替换字符 → toast「检测到大量乱码：当前按 X 解码，可在 设置→文本编码 切换」（用户自改设置，不做预览内临时切换）
- [x] **一般设置改行式** — 语言 / 文本编码 / ZIP 文件名编码三行（原单语言单选升级）；每行显示当前值，点击弹单选
- [x] **ZIP 编码独立** — `zip_encoding` 设置行从压缩设置**移出**至一般设置（标注「仅解压」）；压缩侧 `zipSetEncoding` 调用删除（zip 压缩固定 UTF-8+flag 写入，现代生态全兼容，原本该调用无效）；`encoding_title`/`rowEncoding` 清理
- [x] 字符串 ×4 新增 5 条（encoding_utf16/settings_text_encoding/settings_zip_encoding/msg_encoding_applied/preview_encoding_hint）+ 修 settings_language 重复项
- [x] gradle assembleRelease 编译通过；APK 已更新
- [x] **主机真机替代验证** — 生成 7 种真实编码样本（SJIS/GBK/UTF-8±BOM/UTF-16LE±BOM/无BOM）+ JVM 逐行同款逻辑（`EncVerify.java`）：7/7 精确解码、默认 UTF-8 误读 SJIS/GBK 全触发乱码 toast、28 组合零异常；**已知限制**：CJK→CJK 交叉误读（SJIS↔GBK）输出合法但错误，FFFD 启发式无法识别（FFFD=0），任何工具同理

### D 系列审计（v5.9.0）

- [x] **D1 merged 批量压缩取消被吞** — 复制移入 worker 后无取消检查，`compressDispatch` 入口 `clear_cancel()` 抹掉标志 → 压缩照跑完却 toast「已取消」→ 复制循环 `if (cancelled) break` + 压缩前 `if (cancelled) false else compressDispatch`（对齐 separate 既有模式）
- [x] **D2 lz4 保留位检查位错** — `flg & 0b1` 是 DictID 标志（合法）而非保留位（bit1）→ 真实 lz4 `-D` 字典帧全被误杀、bit1 假帧被放行、dict_id 分支死代码 → 改 `flg & 0b10 != 0 → None`；测试：带 DictID+正确头校验的帧通过、仅 bit1 置位（校验码重算）拒绝
- [x] **D3 gzip 游走 guard 耗尽静默接受** — 64KB 无 NUL 后落出循环照常 `Some` → 加 `terminated` 标记，耗尽即 `None`；测试：70000 字节无 NUL 的 FNAME 字段拒绝
- [x] **D4 7z 加法回绕** — `32 + next + next_size`/`off + total` 无 checked_add，构造头可回绕出错误跳过区间 → `checked_add` 链 + `saturating_sub` 边界比较，溢出/超界统一走卷回退（size=file_len-off）；测试：next=u64::MAX 不 panic、回退分支
- [x] **D5 rar5 头缓冲过小** — 18 字节限 3 个 vint 总长 6 字节，超大 main header（多字节 vint）合法 RAR5 漏报 → 缓冲扩到 64（覆盖 3×10 字节 vint 上限 42）
- [x] **D6 深嵌大 zip（wontfix）** — 记入待处理（见上文）
- [x] **D7 TODO 过期条目** — 旧审计段 `oneshot_async 忙等` 改标已修（引用 v5.9.0 R7 的 1M 轮询上限）
- [x] **D8 预览重试锁忙静默** — `OperationLock.acquire()` 失败静默丢弃点击 → 补 `runOnUiThread { toast(msg_op_in_progress) }`
- [x] **D 系列验证** — workspace 23 套件全绿（scan-core 20 条含 5 条新回归）；files4testing 601 文件语料回归 0 FAIL；scan-core `.so` 三架构重编 + `assembleRelease` + APK 包内 .so hash 核对一致
- [x] **N1 内容搜索整读 OOM 隐患** — `decodeTextStrict(readBytes())` 整文件载入（原流式 forEachLine）；新增 `CONTENT_SEARCH_MAX = 50MB` 硬上限，即便「极端」极限(Long.MAX_VALUE)也跳过 >50MB 文本，杜绝大文件内容搜索 OOM
- [x] **E1 切割按 hit.size 定终点** — `carveToFile` 加 `length: Long?` 参数（null=EOF 回退给无 size 的 gzip/bz2/xz/lzma/xp3）；zip/rar/7z/zstd/lz4/iso 有精确 size → 切 `[offset, offset+size)`；主机端到端：`mp4+zip+mp4` 拼接 → scan 报 zip(offset/size/count)，新切割与原始 zip 逐字节一致；尾部 200KB(>64KB EOCD 窗口)时旧 EOF 切割必 "cannot find EOCD"，E1 修复成功提取

### 待处理

- [ ] **预览格式增加** — 预览支持更多文件格式（如 GIF/WebP、Markdown 渲染、字幕/歌词等）
- [ ] **进度静态量每操作局部上下文** — 若要真正并发再做
- [ ] **gzip/xz/lzma 解压验证** — scan-core 为保持零依赖只做头部校验；加密/高熵数据中 gzip 仍有 ~1/1000 概率误报（binwalk 用解压 dry-run 根治）。若引入 flate2/xz2/lzma-rs 可对齐到接近 0，但 `.so` 会增大
- [ ] **-hp 头加密 RAR 不报** — 无明文结构可验证；binwalk 报（靠魔数）但伴随加密流内部大量误报。若未来需要可加"仅魔数 + 全文件区间"兜底
- [ ] **深嵌大 zip（wontfix）** — EOCD 前向 256MiB 封顶 + 文件尾 64KB 回退覆盖「归档在文件尾」与「压缩体 ≤256MB」两类；深嵌宿主文件中间且压缩体 >256MB 的 zip 仍漏（binwalk 同病，代价不值的取舍）
- [ ] **tar 签名** — 语料中 binwalk 报 "POSIX tar archive"（ustar 魔数 + checksum 验证）；本库无 tar 魔数。可加 `ustar` 魔数 @257 + 头部 checksum 验证，但 GNU 格式 tar 仍漏（binwalk 同样漏）

---

## v5.8.1 (真机修复)

### 已完成

- [x] **ZIP 分卷解压 EOCD 失败** — 根因：`extractAll`/`extractSelected` 的 zip 分支直接调 `ZipCore.zipExtract(src.path)`（单文件 API），对分卷 zip 等于只读 `.001`，EOCD 在最后卷 → "cannot find EOCD"。改走 `zipExtractDispatch`（自动解析分卷）。单文件密码 zip 自解压正常、无密码分卷正常、密码分卷失败 —— 该组合首次暴露此路径
- [x] **7z/zip 分卷非首卷(.002/003)不被识别** — `isVolumeFile` 检查被点击文件自身的魔数，非首卷无魔数 → 判普通文件。改为解析卷集后对**首卷**查魔数（复用 resolve*）
- [x] **7z/zip 密码分卷进度 total=0B** —
  - 7z：压缩器设密码时 `set_encrypt_header(true)` → 头加密 → 无密码读头失败 → total 算 0。`extract_7z_with_password`/`extract_7z_volumes` 改带密码读头算 total；**密码须用 `Password::from`（UTF-16LE），否则 AES 解不了头**
  - zip：`by_index` 对 AES 条目无密码返回 PASSWORD_REQUIRED → 算 total 全跳过。进度/列目录/加密检测全部改用 **`by_index_raw`**（纯元数据，不需要密码）
- [x] **szNeedsPassword/szVolumesNeedsPassword 头加密归档** — 无密码读头失败不再抛错，密码类错误返回 `true`（需要密码）
- [x] **zip 密码分卷列目录/预览** — `list_zip_from` 用 `by_index_raw`，加密条目也能列全（之前返回空列表）
- [x] 回归测试：zip 4 组合写→读回（含 30MB/1MB×29 分卷）、7z 密码分卷 total>0、zip 密码 total>0

### 待处理

- [ ] **UI 重构** — 优化交互流程（分卷压缩入口已加在压缩设置，仍可考虑压缩流程内联入口）
- [ ] **进度静态量每操作局部上下文** — 若要真正并发再做

---

## v5.8.0

### 已完成

- [x] **7z 解压提速 ~7.7x（30 → ~232 MB/s）** — fork `sevenz-rust 0.6.1` 到 `crates/vendor/sevenz-rust`（`[patch.crates-io]`），`decoders.rs` 的 LZMA/LZMA2 解码改用 **`lzma-sys`（liblzma）** `lzma_raw_decoder` 流式解码：LZMA1 从 5 字节 props 解 lc/lp/pb+dict、LZMA2 用 dict_size；`lzma_raw_decoder_memusage` 保 `MAX_MEM_LIMIT` 语义；`Drop` 里 `lzma_end` 释放
  - 分卷路径自动受益（`ConcatReader` → 同一解码栈）；实测 p7zip LZMA2（mx=5/9，含加密 + 分卷）sha256 全匹配；修复 release-only SIGSEGV（过滤器 options 悬垂指针：原实现返回 tuple 使 `&mut opt` 指向已失效栈帧，改 `&mut` 参数让 opt 活在调用帧）
  - BCJ2 多流路径保留 lzma-rust 原实现；压缩侧仍 lzma-rust（未动）
- [x] **bzip2 解压提速 ~1.8x（40-52 → 73-88 MB/s）** — 评估后 `bzip2-core`/`tar-core` 由 oxiarc-bzip2 换 **`bzip2` crate（C libbz2，bzip2-sys 本已经 zip 3.0 进依赖树）**；解码取消改用 `CancelReader`（输入侧读粒度检查）；系统 `bzip2` 双向互操作测试
- [x] **ZIP 分卷解压** — 补 TODO 缺口：`zip-core` 加 `ConcatReader`（复用 7z 模式）+ 5 个 volume JNI（list/extract/selected/password/needsPassword）；`FileUtils` 加 `resolveZipVolumes`（`.zip.001` 字节分卷 + best-effort `.z01/.z02/.zip` 真分卷）+ `isVolumeFile` 按魔数区分 zip(PK)/7z；MainActivity/ArchiveExtractor/ZipCore.kt 路由接入（列/预览/全量/选择/密码/进度/取消，对齐 rar/7z）；实测 7-Zip 生成的分卷 zip 全通
- [x] **7z + ZIP 分卷压缩** — `szCompress`/`zipCompress` 加 `splitSize` 参数（7-Zip `-v` 语义：压缩后按字节切成 `.001/.002/...` 并删原文件）；`archive_common::split_volumes` 共享 helper；压缩设置对话框新增"分卷大小"选择行（不分卷/1MB/5MB/100MB/1GB，pref `compress_split_size`）；`compressDispatch` 透传；完成 toast 显示首个分卷名；4 语言新增 `split_title`/`split_none`
- [x] **版本号 5.8.0** — `versionCode 19` / `versionName "5.8.0"`
- [x] **CI/CD 搭建** — `.github/workflows/ci.yml`：ubuntu 上 `cargo test --workspace` + 两个 fork（sevenz-rust/rars）独立测试套件 + clippy（非致命）；fork 的 Cargo.toml 加空 `[workspace]` 表使其可独立 `cargo test --manifest-path`（598 + 20 用例全过）；新增 `archive_common::split_volumes` 边界单测（空文件/整除/余数/拼接还原/0=no-op）

### 测试

- `cargo test --workspace` 21 套件全绿；7z/bzip2/zip/tar release 模式通过
- 新增：7z 压缩分卷往返、zip 分卷 list/extract/selected、zip 压缩分卷往返、bzip2 系统互操作
- 实测：p7zip 真实分卷 7z（mx=5/9、加密、200MB 随机）解出 sha256 全匹配；p7zip 分卷 zip 解出 0 失败

### 性能（实测，16" M1）

| 项 | 前 | 后 |
|------|------|------|
| 7z 解压 | 30 MB/s | **~232 MB/s** |
| bzip2 解压 | 40-52 MB/s | **73-88 MB/s** |

### 待处理

- [ ] **UI 重构** — 优化交互流程
- [x] 完善单元测试与 CI/CD — 见 v5.8.0 CI/CD 搭建
- [x] **`lastExtractResult`/`lastExtractError` 全局跨线程（已修）** — 彻底移除两个进程级 `@Volatile` 全局，新增 `ExtractOutcome(counts, error)` 按操作显式传参：`extractByFormat` 返回 `ExtractOutcome`，`tryExtractWithPassword`/`showPasswordDialog` 回调改传 `ExtractOutcome`，`friendlyExtractError` 改收显式 error 参数；消除 worker→UI 间的共享可变状态
- [x] **进度静态量跨线程竞争（已修）** — 审计发现 5 处后台提取未持 `OperationLock`（批量预览点击、批量/单归档内容搜索提取、搜索结果按需解压、`extractSelected` zip/7z 分支）会与前台操作并发写同一格式 progress 静态量 → 全部补 `OperationLock`；每个 cdylib 独立静态副本（无跨格式污染），每次操作开头 `reset()`（含清 CANCEL）。若未来要真正并发，需把 progress 静态量改每操作局部上下文（涉及全部 crate，暂不做）
- [x] **RAR 加密大量小文件慢（已评估：不可优化）** — RAR5 每个文件自带独立 salt+kdf_count（fork 自带 writer 实测 6 文件 = 6 个不同 salt，且 rars 与真实 WinRAR 归档互操作验证），PBKDF2(2^15 HMAC-SHA256) 每文件必跑一次，是格式特性非实现问题；`sha2` 已用 SIMD；换官方 unrar 同样逐文件 KDF，无法改善
- [ ] **`.z01` 真分卷 best-effort** — 拼接依赖 zip crate 接受多盘 EOCD（`disk_number` 检查通常能过，但 `number_of_files_on_this_disk` 字段可能少计）；无 WinRAR 样本难以完全验证，已按 best-effort 实现

---

## v5.7.0

### 已完成

- [x] **XP3 封包** — `xp3-core` 新增 `xp3CreateArchive`：`XP3Writer` 流式打包（zlib 0-9，0=raw store），`ProgressReader` 字节进度 + 取消，迭代遍历；往返测试；压缩选择器归"其他格式"组
- [x] **PFS 封包** — `pfs-core` 新增 `pfsCreateArchive`：`Pf8Writer` 打包 PF8（XOR 加密），逐文件进度 + 取消；往返测试；归"其他格式"组
- [x] **KSD 解压 + 打包** — 新 crate `ksd-core`（Kirikiri2）：mode 0/1 解扰 + mode 2 deflate → UTF-16→UTF-8 `.txt`；txt→mode2(zlib) 打包；解压炸弹上限 512MB + 坏魔数拒绝；解压/压缩路由 + 双进度条
  - 流程优化：KSD 压缩仅接受 `.txt`（toast 提示 `msg_ksd_need_txt`），输出名替换扩展名（`save.txt`→`save.ksd`）；归档模式标签显示 "KSD"
- [x] **压缩模式单文件 FAB** — 压缩模式下点击非归档单文件 → 右下角弹 FAB → 选压缩格式（不再直接弹文件信息）
- [x] **批量压缩格式选择器** — "合并/分别压缩"都弹格式选择器；**合并**排除单文件格式（`MERGE_COMPRESS_GROUPS`）；分别压缩对 zip/7z/tar 单文件自动临时目录包裹；共用 `compressDispatch` 派发
- [x] **删除格式分组歧义标签** — 压缩/解压选择器统一改用"通用压缩格式 / 其他格式 / 单文件压缩"，移除旧分组标签（4 语言），避免歧义纠纷
- [x] **7z/zip CRC 损坏输出修复** — 损坏的 7z 归档被静默解出错误数据（系统 7z 报 Data Error）→ `sevenz-core`/`zip-core` 解压 copy 出错时删除损坏输出文件并计数
- [x] **稳定修复包** — 深递归迭代化：`iso_walk`/zip·sevenz `add_dir`/tar `collect_files`/rar·sevenz `walk_files`/`walkSearch` 全改显式栈（防 Android 1MB 线程栈溢出）；竞态修复：Kotlin `OperationLock` 单操作锁（9 个 worker 包裹，并发操作 toast `msg_op_in_progress`）+ `lastExtractResult`/`lastExtractError` `@Volatile`
- [x] **版本号 5.7.0** — `versionCode 18` / `versionName "5.7.0"`

### 语料库正确性测试（files4testing v1.1.0，423 向量 + 13 faults）

- 345 条正向向量（14 格式：gz/bz2/xz/lzma/zstd/lz4/zip/7z/rar/tar×5，含 password 63 + split 18）**sha256 全匹配**
- 13 条 faults 全部正确拒绝（截断/损坏/错密码/缺卷）
- 跳过 78 条（brotli/tar.brotli/tar.lzma/tar.lz4，app 不支持）

### 性能基准（criterion，组合文件 79.5MB 解压）

| 格式 | 吞吐 | 格式 | 吞吐 | 格式 | 吞吐 |
|------|------|------|------|------|------|
| tar | 674 MB/s | rar | 52 MB/s | 7z | 30 MB/s ⚠️ |
| lz4 | 460 MB/s | xz | 35 MB/s | lzma | 28 MB/s |
| zstd | 214 MB/s | tar.xz | 30 MB/s | bzip2 | 17 MB/s ⚠️ |
| gzip | 208 MB/s | | | tar.bzip2 | 10.6 MB/s ⚠️ |
| zip | 187 MB/s | | | | |

- RAR PBKDF2：加密每文件 +10.5ms（明文 46x）— 加密多小文件归档慢（格式特性）

### 待修复（性能/正确性发现）

- [x] **xz 压缩 level 无效 + 解压慢** — `lzma-rs` 的 `compress::Options` 无 level 参数（`_level` 被丢弃），且 `xz_compress` 走快速低质量路径（1800MB/s 假象）。**已修**：`xz-core`/`tar-core`(txz) 改用 **`xz2`（liblzma C，本已通过 zip 依赖进入 Android 交叉编译）**——`XzEncoder::new(w, preset 0-9)` 真级别、`XzDecoder` 流式解压（txz 顺带免临时文件 spool）；xz2 解码 21 条语料 xz 向量 sha256 全匹配，压缩产物系统 `xz -d` 可解
- [x] **lzma (.lzma) 压缩 level 无效 + 极慢** — **已修**：`lzma-core` 压缩改用 **`lzma-sys`（liblzma C FFI）** `lzma_alone_encoder` + 流式 `lzma_code`——preset 0-9 真级别（实测 l1→11.3MB / l6→8.7MB / l9→8.2MB，32MB 文本），速度 ~3x（lzma-rs 0.84→liblzma ~2.5-3 MB/s 级别 6-9，级别 1 达 29 MB/s），产物系统 `lzma -d` 可解；解码保持 lzma-rs（语料 15 向量已验）；注：liblzma alone 头写未知大小，列表预览不显示解压大小
- [x] **7z/bzip2 解压慢** — 已修（见 v5.8.0）：7z 换 liblzma（30→~232 MB/s）、bzip2 换 C libbz2（17→~80 MB/s）
- [x] 完善单元测试与 CI/CD — 见 v5.8.0 CI/CD 搭建

---

## 下版本计划

- [x] **RAR5 filtered 流式解压（根治）** — fork rars 0.4.7 加"过滤块流式"，替代原三选一待决策
  - Rust: `crates/vendor/rars`（`[patch.crates-io]` 指本地 fork）— 流式解码器遇过滤标记(符号 256)不再返回 `FilteredMember`，改为缓冲该过滤块(实测全归档 <64KB 全 Delta)，`apply_filters` 后再 emit；保留字节级进度 + mid-file 取消 + 纯 Rust 无许可证问题
  - 实测 6.8GB solid 加密 RAR 归档（含多个 >512MB filtered 成员）: 全部流式解出，哈希与官方 unrar 逐字节一致；完整归档 5.4 分钟解完无错误
  - 内存: 峰值 446MB（1MB 阈值下，macOS malloc arena 残留为主，Android scudo 会归还 → 实际活跃 ~35-50MB）
  - `rar-core`: `rar50_buffered_decode_limit` 降为 64MB，>64MB 成员全走流式
  - 测试: rars fork 598 单测通过（2 个 FilteredMember 断言测试改为验证流式输出正确），4 个依赖 fixture 的测试 `#[ignore]`（fixture 未入库）
- [x] **NSA/YPF/ISO/LZ4 流式化** — 整文件进内存 → 流式，大文件不 OOM
  - LZ4: `io::copy(FrameDecoder, ProgressWriter)` + 手动解析帧头 `content_size`(免整文件解压算大小) + `CancellableReader` 中途取消
  - YPF: `Take<File>` + `ZlibDecoder` 流式，不再 `asize`/`usize` 整块进内存
  - ISO: `cat_node` 直写 `ProgressWriter`(内部已 8MB 分块)
  - NSA: LZSS 逐符号写入 64KB 块流式落盘，stored 条目 `take()` 直流；SPB 保持整块(BMP 有 2GB 上限)
  - 新增各格式流式回归测试（含手工构造最小 ISO9660 / NSA 归档 / LZSS 编码器对称验证 / LZ4 帧头与 lz4 CLI 比对一致）
- [x] **通用格式对标 ZArchiver** — TAR / GZ / BZ2 / XZ / ZSTD / LZMA / LZ4 全部补齐（见 v5.6.0）；**Z 格式已被 tar/gz 系列淘汰，放弃**；CAB/ARJ/LZH 生态差视需求
- [x] **解压进度** — 每个 format 加进度条（JNI 返回值已是 JSON，轮询机制复用压缩的 static atomic 模式，后改为**字节百分比**，见 v5.5.0）
  - Rust: `archive_common::extract_progress` 静态量共享于 9 个 crate,每 crate 4 个 JNI getter (`*ExtractProgressCount/Total/Name/Cancel`)
  - Kotlin: `PollingProgressDialog` 共享 helper(压缩/解压复用),接入 extractAll / extractSelected / batchDirectExtract / tryExtractWithPassword / showPasswordDialog
  - 支持取消(9 格式),取消后清理空输出目录
- [x] **分卷支持** — RAR 原生多分卷 + 7z `.7z.001/.002` 字节分卷
  - Rust: `rar-core` 用 `extract_volumes_to` 原生解压多卷;`sevenz-core` 新增 `ConcatReader`(零拷贝 Read+Seek 拼接)+ 7z 魔数校验
  - Kotlin: `resolveRarVolumes`(partN + rNN 命名)/ `resolveSevenZVolumes`(按基名分组,修同目录混放多组时拼错卷),`isVolumeFile` 识别,选中显示"共 N 卷"
  - 完整对齐: 全量/预览/选择性/密码/进度/取消;ZIP 分卷因库不支持排除
- [x] **XP3 封包** — XP3 格式的打包/压缩功能（见 v5.7.0）
- [x] **PFS 封包** — PFS/PF6/PF8 格式的打包/压缩功能（见 v5.7.0）
- [ ] **UI 重构** — 优化交互流程
- [x] 完善单元测试与 CI/CD — 见 v5.8.0 CI/CD 搭建

---

## 已解决: RAR5 filtered 大成员

**原问题**: `rars` 对 RAR5 **filtered** 成员(实测最大的一个 786MB)必须**整块缓冲**解码,超过库默认 **512MB** 上限即拒绝。
- 官方 unrar / 7z 实测该成员 786,317,647 字节(整包解压总量 6.8GB)
- 真机上表现为"解压到一半突然密码错误"——旧版 app 把密码重试后的**任何失败**都标成 `err_pwd_wrong`(误导,**已修**: `lastExtractError` + `friendlyExtractError()` 透传真实错误,含 `err_large_member` 提示)

**已解决(2026-08, 采用方案 D)**: fork rars 0.4.7 到 `crates/vendor/rars` 实现**过滤块流式**——
- 原理: RAR5 过滤器(DELTA/E8/E8E9/ARM)作用在有界区间,流式解码器遇过滤标记时只缓冲该过滤块(实测全 <64KB),`apply_filters` 后再 emit,内存有界
- 收益: 保留字节级进度 + mid-file 取消 + 纯 Rust + 无 RARLAB 许可证问题 + JNI 面不变
- 代价: fork 需随上游跟进(当前 0.4.7 基线);RAR5 VM 自定义字节码过滤器 rars 连缓冲路径都不支持(实测目标归档全为 Delta,未见 VM,低风险)
- 完整回归: 6.8GB solid 加密归档全量解压 OK,最大成员哈希与官方 unrar 逐字节一致

---

## Debug 审计(全 crate 扫查)

### 已修(本轮)

- [x] **sevenz 分卷空卷列表 `paths[0]` panic** — `list_7z_volumes`/`extract_7z_volumes`/`sz_volumes_needs_password` 直接下标 `paths[0]` 未查空;且 list/needs_password 的 JNI 入口**未包 `guarded`**,panic 会跨 JNI 边界崩进程 → 已加 `if paths.is_empty() { return Err("empty volume list") }`
- [x] **NSA SPB/LZSS 巨大分配 OOM** — SPB 用 width/height(u16)算 `total_size`,损坏条目可声明 **~12.8GB** 直接 `vec!` → abort;LZSS 可长到 4GB → 已加 **2GB 上限**,超限报错而非崩溃
- [x] **zip/7z 压缩统计 `file_type().unwrap()`** — 坏符号链接会 panic → 已改 `if let Ok(t) = ... else continue`
- [x] **NSA/YPF/ISO/LZ4 整文件进内存(OOM 主因)** — nsa 解出 `raw`(可到 4GB)、ypf 读 `asize` 整段+zlib 整段、iso `cat_node` 整文件、lz4 压缩+解压双份 → 全部改流式(见"下版本计划"NSA/YPF/ISO/LZ4 流式化)
- [x] **common `FNAME.lock().unwrap()` mutex 中毒** — 任一持锁线程 panic 会毒化后续所有 `extract_progress`/`compress_progress` 调用 → 改 `lock().unwrap_or_else(|e| e.into_inner())`
- [x] **gzip 多成员拼接解压** — `gzip` CLI 可产出多段拼接的 `.gz`,原 `GzDecoder` 只解第一段 → 改 `MultiGzDecoder`
- [x] **tar `.tzst`/`.tar.zst` 解压失效** — `tar_fmt`/`tar_reader` 未处理 tzst,`.tar.zst` 被当普通 tar 读(解出乱码/报错) → 补 tzst 分支,用 oxiarc-zstd `ZstdStreamDecoder` 包装
- [x] **tar symlink/hardlink 条目安全** — 原本非目录条目一律当文件写(空文件/潜在跟随) → 改为 `entry_type.is_file()` 才提取,symlink/hardlink/other 一律跳过
- [x] **新增通用格式自包含单测** — gzip(往返/ISIZE/多成员/空/截断)/bzip2(往返/空/截断)/xz/lzma/zstd(往返/空/截断)/tar(5变体往返/路径穿越/符号链接跳过/空 tar)/lz4(压缩往返),共 14 个用例
- [x] **tar 解压进度 total 未重置** — 其他格式都 `extract_progress::reset(总和)`,唯独 tar 漏了 → 改为两遍:先收集条目算总和再 reset,再解压
- [x] **tar 压缩长路径中止整个归档** — `set_path` 超 100 字符报错直接 `?` 中断 → 改为跳过并计数(fail),不毁掉其余文件
- [x] **`oneshot_async` 忙等烧 CPU** — `spin_loop()` 若 future 永 Pending 会 100% CPU 死循环 → 改 `std::thread::yield_now()`
- [x] **压缩失败残留半成品文件** — 仅取消时删输出 → 失败时也删除
- [x] **进度静态量测试竞态** — tar 的 progress-reset 断言与并发测试互踩共享 progress 静态量(偶发 flaky) → 测试模块加 `Mutex` 串行化

### 安全审计（全 crate 扫描）

- [x] **JNI 解析入口 panic 全覆盖** — 修复前 ypf(0)/xp3/pfs/iso/nsa 的 list 入口、sevenz 的 list/needsPassword/volumes、rar 的 list/needsPassword/volumes、6 个通用格式的 list 均未包 guarded → panic 跨 JNI 边界崩进程。现**全部 15 个 crate 的解析入口**（extract/list/needsPassword/volumes）都包 `guarded`/`guard_panic`
- [x] **NSA csize 解压炸弹** — 恶意 NSA 头可声明 csize 到 4GB(u32)，`vec![0u8; csize]` 直接 OOM abort → 加双校验：csize ≤ 2GB 且 `data_start+offset+csize ≤ 文件长度`，超限报错
- [x] **路径穿越（zip-slip）** — 全部多条目格式（xp3/pfs/nsa/iso/ypf/zip/7z/rar/tar）用 `safe_join`（拒 `..`/绝对路径/`:`）；tar 有 `../evil` 手搓 raw tar 回归测试
- [x] **tar 符号链接/hardlink** — 只提取 `entry_type.is_file()`，链接条目一律跳过（不创建不跟随）
- [x] **解压炸弹尺寸上限** — NSA LZSS/SPB 2GB 上限；YPF/XP3/ZIP/7z/RAR/通用格式全部流式输出不整块缓冲
- [x] **测试竞态** — tar 进度静态量断言加 `Mutex` 串行化（消除 flaky）
- 新增 NSA 炸弹回归测试，`cargo test --workspace` **45 通过 0 失败**

### 待处理(按优先级)

- [ ] **进度静态量跨线程竞争** — `extract_progress`/`compress_progress` + `CANCEL` 全局,若两个解压/压缩并发会互相覆盖;app 当前单操作模型(低风险),可考虑每操作局部上下文
- [ ] **`lastExtractResult`/`lastExtractError` 全局跨线程** — 同上,worker 线程写、UI 线程读;单操作串行下安全,多操作并发有竞态
- [x] **RAR 加密大量小文件慢（已评估：不可优化）** — 见 v5.8.0（RAR5 每文件独立 salt，KDF 每文件必跑，格式特性）
- [x] **`oneshot_async` 忙等（已修）** — 原 `spin_loop()`/`yield_now()` 永久轮询 Pending；已加 100 万次轮询上限，超限 panic 由 JNI `guarded` 转错误（见 v5.9.0 R7）
- [ ] **深递归** — `iso_walk`/压缩 `add_dir`/`deleteWithProgress` 对极深目录可能栈溢出;实际目录深度有限,低风险

---

## v5.6.0

### 功能更新

- **通用压缩格式（全纯 Rust，每格式独立 crate + 独立 .so）** — 对标 ZArchiver 覆盖
  - 解压: **gzip** `crates/gzip-core`（flate2 rust 后端，ISIZE 进度）/ **bzip2** `crates/bzip2-core`（oxiarc-bzip2 块式 API 适配流式）/ **xz** `crates/xz-core`（lzma-rs）/ **zstd** `crates/zstd-core`（ruzstd）/ **lzma** `crates/lzma-core`（lzma-rs）/ **tar** `crates/tar-core`（tar crate，支持 `.tar/.tgz/.tar.gz/.tbz2/.tar.bz2/.txz/.tar.xz`）
  - 压缩: 单文件 → `.gz/.bz2/.xz/.zst/.lzma/.lz4`；文件夹 → `.tar/.tar.gz/.tar.bz2/.tar.xz/.tar.zst`（5 变体，`tar::Builder`+`append_data(ProgressReader)` 流式；txz 临时文件转 xz）
  - 压缩实现: gzip `GzEncoder` / bzip2 `BzEncoder` 适配器 / xz·lzma `lzma-rs` 推式 / zstd `oxiarc-zstd ZstdStreamEncoder`（新依赖）/ lz4 `lz4_flex FrameEncoder`；全部 `compress_progress` 流式 + 取消 + 双条
  - 互操性: 全部输出可用系统 `gzip/bzip2/xz/zstd/lz4` CLI 与 `tar` 解出（已验证字节一致）
  - Kotlin: 7 个 `Core.kt` 压缩声明 + `compressAccessors` + 压缩选择器分组（zip/7z + 5 tar 变体 + 6 单文件，去掉死的 xp3/pfs）+ 压缩设置"通用格式"等级行（`generic_level` pref，复用 zip 5 级）
  - 不提供 **RAR 压缩**（RARLAB 官方版权限制）
- **格式选择器改分组可滚动** — 新增 `showFormatPicker`(ui/FormatPicker.kt)：ScrollView 内分组表头，一行一格式点即选；解压（单个+批量）与压缩选择器共用，解决格式一多窗口太大
- **双层进度条** — 顶部=全量进度，底部=当前文件进度（解压+压缩都支持）
  - Rust: `progress_store!` 新增 `FILE_BYTES`/`FILE_TOTAL` + `set_file(total)`，`add_bytes` 同时喂全局与当前文件计数；9 个格式解压循环成员开始时 `set_file(成员大小)`（rar 用 name→size 映射传 writer），zip/sevenz 压缩循环同样接入
  - JNI: 每格式新增 `*ProgressFileCount/FileTotal` getter（9×2 解压 + 2×2 压缩）
  - Kotlin: `PollingProgressDialog` 改为自定义 `Dialog` + 新布局 `dialog_progress_dual.xml`；`ProgressAccessors` 加 `getFileCount/getFileTotal`
  - 底部条显隐: 当前文件 ≥1MB 显示字节百分比;==0(未知大小) 转圈;0<大小<1MB 隐藏防闪烁(阈值 `PROGRESS_FILE_BAR_MIN` in Constants.kt)

### 大文件不 OOM（根治）

- **RAR5 filtered 流式解压** — fork rars 0.4.7 到 `crates/vendor/rars`(`[patch.crates-io]`)，流式解码器支持过滤块缓冲。6.8GB solid 加密归档全量解压 OK，哈希与官方 unrar 一致，多个 >512MB filtered 成员全部解出
- **NSA/YPF/ISO/LZ4 流式化** — 全部改为流式写出，整文件不再进内存：
  - LZ4: `copy(FrameDecoder)` + 帧头 `content_size` 解析 + 中途取消
  - YPF: `Take+ZlibDecoder` 流式
  - ISO: `cat_node` 直写 `ProgressWriter`
  - NSA: LZSS 64KB 块流式 + stored 直流
- **rar-core 阈值 64MB** — 超过的成员全走流式，活跃内存有界(~35-50MB)

### 测试

- rars fork 598 单测通过（2 个 FilteredMember 断言改为验证流式输出）
- 新增 NSA/YPF/ISO/LZ4 流式回归测试（手工构造最小 ISO9660 / NSA / LZSS 编码器 / LZ4 帧头比对）
- workspace 全量 `cargo check` + `cargo test` 通过

---

## v5.5.0

### 功能更新

- **解压/压缩进度改为字节百分比** — 所有 9 个解压格式 + ZIP/7z 压缩统一按字节算百分比
  - Rust: `extract_progress`/`compress_progress` 静态量改 `AtomicU64`,新增 `ProgressWriter`/`ProgressReader` 包装 IO 逐块累计字节
  - 平滑流式: zip/7z/rar/xp3(包 ProgressWriter)+ pfs(原生 `extract_file_with_progress` handler)+ 压缩(ProgressReader 包源文件);逐文件跳变: nsa/iso/ypf/lz4(整文件进内存)
  - 进度 JNI getter `jint → jlong`(支持 >2GB 归档),Kotlin 对应 `Int → Long`,进度框消息显示 `文件名 — 已解压/总字节`
- **JNI 返回值统一为 JSON** — 所有 extract 方法从 `Boolean` 改为返回 `String?` (JSON `{"total","success","error"}`)
  - Rust 端: 9 个 crate 的 extract JNI 函数改为 `-> jstring`，返回 JSON 字符串
  - Kotlin 端: 10 个 `Core.kt` 的 `external fun` 签名同步更新，新增 `ExtractCounts` / `fromJson()` 解析层
  - 新增 `archive_common::extract_result_json()` 序列化函数

- **解压报告准确文件数** — xp3/pfs/nsa/iso/ypf/zip/7z 报告的 `total` 从硬编码 `1` 改为归档内的实际文件数（选中解压时报告选中数）
  - 解压成功弹窗改为显示 "总条目 / 成功 / 错误 / 其他" 四行统计

- **7z 解压 error 追踪** — `sevenz-core` 回调改用 `AtomicU32` 逐文件追踪失败数，不再固定返回 error=0

- **删除进度条** — `deleteWithProgress()` 先 `walkBottomUp().count()` 统计总数,再自底向上逐文件删除,横向进度 + 当前文件名,失败单独计数;单文件用 spinner;接入单删(目录)/批量删,成功弹 `msg_deleted`、有失败弹 `msg_delete_result`

### 功能迭代

- **MainActivity 拆分** — 2732 行 → 1880 行，拆出 13 个独立文件
  - `model/` — ExtractCounts.kt, ArchiveEntry.kt, SearchResult.kt (数据类)
  - `util/` — Constants.kt (色表 + 扩展名集合), FileUtils.kt (工具函数)
  - `adapter/` — PreviewAdapter.kt, FileAdapter.kt
  - `archive/` — ArchiveExtractor.kt (解压调度 + 密码处理), ArchivePreview.kt
  - `ui/` — PreviewDialogs.kt (图片/文本/音频/视频预览)
  - `terminal/` — TerminalDialog.kt
  - `fileops/` — FileOperations.kt (重命名/比较/计算大小)
  - `compression/` — CompressionDialogs.kt

- **xp3-core 改用 fail-counting 模式** — extract_xp3 / extract_xp3_selected 从 `?` 中断改为 skip+count，单个文件失败不影响后续

- **压缩完成自动刷新目录** — `showCompressFormatPicker` 增加 `onComplete` 回调，压缩后自动 `nav(currentDir)`

- 移除 4 个 crate (nsa/iso/lz4/ypf) 的 unused import (`jboolean`/`JNI_TRUE`/`JNI_FALSE`)
- 移除 `nsa-core` 中 `if fail==total` 冗余错误检查
- 移除 `zip-core` `extract_zip_selected_inner` 中死代码 `let total`
- 删除死代码 `ArchiveCore.kt`（旧 JNI 桥，无人引用）、`doExtractBool`（未使用的 lambda）

### Bug 修复

- **`getString` 格式符 `%d` 配 `toString()` 闪退** — 3 处 (`extractSelected`, `startBatchCompress`, `startBatchExtract`) 的 `paths.size.toString()` / `items.size.toString()` / `archives.size.toString()` 改为 `*.size`
- **`lastExtractResult` 影子变量导致解压永远报告失败** — MainActivity 残留 private var 与 ArchiveExtractor 全局 var 同名，`doExtract` 读写 private 变量永远 (0,0,0)
- **`tryExtractWithPassword` 异常时残留旧 `_lastExtractResult`** — 加 `ExtractCounts(0, 0, 0)` 初始重置
- **书签星标失效** — `FileAdapter` 调 `onBookmarkToggled(path)`,但 `MainActivity` 传 `{ saveBookmarks() }` 只保存不增删 → 改为真实增删 + 保存
- **RAR 计数恒为 1** — 8 个 JNI 硬编码 `extract_result_json(1, ...)`,`extract_rar_inner` 恒返回 fail=0 且文件创建失败 `?` 中断整批 → 改为 `AtomicU32` 逐文件失败计数 + 返回真实 `(total, fail)`(匹配非目录成员数),失败文件返回 sink 继续解压
- **7z 分卷检测混入他卷** — `resolveSevenZVolumes` 只按数字后缀收集、不按基名分组,同目录混放多组 7z 分卷时会把别的归档拼进卷列表 → 按正则 group1(公共前缀)过滤同基名分卷
- **死代码清理** — 删 `mismatchMsg`(无调用方)、`msg_disclaimer_ok` / `err_ext_mismatch` 未用资源(4 语言)

---

## v5.3.0

### 功能更新

- **RAR 格式支持**
  - 基础解压 / 归档列表
  - 密码解压 (`rarExtractWithPassword`)
  - 选择性解压 (`rarExtractSelected`)
  - 选择性解压 + 密码 (`rarExtractSelectedWithPassword`)
  - 密码检测 (`rarNeedsPassword`)
  - JNI 桥接 (RarCore.kt)
  - Rust 核心实现 (rar-core, 基于 rars 0.4)

- **LZ4 格式支持**
  - 基础解压 / 归档列表
  - JNI 桥接 (Lz4Core.kt)
  - Rust 核心实现 (lz4-core, 基于 lz4_flex)

- **Cargo.toml / build.sh 集成 RAR/LZ4**

### 功能迭代

- **错误信息国际化** — 新增 15 条错误/提示字符串，覆盖中/英/日/繁四语言
  - 后缀与格式不匹配 (`err_ext_mismatch`)
  - 密码错误 (`err_pwd_wrong`)
  - 无法读取归档（可能含密码） (`err_cannot_read_maybe_pwd`)
  - 不支持的文件格式 (`err_not_archive`)
  - 不支持预览的文件 (`err_preview_unsupported`)
  - 文件打开/解压 IO 错误 (`err_file_open_failed`, `err_extract_io`)
  - 批量解压完成 / 移动结果 (`msg_batch_done`, `msg_move_result`, `msg_move_failed`)
  - 删除确认 / 文件夹大小计算提示 (`confirm_delete_file_msg`, `confirm_delete_batch_msg`, `msg_calc_dir_size_prompt`)
  - 选中文件导航提示 (`msg_selected_nav`, `msg_selected_nav_multi`)
  - 移除全部硬编码中文 toast，统一使用 `getString()`

- **格式选择器增加 RAR/LZ4 选项** — 手动选择格式对话框、批量解压格式选择器

- **文件信息由 toast 改为 AlertDialog** — 长文件名不再截断

- **多选模式下底部操作栏不再遮挡文件列表** — 自动添加 bottom padding

### Bug 修复

- **RAR 密码支持不完整** — 5 处密码处理分支 (showPasswordDialog, doExtract, 选择性解压, 密码重试, 密码检测) 全部补齐 RAR 分支
- **RAR 选择性密码重试提取全部文件** — 改为使用 `rarExtractSelectedWithPassword` 仅解压选中项
- **`extractSelected` RAR 误路由到 7z 解压器** — `else` 分支分离 "7z" 和 "rar"
- **全量解压 `doExtract` RAR 密码重试空操作** — `else -> extractByFormat` 无视密码，改为显式 `rar` 分支
- **批量预览 `batchPreview` 缺少 rar/lz4 列表读取分支** — 补齐 `when(fmt)` 分支
- **批量解压 `startBatchExtract` 格式选择器无 rar/lz4** — 补齐选项和 fmt 数组
- **RAR 密码解压失败** — `extract_rar_inner` 改用 `read_path_with_options` 在 Archive 构造时传入密码，修复 RAR5 加密归档
- **`list_rar_inner` 硬编码 `e:false`** — 改用 `member.meta.is_encrypted` 真实加密状态
- **LZ4 列表大小显示 0 字节** — 解压后取 `decompressed.len()` 作为实际大小
- **`guarded()` 吞掉 panic 细节** — 6 个 crate (rar/lz4/zip/sevenz-core + ypf-core 内联) 全部改为析出 panic 信息
- **`rarNeedsPassword` / `zipNeedsPassword` / `szNeedsPassword` 吞 IO 错误** — Err 时 throw IOException 而非返回 false
