package com.usefulunpacker

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

internal class McpInputException(
    message: String,
    val errorCode: FailureCode = FailureCode.INVALID_RESULT,
    val diagnosticDetail: String? = null,
) : IllegalArgumentException(message)

/** Only use with server-defined text and metadata. Client input belongs in the response. */
internal fun mcpInputError(detail: String, code: FailureCode = FailureCode.INVALID_RESULT) =
    McpInputException(detail, code, diagnosticDetail = detail)

internal const val DEFAULT_MCP_WORKSPACE = "UsefulUnpack-MCP"

internal fun openMcpWorkspace(sharedStorage: File, selected: File?): McpWorkspace {
    val storage = sharedStorage.canonicalFile
    if (!storage.isDirectory) throw mcpInputError("Shared storage is not available")
    val chosen = selected?.canonicalFile ?: storage
    if (!chosen.toPath().startsWith(storage.toPath())) {
        throw mcpInputError("Choose a subdirectory of shared storage")
    }
    val directory = if (chosen == storage) {
        // Selecting the storage root grants access to the dedicated child only.
        McpWorkspace(storage).resolve(DEFAULT_MCP_WORKSPACE).also {
            if (!it.isDirectory && !it.mkdir() && !it.isDirectory) {
                throw mcpInputError("Cannot create the default workspace directory")
            }
            if (Files.isSymbolicLink(it.toPath()) || it.canonicalFile != it) {
                throw mcpInputError("The default workspace must be a real directory")
            }
        }
    } else chosen
    if (!directory.isDirectory || !directory.canRead() || !directory.canWrite()) {
        throw mcpInputError("Workspace must be a readable and writable directory")
    }
    return McpWorkspace(directory)
}

/** Workspace paths are relative to the directory the user selected for this session. */
internal class McpWorkspace(directory: File, private val onResolve: ((File) -> Unit)? = null) {
    val root: File = directory.canonicalFile.also { require(it.isDirectory) }

    fun resolve(path: String, allowRoot: Boolean = false): File {
        if (root.canonicalFile != root || !root.isDirectory) throw mcpInputError("Workspace is unavailable")
        if (allowRoot && path == ".") return root.also { onResolve?.invoke(it) }
        val parts = path.split('/')
        if (path.length > 1024 || parts.any { it.isEmpty() || it == "." || it == ".." } ||
            path.any { it == '\\' || it.isISOControl() }) {
            throw mcpInputError("Use a relative workspace path without parent segments")
        }
        var file = root
        for (part in parts) {
            file = File(file, part)
            if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile != file) {
                throw mcpInputError("Symbolic links are outside the supported workspace")
            }
        }
        onResolve?.invoke(file)
        return file
    }

    /** Resolves [path] against [cwd] (which must itself lie inside [root]), enforcing sandbox boundaries. */
    fun resolveFrom(cwd: File, path: String): File {
        if (root.canonicalFile != root || !root.isDirectory) throw mcpInputError("Workspace is unavailable")
        if (path.length > 1024 || path.any { it == '\\' || it.isISOControl() }) {
            throw mcpInputError("Use a relative workspace path without parent segments")
        }
        if (path.startsWith("/")) {
            if (path == root.path) return root.also { onResolve?.invoke(it) }
            if (path.startsWith(root.path + "/")) {
                return resolve(path.removePrefix(root.path + "/"), allowRoot = true)
            }
            throw mcpInputError("Use a relative workspace path without parent segments")
        }
        val base = when {
            cwd == root -> root
            cwd.path.startsWith(root.path + "/") -> resolve(cwd.path.removePrefix(root.path + "/"), allowRoot = true)
            else -> throw mcpInputError("Current directory is outside the MCP workspace")
        }
        if (path == ".") return base.also { onResolve?.invoke(it) }
        val normalized = if (path.startsWith("./")) path.removePrefix("./") else path
        val parts = normalized.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) {
            throw mcpInputError("Use a relative workspace path without parent segments")
        }
        var file = base
        for (part in parts) {
            file = File(file, part)
            if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile != file) {
                throw mcpInputError("Symbolic links are outside the supported workspace")
            }
        }
        onResolve?.invoke(file)
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
                    throw mcpInputError("Input contains a link or a special file")
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
