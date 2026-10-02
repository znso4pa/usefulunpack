package com.usefulunpacker

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Copies [targets] into [targetDir] with a dual-bar card on the shared
 * [OpOverlay] floating layer, a working cancel button, and — the part that
 * actually mattered — **cleanup of whatever it had already written when it stops**.
 *
 * Why this exists: the previous implementation ran on a bare `thread {}` with no
 * feedback until the end. Files appeared in the target directory one by one and
 * the only signal was a "Copied" toast afterwards, so a user could not tell a
 * fast copy from a stalled one, could not stop a large one, and — worst — a
 * failed `copyRecursively` left a **half-written tree** behind that looks like
 * a successful copy to both the user and to any later "did it work?" check.
 *
 * Cancel semantics: targets that already finished are KEPT (they are complete and
 * correct); only the target being copied when cancel arrived is removed, and
 * only if it did not finish. [getCopyFileName] guarantees a fresh destination
 * name (it loops until the candidate doesn't exist), so removing a partial
 * destination can never touch pre-existing user data — it only deletes bytes this
 * operation just wrote. The source is never touched either way.
 *
 * Progress caliber: **bytes**, overall bar = total across all targets, per-file
 * bar = the member currently being written, message line = the member name.
 * Bytes rather than file count because a directory of thousands of tiny files
 * would otherwise crawl while gigabytes stream past.
 *
 * Locking: [OperationLock], same as the delete/recycle and signature-scan flows.
 * A copy is a file operation, not an archive operation, so it deliberately does
 * NOT go through [com.usefulunpacker.archive.OpScheduler] — see AGENTS.md
 * invariant 6, those legacy file-op flows are a documented exception.
 */
fun MainActivity.copyWithProgress(
    targets: List<File>,
    targetDir: File,
    /** Invoked on the **UI thread** after the card is gone. */
    onDone: (copied: Int, failed: Int, cancelled: Boolean) -> Unit = { _, _, _ -> }
) {
    if (targets.isEmpty()) return
    if (!OperationLock.acquire()) {
        toast(getString(R.string.msg_op_in_progress))
        return
    }
    val ownerTabId = activeTab?.tabId
    // Local, not a global: OperationLock serialises copies so there is only ever
    // one, but a file-scope mutable would survive a finished copy and could be
    // flipped by a stale card from a destroyed window.
    val state = CopyState()
    // The card belongs to the window that started the copy.
    val card = OpOverlay.addCard(
        this, getString(R.string.msg_copying), getString(R.string.action_cancel),
        { state.cancelled = true }, ownerTabId
    )

    thread {
        try {
            val r = copyInto(targets, targetDir, card, state)
            // Callers touch views (refreshTab → navTab), so hop to the UI thread
            // here rather than making every call site remember to.
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                onDone(r.copied, r.failed, r.cancelled)
            }
        } finally {
            // Release on EVERY exit, including an unexpected throw: the copy
            // must not wedge every later file operation behind a held lock.
            OperationLock.release()
        }
    }
}

private class CopyState {
    @Volatile var cancelled = false
    /** Last percent posted to each bar — see [pushFileProgress]. */
    @Volatile var filePct = -1
    @Volatile var overallPct = -1
}

private class CopyResult(val copied: Int, val failed: Int, val cancelled: Boolean)

private fun MainActivity.copyInto(
    targets: List<File>,
    targetDir: File,
    card: OpOverlay.Card?,
    state: CopyState
): CopyResult {
    // ── Phase 1: scan. Indeterminate — the total isn't known yet and walking a
    // large tree takes a moment on its own, so a bar would just be a lie here. ──
    card?.let { c ->
        c.fileBar.visibility = View.GONE
        c.fileText.visibility = View.GONE
        c.overallBar.isIndeterminate = true
        c.overallText.text = ""
    }
    val plans = ArrayList<CopyPlan>(targets.size)
    var totalBytes = 0L
    for (src in targets) {
        // Cancelled during the scan: nothing has been written yet, so there is
        // nothing to clean up and no plan worth keeping.
        if (state.cancelled) break
        val plan = CopyPlan(src, File(targetDir, getCopyFileName(src, targetDir)), scanTreeBytes(src, state))
        plans.add(plan)
        totalBytes += plan.bytes
    }

    // ── Phase 2: copy ──
    var copied = 0
    var failed = 0
    var doneBytes = 0L
    for (plan in plans) {
        if (state.cancelled) break
        if (!plan.src.exists()) { failed++; continue }
        state.filePct = -1
        // Cumulative bytes copied INSIDE this target. The overall bar must be fed
        // `doneBytes + targetDone`, not `doneBytes + fileDone`: the latter is the
        // CURRENT file's bytes, so a directory of N files sawtoothed the overall
        // bar back to near zero at every file boundary — it read as the copy
        // "restarting" over and over.
        val outcome = copyOneTarget(plan, state) { fileDone, fileTotal, targetDone, name ->
            pushFileProgress(card, state, name, fileDone, fileTotal)
            pushOverallProgress(card, state, doneBytes + targetDone, totalBytes)
        }
        when (outcome) {
            CopyOutcome.Done -> {
                copied++; doneBytes += plan.bytes
            }
            CopyOutcome.Cancelled -> {
                // Partial destination of an unfinished target: remove it. The
                // source is untouched, so nothing the user cared about is lost.
                // Its bytes are NOT counted — the copy is being thrown away, and
                // counting them would move the bar backwards on cancel.
                plan.dest.deleteRecursively()
                break
            }
            CopyOutcome.Failed -> {
                plan.dest.deleteRecursively()
                failed++
                doneBytes += plan.bytes
            }
        }
    }
    // Snap to the REAL completed total. Deliberately not `totalBytes`: a cancel
    // must not paint 100% for work that was thrown away, and a `length()` that
    // drifted between scan and read must not leave the bar short of where it
    // actually stopped.
    pushOverallProgress(card, state, doneBytes, totalBytes)

    val wasCancelled = state.cancelled
    val ownerTabId = card?.ownerTabId
    val shouldRefresh = copied > 0 || failed > 0 || wasCancelled
    // copyInto runs on a worker thread; the guard goes FIRST and every UI touch
    // after it, per the project invariant (a dismissed Activity throws
    // BadTokenException, which has been hit twice).
    runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread
        card?.let { OpOverlay.removeCard(this, it) }
        toast(
            when {
                wasCancelled -> resources.getQuantityString(R.plurals.msg_copy_cancelled, copied, copied)
                failed > 0 -> getString(R.string.msg_copy_result, copied, failed)
                else -> getString(R.string.msg_copied)
            }
        )
        if (shouldRefresh) refreshAfterCopy(targetDir, ownerTabId)
    }
    return CopyResult(copied, failed, wasCancelled)
}

/** Re-lists the target directory, but only if it is still the active tab's. */
private fun MainActivity.refreshAfterCopy(targetDir: File, ownerTabId: Int?) {
    val tab = tabs.firstOrNull { it.tabId == ownerTabId } ?: return
    if (activeTab === tab) navTab(tab, targetDir)
}

private enum class CopyOutcome { Done, Cancelled, Failed }

private class CopyPlan(val src: File, val dest: File, val bytes: Long)

/**
 * Total bytes of a file, or of every regular file under a directory.
 *
 * The cancel check is per-FILE, not per-target: this runs before the copy loop
 * starts, and on a tree with a symlink cycle `File.isDirectory` follows the link
 * so the walk can spin for a long time. Without the in-loop check the user
 * could not escape it — the copy thread would sit in the scan holding
 * [OperationLock] and every later file operation would report "busy".
 */
private fun scanTreeBytes(file: File, state: CopyState): Long {
    if (file.isFile) return runCatching { file.length() }.getOrDefault(0L)
    if (!file.isDirectory) return 0L
    var size = 0L
    runCatching {
        file.walkBottomUp().forEach { f ->
            if (state.cancelled) return@forEach
            if (f.isFile) size += runCatching { f.length() }.getOrDefault(0L)
        }
    }
    return size
}

/**
 * Copies one target (file or tree), reporting per-file progress via [onFile]
 * and aborting as soon as cancel is observed.
 *
 * `File.copyRecursively` can't be used for any of the three requirements: it
 * reports no progress, can't be interrupted, and on failure leaves everything it
 * already wrote in place.
 */
private fun copyOneTarget(
    plan: CopyPlan,
    state: CopyState,
    onFile: (fileDone: Long, fileTotal: Long, targetDone: Long, name: String) -> Unit
): CopyOutcome {
    // Cumulative bytes copied inside THIS target. Owned here rather than in the
    // caller so it survives across file boundaries — a per-file counter reset to
    // 0 on every file, and feeding that to the OVERALL bar made it sawtooth back
    // to the bottom at each boundary (it read as the copy "restarting").
    var acc = 0L
    if (!plan.src.isDirectory) {
        val (outcome, written) = copyOneFile(plan.src, plan.dest, plan.src.name, plan.bytes, state, onFile, 0L)
        return outcome
    }
    // Iterative walk — a deeply nested tree must not blow the stack. `dest` is a
    // fresh name, so its own subtree is never re-entered.
    val stack = ArrayDeque<String>()
    stack.addLast("")
    while (stack.isNotEmpty()) {
        if (state.cancelled) return CopyOutcome.Cancelled
        val rel = stack.removeLast()
        val srcDir = if (rel.isEmpty()) plan.src else File(plan.src, rel)
        val outDir = if (rel.isEmpty()) plan.dest else File(plan.dest, rel)
        val children = srcDir.listFiles() ?: continue
        if (!outDir.isDirectory && !outDir.mkdirs()) return CopyOutcome.Failed
        for (c in children) {
            if (state.cancelled) return CopyOutcome.Cancelled
            val childRel = if (rel.isEmpty()) c.name else "$rel/${c.name}"
            if (c.isDirectory) {
                stack.addLast(childRel)
            } else {
                val total = runCatching { c.length() }.getOrDefault(0L)
                val (r, written) = copyOneFile(c, File(plan.dest, childRel), childRel, total, state, onFile, acc)
                acc += written
                if (r != CopyOutcome.Done) return r
            }
        }
    }
    return CopyOutcome.Done
}

/** Streams one file so progress and cancel stay live, and verifies the length. */
private fun copyOneFile(
    src: File,
    dest: File,
    label: String,
    size: Long,
    state: CopyState,
    onFile: (Long, Long, Long, String) -> Unit,
    accBefore: Long
): Pair<CopyOutcome, Long> {
    if (state.cancelled) return CopyOutcome.Cancelled to 0L
    // Declared OUTSIDE the try so the catch can report how far it got.
    var written = 0L
    try {
        dest.parentFile?.mkdirs()
        src.inputStream().use { ins ->
            dest.outputStream().use { outs ->
                val buf = ByteArray(256 * 1024)
                onFile(0L, size, accBefore, label)
                while (true) {
                    if (state.cancelled) return CopyOutcome.Cancelled to written
                    val n = ins.read(buf)
                    if (n < 0) break
                    outs.write(buf, 0, n)
                    written += n
                    onFile(written, size, accBefore + written, label)
                }
                outs.flush()
            }
        }
        // A truncated copy (source shrank mid-read, or the volume filled) must
        // not be reported as done — and must not be left on disk.
        if (size > 0 && dest.length() != size) return CopyOutcome.Failed to written
        return CopyOutcome.Done to written
    } catch (e: Exception) {
        return CopyOutcome.Failed to written
    }
}

/**
 * Posts the per-file bar, **at most once per whole percent**.
 *
 * The copy loop reports every 256 KB chunk. Without this throttle a 1 GB file
 * would queue ~8000 `runOnUiThread` posts and the UI thread — which also has to
 * service the very touch handling that lets the user cancel — becomes the
 * bottleneck, i.e. the progress display would slow the copy down.
 */
private fun MainActivity.pushFileProgress(
    card: OpOverlay.Card?, state: CopyState,
    name: String, fileDone: Long, fileTotal: Long
) {
    if (card == null || isFinishing || isDestroyed) return
    val pct = if (fileTotal > 0) (fileDone * 100 / fileTotal).toInt().coerceIn(0, 100) else 0
    if (pct == state.filePct) return
    state.filePct = pct
    val c = card
    runOnUiThread {
        if (c.root.parent == null) return@runOnUiThread
        c.fileBar.visibility = View.VISIBLE
        c.fileText.visibility = View.VISIBLE
        c.fileBar.isIndeterminate = false
        c.fileBar.max = 100
        c.fileBar.progress = pct
        c.fileText.text = name.takeLast(40)
    }
}

/** Posts the overall bar, throttled the same way as [pushFileProgress]. */
private fun MainActivity.pushOverallProgress(
    card: OpOverlay.Card?, state: CopyState,
    done: Long, total: Long
) {
    if (card == null || isFinishing || isDestroyed) return
    val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
    if (pct == state.overallPct && done < total) return
    state.overallPct = pct
    val c = card
    runOnUiThread {
        if (c.root.parent == null) return@runOnUiThread
        c.overallBar.isIndeterminate = false
        c.overallBar.max = 100
        c.overallBar.progress = pct
        c.overallText.text = if (total > 0) "${fmt(done)} / ${fmt(total)}" else fmt(done)
    }
}
