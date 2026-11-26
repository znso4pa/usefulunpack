package com.usefulunpacker

import java.io.File
import com.usefulunpacker.TerminalPanel.ExecCtx
import com.usefulunpacker.TerminalPanel.UutRun
import com.usefulunpacker.TerminalPanel.Companion.SCRIPT_MAX_BYTES
import com.usefulunpacker.TerminalPanel.Companion.PROMPT
import com.usefulunpacker.TerminalPanel.Companion.SCRIPT_MAX_DEPTH

/**
 * `uu run [-k] <file> [脚本参数...]`：UUT 脚本——解析 AST 后逐句执行
 * （行内管道/重定向可用）。路径**之后**的参数原样传给脚本（`$1`…`$n`、
 * `$argc`、`$args`），所以带 `-` 的实参不会被误当标志。
 */
internal fun TerminalPanel.runScript(args: List<String>, ec: ExecCtx, depth: Int): Int {
    val trace = AppDiagnostics.begin("run_uut")
    return AppDiagnostics.withTrace(trace) {
        val code = runScriptBody(args, ec, depth)
        if (code == 0) trace.success() else trace.fail(FailureCode.COMMAND, "UUT exit=$code")
        code
    }
}

internal fun TerminalPanel.runScriptBody(args: List<String>, ec: ExecCtx, depth: Int): Int {
    if (depth >= SCRIPT_MAX_DEPTH) {
        ec.emit(str(R.string.cli_run_stopped, "0", "1", "depth>${SCRIPT_MAX_DEPTH - 1}"))
        return 1
    }
    var keepGoing = false
    var i = 0
    while (i < args.size && args[i].startsWith("-")) {
        when (args[i]) {
            "-k" -> keepGoing = true
            else -> {
                ec.emit(str(R.string.cli_unknown_flag, args[i]))
                return 2
            }
        }
        i++
    }
    val path = args.getOrNull(i)
    if (path == null) {
        ec.emit("uu run [-k] <file.uut> [args...]")
        return 2
    }
    val scriptArgs = args.drop(i + 1)
    val f = UuText.resolve(ec.s.cwd, path)
    if (!f.isFile) {
        ec.emit(str(R.string.cli_not_found, path))
        return 1
    }
    val bytes = com.usefulunpacker.observedAttempt("TerminalPanel") { readPrefix(f, SCRIPT_MAX_BYTES) }.getOrNull() ?: return 1
    val enc = detectBestEncoding(bytes)
    val text = if (enc != null) decodeTextStrict(bytes, enc) else String(bytes, Charsets.UTF_8)
    val parseTrace = AppDiagnostics.begin("parse_uut")
    val ast = try {
        UutParser.parse(text).also { parseTrace.success() }
    } catch (e: UutParseException) {
        parseTrace.fail(FailureCode.INVALID_RESULT, "UUT_PARSE_ERROR line=${e.line}")
        ec.emit(str(R.string.cli_uut_parse, e.reason, e.line.toString()))
        return 2
    }
    val vars = HashMap<String, String>()
    vars["0"] = path
    scriptArgs.forEachIndexed { k, a -> vars[(k + 1).toString()] = a }
    vars["argc"] = scriptArgs.size.toString()
    vars["args"] = scriptArgs.joinToString(" ")
    val r = execUut(ast, vars, ec, depth, keepGoing)
    ec.emit(
        if (r.stoppedLine > 0) str(R.string.cli_run_stopped, r.ran.toString(), r.exit.toString(), r.stoppedLine.toString())
        else str(R.string.cli_run_summary, r.ran.toString(), r.exit.toString())
    )
    return r.exit
}

/** 求值一个 UUT 条件（变量比较 / 文件测试）；null = 数值比较拿到非整数。 */
internal fun TerminalPanel.evalCond(cond: UutCond, vars: Map<String, String>, cwd: File): Boolean? = when (cond) {
    is UutCond.VarCmp -> UutParser.compareValues(vars[cond.name] ?: "", cond.op, cond.value)
    is UutCond.Exists -> {
        val p = UutParser.expandVars(cond.rawPath, vars)
        // 含通配符时按"有没有任何命中"判断：`if exist *.zip` 是脚本里最常见
        // 的写法（展开逻辑与 for 复用同一套；未命中会退回字面模式，这里滤掉）
        val exists = if (CliGlob.hasWildcards(p)) {
            UuCommands.expandGlobs(listOf("exist") + listOf(p), cwd)
                .drop(1).any { !it.contains('*') && !it.contains('?') }
        } else UuText.resolve(cwd, p).exists()
        if (cond.negate) !exists else exists
    }
}

/** 条件求值失败（数值比较的非整数操作数）时的报错文本。 */
internal fun TerminalPanel.badCmp(cond: UutCond): String = when (cond) {
    is UutCond.VarCmp -> str(R.string.cli_uut_bad_cmp, cond.name, cond.op, cond.value)
    is UutCond.Exists -> str(R.string.cli_uut_bad_cmp, "exist", "=", cond.rawPath)
}

/** 每条命令执行完记下退出码：`$?` / `$errorlevel` 都能取（bat 的 errorlevel 习惯）。 */
internal fun TerminalPanel.noteExit(vars: MutableMap<String, String>, code: Int) {
    vars["?"] = code.toString()
    vars["errorlevel"] = code.toString()
}

/** UUT 执行统计。[returned] = 脚本里执行了 `return`（只结束当前这一层）。 */
internal fun TerminalPanel.execUut(
    stmts: List<UutStmt>, vars: HashMap<String, String>, ec: ExecCtx, depth: Int, keepGoing: Boolean
): UutRun {
    var ran = 0
    var exit = 0
    for (st in stmts) {
        when (st) {
            is UutStmt.Cmd -> {
                // 逐段展开 / 逐段执行（见 UutParser.splitChain 的说明）：
                // 这样 `a ; echo rc=$?` 的 $? 看到的是 a 的退出码。
                var lastExit = 0
                var anyRan = false
                for ((piece, joiner) in UutParser.splitChain(st.raw)) {
                    when (joiner) {
                        "&&" -> if (lastExit != 0) continue   // 前段失败：短路
                        "||" -> if (lastExit == 0) continue   // 前段成功：短路
                    }
                    val expanded = UutParser.expandVars(piece, vars)
                    if (ec.echo) ec.emit(PROMPT + expanded)
                    val toks = tokenizeLine(expanded) ?: run {
                        ec.emit(str(R.string.cli_unclosed_quote, "'"))
                        return UutRun(ran + 1, 1, st.line)
                    }
                    if (toks.isEmpty()) continue
                    // 行内 break / continue / return：是 UUT 语句而不是 shell 命令，
                    // 必须在白名单检查之前拦截（否则 if x then break 直接被拒）
                    when (toks[0]) {
                        "break" -> return UutRun(ran, exit, 0, broke = true)
                        "continue" -> return UutRun(ran, exit, 0, continued = true)
                        "return" -> {
                            val code = UutParser.expandVars(toks.drop(1).joinToString(" "), vars)
                                .trim().toIntOrNull() ?: 0
                            return UutRun(ran, code, 0, returned = true)
                        }
                    }
                    if (!UutParser.commandAllowed(toks[0])) {
                        ec.emit(str(R.string.cli_uut_cmd_not_allowed, toks[0]))
                        if (!keepGoing) return UutRun(ran + 1, 1, st.line)
                        anyRan = true; lastExit = 1; exit = 1
                        continue
                    }
                    lastExit = runTokens(expanded, toks, ec.withExitHook { noteExit(vars, it) }, depth + 1)
                    noteExit(vars, lastExit)
                    anyRan = true
                    // 注意：**不在这里**判错退出 —— `a ; b` 的语义就是 b 必须跑
                    //（停下要等整行结束；否则 `uu l nope.zip ; echo rc=$?` 的
                    // 第二段永远执行不到，退出码也读不出来）
                }
                exit = lastExit
                if (anyRan) ran++
                if (exit != 0 && !keepGoing) return UutRun(ran, exit, st.line)
            }
            is UutStmt.Set -> {
                val expanded = UutParser.expandVars(st.rawValue, vars).trim()
                val cap = UutParser.captureInner(expanded)
                vars[st.name] = when {
                    cap != null -> captureCmd(cap, vars, ec, depth)
                    // 算术赋值：`set n = $n + 1`（展开之后才判断，见 evalArith）
                    else -> try {
                        UutParser.evalArith(expanded) ?: expanded
                    } catch (e: ArithmeticException) {
                        ec.emit(UuText.failed(ctxStr(), e.message ?: "arithmetic error"))
                        return UutRun(ran + 1, 1, st.line)
                    }
                }
            }
            is UutStmt.IfInline -> {
                val okInline = evalCond(st.cond, vars, ec.s.cwd)
                if (okInline == null) {
                    ec.emit(badCmp(st.cond))
                    return UutRun(ran + 1, 1, st.line)
                }
                if (okInline) {
                    val expanded = UutParser.expandVars(st.rawCmd, vars)
                    if (ec.echo) ec.emit(PROMPT + expanded)
                    val toks = tokenizeLine(expanded) ?: run {
                        ec.emit(str(R.string.cli_unclosed_quote, "'"))
                        return UutRun(ran + 1, 1, st.line)
                    }
                    if (toks.isNotEmpty()) when (toks[0]) {
                        // 行内 break / continue / return（与 Cmd 片段循环同一套
                        // 拦截：漏了这边的话 `if x then break` 会落进 shell 兜底，
                        // 静默返回 0、循环跑满 —— 真机实测）
                        "break" -> return UutRun(ran, exit, 0, broke = true)
                        "continue" -> return UutRun(ran, exit, 0, continued = true)
                        "return" -> {
                            val code = UutParser.expandVars(toks.drop(1).joinToString(" "), vars)
                                .trim().toIntOrNull() ?: 0
                            return UutRun(ran, code, 0, returned = true)
                        }
                    }
                    if (toks.isNotEmpty() && !UutParser.commandAllowed(toks[0])) {
                        ec.emit(str(R.string.cli_uut_cmd_not_allowed, toks[0]))
                        if (!keepGoing) return UutRun(ran + 1, 1, st.line)
                        ran++; exit = 1
                    } else if (toks.isNotEmpty()) {
                        exit = runTokens(expanded, toks, ec.withExitHook { noteExit(vars, it) }, depth + 1)
                        noteExit(vars, exit)
                        ran++
                        if (exit != 0 && !keepGoing) return UutRun(ran, exit, st.line)
                    }
                }
            }
            is UutStmt.For -> {
                val patternsRaw = UutParser.expandVars(st.rawPatterns, vars).trim()
                // 单个 `$(命令)` = 按行迭代命令输出（脚本最常用的"遍历结果"写法；
                // 捕获先于分词，否则 `$(` 会被空格切碎）
                val capItems = UutParser.captureInner(patternsRaw)?.let { cap ->
                    captureCmd(cap, vars, ec, depth).split('\n')
                        .map { it.trim() }.filter { it.isNotEmpty() }
                }
                if (capItems != null) {
                    if (capItems.isEmpty()) continue
                    for (item in capItems) {
                        vars[st.varName] = item
                        val b = execUut(st.body, vars, ec, depth, keepGoing)
                        ran += b.ran; exit = b.exit
                        if (b.returned || b.stoppedLine > 0) {
                            return UutRun(ran, exit, b.stoppedLine, b.returned, b.broke, b.continued)
                        }
                        if (b.broke) break
                        if (b.continued) continue
                        if (b.exit != 0 && !keepGoing) return UutRun(ran, exit, st.line)
                    }
                    continue
                }
                val patterns = patternsRaw.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (patterns.isEmpty()) continue
                // 单个 `1..N` = 计数循环（纯数字列表，不做通配展开）
                val range = UutParser.rangeValues(patterns[0])
                if (range != null) {
                    if (range.isEmpty()) continue      // 超上限保护：什么都不做
                    for (n in range) {
                        vars[st.varName] = n
                        val b = execUut(st.body, vars, ec, depth, keepGoing)
                        ran += b.ran; exit = b.exit
                        if (b.returned || b.stoppedLine > 0) {
                            return UutRun(ran, exit, b.stoppedLine, b.returned, b.broke, b.continued)
                        }
                        if (b.broke) break                      // 计数循环同样吃 break
                        if (b.continued) continue
                        if (b.exit != 0 && !keepGoing) return UutRun(ran, exit, st.line)
                    }
                    continue
                }
                // 复用通配展开。expandGlobs 约定 token0 是命令名（不展开），
                // 这里没有命令，补一个占位首 token 再丢掉，否则 `for a in *.txt`
                // 永远匹配不到（实测踩过：报"没有匹配"且循环体整个不跑）。
                val files = UuCommands.expandGlobs(listOf("for") + patterns, ec.s.cwd)
                    .drop(1)
                    .filterNot { it.contains('*') || it.contains('?') }
                if (files.isEmpty()) {
                    ec.emit(str(R.string.cli_find_none, patterns.joinToString(" ")))
                    continue
                }
                for (f in files) {
                    vars[st.varName] = f
                    val sub = execUut(st.body, vars, ec, depth, keepGoing)
                    ran += sub.ran
                    exit = sub.exit
                    if (sub.returned || sub.stoppedLine > 0) {
                        return UutRun(ran, exit, sub.stoppedLine, sub.returned, sub.broke, sub.continued)
                    }
                    if (sub.broke) break
                    if (sub.continued) continue
                    if (sub.exit != 0 && !keepGoing) return UutRun(ran, exit, st.line)
                }
            }
            is UutStmt.While -> {
                var iter = 0
                while (true) {
                    if (iter++ >= UutParser.LOOP_MAX) {
                        // 死循环在 App 里等于"终端卡住"，到上限就停并说清楚
                        ec.emit(str(R.string.cli_uut_loop_max, UutParser.LOOP_MAX.toString()))
                        return UutRun(ran, 1, st.line)
                    }
                    val okWhile = evalCond(st.cond, vars, ec.s.cwd)
                    if (okWhile == null) {
                        ec.emit(badCmp(st.cond))
                        return UutRun(ran, 1, st.line)
                    }
                    if (!okWhile) break
                    val sub = execUut(st.body, vars, ec, depth, keepGoing)
                    ran += sub.ran
                    exit = sub.exit
                    if (sub.returned || sub.stoppedLine > 0) {
                        return UutRun(ran, exit, sub.stoppedLine, sub.returned, sub.broke, sub.continued)
                    }
                    if (sub.broke) break
                    if (sub.continued) continue
                    if (sub.exit != 0 && !keepGoing) return UutRun(ran, exit, st.line)
                }
            }
            is UutStmt.Break -> return UutRun(ran, exit, 0, broke = true)
            is UutStmt.Continue -> return UutRun(ran, exit, 0, continued = true)
            is UutStmt.If -> {
                val okIf = evalCond(st.cond, vars, ec.s.cwd)
                if (okIf == null) {
                    ec.emit(badCmp(st.cond))
                    return UutRun(ran, 1, st.line)
                }
                val body = if (okIf) st.thenBody else st.elseBody
                // 空分支是合法的（`if x` … `end` 什么都不做）
                if (body.isNotEmpty()) {
                    val sub = execUut(body, vars, ec, depth, keepGoing)
                    ran += sub.ran
                    exit = sub.exit
                    if (sub.returned || sub.stoppedLine > 0 || sub.broke || sub.continued) {
                        return UutRun(ran, exit, sub.stoppedLine, sub.returned, sub.broke, sub.continued)
                    }
                    if (sub.exit != 0 && !keepGoing) return UutRun(ran, exit, st.line)
                }
            }
            is UutStmt.Return -> {
                // return 结束**本层**脚本：嵌套 `uu run` 只结束它自己，
                // 退出码成为那条 `uu run` 命令的退出码
                val code = UutParser.expandVars(st.rawCode, vars).trim().toIntOrNull() ?: 0
                return UutRun(ran, code, 0, returned = true)
            }
        }
    }
    return UutRun(ran, exit, 0)
}

/** `$(...)` 捕获：跑一条白名单命令，收集输出文本（不碰视图）。 */
internal fun TerminalPanel.captureCmd(inner: String, vars: MutableMap<String, String>, ec: ExecCtx, depth: Int): String {
    val expanded = UutParser.expandVars(inner, vars)
    val toks = tokenizeLine(expanded) ?: return ""
    if (toks.isEmpty() || !UutParser.commandAllowed(toks[0])) return ""
    val sb = StringBuilder()
    val capEc = this.ExecCtx(
        ec.s, null, -1, sink = { sb.append(it).append('\n') },
        onExit = { noteExit(vars, it) },
    )
    runTokens(expanded, toks, capEc, depth + 1)
    return sb.toString().trim()
}
