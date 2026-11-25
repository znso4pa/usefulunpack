# Filename extension and file signature disagree

1. Record the suffix, detected magic and archive parser outcome separately. `.arc`, `.pfs` and `.bin` can name incompatible formats. Some valid formats depend on the suffix because the probe lacks a strong fixed magic.
2. Use `uu info`, `uu hex` and a small listing. If the leading bytes differ from a known-good sample, check for a prefix, another container or a supported reversible whole-file transform.
3. If you rename a diagnostic copy, choose the candidate suffix and prove it by opening the index and reading an entry. Preserve the original name for filename-dependent formats such as LPK.
