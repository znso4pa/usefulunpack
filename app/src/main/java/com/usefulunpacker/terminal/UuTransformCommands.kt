package com.usefulunpacker

import java.io.File

import com.usefulunpacker.UuCommands.Ctx
import com.usefulunpacker.UuCommands.Result
import com.usefulunpacker.UuCommands.FdRef
import com.usefulunpacker.UuCommands.PreResult

/**
 * `uu l/x --pre` —— 把 [host] 整体按 [pipeline] 变换到临时文件。
 *
 * 读方向**不要求可逆**（逆只在写方向 `--post` 有意义），但变换结果**必须能被
 * [detectFormatByMagic] 认出是归档** —— 否则就是「变换后还是垃圾」，静默喂给
 * 列目录 / 解压器只会产出一堆废条目 / 废文件，不如直接拒。
 *
 * 临时文件落在 [Ctx.cacheDir]（没有 → 报 `needsActivity`）；调用方负责删除
 * （`uu l` 在 [listResolved] 的 finally，`uu x` 在自己的 finally）。
 *
 * 设了一个宽松的大小上限 [PRE_MAX_BYTES]：超出就拒，提示改用 `uu tr` 先落成
 * 永久文件再解 —— 避免在大包上静默复制出一份同样大的临时文件。
 */
internal fun UuCommands.preTransform(host: File, pipeline: TrPipeline, ctx: Ctx): PreResult {
    val cache = ctx.cacheDir ?: return PreResult.Err(Result(UuText.needsActivity(ctx.str), 2))
    val avail = com.usefulunpacker.observedAttempt("UuCommands") { host.length() }.getOrDefault(0L)
    val outLen = avail - pipeline.skip
    if (outLen <= 0) {
        return PreResult.Err(Result(ctx.text(R.string.cli_pre_empty, pipeline.text()), 1))
    }
    if (outLen > PRE_MAX_BYTES) {
        return PreResult.Err(Result(ctx.text(R.string.cli_pre_too_big, fmt(outLen), pipeline.text()), 1))
    }
    val tmp = File(cache, "uu_pre/${host.nameWithoutExtension}-${System.nanoTime()}.bin")
    tmp.parentFile?.mkdirs()
    val res = runTransform(host, 0, null, pipeline, tmp)
    if (res !is TrResult.Ok || res.interrupted) {
        com.usefulunpacker.deleteOutputChecked(tmp)
        return PreResult.Err(Result(UuText.failed(ctx.str, host.name), 1))
    }
    // 防呆：变换后必须能认出是归档，否则拒（见上）。
    if (detectFormatByMagic(tmp) == null) {
        com.usefulunpacker.deleteOutputChecked(tmp)
        return PreResult.Err(Result(ctx.text(R.string.cli_pre_unrecognised, pipeline.text()), 1))
    }
    return PreResult.Ok(tmp)
}

/**
 * `uu c --post` —— 对刚打好的归档整体施加一条**可逆**管道。
 *
 * 写方向比读方向危险得多：产出的是一个**只有知道这条管道的人才解得开**的文件。
 * 两道闸：
 *  ① **静态**：管道必须整体可逆（`skip` 直接拒）—— 逆不存在就无从验证；
 *  ② **动态**：变换完用**逆管道读回**，与变换前的中间产物**逐字节比对**，不等就
 *     判定失败。这是唯一能挡住「写出来看着成功、其实已经坏了」的防线。
 *
 * 失败时把**未变换**的归档还原回原路径，并在消息里写明 —— 既不毁掉打包的成果，
 * 也不让人误以为拿到的是变换过的包。
 *
 * @return 出错时的 Result；成功返回 null（调用方继续正常收尾）。
 */
internal fun UuCommands.applyPost(ctx: Ctx, out: File, pipeline: TrPipeline): Result? {
    val inverse = pipeline.inverse()
        ?: return Result(ctx.text(R.string.cli_post_not_invertible), 1)
    val pre = File(ctxParent(out), ".${out.name}.${System.nanoTime()}.pre")
    // 产物先挪到一边：变换的输入必须是它、输出必须是原路径
    if (!out.renameTo(pre)) return Result(UuText.failed(ctx.str, out.name), 1)
    val r = runTransform(pre, 0, pre.length(), pipeline, out)
    val wrote = (r as? TrResult.Ok)?.takeIf { !it.interrupted }
    if (r is TrResult.Failed || wrote == null) {
        com.usefulunpacker.deleteOutputChecked(out)
        pre.renameTo(out)
        return Result(ctx.text(R.string.cli_post_failed_kept, out.name), 1)
    }
    if (!inverseRoundTrips(pre, out, inverse)) {
        com.usefulunpacker.deleteOutputChecked(out)
        pre.renameTo(out)
        return Result(ctx.text(R.string.cli_post_verify_failed, out.name), 1)
    }
    com.usefulunpacker.deleteOutputChecked(pre)
    return null
}

/**
 * `uu tr <file|fN> <pipeline> [-o out] [-f]` — 按管道变换字节。
 *
 * `uu tr game.dat "skip 16 | xor 0xAA" -o game.zip` —— 「整个文件被裹了一层」
 * 这类归档的出路：内置的 27 种格式都解不了它，但它既不是加密也不是新压缩算法，
 * 只是一个按偏移的字节函数。
 *
 * **源文件只读**，输出永远换路径（同 `uu dd` 的 same-file 拒绝）。变换完**必报
 * 识别结果** —— `uu tr` 是显式字节操作（同 `dd`），不会因为"结果不像归档"就拒绝，
 * 但一句不说就成了静默产出垃圾；真正要拦的是将来 `--pre` 那种隐式形式。
 *
 * 管道词表见 [TrParser] / [TrOp]。`skip` 只允许作第一步（放中间时"丢的是变换前
 * 还是变换后的字节"有歧义，而那个歧义会静默改变结果）。
 */
internal fun UuCommands.tr(args: List<String>, ctx: Ctx): Result {
    rejectUnknownFlags(ctx, args, listOf("-o", "-f"))?.let { return it }
    val pa = splitFlags(args, listOf("-o"), listOf("-f"))
    missingValueError(ctx, pa)?.let { return it }
    if (pa.pos.size != 2) return Result(usageFor("tr"), 2)
    val spec = pa.pos[0]
    val pipeline = when (val p = TrParser.parse(pa.pos[1])) {
        is TrParser.Out.Err -> return Result(trBadMessage(ctx, p.bad), 2)
        is TrParser.Out.Ok -> p.pipeline
    }

    // 源：fN（区间或整文件）或普通路径 → (宿主文件, 基准偏移, 可用字节数)
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
    if (pipeline.skip > avail) {
        return Result(ctx.text(R.string.cli_tr_skip_past_end, fmt(pipeline.skip), fmt(avail)), 1)
    }
    val outLen = avail - pipeline.skip

    val dst = pa.values["-o"]?.let { UuText.resolve(ctx.cwd, it) }
        ?: uniqueFile(
            ctxParent(host),
            "${host.nameWithoutExtension}-tr.${host.extension.ifEmpty { "bin" }}"
        )
    // 自己变换自己：先读后写会把还没读的源覆盖掉，产物必然是垃圾
    if (dst.canonicalPath == host.canonicalPath) {
        return Result(ctx.text(R.string.cli_dd_same_file, host.name), 1)
    }
    if (dst.isDirectory) return Result(ctx.text(R.string.cli_exists, dst.path), 1)
    if (dst.exists()) {
        if ("-f" !in pa.bools) return Result(ctx.text(R.string.cli_exists, dst.path), 1)
        if (!com.usefulunpacker.deleteOutputChecked(dst, recursive = true, cleanup = false)) return Result(UuText.failed(ctx.str, dst.path), 1)
    }
    dst.parentFile?.mkdirs()

    val report: (Long) -> Unit = { done ->
        ctx.progress?.invoke(ctx.text(R.string.cli_tr_progress, fmt(done), fmt(outLen)))
    }
    val res = runTransform(host, base, avail, pipeline, dst, report)
    if (res is TrResult.Failed) {
        com.usefulunpacker.deleteOutputChecked(dst)   // 半成品不留：失败后留个截断文件比报错更坏
        return Result(UuText.failed(ctx.str, dst.name), 1)
    }
    val ok = res as TrResult.Ok
    if (ok.interrupted) {
        com.usefulunpacker.deleteOutputChecked(dst)
        return Result(UuText.extractCancelled(ctx.str), 1)
    }

    // 变换完必报「变成了什么」；识别不出也要说，并给前几字节便于排查
    val head = ByteArray(MAGIC_PROBE)
    val hn = com.usefulunpacker.observedAttempt("UuCommands") { java.io.FileInputStream(dst).use { readFully(it, head) } }.getOrDefault(0)
    val sig = head.copyOf(hn)
    val what = detectFormatByMagic(sig)
    val line = if (what != null) ctx.text(R.string.cli_tr_recognised, what)
               else ctx.text(R.string.cli_tr_unrecognised, sig.joinToString(" ") { "%02X".format(it) })
    return Result(ctx.text(R.string.cli_tr_done, fmt(ok.written), dst.absolutePath) + "\n" + line)
}

/** 管道解析失败 → 指到具体哪一步。 */
internal fun UuCommands.trBadMessage(ctx: Ctx, bad: TrParser.Bad): String = when (bad) {
    is TrParser.Bad.UnknownStep -> ctx.text(R.string.cli_tr_unknown_step, bad.token)
    is TrParser.Bad.BadArg -> ctx.text(R.string.cli_tr_bad_arg, bad.step, bad.arg)
    TrParser.Bad.SkipNotFirst -> ctx.text(R.string.cli_tr_skip_first)
    is TrParser.Bad.TooManySteps -> ctx.text(R.string.cli_tr_too_many, bad.max.toString())
}
