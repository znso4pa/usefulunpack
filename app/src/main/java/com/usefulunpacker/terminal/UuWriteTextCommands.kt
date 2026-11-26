package com.usefulunpacker

import java.io.File

import com.usefulunpacker.UuCommands.Ctx
import com.usefulunpacker.UuCommands.Result
import com.usefulunpacker.UuCommands.SrcSpec

// ─── enc / mvdec / rmd / add / find / diff / hex ─────────────────────

/** 编码别名归一（与 TEXT_ENCODINGS 的四档对齐）。 */
/** The one encoding-name resolver: `uu cat -e` and `uu enc` both use it, so
 *  the accepted set and the error message can never disagree. */
internal fun UuCommands.normalizeEncoding(name: String): String? = when (name.uppercase()) {
    "UTF-8", "UTF8" -> "UTF-8"
    "UTF-16", "UTF16", "UTF-16LE", "UTF16LE" -> "UTF-16"
    "SJIS", "SHIFT-JIS", "SHIFT_JIS", "SHIFTJIS", "CP932" -> "SHIFT-JIS"
    "GBK", "CP936" -> "GBK"
    else -> null
}

private fun UuCommands.encShort(enc: String) = when (enc) {
    "UTF-8" -> "utf8"; "UTF-16" -> "utf16"; "SHIFT-JIS" -> "sjis"; "GBK" -> "gbk"; else -> "out"
}

/** `uu enc <file> <from|auto> <to> [out]` — 文本编码转换，BOM 按源保真。 */
internal fun UuCommands.enc(args: List<String>, ctx: Ctx): Result {
    rejectUnknownFlags(ctx, args)?.let { return it }
    if (args.size < 3) return Result(usageFor("enc"), 2)
    val f = UuText.resolve(ctx.cwd, args[0])
    if (!f.isFile) return Result(UuText.notFound(ctx.str, args[0]), 1)
    val to = normalizeEncoding(args[2])
        ?: return Result(ctx.text(R.string.cli_enc_bad_encoding, args[2]), 1)
    val from = if (args[1].equals("auto", true)) null else normalizeEncoding(args[1])
        ?: return Result(ctx.text(R.string.cli_enc_bad_encoding, args[1]), 1)
    if (f.length() > ENC_MAX_BYTES) {
        return Result(ctx.text(R.string.cli_enc_too_big, fmt(f.length())), 1)
    }
    val bytes = com.usefulunpacker.observedAttempt("UuCommands") { f.readBytes() }.getOrNull()
        ?: return Result(UuText.failed(ctx.str, f.name), 1)
    // Deliberately NOT `textEncodingOf`: `uu enc` is an explicit conversion
    // request, so a file that merely "looks binary" still gets converted
    // when the user names the source encoding with `from`.
    val srcEnc = from ?: detectBestEncoding(bytes)
        ?: return Result(ctx.text(R.string.cli_enc_no_detect, f.name), 1)
    val text = decodeTextStrict(bytes, srcEnc)
    val out = args.getOrNull(3)?.let { UuText.resolve(ctx.cwd, it) }
        ?: uniqueFile(f.parentFile ?: ctx.cwd,
            "${f.nameWithoutExtension}-${encShort(to)}.${f.extension.ifEmpty { "txt" }}")
    if (out.exists()) return Result(ctx.text(R.string.cli_exists, out.path), 1)
    // BOM 保真：源有 BOM 且目标支持（UTF-8/UTF-16）时保留
    val encoded = try {
        LineEditEncoding.forSnapshot(srcEnc, bytes).verifySource(bytes)
        LineEditEncoding.forSnapshot(to, bytes).encode(text)
    } catch (_: java.nio.charset.CharacterCodingException) {
        return Result(ctx.text(R.string.line_edit_unmappable), 1)
    }
    writeOutputAtomically(out, replaceExisting = false) { it.writeBytes(encoded); true }
    return Result(ctx.text(R.string.cli_enc_done, f.name, out.name, srcEnc, to))
}

/**
 * `uu sed <from> <to> <file...> [-i] [--bytes]` — 替换文件内容。
 *
 * 不给 `-i` 就把结果写到 stdout（终端本来就有重定向，`uu sed a b f > g` 即可）；
 * `-i` 才原地改，而且**必须原子** —— 先写同目录临时文件再 rename。中途中断
 * （取消 / 被杀）只留下一个临时文件，源文件不受影响；直接就地截断写的话，
 * 中断会毁掉源文件，那比失败严重得多。
 *
 * 文本模式走全项目同一套编码判定（[textEncodingOf]）并**按原编码写回** ——
 * 不把 SJIS 的脚本偷偷转成 UTF-8（那会让游戏读不了）。`--bytes` 把两个参数当
 * 十六进制字节串（`uu sed --bytes DEADBEEF CAFEBABE -i f.bin`），纯二进制补丁用；
 * 它**必须配 `-i`**：二进制结果没法可靠地穿过终端输出区（String 转换会把 >=0x80
 * 的字节变成 U+FFFD，`uu b64` 的解码分支为同一件事专门留了注释）。
 *
 * **归档内条目不在本命令范围**：改条目要重写归档（偏移全变），那是 `uu set` 的活。
 * 组合写法：`uu cat a.xp3 s.tjs > t.tjs` → `uu sed -i 旧 新 t.tjs` → `uu set a.xp3 s.tjs t.tjs`。
 */
internal fun UuCommands.sed(args: List<String>, ctx: Ctx): Result {
    rejectUnknownFlags(ctx, args, listOf("-i", "--bytes"))?.let { return it }
    val pa = splitFlags(args, emptyList(), listOf("-i", "--bytes"))
    if (pa.pos.size < 3) return Result(usageFor("sed"), 2)
    val fromArg = pa.pos[0]
    val toArg = pa.pos[1]
    val specs = pa.pos.drop(2)
    val inPlace = "-i" in pa.bools
    val asBytes = "--bytes" in pa.bools
    if (asBytes && !inPlace) return Result(ctx.text(R.string.cli_sed_bytes_needs_i), 2)
    if (fromArg.isEmpty()) return Result(ctx.text(R.string.cli_sed_empty_from), 2)

    val fromBytes: ByteArray
    val toBytes: ByteArray
    if (asBytes) {
        fromBytes = hexToBytes(fromArg) ?: return Result(ctx.text(R.string.cli_sed_bad_hex, fromArg), 2)
        toBytes = hexToBytes(toArg) ?: return Result(ctx.text(R.string.cli_sed_bad_hex, toArg), 2)
    } else {
        fromBytes = ByteArray(0); toBytes = ByteArray(0)
    }

    val multi = specs.size > 1
    val out = StringBuilder()
    var ok = 0
    var fail = 0
    for (spec in specs) {
        var temp: File? = null
        try {
            val src = when (val r = resolveSource(spec, ctx)) {
                is SrcSpec.Fail -> { fail++; out.append(r.result.text).append('\n'); continue }
                is SrcSpec.Path -> r.f.takeIf { it.isFile }
                is SrcSpec.Fd -> { temp = r.temp; r.f }
            }
            if (src == null) {
                fail++; out.append(UuText.notFound(ctx.str, spec)).append('\n'); continue
            }
            // fN 的区间条目是个只读视图（临时 carve 出来的），"原地改"它没有意义
            if (inPlace && temp != null) {
                fail++; out.append(ctx.text(R.string.cli_sed_inplace_needs_file, spec)).append('\n'); continue
            }
            if (src.length() > SED_MAX_BYTES) {
                fail++
                out.append(ctx.text(R.string.cli_sed_too_big, src.name, fmt(SED_MAX_BYTES))).append('\n')
                continue
            }
            val raw = com.usefulunpacker.observedAttempt("UuCommands") { src.readBytes() }.getOrNull()
            if (raw == null) {
                fail++; out.append(UuText.failed(ctx.str, src.name)).append('\n'); continue
            }

            var textOut: String? = null
            val replaced: ByteArray
            val hits: Int
            if (asBytes) {
                val pair = replaceAllBytes(raw, fromBytes, toBytes)
                replaced = pair.first; hits = pair.second
            } else {
                val enc = textEncodingOf(raw)
                if (enc == null) {
                    fail++; out.append(ctx.text(R.string.cli_sed_binary, src.name)).append('\n'); continue
                }
                val text = decodeTextStrict(raw, enc)
                hits = countOccurrences(text, fromArg)
                if (hits == 0) {
                    textOut = text
                    replaced = raw
                } else {
                    val newText = text.replace(fromArg, toArg)
                    // 原地改的前提是「这次编解码本身是保真的」：解码再编码必须逐字节
                    // 等于原文件。猜错编码时（UTF-16BE 的 CJK 被认成 SJIS 之类）解码会
                    // 引入 U+FFFD，编回去就不是原字节 —— 那等于悄悄改了一堆没人要求改的
                    // 字节。宁可拒绝（`--bytes` 是逃生口），也不产出这种文件。
                    // 只查 `-i`：写 stdout 时原文件没被碰，用户能先看再决定用不用。
                    if (inPlace) {
                        val encoding = LineEditEncoding.forSnapshot(enc, raw)
                        val faithful = try { encoding.encode(text).contentEquals(raw) }
                            catch (_: java.nio.charset.CharacterCodingException) { false }
                        if (!faithful) {
                            fail++
                            out.append(ctx.text(R.string.cli_sed_lossy, src.name, enc)).append('\n')
                            continue
                        }
                        replaced = try { encoding.encode(newText) } catch (_: java.nio.charset.CharacterCodingException) {
                            fail++
                            out.append(ctx.text(R.string.line_edit_unmappable)).append('\n')
                            continue
                        }
                    } else replaced = raw
                    textOut = newText
                }
            }

            if (inPlace) {
                if (hits > 0 && !atomicWrite(src, replaced)) {
                    fail++; out.append(UuText.failed(ctx.str, src.name)).append('\n'); continue
                }
            } else {
                if (multi) out.append("── ").append(spec).append('\n')
                val body = textOut ?: ""
                out.append(body)
                // 多文件时下一个 `── name` 必须另起一行：文件不以换行收尾时
                // 两段输出会粘成一行（脚本文件很常见，这不是边角情况）
                if (multi && !body.endsWith("\n")) out.append('\n')
            }
            ok++
            if (inPlace) {
                out.append(ctx.text(
                    if (hits > 0) R.string.cli_sed_done else R.string.cli_sed_none,
                    src.name, hits.toString()
                )).append('\n')
            }
        } finally {
            temp?.let { com.usefulunpacker.deleteOutputChecked(it, recursive = true) }
        }
    }
    if (multi) out.append(ctx.text(R.string.cli_sed_summary, ok.toString(), fail.toString())).append('\n')
    return Result(out.toString().trimEnd('\n'), if (fail > 0) 1 else 0)
}

/** 原子替换文件内容：同目录临时文件 + rename。中断时源文件保持原样。 */
private fun UuCommands.atomicWrite(target: File, bytes: ByteArray): Boolean {
    val dir = target.parentFile ?: ctxParent(target)
    val tmp = File(dir, ".${target.name}.${System.nanoTime()}.uu-tmp")
    return try {
        tmp.writeBytes(bytes)
        java.nio.file.Files.move(
            tmp.toPath(), target.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
        true
    } catch (failure: Exception) {
        AppDiagnostics.failure("atomic_write", failure)
        com.usefulunpacker.observedAttempt("UuCommands") { com.usefulunpacker.deleteOutputChecked(tmp) }   // 失败/中断不留临时文件
        false
    }
}

internal fun UuCommands.ctxParent(f: File): File = f.absoluteFile.parentFile ?: File(".")

/** `DEADBEEF` / `de ad be ef` / `0xDEADBEEF` → 字节数组；非法（空 / 奇数长 / 非十六进制）返回 null。 */
private fun UuCommands.hexToBytes(s: String): ByteArray? {
    var hex = s.filter { !it.isWhitespace() }
    if (hex.startsWith("0x", true)) hex = hex.drop(2)
    if (hex.isEmpty() || hex.length % 2 != 0) return null
    if (!hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
    return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** 全部出现处替换（**字面**匹配，不是正则）。返回 (新字节, 命中数)。 */
private fun UuCommands.replaceAllBytes(data: ByteArray, from: ByteArray, to: ByteArray): Pair<ByteArray, Int> {
    if (from.isEmpty()) return data to 0
    val out = java.io.ByteArrayOutputStream(data.size)
    var i = 0
    var hits = 0
    while (i < data.size) {
        var match = i + from.size <= data.size
        if (match) {
            for (j in from.indices) {
                if (data[i + j] != from[j]) { match = false; break }
            }
        }
        if (match) { out.write(to); hits++; i += from.size }
        else { out.write(data[i].toInt()); i++ }
    }
    return out.toByteArray() to hits
}

/** [text] 里 [from] 出现的次数（字面匹配）。 */
private fun UuCommands.countOccurrences(text: String, from: String): Int {
    if (from.isEmpty()) return 0
    var n = 0
    var i = text.indexOf(from)
    while (i >= 0) { n++; i = text.indexOf(from, i + from.length) }
    return n
}
