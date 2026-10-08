# rpa-core test corpus — what these files are and how to check them

These are **real archives produced by real writers**, not hand-made fixtures.
That matters: a self-made fixture fails together with the assumption it
encodes, so every one of these carries a provenance line below and an
independent second opinion.

## These files are not in git

They are **deliberately not distributed** with the repository (engine output /
game data — redistributing them is not ours to do). Keep your own copies at the
paths in the table and `cargo test` uses them. When one is missing, the test that
needs it prints `SKIP …` and passes — so a fresh clone and CI still build, but
**green there no longer means the real-file assertions ran**: run the corpus
locally (and out-of-tree, below) before trusting a format change.

## Files

| File | Written by | Why it is here |
|---|---|---|
| `official-renpy-8.5.3.rpa` | **Ren'Py 8.5.3 SDK** (`distribute` → `launcher/game/archiver.rpy`), the engine's own writer | The ground truth. Protocol-5 pickle, plain dict/list index, `SHORT_BINBYTES` empty `start`, fixed key `0x42424242`, `XOR`-ed offsets. Contains CJK names, a name with a space, a nested path, an empty file and a 2 KB binary. |
| `rpatool-v3-deadbeef.rpa` | [rpatool](https://github.com/Shizmob/rpatool) `-c -3 -k DEADBEEF` | A second, independent writer. Protocol 2 (`BINPUT`/`BINGET` memoization), **2-tuples with no `start` field**, and — because the key exceeds 2^31 — `LONG1`-encoded index values. This is the shape a fixture made with the official writer would never exercise. |
| `rpatool-v2.rpa` | rpatool `-c -2` | `RPA-2.0`: index is not XOR'd at all. |
| `v1.rpi` | this project's oracle, implementing `renpy/loader.py`'s RPA-1.0 rule | `RPA-1.0` is a `.rpi` whose *first bytes* are the zlib'd pickle index and whose offsets point into the same file. No real-world sample was available, so this is spec-derived — the reader's claim for 1.0 is "matches the engine's reader", not "seen in the wild". |
| `truncated-v1.rpi` | `v1.rpi` with the data tail cut off | Because a 1.0 index sits at the front, truncation leaves a *readable* index whose chunks point past EOF. Exercises the bounds check — the thing that stops a silently truncated file from landing on disk. |

`RPA-3.2`/`RPA-4.0` (byte-compatible with 3.0) and `ALT-1.0` (key/offset in the
opposite order, key XOR'd with `0xDABE8DF0`) are implemented too; both are
spec-derived from `unrpa`'s handlers and have no committed fixture.

`ZiX-12A`/`ZiX-12B` are **refused by name**: their key comes from the game's
`renpy/loader.pyo`, which is out of scope.

## Regenerating

The Ren'Py fixture came from the SDK, driven headlessly on macOS:

```bash
# 1. a project whose options.rpy archives everything under game/
#    (build.classify("game/**", "archive") — the default declares an archive
#    but classifies nothing into it, so an empty project produces no .rpa)
SDL_VIDEODRIVER=dummy SDL_AUDIODRIVER=dummy \
  renpy-8.5.3-sdk/renpy.sh renpy-8.5.3-sdk/launcher distribute "$PROJECT" \
  --destination /tmp/dist --no-update
# 2. the archive is inside the Linux package: game/archive.rpa
tar -xjf /tmp/dist/*-linux.tar.bz2 -C /tmp/ex && cp /tmp/ex/*/game/archive.rpa .
```

The rpatool fixtures:

```bash
rpatool -c -3 -k DEADBEEF rpatool-v3-deadbeef.rpa hello.txt empty.txt '中文名字.txt' \
        'with space.txt' sub/nested.txt small.bin
rpatool -c -2 rpatool-v2.rpa <same files>
```

## Checking a real archive out-of-tree

`cargo test` covers the committed fixtures. For a real game archive, run the
harness against the Python oracle (kept outside the repository on purpose —
same pattern as the rar/7z corpora):

```bash
# index agreement (names, sizes, part offsets)
cargo run -p archive_rpa-core --example manifest -- manifest game.rpa > rust.json
python3 oracle.py manifest game.rpa py.json      # oracle: renpy/loader.py logic

# content agreement, byte for byte
cargo run -p archive_rpa-core --example manifest -- extract game.rpa /tmp/rs
python3 oracle.py extract game.rpa /tmp/py
diff -r /tmp/py /tmp/rs                             # must print nothing
```

`unrpa` is a useful third opinion, **except** for multi-part entries: it keeps
only the first part of each entry and silently drops the rest, so a
multi-part archive extracts *differently* under it. `renpy/loader.py` joins
every part in order, which is what this crate does — the engine is the only
consumer that has to be right.

Every fixture above was verified that way: 12 archives, all names/sizes/offsets
identical, all extracted trees byte-identical.
