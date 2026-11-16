# 通用 UI 与失败诊断

此重构保留 Views 和既有视觉主题，组件定义在 `ui/UuComponents.kt`。尺寸/颜色继续由现有 dimen 与 color 资源提供，不新增平行主题表。

| 场景 | 公共入口 | 页面负责的内容 |
|---|---|---|
| 主/次/危险/文字操作 | UuButton + ActionStyle，XML 的 uuActionStyle | 文案、状态、动作 |
| 图标操作 | UuIconButton / UuFab | drawable、无障碍描述、动作 |
| 搜索、路径、名称、密码 | UuTextField | 字段身份、IME、输入类型、校验 |
| 布尔与选择 | UuToggleRow / UuSwitch / UuCheckBox / UuRadioButton / UuRadioGroup | 选中状态、回调 |
| 选择卡片与互斥按钮 | UuChoiceCell / UuButton.selectChoice | 标签、可用/选中状态与动作；formatCell 仅为适配器 |
| 滑块 / 颜色选项 | UuSlider / UuColorButton | 范围、值、颜色与动作 |
| 动作栏 / 多行动作区 | UuActionBar / UuActionPanel | 子项、权重与宿主背景；公共层负责几何 |
| 滚动内容 | UuScrollView / UuHorizontalScrollView | 内容；统一禁用原生滚动条 |
| 进度条 | UuProgressBar | 进度、确定/不确定模式 |
| 溢出菜单 | UuPopupMenu.action | 文案与动作；保留统一主题的原生菜单绘制 |
| 设置/侧栏/操作抽屉条目 | UuActionRow.bind | 标题、值、navigation、动作 |
| 旧式进度弹窗 | UuProgressDialog | 进度内容、取消动作；公共入口负责链 ID 与取消记录 |
| 弹窗框架 | UuDialogBuilder | 标题、正文、操作；不接管调用者的 onShow/onDismiss |
| 弹窗定位、tab 归属、遮罩及关闭清理 | DialogHost 的 belowTabs / keepTabsTappable | 宿主语义；不另设关闭监听器 |
| 书签侧栏宽度 | UuSidePanel | 内容；组件负责屏幕适配 |
| 临时提示 | UuNotice | 文案、时长；错误仍需独立报告 |

按钮、图标、滑块的最小触控规格为 48dp，主文案使用现有 14sp、条目标题使用 15sp，间距沿用 4/8/12/16/24dp。文件列表复选框保留紧凑几何，所属整行提供完整点击区域。内容编辑器、刷子画布、文件列表与格式树不是通用动作控件，不以全局规则重设其内容布局。

组件目录位于设置 → 通用组件。设置、书签、安装/扫描操作条目已经迁移；现有弹窗与按钮/输入/开关创建入口已统一。首页已有 hero/mini 卡继续使用 `kit.kt`。复杂窗口的停靠与手势仍由原 `DialogHost` 管理，没有引入第二套抽屉手势实现。

所有通用操作控件的颜色、背景、字体、内边距及最小触控尺寸由公共类持有。调用处和 XML 不再独立覆盖外观；布局权重、文字换行、输入类型与编辑器等宽字体属于内容语义，仍由页面指定。弹窗公共层给标题/正文/按钮共同父面板提供不透明圆角底，避免窗口 scrim 使正文透出；这不会给 Activity 的壁纸层增加遮挡。DialogHost 的延迟几何更新先检查 Activity、显示状态与窗口挂载。

组件覆盖清单：主工具栏、路径栏、预览动作区、批量动作、设置（含 XML 压缩设置）、书签侧栏、格式与媒体选项、搜索模式、文件行书签按钮、tab 关闭按钮、终端输入及命令路径选择、图片编辑工具/颜色/滑块、文本预览的编码与渲染选项。图片工具与色板置于统一水平滚动容器，避免窄屏时保存按钮或颜色被挤出。

专用展示边界：文件/归档成员/搜索命中/扫描命中行、目录树、主页 hero/mini 卡、tab 的标题与角标、终端输出、代码文本、图像画布及磁盘容量可视化保留各自的数据展示与交互语义；其中的通用按钮、复选框、图标动作仍复用上述组件。基础 TextView、ImageView 与 LinearLayout 本身不是禁止项，不能把它们用于另造通用操作控件。

## 用户意图链与日志

一次用户意图建立一条链：例如「合并」「解压全部」「扫描后切割」。`userIntent` / `onUserIntent` 是入口，`AppDiagnostics.begin` 只建立可失败步骤，不为每次 IO 创建新链。没有用户链的后台诊断进入 `application.log`。

- 短链 ID 在弹窗、通知、线程名和 `.log` 中保持一致。步骤带序号、父步骤、状态、单调时钟耗时与稳定错误码。合并按 `extract_target` → `extract_source` → `repack` 执行，失败中止依赖步骤。
- `featureThread`、`executeFeature`、`runFeatureOnUiThread` 与共享弹窗保留异步上下文；最后一个持有者释放后写入终态。独立操作开启新链，密码重试等延续原链。结果确认弹窗可用 `keepChainOpen=false` 避免延迟完成。线程池提交拒绝也记录失败并释放引用。Activity 已销毁时，UI 派发可通过 `onUnavailable` 完成不触碰 UI 的收尾（如释放密码等待 latch）。
- 终态包括 SUCCESS、FAILED、PARTIAL_FAILURE、CANCELLED。主步骤成功而仅 cleanup 失败记部分失败；主步骤失败保留失败，取消保持 CANCELLED，同时保留 cleanup 错误与可见提醒。清理失败不能被取消过滤。
- `observedAttempt` 为刻意的异常 fallback 留下诊断；取消继续传播。格式/编码候选不匹配仍是正常负向探测。`UuNotice` 的通知归入当前链；错误另由持久失败摘要呈现，可直接打开日志，页面恢复时补呈待处理错误。
- `filesDir/logs/<chainId>.log` 默认只在本地，最多 40 份，每份正文上限 64 Ki 字符，后台合并写入。保存失败保留有界内存副本并显示提示。设置 → 日志与诊断按功能查看状态、清理问题与可复制详情；只有主动分享才导出。上次进程留下但没有终态的文件标为 UNFINISHED，不伪装成仍在执行。
- 开始意图时注册密码/密钥；路径父目录截断，禁止原始 argv、命令正文、脚本与文档载荷进入自动日志。链 ID、状态和错误码属于元数据，不做密码字符串替换，避免单字符密码破坏检索身份。
- UUT 旁边自动生成的 `<script>.log` 改为执行回执：链 ID、功能、状态与退出码。原脚本和 stdout 不自动写入；完整诊断在设置中按同一 ID 查找，输出仍可在终端查看或显式重定向。
- 未捕获的 Java/Kotlin 异常尽力落盘后交回原崩溃处理。系统强杀/原生崩溃不能保证落盘，不自动恢复任务。本次未创建后台任务服务或 MCP 服务。

已接入的意图入口涵盖解包/批量解包、压缩、预览与条目编辑、跨归档合并、工作区、复制/回收/还原、搜索/磁盘统计、扫描/切割、媒体转换、图片/文本编辑、终端与 UU/UUT。此覆盖清单不等于证明所有底层库的每一条故障分支都已验证。

回收站使用独立 `payload` 与元数据，兼容旧布局。复制完整前不删除源；移动后即使进度、元数据或索引更新失败，也保留可恢复备份。还原完成后的索引失败不回滚已还原文件。清理失败保留记录。主删除使用 `deleteOutputChecked(..., cleanup=false)`，其失败属于主操作 IO 错误；删除半成品/缓存使用默认 cleanup 语义，二者不能混淆。清空或永久删除失败不再移除索引或显示成功。

图片和文字编辑通过同目录暂存文件发布完整输出；编码返回 false 或写入异常会保住原文件和上一份恢复副本。自动保存保留编辑链 ID，并避免成功保存后在途写入复活恢复文件。文字保存失败保留编辑窗口，图片保存失败保留编辑器。

## 验证

运行 `python3 scripts/check_ui_components.py --self-test`、`python3 scripts/check_ui_components.py`、Android 单测、lintDebug、四语言键集合与教程分隔检查。静态门禁只防止已知入口重新分叉，无法识别所有自绘控件或证明视觉一致。

本次本地检查：Android 259 项单测通过；Lint 0 错误 / 272 警告；Release 构建通过；Rust workspace 426 项通过。四语言各 734 个 string 键一致、20 个教程均有标题/正文分隔，XML、静态门禁与 diff 空白检查通过。Rust 仅调整扫描测试临时目录隔离，未更改格式编解码或 JNI 接口。

模拟器抽查：只读启动 ARM64 Android 模拟器，安装 Release APK；检查首次启动、设置、组件目录的横竖屏切换、日志入口与压缩设置行，记录并修复弹窗延迟布局崩溃、面板透底和滑块触控高度。字体倍率变更会重建 Activity，此轮未完成全部页面的大字体视觉验收。

待真机与人工验收：手机/平板横竖屏、放大字体、四语言、不同 tab 切换时弹窗归属、遮罩关闭、排队取消、同库任务串行、合并与普通任务互斥、壁纸四区域透出、Honor 原生滚动条规避。无设备时必须明确标为待验收。
