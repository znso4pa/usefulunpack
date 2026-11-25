# Brotli stream (.br)

1. Brotli lacks a strong fixed leading signature for this workflow. Use a trusted original suffix, a known producer or external metadata such as Content-Encoding before choosing the decoder.
2. Use `uu x` for the single stream. Verify the restored payload separately by hash or format parser. A guessed suffix is weaker evidence than successful validation.
3. Use `uu c "sample.bin" "sample.bin.br" -f br`. Verify it by independent decode when compatibility with an HTTP or application consumer matters.
