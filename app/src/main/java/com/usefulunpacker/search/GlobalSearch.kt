package com.usefulunpacker

import android.app.AlertDialog
import android.app.ProgressDialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.widget.AdapterView.OnItemClickListener
import java.io.File
import kotlin.concurrent.thread

/**
 * Shared state for archive-content search. Set by the preview/batch preview
 * search flows so that tapping a result can extract the real file on demand
 * (placeholder files are 0-byte). @Volatile: written on a worker thread,
 * read on the UI thread.
 */
@Volatile internal var searchSourceArchive: File? = null
@Volatile internal var searchSourceFormat: String? = null
@Volatile internal var searchSourceCacheBase: File? = null
@Volatile internal var searchSourcePassword: String? = null
@Volatile internal var searchSourceResolver: Map<String, SearchExtractSource>? = null

/** Where a cached search result placeholder came from: which archive, its
 *  path inside that archive, the output dir to re-extract into, and the
 *  password (empty when the archive is not encrypted). */
data class SearchExtractSource(val archive: File, val internalPath: String, val outDir: File, val password: String)

internal fun MainActivity.globalSearch(startDir: File? = null, tempDir: File? = null) {
        // Main-menu searches (no archive context) must not carry stale
        // archive/password/resolver state from a previous in-archive search.
        if (tempDir == null) {
            searchSourceArchive = null
            searchSourceFormat = null
            searchSourceCacheBase = null
            searchSourcePassword = null
            searchSourceResolver = null
        }
        var searchMode = 0 // 0=filename, 1=content
        var searchDir = startDir ?: currentDir
        // Synchronized collections: written by the worker thread, read by the
        // UI thread (resultAdapter.refresh / stats). Individual ops are safe;
        // iteration (toList) still needs an explicit synchronized block.
        val results = java.util.Collections.synchronizedList(mutableListOf<SearchResult>())
        var searchThread: Thread? = null
        lateinit var searchDialog: AlertDialog
        var currentLimit = 200
        val seenFiles = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        var queryText = ""
        var currentMaxFileSize = Long.MAX_VALUE

        // ─── XML-based UI ───
        val view = layoutInflater.inflate(R.layout.dialog_global_search, null)
        val tvDir = view.findViewById<TextView>(R.id.searchScope)
        val btnChangeDir = view.findViewById<Button>(R.id.btnChangeDir)
        val btnFilename = view.findViewById<Button>(R.id.btnFilename)
        val btnContent = view.findViewById<Button>(R.id.btnContent)
        val etQuery = view.findViewById<EditText>(R.id.etQuery)
        val btnClear = view.findViewById<ImageButton>(R.id.btnClear)
        val btnSearch = view.findViewById<Button>(R.id.btnSearch)
        val searchProgress = view.findViewById<ProgressBar>(R.id.searchProgress)
        val tvStats = view.findViewById<TextView>(R.id.tvStats)
        val btnContinue = view.findViewById<Button>(R.id.btnContinue)
        val btnClose = view.findViewById<Button>(R.id.btnClose)
        val listResults = view.findViewById<ListView>(R.id.listResults)

        tvDir.text = getString(R.string.search_scope, searchDir.path)
        btnChangeDir.text = getString(R.string.action_change)
        btnFilename.text = getString(R.string.filename_search)
        btnContent.text = getString(R.string.content_search)
        etQuery.hint = getString(R.string.input_search)
        btnSearch.text = getString(R.string.search_button)
        tvStats.text = getString(R.string.search_empty)
        btnContinue.text = getString(R.string.continue_scan)
        btnClose.text = getString(R.string.action_close)

        fun selectMode(mode: Int) {
            searchMode = mode
            val onColor = C["accent"]!!; val offBg = 0xFF2a2a2a.toInt()
            btnFilename.setBackgroundColor(if (mode == 0) onColor else offBg)
            btnFilename.setTextColor(if (mode == 0) 0xFF000000.toInt() else C["tertiary"]!!)
            btnContent.setBackgroundColor(if (mode == 1) onColor else offBg)
            btnContent.setTextColor(if (mode == 1) 0xFF000000.toInt() else C["tertiary"]!!)
        }
        btnFilename.setOnClickListener { selectMode(0) }
        btnContent.setOnClickListener { selectMode(1) }
        selectMode(0)

        btnChangeDir.setOnClickListener {
            showFolderPicker(this, searchDir, false) { d ->
                searchDir = d
                tvDir.text = getString(R.string.search_scope, d.path)
            }
        }
        btnClear.setOnClickListener { etQuery.setText("") }

        // Results adapter — uses snapshot to avoid threading crashes
        val resultAdapter = object : BaseAdapter() {
            @Volatile private var snapshot: List<SearchResult> = emptyList()
            fun refresh() { snapshot = synchronized(results) { results.toList() }; notifyDataSetChanged() }
            override fun getCount() = snapshot.size
            override fun getItem(pos: Int) = snapshot.getOrNull(pos) ?: SearchResult(File(""))
            override fun getItemId(pos: Int) = pos.toLong()
            override fun getView(pos: Int, v: View?, p: ViewGroup?): View {
                val view = v ?: layoutInflater.inflate(R.layout.item_search_result, p, false)
                val r = snapshot.getOrNull(pos) ?: return view
                val isArc = formatOfName(r.file.name) != null
                view.findViewById<ImageView>(R.id.search_icon).apply {
                    setImageResource(if (isArc) R.drawable.ic_archive else if (r.file.isDirectory) R.drawable.ic_folder else R.drawable.ic_file)
                    setColorFilter(if (isArc) iconTintForName(r.file.name) else if (r.file.isDirectory) C["warning"]!! else C["secondary"]!!)
                }
                view.findViewById<TextView>(R.id.search_text1).apply {
                    val matchTag = if (r.matchCount > 1) getString(R.string.match_tag, r.matchCount) else ""
                    val lineTag = if (r.lineNumber > 0) getString(R.string.line_tag, r.lineNumber) else ""
                    text = "${r.file.name}$lineTag$matchTag"
                }
                view.findViewById<TextView>(R.id.search_text2).text =
                    if (r.snippet.isNotEmpty()) "${r.file.parent}\n${r.snippet}"
                    else "${r.file.parent}  •  ${fmt(r.file.length())}"
                return view
            }
        }

        listResults.adapter = resultAdapter
        listResults.onItemClickListener = OnItemClickListener { _, _, pos, _ ->
            val r = resultAdapter.getItem(pos) as SearchResult
            if (r.file.path.isEmpty()) return@OnItemClickListener
            // Keep the search dialog open: the file preview closes back into
            // the search, so the user can keep clicking results without
            // re-typing the query.
            val fmt = searchSourceFormat
            val cacheBase = searchSourceCacheBase
            if (r.file.length() == 0L && fmt != null && cacheBase != null) {
                val relPath = r.file.absolutePath.removePrefix(cacheBase.absolutePath + "/")
                // Resolve the archive + password the cached placeholder came from.
                // Batch search stores a per-path resolver; single-archive search
                // falls back to the three source globals.
                val src = searchSourceResolver?.get(relPath)
                    ?: searchSourceArchive?.let { SearchExtractSource(it, relPath, cacheBase, searchSourcePassword ?: "") }
                    ?: return@OnItemClickListener
                // Lock first, then the dual progress dialog (consistent with extractAll).
                if (!tryStartOperation(this)) return@OnItemClickListener
                var cancelled = false
                val accessors = extractAccessors(fmt)
                val pd = PollingProgressDialog(
                    this,
                    getString(R.string.msg_extracting),
                    accessors,
                    { n, b, t -> extractProgressMessage(this, n, b, t) },
                    getString(R.string.action_cancel),
                    { cancelled = true; accessors.cancel() }
                )
                pd.start()
                thread {
                    try {
                        extractByFormat(fmt, src.archive.path, src.outDir.path, src.internalPath, prefs, src.password)
                        runOnUiThread {
                            pd.dismiss()
                            if (cancelled) toast(getString(R.string.msg_cancelled)) else previewClickedFile(r, queryText)
                        }
                    } finally {
                        OperationLock.release()
                    }
                }
            } else {
                previewClickedFile(r, queryText)
            }
        }

        searchDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.msg_search_global))
            .setView(view)
            .create()
        btnClose.setOnClickListener { searchDialog.dismiss() }
        // Size the window BEFORE show so the first layout pass is already the
        // final size — setting it in onShow resizes after the enter animation
        // starts, causing a flash/jump and an empty-results bottom bar that
        // isn't pinned to the window bottom.
        val metrics = this.resources.displayMetrics
        searchDialog.window?.setLayout((metrics.widthPixels * 0.94).toInt(), (metrics.heightPixels * 0.85).toInt())
        searchDialog.show()

        // Launch search
        fun doSearch(query: String, mode: Int, maxFileSize: Long, isContinue: Boolean = false) {
            searchThread?.interrupt()
            if (query.isEmpty()) { toast(getString(R.string.msg_enter_keyword)); return }
            currentMaxFileSize = maxFileSize
            if (!isContinue) { synchronized(results) { results.clear() }; synchronized(seenFiles) { seenFiles.clear() }; currentLimit = 200 }
            else currentLimit += 200
            resultAdapter.refresh()
            searchProgress.visibility = View.VISIBLE
            btnContinue.visibility = View.GONE
            tvStats.text = if (isContinue) getString(R.string.continue_scan) else getString(R.string.search_scanning)
            val scanned = intArrayOf(0)
            searchThread = thread {
                val t = Thread.currentThread()
                walkSearch(query.lowercase(), searchDir, mode, results, currentLimit, maxFileSize, scanned, seenFiles)
                runOnUiThread {
                    // A newer search may have started while this one was
                    // interrupted — only publish results if we're still the
                    // current search thread.
                    if (searchThread !== t) return@runOnUiThread
                    searchProgress.visibility = View.GONE
                    val hasMore = results.size >= currentLimit && results.size < 10000
                    btnContinue.visibility = if (hasMore) View.VISIBLE else View.GONE
                    tvStats.text = getString(R.string.search_result_stats, results.size, scanned[0])
                    resultAdapter.refresh()
                    if (results.isEmpty()) toast(getString(R.string.msg_no_results))
                }
            }
            // Periodically update stats while searching — capture the thread
            // locally so a replaced searchThread doesn't keep this poller
            // alive past the thread it watches, and guard the UI update so a
            // stale poller never stomps the current search's stats.
            val watched = searchThread
            thread {
                var lastScanned = 0
                while (watched?.isAlive == true) {
                    Thread.sleep(200)
                    val cur = scanned[0]
                    if (cur != lastScanned) {
                        lastScanned = cur
                        runOnUiThread {
                            if (searchThread === watched) {
                                tvStats.text = getString(R.string.search_progress_stats, cur, results.size)
                            }
                        }
                    }
                }
            }
        }

        // IME search action (content mode asks for a per-file size limit)
        fun runSearch() {
            queryText = etQuery.text.toString().trim()
            if (queryText.isEmpty()) { toast(getString(R.string.msg_enter_keyword)); return }
            if (searchMode == 1) {
                val labels = resources.getStringArray(R.array.search_size_limits) + getString(R.string.level_extreme)
                val limits = longArrayOf(100_000L, 500_000L, 1_000_000L, 5_000_000L, 10_000_000L, Long.MAX_VALUE)
                val body = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(C["surface"]!!)
                }
                body.addView(TextView(this).apply {
                    text = getString(R.string.size_limit_warn)
                    setTextColor(C["secondary"]!!); textSize = 13f
                    setPadding(24, 16, 24, 12)
                })
                val radioGroup = RadioGroup(this).apply { setPadding(24, 0, 24, 0) }
                labels.forEachIndexed { i, label ->
                    val rb = RadioButton(this).apply {
                        text = label; id = i
                        setTextColor(C["primary"]!!); textSize = 15f
                        setPadding(16, 10, 16, 10)
                        if (i == 2) isChecked = true
                    }
                    radioGroup.addView(rb, LinearLayout.LayoutParams(MATCH, WRAP))
                }
                body.addView(radioGroup)
                body.addView(View(this).apply {
                    setBackgroundColor(C["divider_subtle"]!!)
                    layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { setMargins(24, 8, 24, 8) }
                })
                val customRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(40, 8, 24, 16)
                }
                customRow.addView(TextView(this).apply {
                    text = getString(R.string.custom_mb); setTextColor(C["secondary"]!!); textSize = 13f
                    gravity = Gravity.CENTER_VERTICAL
                })
                val customInput = EditText(this).apply {
                    hint = getString(R.string.custom_mb); setTextColor(C["primary"]!!); setHintTextColor(C["hint"]!!)
                    setBackgroundColor(C["surface_dark"]!!); setPadding(8, 4, 8, 4); textSize = 14f; gravity = Gravity.CENTER
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    layoutParams = LinearLayout.LayoutParams(120, WRAP).apply { setMargins(12, 0, 0, 0) }
                }
                customRow.addView(customInput)
                body.addView(customRow)
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.size_limit_warn))
                    .setView(body)
                    .setPositiveButton(getString(R.string.search_button)) { _, _ ->
                        val custom = customInput.text.toString().toDoubleOrNull()
                        val bytes = if (custom != null && custom > 0) (custom * 1_000_000).toLong()
                                   else limits[radioGroup.checkedRadioButtonId.coerceIn(0, limits.size - 1)]
                        doSearch(queryText, 1, bytes)
                    }
                    .setNegativeButton(getString(R.string.action_cancel), null)
                    .show()
            } else {
                doSearch(queryText, 0, Long.MAX_VALUE)
            }
        }
        etQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { runSearch(); true } else false
        }
        btnSearch.setOnClickListener { runSearch() }

        btnContinue.setOnClickListener {
            if (queryText.isEmpty()) return@setOnClickListener
            doSearch(queryText, searchMode, currentMaxFileSize, true)
        }
    }

internal fun MainActivity.previewClickedFile(r: SearchResult, highlightQuery: String = "") {
        if (r.snippet.isNotEmpty()) {
            showTextPreview(this, r.file, r.lineNumber, highlightQuery)
        } else {
            val ext = r.file.name.lowercase().substringAfterLast('.')
            if (ext in PREVIEW_EXTS || ext in TEXT_SEARCH_EXTS) {
                previewLocalFile(this, r.file)
            } else {
                val parent = r.file.parentFile
                if (parent != null) { nav(parent); toast(r.file.name) }
                else toast(r.file.path)
            }
        }
    }

internal fun MainActivity.walkSearch(
        query: String, startDir: File, mode: Int, results: MutableList<SearchResult>, limit: Int,
        maxFileSize: Long, scanned: IntArray, seenFiles: MutableSet<String>
    ) {
        // Iterative DFS — deeply nested trees can't overflow the call stack.
        val stack = ArrayDeque<File>()
        stack.add(startDir)
        while (stack.isNotEmpty() && results.size < limit && !Thread.currentThread().isInterrupted) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (results.size >= limit || Thread.currentThread().isInterrupted) return
                try {
                    if (child.isFile) {
                        scanned[0]++
                        val absPath = child.absolutePath
                        if (seenFiles.contains(absPath)) continue
                        if (mode == 0) {
                            // Filename search
                            if (child.name.lowercase().contains(query)) {
                                seenFiles.add(absPath)
                                results.add(SearchResult(child))
                            }
                        } else {
                            // Content search: scan whole file, count matches, group per file.
                            val ext = child.extension.lowercase()
                            // Content search reads the whole file into memory (strict
                            // decode), so cap the size even when the user picked the
                            // "extreme" limit — a 2GB text file would otherwise OOM.
                            if (ext in TEXT_SEARCH_EXTS && child.length() < maxFileSize && child.length() <= CONTENT_SEARCH_MAX) {
                                var matchCount = 0
                                var firstSnippet = ""
                                var firstLine = 0
                                var lineNum = 0
                                try {
                                    // Auto-detect the encoding per file (BOM →
                                    // strict UTF-8 → SJIS/GBK heuristic), falling
                                    // back to the global setting when unsure.
                                    val bytes = child.readBytes()
                                    val enc = detectBestEncoding(bytes)
                                        ?: prefs.getString("text_encoding", "UTF-8") ?: "UTF-8"
                                    val text = decodeTextStrict(bytes, enc)
                                    text.lines().forEach { line ->
                                            if (Thread.currentThread().isInterrupted) return@forEach
                                            lineNum++
                                            if (line.lowercase().contains(query)) {
                                                matchCount++
                                                if (firstLine == 0) {
                                                    firstLine = lineNum
                                                    firstSnippet = line.trim().take(120)
                                                }
                                            }
                                        }
                                } catch (_: Exception) { }
                                if (matchCount > 0) {
                                    seenFiles.add(absPath)
                                    results.add(SearchResult(child, firstSnippet, firstLine, matchCount))
                                }
                            }
                        }
                    } else if (child.isDirectory) {
                        stack.add(child)
                    }
                } catch (_: Exception) { }
            }
        }
    }
