package com.usefulunpacker

import android.content.SharedPreferences
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.CancellationException

private const val MCP_UUT_MAX_DEPTH = 5

internal class McpUutState(
    var cwd: File,
    val vars: HashMap<String, String> = HashMap(),
    var returned: Boolean = false,
)

internal class McpArchiveWorker(
    private val workspace: McpWorkspace,
    private val prefs: SharedPreferences? = null,
    private val str: StrFn = { id, args -> "!str:$id" + args.joinToString("") { "[$it]" } },
) {
    private val tokenizer = CliTokenizer()
    private val sessionFds = FdTable()

    fun execute(task: McpTask, command: McpCommand) {
        UuText.withWorkspace(workspace) {
            val state = (task.uutState as? McpUutState)
                ?: McpUutState(workspace.root).also { task.uutState = it }
            if (command.scriptArgs.isNotEmpty() && "argc" !in state.vars) {
                command.scriptArgs.forEachIndexed { idx, arg -> state.vars[(idx + 1).toString()] = arg }
                state.vars["argc"] = command.scriptArgs.size.toString()
                state.vars["args"] = command.scriptArgs.joinToString(" ")
            }
            if (state.returned) return@withWorkspace

            when {
                command.action != null -> {
                    executeStaged(task, command, state.cwd)
                    noteExit(state.vars, 0)
                    task.recordExitCode(0)
                }
                command.uutStmt != null -> {
                    val r = execUutList(task, listOf(command.uutStmt), state, depth = 0, keepGoing = command.keepGoing)
                    task.recordExitCode(r.exit)
                    if (r.returned) {
                        state.returned = true
                        if (r.exit != 0) {
                            throw McpInputException(r.error.ifEmpty { "Script returned exit code ${r.exit}" })
                        }
                    } else if (r.exit != 0 && !command.keepGoing) {
                        val line = if (r.stoppedLine > 0) r.stoppedLine else stmtLine(command.uutStmt)
                        throw McpInputException(r.error.ifEmpty { "Line $line: command failed (exit ${r.exit})" })
                    }
                }
                else -> {
                    val out = runUuCommand(task, command.argv, state, depth = 0)
                    noteExit(state.vars, out.exitCode)
                    task.recordExitCode(out.exitCode)
                    if (out.text.isNotEmpty()) task.appendStdout(out.text)
                    if (out.exitCode != 0) {
                        throw McpInputException(out.text.ifEmpty { "uu ${command.name} failed (exit ${out.exitCode})" })
                    }
                }
            }
        }
    }

    private fun executeStaged(task: McpTask, command: McpCommand, cwd: File) {
        val action = checkNotNull(command.action)
        val source = workspace.resolveFrom(cwd, command.source)
        val output = workspace.resolveFrom(cwd, command.output)
        val parent = output.parentFile ?: throw McpInputException("Output has no parent directory")
        if (!parent.isDirectory) throw McpInputException("Create the output's parent directory first")
        if (source == output || output.toPath().startsWith(source.toPath())) {
            throw McpInputException("The output must be outside the source")
        }
        if (output.exists() && (!command.overwrite || !output.isFile)) {
            throw McpInputException("The output already exists")
        }

        // Use the final filename for native formats whose keys depend on that name,
        // except sidecar-keyed pack formats (int, xp3) that inspect the output's parent directory.
        val staging = Files.createTempDirectory(parent.toPath(), ".uu-mcp-").toFile()
        val packFormat = if (action == McpCommand.Action.PACK) {
            resolveMcpPackFormat(output.name, command.format)
        } else ""
        val staged = if (packFormat in setOf("int", "xp3")) {
            File(parent, ".uu-mcp-${staging.name}-${output.name}")
        } else {
            File(staging, output.name)
        }
        try {
            when (action) {
                McpCommand.Action.EXTRACT -> extract(task, command, source, staged)
                McpCommand.Action.PACK -> pack(task, command, source, staged, packFormat)
                McpCommand.Action.COPY -> {
                    if (!source.isFile) throw McpInputException("Copy requires a regular source file")
                    source.inputStream().use { input ->
                        staged.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                task.checkCancelled()
                                val count = input.read(buffer)
                                if (count < 0) break
                                out.write(buffer, 0, count)
                            }
                        }
                    }
                }
            }
            val publishedRel = workspace.relativePath(output)
            task.publish(publishedRel) {
                if (workspace.resolveFrom(cwd, command.output) != output) throw McpInputException("Output path changed")
                if (command.overwrite) Files.move(staged.toPath(), output.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                else Files.move(staged.toPath(), output.toPath())
            }
        } finally {
            if (staged.parentFile != staging && !deleteOutputChecked(staged)) task.cleanupFailed()
            if (!deleteOutputChecked(staging, recursive = true)) task.cleanupFailed()
        }
    }

    private fun verifyNoSymlinksIn(dir: File, task: McpTask) {
        Files.newDirectoryStream(dir.toPath()).use { entries ->
            for (entry in entries) {
                task.checkCancelled()
                if (Files.isSymbolicLink(entry)) throw McpInputException("Archive directory contains a symbolic link")
            }
        }
    }

    private fun extract(task: McpTask, command: McpCommand, source: File, staged: File) {
        if (!source.isFile) throw McpInputException("Archive input does not exist")
        val format = isVolumeFile(source) ?: detectFormat(source) ?: detectFormatByMagic(source)
            ?: throw McpInputException("Unsupported archive format")
        if (format !in FORMAT_LABELS.keys) throw McpInputException("Unsupported archive format: $format")
        // Multi-volume sets and sidecar-keyed formats (XP3 cxdec, INT exe, LPK SCRIPT.LPK) read adjacent files.
        verifyNoSymlinksIn(source.parentFile!!, task)
        val selected = command.entries.joinToString("\n")
        val effectivePrefs = prefs ?: throw McpInputException("Archive preferences unavailable")
        runArchive(task, format, extractAccessors(format)) {
            if (!staged.mkdir()) throw McpInputException("Could not create extraction directory")
            val outcome = extractByFormat(format, source.path, staged.path, selected, effectivePrefs, command.password)
            task.checkCancelled()
            if (!outcome.counts.ok || outcome.counts.total <= 0) {
                val detail = outcome.error?.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
                throw McpInputException("Extraction did not complete$detail. See diagnostic steps.")
            }
        }
    }

    private fun pack(task: McpTask, command: McpCommand, source: File, staged: File, format: String) {
        if (command.enc.isNotEmpty() && format != "xp3") {
            throw McpInputException("Encryption scheme (-e) is only supported for XP3")
        }
        if (format in SINGLE_FILE_COMPRESS) {
            if (!source.isFile) throw McpInputException("Format $format requires a single source file")
            if (format == "ksd" && !source.name.endsWith(".txt", ignoreCase = true)) {
                throw McpInputException("KSD packing requires a .txt source file")
            }
            if (mvExtMismatch(source, format)) {
                throw McpInputException("Format $format requires a .${mvRequiredExt(format)} source file")
            }
        } else {
            if (!source.isDirectory) throw McpInputException("Packing $format requires a source directory")
            workspace.verifyTree(source, task::checkCancelled)
        }
        if (format in setOf("int", "xp3")) {
            source.parentFile?.let { verifyNoSymlinksIn(it, task) }
            staged.parentFile?.let { verifyNoSymlinksIn(it, task) }
        }
        val effectivePrefs = prefs ?: throw McpInputException("Archive preferences unavailable")
        val level = defaultCompressLevel(effectivePrefs, format)
        runArchive(task, format, compressAccessors(format)) {
            val ok = compressDispatch(source, staged, format, level, command.password, effectivePrefs, splitOverride = 0, enc = command.enc)
            task.checkCancelled()
            if (!ok || !staged.isFile || staged.length() == 0L) {
                val why = PackErrors.last.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
                throw McpInputException("Packing did not complete$why. See diagnostic steps.")
            }
        }
    }

    private fun runArchive(task: McpTask, format: String, accessors: ProgressAccessors, block: () -> Unit) {
        task.checkCancelled()
        val handle = tryStartOperation(format)
        try {
            task.bindNativeCancel { handle.requestCancel() }
            if (!handle.await()) throw CancellationException()
            task.checkCancelled()
            task.bindNativeCancel {
                handle.markCancelRequested()
                try { observedAttempt("mcp_native_cancel", accessors.cancel) }
                catch (_: LinkageError) {
                    AppDiagnostics.begin("mcp_native_cancel").fail(FailureCode.UNSUPPORTED, "Native cancellation is unavailable")
                }
            }
            task.bindProgress {
                try {
                    org.json.JSONObject()
                        .put("format", format)
                        .put("bytes", accessors.getCount())
                        .put("totalBytes", accessors.getTotal())
                        .put("fileCount", accessors.getFileCount())
                        .put("fileTotal", accessors.getFileTotal())
                        .put("currentFile", accessors.getName().orEmpty())
                } catch (_: LinkageError) {
                    null
                }
            }
            task.checkCancelled()
            block()
        } finally {
            task.bindProgress(null)
            task.bindNativeCancel(null)
            handle.release()
        }
    }

    // ─── Full UUT & all 39 uu commands execution ─────────────────────────

    private data class UutRun(
        val ran: Int = 0,
        val exit: Int = 0,
        val stoppedLine: Int = 0,
        val returned: Boolean = false,
        val broke: Boolean = false,
        val continued: Boolean = false,
        val error: String = "",
    )

    private fun stmtLine(stmt: UutStmt): Int = when (stmt) {
        is UutStmt.Cmd -> stmt.line
        is UutStmt.Set -> stmt.line
        is UutStmt.IfInline -> stmt.line
        is UutStmt.If -> stmt.line
        is UutStmt.For -> stmt.line
        is UutStmt.While -> stmt.line
        is UutStmt.Break -> stmt.line
        is UutStmt.Continue -> stmt.line
        is UutStmt.Return -> stmt.line
    }

    private fun noteExit(vars: MutableMap<String, String>, code: Int) {
        vars["?"] = code.toString()
        vars["errorlevel"] = code.toString()
    }

    private fun tokenizeLine(text: String): List<String>? = when (val t = tokenizer.tokenize(text)) {
        is CliTokenizer.Out.Ok -> t.tokens
        is CliTokenizer.Out.Bad -> null
    }

    private fun evalCond(cond: UutCond, vars: Map<String, String>, cwd: File): Boolean? = when (cond) {
        is UutCond.VarCmp -> UutParser.compareValues(vars[cond.name] ?: "", cond.op, cond.value)
        is UutCond.Exists -> {
            val p = UutParser.expandVars(cond.rawPath, vars)
            val exists = if (CliGlob.hasWildcards(p)) {
                UuCommands.expandGlobs(listOf("exist", p), cwd)
                    .drop(1).any { !it.contains('*') && !it.contains('?') }
            } else {
                workspace.resolveFrom(cwd, p).exists()
            }
            if (cond.negate) !exists else exists
        }
    }

    private fun execUutList(
        task: McpTask,
        stmts: List<UutStmt>,
        state: McpUutState,
        depth: Int,
        keepGoing: Boolean,
        sink: ((String) -> Unit)? = null,
    ): UutRun {
        var ran = 0
        var exit = 0
        var lastErr = ""
        fun emit(text: String) {
            if (text.isEmpty()) return
            if (sink != null) sink(text) else task.appendStdout(text)
        }
        for (st in stmts) {
            task.checkCancelled()
            when (st) {
                is UutStmt.Cmd -> {
                    var lastExit = 0
                    var anyRan = false
                    for ((piece, joiner) in UutParser.splitChain(st.raw)) {
                        task.checkCancelled()
                        when (joiner) {
                            "&&" -> if (lastExit != 0) continue
                            "||" -> if (lastExit == 0) continue
                        }
                        val expanded = UutParser.expandVars(piece, state.vars)
                        val toks = tokenizeLine(expanded)
                            ?: return UutRun(ran + 1, 1, st.line, error = "Line ${st.line}: unclosed quote")
                        if (toks.isEmpty()) continue
                        when (toks[0]) {
                            "break" -> return UutRun(ran, exit, 0, broke = true)
                            "continue" -> return UutRun(ran, exit, 0, continued = true)
                            "return" -> {
                                val code = UutParser.expandVars(toks.drop(1).joinToString(" "), state.vars)
                                    .trim().toIntOrNull() ?: 0
                                return UutRun(ran, code, 0, returned = true)
                            }
                        }
                        if (!UutParser.commandAllowed(toks[0])) {
                            throw McpInputException("Line ${st.line}: command not allowed in UUT: ${toks[0]}")
                        }
                        val (segExit, segErr) = runTokens(task, toks, state, depth + 1, sink)
                        lastExit = segExit
                        if (segErr.isNotEmpty()) lastErr = segErr
                        noteExit(state.vars, lastExit)
                        anyRan = true
                    }
                    exit = lastExit
                    if (anyRan) ran++
                    if (exit != 0 && !keepGoing) return UutRun(ran, exit, st.line, error = lastErr)
                }
                is UutStmt.Set -> {
                    val expanded = UutParser.expandVars(st.rawValue, state.vars).trim()
                    val cap = UutParser.captureInner(expanded)
                    state.vars[st.name] = when {
                        cap != null -> captureCmd(task, cap, state, depth)
                        else -> try {
                            UutParser.evalArith(expanded) ?: expanded
                        } catch (e: ArithmeticException) {
                            val msg = "Line ${st.line}: ${e.message ?: "arithmetic error"}"
                            emit(msg)
                            return UutRun(ran + 1, 1, st.line, error = msg)
                        }
                    }
                }
                is UutStmt.IfInline -> {
                    val okInline = evalCond(st.cond, state.vars, state.cwd)
                        ?: return UutRun(ran + 1, 1, st.line, error = "Line ${st.line}: invalid numeric comparison")
                    if (okInline) {
                        val expanded = UutParser.expandVars(st.rawCmd, state.vars)
                        val toks = tokenizeLine(expanded)
                            ?: return UutRun(ran + 1, 1, st.line, error = "Line ${st.line}: unclosed quote")
                        if (toks.isNotEmpty()) {
                            when (toks[0]) {
                                "break" -> return UutRun(ran, exit, 0, broke = true)
                                "continue" -> return UutRun(ran, exit, 0, continued = true)
                                "return" -> {
                                    val code = UutParser.expandVars(toks.drop(1).joinToString(" "), state.vars)
                                        .trim().toIntOrNull() ?: 0
                                    return UutRun(ran, code, 0, returned = true)
                                }
                            }
                            if (!UutParser.commandAllowed(toks[0])) {
                                throw McpInputException("Line ${st.line}: command not allowed in UUT: ${toks[0]}")
                            }
                            val (segExit, segErr) = runTokens(task, toks, state, depth + 1, sink)
                            exit = segExit
                            if (segErr.isNotEmpty()) lastErr = segErr
                            noteExit(state.vars, exit)
                            ran++
                            if (exit != 0 && !keepGoing) return UutRun(ran, exit, st.line, error = lastErr)
                        }
                    }
                }
                is UutStmt.For -> {
                    val patternsRaw = UutParser.expandVars(st.rawPatterns, state.vars).trim()
                    val capItems = UutParser.captureInner(patternsRaw)?.let { cap ->
                        captureCmd(task, cap, state, depth).split('\n')
                            .map { it.trim() }.filter { it.isNotEmpty() }
                    }
                    if (capItems != null) {
                        for (item in capItems) {
                            task.checkCancelled()
                            state.vars[st.varName] = item
                            val b = execUutList(task, st.body, state, depth, keepGoing, sink)
                            ran += b.ran; exit = b.exit; if (b.error.isNotEmpty()) lastErr = b.error
                            if (b.returned || b.stoppedLine > 0) return UutRun(ran, exit, b.stoppedLine, b.returned, b.broke, b.continued, lastErr)
                            if (b.broke) break
                            if (b.continued) continue
                            if (b.exit != 0 && !keepGoing) return UutRun(ran, exit, st.line, error = lastErr)
                        }
                        continue
                    }
                    val patterns = patternsRaw.split(Regex("\\s+")).filter { it.isNotEmpty() }
                    if (patterns.isEmpty()) continue
                    val range = if (patterns.size == 1) UutParser.rangeValues(patterns[0]) else null
                    if (range != null) {
                        for (n in range) {
                            task.checkCancelled()
                            state.vars[st.varName] = n
                            val b = execUutList(task, st.body, state, depth, keepGoing, sink)
                            ran += b.ran; exit = b.exit; if (b.error.isNotEmpty()) lastErr = b.error
                            if (b.returned || b.stoppedLine > 0) return UutRun(ran, exit, b.stoppedLine, b.returned, b.broke, b.continued, lastErr)
                            if (b.broke) break
                            if (b.continued) continue
                            if (b.exit != 0 && !keepGoing) return UutRun(ran, exit, st.line, error = lastErr)
                        }
                        continue
                    }
                    val files = UuCommands.expandGlobs(listOf("for") + patterns, state.cwd)
                        .drop(1)
                        .filterNot { it.contains('*') || it.contains('?') }
                        .map { matched ->
                            val resolved = workspace.resolveFrom(state.cwd, matched)
                            state.cwd.toPath().relativize(resolved.toPath()).toString().replace('\\', '/')
                                .ifEmpty { resolved.name }
                        }
                    for (f in files) {
                        task.checkCancelled()
                        state.vars[st.varName] = f
                        val sub = execUutList(task, st.body, state, depth, keepGoing, sink)
                        ran += sub.ran; exit = sub.exit; if (sub.error.isNotEmpty()) lastErr = sub.error
                        if (sub.returned || sub.stoppedLine > 0) return UutRun(ran, exit, sub.stoppedLine, sub.returned, sub.broke, sub.continued, lastErr)
                        if (sub.broke) break
                        if (sub.continued) continue
                        if (sub.exit != 0 && !keepGoing) return UutRun(ran, exit, st.line, error = lastErr)
                    }
                }
                is UutStmt.While -> {
                    var iter = 0
                    while (true) {
                        task.checkCancelled()
                        if (iter++ >= UutParser.LOOP_MAX) {
                            val msg = "Line ${st.line}: while loop exceeded ${UutParser.LOOP_MAX} iterations"
                            emit(msg)
                            return UutRun(ran, 1, st.line, error = msg)
                        }
                        val okWhile = evalCond(st.cond, state.vars, state.cwd)
                            ?: return UutRun(ran, 1, st.line, error = "Line ${st.line}: invalid numeric comparison")
                        if (!okWhile) break
                        val sub = execUutList(task, st.body, state, depth, keepGoing, sink)
                        ran += sub.ran; exit = sub.exit; if (sub.error.isNotEmpty()) lastErr = sub.error
                        if (sub.returned || sub.stoppedLine > 0) return UutRun(ran, exit, sub.stoppedLine, sub.returned, sub.broke, sub.continued, lastErr)
                        if (sub.broke) break
                        if (sub.continued) continue
                        if (sub.exit != 0 && !keepGoing) return UutRun(ran, exit, st.line, error = lastErr)
                    }
                }
                is UutStmt.Break -> return UutRun(ran, exit, 0, broke = true)
                is UutStmt.Continue -> return UutRun(ran, exit, 0, continued = true)
                is UutStmt.If -> {
                    val okIf = evalCond(st.cond, state.vars, state.cwd)
                        ?: return UutRun(ran, 1, st.line, error = "Line ${st.line}: invalid numeric comparison")
                    val body = if (okIf) st.thenBody else st.elseBody
                    if (body.isNotEmpty()) {
                        val sub = execUutList(task, body, state, depth, keepGoing, sink)
                        ran += sub.ran; exit = sub.exit; if (sub.error.isNotEmpty()) lastErr = sub.error
                        if (sub.returned || sub.stoppedLine > 0 || sub.broke || sub.continued) {
                            return UutRun(ran, exit, sub.stoppedLine, sub.returned, sub.broke, sub.continued, lastErr)
                        }
                        if (sub.exit != 0 && !keepGoing) return UutRun(ran, exit, st.line, error = lastErr)
                    }
                }
                is UutStmt.Return -> {
                    val code = UutParser.expandVars(st.rawCode, state.vars).trim().toIntOrNull() ?: 0
                    return UutRun(ran, code, 0, returned = true)
                }
            }
        }
        return UutRun(ran, exit, 0, error = lastErr)
    }

    private fun captureCmd(task: McpTask, inner: String, state: McpUutState, depth: Int): String {
        val expanded = UutParser.expandVars(inner, state.vars)
        val toks = tokenizeLine(expanded) ?: return ""
        if (toks.isEmpty() || !UutParser.commandAllowed(toks[0])) {
            throw McpInputException("Command not allowed in UUT capture: ${toks.firstOrNull().orEmpty()}")
        }
        val sb = StringBuilder()
        val (code, _) = runTokens(task, toks, state, depth + 1) { sb.append(it).append('\n') }
        noteExit(state.vars, code)
        return sb.toString().trim()
    }

    private fun runTokens(
        task: McpTask,
        tokens0: List<String>,
        state: McpUutState,
        depth: Int,
        sink: ((String) -> Unit)? = null,
    ): Pair<Int, String> {
        var exit = 0
        var lastErr = ""
        for (seg in UuCommands.splitSegments(tokens0)) {
            task.checkCancelled()
            when (seg.op) {
                "&&" -> if (exit != 0) break
                "||" -> if (exit == 0) break
            }
            if (seg.tokens.isEmpty()) continue
            val (segExit, segErr) = runSegment(task, seg.tokens, state, depth, sink)
            exit = segExit
            if (segErr.isNotEmpty()) lastErr = segErr
            noteExit(state.vars, exit)
        }
        return exit to lastErr
    }

    private fun runSegment(
        task: McpTask,
        tokens: List<String>,
        state: McpUutState,
        depth: Int,
        sink: ((String) -> Unit)? = null,
    ): Pair<Int, String> {
        var toks = tokens
        var redir: Pair<String, Boolean>? = null
        val ri = toks.indexOfFirst { it == ">" || it == ">>" }
        if (ri >= 0) {
            val path = toks.getOrNull(ri + 1) ?: return 2 to "Missing redirection target"
            redir = path to (toks[ri] == ">>")
            toks = toks.subList(0, ri)
        }
        val stages = UuCommands.splitOnPipe(toks)
        var text = ""
        var exit = 0
        for ((i, stage) in stages.withIndex()) {
            if (stage.isEmpty()) continue
            if (i == 0) {
                val res = runStage(task, stage, state, depth)
                text = res.text
                exit = res.exitCode
            } else {
                val filtered = UuCommands.pipeFilter(stage, text)
                    ?: return 2 to "Pipe stage only supports grep, head, tail, wc, sort"
                text = filtered.first
                exit = filtered.second
            }
        }
        if (redir != null) {
            val outFile = workspace.resolveFrom(state.cwd, redir.first)
            val parent = outFile.parentFile ?: throw McpInputException("Output has no parent directory")
            if (!parent.isDirectory) throw McpInputException("Create the output's parent directory first")
            val payload = if (text.isEmpty()) "" else text + "\n"
            if (redir.second) outFile.appendText(payload, Charsets.UTF_8)
            else outFile.writeText(payload, Charsets.UTF_8)
            task.recordOutput(workspace.relativePath(outFile))
            return exit to if (exit != 0) text else ""
        }
        if (text.isNotEmpty()) {
            if (sink != null) sink(text) else task.appendStdout(text)
        }
        return exit to if (exit != 0) text else ""
    }

    private fun runStage(
        task: McpTask,
        rawTokens: List<String>,
        state: McpUutState,
        depth: Int,
    ): UuCommands.Result {
        val tokens = UuCommands.expandGlobs(rawTokens, state.cwd, UuCommands.globSkipIndices(rawTokens))
        val head = tokens.firstOrNull() ?: return UuCommands.Result("")
        if (!UutParser.commandAllowed(head)) {
            throw McpInputException("Command not allowed in MCP workspace: $head")
        }
        return when (head) {
            "echo" -> UuCommands.Result(tokens.drop(1).joinToString(" "))
            "pwd" -> UuCommands.Result(workspace.relativePath(state.cwd))
            "ls" -> {
                val target = tokens.getOrNull(1)?.let { workspace.resolveFrom(state.cwd, it) } ?: state.cwd
                if (!target.exists()) return UuCommands.Result("Not found: ${tokens.getOrNull(1)}", 1)
                if (target.isFile) return UuCommands.Result(target.name)
                val items = target.listFiles()
                    ?.filter { !it.name.startsWith(".uu-mcp-") }
                    ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                    ?.map { if (it.isDirectory) "${it.name}/" else it.name }
                    .orEmpty()
                UuCommands.Result(items.joinToString("\n"))
            }
            "cd" -> {
                val arg = tokens.getOrNull(1)
                val nextDir = when {
                    arg == null || arg.isEmpty() -> workspace.root
                    arg == ".." -> {
                        if (state.cwd == workspace.root) throw McpInputException("Cannot cd above workspace root")
                        state.cwd.parentFile ?: workspace.root
                    }
                    else -> workspace.resolveFrom(state.cwd, arg)
                }
                if (!nextDir.isDirectory) return UuCommands.Result("Not a directory: ${arg.orEmpty()}", 1)
                state.cwd = nextDir
                UuCommands.Result(workspace.relativePath(state.cwd))
            }
            "help" -> UuCommands.Result(UuCommands.renderHelp(buildUuCtx(task, state.cwd)))
            "uu" -> {
                val sub = tokens.drop(1)
                if (sub.firstOrNull() == "run") {
                    runNestedUut(task, sub.drop(1), state, depth)
                } else {
                    // Use atomic staging when x, c, cp match standard staged form.
                    val staged = try {
                        McpCommand.parse(sub).takeIf { it.action != null }
                    } catch (_: McpInputException) {
                        null
                    }
                    if (staged != null) {
                        try {
                            executeStaged(task, staged, state.cwd)
                            UuCommands.Result("ok: ${staged.output}", 0)
                        } catch (e: McpInputException) {
                            // Propagate path/sandbox violations directly; return command failures as exit 1.
                            if (e.message?.contains("workspace") == true || e.message?.contains("Symbolic link") == true ||
                                e.message?.contains("parent segments") == true) {
                                throw e
                            }
                            UuCommands.Result(e.message ?: "Command failed", 1)
                        }
                    } else {
                        runUuCommand(task, sub, state, depth)
                    }
                }
            }
            else -> throw McpInputException("Command not allowed in MCP workspace: $head")
        }
    }

    private fun runNestedUut(
        task: McpTask,
        args: List<String>,
        parentState: McpUutState,
        depth: Int,
    ): UuCommands.Result {
        if (depth >= MCP_UUT_MAX_DEPTH) {
            return UuCommands.Result("Maximum UUT nesting depth ($MCP_UUT_MAX_DEPTH) exceeded", 1)
        }
        var keepGoing = false
        var i = 0
        while (i < args.size && args[i].startsWith('-')) {
            if (args[i] == "-k") { keepGoing = true; i++ }
            else return UuCommands.Result("Unknown flag: ${args[i]}", 2)
        }
        val scriptPath = args.getOrNull(i) ?: return UuCommands.Result("uu run [-k] <file.uut> [args...]", 2)
        val scriptArgs = args.drop(i + 1)
        val file = workspace.resolveFrom(parentState.cwd, scriptPath)
        if (!file.isFile || !file.extension.equals("uut", ignoreCase = true)) {
            return UuCommands.Result("Expected a workspace .uut file: $scriptPath", 1)
        }
        if (file.length() > MCP_REQUEST_LIMIT) {
            return UuCommands.Result("UUT script exceeds 64 KiB: $scriptPath", 1)
        }
        val bytes = file.readBytes()
        val enc = detectBomEncoding(bytes) ?: textEncodingOf(bytes) ?: "UTF-8"
        val text = decodeTextStrict(bytes, enc).removePrefix("\uFEFF")
        // Validate the nested script before running it.
        McpCommand.script(text, scriptArgs, keepGoing)
        val ast = try {
            UutParser.parse(text)
        } catch (e: UutParseException) {
            return UuCommands.Result("Line ${e.line}: ${e.reason}", 2)
        }
        val childVars = HashMap<String, String>()
        childVars["0"] = scriptPath
        scriptArgs.forEachIndexed { idx, a -> childVars[(idx + 1).toString()] = a }
        childVars["argc"] = scriptArgs.size.toString()
        childVars["args"] = scriptArgs.joinToString(" ")
        val childState = McpUutState(parentState.cwd, childVars)
        val sb = StringBuilder()
        val r = execUutList(task, ast, childState, depth + 1, keepGoing) { sb.append(it).append('\n') }
        parentState.cwd = childState.cwd
        val output = sb.toString().trimEnd('\n')
        return UuCommands.Result(output.ifEmpty { r.error }, r.exit)
    }

    private fun buildUuCtx(task: McpTask, cwd: File, cacheDir: File? = null): UuCommands.Ctx =
        UuCommands.Ctx(
            prefs = prefs,
            cwd = cwd,
            activity = null,
            str = str,
            fds = sessionFds,
            cacheDir = cacheDir,
            defaultOutDir = cwd,
            registerCancel = { cancelFn -> task.bindNativeCancel(cancelFn) },
        )

    private fun runUuCommand(
        task: McpTask,
        argvWithoutUu: List<String>,
        state: McpUutState,
        depth: Int,
    ): UuCommands.Result {
        if (argvWithoutUu.firstOrNull() == "run") {
            return runNestedUut(task, argvWithoutUu.drop(1), state, depth)
        }
        val expanded = UuCommands.expandGlobs(
            listOf("uu") + argvWithoutUu,
            state.cwd,
            UuCommands.globSkipIndices(listOf("uu") + argvWithoutUu),
        ).drop(1)
        val cmdName = expanded.firstOrNull().orEmpty()
        val beforeFiles = if (cmdName in setOf("x", "c", "cp", "mv", "rn", "mkdir", "set", "cso", "audio", "video", "enc", "mvdec", "rmd", "add", "dd", "sed", "tr", "img", "b64")) {
            state.cwd.listFiles()?.map { it.name to it.lastModified() }?.toMap().orEmpty()
        } else emptyMap()
        val tempCache = File(workspace.root, ".uu-mcp-cache-${task.taskId}-${System.nanoTime()}")
        val res = try {
            UuCommands.dispatch(expanded, buildUuCtx(task, state.cwd, tempCache))
        } finally {
            task.bindNativeCancel(null)
            if (tempCache.exists() && !deleteOutputChecked(tempCache, recursive = true)) {
                task.cleanupFailed()
            }
        }
        if (res.picker != null) {
            throw McpInputException("uu $cmdName requires explicit file or directory path arguments")
        }
        if (res.exitCode == 0 && beforeFiles.isNotEmpty()) {
            state.cwd.listFiles()?.forEach { f ->
                if (!f.name.startsWith(".uu-mcp-") && beforeFiles[f.name] != f.lastModified()) {
                    task.recordOutput(workspace.relativePath(f))
                }
            }
        }
        return res
    }
}
