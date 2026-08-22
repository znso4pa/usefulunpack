package com.usefulunpacker

import android.app.AlertDialog
import android.app.ProgressDialog
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.*
import java.io.File
import kotlin.concurrent.thread

internal fun MainActivity.extractAll(destFile: File, src: File, format: String, initialPwd: String = "", ownerTab: TabState = activeTab) {
        // Acquire the lock BEFORE showing the progress dialog — otherwise a busy
        // lock leaves the dialog spinning forever with the work silently dropped.
        if (!tryStartOperation(this)) return
        // Remember whether the output folder pre-existed so a cancelled extract
        // into a fresh folder can be cleaned up entirely.
        val existedBefore = destFile.exists()
        var cancelled = false
        val accessors = extractAccessors(format)
        val prog = PollingProgressDialog(
            this,
            "${src.name} → ${destFile.name}",
            accessors,
            { n, b, t -> extractProgressMessage(this, n, b, t) },
            getString(R.string.action_cancel),
            { cancelled = true; accessors.cancel() }
        )
        prog.start()

        fun doExtract(pwd: String = ""): ExtractOutcome {
            return runCatching {
                when (format) {
                    "zip" -> { ZipCore.zipSetEncoding(prefs.getString("zip_encoding", "UTF-8") ?: "UTF-8"); ExtractOutcome(ExtractCounts.fromJson(zipExtractDispatch(src.path, destFile.path, "", pwd)), null) }
                    "7z" -> ExtractOutcome(ExtractCounts.fromJson(szExtractDispatch(src.path, destFile.path, "", pwd)), null)
                    "rar" -> ExtractOutcome(ExtractCounts.fromJson(rarExtractDispatch(src.path, destFile.path, "", pwd)), null)
                    else -> extractByFormat(format, src.path, destFile.path, "", prefs)
                }
            }.getOrElse { e -> ExtractOutcome(ExtractCounts(0, 0, 0), e.message) }
        }
        thread {
            // The lock is released on EVERY exit below — including the password
            // retry dialog, which is shown AFTER release so the retry acquires
            // the lock cleanly (an acquire inside the retry while this thread
            // still held the lock was the "already in progress" deadlock).
            var result: ExtractOutcome? = null
            try {
                result = if (format in setOf("zip", "7z", "rar") && isPasswordProtected(src)) {
                    val pwd = if (initialPwd.isNotEmpty()) initialPwd else (promptPasswordSync(this) ?: "")
                    if (pwd.isEmpty()) {
                        runOnUiThread { prog.dismiss(); toast(getString(R.string.msg_cancelled)) }
                        return@thread
                    }
                    doExtract(pwd)
                } else {
                    doExtract()
                }
            } finally {
                OperationLock.release()
            }
            val finalResult = result
            runOnUiThread {
                prog.dismiss()
                if (cancelled) {
                    cleanupCancelledOutput(destFile, existedBefore)
                    toast(getString(R.string.msg_cancelled))
                    return@runOnUiThread
                }
                if (finalResult != null && finalResult.counts.ok) { showExtractSuccess(src.name, destFile.name, finalResult.counts); navTab(ownerTab, ownerTab.currentDir) }
                else if (format in setOf("zip", "7z", "rar")) {
                    cleanupCancelledOutput(destFile, existedBefore)
                    // The lock is free now — the retry acquires it like any
                    // fresh operation.
                    val inp = EditText(this).apply {
                        hint = getString(R.string.prompt_password)
                        setTextColor(C["primary"]!!); setHintTextColor(C["hint"]!!)
                        setBackgroundColor(C["surface"]!!); setPadding(12, 8, 12, 8)
                        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    }
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.title_password))
                        .setMessage(getString(R.string.retry))
                        .setView(inp)
                        .setPositiveButton(getString(R.string.retry)) { _, _ ->
                            val pwd = inp.text.toString()
                            if (!tryStartOperation(this)) return@setPositiveButton
                            var cancelled2 = false
                            val accessors2 = extractAccessors(format)
                            val prog2 = PollingProgressDialog(
                                this,
                                "${src.name} → ${destFile.name}",
                                accessors2,
                                { n, b, t -> extractProgressMessage(this, n, b, t) },
                                getString(R.string.action_cancel),
                                { cancelled2 = true; accessors2.cancel() }
                            )
                            prog2.start()
                            thread {
                                try {
                                    val result2 = doExtract(pwd)
                                    runOnUiThread {
                                        prog2.dismiss()
                                        if (cancelled2) {
                                            cleanupCancelledOutput(destFile, existedBefore)
                                            toast(getString(R.string.msg_cancelled))
                                        } else if (result2.counts.ok) { showExtractSuccess(src.name, destFile.name, result2.counts); navTab(ownerTab, ownerTab.currentDir) }
                                        else toast(friendlyExtractError(this, result2.error))
                                    }
                                } finally {
                                    OperationLock.release()
                                }
                            }
                        }
                        .setNegativeButton(getString(R.string.action_cancel)) { _, _ -> cleanupCancelledOutput(destFile, existedBefore) }
                        .show()
                } else toast(friendlyExtractError(this, finalResult?.error ?: ""))
            }
        }
    }

internal fun MainActivity.showExtractSuccess(srcName: String, outName: String, c: ExtractCounts) {
        val other = c.total - c.success - c.error
        val content = "${srcName}\n→ $outName"
        val tv = TextView(this).apply {
            text = content; setTextColor(C["primary"]!!); textSize = 14f
            setPadding(24, 16, 24, 8)
        }
        val tvCounts = TextView(this).apply {
            text = getString(R.string.extract_result_stats, c.total, c.success, c.error, other)
            setTextColor(C["tertiary"]!!); textSize = 12f
            setPadding(24, 0, 24, 8)
        }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(tv); addView(tvCounts) }
        AlertDialog.Builder(this)
            .setTitle("✓ ${getString(R.string.msg_extract_complete)}")
            .setView(layout)
            .setPositiveButton(getString(R.string.action_confirm), null)
            .show()
    }
