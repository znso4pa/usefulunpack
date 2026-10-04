package com.usefulunpacker

import android.content.SharedPreferences
import java.io.File

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
        val prefs: SharedPreferences,
        /** 会话当前目录，所有相对路径都相对它解析。 */
        val cwd: File,
        /** 需要弹 UI 时才非空（密码框、错误提示、完成后导航）。 */
        val activity: MainActivity? = null,
        /** 取本地化文案。单测用 stub 即可断言到资源 id 级别。 */
        val str: StrFn = { id, _ -> "!str:$id" },
        /** 扫描建立的 FD 表（`uu scan` 的产物）。null = 本轮未扫描过。 */
        val fds: FdTable? = null,
        /** `uu x -p` / `uu l -p` 提供的密码；null = 没给。 */
        val password: String? = null,
        /** 临时文件根目录（fdN 临时 carve 用）。 */
        val cacheDir: File? = null,
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

    /** 要用户补一个路径。[FILE] 允许选文件，[FOLDER] 只收目录。 */
    enum class Picker { FILE, FOLDER }

    /** 需要文件参数的命令缺参时用它。 */
    private fun needFile(p: Picker = Picker.FILE) = Result("", 2, p)

    internal enum class Kind { LIST_FORMATS, HELP, INFO, LIST, EXTRACT, PACK, SCAN }

    internal class Cmd(val name: String, val kind: Kind, val summary: String, val usage: String)

    /**
     * 命令表。新增命令只加这一行 —— `uu help` 会自动跟上。
     * 摘要/用法暂用英文，与 `COMPRESS_LABELS` 的既有做法一致（帮助文本不进资源数组，
     * 因为它是结构化数据的一部分；用户可见的**句子**才走 UuText）。
     */
    private val table: List<Cmd> = listOf(
        Cmd("fmt",   Kind.LIST_FORMATS, "List every supported format",                "uu fmt"),
        Cmd("help",  Kind.HELP,         "Show this help",                              "uu help"),
        Cmd("info",  Kind.INFO,         "Print the detected format of a file",          "uu info <file>"),
        Cmd("l",     Kind.LIST,         "List the entries of an archive",               "uu l <archive>"),
        Cmd("x",     Kind.EXTRACT,      "Extract an archive, optionally selected entries", "uu x <archive> [outdir] [entry...]"),
        Cmd("c",     Kind.PACK,         "Pack a file or folder",                        "uu c <src> <out.ext> [-f key] [level] [splitMB]"),
        Cmd("scan",  Kind.SCAN,         "Scan a file for embedded archive signatures",   "uu scan <file>"),
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
                Kind.EXTRACT -> extract(args, ctx)
                Kind.PACK -> pack(args, ctx)
                Kind.SCAN -> scan(args, ctx)
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

        // fN → 该片段是宿主文件的一段；能当归档读时先临时 carve 出来再列
        val fd = ctx.fds?.get(spec.removePrefix("f").toIntOrNull() ?: -1)
        if (fd != null) {
            if (!fd.isArchive()) {
                return Result(UuText.fdNotArchive(ctx.str, spec, fd.label), 2)
            }
            ctx.fds.checkFresh(fd)?.let { return Result(staleMsg(ctx.str, it), 1) }
            val carved = ctx.cacheDir?.let { File(it, "uufd/fd${fd.fd}-${fd.label.hashCode()}") }
                ?: return Result(UuText.needsActivity(ctx.str), 2)
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

    /**
     * `uu scan` —— 扫描签名，并给每个命中注册一个伪 FD，之后
     * `uu l f3` / `dd if=f3 of=x.zip` / `uu x f3` 可直接引用，不必先落盘。
     */
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
            sb.append("  ").append(fdTag).append(pad(hitColumn))
                .append(hexOffset(h.offset)).append(pad(labelColumn))
                .append(h.label).append(pad(descColumn)).append(what).append('\n')
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

    private fun pad(width: Int) = StringBuilder().apply { repeat(width) { append(' ') } }.toString()

    private fun staleMsg(str: StrFn, r: FdTable.Rejected): String = when (r) {
        is FdTable.Rejected.Stale -> UuText.fdStale(str, "f${r.fd}")
        is FdTable.Rejected.NoSuchFd -> UuText.noSuchFd(str, r.name)
    }

    private fun pack(args: List<String>, ctx: Ctx): Result = pending("uu c", ctx)
    private fun extract(args: List<String>, ctx: Ctx): Result = pending("uu x", ctx)

    private fun pending(cmd: String, ctx: Ctx) = Result(UuText.notYet(ctx.str, cmd), 1)

    // ─── 内部常量 / 辅助 ──────────────────────────────────────────────────

    /** `uu l` 默认最多打印这么多条，末尾给一行汇总。 */
    const val LIST_PREVIEW_LIMIT = 500

    private const val hitColumn = 8      // fdN
    private const val labelColumn = 24    // 0xXXXXXXXX
    private const val descColumn = 26     // label

    /**
     * 列条目。`listEntriesJson` 是 MainActivity 扩展但函数体只用到 prefs，
     * 所以这里不持有 Activity 也调得到 —— 单测能覆盖这条路径。
     */
    private fun listEntriesJsonFor(ctx: Ctx, fmt: String, f: File, pwd: String): String? {
        val host = ctx.activity ?: return null
        return host.listEntriesJson(fmt, f, pwd, nestedOnly = false)
    }

    /**
     * scan-core 的 label → 归档格式 key；非归档命中返回 null（只能 dd，不能 x/l）。
     * 复用 GUI 那张表（`SignatureScan.kt`），**不**在这里手抄第二份 ——
     * 手抄的版本当场就漂了（多了 ypf、少了 POSIX tar）。
     */
    private fun archiveKeyForLabel(label: String): String? = ARCHIVE_LABELS[label]
}
