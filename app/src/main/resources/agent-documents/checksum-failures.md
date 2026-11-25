# Checksum errors and partial extraction

1. Report the exact failing stage and affected members where available. Header corruption, payload checksum failure and authentication failure can produce similar symptoms.
2. Use task status and per-entry counts. A successful archive open does not make every listed file trustworthy. Keep verified members separate from missing or failed ones.
3. Do not feed a failed or truncated output into repacking. Check the original size, volume set and expected password first. Document whether the app removed bad output and verify good samples before retrying.
