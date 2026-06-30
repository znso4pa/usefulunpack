use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, JNI_TRUE, JNI_FALSE};
use archive_common::{s, SyncIo, oneshot_async, json_escape, derive_dirs, safe_join};
use xp3::read::XP3Archive;
use std::fs::{self, File};
use std::collections::HashSet;
use std::io::{BufReader, BufWriter};

// ─── XP3 (Kirikiri) ────────────────────────

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3Extract(
    mut env: JNIEnv, _class: JClass,
    _tool: JString, input: JString, output: JString,
) -> jboolean {
    let inp = s(&mut env, &input);
    let out = s(&mut env, &output);
    extract_xp3(&inp, &out, &mut env)
}

fn extract_xp3(input: &str, output: &str, env: &mut JNIEnv) -> jboolean {
    let file = match File::open(input) {
        Ok(f) => f, Err(e) => { let _ = env.throw_new("java/io/IOException", format!("{e}")); return JNI_FALSE; }
    };
    let mut archive = match oneshot_async(XP3Archive::open(SyncIo(BufReader::new(file)))) {
        Ok(a) => a, Err(e) => { let _ = env.throw_new("java/io/IOException", format!("XP3: {e}")); return JNI_FALSE; }
    };
    let mut fail_count = 0u32;
    for i in 0..archive.entries().len() {
        let name = &archive.entries()[i].name;
        let dest = match safe_join(output, name) {
            Ok(d) => d,
            Err(_) => { fail_count += 1; continue; }
        };
        if let Some(p) = dest.parent() { let _ = fs::create_dir_all(p); }
        let out_file = match File::create(&dest) {
            Ok(f) => f, Err(_) => { fail_count += 1; continue; }
        };
        let mut out_stream = SyncIo(BufWriter::new(out_file));
        let mut xf = match oneshot_async(archive.by_index(i)) {
            Some(Ok(f)) => f,
            _ => { fail_count += 1; continue; }
        };
        if oneshot_async(tokio::io::copy(&mut xf, &mut out_stream)).is_err() {
            fail_count += 1;
        }
    }
    if fail_count > 0 {
        let _ = env.throw_new("java/io/IOException", format!("XP3: {fail_count} file(s) failed"));
        JNI_FALSE
    } else { JNI_TRUE }
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
) -> jboolean {
    let inp = s(&mut env, &input);
    let out = s(&mut env, &output);
    let sel_str = s(&mut env, &selected);
    extract_xp3_selected(&inp, &out, &sel_str, &mut env)
}

fn extract_xp3_selected(input: &str, output: &str, selected: &str, env: &mut JNIEnv) -> jboolean {
    let sel_set: HashSet<&str> = selected.lines().filter(|l| !l.is_empty()).collect();
    if sel_set.is_empty() { return JNI_FALSE; }
    let file = match File::open(input) {
        Ok(f) => f, Err(e) => { let _ = env.throw_new("java/io/IOException", format!("{e}")); return JNI_FALSE; }
    };
    let mut archive = match oneshot_async(XP3Archive::open(SyncIo(BufReader::new(file)))) {
        Ok(a) => a, Err(e) => { let _ = env.throw_new("java/io/IOException", format!("XP3: {e}")); return JNI_FALSE; }
    };
    let mut fail_count = 0u32;
    for i in 0..archive.entries().len() {
        let raw_name = &archive.entries()[i].name;
        let norm_name = raw_name.replace('\\', "/");
        let should = sel_set.contains(norm_name.as_str()) ||
            sel_set.iter().any(|d| { let dd = if d.ends_with('/') { &d[..d.len()-1] } else { d }; norm_name.starts_with(&format!("{dd}/")) });
        if !should { continue; }
        let dest = match safe_join(output, raw_name) {
            Ok(d) => d,
            Err(_) => { fail_count += 1; continue; }
        };
        if let Some(p) = dest.parent() { let _ = fs::create_dir_all(p); }
        let out_file = match File::create(&dest) { Ok(f) => f, Err(_) => { fail_count += 1; continue; } };
        let mut out_stream = SyncIo(BufWriter::new(out_file));
        let mut xf = match oneshot_async(archive.by_index(i)) { Some(Ok(f)) => f, _ => { fail_count += 1; continue; } };
        if oneshot_async(tokio::io::copy(&mut xf, &mut out_stream)).is_err() { fail_count += 1; }
    }
    if fail_count > 0 {
        let _ = env.throw_new("java/io/IOException", format!("XP3: {fail_count} file(s) failed"));
        JNI_FALSE
    } else { JNI_TRUE }
}
