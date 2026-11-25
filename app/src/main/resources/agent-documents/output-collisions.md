# Existing outputs, staging and cleanup

1. Check whether the destination exists and whether it overlaps the source. Use a new stage per job to prevent stale files from making a failed run appear successful.
2. For format-specific required names, put the new output in a different folder rather than adding a suffix that changes runtime behavior. Verify user intent before reusing an existing output.
3. After failure or cancellation, inspect the receipt and distinguish published results from temporary files. Preserve the only surviving copy and report cleanup failures with the chain identifier.
