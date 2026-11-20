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

/** This is the MCP command allowlist. Arguments never go through a shell or terminal expansion. */
internal data class McpCommand(
    val action: Action, val source: String, val output: String,
    val password: String = "", val overwrite: Boolean = false,
) {
    enum class Action(val cliName: String, val usage: String) {
        EXTRACT("x", "uu x [-p PASSWORD] ARCHIVE NEW_DIRECTORY"),
        PACK("c", "uu c [-p PASSWORD_OR_LPK_KEY] SOURCE_DIRECTORY NEW_ARCHIVE"),
        COPY("cp", "uu cp [-f] SOURCE_FILE OUTPUT_FILE"),
    }

    companion object {
        fun parse(argv: List<String>): McpCommand {
            val args = if (argv.firstOrNull() == "uu") argv.drop(1) else argv
            val action = Action.entries.find { it.cliName == args.firstOrNull() }
                ?: throw McpInputException("Supported commands: x, c, cp")
            var index = 1
            var password = ""
            var overwrite = false
            if (args.getOrNull(index) == "-p" && action != Action.COPY) {
                password = args.getOrNull(index + 1)?.takeIf { it.length in 1..1024 && !it.contains('\u0000') }
                    ?: throw McpInputException(action.usage)
                index += 2
            } else if (args.getOrNull(index) == "-f" && action == Action.COPY) {
                overwrite = true
                index++
            }
            if (args.size != index + 2 || args.drop(index).any { it.startsWith('-') }) {
                throw McpInputException(action.usage)
            }
            return McpCommand(action, args[index], args[index + 1], password, overwrite)
        }

        fun script(text: String): List<McpCommand> {
            val lines = text.removePrefix("\uFEFF").lines()
            val commands = ArrayList<McpCommand>()
            for ((index, source) in lines.withIndex()) {
                val line = UutParser.stripComment(source).trim()
                if (line.isEmpty()) continue
                if (commands.size == 32) throw McpInputException("MCP scripts support up to 32 commands")
                val tokens = (CliTokenizer().tokenize(line) as? CliTokenizer.Out.Ok)?.tokens
                if (tokens?.firstOrNull() != "uu" || tokens.any {
                        it in setOf("|", ">", ">>", "&&", "||", ";") || '$' in it
                    }) {
                    throw McpInputException("Line ${index + 1}: expected a literal uu command")
                }
                try {
                    commands.add(parse(tokens))
                } catch (e: McpInputException) {
                    throw McpInputException("Line ${index + 1}: ${e.message}")
                }
            }
            if (commands.isEmpty()) throw McpInputException("The script is empty")
            return commands
        }
    }
}
