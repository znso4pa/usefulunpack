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

private val C = mapOf(
    "accent" to 0xFF35acc6.toInt(),
    "primary" to 0xFFe0f9ff.toInt(),
    "secondary" to 0xFFb0b0b0.toInt(),
    "tertiary" to 0xFF888888.toInt(),
    "tertiary_light" to 0xFFaaaaaa.toInt(),
    "hint" to 0xFF8f8f8f.toInt(),
    "surface" to 0xFF303030.toInt(),
    "surface_dim" to 0xFF252525.toInt(),
    "surface_dark" to 0xFF1a1a1a.toInt(),
    "nav_bg" to 0xFF222222.toInt(),
    "divider" to 0xFF1a1a1a.toInt(),
    "divider_subtle" to 0xFF555555.toInt(),
    "toggle_on" to 0xFF3a3a3a.toInt(),
    "warning" to 0xFFffa726.toInt(),
    "error" to 0xFFff5252.toInt(),
    "success" to 0xFF69f0ae.toInt(),
    "search_hilite_sel" to 0x88FFAA00.toInt(),
    "search_hilite_oth" to 0x33FFAA00.toInt(),
)

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
    private lateinit var btnFolderNext: Button
    private lateinit var listBookmarks: ListView

    private var currentDir = Environment.getExternalStorageDirectory()
    private var selectedFile: File? = null
    private var fileToMove: File? = null
    private var MultiFiles = listOf<File>()
    private var multiSelectMode = false
    private val multiSelected = mutableSetOf<File>()
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

        // Force dark theme permanently
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        )
        // Apply saved language
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
            androidx.core.os.LocaleListCompat.forLanguageTags(
                prefs.getString("app_lang", "zh-CN") ?: "zh-CN"
            )
        )

        drawer = findViewById(R.id.drawer)

        // Init background image picker
        bgImageLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.GetContent()
        ) { uri ->
            if (uri != null) {
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                prefs.edit().putString("bg_image_uri", uri.toString()).apply()
                applyBackgroundImage(uri)
            }
        }
        // Restore saved background
        prefs.getString("bg_image_uri", null)?.let { uriStr ->
            try { applyBackgroundImage(Uri.parse(uriStr)) } catch (_: Exception) {}
        }
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
        // Initial btnCLI setup (dropdown arrow)
        updatePasteButton()
        // Bottom-left circular "add folder" button
        val btnAddFolder = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_input_add)
            setColorFilter(0xFF000000.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setSize(64, 64); setColor(C["accent"]!!)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(12, 12, 12, 12)
            layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(64, 64).apply {
                bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                setMargins(24, 0, 0, 72)
            }
            setOnClickListener {
                val inp = EditText(this@MainActivity).apply {
                    setText("新建文件夹")
                    selectAll()
                    setTextColor(C["primary"]!!); setHintTextColor(C["hint"]!!)
                    setBackgroundColor(C["surface"]!!); setPadding(12, 8, 12, 8)
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.title_new_folder))
                    .setView(inp)
                    .setPositiveButton(getString(R.string.action_new_folder)) { _, _ ->
                        val name = inp.text.toString().trim()
                        if (name.isEmpty()) { toast(getString(R.string.prompt_new_folder_name)); return@setPositiveButton }
                        val dir = File(currentDir, name)
                        if (dir.exists()) { toast(getString(R.string.msg_file_exists)); return@setPositiveButton }
                        if (dir.mkdir()) { toast(getString(R.string.msg_created)); nav(currentDir) }
                        else toast(getString(R.string.title_compress_failed))
                    }
                    .setNegativeButton(getString(R.string.action_cancel), null)
                    .show()
            }
        }
        findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.root)?.addView(btnAddFolder)
        btnExtract.setOnClickListener { extract() }
        fabExtract.setOnClickListener { extract() }
        findViewById<TextView>(R.id.btnAddBookmark).setOnClickListener {
            if (bookmarks.contains(currentDir.absolutePath).not()) {
                bookmarks.add(0, currentDir.absolutePath); saveBookmarks()
            }
            drawer.close()
        }
        // Add folder-nav button to bottom bar (for compression mode)
        btnFolderNext = Button(this).apply {
            text = "→"
            textSize = 14f
            visibility = View.GONE
            setPadding(8, 0, 8, 0)
        }
        bottomBar.addView(btnFolderNext, LinearLayout.LayoutParams(WRAP, WRAP))
        btnFolderNext.setOnClickListener { selectedFile?.let { if (it.isDirectory) nav(it) } }

        listFiles.onItemClickListener = OnItemClickListener { _, _, pos, _ ->
            val f = listFiles.adapter.getItem(pos) as File
            if (multiSelectMode) { toggleMultiSelect(f); return@OnItemClickListener }
            if (f.isDirectory) {
                val isCompressMode = prefs.getInt("work_mode", 0) == 1
                if (isCompressMode) {
                    selectedFile = f
                    tvSelected.text = "📁 ${f.name}"
                    bottomBar.visibility = View.VISIBLE
                    fabExtract.visibility = View.GONE
                    btnExtract.text = getString(R.string.msg_compress_title)
                    btnExtract.setOnClickListener { showCompressFormatPicker(f) }
                    btnFolderNext.visibility = View.VISIBLE
                    progress.visibility = View.GONE
                } else {
                    nav(f)
                }
                return@OnItemClickListener
            }
            if (tryTap()) select(f)
        }
        listFiles.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, pos, _ ->
            val f = listFiles.adapter.getItem(pos) as File
            AlertDialog.Builder(this)
                .setTitle(f.name)
                .setItems(arrayOf(getString(R.string.action_copy_path), getString(R.string.action_move), getString(R.string.action_rename), getString(R.string.action_delete), getString(R.string.action_select), getString(R.string.action_file_info))) { _, w ->
                    when (w) {
                        0 -> { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("p", f.path)); toast(getString(R.string.msg_copied)) }
                        1 -> { fileToMove = f; updatePasteButton(); toast(getString(R.string.msg_selected_nav, f.name)) }
                        2 -> { showRenameDialog(f) }
                        3 -> {
                            val type = if (f.isDirectory) getString(R.string.folder) else getString(R.string.files)
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle(getString(R.string.title_delete))
                                                                .setMessage(getString(R.string.confirm_delete_file_msg, f.name))
                                .setPositiveButton(getString(R.string.action_delete)) { _, _ ->
                                    if (f.isDirectory) f.deleteRecursively() else f.delete()
                                    toast(getString(R.string.msg_deleted)); nav(currentDir)
                                }
                                .setNegativeButton(getString(R.string.action_cancel), null).show()
                        }
                        4 -> { enterMultiSelect(f) }
                        5 -> {
                            if (f.isDirectory) {
                                val fileCount = f.listFiles()?.size ?: 0
                                val eta = fileCount / 200
                                AlertDialog.Builder(this)
                                    .setTitle(getString(R.string.action_file_info))
                                                                        .setMessage(getString(R.string.msg_calc_dir_size_prompt, f.name, fileCount.toString(), eta.toString(), (eta + 3).toString()))
                                    .setPositiveButton("计算") { _, _ -> calcDirSize(f) }
                                    .setNegativeButton(getString(R.string.action_cancel), null).show()
                            } else {
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle(getString(R.string.action_file_info))
                                    .setMessage("${f.name}\n${fmt(fileSize(f))}\n${df.format(Date(f.lastModified()))}")
                                    .setPositiveButton(getString(R.string.action_confirm), null).show()
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

        // Batch action bar for multi-select
        val batchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C["surface_dim"]!!); visibility = View.GONE
            setPadding(12, 6, 12, 6)
            layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(MATCH, WRAP).apply {
                bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
        val tvBatchCount = TextView(this).apply { setTextColor(C["primary"]!!); textSize = 12f }
        fun b(text: String, color: Int) = Button(this).apply { this.text = text; setTextColor(color); background = null; textSize = 12f; isAllCaps = false; setPadding(4, 0, 4, 0) }
        val btnBatchExtract = b(getString(R.string.batch_extract), C["accent"]!!).apply { setOnClickListener { startBatchExtract() } }
        val btnBatchCompress = b(getString(R.string.batch_compress), C["accent"]!!).apply { setOnClickListener { startBatchCompress() } }
        val btnBatchMove = b(getString(R.string.action_move), C["accent"]!!).apply { setOnClickListener { startBatchMove() } }
        val btnBatchDelete = b(getString(R.string.action_delete), C["error"]!!).apply { setOnClickListener { confirmBatchDelete() } }
        val btnBatchCancel = b("✕ " + getString(R.string.action_cancel), C["tertiary"]!!).apply { setOnClickListener { exitMultiSelect() } }
        batchBar.addView(tvBatchCount, LinearLayout.LayoutParams(0, WRAP, 1f))
        batchBar.addView(btnBatchExtract)
        batchBar.addView(btnBatchCompress)
        batchBar.addView(btnBatchMove)
        batchBar.addView(btnBatchDelete)
        batchBar.addView(btnBatchCancel)
        findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.root)?.addView(batchBar)

        loadBookmarks(); nav(currentDir)
        showDisclaimer()
    }

    private fun updatePasteButton() {
        findViewById<TextView>(R.id.btnCLI)?.let { cli ->
            val hasFile = fileToMove != null
            cli.text = if (hasFile) "📋" else "▾"
            cli.textSize = if (hasFile) 16f else 22f
            cli.setOnClickListener { v ->
                if (hasFile) {
                    if (MultiFiles.size > 1) {
                        var ok = 0; var fail = 0
                        for (src in MultiFiles) {
                            val dst = File(currentDir, src.name)
                            if (dst.exists()) { fail++; continue }
                            if (src.renameTo(dst)) ok++ else fail++
                        }
                                                toast(getString(R.string.msg_move_result, ok.toString(), fail.toString())); fileToMove = null; MultiFiles = listOf(); updatePasteButton(); nav(currentDir)
                    } else {
                        val src = fileToMove ?: return@setOnClickListener
                        val dst = File(currentDir, src.name)
                        if (dst.exists()) { toast(getString(R.string.msg_target_exists)); return@setOnClickListener }
                        if (src.renameTo(dst)) { toast(getString(R.string.msg_moved_to, dst.path)); fileToMove = null; MultiFiles = listOf(); updatePasteButton(); nav(currentDir) }
                        else toast(getString(R.string.msg_move_failed))
                    }
                } else {
                    val popup = PopupMenu(this@MainActivity, v)
                    popup.menu.add(0, 0, 0, getString(R.string.action_terminal))
                    popup.menu.add(0, 1, 1, getString(R.string.msg_search_global))
                    popup.menu.add(0, 2, 2, getString(R.string.nav_bookmarks))
                    popup.menu.add(0, 3, 3, getString(R.string.settings))
                    popup.setOnMenuItemClickListener { item ->
                        when (item.itemId) { 0 -> cli(); 1 -> globalSearch(); 2 -> drawer.open(); 3 -> settings() }
                        true
                    }
                    popup.show()
                }
            }
        }
        // Toggle cancel button next to 📋 in toolbar
        val toolbar = findViewById<LinearLayout>(R.id.toolbar)
        val oldCancel = toolbar?.findViewWithTag<TextView>("cancel_move")
        if (fileToMove != null) {
            if (oldCancel == null) {
                val btnCancel = TextView(this@MainActivity).apply {
                    text = "✕"
                    textSize = 16f; setTextColor(C["error"]!!)
                    gravity = Gravity.CENTER; setPadding(4, 0, 8, 0)
                    tag = "cancel_move"
                    setOnClickListener { fileToMove = null; MultiFiles = listOf(); updatePasteButton(); toast(getString(R.string.msg_cancelled)) }
                }
                toolbar?.addView(btnCancel)
            }
        } else {
            oldCancel?.let { toolbar?.removeView(it) }
        }
    }

    private fun enterMultiSelect(f: File) {
        multiSelectMode = true; multiSelected.add(f); refreshMultiSelectUI()
        val compressMode = prefs.getInt("work_mode", 0) == 1
        syncMultiBar()
    }
    private fun toggleMultiSelect(f: File) { if (multiSelected.contains(f)) multiSelected.remove(f) else multiSelected.add(f); refreshMultiSelectUI() }
    private fun exitMultiSelect() { multiSelectMode = false; multiSelected.clear(); syncMultiBar() }
    private fun syncMultiBar() {
        val bar = (findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.root)).let { r ->
            for (i in 0 until r.childCount) { val c = r.getChildAt(i); if (c is LinearLayout && c.childCount >= 6) return@let c as LinearLayout }
            return
        }
        val tv = bar.getChildAt(0) as? TextView ?: return
        val isCompress = prefs.getInt("work_mode", 0) == 1
        tv.text = "已选 ${multiSelected.size} 项"
        bar.visibility = if (multiSelectMode) View.VISIBLE else View.GONE
        if (multiSelectMode) {
            bar.post { listFiles.setPadding(listFiles.paddingLeft, listFiles.paddingTop, listFiles.paddingRight, bar.height) }
        } else {
            listFiles.setPadding(listFiles.paddingLeft, listFiles.paddingTop, listFiles.paddingRight, 0)
        }
        if (bottomBar != null) bottomBar.visibility = if (multiSelectMode) View.GONE else bottomBar.visibility
        // Sync adapter selection state and force full redraw
        (listFiles.adapter as? FileAdapter)?.multiSelected_ = if (multiSelectMode) multiSelected else emptySet()
        listFiles.invalidateViews()
        // Show/hide extract/compress based on mode (use startsWith for emoji safety)
        for (i in 0 until bar.childCount) {
            val btn = bar.getChildAt(i) as? Button ?: continue
            val t = btn.text.toString()
            if (t.startsWith("📂")) btn.visibility = if (isCompress) View.GONE else View.VISIBLE
            if (t.startsWith("📦")) btn.visibility = if (isCompress) View.VISIBLE else View.GONE
        }
    }
    private fun refreshMultiSelectUI() { syncMultiBar() }
    private fun confirmBatchDelete() {
        val sel = multiSelected.toList(); if (sel.isEmpty()) return
                AlertDialog.Builder(this).setTitle(getString(R.string.title_batch_delete)).setMessage(getString(R.string.confirm_delete_batch_msg, sel.size.toString()))
            .setPositiveButton(getString(R.string.action_delete)) { _, _ -> for (f in sel) { if (f.isDirectory) f.deleteRecursively() else f.delete() }; toast(getString(R.string.msg_deleted)); exitMultiSelect(); nav(currentDir) }
            .setNegativeButton(getString(R.string.action_cancel), null).show()
    }
    private fun startBatchMove() {
        val sel = multiSelected.toList(); if (sel.isEmpty()) return
        // Copy all selected files to a temp list for multi-move; use first file as UI indicator
        MultiFiles = sel
        fileToMove = sel[0]; multiSelected.clear()
        updatePasteButton(); exitMultiSelect()
                toast(getString(R.string.msg_selected_nav_multi, sel.size))
    }
    private fun startBatchCompress() {
        val sel = multiSelected.toList(); if (sel.isEmpty()) return
        val items = sel.filter { it.isDirectory || (it.isFile && it.extension.lowercase() !in ARCHIVE_EXTS) }
        if (items.isEmpty()) { toast(getString(R.string.no_compressible)); return }
        val ext = "zip"; val level = prefs.getInt("zip_level", 5)
        val pwEnabled = prefs.getBoolean("compress_password_enabled", false)
        val password = if (pwEnabled) prefs.getString("compress_password", "") ?: "" else ""
        val pd = ProgressDialog(this).apply { setTitle("批量压缩 — $ext"); setProgressStyle(ProgressDialog.STYLE_SPINNER); setCancelable(false); show() }
        thread {
            var ok = true
            for (f in items) {
                val outF = uniqueFile(f.parentFile ?: currentDir, "${f.name}.$ext")
                ZipCore.zipSetEncoding(prefs.getString("zip_encoding", "UTF-8") ?: "UTF-8"); val ok2 = ZipCore.zipCompress("", f.path, outF.path, level.toString(), password)
                if (!ok2) { ok = false; break }
            }
            runOnUiThread { pd.dismiss(); if (ok) toast(getString(R.string.msg_batch_compress_done)) else toast(getString(R.string.title_compress_failed)); exitMultiSelect(); nav(currentDir) }
        }
    }
    private fun batchArchives(): List<File> = multiSelected.filter { it.isFile && it.extension.lowercase() in ARCHIVE_EXTS }
    private fun resolveBatchPath(mergedPath: String): Pair<File, String>? {
        // mergedPath is like "📦 data.xp3/scenario/main.ks". Extract the archive name and original path.
        for ((src, _) in showBatchPreview_all ?: emptyList()) {
            val prefix = "📦 ${src.name}/"
            if (mergedPath.startsWith(prefix)) return src to mergedPath.removePrefix(prefix)
        }
        return null
    }
    private var showBatchPreview_all: List<Pair<File, List<ArchiveEntry>>>? = null

    private fun startBatchExtract() {
        val archives = batchArchives(); if (archives.isEmpty()) { toast(getString(R.string.no_archives)); return }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_select_format))
            .setItems(arrayOf(getString(R.string.format_xp3), getString(R.string.format_pfs), getString(R.string.format_nsa), getString(R.string.format_iso), getString(R.string.format_ypf), getString(R.string.format_zip), getString(R.string.format_7z), getString(R.string.format_rar), getString(R.string.format_lz4))) { _, which ->
                val fmt = arrayOf("xp3", "pfs", "nsa", "iso", "ypf", "zip", "7z", "rar", "lz4")[which]
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("${archives.size} 个归档 — ${fmt.uppercase()}")
                    .setItems(arrayOf(getString(R.string.action_preview), getString(R.string.action_extract))) { _, w ->
                        when (w) {
                            0 -> batchPreview(archives, fmt)
                            1 -> batchDirectExtract(archives, fmt)
                        }
                    }.setNegativeButton(getString(R.string.action_cancel), null).show()
            }.setNegativeButton(getString(R.string.action_cancel), null).show()
    }
    private fun batchDirectExtract(archives: List<File>, fmt: String) {
        val parent = archives[0].parentFile ?: currentDir
        // Pre-compute unique output dirs
        val outDirs = archives.map { src -> uniqueFile(parent, src.nameWithoutExtension) }
        val labels = arrayOf("📁 各单独文件夹（${outDirs.map { it.name }.joinToString(", ")}）", getString(R.string.action_extract))
        AlertDialog.Builder(this).setTitle(getString(R.string.title_extract_to))
            .setItems(labels) { _, w ->
                val pd = ProgressDialog(this).apply { setTitle("批量解压 — $fmt"); setProgressStyle(ProgressDialog.STYLE_SPINNER); setCancelable(false); show() }
                thread {
                    var ok = true
                    for (i in archives.indices) {
                        val out = if (w == 0) outDirs[i] else parent
                        ok = extractByFormat(fmt, archives[i].path, out.path, "")
                        if (!ok) break
                    }
                                        runOnUiThread { pd.dismiss(); if (ok) toast(getString(R.string.msg_batch_done)) else toast(getString(R.string.title_extract_failed)); exitMultiSelect(); nav(currentDir) }
                }
            }.setNegativeButton(getString(R.string.action_cancel), null).show()
    }
    private fun batchPreview(archives: List<File>, fmt: String) {
        val pd = ProgressDialog(this).apply { setTitle(getString(R.string.reading)); setMessage("正在读取 ${archives.size} 个归档..."); setProgressStyle(ProgressDialog.STYLE_SPINNER); setCancelable(false); show() }
        thread {
            val all: MutableList<Pair<File, List<ArchiveEntry>>> = mutableListOf()
            for (src in archives) {
                val json = try { when(fmt) { "xp3"->Xp3Core.xp3ListEntries(src.path); "pfs"->PfsCore.pfsListEntries(src.path); "nsa"->NsaCore.nsaListEntries(src.path); "iso"->IsoCore.isoListEntries(src.path); "ypf"->YpfCore.ypfListEntries(src.path); "zip"->ZipCore.zipListEntries(src.path); "7z"->SevenZCore.szListEntries(src.path); "rar"->RarCore.rarListEntries(src.path); "lz4"->Lz4Core.lz4ListEntries(src.path); else->null } } catch(_:Exception){null}
                if (json != null) all.add(src to parseEntries(json))
            }
            runOnUiThread { pd.dismiss(); if (all.isEmpty()) toast(getString(R.string.msg_cannot_read)); else showBatchPreviewDialog(all, fmt) }
        }
    }
    private fun showBatchPreviewDialog(all: List<Pair<File, List<ArchiveEntry>>>, fmt: String) {
        showBatchPreview_all = all
        val selectedPaths = mutableSetOf<String>()
        // Merge entries: each archive becomes a root-level pseudodir, entries get archive prefix
        val merged = mutableListOf<ArchiveEntry>()
        val arcDirs = mutableSetOf<String>()
        for ((src, entries) in all) {
            val dirPath = "📦 ${src.name}"
            arcDirs.add(dirPath)
            merged.add(ArchiveEntry(dirPath, src.name, 0, true, false, 0))
            for (e in entries) {
                merged.add(ArchiveEntry("$dirPath/${e.path}", e.name, e.size, e.isDirectory, e.isEncrypted, e.depth + 1))
            }
        }
        val expandedPaths = arcDirs.toMutableSet()
        val totalFiles = merged.count { !it.isDirectory }
        val totalSize = merged.filter { !it.isDirectory }.sumOf { it.size }

        // Stats bar
        val tvStats = TextView(this).apply {
            text = "${all.size} 归档 / $totalFiles 文件 / ${fmt(totalSize)}  |  已选 0 项"
            setTextColor(C["tertiary_light"]!!); textSize = 12f
            setPadding(12, 8, 12, 4); setBackgroundColor(C["surface_dim"]!!)
        }
        fun updateStats() {
            val selFiles = selectedPaths.filter { p -> merged.find { e -> e.path == p && !e.isDirectory } != null }
            val selSize = selectedPaths.sumOf { p -> merged.find { e -> e.path == p }?.size ?: 0L }
            tvStats.text = "${all.size} 归档 / $totalFiles 文件 / ${fmt(totalSize)}  |  已选 ${selFiles.size} 项, ${fmt(selSize)}"
        }

        // Preview file click: extract from correct archive then show
        fun batchPreviewClick(entry: ArchiveEntry) {
            if (entry.isDirectory) return
            val (arc, origPath) = resolveBatchPath(entry.path) ?: return
            val cacheDir = File(cacheDir, "batch_preview/${arc.nameWithoutExtension}")
            val pd3 = ProgressDialog(this).apply { setTitle(getString(R.string.msg_extracting)); setMessage(entry.name); setProgressStyle(ProgressDialog.STYLE_SPINNER); setCancelable(false); show() }
            thread {
                val ok = extractByFormat(fmt, arc.path, cacheDir.path, origPath)
                runOnUiThread { pd3.dismiss(); if (ok) previewLocalFile(File(cacheDir, origPath)) else toast(getString(R.string.title_extract_failed)) }
            }
        }
        val adapter = PreviewAdapter(merged, selectedPaths, expandedPaths, { e -> batchPreviewClick(e) }, { updateStats() })

        val listView = ListView(this).apply {
            this.adapter = adapter; setBackgroundColor(C["surface"]!!)
            divider = ColorDrawable(C["surface_dark"]!!); dividerHeight = 1
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; addView(tvStats); addView(listView, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }

        lateinit var dlg: AlertDialog
        // Title bar with search button
        val titleBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(24, 14, 8, 14); setBackgroundColor(C["surface"]!!) }
        titleBar.addView(TextView(this).apply {
            text = "批量预览 ${all.map{it.first.name}.joinToString(", ").take(40)}"; setTextColor(C["primary"]!!); textSize = 17f
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        val btnSearchTitle = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_search); setBackgroundColor(C["surface"]!!); setPadding(8, 4, 8, 4)
            scaleType = ImageView.ScaleType.FIT_XY; layoutParams = LinearLayout.LayoutParams(52, 40)
            setOnClickListener {
                val cacheDir = File(cacheDir, "archive_search/batch_${all.map{it.first.nameWithoutExtension}.joinToString("_").take(50)}")
                cacheDir.deleteRecursively(); cacheDir.mkdirs()
                val pd2 = ProgressDialog(this@MainActivity).apply { setTitle(getString(R.string.preparing_search)); setMessage(getString(R.string.extracting_texts)); setProgressStyle(ProgressDialog.STYLE_SPINNER); setCancelable(false); show() }
                thread {
                    searchSourceArchive = all[0].first; searchSourceFormat = fmt; searchSourceCacheBase = cacheDir
                    val textExts = TEXT_SEARCH_EXTS
                    for ((src, _) in all) {
                        for (e in merged.filter{!it.isDirectory&&it.path.startsWith("📦 ${src.name}/")}) {
                            if (e.name.substringAfterLast('.').lowercase() !in textExts) continue
                            val rp = resolveBatchPath(e.path)?.second ?: continue
                            extractByFormat(fmt, src.path, cacheDir.path, rp)
                        }
                    }
                    runOnUiThread { pd2.dismiss(); dlg.dismiss(); globalSearch(cacheDir, tempDir = cacheDir) }
                }
            }
        }
        titleBar.addView(btnSearchTitle)

        dlg = AlertDialog.Builder(this).setCustomTitle(titleBar).setView(layout)
            .setPositiveButton(getString(R.string.extract_selected)) { _, _ ->
                val sel = selectedPaths.filter { p -> !p.endsWith("/") || selectedPaths.none { it.startsWith(p) && it != p } }
                if (sel.isEmpty()) { toast(getString(R.string.msg_select_one)); return@setPositiveButton }
                val byArchive = mutableMapOf<File, MutableList<String>>()
                for (p in sel) {
                    val r = resolveBatchPath(p) ?: continue
                    byArchive.getOrPut(r.first) { mutableListOf() }.add(r.second)
                }
                val pd2 = ProgressDialog(this).apply { setTitle(getString(R.string.title_batch_extract)); setMessage("${sel.size} 项"); setProgressStyle(ProgressDialog.STYLE_SPINNER); setCancelable(false); show() }
                thread {
                    var ok2 = true
                    for ((src, paths) in byArchive) {
                        if (!extractByFormat(fmt, src.path, uniqueFile(src.parentFile!!, src.nameWithoutExtension).path, paths.joinToString("\n"))) { ok2 = false; break }
                    }
                                        runOnUiThread { pd2.dismiss(); if (ok2) toast(getString(R.string.msg_batch_done)) else toast(getString(R.string.title_extract_failed)); exitMultiSelect(); nav(currentDir) }
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null).create()
        dlg.show()
    }
    private fun showDisclaimer() {
        if (prefs.getBoolean("disclaimer_accepted_v2", false)) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_disclaimer))
            .setMessage("""
                UsefulUnpack 是通用的文件归档管理工具。

                本软件：
                · 不包含、不提供、不绕过任何 DRM 或复制保护
                · 所有格式解析基于公开规范或开源实现
                · 不托管任何版权内容或素材

                用户应遵守当地法律法规，仅对您合法拥有的文件使用本工具。
                开发者不对任何非法或不当使用承担责任。

                继续使用即表示您同意以上条款。
            """.trimIndent())
            .setPositiveButton("同意并继续") { _, _ ->
                prefs.edit().putBoolean("disclaimer_accepted_v2", true).apply()
            }
            .setNegativeButton(getString(R.string.action_close)) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun nav(dir: File) {
        selectedFile = null
        bottomBar.visibility = View.GONE
        fabExtract.visibility = View.GONE
        btnExtract.text = getString(R.string.msg_extract_title)
        btnExtract.setOnClickListener { extract() }
        btnFolderNext.visibility = View.GONE
        currentDir = dir
        tvPath.text = dir.absolutePath
        // Update mode indicator in title
        val isCompress = prefs.getInt("work_mode", 0) == 1
        findViewById<TextView>(R.id.tvTitle)?.text = "UsefulUnpack" + if (isCompress) "  [🗜️压缩]" else "  [📦归档]"

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
            tvEmpty.text = if (raw == null) getString(R.string.no_permission) else getString(R.string.empty_folder)
            tvEmpty.visibility = View.VISIBLE
        } else tvEmpty.visibility = View.GONE

        listFiles.adapter = FileAdapter(files)
    }

    private val PREVIEW_EXTS = setOf("jpg", "jpeg", "png", "mp3", "ogg", "mp4",
        "txt", "json", "ini", "ks", "lua", "py", "js", "html", "css", "xml", "cfg", "log")

    private fun select(f: File) {
        val ext = f.name.lowercase().substringAfterLast('.')

        // Archive files → show FAB for extraction (but not in compression mode)
        if (ext in ARCHIVE_EXTS) {
            if (prefs.getInt("work_mode", 0) == 1) {
                toast(getString(R.string.msg_compress_mode_block))
                return
            }
            selectedFile = f
            tvSelected.text = "${f.name}  |  ${fmt(fileSize(f))}"
            fabExtract.visibility = View.VISIBLE
            return
        }

        // Previewable non-archive files → show preview dialog
        if (ext in PREVIEW_EXTS) {
            AlertDialog.Builder(this)
                .setTitle(f.name)
                .setItems(arrayOf(getString(R.string.preview), getString(R.string.action_file_info))) { _, w ->
                    when (w) {
                        0 -> previewLocalFile(f)
                        1 -> toast("${f.name}\n${fmt(fileSize(f))}\n${df.format(Date(f.lastModified()))}")
                    }
                }.setNegativeButton(getString(R.string.action_cancel), null).show()
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

    private val ARCHIVE_EXTS = setOf("xp3", "pfs", "pf6", "pf8", "nsa", "sar", "iso", "ypf", "zip", "7z", "rar", "lz4")
    private val TEXT_SEARCH_EXTS = setOf(
        "txt", "json", "ini", "ks", "lua", "py", "js", "html", "css", "xml", "cfg", "log",
        "rtf", "md", "yaml", "yml", "toml", "conf", "properties", "sh", "java", "kt", "rs",
        "c", "cpp", "h", "hpp", "swift", "rb", "php", "pl", "sql", "tsv",
        "srt", "ass", "lrc", "bat", "cmd", "ps1", "go", "dart", "r", "csv"
    )

    private fun extract() {
        val src = selectedFile ?: return
        val ext = src.name.lowercase().substringAfterLast('.')
        if (ext !in ARCHIVE_EXTS && src.isDirectory.not()) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.title_extract_failed))
                                .setMessage(getString(R.string.err_not_archive))
                .setPositiveButton(getString(R.string.action_confirm), null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_select_format))
            .setItems(arrayOf(getString(R.string.format_xp3), getString(R.string.format_pfs), getString(R.string.format_nsa), getString(R.string.format_iso), getString(R.string.format_ypf), getString(R.string.format_zip), getString(R.string.format_7z), getString(R.string.format_rar), getString(R.string.format_lz4))) { _, which ->
                val format = arrayOf("xp3", "pfs", "nsa", "iso", "ypf", "zip", "7z", "rar", "lz4")[which]
                showExtractOptions(src, format)
            }.setNegativeButton(getString(R.string.action_cancel), null).show()
    }

    private fun uniqueFile(parent: File, name: String): File {
        var f = File(parent, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot >= 0) name.substring(0, dot) else name
        val ext = if (dot >= 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            f = File(parent, "$base ($n)$ext")
            if (!f.exists()) return f
            n++
        }
    }

    private fun showExtractOptions(src: File, format: String) {
        val parent = src.parentFile ?: return
        val outDir = uniqueFile(parent, src.nameWithoutExtension)

        AlertDialog.Builder(this)
            .setTitle("${src.name} (${format.uppercase()})")
            .setItems(arrayOf(getString(R.string.action_preview), getString(R.string.action_extract))) { _, w ->
                when (w) {
                    0 -> previewArchive(src, format)
                    1 -> showDirectExtractDialog(src, format, parent, outDir)
                }
            }.setNegativeButton(getString(R.string.action_cancel), null).show()
    }

    private fun showDirectExtractDialog(src: File, format: String, parent: File, outDir: File) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_extract_to))
            .setItems(arrayOf("📁 ${getString(R.string.title_new_folder)}: ${outDir.name}", getString(R.string.action_extract))) { _, w ->
                val out = if (w == 0) outDir else parent
                extractAll(out, src, format)
            }.setNegativeButton(getString(R.string.action_cancel), null).show()
    }

    private fun extractAll(destFile: File, src: File, format: String) {
        val pd = ProgressDialog(this).apply {
            setTitle(getString(R.string.extracting_please))
            setMessage("${src.name} → ${destFile.name}")
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            show()
        }
        fun doExtract(pwd: String = "") = runCatching {
            when (format) {
                "zip" -> { ZipCore.zipSetEncoding(prefs.getString("zip_encoding", "UTF-8") ?: "UTF-8"); if (pwd.isEmpty()) ZipCore.zipExtract("", src.path, destFile.path) else ZipCore.zipExtractWithPassword("", src.path, destFile.path, pwd) }
                "7z" -> if (pwd.isEmpty()) SevenZCore.szExtract("", src.path, destFile.path) else SevenZCore.szExtractWithPassword("", src.path, destFile.path, pwd)
                "rar" -> if (pwd.isEmpty()) RarCore.rarExtract("", src.path, destFile.path) else RarCore.rarExtractWithPassword("", src.path, destFile.path, pwd)
                else -> extractByFormat(format, src.path, destFile.path, "")
            }
        }.getOrDefault(false)
        thread {
            val ok = doExtract()
            runOnUiThread {
                pd.dismiss()
                if (ok) { toast(getString(R.string.msg_extract_complete) + " ${destFile.name}"); nav(currentDir) }
                else if (format in setOf("zip", "7z", "rar")) {
                    // Show password dialog
                    val inp = EditText(this@MainActivity).apply {
                        hint = getString(R.string.prompt_password)
                        setTextColor(C["primary"]!!); setHintTextColor(C["hint"]!!)
                        setBackgroundColor(C["surface"]!!)
                        setPadding(12, 8, 12, 8)
                        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    }
                    // Cleanup empty output dir so uniqueFile naming doesn't stack
                    if (destFile.isDirectory && destFile.listFiles()?.isEmpty() == true) destFile.delete()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.title_password))
                        .setMessage(getString(R.string.retry))
                        .setView(inp)
                        .setPositiveButton(getString(R.string.retry)) { _, _ ->
                            val pwd = inp.text.toString()
                            val pd2 = ProgressDialog(this@MainActivity).apply {
                                setTitle(getString(R.string.extracting_please)); setMessage("${src.name} → ${destFile.name}")
                                setProgressStyle(ProgressDialog.STYLE_SPINNER); setCancelable(false); show()
                            }
                            thread {
                                val ok2 = doExtract(pwd)
                                runOnUiThread {
                                    pd2.dismiss()
                                    if (ok2) { toast(getString(R.string.msg_extract_complete) + " ${destFile.name}"); nav(currentDir) }
                                                                        else toast(getString(R.string.err_pwd_wrong))
                                }
                            }
                        }
                        .setNegativeButton(getString(R.string.action_cancel)) { _, _ -> if (destFile.isDirectory && destFile.listFiles()?.isEmpty() == true) destFile.delete() }
                        .show()
                } else toast(getString(R.string.title_extract_failed))
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
            "zip" -> { ZipCore.zipSetEncoding(prefs.getString("zip_encoding", "UTF-8") ?: "UTF-8"); if (selected.isEmpty()) ZipCore.zipExtract("", src, out) else ZipCore.zipExtractSelected("", src, out, selected) }
            "7z" -> if (selected.isEmpty()) SevenZCore.szExtract("", src, out)
                     else SevenZCore.szExtractSelected("", src, out, selected)
            "nsa" -> if (selected.isEmpty()) NsaCore.nsaExtract("", src, out)
                     else NsaCore.nsaExtractSelected("", src, out, selected)
            "rar" -> if (selected.isEmpty()) RarCore.rarExtract("", src, out)
                     else RarCore.rarExtractSelected("", src, out, selected)
            "lz4" -> Lz4Core.lz4Extract("", src, out)
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
                return if (ext !in exts) getString(R.string.err_ext_mismatch, ".$ext", format.uppercase())
               else getString(R.string.title_extract_failed)
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
            setTitle(getString(R.string.reading))
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
                "zip" -> { ZipCore.zipSetEncoding(prefs.getString("zip_encoding", "UTF-8") ?: "UTF-8"); ZipCore.zipListEntries(src.absolutePath) }
                "7z" -> SevenZCore.szListEntries(src.absolutePath)
                "rar" -> RarCore.rarListEntries(src.absolutePath)
                "lz4" -> Lz4Core.lz4ListEntries(src.absolutePath)
                else -> null
            } } catch (_: Exception) { null }
            runOnUiThread { pd.dismiss() }
            if (json == null || json == "[]") {
                                val msg = if (format in setOf("zip", "7z", "rar")) getString(R.string.err_cannot_read_maybe_pwd) else getString(R.string.msg_cannot_read)
                runOnUiThread { toast(msg) }
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
            setTextColor(C["tertiary_light"]!!); textSize = 12f
            setPadding(12, 8, 12, 4)
            setBackgroundColor(C["surface_dim"]!!)
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
            setBackgroundColor(C["surface"]!!)
            divider = ColorDrawable(C["surface_dark"]!!)
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
            setBackgroundColor(C["surface"]!!)
        }
        titleBar.addView(TextView(this).apply {
            text = "预览 ${src.name}"
            setTextColor(C["primary"]!!); textSize = 17f
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })

        // Must declare dlg before btnSearchTitle so its lambda can capture it
        lateinit var dlg: AlertDialog
        val btnSearchTitle = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            setBackgroundColor(C["surface"]!!)
            setPadding(8, 4, 8, 4)
            scaleType = ImageView.ScaleType.FIT_XY
            layoutParams = LinearLayout.LayoutParams(52, 40)
            setOnClickListener {
                val cacheDir = File(cacheDir, "archive_search/${src.nameWithoutExtension}")
                cacheDir.deleteRecursively()
                cacheDir.mkdirs()
                val pd = ProgressDialog(this@MainActivity).apply {
                    setTitle(getString(R.string.preparing_search))
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
            .setPositiveButton(getString(R.string.extract_selected), null)
            .setNegativeButton(getString(R.string.action_cancel), null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val sel = selectedPaths.filter { it.endsWith("/").not() || selectedPaths.none { p -> p != it && p.startsWith(it) } }
                if (sel.isEmpty()) {
                    toast(getString(R.string.msg_select_one))
                } else {
                    dlg.dismiss()
                    showOutputDirDialog(src, sel, format)
                }
            }
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(C["accent"]!!)
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(C["tertiary"]!!)
        }
        dlg.show()
    }

    private fun showOutputDirDialog(src: File, selectedPaths: List<String>, format: String) {
        val parent = src.parentFile ?: return
        val outDir = uniqueFile(parent, src.nameWithoutExtension)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_extract_to))
            .setItems(arrayOf("📁 ${getString(R.string.title_new_folder)}: ${outDir.name}", getString(R.string.action_extract))) { _, w ->
                val out = if (w == 0) outDir else parent
                extractSelected(src, out, selectedPaths, format)
            }.setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun extractSelected(src: File, out: File, paths: List<String>, format: String) {
        val selStr = paths.joinToString("\n")
        val pd = ProgressDialog(this).apply {
            setTitle(getString(R.string.extracting_please))
            setMessage("${src.name}\n→ ${out.name}\n已选 ${paths.size} 项")
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            show()
        }
        if (format in setOf("zip", "7z") && selStr.isNotEmpty()) {
            thread {
                val ok = when (format) {
                    "zip" -> ZipCore.zipExtractSelected("", src.path, out.path, selStr)
                    "7z" -> SevenZCore.szExtractSelected("", src.path, out.path, selStr)
                    else -> false
                }
                runOnUiThread {
                    pd.dismiss()
                    if (ok) { toast(getString(R.string.msg_extract_complete) + " ${out.name}"); nav(currentDir) }
                    else toast(getString(R.string.title_extract_failed))
                }
            }
        } else {
            tryExtractWithPassword(format, src.path, out.path, selStr) { ok ->
                pd.dismiss()
                if (ok) { toast(getString(R.string.msg_extract_complete) + " ${out.name}"); nav(currentDir) }
                else toast(getString(R.string.title_extract_failed))
            }
        }
    }

    private fun showPasswordDialog(fmt: String, src: String, out: String, onResult: (Boolean) -> Unit) {
        val inp = EditText(this@MainActivity).apply {
            hint = getString(R.string.prompt_password)
            setTextColor(C["primary"]!!); setHintTextColor(C["hint"]!!)
            setBackgroundColor(C["surface"]!!); setPadding(12, 8, 12, 8)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this@MainActivity)
            .setTitle(getString(R.string.title_password))
            .setView(inp)
            .setPositiveButton(getString(R.string.retry)) { _, _ ->
                val pwd = inp.text.toString()
                thread {
                    val ok = when (fmt) {
                        "zip" -> ZipCore.zipExtractWithPassword("", src, out, pwd)
                        "7z" -> SevenZCore.szExtractWithPassword("", src, out, pwd)
                        "rar" -> RarCore.rarExtractWithPassword("", src, out, pwd)
                        else -> false
                    }
                    runOnUiThread { onResult(ok) }
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun tryExtractWithPassword(fmt: String, src: String, out: String, sel: String, onResult: (Boolean) -> Unit) {
        fun doExtract(pwd: String = "") = when (fmt) {
            "zip" -> if (pwd.isEmpty()) ZipCore.zipExtract("", src, out) else ZipCore.zipExtractWithPassword("", src, out, pwd)
            "7z" -> if (pwd.isEmpty()) SevenZCore.szExtract("", src, out) else SevenZCore.szExtractWithPassword("", src, out, pwd)
            "rar" -> if (pwd.isEmpty()) RarCore.rarExtract("", src, out) else RarCore.rarExtractWithPassword("", src, out, pwd)
            else -> extractByFormat(fmt, src, out, sel)
        }
        thread {
            val ok = if (fmt in setOf("zip", "7z", "rar") && sel.isNotEmpty()) {
                if (fmt == "zip") ZipCore.zipExtractSelected("", src, out, sel)
                else if (fmt == "7z") SevenZCore.szExtractSelected("", src, out, sel)
                else RarCore.rarExtractSelected("", src, out, sel)
            } else doExtract()
            runOnUiThread {
                if (ok) { onResult(true) }
                else if (fmt in setOf("zip", "7z", "rar")) {
                    val inp = EditText(this@MainActivity).apply {
                        hint = getString(R.string.prompt_password)
                        setTextColor(C["primary"]!!); setHintTextColor(C["hint"]!!)
                        setBackgroundColor(C["surface"]!!); setPadding(12, 8, 12, 8)
                        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.title_password))
                        .setView(inp)
                        .setPositiveButton(getString(R.string.retry)) { _, _ ->
                            val pwd = inp.text.toString()
                            thread {
                                val ok2 = when (fmt) {
                                    "zip" -> ZipCore.zipExtractWithPassword("", src, out, pwd)
                                    "7z" -> SevenZCore.szExtractWithPassword("", src, out, pwd)
                                    "rar" -> if (sel.isNotEmpty()) RarCore.rarExtractSelectedWithPassword("", src, out, sel, pwd)
                                             else RarCore.rarExtractWithPassword("", src, out, pwd)
                                    else -> false
                                }
                                runOnUiThread { onResult(ok2) }
                            }
                        }
                        .setNegativeButton(getString(R.string.action_cancel), null)
                        .show()
                } else { onResult(false) }
            }
        }
    }

    private fun previewFileEntry(archive: File, entry: ArchiveEntry, format: String) {
        val ext = entry.path.substringAfterLast('.').lowercase()
        val TEXT_EXTS = setOf("txt", "json", "ini", "ks", "lua", "py", "js", "html", "css", "xml", "cfg", "log")
        if (ext !in setOf("jpg", "jpeg", "png", "mp3", "ogg", "mp4") && ext !in TEXT_EXTS) {
                        toast(getString(R.string.err_preview_unsupported, ".$ext"))
            return
        }

        val cacheDir = File(cacheDir, "preview/${archive.nameWithoutExtension}")
        fun openPreview() {
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
        if (format in setOf("zip", "7z", "rar")) {
            // Detect if archive needs password first
            val needsPw = when (format) {
                "zip" -> ZipCore.zipNeedsPassword(archive.path)
                "7z" -> SevenZCore.szNeedsPassword(archive.path)
                "rar" -> RarCore.rarNeedsPassword(archive.path)
                else -> false
            }
            if (needsPw) {
                showPasswordDialog(format, archive.path, cacheDir.path) { ok2 ->
                    if (ok2) openPreview() else toast(getString(R.string.title_extract_failed))
                }
            } else {
                tryExtractWithPassword(format, archive.path, cacheDir.path, entry.path) { ok ->
                    if (ok) openPreview() else toast(getString(R.string.title_extract_failed))
                }
            }
        } else {
            tryExtractWithPassword(format, archive.path, cacheDir.path, entry.path) { ok ->
                if (ok) openPreview() else toast(getString(R.string.title_extract_failed))
            }
        }
    }

    private fun showImagePreview(file: File) {
        val bmp = BitmapFactory.decodeFile(file.path)
        if (bmp == null) { toast(getString(R.string.msg_cannot_decode)); return }

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
            .setPositiveButton(getString(R.string.action_close), null)
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
                val color = if (i == selectedIdx) C["search_hilite_sel"]!! else C["search_hilite_oth"]!!
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
            setTextColor(C["primary"]!!)
            textSize = 12f
            setBackgroundColor(C["surface_dark"]!!)
            setPadding(16, 16, 16, 16)
            isVerticalScrollBarEnabled = true
            movementMethod = android.text.method.ScrollingMovementMethod()
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroll = ScrollView(this@MainActivity).apply {
            addView(tv)
            setBackgroundColor(C["surface_dark"]!!)
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
                setBackgroundColor(C["nav_bg"]!!)
                setPadding(0, 6, 0, 6)
            }
            val btnPrev = Button(this@MainActivity).apply {
                text = getString(R.string.search_prev); textSize = 12f; isAllCaps = false
                setTextColor(C["accent"]!!); background = null
                setPadding(12, 4, 12, 4)
            }
            val tvCounter = TextView(this@MainActivity).apply {
                gravity = Gravity.CENTER; textSize = 12f
                setTextColor(C["tertiary_light"]!!)
                setPadding(20, 4, 20, 4)
            }
            val btnNext = Button(this@MainActivity).apply {
                text = getString(R.string.search_next); textSize = 12f; isAllCaps = false
                setTextColor(C["accent"]!!); background = null
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
            .setPositiveButton(getString(R.string.action_close), null)
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
                .setPositiveButton(getString(R.string.action_stop)) { _, _ -> mp.release() }
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

    private fun showCompressFormatPicker(dir: File) {
        AlertDialog.Builder(this)
            .setTitle("${getString(R.string.msg_compress_title)} ${dir.name}")
            .setItems(arrayOf(getString(R.string.format_zip), getString(R.string.format_7z), "📁 XP3", "📁 PFS")) { _, which ->
                val fmt = arrayOf("zip", "7z", "xp3", "pfs")[which]
                val ext = if (fmt == "7z") "7z" else fmt
                val outFile = uniqueFile(dir.parentFile ?: currentDir, "${dir.name}.$ext")
                val level = if (fmt == "zip") prefs.getInt("zip_level", 5) else prefs.getInt("sz_level", 6)
                val pwEnabled = prefs.getBoolean("compress_password_enabled", false)
                val password = if (pwEnabled) prefs.getString("compress_password", "") ?: "" else ""
                var cancelled = false
                val pd = ProgressDialog(this).apply {
                    setTitle("${getString(R.string.msg_compress_progress)} — $ext")
                    setMessage(getString(R.string.msg_compress_progress))
                    setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
                    max = 100
                    setCancelable(true)
                    setButton(ProgressDialog.BUTTON_NEGATIVE, getString(R.string.action_cancel), android.content.DialogInterface.OnClickListener { _, _ -> cancelled = true; if (fmt == "zip") ZipCore.zipCompressCancel() else SevenZCore.szCompressCancel() })
                    show()
                }
                thread {
                    var ok = false
                    try {
                        ok = when (fmt) {
                            "zip" -> { ZipCore.zipSetEncoding(prefs.getString("zip_encoding", "UTF-8") ?: "UTF-8"); ZipCore.zipCompress("", dir.path, outFile.path, level.toString(), password) }
                            "7z" -> SevenZCore.szCompress("", dir.path, outFile.path, level.toString(), password)
                            else -> false
                        }
                    } catch (_: Exception) { /* cancelled */ }
                    if (cancelled) {
                        var deleted = false; for (i in 0..10) { deleted = outFile.delete(); if (deleted) break else Thread.sleep(200) }
                    }
                    runOnUiThread {
                        pd.dismiss()
                        if (cancelled) { toast(getString(R.string.msg_cancelled)) }
                        else if (ok) { toast("${getString(R.string.msg_extract_complete)} ${outFile.name}"); nav(currentDir) }
                        else toast(getString(R.string.title_compress_failed))
                    }
                }
                // Poll progress from Rust globals
                thread {
                    var last = 0
                    while (!cancelled) {
                        Thread.sleep(200)
                        val cur = if (fmt == "zip") ZipCore.zipCompressProgressCount() else SevenZCore.szCompressProgressCount()
                        val tot = if (fmt == "zip") ZipCore.zipCompressProgressTotal() else SevenZCore.szCompressProgressTotal()
                        val fname = (if (fmt == "zip") ZipCore.zipCompressProgressName() else SevenZCore.szCompressProgressName()) ?: ""
                        if (cur != last) {
                            last = cur
                            val pct = if (tot > 0) (cur * 100 / tot).coerceAtMost(100) else 0
                            runOnUiThread {
                                pd.progress = pct
                                pd.setMessage("正在压缩——${fname.takeLast(40)}")
                            }
                        }
                        if (cancelled || (cur > 0 && tot > 0 && cur >= tot)) break
                    }
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun showCompressionSettings() {
        var mode = prefs.getInt("work_mode", 0)
        var zipLevel = prefs.getInt("zip_level", 5).let { if (it !in intArrayOf(0,3,5,7,9)) 5 else it }
        var szLevel = prefs.getInt("sz_level", 6).let { if (it !in intArrayOf(0,3,6,9,12)) 6 else it }
        var passwordEnabled = prefs.getBoolean("compress_password_enabled", false)
        var password = prefs.getString("compress_password", "") ?: ""
        val ZIP_LEVELS = arrayOf(getString(R.string.level_store), getString(R.string.level_low), getString(R.string.level_medium), getString(R.string.level_high), getString(R.string.level_extreme))
        val ZIP_VALS = intArrayOf(0, 3, 5, 7, 9)
        val SZ_LEVELS = arrayOf(getString(R.string.level_store), getString(R.string.level_low), getString(R.string.level_medium), getString(R.string.level_high), getString(R.string.level_extreme))
        val SZ_VALS = intArrayOf(0, 3, 6, 9, 12)

        fun setMode(m: Int) {
            mode = m
            prefs.edit().putInt("work_mode", m).apply()
            findViewById<TextView>(R.id.tvTitle)?.text = "UsefulUnpack" + if (m == 1) "  [🗜️压缩]" else "  [📦归档]"
            if (m == 0) { bottomBar.visibility = View.GONE; btnExtract.text = getString(R.string.msg_extract_title); btnExtract.setOnClickListener { extract() }; btnFolderNext.visibility = View.GONE; selectedFile = null }
        }
        fun styleBtn(btn: Button, active: Boolean) {
            btn.setBackgroundColor(if (active) C["accent"]!! else C["toggle_on"]!!)
            btn.setTextColor(if (active) 0xFF000000.toInt() else C["tertiary"]!!)
        }

        // ═══ Row 1: mode toggle ═══
        lateinit var btnArchive: Button
        lateinit var btnCompress: Button
        btnArchive = Button(this).apply {
            text = getString(R.string.settings_mode_archive); textSize = 13f; isAllCaps = false
            setPadding(20, 6, 20, 6)
            setOnClickListener { setMode(0); styleBtn(this, true); styleBtn(btnCompress, false) }
        }
        btnCompress = Button(this).apply {
            text = getString(R.string.settings_mode_compress); textSize = 13f; isAllCaps = false
            setPadding(20, 6, 20, 6)
            setOnClickListener { setMode(1); styleBtn(this, true); styleBtn(btnArchive, false) }
        }
        styleBtn(btnArchive, mode == 0); styleBtn(btnCompress, mode == 1)
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 12, 20, 8)
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.action_work_mode); setTextColor(C["tertiary_light"]!!); textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
            })
            addView(btnArchive, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(8, 0, 0, 0) })
            addView(btnCompress, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(8, 0, 0, 0) })
        }

        // ═══ Build a format settings section ═══
        fun buildFormatSection(title: String, levels: Array<String>, vals: IntArray, currentLevel: Int, onChanged: (Int) -> Unit): View {
            var expanded = false
            val radioGroup = RadioGroup(this@MainActivity).apply { visibility = View.GONE }
            levels.forEachIndexed { i, label ->
                val rb = RadioButton(this@MainActivity).apply {
                    text = label; id = i
                    setTextColor(C["primary"]!!); textSize = 14f
                    setPadding(40, 8, 16, 8)
                    if (vals[i] == currentLevel) isChecked = true
                }
                radioGroup.addView(rb, LinearLayout.LayoutParams(MATCH, WRAP))
            }
            radioGroup.setOnCheckedChangeListener { _, checkedId ->
                val idx = checkedId.coerceIn(0, vals.size - 1)
                onChanged(vals[idx])
            }
            fun currentLabel() = levels[vals.indexOfFirst { it == currentLevel }.coerceAtLeast(0)]
            val header = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(24, 10, 24, 10)
                setOnClickListener {
                    expanded = !expanded
                    radioGroup.visibility = if (expanded) View.VISIBLE else View.GONE
                    (getChildAt(0) as? TextView)?.text = if (expanded) "▼ $title" else "▶ $title"
                }
                addView(TextView(this@MainActivity).apply {
                    text = "▶ $title"; setTextColor(C["tertiary_light"]!!); textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                })
                addView(TextView(this@MainActivity).apply {
                    text = currentLabel(); setTextColor(C["accent"]!!); textSize = 13f
                })
            }
            return LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(header)
                addView(radioGroup)
                addView(View(this@MainActivity).apply {
                    setBackgroundColor(C["divider_subtle"]!!)
                    layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { setMargins(24, 0, 24, 0) }
                })
            }
        }

        val zipLevelSection = buildFormatSection("ZIP " + getString(R.string.settings_compress), ZIP_LEVELS, ZIP_VALS, zipLevel) { v -> zipLevel = v }
        val szSection = buildFormatSection("7z " + getString(R.string.settings_compress), SZ_LEVELS, SZ_VALS, szLevel) { v -> szLevel = v }

        // ═══ Compact encoding row (like password toggle) ═══
        val zipEncVals = arrayOf("UTF-8", "SHIFT-JIS", "GBK")
        val encLabels = arrayOf(getString(R.string.encoding_utf8), getString(R.string.encoding_sjis), getString(R.string.encoding_gbk))
        var encIdx = zipEncVals.indexOf(prefs.getString("zip_encoding", "UTF-8") ?: "UTF-8").coerceAtLeast(0)
        val tvEncVal = TextView(this@MainActivity).apply {
            text = encLabels[encIdx]; setTextColor(C["accent"]!!); textSize = 13f
        }
        val encRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(24, 10, 24, 10)
            setOnClickListener {
                val cur = zipEncVals.indexOf(prefs.getString("zip_encoding", "UTF-8") ?: "UTF-8").coerceAtLeast(0)
                val next = (cur + 1) % zipEncVals.size
                prefs.edit().putString("zip_encoding", zipEncVals[next]).apply()
                tvEncVal.text = encLabels[next]; toast("编码: ${zipEncVals[next]}")
            }
            addView(TextView(this@MainActivity).apply { text = "编码"; setTextColor(C["tertiary_light"]!!); textSize = 13f; layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) })
            addView(tvEncVal, LinearLayout.LayoutParams(WRAP, WRAP))
        }

        // ═══ Password section ═══
        val etPassword = EditText(this@MainActivity).apply {
            hint = getString(R.string.password_hint_long)
            setTextColor(C["primary"]!!); setHintTextColor(C["hint"]!!)
            setBackgroundColor(C["surface_dark"]!!)
            setPadding(12, 8, 12, 8); textSize = 13f
            visibility = if (passwordEnabled) View.VISIBLE else View.GONE
            setText(password)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val btnEye = TextView(this@MainActivity).apply {
            text = "👁"
            textSize = 16f
            visibility = if (passwordEnabled) View.VISIBLE else View.GONE
            setTextColor(C["accent"]!!)
            gravity = Gravity.CENTER
            setPadding(8, 0, 0, 0)
            setOnClickListener {
                val isHidden = etPassword.inputType == (android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
                etPassword.inputType = if (isHidden)
                    android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else
                    android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                etPassword.setSelection(etPassword.text.length)
            }
        }
        val btnPwdToggle = Button(this@MainActivity).apply {
            text = if (passwordEnabled) getString(R.string.action_close) else getString(R.string.action_confirm)
            setTextColor(if (passwordEnabled) C["error"]!! else C["accent"]!!)
            background = null; textSize = 13f
            setOnClickListener {
                passwordEnabled = !passwordEnabled
                text = if (passwordEnabled) getString(R.string.action_close) else getString(R.string.action_confirm)
                setTextColor(if (passwordEnabled) C["error"]!! else C["accent"]!!)
                etPassword.visibility = if (passwordEnabled) View.VISIBLE else View.GONE
                btnEye.visibility = if (passwordEnabled) View.VISIBLE else View.GONE
            }
        }
        val pwdRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 12, 24, 4)
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.password_colon)
                setTextColor(C["tertiary_light"]!!); textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            addView(btnPwdToggle, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        val pwdInputRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(40, 4, 24, 12) }
            addView(etPassword, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(btnEye)
        }
        val pwdSection = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(pwdRow)
            addView(pwdInputRow)
            addView(View(this@MainActivity).apply {
                setBackgroundColor(C["divider_subtle"]!!)
                layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { setMargins(24, 0, 24, 0) }
            })
        }

        val body = ScrollView(this@MainActivity).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(row1)
                addView(zipLevelSection)
                addView(szSection)
                // Compact encoding toggle row
                addView(View(this@MainActivity).apply { setBackgroundColor(C["divider_subtle"]!!); layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { setMargins(24, 0, 24, 0) } })
                addView(encRow)
                addView(pwdSection)
            })
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_compress))
            .setView(body)
            .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                prefs.edit().putInt("zip_level", zipLevel).apply()
                prefs.edit().putInt("sz_level", szLevel).apply()
                // zip_encoding already saved on toggle
                prefs.edit().putBoolean("compress_password_enabled", passwordEnabled).apply()
                if (passwordEnabled) prefs.edit().putString("compress_password", etPassword.text.toString()).apply()
                else prefs.edit().remove("compress_password").apply()
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun showGeneralSettings() {
        val langTags = arrayOf("zh-CN", "zh-TW", "ja", "en")
        val labels = arrayOf(getString(R.string.language_zhcn), getString(R.string.language_zhtw), getString(R.string.language_ja), getString(R.string.language_en))
        val current = prefs.getString("app_lang", "zh-CN") ?: "zh-CN"
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_general))
            .setSingleChoiceItems(labels, langTags.indexOf(current).coerceAtLeast(0)) { _, which ->
                val tag = langTags[which]
                prefs.edit().putString("app_lang", tag).apply()
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.forLanguageTags(tag)
                )
                recreate()
            }
            .setPositiveButton(getString(R.string.action_confirm), null)
            .show()
    }

    private fun showRenameDialog(f: File) {
        val inp = EditText(this@MainActivity).apply {
            setText(f.name); selectAll()
            setTextColor(C["primary"]!!); setHintTextColor(C["hint"]!!)
            setBackgroundColor(C["surface"]!!); setPadding(12, 8, 12, 8)
        }
        AlertDialog.Builder(this@MainActivity)
            .setTitle(getString(R.string.action_rename))
            .setView(inp)
            .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                val newName = inp.text.toString().trim()
                if (newName.isEmpty() || newName == f.name) return@setPositiveButton
                val dst = File(f.parentFile ?: return@setPositiveButton, newName)
                if (!dst.exists()) { f.renameTo(dst); toast(getString(R.string.action_rename)); nav(currentDir); return@setPositiveButton }
                // Conflict dialog
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.msg_target_exists))
                    .setItems(arrayOf(getString(R.string.action_replace), getString(R.string.action_keep_both), getString(R.string.action_compare))) { _, which ->
                        when (which) {
                            0 -> { dst.delete(); f.renameTo(dst); toast(getString(R.string.action_replace)); nav(currentDir) }
                            1 -> { val u = uniqueFile(f.parentFile!!, newName); f.renameTo(u); toast("已重命名为 ${u.name}"); nav(currentDir) }
                            2 -> { compareFiles(f, dst) }
                        }
                    }.setNegativeButton(getString(R.string.action_cancel), null).show()
            }
            .setNegativeButton(getString(R.string.action_cancel), null).show()
    }
    private fun compareFiles(a: File, b: File) {
        val info = "${a.name}\n大小: ${fmt(fileSize(a))}\n时间: ${df.format(Date(a.lastModified()))}\n\n${b.name}\n大小: ${fmt(fileSize(b))}\n时间: ${df.format(Date(b.lastModified()))}\n\n"
        val extA = a.name.lowercase().substringAfterLast('.')
        val extB = b.name.lowercase().substringAfterLast('.')
        val previewable = setOf("jpg","jpeg","png","txt","json","ini","ks","lua","py","js","html","css","xml","cfg","log","md")
        val canPreview = extA in previewable && extB in previewable
        AlertDialog.Builder(this@MainActivity)
            .setTitle(getString(R.string.title_compare))
            .setMessage(info + if (canPreview) "两个文件均可预览" else "大小相同: ${fileSize(a) == fileSize(b)}")
            .setPositiveButton(if (canPreview) getString(R.string.preview_both) else "确定") { _, _ ->
                if (canPreview) { previewLocalFile(a); previewLocalFile(b) }
            }
            .setNegativeButton(getString(R.string.action_cancel), null).show()
    }

    private fun settings() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings))
            .setItems(arrayOf(getString(R.string.settings_ui), getString(R.string.settings_general), getString(R.string.settings_compress))) { _, which ->
                when (which) {
                    0 -> showUISettings()
                    1 -> showGeneralSettings()
                    2 -> showCompressionSettings()
                }
            }
            .setNegativeButton(getString(R.string.action_close), null)
            .show()
    }

    private var bgImageLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null

    private fun showUISettings() {
        // Background image toggle row
        val hasBg = prefs.getString("bg_image_uri", null) != null
        val tvBgInfo = TextView(this).apply {
            text = if (hasBg) "✅ 已设置自定义背景" else getString(R.string.level_store)
            setTextColor(C["tertiary_light"]!!); textSize = 13f
        }
        val btnPickBg = Button(this).apply {
            text = if (hasBg) "更换" else "选择图片"
            setTextColor(C["accent"]!!); background = null; textSize = 13f
        }
        val btnClearBg = Button(this).apply {
            text = "清除"
            setTextColor(C["error"]!!); background = null; textSize = 13f
            visibility = if (hasBg) View.VISIBLE else View.GONE
        }
        val bgRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 0, 32, 8)
            addView(tvBgInfo, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(btnClearBg)
            addView(btnPickBg, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(8, 0, 0, 0) })
        }

        val curAlpha = prefs.getInt("bg_image_alpha", 20)
        val tvAlpha = TextView(this@MainActivity).apply {
            text = "透明度: ${curAlpha}%"; setTextColor(C["tertiary"]!!); textSize = 12f
            setPadding(40, 4, 32, 0)
        }
        val seekAlpha = SeekBar(this@MainActivity).apply {
            max = 100; progress = curAlpha
            setPadding(40, 0, 40, 8)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { tvAlpha.text = "透明度: $p%" }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        val body = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.settings_bg_image); setTextColor(C["tertiary_light"]!!); textSize = 12f
                setPadding(32, 12, 32, 0)
            })
            addView(bgRow)
            addView(tvAlpha)
            addView(seekAlpha)
        }

        btnPickBg.setOnClickListener { bgImageLauncher?.launch("image/*") }
        btnClearBg.setOnClickListener {
            prefs.edit().remove("bg_image_uri").apply()
            findViewById<View>(R.id.root)?.setBackgroundResource(R.color.bg_surface)
            findViewById<View>(R.id.toolbar)?.setBackgroundResource(R.color.bg_toolbar)
            findViewById<View>(R.id.pathBar)?.setBackgroundResource(R.color.bg_pathbar)
            findViewById<View>(R.id.panel)?.setBackgroundResource(R.color.bg_file_list)
            window?.statusBarColor = 0xBB000000.toInt()
            tvBgInfo.text = getString(R.string.level_store)
            btnClearBg.visibility = View.GONE
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_ui))
            .setView(body)
            .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                prefs.edit().putInt("bg_image_alpha", seekAlpha.progress).apply()
                prefs.getString("bg_image_uri", null)?.let { applyBackgroundImage(Uri.parse(it)) }
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    private fun applyBackgroundImage(uri: Uri) {
        try {
            val bmp = BitmapFactory.decodeStream(contentResolver.openInputStream(uri)) ?: return
            val root = findViewById<View>(R.id.root) ?: return
            val alpha = prefs.getInt("bg_image_alpha", 20).coerceIn(1, 100)
            root.post {
                val rw = root.width; val rh = root.height
                if (rw <= 0 || rh <= 0) return@post
                val bmpW = bmp.width; val bmpH = bmp.height
                val scale = maxOf(rw.toFloat() / bmpW, rh.toFloat() / bmpH)
                val sw = (bmpW * scale).toInt(); val sh = (bmpH * scale).toInt()
                val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, sw, sh, true)
                val x = maxOf((sw - rw) / 2, 0); val y = maxOf((sh - rh) / 2, 0)
                val cw = minOf(rw, sw); val ch = minOf(rh, sh)
                val cropped = android.graphics.Bitmap.createBitmap(scaled, x, y, cw, ch)
                val dr = android.graphics.drawable.BitmapDrawable(resources, cropped)
                dr.alpha = (alpha * 255 / 100).coerceIn(1, 255)
                root.background = dr
            }
            // Make surfaces transparent so the bg shows through everywhere
            findViewById<View>(R.id.toolbar)?.setBackgroundColor(0xBB000000.toInt())
            findViewById<View>(R.id.pathBar)?.setBackgroundColor(0xBB000000.toInt())
            findViewById<View>(R.id.panel)?.background = null
            // Match status bar to toolbar
            window?.statusBarColor = 0xBB000000.toInt()
        } catch (_: Exception) {}
    }

    private fun cli() {
        val inp = EditText(this).apply {
            hint = "cd: $currentDir"
            setTextColor(C["primary"]!!); setHintTextColor(C["hint"]!!)
            setBackgroundColor(C["surface"]!!); textSize = 12f; minLines = 1; maxLines = 1
            setSingleLine(true)
        }
        val out = TextView(this).apply {
            text = "cd: $currentDir"
            setTextColor(C["secondary"]!!); textSize = 11f
            setBackgroundColor(C["nav_bg"]!!); setPadding(12,12,12,12)
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
            runBtn?.setTextColor(C["accent"]!!)
            closeBtn?.setTextColor(C["tertiary"]!!)
            helpBtn?.setTextColor(C["accent"]!!)
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
        val dlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_help))
            .setView(listView)
            .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                if (selectedCmd.isNotEmpty()) { inp.setText(selectedCmd); onApply(selectedCmd) }
            }
            .setNegativeButton(getString(R.string.action_close), null)
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
            setTextColor(C["secondary"]!!); textSize = 11f; setPadding(16, 8, 16, 2)
        }
        val btnChangeDir = Button(this).apply {
            text = getString(R.string.action_move); setTextColor(C["accent"]!!); background = null; textSize = 11f
            setPadding(0, 0, 16, 0)
            setOnClickListener {
                val dirInput = EditText(this@MainActivity).apply {
                    setText(searchDir.path); setTextColor(C["primary"]!!)
                    setHintTextColor(C["hint"]!!); setBackgroundColor(C["surface"]!!)
                    setPadding(12, 8, 12, 8)
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("输入搜索目录").setView(dirInput)
                    .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                        val d = File(dirInput.text.toString().trim())
                        if (d.isDirectory) { searchDir = d; tvDir.text = "📁 搜索范围: ${d.path}" }
                        else toast(getString(R.string.msg_dir_not_found))
                    }.setNegativeButton(getString(R.string.action_cancel), null).show()
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
            val onColor = C["accent"]!!; val offBg = 0xFF2a2a2a.toInt()
            btnFilename.setBackgroundColor(if (mode == 0) onColor else offBg)
            btnFilename.setTextColor(if (mode == 0) 0xFF000000.toInt() else C["tertiary"]!!)
            btnContent.setBackgroundColor(if (mode == 1) onColor else offBg)
            btnContent.setTextColor(if (mode == 1) 0xFF000000.toInt() else C["tertiary"]!!)
        }
        btnFilename = Button(this).apply {
            text = getString(R.string.filename_search); textSize = 13f; isAllCaps = false
            setPadding(24, 6, 24, 6); setOnClickListener { selectMode(0) }
        }
        btnContent = Button(this).apply {
            text = getString(R.string.content_search); textSize = 13f; isAllCaps = false
            setPadding(24, 6, 24, 6); setOnClickListener { selectMode(1) }
        }
        selectMode(0)
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(16, 4, 16, 4)
            addView(btnFilename); addView(btnContent, LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(8, 0, 0, 0) })
        }

        // Search input
        val etQuery = EditText(this).apply {
            hint = getString(R.string.input_search); setTextColor(C["primary"]!!); setHintTextColor(C["hint"]!!)
            setBackgroundColor(C["surface"]!!); textSize = 14f; setPadding(12, 8, 12, 8); setSingleLine(true)
        }

        // Search button
        val btnSearch = Button(this).apply {
            text = getString(R.string.search_button); setTextColor(C["accent"]!!); textSize = 14f
        }

        // Progress bar (indeterminate while searching)
        val searchProgress = ProgressBar(this).apply {
            isIndeterminate = true; visibility = View.GONE
            setPadding(16, 4, 16, 0)
        }

        // Stats + continue button
        val tvStats = TextView(this).apply {
            text = getString(R.string.search_empty); setTextColor(C["tertiary_light"]!!); textSize = 12f
        }
        val btnContinue = Button(this).apply {
            text = getString(R.string.continue_scan); setTextColor(C["accent"]!!); textSize = 12f
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
                    setTextColor(C["primary"]!!); textSize = 14f; setSingleLine(true)
                }
                view.findViewById<TextView>(android.R.id.text2).apply {
                    text = if (r.snippet.isNotEmpty()) "${r.file.parent}\n${r.snippet}"
                           else "${r.file.parent}  •  ${fmt(r.file.length())}"
                    setTextColor(C["tertiary"]!!); textSize = 11f; maxLines = 3
                }
                view.setBackgroundColor(C["surface"]!!)
                return view
            }
        }

        val listResults = ListView(this).apply {
            adapter = resultAdapter; setBackgroundColor(C["surface"]!!)
            divider = ColorDrawable(C["surface_dark"]!!); dividerHeight = 1
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
                        setTitle(getString(R.string.msg_extracting)); setMessage(r.file.name)
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
            .setTitle(getString(R.string.msg_search_global))
            .setView(layout)
            .setNegativeButton(getString(R.string.action_close), null)
            .create()
        searchDialog.show()

        // Launch search
        fun doSearch(query: String, mode: Int, maxFileSize: Long, isContinue: Boolean = false) {
            searchThread?.interrupt()
            if (query.isEmpty()) { toast(getString(R.string.msg_enter_keyword)); return }
            currentMaxFileSize = maxFileSize
            if (!isContinue) { results.clear(); seenFiles.clear(); currentLimit = 200 }
            else currentLimit += 200
            resultAdapter.refresh()
            searchProgress.visibility = View.VISIBLE
            btnContinue.visibility = View.GONE
            tvStats.text = if (isContinue) getString(R.string.continue_scan) else getString(R.string.search_scanning)
            val scanned = intArrayOf(0)
            searchThread = thread {
                walkSearch(query.lowercase(), searchDir, mode, results, currentLimit, maxFileSize, scanned, seenFiles)
                runOnUiThread {
                    searchProgress.visibility = View.GONE
                    val hasMore = results.size >= currentLimit && results.size < 10000
                    btnContinue.visibility = if (hasMore) View.VISIBLE else View.GONE
                    tvStats.text = "找到 ${results.size} 个结果（共扫描 ${scanned[0]} 个文件）"
                    resultAdapter.refresh()
                    if (results.isEmpty()) toast(getString(R.string.msg_no_results))
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
            if (queryText.isEmpty()) { toast(getString(R.string.msg_enter_keyword)); return@setOnClickListener }
            if (searchMode == 1) {
                // Content search: ask for single-file size limit
                val labels = arrayOf("100 KB", "500 KB", "1 MB", "5 MB", "10 MB", getString(R.string.level_extreme))
                val limits = longArrayOf(100_000L, 500_000L, 1_000_000L, 5_000_000L, 10_000_000L, Long.MAX_VALUE)
                val body = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(C["surface"]!!)
                }
                body.addView(TextView(this).apply {
                    text = getString(R.string.size_limit_warn)
                    setTextColor(C["secondary"]!!); textSize = 13f
                    setPadding(24, 16, 24, 12)
                })
                val radioGroup = RadioGroup(this).apply { setPadding(24, 0, 24, 0) }
                labels.forEachIndexed { i, label ->
                    val rb = RadioButton(this).apply {
                        text = label; id = i
                        setTextColor(C["primary"]!!); textSize = 15f
                        setPadding(16, 10, 16, 10)
                        if (i == 2) isChecked = true
                    }
                    radioGroup.addView(rb, LinearLayout.LayoutParams(MATCH, WRAP))
                }
                body.addView(radioGroup)
                body.addView(View(this).apply {
                    setBackgroundColor(C["divider_subtle"]!!)
                    layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { setMargins(24, 8, 24, 8) }
                })
                val customRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(40, 8, 24, 16)
                }
                customRow.addView(TextView(this).apply {
                    text = getString(R.string.custom_mb); setTextColor(C["secondary"]!!); textSize = 13f
                    gravity = Gravity.CENTER_VERTICAL
                })
                val customInput = EditText(this).apply {
                    hint = getString(R.string.custom_mb); setTextColor(C["primary"]!!); setHintTextColor(C["hint"]!!)
                    setBackgroundColor(C["surface_dark"]!!); setPadding(8, 4, 8, 4); textSize = 14f; gravity = Gravity.CENTER
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    layoutParams = LinearLayout.LayoutParams(120, WRAP).apply { setMargins(12, 0, 0, 0) }
                }
                customRow.addView(customInput)
                body.addView(customRow)
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.size_limit_warn))
                    .setView(body)
                    .setPositiveButton(getString(R.string.search_button)) { _, _ ->
                        val custom = customInput.text.toString().toDoubleOrNull()
                        val bytes = if (custom != null && custom > 0) (custom * 1_000_000).toLong()
                                   else limits[radioGroup.checkedRadioButtonId.coerceIn(0, limits.size - 1)]
                        doSearch(queryText, 1, bytes)
                    }
                    .setNegativeButton(getString(R.string.action_cancel), null)
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
                (view.findViewById<TextView>(android.R.id.text1)).apply { setTextColor(C["secondary"]!!); textSize = 13f }
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
                    .setPositiveButton(getString(R.string.action_confirm), null)
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
                icon.setColorFilter(C["warning"]!!)
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
                icon.setColorFilter(C["primary"]!!)
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
        var multiSelected_: Set<File> = emptySet()
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
            // Multi-select: checkbox + highlight, click handled by listView's onItemClickListener
            val cb = view.findViewById<CheckBox>(R.id.checkbox)
            if (multiSelected_.isNotEmpty()) {
                cb.visibility = View.VISIBLE; cb.isChecked = f in multiSelected_
                cb.isClickable = false; cb.isFocusable = false
                if (f in multiSelected_) view.setBackgroundColor(0x4035acc6.toInt())
                else view.setBackgroundColor(0x00000000.toInt())
            } else {
                cb.visibility = View.GONE
                view.setBackgroundColor(0x00000000.toInt())
            }
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
                icon.setImageResource(android.R.drawable.ic_menu_compass); icon.setColorFilter(C["warning"]!!)
                label.text = f.name; size.text = ""; date.text = ""
            } else {
                starBtn.visibility = View.GONE
                val n = f.name.lowercase()
                val res = when { n.endsWith(".xp3")||n.endsWith(".pfs") -> android.R.drawable.ic_menu_compass; n.endsWith(".apk") -> android.R.drawable.ic_menu_manage; else -> android.R.drawable.ic_menu_gallery }
                icon.setImageResource(res); icon.setColorFilter(C["primary"]!!)
                label.text = f.name; size.text = fmt(fileSize(f)); date.text = df.format(Date(f.lastModified()))
            }
            return view
        }
    }
}
