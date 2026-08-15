package com.usefulunpacker

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.concurrent.thread

fun previewLocalFile(activity: AppCompatActivity, f: File) {
    val ext = f.name.lowercase().substringAfterLast('.')
    when (ext) {
        "jpg", "jpeg", "png", "gif", "webp" -> showImagePreview(activity, f)
        "mp3", "ogg" -> playAudio(activity, f)
        "mp4" -> playVideo(activity, f)
        else -> showTextPreview(activity, f)
    }
}

fun showImagePreview(activity: AppCompatActivity, file: File) {
    // Decode OFF the UI thread with a target sample size — a huge PNG/JPG
    // decoded at full size on the main thread ANRs and can OOM.
    val metrics = activity.resources.displayMetrics
    val screenW = metrics.widthPixels
    val screenH = (metrics.heightPixels * 0.8).toInt()
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
            AlertDialog.Builder(activity)
                .setTitle(file.name)
                .setView(scroll)
                .setPositiveButton(activity.getString(R.string.action_close), null)
                .show()
        }
    }
}

fun showTextPreview(activity: AppCompatActivity, file: File, highlightLine: Int = 0, highlightQuery: String = "", showEdit: Boolean = true) {
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

    // Assigned below (after buildContent is defined); the encoding row calls it
    // to re-render the content with the new encoding without leaving the dialog.
    lateinit var refreshContent: () -> Unit

    // Encoding switch row: re-decode instantly so a wrong-pick preview fixes
    // itself (and persists the choice as the new default) without leaving the
    // dialog — the classic "GBK file read as UTF-8" flow.
    val encRow = TextView(activity)
    encRow.apply {
        text = activity.getString(R.string.settings_text_encoding) + ": " + encLabels[TEXT_ENCODINGS.indexOf(curEncoding).coerceAtLeast(0)]
        setTextColor(C["accent"]!!)
        textSize = 13f
        setPadding(16, 10, 16, 10)
        setBackgroundColor(C["surface"]!!)
        isClickable = true
        setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.settings_text_encoding))
                .setSingleChoiceItems(encLabels, TEXT_ENCODINGS.indexOf(curEncoding).coerceAtLeast(0)) { d, w ->
                    curEncoding = TEXT_ENCODINGS[w]
                    encRow.text = activity.getString(R.string.settings_text_encoding) + ": " + encLabels[w]
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

        val tv = TextView(activity).apply {
            this.text = spannable ?: displayText
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

    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(encRow, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(contentRoot, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    val title = if (highlightLine > 0) activity.getString(R.string.file_line_title, file.name, highlightLine) else file.name
    val builder = AlertDialog.Builder(activity)
        .setTitle(title)
        .setView(root)
        .setPositiveButton(activity.getString(R.string.action_close), null)
    // 编辑 only makes sense for real files — an archive-entry preview shows a
    // cache temp copy that a save wouldn't repack (the 编辑 flow handles that).
    if (showEdit) builder.setNeutralButton(activity.getString(R.string.action_edit)) { _, _ -> showTextEditor(activity, file) }
    val dlg = builder.create()
    val metrics = activity.resources.displayMetrics
    dlg.window?.setLayout((metrics.widthPixels * 0.92).toInt(), (metrics.heightPixels * 0.85).toInt())
    dlg.show()
}

/** In-app text editor: edit the whole file with an explicit encoding (reusing
 *  the global text encoding + the 编辑/localization workflow). Preserves the
 *  original BOM state so a round-trip is byte-faithful when encoding unchanged. */
fun showTextEditor(activity: AppCompatActivity, file: File) {
    // Bounded read (2 MiB) — reading a huge file whole on the main thread is
    // an ANR; scripts are tiny, so anything larger is rejected up front.
    if (file.length() > 2L shl 20) {
        Toast.makeText(activity, activity.getString(R.string.msg_editor_too_large), Toast.LENGTH_LONG).show()
        return
    }
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
    var initialText = decodeTextStrict(data, encoding)
    val encLabels = arrayOf(
        activity.getString(R.string.encoding_utf8), activity.getString(R.string.encoding_sjis),
        activity.getString(R.string.encoding_gbk), activity.getString(R.string.encoding_utf16))
    var curEncoding = encoding

    val et = EditText(activity).apply {
        setText(initialText)
        setTextColor(C["primary"]!!)
        setHintTextColor(C["hint"]!!)
        textSize = 14f
        setBackgroundColor(C["surface_dark"]!!)
        setPadding(16, 16, 16, 16)
        gravity = android.view.Gravity.TOP or android.view.Gravity.START
        typeface = android.graphics.Typeface.MONOSPACE
        setHorizontallyScrolling(false)
        // Honor/EMUI NPEs drawing scrollbars on custom views — disable both
        // (the EditText still scrolls natively on touch).
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }
    val encRow = TextView(activity)
    encRow.apply {
        text = activity.getString(R.string.settings_text_encoding) + ": " + encLabels[TEXT_ENCODINGS.indexOf(curEncoding).coerceAtLeast(0)]
        setTextColor(C["accent"]!!)
        textSize = 14f
        setPadding(16, 12, 16, 12)
        setBackgroundColor(C["surface"]!!)
        isClickable = true
        setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.settings_text_encoding))
                .setSingleChoiceItems(encLabels, TEXT_ENCODINGS.indexOf(curEncoding).coerceAtLeast(0)) { d, w ->
                    curEncoding = TEXT_ENCODINGS[w]
                    encRow.text = activity.getString(R.string.settings_text_encoding) + ": " + encLabels[w]
                    // Re-decode the ORIGINAL bytes with the new encoding so the
                    // displayed text updates instantly (previously it only
                    // changed on the next open).
                    initialText = decodeTextStrict(data, curEncoding)
                    et.setText(initialText)
                    d.dismiss()
                }
                .setNegativeButton(activity.getString(R.string.action_cancel), null)
                .show()
        }
    }

    val body = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(C["surface_dark"]!!)
        addView(encRow)
        // FrameLayout wraps the EditText with a draggable scrollbar overlay
        // (Honor-safe; the EditText still scrolls natively on touch).
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
                file.writeBytes(bytes)
                Toast.makeText(activity, activity.getString(R.string.msg_saved), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(activity, activity.getString(R.string.err_extract_io, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
        .setNegativeButton(activity.getString(R.string.action_cancel), null)
        .create()
    val metrics = activity.resources.displayMetrics
    dlg.window?.setLayout((metrics.widthPixels * 0.92).toInt(), (metrics.heightPixels * 0.85).toInt())
    dlg.show()
}

fun playAudio(activity: AppCompatActivity, file: File) {
    try {
        val mp = MediaPlayer().apply {
            setDataSource(file.path)
            prepare()
            start()
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.title_audio_player, file.name))
            .setMessage(activity.getString(R.string.msg_audio_playing))
            .setPositiveButton(activity.getString(R.string.action_stop)) { _, _ -> mp.release() }
            .setOnDismissListener { mp.release() }
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
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (e: Exception) {
        Toast.makeText(activity, activity.getString(R.string.err_video_playback, e.message ?: ""), Toast.LENGTH_SHORT).show()
    }
}
