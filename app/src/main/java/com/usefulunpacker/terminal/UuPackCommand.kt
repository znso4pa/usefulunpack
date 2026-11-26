package com.usefulunpacker

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import com.usefulunpacker.featureThread as thread

import com.usefulunpacker.UuCommands.Ctx
import com.usefulunpacker.UuCommands.Result
import com.usefulunpacker.UuCommands.Picker

/**
 * `uu c <src...> [out] [-c|-s] [-f key] [level] [splitMB] [-e scheme] [-p pw]`。
 *  - 单源 + out：`uu c dir out.zip` → out 是**最终输出路径**（产品要求）。
 *  - 单源无 out：落到默认单独路径（uu_cli/<src 名>.<ext>），格式必须 -f 给出。
 *  - `-c`：多源合并成一个包（暂存目录内重名按 `名字 (n)` 去重，默认名 archive.<ext>）。
 *  - `-s`：多源分别压缩（单文件 + zip/7z/tar 系自动临时目录包装）。
 *  产物名**拿到调度槽之后**解析（排队互不撞名）；已存在拒绝；`.pfs` 二义必须 -f。
 */
internal fun UuCommands.pack(args: List<String>, ctx: Ctx): Result {
    val pa = splitFlags(args, listOf("-p", "-f", "-l", "-b", "-e", "--post"), listOf("-c", "-s"))
    missingValueError(ctx, pa)?.let { return it }
    val (flags, bools, pos) = Triple(pa.values, pa.bools, pa.pos)
    val merge = "-c" in bools
    val separate = "-s" in bools
    if (merge && separate) return Result(UuText.failed(ctx.str, "-c / -s"), 1)
    // `--post`：写方向的自定义「压缩」——把打好的归档整体再过一条**可逆**管道。
    // 只在单源模式有意义：批量会产出多个文件、分卷更是没法整体变换。
    val postPipeline = flags["--post"]?.let { text ->
        when (val r = TrParser.parse(text)) {
            is TrParser.Out.Err -> return Result(trBadMessage(ctx, r.bad), 2)
            is TrParser.Out.Ok -> r.pipeline
        }
    }
    if (postPipeline != null) {
        if (merge || separate) return Result(ctx.text(R.string.cli_post_single_only), 1)
        if (postPipeline.inverse() == null) {
            return Result(ctx.text(R.string.cli_post_not_invertible), 1)
        }
    }
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
        val opH = ctx.reserveArchive(fmt)
        try {
            if (!ctx.awaitArchive(opH)) return Result(UuText.extractCancelled(ctx.str), 1)
            val accessors = compressAccessors(fmt)
            ctx.registerCancel?.invoke {
                opH?.cancelFromOwner { cancelled.set(true); accessors.cancel() }
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
                        val outF = if (fmt == "lpk") File(outDir, "archive.$ext") else uniqueFile(outDir, "archive.$ext")
                        if (outF.exists()) return Result(ctx.text(R.string.cli_exists, outF.path), 1)
                        val ok = compressDispatch(staging, outF, fmt, level, pw, ctx.prefs
                            ?: throw IllegalStateException("prefs required"),
                            if (batchSplit > 0) batchSplit * 1024 * 1024 else null, enc)
                        if (ok) okCount++ else failCount++
                        if (ok) {
                            ctx.onOutput?.invoke(packedTaskOutput(outF))
                            val note = if (enc.isNotEmpty()) encNoteText(ctx.str) else ""
                            packResultLines.add(ctx.text(R.string.cli_pack_ok, outF.name) +
                                if (note.isEmpty()) "" else " $note")
                        }
                    } finally {
                        com.usefulunpacker.deleteOutputChecked(staging, recursive = true)
                    }
                } else {
                    // 分别压缩：每个源一个产物；单文件 + zip/7z/tar 系需临时目录包裹
                    for (srcPath in pos) {
                        val src = UuText.resolve(ctx.cwd, srcPath)
                        if (!src.exists()) { failCount++; continue }
                        val outF = if (fmt == "lpk") File(outDir, lpkPackedName(src.name))
                            else uniqueFile(outDir, src.name + "." + ext)
                        if (outF.exists()) {
                            AppDiagnostics.begin("pack_output").fail(FailureCode.IO, "LPK output already exists")
                            failCount++
                            continue
                        }
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
                            if (ok) {
                                ctx.onOutput?.invoke(packedTaskOutput(outF))
                                packResultLines.add(ctx.text(R.string.cli_pack_ok, outF.name))
                            }
                        } finally {
                            wrapDir?.let { com.usefulunpacker.deleteOutputChecked(it, recursive = true) }
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

    val opH = ctx.reserveArchive(fmt)
    try {
        if (!ctx.awaitArchive(opH)) return Result(UuText.extractCancelled(ctx.str), 1)
        // 拿到槽位后再解析产物名（不变量：排队中的两次打包不能在入队时撞名）。
        val outFile = when {
            pos.size >= 2 -> UuText.resolve(ctx.cwd, pos[1])
            fmt == "lpk" -> File(defaultOut(ctx), lpkPackedName(src.name))
            else -> uniqueFile(defaultOut(ctx), src.name + "." + COMPRESS_EXT[fmt])
        }
        if (outFile.exists()) return Result(ctx.text(R.string.cli_exists, outFile.path), 1)
        outFile.parentFile?.mkdirs()

        val accessors = compressAccessors(fmt)
        ctx.registerCancel?.invoke {
            opH?.cancelFromOwner { cancelled.set(true); accessors.cancel() }
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
            wrapDir?.let { com.usefulunpacker.deleteOutputChecked(it, recursive = true) }
            done.set(true)
            poller?.join(600)
        }

        if (cancelled.get()) {
            com.usefulunpacker.deleteOutputChecked(outFile)
            return Result(UuText.extractCancelled(ctx.str), 1)
        }
        if (!ok) {
            val why = PackErrors.last
            return Result(UuText.packFailed(ctx.str, outFile.name) +
                if (why.isEmpty()) "" else "\n$why", 1)
        }
        var postNote = ""
        if (postPipeline != null) {
            applyPost(ctx, outFile, postPipeline)?.let { return it }
            postNote = "\n" + ctx.text(R.string.cli_post_done, postPipeline.text())
        }
        ctx.onOutput?.invoke(packedTaskOutput(outFile))
        return Result(ctx.text(R.string.cli_pack_ok, outFile.name) +
            (if (encNote.isEmpty()) "" else "\n$encNote") + postNote + "\n" +
            ctx.text(R.string.cli_all_set))
    } finally {
        opH?.release()
    }
}
