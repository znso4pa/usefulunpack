package com.usefulunpacker

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import com.usefulunpacker.featureThread as thread

import com.usefulunpacker.UuCommands.Ctx
import com.usefulunpacker.UuCommands.Result
import com.usefulunpacker.UuCommands.FdRef
import com.usefulunpacker.UuCommands.PreResult

/**
 * `uu x <archive> [outdir] [entry...] [-p pw]`。
 *
 * 进度**只写在终端输出区**（产品要求，不弹对话框）：[Ctx.progress] 每 200ms
 * 收到一条整行替换的进度行；完成后输出统计 + all set。密码遵守项目不变量：
 * 加密包在**拿调度槽之前**解决（-p 或模态询问）。
 */
internal fun UuCommands.extract(args: List<String>, ctx: Ctx): Result {
    val pa = splitFlags(args, listOf("-p", "-o", "--pre"))
    missingValueError(ctx, pa)?.let { return it }
    val (flags, _, pos) = Triple(pa.values, pa.bools, pa.pos)
    // --pre：读方向的便捷变换（见 [preTransform]）。不要求可逆（逆只在写方向
    // --post 有意义），但变换结果必须能被 detectFormatByMagic 认出，否则拒绝。
    val preText = flags["--pre"]
    val prePipeline = if (preText == null) null else when (val r = TrParser.parse(preText)) {
        is TrParser.Out.Err -> return Result(trBadMessage(ctx, r.bad), 2)
        is TrParser.Out.Ok -> r.pipeline
    }
    val pw = flags["-p"]?.takeIf { it.isNotEmpty() } ?: ctx.password
    if (pos.isEmpty()) return needFile()
    val spec = pos[0]
    val optOutdir: String? = flags["-o"]?.takeIf { it.isNotEmpty()}
    val cancelled = AtomicBoolean(false)

    // fN 引用：整文件条目直接用 host；区间条目临时 carve 出来再解
    var carvedTemp: File? = null
    var preTemp: File? = null
    // `run` 是 inline，所以分支里的 `return` 直接返回本函数（非局部返回）。
    val (src0Raw, displayName) = run {
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

    // --pre：把解析好的源先变换到临时文件，再交给解压器。变换结果必须能被
    // 认出是归档，否则拒绝（preTransform 里处理）；变换后会在成功消息里加提示。
    val src0: File = if (prePipeline != null) {
        when (val t = preTransform(src0Raw, prePipeline, ctx)) {
            is PreResult.Err -> return t.result
            is PreResult.Ok -> { preTemp = t.file; t.file }
        }
    } else {
        src0Raw
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
        val opH = ctx.reserveArchive(fmt)
        try {
            // await()==false = 排队期被取消：必须中止，不能带着取消标记开跑
            //（Rust 入口的 clear_cancel 会把它清掉）。
            if (!ctx.awaitArchive(opH)) return Result(UuText.extractCancelled(ctx.str), 1)
            val outDir = outDirExplicit
                ?: uniqueFile(defaultOut(ctx), src0.nameWithoutExtension.ifEmpty { "extracted" })
            val accessors = extractAccessors(fmt)
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

            val o = try {
                extractByFormat(fmt, src0.path, outDir.path, selected, ctx.prefs
                    ?: throw IllegalStateException("prefs required"), pwd)
            } finally {
                done.set(true)
                poller?.join(600)
            }

            if (cancelled.get()) {
                com.usefulunpacker.deleteOutputChecked(outDir, recursive = true)
                return Result(UuText.extractCancelled(ctx.str), 1)
            }
            if (!o.counts.ok) {
                val msg = if (act != null) friendlyExtractError(act, o.error)
                          else (o.error ?: "failed")
                return Result(msg, 1)
            }
            ctx.onOutput?.invoke(outDir)
            // 解压产物可能就在当前窗口的目录里，刷新让用户直接看到。
            // 必须回 UI 线程：refreshTab→navTab 摸视图，后台线程直接调会抛
            // CalledFromWrongThreadException（"Main thread (tid 2), calling
            // thread (tid 29)"——之前被 dispatch 的 catch 包成失败文本，
            // 看起来像解压失败，其实产物是好的）。
            act?.runFeatureOnUiThread {
                if (!act.isFinishing && !act.isDestroyed) act.refreshTab(act.activeTab)
            }
            val missNote = if (selMisses.isEmpty()) "" else
                "\n" + ctx.text(R.string.cli_x_glob_miss, selMisses.joinToString(" "))
            val preNote = if (prePipeline != null) {
                "\n" + ctx.text(R.string.cli_pre_warn, prePipeline.text())
            } else ""
            return Result(
                ctx.text(R.string.cli_extract_ok, displayName,
                    o.counts.total.toString(), o.counts.success.toString(),
                    o.counts.error.toString()) + "\n" +
                ctx.text(R.string.cli_all_set) + "\n" +
                ctx.text(R.string.cli_x_outdir, outDir.absolutePath) + missNote + preNote
            )
        } finally {
            opH?.release()
        }
    } finally {
        carvedTemp?.let { com.usefulunpacker.deleteOutputChecked(it, recursive = true) }
        preTemp?.let { com.usefulunpacker.deleteOutputChecked(it, recursive = true) }
    }
}
