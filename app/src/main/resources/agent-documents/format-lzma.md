# LZMA-Alone stream (.lzma)

1. Use `.lzma` for a confirmed LZMA-Alone container. A raw LZMA bitstream may need separate parameters. An XZ file uses a different container and has its own format key.
2. The header can have an unknown decompressed size, so use successful completion and the decoded output as evidence. Inspect the payload before passing it to another parser.
3. Use `uu c "sample.bin" "sample.bin.lzma" -f lzma` for one source file. Recover it into a fresh directory and compare bytes.
