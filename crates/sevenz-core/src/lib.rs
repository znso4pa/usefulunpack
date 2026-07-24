use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, jint, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, safe_join};
use sevenz_rust::*;
use std::collections::HashSet;
use std::io::Write;
use std::sync::Mutex;
use std::sync::atomic::{AtomicUsize, AtomicBool, Ordering};

static SZ_COUNT: AtomicUsize = AtomicUsize::new(0);
static SZ_TOTAL: AtomicUsize = AtomicUsize::new(0);
static SZ_FNAME: Mutex<String> = Mutex::new(String::new());
static SZ_CANCEL: AtomicBool = AtomicBool::new(false);

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
fn extract_7z_with_password(input: &str, output: &str, password: &str) -> Result<u32, String> {
    let pw: sevenz_rust::Password = password.into();
    sevenz_rust::decompress_file_with_password(input, output, pw).map_err(|e| format!("7z: {e}"))?;
    Ok(0)
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

fn count_files_7z(base: &str, rel: &str) -> usize {
    let dir_path = if rel.is_empty() { base.to_string() } else { format!("{base}/{rel}") };
    let mut total = 0usize;
    if let Ok(entries) = std::fs::read_dir(&dir_path) {
        for entry in entries.flatten() {
            let ft = entry.file_type().unwrap();
            if ft.is_dir() { total += count_files_7z(base, &format!("{}/{}", rel, entry.file_name().to_string_lossy())); }
            else if ft.is_file() { total += 1; }
        }
    }
    total
}

fn compress_7z_inner(input: &str, output: &str, level: i32, password: &str) -> Result<u32, String> {
    SZ_CANCEL.store(false, Ordering::SeqCst);
    SZ_TOTAL.store(count_files_7z(input, ""), Ordering::SeqCst);
    SZ_COUNT.store(0, Ordering::SeqCst);
    *SZ_FNAME.lock().unwrap() = String::new();
    let lzma_preset = match level { 0 => 0, 1..=3 => 3, 4..=6 => 5, 7..=9 => 9, _ => 9 };
    let has_pw = !password.is_empty();
    let mut sz = sevenz_rust::SevenZWriter::create(output).map_err(|e| format!("7z: {e}"))?;
    if has_pw { sz.set_encrypt_header(true); }
    let mut methods: Vec<sevenz_rust::SevenZMethodConfiguration> = Vec::new();
    if has_pw {
        let aes_opts = sevenz_rust::AesEncoderOptions::new(password.into());
        methods.push(aes_opts.into());
    }
    if level == 0 {
        let opts = sevenz_rust::lzma::LZMA2Options::with_preset(0);
        methods.push(
            sevenz_rust::SevenZMethodConfiguration::new(sevenz_rust::SevenZMethod::LZMA2)
                .with_options(sevenz_rust::MethodOptions::LZMA2(opts))
        );
    } else {
        let opts = sevenz_rust::lzma::LZMA2Options::with_preset(lzma_preset);
        methods.push(
            sevenz_rust::SevenZMethodConfiguration::new(sevenz_rust::SevenZMethod::LZMA2)
                .with_options(sevenz_rust::MethodOptions::LZMA2(opts))
        );
    }
    sz.set_content_methods(methods);
    let mut fail = 0u32;
    fn add_dir(sz: &mut sevenz_rust::SevenZWriter<std::fs::File>, base: &str, rel: &str) -> Result<u32, String> {
        let dir_path = if rel.is_empty() { base.to_string() } else { format!("{base}/{rel}") };
        let mut fail = 0u32;
        let entries = std::fs::read_dir(&dir_path).map_err(|e| format!("{e}"))?;
        for entry in entries {
            let entry = entry.map_err(|e| format!("{e}"))?;
            let name = entry.file_name().to_string_lossy().to_string();
            let file_rel = if rel.is_empty() { name.clone() } else { format!("{rel}/{name}") };
            let file_type = entry.file_type().map_err(|e| format!("{e}"))?;
            if file_type.is_dir() {
                let e = sevenz_rust::SevenZArchiveEntry::from_path(&entry.path(), file_rel.clone());
                let _ = sz.push_archive_entry(e, None::<std::fs::File>);
                fail += add_dir(sz, base, &file_rel)?;
            } else if file_type.is_file() {
                if SZ_CANCEL.load(Ordering::SeqCst) { return Err("cancelled".to_string()); }
                *SZ_FNAME.lock().unwrap() = file_rel.clone();
                SZ_COUNT.fetch_add(1, Ordering::SeqCst);
                let e = sevenz_rust::SevenZArchiveEntry::from_path(&entry.path(), file_rel.clone());
                match std::fs::File::open(&entry.path()) {
                    Ok(f) => { let _ = sz.push_archive_entry(e, Some(f)); }
                    Err(_) => { fail += 1; }
                }
            }
        }
        Ok(fail)
    }
    fail += add_dir(&mut sz, input, "")?;
    sz.finish().map_err(|e| format!("7z: {e}"))?;
    Ok(fail)
}

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szExtractWithPassword(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, pw: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let pwd = s(&mut e, &pw); let _ = std::fs::create_dir_all(&out);
    match guarded(|| extract_7z_with_password(&inp, &out, &pwd)) { Ok(0) => JNI_TRUE, Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("7z: {f} failed")); JNI_FALSE } Err(er) => { let _ = e.throw_new("java/io/IOException", format!("7z: {er}")); JNI_FALSE } }
}
fn guarded<T>(f: impl FnOnce() -> Result<T, String>) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|panic| {
        let msg = panic.downcast_ref::<&str>().copied()
            .or_else(|| panic.downcast_ref::<String>().map(|s| s.as_str()))
            .unwrap_or("unknown panic");
        Err(format!("panic: {msg}"))
    })
}

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = std::fs::create_dir_all(&out);
    match guarded(|| extract_7z_all(&inp, &out)) { Ok(0) => JNI_TRUE, Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("7z: {f} failed")); JNI_FALSE } Err(er) => { let _ = e.throw_new("java/io/IOException", format!("7z: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szExtractSelected(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel);
    match guarded(|| extract_7z_selected(&inp, &out, &sel_str)) { Ok(0) => JNI_TRUE, Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("7z: {f} failed")); JNI_FALSE } Err(er) => { let _ = e.throw_new("java/io/IOException", format!("7z: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szCompressCancel(_: JNIEnv, _: JClass) { SZ_CANCEL.store(true, Ordering::SeqCst); }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szCompressProgressCount(_: JNIEnv, _: JClass) -> jint { SZ_COUNT.load(Ordering::SeqCst) as jint }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szCompressProgressTotal(_: JNIEnv, _: JClass) -> jint { SZ_TOTAL.load(Ordering::SeqCst) as jint }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szCompressProgressName(mut e: JNIEnv, _: JClass) -> jstring {
    let name = SZ_FNAME.lock().unwrap().clone();
    e.new_string(&name).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szNeedsPassword(mut e: JNIEnv, _: JClass, i: JString) -> jboolean {
    let inp = s(&mut e, &i);
    // Try to open the archive; if it succeeds, check if any folder uses AES256SHA256
    match sevenz_rust::Archive::open(&inp) {
        Ok(arc) => {
            for f in &arc.folders {
                for c in &f.coders {
                    if c.decompression_method_id() == sevenz_rust::SevenZMethod::AES256SHA256.id() { return JNI_TRUE; }
                }
            }
            JNI_FALSE
        }
        Err(er) => { let _ = e.throw_new("java/io/IOException", format!("7z: {er}")); JNI_FALSE }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szCompress(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, lv: JString, pw: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let lvl = s(&mut e, &lv); let pwd = s(&mut e, &pw);
    let level: i32 = lvl.parse().unwrap_or(6);
    match guarded(|| compress_7z_inner(&inp, &out, level, &pwd)) { Ok(0) => JNI_TRUE, Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("7z compress: {f} failed")); JNI_FALSE }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("7z compress: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    match list_7z(&s(&mut e, &i)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}
