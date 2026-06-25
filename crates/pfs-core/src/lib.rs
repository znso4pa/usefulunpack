use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, derive_dirs};
use pf8::Pf8Archive;
use std::fs;
use std::collections::HashSet;
use std::path::Path;

// ─── PFS (Artemis) ──────────────────────────

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_PfsCore_pfsExtract(
    mut env: JNIEnv, _class: JClass,
    _tool: JString, input: JString, output: JString,
) -> jboolean {
    let inp = s(&mut env, &input);
    let out = s(&mut env, &output);
    let _ = fs::create_dir_all(&out);
    match Pf8Archive::open(Path::new(&inp)) {
        Ok(mut a) => { let _ = a.extract_all(&out); JNI_TRUE }
        Err(e) => { let _ = env.throw_new("java/io/IOException", format!("PFS: {e}")); JNI_FALSE }
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
) -> jboolean {
    let inp = s(&mut env, &input);
    let out = s(&mut env, &output);
    let sel_str = s(&mut env, &selected);
    extract_pfs_selected(&inp, &out, &sel_str, &mut env)
}

fn extract_pfs_selected(input: &str, output: &str, selected: &str, env: &mut JNIEnv) -> jboolean {
    let sel_set: HashSet<&str> = selected.lines().filter(|l| !l.is_empty()).collect();
    if sel_set.is_empty() { return JNI_FALSE; }
    let _ = fs::create_dir_all(&output);
    let mut archive = match Pf8Archive::open(Path::new(input)) {
        Ok(a) => a, Err(e) => { let _ = env.throw_new("java/io/IOException", format!("PFS: {e}")); return JNI_FALSE; }
    };
    let to_extract: Vec<std::path::PathBuf> = archive.entries()
        .map(|e| e.path().to_path_buf())
        .filter(|p| {
            let norm = p.to_string_lossy().replace('\\', "/");
            sel_set.contains(norm.as_str()) ||
                sel_set.iter().any(|sel_dir| {
                    let sd = if sel_dir.ends_with('/') { &sel_dir[..sel_dir.len()-1] } else { sel_dir };
                    norm.starts_with(&format!("{sd}/"))
                })
        }).collect();
    let mut fail_count = 0u32;
    for entry_path in &to_extract {
        let mut dest = Path::new(output).to_path_buf();
        if let Ok(rel) = entry_path.strip_prefix("/") { dest.push(rel); }
        else { for comp in entry_path.components().skip(1) { dest.push(comp); } }
        if dest.to_string_lossy().is_empty() { continue; }
        if let Some(p) = dest.parent() { let _ = fs::create_dir_all(p); }
        if archive.extract_file(entry_path, &dest).is_err() { fail_count += 1; }
    }
    if fail_count > 0 {
        let _ = env.throw_new("java/io/IOException", format!("PFS: {fail_count} file(s) failed"));
        JNI_FALSE
    } else { JNI_TRUE }
}
