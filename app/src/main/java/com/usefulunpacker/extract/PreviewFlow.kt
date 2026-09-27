package com.usefulunpacker

import android.app.AlertDialog
import android.app.ProgressDialog
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import kotlin.concurrent.thread

internal fun MainActivity.previewArchive(src: File, format: String) {
        // Same-archive mutex: an archive already open in another window (any
        // split-volume part counts as the same archive) → toast + jump there
        // instead of opening a second copy.
        val openKey = archiveKey(src)
        val conflict = OpenArchiveRegistry.owner(openKey)
        if (conflict != null && conflict !== activeTab) {
            toast(getString(R.string.msg_archive_open_in_tab, tabTitle(conflict)))
            val idx = tabs.indexOf(conflict)
            if (idx >= 0) viewPager.currentItem = idx
            return
        }
        val pd = ProgressDialog(this).apply {
            setTitle(getString(R.string.reading))
                        setMessage(getString(R.string.msg_reading_archive, src.name))
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            show()
        }
        thread {
            // Ask for the password up front so header-encrypted 7z can be
            // listed and entry previews reuse it (no "wrong password" toast).
            var pwd = ""
            val pwDetected = format in setOf("zip", "7z", "rar") && isPasswordProtected(src)
            if (pwDetected) {
                val entered = promptPasswordSync(this)
                if (entered == null) { runOnUiThread { if (isFinishing || isDestroyed) return@runOnUiThread; pd.dismiss() }; return@thread }
                pwd = entered
            }
            val json = try { when(format) { "xp3" -> Xp3Core.xp3ListEntries(src.absolutePath)
                "pfs" -> PfsCore.pfsListEntries(src.absolutePath)
                "nsa" -> NsaCore.nsaListEntries(src.absolutePath)
                "iso" -> IsoCore.isoListEntries(src.absolutePath)
                "ypf" -> YpfCore.ypfListEntries(src.absolutePath)
                "zip" -> { ZipCore.zipSetEncoding(prefs.getString("zip_encoding", "UTF-8") ?: "UTF-8"); val vols = resolveZipVolumes(src); if (vols.size > 1) ZipCore.zipListEntriesVolumes(volumeJoin(vols)) else ZipCore.zipListEntries(src.absolutePath) }
                "7z" -> { val vols = resolveSevenZVolumes(src); if (vols.size > 1) (if (pwd.isNotEmpty()) SevenZCore.szListEntriesVolumesWithPassword(volumeJoin(vols), pwd) else SevenZCore.szListEntriesVolumes(volumeJoin(vols))) else if (pwd.isNotEmpty()) SevenZCore.szListEntriesWithPassword(src.absolutePath, pwd) else SevenZCore.szListEntries(src.absolutePath) }
                "rar" -> { val vols = resolveRarVolumes(src); if (vols.size > 1) (if (pwd.isNotEmpty()) RarCore.rarListEntriesVolumesWithPassword(volumeJoin(vols), pwd) else RarCore.rarListEntriesVolumes(volumeJoin(vols))) else if (pwd.isNotEmpty()) RarCore.rarListEntriesWithPassword(src.absolutePath, pwd) else RarCore.rarListEntries(src.absolutePath) }
                "lz4" -> Lz4Core.lz4ListEntries(src.absolutePath)
                "gz" -> GzipCore.gzListEntries(src.absolutePath)
                "bz2" -> Bzip2Core.bz2ListEntries(src.absolutePath)
                "xz" -> XzCore.xzListEntries(src.absolutePath)
                "zst" -> ZstdCore.zstListEntries(src.absolutePath)
                "lzma" -> LzmaCore.lzmaListEntries(src.absolutePath)
                "ksd" -> KsdCore.ksdListEntries(src.absolutePath)
                "br" -> BrotliCore.brotliListEntries(src.absolutePath)
                "tar" -> TarCore.tarListEntries(src.absolutePath)
                else -> null
            } } catch (_: Exception) { null }
            runOnUiThread { if (isFinishing || isDestroyed) return@runOnUiThread; pd.dismiss() }
            if (json == null) {
                val msg = if (format in setOf("zip", "7z", "rar")) getString(R.string.err_cannot_read_maybe_pwd) else getString(R.string.msg_cannot_read)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    // Auto-detection can be wrong (e.g. a mislabeled extension) —
                    // offer the manual format picker as an escape hatch.
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.msg_cannot_read))
                        .setMessage(msg)
                        .setPositiveButton(getString(R.string.title_select_format)) { _, _ -> extract() }
                        .setNegativeButton(getString(R.string.action_cancel), null)
                        .show()
                }
                return@thread
            }
            if (json == "[]") {
                // 合法但空的归档不是读取失败：不弹「无法读取/可能需要密码」，
                // 直接提示为空，避免误导用户去试格式选择器。
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    toast(getString(R.string.msg_empty_archive))
                }
                return@thread
            }
            val entries = parseEntries(json)
            // Register only once we actually have entries to show (a failed
            // parse never holds the slot). register() re-checks ownership so a
            // tab that won the race in between is respected.
            if (!OpenArchiveRegistry.register(openKey, activeTab)) {
                val winner = OpenArchiveRegistry.owner(openKey)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (winner != null && winner !== activeTab) {
                        toast(getString(R.string.msg_archive_open_in_tab, tabTitle(winner)))
                        val idx = tabs.indexOf(winner)
                        if (idx >= 0) viewPager.currentItem = idx
                    }
                }
                return@thread
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                renderPreview(activeTab, src, entries, format, pwd, openKey)
            }
        }
    }

/**
 * Renders an archive preview INSIDE the given tab (no modal dialog), so the
 * ViewPager above stays swipeable and the user can flip windows while looking
 * at archive contents. Entries/selection state live on the TabState.
 */
internal fun MainActivity.renderPreview(tab: TabState, src: File, entries: List<ArchiveEntry>, format: String, pwd: String, openKey: String) {
    tab.previewActive = true
    tab.previewSrc = src
    tab.previewFormat = format
    tab.previewPwd = pwd
    tab.previewOpenKey = openKey
    tab.previewEntries = entries
    tab.previewSelected.clear()
    tab.previewExpanded.clear()
    tab.previewExpanded.addAll(entries.filter { it.isDirectory }.map { it.path })
    tab.previewSearchQuery = ""
    if (tab.viewsBound) {
        tab.fabExtract.visibility = View.GONE
        tab.bottomBar.visibility = View.GONE
        tab.batchBar?.visibility = View.GONE
        tab.tvPreviewTitle.text = src.name
        // Preview top-bar: search icon + overflow menu (编辑/条目/转换 live in
        // the menu and toast when the format doesn't apply — see FolderFragment).
        tab.btnPreviewSearch.visibility = View.VISIBLE
        tab.btnPreviewOverflow.visibility = View.VISIBLE
        syncPreview(tab)
        updatePreviewStats(tab)
    }
    saveSession()
}

/** Exits in-tab preview, releasing the archive slot and restoring the browser. */
internal fun MainActivity.exitPreview(tab: TabState) {
    if (!tab.previewActive) return
    tab.previewActive = false
    tab.previewOpenKey?.let { OpenArchiveRegistry.unregister(it) }
    tab.previewOpenKey = null
    tab.previewEntries = emptyList()
    tab.previewSelected.clear()
    tab.previewExpanded.clear()
    if (tab.viewsBound) {
        tab.previewRoot.visibility = View.GONE
        navTab(tab, tab.currentDir)
    }
    saveSession()
}

/** In-preview "search inside archive": extract text entries to a cache dir,
 *  exit the preview, and open the global search on the extracted content. */
internal fun MainActivity.previewSearch(tab: TabState) {
    val src = tab.previewSrc ?: return
    val format = tab.previewFormat
    val pwd = tab.previewPwd
    val entries = tab.previewEntries
    val opH = tryStartOperation(this, format)
    var cancelled = false
    val accessors = extractAccessors(format)
    val prog = PollingProgressDialog(
        this,
        getString(R.string.preparing_search),
        accessors,
        { n, b, t -> extractProgressMessage(this, n, b, t) },
        getString(R.string.action_cancel),
        { cancelled = true; accessors.cancel() },
        opH,
        tab
    )
    prog.start()
    thread {
        if (!opH.await()) return@thread
        try {
            val cacheDir = File(cacheDir, "archive_search/${src.nameWithoutExtension}_${src.parentFile?.absolutePath?.hashCode()?.toString(16) ?: "root"}")
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
            // Phase 1: touch placeholders for ALL entries (fast filename search).
            for (e in entries) {
                if (cancelled) break
                if (e.isDirectory) continue
                val rel = sanitizeEntryPath(e.path) ?: continue
                val f = File(cacheDir, rel)
                f.parentFile?.mkdirs()
                try { f.createNewFile() } catch (_: Exception) {}
            }
            // Phase 2: extract only text entries (content search).
            val textExts = TEXT_SEARCH_EXTS
            if (format == "rar") {
                extractByFormat(format, src.path, cacheDir.path, "", prefs, pwd)
            } else {
                for (e in entries) {
                    // 内层必须查 cancelled：每个新的 Rust 解压入口都会重清 CANCEL
                    // 标志，不查的话取消后照样解完剩余文本条目。
                    if (cancelled) break
                    if (e.isDirectory) continue
                    val rel = sanitizeEntryPath(e.path) ?: continue
                    val ext = e.path.substringAfterLast('.').lowercase()
                    if (ext !in textExts) continue
                    extractByFormat(format, src.path, cacheDir.path, rel, prefs, pwd)
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                prog.dismiss()
                if (cancelled) { toast(getString(R.string.msg_cancelled)); return@runOnUiThread }
                // Keep the in-tab preview alive: search opens on top, and closing
                // it returns to the preview (selection/expansion preserved on the
                // TabState). No exitPreview here.
                searchSourceArchive = src; searchSourceFormat = format
                searchSourceCacheBase = cacheDir
                searchSourcePassword = pwd
                searchSourceResolver = null
                globalSearch(cacheDir, tempDir = cacheDir)
            }
        } catch (e: Exception) {
            runOnUiThread { if (isFinishing || isDestroyed) return@runOnUiThread; prog.dismiss(); toast(getString(R.string.err_extract_io, e.message ?: "")) }
        } finally {
            opH.release()
        }
    }
}

/** In-preview ZIP entry management (delete selected / add from local file),
 *  mirroring the legacy dialog. */
internal fun MainActivity.previewZipManage(tab: TabState) {
    val src = tab.previewSrc ?: return
    val format = tab.previewFormat
    val pwd = tab.previewPwd
    val entries = tab.previewEntries
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.zip_manage))
        .setItems(arrayOf(
            getString(R.string.zip_manage_delete),
            getString(R.string.zip_manage_add))) { _, w ->
            when (w) {
                0 -> {
                    val sel = tab.previewSelected.filter { p -> tab.previewSelected.none { o -> o != p && o.startsWith(p + "/") } }
                        .filter { p -> entries.find { e -> e.path == p }?.isDirectory == false }
                    if (sel.isEmpty()) { toast(getString(R.string.msg_select_one)); return@setItems }
                    AlertDialog.Builder(this)
                        .setMessage(getString(R.string.zip_confirm_delete))
                        .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                            exitPreview(tab)
                            zipDeleteEntries(src, sel, pwd, tab)
                        }
                        .setNegativeButton(getString(R.string.action_cancel), null)
                        .show()
                }
                1 -> {
                    showFolderPicker(this, tab.currentDir, true) { picked ->
                        val baseName = picked.name
                        val inp = EditText(this).apply {
                            setText(baseName)
                            hint = getString(R.string.zip_add_name)
                            setTextColor(C["primary"]!!)
                            setHintTextColor(C["hint"]!!)
                            setBackgroundColor(C["surface"]!!)
                            setPadding(12, 8, 12, 8)
                        }
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.zip_add_title))
                            .setView(inp)
                            .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                                val name = inp.text.toString().trim().ifEmpty { baseName }
                                exitPreview(tab)
                                zipAddEntry(src, name, picked, pwd, tab)
                            }
                            .setNegativeButton(getString(R.string.action_cancel), null)
                            .show()
                    }
                }
            }
        }
        .setNegativeButton(getString(R.string.action_cancel), null)
        .show()
}

/** Rebuilds the in-tab preview list from the tab's entries + selection. */
internal fun MainActivity.syncPreview(tab: TabState) {
    if (!tab.viewsBound) return
    val src = tab.previewSrc ?: return
    tab.previewRoot.visibility = View.VISIBLE
    val adapter = PreviewAdapter(this, tab.previewEntries, tab.previewSelected, tab.previewExpanded,
        { entry -> previewFileEntry(src, entry, tab.previewFormat, tab.previewPwd, tab) },
        { updatePreviewStats(tab) })
    adapter.searchQuery = tab.previewSearchQuery
    tab.previewList.adapter = adapter
}

internal fun MainActivity.updatePreviewStats(tab: TabState) {
    val sel = tab.previewSelected.filter { p -> tab.previewSelected.none { o -> o != p && o.startsWith(p + "/") } }
    val selFiles = sel.count { p -> tab.previewEntries.find { e -> e.path == p }?.isDirectory == false }
    val totalFiles = tab.previewEntries.count { !it.isDirectory }
    val totalSize = tab.previewEntries.filter { !it.isDirectory }.sumOf { it.size }
    tab.tvPreviewStats.text = getString(R.string.preview_stats, totalFiles, fmt(totalSize), selFiles,
        fmt(sel.sumOf { p -> tab.previewEntries.find { e -> e.path == p }?.size ?: 0L }))
}

internal fun MainActivity.showPreviewDialog(src: File, entries: List<ArchiveEntry>, format: String, pwd: String = "", openKey: String? = null, ownerTab: TabState = activeTab) {
    val act = this
        val selectedPaths = mutableSetOf<String>()
        val expandedPaths = entries.filter { it.isDirectory }.map { it.path }.toMutableSet()

        val totalFiles = entries.count { !it.isDirectory }
        val totalSize = entries.filter { !it.isDirectory }.sumOf { it.size }
        val tvStats = TextView(this).apply {
            text = getString(R.string.preview_stats, totalFiles, fmt(totalSize), 0, fmt(0))
            setTextColor(C["tertiary_light"]!!); textSize = 12f
            setPadding(12, 8, 12, 4)
            setBackgroundColor(C["surface_dim"]!!)
        }

        val adapter = PreviewAdapter(this, entries, selectedPaths, expandedPaths, { entry ->
            previewFileEntry(src, entry, format, pwd, ownerTab)
        }, {
            val sel = selectedPaths.filter { p -> selectedPaths.none { o -> o != p && o.startsWith(p + "/") } }
            val selFiles = sel.count { p -> entries.find { e -> e.path == p }?.isDirectory == false }
            val selSize = sel.sumOf { p -> entries.find { e -> e.path == p }?.size ?: 0L }
            tvStats.text = getString(R.string.preview_stats, totalFiles, fmt(totalSize), selFiles, fmt(selSize))
        })

        val listView = ListView(this).apply {
            this.adapter = adapter
            setBackgroundColor(C["surface"]!!)
            divider = ColorDrawable(C["surface_dark"]!!)
            dividerHeight = 1
            enableFastScroll()
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tvStats, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(listView, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }

        // Custom title bar with search button at top-right
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 14, 8, 14)
            setBackgroundColor(C["surface"]!!)
        }
        titleBar.addView(TextView(this).apply {
            text = getString(R.string.preview_title, src.name)
            setTextColor(C["primary"]!!); textSize = 17f
            // Ellipsize so a long filename can't push the search / edit /
            // manage buttons off a narrow screen.
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })

        // Must declare dlg before btnSearchTitle so its lambda can capture it
        lateinit var dlg: AlertDialog
        val btnSearchTitle = ImageButton(this).apply {
            setImageResource(R.drawable.ic_search)
            setBackgroundColor(C["surface"]!!)
            setPadding(8, 4, 8, 4)
            scaleType = ImageView.ScaleType.FIT_XY
            layoutParams = LinearLayout.LayoutParams(64, 48)
            setOnClickListener {
                val cacheDir = File(cacheDir, "archive_search/${src.nameWithoutExtension}_${src.parentFile?.absolutePath?.hashCode()?.toString(16) ?: "root"}")
                // Lock first, then the dual progress dialog (the text-extraction
                // phase reports byte progress); the work dir is cleared INSIDE
                // the lock so a queued second search can't nuke a running one.
                val opH = tryStartOperation(act, format)
                var cancelled = false
                val accessors = extractAccessors(format)
                val prog = PollingProgressDialog(
                    act,
                    getString(R.string.preparing_search),
                    accessors,
                    { n, b, t -> extractProgressMessage(act, n, b, t) },
                    getString(R.string.action_cancel),
                    { cancelled = true; accessors.cancel() },
                    opH,
                    ownerTab
                )
                prog.start()
                thread {
                    if (!opH.await()) return@thread
                    try {
                        cacheDir.deleteRecursively()
                        cacheDir.mkdirs()
                        // Phase 1: touch empty placeholder files for ALL entries (fast, for filename search)
                        for (e in entries) {
                            if (cancelled) break
                            if (e.isDirectory) continue
                            // Same rules as the Rust safe_join: reject ../
                            // absolute paths / drive letters before touching
                            // the filesystem.
                            val rel = sanitizeEntryPath(e.path) ?: continue
                            val f = File(cacheDir, rel)
                            f.parentFile?.mkdirs()
                            try { f.createNewFile() } catch (_: Exception) {}
                        }
                        // Phase 2: extract only text files (overwrites placeholders, for content search).
                        // RAR indexes entries by re-parsing the whole archive header, so extracting
                        // every text entry individually re-parses it N times (a 1GB+ rar = minutes of
                        // CPU + a long-held lock). Extract the archive ONCE and let the text filter
                        // pick from the cache — identical output, far faster.
                        val textExts = TEXT_SEARCH_EXTS
                        if (format == "rar") {
                            extractByFormat(format, src.path, cacheDir.path, "", prefs, pwd)
                        } else {
                            for (e in entries) {
                                // 内层必须查 cancelled：每个新的 Rust 入口都会重清 CANCEL 标志。
                                if (cancelled) break
                                if (e.isDirectory) continue
                                val rel = sanitizeEntryPath(e.path) ?: continue
                                val ext = e.path.substringAfterLast('.').lowercase()
                                if (ext !in textExts) continue
                                extractByFormat(format, src.path, cacheDir.path, rel, prefs, pwd)
                            }
                        }
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            prog.dismiss()
                            if (cancelled) { toast(getString(R.string.msg_cancelled)); return@runOnUiThread }
                            // searchSource* 只在成功路径赋值：取消/失败不得污染
                            // 全局搜索的归档源/解析器映射。
                            searchSourceArchive = src; searchSourceFormat = format
                            searchSourceCacheBase = cacheDir
                            searchSourcePassword = pwd
                            searchSourceResolver = null
                            dlg.dismiss()
                            globalSearch(cacheDir, tempDir = cacheDir)
                        }
                    } catch (e: Exception) {
                        runOnUiThread { if (isFinishing || isDestroyed) return@runOnUiThread; prog.dismiss(); toast(getString(R.string.err_extract_io, e.message ?: "")) }
                    } finally {
                        opH.release()
                    }
                }
            }
        }
        titleBar.addView(btnSearchTitle)

        val btnEditArchive = Button(this).apply {
            text = getString(R.string.edit_archive)
            textSize = 14f
            isAllCaps = false
            setTextColor(C["accent"]!!)
            background = null
            setPadding(12, 8, 12, 8)
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
            setOnClickListener {
                if (format !in setOf("xp3", "pfs", "iso", "nsa", "7z", "ypf")) {
                    toast(getString(R.string.edit_only_pack))
                } else {
                    dlg.dismiss()
                    startEditArchive(src, format, pwd)
                }
            }
        }
        titleBar.addView(btnEditArchive)

        // ZIP entry management: delete selected / add a new entry (in-place via
        // zipModify). Split-volume zips can't be modified, so hide the button.
        if (format == "zip" && !(isVolumeFile(src) == "zip" || isZipVolumeName(src.name))) {
            val btnZipManage = Button(this).apply {
                text = getString(R.string.zip_manage)
                textSize = 14f
                isAllCaps = false
                setTextColor(C["accent"]!!)
                background = null
                setPadding(12, 8, 12, 8)
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
                setOnClickListener {
                    AlertDialog.Builder(this@showPreviewDialog)
                        .setTitle(getString(R.string.zip_manage))
                        .setItems(arrayOf(
                            getString(R.string.zip_manage_delete),
                            getString(R.string.zip_manage_add))) { _, w ->
                            when (w) {
                                0 -> {
                                    val sel = selectedPaths.filter { p -> selectedPaths.none { o -> o != p && o.startsWith(p + "/") } }
                                        .filter { p -> entries.find { e -> e.path == p }?.isDirectory == false }
                                    if (sel.isEmpty()) { toast(getString(R.string.msg_select_one)); return@setItems }
                                    AlertDialog.Builder(this@showPreviewDialog)
                                        .setMessage(getString(R.string.zip_confirm_delete))
                                        .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                                            dlg.dismiss()
                                            zipDeleteEntries(src, sel, pwd, ownerTab)
                                        }
                                        .setNegativeButton(getString(R.string.action_cancel), null)
                                        .show()
                                }
                                1 -> {
                                    // Pick a local file, then name the entry.
                                    showFolderPicker(this@showPreviewDialog, currentDir, true) { picked ->
                                        val baseName = picked.name
                                        val inp = EditText(this@showPreviewDialog).apply {
                                            setText(baseName)
                                            hint = getString(R.string.zip_add_name)
                                            setTextColor(C["primary"]!!)
                                            setHintTextColor(C["hint"]!!)
                                            setBackgroundColor(C["surface"]!!)
                                            setPadding(12, 8, 12, 8)
                                        }
                                        AlertDialog.Builder(this@showPreviewDialog)
                                            .setTitle(getString(R.string.zip_add_title))
                                            .setView(inp)
                                            .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                                                val name = inp.text.toString().trim().ifEmpty { baseName }
                                                dlg.dismiss()
                                                zipAddEntry(src, name, picked, pwd, ownerTab)
                                            }
                                            .setNegativeButton(getString(R.string.action_cancel), null)
                                            .show()
                                    }
                                }
                            }
                        }
                        .setNegativeButton(getString(R.string.action_cancel), null)
                        .show()
                }
            }
            titleBar.addView(btnZipManage)
        }

        // ISO previews can be converted straight to PSP CISO without leaving
        // the dialog (CSO files themselves reach the converter via their FAB).
        if (format == "iso") {
            val btnConvertIso = Button(this).apply {
                text = getString(R.string.action_convert_cso)
                textSize = 14f
                isAllCaps = false
                setTextColor(C["accent"]!!)
                background = null
                setPadding(12, 8, 12, 8)
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
                setOnClickListener {
                    dlg.dismiss()
                    convertIso(src, toCso = true)
                }
            }
            titleBar.addView(btnConvertIso)
        }

        dlg = AlertDialog.Builder(this)
            .setCustomTitle(titleBar)
            .setView(layout)
            .setPositiveButton(getString(R.string.extract_selected), null)
            .setNeutralButton(getString(R.string.extract_all), null)
            .setNegativeButton(getString(R.string.action_cancel), null)
            .create()
        // Honor/EMUI touch-state bug: a dismissed dialog (its fast-scroll list)
        // can leave the ViewPager2 unable to intercept horizontal swipes, so tab
        // switching dies until restart. Reset the pager's input state on dismiss.
        resetPagerInputOnDialogDismiss(dlg) { openKey?.let { OpenArchiveRegistry.unregister(it) } }
        // Preview should not hide the tab strip: bottom-align the dialog below
        // status bar + toolbar + tab bar so the user can still tap a different
        // window while an archive preview is open.
        dlg.window?.let { w ->
            val dm = resources.displayMetrics
            val density = dm.density
            val tabBarH = 50f * density    // tab bar height (now at the very top)
            val toolbarH = 56f * density   // ?attr/actionBarSize (Material default)
            val swipeArea = 130f * density // keep this much ViewPager visible for swiping windows
            w.setGravity(Gravity.BOTTOM)
            val sheetW = minOf(dm.widthPixels, resources.getDimensionPixelSize(R.dimen.dialog_max_width))
            w.setLayout(sheetW, (dm.heightPixels - tabBarH - toolbarH - swipeArea).toInt().coerceAtLeast(0))
            // Local dim: dim only the window's own area (tab bar + toolbar stay
            // bright and tappable above it) — system FLAG_DIM_BEHIND would dim the
            // whole screen including the tab strip.
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x99000000.toInt()))
        }
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val sel = selectedPaths.filter { p -> selectedPaths.none { o -> o != p && o.startsWith(p + "/") } }
                if (sel.isEmpty()) {
                    toast(getString(R.string.msg_select_one))
                } else {
                    dlg.dismiss()
                    showOutputDirDialog(src, sel, format, pwd, ownerTab)
                }
            }
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                dlg.dismiss()
                extractAll(uniqueFile(src.parentFile ?: return@setOnClickListener, src.nameWithoutExtension), src, format, pwd, ownerTab)
            }
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(C["accent"]!!)
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(C["tertiary"]!!)
        }
        dlg.show()
    }

internal fun MainActivity.showOutputDirDialog(src: File, selectedPaths: List<String>, format: String, pwd: String = "", ownerTab: TabState = activeTab) {
        val parent = src.parentFile ?: return
        val outDir = uniqueFile(parent, src.nameWithoutExtension)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_extract_to))
            .setItems(arrayOf(getString(R.string.extract_new_folder, getString(R.string.title_new_folder), outDir.name), getString(R.string.action_extract), getString(R.string.action_choose_dir), getString(R.string.merge_into_archive))) { _, w ->
                when (w) {
                    0 -> extractSelected(src, outDir, selectedPaths, format, ownerTab)
                    1 -> extractSelected(src, parent, selectedPaths, format, ownerTab)
                    2 -> showFolderPicker(this, parent) { picked ->
                        extractSelected(src, picked, selectedPaths, format, ownerTab)
                    }
                    3 -> showMergeTargetPicker(src, selectedPaths, format, pwd, ownerTab)
                }
            }.setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

/**
 * Cross-archive merge: pick a target archive in the current directory, then
 * add the source preview's selected entries into it. Implemented as
 * extract → merge dirs → repack into a `目标-cn.ext` copy (original untouched).
 */
internal fun MainActivity.showMergeTargetPicker(src: File, selectedPaths: List<String>, format: String, pwd: String, ownerTab: TabState) {
    // List candidates in the OWNING tab's directory (not the active tab's) —
    // the picker can be invoked from a background tab's preview.
    val dir = ownerTab.currentDir
    val candidates = dir.listFiles()?.filter {
        it.isFile && it !== src && isArchiveFile(it)
    }?.sortedBy { it.name.lowercase() } ?: emptyList()
    if (candidates.isEmpty()) {
        toast(getString(R.string.merge_no_target))
        return
    }
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.merge_select_target))
        .setItems(candidates.map { it.name }.toTypedArray()) { _, w ->
            val target = candidates[w]
            val targetFmt = detectFormat(target) ?: detectFormatByMagic(target)
            // Only formats that can be repacked are mergeable targets
            // (zip/7z/tar + xp3/pfs/nsa/iso/ypf).
            val mergeable = MERGE_COMPRESS_GROUPS.flatMap { it.second }.toSet()
            if (targetFmt == null || targetFmt !in mergeable) {
                toast(getString(R.string.merge_target_unsupported))
                return@setItems
            }
            mergeIntoArchive(src, selectedPaths, format, pwd, target, targetFmt, ownerTab)
        }
        .setNegativeButton(getString(R.string.action_cancel), null)
        .show()
}

/**
 * Extracts the source preview's selected entries, unpacks the target archive,
 * overlays them into one staging dir, and repacks into `目标-cn.ext`. Runs under
 * the OpScheduler ("merge" key) with the dual progress dialog; source/target are
 * both held by OpenArchiveRegistry so a second window can't race the same archives.
 */
internal fun MainActivity.mergeIntoArchive(
    src: File, selectedPaths: List<String>, format: String, pwd: String,
    target: File, targetFmt: String, ownerTab: TabState = activeTab
) {
    // Same-archive mutex on the TARGET: another window holding it must not
    // repack a stale copy under it. Compare against the ORIGIN tab, not the
    // transiently-active one — the dialog is activity-level and the user can
    // switch windows before tapping.
    val targetKey = archiveKey(target)
    val targetOwner = OpenArchiveRegistry.owner(targetKey)
    if (targetOwner != null && targetOwner !== ownerTab) {
        toast(getString(R.string.msg_archive_open_in_tab, tabTitle(targetOwner)))
        return
    }
    val parent = target.parentFile ?: ownerTab.currentDir
    val outF = uniqueFile(parent, "${target.nameWithoutExtension}-cn.${target.extension.ifEmpty { targetFmt }}")
    val stageDir = File(cacheDir, "merge/${target.nameWithoutExtension}")
    var cancelled = false
    val accessors = extractAccessors(targetFmt)
    // 重封包阶段由压缩进度静态驱动，取消标志是另一份——不一起点火的话，
    // 重包期间点取消是空操作，-cn 文件照样生成却提示「已取消」。
    val compressAcc = compressAccessors(targetFmt)
    // Built once a scheduler handle exists (see runOnUiThread below) so the
    // dialog can render its queued state.
    lateinit var prog: PollingProgressDialog
    thread {
        // Encrypted targets ask once — BEFORE the lock is taken: the modal
        // prompt can block up to 30s and would freeze every other window's
        // operations with bogus "busy" toasts.
        var tgtPwd = ""
        if (targetFmt in setOf("zip", "7z", "rar") && runCatching { isPasswordProtected(target) }.getOrDefault(false)) {
            tgtPwd = promptPasswordSync(this) ?: run { runOnUiThread { toast(getString(R.string.msg_cancelled)) }; return@thread }
        }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            // Pseudo-key "merge": this flow drives TWO formats' Rust statics
            // (source extract + target extract + repack), so merges serialize
            // among themselves; brief display crosstalk with a concurrent
            // same-fmt extraction is cosmetic (cancel flags are cleared at
            // every Rust entry).
            val opH = tryStartOperation(this, "merge")
            prog = PollingProgressDialog(
                this,
                getString(R.string.merge_title, target.name),
                accessors,
                { n, b, t -> extractProgressMessage(this, n, b, t) },
                getString(R.string.action_cancel),
                { cancelled = true; accessors.cancel(); compressAcc.cancel() },
                opH,
                ownerTab
            )
            prog.start()
            thread {
                if (!opH.await()) return@thread
                try {
                    stageDir.deleteRecursively(); stageDir.mkdirs()
                    // 1. Extract source's selected entries.
                    val selStr = selectedPaths.joinToString("\n")
                    val srcOutcome = if (selStr.isEmpty()) ExtractOutcome(ExtractCounts(0, 0, 0), null)
                        else extractByFormat(format, src.path, stageDir.path, selStr, prefs, pwd)
                    if (cancelled) return@thread
                    // 2. Unpack the target archive into the same staging dir (its
                    //    existing entries stay; source entries with a matching path
                    //    overwrite them — merge intent).
                    val tgtOutcome = extractByFormat(targetFmt, target.path, stageDir.path, "", prefs, tgtPwd)
                    if (cancelled) return@thread
                    // 3. Repack into a -cn copy (never overwrite the original target).
                    val ok = compressDispatch(stageDir, outF, targetFmt, prefs.getInt("generic_level", 6), "", prefs)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        prog.dismiss()
                        if (cancelled) toast(getString(R.string.msg_cancelled))
                        else if (ok) toast(getString(R.string.merge_done, outF.name))
                        else toast(getString(R.string.title_compress_failed))
                    }
                } catch (e: Exception) {
                    runOnUiThread { if (isFinishing || isDestroyed) return@runOnUiThread; prog.dismiss(); toast(getString(R.string.err_extract_io, e.message ?: "")) }
                } finally {
                    stageDir.deleteRecursively()
                    opH.release()
                }
            }
        }
    }
}

/** 编辑 minimal loop: full extract to a cache dir → script list → in-place
 *  edit (showTextEditor) → repack into a new "…-cn.xp3/pfs" alongside the
 *  original. The working dir persists while the script list is open, so edits
 *  accumulate until the user taps 封回; re-entering 编辑 re-extracts fresh. */
internal fun MainActivity.startEditArchive(src: File, format: String, pwd: String, ownerTab: TabState = activeTab) {
    val act = this
    // Same-archive mutex: never let two windows edit the same archive (each
    // would write its own -cn copy / repack from the same source).
    val openKey = archiveKey(src)
    val conflict = OpenArchiveRegistry.owner(openKey)
    if (conflict != null && conflict !== activeTab) {
        toast(getString(R.string.msg_archive_open_in_tab, tabTitle(conflict)))
        val idx = tabs.indexOf(conflict)
        if (idx >= 0) viewPager.currentItem = idx
        return
    }
    val editDir = File(cacheDir, "edit/${src.nameWithoutExtension}")
    // Lock first, then the dual progress dialog (see extractAll); the work dir
    // is cleared INSIDE the lock so a queued re-edit can't destroy an active one.
    val opH = tryStartOperation(this, format)
    OpenArchiveRegistry.register(openKey, activeTab)
    var cancelled = false
    val accessors = extractAccessors(format)
    val prog = PollingProgressDialog(
        this,
        "${getString(R.string.edit_archive_title)} — ${src.name}",
        accessors,
        { n, b, t -> extractProgressMessage(this, n, b, t) },
        getString(R.string.action_cancel),
        { cancelled = true; accessors.cancel() },
        opH,
        ownerTab
    )
    prog.start()
    thread {
        // A queued-cancel must release the registry entry too — it was taken
        // eagerly at enqueue time and no other exit path runs below.
        if (!opH.await()) { OpenArchiveRegistry.unregister(openKey); return@thread }
        try {
            editDir.deleteRecursively()
            editDir.mkdirs()
            val o = extractByFormat(format, src.path, editDir.path, "", prefs, pwd)
            val scripts = editDir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in EDIT_SCRIPT_EXTS }
                .sortedBy { it.name.lowercase() }
                .toList()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                prog.dismiss()
                if (cancelled) { OpenArchiveRegistry.unregister(openKey); toast(getString(R.string.msg_cancelled)); return@runOnUiThread }
                if (!o.counts.ok) { OpenArchiveRegistry.unregister(openKey); toast(friendlyExtractError(act, o.error)); return@runOnUiThread }
                if (scripts.isEmpty()) { OpenArchiveRegistry.unregister(openKey); toast(getString(R.string.edit_no_scripts)); return@runOnUiThread }
                showEditScriptList(src, format, editDir, scripts, openKey)
            }
        } catch (e: Exception) {
            OpenArchiveRegistry.unregister(openKey)
            runOnUiThread { if (isFinishing || isDestroyed) return@runOnUiThread; prog.dismiss(); toast(getString(R.string.err_extract_io, e.message ?: "")) }
        } finally {
            opH.release()
        }
    }
}

private fun MainActivity.showEditScriptList(src: File, format: String, editDir: File, scripts: List<File>, openKey: String) {
    val list = ListView(this).apply {
        adapter = object : BaseAdapter() {
            override fun getCount() = scripts.size
            override fun getItem(pos: Int): Any = scripts[pos]
            override fun getItemId(pos: Int) = pos.toLong()
            override fun getView(pos: Int, v: View?, p: ViewGroup?): View {
                val row = v ?: layoutInflater.inflate(R.layout.item_folder_row, p, false)
                row.findViewById<TextView>(R.id.folder_row_name).text =
                    scripts[pos].relativeTo(editDir).path
                return row
            }
        }
        // Editing happens in a modal editor over this dialog — the dialog stays
        // alive so the user can edit several scripts, then tap 封回 once.
        setOnItemClickListener { _, _, pos, _ -> showTextEditor(this@showEditScriptList, scripts[pos]) }
        divider = android.graphics.drawable.ColorDrawable(C["surface_dark"]!!)
        dividerHeight = 1
        enableFastScroll()
    }
    val builder = AlertDialog.Builder(this)
        .setTitle(getString(R.string.edit_archive_title))
        .setView(list)
        .setPositiveButton(getString(R.string.edit_repack)) { _, _ -> repackEditedArchive(src, format, editDir) }
        .setNegativeButton(getString(R.string.action_cancel), null)
    lateinit var dlg: AlertDialog
    // Search the whole extracted working folder (the archive is already fully
    // unpacked into editDir, so results are real files — tap one to preview/edit,
    // then Repack as usual).
    builder.setNeutralButton(getString(R.string.action_search)) { _, _ ->
        dlg.dismiss()
        globalSearch(editDir)
    }
    dlg = builder.create()
    dlg.setOnDismissListener { OpenArchiveRegistry.unregister(openKey) }
    val metrics = resources.displayMetrics
    val (pw, ph) = cappedDialogSize(0.92f, 0.8f)
    dlg.window?.setLayout(pw, ph)
    dlg.show()
}

private fun MainActivity.repackEditedArchive(src: File, format: String, editDir: File, ownerTab: TabState = activeTab) {
    val parent = src.parentFile ?: return
    val ext = src.extension.ifEmpty { format }
    val outF = uniqueFile(parent, "${src.nameWithoutExtension}-cn.$ext")
    val opH = tryStartOperation(this, format)
    var cancelled = false
    val accessors = compressAccessors(format)
    val prog = PollingProgressDialog(
        this,
        "${getString(R.string.edit_repack)} — ${outF.name}",
        accessors,
        { n, b, t -> compressProgressMessage(this, n, b, t) },
        getString(R.string.action_cancel),
        { cancelled = true; accessors.cancel() },
        opH,
        ownerTab
    )
    prog.start()
    thread {
        if (!opH.await()) return@thread
        try {
            val ok = when (format) {
                "xp3" -> Xp3Core.xp3CreateArchive("", editDir.path, outF.path, prefs.getInt("generic_level", 6).toString()) != null
                "nsa" -> NsaCore.nsaCreateArchive("", editDir.path, outF.path, "2") != null
                "iso" -> IsoCore.isoCreateArchive("", editDir.path, outF.path) != null
                "ypf" -> YpfCore.ypfCreateArchive("", editDir.path, outF.path, prefs.getInt("generic_level", 6).toString()) != null
                "7z" -> {
                    val pwEnabled = prefs.getBoolean("compress_password_enabled", false)
                    val password = if (pwEnabled) prefs.getString("compress_password", "") ?: "" else ""
                    compressDispatch(editDir, outF, "7z", prefs.getInt("sz_level", 6), password, prefs)
                }
                "pf6" -> PfsCore.pfsCreateArchivePf6("", editDir.path, outF.path) != null
                else -> PfsCore.pfsCreateArchive("", editDir.path, outF.path) != null
            }
            // PFS/PF6 封包产物按 Artemis 约定自动更名 root.pfs(.NNN)
            var pfsRenamed: String? = null
            if (ok && !cancelled && format in setOf("pfs", "pf6")) {
                applyPfsRootNaming(outF)?.let { pfsRenamed = it.name }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                prog.dismiss()
                if (cancelled) toast(getString(R.string.msg_cancelled))
                else if (ok) {
                    pfsRenamed?.let { toast(getString(R.string.pfs_auto_renamed, it)) }
                    toast(getString(R.string.edit_done, pfsRenamed ?: outF.name))
                    nav(currentDir)
                }
                else toast(getString(R.string.title_compress_failed))
            }
        } finally {
            opH.release()
        }
    }
}

internal fun MainActivity.extractSelected(src: File, out: File, paths: List<String>, format: String, ownerTab: TabState = activeTab) {
        val selStr = paths.joinToString("\n")
        if (selStr.isEmpty()) { toast(getString(R.string.msg_select_one)); return }
        val existedBefore = out.exists()
        if (format !in setOf("zip", "7z", "rar")) {
            tryExtractWithPassword(this, format, src.path, out.path, selStr, prefs,
                onCancel = {
                    cleanupCancelledOutput(out, existedBefore)
                    toast(getString(R.string.msg_cancelled))
                },
                onResult = { o ->
                    // 失败也要清残留：zip/7z/rar 分支清了，非 zip 分支不清会让
                    // 半成品目录留在归档旁。
                    if (o.counts.ok) { showExtractSuccess(src.name, out.name, o.counts); navTab(ownerTab, ownerTab.currentDir) }
                    else { cleanupCancelledOutput(out, existedBefore); toast(friendlyExtractError(this, o.error)) }
                },
                ownerTab = ownerTab
            )
            return
        }
        // zip/7z/rar: prompt for the password up front, and on failure offer a retry dialog.
        fun doSel(p: String): ExtractOutcome = runCatching {
            when (format) {
                "zip" -> ExtractOutcome(ExtractCounts.fromJson(zipExtractDispatch(src.path, out.path, selStr, p)), null)
                "7z" -> ExtractOutcome(ExtractCounts.fromJson(szExtractDispatch(src.path, out.path, selStr, p)), null)
                else -> ExtractOutcome(ExtractCounts.fromJson(rarExtractDispatch(src.path, out.path, selStr, p)), null)
            }
        }.getOrElse { e -> ExtractOutcome(ExtractCounts(0, 0, 0), e.message) }
        thread {
            // Password is resolved BEFORE the lock is taken — the modal prompt
            // blocks up to 30s and would freeze all other windows with bogus
            // "busy" toasts while it waits.
            var pwd = ""
            if (runCatching { isPasswordProtected(src) }.getOrDefault(false)) {
                val entered = promptPasswordSync(this)
                if (entered == null) {
                    runOnUiThread { toast(getString(R.string.msg_cancelled)) }
                    return@thread
                }
                pwd = entered
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // Lock first, then the progress dialog (see extractAll).
                val opH = tryStartOperation(this, format)
                var cancelled = false
                val accessors = extractAccessors(format)
                val prog = PollingProgressDialog(
                    this,
                    "${src.name} → ${out.name}",
                    accessors,
                    { n, b, t -> extractProgressMessage(this, n, b, t) },
                    getString(R.string.action_cancel),
                    { cancelled = true; accessors.cancel() },
                    opH,
                    ownerTab
                )
                prog.start()
                thread {
                    if (!opH.await()) return@thread
                    try {
                        val result = doSel(pwd)
                        val ok = result.counts.ok
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            prog.dismiss()
                            if (cancelled) { cleanupCancelledOutput(out, existedBefore); toast(getString(R.string.msg_cancelled)) }
                            else if (ok) { showExtractSuccess(src.name, out.name, result.counts); navTab(ownerTab, ownerTab.currentDir) }
                            else {
                                cleanupCancelledOutput(out, existedBefore)
                                val inp = EditText(this).apply {
                                    hint = getString(R.string.prompt_password)
                                    setTextColor(C["primary"]!!); setHintTextColor(C["hint"]!!)
                                    setBackgroundColor(C["surface"]!!); setPadding(12, 8, 12, 8)
                                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                                }
                                AlertDialog.Builder(this)
                                    .setTitle(getString(R.string.title_password))
                                    .setMessage(getString(R.string.retry))
                                    .setView(inp)
                                    .setPositiveButton(getString(R.string.retry)) { _, _ ->
                                        val p2 = inp.text.toString()
                                        val opH2 = tryStartOperation(this, format)
                                        // 重试也走进度卡：此前重试完全隐身且不可取消，
                                        // 大包重试期间用户毫无反馈。
                                        var cancelled2 = false
                                        val accessors2 = extractAccessors(format)
                                        val prog2 = PollingProgressDialog(
                                            this,
                                            "${src.name} → ${out.name}",
                                            accessors2,
                                            { n, b, t -> extractProgressMessage(this, n, b, t) },
                                            getString(R.string.action_cancel),
                                            { cancelled2 = true; accessors2.cancel() },
                                            opH2,
                                            ownerTab
                                        )
                                        prog2.start()
                                        thread {
                                            if (!opH2.await()) return@thread
                                            try {
                                                val o2 = doSel(p2)
                                                runOnUiThread {
                                                    if (isFinishing || isDestroyed) return@runOnUiThread
                                                    prog2.dismiss()
                                                    if (cancelled2) { cleanupCancelledOutput(out, existedBefore); toast(getString(R.string.msg_cancelled)) }
                                                    else if (o2.counts.ok) { showExtractSuccess(src.name, out.name, o2.counts); navTab(ownerTab, ownerTab.currentDir) }
                                                    else { cleanupCancelledOutput(out, existedBefore); toast(friendlyExtractError(this, o2.error)) }
                                                }
                                            } finally {
                                                opH2.release()
                                            }
                                        }
                                    }
                                    .setNegativeButton(getString(R.string.action_cancel)) { _, _ -> cleanupCancelledOutput(out, existedBefore) }
                                    .show()
                            }
                        }
                    } finally {
                        opH.release()
                    }
                }
            }
        }
    }

internal fun MainActivity.previewFileEntry(archive: File, entry: ArchiveEntry, format: String, pwd: String = "", ownerTab: TabState = activeTab) {
        val ext = entry.path.substringAfterLast('.').lowercase()
        // Nested archive inside the current one (e.g. a .zip living inside an
        // .xp3): offer to open it in a NEW window so the outer preview is kept.
        val nestedFmt = formatOfName(entry.path)
        if (nestedFmt != null && nestedFmt in setOf("zip", "7z", "rar", "xp3", "pfs", "nsa", "iso", "ypf")) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.nested_archive_title))
                .setMessage(getString(R.string.nested_archive_msg, entry.path, archive.name))
                .setPositiveButton(getString(R.string.nested_archive_open)) { _, _ ->
                    openNestedArchive(archive, entry, format, pwd, nestedFmt)
                }
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show()
            return
        }
        if (ext !in PREVIEW_EXTS) {
            toast(getString(R.string.err_preview_unsupported, ".$ext"))
            return
        }

        val cacheDir = File(cacheDir, "preview/${archive.nameWithoutExtension}")
        fun openPreview() {
            val extracted = File(cacheDir, entry.path)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (ext) {
                    "jpg", "jpeg", "png", "gif", "webp", "bmp" -> showImagePreview(this, extracted)
                    "mp3", "ogg", "wav", "aac", "flac", "aif", "aiff", "m4a" -> playAudio(this, extracted)
                    "mp4", "mkv", "avi", "mov", "webm" -> playVideo(this, extracted)
                    // ZIP entries can be edited in place (zipModify replaces just
                    // that entry); split-volume zips can't (zipModify needs a
                    // single seekable archive and can't re-split), so no 编辑.
                    else -> {
                        val isSplitZip = format == "zip" &&
                            (isVolumeFile(archive) == "zip" || isZipVolumeName(archive.name))
                        val canEditZip = format == "zip" && entry.path.isNotEmpty() && !isSplitZip
                        showTextPreview(this, extracted, showEdit = canEditZip,
                            onEdited = if (canEditZip) { { zipReplaceEntry(archive, entry, extracted, pwd, ownerTab) } } else { null })
                    }
                }
            }
        }
        if (format in setOf("zip", "7z", "rar")) {
            // Password detection reads the central directory via JNI — do it off
            // the UI thread (a multi-GB archive would otherwise jank/ANR).
            thread {
                val needsPw = isPasswordProtected(archive)
                runOnUiThread {
                    // 密码嗅探可达数秒，期间用户可能已退出——弹窗前必须守卫。
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (needsPw && pwd.isEmpty()) {
                        // No password yet (archive opened via a path that didn't prompt) — ask first.
                        showPasswordDialog(this, format, archive.path, cacheDir.path, entry.path, showProgress = true,
                            onResult = { o -> if (o.counts.ok) openPreview() else toast(friendlyExtractError(this, o.error)) },
                            ownerTab = ownerTab
                        )
                    } else {
                        tryExtractWithPassword(this, format, archive.path, cacheDir.path, entry.path, prefs, showProgress = true, initialPassword = pwd,
                            onResult = { o -> if (o.counts.ok) openPreview() else toast(friendlyExtractError(this, o.error)) },
                            ownerTab = ownerTab
                        )
                    }
                }
            }
        } else {
            tryExtractWithPassword(this, format, archive.path, cacheDir.path, entry.path, prefs, showProgress = true, initialPassword = pwd,
                onResult = { o -> if (o.counts.ok) openPreview() else toast(friendlyExtractError(this, o.error)) },
                ownerTab = ownerTab
            )
        }
    }

    /**
     * Lists an archive's entries for the in-tab preview, mirroring the dispatch
     * used when first opening an archive. Returns null when the archive can't be
     * read (wrong format / encrypted without password).
     */
    internal fun MainActivity.listPreviewEntries(format: String, src: File, pwd: String): List<ArchiveEntry>? {
        val json = try { when (format) {
            "xp3" -> Xp3Core.xp3ListEntries(src.absolutePath)
            "pfs" -> PfsCore.pfsListEntries(src.absolutePath)
            "nsa" -> NsaCore.nsaListEntries(src.absolutePath)
            "iso" -> IsoCore.isoListEntries(src.absolutePath)
            "ypf" -> YpfCore.ypfListEntries(src.absolutePath)
            "zip" -> { ZipCore.zipSetEncoding(prefs.getString("zip_encoding", "UTF-8") ?: "UTF-8")
                val vols = resolveZipVolumes(src)
                if (vols.size > 1) ZipCore.zipListEntriesVolumes(volumeJoin(vols)) else ZipCore.zipListEntries(src.absolutePath) }
            "7z" -> { val vols = resolveSevenZVolumes(src)
                if (vols.size > 1) (if (pwd.isNotEmpty()) SevenZCore.szListEntriesVolumesWithPassword(volumeJoin(vols), pwd) else SevenZCore.szListEntriesVolumes(volumeJoin(vols)))
                else if (pwd.isNotEmpty()) SevenZCore.szListEntriesWithPassword(src.absolutePath, pwd) else SevenZCore.szListEntries(src.absolutePath) }
            "rar" -> { val vols = resolveRarVolumes(src)
                if (vols.size > 1) (if (pwd.isNotEmpty()) RarCore.rarListEntriesVolumesWithPassword(volumeJoin(vols), pwd) else RarCore.rarListEntriesVolumes(volumeJoin(vols)))
                else if (pwd.isNotEmpty()) RarCore.rarListEntriesWithPassword(src.absolutePath, pwd) else RarCore.rarListEntries(src.absolutePath) }
            else -> null
        } } catch (_: Exception) { null }
        if (json == null || json == "[]") return null
        return parseEntries(json)
    }

    /**
     * Extracts a nested archive entry out of its parent and opens it for preview
     * in a brand-new window (new tab), auto-jumping to that window. The outer
     * preview stays intact so the user can swipe back to it.
     */
    internal fun MainActivity.openNestedArchive(
        archive: File, entry: ArchiveEntry, parentFmt: String, parentPwd: String, nestedFmt: String
    ) {
        val outDir = File(cacheDir, "nested/${archive.nameWithoutExtension}_${entry.path.hashCode().toString(16)}")
        outDir.mkdirs()
        val extracted = File(outDir, entry.path)
        val openKey = "nested:" + extracted.absolutePath
        tryExtractWithPassword(this, parentFmt, archive.path, outDir.path, entry.path, prefs,
            showProgress = true, initialPassword = parentPwd,
            onResult = { o ->
            // 每个晚到的失败路径都要清掉已解出的嵌套包：cacheDir/nested 无任何
            // 其他清理机制，漏删就是永久孤儿（直到系统清缓存）。
            if (!o.counts.ok) { outDir.deleteRecursively(); toast(friendlyExtractError(this, o.error)); return@tryExtractWithPassword }
            if (!extracted.exists()) {
                outDir.deleteRecursively()
                toast(getString(R.string.err_preview_unsupported, ""))
                return@tryExtractWithPassword
            }
            // Listing a multi-GB nested archive is a slow JNI call — keep it
            // off the UI thread (this callback runs on it).
            thread {
                val entries = listPreviewEntries(nestedFmt, extracted, "")
                runOnUiThread {
                    if (isFinishing || isDestroyed) { outDir.deleteRecursively(); return@runOnUiThread }
                    if (entries.isNullOrEmpty()) {
                        outDir.deleteRecursively()
                        toast(getString(R.string.err_cannot_read_maybe_pwd))
                        return@runOnUiThread
                    }
                    val before = tabs.size
                    addTab()
                    if (tabs.size == before) { outDir.deleteRecursively(); return@runOnUiThread  // max tabs reached
                    }
                    val newTab = tabs.last()
                    // 与 previewArchive 相同的注册竞态处理：抢注失败（他窗已持
                    // 同键）则不渲染，关掉刚开的空窗并清理。
                    if (!OpenArchiveRegistry.register(openKey, newTab)) {
                        val winner = OpenArchiveRegistry.owner(openKey)
                        closeTab(newTab)
                        outDir.deleteRecursively()
                        if (winner != null) toast(getString(R.string.msg_archive_open_in_tab, tabTitle(winner)))
                        return@runOnUiThread
                    }
                    renderPreview(newTab, extracted, entries, nestedFmt, "", openKey)
                }
            }
            }
        )
    }

/**
 * True when the archive is a PKWARE multi-disk zip set (`.z01/.z02/.zip`).
 * Such archives can't be modified in place (zip_modify needs a single
 * seekable stream and would silently drop entries), so editing is refused.
 */
internal fun MainActivity.isMultiDiskZipArchive(archive: File): Boolean =
    isVolumeFile(archive) == "zip" && resolveZipVolumes(archive).size > 1

/**
 * Deletes the temp copies a zip-modify operation may leave behind:
 * `name.mod.zip` / `name.del.zip` / `name.add.zip` and the Rust-side
 * `.tmp{pid}` files next to them. Called on failure so a broken edit never
 * litters the archive's directory.
 */
internal fun MainActivity.cleanupZipModifyArtifacts(archive: File) {
    val dir = archive.parentFile ?: cacheDir
    val base = archive.nameWithoutExtension
    dir.listFiles()?.forEach { f ->
        val n = f.name
        if (n.startsWith("$base.") && (n == "$base.mod.zip" || n == "$base.del.zip" || n == "$base.add.zip"
                || n.startsWith("$base.mod.zip.tmp") || n.startsWith("$base.del.zip.tmp") || n.startsWith("$base.add.zip.tmp"))) {
            f.delete()
        }
    }
}

/**
 * Replaces one entry inside a ZIP archive with the edited cache copy, via the
 * Rust zipModify path (untouched entries stay byte-identical). The result is
 * written as a `name-cn.zip` copy (uniqueFile dedupes to `name-cn (1).zip`),
 * never overwriting the original archive — a failed edit can't corrupt it.
 */
internal fun MainActivity.zipReplaceEntry(archive: File, entry: ArchiveEntry, newContent: File, pwd: String = "", ownerTab: TabState = activeTab) {
    if (isMultiDiskZipArchive(archive)) { toast(getString(R.string.zip_multi_disk_no_edit)); return }
    // Rust 侧按 split('|') 解析操作串：条目名带 '|' 会错切路径（错删别的条目/
    // 写入垃圾路径），必须拒绝。
    if (entry.path.contains('|')) { toast(getString(R.string.zip_invalid_entry_name)); return }
    val opH = tryStartOperation(this, "zip")
    val parent = archive.parentFile ?: cacheDir
    thread {
        if (!opH.await()) return@thread
        // outF/tmp 在拿到槽位后再解析：排队中的第二次编辑若在入队时解析，
        // 会与第一次解析出同一个 -cn 输出名，后完成者覆盖先完成者（编辑丢失）。
        val outF = uniqueFile(parent, "${archive.nameWithoutExtension}-cn.zip")
        val tmp = File(parent, "${archive.nameWithoutExtension}.mod.zip")
        try {
            val ok = ZipCore.zipModify("", archive.path, tmp.path,
                "replace|${entry.path}|${newContent.path}", pwd)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok) {
                    // Move the temp to the -cn output; the original stays put.
                    java.nio.file.Files.move(tmp.toPath(), outF.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    toast(getString(R.string.edit_done, outF.name))
                    navTab(ownerTab, ownerTab.currentDir)
                } else {
                    cleanupZipModifyArtifacts(archive)
                    toast(getString(R.string.title_compress_failed))
                }
            }
        } catch (e: Exception) {
            cleanupZipModifyArtifacts(archive)
            runOnUiThread { if (isFinishing || isDestroyed) return@runOnUiThread; toast(getString(R.string.err_extract_io, e.message ?: "")) }
        } finally {
            opH.release()
        }
    }
}

/**
 * Deletes selected entries from a ZIP archive via the Rust zipModify path
 * (`delete|path` per line), writing a `name-cn.zip` copy (original untouched).
 * Split-volume zips are excluded by the caller.
 */
internal fun MainActivity.zipDeleteEntries(archive: File, paths: List<String>, pwd: String = "", ownerTab: TabState = activeTab) {
    if (isMultiDiskZipArchive(archive)) { toast(getString(R.string.zip_multi_disk_no_edit)); return }
    if (paths.isEmpty()) { toast(getString(R.string.msg_select_one)); return }
    // '|' 会错切 delete 操作串（Rust split('|')），错删别的条目——拒绝。
    if (paths.any { it.contains('|') }) { toast(getString(R.string.zip_invalid_entry_name)); return }
    val opH = tryStartOperation(this, "zip")
    val parent = archive.parentFile ?: cacheDir
    val ops = paths.joinToString("\n") { "delete|$it" }
    thread {
        if (!opH.await()) return@thread
        val outF = uniqueFile(parent, "${archive.nameWithoutExtension}-cn.zip") // 拿到槽位后再解析，防排队互相覆盖
        val tmp = File(parent, "${archive.nameWithoutExtension}.del.zip")
        try {
            val ok = ZipCore.zipModify("", archive.path, tmp.path, ops, pwd)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok) {
                    java.nio.file.Files.move(tmp.toPath(), outF.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    toast(getString(R.string.edit_done, outF.name))
                    navTab(ownerTab, ownerTab.currentDir)
                } else {
                    cleanupZipModifyArtifacts(archive)
                    toast(getString(R.string.title_compress_failed))
                }
            }
        } catch (e: Exception) {
            cleanupZipModifyArtifacts(archive)
            runOnUiThread { if (isFinishing || isDestroyed) return@runOnUiThread; toast(getString(R.string.err_extract_io, e.message ?: "")) }
        } finally {
            opH.release()
        }
    }
}

/**
 * Adds one entry to a ZIP archive from a local file, via the Rust zipModify
 * `add|name|srcPath` path, writing a `name-cn.zip` copy (original untouched).
 */
internal fun MainActivity.zipAddEntry(archive: File, entryName: String, srcFile: File, pwd: String = "", ownerTab: TabState = activeTab) {
    if (isMultiDiskZipArchive(archive)) { toast(getString(R.string.zip_multi_disk_no_edit)); return }
    val opH = tryStartOperation(this, "zip")
    val parent = archive.parentFile ?: cacheDir
    val safeName = entryName.replace('\\', '/').trim('/')
    // 用户输入的新条目名带 '|' 会错切 add 操作串（路径/源路径错位）——拒绝。
    if (safeName.contains('|')) { toast(getString(R.string.zip_invalid_entry_name)); return }
    thread {
        if (!opH.await()) return@thread
        val outF = uniqueFile(parent, "${archive.nameWithoutExtension}-cn.zip") // 拿到槽位后再解析，防排队互相覆盖
        val tmp = File(parent, "${archive.nameWithoutExtension}.add.zip")
        try {
            val ok = ZipCore.zipModify("", archive.path, tmp.path,
                "add|$safeName|${srcFile.path}", pwd)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok) {
                    java.nio.file.Files.move(tmp.toPath(), outF.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    toast(getString(R.string.edit_done, outF.name))
                    navTab(ownerTab, ownerTab.currentDir)
                } else {
                    cleanupZipModifyArtifacts(archive)
                    toast(getString(R.string.title_compress_failed))
                }
            }
        } catch (e: Exception) {
            cleanupZipModifyArtifacts(archive)
            runOnUiThread { if (isFinishing || isDestroyed) return@runOnUiThread; toast(getString(R.string.err_extract_io, e.message ?: "")) }
        } finally {
            opH.release()
        }
    }
}
