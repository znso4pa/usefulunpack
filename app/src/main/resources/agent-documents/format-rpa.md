# Ren’Py RPA archive (.rpa / .rpi)

1. Use the RPA reader for the supported RPA layouts. The `.rpi` alias can identify the old RPA-1.0 layout, for which extension detection may be needed.
2. List the paths and extract the requested scripts/assets. Keep `.rpyc` identified as compiled files. Look for separate source files before planning a text edit.
3. Use `uu c "stage" "rebuilt.rpa" -f rpa` for the supported writer. It stores member payloads raw. Check names relative to the expected `game` layout and re-extract a sample.
