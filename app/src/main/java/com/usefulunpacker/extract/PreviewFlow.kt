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
                if (entered == null) { runOnUiThread { pd.dismiss() }; return@thread }
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
                "tar" -> TarCore.tarListEntries(src.absolutePath)
                else -> null
            } } catch (_: Exception) { null }
            runOnUiThread { pd.dismiss() }
            if (json == null || json == "[]") {
                val msg = if (format in setOf("zip", "7z", "rar")) getString(R.string.err_cannot_read_maybe_pwd) else getString(R.string.msg_cannot_read)
                runOnUiThread {
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
            val entries = parseEntries(json)
            runOnUiThread { showPreviewDialog(src, entries, format, pwd) }
        }
    }

internal fun MainActivity.showPreviewDialog(src: File, entries: List<ArchiveEntry>, format: String, pwd: String = "") {
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
            previewFileEntry(src, entry, format, pwd)
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
                val cacheDir = File(cacheDir, "archive_search/${src.nameWithoutExtension}")
                // Lock first, then the dual progress dialog (the text-extraction
                // phase reports byte progress); the work dir is cleared INSIDE
                // the lock so a refused second search can't nuke a running one.
                if (!tryStartOperation(act)) return@setOnClickListener
                var cancelled = false
                val accessors = extractAccessors(format)
                val prog = PollingProgressDialog(
                    act,
                    getString(R.string.preparing_search),
                    accessors,
                    { n, b, t -> extractProgressMessage(act, n, b, t) },
                    getString(R.string.action_cancel),
                    { cancelled = true; accessors.cancel() }
                )
                prog.start()
                thread {
                    try {
                        cacheDir.deleteRecursively()
                        cacheDir.mkdirs()
                        searchSourceArchive = src; searchSourceFormat = format
                        searchSourceCacheBase = cacheDir
                        searchSourcePassword = pwd
                        searchSourceResolver = null
                        // Phase 1: touch empty placeholder files for ALL entries (fast, for filename search)
                        for (e in entries) {
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
                                if (e.isDirectory) continue
                                val rel = sanitizeEntryPath(e.path) ?: continue
                                val ext = e.path.substringAfterLast('.').lowercase()
                                if (ext !in textExts) continue
                                extractByFormat(format, src.path, cacheDir.path, rel, prefs, pwd)
                            }
                        }
                        runOnUiThread {
                            prog.dismiss()
                            if (cancelled) { toast(getString(R.string.msg_cancelled)); return@runOnUiThread }
                            dlg.dismiss()
                            globalSearch(cacheDir, tempDir = cacheDir)
                        }
                    } catch (e: Exception) {
                        runOnUiThread { prog.dismiss(); toast(getString(R.string.err_extract_io, e.message ?: "")) }
                    } finally {
                        OperationLock.release()
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
                                            zipDeleteEntries(src, sel, pwd)
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
                                                zipAddEntry(src, name, picked, pwd)
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
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val sel = selectedPaths.filter { p -> selectedPaths.none { o -> o != p && o.startsWith(p + "/") } }
                if (sel.isEmpty()) {
                    toast(getString(R.string.msg_select_one))
                } else {
                    dlg.dismiss()
                    showOutputDirDialog(src, sel, format)
                }
            }
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                dlg.dismiss()
                extractAll(uniqueFile(src.parentFile ?: return@setOnClickListener, src.nameWithoutExtension), src, format, pwd)
            }
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(C["accent"]!!)
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(C["tertiary"]!!)
        }
        dlg.show()
    }

internal fun MainActivity.showOutputDirDialog(src: File, selectedPaths: List<String>, format: String) {
        val parent = src.parentFile ?: return
        val outDir = uniqueFile(parent, src.nameWithoutExtension)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_extract_to))
            .setItems(arrayOf(getString(R.string.extract_new_folder, getString(R.string.title_new_folder), outDir.name), getString(R.string.action_extract))) { _, w ->
                val out = if (w == 0) outDir else parent
                extractSelected(src, out, selectedPaths, format)
            }.setNegativeButton(getString(R.string.action_cancel), null)
            .show()
    }

/** 编辑 minimal loop: full extract to a cache dir → script list → in-place
 *  edit (showTextEditor) → repack into a new "…-cn.xp3/pfs" alongside the
 *  original. The working dir persists while the script list is open, so edits
 *  accumulate until the user taps 封回; re-entering 编辑 re-extracts fresh. */
internal fun MainActivity.startEditArchive(src: File, format: String, pwd: String) {
    val act = this
    val editDir = File(cacheDir, "edit/${src.nameWithoutExtension}")
    // Lock first, then the dual progress dialog (see extractAll); the work dir
    // is cleared INSIDE the lock so a refused re-edit can't destroy an active one.
    if (!tryStartOperation(this)) return
    var cancelled = false
    val accessors = extractAccessors(format)
    val prog = PollingProgressDialog(
        this,
        "${getString(R.string.edit_archive_title)} — ${src.name}",
        accessors,
        { n, b, t -> extractProgressMessage(this, n, b, t) },
        getString(R.string.action_cancel),
        { cancelled = true; accessors.cancel() }
    )
    prog.start()
    thread {
        try {
            editDir.deleteRecursively()
            editDir.mkdirs()
            val o = extractByFormat(format, src.path, editDir.path, "", prefs, pwd)
            val scripts = editDir.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in EDIT_SCRIPT_EXTS }
                .sortedBy { it.name.lowercase() }
                .toList()
            runOnUiThread {
                prog.dismiss()
                if (cancelled) { toast(getString(R.string.msg_cancelled)); return@runOnUiThread }
                if (!o.counts.ok) { toast(friendlyExtractError(act, o.error)); return@runOnUiThread }
                if (scripts.isEmpty()) { toast(getString(R.string.edit_no_scripts)); return@runOnUiThread }
                showEditScriptList(src, format, editDir, scripts)
            }
        } catch (e: Exception) {
            runOnUiThread { prog.dismiss(); toast(getString(R.string.err_extract_io, e.message ?: "")) }
        } finally {
            OperationLock.release()
        }
    }
}

private fun MainActivity.showEditScriptList(src: File, format: String, editDir: File, scripts: List<File>) {
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
    val metrics = resources.displayMetrics
    dlg.window?.setLayout((metrics.widthPixels * 0.92).toInt(), (metrics.heightPixels * 0.8).toInt())
    dlg.show()
}

private fun MainActivity.repackEditedArchive(src: File, format: String, editDir: File) {
    val parent = src.parentFile ?: return
    val ext = src.extension.ifEmpty { format }
    val outF = uniqueFile(parent, "${src.nameWithoutExtension}-cn.$ext")
    if (!tryStartOperation(this)) return
    var cancelled = false
    val accessors = compressAccessors(format)
    val prog = PollingProgressDialog(
        this,
        "${getString(R.string.edit_repack)} — ${outF.name}",
        accessors,
        { n, b, t -> compressProgressMessage(this, n, b, t) },
        getString(R.string.action_cancel),
        { cancelled = true; accessors.cancel() }
    )
    prog.start()
    thread {
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
                else -> PfsCore.pfsCreateArchive("", editDir.path, outF.path) != null
            }
            runOnUiThread {
                prog.dismiss()
                if (cancelled) toast(getString(R.string.msg_cancelled))
                else if (ok) { toast(getString(R.string.edit_done, outF.name)); nav(currentDir) }
                else toast(getString(R.string.title_compress_failed))
            }
        } finally {
            OperationLock.release()
        }
    }
}

internal fun MainActivity.extractSelected(src: File, out: File, paths: List<String>, format: String) {
        val selStr = paths.joinToString("\n")
        if (selStr.isEmpty()) { toast(getString(R.string.msg_select_one)); return }
        val existedBefore = out.exists()
        if (format !in setOf("zip", "7z", "rar")) {
            tryExtractWithPassword(this, format, src.path, out.path, selStr, prefs,
                onCancel = {
                    cleanupCancelledOutput(out, existedBefore)
                    toast(getString(R.string.msg_cancelled))
                }
            ) { o ->
                if (o.counts.ok) { showExtractSuccess(src.name, out.name, o.counts); nav(currentDir) }
                else toast(friendlyExtractError(this, o.error))
            }
            return
        }
        // zip/7z/rar: prompt for the password up front, and on failure offer a retry dialog.
        // Lock first, then the progress dialog (see extractAll).
        if (!tryStartOperation(this)) return
        var cancelled = false
        val accessors = extractAccessors(format)
        val prog = PollingProgressDialog(
            this,
            "${src.name} → ${out.name}",
            accessors,
            { n, b, t -> extractProgressMessage(this, n, b, t) },
            getString(R.string.action_cancel),
            { cancelled = true; accessors.cancel() }
        )
        prog.start()
        fun doSel(p: String): ExtractOutcome = runCatching {
            when (format) {
                "zip" -> ExtractOutcome(ExtractCounts.fromJson(zipExtractDispatch(src.path, out.path, selStr, p)), null)
                "7z" -> ExtractOutcome(ExtractCounts.fromJson(szExtractDispatch(src.path, out.path, selStr, p)), null)
                else -> ExtractOutcome(ExtractCounts.fromJson(rarExtractDispatch(src.path, out.path, selStr, p)), null)
            }
        }.getOrElse { e -> ExtractOutcome(ExtractCounts(0, 0, 0), e.message) }
        thread {
            try {
                var pwd = ""
                if (isPasswordProtected(src)) {
                    val entered = promptPasswordSync(this)
                    if (entered == null) {
                        runOnUiThread { prog.dismiss(); toast(getString(R.string.msg_cancelled)) }
                        return@thread
                    }
                    pwd = entered
                }
                val result = doSel(pwd)
                val ok = result.counts.ok
                runOnUiThread {
                    prog.dismiss()
                    if (cancelled) { cleanupCancelledOutput(out, existedBefore); toast(getString(R.string.msg_cancelled)) }
                    else if (ok) { showExtractSuccess(src.name, out.name, result.counts); nav(currentDir) }
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
                                // Worker-side plain acquire; when another
                                // operation holds the lock, surface the busy
                                // toast on the UI thread (never silently
                                // swallow the tap).
                                thread {
                                    if (!OperationLock.acquire()) {
                                        runOnUiThread { toast(getString(R.string.msg_op_in_progress)) }
                                        return@thread
                                    }
                                    try {
                                        val o2 = doSel(p2)
                                        runOnUiThread {
                                            if (o2.counts.ok) { showExtractSuccess(src.name, out.name, o2.counts); nav(currentDir) }
                                            else toast(friendlyExtractError(this, o2.error))
                                        }
                                    } finally {
                                        OperationLock.release()
                                    }
                                }
                            }
                            .setNegativeButton(getString(R.string.action_cancel)) { _, _ -> cleanupCancelledOutput(out, existedBefore) }
                            .show()
                    }
                }
            } finally {
                OperationLock.release()
            }
        }
    }

internal fun MainActivity.previewFileEntry(archive: File, entry: ArchiveEntry, format: String, pwd: String = "") {
        val ext = entry.path.substringAfterLast('.').lowercase()
        if (ext !in PREVIEW_EXTS) {
                        toast(getString(R.string.err_preview_unsupported, ".$ext"))
            return
        }

        val cacheDir = File(cacheDir, "preview/${archive.nameWithoutExtension}")
        fun openPreview() {
            val extracted = File(cacheDir, entry.path)
            runOnUiThread {
                when (ext) {
                    "jpg", "jpeg", "png", "gif", "webp", "bmp" -> showImagePreview(this, extracted)
                    "mp3", "ogg" -> playAudio(this, extracted)
                    "mp4" -> playVideo(this, extracted)
                    // ZIP entries can be edited in place (zipModify replaces just
                    // that entry); split-volume zips can't (zipModify needs a
                    // single seekable archive and can't re-split), so no 编辑.
                    else -> {
                        val isSplitZip = format == "zip" &&
                            (isVolumeFile(archive) == "zip" || isZipVolumeName(archive.name))
                        val canEditZip = format == "zip" && entry.path.isNotEmpty() && !isSplitZip
                        showTextPreview(this, extracted, showEdit = canEditZip,
                            onEdited = if (canEditZip) { { zipReplaceEntry(archive, entry, extracted, pwd) } } else { null })
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
                    if (needsPw && pwd.isEmpty()) {
                        // No password yet (archive opened via a path that didn't prompt) — ask first.
                        showPasswordDialog(this, format, archive.path, cacheDir.path, entry.path, showProgress = false) { o ->
                            if (o.counts.ok) openPreview() else toast(friendlyExtractError(this, o.error))
                        }
                    } else {
                        tryExtractWithPassword(this, format, archive.path, cacheDir.path, entry.path, prefs, showProgress = false, initialPassword = pwd) { o ->
                            if (o.counts.ok) openPreview() else toast(friendlyExtractError(this, o.error))
                        }
                    }
                }
            }
        } else {
            tryExtractWithPassword(this, format, archive.path, cacheDir.path, entry.path, prefs, showProgress = false, initialPassword = pwd) { o ->
                if (o.counts.ok) openPreview() else toast(friendlyExtractError(this, o.error))
            }
        }
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
internal fun MainActivity.zipReplaceEntry(archive: File, entry: ArchiveEntry, newContent: File, pwd: String = "") {
    if (isMultiDiskZipArchive(archive)) { toast(getString(R.string.zip_multi_disk_no_edit)); return }
    if (!tryStartOperation(this)) return
    val parent = archive.parentFile ?: cacheDir
    val outF = uniqueFile(parent, "${archive.nameWithoutExtension}-cn.zip")
    val tmp = File(parent, "${archive.nameWithoutExtension}.mod.zip")
    thread {
        try {
            val ok = ZipCore.zipModify("", archive.path, tmp.path,
                "replace|${entry.path}|${newContent.path}", pwd)
            runOnUiThread {
                if (ok) {
                    // Move the temp to the -cn output; the original stays put.
                    java.nio.file.Files.move(tmp.toPath(), outF.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    toast(getString(R.string.edit_done, outF.name))
                    nav(currentDir)
                } else {
                    cleanupZipModifyArtifacts(archive)
                    toast(getString(R.string.title_compress_failed))
                }
            }
        } catch (e: Exception) {
            cleanupZipModifyArtifacts(archive)
            runOnUiThread { toast(getString(R.string.err_extract_io, e.message ?: "")) }
        } finally {
            OperationLock.release()
        }
    }
}

/**
 * Deletes selected entries from a ZIP archive via the Rust zipModify path
 * (`delete|path` per line), writing a `name-cn.zip` copy (original untouched).
 * Split-volume zips are excluded by the caller.
 */
internal fun MainActivity.zipDeleteEntries(archive: File, paths: List<String>, pwd: String = "") {
    if (isMultiDiskZipArchive(archive)) { toast(getString(R.string.zip_multi_disk_no_edit)); return }
    if (paths.isEmpty()) { toast(getString(R.string.msg_select_one)); return }
    if (!tryStartOperation(this)) return
    val parent = archive.parentFile ?: cacheDir
    val outF = uniqueFile(parent, "${archive.nameWithoutExtension}-cn.zip")
    val tmp = File(parent, "${archive.nameWithoutExtension}.del.zip")
    val ops = paths.joinToString("\n") { "delete|$it" }
    thread {
        try {
            val ok = ZipCore.zipModify("", archive.path, tmp.path, ops, pwd)
            runOnUiThread {
                if (ok) {
                    java.nio.file.Files.move(tmp.toPath(), outF.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    toast(getString(R.string.edit_done, outF.name))
                    nav(currentDir)
                } else {
                    cleanupZipModifyArtifacts(archive)
                    toast(getString(R.string.title_compress_failed))
                }
            }
        } catch (e: Exception) {
            cleanupZipModifyArtifacts(archive)
            runOnUiThread { toast(getString(R.string.err_extract_io, e.message ?: "")) }
        } finally {
            OperationLock.release()
        }
    }
}

/**
 * Adds one entry to a ZIP archive from a local file, via the Rust zipModify
 * `add|name|srcPath` path, writing a `name-cn.zip` copy (original untouched).
 */
internal fun MainActivity.zipAddEntry(archive: File, entryName: String, srcFile: File, pwd: String = "") {
    if (isMultiDiskZipArchive(archive)) { toast(getString(R.string.zip_multi_disk_no_edit)); return }
    if (!tryStartOperation(this)) return
    val parent = archive.parentFile ?: cacheDir
    val outF = uniqueFile(parent, "${archive.nameWithoutExtension}-cn.zip")
    val tmp = File(parent, "${archive.nameWithoutExtension}.add.zip")
    val safeName = entryName.replace('\\', '/').trim('/')
    thread {
        try {
            val ok = ZipCore.zipModify("", archive.path, tmp.path,
                "add|$safeName|${srcFile.path}", pwd)
            runOnUiThread {
                if (ok) {
                    java.nio.file.Files.move(tmp.toPath(), outF.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    toast(getString(R.string.edit_done, outF.name))
                    nav(currentDir)
                } else {
                    cleanupZipModifyArtifacts(archive)
                    toast(getString(R.string.title_compress_failed))
                }
            }
        } catch (e: Exception) {
            cleanupZipModifyArtifacts(archive)
            runOnUiThread { toast(getString(R.string.err_extract_io, e.message ?: "")) }
        } finally {
            OperationLock.release()
        }
    }
}
