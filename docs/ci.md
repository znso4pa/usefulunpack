# CI 与本地验收 / CI coverage

每次 push、pull request 或手动运行工作流，执行三个独立任务：

| 任务 | 检查内容 |
|---|---|
| Resource and UI contracts | 共用控件与异常回退边界；四语言资源键、重复定义、教程标题/正文和条目数；检查器自身的故障注入回归 |
| Android host tests and lint | JDK 17、SDK 35、Build Tools 34.0.0、Gradle Wrapper 8.9；独立 Python 字段依赖对照、Clang printf 对照；Android JVM 单测、Lint、Debug 打包 |
| cargo test | Rust workspace、sevenz-rust/rars vendored fork 单测、Clippy；保留现有兄弟仓依赖配置 |

相同分支或 PR 的新运行会取消旧运行。工作流仅有仓库读取权限；PR 的 Gradle 缓存只读。
Android 测试与 Lint 报告作为 `android-host-reports` 保留 14 天，失败时同样尝试上传；构建尚未生成报告时会提示无报告。

CI 不读取模型配置，不调用模型接口，不需要发布签名或预编译 `.so`，也不分发私有游戏样本。Debug 打包仅检查源码、资源、Manifest 和打包任务，**不上传缺少原生库的 APK**。

## 本地复跑

```sh
python3 scripts/check_ui_components.py --self-test
python3 scripts/check_ui_components.py
python3 scripts/check_resources.py --self-test
python3 scripts/check_resources.py
python3 scripts/localization_oracle.py --check-printf > /tmp/uu-localization-oracle.json
UU_LOCALIZATION_ORACLE=/tmp/uu-localization-oracle.json ./gradlew --no-daemon --max-workers=2 \
  '-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8 -XX:ActiveProcessorCount=2' \
  -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
cargo test --workspace
cargo test --manifest-path crates/vendor/sevenz-rust/Cargo.toml
cargo test --manifest-path crates/vendor/rars/Cargo.toml
cargo clippy --workspace --all-targets
```

`--check-printf` 需要 Clang。Rust 需要按贡献指南放置兄弟仓；其原生真实样本仍从本地运行时读取，缺失时的 `SKIP` 不代表兼容性已经验收。

## CI 与发布的边界

- JVM 单测不能替代 Android ICU、ROM、文件系统与 JNI 的实际运行。四语言检查验证结构一致，不评价翻译质量。
- 真机应另行覆盖横竖屏、输入法、6 Tab 弹窗、任务取消、后台返回，以及真实归档与产物的独立校验。
- 签名 Release APK 仍通过完整原生构建或已有有效 JNI 库构建；CI 不自动发布、不打标签。
- `main` 的开发版本可能领先下载页；以 Releases 附件为已发布安装包。

Every push and PR runs independent resource/UI, Android host and Rust jobs. Android checks use independent localization oracles and upload test/Lint reports for 14 days, including failures. They require no signing secrets, model credentials, private samples or prebuilt JNI libraries. Debug assembly checks packaging only and is not uploaded as a usable APK. Device tests, real-file regressions, signing and human review remain separate release gates.

工具链选择与项目现有 AGP 8.7 配套，见 [Android 官方兼容表](https://developer.android.com/build/releases/agp-8-7-0-release-notes#compatibility)。缓存和 Wrapper 检查采用 [Gradle 官方 Action](https://github.com/gradle/actions/tree/v4/setup-gradle)。
