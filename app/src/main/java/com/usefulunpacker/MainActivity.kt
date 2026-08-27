// ╔══════════════════════════════════════════════════════════════╗
// ║  UsefulUnpack — znso4pa (锌帕) — ZArchiver UI match          ║
// ╚══════════════════════════════════════════════════════════════╝

package com.usefulunpacker

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.content.Context
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
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread
import com.usefulunpacker.fileops.showRecycleBinDialog

/**
 * Caps a dialog to sensible max dimensions so it never stretches edge-to-edge
 * on tablets / landscape phones. `relW`/`relH` are the legacy "fraction of
 * screen" sizes; the result is `min(screen*frac, dimen cap)` — the cap grows
 * on sw600dp / landscape via the resource qualifiers.
 */
internal fun Context.cappedDialogSize(relW: Float, relH: Float): Pair<Int, Int> {
    val dm = resources.displayMetrics
    val maxW = resources.getDimensionPixelSize(R.dimen.dialog_max_width)
    val maxH = resources.getDimensionPixelSize(R.dimen.dialog_max_height)
    val w = minOf((dm.widthPixels * relW).toInt(), maxW).coerceAtLeast(0)
    val h = minOf((dm.heightPixels * relH).toInt(), maxH).coerceAtLeast(0)
    return w to h
}

class MainActivity : AppCompatActivity() {

    internal lateinit var drawer: DrawerLayout
    internal lateinit var listBookmarks: ListView

    // Multi-window views + adapters
    internal lateinit var viewPager: androidx.viewpager2.widget.ViewPager2
    internal lateinit var tabList: androidx.recyclerview.widget.RecyclerView
    internal val tabAdapter = TabStripAdapter(this)
    internal val fragAdapter = TabPagerAdapter(this)

    internal val prefs: SharedPreferences by lazy { getSharedPreferences("bm", MODE_PRIVATE) }
    internal val bookmarks = java.util.concurrent.CopyOnWriteArrayList<String>()
    internal val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    internal var currentMediaPlayer: android.media.MediaPlayer? = null

    // ── Multi-window (tab) state ─────────────────────────────────────────
    // Each tab owns a TabState; the "active" tab's state is what the legacy
    // MainActivity extension functions read/write (currentDir, selectedFile,
    // multiSelected, etc.), so those functions keep working against whichever
    // tab the user is currently viewing.
    internal val tabs = mutableListOf<TabState>()
    internal var activeTabIndex: Int = 0
    internal var activeTab: TabState
        get() = tabs[activeTabIndex]
        set(v) {
            val idx = tabs.indexOf(v)
            if (idx >= 0) activeTabIndex = idx
        }

    // Global multi-select session: stays true as long as ANY tab has selected
    // files, enabling auto-enter on tab switch so cross-tab selection works.
    internal var globalMultiSelectMode = false

    // Delegate per-tab state to the active tab.
    internal var currentDir: File
        get() = activeTab.currentDir
        set(v) { activeTab.currentDir = v }
    internal var selectedFile: File?
        get() = activeTab.selectedFile
        set(v) { activeTab.selectedFile = v }
    internal var fileToMove: File?
        get() = activeTab.fileToMove
        set(v) { activeTab.fileToMove = v }
    internal var multiFiles: List<File>
        get() = activeTab.multiFiles
        set(v) { activeTab.multiFiles = v }
    internal var multiSelectMode: Boolean
        get() = activeTab.multiSelectMode
        set(v) { activeTab.multiSelectMode = v }
    internal val multiSelected: MutableSet<File>
        get() = activeTab.multiSelected

    // Views delegated to the active tab. Read-only — per-tab views are written
    // through the tab's own fields (tab.tvPath etc.), never assigned here.
    internal val tvPath: TextView get() = activeTab.tvPath
    internal val tvCount: TextView get() = activeTab.tvCount
    internal val tvSelected: TextView get() = activeTab.tvSelected
    internal val tvEmpty: TextView get() = activeTab.tvEmpty
    internal val bottomBar: LinearLayout get() = activeTab.bottomBar
    internal val progress: ProgressBar get() = activeTab.progress
    internal val btnExtract: Button get() = activeTab.btnExtract
    internal val listFiles: ListView get() = activeTab.listFiles
    internal val fabExtract: FloatingActionButton get() = activeTab.fabExtract

    // Global bottom-left "add folder" button (shared across tabs, added to the
    // activity root). Hidden during multi-select so it can't overlap the batch bar.
    internal var btnAddFolder: ImageButton? = null

    // Live memory usage badge (设置→一般管理→显示内存). Floating overlay pinned
    // under the toolbar divider; refreshed every second while visible.
    internal var memBadge: TextView? = null
    private val memPollHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private var memPollScheduled = false

    // Background watcher on the current directory: auto-refresh the file list
    // when anything in it changes (rename / move / delete / extract / compress
    // / external changes like adb push or USB), so the user never has to
    // exit and re-enter a path to see the result.
    private var dirObserver: android.os.FileObserver?
        get() = activeTab.dirObserver
        set(v) { activeTab.dirObserver = v }
    private val refreshHandler: android.os.Handler
        get() = activeTab.refreshHandler
    private val refreshRunnable: Runnable
        get() = activeTab.refreshRunnable

    internal fun restartDirObserver(dir: File) {
        val tab = activeTab
        tab.dirObserver?.stopWatching()
        tab.dirObserver = null
        tab.refreshHandler.removeCallbacks(tab.refreshRunnable)
        if (!dir.isDirectory) return
        tab.dirObserver = object : android.os.FileObserver(dir.absolutePath) {
            override fun onEvent(event: Int, path: String?) {
                val e = event and android.os.FileObserver.ALL_EVENTS
                val content = android.os.FileObserver.CREATE or android.os.FileObserver.DELETE or
                    android.os.FileObserver.MOVED_FROM or android.os.FileObserver.MOVED_TO or
                    android.os.FileObserver.CLOSE_WRITE or android.os.FileObserver.DELETE_SELF or
                    android.os.FileObserver.MOVE_SELF or android.os.FileObserver.MODIFY
                if (e and content == 0) return
                tab.refreshHandler.removeCallbacks(tab.refreshRunnable)
                tab.refreshHandler.postDelayed(tab.refreshRunnable, FILE_OBSERVER_DEBOUNCE_MS)
            }
        }.apply { startWatching() }
    }

    internal fun tryTap(tab: TabState): Boolean {
        val now = System.currentTimeMillis()
        if (now - tab.lastTapAt < DOUBLE_TAP_INTERVAL_MS) return false
        tab.lastTapAt = now
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
        // Parallel-decode thread count (0 = auto). 1 disables parallel for
        // low-RAM devices; 2/4/8 cap the worker count.
        val parallelThreads = prefs.getInt("parallel_threads", 0).coerceIn(0, 8)
        try {
            RarCore.setParallelThreads(parallelThreads)
            ZipCore.setParallelThreads(parallelThreads)
        } catch (_: UnsatisfiedLinkError) {
            // stale .so without the setter — parallel uses the auto default
        }

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
        listBookmarks = findViewById(R.id.listBookmarks)

        // ── Set up multi-window (tab) browsing ─────────────────────────
        viewPager = findViewById(R.id.viewPager)
        tabList = findViewById(R.id.tabList)
        tabList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
        tabList.adapter = tabAdapter
        findViewById<ImageButton>(R.id.btnAddTab).setOnClickListener { addTab() }

        // Initial tabs: start with one, up to MAX_TABS. Keeping every page
        // offscreen-retained means tab views never cycle while switching.
        viewPager.adapter = fragAdapter
        viewPager.offscreenPageLimit = MAX_TABS - 1
        // All fragments stay attached (one per slot); only `tabs.size` are visible.
        viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                activeTabIndex = position
                // OpOverlay cards are scoped to their owning window — switching
                // tabs sends the running progress "to background".
                OpOverlay.onActiveTabChanged(
                    this@MainActivity,
                    tabs.getOrNull(position)?.tabId ?: -1,
                    tabs.map { it.tabId }.toSet()
                )
                tabAdapter.notifyDataSetChanged()
                updateTitle()
                // Per-tab toolbar paste button + batch bar must be re-synced to the
                // newly-active tab (fileToMove / multiSelectMode are per-tab).
                updatePasteButton()
                // 全局多选会话：切换到新 tab 时自动进入多选模式
                if (globalMultiSelectMode) {
                    enterMultiSelectMode(activeTab)
                    // 隐藏其他 tab 的 batch bar（它们不再是活跃 tab）
                    for (t in tabs) { if (t !== activeTab) syncMultiBar(t) }
                }
                syncMultiBar(activeTab)
                // Re-render an in-tab preview when its tab becomes active again.
                if (activeTab.previewActive) syncPreview(activeTab)
                // Re-derive the compression-mode "→" button visibility for the
                // now-active tab (it's gated on activeTab === tab).
                val tab = activeTab
                if (tab.multiSelectMode) {
                    tab.btnFolderNext?.visibility = View.GONE
                } else if (tab.selectedFile?.isDirectory == true) {
                    tab.btnFolderNext?.visibility = View.VISIBLE
                } else {
                    tab.btnFolderNext?.visibility = View.GONE
                }
            }
        })
        // Initial tabs: restore the saved session if enabled, else one tab.
        restoreSession()

        findViewById<ImageButton>(R.id.btnDrawer).setOnClickListener { drawer.open() }
        findViewById<ImageButton>(R.id.btnRoot).setOnClickListener { nav(Environment.getExternalStorageDirectory()) }
        findViewById<ImageButton>(R.id.btnRecycle).setOnClickListener { showRecycleBinDialog(this) }
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
        this.btnAddFolder = btnAddFolder
        applyMemoryBadge()
        // btnExtract/fabExtract click listeners are set per-tab in FolderFragment
        // (the views now live inside each tab's folder_view.xml).
        findViewById<TextView>(R.id.btnAddBookmark).setOnClickListener {
            if (bookmarks.contains(currentDir.absolutePath).not()) {
                bookmarks.add(0, currentDir.absolutePath); saveBookmarks()
            }
            drawer.close()
        }
        // Note: the compression-mode "→" folder button is now created per-tab
        // in FolderFragment (bottomBar lives in each tab's folder_view).
        listBookmarks.onItemClickListener = OnItemClickListener { _, _, pos, _ ->
            nav(File(bookmarks[pos])); drawer.close()
        }
        listBookmarks.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, pos, _ ->
            bookmarks.removeAt(pos); saveBookmarks(); true
        }

        loadBookmarks(); nav(currentDir)
        showDisclaimer()
    }

    override fun onPause() {
        super.onPause()
        saveSession()
        try { currentMediaPlayer?.release() } catch (_: Exception) {}
        currentMediaPlayer = null
        tabs.forEach {
            it.dirObserver?.stopWatching()
            // Also drop an already-posted debounce refresh — firing it while
            // paused would run navTab on a possibly-torn-down view and
            // re-register the observer we just stopped.
            it.refreshHandler.removeCallbacks(it.refreshRunnable)
        }
    }

    override fun onResume() {
        super.onResume()
        // Restart each tab's observer; skip background tabs whose fragment views
        // aren't attached yet (their onCreateView re-registers the observer).
        tabs.forEach { restartDirObserverFor(it) }
        thread { com.usefulunpacker.fileops.RecycleBin.autoClean(this, prefs) }
    }

    override fun onBackPressed() {
        // An in-tab archive preview intercepts back to exit the preview first,
        // rather than closing the whole activity.
        if (activeTab.previewActive) {
            exitPreview(activeTab)
            return
        }
        // A picker window's back key cancels the pick: close the tab and return
        // to the origin tab (no file picked).
        val picker = activeTab
        if (picker.pickerCallback != null) {
            val owner = picker.pickerOriginTab
            closeTab(picker)
            if (owner != null && tabs.contains(owner)) {
                val idx = tabs.indexOf(owner)
                if (idx >= 0) viewPager.currentItem = idx
            }
            return
        }
        super.onBackPressed()
    }

    // ── Memory usage badge ───────────────────────────────────────────────
    /** Creates or removes the floating badge per the show_mem pref and
     *  (re)starts the 1s poller. Safe to call repeatedly. */
    internal fun applyMemoryBadge() {
        if (prefs.getBoolean("show_mem", false)) {
            val root = findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.root)
            if (root != null && memBadge == null) {
                memBadge = TextView(this).apply {
                    textSize = resources.getDimension(R.dimen.text_xs) / resources.displayMetrics.scaledDensity
                    setTextColor(C["primary"]!!)
                    setPadding(16, 10, 16, 10)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 8f * resources.displayMetrics.density
                        setColor(0xCC2A2A2A.toInt())
                    }
                    layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topToBottom = R.id.dividerToolbar
                        endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                        topMargin = 12
                        marginEnd = 12
                    }
                }
                root.addView(memBadge)
            }
            updateMemBadgeText()
            scheduleMemPoll()
        } else {
            memBadge?.let { (it.parent as? ViewGroup)?.removeView(it) }
            memBadge = null
        }
    }

    private fun scheduleMemPoll() {
        // Single self-rescheduling tick: exits on its own once the badge is
        // gone (toggle off / view removed), no separate stop call needed.
        if (memPollScheduled) return
        memPollScheduled = true
        memPollHandler.postDelayed(object : Runnable {
            override fun run() {
                memPollScheduled = false
                if (memBadge == null || isFinishing || isDestroyed) return
                try { updateMemBadgeText() } catch (_: Exception) {}
                scheduleMemPoll()
            }
        }, 1000)
    }

    private fun updateMemBadgeText() {
        val tv = memBadge ?: return
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val dm = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(dm)
        val uuBytes = dm.totalPss * 1024L
        val usedBytes = (mi.totalMem - mi.availMem).coerceAtLeast(0)
        val sysBytes = (usedBytes - uuBytes).coerceAtLeast(0)
        tv.text = "${giB(usedBytes)}/${giB(mi.totalMem)}\n" +
            getString(R.string.mem_system) + ": " + memFmt(sysBytes) + "\n" +
            getString(R.string.mem_uu) + ": " + memFmt(uuBytes)
    }

    /** "12.4g" style — one decimal, trailing ".0" trimmed. */
    private fun giB(bytes: Long): String {
        val g = bytes / GIB_F
        val s = String.format(java.util.Locale.US, "%.1f", g)
        return if (s.endsWith(".0")) s.dropLast(2) + "g" else s + "g"
    }

    /** <1GiB → megabytes ("356m"), otherwise one decimal gigabytes. */
    private fun memFmt(bytes: Long): String =
        if (bytes < GIB_F) "${(bytes / MIB_F).toInt()}m" else giB(bytes)

    override fun onDestroy() {
        try { currentMediaPlayer?.release() } catch (_: Exception) {}
        currentMediaPlayer = null
        // 清理编辑临时文件
        try {
            val editDir = File(cacheDir, "edit")
            if (editDir.exists()) {
                editDir.listFiles()?.forEach { it.delete() }
                editDir.delete()
            }
        } catch (_: Exception) {}
        tabs.forEach { it.stopObserver() }
        // Only drop archive registrations owned by THIS instance's tabs — on
        // rotation the new activity may have already re-registered its previews,
        // and a blanket clearAll() would silently kill the same-archive mutex.
        OpenArchiveRegistry.clearFor(tabs)
        memPollHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    internal fun restartDirObserverFor(tab: TabState) {
        if (!tabs.contains(tab)) return
        val wasActive = activeTab === tab
        if (wasActive) {
            restartDirObserver(tab.currentDir)
        } else {
            tab.dirObserver?.stopWatching()
            tab.dirObserver = null
            tab.refreshHandler.removeCallbacks(tab.refreshRunnable)
            val dir = tab.currentDir
            if (!dir.isDirectory) return
            tab.dirObserver = object : android.os.FileObserver(dir.absolutePath) {
                override fun onEvent(event: Int, path: String?) {
                    val e = event and android.os.FileObserver.ALL_EVENTS
                    val content = android.os.FileObserver.CREATE or android.os.FileObserver.DELETE or
                        android.os.FileObserver.MOVED_FROM or android.os.FileObserver.MOVED_TO or
                        android.os.FileObserver.CLOSE_WRITE or android.os.FileObserver.DELETE_SELF or
                        android.os.FileObserver.MOVE_SELF or android.os.FileObserver.MODIFY
                    if (e and content == 0) return
                    tab.refreshHandler.removeCallbacks(tab.refreshRunnable)
                    tab.refreshHandler.postDelayed(tab.refreshRunnable, FILE_OBSERVER_DEBOUNCE_MS)
                }
            }.apply { startWatching() }
        }
    }

    internal fun updatePasteButton() {
        findViewById<ImageButton>(R.id.btnCLI)?.let { cli ->
            val hasFile = fileToMove != null
            cli.setImageResource(if (hasFile) R.drawable.ic_clipboard else R.drawable.ic_overflow)
            cli.setColorFilter(if (hasFile) C["accent"]!! else C["tertiary"]!!)
            cli.setOnClickListener { v ->
                if (hasFile) {
                    if (multiFiles.size > 1) {
                        var ok = 0; var fail = 0
                        for (src in multiFiles) {
                            val dst = File(currentDir, src.name)
                            if (dst.exists()) { fail++; continue }
                            if (src.renameTo(dst)) ok++ else fail++
                        }
                                                toast(getString(R.string.msg_move_result, ok, fail)); fileToMove = null; multiFiles = listOf(); updatePasteButton(); nav(currentDir)
                    } else {
                        val src = fileToMove ?: return@setOnClickListener
                        val dst = File(currentDir, src.name)
                        if (dst.exists()) { toast(getString(R.string.msg_target_exists)); return@setOnClickListener }
                        if (src.renameTo(dst)) { toast(getString(R.string.msg_moved_to, dst.path)); fileToMove = null; multiFiles = listOf(); updatePasteButton(); nav(currentDir) }
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
                    setOnClickListener { fileToMove = null; multiFiles = listOf(); updatePasteButton(); toast(getString(R.string.msg_cancelled)) }
                }
                toolbar?.addView(btnCancel)
            }
        } else {
            oldCancel?.let { toolbar?.removeView(it) }
        }
    }


    // ── Tab management ──────────────────────────────────────────────────
    /** Display title of a tab: its custom name, or the default "窗口 N". */
    internal fun tabTitle(tab: TabState): String {
        val idx = tabs.indexOf(tab)
        return tab.title.ifEmpty { "窗口 ${(idx + 1).coerceAtLeast(1)}" }
    }

    internal fun updateTitle() {
        val isCompress = prefs.getInt("work_mode", 0) == 1
        findViewById<TextView>(R.id.tvTitle)?.text =
            "UsefulUnpack" + (if (isCompress) getString(R.string.title_mode_compress) else getString(R.string.title_mode_archive))
        tabAdapter.notifyDataSetChanged()
    }

    internal fun addTab() {
        if (tabs.size >= MAX_TABS) {
            toast(getString(R.string.msg_max_tabs))
            return
        }
        val tab = TabState(tabs.size)
        // New windows start at the external storage root, like the original app.
        tab.currentDir = android.os.Environment.getExternalStorageDirectory()
        tabs.add(tab)
        rebuildPager()
        viewPager.post { viewPager.currentItem = tabs.size - 1 }
    }

    /** Renames the given tab (long-press its label in the strip). Empty input
     *  resets to the default "窗口 N". The custom name is also what the
     *  same-archive conflict toast shows, so the user can tell windows apart.
     *  Names are hard-capped at 8 characters — the strip is a shared horizontal
     *  bar and an unbounded name would push every other tab off-screen. */
    internal fun showTabRenameDialog(tab: TabState) {
        val inp = EditText(this).apply {
            setText(tab.title)
            selectAll()
            filters = arrayOf(android.text.InputFilter.LengthFilter(8))
            setTextColor(C["primary"]!!); setHintTextColor(C["hint"]!!)
            setBackgroundColor(C["surface"]!!); setPadding(12, 8, 12, 8)
            hint = getString(R.string.tab_rename_prompt)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.tab_rename))
            .setView(inp)
            .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                tab.title = inp.text.toString().trim()
                tabAdapter.notifyDataSetChanged()
            }
            .setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

    /**
     * Opens a NEW WINDOW as a path/file picker (the "pick in a new window" mode):
     * adds a tab rooted at [startDir], runs it in picker mode, and on pick closes
     * the picker tab and returns to the tab that launched it (origin tab keeps its
     * state). File mode: tap a file to pick. Dir mode: a ✓ "pick this dir" button
     * appears in the picker tab's path bar.
     */
    internal fun openPickerInTab(startDir: File, allowFiles: Boolean, onPick: (File) -> Unit) {
        if (tabs.size >= MAX_TABS) {
            // No slot for a picker window — fall back to the in-dialog picker.
            showFolderPickerDialog(this, startDir, allowFiles, onPick)
            return
        }
        val origin = activeTab
        val picker = TabState(tabs.size)
        picker.currentDir = startDir
        picker.pickerCallback = { picked ->
            // Pick complete: close the picker tab and return to the origin tab.
            val owner = picker.pickerOriginTab
            onPick(picked)
            if (tabs.contains(picker)) {
                closeTab(picker)
            }
            if (owner != null && tabs.contains(owner)) {
                val idx = tabs.indexOf(owner)
                if (idx >= 0) viewPager.currentItem = idx
            }
        }
        picker.pickerAllowFiles = allowFiles
        picker.pickerOriginTab = origin
        tabs.add(picker)
        rebuildPager()
        viewPager.post {
            viewPager.currentItem = tabs.size - 1
            restartDirObserverFor(picker)
        }
    }

    internal fun closeTab(tab: TabState) {
        if (tabs.size <= 1) return // keep at least one
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        tab.stopObserver()
        // Drop any archive the closed tab had open so its keys free up.
        OpenArchiveRegistry.releaseTab(tab)
        tabs.removeAt(idx)
        if (activeTabIndex > idx) activeTabIndex--
        if (activeTabIndex >= tabs.size) activeTabIndex = tabs.size - 1
        // Rebuild the pager so every fragment re-binds to the (shifted) tabs by
        // position — FragmentStateAdapter would otherwise reuse a stale fragment
        // that still points at the removed tab.
        rebuildPager()
        viewPager.post {
            viewPager.currentItem = activeTabIndex
            restartDirObserver(activeTab.currentDir)
        }
    }

    /** Detaches and re-attaches the pager adapter so fragments rebind to tabs.
     *  A FRESH adapter instance is used each time — reusing the same
     *  FragmentStateAdapter after adapter=null can retain stale fragments bound
     *  to pre-removal TabStates, which breaks tab isolation after closeTab(). */
    internal fun rebuildPager() {
        val idx = viewPager.currentItem
        viewPager.adapter = null
        viewPager.adapter = TabPagerAdapter(this)
        tabAdapter.notifyDataSetChanged()
        viewPager.post { viewPager.currentItem = idx.coerceIn(0, tabs.size - 1) }
    }

    /** Refreshes a tab's current directory (used by TabState's debounce). */
    internal fun refreshTab(tab: TabState) {
        if (tabs.contains(tab)) navTab(tab, tab.currentDir)
    }

    // ── Per-tab helpers used by FolderFragment ──────────────────────────
    internal fun copySingleFile(tab: TabState, f: File) {
        val targetDir = tab.currentDir
        thread {
            try {
                val dest = File(targetDir, getCopyFileName(f, targetDir))
                if (f.isDirectory) f.copyRecursively(dest, overwrite = false)
                else f.copyTo(dest, overwrite = false)
                runOnUiThread { toast(getString(R.string.msg_copied)); refreshTab(tab) }
            } catch (e: Exception) {
                // A failed copy must not claim success.
                runOnUiThread { toast(getString(R.string.err_file_error)) }
            }
        }
    }

    internal fun confirmDeleteSingle(tab: TabState, f: File) {
        val recycleEnabled = com.usefulunpacker.fileops.RecycleBin.isEnabled(prefs)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_delete))
            .setMessage(getString(if (recycleEnabled) R.string.confirm_recycle_file_msg else R.string.confirm_delete_file_msg, f.name))
            .setPositiveButton(getString(R.string.action_delete)) { _, _ ->
                deleteWithProgress(this, listOf(f), prefs) { del, fail ->
                    if (fail > 0) toast(getString(R.string.msg_delete_result, del, fail)) else toast(getString(if (recycleEnabled) R.string.msg_moved_to_recycle else R.string.msg_deleted))
                    pruneBookmarksForDeleted(listOf(f))
                    refreshTab(tab)
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null).show()
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

    /** Honor/EMUI touch-state workaround: a dismissed dialog (its fast-scroll
     *  list / scrollview) can leave the ViewPager2 unable to intercept horizontal
     *  swipes, so tab switching dies until restart. Re-arming the pager's input
     *  state on dismiss resets the internal RecyclerView's touch handling.
     *  [also] runs first if provided. */
    internal fun resetPagerInputOnDialogDismiss(dlg: android.app.Dialog, also: (() -> Unit)? = null) {
        dlg.setOnDismissListener {
            also?.invoke()
            viewPager.setUserInputEnabled(false)
            viewPager.setUserInputEnabled(true)
        }
    }

    companion object {
        const val MAX_TABS = 4
        private const val GIB_F = 1024f * 1024f * 1024f
        private const val MIB_F = 1024f * 1024f
    }
}

/**
 * Adapter for the horizontal tab strip. Each tab shows "窗口 N" plus a close
 * button (hidden when only one tab remains). Tapping a tab activates it.
 */
internal class TabStripAdapter(private val act: MainActivity) :
    androidx.recyclerview.widget.RecyclerView.Adapter<TabStripAdapter.VH>() {
    class VH(val root: LinearLayout, val badge: TextView) : androidx.recyclerview.widget.RecyclerView.ViewHolder(root)

    override fun getItemCount(): Int = act.tabs.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pd = act.resources.getDimensionPixelSize(R.dimen.space_md)
            // 占满 tabList 高度：垂直方向去掉内边距并设为 MATCH_PARENT
            setPadding(pd, 0, (pd * 2 / 3), 0)
            setBackgroundColor(0x00000000)
            layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(
                androidx.recyclerview.widget.RecyclerView.LayoutParams.WRAP_CONTENT,
                androidx.recyclerview.widget.RecyclerView.LayoutParams.MATCH_PARENT
            )
        }
        // 标签图标，颜色在 onBind 中按激活状态统一着色
        val icon = ImageView(act).apply {
            setImageResource(R.drawable.ic_tab_pc)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val sz = (20 * act.resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                rightMargin = act.resources.getDimensionPixelSize(R.dimen.space_xs)
            }
        }
        // 窗口标题文字：小字号（text_xs），紧挨图标。WRAP_CONTENT 宽度配合
        // maxWidth：超宽名字（历史数据等）在 96dp 处截断省略，不会把其他
        // 标签挤出屏幕。
        val label = TextView(act).apply {
            textSize = act.resources.getDimension(R.dimen.text_xs) / act.resources.displayMetrics.scaledDensity
            setPadding(0, 0, act.resources.getDimensionPixelSize(R.dimen.space_sm), 0)
            maxLines = 1
            maxWidth = (96 * act.resources.displayMetrics.density).toInt()
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        val close = TextView(act).apply {
            text = "✕"
            textSize = act.resources.getDimension(R.dimen.text_sm) / act.resources.displayMetrics.scaledDensity
            setPadding(act.resources.getDimensionPixelSize(R.dimen.space_xs), 0, act.resources.getDimensionPixelSize(R.dimen.space_xs), 0)
        }
        // 选中项数量角标：小圆角背景 + 数字，默认隐藏
        val badge = TextView(act).apply {
            textSize = 9f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(
                (4 * act.resources.displayMetrics.density).toInt(),
                0,
                (4 * act.resources.displayMetrics.density).toInt(),
                0
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(C["accent"]!!)
                cornerRadius = 8 * act.resources.displayMetrics.density
            }
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(icon)
        root.addView(label, LinearLayout.LayoutParams(WRAP, WRAP))
        root.addView(badge, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = (2 * act.resources.displayMetrics.density).toInt() })
        root.addView(close, LinearLayout.LayoutParams(WRAP, WRAP))
        return VH(root, badge)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tabs = act.tabs
        if (position >= tabs.size) {
            // Guard: unbind the close/root listeners so a stale holder can't
            // index tabs[position] out of bounds after a tab is removed.
            holder.root.setOnClickListener(null)
            holder.root.getChildAt(3).setOnClickListener(null)
            return
        }
        val icon = holder.root.getChildAt(0) as ImageView
        val label = holder.root.getChildAt(1) as TextView
        val close = holder.root.getChildAt(3) as TextView
        val isActive = position == act.activeTabIndex
        val thisTab = tabs[position]
        // 图标颜色与其他图标统一：激活 accent，未激活 tertiary
        icon.setColorFilter(if (isActive) C["accent"]!! else C["tertiary"]!!)
        label.text = act.tabTitle(thisTab)
        label.setTextColor(if (isActive) C["accent"]!! else C["tertiary"]!!)
        // Active tab gets a RAISED (lighter) flat background — no stroke, no corner radius.
        holder.root.background = if (isActive) {
            android.graphics.drawable.ColorDrawable(C["surface_raised"]!!)
        } else {
            android.graphics.drawable.ColorDrawable(0x00000000)
        }
        close.setTextColor(C["tertiary"]!!)
        close.visibility = if (tabs.size > 1) View.VISIBLE else View.GONE
        // 选中项角标：仅当该 tab 有选中项时显示数字
        val selCount = thisTab.multiSelected.size
        holder.badge.text = selCount.toString()
        holder.badge.visibility = if (selCount > 0) View.VISIBLE else View.GONE
        holder.root.setOnClickListener {
            act.activeTabIndex = position
            act.viewPager.currentItem = position
            act.tabAdapter.notifyDataSetChanged()
            act.updateTitle()
            act.updatePasteButton()
            act.syncMultiBar(act.activeTab)
        }
        holder.root.setOnLongClickListener { act.showTabRenameDialog(thisTab); true }
        close.setOnClickListener { act.closeTab(thisTab) }
    }
}

/** ViewPager2 adapter hosting one FolderFragment per tab slot (up to MAX_TABS). */
internal class TabPagerAdapter(private val act: MainActivity) :
    androidx.viewpager2.adapter.FragmentStateAdapter(act) {
    override fun getItemCount(): Int = act.tabs.size

    override fun createFragment(position: Int): Fragment {
        return FolderFragment.newInstance(position)
    }
}


