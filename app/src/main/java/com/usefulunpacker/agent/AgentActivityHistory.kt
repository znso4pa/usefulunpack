package com.usefulunpacker

import org.json.JSONArray
import org.json.JSONObject

/** Presentation history stays local and is never added to model message history. */
internal data class AgentActivityRecord(
    val reasoning: String? = null,
    val tool: AgentToolEvent? = null,
    val usage: AgentModelUsage? = null,
) {
    fun json(): JSONObject = JSONObject().apply {
        reasoning?.let { put("r", it.take(8192)) }
        tool?.let {
            put("t", JSONArray().put(it.callIndex).put(it.name.take(64)).put(it.path.take(1024))
                .put(it.failure?.take(256)).put(it.readOnly).put(it.stage.name)
                .put(it.resultCount).put(it.resultLabel?.take(512)))
        }
        usage?.let { put("u", JSONArray().put(it.input).put(it.output).put(it.reasoning).put(it.cachedInput).put(it.elapsedMs)) }
    }

    companion object {
        fun read(data: JSONObject): AgentActivityRecord {
            val tool = data.optJSONArray("t")?.let {
                AgentToolEvent(it.getInt(0), it.getString(1), it.getString(2), it.opt(3) as? String,
                    it.getBoolean(4), AgentToolStage.valueOf(it.getString(5)),
                    (it.opt(6) as? Number)?.toInt(), it.opt(7) as? String)
            }
            val usage = data.optJSONArray("u")?.let {
                AgentModelUsage((it.opt(0) as? Number)?.toLong(), (it.opt(1) as? Number)?.toLong(),
                    (it.opt(2) as? Number)?.toLong(), (it.opt(3) as? Number)?.toLong(), it.getLong(4))
            }
            return AgentActivityRecord(data.opt("r") as? String, tool, usage)
        }
    }
}

internal class AgentActivityHistory {
    private val records = mutableListOf<AgentActivityRecord>()
    fun reasoning(text: String) { add(AgentActivityRecord(reasoning = text)) }
    fun usage(value: AgentModelUsage) { add(AgentActivityRecord(usage = value)) }
    fun tool(value: AgentToolEvent) {
        val at = records.indexOfFirst { it.tool?.callIndex == value.callIndex }
        val record = AgentActivityRecord(tool = value)
        if (at >= 0) records[at] = record else add(record)
    }
    private fun add(record: AgentActivityRecord) { if (records.size < 96) records.add(record) }
    fun snapshot(): List<AgentActivityRecord> = records.toList()
}
