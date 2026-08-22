package com.usefulunpacker

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.concurrent.thread

/** Decodes an image as a static Bitmap capped to [maxPx] on the longest side
 *  (sampled), safe for very large files. GIF/WebP yield their first frame.
 *  Returns null on failure; safe off the UI thread. */
fun decodeBitmapCapped(file: File, maxPx: Int): Bitmap? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        try {
            val src = android.graphics.ImageDecoder.createSource(file)
            android.graphics.ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                // Force a SOFTWARE bitmap: hardware bitmaps can't be drawn onto
                // a software Canvas (editor autosave/save) nor read with
                // getPixel() (eyedrop) — both crashed before this.
                decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE)
                val m = maxOf(info.size.width, info.size.height)
                if (m > maxPx) decoder.setTargetSampleSize(m / maxPx + 1)
            }
        } catch (_: Exception) {
            decodeBitmapSampled(file, maxPx)
        }
    } else decodeBitmapSampled(file, maxPx)
}

private fun decodeBitmapSampled(file: File, maxPx: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var s = 1
        while (bounds.outWidth / (s * 2) >= maxPx && bounds.outHeight / (s * 2) >= maxPx) s *= 2
        val opt = BitmapFactory.Options().apply { inSampleSize = s }
        BitmapFactory.decodeFile(file.path, opt)
    } catch (_: Exception) { null }
}

fun previewLocalFile(activity: AppCompatActivity, f: File) {
    val name = f.name.lowercase()
    val ext = if (name.contains('.')) name.substringAfterLast('.') else ""
    when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp" -> showImagePreview(activity, f)
        "mp3", "ogg", "wav", "aac", "flac", "aif", "aiff", "m4a" -> playAudio(activity, f)
        "mp4", "mkv", "avi", "mov", "webm" -> playVideo(activity, f)
        else -> showTextPreview(activity, f)
    }
}

fun showImagePreview(activity: AppCompatActivity, file: File) {
    // Decode OFF the UI thread with a target sample size — a huge PNG/JPG
    // decoded at full size on the main thread ANRs and can OOM.
    val metrics = activity.resources.displayMetrics
    val screenW = metrics.widthPixels
    val screenH = (metrics.heightPixels * 0.8).toInt()
    lateinit var d: AlertDialog
    thread {
        fun sampleFor(w: Int, h: Int): Int {
            var s = 1
            while (w / (s * 2) >= screenW && h / (s * 2) >= screenH) s *= 2
            return s
        }
        fun decodeSampled(): android.graphics.drawable.Drawable? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0) return null
            val opt = BitmapFactory.Options().apply { inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight) }
            return BitmapFactory.decodeFile(file.path, opt)?.let { android.graphics.drawable.BitmapDrawable(activity.resources, it) }
        }
        val drawable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val src = android.graphics.ImageDecoder.createSource(file)
                android.graphics.ImageDecoder.decodeDrawable(src) { decoder, info, _ ->
                    decoder.setTargetSampleSize(sampleFor(info.size.width, info.size.height))
                }.also { (it as? android.graphics.drawable.AnimatedImageDrawable)?.start() }
            } catch (_: Exception) {
                decodeSampled()
            }
        } else {
            decodeSampled()
        }
        activity.runOnUiThread {
            if (drawable == null) {
                Toast.makeText(activity, activity.getString(R.string.msg_cannot_decode), Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            val iv = ImageView(activity).apply {
                setImageDrawable(drawable)
                setBackgroundColor(0xFF000000.toInt())
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                maxWidth = screenW
                maxHeight = screenH
            }
            val scroll = ScrollView(activity).apply {
                addView(iv)
                setBackgroundColor(0xFF000000.toInt())
                // Honor/EMUI NPEs drawing scrollbars on custom views — disable (scroll works).
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
            }

            // Top bar: filename + overflow menu (编辑 / 转换为其他格式) — keeps
            // the bottom of the preview clean (only Close).
            val overflowBtn = ImageButton(activity).apply {
                setImageResource(R.drawable.ic_overflow)
                setBackgroundColor(0x00000000)
                setColorFilter(C["accent"]!!)
                setPadding(10, 10, 10, 10)
                contentDescription = activity.getString(R.string.action_more)
                setOnClickListener { v ->
                    PopupMenu(activity, v).apply {
                        menu.add(0, 1, 0, activity.getString(R.string.action_edit)).setOnMenuItemClickListener { d.dismiss(); showImageEditor(activity, file); true }
                        menu.add(0, 2, 0, activity.getString(R.string.img_convert_menu)).setOnMenuItemClickListener { d.dismiss(); showImageConvert(activity, file); true }
                        show()
                    }
                }
            }
            val title = TextView(activity).apply {
                text = file.name
                setTextColor(C["primary"]!!)
                textSize = 16f
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            val topBar = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10, 4, 8, 4)
                addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(overflowBtn, LinearLayout.LayoutParams((40 * activity.resources.displayMetrics.density).toInt(), (40 * activity.resources.displayMetrics.density).toInt()))
            }
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(topBar)
                addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            }
            d = AlertDialog.Builder(activity)
                .setView(root)
                .setPositiveButton(activity.getString(R.string.action_close), null)
                .create()
            (activity as? MainActivity)?.resetPagerInputOnDialogDismiss(d)
            d.show()
        }
    }
}

fun showTextPreview(activity: AppCompatActivity, file: File, highlightLine: Int = 0, highlightQuery: String = "", showEdit: Boolean = true, onEdited: (() -> Unit)? = null) {
    // Strict decode with the user-chosen global text encoding (BOM-aware
    // UTF-8/UTF-16, REPLACE for invalid bytes — see decodeTextStrict).
    val prefs = (activity as? MainActivity)?.prefs
    val data = readPrefix(file, 1 shl 20)
    // Compiled TJS2 scripts are binary bytecode, not text — a clear message
    // beats a garbled preview.
    if (isTjsBytecode(data)) {
        Toast.makeText(activity, activity.getString(R.string.msg_tjs_compiled), Toast.LENGTH_LONG).show()
        return
    }
    // Auto-detect the encoding: BOM (UTF-16/UTF-8) wins, then strict UTF-8,
    // then a Shift-JIS vs GBK heuristic; fall back to the global pref when
    // nothing is confident.
    var curEncoding = detectBestEncoding(data)
        ?: prefs?.getString("text_encoding", "UTF-8") ?: "UTF-8"
    val encLabels = arrayOf(
        activity.getString(R.string.encoding_utf8), activity.getString(R.string.encoding_sjis),
        activity.getString(R.string.encoding_gbk), activity.getString(R.string.encoding_utf16))

    fun decode(): String = runCatching { decodeTextStrict(data, curEncoding) }
        .getOrElse { activity.getString(R.string.cannot_read_file, it.message ?: "") }

    // Markdown/RTF rich rendering toggle (md/markdown/rtf only). Default on:
    // md is turned into styled text, rtf is stripped of control words. A plain
    // view is always one tap away.
    val isRich = isRichTextExt(file.name.lowercase().substringAfterLast('.'))
    var richEnabled = isRich

    // Assigned below (after buildContent is defined); the encoding row calls it
    // to re-render the content with the new encoding without leaving the dialog.
    lateinit var refreshContent: () -> Unit

    // Encoding switch row: re-decode instantly so a wrong-pick preview fixes
    // itself (and persists the choice as the new default) without leaving the
    // dialog — the classic "GBK file read as UTF-8" flow.
    val encRow = TextView(activity)
    encRow.apply {
        text = "🔤 " + activity.getString(R.string.settings_text_encoding) + ": " + encLabels[TEXT_ENCODINGS.indexOf(curEncoding).coerceAtLeast(0)] + "  ▾"
        setTextColor(C["accent"]!!)
        textSize = 13f
        setPadding(16, 12, 16, 12)
        setBackgroundColor(C["surface_raised"]!!)
        isClickable = true
        setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.settings_text_encoding))
                .setSingleChoiceItems(encLabels, TEXT_ENCODINGS.indexOf(curEncoding).coerceAtLeast(0)) { d, w ->
                    curEncoding = TEXT_ENCODINGS[w]
                    encRow.text = "🔤 " + activity.getString(R.string.settings_text_encoding) + ": " + encLabels[w] + "  ▾"
                    prefs?.edit()?.putString("text_encoding", curEncoding)?.apply()
                    d.dismiss()
                    refreshContent()
                }
                .setNegativeButton(activity.getString(R.string.action_cancel), null)
                .show()
        }
    }

    lateinit var contentRoot: LinearLayout

    fun buildContent(): android.view.View {
        val raw = decode()
        // Rich rendering happens on the FULL decoded text, before the 50K
        // display cap (a heading at the end must still be styled). Search
        // highlight still matches on the raw text — offsets are preserved by
        // the renderer.
        val rendered: CharSequence? = when {
            richEnabled && isRich && file.name.lowercase().endsWith(".rtf") ->
                stripRtf(raw.take(50000))
            richEnabled && isRich ->
                renderMarkdown(raw.take(50000))
            else -> null
        }
        // displayText is what search matches against and the base for the
        // TextView; rendered (when rich) carries the style spans.
        var displayText = raw.take(50000)
        // A search highlight must never be silently lost: if the match is past
        // the 50K display cap, show a window around the first match instead.
        if (highlightQuery.isNotEmpty()) {
            val idx = raw.indexOf(highlightQuery, ignoreCase = true)
            if (idx >= displayText.length && idx >= 0) {
                val start = (idx - 20000).coerceAtLeast(0)
                val end = (idx + 30000).coerceAtMost(raw.length)
                displayText = raw.substring(start, end)
            }
        }
        val matchPos = mutableListOf<Int>()
        var spannable: android.text.SpannableString? = null
        if (highlightQuery.isNotEmpty()) {
            spannable = android.text.SpannableString(displayText)
            var idx = 0
            val lowerText = displayText.lowercase()
            val lowerQuery = highlightQuery.lowercase()
            while (true) {
                val pos = lowerText.indexOf(lowerQuery, idx)
                if (pos < 0) break
                matchPos.add(pos); idx = pos + 1
            }
        }
        fun applyHighlights(sel: Int) {
            val s = spannable ?: return
            val len = highlightQuery.length
            for (span in s.getSpans(0, s.length, android.text.style.BackgroundColorSpan::class.java)) s.removeSpan(span)
            for (i in matchPos.indices) {
                val color = if (i == sel) C["search_hilite_sel"]!! else C["search_hilite_oth"]!!
                s.setSpan(android.text.style.BackgroundColorSpan(color), matchPos[i], matchPos[i] + len, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (spannable != null && matchPos.isNotEmpty()) applyHighlights(0) else spannable = null

        // Prefer the rich-rendered text; only when a highlight is active do we
        // fall back to the plain-highlight spannable (rendering and highlight
        // on the same CharSequence are hard to compose portably).
        val tvText: CharSequence = when {
            spannable != null -> spannable
            rendered != null -> rendered
            else -> displayText
        }
        val tv = TextView(activity).apply {
            this.text = tvText
            setTextColor(C["primary"]!!)
            textSize = 12f
            setBackgroundColor(C["surface_dark"]!!)
            setPadding(16, 16, 16, 16)
            isVerticalScrollBarEnabled = false
            movementMethod = android.text.method.ScrollingMovementMethod()
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroll = ScrollView(activity).apply {
            addView(tv)
            setBackgroundColor(C["surface_dark"]!!)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }
        val dragBar = DragScrollBar(activity).apply { attach(scroll) }
        val frame = FrameLayout(activity).apply {
            addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(dragBar, FrameLayout.LayoutParams(
                (20 * activity.resources.displayMetrics.density).toInt(), MATCH, Gravity.END))
        }
        if (highlightLine > 0) {
            tv.post {
                val layout = tv.layout ?: return@post
                val lineIdx = (highlightLine - 1).coerceIn(0, layout.lineCount - 1)
                val y = layout.getLineTop(lineIdx) - (scroll.height / 3)
                scroll.scrollTo(0, y.coerceAtLeast(0))
            }
        }

        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))

        if (matchPos.size > 1) {
            var curMatch = 0
            if (highlightLine > 0) {
                val layout = tv.layout
                if (layout != null) {
                    val targetLine = highlightLine - 1
                    curMatch = matchPos.indices.minByOrNull {
                        kotlin.math.abs(layout.getLineForOffset(matchPos[it]) - targetLine)
                    } ?: 0
                }
            }
            val navBar = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                setBackgroundColor(C["nav_bg"]!!); setPadding(0, 6, 0, 6)
            }
            val btnPrev = Button(activity).apply {
                text = activity.getString(R.string.search_prev); textSize = 12f; isAllCaps = false
                setTextColor(C["accent"]!!); background = null; setPadding(12, 4, 12, 4)
            }
            val tvCounter = TextView(activity).apply {
                gravity = Gravity.CENTER; textSize = 12f; setTextColor(C["tertiary_light"]!!); setPadding(20, 4, 20, 4)
            }
            val btnNext = Button(activity).apply {
                text = activity.getString(R.string.search_next); textSize = 12f; isAllCaps = false
                setTextColor(C["accent"]!!); background = null; setPadding(12, 4, 12, 4)
            }
            fun scrollToMatch(idx: Int) {
                curMatch = idx.coerceIn(0, matchPos.size - 1)
                tvCounter.text = "${curMatch + 1} / ${matchPos.size}"
                applyHighlights(curMatch)
                tv.text = spannable
                tv.post {
                    val layout = tv.layout ?: return@post
                    val line = layout.getLineForOffset(matchPos[curMatch])
                    val y = layout.getLineTop(line) - (scroll.height / 3)
                    scroll.scrollTo(0, y.coerceAtLeast(0))
                }
            }
            btnPrev.setOnClickListener { scrollToMatch(curMatch - 1) }
            btnNext.setOnClickListener { scrollToMatch(curMatch + 1) }
            navBar.addView(btnPrev)
            navBar.addView(tvCounter, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            navBar.addView(btnNext)
            col.addView(navBar, LinearLayout.LayoutParams(MATCH, WRAP))
            scrollToMatch(curMatch)
        }
        return col
    }

    // Wrong-encoding detection on the initial render.
    val initialRaw = decode()
    if (textLooksGarbled(initialRaw)) {
        Toast.makeText(activity, activity.getString(R.string.preview_encoding_hint, curEncoding), Toast.LENGTH_LONG).show()
    } else if (curEncoding != "UTF-8" && looksLikeUtf8(data)) {
        Toast.makeText(activity, activity.getString(R.string.preview_looks_utf8_hint), Toast.LENGTH_LONG).show()
    }

    contentRoot = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    contentRoot.addView(buildContent(), LinearLayout.LayoutParams(MATCH, 0, 1f))
    refreshContent = {
        contentRoot.removeAllViews()
        contentRoot.addView(buildContent(), LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    // Render toggle row (md/markdown/rtf only): tap to flip between styled and
    // plain text without leaving the dialog.
    val richRow = TextView(activity)
    if (isRich) {
        richRow.apply {
            text = activity.getString(R.string.preview_rich) + ": " +
                if (richEnabled) activity.getString(R.string.preview_rich_on) else activity.getString(R.string.preview_rich_off)
            setTextColor(C["accent"]!!)
            textSize = 13f
            setPadding(16, 10, 16, 10)
            setBackgroundColor(C["surface"]!!)
            isClickable = true
            setOnClickListener {
                richEnabled = !richEnabled
                text = activity.getString(R.string.preview_rich) + ": " +
                    if (richEnabled) activity.getString(R.string.preview_rich_on) else activity.getString(R.string.preview_rich_off)
                refreshContent()
            }
        }
    }

    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(encRow, LinearLayout.LayoutParams(MATCH, WRAP))
        if (isRich) addView(richRow, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(contentRoot, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    val title = if (highlightLine > 0) activity.getString(R.string.file_line_title, file.name, highlightLine) else file.name
    val builder = AlertDialog.Builder(activity)
        .setTitle(title)
        .setView(root)
        .setPositiveButton(activity.getString(R.string.action_close), null)
    // 编辑 only makes sense for real files — an archive-entry preview shows a
    // cache temp copy that a save wouldn't repack (the 编辑 flow handles that).
    if (showEdit) builder.setNeutralButton(activity.getString(R.string.action_edit)) { _, _ -> showTextEditor(activity, file, onSaved = onEdited) }
    val dlg = builder.create()
    (activity as? MainActivity)?.resetPagerInputOnDialogDismiss(dlg)
    val metrics = activity.resources.displayMetrics
    val (tw, th) = activity.cappedDialogSize(0.92f, 0.85f)
    dlg.window?.setLayout(tw, th)
    dlg.show()
}

/** In-app text editor: edit the whole file with an explicit encoding (reusing
 *  the global text encoding + the 编辑/localization workflow). Preserves the
 *  original BOM state so a round-trip is byte-faithful when encoding unchanged. */
fun showTextEditor(activity: AppCompatActivity, file: File, onSaved: (() -> Unit)? = null) {
    // Bounded read (2 MiB) — reading a huge file whole on the main thread is
    // an ANR; scripts are tiny, so anything larger is rejected up front.
    if (file.length() > 2L shl 20) {
        Toast.makeText(activity, activity.getString(R.string.msg_editor_too_large), Toast.LENGTH_LONG).show()
        return
    }
    
    // 临时文件路径 - 使用文件路径哈希避免冲突
    val tempDir = File(activity.cacheDir, "edit")
    tempDir.mkdirs()
    val tempFile = File(tempDir, file.absolutePath.hashCode().toString(16) + "_" + file.name)
    
    // 检查是否有未保存的临时文件（异常退出场景）
    val shouldAskRestore = tempFile.exists() && 
                           tempFile.lastModified() > file.lastModified() &&
                           tempFile.length() > 0
    
    if (shouldAskRestore) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.editor_unsaved_changes))
            .setMessage(activity.getString(R.string.editor_restore_prompt))
            .setPositiveButton(activity.getString(R.string.editor_restore)) { _, _ ->
                val data = runCatching { file.readBytes() }.getOrNull()
                val content = tempFile.readText()
                val encoding = if (data != null) detectBestEncoding(data) ?: "UTF-8" else "UTF-8"
                val bom = if (data != null) hasBom(data) else false
                openEditorWithContent(activity, content, file, tempFile, onSaved, data ?: content.toByteArray(), encoding, bom)
            }
            .setNegativeButton(activity.getString(R.string.editor_discard)) { _, _ ->
                tempFile.delete()
                val data = runCatching { file.readBytes() }.getOrNull()
                if (data != null) {
                    val encoding = detectBestEncoding(data)
                        ?: (activity as? MainActivity)?.prefs?.getString("text_encoding", "UTF-8") ?: "UTF-8"
                    val bom = hasBom(data)
                    openEditorWithContent(activity, decodeTextStrict(data, encoding), file, tempFile, onSaved, data, encoding, bom)
                }
            }
            .setNeutralButton(activity.getString(R.string.action_cancel), null)
            .show()
    } else {
        val data = runCatching { file.readBytes() }.getOrNull()
        if (data == null) {
            Toast.makeText(activity, activity.getString(R.string.cannot_read_file, ""), Toast.LENGTH_SHORT).show()
            return
        }
        if (isTjsBytecode(data)) {
            Toast.makeText(activity, activity.getString(R.string.msg_tjs_compiled), Toast.LENGTH_LONG).show()
            return
        }
        val encoding = detectBestEncoding(data)
            ?: (activity as? MainActivity)?.prefs?.getString("text_encoding", "UTF-8") ?: "UTF-8"
        val bom = hasBom(data)
        openEditorWithContent(activity, decodeTextStrict(data, encoding), file, tempFile, onSaved, data, encoding, bom)
    }
}

private fun openEditorWithContent(
    activity: AppCompatActivity,
    content: String,
    originalFile: File,
    tempFile: File,
    onSaved: (() -> Unit)?,
    rawData: ByteArray,
    encoding: String,
    bom: Boolean
) {
    val editHistory = EditHistory(maxSteps = 30)
    editHistory.pushState(content)
    var curEncoding = encoding
    
    val encLabels = arrayOf(
        activity.getString(R.string.encoding_utf8), activity.getString(R.string.encoding_sjis),
        activity.getString(R.string.encoding_gbk), activity.getString(R.string.encoding_utf16))
    
    // 用于抑制程序化setText触发的TextWatcher
    var suppressWatcher = false
    
    val et = EditText(activity).apply {
        setText(content)
        setTextColor(C["primary"]!!)
        setHintTextColor(C["hint"]!!)
        textSize = 14f
        setBackgroundColor(C["surface_dark"]!!)
        setPadding(16, 16, 16, 16)
        gravity = android.view.Gravity.TOP or android.view.Gravity.START
        typeface = android.graphics.Typeface.MONOSPACE
        setHorizontallyScrolling(false)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }
    
    // 文本变化监听 - 自动保存 + 编辑历史
    et.addTextChangedListener(object : android.text.TextWatcher {
        private var previousText = content
        
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            previousText = s?.toString() ?: ""
        }
        
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        
        override fun afterTextChanged(s: android.text.Editable?) {
            if (suppressWatcher) return
            val currentText = s?.toString() ?: ""
            if (currentText != previousText) {
                editHistory.pushState(currentText)
                // 自动保存到临时文件
                try {
                    tempFile.writeText(currentText)
                } catch (_: Exception) {}
            }
        }
    })
    
    val encRow = TextView(activity)
    encRow.apply {
        text = "🔤 " + activity.getString(R.string.settings_text_encoding) + ": " + encLabels[TEXT_ENCODINGS.indexOf(curEncoding).coerceAtLeast(0)] + "  ▾"
        setTextColor(C["accent"]!!)
        textSize = 14f
        setPadding(16, 12, 16, 12)
        setBackgroundColor(C["surface_raised"]!!)
        isClickable = true
        setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.settings_text_encoding))
                .setSingleChoiceItems(encLabels, TEXT_ENCODINGS.indexOf(curEncoding).coerceAtLeast(0)) { d, w ->
                    curEncoding = TEXT_ENCODINGS[w]
                    encRow.text = "🔤 " + activity.getString(R.string.settings_text_encoding) + ": " + encLabels[w] + "  ▾"
                    d.dismiss()
                }
                .setNegativeButton(activity.getString(R.string.action_cancel), null)
                .show()
        }
    }
    
    // 撤销/重做按钮
    val undoRedoRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(16, 8, 16, 8)
        setBackgroundColor(C["surface"]!!)
    }
    val btnUndo = Button(activity).apply {
        text = activity.getString(R.string.editor_undo)
        setTextColor(C["accent"]!!)
        background = null
        textSize = 12f
        isEnabled = false
    }
    val btnRedo = Button(activity).apply {
        text = activity.getString(R.string.editor_redo)
        setTextColor(C["accent"]!!)
        background = null
        textSize = 12f
        isEnabled = false
    }
    undoRedoRow.addView(btnUndo)
    undoRedoRow.addView(btnRedo)
    
    fun updateUndoRedoButtons() {
        btnUndo.isEnabled = editHistory.canUndo()
        btnRedo.isEnabled = editHistory.canRedo()
    }
    
    btnUndo.setOnClickListener {
        suppressWatcher = true
        editHistory.undo()?.let { et.setText(it) }
        suppressWatcher = false
        updateUndoRedoButtons()
    }
    
    btnRedo.setOnClickListener {
        suppressWatcher = true
        editHistory.redo()?.let { et.setText(it) }
        suppressWatcher = false
        updateUndoRedoButtons()
    }
    
    editHistory.setOnChangeListener { updateUndoRedoButtons() }
    
    val body = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(C["surface_dark"]!!)
        addView(encRow)
        addView(undoRedoRow)
        val dragBar = DragScrollBar(activity).apply { attach(et) }
        val frame = FrameLayout(activity).apply {
            addView(et, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(dragBar, FrameLayout.LayoutParams(
                (20 * activity.resources.displayMetrics.density).toInt(), MATCH, Gravity.END))
        }
        addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }
    
    val dlg = AlertDialog.Builder(activity)
        .setTitle(activity.getString(R.string.title_text_editor))
        .setView(body)
        .setPositiveButton(activity.getString(R.string.action_save)) { _, _ ->
            val bytes = encodeText(et.text.toString(), curEncoding, bom)
            try {
                originalFile.writeBytes(bytes)
                tempFile.delete()  // 正常保存，删除临时文件
                Toast.makeText(activity, activity.getString(R.string.msg_saved), Toast.LENGTH_SHORT).show()
                onSaved?.invoke()
            } catch (e: Exception) {
                // 保存失败时 KEEP 临时文件 — 它是自动保存的恢复副本；删掉会丢掉
                // 用户的所有未保存编辑，使其无法恢复。
                Toast.makeText(activity, activity.getString(R.string.err_extract_io, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
        .setNegativeButton(activity.getString(R.string.editor_no_save)) { _, _ ->
            tempFile.delete()  // 不保存，也删除临时文件
        }
        .create()
    // Back键退出时清理孤立的临时文件。编辑器不需要 pager touch-state reset
    // (那是归档预览列表的 Honor workaround) — 挂上反而可能在 Honor 上触发
    // ViewPager relayout 把对话框挤掉，造成"编辑脚本窗口自动退出"。
    dlg.setOnDismissListener {
        if (tempFile.exists()) tempFile.delete()
    }
    val metrics = activity.resources.displayMetrics
    val (tw, th) = activity.cappedDialogSize(0.92f, 0.85f)
    dlg.window?.setLayout(tw, th)
    dlg.show()
}

fun playAudio(activity: AppCompatActivity, file: File) {
    try {
        // 释放之前的MediaPlayer
        (activity as? MainActivity)?.currentMediaPlayer?.release()
        
        val mp = MediaPlayer().apply {
            setAudioAttributes(android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .build())
            setDataSource(file.path)
            prepare()
            start()
        }
        
        // 保存引用到Activity
        (activity as? MainActivity)?.currentMediaPlayer = mp
        
        var released = false
        val safeRelease = {
            if (!released) {
                released = true
                mp.release()
                (activity as? MainActivity)?.currentMediaPlayer = null
            }
        }
        
        // 播放完成时释放MediaPlayer
        mp.setOnCompletionListener { safeRelease() }
        
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.title_audio_player, file.name))
            .setMessage(activity.getString(R.string.msg_audio_playing))
            .setPositiveButton(activity.getString(R.string.action_stop)) { _, _ -> safeRelease() }
            .setOnDismissListener { safeRelease() }
            .show()
    } catch (e: Exception) {
        Toast.makeText(activity, activity.getString(R.string.err_audio_playback, e.message ?: ""), Toast.LENGTH_SHORT).show()
    }
}

fun playVideo(activity: AppCompatActivity, file: File) {
    try {
        // Uri.fromFile throws FileUriExposedException on API 24+ — always go
        // through the FileProvider (same one the APK installer uses).
        val uri = androidx.core.content.FileProvider.getUriForFile(
            activity, "${activity.packageName}.fileprovider", file)
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (e: Exception) {
        Toast.makeText(activity, activity.getString(R.string.err_video_playback, e.message ?: ""), Toast.LENGTH_SHORT).show()
    }
}
