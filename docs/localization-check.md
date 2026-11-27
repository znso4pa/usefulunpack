# 本地汉化检查

文本预览 →「汉化检查」→ 选择原文、译文和检查规则。文件只读，不会自动修复或保存副本。
目录对比中的文件配对也可直接进入；UU/UUT 使用 `uu check`，选项和退出码见[目录对比与检查命令](directory-comparison.md)。

这是文本安全检查，不能判断翻译准确性，也不是游戏引擎的语法解析器。设计优先减少误报：
无法确认编码或语法时明确中止或跳过，不把猜测转成错误。未发现问题只表示已启用、已覆盖的规则没有命中。

## 默认范围

- 默认 `encoding`：严格解码两份文本，并检查译文能否表示为用户指定的保存编码。
- 默认保存编码是译文当前编码；不会假定汉化后仍必须使用原文编码。只有用户明确要求时，才选原文编码或指定其他编码。
- 自动读取只接受 BOM 或严格有效的 UTF-8。Shift-JIS、GBK 等旧编码须明确选择，不做启发式猜测。
- 单独支持 CP932（Windows-31J）、GB18030、UTF-16LE 和 UTF-16BE，不收窄为 Shift-JIS / GBK。自动模式仍不猜旧编码；显式 Unicode 字节序与 BOM 冲突时返回 `BOM_ENCODING_CONFLICT`。
- 编码目录使用平台的精确字符集实现，CP932 映射到 `windows-31j`；名称对照见 [Java Supported Encodings](https://docs.oracle.com/en/java/javase/24/intl/supported-encodings.html)。
- 编码变化、BOM 变化、CRLF/LF 变化、空行、重复次数或不可见字符变化本身不报问题。
- 不按日文残留、译文长度、标点或字数给出“漏译/错译”结论，也不调用模型评分。

## 主动启用的占位符规则

只有确认两份文本按物理行对应，并确认格式语法后才启用。行数相等只是必要条件，不能自动证明对应关系。
行数不同会跳过所有逐行占位符检查；无法识别某一行的完整语法时，整行跳过，不比较部分提取结果。

### `named`：Python 格式字段依赖

按受支持的 Python 格式串语法读取所需参数，支持命名/显式序号/自动序号、点属性、数字或 ASCII 名称下标、`!r/!s/!a` 转换、格式说明中的一层动态字段，以及 `{{` / `}}` 字面括号。

- `{name} {count}` → `{count} {name}` 不报问题。
- `{name} {name}` → `{name}` 不报问题，重复引用不需要新参数。
- `{00}` 与 `{0}`、`{items[00]}` 与 `{items[0]}` 分别指向同一参数，不报问题。
- `{name!r:>20}` → `{name!s:<12}` 所需参数相同，不报问题。
- `{} {}` → `{1} {0}`、`{:{}}` → `{0:{1}}` 不报问题；自动序号在同一行内按包含嵌套格式的调用顺序编号。
- `{value:{width}}` → `{value:12}` 失去了动态宽度参数，产生待确认项；`{name}` → `{other}` 同样产生待确认项。
- 不求值，不执行属性访问、下标或表达式，也不根据真实对象类型验证格式说明的含义。
- 不支持混用自动与显式数字序号、未知转换、更深的动态嵌套、非 ASCII 字段/下标或超出复杂度上限的字段；整行跳过，保留明确覆盖信息。每行最多 256 个不同依赖。

定义参考 [Python format-string syntax](https://docs.python.org/3/library/string.html#format-string-syntax)。
`scripts/localization_oracle.py` 用标准库 `Formatter.vformat` 完成分词、自动编号、属性/下标和嵌套字段解析，独立生成 **1,600 组**对应关系；记录器只替换取值和最终显示，不复制 Kotlin 解析算法。
单独采用明确的子集边界，不能把任意游戏引擎的 `{...}` 自动视为 Python 格式串。

### `printf`：POSIX 参数签名

解析参数序号和类型，而不是比较占位符字面文本。宽度/精度中的 `*` 也消耗参数。

- `%s %d` → `%2$d %1$s` 是合法重排，不报问题。
- `%02d %.3f` → `%i %g` 的参数类型没有变化，不报问题。
- `%*.*f` → `%3$*1$.*2$f` 不报问题。
- `%s %d` → `%d %s` 参数位置的类型变化，产生待确认项。
- `%%` 不消耗参数；重复引用显式序号不增加参数。
- 混用显式/隐式序号、参数位置有空洞、同一参数要求冲突类型、其他方言（例如 Python `%(name)s`）及不支持的格式均跳过整行。
- 这不是 Python `%`、C++ format、Ren’Py 或其他 VN 引擎的通用占位符识别。必须明确选择对应语法。

参数模型参考 [ISO C 工作草案 N3096，fprintf](https://www.open-std.org/jtc1/sc22/wg14/www/docs/n3096.pdf)
以及 [POSIX fprintf](https://pubs.opengroup.org/onlinepubs/9799919799/functions/fprintf.html)。

## 结果、隐私与限制

- `TARGET_UNMAPPABLE`：本行不能用用户要求的保存编码表示，是确定的编码错误。
- `PLACEHOLDERS_CHANGED`：已主动启用的语法中，原译文所需参数不同；属于人工确认项。
- 原译文片段只在本地结果详情展示，来自本次检查快照，每侧最多 240 个 UTF-16 单元，截断不会切开代理对。不会为预览再次读取可能已经修改的文件。
- 覆盖统计区分已解析行、含占位符行、未解析行；本地提供前 20 个未检查行及原文/译文归属和快照片段。覆盖说明放在可滚动区域，避免短横屏挤掉问题列表。
- Agent/MCP 仅获得代码、行号、编码与覆盖统计，附前 5 个未检查行的归属摘要；不返回原文、译文、占位符内容或完整路径。
- 本地诊断只记录规则模式、数量、覆盖范围及稳定失败原因，不记录文件文本。
- 每份文件最多 2 MiB、20,000 行，每行最多 65,536 字符。读取期间发现文件标识、大小或时间戳变化会拒绝该快照。
- 保留前 1,000 项详情，汇总计数仍包含全部结果。模型每次默认 20 项、最多 50 项，使用 `nextAfter` 分页。
- 窗口关闭取消本次检查；切换 Tab 隐藏后仍保留结果。所有窗口继续由共享 DialogHost 管理。

## Agent / MCP

共享技能 `LOCALIZATION_CHECK`，最低只读权限；应用内仅向「VN 汉化」任务模式提供此工具，不增加普通对话的工具 schema 开销。MCP 先按名称查询技能 schema，避免每次获取全部接口。

```json
{
  "source": "source.txt",
  "translation": "translated.txt",
  "sourceEncoding": "auto",
  "translationEncoding": "auto",
  "targetEncoding": "translation",
  "profile": "encoding",
  "limit": 20
}
```

`sourceEncoding` / `translationEncoding` 可选 `auto`、`UTF-8`、`SHIFT-JIS`、`CP932`、`GBK`、`GB18030`、`UTF-16`、`UTF-16LE`、`UTF-16BE`。检查器使用单独的精确编码目录，不改变其他编辑/预览页面的全局编码设置。
`targetEncoding` 还支持 `source` 和 `translation`；`profile` 可选 `encoding`、`named`、`printf`。
占位符规则应由用户明确确认语法与逐行对应关系后启用。工具不会根据文件后缀自行宣称兼容某个引擎。

结果状态为 `ERRORS_FOUND`、`REVIEW_REQUIRED`、`INCOMPLETE` 或 `NO_FINDINGS`。
`lineChecks` 明确标记禁用、行数不符、部分语法跳过或已应用；`checkedLines`（占位符语法已解析行）、`placeholderLines`（含参数依赖行）、`skippedLines` 和 `limited` 不能被忽略。`skippedExamples` 默认最多 5 项，仅含 `line`、`side`、`code`。
`NO_FINDINGS` 不等于完整汉化验收通过。检查成功发现编码错误也属于工具正常完成，由报告表达风险；读取失败才返回工具执行错误。

常见执行失败原因：`CHOOSE_ENCODING`、`INVALID_ENCODING`、`UNSUPPORTED_ENCODING`、`BOM_ENCODING_CONFLICT`、`NOT_A_FILE`、`NOT_TEXT`、`FILE_CHANGED`、`FILE_TOO_LARGE`、`TEXT_LIMIT`、`UNSUPPORTED_PROFILE`。
MCP 将输入失败返回为 `LOCALIZATION_<原因>`，同时提供诊断代码、阶段和 log ID。

## 验证

```sh
python3 scripts/localization_oracle.py --check-printf > /tmp/uu-localization-oracle.json
UU_LOCALIZATION_ORACLE=/tmp/uu-localization-oracle.json ./gradlew :app:testDebugUnitTest
```

`--check-printf` 需要 Clang，独立验证 16 组格式/参数类型（32 次编译）。不加该参数时只需 Python 标准库。
没有 Oracle 文件时只跳过独立 Python 对照测试，常规算法、只读、边界和沙箱测试继续执行。
真机验收仍应包含 Tab 切换、旋转、关闭检查、超长路径和小横屏配置页滚动。
