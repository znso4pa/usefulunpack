# CatSystem2 INT/KIF archives

1. Check the KIF signature, the index and whether the archive depends on metadata from the original game folder. Keep the original executable beside the source when needed.
2. List and extract a representative member. If the index cannot be decoded, report the missing or unsupported context before trying payload transforms.
3. Use `uu c "stage" "rebuilt.int" -f int` for the supported writer. Output placement can affect whether it can find the original game metadata and select the intended variant. Verify the variant and content separately.
