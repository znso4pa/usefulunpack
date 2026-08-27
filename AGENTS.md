# AGENTS.md

Instructions for AI coding agents working in this repository.

UsefulUnpack = Android app (Kotlin) + Rust cdylib per archive format, glued by JNI.
Human-facing docs: [README](README.md) · [CONTRIBUTING](CONTRIBUTING.md) (中文: [CONTRIBUTING-zh](CONTRIBUTING-zh.md)) · [TODO.md](TODO.md) (changelog + roadmap, source of truth).

## Commands

```bash
bash build.sh                      # full build: Rust cross-compile (3 ABIs) -> jniLibs -> assembleRelease (needs NDK)
./gradlew :app:assembleRelease     # Kotlin-only changes
cargo test --workspace             # Rust suite — must stay green
./gradlew lintDebug                # baseline: 0 errors / ~268 warnings — NEVER introduce new errors
```

## Non-negotiable architecture invariants

1. **One global progress/CANCEL slot per format on the Rust side** (`progress_store!` in `crates/common`). Therefore EVERY archive operation must go through:
   ```kotlin
   val opH = tryStartOperation(activity, fmtKey)   // never blocks/refuses; queues instead
   thread {
       if (!opH.await()) return@thread             // cancelled while queued → abort silently
       try { /* work */ } finally { opH.release() }
   }
   ```
   Same `fmtKey` serializes; different keys share 3 slots. Composite two-format flows use pseudo-key `"merge"`; zip entry edits use `"zip"`.
2. **Password prompts BEFORE `tryStartOperation`** — modals block ~30 s and must never hold a slot/format lock.
3. Every new JNI operation entry calls `clear_cancel()` first, or the previous op's cancel poisons it.
4. Cancel isolation: queued-cancel must NOT set the format-global CANCEL flag (it would kill an unrelated running same-format op). Use `PollingProgressDialog.cancelQueuedThenNotify()` semantics.
5. Every `runOnUiThread` block that touches UI (Toast, AlertDialog, Progress.dismiss, etc.) MUST start with `if (isFinishing || isDestroyed) return@runOnUiThread`. When a `prog.dismiss()` appears in the same block, place it AFTER the guard to avoid dismissing on a destroyed Activity.
5. Deliberate legacy exceptions — do NOT migrate without revisiting rationale: delete/recycle flows and signature scan stay on old `OperationLock`.
6. `viewPager.offscreenPageLimit = MAX_TABS - 1` keeps all fragments alive; don't lower it.

## Known traps

- ⚠️ **Package ≠ directory**: `archive/ArchiveExtractor.kt` and `archive/ExtractProgress.kt` sit under `archive/` but declare root package `com.usefulunpacker`. Import their symbols explicitly from other packages (`com.usefulunpacker.archive.OpScheduler` style). Same class of quirk may exist elsewhere — trust `package` lines, not folders.
- **Batch-bar buttons**: visibility matches semantic `tag`s (`"extract"`/`"preview"`/`"compress"`) from `buildBatchBar`, checked in `syncMultiBar`. NEVER match display text; emojis exist only in string resources, never concatenated in Kotlin.
- **Cross-tab multi-select**: selections persist per-tab (`TabState.multiSelected`); batch ops aggregate via `allSelectedFiles()` / `totalSelectedCount()`. Cancel clears ALL tabs via `exitAllMultiSelect()`. Tab badges auto-refresh via `syncAllTabAdapters()` + `tabAdapter.notifyDataSetChanged()`.
- **Async completion dialogs**: any `runOnUiThread { ... AlertDialog ... }` after background work requires `isFinishing || isDestroyed` guard (BadTokenException class — exterminated twice).
- **Honor/EMUI ROM**: custom ScrollView/TextView/EditText must not enable native scrollbars (ROM NPE in `onDrawScrollBars`).
- New strings go to ALL FOUR locales: `values/`, `-zh-rCN/`, `-zh-rTW/`, `-ja/`.

## Conventions

- Commits: conventional prefixes; releases are single narrative commits `feat(vX.Y.Z): …` + dedicated TODO.md section + `versionCode`/`versionName` bump in `app/build.gradle`.
- `TODO.md` is the changelog AND roadmap — append a bullet under the current dev section for behavioral changes.
- Format listing JSON contract: `[{"n":name,"s":size,"d":isDir,"e":encrypted}]`; selective extraction takes newline-separated paths with exact + directory-prefix matching.
- Rust JNI style: compact single-line functions wrapped in `guarded()`; reuse `archive_common::{s, json_escape, safe_join}`.
- Progress reporting: feed `extract_progress::reset/set_file/add_bytes` (packing: `compress_progress::*`). Whole-member buffered decodes feed the top bar from the WRITE side (`ProgressWriter::extract`) so totals stay exact.

## Map of non-obvious places

| Thing | Where |
|---|---|
| Scheduler / lock / queue | `archive/OpScheduler.kt` |
| Progress UI (OpOverlay floating layer + PollingProgressDialog) | `archive/ExtractProgress.kt` |
| Format dispatch + password retry flows | `archive/ArchiveExtractor.kt`, `extract/PreviewFlow.kt` (largest file) |
| Tab state / multi-window | `browse/TabState.kt`, `browse/FolderFragment.kt`, `MainActivity.kt` (~840 lines: tab strip adapter, rename, memory badge) |
| Recycle bin | `fileops/RecycleBin.kt` (rename-first fast path; copy fallback reports per-file progress; copy failure must abort BEFORE deleting originals) |
| Signature scan engine | `crates/scan-core` (binwalk-style; validated against binwalk 3.1 corpus) |
| Vendored forks | `crates/vendor/` (rars, sevenz-rust, isomage, zip) |

## Verification before finishing a task

1. `cargo test --workspace` green (if Rust touched)
2. `./gradlew lintDebug` — no new errors (if Kotlin/resources touched)
3. `bash build.sh` completes (if JNI surface changed)
4. Behavioral checklist for UI-sensitive changes: rotation survival, cancel mid-op cleanup, parallel smoke (different formats overlap; same format queues; queued-cancel isolates), Honor scrollbar avoidance
4. Behavioral checklist for UI-sensitive changes: rotation survival, cancel mid-op cleanup, parallel smoke (different formats overlap; same format queues; queued-cancel isolates), Honor scrollbar avoidance
