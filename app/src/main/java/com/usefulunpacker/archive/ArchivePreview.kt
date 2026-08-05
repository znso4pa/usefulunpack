package com.usefulunpacker

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.SharedPreferences
import android.graphics.drawable.ColorDrawable
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread
import java.io.File

fun parseEntries(json: String): List<ArchiveEntry> {
    val result = mutableListOf<ArchiveEntry>()
    val arr = org.json.JSONArray(json)
    for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val path = obj.getString("n")
        val size = obj.optLong("s", 0)
        val isDir = obj.optBoolean("d", false)
        val isEnc = obj.optBoolean("e", false)
        val name = path.substringAfterLast('/')
        val depth = maxOf(0, path.count { it == '/' } - if (isDir) 0 else 0)
        result.add(ArchiveEntry(path, name.ifEmpty { path }, size, isDir, isEnc, depth))
    }
    return result
}
