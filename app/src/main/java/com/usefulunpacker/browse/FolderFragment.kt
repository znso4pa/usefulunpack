package com.usefulunpacker

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import java.io.File
import kotlin.concurrent.thread

/**
 * One folder window (tab). Inflates [R.layout.folder_view], binds a [TabState]'s
 * view references, and wires up directory navigation + item selection.
 */
class FolderFragment : Fragment() {

    lateinit var tab: TabState
        private set

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.folder_view, container, false)

        // Use the shared TabState owned by MainActivity.tabs, keyed by tab id.
        // This keeps per-tab state (currentDir, selection, etc.) consistent
        // between the fragment and the activity's active-tab delegation.
        val act = activity as MainActivity
        val id = arguments?.getInt(ARG_TAB_ID) ?: 0
        // Bind to the managed TabState by position. Never fabricate an unmanaged
        // one — a stale/out-of-range position (after closeTab/rebuild) falls back
        // to the nearest valid tab so the view and the delegated state stay in sync.
        tab = act.tabs.getOrNull(id) ?: act.tabs[act.tabs.lastIndex]

        tab.listFiles = root.findViewById(R.id.listFiles)
        tab.tvPath = root.findViewById(R.id.tvPath)
        tab.tvCount = root.findViewById(R.id.tvCount)
        tab.tvEmpty = root.findViewById(R.id.tvEmpty)
        tab.bottomBar = root.findViewById(R.id.bottomBar)
        tab.tvSelected = root.findViewById(R.id.tvSelected)
        tab.progress = root.findViewById(R.id.progress)
        tab.btnExtract = root.findViewById(R.id.btnExtract)
        tab.fabExtract = root.findViewById(R.id.fabExtract)
        tab.previewRoot = root.findViewById(R.id.previewRoot)
        tab.tvPreviewTitle = root.findViewById(R.id.tvPreviewTitle)
        tab.previewList = root.findViewById(R.id.previewList)
        tab.tvPreviewStats = root.findViewById(R.id.tvPreviewStats)
        tab.btnPreviewSearch = root.findViewById(R.id.btnPreviewSearch)
        tab.btnPreviewOverflow = root.findViewById(R.id.btnPreviewOverflow)
        tab.previewList.enableFastScroll()
        root.findViewById<ImageButton>(R.id.btnPreviewBack).setOnClickListener { act.exitPreview(tab) }
        tab.btnPreviewExtractAll = root.findViewById(R.id.btnPreviewExtractAll)
        tab.btnPreviewExtract = root.findViewById(R.id.btnPreviewExtract)
        tab.btnPreviewMerge = root.findViewById(R.id.btnPreviewMerge)
        tab.btnPreviewExtractAll.setOnClickListener {
            val src = tab.previewSrc ?: return@setOnClickListener
            act.exitPreview(tab)
            act.extractAll(uniqueFile(src.parentFile ?: return@setOnClickListener, src.nameWithoutExtension), src, tab.previewFormat, tab.previewPwd, tab)
        }
        tab.btnPreviewExtract.setOnClickListener {
            val sel = tab.previewSelected.filter { p -> tab.previewSelected.none { o -> o != p && o.startsWith(p + "/") } }
            if (sel.isEmpty()) { act.toast(act.getString(R.string.msg_select_one)); return@setOnClickListener }
            val src = tab.previewSrc ?: return@setOnClickListener
            act.showOutputDirDialog(src, sel, tab.previewFormat, tab.previewPwd, tab)
        }
        tab.btnPreviewMerge.setOnClickListener {
            val sel = tab.previewSelected.filter { p -> tab.previewSelected.none { o -> o != p && o.startsWith(p + "/") } }
            if (sel.isEmpty()) { act.toast(act.getString(R.string.msg_select_one)); return@setOnClickListener }
            val src = tab.previewSrc ?: return@setOnClickListener
            act.showMergeTargetPicker(src, sel, tab.previewFormat, tab.previewPwd, tab)
        }
        // Preview top-bar actions: search (icon) + overflow menu (编辑/条目/转换).
        tab.btnPreviewSearch.setOnClickListener { act.previewSearch(tab) }
        // 编辑：仅可封包格式；否则 toast 提示。
        fun doEdit() {
            if (tab.previewFormat !in setOf("xp3", "pfs", "iso", "nsa", "7z", "ypf")) {
                act.toast(act.getString(R.string.edit_only_pack))
                return
            }
            val src = tab.previewSrc ?: return
            act.exitPreview(tab)
            // Next-frame delay: exitPreview triggers navTab (list refresh +
            // FileObserver restart); starting the edit dialog on the same frame
            // can stack two window ops that Honor drops one of (dialog vanishes).
            act.viewPager.post { act.startEditArchive(src, tab.previewFormat, tab.previewPwd) }
        }
        fun doZipManage() {
            if (tab.previewFormat != "zip") {
                act.toast(act.getString(R.string.zip_manage_only_zip))
                return
            }
            act.previewZipManage(tab)
        }
        fun doConvertIso() {
            if (tab.previewFormat != "iso") {
                act.toast(act.getString(R.string.convert_only_iso))
                return
            }
            val src = tab.previewSrc ?: return
            act.exitPreview(tab)
            act.viewPager.post { act.convertIso(src, toCso = true) }
        }
        tab.btnPreviewOverflow.setOnClickListener { v ->
            PopupMenu(act, v).apply {
                menu.add(0, 1, 0, act.getString(R.string.edit_archive)).setOnMenuItemClickListener { doEdit(); true }
                menu.add(0, 2, 0, act.getString(R.string.zip_manage)).setOnMenuItemClickListener { doZipManage(); true }
                menu.add(0, 3, 0, act.getString(R.string.action_convert_cso)).setOnMenuItemClickListener { doConvertIso(); true }
                show()
            }
        }

        // Default extract/compress button action for this tab.
        tab.btnExtract.setOnClickListener { act.extract(tab) }
        tab.fabExtract.setOnClickListener { act.extract(tab) }

        // Compression-mode "→" folder button, created per-tab on its bottom bar.
        tab.btnFolderNext = Button(requireContext()).apply {
            text = "→"
            textSize = 14f
            visibility = View.GONE
            setPadding(8, 0, 8, 0)
            setOnClickListener { tab.selectedFile?.let { if (it.isDirectory) act.navTab(tab, it) } }
        }
        tab.bottomBar.addView(tab.btnFolderNext, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Set up this tab's debounce-refresh action (FileObserver → nav).
        tab.refreshAction = { (activity as? MainActivity)?.navTab(tab, tab.currentDir) }

        // Up button
        root.findViewById<ImageButton>(R.id.btnUp).setOnClickListener {
            tab.currentDir.parentFile?.let { (activity as? MainActivity)?.navTab(tab, it) }
        }
        // Picker-mode "✓ pick this dir" button (dir-mode only; hidden otherwise).
        val btnPickDir = root.findViewById<Button>(R.id.btnPickDir)
        btnPickDir.setOnClickListener {
            tab.pickerCallback?.invoke(tab.currentDir)
        }
        btnPickDir.visibility = if (tab.pickerCallback != null && !tab.pickerAllowFiles) View.VISIBLE else View.GONE

        // Per-tab batch action bar (multi-select). Added to this tab's folderRoot.
        tab.batchBar = buildBatchBar(root)

        // List item click
        tab.listFiles.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            val f = tab.listFiles.adapter.getItem(pos) as File
            val act = activity as MainActivity
            // Picker mode: tapping a FILE (when files are allowed) picks it.
            if (tab.pickerCallback != null && tab.pickerAllowFiles && f.isFile) {
                tab.pickerCallback?.invoke(f)
                return@OnItemClickListener
            }
            // Picker dir-mode: file taps do nothing; directories navigate.
            if (tab.pickerCallback != null && !tab.pickerAllowFiles && f.isFile) {
                return@OnItemClickListener
            }
            if (tab.multiSelectMode) { act.toggleMultiSelect(tab, f); return@OnItemClickListener }
            if (f.isDirectory) {
                // Picker mode always navigates (never enters compress selection).
                if (tab.pickerCallback != null) { act.navTab(tab, f); return@OnItemClickListener }
                val isCompressMode = act.prefs.getInt("work_mode", 0) == 1
                if (isCompressMode) {
                    tab.selectedFile = f
                    tab.tvSelected.text = act.getString(R.string.folder_selected, f.name)
                    tab.bottomBar.visibility = View.VISIBLE
                    tab.fabExtract.visibility = View.GONE
                    tab.btnExtract.text = act.getString(R.string.msg_compress_title)
                    tab.btnExtract.setOnClickListener { showCompressFormatPicker(act, f, act.prefs, tab.currentDir, tab) { act.navTab(tab, tab.currentDir) } }
                    if (act.activeTab === tab) tab.btnFolderNext?.visibility = View.VISIBLE
                    tab.progress.visibility = View.GONE
                } else {
                    act.navTab(tab, f)
                }
                return@OnItemClickListener
            }
            if (act.tryTap(tab)) act.select(tab, f)
        }

        // Long-press menu
        tab.listFiles.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, pos, _ ->
            if (tab.pickerCallback != null) return@OnItemLongClickListener false
            val f = tab.listFiles.adapter.getItem(pos) as File
            val act = activity as MainActivity
            AlertDialog.Builder(requireContext())
                .setTitle(f.name)
                .setItems(arrayOf(
                    act.getString(R.string.action_copy),
                    act.getString(R.string.action_copy_path),
                    act.getString(R.string.action_move),
                    act.getString(R.string.action_rename),
                    act.getString(R.string.action_delete),
                    act.getString(R.string.action_select),
                    act.getString(R.string.action_file_info),
                    act.getString(R.string.action_scan),
                    act.getString(R.string.action_share))) { _, w ->
                    when (w) {
                        0 -> act.copySingleFile(tab, f)
                        1 -> { (requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("p", f.path)); act.toast(act.getString(R.string.msg_copied)) }
                        2 -> { tab.fileToMove = f; act.updatePasteButton(); act.toast(act.getString(R.string.msg_selected_nav, f.name)) }
                        3 -> showRenameDialog(act, f, tab.currentDir, act.bookmarks) { act.saveBookmarks(); act.navTab(tab, tab.currentDir) }
                        4 -> act.confirmDeleteSingle(tab, f)
                        5 -> act.enterMultiSelect(tab, f)
                        8 -> act.shareFile(f)
                        6 -> {
                            if (f.isDirectory) {
                                // Count on a worker thread — a huge folder's top
                                // level can still jank the UI thread on tap.
                                thread {
                                    val fileCount = f.listFiles()?.size ?: 0
                                    val eta = fileCount / 200
                                    activity?.runOnUiThread {
                                        if (activity?.isFinishing == true) return@runOnUiThread
                                        AlertDialog.Builder(requireContext())
                                            .setTitle(act.getString(R.string.action_file_info))
                                            .setMessage(act.getString(R.string.msg_calc_dir_size_prompt, f.name, fileCount, eta, eta + 3))
                                            .setPositiveButton(act.getString(R.string.calc_size)) { _, _ -> calcDirSize(act, f) }
                                            .setNegativeButton(act.getString(R.string.action_cancel), null).show()
                                    }
                                }
                            } else {
                                act.showFileInfoDialog(f)
                            }
                        }
                        7 -> act.showSignatureScan(f)
                    }
                }.show()
            true
        }

        // If the tab's directory was set before this fragment bound its views
        // (e.g. during app startup), render it now.
        tab.viewsBound = true
        val act2 = activity as? MainActivity
        if (act2 != null) {
            // Restore an in-tab preview if the tab was previewing before the
            // fragment's view was recreated (swipe far / rebuildPager / rotate);
            // otherwise render the normal browser.
            if (tab.previewActive && tab.previewEntries.isNotEmpty()) {
                tab.tvPreviewTitle.text = tab.previewSrc?.name ?: ""
                act2.syncPreview(tab)
                act2.updatePreviewStats(tab)
            } else {
                act2.navTab(tab, tab.currentDir)
            }
            act2.syncMultiBar(tab)
        }

        // Wide screens: pin the in-tab preview to the right pane so the file
        // list stays visible beside it (master-detail).
        applyWidePreviewPane(tab)

        return root
    }

    /** On wide screens, pin the in-tab preview to the right of the split
     *  guideline so the file list remains visible (master-detail). On phones
     *  the preview overlays the full list, as before. */
    private fun applyWidePreviewPane(tab: TabState) {
        val act = activity as MainActivity
        val wide = act.resources.getBoolean(R.bool.is_wide)
        val lp = tab.previewRoot.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        if (wide) {
            lp.startToStart = R.id.guidelineSplit
        } else {
            lp.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        }
        lp.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
        tab.previewRoot.layoutParams = lp
    }

    private fun buildBatchBar(root: View): LinearLayout {
        val act = activity as MainActivity
        val batchBar = LinearLayout(requireContext()).apply {
            tag = "batchBar"
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C["surface_dim"]!!); visibility = View.GONE
            setPadding(12, 6, 12, 6)
            layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.MATCH_PARENT,
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            }
        }
        val tvBatchCount = TextView(requireContext()).apply { setTextColor(C["primary"]!!); textSize = 12f }
        fun b(text: String, color: Int) = Button(requireContext()).apply { this.text = text; setTextColor(color); background = null; textSize = 12f; isAllCaps = false; setPadding(6, 0, 6, 0) }
        // Emoji lives in the string resource (one leading glyph per item) — never
        // concatenate another here or the button shows doubled icons. The tag
        // drives mode-based visibility in syncMultiBar (text matching broke when
        // v5.13.0 prefixed extra emojis: the extract button started with 📦 and
        // was hidden in extract mode).
        val btnBatchExtract = b(act.getString(R.string.batch_extract), C["accent"]!!).apply { tag = "extract"; setOnClickListener { activate(); act.startBatchExtract() } }
        val btnBatchPreview = b(act.getString(R.string.action_preview), C["accent"]!!).apply { tag = "preview"; setOnClickListener { activate(); act.startBatchPreviewOnly() } }
        val btnBatchCompress = b(act.getString(R.string.batch_compress), C["accent"]!!).apply { tag = "compress"; setOnClickListener { activate(); act.startBatchCompress() } }
        val btnBatchCopy = b(act.getString(R.string.action_copy), C["accent"]!!).apply { setOnClickListener { activate(); act.startBatchCopy() } }
        val btnBatchMove = b(act.getString(R.string.action_move), C["accent"]!!).apply { setOnClickListener { activate(); act.startBatchMove() } }
        val btnBatchDelete = b(act.getString(R.string.action_delete), C["error"]!!).apply { setOnClickListener { activate(); act.confirmBatchDelete() } }
        val btnBatchCancel = b("✕ " + act.getString(R.string.action_cancel), C["tertiary"]!!).apply { setOnClickListener { act.exitAllMultiSelect() } }
        val batchScroll = HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            // Inner row fills the scroll area and right-aligns its buttons (the
            // count label stays pinned to the left). On a narrow screen the row
            // overflows and scrolls instead of overflowing the bar.
            addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                addView(btnBatchPreview)
                addView(btnBatchExtract)
                addView(btnBatchCompress)
                addView(btnBatchCopy)
                addView(btnBatchMove)
                addView(btnBatchDelete)
                addView(btnBatchCancel)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        batchBar.addView(tvBatchCount, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        batchBar.addView(batchScroll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        (root as? ViewGroup)?.addView(batchBar)
        return batchBar
    }

    /** Makes this fragment's tab the active one, so batch ops target it. */
    private fun activate() {
        (activity as? MainActivity)?.let {
            val idx = it.tabs.indexOf(tab)
            if (idx >= 0) {
                it.activeTabIndex = idx
                // Keep the pager in sync so a batch op launched from a visible
                // tab targets the right window even if the index was stale.
                it.viewPager.currentItem = idx
            }
        }
    }

    override fun onDestroyView() {
        // Only stop the observer when this tab was genuinely removed. If the
        // fragment's view is being torn down for a still-alive tab (e.g. during
        // rebuildPager adapter swap), leave the observer intact so the tab keeps
        // auto-refreshing; it's re-registered anyway on onResume/nav.
        val act = activity as? MainActivity
        if (act == null || !act.tabs.contains(tab)) tab.stopObserver()
        // Views are gone — clear the bound flag so a debounce refresh firing
        // in the rebind window can't write into the detached ListView/TextViews
        // (onCreateView sets it back once the new views exist). Also drop the
        // opStrip refs: a running inline controller must not keep writing into
        // (or retaining) a detached view tree; it goes headless until rebind.
        tab.viewsBound = false
        super.onDestroyView()
    }

    companion object {
        private const val ARG_TAB_ID = "tab_id"
        fun newInstance(tabId: Int): FolderFragment =
            FolderFragment().apply { arguments = Bundle().apply { putInt(ARG_TAB_ID, tabId) } }
    }
}
