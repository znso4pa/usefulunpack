package com.usefulunpacker.skills

import org.json.JSONObject

/** Stable, app-level operation names shared by MCP and the future in-app Agent. */
internal data class AppSkillDefinition(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
)

internal data class AppSkillResult(val data: JSONObject, val isError: Boolean = false)

/** Executes app skills within the selected workspace. Call from a worker thread. */
internal interface AppSkillRuntime {
    fun skills(): List<AppSkillDefinition>
    fun invokeSkill(name: String, input: JSONObject): AppSkillResult
}
