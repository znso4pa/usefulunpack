package com.usefulunpacker

import java.io.File

import com.usefulunpacker.UuCommands.Ctx
import com.usefulunpacker.UuCommands.Result
import com.usefulunpacker.UuCommands.FdRef
import com.usefulunpacker.UuCommands.SrcSpec

/** `uu hex <file> [offset] [len]` — 十六进制查看（默认 0 / 512B，上限 8KB）。 */
internal fun UuCommands.hex(args: List<String>, ctx: Ctx): Result {
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

/**
 * `uu dd if=<src> of=<dst> [skip=N] [count=N] [seek=N] [-f]` — 精准切字节。
 *
 * 不给 `seek=` 是「切一段出来」；给了 `seek=` 是「把一段写进已有文件的中间」。
 *
 * `if=` 支持 `fN`（`uu scan` 的命中），此时 `skip` / `count` 相对**区间起点**
 * 而不是宿主文件 —— 否则 `uu dd if=f3 of=x.rpa` 还得手工把扫描报出的偏移加
 * 回去，而那正是这条命令要消灭的步骤。
 *
 * 覆盖策略刻意不对称，因为两个方向的风险不同：`seek=0` 是「造一个新文件」，
 * `of=` 已存在就拒绝、`-f` 才覆盖（与 `uu cp` 一致）；`seek>0` 是「改一个已有
 * 文件」，`of=` 必须存在且**不需要** `-f` —— 就地改写本就是它的意图。
 */
internal fun UuCommands.dd(args: List<String>, ctx: Ctx): Result {
    rejectUnknownFlags(ctx, args, listOf("-f"))?.let { return it }
    // dd 是 k=v 语法（if= / of= / skip= …），不是 `-flag value`，走不了 splitFlags。
    var force = false
    val kv = HashMap<String, String>()
    for (a in args) {
        if (a == "-f") { force = true; continue }
        val eq = a.indexOf('=')
        val k = if (eq > 0) a.substring(0, eq).lowercase() else ""
        if (k !in DD_KEYS) return Result(ctx.text(R.string.cli_dd_bad_arg, a), 2)
        if (kv.containsKey(k)) return Result(ctx.text(R.string.cli_dd_dup_arg, k), 2)
        kv[k] = a.substring(eq + 1)
    }
    // `if=`（空值）也要当缺参：否则会被当路径解析，最后报「找不到: 」（空名字）——
    // 那条消息完全指不出问题在哪。
    val ifSpec = kv["if"]?.takeIf { it.isNotEmpty() }
        ?: return Result(ctx.text(R.string.cli_dd_need_if), 2)
    val ofSpec = kv["of"]?.takeIf { it.isNotEmpty() }
        ?: return Result(ctx.text(R.string.cli_dd_need_of), 2)

    // 给了值但不是数字必须报错 —— 静默当「没给」而取默认 0，会切到错误的位置
    // 且从输出上看不出原因。
    var badNum: String? = null
    fun num(key: String): Long? {
        val raw = kv[key] ?: return null
        val v = parseNum(raw)
        if (v == null) badNum = raw
        return v
    }
    val skip = num("skip") ?: 0L
    val count = num("count")
    val seek = num("seek") ?: 0L
    badNum?.let { return Result(ctx.text(R.string.cli_bad_num, it), 2) }
    if (skip < 0 || seek < 0 || (count != null && count < 0)) {
        return Result(ctx.text(R.string.cli_dd_negative), 2)
    }

    // 源：fN（区间或整文件）或普通路径 → (宿主文件, 基准偏移, 可用字节数)
    val host: File
    val base: Long
    val avail: Long
    when (val r = fdRef(ifSpec, ctx)) {
        is FdRef.Stale -> return r.result
        is FdRef.Hit -> { host = r.e.host; base = r.e.offset; avail = r.e.byteSize() }
        FdRef.NotFd -> {
            val f = UuText.resolve(ctx.cwd, ifSpec)
            if (!f.isFile) return Result(UuText.notFound(ctx.str, ifSpec), 1)
            host = f; base = 0L; avail = f.length()
        }
    }
    if (skip > avail) {
        return Result(ctx.text(R.string.cli_dd_skip_past_end, fmt(skip), fmt(avail)), 1)
    }
    // count 不给 = 切到源末尾；给了也不越过剩下的
    val n = minOf(count ?: (avail - skip), avail - skip)

    val dst = UuText.resolve(ctx.cwd, ofSpec)
    // 自己切自己：先读后写会把还没读的源覆盖掉，产物必然是垃圾
    if (dst.canonicalPath == host.canonicalPath) {
        return Result(ctx.text(R.string.cli_dd_same_file, host.name), 1)
    }
    val report: (Long) -> Unit = { done ->
        ctx.progress?.invoke(ctx.text(R.string.cli_dd_progress, fmt(done), fmt(n)))
    }

    if (seek == 0L) {
        if (dst.isDirectory) return Result(ctx.text(R.string.cli_exists, dst.path), 1)
        if (dst.exists()) {
            if (!force) return Result(ctx.text(R.string.cli_exists, dst.path), 1)
            if (!com.usefulunpacker.deleteOutputChecked(dst, recursive = true, cleanup = false)) return Result(UuText.failed(ctx.str, dst.path), 1)
        }
        dst.parentFile?.mkdirs()
        val ok = com.usefulunpacker.observedAttempt("UuCommands") { carveToFile(host, base + skip, n, dst, report); true }
            .getOrDefault(false)
        if (!ok) {
            com.usefulunpacker.deleteOutputChecked(dst)   // 半成品不留：失败后留个截断文件比直接报错更坏
            return Result(UuText.failed(ctx.str, dst.name), 1)
        }
        return Result(ctx.text(R.string.cli_dd_done, fmt(n), dst.absolutePath))
    }

    // seek>0：就地改写已有文件的 [seek, seek+n)
    if (!dst.isFile) return Result(ctx.text(R.string.cli_dd_seek_needs_file, dst.path), 1)
    val ok = com.usefulunpacker.observedAttempt("UuCommands") {
        java.io.RandomAccessFile(dst, "rw").use { raf ->
            raf.seek(seek)
            carveTo(host, base + skip, n, report) { b, len -> raf.write(b, 0, len) }
        }
        true
    }.getOrDefault(false)
    if (!ok) return Result(UuText.failed(ctx.str, dst.name), 1)
    return Result(ctx.text(R.string.cli_dd_spliced, fmt(n), "0x%X".format(seek), dst.absolutePath))
}

/**
 * `uu cmp <a> <b> [-l] [-s] [-n N]` — **逐字节**比对（两侧都支持 `fN`）。
 *
 * 与 [diff] 的分工：[diff] 比的是**条目表**且只看大小，所以「重打包前后条目大小
 * 完全一样、字节却不同」这种情况它永远报"无差异" —— 而校验重打包恰恰要后者。
 *
 * 退出码按 `cmp(1)` 的约定：**0 相同 / 1 有差异 / 2 出错**，脚本才能直接 `if $?`。
 * 整块流式读取，不把两个文件读进内存（对比两个 GB 级归档是常规用法）。
 */
internal fun UuCommands.cmp(args: List<String>, ctx: Ctx): Result {
    rejectUnknownFlags(ctx, args, listOf("-l", "-s", "-n"))?.let { return it }
    val pa = splitFlags(args, listOf("-n"), listOf("-l", "-s"))
    missingValueError(ctx, pa)?.let { return it }
    if (pa.pos.size != 2) return Result(ctx.text(R.string.cli_cmp_needs_two), 2)
    val listAll = "-l" in pa.bools
    val silent = "-s" in pa.bools
    val limitRaw = pa.values["-n"]
    val limit = if (limitRaw == null) Long.MAX_VALUE
                else parseNum(limitRaw)?.takeIf { it >= 0 }
                    ?: return Result(ctx.text(R.string.cli_bad_num, limitRaw), 2)

    var tempA: File? = null
    var tempB: File? = null
    try {
        // 任何失败都必须落成退出码 2：`resolveSource` 的 Fail 可能带 1（fN 宿主被改过），
        // 而 cmp 里 1 是「有差异」—— 直接透传会让脚本把「读不了」当成「内容不同」。
        val fa: File
        val nameA: String
        when (val r = resolveSource(pa.pos[0], ctx)) {
            is SrcSpec.Fail -> return Result(r.result.text, 2)
            is SrcSpec.Path -> {
                if (!r.f.isFile) return Result(UuText.notFound(ctx.str, pa.pos[0]), 2)
                fa = r.f; nameA = r.f.name
            }
            is SrcSpec.Fd -> { tempA = r.temp; fa = r.f; nameA = r.label }
        }
        val fb: File
        val nameB: String
        when (val r = resolveSource(pa.pos[1], ctx)) {
            is SrcSpec.Fail -> return Result(r.result.text, 2)
            is SrcSpec.Path -> {
                if (!r.f.isFile) return Result(UuText.notFound(ctx.str, pa.pos[1]), 2)
                fb = r.f; nameB = r.f.name
            }
            is SrcSpec.Fd -> { tempB = r.temp; fb = r.f; nameB = r.label }
        }

        val bufA = ByteArray(CMP_CHUNK)
        val bufB = ByteArray(CMP_CHUNK)
        var off = 0L            // 已比对的字节数
        var diffs = 0L
        var firstAt = -1L
        var firstA = -1
        var firstB = -1
        val listed = ArrayList<String>()
        var truncated = 0L
        var shortRead = false
        java.io.FileInputStream(fa).use { ia ->
            java.io.FileInputStream(fb).use { ib ->
                while (!shortRead && off < limit && !Thread.currentThread().isInterrupted) {
                    val want = minOf(CMP_CHUNK.toLong(), limit - off).toInt()
                    val na = ia.read(bufA, 0, want)
                    val nb = ib.read(bufB, 0, want)
                    if (na <= 0 && nb <= 0) break      // 两侧同时到末尾
                    // 短的一侧到此为止：它后面的每个字节都算差异（长度差）
                    val n = maxOf(na, nb)
                    for (i in 0 until n) {
                        val va = if (i < na) bufA[i].toInt() and 0xFF else -1
                        val vb = if (i < nb) bufB[i].toInt() and 0xFF else -1
                        if (va == vb) continue
                        if (firstAt < 0) { firstAt = off + i; firstA = va; firstB = vb }
                        diffs++
                        if (listAll) {
                            if (listed.size < CMP_MAX_LIST) {
                                listed.add(ctx.text(R.string.cli_cmp_line,
                                    "0x%08X".format(off + i), byteHex(va), byteHex(vb)))
                            } else truncated++
                        }
                    }
                    off += n
                    if (na < want || nb < want) shortRead = true
                }
            }
        }

        if (diffs == 0L) {
            return if (silent) Result("", 0)
            else Result(ctx.text(R.string.cli_cmp_same, nameA, nameB, fmt(off)))
        }
        if (silent) return Result("", 1)
        val sb = StringBuilder()
        listed.forEach { sb.append(it).append('\n') }
        if (truncated > 0) sb.append(ctx.text(R.string.cli_cmp_more, truncated.toString())).append('\n')
        sb.append(ctx.text(R.string.cli_cmp_first,
            "0x%X".format(firstAt), byteHex(firstA), byteHex(firstB)))
        sb.append('\n').append(ctx.text(R.string.cli_cmp_count, diffs.toString(), fmt(off)))
        return Result(sb.toString(), 1)
    } finally {
        tempA?.let { com.usefulunpacker.deleteOutputChecked(it, recursive = true) }
        tempB?.let { com.usefulunpacker.deleteOutputChecked(it, recursive = true) }
    }
}

/** 差异行里的一个字节；一侧已到末尾（`-1`）用 `--`，那是符号不是文案。 */
private fun UuCommands.byteHex(v: Int) = if (v < 0) "--" else "%02X".format(v)

/**
 * `uu guess <file|fN>` — 从内容反推「这个怪文件被做了什么变换」。
 *
 * 只读头部 [TrGuesser.HEAD_PROBE] 字节，不整文件进内存。**报告永远是
 * 「候选 + 判据」**，不写「就是它」—— 证据不足的候选在 [TrGuesser] 里就被丢掉了。
 *
 * 用法顺序上它排在 `uu scan` 之后：`zip -F/-FF` 的教训是**先信任索引、再扫签名、
 * 最后才猜**。猜出来的东西永远比读出来的弱。
 */
internal fun UuCommands.guess(args: List<String>, ctx: Ctx): Result {
    rejectUnknownFlags(ctx, args)?.let { return it }
    if (args.isEmpty()) return needFile()
    if (args.size > 1) return Result(usageFor("guess"), 2)
    val spec = args[0]

    val host: File
    val base: Long
    val avail: Long
    when (val r = fdRef(spec, ctx)) {
        is FdRef.Stale -> return r.result
        is FdRef.Hit -> { host = r.e.host; base = r.e.offset; avail = r.e.byteSize() }
        FdRef.NotFd -> {
            val f = UuText.resolve(ctx.cwd, spec)
            if (!f.isFile) return Result(UuText.notFound(ctx.str, spec), 1)
            host = f; base = 0L; avail = f.length()
        }
    }
    val want = minOf(avail, TrGuesser.HEAD_PROBE.toLong()).toInt()
    if (want <= 0) return Result(ctx.text(R.string.cli_guess_none, spec))
    val head = ByteArray(want)
    val n = com.usefulunpacker.observedAttempt("UuCommands") {
        java.io.RandomAccessFile(host, "r").use { raf ->
            raf.seek(base)
            var off = 0
            while (off < head.size) {
                val k = raf.read(head, off, head.size - off)
                if (k <= 0) break
                off += k
            }
            off
        }
    }.getOrDefault(-1)
    if (n < 0) return Result(UuText.failed(ctx.str, spec), 1)

    val hits = TrGuesser.guess(TrProbe(head.copyOf(n), avail))
    if (hits.isEmpty()) return Result(ctx.text(R.string.cli_guess_none, spec))

    val sb = StringBuilder()
    sb.append(ctx.text(R.string.cli_guess_header, spec, hits.size.toString())).append('\n')
    for (h in hits) {
        val ev = when (val e = h.evidence) {
            is TrEvidence.Structure -> ctx.text(R.string.cli_guess_ev_struct, e.tag)
            is TrEvidence.Constraints -> ctx.text(R.string.cli_guess_ev_constraints, e.n.toString())
        }
        sb.append("  ").append(h.pipeline.padEnd(GUESS_COL))
            .append("→ ").append(h.format).append("  ").append(ev).append('\n')
    }
    // 把第一条写成能直接复制粘贴的命令 —— 用户不必自己拼管道
    val top = hits.first()
    sb.append(ctx.text(
        R.string.cli_guess_try, spec, top.pipeline,
        "${host.nameWithoutExtension}-fixed.${host.extension.ifEmpty { "bin" }}"
    ))
    return Result(sb.toString().trimEnd('\n'))
}
