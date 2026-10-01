use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jlong, jboolean, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, derive_dirs, safe_join, extract_result_json, ProgressWriter, compress_progress};
use archive_common::extract_progress;
use std::collections::HashSet;
use std::fs::File;
use std::io::{BufReader, BufWriter, Read, Seek, SeekFrom, Write};
use flate2::read::ZlibDecoder;
use flate2::write::ZlibEncoder;
use flate2::Compression;

// --- Marker → length lookup tables ---

fn ypf_fname_len(m: u8) -> Option<usize> {
    match m {
        0xf4=>Some(9),0xfc=>Some(10),0xf6=>Some(11),0xef=>Some(12),0xec=>Some(13),0xf1=>Some(14),
        0xf0=>Some(15),0xf3=>Some(16),0xe7=>Some(17),0xed=>Some(18),0xf2=>Some(19),0xd1=>Some(20),
        0xe4=>Some(21),0xe9=>Some(22),0xe8=>Some(23),0xee=>Some(24),0xe6=>Some(25),0xe5=>Some(26),
        0xea=>Some(27),0xe1=>Some(28),0xe2=>Some(29),0xe3=>Some(30),0xe0=>Some(31),0xdc=>Some(32),
        0xde=>Some(33),0xdd=>Some(34),0xdf=>Some(35),0xdb=>Some(36),0xda=>Some(37),0xd6=>Some(38),
        0xd8=>Some(39),0xd7=>Some(40),0xd9=>Some(41),0xd5=>Some(42),0xd4=>Some(43),0xd0=>Some(44),
        0xd2=>Some(45),0xeb=>Some(46),0xd3=>Some(47),0xcf=>Some(48),0xce=>Some(49),0xcd=>Some(50),
        0xcc=>Some(51),0xcb=>Some(52),0xf9=>Some(53),0xc9=>Some(54),0xc8=>Some(55),_=>None}
}

// GARbro SwapTable00 — paired marker↔length lookup
static SWAP: &[u8] = &[
    0x03,0x48,0x06,0x35,0x0C,0x10,0x11,0x19,0x1C,0x1E,
    0x09,0x0B,0x0D,0x13,0x15,0x1B,0x20,0x23,0x26,0x29,0x2C,0x2F,0x2E,0x32,
];

fn fname_len(marker: u8) -> Option<usize> {
    let v = marker ^ 0xFF;
    if let Some(p) = SWAP.iter().position(|&x| x == v) {
        return Some(if (p & 1) != 0 { SWAP[p-1] } else { SWAP[p+1] } as usize);
    }
    ypf_fname_len(marker)
}

// --- Entry struct ---

struct YpfEntry { name: String, _file_type: u8, compressed: bool, usize: u32, asize: u32, offset: u32 }

// --- Core: open + parse entries ---

fn open_ypf(input: &str) -> Result<(Vec<YpfEntry>, BufReader<File>, u64), String> {
    let mut f = BufReader::new(File::open(input).map_err(|e| format!("{e}"))?);
    let fsize = f.get_ref().metadata().map(|m| m.len()).map_err(|e| format!("{e}"))?;
    let mut b = [0u8;4];
    f.read_exact(&mut b).map_err(|e| format!("{e}"))?;
    if &b != b"YPF\0" { return Err("Not a YPF file".into()); }
    f.read_exact(&mut b).map_err(|e| format!("{e}"))?; // version
    f.read_exact(&mut b).map_err(|e| format!("{e}"))?; let _count = u32::from_le_bytes(b) as usize;
    f.read_exact(&mut b).map_err(|e| format!("{e}"))?; let hdr_len = u32::from_le_bytes(b);
    if _count == 0 || _count > 100000 { return Err("YPF: bad count".into()); }
    if hdr_len < 0x20 || hdr_len as u64 > fsize { return Err("YPF: bad header_len".into()); }

    f.seek(SeekFrom::Start(0x20)).map_err(|e| format!("{e}"))?;

    let mut key: Option<u8> = None;
    let mut ents = Vec::with_capacity(_count.min(50000));
    let mut file_off = 0x20u64;
    let mut skip_streak = 0u32;
    let max_skip = 20u32;

    for _ in 0.._count.min(50000) {
        if skip_streak >= max_skip { break; }
        if ents.len() >= 50000 { break; }

        f.seek(SeekFrom::Start(file_off)).map_err(|e| format!("{e}"))?;
        let mut ehdr = [0u8;5];
        if f.read_exact(&mut ehdr).is_err() { break; }
        let marker = ehdr[4];

        // Layer 1+2: swap table + fixed mapping
        let fl = match fname_len(marker) {
            Some(n) if n > 0 && n < 200 => n,
            _ => {
                // Layer 3: adaptive rescue — scan for file_type(0-6)+comp(0-1)
                let mut scan = vec![0u8; 200];
                f.seek(SeekFrom::Start(file_off+5)).map_err(|e| format!("{e}"))?;
                let nread = f.read(&mut scan).unwrap_or(0);
                let mut found_fl: Option<usize> = None;
                for off in 4..nread.saturating_sub(24).min(120) {
                    if scan[off] <= 6 && scan[off+1] <= 1 {
                        let chk_off = u32::from_le_bytes([scan[off+12],scan[off+13],scan[off+14],scan[off+15]]);
                        if (chk_off as u64) < fsize { found_fl = Some(off); break; }
                    }
                }
                match found_fl {
                    Some(n) => n,
                    None => { file_off += 4; skip_streak += 1; continue; }
                }
            }
        };
        skip_streak = 0;

        // Read fname + tail
        let mut buf = vec![0u8; fl+22];
        f.seek(SeekFrom::Start(file_off+5)).map_err(|e| format!("{e}"))?;
        if f.read_exact(&mut buf).is_err() { file_off += 4; skip_streak += 1; continue; }

        // XOR key auto-detect (first entry only)
        let k = *key.get_or_insert_with(|| {
            let cnt = |xor:u8| -> usize {
                let mut a = buf[..fl].to_vec(); for b in &mut a { *b ^= xor; }
                String::from_utf8_lossy(&a).chars().filter(|c| c.is_ascii_alphanumeric()||*c=='/'||*c=='\\'||*c=='.'||*c=='_').count()
            };
            if cnt(0xFF) >= cnt(0xC9) { 0xFFu8 } else { 0xC9u8 }
        });

        let mut dec = buf[..fl].to_vec(); for b in &mut dec { *b ^= k; }
        let name = encoding_rs::SHIFT_JIS.decode(&dec).0.into_owned().replace('\\', "/");
        let tail = &buf[fl..];
        let ft = tail[0];
        let compressed = tail[1] != 0;
        let ulen = u32::from_le_bytes([tail[2],tail[3],tail[4],tail[5]]);
        let alen = u32::from_le_bytes([tail[6],tail[7],tail[8],tail[9]]);
        let off  = u32::from_le_bytes([tail[10],tail[11],tail[12],tail[13]]);

        let ok = (off as u64) < fsize && (off as u64 + alen as u64) <= fsize && ulen < 1_000_000_000
              && !name.is_empty() && name.chars().any(|c| c.is_alphanumeric()||c=='/'||c=='.'||c=='_'||c=='-');

        if ok {
            ents.push(YpfEntry { name, _file_type: ft, compressed, usize: ulen, asize: alen, offset: off });
        }
        file_off += (5 + fl + 22) as u64;
    }

    if ents.is_empty() { return Err("YPF: no valid entries found".into()); }
    Ok((ents, f, fsize))
}

// --- Extract ---

fn ypf_extract_one(f: &mut BufReader<File>, e: &YpfEntry, d: &std::path::Path, fsize: u64) -> Result<(), String> {
    if e.asize == 0 { return Ok(()); }
    if e.offset as u64 + e.asize as u64 > fsize { return Err("offset OOB".into()); }
    if let Some(p) = d.parent() { std::fs::create_dir_all(p).map_err(|x| format!("{x}"))?; }
    f.seek(SeekFrom::Start(e.offset as u64)).map_err(|x| format!("{x}"))?;
    let mut out_file = ProgressWriter::extract(BufWriter::with_capacity(256 * 1024, std::fs::File::create(d).map_err(|x| format!("{x}"))?));
    let limited = (&mut *f).take(e.asize as u64);
    if e.compressed {
        let dec = ZlibDecoder::new(limited);
        // Cap the decompressed output at the declared size: a malicious
        // entry can declare a small usize while inflating far larger (disk
        // exhaustion bomb). Read::take truncates silently to usize.
        let mut capped = dec.take(e.usize as u64);
        std::io::copy(&mut capped, &mut out_file).map_err(|x| format!("YPF zlib: {x}"))?;
    } else {
        let mut raw = limited;
        std::io::copy(&mut raw, &mut out_file).map_err(|x| format!("{x}"))?;
    }
    out_file.flush().map_err(|x| format!("{x}"))?;
    Ok(())
}

/// Read-only snapshot of the compress progress statics:
/// `(bytes, total, file_bytes, file_total)`.
///
/// Exists for the out-of-tree byte-progress regression harness and
/// `examples/probe.rs`. Pure accessors with **no side effects** — reading them
/// cannot perturb the very counters a test is trying to observe.
pub fn compress_progress_snapshot() -> (u64, u64, u64, u64) {
    (
        compress_progress::bytes(),
        compress_progress::total_bytes(),
        compress_progress::file_bytes(),
        compress_progress::file_total(),
    )
}

fn guard_panic<T, F: FnOnce() -> Result<T, String>>(f: F) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|panic| {
        let msg = panic.downcast_ref::<&str>().copied()
            .or_else(|| panic.downcast_ref::<String>().map(|s| s.as_str()))
            .unwrap_or("unknown panic");
        Err(format!("panic: {msg}"))
    })
}

fn list_ypf(input: &str) -> Result<String, String> {
    let (ents, _, _) = open_ypf(input)?;
    let names: Vec<&str> = ents.iter().map(|e| e.name.as_str()).collect();
    let dirs = derive_dirs(&names);
    let mut all: Vec<(String,u64,bool)> = Vec::new();
    for d in &dirs { all.push((d.clone(),0,true)); }
    for e in &ents { all.push((e.name.clone(), e.usize as u64, false)); }
    all.sort_by(|a,b| a.0.cmp(&b.0));
    let items: Vec<String> = all.iter().map(|(n,s,d)|{
        format!(r#"{{"n":"{}","s":{},"d":{},"e":false}}"#, json_escape(n), if *d {0} else {*s}, *d)
    }).collect();
    Ok(format!("[{}]", items.join(",")))
}

fn extract_ypf_all(i: &str, o: &str) -> Result<(u32, u32), String> {
    let (ents, mut f, fsize) = open_ypf(i)?;
    let total = ents.len() as u32;
    extract_progress::reset(ents.iter().map(|e| e.usize as u64).sum());
    let mut fail = 0u32;
    for e in &ents {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        extract_progress::set_name(&e.name);
        extract_progress::set_file(e.usize as u64);
        // Match the "failure deletes the half-written file" semantics every
        // other format uses instead of stranding truncated output on disk.
        match safe_join(o, &e.name) {
            Ok(d) => {
                if guard_panic(|| ypf_extract_one(&mut f, e, &d, fsize)).is_err() {
                    let _ = std::fs::remove_file(&d);
                    fail += 1;
                }
            }
            Err(_) => { fail += 1; }
        }
    }
    Ok((total, fail))
}

fn extract_ypf_selected(i: &str, o: &str, s: &str) -> Result<(u32, u32), String> {
    let ss: HashSet<&str> = s.lines().filter(|l| !l.is_empty()).collect();
    if ss.is_empty() { return Ok((0, 0)); }
    let (ents, mut f, fsize) = open_ypf(i)?;
    extract_progress::reset(ents.iter().filter(|e| ss.contains(e.name.as_str()) || ss.iter().any(|d| e.name.starts_with(&format!("{d}/")))).map(|e| e.usize as u64).sum());
    let mut fail = 0u32; let mut selected = 0u32;
    for e in &ents {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        if ss.contains(e.name.as_str()) || ss.iter().any(|d| e.name.starts_with(&format!("{d}/"))) {
            selected += 1;
            extract_progress::set_name(&e.name);
            extract_progress::set_file(e.usize as u64);
            match safe_join(o, &e.name) {
                Ok(d) => {
                    let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| ypf_extract_one(&mut f, e, &d, fsize)));
                    if matches!(r, Ok(Err(_)) | Err(_)) {
                        let _ = std::fs::remove_file(&d);
                        fail += 1;
                    }
                }
                Err(_) => { fail += 1; }
            }
        }
    }
    Ok((selected, fail))
}

// ─── Packing (YPF writer) ───

/// Reverse of the unpack-side marker resolution: len → a marker the reader
/// resolves back to that length. First tries the direct `ypf_fname_len` table
/// (9..=55), then the GARbro SwapTable pairs (the paired value IS the length,
/// so a len that appears as a SWAP element maps to the partner marker).
fn marker_for_len(len: usize) -> Option<u8> {
    for (m, l) in [
        (0xf4,9),(0xfc,10),(0xf6,11),(0xef,12),(0xec,13),(0xf1,14),
        (0xf0,15),(0xf3,16),(0xe7,17),(0xed,18),(0xf2,19),(0xd1,20),
        (0xe4,21),(0xe9,22),(0xe8,23),(0xee,24),(0xe6,25),(0xe5,26),
        (0xea,27),(0xe1,28),(0xe2,29),(0xe3,30),(0xe0,31),(0xdc,32),
        (0xde,33),(0xdd,34),(0xdf,35),(0xdb,36),(0xda,37),(0xd6,38),
        (0xd8,39),(0xd7,40),(0xd9,41),(0xd5,42),(0xd4,43),(0xd0,44),
        (0xd2,45),(0xeb,46),(0xd3,47),(0xcf,48),(0xce,49),(0xcd,50),
        (0xcc,51),(0xcb,52),(0xf9,53),(0xc9,54),(0xc8,55),
    ] {
        if l == len { return Some(m); }
    }
    // SwapTable pairs: (a,b) — the reader does `v=marker^0xFF`, finds v in the
    // table, returns the partner. So len==b → marker = a^0xFF and vice versa.
    for &(a, b) in SWAP_PAIRS {
        if len == b as usize { return Some(a ^ 0xFF); }
        if len == a as usize { return Some(b ^ 0xFF); }
    }
    None
}

/// GARbro SwapTable00 as (a,b) pairs (the flat SWAP array is these interleaved).
static SWAP_PAIRS: &[(u8, u8)] = &[
    (0x03,0x48),(0x06,0x35),(0x0C,0x10),(0x11,0x19),(0x1C,0x1E),
    (0x09,0x0B),(0x0D,0x13),(0x15,0x1B),(0x20,0x23),(0x26,0x29),(0x2C,0x2F),(0x2E,0x32),
];

/// Encodes a file name to the YPF on-disk form: Shift-JIS bytes XORed with the
/// archive key (0xFF or 0xC9, matching the unpack side's auto-detect default).
fn encode_name(name: &str, key: u8) -> Option<Vec<u8>> {
    let sjis = encoding_rs::SHIFT_JIS.encode(name).0.into_owned();
    if sjis.is_empty() || sjis.len() > 55 {
        return None; // name too long / empty — cannot pick a marker
    }
    Some(sjis.iter().map(|b| b ^ key).collect())
}

/// Compresses [data] when it shrinks (or level>0), returning (compressed, was_compressed).
fn maybe_compress(data: &[u8], level: i32) -> (Vec<u8>, bool) {
    if level <= 0 || data.len() < 32 {
        return (data.to_vec(), false);
    }
    let mut enc = ZlibEncoder::new(Vec::new(), Compression::new(level as u32));
    if enc.write_all(data).is_err() { return (data.to_vec(), false); }
    match enc.finish() {
        Ok(c) if c.len() < data.len() => (c, true),
        _ => (data.to_vec(), false),
    }
}

/// Packs [input] (a directory) into a YPF archive at [output].
/// Layout mirrors the unpacker: 0x20-byte header, per-entry records
/// (marker + XOR/SJIS name + 22-byte tail), then the data blobs at absolute
/// file offsets. Returns the number of entries written.
fn ypf_create_archive(input: &str, output: &str, level: i32) -> Result<u32, String> {
    // Collect files recursively (iterative — deep trees can't overflow).
    let mut files: Vec<(String, std::path::PathBuf)> = Vec::new();
    let mut stack = vec![input.to_string()];
    while let Some(dir) = stack.pop() {
        if compress_progress::cancelled() { return Err("cancelled".to_string()); }
        let rd = std::fs::read_dir(&dir).map_err(|e| format!("ypf pack: {e}"))?;
        for entry in rd.flatten() {
            let ft = entry.file_type().map_err(|e| format!("{e}"))?;
            let name = entry.file_name().to_string_lossy().into_owned();
            if ft.is_dir() {
                stack.push(format!("{}/{}", dir, name));
            } else if ft.is_file() {
                let rel = format!("{}/{}", dir.trim_start_matches(input).trim_start_matches('/'), name)
                    .trim_start_matches('/').to_string();
                files.push((rel, entry.path()));
            }
        }
    }
    files.sort_by(|a, b| a.0.cmp(&b.0));
    if files.is_empty() { return Err("ypf pack: empty input".to_string()); }

    let key: u8 = 0xFF; // unpack auto-detect prefers 0xFF ties; keep it simple
    // Progress total must match what add_bytes() feeds, and here both are the
    // **source** byte count (see usize_ below), not the file count and not the
    // compressed payload size. The old code fed `payload.len()` (post-compression)
    // against this source-size total, so on compressible input the bar stalled at
    // the compression ratio — e.g. 30% — and never reached 100%.
    let total_bytes: u64 = files.iter().map(|(_, p)| std::fs::metadata(p).map(|m| m.len()).unwrap_or(0)).sum();
    compress_progress::reset(total_bytes);

    // Build in-memory record + payload list first (offsets need the total size
    // of the records before the data area starts).
    // `src_size` is the SOURCE length, kept alongside the payload so the write
    // loop can feed progress in the same unit as `total_bytes`.
    struct Rec { marker: u8, name_xor: Vec<u8>, ft: u8, compressed: bool, usize_: u32, asize: u32, offset: u32, src_size: u32, data: Vec<u8> }
    let mut recs: Vec<Rec> = Vec::new();
    for (rel, path) in &files {
        if compress_progress::cancelled() { return Err("cancelled".to_string()); }
        let enc = encode_name(rel, key).ok_or_else(|| format!("ypf pack: name too long or bad: {rel}"))?;
        let marker = marker_for_len(enc.len()).ok_or_else(|| format!("ypf pack: no marker for len {}", enc.len()))?;
        let data = std::fs::read(path).map_err(|e| format!("ypf pack read {rel}: {e}"))?;
        let (payload, compressed) = maybe_compress(&data, level);
        recs.push(Rec {
            marker,
            name_xor: enc,
            ft: 0,
            compressed,
            usize_: data.len() as u32,
            asize: payload.len() as u32,
            offset: 0, // filled after record area is sized
            src_size: data.len() as u32,
            data: payload,
        });
    }

    // Record area size: sum of (5-byte header + name_len + 22 tail).
    let mut rec_area = 0u64;
    for r in &recs { rec_area += 5 + r.name_xor.len() as u64 + 22; }
    // Header (0x20) + record area → data blobs start here. Offsets are u32 in
    // the format — reject inputs that would overflow (silent truncation would
    // produce a corrupt archive). Matches the NSA/PFS pack guards.
    let mut data_off = 0x20u64 + rec_area;
    if data_off > u32::MAX as u64 {
        return Err("ypf pack: archive too large (offset overflow)".to_string());
    }
    for r in &mut recs {
        r.offset = data_off as u32;
        data_off += r.data.len() as u64;
        if data_off > u32::MAX as u64 {
            return Err("ypf pack: archive too large (offset overflow)".to_string());
        }
    }

    let mut out = File::create(output).map_err(|e| format!("ypf pack create: {e}"))?;
    let hdr_len = 0x20u32 + rec_area as u32;
    out.write_all(b"YPF\0").map_err(|e| format!("{e}"))?;
    out.write_all(&1u32.to_le_bytes()).map_err(|e| format!("{e}"))?; // version
    out.write_all(&(recs.len() as u32).to_le_bytes()).map_err(|e| format!("{e}"))?; // count
    out.write_all(&hdr_len.to_le_bytes()).map_err(|e| format!("{e}"))?; // hdr_len
    // Pad header to 0x20.
    out.write_all(&[0u8; 0x10]).map_err(|e| format!("{e}"))?;

    // Entries. Layout per record: 5-byte header (4 unknown bytes then the
    // marker at offset 4 — the unpacker reads ehdr[0..5] and takes marker from
    // byte 4), then XOR/SJIS name, then 22-byte tail. The unpacker advances by
    // 5 + name_len + 22, so we must match that stride.
    for (i, r) in recs.iter().enumerate() {
        if compress_progress::cancelled() { return Err("cancelled".to_string()); }
        compress_progress::set_name(&files[i].0);
        out.write_all(&[0u8, 0, 0, 0, r.marker]).map_err(|e| format!("{e}"))?;
        out.write_all(&r.name_xor).map_err(|e| format!("{e}"))?;
        // tail (22 bytes): ft(1) compressed(1) usize(4) asize(4) offset(4) + 8 pad
        let mut tail = Vec::new();
        tail.push(r.ft);
        tail.push(if r.compressed { 1 } else { 0 });
        tail.extend_from_slice(&r.usize_.to_le_bytes());
        tail.extend_from_slice(&r.asize.to_le_bytes());
        tail.extend_from_slice(&r.offset.to_le_bytes());
        tail.extend_from_slice(&[0u8; 8]);
        out.write_all(&tail).map_err(|e| format!("{e}"))?;
    }

    // Data blobs.
    for r in recs.iter() {
        if compress_progress::cancelled() { return Err("cancelled".to_string()); }
        out.write_all(&r.data).map_err(|e| format!("{e}"))?;
        // Feed the SOURCE length, matching total_bytes (sum of source sizes).
        // Feeding `r.data.len()` (the compressed payload) made the bar stall at
        // the compression ratio and never reach 100%.
        compress_progress::add_bytes(r.src_size as u64);
    }
    compress_progress::set_file(0);
    Ok(recs.len() as u32)
}

#[doc(hidden)]
pub fn compress_ypf_host(input: &str, output: &str, level: i32) -> Result<u32, String> {
    ypf_create_archive(input, output, level)
}

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_YpfCore_ypfExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_YpfCore_ypfExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_YpfCore_ypfExtractProgressFileCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_YpfCore_ypfExtractProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_YpfCore_ypfExtractProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_YpfCore_ypfExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }

// --- JNI ---
// All archive-parsing entry points are wrapped in guard_panic so a panic on
// malicious input cannot cross the JNI boundary and kill the process.

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_YpfCore_ypfExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = std::fs::create_dir_all(&out);
    match guard_panic(move || extract_ypf_all(&inp, &out)) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_YpfCore_ypfExtractSelected(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel_j: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel_j);
    match guard_panic(move || extract_ypf_selected(&inp, &out, &sel_str)) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_YpfCore_ypfListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i);
    match guard_panic(move || list_ypf(&inp)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_YpfCore_ypfCreateArchive(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, level: JString) -> jboolean {
    compress_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let lv = s(&mut e, &level).parse::<i32>().unwrap_or(6);
    match guard_panic(move || ypf_create_archive(&inp, &out, lv)) {
        Ok(_) => JNI_TRUE,
        Err(er) => { let _ = e.throw_new("java/io/IOException", format!("ypfCreateArchive: {er}")); JNI_FALSE }
    }
}

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_YpfCore_ypfCompressProgressCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_YpfCore_ypfCompressProgressTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_YpfCore_ypfCompressProgressFileCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_YpfCore_ypfCompressProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_YpfCore_ypfCompressProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&compress_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_YpfCore_ypfCompressCancel(_: JNIEnv, _: JClass) { compress_progress::cancel(); }

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::write::ZlibEncoder;
    use flate2::Compression;
    use std::io::Write as _;

    // compress_progress 是全局静态量，并行跑测试会互相覆盖 total/bytes。
    static LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
    fn lock() -> std::sync::MutexGuard<'static, ()> {
        LOCK.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// 进度口径防回归：`add_bytes` 喂的量必须等于 `reset(total)` 的 total，
    /// 且两者都必须是**源文件**字节（不是压缩后 payload）。
    ///
    /// 旧代码喂 `r.data.len()`（压缩后），对着源字节的 total —— 可压缩输入下
    /// 条子停在压缩比处（实测 ~30%），永不归零收尾。这条断言把那类错钉死。
    #[test]
    fn compress_progress_total_matches_bytes_fed() {
        let _g = lock();
        let dir = std::env::temp_dir().join(format!("uu_ypf_prog_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        // Highly compressible content: the ratio between source and payload is
        // what made the old bug visible.
        let body = vec![b'A'; 512 * 1024];
        std::fs::write(dir.join("a_bigfile.bin"), &body).unwrap();
        // A second, incompressible file so the total is a sum of two.
        let noise: Vec<u8> = (0..4096u32).map(|i| (i % 251) as u8).collect();
        std::fs::write(dir.join("b_smallfile.bin"), &noise).unwrap();

        let out = dir.join("out.ypf");
        compress_progress::clear_cancel();
        ypf_create_archive(dir.to_str().unwrap(), out.to_str().unwrap(), 6).unwrap();

        let total = compress_progress::total_bytes();
        let fed = compress_progress::bytes();
        let src_total = body.len() as u64 + noise.len() as u64;
        assert_eq!(
            total, src_total,
            "total must be the sum of SOURCE sizes, not the payload size"
        );
        assert_eq!(
            fed, src_total,
            "add_bytes must be fed SOURCE bytes (src_size) to match total; \
             feeding the compressed payload stalls the bar at the compression ratio"
        );
        // And the payload really was smaller — otherwise this test wouldn't have
        // caught the original bug.
        let packed = std::fs::metadata(&out).map(|m| m.len()).unwrap_or(0);
        assert!(
            packed < src_total,
            "fixture must actually compress (packed {packed} vs source {src_total})"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    fn zlib(data: &[u8]) -> Vec<u8> {
        let mut enc = ZlibEncoder::new(Vec::new(), Compression::default());
        enc.write_all(data).unwrap();
        enc.finish().unwrap()
    }

    #[test]
    fn extract_streams_zlib_and_raw_entries() {
        let _g = lock();
        let data: Vec<u8> = (0..100_000u32).map(|i| (i % 251) as u8).collect();
        let compressed = zlib(&data);
        let dir = std::env::temp_dir().join(format!("uu_ypf_x_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();

        // File layout: [zlib payload][raw payload]
        let mut payload = Vec::new();
        payload.extend_from_slice(&compressed);
        payload.extend_from_slice(&data);
        let fpath = dir.join("payload.bin");
        std::fs::write(&fpath, &payload).unwrap();

        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();

        let mut f = BufReader::new(File::open(&fpath).unwrap());
        let fsize = payload.len() as u64;
        let zlib_entry = YpfEntry {
            name: "a/z.bin".into(),
            _file_type: 0,
            compressed: true,
            usize: data.len() as u32,
            asize: compressed.len() as u32,
            offset: 0,
        };
        let raw_entry = YpfEntry {
            name: "b/raw.bin".into(),
            _file_type: 0,
            compressed: false,
            usize: data.len() as u32,
            asize: data.len() as u32,
            offset: compressed.len() as u32,
        };

        ypf_extract_one(&mut f, &zlib_entry, &out.join("a/z.bin"), fsize).unwrap();
        ypf_extract_one(&mut f, &raw_entry, &out.join("b/raw.bin"), fsize).unwrap();

        assert_eq!(std::fs::read(out.join("a/z.bin")).unwrap(), data);
        assert_eq!(std::fs::read(out.join("b/raw.bin")).unwrap(), data);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn inflated_output_clamped_to_declared_size() {
        let _g = lock();
        // Declared usize = 1 KiB, but the zlib stream inflates to 1 MiB.
        // The decompressed output must be clamped to the declared size
        // (disk-exhaustion guard), never grow unbounded.
        let data: Vec<u8> = vec![0x41; 1024 * 1024];
        let compressed = zlib(&data);
        assert!(compressed.len() < 4096, "zlib of 1MiB 'A' must be tiny");
        let dir = std::env::temp_dir().join(format!("uu_ypf_clamp_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let fpath = dir.join("payload.bin");
        std::fs::write(&fpath, &compressed).unwrap();
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let mut f = BufReader::new(File::open(&fpath).unwrap());
        let entry = YpfEntry {
            name: "clamped.bin".into(),
            _file_type: 0,
            compressed: true,
            usize: 1024,
            asize: compressed.len() as u32,
            offset: 0,
        };
        ypf_extract_one(&mut f, &entry, &out.join("clamped.bin"), compressed.len() as u64).unwrap();
        let got = std::fs::read(out.join("clamped.bin")).unwrap();
        assert_eq!(got.len(), 1024, "output must be clamped to the declared size");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// Pack a directory → unpack it back: names and bytes must round-trip, and
    /// our own reader must parse the archive it wrote (the writer follows the
    /// same marker/XOR/tail layout as the reader).
    #[test]
    fn create_then_extract_round_trip() {
        let _g = lock();
        let dir = std::env::temp_dir().join(format!("uu_ypf_w_{}", std::process::id()));
        std::fs::create_dir_all(dir.join("src/sub")).unwrap();
        // Names whose SJIS length maps to a marker in the 9..=55 table.
        std::fs::write(dir.join("src/rootfile.txt"), b"hello ypf root").unwrap();
        let big: Vec<u8> = (0..5000u32).map(|i| (i % 251) as u8).collect();
        std::fs::write(dir.join("src/sub/datafile.bin"), &big).unwrap();
        // SJIS 多字节名也在表范围。
        std::fs::write(dir.join("src/日本語名.txt"), "日本語内容".as_bytes()).unwrap();

        let arc = dir.join("out.ypf");
        let n = ypf_create_archive(dir.join("src").to_str().unwrap(), arc.to_str().unwrap(), 6).expect("pack");
        assert_eq!(n, 3, "expected 3 entries");

        let list = list_ypf(arc.to_str().unwrap()).expect("list");
        assert!(list.contains("rootfile.txt"), "list: {list}");
        assert!(list.contains("sub/datafile.bin"), "list: {list}");
        assert!(list.contains("日本語名.txt"), "SJIS name must round-trip: {list}");

        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let (total, fail) = extract_ypf_all(arc.to_str().unwrap(), out.to_str().unwrap()).expect("extract");
        assert_eq!(fail, 0, "no failures");
        assert_eq!(total, 3);
        assert_eq!(std::fs::read(out.join("rootfile.txt")).unwrap(), b"hello ypf root");
        assert_eq!(std::fs::read(out.join("sub/datafile.bin")).unwrap(), big);
        assert_eq!(std::fs::read(out.join("日本語名.txt")).unwrap(), "日本語内容".as_bytes());
        std::fs::remove_dir_all(&dir).ok();
    }
}
