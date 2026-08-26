use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, jlong, jint, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, safe_join, extract_result_json, ProgressWriter, ProgressReader};
use archive_common::{extract_progress, compress_progress};
use std::collections::HashSet;
use std::io::{Read, Seek, SeekFrom, Write};
use std::sync::atomic::Ordering;
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

    /// Absolute start offset of each disk within the concatenated stream.
    /// Disk 0 starts at 0; disk N starts after the previous N disks.
    fn disk_offsets(&self) -> Vec<u64> {
        let mut v = Vec::with_capacity(self.bounds.len());
        v.push(0);
        for &b in &self.bounds {
            v.push(b);
        }
        v.pop(); // last entry is total length = end of final disk, not a start
        v
    }

    /// True when this is a PKWARE true-split set (`name.z01/.z02/.../.zip`),
    /// where each part is a separate disk with its own local headers and the
    /// central directory lives in the final `.zip`. Byte-splits
    /// (`name.zip.001/.002`) are a single logical stream and return false.
    fn is_pkware_split(&self, paths: &[&str]) -> bool {
        paths.iter().any(|p| {
            let lower = p.to_lowercase();
            lower.ends_with(".z01") || lower.ends_with(".z02") || lower.ends_with(".z03")
        })
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
    let archive = if reader.is_pkware_split(paths) {
        let offsets = reader.disk_offsets();
        zip::ZipArchive::with_disk_offsets(zip::read::Config::default(), reader, &offsets)
            .map_err(|e| format!("ZIP split: {e}"))?
    } else {
        zip::ZipArchive::new(reader).map_err(|e| format!("ZIP split: {e}"))?
    };
    list_zip_from(archive)
}

/// Thread count override (0 = auto). Set via JNI (settings) or the
/// `UU_PARALLEL_THREADS` env var (host tests). Clamped to 1..=8.
static PARALLEL_THREADS: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);

/// Sets the parallel-decode thread count; 0 restores the automatic choice.
#[doc(hidden)]
pub fn set_parallel_threads(n: u32) {
    PARALLEL_THREADS.store(n, Ordering::Relaxed);
}

fn parallel_thread_count() -> usize {
    if let Ok(v) = std::env::var("UU_PARALLEL_THREADS") {
        if let Ok(n) = v.parse::<u32>() {
            if n > 0 { return n.clamp(1, 8) as usize; }
        }
    }
    let n = PARALLEL_THREADS.load(Ordering::Relaxed);
    if n > 0 {
        n.clamp(1, 8) as usize
    } else {
        std::thread::available_parallelism().map(|n| n.get()).unwrap_or(2).min(4)
    }
}

/// Decodes one zip entry (by central-directory index) into RAM via its own
/// ZipArchive instance — the archive holds a single reader, so concurrent
/// entry reads need one archive per thread. Bounded by the caller (entries
/// larger than the buffering cap never reach here).
fn zip_decode_one(input: &str, index: usize, expected_size: u64) -> Result<(Vec<u8>, u64), String> {
    let mut a = zip::ZipArchive::new(std::fs::File::open(input).map_err(|e| format!("{e}"))?)
        .map_err(|e| format!("{e}"))?;
    let entry = a.by_index(index).map_err(|e| format!("{e}"))?;
    let size = entry.size();
    let mut data = Vec::with_capacity(size as usize);
    entry.take(size).read_to_end(&mut data).map_err(|e| format!("{e}"))?;
    if data.len() as u64 != size {
        return Err(format!("zip short read: {} != {}", data.len(), size));
    }
    let _ = expected_size;
    Ok((data, size))
}

/// Decodes [items] (index, name, size) concurrently, preserving order.
fn zip_decode_batch(input: &str, items: &[(usize, String, u64)]) -> Vec<Result<(Vec<u8>, u64), String>> {
    let mut out: Vec<Result<(Vec<u8>, u64), String>> = items.iter().map(|_| Ok((Vec::new(), 0))).collect();
    std::thread::scope(|s| {
        let handles: Vec<_> = items
            .iter()
            .enumerate()
            .map(|(k, (idx, _, size))| s.spawn(move || (k, zip_decode_one(input, *idx, *size))))
            .collect();
        for (idx, h) in handles.into_iter().enumerate() {
            match h.join() {
                Ok((k, Ok(v))) => out[k] = Ok(v),
                Ok((k, Err(e))) => out[k] = Err(e),
                Err(_) => out[idx] = Err("zip parallel decode panicked".into()),
            }
        }
    });
    out
}

/// Writes an already-decoded entry: per-file bar + overall bar from the write.
fn write_zip_entry_data(name: &str, size: u64, data: &[u8], output: &str) -> Result<(), String> {
    if extract_progress::cancelled() { return Err("cancelled".to_string()); }
    extract_progress::set_name(name);
    extract_progress::set_file(size);
    let dest = safe_join(output, name)?;
    if let Some(p) = dest.parent() { std::fs::create_dir_all(p).map_err(|e| format!("{e}"))?; }
    let mut out = ProgressWriter::extract(std::io::BufWriter::with_capacity(256 * 1024, std::fs::File::create(&dest).map_err(|e| format!("{e}"))?));
    if out.write_all(data).is_err() || out.flush().is_err() {
        let _ = std::fs::remove_file(&dest);
        return Err("zip write failed".into());
    }
    Ok(())
}

/// Parallel full-extract fast path for SINGLE-file, no-password zips. Entries
/// ≤ 32 MiB decode in bounded batches (one thread per entry, each with its own
/// ZipArchive); larger entries stream sequentially. Returns Ok(None) when
/// parallel doesn't apply, and the caller falls back to the sequential path.
fn extract_zip_parallel(input: &str, output: &str) -> Result<Option<(u32, u32)>, String> {
    const ENTRY_LIMIT: u64 = 32 * 1024 * 1024;
    const BATCH_MEM_BUDGET: u64 = 128 * 1024 * 1024;
    let enc = get_enc();

    let mut scan = zip::ZipArchive::new(std::fs::File::open(input).map_err(|e| format!("{e}"))?)
        .map_err(|e| format!("{e}"))?;
    let mut buffered: Vec<(usize, String, u64)> = Vec::new();
    let mut streaming: Vec<(usize, String, u64)> = Vec::new();
    let mut prog_total = 0u64;
    for i in 0..scan.len() {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        if let Ok(entry) = scan.by_index_raw(i) {
            let name = decode_entry_name(&entry, &enc).replace('\\', "/").trim_matches('/').to_string();
            if name.is_empty() || entry.is_dir() { continue; }
            let size = entry.size();
            prog_total += size;
            if size <= ENTRY_LIMIT { buffered.push((i, name, size)); } else { streaming.push((i, name, size)); }
        }
    }
    let total = buffered.len() + streaming.len();
    if buffered.len() < 2 || total == 0 {
        return Ok(None);
    }
    extract_progress::reset(prog_total);
    let mut fail = 0u32;
    let n_threads = parallel_thread_count();

    let mut i = 0usize;
    while i < buffered.len() {
        let mut end = i;
        let mut sum = 0u64;
        while end < buffered.len() && end - i < n_threads {
            if sum + buffered[end].2 > BATCH_MEM_BUDGET && end > i { break; }
            sum += buffered[end].2;
            end += 1;
        }
        let batch = &buffered[i..end];
        let decoded = zip_decode_batch(input, batch);
        for (k, res) in decoded.into_iter().enumerate() {
            if extract_progress::cancelled() { return Err("cancelled".to_string()); }
            let (data, size) = res?;
            if write_zip_entry_data(&batch[k].1, size, &data, output).is_err() { fail += 1; }
        }
        i = end;
    }
    // Streaming entries sequentially (byte-level progress via the write).
    for (idx, name, size) in &streaming {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        let mut a = zip::ZipArchive::new(std::fs::File::open(input).map_err(|e| format!("{e}"))?)
            .map_err(|e| format!("{e}"))?;
        let entry = match a.by_index(*idx) {
            Ok(e) => e,
            Err(_e) => { fail += 1; continue; }
        };
        extract_progress::set_name(name);
        extract_progress::set_file(*size);
        let dest = match safe_join(output, name) {
            Ok(d) => d,
            Err(_) => { fail += 1; continue; }
        };
        if let Some(p) = dest.parent() { std::fs::create_dir_all(p).ok(); }
        let mut out = ProgressWriter::extract(std::io::BufWriter::with_capacity(256 * 1024, std::fs::File::create(&dest).map_err(|e| format!("{e}"))?));
        let mut limited = entry.take(*size);
        match std::io::copy(&mut limited, &mut out) {
            Ok(written) if written >= *size => {
                if std::io::Write::flush(&mut out).is_err() {
                    let _ = std::fs::remove_file(&dest);
                    fail += 1;
                }
            }
            _ => { let _ = std::fs::remove_file(&dest); fail += 1; }
        }
    }
    Ok(Some((total as u32, fail)))
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
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
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
        let entry = match if let Some(p) = pw { archive.by_index_decrypt(i, p) } else { archive.by_index(i) } {
            Ok(e) => e,
            Err(_) => { fail += 1; continue; }
        };
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
        let mut out = match std::fs::File::create(&dest) {
            Ok(f) => ProgressWriter::extract(std::io::BufWriter::with_capacity(256 * 1024, f)),
            Err(_) => { fail += 1; continue; }
        };
        // Cap the decompressed output at the declared uncompressed size so a
        // zip bomb can't exhaust disk. A short read (truncated/corrupt data)
        // must be detected too — io::copy returns Ok with fewer bytes on EOF,
        // which would silently leave an incomplete file.
        let size = entry.size();
        match std::io::copy(&mut entry.take(size), &mut out) {
            Ok(written) if written >= size => {
                // BufWriter may still hold bytes — a flush failure (e.g. disk
                // full) must fail the entry like a write error would.
                if std::io::Write::flush(&mut out).is_err() {
                    let _ = std::fs::remove_file(&dest);
                    fail += 1;
                }
            }
            _ => {
                let _ = std::fs::remove_file(&dest);
                fail += 1;
            }
        }
    }
    if selected.is_some() { Ok((selected_count, fail)) } else { Ok((total, fail)) }
}

fn extract_zip_all_inner(input: &str, output: &str) -> Result<(u32, u32), String> {
    extract_zip_with_password(input, output, "")
}
fn extract_zip_with_password(input: &str, output: &str, password: &str) -> Result<(u32, u32), String> {
    if password.is_empty() {
        if let Some(r) = extract_zip_parallel(input, output)? {
            return Ok(r);
        }
    }
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
    if reader.is_pkware_split(paths) {
        let offsets = reader.disk_offsets();
        let archive = zip::ZipArchive::with_disk_offsets(zip::read::Config::default(), reader, &offsets)
            .map_err(|e| format!("ZIP split: {e}"))?;
        // PKWARE spanned archives are byte splits: each disk continues exactly
        // where the previous one ended, so an entry whose data crosses a disk
        // boundary is still contiguous in the concatenated stream. The vendored
        // zip fork resolves each entry's local header via disk_offsets and
        // find_content reads compressed_size bytes straight through the seam.
        extract_zip_from_multi(archive, output, password, selected)
    } else {
        extract_zip_from(reader, output, password, selected)
    }
}

/// Extract from an already-opened archive with per-disk awareness (multi-disk
/// volumes). Same semantics as [extract_zip_from].
fn extract_zip_from_multi<R: Read + Seek>(
    mut archive: zip::ZipArchive<R>, output: &str, password: &str, selected: Option<&HashSet<String>>,
) -> Result<(u32, u32), String> {
    let enc = get_enc();
    let total = archive.len() as u32;
    let mut fail = 0u32;
    let pw = if password.is_empty() { None } else { Some(password.as_bytes()) };
    let mut prog_total = 0u64;
    for i in 0..archive.len() {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
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
        let entry = match if let Some(p) = pw { archive.by_index_decrypt(i, p) } else { archive.by_index(i) } {
            Ok(e) => e,
            Err(_) => { fail += 1; continue; }
        };
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
        let mut out = match std::fs::File::create(&dest) {
            Ok(f) => ProgressWriter::extract(std::io::BufWriter::with_capacity(256 * 1024, f)),
            Err(_) => { fail += 1; continue; }
        };
        let size = entry.size();
        // Short read (data crosses a disk boundary that's damaged / truncated,
        // or a corrupt archive) must fail this entry, not leave a partial file.
        match std::io::copy(&mut entry.take(size), &mut out) {
            Ok(written) if written >= size => {
                if std::io::Write::flush(&mut out).is_err() {
                    let _ = std::fs::remove_file(&dest);
                    fail += 1;
                }
            }
            _ => {
                let _ = std::fs::remove_file(&dest);
                fail += 1;
            }
        }
    }
    if selected.is_some() { Ok((selected_count, fail)) } else { Ok((total, fail)) }
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

/// Host-side (non-JNI) extraction for examples/tests/benchmarks.
#[doc(hidden)]
pub fn extract_zip_host(input: &str, output: &str, password: &str) -> Result<(u32, u32), String> {
    extract_zip_with_password(input, output, password)
}

/// Host-side list (mirrors the app's list JSON).
#[doc(hidden)]
pub fn list_zip_host(input: &str) -> Result<String, String> {
    list_zip_inner(input)
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
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let pwd = s(&mut e, &pw);
    match guarded(move || extract_zip_with_password(&inp, &out, &pwd)) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = std::fs::create_dir_all(&out);
    match guarded(move || extract_zip_all_inner(&inp, &out)) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtractSelected(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel);
    match guarded(move || extract_zip_selected_inner(&inp, &out, &sel_str)) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtractSelectedWithPassword(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel: JString, pw: JString) -> jstring {
    extract_progress::clear_cancel();
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
    compress_progress::clear_cancel();
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

/// Rewrites a zip archive applying in-place edits: each op is a line
/// `replace|path|srcPath`, `delete|path`, or `add|path|srcPath` (new line
/// separated). Untouched entries are byte-copied via `raw_copy_file` (their
/// encryption bytes and compression survive verbatim); replaced/added entries
/// are written fresh (re-encrypted when a password is supplied). The result is
/// written to `output` — callers swap it over the original afterwards.
/// Returns true when `f` is a PKWARE multi-disk zip archive: the EOCD record
/// (searched backwards from the file end) declares a disk number different
/// from the disk that holds the central directory. Such archives can't be
/// modified by zip_modify (which needs a single seekable stream).
fn is_multi_disk_zip(f: &mut std::fs::File) -> bool {
    let Ok(len) = f.metadata().map(|m| m.len()) else { return false };
    // Search the last 64 KiB + EOCD size for the EOCD signature.
    let win = len.saturating_sub(65536 + 22);
    let mut buf = vec![0u8; (len - win) as usize];
    if f.seek(SeekFrom::Start(win)).is_err() { return false; }
    if f.read_exact(&mut buf).is_err() { return false; }
    // Backwards for the last EOCD magic PK\x05\x06.
    let mut found: Option<usize> = None;
    let mut i = buf.len();
    while i >= 4 {
        i -= 1;
        if buf[i] == 0x06 && i >= 3 && buf[i - 1] == 0x05 && buf[i - 2] == 0x4b && buf[i - 3] == 0x50 {
            found = Some(i - 3);
            break;
        }
    }
    let Some(eocd) = found else { return false };
    if eocd + 8 > buf.len() { return false; }
    let disk_number = u16::from_le_bytes([buf[eocd + 4], buf[eocd + 5]]);
    let disk_with_cd = u16::from_le_bytes([buf[eocd + 6], buf[eocd + 7]]);
    disk_number != disk_with_cd
}

fn zip_modify(input: &str, output: &str, ops: &str, password: &str) -> Result<u32, String> {
    struct Op { kind: String, path: String, src: String }
    let mut op_list: Vec<Op> = Vec::new();
    for line in ops.lines() {
        let line = line.trim();
        if line.is_empty() { continue; }
        let mut parts = line.split('|');
        let kind = parts.next().unwrap_or("").trim().to_string();
        let path = parts.next().unwrap_or("").trim().to_string();
        let src = parts.next().unwrap_or("").trim().to_string();
        if path.is_empty() { return Err("ZIP modify: empty entry path".to_string()); }
        if !matches!(kind.as_str(), "replace" | "delete" | "add") {
            return Err(format!("ZIP modify: unknown op {}", kind));
        }
        if kind != "delete" && src.is_empty() {
            return Err(format!("ZIP modify: missing src for {kind} {path}"));
        }
        op_list.push(Op { kind, path, src });
    }
    if op_list.is_empty() { return Err("ZIP modify: no operations".to_string()); }

    let mut src_file = std::fs::File::open(input).map_err(|e| format!("ZIP modify open {input}: {e}"))?;
    // Reject PKWARE multi-disk archives: ZipArchive::new treats a multi-disk
    // stream as a single file and parses 0 entries, so a modify would rewrite
    // the archive with everything except the new entry lost. Detect via the
    // EOCD's disk-number fields (offsets 4 and 6 within the 22-byte record).
    if is_multi_disk_zip(&mut src_file) {
        return Err("ZIP modify: PKWARE multi-disk archives are not editable".to_string());
    }
    let mut arc = zip::ZipArchive::new(src_file).map_err(|e| format!("ZIP modify parse: {e}"))?;

    let tmp = format!("{output}.tmp{}", std::process::id());
    // Drop-guard: remove the temp file on ANY early return (error) so a failed
    // modify never leaves a `.tmp{pid}` behind. `arm()` disarms it once the
    // rename succeeds (the guard would otherwise delete the new archive).
    struct TmpGuard { path: String, armed: bool }
    impl TmpGuard {
        fn new(path: String) -> Self { Self { path, armed: true } }
        fn disarm(&mut self) { self.armed = false; }
    }
    impl Drop for TmpGuard {
        fn drop(&mut self) { if self.armed { let _ = std::fs::remove_file(&self.path); } }
    }
    let mut guard = TmpGuard::new(tmp.clone());
    let out_file = std::fs::File::create(&tmp).map_err(|e| format!("ZIP modify create {tmp}: {e}"))?;
    let mut zw = zip::write::ZipWriter::new(out_file);
    let pw = if password.is_empty() { None } else { Some(password.to_string()) };

    let mut added = 0u32;
    let mut applied = vec![false; op_list.len()]; // tracks which ops touched an entry
    for idx in 0..arc.len() {
        // Name comes from the raw entry (metadata only, no decryption needed).
        let name = {
            let raw = arc.by_index_raw(idx).map_err(|e| format!("ZIP modify read entry: {e}"))?;
            raw.name().to_string()
        };
        // Directory ops match the exact entry, its `dir/` form, or any entry
        // underneath `dir/` (cascading delete/replace).
        let op = op_list.iter().position(|o| {
            o.path == name || o.path == format!("{name}/") || name.starts_with(&format!("{}/", o.path))
        });
        match op {
            Some(i) => {
                applied[i] = true;
                let o = &op_list[i];
                if o.kind == "delete" { /* skip */ }
                else {
                    // Replace: write fresh bytes under the same name.
                    let raw_comp = {
                        let raw = arc.by_index_raw(idx).map_err(|e| format!("ZIP modify read entry: {e}"))?;
                        raw.compression()
                    };
                    let mut opts = zip::write::FileOptions::<'_, ()>::default()
                        .compression_method(if raw_comp == zip::CompressionMethod::Stored {
                            zip::CompressionMethod::Stored
                        } else {
                            zip::CompressionMethod::Deflated
                        })
                        .compression_level(if raw_comp == zip::CompressionMethod::Stored { None } else { Some(6) });
                    if let Some(p) = &pw {
                        opts = opts.with_aes_encryption(zip::AesMode::Aes256, p);
                    }
                    zw.start_file(&name, opts).map_err(|e| format!("ZIP modify start {name}: {e}"))?;
                    let mut src = std::fs::File::open(&o.src).map_err(|e| format!("ZIP modify open src {}: {e}", o.src))?;
                    std::io::copy(&mut src, &mut zw).map_err(|e| format!("ZIP modify write {name}: {e}"))?;
                    added += 1;
                }
            }
            None => {
                // Untouched member: byte-copy. `raw_copy_file_preserve_encryption`
                // keeps AES-encrypted entries' flag + AES extra field (bytes stay
                // byte-identical and still decrypt with the ORIGINAL password),
                // and plain-copies plain entries. A plain `raw_copy_file` would
                // drop the AES flag and leave ciphertext mislabeled as plaintext.
                let raw_entry = arc.by_index_raw(idx).map_err(|e| format!("ZIP modify copy {name}: {e}"))?;
                zw.raw_copy_file_preserve_encryption(raw_entry)
                    .map_err(|e| format!("ZIP modify copy {name}: {e}"))?;
            }
        }
    }
    // Any replace/delete that matched nothing is an error — the caller asked
    // to change a path that isn't in the archive.
    for (i, o) in op_list.iter().enumerate() {
        if o.kind != "add" && !applied[i] {
            return Err(format!("ZIP modify: path not found in archive: {}", o.path));
        }
    }
    // Appends ("add" ops whose path isn't in the archive).
    for o in &op_list {
        if o.kind != "add" { continue; }
        let exists = (0..arc.len()).any(|idx| arc.by_index(idx).map(|e| e.name().to_string() == o.path).unwrap_or(false));
        if exists { continue; }
        let mut opts = zip::write::FileOptions::<'_, ()>::default()
            .compression_method(zip::CompressionMethod::Deflated)
            .compression_level(Some(6));
        if let Some(p) = &pw {
            opts = opts.with_aes_encryption(zip::AesMode::Aes256, p);
        }
        zw.start_file(&o.path, opts).map_err(|e| format!("ZIP modify start {}: {e}", o.path))?;
        let mut src = std::fs::File::open(&o.src).map_err(|e| format!("ZIP modify open src {}: {e}", o.src))?;
        std::io::copy(&mut src, &mut zw).map_err(|e| format!("ZIP modify write {}: {e}", o.path))?;
        added += 1;
    }
    zw.finish().map_err(|e| format!("ZIP modify finish: {e}"))?;
    std::fs::rename(&tmp, output).map_err(|e| format!("ZIP modify rename: {e}"))?;
    guard.disarm(); // renamed over output — don't let the guard delete it
    Ok(added)
}

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipModify(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, ops: JString, pw: JString) -> jboolean {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let op_str = s(&mut e, &ops); let pwd = s(&mut e, &pw);
    match guarded(move || zip_modify(&inp, &out, &op_str, &pwd)) {
        Ok(_) => JNI_TRUE,
        Err(er) => { let _ = e.throw_new("java/io/IOException", format!("ZIP modify: {er}")); JNI_FALSE }
    }
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
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_setParallelThreads(_: JNIEnv, _: JClass, n: jint) { set_parallel_threads(n.max(0) as u32); }

fn vol_refs(vols: &[String]) -> Vec<&str> { vols.iter().map(|s| s.as_str()).collect() }

fn vol_list(vs: &str) -> Vec<String> { vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect() }

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipListEntriesVolumes(mut e: JNIEnv, _: JClass, v: JString) -> jstring {
    let vs = s(&mut e, &v); let vols = vol_list(&vs);
    match guarded(move || list_zip_volumes(&vol_refs(&vols))) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtractVolumes(mut e: JNIEnv, _: JClass, _t: JString, v: JString, o: JString) -> jstring {
    extract_progress::clear_cancel();
    let vs = s(&mut e, &v); let out = s(&mut e, &o); let _ = std::fs::create_dir_all(&out);
    let vols = vol_list(&vs);
    match guarded(move || extract_zip_volumes(&vol_refs(&vols), &out, "", None)) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtractSelectedVolumes(mut e: JNIEnv, _: JClass, _t: JString, v: JString, o: JString, sel: JString) -> jstring {
    extract_progress::clear_cancel();
    let vs = s(&mut e, &v); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel);
    let vols = vol_list(&vs);
    let ss: HashSet<String> = sel_str.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(move || extract_zip_volumes(&vol_refs(&vols), &out, "", Some(&ss))) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtractVolumesWithPassword(mut e: JNIEnv, _: JClass, _t: JString, v: JString, o: JString, pw: JString) -> jstring {
    extract_progress::clear_cancel();
    let vs = s(&mut e, &v); let out = s(&mut e, &o); let pwd = s(&mut e, &pw); let _ = std::fs::create_dir_all(&out);
    let vols = vol_list(&vs);
    match guarded(move || extract_zip_volumes(&vol_refs(&vols), &out, &pwd, None)) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZipCore_zipExtractSelectedVolumesWithPassword(mut e: JNIEnv, _: JClass, _t: JString, v: JString, o: JString, sel: JString, pw: JString) -> jstring {
    extract_progress::clear_cancel();
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

    /// zip_modify: replace an entry, delete another, add a third, verify the
    /// untouched entry is byte-identical and the archive still extracts.
    #[test]
    fn modify_replace_delete_add_round_trip() {
        let dir = tmp("mod");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("a.txt"), b"original a").unwrap();
        std::fs::write(dir.join("b.bin"), b"keep me verbatim").unwrap();
        std::fs::write(dir.join("c.txt"), b"delete me").unwrap();
        let arc = dir.join("in.zip");
        compress_zip_inner(dir.to_str().unwrap(), arc.to_str().unwrap(), 5, "").expect("compress");

        let new_a = dir.join("new_a.txt");
        std::fs::write(&new_a, b"REPLACED a content").unwrap();
        let new_d = dir.join("new_d.txt");
        std::fs::write(&new_d, b"added d content").unwrap();

        let out = dir.join("out.zip");
        let ops = format!(
            "replace|a.txt|{}\ndelete|c.txt\nadd|d.txt|{}",
            new_a.display(), new_d.display()
        );
        zip_modify(arc.to_str().unwrap(), out.to_str().unwrap(), &ops, "").expect("modify");

        // Extract and verify.
        let outdir = dir.join("x");
        std::fs::create_dir_all(&outdir).unwrap();
        let r = extract_zip_all_inner(out.to_str().unwrap(), outdir.to_str().unwrap());
        assert!(r.is_ok(), "extract after modify: {r:?}");
        assert_eq!(std::fs::read(outdir.join("a.txt")).unwrap(), b"REPLACED a content");
        assert_eq!(std::fs::read(outdir.join("b.bin")).unwrap(), b"keep me verbatim");
        assert_eq!(std::fs::read(outdir.join("d.txt")).unwrap(), b"added d content");
        assert!(!outdir.join("c.txt").exists(), "c.txt must be deleted");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// zip_modify on a password-protected archive: untouched encrypted entries
    /// survive (raw-copy keeps their bytes); a replaced entry re-encrypts.
    #[test]
    fn modify_password_archive_preserves_others() {
        let dir = tmp("modpw");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("a.txt"), b"secret a").unwrap();
        std::fs::write(dir.join("b.txt"), b"also secret").unwrap();
        let arc = dir.join("in.zip");
        compress_zip_inner(dir.to_str().unwrap(), arc.to_str().unwrap(), 5, "pw").expect("compress");

        let new_b = dir.join("nb.txt");
        std::fs::write(&new_b, b"new b").unwrap();
        let out = dir.join("out.zip");
        let ops = format!("replace|b.txt|{}", new_b.display());
        zip_modify(arc.to_str().unwrap(), out.to_str().unwrap(), &ops, "pw").expect("modify pw");

        let outdir = dir.join("x");
        std::fs::create_dir_all(&outdir).unwrap();
        let r = extract_zip_with_password(out.to_str().unwrap(), outdir.to_str().unwrap(), "pw");
        assert!(r.is_ok(), "extract pw after modify: {r:?}");
        assert_eq!(std::fs::read(outdir.join("a.txt")).unwrap(), b"secret a");
        assert_eq!(std::fs::read(outdir.join("b.txt")).unwrap(), b"new b");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// Editing one entry of an AES-encrypted archive must keep the untouched
    /// AES entries encryptable — `raw_copy_file_preserve_encryption` preserves
    /// the encrypted flag + AES extra field (raw_copy_file would drop them,
    /// leaving ciphertext mislabeled as plaintext → corrupt on re-extract).
    #[test]
    fn modify_aes_archive_preserves_untouched_entries() {
        let dir = tmp("modaes");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("a.txt"), b"aes payload a").unwrap();
        std::fs::write(dir.join("b.txt"), b"aes payload b").unwrap();
        let arc = dir.join("in.zip");
        compress_zip_inner(dir.to_str().unwrap(), arc.to_str().unwrap(), 5, "pw").expect("compress");

        // Replace only b.txt with the archive password.
        let new_b = dir.join("nb.txt");
        std::fs::write(&new_b, b"new b").unwrap();
        let out = dir.join("out.zip");
        let ops = format!("replace|b.txt|{}", new_b.display());
        zip_modify(arc.to_str().unwrap(), out.to_str().unwrap(), &ops, "pw").expect("modify aes");

        // The untouched a.txt must still decrypt with the ORIGINAL password and
        // keep its exact bytes.
        let outdir = dir.join("x");
        std::fs::create_dir_all(&outdir).unwrap();
        let r = extract_zip_with_password(out.to_str().unwrap(), outdir.to_str().unwrap(), "pw");
        assert!(r.is_ok(), "extract aes after modify: {r:?}");
        assert_eq!(std::fs::read(outdir.join("a.txt")).unwrap(), b"aes payload a");
        assert_eq!(std::fs::read(outdir.join("b.txt")).unwrap(), b"new b");
        // Without the password, extracting must fail (the entry stayed encrypted).
        let outdir2 = dir.join("y");
        std::fs::create_dir_all(&outdir2).unwrap();
        let r2 = extract_zip_with_password(out.to_str().unwrap(), outdir2.to_str().unwrap(), "");
        assert!(r2.is_err(), "AES entry must still require the password: {r2:?}");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// Editing a zip that contains a legacy ZipCrypto-encrypted entry must be
    /// REFUSED, not silently corrupt the entry. `raw_copy_file` on a non-AES
    /// encrypted entry would drop the encrypted flag while keeping the cipher
    /// bytes — the result extracts as garbage.
    #[test]
    fn modify_legacy_zipcrypto_archive_rejected() {
        // Hand-craft a minimal stored zip whose entry sets the encrypted flag
        // (bit 0 of general-purpose flags) WITHOUT an AES extra field — this is
        // the legacy ZipCrypto case.
        let name = b"a.txt";
        let data = b"zipcrypto payload";
        // Local file header with flags bit0 = 1.
        let mut z = Vec::new();
        z.extend_from_slice(b"PK\x03\x04");
        z.extend_from_slice(&20u16.to_le_bytes()); // version
        z.extend_from_slice(&1u16.to_le_bytes()); // flags: encrypted
        z.extend_from_slice(&0u16.to_le_bytes()); // method = stored
        z.extend_from_slice(&0u16.to_le_bytes()); // time
        z.extend_from_slice(&0u16.to_le_bytes()); // date
        z.extend_from_slice(&0u32.to_le_bytes()); // crc
        z.extend_from_slice(&(data.len() as u32).to_le_bytes()); // csize
        z.extend_from_slice(&(data.len() as u32).to_le_bytes()); // usize
        z.extend_from_slice(&(name.len() as u16).to_le_bytes());
        z.extend_from_slice(&0u16.to_le_bytes()); // extra len
        z.extend_from_slice(name);
        z.extend_from_slice(data);
        let cd_off = z.len() as u32;
        // Central directory header with flags bit0 = 1.
        z.extend_from_slice(b"PK\x01\x02");
        z.extend_from_slice(&20u16.to_le_bytes()); // version made by
        z.extend_from_slice(&20u16.to_le_bytes()); // version needed
        z.extend_from_slice(&1u16.to_le_bytes()); // flags: encrypted
        z.extend_from_slice(&0u16.to_le_bytes()); // method
        z.extend_from_slice(&0u16.to_le_bytes()); // time
        z.extend_from_slice(&0u16.to_le_bytes()); // date
        z.extend_from_slice(&0u32.to_le_bytes()); // crc
        z.extend_from_slice(&(data.len() as u32).to_le_bytes()); // csize
        z.extend_from_slice(&(data.len() as u32).to_le_bytes()); // usize
        z.extend_from_slice(&(name.len() as u16).to_le_bytes());
        z.extend_from_slice(&0u16.to_le_bytes()); // extra len
        z.extend_from_slice(&0u16.to_le_bytes()); // comment len
        z.extend_from_slice(&0u16.to_le_bytes()); // disk start
        z.extend_from_slice(&0u16.to_le_bytes()); // internal attrs
        z.extend_from_slice(&0u32.to_le_bytes()); // external attrs
        z.extend_from_slice(&0u32.to_le_bytes()); // local header offset
        z.extend_from_slice(name);
        let cd_size = (z.len() as u32) - cd_off;
        z.extend_from_slice(b"PK\x05\x06");
        z.extend_from_slice(&0u16.to_le_bytes());
        z.extend_from_slice(&0u16.to_le_bytes());
        z.extend_from_slice(&1u16.to_le_bytes());
        z.extend_from_slice(&1u16.to_le_bytes());
        z.extend_from_slice(&cd_size.to_le_bytes());
        z.extend_from_slice(&cd_off.to_le_bytes());
        z.extend_from_slice(&0u16.to_le_bytes());

        let dir = tmp("zipcrypto");
        std::fs::create_dir_all(&dir).unwrap();
        let arc = dir.join("in.zip");
        std::fs::write(&arc, &z).unwrap();

        // Replacing b.txt (not in the archive) triggers the untouched-copy path
        // for a.txt — which must now fail with the ZipCrypto refusal.
        let src = dir.join("nb.txt");
        std::fs::write(&src, b"x").unwrap();
        let out = dir.join("out.zip");
        let ops = format!("add|b.txt|{}", src.display());
        let r = zip_modify(arc.to_str().unwrap(), out.to_str().unwrap(), &ops, "");
        assert!(r.is_err(), "legacy ZipCrypto archive must not be silently corrupted, got {r:?}");
        let msg = r.unwrap_err();
        assert!(msg.contains("ZipCrypto"), "msg must name ZipCrypto: {msg}");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// A replace/delete targeting a path that isn't in the archive must error,
    /// not silently succeed while changing nothing.
    #[test]
    fn modify_missing_path_errors() {
        let dir = tmp("missing");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("a.txt"), b"present").unwrap();
        let arc = dir.join("in.zip");
        compress_zip_inner(dir.to_str().unwrap(), arc.to_str().unwrap(), 5, "").expect("compress");

        let src = dir.join("s.txt");
        std::fs::write(&src, b"replacement").unwrap();
        let out = dir.join("out.zip");
        let ops = format!("replace|ghost.txt|{}", src.display());
        let r = zip_modify(arc.to_str().unwrap(), out.to_str().unwrap(), &ops, "");
        assert!(r.is_err(), "replace of a missing path must fail, got {r:?}");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// Deleting a directory prefix cascades to every entry underneath it.
    #[test]
    fn modify_delete_directory_cascades() {
        let dir = tmp("deldir");
        std::fs::create_dir_all(dir.join("src/sub")).unwrap();
        std::fs::write(dir.join("src/root.txt"), b"keep root").unwrap();
        std::fs::write(dir.join("src/sub/a.txt"), b"delete a").unwrap();
        std::fs::write(dir.join("src/sub/b.txt"), b"delete b").unwrap();
        let arc = dir.join("in.zip");
        compress_zip_inner(dir.join("src").to_str().unwrap(), arc.to_str().unwrap(), 5, "").expect("compress");

        let out = dir.join("out.zip");
        zip_modify(arc.to_str().unwrap(), out.to_str().unwrap(), "delete|sub", "").expect("delete dir");

        let outdir = dir.join("x");
        std::fs::create_dir_all(&outdir).unwrap();
        let r = extract_zip_all_inner(out.to_str().unwrap(), outdir.to_str().unwrap());
        assert!(r.is_ok(), "extract after dir delete: {r:?}");
        assert_eq!(std::fs::read(outdir.join("root.txt")).unwrap(), b"keep root");
        assert!(!outdir.join("sub").exists(), "sub dir must be deleted");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// A replace targeting a nested path (`sub/a.txt`) replaces only that
    /// entry — the directory prefix match must not swallow siblings.
    #[test]
    fn modify_replace_nested_entry_exact() {
        let dir = tmp("replnested");
        std::fs::create_dir_all(dir.join("src/sub")).unwrap();
        std::fs::write(dir.join("src/sub/a.txt"), b"old a").unwrap();
        std::fs::write(dir.join("src/sub/b.txt"), b"keep b").unwrap();
        let arc = dir.join("in.zip");
        compress_zip_inner(dir.join("src").to_str().unwrap(), arc.to_str().unwrap(), 5, "").expect("compress");

        let new_a = dir.join("new_a.txt");
        std::fs::write(&new_a, b"new a").unwrap();
        let out = dir.join("out.zip");
        let ops = format!("replace|sub/a.txt|{}", new_a.display());
        zip_modify(arc.to_str().unwrap(), out.to_str().unwrap(), &ops, "").expect("replace nested");

        let outdir = dir.join("x");
        std::fs::create_dir_all(&outdir).unwrap();
        let r = extract_zip_all_inner(out.to_str().unwrap(), outdir.to_str().unwrap());
        assert!(r.is_ok(), "extract after nested replace: {r:?}");
        assert_eq!(std::fs::read(outdir.join("sub/a.txt")).unwrap(), b"new a");
        assert_eq!(std::fs::read(outdir.join("sub/b.txt")).unwrap(), b"keep b");
        std::fs::remove_dir_all(&dir).ok();
    }

    // ── PKWARE true-split (.z01/.z02/.zip) ──

    fn local_hdr(name: &str, data: &[u8]) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend_from_slice(&0x04034b50u32.to_le_bytes());
        v.extend_from_slice(&20u16.to_le_bytes()); // version
        v.extend_from_slice(&0u16.to_le_bytes()); // flags
        v.extend_from_slice(&0u16.to_le_bytes()); // method (store)
        v.extend_from_slice(&0u16.to_le_bytes()); // time
        v.extend_from_slice(&0u16.to_le_bytes()); // date
        v.extend_from_slice(&0u32.to_le_bytes()); // crc
        v.extend_from_slice(&(data.len() as u32).to_le_bytes()); // csize
        v.extend_from_slice(&(data.len() as u32).to_le_bytes()); // usize
        v.extend_from_slice(&(name.len() as u16).to_le_bytes());
        v.extend_from_slice(&0u16.to_le_bytes());
        v.extend_from_slice(name.as_bytes());
        v.extend_from_slice(data);
        v
    }

    fn central_hdr(name: &str, offset: u32, size: u32, disk: u16) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend_from_slice(&0x02014b50u32.to_le_bytes());
        v.extend_from_slice(&20u16.to_le_bytes()); // version made by
        v.extend_from_slice(&20u16.to_le_bytes()); // version needed
        v.extend_from_slice(&0u16.to_le_bytes()); // flags
        v.extend_from_slice(&0u16.to_le_bytes()); // method
        v.extend_from_slice(&0u16.to_le_bytes()); // time
        v.extend_from_slice(&0u16.to_le_bytes()); // date
        v.extend_from_slice(&0u32.to_le_bytes()); // crc
        v.extend_from_slice(&size.to_le_bytes()); // csize
        v.extend_from_slice(&size.to_le_bytes()); // usize
        v.extend_from_slice(&(name.len() as u16).to_le_bytes());
        v.extend_from_slice(&0u16.to_le_bytes()); // extra len
        v.extend_from_slice(&0u16.to_le_bytes()); // comment len
        v.extend_from_slice(&disk.to_le_bytes()); // disk number start
        v.extend_from_slice(&0u16.to_le_bytes()); // internal attrs
        v.extend_from_slice(&0u32.to_le_bytes()); // external attrs
        v.extend_from_slice(&offset.to_le_bytes()); // local header offset
        v.extend_from_slice(name.as_bytes());
        v
    }

    /// Builds a PKWARE true-split set:
    ///   test.z01  disk 0: file "a.txt" (local header + data + fake EOCD)
    ///   test.z02  disk 1: file "b.txt" (local header + data)
    ///   test.zip  disk 2 (final): file "c.txt" + central dir + real EOCD
    /// Each file lives wholly on one disk.
    fn make_pkware_split_zip(dir: &std::path::Path) -> Vec<std::path::PathBuf> {
        let a = b"hello from disk zero";
        let b = b"data on disk one";
        let c = b"final disk content";
        let lh_a = local_hdr("a.txt", a);
        let lh_b = local_hdr("b.txt", b);
        let lh_c = local_hdr("c.txt", c);

        // z01: a.txt local header + data + fake EOCD (0 entries, disk 0)
        let mut z01 = lh_a.clone();
        z01.extend_from_slice(&0x06054b50u32.to_le_bytes());
        z01.extend_from_slice(&[0u8; 18]); // disk=0, 0 entries, empty

        // z02: b.txt local header + data (no EOCD)
        let z02 = lh_b.clone();

        // zip: c.txt local header + data + central dir (3 entries) + real EOCD
        let mut cd = Vec::new();
        // offsets are relative to each entry's own disk
        cd.extend(central_hdr("a.txt", 0, a.len() as u32, 0));
        cd.extend(central_hdr("b.txt", 0, b.len() as u32, 1));
        cd.extend(central_hdr("c.txt", 0, c.len() as u32, 2));
        let mut zip_part = lh_c.clone();
        let cd_offset = zip_part.len() as u32;
        zip_part.extend_from_slice(&cd);
        zip_part.extend_from_slice(&0x06054b50u32.to_le_bytes());
        zip_part.extend_from_slice(&0u16.to_le_bytes()); // disk number = 0
        zip_part.extend_from_slice(&2u16.to_le_bytes()); // disk with central dir = 2
        zip_part.extend_from_slice(&3u16.to_le_bytes()); // entries on this disk
        zip_part.extend_from_slice(&3u16.to_le_bytes()); // total entries
        zip_part.extend_from_slice(&(cd.len() as u32).to_le_bytes());
        zip_part.extend_from_slice(&cd_offset.to_le_bytes());
        zip_part.extend_from_slice(&0u16.to_le_bytes()); // comment len

        let p1 = dir.join("test.z01");
        let p2 = dir.join("test.z02");
        let p3 = dir.join("test.zip");
        std::fs::write(&p1, &z01).unwrap();
        std::fs::write(&p2, &z02).unwrap();
        std::fs::write(&p3, &zip_part).unwrap();
        vec![p1, p2, p3]
    }

    #[test]
    fn pkware_split_list_entries() {
        let dir = tmp("pkw");
        std::fs::create_dir_all(&dir).unwrap();
        let vols = make_pkware_split_zip(&dir);
        let refs: Vec<&str> = vols.iter().map(|p| p.to_str().unwrap()).collect();
        let list = list_zip_volumes(&refs).expect("list pkware split");
        assert!(list.contains("a.txt"), "list: {list}");
        assert!(list.contains("b.txt"), "list: {list}");
        assert!(list.contains("c.txt"), "list: {list}");
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn pkware_split_extract_round_trip() {
        let dir = tmp("pkwext");
        std::fs::create_dir_all(&dir).unwrap();
        let vols = make_pkware_split_zip(&dir);
        let refs: Vec<&str> = vols.iter().map(|p| p.to_str().unwrap()).collect();
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let (total, fail) = extract_zip_volumes(&refs, out.to_str().unwrap(), "", None).expect("extract pkware split");
        assert_eq!(fail, 0);
        assert_eq!(total, 3);
        assert_eq!(std::fs::read(out.join("a.txt")).unwrap(), b"hello from disk zero");
        assert_eq!(std::fs::read(out.join("b.txt")).unwrap(), b"data on disk one");
        assert_eq!(std::fs::read(out.join("c.txt")).unwrap(), b"final disk content");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// A PKWARE archive whose entry DATA straddles a disk boundary extracts
    /// correctly. Spanned archives are pure byte splits: disk 1 starts with the
    /// remainder of disk 0's last entry (no per-disk EOCD), so the concatenated
    /// stream is byte-contiguous and find_content reads through the seam.
    #[test]
    fn pkware_split_cross_disk_entry_extracts() {
        let dir = tmp("pkx");
        std::fs::create_dir_all(&dir).unwrap();
        // a.txt lives on disk 0 but declares 1000 bytes — disk 0 only holds
        // the local header + 100 bytes, the remaining 900 bytes continue at the
        // top of disk 1 (a real byte-split).
        let a: Vec<u8> = (0..1000u32).map(|i| (i % 251) as u8).collect();
        let lh_a = local_hdr("a.txt", &a);
        let mut z01 = lh_a.clone();
        z01.truncate(lh_a.len() - 900); // disk 0 cuts off 900 bytes of a's data

        // disk 1: the rest of a.txt (no EOCD, no padding) + b.txt
        let b = b"b data on the second disk";
        let mut z02 = Vec::new();
        z02.extend_from_slice(&a[100..]); // remainder of a.txt
        z02.extend(local_hdr("b.txt", b));

        // final disk: c.txt + central dir + real EOCD. Central-header offsets
        // are relative to each entry's own disk.
        let c = b"c data final disk";
        let mut cd = Vec::new();
        cd.extend(central_hdr("a.txt", 0, a.len() as u32, 0));
        // On disk 1, the first 900 bytes are a's continuation, so b's local
        // header sits at disk-relative offset 900.
        cd.extend(central_hdr("b.txt", 900, b.len() as u32, 1));
        cd.extend(central_hdr("c.txt", 0, c.len() as u32, 2));
        let mut zip_part = local_hdr("c.txt", c);
        let cd_offset = zip_part.len() as u32;
        zip_part.extend_from_slice(&cd);
        zip_part.extend_from_slice(&0x06054b50u32.to_le_bytes());
        zip_part.extend_from_slice(&0u16.to_le_bytes());
        zip_part.extend_from_slice(&2u16.to_le_bytes());
        zip_part.extend_from_slice(&3u16.to_le_bytes());
        zip_part.extend_from_slice(&3u16.to_le_bytes());
        zip_part.extend_from_slice(&(cd.len() as u32).to_le_bytes());
        zip_part.extend_from_slice(&cd_offset.to_le_bytes());
        zip_part.extend_from_slice(&0u16.to_le_bytes());

        let p1 = dir.join("test.z01");
        let p2 = dir.join("test.z02");
        let p3 = dir.join("test.zip");
        std::fs::write(&p1, &z01).unwrap();
        std::fs::write(&p2, &z02).unwrap();
        std::fs::write(&p3, &zip_part).unwrap();

        let paths = vec![p1, p2, p3];
        let refs: Vec<&str> = paths.iter().map(|p| p.to_str().unwrap()).collect();
        let list = list_zip_volumes(&refs).expect("list cross-disk");
        assert!(list.contains("a.txt") && list.contains("b.txt") && list.contains("c.txt"), "list: {list}");
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let (total, fail) = extract_zip_volumes(&refs, out.to_str().unwrap(), "", None).expect("extract cross-disk");
        assert_eq!(fail, 0, "cross-disk extract must not fail");
        assert_eq!(total, 3);
        assert_eq!(std::fs::read(out.join("a.txt")).unwrap(), a, "cross-disk entry data must match");
        assert_eq!(std::fs::read(out.join("b.txt")).unwrap(), b);
        assert_eq!(std::fs::read(out.join("c.txt")).unwrap(), c);
        std::fs::remove_dir_all(&dir).ok();
    }

    /// A cross-disk archive whose disk 1 is missing its continuation bytes
    /// must FAIL the affected entry (short read) instead of silently writing a
    /// truncated file — io::copy returns Ok(fewer bytes) on EOF, so the copy
    /// must be size-checked.
    #[test]
    fn pkware_split_short_read_fails_entry() {
        let dir = tmp("pkxshort");
        std::fs::create_dir_all(&dir).unwrap();
        let a: Vec<u8> = (0..1000u32).map(|i| (i % 251) as u8).collect();
        let lh_a = local_hdr("a.txt", &a);
        let mut z01 = lh_a.clone();
        z01.truncate(lh_a.len() - 900); // disk 0 holds only 100 bytes of a's data

        // disk 1 is BROKEN: it should carry a[100..] (900 bytes) but only has
        // half of it — the rest is missing entirely.
        let b = b"b data";
        let mut z02 = Vec::new();
        z02.extend_from_slice(&a[100..550]); // truncated continuation (450B)
        z02.extend(local_hdr("b.txt", b));

        let c = b"c data";
        let mut cd = Vec::new();
        cd.extend(central_hdr("a.txt", 0, a.len() as u32, 0));
        cd.extend(central_hdr("b.txt", 450, b.len() as u32, 1));
        cd.extend(central_hdr("c.txt", 0, c.len() as u32, 2));
        let mut zip_part = local_hdr("c.txt", c);
        let cd_offset = zip_part.len() as u32;
        zip_part.extend_from_slice(&cd);
        zip_part.extend_from_slice(&0x06054b50u32.to_le_bytes());
        zip_part.extend_from_slice(&0u16.to_le_bytes());
        zip_part.extend_from_slice(&2u16.to_le_bytes());
        zip_part.extend_from_slice(&3u16.to_le_bytes());
        zip_part.extend_from_slice(&3u16.to_le_bytes());
        zip_part.extend_from_slice(&(cd.len() as u32).to_le_bytes());
        zip_part.extend_from_slice(&cd_offset.to_le_bytes());
        zip_part.extend_from_slice(&0u16.to_le_bytes());

        let p1 = dir.join("test.z01");
        let p2 = dir.join("test.z02");
        let p3 = dir.join("test.zip");
        std::fs::write(&p1, &z01).unwrap();
        std::fs::write(&p2, &z02).unwrap();
        std::fs::write(&p3, &zip_part).unwrap();

        let paths = vec![p1, p2, p3];
        let refs: Vec<&str> = paths.iter().map(|p| p.to_str().unwrap()).collect();
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let (total, fail) = extract_zip_volumes(&refs, out.to_str().unwrap(), "", None).expect("extract");
        // a.txt must fail (short read) and leave NO partial file; b/c succeed.
        assert_eq!(fail, 1, "short-read entry must fail: total={total} fail={fail}");
        assert!(!out.join("a.txt").exists(), "no partial a.txt may be left");
        assert_eq!(std::fs::read(out.join("b.txt")).unwrap(), b);
        assert_eq!(std::fs::read(out.join("c.txt")).unwrap(), c);
        std::fs::remove_dir_all(&dir).ok();
    }

    /// zip_modify must reject PKWARE multi-disk archives up front — otherwise
    /// ZipArchive::new would parse 0 entries and a modify would drop every
    /// existing entry.
    #[test]
    fn pkware_split_modify_rejected() {
        let dir = tmp("pkwmod");
        std::fs::create_dir_all(&dir).unwrap();
        let vols = make_pkware_split_zip(&dir);
        // The final .zip part is the multi-disk archive's last disk.
        let final_zip = vols.iter().find(|p| p.extension().map(|e| e == "zip").unwrap_or(false)).unwrap();
        let src = dir.join("s.txt");
        std::fs::write(&src, b"x").unwrap();
        let out = dir.join("out.zip");
        let ops = format!("add|new.txt|{}", src.display());
        let r = zip_modify(final_zip.to_str().unwrap(), out.to_str().unwrap(), &ops, "");
        assert!(r.is_err(), "multi-disk zip must not be modifiable, got {r:?}");
        assert!(r.unwrap_err().contains("multi-disk"), "msg must mention multi-disk");
        std::fs::remove_dir_all(&dir).ok();
    }
}
