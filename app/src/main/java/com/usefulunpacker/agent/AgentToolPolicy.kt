package com.usefulunpacker

import com.usefulunpacker.skills.AppSkillDefinition
import com.usefulunpacker.skills.AppSkillResult
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

internal enum class AgentPermission {
    READ_ONLY, ARCHIVES, EDIT_FILES, FULL_LOCAL;

    fun tools(skills: List<AppSkillDefinition>): Map<String, AgentTool> {
        val registered = skills.associateBy { it.name }
        val tools = AgentReadTool.entries.filter { it.name in registered }.map { read ->
            AgentTool(read.name, read.schema(), read = read)
        }.toMutableList()
        if (this >= ARCHIVES) {
            add(tools, registered, "COMPRESS", setOf("source", "output", "format", "encryption"))
            add(tools, registered, "EXTRACT", setOf("archive", "output", "entries"))
        }
        if (this >= EDIT_FILES) {
            add(tools, registered, "WRITE_TEXT", setOf("path", "text", "encoding", "bom", "overwrite"))
            tools.add(AgentTool("DELETE_FILE", functionSchema("DELETE_FILE",
                "Permanently delete one regular file inside the workspace after confirmation.",
                JSONObject().put("path", JSONObject().put("type", "string")), setOf("path"))))
        }
        if (this == FULL_LOCAL) {
            add(tools, registered, "RUN_UU", setOf("argv"))
            add(tools, registered, "RUN_UUT", setOf("script", "args"), required = setOf("script"))
        }
        return tools.associateBy { it.name }
    }

    private fun add(
        tools: MutableList<AgentTool>,
        registered: Map<String, AppSkillDefinition>,
        name: String,
        fields: Set<String>,
        required: Set<String>? = null,
    ) {
        val skill = registered[name] ?: return
        val source = skill.inputSchema.getJSONObject("properties")
        val properties = JSONObject()
        fields.forEach { key -> properties.put(key, source.getJSONObject(key)) }
        val original = skill.inputSchema.getJSONArray("required")
        val mandatory = required ?: (0 until original.length()).map { original.getString(it) }.filter { it in fields }.toSet()
        tools.add(AgentTool(name, functionSchema(name, skill.description, properties, mandatory)))
    }
}

internal data class AgentApproval(val name: String, val input: JSONObject)

internal data class AgentTool(val name: String, val schema: JSONObject, val read: AgentReadTool? = null) {
    fun accepts(input: JSONObject): Boolean {
        val parameters = schema.getJSONObject("function").getJSONObject("parameters")
        val properties = parameters.getJSONObject("properties")
        val required = parameters.getJSONArray("required")
        return (0 until required.length()).all { input.has(required.getString(it)) } &&
            input.keys().asSequence().all { key ->
                val value = input.opt(key)
                when (properties.optJSONObject(key)?.optString("type")) {
                    "string" -> value is String
                    "boolean" -> value is Boolean
                    "array" -> value is JSONArray && (0 until value.length()).all { value.opt(it) is String }
                    else -> false
                }
            } && (name != "RUN_UU" || !callsScriptFile(input.getJSONArray("argv")))
    }

    private fun callsScriptFile(argv: JSONArray): Boolean {
        val start = if (argv.opt(0) == "uu") 1 else 0
        return argv.opt(start) == "run"
    }
}

private fun functionSchema(name: String, description: String, properties: JSONObject, required: Set<String>) =
    JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", name).put("description", description)
        .put("parameters", JSONObject().put("type", "object").put("properties", properties)
            .put("required", JSONArray(required.toList())).put("additionalProperties", false)))

internal fun deleteAgentFile(workspace: McpWorkspace, input: JSONObject): AppSkillResult {
    val path = input.getString("path")
    return try {
        val file = workspace.resolve(path)
        if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) {
            throw mcpInputError("Choose an existing regular file to delete")
        }
        if (!deleteOutputChecked(file, cleanup = false)) throw mcpInputError("File deletion failed", FailureCode.IO)
        AppSkillResult(JSONObject().put("state", "SUCCESS"))
    } catch (e: McpInputException) {
        val failure = AppDiagnostics.begin("delete_file").fail(e.errorCode, e.diagnosticDetail ?: "Invalid file path", notifyUser = false)
        AppSkillResult(JSONObject().withMcpFailure(failure), true)
    }
}
