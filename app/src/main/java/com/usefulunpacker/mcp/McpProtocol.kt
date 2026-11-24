package com.usefulunpacker

import android.content.SharedPreferences
import com.usefulunpacker.skills.AppSkillDefinition
import com.usefulunpacker.skills.AppSkillResult
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.nio.ByteBuffer

internal const val MCP_PROTOCOL_VERSION = "2025-11-25"
internal val MCP_PROTOCOL_VERSIONS = setOf(MCP_PROTOCOL_VERSION, "2025-06-18", "2025-03-26")
internal const val MCP_REQUEST_LIMIT = 64 * 1024

internal data class McpResponse(val status: Int, val body: JSONObject? = null)

internal class McpProtocol(
    private val runtime: WorkspaceSkillRuntime,
    private val version: String,
) {
    constructor(
        workspace: McpWorkspace,
        tasks: McpTasks,
        version: String,
        prefs: SharedPreferences? = null,
        archiveLister: ((format: String, file: File, password: String) -> Pair<String, String>)? = null,
        signatureScanner: ((file: File) -> List<ScanHit>)? = null,
    ) : this(WorkspaceSkillRuntime(workspace, tasks, prefs, archiveLister, signatureScanner), version)

    private val tools = buildList {
        addAll(runtime.protocolTools())
        val scan = first { it.name == "SCAN" }
        add(scan.copy(name = "scan_signatures"))
        add(AppSkillDefinition("skill_list", "List the shared app skills and their input schemas.",
            objectSchema(JSONObject())))
        add(AppSkillDefinition("skill_call", "Invoke a skill by its name from skill_list.",
            objectSchema(JSONObject()
                .put("skill", JSONObject().put("type", "string"))
                .put("input", JSONObject().put("type", "object")), JSONArray().put("skill"))))
    }.associateBy { it.name }

    private fun objectSchema(properties: JSONObject, required: JSONArray = JSONArray()) =
        JSONObject().put("type", "object").put("properties", properties)
            .put("required", required).put("additionalProperties", false)

    private fun AppSkillDefinition.toJson() = JSONObject().put("name", name)
        .put("description", description).put("inputSchema", inputSchema)

    private fun callTool(name: String, args: JSONObject): AppSkillResult {
        return when (name) {
            "skill_list" -> {
                if (args.length() != 0) return runtime.invalidSkillCall("Unexpected tool arguments", name)
                val definitions = runtime.skills()
                AppSkillResult(JSONObject().put("skills", JSONArray(definitions.map { it.toJson() }))
                    .put("count", definitions.size))
            }
            "skill_call" -> {
                if (args.keys().asSequence().any { it != "skill" && it != "input" }) {
                    return runtime.invalidSkillCall("Unexpected tool arguments")
                }
                val skill = args.opt("skill") as? String
                    ?: return runtime.invalidSkillCall("Expected string argument skill")
                val input = if (!args.has("input")) JSONObject() else args.optJSONObject("input")
                    ?: return runtime.invalidSkillCall("Skill input must be an object")
                runtime.invokeSkill(skill, input)
            }
            "scan_signatures" -> runtime.invokeSkill("SCAN", args)
            else -> runtime.invokeSkill(name, args)
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
            else error(JSONObject.NULL, -32600, "Unsupported notification", 400)
        }
        val params = message.optJSONObject("params") ?: JSONObject()
        val result = when (method) {
            "initialize" -> JSONObject().put("protocolVersion",
                params.optString("protocolVersion").takeIf { it in MCP_PROTOCOL_VERSIONS } ?: MCP_PROTOCOL_VERSION)
                .put("serverInfo", JSONObject().put("name", "UsefulUnpack").put("version", version))
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("instructions", "Use workspace-relative paths. Call skill_list to discover the shared app skills and skill_call to invoke one. Prefer COMPRESS, EXTRACT and SCAN for archive operations. All 39 uu commands are also individually listed as UU_* skills and remain available through run_uu. Use validate_uut and run_uut for multi-step .uut scripts. Poll task_status for progress, stdout and outputs.")
            "ping" -> JSONObject()
            "tools/list" -> JSONObject().put("tools", JSONArray(tools.values.map { it.toJson() }))
            "tools/call" -> {
                val tool = tools[params.opt("name") as? String]
                    ?: return error(id, -32602, "Unknown tool")
                val args = params.optJSONObject("arguments")
                if (params.has("arguments") && args == null) return error(id, -32602, "Expected an arguments object")
                toolResult(callTool(tool.name, args ?: JSONObject()))
            }
            else -> return error(id, -32601, "Method not found")
        }
        return McpResponse(200, envelope(id).put("result", result))
    }

    fun transportError(status: Int, message: String, code: FailureCode = FailureCode.INVALID_RESULT): McpResponse {
        val failure = recordMcpFailure("transport", code, "HTTP $status: $message").also(runtime::rememberRequestLog)
        return McpResponse(status, JSONObject().put("error", message).withMcpFailure(failure))
    }

    private fun toolResult(result: AppSkillResult): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", result.data.toString())))
        .put("structuredContent", result.data).put("isError", result.isError)

    private fun envelope(id: Any) = JSONObject().put("jsonrpc", "2.0").put("id", id)
    private fun error(id: Any, code: Int, message: String, status: Int = 200): McpResponse {
        val failure = recordMcpFailure("protocol", FailureCode.INVALID_RESULT, "JSON-RPC $code: $message")
            .also(runtime::rememberRequestLog)
        return McpResponse(status, envelope(id).put("error", JSONObject()
            .put("code", code).put("message", message)
            .put("data", JSONObject().withMcpFailure(failure))))
    }
}
