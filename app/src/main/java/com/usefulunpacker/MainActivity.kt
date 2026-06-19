// ╔══════════════════════════════════════════════════════════════╗
// ║  UsefulUnpack — znso4pa (锌帕) — ZArchiver UI match          ║
// ╚══════════════════════════════════════════════════════════════╝

package com.usefulunpacker

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.view.*
import android.widget.*
import android.widget.AdapterView.OnItemClickListener
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var drawer: DrawerLayout
    private lateinit var tvPath: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvSelected: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var btnExtract: Button
    private lateinit var listFiles: ListView
    private lateinit var fabExtract: FloatingActionButton
    private lateinit var listBookmarks: ListView

    private var currentDir = Environment.getExternalStorageDirectory()
    private var selectedFile: File? = null
    private val prefs: SharedPreferences by lazy { getSharedPreferences("bm", MODE_PRIVATE) }
    private val bookmarks = mutableListOf<String>()
    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    // Extraction powered by native .so (xp3 + pf8 crates)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Android 11+ need MANAGE_EXTERNAL_STORAGE to browse all files
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            if (!android.os.Environment.isExternalStorageManager()) {
                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = android.net.Uri.parse("package:$packageName")
                startActivity(intent)
                toast("请授予「所有文件访问」权限后重新打开")
                finish()
                return
            }
        }

        drawer = findViewById(R.id.drawer)
        tvPath = findViewById(R.id.tvPath)
        tvCount = findViewById(R.id.tvCount)
        tvSelected = findViewById(R.id.tvSelected)
        tvEmpty = findViewById(R.id.tvEmpty)
        bottomBar = findViewById(R.id.bottomBar)
        progress = findViewById(R.id.progress)
        btnExtract = findViewById(R.id.btnExtract)
        listFiles = findViewById(R.id.listFiles)
        fabExtract = findViewById(R.id.fabExtract)
        listBookmarks = findViewById(R.id.listBookmarks)

        findViewById<ImageButton>(R.id.btnDrawer).setOnClickListener { drawer.open() }
        findViewById<ImageButton>(R.id.btnUp).setOnClickListener { currentDir.parentFile?.let { nav(it) } }
        findViewById<TextView>(R.id.btnCLI).setOnClickListener { cli() }
        btnExtract.setOnClickListener { extract() }
        fabExtract.setOnClickListener { extract() }
        findViewById<TextView>(R.id.btnAddBookmark).setOnClickListener {
            if (bookmarks.contains(currentDir.absolutePath).not()) {
                bookmarks.add(0, currentDir.absolutePath); saveBookmarks()
            }
            drawer.close()
        }
        listFiles.onItemClickListener = OnItemClickListener { _, _, pos, _ ->
            val f = listFiles.adapter.getItem(pos) as File
            if (f.isDirectory) nav(f) else select(f)
        }
        listFiles.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, pos, _ ->
            val f = listFiles.adapter.getItem(pos) as File
            AlertDialog.Builder(this)
                .setTitle(f.name)
                .setItems(arrayOf("📋 复制路径", "ℹ️ 文件信息")) { _, w ->
                    when (w) {
                        0 -> { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("p", f.path)); toast("已复制") }
                        1 -> {
                            if (f.isDirectory) {
                                val fileCount = f.listFiles()?.size ?: 0
                                val eta = fileCount / 200
                                AlertDialog.Builder(this)
                                    .setTitle("文件夹大小")
                                    .setMessage("${f.name}\n包含 $fileCount 个项目\n\n递归计算文件夹大小需要逐文件统计，较大文件夹可能耗时 ${eta}~${eta+3} 秒。是否继续？")
                                    .setPositiveButton("计算") { _, _ -> calcDirSize(f) }
                                    .setNegativeButton("取消", null)
                                    .show()
                            } else {
                                toast("${f.name}\n${fmt(fileSize(f))}\n${df.format(Date(f.lastModified()))}")
                            }
                        }
                    }
                }.show()
            true
        }
        listBookmarks.onItemClickListener = OnItemClickListener { _, _, pos, _ ->
            nav(File(bookmarks[pos])); drawer.close()
        }
        listBookmarks.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, pos, _ ->
            bookmarks.removeAt(pos); saveBookmarks(); true
        }

        loadBookmarks(); nav(currentDir)
        showDisclaimer()
    }

    private fun showDisclaimer() {
        if (prefs.getBoolean("disclaimer_accepted", false)) return
        AlertDialog.Builder(this)
            .setTitle("免责声明")
            .setMessage("""
                UsefulUnpack 是文件解压工具，支持 XP3 / PFS 格式。

                本软件仅提供文件提取功能，不包含任何游戏内容、版权素材或破解密钥。

                用户应遵守当地法律法规，仅对您拥有合法权利的文件使用本工具。开发者（znso4pa）不对用户的任何不当使用承担责任。

                继续使用即表示您同意以上条款。
            """.trimIndent())
            .setPositiveButton("同意并继续") { _, _ ->
                prefs.edit().putBoolean("disclaimer_accepted", true).apply()
            }
            .setNegativeButton("退出") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun nav(dir: File) {
        selectedFile = null
        bottomBar.visibility = View.GONE
        fabExtract.visibility = View.GONE
        currentDir = dir
        tvPath.text = dir.absolutePath

        val files = dir.listFiles()?.sortedWith(
            compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }
        ) ?: emptyList()

        tvCount.text = "${files.size} 项"
        if (files.isEmpty()) { tvEmpty.text = "空文件夹"; tvEmpty.visibility = View.VISIBLE }
        else tvEmpty.visibility = View.GONE

        listFiles.adapter = FileAdapter(files)
    }

    private fun select(f: File) {
        selectedFile = f
        tvSelected.text = "${f.name}  |  ${fmt(fileSize(f))}"
        bottomBar.visibility = View.VISIBLE
        fabExtract.visibility = View.VISIBLE
        bottomBar.visibility = View.GONE  // 只用 FAB，隐藏底部栏
    }

    private fun extract() {
        val src = selectedFile ?: return
        val n = src.name.lowercase()
        val parent = src.parentFile ?: return
        val outDir = File(parent, src.nameWithoutExtension)

        AlertDialog.Builder(this)
            .setTitle("解压 ${src.name}")
            .setItems(arrayOf("📁 新建文件夹: ${outDir.name}", "📂 直接解压到当前目录", "📄 自选目录...")) { _, w ->
                val out = when (w) { 0 -> outDir; 1 -> parent; else -> null } ?: return@setItems
                val pd = ProgressDialog(this).apply {
                    setTitle("解压中")
                    setMessage("${src.name}\n→ ${out.name}")
                    setProgressStyle(ProgressDialog.STYLE_SPINNER)
                    setCancelable(false)
                    show()
                }
                thread {
                    val ok = if (n.endsWith(".xp3")) ArchiveCore.xp3Extract("", src.path, out.path)
                    else if (n.endsWith(".pfs")) ArchiveCore.pfsExtract("", src.path, out.path)
                    else false
                    runOnUiThread {
                        pd.dismiss()
                        if (ok) { toast("完成 → ${out.name}"); nav(currentDir) } else toast("失败")
                    }
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun cli() {
        val inp = EditText(this).apply {
            hint = "cd: $currentDir"
            setTextColor(0xFFe0f9ff.toInt()); setHintTextColor(0xFF707070.toInt())
            setBackgroundColor(0xFF303030.toInt()); textSize = 12f; minLines = 1; maxLines = 1
            setSingleLine(true)
        }
        val out = TextView(this).apply {
            text = "cd: $currentDir"
            setTextColor(0xFFb0b0b0.toInt()); textSize = 11f
            setBackgroundColor(0xFF222222.toInt()); setPadding(12,12,12,12)
            minLines = 6; gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setHorizontallyScrolling(true)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0,12,0,0)
            addView(inp, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(16,0,16,8) })
            addView(out, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(16,0,16,0) })
        }

        fun exec(cmd: String) {
            val parts = cmd.trim().split("\\s+".toRegex())
            val name = parts.getOrNull(0) ?: ""
            val args = parts.drop(1)
            thread {
                val r = when (name) {
                    "help" -> """内置命令: ls / pwd / cd <路径> / cd .. / help / 其他命令透传shell""".trimIndent()
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
                            runOnUiThread { nav(newDir) }
                            "→ ${newDir.absolutePath}"
                        } else "not found: $target"
                    }
                    else -> runCatching {
                        ProcessBuilder("/system/bin/sh", "-c", "cd \"${currentDir.absolutePath}\" && $cmd")
                            .redirectErrorStream(true).start()
                            .let { String(it.inputStream.readBytes()) }
                    }.getOrDefault("命令执行失败")
                }
                runOnUiThread { out.text = r.take(4000) }
            }
        }

        val dlg = AlertDialog.Builder(this).setTitle("Terminal").setView(layout)
            .setPositiveButton("Run", null)
            .setNegativeButton("Close", null)
            .setNeutralButton("Help", null)
            .create()
        dlg.setOnShowListener {
            val runBtn = dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            val closeBtn = dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
            val helpBtn = dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)
            runBtn?.setTextColor(0xFF35acc6.toInt())
            closeBtn?.setTextColor(0xFF888888.toInt())
            helpBtn?.setTextColor(0xFF35acc6.toInt())
            runBtn?.setOnClickListener { val c = inp.text.toString().trim(); if (c.isNotEmpty()) exec(c) }
            closeBtn?.setOnClickListener { dlg.dismiss() }
            helpBtn?.setOnClickListener { showHelp(inp) { cmd -> inp.setText(cmd); exec(cmd) } }
        }
        dlg.show()
    }

    private fun showHelp(inp: EditText, onApply: (String) -> Unit) {
        val commands = listOf(
            "列出当前目录" to "ls",
            "显示当前路径" to "pwd",
            "切换到上级目录" to "cd ..",
            "查看帮助" to "help",
        )
        var selectedCmd = ""
        var lastSelected = -1
        val listView = ListView(this)
        val adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_list_item_1,
            commands.map { "${it.first}\n  ${it.second}" }) {
            override fun getView(pos: Int, v: View?, p: ViewGroup): View {
                val view = super.getView(pos, v, p)
                (view.findViewById<TextView>(android.R.id.text1)).apply {
                    setTextColor(0xFFb0b0b0.toInt()); textSize = 12f
                }
                view.setBackgroundColor(if (pos == lastSelected) 0xFF35acc6.toInt() and 0x30ffffff else 0x00000000)
                return view
            }
        }
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ ->
            selectedCmd = commands[pos].second
            lastSelected = pos
            adapter.notifyDataSetChanged()
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("命令速查")
            .setView(listView)
            .setPositiveButton("应用此命令") { _, _ ->
                if (selectedCmd.isNotEmpty()) { inp.setText(selectedCmd); onApply(selectedCmd) }
            }
            .setNegativeButton("关闭", null)
            .create()
        dlg.show()
    }

    companion object {
        val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }

    private fun loadBookmarks() {
        val s = prefs.getStringSet("paths", emptySet()) ?: emptySet()
        bookmarks.clear(); bookmarks.addAll(s)
        val items = bookmarks.map { "📁 ${File(it).name}" }
        listBookmarks.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items) {
            override fun getView(pos: Int, v: View?, p: ViewGroup): View {
                val view = super.getView(pos, v, p)
                (view.findViewById<TextView>(android.R.id.text1)).apply { setTextColor(0xFFb0b0b0.toInt()); textSize = 13f }
                return view
            }
        }
    }

    private fun calcDirSize(dir: File) {
        val pd = ProgressDialog(this).apply {
            setTitle("计算中")
            setMessage(dir.name)
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setMax(100)
            setCancelable(false)
            show()
        }
        val fileCount = dir.listFiles()?.size ?: 0
        thread {
            var total = 0L
            var processed = 0
            dir.walkTopDown().forEach { f ->
                if (f.isFile) total += runCatching { f.length() }.getOrDefault(0L)
                processed++
                if (processed % 50 == 0) runOnUiThread { pd.progress = (processed * 100 / fileCount).coerceAtMost(100) }
            }
            runOnUiThread {
                pd.dismiss()
                AlertDialog.Builder(this)
                    .setTitle(dir.name)
                    .setMessage("总大小: ${fmt(total)}\n文件数: ${processed}")
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    private fun saveBookmarks() { prefs.edit().putStringSet("paths", bookmarks.toSet()).apply(); loadBookmarks() }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun fileSize(f: File): Long = try {
    android.system.Os.stat(f.absolutePath).st_size
} catch (e: Exception) {
    // Honor/Huawei File.length() 不可靠，用 shell stat 兜底
    runCatching {
        ProcessBuilder("stat", "-c%s", f.absolutePath).redirectErrorStream(true).start()
            .let { String(it.inputStream.readBytes()).trim().toLongOrNull() ?: 0L }
    }.getOrDefault(0L)
}

private fun fmt(b: Long) = when {
    b >= 1_073_741_824 -> "${"%.2f".format(b/1_073_741_824.0)} GB"
    b >= 1_048_576 -> "${"%.1f".format(b/1_048_576.0)} MB"
    b >= 1024 -> "${"%.1f".format(b/1024.0)} KB"
    else -> "$b B"
}

    inner class FileAdapter(private val files: List<File>) : BaseAdapter() {
        private val iconFolder = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setSize(72, 72)
            setColor(ContextCompat.getColor(this@MainActivity, R.color.ui_icon_folder) and 0x40ffffff.toInt())
        }
        override fun getCount() = files.size
        override fun getItem(pos: Int) = files[pos]
        override fun getItemId(pos: Int) = pos.toLong()
        override fun getView(pos: Int, v: View?, p: ViewGroup?): View {
            val view = v ?: layoutInflater.inflate(R.layout.item_file, p, false)
            val f = files[pos]
            val icon = view.findViewById<ImageView>(R.id.icon)
            val label = view.findViewById<TextView>(R.id.label)
            val size = view.findViewById<TextView>(R.id.info_size)
            val date = view.findViewById<TextView>(R.id.info_date)

            if (f.isDirectory) {
                icon.setImageResource(android.R.drawable.ic_menu_compass); icon.setColorFilter(0xFFffa726.toInt())
                label.text = f.name; size.text = ""; date.text = ""
            } else {
                val n = f.name.lowercase()
                val res = when { n.endsWith(".xp3")||n.endsWith(".pfs") -> android.R.drawable.ic_menu_compass; n.endsWith(".apk") -> android.R.drawable.ic_menu_manage; else -> android.R.drawable.ic_menu_gallery }
                icon.setImageResource(res); icon.setColorFilter(0xFFe0f9ff.toInt())
                label.text = f.name; size.text = fmt(fileSize(f)); date.text = df.format(Date(f.lastModified()))
            }
            return view
        }
    }
}
