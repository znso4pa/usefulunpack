# Artemis PF6 archive (.pf6 / .pfs)

1. Use the PFS reader to list the archive. Confirm the PF6 signature on the source if a compatible repack is needed. The suffix can be `.pfs` or `.pf6`.
2. Pack using `uu c "stage" "rebuilt.pfs" -f pf6`. This PF6 writer stores file payloads raw and builds the matching index and offsets.
3. Reopen and sample the entries. Confirm that the target game actually expects PF6 before replacing an archive with the same suffix.
