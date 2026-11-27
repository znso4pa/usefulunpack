# UUT dry runs, exit codes and cancellation

1. Use local validation before execution to check structure, path restrictions and arguments. Validation verifies the script shape, while execution checks data, native decoders and environment.
2. Separate the validation, execution and output verification stages. Use an explicit success check between dependent operations, and have the task report failures rather than continuing into a repack after a failed extraction.
3. Keep the task ID and chain ID when cancelling or when the caller times out. Query status and log before retrying. Check the final output and any cleanup errors, especially on a partially completed batch.

## Read-only comparison and localization checks

`uu dircmp original translated -j` compares relative paths and actual file bytes. `uu check original.txt translated.txt --profile named -j` uses the same conservative localization checker as the UI. Choose a grammar only after the user confirms it and physical-line correspondence. Encoding flags are `--source-encoding`, `--translation-encoding`, and `--target-encoding`.

Both commands work inside UUT. Exit 0 means no findings, 1 means differences/review items, and 2 means incomplete/input/read errors. Nonzero exits stop ordinary UUT scripts; handle expected differences with `||`, or use `uu run -k` to gather results. Continuing is not proof of success. Never treat an incomplete comparison as identical or let it silently authorize destructive work. Prefer the read-only `COMPARE_DIRECTORY` / `LOCALIZATION_CHECK` skills when a structured report is enough.
