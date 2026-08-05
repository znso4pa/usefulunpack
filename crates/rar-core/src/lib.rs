use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, jlong, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, safe_join, extract_result_json, ProgressWriter};
use archive_common::extract_progress;
use std::collections::HashSet;
use std::io::Write;
use std::path::Path;
use std::sync::atomic::{AtomicU32, Ordering};

#[cfg(test)]
use std::sync::Mutex;
#[cfg(test)]
static TEST_LOCK: Mutex<()> = Mutex::new(());

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

fn rar_writer<'a>(
    sel_set: &'a Option<HashSet<String>>,
    out_base: &'a str,
    fail: &'a AtomicU32,
) -> impl FnMut(&rars::ExtractedEntryMeta) -> Result<Box<dyn Write>, rars::Error> + 'a {
    move |meta| {
        if extract_progress::cancelled() { return Err(rars::Error::Cancelled); }
        let name = meta.name_lossy().replace('\\', "/").trim_matches('/').to_string();
        if meta.is_directory || name.is_empty() || name.ends_with('/') {
            let dest = safe_join(out_base, &name).unwrap_or_default();
            std::fs::create_dir_all(&dest).ok();
            return Ok(Box::new(std::io::sink()) as Box<dyn Write>);
        }
        if let Some(ref sel) = sel_set {
            if !sel.contains(&name) && !sel.iter().any(|s| name.starts_with(&format!("{s}/"))) {
                return Ok(Box::new(std::io::sink()) as Box<dyn Write>);
            }
        }
        extract_progress::set_name(&name);
        let dest = safe_join(out_base, &name).unwrap_or_else(|_| Path::new(out_base).join(&name));
        if let Some(p) = Path::new(&dest).parent() { std::fs::create_dir_all(p).ok(); }
        let out_file = match std::fs::File::create(&dest) {
            Ok(f) => f,
            Err(_) => { fail.fetch_add(1, Ordering::SeqCst); return Ok(Box::new(std::io::sink()) as Box<dyn Write>); }
        };
        Ok(Box::new(ProgressWriter::extract(out_file)) as Box<dyn Write>)
    }
}

fn rar_opts(pw: Option<&[u8]>) -> rars::ArchiveReadOptions<'_> {
    // Keep the library's default buffered-decode limit (512MB): RAR5 filtered
    // members above it are rejected cleanly rather than buffering huge amounts
    // of RAM on-device (which would risk OOM / partial output).
    rars::ArchiveReadOptions::with_optional_password(pw)
}

fn read_volumes(paths: &[&str], pw: Option<&[u8]>) -> Result<Vec<rars::Archive>, String> {
    let mut archives = Vec::with_capacity(paths.len());
    for p in paths {
        archives.push(rars::ArchiveReader::read_path_with_options(Path::new(p), rar_opts(pw)).map_err(|e| format!("rar: {e}"))?);
    }
    Ok(archives)
}

fn extract_rar_inner(input: &str, output: &str, selected: Option<&HashSet<String>>, password: &str) -> Result<(u32, u32), String> {
    let pw: Option<&[u8]> = Some(password.as_bytes());
    let archive = rars::ArchiveReader::read_path_with_options(Path::new(input), rar_opts(pw)).map_err(|e| format!("rar: {e}"))?;
    let sel_set: Option<HashSet<String>> = selected.map(|s| s.iter().map(|x| x.to_string()).collect());
    let out_base = output.to_string();

    let mut total = 0u32;
    let mut prog_total = 0u64;
    for member in archive.members() {
        let name = member.meta.name_lossy().replace('\\', "/").trim_matches('/').to_string();
        if member.meta.is_directory || name.is_empty() || name.ends_with('/') { continue; }
        let matches = match &sel_set {
            None => true,
            Some(sel) => sel.contains(&name) || sel.iter().any(|s| name.starts_with(&format!("{s}/"))),
        };
        if matches { total += 1; prog_total += member.meta.unpacked_size; }
    }
    extract_progress::reset(prog_total);
    let fail = AtomicU32::new(0);
    archive.extract_to(pw, rar_writer(&sel_set, &out_base, &fail)).map_err(|e| format!("rar: {e}"))?;
    Ok((total, fail.load(Ordering::SeqCst)))
}

fn extract_rar_volumes_inner(paths: &[&str], output: &str, selected: Option<&HashSet<String>>, password: &str) -> Result<(u32, u32), String> {
    let pw: Option<&[u8]> = Some(password.as_bytes());
    let sel_set: Option<HashSet<String>> = selected.map(|s| s.iter().map(|x| x.to_string()).collect());
    let out_base = output.to_string();
    let archives = read_volumes(paths, pw)?;

    let mut total = 0u32;
    let mut prog_total = 0u64;
    let mut seen: HashSet<String> = HashSet::new();
    for archive in &archives {
        for member in archive.members() {
            let name = member.meta.name_lossy().replace('\\', "/").trim_matches('/').to_string();
            if member.meta.is_directory || name.is_empty() || name.ends_with('/') { continue; }
            if !seen.insert(name.clone()) { continue; }
            let matches = match &sel_set {
                None => true,
                Some(sel) => sel.contains(&name) || sel.iter().any(|s| name.starts_with(&format!("{s}/"))),
            };
            if matches { total += 1; prog_total += member.meta.unpacked_size; }
        }
    }
    extract_progress::reset(prog_total);
    let fail = AtomicU32::new(0);
    rars::extract_volumes_to_with_options(&archives, rar_opts(pw), rar_writer(&sel_set, &out_base, &fail)).map_err(|e| format!("rar: {e}"))?;
    Ok((total, fail.load(Ordering::SeqCst)))
}

fn rar_needs_password_inner(input: &str) -> Result<bool, String> {
    let archive = rars::ArchiveReader::read_path(Path::new(input)).map_err(|e| format!("rar: {e}"))?;
    for member in archive.members() {
        if member.meta.is_encrypted { return Ok(true); }
    }
    Ok(false)
}

fn rar_volumes_needs_password_inner(paths: &[&str]) -> Result<bool, String> {
    let archives = read_volumes(paths, None)?;
    for archive in &archives {
        for member in archive.members() {
            if member.meta.is_encrypted { return Ok(true); }
        }
    }
    Ok(false)
}

fn list_rar_volumes_inner(paths: &[&str]) -> Result<String, String> {
    let archives = read_volumes(paths, None)?;
    let mut all: Vec<(String, u64, bool, bool)> = Vec::new();
    let mut seen: HashSet<String> = HashSet::new();
    for archive in &archives {
        for member in archive.members() {
            let name = member.meta.name_lossy().replace('\\', "/").trim_matches('/').to_string();
            if name.is_empty() { continue; }
            let is_dir = name.ends_with('/');
            let is_enc = member.meta.is_encrypted;
            if !seen.insert(name.clone()) { continue; }
            all.push((name.clone(), member.meta.unpacked_size, is_dir, is_enc));
            let mut path = String::new();
            for part in name.split('/') {
                if part.is_empty() { continue; }
                path = if path.is_empty() { part.to_string() } else { format!("{path}/{part}") };
                if !all.iter().any(|(p,_,_,_)| p == &path) { all.push((path.clone(), 0u64, true, false)); }
            }
        }
    }
    all.sort_by(|a,b| a.0.cmp(&b.0));
    all.dedup_by(|a,b| a.0 == b.0);
    let items: Vec<String> = all.iter().map(|(n,s,d,e)|
        format!(r#"{{"n":"{}","s":{},"d":{},"e":{}}}"#, json_escape(n), *s, *d, *e)
    ).collect();
    Ok(format!("[{}]", items.join(",")))
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
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = std::fs::create_dir_all(&out);
    match guarded(move || extract_rar_inner(&inp, &out, None, "")) { Ok((total, f)) => { let json = extract_result_json(total, total.saturating_sub(f), f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractSelected(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel: JString) -> jstring {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel);
    let ss: HashSet<String> = sel_str.lines().filter(|l| !l.is_empty()).map(|s| s.to_string()).collect();
    match guarded(move || extract_rar_inner(&inp, &out, Some(&ss), "")) { Ok((total, f)) => { let json = extract_result_json(total, total.saturating_sub(f), f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractWithPassword(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, pw: JString) -> jstring {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let pwd = s(&mut e, &pw); let _ = std::fs::create_dir_all(&out);
    match guarded(move || extract_rar_inner(&inp, &out, None, &pwd)) { Ok((total, f)) => { let json = extract_result_json(total, total.saturating_sub(f), f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarNeedsPassword(mut e: JNIEnv, _: JClass, i: JString) -> jboolean {
    let inp = s(&mut e, &i);
    match rar_needs_password_inner(&inp) { Ok(true) => JNI_TRUE, Ok(false) => JNI_FALSE, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("rar: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractSelectedWithPassword(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel: JString, pw: JString) -> jstring {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel); let pwd = s(&mut e, &pw); let _ = std::fs::create_dir_all(&out);
    let ss: HashSet<String> = sel_str.lines().filter(|l| !l.is_empty()).map(|s| s.to_string()).collect();
    match guarded(move || extract_rar_inner(&inp, &out, Some(&ss), &pwd)) { Ok((total, f)) => { let json = extract_result_json(total, total.saturating_sub(f), f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }

fn volume_refs(vols: &[String]) -> Vec<&str> { vols.iter().map(|s| s.as_str()).collect() }

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarListEntriesVolumes(mut e: JNIEnv, _: JClass, v: JString) -> jstring {
    let vs = s(&mut e, &v); let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match list_rar_volumes_inner(&volume_refs(&vols)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("{er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractVolumes(mut e: JNIEnv, _: JClass, _t: JString, v: JString, o: JString) -> jstring {
    let vs = s(&mut e, &v); let out = s(&mut e, &o); let _ = std::fs::create_dir_all(&out);
    let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(move || extract_rar_volumes_inner(&volume_refs(&vols), &out, None, "")) { Ok((total, f)) => { let json = extract_result_json(total, total.saturating_sub(f), f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractSelectedVolumes(mut e: JNIEnv, _: JClass, _t: JString, v: JString, o: JString, sel: JString) -> jstring {
    let vs = s(&mut e, &v); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel);
    let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    let ss: HashSet<String> = sel_str.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(move || extract_rar_volumes_inner(&volume_refs(&vols), &out, Some(&ss), "")) { Ok((total, f)) => { let json = extract_result_json(total, total.saturating_sub(f), f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractVolumesWithPassword(mut e: JNIEnv, _: JClass, _t: JString, v: JString, o: JString, pw: JString) -> jstring {
    let vs = s(&mut e, &v); let out = s(&mut e, &o); let pwd = s(&mut e, &pw); let _ = std::fs::create_dir_all(&out);
    let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(move || extract_rar_volumes_inner(&volume_refs(&vols), &out, None, &pwd)) { Ok((total, f)) => { let json = extract_result_json(total, total.saturating_sub(f), f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractSelectedVolumesWithPassword(mut e: JNIEnv, _: JClass, _t: JString, v: JString, o: JString, sel: JString, pw: JString) -> jstring {
    let vs = s(&mut e, &v); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel); let pwd = s(&mut e, &pw); let _ = std::fs::create_dir_all(&out);
    let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    let ss: HashSet<String> = sel_str.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(move || extract_rar_volumes_inner(&volume_refs(&vols), &out, Some(&ss), &pwd)) { Ok((total, f)) => { let json = extract_result_json(total, total.saturating_sub(f), f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarVolumesNeedsPassword(mut e: JNIEnv, _: JClass, v: JString) -> jboolean {
    let vs = s(&mut e, &v); let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match rar_volumes_needs_password_inner(&volume_refs(&vols)) { Ok(true) => JNI_TRUE, Ok(false) => JNI_FALSE, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("rar: {er}")); JNI_FALSE } }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rars::rar13::{write_stored_volumes, StoredEntry, WriterOptions};
    use rars::features::FeatureSet;
    use rars::version::ArchiveVersion;

    fn make_volumes() -> Vec<std::path::PathBuf> {
        let data: Vec<u8> = (0..4096u32).map(|i| (i % 251) as u8).collect();
        let entry = StoredEntry {
            name: b"data/file.bin",
            data: &data,
            file_time: 0,
            file_attr: 0,
            password: None,
            file_comment: None,
        };
        let opts = WriterOptions::new(ArchiveVersion::Rar14, FeatureSet::store_only());
        let vols = write_stored_volumes(entry, opts, 1024).expect("write volumes");
        assert!(vols.len() >= 2, "expected multiple volumes, got {}", vols.len());
        let dir = std::env::temp_dir().join(format!("uu_rar_vol_test_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let mut paths = Vec::new();
        for (i, v) in vols.iter().enumerate() {
            let p = dir.join(format!("vol{i}.rar"));
            std::fs::write(&p, v).unwrap();
            paths.push(p);
        }
        paths
    }

    #[test]
    fn lists_and_extracts_multivolume_rar() {
        let _g = crate::TEST_LOCK.lock().unwrap();
        let vols = make_volumes();
        let paths: Vec<String> = vols.iter().map(|p| p.to_string_lossy().to_string()).collect();
        let refs: Vec<&str> = paths.iter().map(|s| s.as_str()).collect();

        let list = list_rar_volumes_inner(&refs).expect("list volumes");
        assert!(list.contains("data/file.bin"), "missing entry in {list}");

        let out = std::env::temp_dir().join(format!("uu_rar_vol_out_{}", std::process::id()));
        let out_s = out.to_string_lossy().to_string();
        extract_rar_volumes_inner(&refs, &out_s, None, "").expect("extract volumes");
        let got = std::fs::read(out.join("data/file.bin")).expect("read extracted");
        assert_eq!(got.len(), 4096);
        assert_eq!(got[100], 100);
        assert_eq!(got[4095], (4095u32 % 251) as u8);
        std::fs::remove_dir_all(&out).ok();
        for p in &vols { std::fs::remove_file(p).ok(); }
        if let Some(dir) = vols[0].parent() { std::fs::remove_dir_all(dir).ok(); }
    }
}

/// Manual host verification against real split RAR files.
/// Set `UU_RAR_PARTS` (colon-separated part paths) and optional `UU_RAR_PASS`.
/// Skips (passes silently) when the env var is absent.
#[cfg(test)]
mod manual_volumes {
    use super::*;
    use std::time::Instant;

    fn extract_names(json: &str) -> Vec<String> {
        let mut names = Vec::new();
        let bytes = json.as_bytes();
        let mut i = 0;
        while i < bytes.len() {
            if bytes[i..].starts_with(br#""n":""#) {
                let start = i + 5;
                let mut j = start;
                while j < bytes.len() {
                    let b = bytes[j];
                    if b == b'\\' { j += 2; continue; }
                    if b == b'"' { break; }
                    j += 1;
                }
                if let Ok(s) = std::str::from_utf8(&bytes[start..j]) {
                    names.push(s.to_string());
                }
                i = j + 1;
            } else {
                i += 1;
            }
        }
        names
    }

    fn count_files(dir: &std::path::Path) -> usize {
        let mut n = 0;
        if let Ok(entries) = std::fs::read_dir(dir) {
            for e in entries.flatten() {
                if e.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                    n += count_files(&e.path());
                } else {
                    n += 1;
                }
            }
        }
        n
    }

    fn sha256_file(path: &std::path::Path) -> Option<String> {
        let out = std::process::Command::new("shasum").arg("-a").arg("256").arg(path).output().ok()?;
        if !out.status.success() { return None; }
        Some(String::from_utf8_lossy(&out.stdout).split_whitespace().next().unwrap_or("").to_string())
    }

    fn walk_files(dir: &std::path::Path, out: &mut Vec<std::path::PathBuf>) {
        if let Ok(entries) = std::fs::read_dir(dir) {
            for e in entries.flatten() {
                if e.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                    walk_files(&e.path(), out);
                } else {
                    out.push(e.path());
                }
            }
        }
    }

    #[test]
    fn manual_rar_volumes() {
        let _g = crate::TEST_LOCK.lock().unwrap();
        let Ok(parts) = std::env::var("UU_RAR_PARTS") else {
            eprintln!("[manual_rar] skipped: UU_RAR_PARTS not set");
            return;
        };
        let pass = std::env::var("UU_RAR_PASS").unwrap_or_default();
        let paths: Vec<String> = parts.split(':').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect();
        if paths.is_empty() {
            eprintln!("[manual_rar] skipped: empty parts");
            return;
        }
        let refs: Vec<&str> = paths.iter().map(|s| s.as_str()).collect();
        println!("[manual_rar] parts = {}  |  UU_RAR_PASS={}", paths.len(), if pass.is_empty() { "(none)" } else { "set" });

        // 1. needs_password (no password; header-encrypted sets will error — expected)
        match rar_volumes_needs_password_inner(&refs) {
            Ok(np) => println!("[manual_rar] needs_password = {np}"),
            Err(e) => println!("[manual_rar] needs_password err (no pw) = {e}"),
        }

        // 2. list (no password)
        let mut all_names: Vec<String> = Vec::new();
        match list_rar_volumes_inner(&refs) {
            Ok(j) => {
                all_names = extract_names(&j);
                println!("[manual_rar] list ok (no pw): {} entries", all_names.len());
                for n in all_names.iter().take(8) { println!("[manual_rar]   - {n}"); }
            }
            Err(e) => println!("[manual_rar] list err (no pw) = {e}"),
        }

        // 3. if password given, read members with password for selected-extract names
        let pw_bytes: Option<&[u8]> = if pass.is_empty() { None } else { Some(pass.as_bytes()) };
        if pw_bytes.is_some() && all_names.is_empty() {
            if let Ok(archives) = read_volumes(&refs, pw_bytes) {
                let mut seen = HashSet::new();
                let mut files = Vec::new();
                for a in &archives {
                    for m in a.members() {
                        let n = m.meta.name_lossy().replace('\\', "/").trim_matches('/').to_string();
                        if m.meta.is_directory || n.is_empty() || n.ends_with('/') { continue; }
                        if seen.insert(n.clone()) { files.push(n); }
                    }
                }
                println!("[manual_rar] members readable with pw: {} files", files.len());
                for n in files.iter().take(8) { println!("[manual_rar]   - {n}"); }
                all_names = files;
            }
        }

        // 4. selected extract FIRST (targets the large filtered member if present)
        if !all_names.is_empty() && !pass.is_empty() {
            let out2 = std::env::temp_dir().join(format!("uu_rar_manual_sel_{}", std::process::id()));
            std::fs::create_dir_all(&out2).unwrap();
            let out2_s = out2.to_string_lossy().to_string();
            let mut set = HashSet::new();
            // prefer the large filtered member to exercise the buffered-decode path
            if let Some(big) = all_names.iter().find(|n| n.to_lowercase().contains("data.xp3") || n.to_lowercase().contains(".xp3")) {
                set.insert(big.clone());
            }
            for n in all_names.iter().take(1) { set.insert(n.clone()); }
            eprintln!("[manual_rar] START selected extract ({} files)", set.len());
            let t0 = Instant::now();
            match extract_rar_volumes_inner(&refs, &out2_s, Some(&set), &pass) {
                Ok((total, fail)) => {
                    println!("[manual_rar] selected extract total={total} fail={fail} files_on_disk={} in {:.2}s", count_files(&out2), t0.elapsed().as_secs_f64());
                    for (i, n) in all_names.iter().enumerate() {
                        if set.contains(n) {
                            let f = std::path::Path::new(&out2_s).join(n);
                            println!("[manual_rar]   sel #{i}: {n}  exists={} size={}", f.exists(), f.metadata().map(|m| m.len()).unwrap_or(0));
                        }
                    }
                }
                Err(e) => println!("[manual_rar] selected extract err = {e}"),
            }
            std::fs::remove_dir_all(&out2).ok();
        }

        // 4b. full extract + SHA-256 verification (when UU_EXPECTED_SHA set)
        if let Ok(exp) = std::env::var("UU_EXPECTED_SHA") {
            let outv = std::env::temp_dir().join(format!("uu_rar_verify_{}", std::process::id()));
            std::fs::create_dir_all(&outv).unwrap();
            let outv_s = outv.to_string_lossy().to_string();
            let t0 = Instant::now();
            match extract_rar_volumes_inner(&refs, &outv_s, None, &pass) {
                Ok((total, fail)) => {
                    println!("[manual_rar] VERIFY extract total={total} fail={fail} in {:.2}s", t0.elapsed().as_secs_f64());
                    let mut files = Vec::new();
                    walk_files(&outv, &mut files);
                    let mut matched = false;
                    for f in &files {
                        let h = sha256_file(f).unwrap_or_default();
                        let ok = h == exp;
                        if ok { matched = true; }
                        println!("[manual_rar]   file={} sha={} expected={} {}", f.strip_prefix(&outv).map(|p| p.to_string_lossy().to_string()).unwrap_or_default(), h, exp, if ok { "MATCH" } else { "DIFF" });
                    }
                    if matched { println!("[manual_rar] VERIFY PASS"); } else { println!("[manual_rar] VERIFY FAIL (no file matched)"); }
                }
                Err(e) => println!("[manual_rar] VERIFY extract err = {e}"),
            }
            std::fs::remove_dir_all(&outv).ok();
        }

        // 5. full extract — only when UU_RAR_FULL=1 (a full 6.8GB archive takes a long time)
        if std::env::var("UU_RAR_FULL").map(|v| v == "1").unwrap_or(false) {
            let out = std::env::temp_dir().join(format!("uu_rar_manual_{}", std::process::id()));
            std::fs::create_dir_all(&out).unwrap();
            let out_s = out.to_string_lossy().to_string();
            eprintln!("[manual_rar] START full extract -> {}", out_s);
            let monitor = std::thread::spawn({
                let out2 = out.clone();
                move || {
                    let mut last = 0u64;
                    for _ in 0..300 {
                        std::thread::sleep(std::time::Duration::from_secs(2));
                        let b = extract_progress::bytes();
                        let t = extract_progress::total_bytes();
                        let f = count_files(&out2);
                        if b != last {
                            last = b;
                            eprintln!("[manual_rar] progress bytes={b} total={t} files_on_disk={f}");
                        }
                    }
                }
            });
            let t0 = Instant::now();
            match extract_rar_volumes_inner(&refs, &out_s, None, &pass) {
                Ok((total, fail)) => {
                    println!("[manual_rar] full extract total={total} fail={fail}  in {:.2}s", t0.elapsed().as_secs_f64());
                    println!("[manual_rar] progress bytes={} total_bytes={} name={}", extract_progress::bytes(), extract_progress::total_bytes(), extract_progress::name());
                    println!("[manual_rar] files on disk = {}", count_files(&out));
                }
                Err(e) => println!("[manual_rar] full extract err = {e}"),
            }
            monitor.join().ok();
            std::fs::remove_dir_all(&out).ok();
        }
    }
}
