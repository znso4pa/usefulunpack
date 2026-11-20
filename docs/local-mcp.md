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

端口仅监听设备回环地址，默认关闭。每次启动会分配新端口和令牌。关闭设置弹窗后服务继续运行，直到点击「停止」、Activity 销毁或系统结束进程。客户端退出或 HTTP 断连不取消已接收的任务。停止服务会取消仍在队列中或运行中的任务，已发布的文件保留。若客户端在另一台设备或独立容器中，它的 `127.0.0.1` 不指向手机。 如果通过 USB 在电脑上调试，可用 `adb forward tcp:PORT tcp:PORT`，两处都填平板显示的端口，然后使用原连接配置。调试结束后，用 `adb forward --remove tcp:PORT` 移除转发。

| 工具 | 参数 | 用法 |
|---|---|---|
| `command_help` | 无 | 查看这次连接支持的命令和格式 |
| `workspace_list` | `path`，可选 `after` | 列出相对目录。根目录用 `.`，下一页使用前一页的 `nextAfter` |
| `read_text` / `write_text` | `path`，写入时另加 `text` | 读取或新建小型 UTF-8 文件。写入不覆盖已有文件 |
| `run_uu` | `argv` | 传参数数组，如 `["x","source.zip","extracted"]` |
| `validate_uut` / `run_uut` | `path` | 验证或提交工作目录下的 UTF-8 脚本文件 |
| `task_status` / `task_cancel` / `task_log` | `taskId` | 查询状态、取消或取得有限长度的诊断预览 |

`run_uu` 和 `run_uut` 返回同一个任务 ID 和诊断链 ID。状态为 `QUEUED`、`RUNNING`、`CANCELLING` 或终态 `SUCCESS`、`FAILED`、`CANCELLED`、`PARTIAL_FAILURE`。用 `outputs` 查看已经发布的产物。客户端在请求超时后应先确认已有任务，重复提交会产生新任务。

示例 `translate.uut`，假定 `translation.txt` 是已准备好的本地译文。

```text
uu x source.zip extracted
uu cp -f translation.txt extracted/dialogue.txt
uu c extracted translated.zip
```

脚本每行一条字面量命令，支持引号、注释和最多 32 条命令，任一步失败即停。首版解包支持 ZIP、BGI ARC 和 LPK，封包支持 ZIP 和 LPK。原生操作沿用应用的格式队列。封包要求显式提供最终文件名，LPK 应使用引擎期望的名称；用不同的输出目录避免和原包同路径。保留原文件的文本编码与 BOM，非 UTF-8 译文可以作为文件放入工作目录后使用 `uu cp -f` 应用。

MCP 端的脚本只暴露上述命令子集。交互终端仍支持完整 UUT 语法。协议支持 JSON 响应以及 2025-03-26、2025-06-18 和 2025-11-25 版本协商，客户端须能通过 HTTP 发送 Authorization 请求头。没有 SSE 连接的客户端可用 `task_status` 轮询。日志保存在本机，可在设置的「日志与诊断」中打开对应链。
