package com.usefulunpacker

import android.app.AlertDialog
import android.content.SharedPreferences
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread
import java.io.File
import com.usefulunpacker.archive.OpScheduler

/**
 * Single-operation lock. The app drives one long-running extract/compress at a
 * time; concurrent operations would clobber the shared per-format progress
 * statics. Acquire/release are a plain mutual-exclusion pair: the UI thread
 * (or any entry thread) calls [acquire] before showing the progress dialog and
 * the worker thread calls [release] in a `finally`. Release is intentionally
 * thread-agnostic — the acquire and release always happen on different threads
 * (UI acquires, worker releases), so a holder check would never fire.
 */
object OperationLock {
    @Volatile private var busy = false

    fun acquire(): Boolean = synchronized(this) {
        if (busy) false else { busy = true; true }
    }

    fun release() = synchronized(this) {
        busy = false
    }
}

/** Collapses a format key onto the scheduler slot it must actually share.
 *
 * Format keys that are handled by the SAME native library also share that
 * library's single `extract_progress` / `compress_progress` store, so running
 * them concurrently under separate keys would cross-contaminate both progress
 * bars. Three such families exist today:
 *  - `pf6` and `pfs` both live in `libarchive_pfs_core` -> slot `pfs`
 *  - `rgssad` / `rgss2a` / `rgss3a` all live in `libarchive_rgss_core` and
 *    share the read key `rgss` -> slot `rgss`
 *  - the five tar variants are ONE `tarCompress(fmt, …)` entry point in
 *    `libarchive_tar_core` -> slot `tar` (they differ only in the container,
 *    not in the native side; packing a `.tar` and a `.tgz` at once reset each
 *    other's total and let one cancel abort the other)
 *
 * Done here, inside the single choke point every operation goes through, so a
 * new call site cannot forget it (the `pf6` collapse was previously duplicated
 * by hand at each of its three sites and had to be re-audited twice). */
fun schedulerKeyOf(fmtKey: String): String = when (fmtKey) {
    "pf6" -> "pfs"
    "tar", "tgz", "tbz2", "txz", "tzst" -> "tar"
    // `rpgmv` shares libarchive_rgss_core with the archive side, so it shares
    // the progress store too — hence one slot rather than two.
    "rgssad", "rgss2a", "rgss3a", "rpgmv", "rpgmvp", "rpgmvo", "rpgmvm" -> "rgss"
    else -> fmtKey
}

/** Acquires a scheduler slot for [fmtKey] (never refuses — busy operations are
 *  QUEUED with live position/ETA shown in the progress dialog). The worker
 *  thread must call `handle.await()` before touching the format layer and
 *  `handle.release()` in its finally. Delete/scan flows intentionally stay on
 *  the legacy OperationLock (see OpScheduler docs). */
fun tryStartOperation(activity: AppCompatActivity, fmtKey: String): OpScheduler.OpHandle =
    OpScheduler.obtain(schedulerKeyOf(fmtKey))

/** Maps the raw JNI error message to a user-facing string. */
fun friendlyExtractError(activity: AppCompatActivity, error: String?): String {
    val msg = error
    if (msg.isNullOrEmpty()) return activity.getString(R.string.title_extract_failed)
    val m = msg.lowercase()
    return when {
        // Rust sevenz-rust Error Display = Debug, so variants are camelCase
        // with no spaces: PasswordRequired / MaybeBadPassword /
        // ChecksumVerificationFailed. Match both spaced prose and the
        // camelCase spellings.
        m.contains("wrong password") || m.contains("bad password") || m.contains("password required") ||
            m.contains("maybe bad password") || m.contains("checksum verification failed") ||
            m.contains("passwordrequired") || m.contains("maybebadpassword") ||
            m.contains("checksumverificationfailed") ->
            activity.getString(R.string.err_pwd_wrong)
        m.contains("buffered") || m.contains("configured limit") || m.contains("decode limit") || m.contains("memory") ->
            activity.getString(R.string.err_large_member)
        else -> activity.getString(R.string.err_extract_io, msg)
    }
}

fun rarExtractDispatch(src: String, out: String, sel: String, pw: String): String? {
    val vols = resolveRarVolumes(File(src))
    return if (vols.size > 1) {
        val joined = volumeJoin(vols)
        when {
            sel.isNotEmpty() && pw.isNotEmpty() -> RarCore.rarExtractSelectedVolumesWithPassword("", joined, out, sel, pw)
            sel.isNotEmpty() -> RarCore.rarExtractSelectedVolumes("", joined, out, sel)
            pw.isNotEmpty() -> RarCore.rarExtractVolumesWithPassword("", joined, out, pw)
            else -> RarCore.rarExtractVolumes("", joined, out)
        }
    } else {
        when {
            sel.isNotEmpty() && pw.isNotEmpty() -> RarCore.rarExtractSelectedWithPassword("", src, out, sel, pw)
            sel.isNotEmpty() -> RarCore.rarExtractSelected("", src, out, sel)
            pw.isNotEmpty() -> RarCore.rarExtractWithPassword("", src, out, pw)
            else -> RarCore.rarExtract("", src, out)
        }
    }
}

/** ZIP selected+password is now a first-class JNI combo; plain full/password paths remain. */
fun zipExtractDispatch(src: String, out: String, sel: String, pw: String): String? {
    val vols = resolveZipVolumes(File(src))
    return if (vols.size > 1) {
        val joined = volumeJoin(vols)
        when {
            sel.isNotEmpty() && pw.isNotEmpty() -> ZipCore.zipExtractSelectedVolumesWithPassword("", joined, out, sel, pw)
            pw.isNotEmpty() -> ZipCore.zipExtractVolumesWithPassword("", joined, out, pw)
            sel.isNotEmpty() -> ZipCore.zipExtractSelectedVolumes("", joined, out, sel)
            else -> ZipCore.zipExtractVolumes("", joined, out)
        }
    } else {
        when {
            sel.isNotEmpty() && pw.isNotEmpty() -> ZipCore.zipExtractSelectedWithPassword("", src, out, sel, pw)
            pw.isNotEmpty() -> ZipCore.zipExtractWithPassword("", src, out, pw)
            sel.isNotEmpty() -> ZipCore.zipExtractSelected("", src, out, sel)
            else -> ZipCore.zipExtract("", src, out)
        }
    }
}

fun szExtractDispatch(src: String, out: String, sel: String, pw: String): String? {
    val vols = resolveSevenZVolumes(File(src))
    return if (vols.size > 1) {
        val joined = volumeJoin(vols)
        when {
            sel.isNotEmpty() && pw.isNotEmpty() -> SevenZCore.szExtractSelectedVolumesWithPassword("", joined, out, sel, pw)
            sel.isNotEmpty() -> SevenZCore.szExtractSelectedVolumes("", joined, out, sel)
            pw.isNotEmpty() -> SevenZCore.szExtractVolumesWithPassword("", joined, out, pw)
            else -> SevenZCore.szExtractVolumes("", joined, out)
        }
    } else {
        when {
            sel.isNotEmpty() && pw.isNotEmpty() -> SevenZCore.szExtractSelectedWithPassword("", src, out, sel, pw)
            sel.isNotEmpty() -> SevenZCore.szExtractSelected("", src, out, sel)
            pw.isNotEmpty() -> SevenZCore.szExtractWithPassword("", src, out, pw)
            else -> SevenZCore.szExtract("", src, out)
        }
    }
}

fun rarVolumesNeedsPassword(src: String): Boolean {
    val vols = resolveRarVolumes(File(src))
    return if (vols.size > 1) RarCore.rarVolumesNeedsPassword(volumeJoin(vols)) else RarCore.rarNeedsPassword(src)
}

fun szVolumesNeedsPassword(src: String): Boolean {
    val vols = resolveSevenZVolumes(File(src))
    return if (vols.size > 1) SevenZCore.szVolumesNeedsPassword(volumeJoin(vols)) else SevenZCore.szNeedsPassword(src)
}

fun zipVolumesNeedsPassword(src: String): Boolean {
    val vols = resolveZipVolumes(File(src))
    return if (vols.size > 1) ZipCore.zipVolumesNeedsPassword(volumeJoin(vols)) else ZipCore.zipNeedsPassword(src)
}

/**
 * True when the archive sits next to a cxdec filter sidecar — the game
 * folder's xp3filter.tjs, or a .tpm/.dat carrying the control block. A .dat
 * match is only a hint: the cxdec probe validates the scheme and the caller
 * falls back to plain extraction when it cannot.
 */
fun hasCxdecFilterSidecar(src: String): Boolean {
    val dir = File(src).parentFile ?: return false
    return dir.listFiles()?.any {
        it.name.equals("xp3filter.tjs", ignoreCase = true) ||
            it.name.endsWith(".tpm", ignoreCase = true) ||
            it.name.endsWith(".dat", ignoreCase = true)
    } ?: false
}

/**
 * XP3 extraction with cxdec routing: when a filter sidecar sits next to the
 * archive, the cxdec decrypt path runs first (classic cxdec games extract as
 * garbage through the plain path), falling back to the plain extractor when
 * the cxdec probe finds no matching scheme.
 */
private fun xp3ExtractDispatch(src: String, out: String, selected: String): String? {
    val gameDir = File(src).parent
    if (gameDir != null && hasCxdecFilterSidecar(src)) {
        val json = try {
            if (selected.isEmpty()) Xp3Core.xp3CxdecExtract("", gameDir, src, out)
            else Xp3Core.xp3CxdecExtractSelected("", gameDir, src, out, selected)
        } catch (e: Exception) {
            null // probe failed / not actually cxdec — fall through to plain
        }
        if (json != null) return json
    }
    return if (selected.isEmpty()) Xp3Core.xp3Extract("", src, out)
           else Xp3Core.xp3ExtractSelected("", src, out, selected)
}

fun extractByFormat(
    format: String, src: String, out: String, selected: String,
    prefs: SharedPreferences, password: String = ""
): ExtractOutcome {
    return try {
        val json = when (format) {
            "xp3" -> xp3ExtractDispatch(src, out, selected)
            "pfs" -> if (selected.isEmpty()) PfsCore.pfsExtract("", src, out)
                     else PfsCore.pfsExtractSelected("", src, out, selected)
            "iso" -> if (selected.isEmpty()) IsoCore.isoExtract("", src, out)
                     else IsoCore.isoExtractSelected("", src, out, selected)
            "ypf" -> if (selected.isEmpty()) YpfCore.ypfExtract("", src, out)
                     else YpfCore.ypfExtractSelected("", src, out, selected)
            // One key for all three RGSS container versions: the parser picks
            // the layout from the archive header.
            "rgss" ->
                     if (selected.isEmpty()) RgssCore.rgssExtract("", src, out)
                     else RgssCore.rgssExtractSelected("", src, out, selected)
            // MV/MZ loose assets: one obfuscated file per asset, exposed as a
            // one-entry "archive" whose member is the decoded file.
            "rpgmv" ->
                     if (selected.isEmpty()) RgssCore.rgssMvExtract("", src, out)
                     else RgssCore.rgssMvExtractSelected("", src, out, selected)
            "zip" -> { ZipCore.zipSetEncoding(prefs.getString("zip_encoding", "UTF-8") ?: "UTF-8"); zipExtractDispatch(src, out, selected, password) }
            "7z" -> szExtractDispatch(src, out, selected, password)
            "nsa" -> if (selected.isEmpty()) NsaCore.nsaExtract("", src, out)
                     else NsaCore.nsaExtractSelected("", src, out, selected)
            "rpa" -> if (selected.isEmpty()) RpaCore.rpaExtract("", src, out)
                     else RpaCore.rpaExtractSelected("", src, out, selected)
            "rar" -> rarExtractDispatch(src, out, selected, password)
            "lz4" -> Lz4Core.lz4Extract("", src, out)
            "gz" -> GzipCore.gzExtract("", src, out)
            "bz2" -> Bzip2Core.bz2Extract("", src, out)
            "xz" -> XzCore.xzExtract("", src, out)
            "zst" -> ZstdCore.zstExtract("", src, out)
            "lzma" -> LzmaCore.lzmaExtract("", src, out)
            "ksd" -> KsdCore.ksdExtract("", src, out)
            "br" -> BrotliCore.brotliExtract("", src, out)
            "tar" -> if (selected.isEmpty()) TarCore.tarExtract("", src, out)
                     else TarCore.tarExtractSelected("", src, out, selected)
            else -> null
        }
        ExtractOutcome(ExtractCounts.fromJson(json), null)
    } catch (e: Exception) {
        ExtractOutcome(ExtractCounts(0, 0, 0), e.message)
    }
}

/**
 * Synchronously asks for a password on the UI thread and blocks the caller
 * (a background thread) until the user confirms or cancels. Returns the
 * entered password, or null when cancelled / the activity is gone / timed
 * out. Never called on the main thread.
 */
fun promptPasswordSync(activity: AppCompatActivity, message: String = ""): String? {
    val latch = java.util.concurrent.CountDownLatch(1)
    val holder = arrayOf<String?>(null)
    activity.runOnUiThread {
        // The activity may be finishing (e.g. rotation/back): showing a
        // dialog on a dead window throws BadTokenException and would leave
        // the caller blocked forever — count down and bail out instead.
        if (activity.isFinishing || activity.isDestroyed) {
            latch.countDown()
            return@runOnUiThread
        }
        try {
            val inp = EditText(activity).apply {
                hint = activity.getString(R.string.prompt_password)
                setTextColor(C["primary"]!!); setHintTextColor(C["hint"]!!)
                setBackgroundColor(C["surface"]!!); setPadding(12, 8, 12, 8)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val dlg = AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.title_password))
                .setView(inp)
                .setPositiveButton(activity.getString(R.string.action_confirm)) { _, _ -> holder[0] = inp.text.toString(); latch.countDown() }
                .setNegativeButton(activity.getString(R.string.action_cancel)) { _, _ -> latch.countDown() }
                .create()
            if (message.isNotEmpty()) dlg.setMessage(message)
            dlg.show()
            // `also` keeps the latch release on dismiss while the helper also
            // re-arms the pager (a dismiss listener set here would clobber it).
            dlg.keepTabsTappable { latch.countDown() }
        } catch (_: Exception) {
            // BadTokenException / WindowManager errors: never leave the
            // caller blocked on the latch.
            latch.countDown()
        }
    }
    // 30s ceiling so a wedged UI can't block the worker thread forever.
    val finished = try {
        latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
    } catch (_: InterruptedException) {
        false
    }
    if (!finished) return null
    return holder[0]
}

fun extractProgressMessage(activity: AppCompatActivity, name: String, bytes: Long, total: Long): String =
    activity.getString(R.string.msg_extracting_file, name) + " — ${fmt(bytes)} / ${fmt(total)}"

fun compressProgressMessage(activity: AppCompatActivity, name: String, bytes: Long, total: Long): String =
    activity.getString(R.string.msg_compressing_file, name) + " — ${fmt(bytes)} / ${fmt(total)}"

fun showPasswordDialog(
    activity: AppCompatActivity,
    fmt: String, src: String, out: String,
    sel: String = "",
    showProgress: Boolean = true,
    onCancel: () -> Unit = {},
    onResult: (ExtractOutcome) -> Unit,
    ownerTab: TabState? = null
) {
    val inp = EditText(activity).apply {
        hint = activity.getString(R.string.prompt_password)
        setTextColor(C["primary"]!!); setHintTextColor(C["hint"]!!)
        setBackgroundColor(C["surface"]!!); setPadding(12, 8, 12, 8)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    AlertDialog.Builder(activity)
        .setTitle(activity.getString(R.string.title_password))
        .setView(inp)
        .setPositiveButton(activity.getString(R.string.retry)) { _, _ ->
            // Acquire a scheduler slot BEFORE showing the progress dialog —
            // queued ops surface their position/ETA in that dialog.
            val opH = tryStartOperation(activity, fmt)
            val pwd = inp.text.toString()
            var cancelled = false
            val accessors = extractAccessors(fmt)
            val prog = if (showProgress) PollingProgressDialog(
                activity,
                activity.getString(R.string.extracting_please),
                accessors,
                { n, b, t -> extractProgressMessage(activity, n, b, t) },
                activity.getString(R.string.action_cancel),
                { cancelled = true; accessors.cancel() },
                opH,
                ownerTab
            ) else null
            prog?.start()
            thread {
                // Queued ops block here until a slot+format frees up; a cancel
                // while queued aborts silently (nothing was started).
                if (!opH.await()) return@thread
                try {
                    var err: String? = null
                    val json = runCatching {
                        when (fmt) {
                            "zip" -> zipExtractDispatch(src, out, sel, pwd)
                            "7z" -> szExtractDispatch(src, out, sel, pwd)
                            "rar" -> rarExtractDispatch(src, out, sel, pwd)
                            else -> null
                        }
                    }.onFailure { err = it.message }.getOrNull()
                    val outcome = ExtractOutcome(ExtractCounts.fromJson(json), err)
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        prog?.dismiss()
                        if (cancelled) onCancel()
                        else onResult(outcome)
                    }
                } finally {
                    opH.release()
                }
            }
        }
        .setNegativeButton(activity.getString(R.string.action_cancel), null)
        .show().also { it.keepTabsTappable() }
}

fun tryExtractWithPassword(
    activity: AppCompatActivity,
    fmt: String, src: String, out: String, sel: String,
    prefs: SharedPreferences,
    showProgress: Boolean = true,
    initialPassword: String = "",
    onCancel: () -> Unit = {},
    onResult: (ExtractOutcome) -> Unit,
    ownerTab: TabState? = null
) {
    // Acquire a scheduler slot BEFORE showing the progress dialog — a queued
    // op surfaces position/ETA in that dialog instead of being refused.
    val opH = tryStartOperation(activity, fmt)
    var cancelled = false
    val accessors = extractAccessors(fmt)
    val prog = if (showProgress) PollingProgressDialog(
        activity,
        activity.getString(R.string.extracting_please),
        accessors,
        { n, b, t -> extractProgressMessage(activity, n, b, t) },
        activity.getString(R.string.action_cancel),
        { cancelled = true; accessors.cancel() },
        opH,
        ownerTab
    ) else null
    prog?.start()

    fun doExtract(pwd: String = ""): ExtractOutcome {
        return runCatching {
            when (fmt) {
                "zip" -> ExtractOutcome(ExtractCounts.fromJson(zipExtractDispatch(src, out, sel, pwd)), null)
                // `sel` must be forwarded here too, exactly as in the
                // password-retry branch below. Dropping it (as this once did)
                // silently turns a selective extract of an unencrypted 7z/rar
                // into a FULL extract — the user's selection would be ignored
                // with no error at all.
                "7z" -> ExtractOutcome(ExtractCounts.fromJson(szExtractDispatch(src, out, sel, pwd)), null)
                "rar" -> ExtractOutcome(ExtractCounts.fromJson(rarExtractDispatch(src, out, sel, pwd)), null)
                else -> extractByFormat(fmt, src, out, sel, prefs)
            }
        }.getOrElse { e -> ExtractOutcome(ExtractCounts(0, 0, 0), e.message) }
    }
    thread {
        // The handle is released on EVERY exit below — including the password
        // retry dialog, which is shown AFTER release so the retry can enqueue
        // cleanly (an acquire inside the retry while this thread still held
        // the lock was the "already in progress" deadlock).
        if (!opH.await()) return@thread
        var result: ExtractOutcome? = null
        try {
            result = if (fmt in setOf("zip", "7z", "rar") && sel.isNotEmpty()) {
                runCatching {
                    when (fmt) {
                        "zip" -> ExtractOutcome(ExtractCounts.fromJson(zipExtractDispatch(src, out, sel, initialPassword)), null)
                        "7z" -> ExtractOutcome(ExtractCounts.fromJson(szExtractDispatch(src, out, sel, initialPassword)), null)
                        "rar" -> ExtractOutcome(ExtractCounts.fromJson(rarExtractDispatch(src, out, sel, initialPassword)), null)
                        else -> ExtractOutcome(ExtractCounts(0, 0, 0), null)
                    }
                }.getOrElse { e -> ExtractOutcome(ExtractCounts(0, 0, 0), e.message) }
            } else doExtract(initialPassword)
        } finally {
            opH.release()
        }
        val finalResult = result
        activity.runOnUiThread {
            // Activity died while the extraction ran (rotate/back) — showing
            // dialogs on a dead window token would crash (BadTokenException).
            // Guard FIRST, dismiss after (project invariant).
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            prog?.dismiss()
            if (cancelled) { onCancel(); return@runOnUiThread }
            val ok = finalResult?.counts?.ok ?: false
            if (ok) { onResult(finalResult!!) }
            else if (fmt in setOf("zip", "7z", "rar")) {
                // The lock is free now — the retry acquires it like any fresh
                // operation, so it can never report "operation in progress".
                val inp = EditText(activity).apply {
                    hint = activity.getString(R.string.prompt_password)
                    setTextColor(C["primary"]!!); setHintTextColor(C["hint"]!!)
                    setBackgroundColor(C["surface"]!!); setPadding(12, 8, 12, 8)
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.title_password))
                    .setView(inp)
                    .setPositiveButton(activity.getString(R.string.retry)) { _, _ ->
                        val opH2 = tryStartOperation(activity, fmt)
                        val pwd = inp.text.toString()
                        var cancelled2 = false
                        val accessors2 = extractAccessors(fmt)
                        val prog2 = if (showProgress) PollingProgressDialog(
                            activity,
                            activity.getString(R.string.extracting_please),
                            accessors2,
                            { n, b, t -> extractProgressMessage(activity, n, b, t) },
                            activity.getString(R.string.action_cancel),
                            { cancelled2 = true; accessors2.cancel() },
                            opH2,
                            ownerTab
                        ) else null
                        prog2?.start()
                        thread {
                            if (!opH2.await()) return@thread
                            try {
                                val outcome2 = runCatching {
                                    when (fmt) {
                                        "zip" -> ExtractOutcome(ExtractCounts.fromJson(zipExtractDispatch(src, out, sel, pwd)), null)
                                        "7z" -> ExtractOutcome(ExtractCounts.fromJson(szExtractDispatch(src, out, sel, pwd)), null)
                                        "rar" -> ExtractOutcome(ExtractCounts.fromJson(rarExtractDispatch(src, out, sel, pwd)), null)
                                        else -> ExtractOutcome(ExtractCounts(0, 0, 0), null)
                                    }
                                }.getOrElse { e -> ExtractOutcome(ExtractCounts(0, 0, 0), e.message) }
                                activity.runOnUiThread {
                                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                                    prog2?.dismiss()
                                    if (cancelled2) onCancel()
                                    else onResult(outcome2)
                                }
                            } finally {
                                opH2.release()
                            }
                        }
                    }
                    .setNegativeButton(activity.getString(R.string.action_cancel), null)
                    .show().also { it.keepTabsTappable() }
            } else { onResult(finalResult!!) }
        }
    }
}
