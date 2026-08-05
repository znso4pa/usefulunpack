use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jlong};
use archive_common::{s, json_escape, derive_dirs, safe_join, extract_result_json};
use archive_common::extract_progress;
use pf8::Pf8Archive;
use pf8::callbacks::{ArchiveHandler, ControlAction, ProgressInfo};
use std::fs;
use std::collections::HashSet;
use std::path::Path;

// ─── PFS (Artemis) ──────────────────────────

/// Feeds pf8's per-file byte progress into the shared extract store.
struct PfsProgress {
    base: u64,
    last: u64,
}

impl ArchiveHandler for PfsProgress {
    fn on_entry_started(&mut self, name: &str) -> ControlAction {
        extract_progress::set_name(name);
        ControlAction::Continue
    }
    fn on_progress(&mut self, info: &ProgressInfo) -> ControlAction {
        if extract_progress::cancelled() { return ControlAction::Abort; }
        let cur = self.base + info.processed_bytes;
        if cur > self.last {
            extract_progress::add_bytes(cur - self.last);
            self.last = cur;
        }
        ControlAction::Continue
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
pub extern "system" fn Java_com_usefulunpacker_PfsCore_pfsExtract(
    mut env: JNIEnv, _class: JClass,
    _tool: JString, input: JString, output: JString,
) -> jstring {
    let inp = s(&mut env, &input); let out = s(&mut env, &output);
    match guarded(move || {
        let _ = fs::create_dir_all(&out);
        let mut archive = Pf8Archive::open(Path::new(&inp)).map_err(|e| format!("PFS: {e}"))?;
        let to_extract: Vec<(std::path::PathBuf, u64)> = archive.entries().map(|e| (e.path().to_path_buf(), e.size() as u64)).collect();
        let total = to_extract.len() as u32;
        extract_progress::reset(to_extract.iter().map(|(_, s)| *s).sum());
        let mut fail = 0u32;
        let mut base = 0u64;
        for (entry_path, _entry_size) in &to_extract {
            if extract_progress::cancelled() { return Err("cancelled".to_string()); }
            let entry_name = entry_path.to_string_lossy();
            match safe_join(&out, &entry_name) {
                Ok(dest) => {
                    if let Some(p) = dest.parent() { let _ = fs::create_dir_all(p); }
                    let mut handler = PfsProgress { base, last: base };
                    if archive.extract_file_with_progress(entry_path, &dest, &mut handler).is_err() { fail += 1; }
                    base = handler.last;
                    if extract_progress::cancelled() { return Err("cancelled".to_string()); }
                }
                Err(_) => { fail += 1; }
            }
        }
        Ok((total, fail))
    }) {
        Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match env.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = env.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}

fn list_pfs(input: &str) -> Result<String, String> {
    let archive = Pf8Archive::open(Path::new(input)).map_err(|e| format!("PFS: {e}"))?;
    let entry_paths: Vec<String> = archive.entries().map(|e| e.path().to_string_lossy().replace('\\', "/")).collect();
    let path_refs: Vec<&str> = entry_paths.iter().map(|s| s.as_str()).collect();
    let dirs = derive_dirs(&path_refs);
    let mut all: Vec<(String, u64, bool, bool)> = Vec::new();
    for d in &dirs { all.push((d.clone(), 0, true, false)); }
    for entry in archive.entries() {
        let p = entry.path().to_string_lossy().replace('\\', "/");
        all.push((p, entry.size() as u64, false, entry.is_encrypted()));
    }
    all.sort_by(|a, b| a.0.cmp(&b.0));
    let entries: Vec<String> = all.iter().map(|(n, s, d, e)| {
        let sz = if *d { 0_u64 } else { *s };
        format!(r#"{{"n":"{}","s":{},"d":{},"e":{}}}"#, json_escape(n), sz, d, e)
    }).collect();
    Ok(format!("[{}]", entries.join(",")))
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_PfsCore_pfsListEntries(
    mut env: JNIEnv, _: JClass, input: JString,
) -> jstring {
    let inp = s(&mut env, &input);
    match list_pfs(&inp) {
        Ok(j) => match env.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() },
        Err(e) => { let _ = env.throw_new("java/io/IOException", format!("listEntries: {e}")); std::ptr::null_mut() }
    }
}

// ─── PFS Selective Extract ───

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_PfsCore_pfsExtractSelected(
    mut env: JNIEnv, _: JClass,
    _t: JString, input: JString, output: JString, selected: JString,
) -> jstring {
    let inp = s(&mut env, &input); let out = s(&mut env, &output); let sel_str = s(&mut env, &selected);
    match guarded(move || extract_pfs_selected(&inp, &out, &sel_str)) {
        Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match env.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = env.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}

fn extract_pfs_selected(input: &str, output: &str, selected: &str) -> Result<(u32, u32), String> {
    let sel_set: HashSet<&str> = selected.lines().filter(|l| !l.is_empty()).collect();
    if sel_set.is_empty() { return Ok((0, 0)); }
    let _ = fs::create_dir_all(&output);
    let mut archive = Pf8Archive::open(Path::new(input)).map_err(|e| format!("PFS: {e}"))?;
    let to_extract: Vec<(std::path::PathBuf, u64)> = archive.entries()
        .map(|e| (e.path().to_path_buf(), e.size() as u64))
        .filter(|(p, _)| {
            let norm = p.to_string_lossy().replace('\\', "/");
            sel_set.contains(norm.as_str()) ||
                sel_set.iter().any(|sel_dir| {
                    let sd = if sel_dir.ends_with('/') { &sel_dir[..sel_dir.len()-1] } else { sel_dir };
                    norm.starts_with(&format!("{sd}/"))
                })
        }).collect();
    let total = to_extract.len() as u32;
    extract_progress::reset(to_extract.iter().map(|(_, s)| *s).sum());
    let mut fail = 0u32;
    let mut base = 0u64;
    for (entry_path, _entry_size) in &to_extract {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        let entry_name = entry_path.to_string_lossy();
        let dest = safe_join(output, &entry_name).map_err(|e| format!("{e}"))?;
        if let Some(p) = dest.parent() { let _ = fs::create_dir_all(p); }
        let mut handler = PfsProgress { base, last: base };
        if archive.extract_file_with_progress(entry_path, &dest, &mut handler).is_err() { fail += 1; }
        base = handler.last;
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
    }
    Ok((total, fail))
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_PfsCore_pfsExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_PfsCore_pfsExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_PfsCore_pfsExtractProgressName(env: JNIEnv, _: JClass) -> jstring {
    env.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_PfsCore_pfsExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }
