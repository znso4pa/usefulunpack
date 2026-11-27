package com.usefulunpacker

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CancellationException

class TranslationDraftTest {
    @get:Rule val temp = TemporaryFolder()
    private fun draft(text: String = "Hello {name}\r\nKeep\\n\nTail\r"): TranslationDraft {
        val file = temp.newFolder().canonicalFile.resolve("script.txt").apply { writeText(text) }
        return TranslationDraft.open(file, "UTF-8", "UTF-8")
    }
    private fun propose(turn: TranslationTurn, vararg values: String) {
        turn.invoke(TranslationTurn.READ, JSONObject())
        turn.invoke(TranslationTurn.PROPOSE, JSONObject().put("revision", turn.token).put("translations", JSONArray(values.toList())))
    }
    @Test fun batchEditsOnlyTheDraftPreservesOrderAndAllOriginalTerminators() {
        val draft = draft()
        val bytes = draft.source.readBytes()
        propose(draft.begin(0, 2), "你好 {name}", "保留\\n")
        assertEquals("你好 {name}\r\n保留\\n\nTail\r", draft.preview())
        assertArrayEquals(bytes, draft.source.readBytes())
        assertEquals("Hello {name}", draft.document.original(0))
        draft.undo()
        assertEquals(0, draft.changedCount())
        assertEquals("Hello {name}\r\nKeep\\n\nTail\r", draft.preview())
    }
    @Test fun humanTypingCoalescesAndRowAndWholeDraftRestoreStayLocal() {
        val draft = draft()
        draft.edit(0,"H {name}"); draft.edit(0,"Hi {name}"); draft.edit(0,"Hello again {name}")
        draft.undo(); assertEquals("Hello {name}",draft.value(0))
        draft.edit(0,"你好 {name}"); draft.edit(2,"末尾")
        draft.reset(0);assertEquals("Hello {name}",draft.value(0));assertEquals("末尾",draft.value(2))
        draft.undo();assertEquals("你好 {name}",draft.value(0))
        draft.reset();assertEquals(0,draft.changedCount());assertFalse(draft.canUndo())
    }
    @Test fun editedOrUndoneRowsRejectLateAiResultsEvenWhenTextLooksTheSame() {
        for (restore in listOf(false,true)) {
            val draft=draft();val turn=draft.begin(0,1)
            turn.invoke(TranslationTurn.READ,JSONObject())
            draft.edit(0,"manual {name}")
            if (restore) draft.undo()
            val error=assertThrows(TranslationProblem::class.java) {
                turn.invoke(TranslationTurn.PROPOSE,JSONObject().put("revision",turn.token).put("translations",JSONArray(listOf("AI {name}"))))
            }
            assertEquals("STALE_DRAFT",error.reason)
            assertEquals(if (restore) "Hello {name}" else "manual {name}",draft.value(0))
        }
    }
    @Test fun unrelatedRowEditsDoNotInvalidateAnOtherwiseCurrentBatch() {
        val draft=draft();val turn=draft.begin(0,1);draft.edit(2,"manual")
        propose(turn,"你好 {name}");assertEquals("manual",draft.value(2))
    }
    @Test fun malformedBatchIsAtomicForCountsMarkersNewlinesAndEncoding() {
        for (values in listOf(listOf("你好 {name}"),listOf("你好 {other}","保留\\n"),listOf("你好 {name}","bad\nline"),listOf("你好 {name}","lost escape"))) {
            val draft=draft();val turn=draft.begin(0,2);turn.invoke(TranslationTurn.READ,JSONObject())
            assertThrows(TranslationProblem::class.java) { turn.invoke(TranslationTurn.PROPOSE,JSONObject().put("revision",turn.token).put("translations",JSONArray(values))) }
            assertEquals(0,draft.changedCount())
        }
        val source=temp.newFolder().canonicalFile.resolve("legacy.txt").apply { writeText("hello") }
        val legacy=TranslationDraft.open(source,"UTF-8","SHIFT-JIS")
        assertThrows(TranslationProblem::class.java) { propose(legacy.begin(0,1),"📝") }
        assertEquals(0,legacy.changedCount())
    }
    @Test fun onlySelectedRowsAreReadableAndProposalsCannotBeReplayed() {
        val draft=draft("private-other-row\nselected\nanother-private-row")
        val turn=draft.begin(1,1)
        assertEquals(setOf(TranslationTurn.READ,TranslationTurn.PROPOSE),turn.tools.keys)
        val source=turn.invoke(TranslationTurn.READ,JSONObject()).toString()
        assertTrue(source.contains("selected"));assertFalse(source.contains("private"));assertFalse(source.contains(draft.source.path))
        assertThrows(TranslationProblem::class.java) { turn.invoke(TranslationTurn.READ,JSONObject()) }
        val input=JSONObject().put("revision",turn.token).put("translations",JSONArray(listOf("译文")))
        turn.invoke(TranslationTurn.PROPOSE,input)
        assertThrows(TranslationProblem::class.java) { turn.invoke(TranslationTurn.PROPOSE,input) }
    }
    @Test fun exportPreservesBigEndianBomAndCrLfWithoutOverwritingEitherFile() {
        val root=temp.newFolder().canonicalFile
        val bytes=byteArrayOf(0xfe.toByte(),0xff.toByte())+"hello\r\n".toByteArray(Charsets.UTF_16BE)
        val file=root.resolve("source.txt").apply { writeBytes(bytes) }
        root.resolve("source.edited.txt").writeText("existing")
        val draft=TranslationDraft.open(file,"UTF-16","UTF-16")
        propose(draft.begin(0,1),"你好")
        val output=draft.export()
        assertArrayEquals(byteArrayOf(0xfe.toByte(),0xff.toByte())+"你好\r\n".toByteArray(Charsets.UTF_16BE),output.readBytes())
        assertArrayEquals(bytes,file.readBytes());assertEquals("existing",root.resolve("source.edited.txt").readText())
        assertEquals(1,draft.changedCount());assertTrue(draft.canUndo())
    }
    @Test fun changedSourceIsRejectedBeforeAnyCopyIsPublished() {
        val draft=draft();draft.edit(2,"translated");draft.source.writeText("externally changed")
        assertEquals("SOURCE_CHANGED",assertThrows(TranslationProblem::class.java) { draft.export() }.reason)
        assertEquals(listOf("script.txt"),draft.source.parentFile!!.list()!!.toList())
    }
    @Test fun cp932ExportUsesTheExactWindowsMappingRatherThanAnAliasGuess() {
        val source=temp.newFolder().canonicalFile.resolve("source.txt").apply { writeText("original") }
        val draft=TranslationDraft.open(source,"UTF-8","CP932")
        propose(draft.begin(0,1),"①")
        assertArrayEquals(byteArrayOf(0x87.toByte(),0x40),draft.export().readBytes())
        assertEquals("original",source.readText())
    }
    @Test fun snapshotsKeepUndoAndOlderAutosavesCannotOverwriteNewerDeltas() {
        val draft=draft();val store=AgentSessionStore(temp.newFolder())
        val session=store.save(AgentSession(translation=true),translation=draft.snapshot())
        val older=draft.snapshot();draft.edit(2,"译文")
        store.saveTranslation(session.id,draft.snapshot());store.saveTranslation(session.id,older)
        val restored=checkNotNull(store.openTranslation(store.open(session.id)))
        assertEquals("译文",restored.value(2));restored.undo();assertEquals("Tail",restored.value(2))
    }
    @Test fun sessionDeletionRemovesDraftAndLateAutosaveCannotResurrectIt() {
        val root=temp.newFolder();val store=AgentSessionStore(root);val draft=draft()
        val saved=store.save(AgentSession(translation=true),translation=draft.snapshot())
        assertEquals(2,root.list()!!.size)
        store.delete(saved) {}
        assertTrue(root.list()!!.isEmpty())
        assertThrows(CancellationException::class.java) { store.saveTranslation(saved.id,draft.snapshot()) }
        assertTrue(root.list()!!.isEmpty())
    }
    @Test fun ordinarySessionsDoNotAcquireDraftToolsOrSidecarsAndModelSwitchKeepsTranslationScope() {
        val root=temp.newFolder();val store=AgentSessionStore(root)
        val normal=store.save(AgentSession());assertNull(store.openTranslation(normal));assertEquals(1,root.list()!!.size)
        val scoped=AgentSession(translation=true,workspace="/workspace",baseUrl="https://example.invalid/v1",model="a")
        val changed=scoped.withModelSettings("https://other.invalid/v1","b")
        assertTrue(changed.translation);assertEquals(scoped.workspace,changed.workspace);assertNotEquals(scoped.id,changed.id)
    }
    @Test fun rangeAndSourceBudgetsAreEnforcedBeforeModelCalls() {
        val draft=draft()
        for ((start,count) in listOf(-1 to 1,0 to 9,2 to 2)) assertThrows(TranslationProblem::class.java) { draft.begin(start,count) }
        val large=draft("x".repeat(14000));assertEquals("BATCH_TOO_LARGE",assertThrows(TranslationProblem::class.java) { large.begin(0,1) }.reason)
        val source=temp.newFolder().canonicalFile.resolve("empty.txt").apply { writeText("") }
        assertThrows(TranslationProblem::class.java) { TranslationDraft.open(source,"UTF-8","UTF-8") }
    }
    @Test fun blankRowsAndNulCannotBeFilledByTheModel() {
        for (text in listOf("\n", "text\n")) {
            val draft=draft(text)
            assertThrows(TranslationProblem::class.java) { propose(draft.begin(0,1),if(text=="\n") "explanation" else "bad\u0000text") }
            assertEquals(0,draft.changedCount())
        }
    }
    @Test fun translationLeaseIsExclusiveAndOldOwnersCannotReleaseANewLease() {
        val a=Any();val b=Any();val id="synthetic-session"
        assertTrue(TranslationEditors.claim(id,a));assertTrue(TranslationEditors.claim(id,a))
        assertFalse(TranslationEditors.claim(id,b));assertTrue(TranslationEditors.heldByOther(id,b))
        TranslationEditors.release(id,b);assertFalse(TranslationEditors.claim(id,b))
        TranslationEditors.release(id,a);assertTrue(TranslationEditors.claim(id,b))
        TranslationEditors.release(id,a);assertTrue(TranslationEditors.heldByOther(id,a))
        TranslationEditors.release(id,b)
    }
}
