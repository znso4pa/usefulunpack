package com.usefulunpacker

import com.usefulunpacker.fileops.RecycleBin
import com.usefulunpacker.fileops.RecycleStaging
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Production metadata/transaction code on a real filesystem; failures are injected without permissions tricks. */
class RecycleSafetyTest {
    @get:Rule val temp = TemporaryFolder()
    private class CopyOnlyFile(path: String, private val refuseDelete: Boolean = false) : File(path) {
        override fun renameTo(dest: File): Boolean = false
        override fun delete(): Boolean = if (refuseDelete) false else super.delete()
    }
    private val synchronous: (() -> Unit) -> Unit = { it() }

    @Test fun reservedPayloadAndMetadataNamesRoundTripWithoutOverwritingData() {
        val bin = temp.newFolder()
        for (name in listOf("_meta.json", "payload", "ordinary.txt")) {
            val source = File(temp.root, name).apply { writeText("original bytes for $name") }
            val bytes = source.readBytes()
            assertTrue(RecycleBin.moveToDirectory(bin, source, scheduleBackfill = synchronous))
            assertFalse(source.exists())
            val entry = RecycleBin.listEntriesIn(bin).single()
            assertEquals(name, entry.originalName)
            assertArrayEquals(bytes, File(bin, "${entry.id}/payload").readBytes())
            assertTrue(RecycleBin.restoreFromDirectory(bin, entry.id))
            assertArrayEquals(bytes, source.readBytes())
            assertTrue(RecycleBin.listEntriesIn(bin).isEmpty())
        }
    }

    @Test fun crossVolumeDirectoryFallbackPreservesContentsAndProgressCaliber() {
        val bin = temp.newFolder()
        val source = temp.newFolder()
        File(source, "sub").mkdir()
        File(source, "sub/a.txt").writeText("first")
        File(source, "b.txt").writeText("second")
        val states = mutableListOf<RecycleBin.MoveState>()
        assertTrue(RecycleBin.moveToDirectory(bin, CopyOnlyFile(source.path), RecycleBin.MoveProgress { states.add(it) }, synchronous))
        assertFalse(source.exists())
        val entry = RecycleBin.listEntriesIn(bin).single()
        assertTrue(entry.isDirectory)
        assertTrue(states.isNotEmpty())
        assertTrue(states.all { it.barTotal == it.dataTotal * 2 })
        assertEquals(states.last().barTotal, states.last().barDone)
        assertTrue(RecycleBin.restoreFromDirectory(bin, entry.id, rename = { _, _ -> false }))
        assertEquals("first", File(source, "sub/a.txt").readText())
        assertEquals("second", File(source, "b.txt").readText())
    }

    @Test fun failureAfterRenameKeepsTheOnlySurvivingCopyRecoverable() {
        val bin = temp.newFolder()
        val source = temp.newFile().apply { writeText("must survive") }
        assertFalse(RecycleBin.moveToDirectory(bin, source, RecycleBin.MoveProgress { throw IOException("injected progress failure") }, synchronous))
        assertFalse(source.exists())
        val entry = RecycleBin.listEntriesIn(bin).single()
        assertEquals("must survive", File(bin, "${entry.id}/payload").readText())
        assertTrue(RecycleBin.restoreFromDirectory(bin, entry.id))
        assertEquals("must survive", source.readText())
    }

    @Test fun failureBeforeSourceDeletionDiscardsOnlyTheIncompleteStage() {
        val bin = temp.newFolder()
        val source = temp.newFile().apply { writeText("untouched source") }
        assertFalse(RecycleBin.moveToDirectory(bin, CopyOnlyFile(source.path), RecycleBin.MoveProgress { throw IOException("injected copy failure") }, synchronous))
        assertEquals("untouched source", source.readText())
        assertTrue(bin.listFiles()!!.none { it.isDirectory })
        assertTrue(RecycleBin.listEntriesIn(bin).isEmpty())
    }

    @Test fun deleteFailureIsReportedAndKeepsBothSourceAndCompleteBackup() {
        val bin = temp.newFolder()
        val source = temp.newFile().apply { writeText("both copies stay") }
        assertFalse(RecycleBin.moveToDirectory(bin, CopyOnlyFile(source.path, refuseDelete = true), scheduleBackfill = synchronous))
        assertEquals("both copies stay", source.readText())
        val entry = RecycleBin.listEntriesIn(bin).single()
        assertEquals("both copies stay", File(bin, "${entry.id}/payload").readText())
    }

    @Test fun manifestFailureRetainsBackupAndLaterRebuildDoesNotDuplicateEntries() {
        val bin = temp.newFolder()
        File(bin, "manifest.json").mkdir()
        File(bin, "manifest.json/block").writeText("force an I/O error")
        val source = temp.newFile().apply { writeText("preserved data") }
        assertFalse(RecycleBin.moveToDirectory(bin, source, scheduleBackfill = synchronous))
        val first = RecycleBin.listEntriesIn(bin).single()
        assertEquals("preserved data", File(bin, "${first.id}/payload").readText())
        val second = temp.newFile().apply { writeText("another entry") }
        assertTrue(RecycleBin.moveToDirectory(bin, second, scheduleBackfill = synchronous))
        val entries = RecycleBin.listEntriesIn(bin)
        assertEquals(2, entries.size)
        assertEquals(2, entries.map { it.id }.toSet().size)
    }

    @Test fun legacyMetadataStillRestoresItsOriginalPayloadLayout() {
        val bin = temp.newFolder()
        val entry = File(bin, "legacy").apply { mkdir() }
        val target = File(temp.root, "restored.txt")
        File(entry, "old.txt").writeText("legacy bytes")
        File(entry, "_meta.json").writeText(JSONObject().put("id", "legacy")
            .put("originalPath", target.path).put("originalName", "old.txt")
            .put("deletedAt", 1L).put("isDirectory", false).put("size", 12).toString())
        assertTrue(RecycleBin.restoreFromDirectory(bin, "legacy"))
        assertEquals("legacy bytes", target.readText())
    }

    @Test fun stagingCleanupCannotBeEitherANoopOrOverBroad() {
        val incomplete = temp.newFolder()
        File(incomplete, "payload").writeText("partial")
        RecycleStaging(incomplete).discardIncomplete()
        assertFalse(incomplete.exists())
        val complete = temp.newFolder()
        File(complete, "payload").writeText("only copy")
        RecycleStaging(complete).apply { protect(); discardIncomplete() }
        assertEquals("only copy", File(complete, "payload").readText())
    }

    @Test fun restoreManifestFailureNeverDeletesTheCompletedDestination() {
        val bin = temp.newFolder()
        val source = temp.newFile().apply { writeText("restored copy must survive") }
        assertTrue(RecycleBin.moveToDirectory(bin, source, scheduleBackfill = synchronous))
        val entry = RecycleBin.listEntriesIn(bin).single()
        assertTrue(File(bin, "manifest.json").delete())
        File(bin, "manifest.json").mkdir()
        File(bin, "manifest.json/block").writeText("force bookkeeping failure")
        assertFalse(RecycleBin.restoreFromDirectory(bin, entry.id, rename = { _, _ -> false }))
        assertEquals("restored copy must survive", source.readText())
    }
}
