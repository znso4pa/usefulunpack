# 贡献指南

[**English**](CONTRIBUTING.md) | [**中文**](CONTRIBUTING-zh.md)

> **太长不看** — `bash build.sh` 构建；每次 PR 前 `cargo test --workspace` + `./gradlew lintDebug`；
> 标题使用约定式前缀；**通读一遍[多窗口与并行架构](#多窗口与并行架构必读)**——v5.14 起引入真并行，有几条契约不能破坏。

## PR / Issue 格式

请在 PR 标题和 Issue 标题中使用约定式提交前缀：

| 前缀 | 用途 |
|------|------|
| `feat:` | 新格式或新功能 |
| `fix:` | 修复 Bug |
| `refactor:` | 重构代码，不改变功能 |
| `docs:` | 文档更新 |
| `chore:` | 构建、CI 或维护任务 |
| `perf:` | 性能优化 |
| `style:` | 代码格式化（无逻辑变更） |
| `test:` | 添加或更新测试 |

示例：`feat: 添加 RAR 密码支持`、`fix: LZ4 列表显示 0 字节`、`refactor: 提取公共密码对话框逻辑`

## 新格式 PR 要求

提交添加新存档格式的 PR 时，**必须**在提交前测试以下所有项目：

- [ ] **完整解压** — 解压整个存档，无报错
- [ ] **多选解压** — 长按选择多个文件后解压
- [ ] **选择解压** — 预览存档，勾选特定文件后仅解压选中项
- [ ] **预览** — 在存档预览中点击单个文件（文本/图片/音频），验证内联预览正常
- [ ] **并行冒烟** — 其他格式的操作运行时，本格式的操作能并发执行；同格式第二个操作会排队而非竞态

请在 PR 描述中附带截图或简要说明确认各项通过。

## 项目结构

```
usefulunpack/
├── app/src/main/java/com/usefulunpacker/   # Android 应用 (Kotlin)
│   ├── MainActivity.kt                     # Activity 外壳（约840行）：生命周期、装配、
│   │                                       #   标签栏适配器、改名对话框、内存徽标
│   ├── archive/                            # OpScheduler（并行调度）、ExtractProgress
│   │                                       #   （OpOverlay 进度层）、ArchivePreview、JNI 辅助
│   │                                       #   ⚠ 本目录两个文件声明的是根包——见「历史陷阱」
│   ├── browse/                             # nav()/select()/解压流程、TabState、FolderFragment、
│   │                                       #   多选栏、FileBrowser 分发
│   ├── batch/                              # 批量解压 / 批量压缩 / 批量预览窗
│   ├── extract/                            # 整包/选择解压；PreviewFlow（全仓最大文件：
│   │                                       #   合并、编辑封回、嵌套归档、ZIP 条目管理）
│   ├── compression/                        # 压缩分发 + 内联压缩选项
│   ├── fileops/                            # 签名扫描/切割、回收站+对话框、删除进度、
│   │                                       #   改名/比较、CSO 转换
│   ├── search/                             # 全局搜索 + 搜索源解析
│   ├── ui/                                 # 设置、选择器、文本/图片编辑器与预览、目录选择器
│   ├── model/                              # ArchiveEntry / ExtractCounts / SearchResult
│   ├── adapter/                            # FileAdapter / PreviewAdapter
│   ├── bookmarks/ terminal/ util/          # 杂项（Constants、FileUtils、编码探测…）
│   └── <X>Core.kt                          # 各格式 JNI 桥接对象
├── crates/                                 # Rust 原生库（每格式家族一个 cdylib）
│   ├── common/                             # 共享：json_escape、safe_join、BoundedWriter、
│   │                                       #   progress_store!（每格式全局唯一进度/CANCEL 槽）
│   ├── <fmt>-core/ …                       # xp3 pfs nsa iso ypf zip sevenz rar tar ksd lz4 gzip
│   │                                       #   bzip2 xz zstd lzma brotli cso scan-core
│   └── vendor/                             # 内置 fork（rars、sevenz-rust、isomage、zip）+ 补丁
├── build.sh                                # 一条命令：Rust 三 ABI 交叉编译 + Gradle APK
└── .github/workflows/ci.yml                # cargo test --workspace + clippy（非致命）
```

每个格式是独立 `.so`，经 `System.loadLibrary` 加载。Kotlin `<X>Core.kt` 对象声明 `external fun`，与对应 crate 中 `#[no_mangle]` 的 JNI 函数一一对应。

## 多窗口与并行架构（必读）

v5.14 起 App 最多 **3 个操作真并行**，且有一条硬性平台约束：**Rust 侧每个格式 crate 只有一份全局进度/CANCEL 槽**（`crates/common` 的 `progress_store!`）。同格式两个并发操作会互相踩踏进度条和取消标志。下面的一切都源于这句话。

### 新操作的接入契约

```kotlin
val opH = tryStartOperation(activity, fmtKey)     // 永不阻塞、永不拒绝
…
thread {
    if (!opH.await()) return@thread               // 排队中被取消 → 静默放弃
    try { /* 真正的工作 */ }
    finally { opH.release() }                     // token 式释放，任意线程安全
}
```

- `fmtKey` 即格式字符串（`"zip"`、`"xp3"`…）。同键串行；不同键共享 3 个全局槽位。
- 同时驱动两种格式的复合流程用伪键：合并走 `"merge"`，ZIP 条目编辑走 `"zip"`。
- **密码弹窗必须发生在 `tryStartOperation` 之前**——模态框可能阻塞 ~30 秒，绝不能占着槽位或格式锁。
- 取消语义：进度卡上的显式 ✕ 走 `cancelQueuedThenNotify()`——排队中的操作直接出队、**不触碰 Rust CANCEL 标志**（否则会误杀另一窗口正在运行的同格式操作）；只有运行中的取消才触发 `accessors.cancel()`。
- 刻意例外：删除/回收站（纯文件操作）与签名扫描（Rust `SCAN_LOCK` 已串行化）保留旧 `OperationLock`，迁移前先复核这条理由。
- 每个新 JNI 操作入口必须先调 `clear_cancel()`——否则上一次操作的取消会毒化本次。

### 进度 UI

一律走 `PollingProgressDialog`——v5.14 起它渲染到 activity 内共享的 **OpOverlay 悬浮层**：除进度卡片本身外全部穿透触摸（遮罩仅视觉变暗不拦截）——**操作中切窗、在其他窗口继续干活都畅通**；卡片纵向堆叠；在内容区内居中。它刻意不允许触摸外部/返回键取消——显式按钮是唯一取消路径。排队中的操作显示「⏳ 第N位 · 约Xs」，不会去轮询别人的静态量。

## 历史陷阱（改这些文件前先读）

- ⚠️ **包声明与目录不符**：`archive/ArchiveExtractor.kt` 与 `archive/ExtractProgress.kt` 位于 `archive/` 目录但声明的是**根包** `com.usefulunpacker`。跨包引用它们的符号需要显式 import——已坑过维护者两次，其中一次伪装成编译器灵异事件耗掉一小时。
- **多选栏按钮**：显隐由 `buildBatchBar` 设置的语义 `tag`（`"extract"`/`"preview"`/`"compress"`）驱动，在 `syncMultiBar` 匹配。绝不要匹配显示文本——文案内嵌 emoji 且跨版本变过，文本匹配曾把解压模式下的解压按钮藏掉。emoji 只存在于 strings.xml，代码里绝不拼接。
- `viewPager.offscreenPageLimit = MAX_TABS - 1` 保证所有 tab 的 Fragment 存活、切换不丢状态。不要调小。
- **荣耀/EMUI ROM**：自定义 ScrollView/TextView/EditText 不得启用原生滚动条（ROM 在 `onDrawScrollBars` NPE）；列表用可拖拽快速滚动柄替代。
- 任何异步完成后的 `runOnUiThread { ...弹对话框... }` 必须加 `isFinishing || isDestroyed` 守卫——这类 BadTokenException 已经灭绝两次了，别让它复活。

## 环境搭建

### 前置要求

- **Rust**：[rustup](https://rustup.rs) 安装
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
  ```
- **Android NDK**（r28+）：设置 `ANDROID_NDK_HOME` 或放在 `ANDROID_HOME/ndk/`
- **cargo-ndk**：`cargo install cargo-ndk`
- **Android SDK**：API 34+

### 构建

```bash
bash build.sh
```

交叉编译全部 Rust workspace crate 到 `arm64-v8a`、`armeabi-v7a`、`x86_64`，复制 `.so` 到 `app/src/main/jniLibs/`，然后跑 `gradlew assembleRelease`。

## 新增归档格式

1. 建 `crates/<name>-core/`，`cdylib` crate，依赖 `archive_common`
2. 实现：
   - `list_<name>_inner(input) -> Result<String, String>` — 条目 JSON 数组 `[{"n":"...","s":...,"d":...,"e":...}]`
   - `extract_<name>_inner(input, output, selected?, password?)` — 解压
   - 若支持加密：`needs_password_inner(input)` — 检测是否需要密码
   - 与 Kotlin 声明对应的 `#[no_mangle]` JNI 导出
3. 建 `app/src/main/java/com/usefulunpacker/<Name>Core.kt` 声明 `external fun` 并 `System.loadLibrary`
4. Kotlin 侧注册：
   - `extractByFormat()`（`ArchiveExtractor.kt`）加 `when` 分支
   - `formatOfName()`（`util/Constants.kt`）加扩展名映射
   - `previewArchive()` 加列表分支；如适用，在 `tryExtractWithPassword()` / `showPasswordDialog()` 加密码分支
   - `extractAccessors()` / `compressAccessors()`（`ExtractProgress.kt`）接进度 getter 与 cancel
5. crate 加入 `Cargo.toml` workspace members 和 `build.sh` 的 `CRATES` 数组
6. 字节级进度：调用 `extract_progress::reset/set_file/add_bytes`（打包走 `compress_progress::*`）。整成员缓冲解码的格式（rar ≤64MB 路径），顶条由写入侧喂（`ProgressWriter::extract`）以保证总量精确——不要用轮询解码计数器喂顶条（会少计）
7. 错误文案加入 `res/values/strings.xml`（四个语言目录齐全：`values/`、`-zh-rCN/`、`-zh-rTW/`、`-ja/`）
8. 无需其他注册：调度器自动识别你的格式（fmt key 就是格式字符串）

## 代码约定

- **Rust**：紧凑单行 JNI 函数风格；函数体用 `guarded()` 包裹防 panic 穿越 JNI；复用 `archive_common::{s, json_escape, safe_join}`。
- **Kotlin**：磁盘/网络操作进后台线程；UI 回 `runOnUiThread` 并按上文加守卫；格式分派用 `when`。
- **格式 JSON**：条目字段 `"n"`（名）、`"s"`（大小 int）、`"d"`（是否目录 bool）、`"e"`（是否加密 bool）。
- **密码格式三件套**：基础 `extractWithPassword(...)`、`extractSelectedWithPassword(tool, input, output, selected, password)`、检测 `needsPassword(input) -> Boolean`。
- **选择性解压**：换行分隔路径串；`HashSet` O(1) 查找；精确路径 + 目录前缀双匹配。
- **异步完成弹窗**：展示前守卫 `isFinishing || isDestroyed`。
- **版本纪律**：一次发布一条叙事 commit（`feat(vX.Y.Z): …`）、TODO.md 每版一个专属段落、`app/build.gradle` 同步 bump `versionCode`/`versionName`。

## 测试

CI 跑 `cargo test --workspace`（+ clippy，非致命）。开 PR 前本地先跑：

```bash
cargo test --workspace          # Rust 全套（见下）
./gradlew lintDebug             # 基线：0 errors / 约268 warnings —— 不要新增 errors
bash build.sh                   # 完整 APK（需 NDK）；纯 Kotlin 改动可只跑 :app:assembleRelease
```

Rust 测试覆盖面（保持全绿的理由）：

- **往返/真实语料** — 每个 `*_core` 先打包再解压并比对字节相等；真实语料库（`files4testing` 约423向量+14格式的13个注入故障）验证兼容性：合法归档解压哈希一致，注入故障（截断/损坏/错密码/缺卷）干净拒绝。
- **安全/恶意头** — 构造输入拒绝且不 abort：解压炸弹（`BoundedWriter` 封顶）、超大头数量（7z num_files/coders、ISO 目录尺寸）、负数/溢出长度（KSD、PFS offset）、路径穿越（`safe_join`）、栈深限制（ISO 目录）。
- **签名扫描** — 真实压缩样本向量（gzip/bzip2/xz/zstd/lz4/lzma）捕获字节序/位域回归。历史战绩：对照 binwalk 3.1 的 201 处语义差异全部占优。
- **真实归档 rar 探针** — 环境变量门控的磁盘测试：`UU_RAR_PROBE=/path/to/big.rar cargo test -p archive_rar_core probe_real_archive_progress -- --nocapture` 断言顶条精确到总量；`UU_RAR_SEL_PROBE` 验证非固实随机访问跳过前置成员。

真机回归要点（UI/ROM 敏感改动合入前）：

- 荣耀/EMUI 滚动条 NPE 规避（见历史陷阱）。
- 全新目录中途中止解压须清空整个输出；被取消的 carve 不得向下游交付半截文件。
- 文本预览/编辑器：BOM 自动探测、编码即时切换、乱码读取提示。
- 并行回归（v5.14+）：不同格式两操作重叠执行；同格式两操作排队；排队取消不打扰正在运行的兄弟操作。

## 提交 PR 前

1. 所有 crate `cargo check` 干净；`cargo test --workspace` 全绿
2. `./gradlew lintDebug` 不新增 errors
3. `build.sh` 完成（需 NDK）
4. 若解决了 TODO.md 所列问题，更新对应条目；并在当前开发段落加一行说明
5. 一个 PR 一件事；不顺手重排无关代码
6. 新格式 PR：勾选[新格式 PR 要求](#新格式-pr-要求)的全部五项

## 欢迎认领的方向

[TODO.md](TODO.md) 顶部跟踪着活着的计划——好的切入点包括**预览行长按菜单**（归档条目的分享/信息/提取此项）、**预览排序**，以及「已知限制」清单下的各项。更大的设计定稿轨道（预览工作区 tab、CLI 复活计划）也记录在案——开个 issue 即可认领。

## 许可证

提交贡献即表示同意其以 MIT 许可证发布。
