package com.usefulunpacker

import com.usefulunpacker.fileops.RecycleBin
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

import com.usefulunpacker.UuCommands.Ctx
import com.usefulunpacker.UuCommands.Result
import com.usefulunpacker.UuCommands.Picker
import com.usefulunpacker.UuCommands.SrcSpec

/** `uu hash <file>` → MD5 + SHA-256 两行十六进制，流式读取不整载。支持 fN。 */
internal fun UuCommands.hash(args: List<String>, ctx: Ctx): Result {
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
                } finally { r.temp?.let { com.usefulunpacker.deleteOutputChecked(it, recursive = true) } }
            }
        }
    }
    return Result(out.toString().trimEnd('\n'), if (anyOk) 0 else 1)
}

private fun UuCommands.hashOf(f: File): Result =
    Result("MD5      " + hashFile(f, "MD5") + "\nSHA-256  " + hashFile(f, "SHA-256"))

/** `uu cp <src> <dst>` — 文件/目录/fN；dst 已存在（含作为目标本身）拒绝覆盖。 */
internal fun UuCommands.copy(raw: List<String>, ctx: Ctx): Result {
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
        if (overlappingFileOperation(src, dst)) return Result(ctx.text(R.string.cli_failed, dst.path), 1)
        if (dst.exists()) {
            if (!force) return Result(ctx.text(R.string.cli_exists, dst.path), 1)
            // -f 覆盖：先删再拷（目录也整棵换掉 —— 幂等脚本的写法）
            if (!com.usefulunpacker.deleteOutputChecked(dst, recursive = true, cleanup = false)) return Result(ctx.text(R.string.cli_failed, dst.path), 1)
        }
        if (src.isDirectory) src.copyRecursively(dst, overwrite = false)
        else {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = false)
        }
        ctx.onOutput?.invoke(dst)
        return Result(ctx.text(R.string.cli_copy_done, src.path, dst.path))
    } finally {
        srcTemp?.let { com.usefulunpacker.deleteOutputChecked(it, recursive = true) }  // fN 区间临时 carve 用完即清
    }
}

/** `uu rename <old> <new>` — 同目录改名；new 已存在拒绝。 */
internal fun UuCommands.rename(args: List<String>, ctx: Ctx): Result {
    rejectUnknownFlags(ctx, args)?.let { return it }
    if (args.size < 2) return needFile()
    val src = UuText.resolve(ctx.cwd, args[0])
    if (!src.exists()) return Result(UuText.notFound(ctx.str, args[0]), 1)
    val dst = UuText.resolve(ctx.cwd, args[1])
    if (dst.exists()) return Result(ctx.text(R.string.cli_exists, dst.path), 1)
    if (!src.renameTo(dst)) return Result(UuText.failed(ctx.str, args[0]), 1)
    ctx.onOutput?.invoke(dst)
    return Result(ctx.text(R.string.cli_rename_done, src.name, dst.name))
}

/** `uu rm <path...> [-f]` — 默认进回收站（可恢复），`-f` 永久删除。 */
internal fun UuCommands.rm(args: List<String>, ctx: Ctx): Result {
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
        val done = if (hard) com.usefulunpacker.observedAttempt("UuCommands") { com.usefulunpacker.deleteOutputChecked(f, recursive = true, cleanup = false) }.getOrDefault(false)
                   else com.usefulunpacker.observedAttempt("UuCommands") { RecycleBin.moveToRecycleBin(act!!, f) }.getOrDefault(false)
        if (done) ok++ else fail++
    }
    val msg = ctx.text(if (hard) R.string.cli_rm_deleted else R.string.cli_rm_recycled, ok.toString())
    return if (fail == 0) Result(msg)
           else Result(msg + "\n" + ctx.text(R.string.cli_pack_partial, fail.toString()), 1)
}

/** `uu mkdir <path...>` */
internal fun UuCommands.mkdir(args: List<String>, ctx: Ctx): Result {
    rejectUnknownFlags(ctx, args)?.let { return it }
    if (args.isEmpty()) return needFile(Picker.FILE_OR_FOLDER)
    var ok = 0
    var fail = 0
    for (p in args) {
        val f = UuText.resolve(ctx.cwd, p)
        if (f.isDirectory) ok++ else if (f.mkdirs()) {
            ctx.onOutput?.invoke(f)
            ok++
        } else fail++
    }
    val msg = ctx.text(R.string.cli_mkdir_done, ok.toString())
    return if (fail == 0) Result(msg)
           else Result(msg + "\n" + ctx.text(R.string.cli_pack_partial, fail.toString()), 1)
}

/** `uu mv <src> <dst>` — 同卷 rename 原子；跨卷先复制成功再删源。 */
internal fun UuCommands.mv(raw: List<String>, ctx: Ctx): Result {
    rejectUnknownFlags(ctx, raw, listOf("-f"))?.let { return it }
    val pa = splitFlags(raw, emptyList(), listOf("-f"))
    if (pa.pos.size < 2) return needFile()
    if (pa.pos.size > 2) return Result(ctx.text(R.string.cli_one_src_only, "mv"), 2)
    val force = "-f" in pa.bools
    val src = UuText.resolve(ctx.cwd, pa.pos[0])
    if (!src.exists()) return Result(UuText.notFound(ctx.str, pa.pos[0]), 1)
    var dst = UuText.resolve(ctx.cwd, pa.pos[1])
    if (dst.isDirectory) dst = File(dst, src.name)
    if (overlappingFileOperation(src, dst)) return Result(ctx.text(R.string.cli_failed, dst.path), 1)
    if (dst.exists()) {
        if (!force) return Result(ctx.text(R.string.cli_exists, dst.path), 1)
        if (!com.usefulunpacker.deleteOutputChecked(dst, recursive = true, cleanup = false)) return Result(ctx.text(R.string.cli_failed, dst.path), 1)
    }
    if (src.renameTo(dst)) {
        ctx.onOutput?.invoke(dst)
        return Result(ctx.text(R.string.cli_mv_done, src.name, dst.path))
    }
    try {
        if (src.isDirectory) {
            if (!src.copyRecursively(dst, overwrite = false)) throw java.io.IOException("Incomplete move copy")
        } else { dst.parentFile?.mkdirs(); src.copyTo(dst, overwrite = false) }
    } catch (e: Exception) {
        AppDiagnostics.failure("move_copy", e)
        deleteOutputChecked(dst, recursive = true)
        return Result(UuText.failed(ctx.str, e.message ?: src.name), 1)
    }
    if (!deleteOutputChecked(src, recursive = src.isDirectory)) return Result(ctx.text(R.string.cli_failed, src.path), 1)
    ctx.onOutput?.invoke(dst)
    return Result(ctx.text(R.string.cli_mv_done, src.name, dst.path))
}

/** Validate both ancestry directions before a force operation can delete any destination. */
private fun overlappingFileOperation(source: File, destination: File): Boolean {
    val input = source.canonicalFile.toPath()
    val output = destination.canonicalFile.toPath()
    return input == output || input.startsWith(output) || source.isDirectory && output.startsWith(input)
}

/** `uu tree [dir] [depth]` — 目录树（默认深 3，最多 TREE_MAX 行）。 */
internal fun UuCommands.tree(args: List<String>, ctx: Ctx): Result {
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

/**
 * `uu du <path...> [-d N] [-n N]` — 递归统计占用。
 *
 * 不带旗标时保持老行为：总量 + 文件数，多路径时再加一行合计。
 * 带旗标才是「空间去哪儿了」的视图（WizTree 那类功能的 CLI 形态）：
 *
 * - `-d N`：把深度 1..N 的每个子目录按**子树总量**列出来，大的在前；
 * - `-n N`：列出最大的 N 个文件。
 *
 * 两者可以同时给。**为什么只走一趟**：先 `du` 一遍、再逐个 `du` 子目录的做法
 * 在每一层都要重走整棵树，`/sdcard` 上几十万文件时是 N 倍 I/O。这里子树大小
 * 在回溯时自然得到（`walk` 的返回值），顺便喂 top-N。
 *
 * **取消**：纯 Kotlin 长任务，**不走** `tryStartOperation`（那是 native 编解码器
 * 的槽位，`fmtKey` 语义是「同格式串行」）。注册一个置位钩子，遍历里每
 * [DU_PROGRESS_EVERY] 个文件查一次 —— 点进度行 → 确认 → 下一批就停。
 */
internal fun UuCommands.du(args: List<String>, ctx: Ctx): Result {
    rejectUnknownFlags(ctx, args, listOf("-d", "-n"))?.let { return it }
    val pa = splitFlags(args, listOf("-d", "-n"))
    missingValueError(ctx, pa)?.let { return it }
    if (pa.pos.isEmpty()) return needFile(Picker.FILE_OR_FOLDER)
    val depth = pa.values["-d"]?.toIntOrNull()?.coerceIn(1, DU_MAX_DEPTH) ?: 0
    val topN = pa.values["-n"]?.toIntOrNull()?.coerceIn(1, DU_MAX_TOP) ?: 0
    // 分解视图只对一个根有意义；多路径仍走「各自求和」。
    if ((depth > 0 || topN > 0) && pa.pos.size > 1) return Result(usageFor("du"), 2)
    if (depth == 0 && topN == 0) return duTotals(pa.pos, ctx)

    // `normalize()` 是纯词法操作（不做 I/O）：`File(cwd, ".")` 的 absolutePath
    // 会留着末尾的 `/.`，不归一化的话下面 `rel()` 的 removePrefix 永远切不掉。
    val root = UuText.resolve(ctx.cwd, pa.pos[0]).absoluteFile.normalize()
    if (!root.exists()) return Result(UuText.notFound(ctx.str, pa.pos[0]), 1)
    // 指向单个文件时没有「分解」可言，退回求和。
    if (!root.isDirectory) return duTotals(pa.pos, ctx)

    val cancelled = AtomicBoolean(false)
    ctx.registerCancel?.invoke { cancelled.set(true) }

    val dirBytes = HashMap<File, Long>()
    // 小顶堆：堆顶是当前候选里最小的，比它大就换掉 —— 内存只占 N 个。
    val top = java.util.PriorityQueue<Pair<Long, File>>(compareBy { it.first })
    var files = 0
    var bytes = 0L

    fun walk(d: File, lvl: Int): Long {
        if (cancelled.get()) return 0L
        val kids = d.listFiles() ?: return 0L
        var sum = 0L
        for (f in kids) {
            if (cancelled.get()) return sum
            if (f.isDirectory) {
                val sub = walk(f, lvl + 1)
                sum += sub
                // 只登记到深度上限；更深的目录仍要遍历（总量与 -n 都要它）。
                if (lvl + 1 <= depth) dirBytes[f] = sub
            } else {
                val sz = com.usefulunpacker.observedAttempt("UuCommands") { f.length() }.getOrDefault(0L)
                sum += sz
                bytes += sz
                files++
                if (topN > 0) {
                    if (top.size < topN) top.add(sz to f)
                    else if (sz > (top.peek()?.first ?: 0L)) { top.poll(); top.add(sz to f) }
                }
                if (ctx.progress != null && files % DU_PROGRESS_EVERY == 0) {
                    ctx.progress.invoke(ctx.text(R.string.cli_du_scanning, files.toString(), fmt(bytes)))
                }
            }
        }
        return sum
    }

    val total = walk(root, 0)
    // 遍历结束时把进度行定成最终数字（否则它停在最后一个整千数上）。
    ctx.progress?.invoke(ctx.text(R.string.cli_du_scanning, files.toString(), fmt(total)))
    if (cancelled.get()) return Result(UuText.extractCancelled(ctx.str), 1)

    fun pct(b: Long): String =
        if (total <= 0) "-" else "%.1f%%".format(b * 100.0 / total)

    fun rel(f: File): String =
        f.absolutePath.removePrefix(root.absolutePath).trimStart('/').ifEmpty { "." }

    val sb = StringBuilder()
    sb.append(root.absolutePath).append('\n')
    sb.append(ctx.text(R.string.cli_du_result, fmt(total), files.toString())).append('\n')

    if (dirBytes.isNotEmpty()) {
        val rows = dirBytes.entries.sortedByDescending { it.value }
        for ((i, e) in rows.withIndex()) {
            if (i >= DU_MAX_LINES) {
                sb.append(ctx.text(R.string.cli_tree_truncated, DU_MAX_LINES.toString())).append('\n')
                break
            }
            sb.append(fmt(e.value).padStart(9)).append("  ")
                .append(pct(e.value).padStart(6)).append("  ")
                // 尾随 `/` 是 Unix 惯例，也让「目录行」和「文件行」一眼分开 ——
                // 比再加两条分段标题省事，也不用再维护四份文案。
                .append(rel(e.key)).append('/').append('\n')
        }
    }
    if (top.isNotEmpty()) {
        sb.append('\n')
        for ((b, f) in top.sortedByDescending { it.first }) {
            sb.append(fmt(b).padStart(9)).append("  ").append(rel(f)).append('\n')
        }
    }
    return Result(sb.toString().trimEnd('\n'))
}

/** `uu du` 不带旗标的老行为：递归求和（多路径时再加一行合计）。 */
private fun UuCommands.duTotals(args: List<String>, ctx: Ctx): Result {
    fun sizeOf(f: File): Pair<Long, Int> {
        if (f.isFile) return f.length() to 1
        var bytes = 0L; var files = 0
        f.walkTopDown().forEach { if (it.isFile) { bytes += com.usefulunpacker.observedAttempt("UuCommands") { it.length() }.getOrDefault(0L); files++ } }
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
internal fun UuCommands.stat(args: List<String>, ctx: Ctx): Result {
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

/** `uu find [dir] <glob> [depth]` — 递归文件名匹配（默认 cwd、深 6、上限 200 条）。 */
internal fun UuCommands.find(args: List<String>, ctx: Ctx): Result {
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
