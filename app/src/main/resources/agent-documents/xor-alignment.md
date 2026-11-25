# Repeating XOR and key alignment

1. Keep the direction and position of each transform explicit. For a repeating key with period n, two samples only share the same mask at corresponding positions modulo n.
2. `uu tr` uses zero-based offsets relative to its input. `skip` discards a prefix and leaves that offset basis intact. A fresh `uu dd` slice starts at offset zero, so rotate a file-aligned key when applying it to a slice taken from a nonzero phase.
3. Try a small independently verifiable region first. A header that matches while later bytes fail can indicate the wrong phase, period, or scope. Reapply the inverse to a sample to verify the boundary math.
