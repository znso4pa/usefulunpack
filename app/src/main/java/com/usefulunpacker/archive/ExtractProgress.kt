package com.usefulunpacker

import android.app.ProgressDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class ProgressAccessors(
    val getCount: () -> Long,
    val getTotal: () -> Long,
    val getName: () -> String?,
    val cancel: () -> Unit
)

fun extractAccessors(fmt: String): ProgressAccessors = when (fmt) {
    "xp3" -> ProgressAccessors({ Xp3Core.xp3ExtractProgressCount() }, { Xp3Core.xp3ExtractProgressTotal() }, { Xp3Core.xp3ExtractProgressName() }, { Xp3Core.xp3ExtractCancel() })
    "pfs" -> ProgressAccessors({ PfsCore.pfsExtractProgressCount() }, { PfsCore.pfsExtractProgressTotal() }, { PfsCore.pfsExtractProgressName() }, { PfsCore.pfsExtractCancel() })
    "nsa" -> ProgressAccessors({ NsaCore.nsaExtractProgressCount() }, { NsaCore.nsaExtractProgressTotal() }, { NsaCore.nsaExtractProgressName() }, { NsaCore.nsaExtractCancel() })
    "iso" -> ProgressAccessors({ IsoCore.isoExtractProgressCount() }, { IsoCore.isoExtractProgressTotal() }, { IsoCore.isoExtractProgressName() }, { IsoCore.isoExtractCancel() })
    "ypf" -> ProgressAccessors({ YpfCore.ypfExtractProgressCount() }, { YpfCore.ypfExtractProgressTotal() }, { YpfCore.ypfExtractProgressName() }, { YpfCore.ypfExtractCancel() })
    "zip" -> ProgressAccessors({ ZipCore.zipExtractProgressCount() }, { ZipCore.zipExtractProgressTotal() }, { ZipCore.zipExtractProgressName() }, { ZipCore.zipExtractCancel() })
    "7z" -> ProgressAccessors({ SevenZCore.szExtractProgressCount() }, { SevenZCore.szExtractProgressTotal() }, { SevenZCore.szExtractProgressName() }, { SevenZCore.szExtractCancel() })
    "rar" -> ProgressAccessors({ RarCore.rarExtractProgressCount() }, { RarCore.rarExtractProgressTotal() }, { RarCore.rarExtractProgressName() }, { RarCore.rarExtractCancel() })
    "lz4" -> ProgressAccessors({ Lz4Core.lz4ExtractProgressCount() }, { Lz4Core.lz4ExtractProgressTotal() }, { Lz4Core.lz4ExtractProgressName() }, { Lz4Core.lz4ExtractCancel() })
    else -> throw IllegalArgumentException("unsupported extract format: $fmt")
}

fun compressAccessors(fmt: String): ProgressAccessors = when (fmt) {
    "zip" -> ProgressAccessors({ ZipCore.zipCompressProgressCount() }, { ZipCore.zipCompressProgressTotal() }, { ZipCore.zipCompressProgressName() }, { ZipCore.zipCompressCancel() })
    "7z" -> ProgressAccessors({ SevenZCore.szCompressProgressCount() }, { SevenZCore.szCompressProgressTotal() }, { SevenZCore.szCompressProgressName() }, { SevenZCore.szCompressCancel() })
    else -> throw IllegalArgumentException("unsupported compress format: $fmt")
}

/**
 * Horizontal progress dialog driven by polling the per-format JNI statics,
 * shared by both extraction and compression flows.
 *
 * `onCancel` is invoked when the user taps cancel (the dialog stays up until
 * the worker thread finishes and calls [dismiss]).
 */
class PollingProgressDialog(
    private val activity: AppCompatActivity,
    title: String,
    private val accessors: ProgressAccessors,
    private val messageFn: (name: String, bytes: Long, total: Long) -> String,
    private val cancelLabel: String? = null,
    private val onCancel: () -> Unit = {}
) {
    private var stopped = false
    private val pd = ProgressDialog(activity).apply {
        setTitle(title)
        setMessage(" ")
        setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        max = 100
        setCancelable(true)
    }

    fun start() {
        if (cancelLabel != null) {
            pd.setButton(ProgressDialog.BUTTON_NEGATIVE, cancelLabel) { _, _ ->
                stopped = true
                onCancel()
            }
        }
        pd.show()
        thread {
            var last = 0L
            while (!stopped) {
                Thread.sleep(200)
                val cur = accessors.getCount()
                val tot = accessors.getTotal()
                val name = accessors.getName() ?: ""
                if (cur < last) last = 0
                if (cur != last) {
                    last = cur
                    val pct = if (tot > 0) (cur * 100 / tot).coerceAtMost(100).toInt() else 0
                    val msg = if (name.isNotEmpty()) messageFn(name.takeLast(40), cur, tot) else " "
                    activity.runOnUiThread {
                        pd.progress = pct
                        pd.setMessage(msg)
                    }
                }
            }
        }
    }

    fun dismiss() {
        stopped = true
        if (pd.isShowing) pd.dismiss()
    }
}
