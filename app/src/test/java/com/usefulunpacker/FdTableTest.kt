package com.usefulunpacker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * FD 表的契约：f0 起始、整文件条目（ls）/ 区间条目（scan）共用一张表、
 * 进程级累积不清表、同文件/同命中复用编号、宿主变更即 stale。
 */
class FdTableTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun host(dir: File, size: Long = 1024): File {
        val f = File(dir, "host_${System.nanoTime()}.bin")
        f.writeBytes(ByteArray(size.toInt()))
        return f
    }

    private fun tmp(tag: String) = File(System.getProperty("java.io.tmpdir"), "uufd_$tag").apply { mkdirs() }

    // ── 编号 ──

    @Test
    fun numberingStartsAtZero() {
        val dir = tmp("start0")
        val h = host(dir)
        val t = FdTable()
        val fds = t.register(h, listOf(ScanHit(0, "ZIP archive", 100, 2), ScanHit(200, "RAR archive", 50, null)), h.length(), h.lastModified()) { ARCHIVE_LABELS[it] }
        // 从 0 开始：f0 / f1
        assertEquals(listOf(0, 1), fds)
        assertEquals("ZIP archive", t.get(0)!!.label)
        assertEquals("RAR archive", t.get(1)!!.label)
        dir.deleteRecursively()
    }

    // ── 整文件条目（ls 注册）──

    @Test
    fun registerFilesAssignsSortedOrderAndReusesNumbers() {
        val dir = tmp("files")
        val a = File(dir, "a.zip").apply { writeBytes(ByteArray(64)) }
        val b = File(dir, "b.zip").apply { writeBytes(ByteArray(64)) }
        val t = FdTable()
        // 名称升序：a→f0, b→f1（排序由调用方决定，表只按输入顺序发号）
        assertEquals(listOf(0, 1), t.registerFiles(listOf(a, b)))
        // 同一文件再注册 → 复用编号（跨 ls 调用稳定）
        assertEquals(listOf(0), t.registerFiles(listOf(a)))
        assertEquals(2, t.registerFiles(listOf(File(dir, "c.zip").apply { writeBytes(ByteArray(8)) }))[0])
        // 整文件条目属性
        val e = t.get(0)!!
        assertTrue(e.wholeFile)
        assertEquals(0L, e.offset)
        assertEquals(a.absolutePath, e.host.absolutePath)
        dir.deleteRecursively()
    }

    @Test
    fun scanReusesExistingRangeAndAccumulates() {
        val dir = tmp("scanacc")
        val h = host(dir)
        val t = FdTable()
        val k: (String) -> String? = { ARCHIVE_LABELS[it] }
        val first = t.register(h, listOf(ScanHit(10, "ZIP archive", 100, 3)), h.length(), h.lastModified(), k)
        assertEquals(listOf(0), first)
        // 同文件同偏移同长度 → 复用 f0；新命中追加 f1；**不清表**
        val second = t.register(h, listOf(ScanHit(10, "ZIP archive", 100, 3), ScanHit(500, "RAR archive", 40, null)), h.length(), h.lastModified(), k)
        assertEquals(listOf(0, 1), second)
        assertEquals(2, t.size())
        // 表里已有条目时 ls 的整文件条目接在后面，互不冲突
        val lsFile = File(dir, "x.zip").apply { writeBytes(ByteArray(16)) }
        assertEquals(listOf(2), t.registerFiles(listOf(lsFile)))
        assertEquals(3, t.size())
        dir.deleteRecursively()
    }

    // ── 失效检查 ──

    @Test
    fun staleWhenHostChanges() {
        val dir = tmp("stale")
        val h = host(dir)
        val t = FdTable()
        val fds = t.register(h, listOf(ScanHit(0, "ZIP archive", 100, 2)), h.length(), h.lastModified()) { ARCHIVE_LABELS[it] }
        val e = t.get(fds[0])!!
        assertNull(t.checkFresh(e))
        // 追加内容 → size 变 → stale
        h.appendBytes(ByteArray(10))
        assertTrue(t.checkFresh(e) is FdTable.Rejected.Stale)
        // 整文件条目同样失效
        val f = File(dir, "w.zip").apply { writeBytes(ByteArray(8)) }
        val fd = t.registerFiles(listOf(f))[0]
        val we = t.get(fd)!!
        assertNull(t.checkFresh(we))
        f.appendBytes(ByteArray(4))
        assertTrue(t.checkFresh(we) is FdTable.Rejected.Stale)
        dir.deleteRecursively()
    }

    // ── fN 前缀 ──

    @Test
    fun looksLikeFdOnlyAcceptsFPrefix() {
        val t = FdTable()
        assertTrue(t.looksLikeFd("f0"))
        assertTrue(t.looksLikeFd("f3"))
        assertTrue(t.looksLikeFd("f123"))
        assertTrue("普通文件名不是 fd", !t.looksLikeFd("f.zip"))
        assertTrue(!t.looksLikeFd("archive"))
        assertTrue(!t.looksLikeFd("3"))
    }

    // ── 大小兜底 ──

    @Test
    fun byteSizeFallsBackToHostLengthWhenUnknown() {
        val dir = tmp("size")
        val h = host(dir, size = 2048)
        val t = FdTable()
        // size = null（scan-core 没验出边界）→ 到宿主末尾
        t.register(h, listOf(ScanHit(1024, "PNG image", null, null)), h.length(), h.lastModified()) { ARCHIVE_LABELS[it] }
        assertEquals(1024L, t.get(0)!!.byteSize())
        dir.deleteRecursively()
    }
}
