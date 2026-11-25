# Zstandard-compressed TAR (.tar.zst / .tzst)

1. Confirm the TAR payload under the Zstandard stream. Treat unsupported frame requirements and malformed TAR headers as distinct findings.
2. Try listing the TAR paths and check the terminal status during selective extraction. Nested stream processing can still read a large part of the input.
3. Use `uu c "stage" "rebuilt.tar.zst" -f tzst`. Reopen through the TAR reader and compare relative paths and content hashes.
