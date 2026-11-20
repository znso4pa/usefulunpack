package com.usefulunpacker

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.TreeMap

internal const val MCP_PROTOCOL_VERSION = "2025-11-25"
internal val MCP_PROTOCOL_VERSIONS = setOf(MCP_PROTOCOL_VERSION, "2025-06-18", "2025-03-26")
internal const val MCP_REQUEST_LIMIT = 64 * 1024

internal data class McpResponse(val status: Int, val body: JSONObject? = null)

internal class McpProtocol(
    private val workspace: McpWorkspace,
    private val tasks: McpTasks,
    private val version: String,
) {
    private enum class Tool(val wireName: String, val detail: String, val key: String = "", val kind: String = "string") {
        HELP("command_help", "Show the uu commands and formats available in this workspace."),
        RUN("run_uu", "Queue an archive or file command. Pass argv as separate strings, using workspace-relative paths.", "argv", "array"),
        VALIDATE("validate_uut", "Validate literal uu commands in a UTF-8 .uut file inside the workspace.", "path"),
        SCRIPT("run_uut", "Queue a validated .uut file and stop at the first failed or cancelled step. Returns a taskId.", "path"),
        STATUS("task_status", "Read the task state, chain ID and published output paths.", "taskId"),
        CANCEL("task_cancel", "Request cancellation of a queued or running task.", "taskId"),
        LOG("task_log", "Read the bounded local diagnostic log for this task.", "taskId"),
        LIST("workspace_list", "List workspace files. Use . for the root and after to continue a truncated result.", "path"),
        READ("read_text", "Read a UTF-8 text file up to 64 KiB.", "path"),
        WRITE("write_text", "Create a new UTF-8 file. Use uu cp -f to replace an existing file with a staged translation.", "path");

        fun schema(): JSONObject {
            val properties = JSONObject()
            val required = JSONArray()
            if (key.isNotEmpty()) {
                val parameter = JSONObject().put("type", kind)
                if (kind == "array") parameter.put("items", JSONObject().put("type", "string"))
                properties.put(key, parameter)
                required.put(key)
            }
            if (this == LIST) properties.put("after", JSONObject().put("type", "string"))
            if (this == WRITE) {
                properties.put("text", JSONObject().put("type", "string"))
                required.put("text")
            }
            return JSONObject().put("name", wireName).put("description", detail)
                .put("inputSchema", JSONObject().put("type", "object")
                    .put("properties", properties).put("required", required).put("additionalProperties", false))
        }
    }

    fun handle(body: ByteArray): McpResponse {
        val message = try {
            val tokener = JSONTokener(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString())
            val value = tokener.nextValue()
            if (tokener.nextClean() != '\u0000') throw JSONException("Trailing input")
            value
        } catch (_: JSONException) {
            return error(JSONObject.NULL, -32700, "Invalid JSON", 400)
        } catch (_: java.nio.charset.CharacterCodingException) {
            return error(JSONObject.NULL, -32700, "Expected UTF-8", 400)
        } catch (_: StackOverflowError) {
            return error(JSONObject.NULL, -32700, "JSON nesting is too deep", 400)
        }
        if (message !is JSONObject) return error(JSONObject.NULL, -32600, "Expected a JSON-RPC message", 400)
        val id = message.opt("id")
        if (message.opt("jsonrpc") != "2.0" ||
            (id != null && id !is String && id !is Number) || message.opt("method") !is String) {
            return error(JSONObject.NULL, -32600, "Invalid JSON-RPC request", 400)
        }
        val method = message.getString("method")
        if (id == null) {
            return if (method == "notifications/initialized" || method == "notifications/cancelled") McpResponse(202)
            else McpResponse(400)
        }
        val params = message.optJSONObject("params") ?: JSONObject()
        val result = when (method) {
            "initialize" -> JSONObject().put("protocolVersion",
                params.optString("protocolVersion").takeIf { it in MCP_PROTOCOL_VERSIONS } ?: MCP_PROTOCOL_VERSION)
                .put("serverInfo", JSONObject().put("name", "UsefulUnpack").put("version", version))
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("instructions", "Use workspace-relative paths. Call command_help for syntax. Poll task_status for queued work.")
            "ping" -> JSONObject()
            "tools/list" -> JSONObject().put("tools", JSONArray(Tool.entries.map { it.schema() }))
            "tools/call" -> {
                val tool = Tool.entries.find { it.wireName == params.opt("name") }
                    ?: return error(id, -32602, "Unknown tool")
                val args = params.optJSONObject("arguments")
                if (params.has("arguments") && args == null) return error(id, -32602, "Expected an arguments object")
                try {
                    toolResult(call(tool, args ?: JSONObject()))
                } catch (e: McpInputException) {
                    toolResult(JSONObject().put("error", e.message), true)
                } catch (e: Exception) {
                    AppDiagnostics.begin("mcp_tool").fail(FailureCode.IO, "Could not complete ${tool.wireName}", e)
                    toolResult(JSONObject().put("error", "Tool operation failed. See local diagnostics."), true)
                }
            }
            else -> return error(id, -32601, "Method not found")
        }
        return McpResponse(200, envelope(id).put("result", result))
    }

    private fun call(tool: Tool, args: JSONObject): JSONObject {
        val allowed = when (tool) {
            Tool.LIST -> setOf("path", "after")
            Tool.WRITE -> setOf("path", "text")
            Tool.HELP -> emptySet()
            else -> setOf(tool.key)
        }
        if (args.keys().asSequence().any { it !in allowed }) throw McpInputException("Unexpected tool arguments")
        fun argument(key: String = tool.key): String = args.opt(key) as? String
            ?: throw McpInputException("Expected string argument $key")
        return when (tool) {
            Tool.HELP -> JSONObject()
                .put("commands", JSONArray(McpCommand.Action.entries.map { it.usage }))
                .put("extractFormats", JSONArray(listOf("zip", "bgi", "lpk")))
                .put("packFormats", JSONArray(listOf("zip", "lpk")))
                .put("scriptRules", "UTF-8 .uut file, at most 32 literal uu commands, one per line. Commands stop on the first failure.")
            Tool.RUN -> {
                val argv = args.optJSONArray("argv") ?: throw McpInputException("Expected argv array")
                if (argv.length() > 6) throw McpInputException("Too many command arguments")
                val command = McpCommand.parse(List(argv.length()) {
                    argv.opt(it) as? String ?: throw McpInputException("Expected string command arguments")
                })
                validatePaths(listOf(command))
                tasks.submit(listOf(command), script = false)
            }
            Tool.VALIDATE, Tool.SCRIPT -> {
                val script = workspace.resolve(argument())
                if (!script.isFile || !script.extension.equals("uut", ignoreCase = true)) {
                    throw McpInputException("Expected a workspace .uut file")
                }
                val commands = McpCommand.script(readText(script))
                validatePaths(commands)
                if (tool == Tool.SCRIPT) tasks.submit(commands, script = true)
                else JSONObject().put("valid", true).put("steps", commands.size)
            }
            Tool.STATUS -> tasks.get(argument()).snapshot()
            Tool.CANCEL -> tasks.cancel(argument())
            Tool.LOG -> {
                val task = tasks.get(argument())
                val entry = AppDiagnostics.store?.current(task.trace.id)
                    ?: throw McpInputException("The task log is unavailable or has rotated")
                JSONObject().put("chainId", task.trace.id).put("log", entry.text.takeLast(8192))
                    .put("truncated", entry.text.length > 8192)
            }
            Tool.LIST -> listDirectory(argument(), if (args.has("after")) argument("after") else "")
            Tool.READ -> JSONObject().put("text", readText(workspace.resolve(argument())))
            Tool.WRITE -> writeText(argument(), argument("text"))
        }
    }

    private fun listDirectory(path: String, after: String): JSONObject {
        val folder = workspace.resolve(path, allowRoot = true)
        if (!folder.isDirectory) throw McpInputException("Directory does not exist")
        val page = TreeMap<String, Boolean>()
        val limit = 128
        var next = false
        Files.newDirectoryStream(folder.toPath()).use { entries ->
            for (entry in entries) {
                val name = entry.fileName.toString()
                if (name <= after || name.startsWith(".uu-mcp-")) continue
                val directory = Files.isDirectory(entry, NOFOLLOW_LINKS)
                if (!directory && !Files.isRegularFile(entry, NOFOLLOW_LINKS)) continue
                page[name] = directory
                if (page.size > limit) {
                    page.pollLastEntry()
                    next = true
                }
            }
        }
        return JSONObject().put("entries", JSONArray(page.map { (name, directory) ->
            JSONObject().put("name", name).put("directory", directory)
        })).apply { if (next) put("nextAfter", page.lastKey()) }
    }

    private fun readText(file: java.io.File): String {
        if (!file.isFile) throw McpInputException("Text file does not exist")
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(MCP_REQUEST_LIMIT + 1)
            var size = 0
            while (size < buffer.size) {
                val n = input.read(buffer, size, buffer.size - size)
                if (n < 0) break
                size += n
            }
            if (size > MCP_REQUEST_LIMIT) throw McpInputException("File exceeds 64 KiB")
            ByteBuffer.wrap(buffer, 0, size)
        }
        return try { Charsets.UTF_8.newDecoder().decode(bytes).toString() }
        catch (_: java.nio.charset.CharacterCodingException) { throw McpInputException("File must be UTF-8") }
    }

    private fun writeText(path: String, text: String): JSONObject {
        val output = workspace.resolve(path)
        if (output.exists()) throw McpInputException("The output already exists")
        val parent = output.parentFile ?: throw McpInputException("Output has no parent directory")
        if (!parent.isDirectory) throw McpInputException("Create the output's parent directory first")
        val scope = AppDiagnostics.enterIntent("mcp_write_text", newChain = true)
        try {
            val temp = Files.createTempFile(parent.toPath(), ".uu-mcp-", ".tmp").toFile()
            try {
                temp.writeText(text, Charsets.UTF_8)
                workspace.resolve(path)
                Files.move(temp.toPath(), output.toPath())
                scope.trace.success()
            } finally {
                // The temp name exists only if publication did not move it away.
                deleteOutputChecked(temp)
            }
            return JSONObject().put("chainId", scope.trace.id).put("output", path)
                .put("cleanupFailed", scope.trace.chain?.onlyCleanupFailed() == true)
        } catch (e: Exception) {
            scope.trace.fail(FailureCode.IO, "Could not publish workspace text")
            throw McpInputException("Could not publish text. See chain ${scope.trace.id}.")
        } finally {
            AppDiagnostics.leaveIntent(scope)
        }
    }

    private fun validatePaths(commands: List<McpCommand>) {
        for (command in commands) {
            workspace.resolve(command.source)
            workspace.resolve(command.output)
        }
    }

    private fun toolResult(data: JSONObject, failed: Boolean = false): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", data.toString())))
        .put("structuredContent", data).put("isError", failed)

    private fun envelope(id: Any) = JSONObject().put("jsonrpc", "2.0").put("id", id)
    private fun error(id: Any, code: Int, message: String, status: Int = 200) =
        McpResponse(status, envelope(id).put("error", JSONObject().put("code", code).put("message", message)))
}
