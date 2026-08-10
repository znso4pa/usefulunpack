package com.usefulunpacker

import android.app.AlertDialog
import android.content.SharedPreferences
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread
import java.io.File

@Volatile var lastExtractResult = ExtractCounts(0, 0, 0)
@Volatile var lastExtractError: String? = null

/**
 * Single-operation lock. The app drives one long-running extract/compress at a
 * time; concurrent operations would clobber the shared per-format progress
 * statics and [lastExtractResult]. Worker threads call [acquire] before the JNI
 * work and [release] in a `finally`, so each worker is a distinct lock holder.
 */
object OperationLock {
    @Volatile private var busy = false
    @Volatile private var holder: Thread? = null

    fun acquire(): Boolean = synchronized(this) {
        if (busy) {
            if (holder == Thread.currentThread()) true else false
        } else {
            busy = true; holder = Thread.currentThread(); true
        }
    }

    fun release() = synchronized(this) {
        if (holder == Thread.currentThread()) {
            busy = false; holder = null
        }
    }
}

/** Acquires the operation lock or toasts "busy" and returns false. */
fun tryStartOperation(activity: AppCompatActivity): Boolean {
    if (!OperationLock.acquire()) {
        Toast.makeText(activity, activity.getString(R.string.msg_op_in_progress), Toast.LENGTH_SHORT).show()
        return false
    }
    return true
}

/** Maps the raw JNI error message to a user-facing string. */
fun friendlyExtractError(activity: AppCompatActivity): String {
    val msg = lastExtractError
    if (msg.isNullOrEmpty()) return activity.getString(R.string.title_extract_failed)
    val m = msg.lowercase()
    return when {
        m.contains("wrong password") || m.contains("bad password") || m.contains("password required") ||
            m.contains("maybe bad password") || m.contains("checksum verification failed") ->
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

fun szExtractDispatch(src: String, out: String, sel: String, pw: String): String? {
    val vols = resolveSevenZVolumes(File(src))
    return if (vols.size > 1) {
        val joined = volumeJoin(vols)
        when {
            sel.isNotEmpty() -> SevenZCore.szExtractSelectedVolumes("", joined, out, sel)
            pw.isNotEmpty() -> SevenZCore.szExtractVolumesWithPassword("", joined, out, pw)
            else -> SevenZCore.szExtractVolumes("", joined, out)
        }
    } else {
        when {
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

fun rarVolumeList(src: String): String = volumeJoin(resolveRarVolumes(File(src)))
fun szVolumeList(src: String): String = volumeJoin(resolveSevenZVolumes(File(src)))

fun extractByFormat(
    format: String, src: String, out: String, selected: String,
    prefs: SharedPreferences
): Boolean {
    return try {
        val json = when (format) {
            "xp3" -> if (selected.isEmpty()) Xp3Core.xp3Extract("", src, out)
                     else Xp3Core.xp3ExtractSelected("", src, out, selected)
            "pfs" -> if (selected.isEmpty()) PfsCore.pfsExtract("", src, out)
                     else PfsCore.pfsExtractSelected("", src, out, selected)
            "iso" -> if (selected.isEmpty()) IsoCore.isoExtract("", src, out)
                     else IsoCore.isoExtractSelected("", src, out, selected)
            "ypf" -> if (selected.isEmpty()) YpfCore.ypfExtract("", src, out)
                     else YpfCore.ypfExtractSelected("", src, out, selected)
            "zip" -> { ZipCore.zipSetEncoding(prefs.getString("zip_encoding", "UTF-8") ?: "UTF-8"); if (selected.isEmpty()) ZipCore.zipExtract("", src, out) else ZipCore.zipExtractSelected("", src, out, selected) }
            "7z" -> szExtractDispatch(src, out, selected, "")
            "nsa" -> if (selected.isEmpty()) NsaCore.nsaExtract("", src, out)
                     else NsaCore.nsaExtractSelected("", src, out, selected)
            "rar" -> rarExtractDispatch(src, out, selected, "")
            "lz4" -> Lz4Core.lz4Extract("", src, out)
            "gz" -> GzipCore.gzExtract("", src, out)
            "bz2" -> Bzip2Core.bz2Extract("", src, out)
            "xz" -> XzCore.xzExtract("", src, out)
            "zst" -> ZstdCore.zstExtract("", src, out)
            "lzma" -> LzmaCore.lzmaExtract("", src, out)
            "ksd" -> KsdCore.ksdExtract("", src, out)
            "tar" -> if (selected.isEmpty()) TarCore.tarExtract("", src, out)
                     else TarCore.tarExtractSelected("", src, out, selected)
            else -> null
        }
        val r = ExtractCounts.fromJson(json)
        lastExtractResult = r
        lastExtractError = null
        r.success > 0 && r.error == 0
    } catch (e: Exception) {
        lastExtractResult = ExtractCounts(0, 0, 0)
        lastExtractError = e.message
        false
    }
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
    onResult: (Boolean) -> Unit
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
            val pwd = inp.text.toString()
            var cancelled = false
            val accessors = extractAccessors(fmt)
            val prog = if (showProgress) PollingProgressDialog(
                activity,
                activity.getString(R.string.extracting_please),
                accessors,
                { n, b, t -> extractProgressMessage(activity, n, b, t) },
                activity.getString(R.string.action_cancel),
                { cancelled = true; accessors.cancel() }
            ) else null
            prog?.start()
            thread {
                if (!tryStartOperation(activity)) return@thread
                try {
                    val json = runCatching {
                        when (fmt) {
                            "zip" -> ZipCore.zipExtractWithPassword("", src, out, pwd)
                            "7z" -> szExtractDispatch(src, out, "", pwd)
                            "rar" -> rarExtractDispatch(src, out, sel, pwd)
                            else -> null
                        }
                    }.onFailure { lastExtractError = it.message }.getOrNull()
                    val r = ExtractCounts.fromJson(json); val ok = r.success > 0 && r.error == 0
                    activity.runOnUiThread {
                        prog?.dismiss()
                        if (cancelled) onCancel()
                        else onResult(ok)
                    }
                } finally {
                    OperationLock.release()
                }
            }
        }
        .setNegativeButton(activity.getString(R.string.action_cancel), null)
        .show()
}

fun tryExtractWithPassword(
    activity: AppCompatActivity,
    fmt: String, src: String, out: String, sel: String,
    prefs: SharedPreferences,
    showProgress: Boolean = true,
    onCancel: () -> Unit = {},
    onResult: (ExtractCounts) -> Unit
) {
    var cancelled = false
    val accessors = extractAccessors(fmt)
    val prog = if (showProgress) PollingProgressDialog(
        activity,
        activity.getString(R.string.extracting_please),
        accessors,
        { n, b, t -> extractProgressMessage(activity, n, b, t) },
        activity.getString(R.string.action_cancel),
        { cancelled = true; accessors.cancel() }
    ) else null
    prog?.start()

    fun doExtract(pwd: String = ""): ExtractCounts {
        lastExtractResult = ExtractCounts(0, 0, 0)
        lastExtractError = null
        val json = runCatching {
            when (fmt) {
                "zip" -> if (pwd.isEmpty()) ZipCore.zipExtract("", src, out) else ZipCore.zipExtractWithPassword("", src, out, pwd)
                "7z" -> szExtractDispatch(src, out, "", pwd)
                "rar" -> rarExtractDispatch(src, out, "", pwd)
                else -> { extractByFormat(fmt, src, out, sel, prefs); null }
            }
        }.onFailure { lastExtractError = it.message }.getOrNull()
        return if (json != null) ExtractCounts.fromJson(json) else lastExtractResult
    }
    thread {
        if (!tryStartOperation(activity)) return@thread
        try {
            val result = if (fmt in setOf("zip", "7z", "rar") && sel.isNotEmpty()) {
                val json = runCatching {
                    when (fmt) {
                        "zip" -> ZipCore.zipExtractSelected("", src, out, sel)
                        "7z" -> szExtractDispatch(src, out, sel, "")
                        "rar" -> rarExtractDispatch(src, out, sel, "")
                        else -> null
                    }
                }.onFailure { lastExtractError = it.message }.getOrNull()
                ExtractCounts.fromJson(json)
            } else doExtract()
            val ok = result.success > 0 && result.error == 0
            activity.runOnUiThread {
                prog?.dismiss()
                if (cancelled) { onCancel(); return@runOnUiThread }
                if (ok) { onResult(result) }
                else if (fmt in setOf("zip", "7z", "rar")) {
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
                            val pwd = inp.text.toString()
                            var cancelled2 = false
                            val accessors2 = extractAccessors(fmt)
                            val prog2 = if (showProgress) PollingProgressDialog(
                                activity,
                                activity.getString(R.string.extracting_please),
                                accessors2,
                                { n, b, t -> extractProgressMessage(activity, n, b, t) },
                                activity.getString(R.string.action_cancel),
                                { cancelled2 = true; accessors2.cancel() }
                            ) else null
                            prog2?.start()
                            thread {
                                if (!tryStartOperation(activity)) return@thread
                                try {
                                    val json2 = runCatching {
                                        when (fmt) {
                                            "zip" -> ZipCore.zipExtractWithPassword("", src, out, pwd)
                                            "7z" -> szExtractDispatch(src, out, sel, pwd)
                                            "rar" -> rarExtractDispatch(src, out, sel, pwd)
                                            else -> null
                                        }
                                    }.onFailure { lastExtractError = it.message }.getOrNull()
                                    val r2 = ExtractCounts.fromJson(json2)
                                    activity.runOnUiThread {
                                        prog2?.dismiss()
                                        if (cancelled2) onCancel()
                                        else onResult(r2)
                                    }
                                } finally {
                                    OperationLock.release()
                                }
                            }
                        }
                        .setNegativeButton(activity.getString(R.string.action_cancel), null)
                        .show()
                } else { onResult(result) }
            }
        } finally {
            OperationLock.release()
        }
    }
}
