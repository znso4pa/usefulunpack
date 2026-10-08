# Contributing to UsefulUnpack

[**English**](CONTRIBUTING.md) | [**中文**](CONTRIBUTING-zh.md)

> **TL;DR** — `bash build.sh` to build, `cargo test --workspace` + `./gradlew lintDebug` before every PR,
> conventional-commit prefixes on titles, and **read the [Multi-window & Concurrency](#multi-window--concurrency-architecture-must-read) section once** — v5.14 introduced real parallelism and there are contracts you must not break.

## PR / Issue Format

Use conventional commit prefixes in your PR titles and issue titles:

| Prefix | Usage |
|--------|-------|
| `feat:` | New archive format or feature |
| `fix:` | Bug fix |
| `refactor:` | Code restructuring without feature changes |
| `docs:` | Documentation updates |
| `chore:` | Build, CI, or maintenance tasks |
| `perf:` | Performance improvements |
| `style:` | Code formatting (no logic changes) |
| `test:` | Adding or updating tests |

Examples: `feat: add RAR password support`, `fix: LZ4 list shows 0 bytes`, `refactor: extract common password dialog logic`

## New Format PR Requirements

When submitting a PR that adds a new archive format, you **must** test all of the following before opening the PR:

- [ ] **Full extraction** — extract the entire archive without errors
- [ ] **Multi-select extraction** — long-press select multiple files and extract
- [ ] **Selective extraction** — preview the archive, check specific files, and extract only those
- [ ] **Preview** — tap individual files (text/image/audio) in the archive preview to verify inline preview works
- [ ] **Parallel smoke** — while an op on ANOTHER format is running, your format's op runs concurrently (and vice versa: two ops on YOUR format queue up instead of racing)

Please include screenshots or a brief note confirming each item in the PR description.

## Project Structure

```
usefulunpack/
├── app/src/main/java/com/usefulunpacker/   # Android app (Kotlin)
│   ├── MainActivity.kt                     # Activity shell (~840 lines): lifecycle, wiring,
│   │                                       #   tab strip adapter, rename dialog, memory badge
│   ├── archive/                            # OpScheduler (concurrency), ExtractProgress
│   │                                       #   (OpOverlay progress UI), ArchivePreview, JNI helpers
│   │                                       #   ⚠ two files here declare the ROOT package — see Traps
│   ├── browse/                             # nav()/select()/extract flow, TabState, FolderFragment,
│   │                                       #   multi-select bar, FileBrowser dispatch
│   ├── batch/                              # batch extract / compress / batch preview window
│   ├── extract/                            # extract-all / extract-selected, PreviewFlow (the biggest
│   │                                       #   file — merge, edit+repack, nested archives, zip manage)
│   ├── compression/                        # compress dispatch + inline compress options
│   ├── fileops/                            # signature scan/carve, recycle bin + dialog,
│   │                                       #   delete-with-progress, rename/compare, CSO convert
│   ├── search/                             # global search + search-source resolution
│   ├── ui/                                 # settings, pickers, text/image editors & previews, folder picker
│   ├── model/                              # ArchiveEntry / ExtractCounts / SearchResult
│   ├── adapter/                            # FileAdapter / PreviewAdapter
│   ├── bookmarks/ terminal/ util/          # misc (constants, FileUtils, encoding detection…)
│   └── <X>Core.kt                          # Per-format JNI bridge objects
├── crates/                                 # Rust native libraries (one cdylib per format family)
│   ├── common/                             # shared: json_escape, safe_join, BoundedWriter,
│   │                                       #   progress_store! (per-format GLOBAL progress/CANCEL slots)
│   ├── <fmt>-core/ …                       # xp3 pfs nsa iso ypf zip sevenz rar tar ksd lz4 gzip rgss
│   │                                       #   bzip2 xz zstd lzma brotli cso scan-core
│   └── vendor/                             # vendored forks (rars, sevenz-rust, isomage, zip) + patches
├── build.sh                                # One command: Rust cross-compile (3 ABIs) + Gradle APK
└── .github/workflows/ci.yml                # cargo test --workspace + clippy (non-fatal)
```

Each format is an independent `.so` loaded via `System.loadLibrary`. Kotlin `<X>Core.kt` objects declare `external fun` matched by `#[no_mangle]` JNI functions in the corresponding crate.

## Multi-window & Concurrency Architecture (MUST READ)

Since v5.14 the app runs up to **3 operations truly in parallel**, with a hard platform constraint: **the Rust side keeps exactly ONE global progress/CANCEL slot per format crate** (`progress_store!` in `crates/common`). Two concurrent operations on the SAME format would trample each other's progress bars and cancel flags. Everything below exists because of that sentence.

### The contract for any new operation

```kotlin
val opH = tryStartOperation(activity, fmtKey)     // NEVER blocks, NEVER refuses
…
thread {
    if (!opH.await()) return@thread               // cancelled while queued → abort silently
    try { /* real work */ }
    finally { opH.release() }                     // token release, safe from any thread
}
```

- `fmtKey` = the format string (`"zip"`, `"xp3"`, …). Same-key ops serialize; different keys share the 3 global slots.
- Composite flows that drive TWO formats use pseudo-keys: `"merge"` (merge-into-archive), zip entry edits use `"zip"`.
- **Password prompts happen BEFORE `tryStartOperation`** — a modal can block ~30 s and must never hold a slot or a format lock.
- Cancellation: the progress card's explicit ✕ calls `cancelQueuedThenNotify()` — a merely-QUEUED op is dequeued without touching the Rust CANCEL flag (setting it would kill an unrelated running same-format op in another window). Only RUNNING cancellations fire `accessors.cancel()`.
- Deliberate exceptions: delete/recycle (pure FS work) and signature scan (Rust `SCAN_LOCK` already serializes it) stay on the legacy `OperationLock`. Don't migrate them without revisiting that rationale.
- Every new JNI operation entry must call `clear_cancel()` first — otherwise the previous op's cancel poisons yours.

### Progress UI

Always go through `PollingProgressDialog` — since v5.14 it renders onto a shared **OpOverlay** floating layer inside the activity (everything EXCEPT the progress cards passes touches through, so tab switching AND other windows stay fully operable mid-operation; cards stack vertically; centered within the content area). It is intentionally not cancelable by touch-outside/back — the explicit button is the only cancel path. Queued ops render `⏳ position · ETA` instead of polling foreign statics.

## Historic Traps (read before touching these files)

- ⚠️ **Package/directory mismatch**: `archive/ArchiveExtractor.kt` and `archive/ExtractProgress.kt` sit in the `archive/` directory but declare the **root package** `com.usefulunpacker`. Cross-package references to their symbols need explicit imports — this has bitten maintainers twice and once looked like a compiler bug for an hour.
- **Batch-bar buttons**: visibility is driven by semantic `tag`s (`"extract"` / `"preview"` / `"compress"`) set in `buildBatchBar`, matched in `syncMultiBar`. Never match on display text — strings embed emojis that changed across releases and text matching once hid EXTRACT in extract mode. Emojis live ONLY in string resources, never concatenated in code.
- `viewPager.offscreenPageLimit = MAX_TABS - 1` keeps every tab's fragment attached so inline state survives switching. Don't lower it.
- **Honor/EMUI ROM**: custom ScrollViews/TextViews/EditTexts must NOT enable native scrollbars (ROM NPEs in `onDrawScrollBars`); lists use the draggable fast-scroll handle instead.
- Any `runOnUiThread { ...show Dialog... }` completing async work needs an `isFinishing || isDestroyed` guard — this class of BadTokenException has been exterminated twice; keep it extinct.

## Setup

### Prerequisites

- **Rust**: Install via [rustup](https://rustup.rs)
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
  ```
- **Android NDK** (r28+): Set `ANDROID_NDK_HOME` or place under `ANDROID_HOME/ndk/`
- **cargo-ndk**: `cargo install cargo-ndk`
- **Android SDK**: API 34+

### Build

```bash
bash build.sh
```

This cross-compiles all Rust workspace crates for `arm64-v8a` and `armeabi-v7a`, copies `.so` files into `app/src/main/jniLibs/`, then runs `gradlew assembleRelease`. The `.so` payload is **not** in git (build output — see `.gitignore`), so a fresh clone has no native libraries until `build.sh` runs; the real-file fixtures under `crates/*/testdata/` are not in git either, so tests needing them print `SKIP …` and pass.

## Adding a New Archive Format

1. Create `crates/<name>-core/` with a `cdylib` crate depending on `archive_common`
2. Implement:
   - `list_<name>_inner(input) -> Result<String, String>` — JSON array of entries `[{"n":"...","s":...,"d":...,"e":...}]`
   - `extract_<name>_inner(input, output, selected?, password?)` — extracts files
   - If encrypted: `needs_password_inner(input)` — detects whether a password is required
   - JNI `#[no_mangle]` exports matching the Kotlin declarations
3. Create `app/src/main/java/com/usefulunpacker/<Name>Core.kt` with `external fun` declarations and `System.loadLibrary`
4. Register the format in Kotlin:
   - `extractByFormat()` (`ArchiveExtractor.kt`) — add the `when` branch
   - `formatOfName()` (`util/Constants.kt`) — extension mapping
   - `previewArchive()` listing case, and password branches in `tryExtractWithPassword()` / `showPasswordDialog()` if applicable
   - `extractAccessors()` / `compressAccessors()` (`ExtractProgress.kt`) — wire progress getters + cancel
5. Add the crate to the `Cargo.toml` workspace members and `build.sh`'s `CRATES` array
6. Report byte-level progress via `extract_progress::reset/set_file/add_bytes` (`compress_progress::*` for packing). For whole-member buffered decodes (rar ≤64 MB path), feed the top bar from the WRITE side (`ProgressWriter::extract`) so totals stay exact — never from a polled decode counter.
7. Add error-message strings to `res/values/strings.xml` (all four locales: `values/`, `-zh-rCN/`, `-zh-rTW/`, `-ja/`)
8. Nothing else: the scheduler picks up your format automatically (the fmt key IS the format string)

## Code Conventions

- **Rust**: compact single-line JNI function style; wrap bodies with `guarded()` so panics can't cross JNI; reuse `archive_common::{s, json_escape, safe_join}`.
- **Kotlin**: background threads for anything touching disk/network; `runOnUiThread` for UI, guarded per above; `when` expressions for format dispatch.
- **Format JSON**: entries carry `"n"` (name), `"s"` (size int), `"d"` (is-dir bool), `"e"` (encrypted bool).
- **Password formats**: three variants — base `extractWithPassword(...)`, `extractSelectedWithPassword(tool, input, output, selected, password)`, and `needsPassword(input) -> Boolean`.
- **Selective extraction**: newline-separated path list; `HashSet` lookups; exact-path AND directory-prefix matching.
- **Async completion dialogs**: guard `isFinishing || isDestroyed` before showing anything.
- **Version discipline**: one narrative commit per release (`feat(vX.Y.Z): …`), a dedicated TODO.md section per release, and bump `versionCode`/`versionName` in `app/build.gradle`.

## Testing

CI runs `cargo test --workspace` (+ clippy, non-fatal). Run locally before opening a PR:

```bash
cargo test --workspace          # Rust suite (see below)
./gradlew lintDebug             # baseline: 0 errors / 271 warnings — do not add errors
bash build.sh                   # full APK (needs NDK); Kotlin-only: :app:assembleRelease
```

What the Rust tests cover (keep it green):

- **Round-trips / real corpus** — each `*_core` packs then extracts with byte equality; a real-world corpus (`files4testing`, ~423 vectors + 13 injected faults across 14 formats) validates compatibility: valid archives extract with matching hashes, injected faults (truncated / corrupted / wrong password / missing volume) reject cleanly.
- **Security / malicious headers** — crafted inputs rejected without abort: decompression bombs (`BoundedWriter` caps), huge header counts (7z num_files/coders, ISO dir sizes), negative/overflowing lengths (KSD, PFS offsets), path traversal (`safe_join`), stack-depth limits.
- **Signature scan** — real-compressed-sample vectors (gzip/bzip2/xz/zstd/lz4/lzma) catch byte-order/bitfield regressions. Historically validated against binwalk 3.1 (201 semantic differences, all favorable).
- **RPG Maker (RGSS / MV / MZ)** — `crates/rgss-core`. Parsers checked against the real archives in `uuksu/RPGMakerDecrypter`'s test suite (`EncryptedArchives/Game.rgss{ad,2a,3a}`, `EncryptedFiles/{Image,AudioOrbis,AudioMpeg}`), for which that project publishes exact offsets/sizes/keys and the SHA-1 of each decrypted asset. Because `rgss-core` is also an `rlib`, an out-of-workspace scratch crate can `path`-depend on it and diff against those oracles without touching the repo; `examples/probe.rs` does the same thing on-device.
- **Real-archive rar probes** — env-gated on-disk tests: `UU_RAR_PROBE=/path/to/big.rar cargo test -p archive_rar_core probe_real_archive_progress -- --nocapture` asserts exact top-bar totals; `UU_RAR_SEL_PROBE` verifies non-solid random-access extraction of a late member.

### Synthetic fixtures are necessary, not sufficient

A hand-made fixture encodes the same assumption as the code under test, so both are wrong together and the test stays green. Three examples that shipped green and were caught only by real files: an M4A fixture carrying the wrong `ftyp` minor version (`0.0.2.0`; real files use `0.0.0.0`), a probe window one byte too short to reach the `moov` box, and an obfuscated fixture whose 16-byte header did not match the real `RPGMV` header. Prefer byte-identity as the oracle — re-packing a real file must reproduce it exactly — and keep validators *falsifiable*: never check for a structure at a position your own derivation implies.

Real-device points before merging UI/ROM-sensitive changes:

- Honor/EMUI scrollbar NPE avoidance (see Traps).
- Cancel mid-extraction into a fresh folder deletes the whole output; a cancelled carve never hands a partial file onward.
- Text preview/editor: BOM auto-detection, inline encoding switch, garbled-read hint.
- Parallel regression (v5.14+): two ops on different formats overlap; two on the same format queue; queued-cancel doesn't disturb the running twin.

## Before Submitting a PR

1. `cargo check` clean for all crates; `cargo test --workspace` green
2. `./gradlew lintDebug` introduces no new errors
3. `build.sh` completes (requires NDK) — note it, not `:app:assembleRelease`, is what refreshes the root `UsefulUnpack.apk`
4. Update `TODO.md` if you addressed a listed issue; add a bullet under the current dev section
5. One feature/bugfix per PR; no unrelated reformatting
6. New format PRs: tick all five items in [New Format PR Requirements](#new-format-pr-requirements)

## Where Help Is Wanted

The top of [TODO.md](TODO.md) tracks live plans — good entry points include the **preview long-press menu** (share / info / extract-this for archive entries), **preview sorting**, and items under 已知限制. Bigger design-ready tracks (preview workspace tabs, CLI revival) are documented there too — claim one by opening an issue.

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
