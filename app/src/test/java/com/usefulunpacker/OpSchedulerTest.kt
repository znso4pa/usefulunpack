package com.usefulunpacker

import com.usefulunpacker.archive.OpScheduler
import org.junit.Assert.*
import org.junit.Test

class OpSchedulerTest {
    @Test fun taskCenterCancelDoesNotSignalAnUnownedNativeSlot() {
        val first = OpScheduler.obtain("zip")
        val queued = OpScheduler.obtain("zip")
        try {
            queued.cancelFromOwner { fail("Queued task must not signal native cancellation") }
            assertFalse(queued.await())
            first.release()
            val next = OpScheduler.obtain("zip")
            try {
                first.cancelFromOwner { fail("Released handle must not signal the replacement") }
                assertTrue(next.isRunning)
            } finally { next.release() }
        } finally {
            queued.requestCancel()
            first.release()
        }
    }
    @Test fun compositeReservesBothLibrariesAndConsumesOnlyOneSlot() {
        val handles = mutableListOf<OpScheduler.OpHandle>()
        fun obtain(label: String, vararg resources: String) = OpScheduler.obtain(label, resources.toSet()).also { handles.add(it) }
        try {
            val merge = obtain("merge", "zip", "pfs", "merge")
            assertTrue(merge.isRunning)
            val zip = obtain("zip", "zip")
            val pfs = obtain("pfs", "pfs")
            assertFalse(zip.isRunning); assertFalse(pfs.isRunning)
            assertTrue(obtain("tar", "tar").isRunning)
            assertTrue(obtain("rar", "rar").isRunning)
            val audio = obtain("audio", "audio")
            assertFalse(audio.isRunning)
            merge.release()
            assertTrue(zip.isRunning)
            assertFalse(pfs.isRunning) // three global slots are full
            zip.release()
            assertTrue(pfs.isRunning)
        } finally {
            handles.forEach { it.requestCancel() }
            handles.forEach { it.release() }
        }
    }

    @Test fun queuedCancelNeverSetsTheRunningOperationsCancelFlag() {
        val first = OpScheduler.obtain("zip")
        val queued = OpScheduler.obtain("zip")
        try {
            queued.requestCancel()
            assertFalse(queued.await())
            assertTrue(first.isRunning)
            assertFalse(first.cancelRequested)
            first.markCancelRequested()
            assertTrue(first.await())
            assertTrue(AppDiagnostics.isCancellationRequested())
        } finally { queued.requestCancel(); first.release() }
        assertFalse(AppDiagnostics.isCancellationRequested())
    }
}
