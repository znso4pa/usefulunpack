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
        val scheduleHeadless: Boolean = false,
        val checkCancelled: (() -> Unit)? = null,
        val onOutput: ((File) -> Unit)? = null,
    ) {
        internal fun reserveArchive(format: String): com.usefulunpacker.archive.OpScheduler.OpHandle? {
            if (activity == null && !scheduleHeadless) return null
            return tryStartOperation(format).also { handle ->
                registerCancel?.invoke { handle.requestCancel() }
            }
        }

        internal fun awaitArchive(handle: com.usefulunpacker.archive.OpScheduler.OpHandle?): Boolean {
            if (handle?.await() == false) return false
            checkCancelled?.invoke()
            return true
        }

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
        val failure: AppFailure? = null,
    )

    /** 要用户补一个路径。[FILE] 允许选文件，[FOLDER] 只收目录，
     *  [FILE_OR_FOLDER] 两者皆可（`uu c` 的源：单文件或文件夹都能封）。 */
    enum class Picker { FILE, FOLDER, FILE_OR_FOLDER }

    /** 需要文件参数的命令缺参时用它。 */
    internal fun needFile(p: Picker = Picker.FILE) = Result("", 2, p)

    internal fun usageFor(cmd: String): String =
        if (cmd == "run") "uu run [-k] <file.uut> [args...]"
        else table.firstOrNull { it.name == cmd }?.usage ?: "uu $cmd"

    /** x/c 不加路径时的默认输出位置（单独路径，产品要求）。 */
    internal fun defaultOut(ctx: Ctx): File = (ctx.defaultOutDir ?: ctx.cwd).apply { mkdirs() }

    internal enum class Kind {
        LIST_FORMATS, HELP, DOCS, INFO, LIST, CAT, HASH, GREP, COPY, MV, RENAME, RM, MKDIR,
        TREE, DU, STAT, EXTRACT, PACK, SET, SCAN, CSO, AUDIO, VIDEO,
        ENC, MVDEC, RMD, ADD, FIND, DIFF, HEX, DD, CMP, STRINGS, SED, TR, GUESS, IMG, FD, B64
    }

    internal class Cmd(val name: String, val kind: Kind, val summary: String, val usage: String)

    /** 主界面终端卡的角标用（「uu · N 条命令」）—— 和 `uu help` 的表同一份。 */
    val cmdCount: Int get() = table.size

    /** Single source of truth for MCP command discovery and validation. */
    internal fun commandList(): List<Cmd> = table
    internal fun isKnownCommand(name: String): Boolean = name == "run" || table.any { it.name == name }

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
        Cmd("l",      Kind.LIST,         "List entries (-j JSON / -t tree / -S by size)",  "uu l <archive> [-a] [-j] [-t] [-S] [-p pw] [--pre pipe]"),
        Cmd("cat",    Kind.CAT,          "Print a text file or an archive's entry",        "uu cat <file|archive> [entry] [-e enc] [-p pw]"),
        Cmd("hash",   Kind.HASH,         "Print the MD5 and SHA-256 of files",            "uu hash <file>"),
        Cmd("grep",   Kind.GREP,         "Search text inside a folder or archive",         "uu grep [-i] <pattern> <path>"),
        Cmd("cp",     Kind.COPY,         "Copy a file or folder (-f overwrites)",          "uu cp <src> <dst> [-f]"),
        Cmd("mv",     Kind.MV,           "Move a file or folder (-f overwrites)",          "uu mv <src> <dst> [-f]"),
        Cmd("rn",     Kind.RENAME,       "Rename a file or folder (refuses to overwrite)", "uu rn <old> <new>"),
        Cmd("rm",     Kind.RM,           "Delete (recycle bin by default; -f permanent)",  "uu rm <path...> [-f]"),
        Cmd("mkdir",  Kind.MKDIR,        "Create folders",                                 "uu mkdir <path...>"),
        Cmd("tree",   Kind.TREE,         "Recursive directory listing",                    "uu tree [dir] [depth]"),
        Cmd("du",     Kind.DU,           "Disk usage — totals, per-folder breakdown (-d) or biggest files (-n)", "uu du <path...> [-d N] [-n N]"),
        Cmd("stat",   Kind.STAT,         "Show type / size / modified time",               "uu stat <path...>"),
        Cmd("x",      Kind.EXTRACT,      "Extract an archive, optionally selected entries", "uu x <archive> [-o outdir] [entry...] [-p pw] [--pre pipe]"),
        Cmd("c",      Kind.PACK,         "Pack files or folders (merge / separate)",                          "uu c <src...> [out] [-c|-s] [-f key] [-l level] [-b splitMB] [-e cxdec] [--post pipe] [-p pw]"),
        Cmd("set",    Kind.SET,          "Replace one entry inside an archive",            "uu set <archive> <entry> <localfile> [-p pw]"),
        Cmd("scan",   Kind.SCAN,         "Scan a file for embedded archive signatures",    "uu scan <file>"),
        Cmd("cso",    Kind.CSO,          "Convert ISO ↔ CSO",                              "uu cso <file> [out]"),
        // `uu audio` 的旗标是整个 CLI 里最多的一组（6 个）。**故意不把它们全列进
        // usage 列**：usage 列宽取的是全表最长的一行，为这一个命令把每一行的说明
        // 再往右推十几个字符不划算（`uu docs` 的表本来就已经接近手机终端的宽度
        // 极限）。格式清单放进 summary（那一列不补空格，长短无所谓），旗标解释
        // 放进 PARAMETERS —— 那里本来就是讲旗标的地方。
        Cmd("audio",  Kind.AUDIO,        "Convert audio — in: mp3/ogg/wav/flac/m4a/aac/aiff/au · out: mp3/ogg/wav/aiff/au/aac", "uu audio <src...> <fmt> [-o dir] [--rate N] [--ch N] [--depth N] [--br N]"),
        Cmd("video",  Kind.VIDEO,        "Convert video (wmv/asf/avi/flv/mpeg/ts/mp4/mov → mp4|wmv)", "uu video <src...> [mp4|wmv] [-o dir]"),
        Cmd("enc",    Kind.ENC,          "Convert text encoding (BOM kept)",               "uu enc <file> <from|auto> <to> [out]"),
        Cmd("mvdec",  Kind.MVDEC,        "Decode RPG Maker MV/MZ assets (no key needed)",  "uu mvdec <file...> [-o dir]"),
        Cmd("rmd",    Kind.RMD,          "Delete entries from a ZIP (name-cn copy)",       "uu rmd <zip> <entry...> [-p pw] (globs ok)"),
        Cmd("add",    Kind.ADD,          "Add a file to a ZIP (name-cn copy)",             "uu add <zip> <local> [name] [-p pw]"),
        Cmd("find",   Kind.FIND,         "Find files by name under a folder",              "uu find [dir] <glob> [depth]"),
        Cmd("diff",   Kind.DIFF,         "Compare entry lists of two archives (size-based)", "uu diff <a> <b> [-p pw]"),
        Cmd("hex",    Kind.HEX,          "Hex dump a byte range",                         "uu hex <file> [offset] [len]"),
        Cmd("dd",     Kind.DD,           "Copy a byte range, or splice one into a file",  "uu dd if=<src> of=<dst> [skip=N] [count=N] [seek=N] [-f]"),
        Cmd("cmp",    Kind.CMP,          "Compare two files byte by byte",                "uu cmp <a> <b> [-l] [-s] [-n N]"),
        Cmd("strings", Kind.STRINGS,     "Extract printable strings (recon)",             "uu strings <file> [min] [-e ascii|utf16le] [-n max] [-a]"),
        Cmd("sed",    Kind.SED,          "Replace content (text, or hex bytes with --bytes)", "uu sed <from> <to> <file...> [-i] [--bytes]"),
        Cmd("tr",     Kind.TR,           "Transform bytes through a pipeline (xor/add/rot/skip)", "uu tr <file> \"skip 16 | xor 0xAA\" [-o out] [-f]"),
        Cmd("guess",  Kind.GUESS,        "Guess which transform turns a file back into a known format", "uu guess <file>"),
        Cmd("img",    Kind.IMG,          "Convert images (jpg / png / webp)",             "uu img <src...> <jpg|png|webp> [-o dir] [-q 1-100]"),
        Cmd("fd",     Kind.FD,           "List the registered fN descriptors",            "uu fd [fN...]"),
        Cmd("b64",    Kind.B64,          "Base64 encode (default) or decode a file",      "uu b64 <file> [-d] [out]"),
    )

    /** 供 `help` 内建命令复用，保证它和 `uu help` 讲的是同一份内容。 */
    fun renderHelp(ctx: Ctx): String = help(ctx).text

    /** @param argv 已切分的参数（含命令名本身）。 */
    fun dispatch(argv: List<String>, ctx: Ctx): Result {
        val name = argv.firstOrNull()?.takeIf { token -> table.any { it.name == token } } ?: "unknown"
        val hasPwFlag = name in setOf("l", "cat", "grep", "x", "c", "set", "rmd", "add", "diff")
        val secrets = listOfNotNull(ctx.password) +
            if (hasPwFlag) argv.drop(1).windowed(2).filter { it[0] in setOf("-p", "--password", "-k", "--key") }.map { it[1] }
            else emptyList()
        val _intentScope = AppDiagnostics.enterIntent("uu_$name", secrets)
        try {
        val trace = AppDiagnostics.begin("command", secrets)
        val result = AppDiagnostics.withTrace(trace) { dispatchCommand(argv, ctx) }
        // A missing interactive argument requests a picker; comparisons use exit 1 for differences.
        val failure = if (result.exitCode != 0 && result.picker == null && !(result.exitCode == 1 && name in setOf("cmp", "diff")))
            trace.fail(FailureCode.COMMAND, "Command exit=${result.exitCode}") else null
        trace.note("exit=${result.exitCode}")
        return Result(result.text, result.exitCode, result.picker, failure)
        } catch (intentCancelled: java.util.concurrent.CancellationException) {
        _intentScope.trace.cancelled(); throw intentCancelled
    } catch (intentFailure: Exception) {
        _intentScope.trace.fail(com.usefulunpacker.FailureCode.UNEXPECTED, intentFailure.message ?: intentFailure.javaClass.simpleName, intentFailure)
        throw intentFailure
    } finally { AppDiagnostics.leaveIntent(_intentScope) }
    }

    private fun dispatchCommand(argv: List<String>, ctx: Ctx): Result {
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
                Kind.AUDIO -> audio(args, ctx)
                Kind.VIDEO -> video(args, ctx)
                Kind.ENC -> enc(args, ctx)
                Kind.MVDEC -> mvdec(args, ctx)
                Kind.RMD -> rmd(args, ctx)
                Kind.ADD -> add(args, ctx)
                Kind.FIND -> find(args, ctx)
                Kind.DIFF -> diff(args, ctx)
                Kind.HEX -> hex(args, ctx)
                Kind.DD -> dd(args, ctx)
                Kind.CMP -> cmp(args, ctx)
                Kind.STRINGS -> strings(args, ctx)
                Kind.SED -> sed(args, ctx)
                Kind.TR -> tr(args, ctx)
                Kind.GUESS -> guess(args, ctx)
                Kind.IMG -> img(args, ctx)
                Kind.FD -> fd(args, ctx)
                Kind.B64 -> b64(args, ctx)
            }
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: McpInputException) {
            throw e
        } catch (e: Exception) {
            // UI retains the actionable message; diagnostics avoid echoed argv/payloads.
            val fault = AppDiagnostics.begin("command_exception")
            fault.note(e.stackTrace.joinToString("\n"))
            fault.fail(FailureCode.UNEXPECTED, e.javaClass.simpleName)
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
    internal fun docs(ctx: Ctx): Result = Result(renderDocs())

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
        // 这张表是**手写的第二份清单**，所以它会漂：`-e` 的方案列表曾停在 7 个
        // （6.2 加了三个无侧车方案后没跟上），`-t / -S / -d / -q` 也一直没解释过。
        // 加旗标时这里必须一起改 —— `docsExplainsEveryFlagFromUsageLines` 会盯着。
        val params = listOf(
            "-a            " to "list all entries (uu l; default caps at 500) · scan the whole file (uu strings)",
            "-p <password> " to "archive password (uu l / cat / grep / x / c / set); empty -> prompt",
            "-f <key>      " to "explicit format key (uu c / set; see uu fmt)",
            "-f            " to "force overwrite (uu cp / dd) · permanent delete instead of recycle bin (uu rm)",
            "-c / -s       " to "merge into one archive / separate per source (uu c, multi-source)",
            "-s            " to "silent: exit code only, no output (uu cmp)",
            "-l <level>    " to "compression level (uu c)",
            "-l            " to "list every differing byte, not just the first (uu cmp)",
            "-b <splitMB>  " to "split size in MB (uu c, zip/7z only)",
            "-e <scheme>   " to "encrypt the pack (uu c, xp3 only): cxdec | hashcrypt | fatecrypt (alias fsn) | appliquecrypt | flyingshinecrypt | alteredpinkcrypt | dameganecrypt | natsupochicrypt | okibacrypt | dieselminecrypt — cxdec needs xp3filter.tjs/.tpm or an encrypted .xp3 in the folder, the keyless ones need nothing",
            "--post <pipe> " to "apply a reversible transform pipeline to the finished archive (uu c, single output only) — read it back with uu tr",
            "--pre <pipe>   " to "transform the archive before listing/extracting (uu l / uu x): feeds the transformed bytes to list/extract, refuses if the result is not a recognised format, and prints a warning — a convenience over uu tr | uu x",
            "-e <enc>      " to "source text encoding (uu cat) · ascii | utf16le (uu strings)",
            "-o <dir>      " to "output directory (uu x / mvdec / img; for uu x the entries then follow unambiguously)",
            "-i            " to "case-insensitive search (uu grep) · edit the file in place (uu sed)",
            "-j            " to "raw entry JSON for scripts (uu l): n/s/d/e keys",
            "-t            " to "tree view (uu l)",
            "-S            " to "sort by size (uu l)",
            "-n <N>        " to "compare only the first N bytes (uu cmp) · print at most N strings (uu strings) · list the N biggest files (uu du)",
            "-q <1-100>    " to "image quality (uu img, jpg/webp)",
            "-d <N>        " to "folder breakdown depth 1-6 (uu du): every subdirectory down to N levels, biggest first; add -n to also rank files",
            "-d            " to "decode instead of encode (uu b64)",
            "--bytes       " to "treat <from> / <to> as hex bytes; needs -i (uu sed)",
            "if= of=       " to "source / destination (uu dd); plus skip= count= seek= and -f",
            // 音频转换的旗标（uu audio）。`--vbr` / `--q` **不在 usage 行里**（原因见
            // 命令表里 `uu audio` 上方的注释），但仍然是能用的旗标，必须在这里解释。
            "--rate <N>    " to "target sample rate in Hz (uu audio); omitted or 0 keeps the source rate",
            "--ch <N>      " to "target channel count, 1 or 2 (uu audio); omitted or 0 keeps the source",
            "--depth <N>   " to "bit depth for the lossless targets wav / aiff / au (uu audio): 8 | 16 | 24 | 32",
            "--br <N>      " to "bitrate in kbps for the lossy targets mp3 / ogg / aac (uu audio); omitted -> format default",
            "--vbr         " to "VBR instead of CBR (uu audio, mp3 only)",
            "--q <0-9>     " to "VBR quality, 0 best … 9 smallest (uu audio, mp3 only; needs --vbr)",
        )
        for ((k, v) in params) sb.append("  ").append(k).append(v).append('\n')
        sb.append('\n')
        sb.append("SPECIALS\n")
        sb.append("-".repeat(8)).append('\n')
        sb.append("  fN            ").append("descriptor from \"ls\" / \"uu scan\" — uu l f0 / uu x f0 / uu dd if=f0 of=part.bin\n")
        sb.append("  * ?           ").append("wildcards expand in the current dir — uu x *.zip / uu hash *.png\n")
        sb.append("  |             ").append("pipe into a filter: grep / head / tail / wc / sort(+-r) — uu l a.zip | sort\n")
        sb.append("  > >>          ").append("redirect output to a file (>> appends) — uu l a.zip > list.txt\n")
        sb.append("  && ; ||       ").append("chain: && runs on success, || runs on failure — uu l f0 || uu scan f0\n")
        sb.append("  uu run <file> ").append("run a UUT script: uu run [-k] file.uut [args...] — see below\n")
        sb.append("  progress line ").append("tap to cancel a running uu x / c / set / cso / du -d operation\n")
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
    internal fun info(args: List<String>, ctx: Ctx): Result {
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

    /**
     * `fN` 的解析结果。
     *
     * 三态不能压成 `Entry?`：[Stale] 必须报错而不是当路径走（宿主已被改写/删除，
     * 偏移已无意义），而 [NotFd] **同时覆盖「不是 fN 形状」与「是 fN 形状但表里
     * 没有」** —— 两者都得退回普通路径解析，因为用户完全可能有名字就叫 `f99` 的
     * 文件。早先三处调用点各自写 `get(-1)` 再判空，行为相同但这层意思看不出来。
     */
    internal sealed class FdRef {
        object NotFd : FdRef()
        class Stale(val result: Result) : FdRef()
        class Hit(val e: FdTable.Entry) : FdRef()
    }

    /**
     * `fN` → 条目，含新鲜度校验。**全项目唯一的 fN 解析入口** ——
     * 此前 `listOne` / `resolveSource` / `extract` 各抄了一份同样的三行。
     */
    internal fun fdRef(spec: String, ctx: Ctx): FdRef {
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
    internal fun carveFd(e: FdTable.Entry, ctx: Ctx): File? {
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
    internal sealed class SrcSpec {
        class Path(val f: File) : SrcSpec()
        /**
         * [label] 是**展示名**：区间条目用 `uu scan` 报出的类型名，整文件条目用宿主文件名。
         * 临时 carve 出来的文件名是一串哈希（`f0-642336419-25916572799104`），直接拿去
         * 打日志没人看得懂 —— 真机上 `uu cmp f0 x` 就是这么显示的。
         */
        class Fd(val f: File, val temp: File?, val label: String) : SrcSpec()
        class Fail(val result: Result) : SrcSpec()
    }

    internal fun resolveSource(spec: String, ctx: Ctx): SrcSpec =
        when (val r = fdRef(spec, ctx)) {
            is FdRef.Stale -> SrcSpec.Fail(r.result)
            is FdRef.Hit -> if (r.e.wholeFile) {
                SrcSpec.Fd(r.e.host, null, r.e.host.name)
            } else {
                val carved = carveFd(r.e, ctx)
                if (carved == null) SrcSpec.Fail(Result(UuText.needsActivity(ctx.str), 2))
                else SrcSpec.Fd(carved, carved, r.e.label)
            }
            FdRef.NotFd -> SrcSpec.Path(UuText.resolve(ctx.cwd, spec))
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

    internal fun <T> Ctx.withArchiveSlot(format: String, extract: Boolean = false, block: () -> T): T {
        val handle = reserveArchive(format)
        try {
            if (!awaitArchive(handle)) throw java.util.concurrent.CancellationException()
            if (extract && handle != null) {
                val accessors = extractAccessors(format)
                registerCancel?.invoke {
                    handle.cancelFromOwner(accessors.cancel)
                }
            }
            return block()
        } finally {
            handle?.release()
        }
    }

    /** Why the last listing failed, when the native layer said so. */
    internal val lastListError = ThreadLocal<String>()

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
    internal fun normalizePackEnc(v: String): String? = normalizeXp3PackEnc(v)

    /**
     * `uu l/x --pre` 的变换结果。
     *
     * 读方向便捷形式：把源按管道变换到临时文件，再交给 list/extract。
     * [Err] 携带一条已成形、可直接返回的 Result（变换失败 / 变换后认不出格式）。
     */
    internal sealed class PreResult {
        class Ok(val file: File) : PreResult()
        class Err(val result: Result) : PreResult()
    }

    /** `--pre` 便捷变换允许的最大临时文件大小（8 GiB）。超出见 [preTransform]。 */
    internal const val PRE_MAX_BYTES = 1L shl 33

    /**
     * 用 [inverse] 解 [transformed]，与 [original] 逐字节比对。分块，不整读。
     * 长度也必须相等 —— 管道既然可逆，长度就不可能变。
     *
     * `internal` 是为了能单测：打包路径要 native 侧，单测里跑不动，但这条校验
     * 是 `--post` 唯一的防线，必须能单独验。
     */
    internal fun inverseRoundTrips(original: File, transformed: File, inverse: TrPipeline): Boolean {
        if (original.length() != transformed.length()) return false
        return try {
            java.io.FileInputStream(original).use { a ->
                java.io.FileInputStream(transformed).use { b ->
                    val ba = ByteArray(1 shl 20)
                    val bb = ByteArray(1 shl 20)
                    var off = 0L
                    while (true) {
                        val na = readFully(a, ba)
                        val nb = readFully(b, bb)
                        if (na != nb) return false
                        if (na <= 0) break
                        for (i in 0 until nb) {
                            var v = bb[i]
                            for (s in inverse.steps) v = s.apply(off + i, v)
                            if (v != ba[i]) return false
                        }
                        off += na
                    }
                    true
                }
            }
        } catch (failure: Exception) {
            AppDiagnostics.failure("inverse_verify", failure)
            false
        }
    }

    /**
     * 进度行（产品规格）：`[===>   ] 45% (12.3MB/27.4MB) 当前文件名 (45/200)`。
     * 总量未知（头里没有）时退化为字节数 + 文件名；30 字符 ASCII 条约 600px。
     */
    internal fun progressLine(a: ProgressAccessors): String {
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
    internal class ParsedArgs(
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
    internal fun splitFlags(
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

    internal fun Ctx.stringZipMulti() = text(R.string.zip_multi_disk_no_edit)

    /** 缺值 → 报错 Result；否则 null。 */
    internal fun missingValueError(ctx: Ctx, pa: ParsedArgs): Result? =
        pa.missingValue?.let { Result(ctx.text(R.string.cli_flag_missing_value, it), 1) }

    /**
     * 无旗标命令的防御：任何 `-x` 形状且未被识别的 token 报错（此前会被当路径,
     * 如 `uu mkdir -p a` 造出名为 `-p` 的目录）。纯负数字（-1）不算旗标。
     */
    internal fun rejectUnknownFlags(ctx: Ctx, args: List<String>, known: List<String> = emptyList()): Result? {
        for (a in args) {
            if (a.length > 1 && a[0] == '-' && a !in known && a.drop(1).any { !it.isDigit() }) {
                return Result(ctx.text(R.string.cli_unknown_flag, a), 1)
            }
        }
        return null
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
    internal fun parseNum(s: String): Long? =
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
            // 格式关键字（`uu img … jpg` / `uu audio … ogg`）是固定词不是路径：
            // cwd 里恰好有个叫 `jpg` 的文件时，展开会把它换成那个文件的绝对路径，
            // 格式就丢了。`uu img` 一直有这个问题，加 `uu audio` 时一并补上。
            "img", "audio", "video" -> {
                val formats = when (tokens[1]) {
                    "img" -> IMG_TARGET_FORMATS
                    "audio" -> AUDIO_TARGETS
                    else -> VIDEO_TARGETS
                }
                (2 until tokens.size).filter { tokens[it].lowercase() in formats }.toSet()
            }
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
    internal const val B64_MAX_BYTES = 16L * 1024 * 1024

    // ─── 内部常量 / 辅助 ──────────────────────────────────────────────────

    /** `uu l` 默认最多打印这么多条，末尾给一行汇总。 */
    const val LIST_PREVIEW_LIMIT = 500

    /** `uu cat` 有界读取：2MB 字节 / 100k 字符（scrollback 友好）。 */
    internal const val CAT_MAX_BYTES = 2 * 1024 * 1024L
    internal const val CAT_MAX_CHARS = 100_000

    /** `uu tree` 最多打印 TREE_MAX 行。 */
    internal const val TREE_MAX = 500

    /**
     * `uu du -d` / `-n` 的取值上限，以及分解视图最多打印的行数。
     *
     * 上限存在的理由不是性能而是**可读性**：终端输出区只有 200KB，一屏看不过来的
     * 排行没有意义。`-d 6` 已经能到「游戏目录/子目录/资源包」这种粒度了。
     */
    internal const val DU_MAX_DEPTH = 6
    internal const val DU_MAX_TOP = 50
    internal const val DU_MAX_LINES = 200

    /** `uu du` 遍历时每多少个文件刷一次进度行（2000 次 stat 大约几十毫秒）。 */
    internal const val DU_PROGRESS_EVERY = 2000

    /** `uu grep`：单文件读取上限 / 打印字符上限。 */
    internal const val GREP_MAX_FILE_BYTES = 5L * 1024 * 1024
    internal const val GREP_MAX_PRINT_CHARS = 60_000

    /** `uu enc` 单文件上限 64MB（整文件读入）。 */
    internal const val ENC_MAX_BYTES = 64L * 1024 * 1024

    /** `uu find`：默认递归深度 / 最多打印条数。 */
    internal const val FIND_MAX_DEPTH = 6
    internal const val FIND_MAX_PRINT = 200

    /** `uu hex`：单次最多 8KB。 */
    internal const val HEX_MAX_BYTES = 8L * 1024

    /** `uu dd` 认的 `k=v` 键（它不用 `-flag value` 语法，所以没有对应的旗标表）。 */
    internal val DD_KEYS = setOf("if", "of", "skip", "count", "seek")

    /** `uu cmp`：比对块大小 / `-l` 最多列多少行（列不完只报条数）。 */
    internal const val CMP_CHUNK = 1 shl 20
    internal const val CMP_MAX_LIST = 200

    /**
     * `uu strings`：默认只扫前 8 MiB（`-a` 提到 64 MiB），最多打印 200 条，最短 4 字符。
     * 上限存在的理由见 [strings] 的注释：手机上一个 GB 级归档全扫要很久，而侦察要的
     * 几乎总在开头。上限会写进输出，不让人误以为「就这么多」。
     */
    internal const val STRINGS_SCAN_MAX = 8L * 1024 * 1024
    internal const val STRINGS_SCAN_MAX_ALL = 64L * 1024 * 1024
    internal const val STRINGS_MAX_PRINT = 200
    internal const val STRINGS_MIN_DEFAULT = 4

    /** `uu sed`：整文件读入，所以有上限（与 `uu enc` / `uu b64` 同一个 64 MiB）。 */
    internal const val SED_MAX_BYTES = 64L * 1024 * 1024

    /** `uu guess` 候选列表的管道列宽（管道都是 ASCII，`padEnd` 按字符数即可）。 */
    internal const val GUESS_COL = 24

    /**
     * scan-core 的 label → 归档格式 key；非归档命中返回 null（只能 dd，不能 x/l）。
     * 复用 GUI 那张表（`SignatureScan.kt`），**不**在这里手抄第二份 ——
     * 手抄的版本当场就漂了（多了 ypf、少了 POSIX tar）。
     */
    internal fun archiveKeyForLabel(label: String): String? = ARCHIVE_LABELS[label]
}
