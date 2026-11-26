package com.usefulunpacker


import com.usefulunpacker.UuCommands.Ctx
import com.usefulunpacker.UuCommands.Result

internal fun UuCommands.scan(args: List<String>, ctx: Ctx): Result {
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

private fun UuCommands.scanOne(spec: String, ctx: Ctx): Result {
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

private fun UuCommands.scanWhat(ctx: Ctx, h: ScanHit): String = when {
    h.fileCount != null -> ctx.text(R.string.cli_scan_entries, h.fileCount)
    h.size != null -> fmt(h.size)
    else -> ""
}

private fun UuCommands.hexOffset(v: Long) = "0x%08X".format(v)

internal fun UuCommands.staleMsg(str: StrFn, r: FdTable.Rejected): String = when (r) {
    is FdTable.Rejected.Stale -> UuText.fdStale(str, "f${r.fd}")
    is FdTable.Rejected.NoSuchFd -> UuText.noSuchFd(str, r.name)
}

/** `uu fd [fN...]` — 列出已注册的文件描述符（进程级表，跨 tab 稳定）。 */
internal fun UuCommands.fd(args: List<String>, ctx: Ctx): Result {
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
