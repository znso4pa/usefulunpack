# Gzip stream (.gz)

1. A gzip stream yields a payload rather than an arbitrary directory tree. If the result is TAR, inspect it as a separate archive or use the supported compound suffix.
2. Extract into a fresh directory, then inspect the decoded payload. Concatenated members can contribute to one output. Validate all the expected decoded bytes and report any checksum failure.
3. Use `uu c "sample.bin" "sample.bin.gz" -f gz`. Compare the decompressed output against the original file. Header metadata can differ even with identical decoded bytes.
