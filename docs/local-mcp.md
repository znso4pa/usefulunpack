# 本地 MCP

在 UsefulUnpack 打开 **设置 → 📡 本地 MCP**，点「用默认目录启动」或选择一个可读写的项目目录。使用默认目录或选中共享存储根目录时，服务会使用其中的 `UsefulUnpack-MCP` 子目录。启动后复制连接配置。配置包含本次会话的 URL 和 Bearer 令牌。将它作为一项 **Streamable HTTP** 连接导入同一台 Android 设备上的 Agent（不同客户端的外层配置字段可能不同）。

```json
{
  "url": "http://127.0.0.1:PORT/mcp",
  "headers": {
    "Authorization": "Bearer TOKEN"
  }
}
```

端口仅监听设备回环地址，默认关闭。每次启动会分配新端口和令牌。关闭设置弹窗后服务继续运行，直到点击「停止」、Activity 销毁或系统结束进程。客户端退出或 HTTP 断连不取消已接收的任务。停止服务会取消仍在队列中或运行中的任务，已发布的文件保留。若客户端在另一台设备或独立容器中，它的 `127.0.0.1` 不指向手机。如果通过 USB 在电脑上调试，可用 `adb forward tcp:PORT tcp:PORT`，两处都填平板显示的端口，然后使用原连接配置。调试结束后，用 `adb forward --remove tcp:PORT` 移除转发。

| 工具 | 参数 | 用法 |
|---|---|---|
| `skill_list` / `skill_call` | `skill`、可选 `input` | 列出共享技能目录并按技能名调用。后续应用内 Agent 可直接调用同一运行时 |
| `reference_search` | `query` | 按具体问题查本地指南，最多返回八个标题和文档 ID |
| `reference_read` | `documentId`、`reason` | 按需读取一篇相关指南，适用于用户指定的参考或遇到的具体问题 |
| `COMPRESS` | `source`、`output`，可选 `format`、`password`、`encryption`、`waitMs`、`idempotencyKey` | 把文件或目录打成归档；省略 `format` 时从输出扩展名推断 |
| `EXTRACT` | `archive`、`output`，可选 `entries`、`password`、`waitMs`、`idempotencyKey` | 解包归档，可按条目选择性解包 |
| `SCAN` | `path`，可选 `carveHitIndex`、`output`、`overwrite` | 扫描文件中的归档/媒体签名，或将指定命中切出为文件 |
| `command_help` | 无 | 查看全部 39 条 `uu` 命令（及 `uu run`）、完整 `.uut` 脚本语法、全部解包/封包格式、XP3 加密方案与文本编码 |
| `archive_list` | `path`，可选 `password`、`after` | 不解包直接列出归档条目、总大小、格式与 XP3 加密状态；超 128 项时用 `nextAfter` 翻页 |
| `workspace_list` | `path`，可选 `after` | 列出相对目录。根目录用 `.`，下一页使用前一页的 `nextAfter` |
| `read_text` | `path`，可选 `encoding`、`offset`、`limit` | 读取最多 2 MiB 的文本文件（单页最多 64 KiB 字符）。默认 `auto` 自动识别 `UTF-8`、`SHIFT-JIS`、`GBK`、`UTF-16` 与 BOM |
| `write_text` | `path`、`text`，可选 `encoding`、`bom`、`overwrite` | 写入或覆写文本文件。`overwrite=true` 且 `encoding=auto` 时自动保持原文件的编码与 BOM |
| `run_uu` | `argv` | 执行任意 `uu` 命令（全部 39 条子命令 + `uu run`），如 `["x","game.xp3","extracted","scenario/first.ks"]` 或 `["sed","旧","新","extracted/a.ks","-i"]` |
| `validate_uut` | `path` 或 `script` | 校验工作区 `.uut` 脚本文件或内联 `.uut` 脚本语法与路径边界 |
| `run_uut` | `path` 或 `script`，可选 `args`、`keepGoing` | 提交并执行完整 `.uut` 脚本，支持传入位置参数 `$1..$n` 与 `-k`（`keepGoing`） |
| `task_status` / `task_cancel` / `task_log` | `taskId` | 查询状态（含 `stdout`、`exitCode`、`outputs`）、取消任务或取得本地诊断日志 |

`skill_list` 返回规范技能名、说明和输入 Schema。现有 MCP 工具各有对应技能名；全部 `uu` 子命令另以 `UU_<命令名>` 暴露，例如 `UU_HASH`、`UU_SCAN`、`UU_C`。新客户端可用 `skill_call` 统一调用，旧 MCP 工具名与 `run_uu` 保留兼容。

文档随应用打包。初始化响应只说明用法，查找时返回文档 ID 和标题。遇到格式、偏移、异或或编码问题时，先查关键词，再读取最相关的一篇。VN 总览也可通过 `usefulunpack://guides/vn-v1` 或 `vn_guide` 按需读取。

`COMPRESS` 和 `EXTRACT` 使用工作区相对路径。文件名和条目名按原值处理。任务提交后可以用返回的 `taskId` 查询进度和产物。

统一调用示例：

```json
{
  "name": "skill_call",
  "arguments": {
    "skill": "COMPRESS",
    "input": {
      "source": "translated",
      "output": "translated.zip",
      "format": "zip"
    }
  }
}
```

`run_uu` 和 `run_uut` 返回同一个任务 ID 和诊断链 ID。状态为 `QUEUED`、`RUNNING`、`CANCELLING` 或终态 `SUCCESS`、`FAILED`、`CANCELLED`、`PARTIAL_FAILURE`、`INTERRUPTED`。用 `outputs` 查看已发布的产物、用 `stdout` 查看命令或脚本的标准输出。客户端在请求超时后应先确认已有任务，重复提交会产生新任务。

通过 `task_status` 可以读取当前服务中仍保留的标准输出。`task_list` 和应用私有目录中的持久回执只保留任务元数据。启动服务时，旧共享工作区回执会移入应用私有归档，并和新任务记录隔离。无人值守的 UUT 通过已配对的 MCP 提交。

应用内的任务中心使用同一个任务 ID，可查看排队、运行、取消和终态。新发布的产物可从回执定位到所在目录，回执和诊断保留在设备本地。任务中心通过原任务的取消入口处理取消。

Agent 的错误结果会带 `errorCode`、`stage` 和 `logId`。任务错误也可以通过 `task_status` 查询。用 `task_log` 的 `taskId` 参数传入任务 ID，或本次开放期间返回的 `logId`，即可查看保留的诊断。任务恢复后也可读取仍在磁盘上的日志。若任务账本不可用，任务接口另带 `historyError`，避免把正在运行和无法持久保存混为一谈。

应用把 MCP 失败写入本地诊断。下次打开「本地 MCP」时，会提示「上次开放 MCP 有报错，请查看日志」，同一条记录只提醒一次。可以从该页面点「查看日志」。正常取消和客户端请求可选 SSE 得到的 405 不会触发错误提醒。

推荐将多步解包、探查、转码、替换与回包流程写成 **`.uut` 脚本** 提交（`validate_uut` → `run_uut`）。示例 `translate.uut`：

```text
set fmt = $(uu info $1)
if $fmt = xp3
  uu x $1 extracted
  for f in extracted/*.ks
    uu sed "Hello" "你好" $f -i
  end
  uu c -e cxdec extracted translated.xp3
else
  uu x $1 extracted
  uu cp -f translation.txt extracted/dialogue.txt
  uu c extracted translated.zip
end
```

MCP 端完整支持 **全部 39 条 `uu` 命令**（`fmt / help / docs / info / l / cat / hash / grep / cp / mv / rn / rm / mkdir / tree / du / stat / x / c / set / scan / cso / audio / video / enc / mvdec / rmd / add / find / diff / hex / dd / cmp / strings / sed / tr / guess / img / fd / b64`）与 **`uu run`**，以及完整的 **UUT 脚本语言**（`set` 变量、`$(...)` 命令捕获、整数算术、`if / elif / else / end`、`if [not] exist`、`for ... in <通配 | 1..N | $(...)> ... end`、`while ... end`、`break / continue / return`、`$1..$n / $argc / $args / $?`、`&& / || / ;` 串联、`| grep/head/tail/wc/sort` 管道、`> / >>` 重定向，以及 `uu / ls / cd / pwd / help / echo` 内建命令）。所有路径均在工作区沙箱内解析，禁止符号链接越界和任意系统 shell 命令。
