package com.usefulunpacker

import android.app.AlertDialog
import android.app.ProgressDialog
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.File
import kotlin.concurrent.thread

private fun MainActivity.dp(id: Int) = resources.getDimensionPixelSize(id)
private fun MainActivity.spx(id: Int) = resources.getDimension(id) / resources.displayMetrics.scaledDensity

@Volatile internal var showBatchPreview_all: List<Pair<File, List<ArchiveEntry>>>? = null

internal fun MainActivity.batchArchives(): List<File> = allSelectedFiles().values.flatten().filter { it.isFile && (it.extension.lowercase() in ARCHIVE_EXTS || isVolumeFile(it) != null) }

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

/**
 * Batch-preview entry from the multi-select bar: only allowed when every
 * selected archive shares the same format (a mixed selection has no single
 * way to list all of them at once). Falls back to the per-format flow.
 */
internal fun MainActivity.startBatchPreviewOnly() {
        val archives = batchArchives(); if (archives.isEmpty()) { toast(getString(R.string.no_archives)); return }
        val fmts = archives.map { it -> detectFormat(it) ?: isVolumeFile(it) }
        val distinct = fmts.distinct()
        if (distinct.size != 1 || distinct[0] == null) {
            toast(getString(R.string.batch_preview_mixed))
            return
        }
        batchPreview(archives, distinct[0]!!)
    }

internal fun MainActivity.batchDirectExtract(archives: List<File>, fmt: String) {
        val parent = archives[0].parentFile ?: currentDir
        // Pre-compute unique output dirs
        val outDirs = archives.map { src -> uniqueFile(parent, src.nameWithoutExtension) }
        val labels = arrayOf(getString(R.string.extract_separate_dirs, outDirs.map { it.name }.joinToString(", ")), getString(R.string.action_extract))
        AlertDialog.Builder(this).setTitle(getString(R.string.title_extract_to))
            .setItems(labels) { _, w ->
                thread {
                    // Resolve the password BEFORE taking a scheduler slot — the
                    // modal can block ~30s and must not hog a slot + format lock.
                    var pwd: String? = null
                    if (archives.any { isPasswordProtected(it) }) {
                        pwd = promptPasswordSync(this)
                        if (pwd == null) {
                            runOnUiThread { toast(getString(R.string.msg_cancelled)); exitAllMultiSelect(); nav(currentDir) }
                            return@thread
                        }
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        // Lock first, then the progress dialog (see extractAll).
                        val opH = tryStartOperation(this, fmt)
                        var cancelled = false
                        val accessors = extractAccessors(fmt)
                        val prog = PollingProgressDialog(
                            this,
                            getString(R.string.msg_batch_extract_title, fmt),
                            accessors,
                            { n, b, t -> extractProgressMessage(this, n, b, t) },
                            getString(R.string.action_cancel),
                            { cancelled = true; accessors.cancel() },
                            opH
                        )
                        prog.start()
                        thread {
                            if (!opH.await()) return@thread
                            try {
                                var ok = true
                                var err: String? = null
                                var doneUntil = 0 // 分卷目录模式下本次已完整产出的输出目录数
                                for (i in archives.indices) {
                                    if (cancelled) { ok = false; break }
                                    val out = if (w == 0) outDirs[i] else parent
                                    val o = extractByFormat(fmt, archives[i].path, out.path, "", prefs, pwd ?: "")
                                    ok = o.counts.ok; if (!ok) { err = o.error; break }
                                    if (w == 0) doneUntil = i + 1
                                }
                                runOnUiThread {
                                    if (isFinishing || isDestroyed) return@runOnUiThread
                                    prog.dismiss()
                                    if (cancelled) {
                                        // 清掉本次新建的部分输出目录（uniqueFile 预计算的
                                        // 名字在提取前都不存在，删除是安全的）；解到父目录
                                        // 的模式与既有文件混在一起，无法区分，不清理。
                                        if (w == 0) thread { for (i in 0 until doneUntil) outDirs[i].deleteRecursively() }
                                        toast(getString(R.string.msg_cancelled))
                                    }
                                    else if (ok) toast(getString(R.string.msg_batch_done))
                                    else toast(friendlyExtractError(this, err))
                                    exitAllMultiSelect(); nav(currentDir)
                                }
                            } catch (e: Exception) {
                                runOnUiThread { if (isFinishing || isDestroyed) return@runOnUiThread; prog.dismiss(); toast(getString(R.string.err_extract_io, e.message ?: "")) }
                            } finally {
                                opH.release()
                            }
                        }
                    }
                }
            }.setNegativeButton(getString(R.string.action_cancel), null).show()
    }

internal fun MainActivity.batchPreview(archives: List<File>, fmt: String) {
        // Same-archive mutex: drop archives already open in another window
        // (volume sets count as one archive), so a preview can't race an open
        // copy elsewhere.
        val conflictNames = mutableListOf<String>()
        val filtered = archives.filter { a ->
            val k = archiveKey(a)
            val o = OpenArchiveRegistry.owner(k)
            if (o != null && o !== activeTab) { conflictNames.add(a.name); false } else true
        }
        if (filtered.isEmpty()) {
            toast(getString(R.string.msg_archive_open_skipped, conflictNames.size))
            return
        }
        val pd = ProgressDialog(this).apply { setTitle(getString(R.string.reading)); setMessage(getString(R.string.reading_archives, filtered.size)); setProgressStyle(ProgressDialog.STYLE_SPINNER); setCancelable(false); show() }
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
            for (src in filtered) {
                // Same listing dispatch as the single-archive paths. It used to
                // be a third hand-rolled copy that omitted the ZIP encoding
                // preference, so a GBK-named zip showed mojibake here and
                // rendered correctly when previewed on its own.
                val json = if (fmt in setOf("zip", "7z", "rar")) {
                    // A null password means the user cancelled; the caller
                    // stops the whole batch on pwdCancelled.
                    val p = resolvePwd(src)
                    if (p == null) null else listEntriesJson(fmt, src, p, nestedOnly = false)
                } else {
                    listEntriesJson(fmt, src, "", nestedOnly = false)
                }
                if (json != null) all.add(src to parseEntries(json))
                if (pwdCancelled) break
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                pd.dismiss(); if (all.isEmpty()) toast(getString(R.string.msg_cannot_read)); else { if (conflictNames.isNotEmpty()) toast(getString(R.string.msg_archive_open_skipped, conflictNames.size)); showBatchPreviewDialog(all, fmt, batchPwd, pwdCancelled) }
            }
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
            setTextColor(C["tertiary_light"]!!); textSize = spx(R.dimen.text_sm)
            setPadding(dp(R.dimen.space_md), dp(R.dimen.space_sm), dp(R.dimen.space_md), dp(R.dimen.space_xs)); setBackgroundColor(C["surface_dim"]!!)
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
            thread {
                // Resolve the password BEFORE taking a scheduler slot — the
                // modal can block ~30s and must not hog a slot + format lock.
                val p0 = resolveBatchPwd(arc)
                if (p0 == null) return@thread // cancelled; batchPwdCancelled gates later attempts
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    // Lock first, then the dual progress dialog (see extractAll).
                    val opH = tryStartOperation(this, fmt)
                    var cancelled = false
                    val accessors = extractAccessors(fmt)
                    val prog = PollingProgressDialog(
                        this,
                        getString(R.string.msg_extracting),
                        accessors,
                        { n, b, t -> extractProgressMessage(this, n, b, t) },
                        getString(R.string.action_cancel),
                        { cancelled = true; accessors.cancel() },
                        opH
                    )
                    prog.start()
                    thread {
                        if (!opH.await()) return@thread
                        try {
                            val o = extractByFormat(fmt, arc.path, cacheDir.path, origPath, prefs, p0)
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                prog.dismiss()
                                if (cancelled) toast(getString(R.string.msg_cancelled))
                                else if (o.counts.ok) previewLocalFile(this@showBatchPreviewDialog, File(cacheDir, origPath))
                                else toast(friendlyExtractError(this@showBatchPreviewDialog, o.error))
                            }
                        } catch (e: Exception) {
                            runOnUiThread { if (isFinishing || isDestroyed) return@runOnUiThread; prog.dismiss(); toast(getString(R.string.err_extract_io, e.message ?: "")) }
                        } finally {
                            opH.release()
                        }
                    }
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
        val titleBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(R.dimen.space_lg), dp(R.dimen.space_md), dp(R.dimen.space_sm), dp(R.dimen.space_md)); setBackgroundColor(C["surface"]!!) }
        titleBar.addView(TextView(this).apply {
            text = getString(R.string.batch_preview_title, all.map{it.first.name}.joinToString(", ").take(40)); setTextColor(C["primary"]!!); textSize = spx(R.dimen.text_title)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        val btnSearchTitle = ImageButton(this).apply {
            setImageResource(R.drawable.ic_search); setBackgroundColor(C["surface"]!!); setPadding(dp(R.dimen.space_xs), dp(R.dimen.space_xs), dp(R.dimen.space_xs), dp(R.dimen.space_xs))
            scaleType = ImageView.ScaleType.FIT_XY; layoutParams = LinearLayout.LayoutParams(52, 40)
            setOnClickListener {
                val cacheDir = File(cacheDir, "archive_search/batch_${all.map{it.first.nameWithoutExtension}.joinToString("_").take(50)}")
                thread {
                    // Pre-collect every archive's password BEFORE taking a
                    // scheduler slot — the modals can block ~30s each and must
                    // not sit on a slot + format lock.
                    val pwds = HashMap<File, String>()
                    for ((src, _) in all) {
                        val p = resolveBatchPwd(src) ?: run {
                            runOnUiThread { toast(getString(R.string.msg_cancelled)) }
                            return@thread
                        }
                        pwds[src] = p
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        // Lock first, then the dual progress dialog (the text-extraction
                        // phase reports byte progress); the work dir is cleared INSIDE
                        // the lock so a queued second search can't nuke a running one.
                        val opH = tryStartOperation(act, fmt)
                        var cancelled = false
                        val accessors = extractAccessors(fmt)
                        val prog = PollingProgressDialog(
                            act,
                            getString(R.string.preparing_search),
                            accessors,
                            { n, b, t -> extractProgressMessage(act, n, b, t) },
                            getString(R.string.action_cancel),
                            { cancelled = true; accessors.cancel() },
                            opH
                        )
                        prog.start()
                        thread {
                            if (!opH.await()) return@thread
                            try {
                                cacheDir.deleteRecursively(); cacheDir.mkdirs()
                                // Each archive gets its own subdir so same-named entries
                                // from different archives never collide; a resolver maps
                                // every cached path back to (archive, password, out dir).
                                val resolver = mutableMapOf<String, SearchExtractSource>()
                                val textExts = TEXT_SEARCH_EXTS
                                for ((src, _) in all) {
                                    if (cancelled) break
                                    val pwd = pwds[src] ?: continue
                                    val sub = File(cacheDir, src.name)
                                    sub.mkdirs()
                                    for (e in merged.filter { !it.isDirectory && it.path.startsWith("📦 ${src.name}/") }) {
                                        // 取消检查必须在内层：每个新的 Rust 解压入口都会
                                        // 重清 CANCEL 标志，不查的话取消后照样解完全部条目。
                                        if (cancelled) break
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
                                runOnUiThread {
                                    if (isFinishing || isDestroyed) return@runOnUiThread
                                    prog.dismiss()
                                    if (cancelled) { toast(getString(R.string.msg_cancelled)); return@runOnUiThread }
                                    // searchSource* 全局只在成功路径赋值：取消/失败时
                                    // 不能把全局搜索的解析器指向半成品缓存。
                                    searchSourceArchive = null; searchSourceFormat = fmt; searchSourceCacheBase = cacheDir
                                    searchSourcePassword = null
                                    searchSourceResolver = resolver
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
            }
        }
        titleBar.addView(btnSearchTitle)

        dlg = AlertDialog.Builder(this).setCustomTitle(titleBar).setView(layout)
            .setPositiveButton(getString(R.string.extract_selected)) { _, _ ->
                val sel = selectedPaths.filter { p -> selectedPaths.none { o -> o != p && o.startsWith(p + "/") } }
                if (sel.isEmpty()) { toast(getString(R.string.msg_select_one)); return@setPositiveButton }
                val byArchive = mutableMapOf<File, MutableList<String>>()
                for (p in sel) {
                    val r = resolveBatchPath(p) ?: continue
                    byArchive.getOrPut(r.first) { mutableListOf() }.add(r.second)
                }
                thread {
                    // Pre-collect every archive's password BEFORE taking a
                    // scheduler slot — modals must not sit on a slot + lock.
                    val pwds = HashMap<File, String>()
                    var pwdCancelled2 = false
                    for ((src, _) in byArchive) {
                        val p = resolveBatchPwd(src)
                        if (p == null) { pwdCancelled2 = true; break }
                        pwds[src] = p
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed || pwdCancelled2) {
                            if (pwdCancelled2 && !(isFinishing || isDestroyed)) toast(getString(R.string.msg_cancelled))
                            return@runOnUiThread
                        }
                        // Lock first, then the spinner dialog (see extractAll).
                        val opH = tryStartOperation(this, fmt)
                        var cancelled = false
                        val accessors2 = extractAccessors(fmt)
                        val pd2 = PollingProgressDialog(
                            this,
                            getString(R.string.title_batch_extract),
                            accessors2,
                            { n, b, t -> extractProgressMessage(this, n, b, t) },
                            getString(R.string.action_cancel),
                            { cancelled = true; accessors2.cancel() },
                            opH
                        )
                        pd2.start()
                        thread {
                            if (!opH.await()) return@thread
                            try {
                                var ok2 = true
                                var err: String? = null
                                for ((src, paths) in byArchive) {
                                    if (cancelled) break
                                    val pwd = pwds[src] ?: continue
                                    val o = extractByFormat(fmt, src.path, uniqueFile(src.parentFile ?: currentDir, src.nameWithoutExtension).path, paths.joinToString("\n"), prefs, pwd)
                                    if (!o.counts.ok) { ok2 = false; err = o.error; break }
                                }
                                runOnUiThread {
                                    if (isFinishing || isDestroyed) return@runOnUiThread
                                    pd2.dismiss()
                                    when {
                                        // 取消不算成功：不能弹「批量完成」，也保留选中
                                        // 让用户可直接重试（与 compressMerged 策略一致）。
                                        cancelled -> toast(getString(R.string.msg_cancelled))
                                        ok2 -> { toast(getString(R.string.msg_batch_done)); exitAllMultiSelect(); nav(currentDir) }
                                        else -> toast(friendlyExtractError(this, err))
                                    }
                                }
                            } catch (e: Exception) {
                                runOnUiThread { if (isFinishing || isDestroyed) return@runOnUiThread; pd2.dismiss(); toast(getString(R.string.err_extract_io, e.message ?: "")) }
                            } finally {
                                opH.release()
                            }
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.action_cancel), null).create()
        // Keep the tab strip tappable while batch preview is open (bottom-align
        // below toolbar + tab bar), so the user can still switch windows.
        dlg.window?.let { w ->
            val dm = resources.displayMetrics
            val density = dm.density
            val top = 50f * density + 56f * density + 130f * density
            w.setGravity(Gravity.BOTTOM)
            val sheetW = minOf(dm.widthPixels, resources.getDimensionPixelSize(R.dimen.dialog_max_width))
            w.setLayout(sheetW, (dm.heightPixels - top).toInt().coerceAtLeast(0))
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x99000000.toInt()))
        }
        dlg.show()
    }
