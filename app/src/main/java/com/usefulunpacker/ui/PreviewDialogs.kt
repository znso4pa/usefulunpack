package com.usefulunpacker

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File

fun previewLocalFile(activity: AppCompatActivity, f: File) {
    val ext = f.name.lowercase().substringAfterLast('.')
    when (ext) {
        "jpg", "jpeg", "png" -> showImagePreview(activity, f)
        "mp3", "ogg" -> playAudio(activity, f)
        "mp4" -> playVideo(activity, f)
        else -> showTextPreview(activity, f)
    }
}

fun showImagePreview(activity: AppCompatActivity, file: File) {
    val bmp = BitmapFactory.decodeFile(file.path)
    if (bmp == null) { Toast.makeText(activity, activity.getString(R.string.msg_cannot_decode), Toast.LENGTH_SHORT).show(); return }

    val iv = ImageView(activity).apply {
        setImageBitmap(bmp)
        setBackgroundColor(0xFF000000.toInt())
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        maxWidth = activity.resources.displayMetrics.widthPixels
        maxHeight = (activity.resources.displayMetrics.heightPixels * 0.8).toInt()
    }

    val scroll = ScrollView(activity).apply {
        addView(iv)
        setBackgroundColor(0xFF000000.toInt())
    }

    AlertDialog.Builder(activity)
        .setTitle(file.name)
        .setView(scroll)
        .setPositiveButton(activity.getString(R.string.action_close), null)
        .show()
}

fun showTextPreview(activity: AppCompatActivity, file: File, highlightLine: Int = 0, highlightQuery: String = "") {
    val raw = runCatching { file.readText() }.getOrElse { "无法读取文件: ${it.message}" }
    val displayText = raw.take(50000)
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
            matchPos.add(pos)
            idx = pos + 1
        }
    }

    fun applyHighlights(selectedIdx: Int) {
        val s = spannable ?: return
        val len = highlightQuery.length
        for (span in s.getSpans(0, s.length, android.text.style.BackgroundColorSpan::class.java)) {
            s.removeSpan(span)
        }
        for (i in matchPos.indices) {
            val color = if (i == selectedIdx) C["search_hilite_sel"]!! else C["search_hilite_oth"]!!
            s.setSpan(
                android.text.style.BackgroundColorSpan(color),
                matchPos[i], matchPos[i] + len,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    if (spannable != null && matchPos.isNotEmpty()) applyHighlights(0)
    else spannable = null

    val tv = TextView(activity).apply {
        this.text = spannable ?: displayText
        setTextColor(C["primary"]!!)
        textSize = 12f
        setBackgroundColor(C["surface_dark"]!!)
        setPadding(16, 16, 16, 16)
        isVerticalScrollBarEnabled = true
        movementMethod = android.text.method.ScrollingMovementMethod()
        typeface = android.graphics.Typeface.MONOSPACE
    }
    val scroll = ScrollView(activity).apply {
        addView(tv)
        setBackgroundColor(C["surface_dark"]!!)
    }
    if (highlightLine > 0) {
        tv.post {
            val layout = tv.layout ?: return@post
            val lineIdx = (highlightLine - 1).coerceIn(0, layout.lineCount - 1)
            val y = layout.getLineTop(lineIdx) - (scroll.height / 3)
            scroll.scrollTo(0, y.coerceAtLeast(0))
        }
    }

    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

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
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(C["nav_bg"]!!)
            setPadding(0, 6, 0, 6)
        }
        val btnPrev = Button(activity).apply {
            text = activity.getString(R.string.search_prev); textSize = 12f; isAllCaps = false
            setTextColor(C["accent"]!!); background = null
            setPadding(12, 4, 12, 4)
        }
        val tvCounter = TextView(activity).apply {
            gravity = Gravity.CENTER; textSize = 12f
            setTextColor(C["tertiary_light"]!!)
            setPadding(20, 4, 20, 4)
        }
        val btnNext = Button(activity).apply {
            text = activity.getString(R.string.search_next); textSize = 12f; isAllCaps = false
            setTextColor(C["accent"]!!); background = null
            setPadding(12, 4, 12, 4)
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
        root.addView(navBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        scrollToMatch(curMatch)
    }

    val title = if (highlightLine > 0) "${file.name} (行 $highlightLine)" else file.name
    AlertDialog.Builder(activity)
        .setTitle(title)
        .setView(root)
        .setPositiveButton(activity.getString(R.string.action_close), null)
        .show()
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
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.fromFile(file), "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (e: Exception) {
        Toast.makeText(activity, activity.getString(R.string.err_video_playback, e.message ?: ""), Toast.LENGTH_SHORT).show()
    }
}
