package com.usefulunpacker

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Locale
import java.util.TreeMap
import com.usefulunpacker.skills.AppSkillDefinition
import com.usefulunpacker.skills.AppSkillRuntime
import com.usefulunpacker.skills.AppSkillResult

internal const val MCP_TEXT_FILE_LIMIT = 2 * 1024 * 1024

internal fun normalizeMcpEncoding(raw: String): String = when (raw.trim().uppercase(Locale.ROOT)) {
    "UTF-8", "UTF8" -> "UTF-8"
    "SHIFT-JIS", "SHIFT_JIS", "SHIFTJIS", "SJIS", "CP932", "MS932" -> "SHIFT-JIS"
    "GBK", "GB2312", "GB18030", "CP936" -> "GBK"
    "UTF-16", "UTF16", "UTF-16LE", "UTF16LE" -> "UTF-16"
    else -> throw McpInputException("Unsupported text encoding: $raw (supported: UTF-8, SHIFT-JIS, GBK, UTF-16)")
}

internal class WorkspaceSkillRuntime(
    private val workspace: McpWorkspace,
    private val tasks: McpTasks,
    private val prefs: SharedPreferences? = null,
    private val archiveLister: ((format: String, file: File, password: String) -> Pair<String, String>)? = null,
    private val signatureScanner: ((file: File) -> List<ScanHit>)? = null,
) : AppSkillRuntime {
    private val requestLogIds = LinkedHashSet<String>()
    private val memorySettings = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private enum class Tool(val wireName: String, val detail: String, val key: String = "", val kind: String = "string") {
        HELP("command_help", "Show all 39 uu commands, full .uut script syntax, supported archive formats, XP3 schemes and text encodings."),
        COMPRESS("COMPRESS", "Pack a file or directory into a supported archive format. Format may be inferred from the output extension.", "source"),
        EXTRACT("EXTRACT", "Extract an archive into a workspace directory, optionally selecting entries.", "archive"),
        SCAN("SCAN", "Scan a workspace file for embedded archive and media signatures, or carve a selected hit.", "path"),
        ARCHIVE_LIST("archive_list", "List entries in an archive without extracting it. Supports optional password and after cursor.", "path"),
        INSPECT_FILE("inspect_file", "Deep GUI-level inspection of a workspace file or archive: detects format by extension and magic, XP3 encryption scheme, text encoding & BOM, TJS2 bytecode, image dimensions (PNG/JPEG/GIF/WebP/BMP), RPG Maker MV/MZ header, and hex/ASCII header preview.", "path"),
        APP_SETTINGS("app_settings", "Read or update UsefulUnpack archive and localization preferences (zip_encoding, text_encoding, zip_level, sz_level, generic_level, compress_split_size, parallel_threads, sort_mode)."),
        RUN("run_uu", "Queue any uu command (all 39 commands plus uu run). Pass argv as separate strings, using workspace-relative paths.", "argv", "array"),
        VALIDATE("validate_uut", "Validate a .uut script (by workspace path or inline script text), supporting full UUT syntax (set, if/else, for, while, pipes, redirections and all uu commands).", "path"),
        SCRIPT("run_uut", "Queue and execute a .uut script (by workspace path or inline script text) with optional positional args (\$1..\$n), idempotencyKey and waitMs. Automatically deduplicates identical in-flight scripts.", "path"),
        STATUS("task_status", "Read the task state, chain ID, stdout, exitCode, live progress and published output paths. Pass optional waitMs (0..15000) to long-poll.", "taskId"),
        TASK_LIST("task_list", "List active and recent tasks (newest first), optionally filtered by state. Use after a client timeout to recover the taskId without resubmitting."),
        CANCEL("task_cancel", "Request cancellation of a queued or running task.", "taskId"),
        LOG("task_log", "Read the diagnostic log for a task ID or a logId returned by the current listener.", "taskId"),
        LIST("workspace_list", "List workspace files. Use . for the root and after to continue a truncated result.", "path"),
        READ("read_text", "Read a text file (up to 2 MiB on disk, 64 KiB chars per page). Auto-detects UTF-8, SHIFT-JIS, GBK and UTF-16, or accepts an explicit encoding, offset and limit.", "path"),
        WRITE("write_text", "Write a text file in UTF-8, SHIFT-JIS, GBK or UTF-16. Preserves existing encoding and BOM when overwrite is true unless overridden.", "path");

        val skillId: String get() = wireName.uppercase(Locale.ROOT)

        fun inputSchema(): JSONObject {
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
            if (this == SCAN) {
                properties.put("carveHitIndex", JSONObject().put("type", "integer"))
                properties.put("output", JSONObject().put("type", "string"))
                properties.put("overwrite", JSONObject().put("type", "boolean"))
            }
            if (this == COMPRESS) {
                properties.put("output", JSONObject().put("type", "string"))
                properties.put("format", JSONObject().put("type", "string"))
                properties.put("password", JSONObject().put("type", "string"))
                properties.put("encryption", JSONObject().put("type", "string"))
                properties.put("idempotencyKey", JSONObject().put("type", "string"))
                properties.put("waitMs", JSONObject().put("type", "integer"))
                required.put("output")
            }
            if (this == EXTRACT) {
                properties.put("output", JSONObject().put("type", "string"))
                properties.put("entries", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                properties.put("password", JSONObject().put("type", "string"))
                properties.put("idempotencyKey", JSONObject().put("type", "string"))
                properties.put("waitMs", JSONObject().put("type", "integer"))
                required.put("output")
            }
            if (this == INSPECT_FILE) {
                properties.put("hexBytes", JSONObject().put("type", "integer"))
            }
            if (this == APP_SETTINGS) {
                properties.put("action", JSONObject().put("type", "string"))
                properties.put("key", JSONObject().put("type", "string"))
                properties.put("value", JSONObject().put("type", "string"))
            }
            if (this == RUN || this == SCRIPT) {
                properties.put("idempotencyKey", JSONObject().put("type", "string"))
                properties.put("waitMs", JSONObject().put("type", "integer"))
            }
            if (this == STATUS) {
                properties.put("waitMs", JSONObject().put("type", "integer"))
            }
            if (this == TASK_LIST) {
                properties.put("state", JSONObject().put("type", "string"))
                properties.put("limit", JSONObject().put("type", "integer"))
            }
            if (this == VALIDATE || this == SCRIPT) {
                properties.put("script", JSONObject().put("type", "string"))
            }
            if (this == SCRIPT) {
                properties.put("args", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                properties.put("keepGoing", JSONObject().put("type", "boolean"))
                properties.put("dryRun", JSONObject().put("type", "boolean"))
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
            return JSONObject().put("type", "object")
                .put("properties", properties).put("required", required).put("additionalProperties", false)
        }
    }

    private fun executeSkill(
        tool: Tool?,
        stage: String,
        args: JSONObject,
        usesTasks: Boolean,
        action: () -> JSONObject,
    ): AppSkillResult {
        val scope = if (tool in setOf(Tool.ARCHIVE_LIST, Tool.SCAN, Tool.INSPECT_FILE, Tool.WRITE)) {
            val password = args.opt("password") as? String
            AppDiagnostics.enterIntent("mcp_$stage", secrets = listOfNotNull(password), newChain = true)
        } else null
        try {
            val result = action()
            val toolFailed = scope?.trace?.chain?.failureStatus() == ChainStatus.FAILED
            scope?.trace?.chain?.firstFailure()?.let { (code, failedStage) ->
                result.withMcpFailure(AppFailure(code, failedStage, "", scope.trace.id)
                    .also(::rememberRequestLog))
            }
            if (toolFailed) result.put("error", "Tool operation failed. See local diagnostics.")
            if (usesTasks) {
                tasks.historyFailure()?.let {
                    rememberRequestLog(it)
                    result.put("historyError", JSONObject().put("error", it.detail).withMcpFailure(it))
                }
            }
            return AppSkillResult(result, toolFailed)
        } catch (e: McpInputException) {
            val message = e.message ?: "Invalid input"
            val data = buildInputErrorPayload(tool, AppDiagnostics.redact(message).take(2048))
            val scriptLine = data.optInt("line").takeIf { it > 0 }
            // The response can guide the caller with its own input. The log only records trusted metadata.
            val detail = e.diagnosticDetail ?: if (scriptLine == null) "Tool rejected the input or result"
                else "UUT validation failed at line $scriptLine"
            val failure = recordMcpFailure(stage, e.errorCode, detail)
            return AppSkillResult(data.withMcpFailure(failure.also(::rememberRequestLog)), true)
        } catch (e: LinkageError) {
            val failure = recordMcpFailure(stage, FailureCode.UNSUPPORTED, "Native archive library unavailable", e.javaClass.simpleName)
            return AppSkillResult(JSONObject().put("error", "Native archive library unavailable.")
                .withMcpFailure(failure.also(::rememberRequestLog)), true)
        } catch (e: java.util.concurrent.CancellationException) {
            scope?.trace?.let { trace ->
                trace.cancelled()
                rememberLogId(trace.id)
            }
            return AppSkillResult(JSONObject().put("error", "Tool cancelled").put("errorCode", "CANCELLED")
                .put("stage", stage).apply { scope?.let { put("logId", it.trace.id) } }, true)
        } catch (e: Exception) {
            val failure = recordMcpFailure(stage, FailureCode.IO, "Tool operation failed", e.javaClass.simpleName)
            return AppSkillResult(JSONObject().put("error", "Tool operation failed. See local diagnostics.")
                .withMcpFailure(failure.also(::rememberRequestLog)), true)
        } finally {
            scope?.let(AppDiagnostics::leaveIntent)
        }
    }

    internal fun rememberRequestLog(failure: AppFailure) = rememberLogId(failure.logId)

    private fun rememberLogId(id: String): Unit = synchronized(requestLogIds) {
        requestLogIds.add(id)
        if (requestLogIds.size > 40) requestLogIds.remove(requestLogIds.first())
    }

    private fun call(tool: Tool, args: JSONObject): JSONObject {
        val properties = tool.inputSchema().getJSONObject("properties")
        if (args.keys().asSequence().any { !properties.has(it) }) {
            throw mcpInputError("Unexpected tool arguments")
        }
        fun argument(key: String = tool.key): String = args.opt(key) as? String
            ?: throw McpInputException("Expected string argument $key")
        return when (tool) {
            Tool.COMPRESS -> submitArchive(
                McpCommand.Action.PACK, argument("source"), argument("output"), args,
            )
            Tool.EXTRACT -> {
                val entries = if (!args.has("entries")) null else args.optJSONArray("entries")
                    ?: throw mcpInputError("Archive entries must be an array")
                submitArchive(McpCommand.Action.EXTRACT, argument("archive"), argument("output"), args, entries)
            }
            Tool.SCAN -> scanSignaturesTool(
                argument("path"),
                if (args.has("carveHitIndex")) intArgument(args, "carveHitIndex", 0, 10_000) else null,
                if (args.has("output")) argument("output") else null,
                if (args.has("overwrite")) boolArgument(args, "overwrite") else false,
            )
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
                    .put("skillApi", "Discover named operations with skill_list and call them with skill_call. Archive operations use COMPRESS, EXTRACT and SCAN. Each uu command also has a UU_* skill.")
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
            Tool.INSPECT_FILE -> inspectFileTool(
                argument("path"),
                if (args.has("hexBytes")) intArgument(args, "hexBytes", 16, 512) else 64,
            )
            Tool.APP_SETTINGS -> appSettingsTool(
                if (args.has("action")) argument("action") else "get",
                if (args.has("key")) argument("key") else "",
                if (args.has("value")) args.opt("value")?.toString().orEmpty() else null,
            )
            Tool.RUN -> {
                val argv = args.optJSONArray("argv") ?: throw mcpInputError("Expected argv array")
                if (argv.length() == 0) throw mcpInputError("Expected non-empty command arguments")
                val command = List(argv.length()) {
                    argv.opt(it) as? String ?: throw mcpInputError("Command arguments must be strings")
                }
                submitUu(command, args)
            }
            Tool.VALIDATE, Tool.SCRIPT -> {
                val hasPath = args.has("path")
                val hasScript = args.has("script")
                if (!hasPath && !hasScript) throw mcpInputError("Expected string argument path")
                if (hasPath && hasScript) throw mcpInputError("Provide either path or script, not both")
                val scriptText = if (hasPath) {
                    val scriptFile = workspace.resolve(argument("path"))
                    if (!scriptFile.isFile || !scriptFile.extension.equals("uut", ignoreCase = true)) {
                        throw mcpInputError("Expected a workspace .uut file")
                    }
                    readUtf8Script(scriptFile)
                } else {
                    argument("script").also {
                        if (it.toByteArray(Charsets.UTF_8).size > MCP_REQUEST_LIMIT) {
                            throw mcpInputError("Script exceeds 64 KiB")
                        }
                    }
                }
                val scriptArgs = if (tool == Tool.SCRIPT && args.has("args")) {
                    val arr = args.optJSONArray("args") ?: throw mcpInputError("Expected args array")
                    if (arr.length() > 32) throw mcpInputError("Too many script arguments")
                    List(arr.length()) {
                        arr.opt(it) as? String ?: throw mcpInputError("Expected string script arguments")
                    }
                } else emptyList()
                val keepGoing = if (tool == Tool.SCRIPT && args.has("keepGoing")) {
                    boolArgument(args, "keepGoing")
                } else false
                val dryRun = if (tool == Tool.SCRIPT && args.has("dryRun")) {
                    boolArgument(args, "dryRun")
                } else false
                val commands = McpCommand.script(scriptText, scriptArgs, keepGoing)
                validatePaths(commands)
                if (tool == Tool.SCRIPT && !dryRun) {
                    val key = if (args.has("idempotencyKey")) argument("idempotencyKey") else ""
                    val waitMs = if (args.has("waitMs")) intArgument(args, "waitMs", 0, MCP_MAX_WAIT_MS) else 0
                    tasks.submit(commands, script = true, idempotencyKey = key, waitMs = waitMs)
                } else JSONObject().put("valid", true).put("steps", commands.size).apply {
                    if (dryRun) put("dryRun", true)
                }
            }
            Tool.STATUS -> {
                val task = tasks.get(argument())
                val waitMs = if (args.has("waitMs")) intArgument(args, "waitMs", 0, MCP_MAX_WAIT_MS) else 0
                if (waitMs > 0) task.awaitDone(waitMs)
                task.snapshot()
            }
            Tool.TASK_LIST -> tasks.list(
                if (args.has("state")) argument("state") else "",
                if (args.has("limit")) intArgument(args, "limit", 1, 32) else 32,
            )
            Tool.CANCEL -> tasks.cancel(argument())
            Tool.LOG -> {
                val id = argument()
                if (!synchronized(requestLogIds) { id in requestLogIds } && !tasks.canReadLog(id)) {
                    throw mcpInputError("Unknown or expired log ID")
                }
                val entry = AppDiagnostics.store?.readEntry(id)?.takeIf { it.feature.startsWith("mcp_") }
                    ?: throw mcpInputError("The task log is unavailable or has rotated")
                JSONObject().put("chainId", id).put("log", entry.text.takeLast(8192))
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

    override fun skills(): List<AppSkillDefinition> {
        val toolSkills = Tool.entries.map { tool ->
            AppSkillDefinition(tool.skillId, tool.detail, tool.inputSchema())
        }
        val uuSkills = (UuCommands.commandList().map { it.name to "${it.usage} — ${it.summary}" } +
            ("run" to "uu run [-k] <file.uut> [args...] — Execute a UUT file from this workspace"))
            .map { (command, description) ->
                val properties = JSONObject()
                    .put("args", JSONObject().put("type", "array")
                        .put("items", JSONObject().put("type", "string")))
                    .put("idempotencyKey", JSONObject().put("type", "string"))
                    .put("waitMs", JSONObject().put("type", "integer"))
                val schema = JSONObject().put("type", "object").put("properties", properties)
                    .put("required", JSONArray().put("args")).put("additionalProperties", false)
                AppSkillDefinition("UU_${command.uppercase(Locale.ROOT)}", description, schema)
            }
        return toolSkills + uuSkills
    }

    internal fun protocolTools(): List<AppSkillDefinition> =
        Tool.entries.map { AppSkillDefinition(it.wireName, it.detail, it.inputSchema()) }

    override fun invokeSkill(name: String, input: JSONObject): AppSkillResult {
        val normalized = name.trim().uppercase(Locale.ROOT)
        val tool = Tool.entries.firstOrNull { it.skillId == normalized }
        if (tool != null) {
            val usesTasks = tool in setOf(Tool.COMPRESS, Tool.EXTRACT, Tool.RUN, Tool.SCRIPT,
                Tool.STATUS, Tool.TASK_LIST, Tool.CANCEL)
            return executeSkill(tool, tool.wireName, input, usesTasks) { call(tool, input) }
        }

        val command = normalized.removePrefix("UU_").lowercase(Locale.ROOT)
        if (!normalized.startsWith("UU_") || !UuCommands.isKnownCommand(command)) {
            return invalidSkillCall("Unknown skill name")
        }
        return executeSkill(null, normalized.lowercase(Locale.ROOT), input, usesTasks = true) {
            if (input.keys().asSequence().any { it !in setOf("args", "idempotencyKey", "waitMs") }) {
                throw mcpInputError("Unexpected skill arguments")
            }
            val args = input.optJSONArray("args") ?: throw mcpInputError("Expected skill args array")
            if (args.length() > 63) throw mcpInputError("Too many command arguments")
            val argv = listOf(command) + List(args.length()) { index ->
                args.opt(index) as? String ?: throw mcpInputError("Command arguments must be strings")
            }
            submitUu(argv, input)
        }
    }

    internal fun invalidSkillCall(detail: String, stage: String = "skill_call"): AppSkillResult =
        executeSkill(null, stage, JSONObject(), usesTasks = false) { throw mcpInputError(detail) }

    private fun submitUu(argv: List<String>, options: JSONObject): JSONObject {
        if (argv.isEmpty()) throw mcpInputError("Expected a uu command")
        if (argv.size > 64) throw mcpInputError("Too many command arguments")
        val command = McpCommand.parse(argv)
        validatePaths(listOf(command))
        return submitCommand(command, options)
    }

    private fun submitArchive(
        action: McpCommand.Action,
        source: String,
        output: String,
        options: JSONObject,
        entries: JSONArray? = null,
    ): JSONObject {
        workspace.resolve(source)
        val destination = workspace.resolve(output)
        fun optionalString(key: String): String = if (!options.has(key)) "" else options.opt(key) as? String
            ?: throw McpInputException("Expected string argument $key")
        val password = optionalString("password")
        if (password.length > 1024 || '\u0000' in password) throw mcpInputError("Invalid password")
        val format = if (action == McpCommand.Action.PACK) resolveMcpPackFormat(destination.name, optionalString("format")) else ""
        val encryption = normalizeXp3PackEnc(optionalString("encryption"))
            ?: throw mcpInputError("Unsupported XP3 encryption scheme")
        if (encryption.isNotEmpty() && format != "xp3") {
            throw mcpInputError("Encryption scheme is only supported for XP3")
        }
        val entryCount = entries?.length() ?: 0
        if (entryCount > 16) throw mcpInputError("At most 16 archive entries may be selected")
        val selection = List(entryCount) { index ->
            val entry = entries?.opt(index) as? String ?: throw mcpInputError("Archive entries must be strings")
            if (entry.any { it.isISOControl() }) throw mcpInputError("Invalid archive entry path")
            sanitizeEntryPath(entry) ?: throw mcpInputError("Invalid archive entry path")
        }
        return submitCommand(McpCommand(action, source, output, password, format = format, enc = encryption,
            entries = selection), options)
    }

    private fun submitCommand(command: McpCommand, options: JSONObject): JSONObject {
        val idempotencyKey = if (options.has("idempotencyKey")) {
            options.opt("idempotencyKey") as? String ?: throw mcpInputError("idempotencyKey must be a string")
        } else ""
        val waitMs = if (options.has("waitMs")) intArgument(options, "waitMs", 0, MCP_MAX_WAIT_MS) else 0
        return tasks.submit(listOf(command), script = false, idempotencyKey = idempotencyKey, waitMs = waitMs)
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
        if (password.length > 1024 || '\u0000' in password) throw mcpInputError("Invalid password")
        val file = workspace.resolve(path)
        if (!file.isFile) throw mcpInputError("Archive input does not exist")
        Files.newDirectoryStream(file.parentFile!!.toPath()).use { entries ->
            for (entry in entries) {
                if (Files.isSymbolicLink(entry)) throw mcpInputError("Archive directory contains a symbolic link")
            }
        }
        val format = isVolumeFile(file) ?: detectFormat(file) ?: detectFormatByMagic(file)
            ?: throw mcpInputError("Unsupported archive format")
        val lister = archiveLister
        val (rawJson, xp3Enc) = if (lister != null) lister(format, file, password) else {
            val handle = tryStartOperation(format)
            try {
                if (!handle.await()) throw java.util.concurrent.CancellationException()
                var err = ""
                val json = listEntriesJson(format, file, password, nestedOnly = false, prefs = prefs) { err = it }
                    ?: throw McpInputException(err.ifEmpty { "Could not list archive entries" }, FailureCode.IO)
                json to if (format == "xp3") xp3SchemeToken(format, file) else ""
            } finally {
                handle.release()
            }
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
        if (!folder.isDirectory) throw mcpInputError("Directory does not exist")
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
        if (!file.isFile) throw mcpInputError("Text file does not exist")
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
        catch (_: java.nio.charset.CharacterCodingException) { throw mcpInputError("File must be UTF-8") }
    }

    private fun readTextTool(path: String, encodingArg: String, offset: Int, limit: Int): JSONObject {
        val file = workspace.resolve(path)
        val bytes = readBoundedBytes(file, MCP_TEXT_FILE_LIMIT, "2 MiB")
        if (isTjsBytecode(bytes)) throw mcpInputError("File is compiled TJS2 bytecode, not text")
        val resolvedEncoding = if (encodingArg.trim().equals("auto", ignoreCase = true) || encodingArg.isBlank()) {
            if (bytes.isEmpty()) "UTF-8"
            else detectBomEncoding(bytes) ?: textEncodingOf(bytes)
                ?: throw mcpInputError("File does not appear to be text; specify encoding explicitly")
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
        if (exists && (!overwrite || !output.isFile)) throw mcpInputError("The output already exists")
        val parent = output.parentFile ?: throw mcpInputError("Output has no parent directory")
        if (!parent.isDirectory) throw mcpInputError("Create the output's parent directory first")
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
        val trace = checkNotNull(AppDiagnostics.currentTrace())
        val temp = Files.createTempFile(parent.toPath(), ".uu-mcp-", ".tmp").toFile()
        try {
            temp.writeBytes(encodedBytes)
            if (workspace.resolve(path) != output) throw mcpInputError("Output path changed")
            if (overwrite) Files.move(temp.toPath(), output.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            else Files.move(temp.toPath(), output.toPath())
            trace.success()
        } finally {
            deleteOutputChecked(temp)
        }
        return JSONObject()
            .put("chainId", trace.id)
            .put("output", path)
            .put("encoding", targetEncoding)
            .put("bom", targetBom)
            .put("cleanupFailed", trace.chain?.onlyCleanupFailed() == true)
    }

    private fun validatePaths(commands: List<McpCommand>) {
        for (command in commands) {
            for (path in command.pathsToCheck) {
                workspace.resolveFrom(workspace.root, path)
            }
        }
    }

    private fun scanSignaturesTool(
        path: String,
        carveHitIndex: Int?,
        outputRel: String?,
        overwrite: Boolean,
    ): JSONObject {
        val file = workspace.resolve(path)
        if (!file.isFile) throw mcpInputError("Scan target does not exist")
        val hits = signatureScanner?.invoke(file) ?: run {
            val raw = ScanCore.scanFile(file.absolutePath)
                ?: throw mcpInputError("Signature scan did not return a result", FailureCode.IO)
            enrichScanHits(file, parseScanHits(raw))
        }
        val hitsJson = JSONArray()
        for ((idx, hit) in hits.withIndex()) {
            val fmt = ARCHIVE_LABELS[hit.label]
            val ext = ARCHIVE_EXT_FOR_LABEL[hit.label] ?: EXT_FOR_LABEL[hit.label] ?: "bin"
            val needsCarve = when {
                fmt == null -> true
                fmt in NEEDS_CARVE && hit.offset > 0L -> true
                fmt == "zip" && hit.offset > 0L -> true
                fmt == "rar" && hit.offset > RAR_SCAN_LIMIT -> true
                fmt == "iso" && hit.offset > 32768L -> true
                else -> false
            }
            hitsJson.put(
                JSONObject()
                    .put("index", idx)
                    .put("fd", "f$idx")
                    .put("offset", hit.offset)
                    .put("hexOffset", "0x%x".format(hit.offset))
                    .put("label", hit.label)
                    .put("isArchive", fmt != null)
                    .put("format", fmt.orEmpty())
                    .put("extension", ext)
                    .put("needsCarve", needsCarve)
                    .put("canExtract", fmt != null)
                    .apply {
                        if (hit.size != null) put("size", hit.size)
                        if (hit.fileCount != null) put("fileCount", hit.fileCount)
                        if (hit.note.isNotEmpty()) put("note", hit.note)
                    }
            )
        }
        val out = JSONObject()
            .put("path", path)
            .put("fileSize", file.length())
            .put("totalHits", hits.size)
            .put("hits", hitsJson)
        if (carveHitIndex != null) {
            if (carveHitIndex !in hits.indices) {
                throw McpInputException("carveHitIndex $carveHitIndex is out of range (0..${hits.size - 1})")
            }
            val hit = hits[carveHitIndex]
            val ext = ARCHIVE_EXT_FOR_LABEL[hit.label] ?: EXT_FOR_LABEL[hit.label] ?: "bin"
            val targetRel = outputRel?.takeIf { it.isNotBlank() }
                ?: "${path}_0x${"%x".format(hit.offset)}.$ext"
            val dest = workspace.resolve(targetRel)
            if (dest == file) throw mcpInputError("Carve output must be different from the source file")
            if (dest.exists() && (!overwrite || !dest.isFile)) throw mcpInputError("The output already exists")
            val parent = dest.parentFile ?: throw mcpInputError("Output has no parent directory")
            if (!parent.isDirectory) throw mcpInputError("Create the output's parent directory first")
            val temp = Files.createTempFile(parent.toPath(), ".uu-mcp-carve-", ".tmp").toFile()
            var carvedBytes = 0L
            try {
                carveToFile(file, hit.offset, hit.size, temp) { n -> carvedBytes += n }
                if (overwrite) Files.move(temp.toPath(), dest.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                else Files.move(temp.toPath(), dest.toPath())
            } finally {
                deleteOutputChecked(temp)
            }
            out.put(
                "carved",
                JSONObject()
                    .put("hitIndex", carveHitIndex)
                    .put("output", workspace.relativePath(dest))
                    .put("offset", hit.offset)
                    .put("bytes", carvedBytes)
                    .put("label", hit.label),
            )
        }
        return out
    }

    private fun inspectFileTool(path: String, hexBytes: Int): JSONObject {
        val file = workspace.resolve(path)
        if (!file.exists()) throw mcpInputError("File does not exist")
        if (file.isDirectory) {
            val childCount = file.list()?.count { !it.startsWith(".uu-mcp-") } ?: 0
            return JSONObject()
                .put("path", path)
                .put("name", file.name)
                .put("kind", "directory")
                .put("childCount", childCount)
                .put("lastModifiedMs", file.lastModified())
        }
        val sample = readPrefix(file, 64 * 1024L)
        val archiveFmt = isVolumeFile(file) ?: detectFormat(file) ?: detectFormatByMagic(file)
        val imageInfo = parseImageHeaderInfo(sample)
        val isRpgmv = sample.size >= 16 &&
            sample[0] == 0x52.toByte() && sample[1] == 0x50.toByte() &&
            sample[2] == 0x47.toByte() && sample[3] == 0x4D.toByte() &&
            sample[4] == 0x56.toByte() && sample[9] == 0x03.toByte() && sample[10] == 0x01.toByte()
        val tjs = isTjsBytecode(sample)
        val textEnc = if (!tjs && imageInfo == null && archiveFmt == null && sample.isNotEmpty()) {
            detectBomEncoding(sample) ?: textEncodingOf(sample)
        } else null
        val extLower = file.extension.lowercase(Locale.ROOT)
        val kind = when {
            isRpgmv || archiveFmt != null -> "archive"
            imageInfo != null -> "image"
            tjs -> "tjs2_bytecode"
            extLower in setOf("mp3", "ogg", "wav", "aac", "flac", "aif", "aiff", "m4a", "au") -> "audio"
            extLower in setOf("mp4", "mkv", "avi", "mov", "webm", "wmv", "asf", "flv", "mpg", "mpeg", "ts") -> "video"
            textEnc != null || (sample.isEmpty() && extLower in TEXT_SEARCH_EXTS) -> "text"
            else -> "binary"
        }
        val hexSlice = sample.copyOf(sample.size.coerceAtMost(hexBytes))
        val hexDump = buildString {
            var off = 0
            while (off < hexSlice.size) {
                val end = (off + 16).coerceAtMost(hexSlice.size)
                val hexPart = (off until end).joinToString(" ") { "%02X".format(hexSlice[it].toInt() and 0xFF) }
                val asciiPart = (off until end).map {
                    val b = hexSlice[it].toInt() and 0xFF
                    if (b in 32..126) b.toChar() else '.'
                }.joinToString("")
                if (isNotEmpty()) append('\n')
                append("%04X: %-47s  %s".format(off, hexPart, asciiPart))
                off += 16
            }
        }
        return JSONObject()
            .put("path", path)
            .put("name", file.name)
            .put("size", file.length())
            .put("lastModifiedMs", file.lastModified())
            .put("kind", kind)
            .put("hexPreview", hexDump)
            .apply {
                if (archiveFmt != null) {
                    put("archiveFormat", archiveFmt)
                    put("archiveLabel", FORMAT_LABELS[archiveFmt] ?: archiveFmt)
                    if (archiveFmt == "xp3") {
                        val enc = try { xp3SchemeToken("xp3", file) } catch (_: LinkageError) { "" }
                        if (enc.isNotEmpty()) put("xp3Encryption", enc)
                    }
                }
                if (isRpgmv) {
                    val targetExt = when (extLower) {
                        "rpgmvp", "png_" -> "png"
                        "rpgmvo", "ogg_" -> "ogg"
                        "rpgmvm", "m4a_" -> "m4a"
                        else -> "bin"
                    }
                    put("rpgmvInfo", JSONObject().put("obfuscated", true).put("targetExtension", targetExt))
                }
                if (imageInfo != null) put("imageInfo", imageInfo)
                if (textEnc != null) {
                    val previewText = try {
                        decodeTextStrict(sample, textEnc).lineSequence().take(5).joinToString("\n").take(400)
                    } catch (_: Exception) {
                        ""
                    }
                    put(
                        "textInfo",
                        JSONObject()
                            .put("encoding", textEnc)
                            .put("bom", hasBom(sample))
                            .put("editableScript", extLower in EDIT_SCRIPT_EXTS)
                            .put("preview", previewText),
                    )
                }
            }
    }

    private fun parseImageHeaderInfo(b: ByteArray): JSONObject? {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun u16be(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun u16le(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun u32be(i: Int) = (u16be(i) shl 16) or u16be(i + 2)
        fun u32le(i: Int) = u16le(i) or (u16le(i + 2) shl 16)
        // PNG: 89 50 4E 47 0D 0A 1A 0A + IHDR at 16..24
        if (b.size >= 24 && u8(0) == 0x89 && u8(1) == 0x50 && u8(2) == 0x4E && u8(3) == 0x47) {
            return JSONObject().put("format", "png").put("width", u32be(16)).put("height", u32be(20))
        }
        // GIF87a / GIF89a
        if (b.size >= 10 && u8(0) == 0x47 && u8(1) == 0x49 && u8(2) == 0x46 && u8(3) == 0x38) {
            return JSONObject().put("format", "gif").put("width", u16le(6)).put("height", u16le(8))
        }
        // BMP: 'BM' + width/height at 18..26
        if (b.size >= 26 && u8(0) == 0x42 && u8(1) == 0x4D) {
            return JSONObject().put("format", "bmp").put("width", u32le(18)).put("height", kotlin.math.abs(u32le(22)))
        }
        // WebP: RIFF....WEBP
        if (b.size >= 30 && u8(0) == 0x52 && u8(1) == 0x49 && u8(2) == 0x46 && u8(3) == 0x46 &&
            u8(8) == 0x57 && u8(9) == 0x45 && u8(10) == 0x42 && u8(11) == 0x50
        ) {
            return JSONObject().put("format", "webp")
        }
        // JPEG: FF D8 FF
        if (b.size >= 4 && u8(0) == 0xFF && u8(1) == 0xD8 && u8(2) == 0xFF) {
            var i = 2
            while (i + 8 < b.size && u8(i) == 0xFF) {
                val marker = u8(i + 1)
                if (marker == 0xFF) { i++; continue }
                if (marker in listOf(0xC0, 0xC1, 0xC2, 0xC3)) {
                    return JSONObject().put("format", "jpeg").put("height", u16be(i + 5)).put("width", u16be(i + 7))
                }
                val segLen = u16be(i + 2)
                if (segLen < 2) break
                i += 2 + segLen
            }
            return JSONObject().put("format", "jpeg")
        }
        return null
    }

    private fun appSettingsTool(actionRaw: String, keyRaw: String, valueRaw: String?): JSONObject {
        val action = actionRaw.trim().lowercase(Locale.ROOT).ifEmpty { "get" }
        if (action !in setOf("get", "set")) throw mcpInputError("action must be 'get' or 'set'")
        val validKeys = setOf(
            "zip_encoding", "text_encoding", "zip_level", "sz_level", "generic_level",
            "compress_split_size", "parallel_threads", "sort_mode",
        )
        val key = keyRaw.trim()
        if (key.isNotEmpty() && key !in validKeys) {
            throw McpInputException("Unsupported setting key '$key' (allowed: ${validKeys.joinToString(", ")})")
        }
        fun getStr(k: String, def: String): String =
            prefs?.getString(k, def) ?: (memorySettings[k] as? String) ?: def
        fun getInt(k: String, def: Int): Int =
            prefs?.getInt(k, def) ?: (memorySettings[k] as? Int) ?: def
        fun getLong(k: String, def: Long): Long =
            prefs?.getLong(k, def) ?: (memorySettings[k] as? Long) ?: def
        fun putStr(k: String, v: String) {
            memorySettings[k] = v
            prefs?.edit()?.putString(k, v)?.apply()
        }
        fun putInt(k: String, v: Int) {
            memorySettings[k] = v
            prefs?.edit()?.putInt(k, v)?.apply()
        }
        fun putLong(k: String, v: Long) {
            memorySettings[k] = v
            prefs?.edit()?.putLong(k, v)?.apply()
        }

        if (action == "set") {
            if (key.isEmpty()) throw mcpInputError("key is required when action is 'set'")
            val rawVal = valueRaw ?: throw mcpInputError("value is required when action is 'set'")
            when (key) {
                "zip_encoding" -> {
                    val norm = normalizeMcpEncoding(rawVal)
                    if (norm !in setOf("UTF-8", "SHIFT-JIS", "GBK")) {
                        throw mcpInputError("zip_encoding supports UTF-8, SHIFT-JIS or GBK")
                    }
                    putStr("zip_encoding", norm)
                }
                "text_encoding" -> putStr("text_encoding", normalizeMcpEncoding(rawVal))
                "zip_level", "generic_level" -> {
                    val lv = rawVal.toIntOrNull()
                    if (lv == null || lv !in setOf(0, 3, 5, 7, 9)) {
                        throw McpInputException("$key must be one of 0, 3, 5, 7, 9")
                    }
                    putInt(key, lv)
                }
                "sz_level" -> {
                    val lv = rawVal.toIntOrNull()
                    if (lv == null || lv !in setOf(0, 3, 6, 9, 12)) {
                        throw mcpInputError("sz_level must be one of 0, 3, 6, 9, 12")
                    }
                    putInt(key, lv)
                }
                "compress_split_size" -> {
                    val bytes = rawVal.toLongOrNull()
                    if (bytes == null || bytes < 0L) throw mcpInputError("compress_split_size must be >= 0 bytes")
                    putLong(key, bytes)
                }
                "parallel_threads" -> {
                    val th = rawVal.toIntOrNull()
                    if (th == null || th !in setOf(0, 1, 2, 4, 8)) {
                        throw mcpInputError("parallel_threads must be one of 0 (auto), 1, 2, 4, 8")
                    }
                    putInt(key, th)
                    try {
                        RarCore.setParallelThreads(th)
                        ZipCore.setParallelThreads(th)
                    } catch (_: LinkageError) { /* Pure JVM test environment. */ }
                }
                "sort_mode" -> {
                    val allowedSorts = setOf("name_asc", "name_desc", "size_asc", "size_desc", "date_asc", "date_desc")
                    if (rawVal !in allowedSorts) {
                        throw McpInputException("sort_mode must be one of ${allowedSorts.joinToString(", ")}")
                    }
                    putStr(key, rawVal)
                }
            }
        }

        val all = JSONObject()
            .put("zip_encoding", getStr("zip_encoding", "UTF-8"))
            .put("text_encoding", getStr("text_encoding", "UTF-8"))
            .put("zip_level", getInt("zip_level", 5))
            .put("sz_level", getInt("sz_level", 6))
            .put("generic_level", getInt("generic_level", 6))
            .put("compress_split_size", getLong("compress_split_size", 0L))
            .put("parallel_threads", getInt("parallel_threads", 0))
            .put("sort_mode", getStr("sort_mode", "name_asc"))
        return if (key.isEmpty()) {
            JSONObject().put("settings", all)
        } else {
            JSONObject().put("key", key).put("value", all.get(key)).put("settings", all)
        }
    }

    private fun buildInputErrorPayload(tool: Tool?, msg: String): JSONObject {
        val out = JSONObject().put("error", msg)
        if (tool == Tool.VALIDATE || tool == Tool.SCRIPT) {
            out.put("valid", false)
        }
        Regex("^Line (\\d+):\\s*(.*)$").find(msg)?.let { m ->
            m.groupValues[1].toIntOrNull()?.let { out.put("line", it) }
            out.put("reason", m.groupValues[2])
        }
        Regex("usage:\\s*([^)]+)").find(msg)?.let { m ->
            out.put("expectedUsage", m.groupValues[1].trim())
        }
        when {
            "must be a uu subcommand" in msg || "is a uu subcommand" in msg ->
                out.put("fixHint", "Prefix UsefulUnpack commands with 'uu ' (e.g. 'uu grep', 'uu sed', 'uu cat').")
            "Use 'end' instead" in msg ->
                out.put("fixHint", "Close all UUT if, for, and while blocks with 'end'.")
            "not supported in UUT; use 'set" in msg ->
                out.put("fixHint", "Assign variables in UUT using 'set <name> = <value>'.")
            "Unknown flag" in msg || "Too few arguments" in msg ->
                out.put("fixHint", "Check expectedUsage or call command_help for valid flags and positional arguments.")
        }
        return out
    }

}
