package com.usefulunpacker

import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.util.Collections

/**
 * Holds all per-tab state + view references for one folder window.
 * Each tab (FolderFragment) owns exactly one [TabState].
 *
 * Kept as a plain holder so the existing MainActivity extension functions can
 * read/write `currentDir`, `selectedFile`, `multiSelected`, etc. against the
 * active tab's state.
 */
class TabState(val tabId: Int) {
    var currentDir: File = File("/")

    /**
     * What this tab's path bar must show.
     *
     * Derived, never stored: an open preview covers the list but not the bar, and
     * the bar is written in far fewer places than the preview is rendered in
     * (session restore, tab switch, fragment rebuild). Keeping the value derived
     * is what stops the bar from falling back to the layout's default `/`.
     */
    fun displayPath(): File =
        if (previewActive) previewSrc?.parentFile ?: currentDir else currentDir
    // Optional custom name shown in the tab strip; empty = default "窗口 N".
    var title: String = ""
    var selectedFile: File? = null
    var fileToMove: File? = null
    var multiFiles: List<File> = emptyList()
    var multiSelectMode: Boolean = false
    val multiSelected: MutableSet<File> = Collections.synchronizedSet(mutableSetOf<File>())

    // FileObserver + debounce handler for this tab's directory.
    var dirObserver: FileObserver? = null
    val refreshHandler: Handler = Handler(Looper.getMainLooper())
    // Assigned by the folder fragment to refresh this tab's current directory.
    var refreshAction: (() -> Unit)? = null
    val refreshRunnable: Runnable = Runnable { refreshAction?.invoke() }

    // View references (bound by FolderFragment.onCreateView).
    lateinit var listFiles: ListView
    lateinit var tvPath: TextView
    lateinit var tvCount: TextView
    lateinit var tvEmpty: TextView
    lateinit var bottomBar: LinearLayout
    lateinit var tvSelected: TextView
    lateinit var progress: ProgressBar
    lateinit var btnExtract: Button
    lateinit var fabExtract: FloatingActionButton
    var btnFolderNext: Button? = null
    var batchBar: LinearLayout? = null

    // In-tab archive preview views (bound by FolderFragment).
    lateinit var previewRoot: LinearLayout
    lateinit var tvPreviewTitle: TextView
    lateinit var previewList: ListView
    lateinit var tvPreviewStats: TextView
    lateinit var btnPreviewExtractAll: Button
    lateinit var btnPreviewExtract: Button
    lateinit var btnPreviewMerge: Button
    lateinit var btnPreviewSearch: ImageButton
    lateinit var btnPreviewOverflow: ImageButton


    // In-tab archive preview state (FAB preview renders INSIDE this tab, so the
    // ViewPager stays swipeable — no modal dialog on top).
    var previewActive: Boolean = false
    var previewEntries: List<ArchiveEntry> = emptyList()
    var previewSrc: File? = null
    var previewFormat: String = ""
    var previewPwd: String = ""
    var previewOpenKey: String? = null
    /** Encryption token of the open archive (xp3 only; see `xp3SchemeToken`).
     *  Probed once when the preview opens — it cannot be derived from the
     *  entry list, so it is state, and the title is built from it in one place
     *  (`previewTitleOf`) for both the first render and a view rebuild. */
    var previewEncNote: String = ""
    val previewSelected: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    val previewExpanded: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    var previewSearchQuery: String = ""

    /** Whether a background operation is in flight for this tab. */
    @Volatile var operationActive: Boolean = false

    /** Whether the folder fragment has bound this tab's views yet. */
    @Volatile var viewsBound: Boolean = false

    // Per-tab double-tap debounce timestamp (avoids cross-tab tap interference).
    var lastTapAt: Long = 0L

    // Path/file picker mode: when set, this tab runs as a picker (opened via
    // openPickerInTab). Tapping a file / the "pick this dir" button invokes the
    // callback, then the tab is closed and control returns to the origin tab.
    var pickerCallback: ((File) -> Unit)? = null
    var pickerAllowFiles: Boolean = false
    var pickerOriginTab: TabState? = null

    // 预览工作区身份：非空 = 该 tab 浏览的是 cacheDir/ws/<hash> 下的工作区目录
    // （由预览 ⋮「在窗口中打开」创建）。关闭 tab 时据此弹出「同时清理缓存？」。
    var wsDir: File? = null

    fun stopObserver() {
        dirObserver?.stopWatching()
        dirObserver = null
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    fun cleanup() {
        stopObserver()
    }
}
