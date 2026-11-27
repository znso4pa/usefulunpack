package com.usefulunpacker

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal enum class WorkspaceTool(val wireName: String, val detail: String, val key: String = "", val kind: String = "string") {
    HELP("command_help", "Show all 39 uu commands, full .uut script syntax, supported archive formats, XP3 schemes and text encodings."),
    COMPRESS("COMPRESS", "Pack a file or directory into a supported archive format. Format may be inferred from the output extension.", "source"),
    EXTRACT("EXTRACT", "Extract an archive into a workspace directory, optionally selecting entries.", "archive"),
    SCAN("SCAN", "Scan a workspace file for embedded archive and media signatures, or carve a selected hit.", "path"),
    DETECT_GAME_ENGINE("DETECT_GAME_ENGINE", "Identify a game directory from known engine markers. Use . for the workspace root. Reads names and file types only.", "path"),
    VN_INSPECT("VN_INSPECT", "Inspect a visual novel folder for engine markers, possible text sources, compiled files and archive candidates. Returns a bounded report using file names, not contents.", "path"),
    LOCALIZATION_CHECK("LOCALIZATION_CHECK", "Read-only local text safety check. Default profile encoding checks strict decoding and representability in targetEncoding (default translation). Opt-in named (Python format dependencies) or printf (POSIX argument types) require confirmed physical-line correspondence. Unknown grammar or unequal line counts skip checks; includes bounded skip examples and coverage. No engine or translation-quality verdict. Paged metadata only. Limit 2 MiB/20000 lines per file.", "source"),
    REFERENCE_SEARCH("reference_search", "Search local guide titles when a user request or unresolved format, encoding or tool problem calls for a guide. Returns up to eight titles and IDs without loading any text.", "query"),
    REFERENCE_READ("reference_read", "Read one local guide by an exact document ID from reference_search. Give the specific unresolved question in reason. Skip this tool for routine tasks.", "documentId"),
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
    val argumentNames: Set<String> by lazy {
        inputSchema().getJSONObject("properties").keys().asSequence().toSet()
    }

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
        if (this == LOCALIZATION_CHECK) {
            properties.put("translation", JSONObject().put("type", "string"))
            required.put("translation")
            for (field in listOf("sourceEncoding", "translationEncoding", "targetEncoding", "profile", "after")) {
                properties.put(field, JSONObject().put("type", "string"))
            }
            for (field in listOf("sourceEncoding", "translationEncoding")) {
                properties.getJSONObject(field).put("enum", JSONArray(listOf("auto") + LOCALIZATION_ENCODINGS))
            }
            properties.getJSONObject("targetEncoding").put("enum", JSONArray(listOf("translation", "source") + LOCALIZATION_ENCODINGS))
            properties.getJSONObject("profile").put("enum", JSONArray(listOf("encoding", "named", "printf")))
            properties.put("limit", JSONObject().put("type", "integer"))
        }
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
        if (this == REFERENCE_READ) {
            properties.put("reason", JSONObject().put("type", "string"))
            required.put("reason")
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
