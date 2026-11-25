# 7z archive (.7z)

1. List the archive or the first `.7z.001` part with its siblings present. Header protection can require a password even for listing. If only contents are protected the entry list can still be visible.
2. Test a representative exact entry. In a solid archive a small selection can still require decoding a larger block, so observe the task state before resubmitting.
3. Pack with `uu c "stage" "rebuilt.7z" -f 7z`. Reopen the output, verify selected members after extraction and test the set of volumes together if split.
