package com.usefulunpacker

import android.app.AlertDialog
import android.app.ProgressDialog
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.*
import java.io.File
import com.usefulunpacker.featureThread as thread

internal fun MainActivity.extractAll(destFile: File, src: File, format: String, initialPwd: String = "", ownerTab: TabState = activeTab) {
    val _intentScope = com.usefulunpacker.AppDiagnostics.enterIntent("extract_all", newChain = true)
    try {
        // Remember whether the output folder pre-existed so a cancelled extract
        // into a fresh folder can be cleaned up entirely.
        val existedBefore = destFile.exists()
        var cancelled = false
        val accessors = extractAccessors(format)
        // Built once a scheduler handle exists (see runFeatureOnUiThread below) so it
        // can render the queued state.
        lateinit var prog: PollingProgressDialog

        fun doExtract(pwd: String = ""): ExtractOutcome =
            extractByFormat(format, src.path, destFile.path, "", prefs, pwd)
        thread {
            // The password is resolved BEFORE the operation lock is taken: the
            // modal prompt blocks up to 30s, and holding the lock through it
            // made every other window's operation report a bogus "busy".
            var pwd = initialPwd
            if (format in setOf("zip", "7z", "rar") && pwd.isEmpty() &&
                com.usefulunpacker.observedAttempt("ExtractAll") { isPasswordProtected(src) }.getOrDefault(false)) {
                pwd = promptPasswordSync(this) ?: ""
                if (pwd.isEmpty()) {
                    runFeatureOnUiThread { if (isFinishing || isDestroyed) return@runFeatureOnUiThread; toast(getString(R.string.msg_cancelled)) }
                    return@thread
                }
            }
            runFeatureOnUiThread {
                if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                // Acquire a scheduler slot BEFORE showing the progress dialog —
                // a queued op surfaces position/ETA in that dialog instead of
                // being refused.
                val opH = tryStartOperation(this, format)
                prog = PollingProgressDialog(
                    this,
                    "${src.name} → ${destFile.name}",
                    accessors,
                    { n, b, t -> extractProgressMessage(this, n, b, t) },
                    getString(R.string.action_cancel),
                    { cancelled = true; accessors.cancel() },
                    opH,
                    ownerTab
                )
                prog.start()
                thread {
                    if (!opH.await()) return@thread
                    var result: ExtractOutcome? = null
                    try {
                        result = doExtract(pwd)
                    } finally {
                        opH.release()
                    }
                    val finalResult = result
                    runFeatureOnUiThread {
                        if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                        prog.dismiss()
                        if (cancelled) {
                            cleanupCancelledOutput(destFile, existedBefore)
                            toast(getString(R.string.msg_cancelled))
                            return@runFeatureOnUiThread
                        }
                        if (finalResult != null && finalResult.counts.ok) { showExtractSuccess(src.name, destFile.name, finalResult.counts); navTab(ownerTab, ownerTab.currentDir) }
                        else if (format in setOf("zip", "7z", "rar")) {
                            cleanupCancelledOutput(destFile, existedBefore)
                            // The lock is free now — the retry acquires it like any
                            // fresh operation.
                            val inp = com.usefulunpacker.UuTextField(this).apply {
                                hint = getString(R.string.prompt_password)
                                setTextColor(C["primary"]!!); setHintTextColor(C["hint"]!!)
                                setBackgroundColor(C["surface"]!!); setPadding(12, 8, 12, 8)
                                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                            }
                            com.usefulunpacker.UuDialogBuilder(this)
                                .setTitle(getString(R.string.title_password))
                                .setMessage(getString(R.string.retry))
                                .setView(inp)
                                .setPositiveButton(getString(R.string.retry)) { _, _ ->
                                    val pwd2 = inp.text.toString()
                                    val opH2 = tryStartOperation(this, format)
                                    var cancelled2 = false
                                    val accessors2 = extractAccessors(format)
                                    val prog2 = PollingProgressDialog(
                                        this,
                                        "${src.name} → ${destFile.name}",
                                        accessors2,
                                        { n, b, t -> extractProgressMessage(this, n, b, t) },
                                        getString(R.string.action_cancel),
                                        { cancelled2 = true; accessors2.cancel() },
                                        opH2,
                                        ownerTab
                                    )
                                    prog2.start()
                                    thread {
                                        if (!opH2.await()) return@thread
                                        try {
                                            val result2 = doExtract(pwd2)
                                            runFeatureOnUiThread {
                                                if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                                                prog2.dismiss()
                                                if (cancelled2) {
                                                    cleanupCancelledOutput(destFile, existedBefore)
                                                    toast(getString(R.string.msg_cancelled))
                                                } else if (result2.counts.ok) { showExtractSuccess(src.name, destFile.name, result2.counts); navTab(ownerTab, ownerTab.currentDir) }
                                                else toast(friendlyExtractError(this, result2.error))
                                            }
                                        } finally {
                                            opH2.release()
                                        }
                                    }
                                }
                                .setNegativeButton(R.string.action_cancel) { _, _ -> cleanupCancelledOutput(destFile, existedBefore) }
                                .show().also { it.keepTabsTappable() }
                        } else toast(friendlyExtractError(this, finalResult?.error ?: ""))
                    }
                }
            }
        }
    } catch (intentCancelled: java.util.concurrent.CancellationException) {
        _intentScope.trace.cancelled(); throw intentCancelled
    } catch (intentFailure: Exception) {
        _intentScope.trace.fail(com.usefulunpacker.FailureCode.UNEXPECTED, intentFailure.message ?: intentFailure.javaClass.simpleName, intentFailure)
        throw intentFailure
    } finally { com.usefulunpacker.AppDiagnostics.leaveIntent(_intentScope) }
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
        com.usefulunpacker.UuDialogBuilder(this, keepChainOpen = false)
            .setTitle("✓ ${getString(R.string.msg_extract_complete)}")
            .setView(layout)
            .setPositiveButton(getString(R.string.action_confirm), null)
            .show().also { it.keepTabsTappable() }
    }
