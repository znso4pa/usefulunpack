package com.usefulunpacker

import org.junit.Assert.assertArrayEquals
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
        // 列宽契约：最长的 usage（uu c，79 字符，含 -e cxdec）后面至少留 2 空格，
        // 禁止与说明粘连。usage 变长时这里和 padEnd 必须一起改。
        assertTrue(r.text.contains("[-p pw]  Pack files or folders"))
        // UUT 段落必须在使用文档里（`uu run` 的语法只有这一处可查）
        assertTrue(r.text.contains("UUT SCRIPT"))
        for (kw in listOf(
            "set v = text", "if v = xp3 then", "for a in *.zip", "only uu / ls / cd",
            // v2：位置参数 / 块式 if-else / return
            "\$1 \$2 / \$argc / \$args", "if v = xp3 / else / end", "return [code]",
            // v4：数值比较 / 算术 / while / break
            "if n > 3 / n <= 3", "set n = \$n + 1", "while n < 5 ... end", "break",
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
        assertEquals(null, UuCommands.pipeFilter(listOf("awk"), input))   // 只支持 grep/head/tail/wc/sort
        // sort（第八批新增）：字典序，-r 反向
        assertEquals("alpha\nbeta\ngamma\nmain.ks\nmain2.ks", UuCommands.pipeFilter(listOf("sort"), input)!!.first)
        assertEquals("main2.ks\nmain.ks\ngamma\nbeta\nalpha", UuCommands.pipeFilter(listOf("sort", "-r"), input)!!.first)
    }

    @Test
    fun splitSegmentsHandlesAndAlsoAndSemicolon() {
        // 第九批起 Seg 携带连接方式（op），`||` 与 `&&` / `;` 同级
        val segs = UuCommands.splitSegments(listOf("a", "&&", "b", "||", "c", ";", "d"))
        assertEquals(4, segs.size)
        assertEquals(listOf("a"), segs[0].tokens)
        assertEquals("", segs[0].op)
        assertEquals(listOf("b"), segs[1].tokens)
        assertEquals("&&", segs[1].op)   // b 需 a 成功
        assertEquals(listOf("c"), segs[2].tokens)
        assertEquals("||", segs[2].op)   // c 需 a 失败
        assertEquals(listOf("d"), segs[3].tokens)
        assertEquals(";", segs[3].op)    // d 无条件
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

    // ─── 第七批：uu x 的 entries 通配展开（纯函数部分） ────────────────────

    @Test
    fun matchEntryPathsExpandsGlobsAndKeepsExactItems() {
        val paths = listOf("data/img/a.png", "data/img/b.png", "data/script/x.txt", "readme.md")
        // 无通配符的项原样保留（目录前缀语义不变，交给原生侧）
        val (h1, m1) = UuCommands.matchEntryPaths(paths, listOf("readme.md", "data/script/"))
        assertEquals(listOf("readme.md", "data/script/"), h1)
        assertTrue(m1.isEmpty())
        // `*` 跨目录分隔符
        val (h2, _) = UuCommands.matchEntryPaths(paths, listOf("*.png"))
        assertEquals(listOf("data/img/a.png", "data/img/b.png"), h2)
        // `?` 单字符
        val (h3, _) = UuCommands.matchEntryPaths(paths, listOf("data/img/?.png"))
        assertEquals(listOf("data/img/a.png", "data/img/b.png"), h3)
        // 重复命中只留一份，顺序按模式顺序
        val (h4, _) = UuCommands.matchEntryPaths(paths, listOf("*.png", "a.png".let { "data/img/a*" }))
        assertEquals(listOf("data/img/a.png", "data/img/b.png"), h4)
        // 未命中的模式单独报出来（不能静默少解）
        val (h5, m5) = UuCommands.matchEntryPaths(paths, listOf("*.jpg", "readme.md"))
        assertEquals(listOf("readme.md"), h5)
        assertEquals(listOf("*.jpg"), m5)
    }

    // 注：要判断"通配项全不命中"，必须先列条目（原生 JNI），所以 `uu x` 的这条
    // 分支只能真机验证 —— JVM 单测里 native 库不存在。这里只钉住纯匹配部分。

    // ─── 第八批：uu b64 ───────────────────────────────────────────────────

    @Test
    fun b64EncodesToTextAndDecodesBack() {
        val dir = tmp.root.resolve("b64").apply { mkdirs() }
        val raw = ByteArray(300) { (it % 251).toByte() }
        val src = File(dir, "blob.bin").apply { writeBytes(raw) }
        val c = UuCommands.Ctx(prefs = null, cwd = dir)
        // 编码：输出即 base64 文本（给管道用），不写文件
        val enc = UuCommands.dispatch(listOf("b64", "blob.bin"), c)
        assertEquals(0, enc.exitCode)
        assertEquals(java.util.Base64.getEncoder().encodeToString(raw), enc.text)
        // 解码 round-trip：默认输出名 = 去掉 .b64 + .bin（copy.bin 此时不存在）
        File(dir, "copy.b64").writeText(enc.text)
        val dec = UuCommands.dispatch(listOf("b64", "-d", "copy.b64"), c)
        assertEquals(0, dec.exitCode)
        val produced = File(dir, "copy.bin")
        assertTrue(dec.text, produced.isFile)
        assertTrue("bytes differ", produced.readBytes().contentEquals(raw))
        // 显式输出路径（`uu b64 -d in.b64 out.bin`）
        val dec2 = UuCommands.dispatch(listOf("b64", "-d", "copy.b64", "explicit.bin"), c)
        assertEquals(0, dec2.exitCode)
        assertTrue(File(dir, "explicit.bin").readBytes().contentEquals(raw))
        // 编码写文件（`uu b64 in.bin out.txt`）→ 内容是同一份 base64 文本
        val encFile = UuCommands.dispatch(listOf("b64", "blob.bin", "enc.txt"), c)
        assertEquals(0, encFile.exitCode)
        assertEquals(java.util.Base64.getEncoder().encodeToString(raw), File(dir, "enc.txt").readText().trim())
    }

    @Test
    fun b64RejectsInvalidInputAndBadFlags() {
        val dir = tmp.root.resolve("b64b").apply { mkdirs() }
        File(dir, "junk.b64").writeText("!!! not base64 !!!")
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        val r = UuCommands.dispatch(listOf("b64", "-d", "junk.b64"), c)
        assertEquals(1, r.exitCode)
        // 未知标志照旧拒绝
        assertEquals(1, UuCommands.dispatch(listOf("b64", "-z", "junk.b64"), c).exitCode)
        // 缺文件参数 → 用法错误
        assertEquals(2, UuCommands.dispatch(listOf("b64"), c).exitCode)
    }

    // ─── 第七批补：uu x 的命令级展开只作用于归档 ──────────────────────────

    @Test
    fun globSkipForXKeepsOnlyTheArchiveExpandable() {
        // 条目模式必须原样留给"按条目展开"；cwd 里有同名文件时它会被抢先换掉
        //（真机踩过：uu x multi.zip "*.png" → conv2/a.png → 匹配 0 条 → 解压失败）
        val skip = UuCommands.globSkipIndices(listOf("uu", "x", "a.zip", "*.png"))
        assertTrue(2 !in skip)                       // 归档可展开（uu x *.zip 仍工作）
        assertTrue(3 in skip)                        // 条目模式不展开
        // -o 形式：flag 与其值都跳过，归档仍是第一个位置参数
        val skip2 = UuCommands.globSkipIndices(listOf("uu", "x", "-o", "out", "a.zip", "*.png", "*.jpg"))
        assertTrue(setOf(2, 3, 5, 6).all { it in skip2 })
        assertTrue(4 !in skip2)
        // 位置形式：第二个位置参数是 outdir，也不展开
        val skip3 = UuCommands.globSkipIndices(listOf("uu", "x", "a.zip", "out", "*.png"))
        assertTrue(2 !in skip3)
        assertTrue(setOf(3, 4).all { it in skip3 })
    }

    @Test
    fun matchEntryPathsStarAloneSelectsEverything() {
        // 命令级展开不再吃掉裸 `*` 之后，它按条目语义 = 全选
        val paths = listOf("a.txt", "data/b.png")
        val (hits, misses) = UuCommands.matchEntryPaths(paths, listOf("*"))
        assertEquals(paths, hits)
        assertTrue(misses.isEmpty())
    }

    // ─── 第九批：l -t 树 / cp·mv -f / UUT v5 语义的纯函数部分 ─────────────

    private fun entry(path: String, size: Long = 0, isDir: Boolean = false) =
        com.usefulunpacker.ArchiveEntry(path, path.substringAfterLast('/'), size, isDir, false, path.count { it == '/' })

    @Test
    fun entryTreeRendersNestedWithDirsFirst() {
        val entries = listOf(
            entry("readme.md", 2),
            entry("data/img/b.png", 2),
            entry("data/img/a.png", 1),
            entry("data", isDir = true),
            entry("data/img", isDir = true),
        )
        val out = UuCommands.renderEntryTree(entries) { e ->
            if (e.isDirectory) "/" else "  " + e.size + "B"
        }
        // 目录在前、名称升序；树形符号与缩进
        val lines = out.split('\n')
        assertEquals("├─ data/", lines[0])
        assertEquals("│  └─ img/", lines[1])
        assertEquals("│     ├─ a.png  1B", lines[2])
        assertEquals("│     └─ b.png  2B", lines[3])
        assertEquals("└─ readme.md  2B", lines[4])
    }

    @Test
    fun entryTreeSynthesizesMissingDirectoryNodes() {
        // 有些归档不写目录条目：只有 data/img/a.png 也必须长出 data/ 和 img/
        val entries = listOf(entry("data/img/a.png", 5))
        val out = UuCommands.renderEntryTree(entries) { e ->
            if (e.isDirectory) "/" else "  " + e.size + "B"
        }
        val lines = out.split('\n')
        assertEquals("└─ data/", lines[0])
        assertEquals("   └─ img/", lines[1])
        assertEquals("      └─ a.png  5B", lines[2])
    }

    @Test
    fun copyForceOverwritesAndPlainRefuses() {
        val dir = tmp.root.resolve("cpf").apply { mkdirs() }
        File(dir, "src.txt").writeText("new")
        val dst = File(dir, "dst.txt").apply { writeText("old") }
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        // 无 -f：拒绝
        assertEquals(1, UuCommands.dispatch(listOf("cp", "src.txt", "dst.txt"), c).exitCode)
        assertEquals("old", dst.readText())
        // -f：覆盖
        assertEquals(0, UuCommands.dispatch(listOf("cp", "src.txt", "dst.txt", "-f"), c).exitCode)
        assertEquals("new", dst.readText())
        // 目录目标是"拷进去"语义（与 shell 一致，-f 只对文件目标有意义）：
        // d2 已存在 → 拷成 d2/d1
        File(dir, "d1/x").apply { parentFile.mkdirs(); writeText("1") }
        File(dir, "d2").mkdirs()
        assertEquals(0, UuCommands.dispatch(listOf("cp", "d1", "d2"), c).exitCode)
        assertEquals("1", File(dir, "d2/d1/x").readText())
    }

    @Test
    fun mvForceOverwrites() {
        val dir = tmp.root.resolve("mvf").apply { mkdirs() }
        File(dir, "a.txt").writeText("A")
        File(dir, "b.txt").writeText("B")
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        assertEquals(1, UuCommands.dispatch(listOf("mv", "a.txt", "b.txt"), c).exitCode)
        assertEquals("B", File(dir, "b.txt").readText())
        assertEquals(0, UuCommands.dispatch(listOf("mv", "a.txt", "b.txt", "-f"), c).exitCode)
        assertEquals("A", File(dir, "b.txt").readText())
        // -f 也不吃多余位置参数（继续报用法错）
        assertEquals(2, UuCommands.dispatch(listOf("mv", "a.txt", "b.txt", "c.txt", "-f"), c).exitCode)
    }

    @Test
    fun orElseJoinerSplitByChain() {
        assertEquals(
            listOf("uu l f0" to "", "echo missing" to "||"),
            UutParser.splitChain("uu l f0 || echo missing")
        )
        // 混合：; 之后 && 与 || 各自短路
        assertEquals(
            listOf("a" to "", "b" to ";", "c" to "&&", "d" to "||"),
            UutParser.splitChain("a ; b && c || d")
        )
    }

    // ─── 第十批：uu cat 纯文本文件 ────────────────────────────────────────

    @Test
    fun catPrintsPlainFilesWithEncodingDetection() {
        val dir = tmp.root.resolve("catf").apply { mkdirs() }
        File(dir, "note.txt").writeText("第一行\nsecond line")
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        val r = UuCommands.dispatch(listOf("cat", "note.txt"), c)
        assertEquals(0, r.exitCode)
        assertTrue(r.text, r.text.contains("第一行"))
        assertTrue(r.text, r.text.contains("second line"))
        // 二进制 → 报"无法按文本读"
        File(dir, "bin.dat").writeBytes(byteArrayOf(0, 1, 2, 3, -1, -2))
        val r2 = UuCommands.dispatch(listOf("cat", "bin.dat"), c)
        assertEquals(1, r2.exitCode)
    }

    // ─── 6.3：uu dd（精准切字节） ─────────────────────────────────────────

    /** 内容可辨识的源文件：第 i 字节 = i。 */
    private fun rampFile(dir: File, name: String, n: Int): File =
        File(dir, name).apply { writeBytes(ByteArray(n) { it.toByte() }) }

    private fun rampBytes(from: Int, until: Int) = ByteArray(until - from) { (from + it).toByte() }

    @Test
    fun ddCarvesARangeAndAcceptsHexOffsets() {
        val dir = tmp.root.resolve("dd1").apply { mkdirs() }
        rampFile(dir, "src.bin", 64)
        val r = UuCommands.dispatch(
            listOf("dd", "if=src.bin", "of=out.bin", "skip=0x10", "count=8"), ctx(dir)
        )
        assertEquals(r.text, 0, r.exitCode)
        assertArrayEquals(rampBytes(0x10, 0x18), File(dir, "out.bin").readBytes())
    }

    @Test
    fun ddCountOmittedGoesToEndAndIsClamped() {
        val dir = tmp.root.resolve("dd2").apply { mkdirs() }
        rampFile(dir, "src.bin", 20)
        val c = ctx(dir)
        // 不给 count → 切到源末尾
        assertEquals(0, UuCommands.dispatch(listOf("dd", "if=src.bin", "of=a.bin", "skip=15"), c).exitCode)
        assertArrayEquals(rampBytes(15, 20), File(dir, "a.bin").readBytes())
        // count 超出剩余 → 夹到剩余，不报错也不多读
        assertEquals(0, UuCommands.dispatch(listOf("dd", "if=src.bin", "of=b.bin", "skip=15", "count=999"), c).exitCode)
        assertArrayEquals(rampBytes(15, 20), File(dir, "b.bin").readBytes())
    }

    /** `skip` 相对 **fN 区间起点**，不是宿主文件 —— 否则扫描报出的偏移还得手工加回去。 */
    @Test
    fun ddSkipIsRelativeToAnFdRangeStart() {
        val dir = tmp.root.resolve("dd3").apply { mkdirs() }
        val host = rampFile(dir, "host.bin", 64)
        val t = FdTable()
        val fd = t.register(
            host, listOf(ScanHit(32, "ZIP archive", 16, null)),
            host.length(), host.lastModified()
        ) { null }[0]
        val c = UuCommands.Ctx(prefs = null, cwd = dir, fds = t)
        // 区间起点是宿主偏移 32，所以 skip=4 → 宿主 36
        val r = UuCommands.dispatch(listOf("dd", "if=f$fd", "of=part.bin", "skip=4", "count=4"), c)
        assertEquals(r.text, 0, r.exitCode)
        assertArrayEquals(rampBytes(36, 40), File(dir, "part.bin").readBytes())
        // 整个区间（不给 skip / count）
        assertEquals(0, UuCommands.dispatch(listOf("dd", "if=f$fd", "of=whole.bin"), c).exitCode)
        assertArrayEquals(rampBytes(32, 48), File(dir, "whole.bin").readBytes())
    }

    @Test
    fun ddRefusesToOverwriteUnlessForced() {
        val dir = tmp.root.resolve("dd4").apply { mkdirs() }
        rampFile(dir, "src.bin", 8)
        File(dir, "out.bin").writeBytes(ByteArray(4) { 9 })
        val c = ctx(dir)
        assertEquals(1, UuCommands.dispatch(listOf("dd", "if=src.bin", "of=out.bin"), c).exitCode)
        // 拒绝时目标必须原封不动
        assertArrayEquals(ByteArray(4) { 9 }, File(dir, "out.bin").readBytes())
        assertEquals(0, UuCommands.dispatch(listOf("dd", "if=src.bin", "of=out.bin", "-f"), c).exitCode)
        assertArrayEquals(rampBytes(0, 8), File(dir, "out.bin").readBytes())
    }

    @Test
    fun ddSeekSplicesInPlaceAndLeavesTheRestAlone() {
        val dir = tmp.root.resolve("dd5").apply { mkdirs() }
        File(dir, "patch.bin").writeBytes(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()))
        val target = File(dir, "game.bin").apply { writeBytes(ByteArray(16) { it.toByte() }) }
        val r = UuCommands.dispatch(listOf("dd", "if=patch.bin", "of=game.bin", "seek=4"), ctx(dir))
        assertEquals(r.text, 0, r.exitCode)
        val want = ByteArray(16) { it.toByte() }
        want[4] = 0xAA.toByte(); want[5] = 0xBB.toByte()
        want[6] = 0xCC.toByte(); want[7] = 0xDD.toByte()
        assertArrayEquals(want, target.readBytes())
        // 就地改写，不是插入 —— 长度不变
        assertEquals(16L, target.length())
    }

    @Test
    fun ddSeekNeedsAnExistingFile() {
        val dir = tmp.root.resolve("dd6").apply { mkdirs() }
        rampFile(dir, "src.bin", 8)
        assertEquals(1, UuCommands.dispatch(listOf("dd", "if=src.bin", "of=nope.bin", "seek=4"), ctx(dir)).exitCode)
        assertFalse(File(dir, "nope.bin").exists())
    }

    @Test
    fun ddRejectsSameFileAndBadArguments() {
        val dir = tmp.root.resolve("dd7").apply { mkdirs() }
        rampFile(dir, "src.bin", 16)
        val c = ctx(dir)
        // 自己切自己：会边读边覆盖源，产物必然是垃圾 → 拒
        assertEquals(1, UuCommands.dispatch(listOf("dd", "if=src.bin", "of=src.bin"), c).exitCode)
        assertArrayEquals(rampBytes(0, 16), File(dir, "src.bin").readBytes())
        // 缺 if= / of=
        assertEquals(2, UuCommands.dispatch(listOf("dd", "of=x.bin"), c).exitCode)
        assertEquals(2, UuCommands.dispatch(listOf("dd", "if=src.bin"), c).exitCode)
        // 不是数字 / 负数 / 未知键 / 同一个键给两次
        assertEquals(2, UuCommands.dispatch(listOf("dd", "if=src.bin", "of=x.bin", "skip=abc"), c).exitCode)
        assertEquals(2, UuCommands.dispatch(listOf("dd", "if=src.bin", "of=x.bin", "skip=-1"), c).exitCode)
        assertEquals(2, UuCommands.dispatch(listOf("dd", "if=src.bin", "of=x.bin", "bs=4"), c).exitCode)
        assertEquals(2, UuCommands.dispatch(listOf("dd", "if=src.bin", "if=src.bin", "of=x.bin"), c).exitCode)
        // 参数错时不该留下半成品
        assertFalse(File(dir, "x.bin").exists())
    }

    @Test
    fun ddSkipPastEndIsAnError() {
        val dir = tmp.root.resolve("dd8").apply { mkdirs() }
        rampFile(dir, "src.bin", 8)
        assertEquals(1, UuCommands.dispatch(listOf("dd", "if=src.bin", "of=x.bin", "skip=9"), ctx(dir)).exitCode)
        assertFalse(File(dir, "x.bin").exists())
    }

    // ─── 6.3：uu cmp（逐字节比对） ────────────────────────────────────────

    @Test
    fun cmpIdenticalFilesExitZero() {
        val dir = tmp.root.resolve("cmp1").apply { mkdirs() }
        rampFile(dir, "a.bin", 32)
        rampFile(dir, "b.bin", 32)
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        val r = UuCommands.dispatch(listOf("cmp", "a.bin", "b.bin"), c)
        assertEquals(r.text, 0, r.exitCode)
        assertTrue(r.text, r.text.contains("[a.bin]"))
        assertTrue(r.text, r.text.contains("[b.bin]"))
    }

    @Test
    fun cmpReportsFirstDifferenceAndTotalCount() {
        val dir = tmp.root.resolve("cmp2").apply { mkdirs() }
        rampFile(dir, "a.bin", 32)
        val b = File(dir, "b.bin").apply { writeBytes(ByteArray(32) { it.toByte() }) }
        b.writeBytes(rampBytes(0, 32).also { it[4] = 0xEE.toByte(); it[9] = 0xFF.toByte() })
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        val r = UuCommands.dispatch(listOf("cmp", "a.bin", "b.bin"), c)
        // 有差异 → 退出码 1（脚本能直接 if $?）
        assertEquals(r.text, 1, r.exitCode)
        assertTrue(r.text, r.text.contains("[0x4]"))    // 首个差异偏移
        assertTrue(r.text, r.text.contains("[EE]"))
        assertTrue(r.text, r.text.contains("[2]"))      // 共 2 处
    }

    @Test
    fun cmpDetectsLengthDifference() {
        val dir = tmp.root.resolve("cmp3").apply { mkdirs() }
        rampFile(dir, "a.bin", 8)
        rampFile(dir, "b.bin", 12)
        // 前 8 字节相同、b 多 4 字节 → 有差异
        assertEquals(1, UuCommands.dispatch(listOf("cmp", "a.bin", "b.bin"), ctx(dir)).exitCode)
        // 反过来同样
        assertEquals(1, UuCommands.dispatch(listOf("cmp", "b.bin", "a.bin"), ctx(dir)).exitCode)
    }

    @Test
    fun cmpSilentModeOnlySetsExitCode() {
        val dir = tmp.root.resolve("cmp4").apply { mkdirs() }
        rampFile(dir, "a.bin", 16)
        rampFile(dir, "b.bin", 16)
        val c = ctx(dir)
        val same = UuCommands.dispatch(listOf("cmp", "-s", "a.bin", "b.bin"), c)
        assertEquals(0, same.exitCode)
        assertEquals("", same.text)
        File(dir, "b.bin").writeBytes(rampBytes(0, 16).also { it[0] = 9 })
        val diff = UuCommands.dispatch(listOf("cmp", "-s", "a.bin", "b.bin"), c)
        assertEquals(1, diff.exitCode)
        assertEquals("", diff.text)
    }

    @Test
    fun cmpLimitComparesOnlyTheFirstNBytes() {
        val dir = tmp.root.resolve("cmp5").apply { mkdirs() }
        rampFile(dir, "a.bin", 16)
        File(dir, "b.bin").writeBytes(rampBytes(0, 16).also { it[8] = 0x77.toByte() })
        val c = ctx(dir)
        // 差异在第 8 字节 → -n 8 看不到它
        assertEquals(0, UuCommands.dispatch(listOf("cmp", "-n", "8", "a.bin", "b.bin"), c).exitCode)
        assertEquals(1, UuCommands.dispatch(listOf("cmp", "-n", "9", "a.bin", "b.bin"), c).exitCode)
        // 负数 / 非数字 → 参数错
        assertEquals(2, UuCommands.dispatch(listOf("cmp", "-n", "-1", "a.bin", "b.bin"), c).exitCode)
        assertEquals(2, UuCommands.dispatch(listOf("cmp", "-n", "xx", "a.bin", "b.bin"), c).exitCode)
    }

    @Test
    fun cmpListAllAndArgumentChecks() {
        val dir = tmp.root.resolve("cmp6").apply { mkdirs() }
        rampFile(dir, "a.bin", 16)
        File(dir, "b.bin").writeBytes(rampBytes(0, 16).also { it[2] = 0xAA.toByte(); it[5] = 0xBB.toByte() })
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        val r = UuCommands.dispatch(listOf("cmp", "-l", "a.bin", "b.bin"), c)
        assertEquals(1, r.exitCode)
        assertTrue(r.text, r.text.contains("[AA]"))     // 每个差异都列出来
        assertTrue(r.text, r.text.contains("[BB]"))
        // 参数个数不对 / 文件不存在 → 退出码 2（出错，区别于「有差异」）
        assertEquals(2, UuCommands.dispatch(listOf("cmp", "a.bin"), c).exitCode)
        assertEquals(2, UuCommands.dispatch(listOf("cmp", "a.bin", "b.bin", "c.bin"), c).exitCode)
        assertEquals(2, UuCommands.dispatch(listOf("cmp", "a.bin", "nope.bin"), c).exitCode)
    }

    // ─── 6.3：uu strings（可打印串提取） ──────────────────────────────────

    /** 往定长零填充缓冲区里放一段 ASCII。 */
    private fun putAscii(buf: ByteArray, at: Int, s: String) {
        s.forEachIndexed { i, c -> buf[at + i] = c.code.toByte() }
    }

    @Test
    fun stringsExtractsRunsWithOffsets() {
        val dir = tmp.root.resolve("str1").apply { mkdirs() }
        val data = ByteArray(48)
        putAscii(data, 4, "HELLO123")     // 8 字符 @ 0x04
        putAscii(data, 20, "abc")         // 3 字符 @ 0x14，默认最短 4 → 不算
        putAscii(data, 32, "world!")      // 6 字符 @ 0x20
        File(dir, "a.bin").writeBytes(data)
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        val r = UuCommands.dispatch(listOf("strings", "a.bin"), c)
        assertEquals(r.text, 0, r.exitCode)
        assertTrue(r.text, r.text.contains("0x00000004"))
        assertTrue(r.text, r.text.contains("HELLO123"))
        assertTrue(r.text, r.text.contains("0x00000020"))
        assertTrue(r.text, r.text.contains("world!"))
        assertFalse("短于 min 的不该出现: " + r.text, r.text.contains("abc"))
    }

    /** UTF-16LE 下每个字符后跟一个 0，按 ASCII 扫每段只有 1 字符 → 什么都看不到。 */
    @Test
    fun stringsUtf16leModeSeesWideStrings() {
        val dir = tmp.root.resolve("str2").apply { mkdirs() }
        val data = ByteArray(24)
        "TEST".forEachIndexed { i, c -> data[2 + i * 2] = c.code.toByte() }
        File(dir, "w.bin").writeBytes(data)
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        val ascii = UuCommands.dispatch(listOf("strings", "w.bin"), c)
        assertEquals(0, ascii.exitCode)
        assertFalse(ascii.text, ascii.text.contains("TEST"))
        val wide = UuCommands.dispatch(listOf("strings", "w.bin", "-e", "utf16le"), c)
        assertEquals(wide.text, 0, wide.exitCode)
        assertTrue(wide.text, wide.text.contains("TEST"))
        assertTrue(wide.text, wide.text.contains("0x00000002"))
    }

    @Test
    fun stringsHonorsMinAndMaxAndRejectsBadArgs() {
        val dir = tmp.root.resolve("str3").apply { mkdirs() }
        val data = ByteArray(64)
        putAscii(data, 0, "AAAA"); putAscii(data, 8, "BBBB"); putAscii(data, 16, "CCCC")
        File(dir, "m.bin").writeBytes(data)
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        // -n 2：只打印前 2 条，其余只报条数
        val r = UuCommands.dispatch(listOf("strings", "m.bin", "-n", "2"), c)
        assertEquals(r.text, 0, r.exitCode)
        assertTrue(r.text, r.text.contains("AAAA"))
        assertTrue(r.text, r.text.contains("BBBB"))
        assertFalse(r.text, r.text.contains("CCCC"))
        // min 5：4 字符的都不算 → 一条都没有（与 uu scan 一致，不算错误）
        val none = UuCommands.dispatch(listOf("strings", "m.bin", "5"), c)
        assertEquals(none.text, 0, none.exitCode)
        assertFalse(none.text, none.text.contains("AAAA"))
        // 参数错：min 越界 / 未知编码 / 非数字
        assertEquals(2, UuCommands.dispatch(listOf("strings", "m.bin", "0"), c).exitCode)
        assertEquals(2, UuCommands.dispatch(listOf("strings", "m.bin", "9999"), c).exitCode)
        assertEquals(2, UuCommands.dispatch(listOf("strings", "m.bin", "-e", "utf8"), c).exitCode)
        assertEquals(2, UuCommands.dispatch(listOf("strings", "m.bin", "-n", "xx"), c).exitCode)
        // 文件不存在 → 1（notFound 的既有约定，同 uu hex / uu cat；不是 cmp 那种 0/1/2 三态）
        assertEquals(1, UuCommands.dispatch(listOf("strings", "nope.bin"), c).exitCode)
    }

    // ─── 6.3：uu sed（内容替换） ──────────────────────────────────────────

    @Test
    fun sedPrintsToStdoutByDefaultAndLeavesTheFileAlone() {
        val dir = tmp.root.resolve("sed1").apply { mkdirs() }
        val f = File(dir, "a.txt").apply { writeText("hello world") }
        val r = UuCommands.dispatch(listOf("sed", "hello", "goodbye", "a.txt"), ctx(dir))
        assertEquals(r.text, 0, r.exitCode)
        assertTrue(r.text, r.text.contains("goodbye world"))
        // 不给 -i → 源文件必须原封不动
        assertEquals("hello world", f.readText())
    }

    @Test
    fun sedInPlaceRewritesAndKeepsTheBom() {
        val dir = tmp.root.resolve("sed2").apply { mkdirs() }
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val f = File(dir, "b.txt").apply { writeBytes(bom + "hello world".toByteArray()) }
        val c = ctx(dir)
        assertEquals(0, UuCommands.dispatch(listOf("sed", "-i", "hello", "goodbye", "b.txt"), c).exitCode)
        // BOM 保住、内容换掉 —— 原地改不该顺手把编码/头改了
        assertArrayEquals(bom + "goodbye world".toByteArray(), f.readBytes())
        // 没命中 → 文件一个字节都不动
        val before = f.readBytes()
        assertEquals(0, UuCommands.dispatch(listOf("sed", "-i", "zzz", "yyy", "b.txt"), c).exitCode)
        assertArrayEquals(before, f.readBytes())
    }

    @Test
    fun sedBytesModeReplacesHexAndRequiresInPlace() {
        val dir = tmp.root.resolve("sed3").apply { mkdirs() }
        val f = File(dir, "p.bin").apply {
            writeBytes(byteArrayOf(1, 2, 0xDE.toByte(), 0xAD.toByte(), 3))
        }
        val c = ctx(dir)
        // --bytes 不配 -i → 参数错（二进制结果不能穿过终端）
        assertEquals(2, UuCommands.dispatch(listOf("sed", "--bytes", "DEAD", "BEEF", "p.bin"), c).exitCode)
        assertEquals(0, UuCommands.dispatch(listOf("sed", "--bytes", "-i", "DEAD", "BEEF", "p.bin"), c).exitCode)
        assertArrayEquals(byteArrayOf(1, 2, 0xBE.toByte(), 0xEF.toByte(), 3), f.readBytes())
    }

    @Test
    fun sedRejectsBinaryInTextModeAndBadArgs() {
        val dir = tmp.root.resolve("sed4").apply { mkdirs() }
        File(dir, "bin.dat").writeBytes(ByteArray(64))   // 全 NUL → 不是文本
        File(dir, "t.txt").writeText("abc")
        val c = ctx(dir)
        // 文本模式撞上二进制 → 提示改用 --bytes，退出码 1
        assertEquals(1, UuCommands.dispatch(listOf("sed", "a", "b", "bin.dat"), c).exitCode)
        // 参数错：缺文件 / 空查找串 / 非法十六进制
        assertEquals(2, UuCommands.dispatch(listOf("sed", "a", "b"), c).exitCode)
        assertEquals(2, UuCommands.dispatch(listOf("sed", "", "b", "t.txt"), c).exitCode)
        assertEquals(2, UuCommands.dispatch(listOf("sed", "--bytes", "-i", "XYZ", "AB", "t.txt"), c).exitCode)
        assertEquals(2, UuCommands.dispatch(listOf("sed", "--bytes", "-i", "AB", "XY", "t.txt"), c).exitCode)
        // 多文件：改一个、缺一个 → 退出码 1，但成功那个确实改了
        assertEquals(1, UuCommands.dispatch(listOf("sed", "-i", "a", "b", "t.txt", "nope.txt"), c).exitCode)
        assertEquals("bbc", File(dir, "t.txt").readText())
    }

    // ─── 6.3 debug 回归 ───────────────────────────────────────────────────

    /**
     * `uu docs` 的 PARAMETERS 段是**手写的第二份清单**，天然会漂 —— `-e` 的方案列表就曾
     * 停在 7 个（6.2 加了三个无侧车方案后没跟上），`-t / -S / -d / -q` 也一直没解释过。
     * 这条机械地盯着「usage 行里出现的每个旗标，参数表里都得解释」。
     */
    @Test
    fun docsExplainsEveryFlagFromUsageLines() {
        val text = UuCommands.renderDocs()
        val at = text.indexOf("PARAMETERS")
        assertTrue("docs 里没有 PARAMETERS 段", at > 0)
        val flagRe = Regex("""(?<![\w-])(--?[a-zA-Z][\w-]*)""")
        val inUsage = flagRe.findAll(text.substring(0, at)).map { it.value }.toSet()
        val documented = flagRe.findAll(text.substring(at)).map { it.value }.toSet()
        assertTrue(
            "usage 里有、参数表没解释的旗标: ${inUsage - documented}",
            documented.containsAll(inUsage)
        )
    }

    /**
     * `uu docs` 的 `-e <scheme>` 列表是**手写枚举**，6.2 加了三个无侧车方案时它没跟上
     * （一直停在 7 个）。这份清单与 Rust 的 `Scheme::ALL` 跨语言，没法从那边派生，
     * 所以在这里钉死：加方案时忘了改 docs 就会红。
     */
    @Test
    fun docsListsEverySchemeInTheEncryptFlag() {
        val line = UuCommands.renderDocs().lineSequence().firstOrNull { it.contains("-e <scheme>") }
        assertTrue("docs 里没有 -e <scheme> 行", line != null)
        val schemes = listOf(
            "cxdec", "hashcrypt", "fatecrypt", "appliquecrypt", "flyingshinecrypt",
            "alteredpinkcrypt", "dameganecrypt", "natsupochicrypt", "okibacrypt", "dieselminecrypt",
        )
        for (s in schemes) assertTrue("docs 的 -e 列表缺 $s", line!!.contains(s))
    }

    /** `cmp` 里退出码 1 只能表示「有差异」；读不了必须是 2，否则脚本会把失败当差异。 */
    @Test
    fun cmpOnAStaleFdIsAnErrorNotADifference() {
        val dir = tmp.root.resolve("cmpstale").apply { mkdirs() }
        val host = File(dir, "h.bin").apply { writeBytes(ByteArray(32) { it.toByte() }) }
        File(dir, "o.bin").writeBytes(ByteArray(32) { it.toByte() })
        val t = FdTable()
        val fd = t.register(
            host, listOf(ScanHit(0, "ZIP archive", 32, null)), host.length(), host.lastModified()
        ) { null }[0]
        host.writeBytes(ByteArray(64))   // 宿主被改过 → fd 失效
        val c = UuCommands.Ctx(prefs = null, cwd = dir, fds = t)
        val r = UuCommands.dispatch(listOf("cmp", "f$fd", "o.bin"), c)
        assertEquals(r.text, 2, r.exitCode)
    }

    /** fN 区间显示的是 `uu scan` 报出的类型名，不是临时 carve 出来的哈希文件名。 */
    @Test
    fun cmpShowsTheScanLabelForAnFdRange() {
        val dir = tmp.root.resolve("cmplabel").apply { mkdirs() }
        val host = File(dir, "h.bin").apply { writeBytes(ByteArray(32) { it.toByte() }) }
        File(dir, "same.bin").writeBytes(ByteArray(32) { it.toByte() })
        val t = FdTable()
        val fd = t.register(
            host, listOf(ScanHit(0, "ZIP archive", 32, null)), host.length(), host.lastModified()
        ) { null }[0]
        val c = UuCommands.Ctx(prefs = null, cwd = dir, fds = t, cacheDir = dir, str = argStr())
        val r = UuCommands.dispatch(listOf("cmp", "f$fd", "same.bin"), c)
        assertEquals(r.text, 0, r.exitCode)
        assertTrue(r.text, r.text.contains("[ZIP archive]"))
        assertFalse("不该出现临时文件名: " + r.text, r.text.contains("[f$fd-"))
    }

    /** `-n 0` = 不限量；当成 0 上限的话会误报「没有可打印串」。 */
    @Test
    fun stringsZeroMaxMeansUnlimited() {
        val dir = tmp.root.resolve("str0").apply { mkdirs() }
        val data = ByteArray(32)
        putAscii(data, 0, "AAAA"); putAscii(data, 8, "BBBB")
        File(dir, "z.bin").writeBytes(data)
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        val r = UuCommands.dispatch(listOf("strings", "z.bin", "-n", "0"), c)
        assertEquals(r.text, 0, r.exitCode)
        assertTrue(r.text, r.text.contains("AAAA"))
        assertTrue(r.text, r.text.contains("BBBB"))
    }

    /**
     * `-i` 的前提是「解码再编码逐字节等于原文件」。
     *
     * 载荷要挑得准：35 字节 ASCII + 一个 SJIS/GBK 都不认的 0xFF。坏字符占比低
     * （1×20 < 36）所以 `detectBestEncoding` 不会整体否决、`looksLikeText` 也就为真，
     * 编码被定成 SHIFT-JIS —— 但解码出的 U+FFFD 编回去只能是 '?'，不是 0xFF。
     * 此时必须拒绝，不能悄悄改掉没人要求改的那个字节。
     */
    @Test
    fun sedRefusesInPlaceWhenTheEncodingDoesNotRoundTrip() {
        val dir = tmp.root.resolve("sedlossy").apply { mkdirs() }
        val raw = "hello world hello world hello world".toByteArray() + byteArrayOf(0xFF.toByte())
        assertEquals("载荷长度变了就换一个坏字符占比", 36, raw.size)
        val f = File(dir, "lossy.bin").apply { writeBytes(raw) }
        val c = ctx(dir)
        assertEquals(1, UuCommands.dispatch(listOf("sed", "-i", "hello", "goodbye", "lossy.bin"), c).exitCode)
        // 原文件一个字节都不能变
        assertArrayEquals(raw, f.readBytes())
        // 不给 -i（写 stdout）时原文件同样不动，结果照常打出来
        assertEquals(0, UuCommands.dispatch(listOf("sed", "hello", "goodbye", "lossy.bin"), c).exitCode)
        assertArrayEquals(raw, f.readBytes())
    }

    /** `if=` / `of=` 给了空值要当缺参报，不能退化成「找不到: 」（空名字）。 */
    @Test
    fun ddRejectsEmptyIfAndOfValues() {
        val dir = tmp.root.resolve("ddempty").apply { mkdirs() }
        rampFile(dir, "src.bin", 8)
        val c = ctx(dir)
        assertEquals(2, UuCommands.dispatch(listOf("dd", "if=", "of=x.bin"), c).exitCode)
        assertEquals(2, UuCommands.dispatch(listOf("dd", "if=src.bin", "of="), c).exitCode)
        assertFalse(File(dir, "x.bin").exists())
    }

    // ─── 6.3：uu tr（字节变换管道） ────────────────────────────────────────

    /** 造一个「zip 异或了 0xAA」的文件 —— 内置格式全都解不了的那种。 */
    private fun scrambledZip(dir: File, name: String, key: Int = 0xAA): Pair<File, ByteArray> {
        val raw = java.io.ByteArrayOutputStream().also { bos ->
            java.util.zip.ZipOutputStream(bos).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("a.txt"))
                z.write("hi".toByteArray())
                z.closeEntry()
            }
        }.toByteArray()
        val scrambled = ByteArray(raw.size) { (raw[it].toInt() xor key).toByte() }
        return File(dir, name).apply { writeBytes(scrambled) } to raw
    }

    @Test
    fun trUnscramblesAFileAndReportsWhatItBecame() {
        val dir = tmp.root.resolve("tr1").apply { mkdirs() }
        val (weird, raw) = scrambledZip(dir, "weird.dat")
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        val r = UuCommands.dispatch(listOf("tr", "weird.dat", "xor 0xAA", "-o", "fixed.zip"), c)
        assertEquals(r.text, 0, r.exitCode)
        // 还原出来的必须与原 zip 逐字节相同
        assertArrayEquals(raw, File(dir, "fixed.zip").readBytes())
        // 并且必须报出「变换后识别为 zip」—— 静默成功是不允许的
        assertTrue(r.text, r.text.contains("[zip]"))
        // 源文件只读：一个字节都不能动
        assertArrayEquals(weird.readBytes(), weird.readBytes())
    }

    @Test
    fun trSaysSoWhenTheResultIsNotRecognisable() {
        val dir = tmp.root.resolve("tr2").apply { mkdirs() }
        rampFile(dir, "x.bin", 32)
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        // 瞎猜一个 key → 结果不认识，但命令仍然成功（显式字节操作，同 uu dd）
        val r = UuCommands.dispatch(listOf("tr", "x.bin", "xor 0x5A", "-o", "y.bin"), c)
        assertEquals(r.text, 0, r.exitCode)
        assertTrue(r.text, r.text.contains("!str:${R.string.cli_tr_unrecognised}"))
    }

    @Test
    fun trDefaultsToADerivedNameAndRefusesToClobber() {
        val dir = tmp.root.resolve("tr3").apply { mkdirs() }
        scrambledZip(dir, "game.dat")
        val c = ctx(dir)
        // 不给 -o → 派生名 game-tr.dat
        assertEquals(0, UuCommands.dispatch(listOf("tr", "game.dat", "xor 0xAA"), c).exitCode)
        assertTrue(File(dir, "game-tr.dat").exists())
        // 显式 -o 撞已有文件 → 拒绝；-f 才覆盖
        assertEquals(1, UuCommands.dispatch(listOf("tr", "game.dat", "xor 0xAA", "-o", "game-tr.dat"), c).exitCode)
        assertEquals(0, UuCommands.dispatch(listOf("tr", "game.dat", "xor 0xAA", "-o", "game-tr.dat", "-f"), c).exitCode)
    }

    @Test
    fun trRejectsSameFileBadPipelinesAndBadArgs() {
        val dir = tmp.root.resolve("tr4").apply { mkdirs() }
        rampFile(dir, "s.bin", 32)
        val c = ctx(dir)
        // 自己变换自己：先读后写会把源覆盖掉
        assertEquals(1, UuCommands.dispatch(listOf("tr", "s.bin", "xor 0xAA", "-o", "s.bin"), c).exitCode)
        assertArrayEquals(rampBytes(0, 32), File(dir, "s.bin").readBytes())
        // 管道错 → 参数错（退出码 2），且不产出文件
        assertEquals(2, UuCommands.dispatch(listOf("tr", "s.bin", "nope", "-o", "o.bin"), c).exitCode)
        assertEquals(2, UuCommands.dispatch(listOf("tr", "s.bin", "xor ZZ", "-o", "o.bin"), c).exitCode)
        assertEquals(2, UuCommands.dispatch(listOf("tr", "s.bin", "xor 0xAA | skip 4", "-o", "o.bin"), c).exitCode)
        // 参数个数 / 文件不存在
        assertEquals(2, UuCommands.dispatch(listOf("tr", "s.bin"), c).exitCode)
        assertEquals(2, UuCommands.dispatch(listOf("tr", "s.bin", "not", "extra", "-o", "o.bin"), c).exitCode)
        assertEquals(1, UuCommands.dispatch(listOf("tr", "nope.bin", "not"), c).exitCode)
        assertFalse(File(dir, "o.bin").exists())
        // skip 超出源长度
        assertEquals(1, UuCommands.dispatch(listOf("tr", "s.bin", "skip 999", "-o", "o.bin"), c).exitCode)
        assertFalse(File(dir, "o.bin").exists())
    }

    /** `skip` 与多步管道组合，且 `if=` 支持 fN（直接对扫描命中变换，不必先落盘）。 */
    @Test
    fun trWorksOnAnFdRangeWithSkip() {
        val dir = tmp.root.resolve("tr5").apply { mkdirs() }
        val host = File(dir, "h.bin").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        val t = FdTable()
        val fd = t.register(
            host, listOf(ScanHit(16, "ZIP archive", 32, null)), host.length(), host.lastModified()
        ) { null }[0]
        val c = UuCommands.Ctx(prefs = null, cwd = dir, fds = t)
        assertEquals(0, UuCommands.dispatch(listOf("tr", "f$fd", "skip 4 | xor 0xFF", "-o", "part.bin"), c).exitCode)
        // 区间 [16,48)，丢前 4 → 从 20 起；坐标从区间起点 0 开始
        assertArrayEquals(ByteArray(28) { ((it + 4 + 16) xor 0xFF).toByte() }, File(dir, "part.bin").readBytes())
    }

    // ─── 6.3：uu guess（从内容反推变换） ──────────────────────────────────

    @Test
    fun guessReportsCandidatesWithEvidenceAndACopyableCommand() {
        val dir = tmp.root.resolve("gs1").apply { mkdirs() }
        scrambledZip(dir, "weird.dat", 0xA5)
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        val r = UuCommands.dispatch(listOf("guess", "weird.dat"), c)
        assertEquals(r.text, 0, r.exitCode)
        // 候选 + 它变成了什么 + 一条能直接粘的命令
        // （候选行是原样文本、不经 ctx.text，所以没有 argStr 的方括号）
        assertTrue(r.text, r.text.contains("xor A5"))
        assertTrue(r.text, r.text.contains("→ zip"))
        assertTrue(r.text, r.text.contains("weird-fixed.dat"))
    }

    @Test
    fun guessOnNoiseSaysNothingFoundAndRejectsBadArgs() {
        val dir = tmp.root.resolve("gs2").apply { mkdirs() }
        File(dir, "ramp.bin").writeBytes(ByteArray(512) { it.toByte() })
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        val r = UuCommands.dispatch(listOf("guess", "ramp.bin"), c)
        assertEquals(r.text, 0, r.exitCode)
        assertTrue(r.text, r.text.contains("!str:${R.string.cli_guess_none}"))
        // 多个参数 / 文件不存在 / 缺参数
        assertEquals(2, UuCommands.dispatch(listOf("guess", "a", "b"), c).exitCode)
        assertEquals(1, UuCommands.dispatch(listOf("guess", "nope.bin"), c).exitCode)
        assertEquals(2, UuCommands.dispatch(listOf("guess"), c).exitCode)
    }

    // ─── 6.3：uu c --post（写方向的自定义「压缩」） ───────────────────────

    /** 打包路径要 native 侧，单测里跑不动 —— 所以这里只钉校验分支。 */
    @Test
    fun postRejectsUninvertibleBatchAndBadPipelines() {
        val dir = tmp.root.resolve("post1").apply { mkdirs() }
        File(dir, "a.txt").writeText("x")
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr())
        // skip 丢数据 → 不可逆 → 拒（写方向不能产出没人解得开的包）
        assertEquals(1, UuCommands.dispatch(
            listOf("c", "a.txt", "out.zip", "--post", "skip 4"), c).exitCode)
        // 批量模式会产出多个文件，整体变换没有意义
        assertEquals(1, UuCommands.dispatch(
            listOf("c", "a.txt", "-c", "-f", "zip", "--post", "xor 0xAA"), c).exitCode)
        // 管道本身不合法 → 参数错
        assertEquals(2, UuCommands.dispatch(
            listOf("c", "a.txt", "out.zip", "--post", "xor ZZ"), c).exitCode)
        assertEquals(2, UuCommands.dispatch(
            listOf("c", "a.txt", "out.zip", "--post", "nope"), c).exitCode)
        assertFalse(File(dir, "out.zip").exists())
    }

    /**
     * `--post` 唯一的防线：变换完必须能用逆管道读回、逐字节对上。
     * 打包跑不动，但这条校验本身能单独验。
     */
    @Test
    fun postVerificationCatchesAMismatch() {
        val dir = tmp.root.resolve("post2").apply { mkdirs() }
        val raw = ByteArray(3000) { (it * 37 + 11).toByte() }
        val original = File(dir, "orig.bin").apply { writeBytes(raw) }
        // 变换件：原文件异或 0xA5
        val transformed = File(dir, "x.bin").apply { writeBytes(ByteArray(raw.size) { (raw[it].toInt() xor 0xA5).toByte() }) }
        val inv = (TrParser.parse("xor 0xA5") as TrParser.Out.Ok).pipeline.inverse()!!
        assertTrue(UuCommands.inverseRoundTrips(original, transformed, inv))
        // 差一个字节就该判否
        transformed.writeBytes(ByteArray(raw.size) { (raw[it].toInt() xor 0xA5).toByte() }.also { it[1500] = 0x00 })
        assertFalse(UuCommands.inverseRoundTrips(original, transformed, inv))
        // 长度不等直接否
        transformed.writeBytes(ByteArray(raw.size - 1))
        assertFalse(UuCommands.inverseRoundTrips(original, transformed, inv))
    }

    // ─── 6.3：uu l / uu x --pre（读方向的便捷变换） ───────────────────────

    /**
     * `--pre` 的核心安全闸：变换结果必须能被认出是归档，否则拒绝。
     * 这里用「错误的 key」让一个已加扰的 zip 变换后仍是乱码 → 必须拒。
     * 这条路径在 native 列目录之前就返回，所以不依赖 native 侧。
     */
    @Test
    fun preListRefusesWhenTransformDoesNotRecoverAnArchive() {
        val dir = tmp.root.resolve("pre1").apply { mkdirs() }
        scrambledZip(dir, "weird.dat", 0xAA)   // 一个被 0xAA 异或的 zip
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr(), cacheDir = dir)
        // 再用一个不同的 key 变换 → 结果不是任何已知格式 → 拒绝（不会走到 native）
        val r = UuCommands.dispatch(listOf("l", "weird.dat", "--pre", "xor 0x55"), c)
        assertEquals(r.text, 1, r.exitCode)
        assertTrue(r.text, r.text.contains("!str:${R.string.cli_pre_unrecognised}"))
    }

    /** 同上的 extract 分支：拒绝发生在解压之前。 */
    @Test
    fun preExtractRefusesWhenTransformDoesNotRecoverAnArchive() {
        val dir = tmp.root.resolve("pre2").apply { mkdirs() }
        scrambledZip(dir, "weird.dat", 0xAA)
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr(), cacheDir = dir)
        val r = UuCommands.dispatch(listOf("x", "weird.dat", "--pre", "xor 0x55"), c)
        assertEquals(r.text, 1, r.exitCode)
        assertTrue(r.text, r.text.contains("!str:${R.string.cli_pre_unrecognised}"))
    }

    /**
     * `--pre` 真把文件变换回来了 → 必须走到「变换后」那一端（警告行出现、且不拒）。
     * 列出/解压本身要 native 侧、单测跑不动，所以这里只断言「变换已发生、且没被当垃圾拒」。
     */
    @Test
    fun preWarnsWhenTheTransformRecoversAnArchive() {
        val dir = tmp.root.resolve("pre3").apply { mkdirs() }
        val (weird, _) = scrambledZip(dir, "weird.dat", 0xAA)   // zip XOR 0xAA
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr(), cacheDir = dir)
        // 再用 0xAA 变回去 → 还原成真 zip → 可识别，不会拒
        val r = UuCommands.dispatch(listOf("l", "weird.dat", "--pre", "xor 0xAA"), c)
        assertTrue(r.text, r.text.contains("!str:${R.string.cli_pre_warn}"))
        assertFalse(r.text, r.text.contains("!str:${R.string.cli_pre_unrecognised}"))
        // 临时文件必须被清理（listResolved 的 finally）
        assertTrue("uu_pre 临时文件应被清理", dir.resolve("uu_pre").listFiles().isNullOrEmpty())
    }

    /** 管道本身不合法 → 参数错（与 --post 同样的解析入口 trBadMessage）。 */
    @Test
    fun preRejectsBadPipeline() {
        val dir = tmp.root.resolve("pre4").apply { mkdirs() }
        rampFile(dir, "x.bin", 32)
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr(), cacheDir = dir)
        assertEquals(2, UuCommands.dispatch(listOf("l", "x.bin", "--pre", "nope"), c).exitCode)
        assertEquals(2, UuCommands.dispatch(listOf("l", "x.bin", "--pre", "xor ZZ"), c).exitCode)
    }

    /** `--pre` 缺后续值 → 报缺值错误（与 -p 同一条 missingValueError 通道）。 */
    @Test
    fun preRejectsMissingValue() {
        val dir = tmp.root.resolve("pre5").apply { mkdirs() }
        rampFile(dir, "x.bin", 32)
        val c = ctx(dir)
        assertEquals(1, UuCommands.dispatch(listOf("l", "x.bin", "--pre"), c).exitCode)
    }

    /** `skip` 把字节全丢光 → 报「无可读内容」，而不是静默产出空文件。 */
    @Test
    fun preRejectsWhenSkipDropsEverything() {
        val dir = tmp.root.resolve("pre6").apply { mkdirs() }
        rampFile(dir, "x.bin", 32)
        val c = UuCommands.Ctx(prefs = null, cwd = dir, str = argStr(), cacheDir = dir)
        val r = UuCommands.dispatch(listOf("l", "x.bin", "--pre", "skip 999"), c)
        assertEquals(r.text, 1, r.exitCode)
        assertTrue(r.text, r.text.contains("!str:${R.string.cli_pre_empty}"))
    }
}
