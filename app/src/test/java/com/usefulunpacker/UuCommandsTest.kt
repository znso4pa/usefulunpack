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

    /** str stub 会把参数拼进文本（"[arg]"），便于断言格式化结果。 */
    private fun argStr(): StrFn = { id, args -> "!str:$id" + args.joinToString("") { "[$it]" } }

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

    @Test
    fun copyRefusesDirectoryIntoItself() {
        val outer = tmp.newFolder("mydir")
        File(outer, "f.txt").writeText("v")
        // cp mydir mydir/inner → 必须拒绝而不是无限自嵌套
        val r = UuCommands.dispatch(argv("cp", "mydir", "mydir/inner"), ctx(tmp.root))
        assertTrue(r.exitCode != 0)
        assertEquals("v", File(outer, "f.txt").readText())
    }

    // ── 通配符展开 / 新文件命令 ──

    @Test
    fun expandGlobsMatchesCwdAndKeepsUnmatchedLiterals() {
        val dir = tmp.root.resolve("glob").apply { mkdirs() }
        File(dir, "1.zip").writeBytes(ByteArray(4))
        File(dir, "2.zip").writeBytes(ByteArray(4))
        File(dir, "a.txt").writeBytes(ByteArray(4))
        val ex = UuCommands.expandGlobs(listOf("uu", "x", "*.zip", "keep.rar"), dir)
        assertEquals(5, ex.size)   // uu, x, 1.zip, 2.zip, keep.rar(保留)
        assertEquals("uu", ex[0])
        assertEquals("x", ex[1])
        assertTrue(ex[2].endsWith("1.zip"))
        assertTrue(ex[3].endsWith("2.zip"))
        assertEquals("keep.rar", ex[4])
        // 无匹配 → 保留原样
        val ex2 = UuCommands.expandGlobs(listOf("uu", "x", "nope*.rar"), dir)
        assertEquals("nope*.rar", ex2[2])
    }

    @Test
    fun rmForceDeletesAndReports() {
        val dir = tmp.root.resolve("rmf").apply { mkdirs() }
        val a = File(dir, "a.txt").apply { writeText("x") }
        val sub = File(dir, "sub").apply { mkdirs() }
        File(sub, "b.txt").writeText("y")
        val c = UuCommands.Ctx(prefs = null, cwd = dir)
        val r = UuCommands.dispatch(listOf("rm", "a.txt", "sub", "-f"), c)
        assertEquals(0, r.exitCode)
        assertFalse(a.exists())
        assertFalse(sub.exists())
        // 无 -f 且无 activity → 需要 UI 环境
        File(dir, "keep.txt").writeText("k")
        val r2 = UuCommands.dispatch(listOf("rm", "keep.txt"), c)
        assertEquals(2, r2.exitCode)
        assertTrue(File(dir, "keep.txt").exists())
    }

    @Test
    fun mvMovesAtomicallyAndRefusesOverwriteAndSelfNesting() {
        val dir = tmp.root.resolve("mv").apply { mkdirs() }
        File(dir, "src.txt").writeText("v")
        val c = UuCommands.Ctx(prefs = null, cwd = dir)
        assertEquals(0, UuCommands.dispatch(listOf("mv", "src.txt", "dst.txt"), c).exitCode)
        assertEquals("v", File(dir, "dst.txt").readText())
        // 覆盖拒绝
        File(dir, "other.txt").writeText("o")
        assertTrue(UuCommands.dispatch(listOf("mv", "other.txt", "dst.txt"), c).exitCode != 0)
        // 目录进自己拒绝
        val d = File(dir, "mydir").apply { mkdirs() }
        assertTrue(UuCommands.dispatch(listOf("mv", "mydir", "mydir/inner"), c).exitCode != 0)
    }

    @Test
    fun mkdirTreeDuStatWork() {
        val dir = tmp.root.resolve("misc").apply { mkdirs() }
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        assertEquals(0, UuCommands.dispatch(listOf("mkdir", "a/b/c", "x"), c).exitCode)
        assertTrue(File(dir, "a/b/c").isDirectory)
        File(dir, "x/f1.bin").writeBytes(ByteArray(100))
        File(dir, "x/f2.bin").writeBytes(ByteArray(50))
        val du = UuCommands.dispatch(listOf("du", "x"), c)
        assertEquals(0, du.exitCode)
        assertTrue(du.text.contains("[150 B]"))
        val tree = UuCommands.dispatch(listOf("tree", "."), c)
        assertEquals(0, tree.exitCode)
        assertTrue(tree.text.contains("x/"))
        val st = UuCommands.dispatch(listOf("stat", "x/f1.bin"), c)
        assertTrue(st.text.contains("[file]"))
    }

    @Test
    fun grepFindsMatchesCaseInsensitively() {
        val dir = tmp.root.resolve("grep").apply { mkdirs() }
        File(dir, "s1.txt").writeText("Hello World\nsecond line")
        File(dir, "s2.ks").writeText("no hit here")
        File(dir, "bin.dat").writeBytes(ByteArray(16))
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        // 大小写敏感：hello 不匹配 Hello
        val r0 = UuCommands.dispatch(listOf("grep", "hello", "."), c)
        assertTrue(r0.text.contains("!str:"))  // cli_grep_none
        val r = UuCommands.dispatch(listOf("grep", "Hello", "."), c)
        assertEquals(0, r.exitCode)
        assertTrue(r.text.contains("[1][1]"))          // 1 处匹配 / 1 个文件
        assertTrue(r.text.contains("s1.txt:1"))        // 命中行
        val r2 = UuCommands.dispatch(listOf("grep", "-i", "HELLO", "."), c)
        assertTrue(r2.text.contains("[1][1]"))
        val r3 = UuCommands.dispatch(listOf("grep", "zzz-nope", "."), c)
        assertEquals(0, r3.exitCode)
        assertTrue(r3.text.contains("!str:"))  // cli_grep_none stub
    }

}
