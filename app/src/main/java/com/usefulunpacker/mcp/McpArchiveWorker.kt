package com.usefulunpacker

import android.content.SharedPreferences
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal class McpArchiveWorker(private val workspace: McpWorkspace, private val prefs: SharedPreferences) {
    fun execute(task: McpTask, command: McpCommand) {
        val source = workspace.resolve(command.source)
        val output = workspace.resolve(command.output)
        val parent = output.parentFile ?: throw McpInputException("Output has no parent directory")
        if (!parent.isDirectory) throw McpInputException("Create the output's parent directory first")
        if (source == output || output.toPath().startsWith(source.toPath())) {
            throw McpInputException("The output must be outside the source")
        }
        if (output.exists() && (!command.overwrite || !output.isFile)) {
            throw McpInputException("The output already exists")
        }

        // Use the final filename for native formats whose keys depend on that name.
        val staging = Files.createTempDirectory(parent.toPath(), ".uu-mcp-").toFile()
        val staged = File(staging, output.name)
        try {
            when (command.action) {
                McpCommand.Action.EXTRACT -> extract(task, command, source, staged)
                McpCommand.Action.PACK -> pack(task, command, source, staged)
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
            task.publish(command.output) {
                if (workspace.resolve(command.output) != output) throw McpInputException("Output path changed")
                if (command.overwrite) Files.move(staged.toPath(), output.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                else Files.move(staged.toPath(), output.toPath())
            }
        } finally {
            if (!deleteOutputChecked(staging, recursive = true)) task.cleanupFailed()
        }
    }

    private fun extract(task: McpTask, command: McpCommand, source: File, staged: File) {
        if (!source.isFile) throw McpInputException("Archive input does not exist")
        val format = detectFormat(source)
        if (format !in setOf("zip", "bgi", "lpk")) throw McpInputException("Extraction supports ZIP, BGI ARC and LPK")
        val key = checkNotNull(format)
        // ZIP volumes and LPK key discovery can read adjacent files as well.
        Files.newDirectoryStream(source.parentFile!!.toPath()).use { entries ->
            for (entry in entries) {
                task.checkCancelled()
                if (Files.isSymbolicLink(entry)) throw McpInputException("Archive directory contains a symbolic link")
            }
        }
        runArchive(task, key, extractAccessors(key).cancel) {
            if (!staged.mkdir()) throw McpInputException("Could not create extraction directory")
            val outcome = extractByFormat(key, source.path, staged.path, "", prefs, command.password)
            task.checkCancelled()
            if (!outcome.counts.ok || outcome.counts.total <= 0) {
                throw McpInputException("Extraction did not complete. See diagnostic steps.")
            }
        }
    }

    private fun pack(task: McpTask, command: McpCommand, source: File, staged: File) {
        if (!source.isDirectory) throw McpInputException("Packing requires a source directory")
        workspace.verifyTree(source, task::checkCancelled)
        val format = staged.extension.lowercase()
        if (format !in setOf("zip", "lpk")) throw McpInputException("Packing supports ZIP and LPK")
        runArchive(task, format, compressAccessors(format).cancel) {
            val ok = compressDispatch(source, staged, format, 5, command.password, prefs, splitOverride = 0)
            task.checkCancelled()
            if (!ok || !staged.isFile || staged.length() == 0L) {
                throw McpInputException("Packing did not complete. See diagnostic steps.")
            }
        }
    }

    private fun runArchive(task: McpTask, format: String, cancel: () -> Unit, block: () -> Unit) {
        task.checkCancelled()
        val handle = tryStartOperation(format)
        try {
            task.bindNativeCancel { handle.requestCancel() }
            if (!handle.await()) throw java.util.concurrent.CancellationException()
            task.checkCancelled()
            task.bindNativeCancel {
                handle.markCancelRequested()
                try { observedAttempt("mcp_native_cancel", cancel) }
                catch (_: LinkageError) {
                    AppDiagnostics.begin("mcp_native_cancel").fail(FailureCode.UNSUPPORTED, "Native cancellation is unavailable")
                }
            }
            task.checkCancelled()
            block()
        } finally {
            task.bindNativeCancel(null)
            handle.release()
        }
    }
}
