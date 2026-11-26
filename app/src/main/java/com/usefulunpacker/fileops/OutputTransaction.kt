package com.usefulunpacker

import java.io.File
import java.io.IOException

/** A failed encoder must neither publish a partial file nor destroy the last recovery copy. */
internal fun writeOutputAtomically(destination: File, replaceExisting: Boolean = true, writer: (File) -> Boolean) {
    AppDiagnostics.step("write_output") {
        val staged = File.createTempFile("uu-", ".pending", destination.absoluteFile.parentFile)
        try {
            if (!writer(staged)) throw IOException("Encoder did not complete")
            if (replaceExisting) {
                if (!staged.renameTo(destination)) throw IOException("Cannot publish encoded output")
            } else {
                java.nio.file.Files.move(staged.toPath(), destination.toPath())
            }
            recordTaskOutput(destination)
        } finally {
            if (staged.exists()) deleteOutputChecked(staged)
        }
    }
}
