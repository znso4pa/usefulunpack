# TAR archive (.tar)

1. List the archive to check the top-level paths and identify the intended members. TAR stores multiple entries and can be used without an outer compression stream.
2. Extract only the paths needed. When metadata matters, inspect the expected file types, link handling and modes before claiming the rebuilt tree is equivalent.
3. Use `uu c "stage" "rebuilt.tar" -f tar`. Verify the relative paths and sample payloads. A rebuild can rewrite timestamps or archive metadata.
