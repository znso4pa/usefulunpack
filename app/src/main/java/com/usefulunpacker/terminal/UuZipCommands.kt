package com.usefulunpacker

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import com.usefulunpacker.featureThread as thread

import com.usefulunpacker.UuCommands.Ctx
import com.usefulunpacker.UuCommands.Result

/**
 * `uu set <archive> <entry> <localfile> [-p pw]` — 替换归档内单个条目,
 * 输出 `名字-cn.ext` 副本（原包不动）。ZIP 走 zipModify（未动条目字节级
 * 保留）；其余可封包格式走整解 → 覆盖 → 重封。
 */
internal fun UuCommands.setEntry(args: List<String>, ctx: Ctx): Result {
    val pa = splitFlags(args, listOf("-p", "-f"))
    missingValueError(ctx, pa)?.let { return it }
    val (flags, _, pos) = Triple(pa.values, pa.bools, pa.pos)
    if (pos.size < 3) return Result(usageFor("set"), 2)
    val pw = flags["-p"] ?: ""
    val archive = UuText.resolve(ctx.cwd, pos[0])
    if (!archive.isFile) return Result(UuText.notFound(ctx.str, pos[0]), 1)
    val rel = sanitizeEntryPath(pos[1])
        ?: return Result(UuText.notFound(ctx.str, pos[1]), 1)
    val srcFile = UuText.resolve(ctx.cwd, pos[2])
    if (!srcFile.isFile) return Result(UuText.notFound(ctx.str, pos[2]), 1)
    val fmt = flags["-f"]?.let { key ->
        if (key !in COMPRESS_EXT.keys) return Result(UuText.packUnknownKey(ctx.str, key), 1)
        key
    } ?: detectFormat(archive) ?: detectFormatByMagic(archive)
        ?: return Result(UuText.extractBadFormat(ctx.str, "-"), 1)
    if (fmt == "rar") return Result(ctx.text(R.string.cli_set_unsupported, "RAR"), 1)

    val parent = archive.parentFile ?: ctx.cwd
    val act = ctx.activity

    // ZIP 快路径：zipModify replace（未动条目字节级保留）
    val isSplitZip = fmt == "zip" && (isVolumeFile(archive) == "zip" || isZipVolumeName(archive.name))
    if (fmt == "zip" && !isSplitZip) {
        val opH = ctx.reserveArchive("zip")
        try {
            if (!ctx.awaitArchive(opH)) return Result(UuText.extractCancelled(ctx.str), 1)
            val outF = uniqueFile(parent, "${archive.nameWithoutExtension}-cn.zip")
            val tmp = File(parent, "${archive.nameWithoutExtension}.set.zip")
            val ok = ZipCore.zipModify("", archive.path, tmp.path, "replace|$rel|${srcFile.path}", pw)
            if (!ok) {
                com.usefulunpacker.deleteOutputChecked(tmp)
                return Result(UuText.packFailed(ctx.str, outF.name), 1)
            }
            java.nio.file.Files.move(tmp.toPath(), outF.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            return Result(ctx.text(R.string.cli_set_done, rel, outF.name) + "\n" +
                ctx.text(R.string.cli_all_set))
        } catch (e: Exception) {
            return Result(UuText.failed(ctx.str, e.message ?: rel), 1)
        } finally {
            opH?.release()
        }
    }

    // 通用路径：整解 → 覆盖条目 → 重封（需要该格式支持封包）
    if (fmt !in COMPRESS_EXT.keys) return Result(UuText.packUnknownKey(ctx.str, fmt), 1)
    val staging = ctx.cacheDir?.let { File(it, "uu_set/${System.nanoTime()}") }
        ?: return Result(UuText.needsActivity(ctx.str), 2)
    val opH = ctx.reserveArchive(fmt)
    val cancelled = AtomicBoolean(false)
    try {
        if (!ctx.awaitArchive(opH)) return Result(UuText.extractCancelled(ctx.str), 1)
        var acc = extractAccessors(fmt)
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
        try {
            val o = extractByFormat(fmt, archive.path, staging.path, "", ctx.prefs
                ?: throw IllegalStateException("prefs required"), pw)
            if (cancelled.get()) return Result(UuText.extractCancelled(ctx.str), 1)
            if (!o.counts.ok) {
                return Result(act?.let { friendlyExtractError(it, o.error) } ?: (o.error ?: rel), 1)
            }
            val target = File(staging, rel)
            if (!target.isFile) return Result(ctx.text(R.string.cli_set_no_entry, rel), 1)
            target.parentFile?.mkdirs()
            srcFile.copyTo(target, overwrite = true)
            val outExt = archive.extension.ifEmpty { COMPRESS_EXT[fmt] ?: fmt }
            val outF = uniqueFile(parent, "${archive.nameWithoutExtension}-cn.$outExt")
            acc = compressAccessors(fmt)
            val level = ctx.prefs?.let {
                if (fmt == "zip") it.getInt("zip_level", 5) else it.getInt("generic_level", 6)
            } ?: 6
            // Mirror the source archive's encryption, exactly like the GUI
            // repack: a game's filter runs over every entry, so a plain
            // result would be unreadable by the game it was made for.
            val setEnc = if (fmt == "xp3") xp3RepackEnc(xp3SchemeToken("xp3", archive)) else ""
            val ok = compressDispatch(staging, outF, fmt, level, pw, ctx.prefs
                ?: throw IllegalStateException("prefs required"), null, setEnc)
            if (!ok) return Result(UuText.packFailed(ctx.str, outF.name), 1)
            return Result(ctx.text(R.string.cli_set_done, rel, outF.name) + "\n" +
                ctx.text(R.string.cli_all_set))
        } finally {
            done.set(true)
            poller?.join(600)
            com.usefulunpacker.deleteOutputChecked(staging, recursive = true)
        }
    } finally {
        opH?.release()
    }
}

/** ZIP 就地编辑公共段：在调度槽内解析产物名 → zipModify → 落成 -cn 副本。 */
private fun UuCommands.zipModifyCopy(
    archive: File, ops: String, pw: String, label: String, ctx: Ctx
): Result {
    if (detectFormat(archive) != "zip") {
        return Result(ctx.text(R.string.cli_set_unsupported, "non-ZIP"), 1)
    }
    if (isVolumeFile(archive) == "zip" || isZipVolumeName(archive.name)) {
        return Result(ctx.stringZipMulti(), 1)
    }
    val parent = archive.parentFile ?: ctx.cwd
    val opH = ctx.reserveArchive("zip")
    try {
        if (!ctx.awaitArchive(opH)) return Result(UuText.extractCancelled(ctx.str), 1)
        val outF = uniqueFile(parent, "${archive.nameWithoutExtension}-cn.zip")
        val tmp = File(parent, "${archive.nameWithoutExtension}.edit.zip")
        val ok = com.usefulunpacker.observedAttempt("UuCommands") { ZipCore.zipModify("", archive.path, tmp.path, ops, pw) }
            .getOrDefault(false)
        if (!ok) {
            com.usefulunpacker.deleteOutputChecked(tmp)
            return Result(UuText.packFailed(ctx.str, outF.name), 1)
        }
        java.nio.file.Files.move(tmp.toPath(), outF.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return Result(ctx.text(R.string.cli_set_done, label, outF.name) + "\n" +
            ctx.text(R.string.cli_all_set))
    } finally {
        opH?.release()
    }
}

/** `uu rmd <zip> <entry...> [-p pw]` — 删除 ZIP 条目（输出 -cn 副本）。 */
internal fun UuCommands.rmd(args: List<String>, ctx: Ctx): Result {
    val pa = splitFlags(args, listOf("-p"))
    missingValueError(ctx, pa)?.let { return it }
    if (pa.pos.size < 2) return Result(usageFor("rmd"), 2)
    val archive = UuText.resolve(ctx.cwd, pa.pos[0])
    if (!archive.isFile) return Result(UuText.notFound(ctx.str, pa.pos[0]), 1)
    val pw = pa.values["-p"]?.takeIf { it.isNotEmpty() } ?: ""
    // 条目通配：与 uu x 同一套"按条目 JSON 展开"（`uu rmd a.zip "*.tmp"`）
    val raw = pa.pos.drop(1)
    val (expanded, misses) = expandEntryGlobs(raw, "zip", archive, pw, ctx)
    if (raw.isNotEmpty() && expanded.isEmpty()) {
        return Result(ctx.text(R.string.cli_x_glob_none, misses.joinToString(" ")), 1)
    }
    if (expanded.any { it.contains('|') }) {
        return Result(ctx.text(R.string.zip_invalid_entry_name), 1)
    }
    val ops = expanded.joinToString("\n") { "delete|$it" }
    val r = zipModifyCopy(archive, ops, pw, "${expanded.size} entries", ctx)
    return if (misses.isEmpty()) r
    else Result(r.text + "\n" + ctx.text(R.string.cli_x_glob_miss, misses.joinToString(" ")), r.exitCode)
}

/** `uu add <zip> <local> [name] [-p pw]` — 向 ZIP 添加条目（输出 -cn 副本）。 */
internal fun UuCommands.add(args: List<String>, ctx: Ctx): Result {
    val pa = splitFlags(args, listOf("-p"))
    missingValueError(ctx, pa)?.let { return it }
    if (pa.pos.size < 2) return Result(usageFor("add"), 2)
    val archive = UuText.resolve(ctx.cwd, pa.pos[0])
    if (!archive.isFile) return Result(UuText.notFound(ctx.str, pa.pos[0]), 1)
    val local = UuText.resolve(ctx.cwd, pa.pos[1])
    if (!local.isFile) return Result(UuText.notFound(ctx.str, pa.pos[1]), 1)
    val rawName = pa.pos.getOrNull(2) ?: local.name
    val name = sanitizeEntryPath(rawName.replace('\\', '/').trim('/'))
        ?: return Result(ctx.text(R.string.zip_invalid_entry_name), 1)
    if (name.contains('|')) return Result(ctx.text(R.string.zip_invalid_entry_name), 1)
    // Rust 的 add 对已存在条目是静默跳过——先查重给出明确错误
    val json = listEntriesJsonFor(ctx, "zip", archive,
        pa.values["-p"]?.takeIf { it.isNotEmpty() } ?: "")
    if (json != null && parseEntries(json).any { it.path == name }) {
        return Result(ctx.text(R.string.cli_exists, name), 1)
    }
    val pw = pa.values["-p"]?.takeIf { it.isNotEmpty() } ?: ""
    return zipModifyCopy(archive, "add|$name|${local.path}", pw, name, ctx)
}

/** `uu diff <a> <b> [-p pw]` — 两个归档条目表差异（仅比 size/目录性,无校验和）。 */
internal fun UuCommands.diff(args: List<String>, ctx: Ctx): Result {
    val pa = splitFlags(args, listOf("-p"))
    missingValueError(ctx, pa)?.let { return it }
    if (pa.pos.size < 2) return Result(usageFor("diff"), 2)
    val a = UuText.resolve(ctx.cwd, pa.pos[0]); if (!a.isFile) return Result(UuText.notFound(ctx.str, pa.pos[0]), 1)
    val b = UuText.resolve(ctx.cwd, pa.pos[1]); if (!b.isFile) return Result(UuText.notFound(ctx.str, pa.pos[1]), 1)
    val pw = pa.values["-p"]?.takeIf { it.isNotEmpty() } ?: ""
    fun entriesOf(f: File): List<ArchiveEntry>? {
        val fmt = detectFormat(f) ?: detectFormatByMagic(f) ?: return null
        val json = listEntriesJsonFor(ctx, fmt, f, pw) ?: return null
        return parseEntries(json)
    }
    val ea = entriesOf(a) ?: return Result(UuText.listFailed(ctx.str, a.name), 1)
    val eb = entriesOf(b) ?: return Result(UuText.listFailed(ctx.str, b.name), 1)
    val (onlyA, onlyB, changed, ma, mb) = diffOf(ea, eb)
    val sb = StringBuilder()
    sb.append(ctx.text(R.string.cli_diff_title, a.name, b.name,
        onlyB.size.toString(), onlyA.size.toString(), changed.size.toString())).append('\n')
    for (p in onlyA.sorted()) sb.append("  - ").append(p).append('\n')
    for (p in onlyB.sorted()) sb.append("  + ").append(p).append('\n')
    for (p in changed.sorted()) {
        sb.append("  ~ ").append(p)
            .append("  ").append(fmt(ma[p]!!.size)).append(" → ").append(fmt(mb[p]!!.size)).append('\n')
    }
    return Result(sb.toString().trimEnd('\n'))
}
