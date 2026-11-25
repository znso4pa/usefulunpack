# Detect and recover missing archive volumes

1. Group matching siblings by basename and numeric part. Look for gaps, an absent final ZIP directory part, duplicated part numbers or a small download-error file posing as a volume.
2. Keep the set together and open the expected entry point. For a `.zip.001` byte split use the first part. For a `.z01` set keep the final `.zip`; for 7z/RAR choose the first part.
3. Use the archive reader's volume support. Verify the set's integrity before extraction. Join raw byte splits only when the format is confirmed to be a simple split and the order and boundaries are known.
