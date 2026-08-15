package com.usefulunpacker

import android.app.AlertDialog
import android.app.ProgressDialog
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.*
import java.io.File
import kotlin.concurrent.thread

@Volatile internal var showBatchPreview_all: List<Pair<File, List<ArchiveEntry>>>? = null

internal fun MainActivity.batchArchives(): List<File> = multiSelected.filter { it.isFile && (it.extension.lowercase() in ARCHIVE_EXTS || isVolumeFile(it) != null) }

internal fun MainActivity.resolveBatchPath(mergedPath: String): Pair<File, String>? {
        // mergedPath is like "📦 data.xp3/scenario/main.ks". Extract the archive name and original path.
        for ((src, _) in showBatchPreview_all ?: emptyList()) {
            val prefix = "📦 ${src.name}/"
            if (mergedPath.startsWith(prefix)) return src to mergedPath.removePrefix(prefix)
        }
        return null
    }

internal fun MainActivity.startBatchExtract() {
        val archives = batchArchives(); if (archives.isEmpty()) { toast(getString(R.string.no_archives)); return }
        showFormatPicker(this, getString(R.string.title_select_format)) { fmt ->
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.title_batch_archives_fmt, archives.size, fmt.uppercase()))
                .setItems(arrayOf(getString(R.string.action_preview), getString(R.string.action_extract))) { _, w ->
                    when (w) {
                        0 -> batchPreview(archives, fmt)
                        1 -> batchDirectExtract(archives, fmt)
                    }
                }.setNegativeButton(getString(R.string.action_cancel), null).show()
        }
    }

internal fun MainActivity.batchDirectExtract(archives: List<File>, fmt: String) {
        val parent = archives[0].parentFile ?: currentDir
        // Pre-compute unique output dirs
        val outDirs = archives.map { src -> uniqueFile(parent, src.nameWithoutExtension) }
        val labels = arrayOf(getString(R.string.extract_separate_dirs, outDirs.map { it.name }.joinToString(", ")), getString(R.string.action_extract))
        AlertDialog.Builder(this).setTitle(getString(R.string.title_extract_to))
            .setItems(labels) { _, w ->
                // Lock first, then the progress dialog (see extractAll).
                if (!tryStartOperation(this)) return@setItems
                var cancelled = false
                val accessors = extractAccessors(fmt)
                val prog = PollingProgressDialog(
                    this,
                    getString(R.string.msg_batch_extract_title, fmt),
                    accessors,
                    { n, b, t -> extractProgressMessage(this, n, b, t) },
                    getString(R.string.action_cancel),
                    { cancelled = true; accessors.cancel() }
                )
                prog.start()
                thread {
                    try {
                        // Ask for the password once and reuse it for every archive.
                        var pwd: String? = null
                        if (archives.any { isPasswordProtected(it) }) {
                            pwd = promptPasswordSync(this)
                            if (pwd == null) {
                                runOnUiThread { prog.dismiss(); toast(getString(R.string.msg_cancelled)); exitMultiSelect(); nav(currentDir) }
                                return@thread
                            }
                        }
                        var ok = true
                        var err: String? = null
                        for (i in archives.indices) {
                            if (cancelled) { ok = false; break }
                            val out = if (w == 0) outDirs[i] else parent
                            val o = extractByFormat(fmt, archives[i].path, out.path, "", prefs, pwd ?: "")
                            ok = o.counts.ok; if (!ok) { err = o.error; break }
                        }
                        runOnUiThread {
                            prog.dismiss()
                            if (cancelled) toast(getString(R.string.msg_cancelled))
                            else if (ok) toast(getString(R.string.msg_batch_done))
                            else toast(friendlyExtractError(this, err))
                            exitMultiSelect(); nav(currentDir)
                        }
                    } catch (e: Exception) {
                        runOnUiThread { prog.dismiss(); toast(getString(R.string.err_extract_io, e.message ?: "")) }
                    } finally {
                        OperationLock.release()
                    }
                }
            }.setNegativeButton(getString(R.string.action_cancel), null).show()
    }

internal fun MainActivity.batchPreview(archives: List<File>, fmt: String) {
        val pd = ProgressDialog(this).apply { setTitle(getString(R.string.reading)); setMessage(getString(R.string.reading_archives, archives.size)); setProgressStyle(ProgressDialog.STYLE_SPINNER); setCancelable(false); show() }
        thread {
            // Shared password state: asked once for all archives; a cancel stops
            // further prompts (and further listing) instead of silently failing.
            var batchPwd: String? = null
            var pwdCancelled = false
            fun resolvePwd(arc: File): String? {
                if (pwdCancelled) return null
                batchPwd?.let { return it }
                if (!isPasswordProtected(arc)) return ""
                val p = promptPasswordSync(this)
                if (p == null) { pwdCancelled = true; return null }
                batchPwd = p
                return p
            }
            val all: MutableList<Pair<File, List<ArchiveEntry>>> = mutableListOf()
            for (src in archives) {
                val json = try { when(fmt) {
                    "xp3"->Xp3Core.xp3ListEntries(src.path)
                    "pfs"->PfsCore.pfsListEntries(src.path)
                    "nsa"->NsaCore.nsaListEntries(src.path)
                    "iso"->IsoCore.isoListEntries(src.path)
                    "ypf"->YpfCore.ypfListEntries(src.path)
                    "zip"->{ val vols = resolveZipVolumes(src); if (vols.size>1) ZipCore.zipListEntriesVolumes(volumeJoin(vols)) else ZipCore.zipListEntries(src.path) }
                    "7z"->{ val vols = resolveSevenZVolumes(src); if (vols.size>1) { val p = resolvePwd(src); if (p == null) null else if (p.isEmpty()) SevenZCore.szListEntriesVolumes(volumeJoin(vols)) else SevenZCore.szListEntriesVolumesWithPassword(volumeJoin(vols), p) } else { val p = resolvePwd(src); if (p == null) null else if (p.isEmpty()) SevenZCore.szListEntries(src.path) else SevenZCore.szListEntriesWithPassword(src.path, p) } }
                    "rar"->{ val vols = resolveRarVolumes(src); if (vols.size>1) RarCore.rarListEntriesVolumes(volumeJoin(vols)) else RarCore.rarListEntries(src.path) }
                    "lz4"->Lz4Core.lz4ListEntries(src.path); "gz"->GzipCore.gzListEntries(src.path); "bz2"->Bzip2Core.bz2ListEntries(src.path); "xz"->XzCore.xzListEntries(src.path); "zst"->ZstdCore.zstListEntries(src.path); "lzma"->LzmaCore.lzmaListEntries(src.path); "tar"->TarCore.tarListEntries(src.path)
                    else->null
                } } catch(_:Exception){null}
                if (json != null) all.add(src to parseEntries(json))
                if (pwdCancelled) break
            }
            runOnUiThread { pd.dismiss(); if (all.isEmpty()) toast(getString(R.string.msg_cannot_read)); else showBatchPreviewDialog(all, fmt, batchPwd, pwdCancelled) }
        }
    }

internal fun MainActivity.showBatchPreviewDialog(all: List<Pair<File, List<ArchiveEntry>>>, fmt: String, initialPwd: String? = null, pwdCancelled: Boolean = false) {
    val act = this
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
            text = getString(R.string.batch_preview_stats, all.size, totalFiles, fmt(totalSize), 0, fmt(0))
            setTextColor(C["tertiary_light"]!!); textSize = 12f
            setPadding(12, 8, 12, 4); setBackgroundColor(C["surface_dim"]!!)
        }
        fun updateStats() {
            val selFiles = selectedPaths.filter { p -> merged.find { e -> e.path == p && !e.isDirectory } != null }
            val selSize = selectedPaths.sumOf { p -> merged.find { e -> e.path == p }?.size ?: 0L }
            tvStats.text = getString(R.string.batch_preview_stats, all.size, totalFiles, fmt(totalSize), selFiles.size, fmt(selSize))
        }

        // Preview file click: extract from correct archive then show
        // Shared across batch preview/extract — ask for the password once.
        var batchPwd: String? = initialPwd
        var batchPwdCancelled = pwdCancelled
        // Returns the password to use, or null when the user cancelled the
        // password prompt (callers must abort the operation, not proceed with
        // a wrong password).
        fun resolveBatchPwd(arc: File): String? {
            if (batchPwdCancelled) return null
            batchPwd?.let { return it }
            if (!isPasswordProtected(arc)) return ""
            val p = promptPasswordSync(this)
            if (p == null) { batchPwdCancelled = true; return null }
            batchPwd = p
            return p
        }
        fun batchPreviewClick(entry: ArchiveEntry) {
            if (entry.isDirectory) return
            val (arc, origPath) = resolveBatchPath(entry.path) ?: return
            val cacheDir = File(cacheDir, "batch_preview/${arc.nameWithoutExtension}")
            // Lock first, then the dual progress dialog (see extractAll).
            if (!tryStartOperation(this)) return
            var cancelled = false
            val accessors = extractAccessors(fmt)
            val prog = PollingProgressDialog(
                this,
                getString(R.string.msg_extracting),
                accessors,
                { n, b, t -> extractProgressMessage(this, n, b, t) },
                getString(R.string.action_cancel),
                { cancelled = true; accessors.cancel() }
            )
            prog.start()
            thread {
                try {
                    val p = resolveBatchPwd(arc)
                    if (p == null) { runOnUiThread { prog.dismiss() }; return@thread }
                    val o = extractByFormat(fmt, arc.path, cacheDir.path, origPath, prefs, p)
                    runOnUiThread {
                        prog.dismiss()
                        if (cancelled) toast(getString(R.string.msg_cancelled))
                        else if (o.counts.ok) previewLocalFile(this, File(cacheDir, origPath))
                        else toast(friendlyExtractError(this, o.error))
                    }
                } catch (e: Exception) {
                    runOnUiThread { prog.dismiss(); toast(getString(R.string.err_extract_io, e.message ?: "")) }
                } finally {
                    OperationLock.release()
                }
            }
        }
        val adapter = PreviewAdapter(this, merged, selectedPaths, expandedPaths, { e -> batchPreviewClick(e) }, { updateStats() })

        val listView = ListView(this).apply {
            this.adapter = adapter; setBackgroundColor(C["surface"]!!)
            divider = ColorDrawable(C["surface_dark"]!!); dividerHeight = 1
            enableFastScroll()
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; addView(tvStats); addView(listView, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }

        lateinit var dlg: AlertDialog
        // Title bar with search button
        val titleBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(24, 14, 8, 14); setBackgroundColor(C["surface"]!!) }
        titleBar.addView(TextView(this).apply {
            text = getString(R.string.batch_preview_title, all.map{it.first.name}.joinToString(", ").take(40)); setTextColor(C["primary"]!!); textSize = 17f
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        val btnSearchTitle = ImageButton(this).apply {
            setImageResource(R.drawable.ic_search); setBackgroundColor(C["surface"]!!); setPadding(8, 4, 8, 4)
            scaleType = ImageView.ScaleType.FIT_XY; layoutParams = LinearLayout.LayoutParams(52, 40)
            setOnClickListener {
                val cacheDir = File(cacheDir, "archive_search/batch_${all.map{it.first.nameWithoutExtension}.joinToString("_").take(50)}")
                // Lock first, then the dual progress dialog (the text-extraction
                // phase reports byte progress); the work dir is cleared INSIDE
                // the lock so a refused second search can't nuke a running one.
                if (!tryStartOperation(act)) return@setOnClickListener
                var cancelled = false
                val accessors = extractAccessors(fmt)
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
                        cacheDir.deleteRecursively(); cacheDir.mkdirs()
                        searchSourceArchive = null; searchSourceFormat = fmt; searchSourceCacheBase = cacheDir
                        searchSourcePassword = null
                        // Each archive gets its own subdir so same-named entries
                        // from different archives never collide; a resolver maps
                        // every cached path back to (archive, password, out dir).
                        val resolver = mutableMapOf<String, SearchExtractSource>()
                        val textExts = TEXT_SEARCH_EXTS
                        for ((src, _) in all) {
                            if (cancelled) break
                            val pwd = resolveBatchPwd(src) ?: run { runOnUiThread { prog.dismiss(); toast(getString(R.string.msg_cancelled)) }; return@thread }
                            val sub = File(cacheDir, src.name)
                            sub.mkdirs()
                            for (e in merged.filter { !it.isDirectory && it.path.startsWith("📦 ${src.name}/") }) {
                                val rp = resolveBatchPath(e.path)?.second ?: continue
                                // Same rules as the Rust safe_join (reject
                                // ../, absolute, drive letters) + a canonical
                                // containment check before touching disk.
                                val rel = sanitizeEntryPath(rp) ?: continue
                                val ph = File(sub, rel)
                                if (!ph.canonicalPath.startsWith(sub.canonicalPath + "/")) continue
                                val archiveRel = "$rel"
                                val keyRel = "${src.name}/$rel"
                                resolver[keyRel] = SearchExtractSource(src, archiveRel, sub, pwd)
                                // Phase 1: touch placeholder files for ALL entries (filename search).
                                ph.parentFile?.mkdirs()
                                try { ph.createNewFile() } catch (_: Exception) {}
                                // Phase 2: extract only text files (overwrites placeholder).
                                if (e.name.substringAfterLast('.').lowercase() in textExts) {
                                    extractByFormat(fmt, src.path, sub.path, archiveRel, prefs, pwd)
                                }
                            }
                        }
                        searchSourceResolver = resolver
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

        dlg = AlertDialog.Builder(this).setCustomTitle(titleBar).setView(layout)
            .setPositiveButton(getString(R.string.extract_selected)) { _, _ ->
                val sel = selectedPaths.filter { p -> selectedPaths.none { o -> o != p && o.startsWith(p + "/") } }
                if (sel.isEmpty()) { toast(getString(R.string.msg_select_one)); return@setPositiveButton }
                // Lock first, then the spinner dialog (see extractAll).
                if (!tryStartOperation(this)) return@setPositiveButton
                val byArchive = mutableMapOf<File, MutableList<String>>()
                for (p in sel) {
                    val r = resolveBatchPath(p) ?: continue
                    byArchive.getOrPut(r.first) { mutableListOf() }.add(r.second)
                }
                var cancelled = false
                val accessors2 = extractAccessors(fmt)
                val pd2 = PollingProgressDialog(
                    this,
                    getString(R.string.title_batch_extract),
                    accessors2,
                    { n, b, t -> extractProgressMessage(this, n, b, t) },
                    getString(R.string.action_cancel),
                    { cancelled = true; accessors2.cancel() }
                )
                pd2.start()
                thread {
                    try {
                        var ok2 = true
                        var err: String? = null
                        var pwdCancelled2 = false
                        for ((src, paths) in byArchive) {
                            if (cancelled) { pwdCancelled2 = true; break }
                            val pwd = resolveBatchPwd(src)
                            if (pwd == null) { pwdCancelled2 = true; break }
                            val o = extractByFormat(fmt, src.path, uniqueFile(src.parentFile ?: currentDir, src.nameWithoutExtension).path, paths.joinToString("\n"), prefs, pwd)
                            if (!o.counts.ok) { ok2 = false; err = o.error; break }
                        }
                        runOnUiThread {
                            pd2.dismiss()
                            when {
                                pwdCancelled2 -> toast(getString(R.string.msg_cancelled))
                                ok2 -> toast(getString(R.string.msg_batch_done))
                                else -> toast(friendlyExtractError(this, err))
                            }
                            exitMultiSelect(); nav(currentDir)
                        }
                    } catch (e: Exception) {
                        runOnUiThread { pd2.dismiss(); toast(getString(R.string.err_extract_io, e.message ?: "")) }
                    } finally {
                        OperationLock.release()
                    }
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null).create()
        dlg.show()
    }
