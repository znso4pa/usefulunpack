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

    internal lateinit var drawer: DrawerLayout
    internal lateinit var tvPath: TextView
    internal lateinit var tvCount: TextView
    internal lateinit var tvSelected: TextView
    internal lateinit var tvEmpty: TextView
    internal lateinit var bottomBar: LinearLayout
    internal lateinit var progress: ProgressBar
    internal lateinit var btnExtract: Button
    internal lateinit var listFiles: ListView
    internal lateinit var fabExtract: FloatingActionButton
    internal lateinit var btnFolderNext: Button
    internal lateinit var listBookmarks: ListView

    internal var currentDir = Environment.getExternalStorageDirectory()
    internal var selectedFile: File? = null
    internal var fileToMove: File? = null
    internal var MultiFiles = listOf<File>()
    internal var multiSelectMode = false
    internal val multiSelected = mutableSetOf<File>()
    internal val prefs: SharedPreferences by lazy { getSharedPreferences("bm", MODE_PRIVATE) }
    internal val bookmarks = mutableListOf<String>()
    internal val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    internal var lastTap = 0L

    internal fun tryTap(): Boolean {
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
                                toast(getString(R.string.msg_permission_storage))
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
            setImageResource(R.drawable.ic_add_folder)
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
                    setText(getString(R.string.default_new_folder_name))
                    selectAll()
                    setTextColor(C["primary"]!!); setHintTextColor(C["hint"]!!)
                    setBackgroundColor(C["surface"]!!); setPadding(12, 8, 12, 8)
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.title_new_folder))
                    .setView(inp)
                    .setPositiveButton(getString(R.string.action_new_folder)) { _, _ ->
                        val baseName = inp.text.toString().trim()
                        if (baseName.isEmpty()) { toast(getString(R.string.prompt_new_folder_name)); return@setPositiveButton }
                        var name = baseName
                        var dir = File(currentDir, name)
                        var n = 1
                        while (dir.exists()) {
                            name = "$baseName ($n)"
                            dir = File(currentDir, name)
                            n++
                        }
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
                    tvSelected.text = getString(R.string.folder_selected, f.name)
                    bottomBar.visibility = View.VISIBLE
                    fabExtract.visibility = View.GONE
                    btnExtract.text = getString(R.string.msg_compress_title)
                    btnExtract.setOnClickListener { showCompressFormatPicker(this, f, prefs, currentDir) { nav(currentDir) } }
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
                .setItems(arrayOf(getString(R.string.action_copy_path), getString(R.string.action_move), getString(R.string.action_rename), getString(R.string.action_delete), getString(R.string.action_select), getString(R.string.action_file_info), getString(R.string.action_scan))) { _, w ->
                    when (w) {
                        0 -> { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("p", f.path)); toast(getString(R.string.msg_copied)) }
                        1 -> { fileToMove = f; updatePasteButton(); toast(getString(R.string.msg_selected_nav, f.name)) }
                        2 -> { showRenameDialog(this, f, currentDir, bookmarks) { saveBookmarks() } }
                        3 -> {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle(getString(R.string.title_delete))
                                                                .setMessage(getString(R.string.confirm_delete_file_msg, f.name))
                                .setPositiveButton(getString(R.string.action_delete)) { _, _ ->
                                    deleteWithProgress(this@MainActivity, listOf(f)) { del, fail ->
                                        if (fail > 0) toast(getString(R.string.msg_delete_result, del, fail)) else toast(getString(R.string.msg_deleted))
                                        pruneBookmarksForDeleted(listOf(f))
                                        nav(currentDir)
                                    }
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
                                                                        .setMessage(getString(R.string.msg_calc_dir_size_prompt, f.name, fileCount, eta, eta + 3))
                                    .setPositiveButton(getString(R.string.calc_size)) { _, _ -> calcDirSize(this, f) }
                                    .setNegativeButton(getString(R.string.action_cancel), null).show()
                            } else {
                                showFileInfoDialog(f)
                            }
                        }
                        6 -> { showSignatureScan(f) }
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

    internal fun updatePasteButton() {
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
                                                toast(getString(R.string.msg_move_result, ok, fail)); fileToMove = null; MultiFiles = listOf(); updatePasteButton(); nav(currentDir)
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
                        when (item.itemId) { 0 -> showTerminal(this@MainActivity, currentDir) { nav(it) }; 1 -> globalSearch(); 2 -> drawer.open(); 3 -> settings() }
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


    internal fun showDisclaimer(fromSettings: Boolean = false) {
        if (!fromSettings && prefs.getBoolean("disclaimer_accepted_v2", false)) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_disclaimer))
            .setMessage(getString(R.string.disclaimer_body))
            .setPositiveButton(getString(R.string.msg_disclaimer_agree)) { _, _ ->
                prefs.edit().putBoolean("disclaimer_accepted_v2", true).apply()
            }
            .setNegativeButton(getString(R.string.action_close)) { _, _ -> if (!fromSettings) finish() }
            .setCancelable(fromSettings)
            .show()
    }

    internal var bgImageLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null

    internal fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}

