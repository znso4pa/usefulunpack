use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, jlong, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, safe_join, extract_result_json, ProgressWriter};
use archive_common::extract_progress;
use std::collections::{HashMap, HashSet};
use std::io::Write;
use std::path::Path;
use std::sync::atomic::{AtomicU32, Ordering};

#[cfg(test)]
use std::sync::Mutex;
#[cfg(test)]
static TEST_LOCK: Mutex<()> = Mutex::new(());

fn list_rar_inner(input: &str) -> Result<String, String> {
    list_rar_inner_with_pw(input, "")
}

/// Lists a single RAR. A header-encrypted (-hp) archive cannot be parsed
/// without the password, so [password] is passed through to the reader.
fn list_rar_inner_with_pw(input: &str, password: &str) -> Result<String, String> {
    let pw = if password.is_empty() { None } else { Some(password.as_bytes()) };
    let archive = rars::ArchiveReader::read_path_with_options(Path::new(input), rar_opts(pw))
        .map_err(|e| format!("rar: {e}"))?;
    let mut all: Vec<(String, u64, bool, bool)> = Vec::new();
    for member in archive.members() {
        let name = member.meta.name_lossy().replace('\\', "/").trim_matches('/').to_string();
        if name.is_empty() { continue; }
        let is_dir = name.ends_with('/');
        let is_enc = member.meta.is_encrypted;
        all.push((name.clone(), member.meta.unpacked_size, is_dir, is_enc));
        let mut path = String::new();
        for part in name.split('/') {
            if part.is_empty() { continue; }
            path = if path.is_empty() { part.to_string() } else { format!("{path}/{part}") };
            if !all.iter().any(|(p,_,_,_)| p == &path) { all.push((path.clone(), 0u64, true, false)); }
        }
    }
    all.sort_by(|a,b| a.0.cmp(&b.0));
    all.dedup_by(|a,b| a.0 == b.0);
    let items: Vec<String> = all.iter().map(|(n,s,d,e)|
        format!(r#"{{"n":"{}","s":{},"d":{},"e":{}}}"#, json_escape(n), *s, *d, *e)
    ).collect();
    Ok(format!("[{}]", items.join(",")))
}

fn rar_writer<'a>(
    sel_set: &'a Option<HashSet<String>>,
    sizes: &'a HashMap<String, u64>,
    stored: &'a HashSet<String>,
    out_base: &'a str,
    fail: &'a AtomicU32,
) -> impl FnMut(&rars::ExtractedEntryMeta) -> Result<Box<dyn Write>, rars::Error> + 'a {
    move |meta| {
        if extract_progress::cancelled() { return Err(rars::Error::Cancelled); }
        let name = meta.name_lossy().replace('\\', "/").trim_matches('/').to_string();
        if meta.is_directory || name.is_empty() || name.ends_with('/') {
            if let Ok(dest) = safe_join(out_base, &name) {
                std::fs::create_dir_all(&dest).ok();
            }
            return Ok(Box::new(std::io::sink()) as Box<dyn Write>);
        }
        if let Some(ref sel) = sel_set {
            if !sel.contains(&name) && !sel.iter().any(|s| name.starts_with(&format!("{s}/"))) {
                return Ok(Box::new(std::io::sink()) as Box<dyn Write>);
            }
        }
        extract_progress::set_name(&name);
        extract_progress::set_file(sizes.get(&name).copied().unwrap_or(0));
        // No fallback join: a rejected path (../, absolute, drive letter) must
        // never be written — count it as failed and sink the entry, exactly
        // like zip/nsa/7z/pfs.
        let dest = match safe_join(out_base, &name) {
            Ok(d) => d,
            Err(_) => { fail.fetch_add(1, Ordering::SeqCst); return Ok(Box::new(std::io::sink()) as Box<dyn Write>); }
        };
        if let Some(p) = Path::new(&dest).parent() { std::fs::create_dir_all(p).ok(); }
        let out_file = match std::fs::File::create(&dest) {
            Ok(f) => f,
            Err(_) => { fail.fetch_add(1, Ordering::SeqCst); return Ok(Box::new(std::io::sink()) as Box<dyn Write>); }
        };
        // Buffered members (compressed and ≤ the decode limit) decode whole
        // into RAM before writing; the progress watcher feeds the CURRENT-FILE
        // bar from rars decode_progress during that window, so the write must
        // count only the OVERALL bar (exact, no double-count of the file bar).
        // Stored members always stream (write_stored_to) and large members
        // stream — ProgressWriter counts both bars from the write.
        let buffered = !stored.contains(&name) && sizes.get(&name).copied().unwrap_or(0) <= RAR50_BUFFERED_LIMIT;
        if buffered {
            Ok(Box::new(ProgressWriter::extract_top(out_file)) as Box<dyn Write>)
        } else {
            Ok(Box::new(ProgressWriter::extract(out_file)) as Box<dyn Write>)
        }
    }
}

/// Members at or below this size use the whole-member buffered decode path
/// (decode into RAM, then write once); larger members stream (decode+write
/// interleaved) so a 300MB+ member shows byte-level progress. Must match the
/// value passed into [rar_opts] — the extraction path needs the SAME options
/// object (see `extract_rar_inner`), otherwise rars falls back to its 512MB
/// default and mid-size members get buffered, freezing the top progress bar.
const RAR50_BUFFERED_LIMIT: u64 = 64 * 1024 * 1024;

fn rar_opts(pw: Option<&[u8]>) -> rars::ArchiveReadOptions<'_> {
    let mut options = rars::ArchiveReadOptions::with_optional_password(pw);
    options.rar50_buffered_decode_limit = Some(RAR50_BUFFERED_LIMIT);
    options
}

/// Writes one selected member through the same guards as `rar_writer`
/// (path safety, fail counting, cancel, per-file progress). Buffered members
/// count only the OVERALL bar on write; streamed ones count both bars.
fn fast_write_member<F>(name: &str, size: u64, out_base: &str, fail: &AtomicU32, write: F) -> rars::Result<()>
where
    F: FnOnce(&mut Box<dyn Write>) -> rars::Result<()>,
{
    if extract_progress::cancelled() {
        return Err(rars::Error::Cancelled);
    }
    extract_progress::set_name(name);
    extract_progress::set_file(size);
    let dest = match safe_join(out_base, name) {
        Ok(d) => d,
        Err(_) => {
            fail.fetch_add(1, Ordering::SeqCst);
            return Ok(());
        }
    };
    if let Some(p) = Path::new(&dest).parent() {
        std::fs::create_dir_all(p).ok();
    }
    let out_file = match std::fs::File::create(&dest) {
        Ok(f) => f,
        Err(_) => {
            fail.fetch_add(1, Ordering::SeqCst);
            return Ok(());
        }
    };
    let buffered = size <= RAR50_BUFFERED_LIMIT;
    let mut out: Box<dyn Write> = if buffered {
        Box::new(ProgressWriter::extract_top(out_file))
    } else {
        Box::new(ProgressWriter::extract(out_file))
    };
    write(&mut out)
}

/// Selected-extraction fast path for NON-solid archives. RAR has no central
/// directory: the sequential extractor decodes every member in order (output of
/// unselected members is discarded), so previewing a late txt costs decoding
/// all preceding members. Non-solid members decode independently from their own
/// data range, so this seeks straight to each selected member and skips the
/// rest with zero decode — like zip's random access. Returns Ok(false) when the
/// archive can't be fast-pathed (solid / rar13 / split members / redirections /
/// volumes), and the caller falls back to the sequential extractor.
fn extract_selected_fast(
    archive: &rars::Archive,
    pw: Option<&[u8]>,
    sel_set: &HashSet<String>,
    sizes: &HashMap<String, u64>,
    out_base: &str,
    fail: &AtomicU32,
) -> rars::Result<bool> {
    fn selected(name: &str, sel: &HashSet<String>) -> bool {
        sel.contains(name) || sel.iter().any(|s| name.starts_with(&format!("{s}/")))
    }
    match archive {
        rars::Archive::Rar50Plus(a) if !a.main.is_solid() => {
            for f in a.files() {
                if f.is_split_before() || f.is_split_after() || f.redirection.is_some() {
                    return Ok(false);
                }
            }
            for f in a.files() {
                let name = f.name_lossy().replace('\\', "/").trim_matches('/').to_string();
                if f.is_directory() || name.is_empty() || name.ends_with('/') { continue; }
                if !selected(&name, sel_set) { continue; }
                let size = sizes.get(&name).copied().unwrap_or(0);
                fast_write_member(&name, size, out_base, fail, |out| {
                    f.write_to_with_options(a, rar_opts(pw), out)
                })?;
            }
            Ok(true)
        }
        rars::Archive::Rar15To40(a) if !a.main.is_solid() => {
            for f in a.files() {
                if f.is_split_before() || f.is_split_after() { return Ok(false); }
            }
            for f in a.files() {
                let name = f.name_lossy().replace('\\', "/").trim_matches('/').to_string();
                if f.is_directory() || name.is_empty() || name.ends_with('/') { continue; }
                if !selected(&name, sel_set) { continue; }
                let size = sizes.get(&name).copied().unwrap_or(0);
                fast_write_member(&name, size, out_base, fail, |out| f.write_to(a, pw, out))?;
            }
            Ok(true)
        }
        _ => Ok(false),
    }
}

fn read_volumes(paths: &[&str], pw: Option<&[u8]>) -> Result<Vec<rars::Archive>, String> {
    let mut archives = Vec::with_capacity(paths.len());
    for p in paths {
        archives.push(rars::ArchiveReader::read_path_with_options(Path::new(p), rar_opts(pw)).map_err(|e| format!("rar: {e}"))?);
    }
    Ok(archives)
}

fn extract_rar_inner(input: &str, output: &str, selected: Option<&HashSet<String>>, password: &str) -> Result<(u32, u32), String> {
    let pw: Option<&[u8]> = Some(password.as_bytes());
    let archive = rars::ArchiveReader::read_path_with_options(Path::new(input), rar_opts(pw)).map_err(|e| format!("rar: {e}"))?;
    let sel_set: Option<HashSet<String>> = selected.map(|s| s.iter().map(|x| x.to_string()).collect());
    let out_base = output.to_string();

    let mut total = 0u32;
    let mut prog_total = 0u64;
    let mut sizes: HashMap<String, u64> = HashMap::new();
    let mut stored: HashSet<String> = HashSet::new();
    for member in archive.members() {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        let name = member.meta.name_lossy().replace('\\', "/").trim_matches('/').to_string();
        if member.meta.is_directory || name.is_empty() || name.ends_with('/') { continue; }
        if member.meta.is_stored { stored.insert(name.clone()); }
        let matches = match &sel_set {
            None => true,
            Some(sel) => sel.contains(&name) || sel.iter().any(|s| name.starts_with(&format!("{s}/"))),
        };
        if matches {
            total += 1;
            prog_total += member.meta.unpacked_size;
            sizes.insert(name, member.meta.unpacked_size);
        }
    }
    extract_progress::reset(prog_total);
    let fail = AtomicU32::new(0);
    let result = run_with_cancel_monitor(|| {
        // NOTE: must pass rar_opts(pw) here — `extract_to` builds its own
        // default options (rars' 512MB buffered limit) and would silently drop
        // RAR50_BUFFERED_LIMIT, buffering 100-500MB members and freezing the
        // top progress bar for the whole member.
        if let Some(sel) = &sel_set {
            if extract_selected_fast(&archive, pw, sel, &sizes, &out_base, &fail)? {
                return Ok(());
            }
        }
        archive.extract_to_with_options(rar_opts(pw), rar_writer(&sel_set, &sizes, &stored, &out_base, &fail))
    });
    result.map_err(|e| format!("rar: {e}"))?;
    Ok((total, fail.load(Ordering::SeqCst)))
}

/// Runs [f] while a background thread mirrors the rar-core cancel flag into the
/// vendored rars whole-member decode flag, so a cancel lands promptly inside a
/// large buffered (≤ limit) member instead of waiting for it to finish.
fn run_with_cancel_monitor<T>(f: impl FnOnce() -> rars::Result<T>) -> rars::Result<T> {
    rars::codec::rar50::set_decode_cancel(false);
    let done = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let done2 = done.clone();
    let watcher = std::thread::spawn(move || {
        // Bridge two things from rar-core's cancel/progress state into the
        // vendored rars whole-member decode path:
        //  1. cancel flag → rars DECODE_CANCEL (prompt abort of a buffered member)
        //  2. rars DECODE_PROGRESS → extract_progress::set_file_bytes (the
        //     CURRENT-FILE bar moves while a ≤limit member decodes into RAM).
        //     Only mirrored while a buffered decode is actually running — for
        //     large (>limit) members that stream, decode_progress holds a stale
        //     value and must NOT clobber the write-driven file bar. The OVERALL
        //     bar is fed only by ProgressWriter on write (exact; a decode-poll
        //     would under-count members decoded inside one poll window), so the
        //     two never double-count or lose bytes.
        loop {
            if done2.load(std::sync::atomic::Ordering::SeqCst) {
                break;
            }
            if extract_progress::cancelled() {
                rars::codec::rar50::set_decode_cancel(true);
            }
            if rars::codec::rar50::decode_buffered_active() {
                extract_progress::set_file_bytes(rars::codec::rar50::decode_progress());
            }
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
        rars::codec::rar50::set_decode_cancel(true);
    });
    let result = f();
    done.store(true, std::sync::atomic::Ordering::SeqCst);
    let _ = watcher.join();
    rars::codec::rar50::set_decode_cancel(false);
    result
}

fn extract_rar_volumes_inner(paths: &[&str], output: &str, selected: Option<&HashSet<String>>, password: &str) -> Result<(u32, u32), String> {
    let pw: Option<&[u8]> = Some(password.as_bytes());
    let sel_set: Option<HashSet<String>> = selected.map(|s| s.iter().map(|x| x.to_string()).collect());
    let out_base = output.to_string();
    let archives = read_volumes(paths, pw)?;

    let mut total = 0u32;
    let mut prog_total = 0u64;
    let mut seen: HashSet<String> = HashSet::new();
    let mut sizes: HashMap<String, u64> = HashMap::new();
    let mut stored: HashSet<String> = HashSet::new();
    for archive in &archives {
        for member in archive.members() {
            if extract_progress::cancelled() { return Err("cancelled".to_string()); }
            let name = member.meta.name_lossy().replace('\\', "/").trim_matches('/').to_string();
            if member.meta.is_directory || name.is_empty() || name.ends_with('/') { continue; }
            if !seen.insert(name.clone()) { continue; }
            if member.meta.is_stored { stored.insert(name.clone()); }
            let matches = match &sel_set {
                None => true,
                Some(sel) => sel.contains(&name) || sel.iter().any(|s| name.starts_with(&format!("{s}/"))),
            };
            if matches {
                total += 1;
                prog_total += member.meta.unpacked_size;
                sizes.insert(name, member.meta.unpacked_size);
            }
        }
    }
    extract_progress::reset(prog_total);
    let fail = AtomicU32::new(0);
    let result = run_with_cancel_monitor(|| {
        rars::extract_volumes_to_with_options(&archives, rar_opts(pw), rar_writer(&sel_set, &sizes, &stored, &out_base, &fail))
    });
    result.map_err(|e| format!("rar: {e}"))?;
    Ok((total, fail.load(Ordering::SeqCst)))
}

fn rar_needs_password_inner(input: &str) -> Result<bool, String> {
    match rars::ArchiveReader::read_path(Path::new(input)) {
        Ok(archive) => Ok(archive.members().any(|m| m.meta.is_encrypted)),
        // Header-encrypted (-hp) archives cannot be parsed without a password —
        // the read failure IS the "needs password" signal.
        Err(_) => Ok(true),
    }
}

fn rar_volumes_needs_password_inner(paths: &[&str]) -> Result<bool, String> {
    match read_volumes(paths, None) {
        Ok(archives) => Ok(archives.iter().any(|a| a.members().any(|m| m.meta.is_encrypted))),
        Err(_) => Ok(true),
    }
}

fn list_rar_volumes_inner(paths: &[&str]) -> Result<String, String> {
    list_rar_volumes_inner_with_pw(paths, "")
}

/// Lists a multi-volume RAR. Header-encrypted (-hp) sets need the password to
/// parse the header of each volume.
fn list_rar_volumes_inner_with_pw(paths: &[&str], password: &str) -> Result<String, String> {
    let pw = if password.is_empty() { None } else { Some(password.as_bytes()) };
    let archives = read_volumes(paths, pw)?;
    let mut all: Vec<(String, u64, bool, bool)> = Vec::new();
    let mut seen: HashSet<String> = HashSet::new();
    for archive in &archives {
        for member in archive.members() {
            let name = member.meta.name_lossy().replace('\\', "/").trim_matches('/').to_string();
            if name.is_empty() { continue; }
            let is_dir = name.ends_with('/');
            let is_enc = member.meta.is_encrypted;
            if !seen.insert(name.clone()) { continue; }
            all.push((name.clone(), member.meta.unpacked_size, is_dir, is_enc));
            let mut path = String::new();
            for part in name.split('/') {
                if part.is_empty() { continue; }
                path = if path.is_empty() { part.to_string() } else { format!("{path}/{part}") };
                if !all.iter().any(|(p,_,_,_)| p == &path) { all.push((path.clone(), 0u64, true, false)); }
            }
        }
    }
    all.sort_by(|a,b| a.0.cmp(&b.0));
    all.dedup_by(|a,b| a.0 == b.0);
    let items: Vec<String> = all.iter().map(|(n,s,d,e)|
        format!(r#"{{"n":"{}","s":{},"d":{},"e":{}}}"#, json_escape(n), *s, *d, *e)
    ).collect();
    Ok(format!("[{}]", items.join(",")))
}

fn guarded<T: Send + 'static>(f: impl FnOnce() -> Result<T, String> + Send + 'static) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|panic| {
        let msg = panic.downcast_ref::<&str>().copied()
            .or_else(|| panic.downcast_ref::<String>().map(|s| s.as_str()))
            .unwrap_or("unknown panic");
        Err(format!("panic: {msg}"))
    })
}

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i); match guarded(move || list_rar_inner(&inp)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("{er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarListEntriesWithPassword(mut e: JNIEnv, _: JClass, i: JString, pw: JString) -> jstring {
    let inp = s(&mut e, &i); let pwd = s(&mut e, &pw);
    match guarded(move || list_rar_inner_with_pw(&inp, &pwd)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("{er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = std::fs::create_dir_all(&out);
    match guarded(move || extract_rar_inner(&inp, &out, None, "")) { Ok((total, f)) => { let json = extract_result_json(total, total.saturating_sub(f), f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractSelected(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel);
    let ss: HashSet<String> = sel_str.lines().filter(|l| !l.is_empty()).map(|s| s.to_string()).collect();
    match guarded(move || extract_rar_inner(&inp, &out, Some(&ss), "")) { Ok((total, f)) => { let json = extract_result_json(total, total.saturating_sub(f), f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractWithPassword(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, pw: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let pwd = s(&mut e, &pw); let _ = std::fs::create_dir_all(&out);
    match guarded(move || extract_rar_inner(&inp, &out, None, &pwd)) { Ok((total, f)) => { let json = extract_result_json(total, total.saturating_sub(f), f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarNeedsPassword(mut e: JNIEnv, _: JClass, i: JString) -> jboolean {
    let inp = s(&mut e, &i);
    match guarded(move || rar_needs_password_inner(&inp)) { Ok(true) => JNI_TRUE, Ok(false) => JNI_FALSE, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("rar: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractSelectedWithPassword(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel: JString, pw: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel); let pwd = s(&mut e, &pw); let _ = std::fs::create_dir_all(&out);
    let ss: HashSet<String> = sel_str.lines().filter(|l| !l.is_empty()).map(|s| s.to_string()).collect();
    match guarded(move || extract_rar_inner(&inp, &out, Some(&ss), &pwd)) { Ok((total, f)) => { let json = extract_result_json(total, total.saturating_sub(f), f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractProgressFileCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }

fn volume_refs(vols: &[String]) -> Vec<&str> { vols.iter().map(|s| s.as_str()).collect() }

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarListEntriesVolumes(mut e: JNIEnv, _: JClass, v: JString) -> jstring {
    let vs = s(&mut e, &v); let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(move || list_rar_volumes_inner(&volume_refs(&vols))) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("{er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarListEntriesVolumesWithPassword(mut e: JNIEnv, _: JClass, v: JString, pw: JString) -> jstring {
    let vs = s(&mut e, &v); let pwd = s(&mut e, &pw);
    let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(move || list_rar_volumes_inner_with_pw(&volume_refs(&vols), &pwd)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("{er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractVolumes(mut e: JNIEnv, _: JClass, _t: JString, v: JString, o: JString) -> jstring {
    extract_progress::clear_cancel();
    let vs = s(&mut e, &v); let out = s(&mut e, &o); let _ = std::fs::create_dir_all(&out);
    let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(move || extract_rar_volumes_inner(&volume_refs(&vols), &out, None, "")) { Ok((total, f)) => { let json = extract_result_json(total, total.saturating_sub(f), f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractSelectedVolumes(mut e: JNIEnv, _: JClass, _t: JString, v: JString, o: JString, sel: JString) -> jstring {
    extract_progress::clear_cancel();
    let vs = s(&mut e, &v); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel);
    let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    let ss: HashSet<String> = sel_str.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(move || extract_rar_volumes_inner(&volume_refs(&vols), &out, Some(&ss), "")) { Ok((total, f)) => { let json = extract_result_json(total, total.saturating_sub(f), f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractVolumesWithPassword(mut e: JNIEnv, _: JClass, _t: JString, v: JString, o: JString, pw: JString) -> jstring {
    extract_progress::clear_cancel();
    let vs = s(&mut e, &v); let out = s(&mut e, &o); let pwd = s(&mut e, &pw); let _ = std::fs::create_dir_all(&out);
    let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(move || extract_rar_volumes_inner(&volume_refs(&vols), &out, None, &pwd)) { Ok((total, f)) => { let json = extract_result_json(total, total.saturating_sub(f), f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractSelectedVolumesWithPassword(mut e: JNIEnv, _: JClass, _t: JString, v: JString, o: JString, sel: JString, pw: JString) -> jstring {
    extract_progress::clear_cancel();
    let vs = s(&mut e, &v); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel); let pwd = s(&mut e, &pw); let _ = std::fs::create_dir_all(&out);
    let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    let ss: HashSet<String> = sel_str.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(move || extract_rar_volumes_inner(&volume_refs(&vols), &out, Some(&ss), &pwd)) { Ok((total, f)) => { let json = extract_result_json(total, total.saturating_sub(f), f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarVolumesNeedsPassword(mut e: JNIEnv, _: JClass, v: JString) -> jboolean {
    let vs = s(&mut e, &v); let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(move || rar_volumes_needs_password_inner(&volume_refs(&vols))) { Ok(true) => JNI_TRUE, Ok(false) => JNI_FALSE, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("rar: {er}")); JNI_FALSE } }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rars::rar13::{write_stored_volumes, StoredEntry, WriterOptions};
    use rars::features::FeatureSet;
    use rars::version::ArchiveVersion;

    fn make_volumes() -> Vec<std::path::PathBuf> {
        let data: Vec<u8> = (0..4096u32).map(|i| (i % 251) as u8).collect();
        let entry = StoredEntry {
            name: b"data/file.bin",
            data: &data,
            file_time: 0,
            file_attr: 0,
            password: None,
            file_comment: None,
        };
        let opts = WriterOptions::new(ArchiveVersion::Rar14, FeatureSet::store_only());
        let vols = write_stored_volumes(entry, opts, 1024).expect("write volumes");
        assert!(vols.len() >= 2, "expected multiple volumes, got {}", vols.len());
        let dir = std::env::temp_dir().join(format!("uu_rar_vol_test_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let mut paths = Vec::new();
        for (i, v) in vols.iter().enumerate() {
            let p = dir.join(format!("vol{i}.rar"));
            std::fs::write(&p, v).unwrap();
            paths.push(p);
        }
        paths
    }

    #[test]
    fn lists_and_extracts_multivolume_rar() {
        let _g = crate::TEST_LOCK.lock().unwrap();
        let vols = make_volumes();
        let paths: Vec<String> = vols.iter().map(|p| p.to_string_lossy().to_string()).collect();
        let refs: Vec<&str> = paths.iter().map(|s| s.as_str()).collect();

        let list = list_rar_volumes_inner(&refs).expect("list volumes");
        assert!(list.contains("data/file.bin"), "missing entry in {list}");

        let out = std::env::temp_dir().join(format!("uu_rar_vol_out_{}", std::process::id()));
        let out_s = out.to_string_lossy().to_string();
        extract_rar_volumes_inner(&refs, &out_s, None, "").expect("extract volumes");
        let got = std::fs::read(out.join("data/file.bin")).expect("read extracted");
        assert_eq!(got.len(), 4096);
        assert_eq!(got[100], 100);
        assert_eq!(got[4095], (4095u32 % 251) as u8);
        std::fs::remove_dir_all(&out).ok();
        for p in &vols { std::fs::remove_file(p).ok(); }
        if let Some(dir) = vols[0].parent() { std::fs::remove_dir_all(dir).ok(); }
    }

    /// rar_needs_password_inner: a readable archive with no encrypted members
    /// → false; an unparseable RAR header (the -hp header-encrypted case, where
    /// the reader cannot even open the archive without a password) → true, so
    /// the app prompts for a password instead of silently failing.
    #[test]
    fn needs_password_true_on_unparseable_header() {
        let _g = crate::TEST_LOCK.lock().unwrap();
        let dir = std::env::temp_dir().join(format!("uu_rar_np_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();

        // A normal stored RAR: no password needed. (Rar14 volume writer needs
        // >= 2 volumes, so the payload must exceed the 1024B split size.)
        let data: Vec<u8> = (0..4096u32).map(|i| (i % 251) as u8).collect();
        let entry = StoredEntry {
            name: b"plain.bin",
            data: &data,
            file_time: 0,
            file_attr: 0,
            password: None,
            file_comment: None,
        };
        let opts = WriterOptions::new(ArchiveVersion::Rar14, FeatureSet::store_only());
        let vols = write_stored_volumes(entry, opts, 1024).expect("write");
        let plain = dir.join("plain.rar");
        std::fs::write(&plain, &vols[0]).unwrap();
        assert!(!rar_needs_password_inner(plain.to_str().unwrap()).expect("plain needs_pw"),
            "unencrypted rar must report false");

        // Garbage / a header the reader cannot parse (mirrors -hp) → true.
        let junk = dir.join("junk.rar");
        std::fs::write(&junk, b"\x52\x61\x72\x21\x1a\x07\x01\x00\xDE\xAD\xBE\xEF garbage").unwrap();
        assert!(rar_needs_password_inner(junk.to_str().unwrap()).expect("junk needs_pw"),
            "unparseable header must be treated as needing a password");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// Cancelling before/during a buffered (whole-member) decode must surface a
    /// Cancelled error promptly instead of running the member to completion —
    /// the cancel monitor mirrors the extract-progress flag into the vendored
    /// rars whole-member decode flag.
    #[test]
    fn cancel_aborts_buffered_decode() {
        let _g = crate::TEST_LOCK.lock().unwrap();
        let dir = std::env::temp_dir().join(format!("uu_rar_cancel_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let data: Vec<u8> = (0..4096u32).map(|i| (i % 251) as u8).collect();
        let entry = StoredEntry {
            name: b"a.bin",
            data: &data,
            file_time: 0,
            file_attr: 0,
            password: None,
            file_comment: None,
        };
        let opts = WriterOptions::new(ArchiveVersion::Rar14, FeatureSet::store_only());
        let vols = write_stored_volumes(entry, opts, 1024).expect("write");
        let arc = dir.join("c.rar");
        std::fs::write(&arc, &vols[0]).unwrap();
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();

        // Pre-set the cancel flag; the monitor must bridge it and the extract
        // must error (not complete) — stored entries stream, so the cancel is
        // caught by the member-boundary check in rar_writer.
        extract_progress::cancel();
        let r = extract_rar_inner(arc.to_str().unwrap(), out.to_str().unwrap(), None, "");
        extract_progress::clear_cancel();
        assert!(r.is_err(), "cancelled extract must error: {r:?}");
        assert_eq!(r.unwrap_err(), "cancelled", "must report cancelled");
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn traversal_entry_rejected_and_counted() {
        // An entry named "../evil.txt" must never be written anywhere — the
        // safe_join failure counts the entry as failed and sinks its data
        // (no fallback join that escapes the output directory).
        let _g = crate::TEST_LOCK.lock().unwrap();
        let data: Vec<u8> = (0..2048u32).map(|i| (i % 251) as u8).collect();
        let entry = StoredEntry {
            name: b"../evil.txt",
            data: &data,
            file_time: 0,
            file_attr: 0,
            password: None,
            file_comment: None,
        };
        let opts = WriterOptions::new(ArchiveVersion::Rar14, FeatureSet::store_only());
        // The RAR13 writer always splits into at least two volumes.
        let vols = write_stored_volumes(entry, opts, 1024).expect("write archive");
        assert!(vols.len() >= 2);
        let dir = std::env::temp_dir().join(format!("uu_rar_trav_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let mut paths = Vec::new();
        for (i, v) in vols.iter().enumerate() {
            let p = dir.join(format!("vol{i}.rar"));
            std::fs::write(&p, v).unwrap();
            paths.push(p);
        }
        let path_strs: Vec<String> = paths.iter().map(|p| p.to_string_lossy().to_string()).collect();
        let refs: Vec<&str> = path_strs.iter().map(|s| s.as_str()).collect();
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let (total, fail) = extract_rar_volumes_inner(
            &refs, out.to_str().unwrap(), None, ""
        ).expect("extract must complete");
        assert_eq!(total, 1);
        assert_eq!(fail, 1, "traversal entry must be counted as failed");
        // out/../evil.txt == dir/evil.txt is the exact escape target
        assert!(!dir.join("evil.txt").exists(), "must not escape into the parent dir");
        assert!(!out.join("evil.txt").exists());
        assert_eq!(std::fs::read_dir(&out).unwrap().count(), 0, "nothing may be written");
        std::fs::remove_dir_all(&dir).ok();
    }
}

/// Manual host verification against real split RAR files.
/// Set `UU_RAR_PARTS` (colon-separated part paths) and optional `UU_RAR_PASS`.
/// Skips (passes silently) when the env var is absent.
#[cfg(test)]
mod manual_volumes {
    use super::*;
    use std::time::Instant;

    fn extract_names(json: &str) -> Vec<String> {
        let mut names = Vec::new();
        let bytes = json.as_bytes();
        let mut i = 0;
        while i < bytes.len() {
            if bytes[i..].starts_with(br#""n":""#) {
                let start = i + 5;
                let mut j = start;
                while j < bytes.len() {
                    let b = bytes[j];
                    if b == b'\\' { j += 2; continue; }
                    if b == b'"' { break; }
                    j += 1;
                }
                if let Ok(s) = std::str::from_utf8(&bytes[start..j]) {
                    names.push(s.to_string());
                }
                i = j + 1;
            } else {
                i += 1;
            }
        }
        names
    }

    fn count_files(dir: &std::path::Path) -> usize {
        let mut n = 0;
        if let Ok(entries) = std::fs::read_dir(dir) {
            for e in entries.flatten() {
                if e.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                    n += count_files(&e.path());
                } else {
                    n += 1;
                }
            }
        }
        n
    }

    fn sha256_file(path: &std::path::Path) -> Option<String> {
        let out = std::process::Command::new("shasum").arg("-a").arg("256").arg(path).output().ok()?;
        if !out.status.success() { return None; }
        Some(String::from_utf8_lossy(&out.stdout).split_whitespace().next().unwrap_or("").to_string())
    }

    fn walk_files(dir: &std::path::Path, out: &mut Vec<std::path::PathBuf>) {
        let mut stack: Vec<std::path::PathBuf> = vec![dir.to_path_buf()];
        while let Some(d) = stack.pop() {
            if let Ok(entries) = std::fs::read_dir(&d) {
                for e in entries.flatten() {
                    if e.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                        stack.push(e.path());
                    } else {
                        out.push(e.path());
                    }
                }
            }
        }
    }

    #[test]
    fn manual_rar_volumes() {
        let _g = crate::TEST_LOCK.lock().unwrap();
        let Ok(parts) = std::env::var("UU_RAR_PARTS") else {
            eprintln!("[manual_rar] skipped: UU_RAR_PARTS not set");
            return;
        };
        let pass = std::env::var("UU_RAR_PASS").unwrap_or_default();
        let paths: Vec<String> = parts.split(':').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect();
        if paths.is_empty() {
            eprintln!("[manual_rar] skipped: empty parts");
            return;
        }
        let refs: Vec<&str> = paths.iter().map(|s| s.as_str()).collect();
        println!("[manual_rar] parts = {}  |  UU_RAR_PASS={}", paths.len(), if pass.is_empty() { "(none)" } else { "set" });

        // 1. needs_password (no password; header-encrypted sets will error — expected)
        match rar_volumes_needs_password_inner(&refs) {
            Ok(np) => println!("[manual_rar] needs_password = {np}"),
            Err(e) => println!("[manual_rar] needs_password err (no pw) = {e}"),
        }

        // 2. list (no password)
        let mut all_names: Vec<String> = Vec::new();
        match list_rar_volumes_inner(&refs) {
            Ok(j) => {
                all_names = extract_names(&j);
                println!("[manual_rar] list ok (no pw): {} entries", all_names.len());
                for n in all_names.iter().take(8) { println!("[manual_rar]   - {n}"); }
            }
            Err(e) => println!("[manual_rar] list err (no pw) = {e}"),
        }

        // 3. if password given, read members with password for selected-extract names
        let pw_bytes: Option<&[u8]> = if pass.is_empty() { None } else { Some(pass.as_bytes()) };
        if pw_bytes.is_some() && all_names.is_empty() {
            if let Ok(archives) = read_volumes(&refs, pw_bytes) {
                let mut seen = HashSet::new();
                let mut files = Vec::new();
                for a in &archives {
                    for m in a.members() {
                        let n = m.meta.name_lossy().replace('\\', "/").trim_matches('/').to_string();
                        if m.meta.is_directory || n.is_empty() || n.ends_with('/') { continue; }
                        if seen.insert(n.clone()) { files.push(n); }
                    }
                }
                println!("[manual_rar] members readable with pw: {} files", files.len());
                for n in files.iter().take(8) { println!("[manual_rar]   - {n}"); }
                all_names = files;
            }
        }

        // 4. selected extract FIRST (targets the large filtered member if present)
        if !all_names.is_empty() && !pass.is_empty() {
            let out2 = std::env::temp_dir().join(format!("uu_rar_manual_sel_{}", std::process::id()));
            std::fs::create_dir_all(&out2).unwrap();
            let out2_s = out2.to_string_lossy().to_string();
            let mut set = HashSet::new();
            // prefer the large filtered member to exercise the buffered-decode path
            if let Some(big) = all_names.iter().find(|n| n.to_lowercase().contains("data.xp3") || n.to_lowercase().contains(".xp3")) {
                set.insert(big.clone());
            }
            for n in all_names.iter().take(1) { set.insert(n.clone()); }
            eprintln!("[manual_rar] START selected extract ({} files)", set.len());
            let t0 = Instant::now();
            match extract_rar_volumes_inner(&refs, &out2_s, Some(&set), &pass) {
                Ok((total, fail)) => {
                    println!("[manual_rar] selected extract total={total} fail={fail} files_on_disk={} in {:.2}s", count_files(&out2), t0.elapsed().as_secs_f64());
                    for (i, n) in all_names.iter().enumerate() {
                        if set.contains(n) {
                            let f = std::path::Path::new(&out2_s).join(n);
                            println!("[manual_rar]   sel #{i}: {n}  exists={} size={}", f.exists(), f.metadata().map(|m| m.len()).unwrap_or(0));
                        }
                    }
                }
                Err(e) => println!("[manual_rar] selected extract err = {e}"),
            }
            std::fs::remove_dir_all(&out2).ok();
        }

        // 4b. full extract + SHA-256 verification (when UU_EXPECTED_SHA set)
        if let Ok(exp) = std::env::var("UU_EXPECTED_SHA") {
            let outv = std::env::temp_dir().join(format!("uu_rar_verify_{}", std::process::id()));
            std::fs::create_dir_all(&outv).unwrap();
            let outv_s = outv.to_string_lossy().to_string();
            let t0 = Instant::now();
            match extract_rar_volumes_inner(&refs, &outv_s, None, &pass) {
                Ok((total, fail)) => {
                    println!("[manual_rar] VERIFY extract total={total} fail={fail} in {:.2}s", t0.elapsed().as_secs_f64());
                    let mut files = Vec::new();
                    walk_files(&outv, &mut files);
                    let mut matched = false;
                    for f in &files {
                        let h = sha256_file(f).unwrap_or_default();
                        let ok = h == exp;
                        if ok { matched = true; }
                        println!("[manual_rar]   file={} sha={} expected={} {}", f.strip_prefix(&outv).map(|p| p.to_string_lossy().to_string()).unwrap_or_default(), h, exp, if ok { "MATCH" } else { "DIFF" });
                    }
                    if matched { println!("[manual_rar] VERIFY PASS"); } else { println!("[manual_rar] VERIFY FAIL (no file matched)"); }
                }
                Err(e) => println!("[manual_rar] VERIFY extract err = {e}"),
            }
            std::fs::remove_dir_all(&outv).ok();
        }

        // 5. full extract — only when UU_RAR_FULL=1 (a full 6.8GB archive takes a long time)
        if std::env::var("UU_RAR_FULL").map(|v| v == "1").unwrap_or(false) {
            let out = std::env::temp_dir().join(format!("uu_rar_manual_{}", std::process::id()));
            std::fs::create_dir_all(&out).unwrap();
            let out_s = out.to_string_lossy().to_string();
            eprintln!("[manual_rar] START full extract -> {}", out_s);
            let monitor = std::thread::spawn({
                let out2 = out.clone();
                move || {
                    let mut last = 0u64;
                    for _ in 0..300 {
                        std::thread::sleep(std::time::Duration::from_secs(2));
                        let b = extract_progress::bytes();
                        let t = extract_progress::total_bytes();
                        let f = count_files(&out2);
                        if b != last {
                            last = b;
                            eprintln!("[manual_rar] progress bytes={b} total={t} files_on_disk={f}");
                        }
                    }
                }
            });
            let t0 = Instant::now();
            match extract_rar_volumes_inner(&refs, &out_s, None, &pass) {
                Ok((total, fail)) => {
                    println!("[manual_rar] full extract total={total} fail={fail}  in {:.2}s", t0.elapsed().as_secs_f64());
                    println!("[manual_rar] progress bytes={} total_bytes={} name={}", extract_progress::bytes(), extract_progress::total_bytes(), extract_progress::name());
                    println!("[manual_rar] files on disk = {}", count_files(&out));
                }
                Err(e) => println!("[manual_rar] full extract err = {e}"),
            }
            monitor.join().ok();
            std::fs::remove_dir_all(&out).ok();
        }
    }

    /// Probe: `UU_RAR_PROBE` = path to a real rar. Lists members (name/size/
    /// stored/solid) then extracts the whole archive while logging progress
    /// every 200ms — used to reproduce the "progress bar frozen on a large
    /// member" report with a real 1.1GB galgame archive.
    #[test]
    fn probe_real_archive_progress() {
        let _g = crate::TEST_LOCK.lock().unwrap();
        let Ok(probe) = std::env::var("UU_RAR_PROBE") else {
            eprintln!("[probe] skipped: UU_RAR_PROBE not set");
            return;
        };
        let archive = rars::ArchiveReader::read_path_with_options(Path::new(&probe), rar_opts(None))
            .expect("open archive");
        let solid = match &archive {
            rars::Archive::Rar50Plus(a) => a.main.is_solid(),
            rars::Archive::Rar15To40(a) => a.main.is_solid(),
            rars::Archive::Rar13(_) => false,
            _ => false,
        };
        eprintln!("[probe] family={:?} solid={solid}", archive.family());
        for m in archive.members() {
            let n = m.meta.name_lossy().replace('\\', "/").trim_matches('/').to_string();
            if n.is_empty() || m.meta.is_directory { continue; }
            eprintln!(
                "[probe] {} size={} stored={} enc={}",
                n, m.meta.unpacked_size, m.meta.is_stored, m.meta.is_encrypted
            );
        }

        let out = std::env::temp_dir().join(format!("uu_probe_out_{}", std::process::id()));
        std::fs::create_dir_all(&out).unwrap();
        let out_s = out.to_string_lossy().to_string();
        let monitor = std::thread::spawn(move || {
            let mut last_b = 0u64;
            let mut last_f = 0u64;
            let mut last_n = String::new();
            let mut max_b = 0u64;
            for _ in 0..2000 {
                std::thread::sleep(std::time::Duration::from_millis(200));
                let b = extract_progress::bytes();
                let t = extract_progress::total_bytes();
                let fb = extract_progress::file_bytes();
                let ft = extract_progress::file_total();
                let n = extract_progress::name();
                if b > max_b { max_b = b; }
                if b > t { eprintln!("[probe] !! TOP OVER TOTAL: {b} > {t}"); }
                if fb > ft && ft > 0 { eprintln!("[probe] !! FILE OVER TOTAL: {fb} > {ft} name={n}"); }
                if b != last_b || fb != last_f || n != last_n {
                    last_b = b; last_f = fb; last_n = n.clone();
                    eprintln!("[probe] top={b}/~{t}  file={fb}/{ft}  name={n}");
                }
            }
            eprintln!("[probe] monitor max_top={max_b}");
        });
        let t0 = std::time::Instant::now();
        let r = extract_rar_inner(&probe, &out_s, None, "");
        eprintln!("[probe] extract result={r:?} in {:.2}s", t0.elapsed().as_secs_f64());
        eprintln!(
            "[probe] final top={} total={} (match={})  file={}/{}",
            extract_progress::bytes(),
            extract_progress::total_bytes(),
            extract_progress::bytes() == extract_progress::total_bytes(),
            extract_progress::file_bytes(),
            extract_progress::file_total()
        );
        monitor.join().ok();
        std::fs::remove_dir_all(&out).ok();
    }

    /// Probe the non-solid fast path: `UU_RAR_SEL_PROBE` = path to a real rar.
    /// Selects the LAST text member (or the member named in
    /// `UU_RAR_SEL_NAME`) and extracts it, timing how long it takes — the fast
    /// path must skip the preceding members, so a late txt comes out near
    /// instantly instead of decoding the whole archive.
    #[test]
    fn probe_selected_fast() {
        let _g = crate::TEST_LOCK.lock().unwrap();
        let Ok(probe) = std::env::var("UU_RAR_SEL_PROBE") else {
            eprintln!("[sel] skipped: UU_RAR_SEL_PROBE not set");
            return;
        };
        let archive = rars::ArchiveReader::read_path_with_options(Path::new(&probe), rar_opts(None))
            .expect("open archive");
        let mut names: Vec<String> = Vec::new();
        for m in archive.members() {
            let n = m.meta.name_lossy().replace('\\', "/").trim_matches('/').to_string();
            if !m.meta.is_directory && !n.is_empty() {
                names.push(n);
            }
        }
        let wanted = std::env::var("UU_RAR_SEL_NAME").ok();
        let pick = wanted.unwrap_or_else(|| {
            names.iter().rev().find(|n| n.to_lowercase().ends_with(".txt"))
                .cloned().unwrap_or_else(|| names.last().cloned().unwrap())
        });
        eprintln!("[sel] selecting: {pick}  (member #{} of {})", names.iter().position(|n| n == &pick).unwrap_or(usize::MAX), names.len());
        let mut set = HashSet::new();
        set.insert(pick.clone());
        let out = std::env::temp_dir().join(format!("uu_sel_out_{}", std::process::id()));
        std::fs::create_dir_all(&out).unwrap();
        let out_s = out.to_string_lossy().to_string();
        let t0 = std::time::Instant::now();
        let r = extract_rar_inner(&probe, &out_s, Some(&set), "");
        eprintln!("[sel] selected extract = {r:?} in {:.3}s", t0.elapsed().as_secs_f64());
        let f = std::path::Path::new(&out_s).join(&pick);
        eprintln!("[sel] file exists={} size={}", f.exists(), f.metadata().map(|m| m.len()).unwrap_or(0));
        std::fs::remove_dir_all(&out).ok();
    }
}
