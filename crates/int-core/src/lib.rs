//! INT — CatSystem2's `KIF` archive (`.int`), the container Frontwing-lineage
//! galgames ship their assets in.
//!
//! ## Layout
//!
//! ```text
//! "KIF\0"                        magic
//! u32                            entry count
//! count × { char name[64]; u32 offset; u32 size }
//! ... payload ...
//! ```
//!
//! Two variants, told apart by a `__key__.dat` entry in the index:
//!
//! * **plain** — names are NUL-terminated strings, offset/size are plain LE.
//! * **encrypted** — every other name is run through a per-entry permutation
//!   (`MT19937(table_seed + i)` picks the shift), and both the offset/size pair
//!   and the payload are Blowfish-ECB encrypted with a key that comes from
//!   `__key__.dat`'s *raw size field*.
//!
//! ## Where the key comes from
//!
//! The encryption is not self-contained: `table_seed` is derived from
//! `key_code` / `v_code` / `v_code2` resources **inside the game's executable**,
//! which is why every tool for this format needs the `.exe` next to the
//! archive. We look for `*.exe` beside the archive and refuse with a clear
//! message when none is there — guessing would be worse than failing.
//!
//! ## Verification
//!
//! `testdata/` holds a real archive produced by the engine's own tooling
//! (`ptcl.int`), the matching `fakegame.exe` that carries its keys, and the 13
//! files an independent decoder (arc_unpacker) extracts from it. The tests
//! compare every extracted byte against those — the names, sizes, offsets and
//! payloads all have to line up, and the Blowfish/MT19937 implementations carry
//! their own test vectors on top.

mod bf_tables;
mod blowfish;
mod mt19937;
mod pe;

use archive_common::{
    derive_dirs, extract_progress, extract_result_json, json_escape, s, safe_join, DestAllocator,
    ProgressWriter,
};
use blowfish::Blowfish;
use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;
use mt19937::Mt19937;
use std::fs::File;
use std::io::{BufWriter, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

const MAGIC: &[u8; 4] = b"KIF\0";
const NAME_FIELD: usize = 64;
const RECORD: usize = NAME_FIELD + 8;
/// The index sits at the front; this bounds what we will read for it.
const MAX_INDEX: u64 = 64 * 1024 * 1024;
const MAX_ENTRIES: u32 = 200_000;

#[derive(Debug, Clone)]
pub struct IntEntry {
    pub name: String,
    pub offset: u64,
    pub size: u64,
    /// `Some(reason)` when the entry cannot be extracted (a range outside the
    /// file, or an unusable name) — listing still shows it.
    pub broken: Option<String>,
}

#[derive(Debug)]
pub struct IntArchive {
    pub encrypted: bool,
    /// The payload/index Blowfish key, carried here so extraction does not have
    /// to re-read and re-parse the index (that duplication is exactly what put
    /// the extraction path 8 bytes out of step once already).
    pub(crate) file_key: Option<[u8; 4]>,
    pub entries: Vec<IntEntry>,
    pub file_len: u64,
}

/// The name permutation: for each byte, find where it sits in the *reversed*
/// alphabet starting at `shift`, then emit the forward alphabet at that same
/// offset. Bytes outside the alphabet (`.` and the NUL padding) pass through,
/// and `shift` advances by one per byte.
fn decrypt_name(input: &[u8], seed: u32) -> Vec<u8> {
    const FWD: &[u8; 52] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    let mut rev = *FWD;
    rev.reverse();
    let key = Mt19937::classic(seed).next_u32();
    let mut shift = ((key >> 24) + (key >> 16) + (key >> 8) + key) as u8 as u32;
    let mut out = input.to_vec();
    for p in out.iter_mut() {
        let c = *p;
        let mut index1: u32 = 0;
        let mut index2: u32 = shift;
        while rev[(index2 % 52) as usize] != c {
            if rev[((shift + index1 + 1) % 52) as usize] == c {
                index1 += 1;
                break;
            }
            if rev[((shift + index1 + 2) % 52) as usize] == c {
                index1 += 2;
                break;
            }
            if rev[((shift + index1 + 3) % 52) as usize] == c {
                index1 += 3;
                break;
            }
            index1 += 4;
            index2 += 4;
            if index1 > 52 {
                break;
            }
        }
        if index1 < 52 {
            *p = FWD[index1 as usize];
        }
        shift = shift.wrapping_add(1);
    }
    out
}

/// The CRC-like table seed: a bitwise loop over `game_id`, inverting the state
/// after every byte.
fn table_seed(game_id: &[u8]) -> u32 {
    const POLY: u32 = 0x4C11_DB7;
    let mut seed: u32 = 0xFFFF_FFFF;
    for p in game_id {
        seed ^= (*p as u32) << 24;
        for _ in 0..8 {
            let bit = seed & 0x8000_0000 != 0;
            seed <<= 1;
            if bit {
                seed ^= POLY;
            }
        }
        seed = !seed;
    }
    seed
}

fn trim_zero(b: &[u8]) -> &[u8] {
    let end = b.iter().position(|c| *c == 0).unwrap_or(b.len());
    &b[..end]
}

fn to_name(b: &[u8]) -> Result<String, String> {
    std::str::from_utf8(b)
        .map(|s| s.to_string())
        .map_err(|_| "INT: entry name is not valid UTF-8".to_string())
}

/// Collects `key_code` / `v_code` / `v_code2` from the executables next to the
/// archive. The last executable that carries a given resource wins, matching
/// the reference decoder's loop.
fn keys_from_executables(dir: &Path) -> (Option<Vec<u8>>, Option<Vec<u8>>, Option<Vec<u8>>) {
    let (mut key_code, mut v_code, mut v_code2) = (None, None, None);
    let Ok(rd) = std::fs::read_dir(dir) else { return (key_code, v_code, v_code2) };
    let mut exes: Vec<PathBuf> = rd
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.is_file() && p.extension().map(|e| e.eq_ignore_ascii_case("exe")).unwrap_or(false))
        .collect();
    exes.sort();
    for exe in exes {
        let Ok(res) = pe::resources_of(&exe) else { continue };
        for (path, data) in res {
            let low = path.to_lowercase();
            if low.contains("v_code2") {
                v_code2 = Some(data);
            } else if low.contains("v_code") {
                v_code = Some(data);
            } else if low.contains("key_code") {
                key_code = Some(data);
            }
        }
    }
    (key_code, v_code, v_code2)
}

fn file_key_from(size_field: u32) -> [u8; 4] {
    Mt19937::classic(size_field).next_u32().to_le_bytes()
}


/// Scans the index records (which start immediately after the 8-byte header)
/// for `__key__.dat` and derives the payload/index Blowfish key from its *size*
/// field. ONE source for this — the extraction path used to re-read the index
/// including the header and so walked the records 8 bytes out of step, which
/// left the offsets correct (they come from `open_int`) while every payload
/// decrypted to noise.
fn file_key_from_index(index: &[u8]) -> Option<[u8; 4]> {
    for i in 0..index.len() / RECORD {
        let rec = &index[i * RECORD..(i + 1) * RECORD];
        if trim_zero(&rec[..NAME_FIELD]) == b"__key__.dat" {
            let sf = u32::from_le_bytes([rec[NAME_FIELD + 4], rec[NAME_FIELD + 5], rec[NAME_FIELD + 6], rec[NAME_FIELD + 7]]);
            return Some(file_key_from(sf));
        }
    }
    None
}

/// Opens an `.int` archive and parses its index.
pub fn open_int(path: &str) -> Result<IntArchive, String> {
    let mut f = File::open(path).map_err(|e| format!("INT: open {path}: {e}"))?;
    let file_len = f.metadata().map_err(|e| format!("INT: stat {path}: {e}"))?.len();
    if file_len < 8 {
        return Err("INT: file is too small to be an archive".to_string());
    }
    let mut head = [0u8; 8];
    f.read_exact(&mut head).map_err(|e| format!("INT: read header: {e}"))?;
    if &head[0..4] != MAGIC {
        return Err("INT: not a KIF archive (bad magic)".to_string());
    }
    let count = u32::from_le_bytes([head[4], head[5], head[6], head[7]]);
    if count == 0 {
        return Ok(IntArchive { encrypted: false, file_key: None, entries: Vec::new(), file_len });
    }
    if count > MAX_ENTRIES {
        return Err(format!("INT: implausible entry count {count}"));
    }
    let index_len = count as u64 * RECORD as u64;
    if 8 + index_len > file_len || index_len > MAX_INDEX {
        return Err(format!("INT: index of {count} entries does not fit the file"));
    }
    let mut index = vec![0u8; index_len as usize];
    f.read_exact(&mut index).map_err(|e| format!("INT: read index: {e}"))?;

    // Pass 1: is there a `__key__.dat` entry? Its *size* field (not the
    // offset) seeds the payload key — reading the wrong one still decrypts the
    // names, so it fails in a way that looks like a data problem.
    let file_key = file_key_from_index(&index);
    let encrypted = file_key.is_some();

    let (seed, bf) = if encrypted {
        let dir = Path::new(path).parent().unwrap_or(Path::new("."));
        let (kc, _vc, vc2) = keys_from_executables(dir);
        let (Some(kc), Some(vc2)) = (kc, vc2) else {
            return Err("INT: 需要与归档同目录的游戏 exe（key_code / v_code2 资源）— CatSystem2 的索引密钥来自可执行文件".to_string());
        };
        let key: Vec<u8> = kc.iter().map(|b| b ^ 0xCD).collect();
        let game_id = Blowfish::new(&key).decrypt(&vc2);
        let game_id = trim_zero(&game_id).to_vec();
        if game_id.is_empty() {
            return Err("INT: 从 exe 资源推出的 game id 为空（exe 不匹配？）".to_string());
        }
        (table_seed(&game_id), file_key.map(|k| Blowfish::new(&k)))
    } else {
        (0, None)
    };

    let mut entries = Vec::with_capacity(count as usize);
    for i in 0..count as usize {
        let rec = &index[i * RECORD..(i + 1) * RECORD];
        let raw_name = &rec[..NAME_FIELD];
        if trim_zero(raw_name) == b"__key__.dat" {
            continue;
        }
        let (name, offset, size) = if let Some(bf) = &bf {
            let name = to_name(trim_zero(&decrypt_name(raw_name, seed.wrapping_add(i as u32))))?;
            let mut blob = [0u8; 8];
            blob.copy_from_slice(&rec[NAME_FIELD..NAME_FIELD + 8]);
            let first = u32::from_le_bytes([blob[0], blob[1], blob[2], blob[3]]).wrapping_add(i as u32);
            blob[0..4].copy_from_slice(&first.to_le_bytes());
            let dec = bf.decrypt(&blob);
            (name, u32::from_le_bytes([dec[0], dec[1], dec[2], dec[3]]) as u64, u32::from_le_bytes([dec[4], dec[5], dec[6], dec[7]]) as u64)
        } else {
            let name = to_name(trim_zero(raw_name))?;
            let offset = u32::from_le_bytes([rec[NAME_FIELD], rec[NAME_FIELD + 1], rec[NAME_FIELD + 2], rec[NAME_FIELD + 3]]) as u64;
            let size = u32::from_le_bytes([rec[NAME_FIELD + 4], rec[NAME_FIELD + 5], rec[NAME_FIELD + 6], rec[NAME_FIELD + 7]]) as u64;
            (name, offset, size)
        };
        let mut broken = None;
        match offset.checked_add(size) {
            Some(end) if end <= file_len => {}
            _ => broken = Some(format!("INT: {name} runs past the end of the archive")),
        }
        if broken.is_none() {
            if let Err(e) = safe_join(".", &name) {
                broken = Some(e);
            }
        }
        entries.push(IntEntry { name, offset, size, broken });
    }
    Ok(IntArchive { encrypted, file_key, entries, file_len })
}

/// JSON contract shared with every other format:
/// `[{"n":name,"s":size,"d":isDir,"e":encrypted}]`.
pub fn int_list(path: &str) -> Result<String, String> {
    let ar = open_int(path)?;
    let names: Vec<&str> = ar.entries.iter().map(|e| e.name.as_str()).collect();
    let dirs = derive_dirs(&names);
    let mut all: Vec<(String, u64, bool)> = Vec::new();
    for d in &dirs {
        all.push((d.clone(), 0, true));
    }
    for e in &ar.entries {
        all.push((e.name.clone(), e.size, false));
    }
    all.sort_by(|a, b| a.0.cmp(&b.0));
    let items: Vec<String> = all
        .iter()
        .map(|(n, sz, d)| {
            format!(
                r#"{{"n":"{}","s":{},"d":{},"e":{}}}"#,
                json_escape(n),
                if *d { 0 } else { *sz },
                d,
                ar.encrypted
            )
        })
        .collect();
    Ok(format!("[{}]", items.join(",")))
}

/// Re-aligns an encrypted byte stream across read boundaries.
///
/// The payload is one Blowfish-ECB stream, so every chunk handed to the cipher
/// must start on an 8-byte boundary. `File::read` is allowed to return fewer
/// bytes than asked for — it does on FUSE/sdcardfs and for large entries — and
/// a naive "decrypt whatever arrived" then leaves a short chunk's tail
/// *encrypted* and shifts every later block by the leftover count, so the rest
/// of the file decrypts to noise. This carries the partial block forward
/// instead; the stream's own final partial block is written verbatim, which is
/// what the reference decoder does too.
#[derive(Default)]
struct BlockAligner {
    carry: Vec<u8>,
}

impl BlockAligner {
    /// Feeds one chunk and appends the bytes that are ready to be written.
    fn push(&mut self, chunk: &[u8], bf: Option<&Blowfish>, out: &mut Vec<u8>) {
        if self.carry.is_empty() && bf.is_none() {
            out.extend_from_slice(chunk);
            return;
        }
        let mut data = Vec::with_capacity(self.carry.len() + chunk.len());
        data.extend_from_slice(&self.carry);
        data.extend_from_slice(chunk);
        let aligned = data.len() / 8 * 8;
        if let Some(bf) = bf {
            bf.decrypt_in_place(&mut data[..aligned]);
        }
        out.extend_from_slice(&data[..aligned]);
        self.carry.clear();
        self.carry.extend_from_slice(&data[aligned..]);
    }

    /// Appends whatever is still held back (only ever < 8 bytes).
    fn finish(&mut self, out: &mut Vec<u8>) {
        out.extend_from_slice(&self.carry);
        self.carry.clear();
    }
}

fn write_entry(f: &mut File, e: &IntEntry, out: &str, alloc: &mut DestAllocator, bf: Option<&Blowfish>) -> Result<(), String> {
    if let Some(reason) = &e.broken {
        return Err(reason.clone());
    }
    let dest = alloc.allocate(safe_join(out, &e.name)?);
    if let Some(p) = dest.parent() {
        std::fs::create_dir_all(p).map_err(|er| format!("INT: {}: {er}", p.display()))?;
    }
    let file = File::create(&dest).map_err(|er| format!("INT: {}: {er}", dest.display()))?;
    let mut w = ProgressWriter::extract(BufWriter::with_capacity(256 * 1024, file));
    let res = (|| -> Result<(), String> {
        f.seek(SeekFrom::Start(e.offset)).map_err(|er| format!("INT: seek: {er}"))?;
        let mut buf = vec![0u8; 256 * 1024];
        let mut ready: Vec<u8> = Vec::with_capacity(buf.len() + 8);
        let mut aligner = BlockAligner::default();
        let mut left = e.size;
        while left > 0 {
            let want = left.min(buf.len() as u64) as usize;
            let n = f.read(&mut buf[..want]).map_err(|er| format!("INT: read: {er}"))?;
            if n == 0 {
                return Err(format!("INT: {}: unexpected end of archive", e.name));
            }
            left -= n as u64;
            ready.clear();
            aligner.push(&buf[..n], bf, &mut ready);
            w.write_all(&ready).map_err(|er| format!("INT: write: {er}"))?;
        }
        ready.clear();
        aligner.finish(&mut ready);
        w.write_all(&ready).map_err(|er| format!("INT: write: {er}"))?;
        Ok(())
    })();
    if let Err(er) = res {
        drop(w);
        let _ = std::fs::remove_file(&dest);
        return Err(er);
    }
    w.flush().map_err(|er| format!("INT: flush: {er}"))?;
    Ok(())
}

fn extract_entries(input: &str, ar: &IntArchive, entries: &[&IntEntry], out: &str) -> Result<(u32, u32), String> {
    std::fs::create_dir_all(out).map_err(|e| format!("INT: create {out}: {e}"))?;
    let mut f = File::open(input).map_err(|e| format!("INT: open {input}: {e}"))?;
    // The key travels with the parsed archive — no second index read.
    let bf = ar.file_key.map(|k| Blowfish::new(&k));

    let attempt: Vec<&IntEntry> = entries.iter().copied().filter(|e| e.broken.is_none()).collect();
    let broken = entries.len() as u32 - attempt.len() as u32;
    extract_progress::reset(attempt.iter().map(|e| e.size).sum());
    let mut alloc = DestAllocator::new();
    let mut fail = broken;
    for e in attempt {
        if extract_progress::cancelled() {
            return Err("cancelled".to_string());
        }
        extract_progress::set_name(&e.name);
        extract_progress::set_file(e.size);
        if write_entry(&mut f, e, out, &mut alloc, bf.as_ref()).is_err() {
            fail += 1;
        }
    }
    Ok((entries.len() as u32, fail))
}

fn select<'a>(ar: &'a IntArchive, sel: &str) -> Vec<&'a IntEntry> {
    let set: std::collections::HashSet<&str> = sel.lines().filter(|l| !l.is_empty()).collect();
    ar.entries
        .iter()
        .filter(|e| set.contains(e.name.as_str()) || set.iter().any(|d| e.name.starts_with(&format!("{d}/"))))
        .collect()
}

#[doc(hidden)]
pub fn extract_int_host(input: &str, output: &str) -> Result<(u32, u32), String> {
    let ar = open_int(input)?;
    let all: Vec<&IntEntry> = ar.entries.iter().collect();
    extract_entries(input, &ar, &all, output)
}

#[doc(hidden)]
pub fn extract_int_selected_host(input: &str, output: &str, sel: &str) -> Result<(u32, u32), String> {
    if sel.lines().all(|l| l.is_empty()) {
        return Ok((0, 0));
    }
    let ar = open_int(input)?;
    let picked = select(&ar, sel);
    extract_entries(input, &ar, &picked, output)
}


// ─── JNI ───

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_IntCore_intListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i);
    match guarded(move || int_list(&inp)) {
        Ok(j) => match e.new_string(&j) {
            Ok(js) => js.into_raw(),
            _ => std::ptr::null_mut(),
        },
        Err(er) => {
            let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}"));
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_IntCore_intExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i);
    let out = s(&mut e, &o);
    match guarded(move || extract_int_host(&inp, &out)) {
        Ok((total, error)) => {
            let json = extract_result_json(total, total.saturating_sub(error), error);
            match e.new_string(&json) {
                Ok(js) => js.into_raw(),
                _ => std::ptr::null_mut(),
            }
        }
        Err(er) => {
            let _ = e.throw_new("java/io/IOException", er);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_IntCore_intExtractSelected(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel_j: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i);
    let out = s(&mut e, &o);
    let sel = s(&mut e, &sel_j);
    match guarded(move || extract_int_selected_host(&inp, &out, &sel)) {
        Ok((total, error)) => {
            let json = extract_result_json(total, total.saturating_sub(error), error);
            match e.new_string(&json) {
                Ok(js) => js.into_raw(),
                _ => std::ptr::null_mut(),
            }
        }
        Err(er) => {
            let _ = e.throw_new("java/io/IOException", er);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_IntCore_intExtractProgressCount(_: JNIEnv, _: JClass) -> jlong {
    extract_progress::bytes() as jlong
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_IntCore_intExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong {
    extract_progress::total_bytes() as jlong
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_IntCore_intExtractProgressFileCount(_: JNIEnv, _: JClass) -> jlong {
    extract_progress::file_bytes() as jlong
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_IntCore_intExtractProgressFileTotal(_: JNIEnv, _: JClass) -> jlong {
    extract_progress::file_total() as jlong
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_IntCore_intExtractProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|v| v.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_IntCore_intExtractCancel(_: JNIEnv, _: JClass) {
    extract_progress::cancel();
}

fn guarded<T: Send + 'static>(f: impl FnOnce() -> Result<T, String> + Send + 'static) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|panic| {
        let msg = panic
            .downcast_ref::<&str>()
            .copied()
            .or_else(|| panic.downcast_ref::<String>().map(|v| v.as_str()))
            .unwrap_or("unknown panic");
        Err(format!("panic: {msg}"))
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn testdata(name: &str) -> PathBuf {
        Path::new(env!("CARGO_MANIFEST_DIR")).join("testdata").join(name)
    }

    /// The archive + exe + expected outputs are the ones an independent decoder
    /// (arc_unpacker) ships with, so this is a real-file test, not a fixture we
    /// made up: names, offsets and payloads all have to match that tool.
    /// Cargo runs tests in parallel, so every test needs its own copy — a
    /// shared directory had one test deleting the files another was reading.
    fn fixture_dir(tag: &str) -> PathBuf {
        let src = testdata("");
        let dir = std::env::temp_dir().join(format!("uu_int_{}_{}", std::process::id(), tag));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::copy(src.join("ptcl.int"), dir.join("ptcl.int")).unwrap();
        std::fs::copy(src.join("fakegame.exe"), dir.join("fakegame.exe")).unwrap();
        dir
    }

    #[test]
    fn encrypted_archive_lists_the_expected_names() {
        let dir = fixture_dir("names");
        let ar = open_int(dir.join("ptcl.int").to_str().unwrap()).unwrap();
        assert!(ar.encrypted, "the fixture carries __key__.dat");
        let names: Vec<&str> = ar.entries.iter().map(|e| e.name.as_str()).collect();
        let want = ["ase.kcs", "ase2.kcs", "bhole.kcs", "bubble.kcs", "burst01.kcs", "burst02.kcs", "burst03.kcs", "flare.kcs", "poison.kcs", "rain.kcs", "snow.kcs", "spark.kcs", "wind.kcs"];
        assert_eq!(names, want);
        assert!(ar.entries.iter().all(|e| e.broken.is_none()));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn extraction_reproduces_the_expected_bytes() {
        let dir = fixture_dir("extract");
        let out = dir.join("out");
        let (total, fail) = extract_int_host(dir.join("ptcl.int").to_str().unwrap(), out.to_str().unwrap()).unwrap();
        assert_eq!((total, fail), (13, 0));
        let expected = testdata("expected");
        let mut checked = 0;
        for entry in std::fs::read_dir(&expected).unwrap().flatten() {
            let want = std::fs::read(entry.path()).unwrap();
            let got = std::fs::read(out.join(entry.file_name())).unwrap_or_else(|e| panic!("{}: {e}", entry.file_name().to_string_lossy()));
            assert_eq!(got, want, "{} differs from the reference output", entry.file_name().to_string_lossy());
            checked += 1;
        }
        assert_eq!(checked, 13, "all 13 reference outputs must be present");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn selected_extraction_covers_exact_names() {
        let dir = fixture_dir("selected");
        let out = dir.join("sel");
        let (total, fail) = extract_int_selected_host(dir.join("ptcl.int").to_str().unwrap(), out.to_str().unwrap(), "rain.kcs\nsnow.kcs\n").unwrap();
        assert_eq!((total, fail), (2, 0));
        assert!(out.join("rain.kcs").exists() && out.join("snow.kcs").exists());
        assert!(!out.join("ase.kcs").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Without the executable the key cannot be derived — that has to be a
    /// clear refusal, not a wrong listing full of mojibake names.
    #[test]
    fn missing_executable_is_refused_with_a_reason() {
        let dir = std::env::temp_dir().join(format!("uu_int_noexe_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::copy(testdata("ptcl.int"), dir.join("ptcl.int")).unwrap();
        let err = open_int(dir.join("ptcl.int").to_str().unwrap()).unwrap_err();
        assert!(err.contains("exe"), "got: {err}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Builds a **plain** (unencrypted) KIF archive in memory.
    ///
    /// No real plain-variant sample was available — the reference corpus ships
    /// only the encrypted one — so this fixture is spec-derived: it pins the
    /// layout the reader claims to support (names NUL-terminated, offset/size
    /// plain LE, no `__key__.dat`) and must NOT be read as evidence of
    /// real-world compatibility. The encrypted path is the one with real
    /// evidence behind it.
    fn plain_archive(entries: &[(&str, &[u8])]) -> Vec<u8> {
        let mut body = Vec::new();
        let mut recs = Vec::new();
        let mut at = 8 + entries.len() * RECORD;
        for (name, data) in entries {
            let mut rec = vec![0u8; RECORD];
            let n = name.as_bytes();
            assert!(n.len() < NAME_FIELD);
            rec[..n.len()].copy_from_slice(n);
            rec[NAME_FIELD..NAME_FIELD + 4].copy_from_slice(&(at as u32).to_le_bytes());
            rec[NAME_FIELD + 4..NAME_FIELD + 8].copy_from_slice(&(data.len() as u32).to_le_bytes());
            recs.push(rec);
            body.extend_from_slice(data);
            at += data.len();
        }
        let mut out = Vec::new();
        out.extend_from_slice(MAGIC);
        out.extend_from_slice(&(entries.len() as u32).to_le_bytes());
        for r in recs { out.extend_from_slice(&r); }
        out.extend_from_slice(&body);
        out
    }

    #[test]
    fn plain_archives_need_no_executable_and_list_their_names() {
        let dir = std::env::temp_dir().join(format!("uu_int_plain_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let p = dir.join("plain.int");
        // Deliberately no .exe anywhere near it.
        std::fs::write(&p, plain_archive(&[("a.txt", b"hello"), ("sub/b.bin", &[1, 2, 3, 4, 5, 6, 7, 8, 9])])).unwrap();

        let ar = open_int(p.to_str().unwrap()).unwrap();
        assert!(!ar.encrypted);
        assert_eq!(ar.entries.iter().map(|e| e.name.as_str()).collect::<Vec<_>>(), vec!["a.txt", "sub/b.bin"]);
        assert!(ar.entries.iter().all(|e| e.broken.is_none()));

        let out = dir.join("out");
        let (total, fail) = extract_int_host(p.to_str().unwrap(), out.to_str().unwrap()).unwrap();
        assert_eq!((total, fail), (2, 0));
        assert_eq!(std::fs::read(out.join("a.txt")).unwrap(), b"hello");
        assert_eq!(std::fs::read(out.join("sub/b.bin")).unwrap(), vec![1u8, 2, 3, 4, 5, 6, 7, 8, 9]);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn plain_archive_with_a_range_past_eof_marks_only_that_entry() {
        let dir = std::env::temp_dir().join(format!("uu_int_plain_bad_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let p = dir.join("bad.int");
        let mut raw = plain_archive(&[("ok.txt", b"fine"), ("bad.txt", b"xxxx")]);
        // Point the second entry's size past the end of the file.
        let rec2 = 8 + RECORD + NAME_FIELD + 4;
        raw[rec2..rec2 + 4].copy_from_slice(&9_999u32.to_le_bytes());
        std::fs::write(&p, &raw).unwrap();
        let ar = open_int(p.to_str().unwrap()).unwrap();
        let bad = ar.entries.iter().find(|e| e.name == "bad.txt").unwrap();
        assert!(bad.broken.is_some(), "out-of-range entry must be flagged");
        assert!(ar.entries.iter().find(|e| e.name == "ok.txt").unwrap().broken.is_none());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn non_kif_files_are_rejected() {
        let dir = std::env::temp_dir().join(format!("uu_int_bad_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let p = dir.join("x.int");
        std::fs::write(&p, b"PK\x03\x04not a kif").unwrap();
        let err = open_int(p.to_str().unwrap()).unwrap_err();
        assert!(err.contains("bad magic"), "got: {err}");
        std::fs::write(&p, b"KIF\x00\xff\xff\xff\xff").unwrap();
        let err = open_int(p.to_str().unwrap()).unwrap_err();
        assert!(err.contains("implausible"), "got: {err}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Chunking must not change the result: the same ciphertext fed in awkward
    /// short reads has to decrypt exactly like a single read. This is the bug
    /// the aligner exists for (a short read used to shift every later block).
    #[test]
    fn block_aligner_is_chunking_invariant() {
        let bf = Blowfish::new(&[1, 2, 3, 4, 5, 6, 7, 8, 9]);
        // 40 bytes = 5 full blocks, plus a 5-byte tail the cipher does not cover.
        let cipher: Vec<u8> = (0..45u8).map(|i| i.wrapping_mul(37).wrapping_add(11)).collect();
        let mut one = BlockAligner::default();
        let mut one_shot = Vec::new();
        one.push(&cipher, Some(&bf), &mut one_shot);
        one.finish(&mut one_shot);

        for splits in [vec![3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3], vec![7, 1, 9, 2, 26], vec![44, 1], vec![1, 44]] {
            let mut a = BlockAligner::default();
            let mut got = Vec::new();
            let mut at = 0usize;
            for s in &splits {
                let end = (at + *s).min(cipher.len());
                if at >= cipher.len() {
                    break;
                }
                a.push(&cipher[at..end], Some(&bf), &mut got);
                at = end;
            }
            a.push(&cipher[at..], Some(&bf), &mut got);
            a.finish(&mut got);
            assert_eq!(got, one_shot, "splits {splits:?} changed the result");
            assert_eq!(got.len(), cipher.len());
        }
    }

    /// With no cipher (the plain variant) the aligner is a pass-through.
    #[test]
    fn block_aligner_passes_plain_bytes_through() {
        let mut a = BlockAligner::default();
        let mut got = Vec::new();
        a.push(b"abc", None, &mut got);
        a.push(b"defgh", None, &mut got);
        a.finish(&mut got);
        assert_eq!(got, b"abcdefgh");
    }

    /// The listing JSON is the app-wide contract
    /// (`[{"n":name,"s":size,"d":isDir,"e":encrypted}]`) and the CLI parses it,
    /// so it is pinned here rather than only exercised through the UI.
    #[test]
    fn listing_json_follows_the_shared_contract() {
        let dir = fixture_dir("json");
        let json = int_list(dir.join("ptcl.int").to_str().unwrap()).unwrap();
        assert!(json.starts_with('[') && json.ends_with(']'));
        assert!(json.contains(r#"{"n":"ase.kcs","s":822,"d":false,"e":true}"#), "{json}");
        // Encrypted archives advertise `e:true` — the UI turns that into the lock.
        assert!(json.contains(r#""e":true"#));
        // Plain archives must not claim encryption.
        let plain = dir.join("p.int");
        std::fs::write(&plain, plain_archive(&[("x.txt", b"hi")])).unwrap();
        let json = int_list(plain.to_str().unwrap()).unwrap();
        assert!(json.contains(r#"{"n":"x.txt","s":2,"d":false,"e":false}"#), "{json}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn name_permutation_passes_unknown_bytes_through() {
        // '.' and NUL are outside the alphabet, so they must survive unchanged
        // whatever the shift is (this is what keeps "foo.kcs" shaped like a
        // file name after decryption).
        let raw = b"abc.def\0\0\0";
        let out = decrypt_name(raw, 1234);
        assert_eq!(out[3], b'.');
        assert_eq!(&out[7..], &[0, 0, 0]);
        assert_ne!(&out[0..3], b"abc", "the alphabet bytes are permuted");
    }

    /// Deterministic mutation fuzzer.
    ///
    /// The parsers all run on files the user picked, so "never panic, never
    /// hang, always a clean Ok/Err" is a hard requirement. This walks a real
    /// fixture through byte flips, truncations, insertions and splices with a
    /// fixed LCG, so a failure reproduces exactly.
    fn mutate(base: &[u8], seed: u64, i: usize) -> Vec<u8> {
        let mut r = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407 + i as u64);
        let mut next = |n: usize| -> usize {
            r = r.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            ((r >> 33) as usize) % n.max(1)
        };
        let mut d = base.to_vec();
        match i % 5 {
            0 => {
                let at = next(d.len());
                d[at] ^= 1 << (i % 8);
            }
            1 => d.truncate(next(d.len())),
            2 => {
                let at = next(d.len());
                d.insert(at, (i & 0xFF) as u8);
            }
            3 => {
                let at = next(d.len());
                let len = 1 + next(8);
                for k in 0..len.min(d.len() - at) {
                    d[at + k] = (i.wrapping_mul(31) & 0xFF) as u8;
                }
            }
            _ => {
                // Splice the head over the tail (creates self-referential sizes).
                if d.len() > 16 {
                    let cut = d.len() / 2;
                    let head: Vec<u8> = d[..cut].to_vec();
                    d.extend_from_slice(&head);
                }
            }
        }
        d
    }

    #[test]
    fn mutated_archives_never_panic() {
        let base = std::fs::read(testdata("ptcl.int")).unwrap();
        let dir = std::env::temp_dir().join(format!("uu_int_fuzz_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::copy(testdata("fakegame.exe"), dir.join("fakegame.exe")).unwrap();
        let p = dir.join("m.int");
        for i in 0..400 {
            let mutant = mutate(&base, 0x9E37_79B9_7F4A_7C15, i);
            std::fs::write(&p, &mutant).unwrap();
            // Either parses or fails — and the failure must be a message, not a crash.
            if let Ok(ar) = open_int(p.to_str().unwrap()) {
                // Whatever it parsed, extraction must stay inside the file.
                for e in &ar.entries {
                    if e.broken.is_none() {
                        assert!(e.offset + e.size <= ar.file_len, "entry escaped the file: {e:?}");
                    }
                }
                let _ = int_list(p.to_str().unwrap());
            }
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn mutated_names_and_seeds_never_panic() {
        // The permutation walks a fixed alphabet; a wrong seed must still be a
        // total function (it is what a corrupt index feeds it).
        let raw = [b'a'; 64];
        for seed in [0u32, 1, 0xFFFF_FFFF, 0x8000_0000, 12345] {
            let out = decrypt_name(&raw, seed);
            assert_eq!(out.len(), raw.len());
        }
        // table_seed on every byte value and on empty input.
        for b in 0..=255u8 {
            let _ = table_seed(&[b]);
        }
        assert_eq!(table_seed(&[]), 0xFFFF_FFFF);
    }

    #[test]
    fn table_seed_is_stable_for_the_fixture_game_id() {
        // The game id the reference decoder derives from fakegame.exe; the seed
        // it produces is what the name permutation is driven by.
        let game_id = b"FW-4NPY6FSY";
        assert_eq!(table_seed(game_id), 0x69df_d7a6);
    }
}
