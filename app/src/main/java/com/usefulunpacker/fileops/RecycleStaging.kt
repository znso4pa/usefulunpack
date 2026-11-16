package com.usefulunpacker.fileops

import com.usefulunpacker.deleteOutputChecked
import java.io.File

/** Once originals have moved/deletion has begun, staging may contain the only surviving copy. */
internal class RecycleStaging(private val directory: File) {
    var protected = false
        private set
    fun protect() { protected = true }
    fun discardIncomplete() {
        if (!protected) deleteOutputChecked(directory, recursive = true)
    }
}
