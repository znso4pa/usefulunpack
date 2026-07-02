// ╔══════════════════════════════════════════════════════════════╗
// ║  UsefulUnpack — znso4pa (锌帕) — ZArchiver UI match          ║
// ╚══════════════════════════════════════════════════════════════╝

package com.usefulunpacker

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.*
import android.widget.*
import android.widget.AdapterView.OnItemClickListener
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONArray
import org.json.JSONObject
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
    private var lastTap = 0L

    private fun tryTap(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastTap < 800) return false
        lastTap = now
        return true
    }

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
        findViewById<ImageButton>(R.id.btnRoot).setOnClickListener { nav(Environment.getExternalStorageDirectory()) }
        findViewById<ImageButton>(R.id.btnUp).setOnClickListener { currentDir.parentFile?.let { nav(it) } }
        findViewById<TextView>(R.id.btnCLI).setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add(0, 0, 0, ">_ CLI")
            popup.menu.add(0, 1, 1, "🔍 全局搜索")
            popup.menu.add(0, 2, 2, "📌 书签管理")
            popup.menu.add(0, 3, 3, "⚙️ 设置")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    0 -> cli()
                    1 -> globalSearch()
                    2 -> drawer.open()
                    3 -> toast("设置 — 即将推出")
                }
                true
            }
            popup.show()
        }
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
            if (f.isDirectory) { nav(f); return@OnItemClickListener }
            if (tryTap()) select(f)
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

        val raw = dir.listFiles()
        val files: List<File> = when {
            raw != null -> raw.sortedWith(
                compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }
            )
            // listFiles() returned null — permission denied, e.g. /storage/emulated.
            // Probe known hidden subdirectories so the user can still navigate.
            else -> {
                val probed = mutableListOf<File>()
                for (name in arrayOf("0", "self", "primary")) {
                    val child = File(dir, name)
                    if (child.isDirectory) probed.add(child)
                }
                probed
            }
        }

        tvCount.text = "${files.size} 项"
        if (files.isEmpty()) {
            tvEmpty.text = if (raw == null) "无访问权限" else "空文件夹"
            tvEmpty.visibility = View.VISIBLE
        } else tvEmpty.visibility = View.GONE

        listFiles.adapter = FileAdapter(files)
    }

    private val PREVIEW_EXTS = setOf("jpg", "jpeg", "png", "mp3", "ogg", "mp4",
        "txt", "json", "ini", "ks", "lua", "py", "js", "html", "css", "xml", "cfg", "log")

    private fun select(f: File) {
        val ext = f.name.lowercase().substringAfterLast('.')

        // Archive files → show FAB for extraction
        if (ext in ARCHIVE_EXTS) {
            selectedFile = f
            tvSelected.text = "${f.name}  |  ${fmt(fileSize(f))}"
            fabExtract.visibility = View.VISIBLE
            return
        }

        // Previewable non-archive files → show preview dialog
        if (ext in PREVIEW_EXTS) {
            AlertDialog.Builder(this)
                .setTitle(f.name)
                .setItems(arrayOf("🔍 预览", "ℹ️ 文件信息")) { _, w ->
                    when (w) {
                        0 -> previewLocalFile(f)
                        1 -> toast("${f.name}\n${fmt(fileSize(f))}\n${df.format(Date(f.lastModified()))}")
                    }
                }.setNegativeButton("取消", null).show()
            return
        }

        // Neither archive nor previewable — just show info
        toast("${f.name}\n${fmt(fileSize(f))}\n${df.format(Date(f.lastModified()))}")
    }

    private fun previewLocalFile(f: File) {
        val ext = f.name.lowercase().substringAfterLast('.')
        when (ext) {
            "jpg", "jpeg", "png" -> showImagePreview(f)
            "mp3", "ogg" -> playAudio(f)
            "mp4" -> playVideo(f)
            else -> showTextPreview(f)
        }
    }

    private val ARCHIVE_EXTS = setOf("xp3", "pfs", "pf6", "pf8", "nsa", "sar", "iso", "ypf", "zip", "7z")
    private val TEXT_SEARCH_EXTS = setOf(
        "txt", "json", "ini", "ks", "lua", "py", "js", "html", "css", "xml", "cfg", "log",
        "rtf", "md", "yaml", "yml", "toml", "conf", "properties", "sh", "java", "kt", "rs",
        "c", "cpp", "h", "hpp", "swift", "rb", "php", "pl", "sql", "tsv",
        "srt", "ass", "lrc", "bat", "cmd", "ps1", "go", "dart", "r", "csv"
    )

    private fun extract() {
        val src = selectedFile ?: return
        val ext = src.name.lowercase().substringAfterLast('.')
        // Block files that are clearly not archives (e.g. .jpg) to prevent JNI crash
        if (ext !in ARCHIVE_EXTS && src.isDirectory.not()) {
            AlertDialog.Builder(this)
                .setTitle("无法解压")
                .setMessage(".${ext} 不是压缩包格式\n请选择 .xp3 / .pfs / .nsa / .sar 文件")
                .setPositiveButton("确定", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("选择归档格式")
            .setItems(arrayOf("📦 XP3", "📦 PFS", "📦 NSA/SAR", "📀 ISO", "📦 YPF", "🗜️ ZIP", "📦 7z")) { _, which ->
                val format = arrayOf("xp3", "pfs", "nsa", "iso", "ypf", "zip", "7z")[which]
                showExtractOptions(src, format)
            }.setNegativeButton("取消", null).show()
    }

    private fun showExtractOptions(src: File, format: String) {
        val parent = src.parentFile ?: return
        val outDir = File(parent, src.nameWithoutExtension)

        AlertDialog.Builder(this)
            .setTitle("${src.name} (${format.uppercase()})")
            .setItems(arrayOf("🔍 先预览内容", "📦 直接解压")) { _, w ->
                when (w) {
                    0 -> previewArchive(src, format)
                    1 -> showDirectExtractDialog(src, format, parent, outDir)
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun showDirectExtractDialog(src: File, format: String, parent: File, outDir: File) {
        AlertDialog.Builder(this)
            .setTitle("解压到...")
            .setItems(arrayOf("📁 新建文件夹: ${outDir.name}", "📂 直接解压到当前目录")) { _, w ->
                val out = if (w == 0) outDir else parent
                extractAll(out, src, format)
            }.setNegativeButton("取消", null).show()
    }

    private fun extractAll(out: File, src: File, format: String) {
        val pd = ProgressDialog(this).apply {
            setTitle("解压中")
            setMessage("${src.name} → ${out.name}")
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            show()
        }
        thread {
            val result = runCatching { extractByFormat(format, src.path, out.path, "") }
            runOnUiThread {
                pd.dismiss()
                val ok = result.getOrDefault(false)
                if (ok) { toast("完成 → ${out.name}"); nav(currentDir) }
                else toast(result.exceptionOrNull()?.message ?: mismatchMsg(format, src))
            }
        }
    }

    private fun extractByFormat(format: String, src: String, out: String, selected: String): Boolean {
        return when (format) {
            "xp3" -> if (selected.isEmpty()) Xp3Core.xp3Extract("", src, out)
                     else Xp3Core.xp3ExtractSelected("", src, out, selected)
            "pfs" -> if (selected.isEmpty()) PfsCore.pfsExtract("", src, out)
                     else PfsCore.pfsExtractSelected("", src, out, selected)
            "iso" -> if (selected.isEmpty()) IsoCore.isoExtract("", src, out)
                     else IsoCore.isoExtractSelected("", src, out, selected)
            "ypf" -> if (selected.isEmpty()) YpfCore.ypfExtract("", src, out)
                     else YpfCore.ypfExtractSelected("", src, out, selected)
            "zip" -> if (selected.isEmpty()) ZipCore.zipExtract("", src, out)
                     else ZipCore.zipExtractSelected("", src, out, selected)
            "7z" -> if (selected.isEmpty()) SevenZCore.szExtract("", src, out)
                     else SevenZCore.szExtractSelected("", src, out, selected)
            "nsa" -> if (selected.isEmpty()) NsaCore.nsaExtract("", src, out)
                     else NsaCore.nsaExtractSelected("", src, out, selected)
            else -> false
        }
    }

    private fun mismatchMsg(format: String, file: File): String {
        val ext = file.name.lowercase().substringAfterLast('.')
        val exts = when (format) {
            "pfs" -> setOf("pfs", "pf6", "pf8")
            "nsa" -> setOf("nsa", "sar")
            "iso" -> setOf("iso")
            "ypf" -> setOf("ypf")
            "zip" -> setOf("zip")
            "7z" -> setOf("7z")
            else -> setOf(format)
        }
        return if (ext !in exts) "后缀 .$ext 与格式 ${format.uppercase()} 不匹配"
               else "解压失败"
    }

    private fun parseEntries(json: String): List<ArchiveEntry> {
        val result = mutableListOf<ArchiveEntry>()
        val arr = JSONArray(json)
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

    private fun previewArchive(src: File, format: String) {
        val pd = ProgressDialog(this).apply {
            setTitle("读取中")
            setMessage("正在读取 ${src.name} 的内容...")
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            show()
        }
        thread {
            val json = try { when(format) { "xp3" -> Xp3Core.xp3ListEntries(src.absolutePath)
                "pfs" -> PfsCore.pfsListEntries(src.absolutePath)
                "nsa" -> NsaCore.nsaListEntries(src.absolutePath)
                "iso" -> IsoCore.isoListEntries(src.absolutePath)
                "ypf" -> YpfCore.ypfListEntries(src.absolutePath)
                "zip" -> ZipCore.zipListEntries(src.absolutePath)
                "7z" -> SevenZCore.szListEntries(src.absolutePath)
                else -> null
            } } catch (_: Exception) { null }
            runOnUiThread { pd.dismiss() }
            if (json == null || json == "[]") {
                runOnUiThread { toast("无法读取归档内容") }
                return@thread
            }
            val entries = parseEntries(json)
            runOnUiThread { showPreviewDialog(src, entries, format) }
        }
    }

    private fun showPreviewDialog(src: File, entries: List<ArchiveEntry>, format: String) {
        val selectedPaths = mutableSetOf<String>()
        val expandedPaths = entries.filter { it.isDirectory }.map { it.path }.toMutableSet()

        val totalFiles = entries.count { !it.isDirectory }
        val totalSize = entries.filter { !it.isDirectory }.sumOf { it.size }
        val tvStats = TextView(this).apply {
            text = "共 $totalFiles 文件，${fmt(totalSize)}  |  已选 0 项"
            setTextColor(0xFFaaaaaa.toInt()); textSize = 12f
            setPadding(12, 8, 12, 4)
            setBackgroundColor(0xFF252525.toInt())
        }

        val adapter = PreviewAdapter(entries, selectedPaths, expandedPaths, { entry ->
            previewFileEntry(src, entry, format)
        }, {
            val sel = selectedPaths.filter { !it.endsWith("/") || selectedPaths.none { p -> p != it && p.startsWith(it) } }
            val selFiles = sel.count { p -> entries.find { e -> e.path == p }?.isDirectory == false }
            val selSize = sel.sumOf { p -> entries.find { e -> e.path == p }?.size ?: 0L }
            tvStats.text = "共 $totalFiles 文件，${fmt(totalSize)}  |  已选 $selFiles 项，${fmt(selSize)}"
        })

        val listView = ListView(this).apply {
            this.adapter = adapter
            setBackgroundColor(0xFF303030.toInt())
            divider = ColorDrawable(0xFF1a1a1a.toInt())
            dividerHeight = 1
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tvStats, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(listView, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }

        // Custom title bar with search button at top-right
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 14, 8, 14)
            setBackgroundColor(0xFF303030.toInt())
        }
        titleBar.addView(TextView(this).apply {
            text = "预览 ${src.name}"
            setTextColor(0xFFe0f9ff.toInt()); textSize = 17f
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })

        // Must declare dlg before btnSearchTitle so its lambda can capture it
        lateinit var dlg: AlertDialog
        val btnSearchTitle = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            setBackgroundColor(0xFF303030.toInt())
            setPadding(8, 4, 8, 4)
            scaleType = ImageView.ScaleType.FIT_XY
            layoutParams = LinearLayout.LayoutParams(52, 40)
            setOnClickListener {
                val cacheDir = File(cacheDir, "archive_search/${src.nameWithoutExtension}")
                cacheDir.deleteRecursively()
                cacheDir.mkdirs()
                val pd = ProgressDialog(this@MainActivity).apply {
                    setTitle("准备搜索")
                    setMessage("正在准备 ${src.name} 的文件索引...")
                    setProgressStyle(ProgressDialog.STYLE_SPINNER)
                    setCancelable(false); show()
                }
                thread {
                    searchSourceArchive = src; searchSourceFormat = format
                    searchSourceCacheBase = cacheDir
                    // Phase 1: touch empty placeholder files for ALL entries (fast, for filename search)
                    for (e in entries) {
                        if (e.isDirectory) continue
                        val f = File(cacheDir, e.path)
                        f.parentFile?.mkdirs()
                        f.createNewFile()
                    }
                    // Phase 2: extract only text files (overwrites placeholders, for content search)
                    val textExts = TEXT_SEARCH_EXTS
                    for (e in entries) {
                        if (e.isDirectory) continue
                        val ext = e.path.substringAfterLast('.').lowercase()
                        if (ext !in textExts) continue
                        extractByFormat(format, src.path, cacheDir.path, e.path)
                    }
                    runOnUiThread {
                        pd.dismiss()
                        dlg.dismiss()
                        globalSearch(cacheDir, tempDir = cacheDir)
                    }
                }
            }
        }
        titleBar.addView(btnSearchTitle)

        dlg = AlertDialog.Builder(this)
            .setCustomTitle(titleBar)
            .setView(layout)
            .setPositiveButton("解压所选", null)
            .setNegativeButton("取消", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val sel = selectedPaths.filter { it.endsWith("/").not() || selectedPaths.none { p -> p != it && p.startsWith(it) } }
                if (sel.isEmpty()) {
                    toast("请至少选择一项")
                } else {
                    dlg.dismiss()
                    showOutputDirDialog(src, sel, format)
                }
            }
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFF35acc6.toInt())
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(0xFF888888.toInt())
        }
        dlg.show()
    }

    private fun showOutputDirDialog(src: File, selectedPaths: List<String>, format: String) {
        val parent = src.parentFile ?: return
        val outDir = File(parent, src.nameWithoutExtension)

        AlertDialog.Builder(this)
            .setTitle("解压到...")
            .setItems(arrayOf("📁 新建文件夹: ${outDir.name}", "📂 直接解压到当前目录")) { _, w ->
                val out = if (w == 0) outDir else parent
                extractSelected(src, out, selectedPaths, format)
            }.setNegativeButton("取消", null)
            .show()
    }

    private fun extractSelected(src: File, out: File, paths: List<String>, format: String) {
        val selStr = paths.joinToString("\n")
        val pd = ProgressDialog(this).apply {
            setTitle("解压中")
            setMessage("${src.name}\n→ ${out.name}\n已选 ${paths.size} 项")
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            show()
        }
        thread {
            val result = runCatching { extractByFormat(format, src.path, out.path, selStr) }
            runOnUiThread {
                pd.dismiss()
                val ok = result.getOrDefault(false)
                if (ok) { toast("完成 → ${out.name}"); nav(currentDir) }
                else toast(result.exceptionOrNull()?.message ?: mismatchMsg(format, src))
            }
        }
    }

    private fun previewFileEntry(archive: File, entry: ArchiveEntry, format: String) {
        val ext = entry.path.substringAfterLast('.').lowercase()
        val TEXT_EXTS = setOf("txt", "json", "ini", "ks", "lua", "py", "js", "html", "css", "xml", "cfg", "log")
        if (ext !in setOf("jpg", "jpeg", "png", "mp3", "ogg", "mp4") && ext !in TEXT_EXTS) {
            toast("不支持预览 .$ext 文件")
            return
        }

        val cacheDir = File(cacheDir, "preview/${archive.nameWithoutExtension}")
        thread {
            val ok = extractByFormat(format, archive.path, cacheDir.path, entry.path)
            if (!ok) { runOnUiThread { toast("提取失败") }; return@thread }

            val extracted = File(cacheDir, entry.path)
            runOnUiThread {
                when (ext) {
                    "jpg", "jpeg", "png" -> showImagePreview(extracted)
                    "mp3", "ogg" -> playAudio(extracted)
                    "mp4" -> playVideo(extracted)
                    else -> showTextPreview(extracted)
                }
            }
        }
    }

    private fun showImagePreview(file: File) {
        val bmp = BitmapFactory.decodeFile(file.path)
        if (bmp == null) { toast("无法解码图片"); return }

        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            setBackgroundColor(0xFF000000.toInt())
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            maxWidth = resources.displayMetrics.widthPixels
            maxHeight = (resources.displayMetrics.heightPixels * 0.8).toInt()
        }

        val scroll = ScrollView(this).apply {
            addView(iv)
            setBackgroundColor(0xFF000000.toInt())
        }

        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun showTextPreview(file: File, highlightLine: Int = 0, highlightQuery: String = "") {
        val raw = runCatching { file.readText() }.getOrElse { "无法读取文件: ${it.message}" }
        val displayText = raw.take(50000)
        val matchPos = mutableListOf<Int>() // character indices of each match

        // Build spannable with all match positions tracked
        var spannable: android.text.SpannableString? = null
        if (highlightQuery.isNotEmpty()) {
            spannable = android.text.SpannableString(displayText)
            var idx = 0
            val lowerText = displayText.lowercase()
            val lowerQuery = highlightQuery.lowercase()
            while (true) {
                val pos = lowerText.indexOf(lowerQuery, idx)
                if (pos < 0) break
                matchPos.add(pos)
                idx = pos + 1
            }
        }

        fun applyHighlights(selectedIdx: Int) {
            val s = spannable ?: return
            val len = highlightQuery.length
            // Remove all existing BackgroundColorSpans
            for (span in s.getSpans(0, s.length, android.text.style.BackgroundColorSpan::class.java)) {
                s.removeSpan(span)
            }
            // Re-apply: dim for non-selected, bright for selected
            for (i in matchPos.indices) {
                val color = if (i == selectedIdx) 0x88FFAA00.toInt() else 0x33FFAA00.toInt()
                s.setSpan(
                    android.text.style.BackgroundColorSpan(color),
                    matchPos[i], matchPos[i] + len,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        // Apply initial highlights (first match selected)
        if (spannable != null && matchPos.isNotEmpty()) applyHighlights(0)
        else spannable = null

        val tv = TextView(this@MainActivity).apply {
            this.text = spannable ?: displayText
            setTextColor(0xFFe0f9ff.toInt())
            textSize = 12f
            setBackgroundColor(0xFF1a1a1a.toInt())
            setPadding(16, 16, 16, 16)
            isVerticalScrollBarEnabled = true
            movementMethod = android.text.method.ScrollingMovementMethod()
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroll = ScrollView(this@MainActivity).apply {
            addView(tv)
            setBackgroundColor(0xFF1a1a1a.toInt())
        }
        // Scroll to highlightLine on first show
        if (highlightLine > 0) {
            tv.post {
                val layout = tv.layout ?: return@post
                val lineIdx = (highlightLine - 1).coerceIn(0, layout.lineCount - 1)
                val y = layout.getLineTop(lineIdx) - (scroll.height / 3)
                scroll.scrollTo(0, y.coerceAtLeast(0))
            }
        }

        val root = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }

        // Navigation bar for multiple matches
        if (matchPos.size > 1) {
            var curMatch = 0
            if (highlightLine > 0) {
                val layout = tv.layout
                if (layout != null) {
                    val targetLine = highlightLine - 1
                    curMatch = matchPos.indices.minByOrNull {
                        kotlin.math.abs(layout.getLineForOffset(matchPos[it]) - targetLine)
                    } ?: 0
                }
            }
            val navBar = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setBackgroundColor(0xFF222222.toInt())
                setPadding(0, 6, 0, 6)
            }
            val btnPrev = Button(this@MainActivity).apply {
                text = "◀ 上一个"; textSize = 12f; isAllCaps = false
                setTextColor(0xFF35acc6.toInt()); background = null
                setPadding(12, 4, 12, 4)
            }
            val tvCounter = TextView(this@MainActivity).apply {
                gravity = Gravity.CENTER; textSize = 12f
                setTextColor(0xFFaaaaaa.toInt())
                setPadding(20, 4, 20, 4)
            }
            val btnNext = Button(this@MainActivity).apply {
                text = "下一个 ▶"; textSize = 12f; isAllCaps = false
                setTextColor(0xFF35acc6.toInt()); background = null
                setPadding(12, 4, 12, 4)
            }
            fun scrollToMatch(idx: Int) {
                curMatch = idx.coerceIn(0, matchPos.size - 1)
                tvCounter.text = "${curMatch + 1} / ${matchPos.size}"
                // Update highlights: selected brighter, others dimmer
                applyHighlights(curMatch)
                tv.text = spannable // force re-render with updated spans
                tv.post {
                    val layout = tv.layout ?: return@post
                    val line = layout.getLineForOffset(matchPos[curMatch])
                    val y = layout.getLineTop(line) - (scroll.height / 3)
                    scroll.scrollTo(0, y.coerceAtLeast(0))
                }
            }
            btnPrev.setOnClickListener { scrollToMatch(curMatch - 1) }
            btnNext.setOnClickListener { scrollToMatch(curMatch + 1) }
            navBar.addView(btnPrev)
            navBar.addView(tvCounter, LinearLayout.LayoutParams(0, WRAP, 1f))
            navBar.addView(btnNext)
            root.addView(navBar, LinearLayout.LayoutParams(MATCH, WRAP))
            scrollToMatch(curMatch)
        }

        val title = if (highlightLine > 0) "${file.name} (行 $highlightLine)" else file.name
        AlertDialog.Builder(this@MainActivity)
            .setTitle(title)
            .setView(root)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun playAudio(file: File) {
        try {
            val mp = MediaPlayer().apply {
                setDataSource(file.path)
                prepare()
                start()
            }
            AlertDialog.Builder(this)
                .setTitle("🎵 ${file.name}")
                .setMessage("正在播放…")
                .setPositiveButton("停止") { _, _ -> mp.release() }
                .setOnDismissListener { mp.release() }
                .show()
        } catch (e: Exception) {
            toast("无法播放音频: ${e.message}")
        }
    }

    private fun playVideo(file: File) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.fromFile(file), "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            toast("无法播放视频: ${e.message}")
        }
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

    data class SearchResult(
        val file: File,
        val snippet: String = "",
        val lineNumber: Int = 0,
        val matchCount: Int = 0
    )

    private fun globalSearch(startDir: File? = null, tempDir: File? = null) {
        var searchMode = 0 // 0=filename, 1=content
        var searchDir = startDir ?: currentDir
        val results = mutableListOf<SearchResult>()
        var searchThread: Thread? = null
        lateinit var searchDialog: AlertDialog
        var currentLimit = 200
        val seenFiles = mutableSetOf<String>()
        var queryText = ""
        var currentMaxFileSize = Long.MAX_VALUE

        // Directory display + change button
        val tvDir = TextView(this).apply {
            text = "📁 搜索范围: ${searchDir.path}"
            setTextColor(0xFFb0b0b0.toInt()); textSize = 11f; setPadding(16, 8, 16, 2)
        }
        val btnChangeDir = Button(this).apply {
            text = "更改"; setTextColor(0xFF35acc6.toInt()); background = null; textSize = 11f
            setPadding(0, 0, 16, 0)
            setOnClickListener {
                val dirInput = EditText(this@MainActivity).apply {
                    setText(searchDir.path); setTextColor(0xFFe0f9ff.toInt())
                    setHintTextColor(0xFF707070.toInt()); setBackgroundColor(0xFF303030.toInt())
                    setPadding(12, 8, 12, 8)
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("输入搜索目录").setView(dirInput)
                    .setPositiveButton("确定") { _, _ ->
                        val d = File(dirInput.text.toString().trim())
                        if (d.isDirectory) { searchDir = d; tvDir.text = "📁 搜索范围: ${d.path}" }
                        else toast("目录不存在")
                    }.setNegativeButton("取消", null).show()
            }
        }
        val dirRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tvDir, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(btnChangeDir, LinearLayout.LayoutParams(WRAP, WRAP))
        }

        // Mode toggle
        lateinit var btnFilename: Button
        lateinit var btnContent: Button
        fun selectMode(mode: Int) {
            searchMode = mode
            val onColor = 0xFF35acc6.toInt(); val offBg = 0xFF2a2a2a.toInt()
            btnFilename.setBackgroundColor(if (mode == 0) onColor else offBg)
            btnFilename.setTextColor(if (mode == 0) 0xFF000000.toInt() else 0xFF888888.toInt())
            btnContent.setBackgroundColor(if (mode == 1) onColor else offBg)
            btnContent.setTextColor(if (mode == 1) 0xFF000000.toInt() else 0xFF888888.toInt())
        }
        btnFilename = Button(this).apply {
            text = "📄 文件名搜索"; textSize = 13f; isAllCaps = false
            setPadding(24, 6, 24, 6); setOnClickListener { selectMode(0) }
        }
        btnContent = Button(this).apply {
            text = "📝 内容搜索"; textSize = 13f; isAllCaps = false
            setPadding(24, 6, 24, 6); setOnClickListener { selectMode(1) }
        }
        selectMode(0)
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(16, 4, 16, 4)
            addView(btnFilename); addView(btnContent, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(8, 0, 0, 0) })
        }

        // Search input
        val etQuery = EditText(this).apply {
            hint = "输入关键词..."; setTextColor(0xFFe0f9ff.toInt()); setHintTextColor(0xFF707070.toInt())
            setBackgroundColor(0xFF303030.toInt()); textSize = 14f; setPadding(12, 8, 12, 8); setSingleLine(true)
        }

        // Search button
        val btnSearch = Button(this).apply {
            text = "🔍 搜索"; setTextColor(0xFF35acc6.toInt()); textSize = 14f
        }

        // Progress bar (indeterminate while searching)
        val searchProgress = ProgressBar(this).apply {
            isIndeterminate = true; visibility = View.GONE
            setPadding(16, 4, 16, 0)
        }

        // Stats + continue button
        val tvStats = TextView(this).apply {
            text = "输入关键词开始搜索"; setTextColor(0xFFaaaaaa.toInt()); textSize = 12f
        }
        val btnContinue = Button(this).apply {
            text = "继续扫描"; setTextColor(0xFF35acc6.toInt()); textSize = 12f
            background = null; visibility = View.GONE; setPadding(0, 0, 0, 0)
        }
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(16, 2, 16, 4)
            addView(tvStats, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(btnContinue, LinearLayout.LayoutParams(WRAP, WRAP))
        }

        // Results adapter — uses snapshot to avoid threading crashes
        val resultAdapter = object : BaseAdapter() {
            @Volatile private var snapshot: List<SearchResult> = emptyList()
            fun refresh() { snapshot = results.toList(); notifyDataSetChanged() }
            override fun getCount() = snapshot.size
            override fun getItem(pos: Int) = snapshot.getOrNull(pos) ?: SearchResult(File(""))
            override fun getItemId(pos: Int) = pos.toLong()
            override fun getView(pos: Int, v: View?, p: ViewGroup?): View {
                val view = v ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, p, false)
                val r = snapshot.getOrNull(pos) ?: return view
                view.findViewById<TextView>(android.R.id.text1).apply {
                    val matchTag = if (r.matchCount > 1) "  (${r.matchCount} 处匹配)" else ""
                    val lineTag = if (r.lineNumber > 0) "  [行 ${r.lineNumber}]" else ""
                    text = "${r.file.name}$lineTag$matchTag"
                    setTextColor(0xFFe0f9ff.toInt()); textSize = 14f; setSingleLine(true)
                }
                view.findViewById<TextView>(android.R.id.text2).apply {
                    text = if (r.snippet.isNotEmpty()) "${r.file.parent}\n${r.snippet}"
                           else "${r.file.parent}  •  ${fmt(r.file.length())}"
                    setTextColor(0xFF888888.toInt()); textSize = 11f; maxLines = 3
                }
                view.setBackgroundColor(0xFF303030.toInt())
                return view
            }
        }

        val listResults = ListView(this).apply {
            adapter = resultAdapter; setBackgroundColor(0xFF303030.toInt())
            divider = ColorDrawable(0xFF1a1a1a.toInt()); dividerHeight = 1
            onItemClickListener = OnItemClickListener { _, _, pos, _ ->
                val r = resultAdapter.getItem(pos) as SearchResult
                if (r.file.path.isEmpty()) return@OnItemClickListener
                searchDialog.dismiss()
                // If 0-byte placeholder from archive search, extract on demand
                val archiveSrc = searchSourceArchive
                val fmt = searchSourceFormat
                val cacheBase = searchSourceCacheBase
                if (r.file.length() == 0L && archiveSrc != null && fmt != null && cacheBase != null) {
                    val pd = ProgressDialog(this@MainActivity).apply {
                        setTitle("提取中"); setMessage(r.file.name)
                        setProgressStyle(ProgressDialog.STYLE_SPINNER); setCancelable(false); show()
                    }
                    val relPath = r.file.absolutePath.removePrefix(cacheBase.absolutePath + "/")
                    thread {
                        extractByFormat(fmt, archiveSrc.path, cacheBase.path, relPath)
                        runOnUiThread { pd.dismiss(); previewClickedFile(r, queryText) }
                    }
                } else {
                    previewClickedFile(r, queryText)
                }
            }
        }

        // Layout
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(dirRow, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(modeRow, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(etQuery, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(16, 4, 16, 4) })
            addView(btnSearch, LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(searchProgress, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(statsRow, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(listResults, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }

        searchDialog = AlertDialog.Builder(this)
            .setTitle("🔍 全局搜索")
            .setView(layout)
            .setNegativeButton("关闭", null)
            .create()
        searchDialog.show()

        // Launch search
        fun doSearch(query: String, mode: Int, maxFileSize: Long, isContinue: Boolean = false) {
            searchThread?.interrupt()
            if (query.isEmpty()) { toast("请输入关键词"); return }
            currentMaxFileSize = maxFileSize
            if (!isContinue) { results.clear(); seenFiles.clear(); currentLimit = 200 }
            else currentLimit += 200
            resultAdapter.refresh()
            searchProgress.visibility = View.VISIBLE
            btnContinue.visibility = View.GONE
            tvStats.text = if (isContinue) "继续扫描中..." else "搜索中..."
            val scanned = intArrayOf(0)
            searchThread = thread {
                walkSearch(query.lowercase(), searchDir, mode, results, currentLimit, maxFileSize, scanned, seenFiles)
                runOnUiThread {
                    searchProgress.visibility = View.GONE
                    val hasMore = results.size >= currentLimit && results.size < 10000
                    btnContinue.visibility = if (hasMore) View.VISIBLE else View.GONE
                    tvStats.text = "找到 ${results.size} 个结果（共扫描 ${scanned[0]} 个文件）"
                    resultAdapter.refresh()
                    if (results.isEmpty()) toast("未找到匹配结果")
                }
            }
            // Periodically update stats while searching
            thread {
                var lastScanned = 0
                while (searchThread?.isAlive == true) {
                    Thread.sleep(200)
                    val cur = scanned[0]
                    if (cur != lastScanned) {
                        lastScanned = cur
                        runOnUiThread { tvStats.text = "搜索中... 已扫描 $cur 个文件，找到 ${results.size} 个结果" }
                    }
                }
            }
        }

        btnSearch.setOnClickListener {
            queryText = etQuery.text.toString().trim()
            if (queryText.isEmpty()) { toast("请输入关键词"); return@setOnClickListener }
            if (searchMode == 1) {
                // Content search: ask for single-file size limit
                val labels = arrayOf("100 KB", "500 KB", "1 MB", "5 MB", "10 MB", "不限制")
                val limits = longArrayOf(100_000L, 500_000L, 1_000_000L, 5_000_000L, 10_000_000L, Long.MAX_VALUE)
                val body = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(0xFF303030.toInt())
                }
                body.addView(TextView(this).apply {
                    text = "超过上限的文件将被跳过，避免大文件拖慢搜索"
                    setTextColor(0xFFb0b0b0.toInt()); textSize = 13f
                    setPadding(24, 16, 24, 12)
                })
                val radioGroup = RadioGroup(this).apply { setPadding(24, 0, 24, 0) }
                labels.forEachIndexed { i, label ->
                    val rb = RadioButton(this).apply {
                        text = label; id = i
                        setTextColor(0xFFe0f9ff.toInt()); textSize = 15f
                        setPadding(16, 10, 16, 10)
                        if (i == 2) isChecked = true
                    }
                    radioGroup.addView(rb, LinearLayout.LayoutParams(MATCH, WRAP))
                }
                body.addView(radioGroup)
                body.addView(View(this).apply {
                    setBackgroundColor(0xFF555555.toInt())
                    layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { setMargins(24, 8, 24, 8) }
                })
                val customRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(40, 8, 24, 16)
                }
                customRow.addView(TextView(this).apply {
                    text = "自定义 (MB):"; setTextColor(0xFFb0b0b0.toInt()); textSize = 13f
                    gravity = Gravity.CENTER_VERTICAL
                })
                val customInput = EditText(this).apply {
                    hint = "如 2"; setTextColor(0xFFe0f9ff.toInt()); setHintTextColor(0xFF707070.toInt())
                    setBackgroundColor(0xFF1a1a1a.toInt()); setPadding(8, 4, 8, 4); textSize = 14f; gravity = Gravity.CENTER
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    layoutParams = LinearLayout.LayoutParams(120, WRAP).apply { setMargins(12, 0, 0, 0) }
                }
                customRow.addView(customInput)
                body.addView(customRow)
                AlertDialog.Builder(this)
                    .setTitle("是否跳过超过 XX 的文件？")
                    .setView(body)
                    .setPositiveButton("开始搜索") { _, _ ->
                        val custom = customInput.text.toString().toDoubleOrNull()
                        val bytes = if (custom != null && custom > 0) (custom * 1_000_000).toLong()
                                   else limits[radioGroup.checkedRadioButtonId.coerceIn(0, limits.size - 1)]
                        doSearch(queryText, 1, bytes)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                doSearch(queryText, 0, Long.MAX_VALUE)
            }
        }

        btnContinue.setOnClickListener {
            if (queryText.isEmpty()) return@setOnClickListener
            doSearch(queryText, searchMode, currentMaxFileSize, true)
        }
    }

    private fun previewClickedFile(r: SearchResult, highlightQuery: String = "") {
        if (r.snippet.isNotEmpty()) {
            showTextPreview(r.file, r.lineNumber, highlightQuery)
        } else {
            val ext = r.file.name.lowercase().substringAfterLast('.')
            if (ext in PREVIEW_EXTS || ext in TEXT_SEARCH_EXTS) {
                previewLocalFile(r.file)
            } else {
                val parent = r.file.parentFile
                if (parent != null) { nav(parent); toast(r.file.name) }
                else toast(r.file.path)
            }
        }
    }

    private fun walkSearch(
        query: String, dir: File, mode: Int, results: MutableList<SearchResult>, limit: Int,
        maxFileSize: Long, scanned: IntArray, seenFiles: MutableSet<String>
    ) {
        if (results.size >= limit || Thread.interrupted()) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (results.size >= limit || Thread.interrupted()) return
            try {
                if (child.isFile) {
                    scanned[0]++
                    val absPath = child.absolutePath
                    if (seenFiles.contains(absPath)) continue
                    if (mode == 0) {
                        // Filename search
                        if (child.name.lowercase().contains(query)) {
                            seenFiles.add(absPath)
                            results.add(SearchResult(child))
                        }
                    } else {
                        // Content search: scan whole file, count matches, group per file
                        val ext = child.extension.lowercase()
                        if (ext in TEXT_SEARCH_EXTS && child.length() < maxFileSize) {
                            var matchCount = 0
                            var firstSnippet = ""
                            var firstLine = 0
                            var lineNum = 0
                            try {
                                child.bufferedReader(charset = Charsets.UTF_8).use { reader ->
                                    reader.forEachLine { line ->
                                        if (Thread.interrupted()) return@forEachLine
                                        lineNum++
                                        if (line.lowercase().contains(query)) {
                                            matchCount++
                                            if (firstLine == 0) {
                                                firstLine = lineNum
                                                firstSnippet = line.trim().take(120)
                                            }
                                        }
                                    }
                                }
                            } catch (_: Exception) { }
                            if (matchCount > 0) {
                                seenFiles.add(absPath)
                                results.add(SearchResult(child, firstSnippet, firstLine, matchCount))
                            }
                        }
                    }
                } else if (child.isDirectory) {
                    walkSearch(query, child, mode, results, limit, maxFileSize, scanned, seenFiles)
                }
            } catch (_: Exception) { }
        }
    }

    data class ArchiveEntry(
        val path: String,
        val name: String,
        val size: Long,
        val isDirectory: Boolean,
        val isEncrypted: Boolean,
        val depth: Int
    )

    companion object {
        val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        var searchSourceArchive: File? = null
        var searchSourceFormat: String? = null
        var searchSourceCacheBase: File? = null
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

    inner class PreviewAdapter(
        private val entries: List<ArchiveEntry>,
        private val selectedPaths: MutableSet<String>,
        private val expandedPaths: MutableSet<String>,
        private val onFileClick: (ArchiveEntry) -> Unit = {},
        private val onSelectionChanged: () -> Unit = {}
    ) : BaseAdapter() {

        var searchQuery: String = ""
            set(value) {
                field = value; rebuildVisible(); notifyDataSetChanged()
            }
        // Cache: visible entries
        private var visible: List<ArchiveEntry> = entries
            .filter { e -> isVisible(e) }

        private fun isVisible(e: ArchiveEntry): Boolean {
            // If searching by filename, show all entries matching query
            if (searchQuery.isNotEmpty()) {
                return e.path.lowercase().contains(searchQuery.lowercase()) ||
                       e.name.lowercase().contains(searchQuery.lowercase())
            }
            // Normal: entry is visible if all its ancestor directories are expanded
            val parts = e.path.split('/')
            for (i in 1 until parts.size) {
                val dirPath = parts.take(i).joinToString("/")
                if (dirPath.isNotEmpty() && !expandedPaths.contains(dirPath)) {
                    return false
                }
            }
            return true
        }

        private fun rebuildVisible() {
            visible = entries.filter { e -> isVisible(e) }
        }

        override fun getCount(): Int {
            rebuildVisible()
            return visible.size
        }
        override fun getItem(pos: Int) = visible.getOrNull(pos)
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, v: View?, p: ViewGroup?): View {
            val view = v ?: layoutInflater.inflate(R.layout.item_preview, p, false)
            val entry = visible[pos]
            val checkbox = view.findViewById<CheckBox>(R.id.checkbox)
            val icon = view.findViewById<ImageView>(R.id.icon)
            val label = view.findViewById<TextView>(R.id.label)
            val size = view.findViewById<TextView>(R.id.info_size)

            // Indentation based on depth (cap at ~10 levels)
            val density = resources.displayMetrics.density
            val indentPx = (minOf(entry.depth, 10) * 24 * density).toInt()
            val baseStart = (4 * density).toInt()
            view.setPadding(baseStart + indentPx, 0, (8 * density).toInt(), 0)

            // CheckBox state
            checkbox.setOnCheckedChangeListener(null)
            if (entry.isDirectory) {
                // Directory: checkbox selects/deselects all children
                checkbox.isClickable = true
                checkbox.isFocusable = true
                checkbox.isChecked = selectedPaths.contains(entry.path)
                checkbox.setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedPaths.add(entry.path)
                    else selectedPaths.remove(entry.path)
                    val prefix = "${entry.path}/"
                    for (e in entries) {
                        if (e.path.startsWith(prefix)) {
                            if (checked) selectedPaths.add(e.path) else selectedPaths.remove(e.path)
                        }
                    }
                    onSelectionChanged(); notifyDataSetChanged()
                }
            } else {
                checkbox.isClickable = true
                checkbox.isFocusable = true
                checkbox.isChecked = selectedPaths.contains(entry.path)
                checkbox.setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedPaths.add(entry.path) else selectedPaths.remove(entry.path)
                    onSelectionChanged(); notifyDataSetChanged()
                }
            }

            // Icon + Label click targets
            if (entry.isDirectory) {
                icon.setImageResource(android.R.drawable.ic_menu_compass)
                icon.setColorFilter(0xFFffa726.toInt())
                // Tapping icon or arrow toggles expand/collapse
                val toggle = View.OnClickListener {
                    if (expandedPaths.contains(entry.path)) expandedPaths.remove(entry.path)
                    else expandedPaths.add(entry.path)
                    notifyDataSetChanged()
                }
                icon.setOnClickListener(toggle)
                label.setOnClickListener(toggle)
                val arrow = if (expandedPaths.contains(entry.path)) "▼ " else "▶ "
                label.text = "$arrow${entry.name}"
            } else {
                val ext = entry.path.lowercase()
                val res = when {
                    ext.endsWith(".xp3") || ext.endsWith(".pfs") -> android.R.drawable.ic_menu_compass
                    else -> android.R.drawable.ic_menu_gallery
                }
                icon.setImageResource(res)
                icon.setColorFilter(0xFFe0f9ff.toInt())
                label.text = entry.name
                // Tap file icon or label to preview
                val click = View.OnClickListener { onFileClick(entry) }
                icon.setOnClickListener(click)
                label.setOnClickListener(click)
            }

            // Size
            size.text = if (entry.isDirectory) "" else fmt(entry.size)
            if (entry.isEncrypted) {
                size.text = "🔒 ${size.text}"
            }

            return view
        }
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

            val starBtn = view.findViewById<ImageView>(R.id.btnStar)
            if (f.isDirectory) {
                // ⭐ Star button for quick bookmark
                starBtn.visibility = View.VISIBLE
                val bm = bookmarks.contains(f.absolutePath)
                starBtn.setImageResource(if (bm) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off)
                starBtn.setColorFilter(if (bm) 0xFFffc107.toInt() else 0xFF666666.toInt())
                starBtn.setOnClickListener {
                    if (bookmarks.contains(f.absolutePath)) bookmarks.remove(f.absolutePath)
                    else bookmarks.add(0, f.absolutePath)
                    saveBookmarks(); notifyDataSetChanged()
                }
                icon.setImageResource(android.R.drawable.ic_menu_compass); icon.setColorFilter(0xFFffa726.toInt())
                label.text = f.name; size.text = ""; date.text = ""
            } else {
                starBtn.visibility = View.GONE
                val n = f.name.lowercase()
                val res = when { n.endsWith(".xp3")||n.endsWith(".pfs") -> android.R.drawable.ic_menu_compass; n.endsWith(".apk") -> android.R.drawable.ic_menu_manage; else -> android.R.drawable.ic_menu_gallery }
                icon.setImageResource(res); icon.setColorFilter(0xFFe0f9ff.toInt())
                label.text = f.name; size.text = fmt(fileSize(f)); date.text = df.format(Date(f.lastModified()))
            }
            return view
        }
    }
}
