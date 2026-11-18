//! BGI / Ethornell `.arc` reader (extract-only).
//!
//! Two on-disk layouts share the `.arc` extension and this crate:
//!
//! * **v1** — magic `Pack` + `File    ` at 0/4, `count` u32 at 12, then
//!   `0x20`-byte records from 0x10 (16-byte CP932 name, u32 offset at +0x10,
//!   u32 size at +0x14). Payloads live after the index.
//! * **v2** — magic `BURI` + `KO ARC20` (Buriko builds), `0x80`-byte records
//!   (0x60-byte name, u32 offset at +0x60, u32 size at +0x64).
//!
//! Neither is hierarchic: names are flat. v1 payloads may be DSC-compressed
//! (`DSC FORMAT 1.00\0` + a canonical-Huffman bitstream keyed by a 16-bit
//! magic), v2 payloads may be `BSE 1.` images whose first 0x40 bytes are
//! scrambled by a LCG keyed from the entry header.
//!
//! Reference: GARbro `ArcFormats/Ethornell/ArcBGI.cs` (MIT). The DSC Huffman
//! tree is built from a 512-entry depth table exactly as the reference does —
//! this is NOT a generic canonical Huffman layout, the depth order and the
//! leaf/parent interleaving matter.
//!
//! There is no writer: GARbro declares `CanWrite = false` for both layouts and
//! the DSC encoder is not public, so the app treats `.arc` as extract-only.

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jlong};
use archive_common::{s, json_escape, derive_dirs, safe_join, extract_result_json};
use archive_common::extract_progress;
use std::collections::HashSet;
use std::fs::File;
use std::io::{BufReader, BufWriter, Read, Seek, SeekFrom, Write};

/// One directory record. `packed` means the payload is a DSC stream whose
/// decoded length is `unpacked`; `bse` marks a v2 `BSE 1.` image whose header
/// needs unscrambling.
struct BgiEntry {
    name: String,
    offset: u64,
    size: u32,
    packed: bool,
    unpacked: u32,
    bse: Option<u16>,
}

const V1_RECORD: usize = 0x20;
const V2_RECORD: usize = 0x80;
const MAX_COUNT: u32 = 0xfffff;
/// Hard ceiling on a single decoded entry. Real BGI archives hold images and
/// scripts well under this; anything larger is a hostile/corrupt length field
/// and must not be turned into an allocation.
const MAX_ENTRY: u32 = 512 * 1024 * 1024;

fn cstr_cp932(raw: &[u8]) -> String {
    let end = raw.iter().position(|&b| b == 0).unwrap_or(raw.len());
    encoding_rs::SHIFT_JIS
        .decode(&raw[..end])
        .0
        .into_owned()
        .replace('\\', "/")
}

fn open_bgi(input: &str) -> Result<(Vec<BgiEntry>, BufReader<File>, u64), String> {
    let mut f = BufReader::new(File::open(input).map_err(|e| format!("{e}"))?);
    let fsize = f.get_ref().metadata().map(|m| m.len()).map_err(|e| format!("{e}"))?;
    if fsize < 0x20 {
        return Err("BGI: file too small".into());
    }
    let mut hdr = [0u8; 0x10];
    f.read_exact(&mut hdr).map_err(|e| format!("{e}"))?;
    let v2 = &hdr[0..4] == b"BURI" && &hdr[4..12] == b"KO ARC20";
    let v1 = &hdr[0..4] == b"Pack" && &hdr[4..12] == b"File    ";
    if !v1 && !v2 {
        return Err("BGI: not a BGI/Ethornell archive".into());
    }
    let count = u32::from_le_bytes([hdr[12], hdr[13], hdr[14], hdr[15]]);
    if count == 0 || count > MAX_COUNT {
        return Err(format!("BGI: implausible entry count {count}"));
    }
    let rec = if v2 { V2_RECORD } else { V1_RECORD };
    let index_size = rec as u64 * count as u64;
    let base_offset = 0x10u64 + index_size;
    if base_offset > fsize {
        return Err("BGI: index larger than file".into());
    }

    let mut ents = Vec::with_capacity(count as usize);
    let mut rec_buf = vec![0u8; rec];
    f.seek(SeekFrom::Start(0x10)).map_err(|e| format!("{e}"))?;
    for _ in 0..count {
        if f.read_exact(&mut rec_buf).is_err() {
            return Err("BGI: truncated index".into());
        }
        let (name_raw, off_at) = if v2 { (&rec_buf[..0x60], 0x60) } else { (&rec_buf[..0x10], 0x10) };
        let name = cstr_cp932(name_raw);
        let rel = u32::from_le_bytes([rec_buf[off_at], rec_buf[off_at + 1], rec_buf[off_at + 2], rec_buf[off_at + 3]]);
        let size = u32::from_le_bytes([rec_buf[off_at + 4], rec_buf[off_at + 5], rec_buf[off_at + 6], rec_buf[off_at + 7]]);
        let offset = base_offset + rel as u64;
        // CheckPlacement: the entry must sit inside the file.
        if offset > fsize || offset + size as u64 > fsize {
            return Err(format!("BGI: entry '{name}' out of bounds"));
        }
        if name.is_empty() {
            continue;
        }
        ents.push(BgiEntry { name, offset, size, packed: false, unpacked: size, bse: None });
    }

    // Payload sniffing: v1 marks DSC streams, v2 marks scrambled BSE images.
    let mut probe = [0u8; 0x50];
    for e in &mut ents {
        let want = (e.size as usize).min(probe.len());
        if want == 0 {
            continue;
        }
        f.seek(SeekFrom::Start(e.offset)).map_err(|x| format!("{x}"))?;
        if f.read(&mut probe[..want]).unwrap_or(0) < want {
            continue;
        }
        if !v2 && e.size > 0x220 && &probe[0..16] == b"DSC FORMAT 1.00\0" {
            let unpacked = u32::from_le_bytes([probe[0x14], probe[0x15], probe[0x16], probe[0x17]]);
            if unpacked > 0 && unpacked <= MAX_ENTRY {
                e.packed = true;
                e.unpacked = unpacked;
            }
        } else if v2 && e.size >= 0x50 && &probe[0..6] == b"BSE 1." {
            let ver = u16::from_le_bytes([probe[8], probe[9]]);
            if ver == 0x100 || ver == 0x101 {
                e.bse = Some(ver);
            }
        }
    }

    Ok((ents, f, fsize))
}

// ─── DSC: keyed canonical Huffman ───

/// The 16-bit magic that feeds the key LCG. GARbro reads `ReadUInt16()` at 0
/// and shifts it up — i.e. the ASCII `DS` bytes as little-endian.
fn dsc_magic(header: &[u8]) -> u32 {
    (u16::from_le_bytes([header[0], header[1]]) as u32) << 16
}

/// One step of the LCG. Mirrors `BgiDecoderBase.UpdateKey` including its
/// 32-bit wraparound.
fn dsc_update_key(key: &mut u32, magic: u32) -> u8 {
    let v0 = 20021u32.wrapping_mul(*key & 0xffff);
    let mut v1 = magic | (*key >> 16);
    v1 = v1.wrapping_mul(20021).wrapping_add(key.wrapping_mul(346));
    v1 = v1.wrapping_add(v0 >> 16) & 0xffff;
    *key = (v1 << 16).wrapping_add(v0 & 0xffff).wrapping_add(1);
    (v1 & 0xff) as u8
}

/// MSB-first bit reader (GARbro `MsbBitStream`).
struct MsbBits<R: Read> {
    r: R,
    bits: u32,
    cached: u32,
}

impl<R: Read> MsbBits<R> {
    fn new(r: R) -> Self {
        Self { r, bits: 0, cached: 0 }
    }
    fn get(&mut self, count: u32) -> Option<u32> {
        while self.cached < count {
            let mut b = [0u8; 1];
            if self.r.read(&mut b).ok()? != 1 {
                return None;
            }
            self.bits = (self.bits << 8) | b[0] as u32;
            self.cached += 8;
        }
        self.cached -= count;
        let mask = if count >= 32 { u32::MAX } else { (1u32 << count) - 1 };
        Some((self.bits >> self.cached) & mask)
    }
}

#[derive(Clone, Copy, Default)]
struct HCode {
    code: u16,
    depth: u16,
}

#[derive(Clone, Copy, Default)]
struct HNode {
    parent: bool,
    code: u16,
    left: i32,
    right: i32,
}

/// Builds the decode tree from the depth table. Ported line-for-line from
/// `DscDecoder.CreateHuffmanTree`: a level holds the leaves at that depth
/// followed by parents whose children live on the next level, and the child
/// slots are addressed through a two-row ping-pong index array.
fn build_tree(hcode: &[HCode], node_count: usize) -> Option<Vec<HNode>> {
    let mut hnodes = vec![HNode::default(); 1023];
    let mut nodes_index = [[0usize; 512]; 2];
    let mut next_node_index = 1usize;
    let mut depth_nodes = 1usize;
    let mut depth = 0u16;
    let mut child_index = 0usize;
    nodes_index[0][0] = 0;

    let mut n = 0usize;
    // Every iteration consumes at least one level; 512 levels is far beyond any
    // real depth table and bounds the loop on corrupt input.
    for _ in 0..512 {
        if n >= node_count {
            break;
        }
        let cur = child_index;
        child_index ^= 1;

        let mut depth_existed = 0usize;
        while n < hcode.len() && hcode[n].depth == depth {
            if depth_existed >= 512 {
                return None;
            }
            let slot = nodes_index[cur][depth_existed];
            if slot >= hnodes.len() {
                return None;
            }
            hnodes[slot] = HNode { parent: false, code: hcode[n].code, left: 0, right: 0 };
            depth_existed += 1;
            n += 1;
        }
        let to_create = depth_nodes.checked_sub(depth_existed)?;
        // The child row has 512 slots and is addressed as i*2 / i*2+1.
        if to_create * 2 > 512 {
            return None;
        }
        for i in 0..to_create {
            let left = next_node_index;
            let right = next_node_index + 1;
            if right >= hnodes.len() || depth_existed + i >= 512 {
                return None;
            }
            nodes_index[child_index][i * 2] = left;
            nodes_index[child_index][i * 2 + 1] = right;
            let slot = nodes_index[cur][depth_existed + i];
            if slot >= hnodes.len() {
                return None;
            }
            hnodes[slot] = HNode { parent: true, code: 0, left: left as i32, right: right as i32 };
            next_node_index += 2;
        }
        depth += 1;
        depth_nodes = to_create * 2;
    }
    Some(hnodes)
}

/// Decodes one DSC stream into a buffer of `out_size` bytes.
///
/// The loop is driven by `out_size`, NOT by the `dec_count` word at 0x18.
/// Those two differ in practice (a real fixture declares 3550 output bytes but
/// only 361 at 0x18), and driving the loop by `dec_count` truncates the file
/// after the first few hundred bytes. arc_unpacker's decoder confirms this: it
/// skips the word entirely and decompresses until `output_size` is filled.
fn dsc_decode(data: &[u8], out_size: u32) -> Result<Vec<u8>, String> {
    if data.len() < 0x20 + 512 {
        return Err("BGI DSC: truncated header".into());
    }
    let magic = dsc_magic(data);
    let mut key = u32::from_le_bytes([data[0x10], data[0x11], data[0x12], data[0x13]]);

    let mut hcode: Vec<HCode> = Vec::with_capacity(512);
    for i in 0..512u16 {
        let depth = data[0x20 + i as usize].wrapping_sub(dsc_update_key(&mut key, magic));
        if depth != 0 {
            hcode.push(HCode { code: i, depth: depth as u16 });
        }
    }
    if hcode.is_empty() {
        return Err("BGI DSC: empty Huffman table".into());
    }
    hcode.sort_by_key(|c| (c.depth, c.code));
    let tree = build_tree(&hcode, hcode.len()).ok_or("BGI DSC: bad Huffman tree")?;

    let mut out = vec![0u8; out_size as usize];
    let mut dst = 0usize;
    let mut bits = MsbBits::new(&data[0x20 + 512..]);
    while dst < out.len() {
        let mut node = 0usize;
        // A valid tree is at most 512 deep; bound the walk so a corrupt table
        // cannot spin forever on a self-referencing parent chain.
        let mut guard = 0;
        loop {
            let Some(bit) = bits.get(1) else { return Ok(out) };
            let next = if bit == 0 { tree[node].left } else { tree[node].right };
            if next < 0 || next as usize >= tree.len() {
                return Err("BGI DSC: bad tree link".into());
            }
            node = next as usize;
            if !tree[node].parent {
                break;
            }
            guard += 1;
            if guard > 1024 {
                return Err("BGI DSC: tree walk did not terminate".into());
            }
        }
        let code = tree[node].code as u32;
        if code >= 256 {
            let Some(raw_off) = bits.get(12) else { return Ok(out) };
            let offset = raw_off as usize + 2;
            let count = ((code & 0xff) as usize + 2).min(out.len() - dst);
            if offset > dst {
                return Err("BGI DSC: match out of range".into());
            }
            // Overlapping copy, byte by byte (matches Binary.CopyOverlapped).
            for i in 0..count {
                out[dst + i] = out[dst - offset + i];
            }
            dst += count;
        } else {
            out[dst] = code as u8;
            dst += 1;
        }
    }
    Ok(out)
}

// ─── BSE: v2 scrambled image headers ───

/// The two LCGs differ only in their constants. Signed 32-bit arithmetic is
/// deliberate: `m_key * N >> s` in the reference is an ARITHMETIC shift, so a
/// u32 port would produce different keys for negative intermediates.
struct BseGen {
    key: i32,
    mul: i32,
    shift: u32,
    add: i32,
    bias: i32,
    xor: i32,
}

impl BseGen {
    fn next(&mut self) -> i32 {
        let v = (self.key.wrapping_mul(self.mul) >> self.shift)
            .wrapping_add(self.key.wrapping_mul(self.add))
            .wrapping_add(self.bias)
            ^ self.xor;
        self.key = (v as u32).rotate_right(16) as i32;
        self.key
    }
}

fn decrypt_bse(header: &mut [u8; 0x40], gen: &mut BseGen) {
    let mut decoded = [false; 0x40];
    for _ in 0..0x40 {
        let mut dst = (gen.next() & 0x3f) as usize;
        while decoded[dst] {
            dst = (dst + 1) & 0x3f;
        }
        let shift = (gen.next() & 7) as u32;
        let right = (gen.next() & 1) == 0;
        let symbol = (header[dst] as i32).wrapping_sub(gen.next()) as u8;
        header[dst] = if right { symbol.rotate_right(shift) } else { symbol.rotate_left(shift) };
        decoded[dst] = true;
    }
}

// ─── Extract ───

/// Bytes the user actually gets for [e]. v2 `BSE 1.` payloads lose their
/// 0x10-byte wrapper (the reference emits the unscrambled 0x40-byte header
/// followed by the body), so the declared size is not the output size.
fn out_len(e: &BgiEntry) -> u64 {
    if e.bse.is_some() {
        e.size.saturating_sub(0x10) as u64
    } else {
        e.unpacked as u64
    }
}

fn write_entry(f: &mut BufReader<File>, e: &BgiEntry, dest: &std::path::Path) -> Result<(), String> {
    if let Some(p) = dest.parent() {
        std::fs::create_dir_all(p).map_err(|x| format!("{x}"))?;
    }
    let mut out = BufWriter::with_capacity(
        256 * 1024,
        archive_common::ProgressWriter::extract(File::create(dest).map_err(|x| format!("{x}"))?),
    );
    if e.size == 0 {
        out.flush().map_err(|x| format!("{x}"))?;
        return Ok(());
    }
    f.seek(SeekFrom::Start(e.offset)).map_err(|x| format!("{x}"))?;

    if e.packed {
        let mut raw = vec![0u8; e.size as usize];
        f.read_exact(&mut raw).map_err(|x| format!("{x}"))?;
        let data = dsc_decode(&raw, e.unpacked)?;
        out.write_all(&data).map_err(|x| format!("{x}"))?;
        out.flush().map_err(|x| format!("{x}"))?;
        return Ok(());
    }

    if let Some(ver) = e.bse {
        let mut raw = vec![0u8; e.size as usize];
        f.read_exact(&mut raw).map_err(|x| format!("{x}"))?;
        let key = u32::from_le_bytes([raw[0x0c], raw[0x0d], raw[0x0e], raw[0x0f]]) as i32;
        let mut gen = if ver == 0x101 {
            BseGen { key, mul: 127, shift: 7, add: 83, bias: 53, xor: 0xB97A7E5C_u32 as i32 }
        } else {
            BseGen { key, mul: 257, shift: 8, add: 97, bias: 23, xor: 0xA6CD9B75_u32 as i32 }
        };
        let mut header = [0u8; 0x40];
        header.copy_from_slice(&raw[0x10..0x50]);
        decrypt_bse(&mut header, &mut gen);
        out.write_all(&header).map_err(|x| format!("{x}"))?;
        out.write_all(&raw[0x50..]).map_err(|x| format!("{x}"))?;
        out.flush().map_err(|x| format!("{x}"))?;
        return Ok(());
    }

    // Stored: stream straight through so a huge entry never lands in memory.
    let mut limited = (&mut *f).take(e.size as u64);
    std::io::copy(&mut limited, &mut out).map_err(|x| format!("{x}"))?;
    out.flush().map_err(|x| format!("{x}"))?;
    Ok(())
}

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

fn list_bgi(input: &str) -> Result<String, String> {
    let (ents, _, _) = open_bgi(input)?;
    let names: Vec<&str> = ents.iter().map(|e| e.name.as_str()).collect();
    let dirs = derive_dirs(&names);
    let mut all: Vec<(String, u64, bool)> = Vec::new();
    for d in &dirs {
        all.push((d.clone(), 0, true));
    }
    for e in &ents {
        all.push((e.name.clone(), out_len(e), false));
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
    let (ents, mut f, _) = open_bgi(input)?;
    let wanted = |e: &BgiEntry| match &sel {
        None => true,
        Some(ss) => {
            ss.contains(e.name.as_str())
                || ss.iter().any(|d| e.name.starts_with(&format!("{d}/")))
        }
    };
    let total: u64 = ents.iter().filter(|e| wanted(e)).map(out_len).sum();
    extract_progress::reset(total);
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
        extract_progress::set_file(out_len(e));
        match safe_join(output, &e.name) {
            Ok(d) => {
                if guard_panic(|| write_entry(&mut f, e, &d)).is_err() {
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
pub fn extract_bgi_host(input: &str, output: &str) -> Result<(u32, u32), String> {
    run_extract(input, output, None)
}

#[doc(hidden)]
pub fn list_bgi_host(input: &str) -> Result<String, String> {
    list_bgi(input)
}

// ─── JNI ───

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_BgiCore_bgiExtractProgressCount(_: JNIEnv, _: JClass) -> jlong {
    extract_progress::bytes() as jlong
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_BgiCore_bgiExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong {
    extract_progress::total_bytes() as jlong
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_BgiCore_bgiExtractProgressFileCount(_: JNIEnv, _: JClass) -> jlong {
    extract_progress::file_bytes() as jlong
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_BgiCore_bgiExtractProgressFileTotal(_: JNIEnv, _: JClass) -> jlong {
    extract_progress::file_total() as jlong
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_BgiCore_bgiExtractProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_BgiCore_bgiExtractCancel(_: JNIEnv, _: JClass) {
    extract_progress::cancel();
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_BgiCore_bgiExtract(
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
pub extern "system" fn Java_com_usefulunpacker_BgiCore_bgiExtractSelected(
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
pub extern "system" fn Java_com_usefulunpacker_BgiCore_bgiListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i);
    match guard_panic(move || list_bgi(&inp)) {
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
        let d = std::env::temp_dir().join(format!("uu_bgi_{tag}_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    /// Minimal base64 decoder. The two fixtures below come from arc_unpacker's
    /// test suite and are embedded as text rather than checked in as binaries.
    fn b64(s: &str) -> Vec<u8> {
        fn v(c: u8) -> u32 {
            match c {
                b'A'..=b'Z' => (c - b'A') as u32,
                b'a'..=b'z' => (c - b'a') as u32 + 26,
                b'0'..=b'9' => (c - b'0') as u32 + 52,
                b'+' => 62,
                b'/' => 63,
                _ => 0,
            }
        }
        let raw: Vec<u8> = s.bytes().filter(|c| !c.is_ascii_whitespace() && *c != b'=').collect();
        let mut out = Vec::with_capacity(raw.len() * 3 / 4);
        for ch in raw.chunks(4) {
            let mut buf = 0u32;
            for (i, c) in ch.iter().enumerate() {
                buf |= v(*c) << (18 - 6 * i);
            }
            for i in 0..ch.len() - 1 {
                out.push((buf >> (16 - 8 * i)) as u8);
            }
        }
        out
    }

    /// A real `PackFile    ` archive (arc_unpacker's
    /// `tests/dec/bgi/files/arc/test_packfile.arc`). Unlike the hand-built
    /// fixtures above it exercises the exact 0x20 record layout and the
    /// relative-to-data-start offset convention on a file produced by the
    /// engine, not by this crate.
    #[test]
    fn real_packfile_archive() {
        let _g = lock();
        let arc = b64(
            "UGFja0ZpbGUgICAgAgAAAGFiYy50eHQAAAAAAAAAAAAAAAAAAwAAAHRyYXNoPz8/YW5vdGhlci50eHQAAAAAAAMAAAAKAAAAdHJhc2g/Pz8xMjNhYmNkZWZnaGlq",
        );
        let dir = tmpdir("realarc");
        let p = dir.join("test_packfile.arc");
        std::fs::write(&p, &arc).unwrap();
        let (ents, _, _) = open_bgi(p.to_str().unwrap()).unwrap();
        let names: Vec<&str> = ents.iter().map(|e| e.name.as_str()).collect();
        assert_eq!(names, vec!["abc.txt", "another.txt"]);

        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let (n, fail) = run_extract(p.to_str().unwrap(), out.to_str().unwrap(), None).unwrap();
        assert_eq!((n, fail), (2, 0));
        assert_eq!(std::fs::read(out.join("abc.txt")).unwrap(), b"123");
        assert_eq!(std::fs::read(out.join("another.txt")).unwrap(), b"abcdefghij");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// A real DSC stream (arc_unpacker's
    /// `tests/dec/bgi/files/dsc/setupforgallery`). This is the fixture that
    /// exposed the termination bug: the header declares 3550 output bytes but
    /// only 361 in the `dec_count` word at 0x18. Driving the loop by
    /// `dec_count` produced 361 correct bytes followed by zeros, so the test
    /// asserts on a byte far past that point as well as on the tail.
    #[test]
    fn real_dsc_fixture_decodes_past_dec_count() {
        let _g = lock();
        let dsc = b64(concat!(
            "RFNDIEZPUk1BVCAxLjAwAPBaxBTeDQAAaQEAAAAAAADCbLjLijrx7ORjpM9qPaYggR+TaRXMeMJ+Xd+qsFf+RKMOlfcHMUYnIc4XCQA4Zv4EjaUQSjL3ksvIij14OZV3vTQTfwCcYKWtZZHFWeBy4zrqVX+mn4dRUynRTTfaS935sEpY36LUynnAM9jMUHEx9N/as5xN2H4AOsOzJnGv7y8jS2f8flYaRa79XZ3M8l8EPDaoZXRiiKmrgn20H3MeJ0JGv5V+XQ3p5x4zN4mN/Fqbh0t2Q9T4D0sLpTE6DfDB1zs6G7qZEE0MplHtwdxhrsvdrAgCvj4ospEJ9d0t1s2QQejfg6C2QuyuwRWkdB7wfWGlk+K60a2xIrlYVWRIOSyEvj8N+l6kPFVxCX/2o/htKkRPGmUrj/fep7LZSr7DWgmkfYVGfzumnVofdOZnhkQ5eqznQsKcPMbJW5F2r1XO9KMIJiOi7/C0dLHA7uSLyb0/+3P+D644ChmsxIGUwk1YrDAwJjeUzau3FqShkNWwx4qSBCyKnKS5jfumAuXgik64TdbktgD1mRqmUpfkPrBvVcrNYqs9N+weoW6SGok77b+2UPiXDSGWlrkCZ1icgsyW/AY46WuqusP3VMirkxpOucXX91GSCLsjp+2qY8nc90Z+6Ei+/bQ8dlORPRDY34md0aJ8XWdjILnISfd/m3gHXaqqJ6DLDYotD1yJwKSJEMAB5O08AFSgAN4wACAAmJyUbwAXtAAbBAALnAArzAAN2wAC7pwLhAA7xwEkARedIEVEcAoQABBW+hwVqQAA/QcBZAG3pOBVRAA11AfCAd8DgjdwAAvD4OkHr1pB66gQhB6ED6ADb2HAaqwACEL5AC3xOBt3xC6AB9ACzhwPhDG6xBhDqEA6jOBEIAsAEVQkPKeABAHV4lB1figJcIMyA6hACxwAC/KQsv2kBbrA2EGrygDUgNpAC1QkEapSH1ABN2Cxf6EQ7/kIdSqEOqmCHVhCHfJYh1c0Q6vhEOsCEOdUIdZMEOsyEOjLEOkNEOk0BDhAKgAitiEO/ShDregB0gEGADqpUB1cMFKlEKVVrFKu2ilS4SWppClWKhDr5gh1+Qh0dYh2A0Q4QBjA7HAm+YABcygJsJIE08JaUASwgDXqgnoyQnsRoT2LCNdPE67BrsNQ12Qwa7JIa7GWNdlNEOzIRDs0hDstQh2cwQ7PIQ7OWIdoNEO0YRDtIhDtNQh2owQ7VIQ7WWIdrtEO2IWKtkhDtxQh20TFW8oDrbYKVuEKVwrFK3Wilb0JLW+QpXEoQ7gYIdwkIdxrEO5QAG9wgPJza9582vUAAVbJm3l3/LqX/Y8qVJl39NazJkQAAqYIJpFtaOfUQBJtveXJwCXXr8kAHzgCXPs+BAYAl2ntZwAsd8CinMBR93wKHgCZkiXaCAnGfATMAS49gX0AHmAJcdfAPMAS4YEycAeYAl2LOBwipBwimAQfA5AAkA=="
        ));
        assert_eq!(dsc.len(), 1117);
        let out = dsc_decode(&dsc, 3550).expect("decode");
        assert_eq!(out.len(), 3550);
        assert_eq!(
            &out[..64],
            &[
                0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x14, 0x01, 0x00, 0x00, 0x20, 0x00, 0x00,
                0x00, 0x11, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0xd8, 0x0c, 0x00, 0x00, 0x1e, 0x00,
                0x00, 0x00, 0x7f, 0x00, 0x00, 0x00, 0xf4, 0x0c, 0x00, 0x00, 0x16, 0x00, 0x00, 0x00, 0x02,
                0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x09, 0x00, 0x00, 0x00,
            ]
        );
        // Bytes past `dec_count` (361) must be real stream output. Under the
        // old termination these were zero, which is exactly the bug.
        assert_eq!(&out[361..368], &[0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00]);
        assert!(out[3480..].iter().any(|&b| b != 0), "tail should hold data, not padding");
    }

    /// Builds a v1 archive: `Pack` + `File    `, count, 0x20 records, payloads.
    fn build_v1(files: &[(&str, &[u8])]) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(b"Pack");
        out.extend_from_slice(b"File    ");
        out.extend_from_slice(&(files.len() as u32).to_le_bytes());
        let base = 0x10 + 0x20 * files.len();
        let mut payload = Vec::new();
        for (name, data) in files {
            let mut rec = [0u8; 0x20];
            let nb = name.as_bytes();
            rec[..nb.len().min(16)].copy_from_slice(&nb[..nb.len().min(16)]);
            rec[0x10..0x14].copy_from_slice(&(payload.len() as u32).to_le_bytes());
            rec[0x14..0x18].copy_from_slice(&(data.len() as u32).to_le_bytes());
            out.extend_from_slice(&rec);
            payload.extend_from_slice(data);
        }
        assert_eq!(out.len(), base);
        out.extend_from_slice(&payload);
        out
    }

    #[test]
    fn v1_list_and_extract() {
        let _g = lock();
        let dir = tmpdir("v1");
        let body = b"hello bgi archive".to_vec();
        let arc = build_v1(&[("data.txt", &body), ("sub/a.bin", b"second")]);
        let p = dir.join("game.arc");
        std::fs::write(&p, &arc).unwrap();

        let json = list_bgi(p.to_str().unwrap()).unwrap();
        assert!(json.contains("data.txt"), "{json}");
        assert!(json.contains("sub/a.bin"), "{json}");

        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let (n, fail) = run_extract(p.to_str().unwrap(), out.to_str().unwrap(), None).unwrap();
        assert_eq!((n, fail), (2, 0));
        assert_eq!(std::fs::read(out.join("data.txt")).unwrap(), body);
        assert_eq!(std::fs::read(out.join("sub/a.bin")).unwrap(), b"second");
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn rejects_wrong_magic_and_oob_entries() {
        let _g = lock();
        let dir = tmpdir("bad");
        let p = dir.join("nope.arc");
        std::fs::write(&p, b"NotAnArchiveAtAll!!!!").unwrap();
        assert!(open_bgi(p.to_str().unwrap()).is_err());

        // count says 1 but the declared size runs past EOF
        let mut arc = build_v1(&[("x.bin", b"1234")]);
        let size_at = 0x10 + 0x14;
        arc[size_at..size_at + 4].copy_from_slice(&0xffffu32.to_le_bytes());
        let p2 = dir.join("oob.arc");
        std::fs::write(&p2, &arc).unwrap();
        assert!(open_bgi(p2.to_str().unwrap()).is_err());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn selected_only_extracts_matching_names() {
        let _g = lock();
        let dir = tmpdir("sel");
        let arc = build_v1(&[("a.txt", b"AAA"), ("b.txt", b"BBB")]);
        let p = dir.join("s.arc");
        std::fs::write(&p, &arc).unwrap();
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let (n, fail) = run_extract(p.to_str().unwrap(), out.to_str().unwrap(), Some("b.txt")).unwrap();
        assert_eq!((n, fail), (1, 0));
        assert!(!out.join("a.txt").exists());
        assert_eq!(std::fs::read(out.join("b.txt")).unwrap(), b"BBB");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// The DSC key LCG must stay bit-exact with the reference: a silent drift
    /// here turns every depth byte into noise and the tree never builds. The
    /// first step is computed by hand from `20021*(key&0xffff)`.
    #[test]
    fn dsc_key_steps_are_deterministic() {
        let mut key = 0x1234_5678u32;
        let magic = 0x5344_0000u32;
        let first = dsc_update_key(&mut key, magic);
        // 20021*0x5678 = 0x6A7F_0D58 -> v0; v1 = magic|0x1234 = 0x5344_1234
        // v1*20021 + key*346 + (v0>>16), masked to 16 bits.
        let v0 = 20021u32.wrapping_mul(0x5678);
        let mut v1 = 0x5344_1234u32;
        v1 = v1.wrapping_mul(20021).wrapping_add(0x1234_5678u32.wrapping_mul(346));
        v1 = v1.wrapping_add(v0 >> 16) & 0xffff;
        assert_eq!(first as u32, v1 & 0xff);
        assert_eq!(key, (v1 << 16).wrapping_add(v0 & 0xffff).wrapping_add(1));
    }

    #[test]
    fn msb_bit_reader_is_msb_first() {
        let data = [0b1010_0110u8, 0b1100_0011];
        let mut b = MsbBits::new(&data[..]);
        assert_eq!(b.get(1), Some(1));
        assert_eq!(b.get(1), Some(0));
        assert_eq!(b.get(2), Some(0b10));
        assert_eq!(b.get(4), Some(0b0110));
        assert_eq!(b.get(8), Some(0b1100_0011));
        assert_eq!(b.get(1), None);
    }

    #[test]
    fn empty_input_is_rejected() {
        let _g = lock();
        let dir = tmpdir("empty");
        let p = dir.join("e.arc");
        std::fs::write(&p, b"").unwrap();
        assert!(open_bgi(p.to_str().unwrap()).is_err());
        std::fs::remove_dir_all(&dir).ok();
    }

    // ─── DSC round-trip ───
    //
    // The decoder has no real sample to check against, so this test drives it
    // with a stream produced by a *standard canonical Huffman* encoder written
    // independently below. A canonical code assignment is exactly what the
    // reference tree builder reconstructs from the depth table, so if the port
    // got the level/parent interleaving wrong the codes would not line up and
    // the decode would produce garbage.
    //
    // Alphabet: symbols 0,1,2 at depth 2 and 256,257 at depth 3. Kraft sum
    // 3/4 + 2/8 = 1, so the table is complete.

    struct BitW {
        out: Vec<u8>,
        cur: u8,
        n: u32,
    }
    impl BitW {
        fn new() -> Self {
            Self { out: Vec::new(), cur: 0, n: 0 }
        }
        fn put(&mut self, code: u32, len: u32) {
            for i in (0..len).rev() {
                self.cur = (self.cur << 1) | (((code >> i) & 1) as u8);
                self.n += 1;
                if self.n == 8 {
                    self.out.push(self.cur);
                    self.cur = 0;
                    self.n = 0;
                }
            }
        }
        fn finish(mut self) -> Vec<u8> {
            if self.n > 0 {
                self.cur <<= 8 - self.n;
                self.out.push(self.cur);
            }
            self.out
        }
    }

    fn dsc_fixture(dec: &[(u16, Option<u32>)], out_size: u32) -> Vec<u8> {
        // canonical codes for the alphabet above, sorted by (depth, symbol)
        let table: [(u16, u16, u32); 5] = [
            (0, 2, 0b00),
            (1, 2, 0b01),
            (2, 2, 0b10),
            (256, 3, 0b110),
            (257, 3, 0b111),
        ];
        let mut blob = vec![0u8; 0x20];
        blob[0..16].copy_from_slice(b"DSC FORMAT 1.00\0");
        let key0 = 0xDEAD_BEEFu32;
        blob[0x10..0x14].copy_from_slice(&key0.to_le_bytes());
        blob[0x14..0x18].copy_from_slice(&out_size.to_le_bytes());
        let dec_count: u32 = dec
            .iter()
            .map(|(s, m)| if *s >= 256 { (m.unwrap() & 0xff) + 2 } else { 1 })
            .sum();
        blob[0x18..0x1c].copy_from_slice(&dec_count.to_le_bytes());

        // The depth table is encrypted with one key step per entry — all 512 of
        // them, not just the symbols in use. Unused entries carry depth 0, so
        // they store the raw key byte (the reader subtracts it back to zero).
        let magic = dsc_magic(&blob);
        let mut key = key0;
        blob.resize(0x20 + 512, 0);
        for i in 0..512usize {
            let k = dsc_update_key(&mut key, magic);
            let depth: u8 = match i {
                0 | 1 | 2 => 2,
                256 | 257 => 3,
                _ => 0,
            };
            blob[0x20 + i] = depth.wrapping_add(k);
        }

        let mut bits = BitW::new();
        for (sym, m) in dec {
            let (_, _, code) = table.iter().find(|(s, _, _)| s == sym).copied().unwrap();
            let len = table.iter().find(|(s, _, _)| s == sym).unwrap().1 as u32;
            bits.put(code, len);
            if let Some(off) = m {
                bits.put(*off, 12);
            }
        }
        blob.extend_from_slice(&bits.finish());
        // pad past the 0x220 threshold so the reader treats it as packed
        while blob.len() <= 0x220 {
            blob.push(0);
        }
        blob
    }

    #[test]
    fn dsc_round_trips_literals_and_matches() {
        // literals 0,1,2 then a 3-byte match at offset 2 (field 0), repeated
        let seq = [
            (0u16, None),
            (1, None),
            (2, None),
            (256, Some(0)),
            (257, Some(1)),
        ];
        let blob = dsc_fixture(&seq, 8);
        let out_size = u32::from_le_bytes([blob[0x14], blob[0x15], blob[0x16], blob[0x17]]);
        let got = dsc_decode(&blob, out_size).expect("decode");
        // literals 0,1,2; match(off 2, len 2) -> 1,2 ; match(off 3, len 3) -> 2,1,2
        assert_eq!(got, vec![0, 1, 2, 1, 2, 2, 1, 2]);
    }

    #[test]
    fn dsc_rejects_a_broken_depth_table() {
        let seq = [(0u16, None)];
        let mut blob = dsc_fixture(&seq, 1);
        // zero every depth byte: nothing survives the key subtraction, so the
        // table is empty and the decoder must refuse instead of looping.
        for i in 0..512 {
            blob[0x20 + i] = 0;
        }
        assert!(dsc_decode(&blob, 1).is_err());
    }

    #[test]
    fn packed_entry_is_decoded_end_to_end() {
        let _g = lock();
        let dir = tmpdir("packed");
        let seq = [(0u16, None), (1, None), (2, None), (256, Some(0))];
        let blob = dsc_fixture(&seq, 5);
        let arc = build_v1(&[("img.cbg", &blob)]);
        let p = dir.join("p.arc");
        std::fs::write(&p, &arc).unwrap();
        let (ents, _, _) = open_bgi(p.to_str().unwrap()).unwrap();
        assert!(ents[0].packed, "DSC magic above 0x220 must be recognised");
        assert_eq!(ents[0].unpacked, 5);
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let (n, fail) = run_extract(p.to_str().unwrap(), out.to_str().unwrap(), None).unwrap();
        assert_eq!((n, fail), (1, 0));
        assert_eq!(std::fs::read(out.join("img.cbg")).unwrap(), vec![0, 1, 2, 1, 2]);
        std::fs::remove_dir_all(&dir).ok();
    }
}
