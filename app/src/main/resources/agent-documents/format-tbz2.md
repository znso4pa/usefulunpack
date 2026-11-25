# Bzip2-compressed TAR (.tar.bz2 / .tbz2)

1. The bzip2 layer contains a TAR stream. When the signature alone identifies bzip2, confirm the inner TAR contents. A `.bz2` stream can instead contain one ordinary file.
2. Use the archive layer to list and select entries. A decoder error in the outer stream can prevent selection even when the requested TAR path is known.
3. Use `uu c "stage" "rebuilt.tar.bz2" -f tbz2` for a directory tree. Reopen and re-extract a sample. Validate the inner files, since compressed bytes can differ between writers.
