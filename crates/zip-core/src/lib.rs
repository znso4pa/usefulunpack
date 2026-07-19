use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, jint, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, safe_join};
use std::collections::HashSet;
use std::sync::Mutex;
use std::sync::atomic::{AtomicUsize, AtomicBool, Ordering};

static COMPRESS_COUNT: AtomicUsize = AtomicUsize::new(0);
static COMPRESS_TOTAL: AtomicUsize = AtomicUsize::new(0);
static COMPRESS_FNAME: Mutex<String> = Mutex::new(String::new());
static COMPRESS_CANCEL: AtomicBool = AtomicBool::new(false);
static ZIP_ENCODING: Mutex<String> = Mutex::new(String::new());

fn get_enc() -> String { let g = ZIP_ENCODING.lock().unwrap(); if g.is_empty() { "UTF-8".to_string() } else { g.clone() } }
fn decode_entry_name(entry: &zip::read::ZipFile<'_, std::fs::File>, encoding: &str) -> String {
    let raw = entry.name_raw();
    match encoding {
        "SHIFT-JIS" | "CP932" => {
            let (dec, _, _) = encoding_rs::SHIFT_JIS.decode(raw);
            dec.into_owned()
        }
        "GBK" | "GB2312" => {
            let (dec, _, _) = encoding_rs::GBK.decode(raw);
            dec.into_owned()
        }
        _ => {
            // Try UTF-8 first, fall back to raw lossy
            entry.name().to_string()
        }
    }
}

fn list_zip_inner(input: &str) -> Result<String, String> {
    let enc = get_enc();
    let file = std::fs::File::open(input).map_err(|e| format!("{e}"))?;
    let mut archive = zip::ZipArchive::new(file).map_err(|e| format!("{e}"))?;
    let mut all: Vec<(String, u64, bool)> = Vec::with_capacity(archive.len());
    for i in 0..archive.len() {
        let entry = match archive.by_index(i) { Ok(e) => e, Err(_) => continue };
        let name = decode_entry_name(&entry, &enc).replace('\\', "/").trim_matches('/').to_string();
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
    let enc = get_enc();
    let file = std::fs::File::open(input).map_err(|e| format!("{e}"))?;
    let mut archive = zip::ZipArchive::new(file).map_err(|e| format!("{e}"))?;
    let mut fail = 0u32;
    let pw = if password.is_empty() { None } else { Some(password.as_bytes()) };
    for i in 0..archive.len() {
        let mut entry = if let Some(p) = pw { archive.by_index_decrypt(i, p).map_err(|e| format!("{e}"))? } else { archive.by_index(i).map_err(|e| format!("{e}"))? };
        let name = decode_entry_name(&entry, &enc).replace('\\', "/").trim_matches('/').to_string();
        if name.is_empty() || entry.is_dir() { continue; }
        let dest = safe_join(output, &name).map_err(|e| format!("{e}"))?;
        if let Some(p) = dest.parent() { std::fs::create_dir_all(p).map_err(|e| format!("{e}"))?; }
        let mut out = std::fs::File::create(&dest).map_err(|e| format!("{e}"))?;
        if std::io::copy(&mut entry, &mut out).is_err() { fail += 1; }
    }
    Ok(fail)
}

fn extract_zip_selected_inner(input: &str, output: &str, selected: &str) -> Result<u32, String> {
    let enc = get_enc();
    let ss: HashSet<String> = selected.lines().filter(|l| !l.is_empty()).map(|s| s.to_string()).collect();
    if ss.is_empty() { return Ok(0); }
    let file = std::fs::File::open(input).map_err(|e| format!("{e}"))?;
    let mut archive = zip::ZipArchive::new(file).map_err(|e| format!("{e}"))?;
    let mut fail = 0u32;
    for i in 0..archive.len() {
        let mut entry = match archive.by_index(i) { Ok(e) => e, Err(_) => continue };
        let name = decode_entry_name(&entry, &enc).replace('\\', "/").trim_matches('/').to_string();
        if name.is_empty() || entry.is_dir() { continue; }
        if !ss.contains(&name) && !ss.iter().any(|s| name.starts_with(&format!("{s}/"))) { continue; }
        let dest = safe_join(output, &name).map_err(|e| format!("{e}"))?;
        if let Some(p) = dest.parent() { std::fs::create_dir_all(p).map_err(|e| format!("{e}"))?; }
        let mut out = std::fs::File::create(&dest).map_err(|e| format!("{e}"))?;
        if std::io::copy(&mut entry, &mut out).is_err() { fail += 1; }
    }
    Ok(fail)
}

fn count_files(base: &str, rel: &str) -> usize {
    let dir_path = if rel.is_empty() { base.to_string() } else { format!("{base}/{rel}") };
    let mut total = 0usize;
    if let Ok(entries) = std::fs::read_dir(&dir_path) {
        for entry in entries.flatten() {
            let ft = entry.file_type().unwrap();
            if ft.is_dir() { total += count_files(base, &format!("{}/{}", rel, entry.file_name().to_string_lossy())); }
            else if ft.is_file() { total += 1; }
        }
    }
    total
}

fn compress_zip_inner(input: &str, output: &str, level: i32, password: &str) -> Result<u32, String> {
    let file = std::fs::File::create(output).map_err(|e| format!("{e}"))?;
    let mut zip = zip::write::ZipWriter::new(file);
    let mut fail = 0u32;
    let method = if level <= 0 { zip::CompressionMethod::Stored } else { zip::CompressionMethod::Deflated };
    let pw = if password.is_empty() { None } else { Some(password) };
    COMPRESS_CANCEL.store(false, Ordering::SeqCst);
    COMPRESS_TOTAL.store(count_files(input, ""), Ordering::SeqCst);
    COMPRESS_COUNT.store(0, Ordering::SeqCst);
    *COMPRESS_FNAME.lock().unwrap() = String::new();

    fn add_dir(zip: &mut zip::write::ZipWriter<std::fs::File>, base: &str, rel: &str, method: zip::CompressionMethod, level: i32, pw: Option<&str>) -> Result<u32, String> where zip::write::ZipWriter<std::fs::File>: std::io::Write {
        let dir_path = if rel.is_empty() { base.to_string() } else { format!("{base}/{rel}") };
        let mut fail = 0u32;
        let entries = std::fs::read_dir(&dir_path).map_err(|e| format!("{e}"))?;
        for entry in entries {
            if COMPRESS_CANCEL.load(Ordering::SeqCst) { return Err("cancelled".to_string()); }
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
                *COMPRESS_FNAME.lock().unwrap() = file_rel.clone();
                COMPRESS_COUNT.fetch_add(1, Ordering::SeqCst);
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

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipSetEncoding(mut e: JNIEnv, _: JClass, enc: JString) {
    *ZIP_ENCODING.lock().unwrap() = s(&mut e, &enc);
}
fn guarded<T: Send + 'static>(f: impl FnOnce() -> Result<T, String> + Send + 'static) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|_| Err(format!("panic")) )
}

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipNeedsPassword(mut e: JNIEnv, _: JClass, i: JString) -> jboolean {
    let inp = s(&mut e, &i);
    match std::fs::File::open(&inp) {
        Ok(file) => match zip::ZipArchive::new(file) {
            Ok(mut arc) => {
                for idx in 0..arc.len() {
                    if let Ok(entry) = arc.by_index(idx) { if entry.encrypted() { return JNI_TRUE; } }
                }
                JNI_FALSE
            }
            Err(_) => JNI_FALSE
        },
        Err(_) => JNI_FALSE
    }
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
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipCompressCancel(_: JNIEnv, _: JClass) { COMPRESS_CANCEL.store(true, Ordering::SeqCst); }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipCompressProgressCount(_: JNIEnv, _: JClass) -> jint { COMPRESS_COUNT.load(Ordering::SeqCst) as jint }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipCompressProgressTotal(_: JNIEnv, _: JClass) -> jint { COMPRESS_TOTAL.load(Ordering::SeqCst) as jint }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipCompressProgressName(mut e: JNIEnv, _: JClass) -> jstring {
    let name = COMPRESS_FNAME.lock().unwrap().clone();
    e.new_string(&name).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
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
