package com.usefulunpacker

import java.io.File

import com.usefulunpacker.UuCommands.Ctx
import com.usefulunpacker.UuCommands.Result
import com.usefulunpacker.UuCommands.FdRef
import com.usefulunpacker.UuCommands.PreResult

internal fun UuCommands.list(args: List<String>, ctx: Ctx): Result {
    val pa = splitFlags(args, listOf("-p", "--pre"), listOf("-a", "-j", "-t", "-S"))
    missingValueError(ctx, pa)?.let { return it }
    // -a = 打印全部条目；默认截断（有些归档几十万条，刷屏且吃光 scrollback）
    val showAll = "-a" in pa.bools
    // -j = 输出原始条目 JSON（脚本接口：n/s/d/e 四个键，见 uu docs）。
    // 只接受**一个**归档：多份 JSON 拼在一起没有合法解析方式。
    val asJson = "-j" in pa.bools
    val asTree = "-t" in pa.bools
    if (asJson && asTree) return Result(usageFor("l"), 2)
    if (asJson && "-S" in pa.bools) return Result(usageFor("l"), 2)   // JSON 交给脚本自己排
    // --pre：读方向的便捷变换（见 [listOne] / [preTransform]）。不要求可逆：
    // 读方向不需要把产物再变换回去，可逆性只在写方向（--post）才有意义。
    val preText = pa.values["--pre"]
    val prePipeline = if (preText == null) null else when (val r = TrParser.parse(preText)) {
        is TrParser.Out.Err -> return Result(trBadMessage(ctx, r.bad), 2)
        is TrParser.Out.Ok -> r.pipeline
    }
    val pw = pa.values["-p"]?.takeIf { it.isNotEmpty() } ?: ctx.password ?: ""
    if (pa.pos.isEmpty()) return needFile()
    if (asJson && pa.pos.size > 1) return Result(ctx.text(R.string.cli_json_one_file, pa.pos.size.toString()), 2)
    // 多归档（通配展开）逐个列出，各带一行标题
    if (pa.pos.size > 1) {
        val out = StringBuilder()
        for (spec in pa.pos) {
            out.append("── ").append(spec).append('\n')
            out.append(listOne(spec, showAll, pw, ctx, asJson, asTree, "-S" in pa.bools, prePipeline).text).append('\n')
        }
        return Result(out.toString().trimEnd('\n'))
    }
    return listOne(pa.pos[0], showAll, pw, ctx, asJson, asTree, "-S" in pa.bools, prePipeline)
}

private fun UuCommands.listOne(spec: String, showAll: Boolean, pwd: String, ctx: Ctx, asJson: Boolean = false, asTree: Boolean = false, bySize: Boolean = false, prePipeline: TrPipeline? = null): Result {
    // fN → 整文件条目（ls 注册的）直接读 host；区间条目（scan 注册的）先
    // 临时 carve 出来再列
    when (val r = fdRef(spec, ctx)) {
        is FdRef.Stale -> return r.result
        is FdRef.Hit -> {
            val e = r.e
            if (e.wholeFile) {
                return listResolved(e.host, e.host.name, showAll, pwd, ctx, asJson, asTree, bySize, prePipeline)
            }
            // 非归档命中（png/jpeg/pdf…）没有容器可列 —— 只能 `uu dd` 切出来。
            if (!e.isArchive()) return Result(UuText.fdNotArchive(ctx.str, spec, e.label), 2)
            val carved = carveFd(e, ctx)
                ?: return Result(UuText.needsActivity(ctx.str), 2)
            try {
                // 展示名用 scan 的 label；临时文件名是一串哈希没有信息量。
                return listResolved(carved, e.label, showAll, pwd, ctx, asJson, asTree, bySize, prePipeline)
            } finally {
                com.usefulunpacker.deleteOutputChecked(carved, recursive = true)
            }
        }
        FdRef.NotFd -> Unit
    }

    val f = UuText.resolve(ctx.cwd, spec)
    if (!f.isFile) return Result(UuText.notFound(ctx.str, spec), 1)
    return listResolved(f, f.name, showAll, pwd, ctx, asJson, asTree, bySize, prePipeline)
}

/**
 * `listOne` 的「已解析成具体文件」收口点：`[prePipeline]` 非空时先把文件变换到
 * 临时文件，再交给 [listEntries]。
 *
 * 读方向**不要求可逆**（逆只在写方向 `--post` 才有意义），但变换结果必须能被
 * [detectFormatByMagic] 认出是归档 —— 否则就是「变换后还是垃圾」，静默喂给
 * 列目录器只会刷出一堆废条目，不如直接拒（[preTransform] 里处理）。
 *
 * 临时文件在 `finally` 删掉；变换了就**必须**在前头加一行醒目提示（见
 * [R.string.cli_pre_warn]），因为 `--pre` 是隐式的、用户可能没意识到列的是变换后的。
 * `-j` JSON 路径不加文字提示（那会破坏可解析性，且脚本作者本就显式传了 `--pre`）。
 */
private fun UuCommands.listResolved(
    f: File, displayName: String, showAll: Boolean, pwd: String, ctx: Ctx,
    asJson: Boolean, asTree: Boolean, bySize: Boolean, prePipeline: TrPipeline?,
): Result {
    val target: File
    var temp: File? = null
    if (prePipeline != null) {
        when (val t = preTransform(f, prePipeline, ctx)) {
            is PreResult.Err -> return t.result
            is PreResult.Ok -> { temp = t.file; target = t.file }
        }
    } else {
        target = f
    }
    return try {
        val inner = listEntries(target, displayName, showAll, pwd, ctx, asJson, asTree, bySize)
        val warn = if (temp != null && !asJson && prePipeline != null) {
            ctx.text(R.string.cli_pre_warn, prePipeline.text()) + "\n"
        } else ""
        Result(warn + inner.text, inner.exitCode)
    } finally {
        temp?.let { com.usefulunpacker.deleteOutputChecked(it, recursive = true) }
    }
}

private fun UuCommands.listEntries(
    f: File, displayName: String, showAll: Boolean, pwd: String, ctx: Ctx,
    asJson: Boolean = false, asTree: Boolean = false, bySize: Boolean = false,
): Result {
    val fmt = detectFormat(f) ?: detectFormatByMagic(f)
        ?: return Result(UuText.extractBadFormat(ctx.str, "-"), 1)
    // 密码：先试空密码（大多数包没密码），失败再让用户加 -p。
    // 不弹模态 —— 模态在终端里很怪，而且可阻塞 30s 会冻住输出区。
    lastListError.remove()
    val json = listEntriesJsonFor(ctx, fmt, f, pwd)
        ?: return Result(
            if (!lastListError.get().isNullOrEmpty()) UuText.listFailedWhy(ctx.str, f.name, lastListError.get().orEmpty())
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

/** 读条目表并展开选择项里的通配符；列不出条目时原样返回（让原生侧照常报错）。 */
internal fun UuCommands.expandEntryGlobs(
    patterns: List<String>, fmt: String, src: File, pwd: String, ctx: Ctx,
): Pair<List<String>, List<String>> {
    if (patterns.none { CliGlob.hasWildcards(it) }) return patterns to emptyList()
    val json = listEntriesJsonFor(ctx, fmt, src, pwd) ?: return patterns to emptyList()
    return matchEntryPaths(parseEntries(json).map { it.path }, patterns)
}

internal fun UuCommands.listEntriesJsonFor(ctx: Ctx, fmt: String, f: File, pwd: String): String? =
    ctx.withArchiveSlot(fmt) {
        listEntriesJson(fmt, f, pwd, nestedOnly = false, prefs = ctx.prefs ?: ctx.activity?.prefs) {
            lastListError.set(it)
        }
    }
