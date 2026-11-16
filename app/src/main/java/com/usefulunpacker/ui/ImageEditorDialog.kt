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
import com.usefulunpacker.featureThread as thread

/** Editor working-resolution cap (longest side). Prevents OOM on huge CG. */
private const val IMG_EDIT_MAX_PX = 2048

/**
 * Opens the in-app image editor for the given file. The image is decoded
 * capped at ~2048px; edits output at that resolution as a `name-edit.png`
 * copy (never overwriting the original). Autosaves to cache so rotation /
 * dialog recreation can offer to restore unsaved edits.
 */
fun showImageEditor(activity: AppCompatActivity, file: File) {
    val _intentScope = com.usefulunpacker.AppDiagnostics.enterIntent("image_edit", newChain = true)
    try {
    val tempDir = File(activity.cacheDir, "imgedit"); tempDir.mkdirs()
    val tempFile = File(tempDir, "${file.name.hashCode().toString(16)}.png")

    fun open() {
        val editorView = ImageEditorView(activity)
        val pd = com.usefulunpacker.UuProgressDialog(activity)
        pd.setMessage(activity.getString(R.string.msg_loading)); pd.setCancelable(false); pd.show()
        pd.keepTabsTappable(blockBehind = false)
        thread {
            val bmp = decodeBitmapCapped(file, IMG_EDIT_MAX_PX)
            activity.runFeatureOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runFeatureOnUiThread
                pd.dismiss()
                if (bmp == null) {
                    com.usefulunpacker.UuNotice.failure(activity, activity.getString(R.string.msg_cannot_decode), Toast.LENGTH_SHORT).show()
                    return@runFeatureOnUiThread
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
        com.usefulunpacker.UuDialogBuilder(activity)
            .setTitle(activity.getString(R.string.img_edit_restore_title))
            .setMessage(activity.getString(R.string.img_edit_restore_msg))
            .setPositiveButton(activity.getString(R.string.editor_restore)) { _, _ -> open() }
            .setNegativeButton(activity.getString(R.string.editor_discard)) { _, _ -> com.usefulunpacker.deleteOutputChecked(tempFile); open() }
            .show().also { it.keepTabsTappable() }
    } else open()
    } catch (intentCancelled: java.util.concurrent.CancellationException) {
        _intentScope.trace.cancelled(); throw intentCancelled
    } catch (intentFailure: Exception) {
        _intentScope.trace.fail(com.usefulunpacker.FailureCode.UNEXPECTED, intentFailure.message ?: intentFailure.javaClass.simpleName, intentFailure)
        throw intentFailure
    } finally { com.usefulunpacker.AppDiagnostics.leaveIntent(_intentScope) }
}

private fun showEditorDialog(activity: AppCompatActivity, file: File, editorView: ImageEditorView, tempFile: File) {
    val act = activity
    val ownerTrace = AppDiagnostics.currentTrace()
    val autosaveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun toolBtn(text: String, style: ActionStyle = ActionStyle.TEXT, onClick: () -> Unit) = com.usefulunpacker.UuButton(act).apply {
        this.text = text
        actionStyle = style
        setOnClickListener { onClick() }
    }

    // ── Brush options row (color swatch + palette + opacity + width) ──
    val colorView = UuColorButton(act).apply {
        color = editorView.brushColor
        isEnabled = false
    }
    val palette = intArrayOf(
        0xFFFFE066.toInt(), 0xFF66E0FF.toInt(), 0xFFFF6E66.toInt(),
        0xFFB0FF66.toInt(), 0xFFFF66D0.toInt(), 0xFFFFFFFF.toInt())
    val opacityText = TextView(act).apply {
        text = "${act.getString(R.string.img_edit_opacity)}: ${editorView.brushAlpha * 100 / 255}%"
        setTextColor(C["secondary"]!!); textSize = 12f
    }
    val opacitySeek = com.usefulunpacker.UuSlider(act).apply {
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
    val widthSeek = com.usefulunpacker.UuSlider(act).apply {
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
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        })
        addView(opacityText); addView(opacitySeek, LinearLayout.LayoutParams(act.dp(120), WRAP))
        addView(widthText); addView(widthSeek, LinearLayout.LayoutParams(act.dp(120), WRAP))
        addView(colorView)
    }

    // ── Eyedrop result row ──
    val eyedropRow = UuActionRow(act).apply {
        text = act.getString(R.string.img_edit_pick_hint)
    }
    editorView.onColorSampled = { color ->
        val hex = String.format("#%08X", color)
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        eyedropRow.text = "$hex  RGB($r,$g,$b)  —  ${act.getString(R.string.img_edit_tap_copy)}"
        eyedropRow.setOnClickListener {
            copyToClipboard(act, hex)
            com.usefulunpacker.UuNotice.makeText(act, act.getString(R.string.color_copied, hex), Toast.LENGTH_SHORT).show()
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
                com.usefulunpacker.UuNotice.makeText(act, act.getString(R.string.img_edit_crop_hint), Toast.LENGTH_SHORT).show()
            }
        } else {
            editorView.resetView() // keep the rect mapping 1:1 at fit zoom
            editorView.tool = EditorTool.CROP
            brushRow.visibility = android.view.View.GONE
            com.usefulunpacker.UuNotice.makeText(act, act.getString(R.string.img_edit_crop_hint), Toast.LENGTH_SHORT).show()
        }
    }
    val btnEyedrop = toolBtn(act.getString(R.string.img_edit_eyedrop)) {
        editorView.tool = EditorTool.EYEDROP
        brushRow.visibility = android.view.View.GONE
    }
    val btnUndo = toolBtn(act.getString(R.string.img_edit_undo)) { editorView.undo() }
    val btnReset = toolBtn(act.getString(R.string.img_edit_reset)) { editorView.clearEdits() }
    lateinit var dlg: AlertDialog
    val btnSave = toolBtn(act.getString(R.string.img_edit_save), ActionStyle.PRIMARY) {
        saveEdited(act, file, editorView, tempFile, autosaveExecutor) { dlg.dismiss() }
    }

    // ── Stretch fold-out row (collapsible) ──
    val stretchRow = com.usefulunpacker.UuActionBar(act).apply {
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

    val toolRow = com.usefulunpacker.UuActionBar(act).apply {
        addView(btnBrush); addView(btnCrop); addView(btnStretch)
        addView(btnEyedrop); addView(btnUndo); addView(btnReset)
        addView(android.view.View(act).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        addView(btnSave)
    }

    // ── Palette tap-to-pick ──
    palette.forEach { c ->
        val sw = UuColorButton(act).apply {
            color = c
            setOnClickListener {
                editorView.brushColor = c
                colorView.color = c
            }
        }
        brushRow.addView(sw)
    }

    val container = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(C["surface"]!!)
        addView(UuHorizontalScrollView(act).apply { addView(brushRow) })
        addView(eyedropRow)
        addView(editorView)
        addView(UuHorizontalScrollView(act).apply { addView(stretchRow) })
        addView(UuHorizontalScrollView(act).apply { addView(toolRow) })
    }

    dlg = com.usefulunpacker.UuDialogBuilder(act)
        .setTitle("${act.getString(R.string.img_edit_title)} — ${file.name}")
        .setView(container)
        .setNegativeButton(act.getString(R.string.action_close), null)
        .create()

    // Autosave edits to cache for rotation/restore (throttled by the view).
    // 单线程执行器串行化：400ms 节流会在慢 PNG 编码期间放行第二次触发，
    // 两个线程并发写同一 tempFile 会互相截断产出损坏的恢复副本。用后回收。
    editorView.onAutosave = {
        // Snapshot on the UI thread; the pool only encodes immutable snapshots.
        val bmp = editorView.composeResult()
        if (bmp != null) {
            try {
                executeFeature(autosaveExecutor, ownerTrace) {
                    try {
                        observedAttempt("image_autosave") {
                            writeOutputAtomically(tempFile) { staged ->
                                FileOutputStream(staged).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            }
                        }
                    } finally { bmp.recycle() }
                }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                bmp.recycle()
                AppDiagnostics.failure("image_autosave_schedule", e)
            }
        }
    }
    editorView.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: android.view.View) {}
        override fun onViewDetachedFromWindow(v: android.view.View) {
            editorView.onAutosave = null
            autosaveExecutor.shutdown()
        }
    })

    // Full-size editor (capped 0.9h) — bottom-anchor below the tab strip.
    dlg.belowTabs()
    dlg.show()
}

private fun saveEdited(act: AppCompatActivity, file: File, view: ImageEditorView, tempFile: File,
                       executor: java.util.concurrent.Executor, onDone: () -> Unit) {
    showFolderPicker(act, file.parentFile ?: act.cacheDir, false) { dir ->
        val outF = uniqueFile(dir, "${file.nameWithoutExtension}-edit.png")
        val bmp = view.composeResult()
        val resumeAutosave = view.onAutosave
        view.onAutosave = null
        // Serialize behind pending autosaves, so none can recreate recovery after a successful save.
        executeFeature(executor) {
            val ok = try {
                bmp != null && observedAttempt("image_save") {
                    writeOutputAtomically(outF) { staged ->
                        FileOutputStream(staged).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }.isSuccess
            } finally { bmp?.recycle() }
            act.runFeatureOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runFeatureOnUiThread
                if (ok) {
                    // Only drop the recovery copy once we actually saved — a
                    // failed save keeps it so rotation can still offer restore.
                    com.usefulunpacker.deleteOutputChecked(tempFile)
                    com.usefulunpacker.UuNotice.makeText(act, act.getString(R.string.img_edit_saved, outF.name), Toast.LENGTH_LONG).show()
                } else {
                    view.onAutosave = resumeAutosave
                    com.usefulunpacker.UuNotice.failure(act, act.getString(R.string.img_edit_failed), Toast.LENGTH_SHORT).show()
                }
                if (ok) onDone()
            }
        }
    }
}