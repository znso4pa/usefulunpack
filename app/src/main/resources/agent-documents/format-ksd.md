# KiriKiri KSD (.ksd)

1. Inspect the KSD with its reader and extract the single text output. The native codec understands supported UTF-16LE storage modes and writes decoded text as UTF-8.
2. Check line endings and meaningful characters in the decoded text before editing. Keep the original KSD if the engine expects metadata or a storage mode beyond the writer's output.
3. Use `uu c "save-copy.txt" "rebuilt.ksd" -f ksd` with UTF-8 text. Re-extract and compare text, then test the application that reads it.
