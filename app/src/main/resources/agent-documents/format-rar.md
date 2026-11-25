# RAR archive (.rar)

1. Keep the original split set together and start with the first part. Note whether listing fails before revealing entries or extraction fails on a named entry.
2. Ask the user to supply the local password if necessary. RAR archives with protected contents can expose their names while still rejecting member reads. Check per-member failures and available verified outputs after a CRC failure.
3. RAR is an extraction format in UsefulUnpack. If the downstream workflow accepts another format, stage the verified files and use ZIP or 7z for the new archive.
