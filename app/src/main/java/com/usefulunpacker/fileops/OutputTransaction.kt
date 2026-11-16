package com.usefulunpacker

import java.io.File
import java.io.IOException

/** A failed encoder must neither publish a partial file nor destroy the last recovery copy. */
internal fun writeOutputAtomically(destination: File, writer: (File) -> Boolean) {
    AppDiagnostics.step("write_output") {
        val staged = File.createTempFile("uu-", ".pending", destination.absoluteFile.parentFile)
        try {
            if (!writer(staged)) throw IOException("Encoder did not complete")
            if (!staged.renameTo(destination)) throw IOException("Cannot publish encoded output")
        } finally {
            if (staged.exists()) deleteOutputChecked(staged)
        }
    }
}
