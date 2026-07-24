# TODO

## 下版本计划

- [ ] **分卷支持** — RAR/ZIP/7z 多分卷归档的识别与解压
- [ ] **XP3 封包** — XP3 格式的打包/压缩功能
- [ ] **PFS 封包** — PFS/PF6/PF8 格式的打包/压缩功能
- [ ] **UI 重构** — 优化交互流程
- [ ] 完善单元测试与 CI/CD

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
