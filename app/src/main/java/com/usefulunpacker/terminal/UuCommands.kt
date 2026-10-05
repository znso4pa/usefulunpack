package com.usefulunpacker

import android.content.SharedPreferences
import com.usefulunpacker.fileops.RecycleBin
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * uu 命令集。
 *
 * **单一来源**：每条命令的名字、摘要、用法与派发逻辑都在同一张表里。
 * `uu help` 只遍历这张表渲染帮助，不再手写第二份命令列表 —— 本项目已经因为
 * 「列举代码被手抄成三份」踩过一次（`batchPreview` 漏了 ZIP 编码设置，
 * GBK 命名的包在批量预览里全是乱码，而单包预览正常）。帮助与实现必须同源。
 *
 * **不依赖 Activity**：[Ctx.activity] 可空，[Ctx.str] 有 stub 兜底。
 * 只依赖 [Ctx.prefs] / [Ctx.cwd] 的派发在纯 JVM 单测里照常工作 ——
 * 这是实施顺序第 1 步能独立验证的前提。
 */
internal object UuCommands {

    /** 一次 uu 调用的执行环境。 */
    class Ctx(
        /** 解压/封包派发需要读偏好（zip 编码等）；纯文件命令用不到，单测传 null。 */
        val prefs: SharedPreferences?,
        /** 会话当前目录，所有相对路径都相对它解析。 */
        val cwd: File,
        /** 需要弹 UI 时才非空（密码框、错误提示、完成后导航）。 */
        val activity: MainActivity? = null,
        /** 取本地化文案。单测用 stub 即可断言到资源 id 级别。 */
        val str: StrFn = { id, _ -> "!str:$id" },
        /** 扫描建立的 FD 表（`uu scan` 的产物）。null = 本轮未扫描过。 */
        val fds: FdTable? = null,
        /** `uu x -p` / `uu c -p` 提供的密码；null = 没给。 */
        val password: String? = null,
        /** 临时文件根目录（fN 临时 carve 用）。 */
        val cacheDir: File? = null,
        /**
         * 长命令（x/c）的进度行回调：**整行替换**终端输出区的最后一条进度行，
         * 不弹任何对话框（产品要求：进度只写在 CLI 窗口内）。null = 单测/静默。
         */
        val progress: ((String) -> Unit)? = null,
        /**
         * 默认输出目录（产品要求：x/c 不加路径时落到「单独路径」，不混进源目录）。
         * 终端层传 /storage/emulated/0/uu_cli；null = 退回 cwd（单测）。
         */
        val defaultOutDir: File? = null,
    ) {
        fun text(resId: Int, vararg args: Any) = str(resId, arrayOf(*args))
    }

    /** 派发结果。[text] 写进输出区；[exitCode] 供脚本判断成败。 */
    class Result(
        val text: String,
        val exitCode: Int = 0,
        /**
         * 命令需要路径但调用方没给时置位，由终端层弹选择器。
         * 命令层自己不弹窗：它可能在没有 Activity 的环境里跑（单测）。
         */
        val picker: Picker? = null,
    )

    /** 要用户补一个路径。[FILE] 允许选文件，[FOLDER] 只收目录，
     *  [FILE_OR_FOLDER] 两者皆可（`uu c` 的源：单文件或文件夹都能封）。 */
    enum class Picker { FILE, FOLDER, FILE_OR_FOLDER }

    /** 需要文件参数的命令缺参时用它。 */
    private fun needFile(p: Picker = Picker.FILE) = Result("", 2, p)

    /** x/c 不加路径时的默认输出位置（单独路径，产品要求）。 */
    private fun defaultOut(ctx: Ctx): File = (ctx.defaultOutDir ?: ctx.cwd).apply { mkdirs() }

    internal enum class Kind {
        LIST_FORMATS, HELP, INFO, LIST, CAT, HASH, GREP, COPY, MV, RENAME, RM, MKDIR,
        TREE, DU, STAT, EXTRACT, PACK, SET, SCAN, CSO
    }

    internal class Cmd(val name: String, val kind: Kind, val summary: String, val usage: String)

    /**
     * 命令表。新增命令只加这一行 —— `uu help` 会自动跟上。
     * 摘要/用法暂用英文，与 `COMPRESS_LABELS` 的既有做法一致（帮助文本不进资源数组，
     * 因为它是结构化数据的一部分；用户可见的**句子**才走 UuText）。
     */
    private val table: List<Cmd> = listOf(
        Cmd("fmt",    Kind.LIST_FORMATS, "List every supported format",                    "uu fmt"),
        Cmd("help",   Kind.HELP,         "Show this help",                                 "uu help"),
        Cmd("info",   Kind.INFO,         "Print the detected format of a file",            "uu info <file>"),
        Cmd("l",      Kind.LIST,         "List the entries of an archive",                 "uu l <archive> [-a]"),
        Cmd("cat",    Kind.CAT,          "Print a text entry from an archive",             "uu cat <archive> <entry> [-p pw]"),
        Cmd("hash",   Kind.HASH,         "Print the MD5 and SHA-256 of a file",            "uu hash <file>"),
        Cmd("grep",   Kind.GREP,         "Search text inside a folder or archive",         "uu grep [-i] <pattern> <path>"),
        Cmd("cp",     Kind.COPY,         "Copy a file or folder (refuses to overwrite)",   "uu cp <src> <dst>"),
        Cmd("mv",     Kind.MV,           "Move a file or folder (refuses to overwrite)",   "uu mv <src> <dst>"),
        Cmd("rn",     Kind.RENAME,       "Rename a file or folder (refuses to overwrite)", "uu rn <old> <new>"),
        Cmd("rm",     Kind.RM,           "Delete (recycle bin by default; -f permanent)",  "uu rm <path...> [-f]"),
        Cmd("mkdir",  Kind.MKDIR,        "Create folders",                                 "uu mkdir <path...>"),
        Cmd("tree",   Kind.TREE,         "Recursive directory listing",                    "uu tree [dir] [depth]"),
        Cmd("du",     Kind.DU,           "Show the total size of a folder or file",        "uu du <path>"),
        Cmd("stat",   Kind.STAT,         "Show type / size / modified time",               "uu stat <path...>"),
        Cmd("x",      Kind.EXTRACT,      "Extract an archive, optionally selected entries", "uu x <archive> [outdir] [entry...] [-p pw]"),
        Cmd("c",      Kind.PACK,         "Pack a file or folder",                          "uu c <src...> [out] [-c|-s] [-f key] [level] [splitMB] [-p pw]"),
        Cmd("set",    Kind.SET,          "Replace one entry inside an archive",            "uu set <archive> <entry> <localfile> [-p pw]"),
        Cmd("scan",   Kind.SCAN,         "Scan a file for embedded archive signatures",    "uu scan <file>"),
        Cmd("cso",    Kind.CSO,          "Convert ISO ↔ CSO",                              "uu cso <file> [out]"),
    )

    /** 供 `help` 内建命令复用，保证它和 `uu help` 讲的是同一份内容。 */
    fun renderHelp(ctx: Ctx): String = help(ctx).text

    /** @param argv 已切分的参数（含命令名本身）。 */
    fun dispatch(argv: List<String>, ctx: Ctx): Result {
        val name = argv.firstOrNull() ?: return Result("", 0)
        val cmd = table.firstOrNull { it.name == name }
            ?: return Result(UuText.unknownCommand(ctx.str, name), 1)
        val args = argv.drop(1)
        return try {
            when (cmd.kind) {
                Kind.LIST_FORMATS -> listFormats(ctx)
                Kind.HELP -> help(ctx)
                Kind.INFO -> info(args, ctx)
                Kind.LIST -> list(args, ctx)
                Kind.CAT -> cat(args, ctx)
                Kind.HASH -> hash(args, ctx)
                Kind.GREP -> grep(args, ctx)
                Kind.COPY -> copy(args, ctx)
                Kind.MV -> mv(args, ctx)
                Kind.RENAME -> rename(args, ctx)
                Kind.RM -> rm(args, ctx)
                Kind.MKDIR -> mkdir(args, ctx)
                Kind.TREE -> tree(args, ctx)
                Kind.DU -> du(args, ctx)
                Kind.STAT -> stat(args, ctx)
                Kind.EXTRACT -> extract(args, ctx)
                Kind.PACK -> pack(args, ctx)
                Kind.SET -> setEntry(args, ctx)
                Kind.SCAN -> scan(args, ctx)
                Kind.CSO -> cso(args, ctx)
            }
        } catch (e: Exception) {
            // 命令层不各自 catch：统一转成一行错误，避免把堆栈写进终端输出区
            Result(UuText.failed(ctx.str, e.message ?: e.javaClass.simpleName), 1)
        }
    }

    // ─── 各命令 ──────────────────────────────────────────────────────────

    private fun listFormats(ctx: Ctx): Result {
        val sb = StringBuilder()
        for ((labelRes, keys) in FORMAT_GROUPS) {
            sb.append(ctx.text(labelRes)).append('\n')
            keys.forEach { sb.append("  ").append(it).append('\n') }
            sb.append('\n')
        }
        return Result(sb.toString().trimEnd('\n'))
    }

    private fun help(ctx: Ctx): Result {
        val sb = StringBuilder()
        sb.append(UuText.helpIntro(ctx.str)).append('\n')
        for (c in table) {
            sb.append("  ").append(c.usage).append('\n')
            sb.append("      ").append(c.summary).append('\n')
        }
        sb.append('\n').append(UuText.helpExamples(ctx.str))
        return Result(sb.toString().trimEnd('\n'))
    }

    /**
     * `uu info <file>` → 只输出一个格式 key，脚本可直接判断；
     * 识别不出时输出**空行**（退出码 0），因为「不是归档」是有效答案而不是错误。
     */
    private fun info(args: List<String>, ctx: Ctx): Result {
        if (args.isEmpty()) return needFile()
        val f = UuText.resolve(ctx.cwd, args[0])
        if (!f.isFile) return Result(UuText.notFound(ctx.str, args[0]), 1)
        val fmt = detectFormat(f) ?: detectFormatByMagic(f)
        return Result(fmt ?: "")
    }

    private fun list(args: List<String>, ctx: Ctx): Result {
        if (args.isEmpty()) return needFile()
        // -a = 打印全部条目；默认截断（有些归档几十万条，刷屏且吃光 scrollback）
        val showAll = args.any { it == "-a" }
        val rest = args.filter { it != "-a" }
        if (rest.isEmpty()) return needFile()
        val spec = rest[0]

        // fN → 整文件条目（ls 注册的）直接读 host；区间条目（scan 注册的）先
        // 临时 carve 出来再列
        val fd = ctx.fds?.get(spec.removePrefix("f").toIntOrNull() ?: -1)
        if (fd != null) {
            if (fd.wholeFile) {
                ctx.fds.checkFresh(fd)?.let { return Result(staleMsg(ctx.str, it), 1) }
                return listEntries(fd.host, fd.host.name, showAll, ctx)
            }
            if (!fd.isArchive()) {
                return Result(UuText.fdNotArchive(ctx.str, spec, fd.label), 2)
            }
            ctx.fds.checkFresh(fd)?.let { return Result(staleMsg(ctx.str, it), 1) }
            val carved = ctx.cacheDir?.let {
                File(it, "uufd/f${fd.fd}-${fd.label.hashCode()}-${System.nanoTime()}")
            } ?: return Result(UuText.needsActivity(ctx.str), 2)
            try {
                carved.parentFile?.mkdirs()
                carveToFile(fd.host, fd.offset, fd.length, carved) {}
                // 展示名用 scan 的 label；临时文件名是一串哈希没有信息量。
                return listEntries(carved, fd.label, showAll, ctx)
            } finally {
                carved.deleteRecursively()
            }
        }

        val f = UuText.resolve(ctx.cwd, spec)
        if (!f.isFile) return Result(UuText.notFound(ctx.str, spec), 1)
        return listEntries(f, f.name, showAll, ctx)
    }

    private fun listEntries(f: File, displayName: String, showAll: Boolean, ctx: Ctx): Result {
        val fmt = detectFormat(f) ?: detectFormatByMagic(f)
            ?: return Result(UuText.extractBadFormat(ctx.str, "-"), 1)
        // 密码：先试空密码（大多数包没密码），失败再让用户加 -p。
        // 不弹模态 —— 模态在终端里很怪，而且可阻塞 30s 会冻住输出区。
        val pwd = ctx.password ?: ""
        val json = listEntriesJsonFor(ctx, fmt, f, pwd)
            ?: return Result(UuText.listFailed(ctx.str, f.name), 1)
        if (json == "[]") return Result(UuText.listEmpty(ctx.str, displayName))

        val entries = parseEntries(json)
        val total = entries.sumOf { if (!it.isDirectory) it.size else 0L }
        val sb = StringBuilder()
        sb.append(UuText.listHeader(ctx.str, displayName, entries.size, fmt(total))).append('\n')
        val dirMark = UuText.listDirMark(ctx.str)
        val encMark = UuText.listEncMark(ctx.str)
        val shown = if (showAll) entries.size else minOf(entries.size, LIST_PREVIEW_LIMIT)
        // size 列右对齐到 12 宽；目录/加密用标记占位
        val sizeCol = 12
        for (e in entries.take(shown)) {
            val sizeTxt = if (e.isDirectory) dirMark else fmt(e.size)
            val marks = if (e.isEncrypted) "  $encMark" else ""
            sb.append("  ").append(e.path)
                .append(" ".repeat(maxOf(1, sizeCol - sizeTxt.length)))
                .append(sizeTxt).append(marks).append('\n')
        }
        if (shown < entries.size) {
            sb.append(UuText.listTruncated(ctx.str, entries.size - shown)).append('\n')
        }
        return Result(sb.toString().trimEnd('\n'))
    }

    /** `uu hash <file>` → MD5 + SHA-256 两行十六进制，流式读取不整载。支持 fN。 */
    private fun hash(args: List<String>, ctx: Ctx): Result {
        if (args.isEmpty()) return needFile()
        return when (val r = resolveSource(args[0], ctx)) {
            is SrcSpec.Fail -> r.result
            is SrcSpec.Path -> {
                if (!r.f.isFile) return Result(UuText.notFound(ctx.str, args[0]), 1)
                hashOf(r.f)
            }
            is SrcSpec.Fd -> {
                try { hashOf(r.f) } finally { r.temp?.deleteRecursively() }
            }
        }
    }

    private fun hashOf(f: File): Result =
        Result("MD5      " + hashFile(f, "MD5") + "\nSHA-256  " + hashFile(f, "SHA-256"))

    /**
     * 把参数解析成实际文件：`fN` 优先（整文件条目直接用 host；区间条目先临时
     * carve，[SrcSpec.temp] 交调用方清理），非 fN 走普通路径。
     */
    private sealed class SrcSpec {
        class Path(val f: File) : SrcSpec()
        class Fd(val f: File, val temp: File?) : SrcSpec()
        class Fail(val result: Result) : SrcSpec()
    }

    private fun resolveSource(spec: String, ctx: Ctx): SrcSpec {
        val fd = ctx.fds?.get(spec.removePrefix("f").toIntOrNull() ?: -1)
            ?: return SrcSpec.Path(UuText.resolve(ctx.cwd, spec))
        ctx.fds.checkFresh(fd)?.let { return SrcSpec.Fail(Result(staleMsg(ctx.str, it), 1)) }
        if (fd.wholeFile) return SrcSpec.Fd(fd.host, null)
        // 每次调用唯一化：hash/cp 不占调度槽，两条命令并发引用同一 fN 时
        // 确定性文件名会互踩（truncate 中的文件被读 → 哈希错 / 被先完成方删除）。
        val carved = ctx.cacheDir?.let {
            File(it, "uufd/f${fd.fd}-${fd.label.hashCode()}-${System.nanoTime()}")
        } ?: return SrcSpec.Fail(Result(UuText.needsActivity(ctx.str), 2))
        carved.parentFile?.mkdirs()
        carveToFile(fd.host, fd.offset, fd.length, carved) {}
        return SrcSpec.Fd(carved, carved)
    }

    /** `uu cp <src> <dst>` — 文件/目录/fN；dst 已存在（含作为目标本身）拒绝覆盖。 */
    private fun copy(args: List<String>, ctx: Ctx): Result {
        if (args.size < 2) return needFile()
        var srcTemp: File? = null
        val src: File = when (val r = resolveSource(args[0], ctx)) {
            is SrcSpec.Fail -> return r.result
            is SrcSpec.Path -> {
                if (!r.f.exists()) return Result(UuText.notFound(ctx.str, args[0]), 1)
                r.f
            }
            is SrcSpec.Fd -> { srcTemp = r.temp; r.f }
        }
        try {
            var dst = UuText.resolve(ctx.cwd, args[1])
            // dst 是已存在的目录 → 拷到它里面（区间 fd 的临时名是哈希,退化为
            // "carved" 命名;整文件/目录沿用原名）
            if (dst.isDirectory) {
                val name = if (srcTemp != null) "carved" else src.name
                dst = File(dst, name)
            }
            if (dst.exists()) return Result(ctx.text(R.string.cli_exists, dst.path), 1)
            // 目录拷贝进自己（cp mydir mydir/inner）→ 无限自嵌套直到路径爆掉
            if (src.isDirectory && dst.canonicalPath.startsWith(src.canonicalPath + File.separator)) {
                return Result(ctx.text(R.string.cli_failed, dst.path), 1)
            }
            if (src.isDirectory) src.copyRecursively(dst, overwrite = false)
            else {
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = false)
            }
            return Result(ctx.text(R.string.cli_copy_done, src.path, dst.path))
        } finally {
            srcTemp?.deleteRecursively()  // fN 区间临时 carve 用完即清
        }
    }

    /** `uu rename <old> <new>` — 同目录改名；new 已存在拒绝。 */
    private fun rename(args: List<String>, ctx: Ctx): Result {
        if (args.size < 2) return needFile()
        val src = UuText.resolve(ctx.cwd, args[0])
        if (!src.exists()) return Result(UuText.notFound(ctx.str, args[0]), 1)
        val dst = UuText.resolve(ctx.cwd, args[1])
        if (dst.exists()) return Result(ctx.text(R.string.cli_exists, dst.path), 1)
        if (!src.renameTo(dst)) return Result(UuText.failed(ctx.str, args[0]), 1)
        return Result(ctx.text(R.string.cli_rename_done, src.name, dst.name))
    }

    private fun listEntriesJsonFor(ctx: Ctx, fmt: String, f: File, pwd: String): String? {
        val host = ctx.activity ?: return null
        return host.listEntriesJson(fmt, f, pwd, nestedOnly = false)
    }

    /**
     * `uu x <archive> [outdir] [entry...] [-p pw]`。
     *
     * 进度**只写在终端输出区**（产品要求，不弹对话框）：[Ctx.progress] 每 200ms
     * 收到一条整行替换的进度行；完成后输出统计 + all set。密码遵守项目不变量：
     * 加密包在**拿调度槽之前**解决（-p 或模态询问）。
     */
    private fun extract(args: List<String>, ctx: Ctx): Result {
        val (flags, _, pos) = splitFlags(args, listOf("-p"))
        val pw = flags["-p"] ?: ctx.password
        if (pos.isEmpty()) return needFile()
        val spec = pos[0]

        // fN 引用：整文件条目直接用 host；区间条目临时 carve 出来再解
        var carvedTemp: File? = null
        val src0: File
        val displayName: String
        val fd = ctx.fds?.get(spec.removePrefix("f").toIntOrNull() ?: -1)
        if (fd != null) {
            if (fd.wholeFile) {
                ctx.fds.checkFresh(fd)?.let { return Result(staleMsg(ctx.str, it), 1) }
                src0 = fd.host
                displayName = fd.host.name
            } else {
                if (!fd.isArchive()) return Result(UuText.fdNotArchive(ctx.str, spec, fd.label), 2)
                ctx.fds.checkFresh(fd)?.let { return Result(staleMsg(ctx.str, it), 1) }
                val carved = ctx.cacheDir?.let {
                    File(it, "uufd/f${fd.fd}-${fd.label.hashCode()}-${System.nanoTime()}")
                } ?: return Result(UuText.needsActivity(ctx.str), 2)
                carved.parentFile?.mkdirs()
                carveToFile(fd.host, fd.offset, fd.length, carved) {}
                carvedTemp = carved
                src0 = carved
                displayName = fd.label
            }
        } else {
            val f = UuText.resolve(ctx.cwd, spec)
            if (!f.isFile) return Result(UuText.notFound(ctx.str, spec), 1)
            src0 = f
            displayName = f.name
        }

        try {
            val fmt = detectFormat(src0) ?: detectFormatByMagic(src0)
                ?: return Result(UuText.extractBadFormat(ctx.str, "-"), 1)

            // 密码在拿槽之前解决（项目不变量 #2）。
            var pwd = pw ?: ""
            if (fmt in setOf("zip", "7z", "rar") && isPasswordProtected(src0)) {
                if (pw != null) {
                    pwd = pw
                } else {
                    val act = ctx.activity
                        ?: return Result(ctx.text(R.string.cli_need_password), 1)
                    val entered = promptPasswordSync(act)
                        ?: return Result(UuText.extractPwdCancelled(ctx.str), 1)
                    pwd = entered
                }
            }

            // outdir：不加路径 → 默认单独路径（uu_cli/<归档名>/，产品要求）；
            // 显式给了就以该路径为最终输出（已存在则必须是目录，直接解进去）。
            // 显式路径先行校验（快速失败）；默认目录的唯一化**拿到槽位后再解析**
            //（不变量：排队的两次解压不能在入队时撞名）。
            val outDirExplicit: File? = if (pos.size >= 2) {
                val d = UuText.resolve(ctx.cwd, pos[1])
                if (d.exists() && !d.isDirectory) return Result(ctx.text(R.string.cli_exists, d.path), 1)
                d.mkdirs()
                d
            } else null
            val selected = pos.drop(2).joinToString("\n")

            val act = ctx.activity
            val opH = act?.let { tryStartOperation(it, fmt) }
            try {
                // await()==false = 排队期被取消：必须中止，不能带着取消标记开跑
                //（Rust 入口的 clear_cancel 会把它清掉）。
                if (opH?.await() == false) return Result(UuText.extractCancelled(ctx.str), 1)
                val outDir = outDirExplicit
                    ?: uniqueFile(defaultOut(ctx), src0.nameWithoutExtension.ifEmpty { "extracted" })
                val accessors = extractAccessors(fmt)
                val done = AtomicBoolean(false)
                val poller = if (ctx.progress != null) thread {
                    while (!done.get()) {
                        ctx.progress?.invoke(progressLine(accessors))
                        Thread.sleep(200)
                    }
                } else null

                val o = try {
                    extractByFormat(fmt, src0.path, outDir.path, selected, ctx.prefs
                        ?: throw IllegalStateException("prefs required"), pwd)
                } finally {
                    done.set(true)
                    poller?.join(600)
                }

                if (!o.counts.ok) {
                    val msg = if (act != null) friendlyExtractError(act, o.error)
                              else (o.error ?: "failed")
                    return Result(msg, 1)
                }
                // 解压产物可能就在当前窗口的目录里，刷新让用户直接看到。
                // 必须回 UI 线程：refreshTab→navTab 摸视图，后台线程直接调会抛
                // CalledFromWrongThreadException（"Main thread (tid 2), calling
                // thread (tid 29)"——之前被 dispatch 的 catch 包成失败文本，
                // 看起来像解压失败，其实产物是好的）。
                act?.runOnUiThread {
                    if (!act.isFinishing && !act.isDestroyed) act.refreshTab(act.activeTab)
                }
                return Result(
                    ctx.text(R.string.cli_extract_ok, displayName,
                        o.counts.total.toString(), o.counts.success.toString(),
                        o.counts.error.toString()) + "\n" +
                    ctx.text(R.string.cli_all_set)
                )
            } finally {
                opH?.release()
            }
        } finally {
            carvedTemp?.deleteRecursively()
        }
    }

    /**
     * `uu c <src> <out.ext> [-f key] [level] [splitMB] [-p pw]`。
     * 产物名**在拿到调度槽之后**解析（排队中的两次打包不能撞名）；用户显式
     * 给名 → 目标已存在直接报错，不静默改名。`.pfs` 扩展名对应 pfs/pf6 两种
     * 封包，必须 -f 指明，不猜。不做 RGSS `Game.<ext>` 自动改名（GUI 行为，
     * CLI 尊重用户显式名）。
     */
    /**
     * `uu c <src...> [out] [-c|-s] [-f key] [level] [splitMB] [-p pw]`。
     *  - 单源 + out：`uu c dir out.zip` → out 是**最终输出路径**（产品要求）。
     *  - 单源无 out：落到默认单独路径（uu_cli/<src 名>.<ext>），格式必须 -f 给出。
     *  - `-c`：多源合并成一个包（暂存目录内重名按 `名字 (n)` 去重，默认名 archive.<ext>）。
     *  - `-s`：多源分别压缩（单文件 + zip/7z/tar 系自动临时目录包裹）。
     *  产物名**拿到调度槽之后**解析（排队互不撞名）；已存在拒绝；`.pfs` 二义必须 -f。
     */
    private fun pack(args: List<String>, ctx: Ctx): Result {
        val (flags, bools, pos) = splitFlags(args, listOf("-p", "-f"), listOf("-c", "-s"))
        val merge = "-c" in bools
        val separate = "-s" in bools
        if (merge && separate) return Result(UuText.failed(ctx.str, "-c / -s"), 1)
        val pw = flags["-p"] ?: ""
        val forceKey = flags["-f"]
        if (pos.isEmpty()) return needFile(Picker.FILE_OR_FOLDER)

        val packResultLines = ArrayList<String>()
        // 批量模式（-c/-s）：格式必须 -f 指明（没有 out 后缀可反查，不猜格式）
        if (merge || separate) {
            val fmt = forceKey?.let {
                if (it !in COMPRESS_EXT.keys) return Result(UuText.packUnknownKey(ctx.str, it), 1)
                it
            } ?: return Result(ctx.text(R.string.cli_pack_need_format), 1)
            // zip 的等级 GUI 走 zip_level（generic_level 不是它的档位）
            val level = ctx.prefs?.let { if (fmt == "zip") it.getInt("zip_level", 5) else it.getInt("generic_level", 6) } ?: 6
            val ext = COMPRESS_EXT[fmt]!!
            if (merge && fmt in setOf("gz", "bz2", "xz", "zst", "lzma", "lz4", "br", "ksd")) {
                // 单文件流格式没有「合并多文件」语义，提前拒绝而不是在 Rust 层深处失败
                return Result(UuText.failed(ctx.str, "-c: $fmt"), 1)
            }
            val outDir = defaultOut(ctx)
            val opH = ctx.activity?.let { tryStartOperation(it, fmt) }
            try {
                if (opH?.await() == false) return Result(UuText.extractCancelled(ctx.str), 1)
                val accessors = compressAccessors(fmt)
                val done = AtomicBoolean(false)
                val poller = if (ctx.progress != null) thread {
                    while (!done.get()) {
                        ctx.progress?.invoke(progressLine(accessors))
                        Thread.sleep(200)
                    }
                } else null
                var okCount = 0
                var failCount = 0
                try {
                    if (merge) {
                        // 合并：暂存目录 + 重名去重，一个产物
                        val staging = ctx.cacheDir?.let { File(it, "uu_pack/merge_${System.nanoTime()}") }
                            ?: return Result(UuText.needsActivity(ctx.str), 2)
                        try {
                            staging.mkdirs()
                            val used = mutableSetOf<String>()
                            for (srcPath in pos) {
                                val src = UuText.resolve(ctx.cwd, srcPath)
                                if (!src.exists()) { failCount++; continue }
                                var n = src.name
                                var i = 1
                                while (n in used) {
                                    val e = src.extension
                                    n = if (e.isNotEmpty()) "${src.nameWithoutExtension} ($i).$e" else "${src.name} ($i)"
                                    i++
                                }
                                used.add(n)
                                val dst = File(staging, n)
                                if (src.isDirectory) src.copyRecursively(dst) else src.copyTo(dst)
                            }
                            val outF = uniqueFile(outDir, "archive.$ext")
                            val ok = compressDispatch(staging, outF, fmt, level, pw, ctx.prefs
                                ?: throw IllegalStateException("prefs required"))
                            if (ok) okCount++ else failCount++
                            if (ok) packResultLines.add(ctx.text(R.string.cli_pack_ok, outF.name))
                        } finally {
                            staging.deleteRecursively()
                        }
                    } else {
                        // 分别压缩：每个源一个产物；单文件 + zip/7z/tar 系需临时目录包裹
                        for (srcPath in pos) {
                            val src = UuText.resolve(ctx.cwd, srcPath)
                            if (!src.exists()) { failCount++; continue }
                            val outF = uniqueFile(outDir, src.name + "." + ext)
                            val wrap = src.isFile && fmt in setOf("zip", "7z", "tar", "tgz", "tbz2", "txz", "tzst")
                            val wrapDir = if (wrap) File(ctx.cacheDir, "uu_pack/wrap_${System.nanoTime()}") else null
                            try {
                                if (wrap) {
                                    wrapDir!!.mkdirs()
                                    src.copyTo(File(wrapDir, src.name))
                                }
                                val from = wrapDir ?: src
                                val ok = compressDispatch(from, outF, fmt, level, pw, ctx.prefs
                                    ?: throw IllegalStateException("prefs required"))
                                if (ok) okCount++ else failCount++
                                if (ok) packResultLines.add(ctx.text(R.string.cli_pack_ok, outF.name))
                            } finally {
                                wrapDir?.deleteRecursively()
                            }
                        }
                    }
                } finally {
                    done.set(true)
                    poller?.join(600)
                }
                if (okCount == 0) return Result(UuText.packFailed(ctx.str, ext), 1)
                val sb = StringBuilder()
                for (line in packResultLines) { sb.append(line).append('\n') }
                sb.append(ctx.text(R.string.cli_all_set))
                if (failCount > 0) sb.insert(0, ctx.text(R.string.cli_pack_partial, failCount) + "\n")
                return Result(sb.toString().trimEnd('\n'))
            } finally {
                opH?.release()
            }
        }

        // ── 单源模式 ──
        // 多源必须 -c/-s：否则 "uu c a.txt b.zip c.zip" 会把 b.zip 当产物、
        // c.zip 当等级，静默打出错误的包。
        if (pos.size > 3 || (pos.size == 3 && pos[2].toIntOrNull() == null)) {
            return Result(ctx.text(R.string.cli_pack_multi_needs_flag), 1)
        }
        val src = UuText.resolve(ctx.cwd, pos[0])
        if (!src.exists()) return Result(UuText.notFound(ctx.str, pos[0]), 1)

        // 格式 key：-f 优先；否则从产物名后缀反查（最长后缀优先，tar.gz 完整匹配）。
        // 单源无 out：格式必须 -f 给出（没有后缀可反查，不猜）。
        val fmt: String = if (forceKey != null) {
            if (forceKey !in COMPRESS_EXT.keys) return Result(UuText.packUnknownKey(ctx.str, forceKey), 1)
            forceKey
        } else if (pos.size >= 2) {
            val outName = pos[1]
            val candidates = COMPRESS_EXT.entries
                .filter { outName.endsWith(".${it.value}") }
                .sortedByDescending { it.value.length }
            val hits = candidates.map { it.key }
            when {
                hits.isEmpty() -> return Result(
                    UuText.packNoKeyForExt(ctx.str, outName.substringAfterLast('.', outName)), 1)
                hits.map { COMPRESS_EXT[it] }.distinct().size > 1 ->
                    return Result(UuText.packAmbiguousExt(ctx.str,
                        outName.substringAfterLast('.'), hits.joinToString(", ")), 1)
                else -> hits.first()
            }
        } else {
            return Result(ctx.text(R.string.cli_pack_need_format), 1)
        }

        val level = pos.getOrNull(2)?.toIntOrNull() ?: 6
        val splitMb = pos.getOrNull(3)?.toLongOrNull() ?: 0L
        if (splitMb > 0 && fmt !in setOf("zip", "7z")) {
            return Result(UuText.packSplitUnsupported(ctx.str, fmt), 1)
        }
        val splitBytes = if (splitMb > 0) splitMb * 1024 * 1024 else null

        val act = ctx.activity
        val opH = act?.let { tryStartOperation(it, fmt) }
        try {
            if (opH?.await() == false) return Result(UuText.extractCancelled(ctx.str), 1)
            // 拿到槽位后再解析产物名（不变量：排队中的两次打包不能在入队时撞名）。
            val outFile = if (pos.size >= 2) UuText.resolve(ctx.cwd, pos[1])
                          else uniqueFile(defaultOut(ctx), src.name + "." + COMPRESS_EXT[fmt])
            if (outFile.exists()) return Result(ctx.text(R.string.cli_exists, outFile.path), 1)
            outFile.parentFile?.mkdirs()

            val accessors = compressAccessors(fmt)
            val done = AtomicBoolean(false)
            val poller = if (ctx.progress != null) thread {
                while (!done.get()) {
                    ctx.progress?.invoke(progressLine(accessors))
                    Thread.sleep(200)
                }
            } else null

            // zip/7z/tar 系的 Rust 端 read_dir 不接受单文件输入：单文件需临时目录包裹
            val wrap = src.isFile && fmt in setOf("zip", "7z", "tar", "tgz", "tbz2", "txz", "tzst")
            val wrapDir = if (wrap) File(ctx.cacheDir, "uu_pack/wrap_${System.nanoTime()}") else null
            val ok = try {
                if (wrap) {
                    wrapDir!!.mkdirs()
                    src.copyTo(File(wrapDir, src.name))
                }
                compressDispatch(if (wrap) wrapDir!! else src, outFile, fmt, level, pw, ctx.prefs
                    ?: throw IllegalStateException("prefs required"), splitBytes)
            } finally {
                wrapDir?.deleteRecursively()
                done.set(true)
                poller?.join(600)
            }

            if (!ok) return Result(UuText.packFailed(ctx.str, outFile.name), 1)
            return Result(ctx.text(R.string.cli_pack_ok, outFile.name) + "\n" +
                ctx.text(R.string.cli_all_set))
        } finally {
            opH?.release()
        }
    }

    /**
     * 进度行（产品规格）：`[===>   ] 45% (12.3MB/27.4MB) 当前文件名 (45/200)`。
     * 总量未知（头里没有）时退化为字节数 + 文件名；30 字符 ASCII 条约 600px。
     */
    private fun progressLine(a: ProgressAccessors): String {
        val cur = a.getCount()
        val tot = a.getTotal()
        val fc = a.getFileCount()
        val ft = a.getFileTotal()
        val name = (a.getName() ?: "").takeLast(28)
        val barW = 30
        val bar: String
        val pctTxt: String
        if (tot > 0) {
            val filled = ((cur * barW) / tot).toInt().coerceIn(0, barW)
            bar = buildString {
                append('[')
                repeat(filled) { append('=') }
                if (filled < barW) {
                    append('>')
                    repeat(barW - filled - 1) { append(' ') }
                }
                append(']')
            }
            pctTxt = "${(cur * 100 / tot).coerceAtMost(100)}%"
        } else {
            bar = "[" + " ".repeat(barW) + "]"
            pctTxt = "..."
        }
        val sizeTxt = if (tot > 0) "(${fmt(cur)}/${fmt(tot)})" else fmt(cur)
        val countTxt = if (ft > 0) " (${fc}/${ft})" else ""
        return "$bar $pctTxt $sizeTxt $name$countTxt"
    }

    /**
     * 拆出 `-k value`（[valueKeys]）与 `-b` 布尔（[boolKeys]）旗标；其余按顺序返回。
     */
    private fun splitFlags(
        args: List<String>, valueKeys: List<String>, boolKeys: List<String> = emptyList()
    ): Triple<Map<String, String>, Set<String>, List<String>> {
        val flags = mutableMapOf<String, String>()
        val bools = mutableSetOf<String>()
        val pos = ArrayList<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a in valueKeys -> { flags[a] = args.getOrNull(i + 1) ?: ""; i += 2 }
                a in boolKeys -> { bools.add(a); i++ }
                else -> { pos.add(a); i++ }
            }
        }
        return Triple(flags, bools, pos)
    }

    private fun scan(args: List<String>, ctx: Ctx): Result {
        if (args.isEmpty()) return needFile()
        val f = UuText.resolve(ctx.cwd, args[0])
        if (!f.isFile) return Result(UuText.notFound(ctx.str, args[0]), 1)

        val scan = ScanCore.scanFile(f.absolutePath)
            ?: return Result(UuText.scanFailed(ctx.str, f.name), 1)
        val hits = parseScanHits(scan)
        if (hits.isEmpty()) return Result(UuText.scanNone(ctx.str, f.name))

        // 非归档命中（png/jpeg/pdf…）只能 dd 出来，不能 x/l —— 它们没有容器。
        val keyOf: (String) -> String? = ::archiveKeyForLabel
        val fds = ctx.fds?.register(f, hits, f.length(), f.lastModified(), keyOf)
        val sb = StringBuilder()
        sb.append(UuText.scanHeader(ctx.str, f.name, hits.size)).append('\n')
        for ((i, h) in hits.withIndex()) {
            val fdTag = if (fds != null) "f${fds[i]}" else "-"
            val what = scanWhat(ctx, h)
            sb.append("  ").append(fdTag.padEnd(8))
                .append(hexOffset(h.offset).padEnd(24))
                .append(h.label.padEnd(26)).append(what).append('\n')
        }
        if (fds != null) sb.append(UuText.scanFdHint(ctx.str, fds.size))
        return Result(sb.toString().trimEnd('\n'))
    }

    private fun scanWhat(ctx: Ctx, h: ScanHit): String = when {
        h.fileCount != null -> ctx.text(R.string.cli_scan_entries, h.fileCount)
        h.size != null -> fmt(h.size)
        else -> ""
    }

    private fun hexOffset(v: Long) = "0x%08X".format(v)

    private fun staleMsg(str: StrFn, r: FdTable.Rejected): String = when (r) {
        is FdTable.Rejected.Stale -> UuText.fdStale(str, "f${r.fd}")
        is FdTable.Rejected.NoSuchFd -> UuText.noSuchFd(str, r.name)
    }

    // ─── cat / grep / 文件命令 / set / cso ──────────────────────────────

    /** `uu cat <archive> <entry> [-p pw]` → 打印包内文本条目（编码自动探测，有界读取）。 */
    private fun cat(args: List<String>, ctx: Ctx): Result {
        val (flags, _, pos) = splitFlags(args, listOf("-p"))
        if (pos.size < 2) return needFile()
        val pw = flags["-p"] ?: ctx.password ?: ""
        var temp: File? = null
        val archive: File = when (val r = resolveSource(pos[0], ctx)) {
            is SrcSpec.Fail -> return r.result
            is SrcSpec.Path -> {
                if (!r.f.isFile) return Result(UuText.notFound(ctx.str, pos[0]), 1)
                r.f
            }
            is SrcSpec.Fd -> { temp = r.temp; r.f }
        }
        try {
            val fmt = detectFormat(archive) ?: detectFormatByMagic(archive)
                ?: return Result(UuText.extractBadFormat(ctx.str, "-"), 1)
            val rel = sanitizeEntryPath(pos[1])
                ?: return Result(UuText.notFound(ctx.str, pos[1]), 1)
            val tmpDir = ctx.cacheDir?.let { File(it, "uu_cat/${System.nanoTime()}") }
                ?: return Result(UuText.needsActivity(ctx.str), 2)
            try {
                val o = extractByFormat(fmt, archive.path, tmpDir.path, rel, ctx.prefs
                    ?: throw IllegalStateException("prefs required"), pw)
                val f = File(tmpDir, rel)
                if (!f.isFile) {
                    return Result(ctx.text(R.string.cli_set_no_entry, pos[1]), 1)
                }
                val bytes = readPrefix(f, CAT_MAX_BYTES)
                val enc = detectBestEncoding(bytes)
                    ?: return Result(ctx.text(R.string.cli_cat_binary, pos[1], fmt(f.length())), 1)
                var text = decodeTextStrict(bytes, enc)
                if (text.length > CAT_MAX_CHARS) text = text.take(CAT_MAX_CHARS)
                val truncated = f.length() > CAT_MAX_BYTES || text.length >= CAT_MAX_CHARS
                return Result(text + if (truncated)
                    "\n" + ctx.text(R.string.cli_cat_truncated, fmt(CAT_MAX_BYTES)) else "")
            } finally {
                tmpDir.deleteRecursively()
            }
        } finally {
            temp?.deleteRecursively()
        }
    }

    /**
     * `uu grep [-i] <pattern> <path>` — 目录内按行搜文本；path 是归档时先把
     * 文本条目解到临时目录再搜（与预览内搜索同款文本扩展名过滤）。
     */
    private fun grep(args: List<String>, ctx: Ctx): Result {
        val (_, bools, pos) = splitFlags(args, emptyList(), listOf("-i"))
        if (pos.size < 2) return needFile()
        val pattern = pos[0]
        val ci = "-i" in bools
        val target = UuText.resolve(ctx.cwd, pos[1])
        if (!target.exists()) return Result(UuText.notFound(ctx.str, pos[1]), 1)

        var tempDir: File? = null
        val root: File = if (target.isFile) {
            val fmt = detectFormat(target) ?: detectFormatByMagic(target)
                ?: return Result(UuText.extractBadFormat(ctx.str, "-"), 1)
            val d = ctx.cacheDir?.let { File(it, "uu_grep/${System.nanoTime()}") }
                ?: return Result(UuText.needsActivity(ctx.str), 2)
            tempDir = d
            val json = listEntriesJsonFor(ctx, fmt, target, ctx.password ?: "")
                ?: return Result(UuText.listFailed(ctx.str, target.name), 1)
            val entries = parseEntries(json)
            for (e in entries) {
                if (e.isDirectory) continue
                val ext = e.name.substringAfterLast('.').lowercase()
                if (ext !in TEXT_SEARCH_EXTS) continue
                val rel = sanitizeEntryPath(e.path) ?: continue
                extractByFormat(fmt, target.path, d.path, rel, ctx.prefs
                    ?: throw IllegalStateException("prefs required"), ctx.password ?: "")
            }
            d
        } else target

        try {
            var matches = 0
            var fileName = 0
            val sb = StringBuilder()
            root.walkTopDown().forEach { f ->
                if (!f.isFile) return@forEach
                // 目录搜索只查文本类；归档解出的临时内容已是白名单
                if (tempDir == null && f.extension.lowercase() !in TEXT_SEARCH_EXTS) return@forEach
                if (f.length() > GREP_MAX_FILE_BYTES) return@forEach
                val bytes = runCatching { readPrefix(f, GREP_MAX_FILE_BYTES) }.getOrNull() ?: return@forEach
                val enc = detectBestEncoding(bytes) ?: return@forEach
                val text = runCatching { decodeTextStrict(bytes, enc) }.getOrNull() ?: return@forEach
                var hit = false
                var lineNo = 0
                for (line in text.lineSequence()) {
                    lineNo++
                    val isHit = if (ci) line.contains(pattern, ignoreCase = true)
                                else line.contains(pattern)
                    if (isHit) {
                        matches++
                        hit = true
                        if (sb.length < GREP_MAX_PRINT_CHARS) {
                            sb.append(f.relativeTo(root).path).append(':').append(lineNo)
                                .append(": ").append(line.trim().take(160)).append('\n')
                        }
                    }
                }
                if (hit) fileName++
            }
            if (matches == 0) return Result(ctx.text(R.string.cli_grep_none))
            val head = ctx.text(R.string.cli_grep_result, matches.toString(), fileName.toString())
            val body = sb.toString().trimEnd('\n')
            return Result(head + (if (body.isEmpty()) "" else "\n$body"))
        } finally {
            tempDir?.deleteRecursively()
        }
    }

    /** `uu rm <path...> [-f]` — 默认进回收站（可恢复），`-f` 永久删除。 */
    private fun rm(args: List<String>, ctx: Ctx): Result {
        val (_, bools, pos) = splitFlags(args, emptyList(), listOf("-f"))
        if (pos.isEmpty()) return needFile(Picker.FILE_OR_FOLDER)
        val hard = "-f" in bools
        val act = ctx.activity
        if (!hard && act == null) return Result(UuText.needsActivity(ctx.str), 2)
        var ok = 0
        var fail = 0
        for (p in pos) {
            val f = UuText.resolve(ctx.cwd, p)
            if (!f.exists()) { fail++; continue }
            val done = if (hard) runCatching { f.deleteRecursively() }.getOrDefault(false)
                       else runCatching { RecycleBin.moveToRecycleBin(act!!, f) }.getOrDefault(false)
            if (done) ok++ else fail++
        }
        val msg = ctx.text(if (hard) R.string.cli_rm_deleted else R.string.cli_rm_recycled, ok.toString())
        return if (fail == 0) Result(msg)
               else Result(msg + "\n" + ctx.text(R.string.cli_pack_partial, fail.toString()), 1)
    }

    /** `uu mkdir <path...>` */
    private fun mkdir(args: List<String>, ctx: Ctx): Result {
        if (args.isEmpty()) return needFile(Picker.FILE_OR_FOLDER)
        var ok = 0
        var fail = 0
        for (p in args) {
            val f = UuText.resolve(ctx.cwd, p)
            if (f.isDirectory || f.mkdirs()) ok++ else fail++
        }
        val msg = ctx.text(R.string.cli_mkdir_done, ok.toString())
        return if (fail == 0) Result(msg)
               else Result(msg + "\n" + ctx.text(R.string.cli_pack_partial, fail.toString()), 1)
    }

    /** `uu mv <src> <dst>` — 同卷 rename 原子；跨卷先复制成功再删源。 */
    private fun mv(args: List<String>, ctx: Ctx): Result {
        if (args.size < 2) return needFile()
        val src = UuText.resolve(ctx.cwd, args[0])
        if (!src.exists()) return Result(UuText.notFound(ctx.str, args[0]), 1)
        var dst = UuText.resolve(ctx.cwd, args[1])
        if (dst.isDirectory) dst = File(dst, src.name)
        if (dst.exists()) return Result(ctx.text(R.string.cli_exists, dst.path), 1)
        if (src.isDirectory && dst.canonicalPath.startsWith(src.canonicalPath + File.separator)) {
            return Result(ctx.text(R.string.cli_failed, dst.path), 1)
        }
        if (src.renameTo(dst)) return Result(ctx.text(R.string.cli_mv_done, src.name, dst.path))
        return try {
            if (src.isDirectory) src.copyRecursively(dst, overwrite = false)
            else { dst.parentFile?.mkdirs(); src.copyTo(dst, overwrite = false) }
            if (src.isDirectory) src.deleteRecursively() else src.delete()
            Result(ctx.text(R.string.cli_mv_done, src.name, dst.path))
        } catch (e: Exception) {
            dst.deleteRecursively()  // 半成品清掉；复制失败绝不删源
            Result(UuText.failed(ctx.str, e.message ?: src.name), 1)
        }
    }

    /** `uu tree [dir] [depth]` — 目录树（默认深 3，最多 TREE_MAX 行）。 */
    private fun tree(args: List<String>, ctx: Ctx): Result {
        val pos = args.filter { !it.startsWith("-") }
        val root = if (pos.isEmpty()) ctx.cwd else UuText.resolve(ctx.cwd, pos[0])
        if (!root.isDirectory) return Result(UuText.notFound(ctx.str, pos.getOrNull(0) ?: "."), 1)
        val depth = pos.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 3
        val sb = StringBuilder()
        var count = 0
        var truncated = false
        fun walk(d: File, prefix: String, level: Int) {
            val kids = d.listFiles()?.sortedBy { it.name.lowercase() } ?: return
            for ((i, f) in kids.withIndex()) {
                if (count >= TREE_MAX) { truncated = true; return }
                val last = i == kids.size - 1
                sb.append(prefix).append(if (last) "`-- " else "|-- ")
                    .append(f.name).append(if (f.isDirectory) "/" else "").append('\n')
                count++
                if (f.isDirectory && level < depth) walk(f, prefix + (if (last) "    " else "|   "), level + 1)
            }
        }
        sb.append(root.absolutePath).append('\n')
        walk(root, "", 1)
        if (truncated) sb.append(ctx.text(R.string.cli_tree_truncated, TREE_MAX.toString())).append('\n')
        return Result(sb.toString().trimEnd('\n'))
    }

    /** `uu du <path>` — 递归统计大小与文件数。 */
    private fun du(args: List<String>, ctx: Ctx): Result {
        if (args.isEmpty()) return needFile(Picker.FILE_OR_FOLDER)
        val f = UuText.resolve(ctx.cwd, args[0])
        if (!f.exists()) return Result(UuText.notFound(ctx.str, args[0]), 1)
        var bytes = 0L
        var files = 0
        if (f.isFile) { bytes = f.length(); files = 1 }
        else f.walkTopDown().forEach { if (it.isFile) { bytes += runCatching { it.length() }.getOrDefault(0L); files++ } }
        return Result(ctx.text(R.string.cli_du_result, fmt(bytes), files.toString()))
    }

    /** `uu stat <path...>` — 类型 / 大小 / 修改时间。 */
    private fun stat(args: List<String>, ctx: Ctx): Result {
        if (args.isEmpty()) return needFile()
        val df = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        val lines = args.map { p ->
            val f = UuText.resolve(ctx.cwd, p)
            if (!f.exists()) ctx.text(R.string.cli_not_found, p)
            else ctx.text(R.string.cli_stat_line,
                if (f.isDirectory) "dir" else "file",
                if (f.isDirectory) "-" else fmt(f.length()),
                df.format(java.util.Date(f.lastModified())))
        }
        return Result(lines.joinToString("\n"))
    }

    /**
     * `uu set <archive> <entry> <localfile> [-p pw]` — 替换归档内单个条目,
     * 输出 `名字-cn.ext` 副本（原包不动）。ZIP 走 zipModify（未动条目字节级
     * 保留）；其余可封包格式走整解 → 覆盖 → 重封。
     */
    private fun setEntry(args: List<String>, ctx: Ctx): Result {
        val (flags, _, pos) = splitFlags(args, listOf("-p"))
        if (pos.size < 3) return needFile()
        val pw = flags["-p"] ?: ""
        val archive = UuText.resolve(ctx.cwd, pos[0])
        if (!archive.isFile) return Result(UuText.notFound(ctx.str, pos[0]), 1)
        val rel = sanitizeEntryPath(pos[1])
            ?: return Result(UuText.notFound(ctx.str, pos[1]), 1)
        val srcFile = UuText.resolve(ctx.cwd, pos[2])
        if (!srcFile.isFile) return Result(UuText.notFound(ctx.str, pos[2]), 1)
        val fmt = detectFormat(archive) ?: detectFormatByMagic(archive)
            ?: return Result(UuText.extractBadFormat(ctx.str, "-"), 1)
        if (fmt == "rar") return Result(ctx.text(R.string.cli_set_unsupported, "RAR"), 1)

        val parent = archive.parentFile ?: ctx.cwd
        val act = ctx.activity

        // ZIP 快路径：zipModify replace（未动条目字节级保留）
        val isSplitZip = fmt == "zip" && (isVolumeFile(archive) == "zip" || isZipVolumeName(archive.name))
        if (fmt == "zip" && !isSplitZip) {
            val opH = act?.let { tryStartOperation(it, "zip") }
            try {
                if (opH?.await() == false) return Result(UuText.extractCancelled(ctx.str), 1)
                val outF = uniqueFile(parent, "${archive.nameWithoutExtension}-cn.zip")
                val tmp = File(parent, "${archive.nameWithoutExtension}.set.zip")
                val ok = ZipCore.zipModify("", archive.path, tmp.path, "replace|$rel|${srcFile.path}", pw)
                if (!ok) {
                    tmp.delete()
                    return Result(UuText.packFailed(ctx.str, outF.name), 1)
                }
                java.nio.file.Files.move(tmp.toPath(), outF.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                return Result(ctx.text(R.string.cli_set_done, rel, outF.name) + "\n" +
                    ctx.text(R.string.cli_all_set))
            } catch (e: Exception) {
                return Result(UuText.failed(ctx.str, e.message ?: rel), 1)
            } finally {
                opH?.release()
            }
        }

        // 通用路径：整解 → 覆盖条目 → 重封（需要该格式支持封包）
        if (fmt !in COMPRESS_EXT.keys) return Result(UuText.packUnknownKey(ctx.str, fmt), 1)
        val staging = ctx.cacheDir?.let { File(it, "uu_set/${System.nanoTime()}") }
            ?: return Result(UuText.needsActivity(ctx.str), 2)
        val opH = act?.let { tryStartOperation(it, fmt) }
        try {
            if (opH?.await() == false) return Result(UuText.extractCancelled(ctx.str), 1)
            var acc = extractAccessors(fmt)
            val done = AtomicBoolean(false)
            val poller = if (ctx.progress != null) thread {
                while (!done.get()) {
                    ctx.progress?.invoke(progressLine(acc))
                    Thread.sleep(200)
                }
            } else null
            try {
                val o = extractByFormat(fmt, archive.path, staging.path, "", ctx.prefs
                    ?: throw IllegalStateException("prefs required"), pw)
                if (!o.counts.ok) {
                    return Result(act?.let { friendlyExtractError(it, o.error) } ?: (o.error ?: rel), 1)
                }
                val target = File(staging, rel)
                if (!target.isFile) return Result(ctx.text(R.string.cli_set_no_entry, rel), 1)
                target.parentFile?.mkdirs()
                srcFile.copyTo(target, overwrite = true)
                val outExt = archive.extension.ifEmpty { COMPRESS_EXT[fmt] ?: fmt }
                val outF = uniqueFile(parent, "${archive.nameWithoutExtension}-cn.$outExt")
                acc = compressAccessors(fmt)
                val level = ctx.prefs?.let {
                    if (fmt == "zip") it.getInt("zip_level", 5) else it.getInt("generic_level", 6)
                } ?: 6
                val ok = compressDispatch(staging, outF, fmt, level, pw, ctx.prefs
                    ?: throw IllegalStateException("prefs required"))
                if (!ok) return Result(UuText.packFailed(ctx.str, outF.name), 1)
                return Result(ctx.text(R.string.cli_set_done, rel, outF.name) + "\n" +
                    ctx.text(R.string.cli_all_set))
            } finally {
                done.set(true)
                poller?.join(600)
                staging.deleteRecursively()
            }
        } finally {
            opH?.release()
        }
    }

    /** `uu cso <file> [out]` — ISO→CSO / CSO→ISO。 */
    private fun cso(args: List<String>, ctx: Ctx): Result {
        val pos = args.filter { !it.startsWith("-") }
        if (pos.isEmpty()) return needFile()
        val src = UuText.resolve(ctx.cwd, pos[0])
        if (!src.isFile) return Result(UuText.notFound(ctx.str, pos[0]), 1)
        val toCso = when (src.extension.lowercase()) {
            "iso" -> true
            "cso" -> false
            else -> return Result(ctx.text(R.string.cli_cso_bad_ext, src.name), 1)
        }
        val outF = pos.getOrNull(1)?.let { UuText.resolve(ctx.cwd, it) }
            ?: uniqueFile(defaultOut(ctx), "${src.nameWithoutExtension}.${if (toCso) "cso" else "iso"}")
        if (outF.exists()) return Result(ctx.text(R.string.cli_exists, outF.path), 1)
        val act = ctx.activity
        val opH = act?.let { tryStartOperation(it, "cso") }
        try {
            if (opH?.await() == false) return Result(UuText.extractCancelled(ctx.str), 1)
            val acc = ProgressAccessors(
                { CsoCore.csoProgressCount() }, { CsoCore.csoProgressTotal() },
                { CsoCore.csoProgressFileCount() }, { CsoCore.csoProgressFileTotal() },
                { CsoCore.csoProgressName() }, { CsoCore.csoCancel() }
            )
            val done = AtomicBoolean(false)
            val poller = if (ctx.progress != null) thread {
                while (!done.get()) {
                    ctx.progress?.invoke(progressLine(acc))
                    Thread.sleep(200)
                }
            } else null
            val ok = try {
                if (toCso) CsoCore.isoToCso("", src.path, outF.path, "2048")
                else CsoCore.csoToIso("", src.path, outF.path)
            } finally {
                done.set(true)
                poller?.join(600)
            }
            if (!ok) {
                outF.delete()
                return Result(UuText.packFailed(ctx.str, outF.name), 1)
            }
            return Result(ctx.text(R.string.cli_cso_done, outF.name) + "\n" +
                ctx.text(R.string.cli_all_set))
        } finally {
            opH?.release()
        }
    }

    /**
     * 通配符展开（所有命令共用）：参数含 `*`/`?` 时在 cwd 内展开为实际路径；
     * **无匹配保留原样**（错误信息里能看到用户输入的模式）。目录部分不参与
     * 匹配（带子目录的模式先拆 parent 再匹配文件名）。
     */
    fun expandGlobs(tokens: List<String>, cwd: File): List<String> {
        if (tokens.size <= 1) return tokens
        val out = ArrayList<String>(tokens.size)
        for ((i, t) in tokens.withIndex()) {
            if (i == 0 || !CliGlob.hasWildcards(t)) { out.add(t); continue }
            val f = UuText.resolve(cwd, t)
            val dir = f.parentFile ?: cwd
            val matches = dir.listFiles { c -> CliGlob.matches(c.name, f.name) }
                ?.sortedBy { it.name.lowercase() }.orEmpty()
            if (matches.isEmpty()) out.add(t) else matches.forEach { out.add(it.absolutePath) }
        }
        return out
    }

    // ─── 内部常量 / 辅助 ──────────────────────────────────────────────────

    /** `uu l` 默认最多打印这么多条，末尾给一行汇总。 */
    const val LIST_PREVIEW_LIMIT = 500

    /** `uu cat` 有界读取：2MB 字节 / 100k 字符（scrollback 友好）。 */
    private const val CAT_MAX_BYTES = 2 * 1024 * 1024L
    private const val CAT_MAX_CHARS = 100_000

    /** `uu tree` 最多打印 TREE_MAX 行。 */
    private const val TREE_MAX = 500

    /** `uu grep`：单文件读取上限 / 打印字符上限。 */
    private const val GREP_MAX_FILE_BYTES = 5L * 1024 * 1024
    private const val GREP_MAX_PRINT_CHARS = 60_000

    /**
     * scan-core 的 label → 归档格式 key；非归档命中返回 null（只能 dd，不能 x/l）。
     * 复用 GUI 那张表（`SignatureScan.kt`），**不**在这里手抄第二份 ——
     * 手抄的版本当场就漂了（多了 ypf、少了 POSIX tar）。
     */
    private fun archiveKeyForLabel(label: String): String? = ARCHIVE_LABELS[label]
}
