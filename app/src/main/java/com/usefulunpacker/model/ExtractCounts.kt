package com.usefulunpacker

import org.json.JSONObject

data class ExtractCounts(val total: Int, val success: Int, val error: Int) {
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
