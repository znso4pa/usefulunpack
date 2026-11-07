package com.usefulunpacker

import com.usefulunpacker.fileops.DiskScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [DiskScanner] 的契约。纯 `java.io.File`，JVM 上直接跑（不起 Android）。
 *
 * 这里盯的是三件容易写错的事：**子树大小回溯得到**（只走一趟的前提）、
 * **目录按大小排序**（结果页第一眼的顺序）、**深度兜底不漏算**（超深目录只累加不建节点，
 * 但总量必须还是对的）。
 */
class DiskUsageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun mk(parent: File, name: String, bytes: Int): File =
        File(parent, name).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(bytes))
        }

    @Test
    fun subtreeSizesAreSummedAndDirsSortedBySize() {
        val root = tmp.newFolder("root")
        // big/ = 3000 + 1000 = 4000；small/ = 500；根下散文件 200。
        val big = File(root, "big").apply { mkdirs() }
        mk(big, "a.bin", 3000)
        val bigSub = File(big, "sub").apply { mkdirs() }
        mk(bigSub, "b.bin", 1000)
        val small = File(root, "small").apply { mkdirs() }
        mk(small, "c.bin", 500)
        mk(root, "loose.bin", 200)

        val scan = DiskScanner(topN = 10).scan(root)!!
        assertEquals(4700L, scan.bytes)
        assertEquals(4, scan.files)
        // 直接子目录只有 big / small，大的在前；sub 挂在 big 下面。
        assertEquals(listOf("big", "small"), scan.root.dirs.map { it.dir.name })
        assertEquals(4000L, scan.root.dirs[0].bytes)
        assertEquals(listOf("sub"), scan.root.dirs[0].dirs.map { it.dir.name })
        assertEquals(1000L, scan.root.dirs[0].dirs[0].bytes)
        // 全都读得了 → 不该有「未计入」的提示（结果页据此决定要不要显示那一行）。
        assertEquals(0, scan.deniedDirs)
    }

    @Test
    fun globalTopFilesAreTheLargestAcrossTheWholeTree() {
        val root = tmp.newFolder("top")
        mk(root, "tiny.bin", 10)
        val deep = File(root, "d1/d2").apply { mkdirs() }
        mk(deep, "huge.bin", 900)
        mk(root, "mid.bin", 100)

        val scan = DiskScanner(topN = 2).scan(root)!!
        assertEquals(listOf("huge.bin", "mid.bin"), scan.topFiles.map { it.second.name })
        // 降序
        assertTrue(scan.topFiles[0].first >= scan.topFiles[1].first)
    }

    @Test
    fun eachDirKeepsItsOwnLargestFiles() {
        val root = tmp.newFolder("perdir")
        mk(root, "r1.bin", 30)
        mk(root, "r2.bin", 20)
        mk(root, "r3.bin", 10)
        mk(root, "r4.bin", 5)
        val sub = File(root, "sub").apply { mkdirs() }
        mk(sub, "s1.bin", 7)

        val scan = DiskScanner(topN = 0).scan(root)!!
        val names = scan.root.topFiles.map { it.second.name }
        assertEquals(DiskScanner.TOP_FILES_PER_DIR, names.size)
        // 只留最大的 3 个，r4(5 B) 被挤掉；降序。
        assertEquals(listOf("r1.bin", "r2.bin", "r3.bin"), names)
        assertEquals(listOf("s1.bin"), scan.root.dirs[0].topFiles.map { it.second.name })
    }

    @Test
    fun cancelReturnsNull() {
        val root = tmp.newFolder("cancel")
        mk(root, "a.bin", 1)
        assertNull(DiskScanner(isCancelled = { true }).scan(root))
    }

    /**
     * **在任何一个检查点取消，都不能拿到「扫了一半」的结果。**
     *
     * 盯的是 `weigh()`（超深目录那段）那条路径：它遇到取消只是 `break` 出循环、返回部分结果，
     * 不经过 `walk` 的返回值 —— 被取消的目录如果正好是最后一个子项，`walk` 会一路正常回溯，
     * 调用方就会拿到一份残缺数据（用户点了取消，却看到结果页）。
     *
     * 不写死「第几次检查」，先跑一遍数出总检查次数，再对每个点逐一取消 —— 实现怎么改都不会假红。
     */
    @Test
    fun cancelAtAnyCheckpointNeverYieldsPartialResult() {
        val root = tmp.newFolder("sweep")
        // 比 MAX_DEPTH 深两层 → 一定会走到 weigh()。
        var cur = root
        repeat(DiskScanner.MAX_DEPTH + 2) { i ->
            cur = File(cur, "l$i").apply { mkdirs() }
        }
        mk(cur, "leaf.bin", 512)
        // ⚠️ 根下**不放**兄弟节点：放在这里的话，深链走完后 root 的循环还会再查一次取消，
        // 把「weigh 里取消留下的半截结果」又变回 null，这条测试就抓不到那个 bug 了
        // （实测：加一个 top.bin 之后，去掉兜底也能全绿）。

        var total = 0
        DiskScanner(isCancelled = { total++; false }).scan(root)
        assertTrue("这棵树应当有若干个检查点，实际 $total", total > 4)

        // 取到 total-1：最后一个检查点正好在 weigh 里，必须被覆盖到。
        for (n in 1 until total) {
            var calls = 0
            val scan = DiskScanner(isCancelled = { ++calls > n }).scan(root)
            assertNull("第 $n 个检查点取消后仍返回了结果", scan)
        }
    }

    @Test
    fun tooDeepDirsAreSummedWithoutNodes() {
        val root = tmp.newFolder("deep")
        var cur = root
        // 比 MAX_DEPTH 深两层 —— 最后那层不该建节点，但字节数必须算进去。
        repeat(DiskScanner.MAX_DEPTH + 2) { i ->
            cur = File(cur, "lvl$i").apply { mkdirs() }
        }
        mk(cur, "bottom.bin", 1234)

        val scan = DiskScanner(topN = 4).scan(root)
        assertNotNull(scan)
        assertEquals(1234L, scan!!.bytes)
        assertEquals(1, scan.files)
        assertTrue("超深目录应被记进 truncatedDirs", scan.truncatedDirs > 0)
        // 树本身不该长出 MAX_DEPTH 层以上。
        var depth = 0
        var n = scan.root
        while (n.dirs.isNotEmpty()) {
            depth++
            n = n.dirs[0]
        }
        assertTrue("节点深度不应超过上限，实际 $depth", depth <= DiskScanner.MAX_DEPTH)
    }

    @Test
    fun unreadableDirDoesNotAbortTheScan() {
        // 不真造「读不了」的目录（JVM 上 chmod 行为不跨平台）—— 用一个不存在的路径
        // 走同一条 runCatching 分支：walk 会拿到 null，返回一个 0 字节的空节点。
        val missing = File(tmp.root, "nope")
        val scan = DiskScanner().scan(missing)
        assertNotNull(scan)
        assertEquals(0L, scan!!.bytes)
        assertEquals(0, scan.files)
        // 同时必须记一笔 —— 否则真机上「扫出 85 GB、实际已用 189 GB」就无从解释
        // （`Android/data` 对 App 不可读，见 DiskScan.deniedDirs）。
        assertEquals(1, scan.deniedDirs)
    }
}
