# Zstandard stream (.zst)

1. Identify a standard Zstandard frame before selecting the `.zst` reader. A file can require a decoder window or dictionary beyond what the tested app accepts. Preserve the specific error rather than guessing corruption.
2. Decode a single payload into staging. For a `.tar.zst` tree, list and extract through TAR.
3. Use `uu c "sample.bin" "sample.bin.zst" -f zst`. Verify decoded content. Supply the same dictionary context only if the reader/workflow actually supports it.
