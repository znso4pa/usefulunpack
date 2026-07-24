# Contributing to UsefulUnpack

[**English**](CONTRIBUTING.md) | [**中文**](CONTRIBUTING-zh.md)

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

Please include screenshots or a brief note confirming each item in the PR description.

## Project Structure

```
usefulunpack/
├── app/src/main/java/com/usefulunpacker/   # Android app (Kotlin)
│   ├── MainActivity.kt                     # UI, file browser, extraction workflow
│   ├── Xp3Core.kt / ZipCore.kt / ...       # Per-format JNI bridge objects
│   └── ArchiveCore.kt                      # Shared helpers
├── crates/                                  # Rust native libraries
│   ├── common/                              # Shared utilities (json_escape, safe_join, etc.)
│   ├── xp3-core/ / pfs-core/ / ...         # Per-format cdylib crates
│   ├── rar-core/                            # RAR extraction (rars crate)
│   └── lz4-core/                            # LZ4 decompression (lz4_flex crate)
├── build.sh                                 # One-command: Rust cross-compile + Gradle APK
├── Cargo.toml                               # Workspace root
└── build.gradle                             # Gradle project config
```

Each format is an independent `.so` loaded via `System.loadLibrary`. Kotlin `*Core.kt` objects declare `external fun` declarations matched by `#[no_mangle]` JNI functions in the corresponding crate.

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

This cross-compiles all 9 Rust crates for `arm64-v8a`, `armeabi-v7a`, `x86_64`, copies `.so` files into `app/src/main/jniLibs/`, then runs `gradlew assembleRelease`.

## Adding a New Archive Format

1. Create `crates/<name>-core/` with a `cdylib` crate depending on `archive_common`
2. Implement:
   - `list_<name>_inner(input) -> Result<String, String>` — returns JSON array of entries `[{"n":"...","s":...,"d":...,"e":...}]`
   - `extract_<name>_inner(input, output, selected?, password?)` — extracts files
   - If encryption is supported: `needs_password_inner(input)` — detects whether password is required
   - JNI `#[no_mangle]` exports matching the Kotlin declarations
3. Create `app/src/main/java/com/usefulunpacker/<Name>Core.kt` with `external fun` declarations and `System.loadLibrary`
4. Register the format in `MainActivity.kt`:
   - `extractByFormat()` — add `when` branch
   - `previewArchive()` — add listing case
   - `EXT_FORMAT_MAP` — add file extension mapping (if any)
   - `tryExtractWithPassword()` / `showPasswordDialog()` — add password branches if encryption is supported
5. Add the crate to `Cargo.toml` workspace members and `build.sh` `CRATES` array
6. Add string resources for error messages in `res/values/strings.xml`

## Code Conventions

- **Rust**: Match the existing compact single-line JNI function style. Use `guarded()` to catch panics crossing JNI boundaries. Reuse `archive_common::{s, json_escape, safe_join}`.
- **Kotlin**: Follow existing patterns in `MainActivity.kt`. Use `when` expressions for format dispatch. Run extraction in background threads with `runOnUiThread` for UI updates.
- **Format JSON**: Entry objects must have `"n"` (name string), `"s"` (size as integer), `"d"` (is directory boolean), `"e"` (is encrypted boolean).
- **Password support**: Formats supporting encryption need three variants:
  - Base `extractWithPassword(tool, input, output, password)`
  - `extractSelectedWithPassword(tool, input, output, selected, password)` (selective extraction)
  - `needsPassword(input) -> Boolean` (detection before prompting)
- **Selective extraction**: Accept a newline-separated string of paths. Use `HashSet` for O(1) lookups. Match both exact paths and prefix matches (for directory children).

## Testing

Currently tested via manual APK installation and smoke-test extraction. For Rust changes, run at minimum:
```bash
cargo check -p archive_<name>_core
```

Future goal: automated unit tests in each crate and CI/CD with cross-compilation verification.

## Before Submitting a PR

1. Ensure `cargo check` passes for all crates
2. Verify `build.sh` completes without errors (requires NDK)
3. Update `TODO.md` if addressing known issues
4. Keep changes focused — one feature/bugfix per PR
5. Match the existing code style (no reformatting of unrelated code)
6. For new format PRs: verify the 4 test items listed in [New Format PR Requirements](#new-format-pr-requirements)

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
