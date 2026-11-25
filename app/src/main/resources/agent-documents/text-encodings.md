# Text encoding, BOM and line endings

1. Inspect the extracted file's bytes and declared or known encoding. Distinguish UTF-8, UTF-16LE/BE, Shift-JIS and GBK using representative characters. An ASCII-only sample can look valid under several encodings.
2. Use `uu cat <file> -e <encoding>` for a bounded local preview. `uu enc <file> <from> <to> <out>` converts a copy; it preserves a source BOM for supported output encodings. Use the original format on repack unless you verified the engine accepts a new one.
3. Check CRLF/LF, control tags and placeholders on a small sample. Stop before writing if the decoded text contains unexpected replacement characters or the file behaves like compiled data.
