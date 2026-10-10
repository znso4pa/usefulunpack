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
        /**
         * 命令侧注册「取消当前操作」的钩子（进度行可点 → 确认 → 调用）。
         * 命令在拿到 opH/accessors 后注册；终端层在命令结束时清掉。
         */
        val registerCancel: ((() -> Unit) -> Unit)? = null,
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

    /** 某命令的 usage 行（缺参时回显用法而不是弹无法收敛的选择器）。 */
    private fun usageOf(cmd: String): String = table.first { it.name == cmd }.usage

    /** x/c 不加路径时的默认输出位置（单独路径，产品要求）。 */
    private fun defaultOut(ctx: Ctx): File = (ctx.defaultOutDir ?: ctx.cwd).apply { mkdirs() }

    internal enum class Kind {
        LIST_FORMATS, HELP, DOCS, INFO, LIST, CAT, HASH, GREP, COPY, MV, RENAME, RM, MKDIR,
        TREE, DU, STAT, EXTRACT, PACK, SET, SCAN, CSO,
        ENC, MVDEC, RMD, ADD, FIND, DIFF, HEX, IMG, FD, B64
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
        Cmd("docs",   Kind.DOCS,         "Full command & parameter reference (table)",      "uu docs"),
        Cmd("info",   Kind.INFO,         "Print detected format key(s), one per line",            "uu info <file>"),
        Cmd("l",      Kind.LIST,         "List entries (-j JSON / -t tree / -S by size)",  "uu l <archive> [-a] [-j] [-t] [-S] [-p pw]"),
        Cmd("cat",    Kind.CAT,          "Print a text file or an archive's entry",        "uu cat <file|archive> [entry] [-e enc] [-p pw]"),
        Cmd("hash",   Kind.HASH,         "Print the MD5 and SHA-256 of files",            "uu hash <file>"),
        Cmd("grep",   Kind.GREP,         "Search text inside a folder or archive",         "uu grep [-i] <pattern> <path>"),
        Cmd("cp",     Kind.COPY,         "Copy a file or folder (-f overwrites)",          "uu cp <src> <dst> [-f]"),
        Cmd("mv",     Kind.MV,           "Move a file or folder (-f overwrites)",          "uu mv <src> <dst> [-f]"),
        Cmd("rn",     Kind.RENAME,       "Rename a file or folder (refuses to overwrite)", "uu rn <old> <new>"),
        Cmd("rm",     Kind.RM,           "Delete (recycle bin by default; -f permanent)",  "uu rm <path...> [-f]"),
        Cmd("mkdir",  Kind.MKDIR,        "Create folders",                                 "uu mkdir <path...>"),
        Cmd("tree",   Kind.TREE,         "Recursive directory listing",                    "uu tree [dir] [depth]"),
        Cmd("du",     Kind.DU,           "Show total sizes (sums when given several paths)",        "uu du <path>"),
        Cmd("stat",   Kind.STAT,         "Show type / size / modified time",               "uu stat <path...>"),
        Cmd("x",      Kind.EXTRACT,      "Extract an archive, optionally selected entries", "uu x <archive> [-o outdir] [entry...] [-p pw]"),
        Cmd("c",      Kind.PACK,         "Pack files or folders (merge / separate)",                          "uu c <src...> [out] [-c|-s] [-f key] [-l level] [-b splitMB] [-e cxdec] [-p pw]"),
        Cmd("set",    Kind.SET,          "Replace one entry inside an archive",            "uu set <archive> <entry> <localfile> [-p pw]"),
        Cmd("scan",   Kind.SCAN,         "Scan a file for embedded archive signatures",    "uu scan <file>"),
        Cmd("cso",    Kind.CSO,          "Convert ISO ↔ CSO",                              "uu cso <file> [out]"),
        Cmd("enc",    Kind.ENC,          "Convert text encoding (BOM kept)",               "uu enc <file> <from|auto> <to> [out]"),
        Cmd("mvdec",  Kind.MVDEC,        "Decode RPG Maker MV/MZ assets (no key needed)",  "uu mvdec <file...> [-o dir]"),
        Cmd("rmd",    Kind.RMD,          "Delete entries from a ZIP (name-cn copy)",       "uu rmd <zip> <entry...> [-p pw] (globs ok)"),
        Cmd("add",    Kind.ADD,          "Add a file to a ZIP (name-cn copy)",             "uu add <zip> <local> [name] [-p pw]"),
        Cmd("find",   Kind.FIND,         "Find files by name under a folder",              "uu find [dir] <glob> [depth]"),
        Cmd("diff",   Kind.DIFF,         "Compare entry lists of two archives (size-based)", "uu diff <a> <b> [-p pw]"),
        Cmd("hex",    Kind.HEX,          "Hex dump a byte range",                         "uu hex <file> [offset] [len]"),
        Cmd("img",    Kind.IMG,          "Convert images (jpg / png / webp)",             "uu img <src...> <jpg|png|webp> [-o dir] [-q 1-100]"),
        Cmd("fd",     Kind.FD,           "List the registered fN descriptors",            "uu fd [fN...]"),
        Cmd("b64",    Kind.B64,          "Base64 encode (default) or decode a file",      "uu b64 <file> [-d] [out]"),
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
                Kind.DOCS -> docs(ctx)
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
                Kind.ENC -> enc(args, ctx)
                Kind.MVDEC -> mvdec(args, ctx)
                Kind.RMD -> rmd(args, ctx)
                Kind.ADD -> add(args, ctx)
                Kind.FIND -> find(args, ctx)
                Kind.DIFF -> diff(args, ctx)
                Kind.HEX -> hex(args, ctx)
                Kind.IMG -> img(args, ctx)
                Kind.FD -> fd(args, ctx)
                Kind.B64 -> b64(args, ctx)
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
     * `uu docs` / 终端头「文档」按钮：完整命令表 + 子参数参考。
     * **从同一张 [table] 派生**（单一来源，不手抄第二份）。
     */
    private fun docs(ctx: Ctx): Result = Result(renderDocs())

    fun renderDocs(): String {
        val sb = StringBuilder()
        sb.append("UU CLI reference\n")
        sb.append("=".repeat(96)).append("\n")
        // The usage column is DERIVED from the table, not hardcoded: a fixed
        // width silently glues the longest usage to its description the moment
        // any usage grows (it did, when `uu c` gained `-e cxdec`).
        val usageWidth = table.maxOf { it.usage.length } + 2
        sb.append("COMMAND".padEnd(8)).append("USAGE".padEnd(usageWidth)).append("DESCRIPTION\n")
        sb.append("-".repeat(8)).append(' ').append("-".repeat(usageWidth - 1)).append(' ').append("-".repeat(30)).append('\n')
        for (c in table) {
            sb.append(c.name.padEnd(8))
                .append(c.usage.padEnd(usageWidth))
                .append(c.summary).append('\n')
        }
        sb.append('\n')
        sb.append("PARAMETERS\n")
        sb.append("-".repeat(10)).append('\n')
        val params = listOf(
            "-a            " to "list all entries (uu l; default caps at 500)",
            "-p <password> " to "archive password (uu l / cat / grep / x / c / set); empty -> prompt",
            "-f <key>      " to "explicit format key (uu c / set; see uu fmt)",
            "-c / -s       " to "merge into one archive / separate per source (uu c, multi-source)",
            "-l <level>    " to "compression level (uu c)",
            "-b <splitMB>  " to "split size in MB (uu c, zip/7z only)",
            "-e <scheme>   " to "encrypt the pack (uu c, xp3 only): cxdec | hashcrypt | fatecrypt (alias fsn) | appliquecrypt | flyingshinecrypt | alteredpinkcrypt | dameganecrypt — cxdec needs xp3filter.tjs/.tpm or an encrypted .xp3 in the folder, the keyless ones need nothing",
            "-o <outdir>   " to "output directory (uu x; entries then follow unambiguously)",
            "-f            " to "permanent delete instead of recycle bin (uu rm)",
            "-i            " to "case-insensitive search (uu grep)",
            "-j            " to "raw entry JSON for scripts (uu l): n/s/d/e keys",
        )
        for ((k, v) in params) sb.append("  ").append(k).append(v).append('\n')
        sb.append('\n')
        sb.append("SPECIALS\n")
        sb.append("-".repeat(8)).append('\n')
        sb.append("  fN            ").append("descriptor from \"ls\" / \"uu scan\" — uu l f0 / uu x f0 / uu cp f0 out.zip\n")
        sb.append("  * ?           ").append("wildcards expand in the current dir — uu x *.zip / uu hash *.png\n")
        sb.append("  |             ").append("pipe into a filter: grep / head / tail / wc / sort(+-r) — uu l a.zip | sort\n")
        sb.append("  > >>          ").append("redirect output to a file (>> appends) — uu l a.zip > list.txt\n")
        sb.append("  && ; ||       ").append("chain: && runs on success, || runs on failure — uu l f0 || uu scan f0\n")
        sb.append("  uu run <file> ").append("run a UUT script: uu run [-k] file.uut [args...] — see below\n")
        sb.append("  progress line ").append("tap to cancel a running uu x / c / set / cso operation\n")
        sb.append("  ls pwd cd help").append(" builtins; anything else runs in the system shell\n")
        sb.append('\n')
        sb.append("UUT SCRIPT (uu run <file.uut>)\n")
        sb.append("-".repeat(8)).append('\n')
        sb.append("  set v = text        ").append("assign a variable (\"\$v\" expands in later lines)\n")
        sb.append("  \$1 \$2 / \$argc / \$args").append(" script arguments: uu run wrap.uut a b → \$1=a \$2=b\n")
        sb.append("  set v = \$(uu info x) ").append("capture a command's output into v\n")
        sb.append("  if v = xp3 then ...  ").append("one-line branch; also \"if v != \"\" then ...\"\n")
        sb.append("  if v = xp3 / else / end").append(" block branch (nestable, else optional)\n")
        sb.append("  for a in *.zip ... end").append(" loop over wildcard matches (nestable)\n")
        sb.append("  for l in \$(uu fd)   ").append("iterate command output line by line\n")
        sb.append("  continue             ").append("skip to the next loop iteration\n")
        sb.append("  else if v = pfs      ").append("elif chain inside a block if (one shared end)\n")
        sb.append("  return [code]        ").append("stop this script with an exit code\n")
        sb.append("  for i in 1..5        ").append("counted loop (descending 5..1 works too)\n")
        sb.append("  if exist path / not exist path").append(" file & directory test (no wildcards)\n")
        sb.append("  \$? / \$errorlevel  ").append("exit code of the previous command\n")
        sb.append("  entries (uu x)       ").append("take * and ? matched against the full entry path — uu x a.zip \"*.png\"\n")
        sb.append("  if n > 3 / n <= 3    ").append("numeric compare (< > <= >=); = and != are literal text\n")
        sb.append("  set n = \$n + 1       ").append("arithmetic on integers (+ - * /), division by zero errors out\n")
        sb.append("  while n < 5 ... end  ").append("loop (stops with an error after ").append(UutParser.LOOP_MAX.toString()).append(" iterations)\n")
        sb.append("  break                ").append("leave the innermost for / while\n")
        sb.append("  ... # note           ").append("trailing comments: # at word start, not inside quotes\n")
        sb.append("  uu l -j <archive>    ").append("raw JSON: [{\"n\":name,\"s\":size,\"d\":isDir,\"e\":encrypted}] (one archive)\n")
        sb.append("  only uu / ls / cd / pwd / help / echo").append(" are allowed — no arbitrary shell\n")
        sb.append("  errors stop the script (-k continues); each line is echoed; \"\$\" escapes only variables\n")
        sb.append('\n')
        sb.append("Default output dir (x/c without a path): /storage/emulated/0/uu_cli\n")
        return sb.toString().trimEnd('\n')
    }

    /**
     * `uu info <file>` → 只输出一个格式 key，脚本可直接判断；
     * 识别不出时输出**空行**（退出码 0），因为「不是归档」是有效答案而不是错误。
     */
    private fun info(args: List<String>, ctx: Ctx): Result {
        rejectUnknownFlags(ctx, args)?.let { return it }
        if (args.isEmpty()) return needFile()
        // 多参数（通配展开后）逐个输出格式 key；不是归档输出空行
        val lines = args.map { p ->
            val f = UuText.resolve(ctx.cwd, p)
            if (!f.isFile) UuText.notFound(ctx.str, p)
            else (detectFormat(f) ?: detectFormatByMagic(f) ?: "")
        }
        return Result(lines.joinToString("\n"))
    }

    private fun list(args: List<String>, ctx: Ctx): Result {
        val pa = splitFlags(args, listOf("-p"), listOf("-a", "-j", "-t", "-S"))
        missingValueError(ctx, pa)?.let { return it }
        // -a = 打印全部条目；默认截断（有些归档几十万条，刷屏且吃光 scrollback）
        val showAll = "-a" in pa.bools
        // -j = 输出原始条目 JSON（脚本接口：n/s/d/e 四个键，见 uu docs）。
        // 只接受**一个**归档：多份 JSON 拼在一起没有合法解析方式。
        val asJson = "-j" in pa.bools
        val asTree = "-t" in pa.bools
        if (asJson && asTree) return Result(usageOf("l"), 2)
        if (asJson && "-S" in pa.bools) return Result(usageOf("l"), 2)   // JSON 交给脚本自己排
        val pw = pa.values["-p"]?.takeIf { it.isNotEmpty() } ?: ctx.password ?: ""
        if (pa.pos.isEmpty()) return needFile()
        if (asJson && pa.pos.size > 1) return Result(ctx.text(R.string.cli_json_one_file, pa.pos.size.toString()), 2)
        // 多归档（通配展开）逐个列出，各带一行标题
        if (pa.pos.size > 1) {
            val out = StringBuilder()
            for (spec in pa.pos) {
                out.append("── ").append(spec).append('\n')
                out.append(listOne(spec, showAll, pw, ctx).text).append('\n')
            }
            return Result(out.toString().trimEnd('\n'))
        }
        return listOne(pa.pos[0], showAll, pw, ctx, asJson, asTree, "-S" in pa.bools)
    }

    private fun listOne(spec: String, showAll: Boolean, pwd: String, ctx: Ctx, asJson: Boolean = false, asTree: Boolean = false, bySize: Boolean = false): Result {
        // fN → 整文件条目（ls 注册的）直接读 host；区间条目（scan 注册的）先
        // 临时 carve 出来再列
        when (val r = fdRef(spec, ctx)) {
            is FdRef.Stale -> return r.result
            is FdRef.Hit -> {
                val e = r.e
                if (e.wholeFile) {
                    return listEntries(e.host, e.host.name, showAll, pwd, ctx, asJson, asTree, bySize)
                }
                // 非归档命中（png/jpeg/pdf…）没有容器可列 —— 只能 `uu dd` 切出来。
                if (!e.isArchive()) return Result(UuText.fdNotArchive(ctx.str, spec, e.label), 2)
                val carved = carveFd(e, ctx)
                    ?: return Result(UuText.needsActivity(ctx.str), 2)
                try {
                    // 展示名用 scan 的 label；临时文件名是一串哈希没有信息量。
                    return listEntries(carved, e.label, showAll, pwd, ctx, asJson, asTree, bySize)
                } finally {
                    carved.deleteRecursively()
                }
            }
            FdRef.NotFd -> Unit
        }

        val f = UuText.resolve(ctx.cwd, spec)
        if (!f.isFile) return Result(UuText.notFound(ctx.str, spec), 1)
        return listEntries(f, f.name, showAll, pwd, ctx, asJson, asTree, bySize)
    }

    private fun listEntries(
        f: File, displayName: String, showAll: Boolean, pwd: String, ctx: Ctx,
        asJson: Boolean = false, asTree: Boolean = false, bySize: Boolean = false,
    ): Result {
        val fmt = detectFormat(f) ?: detectFormatByMagic(f)
            ?: return Result(UuText.extractBadFormat(ctx.str, "-"), 1)
        // 密码：先试空密码（大多数包没密码），失败再让用户加 -p。
        // 不弹模态 —— 模态在终端里很怪，而且可阻塞 30s 会冻住输出区。
        lastListError = ""
        val json = listEntriesJsonFor(ctx, fmt, f, pwd)
            ?: return Result(
                if (lastListError.isNotEmpty()) UuText.listFailedWhy(ctx.str, f.name, lastListError)
                else UuText.listFailed(ctx.str, f.name),
                1,
            )
        // 脚本接口：原样给 JNI 的那份 JSON（契约 [{"n":名字,"s":大小,"d":是否目录,"e":是否加密}]），
        // 不做截断也不加表头 —— 可解析性优先于好看。
        if (asJson) return Result(json)
        if (json == "[]") return Result(UuText.listEmpty(ctx.str, displayName))

        val entries0 = parseEntries(json)

        // -S：按大小降序（找游戏包里的大文件）；树视图是结构化的，不受影响
        val entries = if (bySize && !asTree) entries0.sortedByDescending { it.size } else entries0
        val total = entries.sumOf { if (!it.isDirectory) it.size else 0L }
        val sb = StringBuilder()
        sb.append(UuText.listHeader(ctx.str, displayName, entries.size, fmt(total)))
        // The encryption state, appended to the header. `uu info` deliberately
        // does NOT carry it: scripts branch on that key (`if v = xp3`), and a
        // decorated key would silently send them down the wrong path.
        val encLabel = xp3SchemeLabel(ctx.str, xp3SchemeToken(fmt, f))
        if (encLabel.isNotEmpty()) {
            sb.append(' ').append(ctx.text(R.string.enc_suffix_paren, encLabel))
        }
        sb.append('\n')
        // -t：树状视图（游戏归档嵌套很深，平铺列表读不动）。行数多时受 -a 同样的
        // 截断保护 —— 截断提示沿用列表那套。
        if (asTree) {
            val encMark = UuText.listEncMark(ctx.str)
            sb.append(renderEntryTree(entries) { e ->
                if (e.isDirectory) "/" else "  " + fmt(e.size) + if (e.isEncrypted) "  $encMark" else ""
            }).append('\n')
            if (!showAll && entries.size > LIST_PREVIEW_LIMIT) {
                sb.append(UuText.listTruncated(ctx.str, entries.size - LIST_PREVIEW_LIMIT)).append('\n')
            }
            return Result(sb.toString().trimEnd('\n'))
        }
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
        rejectUnknownFlags(ctx, args)?.let { return it }
        if (args.isEmpty()) return needFile()
        val multi = args.size > 1
        var anyOk = false
        val out = StringBuilder()
        for (a in args) {
            when (val r = resolveSource(a, ctx)) {
                is SrcSpec.Fail -> { if (!multi) return r.result; out.append(a).append(": ").append(r.result.text).append('\n'); continue }
                is SrcSpec.Path -> {
                    if (!r.f.isFile) {
                        if (!multi) return Result(UuText.notFound(ctx.str, a), 1)
                        out.append(UuText.notFound(ctx.str, a)).append('\n'); continue
                    }
                    if (multi) out.append(r.f.name).append(':').append('\n')
                    out.append(hashOf(r.f).text).append('\n')
                    anyOk = true
                }
                is SrcSpec.Fd -> {
                    try {
                        if (multi) out.append(a).append(':').append('\n')
                        out.append(hashOf(r.f).text).append('\n'); anyOk = true
                    } finally { r.temp?.deleteRecursively() }
                }
            }
        }
        return Result(out.toString().trimEnd('\n'), if (anyOk) 0 else 1)
    }

    private fun hashOf(f: File): Result =
        Result("MD5      " + hashFile(f, "MD5") + "\nSHA-256  " + hashFile(f, "SHA-256"))

    /**
     * `fN` 的解析结果。
     *
     * 三态不能压成 `Entry?`：[Stale] 必须报错而不是当路径走（宿主已被改写/删除，
     * 偏移已无意义），而 [NotFd] **同时覆盖「不是 fN 形状」与「是 fN 形状但表里
     * 没有」** —— 两者都得退回普通路径解析，因为用户完全可能有名字就叫 `f99` 的
     * 文件。早先三处调用点各自写 `get(-1)` 再判空，行为相同但这层意思看不出来。
     */
    private sealed class FdRef {
        object NotFd : FdRef()
        class Stale(val result: Result) : FdRef()
        class Hit(val e: FdTable.Entry) : FdRef()
    }

    /**
     * `fN` → 条目，含新鲜度校验。**全项目唯一的 fN 解析入口** ——
     * 此前 `listOne` / `resolveSource` / `extract` 各抄了一份同样的三行。
     */
    private fun fdRef(spec: String, ctx: Ctx): FdRef {
        val fds = ctx.fds ?: return FdRef.NotFd
        if (!fds.looksLikeFd(spec)) return FdRef.NotFd
        val e = fds.get(spec.removePrefix("f").toIntOrNull() ?: return FdRef.NotFd)
            ?: return FdRef.NotFd
        fds.checkFresh(e)?.let { return FdRef.Stale(Result(staleMsg(ctx.str, it), 1)) }
        return FdRef.Hit(e)
    }

    /**
     * 区间条目临时 carve 到 [Ctx.cacheDir]；返回临时文件（**调用方负责删除**）。
     * 没有 cacheDir（单测/无 Activity）→ null，调用方报 `needsActivity`。
     *
     * 文件名每次调用唯一化：hash/cp 不占调度槽，两条命令并发引用同一 fN 时
     * 确定性文件名会互踩（truncate 中的文件被读 → 哈希错 / 被先完成方删除）。
     */
    private fun carveFd(e: FdTable.Entry, ctx: Ctx): File? {
        val carved = ctx.cacheDir?.let {
            File(it, "uufd/f${e.fd}-${e.label.hashCode()}-${System.nanoTime()}")
        } ?: return null
        carved.parentFile?.mkdirs()
        carveToFile(e.host, e.offset, e.length, carved) {}
        return carved
    }

    /**
     * 把参数解析成实际文件：`fN` 优先（整文件条目直接用 host；区间条目先临时
     * carve，[SrcSpec.temp] 交调用方清理），非 fN 走普通路径。
     */
    private sealed class SrcSpec {
        class Path(val f: File) : SrcSpec()
        class Fd(val f: File, val temp: File?) : SrcSpec()
        class Fail(val result: Result) : SrcSpec()
    }

    private fun resolveSource(spec: String, ctx: Ctx): SrcSpec =
        when (val r = fdRef(spec, ctx)) {
            is FdRef.Stale -> SrcSpec.Fail(r.result)
            is FdRef.Hit -> if (r.e.wholeFile) {
                SrcSpec.Fd(r.e.host, null)
            } else {
                val carved = carveFd(r.e, ctx)
                if (carved == null) SrcSpec.Fail(Result(UuText.needsActivity(ctx.str), 2))
                else SrcSpec.Fd(carved, carved)
            }
            FdRef.NotFd -> SrcSpec.Path(UuText.resolve(ctx.cwd, spec))
        }

    /** `uu cp <src> <dst>` — 文件/目录/fN；dst 已存在（含作为目标本身）拒绝覆盖。 */
    private fun copy(raw: List<String>, ctx: Ctx): Result {
        rejectUnknownFlags(ctx, raw, listOf("-f"))?.let { return it }
        // 多出来的位置参数此前被**静默忽略**：`uu cp a.txt b.txt dst/` 会把
        // `b.txt` 当成目标、报"已存在"，a.txt 一个都没拷 —— 脚本里这是隐形杀手。
        val pa = splitFlags(raw, emptyList(), listOf("-f"))
        if (pa.pos.size < 2) return needFile()
        if (pa.pos.size > 2) return Result(ctx.text(R.string.cli_one_src_only, "cp"), 2)
        val force = "-f" in pa.bools
        val args = pa.pos
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
            if (dst.exists()) {
                if (!force) return Result(ctx.text(R.string.cli_exists, dst.path), 1)
                // -f 覆盖：先删再拷（目录也整棵换掉 —— 幂等脚本的写法）
                if (!dst.deleteRecursively()) return Result(ctx.text(R.string.cli_failed, dst.path), 1)
            }
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
        rejectUnknownFlags(ctx, args)?.let { return it }
        if (args.size < 2) return needFile()
        val src = UuText.resolve(ctx.cwd, args[0])
        if (!src.exists()) return Result(UuText.notFound(ctx.str, args[0]), 1)
        val dst = UuText.resolve(ctx.cwd, args[1])
        if (dst.exists()) return Result(ctx.text(R.string.cli_exists, dst.path), 1)
        if (!src.renameTo(dst)) return Result(UuText.failed(ctx.str, args[0]), 1)
        return Result(ctx.text(R.string.cli_rename_done, src.name, dst.name))
    }

    /**
     * 条目路径 → 树状文本（**纯函数**，`uu l -t` 用，可单测）。
     * 每目录内目录在前、名称升序；后缀由 [suffixOf] 决定（目录加 "/"，文件带大小），
     * 缺失的父目录条目自动补成目录节点（有些归档不写目录条目）。
     */
    internal fun renderEntryTree(entries: List<ArchiveEntry>, suffixOf: (ArchiveEntry) -> String): String {
        val children = HashMap<String, MutableList<ArchiveEntry>>()
        for (e in entries) {
            val parent = e.path.substringBeforeLast('/', "")
            children.getOrPut(parent) { ArrayList() }.add(e)
        }
        // 归档可能不写目录条目：路径有父级但表里没有 → 补虚拟目录节点。
        // knownDirs = 已存在的目录节点（真实条目 + 已补的），保证每个只补一次
        //（否则两个同父条目会各造一份，树里出现重复节点）
        val knownDirs = entries.filter { it.isDirectory }.map { it.path }.toMutableSet()
        for (e in entries) {
            var p = e.path.substringBeforeLast('/', "")
            while (p.isNotEmpty() && p !in knownDirs) {
                val grand = p.substringBeforeLast('/', "")
                children.getOrPut(grand) { ArrayList() }
                    .add(ArchiveEntry(p, p.substringAfterLast('/'), 0, true, false, p.count { it == '/' }))
                knownDirs.add(p)
                p = grand
            }
        }
        for (list in children.values) {
            list.sortWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }
        val sb = StringBuilder()
        fun walk(parent: String, prefix: String) {
            val list = children[parent].orEmpty()
            for ((i, e) in list.withIndex()) {
                val last = i == list.size - 1
                sb.append(prefix).append(if (last) "└─ " else "├─ ").append(e.name)
                sb.append(suffixOf(e)).append('\n')
                if (e.isDirectory) walk(e.path, prefix + if (last) "   " else "│  ")
            }
        }
        walk("", "")
        return sb.toString().trimEnd('\n')
    }

    /**
     * 选择项（`uu x` 的 entries）里的通配符 → 精确路径（**纯函数**，可单测）。
     *
     * 原生侧只认"精确路径 + 目录前缀"，所以通配符必须在 Kotlin 侧先展开。`*`
     * 可以跨目录分隔符（`*.png` 能匹配 data/img/a.png），`?` 是单字符；无通配符
     * 的项原样保留（目录前缀语义不变）。
     *
     * 注意注释里不要写出"星号斜杠"那个序列 —— 它会提前结束块注释（踩过）。
     * @return (展开后的路径, 未命中的模式)；两者都按输入顺序，路径去重
     */
    internal fun matchEntryPaths(paths: List<String>, patterns: List<String>): Pair<List<String>, List<String>> {
        val hits = ArrayList<String>()
        val misses = ArrayList<String>()
        for (p in patterns) {
            if (!CliGlob.hasWildcards(p)) { hits.add(p); continue }
            val m = paths.filter { CliGlob.matches(it, p) }
            if (m.isEmpty()) misses.add(p) else hits.addAll(m)
        }
        return hits.distinct() to misses
    }

    /** 读条目表并展开选择项里的通配符；列不出条目时原样返回（让原生侧照常报错）。 */
    private fun expandEntryGlobs(
        patterns: List<String>, fmt: String, src: File, pwd: String, ctx: Ctx,
    ): Pair<List<String>, List<String>> {
        if (patterns.none { CliGlob.hasWildcards(it) }) return patterns to emptyList()
        val json = listEntriesJsonFor(ctx, fmt, src, pwd) ?: return patterns to emptyList()
        return matchEntryPaths(parseEntries(json).map { it.path }, patterns)
    }

    private fun listEntriesJsonFor(ctx: Ctx, fmt: String, f: File, pwd: String): String? {
        val host = ctx.activity ?: return null
        return host.listEntriesJson(fmt, f, pwd, nestedOnly = false) { lastListError = it }
    }

    /** Why the last listing failed, when the native layer said so. */
    private var lastListError: String = ""

    /**
     * `uu x <archive> [outdir] [entry...] [-p pw]`。
     *
     * 进度**只写在终端输出区**（产品要求，不弹对话框）：[Ctx.progress] 每 200ms
     * 收到一条整行替换的进度行；完成后输出统计 + all set。密码遵守项目不变量：
     * 加密包在**拿调度槽之前**解决（-p 或模态询问）。
     */
    private fun extract(args: List<String>, ctx: Ctx): Result {
        val pa = splitFlags(args, listOf("-p", "-o"))
        missingValueError(ctx, pa)?.let { return it }
        val (flags, _, pos) = Triple(pa.values, pa.bools, pa.pos)
        val pw = flags["-p"]?.takeIf { it.isNotEmpty() } ?: ctx.password
        if (pos.isEmpty()) return needFile()
        val spec = pos[0]
        val optOutdir: String? = flags["-o"]?.takeIf { it.isNotEmpty()}
        val cancelled = AtomicBoolean(false)

        // fN 引用：整文件条目直接用 host；区间条目临时 carve 出来再解
        var carvedTemp: File? = null
        // `run` 是 inline，所以分支里的 `return` 直接返回本函数（非局部返回）。
        val (src0, displayName) = run {
            when (val r = fdRef(spec, ctx)) {
                is FdRef.Stale -> return r.result
                is FdRef.Hit -> {
                    val e = r.e
                    if (e.wholeFile) {
                        e.host to e.host.name
                    } else {
                        if (!e.isArchive()) return Result(UuText.fdNotArchive(ctx.str, spec, e.label), 2)
                        val carved = carveFd(e, ctx)
                            ?: return Result(UuText.needsActivity(ctx.str), 2)
                        carvedTemp = carved
                        carved to e.label
                    }
                }
                FdRef.NotFd -> {
                    val f = UuText.resolve(ctx.cwd, spec)
                    if (!f.isFile) return Result(UuText.notFound(ctx.str, spec), 1)
                    f to f.name
                }
            }
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

            // outdir：-o 优先（此时其后的位置参数全部是 entries，无二义）；
            // 否则沿用位置形式 pos[1]=outdir；都不给 → 默认单独路径。
            // 显式路径先行校验（快速失败）；默认目录的唯一化**拿到槽位后再解析**。
            val entryStart: Int
            val outDirExplicit: File? = if (optOutdir != null) {
                entryStart = 1
                val d = UuText.resolve(ctx.cwd, optOutdir)
                if (d.exists() && !d.isDirectory) return Result(ctx.text(R.string.cli_exists, d.path), 1)
                d.mkdirs()
                d
            } else if (pos.size >= 2) {
                entryStart = 2
                val d = UuText.resolve(ctx.cwd, pos[1])
                if (d.exists() && !d.isDirectory) {
                    // 通配展开后的第二个归档 / 误写的条目名都会落到这里：
                    // 位置形式无法与「条目」区分,给明确出口而不是误导性的 exists 报错。
                    return Result(ctx.text(R.string.cli_x_outdir_is_file, pos[1]), 1)
                }
                d.mkdirs()
                d
            } else {
                entryStart = 1
                null
            }
            // 选择项里的通配符先展开成精确路径（原生侧不认 glob）
            val rawSel = pos.drop(entryStart)
            val (selPaths, selMisses) = expandEntryGlobs(rawSel, fmt, src0, pwd, ctx)
            if (rawSel.isNotEmpty() && selPaths.isEmpty()) {
                return Result(ctx.text(R.string.cli_x_glob_none, selMisses.joinToString(" ")), 1)
            }
            val selected = selPaths.joinToString("\n")

            val act = ctx.activity
            val opH = act?.let { tryStartOperation(it, fmt) }
            try {
                // await()==false = 排队期被取消：必须中止，不能带着取消标记开跑
                //（Rust 入口的 clear_cancel 会把它清掉）。
                if (opH?.await() == false) return Result(UuText.extractCancelled(ctx.str), 1)
                val outDir = outDirExplicit
                    ?: uniqueFile(defaultOut(ctx), src0.nameWithoutExtension.ifEmpty { "extracted" })
                val accessors = extractAccessors(fmt)
                ctx.registerCancel?.invoke {
                    val running = opH?.isRunning == true
                    opH?.requestCancel()
                    if (running) { cancelled.set(true); accessors.cancel() }
                }
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

                if (cancelled.get()) {
                    outDir.deleteRecursively()
                    return Result(UuText.extractCancelled(ctx.str), 1)
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
                val missNote = if (selMisses.isEmpty()) "" else
                    "\n" + ctx.text(R.string.cli_x_glob_miss, selMisses.joinToString(" "))
                return Result(
                    ctx.text(R.string.cli_extract_ok, displayName,
                        o.counts.total.toString(), o.counts.success.toString(),
                        o.counts.error.toString()) + "\n" +
                    ctx.text(R.string.cli_all_set) + "\n" +
                    ctx.text(R.string.cli_x_outdir, outDir.absolutePath) + missNote
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
     * `-e <name>` → the `enc` argument the packer wants, or null when the name
     * is not one we implement. Case-insensitive, and the CANONICAL spelling is
     * what reaches the packer, so a user typing `-e fatecrypt` and the GUI
     * picking FateCrypt take the identical path.
     *
     * `fsn` is accepted as an alias for FateCrypt — it is arc_unpacker's plugin
     * id and the name the scheme is usually catalogued under, so it is the name
     * users are most likely to type. The other schemes have no alias; see
     * `archive_xp3crypt_core::Scheme::alias`.
     */
    internal fun normalizePackEnc(v: String): String? = when (v.trim().lowercase()) {
        "" -> ""
        "cxdec" -> "cxdec"
        "hashcrypt" -> "crypt:HashCrypt"
        "fatecrypt", "fsn" -> "crypt:FateCrypt"
        "appliquecrypt" -> "crypt:AppliqueCrypt"
        "flyingshinecrypt" -> "crypt:FlyingShineCrypt"
        "alteredpinkcrypt" -> "crypt:AlteredPinkCrypt"
        "dameganecrypt" -> "crypt:DameganeCrypt"
        "natsupochicrypt" -> "crypt:NatsupochiCrypt"
        "okibacrypt" -> "crypt:OkibaCrypt"
        "dieselminecrypt" -> "crypt:DieselmineCrypt"
        else -> null
    }

    /**
     * `uu c <src...> [out] [-c|-s] [-f key] [level] [splitMB] [-e scheme] [-p pw]`。
     *  - 单源 + out：`uu c dir out.zip` → out 是**最终输出路径**（产品要求）。
     *  - 单源无 out：落到默认单独路径（uu_cli/<src 名>.<ext>），格式必须 -f 给出。
     *  - `-c`：多源合并成一个包（暂存目录内重名按 `名字 (n)` 去重，默认名 archive.<ext>）。
     *  - `-s`：多源分别压缩（单文件 + zip/7z/tar 系自动临时目录包装）。
     *  产物名**拿到调度槽之后**解析（排队互不撞名）；已存在拒绝；`.pfs` 二义必须 -f。
     */
    private fun pack(args: List<String>, ctx: Ctx): Result {
        val pa = splitFlags(args, listOf("-p", "-f", "-l", "-b", "-e"), listOf("-c", "-s"))
        missingValueError(ctx, pa)?.let { return it }
        val (flags, bools, pos) = Triple(pa.values, pa.bools, pa.pos)
        val merge = "-c" in bools
        val separate = "-s" in bools
        if (merge && separate) return Result(UuText.failed(ctx.str, "-c / -s"), 1)
        val pw = flags["-p"] ?: ""
        val forceKey = flags["-f"]
        // `-e <scheme>`: only xp3 knows how to encrypt. The value is normalized
        // to what the packer expects — `cxdec` stays a bare family name (the
        // packer DISCOVERS the concrete scheme from the folder, and refuses when
        // there is nothing to resolve it from), while a keyless scheme becomes
        // `crypt:<Name>` because its key is each entry's own ADLR and there is
        // nothing to discover. An unrecognized name is refused, not ignored.
        val encArg = flags["-e"] ?: ""
        val enc = normalizePackEnc(encArg) ?: return Result(UuText.packUnknownEnc(ctx.str, encArg), 1)
        val cancelled = AtomicBoolean(false)
        if (pos.isEmpty()) return needFile(Picker.FILE_OR_FOLDER)

        val packResultLines = ArrayList<String>()
        // 批量模式（-c/-s）：格式必须 -f 指明（没有 out 后缀可反查，不猜格式）
        if (merge || separate) {
            val fmt = forceKey?.let {
                if (it !in COMPRESS_EXT.keys) return Result(UuText.packUnknownKey(ctx.str, it), 1)
                it
            } ?: return Result(ctx.text(R.string.cli_pack_need_format), 1)
            if (enc.isNotEmpty() && fmt != "xp3") return Result(UuText.packEncOnlyXp3(ctx.str), 1)
            // zip 的等级 GUI 走 zip_level（generic_level 不是它的档位）；-l 可覆盖
            val level = flags["-l"]?.toIntOrNull()
                ?: ctx.prefs?.let { if (fmt == "zip") it.getInt("zip_level", 5) else it.getInt("generic_level", 6) } ?: 6
            val batchSplit = flags["-b"]?.toLongOrNull() ?: 0L
            if (batchSplit > 0 && fmt !in setOf("zip", "7z")) {
                return Result(UuText.packSplitUnsupported(ctx.str, fmt), 1)
            }
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
                ctx.registerCancel?.invoke {
                    val running = opH?.isRunning == true
                    opH?.requestCancel()
                    if (running) { cancelled.set(true); accessors.cancel() }
                }
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
                                ?: throw IllegalStateException("prefs required"),
                                if (batchSplit > 0) batchSplit * 1024 * 1024 else null, enc)
                            if (ok) okCount++ else failCount++
                            if (ok) {
                                val note = if (enc.isNotEmpty()) encNoteText(ctx.str) else ""
                                packResultLines.add(ctx.text(R.string.cli_pack_ok, outF.name) +
                                    if (note.isEmpty()) "" else " $note")
                            }
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
                                    ?: throw IllegalStateException("prefs required"),
                                    if (batchSplit > 0) batchSplit * 1024 * 1024 else null, enc)
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
                if (okCount == 0) {
                    val why = PackErrors.last
                    return Result(UuText.packFailed(ctx.str, ext) +
                        if (why.isEmpty()) "" else "\n$why", 1)
                }
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
        if (pos.size > 2) return Result(ctx.text(R.string.cli_pack_multi_needs_flag), 1)
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

        if (enc.isNotEmpty() && fmt != "xp3") return Result(UuText.packEncOnlyXp3(ctx.str), 1)

        // 等级/分卷走 -l/-b 标志（位置式 3/4 参形态是死代码——曾被 arity 守卫拦死）
        val level = flags["-l"]?.toIntOrNull() ?: 6
        val splitMb = flags["-b"]?.toLongOrNull() ?: 0L
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
            ctx.registerCancel?.invoke {
                val running = opH?.isRunning == true
                opH?.requestCancel()
                if (running) { cancelled.set(true); accessors.cancel() }
            }
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
            var encNote = ""
            val ok = try {
                if (wrap) {
                    wrapDir!!.mkdirs()
                    src.copyTo(File(wrapDir, src.name))
                }
                val packOk = compressDispatch(if (wrap) wrapDir!! else src, outFile, fmt, level, pw, ctx.prefs
                    ?: throw IllegalStateException("prefs required"), splitBytes, enc)
                // Which scheme an encrypted pack used is only known after it has
                // run; the native side reports it. This branch builds its result
                // text itself (packResultLines belongs to the batch branch), so
                // the note has to be appended to THIS return.
                if (packOk && enc.isNotEmpty()) encNote = encNoteText(ctx.str)
                packOk
            } finally {
                wrapDir?.deleteRecursively()
                done.set(true)
                poller?.join(600)
            }

            if (cancelled.get()) {
                outFile.delete()
                return Result(UuText.extractCancelled(ctx.str), 1)
            }
            if (!ok) {
                val why = PackErrors.last
                return Result(UuText.packFailed(ctx.str, outFile.name) +
                    if (why.isEmpty()) "" else "\n$why", 1)
            }
            return Result(ctx.text(R.string.cli_pack_ok, outFile.name) +
                (if (encNote.isEmpty()) "" else "\n$encNote") + "\n" +
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

    /** [splitFlags] 的结果。[missingValue] 非空 = 某个取值旗标后面没跟值（命令须报错）。 */
    private class ParsedArgs(
        val values: Map<String, String>,
        val bools: Set<String>,
        val pos: List<String>,
        val missingValue: String?,
    )

    /**
     * 拆出 `-k value`（[valueKeys]）与 `-b` 布尔（[boolKeys]）旗标；其余按顺序返回。
     * 取值旗标缺后续 token 时记入 [ParsedArgs.missingValue]（此前会静默变成空串,
     * 把 `-p` 变成空密码并吞掉密码弹窗）。
     */
    private fun splitFlags(
        args: List<String>, valueKeys: List<String>, boolKeys: List<String> = emptyList()
    ): ParsedArgs {
        val flags = mutableMapOf<String, String>()
        val bools = mutableSetOf<String>()
        val pos = ArrayList<String>()
        var missing: String? = null
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a in valueKeys -> {
                    val v = args.getOrNull(i + 1)
                    if (v == null) missing = missing ?: a
                    else flags[a] = v
                    i += 2
                }
                a in boolKeys -> { bools.add(a); i++ }
                else -> { pos.add(a); i++ }
            }
        }
        return ParsedArgs(flags, bools, pos, missing)
    }

    private fun Ctx.stringZipMulti() = text(R.string.zip_multi_disk_no_edit)

    /** 缺值 → 报错 Result；否则 null。 */
    private fun missingValueError(ctx: Ctx, pa: ParsedArgs): Result? =
        pa.missingValue?.let { Result(ctx.text(R.string.cli_flag_missing_value, it), 1) }

    /**
     * 无旗标命令的防御：任何 `-x` 形状且未被识别的 token 报错（此前会被当路径,
     * 如 `uu mkdir -p a` 造出名为 `-p` 的目录）。纯负数字（-1）不算旗标。
     */
    private fun rejectUnknownFlags(ctx: Ctx, args: List<String>, known: List<String> = emptyList()): Result? {
        for (a in args) {
            if (a.length > 1 && a[0] == '-' && a !in known && a.drop(1).any { !it.isDigit() }) {
                return Result(ctx.text(R.string.cli_unknown_flag, a), 1)
            }
        }
        return null
    }

    private fun scan(args: List<String>, ctx: Ctx): Result {
        rejectUnknownFlags(ctx, args)?.let { return it }
        if (args.isEmpty()) return needFile()
        // 多参数逐个扫描,结果分段输出
        if (args.size > 1) {
            val sb = StringBuilder()
            for (a in args) {
                val r = scanOne(a, ctx)
                if (args.size > 1) sb.append("── ").append(a).append('\n')
                sb.append(r.text).append('\n')
            }
            return Result(sb.toString().trimEnd('\n'))
        }
        return scanOne(args[0], ctx)
    }

    private fun scanOne(spec: String, ctx: Ctx): Result {
        val f = UuText.resolve(ctx.cwd, spec)
        if (!f.isFile) return Result(UuText.notFound(ctx.str, spec), 1)

        val scan = ScanCore.scanFile(f.absolutePath)
            ?: return Result(UuText.scanFailed(ctx.str, f.name), 1)
        val hits = enrichScanHits(f, parseScanHits(scan))
        if (hits.isEmpty()) return Result(UuText.scanNone(ctx.str, f.name))

        // 非归档命中（png/jpeg/pdf…）只能 dd 出来，不能 x/l —— 它们没有容器。
        val keyOf: (String) -> String? = ::archiveKeyForLabel
        val fds = ctx.fds?.register(f, hits, f.length(), f.lastModified(), keyOf)
        val sb = StringBuilder()
        sb.append(UuText.scanHeader(ctx.str, f.name, hits.size)).append('\n')
        for ((i, h) in hits.withIndex()) {
            val fdTag = if (fds != null) "f${fds[i]}" else "-"
            val what = scanWhat(ctx, h)
            // The note goes at the END of the line, not in the label column:
            // it is CJK-width text, and padEnd counts characters, so a long
            // suffix would glue itself to the next column.
            val note = if (h.note.isEmpty()) ""
            else ctx.text(R.string.enc_suffix_paren, xp3SchemeLabel(ctx.str, h.note))
            sb.append("  ").append(fdTag.padEnd(8))
                .append(hexOffset(h.offset).padEnd(24))
                .append(h.label.padEnd(26)).append(what).append(note).append('\n')
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
        val pa = splitFlags(args, listOf("-p", "-e"))
        missingValueError(ctx, pa)?.let { return it }
        val (flags, _, pos) = Triple(pa.values, pa.bools, pa.pos)
        // `-e <encoding>`: decode with the named encoding and skip the
        // text/binary sniff entirely — an explicit request outranks a guess.
        var forcedEnc: String? = null
        flags["-e"]?.let { name ->
            // "-e auto" is explicit auto-detection, which is what happens without
            // the flag — accepted so the shared error message's list is truthful.
            forcedEnc = when {
                name.equals("auto", ignoreCase = true) -> null
                else -> normalizeEncoding(name)
                    ?: return Result(ctx.text(R.string.cli_enc_bad_encoding, name), 1)
            }
        }
        // 单参数 + 纯文本文件 = 像 shell cat 一样直接打印（脚本通用读取器，
        // 编码自动探测，2MB/100k 字符上限与归档条目一致）。归档或不存在 → 走
        // 原有的两参数流程 / 选择器。
        if (pos.size == 1) {
            val single = UuText.resolve(ctx.cwd, pos[0])
            if (!single.exists()) return Result(UuText.notFound(ctx.str, pos[0]), 1)
            if (single.isFile && detectFormat(single) == null && detectFormatByMagic(single) == null) {
                val bytes = readPrefix(single, CAT_MAX_BYTES)
                val enc = forcedEnc ?: textEncodingOf(bytes)
                    ?: return Result(ctx.text(R.string.cli_cat_binary, pos[0], fmt(single.length())), 1)
                var text = decodeTextStrict(bytes, enc)
                if (text.length > CAT_MAX_CHARS) text = text.take(CAT_MAX_CHARS)
                val truncated = single.length() > CAT_MAX_BYTES || text.length >= CAT_MAX_CHARS
                return Result(text + if (truncated)
                    "\n" + ctx.text(R.string.cli_cat_truncated, fmt(CAT_MAX_BYTES)) else "")
            }
            return needFile()
        }
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
                val enc = forcedEnc ?: textEncodingOf(bytes)
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
        val pa = splitFlags(args, listOf("-p"), listOf("-i"))
        missingValueError(ctx, pa)?.let { return it }
        val (flags, bools, pos) = Triple(pa.values, pa.bools, pa.pos)
        val gpw = flags["-p"]?.takeIf { it.isNotEmpty() } ?: ctx.password ?: ""
        if (pos.size < 2) return needFile()
        val pattern = pos[0]
        val ci = "-i" in bools
        val target = UuText.resolve(ctx.cwd, pos[1])
        if (!target.exists()) return Result(UuText.notFound(ctx.str, pos[1]), 1)

        var tempDir: File? = null
        // 显式指定的**纯文本文件**直接搜它。此前一律按归档处理，`uu grep alpha
        // a.txt` 会报"不是可解压的归档：-"，等于把合法用法判死（`-` 还是占位符，
        // 用户根本看不出说的是哪个文件）。
        var explicitFile = false
        val root: File = if (target.isFile) {
            val fmt = detectFormat(target) ?: detectFormatByMagic(target)
            if (fmt == null) {
                explicitFile = true
                target
            } else {
            val d = ctx.cacheDir?.let { File(it, "uu_grep/${System.nanoTime()}") }
                ?: return Result(UuText.needsActivity(ctx.str), 2)
            tempDir = d
            val json = listEntriesJsonFor(ctx, fmt, target, gpw)
                ?: return Result(UuText.listFailed(ctx.str, target.name), 1)
            val entries = parseEntries(json)
            for (e in entries) {
                if (e.isDirectory) continue
                val ext = e.name.substringAfterLast('.').lowercase()
                if (ext !in TEXT_SEARCH_EXTS) continue
                val rel = sanitizeEntryPath(e.path) ?: continue
                extractByFormat(fmt, target.path, d.path, rel, ctx.prefs
                    ?: throw IllegalStateException("prefs required"), gpw)
            }
            d
            }
        } else target

        try {
            var matches = 0
            var fileName = 0
            val sb = StringBuilder()
            root.walkTopDown().forEach { f ->
                if (!f.isFile) return@forEach
                // 目录搜索只查文本类；归档解出的临时内容已是白名单；
                // 用户显式点名的文件不设白名单（shell 的 `grep pat file` 语义）
                if (!explicitFile && tempDir == null && f.extension.lowercase() !in TEXT_SEARCH_EXTS) return@forEach
                if (f.length() > GREP_MAX_FILE_BYTES) return@forEach
                val bytes = runCatching { readPrefix(f, GREP_MAX_FILE_BYTES) }.getOrNull() ?: return@forEach
                val enc = textEncodingOf(bytes) ?: return@forEach
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
        val pa = splitFlags(args, emptyList(), listOf("-f"))
        val (_, bools, pos) = Triple(pa.values, pa.bools, pa.pos)
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
        rejectUnknownFlags(ctx, args)?.let { return it }
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
    private fun mv(raw: List<String>, ctx: Ctx): Result {
        rejectUnknownFlags(ctx, raw, listOf("-f"))?.let { return it }
        val pa = splitFlags(raw, emptyList(), listOf("-f"))
        if (pa.pos.size < 2) return needFile()
        if (pa.pos.size > 2) return Result(ctx.text(R.string.cli_one_src_only, "mv"), 2)
        val force = "-f" in pa.bools
        val src = UuText.resolve(ctx.cwd, pa.pos[0])
        if (!src.exists()) return Result(UuText.notFound(ctx.str, pa.pos[0]), 1)
        var dst = UuText.resolve(ctx.cwd, pa.pos[1])
        if (dst.isDirectory) dst = File(dst, src.name)
        if (dst.exists()) {
            if (!force) return Result(ctx.text(R.string.cli_exists, dst.path), 1)
            if (!dst.deleteRecursively()) return Result(ctx.text(R.string.cli_failed, dst.path), 1)
        }
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
        rejectUnknownFlags(ctx, args)?.let { return it }
        val pos = args
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
        rejectUnknownFlags(ctx, args)?.let { return it }
        if (args.isEmpty()) return needFile(Picker.FILE_OR_FOLDER)
        fun sizeOf(f: File): Pair<Long, Int> {
            if (f.isFile) return f.length() to 1
            var bytes = 0L; var files = 0
            f.walkTopDown().forEach { if (it.isFile) { bytes += runCatching { it.length() }.getOrDefault(0L); files++ } }
            return bytes to files
        }
        val multi = args.size > 1
        var totalBytes = 0L; var totalFiles = 0
        val out = StringBuilder()
        for (a in args) {
            val f = UuText.resolve(ctx.cwd, a)
            if (!f.exists()) { if (!multi) return Result(UuText.notFound(ctx.str, a), 1); out.append(UuText.notFound(ctx.str, a)).append('\n'); continue }
            val (b, n) = sizeOf(f)
            totalBytes += b; totalFiles += n
            if (multi) out.append(f.name).append(":\n")
            out.append(ctx.text(R.string.cli_du_result, fmt(b), n.toString())).append('\n')
        }
        if (multi) out.append(ctx.text(R.string.cli_du_result, fmt(totalBytes), totalFiles.toString())).append('\n')
        return Result(out.toString().trimEnd('\n'))
    }

    /** `uu stat <path...>` — 类型 / 大小 / 修改时间。 */
    private fun stat(args: List<String>, ctx: Ctx): Result {
        rejectUnknownFlags(ctx, args)?.let { return it }
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
        val pa = splitFlags(args, listOf("-p", "-f"))
        missingValueError(ctx, pa)?.let { return it }
        val (flags, _, pos) = Triple(pa.values, pa.bools, pa.pos)
        if (pos.size < 3) return Result(usageOf("set"), 2)
        val pw = flags["-p"] ?: ""
        val archive = UuText.resolve(ctx.cwd, pos[0])
        if (!archive.isFile) return Result(UuText.notFound(ctx.str, pos[0]), 1)
        val rel = sanitizeEntryPath(pos[1])
            ?: return Result(UuText.notFound(ctx.str, pos[1]), 1)
        val srcFile = UuText.resolve(ctx.cwd, pos[2])
        if (!srcFile.isFile) return Result(UuText.notFound(ctx.str, pos[2]), 1)
        val fmt = flags["-f"]?.let { key ->
            if (key !in COMPRESS_EXT.keys) return Result(UuText.packUnknownKey(ctx.str, key), 1)
            key
        } ?: detectFormat(archive) ?: detectFormatByMagic(archive)
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
        val cancelled = AtomicBoolean(false)
        try {
            if (opH?.await() == false) return Result(UuText.extractCancelled(ctx.str), 1)
            var acc = extractAccessors(fmt)
            ctx.registerCancel?.invoke {
                val running = opH?.isRunning == true
                opH?.requestCancel()
                if (running) { cancelled.set(true); acc.cancel() }
            }
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
                if (cancelled.get()) return Result(UuText.extractCancelled(ctx.str), 1)
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
                // Mirror the source archive's encryption, exactly like the GUI
                // repack: a game's filter runs over every entry, so a plain
                // result would be unreadable by the game it was made for.
                val setEnc = if (fmt == "xp3") xp3RepackEnc(xp3SchemeToken("xp3", archive)) else ""
                val ok = compressDispatch(staging, outF, fmt, level, pw, ctx.prefs
                    ?: throw IllegalStateException("prefs required"), null, setEnc)
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
        rejectUnknownFlags(ctx, args)?.let { return it }
        val pos = args
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
        val cancelled = AtomicBoolean(false)
        try {
            if (opH?.await() == false) return Result(UuText.extractCancelled(ctx.str), 1)
            val acc = ProgressAccessors(
                { CsoCore.csoProgressCount() }, { CsoCore.csoProgressTotal() },
                { CsoCore.csoProgressFileCount() }, { CsoCore.csoProgressFileTotal() },
                { CsoCore.csoProgressName() }, { CsoCore.csoCancel() }
            )
            ctx.registerCancel?.invoke {
                val running = opH?.isRunning == true
                opH?.requestCancel()
                if (running) { cancelled.set(true); acc.cancel() }
            }
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
            if (cancelled.get()) {
                outF.delete()
                return Result(UuText.extractCancelled(ctx.str), 1)
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

    // ─── enc / mvdec / rmd / add / find / diff / hex ─────────────────────

    /** 编码别名归一（与 TEXT_ENCODINGS 的四档对齐）。 */
    /** The one encoding-name resolver: `uu cat -e` and `uu enc` both use it, so
     *  the accepted set and the error message can never disagree. */
    private fun normalizeEncoding(name: String): String? = when (name.uppercase()) {
        "UTF-8", "UTF8" -> "UTF-8"
        "UTF-16", "UTF16", "UTF-16LE", "UTF16LE" -> "UTF-16"
        "SJIS", "SHIFT-JIS", "SHIFT_JIS", "SHIFTJIS", "CP932" -> "SHIFT-JIS"
        "GBK", "CP936" -> "GBK"
        else -> null
    }

    private fun encShort(enc: String) = when (enc) {
        "UTF-8" -> "utf8"; "UTF-16" -> "utf16"; "SHIFT-JIS" -> "sjis"; "GBK" -> "gbk"; else -> "out"
    }

    /** `uu enc <file> <from|auto> <to> [out]` — 文本编码转换，BOM 按源保真。 */
    private fun enc(args: List<String>, ctx: Ctx): Result {
        rejectUnknownFlags(ctx, args)?.let { return it }
        if (args.size < 3) return Result(usageOf("enc"), 2)
        val f = UuText.resolve(ctx.cwd, args[0])
        if (!f.isFile) return Result(UuText.notFound(ctx.str, args[0]), 1)
        val to = normalizeEncoding(args[2])
            ?: return Result(ctx.text(R.string.cli_enc_bad_encoding, args[2]), 1)
        val from = if (args[1].equals("auto", true)) null else normalizeEncoding(args[1])
            ?: return Result(ctx.text(R.string.cli_enc_bad_encoding, args[1]), 1)
        if (f.length() > ENC_MAX_BYTES) {
            return Result(ctx.text(R.string.cli_enc_too_big, fmt(f.length())), 1)
        }
        val bytes = runCatching { f.readBytes() }.getOrNull()
            ?: return Result(UuText.failed(ctx.str, f.name), 1)
        // Deliberately NOT `textEncodingOf`: `uu enc` is an explicit conversion
        // request, so a file that merely "looks binary" still gets converted
        // when the user names the source encoding with `from`.
        val srcEnc = from ?: detectBestEncoding(bytes)
            ?: return Result(ctx.text(R.string.cli_enc_no_detect, f.name), 1)
        val text = decodeTextStrict(bytes, srcEnc)
        val out = args.getOrNull(3)?.let { UuText.resolve(ctx.cwd, it) }
            ?: uniqueFile(f.parentFile ?: ctx.cwd,
                "${f.nameWithoutExtension}-${encShort(to)}.${f.extension.ifEmpty { "txt" }}")
        if (out.exists()) return Result(ctx.text(R.string.cli_exists, out.path), 1)
        // BOM 保真：源有 BOM 且目标支持（UTF-8/UTF-16）时保留
        out.writeBytes(encodeText(text, to, hasBom(bytes)))
        return Result(ctx.text(R.string.cli_enc_done, f.name, out.name, srcEnc, to))
    }

    /** `uu mvdec <file...> [-o dir]` — MV/MZ 资产解密（标准头重建，无需密钥）。 */
    private fun mvdec(args: List<String>, ctx: Ctx): Result {
        val pa = splitFlags(args, listOf("-o"))
        missingValueError(ctx, pa)?.let { return it }
        if (pa.pos.isEmpty()) return needFile()
        val outDir = pa.values["-o"]?.let { UuText.resolve(ctx.cwd, it) } ?: defaultOut(ctx)
        outDir.mkdirs()
        var ok = 0
        var fail = 0
        for (p in pa.pos) {
            val f = UuText.resolve(ctx.cwd, p)
            if (!f.isFile) { fail++; continue }
            val o = runCatching {
                extractByFormat("rpgmv", f.path, outDir.path, "", ctx.prefs
                    ?: throw IllegalStateException("prefs required"), "")
            }.getOrNull()
            if (o != null && o.counts.ok) ok++ else fail++
        }
        val msg = ctx.text(R.string.cli_ok_count, ok.toString())
        return if (fail == 0) Result(msg)
               else Result(msg + "\n" + ctx.text(R.string.cli_pack_partial, fail.toString()), 1)
    }

    /** ZIP 就地编辑公共段：在调度槽内解析产物名 → zipModify → 落成 -cn 副本。 */
    private fun zipModifyCopy(
        archive: File, ops: String, pw: String, label: String, ctx: Ctx
    ): Result {
        if (detectFormat(archive) != "zip") {
            return Result(ctx.text(R.string.cli_set_unsupported, "non-ZIP"), 1)
        }
        if (isVolumeFile(archive) == "zip" || isZipVolumeName(archive.name)) {
            return Result(ctx.stringZipMulti(), 1)
        }
        val parent = archive.parentFile ?: ctx.cwd
        val act = ctx.activity
        val opH = act?.let { tryStartOperation(it, "zip") }
        try {
            if (opH?.await() == false) return Result(UuText.extractCancelled(ctx.str), 1)
            val outF = uniqueFile(parent, "${archive.nameWithoutExtension}-cn.zip")
            val tmp = File(parent, "${archive.nameWithoutExtension}.edit.zip")
            val ok = runCatching { ZipCore.zipModify("", archive.path, tmp.path, ops, pw) }
                .getOrDefault(false)
            if (!ok) {
                tmp.delete()
                return Result(UuText.packFailed(ctx.str, outF.name), 1)
            }
            java.nio.file.Files.move(tmp.toPath(), outF.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            return Result(ctx.text(R.string.cli_set_done, label, outF.name) + "\n" +
                ctx.text(R.string.cli_all_set))
        } finally {
            opH?.release()
        }
    }

    /** `uu rmd <zip> <entry...> [-p pw]` — 删除 ZIP 条目（输出 -cn 副本）。 */
    private fun rmd(args: List<String>, ctx: Ctx): Result {
        val pa = splitFlags(args, listOf("-p"))
        missingValueError(ctx, pa)?.let { return it }
        if (pa.pos.size < 2) return Result(usageOf("rmd"), 2)
        val archive = UuText.resolve(ctx.cwd, pa.pos[0])
        if (!archive.isFile) return Result(UuText.notFound(ctx.str, pa.pos[0]), 1)
        val pw = pa.values["-p"]?.takeIf { it.isNotEmpty() } ?: ""
        // 条目通配：与 uu x 同一套"按条目 JSON 展开"（`uu rmd a.zip "*.tmp"`）
        val raw = pa.pos.drop(1)
        val (expanded, misses) = expandEntryGlobs(raw, "zip", archive, pw, ctx)
        if (raw.isNotEmpty() && expanded.isEmpty()) {
            return Result(ctx.text(R.string.cli_x_glob_none, misses.joinToString(" ")), 1)
        }
        if (expanded.any { it.contains('|') }) {
            return Result(ctx.text(R.string.zip_invalid_entry_name), 1)
        }
        val ops = expanded.joinToString("\n") { "delete|$it" }
        val r = zipModifyCopy(archive, ops, pw, "${expanded.size} entries", ctx)
        return if (misses.isEmpty()) r
        else Result(r.text + "\n" + ctx.text(R.string.cli_x_glob_miss, misses.joinToString(" ")), r.exitCode)
    }

    /** `uu add <zip> <local> [name] [-p pw]` — 向 ZIP 添加条目（输出 -cn 副本）。 */
    private fun add(args: List<String>, ctx: Ctx): Result {
        val pa = splitFlags(args, listOf("-p"))
        missingValueError(ctx, pa)?.let { return it }
        if (pa.pos.size < 2) return Result(usageOf("add"), 2)
        val archive = UuText.resolve(ctx.cwd, pa.pos[0])
        if (!archive.isFile) return Result(UuText.notFound(ctx.str, pa.pos[0]), 1)
        val local = UuText.resolve(ctx.cwd, pa.pos[1])
        if (!local.isFile) return Result(UuText.notFound(ctx.str, pa.pos[1]), 1)
        val rawName = pa.pos.getOrNull(2) ?: local.name
        val name = sanitizeEntryPath(rawName.replace('\\', '/').trim('/'))
            ?: return Result(ctx.text(R.string.zip_invalid_entry_name), 1)
        if (name.contains('|')) return Result(ctx.text(R.string.zip_invalid_entry_name), 1)
        // Rust 的 add 对已存在条目是静默跳过——先查重给出明确错误
        val json = listEntriesJsonFor(ctx, "zip", archive,
            pa.values["-p"]?.takeIf { it.isNotEmpty() } ?: "")
        if (json != null && parseEntries(json).any { it.path == name }) {
            return Result(ctx.text(R.string.cli_exists, name), 1)
        }
        val pw = pa.values["-p"]?.takeIf { it.isNotEmpty() } ?: ""
        return zipModifyCopy(archive, "add|$name|${local.path}", pw, name, ctx)
    }

    /** `uu find [dir] <glob> [depth]` — 递归文件名匹配（默认 cwd、深 6、上限 200 条）。 */
    private fun find(args: List<String>, ctx: Ctx): Result {
        rejectUnknownFlags(ctx, args)?.let { return it }
        if (args.isEmpty()) return needFile()
        val single = args.size == 1
        val root = if (single) ctx.cwd else UuText.resolve(ctx.cwd, args[0])
        if (!root.isDirectory) return Result(UuText.notFound(ctx.str, args[0]), 1)
        val glob = if (single) args[0] else args[1]
        val depth = args.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: FIND_MAX_DEPTH
        val hits = ArrayList<File>()
        root.walkTopDown().maxDepth(depth).forEach { f ->
            if (f != root && CliGlob.matches(f.name, glob)) hits.add(f)
        }
        if (hits.isEmpty()) return Result(ctx.text(R.string.cli_find_none, glob))
        val shown = hits.take(FIND_MAX_PRINT)
        val sb = StringBuilder()
        for (f in shown) {
            sb.append("  ").append(f.relativeTo(root).path).append(if (f.isDirectory) "/" else "").append('\n')
        }
        if (hits.size > shown.size) {
            sb.append(ctx.text(R.string.cli_list_truncated, (hits.size - shown.size).toString())).append('\n')
        }
        return Result(sb.toString().trimEnd('\n'))
    }

    /** `uu diff <a> <b> [-p pw]` — 两个归档条目表差异（仅比 size/目录性,无校验和）。 */
    private fun diff(args: List<String>, ctx: Ctx): Result {
        val pa = splitFlags(args, listOf("-p"))
        missingValueError(ctx, pa)?.let { return it }
        if (pa.pos.size < 2) return Result(usageOf("diff"), 2)
        val a = UuText.resolve(ctx.cwd, pa.pos[0]); if (!a.isFile) return Result(UuText.notFound(ctx.str, pa.pos[0]), 1)
        val b = UuText.resolve(ctx.cwd, pa.pos[1]); if (!b.isFile) return Result(UuText.notFound(ctx.str, pa.pos[1]), 1)
        val pw = pa.values["-p"]?.takeIf { it.isNotEmpty() } ?: ""
        fun entriesOf(f: File): List<ArchiveEntry>? {
            val fmt = detectFormat(f) ?: detectFormatByMagic(f) ?: return null
            val json = listEntriesJsonFor(ctx, fmt, f, pw) ?: return null
            return parseEntries(json)
        }
        val ea = entriesOf(a) ?: return Result(UuText.listFailed(ctx.str, a.name), 1)
        val eb = entriesOf(b) ?: return Result(UuText.listFailed(ctx.str, b.name), 1)
        val (onlyA, onlyB, changed, ma, mb) = diffOf(ea, eb)
        val sb = StringBuilder()
        sb.append(ctx.text(R.string.cli_diff_title, a.name, b.name,
            onlyB.size.toString(), onlyA.size.toString(), changed.size.toString())).append('\n')
        for (p in onlyA.sorted()) sb.append("  - ").append(p).append('\n')
        for (p in onlyB.sorted()) sb.append("  + ").append(p).append('\n')
        for (p in changed.sorted()) {
            sb.append("  ~ ").append(p)
                .append("  ").append(fmt(ma[p]!!.size)).append(" → ").append(fmt(mb[p]!!.size)).append('\n')
        }
        return Result(sb.toString().trimEnd('\n'))
    }

    /**
     * `uu img <src...> <jpg|png|webp> [-o dir] [-q 1-100]` — 图片格式转换。
     * 解码/合成/压缩走 `encodeImageTo`（与图片转换对话框同一实现）。
     * 输出**永不覆盖**：目标已存在则自动加 " (n)" 后缀（与对话框一致），
     * 实际路径逐条打印，方便脚本读回。支持 fN（扫描命中的图片可直接转）。
     */
    private fun img(args: List<String>, ctx: Ctx): Result {
        val pa = splitFlags(args, listOf("-o", "-q"))
        missingValueError(ctx, pa)?.let { return it }
        // 格式是固定关键字：取最后一个匹配的位置参数（其余位置参数都是源）
        val fmtIdx = pa.pos.indexOfLast { it.lowercase() in IMG_TARGET_FORMATS }
        if (fmtIdx < 0) {
            val only = pa.pos.singleOrNull()
            return if (only != null) Result(ctx.text(R.string.cli_img_bad_fmt, only), 2)
            else Result(usageOf("img"), 2)
        }
        val fmt = pa.pos[fmtIdx].lowercase()
        val srcs = pa.pos.filterIndexed { i, _ -> i != fmtIdx }
        if (srcs.isEmpty()) return needFile()
        val q = pa.values["-q"]?.toIntOrNull()?.coerceIn(1, 100) ?: if (fmt == "jpg") 90 else 100
        val outDir = pa.values["-o"]?.let { UuText.resolve(ctx.cwd, it) } ?: defaultOut(ctx)
        outDir.mkdirs()
        val sb = StringBuilder()
        var ok = 0
        var fail = 0
        for (srcArg in srcs) {
            var temp: File? = null
            val src = when (val r = resolveSource(srcArg, ctx)) {
                is SrcSpec.Fail -> { sb.append(r.result.text).append('\n'); fail++; continue }
                is SrcSpec.Path -> {
                    if (!r.f.isFile) { sb.append(UuText.notFound(ctx.str, srcArg)).append('\n'); fail++; continue }
                    r.f
                }
                is SrcSpec.Fd -> { temp = r.temp; r.f }
            }
            try {
                val dest = uniqueFile(outDir, "${src.nameWithoutExtension}.$fmt")
                if (encodeImageTo(src, fmt, dest, q)) {
                    sb.append(ctx.text(R.string.cli_img_ok, srcArg, dest.absolutePath)).append('\n')
                    ok++
                } else {
                    sb.append(ctx.text(R.string.cli_img_fail, srcArg)).append('\n')
                    fail++
                }
            } finally {
                temp?.delete()
            }
        }
        if (srcs.size > 1) sb.append(ctx.text(R.string.cli_img_summary, ok.toString(), fail.toString()))
        return Result(sb.toString().trimEnd('\n'), if (fail > 0) 1 else 0)
    }

    /**
     * `uu b64 <file> [-d] [out]` — Base64 编/解码。
     *
     * 默认编码：输出**纯 base64 文本**（无换行，便于管道与脚本）；
     * `-d` 解码：按 base64 文本读入，写出原始字节（`out` 省略时写 `<名字>.b64` /
     * `<名字>.bin`，用 `>` 重定向也可以）。
     * 支持 fN（扫描命中的 base64 blob 可以直接解）。cap 见 [B64_MAX_BYTES]。
     */
    private fun b64(args: List<String>, ctx: Ctx): Result {
        val pa = splitFlags(args, emptyList(), listOf("-d"))
        val decode = "-d" in pa.bools
        if (pa.pos.isEmpty()) return needFile()
        val spec = pa.pos[0]
        val outArg = pa.pos.getOrNull(1)
        var temp: File? = null
        val src = when (val r = resolveSource(spec, ctx)) {
            is SrcSpec.Fail -> return r.result
            is SrcSpec.Path -> {
                if (!r.f.isFile) return Result(UuText.notFound(ctx.str, spec), 1)
                r.f
            }
            is SrcSpec.Fd -> { temp = r.temp; r.f }
        }
        try {
            if (src.length() > B64_MAX_BYTES) {
                return Result(ctx.text(R.string.cli_b64_too_big, fmt(src.length()), fmt(B64_MAX_BYTES)), 1)
            }
            val bytes = runCatching { src.readBytes() }.getOrNull()
                ?: return Result(UuText.failed(ctx.str, src.name), 1)
            if (!decode) {
                val text = java.util.Base64.getEncoder().encodeToString(bytes)
                val dest = outArg?.let { UuText.resolve(ctx.cwd, it) }
                if (dest != null) {
                    if (dest.exists()) return Result(ctx.text(R.string.cli_exists, dest.path), 1)
                    dest.parentFile?.mkdirs()
                    val ok = runCatching { dest.writeText(text + "\n"); true }.getOrDefault(false)
                    return if (ok) Result(ctx.text(R.string.cli_b64_saved, dest.absolutePath))
                           else Result(UuText.failed(ctx.str, dest.name), 1)
                }
                // 不给路径：base64 文本就是命令输出（可管道、可重定向）
                return Result(text)
            }
            // 解码：**字节不进 String**（默认字符集转换会把 >=0x80 的字节变成 U+FFFD，
            // 实测 round-trip 出来"bytes differ"）。Base64 解码本身允许换行（MIME 解码器）。
            val raw = runCatching {
                java.util.Base64.getMimeDecoder().decode(bytes.toString(Charsets.ISO_8859_1))
            }.getOrElse { return Result(ctx.text(R.string.cli_b64_bad, spec), 1) }
            val defaultName = src.name.removeSuffix(".b64").removeSuffix(".txt")
                .ifEmpty { "decoded" } + ".bin"
            val dest = outArg?.let { UuText.resolve(ctx.cwd, it) }
                ?: uniqueFile(ctx.cwd, defaultName)
            if (dest.exists()) return Result(ctx.text(R.string.cli_exists, dest.path), 1)
            dest.parentFile?.mkdirs()
            val ok = runCatching { dest.writeBytes(raw); true }.getOrDefault(false)
            return if (ok) Result(ctx.text(R.string.cli_b64_saved, dest.absolutePath))
                   else Result(UuText.failed(ctx.str, dest.name), 1)
        } finally {
            temp?.delete()
        }
    }

    /** `uu fd [fN...]` — 列出已注册的文件描述符（进程级表，跨 tab 稳定）。 */
    private fun fd(args: List<String>, ctx: Ctx): Result {
        rejectUnknownFlags(ctx, args)?.let { return it }
        val fds = ctx.fds ?: return Result(UuText.needsActivity(ctx.str), 2)
        val list = if (args.isEmpty()) fds.all() else args.map { a ->
            val n = a.removePrefix("f").toIntOrNull()
                ?: return Result(UuText.noSuchFd(ctx.str, a), 1)
            fds.get(n) ?: return Result(UuText.noSuchFd(ctx.str, a), 1)
        }
        if (list.isEmpty()) return Result(ctx.text(R.string.cli_fd_none))
        val sb = StringBuilder()
        for (e in list.sortedBy { it.fd }) {
            sb.append(
                when {
                    e.wholeFile -> ctx.text(R.string.cli_fd_file, e.fd.toString(), fmt(e.byteSize()), e.host.name)
                    // 区间条目要带上**宿主文件名**（label 是扫描器的类型名，
                    // 光有 label 看不出这段在哪个文件里）
                    e.archiveKey != null -> ctx.text(
                        R.string.cli_fd_range_arch, e.fd.toString(), fmt(e.byteSize()),
                        "%x".format(e.offset), e.host.name, e.label, e.archiveKey
                    )
                    else -> ctx.text(
                        R.string.cli_fd_range, e.fd.toString(), fmt(e.byteSize()),
                        "%x".format(e.offset), e.host.name, e.label
                    )
                }
            )
            if (fds.checkFresh(e) != null) sb.append(ctx.text(R.string.cli_fd_stale_mark))
            sb.append('\n')
        }
        sb.append(ctx.text(R.string.cli_fd_total, list.size.toString()))
        return Result(sb.toString())
    }

    /** `uu hex <file> [offset] [len]` — 十六进制查看（默认 0 / 512B，上限 8KB）。 */
    private fun hex(args: List<String>, ctx: Ctx): Result {
        rejectUnknownFlags(ctx, args)?.let { return it }
        if (args.isEmpty()) return needFile()
        val f = UuText.resolve(ctx.cwd, args[0])
        if (!f.isFile) return Result(UuText.notFound(ctx.str, args[0]), 1)
        val offset = args.getOrNull(1)?.let { parseNum(it) } ?: 0L
        val len = (args.getOrNull(2)?.let { parseNum(it) } ?: 512L).coerceIn(1L, HEX_MAX_BYTES)
        val buf = ByteArray(len.toInt())
        val n = try {
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(offset)
                raf.read(buf)
            }
        } catch (e: Exception) { -1 }
        if (n < 0) return Result(UuText.failed(ctx.str, f.name), 1)
        val sb = StringBuilder()
        sb.append(f.name).append("  ").append(ctx.text(R.string.cli_hex_range,
            "0x%08X".format(offset), n.toString())).append('\n')
        var i = 0
        while (i < n) {
            val rowLen = minOf(16, n - i)
            sb.append("%08X  ".format(offset + i))
            val ascii = StringBuilder()
            for (j in 0 until 16) {
                if (j < rowLen) {
                    val v = buf[i + j].toInt() and 0xFF
                    sb.append("%02X ".format(v))
                    ascii.append(if (v in 0x20..0x7E) v.toChar() else '.')
                } else {
                    sb.append("   ")
                }
                if (j == 7) sb.append(' ')
            }
            sb.append(" |").append(ascii).append("|\n")
            i += rowLen
        }
        return Result(sb.toString().trimEnd('\n'))
    }

    /** 条目表差异（纯函数,可单测）：(仅A, 仅B, 变化, ma, mb)。 */
    internal fun diffOf(
        ea: List<ArchiveEntry>, eb: List<ArchiveEntry>
    ): Quint<Set<String>, Set<String>, List<String>, Map<String, ArchiveEntry>, Map<String, ArchiveEntry>> {
        val ma = ea.associateBy { it.path }
        val mb = eb.associateBy { it.path }
        val onlyA = ma.keys - mb.keys
        val onlyB = mb.keys - ma.keys
        val changed = (ma.keys intersect mb.keys).filter { p ->
            val x = ma[p]!!; val y = mb[p]!!
            x.isDirectory != y.isDirectory || (!x.isDirectory && x.size != y.size)
        }
        return Quint(onlyA, onlyB, changed, ma, mb)
    }

    internal class Quint<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E) {
        operator fun component1() = a
        operator fun component2() = b
        operator fun component3() = c
        operator fun component4() = d
        operator fun component5() = e
    }

    /** 支持 0x 前缀的十六进制或十进制。 */
    private fun parseNum(s: String): Long? =
        if (s.startsWith("0x", true)) s.drop(2).toLongOrNull(16) else s.toLongOrNull()

        /** `&&`/`;` 切出的一段。[andAlso] = 需前段成功（&&）才执行。 */
    /**
     * 一个串联段。[op] 是它**与前一段的连接方式**：""（首段）、";"（总是执行）、
     * "&&"（前段成功才执行）、"||"（前段失败才执行）。
     */
    internal class Seg(val tokens: List<String>, val op: String)

    /** 顶层按 `&&` / `;` 分段（tokenizer 保留它们是普通 token）。 */
    internal fun splitSegments(tokens: List<String>): List<Seg> {
        val out = ArrayList<Seg>()
        var cur = ArrayList<String>()
        var op = ""
        for (t in tokens) {
            if (t == "&&" || t == ";" || t == "||") {
                out.add(Seg(cur, op))
                op = t
                cur = ArrayList()
            } else cur.add(t)
        }
        out.add(Seg(cur, op))
        return out
    }

    /** 按 `|` 切分管道段。 */
    internal fun splitOnPipe(tokens: List<String>): List<List<String>> {
        if (tokens.none { it == "|" }) return listOf(tokens)
        val out = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        for (t in tokens) {
            if (t == "|") { out.add(cur); cur = ArrayList() } else cur.add(t)
        }
        out.add(cur)
        return out
    }

    /** shell 兜底时从 token 重建命令：全部加安全引号（tokenizer 已丢原始引号）。 */
    internal fun shellQuote(t: String): String =
        if (t.isNotEmpty() && t.all { it.isLetterOrDigit() || it in "._-/@:=+,^%~" }) t
        else "'" + t.replace("'", "'\\''") + "'"

    /** 脚本有效行（跳过空行与 # 注释）：(行号, 命令文本)。 */
    internal fun scriptLines(text: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        for ((i, raw) in text.lines().withIndex()) {
            val ln = raw.trim()
            if (ln.isEmpty() || ln.startsWith("#")) continue
            out.add(i + 1 to ln)
        }
        return out
    }

    /**
     * 管道过滤器（`uu l x.zip | grep main` 的右段）：grep [-i] <模式> / head [n] /
     * tail [n] / wc [-l]。返回 (文本, 退出码)；不支持的过滤器返回 null
     * （终端层提示改用 shell）。
     */
    internal fun pipeFilter(tokens: List<String>, input: String): Pair<String, Int>? {
        val name = tokens.firstOrNull() ?: return null
        val lines = input.split('\n')
        return when (name) {
            "grep" -> {
                val ci = tokens.any { it == "-i" }
                val pattern = tokens.drop(1).firstOrNull { it != "-i" } ?: return "" to 2
                val hits = lines.filter {
                    if (ci) it.contains(pattern, ignoreCase = true) else it.contains(pattern)
                }
                (if (hits.isEmpty()) "(no match)" else hits.joinToString("\n")) to if (hits.isEmpty()) 1 else 0
            }
            "head" -> {
                val n = tokens.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 10
                lines.take(n).joinToString("\n") to 0
            }
            "tail" -> {
                val n = tokens.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 10
                lines.takeLast(n).joinToString("\n") to 0
            }
            "sort" -> {
                val rev = tokens.any { it == "-r" }
                val sorted = if (rev) lines.sortedDescending() else lines.sorted()
                sorted.joinToString("\n") to 0
            }
            "wc" -> {
                val linesOnly = tokens.any { it == "-l" }
                if (linesOnly) lines.size.toString() to 0
                else "${lines.size} ${input.split(Regex("\\s+")).count { it.isNotEmpty() }} ${input.length}" to 0
            }
            else -> null
        }
    }

    /**
     * 通配符展开（所有命令共用）：参数含 `*`/`?` 时在 cwd 内展开为实际路径；
     * **无匹配保留原样**（错误信息里能看到用户输入的模式）。目录部分不参与
     * 匹配（带子目录的模式先拆 parent 再匹配文件名）。
     */
    fun expandGlobs(tokens: List<String>, cwd: File, skip: Set<Int> = emptySet()): List<String> {
        if (tokens.size <= 1) return tokens
        val out = ArrayList<String>(tokens.size)
        for ((i, t) in tokens.withIndex()) {
            if (i == 0 || i in skip || !CliGlob.hasWildcards(t)) { out.add(t); continue }
            val f = UuText.resolve(cwd, t)
            val dir = f.parentFile ?: cwd
            val matches = dir.listFiles { c -> CliGlob.matches(c.name, f.name) }
                ?.sortedBy { it.name.lowercase() }.orEmpty()
            if (matches.isEmpty()) out.add(t) else matches.forEach { out.add(it.absolutePath) }
        }
        return out
    }

    /**
     * 通配展开的**位置排除集**：这些位置参数是"模式"而不是"路径"，展开会把模式
     * 换成命中文件本身，语义直接反转 —— `uu find . *.txt` 会变成
     * `uu find . /dir/a.txt`，于是 find 去匹配字面名 `a.txt` 的**直接父路径**，
     * 报"没有匹配 a.txt 的文件"（实测踩过）。find 的所有位置参数、grep 的
     * pattern 都属此类。
     */
    fun globSkipIndices(tokens: List<String>): Set<Int> = when (tokens.firstOrNull()) {
        // 真实命令行首 token 是 `uu`，子命令在它后面 —— 只按裸命令名匹配过
        // 一次，结果 `uu find . *.txt` 照旧被展开（实测踩过）。
        "uu" -> when (tokens.getOrNull(1)) {
            "find" -> (2 until tokens.size).toSet()
            "grep" -> setOf(if (tokens.getOrNull(2) == "-i") 3 else 2)
            "x" -> xSkipFrom(tokens, from = 2)
            else -> emptySet()
        }
        "find" -> (1 until tokens.size).toSet()
        "grep" -> setOf(if (tokens.getOrNull(1) == "-i") 2 else 1)
        "x" -> xSkipFrom(tokens, from = 1)
        else -> emptySet()
    }

    /**
     * `uu x` 只让**归档**（第一个位置参数）参与命令级通配展开，其余全部跳过。
     * entries 有自己的"按条目"展开（第七批），而命令级展开发生在它**之前** ——
     * cwd 里恰好有同名 .png 时，`uu x a.zip "*.png"` 的模式会被换成那个文件的
     * 绝对路径，条目展开看到一个死路径，原生选择器匹配 0 条 → "解压失败"
     *（真机踩过）。副产物：裸 `*` 不再被 cwd 吃掉，`uu x a.zip *` 现在的语义
     * 是"选中全部条目"。
     */
    private fun xSkipFrom(tokens: List<String>, from: Int): Set<Int> {
        val skip = mutableSetOf<Int>()
        var i = from
        var positional = 0
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t == "-o" || t == "-p" -> { skip.add(i); if (i + 1 < tokens.size) skip.add(i + 1); i += 2 }
                t.startsWith("-") -> { skip.add(i); i++ }
                else -> {
                    positional++
                    if (positional > 1) skip.add(i)   // outdir（位置形式）与 entries 不展开
                    i++
                }
            }
        }
        return skip
    }

    /** `uu b64` 的单文件上限：编解码都在内存里做（文本形式还要再涨 4/3）。 */
    private const val B64_MAX_BYTES = 16L * 1024 * 1024

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

    /** `uu enc` 单文件上限 64MB（整文件读入）。 */
    private const val ENC_MAX_BYTES = 64L * 1024 * 1024

    /** `uu find`：默认递归深度 / 最多打印条数。 */
    private const val FIND_MAX_DEPTH = 6
    private const val FIND_MAX_PRINT = 200

    /** `uu hex`：单次最多 8KB。 */
    private const val HEX_MAX_BYTES = 8L * 1024

    /**
     * scan-core 的 label → 归档格式 key；非归档命中返回 null（只能 dd，不能 x/l）。
     * 复用 GUI 那张表（`SignatureScan.kt`），**不**在这里手抄第二份 ——
     * 手抄的版本当场就漂了（多了 ypf、少了 POSIX tar）。
     */
    private fun archiveKeyForLabel(label: String): String? = ARCHIVE_LABELS[label]
}
