# 只读目录对比与检查命令

文件夹长按菜单 →「与另一个目录对比」。第二个目录可以浏览选择，也可以直接选择已有 Tab 的当前目录。
两个目录在开始时固定；比较结果属于发起操作的 Tab。切换 Tab 不会更换比较对象。没有同步、覆盖或删除操作。

## 比较规则

- 按相对路径、原始大小写匹配，不按名称相似度猜测重命名。
- 文件大小不同即有差异；大小相同仍以两个 64 KiB 缓冲区逐块比较实际字节，时间戳相同不能代表内容相同。
- 新增/删除目录及其成员会分别列出；文件与目录互换记为类型变化，不继续猜测另一侧的子节点。
- 软链接、特殊文件、不可读节点和路径/深度超限明确标记未完成，不作为相同文件。
- 读取前后检查文件标识、大小和修改时间；最后复核所列目录。如果目录结构变化，撤回普通差异结论并报告未完成。
- 最多遍历 50,000 个节点，深度最多 64，相对路径最多 1,024 字符；保留最多 2,000 项详情。触及边界不声称目录相同。
- 关闭窗口取消当前读取。所有文件都只读，不创建临时解包目录。

目录是实时数据，比较不是文件系统事务快照；结果属于读取时的状态。查看详情会重新校验文件状态，变化后要求重新比较。

## 结果与文本详情

结果可按新增、删除、修改、类型变化、不可读及跳过筛选。详情复用当前工作窗口，不为每个文件增加 Tab。
文本增删复用现有行差异组件，自动预览只接受严格 UTF-8 或 BOM 指明的 Unicode，单侧最多 64 KiB，最多 1,000 行、250,000 个 LCS 单元。
旧编码、二进制或超出上限时只显示元数据；可以主动打开文件预览，或把原译文配对送入汉化检查并明确选择编码。
如果解码后的文本行相同但字节不同，明确提示可能涉及 BOM、编码或换行，不伪造正文增删。

## UU / UUT

```text
uu dircmp <原目录> <目标目录> [-j] [--after N] [--limit N]
uu check <原文> <译文> [-j] [--profile encoding|named|printf] [options]
```

`uu check` 选项：`--source-encoding`、`--translation-encoding` 默认 `auto`；`--target-encoding` 默认 `translation`，也可为 `source` 或明确编码。
完整编码和语法边界见[汉化检查说明](localization-check.md)。`--after` 为前页返回的游标；默认一页 20 项，`--limit` 为 1–50。
`uu docs` 的参数表和 MCP `command_help` 均可发现新命令。

两个命令都采用比较工具退出码：

| 退出码 | 含义 |
|---|---|
| 0 | 完整比较无差异，或已启用检查规则无发现 |
| 1 | 有目录差异，或文本检查有错误/待确认项；不是命令执行故障 |
| 2 | 输入错误、读取失败、覆盖不完整或结果超限 |

UUT 中直接调用相同命令。默认脚本遇到非零退出码会停止；若需要处理预期差异，可用 `||` 分支，或用 `uu run -k` 继续收集检查结果。
继续执行不等于检查通过，不要忽略退出码 2 后进入覆盖、删除或封包。
GUI 和结构化工具直接显示报告状态，避免把正常差异误当异常。

## Agent / MCP

共享只读技能 `COMPARE_DIRECTORY`：

```json
{"source":"original","target":"translated","after":"","limit":20}
```

状态为 `IDENTICAL` / `DIFFERENT` / `INCOMPLETE`；`complete` 与 `limited` 必须一并检查。
为适配本地 HTTP 连接期限，结构化工具单次最多读取 128 MiB（两侧合计）、运行 5 秒；超限明确返回 `SCAN_BUDGET` / `VERIFY_BUDGET`。完整长扫描可用 GUI 或异步 `RUN_UU` / `UU_DIRCMP` 任务，再查询状态或取消，不让请求断开后持续占用服务线程。Agent 保留未完成报告的覆盖信息和相对路径，不把它丢成一条泛化错误。
结果仅含相对路径、状态、大小、受限原因和统计，不返回文件正文、绝对根路径或文本差异。
`UU_DIRCMP`、`UU_CHECK` 也会随命令表自动注册。两侧路径均经工作区校验，UUT 的新选项也受白名单约束。
应用内 Agent 仅在「文件查询」和「VN 汉化」模式获得目录对比工具，最低只读权限；普通通用会话不增加该 schema。

## 验证

主机回归覆盖同大小同日期改动、只改日期、空目录、嵌套新增/删除、类型变化、软链、并发变化、取消、详情上限、过期预览、UU 及真实 UUT 调用。
可构建单独的离线设备验收 APK：

```sh
./gradlew -PinspectionSmoke :app:assembleRelease :app:assembleReleaseAndroidTest
adb install -r app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
adb shell am instrument -w com.usefulunpacker.inspectionsmoke.test/com.usefulunpacker.InspectionSmokeInstrumentation
adb uninstall com.usefulunpacker.inspectionsmoke.test
```

验收仅使用应用私有缓存中的合成文件，测试实际字节读取、行差异、UU/UUT、编码、沙箱、设备自身的 MCP 回环端口及窗口打开/关闭。
不读取模型密钥，不调用模型接口，不修改已有游戏文件；测试 APK 独立于正式 APK。
