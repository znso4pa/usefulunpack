# XZ stream (.xz)

1. Inspect the XZ container and decode into a staging directory. Differentiate an XZ container from a legacy `.lzma` stream even though both can use members of the LZMA algorithm family.
2. Use the full `.tar.xz` suffix and TAR reader when the decoded file is a TAR archive. Investigate truncation separately from unsupported decoder parameters.
3. Use `uu c "sample.bin" "sample.bin.xz" -f xz`. Test a fresh extraction and compare the original and restored payload by hash or `uu cmp`.
