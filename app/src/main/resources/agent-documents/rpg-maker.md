# RPG Maker MV/MZ translation

## Locate editable text

- Check `js/rpg_core.js` and `js/rmmz_core.js` for MV/MZ evidence. Look under `data` or `www/data` for existing translations, map events and common events. Handle asset decoding as a separate operation from dialogue editing.
- For MZ, event code `101` configures Show Text and `401` carries a dialogue line. Code `102` holds the choice array and `402` marks the branch for a choice. Preserve choice ordering and cross-check branch labels. Use the engine/version-specific reference before interpreting other parameters.

## Verify the edit

- Keep record IDs, event command codes, indentation fields, parameter types and escape sequences. In JSON, preserve both the engine-level escape and its JSON string representation. Inspect only translatable fields.
- Parse the resulting JSON, then test a dialogue and a branched choice in the engine if available. For metadata-only inspection, report what was located and where translation would start.

## Upstream references

- [RPG Maker MV event commands](https://rpgmakerofficial.com/product/MV_Help/page/01_10.html)
- [RPG Maker MZ message commands](https://rpgmakerofficial.com/product/MZ_help-en/01_10_01.html)
- [RPG Maker MZ script reference for event codes](https://rpgmakerofficial.com/product/mz/plugin/javascript/script_reference/eventcode.pdf)
