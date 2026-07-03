use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, safe_join};
use std::collections::HashSet;

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
    extract_zip_with_password(input, output, "")
}
fn extract_zip_with_password(input: &str, output: &str, password: &str) -> Result<u32, String> {
    let file = std::fs::File::open(input).map_err(|e| format!("{e}"))?;
    let mut archive = zip::ZipArchive::new(file).map_err(|e| format!("{e}"))?;
    let mut fail = 0u32;
    let pw = if password.is_empty() { None } else { Some(password.as_bytes()) };
    for i in 0..archive.len() {
        let mut entry = if let Some(p) = pw { archive.by_index_decrypt(i, p).map_err(|e| format!("{e}"))? } else { archive.by_index(i).map_err(|e| format!("{e}"))? };
        let name = entry.name().replace('\\', "/").trim_matches('/').to_string();
        if name.is_empty() || entry.is_dir() { continue; }
        let dest = safe_join(output, entry.name()).map_err(|e| format!("{e}"))?;
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
        let dest = safe_join(output, entry.name()).map_err(|e| format!("{e}"))?;
        if let Some(p) = dest.parent() { std::fs::create_dir_all(p).map_err(|e| format!("{e}"))?; }
        let mut out = std::fs::File::create(&dest).map_err(|e| format!("{e}"))?;
        if std::io::copy(&mut entry, &mut out).is_err() { fail += 1; }
    }
    Ok(fail)
}

fn compress_zip_inner(input: &str, output: &str, level: i32, password: &str) -> Result<u32, String> {
    let file = std::fs::File::create(output).map_err(|e| format!("{e}"))?;
    let mut zip = zip::write::ZipWriter::new(file);
    let mut fail = 0u32;
    let method = if level <= 0 { zip::CompressionMethod::Stored } else { zip::CompressionMethod::Deflated };
    let pw = if password.is_empty() { None } else { Some(password) };

    fn add_dir(zip: &mut zip::write::ZipWriter<std::fs::File>, base: &str, rel: &str, method: zip::CompressionMethod, level: i32, pw: Option<&str>) -> Result<u32, String> where zip::write::ZipWriter<std::fs::File>: std::io::Write {
        let dir_path = if rel.is_empty() { base.to_string() } else { format!("{base}/{rel}") };
        let mut fail = 0u32;
        let entries = std::fs::read_dir(&dir_path).map_err(|e| format!("{e}"))?;
        for entry in entries {
            let entry = entry.map_err(|e| format!("{e}"))?;
            let name = entry.file_name().to_string_lossy().to_string();
            let file_rel = if rel.is_empty() { name.clone() } else { format!("{rel}/{name}") };
            let file_type = entry.file_type().map_err(|e| format!("{e}"))?;
            if file_type.is_dir() {
                // Don't add directory entries; ZIP handles dir creation on extract
                fail += add_dir(zip, base, &file_rel, method, level, pw)?;
            } else if file_type.is_file() {
                let base = zip::write::FileOptions::<'_, ()>::default()
                    .compression_method(method)
                    .compression_level(if level <= 0 { None } else { Some(level as i64) });
                if let Some(p) = pw {
                    zip.start_file(&file_rel, base.with_aes_encryption(zip::AesMode::Aes256, p)).map_err(|e| format!("{e}"))?;
                } else {
                    zip.start_file(&file_rel, base).map_err(|e| format!("{e}"))?;
                }
                let mut f = std::fs::File::open(&entry.path()).map_err(|e| format!("{e}"))?;
                if std::io::copy(&mut f, zip).is_err() { fail += 1; }
            }
        }
        Ok(fail)
    }
    fail += add_dir(&mut zip, input, "", method, level, pw)?;
    zip.finish().map_err(|e| format!("{e}"))?;
    Ok(fail)
}

fn guarded<T: Send + 'static>(f: impl FnOnce() -> Result<T, String> + Send + 'static) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|_| Err(format!("panic")) )
}

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtractWithPassword(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, pw: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let pwd = s(&mut e, &pw);
    match guarded(move || extract_zip_with_password(&inp, &out, &pwd)) { Ok(0) => JNI_TRUE, Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("ZIP: {f} failed")); JNI_FALSE }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("ZIP: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = std::fs::create_dir_all(&out);
    match guarded(move || extract_zip_all_inner(&inp, &out)) { Ok(0) => JNI_TRUE, Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("ZIP: {f} failed")); JNI_FALSE }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("ZIP: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtractSelected(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel);
    match guarded(move || extract_zip_selected_inner(&inp, &out, &sel_str)) { Ok(0) => JNI_TRUE, Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("ZIP: {f} failed")); JNI_FALSE }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("ZIP: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipCompress(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, lv: JString, pw: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let lvl = s(&mut e, &lv); let pwd = s(&mut e, &pw);
    let level: i32 = lvl.parse().unwrap_or(5);
    match guarded(move || compress_zip_inner(&inp, &out, level, &pwd)) { Ok(0) => JNI_TRUE, Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("ZIP compress: {f} failed")); JNI_FALSE }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("ZIP compress: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i);
    match guarded(move || list_zip_inner(&inp)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("{er}")); std::ptr::null_mut() } }
}
