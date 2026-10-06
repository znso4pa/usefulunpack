//! RPA — the Ren'Py archive format (`.rpa`, plus the legacy `.rpi` index-only
//! variant).
//!
//! ## Layout
//!
//! `RPA-3.0 <16 hex index offset> <8 hex key>\n` (34 bytes, always written with
//! `%016x %08x`), then the payload, then the zlib-compressed index. Ren'Py's
//! own reader (`renpy/loader.py`) reads a flat 40 bytes and slices
//! `[8:24]`/`[25:33]`, so a 34-byte header is what real files carry — the extra
//! 6 bytes it reads are just the first payload bytes, harmlessly discarded.
//! Older variants: `RPA-2.0 <offset>` (index not XOR'd), `RPA-1.0` = an `.rpi`
//! file that *starts* with the zlib'd index and whose offsets point at the tail
//! of the same file, and `ALT-1.0 <key^0xDABE8DF0> <offset>` (a short-lived
//! mainline variant; note key/offset are in the opposite order to 3.0).
//! `RPA-3.2`/`RPA-4.0` are byte-compatible with 3.0.
//!
//! ## Index
//!
//! `zlib(pickle(dict))` mapping `name -> list[part]`, where a part is
//! `(offset, dlen)` or `(offset, dlen, start)` and — for 3.0 — `offset` and
//! `dlen` are XOR'd with the header key. The **shape is taken from the first
//! part only**, exactly as `renpy/loader.py` does; all parts of one entry then
//! have that same shape (a mixed list is a `ValueError` in Ren'Py, and we
//! reject it the same way).
//!
//! A non-empty `start` is *inline data stored in the index itself*: Ren'Py's
//! compat path walks the parts in order, appending the inline bytes and then
//! reading each file chunk, and joins them. We reproduce that exactly. Note
//! that the two third-party readers disagree here — `unrpa` keeps only the
//! FIRST part and would silently drop later chunks — so this reader follows
//! the engine, which is the only consumer that has to be right.
//!
//! ## XOR and Python integers
//!
//! `offset ^ key` is computed by Python's arbitrary-precision ints and then
//! pickled; with keys ≥ 2^31 (rpatool's default 0xDEADBEEF, for one) the value
//! needs `LONG1` and lands here as a sign-extended `i64`. XOR-ing two `i64`s
//! reproduces CPython bit-for-bit for every value a writer can produce, and a
//! *negative* result can only come from a corrupt index — we reject it rather
//! than casting it into a huge offset.
//!
//! ## What was verified against what
//!
//! * A real archive written by Ren'Py 8.5.3's SDK (`distribute` →
//!   `archiver.Archive`) — the official writer — is exercised end-to-end by
//!   `tests/official_corpus.rs`, including CJK names, spaces, a nested path,
//!   an empty file and multi-MB members.
//! * `unrpa` 2.x and `rpatool` (two independent readers/writers) agree with
//!   this reader byte-for-byte on the 3.0/3.2/2.0/1.0 and ALT corpora.
//! * `ZiX-12A`/`ZiX-12B` are detected and refused with a specific message:
//!   they need the game's `renpy/loader.pyo` to derive the key, which is
//!   out of scope here.

mod pickle;

use archive_common::{
    compress_progress, derive_dirs, extract_progress, extract_result_json, json_escape, s,
    safe_join, DestAllocator, ProgressWriter,
};
use flate2::read::ZlibDecoder;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use pickle::Value;
use std::fs::File;
use std::io::{BufWriter, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

/// Targeted-progress caliber: **WRITE side**. The RPA index stores every
/// member's exact `dlen`, so the denominator is known before the first byte is
/// written — no READ-side fallback is needed anywhere in this format.
const ALT_EXTRA_KEY: u64 = 0xDABE8DF0;

/// Caps on the index stream. A real index for a 100k-entry game is a few MB;
/// these bounds only exist so a crafted header cannot make us allocate
/// unbounded memory before validating anything.
const MAX_INDEX_COMPRESSED: u64 = 256 * 1024 * 1024;
const MAX_INDEX_DECOMPRESSED: u64 = 256 * 1024 * 1024;

/// One piece of an entry: either bytes carried inside the index (`start`) or a
/// byte range of the archive file.
#[derive(Debug, Clone, PartialEq)]
pub enum RpaPart {
    Inline(Vec<u8>),
    Chunk { offset: u64, len: u64 },
}

#[derive(Debug, Clone)]
pub struct RpaEntry {
    pub name: String,
    pub parts: Vec<RpaPart>,
    /// Declared size (inline bytes + every chunk's length).
    pub size: u64,
    /// `Some(reason)` when the entry cannot be extracted (a chunk outside the
    /// file, or an unusable name) — listing still shows it, extraction fails
    /// just that entry.
    pub broken: Option<String>,
}

#[derive(Debug)]
pub struct Rpa {
    pub version: &'static str,
    pub key: u64,
    pub entries: Vec<RpaEntry>,
    pub file_len: u64,
}

// ─── header / index ───

fn hex_u64(bytes: &[u8], what: &str) -> Result<u64, String> {
    let t = std::str::from_utf8(bytes).map_err(|_| format!("RPA: {what} is not ASCII"))?.trim();
    if t.is_empty() || t.len() > 16 {
        return Err(format!("RPA: malformed {what} field ({t:?})"));
    }
    u64::from_str_radix(t, 16).map_err(|_| format!("RPA: malformed {what} field ({t:?})"))
}

/// Splits the header line into whitespace-separated fields.
///
/// Ren'Py slices fixed byte ranges (`l[8:24]`, `l[25:33]`) and unrpa splits the
/// line; both accept every file the official writer produces (`%016x %08x`).
/// Splitting is the tolerant superset — it also takes a short field that a
/// writer did not zero-pad — so that is what we do, on at most one line.
fn header_fields(line: &[u8]) -> Vec<Vec<u8>> {
    let end = line.iter().position(|b| *b == b'\n').unwrap_or(line.len());
    line[..end]
        .split(|b| *b == b' ' || *b == b'\t')
        .filter(|f| !f.is_empty())
        .map(|f| f.to_vec())
        .collect()
}

/// Reads up to `n` bytes, looping until the buffer is full or EOF is reached.
///
/// A single `read` is allowed to return fewer bytes than asked for (short
/// reads happen on FUSE/sdcardfs and when a large read crosses a page
/// boundary); treating that as "the header is truncated" would reject a valid
/// archive, so every header read goes through here.
fn read_at_most(f: &mut File, n: usize) -> Result<Vec<u8>, String> {
    let mut buf = vec![0u8; n];
    let mut got = 0usize;
    while got < n {
        match f.read(&mut buf[got..]).map_err(|e| format!("RPA: read header: {e}"))? {
            0 => break,
            k => got += k,
        }
    }
    buf.truncate(got);
    Ok(buf)
}

/// Reads the index stream (zlib) and returns the decompressed pickle bytes.
///
/// The decoder reads straight from the file rather than slurping a window into
/// memory first: for `RPA-1.0` the index sits at the *front* of a file whose
/// tail is the payload, so "read the rest of the file" would mean pulling
/// hundreds of megabytes (up to the guard cap) to decompress a few kilobytes.
/// Both caps stay as bomb guards.
fn read_index(f: &mut File, at: u64) -> Result<Vec<u8>, String> {
    f.seek(SeekFrom::Start(at)).map_err(|e| format!("RPA: seek to index: {e}"))?;
    let mut out = Vec::new();
    ZlibDecoder::new(f.take(MAX_INDEX_COMPRESSED))
        .take(MAX_INDEX_DECOMPRESSED)
        .read_to_end(&mut out)
        .map_err(|e| format!("RPA: index is not a zlib stream: {e}"))?;
    if out.is_empty() {
        return Err("RPA: index is empty".to_string());
    }
    if out.len() as u64 >= MAX_INDEX_DECOMPRESSED {
        return Err("RPA: index is implausibly large".to_string());
    }
    Ok(out)
}

/// `offset ^ key` with Python semantics; a negative result means the index is
/// corrupt (see the module docs).
fn xor_key(v: i64, key: u64) -> Result<u64, String> {
    let r = v ^ (key as i64);
    if r < 0 {
        return Err(format!("RPA: negative offset after XOR ({r})"));
    }
    Ok(r as u64)
}

fn int_of(v: &Value, what: &str) -> Result<i64, String> {
    match v {
        Value::Int(i) => Ok(*i),
        other => Err(format!("RPA: {what} is not an integer ({other:?})")),
    }
}

/// Entry name: unicode stays as-is; Python-2 `str` bytes must be UTF-8.
///
/// Mangled names are worse than an error here — a py2-era index that stored a
/// non-UTF-8 name cannot be read the way the engine would read it, so we say
/// so instead of writing mojibake to the user's disk.
fn name_of(v: &Value) -> Result<String, String> {
    match v {
        Value::Str(s) => Ok(s.clone()),
        Value::Bytes(b) => std::str::from_utf8(b)
            .map(|s| s.to_string())
            .map_err(|_| "RPA: entry name is not valid UTF-8 (Python-2 era index) — unsupported".to_string()),
        other => Err(format!("RPA: entry name is not a string ({other:?})")),
    }
}

fn build_entries(index: Value, key: u64, file_len: u64) -> Result<Vec<RpaEntry>, String> {
    let dict = match index {
        Value::Dict(d) => d,
        other => return Err(format!("RPA: index is not a dict ({other:?})")),
    };
    let mut entries = Vec::with_capacity(dict.len());
    for (k, v) in dict {
        let name = name_of(&k)?;
        let list = match v {
            Value::List(l) => l,
            other => return Err(format!("RPA: index value for {name:?} is not a list ({other:?})")),
        };
        if list.is_empty() {
            return Err(format!("RPA: index entry {name:?} has no parts"));
        }
        // Shape comes from the first part only — renpy/loader.py's rule.
        let three = match &list[0] {
            Value::Tuple(t) if t.len() == 2 => false,
            Value::Tuple(t) if t.len() == 3 => true,
            other => return Err(format!("RPA: index part for {name:?} is not a 2- or 3-tuple ({other:?})")),
        };
        let mut parts: Vec<RpaPart> = Vec::with_capacity(list.len());
        let mut size: u64 = 0;
        for item in &list {
            let t = match item {
                Value::Tuple(t) => t,
                other => return Err(format!("RPA: index part for {name:?} is not a tuple ({other:?})")),
            };
            if three {
                if t.len() != 3 {
                    return Err(format!("RPA: index entry {name:?} mixes 2- and 3-tuples"));
                }
                let inline = match &t[2] {
                    Value::Bytes(b) => b.clone(),
                    Value::None => Vec::new(),
                    Value::Str(st) => st.clone().into_bytes(),
                    other => return Err(format!("RPA: inline start for {name:?} is not bytes ({other:?})")),
                };
                if !inline.is_empty() {
                    size = size.saturating_add(inline.len() as u64);
                    parts.push(RpaPart::Inline(inline));
                }
            } else if t.len() != 2 {
                return Err(format!("RPA: index entry {name:?} mixes 2- and 3-tuples"));
            }
            let offset = xor_key(int_of(&t[0], "offset")?, key)?;
            let len = xor_key(int_of(&t[1], "length")?, key)?;
            size = size.saturating_add(len);
            parts.push(RpaPart::Chunk { offset, len });
        }

        let mut broken: Option<String> = None;
        for p in &parts {
            if let RpaPart::Chunk { offset, len } = p {
                match offset.checked_add(*len) {
                    Some(end) if end <= file_len => {}
                    _ => {
                        broken = Some(format!("RPA: {name} runs past the end of the archive"));
                        break;
                    }
                }
            }
        }
        if let Err(e) = safe_join(".", &name) {
            broken = Some(e);
        }
        entries.push(RpaEntry { name, parts, size, broken });
    }
    Ok(entries)
}

/// Opens an RPA archive and parses its index.
pub fn open_rpa(path: &str) -> Result<Rpa, String> {
    let mut f = File::open(path).map_err(|e| format!("RPA: open {path}: {e}"))?;
    let file_len = f.metadata().map_err(|e| format!("RPA: stat {path}: {e}"))?.len();
    if file_len < 8 {
        return Err("RPA: file is too small to be an archive".to_string());
    }
    let mut head = [0u8; 8];
    f.read_exact(&mut head).map_err(|e| format!("RPA: read header: {e}"))?;

    // Read the header line from the very start: `header_fields` then yields
    // [magic, …] for every variant, so the field indices below are uniform.
    let (version, index_at, key): (&'static str, u64, u64) = if head == *b"RPA-3.0 " || head == *b"RPA-3.2 " || head == *b"RPA-4.0 " {
        f.seek(SeekFrom::Start(0)).map_err(|e| format!("RPA: seek: {e}"))?;
        let line = read_at_most(&mut f, 64)?;
        let fields = header_fields(&line);
        if fields.len() < 3 {
            return Err("RPA: truncated 3.x header".to_string());
        }
        let v = match &head {
            h if *h == *b"RPA-3.2 " => "RPA-3.2",
            h if *h == *b"RPA-4.0 " => "RPA-4.0",
            _ => "RPA-3.0",
        };
        (v, hex_u64(&fields[1], "index offset")?, hex_u64(&fields[2], "key")?)
    } else if head == *b"RPA-2.0 " {
        f.seek(SeekFrom::Start(0)).map_err(|e| format!("RPA: seek: {e}"))?;
        let line = read_at_most(&mut f, 32)?;
        let fields = header_fields(&line);
        if fields.len() < 2 {
            return Err("RPA: truncated 2.0 header".to_string());
        }
        ("RPA-2.0", hex_u64(&fields[1], "index offset")?, 0)
    } else if head == *b"ALT-1.0 " {
        // unrpa's ALT1: the header holds `key ^ 0xDABE8DF0` then the offset,
        // i.e. the opposite order to RPA-3.0.
        f.seek(SeekFrom::Start(0)).map_err(|e| format!("RPA: seek: {e}"))?;
        let line = read_at_most(&mut f, 48)?;
        let fields = header_fields(&line);
        if fields.len() < 3 {
            return Err("RPA: truncated ALT-1.0 header".to_string());
        }
        ("ALT-1.0", hex_u64(&fields[2], "index offset")?, hex_u64(&fields[1], "key")? ^ ALT_EXTRA_KEY)
    } else if head[0] == 0x78 && head[1] == 0x9c {
        // RPA-1.0 (`.rpi`): the file *is* the index stream, followed by data.
        ("RPA-1.0", 0, 0)
    } else if head.starts_with(b"ZiX-12A") || head.starts_with(b"ZiX-12B") {
        return Err("RPA: ZiX-12A/B archives need the game's renpy/loader.pyo to derive the key — not supported".to_string());
    } else {
        return Err("RPA: not an RPA archive (unrecognised header)".to_string());
    };

    let idx = read_index(&mut f, index_at)?;
    let value = pickle::loads(&idx)?;
    let entries = build_entries(value, key, file_len)?;
    Ok(Rpa { version, key, entries, file_len })
}

// ─── listing ───

/// JSON contract shared with every other format:
/// `[{"n":name,"s":size,"d":isDir,"e":encrypted}]`.
pub fn rpa_list(path: &str) -> Result<String, String> {
    let rpa = open_rpa(path)?;
    let names: Vec<&str> = rpa.entries.iter().map(|e| e.name.as_str()).collect();
    let dirs = derive_dirs(&names);
    let mut all: Vec<(String, u64, bool)> = Vec::new();
    for d in &dirs {
        all.push((d.clone(), 0, true));
    }
    for e in &rpa.entries {
        all.push((e.name.clone(), e.size, false));
    }
    all.sort_by(|a, b| a.0.cmp(&b.0));
    let items: Vec<String> = all
        .iter()
        .map(|(n, sz, d)| format!(r#"{{"n":"{}","s":{},"d":{},"e":false}}"#, json_escape(n), if *d { 0 } else { *sz }, d))
        .collect();
    Ok(format!("[{}]", items.join(",")))
}

// ─── extraction ───

fn select<'a>(rpa: &'a Rpa, sel: &str) -> Vec<&'a RpaEntry> {
    let set: std::collections::HashSet<&str> = sel.lines().filter(|l| !l.is_empty()).collect();
    rpa.entries
        .iter()
        .filter(|e| set.contains(e.name.as_str()) || set.iter().any(|d| e.name.starts_with(&format!("{d}/"))))
        .collect()
}

fn write_entry(f: &mut File, e: &RpaEntry, out: &str, alloc: &mut DestAllocator) -> Result<(), String> {
    if let Some(reason) = &e.broken {
        return Err(reason.clone());
    }
    let dest = alloc.allocate(safe_join(out, &e.name)?);
    if let Some(p) = dest.parent() {
        std::fs::create_dir_all(p).map_err(|er| format!("RPA: {}: {er}", p.display()))?;
    }
    let file = File::create(&dest).map_err(|er| format!("RPA: {}: {er}", dest.display()))?;
    let mut w = ProgressWriter::extract(BufWriter::with_capacity(256 * 1024, file));
    let mut buf = vec![0u8; 256 * 1024];
    let res = (|| -> Result<(), String> {
        for part in &e.parts {
            match part {
                RpaPart::Inline(b) => w.write_all(b).map_err(|er| format!("RPA: write: {er}"))?,
                RpaPart::Chunk { offset, len } => {
                    f.seek(SeekFrom::Start(*offset)).map_err(|er| format!("RPA: seek: {er}"))?;
                    let mut left = *len;
                    while left > 0 {
                        let want = left.min(buf.len() as u64) as usize;
                        let n = f.read(&mut buf[..want]).map_err(|er| format!("RPA: read: {er}"))?;
                        if n == 0 {
                            return Err(format!("RPA: {}: unexpected end of archive", e.name));
                        }
                        w.write_all(&buf[..n]).map_err(|er| format!("RPA: write: {er}"))?;
                        left -= n as u64;
                    }
                }
            }
        }
        Ok(())
    })();
    if let Err(er) = res {
        // A decoder that can fail must not leave a plausible-looking truncated
        // file behind: the user would keep it, and so would any later tool.
        drop(w);
        let _ = std::fs::remove_file(&dest);
        return Err(er);
    }
    w.flush().map_err(|er| format!("RPA: flush: {er}"))?;
    Ok(())
}

/// Extracts exactly the entries it is handed. Returns `(attempted, failed)`.
///
/// Entries the index declared out of bounds are not attempted: they are
/// counted as failures and left out of the progress total, so a corrupt entry
/// cannot leave the bar stuck below 100% for the whole run.
fn extract_entries(input: &str, entries: &[&RpaEntry], out: &str) -> Result<(u32, u32), String> {
    std::fs::create_dir_all(out).map_err(|e| format!("RPA: create {out}: {e}"))?;
    let mut file = File::open(input).map_err(|e| format!("RPA: open {input}: {e}"))?;
    let attempt: Vec<&RpaEntry> = entries.iter().copied().filter(|e| e.broken.is_none()).collect();
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
        if write_entry(&mut file, e, out, &mut alloc).is_err() {
            fail += 1;
        }
    }
    Ok((entries.len() as u32, fail))
}

/// Host entry point (also the out-of-tree harness' API): extract everything.
#[doc(hidden)]
pub fn extract_rpa_host(input: &str, output: &str) -> Result<(u32, u32), String> {
    let rpa = open_rpa(input)?;
    let all: Vec<&RpaEntry> = rpa.entries.iter().collect();
    extract_entries(input, &all, output)
}

/// Host entry point: extract the newline-separated selection (exact names plus
/// `dir/` prefixes, same contract as every other format).
#[doc(hidden)]
pub fn extract_rpa_selected_host(input: &str, output: &str, sel: &str) -> Result<(u32, u32), String> {
    if sel.lines().all(|l| l.is_empty()) {
        return Ok((0, 0));
    }
    let rpa = open_rpa(input)?;
    let picked = select(&rpa, sel);
    extract_entries(input, &picked, output)
}

// ─── JNI ───
// Wrapped in `guarded` so a panic on a malicious index cannot cross the JNI
// boundary and kill the process.

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RpaCore_rpaListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i);
    match guarded(move || rpa_list(&inp)) {
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
pub extern "system" fn Java_com_usefulunpacker_RpaCore_rpaExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i);
    let out = s(&mut e, &o);
    match guarded(move || extract_rpa_host(&inp, &out)) {
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
pub extern "system" fn Java_com_usefulunpacker_RpaCore_rpaExtractSelected(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel_j: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i);
    let out = s(&mut e, &o);
    let sel = s(&mut e, &sel_j);
    match guarded(move || extract_rpa_selected_host(&inp, &out, &sel)) {
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
pub extern "system" fn Java_com_usefulunpacker_RpaCore_rpaExtractProgressCount(_: JNIEnv, _: JClass) -> jlong {
    extract_progress::bytes() as jlong
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RpaCore_rpaExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong {
    extract_progress::total_bytes() as jlong
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RpaCore_rpaExtractProgressFileCount(_: JNIEnv, _: JClass) -> jlong {
    extract_progress::file_bytes() as jlong
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RpaCore_rpaExtractProgressFileTotal(_: JNIEnv, _: JClass) -> jlong {
    extract_progress::file_total() as jlong
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RpaCore_rpaExtractProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|v| v.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RpaCore_rpaExtractCancel(_: JNIEnv, _: JClass) {
    extract_progress::cancel();
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RpaCore_rpaCreateArchive(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, _level: JString) -> jboolean {
    compress_progress::clear_cancel();
    let inp = s(&mut e, &i);
    let out = s(&mut e, &o);
    match guarded(move || rpa_create_archive(&inp, &out)) {
        Ok(_) => JNI_TRUE,
        Err(er) => {
            let _ = e.throw_new("java/io/IOException", format!("rpaCreateArchive: {er}"));
            JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RpaCore_rpaCompressProgressCount(_: JNIEnv, _: JClass) -> jlong {
    compress_progress::bytes() as jlong
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RpaCore_rpaCompressProgressTotal(_: JNIEnv, _: JClass) -> jlong {
    compress_progress::total_bytes() as jlong
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RpaCore_rpaCompressProgressFileCount(_: JNIEnv, _: JClass) -> jlong {
    compress_progress::file_bytes() as jlong
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RpaCore_rpaCompressProgressFileTotal(_: JNIEnv, _: JClass) -> jlong {
    compress_progress::file_total() as jlong
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RpaCore_rpaCompressProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&compress_progress::name()).map(|v| v.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RpaCore_rpaCompressCancel(_: JNIEnv, _: JClass) {
    compress_progress::cancel();
}

// ─── packing ───

/// The fixed key Ren'Py's own archiver uses. It is obfuscation, not
/// encryption — the value sits in the header in plain text — and matching the
/// reference writer keeps our archives indistinguishable from the SDK's.
const WRITE_KEY: u64 = 0x42424242;

/// Per-entry filler, byte-for-byte what `archiver.rpy` writes. Ren'Py's reader
/// seeks straight past it; it exists so a partially-optimized repack still
/// looks like a normal archive to a hex editor.
const ENTRY_PADDING: &[u8] = b"Made with Ren'Py.";

/// A single file packs as itself; a directory packs as its tree, names
/// relative and sorted (a stable order is what makes our output
/// reproducible).
fn collect_files(base: &Path) -> Result<Vec<(PathBuf, String)>, String> {
    let mut out = Vec::new();
    if base.is_file() {
        let name = base.file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
        if name.is_empty() {
            return Err("RPA: empty filename".to_string());
        }
        out.push((base.to_path_buf(), name));
        return Ok(out);
    }
    let mut stack = vec![(base.to_path_buf(), String::new())];
    while let Some((dir, rel)) = stack.pop() {
        let mut entries: Vec<_> = std::fs::read_dir(&dir)
            .map_err(|e| format!("RPA: read_dir {}: {e}", dir.display()))?
            .collect::<Result<_, _>>()
            .map_err(|e| format!("RPA: read_dir {}: {e}", dir.display()))?;
        entries.sort_by_key(|e| e.file_name());
        for entry in entries {
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().to_string();
            let child_rel = if rel.is_empty() { name.clone() } else { format!("{rel}/{name}") };
            let meta = entry.metadata().map_err(|e| format!("RPA: metadata {}: {e}", path.display()))?;
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

/// Packs `input` (a file or a directory) into an RPA archive at `output`.
///
/// Caliber: the compress bar counts **source bytes**, so it reaches exactly
/// 100% (an RPA stores payloads raw, so source and destination sizes agree
/// anyway — but the padding and the index still mean they are not identical).
pub fn rpa_create_archive(input: &str, output: &str) -> Result<u32, String> {
    let files = collect_files(Path::new(input))?;
    if files.is_empty() {
        return Err("RPA: nothing to pack".to_string());
    }
    let total: u64 = files.iter().map(|(p, _)| std::fs::metadata(p).map(|m| m.len()).unwrap_or(0)).sum();
    compress_progress::reset(total);

    let res = (|| -> Result<u32, String> {
        let mut out = std::io::BufWriter::with_capacity(256 * 1024, File::create(output).map_err(|e| format!("RPA: create {output}: {e}"))?);
        // Placeholder header, rewritten once the index offset is known.
        let placeholder = format!("RPA-3.0 {:016x} {:08x}\n", 0, WRITE_KEY);
        out.write_all(placeholder.as_bytes()).map_err(|e| format!("RPA: write header: {e}"))?;
        let mut index: Vec<(String, u64, u64)> = Vec::with_capacity(files.len());
        let mut buf = vec![0u8; 256 * 1024];

        for (path, name) in &files {
            if compress_progress::cancelled() {
                return Err("cancelled".to_string());
            }
            compress_progress::set_name(name);
            let size = std::fs::metadata(path).map_err(|e| format!("RPA: stat {}: {e}", path.display()))?.len();
            compress_progress::set_file(size);
            out.write_all(ENTRY_PADDING).map_err(|e| format!("RPA: write padding: {e}"))?;
            let offset = out.stream_position().map_err(|e| format!("RPA: tell: {e}"))?;
            let mut src = std::io::BufReader::with_capacity(256 * 1024, File::open(path).map_err(|e| format!("RPA: open {}: {e}", path.display()))?);
            let mut written = 0u64;
            loop {
                if compress_progress::cancelled() {
                    return Err("cancelled".to_string());
                }
                let n = src.read(&mut buf).map_err(|e| format!("RPA: read {}: {e}", path.display()))?;
                if n == 0 {
                    break;
                }
                out.write_all(&buf[..n]).map_err(|e| format!("RPA: write: {e}"))?;
                written += n as u64;
                compress_progress::add_bytes(n as u64);
            }
            if written != size {
                return Err(format!("RPA: {} changed size while packing", path.display()));
            }
            index.push((name.clone(), offset ^ WRITE_KEY, written ^ WRITE_KEY));
        }

        let index_offset = out.stream_position().map_err(|e| format!("RPA: tell: {e}"))?;
        let mut enc = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::default());
        enc.write_all(&pickle::dumps_index(&index)).map_err(|e| format!("RPA: index: {e}"))?;
        let packed = enc.finish().map_err(|e| format!("RPA: index: {e}"))?;
        out.write_all(&packed).map_err(|e| format!("RPA: index: {e}"))?;
        out.flush().map_err(|e| format!("RPA: flush: {e}"))?;
        drop(out);

        let mut f = std::fs::OpenOptions::new().write(true).open(output).map_err(|e| format!("RPA: reopen {output}: {e}"))?;
        f.seek(SeekFrom::Start(0)).map_err(|e| format!("RPA: seek: {e}"))?;
        let header = format!("RPA-3.0 {:016x} {:08x}\n", index_offset, WRITE_KEY);
        debug_assert_eq!(header.len(), placeholder.len(), "the header must be rewritten in place");
        f.write_all(header.as_bytes()).map_err(|e| format!("RPA: header: {e}"))?;
        Ok(files.len() as u32)
    })();

    if res.is_err() {
        // Packing is all-or-nothing: a half-written archive that still parses
        // would be worse than none at all.
        let _ = std::fs::remove_file(output);
    }
    res
}

#[doc(hidden)]
pub fn create_rpa_host(input: &str, output: &str) -> Result<u32, String> {
    rpa_create_archive(input, output)
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

pub use pickle::Value as PickleValue;

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture(name: &str) -> Vec<u8> {
        let p = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("testdata").join(name);
        std::fs::read(&p).unwrap_or_else(|e| panic!("{}: {e}", p.display()))
    }

    fn with_tmp<T>(tag: &str, f: impl FnOnce(&std::path::Path) -> T) -> T {
        let dir = std::env::temp_dir().join(format!("uu_rpa_{}_{}", tag, std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let r = f(&dir);
        let _ = std::fs::remove_dir_all(&dir);
        r
    }

    /// The fixture is a real archive built by Ren'Py 8.5.3's SDK; these are its
    /// contents, verified against `unrpa` and the official `renpy/loader.py`
    /// logic (see testdata/README.md).
    #[test]
    fn official_fixture_index_matches_the_official_writer() {
        with_tmp("official_index", |dir| {
            let p = dir.join("archive.rpa");
            std::fs::write(&p, fixture("official-renpy-8.5.3.rpa")).unwrap();
            let rpa = open_rpa(p.to_str().unwrap()).unwrap();
            assert_eq!(rpa.version, "RPA-3.0");
            assert_eq!(rpa.key, 0x42424242, "the official writer's fixed key");
            let names: Vec<&str> = rpa.entries.iter().map(|e| e.name.as_str()).collect();
            for want in ["empty.bin", "hello.txt", "with space.txt", "中文 名字.txt", "sub/deep/nested.dat", "small.bin"] {
                assert!(names.contains(&want), "missing {want} in {names:?}");
            }
            let hello = rpa.entries.iter().find(|e| e.name == "hello.txt").unwrap();
            assert_eq!(hello.size, 12);
            assert_eq!(hello.parts.len(), 1, "3-tuple with an empty start is a plain chunk");
            assert!(hello.broken.is_none());
            let empty = rpa.entries.iter().find(|e| e.name == "empty.bin").unwrap();
            assert_eq!(empty.size, 0);
            let cjk = rpa.entries.iter().find(|e| e.name == "中文 名字.txt").unwrap();
            assert_eq!(cjk.size, 19);
        });
    }

    /// rpatool is a second, independent writer: protocol 2, 2-tuples (no
    /// `start` field at all) and — because its default key is 0xDEADBEEF —
    /// `LONG1`-encoded values in the index.
    #[test]
    fn rpatool_fixture_round_trips_names_and_sizes() {
        with_tmp("rpatool", |dir| {
            let p = dir.join("a.rpa");
            std::fs::write(&p, fixture("rpatool-v3-deadbeef.rpa")).unwrap();
            let rpa = open_rpa(p.to_str().unwrap()).unwrap();
            assert_eq!(rpa.key, 0xDEADBEEF);
            assert_eq!(rpa.entries.iter().map(|e| e.name.as_str()).collect::<Vec<_>>().len(), 6);
            for e in &rpa.entries {
                assert!(e.broken.is_none(), "{}: {:?}", e.name, e.broken);
            }
        });
    }

    #[test]
    fn rpatool_v2_fixture_has_no_xor() {
        with_tmp("rpatool2", |dir| {
            let p = dir.join("a.rpa");
            std::fs::write(&p, fixture("rpatool-v2.rpa")).unwrap();
            let rpa = open_rpa(p.to_str().unwrap()).unwrap();
            assert_eq!(rpa.version, "RPA-2.0");
            assert_eq!(rpa.key, 0);
            assert!(!rpa.entries.is_empty());
        });
    }

    /// RPA-1.0 keeps the index at the *front*, so truncating the file leaves a
    /// readable index whose chunks point past EOF — the bounds check is what
    /// stands between that and a silently truncated file on disk.
    #[test]
    fn truncated_v1_marks_entries_broken_and_writes_nothing_for_them() {
        with_tmp("truncated", |dir| {
            let p = dir.join("t.rpi");
            std::fs::write(&p, fixture("truncated-v1.rpi")).unwrap();
            let rpa = open_rpa(p.to_str().unwrap()).unwrap();
            assert_eq!(rpa.version, "RPA-1.0");
            let broken: Vec<&str> = rpa.entries.iter().filter(|e| e.broken.is_some()).map(|e| e.name.as_str()).collect();
            let ok: Vec<&str> = rpa.entries.iter().filter(|e| e.broken.is_none()).map(|e| e.name.as_str()).collect();
            assert!(!broken.is_empty() && !ok.is_empty(), "fixture must straddle the truncation point (ok={ok:?} broken={broken:?})");

            let out = dir.join("out");
            let rpa2 = open_rpa(p.to_str().unwrap()).unwrap();
            let all: Vec<&RpaEntry> = rpa2.entries.iter().collect();
            let (total, fail) = extract_entries(p.to_str().unwrap(), &all, out.to_str().unwrap()).unwrap();
            assert_eq!(total, rpa.entries.len() as u32);
            assert_eq!(fail as usize, broken.len(), "every out-of-bounds entry must be reported as a failure");
            // The point of the bounds check: a chunk that runs past EOF must not
            // leave a plausible-looking truncated file behind.
            for name in &broken {
                assert!(!out.join(name).exists(), "broken entry {name} left a file on disk");
            }
            for name in &ok {
                assert!(out.join(name).exists(), "extractable entry {name} is missing");
            }
        });
    }

    #[test]
    fn extracting_the_official_fixture_reproduces_its_bytes() {
        with_tmp("extract", |dir| {
            let p = dir.join("archive.rpa");
            std::fs::write(&p, fixture("official-renpy-8.5.3.rpa")).unwrap();
            let out = dir.join("out");
            let (total, fail) = extract_rpa_host(p.to_str().unwrap(), out.to_str().unwrap()).unwrap();
            assert_eq!(fail, 0, "nothing in the real fixture may fail");
            assert_eq!(total, 12);
            assert_eq!(std::fs::read(out.join("hello.txt")).unwrap(), b"hello world\n");
            assert_eq!(std::fs::read(out.join("empty.bin")).unwrap(), b"");
            assert_eq!(std::fs::read(out.join("with space.txt")).unwrap(), b"spaced\n");
            assert_eq!(std::fs::read(out.join("sub/deep/nested.dat")).unwrap().len(), 1024);
            assert_eq!(std::fs::read(out.join("中文 名字.txt")).unwrap().len(), 19);
            // Every declared size must match what actually landed on disk.
            let rpa = open_rpa(p.to_str().unwrap()).unwrap();
            for e in &rpa.entries {
                let p2 = out.join(&e.name);
                let len = std::fs::metadata(&p2).unwrap_or_else(|er| panic!("{}: {er}", p2.display())).len();
                assert_eq!(len, e.size, "{}: size mismatch", e.name);
            }
        });
    }

    #[test]
    fn selected_extraction_covers_exact_names_and_dir_prefixes() {
        with_tmp("selected", |dir| {
            let p = dir.join("archive.rpa");
            std::fs::write(&p, fixture("official-renpy-8.5.3.rpa")).unwrap();
            let out = dir.join("out");
            let (total, fail) = extract_rpa_selected_host(p.to_str().unwrap(), out.to_str().unwrap(), "hello.txt\nsub/deep\n").unwrap();
            assert_eq!(fail, 0);
            assert_eq!(total, 2, "one exact name + one under the directory prefix");
            assert!(out.join("hello.txt").exists());
            assert!(out.join("sub/deep/nested.dat").exists());
            assert!(!out.join("small.bin").exists());
        });
    }

    #[test]
    fn non_utf8_names_are_refused_not_mangled() {
        // A py2-era name cannot even be written without GLOBAL, which the
        // pickle layer refuses by name; this asserts the *message*, since a
        // silent fallback would put mojibake on the user's disk.
        let dir = std::env::temp_dir().join(format!("uu_rpa_badname_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let p = dir.join("a.rpa");
        // RPA-3.0 header + integrity-preserving zlib of a pickle that carries a
        // bytes-key dict (protocol 2, no classes) — the name decodes as UTF-8
        // only if the writer meant it to.
        let mut f = std::fs::File::create(&p).unwrap();
        let pickle = [0x80u8, 0x02, 0x7d, 0x71, 0x00, 0x43, 0x02, 0xff, 0xfe, 0x71, 0x01, 0x5d, 0x71, 0x02, 0x4b, 0x01, 0x4b, 0x01, 0x86, 0x71, 0x03, 0x61, 0x73, 0x2e];
        let mut enc = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::default());
        enc.write_all(&pickle).unwrap();
        let idx = enc.finish().unwrap();
        // Index goes *after* the 34-byte header, and the header's offset field
        // must say so.
        let head = format!("RPA-3.0 {:016x} {:08x}\n", 34, 0u32);
        f.write_all(head.as_bytes()).unwrap();
        f.write_all(&idx).unwrap();
        drop(f);
        let err = open_rpa(p.to_str().unwrap()).unwrap_err();
        assert!(err.contains("not valid UTF-8"), "got: {err}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A short header read must not be mistaken for a truncated header. (The
    /// helper loops; here we can at least pin its EOF behaviour and that a
    /// header-only file still parses its fields.)
    #[test]
    fn header_reads_survive_short_files() {
        with_tmp("hdr", |dir| {
            // 34-byte header with no payload at all: index offset points at EOF.
            let p = dir.join("h.rpa");
            std::fs::write(&p, b"RPA-3.0 0000000000000022 42424242\n").unwrap();
            let err = open_rpa(p.to_str().unwrap()).unwrap_err();
            assert!(err.contains("index"), "expected an index complaint, got: {err}");

            // A header split across the probe window still yields all fields.
            let mut f = File::open(&p).unwrap();
            let line = read_at_most(&mut f, 64).unwrap();
            assert_eq!(line.len(), 34);
            let fields = header_fields(&line);
            assert_eq!(fields.len(), 3);
            assert_eq!(fields[1], b"0000000000000022");
        });
    }

    /// Deterministic mutation fuzzer over the real fixtures: whatever bytes the
    /// user hands us, `open_rpa` must return Ok or a message — never a panic and
    /// never an unbounded walk.
    fn mutate(base: &[u8], seed: u64, i: usize) -> Vec<u8> {
        let mut r = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407 + i as u64);
        let mut next = |n: usize| -> usize {
            r = r.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            ((r >> 33) as usize) % n.max(1)
        };
        let mut d = base.to_vec();
        match i % 4 {
            0 => {
                let at = next(d.len());
                d[at] ^= 1 << (i % 8);
            }
            1 => d.truncate(next(d.len())),
            2 => {
                let at = next(d.len());
                let len = 1 + next(16);
                for k in 0..len.min(d.len() - at) {
                    d[at + k] = (i.wrapping_mul(17) & 0xFF) as u8;
                }
            }
            _ => {
                let at = next(d.len());
                d.insert(at, (i & 0xFF) as u8);
            }
        }
        d
    }

    #[test]
    fn mutated_archives_never_panic() {
        with_tmp("fuzz", |dir| {
            let p = dir.join("m.rpa");
            for name in ["official-renpy-8.5.3.rpa", "rpatool-v3-deadbeef.rpa", "v1.rpi", "truncated-v1.rpi"] {
                let base = fixture(name);
                for i in 0..300 {
                    std::fs::write(&p, mutate(&base, 0x2545_F491_4F6C_DD1D, i)).unwrap();
                    if let Ok(rpa) = open_rpa(p.to_str().unwrap()) {
                        // Anything that parsed must stay inside the file.
                        for e in &rpa.entries {
                            if e.broken.is_none() {
                                for part in &e.parts {
                                    if let RpaPart::Chunk { offset, len } = part {
                                        assert!(offset + len <= rpa.file_len, "{name}: {e:?} escaped the file");
                                    }
                                }
                            }
                        }
                        let _ = rpa_list(p.to_str().unwrap());
                    }
                }
            }
        });
    }

    /// The pickle reader gets its own, more targeted fuzzing: the real index is
    /// decompressed, mutated in place and re-injected, so the mutations land on
    /// the opcode stream rather than on the payload.
    #[test]
    fn mutated_pickle_indexes_never_panic() {
        with_tmp("fuzzpickle", |dir| {
            let p = dir.join("m.rpa");
            let base = fixture("official-renpy-8.5.3.rpa");
            let index_at = usize::from_str_radix(std::str::from_utf8(&base[8..24]).unwrap(), 16).unwrap();
            let mut z = flate2::read::ZlibDecoder::new(&base[index_at..]);
            let mut idx = Vec::new();
            std::io::Read::read_to_end(&mut z, &mut idx).unwrap();
            assert!(!idx.is_empty());

            for i in 0..400 {
                let m = mutate(&idx, 0x9E37_79B9_7F4A_7C15, i);
                if m.is_empty() {
                    continue;
                }
                let mut enc = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::fast());
                std::io::Write::write_all(&mut enc, &m).unwrap();
                let packed = enc.finish().unwrap();
                let mut file = base[..index_at].to_vec();
                file.extend_from_slice(&packed);
                std::fs::write(&p, &file).unwrap();
                let _ = open_rpa(p.to_str().unwrap());
            }
        });
    }

    #[test]
    fn non_archives_are_rejected_by_name() {
        with_tmp("badhead", |dir| {
            let p = dir.join("x.zip");
            std::fs::write(&p, b"PK\x03\x04not an rpa").unwrap();
            let err = open_rpa(p.to_str().unwrap()).unwrap_err();
            assert!(err.contains("not an RPA archive"), "got: {err}");

            let z = dir.join("z.rpa");
            std::fs::write(&z, b"ZiX-12B 00112233").unwrap();
            let err = open_rpa(z.to_str().unwrap()).unwrap_err();
            assert!(err.contains("loader.pyo"), "got: {err}");

            let short = dir.join("s.rpa");
            std::fs::write(&short, b"RPA").unwrap();
            let err = open_rpa(short.to_str().unwrap()).unwrap_err();
            assert!(err.contains("too small"), "got: {err}");

            // A 3.x header whose index offset points at garbage.
            let bad = dir.join("g.rpa");
            std::fs::write(&bad, b"RPA-3.0 0000000000000000 42424242\nnot zlib at all").unwrap();
            let err = open_rpa(bad.to_str().unwrap()).unwrap_err();
            assert!(err.contains("zlib"), "got: {err}");
        });
    }

    /// What we write must be readable by us — including the case that matters
    /// in the field: a packed tree with CJK names, spaces, a nested path and an
    /// empty file round-trips byte-for-byte. (Readability by *other* readers is
    /// checked out-of-tree; see testdata/README.md.)
    #[test]
    fn packed_archive_round_trips_byte_for_byte() {
        with_tmp("pack", |dir| {
            let src = dir.join("src");
            std::fs::create_dir_all(src.join("sub/deep")).unwrap();
            std::fs::write(src.join("hello.txt"), b"hello world\n").unwrap();
            std::fs::write(src.join("empty.bin"), b"").unwrap();
            std::fs::write(src.join("with space.txt"), b"spaced\n").unwrap();
            std::fs::write(src.join("中文 名字.txt"), "中文内容\n".as_bytes()).unwrap();
            std::fs::write(src.join("sub/deep/nested.dat"), vec![7u8; 4096]).unwrap();

            let out = dir.join("packed.rpa");
            let n = rpa_create_archive(src.to_str().unwrap(), out.to_str().unwrap()).unwrap();
            assert_eq!(n, 5);

            // The header must point at the index and carry the reference key.
            let head = std::fs::read(&out).unwrap();
            assert!(head.starts_with(b"RPA-3.0 "));
            let rpa = open_rpa(out.to_str().unwrap()).unwrap();
            assert_eq!(rpa.version, "RPA-3.0");
            assert_eq!(rpa.key, 0x42424242);
            assert_eq!(rpa.entries.len(), 5);
            assert!(rpa.entries.iter().all(|e| e.broken.is_none() && e.parts.len() == 1), "we write the 2-tuple direct form");

            let back = dir.join("out");
            let (total, fail) = extract_rpa_host(out.to_str().unwrap(), back.to_str().unwrap()).unwrap();
            assert_eq!((total, fail), (5, 0));
            for rel in ["hello.txt", "empty.bin", "with space.txt", "中文 名字.txt", "sub/deep/nested.dat"] {
                let a = std::fs::read(src.join(rel)).unwrap();
                let b = std::fs::read(back.join(rel)).unwrap_or_else(|e| panic!("{rel}: {e}"));
                assert_eq!(a, b, "{rel} differs after a round trip");
            }
        });
    }

    #[test]
    fn packing_a_single_file_names_it_after_itself() {
        with_tmp("pack1", |dir| {
            let f = dir.join("one.png");
            std::fs::write(&f, b"png-ish").unwrap();
            let out = dir.join("one.rpa");
            assert_eq!(rpa_create_archive(f.to_str().unwrap(), out.to_str().unwrap()).unwrap(), 1);
            let rpa = open_rpa(out.to_str().unwrap()).unwrap();
            assert_eq!(rpa.entries[0].name, "one.png");
        });
    }

    #[test]
    fn a_failed_pack_leaves_no_archive_behind() {
        with_tmp("packfail", |dir| {
            let out = dir.join("nope.rpa");
            let err = rpa_create_archive(dir.join("missing").to_str().unwrap(), out.to_str().unwrap()).unwrap_err();
            assert!(err.contains("read_dir"), "got: {err}");
            assert!(!out.exists(), "a failed pack must not leave a half-written archive");
        });
    }

    fn walk(dir: &std::path::Path) -> Vec<String> {
        let mut out = Vec::new();
        let mut stack = vec![dir.to_path_buf()];
        while let Some(d) = stack.pop() {
            if let Ok(rd) = std::fs::read_dir(&d) {
                for e in rd.flatten() {
                    let p = e.path();
                    if p.is_dir() {
                        stack.push(p);
                    } else {
                        out.push(p.display().to_string());
                    }
                }
            }
        }
        out.sort();
        out
    }

    /// The names on disk are the archive's names, except where `DestAllocator`
    /// had to rename a case-only collision. This fixture has none, so the two
    /// must agree exactly — and the helper above is what makes that checkable.
    #[test]
    fn extracted_names_match_the_index_exactly() {
        with_tmp("names", |dir| {
            let p = dir.join("archive.rpa");
            std::fs::write(&p, fixture("official-renpy-8.5.3.rpa")).unwrap();
            let out = dir.join("out");
            extract_rpa_host(p.to_str().unwrap(), out.to_str().unwrap()).unwrap();
            let got: Vec<String> = walk(&out)
                .into_iter()
                .map(|s| s.trim_start_matches(&format!("{}/", out.display())).to_string())
                .collect();
            let rpa = open_rpa(p.to_str().unwrap()).unwrap();
            let mut want: Vec<String> = rpa.entries.iter().map(|e| e.name.clone()).collect();
            want.sort();
            assert_eq!(got, want);
        });
    }
}
