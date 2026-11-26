package com.usefulunpacker


import android.widget.*
import java.io.File
import com.usefulunpacker.featureThread as thread
/**
 * Lists an archive's entries as the format's raw JSON, or null when the format
 * cannot be listed.
 *
 * SINGLE SOURCE for the per-format listing dispatch. It used to be copy-pasted
 * into three places (previewArchive, listPreviewEntries, batchPreview) and the
 * copies had already drifted: batchPreview was the only one that never applied
 * the ZIP encoding preference, so a GBK-named zip listed as mojibake in batch
 * preview while single preview was correct. Always call this instead.
 *
 * @param pwd already-resolved password, or "" (the caller must have asked
 *   BEFORE taking a scheduler slot — see the project invariant).
 * @param nestedOnly restricts the result to formats that can appear *inside*
 *   another archive, i.e. the ones with more than one entry.
 */
internal fun MainActivity.listEntriesJson(
    format: String, src: File, pwd: String, nestedOnly: Boolean, onError: ((String) -> Unit)? = null
): String? = listEntriesJson(format, src, pwd, nestedOnly, prefs, onError)

internal fun listEntriesJson(
    format: String, src: File, pwd: String, nestedOnly: Boolean,
    prefs: android.content.SharedPreferences? = null, onError: ((String) -> Unit)? = null
): String? {
    val trace = AppDiagnostics.begin("list_entries", listOf(pwd))
    var notApplicable = false
    return AppDiagnostics.withTrace(trace) { try {
    val json = when (format) {
        "xp3" -> Xp3Core.xp3ListEntries(src.absolutePath)
        "pfs" -> PfsCore.pfsListEntries(src.absolutePath)
        "nsa" -> NsaCore.nsaListEntries(src.absolutePath)
        "rpa" -> RpaCore.rpaListEntries(src.absolutePath)
        "int" -> IntCore.intListEntries(src.absolutePath)
        "bgi" -> BgiCore.bgiListEntries(src.absolutePath)
        "lpk" -> LpkCore.lpkListEntries(src.absolutePath)
        "iso" -> IsoCore.isoListEntries(src.absolutePath)
        "ypf" -> YpfCore.ypfListEntries(src.absolutePath)
        "rgss" -> RgssCore.rgssListEntries(src.absolutePath)
        "rpgmv" -> RgssCore.rgssMvListEntries(src.absolutePath)
        // The encoding is process-global native state, so it must be re-applied
        // on every listing — not just once per process.
        "zip" -> {
            ZipCore.zipSetEncoding(prefs?.getString("zip_encoding", "UTF-8") ?: "UTF-8")
            val vols = resolveZipVolumes(src)
            if (vols.size > 1) ZipCore.zipListEntriesVolumes(volumeJoin(vols)) else ZipCore.zipListEntries(src.absolutePath)
        }
        "7z" -> {
            val vols = resolveSevenZVolumes(src)
            if (vols.size > 1) (if (pwd.isNotEmpty()) SevenZCore.szListEntriesVolumesWithPassword(volumeJoin(vols), pwd) else SevenZCore.szListEntriesVolumes(volumeJoin(vols)))
            else if (pwd.isNotEmpty()) SevenZCore.szListEntriesWithPassword(src.absolutePath, pwd) else SevenZCore.szListEntries(src.absolutePath)
        }
        "rar" -> {
            val vols = resolveRarVolumes(src)
            if (vols.size > 1) (if (pwd.isNotEmpty()) RarCore.rarListEntriesVolumesWithPassword(volumeJoin(vols), pwd) else RarCore.rarListEntriesVolumes(volumeJoin(vols)))
            else if (pwd.isNotEmpty()) RarCore.rarListEntriesWithPassword(src.absolutePath, pwd) else RarCore.rarListEntries(src.absolutePath)
        }
        // Single-stream formats: one synthetic entry, nothing to list. Only the
        // full-archive listing paths can show them.
        "lz4" -> if (nestedOnly) { notApplicable = true; null } else Lz4Core.lz4ListEntries(src.absolutePath)
        "gz" -> if (nestedOnly) { notApplicable = true; null } else GzipCore.gzListEntries(src.absolutePath)
        "bz2" -> if (nestedOnly) { notApplicable = true; null } else Bzip2Core.bz2ListEntries(src.absolutePath)
        "xz" -> if (nestedOnly) { notApplicable = true; null } else XzCore.xzListEntries(src.absolutePath)
        "zst" -> if (nestedOnly) { notApplicable = true; null } else ZstdCore.zstListEntries(src.absolutePath)
        "lzma" -> if (nestedOnly) { notApplicable = true; null } else LzmaCore.lzmaListEntries(src.absolutePath)
        "br" -> if (nestedOnly) { notApplicable = true; null } else BrotliCore.brotliListEntries(src.absolutePath)
        "tar" -> if (nestedOnly) { notApplicable = true; null } else TarCore.tarListEntries(src.absolutePath)
        "ksd" -> if (nestedOnly) { notApplicable = true; null } else KsdCore.ksdListEntries(src.absolutePath)
        else -> { notApplicable = true; null }
    }
    if (notApplicable) trace.skipped()
    else if (json == null) trace.fail(FailureCode.INVALID_RESULT, "Listing returned no result")
    else trace.success()
    json
} catch (e: Exception) {
    // The native layer's message is the only thing that says *why* an archive
    // could not be listed ("needs the game exe next to it", "unsupported ZiX
    // variant", …). Swallowing it left the user staring at a generic "cannot
    // read" with no way to act, so callers that can show it pass `onError`.
    // The JNI wrapper prefixes its own op name ("listEntries: …"); the native
    // message already names the format, so drop the redundant prefix.
    trace.fail(FailureCode.IO, e.message ?: e.javaClass.simpleName, e)
    onError?.invoke((e.message ?: "").removePrefix("listEntries: "))
    null
} catch (e: LinkageError) {
    trace.fail(FailureCode.UNSUPPORTED, "Native archive library unavailable")
    null
}
    }
}

/**
 * Lists an archive's entries for the in-tab preview, mirroring the dispatch
 * used when first opening an archive. Returns null when the archive can't be
 * read (wrong format / encrypted without password).
 */
    internal fun MainActivity.listPreviewEntries(format: String, src: File, pwd: String): List<ArchiveEntry>? {
        val json = listEntriesJson(format, src, pwd, nestedOnly = true) ?: return null
        if (json == "[]") return null
        return parseEntries(json)
    }

    /**
     * Extracts a nested archive entry out of its parent and opens it for preview
     * in a brand-new window (new tab), auto-jumping to that window. The outer
     * preview stays intact so the user can swipe back to it.
     */
    internal fun MainActivity.openNestedArchive(
        archive: File, entry: ArchiveEntry, parentFmt: String, parentPwd: String, nestedFmt: String
    ) {
        val outDir = File(cacheDir, "nested/${archive.nameWithoutExtension}_${entry.path.hashCode().toString(16)}")
        outDir.mkdirs()
        val extracted = File(outDir, entry.path)
        val openKey = "nested:" + extracted.absolutePath
        tryExtractWithPassword(this, parentFmt, archive.path, outDir.path, entry.path, prefs,
            showProgress = true, initialPassword = parentPwd,
            onResult = { o ->
            // 每个晚到的失败路径都要清掉已解出的嵌套包：cacheDir/nested 无任何
            // 其他清理机制，漏删就是永久孤儿（直到系统清缓存）。
            if (!o.counts.ok) { com.usefulunpacker.deleteOutputChecked(outDir, recursive = true); toast(friendlyExtractError(this, o.error)); return@tryExtractWithPassword }
            if (!extracted.exists()) {
                com.usefulunpacker.deleteOutputChecked(outDir, recursive = true)
                failureNotice(getString(R.string.err_preview_unsupported, ""))
                return@tryExtractWithPassword
            }
            // Listing a multi-GB nested archive is a slow JNI call — keep it
            // off the UI thread (this callback runs on it).
            thread {
                val entries = listPreviewEntries(nestedFmt, extracted, "")
                // A nested cxdec archive has no sidecar of its own (it was just
                // carved out of its parent), so the probe can only report
                // "suspect" here — which is exactly the warning the user needs.
                val encNote = xp3SchemeToken(nestedFmt, extracted)
                runFeatureOnUiThread {
                    if (isFinishing || isDestroyed) { com.usefulunpacker.deleteOutputChecked(outDir, recursive = true); return@runFeatureOnUiThread }
                    if (entries.isNullOrEmpty()) {
                        com.usefulunpacker.deleteOutputChecked(outDir, recursive = true)
                        failureNotice(getString(R.string.err_cannot_read_maybe_pwd))
                        return@runFeatureOnUiThread
                    }
                    val before = tabs.size
                    addTab()
                    if (tabs.size == before) { com.usefulunpacker.deleteOutputChecked(outDir, recursive = true); return@runFeatureOnUiThread  // max tabs reached
                    }
                    val newTab = tabs.last()
                    // 与 previewArchive 相同的注册竞态处理：抢注失败（他窗已持
                    // 同键）则不渲染，关掉刚开的空窗并清理。
                    if (!OpenArchiveRegistry.register(openKey, newTab)) {
                        val winner = OpenArchiveRegistry.owner(openKey)
                        closeTab(newTab)
                        com.usefulunpacker.deleteOutputChecked(outDir, recursive = true)
                        if (winner != null) toast(getString(R.string.msg_archive_open_in_tab, tabTitle(winner)))
                        return@runFeatureOnUiThread
                    }
                    renderPreview(newTab, extracted, entries, nestedFmt, "", openKey, encNote)
                }
            }
            }
        )
    }
