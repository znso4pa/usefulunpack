# RPG Maker VX Ace RGSS archive (.rgss3a)

1. Identify the VX Ace project and use RGSS listing to inspect its version 3 archive entries. Keep filenames and case consistent with the source.
2. Extract a sample asset first, then the requested files. Verify failed entries separately from successfully extracted entries.
3. Use `uu c "stage" "rebuild/Game.rgss3a" -f rgss3a`. Reopen the archive and compare member bytes. Archive header and extension must agree with the engine target.
