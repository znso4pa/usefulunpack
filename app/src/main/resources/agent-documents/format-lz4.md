# LZ4 frame (.lz4)

1. Check whether the file holds a standard LZ4 frame or an application-specific raw block before choosing the reader. A raw block can need external framing and a decoded size.
2. Decode one payload into staging. Record an explicit frame or checksum error before considering a container mismatch.
3. Use `uu c "sample.bin" "sample.bin.lz4" -f lz4`. Verify the decoded bytes. Match the consumer's expected framing before deploying the output.
