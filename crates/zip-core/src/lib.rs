use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, jlong, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, safe_join, extract_result_json, ProgressWriter, ProgressReader};
use archive_common::{extract_progress, compress_progress};
use std::collections::HashSet;
use std::io::{Read, Seek, SeekFrom};
use std::sync::Mutex;

static ZIP_ENCODING: Mutex<String> = Mutex::new(String::new());

fn get_enc() -> String { let g = ZIP_ENCODING.lock().unwrap(); if g.is_empty() { "UTF-8".to_string() } else { g.clone() } }
fn decode_entry_name<R: Read>(entry: &zip::read::ZipFile<'_, R>, encoding: &str) -> String {
    let raw = entry.name_raw();
    match encoding {
        "SHIFT-JIS" | "CP932" => {
            let (dec, _) = encoding_rs::SHIFT_JIS.decode_without_bom_handling(raw);
            dec.into_owned()
        }
        "GBK" | "GB2312" => {
            let (dec, _) = encoding_rs::GBK.decode_without_bom_handling(raw);
            dec.into_owned()
        }
        _ => {
            // Try UTF-8 first, fall back to raw lossy
            entry.name().to_string()
        }
    }
}

// ─── split-volume zip (`.zip.001/.002` byte-split, or `.z01/.z02/.zip`) ───

/// Presents multiple part files as a single logical `Read + Seek` stream, so
/// split zip archives can be parsed/extracted without copying to disk.
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
            let f = std::fs::File::open(p).map_err(|e| format!("ZIP: {e}"))?;
            total += f.metadata().map_err(|e| format!("ZIP: {e}"))?.len();
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
            self.parts[self.cur].seek(SeekFrom::Start(self.pos - part_start))?;
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
    fn seek(&mut self, pos: SeekFrom) -> std::io::Result<u64> {
        let new_pos = match pos {
            SeekFrom::Start(p) => p,
            SeekFrom::End(o) => (self.len as i64 + o).max(0) as u64,
            SeekFrom::Current(o) => (self.pos as i64 + o).max(0) as u64,
        };
        if new_pos > self.len {
            return Err(std::io::Error::new(std::io::ErrorKind::InvalidInput, "seek past end"));
        }
        self.locate(new_pos);
        Ok(new_pos)
    }
}

fn list_zip_from<R: Read + Seek>(mut archive: zip::ZipArchive<R>) -> Result<String, String> {
    let enc = get_enc();
    let mut all: Vec<(String, u64, bool, bool)> = Vec::with_capacity(archive.len());
    for i in 0..archive.len() {
        // by_index_raw: metadata only — works for AES-encrypted entries without
        // a password (by_index would skip them with PASSWORD_REQUIRED).
        let entry = match archive.by_index_raw(i) { Ok(e) => e, Err(_) => continue };
        let name = decode_entry_name(&entry, &enc).replace('\\', "/").trim_matches('/').to_string();
        if name.is_empty() { continue; }
        let is_dir = entry.is_dir();
        all.push((name.clone(), entry.size(), is_dir, entry.encrypted()));
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

fn list_zip_inner(input: &str) -> Result<String, String> {
    let file = std::fs::File::open(input).map_err(|e| format!("{e}"))?;
    let archive = zip::ZipArchive::new(file).map_err(|e| format!("{e}"))?;
    list_zip_from(archive)
}

fn list_zip_volumes(paths: &[&str]) -> Result<String, String> {
    let reader = ConcatReader::open(paths)?;
    let archive = zip::ZipArchive::new(reader).map_err(|e| format!("ZIP split: {e}"))?;
    list_zip_from(archive)
}

fn extract_zip_from<R: Read + Seek>(
    reader: R, output: &str, password: &str, selected: Option<&HashSet<String>>,
) -> Result<(u32, u32), String> {
    let enc = get_enc();
    let mut archive = zip::ZipArchive::new(reader).map_err(|e| format!("{e}"))?;
    let total = archive.len() as u32;
    let mut fail = 0u32;
    let pw = if password.is_empty() { None } else { Some(password.as_bytes()) };
    let mut prog_total = 0u64;
    for i in 0..archive.len() {
        if let Ok(entry) = archive.by_index_raw(i) {
            let name = decode_entry_name(&entry, &enc).replace('\\', "/").trim_matches('/').to_string();
            if name.is_empty() || entry.is_dir() { continue; }
            let keep = match selected {
                None => true,
                Some(ss) => ss.contains(&name) || ss.iter().any(|s| name.starts_with(&format!("{s}/"))),
            };
            if keep { prog_total += entry.size(); }
        }
    }
    extract_progress::reset(prog_total);
    let mut selected_count = 0u32;
    for i in 0..archive.len() {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        let mut entry = if let Some(p) = pw { archive.by_index_decrypt(i, p).map_err(|e| format!("{e}"))? } else { archive.by_index(i).map_err(|e| format!("{e}"))? };
        let name = decode_entry_name(&entry, &enc).replace('\\', "/").trim_matches('/').to_string();
        if name.is_empty() || entry.is_dir() { continue; }
        if let Some(ss) = selected {
            if !ss.contains(&name) && !ss.iter().any(|s| name.starts_with(&format!("{s}/"))) { continue; }
        }
        selected_count += 1;
        extract_progress::set_name(&name);
        extract_progress::set_file(entry.size());
        let dest = safe_join(output, &name).map_err(|e| format!("{e}"))?;
        if let Some(p) = dest.parent() { std::fs::create_dir_all(p).map_err(|e| format!("{e}"))?; }
        let mut out = ProgressWriter::extract(std::fs::File::create(&dest).map_err(|e| format!("{e}"))?);
        if std::io::copy(&mut entry, &mut out).is_err() {
            let _ = std::fs::remove_file(&dest);
            fail += 1;
        }
    }
    if selected.is_some() { Ok((selected_count, fail)) } else { Ok((total, fail)) }
}

fn extract_zip_all_inner(input: &str, output: &str) -> Result<(u32, u32), String> {
    extract_zip_with_password(input, output, "")
}
fn extract_zip_with_password(input: &str, output: &str, password: &str) -> Result<(u32, u32), String> {
    let file = std::fs::File::open(input).map_err(|e| format!("{e}"))?;
    extract_zip_from(file, output, password, None)
}
fn extract_zip_selected_inner(input: &str, output: &str, selected: &str) -> Result<(u32, u32), String> {
    let ss: HashSet<String> = selected.lines().filter(|l| !l.is_empty()).map(|s| s.to_string()).collect();
    if ss.is_empty() { return Ok((0, 0)); }
    let file = std::fs::File::open(input).map_err(|e| format!("{e}"))?;
    extract_zip_from(file, output, "", Some(&ss))
}

fn extract_zip_volumes(paths: &[&str], output: &str, password: &str, selected: Option<&HashSet<String>>) -> Result<(u32, u32), String> {
    let reader = ConcatReader::open(paths)?;
    extract_zip_from(reader, output, password, selected)
}

fn total_bytes(base: &str, rel: &str) -> u64 {
    let dir_path = if rel.is_empty() { base.to_string() } else { format!("{base}/{rel}") };
    let mut total = 0u64;
    if let Ok(entries) = std::fs::read_dir(&dir_path) {
        for entry in entries.flatten() {
            let ft = if let Ok(t) = entry.file_type() { t } else { continue };
            if ft.is_dir() { total += total_bytes(base, &format!("{}/{}", rel, entry.file_name().to_string_lossy())); }
            else if ft.is_file() { total += entry.metadata().map(|m| m.len()).unwrap_or(0); }
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
    compress_progress::reset(total_bytes(input, ""));

    fn add_dir(zip: &mut zip::write::ZipWriter<std::fs::File>, base: &str, rel: &str, method: zip::CompressionMethod, level: i32, pw: Option<&str>) -> Result<u32, String> where zip::write::ZipWriter<std::fs::File>: std::io::Write {
        let mut fail = 0u32;
        // Iterative DFS — deeply nested trees can't overflow the stack.
        let mut stack: Vec<String> = vec![rel.to_string()];
        while let Some(rel) = stack.pop() {
            let dir_path = if rel.is_empty() { base.to_string() } else { format!("{base}/{rel}") };
            let entries = std::fs::read_dir(&dir_path).map_err(|e| format!("{e}"))?;
            for entry in entries {
                if compress_progress::cancelled() { return Err("cancelled".to_string()); }
                let entry = entry.map_err(|e| format!("{e}"))?;
                let name = entry.file_name().to_string_lossy().to_string();
                let file_rel = if rel.is_empty() { name.clone() } else { format!("{rel}/{name}") };
                let file_type = entry.file_type().map_err(|e| format!("{e}"))?;
                if file_type.is_dir() {
                    // Don't add directory entries; ZIP handles dir creation on extract
                    stack.push(file_rel);
                } else if file_type.is_file() {
                    let base = zip::write::FileOptions::<'_, ()>::default()
                        .compression_method(method)
                        .compression_level(if level <= 0 { None } else { Some(level as i64) });
                    if let Some(p) = pw {
                        zip.start_file(&file_rel, base.with_aes_encryption(zip::AesMode::Aes256, p)).map_err(|e| format!("{e}"))?;
                    } else {
                        zip.start_file(&file_rel, base).map_err(|e| format!("{e}"))?;
                    }
                    compress_progress::set_name(&file_rel);
                    let mut f = std::fs::File::open(&entry.path()).map_err(|e| format!("{e}"))?;
                    compress_progress::set_file(f.metadata().map(|m| m.len()).unwrap_or(0));
                    if std::io::copy(&mut ProgressReader::compress(&mut f), zip).is_err() {
                        if compress_progress::cancelled() { return Err("cancelled".to_string()); }
                        fail += 1;
                    }
                }
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
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|panic| {
        let msg = panic.downcast_ref::<&str>().copied()
            .or_else(|| panic.downcast_ref::<String>().map(|s| s.as_str()))
            .unwrap_or("unknown panic");
        Err(format!("panic: {msg}"))
    })
}

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipNeedsPassword(mut e: JNIEnv, _: JClass, i: JString) -> jboolean {
    let inp = s(&mut e, &i);
    match guarded(move || {
        let file = std::fs::File::open(&inp).map_err(|e| format!("ZIP: {e}"))?;
        let mut arc = zip::ZipArchive::new(file).map_err(|e| format!("ZIP: {e}"))?;
        for idx in 0..arc.len() {
            if let Ok(entry) = arc.by_index_raw(idx) { if entry.encrypted() { return Ok(true); } }
        }
        Ok(false)
    }) { Ok(true) => JNI_TRUE, Ok(false) => JNI_FALSE, Err(er) => { let _ = e.throw_new("java/io/IOException", er); JNI_FALSE }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtractWithPassword(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, pw: JString) -> jstring {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let pwd = s(&mut e, &pw);
    match guarded(move || extract_zip_with_password(&inp, &out, &pwd)) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = std::fs::create_dir_all(&out);
    match guarded(move || extract_zip_all_inner(&inp, &out)) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtractSelected(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel: JString) -> jstring {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel);
    match guarded(move || extract_zip_selected_inner(&inp, &out, &sel_str)) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtractSelectedWithPassword(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel: JString, pw: JString) -> jstring {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel); let pwd = s(&mut e, &pw);
    let ss: HashSet<String> = sel_str.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(move || {
        let file = std::fs::File::open(&inp).map_err(|e| format!("{e}"))?;
        extract_zip_from(file, &out, &pwd, Some(&ss))
    }) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipCompressCancel(_: JNIEnv, _: JClass) { compress_progress::cancel(); }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipCompressProgressCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipCompressProgressTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipCompressProgressFileCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipCompressProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipCompressProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&compress_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipCompress(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, lv: JString, pw: JString, sp: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let lvl = s(&mut e, &lv); let pwd = s(&mut e, &pw); let split = s(&mut e, &sp);
    let level: i32 = lvl.parse().unwrap_or(5);
    let split_size: u64 = split.parse().unwrap_or(0);
    match guarded(move || {
        let f = compress_zip_inner(&inp, &out, level, &pwd)?;
        if f == 0 && split_size > 0 {
            archive_common::split_volumes(&out, split_size)?;
        }
        Ok(f)
    }) { Ok(0) => JNI_TRUE, Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("ZIP compress: {f} failed")); JNI_FALSE }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("ZIP compress: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i);
    match guarded(move || list_zip_inner(&inp)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("{er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtractProgressFileCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtractProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtractProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }

fn vol_refs(vols: &[String]) -> Vec<&str> { vols.iter().map(|s| s.as_str()).collect() }

fn vol_list(vs: &str) -> Vec<String> { vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect() }

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipListEntriesVolumes(mut e: JNIEnv, _: JClass, v: JString) -> jstring {
    let vs = s(&mut e, &v); let vols = vol_list(&vs);
    match guarded(move || list_zip_volumes(&vol_refs(&vols))) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtractVolumes(mut e: JNIEnv, _: JClass, _t: JString, v: JString, o: JString) -> jstring {
    let vs = s(&mut e, &v); let out = s(&mut e, &o); let _ = std::fs::create_dir_all(&out);
    let vols = vol_list(&vs);
    match guarded(move || extract_zip_volumes(&vol_refs(&vols), &out, "", None)) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtractSelectedVolumes(mut e: JNIEnv, _: JClass, _t: JString, v: JString, o: JString, sel: JString) -> jstring {
    let vs = s(&mut e, &v); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel);
    let vols = vol_list(&vs);
    let ss: HashSet<String> = sel_str.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(move || extract_zip_volumes(&vol_refs(&vols), &out, "", Some(&ss))) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtractVolumesWithPassword(mut e: JNIEnv, _: JClass, _t: JString, v: JString, o: JString, pw: JString) -> jstring {
    let vs = s(&mut e, &v); let out = s(&mut e, &o); let pwd = s(&mut e, &pw); let _ = std::fs::create_dir_all(&out);
    let vols = vol_list(&vs);
    match guarded(move || extract_zip_volumes(&vol_refs(&vols), &out, &pwd, None)) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtractSelectedVolumesWithPassword(mut e: JNIEnv, _: JClass, _t: JString, v: JString, o: JString, sel: JString, pw: JString) -> jstring {
    let vs = s(&mut e, &v); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel); let pwd = s(&mut e, &pw); let _ = std::fs::create_dir_all(&out);
    let vols = vol_list(&vs);
    let ss: HashSet<String> = sel_str.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(move || extract_zip_volumes(&vol_refs(&vols), &out, &pwd, Some(&ss))) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipVolumesNeedsPassword(mut e: JNIEnv, _: JClass, v: JString) -> jboolean {
    let vs = s(&mut e, &v); let vols = vol_list(&vs);
    match guarded(move || {
        let reader = ConcatReader::open(&vol_refs(&vols))?;
        let mut arc = zip::ZipArchive::new(reader).map_err(|e| format!("ZIP split: {e}"))?;
        for idx in 0..arc.len() {
            if let Ok(entry) = arc.by_index_raw(idx) { if entry.encrypted() { return Ok(true); } }
        }
        Ok(false)
    }) { Ok(true) => JNI_TRUE, Ok(false) => JNI_FALSE, Err(er) => { let _ = e.throw_new("java/io/IOException", er); JNI_FALSE }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> std::path::PathBuf {
        use std::sync::atomic::{AtomicU64, Ordering};
        static CT: AtomicU64 = AtomicU64::new(0);
        std::env::temp_dir().join(format!("uu_zip_vol_{}_{}_{}", std::process::id(), CT.fetch_add(1, Ordering::SeqCst), tag))
    }

    fn make_split_zip() -> (Vec<std::path::PathBuf>, std::path::PathBuf) {
        let dir = tmp("src");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("a.txt"), b"hello split zip world").unwrap();
        std::fs::write(dir.join("b.bin"), (0..512u32).map(|i| (i % 251) as u8).collect::<Vec<u8>>()).unwrap();
        let arc = tmp("out.zip");
        compress_zip_inner(dir.to_str().unwrap(), arc.to_str().unwrap(), 5, "").expect("compress");
        let bytes = std::fs::read(&arc).unwrap();
        let parts_dir = tmp("parts");
        std::fs::create_dir_all(&parts_dir).unwrap();
        let split = bytes.len() / 2;
        let p1 = parts_dir.join("split.zip.001");
        let p2 = parts_dir.join("split.zip.002");
        std::fs::write(&p1, &bytes[..split]).unwrap();
        std::fs::write(&p2, &bytes[split..]).unwrap();
        std::fs::remove_dir_all(&dir).ok();
        std::fs::remove_file(&arc).ok();
        (vec![p1, p2], parts_dir)
    }

    #[test]
    fn concat_reader_lists_and_extracts_split_zip() {
        let (vols, parts_dir) = make_split_zip();
        let paths: Vec<String> = vols.iter().map(|p| p.to_string_lossy().to_string()).collect();
        let refs: Vec<&str> = paths.iter().map(|s| s.as_str()).collect();

        let list = list_zip_volumes(&refs).expect("list volumes");
        assert!(list.contains("a.txt") && list.contains("b.bin"), "missing entries in {list}");

        let out = tmp("outdir");
        std::fs::create_dir_all(&out).unwrap();
        let out_s = out.to_string_lossy().to_string();
        let (total, error) = extract_zip_volumes(&refs, &out_s, "", None).expect("extract volumes");
        assert_eq!(error, 0);
        assert_eq!(total, 2);
        assert_eq!(std::fs::read(out.join("a.txt")).unwrap(), b"hello split zip world");
        assert_eq!(std::fs::read(out.join("b.bin")).unwrap().len(), 512);

        std::fs::remove_dir_all(&out).ok();
        std::fs::remove_dir_all(&parts_dir).ok();
    }

    #[test]
    fn concat_reader_selected_extract_split_zip() {
        let (vols, parts_dir) = make_split_zip();
        let paths: Vec<String> = vols.iter().map(|p| p.to_string_lossy().to_string()).collect();
        let refs: Vec<&str> = paths.iter().map(|s| s.as_str()).collect();
        let out = tmp("selout");
        std::fs::create_dir_all(&out).unwrap();
        let out_s = out.to_string_lossy().to_string();
        let ss: HashSet<String> = ["a.txt".to_string()].into_iter().collect();
        let (total, error) = extract_zip_volumes(&refs, &out_s, "", Some(&ss)).expect("selected extract");
        assert_eq!(error, 0);
        assert_eq!(total, 1);
        assert_eq!(std::fs::read(out.join("a.txt")).unwrap(), b"hello split zip world");
        assert!(!out.join("b.bin").exists());
        std::fs::remove_dir_all(&out).ok();
        std::fs::remove_dir_all(&parts_dir).ok();
    }

    #[test]
    fn compress_with_split_round_trip() {
        let dir = tmp("splitc");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("a.txt"), b"split zip compression world").unwrap();
        let mut seed: u64 = 0x1234_5678_9abc_def0;
        let payload: Vec<u8> = (0..200_000u32).map(|_| { seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407); (seed >> 33) as u8 }).collect();
        std::fs::write(dir.join("b.bin"), payload).unwrap();
        let arc = tmp("s.zip");
        let arc_s = arc.to_string_lossy().to_string();
        compress_zip_inner(dir.to_str().unwrap(), &arc_s, 5, "").expect("compress");
        let parts = archive_common::split_volumes(&arc_s, 1000).expect("split");
        assert!(parts >= 2, "expected multiple parts, got {parts}");

        let mut refs: Vec<String> = Vec::new();
        let mut idx = 1u32;
        loop {
            let p = format!("{arc_s}.{idx:03}");
            if !std::path::Path::new(&p).exists() { break; }
            refs.push(p);
            idx += 1;
        }
        let pref: Vec<&str> = refs.iter().map(|s| s.as_str()).collect();
        let list = list_zip_volumes(&pref).expect("list split");
        assert!(list.contains("a.txt") && list.contains("b.bin"));

        let out = tmp("sout");
        std::fs::create_dir_all(&out).unwrap();
        let out_s = out.to_string_lossy().to_string();
        let (total, error) = extract_zip_volumes(&pref, &out_s, "", None).expect("extract split");
        assert_eq!(error, 0);
        assert_eq!(total, 2);
        assert_eq!(std::fs::read(out.join("a.txt")).unwrap(), b"split zip compression world");
        assert_eq!(std::fs::read(out.join("b.bin")).unwrap().len(), 200000);
        std::fs::remove_dir_all(&dir).ok();
        std::fs::remove_dir_all(&out).ok();
    }

    /// Manual host verification against a real split zip (e.g. made by 7-Zip).
    /// Set `UU_ZIP_PARTS` (colon-separated part paths).
    #[test]
    fn manual_zip_volumes() {
        let Ok(parts) = std::env::var("UU_ZIP_PARTS") else {
            eprintln!("[manual_zip] skipped: UU_ZIP_PARTS not set");
            return;
        };
        let paths: Vec<String> = parts.split(':').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect();
        if paths.is_empty() { eprintln!("[manual_zip] skipped"); return; }
        let refs: Vec<&str> = paths.iter().map(|s| s.as_str()).collect();
        println!("[manual_zip] parts = {}", paths.len());
        match list_zip_volumes(&refs) {
            Ok(j) => println!("[manual_zip] list ok: {} entries", j.matches("\"n\"").count()),
            Err(e) => println!("[manual_zip] list err = {e}"),
        }
        let out = std::env::temp_dir().join(format!("uu_zip_verify_{}", std::process::id()));
        std::fs::create_dir_all(&out).unwrap();
        let out_s = out.to_string_lossy().to_string();
        match extract_zip_volumes(&refs, &out_s, "", None) {
            Ok((total, fail)) => println!("[manual_zip] extract total={total} fail={fail}"),
            Err(e) => println!("[manual_zip] extract err = {e}"),
        }
        std::fs::remove_dir_all(&out).ok();
    }

    /// Mirrors the app's 4 write→read-back combos to isolate the
    /// password+split failure.
    #[test]
    fn password_and_split_round_trips() {
        let dir = tmp("pw");
        std::fs::create_dir_all(&dir).unwrap();
        let payload: Vec<u8> = (0..400_000u32).map(|i| (i % 251) as u8).collect();
        std::fs::write(dir.join("a.txt"), b"password split round trip").unwrap();
        std::fs::write(dir.join("b.bin"), &payload).unwrap();

        // 1) no-password single
        let s1 = tmp("pw_ns.zip");
        compress_zip_inner(dir.to_str().unwrap(), s1.to_str().unwrap(), 5, "").expect("compress");
        assert!(extract_zip_from(std::fs::File::open(&s1).unwrap(), dir.join("o1").to_str().unwrap(), "", None).is_ok(), "no-pw single");

        // 2) password single
        let s2 = tmp("pw_ps.zip");
        compress_zip_inner(dir.to_str().unwrap(), s2.to_str().unwrap(), 5, "secret").expect("compress");
        let o2 = extract_zip_from(std::fs::File::open(&s2).unwrap(), dir.join("o2").to_str().unwrap(), "secret", None);
        assert!(o2.is_ok(), "pw single failed: {o2:?}");
        assert!(extract_progress::total_bytes() > 0, "pw single must report a real total, got {}", extract_progress::total_bytes());

        // 3) no-password split
        let s3 = tmp("pw_nsp.zip");
        compress_zip_inner(dir.to_str().unwrap(), s3.to_str().unwrap(), 5, "").expect("compress");
        let n3 = archive_common::split_volumes(s3.to_str().unwrap(), 2000).expect("split");
        let parts3 = collect_parts(&s3, n3);
        let refs3: Vec<&str> = parts3.iter().map(|s| s.as_str()).collect();
        assert!(list_zip_volumes(&refs3).is_ok(), "no-pw split list failed");

        // 4) password split — LARGE payload + many parts (mirrors the device case)
        std::fs::remove_dir_all(dir.join("o1")).ok();
        std::fs::remove_dir_all(dir.join("o2")).ok();
        let big: Vec<u8> = {
            let mut seed: u64 = 0x9e37_79b9_7f4a_7c15;
            (0..30_000_000u32).map(|_| { seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407); (seed >> 33) as u8 }).collect()
        };
        std::fs::write(dir.join("big.bin"), &big).unwrap();
        let s4 = tmp("pw_psp.zip");
        compress_zip_inner(dir.to_str().unwrap(), s4.to_str().unwrap(), 5, "secret").expect("compress");
        let n4 = archive_common::split_volumes(s4.to_str().unwrap(), 1024 * 1024).expect("split");
        eprintln!("[pw-split] {n4} parts, archive {} bytes", std::fs::metadata(&s4).map(|m| m.len()).unwrap_or(0));
        let parts4 = collect_parts(&s4, n4);
        let refs4: Vec<&str> = parts4.iter().map(|s| s.as_str()).collect();
        let list = list_zip_volumes(&refs4);
        eprintln!("[pw-split] list = {list:?}");
        {
            let out = tmp("pw_osplit");
            std::fs::create_dir_all(&out).unwrap();
            extract_progress::reset(0);
            let ex = extract_zip_volumes(&refs4, out.to_str().unwrap(), "secret", None);
            eprintln!("[pw-split] extract = {ex:?}");
            assert!(ex.is_ok(), "pw split extract failed: {ex:?}");
            assert!(extract_progress::total_bytes() > 0, "pw split must report a real total, got {}", extract_progress::total_bytes());
            std::fs::remove_dir_all(&out).ok();
        }
        assert!(list.is_ok(), "pw split list FAILED: {list:?}");

        std::fs::remove_dir_all(&dir).ok();
    }

    fn collect_parts(path: &std::path::Path, n: u64) -> Vec<String> {
        let s = path.to_string_lossy();
        (1..=n).map(|i| format!("{s}.{i:03}")).collect()
    }

    #[test]
    fn password_selected_extracts_only_selected() {
        let dir = tmp("pws");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("a.txt"), b"AAA").unwrap();
        std::fs::write(dir.join("b.txt"), b"BBB").unwrap();
        std::fs::write(dir.join("c.txt"), b"CCC").unwrap();
        let z = tmp("pws_o.zip");
        compress_zip_inner(dir.to_str().unwrap(), z.to_str().unwrap(), 5, "secret").expect("compress");
        // selected + password must extract ONLY the selected entries
        let out = tmp("pws_out");
        std::fs::create_dir_all(&out).unwrap();
        let ss: HashSet<String> = ["b.txt".to_string()].into_iter().collect();
        let r = extract_zip_from(std::fs::File::open(&z).unwrap(), out.to_str().unwrap(), "secret", Some(&ss));
        assert!(r.is_ok(), "selected+pw failed: {r:?}");
        let (total, fail) = r.unwrap();
        assert_eq!(fail, 0);
        assert_eq!(total, 1);
        assert!(out.join("b.txt").exists(), "selected b.txt missing");
        assert!(!out.join("a.txt").exists(), "a.txt should NOT be extracted");
        assert!(!out.join("c.txt").exists(), "c.txt should NOT be extracted");
        std::fs::remove_dir_all(&dir).ok();
        std::fs::remove_file(&z).ok();
        std::fs::remove_dir_all(&out).ok();
    }
}
