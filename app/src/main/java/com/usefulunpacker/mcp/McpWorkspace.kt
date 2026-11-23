package com.usefulunpacker

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

internal class McpInputException(message: String) : IllegalArgumentException(message)

internal const val DEFAULT_MCP_WORKSPACE = "UsefulUnpack-MCP"

internal fun openMcpWorkspace(sharedStorage: File, selected: File?): McpWorkspace {
    val storage = sharedStorage.canonicalFile
    if (!storage.isDirectory) throw McpInputException("Shared storage is not available")
    val chosen = selected?.canonicalFile ?: storage
    if (!chosen.toPath().startsWith(storage.toPath())) {
        throw McpInputException("Choose a subdirectory of shared storage")
    }
    val directory = if (chosen == storage) {
        // Selecting the storage root grants access to the dedicated child only.
        McpWorkspace(storage).resolve(DEFAULT_MCP_WORKSPACE).also {
            if (!it.isDirectory && !it.mkdir() && !it.isDirectory) {
                throw McpInputException("Cannot create the default workspace directory")
            }
            if (Files.isSymbolicLink(it.toPath()) || it.canonicalFile != it) {
                throw McpInputException("The default workspace must be a real directory")
            }
        }
    } else chosen
    if (!directory.isDirectory || !directory.canRead() || !directory.canWrite()) {
        throw McpInputException("Workspace must be a readable and writable directory")
    }
    return McpWorkspace(directory)
}

/** Workspace paths are relative to the directory the user selected for this session. */
internal class McpWorkspace(directory: File) {
    val root: File = directory.canonicalFile.also { require(it.isDirectory) }

    fun resolve(path: String, allowRoot: Boolean = false): File {
        if (root.canonicalFile != root || !root.isDirectory) throw McpInputException("Workspace is unavailable")
        if (allowRoot && path == ".") return root
        val parts = path.split('/')
        if (path.length > 1024 || parts.any { it.isEmpty() || it == "." || it == ".." } ||
            path.any { it == '\\' || it.isISOControl() }) {
            throw McpInputException("Use a relative workspace path without parent segments")
        }
        var file = root
        for (part in parts) {
            file = File(file, part)
            if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile != file) {
                throw McpInputException("Symbolic links are outside the supported workspace")
            }
        }
        return file
    }

    /** Resolves [path] against [cwd] (which must itself lie inside [root]), enforcing sandbox boundaries. */
    fun resolveFrom(cwd: File, path: String): File {
        if (root.canonicalFile != root || !root.isDirectory) throw McpInputException("Workspace is unavailable")
        if (path.length > 1024 || path.any { it == '\\' || it.isISOControl() }) {
            throw McpInputException("Use a relative workspace path without parent segments")
        }
        if (path.startsWith("/")) {
            if (path == root.path) return root
            if (path.startsWith(root.path + "/")) {
                return resolve(path.removePrefix(root.path + "/"), allowRoot = true)
            }
            throw McpInputException("Use a relative workspace path without parent segments")
        }
        val base = when {
            cwd == root -> root
            cwd.path.startsWith(root.path + "/") -> resolve(cwd.path.removePrefix(root.path + "/"), allowRoot = true)
            else -> throw McpInputException("Current directory is outside the MCP workspace")
        }
        if (path == ".") return base
        val normalized = if (path.startsWith("./")) path.removePrefix("./") else path
        val parts = normalized.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) {
            throw McpInputException("Use a relative workspace path without parent segments")
        }
        var file = base
        for (part in parts) {
            file = File(file, part)
            if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile != file) {
                throw McpInputException("Symbolic links are outside the supported workspace")
            }
        }
        return file
    }

    fun relativePath(file: File): String =
        root.toPath().relativize(file.toPath()).toString().replace('\\', '/').ifEmpty { "." }

    fun verifyTree(start: File, checkCancelled: () -> Unit) {
        val nodes = ArrayDeque<File>()
        nodes.add(start)
        while (nodes.isNotEmpty()) {
            checkCancelled()
            val file = nodes.removeLast()
            val path = file.toPath()
            when {
                Files.isDirectory(path, NOFOLLOW_LINKS) -> Files.newDirectoryStream(path).use { children ->
                    children.forEach { nodes.add(it.toFile()) }
                }
                !Files.isRegularFile(path, NOFOLLOW_LINKS) ->
                    throw McpInputException("Input contains a link or a special file")
            }
        }
    }
}

/** Resolves the pack format key from an explicit `-f` flag or the output filename suffix. */
internal fun resolveMcpPackFormat(outputName: String, forceFormat: String = ""): String {
    if (forceFormat.isNotEmpty()) {
        val key = forceFormat.lowercase()
        if (key !in COMPRESS_EXT.keys) throw McpInputException("Unsupported pack format: $forceFormat")
        return key
    }
    val lower = outputName.lowercase()
    val candidates = COMPRESS_EXT.entries
        .filter { lower.endsWith(".${it.value}") }
        .sortedByDescending { it.value.length }
    if (candidates.isEmpty()) throw McpInputException("Cannot infer pack format from $outputName; pass -f FORMAT")
    val bestExt = candidates.first().value
    val hits = candidates.filter { it.value == bestExt }.map { it.key }
    // .pfs maps to both "pfs" (PF8) and "pf6"; default to "pfs" unless -f pf6 is passed.
    return if ("pfs" in hits) "pfs" else hits.first()
}

/**
 * Represents a validated MCP `uu` command or top-level `.uut` statement.
 * Supports all 39 `uu` commands in [UuCommands] plus `uu run` and full `.uut` scripts,
 * while never allowing arbitrary system shell execution.
 */
internal data class McpCommand(
    val action: Action?,
    val source: String,
    val output: String,
    val password: String = "",
    val overwrite: Boolean = false,
    val format: String = "",
    val enc: String = "",
    val entries: List<String> = emptyList(),
    val name: String = action?.cliName.orEmpty(),
    val argv: List<String> = emptyList(),
    val uutStmt: UutStmt? = null,
    val pathsToCheck: List<String> = listOfNotNull(
        source.takeIf { it.isNotEmpty() },
        output.takeIf { it.isNotEmpty() },
    ),
    val secrets: List<String> = listOfNotNull(password.takeIf { it.isNotEmpty() }),
    val scriptArgs: List<String> = emptyList(),
    val keepGoing: Boolean = false,
) {
    enum class Action(val cliName: String, val usage: String) {
        EXTRACT("x", "uu x [-p PASSWORD] ARCHIVE NEW_DIRECTORY [ENTRY...]"),
        PACK("c", "uu c [-p PASSWORD_OR_KEY] [-f FORMAT] [-e XP3_SCHEME] SOURCE NEW_ARCHIVE"),
        COPY("cp", "uu cp [-f] SOURCE_FILE OUTPUT_FILE"),
    }

    companion object {
        private val FD_REF_RE = Regex("^f\\d+$")
        private val PIPE_FILTERS = setOf("grep", "head", "tail", "wc", "sort")

        fun parse(argv: List<String>): McpCommand {
            val args = if (argv.firstOrNull() == "uu") argv.drop(1) else argv
            val cmdName = args.firstOrNull() ?: throw McpInputException("Expected a uu command")
            if (!UuCommands.isKnownCommand(cmdName)) {
                throw McpInputException("Unknown uu command: $cmdName")
            }
            val subArgs = args.drop(1)
            // Fast-path standard atomic staging forms for x, c, cp.
            val stagedAction = Action.entries.find { it.cliName == cmdName }
            if (stagedAction != null && isStagedForm(stagedAction, subArgs)) {
                return parseStaged(stagedAction, args, subArgs)
            }
            val secrets = extractSecrets(cmdName, subArgs)
            val staticPaths = extractStaticPathsForUu(cmdName, subArgs)
            return McpCommand(
                action = null,
                source = staticPaths.firstOrNull().orEmpty(),
                output = staticPaths.getOrNull(1).orEmpty(),
                password = secrets.firstOrNull().orEmpty(),
                name = cmdName,
                argv = args,
                pathsToCheck = staticPaths,
                secrets = secrets,
            )
        }

        private fun isStagedForm(action: Action, subArgs: List<String>): Boolean = when (action) {
            Action.COPY -> true
            Action.EXTRACT -> subArgs.none { it in setOf("-o", "--pre") || it.matches(FD_REF_RE) || CliGlob.hasWildcards(it) } &&
                countPositional(subArgs, setOf("-p")) >= 2
            Action.PACK -> subArgs.none { it in setOf("-c", "-s", "-l", "-b", "--post") || CliGlob.hasWildcards(it) } &&
                countPositional(subArgs, setOf("-p", "-f", "-e")) == 2
        }

        private fun countPositional(args: List<String>, valueFlags: Set<String>): Int {
            var count = 0
            var i = 0
            while (i < args.size) {
                val tok = args[i]
                when {
                    tok in valueFlags -> i += 2
                    tok.startsWith('-') -> i++
                    else -> { count++; i++ }
                }
            }
            return count
        }

        private fun parseStaged(action: Action, fullArgv: List<String>, subArgs: List<String>): McpCommand {
            var i = 0
            var password = ""
            var overwrite = false
            var format = ""
            var enc = ""
            val rest = ArrayList<String>()
            while (i < subArgs.size) {
                val tok = subArgs[i]
                when {
                    tok == "-p" && action != Action.COPY -> {
                        if (password.isNotEmpty()) throw McpInputException(action.usage)
                        password = subArgs.getOrNull(i + 1)?.takeIf { it.length in 1..1024 && !it.contains('\u0000') }
                            ?: throw McpInputException(action.usage)
                        i += 2
                    }
                    tok == "-f" && action == Action.COPY -> {
                        if (overwrite) throw McpInputException(action.usage)
                        overwrite = true
                        i++
                    }
                    tok == "-f" && action == Action.PACK -> {
                        if (format.isNotEmpty()) throw McpInputException(action.usage)
                        val rawFmt = subArgs.getOrNull(i + 1)?.lowercase() ?: throw McpInputException(action.usage)
                        if (rawFmt !in COMPRESS_EXT.keys) throw McpInputException("Unsupported pack format: $rawFmt")
                        format = rawFmt
                        i += 2
                    }
                    tok == "-e" && action == Action.PACK -> {
                        if (enc.isNotEmpty()) throw McpInputException(action.usage)
                        val rawEnc = subArgs.getOrNull(i + 1)?.takeIf { it.isNotEmpty() }
                            ?: throw McpInputException(action.usage)
                        enc = normalizeXp3PackEnc(rawEnc)
                            ?: throw McpInputException("Unsupported XP3 encryption scheme: $rawEnc")
                        i += 2
                    }
                    tok.startsWith('-') -> throw McpInputException(action.usage)
                    else -> {
                        rest.add(tok)
                        i++
                    }
                }
            }
            return when (action) {
                Action.EXTRACT -> {
                    if (rest.size < 2 || rest.size > 18) throw McpInputException(action.usage)
                    val entries = rest.drop(2).map {
                        sanitizeEntryPath(it) ?: throw McpInputException("Invalid archive entry path: $it")
                    }
                    McpCommand(
                        action = action, source = rest[0], output = rest[1],
                        password = password, entries = entries, name = action.cliName, argv = fullArgv,
                    )
                }
                Action.PACK -> {
                    if (rest.size != 2) throw McpInputException(action.usage)
                    val resolvedFmt = resolveMcpPackFormat(rest[1], format)
                    if (enc.isNotEmpty() && resolvedFmt != "xp3") {
                        throw McpInputException("Encryption scheme (-e) is only supported for XP3")
                    }
                    McpCommand(
                        action = action, source = rest[0], output = rest[1],
                        password = password, format = resolvedFmt, enc = enc, name = action.cliName, argv = fullArgv,
                    )
                }
                Action.COPY -> {
                    if (rest.size != 2) throw McpInputException(action.usage)
                    McpCommand(
                        action = action, source = rest[0], output = rest[1],
                        overwrite = overwrite, name = action.cliName, argv = fullArgv,
                    )
                }
            }
        }

        private fun extractSecrets(cmdName: String, subArgs: List<String>): List<String> {
            if (cmdName !in setOf("l", "cat", "x", "c", "set", "rmd", "add", "diff")) return emptyList()
            val out = ArrayList<String>()
            var i = 0
            while (i < subArgs.size) {
                if (subArgs[i] in setOf("-p", "--password", "-k", "--key")) {
                    val secret = subArgs.getOrNull(i + 1)
                    if (subArgs[i] == "-p" && (secret == null || secret.isEmpty() || secret.length > 1024 || '\u0000' in secret)) {
                        throw McpInputException("Invalid -p password")
                    }
                    if (!secret.isNullOrEmpty()) out.add(secret)
                    i += 2
                } else {
                    i++
                }
            }
            return out
        }

        internal fun extractStaticPathsForUu(cmdName: String, subArgs: List<String>): List<String> {
            val valueFlags = when (cmdName) {
                "x", "l" -> setOf("-p", "-o", "--pre")
                "c" -> setOf("-p", "-f", "-l", "-b", "-e", "--post")
                "cat" -> setOf("-e", "-p")
                "du" -> setOf("-d", "-n")
                "set", "rmd", "add", "diff" -> setOf("-p")
                "mvdec" -> setOf("-o")
                "audio" -> setOf("-o", "--rate", "--ch", "--depth", "--br")
                "video" -> setOf("-o")
                "cmp" -> setOf("-n")
                "strings" -> setOf("-e", "-n")
                "tr" -> setOf("-o")
                "img" -> setOf("-o", "-q")
                else -> emptySet()
            }
            val pos = ArrayList<String>()
            val flagPaths = ArrayList<String>()
            var i = 0
            while (i < subArgs.size) {
                val tok = subArgs[i]
                when {
                    tok in valueFlags -> {
                        val v = subArgs.getOrNull(i + 1) ?: throw McpInputException("Missing value for flag $tok")
                        if (tok == "-o") flagPaths.add(v)
                        i += 2
                    }
                    tok.startsWith('-') -> i++
                    else -> {
                        pos.add(tok)
                        i++
                    }
                }
            }
            val acceptsFd = cmdName in setOf("info", "l", "cat", "hash", "cp", "x", "hex", "dd", "strings", "tr", "guess", "fd")
            val candidates: List<String> = when (cmdName) {
                "fmt", "help", "docs", "fd" -> emptyList()
                "x" -> if ("-o" in subArgs) listOfNotNull(pos.firstOrNull()) else pos.take(2)
                "cat" -> {
                    pos.getOrNull(1)?.let { if ('$' !in it) sanitizeEntryPath(it) ?: throw McpInputException("Invalid archive entry path: $it") }
                    listOfNotNull(pos.firstOrNull())
                }
                "grep" -> listOfNotNull(pos.getOrNull(1))
                "find" -> if (pos.size >= 2) listOf(pos[0]) else emptyList()
                "tree", "hex", "strings", "tr", "run" -> listOfNotNull(pos.firstOrNull())
                "set" -> {
                    pos.getOrNull(1)?.let { if ('$' !in it) sanitizeEntryPath(it) ?: throw McpInputException("Invalid archive entry path: $it") }
                    listOfNotNull(pos.getOrNull(0), pos.getOrNull(2))
                }
                "add" -> {
                    pos.getOrNull(2)?.let { if ('$' !in it) sanitizeEntryPath(it) ?: throw McpInputException("Invalid archive entry path: $it") }
                    listOfNotNull(pos.getOrNull(0), pos.getOrNull(1))
                }
                "rmd" -> listOfNotNull(pos.firstOrNull())
                "enc" -> listOfNotNull(pos.getOrNull(0), pos.getOrNull(3))
                "sed" -> pos.drop(2)
                "dd" -> pos.mapNotNull {
                    when {
                        it.startsWith("if=") -> it.removePrefix("if=")
                        it.startsWith("of=") -> it.removePrefix("of=")
                        else -> null
                    }
                }
                "img", "audio" -> if (pos.size >= 2) pos.dropLast(1) else pos
                "video" -> pos.filter { it.lowercase() !in setOf("mp4", "wmv") }
                else -> pos
            } + flagPaths
            return candidates.filter { p ->
                p.isNotEmpty() && '$' !in p && !CliGlob.hasWildcards(p) && !(acceptsFd && p.matches(FD_REF_RE))
            }
        }

        fun script(text: String, scriptArgs: List<String> = emptyList(), keepGoing: Boolean = false): List<McpCommand> {
            val cleaned = text.removePrefix("\uFEFF")
            val ast = try {
                UutParser.parse(cleaned)
            } catch (e: UutParseException) {
                throw McpInputException("Line ${e.line}: ${e.reason}")
            }
            if (ast.isEmpty()) throw McpInputException("The script is empty")
            if (ast.size > 64) throw McpInputException("MCP scripts support up to 64 top-level statements")
            return ast.mapIndexed { idx, stmt ->
                val paths = ArrayList<String>()
                val secrets = ArrayList<String>()
                validateStmt(stmt, paths, secrets)
                // If a top-level statement is a single plain literal uu command, parse it directly
                // so step.source/step.output and atomic staging work identically to single run_uu commands.
                val plainUu = (stmt as? UutStmt.Cmd)?.let { tryParsePlainLiteralUu(it) }
                if (plainUu != null) {
                    plainUu.copy(uutStmt = stmt, scriptArgs = if (idx == 0) scriptArgs else emptyList(), keepGoing = keepGoing)
                } else {
                    McpCommand(
                        action = null,
                        source = paths.firstOrNull().orEmpty(),
                        output = paths.getOrNull(1).orEmpty(),
                        password = secrets.firstOrNull().orEmpty(),
                        name = stmtName(stmt),
                        argv = listOf("uut", stmtName(stmt)),
                        uutStmt = stmt,
                        pathsToCheck = paths,
                        secrets = secrets,
                        scriptArgs = if (idx == 0) scriptArgs else emptyList(),
                        keepGoing = keepGoing,
                    )
                }
            }
        }

        private fun stmtName(stmt: UutStmt): String = when (stmt) {
            is UutStmt.Cmd -> {
                val firstPiece = UutParser.splitChain(stmt.raw).firstOrNull()?.first.orEmpty()
                val toks = (CliTokenizer().tokenize(firstPiece) as? CliTokenizer.Out.Ok)?.tokens.orEmpty()
                if (toks.firstOrNull() == "uu") toks.getOrNull(1) ?: "uu" else toks.firstOrNull() ?: "cmd"
            }
            is UutStmt.Set -> "set"
            is UutStmt.IfInline, is UutStmt.If -> "if"
            is UutStmt.For -> "for"
            is UutStmt.While -> "while"
            is UutStmt.Break -> "break"
            is UutStmt.Continue -> "continue"
            is UutStmt.Return -> "return"
        }

        private fun tryParsePlainLiteralUu(cmd: UutStmt.Cmd): McpCommand? {
            if ('$' in cmd.raw) return null
            val pieces = UutParser.splitChain(cmd.raw)
            if (pieces.size != 1) return null
            val tokens = (CliTokenizer().tokenize(pieces[0].first) as? CliTokenizer.Out.Ok)?.tokens ?: return null
            if (tokens.firstOrNull() != "uu" || tokens.any { it in setOf("|", ">", ">>") || CliGlob.hasWildcards(it) }) {
                return null
            }
            return try {
                parse(tokens)
            } catch (e: McpInputException) {
                throw McpInputException("Line ${cmd.line}: ${e.message}")
            }
        }

        private fun validateStmt(stmt: UutStmt, paths: MutableList<String>, secrets: MutableList<String>) {
            when (stmt) {
                is UutStmt.Cmd -> validateRawCommandLine(stmt.line, stmt.raw, paths, secrets)
                is UutStmt.Set -> {
                    UutParser.captureInner(stmt.rawValue)?.let { inner ->
                        validateRawCommandLine(stmt.line, inner, paths, secrets)
                    }
                }
                is UutStmt.IfInline -> {
                    validateCond(stmt.cond, paths)
                    validateRawCommandLine(stmt.line, stmt.rawCmd, paths, secrets)
                }
                is UutStmt.If -> {
                    validateCond(stmt.cond, paths)
                    stmt.thenBody.forEach { validateStmt(it, paths, secrets) }
                    stmt.elseBody.forEach { validateStmt(it, paths, secrets) }
                }
                is UutStmt.For -> {
                    UutParser.captureInner(stmt.rawPatterns)?.let { inner ->
                        validateRawCommandLine(stmt.line, inner, paths, secrets)
                    }
                    stmt.body.forEach { validateStmt(it, paths, secrets) }
                }
                is UutStmt.While -> {
                    validateCond(stmt.cond, paths)
                    stmt.body.forEach { validateStmt(it, paths, secrets) }
                }
                is UutStmt.Break, is UutStmt.Continue, is UutStmt.Return -> Unit
            }
        }

        private fun validateCond(cond: UutCond, paths: MutableList<String>) {
            if (cond is UutCond.Exists && '$' !in cond.rawPath && !CliGlob.hasWildcards(cond.rawPath)) {
                paths.add(cond.rawPath)
            }
        }

        private fun validateRawCommandLine(
            lineNo: Int, raw: String, paths: MutableList<String>, secrets: MutableList<String>,
        ) {
            val pieces = UutParser.splitChain(raw)
            if (pieces.isEmpty()) throw McpInputException("Line $lineNo: empty command")
            for ((piece, _) in pieces) {
                val tokenized = CliTokenizer().tokenize(piece)
                val tokens = (tokenized as? CliTokenizer.Out.Ok)?.tokens
                    ?: throw McpInputException("Line $lineNo: unclosed quote")
                if (tokens.isEmpty()) continue
                var stageTokens = tokens
                val redirIdx = stageTokens.indexOfFirst { it == ">" || it == ">>" }
                if (redirIdx >= 0) {
                    val target = stageTokens.getOrNull(redirIdx + 1)
                        ?: throw McpInputException("Line $lineNo: missing redirection target")
                    if (stageTokens.size > redirIdx + 2) {
                        throw McpInputException("Line $lineNo: unexpected arguments after redirection")
                    }
                    if ('$' !in target && !CliGlob.hasWildcards(target)) paths.add(target)
                    stageTokens = stageTokens.subList(0, redirIdx)
                }
                val stages = UuCommands.splitOnPipe(stageTokens)
                if (stages.any { it.isEmpty() }) throw McpInputException("Line $lineNo: empty pipe stage")
                for ((i, stage) in stages.withIndex()) {
                    val head = stage[0]
                    if (i == 0) {
                        if (!UutParser.commandAllowed(head) && head != "continue") {
                            throw McpInputException("Line $lineNo: command not allowed in UUT: $head")
                        }
                        when (head) {
                            "uu" -> {
                                val sub = stage.getOrNull(1)
                                    ?: throw McpInputException("Line $lineNo: expected a uu subcommand")
                                if (!sub.startsWith('$')) {
                                    if (!UuCommands.isKnownCommand(sub)) {
                                        throw McpInputException("Line $lineNo: unknown uu command: $sub")
                                    }
                                    try {
                                        secrets.addAll(extractSecrets(sub, stage.drop(2)))
                                        paths.addAll(extractStaticPathsForUu(sub, stage.drop(2)))
                                    } catch (e: McpInputException) {
                                        throw McpInputException("Line $lineNo: ${e.message}")
                                    }
                                }
                            }
                            "ls", "cd" -> {
                                val arg = stage.getOrNull(1)
                                if (arg != null && '$' !in arg && !CliGlob.hasWildcards(arg) && !(head == "cd" && arg == "..")) {
                                    paths.add(arg)
                                }
                            }
                        }
                    } else if (head !in PIPE_FILTERS) {
                        throw McpInputException("Line $lineNo: pipe stage must be grep, head, tail, wc or sort")
                    }
                }
            }
        }
    }
}
