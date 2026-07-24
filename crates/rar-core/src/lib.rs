use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, safe_join};
use std::collections::HashSet;
use std::io::Write;
use std::path::Path;

fn list_rar_inner(input: &str) -> Result<String, String> {
    let archive = rars::ArchiveReader::read_path(Path::new(input)).map_err(|e| format!("rar: {e}"))?;
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

fn extract_rar_inner(input: &str, output: &str, selected: Option<&HashSet<String>>, password: &str) -> Result<u32, String> {
    let pw: Option<&[u8]> = Some(password.as_bytes());
    let opts = rars::ArchiveReadOptions::with_optional_password(pw);
    let archive = rars::ArchiveReader::read_path_with_options(Path::new(input), opts).map_err(|e| format!("rar: {e}"))?;
    let sel_set: Option<HashSet<String>> = selected.map(|s| s.iter().map(|x| x.to_string()).collect());
    let out_base = output.to_string();

    archive.extract_to(pw, |meta| {
        let name = meta.name_lossy().replace('\\', "/").trim_matches('/').to_string();
        if meta.is_directory || name.is_empty() || name.ends_with('/') {
            let dest = safe_join(&out_base, &name).unwrap_or_default();
            std::fs::create_dir_all(&dest).ok();
            return Ok(Box::new(std::io::sink()) as Box<dyn Write>);
        }
        if let Some(ref sel) = sel_set {
            if !sel.contains(&name) && !sel.iter().any(|s| name.starts_with(&format!("{s}/"))) {
                return Ok(Box::new(std::io::sink()) as Box<dyn Write>);
            }
        }
        let dest = safe_join(&out_base, &name).unwrap_or_else(|_| Path::new(&out_base).join(&name));
        if let Some(p) = Path::new(&dest).parent() { std::fs::create_dir_all(p).ok(); }
        let out_file = std::fs::File::create(&dest).map_err(|e| rars::Error::from(e))?;
        Ok(Box::new(out_file) as Box<dyn Write>)
    }).map_err(|e| format!("rar: {e}"))?;

    Ok(0)
}

fn rar_needs_password_inner(input: &str) -> Result<bool, String> {
    let archive = rars::ArchiveReader::read_path(Path::new(input)).map_err(|e| format!("rar: {e}"))?;
    for member in archive.members() {
        if member.meta.is_encrypted { return Ok(true); }
    }
    Ok(false)
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
    let inp = s(&mut e, &i); match list_rar_inner(&inp) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("{er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = std::fs::create_dir_all(&out);
    match guarded(move || extract_rar_inner(&inp, &out, None, "")) { Ok(0) => JNI_TRUE, Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("rar: {f} failed")); JNI_FALSE }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("rar: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractSelected(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel);
    let ss: HashSet<String> = sel_str.lines().filter(|l| !l.is_empty()).map(|s| s.to_string()).collect();
    match guarded(move || extract_rar_inner(&inp, &out, Some(&ss), "")) { Ok(0) => JNI_TRUE, Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("rar: {f} failed")); JNI_FALSE }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("rar: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractWithPassword(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, pw: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let pwd = s(&mut e, &pw); let _ = std::fs::create_dir_all(&out);
    match guarded(move || extract_rar_inner(&inp, &out, None, &pwd)) { Ok(0) => JNI_TRUE, Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("rar: {f} failed")); JNI_FALSE }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("rar: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarNeedsPassword(mut e: JNIEnv, _: JClass, i: JString) -> jboolean {
    let inp = s(&mut e, &i);
    match rar_needs_password_inner(&inp) { Ok(true) => JNI_TRUE, Ok(false) => JNI_FALSE, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("rar: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractSelectedWithPassword(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel: JString, pw: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel); let pwd = s(&mut e, &pw); let _ = std::fs::create_dir_all(&out);
    let ss: HashSet<String> = sel_str.lines().filter(|l| !l.is_empty()).map(|s| s.to_string()).collect();
    match guarded(move || extract_rar_inner(&inp, &out, Some(&ss), &pwd)) { Ok(0) => JNI_TRUE, Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("rar: {f} failed")); JNI_FALSE }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("rar: {er}")); JNI_FALSE } }
}
