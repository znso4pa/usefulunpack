# Bzip2 stream (.bz2)

1. The `BZh` header identifies a standard bzip2 stream. Decide whether it contains an independent file or an inner TAR archive before asking for archive entries.
2. Decode into a fresh output directory. A file produced during a failing decode is not a valid recovered payload. Use terminal status and a local verifier.
3. Use `uu c "sample.bin" "sample.bin.bz2" -f bz2` for one input file. Re-extract and compare bytes. Use the `tbz2` key for a TAR tree compressed with bzip2.
