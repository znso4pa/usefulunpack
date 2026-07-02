use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, safe_join};
use sevenz_rust::*;
use std::collections::HashSet;
use std::io::Write;

fn list_7z(input: &str) -> Result<String, String> {
    let archive = Archive::open(input).map_err(|e| format!("7z: {e}"))?;
    let mut all: Vec<(String, u64, bool)> = Vec::new();
    for entry in &archive.files {
        let name = entry.name().replace('\\', "/").trim_matches('/').to_string();
        if name.is_empty() { continue; }
        all.push((name.clone(), entry.size(), entry.is_directory()));
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

fn normalize_entry_name(name: &str) -> String {
    name.replace('\\', "/").trim_matches('/').to_string()
}

fn is_selected(name: &str, selected: Option<&HashSet<String>>) -> bool {
    match selected {
        None => true,
        Some(paths) => paths.contains(name) || paths.iter().any(|s| name.starts_with(&format!("{s}/"))),
    }
}

fn extract_7z(input: &str, output: &str, selected: Option<&HashSet<String>>) -> Result<u32, String> {
    decompress_file_with_extract_fn(input, output, |entry, reader, _| {
        let name = normalize_entry_name(entry.name());
        if name.is_empty() {
            return Ok(true);
        }
        let should_extract = is_selected(&name, selected);
        if !should_extract {
            if !entry.is_directory() {
                std::io::copy(reader, &mut std::io::sink()).map_err(Error::io)?;
            }
            return Ok(true);
        }

        let dest = safe_join(output, entry.name()).map_err(Error::other)?;
        if entry.is_directory() {
            if !dest.exists() {
                std::fs::create_dir_all(&dest).map_err(Error::io)?;
            }
            return Ok(true);
        }

        if let Some(parent) = dest.parent() {
            std::fs::create_dir_all(parent).map_err(Error::io)?;
        }
        let file = std::fs::File::create(&dest)
            .map_err(|e| Error::io_msg(e, dest.to_string_lossy().to_string()))?;
        let mut writer = std::io::BufWriter::new(file);
        std::io::copy(reader, &mut writer).map_err(Error::io)?;
        writer.flush().map_err(Error::io)?;
        Ok(true)
    }).map_err(|e| format!("7z: {e}"))?;
    Ok(0)
}

fn extract_7z_all(input: &str, output: &str) -> Result<u32, String> {
    extract_7z(input, output, None)
}

fn extract_7z_selected(input: &str, output: &str, selected: &str) -> Result<u32, String> {
    let paths: HashSet<String> = selected
        .lines()
        .map(normalize_entry_name)
        .filter(|s| !s.is_empty())
        .collect();
    if paths.is_empty() {
        return Ok(0);
    }
    extract_7z(input, output, Some(&paths))
}

fn guarded<T>(f: impl FnOnce() -> Result<T, String>) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|_| Err("panic".to_string()))
}

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = std::fs::create_dir_all(&out);
    match guarded(|| extract_7z_all(&inp, &out)) { Ok(0) => JNI_TRUE, Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("7z: {f} failed")); JNI_FALSE } Err(er) => { let _ = e.throw_new("java/io/IOException", format!("7z: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szExtractSelected(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel);
    match guarded(|| extract_7z_selected(&inp, &out, &sel_str)) { Ok(0) => JNI_TRUE, Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("7z: {f} failed")); JNI_FALSE } Err(er) => { let _ = e.throw_new("java/io/IOException", format!("7z: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    match list_7z(&s(&mut e, &i)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}
