use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jlong};
use archive_common::{s, SyncIo, oneshot_async, json_escape, derive_dirs, safe_join, extract_result_json, ProgressWriter, ProgressReader, DestAllocator};
use archive_common::{extract_progress, compress_progress};
use archive_cxdec_core::CxEncryption;
use xp3::read::XP3Archive;
use xp3::header::XP3Version;
use xp3::write::{XP3Writer, TransformFn};
use std::fs::{self, File};
use std::collections::HashSet;
use std::io::{BufReader, BufWriter};
use std::path::{Path, PathBuf};

// ─── XP3 (Kirikiri) ────────────────────────

/// Only small entries are buffered for the KSD-mode-2 filter probe — the filter
/// appears only on text/scripts (tiny), while images/audio are streamed.
const KSD_PROBE_MAX: u64 = 16 * 1024 * 1024;

/// Disk-fill guardrail: an entry may write at most declared size + 1 GiB.
/// krkr2 neither caps nor verifies the sum of decoded segment bytes against
/// INFO.size, and third-party repacks really do disagree (the extra bytes are
/// KEPT on disk — lenient extraction, see the extract loops) — but a hostile
/// entry inflating gigabytes past its declaration is cut off here instead of
/// filling the disk. Well-formed packs never come near the slack.
const ENTRY_WRITE_SLACK: u64 = 1024 * 1024 * 1024;

/// Copies one entry from the xp3 stream to disk. Small entries are buffered so
/// a Kirikiri KSD mode-2 filter (`FE FE 02 FF FE …`, used on text inside XP3)
/// can be unwrapped — the xp3 crate only decodes the outer zlib, which would
/// otherwise leave the scrambled wrapper as the file content. Returns true on
/// success. Every success path flushes explicitly: a `BufWriter` dropped on
/// failure would swallow a disk-full/EIO from its final <8 KiB, counting a
/// truncated file as extracted (same semantics zip-core already enforces).
/// Failure paths do NOT flush — the caller deletes the half-written file.
fn copy_xp3_entry<R: tokio::io::AsyncRead + Unpin>(
    mut xf: R,
    size: u64,
    out_stream: &mut SyncIo<ProgressWriter<BufWriter<File>>>,
) -> bool {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let cap = size.saturating_add(ENTRY_WRITE_SLACK);
    if size <= KSD_PROBE_MAX {
        // Buffer up to a hard cap so a crafted entry that inflates far beyond
        // its declared size can't grow the Vec unboundedly (zlib bomb → OOM).
        // The preallocation is capped too — the declared size is attacker-
        // controlled and a burst of inflated claims would churn the heap.
        let copied: Result<Vec<u8>, std::io::Error> = oneshot_async(async {
            let x = &mut xf; // borrow, not consume — we may stream the rest below
            let mut buf2 = Vec::with_capacity((size as usize).min(1024 * 1024));
            let limit = (KSD_PROBE_MAX + 1) as usize;
            let mut tmp = [0u8; 8192];
            loop {
                if buf2.len() >= limit { break; }
                let want = (limit - buf2.len()).min(tmp.len());
                let n = x.read(&mut tmp[..want]).await?;
                if n == 0 { break; }
                buf2.extend_from_slice(&tmp[..n]);
            }
            Ok(buf2)
        });
        match copied {
            Ok(b) if b.len() as u64 <= KSD_PROBE_MAX => {
                let payload = archive_ksd_core::ksd_mode2_decode(&b).unwrap_or(b);
                // Calibrate the per-file progress to the actual (KSD-unwrapped)
                // size — the wrapper's declared size was set before we knew.
                extract_progress::set_file(payload.len() as u64);
                oneshot_async(async {
                    out_stream.write_all(&payload).await?;
                    out_stream.flush().await
                }).is_ok()
            }
            Ok(b) => {
                // The stream is bigger than the probe window — write what we
                // already buffered verbatim (no KSD guess), then stream the
                // rest. Skipping straight to `io::copy` would drop the buffered
                // bytes (the reader has already consumed them).
                let rest = cap.saturating_sub(b.len() as u64);
                oneshot_async(async {
                    out_stream.write_all(&b).await?;
                    tokio::io::copy(&mut xf.take(rest), out_stream).await?;
                    out_stream.flush().await
                }).is_ok()
            }
            _ => {
                // Read error: stream the remainder (best-effort).
                oneshot_async(async {
                    tokio::io::copy(&mut xf.take(cap), out_stream).await?;
                    out_stream.flush().await
                }).is_ok()
            }
        }
    } else {
        oneshot_async(async {
            tokio::io::copy(&mut xf.take(cap), out_stream).await?;
            out_stream.flush().await
        }).is_ok()
    }
}

fn guarded<T: Send + 'static>(f: impl FnOnce() -> Result<T, String> + Send + 'static) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|panic| {
        let msg = panic.downcast_ref::<&str>().copied()
            .or_else(|| panic.downcast_ref::<String>().map(|s| s.as_str()))
            .unwrap_or("unknown panic");
        Err(format!("panic: {msg}"))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3Extract(
    mut env: JNIEnv, _class: JClass,
    _tool: JString, input: JString, output: JString,
) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut env, &input); let out = s(&mut env, &output);
    match guarded(move || extract_xp3(&inp, &out)) {
        Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match env.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = env.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}

fn extract_xp3(input: &str, output: &str) -> Result<(u32, u32), String> {
    let file = File::open(input).map_err(|e| format!("{e}"))?;
    let mut archive = oneshot_async(XP3Archive::open(SyncIo(BufReader::new(file))))
        .map_err(|e| format!("XP3: {e}"))?;
    let total = archive.entries().len() as u32;
    extract_progress::reset(archive.entries().iter().map(|e| e.size).sum());
    let mut fail = 0u32;
    // XP3 indexes can carry duplicate names, and /sdcard + FAT are
    // case-insensitive — allocate collision-free dests instead of letting
    // last-wins File::create silently destroy the earlier entry's data.
    let mut dests = DestAllocator::new();
    for i in 0..total as usize {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        let entry = &archive.entries()[i];
        extract_progress::set_name(&entry.name);
        extract_progress::set_file(entry.size);
        let size = entry.size;
        let dest = match safe_join(output, &entry.name) {
            Ok(d) => dests.allocate(d),
            Err(_) => { fail += 1; continue; }
        };
        if let Some(p) = dest.parent() { let _ = fs::create_dir_all(p); }
        let out_file = match File::create(&dest) {
            Ok(f) => f,
            Err(_) => { fail += 1; continue; }
        };
        let mut out_stream = SyncIo(ProgressWriter::extract(BufWriter::new(out_file)));
        let xf = match oneshot_async(archive.by_index(i)) {
            Some(Ok(f)) => f,
            _ => { let _ = fs::remove_file(&dest); fail += 1; continue; }
        };
        if !copy_xp3_entry(xf, size, &mut out_stream) {
            let _ = fs::remove_file(&dest);
            fail += 1;
            continue;
        }
        // Lenient extraction: keep what the segments actually decoded, even
        // when it disagrees with INFO.size (krkr2 does the same). Re-base the
        // progress so the bar still lands exactly on 100%.
        let written = extract_progress::file_bytes();
        if written != size {
            extract_progress::calibrate_file(written);
            let delta = (written as i128 - size as i128).clamp(i64::MIN as i128, i64::MAX as i128);
            extract_progress::adjust_total(delta as i64);
        }
    }
    Ok((total, fail))
}

/// Encryption state of one XP3, as a machine token the UI localizes:
///   `plain`          — no protection (or no evidence either way)
///   `cxdec:<scheme>` — cxdec-protected; the scheme scored against real entries
///   `cxdec:?`        — a cxdec game folder, but no known scheme decrypts it
///   `suspect`        — no cxdec sidecar, yet the index marks entries protected
///
/// Deliberately does NOT call `clear_cancel()` and never touches the progress
/// store: it shares this .so (and that store) with extraction, so clearing
/// anything here would wipe a running operation's counters.
fn probe_scheme_token(archive: &str) -> String {
    let dir = Path::new(archive).parent().unwrap_or_else(|| Path::new("."));
    match archive_cxdec_core::probe_scheme(dir, archive) {
        archive_cxdec_core::SchemeProbe::Detected(name) => format!("cxdec:{name}"),
        archive_cxdec_core::SchemeProbe::NoSchemeMatch => "cxdec:?".to_string(),
        archive_cxdec_core::SchemeProbe::NoControlBlock => {
            // No sidecar left to work with. The flag alone is not evidence (a
            // real filter-less archive sets it on every entry and still reads
            // as plain), so this asks whether the CONTENT is unrecognizable too.
            if archive_cxdec_core::content_looks_encrypted(archive) {
                "suspect".to_string()
            } else {
                "plain".to_string()
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3ProbeScheme(
    mut env: JNIEnv, _: JClass, input: JString,
) -> jstring {
    let inp = s(&mut env, &input);
    // A probe failure must never break preview/listing, so it returns null
    // ("no note") instead of throwing.
    match guarded(move || Ok::<String, String>(probe_scheme_token(&inp))) {
        Ok(tok) => match env.new_string(&tok) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() },
        Err(_) => std::ptr::null_mut(),
    }
}

fn list_xp3(input: &str) -> Result<String, String> {
    let file = File::open(input).map_err(|e| format!("{e}"))?;
    let archive = oneshot_async(XP3Archive::open(SyncIo(BufReader::new(file))))
        .map_err(|e| format!("XP3: {e}"))?;
    let raw_names: Vec<&str> = archive.entries().iter().map(|e| e.name.as_str()).collect();
    let normalized: Vec<String> = raw_names.iter().map(|n| n.replace('\\', "/")).collect();
    let norm_refs: Vec<&str> = normalized.iter().map(|s| s.as_str()).collect();
    let dirs = derive_dirs(&norm_refs);
    let mut all: Vec<(String, u64, bool)> = Vec::new();
    for d in &dirs { all.push((d.clone(), 0, true)); }
    for entry in archive.entries().iter() {
        all.push((entry.name.replace('\\', "/"), entry.size, false));
    }
    all.sort_by(|a, b| a.0.cmp(&b.0));
    let entries: Vec<String> = all.iter().map(|(n, s, d)| {
        let sz = if *d { 0_u64 } else { *s };
        format!(r#"{{"n":"{}","s":{},"d":{},"e":false}}"#, json_escape(n), sz, d)
    }).collect();
    Ok(format!("[{}]", entries.join(",")))
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3ListEntries(
    mut env: JNIEnv, _: JClass, input: JString,
) -> jstring {
    let inp = s(&mut env, &input);
    match guarded(move || list_xp3(&inp)) {
        Ok(j) => match env.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() },
        Err(e) => { let _ = env.throw_new("java/io/IOException", format!("listEntries: {e}")); std::ptr::null_mut() }
    }
}

// ─── XP3 Selective Extract ───

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3ExtractSelected(
    mut env: JNIEnv, _: JClass,
    _t: JString, input: JString, output: JString, selected: JString,
) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut env, &input); let out = s(&mut env, &output); let sel_str = s(&mut env, &selected);
    match guarded(move || extract_xp3_selected(&inp, &out, &sel_str)) {
        Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match env.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = env.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}

fn extract_xp3_selected(input: &str, output: &str, selected: &str) -> Result<(u32, u32), String> {
    let sel_set: HashSet<&str> = selected.lines().filter(|l| !l.is_empty()).collect();
    if sel_set.is_empty() { return Ok((0, 0)); }
    let file = File::open(input).map_err(|e| format!("{e}"))?;
    let mut archive = oneshot_async(XP3Archive::open(SyncIo(BufReader::new(file))))
        .map_err(|e| format!("XP3: {e}"))?;
    let matches = |raw_name: &str| {
        let norm_name = raw_name.replace('\\', "/");
        sel_set.contains(norm_name.as_str()) ||
            sel_set.iter().any(|d| { let dd = if d.ends_with('/') { &d[..d.len()-1] } else { d }; norm_name.starts_with(&format!("{dd}/")) })
    };
    extract_progress::reset(archive.entries().iter().filter(|e| matches(&e.name)).map(|e| e.size).sum());
    let mut sel = 0u32; let mut fail = 0u32;
    let mut dests = DestAllocator::new();
    for i in 0..archive.entries().len() {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        let raw_name = &archive.entries()[i].name;
        if !matches(raw_name) { continue; }
        sel += 1;
        extract_progress::set_name(raw_name);
        extract_progress::set_file(archive.entries()[i].size);
        let size = archive.entries()[i].size;
        let dest = match safe_join(output, raw_name) {
            Ok(d) => dests.allocate(d),
            Err(_) => { fail += 1; continue; }
        };
        if let Some(p) = dest.parent() { let _ = fs::create_dir_all(p); }
        let out_file = match File::create(&dest) {
            Ok(f) => f,
            Err(_) => { fail += 1; continue; }
        };
        let mut out_stream = SyncIo(ProgressWriter::extract(BufWriter::new(out_file)));
        let xf = match oneshot_async(archive.by_index(i)) {
            Some(Ok(f)) => f,
            _ => { let _ = fs::remove_file(&dest); fail += 1; continue; }
        };
        if !copy_xp3_entry(xf, size, &mut out_stream) {
            let _ = fs::remove_file(&dest);
            fail += 1;
            continue;
        }
        let written = extract_progress::file_bytes();
        if written != size {
            extract_progress::calibrate_file(written);
            let delta = (written as i128 - size as i128).clamp(i64::MIN as i128, i64::MAX as i128);
            extract_progress::adjust_total(delta as i64);
        }
    }
    Ok((sel, fail))
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3ExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3ExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3ExtractProgressFileCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3ExtractProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_total() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3ExtractProgressName(env: JNIEnv, _: JClass) -> jstring {
    env.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3ExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }

// ─── XP3 cxdec (classic content-filter decryption) ────────────────────────
// Scheme probing, control-block discovery and entry decryption live in
// archive_cxdec-core; these entries only translate the JNI surface. Progress
// and cancel share this cdylib's extract store, so the app's existing "xp3"
// polling accessors and OpScheduler key work unchanged.

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3CxdecExtract(
    mut env: JNIEnv, _: JClass,
    _t: JString, game_dir: JString, input: JString, output: JString,
) -> jstring {
    extract_progress::clear_cancel();
    let gd = s(&mut env, &game_dir); let inp = s(&mut env, &input); let out = s(&mut env, &output);
    match guarded(move || archive_cxdec_core::extract(&gd, &inp, &out, None)) {
        Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match env.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = env.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3CxdecExtractSelected(
    mut env: JNIEnv, _: JClass,
    _t: JString, game_dir: JString, input: JString, output: JString, selected: JString,
) -> jstring {
    extract_progress::clear_cancel();
    let gd = s(&mut env, &game_dir); let inp = s(&mut env, &input); let out = s(&mut env, &output); let sel_str = s(&mut env, &selected);
    match guarded(move || archive_cxdec_core::extract(&gd, &inp, &out, Some(&sel_str))) {
        Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match env.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = env.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}

// ─── XP3 Pack (封包) ──────────────────────

/// Collects files under `base` (or the single file itself) with `/`-separated
/// archive paths. Iterative — no recursion, so deep trees can't overflow the stack.
fn collect_files_xp3(base: &Path) -> Result<Vec<(PathBuf, String)>, String> {
    let mut out = Vec::new();
    if base.is_file() {
        let name = base.file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
        out.push((base.to_path_buf(), name));
        return Ok(out);
    }
    let mut stack = vec![(base.to_path_buf(), String::new())];
    while let Some((dir, rel)) = stack.pop() {
        let mut entries: Vec<_> = fs::read_dir(&dir).map_err(|e| format!("read_dir {}: {e}", dir.display()))?
            .collect::<Result<_, _>>().map_err(|e| format!("read_dir {}: {e}", dir.display()))?;
        entries.sort_by_key(|e| e.file_name());
        for entry in entries {
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().to_string();
            let child_rel = if rel.is_empty() { name.clone() } else { format!("{rel}/{name}") };
            let meta = entry.metadata().map_err(|e| format!("metadata {}: {e}", path.display()))?;
            if meta.is_dir() {
                stack.push((path, child_rel));
            } else if meta.is_file() {
                out.push((path, child_rel));
            }
        }
    }
    out.sort_by(|a, b| a.1.cmp(&b.1));
    Ok(out)
}

/// How an encrypted pack resolved its scheme. Read by Kotlin right after a
/// successful `xp3CreateArchive` (same .so, and the xp3 format slot is held for
/// the whole operation, so no other pack can overwrite it in between).
static LAST_ENC_NOTE: std::sync::Mutex<String> = std::sync::Mutex::new(String::new());

fn set_last_enc_note(note: &str) {
    if let Ok(mut g) = LAST_ENC_NOTE.lock() {
        *g = note.to_string();
    }
}

/// Machine-readable note for the UI: which scheme was used, where it came from,
/// and whether it could be checked against real ciphertext.
fn enc_note_json(scheme: &str, source: &str, verified: bool) -> String {
    format!(
        r#"{{"scheme":"{}","source":"{}","verified":{}}}"#,
        json_escape(scheme), json_escape(source), verified
    )
}

/// Resolves the cxdec scheme to encrypt a new archive with.
///
/// Only two sources exist, and they are not equally trustworthy:
/// 1. an existing encrypted `.xp3` in the output/source folder — its scheme was
///    scored against real game ciphertext by `detect_cipher`, so it is verified;
/// 2. the game's own `xp3filter.tjs` constants + the feng template ordering —
///    nothing can check this, so the note marks it unverified.
/// With neither, the pack is refused rather than guessed at.
fn resolve_writer_cipher(input: &Path, output: &Path) -> Result<(CxEncryption, String), String> {
    let mut dirs: Vec<PathBuf> = Vec::new();
    if let Some(d) = output.parent() {
        dirs.push(d.to_path_buf());
    }
    if input.is_dir() && !dirs.contains(&input.to_path_buf()) {
        dirs.push(input.to_path_buf());
    }

    for dir in &dirs {
        let mut oracles: Vec<PathBuf> = match fs::read_dir(dir) {
            Ok(rd) => rd
                .filter_map(|e| e.ok().map(|e| e.path()))
                .filter(|p| {
                    p.is_file()
                        && p != output
                        && p.extension().map(|e| e.eq_ignore_ascii_case("xp3")).unwrap_or(false)
                })
                .collect(),
            Err(_) => Vec::new(),
        };
        oracles.sort();
        for cand in oracles {
            if let Ok((cx, name)) = archive_cxdec_core::detect_cipher(dir, &cand.to_string_lossy()) {
                let src = cand
                    .file_name()
                    .map(|n| n.to_string_lossy().to_string())
                    .unwrap_or_default();
                return Ok((cx, enc_note_json(name, &src, true)));
            }
        }
    }

    for dir in &dirs {
        let params = fs::read_to_string(dir.join("xp3filter.tjs"))
            .ok()
            .as_deref()
            .and_then(archive_cxdec_core::tjs_scheme_params);
        if params.is_some() {
            let name = archive_cxdec_core::FENG_TEMPLATE.name;
            let cx = archive_cxdec_core::cipher_by_name(dir, name)?;
            return Ok((cx, enc_note_json(name, "xp3filter.tjs", false)));
        }
    }

    Err("XP3: cxdec 加密需要目录内有一份已加密的 .xp3 用于确定方案，或配套的 xp3filter.tjs / .tpm 控制表".to_string())
}

/// Entry size up to which the post-pack verification reads the entry back in
/// full. Above it only a prefix is checked: the cipher is offset-addressed and
/// the layout uniform, so the head catches the mistakes that matter, while a
/// whole-entry read would buffer hundreds of MB of a movie in RAM.
const VERIFY_FULL_MAX: u64 = 64 * 1024 * 1024;
const VERIFY_PREFIX: usize = 64 * 1024;

fn adler32_of(path: &Path) -> Result<u32, String> {
    use std::io::Read as _;
    let mut f = File::open(path).map_err(|e| format!("XP3 open {}: {e}", path.display()))?;
    let mut hasher = adler32::RollingAdler32::new();
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = f.read(&mut buf).map_err(|e| format!("XP3 read {}: {e}", path.display()))?;
        if n == 0 {
            break;
        }
        hasher.update_buffer(&buf[..n]);
    }
    Ok(hasher.hash())
}

/// Reads back what an encrypted pack just wrote, with the same cipher, and
/// compares it against the source bytes. Catches layout mistakes (protected
/// flag, segment sizes, index) and a cipher/ADLR disagreement — the reader
/// keys the cipher off the STORED checksum, so if our key derivation had used
/// a different value the decrypted bytes would not match.
fn verify_encrypted_pack(
    output: &str,
    files: &[(PathBuf, String)],
    cipher: &std::sync::Arc<std::sync::Mutex<CxEncryption>>,
    full_max: u64,
) -> Result<(), String> {
    use std::io::Read as _;
    for (src, name) in files {
        if compress_progress::cancelled() {
            return Err("cancelled".to_string());
        }
        compress_progress::set_name(name);
        let size = src.metadata().map(|m| m.len()).unwrap_or(0);
        let got = {
            let mut g = cipher.lock().map_err(|_| "XP3: cipher lock poisoned".to_string())?;
            if size <= full_max {
                archive_cxdec_core::read_named_with(output, name, &mut g)?
            } else {
                archive_cxdec_core::read_named_prefix_with(output, name, VERIFY_PREFIX, &mut g)?
            }
        };
        let expect_len = if size <= full_max { size } else { VERIFY_PREFIX.min(size as usize) as u64 };
        if got.len() as u64 != expect_len {
            return Err(format!("XP3: 自校验失败（{name}: 读回 {} 字节，应为 {expect_len}）", got.len()));
        }
        let mut f = File::open(src).map_err(|e| format!("XP3 open {}: {e}", src.display()))?;
        let mut expect = vec![0u8; got.len()];
        f.read_exact(&mut expect).map_err(|e| format!("XP3 read {}: {e}", src.display()))?;
        if got != expect {
            return Err(format!("XP3: 自校验失败（{name}: 解回的字节与源文件不一致）"));
        }
        // Count the WHOLE entry even when only a prefix was read: the total is
        // 2 x the payload (write pass + verify pass), so anything less would
        // leave the bar short of 100% on an archive with large entries.
        compress_progress::add_bytes(if size <= full_max { got.len() as u64 } else { size });
    }
    Ok(())
}

fn create_xp3(input: &str, output: &str, level: i32, enc: &str) -> Result<u32, String> {
    let files = collect_files_xp3(Path::new(input))?;
    if files.is_empty() { return Err("XP3: no files to archive".to_string()); }
    let total: u64 = files.iter().map(|(p, _)| p.metadata().map(|m| m.len()).unwrap_or(0)).sum();

    let encrypted = enc.eq_ignore_ascii_case("cxdec");
    let cipher = if encrypted {
        let (cx, note) = resolve_writer_cipher(Path::new(input), Path::new(output))?;
        set_last_enc_note(&note);
        Some(std::sync::Arc::new(std::sync::Mutex::new(cx)))
    } else {
        set_last_enc_note("");
        None
    };

    // Encrypted entries are written STORED (un-packed). That is the only layout
    // this project has evidence for: the reader inflates before it decrypts, so
    // a packed encrypted segment would have to hold zlib(ciphertext) — plausible
    // but unverified, and pointless to boot (compressing ciphertext). The level
    // therefore does not apply to an encrypted pack.
    //
    // The total covers the verify pass too: it re-reads every entry, and a bar
    // that sits at 100% while the archive is still being checked is worse than
    // no bar at all (same rule as the recycle bin's cross-volume copy).
    compress_progress::reset(if encrypted { total.saturating_mul(2) } else { total });

    let out_file = File::create(output).map_err(|e| format!("XP3 create {output}: {e}"))?;
    let mut writer = oneshot_async(XP3Writer::new(
        XP3Version::Current { minor: 0 },
        SyncIo(BufWriter::new(out_file)),
    )).map_err(|e| format!("XP3: {e}"))?;

    let lvl = level.clamp(0, 9) as u8;
    let mut count = 0u32;
    for (src, name) in &files {
        if compress_progress::cancelled() { return Err("cancelled".to_string()); }
        let size = src.metadata().map(|m| m.len()).unwrap_or(0);
        compress_progress::set_name(name);
        compress_progress::set_file(size);
        // level 0 = raw store (no zlib wrapper), matching "store" semantics
        let compression: Option<u8> = if lvl == 0 { None } else { Some(lvl) };
        let src_file = File::open(src).map_err(|e| format!("XP3 open {}: {e}", src.display()))?;
        let mut reader = SyncIo(ProgressReader::compress(BufReader::new(src_file)));
        let write_result = match &cipher {
            Some(cx) => {
                // The cipher keys off adler32(plaintext), which is also the ADLR
                // the writer stores — it needs the value before the first byte,
                // so it is computed in its own pass over the file.
                let hash = adler32_of(src)?;
                let shared = std::sync::Arc::clone(cx);
                let transform = TransformFn::new(move |off: u64, data: &mut [u8]| {
                    let mut g = shared
                        .lock()
                        .map_err(|_| std::io::Error::new(std::io::ErrorKind::Other, "cipher lock"))?;
                    g.encrypt(hash, off, data)
                        .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e.to_string()))
                });
                oneshot_async(async {
                    let mut fw = writer.file_transformed(name.clone(), true, transform).await?;
                    tokio::io::copy(&mut reader, &mut fw).await?;
                    fw.finish().await?;
                    Ok::<(), std::io::Error>(())
                })
            }
            None => oneshot_async(async {
                let mut fw = writer.file(name.clone(), false, compression).await?;
                tokio::io::copy(&mut reader, &mut fw).await?;
                fw.finish().await?;
                Ok::<(), std::io::Error>(())
            }),
        };
        if write_result.is_err() {
            let _ = fs::remove_file(output);
            return Err(format!("XP3 write {name}: io error"));
        }
        count += 1;
    }
    if oneshot_async(writer.finish(None)).is_err() {
        let _ = fs::remove_file(output);
        return Err("XP3 finalize: io error".to_string());
    }
    if compress_progress::cancelled() {
        let _ = fs::remove_file(output);
        return Err("cancelled".to_string());
    }
    if let Some(cx) = &cipher {
        if let Err(e) = verify_encrypted_pack(output, &files, cx, VERIFY_FULL_MAX) {
            // A pack that cannot be read back is not left on disk: the game
            // would either reject it or, worse, load garbage.
            let _ = fs::remove_file(output);
            return Err(e);
        }
    }
    Ok(count)
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3CreateArchive(
    mut env: JNIEnv, _: JClass, _t: JString, input: JString, output: JString, level: JString,
    enc: JString,
) -> jstring {
    compress_progress::clear_cancel();
    let inp = s(&mut env, &input); let out = s(&mut env, &output);
    let lvl: i32 = s(&mut env, &level).parse().unwrap_or(5);
    // "" = plain, "cxdec" = cxdec-encrypted (the scheme is resolved from the
    // folder; see resolve_writer_cipher).
    let enc = s(&mut env, &enc);
    match guarded(move || create_xp3(&inp, &out, lvl, &enc)) {
        Ok(total) => { let json = extract_result_json(total, total, 0); match env.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = env.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}
/// Note describing the scheme the last encrypted pack used, as JSON
/// (`{"scheme","source","verified"}`), or an empty string for a plain pack.
/// Only meaningful right after a successful `xp3CreateArchive`: the format slot
/// is held for the whole operation, so nothing else can overwrite it meanwhile.
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3LastEncNote(
    mut env: JNIEnv, _: JClass,
) -> jstring {
    let note = LAST_ENC_NOTE.lock().map(|g| g.clone()).unwrap_or_default();
    match env.new_string(&note) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3CompressProgressCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3CompressProgressTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::total_bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3CompressProgressFileCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3CompressProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_total() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3CompressProgressName(env: JNIEnv, _: JClass) -> jstring {
    env.new_string(&compress_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3CompressCancel(_: JNIEnv, _: JClass) { compress_progress::cancel(); }

#[cfg(test)]
mod tests {

    /// 进度 store 是 per-cdylib 的**静态量**，cargo 默认并行跑同一个 crate
    /// 的测试，两个测试的 `reset(total)` + `add_bytes` 会互相踩：抢在前面的那个
    /// 会用自己的夹具尺寸改掉 total，后一个断言 total 的测试就红。凡是调了
    /// extract/compress 入口的测试都必须持这把锁。
    ///
    /// 实证：`archive_lzma-core` 的 `extract_progress_total_is_reported` 曾在 CI 上
    /// 以 `left: 327, right: 119` 失败，本地 25/25 通过。
    static PROGRESS_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
    fn progress_lock() -> std::sync::MutexGuard<'static, ()> {
        PROGRESS_LOCK.lock().unwrap_or_else(|e| e.into_inner())
    }
    use super::*;

    fn tmp(tag: &str) -> PathBuf { std::env::temp_dir().join(format!("uu_xp3_{}_{}", std::process::id(), tag)) }

    #[test]
    fn pack_round_trip_matches_bytes() {
    let _g = progress_lock();
        let dir = tmp("roundtrip");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::create_dir_all(dir.join("sub")).unwrap();
        std::fs::write(dir.join("a.txt"), b"hello xp3").unwrap();
        let big: Vec<u8> = (0..200_000u32).map(|i| (i % 251) as u8).collect();
        std::fs::write(dir.join("sub/b.bin"), &big).unwrap();
        let xp3 = dir.join("out.xp3");
        let out = dir.join("out");
        create_xp3(dir.to_str().unwrap(), xp3.to_str().unwrap(), 6, "").unwrap();
        std::fs::create_dir_all(&out).unwrap();
        extract_xp3(xp3.to_str().unwrap(), out.to_str().unwrap()).unwrap();
        assert_eq!(std::fs::read(out.join("a.txt")).unwrap(), b"hello xp3");
        assert_eq!(std::fs::read(out.join("sub/b.bin")).unwrap(), big);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn pack_single_file() {
    let _g = progress_lock();
        let dir = tmp("single");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("one.dat"), vec![9u8; 5000]).unwrap();
        let xp3 = dir.join("one.xp3");
        let out = dir.join("out");
        create_xp3(dir.join("one.dat").to_str().unwrap(), xp3.to_str().unwrap(), 0, "").unwrap();
        std::fs::create_dir_all(&out).unwrap();
        extract_xp3(xp3.to_str().unwrap(), out.to_str().unwrap()).unwrap();
        assert_eq!(std::fs::read(out.join("one.dat")).unwrap(), vec![9u8; 5000]);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn ksd_mode2_wrapped_entry_extracts_as_text() {
    let _g = progress_lock();
        // A real galgame XP3 stores some text entries as
        //   zlib( KSD mode-2 wrapper `FE FE 02 FF FE` + comp_len/uncomp_len + zlib(text) )
        // The xp3 crate only unwraps the OUTER zlib, so without the KSD unwrap
        // the extracted file would be the wrapper binary — "garbled in every
        // encoding". Build that wrapper, pack, extract, and require the real text.
        let text: Vec<u8> = "こんにちは\nテスト\n".encode_utf16()
            .flat_map(|u| u.to_le_bytes()).collect();
        let mut enc = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::new(6));
        std::io::Write::write_all(&mut enc, &text).unwrap();
        let inner = enc.finish().unwrap();
        let mut wrapper = vec![0xFE, 0xFE, 0x02, 0xFF, 0xFE];
        wrapper.extend_from_slice(&(inner.len() as i64).to_le_bytes());
        wrapper.extend_from_slice(&(text.len() as i64).to_le_bytes());
        wrapper.extend_from_slice(&inner);

        let dir = tmp("ksd");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("script.txt"), &wrapper).unwrap();
        let xp3 = dir.join("ksd.xp3");
        create_xp3(dir.to_str().unwrap(), xp3.to_str().unwrap(), 6, "").unwrap();
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        extract_xp3(xp3.to_str().unwrap(), out.to_str().unwrap()).unwrap();
        let got = std::fs::read(out.join("script.txt")).unwrap();
        assert_eq!(got, text, "KSD wrapper must be unwrapped to the original text");
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn duplicate_entry_names_are_renamed_not_overwritten() {
    let _g = progress_lock();
        // XP3 indexes may carry several entries under one name, and /sdcard +
        // FAT are case-insensitive: without the dedup pass the second entry's
        // File::create truncates the first (last-wins) while the result JSON
        // still reports success. Both entries must land on disk.
        let dir = tmp("dupnames");
        std::fs::create_dir_all(&dir).unwrap();
        let xp3 = dir.join("dup.xp3");
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();

        let out_file = std::fs::File::create(&xp3).unwrap();
        let mut writer = oneshot_async(XP3Writer::new(
            XP3Version::Current { minor: 0 },
            SyncIo(BufWriter::new(out_file)),
        )).unwrap();
        for (name, mut payload) in [("Readme.txt", &b"payload-one"[..]), ("readme.txt", &b"payload-two"[..])] {
            let mut fw = oneshot_async(writer.file(name.to_string(), false, Some(6))).unwrap();
            oneshot_async(tokio::io::copy(&mut payload, &mut fw)).unwrap();
            oneshot_async(fw.finish()).unwrap();
        }
        oneshot_async(writer.finish(None)).unwrap();

        let (_, error) = extract_xp3(xp3.to_str().unwrap(), out.to_str().unwrap()).unwrap();
        assert_eq!(error, 0);
        let mut names: Vec<String> = std::fs::read_dir(&out).unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        names.sort();
        assert_eq!(names.len(), 2, "both entries must exist, got {names:?}");
        // Case-folded uniqueness is what actually protects /sdcard and FAT.
        let folded: HashSet<String> = names.iter().map(|n| n.to_lowercase()).collect();
        assert_eq!(folded.len(), 2, "case-only collision must be renamed, got {names:?}");
        assert!(names.iter().any(|n| n.contains("(1)")), "renamed variant expected, got {names:?}");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// The token the UI keys off, for the two states that need no cxdec folder:
    /// a plain pack says `plain`, and an archive whose index marks entries
    /// protected says `suspect` — which is also the shape of the vendored real
    /// `sample.xp3` (flag set, content plain).
    #[test]
    fn probe_token_reports_plain_and_suspect() {
        let _g = progress_lock();
        let dir = tmp("probe_token");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("a.txt"), b"hello".to_vec()).unwrap();
        let plain = dir.join("plain.xp3");
        create_xp3(dir.to_str().unwrap(), plain.to_str().unwrap(), 6, "").unwrap();
        assert_eq!(probe_scheme_token(plain.to_str().unwrap()), "plain");

        // A protected entry whose content is NOT readable, written by hand:
        // both halves of the evidence (flag set + nothing recognizable) are
        // needed. With readable content this is "plain" — see the sample below.
        let prot = dir.join("prot.xp3");
        let noise: Vec<u8> = (0..64u8).map(|i| 0x80 | (i & 0x3F)).collect();
        {
            let out_file = File::create(&prot).unwrap();
            let mut writer = oneshot_async(XP3Writer::new(
                XP3Version::Current { minor: 0 },
                SyncIo(BufWriter::new(out_file)),
            )).unwrap();
            let mut fw = oneshot_async(writer.file("a.bin".to_string(), true, Some(6))).unwrap();
            let mut payload: &[u8] = &noise;
            oneshot_async(tokio::io::copy(&mut payload, &mut fw)).unwrap();
            oneshot_async(fw.finish()).unwrap();
            oneshot_async(writer.finish(None)).unwrap();
        }
        assert_eq!(probe_scheme_token(prot.to_str().unwrap()), "suspect");

        // The real vendored sample: current format, protected flag 1, but its
        // payload reads as plaintext. That combination is exactly what a real
        // filter-less Kirikiri archive looks like (measured on a 294 MB /
        // 7926-entry one: every entry flagged, every segment inflating to a
        // valid WebP/TJS), so the flag alone must NOT raise the warning.
        let real = dir.join("sample.xp3");
        std::fs::write(&real, include_bytes!("../../vendor/xp3/sample.xp3")).unwrap();
        assert_eq!(probe_scheme_token(real.to_str().unwrap()), "plain");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// The same archive reports its scheme inside the game folder and `suspect`
    /// once copied out of it — that is exactly the third state the user asked
    /// for, and it is the only signal left when the sidecar is gone.
    #[test]
    fn probe_token_reports_cxdec_then_suspect_when_copied_out() {
        let _g = progress_lock();
        let dir = tmp("probe_cxdec_token");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("xp3filter.tjs"), fake_tjs(0x275, 0x380)).unwrap();
        std::fs::write(dir.join("bg.png"), png_bytes(3000)).unwrap();
        let xp3 = dir.join("data.xp3");
        create_xp3(dir.to_str().unwrap(), xp3.to_str().unwrap(), 6, "cxdec").unwrap();
        let token = probe_scheme_token(xp3.to_str().unwrap());
        assert!(token.starts_with("cxdec:"), "{token}");
        assert!(token.contains("feng"), "{token}");

        let lonely = tmp("probe_cxdec_lonely");
        std::fs::create_dir_all(&lonely).unwrap();
        let copy = lonely.join("data.xp3");
        std::fs::copy(&xp3, &copy).unwrap();
        assert_eq!(probe_scheme_token(copy.to_str().unwrap()), "suspect");
        std::fs::remove_dir_all(&dir).ok();
        std::fs::remove_dir_all(&lonely).ok();
    }

    /// The verify pass accounts for the whole entry even when it only reads a
    /// prefix, so the bar (total = 2 x payload) lands exactly on 100% instead
    /// of stopping short — a bar that never fills reads as "still working".
    /// The threshold is a parameter precisely so this branch is reachable with
    /// a small file instead of a 64 MiB one.
    #[test]
    fn verify_prefix_path_still_lands_on_total() {
        let _g = progress_lock();
        let dir = tmp("enc_prefix");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("xp3filter.tjs"), fake_tjs(0x275, 0x380)).unwrap();
        // Bigger than the verify prefix (64 KiB), so the prefix branch really
        // reads less than the entry — with a small file it would read it whole
        // and the accounting under test would be indistinguishable.
        std::fs::write(dir.join("big.png"), png_bytes(100_000)).unwrap();
        let xp3 = dir.join("data.xp3");
        create_xp3(dir.to_str().unwrap(), xp3.to_str().unwrap(), 6, "cxdec").unwrap();
        assert_eq!(
            compress_progress::bytes(),
            compress_progress::total_bytes(),
            "an encrypted pack must finish at exactly its total",
        );

        // Same verify, threshold below the entry size: the prefix branch must
        // still account for the whole entry.
        compress_progress::reset(200_000);
        let cx = archive_cxdec_core::cipher_by_name(&dir, "cxdec feng template").unwrap();
        let files = vec![(dir.join("big.png"), "big.png".to_string())];
        verify_encrypted_pack(xp3.to_str().unwrap(), &files, &std::sync::Arc::new(std::sync::Mutex::new(cx)), 1000)
            .unwrap();
        assert_eq!(compress_progress::bytes(), 100_000, "prefix verify counts the full entry");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// A synthetic cxdec game folder: `xp3filter.tjs` carrying a control block
    /// (the marker plus filler — the cipher only needs both sides to agree) and
    /// the game's own mask/offset constants in the decode entry.
    fn fake_tjs(mask: u32, offset: u32) -> String {
        let mut bytes = archive_cxdec_core::CONTROL_BLOCK_SIGNATURE.to_vec();
        // Varied filler, not a constant: a constant block makes the generated
        // key stream degenerate (measured: it left ASCII plaintext readable
        // after "encryption", so the encryption-evidence test looked broken
        // while the fixture was the broken part).
        bytes.extend((bytes.len()..4096).map(|i| ((i * 37 + 11) % 251) as u8));
        let arr: Vec<String> = bytes.iter().map(|b| format!("0x{b:02X}")).collect();
        format!(
            "@set(_DEBUG=0)\nclass cxdec {{\n    var tempBlock = [{}];\n\
             function cxdec_decode(hash, offset, buf, len) {{\n\
             var bondary = (hash & 0x{mask:X}) + 0x{offset:X};\n    }}\n}}\n",
            arr.join(", "),
        )
    }

    /// Entries have to LOOK like something for the scheme scorer to score them
    /// (it matches 13 plaintext magics on 64-byte prefixes), and their bytes
    /// have to VARY like a real file's: a constant run, XORed with a constant
    /// stretch of the keystream, leaves a constant ciphertext run that reads as
    /// text — which made the encryption evidence test lie about its own fixture.
    fn png_bytes(n: usize) -> Vec<u8> {
        let mut v = b"\x89PNG\r\n\x1a\n".to_vec();
        v.extend((v.len()..n).map(|i| ((i * 31 + 7) % 251) as u8));
        v
    }

    /// The tjs path: no other archive to learn the scheme from, so the writer
    /// uses the script's constants + the feng ordering, and says so.
    #[test]
    fn encrypted_pack_round_trips_and_notes_the_tjs_source() {
        let _g = progress_lock();
        let dir = tmp("enc_tjs");
        std::fs::create_dir_all(&dir).unwrap();
        // Constants deliberately NOT the feng template's, and one entry far
        // larger than a single write chunk (tokio::io::copy hands over 8 KiB at
        // a time): that is what exercises the cipher's running offset and the
        // base_offset split, which a 3 KB file never would.
        std::fs::write(dir.join("xp3filter.tjs"), fake_tjs(0x2AB, 0x4C1)).unwrap();
        std::fs::write(dir.join("bg.png"), png_bytes(3000)).unwrap();
        std::fs::write(dir.join("big.png"), png_bytes(200_000)).unwrap();
        std::fs::write(dir.join("script.ks"), b"*start\nhello\n".to_vec()).unwrap();
        let xp3 = dir.join("data.xp3");
        create_xp3(dir.to_str().unwrap(), xp3.to_str().unwrap(), 6, "cxdec").unwrap();

        let note = LAST_ENC_NOTE.lock().unwrap().clone();
        assert!(note.contains("\"verified\":false"), "{note}");
        assert!(note.contains("xp3filter.tjs"), "{note}");
        assert!(note.contains("feng"), "{note}");

        // The entries must be marked protected (that is what makes a krkr2
        // filter run over them) and must decrypt back to the source bytes.
        assert!(archive_cxdec_core::content_looks_encrypted(xp3.to_str().unwrap()));
        let mut cx = archive_cxdec_core::cipher_by_name(&dir, "cxdec feng template").unwrap();
        let back = archive_cxdec_core::read_named_with(xp3.to_str().unwrap(), "bg.png", &mut cx).unwrap();
        assert_eq!(back, png_bytes(3000));
        let back2 = archive_cxdec_core::read_named_with(xp3.to_str().unwrap(), "big.png", &mut cx).unwrap();
        assert_eq!(back2, png_bytes(200_000));
        let back3 = archive_cxdec_core::read_named_with(xp3.to_str().unwrap(), "script.ks", &mut cx).unwrap();
        assert_eq!(back3, b"*start\nhello\n".to_vec());
        std::fs::remove_dir_all(&dir).ok();
    }

    /// The verified path: an already-encrypted archive in the folder is the
    /// oracle, and the note says the scheme came from it.
    #[test]
    fn encrypted_pack_prefers_an_existing_encrypted_archive() {
        let _g = progress_lock();
        let odir = tmp("enc_oracle");
        std::fs::create_dir_all(&odir).unwrap();
        std::fs::write(odir.join("xp3filter.tjs"), fake_tjs(0x275, 0x380)).unwrap();
        std::fs::write(odir.join("bg.png"), png_bytes(3000)).unwrap();
        let oracle = odir.join("patch.xp3");
        create_xp3(odir.to_str().unwrap(), oracle.to_str().unwrap(), 6, "cxdec").unwrap();

        // Target folder: the game's script (the control block lives there, and
        // without it nothing can be encrypted) PLUS the oracle next to the
        // output. The oracle decides the scheme; the script only supplies the
        // key table.
        let tdir = tmp("enc_target");
        std::fs::create_dir_all(&tdir).unwrap();
        std::fs::write(tdir.join("xp3filter.tjs"), fake_tjs(0x275, 0x380)).unwrap();
        std::fs::write(tdir.join("bg.png"), png_bytes(4096)).unwrap();
        std::fs::copy(&oracle, tdir.join("patch.xp3")).unwrap();
        let out = tdir.join("data.xp3");
        create_xp3(tdir.to_str().unwrap(), out.to_str().unwrap(), 6, "cxdec").unwrap();

        let note = LAST_ENC_NOTE.lock().unwrap().clone();
        assert!(note.contains("\"verified\":true"), "{note}");
        assert!(note.contains("patch.xp3"), "{note}");

        // Read back with the scheme the ORACLE scores — nothing else is available
        // in this folder, and that is exactly the point.
        let (mut cx, _) =
            archive_cxdec_core::detect_cipher(&tdir, tdir.join("patch.xp3").to_str().unwrap()).unwrap();
        let back = archive_cxdec_core::read_named_with(out.to_str().unwrap(), "bg.png", &mut cx).unwrap();
        assert_eq!(back, png_bytes(4096));
        std::fs::remove_dir_all(&odir).ok();
        std::fs::remove_dir_all(&tdir).ok();
    }

    /// With neither source the pack is refused — and leaves nothing behind.
    #[test]
    fn encrypted_pack_refuses_without_a_scheme_source() {
        let _g = progress_lock();
        let dir = tmp("enc_refuse");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("a.txt"), b"plain".to_vec()).unwrap();
        let out = dir.join("out.xp3");
        let err = create_xp3(dir.to_str().unwrap(), out.to_str().unwrap(), 6, "cxdec").unwrap_err();
        assert!(err.contains("cxdec"), "{err}");
        assert!(!out.exists(), "a refused pack must not leave a file");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// The post-pack verification must actually refuse a bad archive: feeding it
    /// a cipher built from a different scheme has to fail loudly rather than
    /// pass an archive the game could not read.
    #[test]
    fn verify_rejects_a_wrong_cipher() {
        let _g = progress_lock();
        let dir = tmp("enc_verify");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("xp3filter.tjs"), fake_tjs(0x275, 0x380)).unwrap();
        std::fs::write(dir.join("bg.png"), png_bytes(3000)).unwrap();
        let xp3 = dir.join("data.xp3");
        create_xp3(dir.to_str().unwrap(), xp3.to_str().unwrap(), 6, "cxdec").unwrap();

        let wrong = archive_cxdec_core::cipher_by_name(&dir, "cxdec default orders").unwrap();
        let files = vec![(dir.join("bg.png"), "bg.png".to_string())];
        let err = verify_encrypted_pack(
            xp3.to_str().unwrap(),
            &files,
            &std::sync::Arc::new(std::sync::Mutex::new(wrong)),
            VERIFY_FULL_MAX,
        )
        .unwrap_err();
        assert!(err.contains("自校验失败"), "{err}");
        std::fs::remove_dir_all(&dir).ok();
    }
}
