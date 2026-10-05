package com.usefulunpacker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `uu` 文件命令（hash / copy / rename）的契约。这些命令只用 [java.io.File]，
 * prefs 传 null 即可在纯 JVM 下断言行为。
 */
class UuCommandsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ctx(cwd: File, pw: String? = null) = UuCommands.Ctx(
        prefs = null, cwd = cwd, password = pw,
        str = { id, _ -> "!str:$id" },
    )

    private fun text(r: UuCommands.Result) = r.text

    /**
     * dispatch 的 argv **不含** "uu" 前缀（终端层在 tokens.drop(1) 之后传入）——
     * 这里对齐真实调用契约。
     */
    private fun argv(vararg a: String) = a.toList()

    // ── copy ──

    @Test
    fun copyFileThenRefuseOverwrite() {
        val src = tmp.newFile("a.txt").apply { writeText("hello") }
        val dst = File(tmp.root, "b.txt")
        val r1 = UuCommands.dispatch(argv("cp", src.name, "b.txt"), ctx(tmp.root))
        assertEquals(0, r1.exitCode)
        assertEquals("hello", dst.readText())
        // dst 已存在 → 拒绝且不破坏目标
        val r2 = UuCommands.dispatch(argv("cp", src.name, "b.txt"), ctx(tmp.root))
        assertTrue(r2.exitCode != 0)
        assertEquals("hello", dst.readText())
    }

    @Test
    fun copyIntoExistingDirUsesCpSemantics() {
        val src = tmp.newFile("a.txt").apply { writeText("x") }
        val dir = tmp.newFolder("d")
        val r = UuCommands.dispatch(argv("cp", src.name, "d"), ctx(tmp.root))
        assertEquals(0, r.exitCode)
        assertTrue(File(dir, "a.txt").isFile)
    }

    @Test
    fun copyDirectoryRecursively() {
        val sub = tmp.newFolder("srcdir").resolve("nested").apply { mkdirs() }
        File(sub, "deep.txt").writeText("deep")
        val r = UuCommands.dispatch(argv("cp", "srcdir", "dstdir"), ctx(tmp.root))
        assertEquals(0, r.exitCode)
        assertEquals("deep", File(tmp.root, "dstdir/nested/deep.txt").readText())
    }

    // ── rename ──

    @Test
    fun renameMovesAndRefusesExistingTarget() {
        val a = tmp.newFile("old.123").apply { writeText("v") }
        tmp.newFile("occupied.txt").apply { writeText("busy") }
        val r1 = UuCommands.dispatch(argv("rn", "old.123", "new.456"), ctx(tmp.root))
        assertEquals(0, r1.exitCode)
        assertFalse(a.exists())
        assertEquals("v", File(tmp.root, "new.456").readText())
        // 目标已存在（occupied.txt）→ 拒绝且双方原样
        val r2 = UuCommands.dispatch(argv("rn", "new.456", "occupied.txt"), ctx(tmp.root))
        assertTrue(r2.exitCode != 0)
        assertTrue(File(tmp.root, "new.456").exists())
        assertEquals("busy", File(tmp.root, "occupied.txt").readText())
    }

    // ── hash ──

    @Test
    fun hashPrintsMd5AndSha256Hex() {
        val f = tmp.newFile("h.txt").apply { writeText("abc") }
        val r = UuCommands.dispatch(argv("hash", "h.txt"), ctx(tmp.root))
        assertEquals(0, r.exitCode)
        // 已知向量：abc 的 MD5 / SHA-256
        assertTrue(text(r).contains("900150983cd24fb0d6963f7d28e17f72"))
        assertTrue(text(r).contains("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
        assertFalse(text(r).contains("!!"))
    }

    @Test
    fun missingFileReportsNotFound() {
        val r = UuCommands.dispatch(argv("hash", "nope.bin"), ctx(tmp.root))
        assertTrue(r.exitCode != 0)
    }    // ── pack 默认路径 / -c -s 旗标解析 ──

    @Test
    fun packSingleWithoutOutRequiresFormatKey() {
        val dir = tmp.root.resolve("packnf").apply { mkdirs() }
        File(dir, "a.txt").writeText("x")
        val c = UuCommands.Ctx(prefs = null, cwd = dir)
        val r = UuCommands.dispatch(listOf("c", "a.txt"), c)
        assertTrue(r.exitCode != 0)
        assertTrue(r.picker == null)
        // 不猜格式：必须 -f 或产物名后缀
        assertTrue(r.text.contains("!str:") || r.text.contains("uu c"))
    }

    @Test
    fun packMergeAndSeparateMutuallyExclusive() {
        val dir = tmp.root.resolve("packms").apply { mkdirs() }
        val c = UuCommands.Ctx(prefs = null, cwd = dir)
        val r = UuCommands.dispatch(listOf("c", "a", "b", "-c", "-s", "-f", "zip"), c)
        assertTrue(r.exitCode != 0)
    }

    @Test
    fun barePackAsksForFileOrFolderSource() {
        val dir = tmp.root.resolve("packbare").apply { mkdirs() }
        val c = UuCommands.Ctx(prefs = null, cwd = dir)
        val r = UuCommands.dispatch(listOf("c"), c)
        assertEquals(2, r.exitCode)
        assertEquals(UuCommands.Picker.FILE_OR_FOLDER, r.picker)
    }

    // ── fN 引用（hash / cp 走 FD 表）──

    @Test
    fun hashAndCopyResolveFNDescriptors() {
        val dir = tmp.root.resolve("fdargs").apply { mkdirs() }
        val f = File(dir, "game.zip").apply { writeBytes("archive-bytes".toByteArray()) }
        val t = FdTable()
        assertEquals(listOf(0), t.registerFiles(listOf(f)))
        val c = UuCommands.Ctx(prefs = null, cwd = dir, fds = t, cacheDir = dir)

        val h = UuCommands.dispatch(listOf("hash", "f0"), c)
        assertEquals(0, h.exitCode)
        // "archive-bytes" 的 MD5 已知向量，证明读到的是 f0 指向的那个文件
        assertTrue(h.text.contains("bc450cd98c2921b77a78a6459c9b032a"))

        val dst = File(dir, "out.zip")
        val cp = UuCommands.dispatch(listOf("cp", "f0", "out.zip"), c)
        assertEquals(0, cp.exitCode)
        assertTrue(dst.isFile)
        assertEquals("archive-bytes", dst.readText())
        dir.deleteRecursively()
    }

    @Test
    fun packWithoutOutNameShowsUsage() {
        val dir = tmp.root.resolve("packusage").apply { mkdirs() }
        val c = UuCommands.Ctx(prefs = null, cwd = dir)
        val r = UuCommands.dispatch(listOf("c", "somedir"), c)
        // 缺产物名：不猜格式，报「需要 -f」且不弹选择器（选择器只用于补「源」）
        assertTrue(r.exitCode != 0)
        assertTrue(r.picker == null)
        dir.deleteRecursively()
    }

}
