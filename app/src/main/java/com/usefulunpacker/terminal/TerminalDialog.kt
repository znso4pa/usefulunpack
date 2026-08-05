package com.usefulunpacker

import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread
import java.io.File

fun showTerminal(activity: AppCompatActivity, currentDir: File, onNavigate: (File) -> Unit) {
    val inp = EditText(activity).apply {
        hint = "cd: $currentDir"
        setTextColor(C["primary"]!!); setHintTextColor(C["hint"]!!)
        setBackgroundColor(C["surface"]!!); textSize = 12f; minLines = 1; maxLines = 1
        setSingleLine(true)
    }
    val out = TextView(activity).apply {
        text = "cd: $currentDir"
        setTextColor(C["secondary"]!!); textSize = 11f
        setBackgroundColor(C["nav_bg"]!!); setPadding(12, 12, 12, 12)
        minLines = 6; gravity = Gravity.TOP or Gravity.START
        setHorizontallyScrolling(true)
    }
    val layout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, 12, 0, 0)
        addView(inp, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(16, 0, 16, 8) })
        addView(out, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(16, 0, 16, 0) })
    }

    fun exec(cmd: String) {
        val parts = cmd.trim().split("\\s+".toRegex())
        val name = parts.getOrNull(0) ?: ""
        val args = parts.drop(1)
        thread {
            val r = when (name) {
                "help" -> "内置命令: ls / pwd / cd <路径> / cd .. / help / 其他命令透传shell".trimIndent()
                "ls" -> currentDir.listFiles()?.joinToString("\n") {
                    val marker = if (it.isDirectory) "/" else ""
                    "${it.name}$marker  ${fmt(fileSize(it))}"
                } ?: "empty"
                "pwd" -> currentDir.absolutePath
                "cd" -> {
                    val target = args.getOrNull(0) ?: ""
                    val newDir = if (target == "..") currentDir.parentFile
                                 else if (target.startsWith("/")) File(target)
                                 else File(currentDir, target)
                    if (newDir != null && newDir.isDirectory) {
                        activity.runOnUiThread { onNavigate(newDir) }
                        "→ ${newDir.absolutePath}"
                    } else "not found: $target"
                }
                else -> runCatching {
                    ProcessBuilder("/system/bin/sh", "-c", "cd \"${currentDir.absolutePath}\" && $cmd")
                        .redirectErrorStream(true).start()
                        .let { String(it.inputStream.readBytes()) }
                }.getOrDefault("命令执行失败")
            }
            activity.runOnUiThread { out.text = r.take(4000) }
        }
    }

    val dlg = AlertDialog.Builder(activity).setTitle("Terminal").setView(layout)
        .setPositiveButton("Run", null)
        .setNegativeButton("Close", null)
        .setNeutralButton("Help", null)
        .create()
    dlg.setOnShowListener {
        dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { val c = inp.text.toString().trim(); if (c.isNotEmpty()) exec(c) }
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener { dlg.dismiss() }
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            showTerminalHelp(activity, inp) { cmd -> inp.setText(cmd); exec(cmd) }
        }
    }
    dlg.show()
}

fun showTerminalHelp(activity: AppCompatActivity, inp: EditText, onApply: (String) -> Unit) {
    val commands = listOf(
        "列出当前目录" to "ls",
        "显示当前路径" to "pwd",
        "切换到上级目录" to "cd ..",
        "查看帮助" to "help",
    )
    var selectedCmd = ""
    var lastSelected = -1
    val listView = ListView(activity)
    val adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1,
        commands.map { "${it.first}\n  ${it.second}" }) {
        override fun getView(pos: Int, v: View?, p: ViewGroup): View {
            val view = super.getView(pos, v, p)
            (view.findViewById<TextView>(android.R.id.text1)).apply {
                setTextColor(C["secondary"]!!); textSize = 12f
            }
            view.setBackgroundColor(if (pos == lastSelected) C["accent"]!! and 0x30ffffff else 0x00000000)
            return view
        }
    }
    listView.adapter = adapter
    listView.setOnItemClickListener { _, _, pos, _ ->
        selectedCmd = commands[pos].second
        lastSelected = pos
        adapter.notifyDataSetChanged()
    }
    AlertDialog.Builder(activity)
        .setTitle(activity.getString(R.string.title_help))
        .setView(listView)
        .setPositiveButton(activity.getString(R.string.action_confirm)) { _, _ ->
            if (selectedCmd.isNotEmpty()) { inp.setText(selectedCmd); onApply(selectedCmd) }
        }
        .setNegativeButton(activity.getString(R.string.action_close), null)
        .create()
        .show()
}
