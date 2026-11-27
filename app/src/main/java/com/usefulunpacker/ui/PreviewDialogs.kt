package com.usefulunpacker

import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import com.usefulunpacker.featureThread as thread
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

fun showTextPreview(activity: AppCompatActivity, file: File, highlightLine: Int = 0, highlightQuery: String = "", showEdit: Boolean = true, onEdited: (() -> Unit)? = null) {
    val _intentScope = com.usefulunpacker.AppDiagnostics.enterIntent("text_preview")
    try {
    // Prefix read + encoding sniffing hit slow storage (1 MiB cap + double
    // decode) — do them off the main thread behind a loading dialog, then
    // build the preview UI on main. A finished activity just drops the result.
    val pd = com.usefulunpacker.UuProgressDialog(activity).apply {
        setMessage(activity.getString(R.string.msg_loading)); setCancelable(false); show()
    }
    pd.keepTabsTappable()
    thread {
        val prefs = (activity as? MainActivity)?.prefs
        val data = com.usefulunpacker.observedAttempt("PreviewDialogs") { readPrefix(file, 1 shl 20) }.getOrNull()
        activity.runFeatureOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runFeatureOnUiThread
            pd.dismiss()
            if (data == null) {
                com.usefulunpacker.UuNotice.failure(activity, activity.getString(R.string.cannot_read_file, ""), Toast.LENGTH_SHORT).show()
                return@runFeatureOnUiThread
            }
            // Compiled TJS2 scripts are binary bytecode, not text — a clear message
            // beats a garbled preview.
            if (isTjsBytecode(data)) {
                com.usefulunpacker.UuNotice.makeText(activity, activity.getString(R.string.msg_tjs_compiled), Toast.LENGTH_LONG).show()
                return@runFeatureOnUiThread
            }
            showTextPreviewLoaded(activity, file, data, highlightLine, highlightQuery, showEdit, onEdited)
        }
    }
    } catch (intentCancelled: java.util.concurrent.CancellationException) {
        _intentScope.trace.cancelled(); throw intentCancelled
    } catch (intentFailure: Exception) {
        _intentScope.trace.fail(com.usefulunpacker.FailureCode.UNEXPECTED, intentFailure.message ?: intentFailure.javaClass.simpleName, intentFailure)
        throw intentFailure
    } finally { com.usefulunpacker.AppDiagnostics.leaveIntent(_intentScope) }
}

/** Main-thread UI half of [showTextPreview], invoked once the bytes are in. */
private fun showTextPreviewLoaded(activity: AppCompatActivity, file: File, data: ByteArray, highlightLine: Int, highlightQuery: String, showEdit: Boolean, onEdited: (() -> Unit)?) {
    // Strict decode with the user-chosen global text encoding (BOM-aware
    // UTF-8/UTF-16, REPLACE for invalid bytes — see decodeTextStrict).
    val prefs = (activity as? MainActivity)?.prefs
    // Auto-detect the encoding: BOM (UTF-16/UTF-8) wins, then strict UTF-8,
    // then a Shift-JIS vs GBK heuristic; fall back to the global pref when
    // nothing is confident.
    var curEncoding = detectBestEncoding(data)
        ?: prefs?.getString("text_encoding", "UTF-8") ?: "UTF-8"


    fun decode(): String = com.usefulunpacker.observedAttempt("PreviewDialogs") { decodeTextStrict(data, curEncoding) }
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
    val encRow = textEncodingRow(activity, curEncoding) { chosen ->
        curEncoding = chosen
        prefs?.edit()?.putString("text_encoding", chosen)?.apply()
        refreshContent()
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
        val scroll = com.usefulunpacker.UuScrollView(activity).apply {
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
            val navBar = com.usefulunpacker.UuActionBar(activity).apply {
                setBackgroundColor(C["nav_bg"]!!); setPadding(0, 6, 0, 6)
            }
            val btnPrev = com.usefulunpacker.UuButton(activity).apply {
                text = activity.getString(R.string.search_prev)
            }
            val tvCounter = TextView(activity).apply {
                gravity = Gravity.CENTER; textSize = 12f; setTextColor(C["tertiary_light"]!!); setPadding(20, 4, 20, 4)
            }
            val btnNext = com.usefulunpacker.UuButton(activity).apply {
                text = activity.getString(R.string.search_next)
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
        com.usefulunpacker.UuNotice.makeText(activity, activity.getString(R.string.preview_encoding_hint, curEncoding), Toast.LENGTH_LONG).show()
    } else if (curEncoding != "UTF-8" && looksLikeUtf8(data)) {
        com.usefulunpacker.UuNotice.makeText(activity, activity.getString(R.string.preview_looks_utf8_hint), Toast.LENGTH_LONG).show()
    }

    contentRoot = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    contentRoot.addView(buildContent(), LinearLayout.LayoutParams(MATCH, 0, 1f))
    refreshContent = {
        contentRoot.removeAllViews()
        contentRoot.addView(buildContent(), LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    // Render toggle row (md/markdown/rtf only): tap to flip between styled and
    // plain text without leaving the dialog.
    val richRow = UuActionRow(activity)
    if (isRich) {
        richRow.apply {
            text = activity.getString(R.string.preview_rich) + ": " +
                if (richEnabled) activity.getString(R.string.preview_rich_on) else activity.getString(R.string.preview_rich_off)
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
        addView(UuActionRow(activity).apply {
            bind(activity.getString(R.string.localization_title), navigation = true) { showLocalizationCheck(activity, file) }
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        (activity as? MainActivity)?.let { host ->
            addView(UuActionRow(activity).apply {
                bind(activity.getString(R.string.translation_title), navigation = true) { host.showTranslationAgent(file, curEncoding) }
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        if (isRich) addView(richRow, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(contentRoot, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    val title = if (highlightLine > 0) activity.getString(R.string.file_line_title, file.name, highlightLine) else file.name
    val builder = com.usefulunpacker.UuDialogBuilder(activity)
        .setTitle(title)
        .setView(root)
        .setPositiveButton(activity.getString(R.string.action_close), null)
    // 编辑 only makes sense for real files — an archive-entry preview shows a
    // cache temp copy that a save wouldn't repack (the 编辑 flow handles that).
    if (showEdit) builder.setNeutralButton(activity.getString(R.string.action_edit)) { _, _ -> showTextEditor(activity, file, onSaved = onEdited) }
    val dlg = builder.create()
    // Tall (capped 0.85h) — bottom-anchor below the tab strip instead of just
    // freeing touches, or the window would still cover the strip on short screens.
    dlg.belowTabs()
    dlg.show()
}
