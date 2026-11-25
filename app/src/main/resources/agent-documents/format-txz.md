# XZ-compressed TAR (.tar.xz / .txz)

1. XZ detection identifies the compression stream. Check that its payload is TAR before requesting individual paths. Preserve the full suffix on copies used by the archive reader.
2. List the inner archive, record any outer integrity failure separately, and use exact TAR paths for selective extraction.
3. Use `uu c "stage" "rebuilt.tar.xz" -f txz`. Check free space for the source, output and verification copy. Compare a sample of unpacked entries.
