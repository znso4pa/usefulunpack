# int-core test corpus — what these files are and why they are not in git

Real files from a real game plus the sample set of an independent decoder (see
`crates/int-core/src/lib.rs` for how each is used). They are **deliberately not
distributed** with the repository: `ptcl.int` is an engine's own archive and
`fakegame.exe` is the game executable that keys it, so redistributing them is
not ours to do.

| File | What it is | Why it is here |
|---|---|---|
| `ptcl.int` | a CatSystem2 KIF archive from the `arc_unpacker` sample set | The ground truth for the reader: 13 entries, an encrypted `__key__.dat` field, names/offsets/payloads an independent decoder also produces |
| `fakegame.exe` | the game executable the archive's keys are derived from | `key_code` / `v_code` / `v_code2` resources — without it the reader must refuse and the writer must fall back to the plain variant |
| `expected/*.kcs` | the archive's entries as extracted by `arc_unpacker` | Byte-for-byte reference for extraction (13 files) |

Missing files make the tests that need them print `SKIP …` and pass, so a fresh
clone and CI still build — but **green there no longer means the real-file
assertions ran**. Keep your own copies here (a CatSystem2 game's `*.int` plus its
`.exe`, and a decoder to produce the reference outputs in `expected/`) and run
`cargo test -p archive_int-core` locally before trusting a KIF change.
