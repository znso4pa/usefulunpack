package com.usefulunpacker.fileops

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 最近打开的归档 —— 主界面（hub）「最近」列表的数据源。
 *
 * 只存 `path + 时间`，上限 [CAP] 条；重复打开同一文件提到最前。存成一个
 * SharedPreferences JSON 数组 —— 最多 8 条，不值得上 Room / 独立文件。
 *
 * 记录点：归档**真正打开**时（预览 / 编辑，`OpenArchiveRegistry.register` 成功
 * 之后）。打不开的（密码错、解析失败）不记 —— 「最近」里不该有没看过的东西。
 */
object RecentsStore {

    private const val KEY = "recent_archives"
    private const val CAP = 8

    data class Entry(val path: String, val time: Long) {
        val file: File get() = File(path)
    }

    /** 记一次打开：去重、置顶、截断到 [CAP]。 */
    fun record(context: Context, file: File) {
        val prefs = prefs(context)
        val list = read(prefs).toMutableList()
        list.removeAll { it.path == file.absolutePath }
        list.add(0, Entry(file.absolutePath, System.currentTimeMillis()))
        while (list.size > CAP) list.removeAt(list.size - 1)
        val arr = org.json.JSONArray()
        list.forEach { arr.put(JSONObject().put("p", it.path).put("t", it.time)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    /** 现在的「最近」列表，已过滤掉磁盘上不存在的条目（挪走/删掉的不出现）。 */
    fun list(context: Context): List<Entry> = read(prefs(context)).filter { it.file.exists() }

    private fun prefs(context: Context) = context.getSharedPreferences("recents", Context.MODE_PRIVATE)

    private fun read(prefs: android.content.SharedPreferences): List<Entry> {
        val s = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val p = o.optString("p")
                if (p.isEmpty()) null else Entry(p, o.optLong("t"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
