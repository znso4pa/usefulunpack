# ZIP, 7z and RAR extraction and repacking

1. Use `ARCHIVE_LIST` to identify the archive and its entries. Keep all volumes together. Open the first 7z or RAR part. For a ZIP with `.z01`, `.z02` and a final `.zip`, open the `.zip`. For byte-split ZIP files named `.zip.001`, `.zip.002` and so on, start at `.zip.001`.
2. If opening or extracting asks for a password, have the user supply it through the local archive UI. Some archives list successfully with protected members. Report missing volumes, checksum failures and password errors as separate cases when the app distinguishes them.
3. Extract an exact entry or directory first. Use `EXTRACT` with `archive`, `output` and `entries`, or `uu x "input.zip" -o "sample" "docs/intro.txt"`. Check the task's terminal result and the expected output file.
4. Run `COMPRESS` with `source`, `output` and optionally `format` to create ZIP or 7z. The current app reads RAR, including supported multi-volume archives. Choose ZIP or 7z if the user accepts a different format for the new archive. Use `uu c` for split ZIP/7z output and check its current help for `-b`.
5. Reopen the new archive. Compare entry paths and sample content hashes after re-extraction. Keep filenames and their encoding intact. If the archive is a deployment package, verify its expected top-level layout before handing it off.

See [7-Zip's format reference](https://www.7-zip.org/7z.html).
