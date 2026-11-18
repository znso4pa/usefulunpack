//! Lucifen Easy Game System `.lpk` reader (extract-only).
//!
//! Everything in an LPK is keyed off the **file name**: the two 32-bit keys are
//! seeded with fixed constants and folded over the CP932 bytes of the upper-
//! cased stem (one from the end, one from the start, rotating by 7 each step).
//! The second key then unmasks the 4 bytes at offset 4, which pack the index
//! size into 24 bits and five behaviour flags into the top byte. The index
//! itself is a letter trie encrypted with a rotating-key scheme.
//!
//! Games that need more than that ship `SCRIPT.LPK` next to the data archives;
//! its `gameinit.sob` entry carries a per-archive key pair (name → key1/key2).
//! Without it only `SCRIPT.LPK` itself opens, so this reader imports the table
//! the same way the reference does.
//!
//! Reference: GARbro `ArcFormats/Lucifen/ArcLPK.cs` (MIT). Extract-only —
//! `CanWrite` is false upstream and the writer was never published.

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jlong, jboolean, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, derive_dirs, safe_join, extract_result_json};
use archive_common::extract_progress;
use archive_common::compress_progress;
use std::collections::{HashMap, HashSet};
use std::fs::File;
use std::io::{BufReader, Read, Seek, SeekFrom, Write};

/// One constant set. The engine shipped two of them: almost every title uses
/// the first, but a handful seed the key schedule and the content obfuscation
/// with different numbers. The header check is cheap enough to just try both.
#[derive(Clone, Copy, Debug)]
struct Scheme {
    base1: u32,
    base2: u32,
    content_xor: u8,
    rotate: u32,
}

const SCHEMES: [Scheme; 2] = [
    Scheme { base1: 0xA5B9_AC6B, base2: 0x9A63_9DE5, content_xor: 0x5D, rotate: 0x3174_6285 },
    Scheme { base1: 0x39A4_E1FD, base2: 0xD63B_1E29, content_xor: 0xA6, rotate: 0x3614_7352 },
];

/// Ceiling on a decoded entry. LPK members are scripts, images and audio; a
/// declared length past this is a corrupt/hostile index field.
const MAX_ENTRY: u32 = 512 * 1024 * 1024;
const MAX_COUNT: u32 = 0x10_0000;

#[derive(Debug)]
struct LpkInfo {
    aligned: bool,
    encrypted: bool,
    packed_entries: bool,
    whole_crypt: bool,
    key: u32,
    prefix: Vec<u8>,
    scheme: Scheme,
}

#[derive(Debug)]
struct LpkEntry {
    name: String,
    offset: u64,
    size: u32,
    packed: bool,
    unpacked: u32,
}

// ─── Key schedule ───

/// Folds the archive's stem into the two base keys. Both indices walk the name
/// (one forward, one backward) for exactly `len` steps — the loop terminates on
/// the backward index reaching -1, not on the two meeting.
fn derive_keys(basename: &[u8], scheme: Scheme) -> (u32, u32) {
    let mut k1 = scheme.base1;
    let mut k2 = scheme.base2;
    let len = basename.len();
    for b in 0..len {
        let e = len - 1 - b;
        k1 ^= basename[e] as u32;
        k2 ^= basename[b] as u32;
        k1 = k1.rotate_right(7);
        k2 = k2.rotate_left(7);
    }
    (k1, k2)
}

fn decrypt_index(data: &mut [u8], mut key: u32, rotate: u32) {
    let mut pattern = rotate;
    for chunk in data.chunks_exact_mut(4) {
        let v = u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]) ^ key;
        chunk.copy_from_slice(&v.to_le_bytes());
        pattern = pattern.rotate_left(4);
        key = key.rotate_right(pattern & 0x1f);
    }
}

fn decrypt_entry(data: &mut [u8], mut key: u32, rotate: u32) {
    let mut pattern = rotate;
    for chunk in data.chunks_exact_mut(4) {
        let v = u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]) ^ key;
        chunk.copy_from_slice(&v.to_le_bytes());
        pattern = pattern.rotate_right(4);
        key = key.rotate_left(pattern & 0x1f);
    }
}

/// Whole-content obfuscation: XOR then a nibble swap.
fn decrypt_content(data: &mut [u8], xor: u8) {
    for b in data.iter_mut() {
        *b = (*b ^ xor).rotate_right(4);
    }
}

// ─── Index parsing ───

fn u32_at(b: &[u8], i: usize) -> Option<u32> {
    let s = b.get(i..i + 4)?;
    Some(u32::from_le_bytes([s[0], s[1], s[2], s[3]]))
}

fn u16_at(b: &[u8], i: usize) -> Option<u16> {
    let s = b.get(i..i + 2)?;
    Some(u16::from_le_bytes([s[0], s[1]]))
}

struct Reader<'a> {
    index: &'a [u8],
    width: usize,
    entries_offset: usize,
    entry_size: usize,
    info: &'a LpkInfo,
    budget: usize,
}

impl<'a> Reader<'a> {
    fn add_entry(&self, name: &[u8], entry_num: usize, dir: &mut Vec<LpkEntry>) -> Result<(), String> {
        if name.is_empty() {
            return Err("LPK: empty entry name".into());
        }
        let name = encoding_rs::SHIFT_JIS.decode(name).0.into_owned().replace('\\', "/");
        let mut pos = self
            .entries_offset
            .checked_add(self.entry_size.checked_mul(entry_num).ok_or("LPK: entry index overflow")?)
            .ok_or("LPK: entry index overflow")?;
        if pos + self.entry_size > self.index.len() {
            return Err("LPK: entry record out of bounds".into());
        }
        if self.entry_size & 1 != 0 {
            pos += 1; // per-entry flag byte, not used by the reader
        }
        let raw_off = u32_at(self.index, pos).ok_or("LPK: truncated entry")?;
        let size = u32_at(self.index, pos + 4).ok_or("LPK: truncated entry")?;
        let offset = if self.info.aligned { (raw_off as u64) << 11 } else { raw_off as u64 };
        let mut unpacked = size;
        let mut packed = false;
        if self.info.packed_entries {
            let u = u32_at(self.index, pos + 8).ok_or("LPK: truncated entry")?;
            packed = u != 0;
            if packed {
                unpacked = u;
            }
        }
        if unpacked > MAX_ENTRY {
            return Err(format!("LPK: entry '{name}' declares {unpacked} bytes"));
        }
        dir.push(LpkEntry { name, offset, size, packed, unpacked });
        Ok(())
    }

    /// Walks the letter trie. `off` is the start of a node list; each node is
    /// `letter(1) + offset(width)` where the offset is relative to the byte
    /// right after the node. A zero letter terminates a name and the offset is
    /// then an index into the entry table.
    fn traverse(&mut self, off: usize, name_len: usize, name: &mut [u8; 260], dir: &mut Vec<LpkEntry>) -> Result<(), String> {
        if self.budget == 0 {
            return Err("LPK: index trie is too large".into());
        }
        self.budget -= 1;
        if off >= self.index.len() {
            return Err("LPK: index trie out of bounds".into());
        }
        if name_len >= name.len() {
            return Err("LPK: entry name too long".into());
        }
        let count = self.index[off] as usize;
        let mut p = off + 1;
        for _ in 0..count {
            let letter = *self.index.get(p).ok_or("LPK: truncated trie node")?;
            p += 1;
            let next = if self.width == 4 {
                u32_at(self.index, p).ok_or("LPK: truncated trie offset")? as usize
            } else {
                u16_at(self.index, p).ok_or("LPK: truncated trie offset")? as usize
            };
            p += self.width;
            name[name_len] = letter;
            // `next` is a u32 straight off disk; on a 32-bit target `p + next`
            // would wrap and silently point at a valid-looking node.
            let child = p.checked_add(next).ok_or("LPK: trie offset overflow")?;
            if letter != 0 {
                self.traverse(child, name_len + 1, name, dir)?;
            } else {
                self.add_entry(&name[..name_len], next, dir)?;
            }
        }
        Ok(())
    }
}

fn parse_index(index: &[u8], info: &mut LpkInfo) -> Result<Vec<LpkEntry>, String> {
    let count = u32_at(index, 0).ok_or("LPK: truncated index")?;
    if count == 0 || count > MAX_COUNT {
        return Err(format!("LPK: implausible entry count {count}"));
    }
    let mut p = 4usize;
    let prefix_len = *index.get(p).ok_or("LPK: truncated index")? as usize;
    p += 1;
    if prefix_len != 0 {
        let end = p + prefix_len;
        info.prefix = index.get(p..end).ok_or("LPK: truncated prefix")?.to_vec();
        p = end;
    }
    let width_flag = *index.get(p).ok_or("LPK: truncated index")?;
    p += 1;
    let width = if width_flag != 0 { 4 } else { 2 };
    let letter_table_len = u32_at(index, p).ok_or("LPK: truncated index")? as usize;
    p += 4;
    let entries_offset = p.checked_add(letter_table_len).ok_or("LPK: index overflow")?;
    if entries_offset >= index.len() {
        return Err("LPK: letter table runs past the index".into());
    }
    let mut entry_size = if info.packed_entries { 13 } else { 9 };
    let avail = index.len() - entries_offset;
    if avail / entry_size < count as usize {
        entry_size = avail / count as usize;
    }
    if entry_size < 8 || (info.packed_entries && entry_size < 12) {
        return Err("LPK: unsupported entry record size".into());
    }

    let mut reader = Reader {
        index,
        width,
        entries_offset,
        entry_size,
        info,
        // The trie is a tree, but a corrupt index can point many nodes at the
        // same subtree. Cap the total visits so it cannot blow up.
        budget: count as usize * 4 + 4096,
    };
    let mut name = [0u8; 260];
    let mut dir = Vec::with_capacity(count as usize);
    reader.traverse(p, 0, &mut name, &mut dir)?;
    if dir.len() != count as usize {
        return Err(format!("LPK: index lists {count} entries, trie yielded {}", dir.len()));
    }
    Ok(dir)
}

// ─── Open ───

fn cp932_stem(name: &str) -> Vec<u8> {
    let stem = std::path::Path::new(name)
        .file_stem()
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();
    let upper = stem.to_uppercase();
    encoding_rs::SHIFT_JIS.encode(&upper).0.into_owned()
}

/// One open attempt with a specific constant set and an optional per-file key
/// pair.
fn try_open_with(input: &str, scheme: Scheme, per_file: Option<(u32, u32)>) -> Result<(Vec<LpkEntry>, LpkInfo), String> {
    let mut f = BufReader::new(File::open(input).map_err(|e| format!("{e}"))?);
    let fsize = f.get_ref().metadata().map(|m| m.len()).map_err(|e| format!("{e}"))?;
    if fsize < 0x10 {
        return Err("LPK: file too small".into());
    }
    let mut hdr = [0u8; 8];
    f.read_exact(&mut hdr).map_err(|e| format!("{e}"))?;
    if &hdr[0..4] != b"LPK1" {
        return Err("LPK: not a Lucifen archive".into());
    }
    let file_name = std::path::Path::new(input)
        .file_name()
        .map(|s| s.to_string_lossy().to_uppercase())
        .unwrap_or_default();
    let (mut k1, mut k2) = derive_keys(&cp932_stem(&file_name), scheme);
    if let Some((a, b)) = per_file {
        k1 ^= a;
        k2 ^= b;
    }
    let code = u32::from_le_bytes([hdr[4], hdr[5], hdr[6], hdr[7]]) ^ k2;
    let mut index_size = (code & 0x00ff_ffff) as u64;
    let flags = (code >> 24) as u8;
    if flags & 1 != 0 {
        // Aligned archives store offsets >> 11 and enlarge the index to match.
        index_size = (index_size << 11).wrapping_sub(8);
    }
    if index_size < 5 || index_size >= fsize {
        return Err("LPK: implausible index size (wrong key for this file?)".into());
    }
    let mut index = vec![0u8; index_size as usize];
    f.read_exact(&mut index).map_err(|e| format!("{e}"))?;
    decrypt_index(&mut index, k2, scheme.rotate);

    let mut info = LpkInfo {
        aligned: flags & 1 != 0,
        encrypted: flags & 4 != 0,
        packed_entries: flags & 8 != 0,
        whole_crypt: flags & 0x10 != 0,
        key: k1,
        prefix: Vec::new(),
        scheme,
    };
    if flags & 2 == 0 {
        return Err("LPK: unsupported index format (flag1 clear)".into());
    }
    // Patch archives set the whole-crypt bit without actually obfuscating.
    if info.whole_crypt && file_name == "PATCH.LPK" {
        info.whole_crypt = false;
    }
    let dir = parse_index(&index, &mut info)?;
    Ok((dir, info))
}

/// Tries every known constant set. The header check rejects a wrong scheme
/// immediately (a bad key makes the 24-bit index size implausible), so this
/// costs one extra seek+read and nothing else.
fn try_open(input: &str, per_file: Option<(u32, u32)>) -> Result<(Vec<LpkEntry>, LpkInfo), String> {
    let mut last = String::new();
    for scheme in SCHEMES {
        match try_open_with(input, scheme, per_file) {
            Ok(v) => return Ok(v),
            Err(e) => last = e,
        }
    }
    Err(last)
}

fn open_lpk(input: &str) -> Result<(Vec<LpkEntry>, LpkInfo), String> {
    let first = match try_open(input, None) {
        Ok(v) => return Ok(v),
        Err(e) => e,
    };
    let name = std::path::Path::new(input)
        .file_name()
        .map(|s| s.to_string_lossy().to_uppercase())
        .unwrap_or_default();
    if name == "SCRIPT.LPK" {
        return Err(first);
    }
    let dir = std::path::Path::new(input).parent().unwrap_or(std::path::Path::new("."));

    // Step 1: the table published by the sibling SCRIPT.LPK.
    let note = match import_keys(dir) {
        Ok(keys) => match keys.get(&name) {
            Some(&(a, b)) => {
                if let Ok(v) = try_open(input, Some((a, b))) {
                    return Ok(v);
                }
                "the SCRIPT.LPK key did not open it".to_string()
            }
            None => "SCRIPT.LPK has no key for this file".to_string(),
        },
        Err(e) => format!("no usable SCRIPT.LPK ({e})"),
    };

    // Step 2: recover the pair from whatever the game directory holds. This is
    // slower, so it only runs once the cheap paths have failed.
    for scheme in SCHEMES {
        if let Some((a, b)) = find_aux_keys(input, scheme) {
            if let Ok(v) = try_open_with(input, scheme, Some((a, b))) {
                return Ok(v);
            }
        }
    }
    Err(format!("{first} — {note}, and no key pair was found in the game directory"))
}

// ─── SCRIPT.LPK key import ───

fn cstring(b: &[u8], from: usize) -> String {
    let end = b[from..].iter().position(|&c| c == 0).map(|i| from + i).unwrap_or(b.len());
    String::from_utf8_lossy(&b[from..end]).into_owned()
}

/// Parses `gameinit.sob` for the `(archive name → key pair)` table.
///
/// The file is a compiled script blob; the table is recovered by scanning the
/// bytecode for a fixed 29-word signature and reading two words out of it. This
/// is a direct port of the reference scan — unverified against a real game,
/// because the layout of the surrounding bytecode is compiler-version specific.
fn parse_game_init(sob: &[u8]) -> Option<HashMap<String, (u32, u32)>> {
    if sob.len() < 8 || &sob[0..4] != b"SOB0" {
        return None;
    }
    let delta = i32::from_le_bytes([sob[4], sob[5], sob[6], sob[7]]);
    let offset = delta.checked_add(8)?;
    if offset <= 0 || offset as usize >= sob.len() {
        return None;
    }
    let base = offset as usize;
    let index_len = u32_at(sob, base)? as usize;
    if base.checked_add(8)?.checked_add(index_len)? >= sob.len() {
        return None;
    }
    let start = base + 8;
    let words = index_len / 4;
    if words < 28 {
        return None;
    }
    let last = start + (words - 28) * 4;

    let mut map = HashMap::new();
    let mut p = start;
    while p < last {
        let w = |i: usize| u32_at(sob, p + i * 4);
        let ok = w(0) == Some(0x28)
            && w(1) == Some(0)
            && matches!(w(2), Some(v) if v != 0)
            && w(3) == Some(8)
            && w(4) == Some(1)
            && w(5) == Some(8)
            && w(6) == Some(1)
            && w(7) == Some(8)
            && matches!(w(8), Some(v) if v != 0)
            && w(9) == Some(8)
            && matches!(w(10), Some(v) if v != 0)
            && w(11) == Some(5)
            && w(17) == Some(0x28)
            && w(18) == Some(0)
            && matches!(w(19), Some(v) if v != 0)
            && w(20) == Some(8)
            && matches!(w(21), Some(v) if v != 0)
            && w(22) == Some(8)
            && w(23) == Some(0xffff_ffff)
            && w(24) == Some(8)
            && w(25) == Some(1)
            && w(26) == Some(8)
            && w(27) == Some(1)
            && w(28) == Some(5);
        if ok {
            // name = p + p[21] - p[2] + 0x34 (byte address arithmetic)
            let w2 = w(2).unwrap() as i64;
            let w21 = w(21).unwrap() as i64;
            let name_at = (p as i64).checked_add(w21)?.checked_sub(w2)?.checked_add(0x34)?;
            if name_at >= 0 && (name_at as usize) < sob.len() {
                let name = cstring(sob, name_at as usize).to_uppercase();
                if !name.is_empty() {
                    map.insert(name, (w(8).unwrap(), w(10).unwrap()));
                }
            }
            p += 0x22 * 4;
        } else {
            p += 4;
        }
    }
    if map.is_empty() { None } else { Some(map) }
}

fn import_keys(dir: &std::path::Path) -> Result<HashMap<String, (u32, u32)>, String> {
    let script = dir.join("SCRIPT.LPK");
    let path = script.to_str().ok_or("SCRIPT.LPK path is not UTF-8")?;
    let (dir_entries, info) = try_open(path, None)?;
    let entry = dir_entries
        .iter()
        .find(|e| e.name.eq_ignore_ascii_case("gameinit.sob"))
        .ok_or("SCRIPT.LPK has no gameinit.sob entry")?;
    let mut f = BufReader::new(File::open(path).map_err(|e| format!("{e}"))?);
    let data = read_entry(&mut f, entry, &info)?;
    parse_game_init(&data).ok_or_else(|| "gameinit.sob: no key table found".to_string())
}

/// Brute-forces the per-file aux key pair out of the game's own directory.
///
/// Every archive except `SCRIPT.LPK` is keyed by a pair that lives somewhere in
/// the game files — usually the compiled script blob, sometimes the executable.
/// GARbro recovers it by matching one fixed 29-word bytecode signature, which
/// only survives the compiler version it was written against. This instead
/// slides an 8-byte window over the neighbouring files and keeps whatever makes
/// the LPK header and index structure sane: the same approach the community
/// extractor uses, and one that does not care how the bytecode was laid out.
///
/// The first filter is the entry count. A wrong key turns it into a uniformly
/// random u32, and only about 0.02% of those fall under `MAX_COUNT`, so the
/// expensive work happens a handful of times instead of millions.
fn find_aux_keys(input: &str, scheme: Scheme) -> Option<(u32, u32)> {
    let path = std::path::Path::new(input);
    let dir = path.parent().unwrap_or(std::path::Path::new("."));
    let self_name = path.file_name()?.to_string_lossy().to_uppercase();
    let fsize = std::fs::metadata(input).ok()?.len();
    if fsize < 0x20 {
        return None;
    }

    let mut head = [0u8; 0x210];
    {
        let mut f = File::open(input).ok()?;
        let mut hdr = [0u8; 8];
        f.read_exact(&mut hdr).ok()?;
        head[..8].copy_from_slice(&hdr);
        let want = ((fsize - 8).min((head.len() - 8) as u64)) as usize;
        f.read_exact(&mut head[8..8 + want]).ok()?;
        for b in head[8 + want..].iter_mut() {
            *b = 0;
        }
    }
    let header_word = u32::from_le_bytes([head[4], head[5], head[6], head[7]]);
    let (base1, base2) = derive_keys(&cp932_stem(&self_name), scheme);

    // Small files first: script blobs are usually a few hundred KB, while an
    // executable can be tens of MB and is the least likely place for the table.
    let mut files: Vec<(u64, std::path::PathBuf)> = Vec::new();
    for e in std::fs::read_dir(dir).ok()?.flatten() {
        let p = e.path();
        if !p.is_file() {
            continue;
        }
        let name = p.file_name().map(|n| n.to_string_lossy().to_uppercase()).unwrap_or_default();
        if name == self_name || name.ends_with(".LPK") {
            continue; // other archives are encrypted with the very key we want
        }
        let Ok(md) = e.metadata() else { continue };
        if md.len() > 16 * 1024 * 1024 {
            continue;
        }
        files.push((md.len(), p));
    }
    files.sort();

    let mut budget: u64 = 48 * 1024 * 1024;
    let mut probe = [0u8; 0x210 - 8];
    for (len, p) in files {
        if budget == 0 {
            break;
        }
        let Ok(data) = std::fs::read(&p) else { continue };
        budget = budget.saturating_sub(len.min(budget));
        if data.len() < 8 {
            continue;
        }
        let mut i = 0usize;
        while i + 8 <= data.len() {
            let a1 = u32::from_le_bytes([data[i], data[i + 1], data[i + 2], data[i + 3]]);
            let a2 = u32::from_le_bytes([data[i + 4], data[i + 5], data[i + 6], data[i + 7]]);
            for (x, y) in [(a1, a2), (a2, a1)] {
                let k2 = base2 ^ y;
                let code = header_word ^ k2;
                let flags = (code >> 24) as u8;
                if flags & 2 == 0 {
                    continue;
                }
                let mut size = (code & 0x00ff_ffff) as u64;
                if flags & 1 != 0 {
                    size = (size << 11).wrapping_sub(8);
                }
                if size < 12 || size >= fsize {
                    continue;
                }
                // Cheap gate: the decrypted entry count. Random keys almost
                // never produce a plausible one.
                let raw_count = u32::from_le_bytes([head[8], head[9], head[10], head[11]]) ^ k2;
                if raw_count == 0 || raw_count > MAX_COUNT {
                    continue;
                }
                // Now the full structural check on the cached prefix.
                probe.copy_from_slice(&head[8..]);
                decrypt_index(&mut probe, k2, scheme.rotate);
                if !index_prefix_sane(&probe, size as usize) {
                    continue;
                }
                let mut index = vec![0u8; size as usize];
                let Ok(mut f) = File::open(input) else { continue };
                if f.seek(SeekFrom::Start(8)).is_err() || f.read_exact(&mut index).is_err() {
                    continue;
                }
                decrypt_index(&mut index, k2, scheme.rotate);
                let mut info = LpkInfo {
                    aligned: flags & 1 != 0,
                    encrypted: flags & 4 != 0,
                    packed_entries: flags & 8 != 0,
                    whole_crypt: flags & 0x10 != 0 && self_name != "PATCH.LPK",
                    key: base1 ^ x,
                    prefix: Vec::new(),
                    scheme,
                };
                if parse_index(&index, &mut info).is_ok() {
                    return Some((x, y));
                }
            }
            i += 4;
        }
    }
    None
}

/// Header-level check on the first 0x210 bytes of a decrypted index: count,
/// prefix length, offset width and the letter-table length must all fit.
fn index_prefix_sane(index: &[u8], index_size: usize) -> bool {
    let Some(count) = u32_at(index, 0) else { return false };
    if count == 0 || count > MAX_COUNT {
        return false;
    }
    let Some(&plen) = index.get(4) else { return false };
    let plen = plen as usize;
    if plen > 200 {
        return false;
    }
    let mut p = 5 + plen;
    if index.get(p).is_none() {
        return false;
    }
    p += 1;
    let Some(tlen) = u32_at(index, p) else { return false };
    p += 4;
    let tlen = tlen as usize;
    // The trie must end inside the index and leave room for the entry table.
    match p.checked_add(tlen) {
        Some(end) if end < index_size && end < index.len() => {}
        _ => return false,
    }
    true
}

// ─── Entry decode ───


/// LZSS with a 0x1000 window pre-filled with zeros and the write cursor
/// starting at 0xFEE — the variant every Lucifen archive uses.
fn lzss_decode(src: &[u8], limit: usize) -> Vec<u8> {
    let mut frame = [0u8; 0x1000];
    let mut frame_pos: usize = 0xFEE;
    let mut out = Vec::with_capacity(limit.min(1 << 20));
    let mut i = 0usize;
    'outer: while i < src.len() {
        let ctl = src[i];
        i += 1;
        for bit in 0..8 {
            if out.len() >= limit {
                break 'outer;
            }
            if ctl & (1 << bit) != 0 {
                let Some(&b) = src.get(i) else { break 'outer };
                i += 1;
                frame[frame_pos & 0xfff] = b;
                frame_pos += 1;
                out.push(b);
            } else {
                if i + 1 >= src.len() {
                    break 'outer;
                }
                let lo = src[i] as usize;
                let hi = src[i + 1] as usize;
                i += 2;
                let mut offset = ((hi & 0xf0) << 4) | lo;
                let count = 3 + (hi & 0xf);
                for _ in 0..count {
                    let v = frame[offset & 0xfff];
                    offset += 1;
                    frame[frame_pos & 0xfff] = v;
                    frame_pos += 1;
                    out.push(v);
                    if out.len() >= limit {
                        break 'outer;
                    }
                }
            }
        }
    }
    out
}

fn read_entry(f: &mut BufReader<File>, e: &LpkEntry, info: &LpkInfo) -> Result<Vec<u8>, String> {
    if e.size == 0 {
        return Ok(Vec::new());
    }
    // The archive-wide prefix is stored ONCE in the index, not repeated in
    // every entry: an entry's `size` counts the reconstructed length, so only
    // `size - prefix.len()` bytes are actually on disk. Reading `size` bytes
    // runs past the end of the last entry (by exactly the prefix length) and
    // silently appends the next entry's opening bytes to everything else.
    let stored = if e.packed {
        e.size as usize
    } else {
        (e.size as usize).saturating_sub(info.prefix.len())
    };
    if stored == 0 {
        return Ok(info.prefix.clone());
    }
    f.seek(SeekFrom::Start(e.offset)).map_err(|x| format!("{x}"))?;
    let mut raw = vec![0u8; stored];
    f.read_exact(&mut raw).map_err(|x| format!("{x}"))?;

    let mut data = if e.packed { lzss_decode(&raw, e.unpacked as usize) } else { raw };
    if info.whole_crypt {
        decrypt_content(&mut data, info.scheme.content_xor);
    }
    if info.encrypted {
        let n = data.len().min(0x100);
        if n >= 4 {
            decrypt_entry(&mut data[..n], info.key, info.scheme.rotate);
        }
    }
    if !info.prefix.is_empty() {
        let mut with = info.prefix.clone();
        with.extend_from_slice(&data);
        data = with;
    }
    Ok(data)
}

// ─── Create ───

/// A node of the letter trie. `BTreeMap` keeps the children in byte order,
/// which is the order the reference writer emits them in.
struct TrieNode {
    children: std::collections::BTreeMap<u8, usize>,
    terminal: Option<usize>,
}

fn cp932(s: &str) -> Result<Vec<u8>, String> {
    let (b, _, err) = encoding_rs::SHIFT_JIS.encode(s);
    if err {
        return Err(format!("file name not representable in CP932: {s}"));
    }
    Ok(b.into_owned())
}

fn collect_files(
    dir: &std::path::Path,
    rel: &mut Vec<u8>,
    out: &mut Vec<(Vec<u8>, std::path::PathBuf)>,
) -> Result<(), String> {
    let mut items: Vec<_> = std::fs::read_dir(dir)
        .map_err(|e| format!("{}: {e}", dir.display()))?
        .flatten()
        .collect();
    items.sort_by_key(|e| e.file_name());
    for e in items {
        let p = e.path();
        let Some(name) = e.file_name().to_str().map(|s| s.to_string()) else {
            return Err(format!("non-UTF-8 file name: {}", p.display()));
        };
        let keep = rel.len();
        if !rel.is_empty() {
            // The engine is a Windows program and stores names with backslashes.
            rel.push(b'\\');
        }
        rel.extend_from_slice(&cp932(&name)?);
        if p.is_dir() {
            collect_files(&p, rel, out)?;
        } else {
            out.push((rel.clone(), p));
        }
        rel.truncate(keep);
    }
    Ok(())
}

fn build_trie(names: &[Vec<u8>]) -> Vec<TrieNode> {
    let mut nodes = vec![TrieNode { children: Default::default(), terminal: None }];
    for (idx, name) in names.iter().enumerate() {
        let mut cur = 0usize;
        for &b in name {
            let next = match nodes[cur].children.get(&b) {
                Some(&n) => n,
                None => {
                    let n = nodes.len();
                    nodes.push(TrieNode { children: Default::default(), terminal: None });
                    nodes[cur].children.insert(b, n);
                    n
                }
            };
            cur = next;
        }
        nodes[cur].terminal = Some(idx);
    }
    nodes
}

fn write_node(
    nodes: &[TrieNode],
    idx: usize,
    width: usize,
    buf: &mut Vec<u8>,
    fixups: &mut Vec<(usize, usize)>,
    starts: &mut [usize],
) {
    starts[idx] = buf.len();
    let n = &nodes[idx];
    let count = n.children.len() + usize::from(n.terminal.is_some());
    buf.push(count as u8);
    // A zero letter marks the end of a name; its offset is the entry index.
    if let Some(t) = n.terminal {
        buf.push(0);
        if width == 4 {
            buf.extend_from_slice(&(t as u32).to_le_bytes());
        } else {
            buf.extend_from_slice(&(t as u16).to_le_bytes());
        }
    }
    for (&letter, &child) in &n.children {
        buf.push(letter);
        fixups.push((buf.len(), child));
        buf.extend(std::iter::repeat(0u8).take(width));
    }
    // Child bodies follow this node's header, in the same order.
    for &child in n.children.values() {
        write_node(nodes, child, width, buf, fixups, starts);
    }
}

fn serialize_trie(nodes: &[TrieNode], width: usize) -> Vec<u8> {
    let mut buf = Vec::new();
    let mut fixups: Vec<(usize, usize)> = Vec::new();
    let mut starts = vec![0usize; nodes.len()];
    write_node(nodes, 0, width, &mut buf, &mut fixups, &mut starts);
    for (pos, child) in fixups {
        // Offsets are relative to the byte right after the offset field.
        let rel = starts[child] as i64 - (pos + width) as i64;
        if width == 4 {
            buf[pos..pos + 4].copy_from_slice(&(rel as i32).to_le_bytes());
        } else {
            buf[pos..pos + 2].copy_from_slice(&(rel as u16).to_le_bytes());
        }
    }
    buf
}

/// Packs a directory into an LPK.
///
/// LZSS is not implemented: the format supports per-entry compression, but the
/// engine's encoder was never published and a wrong stream would decode into
/// silently corrupt files. Everything else the writer needs — the trie, the
/// index cipher, the content transforms — is symmetric with the reader, so the
/// output is a plain `flag1`-only archive that the engine reads like any other.
///
/// `aux` is the per-archive key pair XORed into the derived keys. It is only
/// needed when the target archive is one the engine keys through
/// `SCRIPT.LPK`/`gameinit.sob`; `SCRIPT.LPK` itself needs none.
fn lpk_create_archive(input: &str, output: &str, aux: Option<(u32, u32)>) -> Result<(), String> {
    let root = std::path::Path::new(input);
    if !root.is_dir() {
        return Err(format!("not a directory: {input}"));
    }
    let mut files: Vec<(Vec<u8>, std::path::PathBuf)> = Vec::new();
    collect_files(root, &mut Vec::new(), &mut files)?;
    if files.is_empty() {
        return Err("no files to pack".into());
    }
    if files.len() > MAX_COUNT as usize {
        return Err(format!("{} entries exceeds the format limit", files.len()));
    }
    // Deterministic output, and the entry indices follow this order.
    files.sort_by(|a, b| a.0.cmp(&b.0));

    let total: u64 = files
        .iter()
        .filter_map(|(_, p)| std::fs::metadata(p).ok())
        .map(|m| m.len())
        .sum();
    compress_progress::reset(total);

    let names: Vec<Vec<u8>> = files.iter().map(|(n, _)| n.clone()).collect();
    let nodes = build_trie(&names);
    let width = 4usize;
    let letter_table = serialize_trie(&nodes, width);

    let count = files.len();
    const ENTRY_SIZE: usize = 9; // flag(1) + offset(4) + size(4)
    let mut index = Vec::new();
    index.extend_from_slice(&(count as u32).to_le_bytes());
    index.push(0); // no archive-wide prefix
    index.push(1); // 4-byte trie offsets
    index.extend_from_slice(&(letter_table.len() as u32).to_le_bytes());
    index.extend_from_slice(&letter_table);
    let entries_at = index.len();
    index.resize(entries_at + ENTRY_SIZE * count, 0);
    // The index cipher works on whole u32 words.
    while index.len() % 4 != 0 {
        index.push(0);
    }
    let data_start = 8u64 + index.len() as u64;

    let mut cur = data_start;
    let mut payloads: Vec<Vec<u8>> = Vec::with_capacity(count);
    for (i, (name_bytes, p)) in files.iter().enumerate() {
        if compress_progress::cancelled() {
            return Err("cancelled".into());
        }
        compress_progress::set_name(&String::from_utf8_lossy(name_bytes).replace('\\', "/"));
        compress_progress::set_file(i as u64);
        let data = std::fs::read(p).map_err(|e| format!("{}: {e}", p.display()))?;
        compress_progress::add_bytes(data.len() as u64);
        let base = entries_at + i * ENTRY_SIZE;
        index[base] = 0; // per-entry flags
        index[base + 1..base + 5].copy_from_slice(&(cur as u32).to_le_bytes());
        index[base + 5..base + 9].copy_from_slice(&(data.len() as u32).to_le_bytes());
        cur += data.len() as u64;
        if cur > u32::MAX as u64 {
            return Err("archive would exceed 4 GiB".into());
        }
        payloads.push(data);
    }
    if index.len() >= (1 << 24) {
        return Err("index too large for the 24-bit header field".into());
    }

    let file_name = std::path::Path::new(output)
        .file_name()
        .map(|s| s.to_string_lossy().to_uppercase())
        .ok_or("output path has no file name")?;
    let (_, mut k2) = derive_keys(&cp932_stem(&file_name), SCHEMES[0]);
    if let Some((_, b)) = aux {
        k2 ^= b;
    }
    decrypt_index(&mut index, k2, SCHEMES[0].rotate); // symmetric

    let code = (0x02u32 << 24) | (index.len() as u32 & 0x00ff_ffff);
    let mut out = std::io::BufWriter::with_capacity(
        256 * 1024,
        File::create(output).map_err(|e| format!("{e}"))?,
    );
    out.write_all(b"LPK1").map_err(|e| format!("{e}"))?;
    out.write_all(&(code ^ k2).to_le_bytes()).map_err(|e| format!("{e}"))?;
    out.write_all(&index).map_err(|e| format!("{e}"))?;
    for p in &payloads {
        out.write_all(p).map_err(|e| format!("{e}"))?;
    }
    out.flush().map_err(|e| format!("{e}"))?;
    Ok(())
}

// ─── Extract ───

fn guard_panic<T, F: FnOnce() -> Result<T, String>>(f: F) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|panic| {
        let msg = panic
            .downcast_ref::<&str>()
            .copied()
            .or_else(|| panic.downcast_ref::<String>().map(|s| s.as_str()))
            .unwrap_or("unknown panic");
        Err(format!("panic: {msg}"))
    })
}

fn list_lpk(input: &str) -> Result<String, String> {
    let (ents, _) = open_lpk(input)?;
    let names: Vec<&str> = ents.iter().map(|e| e.name.as_str()).collect();
    let dirs = derive_dirs(&names);
    let mut all: Vec<(String, u64, bool)> = Vec::new();
    for d in &dirs {
        all.push((d.clone(), 0, true));
    }
    for e in &ents {
        all.push((e.name.clone(), e.unpacked as u64, false));
    }
    all.sort_by(|a, b| a.0.cmp(&b.0));
    let items: Vec<String> = all
        .iter()
        .map(|(n, s, d)| format!(r#"{{"n":"{}","s":{},"d":{},"e":false}}"#, json_escape(n), if *d { 0 } else { *s }, d))
        .collect();
    Ok(format!("[{}]", items.join(",")))
}

fn run_extract(input: &str, output: &str, only: Option<&str>) -> Result<(u32, u32), String> {
    let sel: Option<HashSet<&str>> = only.map(|s| s.lines().filter(|l| !l.is_empty()).collect());
    if matches!(&sel, Some(s) if s.is_empty()) {
        return Ok((0, 0));
    }
    let (ents, info) = open_lpk(input)?;
    let wanted = |e: &LpkEntry| match &sel {
        None => true,
        Some(ss) => ss.contains(e.name.as_str()) || ss.iter().any(|d| e.name.starts_with(&format!("{d}/"))),
    };
    let total: u64 = ents.iter().filter(|e| wanted(e)).map(|e| e.unpacked as u64).sum();
    extract_progress::reset(total);
    let mut f = BufReader::new(File::open(input).map_err(|e| format!("{e}"))?);
    let mut fail = 0u32;
    let mut done = 0u32;
    for e in &ents {
        if extract_progress::cancelled() {
            return Err("cancelled".to_string());
        }
        if !wanted(e) {
            continue;
        }
        done += 1;
        extract_progress::set_name(&e.name);
        extract_progress::set_file(e.unpacked as u64);
        match safe_join(output, &e.name) {
            Ok(d) => {
                let r = guard_panic(|| {
                    if let Some(p) = d.parent() {
                        std::fs::create_dir_all(p).map_err(|x| format!("{x}"))?;
                    }
                    let data = read_entry(&mut f, e, &info)?;
                    let mut out = std::io::BufWriter::with_capacity(
                        256 * 1024,
                        archive_common::ProgressWriter::extract(File::create(&d).map_err(|x| format!("{x}"))?),
                    );
                    out.write_all(&data).map_err(|x| format!("{x}"))?;
                    out.flush().map_err(|x| format!("{x}"))?;
                    Ok(())
                });
                if r.is_err() {
                    let _ = std::fs::remove_file(&d);
                    fail += 1;
                }
            }
            Err(_) => fail += 1,
        }
    }
    Ok((done, fail))
}

#[doc(hidden)]
pub fn extract_lpk_host(input: &str, output: &str) -> Result<(u32, u32), String> {
    run_extract(input, output, None)
}

#[doc(hidden)]
pub fn list_lpk_host(input: &str) -> Result<String, String> {
    list_lpk(input)
}

/// Parses the optional aux pair the packer takes from the UI: `"0x1234:0xabcd"`
/// (hex) or two decimal numbers. An empty string means "no pair", which is the
/// right answer for `SCRIPT.LPK` and for archives the engine does not key.
fn parse_aux(spec: &str) -> Result<Option<(u32, u32)>, String> {
    let spec = spec.trim();
    if spec.is_empty() {
        return Ok(None);
    }
    let (a, b) = spec
        .split_once(':')
        .ok_or_else(|| format!("aux key must look like 0x12345678:0x9abcdef0, got {spec:?}"))?;
    let one = |x: &str| -> Result<u32, String> {
        let x = x.trim();
        let r = if let Some(h) = x.strip_prefix("0x").or_else(|| x.strip_prefix("0X")) {
            u32::from_str_radix(h, 16)
        } else {
            x.parse::<u32>()
        };
        r.map_err(|_| format!("not a 32-bit key: {x:?}"))
    };
    Ok(Some((one(a)?, one(b)?)))
}

#[doc(hidden)]
pub fn create_lpk_host(input: &str, output: &str, aux: Option<(u32, u32)>) -> Result<(), String> {
    lpk_create_archive(input, output, aux)
}

// ─── JNI ───

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_LpkCore_lpkCompressProgressCount(_: JNIEnv, _: JClass) -> jlong {
    compress_progress::bytes() as jlong
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_LpkCore_lpkCompressProgressTotal(_: JNIEnv, _: JClass) -> jlong {
    compress_progress::total_bytes() as jlong
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_LpkCore_lpkCompressProgressFileCount(_: JNIEnv, _: JClass) -> jlong {
    compress_progress::file_bytes() as jlong
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_LpkCore_lpkCompressProgressFileTotal(_: JNIEnv, _: JClass) -> jlong {
    compress_progress::file_total() as jlong
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_LpkCore_lpkCompressProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&compress_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_LpkCore_lpkCompressCancel(_: JNIEnv, _: JClass) {
    compress_progress::cancel();
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_LpkCore_lpkCreateArchive(
    mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, aux: JString,
) -> jboolean {
    compress_progress::clear_cancel();
    let inp = s(&mut e, &i);
    let out = s(&mut e, &o);
    let spec = s(&mut e, &aux);
    let parsed = match parse_aux(&spec) {
        Ok(v) => v,
        Err(er) => {
            let _ = e.throw_new("java/io/IOException", format!("lpkCreateArchive: {er}"));
            return JNI_FALSE;
        }
    };
    match guard_panic(move || lpk_create_archive(&inp, &out, parsed)) {
        Ok(()) => JNI_TRUE,
        Err(er) => {
            let _ = e.throw_new("java/io/IOException", format!("lpkCreateArchive: {er}"));
            JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_LpkCore_lpkExtractProgressCount(_: JNIEnv, _: JClass) -> jlong {
    extract_progress::bytes() as jlong
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_LpkCore_lpkExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong {
    extract_progress::total_bytes() as jlong
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_LpkCore_lpkExtractProgressFileCount(_: JNIEnv, _: JClass) -> jlong {
    extract_progress::file_bytes() as jlong
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_LpkCore_lpkExtractProgressFileTotal(_: JNIEnv, _: JClass) -> jlong {
    extract_progress::file_total() as jlong
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_LpkCore_lpkExtractProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_LpkCore_lpkExtractCancel(_: JNIEnv, _: JClass) {
    extract_progress::cancel();
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_LpkCore_lpkExtract(
    mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString,
) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i);
    let out = s(&mut e, &o);
    let _ = std::fs::create_dir_all(&out);
    match guard_panic(move || run_extract(&inp, &out, None)) {
        Ok((total, error)) => {
            let json = extract_result_json(total, total - error, error);
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
pub extern "system" fn Java_com_usefulunpacker_LpkCore_lpkExtractSelected(
    mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel: JString,
) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i);
    let out = s(&mut e, &o);
    let sel_s = s(&mut e, &sel);
    match guard_panic(move || run_extract(&inp, &out, Some(&sel_s))) {
        Ok((total, error)) => {
            let json = extract_result_json(total, total - error, error);
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
pub extern "system" fn Java_com_usefulunpacker_LpkCore_lpkListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i);
    match guard_panic(move || list_lpk(&inp)) {
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

#[cfg(test)]
mod tests {
    use super::*;

    static LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
    fn lock() -> std::sync::MutexGuard<'static, ()> {
        LOCK.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn tmpdir(tag: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("uu_lpk_{tag}_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    /// Inverse of the reader's index scramble, used to build fixtures.
    fn encrypt_index(data: &mut [u8], mut key: u32, rotate: u32) {
        let mut pattern = rotate;
        for chunk in data.chunks_exact_mut(4) {
            let v = u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]) ^ key;
            chunk.copy_from_slice(&v.to_le_bytes());
            pattern = pattern.rotate_left(4);
            key = key.rotate_right(pattern & 0x1f);
        }
    }

    /// Builds a real letter trie: one level per character, a 0-letter child
    /// terminating each name. Node offsets are relative to the byte after the
    /// node, so the fixture has to lay the table out parent-before-child.
    fn build_index(
        names: &[&str], payloads: &[Vec<u8>], sizes: &[(u32, u32)], packed: bool, prefix: &[u8],
    ) -> Vec<u8> {
        struct N {
            kids: Vec<(u8, usize)>,
            entry: Option<usize>,
        }
        let mut nodes: Vec<N> = vec![N { kids: Vec::new(), entry: None }];
        for (i, n) in names.iter().enumerate() {
            let mut cur = 0usize;
            for &b in n.as_bytes() {
                let next = nodes[cur].kids.iter().find(|(c, _)| *c == b).map(|(_, k)| *k);
                cur = match next {
                    Some(k) => k,
                    None => {
                        let k = nodes.len();
                        nodes.push(N { kids: Vec::new(), entry: None });
                        nodes[cur].kids.push((b, k));
                        k
                    }
                };
            }
            nodes[cur].entry = Some(i);
        }
        for n in nodes.iter_mut() {
            n.kids.sort_by_key(|(c, _)| *c);
        }

        fn node_size(nodes: &[N], i: usize) -> usize {
            1 + (nodes[i].kids.len() + usize::from(nodes[i].entry.is_some())) * 5
        }
        fn assign(nodes: &[N], i: usize, at: usize, pos: &mut Vec<usize>) -> usize {
            pos[i] = at;
            let mut next = at + node_size(nodes, i);
            for &(_, c) in &nodes[i].kids {
                next = assign(nodes, c, next, pos);
            }
            next
        }
        fn emit(nodes: &[N], i: usize, pos: &[usize], table: &mut [u8]) {
            let at = pos[i];
            let n = &nodes[i];
            table[at] = (n.kids.len() + usize::from(n.entry.is_some())) as u8;
            let mut p = at + 1;
            for &(ch, c) in &n.kids {
                table[p] = ch;
                let rel = (pos[c] - (p + 5)) as u32;
                table[p + 1..p + 5].copy_from_slice(&rel.to_le_bytes());
                p += 5;
            }
            if let Some(e) = n.entry {
                table[p] = 0;
                table[p + 1..p + 5].copy_from_slice(&(e as u32).to_le_bytes());
            }
            for &(_, c) in &n.kids {
                emit(nodes, c, pos, table);
            }
        }

        let mut pos = vec![0usize; nodes.len()];
        let table_len = assign(&nodes, 0, 0, &mut pos);
        let mut table = vec![0u8; table_len];
        emit(&nodes, 0, &pos, &mut table);

        let entry_size = if packed { 13 } else { 9 };
        let entries_len = entry_size * names.len();
        let index_len = 10 + prefix.len() + table_len + entries_len;
        let pad = (4 - index_len % 4) % 4;
        // Entry offsets are absolute file offsets, and the payloads start right
        // after the (padded) index.
        let mut data_off = (8 + index_len + pad) as u32;
        let mut entries = vec![0u8; entries_len];
        for (i, p) in payloads.iter().enumerate() {
            let base = i * entry_size;
            let (size, unpacked) = sizes[i];
            entries[base] = 0; // flag
            entries[base + 1..base + 5].copy_from_slice(&data_off.to_le_bytes());
            entries[base + 5..base + 9].copy_from_slice(&size.to_le_bytes());
            if packed {
                entries[base + 9..base + 13].copy_from_slice(&unpacked.to_le_bytes());
            }
            data_off += p.len() as u32;
        }

        let mut out = Vec::new();
        out.extend_from_slice(&(names.len() as u32).to_le_bytes());
        out.push(prefix.len() as u8);
        out.extend_from_slice(prefix);
        out.push(1); // 4-byte offsets
        out.extend_from_slice(&(table_len as u32).to_le_bytes());
        out.extend_from_slice(&table);
        out.extend_from_slice(&entries);
        assert_eq!(out.len(), index_len);
        for _ in 0..pad {
            out.push(0);
        }
        out
    }

    fn build_lpk_flags(
        file_name: &str, names: &[&str], payloads: &[Vec<u8>], sizes: &[(u32, u32)], flags: u8,
    ) -> Vec<u8> {
        build_lpk_scheme(file_name, names, payloads, sizes, flags, SCHEMES[0], None, &[])
    }

    fn build_lpk_scheme(
        file_name: &str, names: &[&str], payloads: &[Vec<u8>], sizes: &[(u32, u32)], flags: u8,
        scheme: Scheme, aux: Option<(u32, u32)>, prefix: &[u8],
    ) -> Vec<u8> {
        let mut index = build_index(names, payloads, sizes, flags & 8 != 0, prefix);
        let (_, mut k2) = derive_keys(&cp932_stem(file_name), scheme);
        if let Some((_, b)) = aux {
            k2 ^= b;
        }
        encrypt_index(&mut index, k2, scheme.rotate);
        let code = ((flags as u32) << 24) | (index.len() as u32 & 0x00ff_ffff);
        let mut out = Vec::new();
        out.extend_from_slice(b"LPK1");
        out.extend_from_slice(&(code ^ k2).to_le_bytes());
        out.extend_from_slice(&index);
        for p in payloads {
            out.extend_from_slice(p);
        }
        out
    }

    fn build_lpk(file_name: &str, names: &[&str], payloads: &[Vec<u8>], packed: bool) -> Vec<u8> {
        let sizes: Vec<(u32, u32)> = payloads.iter().map(|p| (p.len() as u32, p.len() as u32)).collect();
        build_lpk_flags(file_name, names, payloads, &sizes, if packed { 0x0A } else { 0x02 })
    }

    /// All-literal LZSS stream: control byte 0xFF (or a truncated mask on the
    /// tail) followed by the raw bytes.
    fn lzss_encode_literals(data: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        for chunk in data.chunks(8) {
            out.push(((1u16 << chunk.len()) - 1) as u8);
            out.extend_from_slice(chunk);
        }
        out
    }

    #[test]
    fn key_schedule_folds_both_ends() {
        let (k1, k2) = derive_keys(b"A", SCHEMES[0]);
        assert_eq!(k1, (SCHEMES[0].base1 ^ 0x41).rotate_right(7));
        assert_eq!(k2, (SCHEMES[0].base2 ^ 0x41).rotate_left(7));
        // Two bytes walk from both ends: k1 sees the LAST byte first.
        let (a1, a2) = derive_keys(b"AB", SCHEMES[0]);
        let mut e1 = (SCHEMES[0].base1 ^ 0x42).rotate_right(7);
        e1 = (e1 ^ 0x41).rotate_right(7);
        assert_eq!(a1, e1);
        let mut e2 = (SCHEMES[0].base2 ^ 0x41).rotate_left(7);
        e2 = (e2 ^ 0x42).rotate_left(7);
        assert_eq!(a2, e2);
    }

    #[test]
    fn index_scramble_round_trips() {
        let mut data: Vec<u8> = (0..256u32).map(|i| (i * 7) as u8).collect();
        let orig = data.clone();
        encrypt_index(&mut data, 0x1234_5678, SCHEMES[0].rotate);
        assert_ne!(data, orig);
        decrypt_index(&mut data, 0x1234_5678, SCHEMES[0].rotate);
        assert_eq!(data, orig);
    }

    #[test]
    fn lzss_decodes_literals_and_back_references() {
        let src = [0b0000_0111u8, b'A', b'B', b'C', 0xF0, 0x02];
        let out = lzss_decode(&src, 8);
        assert_eq!(&out[..3], b"ABC");
        assert_eq!(out.len(), 8, "3 literals + 5-byte match");
        assert!(out[3..].iter().all(|&b| b == 0), "frame starts zero-filled");
    }

    #[test]
    fn lzss_respects_the_declared_limit() {
        let src = [0xFFu8, b'X', b'Y', b'Z'];
        let out = lzss_decode(&src, 2);
        assert_eq!(out, b"XY");
    }

    #[test]
    fn list_and_extract_a_plain_archive() {
        let _g = lock();
        let dir = tmpdir("plain");
        let names = ["a.txt", "b.bin"];
        let payloads = vec![b"hello".to_vec(), vec![1u8, 2, 3, 4, 5, 6]];
        let arc = build_lpk("data.lpk", &names, &payloads, false);
        let p = dir.join("data.lpk");
        std::fs::write(&p, &arc).unwrap();

        let json = list_lpk(p.to_str().unwrap()).expect("list");
        assert!(json.contains("a.txt"), "{json}");
        assert!(json.contains("b.bin"), "{json}");

        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let (n, fail) = run_extract(p.to_str().unwrap(), out.to_str().unwrap(), None).unwrap();
        assert_eq!((n, fail), (2, 0));
        assert_eq!(std::fs::read(out.join("a.txt")).unwrap(), b"hello");
        assert_eq!(std::fs::read(out.join("b.bin")).unwrap(), payloads[1]);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn wrong_file_name_breaks_the_index() {
        let _g = lock();
        // The key comes from the name, so renaming an archive makes its index
        // unreadable — that is the format's design, and the error has to say so
        // instead of reporting garbage entries.
        let dir = tmpdir("rename");
        let arc = build_lpk("data.lpk", &["a.txt"], &[b"x".to_vec()], false);
        let p = dir.join("other.lpk");
        std::fs::write(&p, &arc).unwrap();
        let err = try_open(p.to_str().unwrap(), None).unwrap_err();
        assert!(err.contains("LPK"), "{err}");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// The engine shipped a second constant set (different base keys, content
    /// XOR and rotate pattern) that a handful of titles use. `try_open` must
    /// find it on its own — the caller has no way to know which one a file
    /// needs, and a wrong guess fails the header check instead of producing
    /// plausible nonsense.
    #[test]
    fn second_constant_set_is_detected_automatically() {
        let _g = lock();
        let dir = tmpdir("scheme2");
        let plain: Vec<u8> = (0..500u32).map(|i| (i % 253) as u8).collect();
        let sizes = [(plain.len() as u32, plain.len() as u32)];
        // Whole-crypt + entry-crypt exercise the per-scheme XOR and rotate. The
        // reader applies whole-crypt first, then entry-crypt, so the writer
        // must invert them in the opposite order.
        let (k1, _) = derive_keys(&cp932_stem("scheme2.lpk"), SCHEMES[1]);
        let mut body = plain.clone();
        decrypt_entry(&mut body[..0x100], k1, SCHEMES[1].rotate);
        for b in body.iter_mut() {
            *b = b.rotate_left(4) ^ SCHEMES[1].content_xor;
        }
        let arc = build_lpk_scheme("scheme2.lpk", &["a.bin"], &[body], &sizes, 0x16, SCHEMES[1], None, &[]);
        let p = dir.join("scheme2.lpk");
        std::fs::write(&p, &arc).unwrap();

        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let (n, fail) = run_extract(p.to_str().unwrap(), out.to_str().unwrap(), None).unwrap();
        assert_eq!((n, fail), (1, 0));
        assert_eq!(std::fs::read(out.join("a.bin")).unwrap(), plain);
        std::fs::remove_dir_all(&dir).ok();
    }

    /// End-to-end check of the fallback that matters most in practice: a data
    /// archive with no SCRIPT.LPK beside it, whose key pair sits at an
    /// arbitrary offset inside some unrelated game file. `open_lpk` has to
    /// find it by sliding an 8-byte window, then open the archive with it.
    #[test]
    fn aux_key_is_recovered_from_the_game_directory() {
        let _g = lock();
        let dir = tmpdir("bruteforce");
        let aux = (0x1234_ABCDu32, 0x5678_EF01u32);
        let plain = b"hello from a keyed archive".to_vec();
        let sizes = [(plain.len() as u32, plain.len() as u32)];
        // Flag 0x02 only, so the payload is stored verbatim and any mistake is
        // a plain byte mismatch rather than a decrypt artefact.
        let arc = build_lpk_scheme(
            "DATA.lpk", &["a.txt"], &[plain.clone()], &sizes, 0x02, SCHEMES[0], Some(aux), &[],
        );
        std::fs::write(dir.join("DATA.lpk"), &arc).unwrap();

        // The pair lives at offset 20 of a file that looks nothing like a key
        // table; the scan must not depend on any signature.
        let mut blob = vec![0u8; 96];
        blob[20..24].copy_from_slice(&aux.0.to_le_bytes());
        blob[24..28].copy_from_slice(&aux.1.to_le_bytes());
        std::fs::write(dir.join("game.dat"), &blob).unwrap();

        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let lpk = dir.join("DATA.lpk");
        let (n, fail) = run_extract(lpk.to_str().unwrap(), out.to_str().unwrap(), None).unwrap();
        assert_eq!((n, fail), (1, 0));
        assert_eq!(std::fs::read(out.join("a.txt")).unwrap(), plain);
        std::fs::remove_dir_all(&dir).ok();
    }

    /// Pack, then read back with the same reader. The trie layout, the entry
    /// offsets and the index cipher are all written here from scratch, so a
    /// round trip is the only thing that proves the writer agrees with the
    /// reader about relative offsets and data start.
    #[test]
    fn create_then_extract_round_trips() {
        let _g = lock();
        let dir = tmpdir("create");
        let src = dir.join("src");
        std::fs::create_dir_all(src.join("sub")).unwrap();
        std::fs::write(src.join("a.txt"), b"alpha").unwrap();
        std::fs::write(src.join("sub").join("b.bin"), [1u8, 2, 3, 4]).unwrap();

        let lpk = dir.join("NEW.LPK");
        lpk_create_archive(src.to_str().unwrap(), lpk.to_str().unwrap(), None).unwrap();

        let (ents, _) = open_lpk(lpk.to_str().unwrap()).unwrap();
        let mut names: Vec<&str> = ents.iter().map(|e| e.name.as_str()).collect();
        names.sort();
        assert_eq!(names, vec!["a.txt", "sub/b.bin"]);

        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let (n, fail) = run_extract(lpk.to_str().unwrap(), out.to_str().unwrap(), None).unwrap();
        assert_eq!((n, fail), (2, 0));
        assert_eq!(std::fs::read(out.join("a.txt")).unwrap(), b"alpha");
        assert_eq!(std::fs::read(out.join("sub").join("b.bin")).unwrap(), [1u8, 2, 3, 4]);
        std::fs::remove_dir_all(&dir).ok();
    }

    /// The writer must honour an aux key pair, otherwise a packed data archive
    /// would be unreadable by the game that keys it through SCRIPT.LPK.
    #[test]
    fn create_with_aux_key_round_trips() {
        let _g = lock();
        let dir = tmpdir("createaux");
        let src = dir.join("src");
        std::fs::create_dir_all(&src).unwrap();
        std::fs::write(src.join("x.dat"), b"payload").unwrap();

        let aux = (0x0BAD_F00Du32, 0xFEED_1234u32);
        let lpk = dir.join("PIC.LPK");
        lpk_create_archive(src.to_str().unwrap(), lpk.to_str().unwrap(), Some(aux)).unwrap();

        // Without the pair the header must not decode.
        assert!(try_open(lpk.to_str().unwrap(), None).is_err());
        // With it, the archive opens and the payload matches. `run_extract`
        // goes through `open_lpk`, which has no way to receive an explicit
        // pair, so the check reads the entry directly.
        let (ents, info) = try_open(lpk.to_str().unwrap(), Some(aux)).unwrap();
        assert_eq!(ents.len(), 1);
        let mut f = BufReader::new(File::open(&lpk).unwrap());
        assert_eq!(read_entry(&mut f, &ents[0], &info).unwrap(), b"payload");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// The aux pair arrives as free text from the UI, so both accepted forms
    /// and the failure modes matter: an empty string means "no pair", which is
    /// what SCRIPT.LPK needs.
    #[test]
    fn aux_spec_parsing() {
        assert_eq!(parse_aux("").unwrap(), None);
        assert_eq!(parse_aux("   ").unwrap(), None);
        assert_eq!(parse_aux("0x1234ABCD:0x0000F00D").unwrap(), Some((0x1234_ABCD, 0xF00D)));
        assert_eq!(parse_aux("305419896:65535").unwrap(), Some((0x1234_5678, 65535)));
        assert!(parse_aux("0x1234").is_err());
        assert!(parse_aux("zzz:1").is_err());
        assert!(parse_aux("1:2:3").is_err());
    }

    /// Regression for the prefix accounting bug found on a real Lucifen title.
    ///
    /// The archive-wide prefix lives ONCE in the index, not once per entry, so
    /// an entry's `size` counts the *reconstructed* length while only
    /// `size - prefix.len()` bytes sit on disk. Reading `size` bytes ran
    /// exactly `prefix.len()` past the end of the final entry — the last file
    /// in a real archive failed outright with "failed to fill whole buffer",
    /// and every other entry silently had the next entry's opening bytes glued
    /// onto its tail.
    #[test]
    fn prefix_is_stored_once_not_per_entry() {
        let _g = lock();
        let dir = tmpdir("prefix");
        // Any prefix works; it only has to be long enough to be unmistakable.
        let prefix = b"uu-prefix-14b\0";
        assert_eq!(prefix.len(), 14);

        // Each reconstructed file starts with the prefix; the bytes after it
        // are the only thing actually stored.
        let mut plain_a = prefix.to_vec();
        plain_a.extend((0..186u32).map(|i| i as u8));
        let mut plain_b = prefix.to_vec();
        plain_b.extend((0..286u32).map(|i| (i * 7) as u8));
        let stored: Vec<Vec<u8>> =
            [&plain_a, &plain_b].iter().map(|p| p[prefix.len()..].to_vec()).collect();
        let sizes: Vec<(u32, u32)> =
            [&plain_a, &plain_b].iter().map(|p| (p.len() as u32, p.len() as u32)).collect();

        // Flags 0x02 only: verbatim payloads, so a mismatch is a plain byte
        // difference and not a decrypt artefact.
        let arc = build_lpk_scheme(
            "DATA.LPK", &["a.ogg", "b.ogg"], &stored, &sizes, 0x02, SCHEMES[0], None, prefix,
        );
        let p = dir.join("DATA.LPK");
        std::fs::write(&p, &arc).unwrap();

        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let (n, fail) = run_extract(p.to_str().unwrap(), out.to_str().unwrap(), None).unwrap();
        assert_eq!((n, fail), (2, 0));
        // A too-long read shows up here as a corrupted tail on `a.ogg` …
        assert_eq!(std::fs::read(out.join("a.ogg")).unwrap(), plain_a);
        // … and as a hard error on the last entry, `b.ogg`.
        assert_eq!(std::fs::read(out.join("b.ogg")).unwrap(), plain_b);
        std::fs::remove_dir_all(&dir).ok();
    }

    /// Optional end-to-end run against a real archive, which is not committed.
    /// Point `UU_LPK_SAMPLE` at any Lucifen `.lpk` and run:
    ///
    /// ```text
    /// UU_LPK_SAMPLE=/path/to/archive.lpk \
    ///   cargo test -p archive_lpk-core real_sample -- --ignored --nocapture
    /// ```
    #[test]
    #[ignore]
    fn real_sample() {
        let Ok(path) = std::env::var("UU_LPK_SAMPLE") else {
            eprintln!("SKIP real_sample: UU_LPK_SAMPLE not set");
            return;
        };
        let (ents, info) = open_lpk(&path).unwrap();
        eprintln!(
            "entries={} prefix={} aligned={} packed={} whole={} enc={}",
            ents.len(), info.prefix.len(), info.aligned, info.packed_entries,
            info.whole_crypt, info.encrypted
        );
        assert!(!ents.is_empty(), "empty directory");
        let mut f = BufReader::new(File::open(&path).unwrap());
        // Every entry must decode, including the last one — that is precisely
        // the one the prefix bug used to break.
        let mut ok = 0;
        for e in &ents {
            let data = read_entry(&mut f, e, &info)
                .unwrap_or_else(|er| panic!("{} off={} size={}: {er}", e.name, e.offset, e.size));
            if !info.prefix.is_empty() {
                assert!(data.starts_with(&info.prefix), "{} lost the prefix", e.name);
            }
            ok += 1;
        }
        assert_eq!(ok, ents.len());
    }

    #[test]
    fn rejects_non_lpk_input() {
        let _g = lock();
        let dir = tmpdir("bad");
        let p = dir.join("x.lpk");
        std::fs::write(&p, b"LPK1notreallyanindex").unwrap();
        assert!(try_open(p.to_str().unwrap(), None).is_err());
        let p2 = dir.join("y.lpk");
        std::fs::write(&p2, b"NOPE").unwrap();
        assert!(try_open(p2.to_str().unwrap(), None).is_err());
        std::fs::remove_dir_all(&dir).ok();
    }

    /// The full decode pipeline: LZSS → whole-content obfuscation → per-entry
    /// header decrypt → prefix. The writer below applies the exact inverses in
    /// reverse order, so any mistake in the order or the direction shows up as
    /// a byte mismatch rather than a silently plausible file.
    #[test]
    fn packed_whole_crypt_and_entry_encrypted() {
        let _g = lock();
        let dir = tmpdir("crypt");
        let plain: Vec<u8> = (0..300u32).map(|i| (i % 251) as u8).collect();
        let (k1, _) = derive_keys(&cp932_stem("crypt.lpk"), SCHEMES[0]);

        let mut body = plain.clone();
        // decrypt_entry XORs a key schedule that does not depend on the data,
        // so it is its own inverse — the writer reuses it.
        decrypt_entry(&mut body[..0x100], k1, SCHEMES[0].rotate);
        for b in body.iter_mut() {
            *b = b.rotate_left(4) ^ SCHEMES[0].content_xor; // inverse of decrypt_content
        }
        let packed = lzss_encode_literals(&body);

        let sizes = [(packed.len() as u32, plain.len() as u32)];
        // flag1 | encrypted | packed entries | whole crypt
        let arc = build_lpk_flags("crypt.lpk", &["a.bin"], &[packed], &sizes, 0x1E);
        let p = dir.join("crypt.lpk");
        std::fs::write(&p, &arc).unwrap();

        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let (n, fail) = run_extract(p.to_str().unwrap(), out.to_str().unwrap(), None).unwrap();
        assert_eq!((n, fail), (1, 0));
        assert_eq!(std::fs::read(out.join("a.bin")).unwrap(), plain);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn selected_extraction_filters_names() {
        let _g = lock();
        let dir = tmpdir("sel");
        let arc = build_lpk("s.lpk", &["a.txt", "b.txt"], &[b"AAA".to_vec(), b"BBB".to_vec()], false);
        let p = dir.join("s.lpk");
        std::fs::write(&p, &arc).unwrap();
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let (n, fail) = run_extract(p.to_str().unwrap(), out.to_str().unwrap(), Some("b.txt")).unwrap();
        assert_eq!((n, fail), (1, 0));
        assert!(!out.join("a.txt").exists());
        assert_eq!(std::fs::read(out.join("b.txt")).unwrap(), b"BBB");
        std::fs::remove_dir_all(&dir).ok();
    }
}
