package com.usefulunpacker

import android.app.AlertDialog
import android.app.ProgressDialog
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.*
import java.io.File
import kotlin.concurrent.thread

internal fun MainActivity.startBatchCompress() {
        val sel = multiSelected.toList(); if (sel.isEmpty()) return
        val items = sel.filter { it.isDirectory || (it.isFile && it.extension.lowercase() !in ARCHIVE_EXTS) }
        if (items.isEmpty()) { toast(getString(R.string.no_compressible)); return }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_compress_items, items.size))
            .setItems(arrayOf(getString(R.string.batch_compress_merge), getString(R.string.batch_compress_separate))) { _, w ->
                when (w) {
                    0 -> compressMerged(items)
                    1 -> compressSeparate(items)
                }
            }.setNegativeButton(getString(R.string.action_cancel), null).show()
    }

internal fun MainActivity.compressMerged(items: List<File>) {
        // 合并只支持多条目归档格式，先选格式再命名；压缩选项（等级/分卷）内联
        showFormatPicker(this, getString(R.string.title_select_format),
            groups = MERGE_COMPRESS_GROUPS, labels = COMPRESS_LABELS
        ) { fmt ->
            val ext = COMPRESS_EXT[fmt] ?: fmt
            val inp = EditText(this).apply {
                setTextColor(C["primary"]!!); setHintTextColor(C["hint"]!!)
                setBackgroundColor(C["surface"]!!); setPadding(12, 8, 12, 8)
                setText("archive")
                setSingleLine()
            }
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.title_compress_name))
                .setView(inp)
                .setPositiveButton(getString(R.string.action_confirm)) { _, _ ->
                    val name = inp.text.toString().trim().ifEmpty { "archive" }
                    showCompressOptionsDialog(this, prefs, fmt) { level, chosenSplit ->
                        val pwEnabled = prefs.getBoolean("compress_password_enabled", false)
                        val password = if (pwEnabled) prefs.getString("compress_password", "") ?: "" else ""
                        val outF = uniqueFile(currentDir, "$name.$ext")
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
                                // Copy off the UI thread — large batches would
                                // ANR the main thread here. Clear any stale staging
                                // dir from a previously crashed run first.
                                tmpDir.deleteRecursively()
                                tmpDir.mkdirs()
                                for (f in items) {
                                    if (cancelled) break
                                    if (f.isDirectory) f.copyRecursively(File(tmpDir, f.name))
                                    else f.copyTo(File(tmpDir, f.name), overwrite = true)
                                }
                                // A cancel pressed during the copy must not be
                                // silently overwritten: the Rust compress entry
                                // clears the cancel flag on entry, so honor the
                                // Kotlin-side flag here instead.
                                val ok = if (cancelled) false else compressDispatch(tmpDir, outF, fmt, level, password, prefs, chosenSplit)
                                tmpDir.deleteRecursively()
                                runOnUiThread {
                                    pd.dismiss()
                                    if (cancelled) {
                                        toast(getString(R.string.msg_cancelled))
                                    } else if (ok) {
                                        val shown = if (chosenSplit > 0 && fmt in setOf("zip", "7z")) "${outF.name}.001" else outF.name
                                        toast("${getString(R.string.msg_extract_complete)} $shown")
                                        // Leave multi-select only on success — on
                                        // cancel/failure keep the selection so the
                                        // user can retry without re-picking files.
                                        exitMultiSelect(); nav(currentDir)
                                    } else toast(getString(R.string.title_compress_failed))
                                }
                            } catch (e: Exception) {
                                // Uncaught I/O here used to crash the app and leave
                                // the progress dialog + poller thread dangling.
                                runOnUiThread {
                                    pd.dismiss()
                                    toast(getString(R.string.err_extract_io, e.message ?: ""))
                                }
                            } finally {
                                tmpDir.deleteRecursively()
                                opH.release()
                            }
                        }
                        true
                    }
                }
                .setNegativeButton(getString(R.string.action_cancel), null).show()
        }
    }

internal fun MainActivity.compressSeparate(items: List<File>) {
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
            // 压缩选项（等级/分卷）内联，与单文件压缩流一致。
            showCompressOptionsDialog(this, prefs, fmt) { level, chosenSplit ->
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
                            val outName = if (fmt == "ksd") "${f.nameWithoutExtension}.$ext" else "${f.name}.$ext"
                            val outF = uniqueFile(f.parentFile ?: currentDir, outName)
                            // zip/7z/tar 的 Rust 端 read_dir 不接受单文件输入，需临时目录包裹
                            val ok2 = if (f.isDirectory || fmt !in setOf("zip", "7z", "tar", "tgz", "tbz2", "txz", "tzst")) {
                                compressDispatch(f, outF, fmt, level, password, prefs, chosenSplit)
                            } else {
                                val tmpDir = File(cacheDir, "batch_compress/${f.nameWithoutExtension}")
                                tmpDir.mkdirs()
                                f.copyTo(File(tmpDir, f.name), overwrite = true)
                                val result = compressDispatch(tmpDir, outF, fmt, level, password, prefs, chosenSplit)
                                tmpDir.deleteRecursively()
                                result
                            }
                            if (!ok2) { ok = false; break }
                        }
                        runOnUiThread {
                            pd.dismiss()
                            if (cancelled) toast(getString(R.string.msg_cancelled))
                            else if (ok) toast(getString(R.string.msg_batch_compress_done))
                            else toast(getString(R.string.title_compress_failed))
                            exitMultiSelect(); nav(currentDir)
                        }
                    } catch (e: Exception) {
                        runOnUiThread { pd.dismiss(); toast(getString(R.string.err_extract_io, e.message ?: "")) }
                    } finally {
                        opH.release()
                    }
                }
                true
            }
        }
    }
