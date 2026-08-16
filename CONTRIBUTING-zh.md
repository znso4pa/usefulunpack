# 贡献指南

[**English**](CONTRIBUTING.md) | [**中文**](CONTRIBUTING-zh.md)

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

提交添加新存档格式的 PR 时，**必须**在提交前用各种必要方式测试以下所有项目：

- [ ] **完整解压** — 解压整个存档，无报错
- [ ] **多选解压** — 长按选择多个文件后解压
- [ ] **选择解压** — 预览存档，勾选特定文件后仅解压选中项
- [ ] **预览** — 在存档预览中点击单个文件（文本/图片/音频），验证内联预览正常工作

请在 PR 描述中附带截图或简要说明，确认上述各项均已通过。

## 项目结构

```
usefulunpack/
├── app/src/main/java/com/usefulunpacker/   # Android 应用 (Kotlin)
│   ├── MainActivity.kt                     # Activity 外壳 — 生命周期与装配；业务逻辑已拆出
│   │                                       #   （MainActivity 约 360 行）
│   ├── browse/                             # nav()/select()/解压流程、多选
│   ├── batch/                              # 批量解压/压缩/预览
│   ├── extract/                            # 解压全部/所选、预览流程、编辑+重打包
│   ├── search/                             # 全局搜索、归档内搜索、搜索源解析
│   ├── ui/                                 # 对话框：设置、帮助、文件夹选择、文本预览/编辑器、格式选择
│   ├── fileops/                            # 签名扫描、切割、删除进度、重命名/移动
│   ├── compression/                        # 压缩派发 + 内联压缩选项
│   ├── archive/                            # 解压辅助 + PollingProgressDialog 访问器
│   ├── util/                               # 常量、文件工具、文本编码自动探测
│   ├── Xp3Core.kt / ZipCore.kt / ...       # 各格式 JNI 桥接对象
│   └── ArchiveCore.kt                      # 共享工具函数
├── crates/                                  # Rust 原生库
│   ├── common/                              # 共享工具 (json_escape, safe_join, 进度静态量, BoundedWriter 等)
│   ├── xp3-core/ / pfs-core/ / nsa-core/ / ypf-core/ / iso-core/   # 各格式 cdylib crate
│   ├── zip-core/ / sevenz-core/ / rar-core/ / tar-core/ / ksd-core/
│   ├── lz4-core/ / gzip-core/ / bzip2-core/ / xz-core/ / zstd-core/ / lzma-core/
│   ├── scan-core/                           # 签名扫描引擎 (binwalk 风格, 零依赖)
│   └── vendor/                              # 内购分叉 (rars, sevenz-rust, isomage) + [patch.crates-io]
├── build.sh                                 # 一键构建: Rust 交叉编译 + Gradle APK
├── Cargo.toml                               # 工作区根配置
└── build.gradle                             # Gradle 项目配置
```

每个格式都是一个独立的 `.so` 文件，通过 `System.loadLibrary` 加载。Kotlin 的 `*Core.kt` 对象声明 `external fun`，与对应 crate 中的 `#[no_mangle]` JNI 函数一一对应。

## 环境配置

### 前置条件

- **Rust**: 通过 [rustup](https://rustup.rs) 安装
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
  ```
- **Android NDK** (r28+): 设置 `ANDROID_NDK_HOME` 或放入 `ANDROID_HOME/ndk/` 下
- **cargo-ndk**: `cargo install cargo-ndk`
- **Android SDK**: API 34+

### 构建

```bash
bash build.sh
```

该命令会交叉编译全部 Rust crate，目标平台为 `arm64-v8a`、`armeabi-v7a`、`x86_64`，将 `.so` 文件拷贝到 `app/src/main/jniLibs/`，然后执行 `gradlew assembleRelease`。

## 添加新存档格式

1. 创建 `crates/<name>-core/` 目录，包含一个依赖 `archive_common` 的 `cdylib` crate
2. 实现以下函数：
   - `list_<name>_inner(input) -> Result<String, String>` — 返回条目的 JSON 数组 `[{"n":"...","s":...,"d":...,"e":...}]`
   - `extract_<name>_inner(input, output, selected?, password?)` — 解压文件
   - 如果支持加密: `needs_password_inner(input)` — 检测是否需要密码
   - JNI `#[no_mangle]` 导出函数，与 Kotlin 声明对应
3. 创建 `app/src/main/java/com/usefulunpacker/<Name>Core.kt`，包含 `external fun` 声明和 `System.loadLibrary`
4. 在 `MainActivity.kt` 中注册格式：
   - `extractByFormat()` — 添加 `when` 分支
   - `previewArchive()` — 添加列表读取分支
   - `EXT_FORMAT_MAP` — 添加文件扩展名映射（如有）
   - `tryExtractWithPassword()` / `showPasswordDialog()` — 如果支持加密，添加密码处理分支
5. 将 crate 添加到 `Cargo.toml` 工作区成员列表和 `build.sh` 的 `CRATES` 数组中
6. 上报字节级进度：调用 `extract_progress::reset/set_file/add_bytes`（封包用 `compress_progress::*`）使共享双层进度条生效，并在 Kotlin `extractAccessors()`/`compressAccessors()` 注册格式。对"整块缓冲解码"的格式（rar 的 ≤64MB 路径），顶条必须由**写盘**驱动（`ProgressWriter::extract`，或底条由解码 watcher 单独驱动时用 `extract_top` + `add_top_bytes`）以保持总量精确——**绝不要**用轮询的解码计数喂顶条（会漏掉轮询间隙内解完的成员）。
7. 在 `res/values/strings.xml` 中添加错误消息字符串资源

## 代码规范

- **Rust**: 沿用现有的紧凑单行 JNI 函数风格。使用 `guarded()` 捕获跨越 JNI 边界的 panic。复用 `archive_common::{s, json_escape, safe_join}`。
- **Kotlin**: 遵循 `MainActivity.kt` 中的现有模式。使用 `when` 表达式进行格式分发。在后台线程中执行解压，使用 `runOnUiThread` 更新界面。
- **格式 JSON**: 条目对象必须包含 `"n"`（名称字符串）、`"s"`（大小为整数）、`"d"`（是否为目录布尔值）、`"e"`（是否加密布尔值）。
- **密码支持**: 支持加密的格式需要三种变体：
  - 基础 `extractWithPassword(tool, input, output, password)`
  - `extractSelectedWithPassword(tool, input, output, selected, password)`（选择性解压）
  - `needsPassword(input) -> Boolean`（提前检测是否需密码）
- **选择性解压**: 接受换行分隔的路径字符串。使用 `HashSet` 实现 O(1) 查找。同时匹配精确路径和前缀匹配（用于目录子文件）。

## 测试

Rust 单元测试 + 手动 APK 冒烟测试。CI 会运行完整工作区套件，PR 前请在本地运行：
```bash
cargo test --workspace
```

Rust 测试覆盖内容（保持全绿）：
- **往返 / 真实语料** — 各 `*_core` crate 封包后再解包并逐字节比对。使用真实语料库 `files4testing`（约 423 向量 + 13 条注入故障，覆盖 14 种格式）：合法归档必须解出哈希一致，注入的故障（截断/损坏/错密码/缺卷）必须被干净拒绝。
- **安全 / 恶意头** — 单元测试断言构造输入在不上崩的前提下被拒绝：解压炸弹（经 `BoundedWriter`/声明大小封顶输出）、超大头部数量（7z num_files/coders、ISO 目录大小）、负数/溢出长度（KSD、PFS 偏移）、路径穿越（`safe_join`）、栈深度限制（ISO 目录）。
- **签名扫描** — scan-core 内置真实压缩样本向量（gzip/bzip2/xz/zstd/lz4/lzma），防止字节序/位域回归。
- **真实归档 rar 探针** — `rar-core` 内置两条环境变量门控测试：`probe_real_archive_progress`（`UU_RAR_PROBE`）整包解压并断言顶条精确到 total（无漏/无双计）；`probe_selected_fast`（`UU_RAR_SEL_PROBE`）验证非 solid 随机访问路径解尾部成员不解前面。用法：`UU_RAR_PROBE=/path/to/big.rar cargo test -p archive_rar_core probe_real_archive_progress -- --nocapture`。

Kotlin 改动通过构建 APK 冒烟验证：
```bash
bash build.sh   # 或: ./gradlew :app:assembleRelease  (仅 Kotlin 改动)
```

涉及 UI/ROM 的改动合并前真机要点：
- 荣耀/EMUI：自定义 ScrollView/TextView/EditText **不得启用原生滚动条**（该 ROM 在 `onDrawScrollBars` 上 NPE）；列表使用可拖快滑把手。
- 解压中取消到新建文件夹必须整目录删除；取消切割不得把半成品文件交给解压器。
- 文本预览/编辑器：UTF-16/UTF-8 BOM 自动探测、编码内联切换、乱码时"看起来是 UTF-8"提示。

## 提交 PR 前

1. 确保所有 crate 的 `cargo check` 通过
2. 确认 `build.sh` 无错误完成（需要 NDK）
3. 如修复已知问题，更新 `TODO.md`
4. 保持修改聚焦 — 每个 PR 只包含一个功能或修复
5. 匹配现有代码风格（不要重新格式化无关代码）
6. 新格式 PR：验证 [新格式 PR 要求](#新格式-pr-要求) 中列出的 4 个测试项目

## 许可证

贡献即表示您同意将您的贡献以 MIT 许可证授权。
