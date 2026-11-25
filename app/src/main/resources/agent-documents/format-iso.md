# ISO disc image (.iso)

1. Open the image and list the filesystem tree before extracting. Distinguish extraction of files from preservation of the original sectors, boot records or disc layout.
2. Extract individual paths or a tree into staging. Preserve the original image if its boot or disc metadata is significant.
3. Use `uu c "stage" "rebuilt.iso" -f iso` for an image built from files. Validate by reopening the image and examining the consumer's filesystem requirements.
