use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, derive_dirs};
use std::collections::HashSet;
use std::io::Read;

fn list_zip_inner(input: &str) -> Result<String, String> {
    let file = std::fs::File::open(input).map_err(|e| format!("{e}"))?;
    let mut archive = zip::ZipArchive::new(file).map_err(|e| format!("{e}"))?;
    let mut all: Vec<(String, u64, bool)> = Vec::with_capacity(archive.len());
    for i in 0..archive.len() {
        let entry = archive.by_index(i).map_err(|e| format!("{e}"))?;
        let name = entry.name().replace('\\', "/").trim_matches('/').to_string();
        if name.is_empty() { continue; }
        let is_dir = entry.is_dir();
        all.push((name.clone(), entry.size(), is_dir));
        let mut path = String::new();
        for part in name.split('/') {
            if part.is_empty() { continue; }
            path = if path.is_empty() { part.to_string() } else { format!("{path}/{part}") };
            if !all.iter().any(|(p,_,_)| p == &path) { all.push((path.clone(), 0u64, true)); }
        }
    }
    all.sort_by(|a,b| a.0.cmp(&b.0));
    all.dedup_by(|a,b| a.0 == b.0);
    let items: Vec<String> = all.iter().map(|(n,s,d)|
        format!(r#"{{"n":"{}","s":{},"d":{},"e":false}}"#, json_escape(n), *s, *d)
    ).collect();
    Ok(format!("[{}]", items.join(",")))
}

fn extract_zip_all_inner(input: &str, output: &str) -> Result<u32, String> {
    let file = std::fs::File::open(input).map_err(|e| format!("{e}"))?;
    let mut archive = zip::ZipArchive::new(file).map_err(|e| format!("{e}"))?;
    let mut fail = 0u32;
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i).map_err(|e| format!("{e}"))?;
        let name = entry.name().replace('\\', "/").trim_matches('/').to_string();
        if name.is_empty() || entry.is_dir() { continue; }
        let mut dest = std::path::Path::new(output).to_path_buf();
        for comp in name.split('/') { if !comp.is_empty() { dest.push(comp); } }
        if let Some(p) = dest.parent() { std::fs::create_dir_all(p).map_err(|e| format!("{e}"))?; }
        let mut out = std::fs::File::create(&dest).map_err(|e| format!("{e}"))?;
        if std::io::copy(&mut entry, &mut out).is_err() { fail += 1; }
    }
    Ok(fail)
}

fn extract_zip_selected_inner(input: &str, output: &str, selected: &str) -> Result<u32, String> {
    let ss: HashSet<&str> = selected.lines().filter(|l| !l.is_empty()).collect();
    if ss.is_empty() { return Ok(0); }
    let file = std::fs::File::open(input).map_err(|e| format!("{e}"))?;
    let mut archive = zip::ZipArchive::new(file).map_err(|e| format!("{e}"))?;
    let mut fail = 0u32;
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i).map_err(|e| format!("{e}"))?;
        let name = entry.name().replace('\\', "/").trim_matches('/').to_string();
        if name.is_empty() || entry.is_dir() { continue; }
        if !ss.contains(name.as_str()) && !ss.iter().any(|s| name.starts_with(&format!("{s}/"))) { continue; }
        let mut dest = std::path::Path::new(output).to_path_buf();
        for comp in name.split('/') { if !comp.is_empty() { dest.push(comp); } }
        if let Some(p) = dest.parent() { std::fs::create_dir_all(p).map_err(|e| format!("{e}"))?; }
        let mut out = std::fs::File::create(&dest).map_err(|e| format!("{e}"))?;
        if std::io::copy(&mut entry, &mut out).is_err() { fail += 1; }
    }
    Ok(fail)
}

fn guarded<T: Send + 'static>(f: impl FnOnce() -> Result<T, String> + Send + 'static) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|e| Err(format!("panic")) )
}

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = std::fs::create_dir_all(&out);
    match guarded(move || extract_zip_all_inner(&inp, &out)) { Ok(0) => JNI_TRUE, Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("ZIP: {f} failed")); JNI_FALSE }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("ZIP: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtractSelected(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel);
    match guarded(move || extract_zip_selected_inner(&inp, &out, &sel_str)) { Ok(0) => JNI_TRUE, Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("ZIP: {f} failed")); JNI_FALSE }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("ZIP: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i);
    match guarded(move || list_zip_inner(&inp)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("{er}")); std::ptr::null_mut() } }
}
