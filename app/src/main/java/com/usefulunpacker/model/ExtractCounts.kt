package com.usefulunpacker

import org.json.JSONObject

data class ExtractCounts(val total: Int, val success: Int, val error: Int) {
    val ok: Boolean get() = success > 0 && error == 0

    companion object {
        fun fromJson(json: String?): ExtractCounts {
            if (json == null) return ExtractCounts(0, 0, 1)
            return try {
                val obj = JSONObject(json)
                ExtractCounts(obj.optInt("total", 0), obj.optInt("success", 0), obj.optInt("error", 0))
            } catch (_: Exception) { ExtractCounts(0, 0, 1) }
        }
    }
}

/** Per-operation extract result: counts + the raw error message (if any).
 * Replaces the old process-global `lastExtractResult`/`lastExtractError` so no
 * cross-thread shared mutable state leaks between operations. */
data class ExtractOutcome(val counts: ExtractCounts, val error: String?)
