package com.usefulunpacker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * FD 表的契约。这些是 POSIX fd 语义的映射，所以断言要钉死编号起点与失效检查。
 */
class FdTableTest {

    private fun tmp(tag: String): File {
        val d = File(System.getProperty("java.io.tmpdir"), "uufd_$tag")
        d.mkdirs()
        return d
    }

    private fun host(dir: File, size: Int = 4096): File {
        val f = File(dir, "movie.mkv")
        RandomAccessFile(f, "rw").use { ra ->
            ra.setLength(size.toLong())
            ra.write("PK\u0003\u0004".toByteArray())
        }
        return f
    }

    @Test
    fun fdsStartAtThreeSoStdioStaysReserved() {
        val dir = tmp("start")
        val h = host(dir)
        val t = FdTable()
        val hits = listOf(ScanHit(0, "ZIP archive", 100, 12), ScanHit(256, "PNG image", 50, null))
        val fds = t.register(h, hits, h.length(), h.lastModified()) { ARCHIVE_LABELS[it] }
        // 第一个命中是 f3 —— f0/1/2 保留给 stdin/stdout/stderr，与真 POSIX fd 一致
        assertEquals(listOf(3, 4), fds)
        assertNull("fd0 保留", t.get(0))
        assertNull("fd1 保留", t.get(1))
        assertNull("fd2 保留", t.get(2))
        dir.deleteRecursively()
    }

    @Test
    fun archiveHitsAreMarkedAndNonArchiveHitsAreNot() {
        val dir = tmp("kind")
        val h = host(dir)
        val t = FdTable()
        t.register(h, listOf(
            ScanHit(0, "ZIP archive", 100, 12),
            ScanHit(256, "PNG image", 50, null),
        ), h.length(), h.lastModified()) { ARCHIVE_LABELS[it] }
        assertTrue("zip 命中应可当归档读", t.get(3)!!.isArchive())
        assertEquals("zip", t.get(3)!!.archiveKey)
        assertTrue("png 命中没有容器，只能 dd", !t.get(4)!!.isArchive())
        assertNull(t.get(4)!!.archiveKey)
        dir.deleteRecursively()
    }

    @Test
    fun tarLabelIsRecognisedSoFdCanBeListed() {
        // 手抄 label 表时丢过 "POSIX tar"，这里钉死：它必须映射到 tar
        assertEquals("tar", ARCHIVE_LABELS["POSIX tar archive"])
        assertNotNull(ARCHIVE_LABELS["7-zip archive"])
    }

    @Test
    fun changedHostIsStale() {
        val dir = tmp("stale")
        val h = host(dir)
        val t = FdTable()
        t.register(h, listOf(ScanHit(0, "ZIP archive", 100, 12)), h.length(), h.lastModified()) { ARCHIVE_LABELS[it] }
        val e = t.get(3)!!
        assertNull("刚扫描完应是新鲜的", t.checkFresh(e))
        // 宿主被改写 → 偏移量已无意义，必须报错而不是静默读出垃圾
        RandomAccessFile(h, "rw").use { it.setLength(9999) }
        assertTrue("宿主变了必须 stale", t.checkFresh(e) is FdTable.Rejected.Stale)
        dir.deleteRecursively()
    }

    @Test
    fun deletedHostIsStale() {
        val dir = tmp("gone")
        val h = host(dir)
        val t = FdTable()
        t.register(h, listOf(ScanHit(0, "ZIP archive", 100, 12)), h.length(), h.lastModified()) { ARCHIVE_LABELS[it] }
        val e = t.get(3)!!
        h.delete()
        assertTrue(t.checkFresh(e) is FdTable.Rejected.Stale)
        dir.deleteRecursively()
    }

    @Test
    fun rescanRebuildsTheWholeTable() {
        val dir = tmp("rescan")
        val h = host(dir)
        val t = FdTable()
        t.register(h, listOf(ScanHit(0, "ZIP archive", 100, 12), ScanHit(256, "PNG image", 50, null)),
            h.length(), h.lastModified()) { ARCHIVE_LABELS[it] }
        assertEquals(2, t.size)
        // 重新扫描整表重建，旧的 fd 不该残留
        t.register(h, listOf(ScanHit(0, "GIF image", 20, null)), h.length(), h.lastModified()) { ARCHIVE_LABELS[it] }
        assertEquals(1, t.size)
        assertNull("旧 fd4 已被顶掉", t.get(4))
        dir.deleteRecursively()
    }

    @Test
    fun looksLikeFdOnlyAcceptsFPrefix() {
        val t = FdTable()
        assertTrue(t.looksLikeFd("f3"))
        assertTrue(t.looksLikeFd("f0"))
        assertTrue(t.looksLikeFd("f123"))
        assertTrue("普通文件名不是 fd", !t.looksLikeFd("f.zip"))
        assertTrue(!t.looksLikeFd("archive"))
        assertTrue(!t.looksLikeFd("3"))
    }

    @Test
    fun byteSizeFallsBackToHostLengthWhenUnknown() {
        val dir = tmp("size")
        val h = host(dir, size = 2048)
        val t = FdTable()
        // size = null（scan-core 没验出边界）→ 到宿主末尾
        t.register(h, listOf(ScanHit(1024, "PNG image", null, null)), h.length(), h.lastModified()) { ARCHIVE_LABELS[it] }
        assertEquals(1024L, t.get(3)!!.byteSize())
        dir.deleteRecursively()
    }
}
