# Gzip-compressed TAR (.tar.gz / .tgz)

1. Use the full compound suffix when working with a confirmed TAR payload. A `.gz` file can also be a compressed single file, so inspect names inside the decoded TAR before treating it as an archive.
2. List and extract from the TAR stream using `uu l` and `uu x` with selected member paths. If the actual payload is a standalone file, extract that single gzip stream.
3. Use `uu c "stage" "rebuilt.tar.gz" -f tgz`. Reopen the new archive as TAR and verify that the requested entry paths match.
