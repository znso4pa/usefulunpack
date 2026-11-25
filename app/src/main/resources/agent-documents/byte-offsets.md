# Byte offsets, lengths and range reads

1. Use zero-based byte offsets. Write down the origin, absolute offset, length and end-exclusive byte before carving. Convert hexadecimal and decimal once and check `offset + length` against the source size.
2. `uu hex "sample.bin" 0x120 32` previews 32 bytes. `uu dd if=sample.bin of=region.bin skip=0x120 count=32` copies the range to a separate file. With a scan descriptor such as `f3`, offsets are relative to the descriptor itself.
3. `uu dd` can clamp a requested count at EOF, so verify the carved size against the expected range. Use a manifest, header offset or next independently validated boundary as evidence for the intended length.
