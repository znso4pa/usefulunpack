package com.usefulunpacker

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Locale
import java.util.TreeMap

internal const val MCP_PROTOCOL_VERSION = "2025-11-25"
internal val MCP_PROTOCOL_VERSIONS = setOf(MCP_PROTOCOL_VERSION, "2025-06-18", "2025-03-26")
internal const val MCP_REQUEST_LIMIT = 64 * 1024
internal const val MCP_TEXT_FILE_LIMIT = 2 * 1024 * 1024

internal data class McpResponse(val status: Int, val body: JSONObject? = null)

internal fun normalizeMcpEncoding(raw: String): String = when (raw.trim().uppercase(Locale.ROOT)) {
    "UTF-8", "UTF8" -> "UTF-8"
    "SHIFT-JIS", "SHIFT_JIS", "SHIFTJIS", "SJIS", "CP932", "MS932" -> "SHIFT-JIS"
    "GBK", "GB2312", "GB18030", "CP936" -> "GBK"
    "UTF-16", "UTF16", "UTF-16LE", "UTF16LE" -> "UTF-16"
    else -> throw McpInputException("Unsupported text encoding: $raw (supported: UTF-8, SHIFT-JIS, GBK, UTF-16)")
}

internal class McpProtocol(
    private val workspace: McpWorkspace,
    private val tasks: McpTasks,
    private val version: String,
    private val prefs: SharedPreferences? = null,
    private val archiveLister: ((format: String, file: File, password: String) -> Pair<String, String>)? = null,
) {
    private enum class Tool(val wireName: String, val detail: String, val key: String = "", val kind: String = "string") {
        HELP("command_help", "Show all 39 uu commands, full .uut script syntax, supported archive formats, XP3 schemes and text encodings."),
        ARCHIVE_LIST("archive_list", "List entries in an archive without extracting it. Supports optional password and after cursor.", "path"),
        RUN("run_uu", "Queue any uu command (all 39 commands plus uu run). Pass argv as separate strings, using workspace-relative paths.", "argv", "array"),
        VALIDATE("validate_uut", "Validate a .uut script (by workspace path or inline script text), supporting full UUT syntax (set, if/else, for, while, pipes, redirections and all uu commands).", "path"),
        SCRIPT("run_uut", "Queue and execute a .uut script (by workspace path or inline script text) with optional positional args (\$1..\$n). Returns a taskId.", "path"),
        STATUS("task_status", "Read the task state, chain ID, stdout, exitCode and published output paths.", "taskId"),
        CANCEL("task_cancel", "Request cancellation of a queued or running task.", "taskId"),
        LOG("task_log", "Read the bounded local diagnostic log for this task.", "taskId"),
        LIST("workspace_list", "List workspace files. Use . for the root and after to continue a truncated result.", "path"),
        READ("read_text", "Read a text file (up to 2 MiB on disk, 64 KiB chars per page). Auto-detects UTF-8, SHIFT-JIS, GBK and UTF-16, or accepts an explicit encoding, offset and limit.", "path"),
        WRITE("write_text", "Write a text file in UTF-8, SHIFT-JIS, GBK or UTF-16. Preserves existing encoding and BOM when overwrite is true unless overridden.", "path");

        fun schema(): JSONObject {
            val properties = JSONObject()
            val required = JSONArray()
            if (key.isNotEmpty()) {
                val parameter = JSONObject().put("type", kind)
                if (kind == "array") parameter.put("items", JSONObject().put("type", "string"))
                properties.put(key, parameter)
                if (this != VALIDATE && this != SCRIPT) required.put(key)
            }
            if (this == LIST) properties.put("after", JSONObject().put("type", "string"))
            if (this == ARCHIVE_LIST) {
                properties.put("password", JSONObject().put("type", "string"))
                properties.put("after", JSONObject().put("type", "string"))
            }
            if (this == VALIDATE || this == SCRIPT) {
                properties.put("script", JSONObject().put("type", "string"))
            }
            if (this == SCRIPT) {
                properties.put("args", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                properties.put("keepGoing", JSONObject().put("type", "boolean"))
            }
            if (this == READ) {
                properties.put("encoding", JSONObject().put("type", "string"))
                properties.put("offset", JSONObject().put("type", "integer"))
                properties.put("limit", JSONObject().put("type", "integer"))
            }
            if (this == WRITE) {
                properties.put("text", JSONObject().put("type", "string"))
                required.put("text")
                properties.put("encoding", JSONObject().put("type", "string"))
                properties.put("bom", JSONObject().put("type", "boolean"))
                properties.put("overwrite", JSONObject().put("type", "boolean"))
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
                .put("instructions", "Use workspace-relative paths. All 39 uu commands (plus uu run) are available via run_uu. Prefer writing and running .uut scripts via validate_uut and run_uut for multi-step workflows, variables, loops, conditionals, pipes and redirections. Call command_help for the full uu command table and .uut syntax. Poll task_status for queued work, stdout and outputs.")
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
                } catch (e: LinkageError) {
                    AppDiagnostics.begin("mcp_tool").fail(FailureCode.UNSUPPORTED, "Native archive library unavailable")
                    toolResult(JSONObject().put("error", "Native archive library unavailable."), true)
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
            Tool.ARCHIVE_LIST -> setOf("path", "password", "after")
            Tool.VALIDATE -> setOf("path", "script")
            Tool.SCRIPT -> setOf("path", "script", "args", "keepGoing")
            Tool.READ -> setOf("path", "encoding", "offset", "limit")
            Tool.WRITE -> setOf("path", "text", "encoding", "bom", "overwrite")
            Tool.HELP -> emptySet()
            else -> setOf(tool.key)
        }
        if (args.keys().asSequence().any { it !in allowed }) throw McpInputException("Unexpected tool arguments")
        fun argument(key: String = tool.key): String = args.opt(key) as? String
            ?: throw McpInputException("Expected string argument $key")
        return when (tool) {
            Tool.HELP -> {
                val allCommands = UuCommands.commandList().map { "${it.usage} — ${it.summary}" } +
                    "uu run [-k] <file.uut> [args...] — Run a UUT script inside the workspace"
                JSONObject()
                    .put("commands", JSONArray(allCommands))
                    .put("commandNames", JSONArray(UuCommands.commandList().map { it.name } + "run"))
                    .put("extractFormats", JSONArray(FORMAT_LABELS.keys.toList()))
                    .put("packFormats", JSONArray(COMPRESS_EXT.keys.toList()))
                    .put("xp3Schemes", JSONArray(listOf(
                        "cxdec", "HashCrypt", "FateCrypt", "AppliqueCrypt", "FlyingShineCrypt",
                        "AlteredPinkCrypt", "DameganeCrypt", "NatsupochiCrypt", "OkibaCrypt", "DieselmineCrypt",
                    )))
                    .put("textEncodings", JSONArray(TEXT_ENCODINGS.toList()))
                    .put("uutSyntax", JSONArray(listOf(
                        "set <var> = <value> | set <var> = $(uu ...) | set <n> = \$n + 1",
                        "if <var> =|!=|<|>|<=|>= <val> then <cmd> | if [not] exist <path> ... elif ... else ... end",
                        "for <var> in <glob... | 1..N | \$(cmd)> ... end",
                        "while <cond> ... end (with break, continue, return [code])",
                        "Positional args: \$1..\$n, \$argc, \$args; last exit code: \$? / \$errorlevel",
                        "Chaining: cmd1 && cmd2 | cmd1 || cmd2 | cmd1 ; cmd2",
                        "Pipes & redirection: cmd | grep/head/tail/wc/sort > file.txt (or >> file.txt)",
                        "Allowed script commands: uu <any of 39 subcommands | run>, ls, cd, pwd, help, echo",
                    )))
                    .put("scriptRules", "Use .uut scripts for multi-step archive/localization workflows. Pass a workspace .uut path or inline script text to validate_uut and run_uut.")
            }
            Tool.ARCHIVE_LIST -> listArchive(
                argument("path"),
                if (args.has("password")) argument("password") else "",
                if (args.has("after")) argument("after") else "",
            )
            Tool.RUN -> {
                val argv = args.optJSONArray("argv") ?: throw McpInputException("Expected argv array")
                if (argv.length() == 0) throw McpInputException("Expected non-empty argv array")
                if (argv.length() > 64) throw McpInputException("Too many command arguments")
                val command = McpCommand.parse(List(argv.length()) {
                    argv.opt(it) as? String ?: throw McpInputException("Expected string command arguments")
                })
                validatePaths(listOf(command))
                tasks.submit(listOf(command), script = false)
            }
            Tool.VALIDATE, Tool.SCRIPT -> {
                val hasPath = args.has("path")
                val hasScript = args.has("script")
                if (!hasPath && !hasScript) throw McpInputException("Expected string argument path")
                if (hasPath && hasScript) throw McpInputException("Provide either path or script, not both")
                val scriptText = if (hasPath) {
                    val scriptFile = workspace.resolve(argument("path"))
                    if (!scriptFile.isFile || !scriptFile.extension.equals("uut", ignoreCase = true)) {
                        throw McpInputException("Expected a workspace .uut file")
                    }
                    readUtf8Script(scriptFile)
                } else {
                    argument("script").also {
                        if (it.toByteArray(Charsets.UTF_8).size > MCP_REQUEST_LIMIT) {
                            throw McpInputException("Script exceeds 64 KiB")
                        }
                    }
                }
                val scriptArgs = if (tool == Tool.SCRIPT && args.has("args")) {
                    val arr = args.optJSONArray("args") ?: throw McpInputException("Expected args array")
                    if (arr.length() > 32) throw McpInputException("Too many script arguments")
                    List(arr.length()) {
                        arr.opt(it) as? String ?: throw McpInputException("Expected string script arguments")
                    }
                } else emptyList()
                val keepGoing = if (tool == Tool.SCRIPT && args.has("keepGoing")) {
                    boolArgument(args, "keepGoing")
                } else false
                val commands = McpCommand.script(scriptText, scriptArgs, keepGoing)
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
            Tool.READ -> readTextTool(
                argument("path"),
                if (args.has("encoding")) argument("encoding") else "auto",
                if (args.has("offset")) intArgument(args, "offset", 0, Int.MAX_VALUE) else 0,
                if (args.has("limit")) intArgument(args, "limit", 1, MCP_REQUEST_LIMIT) else MCP_REQUEST_LIMIT,
            )
            Tool.WRITE -> writeText(
                argument("path"),
                argument("text"),
                if (args.has("encoding")) argument("encoding") else "auto",
                if (args.has("bom")) boolArgument(args, "bom") else null,
                if (args.has("overwrite")) boolArgument(args, "overwrite") else false,
            )
        }
    }

    private fun intArgument(args: JSONObject, key: String, min: Int, max: Int): Int {
        val raw = args.opt(key)
        val value = when (raw) {
            is Int -> raw
            is Long -> raw.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            is Number -> raw.toInt().takeIf { it.toDouble() == raw.toDouble() }
            else -> null
        } ?: throw McpInputException("Expected integer argument $key")
        if (value !in min..max) throw McpInputException("Argument $key must be between $min and $max")
        return value
    }

    private fun boolArgument(args: JSONObject, key: String): Boolean =
        args.opt(key) as? Boolean ?: throw McpInputException("Expected boolean argument $key")

    private fun listArchive(path: String, password: String, after: String): JSONObject {
        if (password.length > 1024 || '\u0000' in password) throw McpInputException("Invalid password")
        val file = workspace.resolve(path)
        if (!file.isFile) throw McpInputException("Archive input does not exist")
        Files.newDirectoryStream(file.parentFile!!.toPath()).use { entries ->
            for (entry in entries) {
                if (Files.isSymbolicLink(entry)) throw McpInputException("Archive directory contains a symbolic link")
            }
        }
        val format = isVolumeFile(file) ?: detectFormat(file) ?: detectFormatByMagic(file)
            ?: throw McpInputException("Unsupported archive format")
        val scope = AppDiagnostics.enterIntent("mcp_archive_list", secrets = listOf(password), newChain = true)
        val (rawJson, xp3Enc) = try {
            val lister = archiveLister
            if (lister != null) {
                lister(format, file, password).also { scope.trace.success() }
            } else {
                val handle = tryStartOperation(format)
                try {
                    if (!handle.await()) throw McpInputException("Archive listing was cancelled")
                    var err = ""
                    val json = listEntriesJson(format, file, password, nestedOnly = false, prefs = prefs) { err = it }
                        ?: throw McpInputException(err.ifEmpty { "Could not list archive entries" })
                    val enc = if (format == "xp3") xp3SchemeToken(format, file) else ""
                    scope.trace.success()
                    json to enc
                } finally {
                    handle.release()
                }
            }
        } catch (e: McpInputException) {
            scope.trace.fail(FailureCode.INVALID_RESULT, e.message ?: "Archive listing failed")
            throw e
        } catch (e: LinkageError) {
            scope.trace.fail(FailureCode.UNSUPPORTED, "Native archive library unavailable")
            throw McpInputException("Native archive library unavailable.")
        } finally {
            AppDiagnostics.leaveIntent(scope)
        }
        val allEntries = parseEntries(rawJson)
        val totalBytes = allEntries.sumOf { if (it.isDirectory) 0L else it.size }
        val page = TreeMap<String, ArchiveEntry>()
        val limit = 128
        var next = false
        for (entry in allEntries) {
            if (entry.path <= after) continue
            page[entry.path] = entry
            if (page.size > limit) {
                page.pollLastEntry()
                next = true
            }
        }
        return JSONObject()
            .put("format", format)
            .put("totalEntries", allEntries.size)
            .put("totalBytes", totalBytes)
            .put("entries", JSONArray(page.values.map { entry ->
                JSONObject()
                    .put("name", entry.path)
                    .put("size", entry.size)
                    .put("directory", entry.isDirectory)
                    .put("encrypted", entry.isEncrypted)
            }))
            .apply {
                if (xp3Enc.isNotEmpty()) put("encryption", xp3Enc)
                if (next) put("nextAfter", page.lastKey())
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

    private fun readBoundedBytes(file: File, maxBytes: Int, limitLabel: String): ByteArray {
        if (!file.isFile) throw McpInputException("Text file does not exist")
        return file.inputStream().use { input ->
            val buffer = ByteArray(maxBytes + 1)
            var size = 0
            while (size < buffer.size) {
                val n = input.read(buffer, size, buffer.size - size)
                if (n < 0) break
                size += n
            }
            if (size > maxBytes) throw McpInputException("File exceeds $limitLabel")
            if (size == buffer.size) buffer else buffer.copyOf(size)
        }
    }

    private fun readUtf8Script(file: File): String {
        val bytes = readBoundedBytes(file, MCP_REQUEST_LIMIT, "64 KiB")
        return try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: java.nio.charset.CharacterCodingException) { throw McpInputException("File must be UTF-8") }
    }

    private fun readTextTool(path: String, encodingArg: String, offset: Int, limit: Int): JSONObject {
        val file = workspace.resolve(path)
        val bytes = readBoundedBytes(file, MCP_TEXT_FILE_LIMIT, "2 MiB")
        if (isTjsBytecode(bytes)) throw McpInputException("File is compiled TJS2 bytecode, not text")
        val resolvedEncoding = if (encodingArg.trim().equals("auto", ignoreCase = true) || encodingArg.isBlank()) {
            if (bytes.isEmpty()) "UTF-8"
            else detectBomEncoding(bytes) ?: textEncodingOf(bytes)
                ?: throw McpInputException("File does not appear to be text; specify encoding explicitly")
        } else {
            normalizeMcpEncoding(encodingArg)
        }
        val fullText = decodeTextStrict(bytes, resolvedEncoding)
        val start = offset.coerceAtMost(fullText.length)
        val end = (start + limit).coerceAtMost(fullText.length)
        val truncated = end < fullText.length
        return JSONObject()
            .put("text", fullText.substring(start, end))
            .put("encoding", resolvedEncoding)
            .put("bom", hasBom(bytes))
            .put("offset", start)
            .put("totalChars", fullText.length)
            .put("truncated", truncated)
            .apply { if (truncated) put("nextOffset", end) }
    }

    private fun writeText(
        path: String, text: String, encodingArg: String, bomArg: Boolean?, overwrite: Boolean,
    ): JSONObject {
        val output = workspace.resolve(path)
        val exists = output.exists()
        if (exists && (!overwrite || !output.isFile)) throw McpInputException("The output already exists")
        val parent = output.parentFile ?: throw McpInputException("Output has no parent directory")
        if (!parent.isDirectory) throw McpInputException("Create the output's parent directory first")
        val existingSample = if (exists) readPrefix(output, 64 * 1024L) else null
        val targetEncoding = if (encodingArg.trim().equals("auto", ignoreCase = true) || encodingArg.isBlank()) {
            if (existingSample != null && existingSample.isNotEmpty()) {
                detectBomEncoding(existingSample) ?: textEncodingOf(existingSample) ?: "UTF-8"
            } else "UTF-8"
        } else {
            normalizeMcpEncoding(encodingArg)
        }
        val targetBom = (bomArg ?: (existingSample != null && hasBom(existingSample))) &&
            targetEncoding in setOf("UTF-8", "UTF-16")
        val encodedBytes = encodeText(text, targetEncoding, targetBom)
        val scope = AppDiagnostics.enterIntent("mcp_write_text", newChain = true)
        try {
            val temp = Files.createTempFile(parent.toPath(), ".uu-mcp-", ".tmp").toFile()
            try {
                temp.writeBytes(encodedBytes)
                if (workspace.resolve(path) != output) throw McpInputException("Output path changed")
                if (overwrite) Files.move(temp.toPath(), output.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                else Files.move(temp.toPath(), output.toPath())
                scope.trace.success()
            } finally {
                // The temp name exists only if publication did not move it away.
                deleteOutputChecked(temp)
            }
            return JSONObject()
                .put("chainId", scope.trace.id)
                .put("output", path)
                .put("encoding", targetEncoding)
                .put("bom", targetBom)
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
            for (path in command.pathsToCheck) {
                workspace.resolveFrom(workspace.root, path)
            }
        }
    }

    private fun toolResult(data: JSONObject, failed: Boolean = false): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", data.toString())))
        .put("structuredContent", data).put("isError", failed)

    private fun envelope(id: Any) = JSONObject().put("jsonrpc", "2.0").put("id", id)
    private fun error(id: Any, code: Int, message: String, status: Int = 200) =
        McpResponse(status, envelope(id).put("error", JSONObject().put("code", code).put("message", message)))
}
