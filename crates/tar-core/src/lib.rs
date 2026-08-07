use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, jlong, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, derive_dirs, safe_join, extract_result_json, ProgressWriter, ProgressReader};
use archive_common::{extract_progress, compress_progress};
use std::collections::HashSet;
use std::fs::{self, File};
use std::io::{self, BufReader, Read, Write};
use std::path::PathBuf;

/// Streaming reader over oxiarc-bzip2's block API (used by .tar.bz2/.tbz2).
struct BzStream<R: Read> {
    decoder: oxiarc_bzip2::BzDecoder<R>,
    buf: Vec<u8>,
    pos: usize,
}

impl<R: Read> BzStream<R> {
    fn new(reader: R) -> Result<Self, String> {
        Ok(Self {
            decoder: oxiarc_bzip2::BzDecoder::new(reader).map_err(|e| format!("bzip2: {e}"))?,
            buf: Vec::new(),
            pos: 0,
        })
    }
}

impl<R: Read> Read for BzStream<R> {
    fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
        loop {
            if extract_progress::cancelled() {
                return Err(io::Error::new(io::ErrorKind::Interrupted, "cancelled"));
            }
            if self.pos < self.buf.len() {
                let n = out.len().min(self.buf.len() - self.pos);
                out[..n].copy_from_slice(&self.buf[self.pos..self.pos + n]);
                self.pos += n;
                return Ok(n);
            }
            match self.decoder.read_block() {
                Ok(Some(block)) => { self.buf = block; self.pos = 0; }
                Ok(None) => return Ok(0),
                Err(e) => return Err(io::Error::other(format!("bzip2: {e}"))),
            }
        }
    }
}

/// Streaming writer over oxiarc-bzip2's block API (used by .tar.bz2/.tbz2).
struct BzWriter<W: Write>(oxiarc_bzip2::BzEncoder<W>);

impl<W: Write> Write for BzWriter<W> {
    fn write(&mut self, data: &[u8]) -> io::Result<usize> {
        self.0.write_block(data).map_err(|e| io::Error::other(format!("bzip2: {e}")))?;
        Ok(data.len())
    }
    fn flush(&mut self) -> io::Result<()> { Ok(()) }
}

/// Derives the tar compression variant from the input name.
fn tar_fmt(input: &str) -> &'static str {
    let lower = input.to_lowercase();
    if lower.ends_with(".txz") || lower.ends_with(".tar.xz") { "txz" }
    else if lower.ends_with(".tbz2") || lower.ends_with(".tar.bz2") { "tbz2" }
    else if lower.ends_with(".tgz") || lower.ends_with(".tar.gz") { "tgz" }
    else if lower.ends_with(".tzst") || lower.ends_with(".tar.zst") { "tzst" }
    else { "tar" }
}

/// Builds a `Read` over the tar member bytes. `.txz` is spooled to a temp tar
/// (lzma-rs is push-style, not a `Read`); the temp path is removed by the
/// caller.
fn tar_reader(input: &str) -> Result<(Box<dyn Read>, Option<std::path::PathBuf>), String> {
    let file = File::open(input).map_err(|e| format!("tar: {e}"))?;
    match tar_fmt(input) {
        "tar" => Ok((Box::new(file), None)),
        "tgz" => Ok((Box::new(flate2::read::GzDecoder::new(file)), None)),
        "tbz2" => Ok((Box::new(BzStream::new(BufReader::new(file))?), None)),
        "tzst" => Ok((Box::new(oxiarc_zstd::ZstdStreamDecoder::new(file)), None)),
        "txz" => {
            let tmp = std::env::temp_dir().join(format!("uu_txz_{}.tar", std::process::id()));
            let mut r = BufReader::new(file);
            let mut w = File::create(&tmp).map_err(|e| format!("{e}"))?;
            lzma_rs::xz_decompress(&mut r, &mut w).map_err(|e| format!("txz: {e}"))?;
            let back = File::open(&tmp).map_err(|e| format!("{e}"))?;
            Ok((Box::new(back), Some(tmp)))
        }
        _ => Err("unsupported tar format".into()),
    }
}

fn list_tar(input: &str) -> Result<String, String> {
    let (reader, tmp) = tar_reader(input)?;
    let res = (|| -> Result<String, String> {
        let mut ar = tar::Archive::new(reader);
        let mut all: Vec<(String, u64, bool)> = Vec::new();
        for entry in ar.entries().map_err(|e| format!("tar: {e}"))? {
            let e = entry.map_err(|e| format!("tar: {e}"))?;
            let path = e.path().map_err(|e| format!("tar: {e}"))?.to_string_lossy().replace('\\', "/");
            if path.is_empty() { continue; }
            let is_dir = e.header().entry_type().is_dir() || path.ends_with('/');
            all.push((path, e.size(), is_dir));
        }
        let names: Vec<&str> = all.iter().map(|(n, _, _)| n.as_str()).collect();
        let dirs = derive_dirs(&names);
        let mut merged: Vec<(String, u64, bool)> = Vec::new();
        for d in &dirs { merged.push((d.clone(), 0, true)); }
        merged.extend(all.iter().filter(|(_, _, d)| !*d).map(|(n, s, _)| (n.clone(), *s, false)));
        merged.sort_by(|a, b| a.0.cmp(&b.0));
        merged.dedup_by(|a, b| a.0 == b.0);
        let items: Vec<String> = merged.iter().map(|(n, s, d)| {
            format!(r#"{{"n":"{}","s":{},"d":{},"e":false}}"#, json_escape(n), *s, *d)
        }).collect();
        Ok(format!("[{}]", items.join(",")))
    })();
    if let Some(p) = tmp { let _ = fs::remove_file(p); }
    res
}

fn extract_tar(input: &str, output: &str, selected: Option<&HashSet<String>>) -> Result<(u32, u32), String> {
    // Pass 1: collect file entries so the progress total can be computed
    // (tar was the only format that never reset the progress store).
    let (reader1, tmp1) = tar_reader(input)?;
    let mut files: Vec<(String, u64)> = Vec::new();
    {
        let mut ar = tar::Archive::new(reader1);
        for entry in ar.entries().map_err(|e| format!("tar: {e}"))? {
            let mut e = entry.map_err(|e| format!("tar: {e}"))?;
            let path = e.path().map_err(|e| format!("tar: {e}"))?.to_string_lossy().replace('\\', "/");
            if path.is_empty() { continue; }
            let entry_type = e.header().entry_type();
            let is_dir = entry_type.is_dir() || path.ends_with('/');
            if is_dir || !entry_type.is_file() { continue; }
            if let Some(sel) = selected {
                if !sel.contains(&path) && !sel.iter().any(|d| path.starts_with(&format!("{d}/"))) { continue; }
            }
            files.push((path, e.size()));
        }
    }
    if let Some(p) = tmp1 { let _ = fs::remove_file(p); }
    extract_progress::reset(files.iter().map(|(_, s)| *s).sum());

    // Pass 2: extract (re-open through the right decoder).
    let (reader2, tmp2) = tar_reader(input)?;
    let res = (|| -> Result<(u32, u32), String> {
        let mut ar = tar::Archive::new(reader2);
        let mut total = 0u32;
        let mut fail = 0u32;
        for entry in ar.entries().map_err(|e| format!("tar: {e}"))? {
            if extract_progress::cancelled() { return Err("cancelled".to_string()); }
            let mut e = entry.map_err(|e| format!("tar: {e}"))?;
            let path = e.path().map_err(|e| format!("tar: {e}"))?.to_string_lossy().replace('\\', "/");
            if !files.iter().any(|(p, _)| p == &path) { continue; }
            total += 1;
            extract_progress::set_name(&path);
            extract_progress::set_file(e.size());
            let dest = match safe_join(output, &path) {
                Ok(d) => d,
                Err(_) => { fail += 1; continue; }
            };
            if let Some(p) = dest.parent() { let _ = fs::create_dir_all(p); }
            let mut out = match File::create(&dest) {
                Ok(f) => ProgressWriter::extract(f),
                Err(_) => { fail += 1; continue; }
            };
            if io::copy(&mut e, &mut out).is_err() { fail += 1; }
        }
        Ok((total, fail))
    })();
    if let Some(p) = tmp2 { let _ = fs::remove_file(p); }
    res
}

fn guarded<T: Send + 'static>(f: impl FnOnce() -> Result<T, String> + Send + 'static) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|panic| {
        let msg = panic.downcast_ref::<&str>().copied()
            .or_else(|| panic.downcast_ref::<String>().map(|s| s.as_str()))
            .unwrap_or("unknown panic");
        Err(format!("panic: {msg}"))
    })
}

// ─── tar packing (compression) ───────────────────────────────────────

fn collect_files(base: &str, rel: &str, files: &mut Vec<(PathBuf, String)>) -> Result<(), String> {
    let dir = if rel.is_empty() { base.to_string() } else { format!("{base}/{rel}") };
    for entry in fs::read_dir(&dir).map_err(|e| format!("tar: {e}"))? {
        let entry = entry.map_err(|e| format!("tar: {e}"))?;
        let name = entry.file_name().to_string_lossy().to_string();
        let child_rel = if rel.is_empty() { name.clone() } else { format!("{rel}/{name}") };
        let ft = entry.file_type().map_err(|e| format!("tar: {e}"))?;
        if ft.is_dir() {
            collect_files(base, &child_rel, files)?;
        } else if ft.is_file() {
            files.push((entry.path(), child_rel));
        }
    }
    Ok(())
}

fn append_files(files: &[(PathBuf, String)], writer: &mut dyn Write) -> Result<u32, String> {
    let mut builder = tar::Builder::new(writer);
    let mut fail = 0u32;
    for (path, rel) in files {
        if compress_progress::cancelled() { return Err("cancelled".to_string()); }
        let size = path.metadata().map(|m| m.len()).unwrap_or(0);
        compress_progress::set_name(rel);
        compress_progress::set_file(size);
        let mut header = tar::Header::new_gnu();
        header.set_size(size);
        header.set_mode(0o644);
        // paths longer than the header name field (100 chars) abort append;
        // skip and count them instead of killing the whole archive
        if header.set_path(rel).is_err() {
            fail += 1;
            continue;
        }
        header.set_cksum();
        let mut f = match File::open(path) {
            Ok(f) => f,
            Err(_) => { fail += 1; continue; }
        };
        if builder.append_data(&mut header, rel, ProgressReader::compress(&mut f)).is_err() {
            fail += 1;
        }
    }
    builder.finish().map_err(|e| format!("tar: {e}"))?;
    Ok(fail)
}

fn compress_tar(input: &str, output: &str, fmt: &str, level: i32) -> Result<u32, String> {
    let mut files = Vec::new();
    collect_files(input, "", &mut files)?;
    let total: u64 = files.iter().map(|(p, _)| p.metadata().map(|m| m.len()).unwrap_or(0)).sum();
    compress_progress::reset(total);
    let mut fail = 0u32;
    match fmt {
        "tar" => {
            let mut f = File::create(output).map_err(|e| format!("{e}"))?;
            fail += append_files(&files, &mut f)?;
        }
        "tgz" => {
            let mut gz = flate2::write::GzEncoder::new(
                File::create(output).map_err(|e| format!("{e}"))?,
                flate2::Compression::new(level.clamp(0, 9) as u32),
            );
            fail += append_files(&files, &mut gz)?;
            gz.finish().map_err(|e| format!("tgz: {e}"))?;
        }
        "tbz2" => {
            let lvl = oxiarc_bzip2::CompressionLevel::new(level.clamp(1, 9) as u8);
            let enc = oxiarc_bzip2::BzEncoder::new(File::create(output).map_err(|e| format!("{e}"))?, lvl).map_err(|e| format!("bzip2: {e}"))?;
            let mut bz = BzWriter(enc);
            fail += append_files(&files, &mut bz)?;
            bz.0.finish().map_err(|e| format!("tbz2: {e}"))?;
        }
        "tzst" => {
            let level = if level < 1 { 3 } else { level.min(22) };
            let mut zs = oxiarc_zstd::ZstdStreamEncoder::new(File::create(output).map_err(|e| format!("{e}"))?, level);
            fail += append_files(&files, &mut zs)?;
            zs.finish().map_err(|e| format!("tzst: {e}"))?;
        }
        "txz" => {
            let tmp = std::env::temp_dir().join(format!("uu_txz_out_{}.tar", std::process::id()));
            let mut tf = File::create(&tmp).map_err(|e| format!("{e}"))?;
            fail += append_files(&files, &mut tf)?;
            let mut r = BufReader::new(File::open(&tmp).map_err(|e| format!("{e}"))?);
            let mut out = File::create(output).map_err(|e| format!("{e}"))?;
            lzma_rs::xz_compress(&mut r, &mut out).map_err(|e| format!("txz: {e}"))?;
            let _ = fs::remove_file(tmp);
        }
        _ => return Err(format!("unsupported tar output: {fmt}")),
    }
    if compress_progress::cancelled() { return Err("cancelled".to_string()); }
    if fail > 0 { return Err(format!("{fail} files skipped")); }
    Ok(0)
}

fn finish_jstring(env: &mut JNIEnv, result: Result<(u32, u32), String>) -> jstring {
    match result {
        Ok((total, error)) => {
            let json = extract_result_json(total, total - error, error);
            match env.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }
        }
        Err(er) => { let _ = env.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_TarCore_tarListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i);
    match guarded(move || list_tar(&inp)) {
        Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() },
        Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_TarCore_tarExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = fs::create_dir_all(&out);
    finish_jstring(&mut e, guarded(move || extract_tar(&inp, &out, None)))
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_TarCore_tarExtractSelected(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel: JString) -> jstring {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel);
    let ss: HashSet<String> = sel_str.lines().filter(|l| !l.is_empty()).map(|s| s.to_string()).collect();
    if ss.is_empty() { return finish_jstring(&mut e, Ok((0, 0))); }
    let _ = fs::create_dir_all(&out);
    finish_jstring(&mut e, guarded(move || extract_tar(&inp, &out, Some(&ss))))
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_TarCore_tarExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_TarCore_tarExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_TarCore_tarExtractProgressFileCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_TarCore_tarExtractProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_TarCore_tarExtractProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_TarCore_tarExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_TarCore_tarCompress(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, fmt: JString, lv: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let f = s(&mut e, &fmt); let lvl: i32 = s(&mut e, &lv).parse().unwrap_or(5);
    match guarded(move || compress_tar(&inp, &out, &f, lvl)) {
        Ok(0) => JNI_TRUE,
        Ok(_) => { let _ = e.throw_new("java/io/IOException", "tar: compress failed"); JNI_FALSE }
        Err(er) => { let _ = e.throw_new("java/io/IOException", format!("tar: {er}")); JNI_FALSE }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_TarCore_tarCompressProgressCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_TarCore_tarCompressProgressTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_TarCore_tarCompressProgressFileCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_TarCore_tarCompressProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_TarCore_tarCompressProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&compress_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_TarCore_tarCompressCancel(_: JNIEnv, _: JClass) { compress_progress::cancel(); }

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;
    // The progress stores are process-wide; serialize tests that touch them.
    static LOCK: Mutex<()> = Mutex::new(());

    fn lock() -> std::sync::MutexGuard<'static, ()> { LOCK.lock().unwrap_or_else(|e| e.into_inner()) }

    fn tmp(tag: &str) -> std::path::PathBuf {
        std::env::temp_dir().join(format!("uu_tar_{}_{}", std::process::id(), tag))
    }

    /// Builds a raw tar with arbitrary entry names/typeflags (tar::Builder's
    /// set_path refuses `..`, so we craft bytes directly for security tests).
    fn raw_tar(entries: &[(&str, u8, &[u8])]) -> Vec<u8> {
        let mut out = Vec::new();
        for (name, typeflag, data) in entries {
            let mut h = [0u8; 512];
            let nb = name.as_bytes();
            h[..nb.len().min(100)].copy_from_slice(&nb[..nb.len().min(100)]);
            h[100..108].copy_from_slice(b"0000644\0");
            h[108..116].copy_from_slice(b"0000000\0");
            h[116..124].copy_from_slice(b"0000000\0");
            let size = data.len();
            h[124..136].copy_from_slice(format!("{:011o}\0", size).as_bytes());
            h[136..148].copy_from_slice(b"00000000000\0");
            h[148..156].copy_from_slice(b"        ");
            h[156] = *typeflag;
            h[257..263].copy_from_slice(b"ustar\0");
            let sum: u32 = h.iter().map(|&b| b as u32).sum();
            h[148..156].copy_from_slice(format!("{:06o}\0 ", sum).as_bytes());
            out.extend_from_slice(&h);
            out.extend_from_slice(data);
            let pad = (512 - data.len() % 512) % 512;
            out.extend(std::iter::repeat(0u8).take(pad));
        }
        out.extend_from_slice(&[0u8; 1024]);
        out
    }

    #[test]
    fn all_variants_round_trip() {
        let _g = lock();
        let root = tmp("rt");
        let dir = root.join("src");
        let arcs = root.join("arcs");
        std::fs::create_dir_all(dir.join("sub")).unwrap();
        std::fs::create_dir_all(&arcs).unwrap();
        let data: Vec<u8> = (0..50_000u32).map(|i| (i % 251) as u8).collect();
        std::fs::write(dir.join("a.txt"), &data).unwrap();
        std::fs::write(dir.join("sub/b.bin"), b"hello tar sub").unwrap();
        for (fmt, ext) in [("tar", ".tar"), ("tgz", ".tar.gz"), ("tbz2", ".tar.bz2"), ("txz", ".tar.xz"), ("tzst", ".tar.zst")] {
            let out = arcs.join(format!("x{ext}"));
            compress_tar(dir.to_str().unwrap(), out.to_str().unwrap(), fmt, 6).unwrap();

            let xdir = root.join(format!("out_{fmt}"));
            std::fs::create_dir_all(&xdir).unwrap();
            extract_tar(out.to_str().unwrap(), xdir.to_str().unwrap(), None).unwrap();
            assert_eq!(std::fs::read(xdir.join("a.txt")).unwrap(), data, "{fmt}");
            assert_eq!(std::fs::read(xdir.join("sub/b.bin")).unwrap(), b"hello tar sub", "{fmt}");
        }
        std::fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn path_traversal_rejected() {
        let _g = lock();
        let dir = tmp("trav");
        std::fs::create_dir_all(&dir).unwrap();
        let tar = dir.join("evil.tar");
        std::fs::write(&tar, raw_tar(&[("../evil.txt", 0x30, b"EVIL")])).unwrap();
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        // must not write outside output, and must not crash
        let _ = extract_tar(tar.to_str().unwrap(), out.to_str().unwrap(), None);
        assert!(!dir.join("evil.txt").exists(), "traversal wrote outside!");
        assert!(!out.join("../evil.txt").exists());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn symlink_entry_skipped() {
        let _g = lock();
        let dir = tmp("sym");
        std::fs::create_dir_all(&dir).unwrap();
        let tar = dir.join("sym.tar");
        std::fs::write(&tar, raw_tar(&[("link", 0x32, b"")])).unwrap();
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let r = extract_tar(tar.to_str().unwrap(), out.to_str().unwrap(), None).unwrap();
        assert_eq!(r.0, 0, "symlink should be skipped, not counted as a file");
        assert!(!out.join("link").exists(), "symlink was materialized");
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn extraction_resets_progress_total() {
        let _g = lock();
        let root = tmp("prog");
        let dir = root.join("src");
        std::fs::create_dir_all(&dir).unwrap();
        let data = vec![1u8; 3000];
        std::fs::write(dir.join("f.bin"), &data).unwrap();
        let tar = root.join("t.tar");
        compress_tar(dir.to_str().unwrap(), tar.to_str().unwrap(), "tar", 6).unwrap();
        let out = root.join("out");
        std::fs::create_dir_all(&out).unwrap();
        extract_progress::reset(1); // stale value on purpose
        extract_tar(tar.to_str().unwrap(), out.to_str().unwrap(), None).unwrap();
        assert_eq!(extract_progress::total_bytes(), 3000, "tar must reset progress total");
        assert_eq!(extract_progress::file_total(), 3000);
        std::fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn long_path_entry_skipped_not_abort() {
        let _g = lock();
        let root = tmp("long");
        let dir = root.join("src");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("ok.txt"), b"ok").unwrap();
        // 120-char filename → tar header name field (100) overflow
        let long = "x".repeat(120);
        std::fs::write(dir.join(&long), b"long data").unwrap();
        let tar = root.join("t.tar");
        // the long path is skipped and reported; the archive is still valid
        let _ = compress_tar(dir.to_str().unwrap(), tar.to_str().unwrap(), "tar", 6);
        let out = root.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let r = extract_tar(tar.to_str().unwrap(), out.to_str().unwrap(), None).unwrap();
        assert_eq!(r.0, 1, "only the short file should extract");
        assert_eq!(std::fs::read(out.join("ok.txt")).unwrap(), b"ok");
        std::fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn empty_tar_ok() {
        let _g = lock();
        let dir = tmp("empty");
        std::fs::create_dir_all(&dir).unwrap();
        let tar = dir.join("e.tar");
        std::fs::write(&tar, [0u8; 1024]).unwrap();
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let r = extract_tar(tar.to_str().unwrap(), out.to_str().unwrap(), None).unwrap();
        assert_eq!(r.0, 0);
        std::fs::remove_dir_all(&dir).ok();
    }
}
