# XP3 listing, extraction and encrypted repacking

## Identify the encryption layer

- Use `uu l` or `ARCHIVE_LIST` and inspect the reported XP3 scheme. `crypt:<scheme>` identifies a supported content filter. `cxdec:<scheme>` identifies a cxdec scheme. Treat `cxdec:?` and `suspect` as unresolved. An entry flag or a readable archive index is insufficient evidence for repacking.
- For a recognized scheme, extract one meaningful file to a separate directory. Confirm the expected media signature or script readability and encoding. A single header match is a reason for another check, such as opening the asset or comparing a known original.

## Repack with the XP3 writer

- Use `COMPRESS` with `format: "xp3"` and `encryption` set to the verified scheme, or `uu c <source> <output.xp3> -f xp3 -e <scheme>`. The CLI help lists supported keyless schemes. Use `cxdec` for the native cxdec path and keep the original archive and needed sidecar metadata available near the input or output.
- Keep plaintext files in the staging tree when using the native writer. It handles the content filter, stored ADLR, index and segments. Applying XOR to the whole container produces a separate outer wrapper. Use that only when the consuming workflow expects such a wrapper.
- Reopen and re-extract representative members, then compare their plaintext hashes. Test the patch in the engine if available. If the filter is a custom per-entry algorithm, report what can be listed or verified and what details a compatible reader/writer would need.

Reference [GARbro's XP3 implementation](https://github.com/morkt/GARbro/blob/master/ArcFormats/KiriKiri/ArcXP3.cs).
