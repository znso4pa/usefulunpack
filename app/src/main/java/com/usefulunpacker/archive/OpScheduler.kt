package com.usefulunpacker.archive

import android.os.SystemClock
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase-2 operation scheduler (replaces the old single-global OperationLock
 * for archive extract/compress/convert/merge/zip-edit/search flows):
 *
 *  - Up to [MAX_SLOTS] operations run truly in parallel (Semaphore semantics).
 *  - Operations with the SAME format key serialize: the Rust side keeps one
 *    global progress/CANCEL slot per format library (crates/common progress_store!)
 *    so two concurrent same-format runs would trample each other's progress and
 *    cancel flags. Cross-format runs are naturally isolated.
 *  - When neither a slot nor the format lock is available, the operation is
 *    QUEUED (FIFO) instead of refused; the UI shows its live position + ETA
 *    and the user can cancel it while it waits ([OpHandle.requestCancel]).
 *
 * Threading: every state transition happens under [schedLock]; blocking waits
 * happen on per-handle monitors, never while holding schedLock. Release is
 * token-based ([OpHandle]) and safe from any thread — mirrors the old
 * unconditional OperationLock.release() contract.
 *
 * ETA: per-format EWMA of observed durations feeds "starts in ~Xs" estimates.
 * With no history yet the label degrades to position-only (see [etaSeconds]).
 */
object OpScheduler {
    const val MAX_SLOTS = 3

    private val schedLock = Any()
    private var runningSlots = 0
    private val lockedFmts = HashSet<String>()
    private val queue = ArrayDeque<OpHandle>()
    private val idGen = AtomicLong()

    private val ewmaDurationMs = ConcurrentHashMap<String, Double>()

    class OpHandle internal constructor(val fmt: String, internal val id: Long) {
        internal enum class S { QUEUED, RUNNING, DONE, CANCELLED }
        @Volatile internal var state = S.QUEUED
        @Volatile internal var interruptAbort = false
        internal var runStartedAt = 0L
        internal val gate = Object()
        @Volatile internal var lastPosition = 0   // cached for UI polls
        @Volatile internal var lastEtaSec = -1    // -1 = unknown

        /** True once the operation actually started (slot + format acquired). */
        val isRunning get() = state == S.RUNNING

        private fun isFinished() = state == S.DONE || state == S.CANCELLED

        /** Worker-side barrier: blocks until the op is promoted to RUNNING.
         *  Returns false when it was cancelled while still queued, or when the
         *  waiting thread was interrupted (the handle flags itself
         *  [interruptAbort] so promotion drops it — we must NOT touch
         *  schedLock here: promote holds schedLock and wants this gate, and a
         *  gate-holder wanting schedLock would invert the lock order).
         *  Worker must abort silently on false — nothing was started. */
        fun await(): Boolean = synchronized(gate) {
            while (state == S.QUEUED) {
                try {
                    @Suppress("PLATFORM_FUNCTION_CALL") gate.wait()
                } catch (e: InterruptedException) {
                    // 唤醒时可能已被提升为 RUNNING（promotion 在 wait 返回前完成）。
                    // 此时照常开跑：返回 false 会让 worker 静默放弃，而槽位 +
                    // 格式锁只有 release() 能释放——永久泄漏。
                    if (state == S.RUNNING) return true
                    interruptAbort = true
                    return false
                }
            }
            state == S.RUNNING
        }

        /** Queue-only cancel. No-op once RUNNING (use the progress accessors'
         *  cancel() for that, as before). Safe from any thread. */
        fun requestCancel() {
            synchronized(schedLock) {
                if (state != S.QUEUED) return
                queue.remove(this)
                state = S.CANCELLED
                recomputePositionsLocked()
            }
            synchronized(gate) { gate.notifyAll() }
        }

        /** Token release — pairs with a successful [await]. Idempotent-ish:
         *  releasing a non-RUNNING handle is a no-op (defensive). */
        fun release() {
            var promotedAny = false
            synchronized(schedLock) {
                if (state != S.RUNNING) return
                runningSlots--
                lockedFmts.remove(fmt)
                state = S.DONE
                val durMs = SystemClock.elapsedRealtime() - runStartedAt
                if (durMs > 500) {
                    ewmaDurationMs.merge(fmt, durMs.toDouble()) { old, v -> old * 0.7 + v * 0.3 }
                }
                promotedAny = promoteLocked()
            }
            // Positions may have shifted for the remaining queue entries.
            if (promotedAny) refreshCachedQueueInfo()
        }

        /** 1-based FIFO position; 0 when running/done/cancelled. */
        fun position(): Int = if (isRunning || isFinished()) 0 else lastPosition

        /** Estimated seconds until this op STARTS, or null with no history
         *  (also null once finished/cancelled). */
        fun etaSeconds(): Int? {
            if (isRunning || isFinished()) return null
            val est = lastEtaSec
            return if (est >= 0) est else null
        }
    }

    /** Fast path + slow path in one: returns a RUNNING handle immediately when
     *  resources allow, otherwise registers a QUEUED handle (never refuses). */
    fun obtain(fmt: String): OpHandle {
        val h = OpHandle(fmt, idGen.incrementAndGet())
        synchronized(schedLock) {
            if (!tryStartLocked(h)) {
                queue.addLast(h)
                recomputePositionsLocked()
            }
        }
        return h
    }

    // ── internals (all callers hold schedLock) ──

    private fun tryStartLocked(h: OpHandle): Boolean {
        if (runningSlots >= MAX_SLOTS) return false
        if (!lockedFmts.add(h.fmt)) return false
        runningSlots++
        h.state = OpHandle.S.RUNNING
        h.runStartedAt = SystemClock.elapsedRealtime()
        return true
    }

    /** Promote waiting handles while slots are free. Same-fmt head-of-line
     *  blocking is skipped so other formats can proceed; entries aborted by a
     *  worker interrupt are dropped instead of started. */
    private fun promoteLocked(): Boolean {
        queue.removeAll { it.interruptAbort }
        var moved = false
        while (runningSlots < MAX_SLOTS) {
            val next = queue.firstOrNull { !lockedFmts.contains(it.fmt) } ?: break
            queue.remove(next)
            if (!tryStartLocked(next)) { queue.addFirst(next); break }
            moved = true
            synchronized(next.gate) { next.gate.notifyAll() }
        }
        if (moved || queue.isNotEmpty()) recomputePositionsLocked()
        return moved
    }

    private fun recomputePositionsLocked() {
        var pos = 0
        for (h in queue) {
            pos++
            h.lastPosition = pos
        }
        refreshCachedQueueInfo()
    }

    /** Precompute ETA cache for queued handles (called on queue mutations). */
    private fun refreshCachedQueueInfo() {
        // Running same-fmt ops count as "ahead" for ETA purposes.
        val runningByFmt = HashMap<String, Int>()
        synchronized(schedLock) {
            // collect from lockedFmts (one per fmt)
            for (f in lockedFmts) runningByFmt[f] = 1
            for (h in queue) {
                val aheadSameFmt = runningByFmt[h.fmt] ?: 0
                var ms = 0.0
                // each ahead queued same-fmt op costs one mean duration; the
                // running one costs its (unknown) remainder ≈ mean duration
                repeat(aheadSameFmt) {
                    ms += ewmaDurationMs[h.fmt] ?: return@repeat
                }
                // plus same-fmt queued entries between the front and me
                var seen = 0
                for (o in queue) {
                    if (o === h) break
                    if (o.fmt == h.fmt) {
                        seen++
                        ewmaDurationMs[h.fmt]?.let { ms += it }
                    }
                }
                h.lastEtaSec = if (aheadSameFmt == 0 && seen == 0) 0
                               else if (ms > 0) (ms / 1000.0).toInt().coerceAtLeast(1)
                               else -1
            }
        }
    }
}

/** Humanized short duration for queue labels ("40s" / "3m" style per locale). */
internal fun etaLabel(sec: Int): String = when {
    sec < 60 -> "${sec}s"
    else -> "${sec / 60}m"
}
