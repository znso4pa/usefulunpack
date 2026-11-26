package com.usefulunpacker

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import com.usefulunpacker.featureThread as thread

import com.usefulunpacker.UuCommands.Ctx
import com.usefulunpacker.UuCommands.Result
import com.usefulunpacker.UuCommands.SrcSpec

/** `uu cso <file> [out]` — ISO→CSO / CSO→ISO。 */
internal fun UuCommands.cso(args: List<String>, ctx: Ctx): Result {
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
    val opH = ctx.reserveArchive("cso")
    val cancelled = AtomicBoolean(false)
    try {
        if (!ctx.awaitArchive(opH)) return Result(UuText.extractCancelled(ctx.str), 1)
        val acc = ProgressAccessors(
            { CsoCore.csoProgressCount() }, { CsoCore.csoProgressTotal() },
            { CsoCore.csoProgressFileCount() }, { CsoCore.csoProgressFileTotal() },
            { CsoCore.csoProgressName() }, { CsoCore.csoCancel() }
        )
        ctx.registerCancel?.invoke {
            opH?.cancelFromOwner { cancelled.set(true); acc.cancel() }
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
            com.usefulunpacker.deleteOutputChecked(outF)
            return Result(UuText.extractCancelled(ctx.str), 1)
        }
        if (!ok) {
            com.usefulunpacker.deleteOutputChecked(outF)
            return Result(UuText.packFailed(ctx.str, outF.name), 1)
        }
        return Result(ctx.text(R.string.cli_cso_done, outF.name) + "\n" +
            ctx.text(R.string.cli_all_set))
    } finally {
        opH?.release()
    }
}

/**
 * `uu audio <src...> <fmt> [-o dir] [--rate N] [--ch N] [--depth N] [--br N]`
 * （另有 `--vbr` / `--q N`，只对 mp3 有意义）—— 音频格式转换。
 *
 * 走的是与「长按 → 文件转换」**同一个引擎**（`AudioCore` +
 * [AudioConvertOptions]），不是第二份实现；选项也共用那个字符串协议。
 *
 * 逐条转换、逐条打印，与 `uu img` 同形：一条失败不影响其余，末行给汇总。
 * 失败时**把引擎给的原因原样带出来** —— `audioConvert` 失败会从 JNI 抛
 * `IOException("audio: …")`，吞掉它只报「失败」等于把唯一的诊断信息扔掉。
 */
internal fun UuCommands.audio(args: List<String>, ctx: Ctx): Result {
    val pa = splitFlags(
        args,
        listOf("-o", "--rate", "--ch", "--depth", "--br", "--q"),
        listOf("--vbr"),
    )
    missingValueError(ctx, pa)?.let { return it }
    // 格式是固定关键字：取**最后一个**匹配的位置参数（其余位置参数都是源），
    // 与 `uu img` 同一个约定 —— 源文件名里出现 "wav" 也不会被当成格式。
    val fmtIdx = pa.pos.indexOfLast { it.lowercase() in AUDIO_TARGETS }
    if (fmtIdx < 0) {
        val only = pa.pos.singleOrNull()
        return if (only != null) Result(ctx.text(R.string.cli_audio_bad_fmt, only), 2)
        else Result(usageFor("audio"), 2)
    }
    val fmt = pa.pos[fmtIdx].lowercase()
    val srcs = pa.pos.filterIndexed { i, _ -> i != fmtIdx }
    if (srcs.isEmpty()) return needFile()

    // 越界的值不在这里夹：`AudioOptions::sanitize` 是唯一真源（UI 走的是同一条）。
    val opts = AudioConvertOptions(
        rate = pa.values["--rate"]?.toIntOrNull() ?: 0,
        channels = pa.values["--ch"]?.toIntOrNull() ?: 0,
        depth = pa.values["--depth"]?.toIntOrNull() ?: 16,
        bitrate = pa.values["--br"]?.toIntOrNull() ?: 0,
        vbr = "--vbr" in pa.bools,
        vbrQuality = pa.values["--q"]?.toIntOrNull() ?: 2,
    )
    val outDir = pa.values["-o"]?.let { UuText.resolve(ctx.cwd, it) } ?: defaultOut(ctx)
    outDir.mkdirs()

    val opH = ctx.reserveArchive("audio")
    val cancelled = AtomicBoolean(false)
    val sb = StringBuilder()
    var ok = 0
    var fail = 0
    try {
        if (!ctx.awaitArchive(opH)) return Result(UuText.extractCancelled(ctx.str), 1)
        val acc = ProgressAccessors(
            { AudioCore.audioProgressCount() }, { AudioCore.audioProgressTotal() },
            { AudioCore.audioProgressFileCount() }, { AudioCore.audioProgressFileTotal() },
            { AudioCore.audioProgressName() }, { AudioCore.audioCancel() }
        )
        ctx.registerCancel?.invoke {
            opH?.cancelFromOwner { cancelled.set(true); acc.cancel() }
        }

        for (arg in srcs) {
            if (cancelled.get()) return Result(UuText.extractCancelled(ctx.str), 1)
            val src = UuText.resolve(ctx.cwd, arg)
            if (!src.isFile) {
                sb.append(UuText.notFound(ctx.str, arg)).append('\n'); fail++; continue
            }
            if (src.extension.lowercase() !in AUDIO_SOURCE_EXTS) {
                sb.append(ctx.text(R.string.cli_audio_bad_src, src.name)).append('\n')
                fail++; continue
            }
            val outF = uniqueFile(outDir, "${src.nameWithoutExtension}.$fmt")
            val done = AtomicBoolean(false)
            val poller = if (ctx.progress != null) thread {
                while (!done.get()) {
                    ctx.progress?.invoke(progressLine(acc))
                    Thread.sleep(200)
                }
            } else null
            // 单条失败不能中断整批：把原因记下来接着转下一个。
            val okOne = try {
                AudioCore.audioConvert("", src.path, outF.path, fmt, opts.toJni())
            } catch (e: Exception) {
                com.usefulunpacker.deleteOutputChecked(outF)
                sb.append(
                    ctx.text(R.string.cli_conv_fail, src.name, e.message ?: e.javaClass.simpleName)
                ).append('\n')
                fail++
                continue
            } finally {
                done.set(true)
                poller?.join(600)
            }
            if (!okOne) {
                // 返回 false 而没抛异常：引擎侧的失败路径已经删过输出，这里再删一次是幂等的。
                com.usefulunpacker.deleteOutputChecked(outF)
                sb.append(ctx.text(R.string.cli_conv_fail, src.name, fmt)).append('\n')
                fail++
                continue
            }
            ok++
            sb.append(ctx.text(R.string.cli_conv_ok, src.name, outF.name)).append('\n')
        }

        if (cancelled.get()) return Result(UuText.extractCancelled(ctx.str), 1)
        if (srcs.size > 1) {
            sb.append(ctx.text(R.string.cli_conv_summary, ok.toString(), fail.toString()))
        }
        return Result(sb.toString().trimEnd('\n'), if (ok == 0 && fail > 0) 1 else 0)
    } finally {
        opH?.release()
    }
}

/**
 * `uu video` —— 视频格式转换（WMV/ASF → MP4）。
 *
 * 走的是与「长按 → 文件转换 → MP4」**同一个引擎**（[VideoCore]），不是第二份实现。
 * 形状与 `uu audio` 完全一致：逐条转换、逐条打印，一条失败不影响其余，末行给汇总；
 * 失败时把引擎给的原因原样带出来（JNI 抛的是 `IOException("video: …")`）。
 *
 * 目标有两个（`mp4` / `wmv`，见 `VIDEO_TARGETS`），位置参数里**最后一个**恰好是
 * 其中之一时当格式，其余都当源。不写就默认 mp4。
 *
 * 这里**不按扩展名拦**（和界面上的 `VIDEO_SOURCE_EXTS` 不同）：命令行是给人用的，
 * 引擎自己会报「这个文件里没有视频轨」/「没有 XXX 的解码器」，比在终端里再维护
 * 一份白名单诚实。`.mp4` 源选 `wmv` 会被引擎拒掉（没有 H.264 解码器），也是引擎报的。
 */
internal fun UuCommands.video(args: List<String>, ctx: Ctx): Result {
    val pa = splitFlags(args, listOf("-o"))
    missingValueError(ctx, pa)?.let { return it }
    val fmtIdx = pa.pos.indexOfLast { it.lowercase() in VIDEO_TARGETS }
    val srcs = if (fmtIdx >= 0) pa.pos.filterIndexed { i, _ -> i != fmtIdx } else pa.pos
    // 目标：位置参数里的 `mp4` / `wmv`；不给则默认 mp4。
    val target = if (fmtIdx >= 0) pa.pos[fmtIdx].lowercase() else "mp4"
    val outExt = if (target == "wmv") "wmv" else "mp4"
    if (srcs.isEmpty()) return needFile()
    val outDir = pa.values["-o"]?.let { UuText.resolve(ctx.cwd, it) } ?: defaultOut(ctx)
    outDir.mkdirs()

    val opH = ctx.reserveArchive("video")
    val cancelled = AtomicBoolean(false)
    val sb = StringBuilder()
    var ok = 0
    var fail = 0
    try {
        if (!ctx.awaitArchive(opH)) return Result(UuText.extractCancelled(ctx.str), 1)
        val acc = ProgressAccessors(
            { VideoCore.videoProgressCount() }, { VideoCore.videoProgressTotal() },
            { VideoCore.videoProgressFileCount() }, { VideoCore.videoProgressFileTotal() },
            { VideoCore.videoProgressName() }, { VideoCore.videoCancel() }
        )
        ctx.registerCancel?.invoke {
            opH?.cancelFromOwner { cancelled.set(true); acc.cancel() }
        }

        for (arg in srcs) {
            if (cancelled.get()) return Result(UuText.extractCancelled(ctx.str), 1)
            val src = UuText.resolve(ctx.cwd, arg)
            if (!src.isFile) {
                sb.append(UuText.notFound(ctx.str, arg)).append('\n'); fail++; continue
            }
            if (src.extension.lowercase() !in VIDEO_SOURCE_EXTS) {
                sb.append(ctx.text(R.string.cli_video_bad_src, src.name)).append('\n')
                fail++; continue
            }
            val outF = uniqueFile(outDir, "${src.nameWithoutExtension}.$outExt")
            val done = AtomicBoolean(false)
            val poller = if (ctx.progress != null) thread {
                while (!done.get()) {
                    ctx.progress?.invoke(progressLine(acc))
                    Thread.sleep(200)
                }
            } else null
            // 单条失败不能中断整批：把原因记下来接着转下一个。
            val okOne = try {
                VideoCore.videoConvert("", src.path, outF.path, target, "")
            } catch (e: Exception) {
                com.usefulunpacker.deleteOutputChecked(outF)
                sb.append(
                    ctx.text(R.string.cli_conv_fail, src.name, e.message ?: e.javaClass.simpleName)
                ).append('\n')
                fail++
                continue
            } finally {
                done.set(true)
                poller?.join(600)
            }
            if (!okOne) {
                // 返回 false 而没抛异常：引擎侧的失败路径已经删过输出，这里再删一次是幂等的。
                com.usefulunpacker.deleteOutputChecked(outF)
                sb.append(ctx.text(R.string.cli_conv_fail, src.name, target)).append('\n')
                fail++
                continue
            }
            ok++
            sb.append(ctx.text(R.string.cli_conv_ok, src.name, outF.name)).append('\n')
            // 截断的源会「成功但很短」—— 片留着，但要当场说清为什么短。
            val short = VideoCore.videoDecodedPercent()
            if (short in 1..99) {
                sb.append(ctx.text(R.string.cli_conv_short, short.toString())).append('\n')
            }
        }

        if (cancelled.get()) return Result(UuText.extractCancelled(ctx.str), 1)
        if (srcs.size > 1) {
            sb.append(ctx.text(R.string.cli_conv_summary, ok.toString(), fail.toString()))
        }
        return Result(sb.toString().trimEnd('\n'), if (ok == 0 && fail > 0) 1 else 0)
    } finally {
        opH?.release()
    }
}

/** `uu mvdec <file...> [-o dir]` — MV/MZ 资产解密（标准头重建，无需密钥）。 */
internal fun UuCommands.mvdec(args: List<String>, ctx: Ctx): Result {
    val pa = splitFlags(args, listOf("-o"))
    missingValueError(ctx, pa)?.let { return it }
    if (pa.pos.isEmpty()) return needFile()
    val outDir = pa.values["-o"]?.let { UuText.resolve(ctx.cwd, it) } ?: defaultOut(ctx)
    outDir.mkdirs()
    var ok = 0
    var fail = 0
    for (p in pa.pos) {
        ctx.checkCancelled?.invoke()
        val f = UuText.resolve(ctx.cwd, p)
        if (!f.isFile) { fail++; continue }
        val o = ctx.withArchiveSlot("rpgmv", extract = true) {
            com.usefulunpacker.observedAttempt("UuCommands") {
                extractByFormat("rpgmv", f.path, outDir.path, "", ctx.prefs
                    ?: throw IllegalStateException("prefs required"), "")
            }.getOrNull()
        }
        if (o != null && o.counts.ok) ok++ else fail++
    }
    val msg = ctx.text(R.string.cli_ok_count, ok.toString())
    return if (fail == 0) Result(msg)
           else Result(msg + "\n" + ctx.text(R.string.cli_pack_partial, fail.toString()), 1)
}

/**
 * `uu img <src...> <jpg|png|webp> [-o dir] [-q 1-100]` — 图片格式转换。
 * 解码/合成/压缩走 `encodeImageTo`（与图片转换对话框同一实现）。
 * 输出**永不覆盖**：目标已存在则自动加 " (n)" 后缀（与对话框一致），
 * 实际路径逐条打印，方便脚本读回。支持 fN（扫描命中的图片可直接转）。
 */
internal fun UuCommands.img(args: List<String>, ctx: Ctx): Result {
    val pa = splitFlags(args, listOf("-o", "-q"))
    missingValueError(ctx, pa)?.let { return it }
    // 格式是固定关键字：取最后一个匹配的位置参数（其余位置参数都是源）
    val fmtIdx = pa.pos.indexOfLast { it.lowercase() in IMG_TARGET_FORMATS }
    if (fmtIdx < 0) {
        val only = pa.pos.singleOrNull()
        return if (only != null) Result(ctx.text(R.string.cli_img_bad_fmt, only), 2)
        else Result(usageFor("img"), 2)
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
                sb.append(ctx.text(R.string.cli_conv_ok, srcArg, dest.absolutePath)).append('\n')
                ok++
            } else {
                sb.append(ctx.text(R.string.cli_img_fail, srcArg)).append('\n')
                fail++
            }
        } finally {
            temp?.let { com.usefulunpacker.deleteOutputChecked(it) }
        }
    }
    if (srcs.size > 1) sb.append(ctx.text(R.string.cli_conv_summary, ok.toString(), fail.toString()))
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
internal fun UuCommands.b64(args: List<String>, ctx: Ctx): Result {
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
        val bytes = com.usefulunpacker.observedAttempt("UuCommands") { src.readBytes() }.getOrNull()
            ?: return Result(UuText.failed(ctx.str, src.name), 1)
        if (!decode) {
            val text = java.util.Base64.getEncoder().encodeToString(bytes)
            val dest = outArg?.let { UuText.resolve(ctx.cwd, it) }
            if (dest != null) {
                if (dest.exists()) return Result(ctx.text(R.string.cli_exists, dest.path), 1)
                dest.parentFile?.mkdirs()
                val ok = com.usefulunpacker.observedAttempt("UuCommands") { dest.writeText(text + "\n"); true }.getOrDefault(false)
                return if (ok) Result(ctx.text(R.string.cli_b64_saved, dest.absolutePath))
                       else Result(UuText.failed(ctx.str, dest.name), 1)
            }
            // 不给路径：base64 文本就是命令输出（可管道、可重定向）
            return Result(text)
        }
        // 解码：**字节不进 String**（默认字符集转换会把 >=0x80 的字节变成 U+FFFD，
        // 实测 round-trip 出来"bytes differ"）。Base64 解码本身允许换行（MIME 解码器）。
        val raw = com.usefulunpacker.observedAttempt("UuCommands") {
            java.util.Base64.getMimeDecoder().decode(bytes.toString(Charsets.ISO_8859_1))
        }.getOrElse { return Result(ctx.text(R.string.cli_b64_bad, spec), 1) }
        val defaultName = src.name.removeSuffix(".b64").removeSuffix(".txt")
            .ifEmpty { "decoded" } + ".bin"
        val dest = outArg?.let { UuText.resolve(ctx.cwd, it) }
            ?: uniqueFile(ctx.cwd, defaultName)
        if (dest.exists()) return Result(ctx.text(R.string.cli_exists, dest.path), 1)
        dest.parentFile?.mkdirs()
        val ok = com.usefulunpacker.observedAttempt("UuCommands") { dest.writeBytes(raw); true }.getOrDefault(false)
        return if (ok) Result(ctx.text(R.string.cli_b64_saved, dest.absolutePath))
               else Result(UuText.failed(ctx.str, dest.name), 1)
    } finally {
        temp?.let { com.usefulunpacker.deleteOutputChecked(it) }
    }
}
