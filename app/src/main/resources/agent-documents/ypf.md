# YPF filename obfuscation and payload checks

- A normal YPF index contains encoded filenames and entry metadata. UsefulUnpack handles supported filename XOR and Shift-JIS decoding through its native reader. Individual entries can contain raw or zlib-compressed data. Inspect these separately before deciding which layer has changed.
- Start with `uu l` and extract a small script and a media entry into staging. If names look wrong, collect the file header, archive version and examples of the name problem. If names are sound and entries look wrong, record which asset types fail, whether any entries decode, and how the decoded files compare to known-good equivalents.
- Pack a supported variant with `uu c "stage" "output.ypf" -f ypf`. The writer emits its supported YPF layout, including name records and payload offsets. Reopen the output, verify that the same paths are present, and validate matching content and game compatibility separately.
- For a confirmed whole-file wrapper, use `uu tr` to write a separate `.ypf` file, then open it with `uu l`. The magic table used by `uu guess` and `--pre` lacks YPF, so the explicit extension is needed for this route. For a confirmed per-file transform, apply `uu tr` to the copied payload only. A new rule that changes name parsing, entry offsets or compression needs independent verification and may need a reader/writer change.

References [GARbro's YPF reader](https://github.com/morkt/GARbro/blob/master/ArcFormats/YuRis/ArcYPF.cs) and [YU-RIS unpacker format notes](https://github.com/mwzzhang/python-YU-RIS-package-file-unpacker).
