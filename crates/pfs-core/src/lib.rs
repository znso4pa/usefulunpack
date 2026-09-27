use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jlong};
use archive_common::{s, json_escape, derive_dirs, safe_join, extract_result_json, DestAllocator};
use archive_common::{extract_progress, compress_progress};
use pf8::Pf8Archive;
use pf8::entry::Pf8Entry;
use pf8::writer::Pf8Writer;
use pf8::callbacks::{ArchiveHandler, ControlAction, ProgressInfo};
use std::fs;
use std::io::{Read, Write};
use std::collections::HashSet;
use std::path::{Path, PathBuf};

// ─── PFS (Artemis) ──────────────────────────

/// Feeds pf8's per-file byte progress into the shared extract store.
struct PfsProgress {
    base: u64,
    last: u64,
}

impl ArchiveHandler for PfsProgress {
    fn on_entry_started(&mut self, name: &str) -> ControlAction {
        extract_progress::set_name(name);
        ControlAction::Continue
    }
    fn on_progress(&mut self, info: &ProgressInfo) -> ControlAction {
        if extract_progress::cancelled() { return ControlAction::Abort; }
        let cur = self.base + info.processed_bytes;
        if cur > self.last {
            extract_progress::add_bytes(cur - self.last);
            self.last = cur;
        }
        ControlAction::Continue
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
pub extern "system" fn Java_com_usefulunpacker_PfsCore_pfsExtract(
    mut env: JNIEnv, _class: JClass,
    _tool: JString, input: JString, output: JString,
) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut env, &input); let out = s(&mut env, &output);
    match guarded(move || {
        let _ = fs::create_dir_all(&out);
        let mut archive = Pf8Archive::open(Path::new(&inp)).map_err(|e| format!("PFS: {e}"))?;
        let to_extract: Vec<(std::path::PathBuf, u64)> = archive.entries().map(|e| (e.path().to_path_buf(), e.size() as u64)).collect();
        let total = to_extract.len() as u32;
        extract_progress::reset(to_extract.iter().map(|(_, s)| *s).sum());
        let mut fail = 0u32;
        let mut base = 0u64;
        // Duplicate / case-only-colliding names would be last-wins truncation
        // on /sdcard and FAT — allocate distinct dests instead.
        let mut dests = DestAllocator::new();
        for (entry_path, _entry_size) in &to_extract {
            if extract_progress::cancelled() { return Err("cancelled".to_string()); }
            let entry_name = entry_path.to_string_lossy();
            extract_progress::set_file(*_entry_size);
            match safe_join(&out, &entry_name) {
                Ok(dest) => {
                    let dest = dests.allocate(dest);
                    if let Some(p) = dest.parent() { let _ = fs::create_dir_all(p); }
                    let mut handler = PfsProgress { base, last: base };
                    if archive.extract_file_with_progress(entry_path, &dest, &mut handler).is_err() {
                        let _ = fs::remove_file(&dest);
                        fail += 1;
                    }
                    base = handler.last;
                    if extract_progress::cancelled() { return Err("cancelled".to_string()); }
                }
                Err(_) => { fail += 1; }
            }
        }
        Ok((total, fail))
    }) {
        Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match env.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = env.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}

fn list_pfs(input: &str) -> Result<String, String> {
    let archive = Pf8Archive::open(Path::new(input)).map_err(|e| format!("PFS: {e}"))?;
    let entry_paths: Vec<String> = archive.entries().map(|e| e.path().to_string_lossy().replace('\\', "/")).collect();
    let path_refs: Vec<&str> = entry_paths.iter().map(|s| s.as_str()).collect();
    let dirs = derive_dirs(&path_refs);
    let mut all: Vec<(String, u64, bool, bool)> = Vec::new();
    for d in &dirs { all.push((d.clone(), 0, true, false)); }
    for entry in archive.entries() {
        let p = entry.path().to_string_lossy().replace('\\', "/");
        // PF8's built-in XOR encryption is derived from the index and decrypted
        // transparently — it is NOT a user password, so never flag entries as
        // password-protected (no 🔒 badge / password prompts for PFS).
        all.push((p, entry.size() as u64, false, false));
    }
    all.sort_by(|a, b| a.0.cmp(&b.0));
    let entries: Vec<String> = all.iter().map(|(n, s, d, e)| {
        let sz = if *d { 0_u64 } else { *s };
        format!(r#"{{"n":"{}","s":{},"d":{},"e":{}}}"#, json_escape(n), sz, d, e)
    }).collect();
    Ok(format!("[{}]", entries.join(",")))
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_PfsCore_pfsListEntries(
    mut env: JNIEnv, _: JClass, input: JString,
) -> jstring {
    let inp = s(&mut env, &input);
    match guarded(move || list_pfs(&inp)) {
        Ok(j) => match env.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() },
        Err(e) => { let _ = env.throw_new("java/io/IOException", format!("listEntries: {e}")); std::ptr::null_mut() }
    }
}

// ─── PFS Selective Extract ───

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_PfsCore_pfsExtractSelected(
    mut env: JNIEnv, _: JClass,
    _t: JString, input: JString, output: JString, selected: JString,
) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut env, &input); let out = s(&mut env, &output); let sel_str = s(&mut env, &selected);
    match guarded(move || extract_pfs_selected(&inp, &out, &sel_str)) {
        Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match env.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = env.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}

fn extract_pfs_selected(input: &str, output: &str, selected: &str) -> Result<(u32, u32), String> {
    let sel_set: HashSet<&str> = selected.lines().filter(|l| !l.is_empty()).collect();
    if sel_set.is_empty() { return Ok((0, 0)); }
    let _ = fs::create_dir_all(&output);
    let mut archive = Pf8Archive::open(Path::new(input)).map_err(|e| format!("PFS: {e}"))?;
    let to_extract: Vec<(std::path::PathBuf, u64)> = archive.entries()
        .map(|e| (e.path().to_path_buf(), e.size() as u64))
        .filter(|(p, _)| {
            let norm = p.to_string_lossy().replace('\\', "/");
            sel_set.contains(norm.as_str()) ||
                sel_set.iter().any(|sel_dir| {
                    let sd = if sel_dir.ends_with('/') { &sel_dir[..sel_dir.len()-1] } else { sel_dir };
                    norm.starts_with(&format!("{sd}/"))
                })
        }).collect();
    let total = to_extract.len() as u32;
    extract_progress::reset(to_extract.iter().map(|(_, s)| *s).sum());
    let mut fail = 0u32;
    let mut base = 0u64;
    let mut dests = DestAllocator::new();
    for (entry_path, _entry_size) in &to_extract {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        let entry_name = entry_path.to_string_lossy();
        extract_progress::set_file(*_entry_size);
        let dest = dests.allocate(safe_join(output, &entry_name).map_err(|e| format!("{e}"))?);
        if let Some(p) = dest.parent() { let _ = fs::create_dir_all(p); }
        let mut handler = PfsProgress { base, last: base };
        if archive.extract_file_with_progress(entry_path, &dest, &mut handler).is_err() {
            let _ = fs::remove_file(&dest);
            fail += 1;
        }
        base = handler.last;
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
    }
    Ok((total, fail))
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_PfsCore_pfsExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_PfsCore_pfsExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_PfsCore_pfsExtractProgressFileCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_PfsCore_pfsExtractProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_total() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_PfsCore_pfsExtractProgressName(env: JNIEnv, _: JClass) -> jstring {
    env.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_PfsCore_pfsExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }

// ─── PFS Pack (封包) ──────────────────────

/// Collects files under `base` (or the single file itself) with `/`-separated
/// archive paths. Iterative — no recursion.
fn collect_files_pfs(base: &Path) -> Result<Vec<(PathBuf, String)>, String> {
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

/// Packs into a PF8 archive (XOR-encrypted per-file, same as the pf8 builder).
/// Progress is per-file (the pf8 writer has no byte-level hook), matching the
/// app's per-file-jump progress mode.
fn create_pfs(input: &str, output: &str) -> Result<u32, String> {
    let files = collect_files_pfs(Path::new(input))?;
    if files.is_empty() { return Err("PFS: no files to archive".to_string()); }
    let total: u64 = files.iter().map(|(p, _)| p.metadata().map(|m| m.len()).unwrap_or(0)).sum();
    compress_progress::reset(total);

    let mut entries: Vec<(PathBuf, PathBuf, u64)> = Vec::new(); // (src, rel, size)
    for (src, rel) in &files {
        let size = src.metadata().map_err(|e| format!("PFS metadata {}: {e}", src.display()))?.len();
        if size > u32::MAX as u64 { return Err(format!("PFS file too large: {rel}")); }
        entries.push((src.clone(), PathBuf::from(rel), size));
    }
    entries.sort_by(|a, b| a.1.cmp(&b.1));

    let mut writer = Pf8Writer::create(Path::new(output)).map_err(|e| format!("PFS create {output}: {e}"))?;
    let mut pf8_entries: Vec<Pf8Entry> = Vec::new();
    let mut offset: u64 = 0;
    for (_, rel, size) in &entries {
        if offset + size > u32::MAX as u64 {
            return Err(format!("PFS archive too large: offset {} + {size} exceeds 4GB ({})", offset, rel.display()));
        }
        pf8_entries.push(Pf8Entry::new(rel, offset as u32, *size as u32));
        offset += *size;
    }
    let refs: Vec<&Pf8Entry> = pf8_entries.iter().collect();
    writer.write_header(&refs).map_err(|e| format!("PFS header: {e}"))?;

    for (i, (src, rel, size)) in entries.iter().enumerate() {
        if compress_progress::cancelled() { return Err("cancelled".to_string()); }
        compress_progress::set_name(&rel.to_string_lossy());
        compress_progress::set_file(*size);
        writer.write_file_data(&pf8_entries[i], src).map_err(|e| format!("PFS write {}: {e}", rel.display()))?;
        compress_progress::add_bytes(*size);
    }
    writer.finalize().map_err(|e| format!("PFS finalize: {e}"))?;
    if compress_progress::cancelled() { return Err("cancelled".to_string()); }
    Ok(entries.len() as u32)
}

// ─── PF6 Pack (独立实现：pf8 同构索引布局、pf6 魔数、数据不加密) ─────────────
//
// The pf8 crate has no PF6 pack support (read-only, unencrypted), so the
// writer below is self-contained. Layout mirrors Pf8Writer::write_header:
//   magic 'pf6' | index_size u32 | index_count u32
//   entries: name_len u32 | name (backslash path) | 0000 | offset u32 | size u32
//   filesize_count u32 | filesize_offsets u64[] | 8×00 | filesize_count_offset u32
// File data is stored raw — PF6 entries carry no SHA1-index XOR (the crate's
// reader marks every PF6 entry unencrypted, so plain bytes round-trip).

const PF6_MAGIC: &[u8] = b"pf6";
const PF6_INDEX_DATA_START: usize = 0x07;
const PF6_FILESIZE_OFFSETS_START: usize = 0x0F;

fn create_pf6(input: &str, output: &str) -> Result<u32, String> {
    let files = collect_files_pfs(Path::new(input))?;
    if files.is_empty() { return Err("PFS: no files to archive".to_string()); }
    let total: u64 = files.iter().map(|(p, _)| p.metadata().map(|m| m.len()).unwrap_or(0)).sum();
    compress_progress::reset(total);

    // pf8-style backslash paths, sorted for byte-stable output
    let mut entries: Vec<(String, PathBuf, u64)> = Vec::new();
    for (src, rel) in &files {
        let size = src.metadata().map_err(|e| format!("PF6 metadata {}: {e}", src.display()))?.len();
        if size > u32::MAX as u64 { return Err(format!("PF6 file too large: {rel}")); }
        let pf8_path = Path::new(rel).iter().map(|c| c.to_string_lossy()).collect::<Vec<_>>().join("\\");
        entries.push((pf8_path, src.clone(), size));
    }
    entries.sort_by(|a, b| a.0.cmp(&b.0));

    let count = entries.len() as u32;
    let fileentry_size: usize = entries.iter().map(|(p, _, _)| p.len() + 16).sum();
    let index_size = (4 + fileentry_size + 4 + (count as usize + 1) * 8 + 4) as u32;
    let mut header: Vec<u8> = Vec::with_capacity(PF6_INDEX_DATA_START + index_size as usize);
    header.extend_from_slice(PF6_MAGIC);
    header.extend_from_slice(&index_size.to_le_bytes());
    header.extend_from_slice(&count.to_le_bytes());

    let mut file_offset: u32 = index_size + PF6_INDEX_DATA_START as u32;
    let mut filesize_offsets: Vec<u64> = Vec::new();
    for (name, _, size) in &entries {
        let size = *size as u32;
        header.extend_from_slice(&(name.len() as u32).to_le_bytes());
        header.extend_from_slice(name.as_bytes());
        header.extend_from_slice(&[0x00; 4]);
        header.extend_from_slice(&file_offset.to_le_bytes());
        let size_pos = header.len();
        header.extend_from_slice(&size.to_le_bytes());
        filesize_offsets.push((size_pos - PF6_FILESIZE_OFFSETS_START) as u64);
        file_offset = file_offset.checked_add(size)
            .ok_or_else(|| format!("PF6 archive too large: data passes 4GB at {}", name))?;
    }
    header.extend_from_slice(&(count + 1).to_le_bytes());
    let filesize_count_offset = (header.len() - 4 - PF6_INDEX_DATA_START) as u32;
    for off in &filesize_offsets { header.extend_from_slice(&off.to_le_bytes()); }
    header.extend_from_slice(&[0x00; 8]);
    header.extend_from_slice(&filesize_count_offset.to_le_bytes());

    let mut out = fs::File::create(output).map_err(|e| format!("PF6 create {output}: {e}"))?;
    out.write_all(&header).map_err(|e| format!("PF6 write {output}: {e}"))?;

    // File data raw (no XOR), streamed 4MiB at a time with progress + cancel.
    let mut buf = vec![0u8; 4 * 1024 * 1024];
    for (name, src, size) in &entries {
        if compress_progress::cancelled() { return Err("cancelled".to_string()); }
        compress_progress::set_name(name);
        compress_progress::set_file(*size);
        let mut f = fs::File::open(src).map_err(|e| format!("PF6 open {}: {e}", src.display()))?;
        let mut left = *size;
        while left > 0 {
            if compress_progress::cancelled() { return Err("cancelled".to_string()); }
            let want = buf.len().min(left as usize);
            f.read_exact(&mut buf[..want]).map_err(|e| format!("PF6 read {}: {e}", src.display()))?;
            out.write_all(&buf[..want]).map_err(|e| format!("PF6 write {}: {e}", name))?;
            compress_progress::add_bytes(want as u64);
            left -= want as u64;
        }
    }
    if compress_progress::cancelled() { return Err("cancelled".to_string()); }
    Ok(entries.len() as u32)
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_PfsCore_pfsCreateArchivePf6(
    mut env: JNIEnv, _: JClass, _t: JString, input: JString, output: JString,
) -> jstring {
    compress_progress::clear_cancel();
    let inp = s(&mut env, &input); let out = s(&mut env, &output);
    match guarded(move || create_pf6(&inp, &out)) {
        Ok(total) => { let json = extract_result_json(total, total, 0); match env.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = env.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_PfsCore_pfsCreateArchive(
    mut env: JNIEnv, _: JClass, _t: JString, input: JString, output: JString,
) -> jstring {
    compress_progress::clear_cancel();
    let inp = s(&mut env, &input); let out = s(&mut env, &output);
    match guarded(move || create_pfs(&inp, &out)) {
        Ok(total) => { let json = extract_result_json(total, total, 0); match env.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = env.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_PfsCore_pfsCompressProgressCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_PfsCore_pfsCompressProgressTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::total_bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_PfsCore_pfsCompressProgressFileCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_PfsCore_pfsCompressProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_total() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_PfsCore_pfsCompressProgressName(env: JNIEnv, _: JClass) -> jstring {
    env.new_string(&compress_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_PfsCore_pfsCompressCancel(_: JNIEnv, _: JClass) { compress_progress::cancel(); }

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> PathBuf { std::env::temp_dir().join(format!("uu_pfs_{}_{}", std::process::id(), tag)) }

    #[test]
    fn pack_round_trip_matches_bytes() {
        let dir = tmp("roundtrip");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::create_dir_all(dir.join("sub")).unwrap();
        std::fs::write(dir.join("a.txt"), b"hello pfs").unwrap();
        let big: Vec<u8> = (0..300_000u32).map(|i| (i % 253) as u8).collect();
        std::fs::write(dir.join("sub/b.bin"), &big).unwrap();
        let pfs = dir.join("out.pfs");
        let out = dir.join("out");
        create_pfs(dir.to_str().unwrap(), pfs.to_str().unwrap()).unwrap();
        let mut archive = Pf8Archive::open(Path::new(&pfs)).map_err(|e| e.to_string()).unwrap();
        let paths: Vec<String> = archive.entries().map(|e| e.path().to_string_lossy().replace('\\', "/")).collect();
        assert!(paths.contains(&"a.txt".to_string()));
        assert!(paths.contains(&"sub/b.bin".to_string()));
        let out_dir = out.clone();
        archive.extract_all(&out_dir).unwrap();
        assert_eq!(std::fs::read(out.join("a.txt")).unwrap(), b"hello pfs");
        assert_eq!(std::fs::read(out.join("sub/b.bin")).unwrap(), big);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn pack_single_file() {
        let dir = tmp("single");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("one.dat"), vec![7u8; 8000]).unwrap();
        let pfs = dir.join("one.pfs");
        let out = dir.join("out");
        create_pfs(dir.join("one.dat").to_str().unwrap(), pfs.to_str().unwrap()).unwrap();
        let out_dir = out.clone();
        Pf8Archive::open(Path::new(&pfs)).unwrap().extract_all(&out_dir).unwrap();
        assert_eq!(std::fs::read(out.join("one.dat")).unwrap(), vec![7u8; 8000]);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn pf6_header_matches_pf8_writer_layout() {
        // The independent PF6 writer must produce a header byte-identical to
        // the pf8 crate's Pf8Writer output for the same entry set (modulo the
        // magic) — any drift would break external tools reading the index or
        // the filesize-offset table. Data differs (PF8 XOR-encrypts), so only
        // the header region is compared; XOR is length-preserving so the
        // total file length must also match.
        let dir = tmp("pf6parity");
        let indir = dir.join("in");
        std::fs::create_dir_all(indir.join("sub")).unwrap();
        std::fs::write(indir.join("a.txt"), b"parity").unwrap();
        std::fs::write(indir.join("sub/b.bin"), vec![1u8; 3000]).unwrap();

        // Outputs live OUTSIDE the input dir — collect_files_pfs would
        // otherwise pack the previous archive into the next run.
        let pf8 = dir.join("p.pfs");
        let pf6 = dir.join("q.pfs");
        create_pfs(indir.to_str().unwrap(), pf8.to_str().unwrap()).unwrap();
        create_pf6(indir.to_str().unwrap(), pf6.to_str().unwrap()).unwrap();

        let d8 = std::fs::read(&pf8).unwrap();
        let d6 = std::fs::read(&pf6).unwrap();
        assert_eq!(&d8[..3], b"pf8");
        assert_eq!(&d6[..3], b"pf6");
        assert_eq!(d8.len(), d6.len(), "XOR is length-preserving");
        let index_size = u32::from_le_bytes(d6[3..7].try_into().unwrap()) as usize;
        let header_len = 7 + index_size;
        assert_eq!(d6[3..header_len], d8[3..header_len], "header layout drift");
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn pf6_pack_round_trip() {
        // PF6 pack (independent writer, no encryption) must be readable by the
        // pf8 crate's PF6 path and round-trip every byte, including nested
        // directories and a file large enough to cross the 4MiB stream chunks.
        let dir = tmp("pf6");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::create_dir_all(dir.join("sub/bg")).unwrap();
        std::fs::create_dir_all(dir.join("script")).unwrap();
        std::fs::write(dir.join("script/init.tjs"), b"// pf6 test\n").unwrap();
        let big: Vec<u8> = (0..5_500_000u32).map(|i| (i % 251) as u8).collect();
        std::fs::write(dir.join("sub/bg/large.png"), &big).unwrap();
        std::fs::write(dir.join("sub/skip_me.mp4"), b"unencrypted-extension").unwrap();

        let pf6 = dir.join("root.pfs");
        create_pf6(dir.to_str().unwrap(), pf6.to_str().unwrap()).unwrap();

        // magic must be pf6
        let magic = std::fs::read(&pf6).unwrap();
        assert_eq!(&magic[..3], b"pf6");

        let mut archive = Pf8Archive::open(Path::new(&pf6)).unwrap();
        let paths: Vec<String> = archive.entries()
            .map(|e| e.path().to_string_lossy().replace('\\', "/")).collect();
        assert!(paths.contains(&"script/init.tjs".to_string()), "{paths:?}");
        assert!(paths.contains(&"sub/bg/large.png".to_string()), "{paths:?}");
        let out = dir.join("out");
        let out_dir = out.clone();
        archive.extract_all(&out_dir).unwrap();
        assert_eq!(std::fs::read(out.join("script/init.tjs")).unwrap(), b"// pf6 test\n");
        assert_eq!(std::fs::read(out.join("sub/bg/large.png")).unwrap(), big);
        assert_eq!(std::fs::read(out.join("sub/skip_me.mp4")).unwrap(), b"unencrypted-extension");
        std::fs::remove_dir_all(&dir).ok();
    }
}
