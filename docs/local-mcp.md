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
| `command_help` | 无 | 查看全部 39 条 `uu` 命令（及 `uu run`）、完整 `.uut` 脚本语法、全部解包/封包格式、XP3 加密方案与文本编码 |
| `archive_list` | `path`，可选 `password`、`after` | 不解包直接列出归档条目、总大小、格式与 XP3 加密状态；超 128 项时用 `nextAfter` 翻页 |
| `workspace_list` | `path`，可选 `after` | 列出相对目录。根目录用 `.`，下一页使用前一页的 `nextAfter` |
| `read_text` | `path`，可选 `encoding`、`offset`、`limit` | 读取最多 2 MiB 的文本文件（单页最多 64 KiB 字符）。默认 `auto` 自动识别 `UTF-8`、`SHIFT-JIS`、`GBK`、`UTF-16` 与 BOM |
| `write_text` | `path`、`text`，可选 `encoding`、`bom`、`overwrite` | 写入或覆写文本文件。`overwrite=true` 且 `encoding=auto` 时自动保持原文件的编码与 BOM |
| `run_uu` | `argv` | 执行任意 `uu` 命令（全部 39 条子命令 + `uu run`），如 `["x","game.xp3","extracted","scenario/first.ks"]` 或 `["sed","旧","新","extracted/a.ks","-i"]` |
| `validate_uut` | `path` 或 `script` | 校验工作区 `.uut` 脚本文件或内联 `.uut` 脚本语法与路径边界 |
| `run_uut` | `path` 或 `script`，可选 `args`、`keepGoing` | 提交并执行完整 `.uut` 脚本，支持传入位置参数 `$1..$n` 与 `-k`（`keepGoing`） |
| `task_status` / `task_cancel` / `task_log` | `taskId` | 查询状态（含 `stdout`、`exitCode`、`outputs`）、取消任务或取得本地诊断日志 |

`run_uu` 和 `run_uut` 返回同一个任务 ID 和诊断链 ID。状态为 `QUEUED`、`RUNNING`、`CANCELLING` 或终态 `SUCCESS`、`FAILED`、`CANCELLED`、`PARTIAL_FAILURE`。用 `outputs` 查看已发布的产物、用 `stdout` 查看命令或脚本的标准输出。客户端在请求超时后应先确认已有任务，重复提交会产生新任务。

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
