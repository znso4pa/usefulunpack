package com.usefulunpacker


import android.widget.*
import java.io.File
import com.usefulunpacker.featureThread as thread
internal fun MainActivity.showOutputDirDialog(src: File, selectedPaths: List<String>, format: String, pwd: String = "", ownerTab: TabState = activeTab) {
        val parent = src.parentFile ?: return
        val outDir = uniqueFile(parent, src.nameWithoutExtension)

        com.usefulunpacker.UuDialogBuilder(this)
            .setTitle(getString(R.string.title_extract_to))
            .setItems(arrayOf(getString(R.string.extract_new_folder, getString(R.string.title_new_folder), outDir.name), getString(R.string.action_extract), getString(R.string.action_choose_dir), getString(R.string.merge_into_archive))) { _, w ->
                when (w) {
                    0 -> extractSelected(src, outDir, selectedPaths, format, ownerTab)
                    1 -> extractSelected(src, parent, selectedPaths, format, ownerTab)
                    2 -> showFolderPicker(this, parent) { picked ->
                        extractSelected(src, picked, selectedPaths, format, ownerTab)
                    }
                    3 -> showMergeTargetPicker(src, selectedPaths, format, pwd, ownerTab)
                }
            }.setNegativeButton(R.string.action_cancel, null)
            .show().also { it.keepTabsTappable() }
    }

internal fun MainActivity.extractSelected(src: File, out: File, paths: List<String>, format: String, ownerTab: TabState = activeTab) {
    val _intentScope = com.usefulunpacker.AppDiagnostics.enterIntent("extract_selected", newChain = true)
    try {
        val selStr = paths.joinToString("\n")
        if (selStr.isEmpty()) { toast(getString(R.string.msg_select_one)); return }
        val existedBefore = out.exists()
        if (format !in setOf("zip", "7z", "rar")) {
            tryExtractWithPassword(this, format, src.path, out.path, selStr, prefs,
                onCancel = {
                    cleanupCancelledOutput(out, existedBefore)
                    toast(getString(R.string.msg_cancelled))
                },
                onResult = { o ->
                    // 失败也要清残留：zip/7z/rar 分支清了，非 zip 分支不清会让
                    // 半成品目录留在归档旁。
                    if (o.counts.ok) {
                        recordTaskOutput(out)
                        showExtractSuccess(src.name, out.name, o.counts)
                        navTab(ownerTab, ownerTab.currentDir)
                    }
                    else { cleanupCancelledOutput(out, existedBefore); toast(friendlyExtractError(this, o.error)) }
                },
                ownerTab = ownerTab
            )
            return
        }
        // zip/7z/rar: prompt for the password up front, and on failure offer a retry dialog.
        fun doSel(p: String): ExtractOutcome = extractByFormat(format, src.path, out.path, selStr, prefs, p)
        thread {
            // Password is resolved BEFORE the lock is taken — the modal prompt
            // blocks up to 30s and would freeze all other windows with bogus
            // "busy" toasts while it waits.
            var pwd = ""
            if (com.usefulunpacker.observedAttempt("PreviewFlow") { isPasswordProtected(src) }.getOrDefault(false)) {
                val entered = promptPasswordSync(this)
                if (entered == null) {
                    runFeatureOnUiThread { if (isFinishing || isDestroyed) return@runFeatureOnUiThread; toast(getString(R.string.msg_cancelled)) }
                    return@thread
                }
                pwd = entered
            }
            runFeatureOnUiThread {
                if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                // Lock first, then the progress dialog (see extractAll).
                val opH = tryStartOperation(this, format)
                var cancelled = false
                val accessors = extractAccessors(format)
                val prog = PollingProgressDialog(
                    this,
                    "${src.name} → ${out.name}",
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
                    try {
                        val result = doSel(pwd)
                        val ok = result.counts.ok
                        if (ok && !cancelled) recordTaskOutput(out)
                        runFeatureOnUiThread {
                            if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                            prog.dismiss()
                            if (cancelled) { cleanupCancelledOutput(out, existedBefore); toast(getString(R.string.msg_cancelled)) }
                            else if (ok) { showExtractSuccess(src.name, out.name, result.counts); navTab(ownerTab, ownerTab.currentDir) }
                            else {
                                cleanupCancelledOutput(out, existedBefore)
                                val inp = com.usefulunpacker.UuTextField(this).apply {
                                    hint = getString(R.string.prompt_password)
                                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                                }
                                com.usefulunpacker.UuDialogBuilder(this)
                                    .setTitle(getString(R.string.title_password))
                                    .setMessage(getString(R.string.retry))
                                    .setView(inp)
                                    .setPositiveButton(getString(R.string.retry)) { _, _ ->
                                        val p2 = inp.text.toString()
                                        val opH2 = tryStartOperation(this, format)
                                        // 重试也走进度卡：此前重试完全隐身且不可取消，
                                        // 大包重试期间用户毫无反馈。
                                        var cancelled2 = false
                                        val accessors2 = extractAccessors(format)
                                        val prog2 = PollingProgressDialog(
                                            this,
                                            "${src.name} → ${out.name}",
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
                                                val o2 = doSel(p2)
                                                if (o2.counts.ok && !cancelled2) recordTaskOutput(out)
                                                runFeatureOnUiThread {
                                                    if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                                                    prog2.dismiss()
                                                    if (cancelled2) { cleanupCancelledOutput(out, existedBefore); toast(getString(R.string.msg_cancelled)) }
                                                    else if (o2.counts.ok) { showExtractSuccess(src.name, out.name, o2.counts); navTab(ownerTab, ownerTab.currentDir) }
                                                    else { cleanupCancelledOutput(out, existedBefore); toast(friendlyExtractError(this, o2.error)) }
                                                }
                                            } finally {
                                                opH2.release()
                                            }
                                        }
                                    }
                                    .setNegativeButton(R.string.action_cancel) { _, _ -> cleanupCancelledOutput(out, existedBefore) }
                                    .show().also { it.keepTabsTappable() }
                            }
                        }
                    } finally {
                        opH.release()
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
