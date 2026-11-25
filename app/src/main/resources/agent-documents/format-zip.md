# ZIP archive (.zip)

1. List with `uu l` or `ARCHIVE_LIST`. Check entry paths and name encoding before selecting files. An index can remain readable when member contents require a password.
2. Extract one or more exact entries into a fresh directory. Keep `.zip`/`.z01` companions together, or start at `.zip.001` for byte-split input.
3. Pack with `uu c "stage" "rebuilt.zip" -f zip`. For split output check `-b` in local help. Verify both paths and selected decompressed members. Treat any CRC or password error as a failed member.
