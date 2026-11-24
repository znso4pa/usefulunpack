package com.usefulunpacker.skills

import org.json.JSONObject

/** Stable, app-level operation names shared by MCP and the future in-app Agent. */
internal data class AppSkillDefinition(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
)

/** Implementations own authorization, workspace bounds, scheduling, cancellation and diagnostics. */
internal interface AppSkillRuntime {
    fun skills(): List<AppSkillDefinition>
    fun invokeSkill(name: String, input: JSONObject): JSONObject
}
