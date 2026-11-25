# Artemis PFS/PF8 archive (.pfs / .pf8)

1. A `.pfs` filename can contain several variants. Check the header and list entries through the PFS reader. Copy an original path into your selective extraction request.
2. Use `uu c "stage" "rebuilt.pfs" -f pfs` for the supported PF8 writer. The writer is responsible for the index and its payload transformations.
3. Compare restored files rather than encrypted archive bytes. If the engine expects PF6, choose that writer explicitly and verify with its format guide.
