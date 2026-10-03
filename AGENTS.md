# AGENTS.md

Instructions for AI coding agents working in this repository.

UsefulUnpack = Android app (Kotlin) + Rust cdylib per archive format, glued by JNI.
Human-facing docs: [README](README.md) · [CONTRIBUTING](CONTRIBUTING.md) (中文: [CONTRIBUTING-zh](CONTRIBUTING-zh.md)) · [TODO.md](TODO.md) (changelog + roadmap, source of truth).

## Commands

```bash
bash build.sh                      # full build: Rust cross-compile (3 ABIs) -> jniLibs -> assembleRelease -> ./UsefulUnpack.apk (needs NDK)
./gradlew :app:assembleRelease     # Kotlin-only changes (output lands in app/build/outputs/apk/release/ — NOT the root APK)
cargo test --workspace             # Rust suite — must stay green
./gradlew lintDebug                # baseline: 0 errors / 271 warnings — NEVER introduce new errors
```

> `build.sh` is the only script that refreshes the root `UsefulUnpack.apk`. A bare
> `:app:assembleRelease` does not, so `adb install UsefulUnpack.apk` after one
> silently ships the PREVIOUS build.

## Non-negotiable architecture invariants

1. **One global progress/CANCEL slot per native library on the Rust side** (`progress_store!` in `crates/common`). Therefore EVERY archive operation must go through:
   ```kotlin
   val opH = tryStartOperation(activity, fmtKey)   // never blocks/refuses; queues instead
   thread {
       if (!opH.await()) return@thread             // cancelled while queued → abort silently
       try { /* work */ } finally { opH.release() }
   }
   ```
   Same `fmtKey` serializes; different keys share 3 slots. Composite two-format flows use pseudo-key `"merge"`; zip entry edits use `"zip"`.
   **Keys that share a `.so` MUST also share a slot** — go through `schedulerKeyOf()` in `archive/ArchiveExtractor.kt`, never the raw key. Three such families exist: `pf6`→`pfs`; the five tar variants (`tar`/`tgz`/`tbz2`/`txz`/`tzst`)→`tar`; and all eight `rgss*`/`rpgmv*`→`rgss`. A per-format `.so` with one `compress_progress` static, polled by several keys, means the second op's `reset(total)` wipes the first one's total and the second op's cancel aborts the first. This already happened twice (pf6, then the tar family) — the `schedulerKeyOf` comment lists the families; keep it accurate when adding a format.
2. **Password prompts BEFORE `tryStartOperation`** — modals block ~30 s and must never hold a slot/format lock.
3. Every new JNI operation entry calls `clear_cancel()` first, or the previous op's cancel poisons it.
4. Cancel isolation: queued-cancel must NOT set the format-global CANCEL flag (it would kill an unrelated running same-format op). Use `PollingProgressDialog.cancelQueuedThenNotify()` semantics.
5. Every `runOnUiThread` block that touches UI (Toast, AlertDialog, Progress.dismiss, etc.) MUST start with `if (isFinishing || isDestroyed) return@runOnUiThread`. When a `prog.dismiss()` appears in the same block, place it AFTER the guard to avoid dismissing on a destroyed Activity.
6. Deliberate legacy exceptions — do NOT migrate without revisiting rationale: delete/recycle flows and signature scan stay on old `OperationLock`.
7. `viewPager.offscreenPageLimit = MAX_TABS - 1` keeps all fragments alive; don't lower it.
8. **Derived UI state, never stored**: anything a tab shows about its own state must be a function of that state. `TabState.displayPath()` exists because `tvPath` was written in only one of the three places a preview gets rendered — a rebuilt fragment left the bar at the layout default `/`. Same rule for `syncMergeButton()` and the merge target list, which share `mergeTargetGroups()` so the button and the picker can never disagree.
9. **Semi-transparent drawing needs its own layer.** Painting segments straight onto a persistent bitmap blends each one into the previous (`SRC_OVER`), so a 50% stroke gets darker the longer it is drawn and blobs at the joints. `ImageEditorView` renders each stroke at FULL alpha into `activeLayer` and applies that stroke's alpha once at composite time; the stroke records its own colour/alpha/width so re-rendering history (undo) cannot restyle it.
10. **One source of truth per dispatch table.** Duplicated `when (format)` blocks drift. The archive listing dispatch is `listEntriesJson()`; merge availability is `mergeTargetGroups()`. When you add a format, add it there and let the others forward.

## Known traps

- ⚠️ **Package ≠ directory**: `archive/ArchiveExtractor.kt` and `archive/ExtractProgress.kt` sit under `archive/` but declare root package `com.usefulunpacker`. Import their symbols explicitly from other packages (`com.usefulunpacker.archive.OpScheduler` style). Same class of quirk may exist elsewhere — trust `package` lines, not folders.
- **Batch-bar buttons**: visibility matches semantic `tag`s (`"extract"`/`"preview"`/`"compress"`) from `buildBatchBar`, checked in `syncMultiBar`. NEVER match display text; emojis exist only in string resources, never concatenated in Kotlin.
- **Cross-tab multi-select**: selections persist per-tab (`TabState.multiSelected`); batch ops aggregate via `allSelectedFiles()` / `totalSelectedCount()`. Cancel clears ALL tabs via `exitAllMultiSelect()`. Tab badges auto-refresh via `syncAllTabAdapters()` + `tabAdapter.notifyDataSetChanged()`.
- **Async completion dialogs**: any `runOnUiThread { ... AlertDialog ... }` after background work requires `isFinishing || isDestroyed` guard (BadTokenException class — exterminated twice).
- **Honor/EMUI ROM**: custom ScrollView/TextView/EditText must not enable native scrollbars (ROM NPE in `onDrawScrollBars`).
- ⚠️ **An opaque background kills the user's wallpaper**: the wallpaper is drawn on `R.id.root`, so any opaque view sitting on top of it hides the wallpaper completely. 4.x was single-window, where clearing `panel` was enough; the multi-window layout then added `viewPager` / `folderRoot` / `previewRoot` / `tabBar`, and `applyBackgroundImage` was never updated — **the wallpaper stopped showing anywhere**, and every opaque layer added later silently re-broke it again. Rules: (1) the yield tables `BACKDROP_TRANSLUCENT` / `BACKDROP_TRANSPARENT` / `BACKDROP_ORIGINAL` live in `ui/AppSettings.kt`; any view that introduces an opaque background must be registered in them; (2) `panel` / `pathBar` / `bottomBar` / `folderRoot` / `previewRoot` exist once PER TAB, so they must be handled by walking the tree (`refreshBackdrop()`) — `findViewById` only ever reaches the first one; (3) `FolderFragment` must call `applyBackdropInTree()` on **its own root** at bind time, NOT `refreshBackdrop()` — in `onCreateView` the fragment's view is not yet attached to the ViewPager, so a walk from `R.id.root` cannot reach it (shipped that way once; only the tab strip showed through). Also, `applyBackgroundImage` must not bail silently when `root.width == 0` on the first frame — retry one frame later instead. After touching any of this, set a real wallpaper on-device and confirm all four areas show through: tab strip / toolbar / file list / preview.
- New strings go to ALL FOUR locales: `values/`, `-zh-rCN/`, `-zh-rTW/`, `-ja/`. Check with a set comparison, not eyeballing — `values-zh-rTW` had drifted 7 `<string>` and 1 `help_tutorials` item behind for a long time. In XML, `'` must be `\'` (AAPT2 fails obscurely otherwise) and a help entry MUST carry `Title\nBody` or `parseItem` renders the whole thing as a bold title with no body.
- Android string resources also carry a `\\n` (literal backslash-n) style in older entries; both appear in `strings.xml`. Match the style of the array you are editing instead of normalizing the file.

## The lesson that keeps costing the most

**Self-made fixtures fail together with the assumption they encode.** Every one of these shipped green locally and was caught only by real data:

- An M4A fixture whose `ftyp` minor version was the same wrong guess as the code (`0.0.2.0`; real files use `0.0.0.0`).
- A 24-byte probe window that stopped one byte short of the `moov` box, so a real `.rpgmvm` was rejected as invalid.
- A hand-typed obfuscated fixture whose 16-byte header did not match `RPGM_HEADER` (which carries `03 01`), so the reader passed it through as "not obfuscated" and the test still went green.
- An M4A validation that searched for a box type at the position implied by the value it had just derived — unfalsifiable by construction.

When adding a format, **get real samples and check them against an independent oracle**, and prefer byte-identity over "it extracted without error": re-packing must reproduce the original file exactly. See the RPG Maker corpus note below.

## Real-file regression corpora

| Corpus | What it gives | Oracle |
|---|---|---|
| `files4testing` (see `CONTRIBUTING.md`) | ~423 vectors + 13 injected faults across 14 formats | extraction hashes; faults must reject cleanly |
| **uuksu/RPGMakerDecrypter** test data | real `Game.rgssad` / `.rgss2a` / `.rgss3a`, and real `.rpgmvp`/`.rpgmvo`/`.rpgmvm` assets (kept WITHOUT extensions) | exact offset/size/key per entry; `MD5("12345")` as the MV keystream; SHA-1 of each decrypted asset |
| binwalk 3.1 samples | signature-scan agreement | 201 semantic differences, all favorable |

Download the RPG Maker files with `curl` from `raw.githubusercontent.com/uuksu/RPGMakerDecrypter/master/RPGMakerDecrypter.Tests/`. A harness that exercises the parser from OUTSIDE the workspace is preferable — `crates/rgss-core` is an `rlib` as well as a `cdylib`, so a scratch crate can `path`-depend on it and leave the repo untouched. The env-gated `examples/probe.rs` is the on-device version of the same idea.

## Conventions

- Commits: conventional prefixes; releases are single narrative commits `feat(vX.Y.Z): …` + dedicated TODO.md section + `versionCode`/`versionName` bump in `app/build.gradle`.
- `TODO.md` is the changelog AND roadmap — append a bullet under the current dev section for behavioral changes.
- Format listing JSON contract: `[{"n":name,"s":size,"d":isDir,"e":encrypted}]`; selective extraction takes newline-separated paths with exact + directory-prefix matching. A forwarded selection argument must be forwarded on EVERY branch: dropping it silently turns a selective extract into a full one.
- Rust JNI style: compact single-line functions wrapped in `guarded()`; reuse `archive_common::{s, json_escape, safe_join}`.
- Progress reporting: feed `extract_progress::reset/set_file/add_bytes` (packing: `compress_progress::*`). Whole-member buffered decodes feed the top bar from the WRITE side (`ProgressWriter::extract`) so totals stay exact.
- **Pick ONE progress caliber per format and state it in a comment.** Two exist: WRITE side (`total` = uncompressed size, `bytes` = bytes written — exact, but the total is unknowable whenever the header doesn't store it) and READ side (`total` = archive size, `bytes` = archive bytes consumed — always has a denominator). Since `.lzma` headers carry `uncompressed_size = -1` in real files and zstd/brotli/xz frames don't store a size at all, **the five single-file streams (brotli, bzip2, xz, zstd, lzma) use the READ side**; gzip keeps the WRITE side for single members (exact and cheap) and uses READ for concatenated members. A format that does `reset(0)` and only feeds output bytes shows a spinner for the whole operation — `ExtractProgress.kt` treats `total <= 0` as indeterminate.
- **`ProgressReader` implements BOTH `Read` and `BufRead` — never wrap it in a `BufReader`.** `BufReader` satisfies reads from its own 8 KB buffer, whose `fill_buf` does not count and whose `consume` only fires on consumption, so the counter drifts *both* short of and (via readahead) *past* the real size. Correct nesting is `BufReader::with_capacity(64 KiB, ProgressReader::extract(file))` — `ProgressReader` wraps the raw file.
- **`clear_cancel()` starts an operation, so it must zero the counters too — not just CANCEL.** Every format does real work before its own `reset(total)`: zstd `fs::read`s the whole archive, rar opens the reader and walks every member, tar decompresses the entire outer stream in pass 1. The UI poll loop paints the statics throughout that window, so leaving the previous operation's `bytes == total` in place renders a confident, fabricated **100%** for the whole pre-scan — and only when a second operation follows a first, which is why it read as intermittent. `total = 0` is the right signal there: `ExtractProgress.kt` renders `total <= 0` as indeterminate ("preparing"). `reset()` deliberately still preserves CANCEL, because a cancel pressed during the pre-scan must survive into the extraction phase; the two jobs are not interchangeable.
- **A move that reads AND writes the same volume needs 2× the byte total.** The recycle bin's cross-volume fallback copies the tree and then deletes the originals, so reporting the copy as 0..100% left the bar full while `deleteRecursively()` ground through the tree with nothing to show. Progress that reaches 100% before the work is over is worse than no progress bar.
- **A checksum failure must delete the output, not just report it.** `unrar` leaves **zero** files when it rejects a corrupt member; a decoder that returns `Err` while leaving the decoded bytes on disk gives the user an error toast *and* a plausible-looking corrupt file, and any later tool may pick that up. In rar-core the per-member checksum is verified *inside* the vendored reader — i.e. **after** `rar_writer`'s factory created the destination and the bytes were written — so nothing unlinked it. Measured: 55 of 70 oracle-detectable split-member mutations left output behind. The fix routes on **error kind**, because the three kinds imply different scopes: `Error::AtEntry { name }` deletes that member only (unrar keeps the members that verified — 16 of 17 on a real fixture, so deleting everything would be *more* destructive than the reference); an unattributable error such as `WrongPasswordOrCorruptData` (which the vendored `entry_error` returns **without** an `AtEntry` wrapper) deletes everything the run wrote; `Error::AtArchiveOffset` deletes nothing, since a header-CRC failure means we aborted and produced nothing while unrar recovers. Compare `unrar x` leftovers against ours as a **set**, not a count — unrar keeps good members, so "any leftover" is the wrong bar.
- **A unit test for a vendored-fixture-only code path may not be reproducible end-to-end.** rar-core ships no RAR3/RAR5 writer, and those are exactly the families that write before verifying; the only family the vendored writer can build (RAR 1.3/1.4) verifies *before* writing, so a checksum failure never creates a file and cannot reproduce the bug. Such a test must assert the decision logic directly and say so, with the end-to-end proof in an out-of-tree harness. And **sabotage it both ways**: no-op cleanup must fail the test, and over-broad cleanup must too. A first sabotage that only deleted a `return` while the guarding `if` remained proved nothing — the test stayed green.
- **A decoder that can fail must delete its partial output.** `File::create(&dest)` runs before any content is validated, so a corrupt stream leaves a truncated file behind that looks like a successful extraction to both the user and any later "did it work?" check. Wrap every error path in a `fail()` closure that calls `remove_file`.
- **Check third-party decoder limits against real data.** `ruzstd`'s `DEFAULT_MAX_WINDOW_SIZE` is 100 MB, but zstd level 22 writes `window_log = 27` (128 MB) — every `.zst-22` sample was rejected while the system `zstd` decoded it fine. Call `new_with_max_window_size` explicitly; keep it bounded, since the window ring buffer is allocated from the header before any content is validated.
- **A filename encoding is not determined by the flag alone — real writers ignore the flag, so decode like the reference tools do.** APPNOTE says ZIP bit 11 (EFS) clear ⇒ CP437, and the vendored reader followed that literally. Info-ZIP `zip` writes UTF-8 bytes **without** setting EFS, so every CJK name in such an archive came out as CP437 mojibake — measured on a real archive: `第一章` (UTF-8 `E7 AC AC E4 B8 80 E7 AB A0`) decoded to `τ¼¼Σ╕Çτ½á`, while `unzip` 6.00 decoded all three such entries correctly. `unzip`, 7-Zip and Explorer all heuristically prefer UTF-8 for the unflagged case, so the rule is: EFS set ⇒ UTF-8; otherwise try UTF-8 and fall back to CP437 only when the bytes are not valid UTF-8 (pure ASCII is valid UTF-8 and decodes identically, so nothing that used to work changes). **Two** parsers decode names — `read.rs` (central directory; every entry point here) and `types.rs` (local header, behind the public `read_zipfile_from_stream`); the helper lives in `cp437.rs` so they cannot drift. Sabotaging the `types.rs` site alone turns nothing red, so it is fixed for the streaming API's sake and is **not** claimed as covered — the test exercises `read.rs` via `extract_zip_host` *and* `list_zip_host`, because those are two different code paths and fixing only one leaves listing showing mojibake. Test both directions: reverting to unconditional CP437 must fail, and over-fixing to unconditional UTF-8 must also fail (it would lose the legitimate CP437 archives).
- **A stored checksum's COVERAGE is part of its definition — and coverage can change with encryption.** rar-core verified every `Rar50Plus { crc32 }` against the *unpacked* data. That is right for a plain RAR 5 member and **wrong for an encrypted one**, where the stored value is a keyed MAC: `keys.mac_crc32(crc32(unpacked))`, which the vendored reader's `verify_integrity_with_keys` applies and the public `verify_crc32` explicitly refuses to. Measured on `rar` 7.23 output: stored `0x14c7d4b9`, CRC32 of the correct plaintext `0x2364528c` — and `0x2364528c` was exactly what the decoder produced, so the plaintext was right and only the *comparison* was wrong. Every password-protected RAR 5 archive, i.e. the most common galgame distribution shape, failed to extract. RAR 1.5–4.x is genuinely different: its `crc32` is the unpacked CRC even when encrypted (`readme_154_password.rar`, stored and computed both `0x509e5e3c`), so the skip is per-family and driven by `uses_hash_mac()`, which exists only in the RAR 5 module. The rule is factored into `verify_here()` and unit-tested per family × encrypted × split, because the bug cannot be reproduced from a fixture: no RAR3/RAR5 writer exists in the vendored fork and `ArchiveMemberMeta` is `#[non_exhaustive]`.
- **An oracle that fails on BOTH sides still reports "consistent" — so the corpus must prove it can fail.** `raroracle` decided an archive needed a password by asking whether `unrar lb` succeeded without one. Data-encrypted RAR 5 with *visible headers* lists fine that way, so the harness passed an empty password; unrar then refused, we refused, and the pair was scored as agreement. The whole data-encrypted class — the mainstream shape — was never actually exercised, and the bug above shipped green through it. Decide "is this encrypted" from the archive's own **flags** (`unrar vt` prints `Flags: encrypted`), never from whether an unauthenticated operation happens to succeed. Proof the harness now bites: reintroducing the bug drops it 119/123 → 118/123 and names the file.
- **A corpus you generate is only as good as its assertions, and `rar`/`zip`/`7z` will happily build you a valid EMPTY archive.** Two fixture bugs in one sitting, both silent: `open('big/x.bin','wb')` does not create `big/`, so the Python seed aborted and `rar a` produced a well-formed archive of nothing — the whole matrix degenerated to "zero samples, all green". And the "12 MiB incompressible" file was one 64 KiB chunk repeated 192 times, which LZ77 collapsed to 27 KB, so `-v2m` never reached the split threshold and the volume tests never ran a single volume. `rar`, `zip` and `7z` do not error on any of this. Generate with the real tool, then assert per archive: entry count, minimum byte size, that the incompressible asset is actually incompressible (`zlib` ratio), and that the split/recovery volumes really split. Symptom of skipping this: an "encrypted" corpus where every archive extracts to nothing.
- **Verify a container's stored checksums, and check the algorithm per family — they are not variants of one CRC.** rar-core verified nothing, so a tampered member decoded "successfully" and left plausible-but-wrong bytes on disk while `unrar t` reported `checksum error`. RAR 1.5–5.x store a full CRC-32 of the *unpacked* data; RAR 1.3/1.4 store a separate 16-bit `sum(bytes).rotate_left(1)` — treating it as `crc32 & 0xffff` rejects every legitimate 1.3/1.4 archive. And a **split** member's stored checksum covers only the FIRST volume's slice, so verifying the reassembled output against it rejects every legitimate split archive. Both mistakes shipped green locally and were caught only by real data plus a "the pristine archive must still extract" precondition — keep that precondition in any checksum test.
- **A per-entry guard needs the caller's error counter, not its own.** rars' writer factory hands out `Box<dyn Write + 'static>`, so a verification guard cannot borrow the caller's stack `AtomicU32`; it needs an `Arc<AtomicU32>` **shared with the caller**. Giving the guard a fresh counter is a silent no-op: the corrupt file gets deleted but the entry still reports success, and the archive-level result still reads `Ok((total, 0))`.
- **`Ok((total, errors))` from a container is not success — check `errors > 0 || total == 0`.** zip/7z/rar all take a per-entry `fail += 1` branch and still return `Ok`. A regression harness that only asserts `is_err()` will pass while the app tells the user everything worked.
- RPG Maker specifics worth remembering: RGSS v1 and v2 share one layout (VX is written as v1, keyed off the extension); a packed archive the engine will open must be named `Game.<ext>`, and there is no usable fallback name if that slot is taken. MV/MZ obfuscation XORs only the first 16 asset bytes behind a 16-byte `RPGMV` header, so without the game's key the header is *reconstructed* per container — and the M4A minor version is a reconstruction, not a recovery.

## Map of non-obvious places

| Thing | Where |
|---|---|
| Scheduler / lock / queue / slot collapsing | `archive/OpScheduler.kt`, `schedulerKeyOf()` in `archive/ArchiveExtractor.kt` |
| Progress UI (OpOverlay floating layer + PollingProgressDialog) | `archive/ExtractProgress.kt` |
| Format dispatch + password retry flows | `archive/ArchiveExtractor.kt`, `extract/PreviewFlow.kt` (largest file) |
| Archive listing dispatch (single source) | `listEntriesJson()` in `extract/PreviewFlow.kt` |
| Cross-archive merge (targets, staging, repack) | `mergeTargetGroups()` / `mergeIntoArchive()` in `extract/PreviewFlow.kt` |
| Tab state / multi-window / path bar | `browse/TabState.kt` (`displayPath()`), `browse/FolderFragment.kt`, `MainActivity.kt` |
| Image editor (brush alpha layering) | `ui/ImageEditorView.kt`, `ui/ImageEditorDialog.kt` |
| RPG Maker MV/MZ key + extension rules | `RgssCore.kt` (`mvRequiredExt`, `mvExtMismatch`, `PREF_MV_KEY`) |
| Recycle bin | `fileops/RecycleBin.kt` (rename-first fast path; copy fallback reports per-file progress; copy failure must abort BEFORE deleting originals) |
| Wallpaper / backdrop yielding | `applyBackgroundImage` + `refreshBackdrop()` + `applyBackdropInTree()` in `ui/AppSettings.kt` (yield tables and the registration rule for new opaque layers: see Known traps) |
| Signature scan engine | `crates/scan-core` (binwalk-style; validated against binwalk 3.1 corpus) |
| RPG Maker RGSS / MV-MZ codec | `crates/rgss-core` (+ `examples/probe.rs` for on-device diagnosis) |
| Vendored forks | `crates/vendor/` (rars, sevenz-rust, isomage, zip, xp3) |

## Verification before finishing a task

1. `cargo test --workspace` green (if Rust touched)
2. `./gradlew lintDebug` — no new errors (if Kotlin/resources touched)
3. `bash build.sh` completes (if JNI surface changed)
4. If a format or codec was touched, run the real-file corpora above — synthetic round-trips are necessary, not sufficient
5. Behavioral checklist for UI-sensitive changes: rotation survival, cancel mid-op cleanup, parallel smoke (different formats overlap; same format queues; queued-cancel isolates), Honor scrollbar avoidance
6. Four-locale string set comparison (same key set in all four `strings.xml`, and every `help_tutorials` item has a `Title\nBody` split)
