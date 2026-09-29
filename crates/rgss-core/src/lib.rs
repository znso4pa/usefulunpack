//! RPG Maker RGSS encrypted archives (`Game.rgssad` / `Game.rgss2a` / `Game.rgss3a`).
//!
//! Layout (verified against uuksu/RPGMakerDecrypter, mkxp-z `crypto/rgssad.cpp`
//! and RPG-Maker-Translation-Tools/rpgm-archive-decrypter-lib — no equivalent
//! crate exists on crates.io, so everything here is self-contained):
//!
//! ```text
//! header:  "RGSSAD\0" | u8 version          (1 = XP, 2 = VX, 3 = VX Ace)
//!
//! v1/v2 (XP, VX) — sequential stream, no index table, key = 0xDEADCAFE:
//!   loop until EOF:
//!     name_len: u32, plain = enc ^ key,              key = key*7 + 3
//!     name[]:   per byte  b ^= (key & 0xFF),          key = key*7 + 3
//!     size:     u32, plain = enc ^ key,              key = key*7 + 3
//!     data:     4-byte window, key = key*7 + 3, using the key value from
//!               *after* the `size` field. The data rotation is NOT carried
//!               over — the next entry header resumes from that same key.
//!
//! v3 (VX Ace) — index table + data region:
//!   key = seed*9 + 3                                (seed = u32 at offset 8)
//!   loop: offset = enc ^ key; 0 terminates
//!     offset / size / entry_key / name_len : u32, each ^ key (key never rotates)
//!     name[]: per byte b ^= (key >> (8 * (i % 4))) & 0xFF
//!     ... data blocks live at their own offsets, each XORed with entry_key
//!        using the v1 data rule (key*7 + 3 per 4 bytes)
//!   The official packer writes 3 more dwords after the terminator; readers
//!   stop at the zero offset, so we do not emit them.
//! ```
//!
//! Archive paths use `\` inside the archive and are normalized to `/` on read.
//!
//! # RPG Maker MV / MZ loose assets
//!
//! The same engine family, but not archives: MV ships every picture and sound
//! as a separate obfuscated file instead of packing them into one container.
//! The scheme is much weaker than RGSS (uuksu `RPGMakerDecrypter.MVMZ` and
//! [rpgm-asset-decrypter-lib](https://github.com/RPG-Maker-Translation-Tools/
//! rpgm-asset-decrypter-lib), a Rust rewrite of Petschko's decrypter, agree):
//!
//! ```text
//! offset 0..16   "RPGMV" + three NULs + 00 03 01 + zeros   (the RPGM header)
//! offset 16..32  the source asset's first 16 bytes, XOR keystream
//! offset 32..    the source asset, untouched
//! ```
//!
//! So it is not a cipher at all: 16 bytes of `keystream ^ file_header`, where
//! `keystream` is the MD5 of the `encryptionKey` string from
//! `www/data/System.json` (which almost nobody sets, so in practice it is the
//! MD5 of the empty string). Everything past offset 32 is plaintext already.
//!
//! Because the 16-byte window is tiny and the plaintext header of each asset
//! type is known, the keystream is recovered **from the file itself** — no
//! `System.json` sidecar, no MD5, and nothing to brute-force:
//!   - `.rpgmvp` (PNG): the 16-byte header is a hard PNG constant. Exact.
//!   - `.rpgmvo` (Ogg): 14 of 16 bytes are fixed; the last two are the low
//!     bytes of the stream serial number, read from the **second** page (which
//!     is plaintext) since the serial is constant across pages. Exact.
//!   - `.rpgmvm` (M4A): 12 of 16 bytes are known; the `ftyp` box size and the
//!     major brand are recovered by scanning for the next box. Heuristic, so it
//!     is validated before anything is written.
//!
//! Each file is presented to the app as a ONE-ENTRY archive whose single member
//! is the decoded asset, named with its true extension. That reuses every
//! existing flow (preview, selective extract, batch extract, global search)
//! unchanged, and the correct extension is what makes the entry open in the
//! image / audio viewer.
//!
//! It shares this library's `extract_progress` store (and therefore its
//! scheduler slot) with the archive side, the same way `pf6` shares `pfs`'s —
//! `progress_store!` is private to `archive_common`, and two flows from the same
//! engine sharing one slot is the right trade anyway.
//!
//! It shares this library's `extract_progress` store (and therefore its
//! scheduler slot) with the archive side, the same way `pf6` shares `pfs`'s —
//! `progress_store!` is private to `archive_common`, and two flows from the same
//! engine sharing one slot is the right trade anyway.

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jlong};
use archive_common::{s, json_escape, derive_dirs, safe_join, extract_result_json, DestAllocator, ProgressWriter};
use archive_common::{extract_progress, compress_progress};
use std::fs::{self, File};
use std::io::{Read, Seek, SeekFrom, Write};
use std::collections::HashSet;
use std::path::{Path, PathBuf};

const RGSS_MAGIC: &[u8; 7] = b"RGSSAD\0";
/// Key used by every v1/v2 (XP / VX) archive. Third-party packers may have
/// swapped it; the parse is tried against both the rotating and the constant
/// key scheme and the plausible one wins (see `pick_older_entries`).
const V1_KEY: u32 = 0xDEAD_CAFE;
/// Seed written by our v3 packer. Any value works — the reader derives the key
/// from the archive itself — 0 is what the reference implementation emits.
const V3_SEED: u32 = 0;
/// Hostile-index guards. A 512-byte path is far beyond any real RPG Maker asset
/// path; 100k entries is well past the largest shipped archive (~3k).
const MAX_NAME: usize = 512;
const MAX_ENTRIES: usize = 100_000;
const STREAM_CHUNK: usize = 256 * 1024;

#[derive(Clone, Debug)]
pub struct RgssEntry {
    /// Archive path, normalized to `/`.
    pub name: String,
    pub offset: u64,
    pub size: u64,
    /// Key the entry's data blob is XORed with (v1: the running stream key at
    /// the moment the size field was read; v3: the per-entry key field).
    pub data_key: u32,
}

#[derive(Debug)]
pub struct RgssArchive {
    pub version: u8,
    pub entries: Vec<RgssEntry>,
    pub file_size: u64,
}

impl RgssArchive {
    pub fn total_bytes(&self) -> u64 { self.entries.iter().map(|e| e.size).sum() }
}

// ─── key rotation ──────────────────────────

/// The one LCG the whole format is built on: `key = key*7 + 3`.
#[inline]
fn rot7(key: &mut u32) { *key = key.wrapping_mul(7).wrapping_add(3); }

/// v3 derives its master key with a different multiplier: `key = key*9 + 3`.
#[inline]
fn rot9(key: &mut u32) { *key = key.wrapping_mul(9).wrapping_add(3); }

/// Applies the payload cipher: a 4-byte XOR window that advances the key once
/// per window. Symmetric — the same call encrypts and decrypts.
struct PayloadKey { key: u32, bytes: [u8; 4], pos: u8 }

impl PayloadKey {
    fn new(key: u32) -> Self { Self { key, bytes: key.to_le_bytes(), pos: 0 } }
    fn apply(&mut self, buf: &mut [u8]) {
        for b in buf.iter_mut() {
            *b ^= self.bytes[self.pos as usize];
            self.pos += 1;
            if self.pos == 4 { self.pos = 0; rot7(&mut self.key); self.bytes = self.key.to_le_bytes(); }
        }
    }
}

// ─── names ──────────────────────────

/// Decodes an archive path: `\` → `/`, UTF-8 when valid, otherwise CP932
/// (RPG Maker XP/VX store Shift-JIS names), otherwise a lossy ASCII fold so a
/// hostile index can still be shown to the user instead of aborting the listing.
fn decode_name(raw: &[u8]) -> String {
    let norm: Vec<u8> = raw.iter().map(|&b| if b == b'\\' { b'/' } else { b }).collect();
    if let Ok(s) = std::str::from_utf8(&norm) {
        if !s.chars().any(|c| c.is_control()) { return s.to_string(); }
    }
    if let Some(s) = encoding_rs::SHIFT_JIS.decode_without_bom_handling_and_without_replacement(&norm) {
        if !s.is_empty() { return s.into_owned(); }
    }
    norm.iter().map(|&b| if (0x20..=0x7E).contains(&b) { b as char } else { '_' }).collect()
}

// ─── parsing ──────────────────────────

fn read_u32(f: &mut File) -> Result<u32, String> {
    let mut b = [0u8; 4];
    f.read_exact(&mut b).map_err(|e| format!("RGSS: truncated index ({e})"))?;
    Ok(u32::from_le_bytes(b))
}

/// Reads a u32, returning `None` on a clean end-of-file at a 4-byte boundary
/// (v1 archives are terminated by EOF; a short read is corruption).
fn read_u32_eof(f: &mut File) -> Result<Option<u32>, String> {
    let mut b = [0u8; 4];
    let mut n = 0;
    while n < 4 {
        match f.read(&mut b[n..]).map_err(|e| format!("RGSS: read failed ({e})"))? {
            0 => return Ok(None),
            k => n += k,
        }
    }
    Ok(Some(u32::from_le_bytes(b)))
}

/// v1 / v2 index walk. `rotate` selects the documented rotating-key scheme;
/// the constant-key variant exists only as a fallback for third-party packers
/// that swapped the LCG out (see `pick_older_entries`).
fn parse_older(f: &mut File, file_size: u64, rotate: bool) -> Result<Vec<RgssEntry>, String> {
    let mut key = V1_KEY;
    let mut ents: Vec<RgssEntry> = Vec::new();
    f.seek(SeekFrom::Start(8)).map_err(|e| format!("RGSS: seek: {e}"))?;
    loop {
        let cur = f.stream_position().map_err(|e| format!("RGSS: tell: {e}"))?;
        if cur >= file_size { break; }
        if file_size - cur < 8 { return Err("RGSS: truncated v1 entry header".into()); }
        if ents.len() >= MAX_ENTRIES { return Err(format!("RGSS: more than {MAX_ENTRIES} entries")); }

        let raw_len = read_u32(f)?;
        let name_len = raw_len ^ key;
        if rotate { rot7(&mut key); }
        if name_len == 0 || name_len as usize > MAX_NAME {
            return Err(format!("RGSS: implausible v1 name length {name_len}"));
        }
        if file_size - f.stream_position().map_err(|e| format!("RGSS: tell: {e}"))? < name_len as u64 {
            return Err("RGSS: v1 name runs past end of file".into());
        }
        let mut name = vec![0u8; name_len as usize];
        f.read_exact(&mut name).map_err(|e| format!("RGSS: read name: {e}"))?;
        for b in name.iter_mut() {
            *b ^= (key & 0xFF) as u8;
            if rotate { rot7(&mut key); }
        }

        let raw_size = read_u32(f)?;
        let size = (raw_size ^ key) as u64;
        if rotate { rot7(&mut key); }
        let data_key = key;

        let offset = f.stream_position().map_err(|e| format!("RGSS: tell: {e}"))?;
        let end = offset.checked_add(size).ok_or_else(|| "RGSS: v1 entry size overflow".to_string())?;
        if end > file_size { return Err("RGSS: v1 entry data runs past end of file".into()); }
        ents.push(RgssEntry { name: decode_name(&name), offset, size, data_key });
        f.seek(SeekFrom::Start(end)).map_err(|e| format!("RGSS: seek: {e}"))?;
    }
    Ok(ents)
}

fn parse_v3(f: &mut File, file_size: u64) -> Result<Vec<RgssEntry>, String> {
    f.seek(SeekFrom::Start(8)).map_err(|e| format!("RGSS: seek: {e}"))?;
    let mut key = read_u32(f)?;
    rot9(&mut key);
    let key_bytes = key.to_le_bytes();

    let mut ents: Vec<RgssEntry> = Vec::new();
    loop {
        if ents.len() >= MAX_ENTRIES { return Err(format!("RGSS: more than {MAX_ENTRIES} entries")); }
        let offset = match read_u32_eof(f)? {
            Some(v) => (v ^ key) as u64,
            None => return Err("RGSS: v3 index is not terminated".into()),
        };
        if offset == 0 { break; }
        let size = (read_u32(f)? ^ key) as u64;
        let data_key = read_u32(f)? ^ key;
        let name_len = (read_u32(f)? ^ key) as usize;
        if name_len == 0 || name_len > MAX_NAME {
            return Err(format!("RGSS: implausible v3 name length {name_len}"));
        }
        let mut name = vec![0u8; name_len];
        f.read_exact(&mut name).map_err(|e| format!("RGSS: read name: {e}"))?;
        for (i, b) in name.iter_mut().enumerate() { *b ^= key_bytes[i & 3]; }
        let end = offset.checked_add(size).ok_or_else(|| "RGSS: v3 entry size overflow".to_string())?;
        if offset > file_size || end > file_size { return Err("RGSS: v3 entry data runs past end of file".into()); }
        ents.push(RgssEntry { name: decode_name(&name), offset, size, data_key });
    }
    Ok(ents)
}

/// Ranks a parse candidate. Bounds are already validated by the parser, so the
/// only thing left to judge is whether the names look like paths at all.
fn score_older(ents: &[RgssEntry]) -> i64 {
    if ents.is_empty() { return i64::MIN; }
    let mut score = 0i64;
    for e in ents {
        if e.name.is_empty() { return i64::MIN; }
        if e.name.chars().all(|c| !c.is_control()) { score += 2; } else { score -= 2; }
        if e.name.contains('/') { score += 1; }
        if e.name.contains('.') { score += 1; }
    }
    score
}

fn pick_older_entries(f: &mut File, file_size: u64) -> Result<Vec<RgssEntry>, String> {
    let primary = parse_older(f, file_size, true);
    let fallback = parse_older(f, file_size, false);
    let primary_score = primary.as_ref().map(|e| score_older(e)).unwrap_or(i64::MIN);
    let fallback_score = fallback.as_ref().map(|e| score_older(e)).unwrap_or(i64::MIN);
    if primary_score >= fallback_score { primary } else { fallback }
}

pub fn open(input: &str) -> Result<RgssArchive, String> {
    let mut f = File::open(input).map_err(|e| format!("RGSS: open {input}: {e}"))?;
    let file_size = f.metadata().map_err(|e| format!("RGSS: stat {input}: {e}"))?.len();
    if file_size < 8 { return Err("RGSS: file is too small to be an archive".into()); }
    let mut head = [0u8; 8];
    f.read_exact(&mut head).map_err(|e| format!("RGSS: read header: {e}"))?;
    if &head[..7] != RGSS_MAGIC { return Err("RGSS: not an RGSS archive (bad magic)".into()); }
    let version = head[7];
    let entries = match version {
        1 | 2 => pick_older_entries(&mut f, file_size)?,
        3 => parse_v3(&mut f, file_size)?,
        other => return Err(format!("RGSS: unsupported archive version {other}")),
    };
    if entries.is_empty() { return Err("RGSS: archive has no entries".into()); }
    Ok(RgssArchive { version, entries, file_size })
}

// ─── extract ──────────────────────────

fn rgss_extract_one(f: &mut File, e: &RgssEntry, d: &Path, buf: &mut [u8]) -> Result<(), String> {
    if let Some(p) = d.parent() { fs::create_dir_all(p).map_err(|x| format!("RGSS mkdir: {x}"))?; }
    f.seek(SeekFrom::Start(e.offset)).map_err(|x| format!("RGSS seek: {x}"))?;
    let mut out = ProgressWriter::extract(std::io::BufWriter::with_capacity(
        256 * 1024,
        File::create(d).map_err(|x| format!("RGSS create: {x}"))?,
    ));
    let mut cipher = PayloadKey::new(e.data_key);
    let mut left = e.size;
    while left > 0 {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        let want = buf.len().min(left as usize);
        f.read_exact(&mut buf[..want]).map_err(|x| format!("RGSS read: {x}"))?;
        cipher.apply(&mut buf[..want]);
        out.write_all(&buf[..want]).map_err(|x| format!("RGSS write: {x}"))?;
        left -= want as u64;
    }
    out.flush().map_err(|x| format!("RGSS flush: {x}"))?;
    Ok(())
}

fn list_rgss(input: &str) -> Result<String, String> {
    let archive = open(input)?;
    let names: Vec<&str> = archive.entries.iter().map(|e| e.name.as_str()).collect();
    let dirs = derive_dirs(&names);
    let mut all: Vec<(String, u64, bool)> = Vec::with_capacity(archive.entries.len() + dirs.len());
    for d in &dirs { all.push((d.clone(), 0, true)); }
    for e in &archive.entries { all.push((e.name.clone(), e.size, false)); }
    all.sort_by(|a, b| a.0.cmp(&b.0));
    let items: Vec<String> = all.iter().map(|(n, s, d)| {
        format!(r#"{{"n":"{}","s":{},"d":{},"e":false}}"#, json_escape(n), if *d { 0 } else { *s }, d)
    }).collect();
    Ok(format!("[{}]", items.join(",")))
}

fn extract_rgss(input: &str, output: &str, selected: Option<&str>) -> Result<(u32, u32), String> {
    let archive = open(input)?;
    let sel: HashSet<&str> = selected.unwrap_or("").lines().filter(|l| !l.is_empty()).collect();

    let targets: Vec<&RgssEntry> = archive.entries.iter().filter(|e| {
        if sel.is_empty() { return true; }
        sel.contains(e.name.as_str()) || sel.iter().any(|d| e.name.starts_with(&format!("{d}/")))
    }).collect();
    let _ = fs::create_dir_all(output);
    extract_progress::reset(targets.iter().map(|e| e.size).sum());
    let mut fail = 0u32;
    // Duplicate / case-only-colliding names would be last-wins truncation on
    // /sdcard and FAT — allocate distinct dests instead.
    let mut dests = DestAllocator::new();
    let mut f = File::open(input).map_err(|e| format!("RGSS: open {input}: {e}"))?;
    // One scratch buffer for the whole run: a real RGSS3A holds thousands of
    // entries and most are a few hundred bytes, so allocating (and zeroing) a
    // fresh 256 KiB buffer per entry would dominate small-file extraction.
    let mut buf = vec![0u8; STREAM_CHUNK];
    for e in &targets {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        extract_progress::set_name(&e.name);
        extract_progress::set_file(e.size);
        match safe_join(output, &e.name) {
            Ok(d) => {
                let d = dests.allocate(d);
                if rgss_extract_one(&mut f, e, &d, &mut buf).is_err() {
                    let _ = fs::remove_file(&d);
                    fail += 1;
                }
            }
            Err(_) => { fail += 1; }
        }
    }
    Ok((targets.len() as u32, fail))
}

// ─── pack ──────────────────────────

/// Collects files under `base` (or the single file itself) with `/`-separated
/// archive paths. Iterative — no recursion.
fn collect_files(base: &Path) -> Result<Vec<(PathBuf, String)>, String> {
    let mut out = Vec::new();
    if base.is_file() {
        let name = base.file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
        out.push((base.to_path_buf(), name));
        return Ok(out);
    }
    if !base.is_dir() { return Err(format!("RGSS: {} is neither a file nor a directory", base.display())); }
    let mut stack = vec![(base.to_path_buf(), String::new())];
    while let Some((dir, rel)) = stack.pop() {
        let mut entries: Vec<_> = fs::read_dir(&dir).map_err(|e| format!("RGSS read_dir {}: {e}", dir.display()))?
            .collect::<Result<_, _>>().map_err(|e| format!("RGSS read_dir {}: {e}", dir.display()))?;
        entries.sort_by_key(|e| e.file_name());
        for entry in entries {
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().to_string();
            let child_rel = if rel.is_empty() { name.clone() } else { format!("{rel}/{name}") };
            let meta = entry.metadata().map_err(|e| format!("RGSS metadata {}: {e}", path.display()))?;
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

struct PackEntry { name: String, src: PathBuf, size: u64 }

fn prepare(input: &str) -> Result<Vec<PackEntry>, String> {
    let files = collect_files(Path::new(input))?;
    if files.is_empty() { return Err("RGSS: no files to archive".to_string()); }
    let mut entries: Vec<PackEntry> = Vec::with_capacity(files.len());
    for (src, rel) in files {
        if rel.is_empty() { return Err("RGSS: file with an empty name".to_string()); }
        let size = src.metadata().map_err(|e| format!("RGSS metadata {}: {e}", src.display()))?.len();
        if size > u32::MAX as u64 { return Err(format!("RGSS file too large: {rel}")); }
        // RPG Maker stores archive paths with `\` separators.
        entries.push(PackEntry { name: rel.replace('/', "\\"), src, size });
    }
    entries.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(entries)
}

/// Streams one entry's payload through the cipher into `out`, feeding progress
/// and honouring cancel.
fn write_payload(out: &mut File, e: &PackEntry, key: u32, buf: &mut [u8]) -> Result<(), String> {
    if compress_progress::cancelled() { return Err("cancelled".to_string()); }
    compress_progress::set_name(&e.name.replace('\\', "/"));
    compress_progress::set_file(e.size);
    if e.size == 0 { return Ok(()); }
    let mut f = File::open(&e.src).map_err(|x| format!("RGSS open {}: {x}", e.src.display()))?;
    let mut cipher = PayloadKey::new(key);
    let mut left = e.size;
    while left > 0 {
        if compress_progress::cancelled() { return Err("cancelled".to_string()); }
        let want = buf.len().min(left as usize);
        f.read_exact(&mut buf[..want]).map_err(|x| format!("RGSS read {}: {x}", e.src.display()))?;
        cipher.apply(&mut buf[..want]);
        out.write_all(&buf[..want]).map_err(|x| format!("RGSS write {}: {x}", e.name))?;
        compress_progress::add_bytes(want as u64);
        left -= want as u64;
    }
    Ok(())
}

/// v1 / v2: one sequential pass. Metadata and payload are interleaved, so the
/// index cannot be pre-computed and no offsets need to be back-patched.
fn create_older(entries: &[PackEntry], output: &str) -> Result<u32, String> {
    let mut out = File::create(output).map_err(|e| format!("RGSS create {output}: {e}"))?;
    out.write_all(RGSS_MAGIC).map_err(|e| format!("RGSS write {output}: {e}"))?;
    out.write_all(&[1u8]).map_err(|e| format!("RGSS write {output}: {e}"))?;
    let mut key = V1_KEY;
    // Shared scratch buffer — see the note in `extract_rgss`.
    let mut buf = vec![0u8; STREAM_CHUNK];
    for e in entries {
        let name = e.name.as_bytes();
        let name_len = name.len() as u32;
        out.write_all(&(name_len ^ key).to_le_bytes()).map_err(|x| format!("RGSS write: {x}"))?;
        rot7(&mut key);
        for &b in name {
            out.write_all(&[b ^ (key & 0xFF) as u8]).map_err(|x| format!("RGSS write: {x}"))?;
            rot7(&mut key);
        }
        out.write_all(&((e.size as u32) ^ key).to_le_bytes()).map_err(|x| format!("RGSS write: {x}"))?;
        rot7(&mut key);
        // The payload uses the key as it stands here and does not advance it.
        write_payload(&mut out, e, key, &mut buf)?;
    }
    out.flush().map_err(|e| format!("RGSS flush {output}: {e}"))?;
    Ok(entries.len() as u32)
}

/// v3: index first, then the data region. Offsets are known before the data is
/// written (index size is fully determined by the names), so a single forward
/// pass suffices.
fn create_v3(entries: &[PackEntry], output: &str) -> Result<u32, String> {
    let mut key = V3_SEED;
    rot9(&mut key);
    const ENTRY_KEY: u32 = 0;
    let key_bytes = key.to_le_bytes();

    let data_start: u64 = 12
        + entries.iter().map(|e| 16 + e.name.len() as u64).sum::<u64>()
        + 4; // terminator
    let mut offset = data_start;
    for e in entries {
        offset = offset.checked_add(e.size)
            .ok_or_else(|| format!("RGSS archive too large at {} (4GB offset overflow)", e.name))?;
        if offset > u32::MAX as u64 {
            return Err(format!("RGSS archive too large: {} exceeds the 4GB RGSS3A offset limit", e.name));
        }
    }

    let mut out = File::create(output).map_err(|e| format!("RGSS create {output}: {e}"))?;
    out.write_all(RGSS_MAGIC).map_err(|e| format!("RGSS write {output}: {e}"))?;
    out.write_all(&[3u8]).map_err(|e| format!("RGSS write {output}: {e}"))?;
    out.write_all(&V3_SEED.to_le_bytes()).map_err(|e| format!("RGSS write {output}: {e}"))?;

    let mut data_offset = data_start;
    for e in entries {
        out.write_all(&((data_offset as u32) ^ key).to_le_bytes()).map_err(|x| format!("RGSS write: {x}"))?;
        out.write_all(&((e.size as u32) ^ key).to_le_bytes()).map_err(|x| format!("RGSS write: {x}"))?;
        out.write_all(&(ENTRY_KEY ^ key).to_le_bytes()).map_err(|x| format!("RGSS write: {x}"))?;
        out.write_all(&(e.name.len() as u32 ^ key).to_le_bytes()).map_err(|x| format!("RGSS write: {x}"))?;
        for (i, &b) in e.name.as_bytes().iter().enumerate() {
            out.write_all(&[b ^ key_bytes[i & 3]]).map_err(|x| format!("RGSS write: {x}"))?;
        }
        data_offset += e.size;
    }
    out.write_all(&key.to_le_bytes()).map_err(|x| format!("RGSS write: {x}"))?;

    let mut buf = vec![0u8; STREAM_CHUNK];
    for e in entries {
        write_payload(&mut out, e, ENTRY_KEY, &mut buf)?;
    }
    out.flush().map_err(|e| format!("RGSS flush {output}: {e}"))?;
    Ok(entries.len() as u32)
}

pub fn create(input: &str, output: &str, version: u8) -> Result<u32, String> {
    if version != 1 && version != 3 {
        return Err(format!("RGSS: cannot write archive version {version}"));
    }
    let entries = prepare(input)?;
    let total: u64 = entries.iter().map(|e| e.size).sum();
    compress_progress::reset(total);
    let n = if version == 3 { create_v3(&entries, output)? } else { create_older(&entries, output)? };
    if compress_progress::cancelled() { return Err("cancelled".to_string()); }
    Ok(n)
}

// ─── RPG Maker MV / MZ loose assets (.rpgmvp / .rpgmvo / .rpgmvm) ───
//
// The "encryption" is not a cipher. The file is
//
//     "RPGMV..." header (16) | asset[0..16] XOR keystream (16) | asset[16..]
//
// and every asset type has a header RPG Maker writes predictably, so the whole
// 16-byte keystream is recoverable from the file itself — no `System.json`
// sidecar and no MD5. The decoded output is therefore just
//
//     recovered asset header (16) | the file's tail from offset 32, verbatim
//
// and the keystream itself is never needed again, which is why this is a copy
// with a 16-byte prefix rather than a streaming cipher.

/// Fixed header RPG Maker MV/MZ writes in front of every obfuscated asset.
const RPGM_HEADER: &[u8; 16] = &[
    0x52, 0x50, 0x47, 0x4D, 0x56, 0x00, 0x00, 0x00, 0x00, 0x03, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
];
/// Length of the RPGM header RPG Maker writes in front of every asset.
const MV_RPGM_HEADER_LEN: u64 = 16;
/// Where the asset's untouched tail begins: 16 (header) + 16 (obfuscated).
const MV_TAIL_OFFSET: u64 = 32;
/// Read up front: through the tail, plus enough of it to sanity check the guess
/// made inside the obfuscated window.
///
/// 96, not 56: validating an MPEG-4 asset means finding the box that follows
/// `ftyp`, and on real files that box ("moov") can sit ~20 bytes into the tail.
/// A 56-byte probe left a 24-byte tail, whose scan range stopped one byte short
/// of the match — the file was rejected for being a hair too small to verify.
const MV_PROBE_LEN: usize = 96;
/// The first 16 bytes of every PNG: signature, then the start of the IHDR chunk.
const PNG_HEADER16: [u8; 16] = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52];
const PNG_SIG: &[u8] = b"\x89PNG\r\n\x1a\n";
const OGG_SIG: &[u8] = b"OggS";
const FTYP_SIG: &[u8] = b"ftyp";
/// Boxes that can legally follow `ftyp` in an MPEG-4 file.
const MP4_BOXES: &[&[u8]] = &[b"moov", b"mdat", b"free", b"skip", b"wide", b"pnot"];

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum MvKind { Png, Ogg, M4a, Unknown }

impl MvKind {
    /// The true extension for this asset type. RPG Maker's obfuscated extension
    /// only names the container, never the real format, so this is a fixed
    /// mapping rather than a guess.
    fn ext(self) -> &'static str {
        match self {
            MvKind::Png => ".png",
            MvKind::Ogg => ".ogg",
            MvKind::M4a => ".m4a",
            MvKind::Unknown => ".bin",
        }
    }

    /// Does a plain asset header look like this container? Used when *packing*:
    /// the obfuscated extension names the container, so obfuscating a JPEG into
    /// `foo.rpgmvp` would produce a file the engine rejects at load time. The
    /// engine's own loader keys off the container, not the payload, so catching
    /// the mismatch here is the only place it can be caught cheaply.
    fn matches(self, head: &[u8]) -> bool {
        match self {
            MvKind::Png => head.starts_with(&[0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A]),
            MvKind::Ogg => head.starts_with(OGG_SIG),
            MvKind::M4a => head.len() >= 8 && &head[4..8] == FTYP_SIG,
            MvKind::Unknown => true,
        }
    }
}

fn mv_kind_of(name: &str) -> MvKind {
    let lower = name.to_ascii_lowercase();
    if lower.ends_with(".rpgmvp") || lower.ends_with(".png_") { MvKind::Png }
    else if lower.ends_with(".rpgmvo") || lower.ends_with(".ogg_") { MvKind::Ogg }
    else if lower.ends_with(".rpgmvm") || lower.ends_with(".m4a_") { MvKind::M4a }
    else { MvKind::Unknown }
}

fn read_up_to(f: &mut File, buf: &mut [u8]) -> Result<usize, String> {
    let mut n = 0;
    while n < buf.len() {
        match f.read(&mut buf[n..]).map_err(|e| format!("MV: read: {e}"))? {
            0 => break,
            k => n += k,
        }
    }
    Ok(n)
}

/// Low two bytes of an Ogg stream's serial number, taken from the SECOND page.
///
/// Page 1's own copy is inside the obfuscated window, but the serial is constant
/// across pages, so page 2 (plaintext) gives it to us. `asset` must start at the
/// asset's byte 0, i.e. begin with the recovered header.
fn ogg_serial_low(asset: &[u8]) -> Option<[u8; 2]> {
    fn next_page(data: &[u8], mut pos: usize) -> Option<(usize, [u8; 4])> {
        let head = data.get(pos..pos + 27)?;
        if &head[..4] != OGG_SIG { return None; }
        let segments = head[26] as usize;
        let table = data.get(pos + 27..pos + 27 + segments)?;
        let body: usize = table.iter().map(|&s| s as usize).sum();
        let serial = [head[14], head[15], head[16], head[17]];
        pos += 27 + segments + body;
        Some((pos, serial))
    }
    let second_start = next_page(asset, 0)?.0;
    let serial = next_page(asset, second_start)?.1;
    Some([serial[0], serial[1]])
}

/// The asset's first 16 bytes, as RPG Maker wrote them before obfuscation.
///
/// `probed` is the file's bytes from `MV_TAIL_OFFSET` onwards (the untouched
/// tail). For Ogg the serial's low two bytes need a second page, so they start
/// out zero and are filled in by `mv_fix_ogg_serial`.
fn mv_plain_header(kind: MvKind, tail: &[u8]) -> Result<[u8; 16], String> {
    let mut out = [0u8; 16];
    match kind {
        MvKind::Png => out = PNG_HEADER16,
        MvKind::Ogg => {
            // "OggS", version 0, header type 2 (first page = beginning of
            // stream), zero granule position, then the serial's low two bytes.
            out[..6].copy_from_slice(b"OggS\x00\x02");
        }
        MvKind::M4a => {
            // 4..8 "ftyp" is known; 8..12 the major brand and 12..16 the minor
            // version sit inside the obfuscated window, so they are assumed
            // rather than recovered — without the game's key there is nothing
            // left to read them from. The values are the ones real files use:
            // `00 00 00 20 66 74 79 70 4d 34 41 20 00 00 00 00`, i.e. major
            // brand "M4A " and minor version 0.0.0.0. (An earlier revision
            // assumed 0.0.2.0 here; the reference corpus proves that wrong, and
            // those 4 bytes are the only difference in the whole payload.)
            // 0..4 is the ftyp box's own size, recovered from the tail below.
            out[4..8].copy_from_slice(FTYP_SIG);
            out[8..12].copy_from_slice(b"M4A ");
            out[12..16].copy_from_slice(&[0x00, 0x00, 0x00, 0x00]);
            // `tail` starts at the asset's byte 16, so a box TYPE found at
            // tail[i] sits at asset[i + 16] and the box it belongs to starts
            // 4 bytes earlier, at asset[i + 12] — which is exactly the `ftyp`
            // box size we are looking for.
            let mut box_size = 28u32; // the overwhelmingly common value
            // `i` is the last index where a full 4-byte type still fits, so the
            // range is inclusive — `..len-4` drops exactly that one position.
            for i in 0..=tail.len().saturating_sub(4) {
                if MP4_BOXES.contains(&&tail[i..i + 4]) { box_size = (i + 12) as u32; break; }
            }
            out[..4].copy_from_slice(&box_size.to_be_bytes());
        }
        MvKind::Unknown => return Err("MV: cannot tell what kind of asset this is".to_string()),
    }
    Ok(out)
}

/// Fills in an Ogg header's serial bytes from the stream's second page.
fn mv_fix_ogg_serial(f: &mut File, plain16: &mut [u8; 16]) {
    /// Two pages is the worst case (a page body is at most 255*255 bytes),
    /// plus slack for headers and segment tables.
    const WINDOW: usize = 2 * (27 + 255 + 255 * 255) + 1024;
    let mut tail = vec![0u8; WINDOW];
    if f.seek(SeekFrom::Start(MV_TAIL_OFFSET)).is_err() { return; }
    let n = match read_up_to(f, &mut tail) { Ok(n) => n, Err(_) => return };
    if n < 11 { return; } // not even one full page header
    let mut asset = Vec::with_capacity(16 + n);
    asset.extend_from_slice(plain16);
    asset.extend_from_slice(&tail[..n]);
    if let Some(serial) = ogg_serial_low(&asset) { plain16[14..16].copy_from_slice(&serial); }
}

/// Structural sanity check on the asset's tail, which lives OUTSIDE the
/// obfuscated window and so genuinely verifies the guess made inside it.
/// Without this the "recovery" would be unfalsifiable — a wrong assumption
/// would still reproduce a self-consistent (and garbage) header, and we would
/// happily write it out.
///
/// `tail` is the asset starting at its byte 16.
fn mv_check_plain(kind: MvKind, tail: &[u8]) -> Result<(), String> {
    let bad = |why: &str| format!(
        "MV: {why} — this does not look like an RPG Maker {kind:?} asset (a non-standard RPGM header would need the game's own encryption key)"
    );
    match kind {
        // PNG: the first 16 bytes are signature + the IHDR chunk's LENGTH and
        // TAG, so those are inside the obfuscated window. What is verifiable is
        // the chunk BODY that follows: width, height, bit depth, colour type.
        MvKind::Png => {
            if tail.len() >= 10 {
                let dim = |s: &[u8]| u32::from_be_bytes([s[0], s[1], s[2], s[3]]);
                let (w, h) = (dim(&tail[..4]), dim(&tail[4..8]));
                if !(1..=32768).contains(&w) || !(1..=32768).contains(&h) {
                    return Err(bad("implausible PNG dimensions"));
                }
                if !matches!(tail[8], 1 | 2 | 4 | 8 | 16) { return Err(bad("bad PNG bit depth")); }
                if !matches!(tail[9], 0 | 2 | 3 | 4 | 6) { return Err(bad("bad PNG colour type")); }
            }
        }
        // Ogg: the first page's sequence number is 0, at page offset 18, i.e.
        // tail[2..6].
        MvKind::Ogg => {
            if tail.len() >= 6 && tail[2..6] != [0, 0, 0, 0] {
                return Err(bad("the first Ogg page does not have sequence 0"));
            }
        }
        // M4A: the `ftyp` box size was *recovered* by finding the next box, so
        // validate the MP4 structure around it: the next box declares a size,
        // that size must be sane, and its type must be one we know. (Simply
        // looking for a box type at the position implied by the recovered size
        // would be circular — it would hold no matter what the size came out as.)
        MvKind::M4a => {
            let found = (4..=tail.len().saturating_sub(4))
                .find(|&i| MP4_BOXES.contains(&&tail[i..i + 4]));
            match found {
                Some(i) => {
                    let declared = u32::from_be_bytes([tail[i - 4], tail[i - 3], tail[i - 2], tail[i - 1]]);
                    if declared < 8 { return Err(bad("the MP4 box after ftyp declares an impossible size")); }
                    let ftyp_size = (i + 12) as u64;
                    if ftyp_size < 16 { return Err(bad("implausible ftyp box size")); }
                }
                None => return Err(bad("no known MP4 box follows the ftyp box")),
            }
        }
        MvKind::Unknown => {}
    }
    Ok(())
}

/// The decoded plan for one MV asset file.
#[derive(Debug)]
struct MvAsset {
    name: String,
    /// The asset's recovered first 16 bytes — dropped for a file that was never
    /// obfuscated.
    header: [u8; 16],
    /// 32 for an obfuscated file, 0 for one that was never obfuscated.
    payload_offset: u64,
    /// Bytes copied verbatim from the file.
    payload_size: u64,
}

impl MvAsset {
    /// Size of the decoded file the user ends up with: the copied tail, plus
    /// the 16 recovered header bytes for an obfuscated file.
    fn decoded_size(&self) -> u64 {
        self.payload_size + if self.payload_offset != 0 { 16 } else { 0 }
    }
}

/// Recover which container an obfuscated asset holds when its FILENAME does not
/// say. The `.rpgmvp` / `.rpgmvo` / `.rpgmvm` names are the engine's convention,
/// but a renamed or extension-less file is common (repacked games, and every
/// real-world corpus keeps them as `Image` / `AudioOrbis` / `AudioMpeg`).
///
/// The evidence must be INDEPENDENT of the guess: `mv_plain_header` *invents*
/// the container's magic from the kind, so asking it whether the header looks
/// like that same kind would always say yes. `mv_check_plain` is the
/// independent half — it only reads the tail, which the obfuscation never
/// touched. Exactly one kind has to pass it; zero or several means the file is
/// not something we can vouch for and the caller must refuse.
fn mv_sniff_kind(tail: &[u8]) -> Option<MvKind> {
    let hits: Vec<MvKind> = [MvKind::Png, MvKind::Ogg, MvKind::M4a]
        .into_iter()
        .filter(|k| mv_check_plain(*k, tail).is_ok())
        .collect();
    match hits.as_slice() {
        [only] => Some(*only),
        _ => None,
    }
}

fn mv_plan(input: &str) -> Result<MvAsset, String> {
    let kind = mv_kind_of(input);
    let mut f = File::open(input).map_err(|e| format!("MV: open {input}: {e}"))?;
    let file_size = f.metadata().map_err(|e| format!("MV: stat {input}: {e}"))?.len();
    if file_size == 0 { return Err("MV: file is empty".to_string()); }

    let mut probe = [0u8; MV_PROBE_LEN];
    let read = read_up_to(&mut f, &mut probe)?;
    let probe = &probe[..read];

    // No RPGM header means the file was never obfuscated. Repacked and
    // translated MV games ship these extensions as plain assets, and the user
    // still wants their bytes back, so hand them over untouched rather than
    // refusing — the extension is a naming convention, not a guarantee.
    if !probe.starts_with(RPGM_HEADER) {
        return Ok(MvAsset {
            name: mv_output_name(input, kind, probe, false),
            header: [0u8; 16],
            payload_offset: 0,
            payload_size: file_size,
        });
    }
    if file_size < MV_TAIL_OFFSET {
        return Err(format!(
            "MV: file is only {file_size} bytes — too short to hold an RPGM header plus the obfuscated window"
        ));
    }

    // An extension-less (or renamed) file has to identify itself from its
    // content; the extension stays authoritative when it is present, since the
    // engine defines the mapping.
    let kind = if kind == MvKind::Unknown {
        mv_sniff_kind(&probe[MV_TAIL_OFFSET as usize..])
            .ok_or("MV: cannot tell what kind of asset this is")?
    } else { kind };
    let mut plain16 = mv_plain_header(kind, &probe[MV_TAIL_OFFSET as usize..])?;
    if kind == MvKind::Ogg { mv_fix_ogg_serial(&mut f, &mut plain16); }
    mv_check_plain(kind, &probe[MV_TAIL_OFFSET as usize..])?;
    Ok(MvAsset {
        name: mv_output_name(input, kind, &plain16, true),
        header: plain16,
        payload_offset: MV_TAIL_OFFSET,
        payload_size: file_size - MV_TAIL_OFFSET,
    })
}

/// The output name for a decoded asset.
///
/// For an OBFUSCATED file the extension comes from the asset type RPG Maker's own
/// naming implies, never from sniffing: the plaintext header is (partly) our own
/// reconstruction, so "sniffing" it would just be us reading our own guess back.
///
/// For a file that was never obfuscated the content is the only real evidence,
/// and it can contradict the extension (a `.rpgmvp` holding a RIFF/WAVE blob).
/// Handing the user `broken.png` would be worse than `sound.wav`, so the
/// sniffed type wins whenever the two disagree.
fn mv_output_name(input: &str, kind: MvKind, head: &[u8], obfuscated: bool) -> String {
    let stem = Path::new(input).file_stem()
        .map(|s| s.to_string_lossy().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "output".to_string());
    let ext = if obfuscated {
        kind.ext()
    } else {
        // Unobfuscated: the content is the only evidence. An extension we could
        // not map (`.dat`) leaves nothing to fall back on, so take the sniff
        // as-is; a recognised one is only overruled if the sniff succeeded.
        let sniffed = sniff_media_ext(head);
        if sniffed != ".bin" { sniffed } else { kind.ext() }
    };
    format!("{stem}{ext}")
}

fn sniff_media_ext(head: &[u8]) -> &'static str {
    if head.starts_with(PNG_SIG) { ".png" }
    else if head.starts_with(OGG_SIG) { ".ogg" }
    else if head.get(4..8) == Some(FTYP_SIG) { ".m4a" }
    else if head.starts_with(b"RIFF") { ".wav" }
    else if head.starts_with(b"fLaC") { ".flac" }
    else if head.starts_with(b"ID3") || head.starts_with(&[0xff, 0xfb]) { ".mp3" }
    else if head.starts_with(b"BM") { ".bmp" }
    else if head.starts_with(b"GIF8") { ".gif" }
    else if head.starts_with(&[0xff, 0xd8, 0xff]) { ".jpg" }
    else { ".bin" }
}

pub fn mv_list(input: &str) -> Result<String, String> {
    let asset = mv_plan(input)?;
    // Presented as a one-entry archive so the whole preview / batch / search
    // machinery works unchanged.
    Ok(format!(
        r#"[{{"n":"{}","s":{},"d":false,"e":false}}]"#,
        json_escape(&asset.name), asset.decoded_size()
    ))
}

/// Writes the decoded asset: the recovered 16-byte header, then the file's tail
/// copied verbatim. There is no cipher state to carry.
///
/// Takes the plan rather than recomputing it — [mv_plan] re-opens the file and,
/// for Ogg, re-walks pages to recover the serial, which is far too much work to
/// do twice for one output.
fn mv_extract_one(input: &str, output: &str, asset: &MvAsset, dests: &mut DestAllocator, buf: &mut [u8]) -> Result<u64, String> {
    let dest = dests.allocate(safe_join(output, &asset.name)?);
    if let Some(p) = dest.parent() { fs::create_dir_all(p).map_err(|e| format!("MV mkdir: {e}"))?; }
    let mut out = ProgressWriter::extract(std::io::BufWriter::with_capacity(
        256 * 1024,
        File::create(&dest).map_err(|e| format!("MV: create {}: {e}", dest.display()))?,
    ));
    // Only an obfuscated file needs its header put back.
    if asset.payload_offset != 0 { out.write_all(&asset.header).map_err(|e| format!("MV: write: {e}"))?; }
    let mut f = File::open(input).map_err(|e| format!("MV: open {input}: {e}"))?;
    f.seek(SeekFrom::Start(asset.payload_offset)).map_err(|e| format!("MV: seek: {e}"))?;
    let mut left = asset.payload_size;
    while left > 0 {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        let want = buf.len().min(left as usize);
        f.read_exact(&mut buf[..want]).map_err(|e| format!("MV: read: {e}"))?;
        out.write_all(&buf[..want]).map_err(|e| format!("MV: write: {e}"))?;
        left -= want as u64;
    }
    out.flush().map_err(|e| format!("MV: flush: {e}"))?;
    Ok(asset.decoded_size())
}

/// Runs one decode, deleting a partially written file on any failure. Every
/// other format in the app has the same rule: a half-written image is worse
/// than no image, because the user has no way to tell it apart from a real one.
fn mv_extract_guarded(input: &str, output: &str, dests: &mut DestAllocator, buf: &mut [u8]) -> Result<u64, String> {
    let asset = mv_plan(input)?;
    match mv_extract_one(input, output, &asset, dests, buf) {
        Ok(n) => Ok(n),
        Err(e) => {
            let _ = fs::remove_file(safe_join(output, &asset.name).unwrap_or_default());
            Err(e)
        }
    }
}

pub fn mv_extract(input: &str, output: &str, selected: Option<&str>) -> Result<(u32, u32), String> {
    let asset = mv_plan(input)?;
    let sel: HashSet<&str> = selected.unwrap_or("").lines().filter(|l| !l.is_empty()).collect();
    if !sel.is_empty() && !sel.contains(asset.name.as_str()) { return Ok((0, 0)); }

    let _ = fs::create_dir_all(output);
    extract_progress::reset(asset.decoded_size());
    extract_progress::set_name(&asset.name);
    extract_progress::set_file(asset.decoded_size());
    let mut dests = DestAllocator::new();
    let mut buf = vec![0u8; STREAM_CHUNK];
    match mv_extract_guarded(input, output, &mut dests, &mut buf) {
        Ok(_) => Ok((1, 0)),
        Err(e) if e == "cancelled" => Err(e),
        Err(_) => Ok((1, 1)),
    }
}

// ─── MD5 (present only because the MV format defines one) ───
//
// RPG Maker derives the MV keystream as `MD5(encryptionKeyString)`, so writing
// a valid obfuscated asset under a non-default key needs MD5. This is a format
// requirement, not a security control: the "encryption" is 16 bytes of XOR
// against a key the file itself reveals. Implemented here rather than pulled in
// as a dependency so it can be pinned against the standard test vectors.

const MD5_S: [u32; 64] = [
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
];

const fn md5_k(i: usize) -> u32 {
    // floor(abs(sin(i + 1)) * 2^32) — the standard constant table. Written out
    // rather than computed so the result cannot drift with the libm in use.
    const K: [u32; 64] = [
        0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a,
        0xa8304613, 0xfd469501, 0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be,
        0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821, 0xf61e2562, 0xc040b340,
        0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
        0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8,
        0x676f02d9, 0x8d2a4c8a, 0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c,
        0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70, 0x289b7ec6, 0xeaa127fa,
        0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
        0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92,
        0xffeff47d, 0x85845dd1, 0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1,
        0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391,
    ];
    K[i]
}

pub fn md5(data: &[u8]) -> [u8; 16] {
    let mut msg = data.to_vec();
    let bit_len = (data.len() as u64).wrapping_mul(8);
    msg.push(0x80);
    while msg.len() % 64 != 56 { msg.push(0); }
    msg.extend_from_slice(&bit_len.to_le_bytes());

    let (mut a0, mut b0, mut c0, mut d0) =
        (0x67452301u32, 0xefcdab89u32, 0x98badcfeu32, 0x10325476u32);
    for chunk in msg.chunks(64) {
        let mut m = [0u32; 16];
        for (i, word) in m.iter_mut().enumerate() {
            *word = u32::from_le_bytes([chunk[i * 4], chunk[i * 4 + 1], chunk[i * 4 + 2], chunk[i * 4 + 3]]);
        }
        let (mut a, mut b, mut c, mut d) = (a0, b0, c0, d0);
        for (i, &s) in MD5_S.iter().enumerate() {
            let (f, g) = match i / 16 {
                0 => ((b & c) | (!b & d), i),
                1 => ((d & b) | (!d & c), (5 * i + 1) % 16),
                2 => (b ^ c ^ d, (3 * i + 5) % 16),
                _ => (c ^ (b | !d), (7 * i) % 16),
            };
            let tmp = f.wrapping_add(a).wrapping_add(md5_k(i)).wrapping_add(m[g]);
            a = d;
            d = c;
            c = b;
            b = b.wrapping_add(tmp.rotate_left(s));
        }
        a0 = a0.wrapping_add(a);
        b0 = b0.wrapping_add(b);
        c0 = c0.wrapping_add(c);
        d0 = d0.wrapping_add(d);
    }
    let mut out = [0u8; 16];
    for (i, w) in [a0, b0, c0, d0].iter().enumerate() {
        out[i * 4..i * 4 + 4].copy_from_slice(&w.to_le_bytes());
    }
    out
}

/// Resolves the keystream a user typed into the 16 bytes RPG Maker will use.
///
/// Two accepted forms, because both are in circulation:
///  - 32 hex characters: taken as the keystream itself. Community tools show
///    users exactly this value, so most people copy it from there rather than
///    from `System.json`.
///  - anything else, including empty: the MD5 of the UTF-8 text, which is RPG
///    Maker's actual rule — `encryptionKey` is a free-form string in
///    `System.json`, and an unset one is by far the most common.
pub fn mv_keystream_from_input(input: &str) -> Result<[u8; 16], String> {
    let trimmed = input.trim();
    if trimmed.len() == 32 && trimmed.bytes().all(|b| b.is_ascii_hexdigit()) {
        let mut out = [0u8; 16];
        for (i, slot) in out.iter_mut().enumerate() {
            *slot = u8::from_str_radix(&trimmed[i * 2..i * 2 + 2], 16)
                .map_err(|_| "MV: keystream is not valid hex".to_string())?;
        }
        return Ok(out);
    }
    if trimmed.len() > 512 {
        return Err("MV: encryption key is implausibly long".to_string());
    }
    Ok(md5(trimmed.as_bytes()))
}

/// Obfuscates one decoded asset: `RPGM header | asset[0..16] XOR keystream | asset[16..]`.
///
/// The exact inverse of the decode side, so a round trip through this and back is
/// byte-identical for the same keystream. [output] is chosen by the caller so
/// the app can name it `foo.rpgmvp` / `foo.rpgmvo` / `foo.rpgmvm`.
pub fn mv_encrypt(input: &str, output: &str, keystream: &[u8; 16]) -> Result<u64, String> {
    // A keystream of all zeros means 32 zeros were typed: the output would be a
    // plain asset behind a header, which is worse than saying so.
    if *keystream == [0u8; 16] {
        return Err("MV: the keystream is all zeros — the output would not be obfuscated at all".to_string());
    }
    let size = fs::metadata(input).map_err(|e| format!("MV: stat {input}: {e}"))?.len();
    if size < 16 {
        return Err(format!("MV: {input} is only {size} bytes — too short to obfuscate (an asset header is 16 bytes)"));
    }
    // Two packing mistakes are silently accepted otherwise, and both yield a
    // file the engine refuses at load time with no way for the user to tell
    // why: obfuscating an already-obfuscated asset, and obfuscating the wrong
    // media type into a container the engine keys off by extension.
    {
        let mut f = File::open(input).map_err(|e| format!("MV: open {input}: {e}"))?;
        let mut head = [0u8; 16];
        f.read_exact(&mut head).map_err(|e| format!("MV: read {input}: {e}"))?;
        if head.starts_with(RPGM_HEADER) {
            return Err(format!(
                "MV: {input} is already an obfuscated RPG Maker asset — decode it first, then pack the plain file"
            ));
        }
        let kind = mv_kind_of(output);
        if !kind.matches(&head) {
            return Err(format!(
                "MV: {input} is not {} data, so it cannot be packed as {}",
                kind.ext().trim_start_matches('.').to_ascii_uppercase(),
                Path::new(output).file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default(),
            ));
        }
    }
    compress_progress::reset(size + MV_RPGM_HEADER_LEN);
    compress_progress::set_file(size + MV_RPGM_HEADER_LEN);
    compress_progress::set_name(
        &Path::new(output).file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default(),
    );

    let result = (|| -> Result<(), String> {
        if let Some(parent) = Path::new(output).parent() {
            if !parent.as_os_str().is_empty() {
                fs::create_dir_all(parent).map_err(|e| format!("MV mkdir {}: {e}", parent.display()))?;
            }
        }
        let mut src = File::open(input).map_err(|e| format!("MV: open {input}: {e}"))?;
        let mut out = ProgressWriter::compress(std::io::BufWriter::with_capacity(
            256 * 1024,
            File::create(output).map_err(|e| format!("MV: create {output}: {e}"))?,
        ));
        out.write_all(RPGM_HEADER).map_err(|e| format!("MV: write {output}: {e}"))?;
        // The obfuscated window: the asset's own first 16 bytes, XORed.
        let mut buf = vec![0u8; STREAM_CHUNK.max(16)];
        src.read_exact(&mut buf[..16]).map_err(|e| format!("MV: read {input}: {e}"))?;
        for (i, b) in buf[..16].iter_mut().enumerate() { *b ^= keystream[i]; }
        out.write_all(&buf[..16]).map_err(|e| format!("MV: write {output}: {e}"))?;
        compress_progress::add_bytes(16);
        // The rest is stored verbatim.
        let mut left = size - 16;
        while left > 0 {
            if compress_progress::cancelled() { return Err("cancelled".to_string()); }
            let want = buf.len().min(left as usize);
            src.read_exact(&mut buf[..want]).map_err(|e| format!("MV: read {input}: {e}"))?;
            out.write_all(&buf[..want]).map_err(|e| format!("MV: write {output}: {e}"))?;
            compress_progress::add_bytes(want as u64);
            left -= want as u64;
        }
        out.flush().map_err(|e| format!("MV: flush {output}: {e}"))
    })();

    match result {
        Ok(()) => {
            if compress_progress::cancelled() {
                let _ = fs::remove_file(output);
                return Err("cancelled".to_string());
            }
            Ok(size + MV_RPGM_HEADER_LEN)
        }
        Err(e) => { let _ = fs::remove_file(output); Err(e) }
    }
}

// ─── JNI ──────────────────────────

fn guarded<T: Send + 'static>(f: impl FnOnce() -> Result<T, String> + Send + 'static) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|panic| {
        let msg = panic.downcast_ref::<&str>().copied()
            .or_else(|| panic.downcast_ref::<String>().map(|s| s.as_str()))
            .unwrap_or("unknown panic");
        Err(format!("panic: {msg}"))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssExtract(
    mut env: JNIEnv, _class: JClass,
    _tool: JString, input: JString, output: JString,
) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut env, &input);
    let out = s(&mut env, &output);
    match guarded(move || extract_rgss(&inp, &out, None)) {
        Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match env.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = env.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssExtractSelected(
    mut env: JNIEnv, _class: JClass,
    _tool: JString, input: JString, output: JString, selected: JString,
) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut env, &input);
    let out = s(&mut env, &output);
    let sel = s(&mut env, &selected);
    match guarded(move || extract_rgss(&inp, &out, Some(&sel))) {
        Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match env.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = env.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssListEntries(
    mut env: JNIEnv, _: JClass, input: JString,
) -> jstring {
    let inp = s(&mut env, &input);
    match guarded(move || list_rgss(&inp)) {
        Ok(j) => match env.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() },
        Err(e) => { let _ = env.throw_new("java/io/IOException", format!("listEntries: {e}")); std::ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssCreateArchive(
    mut env: JNIEnv, _: JClass, _tool: JString,
    input: JString, output: JString, version: JString,
) -> jstring {
    compress_progress::clear_cancel();
    let inp = s(&mut env, &input);
    let out = s(&mut env, &output);
    let version: u8 = s(&mut env, &version).parse().unwrap_or(3);
    match guarded(move || create(&inp, &out, version)) {
        Ok(total) => { let json = extract_result_json(total, total, 0); match env.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = env.throw_new("java/io/IOException", format!("rgssCreateArchive: {er}")); std::ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssMvEncrypt(
    mut env: JNIEnv, _class: JClass, _tool: JString,
    input: JString, output: JString, key: JString,
) -> jstring {
    compress_progress::clear_cancel();
    let inp = s(&mut env, &input);
    let out = s(&mut env, &output);
    let key_str = s(&mut env, &key);
    // total/success count files, like every other packer in the repo.
    let result = guarded(move || {
        let keystream = mv_keystream_from_input(&key_str)?;
        mv_encrypt(&inp, &out, &keystream)?;
        Ok(extract_result_json(1, 1, 0))
    });
    match result {
        Ok(json) => match env.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() },
        Err(er) => { let _ = env.throw_new("java/io/IOException", format!("rgssMvEncrypt: {er}")); std::ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssExtractProgressFileCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssExtractProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_total() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssExtractProgressName(env: JNIEnv, _: JClass) -> jstring {
    env.new_string(extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssCompressProgressCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssCompressProgressTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::total_bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssCompressProgressFileCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssCompressProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_total() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssCompressProgressName(env: JNIEnv, _: JClass) -> jstring {
    env.new_string(compress_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssCompressCancel(_: JNIEnv, _: JClass) { compress_progress::cancel(); }

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssMvListEntries(
    mut env: JNIEnv, _: JClass, input: JString,
) -> jstring {
    let inp = s(&mut env, &input);
    match guarded(move || mv_list(&inp)) {
        Ok(j) => match env.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() },
        Err(e) => { let _ = env.throw_new("java/io/IOException", format!("listEntries: {e}")); std::ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssMvExtract(
    mut env: JNIEnv, _class: JClass,
    _tool: JString, input: JString, output: JString,
) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut env, &input);
    let out = s(&mut env, &output);
    match guarded(move || mv_extract(&inp, &out, None)) {
        Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match env.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = env.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssMvExtractSelected(
    mut env: JNIEnv, _class: JClass,
    _tool: JString, input: JString, output: JString, selected: JString,
) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut env, &input);
    let out = s(&mut env, &output);
    let sel = s(&mut env, &selected);
    match guarded(move || mv_extract(&inp, &out, Some(&sel))) {
        Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match env.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = env.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssMvProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssMvProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssMvProgressFileCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_bytes() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssMvProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_total() as jlong }
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssMvProgressName(env: JNIEnv, _: JClass) -> jstring {
    env.new_string(extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RgssCore_rgssMvCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(b: &[u8]) -> String { b.iter().map(|x| format!("{x:02x}")).collect() }

    fn tmp(tag: &str) -> PathBuf { std::env::temp_dir().join(format!("uu_rgss_{}_{}", std::process::id(), tag)) }

    /// `compress_progress` is process-wide, and neither `create()` nor
    /// `mv_encrypt()` — unlike the JNI entries that wrap them — clears a stale
    /// CANCEL. So the cancel test and EVERY test that packs have to take turns,
    /// or a pack that starts while the flag is set aborts with "cancelled" and
    /// fails spuriously. The failure is intermittent (it depends on thread
    /// scheduling), so it shows up as a flaky suite rather than a clear bug.
    ///
    /// Any new test that calls `create` or `mv_encrypt` must take this guard.
    static PACK_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
    fn pack_lock() -> std::sync::MutexGuard<'static, ()> {
        PACK_LOCK.lock().unwrap_or_else(|e| e.into_inner())
    }

    // ── Expected bytes ──
    //
    // Derived from the format notes by a separate implementation written in
    // Python straight off the layout description (not from this crate), so a
    // shared misunderstanding of the spec cannot hide behind a self-consistent
    // round-trip. The derivation, for `RGSSAD v1` with entries
    // `("a", b"hello")` and `("Data\Scripts.rxdata", 9 zero bytes)`:
    //
    //   key = 0xDEADCAFE
    //   "RGSSAD\0" 01
    //   1 ^ key            -> LE u32,      key = key*7+3
    //   'a' ^ (key & 0xFF), key = key*7+3                      (per byte!)
    //   5 ^ key            -> LE u32,      key = key*7+3
    //   payload XORed with the current key, advancing key*7+3 every 4 bytes
    //   ...then the second entry, resuming from that same key
    const V1_TWO: &[u8] = &[
        82, 71, 83, 83, 65, 68, 0, 1, 237, 202, 173, 222, 177, 215, 137, 143,
        217, 245, 238, 172, 124, 230, 105, 189, 139, 244, 213, 218, 84, 2, 92, 167,
        72, 116, 123, 197, 252, 45, 96, 102, 233, 65, 161, 205, 196, 252, 45, 96,
        7, 200, 97, 205, 104, 246, 201, 241, 177, 58,
    ];

    // `RGSSAD v3`, seed 0 (so key = 3), entries
    // ("Graphics\P.png", 11x0x03) ("a.txt", "parity") ("empty.dat", empty).
    const V3_THREE: &[u8] = &[
        82, 71, 83, 83, 65, 68, 0, 3, 0, 0, 0, 0, 95, 0, 0, 0,
        8, 0, 0, 0, 3, 0, 0, 0, 13, 0, 0, 0, 68, 114, 97, 112,
        107, 105, 99, 115, 95, 80, 46, 112, 109, 103, 100, 0, 0, 0, 5, 0,
        0, 0, 3, 0, 0, 0, 6, 0, 0, 0, 98, 46, 116, 120, 119, 110,
        0, 0, 0, 3, 0, 0, 0, 3, 0, 0, 0, 10, 0, 0, 0, 102,
        109, 112, 116, 122, 46, 100, 97, 119, 3, 0, 0, 0, 3, 3, 3, 3,
        0, 3, 3, 3, 27, 3, 3, 112, 97, 114, 105, 119, 121,
    ];

    // One 1-char entry "a" holding the single byte 'b', for each version.
    const V1_TINY: &[u8] = &[
        82, 71, 83, 83, 65, 68, 0, 1, 255, 202, 173, 222,
        148, 183, 218, 67, 159, 159,
    ];
    const V3_TINY: &[u8] = &[
        82, 71, 83, 83, 65, 68, 0, 3, 0, 0, 0, 0, 34, 0, 0, 0,
        2, 0, 0, 0, 3, 0, 0, 0, 2, 0, 0, 0, 98, 3, 0, 0, 0, 98,
    ];

    fn write_tree(dir: &Path, files: &[(&str, &[u8])]) {
        for (name, data) in files {
            let p = dir.join(name);
            fs::create_dir_all(p.parent().unwrap()).unwrap();
            fs::write(p, data).unwrap();
        }
    }

    fn walk(dir: &Path) -> Vec<(String, Vec<u8>)> {
        let mut out = Vec::new();
        if let Ok(rd) = fs::read_dir(dir) {
            for e in rd.flatten() {
                let p = e.path();
                if p.is_dir() {
                    for (n, d) in walk(&p) {
                        out.push((format!("{}/{}", p.file_name().unwrap().to_string_lossy(), n), d));
                    }
                } else {
                    out.push((p.file_name().unwrap().to_string_lossy().to_string(), fs::read(&p).unwrap()));
                }
            }
        }
        out
    }

    // ── reader against a fixed, externally derived byte sequence ──

    #[test]
    fn parses_fixed_v1_bytes() {
        let dir = tmp("fixed1");
        fs::create_dir_all(&dir).unwrap();
        let arc = dir.join("Game.rgssad");
        fs::write(&arc, V1_TINY).unwrap();

        let a = open(arc.to_str().unwrap()).unwrap();
        assert_eq!(a.version, 1);
        assert_eq!(a.entries.len(), 1);
        assert_eq!(a.entries[0].name, "a");
        assert_eq!(a.entries[0].size, 1);
        let out = dir.join("out");
        assert_eq!(extract_rgss(arc.to_str().unwrap(), out.to_str().unwrap(), None).unwrap(), (1, 0));
        assert_eq!(fs::read(out.join("a")).unwrap(), b"b");
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn parses_fixed_v3_bytes() {
        let dir = tmp("fixed3");
        fs::create_dir_all(&dir).unwrap();
        let arc = dir.join("Game.rgss3a");
        fs::write(&arc, V3_TINY).unwrap();

        let a = open(arc.to_str().unwrap()).unwrap();
        assert_eq!(a.version, 3);
        assert_eq!(a.entries.len(), 1);
        assert_eq!(a.entries[0].name, "a");
        assert_eq!(a.entries[0].size, 1);
        assert_eq!(a.entries[0].data_key, 0);
        let out = dir.join("out");
        assert_eq!(extract_rgss(arc.to_str().unwrap(), out.to_str().unwrap(), None).unwrap(), (1, 0));
        assert_eq!(fs::read(out.join("a")).unwrap(), b"b");
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn version_two_reads_as_v1_layout() {
        // The v2 byte is only a version marker; the layout is identical to v1
        // (uuksu, mkxp-z and rgssad-rs all treat .rgssad / .rgss2a alike).
        let mut v = V1_TINY.to_vec();
        v[7] = 2;
        let dir = tmp("ver2");
        fs::create_dir_all(&dir).unwrap();
        let arc = dir.join("Game.rgss2a");
        fs::write(&arc, &v).unwrap();
        let a = open(arc.to_str().unwrap()).unwrap();
        assert_eq!(a.version, 2);
        assert_eq!(a.entries.len(), 1);
        assert_eq!(a.entries[0].name, "a");
        let out = dir.join("out");
        assert_eq!(extract_rgss(arc.to_str().unwrap(), out.to_str().unwrap(), None).unwrap(), (1, 0));
        assert_eq!(fs::read(out.join("a")).unwrap(), b"b");
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn reader_accepts_the_spec_derived_archives() {
        let dir = tmp("specread");
        fs::create_dir_all(&dir).unwrap();
        let a = dir.join("Game.rgssad");
        fs::write(&a, V1_TWO).unwrap();
        let names: Vec<String> = open(a.to_str().unwrap()).unwrap().entries.iter().map(|e| e.name.clone()).collect();
        assert_eq!(names, vec!["Data/Scripts.rxdata".to_string(), "a".to_string()]);

        let b = dir.join("Game.rgss3a");
        fs::write(&b, V3_THREE).unwrap();
        let arch = open(b.to_str().unwrap()).unwrap();
        assert_eq!(arch.version, 3);
        assert_eq!(arch.entries.len(), 3);
        assert_eq!(arch.entries[0].name, "Graphics/P.png");
        assert_eq!(arch.entries[1].name, "a.txt");
        assert_eq!(arch.entries[2].name, "empty.dat");
        assert_eq!(arch.entries[2].size, 0);
        assert_eq!(arch.total_bytes(), 17);
        let out = dir.join("out");
        assert_eq!(extract_rgss(b.to_str().unwrap(), out.to_str().unwrap(), None).unwrap(), (3, 0));
        assert_eq!(fs::read(out.join("Graphics/P.png")).unwrap(), vec![3u8; 11]);
        assert_eq!(fs::read(out.join("a.txt")).unwrap(), b"parity");
        assert_eq!(fs::read(out.join("empty.dat")).unwrap(), b"");
        fs::remove_dir_all(&dir).ok();
    }

    // ── writer against the spec bytes ──

    #[test]
    fn v1_writer_matches_spec_bytes() {
        let _guard = pack_lock();
        let dir = tmp("w1");
        let indir = dir.join("in");
        write_tree(&indir, &[("a", b"hello"), ("Data/Scripts.rxdata", &[0u8; 9])]);
        let arc = dir.join("out.rgssad");
        assert_eq!(create(indir.to_str().unwrap(), arc.to_str().unwrap(), 1).unwrap(), 2);
        assert_eq!(fs::read(&arc).unwrap(), V1_TWO, "v1 writer drifted from the spec");
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn v3_writer_matches_spec_bytes() {
        let _guard = pack_lock();
        let dir = tmp("w3");
        let indir = dir.join("in");
        write_tree(&indir, &[
            ("Graphics/P.png", &[3u8; 11]),
            ("a.txt", b"parity"),
            ("empty.dat", b""),
        ]);
        let arc = dir.join("out.rgss3a");
        assert_eq!(create(indir.to_str().unwrap(), arc.to_str().unwrap(), 3).unwrap(), 3);
        assert_eq!(fs::read(&arc).unwrap(), V3_THREE, "v3 writer drifted from the spec");
        fs::remove_dir_all(&dir).ok();
    }

    // ── round trips ──

    #[test]
    fn v3_round_trip() {
        let _guard = pack_lock();
        let dir = tmp("rt3");
        let indir = dir.join("in");
        fs::create_dir_all(indir.join("Data")).unwrap();
        fs::create_dir_all(indir.join("Graphics/Characters")).unwrap();
        fs::write(indir.join("Data/Scripts.rvdata2"), b"module Kernel\r\nend\r\n").unwrap();
        fs::write(indir.join("Graphics/Characters/主人公.png"), vec![0x89, b'P', b'N', b'G']).unwrap();
        fs::write(indir.join("zero.bin"), b"").unwrap();
        // Larger than one 256KiB stream chunk so the payload cipher is
        // exercised across key rotations in both directions.
        let big: Vec<u8> = (0..900_000u32).map(|i| (i % 251) as u8).collect();
        fs::write(indir.join("Graphics/big.pak"), &big).unwrap();

        let arc = dir.join("Game.rgss3a");
        assert_eq!(create(indir.to_str().unwrap(), arc.to_str().unwrap(), 3).unwrap(), 4);

        let a = open(arc.to_str().unwrap()).unwrap();
        assert_eq!(a.version, 3);
        let names: Vec<&str> = a.entries.iter().map(|e| e.name.as_str()).collect();
        assert!(names.contains(&"Data/Scripts.rvdata2"), "{names:?}");
        assert!(names.contains(&"Graphics/Characters/主人公.png"), "{names:?}");
        // Backslashes are normalized on read.
        assert!(!names.iter().any(|n| n.contains('\\')), "{names:?}");

        let listing = list_rgss(arc.to_str().unwrap()).unwrap();
        assert!(listing.contains(r#"{"n":"Graphics","s":0,"d":true,"e":false}"#), "{listing}");
        assert!(listing.contains(r#""n":"Data/Scripts.rvdata2","s":20,"d":false,"e":false}"#), "{listing}");

        let out = dir.join("out");
        let (total, fail) = extract_rgss(arc.to_str().unwrap(), out.to_str().unwrap(), None).unwrap();
        assert_eq!((total, fail), (4, 0));
        assert_eq!(fs::read(out.join("Data/Scripts.rvdata2")).unwrap(), b"module Kernel\r\nend\r\n");
        assert_eq!(fs::read(out.join("Graphics/Characters/主人公.png")).unwrap(), vec![0x89, b'P', b'N', b'G']);
        assert_eq!(fs::read(out.join("zero.bin")).unwrap(), b"");
        assert_eq!(fs::read(out.join("Graphics/big.pak")).unwrap(), big);
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn v1_round_trip() {
        let _guard = pack_lock();
        let dir = tmp("rt1");
        let indir = dir.join("in");
        fs::create_dir_all(indir.join("Graphics")).unwrap();
        fs::write(indir.join("Scripts.rxdata"), b"x").unwrap();
        let mid: Vec<u8> = (0..5_000u32).map(|i| (i % 7) as u8).collect();
        fs::write(indir.join("Graphics/Tileset.bin"), &mid).unwrap();
        // A name that only survives a UTF-8 -> CP932 -> UTF-8 round trip.
        let cjk = "書.wav";
        fs::write(indir.join(cjk), b"OggS\x00payload").unwrap();

        let arc = dir.join("Game.rgssad");
        assert_eq!(create(indir.to_str().unwrap(), arc.to_str().unwrap(), 1).unwrap(), 3);
        let a = open(arc.to_str().unwrap()).unwrap();
        assert_eq!(a.version, 1);
        let names: Vec<&str> = a.entries.iter().map(|e| e.name.as_str()).collect();
        assert!(names.contains(&cjk), "{names:?}");
        assert!(names.contains(&"Graphics/Tileset.bin"), "{names:?}");

        let out = dir.join("out");
        let (total, fail) = extract_rgss(arc.to_str().unwrap(), out.to_str().unwrap(), None).unwrap();
        assert_eq!((total, fail), (3, 0));
        assert_eq!(fs::read(out.join("Graphics/Tileset.bin")).unwrap(), mid);
        assert_eq!(fs::read(out.join(cjk)).unwrap(), b"OggS\x00payload");
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn decodes_shift_jis_names() {
        // 0x8F 0x91 is 書 in Shift-JIS and invalid UTF-8 on its own.
        let raw = [0x8Fu8, 0x91, 0x2E, 0x77, 0x61, 0x76];
        assert_eq!(decode_name(&raw), "書.wav");
        // ASCII and UTF-8 names take the direct path.
        assert_eq!(decode_name(b"Data/A.png"), "Data/A.png");
        assert_eq!(decode_name("データ.txt".as_bytes()), "データ.txt");
        // Backslashes normalize; undecodable bytes degrade instead of failing.
        assert_eq!(decode_name(b"Data\\Map.rvdata2"), "Data/Map.rvdata2");
        assert_eq!(decode_name(&[0xFF, 0xFE, b'a']), "__a");
    }

    #[test]
    fn selective_extract_matches_dir_prefix() {
        let _guard = pack_lock();
        let dir = tmp("sel");
        let indir = dir.join("in");
        write_tree(&indir, &[("Data/A.rvdata2", b"a"), ("Graphics/B.png", b"bb"), ("Data/sub/C.png", b"c")]);
        let arc = dir.join("Game.rgss3a");
        create(indir.to_str().unwrap(), arc.to_str().unwrap(), 3).unwrap();

        let out = dir.join("out");
        let (total, fail) = extract_rgss(arc.to_str().unwrap(), out.to_str().unwrap(), Some("Data")).unwrap();
        assert_eq!((total, fail), (2, 0), "directory prefix must pull in nested entries");
        assert!(out.join("Data/A.rvdata2").exists());
        assert!(out.join("Data/sub/C.png").exists());
        assert!(!out.join("Graphics/B.png").exists());

        let out2 = dir.join("out2");
        let (t2, f2) = extract_rgss(arc.to_str().unwrap(), out2.to_str().unwrap(), Some("Graphics/B.png")).unwrap();
        assert_eq!((t2, f2), (1, 0));
        assert!(!out2.join("Data").exists());
        fs::remove_dir_all(&dir).ok();
    }

    // ── hostile / degenerate input ──

    #[test]
    fn rejects_bad_magic_and_version() {
        let dir = tmp("bad");
        fs::create_dir_all(&dir).unwrap();

        let a = dir.join("a.rgss3a");
        fs::write(&a, b"NOTRGSS\x03abcdefgh").unwrap();
        assert!(open(a.to_str().unwrap()).unwrap_err().contains("bad magic"));

        // seed 0 -> key 3, so the terminator on disk is the key itself.
        let b = dir.join("b.rgss3a");
        let mut v = b"RGSSAD\0\x03".to_vec();
        v.extend_from_slice(&0u32.to_le_bytes());
        v.extend_from_slice(&3u32.to_le_bytes());
        fs::write(&b, &v).unwrap();
        assert!(open(b.to_str().unwrap()).unwrap_err().contains("no entries"));

        let tiny = dir.join("tiny.rgss3a");
        fs::write(&tiny, b"RGSSAD").unwrap();
        assert!(open(tiny.to_str().unwrap()).unwrap_err().contains("too small"));

        let c = dir.join("c.rgss3a");
        let mut v = b"RGSSAD\0\x09".to_vec();
        v.extend_from_slice(&[0u8; 32]);
        fs::write(&c, &v).unwrap();
        assert!(open(c.to_str().unwrap()).unwrap_err().contains("unsupported archive version 9"));

        // A v3 index that runs off the end of the file instead of hitting the
        // zero-offset terminator.
        let d = dir.join("d.rgss3a");
        let mut v = b"RGSSAD\0\x03".to_vec();
        v.extend_from_slice(&0u32.to_le_bytes());
        v.extend_from_slice(&7u32.to_le_bytes());
        fs::write(&d, &v).unwrap();
        assert!(open(d.to_str().unwrap()).unwrap_err().contains("truncated index"));

        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn rejects_hostile_index() {
        let dir = tmp("hostile");
        fs::create_dir_all(&dir).unwrap();
        let key: u32 = 3;

        // v3: absurd name length
        let a = dir.join("a.rgss3a");
        let mut v = b"RGSSAD\0\x03".to_vec();
        v.extend_from_slice(&0u32.to_le_bytes());
        v.extend_from_slice(&(64u32 ^ key).to_le_bytes());
        v.extend_from_slice(&key.to_le_bytes()); // entry key, plaintext 0
        v.extend_from_slice(&key.to_le_bytes()); // entry key, plaintext 0
        v.extend_from_slice(&(0xFFFF_FFFFu32 ^ key).to_le_bytes());
        v.extend_from_slice(&[0u8; 16]);
        fs::write(&a, &v).unwrap();
        assert!(open(a.to_str().unwrap()).unwrap_err().contains("implausible v3 name length"));

        // v3: data offset past EOF
        let b = dir.join("b.rgss3a");
        let mut v = b"RGSSAD\0\x03".to_vec();
        v.extend_from_slice(&0u32.to_le_bytes());
        v.extend_from_slice(&(1_000_000u32 ^ key).to_le_bytes());
        v.extend_from_slice(&(16u32 ^ key).to_le_bytes());
        v.extend_from_slice(&key.to_le_bytes()); // entry key, plaintext 0
        v.extend_from_slice(&(1u32 ^ key).to_le_bytes());
        v.push(b'x');
        v.extend_from_slice(&key.to_le_bytes());
        fs::write(&b, &v).unwrap();
        assert!(open(b.to_str().unwrap()).unwrap_err().contains("past end of file"));

        // v1: absurd name length (header plus enough room to read the field)
        let c = dir.join("c.rgssad");
        let mut v = b"RGSSAD\0\x01".to_vec();
        v.extend_from_slice(&(0xFFFF_FFFFu32 ^ 0xDEAD_CAFE).to_le_bytes());
        v.extend_from_slice(&[0u8; 16]);
        fs::write(&c, &v).unwrap();
        assert!(open(c.to_str().unwrap()).unwrap_err().contains("implausible v1 name length"));

        // v1: entry header cut short by EOF
        let d = dir.join("d.rgssad");
        fs::write(&d, b"RGSSAD\0\x01\x01\x02\x03").unwrap();
        assert!(open(d.to_str().unwrap()).unwrap_err().contains("truncated v1 entry header"));

        // v1: name longer than the rest of the file
        let e = dir.join("e.rgssad");
        let mut v = b"RGSSAD\0\x01".to_vec();
        v.extend_from_slice(&(1000u32 ^ 0xDEAD_CAFE).to_le_bytes());
        v.extend_from_slice(&[0u8; 8]);
        fs::write(&e, &v).unwrap();
        assert!(open(e.to_str().unwrap()).unwrap_err().contains("implausible v1 name length"));

        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn rejects_path_traversal_entries() {
        let dir = tmp("trav");
        fs::create_dir_all(&dir).unwrap();
        let key: u32 = 3;
        let name = b"..\\..\\evil.txt";
        let data_start = 12 + 16 + name.len() + 4;
        let mut v = b"RGSSAD\0\x03".to_vec();
        v.extend_from_slice(&0u32.to_le_bytes());
        v.extend_from_slice(&((data_start as u32) ^ key).to_le_bytes());
        v.extend_from_slice(&(5u32 ^ key).to_le_bytes());
        v.extend_from_slice(&key.to_le_bytes()); // entry key, plaintext 0
        v.extend_from_slice(&(name.len() as u32 ^ key).to_le_bytes());
        for (i, &b) in name.iter().enumerate() { v.push(b ^ key.to_le_bytes()[i & 3]); }
        v.extend_from_slice(&key.to_le_bytes());
        v.extend_from_slice(b"pwned");
        let arc = dir.join("Game.rgss3a");
        fs::write(&arc, &v).unwrap();

        let out = dir.join("out");
        let (total, fail) = extract_rgss(arc.to_str().unwrap(), out.to_str().unwrap(), None).unwrap();
        assert_eq!((total, fail), (1, 1), "traversal entry must be counted as a failure");
        assert!(!dir.join("evil.txt").exists());
        assert!(walk(&out).is_empty());
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn duplicate_names_do_not_truncate() {
        // Two index entries can share a name (or differ only in case) — a
        // hostile or hand-edited archive. /sdcard and FAT are case-insensitive,
        // so writing both to the same path would silently destroy one payload.
        let dir = tmp("dup");
        fs::create_dir_all(&dir).unwrap();
        let key: u32 = 3;
        let names = ["Readme.txt", "readme.txt", "Readme.txt"];
        let payloads: [&[u8]; 3] = [b"one", b"twotwo", b"three"];
        let index_len: usize = names.iter().map(|n| 16 + n.len()).sum::<usize>() + 4;
        let mut v: Vec<u8> = b"RGSSAD\0\x03".to_vec();
        v.extend_from_slice(&0u32.to_le_bytes());
        let mut cursor = 12 + index_len;
        for (name, data) in names.iter().zip(payloads.iter()) {
            v.extend_from_slice(&((cursor as u32) ^ key).to_le_bytes());
            v.extend_from_slice(&((data.len() as u32) ^ key).to_le_bytes());
            v.extend_from_slice(&key.to_le_bytes()); // entry key, plaintext 0
            v.extend_from_slice(&(name.len() as u32 ^ key).to_le_bytes());
            for (i, &b) in name.as_bytes().iter().enumerate() { v.push(b ^ key.to_le_bytes()[i & 3]); }
            cursor += data.len();
        }
        v.extend_from_slice(&key.to_le_bytes());
        // Entry key 0 still means "encrypted": each 4-byte group is XORed with
        // the current key and the key then advances. Spelled out here so the
        // fixture does not go through the production cipher.
        for data in payloads {
            let mut k = 0u32;
            for g in data.chunks(4) {
                for (j, &b) in g.iter().enumerate() { v.push(b ^ ((k >> (8 * j)) & 0xFF) as u8); }
                k = k.wrapping_mul(7).wrapping_add(3);
            }
        }
        let arc = dir.join("Game.rgss3a");
        fs::write(&arc, &v).unwrap();

        let a = open(arc.to_str().unwrap()).unwrap();
        assert_eq!(a.entries.len(), 3);
        let out = dir.join("out");
        let (total, fail) = extract_rgss(arc.to_str().unwrap(), out.to_str().unwrap(), None).unwrap();
        assert_eq!((total, fail), (3, 0));
        let written = walk(&out);
        assert_eq!(written.len(), 3, "{written:?}");
        for payload in payloads {
            assert!(written.iter().any(|(_, d)| d == payload), "{written:?}");
        }
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn create_rejects_unsupported_version_and_empty_dir() {
        let _guard = pack_lock();
        let dir = tmp("crej");
        let empty = dir.join("empty");
        fs::create_dir_all(&empty).unwrap();
        let arc = dir.join("o.rgss3a");
        assert!(create(empty.to_str().unwrap(), arc.to_str().unwrap(), 2).is_err(), "v2 is read-only");
        assert!(create(empty.to_str().unwrap(), arc.to_str().unwrap(), 3).unwrap_err().contains("no files"));
        assert!(create(empty.to_str().unwrap(), arc.to_str().unwrap(), 9).is_err());
        assert!(create(dir.join("nope").to_str().unwrap(), arc.to_str().unwrap(), 3).is_err());
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn packs_a_single_file() {
        let _guard = pack_lock();
        let dir = tmp("one");
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("one.dat"), vec![7u8; 8000]).unwrap();
        let arc = dir.join("one.rgss3a");
        assert_eq!(create(dir.join("one.dat").to_str().unwrap(), arc.to_str().unwrap(), 3).unwrap(), 1);
        let out = dir.join("out");
        assert_eq!(extract_rgss(arc.to_str().unwrap(), out.to_str().unwrap(), None).unwrap(), (1, 0));
        assert_eq!(fs::read(out.join("one.dat")).unwrap(), vec![7u8; 8000]);
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn cancel_aborts_a_pack_in_progress() {
        let _guard = pack_lock();
        let dir = tmp("cancel");
        let indir = dir.join("in");
        write_tree(&indir, &[("a.bin", &vec![9u8; 4_000_000]), ("b.bin", &vec![9u8; 4_000_000])]);
        let arc = dir.join("out.rgss3a");
        // Poison the flag the way a mid-run cancel would, then confirm the
        // writer refuses rather than finishing the archive.
        compress_progress::cancel();
        let res = create(indir.to_str().unwrap(), arc.to_str().unwrap(), 3);
        compress_progress::clear_cancel();
        assert_eq!(res.unwrap_err(), "cancelled");
        fs::remove_dir_all(&dir).ok();
    }

    // ── RPG Maker MV / MZ loose assets ──

    /// Fixtures built by a separate implementation written straight off the
    /// format notes (same rationale as V1_TWO / V3_THREE): the "encryption" is
    /// `RPGM header + (asset[0..16] XOR keystream) + asset[16..]`, where the
    /// keystream is the MD5 of System.json's `encryptionKey` — empty in
    /// practice, so MD5("") = d41d8cd98f00b204e9800998ecf8427e.
    ///
    /// A PNG: 16-byte signature window + the start of the IHDR chunk.
    const MV_PNG_ENC: &[u8] = &[
        82, 80, 71, 77, 86, 0, 0, 0, 0, 3, 1, 0, 0, 0, 0, 0,
        93, 77, 194, 158, 130, 10, 168, 14, 233, 128, 9, 149, 165, 176, 6, 44,
        0, 0, 1, 64, 0, 0, 0, 240, 8, 6, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0,
    ];
    const MV_PNG_PLAIN: &[u8] = &[
        137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82,
        0, 0, 1, 64, 0, 0, 0, 240, 8, 6, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0,
    ];

    /// An Ogg stream of two pages, serial 0x1234ABCD. The serial's low two
    /// bytes sit inside the encrypted window and are only recoverable from the
    /// second page, which is what the parser has to do.
    const MV_OGG_ENC: &[u8] = &[
        82, 80, 71, 77, 86, 0, 0, 0, 0, 3, 1, 0, 0, 0, 0, 0,
        155, 122, 235, 138, 143, 2, 178, 4, 233, 128, 9, 152, 236, 248, 143, 213,
        52, 18, 0, 0, 0, 0, 0, 0, 0, 0, 1, 20, 0, 1, 2, 3,
        4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
        79, 103, 103, 83, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0, 205, 171,
        52, 18, 1, 0, 0, 0, 0, 0, 0, 0, 1, 24, 100, 101, 102, 103,
        104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115, 116, 117, 118, 119,
        120, 121, 122, 123,
    ];
    const MV_OGG_PLAIN: &[u8] = &[
        79, 103, 103, 83, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 205, 171,
        52, 18, 0, 0, 0, 0, 0, 0, 0, 0, 1, 20, 0, 1, 2, 3,
        4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
        79, 103, 103, 83, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0, 205, 171,
        52, 18, 1, 0, 0, 0, 0, 0, 0, 0, 1, 24, 100, 101, 102, 103,
        104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115, 116, 117, 118, 119,
        120, 121, 122, 123,
    ];

    #[test]
    fn mv_decrypts_an_encrypted_png() {
        let dir = tmp("mv_png");
        fs::create_dir_all(&dir).unwrap();
        let src = dir.join("hero.rpgmvp");
        fs::write(&src, MV_PNG_ENC).unwrap();

        // The `.rpgmvp` extension becomes `.png`, which is what makes the one
        // decoded entry open in the image viewer.
        let listing = mv_list(src.to_str().unwrap()).unwrap();
        assert!(listing.contains(r#""n":"hero.png""#), "{listing}");
        assert!(listing.contains(r#""s":69"#), "{listing}");

        let out = dir.join("out");
        assert_eq!(mv_extract(src.to_str().unwrap(), out.to_str().unwrap(), None).unwrap(), (1, 0));
        assert_eq!(fs::read(out.join("hero.png")).unwrap(), MV_PNG_PLAIN);
        fs::remove_dir_all(&dir).ok();
    }

    /// A REAL 1x1 PNG (built with zlib/CRC by an independent encoder) rather
    /// than a hand-made blob, so the round-trip is checked against a file that
    /// is structurally valid end to end.
    const REAL_PNG_ENC: &[u8] = &[
        82, 80, 71, 77, 86, 0, 0, 0, 0, 3, 1, 0,
        0, 0, 0, 0, 93, 77, 194, 158, 130, 10, 168, 14,
        233, 128, 9, 149, 165, 176, 6, 44, 0, 0, 0, 1,
        0, 0, 0, 1, 8, 2, 0, 0, 0, 144, 119, 83,
        222, 0, 0, 0, 12, 73, 68, 65, 84, 120, 156, 99,
        248, 207, 192, 0, 0, 3, 1, 1, 0, 201, 254, 146,
        239, 0, 0, 0, 0, 73, 69, 78, 68, 174, 66, 96,
        130,
    ];
    const REAL_PNG_PLAIN: &[u8] = &[
        137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13,
        73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1,
        8, 2, 0, 0, 0, 144, 119, 83, 222, 0, 0, 0,
        12, 73, 68, 65, 84, 120, 156, 99, 248, 207, 192, 0,
        0, 3, 1, 1, 0, 201, 254, 146, 239, 0, 0, 0,
        0, 73, 69, 78, 68, 174, 66, 96, 130,

    ];

    #[test]
    fn mv_decrypts_a_real_png_byte_for_byte() {
        let dir = tmp("mv_real");
        fs::create_dir_all(&dir).unwrap();
        let src = dir.join("real.rpgmvp");
        fs::write(&src, REAL_PNG_ENC).unwrap();
        let out = dir.join("out");
        assert_eq!(mv_extract(src.to_str().unwrap(), out.to_str().unwrap(), None).unwrap(), (1, 0));
        let got = fs::read(out.join("real.png")).unwrap();
        assert_eq!(got.len(), 69);
        assert_eq!(got, REAL_PNG_PLAIN);
        // and it is still a structurally valid PNG
        assert_eq!(&got[..8], PNG_SIG);
        assert_eq!(&got[12..16], b"IHDR");
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn mv_recovers_the_ogg_serial_from_the_second_page() {
        let dir = tmp("mv_ogg");
        fs::create_dir_all(&dir).unwrap();
        let src = dir.join("BGM_Field.rpgmvo");
        fs::write(&src, MV_OGG_ENC).unwrap();

        let listing = mv_list(src.to_str().unwrap()).unwrap();
        assert!(listing.contains(r#""n":"BGM_Field.ogg""#), "{listing}");

        let out = dir.join("out");
        assert_eq!(mv_extract(src.to_str().unwrap(), out.to_str().unwrap(), None).unwrap(), (1, 0));
        // The serial's low two bytes (205, 171) can only be right if the second
        // page was walked.
        assert_eq!(fs::read(out.join("BGM_Field.ogg")).unwrap(), MV_OGG_PLAIN);
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn mv_passes_through_unencrypted_content() {
        // Repacked MV games routinely ship these extensions with plain content.
        let dir = tmp("mv_plain");
        fs::create_dir_all(&dir).unwrap();
        let png = dir.join("Plain.rpgmvp");
        fs::write(&png, MV_PNG_PLAIN).unwrap();
        let listing = mv_list(png.to_str().unwrap()).unwrap();
        assert!(listing.contains(r#""n":"Plain.png""#), "{listing}");
        let out = dir.join("out");
        assert_eq!(mv_extract(png.to_str().unwrap(), out.to_str().unwrap(), None).unwrap(), (1, 0));
        assert_eq!(fs::read(out.join("Plain.png")).unwrap(), MV_PNG_PLAIN);

        // An unknown extension with a recognizable body falls back to sniffing.
        let odd = dir.join("mystery.dat");
        fs::write(&odd, b"RIFF....WAVEfmt ").unwrap();
        assert!(mv_list(odd.to_str().unwrap()).unwrap().contains(r#""n":"mystery.wav""#));
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn mv_rejects_files_it_cannot_decode() {
        let dir = tmp("mv_bad");
        fs::create_dir_all(&dir).unwrap();

        let empty = dir.join("empty.rpgmvp");
        fs::write(&empty, b"").unwrap();
        assert!(mv_plan(empty.to_str().unwrap()).unwrap_err().contains("empty"));

        // Too short to hold a header plus an encrypted window.
        let tiny = dir.join("tiny.rpgmvp");
        let mut short = RPGM_HEADER.to_vec();
        short.extend_from_slice(&[7u8; 4]);
        fs::write(&tiny, &short).unwrap();
        assert!(mv_plan(tiny.to_str().unwrap()).unwrap_err().contains("too short"));

        // A file with an RPGM header that is truncated inside the obfuscated
        // window cannot yield a keystream at all.
        let clipped = dir.join("clipped.rpgmvp");
        let mut short = RPGM_HEADER.to_vec();
        short.extend_from_slice(&[7u8; 4]);
        fs::write(&clipped, &short).unwrap();
        assert!(mv_plan(clipped.to_str().unwrap()).unwrap_err().contains("too short"));

        // A file that merely *starts* with the magic but is otherwise a
        // different asset is handed back untouched rather than mangled.
        let notmv = dir.join("notmv.rpgmvp");
        fs::write(&notmv, b"\x89PNG\r\n\x1a\nplain").unwrap();
        assert_eq!(mv_plan(notmv.to_str().unwrap()).unwrap().decoded_size(), 13);

        // A correct RPGM header whose payload decrypts to something that is
        // not a PNG must be refused rather than written out as garbage.
        let mut forged = RPGM_HEADER.to_vec();
        forged.extend_from_slice(&[0x11; 16]);
        forged.extend_from_slice(&[0x22; 48]);
        let bad = dir.join("forged.rpgmvp");
        fs::write(&bad, &forged).unwrap();
        assert!(mv_plan(bad.to_str().unwrap()).unwrap_err().contains("PNG dimensions"));

        // A single-page Ogg has no second page to read the serial from; the
        // structural check on the page sequence number still has to pass.
        let single = dir.join("single.rpgmvo");
        let key = [
            212u8, 29, 140, 217, 143, 0, 178, 4, 233, 128, 9, 152, 236, 248, 66, 126,
        ];
        let mut p1 = Vec::new();
        p1.extend_from_slice(b"OggS\x00\x02");
        p1.extend_from_slice(&0u32.to_le_bytes());
        p1.extend_from_slice(&0x1234ABCDu32.to_le_bytes());
        p1.extend_from_slice(&0u32.to_le_bytes());
        p1.extend_from_slice(&0u32.to_le_bytes());
        p1.push(1);
        p1.push(8);
        p1.extend_from_slice(&[9u8; 8]);
        let mut enc = RPGM_HEADER.to_vec();
        enc.extend((0..16).map(|i| p1[i] ^ key[i]));
        enc.extend_from_slice(&p1[16..]);
        fs::write(&single, &enc).unwrap();
        assert!(mv_plan(single.to_str().unwrap()).is_ok(), "one page is enough here");

        fs::remove_dir_all(&dir).ok();
    }

    /// The same asset obfuscated with the default key (MD5 of the empty string),
    /// using RPG Maker's real 16-byte header. Generated from MV_M4A_PLAIN by the
    /// regression harness, not typed by hand.
    const MV_M4A_ENC: &[u8] = &[
        82, 80, 71, 77, 86, 0, 0, 0, 0, 3, 1, 0,
        0, 0, 0, 0, 212, 29, 140, 249, 233, 116, 203, 116,
        164, 180, 72, 184, 236, 248, 66, 126, 77, 52, 65, 32,
        109, 112, 52, 50, 105, 115, 111, 109, 0, 0, 0, 0,
        0, 0, 4, 66, 109, 111, 111, 118, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0,
    ];
    /// Real header of the reference corpus' `AudioMpeg` asset: a 32-byte `ftyp`
    /// box, minor version 0.0.0.0, four compat brands, and `moov` at tail[20] —
    /// the layout a 24-byte probe window could not reach, and the minor version
    /// an earlier revision of this file got wrong (it assumed 0.0.2.0).
    const MV_M4A_PLAIN: &[u8] = &[
        0, 0, 0, 32, 102, 116, 121, 112, 77, 52, 65, 32,
        0, 0, 0, 0, 77, 52, 65, 32, 109, 112, 52, 50,
        105, 115, 111, 109, 0, 0, 0, 0, 0, 0, 4, 66,
        109, 111, 111, 118, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    ];

    #[test]
    fn mv_recovers_the_m4a_ftyp_box_size() {
        let dir = tmp("mv_m4a");
        fs::create_dir_all(&dir).unwrap();
        let src = dir.join("Sound.rpgmvm");
        fs::write(&src, MV_M4A_ENC).unwrap();
        let listing = mv_list(src.to_str().unwrap()).unwrap();
        assert!(listing.contains(r#""n":"Sound.m4a""#), "{listing}");
        let out = dir.join("out");
        assert_eq!(mv_extract(src.to_str().unwrap(), out.to_str().unwrap(), None).unwrap(), (1, 0));
        let got = fs::read(out.join("Sound.m4a")).unwrap();
        // The recovered box size has to be right or the MP4 will not parse.
        assert_eq!(got, MV_M4A_PLAIN);
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn md5_matches_the_standard_vectors() {
        // RFC 1321 test suite.
        assert_eq!(hex(&md5(b"")), "d41d8cd98f00b204e9800998ecf8427e");
        assert_eq!(hex(&md5(b"a")), "0cc175b9c0f1b6a831c399e269772661");
        assert_eq!(hex(&md5(b"abc")), "900150983cd24fb0d6963f7d28e17f72");
        assert_eq!(hex(&md5(b"message digest")), "f96b697d7cb7938d525a2f31aaf161d0");
        assert_eq!(hex(&md5(b"abcdefghijklmnopqrstuvwxyz")), "c3fcd3d76192e4007dfb496cca67e13b");
        assert_eq!(hex(&md5(b"12345678901234567890123456789012345678901234567890123456789012345678901234567890")),
                   "57edf4a22be3c955ac49da2e2107b67a");
        // The default RPG Maker key: MD5 of an empty `encryptionKey`.
        assert_eq!(hex(&md5(b"")), "d41d8cd98f00b204e9800998ecf8427e");
    }

    #[test]
    fn mv_keystream_input_accepts_hex_or_text() {
        // 32 hex digits: used verbatim.
        assert_eq!(hex(&mv_keystream_from_input("d41d8cd98f00b204e9800998ecf8427e").unwrap()),
                   "d41d8cd98f00b204e9800998ecf8427e");
        assert_eq!(hex(&mv_keystream_from_input("  D41D8CD98F00B204E9800998ECF8427E  ").unwrap()),
                   "d41d8cd98f00b204e9800998ecf8427e");
        // Anything else: MD5 of the text, per RPG Maker's own rule.
        assert_eq!(hex(&mv_keystream_from_input("").unwrap()), hex(&md5(b"")));
        assert_eq!(hex(&mv_keystream_from_input("hunter2").unwrap()), hex(&md5(b"hunter2")));
        // A 32-character non-hex string is a key, not a keystream.
        let zs = "z".repeat(32);
        assert_eq!(hex(&mv_keystream_from_input(&zs).unwrap()), hex(&md5(zs.as_bytes())));
        assert!(mv_keystream_from_input(&"x".repeat(600)).is_err());
    }

    #[test]
    fn mv_encrypt_round_trips_byte_for_byte() {
        let _guard = pack_lock();
        let dir = tmp("mv_pack");
        fs::create_dir_all(&dir).unwrap();
        let key = mv_keystream_from_input("").unwrap();
        assert_eq!(hex(&key), "d41d8cd98f00b204e9800998ecf8427e");

        let obf = dir.join("obf");
        let back = dir.join("back");
        for (plain, decoded, obfuscated) in [
            (MV_PNG_PLAIN, "hero.png", "hero.rpgmvp"),
            (MV_OGG_PLAIN, "BGM.ogg", "BGM.rpgmvo"),
            (MV_M4A_PLAIN, "clip.m4a", "clip.rpgmvm"),
        ] {
            let src = dir.join(decoded);
            fs::write(&src, plain).unwrap();
            let enc = obf.join(obfuscated);
            mv_encrypt(src.to_str().unwrap(), enc.to_str().unwrap(), &key).unwrap();
            // obfuscated size = asset + the 16-byte RPGM header
            assert_eq!(fs::metadata(&enc).unwrap().len(), plain.len() as u64 + 16, "{obfuscated}");

            assert_eq!(mv_extract(enc.to_str().unwrap(), back.to_str().unwrap(), None).unwrap(), (1, 0));
            assert_eq!(fs::read(back.join(decoded)).unwrap(), plain, "{obfuscated} did not round trip");
        }
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn mv_encrypt_refuses_degenerate_input() {
        let _guard = pack_lock();
        let dir = tmp("mv_packbad");
        fs::create_dir_all(&dir).unwrap();
        let src = dir.join("a.png");
        fs::write(&src, MV_PNG_PLAIN).unwrap();
        // 32 zeros would produce a plain asset behind a header.
        let out = dir.join("a.rpgmvp");
        assert!(mv_encrypt(src.to_str().unwrap(), out.to_str().unwrap(), &[0u8; 16]).unwrap_err().contains("all zeros"));
        assert!(!out.exists(), "no half-written output on refusal");
        // Too short to have a 16-byte header.
        let tiny = dir.join("t.png");
        fs::write(&tiny, b"short").unwrap();
        let out2 = dir.join("t.rpgmvp");
        assert!(mv_encrypt(tiny.to_str().unwrap(), out2.to_str().unwrap(), &md5(b"x")).unwrap_err().contains("too short"));
        assert!(!out2.exists());
        // A non-default key still produces something the reader can undo.
        let out3 = dir.join("k.rpgmvp");
        mv_encrypt(src.to_str().unwrap(), out3.to_str().unwrap(), &md5(b"hunter2")).unwrap();
        assert_ne!(fs::read(&out3).unwrap()[16..32], MV_PNG_PLAIN[..16]);
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn mv_encrypt_refuses_already_obfuscated_and_wrong_media_type() {
        let _guard = pack_lock();
        let dir = tmp("mv_packguard");
        fs::create_dir_all(&dir).unwrap();
        let key = mv_keystream_from_input("").unwrap();

        // Packing an already-obfuscated asset: it starts with the RPGM header,
        // and the result would be unreadable (and look plausible).
        let obf = dir.join("hero.rpgmvp");
        fs::write(&obf, MV_PNG_ENC).unwrap();
        let again = dir.join("twice.rpgmvp");
        let e = mv_encrypt(obf.to_str().unwrap(), again.to_str().unwrap(), &key).unwrap_err();
        assert!(e.contains("already"), "{e}");
        assert!(!again.exists(), "no half-written output on refusal");

        // Wrong media type: a JPEG must not be hidden behind .rpgmvp, or the
        // engine loads it as a picture and shows nothing.
        let jpg = dir.join("photo.jpg");
        let mut j = vec![0xFF, 0xD8, 0xFF, 0xE0];
        j.extend_from_slice(&[7u8; 28]);
        fs::write(&jpg, &j).unwrap();
        for (out_name, what) in [("photo.rpgmvp", "PNG"), ("photo.rpgmvo", "OGG"), ("photo.rpgmvm", "M4A")] {
            let out = dir.join(out_name);
            let e = mv_encrypt(jpg.to_str().unwrap(), out.to_str().unwrap(), &key).unwrap_err();
            assert!(e.contains(&format!("is not {what}")), "{out_name}: {e}");
            assert!(!out.exists(), "{out_name} left behind");
        }
        // The right container still works, proving the guard is not over-eager.
        let ok = dir.join("photo.bin");
        mv_encrypt(jpg.to_str().unwrap(), ok.to_str().unwrap(), &key).unwrap();
        assert_eq!(fs::metadata(&ok).unwrap().len(), j.len() as u64 + 16);
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn mv_selected_extract_and_collision_handling() {
        let dir = tmp("mv_sel");
        fs::create_dir_all(&dir).unwrap();
        let src = dir.join("hero.rpgmvp");
        fs::write(&src, MV_PNG_ENC).unwrap();

        let out = dir.join("out");
        let (total, fail) = mv_extract(src.to_str().unwrap(), out.to_str().unwrap(), Some("something-else")).unwrap();
        assert_eq!((total, fail), (0, 0));
        let (total, fail) = mv_extract(src.to_str().unwrap(), out.to_str().unwrap(), Some("hero.png")).unwrap();
        assert_eq!((total, fail), (1, 0));

        // A second run over the same directory overwrites, which is the
        // documented scope of DestAllocator (it guards collisions *within* one
        // run — an MV asset is a single entry, so that cannot arise here).
        let (total, fail) = mv_extract(src.to_str().unwrap(), out.to_str().unwrap(), None).unwrap();
        assert_eq!((total, fail), (1, 0));
        assert_eq!(fs::read(out.join("hero.png")).unwrap(), MV_PNG_PLAIN);
        assert_eq!(fs::read_dir(&out).unwrap().count(), 1);
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn mv_kind_mapping() {
        assert_eq!(mv_kind_of("a/b/hero.rpgmvp"), MvKind::Png);
        assert_eq!(mv_kind_of("HERO.PNG_"), MvKind::Png);
        assert_eq!(mv_kind_of("a.rpgmvo"), MvKind::Ogg);
        assert_eq!(mv_kind_of("a.ogg_"), MvKind::Ogg);
        assert_eq!(mv_kind_of("a.rpgmvm"), MvKind::M4a);
        assert_eq!(mv_kind_of("a.m4a_"), MvKind::M4a);
        assert_eq!(mv_kind_of("a.png"), MvKind::Unknown);
        assert_eq!(MvKind::Png.ext(), ".png");
        assert_eq!(MvKind::Ogg.ext(), ".ogg");
        assert_eq!(MvKind::M4a.ext(), ".m4a");
    }

    #[test]
    fn repacked_v3_matches_source_entry_names() {
        let _guard = pack_lock();
        // Extract -> repack -> re-list must reproduce the same path set; this
        // is the preview-workspace "edit and repack" loop users rely on.
        let dir = tmp("repack");
        let indir = dir.join("in");
        write_tree(&indir, &[
            ("Audio/SE/decision.ogg", b"decision body"),
            ("Audio/SE/cursor.ogg", b"cursor body"),
            ("Data/Armor.rvdata2", b"armor body"),
        ]);
        let first = dir.join("Game.rgss3a");
        create(indir.to_str().unwrap(), first.to_str().unwrap(), 3).unwrap();
        let out = dir.join("out");
        extract_rgss(first.to_str().unwrap(), out.to_str().unwrap(), None).unwrap();
        let second = dir.join("Game2.rgss3a");
        create(out.to_str().unwrap(), second.to_str().unwrap(), 3).unwrap();

        let a: Vec<String> = open(first.to_str().unwrap()).unwrap().entries.iter().map(|e| e.name.clone()).collect();
        let b: Vec<String> = open(second.to_str().unwrap()).unwrap().entries.iter().map(|e| e.name.clone()).collect();
        assert_eq!(a, b);
        assert_eq!(fs::read(second).unwrap().len(), fs::read(first).unwrap().len());
        fs::remove_dir_all(&dir).ok();
    }
}
