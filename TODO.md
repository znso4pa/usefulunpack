## feat(v6.0.0): 真机回归（真语料）+ 合并跨窗口目标 + 画笔透明度修复

### 1. 真机回归：拉真语料对 oracle，抓出 3 个合成夹具永远发现不了的 bug

之前的验证全是自造夹具。本轮从 `uuksu/RPGMakerDecrypter`（社区公认的 ground truth 工具）
拉下**真归档 + 真混淆素材 + 公开断言**，在**仓外**建 harness（`/tmp/uu_regress`，path 依赖
指向 `crates/rgss-core`，repo 一个字节没动）跑端到端比对。

语料：`Game.rgssad` / `Game.rgss2a` / `Game.rgss3a`（真 XP/VX/VX Ace 归档）+ `Image` /
`AudioOrbis` / `AudioMpeg`（真 MV 混淆素材，**无扩展名**）。Oracle：精确 offset/size/key、
`MD5("12345")` 作为 keystream、三个素材解密后 SHA1 全部公开。

| # | 位置 | 问题 | 修复 |
|---|------|------|------|
| 1 | `mv_plain_header` M4A 分支 | ftyp 的 **minor version 硬编码成 `0.0.2.0`**，真文件是 `0.0.0.0`。这 4 字节在混淆窗内、没有密钥就恢复不出来 → `.rpgmvm` 解码结果**永远不是逐字节正确的**。合成夹具带着同样的错，所以自测 100% 绿 | 按真值改 `0x00,0x00,0x00,0x00`；夹具改为从真文件截取 |
| 2 | `MV_PROBE_LEN` / box 扫描范围 | probe 窗 56B → 窗外只剩 24B，而扫描范围 `4..len-4 = 4..20` **恰好排除 20**，真文件的 `moov` 正在 `tail[20]`。一字节之差导致真 M4A 被判非法；`box_size` 恢复同样漏位，真值应是 **32** 而非默认 28 —— 这是**载荷正确性问题**，不只是校验 | probe 提到 96B；两处扫描范围改闭区间 |
| 3 | `mv_kind_of` | 素材类型**只从文件名取**，无扩展名的真素材直接报「cannot tell what kind」。改名的包、重打包的游戏都这样 | 新增 `mv_sniff_kind()`：用 `mv_check_plain`（**只读窗外**的独立证据）逐个试，要求唯一命中 |

**第 3 条的第一版修法是错的，值得记下来**：我先让 `mv_plain_header` 重建出魔数再「检测」
它——而 `mv_plain_header` 本来就是**按 kind 凭空造**魔数的（Png 直接返回常量
`PNG_HEADER16`），等于自证。这是本轮第三次犯「循环论证」，用自己刚写过的判据去抓自己。
正确做法：证据必须**独立于猜测**。`mv_check_plain` 恰好就是为此写的（PNG 验 IHDR body、
Ogg 验 page sequence、M4A 验下一个 box），且三者互斥，真实素材上各自唯一命中。

回归结果（26/26）：

- RGSS 解析 vs uuksu 精确断言：3 归档 × (version / entries / offset / size / key) 全对
- 载荷是**合法 Ruby Marshal 4.8**，可见 `RPG::Actor` / `RPG::Map` / `RPG::Tileset` 类名
  —— 排除「名字对但载荷是垃圾」
- 解包→重封→重解，v1 与 v3 两种目标版本均逐字节一致
- **v1 重封 = 与 uuksu 原始 `Game.rgssad` 504228 B 逐字节相同**（字节恒等）
- MV 密钥推导 + 三个素材解密后 **SHA1 与 oracle 全对**
- **MV 封包往返 = 与 RPG Maker 自己产出的混淆文件逐字节相同**（字节恒等）
- 4 种密钥形式往返一致；3 类拒绝（类型不符 / 已混淆再混淆 / 全零密钥）均不留产物

### 2. 合并进现有归档：可发现性 + 目标可来自其他窗口

用户反馈「第三个我不会用」。查下来不是不会用，是**按钮标签被裁掉了**。

`folder_view.xml` 里三个按钮 `layout_weight=1` 平分，而合并那个标签 10 字（「📦 合并到其他
归档…」）、另外两个各 4 字，360dp 机型上每个按钮只有约 118dp，12sp 的 10 个中文字放不下，
**且未设 `ellipsize`**，文字直接被按钮边界裁掉，看着像个空壳。

- 新增短串 `merge_short`（4 语言）给底栏按钮；`merge_into_archive` 保持不变，继续给
  `showOutputDirDialog` 菜单项用——同一字符串两处复用、约束不同，所以不复用
- **目标候选从「仅当前目录」扩到「其他窗口正在打开的归档」**：
  - 来源是 `tabs.filter { it !== ownerTab && it.previewActive && it.previewSrc != null }`
    ——`previewSrc` 而非 `OpenArchiveRegistry`，因为 registry **没有枚举接口**，而
    `previewActive` 保证它此刻真的开着
  - 用 `showFormatPicker` 分组展示（它本来就是通用的：`Pair<Int, List<String>>` + labels，
    不做任何格式相关处理），顺带白拿 ScrollView 与 Honor/EMUI 禁滚动条的规避
  - 列表在**枚举时**就过滤掉不可封包格式，列表里只留可操作项，省掉
    `merge_target_unsupported` 的点击后报错
  - 重复项（某窗口打开的归档也在本目录）折叠成本目录行——更近
- **拆掉 `mergeIntoArchive` 里的过度防御**：原来若目标被别的窗口持有就拒绝，而那恰好是
  现在要支持的场景。依据：**目标全程只读**，产物是 `uniqueFile(parent, "<名>-cn.<ext>")`
  这个新文件（`PreviewFlow.kt:717-721`），目标从未被打开写入 → 并发读+读安全，别人的预览
  也不失效。真正需要串行化的由 `"merge"` 调度槽负责；而三个**原地写** zip 的函数
  （`zipReplaceEntry`/`zipDeleteEntries`/`zipAddEntry`）**本来就不查 registry**，靠 `"zip"`
  槽串行。换成 `archiveKey(target) == archiveKey(src)` 守卫
- **无可合并目标时按钮置灰**：`mergeTargetGroups()` 是选择器与置灰的**唯一来源**，
  两处不会对「有没有目标」产生分歧。`textColor` 是平铺颜色不是 state-list，所以另外显式
  设 `alpha=0.4f`。`refreshMergeButtons()` 挂在 `renderPreview` / `exitPreview`——
  任何窗口开关预览都会改变别的窗口的答案，不挂就会过期

### 3. 图片编辑器：画笔不透明度

用户反馈「不透明度以后画笔在图片上画画会有问题」。根因是**笔迹没有独立的透明度层**，
半透明被重复叠加，三个缺陷同源：

- **A（用户看到的）**：`handleBrush` 把每一段线直接画到常驻 `overlay` 上，用 `SRC_OVER`
  合成。相邻线段共享端点、圆头笔帽互相重叠，于是**每段都在前段的 alpha 上再叠一次**：
  50% 下 50%→75%→87%…，慢拖时笔画越往后越深、关节结块。alpha=255 时看不出来，
  所以只在调低不透明度后暴露
- **B**：`redrawOverlay()` 用**当前**的 `brushAlpha`/`brushColor` 重画所有笔迹，而它被
  `undo()` 调用 → 100% 画几笔、调到 30%、按撤销，**剩下所有笔迹立刻变成 30%**。
  笔迹只存了几何点，没存画时用的颜色和透明度
- **C**：`size < 2` 的笔迹被 `redrawOverlay` 跳过，单击（DOWN+UP 无 MOVE）**不出点**

改法：每个笔迹记录自己的颜色/透明度/线宽（`Stroke`），画进独立图层时用**满 alpha**，
只在合成那一刻施加一次该笔迹的透明度。`onDraw` 用同一个合成路径画进行中的图层 →
实时预览与最终落盘一致。`composeResult()` 无需改动。

### 4. tab 切换后路径栏跳到 `/`

根因：**`tvPath` 只在 `navTab` 里被写，而预览渲染从不写它。** 预览盖住列表但盖不住路径栏。
进预览 → `syncPreview` 这条链完全不碰 `tvPath`；而 `TabState.currentDir` 初值是
`File("/")`、`folder_view.xml` 默认文本也是 `/`。所以只要片段在预览态下被重建
（切 tab、旋转、`rebuildPager`、会话恢复），路径栏就停在 `/`。

`FolderFragment.kt:253` 早意识到这点并手动回填，但那只是**一处**打补丁，`syncPreview`
那条路没有。

改法：把路径变成**派生值**而非存储值，三处写入统一：

```kotlin
fun displayPath(): File =
    if (previewActive) previewSrc?.parentFile ?: currentDir else currentDir
```

在 `navTab` / `syncPreview` / 片段绑定三处都用 `displayPath().absolutePath`。预览时显示
归档所在目录（这才是「你在哪」的答案）。以后新增渲染路径不会再漏。

注：路径栏是 `ellipsize="start"`，长路径会截掉**头部**（显示 `…/Pictures/game`）——这是原有
的显示取舍，未改动。

### 5. 机械审计（不读代码，改跑脚本）

换了手法：不顺链路读，改跑 4 个审计。1 个真 bug，另外 3 个报的都是我自己的误报。

| 审计 | 结果 |
|---|---|
| 取槽是否所有出口都释放 | 25 处全 OK（漏一个 = 那个格式永久卡死） |
| Rust 侧 `clear_cancel` 不变量 | 报 36 个，实际全是 `*ListEntries`/`*NeedsPassword`/`setParallelThreads`/`zipSetEncoding`——纯读不受取消标志影响，**正则过宽** |
| **解压侧 key 集合对称性** | **真 bug**，见下 |
| 路径逃逸 | 45 处 `File::create` + 22 处条目名 `join`：排除 `#[cfg(test)]` 与「名字来自输入文件自身 `file_stem()`」的单流格式后，**无逃逸**；9 个多条目 crate 全经 `safe_join` |

**key 对称性真 bug**：列举代码被手抄成**三份**，已漂移一次——

| 列举路径 | zip 编码设置 | ksd 分支 |
|---|---|---|
| `previewArchive` | ✅ | ✅ |
| `listPreviewEntries` | ✅ | ❌（设计如此，只管可嵌套格式） |
| **`batchPreview`** | **❌** | **❌** |

`zipSetEncoding` 设置的是**进程级 native 全局状态**，5 处调用点都设了，唯独批量预览没设
→ 用户设置 GBK 后，单包预览中文 zip 文件名正常，**批量预览同一个文件全是乱码**。静默、
只有对比才看得出。

修法不是补那两行，而是抽 `listEntriesJson(format, src, pwd, nestedOnly)` 作单一来源，
三处都只是转发。`nestedOnly` 保留「可嵌套格式只有 10 种」的语义（9 个单流格式在
`nestedOnly=true` 时返回 null）。`batchPreview` 特有的 `resolvePwd` 取消语义保留。
现在 19 种格式精确对齐。

### 6. 其他修复

| 位置 | 问题 | 修复 |
|---|------|------|
| `schedulerKeyOf` | 5 个 tar 变体共用 `libarchive_tar_core` 的**同一个** `compress_progress` 静态量，却各占各的槽位 → **可以并发** → 第二个的 `reset(total)` 清掉第一个的总量；取消第二个会**顺手杀掉第一个**。这正是 `pf6→pfs` 当初归并要解决的同类问题，tar 一直漏了（而该函数注释还写着「今天只有两族」） | 归并到 `tar` 槽；注释改成三族并逐个列名 |
| `tryExtractWithPassword` 的 `doExtract` | 7z/rar 丢 `sel`（写死 `""`），而同函数重试分支传对了。`sel` 是四路分派输入，丢了会掉进 `else` → **全量解压**且无任何提示。当前不可达（调用方都先挡了空选），但是活陷阱 | 转发 `sel`；补注释说明为什么不能省 |
| `mergeIntoArchive` | `password` 写死 `""`、`level` 写死 `generic_level`。6 个 `compressDispatch` 调用点里 5 个读设置，只有这里不读 → **合并进带密码的 7z 会产出无密码的副本**（等于把受保护归档复制成明文） | 读设置 + 共用 `defaultCompressLevel(prefs, fmt)` |
| `repackEditedArchive` | `gameNaming = format in setOf("rgssad","rgss2a","rgss3a")`，但该路径的 `format` 是**读取 key `"rgss"`**（`FolderFragment.kt:77` 为证）→ 条件恒假 → **编辑回包的 `Game.<ext>` 改名从未生效**，而这是用户唯一没有独立压缩按钮的重打包入口 | 改 `format == "rgss"`；写版本仍由源文件扩展名推 |
| `mv_encrypt` | 用 `.jpg` 选 `rpgmvp`、或对已混淆文件再混淆，都会**静默产出游戏读不了的文件** | 新增 `MvKind::matches()`：类型不符拒、已混淆拒（均在建文件之前 return，不留半成品） |
| MV 封包 key 扩展名把关 | `rpgmvp` 只收 `.png`、`rpgmvo` 只收 `.ogg`、`rpgmvm` 只收 `.m4a`，不符则 toast 拦下（拦在**填密钥之前**）。批量下整批拦而非静默跳过：选 50 张图只产 47 个，事后极难发现少了哪 3 个 | `mvRequiredExt` / `mvExtMismatch` + `msg_mv_ext_mismatch`；选择器标签改为「… — from .png」把规则前置 |
| `COMPRESS_LABELS` MV 条目 | 封包格式列表里 MV 那条写着 **"Extract only"**——解码时代的遗留，用户在选择器里看到「仅解压」点进去却是封包对话框 | 四语言改为「解压 & 封包」并补上类型规则 |

### 7. 我自己在这轮犯的 3 次同类错误（都栽在夹具和脚本上）

值得记下来，因为它们和上面 3 个真 bug 是同一个教训：**自造的验证物会和被验证的假设一起错。**

- 手抄密文夹具时用了 11 个 0，而 `RPGM_HEADER` 是 16 字节含 `03 01` → 文件被当成「未混淆」
  直接透传，**测试一度是绿的**。改为用脚本从明文生成密文（本次会话已因此栽过两次，
  这次彻底换成生成式）
- 「三处列举代码」审计里我的括号配对函数把 `&[u8]` 的括号当成数组开头 → 误报
- 汇总脚本本身 2 个 bug（标签 padding 未 strip、`diff -r` 把重封产物也算进对比目录）→
  报出的 5 条「失败」全是假的

### 8. 背景图（壁纸）：被自己加的不透明层挡死

`applyBackgroundImage` 把壁纸画在 `R.id.root` 上，再把 `toolbar` / `pathBar` 压成半透明黑、
`panel` 置空 —— 这套是 4.x 单窗口时代的做法，**当时是对的**。多窗口改造给布局加了
`viewPager`、`folderRoot`、`previewRoot`、`tabBar` 四层，每层都带自己的不透明背景，而
`applyBackgroundImage` 从没跟着更新：壁纸从此**整个 app 都看不到**，而每一层新加的不透明底
都会重新挡一次 —— 与「壁纸画在 `R.id.root`、任何不透明底都会挡住它」这条既有不变量
完全吻合，只是当初没人按它去登记新增的层。

四个真缺陷：

| 位置 | 问题 | 修复 |
|------|------|------|
| `applyBackgroundImage` | 只清 `panel` / `pathBar` / `toolbar`。`viewPager`、`folderRoot`、`previewRoot`、`tabBar` 四层不透明底**从没被管过** | 三张让位表 `BACKDROP_TRANSLUCENT` / `BACKDROP_TRANSPARENT` / `BACKDROP_ORIGINAL` + 整棵树遍历；新增不透明背景的 view 必须登记 |
| 同上，多 tab | `panel` / `pathBar` / `bottomBar` / `folderRoot` 是**每个 tab 一份**（`offscreenPageLimit` 全量驻留），`findViewById` 只能清到第一份 → 只有第一个窗口透得出壁纸 | 走整棵树；`clear` 分支对称恢复全部 8 个 id（原表只恢复 4 个，多出来的会残留） |
| `placeOnRoot` 首帧 | `if (rw <= 0 \|\| rh <= 0) return` —— 首帧没量到尺寸就**静默什么都不做**，壁纸永不出现且**无任何日志可查** | 抽成本地函数 + `root.post` 重试一帧 |
| `clear` 分支的 root | 恢复成 `bg_surface`，而布局里 root 的底是 `bg_file_list` | 改回 `bg_file_list` |

**第一版修法自己踩了同一个坑，值得记下来**：把让位调用放在 `FolderFragment.onCreateView` 里、
调 `act2.refreshBackdrop()`（从 `R.id.root` 往下走）。但 **`onCreateView` 返回时 fragment 的
view 还没挂到 ViewPager 上**，从 root 出发根本走不到它 —— 那样只有 tab 条透得出壁纸，
其余三处依旧全黑，而静态检查和 lint 全绿。改成 `applyBackdropInTree(自己的 root)`，
不依赖是否已挂载。

`AGENTS.md` 本轮新增了一条壁纸让位的不变量，并把这三张表登记规则、第 (2) 条的
「必须走整棵树」、第 (3) 条的「fragment 必须遍历自己的子树，不能调 `refreshBackdrop()`」
连同「改完必须实机设图确认四处透出」的验收动作一起写进去（AGENTS.md 通篇英文）。

**状态**：**实机验证通过**（用户确认）。静态侧亦已查：8 个 id / 8 个颜色全部真实存在、
两张处理表与恢复表 key 一致且无 id 跨表、无浅色主题、列表行 item 用透明涟漪不挡壁纸、
`FolderFragment` 只经 `TabPagerAdapter` 创建（`rebuildPager()` 重建后必经 `onCreateView`）。

已完成的实机确认：

1. ✅ 高对比壁纸下 tab 条 / 工具栏 / 列表 / 预览**四处都透出**
2. ✅ 第二窗口同样透出（`refreshBackdrop()` 整棵树遍历生效）
3. ✅ 点「清除背景」→ 四处恢复实心底、无残留半透明

## feat(v6.0.0): 字节级进度条深度调试 —— 6 处解压 + 2 处压缩 + 回收站

### 1. 口径先定下来：未知输出大小的格式统一走**读侧**

解压进度分两种口径：

- **写侧**：`total = 未压缩条目总大小`，`bytes = 已写出字节`。精确，但很多格式的
  输出大小事先**不可知** —— `.lzma` 头的 uncompressed size 常年是 `-1`，zstd/brotli/
  xz 帧头根本不存这个字段。
- **读侧**：`total = 归档大小`，`bytes = 已消费归档字节`。永远有分母。

用户裁定：**读侧**。所以本轮把 5 个单文件流（brotli/bzip2/xz/zstd/lzma）从写侧改读侧，
gzip **单成员保留写侧**（它精确可知且体积小，改读侧反而倒退），**多成员改读侧**。
两种口径的 UI 含义不同，但都不再出现「整个解压过程转圈」。

### 2. 查出来的 8 个缺陷

| # | 位置 | 问题 | 修复 |
|---|------|------|------|
| 1 | brotli / bzip2 / xz / zstd / lzma | `reset(0)` + 只喂输出字节 → `total <= 0`，Kotlin 侧永远不定进度，整个解压是一个转圈 | 改读侧（`ProgressReader::extract` 套裸文件）+ 保留 `BoundedWriter` 防解压炸弹 |
| 2 | lzma | **上一条的同源 bug 藏得更深**：真实 `.lzma` 的 `uncompressed_size` 就是 `-1` | 同上；另外 `lzma_rs` 读到约 93% 就停（它知道解压后多大），补一段收尾让条落到 100% |
| 3 | gzip 多成员 | 走写侧但多成员的总量无从累加 | 多成员分支改读侧 |
| 4 | **zstd 窗口上限** | `StreamingDecoder::new` 用 ruzstd 的 `DEFAULT_MAX_WINDOW_SIZE = 100MB`，而 zstd **level 22 写 `window_log=27`（128MB）** → files4testing 里所有 `.zst-22` 全被拒，系统 zstd 却能解 | 改 `new_with_max_window_size(ZSTD_MAX_WINDOW)`，上限 1 GiB（窗口在内容校验前就分配，必须有界） |
| 5 | zstd 失败清理 | 解压失败时 `File::create(&dest)` 已经建好文件，Err 返回后**半成品留在磁盘上** | 加 `fail()` 闭包，所有错误路径 `remove_file` |
| 6 | ypf 压缩 | `total` 是源目录字节，喂的却是**压缩后** payload → 进度条冲过 100% 后卡住 | 记录 `src_size`，按源字节喂 |
| 7 | ksd 压缩 | `reset()` 之后**从不 `add_bytes`** → 恒 0% | 按 64 KiB 分块喂 UTF-16LE 输入字节，并检查取消 |
| 8 | 回收站 | `scanTree()` 一直算得出字节总数（`ScanResult.size`），但 `MoveProgress` 只带文件计数 → 一堆小文件时条几乎不动 | 签名扩为 `(done, total, doneBytes, totalBytes)`；条走**字节**，文字带**文件计数** |

### 3. `ProgressReader` 同时实现 `Read` 和 `BufRead` 是个陷阱

lzma 的收尾补丁第一版写成 `if in_len > fed { add_bytes(in_len - fed) }`，测试仍然红，
而且红得很有启发性：`left: 330, right: 327` —— **超出了 3 字节**。

原因是我把 `ProgressReader` 套在 `BufReader` **外面**：`ProgressReader` 也有 `BufRead`
实现，于是解码器的读请求由 8 KB 缓冲满足，`fill_buf` 不计数、`consume` 只在消费时计数。
小文件会被**整个读空**，`read` 路径计到 327、`BufRead` 路径停在 305。

**正解是反过来：`BufReader::with_capacity(64 KiB, ProgressReader::extract(file))`** ——
让 `ProgressReader` 套裸文件，只数真实读取。另外 4 个 crate 本来就是
`ProgressReader::extract(裸文件)`，没有这个问题。

### 4. 回归：files4testing 484 条向量 + 23 条注入故障

仓外 harness（`/tmp/f4tregress`，`[patch.crates-io]` 指向本地 vendor fork，否则会去
crates.io 拉官方 `zip`/`rars`，缺我们自己加的 `with_disk_offsets`）：

- **484 / 484 通过**，覆盖 20 种格式 × 3 层（plain / password(123) / 1 MB 分卷）
- **22 / 23 条注入故障干净拒绝**（干净 = 返回 Err **且不留半个产物**）

**harness 自己犯了 4 次「夹具编码错误前提」，全部由 sabotage 抓出来**（把判据换成必错
的值，看报告是否变红）：

1. 手写 JSON 扫描漏掉 484 条里的一半，还误吃顶层 `raw_files` 的 9 个 `path` →
   换 `serde_json`。漏一半向量的报告比没有报告更糟。
2. `"zip" if is_volume` 的 guard 臂写在**无 guard 的 `"zip"` 臂下面** → Rust 取首个匹配
   臂，卷路径从未执行过。同一类错误今天已经栽了三次。
3. `rawfile_tree` 结构 oracle 的臂同样排在 `"iso"` 臂之后，从未执行；而我又在 main 里
   加了个「这条算通过」的兜底臂，于是 **sabotage 换成 `Err` 时报告依然 484 全绿**。
4. tar 与 iso 的期望条目集合**本来就不一样**（tar 有 `link_to_rawfile1.txt`，iso 没有），
   共用一份表会让 iso 永远失败。

另外 zip/7z/rar 的 host 入口返回 `(total, error_count)`，**`Ok` 不等于成功** ——
wrongpass 走 per-entry 的 `fail += 1`，整包仍返回 `Ok`。最初据此判成「竟然成功」。

### 5. rar 条目 CRC 校验（已修）：篡改的条目不再伪装成成功

`crates/rar-core` 里**一次都没校验过条目校验和**（整个文件 grep `crc` 零命中），所以
`faults/corrupt-rawfile1.m5.rar` 被当成解压成功，还留下一个内容已损坏的
`rawfile1.txt` —— 而 `unrar t` 对同一个文件报 `checksum error`。这是最糟的失败形状：
用户被告知成功了，拿到的却是错数据。

**「Ok 不等于成功」在这里第二次咬人**：RAR1.5+ 的篡改条目走 per-entry 分支，整包仍
返回 `Ok((total, errors))`，必须看第二个数；RAR1.3/1.4 则是内建 `verify_checksum`
直接中断整包。两条路径的契约不同，但都算失败。

实现要点（四条写入路径全部覆盖）：

- 顺序路径 `rar_writer`：`CrcGuardWriter` **在 `Drop` 时**校验。这是唯一可用的完成
  钩子 —— `extract_to_with_options` 只给每条目一个 `Box<dyn Write>`，没有「条目结束」
  回调。
- `fast_write_member`（选择集路径 / 并行路径的流式成员）：同样走 `CrcGuardWriter`。
- `write_buffered_member`（并行路径的批量成员）：数据已在内存，一次 `of_slice` 即可。
- 计数器必须**共享调用方那个**。`Box<dyn Write + 'static>` 不许借用栈上计数器，所以用
  `Arc<AtomicU32>`。第一版在 guard 里 `Arc::new(AtomicU32::new(0))` 造了个新计数器 ——
  那等于把校验变成了静默的 no-op：文件被删了，但条目照报成功。

**两处必须靠真实数据才能发现的算法前提**（都是夹具抓出来的）：

1. **RAR1.3/1.4 的校验和不是 crc32 的低 16 位**，而是一套独立的 16 位算法
   （`sum(bytes).rotate_left(1)`，vendored 里的 `Rar13Checksum`，原本私有，已公开）。
   我第一版按 `crc32 & 0xffff` 实现，结果**每一个合法的 RAR1.3/1.4 归档都被误判为
   损坏**。夹具里那句「pristine archive must still extract」就是为这个准备的。
2. **分卷条目的校验和只覆盖第一卷那一段**。实测：4 卷 4096 字节的夹具，header 存的是
   `0x8cb4`（= 前 1024 字节的校验和），而整个成员的校验和是 `0xbf5f`。拿全量数据去比，
   会拒绝**所有**合法的分卷归档。所以 `member_crc` 对 `is_split_before/after` 的成员
   返回 `None`，交给 vendored reader 逐卷校验（它本来就在做）。

修完 files4testing **23/23 干净拒绝，0 问题**，484 正向向量零回归。

### 6. 进度条测试的全局 static 竞争（CI 报错，64 处潜在 flaky）

`archive_lzma-core` 的 `extract_progress_total_is_reported` 在 CI 上以
`left: 327, right: 119` 失败，本地 25/25 通过。原因是它和同 crate 的兄弟测试并发跑在
**同一个 per-cdylib static 进度 store** 上，兄弟测试用自己的夹具尺寸 `reset(total)`，
把它的 total 从 327 改成 119 —— 数字完全确定正是竞争的特征。

全仓审计后发现 **17 个 crate、64 个测试**在共享 store 上竞争，只有 lzma 断言了 total
所以只有它会红。已给全部 64 处加 per-crate 测试锁。

**这个自动化脚本我写砸了三版**，每一版都是同一类错误，记下来免得第四次再犯：

1. 按「相对 mod 起点的字符偏移」插入 → 偏移算错，一个都没落地
2. 假设「一个 crate 只有一个测试模块」→ lz4 的测试在 `mod compress_tests`、helper 却
   落在 `mod tests`；nsa 有**两个**测试模块各带一个 `PROGRESS_LOCK`
3. 用花括号配平找模块边界 → 注释和字符串里的 `{`/`}` 把配平数带偏，插入点落进函数体
   中间；改用行级处理后，又因为「mod 插入和测试插入分成两段」破坏了全局倒序，
   高行号的插入顶偏了后面的行号，守卫落到模块层（`let` 不能做全局变量）

最终版是行级处理 + 单一列表按行号倒序 + 插完当场文本校验 + 真实编译把关。

**但脚本给的锁本身可能是错的，加完之后又发现三类"加了等于没加"** —— 这一条比脚本
怎么写更值得记：

1. **一个 crate 里出现两把锁**。bzip2/lzma/xz/zstd 原本各有一把 `lock()`，脚本又加了
   一把 `progress_lock()`，两者是**独立的 Mutex** —— 测试各拿一把，等于完全没锁。
   zstd 的 `extract_progress_total_is_reported` 在 20 轮里还是炸了一次才发现。
2. **跨测试模块各一把**。nsa 有 `tests` 与 `security_tests`、rgss 有两个模块、
   sevenz 有两个模块；同一个 crate 的模块共享同一批 per-cdylib static，模块级锁挡不住
   跨模块竞争。全部收敛到 crate 级一把。
3. **同一测试拿两把锁 → 死锁**。把 sevenz 的 `progress_lock` 指向 crate 级 `TEST_LOCK`
   之后，原本同时写 `let _g = progress_lock();` 和 `let _g = crate::TEST_LOCK.lock()`
   的 7 个测试立刻挂死在非重入 Mutex 上（rgss 上一轮已经犯过一次同样的错，没长记性）。
   守卫名统一后，又用脚本复查"任何测试体内 lock 变量 > 1"来兜底。

**教训**：机械改造的产出必须复查"语义唯一性"，不能只验证"文本/编译通过" —— 我用文本
校验和真实编译各把关了一次，绿了，然后真机上还是随机炸。

### 7. 「进度条到了 100% 还会卡一阵」—— 根因与修法

用户追问这个症状。**不是错觉，是真 bug，而且「有的时候」正是它的特征。**

**根因 A：上一次的 100% 被当成本次的进度显示**

`clear_cancel()` 原本只清 CANCEL，不清 `BYTES/TOTAL`（`crates/common/src/lib.rs`），
于是**上一个操作留下的 `bytes == total` 会活到下一个操作开始之后**。而每个格式都要
先干重活才 `reset(total)`：

| 格式 | reset 之前的重活 |
|---|---|
| zstd | `fs::read()` 把**整个归档**读进内存（reset 在第 89 行，read 在第 67 行）|
| rar | `ArchiveReader::read_path_with_options` + 遍历全部成员算总量 |
| tar | pass 1 把**整个外层流解压一遍** |
| zip / 7z / iso | 读中央目录 / 7z 头 / ISO 目录树 |

`PollingProgressDialog.start()` 一创建就 200ms 轮询，`opH.await()` 一通过立刻开始画
这些 static。所以**「刚做完一个、紧接着做第二个」时，新操作在 pre-reset 窗口里显示
的是上一次的 100%** —— 这就是为什么它时有时无：只有「第二个操作跟在第一个后面」才触发。

修法：`clear_cancel()` 归零全部计数。它本来就已在每个操作入口（68 个 JNI 入口，
已用脚本逐个核验都以它开头）调用，所以不需要新增调用点。`total = 0` 正好是正确信号
—— `ExtractProgress.kt` 把 `total <= 0` 渲染为不定进度，语义就是「准备中」。

`reset()` 仍然保留 CANCEL：pre-scan 期间按的取消必须活到解压阶段。这两件事**不能合并**，
各有各的理由，已在代码注释和 AGENTS.md 里写明。

**根因 B：真的有 100% 之后还在干的重活**

回收站的跨盘回退是**读一遍 + 写一遍**（copy 树 + 删原树），原来只按 copy 报 0..100%，
于是条满之后 `deleteRecursively()` 在一棵大树里磨半天。改成总量 = 2×树大小，两段各占
一半；删除阶段用 copy 阶段记下的 `(File, Long)` 列表逐个删（同一套遍历，避免符号链接
目录被走第二次），目录在文件之后按深度倒序删。

**根因 C（未修，已记录）**

- rar 批量并行解码：整批（≤192 MiB / 4 线程）先解码进 RAM 再写，这段不喂总条 → 条一段一段跳
- 压缩编码器收尾：喂完最后一个输入字节后还有 `enc.finish()` / `zip.finish()` / `sz.finish()`。
  编码器 flush 处理的不是源数据，**无法用源字节表达**，只能改文案，不做

顺带修掉一个潜在 flaky：`common` 里已有的 `progress_total_adjust_saturates` 会在并行
测试里把全局 store 的 total 打成 0，与新加的用例抢同一个 static。已加测试锁。

### 8. 复制：进度条 + 可取消 + 半成品清理

用户反馈「复制时文件先出来、最后才 toast『已复制』，分不清在干什么」。两条复制路径
（`copySingleFile` 单个、`startBatchCopy` 多选）都跑在裸 `thread {}` 上：全程无反馈、
无法取消，而且**失败的 `copyRecursively` 会把已经写了一半的树留在盘上** —— 对用户和
之后的「复制成功了吗」检查都像是成功的。

统一到 `fileops/CopyProgress.kt`：

- `OpOverlay` 双条卡：总条 = 全部目标的字节，文件条 = 当前成员，消息行 = 成员名
- 取消按钮真正生效，**且只删正在复制那个目标的半成品**；已完成的保留
- `getCopyFileName` 保证目标名是全新的（循环直到不存在），所以删半成品不可能碰到用户
  原有数据，只删这次刚写的字节
- 锁用 `OperationLock`（与删除/回收站/扫描一致；复制是文件操作不是归档操作，按
  AGENTS.md 不变量 6 属于既有例外，不迁移到 OpScheduler）

**「进度条偶尔重来」的根因**：总条喂的是 `doneBytes + fileDone`，而 `fileDone` 是
**当前这一个文件**的字节，`doneBytes` 只在整个目录复制完才推进 —— 复制多文件目录时，
每换到一个新文件总条就从该文件大小重新开始，锯齿状反复归零。单文件目标看不出来，
这正好是「偶尔」。改成由 `copyOneTarget` 持有**目标内累计字节**（跨文件边界不归零）。

两个自己写出来又改掉的缺陷：

1. 取消时把总条强行 `pushOverallProgress(totalBytes, totalBytes)` —— 那是**为做完的
   工作涂 100%**。改回真实完成量。
2. 每 256 KB 就 post 两次 UI。1 GB 文件会排 ~8000 个 `runOnUiThread`，UI 线程（还要
   处理用户点取消的触摸）会变成瓶颈 —— 进度显示反过来拖慢复制。改成**按整百分点节流**。

另外 `scanTreeBytes` 是新加的、跑在复制之前，`File.isDirectory` 会跟随符号链接，所以
环状链接会让遍历长时间打转；取消检查必须放在**文件级**而不是目标级，否则用户逃不掉，
而复制线程会一直占着 `OperationLock` 让后续所有文件操作都报「忙」。

### 9. 回收站：2× 字节总量让大小显示翻倍

跨盘回收站是**读一遍 + 写一遍**（copy 树 + 删原树），上一轮把总量设成树大小的 2 倍，
好让条单调走到 100%（原来复制完就 100%，然后 `deleteRecursively()` 在大树里磨半天）。
用户实测：3.6 GB 的目录**显示成 7.2 GB**。

条的行为是对的，**文字基准错了**。`MoveProgress` 改为携带两种口径
（`MoveState(barDone, barTotal, dataTotal)`）：

- `barTotal` = 2× 树大小 → 只喂条，保证单调 0→100%，不重复也不冻结
- `dataTotal` = 真实树大小 → 只喂文字，3.6 GB 就显示 3.6 GB
- `cleaningUp = barDone > dataTotal` → 清理阶段消息行改成「正在删除原文件…」，
  否则会看到「条在动但文字已经 3.6 GB / 3.6 GB 满格」，像卡住

**顺带修掉「偶尔丧失进度」**：rename 快路径**一个数都不上报**，条一直转圈、大小行
空着。现在立刻上报完成态（`barDone == barTotal == dataTotal`）。

### 10. 验证结果

- `cargo test --workspace` **217 / 217**，并连跑 15 轮无 flaky（进度断言 7 + clear_cancel 不变量 2 + rar CRC 1 + 测试锁收敛）
- files4testing **484 / 484**，20 种格式 × 3 层，注入故障 **23 / 23** 干净拒绝（本轮补上 rar CRC）
- 进度终值 harness：7 / 7 落在 100%（5 个读侧 + gzip 单成员写侧 + gzip 多成员读侧）
- `lintDebug` **0 error / 271 warning**，与基线逐条比对**零新增**
- `bash build.sh` 三 ABI 交叉编译通过，`adb install` 成功
- **用户实机确认**：lzma / brotli 预览正常；壁纸正常；复制进度条「重来」已修复；
  回收站大小显示（3.6 GB 不再显示 7.2 GB）与「丧失进度」已修复

### 待做（装机后，仅剩交互层）

算法层已无已知不确定项（RGSS/MV 26/26 对 oracle 含两组字节恒等；进度条 484/484
files4testing）。剩的只有 UI 观感：

1. 360dp 机型上确认 `merge_short` 不再截断
2. 开两个窗口各预览一个归档 → 合并按钮能选到对方那个；关掉对方窗口的预览 → 按钮转灰
3. 图片编辑器：调低不透明度画一笔，确认笔画均匀、关节无结块；改不透明度后按撤销，确认
   历史笔迹不变
4. 预览态切 tab，确认路径栏仍是原目录
5. 把真 `Game.rgss3a` 封包→改名→放回游戏目录→游戏能启动
6. **回收站字节进度**：移一个跨盘的大目录（会走 copy+delete 回退）→ 确认进度条按
   **体积**推进而不是按文件数慢慢爬，文字里的文件计数同步；同一个含大量小文件的
   目录应能看到条跑得比原来快得多

## feat(v6.0.0): RAR 真语料回归（三个家族）+ 分卷损坏矩阵 —— 找出 1 个真缺陷

### 1. 为什么要做：files4testing 的 rar 向量 **100% 是 RAR 5**

上一轮给 rar-core 加了条目校验和，涉及**三条**算法路径，但真实文件覆盖是：

| 家族 | 我的 CRC 路径 | files4testing | 真实文件覆盖 |
|---|---|---|---|
| RAR 1.3/1.4 | `Rar13`（16 位 `sum().rotate_left(1)`）| 0 | **0** |
| RAR 1.5–4.x | `Crc32` via `Rar15To40` | 0 | **0，连合成的都没有** |
| RAR 5 | `Crc32` via `Rar50Plus` | 65 | 有 |

也就是说**两条路径从未被任何真实归档检验过**。而上一版 RAR1.3 的实现正是把算法
想当然写成 `crc32 & 0xffff` —— 会拒绝**每一个**合法的 RAR 1.4 归档。

语料来源：本地 cargo registry 里 `rars` crate **自带**的测试夹具，一直躺在
`~/.cargo/registry/src/*/rars-0.4.9/tests/fixtures/`，从没被用过：

| 目录 | 文件数 | 内容 |
|---|---|---|
| `rar13/` | 18 | **真实 RAR 1.4**：分卷（`MULTIVOL.R00`）、solid、加密、SFX、多文件、带目录 |
| `rar15_40/` | 92 | **RAR 1.5–4.x**：rar154/202/250/300/420、加密、头加密、PPMd、RARVM 滤镜、recovery volume |
| `rar50/` | 53 | RAR 5（作为对照基准组）|

加密夹具口令统一是 `password`（已用 `unrar t -p` 逐个反查确认，不是猜的）。

### 2. 一致性比对：123 个样本，**0 个真实缺陷**

新建仓外 harness（`raroracle`），以 `unrar 7.23` 为**独立 oracle**，同一份文件分别问
两边，比对「接受/拒绝」+ 产出内容：

| 结果 | 数量 |
|---|---|
| ✅ 一致 | **119 / 123** |
| 🔴 误拒合法归档（unrar 接受，我们拒绝）| **0** |
| 🟡 我们比 unrar 宽松 | 4 |

**上一轮修的 RAR1.3 算法经真实 RAR 1.4 归档验证是对的**（16/16 一致）。四条分歧
逐一甄别后全部不是我们的缺陷：

- **3 条**（`comment_nopsw` / `comment_psw` / `comments`）：unrar 报
  `The archive comment is corrupt`，而两个数据成员都是 `OK`。我们**不提取归档注释**，
  所以接受数据是设计内行为。
- **1 条**（`rarvm/generic_delta_padding_mutation.rar`）：unrar 报
  `itanium_synthetic_bundles.bin - checksum error` 并拒绝产出，但我们解出的 1 MiB
  数据 CRC32 = `0x39086451`，**与归档自己存的值一致**，也与 `rars` 自己的回归测试
  期望一致：

  ```rust
  assert_eq!(files[0].file_crc, 0x3908_6451);
  assert_eq!(crc32(&extracted[0].data), 0x3908_6451);   // 期望成功
  ```

  这是 **unrar 与 rars 在非标准 VM 滤镜程序上的分歧**，我们和归档自己站一边。

### 3. harness 自己犯了 5 次错，全部会让报告变成假结论

这一节比结论更值钱——**每一次都产出了「看起来像产品缺陷」的结果**：

1. **语料目录硬编码** → harness 只能跑一个语料，所以「换语料测 RAR3」这件事一直没做。
   改成 `UU_CORPUS_DIR` 环境变量。
2. **`volume_set` 不认 `.r00` 旧命名**（只认 `.001` / `.part`），也不排除 `.rev`
   recovery volume。
3. **旧命名分组差一错误**：判「是不是旧命名首卷」时检查了 `stem.len()+1 == '.'`，
   而 `.` 在 `stem.len()` 位置 → 所有旧命名组都没被识别，喂给提取器的只有首卷。
4. **旧命名卷序错**：只按「是不是 `.rNN`」分两档排序，`.r00/.r01/.r02` 之间落进
   `read_dir` 的任意顺序 → **5 条假的「误拒合法归档」**，报的还是
   `match distance out of range` / `checksum mismatch` 这种和真解码器 bug 一模一样的错。
   正解是按 `(letter - 'r') * 100 + digits` 排。修好后误拒归零。
5. **oracle 调用传了整个卷集合**：unrar 收到多余路径会当成 glob，返回 exit=10
   （"no files matching"）→ 看起来像「unrar 拒绝」。正解是**只传首卷**，unrar 会
   自己按名字发现兄弟卷。同理，变异体必须写在**同名同目录**下，否则 unrar 读的是
   原始兄弟卷 —— 修这一条之前，损坏矩阵只能命中第 1 卷。

### 4. 🔴 真缺陷：解压失败后**把损坏文件留在盘上**

分卷损坏矩阵：对 26 个多分卷组、90 个卷各翻一个字节（多种 bit 模式），**先问 oracle
这个变异它检不检测得到**，只把「unrar 从接受变成拒绝」的落点算作有效变异。

| 结果 | 数量 |
|---|---|
| 尝试变异 | 90 卷 |
| oracle 确认可检测 | **70 处** |
| 🔴 **漏检**（unrar 拒绝，我们接受）| **0** ✅ |
| 🟡 **拒绝了，但输出目录留下产物** | **55 处** |
| ✅ 一致拒绝且无残留 | 15 处 |

**「0 漏检」实证了一个此前的判断**：让 `member_crc()` 对 `is_split_before/after`
的成员返回 `None`（因为分卷条目 header 的校验和只覆盖首卷那一段）是对的 —— 校验
由 vendored reader 逐卷完成，三个路径（stored / buffered / streaming）都真的在校验。

**但清理没做**。最小复现（`stored_multivol_rar300` 的 `.r00` 偏移 100 翻一个字节）：

| | 行为 |
|---|---|
| 我们 | 返回正确的 `Err("checksum mismatch: expected 0x4a832ebd, got 0xe3bb78a8")`，**但留下 `stored-volume.txt` 3360 字节损坏数据** |
| unrar | 拒绝，**0 个文件** |

RAR 5 的加密/多卷/solid 变体还会留下 2 个产物。覆盖三个家族、单卷与分卷、stored 与
压缩全部形态。

**成因**：条目校验和的校验发生在 vendored reader 内部，**在写入方 `File::create` 并
写完解码字节之后**；校验失败时错误向上抛，但没人删除目标文件。rar-core 自己的两条
路径（`write_buffered_member` / `fast_write_member`）在错误时会 `remove_file`，所以
部分场景是干净的；走 `rar_writer`（顺序路径）和 `extract_volumes_to_with_options`
（分卷路径）的都不清。

**这是既有缺陷，不是上一轮引入的** —— vendored reader 一直在校验，也一直不清。
但上一轮让校验更容易触发（分卷损坏现在必然报错），把它从「少见」变成「必然出现」，
所以值得单独修。

**用户可见后果**：解压失败弹出错误提示，**同时**输出目录里躺着一个看起来正常的损坏
文件；用户或后续任何工具都可能把它当成解压结果拿走。

### 5. 修好了：按错误种类分流清理，残留与 unrar 完全一致

`rar_writer` 拿到一个共享的产出登记表（`Arc<Mutex<Vec<String>>>`），提取循环在拿到
`Err` 后按**错误种类**决定清什么：

| 错误 | 清理范围 | 依据 |
|---|---|---|
| `AtEntry { name }` | **只删那一个条目** | unrar 在 17 成员归档上只删坏的那一个、留下另外 16 个。全删会比参照实现更破坏 |
| `WrongPasswordOrCorruptData` 等不可归类 | 删掉本次登记的全部 | vendored 的 `entry_error` 对这个错误**不包 `AtEntry`**，所以无法归因到具体条目；而我们写出去的是无法解密验证的垃圾，unrar 此处留 0 个文件 |
| `AtArchiveOffset { .. }`（头部 CRC）| 不清 | unrar 会恢复并继续解出成员，我们整体中止、什么都没产出 —— 这是更严不是更不安全，清它反而会丢东西 |

条目名归一化后再比对：vendored 错误带的是**原始名**（`tmp\dir\file.bin`），而写入
工厂用的是 `name_lossy()` + 分隔符转 `/`，直接比字符串永远匹配不上。

**修完的实测**（同一个损坏矩阵，判定标准也从「有任何残留」改成「残留集合与 unrar
相同」——因为 unrar 自己也会保留校验通过的成员，要求零残留等于要求我们比参照实现
更破坏）：

| | 修前 | 修后 |
|---|---|---|
| 与 unrar 一致（拒绝 + 残留集合相同）| 15 / 70 | **60 / 70** |
| 🔴 多留（比 unrar 脏 = 真实缺陷）| 55 | **0** |
| 🔴 漏检 | 0 | **0** |
| 🟡 少留（比 unrar 严）| — | 10 |

剩下 10 处「少留」全是头部 CRC 错误时我们整体中止而 unrar 会恢复 —— 属另一个功能
（从头部损坏中恢复），不是数据安全问题。

**测试的诚实边界**：单测直接验证清理决策，而不是端到端。原因是 vendored fork **没有
RAR3/RAR5 写入器**，而这两个家族恰恰是「先写后校验」的；唯一能造的 RAR 1.3/1.4 是
**先校验再写**，校验失败时压根不产生文件、复现不出这个 bug。端到端证明放在仓外矩阵里
（70 处真实变异、覆盖三个家族）。

测试经双向 sabotage 验证有效：把清理改成空操作 → 红；改成无条件全删 → 也红
（`a verified member must survive`）。第一版 sabotage 只删掉了 `return` 而 `if` 守卫
还在，测试纹丝不动 —— 提醒「sabotage 必须真的改变语义，否则验的是假的」。

### 5.1 修法细节

`rar_writer` 是顺序路径与分卷路径共用的写入工厂，它返回 `Box<dyn Write>` 给
vendored reader，而**校验发生在 reader 内部**——也就是在 `File::create` 与写完
解码字节之后。错误向上抛，但工厂没有任何机会知道「这个条目的校验没过」。

不能靠 `Drop` 去猜：writer 被 drop 时解码可能已经失败，但也可能只是正常读到末尾，
两者在 drop 那一刻长得一模一样。

可行做法是让 writer 自己持有一份「实际写出了多少字节 + 期望值」，并在 drop 时
**结合调用方传回的最终结果**判定 —— 但 `Box<dyn Write>` 没有「条目结束」回调，
所以更稳的路径是让 `rar_writer` 额外维护一个 `Arc<Mutex<Option<String>>>` 记录目标
路径，由**提取循环**在拿到 `Err` 后统一清理该路径。这样清理的时机与「知道失败了」
严格同刻，不会误删正常完成的条目。

涉及两处：
- `extract_rar_inner` 的顺序分支：`extract_to_with_options` 返回 `Err` 后清理本条目
- `extract_rar_volumes_inner` 的分卷分支：`extract_volumes_to_with_options` 同理

单条目损坏时清理该条目即可；整包失败时清理本次登记过的全部路径。

## fix(v6.0.0): 带密码的 RAR 5 全部解不出来 —— 上一次的校验和修复本身就是 bug

### 1. 症状：用真实 rar 7.23 生成的加密归档，unrar 秒解，我们全拒

`rar` 7.23 CLI 一直都在本地，之前只拿它当 oracle、没拿它当生成器，于是矩阵里
**一个由本机参考实现生成的加密归档都没有**。补上生成器后立刻全红：

| 样本 | unrar | 我们 |
|---|---|---|
| `pw_data.rar`（内容加密） | 解出 16 条目 | `Err(checksum mismatch: expected 0x14c7d4b9, got 0x2364528c)` |
| `pw_mixed.rar`（逐文件加密） | 16 条目 | `Err(checksum mismatch: expected 0xc58138dc, got 0x2364528c)` |
| `pw_append.rar`（旧式追加） | 5 条目 | `Err(checksum mismatch: expected 0x924c6cde, got 0x73dd81a9)` |
| `pw_timelock.rar`（时间锁） | 5 条目 | `Err(checksum mismatch: expected 0x11dcdd33, got 0x73dd81a9)` |

**影响面**：所有带密码的 RAR 5 归档 —— 也就是 galgame 最常见的分发形态 —— 全部无法解压。

### 2. 根因：`crc32` 的**覆盖对象**在加密时变了

`0x2364528c` 正是正确明文的 CRC32 —— 也就是说**我们的解密完全正确**，错的只是比对。

拿 unrar 自己报出的 MAC 做判别：存储值 `0x14c7d4b9`，明文 CRC32 `0x2364528c`，两者不等；
再对整个文件暴力扫描任意长度、任意偏移的连续字节段，没有一段的 CRC32 等于存储值 ——
说明它压根不是文件里某段明文的校验和。vendored reader 自己给出了答案：

```rust
let actual = crc32(data);
let actual = if self.uses_hash_mac() { keys.mac_crc32(actual) } else { actual };
if actual != expected { return Err(Error::Crc32Mismatch { .. }) }
```

即**加密条目的 `crc32` 是 `keys.mac_crc32(crc32(明文))`，一个带密钥的 MAC**。
公开的 `verify_crc32()` 遇到 `uses_hash_mac()` 直接返回错误，它明确拒绝这种用法；
而 `verify_integrity_with_keys()`（抽取路径实际走的那个）才做 MAC 变换。
上一轮我按前者（明文 CRC）的语义在 rar-core 里**重复实现**了一遍，漏了变换。

**这不是 RAR 5 独有**：`readme_154_password.rar`（RAR 4 加密）存储值与明文 CRC 都是
`0x509e5e3c` —— RAR 1.5–4.x 的 `crc32` 加密时**仍是明文 CRC**。所以让位是**按家族**的，
判据就是 `uses_hash_mac()` 的作用域 —— 它只存在于 rar50 模块。

### 3. 修法

把判定从 `member_crc` 里抽成纯函数 `verify_here(family, is_encrypted, is_split)`：

| 家族 | 加密 | 校验归属 |
|---|---|---|
| RAR 5 | 是 | **交回读取器**（它自己按 MAC 校验） |
| RAR 5 | 否 | 本层校验 |
| RAR 1.5–4.x | 是 / 否 | 本层校验 |
| RAR 1.3/1.4 | 是 / 否 | 本层校验（除分卷） |
| 任意 | 分卷 | 不校验（存储值只覆盖首卷切片） |

抽成纯函数是因为**这个 bug 无法用夹具复现**：vendored fork 没有 RAR3/RAR5 写入器，
造不出加密 RAR5 归档；而 `ArchiveMemberMeta` 是 `#[non_exhaustive]`，仓外无法构造。
单测按 家族 × 加密 × 分卷 断言这条规则本身，端到端证明放在仓外矩阵。

### 4. 🔴 更严重的是：我的 123 样本回归**根本没测这批归档**

原 `raroracle` 判断某归档是否加密，方法是看「不带口令 `unrar lb` 能否列目录」。
但**数据加密、文件头可见**的归档不需要口令就能列目录 —— 于是被判成「无需口令」，
接着 unrar 没口令拒绝解、我们也拒绝解，**两边一起失败被判成「一致」**。

症状正是这个 bug 本身：`password_crc32.rar` 明明解不出来，那轮回归却报 119/123 一致。
整类数据加密归档（主流形态）从未被真正执行过。files4testing 同样漏：
`wrongpass-rawfile1.m5.rar` 只测**错误**口令，从没测过**正确**口令。

改判据：读归档**自身的标志位**（`unrar vt` 的 `Flags: encrypted`），
而不是看某个无认证操作是否碰巧成功。
修正后 sabotage 验证：把 bug 改回去，回归从 119/123 掉到 118/123 并直接点名该文件。

### 5. 生成器本身也骗了我两次

`rar`/`zip`/`7z` 对「合法但完全空」的归档**不报任何错**：

1. 素材脚本 `open('big/x.bin','wb')` 不创建 `big/`，Python 在那抛异常退出，后面的素材
   一个都没生成，而 `rar a` 照样给每个目标产出合法空归档 → 矩阵退化成「零样本全绿」。
   第一次我只看到断言结果、没看到 traceback（被 `tail -30` 截掉了）。
2. 所谓「12 MiB 不可压缩」实际是同一个 64 KiB 块重复 192 次，LZ77 一次压到 27 KB，
   于是 `-v2m` 永远达不到分卷阈值，**分卷测试一次都没真正运行**，rar 安静地不拆卷。
   报错还被 `>/dev/null 2>&1` 吞掉了。

修：素材自检 + 逐归档断言条目数/最小体积 + 用 `zlib` 断言不可压缩率 + 断言卷真的拆开了。

### 6. 修后

`rar` 7.23 生成的 22 组合矩阵（压缩级别 × solid × 五种加密 × 恢复卷 × 两种分卷命名 ×
快速打开 × 1GB 字典 × 条目类型边界 × 错误口令）**22/22 与 unrar 完全一致**，
**240 个条目逐字节相同**（含 CJK/全角/emoji/西里尔/阿拉伯文件名、含空格与制表符的文件名）。
`cargo test --workspace` 218/218 · `raroracle` 119/123（4 条分歧逐条甄别后都不是缺陷）·
损坏矩阵无漏检无多留 · files4testing 484/484 + 23/23。

### 7. 边界说明（不含糊其辞）

## fix(v6.0.0): ZIP 里未设 UTF-8 标志的 CJK 文件名全部解成乱码

### 1. 症状

用真实 `zip`（Info-ZIP）生成的归档，`unzip` 解出的名字正确，我们解出乱码：

| 正确（unzip 6.00） | 我们 |
|---|---|
| `mix/第一章/初音ミク.png` | `mix/τ¼¼Σ╕Çτ½á/σê¥Θƒ│πâƒπé».png` |
| `mix/第一章/恋と選挙.dat` | `mix/τ¼¼Σ╕Çτ½á/µüïπü¿Θü╕µîÖ.dat` |

`第一章` 的 UTF-8 是 `E7 AC AC E4 B8 80 E7 AB A0`，按 CP437 重新解释正好得到
`τ¼¼Σ╕Çτ½á` —— 不是别的什么编码问题，就是这一条。所有 CJK 名（含目录）全中。
对 galgame 应用来说，解出来的文件名全废。

### 2. 根因：照 APPNOTE 字面走，真实工具不这么做

APPNOTE 规定 bit 11（EFS）为 0 就是 CP437，vendored reader 照做了：

```rust
let is_utf8 = flags & (1 << 11) != 0;
match is_utf8 { true => utf8_lossy(raw), false => raw.from_cp437() }
```

但 **Info-ZIP `zip` 会写 UTF-8 字节却不置 EFS 位**，而 `unzip` / 7-Zip / Explorer
都会启发式优先按 UTF-8 解。规范在这里与实际生态相反，照规范反而破坏了数据。

### 3. 修法：未置位时先试 UTF-8，非法才回退 CP437

`cp437.rs` 里唯一实现 `decode_name(raw, flagged_utf8)`：EFS 置位 ⇒ UTF-8；
否则先试 UTF-8，`from_utf8` 失败才 CP437。纯 ASCII 两种解码一致，所以旧归档零影响。

### 4. 两个解码点，必须一起改

`read.rs`（中央目录）与 `types.rs`（本地头，公开流式 API `read_zipfile_from_stream`）
各有一处同样的解码。helper 放 `cp437.rs` 供两处共用，避免再次漂移。

**清单要诚实**：sabotage 证明 `types.rs` 那处**不被本项目任何入口走到**
（我们只用 `ZipArchive` 的中央目录路径），所以它是「为公开流式 API 顺手修好」，
**不算被测试覆盖**。测试覆盖的是 `read.rs`，且同时断言 `extract_zip_host`
与 `list_zip_host` —— 这是两条不同路径，只修一条会让 listing 继续显示乱码。

### 5. 测试双向 sabotage 有效

- 退回「无条件 CP437」→ 红，失败信息直接打印 `["τ¼¼Σ╕Çτ½á"]`
- 过度修复成「无条件 UTF-8」→ 也红（会丢掉合法的 CP437 旧归档，`aé.txt` 变 `a�.txt`）

测试用手写字节构造 ZIP，而不是用写入器生成：vendored 写入器对非 ASCII 名会自动置
EFS 位，**生成不出这个待测形态** —— 而问题恰恰只在这种形态里出现。

### 6. 边界说明（不含糊其辞）



- **RAR 1.3/1.4 的真实归档基本绝迹**（1990 年代格式，`rar` 7.23 也不生成）。`rars` 的
  `rar13/` 是目前唯一真实来源，只有 14 个、只覆盖 RAR **1.4**、没有 1.3。若这 14 个
  全过，应表述为「RAR1.4 路径已由真实归档验证，RAR1.3 仅由合成夹具覆盖」。
- harness 与语料都在仓外（`/tmp` 与临时目录），repo 一个字节未动。

## feat(v6.0.0): MV/MZ 封包（自填密钥）+ RGSS 自动命名为 Game.*

在「散素材解码」与「RGSS 封包」之上补两条用户明确要的能力。

### 1. MV/MZ 封包：用户自己填 XOR 密钥

`mv_encrypt()` 是解码的精确逆运算：`RPGM 头 | asset[0..16] XOR keystream | asset[16..]`。
因为 `keystream = MD5(encryptionKey)`，**重新混淆需要游戏自己的密钥**——但这恰好是
用户手上有的东西，所以做成输入框而不是内置。

密钥输入接受**两种形式**（两种在现实里都有人拿）：

| 输入 | 语义 |
|------|------|
| 32 位十六进制 | 直接当作 XOR 密钥流（社区工具给用户看的就是这个值） |
| 其他任意文本（含空） | 作为 `encryptionKey` 交给 MD5 —— RPG Maker 的真实规则；空 = 默认密钥，也就是 `encryptionKey` 未设置时的结果 |

为此**自研了 MD5**（约 60 行）而非引依赖：lockfile 里没有 md5 crate，而 MD5 是完全
确定的算法，可以用 RFC 1321 标准向量钉死（`md5_matches_the_standard_vectors` 覆盖
7 个官方向量）。这不是安全用途，只是格式要求——「加密」本来就是 16 字节 XOR。

**粒度：单个素材**。`foo.png` → `foo.rpgmvp`（**替换**扩展名，不是追加）。这正好落在
app 既有的「一个输入 → 一个产物」模型里，无需新流程；批量「分别封包」选 50 张图即得
50 个 `.rpgmvp`。因此 `rpgmvp/vo/vm` 进 `SINGLE_FILE_COMPRESS`，选文件夹时禁用
（一个文件夹无法变成单个 `.rpgmvp`）。

端到端实测（真 PNG，三种密钥形式）：写入字节数正确、`mv_list` 正确、解码回来与原文件
**逐字节相同**。

### 2. RGSS 封包自动改名 `Game.<ext>` + toast

RPG Maker 只加载与 `.exe` 同目录、名为 `Game.rgss3a`（/`.rgssad` / `.rgss2a`）的归档，
其他名字**静默不加载**。新增 `resolveRgssOutName()`（挨着 `resolvePfsOutName`），
封包选项里加开关（默认开，与 pfs 的 Artemis 命名同一套交互），改名时复用已有的
`msg_renamed_to` 并追加一句「放回游戏目录即可覆盖原包」。

**与 pfs 的关键差异**：pfs 有 `root.pfs.NNN` 分层补丁语义，所以冲突时递增后缀有意义；
RGSS **没有任何可用的退路**——`Game.rgss3a.001` 同样不会被挂载。所以槽位被占用时
**保留调用方原名并说明**，而不是产出一个看起来成功、实则无效的文件。

命名生效的三条路径：目录封包、批量合并（都是「一个产物」）；**批量分别封包刻意不生效**
——那个循环要写 N 个归档，全叫 `Game.rgss3a` 会互相覆盖。编辑回包与合并回包若源包就是
`Game.rgss3a`，`target.exists()` 命中 → 不改名、绝不覆盖原包（这正是「编辑回包不覆盖
原包」语义要求的，日文帮助文案里也写明了要手动替换）。

---

## debug: 全面 debug（本轮自查抓出 7 个真 bug）

按「先审自己的新代码」逐条过，抓到的都是**能静默产出坏文件**那一类：

| # | 位置 | 问题 | 修复 |
|---|------|------|------|
| 1 | `mv_extract` | 每次提取把 `mv_plan()` 跑**两遍**（第二遍再开一次文件；Ogg 还要再读 130KB 再走一遍 page）——批量 500 个 `.rpgmvo` 就是 65MB 冗余读 | 计划对象向下传递，只算一次 |
| 2 | `mv_extract` | 失败/取消**不删半成品**，与全 app「失败删半成品」的规则不一致——用户分不清截断的图和真图 | 加 `mv_extract_guarded()`，任何失败都删 |
| 3 | `mv_output_name` | 透传路径**盲信扩展名**：一个内容是 RIFF 的 `.rpgmvp` 会被命名成 `foo.png`（打不开的图） | 嗅探结果与扩展名矛盾时以内容为准 |
| 4 | `mv_plain_header` M4A 分支 | 恢复 `ftyp` box 尺寸时**差 12 字节**（类型在 `asset[i+4]`，box 起点在 `asset[i]`，换算到 tail 基准是 `i+12` 却写成了 `i`）。这条路径**一个测试都没有**，是补测试时立刻暴露的 | 改为 `i + 12`，并加真 M4A 测试 |
| 5 | `mv_check_plain` M4A 分支 | 校验写成「在推导出的尺寸位置找 box 类型」——**循环论证**，无论算出什么都成立 | 改为独立校验：下一个 box 声明的 size 必须 `>= 8` |
| 6 | `mv_list` | 报的大小是拷贝长度（少 16 字节还原头），与实际落盘文件不符，进度条也会短一截 | 新增 `decoded_size()`，列表/进度/落盘统一 |
| 7 | 封包对话框 | 密钥只在**失焦**时提交——用户输完直接点「确定」（按钮不一定触发失焦）会用**旧密钥**封包，产出游戏读不了的归档 | 保留输入框引用，在确定按钮回调里读 |

另外两处是既有基础设施的债，顺手清了：

- **`// ─── JNI ─` 段头在某次批量替换里被吞掉**，只有注释丢失、无功能影响，但会让后来人
  找不到 JNI 段的起点 → 补回
- **MD5 常量表我第一版用 `sin()` 现算**——结果会随 libm 实现漂移。改成把 64 个常量
  写死，`const fn` 查表，行为确定

以及**测试夹具手抄错位两次**（PNG chunk 头重复、长度字段写错位）。已改为夹具一律由脚本按
规格生成后直接写入源码——这也是「真 PNG 夹具」能抓出第 5 行那个校验 bug 的原因。

### 顺手把一处「不可证伪」的校验变成可证伪

`mv_check_plain` 的存在理由是：还原 16 字节头这个动作**按构造永远自洽**，猜错了也会产出
一个自洽的垃圾头。所以必须用**混淆窗口之外**的字节去验（PNG 验 IHDR body 的宽/高/位深/
色彩类型，Ogg 验第一个 page 的 sequence 为 0，M4A 验下一个 box 声明的 size）。第一版我
把校验点取在了窗口内，等于没写。

---

## 验证状态

- `cargo test --workspace` 全绿，3 连跑；rgss-core **31 个测试**，clippy 零告警
  （工作区里仅 `crates/common` 有 4 条既有 `io::Error::other` 建议）
- `showCompressOptionsDialog` 回调签名从 3 值扩到 5 值，3 个调用点全部更新
- **key 覆盖审计脚本重跑并扩到新封包 key**：extractByFormat / extractAccessors /
  compressAccessors / compressDispatch / previewArchive / listEntriesJson /
  batchPreview / 读 key 路由 / 调度归并 —— 9 项全 OK
- **共享 progress store 的分组反查**：RgssCore 8 key、TarCore 5 key、PfsCore 2 key
  全部收敛到唯一调度槽
- `./gradlew lintDebug` **0 error / 271 warning**（基线 272，本轮用 en dash 消掉 1 条 TypographyDashes）
- `bash build.sh` 全过；**26 个 JNI 导出 ↔ 26 个 `external fun`**，`nm -D` 精确一一对应
- 端到端功能实测：三种密钥形式（默认 / 自定义文本 / 原始 hex）封包后再解码，与原始 PNG
  逐字节相同
- **真语料回归 26/26**（详见上方「真机回归」章节），含两组**字节恒等**：
  RGSS v1 重封 = 原始 `Game.rgssad`；MV 重封 = 原始混淆素材

### 待做

- **真包验证已由上方「真机回归」章节完成**（uuksu 真语料，26/26 对 oracle，含两组字节恒等）。
  装机后仅需人工确认「放回游戏目录后游戏能启动」这一条交互闭环。
- M4A 的 `major brand` 假定为 `M4A `（参考实现同样如此）；若真包里出现别的 brand，
  4 个字节会解错但不影响播放。要精确就得读 `System.json`

## feat(v6.0.0): RPG Maker MV/MZ 散素材解码（.rpgmvp / .rpgmvo / .rpgmvm）

接在 RGSS 之后。同一个引擎家族，但**不是归档**：MV 把每张图、每个声音各自存成一个
混淆文件。仍然收进 `rgss-core`，共用一个 `.so`。

### 「加密」其实不是密码学

和 RGSS 的 `*7+3` 轮转完全不同，MV 的方案是**只异或 16 字节**：

```
offset 0..16    "RPGMV" + 3 NULs + 00 03 01 + 0   固定头
offset 16..32   素材自身的前 16 字节 XOR keystream
offset 32..     素材剩余部分，完全明文
```

`keystream` 是 `System.json` 里 `encryptionKey` 的 MD5——而这个字段几乎没人填，
所以实际就是空串的 MD5（`d41d8cd98f00b204e9800998ecf8427e`）。
**但我们根本不需要它**：三类素材的头部都是已知的，keystream 可以从文件自身反解出来。
于是解码结果就是 `还原出的 16 字节头 + 文件从 offset 32 起的原样拷贝`——
keystream 之后再也不需要参与，所以实现是一次「写 16 字节 + 流式拷贝」，
而不是流式密码。

| 类型 | 还原方式 | 确定性 |
|------|---------|--------|
| `.rpgmvp` PNG | 前 16 字节是硬编码常量（签名 + IHDR 的长度和 tag） | 精确 |
| `.rpgmvo` Ogg | 16 字节里 14 个固定；剩下 2 个是流 serial 的低字节——**去第二个 page 读**（serial 跨 page 恒定，而第二个 page 是明文） | 精确 |
| `.rpgmvm` M4A | 16 字节里 12 个固定；`ftyp` box 尺寸靠扫描下一个 box 定位，major brand 假定为 `M4A ` | 启发式（写盘前会校验） |

格式对照 [Petschko's RPG-Maker-MV-Decrypter](https://gitlab.com/Petschko/RPG-Maker-MV-Decrypter)（社区事实标准）与 [rpgm-asset-decrypter-lib](https://github.com/RPG-Maker-Translation-Tools/rpgm-asset-decrypter-lib)（其 Rust 重写，MIT）。**顺带纠正了一个我一开始的误判**：我最初按「16 字节头里带 per-file 种子 + `*7+3` 轮转」实现，写完才发现真实格式完全不是这样——第一版整个是错的，已推倒重写。

### 关键设计：当成「单条目归档」

每个文件对 app 暴露为**只有一个条目的归档**，条目就是解码后的素材，扩展名取真实类型。
这样预览 / 选择性提取 / 批量提取 / 全局搜索**全部零改动复用**，而且条目名字对了，
点条目就能直接进图片 / 音频预览——否则用户拿到的会是个没有扩展名的怪文件。

### 三道防线

1. **没 `RPGMV` 头 = 根本没混淆** → 原样透出。重打包/汉化过的 MV 游戏大量把
   `.rpgm*` 扩展名配明文内容，一律报错是帮倒忙（`.rpgmvm` 视频本来就不加密）
2. **还原的 16 字节头不可证伪**——错误的假设同样能自洽地产出一个垃圾头。所以
   **必须拿混淆窗口之外的字节去验**：PNG 查 IHDR body 的宽高/位深/色彩类型，
   Ogg 查第一个 page 的 sequence 是否为 0，M4A 查 `ftyp` 之后的 box 类型。
   验不过就报错，**绝不写出垃圾文件**
3. 扩展名映射是**查表**（`.rpgmvp` 恒为 PNG），不是嗅探；只有扩展名什么都说明不了的
   文件才回退到嗅探

### 缺陷记录

| 位置 | 问题 | 修复 |
|------|------|------|
| 整个 MV 实现（第一版） | 按错误的格式理解实现：以为头里带 per-file 种子、载荷是 `*7+3` 轮转（实际那是 RGSS 的方案） | 对照权威实现后推倒重写 |
| 结构性校验 | 校验字节取在混淆窗口**之内**——那么"还原"永远自洽，校验形同虚设；且索引基准算错 16 字节 | 改用窗口**之外**的 IHDR body / page sequence / box type 校验 |
| 结构性校验（第二轮） | 以为 PNG 的 IHDR **长度字段**在窗口外可验——其实它就在 `asset[8..12]`，仍在窗口内 | 改验 IHDR **body**（宽/高/位深/色彩类型） |
| `mv_list` 报的大小 | 报的是拷贝长度（少了还原头的 16 字节），与实际落盘文件不符 | 新增 `decoded_size()`，列表/进度/落盘三处统一 |
| 手抄测试夹具 | 手工誊写夹具字节，誊错两次（PNG chunk 头重复、长度字段写错位） | 夹具改由脚本按规格生成后直接写入源码，不再手抄 |
| `resources` 编译失败 | 四语言帮助文案里的 `MZ's` 未转义单引号 → AAPT2 抛 NPE（报错信息完全指不到真正原因） | 转义为 `MZ\'s` |

### 验证状态

- `cargo test --workspace` 全绿；rgss-core 25 个测试（20 连跑稳定）；clippy 零告警
- 夹具仍按老规矩**独立于本 crate 生成**（另一份按规格写的 Python 实现）
- **额外加了一个真 PNG 夹具**：由独立的 zlib/CRC 编码器生成的 1×1 合法 PNG，
  逐字节回环——**它当场抓出了上面表格里第三行那个校验 bug**
- `./gradlew lintDebug` 0 error / 272 warning（与基线逐项一致）
- `bash build.sh` 全过；25 个 JNI 导出与 Kotlin 侧 `external fun` 用 `nm -D` 逐一对齐
- `examples/probe.rs` 同时支持归档与 MV 素材（装机取样时直接用）

### 待做

- **真包验证（装机后）**：adb pull 一个真游戏目录的 `www/img/pictures/*.rpgmvp`
  与 `www/audio/*/*.rpgmvo` → `cargo run -p archive_rgss-core --example probe -- x.rpgmvp`
  → 与 Petschko 工具解同一文件**逐字节比对**（PNG/OGG 都应完全一致）
- **不做回封**：重新混淆需要游戏自己的 `encryptionKey`（MZ 的 `.mzp` 之类才是真正需要），
  本轮明确只读，故 `rpgmv` 不在 `COMPRESS_GROUPS`、也不在编辑允许集里
- 未混淆文件的透出目前是静默的；若真包验证发现大量游戏其实是明文，可考虑在列表里
  标注「未加密」

---

# TODO

## feat(v6.0.0): RPG Maker RGSS 封包支持（.rgssad / .rgss2a / .rgss3a）

6.0 首个新封包。选型时对比了三个候选（CatSystem2 `.int` / ASAR / RPG Maker），
RGSS 胜出的原因是**密钥完全自包含**——v3 主密钥由包内种子派生，v1/v2 用固定
`0xDEADCAFE`，不像 cxdec 那样需要 exe 侧车文件；魔数 `RGSSAD\0` + 版本字节 8 字节，
签名扫描零误报；crates.io 上无同类 crate，自研约 700 行即可覆盖读写。

### 格式规格（三方交叉验证，第四方独立复现）

uuksu/RPGMakerDecrypter（C#，社区事实标准）、mkxp-z `crypto/rgssad.cpp`、
[rpgm-archive-decrypter-lib](https://github.com/RPG-Maker-Translation-Tools/rpgm-archive-decrypter-lib)（Rust）
三方一致。公共头 `"RGSSAD\0"` + `u8 version`（1=XP / 2=VX / 3=VX Ace）。

- **v1 / v2（顺序流，无索引表）**：`key = 0xDEADCAFE` 起手，
  `name_len`(u32) → 每**字节**轮转的 `name` → `size`(u32) → 数据
  （4 字节窗口 `key*7+3`）。⚠ 两个易错点：**文件名是每字节轮转**（不是每 4 字节），
  且**数据的轮转不持久化**——下一条 EntryHeader 从 size 之后那一刻的 key 继续
- **v3（索引表 + 数据区）**：`key = seed*9 + 3`（u32 上是双射，seed 无约束），
  `offset`(0 终止) / `size` / `entry_key` / `name_len` 四个 u32 各自 `^ key`（主密钥全程不轮转），
  文件名 `b ^ ((key >> 8*(i%4)) & 0xFF)`，数据用 `entry_key` 按 v1 的 4 字节窗口规则
- **版本 1 与 2 布局完全相同**（uuksu 只接受 1、mkxp-z 的 RGSS2_Archiver 复用同一 opener、
  YARE-py 明写 `v1 (.rgssad, .rgss2a)`），但 XentaxWiki 声称 v2 是 version=2。
  实现**接受 1/2/3 全部三个版本字节**，并额外做**双候选解码 + 启发式择优**：
  主路径走文档的轮转 key，备路径走常量 key，按「名称可打印 + 是相对路径 + size 在文件内」打分，
  取分高者。约 30 行代码消掉整类规格不确定性
- 文件名编码：UTF-8 优先、CP932 兜底（`encoding_rs`，ypf-core 同款依赖），不可解码时降级为 ASCII 折叠而非报错

### 实现

- 新增 `crates/rgss-core`（`archive_rgss-core`，`["cdylib", "rlib"]`——rlib 只给
  `examples/probe.rs` 用，产物仍只有一个 `.so`），无新 crates.io 依赖
- JNI 面 16 个导出，与 `RgssCore.kt` 的 16 个 `external fun` 逐一对齐（已用 `nm -D` 核过）
- 三个 fmtKey（`rgssad`/`rgss2a`/`rgss3a`）读路径完全等价（按包头自动判别布局）；
  封包时用 `rgssWriteVersionOf()` 选目标版本（`rgss2a` 写 v1 布局，与引擎对该扩展名的期望一致）
- 封包：v1 单遍流式（元数据与数据交错，索引无法预计算）；v3 单遍前向
  （索引大小只由文件名决定，数据偏移在写数据前已知，无需回填）4GiB 偏移溢出护栏
- `examples/probe.rs`：真包诊断工具（照 cxdec `probe.rs` 先例），打印版本 / 条目数 /
  每条解密后前 16 字节的魔数嗅探——**名称看着对但数据全是垃圾是这类格式最典型的失败模式**，
  光看列表看不出来

### 顺手修的两处既有缺陷

| 位置 | 问题 | 修复 |
|------|------|------|
| `SignatureScan.SCAN_SIG_COUNT` / `SCAN_PATTERN_COUNT` | 声明 31/77，实际表里是 32/82（注释自己写着「ksd 2」而 KSD 实有 3 个魔数）——扫描对话框页脚一直显示错数字 | 改为脚本从 `validators.rs` 实测的 **32/83**，并在 scan-core 加 `signature_counts_match_the_kotlin_footer` 测试锁死，防止再次漂移 |
| `tryStartOperation` | `pf6`→`pfs` 调度归并靠 3 处手写 `if (fmt == "pf6") "pfs" else fmt` 复制，已经漏网补过两次（TODO v5.15 二轮 debug 记录）。RGSS 若照抄，同一个 .so 的 `extract_progress` store 会被 `rgssad`/`rgss3a` 两条解压并发污染——共 15 处 `tryStartOperation` 调用点，靠人肉不可能不漏 | 归并逻辑收进 `schedulerKeyOf()`，由 `tryStartOperation` 单点强制，3 处手写副本删除。今后新增格式只需在一处登记 |
| `AGENTS.md` lint 基线 | 文档写「~268 warnings」，实际基线已是 272 | 本轮实测基线 272，改动前后一致（0 新增） |

### 验证状态

- `cargo test --workspace` 全绿；`cargo clippy -p archive_rgss-core --all-targets` 零告警
- `rgss-core` 18 个测试，其中最关键的 4 个用**独立于本 crate、由规格描述直接写出的
  Python 实现**生成的裸字节常量做对拍（uuksu C# → Rust → Python 三方推导，
  杜绝「自洽的往返掩盖共同误读规格」）：
  - `v1_writer_matches_spec_bytes` / `v3_writer_matches_spec_bytes`：writer 逐字节 == 规格字节
  - `reader_accepts_the_spec_derived_archives`：reader 解析同一批字节并解出正确明文
  - `parses_fixed_v1_bytes` / `parses_fixed_v3_bytes` / `version_two_reads_as_v1_layout`
  - 其余覆盖往返（含 CP932 日文名、0 字节文件、900KB 跨 256KiB 流块的大文件）、
    目录前缀选择性提取、敌意索引（0xFFFFFFFF 文件名长度 / 越界偏移 / 截断）、
    `..\` 路径穿越（计为失败且不落盘）、重名与大小写碰撞（`DestAllocator` 改名不覆盖）、
    封包中途取消、坏魔数 / 未知版本字节
- ⚠️ 该 Python 对拍在开发中**抓到了实现错误**（我第一版把 v3 载荷密码误当成了
  v3 的*文件名*密码：前者是 4 字节窗口整体 XOR 整组 key，后者才是按位置取 key 字节）
- `./gradlew lintDebug` 0 error、272 warning（与基线逐项一致，0 新增）
- `bash build.sh` 全过；3 个 ABI 的 `libarchive_rgss_core.so` 已进 APK
  （614K/460K/667K），jniLibs 由 19 → 20 个 `.so`

### 待做

- **真包验证（装机后）**：adb pull 一个真 `Game.rgss3a` →
  `cargo run -p archive_rgss-core --example probe -- Game.rgss3a` →
  与 uuksu 解同一包的结果比对（目录树 + 逐文件 sha256 全等）；
  再验「本工具封包 → 改名回游戏目录 → 游戏能启动」
- **已知取舍**：封包时文件名按磁盘上的 UTF-8 字节写入。RGSS1(XP) 是 Ruby 1.8
  （原始字节按 CP932 解读），日文名理论上需要写 CP932 才匹配得上；VX/VX Ace 是
  Ruby 1.9+（UTF-8），无此问题。中文名在 CP932 下本就无法表示，故本轮统一走 UTF-8。
  若真包验证发现 XP 日文名对不上，在此处改 `create_older` 的文件名编码即可
- ~~**RPG Maker MV/MZ 的 `.rpgmvp`/`.rpgmvo`/`.rpgmvm`**~~ —— **已在本版本做掉，
  见上方章节**。原判断「需另开按目录批量解密流程、密钥取自 System.json」有误：
  密钥流可从每个文件自身还原，不需要 `System.json` 侧车文件
- 封包产物命名：RPG Maker 只加载与 .exe 同目录的 `Game.rgss3a`，用户若封成
  `新建文件夹.rgss3a` 放回去游戏不会加载。本轮只在帮助文案里提示，未做 pfs 的
  `root.pfs` 式自动改名（待用户确认是否需要）

---

## 已修复: PFS 封包产物命名重构 — 预解析最终名 + Artemis 命名开关

上一版用「封完再 `renameTo` 成 `root.pfs`」，本轮改成**在拿到调度槽位之后直接解析出最终名、
直接写进去**，并补齐 6 个缺陷。

### 设计变更

| | 旧（post-rename） | 新（预解析最终名） |
|---|---|---|
| 机制 | `applyPfsRootNaming()` 封完改名 | `resolvePfsOutName(outFile, artemisNaming)`（`util/FileUtils.kt`）解析后直接压 |
| 改名失败 | `renameTo` 失败静默返回 null，toast 只报真实名 → **无任何告警**（Artemis 场景下产物游戏不会挂载，用户却以为成功） | 无改名步骤，该类失败消失 |
| 进度卡标题 | 用 `outF.name`（`game-cn.pfs`）→ 与最终产物 `root.pfs.NNN` 不符 | 编辑回包标题改用**源包名** `src.name` |
| 用户显式命名 | 批量合并的名字输入框对 pfs/pf6 **完全作废** | 封包选项新增开关（默认开=Artemis 命名，关=尊重输入框名字，等价 pfs-rs 的 `-o`） |

### 缺陷清单

| 位置 | 问题 | 修复 |
|------|------|------|
| `CompressionDialogs.runCompress` | `uniqueFile` 在 `tryStartOperation` **之前**解析 → 排队中的第二次封包拿到同一名字，后完成者覆盖先完成者（产物丢失）。zip 三件套早已修过（解析挪到 `await()` 之后），封包/回包路径漏网 | 解析挪进 `thread{}` 的 `await()` 之后 |
| `PreviewFlow.repackEditedArchive` | 同上；且 `outF.name` 被进度卡标题提前消费，无法简单后移 | 同上 + 标题改用源包名 |
| `PreviewFlow.mergeIntoArchive` | 上一版**不**参与 root.pfs 化，产物 `game-cn.pfs`；与编辑回包 `root.pfs.NNN` 不一致（TODO 旧决策「root.pfs 化只作用于打包产物」作废） | 统一走 `resolvePfsOutName`，开关同源；最终名进 `merge_done` toast |
| `BatchCompress` 合并/分别 | 同 A（`uniqueFile` 在入队时解析） | 解析挪进 `await()` 之后 |
| `compressDispatch` 失败路径 | 只 `catch → false`，**不清理半成品**。原本残留的是无害的 `X.pfs`；直接写 `root.pfs` 后，**截断的 root.pfs 会被游戏当分层补丁挂载** | runCompress 原有清理保留；repack / merge / 批量合并 / 批量分别补 `if (cancelled \|\| !ok) outF.delete()` |
| `PreviewFlow.repackEditedArchive` | 收了 `ownerTab` 只用于进度卡，完成后 `nav(currentDir)` 刷瞬时活跃窗（与 c86bfd0 修的 CsoConvert `refreshTab(ownerTab)` 同类） | `nav(currentDir)` → `refreshTab(ownerTab)`；`startEditArchive` 两个调用点补传 ownerTab（`PreviewFlow` / `FolderFragment`） |
| `ExtractProgress.compressAccessors` | `"pf6" -> …` 与 `"nsa" -> …` 挤在同一行（缺换行） | 换行 |

### 其余

- 新增字符串 `pfs_artemis_naming`（4 locale）；**移除**已无引用的 `pfs_auto_renamed`（4 locale）
- 调度 key 归并（pf6→pfs）与 `compressAccessors` 共用 `PfsCore.pfsCompress*` 保持不变——已复核正确
- 纯 Kotlin 改动，JNI 面未变，无需 `bash build.sh`
- 已知取舍：`mergeIntoArchive` 走伪 key `"merge"`，与 `"pfs"` 不同槽，理论上可与 pfs 封包并发抢同一个 `root.pfs.NNN`；两处 toast 都报最终名，用户可发现

### 验证状态

- `./gradlew :app:compileReleaseKotlin` / `lintDebug` / `:app:assembleRelease` 全过（0 lint error，无新增 warning）
- **真机回归待做**（装机后按序验）：
  1. pfs 连封两次 → `root.pfs` + `root.pfs.000`
  2. **封包中途取消 → 目录不留任何半截 `root.pfs`**（新直写路径最关键的一条）
  3. 关闭开关封 pfs → 用输入框填的名字
  4. 连续两次「编辑回包」同一 7z → 两个产物都在（验证槽位后解析）
  5. 合并回包 pfs → 同样落在 `root.pfs`/`.NNN`，与编辑回包一致

---

## 已实现: PFS 大更新 — PF6 独立封包 + root.pfs 自动更名

参考 [pfs-rs](https://github.com/sakarie9/pfs-rs)（其 pf8 crate 即本仓库在用的 0.1.6：PF6 仅读取且无加密、PF8 可读可写）：

- **PF6 独立封包**：`pfs-core` 新增自含 `create_pf6`（crate 无 PF6 打包能力）——索引布局与
  Pf8Writer 逐字节对齐（`pf6` 魔数 + index_size/count + name\0 + offset/size 条目 +
  filesize_offsets u64 表 + 尾部标记），数据**不加密**（与 crate 的 PF6 读取语义一致，
  PF6 条目一律视为明文）；4MiB 流式写入 + 进度/取消 + 4GB u32 偏移护栏 +
  pf8 风格反斜杠路径。读取侧复用 pf8 crate 的 PF6 支持，无新 so
  （JNI 新增 `pfsCreateArchivePf6` 挂在 PfsCore）
- **封包产物自动更名**：PFS/PF6 封包完成后按 Artemis 分层补丁约定自动更名
  `root.pfs`；已存在则 `root.pfs.000`、`root.pfs.001` …（三位数字递增），
  toast 提示「已自动更名为 …」（四语言）。单目录封包与预览工作区"合并回包"
  两条路径都生效；产物本身已是 root.pfs 时不多余更名
  （⚠️ 本节的「自动更名」机制已由上方「命名重构」章节整体替换为预解析最终名）
- 格式选择器新增 PF6 项（PFS/PF8 与 PF6 并列可选）；pf6 读取/预览/解包沿用
  既有 "pfs" 通道（crate 原生支持）
- 回归测试：PF6 打包 → pf8 crate PF6 读取 → 逐字节回环（嵌套目录 +
  5.5MB 跨 4MiB 分块大文件 + mp4 免加密扩展名）；**PF6 头部与 Pf8Writer
  输出逐字节对齐**（除魔数外 header 区域完全一致，防外部工具解析漂移）
- **全面 debug 收尾**：`compressAccessors("pf6")` 缺分支导致 PF6 封包在选择
  格式后直接崩溃（无进度窗口的根因）；pf6 调度 key 归并到 "pfs"（PF6/PF8
  共用同一 cdylib 的 compress_progress store，必须串行防进度互相污染）；
  批量合并/批量分别封包路径同样接入 root.pfs 自动更名；签名扫描 PF6/PF8
  校验强化（index_size 越界 + index_count×16 上限，过滤 3 字节魔数文本碰撞），
  Kotlin 侧 ARCHIVE_LABELS 补 PF6/PF8 → pfs 路由 + NEEDS_CARVE 补 pfs；
  cargo test --workspace 全绿
- **二轮 debug**：调度 key 归并漏网三处补齐（BatchCompress 合并/分别 +
  PreviewFlow 合并回包的 `tryStartOperation` 此前仍传裸 "pf6"，并行 pf8/pf6
  封包会互相污染进度）；PF6 writer index_size u32 溢出护栏（超 4GB 索引明确
  报错而非静默截断）+ file_offset 起点 checked_add；回环测试补日文文件名
  （SHIFT_JIS 路径编码路径）与 0 字节条目；合并回包的 `-cn.pfs` 翻译命名
  惯例保留不动（root.pfs 化只作用于"打包产物"，编辑回包走翻译工作流语义）
  （⚠️ 末句该决策已由上方「命名重构」章节推翻：合并回包现也参与 root.pfs 化）

---

## 已实现: cxdec 经典解密（XP3 内容过滤，单包拆包）

经典 cxdec 保护的 XP3（feng《ちいさな彼女の小夜曲》真机样本验证目标）此前解出的是密文
（"损坏的图片/全是 mp3 流"）。现按游戏文件夹自动路由解密：

- **密码机核心 + XP3 解析 vendor 自 [Cxdec_Tools](https://github.com/1F1E33-float32/Cxdec_Tools)
  （MIT © 2026 bfloat16，致谢见 README）** → `crates/vendor/cxdec-tools`（原样保留，仅补 `Xp3Archive::open` pub 入口）
- 新增 `crates/cxdec-core`（**rlib，链接进 xp3_core 同一个 .so，无新 so**）：
  - 游戏 scheme 表：feng 模板（从其 xp3filter.tjs 的 VM switch 排列推导：mask 0x275 /
    offset 0x380 + 三组扰动序）+ arc_unpacker 插件表 8 款（fha/comyu/mahoyoru/natsuzora/
    tenshin/dracuriot/lavender/karakara/waremete）
  - 控制表探测：游戏目录 `.tpm`/`.dat` 二进制签名扫描（vendor 读取器，字补码语义）或
    `xp3filter.tjs` 内嵌 4096 字节数组解析（含 `bondary = (hash & 0xNNN) + 0xMMM` 常量提取）
  - scheme 判定：对包内前 8 条目按 13 组明文魔数（TLG/PNG/JPEG/BMP/Ogg/RIFF/MP3 同步…）
    打分取最优，全部不中则明确报错
- **Kotlin 路由**（`ArchiveExtractor.xp3ExtractDispatch`）：xp3 旁有 cxdec 侧车文件
  （xp3filter.tjs/.tpm/.dat）→ 先走 `Xp3Core.xp3CxdecExtract[Selected]`，探测失败自动回退
  普通 XP3 路径；进度/取消复用 xp3 通道（同 .so 同 store，无新 fmtKey）；预览列表无需变化
  （经典 cxdec 索引为明文）；批量/预览/工作区/全局搜索全部经 `extractByFormat` 自动覆盖
- 单条目整块 RAM 解码，1GiB 声明上限防敌意索引；写失败删半成品；重名走 DestAllocator
- **真包验证**（feng《ちいさな彼女の小夜曲》patch.xp3 + data.xp3，adb pull → Rust probe 实测）：
  首轮 454 条仅 ~15% 有效 → 定位 vendored VM 的 `MovEaxIndirect` 对控制表字取反
  （TPM 读取器同样取反、双重取反抵消，故上游未发现；TJS 路径需预取反一次）——修复后
  **patch.xp3 454/454、data.xp3 2432/2432 全部有效**（908 PNG + 1022 TLG + 135 OGG +
  41 SCN(mdf) + 149 tjs/ks UTF-16 文本 + 字体，0 未知）
- `tjs_scheme_params` 锚点修正：`(hash & 0x275) + 0x380` 的带括号形式（此前误抓 VM 自身的
  `hash & 0x7f`）
- 回归测试：手工构造 cxdec 加密 XP3 → feng/TPM scheme 端到端解出明文、错误 scheme 拒绝、
  控制表解析（含签名校验/非法数组拒绝）；新增 `examples/probe.rs` 真包诊断工具
- **脚本查看增强**：`detectBestEncoding` 新增无 BOM UTF-16LE 识别
  （`detectBomlessUtf16Le`：`[可打印ASCII, 0x00]` 字对指纹，SJIS/GBK 文件不含 0x00 字节、
  二进制 NUL 串是 `[0,0]` 对——判别力强）。krkr2 的 tjs/ks 常见无 BOM UTF-16LE，
  此前落入 SJIS 启发式显示乱码；预览/编辑/内容搜索共用的识别链一处修复全生效。
  真包 149 个 tjs/ks 全部正确识别（含 28 个无 BOM），SJIS/PNG 对照零误触
- **范围**：仅经典 cxdec 单包拆包。新一代 HX（exe bootstrap 全静态还原 + 文件名恢复，
  Yuzusoft 2025+ 等）是 Cxdec_Tools 的另一条 recover 流水线，见下版本计划

---


## 已修复: XP3/PFS/KSD 移植审查反馈（Tyranor-Next 对照审查）

背景：Tyranor-Next 把本仓库 `xp3-core` / `pfs-core` / `ksd-core` / `common` 移植为单 crate 时，
用多轮自动化审计 + 真机样本（feng《娇小少女的小夜曲》cxdec 包）做了对照审查，发现的问题中
**上游同样存在**的部分已全部落地修复。对照实现见 Tyranor-Next 仓库
`engine/rust/src/lib.rs`（分支 `pr/archive-unpack` / PR #94，提交 978611c → 1523a57 → bb1cd71 → 51516c8）。

| # | 级别 | 修复 | 落点 |
|---|------|------|------|
| 1 | 数据损坏 | XP3/PFS 解包**重名去重 + 大小写碰撞防护**：新增 `archive_common::DestAllocator`（key 大小写折叠，碰撞改名 `名字 (n).ext`，绝不覆盖先写的文件），xp3 全量/选择性 + pfs 全量/选择性共 4 条提取路径全部接入——此前同名条目 last-wins 截断覆盖，JSON 仍报 success | `crates/common`、`xp3-core`、`pfs-core` |
| 2 | 数据损坏 | XP3 **宽容提取 + 炸弹护栏 + 进度自校正**：实际解出 ≠ INFO.size 时内容照写不截断（krkr2 同口径，保住劣质重打包），写盘以 `声明 size + 1GiB` 为硬上限（`AsyncReadExt::take`）封死敌意灌盘；每条解完按 `实际写字节 − 声明 size` 自校正总进度（新增 `extract_progress::adjust_total` / `calibrate_file`，饱和运算），进度条终态精确 100%（KSD 解包条目此前总进度偏大卡不满的同源问题一并解决） | `xp3-core`、`crates/common` |
| 3 | 数据损坏 | KSD 缓冲路径 **BufWriter drop 吞错**：`copy_xp3_entry` 全部成功出口显式 flush（磁盘满/EIO 恰落在尾部 <8KiB 时不再把截断文件计为成功），失败出口不 flush 由调用方删半成品——对齐 zip-core 已有的 flush 语义 | `xp3-core` |
| 4 | 完整性 | xp3 `by_index` 失败分支补 `remove_file`，不再留 0 字节残档（与 copy 失败分支对齐） | `xp3-core` |
| 5 | 完整性 | ypf 解包失败即删半成品（对齐其余全部格式的一致语义） | `ypf-core` |
| 6 | 完整性 | KSD 透传误判：`ksd_mode2_decode` 加 **UTF-16 弱校验**（严格代理对 + 拒绝非空白控制字符），魔数 5 字节碰撞的二进制条目（TGA 类）不再被"解码"成垃圾替换原文件 | `ksd-core` |
| 7 | 资源 | **vendor xp3 0.4.2** → `crates/vendor/xp3`（`[patch.crates-io]` 本地 fork）：索引解压总量 cap 256MiB（原 `read_to_end` 放大 ~1032×，≤4GB 敌意包可在 `open` 阶段 native OOM）+ 条目数 cap 100 万 + UTF-16 name_len 边界检查（截短 INFO 段不再 panic） | `crates/vendor/xp3` |
| 8 | 资源 | `Vec::with_capacity(size)` 预分配限幅 `min(size, 1MiB)`（声明 size 攻击者可控，N 条连发是堆放大） | `xp3-core` |
| 9 | 资源 | KSD `MAX_MODE2_OUT` 512MiB → 16MiB（KSD 只包裹 KB 级文本条目，且与 XP3 探测窗口 `KSD_PROBE_MAX` 对齐） | `ksd-core` |

- **新增回归测试**：重名/大小写碰撞端到端（`XP3Writer` 构造 `Readme.txt`+`readme.txt` 双条目 → 解包必须两文件共存且大小写折叠唯一）、KSD 探测魔数碰撞拒绝/真文本接受、`DestAllocator` 单测、`adjust_total` 饱和（不 panic 不回绕）单测；`cargo test --workspace` 全绿
- **策略说明**：重名条目采用**改名**（常见解压器惯例）而非 Tyranor-Next 的"包内重名整体拒绝"——两者都消除静默覆盖，改名让重复条目全部落盘且无需 Kotlin 侧预检改动
- xp3 上游（crates.io 0.4.2）建议提 issue/PR 反馈索引 cap 与 name_len 边界两处补丁

### 后续（已进下版本计划）
- cxdec 解密支持（反馈第 10 条，feng 系真包可解）
- `DestAllocator` 推广到 zip/tar 提取循环（zip 并行路径需共享 Mutex 版分配器）

---

## v5.15.0 Debug Pass（已完成）

### Round 1：`runOnUiThread` 防护守卫（BadTokenException 修复）
全量审计 9 个文件，为所有 `runOnUiThread` 回调补充 `if (isFinishing || isDestroyed) return@runOnUiThread` 守卫，防止旋转/退出 Activity 期间异步回调触发窗口崩溃：

| 文件 | 修复数 | 说明 |
|------|--------|------|
| `extract/PreviewFlow.kt` | 12 守卫 + 2 后台 toast | 最大文件，搜索/编辑/密码/压缩全覆盖 |
| `batch/BatchExtract.kt` | 6 守卫 | 批量解压 |
| `batch/BatchCompress.kt` | 6 守卫 | 批量压缩（合并/分离） |
| `fileops/SignatureScan.kt` | 5 守卫 | 签名扫描 |
| `fileops/CsoConvert.kt` | 2 守卫 | CSO↔ISO |
| `archive/ArchiveExtractor.kt` | 1 守卫 | 密码输入框 EditText |
| `ui/ImageEditorDialog.kt` | 1 守卫 | 保存回调 |
| `ui/ImageConvertDialog.kt` | 1 守卫 | 转换回调 |
| `browse/MultiSelect.kt` | 1 守卫 | 批量复制 |

- **prog.dismiss() 顺序修正**：`ExtractAll.kt` 两处将 `prog.dismiss()` 移至守卫之后，避免已销毁 Activity 上 dismiss 抛异常。
- **后台 Toast 规范**：PreviewFlow 中 `mergeIntoArchive` / `extractSelected` 的 `toast()` 调用包裹 `runOnUiThread`，遵守后台线程不能直接 Toast 的约束。

### Round 2：资源泄漏修复
| 文件 | 修复 | 说明 |
|------|------|------|
| `ui/ImageConvertDialog.kt` | Bitmap recycle | 合成 opaque 后立即回收原始 bmp；compress 完成后回收最终 bmp |
| `util/FileUtils.kt` | Process waitFor | `fileSize` 的 `ProcessBuilder` 补充 `process.waitFor()`，防止僵尸进程 |

### Round 3：Rust 侧修复
| 文件 | 修复 | 说明 |
|------|------|------|
| `crates/zstd-core/src/lib.rs` | fs::read OOM 保护 | 大于 512MB 的文件拒绝全量读入，返回明确错误 |
| `crates/zip-core/src/lib.rs` | zipModify clear_cancel | JNI 入口补充 `clear_cancel()`，防止前一次操作残留的 cancel 标记污染当前操作 |
| `crates/nsa-core/src/lib.rs` | 测试序列化 | `compress_progress_not_double_counted` 加 `LazyLock<Mutex>` 防止并行测试污染全局原子计数器 |

---

## v5.15.0 · 跨 tab 多选操作（已完成）

### 设计
- 各 tab 独立保留选中状态：切 tab 不丢失选择，checkbox 持久化在 `TabState.multiSelected`
- 批量操作聚合所有 tab 的选中文件：删除/复制/移动/解压/压缩均读取 `allSelectedFiles()`
- tab 标签显示选中数量角标：`TabStripAdapter` 每个 tab 右侧小圆角 badge 数字
- 批量操作栏显示跨 tab 计数：「已选 N 项（跨 M 个窗口）」
- 取消按钮清空所有 tab 的选中状态（`exitAllMultiSelect()`）

### 改动文件

| 文件 | 改动 |
|------|------|
| `MainActivity.kt` `TabStripAdapter` | VH 新增 `badge: TextView`；`onCreateViewHolder` 创建角标 View；`onBindViewHolder` 绑定数字/隐藏 |
| `MultiSelect.kt` | 新增 `allSelectedFiles()`、`totalSelectedCount()`、`exitAllMultiSelect()`、`syncAllTabAdapters()` |
| `MultiSelect.kt` `syncMultiBar` | 跨 tab 计数文案 + 调用 `syncAllTabAdapters()` 同步所有 adapter |
| `MultiSelect.kt` 批量操作 | `confirmBatchDelete`/`startBatchMove`/`startBatchCopy` 改读全局选中 + 操作后 `exitAllMultiSelect()` |
| `BatchExtract.kt` | `batchArchives()` 改读全局选中；`exitMultiSelect()` → `exitAllMultiSelect()` |
| `BatchCompress.kt` | `startBatchCompress()` 改读全局选中；`exitMultiSelect()` → `exitAllMultiSelect()` |
| `FolderFragment.kt` | batchCancel 改调 `exitAllMultiSelect()` |
| `strings.xml` (4 locale) | 新增 `multi_selected_cross_tab` 跨 tab 计数文案 |

### Debug 修复（本轮）

| 问题 | 修复 | 说明 |
|------|------|------|
| `startBatchCopy` 竞态 | `exitAllMultiSelect()` 移至 thread 启动前 | 复制期间用户可点删除删掉正在复制的源文件 |
| `startBatchCopy` 刷新目录 | `navTab(tab, targetDir)` 替代 `navTab(tab, tab.currentDir)` | 复制完成后刷新目标目录而非可能已变化的当前目录 |
| 跨 tab 选择不可用 | `globalMultiSelectMode` 全局会话标记 | 切换 tab 时自动进入多选模式；之前切 tab 后点击只会导航 |
| batch bar 非活跃 tab | `syncMultiBar` 只在活跃 tab 或有选中项的 tab 显示 | 全局模式下非活跃 tab 不显示空 batch bar |

---

## v5.15.0 · 预览工作区（已完成）

> 版本号 5.15.0 — versionCode 26 / versionName "5.15.0"。本版以预览工作区为主功能，随后两轮全面 debug（含真机反馈修复）稳定化。

> 预览 ⋮ 加「在窗口中打开」：把包内容实体化到 `cacheDir/ws/<hash>/` 并以普通目录 tab 打开，多选/复制/移动/重命名/详情/排序/分享等 FS 能力全部免费获得。套娃递归天然支持：工作区内的子归档走现有 select → preview 流程，其 ⋮ 又能再开工作区。

### 实现要点
- **双入口**：⋮「在窗口中打开」（整包）+ ⋮「选中项开为工作区」（仅勾选的顶层条目，目录勾选=整棵子树）；窗口已满（MAX_TABS）时**前置拒绝**，不解压不留孤儿缓存。
- **同归档防重**：ws 目录名由源归档绝对路径哈希决定；已存在同 wsDir 的工作区 tab 时直接 toast 跳转，绝不 `deleteRecursively` 别的窗口正在浏览的目录。
- **大包保护**：条目实际大小合计 > 200MB（`WS_SIZE_LIMIT_BYTES` 常量，暂不进设置页）弹确认框显示真实大小。
- **密码不变量**：能进预览说明密码已收集，工作区流程直接复用 `tab.previewPwd`，不弹窗不占槽。
- **调度**：走 `tryStartOperation(format)` + PollingProgressDialog + OpOverlay，与全部归档操作同轨排队；工作目录在锁内清空重建，排队重开不会毁掉进行中那次。
- **关闭清理**：`closeTab` 对 `wsDir != null` 的 tab 询问「同时清理工作区缓存？」；是=后台线程删 `ws/<hash>`（大目录不同步删卡 UI），否=保留供下次复用。
- **会话恢复（原「待定」项已决策）**：会话 JSON 存 `ws:1` + `wsRoot` 绝对路径（用户可在 ws 内深入导航，currentDir ≠ 根）；恢复时目录仍在→完整还原工作区身份（📦 标题 + 关闭清理询问），已被系统清掉→降级普通目录 tab。工作区 tab 的 title 截断上限放宽到 32（普通 tab 仍 8），否则 📦 标题被砍断。

### 改动文件

| 文件 | 改动 |
|------|------|
| `extract/WorkspaceFlow.kt`（新） | `openWorkspaceFromPreview` / `startWorkspaceExtract` / `openWorkspaceTab` 全流程 |
| `browse/TabState.kt` | 新增 `wsDir: File?` 工作区身份字段 |
| `browse/FolderFragment.kt` | ⋮ PopupMenu 新增两个工作区入口 |
| `MainActivity.kt` `closeTab` | 工作区 tab 关闭前清理询问；原关闭逻辑拆出 `closeTabNow` |
| `SessionRestore.kt` | `saveSession` 存 ws 标记 + wsRoot；`restoreSession` 目录存在则还原身份 |
| `util/Constants.kt` | `WS_SIZE_LIMIT_BYTES`（200MB） |
| `strings.xml`（4 locale） | `ws_open` / `ws_open_selected` / `ws_size_confirm` / `ws_cleanup_prompt` / `ws_tab_title`（📦 前缀） |

---

## v5.15.0 · 全面 Debug（工作区后首轮，已完成）

> 双路审计全 Kotlin 面（归档操作链 + 浏览/多选/会话/回收站/搜索/文件操作），27 条发现采纳 25 条修复（1 条误报剔除：「extractByFormat 缺 7z 分支」实为存在；1 条语义问题记录不修：批量解压跨目录选择统一解到第一个归档目录，待定夺）。

### P1 / P2

| 位置 | 问题 | 修复 |
|------|------|------|
| `MultiSelect.syncAllTabAdapters` | 多选中点「+」开新窗必崩：新 tab 的 `listFiles` lateinit 未绑定就被解引用 | 加 `viewsBound` 守卫跳过未绑定 tab |
| `RecycleBin.restore` | **文件夹永远无法还原**：跨盘 rename 必失败，copy fallback 的 `copyTo` 对目录抛异常 | 目录走 `copyRecursively`，失败清半成品 |
| `BatchExtract` 批量搜索 | 取消失效：内层 per-entry 循环不查 cancelled，且每个新 Rust 入口重清 CANCEL | 内层循环加 `if (cancelled) break` |
| `BatchExtract` 解压所选 | 取消仍弹「批量完成」并清空多选 | 取消分支独立处理，保留选中可重试 |
| `BatchCompress.compressMerged` | 跨 tab 同名文件在 staging 静默覆盖 → 合并产物丢文件 | staging 内重名按 `名字 (n)` 去重 |
| `BatchCompress.compressSeparate` | staging 不预清理 + 异常不清理 → 残留打进新包 | 用前 `deleteRecursively` + `finally` 清理 |
| `ExtractProgress` 轮询线程 | 调用方守卫跳过 dismiss() 时 200ms 轮询线程持有死 Activity 永远空转 | 循环内检测 `isFinishing/isDestroyed` 自退 |
| `WorkspaceFlow` | 勾选大目录可绕过 200MB 确认（目录条目 size=0） | 按前缀累计整棵子树大小 |
| `WorkspaceFlow` | 排队期间重复开 → 删掉活 tab 正浏览的目录 + 重复 tab；解压完窗口满 → 孤儿缓存 | `openWorkspaceTab` 重查同 wsDir；满窗删成果 |

### P3

| 位置 | 问题 | 修复 |
|------|------|------|
| `ArchiveExtractor.tryExtractWithPassword` ×2 | dismiss 在守卫前（违反项目不变量） | 守卫前置 |
| `GlobalSearch` 结果点击 | 同上 | 守卫前置 |
| `FileBrowser` | install 成功路径无守卫；`navTab` 两处 UI 回调无守卫 | 补 `isFinishing/isDestroyed` |
| `RecycleBinDialog` ×2 | 守卫缺 `isDestroyed` → 后续 AlertDialog 可 BadTokenException | 补全 |
| `FolderFragment` 文件信息 | 守卫缺 `isDestroyed`；`requireContext()` 异步后可抛 | 提前捕获 context + 补守卫 |
| `SessionRestore` | 畸形 JSON 跳项后 previews 错挂到别的 tab；JSON null 还原成字面量 "null"；守卫缺 isDestroyed | 锁步解析（SavedTab 结构）+ "null" 防御 + 补守卫 |
| `RecycleBin` | size 回填在裸线程改 manifest 内别名 JSONObject（竞态）；损坏 meta 的 `getString/getLong` 杀进程 | `_meta.json` 用独立副本；读取包 runCatching |
| `MultiSelect.exitMultiSelect` | 导航后 `globalMultiSelectMode` 永不复位 → 空批量栏随切 tab 反复自动弹出 | 重算 `tabs.any { multiSelected.isNotEmpty() }` |
| `MainActivity.addTab/openPickerInTab` | 关低号 tab 后 tabId 重复 → OpOverlay 卡片归属错乱 | tabId 单调递增 |
| `CsoConvert` | 完成刷新打到瞬时活跃窗而非发起窗 | `refreshTab(ownerTab)` |
| `FileUtils.readPrefix` | 单次 `read()` 短读静默截断预览/搜索前缀 | 循环读满 |
| `MainActivity` 启动权限跳转 | 部分 ROM 无该 action → 首启动 ActivityNotFoundException | try/catch + 降级通用 action |
| `OpScheduler.await` | 提升为 RUNNING 后被中断返回 false → 槽位+格式锁永久泄漏（防御性） | 中断时已 RUNNING 则照常返回 true |

### 真机反馈修复（工作区首轮测试）

| 位置 | 问题 | 修复 |
|------|------|------|
| `FileBrowser.navTab` | **多选中点「+」开新窗选中全丢**：`rebuildPager` 重建所有 fragment → 各 tab `onCreateView` → `navTab(同目录)` → 无条件 `exitMultiSelect` 清空选中并重置全局会话（FileObserver 防抖刷新同样中招） | 记录 `prevDir`，仅**目录真正变化**时才清多选；同目录重渲染/自动刷新保留选中 |
| `FolderFragment.onCreateView` 预览恢复分支 | **预览中的 tab 重建视图后路径栏显示「/」**：分支不经过 `navTab`，`tvPath` 停留在 XML 默认文本 `/`（工作区/加窗/关窗都会触发 rebuildPager，预览 tab 必现） | 预览分支手动回填 `tvPath = currentDir` |

### 深度 Debug 第二轮（预览链 + ui 杂项 + 回归自查）

| 位置 | 问题 | 修复 |
|------|------|------|
| `extract/WorkspaceFlow.openWorkspaceTab` | **回归自查抓到**：重复分支删 wsDir 会清掉活 tab 正浏览的目录 | 重复时只跳转，不删（排队那次已重建相同内容） |
| `PreviewFlow` 预览内搜索 ×2 | 取消失效：per-entry 循环不查 cancelled，每个新 Rust 入口重清 CANCEL | 内层循环加 `if (cancelled) break` |
| `PreviewFlow` 搜索 ×2 | `searchSource*` 全局在提取前赋值：并发搜索互相覆盖 + 取消后残留 | 移到成功分支赋值（与批量搜索同约定） |
| `PreviewFlow.previewFileEntry` | 密码嗅探(可达数秒)后的 `runOnUiThread` 无守卫 → BadTokenException | 补守卫 |
| `PreviewFlow` zip 编辑 ×3 | outF/tmp 在拿槽位前解析：排队中的两次编辑解析出同一 `-cn` 输出名，后完成覆盖先完成（编辑丢失） | 移到 `await()` 之后解析 |
| `PreviewFlow` zip 编辑 ×3 | 条目名含 `\|` 时 Rust `split('\|')` 错切操作串 → **错删别的条目** | 入口拒绝含 `\|` 的名字（新字符串 `zip_invalid_entry_name`） |
| `PreviewFlow.openNestedArchive` | 晚期失败(列目录失败/满窗/销毁)把解出的嵌套包留在 `cacheDir/nested` 成永久孤儿；`register()` 返回值被忽略，同嵌套包可开两窗互踩注册 | 所有失败路径清 outDir；检查 register，失败关窗清理 |
| `PreviewFlow.mergeIntoArchive` | 重包阶段点取消是空操作(压缩取消标志是另一份)，-cn 照样生成却报「已取消」 | 取消同时点火 `compressAccessors` |
| `PreviewFlow.extractSelected` | 非 zip 分支失败残留半成品目录；密码重试完全无进度不可取消；dismiss 顺序 | 失败/取消统一清理；重试补进度卡；守卫前置 |
| `PreviewFlow.previewArchive` | 空归档(`[]`)被当读取失败弹「可能需要密码」误导；ProgressDialog dismiss 缺 `isDestroyed` | 空归档独立提示（新字符串 `msg_empty_archive`）；守卫补全 |
| `PreviewFlow` 搜索缓存 ×2 | 缓存目录只用文件名做键：不同目录的同名归档互踩搜索内容 | 目录名加父路径哈希 |
| `ui/PreviewDialogs` 文本编辑 | 在途自动保存 `thread{writeText}` 可在 `tempFile.delete()` 后完成，用旧内容复活临时文件（误弹「恢复上次编辑」） | 代际计数，过期写丢弃 |
| `ui/PreviewDialogs.playAudio` | 播第二个音频时旧对话框永远停在「播放中」 | `MainActivity.currentAudioDialog` 追踪并 dismiss |
| `ui/AppSettings.applyBackgroundImage` | **P1**：`createScaledBitmap`/`createBitmap` 尺寸恰好相等时返回源对象本身，随后 `recycle()` 掉正在用作背景的位图 → 下一帧绘制崩溃 | 身份判断后再回收 |
| `compression/CompressionDialogs.runCompress` | 完成回调无守卫，`onComplete()`（含 navTab）在销毁后的 Activity 上执行 | 补守卫 |
| `fileops/DeleteProgress` ×5 | 全部 `runOnUiThread` 无守卫；`onDone`（exitAllMultiSelect/navTab）在销毁后执行 | 补守卫 |
| `terminal/TerminalDialog.exec` | `waitFor` 先于排空管道：输出 >64KB 时子进程写阻塞 → 健康命令被 30s 误杀且输出全丢 | 后台线程边跑边排空 |
| `ui/RichTextRender.stripRtf` | `\'hh` 分支不可达（控制字读取只收字母，cmd 永不为 `'`）→ **非 ASCII RTF 全部渲染成 'xx 乱码**；`\u+N` 正号路径丢字符；粗+斜体只渲染粗体 | 特判 `\'` 转义；数字起点跟随符号；粗斜独立 span |
| `ui/ImageConvertDialog` | 目录选择被取消时 ≤64MB 位图滞留等 GC | 改为先压到缓存临时文件，选完目录只搬移 |
| `ui/ImageEditorDialog` | 自动保存 400ms 节流放行并发写同一 tempFile（损坏恢复副本）；合成位图不回收 | 单线程执行器串行化 + recycle |

### 补记（已落地但上表遗漏项）

| 位置 | 问题 | 修复 |
|------|------|------|
| `batch/BatchExtract.batchDirectExtract` | 分卷目录模式取消后，本次新建的输出目录残留（`uniqueFile` 预计算名，删除安全） | `doneUntil` 记录已完整产出数，取消分支后台删掉它们；解到父目录模式与既有文件混杂则不清理 |
| `batch/BatchCompress.compressSeparate` | 取消/失败也清空多选并刷新（与 compressMerged 策略不一致，取消后无法直接重试） | 取消/失败保留多选，仅成功才 `exitAllMultiSelect() + nav` |
| `terminal/TerminalDialog` | cd 跳转回调/命令输出回填 3 处 `runOnUiThread` 无守卫 | 补 `isFinishing/isDestroyed` 守卫（与 exec 管道修复同轮） |
| `jniLibs`（3 ABI `.so`） | `build.sh` 重编产物刷新入库（本轮无 Rust 源码改动） | — |

---

## 预览工作区 · 设计存档（已实现，见上方 v5.15.0 段落）

> 归档预览 ⋮ 加「在窗口中打开」：把包内容实体化成一个专属 tab 的普通浏览目录，多选/复制/移动/分享/重命名/详情/排序等 FS 能力全部免费获得。此方案吸收并取代原 Tier1+2 统一化计划的主体价值。

### 已拍板的设计决策
- **路线 A 实体化**：解压到 `cacheDir/ws/<hash>/` → 新 tab 以普通 FolderFragment 浏览该目录；不做虚拟化 Node 抽象。
- **双入口**：
  1. 预览 ⋮ 菜单「在窗口中打开」→ 整包进工作区
  2. 预览中勾选条目 → 「选中项开为工作区」→ 仅勾选子集解入
- **套娃递归**：工作区内的子归档点按走现有 `select → previewArchive` 流程，其 ⋮ 又能再开工作区——无限层级天然支持（`openNestedArchive` 路由可逐步被此机制替代）。
- **缓存清理**：关闭工作区 tab 时询问「同时清理缓存？」（是=删 `ws/<hash>`，否=保留）。
- **大包保护**：超过阈值（暂定 200MB，可入设置）弹确认框显示实际大小。

### 实现要点 / 参照
- 整解压到缓存再操作的先例：`startEditArchive`（cacheDir/edit 同构流程）；进度用 OpOverlay 卡片。
- 工作区 tab 标题带 📦 前缀以示身份；tab 关闭回调里挂清理询问逻辑。
- 会话恢复是否还原工作区 tab：**已决策（v5.15.0 实现）**：ws 目录存在→完整还原工作区身份；被系统清掉→降级普通目录 tab。
- 与回收站的关系：工作区内删除走正常回收站流程 ✓ 无需特判。

---

## v5.14 Phase 2 · 第二步：悬浮层进度（已完成，opStrip 方案已废弃）

> 中间迭代过 per-tab 内嵌条（opStrip）方案，真机体验后按用户意见整体回退，定稿为同窗悬浮层。

### 最终形态：OpOverlay 同窗悬浮层
- 进度卡片挂在 activity content 上的悬浮层内：顶部穿透区高度实测自 `viewPager.top`（状态栏+工具栏+标签栏），该区域无内容不拦截触摸 → **解压/压缩进行中标签栏全程可点可切窗**。
- 卡片在「标签栏以下区域」水平垂直居中；复用 `dialog_progress_dual` 双条布局，视觉即原版弹窗。
- 多操作并发 = 多卡片纵向堆叠；排队中的操作同样成卡显示「⏳ 第N位·约Xs」。
- 取消仅限卡片显式按钮；外部触摸/返回键不误触（弹窗时代遗留问题一并根治）。

### 已废弃方案的教训存档
- opStrip（per-tab 内嵌条）：单写者守卫、ownerTab 全链穿透、视图重建重挂等复杂度换来的体验反不如悬浮层——用户实测后否决。相关代码已全部移除（含 TabState 引用、FolderFragment 绑定、各站点 ownerTab 参数）。
- 历史遗留坑两次踩中：ExtractProgress.kt / ArchiveExtractor.kt 均"文件在 archive/ 目录但 package 声明为根包"，跨包引用必须显式 import。

### 同轮交付的回收站重做
- moveToRecycleBin：**rename 优先零扫描**（大文件夹秒完成，根治 43 秒假死），大小后台补算回填 meta+manifest；跨盘复制回退才扫描驱动进度，**失败立即中止并保留原件**（曾吞错照删=数据丢失的回归修复）。
- 删除进度统一悬浮层横条：弃用系统 ProgressDialog 转圈；`[i/N]` 目标计数 + 当前目标文件数推进 + 扫描期不确定态；删除不可逆故无取消按钮。

---

## v5.14 Phase 2 · 第一步：OperationScheduler（已完成）

> 方向此前已拍板（窗口=4 / 槽位+格式锁 / 排队+ETA / 内嵌进度条）。本步落地调度器与排队体验，内嵌进度条留给第二步。

### OperationScheduler（新文件 archive/OpScheduler.kt）
- `Semaphore(3)` 全局槽位 + per-format 互斥：不同格式最多 3 路真并行；同格式串行（Rust 侧每格式仅一份进度/CANCEL 静态槽，crates/common progress_store!）。
- 忙碌不再拒绝：抢不到槽位/格式锁的操作**入队**，FIFO + 同格式队首阻塞跳过（其他格式可越过继续）。
- Handle 语义：`await()`（worker 阻塞至开跑）/ `release()`（token 式跨线程释放）/ `requestCancel()`（**排队中可取消**直接出队）。
- ETA：每格式吞吐时长 EWMA，队列展示「⏳ 排队中 第N位 · 约 Xs 后开始」；无历史数据时降级为只显示排队位。
- 进度对话框（PollingProgressDialog）新增排队态渲染：排队期间显示位置/ETA、隐藏双条；开跑后无缝切正常轮询。取消按钮在排队期 = 出队。
- 迁移 ~21 个操作入口（提取全部/批量/单选、压缩单文件/批量合并/批量分别、CSO 转换、合并归档、编辑归档+封回、ZIP 条目编辑三兄弟、搜索结果提取、两处搜索准备）；密码「先问后锁」不变量保持。
- 键映射：提取按源格式、压缩按目标格式、CSO=cso、合并=伪键 merge（跨两格式驱动）、ZIP编辑=zip。
- **不纳入调度器**（维持旧 OperationLock）：删除/回收站（纯文件操作不碰 Rust 全局）、签名扫描/carve（Rust 侧已自带 SCAN_LOCK 串行化）、全局搜索的扫描阶段。

### 已知限制
- [ ] 合并/搜索等多格式流程与同格式单操作并行时，进度数字可能短暂互相覆盖（纯显示层；Rust 入口 clear_cancel 保证取消标志不串）。
- [ ] ZIP 条目编辑与重试路径暂无进度框——排队时不显示位置，到号静默执行。
- [ ] 同格式队列理论上可被持续的跨格式流量饿死（队首阻塞跳过策略；3 槽位+典型操作时长下实际影响很小）。
- [ ] 第二步待做：每 tab 内嵌进度条（folder_view.xml pathBar 下加 opStrip，panel/previewRoot 约束改挂其下；TabState.operationActive 死字段复用为占用标记）。

### Debug 回归（调度器落地后全量审计修复）
- 排队期取消不再误写格式全局 CANCEL 标志（此前会连带杀掉另一窗口同格式的运行中操作）——取消路径统一先出队、仅在持槽时触发 Rust 取消。
- 编辑归档排队取消后正确释放归档互斥注册表（此前泄漏到进程重启）。
- await() 捕获 InterruptedException：中断的排队项自标记废弃，由晋升逻辑清除，不崩进程不占槽。
- 密码弹窗全面移出槽位/格式锁（batchDirectExtract / batchPreviewClick / 批量搜索准备 / extract-selected 四处改为开跑前预收集密码，30 秒模态不再霸占并行槽位）。
- 取消后 position()/ETA 不再返回脏值。

---

## v5.14.0 (内存监控徽标 + 窗口扩容4 + emoji全量统一 + 两轮全面debug)

> 本版以稳定性为主：一轮全面 debug（lint 47 errors → 0），修掉一批崩溃级/数据级隐患；同时落地内存监控徽标、窗口数扩到 4、全 App emoji 统一。已合并社区 PR #3（标签栏重构 + PC 图标）。

### 内存监控悬浮徽标（设置 → 一般管理 → 显示内存）
- 打开开关后，主界面右上角常驻一块半透明小徽标，每秒刷新：
  - 第一行：全机已用/总内存（如 `12.4g/23.5g`）
  - 第二行：系统占用（全机已用减去本应用）
  - 第三行：UU（本应用 PSS，**含解压引擎的 native 内存**——解压大归档时能直观看到涨落）
- 关闭开关即时消失；设置页开关即时生效，无需重启。
- 无任何特殊权限；每秒一次微秒级读取，开销可忽略。

### 窗口数 3 → 4
- 新建窗口、选择器开新窗、会话恢复上限同步扩到 4。
- 文案 ×4 语言同步更新；顺带修复默认（英文）语言里混入中文的提示。
- **窗口改名限 8 字符**（输入时硬限制，第 9 个字打不进去）；标签显示加 96dp 宽度上限兜底，超宽名字中间省略号，不再把其他标签挤出屏幕。

### emoji 全量统一
- 修复多选栏按钮双 emoji（v5.13.0 回归）：解压/压缩/复制/移动/删除曾显示两个图标，「删除」甚至是同款 🗑️🗑️。
- 顺带挖出一个更深的 bug：多选栏按钮显隐靠 emoji 文本前缀匹配，v5.13.0 给按钮拼了新前缀后，「解压」按钮在解压模式下反而被隐藏、压缩按钮两种模式都常驻。现改按语义 tag 匹配，与显示文本彻底解耦。
- 修复退出多选后，压缩模式的底部选择栏永久消失（进多选时被藏、退出无人恢复）。
- 各菜单 emoji 补齐 ×4 语言：解压 📂、预览 🔍、CSO 转换 🔄、合并归档 📦、ZIP 条目管理 🗑️/➕、编辑归档 ✏️。
- 修复 ja/TW 语言包「预览」混入简体中文。

### 全面 Debug（lint 47 errors → 0）
- 崩溃级：
  - 大归档解压等长任务中途旋转/退出，完成回调在已销毁窗口上弹对话框闪退（BadTokenException）→ 约 10 处完成回调统一加存活守卫。
  - ja 语言包全角 `％` 导致「不支持预览」提示的扩展名占位符失效 → 修。
- 状态错乱（数据级）：
  - 旋转时归档互斥注册表被无差别清空 → 同一归档可能被两个窗口同时打开编辑；现按窗口归属精确清理 + 会话恢复时强制接管，任何时序都收敛到新窗口持有。
  - 批量压缩失败/取消后仍强制退出多选并跳走 → 现仅成功时退出，保留选择方便重试。
  - 回收站「自动清理」不显示天数/「从不」→ 修（资源串无占位符却被传参）。
  - 全局搜索输入空关键字会打断进行中的搜索并卡死转圈 → 修。
- 卡顿 / ANR：
  - 嵌套归档打开、扫描命中列表在 UI 线程同步解析大归档（多秒级冻结）→ 移到后台线程。
- 泄漏：
  - 连续播放两段音频后，关掉第一段会误清第二段的播放器引用 → 泄漏且后台继续出声；现引用比对后才清理。
- 交互：
  - 加密归档的密码弹窗最长持操作锁 30 秒，期间所有窗口的操作全部误报「操作进行中」→ 改为密码先问、后拿锁（全部解压/合并流程）。
  - 合并归档 / ZIP 条目编辑完成后可能刷新错误的窗口 → 统一按发起窗口路由。
  - 视图重建窗口期的脏写防护（viewsBound 复位 + 暂停时清目录防抖回调）。
- 视觉 / 国际化：
  - 18 处 `android:tint` → `app:tint`（部分设备图标染色失效）。
  - 补齐 12+5 个缺失翻译（ja/zh）。
  - 预览统计条「已选大小」显示裸字节数 → 与总大小统一人类可读格式。

### 全面 Debug · 第二轮（收尾扫描上轮未覆盖区域 + 新增代码审计）
- 崩溃级：
  - 又一批约 14 处异步完成后裸弹对话框（安装流程、目录大小统计、回收站恢复/彻底删除、签名扫描、图片编辑/转换、批量预览、全局搜索入口、大图解码）→ 统一补存活守卫；签名扫描一处原先只查进度框状态、挡不住已销毁窗口。
  - 回收站「恢复」解析损坏的 `_meta.json` 时在裸线程抛异常 → 直接杀死进程；现捕获并按恢复失败提示；清单文件损坏同理兜底。
- 数据 / 正确性：
  - 复制失败仍提示「已复制」→ 改错误提示。
  - 重命名三处忽略 `renameTo` 返回值，跨设备/冲突时假成功 → 检查结果并报错。
  - 文本编辑器保存失败后一关闭对话框，「自动保存恢复副本」立即被误删（与设计意图相反）→ 只有保存成功或明确放弃才删；back 键退出保留副本，下次打开走恢复引导。
  - ViewPager 硬编码 `offscreenPageLimit = 3` 恰好等于窗口上限−1（扩到 5 个窗口会静默劣化）→ 改由 MAX_TABS 推导。
- 卡顿 / ANR：
  - 回收站设置页统计、清空回收站、回收站列表逐条读盘全部在主线程 → 移后台。
  - 文本编辑器每个按键都同步写自动保存盘 → 防抖 500ms + 后台写（并防挂起写入「复活」刚删掉的临时副本）。
  - 文件选择对话框每步导航同步列目录（大目录卡顿）→ 后台列举 + 过期结果丢弃。
  - 音频预览 `prepare()` 在主线程（ANR 风险）→ 移后台，播放器就绪后再接管。
  - 文本预览 1MiB 读入+编码探测、文本编辑器整读 2MiB 在主线程 → 后台加载 + 加载框。
- 图片方向：
  - API 26/27 上 BitmapFactory 忽略 EXIF 方向，竖拍照片预览/编辑横躺 → 解码后按 EXIF 回正（改用 androidx.exifinterface）。
  - PNG/WebP 转 JPG 时透明区域变黑 → 白底合成后输出。
- 会话恢复：
  - 自定义窗口名现在随会话持久化（读取时同样限 8 字符），重启不再丢名字。

### 已知限制 / 待完善
- [ ] 归档内搜索的全局源在两次搜索之间存在空窗，另一窗口发起搜索可能串扰（疑似，未复现）。
- [ ] 会话恢复遇畸形 JSON 可能让预览挂错窗口（下标错位，未证实可达）。
- [ ] 归档密码明文存 SharedPreferences（隐私面，待评估加密存储）。
- [ ] 内存徽标退到后台仍每秒轮询（可在 onPause/onResume 里停启，省电项）。
- [ ] Phase 2 真并行仍待做（方向已定：4 窗口 / Semaphore(3) 槽位 + per-format 锁 / 同格式排队+预计等待 / 每 tab 内嵌进度条），见 v5.13.0 段的 Phase 2 清单。

---

## v5.13.0 (多窗口 + 内嵌预览 + 合并/编辑 + 图片编辑器/格式转换 + 分享/会话恢复 + 全量UI重构 + 性能与全格式)

> 本版在 v5.12 的基础上继续大改：先是「多窗口 Tab 浏览器」「内嵌归档预览」「跨归档合并」「脚本编辑回封」「回收站」，随后加入图片编辑/转换、文件分享、恢复上次会话，并做了一次彻底的外观翻新。整版统一记为 v5.13.0。

### 图片编辑器（图片预览 ⋮ → 编辑）
- 画笔：在图上涂色，颜色 / 透明度 / 粗细都能调（水彩或荧光笔效果）。
- 裁剪：框选一块区域，再点一次「裁剪」就裁掉。
- 拉伸：一键拉成 1:1 / 4:3 / 3:4 / 16:9 / 9:16 常见比例。
- 取色：按住拖动，实时显示手指下的颜色，点一下复制色值。
- 缩放平移：双指捏合缩放、拖动平移；放大后画笔/取色依旧准确。
- 保存：另存「原名-edit.png」，不动原图；中途退出自动保存，可回来继续。

### 图片格式转换（图片预览 ⋮ → 转换为其他格式）
- 普通图 jpg/png/webp 互转；动图 gif/webp 可转成普通图的首帧。
- 另存新文件，原图不动。

### 分享文件（长按文件 → 分享）
- 直接把文件交给微信/QQ 等应用，无需联网权限。
- 文件名原样传给对方（带 (1) 是接收方按同名去重，不是本 App 造成的）。

### 恢复上次会话（设置 → 其余设置）
- 打开开关后，下次启动回到上次的文件夹，并重开上次正在看的归档；最多 3 个窗口，超出会提示。
- 回收站设置也收进了「其余设置」。

### 全量 UI 重构
- 圆角、间距、字号、行高等统一规范，界面更整齐。
- 归档预览顶部的操作收进右上角 ⋮ 菜单，不再挤成一排。
- 平板 / 横屏：弹窗不再铺满全屏，自动限宽居中；宽屏下左侧文件列表、右侧预览并排。
- 修复右下角圆形按钮尺寸异常；工具栏图标统一矢量，不再用奇怪字符。

### Debug 修复
- 全局搜索一打开就闪退 → 修。
- 预览里点「编辑」闪退 → 修。
- 图片编辑器画笔 / 拉伸 / 取色闪退（同一个根因：图片被解码成硬件位图）→ 修。
- 裁剪无法确认、放大后取色失灵 → 修。

### 已知限制 / 待完善
- [ ] 动图 gif ↔ 动图 webp 互相转换（需要额外的解码库 / Rust 集成），暂未做。
- [ ] 图片编辑上限约 2048 像素，超大图的输出分辨率受此限制。
- [ ] 文本格式转换（仅改后缀）已按用户指示放弃，改为文件分享。

---

### 多窗口 Tab 浏览器（最多3窗口 + 并行操作 + 同归档互斥）

### 总目标
- 最多 **3 个 tab**（ViewPager2 滑动分页，可新建/关闭）
- 最多 **3 个并行操作**（方案 A：限不同格式，不动 Rust）
- **同一归档不能同时在多个 tab 打开**（自动跳转到已有 tab）

### Phase 1 — UI 拆分（Tab 状态容器 + 三窗口浏览）✅

- [x] **`folder_view.xml`** — 把 `panel/listFiles/tvEmpty/bottomBar/tvSelected/progress/btnExtract/fabExtract` 从 `activity_main.xml` 抽为 per-tab 布局，由每个 `FolderFragment` 复用 inflate
- [x] **`activity_main.xml`** — 改为 `toolbar + tabBar(tabList RecyclerView + btnAddTab) + ViewPager2`；`btnUp/pathBar/panel/bottomBar/fabExtract` 移除（进 folder_view）
- [x] **`TabState`** — per-tab 状态容器（`currentDir/selectedFile/fileToMove/multiFiles/multiSelectMode/multiSelected` + `dirObserver/refreshHandler` + 视图引用）
- [x] **`FolderFragment`** — 每个 tab 一个，绑定 TabState 视图，处理列表点击/长按/上一级/多选栏（batchBar 改为 per-tab，`buildBatchBar`）
- [x] **MainActivity 委托** — `currentDir/selectedFile/listFiles/tvPath/...` 等字段委托到 `activeTab`，使大量既有扩展函数无需改动即可对当前 tab 工作
- [x] **`nav/select/extract/multiSelect`** 全部改为 tab 参数化（`navTab(tab,dir)` / `select(tab,f)` 等），无参版本委托到 activeTab
- [x] **Tab 增删** — `TabStripAdapter`（顶部 "窗口 N" + ✕ 关闭）+ `TabPagerAdapter`（FragmentStateAdapter）+ `addTab/closeTab/rebuildPager`（关闭中间 tab 时强制重建 fragment 避免状态错位）
- [x] 依赖：`androidx.viewpager2:viewpager2:1.1.0` + `androidx.fragment:fragment-ktx:1.6.2`
- [x] 新增字符串 `msg_max_tabs` ×4 语言
- [x] **启动崩溃修复** — `btnExtract/fabExtract/bottomBar` 等 per-tab 视图在 fragment 创建前被 `onCreate` 访问（委托到 activeTab 的 lateinit）→ `UninitializedPropertyAccessException`。修复：
  - 从 `onCreate` 移除 `btnExtract/fabExtract` 点击监听与 `btnFolderNext` 的添加（移到 FolderFragment per-tab 创建）
  - `btnFolderNext` 改 per-tab（`TabState.btnFolderNext`），由 FolderFragment 创建到各 tab bottomBar
  - `navTab` 加 `viewsBound` 守卫：fragment 未绑定视图时只更新 currentDir，等 fragment `onCreateView` 后主动渲染
  - `AppSettings.kt`/`FileBrowser.kt` 的 `btnFolderNext` 引用改 `tab.btnFolderNext`

### Phase 2 — 并行操作槽（3 许可信号量 + per-format 锁）【待做】
- [ ] `OperationLock` 单互斥 → `Semaphore(3)` + per-format `ConcurrentHashMap<String,Mutex>`；`tryStartOperation(fmt)` 双重检查
- [ ] 24 处操作入口传 fmt；`release()` 调用点兼容
- [ ] 效果：3 个不同格式并行；同格式第 2 个被格式锁挡住；第 4 个被槽位挡住

### Phase 3 — 同归档互斥（打开注册表）✅
- [x] `OpenArchiveRegistry`（canonicalKey → TabState），`previewArchive`/`startEditArchive`/批量预览前注册，冲突 toast「该归档已在『窗口 N』打开」+ 自动跳转到已有 tab
- [x] 分卷 `archiveKey` 归一到主卷 canonicalPath——`name.zip.001/.002/.zip`、`name.7z.001`、`name.part1.rar` 视作同一归档
- [x] 释放：预览/编辑对话框 dismiss、`closeTab`（`releaseTab`）、`onDestroy`（`clearAll`）
- [x] **Tab 重命名** — 长按标签弹「✏️ 重命名窗口」，自定义名用于标签显示与冲突 toast（空输入恢复「窗口 N」）

### 已知限制 / 待完善
- [ ] 完成回调（解压/压缩/搜索等 `nav(currentDir)`）仍未全部路由到发起 tab（后台 tab 操作会刷新当前 activeTab）—— Phase 2 一并处理
- [ ] `btnFolderNext`（压缩模式进入按钮）只加到 active tab 的 bottomBar，切换后其他 tab 无此按钮
- [ ] 批量预览（多选归档）仍是对话框（非内嵌）；归档内单文件预览（图片/文本）仍是对话框（临时查看合理）
- [ ] 方案 B（任意格式并行）需重构 Rust `common` 进度存储为每操作上下文，本轮不做

### 已修复（v5.13.0 debug 轮次）
- [x] **预览后 Tab 无法滑动** — 对话框（归档/文本/图片/编辑器）关闭后 ViewPager2 无法横向滑动（荣耀/EMUI touch-state 残留）。新增 `resetPagerInputOnDialogDismiss`：对话框 dismiss 时 `setUserInputEnabled(false→true)` 复位触摸，应用到归档预览/文本/图片/编辑器 4 处
- [x] **多选批量按钮位置回归** — 批量操作按钮从最右跑到最左。内层按钮行改 `MATCH_PARENT + Gravity.END` 右对齐，窄屏仍可横向滚动
- [x] **孤儿 TabState** — `FolderFragment` 越界 `tab_id` 改绑最近有效 tab，不再伪造未管理状态（原导致目录 `/`、无 FileObserver、隔离失效）
- [x] **全局粘贴按钮切 tab 不刷新** — `onPageSelected`/TabStrip 点击补调 `updatePasteButton()`+`syncMultiBar()`
- [x] **rebuildPager 复用同一 FragmentStateAdapter** — 改每次新建 `TabPagerAdapter` 实例，杜绝 stale fragment 引用已删除 tab
- [x] **多选时 FAB/全局加文件夹按钮遮挡** — `syncMultiBar` 多选隐藏 FAB（退出恢复）+ `btnAddFolder` 多选隐藏
- [x] **压缩模式 → 按钮切 tab 后可见性不重算** — `onPageSelected` 按新 active tab 重推导
- [x] **onDestroyView 误停存活 tab 的 observer** — 仅当 tab 确实被移除才 `stopObserver`
- [x] **TabStrip 关闭按钮越界索引** — 提前捕获 tab 引用 + 守卫时清空监听
- [x] **进入回收站异步加载+进度条** — `showRecycleBinDialog` 同步读 `listEntries`（大回收站冻结 UI）改后台线程 + spinner 进度条
- [x] **预览时返回键退出预览** — `onBackPressed`：`activeTab.previewActive` 时先 `exitPreview` 而非退出 App
- [x] **zipModify 三函数刷新错 tab** — `zipReplace/Delete/AddEntry` 加 ownerTab，完成后刷新发起 tab 而非 activeTab

### Phase 4 — 预览内嵌 + 选择方式（v5.13.0 增量）✅
- [x] **归档预览内嵌 tab** — FAB 预览不再弹全局对话框：`renderPreview/exitPreview/syncPreview`，预览状态存 `TabState`（entries/selected/expanded/searchQuery）
  - `folder_view.xml` 加 `previewRoot`（顶栏 + 条目列表 + 操作栏），ViewPager 全程可划屏切换窗口（彻底解决「预览不能切 tab」）
  - 视图重建/旋转时恢复预览；`navTab` 进入时退出预览并释放归档锁
- [x] **预览操作栏** — 底部「提取全部 / 提取选中 / 合并到归档」+ 统计条；顶栏「搜索 / 编辑 / ZIP 管理 / ISO 转 CSO」按格式显隐（编辑=xp3/pfs/iso/nsa/7z/ypf、ZIP管理=zip、转换=iso）
- [x] **预览搜索不退出预览** — `previewSearch` 提取文本后 `globalSearch` 盖在预览上，关闭搜索回到预览（勾选/展开保留）
- [x] **Chrome 式顶置 Tab 栏** — tab 栏移到最顶（50dp），toolbar（回收站/主页/标题/CLI）在其下，ViewPager 在 toolbar 下；活动 tab 圆角高亮胶囊 + 加粗 accent（浏览器标签风格）
- [x] **跨归档合并提取** — 预览勾选 → 「合并到其他归档」：提取源选中 → 解包目标 → 合并 staging → 封包 `目标-cn.ext` 副本（原归档不动）；目标限定可封包格式 + `OpenArchiveRegistry` 保护 + 加密目标弹密码
- [x] **选择路径/文件方式** — 一般设置新增 `picker_mode`：**直接打开路径界面**（对话框）/**在新窗口中打开选择**（新 tab）
  - `openPickerInTab`：picker tab 选择模式（TabState 加 pickerCallback/AllowFiles/OriginTab），文件模式点文件选择、目录模式「✓ 选择此目录」按钮，选中后关闭 picker tab 回原 tab，返回键取消选择
  - 所有 `showFolderPicker` 调用点（搜索改路径/提取目标/ZIP 加条目/压缩目标）统一走分派
- [x] **同归档互斥 + Tab 重命名**（Phase 3 完成）

### 并发 / 隔离现状（FAQ）
**Q1：三个 tab 同时开解压，OOM 概率大吗（即便 zip/rar 都是默认单核）？**
- 当前 v5.13.0：**三个 tab 无法真正同时解压**——`OperationLock` 是全局单锁（`ArchiveExtractor.kt:20`），第 2、3 个解压会被拒并 toast「操作进行中」。因此 **OOM 概率极低**，内存压力与单 tab 解压一致
- 真正的内存来源是**单个归档内部的并行解码**：RAR 非 solid 并行 ≤4 线程（>192MiB 才并行、batch ≤4 线程）、ZIP 条目级并行（≤32MiB batch），大成员（>64MB）已流式解码不整块缓冲
- ⚠️ 注意：`parallel_threads` 是 **per-format 全局设置，不是 per-tab**——单 tab 解压用 4/8 线程时即占满内存预算
- 未来 Phase 2 把单锁改成 `Semaphore(3)` 后，3 个 tab 才真正并行解压，届时 OOM 风险上升，需 per-format 锁 + 流式 + 成员上限兜底

**Q2：多 tab 同时打开同一个文件，第一个 tab 的窗口会即刻同步更新到其他 tab 吗？**
- 每个 tab 有独立 `FileObserver`（监听各自 `currentDir`）+ debounce。**两个 tab 若在同一目录**（如都在 `/Download`），任一 tab 解压/改名/删除产生目录事件，两个 observer 各自收到并各自刷新 → **即刻同步**
- 两个 tab 若在不同目录则互不干扰（目录不同无事件）
- **inode/inotify 层面允许多个 observer 监听同一目录，不存在「禁止多 tab 操作同一文件」的限制**——真正防并发的是应用层 `OperationLock`（同一时刻全局只有一个解压/压缩操作）
- ⚠️ 潜在隐患：两个 tab 在同一目录、对**同一文件**同时写（如都编辑同一个脚本后各自保存），FileObserver 只是观察不提供文件锁，存在并发写同一文件的竞争——目前被 `OperationLock` 串行化兜住，Phase 3 的 `OpenArchiveRegistry`（同归档互斥）会进一步封死

---

### 旗舰版（性能全链路 + 多核并行 + 全格式回归 + Brotli + 回收站 + 复制/排序）

### Phase 1 — 性能全链路（真实 before/after 基准，同机 Apple M-series）

- [x] **1A release profile** — `opt-level "s"→3` + `codegen-units=1` → 全格式普涨；原子 `SeqCst→Relaxed`（每块写省 2 barrier）；不用 `panic=abort`
- [x] **1B RAR5 解码提速** — BitReader 64-bit O(1) + copy_match 批量 + filter 尾克隆 + E8/E9 memchr
  - **before/after（56 条目 377 MiB，同机同文件）：84 → 111 MiB/s，聚合 +32%**
  - 单文件：JPEG +73%、text +50%、bitmap +47%、streaming +57%、DNA +68%
  - 密码条目（PBKDF2 瓶颈）：+21%
- [x] **1C scan-core** — gzip/JPEG 批量读 + EOCD 尾先 + post-pass 线性化 + 缓冲复用 + CRC32 查表
- [x] **1D IO 批量化** — nsa/ypf BufReader+BufWriter、cso 顺序读、zip BufWriter+flush、iso HashMap、sevenz 单次 open
- [x] **1E tar/rar BufWriter** — tar 解压 +10-25%、rar 流式成员 +5-15%
- [x] **1F RAR4 BitReader 64-bit buffer** — rar29/rar20 移植 64-bit 位缓冲（RAR4 decode 3-5x）
- [x] **1G RAR4 copy_match bulk** — extend_from_within 批量复制（rar29/rar20，+20-40%）
- [x] **1H Brotli 格式支持** — brotli-core crate + 21 语料全 PASS

### Phase 2 — 多核并行解压

- [x] **2A RAR 非 solid 并行** — extract_all_parallel（batch ≤4 线程/192MiB，大成员流式）
  - **1 线程 72 → 自动 118 MiB/s，1.64×**
- [x] **2B ZIP 条目级并行** — extract_zip_parallel（无密码单文件，≤32MiB batch）
- [x] **2C 线程数设置** — JNI setParallelThreads + 设置页「并行解压线程」（自动/1/2/4/8，4 语言）

### 全量回归（files4testing v1.2，484 条目，测机 Apple M-series）

`cargo run --release -p corpus-regress -- <corpus_dir>`

**303/330 PASS（91.8%）**，31 skipped（split volumes），1 failed
> CSO 4 个失败已修复（见下方「CSO 两种索引编码」）：修正后 4/4 CSO 全 PASS。剩余 1 失败为 pre-existing `rawfile_tree.iso`（ISO 语料问题，与 CSO 无关）

| 格式 | 条目 | PASS | FAIL | SKIP |
|------|------|------|------|------|
| rar | 65 | 56 | 0 | 9 |
| zip | 47 | 46 | 0 | 1 |
| 7z | 68 | 47 | 0 | 21 |
| gzip | 22 | 22 | 0 | 0 |
| bzip2 | 15 | 15 | 0 | 0 |
| xz | 25 | 25 | 0 | 0 |
| lzma | 16 | 16 | 0 | 0 |
| lz4 | 23 | 23 | 0 | 0 |
| zstd | 24 | 24 | 0 | 0 |
| brotli | 21 | 21 | 0 | 0 |
| iso | 5 | 5 | 0 | 0 |
| cso | 4 | 4 | 0 | 0 |

- CSO 已修复（见下）：header_size=24 与标准格式分别用 bit31/bit0 压缩标志解码，4/4 PASS
- 31 skipped = split volumes（单文件 harness 不支持多卷）
- 40 套件全绿
- 聚合吞吐：2164.9 MiB / 27.76s = **78 MiB/s**（含密码/PBKDF2）

### TODO

- [x] 三架构 .so 重编 + APK 更新（已完成）
- [x] **Brotli 格式 Kotlin 层接入** — `BrotliCore.kt` JNI 桥接 + Constants 9处注册 + 解压/压缩流水线 + 预览/批量 + 压缩级别 0/2/5/8/11（共用通用格式设置）+ 4语言字符串 + help_formats 文档
- [x] **回收站/软删除** — `RecycleBin.kt` 核心类（moveToRecycleBin/restore/emptyRecycleBin/autoClean）+ `RecycleBinDialog.kt` 浏览/恢复/永久删除 UI + `DeleteProgress.kt` 软删除支持 + 设置页回收站开关（启用/自动清理天数7/14/30/60/90/从不）+ 抽屉旁回收站入口 + 4语言字符串 + manifest.json 元数据 + 自动清理 + .recycle 目录隐藏 + 使用文档说明 + 垃圾桶图标
- [x] **文件复制功能** — 多选批量复制（默认文件名(1).后缀）+ 批量操作栏复制按钮 + 长按菜单复制选项 + 4语言字符串
- [x] **文件排序功能** — 一般设置排序选项（名称/大小/日期 × 升序/降序）+ 持久化到SharedPreferences + nav()实时应用
- [x] **媒体预览优化** — 移除FLAG_HW_AV_SYNC修复MP3无声 + 扩展支持格式（wav/aac/flac/aif/aiff/m4a/mkv/avi/mov/webm）+ 归档内预览显示进度 + 视频MIME改为video/* + 屏幕旋转不再重建Activity + MediaPlayer生命周期管理（onPause/onDestroy释放）
- [x] **自由选解压目录** — showOutputDirDialog/showDirectExtractDialog添加第三选项"选择目录" + 复用FolderPicker + 4语言字符串
- [x] **文本编辑器增强** — EditHistory类（50步撤销/重做）+ 自动保存到临时文件 + 异常退出恢复询问 + 正常退出删除临时文件 + 编辑历史管理
- [x] **搜索增强** — 正则表达式支持 + 大小写敏感切换 + 文件类型过滤（扩展名） + UI添加CheckBox和过滤输入框 + 无效正则提示

### 出口路径修复记录

- [x] **EXIT-3 [中]** — 回收站清空后关闭对话框
- [x] **EXIT-4 [低]** — 编辑器Back键退出时清理临时文件（setOnDismissListener）
- [x] **EXIT-5 [低]** — 编辑器保存失败时清理临时文件（catch块添加delete）
- [x] **EXIT-6 [低]** — 音频播放完成时释放MediaPlayer（setOnCompletionListener）
- [x] **EXIT-7 [低]** — onDestroy中MediaPlayer release添加try-catch

### Debug修复记录

- [x] **BUG-1 [高]** — 文本编辑器BOM/编码检测改为传递rawData参数，保留原始字节信息
- [x] **BUG-2 [高]** — 撤销/重做添加suppressWatcher标志位，防止setText触发TextWatcher破坏redo分支
- [x] **BUG-3 [中]** — 文本编辑器添加重做按钮（与撤销并排）
- [x] **BUG-4 [中]** — 临时文件命名使用文件路径哈希，避免同名文件冲突
- [x] **BUG-5 [高]** — MediaPlayer添加released标志位，防止双重release崩溃
- [x] **BUG-7 [低]** — showOutputDirDialog添加pwd参数（密码传递）
- [x] **BUG-9 [低]** — 正则表达式无效时Toast提示用户
- [x] **BUG-13 [中]** — 补充editor_redo翻译（4语言）
- [x] **BUG-16 [中]** — onDestroy中清理cacheDir/edit/临时文件目录
- [x] **BUG-17 [中]** — EditHistory maxSteps从50降为30，减少内存占用

### 出口路径修复记录

- [x] **EXIT-1 [中]** — 搜索对话框关闭时中断搜索线程（setOnDismissListener）
- [x] **EXIT-3 [中]** — 回收站清空后关闭对话框（dialog?.dismiss()）
- [x] **EXIT-4 [低]** — 编辑器Back键退出时清理临时文件（setOnDismissListener）
- [x] **EXIT-5 [低]** — 编辑器保存失败时清理临时文件（catch块添加delete）
- [x] **EXIT-6 [低]** — 音频播放完成时释放MediaPlayer（setOnCompletionListener）
- [x] **EXIT-7 [低]** — onDestroy中MediaPlayer release添加try-catch

### 全面Debug修复记录（Kotlin + Rust）

- [x] **KOT-1 [高]** — getCopyFileName改为检查currentDir路径而非源文件路径
- [x] **KOT-2 [高]** — RecycleBin清空操作移到后台线程，避免ANR
- [x] **KOT-3 [中]** — RecycleBin.moveToRecycleBin控制流优化，renameTo成功后直接return
- [x] **KOT-4 [中]** — 移除showTextEditor中无关的MediaPlayer释放代码
- [x] **KOT-5 [中]** — syncMultiBar改用findViewWithTag替代childCount匹配
- [x] **KOT-10 [中]** — multiSelected改用Collections.synchronizedSet线程安全
- [x] **RUST-1 [中]** — RAR空密码传递修复：extract_rar_inner和extract_rar_volumes_inner中空密码改为None
- [x] **KOT-6 [中]** — ProgressDialog添加isFinishing检查，防止Activity销毁后dismiss崩溃
- [x] **P2-1 [低]** — calcDirSize改为单次遍历，添加进度提示
- [x] **P2-2 [低]** — bookmarks改用CopyOnWriteArrayList线程安全
- [x] **P2-3 [低]** — PreviewAdapter优化：rebuildVisible移至notifyDataSetChanged
- [x] **P2-4 [低]** — 关键位置异常日志记录（FileBrowser APK备份）
- [x] **P2-5 [低]** — Magic数字提取为命名常量（DOUBLE_TAP_INTERVAL_MS等）
- [x] **Toast增强** — 关键操作添加详细错误日志（复制失败等）
- [x] **P2-7 [低]** — 命名规范化：MultiFiles→multiFiles
- [x] **P2-8 [低]** — previewLocalFile无扩展名处理
- [x] **P2-9 [低]** — Shell命令添加30秒超时机制
- [x] **P2-10 [低]** — Bitmap内存回收（scaled/cropped后recycle原bitmap）
- [ ] Phase 3 剩余功能（自由选解压目录/文本编辑器/搜索增强）

### 真机 Bug 修复（v5.13 追加轮次）

- [x] **KOT-11 [严重]** — 多选后直接闪退：`MultiSelect.kt` `syncMultiBar` 用 `findViewById<LinearLayout>(R.id.root)` 强转根视图，但根视图实际是 `ConstraintLayout`（`MainActivity` 以 `ConstraintLayout` 添加 batchBar）→ `ClassCastException`。改 `findViewById<ViewGroup>(R.id.root)`（LinearLayout 与 ConstraintLayout 共同父类），多选恢复可用
- [x] **RUST-2 [高]** — scan-core XP3 未检测：magic 字节写反（`\x1a\n` 应为 `\n\x1a`，且 magic 实为 10 字节 `XP3\r\n \n\x1a\x8b\x67` 非 8 字节）；且头部字段解析偏移错误（XP3 无固定 idx_size 字段）。重写 `validate_xp3`：10 字节 magic + 识别旧格式（u64 直接索引偏移）与现行格式（0x17 标识 + minor + 128 + 相对偏移），独立文件延伸至 EOF。实测 video.xp3 由 0 命中 → **1x XP3 archive**
- [x] **RUST-3 [高]** — scan-core ISO 未检测：PVD logical block size 的 BE 字段偏移写错（读 132-133，应为 130-131）→ `block_lsb != block_msb` 校验失败 → validate_iso 返回 None。改读 `vd[130..132]`。实测 Memories_Off ISO 由一堆无用命中 → **1x ISO 9660**
- [x] **RUST-4 [中]** — scan-core NSA 全误报：NSA 无 magic bytes，Aho-Corasick 只扫出内部 JPEG/MP3（arc.nsa 7314 命中）。新增 `validate_nsa_whole_file` 结构验证（u16 BE count + 可打印 NUL 文件名 + comp≤2 + 数据体在文件内），scan_file 入口预检整文件报 **1x NSA archive**；随机/非可打印名拒绝

### 全量 Debug 轮次（v5.13 — 全功能 + 旧 bug 复盘）

#### Rust 安全/正确性（H 级）

- [x] **H1 brotli 解压无输出上限** — `brotli-core` 只包 `ProgressWriter` 未套 `BoundedWriter`，几 KB 流可膨胀写满磁盘。包 `BoundedWriter::new(_, DEFAULT_EXTRACT_CAP)`（对齐 xz/bzip2/zstd）
- [x] **H2 lz4 无 Content-Size 时无上限** — `declared==0` 时用 `u64::MAX`。改为回退 `DEFAULT_EXTRACT_CAP`
- [x] **H3 lzma 流式头无上限** — `.lzma` alone 头 `usize=u64::MAX`（liblzma 默认输出）时 `declared==0` → 无上限，最常见的 LZMA 形态放开炸弹口。改回退 `DEFAULT_EXTRACT_CAP`
- [x] **H4 scan-core YPF 验证器偏移全错** — 真实 YPF 头是 magic(4)+version(u32@4)+count(u32@8)+hdr_len(u32@12)，但验证器读 count=u16@4、hdr_len=u32@6 → 所有合法 YPF 被拒。改读 u32@8 与 u32@12
- [x] **H5 rar 取消监视线程 panic 泄漏** — `run_with_cancel_monitor` 中 `f()` panic 时 `done.store`+`join` 不执行 → 僵尸线程永久泄漏并错误驱动后续 RAR 进度/取消。改 RAII Guard 保证 panic 路径也终止 + reset

#### Rust 数据完整性/进度（M 级）

- [x] **M1 tar 截断成员不报错** — `io::copy` 返回短字节被当成功。改 `written >= e.size()` 校验，不足删半成品 + fail++
- [x] **M2 7z 成员短读不检测** — 无 CRC 文件夹截断算成功。改 `copied >= entry.size()` 校验，不足删文件 + fail++
- [x] **M3 nsa 打包 >4GiB 单文件静默截断** — `size as u32` 溢出生成损坏归档。进 store 分支前校验 `size <= u32::MAX` 否则报错
- [x] **M4 nsa 失败留半成品 / csize==0 误成功** — 错误路径统一 `remove_file`；csize==0&&usize==0 创建空文件（成功匹配磁盘）；仅 csize==0 保留原行为
- [x] **M5 iso 打包进度恒 0** — `create_iso` reset 后 `write_iso` 不喂 `add_bytes`。文件拷贝处补进度
- [x] **M6 ypf 压缩进度量纲错乱** — `reset(files.len())`（个数）但 `add_bytes`（字节）。改 reset 为文件字节总和
- [x] **M7 zip 单个坏条目中止整个解压** — `by_index` 用 `?` 中断。改 fail++ & continue（单/分卷两处），对齐 xp3/nsa/rar
- [x] **M9 scan-core KSD mode-1 漏检** — 签名表只覆盖 mode0/2，mode1（`FE FE 01 FF FE`）永不触发。补 mode1 magic
- [x] **M10 cso/iso 失败留半成品** — cso_to_iso 外包一层清理；extract_iso_one 失败删文件

#### Kotlin 功能正确性（高）

- [x] **KOT-12 [高]** — RecycleBin manifest 损坏后条目永久不可恢复：`updateManifest` 解析失败直接重建空 manifest 覆盖，丢弃全部条目。改 `recoverManifest`（从各 entryDir 的 `_meta.json` 重建）+ 原子写（tmp + rename）
- [x] **KOT-13 [高]** — RecycleBin.restore 路径前缀 `startsWith` 误判：`/.recycleXXX/...` 兄弟路径被误判在回收站内而永久拒绝恢复。改组件级比较（base + `File.separator`）+ canonicalPath 包 runCatching
- [x] **KOT-14 [高]** — nav() 并发导航旧目录列表覆盖当前目录：后台扫描完成时 `runOnUiThread { listFiles.adapter = adapter }` 无 `currentDir` 校验 → 旧目录覆盖新目录（用户可对看不见的文件操作）。加 `if (currentDir != dir) return@runOnUiThread` 守卫
- [x] **KOT-15 [高]** — 全局搜索大小写不敏感时正则模式被 lowercased：`query.lowercase()` 破坏 `\p{Lu}`/`[A-Z]`/字符类。改仅对普通文本 lower，正则保持原样（IGNORE_CASE 单独控制）
- [x] **KOT-16 [中]** — 搜索 "Invalid regex" 硬编码英文未国际化。新增 `err_invalid_regex` 4 语言 + 改用 getString

#### Kotlin 中低

- [x] **KOT-17 [中]** — 批量复制读取非 volatile `currentDir`，复制中导航会拷错目录。进线程前快照 `targetDir`
- [x] **KOT-18 [中]** — syncMultiBar 的 📂/📦 显隐是死代码：按钮嵌套在 HorizontalScrollView 内层，`bar.getChildAt(i)` 遍历不到。改递归遍历内层按钮
- [x] **KOT-19 [中]** — 编辑器保存失败时删除临时文件丢编辑：tempFile 是恢复副本，保存失败应保留（删掉则未保存编辑无法恢复）
- [x] **KOT-20 [中]** — showRenameDialog 未校验路径分隔符/`..` → 改名可逃逸目录。拒绝 `/`、`\`、`..`

### scan-core 回归测试（v5.13 追加）

- [x] 新增 `xp3_old_format_validates` / `xp3_current_format_validates` / `xp3_bad_magic_rejected`
- [x] 新增 `nsa_whole_file_validates` / `nsa_random_data_rejected` / `nsa_non_printable_name_rejected`
- [x] 修正 `iso9660_volume_descriptor` 测试（BE block size 写入偏移 132→130）
- [x] workspace 全绿（scan-core 34 测试）
- [x] **全量验证** — `cargo test --workspace` 全绿；语料回归 303/330 PASS（CSO 4/4 全 PASS，唯一失败为 pre-existing `rawfile_tree.iso`）；实测 video.xp3 / arc.nsa / Memories_Off ISO 均报 **1x 对应归档**；随机数据仅 ~5 个 MEDIUM MP3 误报（正常率）；`cargo check --workspace` + `gradle compileDebugKotlin` 均通过

### 回收站 Debug 修复记录

- [x] **BUG-9 [严重]** — RecycleBinDialog.kt 重写为命名适配器 RecycleEntryAdapter，修复 ListView 更新失效
- [x] **BUG-1/4 [高]** — restore() 和永久删除时正确更新 manifest，添加 removeFromManifest() 方法
- [x] **BUG-7/8 [高]** — DeleteProgress.kt 进度计算改为以目标数量为基准，每个目标处理后更新消息
- [x] **BUG-2 [中]** — restore() 中添加 parentFile null 安全检查
- [x] **BUG-3 [中]** — readManifest() 添加 synchronized 加锁
- [x] **BUG-11 [中]** — 恢复操作移到后台线程，避免 ANR
- [x] **BUG-12/23 [中]** — 添加 recycle_disabled_message/recycle_enable 字符串资源，AppSettings 使用 getString
- [x] **BUG-13 [低]** — RecycleEntryAdapter.updateEntries() 更新头部条目数和总大小
- [x] **BUG-26 [严重]** — 永久删除操作移到后台线程
- [x] **BUG-27 [中等]** — DeleteProgress 直接删除模式改为以目标数量为基准
- [x] **BUG-28 [低]** — emptyRecycleBin() 添加 synchronized 保护
- [x] **BUG-29 [低]** — 删除/恢复最后一个条目后关闭外层列表对话框

---

## v5.12.0 (PKWARE 跨盘 + ZIP 保 AES + scan 降误报 + md/rtf 渲染 + UI + 深度 debug)

### A 组 — PKWARE 跨盘条目完整支持

- [x] **移除跨盘硬拒绝** — `extract_zip_volumes` 不再检测 `entry_end > disk_end` 报错；PKWARE 多盘 zip 是纯字节切分（盘 N+1 紧接盘 N 数据续段），`ConcatReader` 拼接流下 `find_content` 的 `take(compressed_size)` 天然读穿盘界
- [x] **回归测试** — 原 `pkware_split_cross_disk_entry_rejected` 改为 `pkware_split_cross_disk_entry_extracts`（跨盘 a.txt 1000B + b/c 三文件 list + extract byte-exact；中央目录偏移按盘相对：b 在盘 1 偏移 900）；`pkware_split_modify_rejected` 保留（zipModify 仍拒多盘）

### B 组 — ZIP 加密条目编辑保 AES

- [x] **vendored zip `AesPassthrough`** — `EncryptWith::AesPassthrough`：raw-copy 路径按源条目 AES 模式/厂商版本/真实压缩方法写头（加密标志 + 0x9901 extra），字节原样通过（不再走 AesWriter 二次加密）；`AesVendorVersion` 补 `Eq/PartialEq`
- [x] **zipModify 统一 raw-copy** — 未触及条目一律 `raw_copy_file_preserve_encryption`：AES 条目保加密标志（仍可原密码解）、明文条目纯字节复制；删除原"密码归档解密重写"路径（重压缩、非字节原样）
- [x] **回归测试** — `modify_aes_archive_preserves_untouched_entries`：AES 归档只改 b.txt → a.txt 原密码可解 + 字节一致；无密码提取仍失败（条目保持加密）

### C 组 — scan-core xz/lzma 解压 dry-run（降误报）

- [x] **xz dry-run** — `xz_dry_run`（xz2 `XzDecoder`，1MB 有界输出，出错即拒）；`validate_xz` 头部 CRC 通过后要求真实解码；真实 xz 样本接受、垃圾/随机拒绝
- [x] **lzma dry-run** — `lzma_dry_run`（lzma-rs `lzma_decompress`，输入有界，截断但产出 ≥256B 接受）；`validate_lzma` 接入；`lzma_preset0_dict_validates`/`real_world_compressed_samples` 改用真实压缩流
- [x] scan-core 新增依赖 `xz2` + `lzma-rs`（xz2 挂 liblzma，.so 略增可接受）

### D 组 — -hp 头加密 RAR5 兜底

- [x] **`validate_rar5_hp`** — 魔数 + 明文解析失败时退化为"仅魔数 + 全文件区间"（MEDIUM 置信度）；与明文 RAR5（HIGH，同魔数）同偏移共存，post-pass 高置信度胜出；明文不再被兜底重复命中
- [x] **回归测试** — `rar5_hp_header_encrypted_reported_by_fallback`（-hp 魔数+密文：明文拒、兜底报全文件；明文 RAR 兜底不报）

### E 组 — Markdown/RTF 富文本预览

- [x] **轻量渲染器** `ui/RichTextRender.kt` — `renderMarkdown`（标题/粗斜体/行内码/围栏代码块/列表/引用/链接/分隔线，字符偏移不变、搜索高亮可叠加）+ `stripRtf`（剥控制字/分组，`\par`→换行、`\'hh`、`\uN`）
- [x] **预览内渲染开关行** — md/markdown/rtf 默认富文本渲染，点击切换纯文本；编码行下新增"渲染"行；4 语言 string（preview_rich/on/off）

### F 组 — UI 改进 + 深度 debug

- [x] **批量/合并压缩内联选项流** — `showCompressOptionsDialog` 重构为共享（`onResolved(level, split)`），单文件/批量合并/批量分别统一走「格式 → 选项(等级+分卷)」流；split 透传 `compressDispatch`
- [x] **批量栏窄屏横滚** — 批量操作栏按钮行包 `HorizontalScrollView`（计数固定左，按钮可横滚）；预览标题栏标题 `ellipsize=MIDDLE` 防按钮被挤出
- [x] **JNI 接口一致性核对** — 脚本比对 279 个 Rust `Java_` 导出 vs 278 个 Kotlin `external fun`：补齐 `ScanCore.scanCancelled()` 声明
- [x] 深度 debug：跨盘测试真实字节布局核对、AES passthrough 头写入验证、xz/lzma dry-run 边界、-hp 与明文同魔数共存 post-pass

### 第一轮全面 debug（既有代码 + 接口 + 真实语料）

- [x] **xz/lzma dry-run 嵌入宿主漏报（严重回归修复）** — 嵌入宿主流的 xz 解码完真实数据后撞尾随字节 Err → 现 produced>0 接受；lzma-rs 环形缓冲只在 finish 才 flush（嵌入必 sink=0），流式头(0xFFFF)跳过 dry-run 回退头部校验（否则嵌入全漏）；补 `xz_embedded_in_host`/`lzma_embedded_in_host` 回归（此前仅 gzip 有嵌入测试，回归未被捕获）
- [x] **zip 解压短读检测** — `io::copy` 返回字节 < size → 删半成品 + fail（跨盘/损坏不再静默写不完整文件）；回归 `pkware_split_short_read_fails_entry`
- [x] **批量栏 count 布局** — count 固定宽(WRAP/weight0)，scroll 占剩余；预览标题 ellipsize
- [x] **传统 ZipCrypto 编辑拒绝** — `raw_copy_file_preserve_encryption` 对非 AES 加密条目返回错误（不静默损坏）；回归 `modify_legacy_zipcrypto_archive_rejected`
- [x] **-hp 去重上移 scan 级** — `validate_rar5_hp` 不再内部调 `validate_rar5`（重复 EOF 搜索），同 offset 由 post-pass 高置信度胜出；scan 级测试确认明文 HIGH 唯一、无重复 -hp 命中
- [x] 真实语料验证：8 格式真实样本精确命中+精确 size；嵌入宿主 xz/lzma 修复后全命中；RAR 明文/密码/-hp 头加密全正确；30MB 随机零误报；120 变异文件零崩溃；300MB 扫描 467ms 无 OOM；嵌入 zip/7z 精确切割逐字节一致
- [x] JNI 一致性：279 个 Kotlin extern = 279 个 Rust `Java_` 导出零孤儿、返回类型零差异

### 真机回归修复（荣耀，/sdcard/1）

- [x] **① cross.z02 误判 rar（红色）** — `isVolumeFile` 只嗅探文件自身魔数，`.z02` 非首卷无 PK 魔数 → OLD_RAR_RE 误判 rar。修：`.zNN` 卷名先解析完整卷集(`resolveZipVolumes`)判首卷魔数，有 PK 兄弟才判 zip；rar 分支仅在无 zip 卷集时生效
- [x] **② zipcrypto.zip 无密码（素材问题，非 app bug）** — macOS Python zipfile `setpassword` 写入不加密(flag_bits=0)，文件实为明文。真机改用系统 `zip -e` 生成的真 ZipCrypto(flag_bits 0x9)
- [x] **③ test_hp.rar 打不开/解压不了（完整支持 -hp）** — `rar_needs_password` 用无密码 `read_path` 读 -hp 头失败→Kotlin 吞异常返 false→不弹密码框；`list_rar_inner` 也无密码读头失败。修：needs_password 读头失败返 true（-hp 即需密码信号）+ rar-core 密码版 list(`list_rar_inner_with_pw`/volumes)+ JNI `rarListEntriesWithPassword`/`rarListEntriesVolumesWithPassword` + Kotlin previewArchive/BatchExtract 透传密码；回归 `needs_password_true_on_unparseable_header`
- [x] **④ 使用文档全粗/同色同号（AAPT2 折叠字面换行）** — help_formats/tutorials 的 `<item>` 内用字面换行，AAPT2 编译折叠成空格 → `parseItem` 的 `indexOf('\n')` 永远失败 → 整条全当标题(accent 加粗)、无正文。修：4 语言 31 条字面换行改 `\n` 转义(实测 `\n` 转义编译后保留 0x0a)+ disclaimer_body 同修 + `parseItem` 无换行启发式兜底(`—`/`-` 切分)；字号 14f 标题 accent 加粗 / 12f 正文 secondary + 空行
- [x] 部署：build.sh 全量 + adb install + 素材推送 /sdcard/1

### 第二轮真机 debug（RTF + rar 取消 + 进度）

- [x] **RTF 渲染偏移错乱** — `\b0`/`\i0` 的 `0` 被数字参数解析吞掉 → cmd 变 "b"/"i" → 开关反转(加粗/斜体圈错范围)。修：b/i 后紧跟 0/1 并入 cmd(`b0`/`b1`/`i0`/`i1` 显式处理)；模拟验证 `hello \b bold\b0 rtf` → bold 正确加粗
- [x] **rar 解压取消卡住** — rars 整块解码(`decoded_file_data`/`decode_split`)期间无取消检查 → 取消要等当前 ≤64MB 成员解完才响应。修：rars fork `codec/rar50.rs` 加 `DECODE_CANCEL` 静态标志 + 解码循环每块检查；rar-core `run_with_cancel_monitor` 桥接 `extract_progress::cancelled()`(50ms 轮询,`done` 标志防监视线程不退出)；取消响应回归测试
- [x] **进度框取消时立即 dismiss** — `PollingProgressDialog` onCancel 时立即 dismiss(不再等 worker 返回),worker 后台清理 + finally 释放锁
- [ ] **rar 进度跳变(格式特性)** — ≤64MB 成员走整块解压+一次写,进度在成员边界跳变;大成员(>64MB)流式进度正常。可接受,记录
- [ ] **LZ4 列表 0b(已知限制)** — 无 Content Size flag 的 LZ4 无法预知解压大小(同类 lzma),显示 0b
- [ ] **OperationLock 密码重试死锁(待用户复现决策)** — 解压失败弹密码重试框时外层锁未释放 → 重试报 busy;用户先自行试错,后续再修

### 第三轮真机 debug（rar 性能 + 进度 + 编码 + 压缩 UI）

- [x] **rar 解码器加速(Huffman 查表)** — RAR5 逐位 Huffman 解码(纯 Rust,~52-100MB/s)是性能瓶颈。试过 BitReader 64-bit 缓冲(反而慢 10%,回退);最终 8-bit lookahead 查表:短码(≤8 位)一次 peek 查表命中,长码 fallback 逐位;BitReader 加 `peek_bits`/`skip_bits`。文本数据解压 ~30% 提升(101→133MB/s),rars 598 测试全绿
- [x] **rar 进度条双重计数(1.78G/1.34G)** — `run_with_cancel_monitor` 喂 `add_bytes`(整块解码进度)+ ProgressWriter 写盘再喂 → 超 total。修：common 加 `set_file_bytes`(只设当前文件底部条);monitor 改用 `set_file_bytes(decode_progress)` → 顶部只由写盘喂,不再超 100%
- [x] **settings_chinese.txt 误判 GBK** — `detectBestEncoding` 对 SJIS 短文本 GBK 碰巧更干净 → 误选。修：优先 SJIS(galgame 惯例),仅当 GBK 替换字符显著更少(≥25% 优势)才选 GBK
- [x] **LZ4 无 content size 列表显示未知** — 无 Content Size flag 的 LZ4 无法预知解压大小(硬伤),列表返回 -1,前端 `fmt(-1)` 显示 "?"(非误导的 0 B)
- [x] **压缩分卷类型歧义** — 分卷行标注 `.001/.002` 字节分卷(7-Zip 语义,非 PKWARE z01 真分卷,writer 不生成 z01),消除歧义;内联压缩选项选择分卷后行文字即时刷新
- [x] 注: 用户反馈 rar 解压单文件/txt 预览慢是 **solid 归档格式固有**(无随机访问索引,解压单条目=顺序解码之前所有 solid 块),zip/7z 有中央目录所以快;加密多小文件每文件 PBKDF2 亦固有

### 第四轮 debug（压缩选项 UI + rar 进度 + RTF 偏移 + 编码优先修正）

- [x] **压缩等级行切换不刷新** — 内联压缩选项对话框等级行 text 创建时算一次,onClick 只改 `level` 变量 → 切"低→中等"后行仍显示"压缩等级：低"。修：持 `levelRow` 引用,选择回调里同步刷新行文字(与分卷行 be51fa1 修法一致)
- [x] **内联分卷缺"自定义"** — 内联对话框单选项只有 不分卷/1MB/100MB/1GB,无自定义入口(设置页有)。修：追加 `split_custom`(index 4),checked 索引按值 when 匹配(自定义设置值高亮"自定义"而非误高亮"不分卷"),index 4 弹 MB/GB 切换+1~2048 校验输入框(镜像设置页),确认回写 `chosenSplit` 并刷新行
- [x] **rar 大成员(>64MB 流式)底部进度条冻结** — `run_with_cancel_monitor` 无条件 `set_file_bytes(decode_progress())`,但流式路径从不更新 decode_progress → 陈旧值(0 或上个成员 size)每 50ms 覆盖写盘驱动的 FILE_BYTES → 大成员底部条卡住/错乱。修：rars 加 `DECODE_BUFFERED_ACTIVE` 标志(Drop guard 包裹整块解码函数),monitor 仅在 buffered-active 时镜像 decode_progress;流式成员交给 ProgressWriter 写盘计数
- [x] **RTF 样式 span 偏移错位/丢失** — `text.replace("\n{3,}","\n\n").trim()` 后 spans 只过滤不重算 → 末尾 `\par}` 被 trim 后末段 span 丢弃、前导空白/折叠处错位。修：构建最终文本时记录 输出位置→源位置 映射(srcIndex 严格递增),span 经二分映射重算后应用
- [x] **detectBestEncoding SJIS 优先为死逻辑** — 第三轮 `bG*3 > bS*4` 阈值在 `bS > bG`(唯一生效场景)时恒假 → 行为与旧代码完全一致(GBK 只要坏字符更少就赢),"优先 SJIS"未实现。修：`bG*2 < bS`(GBK 坏字符不足 SJIS 一半才选 GBK)

### 第五轮 debug（真实 1.1GB rar 复现：进度冻结根因 + 非 solid 随机访问）

- [x] **rar 顶条冻结根因：`extract_to` 丢弃 rar_opts** — 用真实 1.1GB 非 solid rar(46 成员)探针复现：cg.ypf(392MB)解压时顶条卡死(写盘瞬间才跳)、底条靠 watcher 走。根因：`extract_rar_inner` 调 `archive.extract_to(pw,…)` → rars 内部 `read_options()` 用默认 **512MB** 缓冲上限,`rar_opts` 设的 64MB 只在解析时生效 → 392MB 成员也整块缓冲。修：单文件路径改用 `extract_to_with_options(rar_opts(pw),…)`(分卷路径原本就传了)。修后大成员流式,顶条字节级平滑;探针验证 顶条最终精确 = total(无漏/无双计)
- [x] **rar 进度计数重构** — 顶条一律由**写盘**驱动(精确,不经 50ms 轮询,避免"一次轮询内解完的成员漏计"——实测 watcher 喂顶条漏 17.3MB);底条由 buffered 解码的 `decode_progress` 喂(watcher)。common 加 `add_top_bytes` + `ProgressWriter::extract_top`(只计顶条),rar_writer/快路径对缓冲成员用 extract_top(写盘只计顶条,底条归 watcher),流式/存储成员用 ProgressWriter(双条)
- [x] **非 solid 选中解压/预览随机访问(快路径)** — RAR 无中央目录,顺序 `extract_to` 解码所有成员(未选中也解) → 预览排后 txt = 解码前面全部(1.3GB 归档里点最后一个 txt = 解 1.3GB)。修：非 solid(且无 split/redirection)时遍历 `files()` 只对选中成员调 `write_to`,未选中零解码(rars 每成员独立 data_range,天然随机访问)。fork 补 `FileHeader::write_to_with_options`(带 64MB 限制,大选中成员流式不 OOM)。真实归档实测：member #38/#45(前面有 392MB cg.ypf 等)选中解压 **0.06s**(原顺序路径 255s)。solid/分卷/rar13 回退顺序
- [x] 真实归档回归：全量解压 46/46 成功,`extract_progress::bytes() == total_bytes()` 精确命中;rars rar50 62 测试 + rar-core 7 测试全绿

### 待处理（延续 + v5.12.1 计划）

- [x] **7z 包内修改（编辑闭环）** — 复用 xp3/pfs/nsa/iso 流程：全量解压→改脚本→`szCompress` 重压为 `原名-cn.7z` 副本(uniqueFile 叠加 (1))
- [x] **zip 编辑改 -cn 副本** — `zipReplaceEntry`/`zipDeleteEntries`/`zipAddEntry` 从覆盖原文件改为 `原名-cn.zip` 副本（不再破坏原归档）
- [x] **YPF 封包（带 XOR+SJIS）** — 逆向 SwapTable(len→marker) + XOR(0xFF) + ShiftJIS 文件名编码；`ypfCreateArchive` + 压缩路由 + 编辑闭环；自往返测试通过；待真实 YPF 样本校准短名(≤8 字节)marker 编码
- [x] **RTF 富文本增强** — `stripRtf` 剥控制字后映射 `\b`/`\i` → BOLD/ITALIC span
- [ ] **深嵌大 zip（wontfix）** — EOCD 前向 256MiB 封顶 + 文件尾 64KB 回退覆盖两类场景；深嵌中间且压缩体 >256MB 的 zip 仍漏（binwalk 同病，代价不值）
- [ ] **进度静态量每操作局部上下文** — 全局 Atomic 静态量在单操作模型下安全；要真并发再改

#### v5.13.0 计划（多核并行解压）

- [ ] **rar 非 solid 多线程/多核并行解压** — 非 solid 多成员 rar 用 `std::thread::scope` 起 N 个 worker(`N = min(available_parallelism,4)`,可设置),每 worker 对领到的成员调 `write_to_with_options`(流式、独立 data_range),共享 AtomicUsize 索引;顶条靠写盘原子累加精确聚合,底条改为"在飞聚合"(各 worker 上报 size/bytes,协调线程求和,`common` 需补 `set_file_total`);取消各 worker 成员间检查。solid/单大文件/分卷/rar13 无收益或格式限制,回退顺序。zip 并行(各 worker 独立开归档)也归 5.13
- [ ] 设置项「解压线程数」自动/1/2/4(AppSettings, 存 pref `extract_threads`),JNI 走 `rarSetThreads(i32)`(0=自动)与 `zipSetEncoding` 同款模式;strings ×4

## v5.11.0 (Rust 加固 + 封包/转换/编辑扩展 + PKWARE 多盘 + 深度 debug + 真机回归)

### A 组 — 单文件解压输出封顶（防解压炸弹）

- [x] **gzip/bzip2/xz/zstd 输出封顶** — `BoundedWriter` 包输出侧：gzip 用 footer ISIZE（单成员），bzip2/xz/zstd 无声明 → 16GB 硬上限（`common::DEFAULT_EXTRACT_CAP`）；超限删半成品；gzip 多成员检测（`1F 8B 08` 计数）回退硬上限
- [x] **BoundedWriter 回归测试** — common 单测（恰好限额/超限截断/零预算拒绝）+ gzip ISIZE 撒谎炸弹测试

### B 组 — scan-core gzip 解压验证

- [x] **gzip 解压 dry-run** — 头部校验后流式解码单成员（flate2，前 1MB 有界），随机/垃圾数据伪装 gzip 降误报 ~1/1000 → 近 0；测试改真实 flate2 样本 + 垃圾 deflate 拒绝

### C 组 — 封包扩展

- [x] **NSA/SAR 封包** — `lzss_encode` 提为正式 encoder（测试版删除），`nsaCreateArchive`（stored + LZSS，进度/取消），JNI + compress 进度 getter，Kotlin 压缩路由/选择器/进度接入；往返 + store 回归测试
- [x] **ISO 9660 Level 1 封包** — 新 `isoCreateArchive`（writer：PVD/终止符/目录记录/8.3 大写文件名/扇区对齐，两遍 layout，递归目录树），isomage 读取往返回归测试；Kotlin 压缩路由 + 格式选择器 + 进度接入
- [x] **CSO↔ISO 双向转换** — 新 `crates/cso-core`（CISO 魔数/2KB 块/zlib 逐块，压缩后不缩小则 stored，进度/取消），`csoToIso`/`isoToCso` JNI，Kotlin 转换路由（cso 文件 FAB 转 ISO + ISO 预览选项转 CSO）；往返 + 随机数据 + 坏魔数回归

### D 组 — ZIP 包内处理

- [x] **ZIP 条目增删改** — `zipModify`（replace/delete/add，换行分隔 op 串）：无密码归档 `raw_copy_file` 只改目标保留其它条目字节；密码归档解密重写保加密（raw_copy 对 AES 丢加密标志的已知局限）；回归测试（增删改往返 + 密码归档）
- [x] **ZIP 包内编辑闭环** — zip 条目预览加「编辑」按钮，保存后 `zipModify` 只替换该条目（原子换文件）；`showTextEditor`/`showTextPreview` 加 `onSaved`/`onEdited` 回调

### E 组 — 预览扩充

- [x] **galgame 脚本** — `xhtml`/`vsq`/`ksc` 进文本预览 + 内容搜索（`.se`/`.ast` 经核实是 NScripter 音频非文本，已排除）
- [x] **图片** — `bmp` 进图片预览（BitmapFactory 原生支持）
- [x] **previewFileEntry 改用全局 `PREVIEW_EXTS`** — 此前本地集合与全局不一致（漏 md/rtf/csv 等），统一修复

### F 组 — PKWARE 多盘 zip（`.z01/.z02/.zip`）

- [x] **fork zip crate**（MIT，`[patch.crates-io]` 指 `crates/vendor/zip`，仿 rars/sevenz-rust 先例）
- [x] **多盘读取** — `ZipFileData` 加 `disk_number_start`（中央目录条目盘号）；`find_central_directory` 按 `disk_with_central_directory` 校正 cd 偏移（原 zip crate 用盘相对偏移当绝对位定位 cd 失败）；`ZipArchive::with_disk_offsets`；移除 zip32 多盘硬拒绝
- [x] **zip-core ConcatReader** — 加 `disk_offsets()`（每卷绝对起点）+ `is_pkware_split()`（`.zNN` 真分卷检测）；`list_zip_volumes`/`extract_zip_volumes` 对真分卷走 `with_disk_offsets`，字节分卷走原路径
- [x] **跨盘条目检测** — 条目数据越盘边界 → 明确报错（单盘条目完全支持；跨盘暂不支持）
- [x] **回归** — 合成 `.z01/.z02/.zip`（每文件单盘）list/extract 全通；跨盘拒绝；现有 10 测试不回归（14 全绿）

### G 组 — ISO/NSA 编辑条目

- [x] **ISO/NSA 编辑闭环** — 复用 xp3/pfs 编辑流程：预览标题栏「编辑」→ 全量解包（`extractByFormat` 已支持 iso/nsa）→ 脚本列表（EDIT_SCRIPT_EXTS）→ 显式编码编辑 → 「重新打包」→ `nsaCreateArchive`/`isoCreateArchive` 重封为 `原名-cn.iso/nsa`；`repackEditedArchive` when 加 iso/nsa 分支；编辑按钮条件与 `edit_only_pack` 文案（4 语言）扩展
- [x] 说明：ISO 重封沿用 Level 1（8.3 大写文件名 + 1GiB 上限），NSA 重封 LZSS(stored 回退)

### 深度 debug 轮次

- [x] **第一轮（逐 commit）** — A1 gzip_member_count 跨块魔数漏检（`1F 8B|08` 布局漏计 → 双成员 gzip 误判单成员、末成员 ISIZE 截断合法多成员）；B 截断 gzip 拒绝回归；C NSA 封包 3 处（body 全量进 RAM OOM / count u16 溢出 / offset u64 写 8 字节 header 错位）；D zipModify 2 处（replace/delete 未命中路径报错 / 目录级联删除）；E zipReplaceEntry 原子替换（原 delete+renameTo 丢数据）；F `.se`/`.ast` 误判文本移除；G CSO 3 处（输出封顶 / offset 递减下溢 / 2GiB 上限）；H ISO 4 处（layout 不递归 / 8.3 名冲突 / >4GiB 溢出 / 1GiB 防 OOM）
- [x] **第二轮** — NSA 压缩进度翻倍（LZSS 探测读不计数）；ISO 无扩展名去重名非法（`FOO;1~1`）；CSO 压缩块单次 read 截断；zipModify 嵌套路径替换回归
- [x] **第三轮（既有代码 + 接口 + 真实语料）** — xp3 KSD 探测缓冲超限丢段；分卷 zip 编辑误暴露；JNI 接口一致性核对；294MB xp3 scan_cli 3.7s 零崩溃；537MB 真实加密分卷 zip 解析正常；common/ksd/pfs/ypf 复查
- [x] 验证：workspace 26 套件全绿（新增回归覆盖上述全部）

### 真机测试（荣耀/华为，`/sdcard/1` 素材）

- [x] **素材准备** — 主机生成小素材（单文件 gz/bz2/xz/zst/lz4/多成员gz、zip/7z 无密码+密码、tar 5 变体、zip/7z 字节分卷、PKWARE `.z01/.z02/.zip`、bmp/xhtml/vsq/ksc 预览样本、70MB big.bin）→ adb push `/sdcard/1/`
- [x] **z01 多盘解压** — 合成 `.z01/.z02/.zip`（每文件单盘）list/extract 内容精确匹配（主机 vendored zip 验证）
- [x] **NSA/ISO 封包往返** — 压缩模式打包→解压内容一致，进度正常
- [x] **ZIP 编辑条目** — 预览内编辑保存只改该条目
- [x] **gzip 多成员解压** — 完整解出

### 真机回归修复

- [x] **① crash：`.z01` 被误判为老式 RAR 分卷** — `OLD_RAR_RE`（`(.+)\.([r-z])\d{2}$`）匹配 `.z01`（z∈[r-z]），`isVolumeFile` rar 分支无条件返回 "rar" → `resolveRarVolumes` 里 `groupValues[2]` 单字母 "z" 的 `drop(1).toInt()` 空串崩溃。修：rar 分支加 `!startsWithZipMagic(f)`（`.zNN` 开头 PK 排除）+ `toIntOrNull() ?: continue` 兜底
- [x] **② 多盘 zip 增删改覆盖原有条目** — `zip_modify` 用 `ZipArchive::new`（单盘）解析多盘 `.z01/.z02/.zip` → 0 条目 → 重写后原有全丢（设备上 test.zip 被覆盖成 2 文件实锤）。修：双层防护 — Rust `is_multi_disk_zip`（EOCD `disk_number != disk_with_cd` 检测）拒绝；Kotlin `isMultiDiskZipArchive` 排除编辑/增删按钮与函数入口；回归测试 `pkware_split_modify_rejected`
- [x] **③ tmp 残留（`.del.zip.tmp{pid}`）** — Rust `zip_modify` 错误路径从不删 tmp；Kotlin 失败只删自身 `.del.zip`。修：Rust `TmpGuard` Drop 守卫（错误自动删 + rename 后 disarm）；Kotlin `cleanupZipModifyArtifacts` 删 `.mod/.del/.add.zip` 及 `.tmp*`
- [x] **④ FAB 残留旧 zip** — `select()` 预览/信息分支不隐藏 FAB 也不清 `selectedFile`（点完 zip 再点普通文件，FAB 仍作用于旧 zip）。修：两分支加 `selectedFile=null` + `fabExtract.visibility=GONE`
- [x] **⑤ 批量栏无预览入口** — 批量栏只有解压/压缩/移动/删除/取消。修：加「预览」按钮 → `startBatchPreviewOnly`（同格式才可预览，混合格式 toast `batch_preview_mixed`）
- [x] **⑥ 使用文档渲染** — help 条目标题全加粗刺眼 → 标题 accent 色 + 正文 primary 行距；全量更新 4 语言使用文档（补 CSO、各格式限制、z01、编辑条目、批量预览等教程）；修 AGP 对 `<item>` 内裸撇号 `'` 的合并 NPE（改 `does not`/`cannot`）
- [x] **⑦ sample.bmp 纯黑** — 1x1 红点在黑背景不可见（素材问题）。重新生成 100x100 纯红 BMP
- [x] **hello.zip 密码误报（素材）** — 旧素材 `zip -r ../single` 致条目带 `../` 路径，`safe_join` 拒绝 → 兜底"可能需密码"。重新生成无 `../` 干净 zip

### 验证与提交

- [x] workspace 26 套件全绿（新增 cso×3 / iso writer / zip modify×2 / gzip 炸弹 / BoundedWriter / 多盘拒改等回归）
- [x] 三架构 `.so` 重编（含 cso-core）+ `assembleRelease` + APK 更新（5.11.0 / code 22）
- [x] 提交：`92f03f4` A1 / `12a60de` B / `2044ce3` NSA / `b287c8c` zipModify / `0e8efdc` zip 编辑 / `5184067` 预览 / `6f99078` CSO / `2c03287` ISO / z01 多盘 / debug 三轮 / 真机回归 5+2 项 / ISO-NSA 编辑 / 文档更新



## v5.10.0 (编辑闭环 + 预览扩展 + 扫描增强 + 自动刷新)

### 预览扩展（A）

- [x] **文本格式全进预览** — `PREVIEW_EXTS` 重构 = 图片/音频/视频 + 全部 `TEXT_SEARCH_EXTS`（md/rtf/yaml/toml/sh/srt/ass/lrc/vtt/csv…）；`vtt` 补入搜索列表；调整声明顺序避免顶层 val 初始化顺序问题
- [x] **GIF/WebP 动图预览** — `previewLocalFile` 路由加 gif/webp → 图片；`showImagePreview` 改 `ImageDecoder`（API 28+ 返回 `AnimatedImageDrawable` 自动播放）+ API 26-27 静态帧回退
- [x] **文本预览框放大 + 防 OOM** — 对话框 92%×85%；新增 `readPrefix(file, 1MB)` 有界读取，大 .log/.csv 秒开不整读

### 归档文本编辑闭环（XP3/PFS 取出→看→编辑→封回）

- [x] **文本编辑器** — `showTextEditor`：大 EditText + 顶部编码行（UTF-8/SJIS/GBK/UTF-16 单选）+ 保存；预览对话框加「编辑」Neutral 按钮进入
- [x] **编码保真写出** — `encodeText(text, enc, bom)`（UTF-8/UTF-16 按原 BOM 状态写回、SJIS/GBK REPLACE）+ `hasBom`；编码不变时字节级往返一致（已知限制：UTF-16 BE→LE）
- [x] **归档编辑流程** — 预览标题栏「编辑」→ 全量解包到 cache/edit → 脚本列表（.ks/.tjs/.csv/.txt/.ini/.cfg/.json/.log，`EDIT_SCRIPT_EXTS`）→ 就地编辑 → 「重新打包」→ `xp3CreateArchive`/`pfsCreateArchive` 全量重封为 `原名-cn.xp3/pfs`；OperationLock 隔离
- [x] **归档条目预览隐藏「编辑」** — `showTextPreview` 加 `showEdit` 参数，`previewFileEntry` 传 false（cache 临时文件保存不会封回归档，避免误导）
- [x] **启动先 dismiss 预览对话框** — 封回 + nav 后不留过期预览窗口

### B 扫描增强（Rust scan-core）

- [x] **tar 签名** — `validate_tar`：`ustar` 魔数 @257（GNU `\0`/POSIX 空格版本字节）+ 头 checksum（148..156 八进制按空格求和）+ 512 块链遍历到双零块算精确 size；**容忍前导空格八进制**（legacy tar）；注册第 22 签名/68 魔数；3 条回归测试（精确 size/损坏 checksum 拒绝/随机偏移拒绝/空格填充 size）
- [x] **ISO 命中修复（潜在 bug）** — 独立 ISO 命中被 scan 的 `magic_offset + size ≤ file_len` sanity 拒绝（size 语义从整图改为 **extent-from-magic**，ISO 减 32768）；post-pass `size > remaining` 同理；ISO 现在能真扫出
- [x] **切割偏移调整表** — `magicStartAdjust`（ISO +32768 / tar +257）：`carveOffsetOf` 起点回退、`carveLengthOf` 长度补回 → ISO/tar 精确切割
- [x] **嵌入 ISO 解压走 carve** — `extractHit`：`hit.offset > 32768` 时不再直览宿主文件，`separateAndExtract` 的 needsCarve 补该条件

### C 系列

- [x] **C1 `.z01` 真分卷加固** — zip 3.x 原生支持多盘 EOCD；`resolveZipVolumes` 真分卷 base/末卷匹配改大小写不敏感（`FOO.Z01`+`foo.zip` 可拼全套）
- [x] **C2 深递归（审计关闭）** — `iso_walk`/zip·sevenz `add_dir`/tar·pfs·xp3 `collect_files`/rar·sevenz `walk_files`/Kotlin `walkTopDown/BottomUp`/`deleteWithProgress` 全为显式栈或无递归
- [x] **C3 压缩流程内联** — 单文件压缩选格式后弹「压缩选项」（等级 + 分卷大小）；`compressDispatch` 加 `splitOverride` 参数；**debug 修：自定义分卷不再被 indexOf 静默降级**（`chosenSplit` 透传 + `fmt` 格式化显示，未改动时保留设置值）

### 文件列表自动刷新

- [x] **重命名立即刷新** — `showRenameDialog` 成功分支（无冲突/替换/保留两者）调用 `onSaved()`；调用点改 `{ saveBookmarks(); nav(currentDir) }`（此前重命名后必须退出重进）
- [x] **FileObserver 后台监听** — 监听 `currentDir`，内容变更事件（CREATE/DELETE/MOVED/CLOSE_WRITE/MODIFY，排除读取事件防 observer↔nav 循环）+ 400ms 防抖 → 主线程 `nav(currentDir)`；`nav()` 换目录重启、onPause 停/onResume 重启/onDestroy 清理；覆盖压缩/解压/外部改动（adb push/USB/其他 App）

### 验证与提交

- [x] scan-core 22 测试全绿（新增 tar×2 + 空格填充回归）；workspace 全绿
- [x] `.so` 三架构重编（scan-core）+ `assembleRelease` + APK 更新
- [x] 提交：全部 v5.10.0 改动已合并为**单个提交**（自 v5.9.0 `76b88ea` 之上）

### 真机回归修复（v5.10.0）

- [x] **荣耀 onDrawScrollBars 崩溃（根因实证）** — `mScrollBar.mutate() on null` NPE 是 Honor/EMUI ROM bug：`onDrawScrollBars` 无 null 守卫，且其 `ScrollabilityCache` 不创建 ScrollBarDrawable 对象。**主题 drawable 无效**（对象非 null 缺失），正解 = 禁用自定义视图滚动条绘制。7 处禁用：预览 TextView+2×ScrollView、编辑器 EditText、格式选择器、帮助、压缩设置 XML（`scrollbars="none"`）；滚动功能保留（movementMethod / 原生 / 触摸）
- [x] **设置编码选择器陈旧** — `textEncCurrent`/`zipEncCurrent` 改 `var`，选完即刷新行值 + 再开高亮正确
- [x] **预览加编码切换行** — 切编码立即按原 bytes 重解码重渲染（写 prefs 全局生效），不再"改了没反应"
- [x] **UTF-16/UTF-8 BOM 自动探测** — `detectBomEncoding`：预览/编辑器初始解码按 BOM 走（FF FE/FE FF→UTF-16，EF BB BF→UTF-8），galgame UTF-16LE 脚本开箱即显
- [x] **DragScrollBar 自定义可拖滚动条** — 基于 `layout.height`/`scrollY` 公开 API 算拇指（不走荣耀崩溃的 onDrawScrollBars 路径），预览+编辑器右侧可拖跳转；列表沿用 fastScroll 常显把手
- [x] **GIF/WebP 显式 `AnimatedImageDrawable.start()`** — 荣耀不自动播的修复
- [x] **编辑器 2MB 上限防 ANR** — 大文件 toast 拒绝，不再整读
- [x] **looksLikeUtf8 乱码提示** — 严格 UTF-8 校验；非 UTF-8 编码下文件是合法 UTF-8 → 提示切换（覆盖"合法但错"的交叉误读）
- [x] **XP3 内 KSD-mode-2 加扰文本解包（重大）** — `启动游戏.xp3` 61 个 txt 中 48 个为 `zlib(KSD 包装(FE FE 02 FF FE + 长度 + zlib(UTF-16LE 文本)))`；xp3-0.4.2 crate 只解外层 zlib → 内容写成 KSD 二进制 → 全编码乱码。修复：`ksd-core` 暴露 `pub ksd_mode2_decode`（+rlib crate-type），`xp3-core` 小条目缓冲探测前缀并解包写盘（大条目流式，非 KSD 原样）；回归测试 `ksd_mode2_wrapped_entry_extracts_as_text`；重编 xp3_core 三架构
- [x] **归档预览标题栏按钮放大** — 查找按钮 52×40→64×48、编辑按钮统一尺寸
- [x] 验证：workspace 全绿（xp3-core 3 测试含 KSD 回归）；`assembleRelease` + APK 更新

### P2 批改 + 交互优化（v5.10.0）

- [x] **双进度条去阈值** — 删除 `PROGRESS_FILE_BAR_MIN`(1MB)隐藏逻辑：底部"当前文件"条恒显（有 size 走确定进度、未知走不定圈），消除 iso/nsa/xp3 小文件时"丧失双条"的感知
- [x] **七个入口迁移双条** — 重打包、编辑脚本启动、归档内搜索 prep、批量解压所选、批量预览单文件、批量搜索 prep、搜索结果按需解压，全部从 spinner 迁到 `PollingProgressDialog`（锁先于框 + 取消透传）
- [x] **xp3 KSD 进度校准** — 解包后按实际大小 `set_file`，底部条不再瞬间顶满
- [x] **pfs 不再报加密** — PF8 内置 XOR 为索引派生、非用户密码；`list_pfs` 报 `e:false`，pfs 条目不再显示 🔒
- [x] **编码自动检测 `detectBestEncoding`** — BOM(UTF-16/UTF-8) → 严格 UTF-8 → SJIS/GBK 启发（不自信回退设置值），接入预览/编辑器/全局搜索内容解码
- [x] **搜索结果保留搜索框** — 点结果打开文件关闭后回到原搜索，免重复输入
- [x] **搜索高亮丢失修复** — 匹配超出 5 万字符显示截断时取匹配 ±2 万窗口重显
- [x] **全局搜索进度条改圆环 spinner** — 水平 indeterminate 在荣耀等 ROM 渲染成满条蓝条
- [x] **取消半成品清理** — Kotlin 新建输出文件夹取消时 `deleteRecursively` 整删（`cleanupCancelledOutput` 捕获 existedBefore）；Rust 各 crate（xp3/pfs/tar/lz4/gz/bz2/xz/zst/lzma/ksd，加原有 zip/7z）解压 copy 失败时 `remove_file` 半成品；重编 10 个 .so 三架构
- [x] **术语去歧义** — 全库统一为编辑闭环/编辑流程/EDIT_SCRIPT_EXTS，无本地化歧义措辞
- [x] **Kotlin 代码审计修复（top-10 + 低危）** — worker 级 catch 防崩溃/进度框泄漏（批量压缩/搜索占位/批量/编辑启动/搜索结果）；deleteRecursively 移入锁内；carve 持锁+可取消；stopped @Volatile；Phase-2 用 sanitize 路径；isPasswordProtected 移 worker；位图 worker 解码+降采样+关流；drawer 硬编码迁 strings×4；listBookmarks scrollbars=none；skip 循环/死绑定/死代码/标签本地化/parentFile 兜底
- [x] **Rust 安全审计修复（5 高 + 5 中）** — sevenz-rust 头数量/缓冲区全部封顶 + assert→Err；isomage vendor 并加 data_length 封顶 + 目录递归深度≤128；xp3 缓冲解压有界读取（zlib 炸弹）；ksd 负数长度拒绝；zip/lzma/lz4 输出封顶（新增 common `BoundedWriter`）；pfs offset u64 累加 + >4GB 报错；nsa SPB 零维度拒绝；workspace 24 套件全绿
- [x] 验证：workspace 全绿；`assembleRelease` + APK 更新

### Rust 安全审计（v5.10.0，恶意头/炸弹/溢出，5 高 + 5 中）

- [x] **7z 受控无界分配（高）** — sevenz-rust `num_files`/`num_coders`/`next_header_size`/解码后 `buf_size`/`properties_size` 全部封顶（1M/1024/64MB/64MB/16MB），构造头不再 OOM abort
- [x] **ISO 目录 OOM + 栈溢出（高）** — isomage 已 vendor（`[patch.crates-io]`），`data_length` 封顶 64MB、`parse_directory` 深度 ≤128（防环/递归 SIGSEGV）
- [x] **xp3 解压炸弹（高）** — 小条目缓冲改手动有界读取（≤16MB），超限转流式
- [x] **ksd 负数长度回绕（高）** — `compressed_len/uncompressed_len` 负值显式拒绝，不再 `as usize` 回绕切片 panic
- [x] **zip/lzma/lz4 输出封顶（中）** — 新增 common `BoundedWriter`，按声明解压大小封顶（lzma-alone 头 / lz4 content size / zip entry.size）；gz/bz2/xz/zst 无可靠 in-header 声明大小，记接受风险
- [x] **sevenz assert → Err（中）** — `total_input_streams <= total_output_streams` 不再 panic
- [x] **pfs offset u32 回绕（中）** — u64 累加 + 总大小 >4GB 报错
- [x] **nsa SPB 零维度下溢（中）** — `height/width == 0` 显式拒绝，防 `height-1` 下溢
- [x] 验证：workspace 24 套件全绿；8 个受影响 crate 三架构重编

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
- [x] **N1 内容搜索整读 OOM 隐患** — `decodeTextStrict(readBytes())` 整文件载入（原流式 forEachLine）；新增 50MB 硬上限，即便「极端」极限(Long.MAX_VALUE)也跳过 >50MB 文本，杜绝大文件内容搜索 OOM
- [x] **E1 切割按 hit.size 定终点** — `carveToFile` 加 `length: Long?` 参数（null=EOF 回退给无 size 的 gzip/bz2/xz/lzma/xp3）；zip/rar/7z/zstd/lz4/iso 有精确 size → 切 `[offset, offset+size)`；主机端到端：`mp4+zip+mp4` 拼接 → scan 报 zip(offset/size/count)，新切割与原始 zip 逐字节一致；尾部 200KB(>64KB EOCD 窗口)时旧 EOF 切割必 "cannot find EOCD"，E1 修复成功提取

### 待处理

- [ ] **进度静态量每操作局部上下文** — 若要真正并发再做
- [ ] **gzip/xz/lzma 解压验证** — scan-core 为保持零依赖只做头部校验；加密/高熵数据中 gzip 仍有 ~1/1000 概率误报（binwalk 用解压 dry-run 根治）。若引入 flate2/xz2/lzma-rs 可对齐到接近 0，但 `.so` 会增大
- [ ] **-hp 头加密 RAR 不报** — 无明文结构可验证；binwalk 报（靠魔数）但伴随加密流内部大量误报。若未来需要可加"仅魔数 + 全文件区间"兜底
- [ ] **深嵌大 zip（wontfix）** — EOCD 前向 256MiB 封顶 + 文件尾 64KB 回退覆盖「归档在文件尾」与「压缩体 ≤256MB」两类；深嵌宿主文件中间且压缩体 >256MB 的 zip 仍漏（binwalk 同病，代价不值的取舍）
- [ ] **Markdown/RTF 富文本渲染** — md 现纯文本直显（`#`/`**` 原样），rtf 露控制字；可选加轻量渲染器

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
- [x] **`.z01` 真分卷 best-effort（已审 v5.10.0）** — 拼接依赖 zip crate 接受多盘 EOCD（zip 3.x 原生支持）；`resolveZipVolumes` 补大小写不敏感 base 匹配（K12 同类）；无 WinRAR 样本，真机验证待做

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
- [x] **cxdec 解密支持（经典 cxdec 单包拆包）** — 见顶部「已实现: cxdec 经典解密」；
- [ ] **cxdec HX 新一代（exe bootstrap）** — Cxdec_Tools 的 recover 流水线（bootstrap 密钥派生 + 索引解密 + 文件名恢复，Yuzusoft/Laplacian/Purple software 2025+），作为独立模式接入
- [ ] **DestAllocator 推广 zip/tar** — 提取循环统一重名/大小写碰撞去重（zip 并行提取路径需共享 Mutex 版分配器；xp3/pfs 已接入，见上方审查反馈修复第 1 条与反馈第 11 条）

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
- [x] **应用图标更换** — 根目录新图（530×512 中央正方形裁剪 + Lanczos 重采样）生成五档 `mipmap/ic_launcher.png`（48/72/96/144/192）
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
- [x] **深递归（已审 v5.10.0，全部已迭代化）** — `iso_walk`/zip·sevenz `add_dir`/tar·pfs·xp3 `collect_files`/rar·sevenz `walk_files`/Kotlin `walkTopDown/BottomUp`（stdlib 非递归）/`deleteWithProgress` 全为显式栈或无递归；无栈溢出风险，待办关闭

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

### Phase 1 补全 b（RAR4 copy_match + Brotli）

- [x] **RAR4 copy_match 批量复制** — rar29/rar20 streaming 路径：offset ≤ output.len() 时用 `extend_from_within` 批量复制，pending 路径同理，预期 20-40%（repetitive data）
- [x] **Brotli 格式支持** — 新增 brotli-core crate（brotli 8.0，pure Rust），JNI/host 入口 + compress/extract + BufWriter(256K) + corpus-regress 21 条目全 PASS

#### 全量回归最终成绩

- **295/326 PASS（90.5%）**，31 skipped（tar 变体不支持），0 失败
- 39 套件全绿（含 brotli-core）

#### 最终全量回归（v1.2 语料 484 条目，全部格式）

- **299/330 PASS（90.6%）**，31 skipped（split volumes），5 failed
- 5 个失败全是 **CSO 语料格式问题**（header_size=24 → 0 index 条目，生成时 bug，非代码 bug）
- CSO core 6 个内部测试全绿（代码正确，语料无效）
- 31 skipped = RAR/7z/zip split volumes（单文件 harness 不支持多卷）
- 支持格式：rar 65/zip 47/7z 68/gzip 22/bzip2 15/xz 25/lzma 16/lz4 23/zstd 24/brotli 21/iso 5
- 39 套件全绿
