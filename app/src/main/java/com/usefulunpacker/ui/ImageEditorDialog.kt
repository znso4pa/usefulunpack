package com.usefulunpacker

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

/** Editor working-resolution cap (longest side). Prevents OOM on huge CG. */
private const val IMG_EDIT_MAX_PX = 2048

/**
 * Opens the in-app image editor for the given file. The image is decoded
 * capped at ~2048px; edits output at that resolution as a `name-edit.png`
 * copy (never overwriting the original). Autosaves to cache so rotation /
 * dialog recreation can offer to restore unsaved edits.
 */
fun showImageEditor(activity: AppCompatActivity, file: File) {
    val tempDir = File(activity.cacheDir, "imgedit"); tempDir.mkdirs()
    val tempFile = File(tempDir, "${file.name.hashCode().toString(16)}.png")

    fun open() {
        val editorView = ImageEditorView(activity)
        val pd = android.app.ProgressDialog(activity)
        pd.setMessage(activity.getString(R.string.msg_loading)); pd.setCancelable(false); pd.show()
        thread {
            val bmp = decodeBitmapCapped(file, IMG_EDIT_MAX_PX)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                pd.dismiss()
                if (bmp == null) {
                    Toast.makeText(activity, activity.getString(R.string.msg_cannot_decode), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                // A newer autosave wins (rotation happened mid-edit).
                if (tempFile.exists() && tempFile.lastModified() > file.lastModified()) {
                    val restored = decodeBitmapCapped(tempFile, IMG_EDIT_MAX_PX)
                    if (restored != null) editorView.setBitmap(restored) else editorView.setBitmap(bmp)
                } else {
                    editorView.setBitmap(bmp)
                }
                showEditorDialog(activity, file, editorView, tempFile)
            }
        }
    }

    if (tempFile.exists() && tempFile.lastModified() > file.lastModified()) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.img_edit_restore_title))
            .setMessage(activity.getString(R.string.img_edit_restore_msg))
            .setPositiveButton(activity.getString(R.string.editor_restore)) { _, _ -> open() }
            .setNegativeButton(activity.getString(R.string.editor_discard)) { _, _ -> tempFile.delete(); open() }
            .show()
    } else open()
}

private fun showEditorDialog(activity: AppCompatActivity, file: File, editorView: ImageEditorView, tempFile: File) {
    val act = activity

    fun toolBtn(text: String, onClick: () -> Unit) = Button(act).apply {
        this.text = text
        isAllCaps = false
        setTextColor(C["accent"]!!)
        background = null
        textSize = 13f
        setPadding(8, 6, 8, 6)
        setOnClickListener { onClick() }
    }

    // ── Brush options row (color swatch + palette + opacity + width) ──
    val colorView = ImageView(act).apply {
        layoutParams = LinearLayout.LayoutParams(36, 36).apply { setMargins(8, 0, 8, 0) }
        setBackgroundColor(editorView.brushColor)
    }
    val palette = intArrayOf(
        0xFFFFE066.toInt(), 0xFF66E0FF.toInt(), 0xFFFF6E66.toInt(),
        0xFFB0FF66.toInt(), 0xFFFF66D0.toInt(), 0xFFFFFFFF.toInt())
    val opacityText = TextView(act).apply {
        text = "${act.getString(R.string.img_edit_opacity)}: ${editorView.brushAlpha * 100 / 255}%"
        setTextColor(C["secondary"]!!); textSize = 12f
    }
    val opacitySeek = SeekBar(act).apply {
        max = 255; progress = editorView.brushAlpha
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                editorView.brushAlpha = p
                opacityText.text = "${act.getString(R.string.img_edit_opacity)}: ${p * 100 / 255}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }
    val widthText = TextView(act).apply {
        text = "${act.getString(R.string.img_edit_width)}: ${editorView.brushWidthPx}px"
        setTextColor(C["secondary"]!!); textSize = 12f
    }
    val widthSeek = SeekBar(act).apply {
        max = 60; progress = editorView.brushWidthPx
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                editorView.brushWidthPx = p.coerceAtLeast(2)
                widthText.text = "${act.getString(R.string.img_edit_width)}: ${p}px"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }
    val brushRow = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(8, 4, 8, 4)
        addView(TextView(act).apply {
            text = act.getString(R.string.img_edit_brush); setTextColor(C["primary"]!!); textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        addView(opacityText); addView(opacitySeek, LinearLayout.LayoutParams(0, WRAP, 2f))
        addView(widthText); addView(widthSeek, LinearLayout.LayoutParams(0, WRAP, 2f))
        addView(colorView)
    }

    // ── Eyedrop result row ──
    val eyedropRow = TextView(act).apply {
        text = act.getString(R.string.img_edit_pick_hint)
        setTextColor(C["secondary"]!!); textSize = 13f
        setPadding(12, 6, 12, 6)
        gravity = Gravity.CENTER
    }
    editorView.onColorSampled = { color ->
        val hex = String.format("#%08X", color)
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        eyedropRow.text = "$hex  RGB($r,$g,$b)  —  ${act.getString(R.string.img_edit_tap_copy)}"
        eyedropRow.setOnClickListener {
            copyToClipboard(act, hex)
            Toast.makeText(act, act.getString(R.string.color_copied, hex), Toast.LENGTH_SHORT).show()
        }
    }

    // ── Main editor area ──
    editorView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)

    // ── Tool row ──
    val btnBrush = toolBtn(act.getString(R.string.img_edit_brush)) {
        editorView.tool = EditorTool.BRUSH
        brushRow.visibility = android.view.View.VISIBLE
    }
    val btnCrop = toolBtn(act.getString(R.string.img_edit_crop)) {
        if (editorView.tool == EditorTool.CROP) {
            // Second tap = confirm crop (the missing call point).
            if (editorView.performCrop()) {
                editorView.tool = EditorTool.BRUSH
                brushRow.visibility = android.view.View.VISIBLE
            } else {
                Toast.makeText(act, act.getString(R.string.img_edit_crop_hint), Toast.LENGTH_SHORT).show()
            }
        } else {
            editorView.resetView() // keep the rect mapping 1:1 at fit zoom
            editorView.tool = EditorTool.CROP
            brushRow.visibility = android.view.View.GONE
            Toast.makeText(act, act.getString(R.string.img_edit_crop_hint), Toast.LENGTH_SHORT).show()
        }
    }
    val btnEyedrop = toolBtn(act.getString(R.string.img_edit_eyedrop)) {
        editorView.tool = EditorTool.EYEDROP
        brushRow.visibility = android.view.View.GONE
    }
    val btnUndo = toolBtn(act.getString(R.string.img_edit_undo)) { editorView.undo() }
    val btnReset = toolBtn(act.getString(R.string.img_edit_reset)) { editorView.clearEdits() }
    lateinit var dlg: AlertDialog
    val btnSave = toolBtn(act.getString(R.string.img_edit_save)) {
        saveEdited(act, file, editorView, tempFile) { dlg.dismiss() }
    }

    // ── Stretch fold-out row (collapsible) ──
    val stretchRow = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(10, 2, 10, 2)
        visibility = android.view.View.GONE
    }
    fun addRatio(label: String, w: Int, h: Int) {
        stretchRow.addView(toolBtn(label) {
            editorView.tool = EditorTool.BRUSH
            editorView.stretchTo(w, h)
            stretchRow.visibility = android.view.View.GONE
        })
    }
    addRatio("1:1", 1, 1)
    addRatio("4:3", 4, 3)
    addRatio("3:4", 3, 4)
    addRatio("16:9", 16, 9)
    addRatio("9:16", 9, 16)
    val btnStretch = toolBtn(act.getString(R.string.img_edit_stretch)) {
        stretchRow.visibility = if (stretchRow.visibility == android.view.View.GONE)
            android.view.View.VISIBLE else android.view.View.GONE
    }

    val toolRow = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(6, 4, 6, 6)
        addView(btnBrush); addView(btnCrop); addView(btnStretch)
        addView(btnEyedrop); addView(btnUndo); addView(btnReset)
        addView(android.view.View(act).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        addView(btnSave)
    }

    // ── Palette tap-to-pick ──
    palette.forEach { c ->
        val sw = ImageView(act).apply {
            layoutParams = LinearLayout.LayoutParams(30, 30).apply { setMargins(4, 0, 4, 0) }
            setBackgroundColor(c)
            isClickable = true
            setOnClickListener {
                editorView.brushColor = c
                colorView.setBackgroundColor(c)
            }
        }
        brushRow.addView(sw)
    }

    val container = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(C["surface"]!!)
        addView(brushRow)
        addView(eyedropRow)
        addView(editorView)
        addView(stretchRow)
        addView(toolRow)
    }

    dlg = AlertDialog.Builder(act)
        .setTitle("${act.getString(R.string.img_edit_title)} — ${file.name}")
        .setView(container)
        .setNegativeButton(act.getString(R.string.action_close), null)
        .create()
    val (w, h) = act.cappedDialogSize(0.95f, 0.9f)
    dlg.window?.setLayout(w, h)

    // Autosave edits to cache for rotation/restore (throttled by the view).
    editorView.onAutosave = {
        thread {
            val bmp = editorView.composeResult()
            if (bmp != null) {
                runCatching { FileOutputStream(tempFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            }
        }
    }

    dlg.show()
}

private fun saveEdited(act: AppCompatActivity, file: File, view: ImageEditorView, tempFile: File, onDone: () -> Unit) {
    showFolderPicker(act, file.parentFile ?: act.cacheDir, false) { dir ->
        val outF = uniqueFile(dir, "${file.nameWithoutExtension}-edit.png")
        thread {
            val bmp = view.composeResult()
            val ok = bmp != null && runCatching {
                FileOutputStream(outF).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                true
            }.getOrDefault(false)
            act.runOnUiThread {
                if (ok) {
                    // Only drop the recovery copy once we actually saved — a
                    // failed save keeps it so rotation can still offer restore.
                    tempFile.delete()
                    Toast.makeText(act, act.getString(R.string.img_edit_saved, outF.name), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(act, act.getString(R.string.img_edit_failed), Toast.LENGTH_SHORT).show()
                }
                onDone()
            }
        }
    }
}