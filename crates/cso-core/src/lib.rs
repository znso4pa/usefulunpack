//! PSP CISO (CSO) compressed ISO image support.
//!
//! Layout: 24-byte header (`CISO` magic, header_size, total_bytes, block_size)
//! followed by an index table of `header_size/4 - 6` little-endian u32s. Each
//! index's bit0 is the compression flag (1 = zlib, 0 = raw block) and the
//! remaining 31 bits are the byte offset into the data area (>> 1). Data area
//! holds one entry per block; the final index is the data-area end sentinel.
//!
//! ISO→CSO compresses every block (falling back to raw when zlib wouldn't
//! shrink); CSO→ISO reconstructs the original image. Both directions stream
//! block-by-block with byte progress and cancellation.

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
const MAX_INDEX_ENTRIES: u64 = 16_000_000; // ~32GB at 2048-byte blocks

fn guarded<T: Send + 'static>(f: impl FnOnce() -> Result<T, String> + Send + 'static) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|panic| {
        let msg = panic.downcast_ref::<&str>().copied()
            .or_else(|| panic.downcast_ref::<String>().map(|s| s.as_str()))
            .unwrap_or("unknown panic");
        Err(format!("panic: {msg}"))
    })
}

/// Parses a CSO header + index table, returning (block_size, total_bytes, index).
fn read_cso_index(input: &str) -> Result<(u32, u64, Vec<u32>), String> {
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
    if header_size < CSO_HEADER_SIZE || header_size > 64 * 1024 * 1024 {
        return Err(format!("CSO: bad header size {header_size}"));
    }
    let count = header_size / 4 - 6;
    if count as u64 > MAX_INDEX_ENTRIES {
        return Err(format!("CSO: too many blocks {count}"));
    }
    let mut index = vec![0u32; count];
    let mut buf = vec![0u8; count * 4];
    f.read_exact(&mut buf).map_err(|e| format!("CSO index: {e}"))?;
    for (i, chunk) in buf.chunks_exact(4).enumerate() {
        index[i] = u32::from_le_bytes(chunk.try_into().unwrap());
    }
    Ok((block_size, total_bytes, index))
}

/// Decodes one CSO block into `out` (may be shorter than block_size for the last block).
fn read_cso_block(f: &mut std::fs::File, index: u32, next_index: u32, block_size: usize) -> Result<Vec<u8>, String> {
    let compressed = index & 1 == 1;
    let start = (index >> 1) as u64;
    let end = (next_index >> 1) as u64;
    if end < start {
        return Err("CSO: corrupt block index (offsets decrease)".to_string());
    }
    let len = (end - start) as usize;
    if len > 64 * 1024 * 1024 {
        return Err(format!("CSO: block too large ({len} bytes)"));
    }
    f.seek(SeekFrom::Start(start)).map_err(|e| format!("CSO seek: {e}"))?;
    let mut data = vec![0u8; len];
    f.read_exact(&mut data).map_err(|e| format!("CSO read block: {e}"))?;
    if compressed {
        // Drain the whole inflated stream into an exactly-block_size buffer,
        // erroring if a crafted block inflates beyond it (a truncated read
        // would silently drop data).
        let mut dec = ZlibDecoder::new(&data[..]);
        let mut out = vec![0u8; block_size];
        let mut filled = 0usize;
        loop {
            let n = dec.read(&mut out[filled..]).map_err(|e| format!("CSO inflate: {e}"))?;
            if n == 0 { break; }
            filled += n;
            if filled >= block_size {
                // One more read to prove the stream ends at/under block_size.
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
    let (block_size, total_bytes, index) = read_cso_index(input)?;
    let mut src = std::fs::File::open(input).map_err(|e| format!("CSO open {input}: {e}"))?;
    let mut out = std::fs::File::create(output).map_err(|e| format!("CSO create {output}: {e}"))?;
    extract_progress::reset(total_bytes);
    let block_count = index.len().saturating_sub(1);
    let mut written: u64 = 0;
    for i in 0..block_count {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        let b = read_cso_block(&mut src, index[i], index[i + 1], block_size as usize)?;
        // Bound the total output to the declared image size (a crafted CSO
        // can't expand past it and fill disk).
        if written + b.len() as u64 > total_bytes {
            return Err(format!("CSO: output exceeds declared size {total_bytes}"));
        }
        out.write_all(&b).map_err(|e| format!("CSO write: {e}"))?;
        written += b.len() as u64;
        extract_progress::add_bytes(b.len() as u64);
    }
    // If the declared size wasn't fully produced, the image is truncated.
    if written < total_bytes {
        return Err(format!("CSO: output {} bytes < declared {total_bytes}", written));
    }
    Ok(block_count as u32)
}

/// ISO → CSO: compresses an ISO image into a PSP CISO archive.
fn iso_to_cso(input: &str, output: &str, block_size: u32) -> Result<u32, String> {
    let block_size = if block_size == 0 { 2048 } else { block_size.max(512).min(64 * 1024) };
    let mut src = std::fs::File::open(input).map_err(|e| format!("CSO open {input}: {e}"))?;
    let src_len = src.metadata().map(|m| m.len()).unwrap_or(0);
    if src_len == 0 { return Err("CSO: empty input".to_string()); }
    if src_len > 32u64 * 1024 * 1024 * 1024 { return Err("CSO: input too large (32GiB cap)".to_string()); }
    extract_progress::reset(src_len);

    let block_count = (src_len + block_size as u64 - 1) / block_size as u64;
    // Index table: one entry per block + trailing sentinel = block_count+1.
    let header_size = CSO_HEADER_SIZE + ((block_count + 1) as usize) * 4;
    let mut out = std::fs::File::create(output).map_err(|e| format!("CSO create {output}: {e}"))?;
    let mut hdr = Vec::with_capacity(header_size);
    hdr.extend_from_slice(CSO_MAGIC);
    hdr.extend_from_slice(&(header_size as u32).to_le_bytes());
    hdr.extend_from_slice(&src_len.to_le_bytes());
    hdr.extend_from_slice(&block_size.to_le_bytes());
    hdr.extend_from_slice(&0u32.to_le_bytes()); // reserved
    hdr.resize(header_size, 0); // placeholder for index entries
    out.write_all(&hdr).map_err(|e| format!("CSO header: {e}"))?;

    let mut index_offsets: Vec<u32> = Vec::with_capacity(block_count as usize);
    let mut data_pos: u64 = header_size as u64;
    for i in 0..block_count {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        let start = i * block_size as u64;
        let len = ((src_len - start) as usize).min(block_size as usize);
        let mut buf = vec![0u8; len];
        src.read_exact(&mut buf).map_err(|e| format!("CSO read: {e}"))?;
        // Try zlib; keep it only if it actually shrinks.
        let mut enc = ZlibEncoder::new(Vec::new(), Compression::default());
        enc.write_all(&buf).map_err(|e| format!("CSO deflate: {e}"))?;
        let compressed = enc.finish().map_err(|e| format!("CSO deflate: {e}"))?;
        let (flag, payload) = if compressed.len() < len {
            (1u32, compressed)
        } else {
            (0u32, buf)
        };
        // The index stores byte offsets in 31 bits (bit0 = compression flag),
        // so the data area caps at 2 GiB — a format limit, not ours.
        if data_pos + payload.len() as u64 >= (1u64 << 31) {
            return Err("CSO: data area exceeds 2 GiB (format limit)".to_string());
        }
        index_offsets.push((data_pos as u32) << 1 | flag);
        out.write_all(&payload).map_err(|e| format!("CSO data: {e}"))?;
        data_pos += payload.len() as u64;
        extract_progress::add_bytes(len as u64);
    }
    // Sentinel: data end.
    if data_pos >= (1u64 << 31) {
        return Err("CSO: data area exceeds 2 GiB (format limit)".to_string());
    }
    index_offsets.push((data_pos as u32) << 1);

    // Rewrite the index table into the header (already reserved).
    out.seek(SeekFrom::Start(CSO_HEADER_SIZE as u64)).map_err(|e| format!("CSO seek: {e}"))?;
    for off in &index_offsets {
        out.write_all(&off.to_le_bytes()).map_err(|e| format!("CSO index write: {e}"))?;
    }
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
        // Random-ish data across many blocks so zlib kicks in.
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
        // Truly random bytes won't shrink under zlib → blocks stay raw.
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

    /// A CSO whose block index offsets decrease (end < start) must be rejected
    /// instead of underflowing into a huge allocation.
    #[test]
    fn decreasing_block_index_rejected() {
        let dir = tmp("decr");
        std::fs::create_dir_all(&dir).unwrap();
        // Build a minimal CSO header + 2 index entries where the 2nd offset is
        // smaller than the 1st.
        let mut blob = Vec::new();
        blob.extend_from_slice(b"CISO");
        blob.extend_from_slice(&(24u32 + 3 * 4).to_le_bytes()); // header_size
        blob.extend_from_slice(&1024u64.to_le_bytes()); // total_bytes
        blob.extend_from_slice(&2048u32.to_le_bytes()); // block_size
        blob.extend_from_slice(&0u32.to_le_bytes());
        blob.extend_from_slice(&(10u32 << 1).to_le_bytes()); // offset 10
        blob.extend_from_slice(&(5u32 << 1).to_le_bytes());  // offset 5 < 10!
        blob.extend_from_slice(&(12u32 << 1).to_le_bytes()); // sentinel
        let p = dir.join("decr.cso");
        std::fs::write(&p, &blob).unwrap();
        assert!(cso_to_iso(p.to_str().unwrap(), dir.join("o.iso").to_str().unwrap()).is_err(),
            "decreasing offsets must fail");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// Output is bounded by the declared total_bytes: an index pointing past
    /// the declared size can't write more than total_bytes to disk.
    #[test]
    fn output_bounded_by_declared_total() {
        let dir = tmp("bounded");
        std::fs::create_dir_all(&dir).unwrap();
        // header(24) + index(3 entries × 4 = 12) → data starts at 36.
        // total_bytes declares 2048 but the index maps TWO raw 2048-byte
        // blocks — the second block's data must not be written.
        let mut blob = Vec::new();
        blob.extend_from_slice(b"CISO");
        blob.extend_from_slice(&(24u32 + 3 * 4).to_le_bytes());
        blob.extend_from_slice(&2048u64.to_le_bytes()); // total = 1 block
        blob.extend_from_slice(&2048u32.to_le_bytes()); // block_size
        blob.extend_from_slice(&0u32.to_le_bytes());
        blob.extend_from_slice(&((36u32) << 1).to_le_bytes());      // block0 at 36
        blob.extend_from_slice(&(((36 + 2048) as u32) << 1).to_le_bytes()); // block1
        blob.extend_from_slice(&(((36 + 4096) as u32) << 1).to_le_bytes()); // sentinel
        blob.extend_from_slice(&[0xAA; 2048]);
        blob.extend_from_slice(&[0xBB; 2048]);
        let p = dir.join("b.cso");
        std::fs::write(&p, &blob).unwrap();
        // total(2048) < produced(4096) → writes 2048 then fails the length check.
        let r = cso_to_iso(p.to_str().unwrap(), dir.join("o.iso").to_str().unwrap());
        assert!(r.is_err(), "output beyond declared total must fail, got {r:?}");
        let got = std::fs::read(dir.join("o.iso")).unwrap_or_default();
        assert!(got.len() <= 2048, "written <= declared total, got {}", got.len());
        std::fs::remove_dir_all(&dir).ok();
    }

    /// A compressed block that inflates beyond block_size must be rejected
    /// (a single `read` would otherwise silently truncate the data).
    #[test]
    fn compressed_block_over_blocksize_rejected() {
        use flate2::write::ZlibEncoder;
        use flate2::Compression;
        use std::io::Write as _;
        let dir = tmp("bigblk");
        std::fs::create_dir_all(&dir).unwrap();
        // block_size = 2048; compress 4096 bytes → inflates past the block.
        let mut enc = ZlibEncoder::new(Vec::new(), Compression::default());
        enc.write_all(&[0x5Au8; 4096]).unwrap();
        let comp = enc.finish().unwrap();
        let data_start = 24 + 3 * 4; // header + 3 index entries
        let mut blob = Vec::new();
        blob.extend_from_slice(b"CISO");
        blob.extend_from_slice(&(24u32 + 3 * 4).to_le_bytes());
        blob.extend_from_slice(&4096u64.to_le_bytes()); // total = 4096 (2 blocks)
        blob.extend_from_slice(&2048u32.to_le_bytes()); // block_size = 2048
        blob.extend_from_slice(&0u32.to_le_bytes());
        blob.extend_from_slice(&((data_start as u32) << 1 | 1).to_le_bytes()); // block0 compressed
        blob.extend_from_slice(&(((data_start + comp.len()) as u32) << 1).to_le_bytes()); // block1 raw
        blob.extend_from_slice(&(((data_start + comp.len() + 2048) as u32) << 1).to_le_bytes()); // sentinel
        blob.extend_from_slice(&comp);
        blob.extend_from_slice(&[0x5Au8; 2048]);
        let p = dir.join("big.cso");
        std::fs::write(&p, &blob).unwrap();
        let r = cso_to_iso(p.to_str().unwrap(), dir.join("o.iso").to_str().unwrap());
        assert!(r.is_err(), "block inflating past block_size must fail, got {r:?}");
        std::fs::remove_dir_all(&dir).ok();
    }
}
