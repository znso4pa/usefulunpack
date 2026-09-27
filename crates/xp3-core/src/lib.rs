use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jlong};
use archive_common::{s, SyncIo, oneshot_async, json_escape, derive_dirs, safe_join, extract_result_json, ProgressWriter, ProgressReader, DestAllocator};
use archive_common::{extract_progress, compress_progress};
use xp3::read::XP3Archive;
use xp3::header::XP3Version;
use xp3::write::XP3Writer;
use std::fs::{self, File};
use std::collections::HashSet;
use std::io::{BufReader, BufWriter};
use std::path::{Path, PathBuf};

// ─── XP3 (Kirikiri) ────────────────────────

/// Only small entries are buffered for the KSD-mode-2 filter probe — the filter
/// appears only on text/scripts (tiny), while images/audio are streamed.
const KSD_PROBE_MAX: u64 = 16 * 1024 * 1024;

/// Disk-fill guardrail: an entry may write at most declared size + 1 GiB.
/// krkr2 neither caps nor verifies the sum of decoded segment bytes against
/// INFO.size, and third-party repacks really do disagree (the extra bytes are
/// KEPT on disk — lenient extraction, see the extract loops) — but a hostile
/// entry inflating gigabytes past its declaration is cut off here instead of
/// filling the disk. Well-formed packs never come near the slack.
const ENTRY_WRITE_SLACK: u64 = 1024 * 1024 * 1024;

/// Copies one entry from the xp3 stream to disk. Small entries are buffered so
/// a Kirikiri KSD mode-2 filter (`FE FE 02 FF FE …`, used on text inside XP3)
/// can be unwrapped — the xp3 crate only decodes the outer zlib, which would
/// otherwise leave the scrambled wrapper as the file content. Returns true on
/// success. Every success path flushes explicitly: a `BufWriter` dropped on
/// failure would swallow a disk-full/EIO from its final <8 KiB, counting a
/// truncated file as extracted (same semantics zip-core already enforces).
/// Failure paths do NOT flush — the caller deletes the half-written file.
fn copy_xp3_entry<R: tokio::io::AsyncRead + Unpin>(
    mut xf: R,
    size: u64,
    out_stream: &mut SyncIo<ProgressWriter<BufWriter<File>>>,
) -> bool {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let cap = size.saturating_add(ENTRY_WRITE_SLACK);
    if size <= KSD_PROBE_MAX {
        // Buffer up to a hard cap so a crafted entry that inflates far beyond
        // its declared size can't grow the Vec unboundedly (zlib bomb → OOM).
        // The preallocation is capped too — the declared size is attacker-
        // controlled and a burst of inflated claims would churn the heap.
        let copied: Result<Vec<u8>, std::io::Error> = oneshot_async(async {
            let x = &mut xf; // borrow, not consume — we may stream the rest below
            let mut buf2 = Vec::with_capacity((size as usize).min(1024 * 1024));
            let limit = (KSD_PROBE_MAX + 1) as usize;
            let mut tmp = [0u8; 8192];
            loop {
                if buf2.len() >= limit { break; }
                let want = (limit - buf2.len()).min(tmp.len());
                let n = x.read(&mut tmp[..want]).await?;
                if n == 0 { break; }
                buf2.extend_from_slice(&tmp[..n]);
            }
            Ok(buf2)
        });
        match copied {
            Ok(b) if b.len() as u64 <= KSD_PROBE_MAX => {
                let payload = archive_ksd_core::ksd_mode2_decode(&b).unwrap_or(b);
                // Calibrate the per-file progress to the actual (KSD-unwrapped)
                // size — the wrapper's declared size was set before we knew.
                extract_progress::set_file(payload.len() as u64);
                oneshot_async(async {
                    out_stream.write_all(&payload).await?;
                    out_stream.flush().await
                }).is_ok()
            }
            Ok(b) => {
                // The stream is bigger than the probe window — write what we
                // already buffered verbatim (no KSD guess), then stream the
                // rest. Skipping straight to `io::copy` would drop the buffered
                // bytes (the reader has already consumed them).
                let rest = cap.saturating_sub(b.len() as u64);
                oneshot_async(async {
                    out_stream.write_all(&b).await?;
                    tokio::io::copy(&mut xf.take(rest), out_stream).await?;
                    out_stream.flush().await
                }).is_ok()
            }
            _ => {
                // Read error: stream the remainder (best-effort).
                oneshot_async(async {
                    tokio::io::copy(&mut xf.take(cap), out_stream).await?;
                    out_stream.flush().await
                }).is_ok()
            }
        }
    } else {
        oneshot_async(async {
            tokio::io::copy(&mut xf.take(cap), out_stream).await?;
            out_stream.flush().await
        }).is_ok()
    }
}

fn guarded<T: Send + 'static>(f: impl FnOnce() -> Result<T, String> + Send + 'static) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|panic| {
        let msg = panic.downcast_ref::<&str>().copied()
            .or_else(|| panic.downcast_ref::<String>().map(|s| s.as_str()))
            .unwrap_or("unknown panic");
        Err(format!("panic: {msg}"))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3Extract(
    mut env: JNIEnv, _class: JClass,
    _tool: JString, input: JString, output: JString,
) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut env, &input); let out = s(&mut env, &output);
    match guarded(move || extract_xp3(&inp, &out)) {
        Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match env.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = env.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}

fn extract_xp3(input: &str, output: &str) -> Result<(u32, u32), String> {
    let file = File::open(input).map_err(|e| format!("{e}"))?;
    let mut archive = oneshot_async(XP3Archive::open(SyncIo(BufReader::new(file))))
        .map_err(|e| format!("XP3: {e}"))?;
    let total = archive.entries().len() as u32;
    extract_progress::reset(archive.entries().iter().map(|e| e.size).sum());
    let mut fail = 0u32;
    // XP3 indexes can carry duplicate names, and /sdcard + FAT are
    // case-insensitive — allocate collision-free dests instead of letting
    // last-wins File::create silently destroy the earlier entry's data.
    let mut dests = DestAllocator::new();
    for i in 0..total as usize {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        let entry = &archive.entries()[i];
        extract_progress::set_name(&entry.name);
        extract_progress::set_file(entry.size);
        let size = entry.size;
        let dest = match safe_join(output, &entry.name) {
            Ok(d) => dests.allocate(d),
            Err(_) => { fail += 1; continue; }
        };
        if let Some(p) = dest.parent() { let _ = fs::create_dir_all(p); }
        let out_file = match File::create(&dest) {
            Ok(f) => f,
            Err(_) => { fail += 1; continue; }
        };
        let mut out_stream = SyncIo(ProgressWriter::extract(BufWriter::new(out_file)));
        let xf = match oneshot_async(archive.by_index(i)) {
            Some(Ok(f)) => f,
            _ => { let _ = fs::remove_file(&dest); fail += 1; continue; }
        };
        if !copy_xp3_entry(xf, size, &mut out_stream) {
            let _ = fs::remove_file(&dest);
            fail += 1;
            continue;
        }
        // Lenient extraction: keep what the segments actually decoded, even
        // when it disagrees with INFO.size (krkr2 does the same). Re-base the
        // progress so the bar still lands exactly on 100%.
        let written = extract_progress::file_bytes();
        if written != size {
            extract_progress::calibrate_file(written);
            let delta = (written as i128 - size as i128).clamp(i64::MIN as i128, i64::MAX as i128);
            extract_progress::adjust_total(delta as i64);
        }
    }
    Ok((total, fail))
}

fn list_xp3(input: &str) -> Result<String, String> {
    let file = File::open(input).map_err(|e| format!("{e}"))?;
    let archive = oneshot_async(XP3Archive::open(SyncIo(BufReader::new(file))))
        .map_err(|e| format!("XP3: {e}"))?;
    let raw_names: Vec<&str> = archive.entries().iter().map(|e| e.name.as_str()).collect();
    let normalized: Vec<String> = raw_names.iter().map(|n| n.replace('\\', "/")).collect();
    let norm_refs: Vec<&str> = normalized.iter().map(|s| s.as_str()).collect();
    let dirs = derive_dirs(&norm_refs);
    let mut all: Vec<(String, u64, bool)> = Vec::new();
    for d in &dirs { all.push((d.clone(), 0, true)); }
    for entry in archive.entries().iter() {
        all.push((entry.name.replace('\\', "/"), entry.size, false));
    }
    all.sort_by(|a, b| a.0.cmp(&b.0));
    let entries: Vec<String> = all.iter().map(|(n, s, d)| {
        let sz = if *d { 0_u64 } else { *s };
        format!(r#"{{"n":"{}","s":{},"d":{},"e":false}}"#, json_escape(n), sz, d)
    }).collect();
    Ok(format!("[{}]", entries.join(",")))
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3ListEntries(
    mut env: JNIEnv, _: JClass, input: JString,
) -> jstring {
    let inp = s(&mut env, &input);
    match guarded(move || list_xp3(&inp)) {
        Ok(j) => match env.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() },
        Err(e) => { let _ = env.throw_new("java/io/IOException", format!("listEntries: {e}")); std::ptr::null_mut() }
    }
}

// ─── XP3 Selective Extract ───

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3ExtractSelected(
    mut env: JNIEnv, _: JClass,
    _t: JString, input: JString, output: JString, selected: JString,
) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut env, &input); let out = s(&mut env, &output); let sel_str = s(&mut env, &selected);
    match guarded(move || extract_xp3_selected(&inp, &out, &sel_str)) {
        Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match env.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = env.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}

fn extract_xp3_selected(input: &str, output: &str, selected: &str) -> Result<(u32, u32), String> {
    let sel_set: HashSet<&str> = selected.lines().filter(|l| !l.is_empty()).collect();
    if sel_set.is_empty() { return Ok((0, 0)); }
    let file = File::open(input).map_err(|e| format!("{e}"))?;
    let mut archive = oneshot_async(XP3Archive::open(SyncIo(BufReader::new(file))))
        .map_err(|e| format!("XP3: {e}"))?;
    let matches = |raw_name: &str| {
        let norm_name = raw_name.replace('\\', "/");
        sel_set.contains(norm_name.as_str()) ||
            sel_set.iter().any(|d| { let dd = if d.ends_with('/') { &d[..d.len()-1] } else { d }; norm_name.starts_with(&format!("{dd}/")) })
    };
    extract_progress::reset(archive.entries().iter().filter(|e| matches(&e.name)).map(|e| e.size).sum());
    let mut sel = 0u32; let mut fail = 0u32;
    let mut dests = DestAllocator::new();
    for i in 0..archive.entries().len() {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        let raw_name = &archive.entries()[i].name;
        if !matches(raw_name) { continue; }
        sel += 1;
        extract_progress::set_name(raw_name);
        extract_progress::set_file(archive.entries()[i].size);
        let size = archive.entries()[i].size;
        let dest = match safe_join(output, raw_name) {
            Ok(d) => dests.allocate(d),
            Err(_) => { fail += 1; continue; }
        };
        if let Some(p) = dest.parent() { let _ = fs::create_dir_all(p); }
        let out_file = match File::create(&dest) {
            Ok(f) => f,
            Err(_) => { fail += 1; continue; }
        };
        let mut out_stream = SyncIo(ProgressWriter::extract(BufWriter::new(out_file)));
        let xf = match oneshot_async(archive.by_index(i)) {
            Some(Ok(f)) => f,
            _ => { let _ = fs::remove_file(&dest); fail += 1; continue; }
        };
        if !copy_xp3_entry(xf, size, &mut out_stream) {
            let _ = fs::remove_file(&dest);
            fail += 1;
            continue;
        }
        let written = extract_progress::file_bytes();
        if written != size {
            extract_progress::calibrate_file(written);
            let delta = (written as i128 - size as i128).clamp(i64::MIN as i128, i64::MAX as i128);
            extract_progress::adjust_total(delta as i64);
        }
    }
    Ok((sel, fail))
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3ExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3ExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3ExtractProgressFileCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3ExtractProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_total() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3ExtractProgressName(env: JNIEnv, _: JClass) -> jstring {
    env.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3ExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }

// ─── XP3 Pack (封包) ──────────────────────

/// Collects files under `base` (or the single file itself) with `/`-separated
/// archive paths. Iterative — no recursion, so deep trees can't overflow the stack.
fn collect_files_xp3(base: &Path) -> Result<Vec<(PathBuf, String)>, String> {
    let mut out = Vec::new();
    if base.is_file() {
        let name = base.file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
        out.push((base.to_path_buf(), name));
        return Ok(out);
    }
    let mut stack = vec![(base.to_path_buf(), String::new())];
    while let Some((dir, rel)) = stack.pop() {
        let mut entries: Vec<_> = fs::read_dir(&dir).map_err(|e| format!("read_dir {}: {e}", dir.display()))?
            .collect::<Result<_, _>>().map_err(|e| format!("read_dir {}: {e}", dir.display()))?;
        entries.sort_by_key(|e| e.file_name());
        for entry in entries {
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().to_string();
            let child_rel = if rel.is_empty() { name.clone() } else { format!("{rel}/{name}") };
            let meta = entry.metadata().map_err(|e| format!("metadata {}: {e}", path.display()))?;
            if meta.is_dir() {
                stack.push((path, child_rel));
            } else if meta.is_file() {
                out.push((path, child_rel));
            }
        }
    }
    out.sort_by(|a, b| a.1.cmp(&b.1));
    Ok(out)
}

fn create_xp3(input: &str, output: &str, level: i32) -> Result<u32, String> {
    let files = collect_files_xp3(Path::new(input))?;
    if files.is_empty() { return Err("XP3: no files to archive".to_string()); }
    let total: u64 = files.iter().map(|(p, _)| p.metadata().map(|m| m.len()).unwrap_or(0)).sum();
    compress_progress::reset(total);

    let out_file = File::create(output).map_err(|e| format!("XP3 create {output}: {e}"))?;
    let mut writer = oneshot_async(XP3Writer::new(
        XP3Version::Current { minor: 0 },
        SyncIo(BufWriter::new(out_file)),
    )).map_err(|e| format!("XP3: {e}"))?;

    let lvl = level.clamp(0, 9) as u8;
    let mut count = 0u32;
    for (src, name) in &files {
        if compress_progress::cancelled() { return Err("cancelled".to_string()); }
        let size = src.metadata().map(|m| m.len()).unwrap_or(0);
        compress_progress::set_name(name);
        compress_progress::set_file(size);
        // level 0 = raw store (no zlib wrapper), matching "store" semantics
        let compression: Option<u8> = if lvl == 0 { None } else { Some(lvl) };
        let mut fw = oneshot_async(writer.file(name.clone(), false, compression))
            .map_err(|e| format!("XP3 add {name}: {e}"))?;
        let src_file = File::open(src).map_err(|e| format!("XP3 open {}: {e}", src.display()))?;
        let mut reader = SyncIo(ProgressReader::compress(BufReader::new(src_file)));
        if oneshot_async(tokio::io::copy(&mut reader, &mut fw)).is_err() {
            return Err(format!("XP3 write {name}: io error"));
        }
        oneshot_async(fw.finish()).map_err(|e| format!("XP3 finish {name}: {e}"))?;
        count += 1;
    }
    oneshot_async(writer.finish(None)).map_err(|e| format!("XP3 finalize: {e}"))?;
    if compress_progress::cancelled() { return Err("cancelled".to_string()); }
    Ok(count)
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3CreateArchive(
    mut env: JNIEnv, _: JClass, _t: JString, input: JString, output: JString, level: JString,
) -> jstring {
    compress_progress::clear_cancel();
    let inp = s(&mut env, &input); let out = s(&mut env, &output);
    let lvl: i32 = s(&mut env, &level).parse().unwrap_or(5);
    match guarded(move || create_xp3(&inp, &out, lvl)) {
        Ok(total) => { let json = extract_result_json(total, total, 0); match env.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = env.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3CompressProgressCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3CompressProgressTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::total_bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3CompressProgressFileCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3CompressProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_total() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3CompressProgressName(env: JNIEnv, _: JClass) -> jstring {
    env.new_string(&compress_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_Xp3Core_xp3CompressCancel(_: JNIEnv, _: JClass) { compress_progress::cancel(); }

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> PathBuf { std::env::temp_dir().join(format!("uu_xp3_{}_{}", std::process::id(), tag)) }

    #[test]
    fn pack_round_trip_matches_bytes() {
        let dir = tmp("roundtrip");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::create_dir_all(dir.join("sub")).unwrap();
        std::fs::write(dir.join("a.txt"), b"hello xp3").unwrap();
        let big: Vec<u8> = (0..200_000u32).map(|i| (i % 251) as u8).collect();
        std::fs::write(dir.join("sub/b.bin"), &big).unwrap();
        let xp3 = dir.join("out.xp3");
        let out = dir.join("out");
        create_xp3(dir.to_str().unwrap(), xp3.to_str().unwrap(), 6).unwrap();
        std::fs::create_dir_all(&out).unwrap();
        extract_xp3(xp3.to_str().unwrap(), out.to_str().unwrap()).unwrap();
        assert_eq!(std::fs::read(out.join("a.txt")).unwrap(), b"hello xp3");
        assert_eq!(std::fs::read(out.join("sub/b.bin")).unwrap(), big);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn pack_single_file() {
        let dir = tmp("single");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("one.dat"), vec![9u8; 5000]).unwrap();
        let xp3 = dir.join("one.xp3");
        let out = dir.join("out");
        create_xp3(dir.join("one.dat").to_str().unwrap(), xp3.to_str().unwrap(), 0).unwrap();
        std::fs::create_dir_all(&out).unwrap();
        extract_xp3(xp3.to_str().unwrap(), out.to_str().unwrap()).unwrap();
        assert_eq!(std::fs::read(out.join("one.dat")).unwrap(), vec![9u8; 5000]);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn ksd_mode2_wrapped_entry_extracts_as_text() {
        // A real galgame XP3 stores some text entries as
        //   zlib( KSD mode-2 wrapper `FE FE 02 FF FE` + comp_len/uncomp_len + zlib(text) )
        // The xp3 crate only unwraps the OUTER zlib, so without the KSD unwrap
        // the extracted file would be the wrapper binary — "garbled in every
        // encoding". Build that wrapper, pack, extract, and require the real text.
        let text: Vec<u8> = "こんにちは\nテスト\n".encode_utf16()
            .flat_map(|u| u.to_le_bytes()).collect();
        let mut enc = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::new(6));
        std::io::Write::write_all(&mut enc, &text).unwrap();
        let inner = enc.finish().unwrap();
        let mut wrapper = vec![0xFE, 0xFE, 0x02, 0xFF, 0xFE];
        wrapper.extend_from_slice(&(inner.len() as i64).to_le_bytes());
        wrapper.extend_from_slice(&(text.len() as i64).to_le_bytes());
        wrapper.extend_from_slice(&inner);

        let dir = tmp("ksd");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("script.txt"), &wrapper).unwrap();
        let xp3 = dir.join("ksd.xp3");
        create_xp3(dir.to_str().unwrap(), xp3.to_str().unwrap(), 6).unwrap();
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        extract_xp3(xp3.to_str().unwrap(), out.to_str().unwrap()).unwrap();
        let got = std::fs::read(out.join("script.txt")).unwrap();
        assert_eq!(got, text, "KSD wrapper must be unwrapped to the original text");
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn duplicate_entry_names_are_renamed_not_overwritten() {
        // XP3 indexes may carry several entries under one name, and /sdcard +
        // FAT are case-insensitive: without the dedup pass the second entry's
        // File::create truncates the first (last-wins) while the result JSON
        // still reports success. Both entries must land on disk.
        let dir = tmp("dupnames");
        std::fs::create_dir_all(&dir).unwrap();
        let xp3 = dir.join("dup.xp3");
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();

        let out_file = std::fs::File::create(&xp3).unwrap();
        let mut writer = oneshot_async(XP3Writer::new(
            XP3Version::Current { minor: 0 },
            SyncIo(BufWriter::new(out_file)),
        )).unwrap();
        for (name, mut payload) in [("Readme.txt", &b"payload-one"[..]), ("readme.txt", &b"payload-two"[..])] {
            let mut fw = oneshot_async(writer.file(name.to_string(), false, Some(6))).unwrap();
            oneshot_async(tokio::io::copy(&mut payload, &mut fw)).unwrap();
            oneshot_async(fw.finish()).unwrap();
        }
        oneshot_async(writer.finish(None)).unwrap();

        let (_, error) = extract_xp3(xp3.to_str().unwrap(), out.to_str().unwrap()).unwrap();
        assert_eq!(error, 0);
        let mut names: Vec<String> = std::fs::read_dir(&out).unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        names.sort();
        assert_eq!(names.len(), 2, "both entries must exist, got {names:?}");
        // Case-folded uniqueness is what actually protects /sdcard and FAT.
        let folded: HashSet<String> = names.iter().map(|n| n.to_lowercase()).collect();
        assert_eq!(folded.len(), 2, "case-only collision must be renamed, got {names:?}");
        assert!(names.iter().any(|n| n.contains("(1)")), "renamed variant expected, got {names:?}");
        std::fs::remove_dir_all(&dir).ok();
    }
}
