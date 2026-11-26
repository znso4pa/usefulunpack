package com.usefulunpacker


import android.widget.*
import java.io.File
import com.usefulunpacker.featureThread as thread
internal fun MainActivity.previewFileEntry(archive: File, entry: ArchiveEntry, format: String, pwd: String = "", ownerTab: TabState = activeTab) {
    val _intentScope = com.usefulunpacker.AppDiagnostics.enterIntent("entry_preview", newChain = true)
    try {
        val ext = entry.path.substringAfterLast('.').lowercase()
        // Nested archive inside the current one (e.g. a .zip living inside an
        // .xp3): offer to open it in a NEW window so the outer preview is kept.
        val nestedFmt = formatOfName(entry.path)
        // `int` is deliberately absent: its index key comes from the game's exe,
        // which a carved-out temp copy has no access to — the nested-open would
        // only ever bounce. `lpk` is absent for the same reason (a data archive
        // needs the sibling SCRIPT.LPK for its per-file key). `rpa` and `bgi`
        // need nothing external, so they are here.
        if (nestedFmt != null && nestedFmt in setOf("zip", "7z", "rar", "xp3", "pfs", "nsa", "iso", "ypf", "rgss", "rpgmv", "rpa", "bgi")) {
            com.usefulunpacker.UuDialogBuilder(this)
                .setTitle(getString(R.string.nested_archive_title))
                .setMessage(getString(R.string.nested_archive_msg, entry.path, archive.name))
                .setPositiveButton(getString(R.string.nested_archive_open)) { _, _ ->
                    openNestedArchive(archive, entry, format, pwd, nestedFmt)
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show().also { it.keepTabsTappable() }
            return
        }
        val cacheDir = File(cacheDir, "preview/${archive.nameWithoutExtension}")
        fun showExtracted(extracted: File) {
            runFeatureOnUiThread {
                if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                when (ext) {
                    "jpg", "jpeg", "png", "gif", "webp", "bmp" -> showImagePreview(this, extracted)
                    "mp3", "ogg", "wav", "aac", "flac", "aif", "aiff", "m4a" -> playAudio(this, extracted)
                    "mp4", "mkv", "avi", "mov", "webm" -> playVideo(this, extracted)
                    // ZIP entries can be edited in place (zipModify replaces just
                    // that entry); split-volume zips can't (zipModify needs a
                    // single seekable archive and can't re-split), so no 编辑.
                    else -> {
                        val isSplitZip = format == "zip" &&
                            (isVolumeFile(archive) == "zip" || isZipVolumeName(archive.name))
                        val canEditZip = format == "zip" && entry.path.isNotEmpty() && !isSplitZip
                        showTextPreview(this, extracted, showEdit = canEditZip,
                            onEdited = if (canEditZip) { { zipReplaceEntry(archive, entry, extracted, pwd, ownerTab) } } else { null })
                    }
                }
            }
        }
        fun openPreview() {
            val extracted = File(cacheDir, entry.path)
            // The extension decides for known types; for everything else the
            // entry is sniffed *after* extraction, so a script/data file with an
            // extension nobody listed still opens as text instead of being
            // refused. The read is bounded (64 KiB) and off the UI thread.
            if (ext in PREVIEW_EXTS) { showExtracted(extracted); return }
            thread {
                val text = looksLikeTextFile(extracted)
                runFeatureOnUiThread {
                    if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                    if (text) showExtracted(extracted)
                    else failureNotice(getString(R.string.err_preview_unsupported, ".$ext"))
                }
            }
        }
        if (format in setOf("zip", "7z", "rar")) {
            // Password detection reads the central directory via JNI — do it off
            // the UI thread (a multi-GB archive would otherwise jank/ANR).
            thread {
                val needsPw = isPasswordProtected(archive)
                runFeatureOnUiThread {
                    // 密码嗅探可达数秒，期间用户可能已退出——弹窗前必须守卫。
                    if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                    if (needsPw && pwd.isEmpty()) {
                        // No password yet (archive opened via a path that didn't prompt) — ask first.
                        showPasswordDialog(this, format, archive.path, cacheDir.path, entry.path, showProgress = true,
                            onResult = { o -> if (o.counts.ok) openPreview() else toast(friendlyExtractError(this, o.error)) },
                            ownerTab = ownerTab
                        )
                    } else {
                        tryExtractWithPassword(this, format, archive.path, cacheDir.path, entry.path, prefs, showProgress = true, initialPassword = pwd,
                            onResult = { o -> if (o.counts.ok) openPreview() else toast(friendlyExtractError(this, o.error)) },
                            ownerTab = ownerTab
                        )
                    }
                }
            }
        } else {
            tryExtractWithPassword(this, format, archive.path, cacheDir.path, entry.path, prefs, showProgress = true, initialPassword = pwd,
                onResult = { o -> if (o.counts.ok) openPreview() else toast(friendlyExtractError(this, o.error)) },
                ownerTab = ownerTab
            )
        }
    } catch (intentCancelled: java.util.concurrent.CancellationException) {
        _intentScope.trace.cancelled(); throw intentCancelled
    } catch (intentFailure: Exception) {
        _intentScope.trace.fail(com.usefulunpacker.FailureCode.UNEXPECTED, intentFailure.message ?: intentFailure.javaClass.simpleName, intentFailure)
        throw intentFailure
    } finally { com.usefulunpacker.AppDiagnostics.leaveIntent(_intentScope) }
    }
