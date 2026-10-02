use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, jlong, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, safe_join, extract_result_json, ProgressWriter, ProgressReader};
use archive_common::{extract_progress, compress_progress};
use sevenz_rust::*;
use std::collections::HashSet;
use std::io::{Write, Read, Seek};
use std::sync::atomic::{AtomicU32, Ordering};

#[cfg(test)]
use std::sync::Mutex;
#[cfg(test)]
static TEST_LOCK: Mutex<()> = Mutex::new(());

fn list_from_archive(archive: &Archive) -> String {
    // Per-folder AES detection: a file is encrypted when its folder contains an
    // AES256SHA256 coder. Used to surface the "🔒" flag in the preview list.
    let folder_encrypted: Vec<bool> = archive.folders.iter().map(|f| {
        f.coders.iter().any(|c| c.decompression_method_id() == SevenZMethod::AES256SHA256.id())
    }).collect();
    let encrypted: Vec<bool> = archive.files.iter().enumerate().map(|(i, _)| {
        archive.stream_map.file_folder_index.get(i)
            .and_then(|o| *o)
            .and_then(|fi| folder_encrypted.get(fi).copied())
            .unwrap_or(false)
    }).collect();
    let mut all: Vec<(String, u64, bool, bool)> = Vec::new();
    for (idx, entry) in archive.files.iter().enumerate() {
        let name = entry.name().replace('\\', "/").trim_matches('/').to_string();
        if name.is_empty() { continue; }
        all.push((name.clone(), entry.size(), entry.is_directory(), encrypted.get(idx).copied().unwrap_or(false)));
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
    format!("[{}]", items.join(","))
}

fn list_7z(input: &str) -> Result<String, String> {
    let archive = Archive::open(input).map_err(|e| format!("7z: {e}"))?;
    Ok(list_from_archive(&archive))
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

// ─── 7z split volumes (`.7z.001` / `.001`) — zero-copy concatenation ───

const SZ_MAGIC: [u8; 6] = [0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C];

fn looks_like_7z(path: &str) -> Result<bool, String> {
    let mut f = std::fs::File::open(path).map_err(|e| format!("7z: {e}"))?;
    let mut sig = [0u8; 6];
    let n = f.read(&mut sig).map_err(|e| format!("7z: {e}"))?;
    Ok(n == 6 && sig == SZ_MAGIC)
}

/// Presents multiple part files as a single logical `Read + Seek` stream,
/// so split archives can be parsed/extracted without copying to disk.
struct ConcatReader {
    parts: Vec<std::fs::File>,
    bounds: Vec<u64>,
    cur: usize,
    pos: u64,
    len: u64,
}

impl ConcatReader {
    fn open(paths: &[&str]) -> Result<Self, String> {
        let mut parts = Vec::with_capacity(paths.len());
        let mut bounds = Vec::with_capacity(paths.len());
        let mut total: u64 = 0;
        for p in paths {
            let f = std::fs::File::open(p).map_err(|e| format!("7z: {e}"))?;
            total += f.metadata().map_err(|e| format!("7z: {e}"))?.len();
            bounds.push(total);
            parts.push(f);
        }
        Ok(Self { parts, bounds, cur: 0, pos: 0, len: total })
    }

    fn locate(&mut self, pos: u64) {
        self.pos = pos;
        self.cur = self.bounds.partition_point(|&b| b <= pos);
    }
}

impl Read for ConcatReader {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        if self.pos >= self.len || self.cur >= self.parts.len() {
            return Ok(0);
        }
        loop {
            let part_start = if self.cur == 0 { 0 } else { self.bounds[self.cur - 1] };
            self.parts[self.cur].seek(std::io::SeekFrom::Start(self.pos - part_start))?;
            let n = self.parts[self.cur].read(buf)?;
            if n > 0 {
                self.pos += n as u64;
                return Ok(n);
            }
            if self.cur + 1 >= self.parts.len() {
                return Ok(0);
            }
            self.cur += 1;
        }
    }
}

impl Seek for ConcatReader {
    fn seek(&mut self, pos: std::io::SeekFrom) -> std::io::Result<u64> {
        let new_pos = match pos {
            std::io::SeekFrom::Start(p) => p,
            std::io::SeekFrom::End(o) => (self.len as i64 + o).max(0) as u64,
            std::io::SeekFrom::Current(o) => (self.pos as i64 + o).max(0) as u64,
        };
        if new_pos > self.len {
            return Err(std::io::Error::new(std::io::ErrorKind::InvalidInput, "seek past end"));
        }
        self.locate(new_pos);
        Ok(new_pos)
    }
}

fn list_7z_volumes(paths: &[&str]) -> Result<String, String> {
    if paths.is_empty() { return Err("7z: empty volume list".into()); }
    if !looks_like_7z(paths[0])? { return Err("7z: not a valid 7z split archive".into()); }
    let mut reader = ConcatReader::open(paths)?;
    let len = reader.len;
    let archive = Archive::read(&mut reader, len, &[]).map_err(|e| format!("7z: {e}"))?;
    Ok(list_from_archive(&archive))
}

fn list_7z_volumes_with_password(paths: &[&str], password: &str) -> Result<String, String> {
    if paths.is_empty() { return Err("7z: empty volume list".into()); }
    if !looks_like_7z(paths[0])? { return Err("7z: not a valid 7z split archive".into()); }
    let mut reader = ConcatReader::open(paths)?;
    let len = reader.len;
    // Header-encrypted (-mhe=on) split archives can only be listed with the
    // password (Password is UTF-16LE), matching extract_7z_volumes.
    let pass = sevenz_rust::Password::from(password);
    let archive = Archive::read(&mut reader, len, pass.as_slice()).map_err(|e| format!("7z: {e}"))?;
    Ok(list_from_archive(&archive))
}

fn extract_7z_volumes(paths: &[&str], output: &str, selected: Option<&HashSet<String>>, password: &str) -> Result<(u32, u32), String> {
    if paths.is_empty() { return Err("7z: empty volume list".into()); }
    if !looks_like_7z(paths[0])? { return Err("7z: not a valid 7z split archive".into()); }
    let mut list_reader = ConcatReader::open(paths)?;
    let len = list_reader.len;
    // Pass the password so encrypted-header archives still yield a real total
    // (Archive::read with no password fails before the folder list is available).
    // Password is UTF-16LE (Password::from), matching the extract path.
    let pwd = sevenz_rust::Password::from(password);
    let (result_total, prog_total) = Archive::read(&mut list_reader, len, pwd.as_slice())
        .map(|a| {
            let matching: Vec<_> = a.files.iter().filter(|f| {
                let name = normalize_entry_name(f.name());
                !extract_progress::cancelled() && !name.is_empty() && !f.is_directory() && is_selected(&name, selected)
            }).collect();
            (matching.len() as u32, matching.iter().map(|f| f.size()).sum::<u64>())
        })
        .unwrap_or((0, 0));
    extract_progress::reset(prog_total);
    let fail = AtomicU32::new(0);
    let reader = ConcatReader::open(paths)?;
    if password.is_empty() {
        decompress_with_extract_fn(reader, output, |entry, rd, _| {
            handle_entry(entry, rd, output, selected, &fail)
        }).map_err(|e| format!("7z: {e}"))?;
    } else {
        decompress_with_extract_fn_and_password(reader, output, password.into(), |entry, rd, _| {
            handle_entry(entry, rd, output, selected, &fail)
        }).map_err(|e| format!("7z: {e}"))?;
    }
    Ok((result_total, fail.load(Ordering::SeqCst)))
}

fn sz_volumes_needs_password(paths: &[&str]) -> Result<bool, String> {
    if paths.is_empty() { return Err("7z: empty volume list".into()); }
    if !looks_like_7z(paths[0])? { return Err("7z: not a valid 7z split archive".into()); }
    let mut reader = ConcatReader::open(paths)?;
    let len = reader.len;
    match Archive::read(&mut reader, len, &[]) {
        Ok(arc) => {
            for f in &arc.folders { for c in &f.coders { if c.decompression_method_id() == SevenZMethod::AES256SHA256.id() { return Ok(true); } } }
            Ok(false)
        }
        // Encrypted headers can't be read without the password — that itself
        // means a password is required.
        Err(er) => {
            let m = format!("{er}").to_lowercase();
            if m.contains("password") || m.contains("encrypted") || m.contains("decode") || m.contains("aes") {
                Ok(true)
            } else {
                Err(format!("7z: {er}"))
            }
        }
    }
}

fn extract_7z(input: &str, output: &str, selected: Option<&HashSet<String>>) -> Result<(u32, u32), String> {
    // total must reflect the SELECTED entries, not the whole archive — a
    // selective extract reporting the full count produces a false "success"
    // when the selection is empty / matches nothing. One header parse yields
    // both total and the progress total (the old code parsed twice).
    let (total, prog_total) = sevenz_rust::Archive::open(input)
        .map(|a| {
            let matching: Vec<_> = a.files.iter().filter(|f| {
                let name = normalize_entry_name(f.name());
                !extract_progress::cancelled() && !name.is_empty() && !f.is_directory() && is_selected(&name, selected)
            }).collect();
            let size_sum = matching.iter().map(|f| f.size()).sum::<u64>();
            match selected {
                Some(_) => (matching.len() as u32, size_sum),
                None => (a.files.len() as u32, size_sum),
            }
        })
        .unwrap_or((0, 0));
    extract_progress::reset(prog_total);
    let fail = AtomicU32::new(0);
    decompress_file_with_extract_fn(input, output, |entry, reader, _| {
        handle_entry(entry, reader, output, selected, &fail)
    }).map_err(|e| format!("7z: {e}"))?;
    Ok((total, fail.load(Ordering::SeqCst)))
}

fn handle_entry(
    entry: &SevenZArchiveEntry, reader: &mut dyn Read, output: &str,
    selected: Option<&HashSet<String>>, fail: &AtomicU32,
) -> Result<bool, Error> {
    if extract_progress::cancelled() { return Err(Error::other("cancelled")); }
    let name = normalize_entry_name(entry.name());
    if name.is_empty() { return Ok(true); }
    let should_extract = is_selected(&name, selected);
    if !should_extract {
        if !entry.is_directory() { let _ = std::io::copy(reader, &mut std::io::sink()); }
        return Ok(true);
    }
    if !entry.is_directory() {
        extract_progress::set_name(&name);
        extract_progress::set_file(entry.size());
    }
    let dest = match safe_join(output, entry.name()) {
        Ok(d) => d,
        Err(_) => { fail.fetch_add(1, Ordering::SeqCst); return Ok(true); }
    };
    if entry.is_directory() {
        if !dest.exists() {
            if std::fs::create_dir_all(&dest).is_err() {
                fail.fetch_add(1, Ordering::SeqCst);
            }
        }
        return Ok(true);
    }
    if let Some(parent) = dest.parent() {
        if std::fs::create_dir_all(parent).is_err() {
            fail.fetch_add(1, Ordering::SeqCst);
            return Ok(true);
        }
    }
    let file = match std::fs::File::create(&dest) {
        Ok(f) => f,
        Err(_) => { fail.fetch_add(1, Ordering::SeqCst); return Ok(true); }
    };
    let mut writer = ProgressWriter::extract(std::io::BufWriter::new(file));
    // Application-side cap: never write more than the entry's declared size,
    // even if the vendored folder reader mis-decodes past unpack_size.
    let mut limited = (&mut *reader).take(entry.size());
    let copied = std::io::copy(&mut limited, &mut writer);
    let flushed = writer.flush();
    drop(writer);
    let ok = match &copied {
        Ok(n) if *n as u64 >= entry.size() => flushed.is_ok(),
        _ => false,
    };
    if !ok {
        // CRC/data/flush error (e.g. ENOSPC surfaces at flush) or a short
        // read (truncated/corrupt): don't leave corrupt output on disk.
        let _ = std::fs::remove_file(&dest);
        fail.fetch_add(1, Ordering::SeqCst);
    }
    Ok(true)
}

fn extract_7z_all(input: &str, output: &str) -> Result<(u32, u32), String> {
    extract_7z(input, output, None)
}
fn extract_7z_with_password(input: &str, output: &str, password: &str) -> Result<(u32, u32), String> {
    // Read the folder list with the password so encrypted-header archives
    // still report a real progress total.
    let (total, prog_total) = {
        let mut file = std::fs::File::open(input).map_err(|e| format!("7z: {e}"))?;
        let len = file.metadata().map_err(|e| format!("7z: {e}"))?.len();
        // Password is UTF-16LE (Password::from), matching the extract path.
        let pwd = sevenz_rust::Password::from(password);
        sevenz_rust::Archive::read(&mut file, len, pwd.as_slice())
            .map(|a| {
                let total = a.files.len() as u32;
                let prog = a.files.iter().filter(|f| {
                    let name = normalize_entry_name(f.name());
                    !name.is_empty() && !f.is_directory()
                }).map(|f| f.size()).sum::<u64>();
                (total, prog)
            })
            .unwrap_or((0, 0))
    };
    extract_progress::reset(prog_total);
    let file = std::fs::File::open(input).map_err(|e| format!("7z: {e}"))?;
    let fail = AtomicU32::new(0);
    decompress_with_extract_fn_and_password(file, output, password.into(), |entry, reader, _| {
        handle_entry(entry, reader, output, None, &fail)
    }).map_err(|e| format!("7z: {e}"))?;
    Ok((total, fail.load(Ordering::SeqCst)))
}

fn extract_7z_selected(input: &str, output: &str, selected: &str) -> Result<(u32, u32), String> {
    let paths: HashSet<String> = selected
        .lines()
        .map(normalize_entry_name)
        .filter(|s| !s.is_empty())
        .collect();
    if paths.is_empty() {
        return Ok((0, 0));
    }
    extract_7z(input, output, Some(&paths))
}

fn extract_7z_selected_with_password(input: &str, output: &str, selected: &str, password: &str) -> Result<(u32, u32), String> {
    let paths: HashSet<String> = selected
        .lines()
        .map(normalize_entry_name)
        .filter(|s| !s.is_empty())
        .collect();
    if paths.is_empty() {
        return Ok((0, 0));
    }
    // total with the password (encrypted headers can't be read without it).
    // Count only the SELECTED non-dir entries so an empty/no-match selection
    // reports a real total instead of a false full-archive success.
    let (total, prog_total) = {
        let mut file = std::fs::File::open(input).map_err(|e| format!("7z: {e}"))?;
        let len = file.metadata().map_err(|e| format!("7z: {e}"))?.len();
        let pwd = sevenz_rust::Password::from(password);
        sevenz_rust::Archive::read(&mut file, len, pwd.as_slice())
            .map(|a| {
                let matching: Vec<_> = a.files.iter().filter(|f| {
                    let name = normalize_entry_name(f.name());
                    !name.is_empty() && !f.is_directory() && is_selected(&name, Some(&paths))
                }).collect();
                let total = matching.len() as u32;
                let prog = matching.iter().map(|f| f.size()).sum::<u64>();
                (total, prog)
            })
            .unwrap_or((0, 0))
    };
    extract_progress::reset(prog_total);
    let file = std::fs::File::open(input).map_err(|e| format!("7z: {e}"))?;
    let fail = AtomicU32::new(0);
    decompress_with_extract_fn_and_password(file, output, password.into(), |entry, reader, _| {
        handle_entry(entry, reader, output, Some(&paths), &fail)
    }).map_err(|e| format!("7z: {e}"))?;
    Ok((total, fail.load(Ordering::SeqCst)))
}

fn total_bytes_7z(base: &str, rel: &str) -> u64 {
    let dir_path = if rel.is_empty() { base.to_string() } else { format!("{base}/{rel}") };
    let mut total = 0u64;
    if let Ok(entries) = std::fs::read_dir(&dir_path) {
        for entry in entries.flatten() {
            let ft = if let Ok(t) = entry.file_type() { t } else { continue };
            if ft.is_dir() { total += total_bytes_7z(base, &format!("{}/{}", rel, entry.file_name().to_string_lossy())); }
            else if ft.is_file() { total += entry.metadata().map(|m| m.len()).unwrap_or(0); }
        }
    }
    total
}

fn compress_7z_inner(input: &str, output: &str, level: i32, password: &str) -> Result<u32, String> {
    compress_progress::reset(total_bytes_7z(input, ""));
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
        let mut fail = 0u32;
        // Iterative DFS — deeply nested trees can't overflow the stack.
        let mut stack: Vec<String> = vec![rel.to_string()];
        while let Some(rel) = stack.pop() {
            let dir_path = if rel.is_empty() { base.to_string() } else { format!("{base}/{rel}") };
            let entries = std::fs::read_dir(&dir_path).map_err(|e| format!("{e}"))?;
            for entry in entries {
                let entry = entry.map_err(|e| format!("{e}"))?;
                let name = entry.file_name().to_string_lossy().to_string();
                let file_rel = if rel.is_empty() { name.clone() } else { format!("{rel}/{name}") };
                let file_type = entry.file_type().map_err(|e| format!("{e}"))?;
                if file_type.is_dir() {
                    let e = sevenz_rust::SevenZArchiveEntry::from_path(&entry.path(), file_rel.clone());
                    let _ = sz.push_archive_entry(e, None::<std::fs::File>);
                    stack.push(file_rel);
                } else if file_type.is_file() {
                    if compress_progress::cancelled() { return Err("cancelled".to_string()); }
                    compress_progress::set_name(&file_rel);
                    let e = sevenz_rust::SevenZArchiveEntry::from_path(&entry.path(), file_rel.clone());
                    match std::fs::File::open(&entry.path()) {
                        Ok(f) => {
                            compress_progress::set_file(f.metadata().map(|m| m.len()).unwrap_or(0));
                            if sz.push_archive_entry(e, Some(ProgressReader::compress(f))).is_err() {
                                if compress_progress::cancelled() { return Err("cancelled".to_string()); }
                                fail += 1;
                            }
                        }
                        Err(_) => { fail += 1; }
                    }
                }
            }
        }
        Ok(fail)
    }
    fail += add_dir(&mut sz, input, "")?;
    sz.finish().map_err(|e| format!("7z: {e}"))?;
    Ok(fail)
}

fn guarded<T>(f: impl FnOnce() -> Result<T, String>) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|panic| {
        let msg = panic.downcast_ref::<&str>().copied()
            .or_else(|| panic.downcast_ref::<String>().map(|s| s.as_str()))
            .unwrap_or("unknown panic");
        Err(format!("panic: {msg}"))
    })
}

/// Host-side (non-JNI) extraction entry point for examples/tests/benchmarks.
/// Delegates to the same path the app uses. `selected` is an optional list of
/// entry names to extract (all when empty).
#[doc(hidden)]
/// Host-side entry for split volumes (`.7z.001/.002`).
///
/// Mirrors what the app does via `szExtractVolumes`: the volume set is
/// presented to the parser as one logical stream by `ConcatReader`. Reading
/// only `.001` alone fails with UnexpectedEof because the packed-stream header
/// points into later parts.
#[doc(hidden)]
pub fn extract_7z_volumes_host(paths: &[String], output: &str, password: &str) -> Result<(u32, u32), String> {
    extract_7z_volumes(&vol_refs(paths), output, None, password)
}

pub fn extract_7z_host(input: &str, output: &str, selected: &[String], password: &str) -> Result<(u32, u32), String> {
    let sel: Option<HashSet<String>> = if selected.is_empty() { None } else { Some(selected.iter().cloned().collect()) };
    if password.is_empty() {
        extract_7z(input, output, sel.as_ref())
    } else {
        extract_7z_with_password(input, output, password)
    }
}

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szExtractWithPassword(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, pw: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let pwd = s(&mut e, &pw); let _ = std::fs::create_dir_all(&out);
    match guarded(|| extract_7z_with_password(&inp, &out, &pwd)) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = std::fs::create_dir_all(&out);
    match guarded(|| extract_7z_all(&inp, &out)) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szExtractSelected(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel);
    match guarded(|| extract_7z_selected(&inp, &out, &sel_str)) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szExtractSelectedWithPassword(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel: JString, pw: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel); let pwd = s(&mut e, &pw);
    match guarded(|| extract_7z_selected_with_password(&inp, &out, &sel_str, &pwd)) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i);
    match guarded(move || list_7z(&inp)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szListEntriesWithPassword(mut e: JNIEnv, _: JClass, i: JString, pw: JString) -> jstring {
    let inp = s(&mut e, &i); let pwd = s(&mut e, &pw);
    // For header-encrypted archives the folder list is only readable with the
    // password (Password is UTF-16LE).
    let r = guarded(move || {
        let mut file = std::fs::File::open(&inp).map_err(|e| format!("7z: {e}"))?;
        let len = file.metadata().map_err(|e| format!("7z: {e}"))?.len();
        let pass = sevenz_rust::Password::from(pwd.as_str());
        let arc = sevenz_rust::Archive::read(&mut file, len, pass.as_slice()).map_err(|e| format!("7z: {e}"))?;
        Ok(list_from_archive(&arc))
    });
    match r { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szCompressCancel(_: JNIEnv, _: JClass) { compress_progress::cancel(); }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szCompressProgressCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szCompressProgressTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szCompressProgressFileCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szCompressProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szCompressProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&compress_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szNeedsPassword(mut e: JNIEnv, _: JClass, i: JString) -> jboolean {
    let inp = s(&mut e, &i);
    match guarded(move || {
        match sevenz_rust::Archive::open(&inp) {
            Ok(arc) => {
                for f in &arc.folders { for c in &f.coders { if c.decompression_method_id() == sevenz_rust::SevenZMethod::AES256SHA256.id() { return Ok(true); } } }
                Ok(false)
            }
            // Encrypted headers can't be read without the password — that itself
            // means a password is required.
            Err(er) => {
                let m = format!("{er}").to_lowercase();
                if m.contains("password") || m.contains("encrypted") || m.contains("decode") || m.contains("aes") {
                    Ok(true)
                } else {
                    Err(format!("7z: {er}"))
                }
            }
        }
    }) { Ok(true) => JNI_TRUE, Ok(false) => JNI_FALSE, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("7z: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szCompress(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, lv: JString, pw: JString, sp: JString) -> jboolean {
    compress_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let lvl = s(&mut e, &lv); let pwd = s(&mut e, &pw); let split = s(&mut e, &sp);
    let level: i32 = lvl.parse().unwrap_or(6);
    let split_size: u64 = split.parse().unwrap_or(0);
    match guarded(|| {
        let f = compress_7z_inner(&inp, &out, level, &pwd)?;
        if f == 0 && split_size > 0 {
            archive_common::split_volumes(&out, split_size)?;
        }
        Ok(f)
    }) { Ok(0) => JNI_TRUE, Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("7z compress: {f} failed")); JNI_FALSE }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("7z compress: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szExtractProgressFileCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szExtractProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szExtractProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }

fn vol_refs(vols: &[String]) -> Vec<&str> { vols.iter().map(|s| s.as_str()).collect() }

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szListEntriesVolumes(mut e: JNIEnv, _: JClass, v: JString) -> jstring {
    let vs = s(&mut e, &v); let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(move || list_7z_volumes(&vol_refs(&vols))) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szListEntriesVolumesWithPassword(mut e: JNIEnv, _: JClass, v: JString, pw: JString) -> jstring {
    let vs = s(&mut e, &v); let pwd = s(&mut e, &pw);
    let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(move || list_7z_volumes_with_password(&vol_refs(&vols), &pwd)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szExtractVolumes(mut e: JNIEnv, _: JClass, _t: JString, v: JString, o: JString) -> jstring {
    extract_progress::clear_cancel();
    let vs = s(&mut e, &v); let out = s(&mut e, &o); let _ = std::fs::create_dir_all(&out);
    let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(|| extract_7z_volumes(&vol_refs(&vols), &out, None, "")) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szExtractSelectedVolumes(mut e: JNIEnv, _: JClass, _t: JString, v: JString, o: JString, sel: JString) -> jstring {
    extract_progress::clear_cancel();
    let vs = s(&mut e, &v); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel);
    let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    let ss: HashSet<String> = sel_str.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(|| extract_7z_volumes(&vol_refs(&vols), &out, Some(&ss), "")) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szExtractSelectedVolumesWithPassword(mut e: JNIEnv, _: JClass, _t: JString, v: JString, o: JString, sel: JString, pw: JString) -> jstring {
    extract_progress::clear_cancel();
    let vs = s(&mut e, &v); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel); let pwd = s(&mut e, &pw); let _ = std::fs::create_dir_all(&out);
    let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    let ss: HashSet<String> = sel_str.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(|| extract_7z_volumes(&vol_refs(&vols), &out, Some(&ss), &pwd)) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szExtractVolumesWithPassword(mut e: JNIEnv, _: JClass, _t: JString, v: JString, o: JString, pw: JString) -> jstring {
    extract_progress::clear_cancel();
    let vs = s(&mut e, &v); let out = s(&mut e, &o); let pwd = s(&mut e, &pw); let _ = std::fs::create_dir_all(&out);
    let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(|| extract_7z_volumes(&vol_refs(&vols), &out, None, &pwd)) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szVolumesNeedsPassword(mut e: JNIEnv, _: JClass, v: JString) -> jboolean {
    let vs = s(&mut e, &v); let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(move || sz_volumes_needs_password(&vol_refs(&vols))) { Ok(true) => JNI_TRUE, Ok(false) => JNI_FALSE, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("7z: {er}")); JNI_FALSE } }
}

#[cfg(test)]
mod tests {

    /// 进度 store 是 per-cdylib 的**静态量**，cargo 默认并行跑同一个 crate
    /// 的测试，两个测试的 `reset(total)` + `add_bytes` 会互相踩：抢在前面的那个
    /// 会用自己的夹具尺寸改掉 total，后一个断言 total 的测试就红。凡是调了
    /// extract/compress 入口的测试都必须持这把锁。
    ///
    /// 实证：`archive_lzma-core` 的 `extract_progress_total_is_reported` 曾在 CI 上
    /// 以 `left: 327, right: 119` 失败，本地 25/25 通过。
    /// One lock per crate, not per test module: `crates/sevenz-core` has two
    /// `#[cfg(test)]` modules and they share the same per-cdylib progress
    /// statics, so a per-module mutex would let a test in one module race a
    /// test in the other. Reuses the crate-level [TEST_LOCK].
    fn progress_lock() -> std::sync::MutexGuard<'static, ()> {
        TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner())
    }
    use super::*;

    fn make_split_7z() -> (Vec<std::path::PathBuf>, String) {
        let dir = std::env::temp_dir().join(format!("uu_7z_vol_src_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("a.txt"), b"hello split world".to_vec()).unwrap();
        std::fs::write(dir.join("b.bin"), (0..512u32).map(|i| (i % 251) as u8).collect::<Vec<u8>>()).unwrap();
        let arc = std::env::temp_dir().join(format!("uu_7z_vol_src_{}.7z", std::process::id()));
        let arc_s = arc.to_string_lossy().to_string();
        compress_7z_inner(&dir.to_string_lossy(), &arc_s, 5, "").expect("compress");
        let bytes = std::fs::read(&arc).unwrap();
        let split = bytes.len() / 2;
        let parts_dir = std::env::temp_dir().join(format!("uu_7z_vol_parts_{}", std::process::id()));
        std::fs::create_dir_all(&parts_dir).unwrap();
        let p1 = parts_dir.join("split.7z.001");
        let p2 = parts_dir.join("split.7z.002");
        std::fs::write(&p1, &bytes[..split]).unwrap();
        std::fs::write(&p2, &bytes[split..]).unwrap();
        std::fs::remove_dir_all(&dir).ok();
        std::fs::remove_file(&arc).ok();
        (vec![p1, p2], parts_dir.to_string_lossy().to_string())
    }

    #[test]
    fn concat_reader_lists_and_extracts_split_7z() {
        let (vols, parts_dir) = make_split_7z();
        let paths: Vec<String> = vols.iter().map(|p| p.to_string_lossy().to_string()).collect();
        let refs: Vec<&str> = paths.iter().map(|s| s.as_str()).collect();

        assert!(looks_like_7z(&refs[0]).unwrap());

        let list = list_7z_volumes(&refs).expect("list volumes");
        assert!(list.contains("a.txt") && list.contains("b.bin"), "missing entries in {list}");

        let out = std::env::temp_dir().join(format!("uu_7z_vol_out_{}", std::process::id()));
        let out_s = out.to_string_lossy().to_string();
        let (total, error) = extract_7z_volumes(&refs, &out_s, None, "").expect("extract volumes");
        assert_eq!(error, 0);
        assert_eq!(total, 2);
        assert_eq!(std::fs::read(out.join("a.txt")).unwrap(), b"hello split world");
        assert_eq!(std::fs::read(out.join("b.bin")).unwrap().len(), 512);

        std::fs::remove_dir_all(&out).ok();
        std::fs::remove_dir_all(&parts_dir).ok();
    }

    #[test]
    fn rejects_non_7z_magic() {
        let _g = crate::TEST_LOCK.lock().unwrap();
        let dir = std::env::temp_dir().join(format!("uu_7z_bad_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let p1 = dir.join("x.7z.001");
        std::fs::write(&p1, b"not a 7z archive at all").unwrap();
        assert!(list_7z_volumes(&[p1.to_str().unwrap()]).is_err());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn compress_with_split_round_trip() {
        let _g = crate::TEST_LOCK.lock().unwrap();
        let dir = std::env::temp_dir().join(format!("uu_7z_splitc_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("a.txt"), b"split compression world").unwrap();
        // pseudo-random, non-compressible payload so the archive exceeds the split size
        let mut seed: u64 = 0x1234_5678_9abc_def0;
        let payload: Vec<u8> = (0..200_000u32).map(|_| { seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407); (seed >> 33) as u8 }).collect();
        std::fs::write(dir.join("b.bin"), payload).unwrap();
        let arc = std::env::temp_dir().join(format!("uu_7z_splitc_out_{}.7z", std::process::id()));
        let arc_s = arc.to_string_lossy().to_string();
        compress_7z_inner(&dir.to_string_lossy(), &arc_s, 5, "").expect("compress");
        let parts = archive_common::split_volumes(&arc_s, 1000).expect("split");
        assert!(parts >= 2, "expected multiple parts, got {parts}");
        assert!(!arc.exists(), "original should be removed");

        let mut refs: Vec<String> = Vec::new();
        let mut idx = 1u32;
        loop {
            let p = format!("{arc_s}.{idx:03}");
            if !std::path::Path::new(&p).exists() { break; }
            refs.push(p);
            idx += 1;
        }
        let pref: Vec<&str> = refs.iter().map(|s| s.as_str()).collect();
        let list = list_7z_volumes(&pref).expect("list split");
        assert!(list.contains("a.txt") && list.contains("b.bin"));
        assert!(!list.contains("s.7z"), "archive must not contain itself: {list}");

        let out = dir.join("out");
        let out_s = out.to_string_lossy().to_string();
        let (total, error) = extract_7z_volumes(&pref, &out_s, None, "").expect("extract split");
        assert_eq!(error, 0);
        assert_eq!(total, 2);
        assert_eq!(std::fs::read(out.join("a.txt")).unwrap(), b"split compression world");
        assert_eq!(std::fs::read(out.join("b.bin")).unwrap().len(), 200000);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn password_split_round_trip_reports_progress_total() {
        let _g = crate::TEST_LOCK.lock().unwrap();
        let dir = std::env::temp_dir().join(format!("uu_7z_pwsplit_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("a.txt"), b"password 7z split").unwrap();
        let mut seed: u64 = 0xdead_beef_cafe_f00d;
        let payload: Vec<u8> = (0..120_000u32).map(|_| { seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407); (seed >> 33) as u8 }).collect();
        std::fs::write(dir.join("b.bin"), &payload).unwrap();
        // encrypt_header = true (as the app does when a password is set)
        let arc = std::env::temp_dir().join(format!("uu_7z_pwsplit_out_{}.7z", std::process::id()));
        let arc_s = arc.to_string_lossy().to_string();
        compress_7z_inner(&dir.to_string_lossy(), &arc_s, 5, "secret").expect("compress");

        // single-file password path must report a non-zero progress total
        extract_progress::reset(0);
        let out1 = std::env::temp_dir().join(format!("uu_7z_pwsplit_o1_{}", std::process::id()));
        std::fs::create_dir_all(&out1).unwrap();
        let (total, error) = extract_7z_with_password(&arc_s, out1.to_str().unwrap(), "secret").expect("extract pw");
        assert_eq!(error, 0);
        assert_eq!(total, 2);
        assert!(extract_progress::total_bytes() > 0, "password single must report a real total, got {}", extract_progress::total_bytes());

        // split path with password must also report a non-zero total
        let parts = archive_common::split_volumes(&arc_s, 40_000).expect("split");
        let mut refs: Vec<String> = Vec::new();
        let mut idx = 1u32;
        loop {
            let p = format!("{arc_s}.{idx:03}");
            if !std::path::Path::new(&p).exists() { break; }
            refs.push(p); idx += 1;
        }
        assert!(parts >= 2, "expected >= 2 parts, got {parts}");
        let pref: Vec<&str> = refs.iter().map(|s| s.as_str()).collect();
        extract_progress::reset(0);
        let out2 = std::env::temp_dir().join(format!("uu_7z_pwsplit_o2_{}", std::process::id()));
        std::fs::create_dir_all(&out2).unwrap();
        let (total2, error2) = extract_7z_volumes(&pref, out2.to_str().unwrap(), None, "secret").expect("extract pw split");
        assert_eq!(error2, 0);
        assert_eq!(total2, 2);
        assert!(extract_progress::total_bytes() > 0, "password split must report a real total, got {}", extract_progress::total_bytes());

        std::fs::remove_dir_all(&dir).ok();
        std::fs::remove_dir_all(&out1).ok();
        std::fs::remove_dir_all(&out2).ok();
    }
}

/// Manual host verification against files4testing vectors.
/// Set `UU_7Z_PARTS` (colon-separated part paths) + optional `UU_7Z_PASS` +
/// `UU_EXPECTED_SHA` (expected sha256 of the extracted file).
#[cfg(test)]
mod manual_volumes {

    /// 进度 store 是 per-cdylib 的**静态量**，cargo 默认并行跑同一个 crate
    /// 的测试，两个测试的 `reset(total)` + `add_bytes` 会互相踩：抢在前面的那个
    /// 会用自己的夹具尺寸改掉 total，后一个断言 total 的测试就红。凡是调了
    /// extract/compress 入口的测试都必须持这把锁。
    ///
    /// 实证：`archive_lzma-core` 的 `extract_progress_total_is_reported` 曾在 CI 上
    /// 以 `left: 327, right: 119` 失败，本地 25/25 通过。
    /// One lock per crate, not per test module: `crates/sevenz-core` has two
    /// `#[cfg(test)]` modules and they share the same per-cdylib progress
    /// statics, so a per-module mutex would let a test in one module race a
    /// test in the other. Reuses the crate-level [TEST_LOCK].
    // (see the crate-level `progress_lock` above)
    use super::*;

    fn sha256_file(path: &std::path::Path) -> Option<String> {
        let out = std::process::Command::new("shasum").arg("-a").arg("256").arg(path).output().ok()?;
        if !out.status.success() { return None; }
        Some(String::from_utf8_lossy(&out.stdout).split_whitespace().next().unwrap_or("").to_string())
    }

    fn walk_files(dir: &std::path::Path, out: &mut Vec<std::path::PathBuf>) {
        let mut stack: Vec<std::path::PathBuf> = vec![dir.to_path_buf()];
        while let Some(d) = stack.pop() {
            if let Ok(entries) = std::fs::read_dir(&d) {
                for e in entries.flatten() {
                    if e.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                        stack.push(e.path());
                    } else {
                        out.push(e.path());
                    }
                }
            }
        }
    }

    #[test]
    fn manual_7z_volumes() {
        let _g = crate::TEST_LOCK.lock().unwrap();
        let Ok(parts) = std::env::var("UU_7Z_PARTS") else {
            eprintln!("[manual_7z] skipped: UU_7Z_PARTS not set");
            return;
        };
        let pass = std::env::var("UU_7Z_PASS").unwrap_or_default();
        let exp = std::env::var("UU_EXPECTED_SHA").unwrap_or_default();
        let paths: Vec<String> = parts.split(':').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect();
        if paths.is_empty() { eprintln!("[manual_7z] skipped"); return; }
        let refs: Vec<&str> = paths.iter().map(|s| s.as_str()).collect();
        println!("[manual_7z] parts = {}  pass={}", paths.len(), if pass.is_empty() { "(none)" } else { "set" });

        match list_7z_volumes(&refs) {
            Ok(j) => println!("[manual_7z] list ok: {} entries", j.matches("\"n\"").count()),
            Err(e) => println!("[manual_7z] list err = {e}"),
        }

        let out = std::env::temp_dir().join(format!("uu_7z_verify_{}", std::process::id()));
        std::fs::create_dir_all(&out).unwrap();
        let out_s = out.to_string_lossy().to_string();
        let t0 = std::time::Instant::now();
        match extract_7z_volumes(&refs, &out_s, None, &pass) {
            Ok((total, fail)) => {
                println!("[manual_7z] extract total={total} fail={fail} in {:.2}s", t0.elapsed().as_secs_f64());
                if !exp.is_empty() {
                    let mut files = Vec::new();
                    walk_files(&out, &mut files);
                    let mut matched = false;
                    for f in &files {
                        let h = sha256_file(f).unwrap_or_default();
                        if h == exp { matched = true; }
                        println!("[manual_7z]   file={} sha={} {}", f.strip_prefix(&out).map(|p| p.to_string_lossy().to_string()).unwrap_or_default(), h, if h == exp { "MATCH" } else { "DIFF" });
                    }
                    println!("[manual_7z] VERIFY {}", if matched { "PASS" } else { "FAIL" });
                }
            }
            Err(e) => println!("[manual_7z] extract err = {e}"),
        }
        std::fs::remove_dir_all(&out).ok();
    }
}
