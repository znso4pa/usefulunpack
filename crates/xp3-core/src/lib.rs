use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jlong};
use archive_common::{s, SyncIo, oneshot_async, json_escape, derive_dirs, safe_join, extract_result_json, ProgressWriter};
use archive_common::extract_progress;
use xp3::read::XP3Archive;
use std::fs::{self, File};
use std::collections::HashSet;
use std::io::{BufReader, BufWriter};

// ─── XP3 (Kirikiri) ────────────────────────

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
    for i in 0..total as usize {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        let name = &archive.entries()[i].name;
        extract_progress::set_name(name);
        let dest = match safe_join(output, name) {
            Ok(d) => d,
            Err(_) => { fail += 1; continue; }
        };
        if let Some(p) = dest.parent() { let _ = fs::create_dir_all(p); }
        let out_file = match File::create(&dest) {
            Ok(f) => f,
            Err(_) => { fail += 1; continue; }
        };
        let mut out_stream = SyncIo(ProgressWriter::extract(BufWriter::new(out_file)));
        let mut xf = match oneshot_async(archive.by_index(i)) {
            Some(Ok(f)) => f,
            _ => { fail += 1; continue; }
        };
        if oneshot_async(tokio::io::copy(&mut xf, &mut out_stream)).is_err() {
            fail += 1;
        }
    }
    Ok((total, fail))
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
    match list_xp3(&inp) {
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
    for i in 0..archive.entries().len() {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        let raw_name = &archive.entries()[i].name;
        if !matches(raw_name) { continue; }
        sel += 1;
        extract_progress::set_name(raw_name);
        let dest = match safe_join(output, raw_name) {
            Ok(d) => d,
            Err(_) => { fail += 1; continue; }
        };
        if let Some(p) = dest.parent() { let _ = fs::create_dir_all(p); }
        let out_file = match File::create(&dest) {
            Ok(f) => f,
            Err(_) => { fail += 1; continue; }
        };
        let mut out_stream = SyncIo(ProgressWriter::extract(BufWriter::new(out_file)));
        let mut xf = match oneshot_async(archive.by_index(i)) {
            Some(Ok(f)) => f,
            _ => { fail += 1; continue; }
        };
        if oneshot_async(tokio::io::copy(&mut xf, &mut out_stream)).is_err() {
            fail += 1;
        }
    }
    Ok((sel, fail))
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3ExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3ExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3ExtractProgressName(env: JNIEnv, _: JClass) -> jstring {
    env.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3ExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }
