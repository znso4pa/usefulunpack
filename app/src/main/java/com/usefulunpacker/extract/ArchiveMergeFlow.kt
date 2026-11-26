package com.usefulunpacker


import android.widget.*
import java.io.File
import com.usefulunpacker.featureThread as thread
/**
 * Cross-archive merge: pick a target archive in the current directory, then
 * add the source preview's selected entries into it. Implemented as
 * extract → merge dirs → repack into a `目标-cn.ext` copy (original untouched).
 */
internal fun MainActivity.showMergeTargetPicker(src: File, selectedPaths: List<String>, format: String, pwd: String, ownerTab: TabState) {
    // List candidates in the OWNING tab's directory (not the active tab's) —
    // the picker can be invoked from a background tab's preview.
    val groups = mergeTargetGroups(ownerTab, src)
    if (groups.all { it.second.isEmpty() }) {
        toast(getString(R.string.merge_no_target))
        return
    }
    // showFormatPicker is format-agnostic (groups of string keys + a label map),
    // so it doubles as the grouped file picker here. Absolute paths are the keys.
    val labels = HashMap<String, String>()
    groups.forEach { (_, list) ->
        list.forEach { c ->
            labels[c.file.absolutePath] =
                if (c.window == null) c.file.name else "${c.file.name}  (${tabTitle(c.window)})"
        }
    }
    showFormatPicker(
        this, getString(R.string.merge_select_target),
        groups = groups.map { it.first to it.second.map { c -> c.file.absolutePath } },
        labels = labels,
        columns = 1,
    ) { path ->
        val target = File(path)
        // Re-resolve here rather than trusting the list: the directory can have
        // changed between listing and tapping.
        val targetFmt = detectFormat(target) ?: detectFormatByMagic(target)
        if (targetFmt == null || targetFmt !in MERGE_COMPRESS_GROUPS.flatMap { g -> g.second }) {
            toast(getString(R.string.merge_target_unsupported))
            return@showFormatPicker
        }
        mergeIntoArchive(src, selectedPaths, format, pwd, target, targetFmt, ownerTab)
    }
}

/** A merge target plus the window that has it open, or null for "just a file in
 *  this folder" (no window label needed). */
internal data class MergeTarget(val file: File, val window: TabState?)

/**
 * Every archive this tab can merge INTO, split by how the user is most likely
 * to recognise it: first the folder they are browsing, then the archives other
 * windows currently have open.
 *
 * ONE source of truth — the picker and the button's enabled state both read it,
 * so they can never disagree about whether a merge target exists.
 *
 * Only repackable formats survive the filter (zip/7z/tar + xp3/pfs/nsa/iso/ypf +
 * rgss + rpa), so the list never offers something that would bounce off
 * `merge_target_unsupported` on tap. Duplicates (an archive open in a neighbour
 * window that also sits in this folder) collapse to the folder row, which is the
 * nearer one.
 */
private fun MainActivity.mergeTargetGroups(
    ownerTab: TabState, src: File
): List<Pair<Int, List<MergeTarget>>> {
    val mergeable = MERGE_COMPRESS_GROUPS.flatMap { it.second }.toSet()
    fun fmtOf(f: File): String? = detectFormat(f) ?: detectFormatByMagic(f)
    val srcKey = archiveKey(src)

    val here = ownerTab.currentDir.listFiles().orEmpty()
        .filter { it.isFile && isArchiveFile(it) && archiveKey(it) != srcKey }
        .filter { fmtOf(it) in mergeable }
        .sortedBy { it.name.lowercase() }
        .map { MergeTarget(it, null) }

    val seen = here.mapTo(HashSet()) { archiveKey(it.file) }
    // `previewSrc` rather than OpenArchiveRegistry: the registry has no
    // enumeration API, and previewActive guarantees the file is open RIGHT NOW.
    val elsewhere = tabs.asSequence()
        .filter { it !== ownerTab && it.previewActive && it.previewSrc != null }
        .mapNotNull { t -> t.previewSrc?.let { MergeTarget(it, t) } }
        .filter { archiveKey(it.file) != srcKey && archiveKey(it.file) !in seen }
        .filter { fmtOf(it.file) in mergeable }
        .sortedBy { tabTitle(it.window!!) }
        .toList()

    return listOf(
        R.string.merge_group_here to here,
        R.string.merge_group_open to elsewhere,
    )
}

/** Re-evaluates the merge button on every open preview. Another window opening
 *  or closing an archive changes the answer, and nothing else re-renders this
 *  tab, so without this hook the button would go stale. */
internal fun MainActivity.refreshMergeButtons() { for (t in tabs) syncMergeButton(t) }

/**
 * Greys the merge button out when [tab]'s preview has nowhere to merge INTO.
 * Reads the same `mergeTargetGroups` the picker does, so a disabled button
 * always means "no target right now" and an enabled one always means the tap
 * opens a list with something in it.
 */
internal fun MainActivity.syncMergeButton(tab: TabState) {
    val src = tab.previewSrc ?: return
    if (!tab.viewsBound || !tab.previewActive) return
    val ok = mergeTargetGroups(tab, src).any { it.second.isNotEmpty() }
    tab.btnPreviewMerge.isEnabled = ok
    // textColor is a flat colour, not a state list, so the disabled state needs
    // an explicit alpha to be visible at all.
    tab.btnPreviewMerge.alpha = if (ok) 1f else 0.4f
}

/**
 * Extracts the source preview's selected entries, unpacks the target archive,
 * overlays them into one staging dir, and repacks into a NEW archive next to the
 * target (`目标-cn.ext`, or `root.pfs`/`.NNN` for pfs/pf6 — see resolvePfsOutName).
 * Runs under
 * the OpScheduler ("merge" key) with the dual progress dialog; source/target are
 * both held by OpenArchiveRegistry so a second window can't race the same archives.
 */
internal fun MainActivity.mergeIntoArchive(
    src: File, selectedPaths: List<String>, format: String, pwd: String,
    target: File, targetFmt: String, ownerTab: TabState = activeTab
) {
    val _intentScope = com.usefulunpacker.AppDiagnostics.enterIntent("merge", newChain = true)
    try {
    // Both inputs remain read-only. Reserve their native libraries together so
    // ordinary extraction/packing cannot share their progress or cancellation state.
    if (archiveKey(target) == archiveKey(src)) {
        toast(getString(R.string.merge_self_target))
        return
    }
    val parent = target.parentFile ?: ownerTab.currentDir
    // 与编辑回包统一：pfs/pf6 目标也走 Artemis 命名（root.pfs/.NNN），开关同源。
    // Resolve the output name only after acquiring all format resources.
    val artemisNaming = targetFmt in setOf("pfs", "pf6") && prefs.getBoolean("pfs_artemis_naming", true)
    // Same reasoning as the pfs toggle: RPG Maker only opens `Game.<ext>`.
    val gameNaming = targetFmt in setOf("rgssad", "rgss2a", "rgss3a") && prefs.getBoolean("rgss_game_naming", true)
    if (selectedPaths.isEmpty()) { failureNotice(getString(R.string.msg_cannot_read)); return }
    val stageDir = File(cacheDir, "merge/${java.util.UUID.randomUUID()}")
    val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    val trace = AppDiagnostics.begin("merge", listOf(pwd))
    var outputFile: File? = null
    val accessors = extractAccessors(targetFmt)
    // 重封包阶段由压缩进度静态驱动，取消标志是另一份——不一起点火的话，
    // 重包期间点取消是空操作，-cn 文件照样生成却提示「已取消」。
    val compressAcc = compressAccessors(targetFmt)
    val sourceAccessors = extractAccessors(format)
    val phase = java.util.concurrent.atomic.AtomicReference(accessors)
    val progress = ProgressAccessors(
        { phase.get().getCount() }, { phase.get().getTotal() },
        { phase.get().getFileCount() }, { phase.get().getFileTotal() },
        { phase.get().getName() }, { phase.get().cancel() }
    )
    // Built once a scheduler handle exists (see runFeatureOnUiThread below) so the
    // dialog can render its queued state.
    lateinit var prog: PollingProgressDialog
    thread(trace = trace) {
        // Encrypted targets ask once — BEFORE the lock is taken: the modal
        // prompt can block up to 30s and would freeze every other window's
        // operations with bogus "busy" toasts.
        var tgtPwd = ""
        if (targetFmt in setOf("zip", "7z", "rar") && com.usefulunpacker.observedAttempt("PreviewFlow") { isPasswordProtected(target) }.getOrDefault(false)) {
            tgtPwd = promptPasswordSync(this) ?: run { trace.cancelled(); runFeatureOnUiThread { if (isFinishing || isDestroyed) return@runFeatureOnUiThread; toast(getString(R.string.msg_cancelled)) }; return@thread }
        }
        runFeatureOnUiThread {
            if (isFinishing || isDestroyed) return@runFeatureOnUiThread
            val opH = tryStartCompositeOperation(format, targetFmt)
            prog = PollingProgressDialog(
                this,
                getString(R.string.merge_title, target.name),
                progress,
                { n, b, t -> extractProgressMessage(this, n, b, t) },
                getString(R.string.action_cancel),
                { cancelled.set(true); sourceAccessors.cancel(); accessors.cancel(); compressAcc.cancel() },
                opH,
                ownerTab
            )
            prog.start()
            thread {
                if (!opH.await()) { trace.cancelled(); return@thread }
                try {
                    if (!stageDir.mkdirs() && !stageDir.isDirectory) throw java.io.IOException("Cannot create merge staging directory")
                    // Target first, selected source second: the source wins on matching paths.
                    val ready = extractMergeInputs(cancelled::get,
                        target = {
                            phase.set(accessors)
                            AppDiagnostics.step("extract_target") {
                                extractByFormat(targetFmt, target.path, stageDir.path, "", prefs, tgtPwd).also {
                                    if (!cancelled.get()) requireMergeExtraction(it, "target")
                                }
                            }
                        },
                        source = {
                            phase.set(sourceAccessors)
                            AppDiagnostics.step("extract_source") {
                                extractByFormat(format, src.path, stageDir.path, selectedPaths.joinToString("\n"), prefs, pwd).also {
                                    if (!cancelled.get()) requireMergeExtraction(it, "source")
                                }
                            }
                        })
                    if (!ready) { trace.cancelled(); return@thread }
                    // 3. Repack into a new file (never overwrite the original target).
                    val mergeExt = target.extension.ifEmpty { targetFmt }
                    val (outF, renamed) = resolveRgssOutName(
                        resolvePfsOutName(
                            uniqueFile(parent, "${target.nameWithoutExtension}-cn.$mergeExt"),
                            artemisNaming
                        ),
                        gameNaming, mergeExt
                    )
                    // Level and password come from the same settings the pack
                    // flows use: a merge into a password-protected 7z/zip that
                    // silently dropped the password would hand back an
                    // unencrypted copy of a protected archive.
                    outputFile = outF
                    phase.set(compressAcc)
                    val pwEnabled = prefs.getBoolean("compress_password_enabled", false)
                    val password = if (pwEnabled) prefs.getString("compress_password", "") ?: "" else ""
                    // Merging into an xp3 mirrors the TARGET archive's
                    // encryption, for the same reason the repack does: a game's
                    // filter runs over every entry, so a plain merge result is
                    // unreadable by the game it is meant for.
                    val mergeEnc = if (targetFmt == "xp3") xp3RepackEnc(xp3SchemeToken("xp3", target)) else ""
                    val ok = AppDiagnostics.step("repack") {
                        compressDispatch(stageDir, outF, targetFmt, defaultCompressLevel(prefs, targetFmt), password, prefs, null, mergeEnc).also {
                            if (!it && !cancelled.get()) throw java.io.IOException(PackErrors.last)
                        }
                    }
                    // 失败/取消清半成品（产物名可能是 root.pfs）。
                    if (cancelled.get() || !ok) deleteOutputChecked(outF)
                    if (cancelled.get()) trace.cancelled() else if (ok) {
                        recordPackedTaskOutput(outF)
                        trace.success()
                    }
                    runFeatureOnUiThread {
                        if (isFinishing || isDestroyed) return@runFeatureOnUiThread
                        prog.dismiss()
                        if (cancelled.get()) toast(getString(R.string.msg_cancelled))
                        else if (ok) {
                            toast(getString(R.string.merge_done, outF.name) + if (renamed) " " + getString(R.string.rgss_game_naming_note) else "")
                            refreshTab(ownerTab)
                        }
                        else failureNotice(getString(R.string.title_compress_failed))
                    }
                } catch (e: Exception) { com.usefulunpacker.AppDiagnostics.failure("PreviewFlow", e);
                    trace.fail(FailureCode.IO, e.message ?: e.javaClass.simpleName, e)
                    outputFile?.let { deleteOutputChecked(it) }
                    runFeatureOnUiThread { if (isFinishing || isDestroyed) return@runFeatureOnUiThread; prog.dismiss(); failureNotice(getString(R.string.err_extract_io, e.message ?: "")) }
                } finally {
                    deleteOutputChecked(stageDir, recursive = true)
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
