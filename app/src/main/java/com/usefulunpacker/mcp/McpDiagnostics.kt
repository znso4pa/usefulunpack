package com.usefulunpacker

import org.json.JSONObject

internal const val PREF_MCP_FAILURE = "mcp_last_failure"
internal const val PREF_MCP_FAILURE_SHOWN = "mcp_last_failure_shown"

/** Used at the request boundary, before a task exists or when a transport operation fails. */
internal fun recordMcpFailure(
    stage: String,
    code: FailureCode,
    detail: String,
    causeType: String? = null,
    ownChain: Boolean = false,
): AppFailure {
    val existing = AppDiagnostics.currentTrace()
    val scope = AppDiagnostics.enterIntent("mcp_$stage", newChain = ownChain || existing?.feature?.startsWith("mcp_") != true)
    try {
        val step = if (scope.previous == scope.trace) AppDiagnostics.begin(stage) else scope.trace
        return step.fail(code, if (causeType == null) detail else "$detail ($causeType)", notifyUser = false)
    } finally {
        AppDiagnostics.leaveIntent(scope)
    }
}

internal fun JSONObject.withMcpFailure(failure: AppFailure): JSONObject =
    put("errorCode", failure.code.name).put("stage", failure.stage).put("logId", failure.logId)
