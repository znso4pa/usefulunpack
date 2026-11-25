# Unpacking, repacking and verification

## Choose the operation

- Use `ARCHIVE_LIST` to enumerate a known archive. For `EXTRACT`, use exact listed paths when the user needs only selected files. Use `SCAN` for embedded file signatures when the input is an unknown container. Wait for `TASK_STATUS` to show a terminal result before picking up an asynchronous operation's outputs.
- Work in a separate output directory. Confirm the format and variant as well as any required password, encryption or directory layout before `COMPRESS`. A valid XP3 index alone is insufficient to identify its encryption or show that the app can reproduce it.

## Verify the result

- Reopen the repack. Compare entry names and paths, then re-extract and compare a sample of changed and unchanged members. Byte-for-byte archive identity depends on the format and rebuild strategy. Use content equivalence where container bytes are expected to change.
- Report which checks succeeded, any skipped or failed entries, and whether the engine opened the result. Preserve original files until the user has a chance to verify the output.
