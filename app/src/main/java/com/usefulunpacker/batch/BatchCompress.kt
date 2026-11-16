package com.usefulunpacker

import android.app.AlertDialog
import android.app.ProgressDialog
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.*
import java.io.File
import com.usefulunpacker.featureThread as thread

internal fun MainActivity.startBatchCompress() {
        // 跨 tab：聚合所有 tab 的选中文件
        val sel = allSelectedFiles().values.flatten().toList(); if (sel.isEmpty()) return
        val items = sel.filter { it.isDirectory || (it.isFile && it.extension.lowercase() !in ARCHIVE_EXTS) }
        if (items.isEmpty()) { toast(getString(R.string.no_compressible)); return }
        com.usefulunpacker.UuDialogBuilder(this)
            .setTitle(getString(R.string.title_compress_items, items.size))
            .setItems(arrayOf(getString(R.string.batch_compress_merge), getString(R.string.batch_compress_separate))) { _, w ->
                when (w) {
                    0 -> compressMerged(items)
                    1 -> compressSeparate(items)
                }
            }.setNegativeButton(R.string.action_cancel, null).show().also { it.keepTabsTappable() }
    }

internal fun MainActivity.compressMerged(items: List<File>) {
    val _intentScope = com.usefulunpacker.AppDiagnostics.enterIntent("batch_compress_merged")
    try {
        // 合并只支持多条目归档格式，先选格式再命名；压缩选项（等级/分卷）内联
        showFormatPicker(this, getString(R.string.title_select_format),
            groups = MERGE_COMPRESS_GROUPS, labels = COMPRESS_LABELS
        ) { fmt ->
            val ext = COMPRESS_EXT[fmt] ?: fmt
            val inp = com.usefulunpacker.UuTextField(this).apply {
                setText("archive")
                setSingleLine()
            }
            com.usefulunpacker.UuDialogBuilder(this)
                .setTitle(getString(R.string.title_compress_name))
                .setView(inp)
                .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                    val name = inp.text.toString().trim().ifEmpty { "archive" }
                    showCompressOptionsDialog(this, prefs, fmt) { level, chosenSplit, artemisNaming, gameNaming, mvKey, xp3Enc ->
                        val pwEnabled = prefs.getBoolean("compress_password_enabled", false)
                        val password = if (pwEnabled) prefs.getString("compress_password", "") ?: "" else ""
                        // Sanitize the staging subdir name (reject ../ and path
                        // separators) so a weird name can't escape cacheDir.
                        val safeName = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
                        val tmpDir = File(cacheDir, "batch_compress/$safeName")
                        // Lock first, then the progress dialog (see extractAll);
                        // busy just queues — position/ETA show in the dialog.
                        val opH = tryStartOperation(this, fmt)
                        var cancelled = false
                        val accessors = compressAccessors(fmt)
                        val pd = PollingProgressDialog(
                            this,
                            getString(R.string.title_compress_merged, ext),
                            accessors,
                            { n, b, t -> compressProgressMessage(this, n, b, t) },
                            getString(R.string.action_cancel),
                            { cancelled = true; accessors.cancel() },
                            opH,
                            activeTab
                        )
                        pd.start()
                        thread {
                            if (!opH.await()) return@thread
                            try {
                                // 产物名在【拿到槽位之后】解析：入队时解析的话，排队
                                // 中的第二次封包会和第一次拿到同一个名字互相覆盖。
                                val (outF, renamed) = resolveRgssOutName(
                                    resolvePfsOutName(uniqueFile(currentDir, "$name.$ext"), artemisNaming),
                                    gameNaming, ext
                                )
                                // Copy off the UI thread — large batches would
                                // ANR the main thread here. Clear any stale staging
                                // dir from a previously crashed run first.
                                com.usefulunpacker.deleteOutputChecked(tmpDir, recursive = true)
                                tmpDir.mkdirs()
                                // 跨 tab 聚合的选择常有同名文件（两个窗口各一个
                                // readme.txt 很常见）——直接用原名拷贝会静默覆盖，
                                // 合并产物丢文件。重名的按 `名字 (n)` 去重。
                                val usedNames = mutableSetOf<String>()
                                for (f in items) {
                                    if (cancelled) break
                                    var n = f.name
                                    var i = 1
                                    while (n in usedNames) {
                                        val ext = f.extension
                                        val base = f.nameWithoutExtension
                                        n = if (ext.isNotEmpty()) "$base ($i).$ext" else "$base ($i)"
                                        i++
                                    }
                                    usedNames.add(n)
                                    if (f.isDirectory) f.copyRecursively(File(tmpDir, n))
                                    else f.copyTo(File(tmpDir, n), overwrite = false)
                                }
                                // A cancel pressed during the copy must not be
                                // silently overwritten: the Rust compress entry
                                // clears the cancel flag on entry, so honor the
                                // Kotlin-side flag here instead.
                                val ok = if (cancelled) false else compressDispatch(tmpDir, outF, fmt, level, password, prefs, chosenSplit, xp3Enc)
                                com.usefulunpacker.deleteOutputChecked(tmpDir, recursive = true)
                                // 失败/取消必须清掉半成品：产物名可能是 root.pfs，
                                // 留个截断的 root.pfs 会被游戏当分层补丁挂载。
                                if (!ok) com.usefulunpacker.deleteOutputChecked(outF)
                                runFeatureOnUiThread {
                                    if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                                    pd.dismiss()
                                    if (cancelled) {
                                        toast(getString(R.string.msg_cancelled))
                                    } else if (ok) {
                                        val shown = if (chosenSplit > 0 && fmt in setOf("zip", "7z")) "${outF.name}.001" else outF.name
                                        toast(if (renamed) {
                                            getString(R.string.msg_renamed_to, shown) + " " + getString(R.string.rgss_game_naming_note)
                                        } else "${getString(R.string.msg_extract_complete)} $shown")
                                        // Leave multi-select only on success — on
                                        // cancel/failure keep the selection so the
                                        // user can retry without re-picking files.
                                        exitAllMultiSelect(); nav(currentDir)
                                    } else failureNotice(getString(R.string.title_compress_failed))
                                }
                            } catch (e: Exception) { com.usefulunpacker.AppDiagnostics.failure("BatchCompress", e);
                                // Uncaught I/O here used to crash the app and leave
                                // the progress dialog + poller thread dangling.
                                runFeatureOnUiThread {
                                    if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                                    pd.dismiss()
                                    failureNotice(getString(R.string.err_extract_io, e.message ?: ""))
                                }
                            } finally {
                                com.usefulunpacker.deleteOutputChecked(tmpDir, recursive = true)
                                opH.release()
                            }
                        }
                        true
                    }
                }
                .setNegativeButton(R.string.action_cancel, null).show().also { it.keepTabsTappable() }
        }
    } catch (intentCancelled: java.util.concurrent.CancellationException) {
        _intentScope.trace.cancelled(); throw intentCancelled
    } catch (intentFailure: Exception) {
        _intentScope.trace.fail(com.usefulunpacker.FailureCode.UNEXPECTED, intentFailure.message ?: intentFailure.javaClass.simpleName, intentFailure)
        throw intentFailure
    } finally { com.usefulunpacker.AppDiagnostics.leaveIntent(_intentScope) }
    }

internal fun MainActivity.compressSeparate(items: List<File>) {
    val _intentScope = com.usefulunpacker.AppDiagnostics.enterIntent("batch_compress_separate")
    try {
        showFormatPicker(this, getString(R.string.title_select_format),
            groups = COMPRESS_GROUPS, labels = COMPRESS_LABELS
        ) { fmt ->
            val ext = COMPRESS_EXT[fmt] ?: fmt
            if (fmt in SINGLE_FILE_COMPRESS && items.any { it.isDirectory }) {
                toast(getString(R.string.msg_single_file_compress)); return@showFormatPicker
            }
            if (fmt == "ksd" && items.any { !it.name.lowercase().endsWith(".txt") }) {
                toast(getString(R.string.msg_ksd_need_txt)); return@showFormatPicker
            }
            // MV/MZ 按扩展名定类型（见 mvExtMismatch）。批量下宁可整批拦下也不
            // 静默跳过：用户选了 50 张图，产 47 个会让「少了 3 个」变得难查。
            if (items.any { mvExtMismatch(it, fmt) }) {
                toast(getString(R.string.msg_mv_ext_mismatch, mvRequiredExt(fmt))); return@showFormatPicker
            }
            // 压缩选项（等级/分卷）内联，与单文件压缩流一致。
            showCompressOptionsDialog(this, prefs, fmt) { level, chosenSplit, artemisNaming, gameNaming, mvKey, xp3Enc ->
                val pwEnabled = prefs.getBoolean("compress_password_enabled", false)
                val password = if (pwEnabled) prefs.getString("compress_password", "") ?: "" else ""
                // Lock first, then the progress dialog (see extractAll).
                val opH = tryStartOperation(this, fmt)
                var cancelled = false
                val accessors = compressAccessors(fmt)
                val pd = PollingProgressDialog(
                    this,
                    getString(R.string.msg_batch_compress_title, ext),
                    accessors,
                    { n, b, t -> compressProgressMessage(this, n, b, t) },
                    getString(R.string.action_cancel),
                    { cancelled = true; accessors.cancel() },
                    opH,
                    activeTab
                )
                pd.start()
                thread {
                    if (!opH.await()) return@thread
                    try {
                        var ok = true
                        for (f in items) {
                            if (cancelled) { ok = false; break }
                            // MV obfuscation REPLACES the extension (foo.png ->
                            // foo.rpgmvp); everything else appends. RPG Maker's
                            // Game.<ext> name is deliberately not applied here:
                            // this loop writes N archives, and they would all
                            // claim the same slot.
                            val outName = when {
                                isMvPackKey(fmt) -> mvPackedName(f, fmt)
                                fmt == "ksd" -> "${f.nameWithoutExtension}.$ext"
                                else -> "${f.name}.$ext"
                            }
                            val outF = resolvePfsOutName(uniqueFile(f.parentFile ?: currentDir, outName), artemisNaming)
                            // zip/7z/tar 的 Rust 端 read_dir 不接受单文件输入，需临时目录包裹
                            val ok2 = if (f.isDirectory || fmt !in setOf("zip", "7z", "tar", "tgz", "tbz2", "txz", "tzst")) {
                                compressDispatch(f, outF, fmt, level, if (isMvPackKey(fmt)) mvKey else password, prefs, chosenSplit, xp3Enc)
                            } else {
                                val tmpDir = File(cacheDir, "batch_compress/${f.nameWithoutExtension}")
                                // 先清掉上次崩溃/失败可能留下的残留，否则旧文件会被
                                // 打进本次产物；异常路径也要保证清理（finally 语义）。
                                com.usefulunpacker.deleteOutputChecked(tmpDir, recursive = true)
                                tmpDir.mkdirs()
                                val result = try {
                                    f.copyTo(File(tmpDir, f.name), overwrite = true)
                                    compressDispatch(tmpDir, outF, fmt, level, password, prefs, chosenSplit, xp3Enc)
                                } finally {
                                    com.usefulunpacker.deleteOutputChecked(tmpDir, recursive = true)
                                }
                                result
                            }
                            if (!ok2) { com.usefulunpacker.deleteOutputChecked(outF); ok = false; break }
                        }
                        runFeatureOnUiThread {
                            if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                            pd.dismiss()
                            // 取消/失败保留多选（与 compressMerged 策略一致），
                            // 只有成功才清空并刷新。
                            if (cancelled) toast(getString(R.string.msg_cancelled))
                            else if (ok) {
                                toast(getString(R.string.msg_batch_compress_done)); exitAllMultiSelect(); nav(currentDir)
                            }
                            else failureNotice(getString(R.string.title_compress_failed))
                        }
                    } catch (e: Exception) { com.usefulunpacker.AppDiagnostics.failure("BatchCompress", e);
                        runFeatureOnUiThread { if (isFinishing || isDestroyed) return@runFeatureOnUiThread; pd.dismiss(); failureNotice(getString(R.string.err_extract_io, e.message ?: "")) }
                    } finally {
                        opH.release()
                    }
                }
                true
            }
        }
    } catch (intentCancelled: java.util.concurrent.CancellationException) {
        _intentScope.trace.cancelled(); throw intentCancelled
    } catch (intentFailure: Exception) {
        _intentScope.trace.fail(com.usefulunpacker.FailureCode.UNEXPECTED, intentFailure.message ?: intentFailure.javaClass.simpleName, intentFailure)
        throw intentFailure
    } finally { com.usefulunpacker.AppDiagnostics.leaveIntent(_intentScope) }
    }
