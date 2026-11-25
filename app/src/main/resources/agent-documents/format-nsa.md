# NScripter NSA/SAR archives

1. Both `.nsa` and `.sar` route through the NScripter reader. Confirm variant, file names and the payload's compression before reusing a source extension.
2. Extract and inspect dialogue files or media. Keep filename encoding and path separators consistent with the source entries.
3. Use `uu c "stage" "rebuilt.nsa" -f nsa` for an NSA output. The packer can write stored files or supported LZSS payloads. Verify decoded file content and check an engine's SAR requirement separately.
