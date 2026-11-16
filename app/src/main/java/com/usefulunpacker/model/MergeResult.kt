package com.usefulunpacker

/** A failed/partial extraction must never become the input of a successful-looking repack. */
internal fun requireMergeExtraction(outcome: ExtractOutcome, stage: String) {
    if (!outcome.counts.ok || outcome.error != null) {
        throw java.io.IOException("Merge $stage extraction failed: ${outcome.error ?: "${outcome.counts.error} errors, ${outcome.counts.success} files"}")
    }
}

/** The overlay order and failure barriers are shared with host-side regression tests. */
internal fun extractMergeInputs(
    cancelled: () -> Boolean,
    target: () -> ExtractOutcome,
    source: () -> ExtractOutcome,
): Boolean {
    if (cancelled()) return false
    val targetResult = target()
    if (cancelled()) return false
    requireMergeExtraction(targetResult, "target")
    val sourceResult = source()
    if (cancelled()) return false
    requireMergeExtraction(sourceResult, "source")
    return true
}
