# Archive entry names and encoding

1. A readable payload with broken filenames points to archive metadata. A readable filename with broken dialogue points to the payload. Inspect both separately.
2. ZIP names can involve UTF-8 or legacy name handling. For engine archives such as YPF, the reader assembles name records and decodes their bytes. Test names with non-ASCII characters and preserve normalization, case and relative directories.
3. Correct the reader's filename encoding where supported, then list again before using selective extraction. Compare output names against the engine's references so duplicate or colliding decoded paths remain visible.
