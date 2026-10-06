package com.usefulunpacker

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.usefulunpacker.archive.OpScheduler
import com.usefulunpacker.archive.etaLabel
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
    "rpa" -> ProgressAccessors({ RpaCore.rpaExtractProgressCount() }, { RpaCore.rpaExtractProgressTotal() }, { RpaCore.rpaExtractProgressFileCount() }, { RpaCore.rpaExtractProgressFileTotal() }, { RpaCore.rpaExtractProgressName() }, { RpaCore.rpaExtractCancel() })
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
    "ksd" -> ProgressAccessors({ KsdCore.ksdExtractProgressCount() }, { KsdCore.ksdExtractProgressTotal() }, { KsdCore.ksdExtractProgressFileCount() }, { KsdCore.ksdExtractProgressFileTotal() }, { KsdCore.ksdExtractProgressName() }, { KsdCore.ksdExtractCancel() })
    "br" -> ProgressAccessors({ BrotliCore.brotliExtractProgressCount() }, { BrotliCore.brotliExtractProgressTotal() }, { BrotliCore.brotliExtractProgressFileCount() }, { BrotliCore.brotliExtractProgressFileTotal() }, { BrotliCore.brotliExtractProgressName() }, { BrotliCore.brotliExtractCancel() })
    "tar" -> ProgressAccessors({ TarCore.tarExtractProgressCount() }, { TarCore.tarExtractProgressTotal() }, { TarCore.tarExtractProgressFileCount() }, { TarCore.tarExtractProgressFileTotal() }, { TarCore.tarExtractProgressName() }, { TarCore.tarExtractCancel() })
    "rpgmv" -> ProgressAccessors({ RgssCore.rgssMvProgressCount() }, { RgssCore.rgssMvProgressTotal() }, { RgssCore.rgssMvProgressFileCount() }, { RgssCore.rgssMvProgressFileTotal() }, { RgssCore.rgssMvProgressName() }, { RgssCore.rgssMvCancel() })
    "rgss" -> ProgressAccessors({ RgssCore.rgssExtractProgressCount() }, { RgssCore.rgssExtractProgressTotal() }, { RgssCore.rgssExtractProgressFileCount() }, { RgssCore.rgssExtractProgressFileTotal() }, { RgssCore.rgssExtractProgressName() }, { RgssCore.rgssExtractCancel() })
    else -> throw IllegalArgumentException("unsupported extract format: $fmt")
}

fun compressAccessors(fmt: String): ProgressAccessors = when (fmt) {
    "xp3" -> ProgressAccessors({ Xp3Core.xp3CompressProgressCount() }, { Xp3Core.xp3CompressProgressTotal() }, { Xp3Core.xp3CompressProgressFileCount() }, { Xp3Core.xp3CompressProgressFileTotal() }, { Xp3Core.xp3CompressProgressName() }, { Xp3Core.xp3CompressCancel() })
    "pfs" -> ProgressAccessors({ PfsCore.pfsCompressProgressCount() }, { PfsCore.pfsCompressProgressTotal() }, { PfsCore.pfsCompressProgressFileCount() }, { PfsCore.pfsCompressProgressFileTotal() }, { PfsCore.pfsCompressProgressName() }, { PfsCore.pfsCompressCancel() })
    "pf6" -> ProgressAccessors({ PfsCore.pfsCompressProgressCount() }, { PfsCore.pfsCompressProgressTotal() }, { PfsCore.pfsCompressProgressFileCount() }, { PfsCore.pfsCompressProgressFileTotal() }, { PfsCore.pfsCompressProgressName() }, { PfsCore.pfsCompressCancel() })
    "nsa" -> ProgressAccessors({ NsaCore.nsaCompressProgressCount() }, { NsaCore.nsaCompressProgressTotal() }, { NsaCore.nsaCompressProgressFileCount() }, { NsaCore.nsaCompressProgressFileTotal() }, { NsaCore.nsaCompressProgressName() }, { NsaCore.nsaCompressCancel() })
    "rpa" -> ProgressAccessors({ RpaCore.rpaCompressProgressCount() }, { RpaCore.rpaCompressProgressTotal() }, { RpaCore.rpaCompressProgressFileCount() }, { RpaCore.rpaCompressProgressFileTotal() }, { RpaCore.rpaCompressProgressName() }, { RpaCore.rpaCompressCancel() })
    "iso" -> ProgressAccessors({ IsoCore.isoCompressProgressCount() }, { IsoCore.isoCompressProgressTotal() }, { IsoCore.isoCompressProgressFileCount() }, { IsoCore.isoCompressProgressFileTotal() }, { IsoCore.isoCompressProgressName() }, { IsoCore.isoCompressCancel() })
    "zip" -> ProgressAccessors({ ZipCore.zipCompressProgressCount() }, { ZipCore.zipCompressProgressTotal() }, { ZipCore.zipCompressProgressFileCount() }, { ZipCore.zipCompressProgressFileTotal() }, { ZipCore.zipCompressProgressName() }, { ZipCore.zipCompressCancel() })
    "7z" -> ProgressAccessors({ SevenZCore.szCompressProgressCount() }, { SevenZCore.szCompressProgressTotal() }, { SevenZCore.szCompressProgressFileCount() }, { SevenZCore.szCompressProgressFileTotal() }, { SevenZCore.szCompressProgressName() }, { SevenZCore.szCompressCancel() })
    "gz" -> ProgressAccessors({ GzipCore.gzCompressProgressCount() }, { GzipCore.gzCompressProgressTotal() }, { GzipCore.gzCompressProgressFileCount() }, { GzipCore.gzCompressProgressFileTotal() }, { GzipCore.gzCompressProgressName() }, { GzipCore.gzCompressCancel() })
    "bz2" -> ProgressAccessors({ Bzip2Core.bz2CompressProgressCount() }, { Bzip2Core.bz2CompressProgressTotal() }, { Bzip2Core.bz2CompressProgressFileCount() }, { Bzip2Core.bz2CompressProgressFileTotal() }, { Bzip2Core.bz2CompressProgressName() }, { Bzip2Core.bz2CompressCancel() })
    "xz" -> ProgressAccessors({ XzCore.xzCompressProgressCount() }, { XzCore.xzCompressProgressTotal() }, { XzCore.xzCompressProgressFileCount() }, { XzCore.xzCompressProgressFileTotal() }, { XzCore.xzCompressProgressName() }, { XzCore.xzCompressCancel() })
    "zst" -> ProgressAccessors({ ZstdCore.zstCompressProgressCount() }, { ZstdCore.zstCompressProgressTotal() }, { ZstdCore.zstCompressProgressFileCount() }, { ZstdCore.zstCompressProgressFileTotal() }, { ZstdCore.zstCompressProgressName() }, { ZstdCore.zstCompressCancel() })
    "lzma" -> ProgressAccessors({ LzmaCore.lzmaCompressProgressCount() }, { LzmaCore.lzmaCompressProgressTotal() }, { LzmaCore.lzmaCompressProgressFileCount() }, { LzmaCore.lzmaCompressProgressFileTotal() }, { LzmaCore.lzmaCompressProgressName() }, { LzmaCore.lzmaCompressCancel() })
    "lz4" -> ProgressAccessors({ Lz4Core.lz4CompressProgressCount() }, { Lz4Core.lz4CompressProgressTotal() }, { Lz4Core.lz4CompressProgressFileCount() }, { Lz4Core.lz4CompressProgressFileTotal() }, { Lz4Core.lz4CompressProgressName() }, { Lz4Core.lz4CompressCancel() })
    "ksd" -> ProgressAccessors({ KsdCore.ksdCompressProgressCount() }, { KsdCore.ksdCompressProgressTotal() }, { KsdCore.ksdCompressProgressFileCount() }, { KsdCore.ksdCompressProgressFileTotal() }, { KsdCore.ksdCompressProgressName() }, { KsdCore.ksdCompressCancel() })
    "br" -> ProgressAccessors({ BrotliCore.brotliCompressProgressCount() }, { BrotliCore.brotliCompressProgressTotal() }, { BrotliCore.brotliCompressProgressFileCount() }, { BrotliCore.brotliCompressProgressFileTotal() }, { BrotliCore.brotliCompressProgressName() }, { BrotliCore.brotliCompressCancel() })
    "ypf" -> ProgressAccessors({ YpfCore.ypfCompressProgressCount() }, { YpfCore.ypfCompressProgressTotal() }, { YpfCore.ypfCompressProgressFileCount() }, { YpfCore.ypfCompressProgressFileTotal() }, { YpfCore.ypfCompressProgressName() }, { YpfCore.ypfCompressCancel() })
    // "rgss" is the READ key and belongs here too: repackEditedArchive builds
    // its progress card from the archive's read key, not from a picker choice.
    // Omitting it is the same class of bug as the old missing "pf6" branch —
    // an IllegalArgumentException before the card is even shown, so the repack
    // dies silently.
    "rgss", "rgssad", "rgss2a", "rgss3a", "rpgmvp", "rpgmvo", "rpgmvm" -> ProgressAccessors({ RgssCore.rgssCompressProgressCount() }, { RgssCore.rgssCompressProgressTotal() }, { RgssCore.rgssCompressProgressFileCount() }, { RgssCore.rgssCompressProgressFileTotal() }, { RgssCore.rgssCompressProgressName() }, { RgssCore.rgssCompressCancel() })
    "tar", "tgz", "tbz2", "txz", "tzst" -> ProgressAccessors({ TarCore.tarCompressProgressCount() }, { TarCore.tarCompressProgressTotal() }, { TarCore.tarCompressProgressFileCount() }, { TarCore.tarCompressProgressFileTotal() }, { TarCore.tarCompressProgressName() }, { TarCore.tarCompressCancel() })
    else -> throw IllegalArgumentException("unsupported compress format: $fmt")
}

/**
 * In-window floating layer hosting operation progress cards.
 *
 * Why not a Dialog: a dialog window covers the whole screen, which used to
 * freeze tab switching during extraction/compression. This layer lives INSIDE
 * the activity's view tree:
 *
 *   ┌────────────────────────┐
 *   │ toolbar                │ ← everything EXCEPT the progress card passes
 *   │ tab strip [1][2][+]    │   touches fall through to the real widgets
 *   ├────────────────────────┤
 *   │ ░░ scrim ░░░░░░░░░░░░  │  (clickable: blocks the content underneath)
 *   │ ░░ ┌────────────┐ ░░░  │
 *   │ ░░ │ dual bars  │ ░░░  │  centered within the REMAINING area
 *   │ ░░ └────────────┘ ░░░  │  ("不算标签栏的居中")
 *   │ ░░ ┌────────────┐ ░░░  │
 *   │ ░░ │ queued op  │ ░░░  │  concurrent ops stack vertically
 *   │ ░░ └────────────┘ ░░░  │
 *   └────────────────────────┘
 */
object OpOverlay {
    class Card(
        val root: View,
        val msg: TextView,
        val overallBar: ProgressBar,
        val overallText: TextView,
        val fileBar: ProgressBar,
        val fileText: TextView,
        /** Owning window's tabId — null means "global" (visible on every tab). */
        val ownerTabId: Int?
    )

    private const val SCRIM_COLOR = 0x66000000.toInt()

    private fun dp(activity: AppCompatActivity, v: Int): Int =
        (v * activity.resources.displayMetrics.density).toInt()

    /** Pass-through height = everything above the pager (status+toolbar+tabs). */
    private fun topInset(activity: AppCompatActivity): Int {
        val pager = activity.findViewById<View>(R.id.viewPager)
        if (pager != null && pager.top > 0) return pager.top
        // Fallback before first layout: status bar + toolbar(56dp) + tabs(50dp)
        val sb = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        val status = if (sb > 0) activity.resources.getDimensionPixelSize(sb) else dp(activity, 24)
        return status + dp(activity, 106)
    }

    private fun ensureHost(activity: AppCompatActivity): ViewGroup? {
        if (activity.isFinishing || activity.isDestroyed) return null
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
        var host = content.findViewById<ViewGroup>(R.id.op_overlay_host)
        if (host == null) {
            val ctx = activity
            val vertical = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                id = R.id.op_overlay_host
                isClickable = false
                isFocusable = false
            }
            val spacer = View(ctx).apply { isClickable = false }
            val scrim = FrameLayout(ctx).apply {
                // Visual dim only — NOT clickable, so touches fall through and the
                // user can keep operating OTHER windows (and this one) mid-op.
                // Only the progress card itself intercepts input.
                isClickable = false
                setBackgroundColor(SCRIM_COLOR)
            }
            vertical.addView(spacer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, topInset(ctx)))
            vertical.addView(scrim, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            content.addView(vertical, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            host = vertical
        }
        return host
    }

    /** Adds a dual-bar card; returns null when the activity can't host it. */
    fun addCard(
        activity: AppCompatActivity,
        title: String,
        cancelLabel: String?,
        onCancelClick: () -> Unit,
        ownerTabId: Int? = null
    ): Card? {
        val host = ensureHost(activity) ?: return null
        val scrim = host.getChildAt(1) as? FrameLayout ?: return null
        var cards = scrim.findViewById<LinearLayout?>(R.id.op_overlay_cards)
        if (cards == null) {
            cards = LinearLayout(activity).apply {
                id = R.id.op_overlay_cards
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            scrim.addView(cards, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val density = activity.resources.displayMetrics.density
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 12), dp(activity, 6))
        }
        val inner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(C["surface"] ?: Color.parseColor("#FF2A2A2A"))
                cornerRadius = 14f * density
            }
            setPadding(dp(activity, 18), dp(activity, 14), dp(activity, 18), dp(activity, 10))
        }
        LayoutInflater.from(activity).inflate(R.layout.dialog_progress_dual, inner, true)
        val msg = inner.findViewById<TextView>(R.id.progress_msg)
        msg.text = title
        val cancelBtn = inner.findViewById<Button>(R.id.progress_cancel)
        if (cancelLabel != null) {
            cancelBtn.visibility = View.VISIBLE
            cancelBtn.text = cancelLabel
            cancelBtn.setOnClickListener { onCancelClick() }
        } else {
            cancelBtn.visibility = View.GONE
        }
        wrap.addView(inner, LinearLayout.LayoutParams(
            activity.resources.getDimensionPixelSize(R.dimen.dialog_max_width),
            ViewGroup.LayoutParams.WRAP_CONTENT))
        // Tag carries the owner for per-tab visibility switches.
        wrap.tag = ownerTabId
        cards.addView(wrap)
        return Card(
            root = wrap,
            msg = msg,
            overallBar = inner.findViewById(R.id.progress_overall),
            overallText = inner.findViewById(R.id.progress_overall_text),
            fileBar = inner.findViewById(R.id.progress_file),
            fileText = inner.findViewById(R.id.progress_file_text),
            ownerTabId = ownerTabId
        )
    }

    /**
     * Per-tab card scoping: show a card only while its owning window is the
     * active one (the user asked for "progress goes to background when I
     * switch tabs"). Cards whose owner tab no longer exists stay VISIBLE so
     * their cancel affordance can't become unreachable.
     */
    fun onActiveTabChanged(activity: AppCompatActivity, activeTabId: Int, liveTabIds: Set<Int>) {
        activity.runOnUiThread {
            val host = activity.findViewById<ViewGroup>(R.id.op_overlay_host) ?: return@runOnUiThread
            val scrim = host.getChildAt(1) as? FrameLayout ?: return@runOnUiThread
            val cards = scrim.findViewById<LinearLayout>(R.id.op_overlay_cards) ?: return@runOnUiThread
            for (i in 0 until cards.childCount) {
                val child = cards.getChildAt(i)
                val owner = child.tag as? Int
                child.visibility = if (owner == null || owner == activeTabId || owner !in liveTabIds)
                    View.VISIBLE else View.GONE
            }
        }
    }

    fun removeCard(activity: AppCompatActivity, card: Card) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        (card.root.parent as? ViewGroup)?.removeView(card.root)
        val host = content.findViewById<ViewGroup>(R.id.op_overlay_host) ?: return
        val scrim = host.getChildAt(1) as? FrameLayout
        val cards = scrim?.findViewById<LinearLayout?>(R.id.op_overlay_cards)
        if (cards == null || cards.childCount == 0) {
            content.removeView(host)
        }
    }
}

/**
 * Operation progress card on the [OpOverlay] floating layer, driven by polling
 * the per-format JNI statics — shared by extraction and compression flows.
 *
 * The original dual-bar look: top bar = overall progress, bottom bar = current
 * file progress (indeterminate when its size is unknown, hidden while queued).
 *
 * Cancel fires ONLY from the explicit 取消 control (touch-outside/back can't).
 * Queued operations render "⏳ 排队中 第N位" instead of polling foreign statics.
 */
class PollingProgressDialog(
    private val activity: AppCompatActivity,
    title: String,
    private val accessors: ProgressAccessors,
    private val messageFn: (name: String, bytes: Long, total: Long) -> String,
    private val cancelLabel: String? = null,
    private val onCancel: () -> Unit = {},
    private val queuedHandle: OpScheduler.OpHandle? = null,
    /** Card scoping: show only while this window is the active tab (null=global). */
    private val ownerTab: TabState? = null
) {
    /** Kept in the signature for call-site compatibility; the overlay itself
     *  renders queue position via [queuedLabelText] set by poll ticks. */
    @Volatile internal var stopped = false
    private var card: OpOverlay.Card? = null
    private val titleText = title
    private var handleRef: OpScheduler.OpHandle? = queuedHandle

    /** Routes a UI update onto the card (no-op when headless/gone). */
    private fun postUi(update: (OpOverlay.Card) -> Unit) {
        activity.runOnUiThread {
            if (stopped) return@runOnUiThread
            val c = card ?: return@runOnUiThread
            update(c)
        }
    }

    fun start() {
        card = OpOverlay.addCard(activity, titleText, cancelLabel, {
            stopped = true
            cancelQueuedThenNotify()
            dismiss()
        }, ownerTab?.tabId)
        thread {
            var last = 0L
            var lastQueueLabel: String? = null
            while (!stopped) {
                Thread.sleep(200)
                // Activity 已销毁时的自救退出：调用方的完成回调按项目不变量是
                // 「守卫在前、dismiss 在后」，守卫 return 会跳过 dismiss()——不
                // 在这里退出的话，200ms 轮询线程会持有死 Activity 永远空转。
                if (activity.isFinishing || activity.isDestroyed) break
                // Queued phase: the format statics belong to whoever ran last —
                // show live position/ETA until this op actually starts.
                val q = handleRef
                if (q != null && !q.isRunning) {
                    val label = queuedLabelText(q)
                    if (label != lastQueueLabel || last != -1L) {
                        lastQueueLabel = label
                        last = -1L
                        postUi { c ->
                            c.overallBar.isIndeterminate = true
                            c.overallText.text = ""
                            c.fileBar.visibility = View.GONE
                            c.fileText.visibility = View.GONE
                            c.msg.text = label
                        }
                    }
                    continue
                }
                if (last == -1L) {
                    // Just left the queued phase — restore both bars.
                    postUi { c ->
                        c.fileBar.visibility = View.VISIBLE
                        c.fileText.visibility = View.VISIBLE
                    }
                }
                val cur = accessors.getCount()
                val tot = accessors.getTotal()
                val fc = accessors.getFileCount()
                val ft = accessors.getFileTotal()
                val name = accessors.getName() ?: ""
                if (cur < last) last = 0
                if (cur != last) {
                    last = cur
                    postUi { c ->
                        c.overallBar.max = 100
                        c.overallBar.isIndeterminate = tot <= 0
                        c.overallBar.progress = if (tot > 0) (cur * 100 / tot).coerceAtMost(100).toInt() else 0
                        c.overallText.text = if (tot > 0) "${fmt(cur)} / ${fmt(tot)}" else fmt(cur)
                        c.msg.text = if (name.isNotEmpty()) messageFn(name, cur, tot) else titleText
                        updateFileBar(c, fc, ft, name)
                    }
                }
            }
        }
    }

    /**
     * Shared cancel path. Ordering matters: dequeue FIRST, and only fire
     * [onCancel] (which sets the format-global Rust CANCEL flag) when this
     * operation actually holds the slot — cancelling a merely-QUEUED op must
     * never kill an unrelated RUNNING same-format op in another window.
     */
    private fun cancelQueuedThenNotify() {
        val q = handleRef
        if (q == null) {
            onCancel()
            return
        }
        val wasRunning = q.isRunning
        q.requestCancel() // no-op once running; dequeues while queued
        // Promotion may land between the read and the dequeue attempt.
        if (wasRunning || q.isRunning) onCancel()
    }

    /** "⏳ 排队中 第N位" (+ ETA when duration history exists for this format). */
    private fun queuedLabelText(q: OpScheduler.OpHandle): String {
        val pos = q.position()
        val eta = q.etaSeconds()
        return if (eta != null && pos > 0)
            activity.getString(R.string.msg_op_queued_eta, pos, etaLabel(eta))
        else
            activity.getString(R.string.msg_op_queued, pos.coerceAtLeast(1))
    }

    private fun updateFileBar(c: OpOverlay.Card, fc: Long, ft: Long, name: String) {
        if (ft > 0) {
            c.fileBar.visibility = View.VISIBLE
            c.fileText.visibility = View.VISIBLE
            c.fileBar.isIndeterminate = false
            c.fileBar.max = 100
            c.fileBar.progress = (fc * 100 / ft).coerceAtMost(100).toInt()
            c.fileText.text = "${fmt(fc)} / ${fmt(ft)}"
        } else {
            // current-file size unknown — indeterminate spinner + name
            c.fileBar.visibility = View.VISIBLE
            c.fileText.visibility = View.VISIBLE
            c.fileBar.isIndeterminate = true
            c.fileText.text = name.takeLast(40)
        }
    }

    fun dismiss() {
        stopped = true
        val c = card
        card = null
        if (c != null) OpOverlay.removeCard(activity, c)
    }
}
