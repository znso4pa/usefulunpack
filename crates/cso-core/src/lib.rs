//! PSP CISO (CSO) compressed ISO image support.
//!
//! Two format variants are handled:
//!
//! - **Standard CISO** (`header_size > 24`): The header_size field includes the index
//!   table. Index entries store `(absolute_position << 1) | compression_flag_in_bit0`.
//!   Data area starts at byte `header_size`.
//!
//! - **gen_corpus** (`header_size == 24`): The index immediately follows the 24-byte
//!   header. Index entries store `bit31 = compression_flag`, `lower31 = absolute_position >> 1`.
//!   Data area starts at byte 0 (offsets are absolute).
//!
//! ISO→CSO compresses every block (falling back to raw when zlib wouldn't shrink);
//! CSO→ISO reconstructs the original image. Both directions stream block-by-block
//! with byte progress and cancellation.

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, jlong, JNI_TRUE, JNI_FALSE};
use archive_common::{s, extract_progress};
use flate2::write::ZlibEncoder;
use flate2::read::ZlibDecoder;
use flate2::Compression;
use std::io::{Read, Seek, SeekFrom, Write};

const CSO_MAGIC: &[u8; 4] = b"CISO";
const CSO_HEADER_SIZE: usize = 24;

fn guarded<T: Send + 'static>(f: impl FnOnce() -> Result<T, String> + Send + 'static) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|panic| {
        let msg = panic.downcast_ref::<&str>().copied()
            .or_else(|| panic.downcast_ref::<String>().map(|s| s.as_str()))
            .unwrap_or("unknown panic");
        Err(format!("panic: {msg}"))
    })
}

/// Host-side (non-JNI) extraction entry point.
#[doc(hidden)]
pub fn extract_cso_host(input: &str, output: &str) -> Result<u32, String> {
    cso_to_iso(input, output)
}

/// Parsed index entry: absolute file position and compression flag.
struct IndexEntry {
    pos: u64,
    compressed: bool,
}

/// Decode a standard CISO index entry: `(absolute_position << 1) | bit0_flag`.
fn decode_standard(entry: u32) -> IndexEntry {
    IndexEntry { pos: (entry >> 1) as u64, compressed: (entry & 1) != 0 }
}

/// Decode a gen_corpus index entry: `bit31_flag | (absolute_position >> 1)`.
fn decode_gen_corpus(entry: u32) -> IndexEntry {
    IndexEntry { pos: ((entry & 0x7FFFFFFF) as u64) << 1, compressed: (entry & 0x80000000) != 0 }
}

struct ParsedCso {
    block_size: u32,
    total_bytes: u64,
    entries: Vec<IndexEntry>,
}

/// Parses a CSO header + index table, detecting the format variant.
fn read_cso_index(input: &str) -> Result<ParsedCso, String> {
    let mut f = std::fs::File::open(input).map_err(|e| format!("CSO open {input}: {e}"))?;
    let mut hdr = [0u8; CSO_HEADER_SIZE];
    f.read_exact(&mut hdr).map_err(|e| format!("CSO header: {e}"))?;
    if &hdr[0..4] != CSO_MAGIC {
        return Err("CSO: bad magic (not a CISO image)".to_string());
    }
    let header_size = u32::from_le_bytes([hdr[4], hdr[5], hdr[6], hdr[7]]) as usize;
    let total_bytes = u64::from_le_bytes(hdr[8..16].try_into().unwrap());
    let block_size = u32::from_le_bytes([hdr[16], hdr[17], hdr[18], hdr[19]]);
    if block_size == 0 || block_size > 64 * 1024 {
        return Err(format!("CSO: bad block size {block_size}"));
    }
    if header_size < CSO_HEADER_SIZE {
        return Err(format!("CSO: bad header size {header_size}"));
    }

    let expected_blocks = (total_bytes + block_size as u64 - 1) / block_size as u64;
    let expected_index_entries = (expected_blocks + 1) as usize;

    let is_standard = header_size > CSO_HEADER_SIZE;
    let index_start = CSO_HEADER_SIZE;

    let file_size = f.metadata().map(|m| m.len()).unwrap_or(0);
    let available_for_index = (file_size as usize - index_start) / 4;
    let index_count = available_for_index.min(expected_index_entries);

    if index_count == 0 {
        return Err("CSO: no index table found".to_string());
    }

    f.seek(std::io::SeekFrom::Start(index_start as u64)).map_err(|e| format!("CSO seek: {e}"))?;
    let mut raw = vec![0u32; index_count];
    let mut buf = vec![0u8; index_count * 4];
    f.read_exact(&mut buf).map_err(|e| format!("CSO index: {e}"))?;
    for (i, chunk) in buf.chunks_exact(4).enumerate() {
        raw[i] = u32::from_le_bytes(chunk.try_into().unwrap());
    }

    let decoder: fn(u32) -> IndexEntry = if is_standard { decode_standard } else { decode_gen_corpus };
    let entries: Vec<IndexEntry> = raw.iter().map(|&v| decoder(v)).collect();

    Ok(ParsedCso { block_size, total_bytes, entries })
}

/// Decodes one CSO block into a Vec<u8> (may be shorter than block_size for the last block).
fn read_cso_block(
    f: &mut std::fs::File,
    entry: &IndexEntry,
    next: &IndexEntry,
    block_size: usize,
    cur_pos: &mut u64,
) -> Result<Vec<u8>, String> {
    let start = entry.pos;
    let end = next.pos;
    if end < start {
        return Err("CSO: corrupt block index (offsets decrease)".to_string());
    }
    let len = (end - start) as usize;
    if len > 64 * 1024 * 1024 {
        return Err(format!("CSO: block too large ({len} bytes)"));
    }
    if *cur_pos != start {
        f.seek(SeekFrom::Start(start)).map_err(|e| format!("CSO seek: {e}"))?;
    }
    let mut data = vec![0u8; len];
    f.read_exact(&mut data).map_err(|e| format!("CSO read block: {e}"))?;
    *cur_pos = end;
    if entry.compressed {
        let mut dec = ZlibDecoder::new(&data[..]);
        let mut out = vec![0u8; block_size];
        let mut filled = 0usize;
        loop {
            let n = dec.read(&mut out[filled..]).map_err(|e| format!("CSO inflate: {e}"))?;
            if n == 0 { break; }
            filled += n;
            if filled >= block_size {
                let mut extra = [0u8; 1];
                if dec.read(&mut extra).map_err(|e| format!("CSO inflate: {e}"))? != 0 {
                    return Err(format!("CSO: block inflates beyond {block_size} bytes"));
                }
                break;
            }
        }
        out.truncate(filled);
        Ok(out)
    } else {
        Ok(data)
    }
}

/// CSO → ISO: decompresses a compressed ISO image back to the original bytes.
fn cso_to_iso(input: &str, output: &str) -> Result<u32, String> {
    let r = cso_to_iso_inner(input, output);
    if r.is_err() {
        let _ = std::fs::remove_file(output);
    }
    r
}

fn cso_to_iso_inner(input: &str, output: &str) -> Result<u32, String> {
    let cso = read_cso_index(input)?;
    let mut src = std::fs::File::open(input).map_err(|e| format!("CSO open {input}: {e}"))?;
    let mut out = std::io::BufWriter::with_capacity(256 * 1024, std::fs::File::create(output).map_err(|e| format!("CSO create {output}: {e}"))?);
    extract_progress::reset(cso.total_bytes);
    let block_count = cso.entries.len().saturating_sub(1);

    let mut written: u64 = 0;
    let mut cur_pos: u64 = 0;
    for i in 0..block_count {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        let b = read_cso_block(&mut src, &cso.entries[i], &cso.entries[i + 1], cso.block_size as usize, &mut cur_pos)?;
        if written + b.len() as u64 > cso.total_bytes {
            return Err(format!("CSO: output exceeds declared size {}", cso.total_bytes));
        }
        out.write_all(&b).map_err(|e| format!("CSO write: {e}"))?;
        written += b.len() as u64;
        extract_progress::add_bytes(b.len() as u64);
    }
    out.flush().map_err(|e| format!("CSO flush: {e}"))?;
    if written < cso.total_bytes {
        return Err(format!("CSO: output {} bytes < declared {}", written, cso.total_bytes));
    }
    Ok(block_count as u32)
}

/// ISO → CSO: compresses an ISO image into a PSP CISO archive (standard format).
fn iso_to_cso(input: &str, output: &str, block_size: u32) -> Result<u32, String> {
    let block_size = if block_size == 0 { 2048 } else { block_size.max(512).min(64 * 1024) };
    let mut src = std::fs::File::open(input).map_err(|e| format!("CSO open {input}: {e}"))?;
    let src_len = src.metadata().map(|m| m.len()).unwrap_or(0);
    if src_len == 0 { return Err("CSO: empty input".to_string()); }
    if src_len > 32u64 * 1024 * 1024 * 1024 { return Err("CSO: input too large (32GiB cap)".to_string()); }
    extract_progress::reset(src_len);

    let block_count = (src_len + block_size as u64 - 1) / block_size as u64;
    let header_size = CSO_HEADER_SIZE + ((block_count + 1) as usize) * 4;
    let mut out = std::io::BufWriter::with_capacity(256 * 1024, std::fs::File::create(output).map_err(|e| format!("CSO create {output}: {e}"))?);
    let mut hdr = Vec::with_capacity(header_size);
    hdr.extend_from_slice(CSO_MAGIC);
    hdr.extend_from_slice(&(header_size as u32).to_le_bytes());
    hdr.extend_from_slice(&src_len.to_le_bytes());
    hdr.extend_from_slice(&block_size.to_le_bytes());
    hdr.extend_from_slice(&0u32.to_le_bytes());
    hdr.resize(header_size, 0);
    out.write_all(&hdr).map_err(|e| format!("CSO header: {e}"))?;

    let mut index_offsets: Vec<u32> = Vec::with_capacity(block_count as usize);
    let mut data_pos: u64 = header_size as u64;
    for i in 0..block_count {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        let start = i * block_size as u64;
        let len = ((src_len - start) as usize).min(block_size as usize);
        let mut buf = vec![0u8; len];
        src.read_exact(&mut buf).map_err(|e| format!("CSO read: {e}"))?;
        let mut enc = ZlibEncoder::new(Vec::new(), Compression::default());
        enc.write_all(&buf).map_err(|e| format!("CSO deflate: {e}"))?;
        let compressed = enc.finish().map_err(|e| format!("CSO deflate: {e}"))?;
        let (flag, payload) = if compressed.len() < len {
            (1u32, compressed)
        } else {
            (0u32, buf)
        };
        if data_pos + payload.len() as u64 >= (1u64 << 31) {
            return Err("CSO: data area exceeds 2 GiB (format limit)".to_string());
        }
        index_offsets.push(((data_pos as u32) << 1) | flag);
        out.write_all(&payload).map_err(|e| format!("CSO data: {e}"))?;
        data_pos += payload.len() as u64;
        extract_progress::add_bytes(len as u64);
    }
    if data_pos >= (1u64 << 31) {
        return Err("CSO: data area exceeds 2 GiB (format limit)".to_string());
    }
    index_offsets.push((data_pos as u32) << 1);

    out.seek(SeekFrom::Start(CSO_HEADER_SIZE as u64)).map_err(|e| format!("CSO seek: {e}"))?;
    for off in &index_offsets {
        out.write_all(&off.to_le_bytes()).map_err(|e| format!("CSO index write: {e}"))?;
    }
    out.flush().map_err(|e| format!("CSO flush: {e}"))?;
    Ok(block_count as u32)
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_CsoCore_csoToIso(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jboolean {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o);
    match guarded(move || cso_to_iso(&inp, &out)) {
        Ok(_) => JNI_TRUE,
        Err(er) => { let _ = e.throw_new("java/io/IOException", format!("cso: {er}")); JNI_FALSE }
    }
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_CsoCore_isoToCso(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, bs: JString) -> jboolean {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let bs_str = s(&mut e, &bs);
    let block_size: u32 = bs_str.parse().unwrap_or(2048);
    match guarded(move || iso_to_cso(&inp, &out, block_size)) {
        Ok(_) => JNI_TRUE,
        Err(er) => { let _ = e.throw_new("java/io/IOException", format!("cso: {er}")); JNI_FALSE }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_CsoCore_csoProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_CsoCore_csoProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_CsoCore_csoProgressFileCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_CsoCore_csoProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_CsoCore_csoProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_CsoCore_csoCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> std::path::PathBuf {
        std::env::temp_dir().join(format!("uu_cso_{}_{}", std::process::id(), tag))
    }

    #[test]
    fn iso_to_cso_round_trip() {
        let dir = tmp("rt");
        std::fs::create_dir_all(&dir).unwrap();
        let data: Vec<u8> = (0..(2048 * 5 + 100u64) as u32).map(|i| (i.wrapping_mul(31) % 251) as u8).collect();
        let iso = dir.join("in.iso");
        std::fs::write(&iso, &data).unwrap();
        let cso = dir.join("out.cso");
        iso_to_cso(iso.to_str().unwrap(), cso.to_str().unwrap(), 2048).expect("iso→cso");
        let back = dir.join("back.iso");
        cso_to_iso(cso.to_str().unwrap(), back.to_str().unwrap()).expect("cso→iso");
        assert_eq!(std::fs::read(&back).unwrap(), data, "round-trip must reconstruct the ISO");
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn incompressible_data_stored_raw() {
        let dir = tmp("raw");
        std::fs::create_dir_all(&dir).unwrap();
        use std::collections::hash_map::RandomState;
        use std::hash::{BuildHasher, Hasher};
        let mut data = vec![0u8; 2048 * 3];
        let s = RandomState::new();
        for chunk in data.chunks_mut(8) {
            let mut h = s.build_hasher();
            h.write_u64(chunk.len() as u64);
            chunk.copy_from_slice(&h.finish().to_le_bytes());
        }
        let iso = dir.join("r.iso");
        std::fs::write(&iso, &data).unwrap();
        let cso = dir.join("r.cso");
        iso_to_cso(iso.to_str().unwrap(), cso.to_str().unwrap(), 2048).expect("encode");
        let back = dir.join("rb.iso");
        cso_to_iso(cso.to_str().unwrap(), back.to_str().unwrap()).expect("decode");
        assert_eq!(std::fs::read(&back).unwrap(), data);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn bad_magic_rejected() {
        let dir = tmp("bad");
        std::fs::create_dir_all(&dir).unwrap();
        let p = dir.join("not.cso");
        std::fs::write(&p, b"NOTCISOthis is not a ciso file at all").unwrap();
        assert!(cso_to_iso(p.to_str().unwrap(), dir.join("o.iso").to_str().unwrap()).is_err());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn decreasing_block_index_rejected() {
        let dir = tmp("decr");
        std::fs::create_dir_all(&dir).unwrap();
        // Standard format: (absolute_position << 1) | flag.
        // Block 0 at 36, block 1 at 24 (decreasing!).
        let mut blob = Vec::new();
        blob.extend_from_slice(b"CISO");
        blob.extend_from_slice(&(24u32 + 3 * 4).to_le_bytes());
        blob.extend_from_slice(&1024u64.to_le_bytes());
        blob.extend_from_slice(&2048u32.to_le_bytes());
        blob.extend_from_slice(&0u32.to_le_bytes());
        blob.extend_from_slice(&((36u32) << 1).to_le_bytes());
        blob.extend_from_slice(&((24u32) << 1).to_le_bytes());
        blob.extend_from_slice(&((40u32) << 1).to_le_bytes());
        let p = dir.join("decr.cso");
        std::fs::write(&p, &blob).unwrap();
        assert!(cso_to_iso(p.to_str().unwrap(), dir.join("o.iso").to_str().unwrap()).is_err(),
            "decreasing offsets must fail");
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn output_bounded_by_declared_total() {
        let dir = tmp("bounded");
        std::fs::create_dir_all(&dir).unwrap();
        // Standard format. header_size=36, data starts at byte 36.
        // total_bytes=3000 → code reads all 3 index entries (2 blocks),
        // but actual data (2×2048=4096) exceeds total_bytes → must fail.
        let mut blob = Vec::new();
        blob.extend_from_slice(b"CISO");
        blob.extend_from_slice(&(24u32 + 3 * 4).to_le_bytes());
        blob.extend_from_slice(&3000u64.to_le_bytes());
        blob.extend_from_slice(&2048u32.to_le_bytes());
        blob.extend_from_slice(&0u32.to_le_bytes());
        blob.extend_from_slice(&((36u32) << 1).to_le_bytes());
        blob.extend_from_slice(&(((36 + 2048) as u32) << 1).to_le_bytes());
        blob.extend_from_slice(&(((36 + 4096) as u32) << 1).to_le_bytes());
        blob.extend_from_slice(&[0xAA; 2048]);
        blob.extend_from_slice(&[0xBB; 2048]);
        let p = dir.join("b.cso");
        std::fs::write(&p, &blob).unwrap();
        // total(3000) < produced(4096) → writes ≤3000 then fails the length check.
        let r = cso_to_iso(p.to_str().unwrap(), dir.join("o.iso").to_str().unwrap());
        assert!(r.is_err(), "output beyond declared total must fail, got {r:?}");
        let got = std::fs::read(dir.join("o.iso")).unwrap_or_default();
        assert!(got.len() <= 3000, "written <= declared total, got {}", got.len());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn compressed_block_over_blocksize_rejected() {
        use flate2::write::ZlibEncoder;
        use flate2::Compression;
        use std::io::Write as _;
        let dir = tmp("bigblk");
        std::fs::create_dir_all(&dir).unwrap();
        let mut enc = ZlibEncoder::new(Vec::new(), Compression::default());
        enc.write_all(&[0x5Au8; 4096]).unwrap();
        let comp = enc.finish().unwrap();
        let data_start = 24 + 3 * 4;
        let mut blob = Vec::new();
        blob.extend_from_slice(b"CISO");
        blob.extend_from_slice(&(24u32 + 3 * 4).to_le_bytes());
        blob.extend_from_slice(&4096u64.to_le_bytes());
        blob.extend_from_slice(&2048u32.to_le_bytes());
        blob.extend_from_slice(&0u32.to_le_bytes());
        blob.extend_from_slice(&((data_start as u32) << 1 | 1).to_le_bytes());
        blob.extend_from_slice(&(((data_start + comp.len()) as u32) << 1).to_le_bytes());
        blob.extend_from_slice(&(((data_start + comp.len() + 2048) as u32) << 1).to_le_bytes());
        blob.extend_from_slice(&comp);
        blob.extend_from_slice(&[0x5Au8; 2048]);
        let p = dir.join("big.cso");
        std::fs::write(&p, &blob).unwrap();
        let r = cso_to_iso(p.to_str().unwrap(), dir.join("o.iso").to_str().unwrap());
        assert!(r.is_err(), "block inflating past block_size must fail, got {r:?}");
        std::fs::remove_dir_all(&dir).ok();
    }
}
