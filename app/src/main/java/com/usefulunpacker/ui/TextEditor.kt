package com.usefulunpacker

import android.app.AlertDialog
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import com.usefulunpacker.featureThread as thread
/** In-app text editor: edit the whole file with an explicit encoding (reusing
 *  the global text encoding + the 编辑/localization workflow). Preserves the
 *  original BOM state so a round-trip is byte-faithful when encoding unchanged. */
fun showTextEditor(activity: AppCompatActivity, file: File, onSaved: (() -> Unit)? = null) {
    val _intentScope = com.usefulunpacker.AppDiagnostics.enterIntent("text_edit")
    try {
    // Bounded read (2 MiB) — reading a huge file whole on the main thread is
    // an ANR; scripts are tiny, so anything larger is rejected up front.
    if (file.length() > 2L shl 20) {
        com.usefulunpacker.UuNotice.makeText(activity, activity.getString(R.string.msg_editor_too_large), Toast.LENGTH_LONG).show()
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
        com.usefulunpacker.UuDialogBuilder(activity)
            .setTitle(activity.getString(R.string.editor_unsaved_changes))
            .setMessage(activity.getString(R.string.editor_restore_prompt))
            .setPositiveButton(activity.getString(R.string.editor_restore)) { _, _ ->
                loadAndOpenEditor(activity, file, tempFile, onSaved, useTempContent = true)
            }
            .setNegativeButton(activity.getString(R.string.editor_discard)) { _, _ ->
                com.usefulunpacker.deleteOutputChecked(tempFile)
                loadAndOpenEditor(activity, file, tempFile, onSaved, useTempContent = false)
            }
            .setNeutralButton(activity.getString(R.string.action_cancel), null)
            .show().also { it.keepTabsTappable() }
    } else {
        loadAndOpenEditor(activity, file, tempFile, onSaved, useTempContent = false)
    }
    } catch (intentCancelled: java.util.concurrent.CancellationException) {
        _intentScope.trace.cancelled(); throw intentCancelled
    } catch (intentFailure: Exception) {
        _intentScope.trace.fail(com.usefulunpacker.FailureCode.UNEXPECTED, intentFailure.message ?: intentFailure.javaClass.simpleName, intentFailure)
        throw intentFailure
    } finally { com.usefulunpacker.AppDiagnostics.leaveIntent(_intentScope) }
}

/** Shared loader for [showTextEditor]: reads the source (and optionally the
 *  autosave copy) off the main thread behind a loading dialog, then opens the
 *  editor on main. Keeps the per-branch semantics of the old inline code. */
private fun loadAndOpenEditor(activity: AppCompatActivity, file: File, tempFile: File, onSaved: (() -> Unit)?, useTempContent: Boolean) {
    val pd = com.usefulunpacker.UuProgressDialog(activity).apply {
        setMessage(activity.getString(R.string.msg_loading)); setCancelable(false); show()
    }
    pd.keepTabsTappable()
    thread {
        val data = com.usefulunpacker.observedAttempt("PreviewDialogs") { file.readBytes() }.getOrNull()
        val tempContent = if (useTempContent) com.usefulunpacker.observedAttempt("PreviewDialogs") { tempFile.readText() }.getOrNull() else null
        activity.runFeatureOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runFeatureOnUiThread
            pd.dismiss()
            if (useTempContent && tempContent != null) {
                val encoding = if (data != null) detectBestEncoding(data) ?: "UTF-8" else "UTF-8"
                val bom = if (data != null) hasBom(data) else false
                openEditorWithContent(activity, tempContent, file, tempFile, onSaved, data ?: tempContent.toByteArray(), encoding, bom)
                return@runFeatureOnUiThread
            }
            if (data == null) {
                com.usefulunpacker.UuNotice.failure(activity, activity.getString(R.string.cannot_read_file, ""), Toast.LENGTH_SHORT).show()
                return@runFeatureOnUiThread
            }
            if (!useTempContent && isTjsBytecode(data)) {
                com.usefulunpacker.UuNotice.makeText(activity, activity.getString(R.string.msg_tjs_compiled), Toast.LENGTH_LONG).show()
                return@runFeatureOnUiThread
            }
            val encoding = detectBestEncoding(data)
                ?: (activity as? MainActivity)?.prefs?.getString("text_encoding", "UTF-8") ?: "UTF-8"
            val bom = hasBom(data)
            openEditorWithContent(activity, decodeTextStrict(data, encoding), file, tempFile, onSaved, data, encoding, bom)
        }
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
    val ownerTrace = AppDiagnostics.currentTrace()
    val autosaveLock = Any()
    val editHistory = EditHistory(maxSteps = 30)
    editHistory.pushState(content)
    var curEncoding = encoding



    // 用于抑制程序化setText触发的TextWatcher
    var suppressWatcher = false

    val et = com.usefulunpacker.UuTextField(activity).apply {
        setText(content)
        gravity = android.view.Gravity.TOP or android.view.Gravity.START
        typeface = android.graphics.Typeface.MONOSPACE
        setHorizontallyScrolling(false)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }

    // 自动保存防抖：停止输入 500ms 后才落盘（后台线程），避免每个按键都
    // 在 UI 线程同步写文件造成卡顿。保存/放弃前必须 cancelPendingAutosave()，
    // 否则挂起的写入会把刚删掉的临时文件又"复活"成过期副本。
    val autosaveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    var autosavePending: Runnable? = null
    // 代际计数：已在跑的 thread{writeText} 无法被 removeCallbacks 取消，若在
    // 保存/放弃的 com.usefulunpacker.deleteOutputChecked(tempFile) 之后完成，会把刚删的临时文件用旧内容
    // 复活（下次打开弹「恢复上次编辑」）。写入前校验代际，过期即弃写。
    val autosaveGen = java.util.concurrent.atomic.AtomicInteger()
    fun cancelPendingAutosave() {
        autosavePending?.let { autosaveHandler.removeCallbacks(it); ownerTrace?.release() }
        autosavePending = null
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
                // 自动保存到临时文件（防抖 + 后台写）
                cancelPendingAutosave()
                autosaveGen.incrementAndGet()
                val gen = autosaveGen.get()
                val snapshot = currentText
                ownerTrace?.retain() // Keep the intent alive throughout the debounce delay.
                val r = Runnable {
                    autosavePending = null
                    try {
                        thread(trace = ownerTrace) {
                            observedAttempt("text_autosave") {
                                synchronized(autosaveLock) {
                                    if (gen == autosaveGen.get()) writeOutputAtomically(tempFile) { staged -> staged.writeText(snapshot); true }
                                }
                            }
                        }
                    } finally { ownerTrace?.release() }
                }
                autosavePending = r
                if (!autosaveHandler.postDelayed(r, 500)) {
                    autosavePending = null
                    try { AppDiagnostics.begin("text_autosave_schedule").fail(FailureCode.IO, "Cannot schedule recovery write") }
                    finally { ownerTrace?.release() }
                }
            }
        }
    })

    val encRow = textEncodingRow(activity, curEncoding) { curEncoding = it }

    // 撤销/重做按钮
    val undoRedoRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(16, 8, 16, 8)
        setBackgroundColor(C["surface"]!!)
    }
    val btnUndo = com.usefulunpacker.UuButton(activity).apply {
        text = activity.getString(R.string.editor_undo)
        isEnabled = false
    }
    val btnRedo = com.usefulunpacker.UuButton(activity).apply {
        text = activity.getString(R.string.editor_redo)
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

    val lineEdit = UuActionRow(activity).apply {
        bind(activity.getString(R.string.line_edit_title), navigation = true) {
            showLineEditor(activity, originalFile, et.text.toString(), rawData, encoding, curEncoding)
        }
    }

    val body = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(C["surface_dark"]!!)
        addView(encRow)
        addView(lineEdit)
        addView(undoRedoRow)
        val dragBar = DragScrollBar(activity).apply { attach(et) }
        val frame = FrameLayout(activity).apply {
            addView(et, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(dragBar, FrameLayout.LayoutParams(
                (20 * activity.resources.displayMetrics.density).toInt(), MATCH, Gravity.END))
        }
        addView(frame, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    val dlg = com.usefulunpacker.UuDialogBuilder(activity)
        .setTitle(activity.getString(R.string.title_text_editor))
        .setView(body)
        .setPositiveButton(activity.getString(R.string.action_save), null)
        .setNegativeButton(activity.getString(R.string.editor_no_save)) { _, _ ->
            autosaveGen.incrementAndGet()
            cancelPendingAutosave()
            synchronized(autosaveLock) { deleteOutputChecked(tempFile) } // Exclude in-flight autosaves.
        }
        .create()
    dlg.setOnShowListener {
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            AppDiagnostics.invokeCallback(ownerTrace) {
                val bytes = encodeText(et.text.toString(), curEncoding, bom)
                autosaveGen.incrementAndGet()
                cancelPendingAutosave()
                try {
                    synchronized(autosaveLock) {
                        writeOutputAtomically(originalFile) { staged -> staged.writeBytes(bytes); true }
                        deleteOutputChecked(tempFile)
                    }
                    UuNotice.makeText(activity, activity.getString(R.string.msg_saved), Toast.LENGTH_SHORT).show()
                    onSaved?.invoke()
                    dlg.dismiss()
                } catch (failure: Exception) {
                    AppDiagnostics.failure("text_save", failure)
                    // Keep the editor and its live text open if saving failed.
                    UuNotice.failure(activity, activity.getString(R.string.err_extract_io, failure.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    // back 键退出时【不】清理临时文件：它是自动保存的恢复副本，下次打开同一
    // 文件时会弹「恢复上次编辑」引导（用户可选恢复或放弃，那里负责真正清理）。
    // 此前这里无条件删除，保存失败后对话框一关恢复副本就没了。
    dlg.belowTabs()
    dlg.show()
}
