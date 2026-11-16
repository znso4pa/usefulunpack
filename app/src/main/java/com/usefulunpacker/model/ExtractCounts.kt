package com.usefulunpacker

import org.json.JSONObject

data class ExtractCounts(val total: Int, val success: Int, val error: Int) {
    val ok: Boolean get() = success > 0 && error == 0

    companion object {
        fun fromJson(json: String?): ExtractCounts {
            return try {
                require(json != null) { "Native extraction returned no result" }
                val obj = JSONObject(json)
                val counts = ExtractCounts(obj.getInt("total"), obj.getInt("success"), obj.getInt("error"))
                require(counts.total >= 0 && counts.success >= 0 && counts.error >= 0) { "Negative extraction counts" }
                counts
            } catch (e: Exception) {
                AppDiagnostics.failure("native-extract-result", e)
                ExtractCounts(0, 0, 1)
            }
        }
    }
}

/** Per-operation extract result: counts + the raw error message (if any).
 * Replaces the old process-global `lastExtractResult`/`lastExtractError` so no
 * cross-thread shared mutable state leaks between operations. */
data class ExtractOutcome(val counts: ExtractCounts, val error: String?, val failure: AppFailure? = null)
