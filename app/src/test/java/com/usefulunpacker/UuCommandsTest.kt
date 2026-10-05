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

    @Test
    fun docsListsEveryCommandAndParameters() {
        val dir = tmp.root.resolve("docs").apply { mkdirs() }
        val c = UuCommands.Ctx(prefs = null, cwd = dir)
        val r = UuCommands.dispatch(listOf("docs"), c)
        assertEquals(0, r.exitCode)
        // 表格派生自同一张命令表:抽查若干命令都在
        for (name in listOf("cat", "grep", "rm", "set", "mv", "tree", "du", "stat", "mkdir", "cso")) {
            assertTrue("missing $name", r.text.contains("uu $name"))
        }
        assertTrue(r.text.contains("PARAMETERS"))
        assertTrue(r.text.contains("-p <password>"))
        assertTrue(r.text.contains("fN"))
        // 列宽契约：最长的 usage（uu c，68 字符）后面至少留 2 空格，禁止与说明粘连
        assertTrue(r.text.contains("[-p pw]  Pack files or folders"))
        // UUT 段落必须在使用文档里（`uu run` 的语法只有这一处可查）
        assertTrue(r.text.contains("UUT SCRIPT"))
        for (kw in listOf(
            "set v = text", "if v = xp3 then", "for a in *.zip", "only uu / ls / cd",
            // v2：位置参数 / 块式 if-else / return
            "\$1 \$2 / \$argc / \$args", "if v = xp3 / else / end", "return [code]",
        )) {
            assertTrue("missing UUT doc: $kw", r.text.contains(kw))
        }
    }

    // ── Round 1：参数缺口回归 ──

    @Test
    fun flagMissingValueErrorsInsteadOfEmptyPassword() {
        val dir = tmp.root.resolve("flagmiss").apply { mkdirs() }
        File(dir, "a.zip").writeBytes(ByteArray(8))
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        // `-p` 在末尾无值：必须报「缺值」，而不是变成空密码吞掉弹窗
        val r = UuCommands.dispatch(listOf("x", "a.zip", "-p"), c)
        assertEquals(1, r.exitCode)
        assertTrue(r.text.contains("!str:"))
        assertTrue(r.text.contains("[-p]"))
    }

    @Test
    fun unknownFlagRejectedInsteadOfBecomingPath() {
        val dir = tmp.root.resolve("unkflag").apply { mkdirs() }
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        val r = UuCommands.dispatch(listOf("mkdir", "-p", "a"), c)
        assertEquals(1, r.exitCode)
        assertTrue(r.text.contains("[-p]"))
        assertFalse("flag must not become a dir", File(dir, "-p").exists())
        assertFalse("mkdir must not run", File(dir, "a").exists())
    }

    @Test
    fun hashHandlesMultipleArguments() {
        val dir = tmp.root.resolve("hashmulti").apply { mkdirs() }
        File(dir, "a.txt").writeText("abc")
        File(dir, "b.txt").writeText("abc")
        val c = UuCommands.Ctx(prefs = null, cwd = dir)
        val r = UuCommands.dispatch(listOf("hash", "a.txt", "b.txt"), c)
        assertEquals(0, r.exitCode)
        // 两个文件都产出（各自带头部行），abc 的已知向量出现两次
        assertEquals(2, Regex("900150983cd24fb0d6963f7d28e17f72").findAll(r.text).count())
        assertTrue(r.text.contains("a.txt:"))
        assertTrue(r.text.contains("b.txt:"))
    }

    @Test
    fun infoHandlesMultipleArguments() {
        val dir = tmp.root.resolve("infomulti").apply { mkdirs() }
        File(dir, "a.bin").writeBytes(ByteArray(4))
        File(dir, "b.bin").writeBytes(ByteArray(4))
        val c = UuCommands.Ctx(prefs = null, cwd = dir)
        val r = UuCommands.dispatch(listOf("info", "a.bin", "b.bin"), c)
        assertEquals(0, r.exitCode)
        // 两个非归档各输出一个空行 → 两行
        assertEquals(2, r.text.split("\n").size)
    }

    @Test
    fun duSumsMultipleArguments() {
        val dir = tmp.root.resolve("dumulti").apply { mkdirs() }
        File(dir, "a.bin").writeBytes(ByteArray(100))
        File(dir, "b.bin").writeBytes(ByteArray(50))
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        val r = UuCommands.dispatch(listOf("du", "a.bin", "b.bin"), c)
        assertEquals(0, r.exitCode)
        assertTrue(r.text.contains("[150 B][2]"))  // 汇总行
    }

    @Test
    fun listConsumesDashPFlagInsteadOfTreatingItAsArchive() {
        val dir = tmp.root.resolve("listp").apply { mkdirs() }
        File(dir, "a.zip").writeBytes(ByteArray(8))
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        // 无 activity 时列表失败（listFailed），但绝不能是「找不到 -p」
        val r = UuCommands.dispatch(listOf("l", "-p", "secret", "a.zip"), c)
        assertFalse(r.text.contains("[-p]"))
    }

    @Test
    fun packRejectsThreePositionalsWithoutFlags() {
        val dir = tmp.root.resolve("c3pos").apply { mkdirs() }
        for (n in listOf("a.txt", "b.txt", "c.txt")) File(dir, n).writeText("x")
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        val r = UuCommands.dispatch(listOf("c", "a.txt", "b.txt", "c.txt"), c)
        assertEquals(1, r.exitCode)
        assertTrue(r.text.contains("!str:"))  // cli_pack_multi_needs_flag
    }

    // ── Round 2：enc / find / hex / diff ──

    @Test
    fun encConvertsShiftJisToUtf8AndKeepsBom() {
        val dir = tmp.root.resolve("enc").apply { mkdirs() }
        val sjis = File(dir, "a.txt")
        sjis.writeBytes(encodeText("日本語テスト", "SHIFT-JIS", false))
        val c = UuCommands.Ctx(prefs = null, cwd = dir)
        val r = UuCommands.dispatch(listOf("enc", "a.txt", "auto", "utf-8"), c)
        assertEquals(0, r.exitCode)
        val out = File(dir, "a-utf8.txt")
        assertTrue(out.isFile)
        assertEquals("日本語テスト", decodeTextStrict(out.readBytes(), "UTF-8"))
        assertFalse("目标为无 BOM 源时不加 BOM", hasBom(out.readBytes()))
        // 源带 BOM → 输出保真
        val bomFile = File(dir, "b.txt").apply { writeBytes(encodeText("x", "UTF-16", true)) }
        val r2 = UuCommands.dispatch(listOf("enc", "b.txt", "utf-16", "utf-8"), c)
        assertEquals(0, r2.exitCode)
        assertTrue(hasBom(File(dir, "b-utf8.txt").readBytes()))
    }

    @Test
    fun encRejectsUnknownEncoding() {
        val dir = tmp.root.resolve("encbad").apply { mkdirs() }
        File(dir, "a.txt").writeText("x")
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        val r = UuCommands.dispatch(listOf("enc", "a.txt", "auto", "klingon"), c)
        assertEquals(1, r.exitCode)
        assertTrue(r.text.contains("[klingon]"))
    }

    @Test
    fun findMatchesRecursivelyWithDepthLimit() {
        val dir = tmp.root.resolve("find").apply { mkdirs() }
        File(dir, "a/b/deep").mkdirs()
        File(dir, "a/x.ks").writeText("1")
        File(dir, "a/b/y.ks").writeText("2")
        File(dir, "a/b/deep/z.ks").writeText("3")
        val c = UuCommands.Ctx(prefs = null, cwd = dir)
        // 全深度（默认 6）：三个都命中
        val r = UuCommands.dispatch(listOf("find", "a", "*.ks"), c)
        assertEquals(0, r.exitCode)
        // 路径相对 root 输出
        assertTrue(r.text.contains("x.ks"))
        assertTrue(r.text.contains("b/deep/z.ks"))
        // 深度 1：只有第一层
        val r2 = UuCommands.dispatch(listOf("find", "a", "*.ks", "1"), c)
        assertTrue(r2.text.contains("x.ks"))
        assertFalse(r2.text.contains("y.ks"))
    }

    @Test
    fun hexDumpsRangeWithHexNumbers() {
        val dir = tmp.root.resolve("hex").apply { mkdirs() }
        val f = File(dir, "a.bin")
        f.writeBytes(byteArrayOf(0x41, 0x42, 0x00, 0x7F, 0x0A))
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        val r = UuCommands.dispatch(listOf("hex", "a.bin", "0x1", "3"), c)
        assertEquals(0, r.exitCode)
        assertTrue(r.text.contains("42 00 7F"))
        assertTrue(r.text.contains("B.."))  // ascii 列
    }

    @Test
    fun diffOfClassifiesAddedRemovedChanged() {
        fun e(p: String, sz: Long, dir: Boolean = false) =
            ArchiveEntry(p, p.substringAfterLast('/'), sz, dir, false, 0)
        val a = listOf(e("x.txt", 10), e("gone.txt", 5), e("chg.txt", 100), e("d", 0, true))
        val b = listOf(e("x.txt", 10), e("new.txt", 7), e("chg.txt", 200), e("d", 0, true))
        val (onlyA, onlyB, changed, _, _) = UuCommands.diffOf(a, b)
        assertEquals(setOf("gone.txt"), onlyA)
        assertEquals(setOf("new.txt"), onlyB)
        assertEquals(listOf("chg.txt"), changed)
    }

    // ── Round 3：管道/分段/过滤器/脚本行 ──

    @Test
    fun pipeFilterGrepHeadTailWc() {
        val input = "alpha\nbeta\nmain.ks\ngamma\nmain2.ks"
        // grep 大小写敏感/不敏感
        assertEquals("main.ks\nmain2.ks", UuCommands.pipeFilter(listOf("grep", "main"), input)!!.first)
        assertEquals("alpha", UuCommands.pipeFilter(listOf("grep", "-i", "ALPHA"), input)!!.first)
        assertEquals("beta", UuCommands.pipeFilter(listOf("grep", "-i", "BETA"), input)!!.first)
        assertEquals(1, UuCommands.pipeFilter(listOf("grep", "zzz"), input)!!.second)
        // head / tail
        assertEquals("alpha\nbeta", UuCommands.pipeFilter(listOf("head", "2"), input)!!.first)
        assertEquals("main2.ks", UuCommands.pipeFilter(listOf("tail", "1"), input)!!.first)
        // wc -l
        assertEquals("5", UuCommands.pipeFilter(listOf("wc", "-l"), input)!!.first)
        // 不支持的过滤器 → null
        assertEquals(null, UuCommands.pipeFilter(listOf("sort"), input))
    }

    @Test
    fun splitSegmentsHandlesAndAlsoAndSemicolon() {
        val segs = UuCommands.splitSegments(listOf("a", "&&", "b", ";", "c"))
        assertEquals(3, segs.size)
        assertEquals(listOf("a"), segs[0].tokens)
        assertFalse(segs[0].andAlso)
        assertEquals(listOf("b"), segs[1].tokens)
        assertTrue(segs[1].andAlso)   // b 需 a 成功
        assertEquals(listOf("c"), segs[2].tokens)
        assertFalse(segs[2].andAlso)  // c 无条件
    }

    @Test
    fun splitOnPipeKeepsStages() {
        val stages = UuCommands.splitOnPipe(listOf("uu", "l", "a.zip", "|", "grep", "main"))
        assertEquals(2, stages.size)
        assertEquals(listOf("uu", "l", "a.zip"), stages[0])
        assertEquals(listOf("grep", "main"), stages[1])
        assertEquals(1, UuCommands.splitOnPipe(listOf("ls")).size)
    }

    @Test
    fun shellQuoteProtectsSpecials() {
        assertEquals("abc", UuCommands.shellQuote("abc"))
        assertEquals("'a b'", UuCommands.shellQuote("a b"))
        assertEquals("'a;b'", UuCommands.shellQuote("a;b"))
        assertEquals("'it'\\''s'", UuCommands.shellQuote("it's"))
    }

    @Test
    fun scriptLinesSkipCommentsAndBlanks() {
        val lines = UuCommands.scriptLines("# comment\n\nuu x a.zip\n   \nuu c a -f zip\n")
        assertEquals(2, lines.size)
        assertEquals(3 to "uu x a.zip", lines[0])
        assertEquals(5 to "uu c a -f zip", lines[1])
    }

    // ─── 通配展开的位置排除（模式参数不能被展成命中文件） ──────────────────

    @Test
    fun copyAndMvRejectExtraPositionalArgs() {
        // 多源被静默忽略过一次：`uu cp a.txt b.txt dst/` 报"已存在 b.txt"，
        // 用户以为两个都拷了。必须明确报用法错。
        val c = UuCommands.Ctx(prefs = null, cwd = tmp.root)
        assertEquals(2, UuCommands.dispatch(listOf("cp", "a.txt", "b.txt", "dst"), c).exitCode)
        assertEquals(2, UuCommands.dispatch(listOf("mv", "a.txt", "b.txt", "dst"), c).exitCode)
    }

    @Test
    fun globSkipUnderstandsUuPrefixedCommands() {
        // 真实命令行首 token 是 uu（`uu find . *.txt 1`），子命令在 index 1
        assertEquals(setOf(2, 3, 4), UuCommands.globSkipIndices(listOf("uu", "find", ".", "*.txt", "1")))
        assertEquals(setOf(2), UuCommands.globSkipIndices(listOf("uu", "grep", "alpha", "*.txt")))
        assertEquals(setOf(3), UuCommands.globSkipIndices(listOf("uu", "grep", "-i", "alpha", "*.txt")))
        assertEquals(emptySet<Int>(), UuCommands.globSkipIndices(listOf("uu", "x", "*.zip")))
    }

    @Test
    fun globSkipCoversFindGlobAndGrepPattern() {
        // find 的位置参数全是模式语境（单参形态下 token1 就是 glob）
        assertEquals(setOf(1, 2, 3), UuCommands.globSkipIndices(listOf("find", ".", "*.txt", "2")))
        assertEquals(setOf(1), UuCommands.globSkipIndices(listOf("find", "*.txt")))
        // grep：pattern 在 -i 之后顺延一位
        assertEquals(setOf(1), UuCommands.globSkipIndices(listOf("grep", "abc", "*.txt")))
        assertEquals(setOf(2), UuCommands.globSkipIndices(listOf("grep", "-i", "abc", "*.txt")))
        // 其余命令不排除任何位置（x/c/hash 的路径参数照常展开）
        assertEquals(emptySet<Int>(), UuCommands.globSkipIndices(listOf("hash", "*.png")))
        assertEquals(emptySet<Int>(), UuCommands.globSkipIndices(emptyList()))
    }

    @Test
    fun expandGlobsHonorsSkipSet() {
        val dir = tmp.root
        File(dir, "a.txt").writeText("a")
        File(dir, "b.txt").writeText("b")
        // 不排除：token1 展开成两个绝对路径
        val all = UuCommands.expandGlobs(listOf("x", "*.txt"), dir)
        assertEquals(3, all.size)
        // 排除 token1：模式原样保留（find/grep 的语义）
        val kept = UuCommands.expandGlobs(listOf("x", "*.txt"), dir, setOf(1))
        assertEquals(listOf("x", "*.txt"), kept)
        // token0 永不展开（那是命令名，即使含通配符也原样保留）
        val head = UuCommands.expandGlobs(listOf("*.txt", "*.txt"), dir)
        assertEquals("*.txt", head[0])
        assertEquals(3, head.size)
    }

    // ─── 第五批：uu fd（描述符表列出） ─────────────────────────────────────

    @Test
    fun fdListsWholeFileAndRangeEntries() {
        val dir = tmp.root.resolve("fdt").apply { mkdirs() }
        val a = File(dir, "a.zip").apply { writeBytes(ByteArray(16) { it.toByte() }) }
        val host = File(dir, "blob.bin").apply { writeBytes(ByteArray(64) { 7 }) }
        val t = FdTable()
        val whole = t.registerFiles(listOf(a))[0]
        val range = t.register(host, listOf(ScanHit(8, "ZIP archive", 16, null)), host.length(), host.lastModified()) { null }[0]

        // str 用 argStr()：默认 stub 会把参数丢掉，断言不到文件名/大小
        val c = UuCommands.Ctx(prefs = null, cwd = dir, fds = t, str = argStr())
        val all = UuCommands.dispatch(listOf("fd"), c)
        assertEquals("text=" + all.text, 0, all.exitCode)
        assertTrue(all.text, all.text.contains("[${whole}]"))   // 格式串自带 f，不能传 "f0"
        assertTrue(all.text, all.text.contains("[a.zip]"))
        assertTrue(all.text, all.text.contains("[${range}]"))
        assertTrue(all.text, all.text.contains("[blob.bin]"))   // 区间要带宿主文件名
        assertTrue(all.text, all.text.contains("[8]"))     // 区间偏移（十六进制）
        assertTrue(all.text, all.text.contains("[2]"))     // 共 2 个描述符

        // 单个 fN 过滤
        val one = UuCommands.dispatch(listOf("fd", "f$range"), c)
        assertEquals(0, one.exitCode)
        assertFalse(one.text, one.text.contains("[a.zip]"))
        // 不存在的编号 → 报错退出码 1
        assertEquals(1, UuCommands.dispatch(listOf("fd", "f99"), c).exitCode)
    }

    @Test
    fun fdOnEmptyTableSaysSo() {
        val dir = tmp.root.resolve("fde").apply { mkdirs() }
        val c = UuCommands.Ctx(prefs = null, cwd = dir, fds = FdTable())
        val r = UuCommands.dispatch(listOf("fd"), c)
        assertEquals("text=" + r.text, 0, r.exitCode)
        assertTrue(r.text.contains("!str:"))
    }

    // ─── 第六批：uu l -j（脚本接口） ──────────────────────────────────────

    @Test
    fun jsonListingRefusesMultipleArchives() {
        val dir = tmp.root.resolve("jsonl").apply { mkdirs() }
        File(dir, "a.zip").writeBytes(ByteArray(8))
        File(dir, "b.zip").writeBytes(ByteArray(8))
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        val r = UuCommands.dispatch(listOf("l", "-j", "a.zip", "b.zip"), c)
        assertEquals(2, r.exitCode)
        assertTrue(r.text, r.text.contains("[2]"))
    }

    @Test
    fun jsonListingOnNonArchiveStillReportsFormatError() {
        // -j 不是绕过错误处理的开关：非归档照旧报错、退出码 1
        val dir = tmp.root.resolve("jsonl2").apply { mkdirs() }
        File(dir, "x.bin").writeBytes(ByteArray(8) { 1 })
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        assertEquals(1, UuCommands.dispatch(listOf("l", "-j", "x.bin"), c).exitCode)
    }
}
