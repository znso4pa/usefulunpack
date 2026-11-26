package com.usefulunpacker

import com.usefulunpacker.fileops.RecentsStore

import android.app.AlertDialog
import android.app.ProgressDialog
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.*
import java.io.File
import com.usefulunpacker.featureThread as thread
internal fun MainActivity.previewArchive(src: File, format: String) {
    val _intentScope = com.usefulunpacker.AppDiagnostics.enterIntent("archive_preview", newChain = true)
    try {
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
        val pd = com.usefulunpacker.UuProgressDialog(this).apply {
            setTitle(getString(R.string.reading))
                        setMessage(getString(R.string.msg_reading_archive, src.name))
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            show()
        }
        pd.keepTabsTappable()
        thread {
            // Ask for the password up front so header-encrypted 7z can be
            // listed and entry previews reuse it (no "wrong password" toast).
            var pwd = ""
            val pwDetected = format in setOf("zip", "7z", "rar") && isPasswordProtected(src)
            if (pwDetected) {
                val entered = promptPasswordSync(this)
                if (entered == null) { runFeatureOnUiThread { if (isFinishing || isDestroyed) return@runFeatureOnUiThread; pd.dismiss() }; return@thread }
                pwd = entered
            }
            var listError = ""
            val json = listEntriesJson(format, src, pwd, nestedOnly = false) { listError = it }
            runFeatureOnUiThread { if (isFinishing || isDestroyed) return@runFeatureOnUiThread; pd.dismiss() }
            if (json == null) {
                val base = if (format in setOf("zip", "7z", "rar")) getString(R.string.err_cannot_read_maybe_pwd) else getString(R.string.msg_cannot_read)
                val msg = if (listError.isNotEmpty()) "$base\n\n$listError" else base
                runFeatureOnUiThread {
                    if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                    // Auto-detection can be wrong (e.g. a mislabeled extension) —
                    // offer the manual format picker as an escape hatch.
                    com.usefulunpacker.UuDialogBuilder(this)
                        .setTitle(getString(R.string.msg_cannot_read))
                        .setMessage(msg)
                        .setPositiveButton(getString(R.string.title_select_format)) { _, _ -> extract() }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show().also { it.keepTabsTappable() }
                }
                return@thread
            }
            if (json == "[]") {
                // 合法但空的归档不是读取失败：不弹「无法读取/可能需要密码」，
                // 直接提示为空，避免误导用户去试格式选择器。
                runFeatureOnUiThread {
                    if (isFinishing || isDestroyed) return@runFeatureOnUiThread
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
                runFeatureOnUiThread {
                    if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                    if (winner != null && winner !== activeTab) {
                        toast(getString(R.string.msg_archive_open_in_tab, tabTitle(winner)))
                        val idx = tabs.indexOf(winner)
                        if (idx >= 0) viewPager.currentItem = idx
                    }
                }
                return@thread
            }
            // 「最近打开的归档」在这里记（真正打开才算；密码错/解析失败走不到这）。
            RecentsStore.record(this, src)
            // Encryption probe runs on this background thread with the listing
            // (it reads the game folder's sidecar and scores real entries).
            val encNote = xp3SchemeToken(format, src)
            runFeatureOnUiThread {
                if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                renderPreview(activeTab, src, entries, format, pwd, openKey, encNote)
            }
        }
    } catch (intentCancelled: java.util.concurrent.CancellationException) {
        _intentScope.trace.cancelled(); throw intentCancelled
    } catch (intentFailure: Exception) {
        _intentScope.trace.fail(com.usefulunpacker.FailureCode.UNEXPECTED, intentFailure.message ?: intentFailure.javaClass.simpleName, intentFailure)
        throw intentFailure
    } finally { com.usefulunpacker.AppDiagnostics.leaveIntent(_intentScope) }
    }

/**
 * The preview title bar text. ONE place builds it, because two render paths
 * write that view — the initial render here and a fragment rebuild in
 * FolderFragment — and a title written in only one of them goes stale exactly
 * like `displayPath()` once did (see AGENTS.md).
 */
internal fun MainActivity.previewTitleOf(tab: TabState): String {
    val base = tab.previewSrc?.name ?: ""
    val label = xp3SchemeLabel(strFnOf(this), tab.previewEncNote)
    return if (label.isEmpty()) base else base + getString(R.string.enc_suffix_paren, label)
}

/**
 * Renders an archive preview INSIDE the given tab (no modal dialog), so the
 * ViewPager above stays swipeable and the user can flip windows while looking
 * at archive contents. Entries/selection state live on the TabState.
 */
internal fun MainActivity.renderPreview(tab: TabState, src: File, entries: List<ArchiveEntry>, format: String, pwd: String, openKey: String, encNote: String = "") {
    tab.previewActive = true
    tab.previewSrc = src
    tab.previewFormat = format
    tab.previewPwd = pwd
    tab.previewOpenKey = openKey
    tab.previewEncNote = encNote
    tab.previewEntries = entries
    tab.previewSelected.clear()
    tab.previewExpanded.clear()
    tab.previewExpanded.addAll(entries.filter { it.isDirectory }.map { it.path })
    tab.previewSearchQuery = ""
    if (tab.viewsBound) {
        tab.fabExtract.visibility = View.GONE
        tab.bottomBar.visibility = View.GONE
        tab.batchBar?.visibility = View.GONE
        tab.tvPreviewTitle.text = previewTitleOf(tab)
        // Preview top-bar: search icon + overflow menu (编辑/条目/转换 live in
        // the menu and toast when the format doesn't apply — see FolderFragment).
        tab.btnPreviewSearch.visibility = View.VISIBLE
        tab.btnPreviewOverflow.visibility = View.VISIBLE
        syncPreview(tab)
        updatePreviewStats(tab)
    }
    // A new preview open in ANY window changes what every other open preview can
    // merge into, so the buttons have to be re-evaluated everywhere.
    refreshMergeButtons()
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
        // 可见性不在这里写：navTab 末尾的 applyLayer 会按 previewActive=false 收层
        //（唯一写入点是 browse/TabLayers.kt，这里多写一行就会破坏那条不变式）。
        navTab(tab, tab.currentDir)
    }
    // This window just gave up its archive, which may have been another window's
    // only merge target.
    refreshMergeButtons()
    saveSession()
}

/** In-preview "search inside archive": extract text entries to a cache dir,
 *  exit the preview, and open the global search on the extracted content. */
internal fun MainActivity.previewSearch(tab: TabState) {
    val _intentScope = com.usefulunpacker.AppDiagnostics.enterIntent("archive_search", newChain = true)
    try {
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
            com.usefulunpacker.deleteOutputChecked(cacheDir, recursive = true)
            cacheDir.mkdirs()
            // Phase 1: touch placeholders for ALL entries (fast filename search).
            for (e in entries) {
                if (cancelled) break
                if (e.isDirectory) continue
                val rel = sanitizeEntryPath(e.path) ?: continue
                val f = File(cacheDir, rel)
                f.parentFile?.mkdirs()
                try { f.createNewFile() } catch (failureCause: Exception) { com.usefulunpacker.AppDiagnostics.failure("PreviewFlow", failureCause);}
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
            runFeatureOnUiThread {
                if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                prog.dismiss()
                if (cancelled) { toast(getString(R.string.msg_cancelled)); return@runFeatureOnUiThread }
                // Keep the in-tab preview alive: search opens on top, and closing
                // it returns to the preview (selection/expansion preserved on the
                // TabState). No exitPreview here.
                searchSourceArchive = src; searchSourceFormat = format
                searchSourceCacheBase = cacheDir
                searchSourcePassword = pwd
                searchSourceResolver = null
                globalSearch(cacheDir, tempDir = cacheDir)
            }
        } catch (e: Exception) { com.usefulunpacker.AppDiagnostics.failure("PreviewFlow", e);
            runFeatureOnUiThread { if (isFinishing || isDestroyed) return@runFeatureOnUiThread; prog.dismiss(); failureNotice(getString(R.string.err_extract_io, e.message ?: "")) }
        } finally {
            opH.release()
        }
    }
    } catch (intentCancelled: java.util.concurrent.CancellationException) {
        _intentScope.trace.cancelled(); throw intentCancelled
    } catch (intentFailure: Exception) {
        _intentScope.trace.fail(com.usefulunpacker.FailureCode.UNEXPECTED, intentFailure.message ?: intentFailure.javaClass.simpleName, intentFailure)
        throw intentFailure
    } finally { com.usefulunpacker.AppDiagnostics.leaveIntent(_intentScope) }
}

/** In-preview ZIP entry management (delete selected / add from local file),
 *  mirroring the legacy dialog. */
internal fun MainActivity.previewZipManage(tab: TabState) {
    val src = tab.previewSrc ?: return
    val format = tab.previewFormat
    val pwd = tab.previewPwd
    val entries = tab.previewEntries
    com.usefulunpacker.UuDialogBuilder(this)
        .setTitle(getString(R.string.zip_manage))
        .setItems(arrayOf(
            getString(R.string.zip_manage_delete),
            getString(R.string.zip_manage_add))) { _, w ->
            when (w) {
                0 -> {
                    val sel = tab.previewSelected.filter { p -> tab.previewSelected.none { o -> o != p && o.startsWith(p + "/") } }
                        .filter { p -> entries.find { e -> e.path == p }?.isDirectory == false }
                    if (sel.isEmpty()) { toast(getString(R.string.msg_select_one)); return@setItems }
                    com.usefulunpacker.UuDialogBuilder(this)
                        .setMessage(getString(R.string.zip_confirm_delete))
                        .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                            exitPreview(tab)
                            zipDeleteEntries(src, sel, pwd, tab)
                        }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show().also { it.keepTabsTappable() }
                }
                1 -> {
                    showFolderPicker(this, tab.currentDir, true) { picked ->
                        val baseName = picked.name
                        val inp = com.usefulunpacker.UuTextField(this).apply {
                            setText(baseName)
                            hint = getString(R.string.zip_add_name)
                        }
                        com.usefulunpacker.UuDialogBuilder(this)
                            .setTitle(getString(R.string.zip_add_title))
                            .setView(inp)
                            .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                                val name = inp.text.toString().trim().ifEmpty { baseName }
                                exitPreview(tab)
                                zipAddEntry(src, name, picked, pwd, tab)
                            }
                            .setNegativeButton(R.string.action_cancel, null)
                            .show().also { it.keepTabsTappable() }
                    }
                }
            }
        }
        .setNegativeButton(R.string.action_cancel, null)
        .show().also { it.keepTabsTappable() }
}

/** Rebuilds the in-tab preview list from the tab's entries + selection. */
internal fun MainActivity.syncPreview(tab: TabState) {
    if (!tab.viewsBound) return
    val src = tab.previewSrc ?: return
    // 层可见性统一由 applyLayer 配平（previewActive 为真 → previewRoot 亮、hub 让位）
    applyLayer(tab)
    // The preview covers the list but NOT the path bar, and this runs on every
    // tab switch / fragment rebuild — so the bar is refreshed from the tab's
    // derived state here too, not only in navTab. Without this the bar kept
    // whatever it last had, i.e. the layout default `/` for a tab whose
    // fragment was rebuilt while a preview was open.
    tab.tvPath.text = tab.displayPath().absolutePath
    syncMergeButton(tab)
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
        val titleBar = com.usefulunpacker.UuActionBar(this).apply {
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
        val btnSearchTitle = com.usefulunpacker.UuIconButton(this).apply {
            setImageResource(R.drawable.ic_search)
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
                        com.usefulunpacker.deleteOutputChecked(cacheDir, recursive = true)
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
                            try { f.createNewFile() } catch (failureCause: Exception) { com.usefulunpacker.AppDiagnostics.failure("PreviewFlow", failureCause);}
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
                        runFeatureOnUiThread {
                            if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                            prog.dismiss()
                            if (cancelled) { toast(getString(R.string.msg_cancelled)); return@runFeatureOnUiThread }
                            // searchSource* 只在成功路径赋值：取消/失败不得污染
                            // 全局搜索的归档源/解析器映射。
                            searchSourceArchive = src; searchSourceFormat = format
                            searchSourceCacheBase = cacheDir
                            searchSourcePassword = pwd
                            searchSourceResolver = null
                            dlg.dismiss()
                            globalSearch(cacheDir, tempDir = cacheDir)
                        }
                    } catch (e: Exception) { com.usefulunpacker.AppDiagnostics.failure("PreviewFlow", e);
                        runFeatureOnUiThread { if (isFinishing || isDestroyed) return@runFeatureOnUiThread; prog.dismiss(); failureNotice(getString(R.string.err_extract_io, e.message ?: "")) }
                    } finally {
                        opH.release()
                    }
                }
            }
        }
        titleBar.addView(btnSearchTitle)

        val btnEditArchive = com.usefulunpacker.UuButton(this).apply {
            text = getString(R.string.edit_archive)
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
            setOnClickListener {
                if (format !in setOf("xp3", "pfs", "iso", "nsa", "7z", "ypf", "rgss")) {
                    toast(getString(R.string.edit_only_pack))
                } else {
                    dlg.dismiss()
                    startEditArchive(src, format, pwd, ownerTab)
                }
            }
        }
        titleBar.addView(btnEditArchive)

        // ZIP entry management: delete selected / add a new entry (in-place via
        // zipModify). Split-volume zips can't be modified, so hide the button.
        if (format == "zip" && !(isVolumeFile(src) == "zip" || isZipVolumeName(src.name))) {
            val btnZipManage = com.usefulunpacker.UuButton(this).apply {
                text = getString(R.string.zip_manage)
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
                setOnClickListener {
                    com.usefulunpacker.UuDialogBuilder(this@showPreviewDialog)
                        .setTitle(getString(R.string.zip_manage))
                        .setItems(arrayOf(
                            getString(R.string.zip_manage_delete),
                            getString(R.string.zip_manage_add))) { _, w ->
                            when (w) {
                                0 -> {
                                    val sel = selectedPaths.filter { p -> selectedPaths.none { o -> o != p && o.startsWith(p + "/") } }
                                        .filter { p -> entries.find { e -> e.path == p }?.isDirectory == false }
                                    if (sel.isEmpty()) { toast(getString(R.string.msg_select_one)); return@setItems }
                                    com.usefulunpacker.UuDialogBuilder(this@showPreviewDialog)
                                        .setMessage(getString(R.string.zip_confirm_delete))
                                        .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                                            dlg.dismiss()
                                            zipDeleteEntries(src, sel, pwd, ownerTab)
                                        }
                                        .setNegativeButton(R.string.action_cancel, null)
                                        .show().also { it.keepTabsTappable() }
                                }
                                1 -> {
                                    // Pick a local file, then name the entry.
                                    showFolderPicker(this@showPreviewDialog, currentDir, true) { picked ->
                                        val baseName = picked.name
                                        val inp = com.usefulunpacker.UuTextField(this@showPreviewDialog).apply {
                                            setText(baseName)
                                            hint = getString(R.string.zip_add_name)
                                        }
                                        com.usefulunpacker.UuDialogBuilder(this@showPreviewDialog)
                                            .setTitle(getString(R.string.zip_add_title))
                                            .setView(inp)
                                            .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                                                val name = inp.text.toString().trim().ifEmpty { baseName }
                                                dlg.dismiss()
                                                zipAddEntry(src, name, picked, pwd, ownerTab)
                                            }
                                            .setNegativeButton(R.string.action_cancel, null)
                                            .show().also { it.keepTabsTappable() }
                                    }
                                }
                            }
                        }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show().also { it.keepTabsTappable() }
                }
            }
            titleBar.addView(btnZipManage)
        }

        // ISO previews can be converted straight to PSP CISO without leaving
        // the dialog (CSO files themselves reach the converter via their FAB).
        if (format == "iso") {
            val btnConvertIso = com.usefulunpacker.UuButton(this).apply {
                text = getString(R.string.action_convert_cso)
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
                setOnClickListener {
                    dlg.dismiss()
                    convertIso(src, toCso = true)
                }
            }
            titleBar.addView(btnConvertIso)
        }

        dlg = com.usefulunpacker.UuDialogBuilder(this)
            .setCustomTitle(titleBar)
            .setView(layout)
            .setPositiveButton(getString(R.string.extract_selected), null)
            .setNeutralButton(getString(R.string.extract_all), null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        // Keep the tab strip usable: bottom-anchor below status+toolbar+tabs. The
        // helper also resets the pager's input state on dismiss (Honor/EMUI
        // touch-state bug: a dismissed fast-scroll list can leave ViewPager2
        // unable to intercept horizontal swipes, killing tab switching), and the
        // `also` callback releases this dialog's open-archive slot.
        belowTabBar(dlg) { openKey?.let { OpenArchiveRegistry.unregister(it) } }
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
