# Partial XOR on a fixed byte range

1. Record the exact affected range and whether the key starts at the range boundary. Check bytes on both sides of the proposed range against a known-good sample.
2. For a confirmed constant XOR over 32 bytes starting at `0x120`, copy the original to a fresh working file. Use `uu dd if=original.bin of=region.bin skip=0x120 count=32`, then `uu tr "region.bin" "xor 0xAA" -o "restored-region.bin"`. Check that the slice is still 32 bytes.
3. Write that verified slice only into the copy using `uu dd if=restored-region.bin of=working.bin seek=0x120`. A positive `seek` changes an existing file. A zero `seek` uses the create-file path, so assemble a new file when replacing the leading range. Check the unchanged prefix and suffix, then validate the complete copied payload.
