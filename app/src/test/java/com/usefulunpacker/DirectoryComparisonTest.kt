package com.usefulunpacker

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectoryComparisonTest {
    @get:Rule val temp = TemporaryFolder()
    private fun roots() = temp.newFolder().canonicalFile to temp.newFolder().canonicalFile
    private fun File.put(path: String, text: String) = resolve(path).apply { checkNotNull(parentFile).mkdirs(); writeText(text) }

    @Test fun byteComparisonFindsSameSizeSameDateEditsAndIgnoresTimestampOnlyChanges() {
        val (a,b) = roots()
        a.put("same.txt", "same"); b.put("same.txt", "same")
        a.put("modified.txt", "AAAA"); b.put("modified.txt", "BBBB")
        for (root in listOf(a,b)) Files.setLastModifiedTime(root.resolve("modified.txt").toPath(), FileTime.fromMillis(100000))
        Files.setLastModifiedTime(a.resolve("same.txt").toPath(), FileTime.fromMillis(200000))
        val result = DirectoryComparator.compare(a,b)
        assertTrue(result.complete)
        assertEquals("DIFFERENT", result.status)
        assertEquals(1, result.unchanged)
        assertEquals("modified.txt", result.entries.single().path)
        assertEquals(DirectoryChange.MODIFIED, result.entries.single().state)
        assertEquals("AAAA", a.resolve("modified.txt").readText())
        assertEquals("BBBB", b.resolve("modified.txt").readText())
    }

    @Test fun unionIncludesNestedFilesEmptyDirectoriesAndTypeChangesWithoutGuessingRenames() {
        val (a,b) = roots()
        a.put("removed/script.txt", "old"); b.put("added/script.txt", "new")
        a.resolve("empty").mkdir(); a.put("kind", "file"); b.put("kind/child", "directory child")
        val result = DirectoryComparator.compare(a,b)
        val rows = result.entries.associateBy { it.path }
        assertTrue(result.complete)
        assertEquals(DirectoryChange.REMOVED, rows.getValue("removed/script.txt").state)
        assertEquals(DirectoryChange.ADDED, rows.getValue("added/script.txt").state)
        assertEquals(DirectoryChange.REMOVED, rows.getValue("empty").state)
        assertEquals(DirectoryChange.TYPE_CHANGED, rows.getValue("kind").state)
        assertFalse(rows.containsKey("kind/child"))
    }

    @Test fun identitySameRootsAndEmptyTreesAreValidAndNeverWriteAnything() {
        val (a,b) = roots()
        assertEquals("IDENTICAL", DirectoryComparator.compare(a,b).status)
        a.put("script.txt", "same")
        val result = DirectoryComparator.compare(a,a)
        assertEquals("IDENTICAL", result.status)
        assertEquals(1, result.unchanged)
    }

    @Test fun symlinksAreReportedIncompleteWithoutReadingTheirTargets() {
        val (a,b) = roots()
        val outside = temp.newFolder()
        outside.put("private.txt", "never include this body")
        Files.createSymbolicLink(a.resolve("link").toPath(), outside.toPath())
        val result = DirectoryComparator.compare(a,b)
        assertFalse(result.complete)
        assertEquals(DirectoryChange.SKIPPED, result.entries.single().state)
        assertEquals("SYMLINK", result.entries.single().reason)
        assertFalse(result.json().toString().contains("private.txt"))
    }

    @Test fun metadataIsRelativePagedBoundedAndDoesNotIncludeContents() {
        val (a,b) = roots()
        repeat(2001) { b.put("f${it.toString().padStart(4,'0')}.txt", "private-body") }
        val result = DirectoryComparator.compare(a,b)
        assertEquals(2001, result.counts.getValue(DirectoryChange.ADDED))
        assertEquals(2000, result.entries.size)
        assertTrue(result.limited)
        val page = result.json()
        assertEquals(20, page.getJSONArray("entries").length())
        assertEquals("20", page.getString("nextAfter"))
        assertFalse(page.toString().contains(a.path))
        assertFalse(page.toString().contains("private-body"))
        assertEquals("f0020.txt", result.json(20).getJSONArray("entries").getJSONObject(0).getString("path"))
    }

    @Test fun cancellationAndInvalidRootsCannotMasqueradeAsEquality() {
        val (a,b) = roots()
        assertThrows(CancellationException::class.java) { DirectoryComparator.compare(a,b, { true }) }
        assertThrows(java.io.IOException::class.java) { DirectoryComparator.compare(a.resolve("missing"),b) }
        var path = a
        repeat(67) { path = path.resolve("nested").apply { mkdir() } }
        assertFalse(DirectoryComparator.compare(a,b).complete)
    }

    @Test fun fileAndTreeMutationsDuringReadsInvalidateTheAffectedVerdict() {
        for (tree in listOf(false,true)) {
            val (a,b) = roots()
            val bytes = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
            a.resolve("large.bin").writeBytes(bytes); b.resolve("large.bin").writeBytes(bytes)
            var calls = 0
            val result = DirectoryComparator.compare(a,b, {
                if (++calls == 10) {
                    if (tree) b.put("new.txt", "created during traversal")
                    else b.resolve("large.bin").appendBytes(byteArrayOf(7))
                }
                false
            })
            assertFalse(result.complete)
            assertTrue(result.entries.any { it.state == DirectoryChange.UNREADABLE })
            assertNotEquals("IDENTICAL", result.status)
        }
    }

    @Test fun lineDiffUsesTheSameSnapshotAndRefusesLaterFileChanges() {
        val (a,b) = roots()
        a.put("script.txt", "before\nkeep\n"); b.put("script.txt", "after\nkeep\n")
        val report = DirectoryComparator.compare(a,b)
        val row = report.entries.single()
        val diff = directoryComparisonPreview(report,row) {}
        assertEquals(listOf("before","after"), diff.lines.filter { it.kind != 0 }.map { it.text })
        b.resolve("script.txt").appendText("changed again")
        assertThrows(java.io.IOException::class.java) { directoryComparisonPreview(report,row) {} }
    }

    @Test fun directCallBudgetsNeverTurnUncheckedBytesIntoAnEqualityVerdict() {
        val (a,b)=roots()
        a.resolve("large.bin").writeBytes(ByteArray(128 * 1024))
        b.resolve("large.bin").writeBytes(ByteArray(128 * 1024))
        for (report in listOf(DirectoryComparator.compare(a,b,maxReadBytes=1),DirectoryComparator.compare(a,b,maxDurationMs=0))) {
            assertFalse(report.complete)
            assertTrue(report.limited)
            assertEquals("INCOMPLETE",report.status)
            assertTrue(report.entries.any { it.reason.contains("BUDGET") })
        }
    }

    @Test fun uuCommandsShareAlgorithmsAndHaveConventionalComparisonExits() {
        val root=temp.newFolder()
        root.resolve("a").mkdir(); root.resolve("b").mkdir()
        root.resolve("a").put("script.txt", "Hello {name}")
        root.resolve("b").put("script.txt", "你好 {other}")
        val ctx=UuCommands.Ctx(null,root)
        val result=UuCommands.dispatch(listOf("dircmp","a","b","-j"),ctx)
        assertEquals(1,result.exitCode); assertNull(result.failure)
        assertEquals("DIFFERENT",JSONObject(result.text).getString("status"))
        val check=UuCommands.dispatch(listOf("check","a/script.txt","b/script.txt","--profile","named","-j"),ctx)
        assertEquals(1,check.exitCode); assertNull(check.failure)
        assertEquals(1,JSONObject(check.text).getInt("totalIssues"))
        for (args in listOf(listOf("dircmp","a","b","--bad"),listOf("check","a/script.txt","b/script.txt","--limit","0"))) {
            assertEquals(2,UuCommands.dispatch(args,ctx).exitCode)
        }
    }

    @Test fun mcpAndUutExposeBothCommandsWithoutEscapingTheWorkspace() {
        val root=temp.newFolder().canonicalFile
        root.resolve("a").mkdir();root.resolve("b").mkdir()
        root.resolve("a").put("script.txt","{name}")
        root.resolve("b").put("script.txt","{name}")
        val workspace=McpWorkspace(root)
        val worker=McpArchiveWorker(workspace)
        McpTasks(execute=worker::execute).use { tasks ->
            val runtime=WorkspaceSkillRuntime(workspace,tasks)
            val report=runtime.invokeSkill("COMPARE_DIRECTORY",JSONObject().put("source","a").put("target","b"))
            assertFalse(report.isError);assertEquals("IDENTICAL",report.data.getString("status"))
            for (skill in listOf("UU_DIRCMP","UU_CHECK")) assertTrue(runtime.skills().any { it.name==skill })
            val script="uu dircmp a b -j\nuu check a/script.txt b/script.txt --profile named -j"
            val result=runtime.invokeSkill("RUN_UUT",JSONObject().put("script",script).put("waitMs",15000))
            assertFalse(result.isError)
            assertEquals("SUCCESS",result.data.getString("state"))
            assertTrue(result.data.getString("stdout").contains("NO_FINDINGS"))
            root.resolve("b/script.txt").writeText("{other}")
            val different=runtime.invokeSkill("UU_DIRCMP",JSONObject().put("args",org.json.JSONArray(listOf("a","b","-j"))).put("waitMs",15000))
            assertEquals("SUCCESS",different.data.getString("state"))
            assertEquals(1,different.data.getInt("exitCode"))
            assertTrue(different.data.getString("stdout").contains("DIFFERENT"))
            val bad=runtime.invokeSkill("COMPARE_DIRECTORY",JSONObject().put("source","a").put("target","../outside"))
            assertTrue(bad.isError)
            assertEquals(listOf("a","b"),root.list()!!.sorted())
        }
        assertEquals(listOf("a","b"),McpCommand.parse(listOf("dircmp","a","b","--limit","5")).pathsToCheck)
        assertEquals(listOf("a/script.txt","b/script.txt"),McpCommand.parse(listOf("check","a/script.txt","b/script.txt","--profile","named")).pathsToCheck)
    }
}
