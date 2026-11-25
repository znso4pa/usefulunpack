# PFS/PF6, RPA, RGSS and LPK repacking

- **PFS/PF6.** `.pfs` can identify distinct variants. Confirm the source variant from its header or an informed project reference. Use `-f pf6` for a confirmed PF6 target and `-f pfs` for the supported PFS/PF8 layout. A shared output suffix only tells you the filename convention.
- **Ren'Py RPA.** List archive paths and check for loose `.rpy` and translation files first. Pack editable or bundled assets with the `rpa` format when the workflow calls for an archive. RPA extraction preserves `.rpyc` as compiled files. Keep internal paths aligned with the engine's `game` directory layout.
- **RPG Maker RGSS.** Match the engine variant `rgssad`, `rgss2a` or `rgss3a`. Keep the engine-required archive basename and use a separate output directory. RPG Maker MV/MZ wrapped media is a one-file operation and uses a separate format family.
- **Lucifen LPK.** The archive filename participates in its key derivation. Keep the intended original name in a different directory when rebuilding or testing it.
- Use `uu fmt` or the local skill catalog for the installed version's read/write support, then reopen and verify samples. Keep the original files until the engine test is complete.
