package com.usefulunpacker

import android.app.AlertDialog
import android.app.ProgressDialog
import android.graphics.drawable.ColorDrawable
import android.view.View
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
                "rar" -> { val vols = resolveRarVolumes(src); if (vols.size > 1) RarCore.rarListEntriesVolumes(volumeJoin(vols)) else RarCore.rarListEntries(src.absolutePath) }
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
            text = getString(R.string.preview_title, src.name)
            setTextColor(C["primary"]!!); textSize = 17f
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })

        // Must declare dlg before btnSearchTitle so its lambda can capture it
        lateinit var dlg: AlertDialog
        val btnSearchTitle = ImageButton(this).apply {
            setImageResource(R.drawable.ic_search)
            setBackgroundColor(C["surface"]!!)
            setPadding(8, 4, 8, 4)
            scaleType = ImageView.ScaleType.FIT_XY
            layoutParams = LinearLayout.LayoutParams(52, 40)
            setOnClickListener {
                val cacheDir = File(cacheDir, "archive_search/${src.nameWithoutExtension}")
                cacheDir.deleteRecursively()
                cacheDir.mkdirs()
                val pd = ProgressDialog(act).apply {
                    setTitle(getString(R.string.preparing_search))
                                        setMessage(getString(R.string.msg_preparing_index, src.name))
                    setProgressStyle(ProgressDialog.STYLE_SPINNER)
                    setCancelable(false); show()
                }
                thread {
                    if (!OperationLock.acquire()) { runOnUiThread { pd.dismiss(); dlg.dismiss() }; return@thread }
                    try {
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
                            f.createNewFile()
                        }
                        // Phase 2: extract only text files (overwrites placeholders, for content search)
                        val textExts = TEXT_SEARCH_EXTS
                        for (e in entries) {
                            if (e.isDirectory) continue
                            val ext = e.path.substringAfterLast('.').lowercase()
                            if (ext !in textExts) continue
                            extractByFormat(format, src.path, cacheDir.path, e.path, prefs, pwd)
                        }
                        runOnUiThread {
                            pd.dismiss()
                            dlg.dismiss()
                            globalSearch(cacheDir, tempDir = cacheDir)
                        }
                    } finally {
                        OperationLock.release()
                    }
                }
            }
        }
        titleBar.addView(btnSearchTitle)

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

internal fun MainActivity.extractSelected(src: File, out: File, paths: List<String>, format: String) {
        val selStr = paths.joinToString("\n")
        if (selStr.isEmpty()) { toast(getString(R.string.msg_select_one)); return }
        if (format !in setOf("zip", "7z", "rar")) {
            tryExtractWithPassword(this, format, src.path, out.path, selStr, prefs,
                onCancel = {
                    if (out.isDirectory && out.listFiles()?.isEmpty() == true) out.delete()
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
                    if (cancelled) { if (out.isDirectory && out.listFiles()?.isEmpty() == true) out.delete(); toast(getString(R.string.msg_cancelled)) }
                    else if (ok) { showExtractSuccess(src.name, out.name, result.counts); nav(currentDir) }
                    else {
                        if (out.isDirectory && out.listFiles()?.isEmpty() == true) out.delete()
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
                            .setNegativeButton(getString(R.string.action_cancel)) { _, _ -> if (out.isDirectory && out.listFiles()?.isEmpty() == true) out.delete() }
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
                    "jpg", "jpeg", "png" -> showImagePreview(this, extracted)
                    "mp3", "ogg" -> playAudio(this, extracted)
                    "mp4" -> playVideo(this, extracted)
                    else -> showTextPreview(this, extracted)
                }
            }
        }
        if (format in setOf("zip", "7z", "rar")) {
            val needsPw = isPasswordProtected(archive)
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
        } else {
            tryExtractWithPassword(this, format, archive.path, cacheDir.path, entry.path, prefs, showProgress = false, initialPassword = pwd) { o ->
                if (o.counts.ok) openPreview() else toast(friendlyExtractError(this, o.error))
            }
        }
    }
