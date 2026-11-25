# Embedded archives and scan-hit descriptors

1. Use `SCAN` or `uu scan` to locate embedded signatures after checking whether the outer file can be opened by a real archive reader. Random or compressed bytes can contain a false positive.
2. A CLI scan can return a descriptor such as `f3`. Use `uu l f3` for an indexed candidate or `uu dd if=f3 of=chunk.bin` to copy its range. Treat hit sizes and boundaries as evidence to verify, especially when the length is absent.
3. Inspect the carved file separately and verify a member before carving other hits. Re-run the scan if the host file changes or the descriptor becomes stale.
