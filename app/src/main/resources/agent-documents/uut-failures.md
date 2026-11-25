# UUT dry runs, exit codes and cancellation

1. Use local validation before execution to check structure, path restrictions and arguments. Validation verifies the script shape, while execution checks data, native decoders and environment.
2. Separate the validation, execution and output verification stages. Use an explicit success check between dependent operations, and have the task report failures rather than continuing into a repack after a failed extraction.
3. Keep the task ID and chain ID when cancelling or when the caller times out. Query status and log before retrying. Check the final output and any cleanup errors, especially on a partially completed batch.
