package com.usefulunpacker

import android.app.Dialog
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class ProgressAccessors(
    val getCount: () -> Long,
    val getTotal: () -> Long,
    val getFileCount: () -> Long,
    val getFileTotal: () -> Long,
    val getName: () -> String?,
    val cancel: () -> Unit
)

fun extractAccessors(fmt: String): ProgressAccessors = when (fmt) {
    "xp3" -> ProgressAccessors({ Xp3Core.xp3ExtractProgressCount() }, { Xp3Core.xp3ExtractProgressTotal() }, { Xp3Core.xp3ExtractProgressFileCount() }, { Xp3Core.xp3ExtractProgressFileTotal() }, { Xp3Core.xp3ExtractProgressName() }, { Xp3Core.xp3ExtractCancel() })
    "pfs" -> ProgressAccessors({ PfsCore.pfsExtractProgressCount() }, { PfsCore.pfsExtractProgressTotal() }, { PfsCore.pfsExtractProgressFileCount() }, { PfsCore.pfsExtractProgressFileTotal() }, { PfsCore.pfsExtractProgressName() }, { PfsCore.pfsExtractCancel() })
    "nsa" -> ProgressAccessors({ NsaCore.nsaExtractProgressCount() }, { NsaCore.nsaExtractProgressTotal() }, { NsaCore.nsaExtractProgressFileCount() }, { NsaCore.nsaExtractProgressFileTotal() }, { NsaCore.nsaExtractProgressName() }, { NsaCore.nsaExtractCancel() })
    "iso" -> ProgressAccessors({ IsoCore.isoExtractProgressCount() }, { IsoCore.isoExtractProgressTotal() }, { IsoCore.isoExtractProgressFileCount() }, { IsoCore.isoExtractProgressFileTotal() }, { IsoCore.isoExtractProgressName() }, { IsoCore.isoExtractCancel() })
    "ypf" -> ProgressAccessors({ YpfCore.ypfExtractProgressCount() }, { YpfCore.ypfExtractProgressTotal() }, { YpfCore.ypfExtractProgressFileCount() }, { YpfCore.ypfExtractProgressFileTotal() }, { YpfCore.ypfExtractProgressName() }, { YpfCore.ypfExtractCancel() })
    "zip" -> ProgressAccessors({ ZipCore.zipExtractProgressCount() }, { ZipCore.zipExtractProgressTotal() }, { ZipCore.zipExtractProgressFileCount() }, { ZipCore.zipExtractProgressFileTotal() }, { ZipCore.zipExtractProgressName() }, { ZipCore.zipExtractCancel() })
    "7z" -> ProgressAccessors({ SevenZCore.szExtractProgressCount() }, { SevenZCore.szExtractProgressTotal() }, { SevenZCore.szExtractProgressFileCount() }, { SevenZCore.szExtractProgressFileTotal() }, { SevenZCore.szExtractProgressName() }, { SevenZCore.szExtractCancel() })
    "rar" -> ProgressAccessors({ RarCore.rarExtractProgressCount() }, { RarCore.rarExtractProgressTotal() }, { RarCore.rarExtractProgressFileCount() }, { RarCore.rarExtractProgressFileTotal() }, { RarCore.rarExtractProgressName() }, { RarCore.rarExtractCancel() })
    "lz4" -> ProgressAccessors({ Lz4Core.lz4ExtractProgressCount() }, { Lz4Core.lz4ExtractProgressTotal() }, { Lz4Core.lz4ExtractProgressFileCount() }, { Lz4Core.lz4ExtractProgressFileTotal() }, { Lz4Core.lz4ExtractProgressName() }, { Lz4Core.lz4ExtractCancel() })
    "gz" -> ProgressAccessors({ GzipCore.gzExtractProgressCount() }, { GzipCore.gzExtractProgressTotal() }, { GzipCore.gzExtractProgressFileCount() }, { GzipCore.gzExtractProgressFileTotal() }, { GzipCore.gzExtractProgressName() }, { GzipCore.gzExtractCancel() })
    "bz2" -> ProgressAccessors({ Bzip2Core.bz2ExtractProgressCount() }, { Bzip2Core.bz2ExtractProgressTotal() }, { Bzip2Core.bz2ExtractProgressFileCount() }, { Bzip2Core.bz2ExtractProgressFileTotal() }, { Bzip2Core.bz2ExtractProgressName() }, { Bzip2Core.bz2ExtractCancel() })
    "xz" -> ProgressAccessors({ XzCore.xzExtractProgressCount() }, { XzCore.xzExtractProgressTotal() }, { XzCore.xzExtractProgressFileCount() }, { XzCore.xzExtractProgressFileTotal() }, { XzCore.xzExtractProgressName() }, { XzCore.xzExtractCancel() })
    "zst" -> ProgressAccessors({ ZstdCore.zstExtractProgressCount() }, { ZstdCore.zstExtractProgressTotal() }, { ZstdCore.zstExtractProgressFileCount() }, { ZstdCore.zstExtractProgressFileTotal() }, { ZstdCore.zstExtractProgressName() }, { ZstdCore.zstExtractCancel() })
    "lzma" -> ProgressAccessors({ LzmaCore.lzmaExtractProgressCount() }, { LzmaCore.lzmaExtractProgressTotal() }, { LzmaCore.lzmaExtractProgressFileCount() }, { LzmaCore.lzmaExtractProgressFileTotal() }, { LzmaCore.lzmaExtractProgressName() }, { LzmaCore.lzmaExtractCancel() })
    "tar" -> ProgressAccessors({ TarCore.tarExtractProgressCount() }, { TarCore.tarExtractProgressTotal() }, { TarCore.tarExtractProgressFileCount() }, { TarCore.tarExtractProgressFileTotal() }, { TarCore.tarExtractProgressName() }, { TarCore.tarExtractCancel() })
    else -> throw IllegalArgumentException("unsupported extract format: $fmt")
}

fun compressAccessors(fmt: String): ProgressAccessors = when (fmt) {
    "zip" -> ProgressAccessors({ ZipCore.zipCompressProgressCount() }, { ZipCore.zipCompressProgressTotal() }, { ZipCore.zipCompressProgressFileCount() }, { ZipCore.zipCompressProgressFileTotal() }, { ZipCore.zipCompressProgressName() }, { ZipCore.zipCompressCancel() })
    "7z" -> ProgressAccessors({ SevenZCore.szCompressProgressCount() }, { SevenZCore.szCompressProgressTotal() }, { SevenZCore.szCompressProgressFileCount() }, { SevenZCore.szCompressProgressFileTotal() }, { SevenZCore.szCompressProgressName() }, { SevenZCore.szCompressCancel() })
    "gz" -> ProgressAccessors({ GzipCore.gzCompressProgressCount() }, { GzipCore.gzCompressProgressTotal() }, { GzipCore.gzCompressProgressFileCount() }, { GzipCore.gzCompressProgressFileTotal() }, { GzipCore.gzCompressProgressName() }, { GzipCore.gzCompressCancel() })
    "bz2" -> ProgressAccessors({ Bzip2Core.bz2CompressProgressCount() }, { Bzip2Core.bz2CompressProgressTotal() }, { Bzip2Core.bz2CompressProgressFileCount() }, { Bzip2Core.bz2CompressProgressFileTotal() }, { Bzip2Core.bz2CompressProgressName() }, { Bzip2Core.bz2CompressCancel() })
    "xz" -> ProgressAccessors({ XzCore.xzCompressProgressCount() }, { XzCore.xzCompressProgressTotal() }, { XzCore.xzCompressProgressFileCount() }, { XzCore.xzCompressProgressFileTotal() }, { XzCore.xzCompressProgressName() }, { XzCore.xzCompressCancel() })
    "zst" -> ProgressAccessors({ ZstdCore.zstCompressProgressCount() }, { ZstdCore.zstCompressProgressTotal() }, { ZstdCore.zstCompressProgressFileCount() }, { ZstdCore.zstCompressProgressFileTotal() }, { ZstdCore.zstCompressProgressName() }, { ZstdCore.zstCompressCancel() })
    "lzma" -> ProgressAccessors({ LzmaCore.lzmaCompressProgressCount() }, { LzmaCore.lzmaCompressProgressTotal() }, { LzmaCore.lzmaCompressProgressFileCount() }, { LzmaCore.lzmaCompressProgressFileTotal() }, { LzmaCore.lzmaCompressProgressName() }, { LzmaCore.lzmaCompressCancel() })
    "lz4" -> ProgressAccessors({ Lz4Core.lz4CompressProgressCount() }, { Lz4Core.lz4CompressProgressTotal() }, { Lz4Core.lz4CompressProgressFileCount() }, { Lz4Core.lz4CompressProgressFileTotal() }, { Lz4Core.lz4CompressProgressName() }, { Lz4Core.lz4CompressCancel() })
    "tar", "tgz", "tbz2", "txz", "tzst" -> ProgressAccessors({ TarCore.tarCompressProgressCount() }, { TarCore.tarCompressProgressTotal() }, { TarCore.tarCompressProgressFileCount() }, { TarCore.tarCompressProgressFileTotal() }, { TarCore.tarCompressProgressName() }, { TarCore.tarCompressCancel() })
    else -> throw IllegalArgumentException("unsupported compress format: $fmt")
}

/**
 * Dual progress dialog driven by polling the per-format JNI statics,
 * shared by both extraction and compression flows.
 *
 * Top bar = overall (全量) progress; bottom bar = current file progress,
 * shown only when the current member is large enough (or indeterminate when
 * its size is unknown) to avoid flicker on many small files.
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
    private var dialog: Dialog? = null
    private lateinit var msgText: TextView
    private lateinit var overallBar: ProgressBar
    private lateinit var overallText: TextView
    private lateinit var fileBar: ProgressBar
    private lateinit var fileText: TextView
    private val titleText = title

    fun start() {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_progress_dual, null)
        msgText = view.findViewById(R.id.progress_msg)
        overallBar = view.findViewById(R.id.progress_overall)
        overallText = view.findViewById(R.id.progress_overall_text)
        fileBar = view.findViewById(R.id.progress_file)
        fileText = view.findViewById(R.id.progress_file_text)
        msgText.text = titleText

        val d = Dialog(activity, R.style.Theme_UsefulUnpack_Dialog)
        d.setContentView(view)
        d.setCancelable(true)
        d.setOnCancelListener { stopped = true; onCancel() }
        if (cancelLabel != null) {
            val cancelBtn = view.findViewById<Button>(R.id.progress_cancel)
            cancelBtn.visibility = View.VISIBLE
            cancelBtn.text = cancelLabel
            cancelBtn.setOnClickListener { stopped = true; onCancel() }
        }
        dialog = d
        d.show()

        thread {
            var last = 0L
            while (!stopped) {
                Thread.sleep(200)
                val cur = accessors.getCount()
                val tot = accessors.getTotal()
                val fc = accessors.getFileCount()
                val ft = accessors.getFileTotal()
                val name = accessors.getName() ?: ""
                if (cur < last) last = 0
                if (cur != last) {
                    last = cur
                    activity.runOnUiThread {
                        val dlg = dialog
                        if (dlg == null || !dlg.isShowing) return@runOnUiThread
                        overallBar.max = 100
                        overallBar.isIndeterminate = tot <= 0
                        overallBar.progress = if (tot > 0) (cur * 100 / tot).coerceAtMost(100).toInt() else 0
                        overallText.text = if (tot > 0) "${fmt(cur)} / ${fmt(tot)}" else fmt(cur)
                        msgText.text = if (name.isNotEmpty()) messageFn(name, cur, tot) else titleText
                        updateFileBar(fc, ft, name)
                    }
                }
            }
        }
    }

    private fun updateFileBar(fc: Long, ft: Long, name: String) {
        if (ft >= PROGRESS_FILE_BAR_MIN) {
            fileBar.visibility = View.VISIBLE
            fileText.visibility = View.VISIBLE
            fileBar.isIndeterminate = false
            fileBar.max = 100
            fileBar.progress = (fc * 100 / ft).coerceAtMost(100).toInt()
            fileText.text = "${fmt(fc)} / ${fmt(ft)}"
        } else if (ft > 0) {
            // small current file — hide the bottom bar to avoid flicker
            fileBar.visibility = View.GONE
            fileText.visibility = View.GONE
        } else {
            // unknown size — show an indeterminate spinner for the current file
            fileBar.visibility = View.VISIBLE
            fileText.visibility = View.VISIBLE
            fileBar.isIndeterminate = true
            fileText.text = name.takeLast(40)
        }
    }

    fun dismiss() {
        stopped = true
        val d = dialog
        if (d != null && d.isShowing) d.dismiss()
    }
}
