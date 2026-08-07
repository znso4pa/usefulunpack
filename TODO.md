# TODO

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
- [ ] **通用格式对标 ZArchiver** — 补 TAR / GZ / BZ2 / XZ / Z（纯流式，低成本高回报）；CAB/ARJ/LZH 生态差视需求
- [x] **解压进度** — 每个 format 加进度条（JNI 返回值已是 JSON，轮询机制复用压缩的 static atomic 模式，后改为**字节百分比**，见 v5.5.0）
  - Rust: `archive_common::extract_progress` 静态量共享于 9 个 crate,每 crate 4 个 JNI getter (`*ExtractProgressCount/Total/Name/Cancel`)
  - Kotlin: `PollingProgressDialog` 共享 helper(压缩/解压复用),接入 extractAll / extractSelected / batchDirectExtract / tryExtractWithPassword / showPasswordDialog
  - 支持取消(9 格式),取消后清理空输出目录
- [x] **分卷支持** — RAR 原生多分卷 + 7z `.7z.001/.002` 字节分卷
  - Rust: `rar-core` 用 `extract_volumes_to` 原生解压多卷;`sevenz-core` 新增 `ConcatReader`(零拷贝 Read+Seek 拼接)+ 7z 魔数校验
  - Kotlin: `resolveRarVolumes`(partN + rNN 命名)/ `resolveSevenZVolumes`(按基名分组,修同目录混放多组时拼错卷),`isVolumeFile` 识别,选中显示"共 N 卷"
  - 完整对齐: 全量/预览/选择性/密码/进度/取消;ZIP 分卷因库不支持排除
- [ ] **XP3 封包** — XP3 格式的打包/压缩功能
- [ ] **PFS 封包** — PFS/PF6/PF8 格式的打包/压缩功能
- [ ] **UI 重构** — 优化交互流程
- [ ] 完善单元测试与 CI/CD

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
- [ ] **RAR 加密大量小文件慢** — rars 每文件 PBKDF2(~2s/个),几百个小文件加密归档要解很久;非 bug,性能特性(换官方 unrar 可显著改善)
- [ ] **`oneshot_async` 忙等** — `spin_loop()` 轮询 Pending;若 future 永不完成会死循环卡死。当前 xp3 用的同步 reader 总返回 Ready/Err,低概率
- [ ] **深递归** — `iso_walk`/压缩 `add_dir`/`deleteWithProgress` 对极深目录可能栈溢出;galgame 目录深度有限,低风险

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
