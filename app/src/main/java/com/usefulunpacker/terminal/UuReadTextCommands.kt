package com.usefulunpacker

import java.io.File

import com.usefulunpacker.UuCommands.Ctx
import com.usefulunpacker.UuCommands.Result
import com.usefulunpacker.UuCommands.SrcSpec

// ─── cat / grep / 文件命令 / set / cso ──────────────────────────────


/** `uu cat <archive> <entry> [-p pw]` → 打印包内文本条目（编码自动探测，有界读取）。 */
internal fun UuCommands.cat(args: List<String>, ctx: Ctx): Result {
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
            val outcome = ctx.withArchiveSlot(fmt, extract = true) {
                extractByFormat(fmt, archive.path, tmpDir.path, rel, ctx.prefs
                    ?: throw IllegalStateException("prefs required"), pw)
            }
            if (!outcome.counts.ok || outcome.counts.total <= 0) {
                return Result(UuText.failed(ctx.str, outcome.error ?: archive.name), 1)
            }
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
            com.usefulunpacker.deleteOutputChecked(tmpDir, recursive = true)
        }
    } finally {
        temp?.let { com.usefulunpacker.deleteOutputChecked(it, recursive = true) }
    }
}

/**
 * `uu grep [-i] <pattern> <path>` — 目录内按行搜文本；path 是归档时先把
 * 文本条目解到临时目录再搜（与预览内搜索同款文本扩展名过滤）。
 */
internal fun UuCommands.grep(args: List<String>, ctx: Ctx): Result {
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
    try {
        val fmt = if (target.isFile) detectFormat(target) ?: detectFormatByMagic(target) else null
        val explicitFile = fmt == null && target.isFile
        val root = if (fmt == null) {
            target
        } else {
            val d = ctx.cacheDir?.let { File(it, "uu_grep/${System.nanoTime()}") }
                ?: return Result(UuText.needsActivity(ctx.str), 2)
            tempDir = d
            val json = listEntriesJsonFor(ctx, fmt, target, gpw)
                ?: return Result(UuText.listFailed(ctx.str, target.name), 1)
            for (e in parseEntries(json)) {
                if (e.isDirectory || e.name.substringAfterLast('.').lowercase() !in TEXT_SEARCH_EXTS) continue
                val rel = sanitizeEntryPath(e.path)
                    ?: return Result(UuText.failed(ctx.str, e.path), 1)
                val outcome = ctx.withArchiveSlot(fmt, extract = true) {
                    extractByFormat(fmt, target.path, d.path, rel, ctx.prefs
                        ?: throw IllegalStateException("prefs required"), gpw)
                }
                if (!outcome.counts.ok || outcome.counts.total <= 0 || !File(d, rel).isFile) {
                    return Result(UuText.failed(ctx.str, outcome.error ?: e.path), 1)
                }
            }
            d
        }
        var matches = 0
        var fileName = 0
        val sb = StringBuilder()
        root.walkTopDown().forEach { f ->
            ctx.checkCancelled?.invoke()
            if (!f.isFile) return@forEach
            // 目录搜索只查文本类；归档解出的临时内容已是白名单；
            // 用户显式点名的文件不设白名单（shell 的 `grep pat file` 语义）
            if (!explicitFile && tempDir == null && f.extension.lowercase() !in TEXT_SEARCH_EXTS) return@forEach
            if (f.length() > GREP_MAX_FILE_BYTES) return@forEach
            val bytes = com.usefulunpacker.observedAttempt("UuCommands") { readPrefix(f, GREP_MAX_FILE_BYTES) }.getOrNull() ?: return@forEach
            val enc = textEncodingOf(bytes) ?: return@forEach
            val text = com.usefulunpacker.observedAttempt("UuCommands") { decodeTextStrict(bytes, enc) }.getOrNull() ?: return@forEach
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
        tempDir?.let { com.usefulunpacker.deleteOutputChecked(it, recursive = true) }
    }
}

/**
 * `uu strings <file|fN> [min] [-e ascii|utf16le] [-n max] [-a]` — 提取可打印串。
 *
 * 用来回答「这是个什么文件」：比 [hex] 高效得多，脚本名、路径、URL、版权行
 * 一眼就能看出来。**默认只扫前 8 MiB**（`-a` 提到 64 MiB）—— 手机上一个
 * 1.7 GB 的归档全扫要很久，而侦察要的几乎总在开头。扫描上限会写在输出里，
 * 不让人误以为「就这么多」。
 *
 * `-e utf16le` 扫 2 字节对齐的宽字符串 —— galgame 脚本常见这种编码，
 * 按 ASCII 扫会一个字都看不到。
 */
internal fun UuCommands.strings(args: List<String>, ctx: Ctx): Result {
    rejectUnknownFlags(ctx, args, listOf("-a", "-e", "-n"))?.let { return it }
    val pa = splitFlags(args, listOf("-e", "-n"), listOf("-a"))
    missingValueError(ctx, pa)?.let { return it }
    if (pa.pos.isEmpty()) return needFile()
    val spec = pa.pos[0]
    val minRaw = pa.pos.getOrNull(1)
    val min = if (minRaw == null) STRINGS_MIN_DEFAULT
              else parseNum(minRaw)?.takeIf { it in 1..1024 }?.toInt()
                  ?: return Result(ctx.text(R.string.cli_strings_bad_min, minRaw), 2)
    val enc = pa.values["-e"]?.lowercase() ?: "ascii"
    if (enc !in setOf("ascii", "utf16le")) {
        return Result(ctx.text(R.string.cli_strings_bad_enc, enc), 2)
    }
    val maxRaw = pa.values["-n"]
    val maxParsed = if (maxRaw == null) STRINGS_MAX_PRINT
                    else parseNum(maxRaw)?.takeIf { it >= 0 }?.toInt()
                        ?: return Result(ctx.text(R.string.cli_bad_num, maxRaw), 2)
    // `-n 0` 按常见约定当「不限量」。真当成 0 上限的话每条都走"超出上限"分支，
    // 最后 hits 仍是 0，于是报「没有可打印串」—— 而文件里明明有一堆。
    val max = if (maxParsed == 0) Int.MAX_VALUE else maxParsed

    var temp: File? = null
    try {
        val src = when (val r = resolveSource(spec, ctx)) {
            is SrcSpec.Fail -> return r.result
            is SrcSpec.Path -> r.f.takeIf { it.isFile }
                ?: return Result(UuText.notFound(ctx.str, spec), 1)
            is SrcSpec.Fd -> { temp = r.temp; r.f }
        }
        val cap = if ("-a" in pa.bools) STRINGS_SCAN_MAX_ALL else STRINGS_SCAN_MAX
        val size = src.length()
        val take = minOf(size, cap)
        if (take <= 0L) return Result(ctx.text(R.string.cli_strings_none, spec, min.toString()))
        val buf = ByteArray(take.toInt())
        val n = java.io.FileInputStream(src).use { readFully(it, buf) }

        val out = StringBuilder()
        var hits = 0
        var hidden = 0L
        scanRuns(buf, n, min, enc == "utf16le") { at, s ->
            if (hits < max) {
                out.append("  ").append("0x%08X".format(at)).append("  ").append(s).append('\n')
                hits++
            } else hidden++
        }
        if (hits == 0) return Result(ctx.text(R.string.cli_strings_none, spec, min.toString()))
        val head = StringBuilder()
        head.append(ctx.text(R.string.cli_strings_header, src.name, hits.toString())).append('\n')
        head.append(out.toString().trimEnd('\n'))
        if (hidden > 0) head.append('\n').append(ctx.text(R.string.cli_strings_more, hidden.toString()))
        if (size > cap) head.append('\n').append(ctx.text(R.string.cli_strings_truncated, fmt(cap)))
        return Result(head.toString())
    } finally {
        temp?.let { com.usefulunpacker.deleteOutputChecked(it, recursive = true) }
    }
}

/**
 * 扫 [buf] 前 [n] 字节里长度 ≥ [min] 的可打印串（绝对偏移见 [base]）。
 * [utf16] 时按 2 字节对齐、低字节可打印且高字节为 0 才算一个字符。
 */
private fun UuCommands.scanRuns(buf: ByteArray, n: Int, min: Int, utf16: Boolean, base: Long = 0L,
                     emit: (Long, String) -> Unit) {
    val step = if (utf16) 2 else 1
    var i = 0
    var runStart = -1
    while (i + step <= n) {
        val lo = buf[i].toInt() and 0xFF
        val printable = lo in 0x20..0x7E && (!utf16 || buf[i + 1].toInt() == 0)
        if (printable) {
            if (runStart < 0) runStart = i
        } else {
            if (runStart >= 0 && (i - runStart) / step >= min) {
                val sb = StringBuilder((i - runStart) / step)
                var j = runStart
                while (j < i) { sb.append((buf[j].toInt() and 0xFF).toChar()); j += step }
                emit(base + runStart, sb.toString())
            }
            runStart = -1
        }
        i += step
    }
    // 末尾那段没被非可打印字节终止的串也要算（文件就在串中间结束）
    if (runStart >= 0 && (n - runStart) / step >= min) {
        val sb = StringBuilder((n - runStart) / step)
        var j = runStart
        while (j + step <= n) { sb.append((buf[j].toInt() and 0xFF).toChar()); j += step }
        emit(base + runStart, sb.toString())
    }
}

/** 读满 [buf]（`read` 允许短读）；返回实际读到的字节数。 */
internal fun UuCommands.readFully(input: java.io.InputStream, buf: ByteArray): Int {
    var off = 0
    while (off < buf.size) {
        val k = input.read(buf, off, buf.size - off)
        if (k <= 0) break
        off += k
    }
    return off
}
