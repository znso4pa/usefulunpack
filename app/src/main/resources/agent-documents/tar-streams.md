# TAR archives and single-file compression

- A TAR archive can hold multiple paths. `.tar.gz`, `.tar.bz2`, `.tar.xz` and `.tar.zst` put a compression stream around that archive. A standalone `.gz`, `.bz2`, `.xz` or `.zst` can instead hold a single non-archive payload. Check what the app identifies before selecting entries.
- Use `uu l` or `ARCHIVE_LIST` to inspect a TAR archive, then `uu x` or `EXTRACT` with the intended entries. For standalone streams, extract the payload first and call `uu info` on that distinct output if further inspection is needed.
- To pack a directory as TAR plus gzip, use `uu c "stage" "output.tar.gz" -f tgz`. The matching format keys are `tar`, `tgz`, `tbz2`, `txz` and `tzst`. Use a single-file format such as `gz` for a single payload.
- Reopen the TAR archive to verify paths. For a standalone stream, compare the decompressed file and the source by hash or `uu cmp`. Container bytes can differ with compression settings or metadata.
