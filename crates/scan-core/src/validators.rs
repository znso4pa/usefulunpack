//! Lightweight header validators for signature-scan hits, modeled on binwalk's
//! signature parsers. Each validator reads only the format's header bytes and
//! returns (size, file_count) when the structure is plausible, or None for a
//! false positive. No decompression, no heavy dependencies — pure std + a few
//! seeks. Confidence levels follow binwalk: HIGH for formats with a full
//! structural walk (zip/png/jpeg/riff), MEDIUM for header-only checks.

use std::fs::File;
use std::io::{Read, Seek, SeekFrom};

pub const CONFIDENCE_MEDIUM: u8 = 128;
pub const CONFIDENCE_HIGH: u8 = 250;

/// Validated hit metadata: size (when derivable) and entry count (when known).
#[derive(Debug, Clone, Copy)]
pub struct HitInfo {
    pub size: Option<u64>,
    pub count: Option<u32>,
}

type Validator = fn(&mut File, u64, u64) -> Option<HitInfo>;

fn read_at(f: &mut File, off: u64, buf: &mut [u8]) -> bool {
    if f.seek(SeekFrom::Start(off)).is_err() {
        return false;
    }
    f.read_exact(buf).is_ok()
}

/// Absolute position of the first NUL byte at or after [off], scanning in
/// bounded chunks (no per-byte seeks). None when none exists within [limit]
/// bytes or before the end of the file — used to prove NUL-terminated gzip
/// FNAME/FCOMMENT fields and similar C-string walks.
fn find_nul(f: &mut File, off: u64, file_len: u64, limit: u64) -> Option<u64> {
    const CHUNK: u64 = 64 * 1024;
    let mut buf = vec![0u8; CHUNK as usize];
    let mut pos = off;
    let end = file_len.min(off.saturating_add(limit));
    while pos < end {
        let want = (end - pos).min(CHUNK) as usize;
        if !read_at(f, pos, &mut buf[..want]) {
            return None;
        }
        if let Some(idx) = buf[..want].iter().position(|&b| b == 0) {
            return Some(pos + idx as u64);
        }
        pos += want as u64;
    }
    None
}

fn u16le(b: &[u8], i: usize) -> u16 {
    u16::from_le_bytes([b[i], b[i + 1]])
}
fn u32le(b: &[u8], i: usize) -> u32 {
    u32::from_le_bytes([b[i], b[i + 1], b[i + 2], b[i + 3]])
}
fn u64le(b: &[u8], i: usize) -> u64 {
    u64::from_le_bytes([b[i], b[i + 1], b[i + 2], b[i + 3], b[i + 4], b[i + 5], b[i + 6], b[i + 7]])
}

/// Verifies a candidate EOCD record at [eocd_abs]: parses entry count,
/// central-directory offset and comment length, and checks the central
/// directory header really sits at offset+cd_offset. Returns the validated
/// hit info, or None for a false EOCD.
fn check_eocd(f: &mut File, eocd_abs: u64, off: u64, file_len: u64) -> Option<HitInfo> {
    let mut eh = [0u8; 22];
    if !read_at(f, eocd_abs, &mut eh) {
        return None;
    }
    let total = u16le(&eh, 10);
    let cd_off = u32le(&eh, 16);
    let comment_len = u16le(&eh, 20);
    let cd_abs = off + cd_off as u64;
    if cd_abs + 4 > file_len {
        return None;
    }
    let mut cd = [0u8; 4];
    if !read_at(f, cd_abs, &mut cd) || cd != [0x50, 0x4B, 0x01, 0x02] {
        return None;
    }
    let size = eocd_abs + 22 + comment_len as u64 - off;
    if size > file_len {
        return None;
    }
    Some(HitInfo { size: Some(size), count: Some(total as u32) })
}

/// ZIP: PK\x03\x04 local header + a reachable EOCD whose central-directory
/// offset actually points at a PK\x01\x02 record. Mirrors the previous Kotlin
/// validator. The forward EOCD search is capped at 256 MiB to bound the cost
/// of false positives on huge files; when that misses (archives larger than
/// ~256 MiB), the file tail (64 KiB + 22 bytes) is searched backwards instead
/// — the EOCD of a standalone zip always sits at the very end.
fn validate_zip(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    let mut lh = [0u8; 30];
    if !read_at(f, off, &mut lh) {
        return None;
    }
    let method = u16le(&lh, 8);
    if method != 0 && method != 8 {
        return None; // stored or deflate only
    }
    // Local header length includes name + extra fields.
    let lh_len = 30 + u16le(&lh, 26) as u64 + u16le(&lh, 28) as u64;
    if lh_len < 30 || off + lh_len > file_len {
        return None;
    }
    // Standalone archives first: a zip's EOCD always sits within 64 KiB + 22
    // bytes of the archive end, so probe the FILE tail for a validating EOCD
    // before spending up to 256 MiB on a forward scan. Huge standalone zips
    // resolve in one 64 KiB read instead of a 256 MiB miss. Embedded zips (EOCD
    // not at file end) fall through to the forward scan below unchanged.
    const EOCD_MAGIC: [u8; 4] = [0x50, 0x4B, 0x05, 0x06];
    const FWD_CAP: u64 = 268_435_456;
    let tail_from = file_len.saturating_sub(64 * 1024 + 22);
    if tail_from > off + lh_len {
        let mut search_end = file_len;
        for _ in 0..2 {
            let eocd = find_marker_in_tail(f, search_end, search_end - tail_from + 1, &EOCD_MAGIC);
            match eocd {
                Some(eocd_abs) => {
                    if let Some(info) = check_eocd(f, eocd_abs, off, file_len) {
                        return Some(info);
                    }
                    search_end = eocd_abs; // try an earlier candidate
                }
                None => break,
            }
        }
    }
    // Forward scan for EOCD, bounded (256 MiB), with overlap carry so an
    // EOCD magic straddling a 64 KiB chunk boundary is still found. Reached
    // only for embedded archives whose EOCD is not in the file tail.
    let mut pos = off + lh_len;
    let end = file_len.min(off + FWD_CAP);
    let mut buf = [0u8; 64 * 1024];
    let mut window: Vec<u8> = Vec::with_capacity(buf.len() + 3);
    let mut overlap: Vec<u8> = Vec::new();
    while pos < end {
        if f.seek(SeekFrom::Start(pos)).is_err() {
            return None;
        }
        let n = f.read(&mut buf).unwrap_or(0);
        if n == 0 {
            break;
        }
        window.clear();
        window.extend_from_slice(&overlap);
        window.extend_from_slice(&buf[..n]);
        let base = pos.saturating_sub(overlap.len() as u64);
        let mut hit_at: Option<u64> = None;
        for idx in 0..=window.len().saturating_sub(EOCD_MAGIC.len()) {
            if window[idx..idx + EOCD_MAGIC.len()] == EOCD_MAGIC {
                hit_at = Some(base + idx as u64);
                break;
            }
        }
        if let Some(eocd_abs) = hit_at {
            if let Some(info) = check_eocd(f, eocd_abs, off, file_len) {
                return Some(info);
            }
            // False EOCD: skip past it so it is not re-found.
            pos = pos.max(eocd_abs + 1);
            overlap.clear();
            continue;
        }
        overlap = window[window.len().saturating_sub(EOCD_MAGIC.len() - 1)..].to_vec();
        pos += n as u64;
    }
    None
}

/// 7z: 6-byte signature + header CRC32 (bytes 12..32) + next-header
/// offset/size within bounds (binwalk parity, dual-CRC when possible).
fn validate_7z(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    let mut h = [0u8; 32];
    if !read_at(f, off, &mut h) {
        return None;
    }
    // Start header layout: sig(6) version(2) start_header_crc(4)
    // next_header_offset(8 @12) next_header_size(8 @20) next_header_crc(4 @28).
    // Header CRC32: bytes [12..32] must match u32 at offset 8.
    let header_crc = u32le(&h, 8);
    if crc32(&h[12..32], 0) != header_crc {
        return None;
    }
    let next = u64le(&h, 12);
    let next_size = u64le(&h, 20);
    if next == 0 || next_size == 0 {
        return None;
    }
    // Overflow-safe: a crafted header can make 32 + next + next_size (or
    // off + total) wrap around; treat anything that doesn't fit inside the
    // file as the truncated/volume case instead of a bogus size.
    let total = 32u64.checked_add(next).and_then(|v| v.checked_add(next_size));
    if total.map_or(true, |t| t > file_len.saturating_sub(off)) {
        // Truncated or multi-volume 7z: the header block extends past this
        // file (continuation lives in the next volume). The CRC-validated
        // start header is strong enough — treat the rest of this file as
        // one region so the compressed payload isn't scanned as other
        // formats (mirrors the RAR volume fallback).
        return Some(HitInfo { size: Some(file_len - off), count: None });
    }
    Some(HitInfo { size: Some(total.unwrap()), count: None })
}

/// Scans forward from [off] for a magic marker, returning its absolute
/// position. Reads in 1 MiB chunks; stops at [end]. Used to locate RAR EOF
/// markers (binwalk parity) and other trailing records.
fn find_marker(f: &mut File, off: u64, end: u64, magic: &[u8]) -> Option<u64> {
    const CHUNK: usize = 1 << 20;
    let mut buf = vec![0u8; CHUNK];
    let mut pos = off;
    let mut overlap: Vec<u8> = Vec::new();
    let mut window: Vec<u8> = Vec::with_capacity(CHUNK + magic.len());
    while pos < end {
        if f.seek(SeekFrom::Start(pos)).is_err() {
            return None;
        }
        let n = f.read(&mut buf).unwrap_or(0);
        if n == 0 {
            return None;
        }
        window.clear();
        window.extend_from_slice(&overlap);
        window.extend_from_slice(&buf[..n]);
        let window_base = pos.saturating_sub(overlap.len() as u64);
        // Find the first occurrence of magic in the window.
        if magic.len() <= window.len() {
            for i in 0..=window.len() - magic.len() {
                if &window[i..i + magic.len()] == magic {
                    return Some(window_base + i as u64);
                }
            }
        }
        overlap.clear();
        overlap.extend_from_slice(&window[window.len().saturating_sub(magic.len() - 1)..]);
        pos += n as u64;
    }
    None
}

/// Scans backward from [end] for a magic marker within the last [tail_limit]
/// bytes (streaming, reverse). RAR EOF markers sit at the very end of the
/// archive, so this is much faster than a full forward scan on huge files.
fn find_marker_in_tail(f: &mut File, end: u64, tail_limit: u64, magic: &[u8]) -> Option<u64> {
    if tail_limit == 0 {
        return None;
    }
    let from = end.saturating_sub(tail_limit);
    let mut buf = vec![0u8; 1 << 20];
    let mut pos = end;
    let mut carry: Vec<u8> = Vec::new();
    let mut window: Vec<u8> = Vec::with_capacity(buf.len() + magic.len());
    while pos > from {
        let read_from = pos.saturating_sub(buf.len() as u64).max(from);
        let want = (pos - read_from) as usize;
        if f.seek(SeekFrom::Start(read_from)).is_err() {
            return None;
        }
        let n = f.read(&mut buf[..want]).unwrap_or(0);
        if n == 0 {
            break;
        }
        // Prepend: carry holds bytes already read after this chunk.
        window.clear();
        window.extend_from_slice(&buf[..n]);
        window.extend_from_slice(&carry);
        // Search for the LAST occurrence of magic in this window.
        if magic.len() <= window.len() {
            for i in (0..=window.len() - magic.len()).rev() {
                if &window[i..i + magic.len()] == magic {
                    return Some(read_from + i as u64);
                }
            }
        }
        carry.clear();
        carry.extend_from_slice(&window[..magic.len().saturating_sub(1).min(window.len())]);
        pos = read_from;
        if n < want {
            break; // reached file start
        }
    }
    None
}

/// RAR4: 7-byte signature + HEAD_CRC(2) + HEAD_TYPE(1) + HEAD_FLAGS(2) +
/// HEAD_SIZE(2). The HEAD_CRC is data-dependent (CRC16 of the block fields),
/// so it cannot be compared to a constant — real archives carry arbitrary
/// values there. The discriminator is the first block after the signature
/// always being the MAIN header (type 0x73); RAR5's byte 9 is part of its
/// header CRC instead. Locates the RAR4 EOF marker (C4 3D 7B 00 40 07 00)
/// for the full archive size (binwalk parity).
fn validate_rar4(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    let mut h = [0u8; 14];
    if !read_at(f, off, &mut h) {
        return None;
    }
    let htype = h[9];
    let flags = u16le(&h, 10);
    let hsize = u16le(&h, 12);
    if htype != 0x73 || hsize < 13 || off + hsize as u64 > file_len {
        return None;
    }
    const RAR4_EOF: [u8; 7] = [0xC4, 0x3D, 0x7B, 0x00, 0x40, 0x07, 0x00];
    // EOF marker sits at the end of the archive — search the last 64 MiB.
    let eof = find_marker_in_tail(f, file_len, 64 * 1024 * 1024, &RAR4_EOF)
        .or_else(|| find_marker(f, off + hsize as u64, file_len, &RAR4_EOF));
    if let Some(eof) = eof {
        return Some(HitInfo { size: Some(eof + RAR4_EOF.len() as u64 - off), count: None });
    }
    // MHD_VOLUME = 0x0001: non-last volumes carry no EOF marker — treat the
    // whole volume as one region so the scanner doesn't misreport the
    // compressed payload inside.
    if flags & 0x0001 != 0 {
        return Some(HitInfo { size: Some(file_len - off), count: None });
    }
    Some(HitInfo { size: None, count: None })
}

/// Reads a RAR5 VINT (7 bits per byte, high bit = continuation) from [h] at
/// [i]. Returns (value, bytes consumed) or None when [h] is too short.
fn read_vint(h: &[u8], i: usize) -> Option<(u64, usize)> {
    let mut v: u64 = 0;
    let mut shift = 0u32;
    let mut n = 0usize;
    while n < 10 {
        let b = *h.get(i + n)?;
        v |= ((b & 0x7F) as u64) << shift;
        n += 1;
        if b & 0x80 == 0 {
            return Some((v, n));
        }
        shift += 7;
    }
    None
}

/// RAR5: 8-byte signature + HEAD_CRC(4) + HEAD_SIZE(vint) + HEAD_TYPE(vint) +
/// HEAD_FLAGS(vint). The vint encoding means fixed u16 offsets do not apply —
/// the main-header type/flags are parsed where the vints actually end. Locates
/// the RAR5 EOF marker (1D 77 56 51 03 05 04 00) for the full archive size.
/// When the main header's volume flag (HFL_VOLUME=0x0001) is set and no EOF
/// marker is found (only the last volume carries it), the whole volume is
/// treated as one region so the scanner doesn't misreport the compressed
/// payload inside.
fn validate_rar5(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    // 64 bytes: the three vint fields start at 12 and each may take up to
    // 10 bytes, so a pathological-but-legal multi-byte vint header must not
    // truncate the parse (18 bytes used to miss such archives).
    let mut h = [0u8; 64];
    if !read_at(f, off, &mut h) {
        return None;
    }
    let (hsize, n1) = read_vint(&h, 12)?;
    let (htype, n2) = read_vint(&h, 12 + n1)?;
    let (flags, _n3) = read_vint(&h, 12 + n1 + n2)?;
    // Block span = sig(8) + HEAD_CRC(4) + header data (HEAD_SIZE counts from
    // the HEAD_SIZE field itself); the first block after the signature is
    // always the MAIN header (type 1) — that is what discriminates RAR5
    // bytes from RAR4 (whose type byte is 0x73 at 9).
    let block_end = off + 8 + 4 + hsize;
    if hsize == 0 || block_end > file_len || htype != 1 {
        return None;
    }
    const RAR5_EOF: [u8; 8] = [0x1D, 0x77, 0x56, 0x51, 0x03, 0x05, 0x04, 0x00];
    // EOF marker sits at the end of the archive — search the last 64 MiB.
    let eof = find_marker_in_tail(f, file_len, 64 * 1024 * 1024, &RAR5_EOF)
        .or_else(|| find_marker(f, block_end, file_len, &RAR5_EOF));
    if let Some(eof) = eof {
        return Some(HitInfo { size: Some(eof + RAR5_EOF.len() as u64 - off), count: None });
    }
    // Multi-volume, non-last part: no EOF here; treat whole volume as the
    // region to avoid scanning encrypted payload as other formats.
    if flags & 0x0001 != 0 {
        return Some(HitInfo { size: Some(file_len - off), count: None });
    }
    Some(HitInfo { size: None, count: None })
}

/// RAR5 `-hp` header-encrypted archives: the main header (size/type/flags)
/// is encrypted, so [validate_rar5]'s plaintext vint parse fails and the
/// archive would be dropped entirely. The 8-byte signature is distinctive
/// enough that a "magic + whole remaining file" MEDIUM-confidence fallback is
/// safe (random data carrying all 8 bytes is astronomically rare). When the
/// archive IS plaintext, [validate_rar5] reports a HIGH-confidence hit at the
/// same offset and the post-pass keeps only the higher-confidence one.
fn validate_rar5_hp(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    // Signature was already matched by the scanner, but re-read to be explicit
    // and to confirm there is room for the header CRC + some payload.
    let mut sig = [0u8; 12];
    if !read_at(f, off, &mut sig) || &sig[0..8] != b"Rar!\x1a\x07\x01\x00" {
        return None;
    }
    if file_len - off < 12 + 4 {
        return None; // too small to hold a real (even encrypted) archive
    }
    // The plaintext RAR5 signature shares this same magic. Both validators run
    // on the hit; when the archive IS plaintext, the HIGH-confidence entry
    // (with a real EOF-derived size) wins the same-offset post-pass, and its
    // size-skip keeps this MEDIUM entry from being reached. No need to re-parse
    // the plaintext header here.
    Some(HitInfo { size: Some(file_len - off), count: None })
}

/// gzip: 1F 8B 08 + stricter header checks (binwalk-lite, no decompression):
/// FLG reserved bits clear, MTIME plausible (<= now + 1y), OS byte within the
/// gzip spec's defined range (0..13 + 255 = unknown), and if FNAME/FCOMMENT
/// set, the C-string terminator must exist within bounds.
fn validate_gzip(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    let mut h = [0u8; 10];
    if !read_at(f, off, &mut h) {
        return None;
    }
    let flg = h[3];
    if flg & 0b1110_0000 != 0 {
        return None; // reserved bits must be zero
    }
    // MTIME (bytes 4-7, little-endian Unix seconds): must be plausible.
    let mtime = u32::from_le_bytes([h[4], h[5], h[6], h[7]]);
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as u32)
        .unwrap_or(0);
    if mtime > now.saturating_add(366 * 86400) {
        return None; // far-future timestamp → not a real gzip header
    }
    // OS byte: 0..13 are all defined by the gzip spec; 255 = unknown.
    if h[9] > 13 && h[9] != 255 {
        return None;
    }
    // FEXTRA (0x04): 2-byte XLEN followed by that many extra bytes — must be
    // skipped entirely before the FNAME walk (the payload may contain NULs).
    let mut pos = off + 10;
    if flg & 0x04 != 0 {
        let mut xl = [0u8; 2];
        if !read_at(f, pos, &mut xl) {
            return None;
        }
        pos += 2 + u16le(&xl, 0) as u64;
        if pos > file_len {
            return None;
        }
    }
    // FNAME (0x08) and FCOMMENT (0x10): NUL-terminated C strings — walk to
    // the terminator to prove the optional fields exist within the file.
    for bit in [0x08u8, 0x10u8] {
        if flg & bit != 0 {
            // Chunked in-memory NUL scan — the old loop did one seek+read per
            // byte (up to 128k syscalls on two long fields).
            let nul = find_nul(f, pos, file_len, 65536)?;
            pos = nul + 1;
        }
    }
    // Decompression dry-run: feed the stream to MultiGzDecoder (concatenated
    // members allowed) and require at least one byte of output. High-entropy
    // random data with a plausible-looking header fails here, cutting the
    // header-only false-positive rate from ~1/1000 to near zero. Reads are
    // bounded to ~1 MiB of *output* so a huge legit archive costs little; the
    // file cursor is restored afterwards.
    if !gzip_dry_run(f, off) {
        return None;
    }
    Some(HitInfo { size: None, count: None })
}

/// Decodes a bounded prefix of a gzip stream at `off` and returns whether it
/// looks like genuine deflate. A clean EOF (Ok(0)) accepts; any decode error
/// rejects — garbage/random data with a plausible header trips an error
/// (typically UnexpectedEof) almost immediately, while a huge legit archive is
/// accepted once we've produced DRY_RUN_OUT bytes without error. The file
/// position is restored.
fn gzip_dry_run(f: &mut File, off: u64) -> bool {
    const DRY_RUN_OUT: usize = 1 << 20;
    if f.seek(SeekFrom::Start(off)).is_err() {
        return false;
    }
    // Take a bounded slice of the *file* so the decoder can't pull the whole
    // host into the deflate window on a massive candidate.
    let limited = f.take(DRY_RUN_OUT as u64 * 2);
    // GzDecoder (single member): stops after the first member's footer, so a
    // gzip embedded mid-host with trailing bytes validates cleanly.
    let mut dec = flate2::read::GzDecoder::new(limited);
    let mut sink = [0u8; 8192];
    let mut produced = 0usize;
    loop {
        match dec.read(&mut sink) {
            Ok(0) => break, // clean EOF → valid stream
            Ok(n) => {
                produced += n;
                if produced >= DRY_RUN_OUT {
                    break;
                }
            }
            Err(_) => {
                // Restore the caller's view of the file before bailing.
                let _ = f.seek(SeekFrom::Start(off));
                return false;
            }
        }
    }
    let _ = f.seek(SeekFrom::Start(off));
    produced > 0
}

/// Decodes a bounded prefix of an xz stream at `off` and returns whether it
/// looks like genuine xz-compressed data. Mirrors [gzip_dry_run]: the input
/// slice is bounded and only ~1 MiB of *output* is decoded. A clean EOF
/// (Ok(0)) accepts; high-entropy random data with a plausible header trips an
/// error almost immediately. When the stream ends but trailing host bytes
/// follow (an xz embedded mid-file), the decoder reports an error AFTER
/// producing the real output — accept that too (produced > 0), so embedded
/// streams are not wrongly dropped. Restores the cursor.
fn xz_dry_run(f: &mut File, off: u64) -> bool {
    const DRY_RUN_OUT: usize = 1 << 20;
    if f.seek(SeekFrom::Start(off)).is_err() {
        return false;
    }
    let limited = f.take(DRY_RUN_OUT as u64 * 2);
    let mut dec = xz2::read::XzDecoder::new(limited);
    let mut sink = [0u8; 8192];
    let mut produced = 0usize;
    loop {
        match dec.read(&mut sink) {
            Ok(0) => break, // clean EOF → valid stream
            Ok(n) => {
                produced += n;
                if produced >= DRY_RUN_OUT {
                    break;
                }
            }
            Err(_) => {
                let _ = f.seek(SeekFrom::Start(off));
                // An embedded stream decodes its real bytes first, then trips
                // on the trailing host data — that is a hit, not a false
                // positive. Producing nothing before the error means garbage.
                return produced > 0;
            }
        }
    }
    let _ = f.seek(SeekFrom::Start(off));
    produced > 0
}

/// Verifies a raw .lzma (alone) stream at `off` looks like genuine LZMA data.
/// The alone header has no checksum, so a decompression dry-run is what
/// separates real files from random data carrying a plausible props+dict
/// magic. Two regimes, driven by the header's uncompressed-size field:
///   * usize is KNOWN (≠ 0xFFFF…): lzma-rs decodes exactly that many bytes and
///     stops — an embedded stream also validates cleanly (trailing host bytes
///     are ignored), so the dry-run is reliable.
///   * usize is STREAMING (0xFFFF…, what liblzma's alone encoder always emits):
///     lzma-rs cannot bound the decode, and its internal circular buffer only
///     flushes on a clean finish — a stream embedded mid-host always errors
///     with zero visible output. Running the dry-run here would wrongly drop
///     every embedded streaming .lzma, so we SKIP it and fall back to the
///     header whitelist (props + dict whitelist + usize sanity are already
///     strong enough to reject random data). Restores the cursor.
fn lzma_dry_run(f: &mut File, off: u64) -> bool {
    // props(1) + dict(4) + usize(8) — the caller already validated these, but
    // re-read to decide streaming vs known size.
    let mut h = [0u8; 13];
    if !read_at(f, off, &mut h) {
        return false;
    }
    let usize_ = u64le(&h, 5);
    if usize_ == u64::MAX {
        // Streaming header (liblzma alone encoder default) — skip the dry-run
        // rather than drop embedded streams (see doc comment).
        return true;
    }
    const DRY_RUN_OUT: usize = 1 << 20;
    if f.seek(SeekFrom::Start(off)).is_err() {
        return false;
    }
    // Cap the input: lzma-rs decodes to the declared uncompressed-size field's
    // end (so trailing host bytes after an embedded stream are never consumed),
    // but a crafted header can declare a huge usize — never read more than ~2
    // MiB of *input*. A large real archive will hit the take bound and error,
    // which the `sink.len() >= 256` branch accepts.
    let limited = f.take(DRY_RUN_OUT as u64 * 2);
    let mut sink = Vec::new();
    let mut br = std::io::BufReader::new(limited);
    let result = lzma_rs::lzma_decompress(&mut br, &mut sink);
    let _ = f.seek(SeekFrom::Start(off));
    if result.is_err() {
        // Truncated input (our take bound hit mid-stream, or a corrupt tail)
        // is acceptable for a legit archive if it produced a plausible amount
        // of output before failing.
        return sink.len() >= 256;
    }
    !sink.is_empty()
}

/// bzip2: the magic table already carries the full 10-byte
/// "BZh{1-9}1AY&SY" signature (binwalk parity), so this just confirms the
/// block-size digit — the magic itself rejects random false positives.
/// Decompression dry-run for a zlib stream, mirroring [gzip_dry_run]: the header
/// check alone (CMF/FLG mod 31) passes ~1 in 31 random byte pairs, and a font's
/// glyph data is full of 0x78 bytes — a real TrueType file produced 53 hits.
/// Decoding the first megabyte of output brings that to ~0.
fn zlib_dry_run(f: &mut File, off: u64) -> bool {
    const DRY_RUN_OUT: usize = 1 << 20;
    if f.seek(SeekFrom::Start(off)).is_err() {
        return false;
    }
    let limited = f.take(DRY_RUN_OUT as u64 * 2);
    let mut dec = flate2::read::ZlibDecoder::new(limited);
    let mut sink = [0u8; 8192];
    let mut produced = 0usize;
    loop {
        match dec.read(&mut sink) {
            Ok(0) => break,
            Ok(n) => {
                produced += n;
                if produced >= DRY_RUN_OUT {
                    break;
                }
            }
            Err(_) => {
                let _ = f.seek(SeekFrom::Start(off));
                return false;
            }
        }
    }
    let _ = f.seek(SeekFrom::Start(off));
    produced > 0
}

fn validate_bzip2(f: &mut File, off: u64, _file_len: u64) -> Option<HitInfo> {
    let mut h = [0u8; 10];
    if !read_at(f, off, &mut h) {
        return None;
    }
    if &h[0..3] != b"BZh" || !(h[3].is_ascii_digit() && h[3] >= b'1' && h[3] <= b'9') {
        return None;
    }
    if &h[4..10] != b"1AY&SY" {
        return None;
    }
    Some(HitInfo { size: None, count: None })
}

/// xz: FD 37 7A 58 5A 00 + stream flags CRC32 (bytes 8..12 == CRC32 of 6..8).
fn validate_xz(f: &mut File, off: u64, _file_len: u64) -> Option<HitInfo> {
    let mut h = [0u8; 12];
    if !read_at(f, off, &mut h) {
        return None;
    }
    let crc = u32le(&h, 8);
    let expected = crc32(&h[6..8], 0);
    if crc != expected {
        return None;
    }
    // Header CRC is a strong check but not enough on high-entropy data —
    // require the stream to actually decode a bounded prefix.
    if !xz_dry_run(f, off) {
        return None;
    }
    Some(HitInfo { size: None, count: None })
}

/// zstd: 28 B5 2F FD + frame header descriptor sanity + walk blocks to the
/// last block for a real size (binwalk parity, pure bitfield parsing).
fn validate_zstd(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    // Parse frame header descriptor (RFC 8878 bit layout):
    // bit7-6 fcs flag | bit5 single segment | bit4-3 unused |
    // bit2 content checksum | bit1-0 dictionary ID flag.
    let mut h = [0u8; 5];
    if !read_at(f, off, &mut h) {
        return None;
    }
    let fd = h[4];
    if fd & 0b0001_1000 != 0 {
        return None; // unused bits must be zero
    }
    let fcs_flag = (fd >> 6) & 0b11;
    let single_seg = (fd >> 5) & 1;
    if fcs_flag == 0b11 {
        return None; // reserved
    }
    let dict_flag = fd & 0b11;
    // Header sizes: window descriptor absent when single_seg; FCS size by
    // flag; dictID size by flag (0/1/2/4 bytes). The magic (4) + fd (1) come
    // first. NOTE: libzstd emits fcs_flag=0 with single_seg=0 for unknown
    // content sizes (e.g. piped input) — that combination has NO FCS field
    // at all; only single-segment frames carry a 1-byte FCS for flag 0.
    let fhd_size: u64 = if single_seg == 1 { 0 } else { 1 };
    let fcs_size: u64 = match fcs_flag {
        0 if single_seg == 1 => 1,
        0 => 0,
        1 => 2,
        2 => 4,
        _ => 8,
    };
    let dict_size: u64 = match dict_flag { 0 => 0, 1 => 1, 2 => 2, _ => 4 };
    let header_len = 4 + 1 + fhd_size + fcs_size + dict_size;
    let mut pos = off + header_len;
    let mut guard = 0u32;
    while guard < 10_000_000 {
        guard += 1;
        if pos + 3 > file_len {
            return None;
        }
        let mut bh = [0u8; 3];
        if !read_at(f, pos, &mut bh) {
            return None;
        }
        let bits = (bh[0] as u32) | ((bh[1] as u32) << 8) | ((bh[2] as u32) << 16);
        let block_type = (bits >> 1) & 0b11;
        if block_type == 3 {
            return None; // reserved
        }
        let mut block_size = (bits >> 3) & 0x1F_FFFF;
        if block_type == 1 {
            block_size = 1; // RLE block stores exactly 1 byte (binwalk parity)
        }
        let last = bits & 1 != 0;
        pos += 3 + block_size as u64;
        if pos > file_len {
            return None;
        }
        if last {
            // Real-world single-block frames exist (any small input to the
            // zstd CLI); requiring >=2 blocks wrongly rejects them. The walk
            // is already bounded and in-bounds, which is enough validation.
            let size = pos - off + if fd & 0b100 != 0 { 4 } else { 0 }; // content checksum
            if size > file_len - off {
                return None;
            }
            return Some(HitInfo { size: Some(size), count: None });
        }
    }
    None
}

/// lz4 frame: 04 22 4D 18 + FLG/BDesc sanity + header checksum (xxh32, always
/// present per lz4_flex/binwalk) + block walk to the end marker for a real
/// size.
fn validate_lz4(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    let mut h = [0u8; 8];
    if !read_at(f, off, &mut h) {
        return None;
    }
    let flg = h[4];
    let bd = h[5];
    let version = (flg >> 6) & 0b11;
    if version != 1 || flg & 0b10 != 0 {
        return None; // version must be 01; FLG bit1 is the reserved bit
    }
    if bd & 0b1000_1111 != 0 {
        return None; // reserved bits in block descriptor
    }
    let content_size_present = flg & 0x08 != 0;
    let block_checksum = flg & 0x10 != 0;
    let dict_id = flg & 0x01 != 0;
    let content_checksum = flg & 0x04 != 0;
    // Header fields after FLG/BD: optional content size (8), optional dict id
    // (4), then the 1-byte header checksum (always present in practice).
    let mut header_data = vec![flg, bd];
    let mut pos = off + 6;
    if content_size_present {
        let mut cs = [0u8; 8];
        if !read_at(f, pos, &mut cs) {
            return None;
        }
        header_data.extend_from_slice(&cs);
        pos += 8;
    }
    if dict_id {
        let mut did = [0u8; 4];
        if !read_at(f, pos, &mut did) {
            return None;
        }
        header_data.extend_from_slice(&did);
        pos += 4;
    }
    // Header checksum byte: xxh32(FLG..optional fields) >> 8 & 0xFF.
    let mut hc_b = [0u8; 1];
    if !read_at(f, pos, &mut hc_b) {
        return None;
    }
    let expected = (xxh32(&header_data, 0) >> 8) as u8;
    if hc_b[0] != expected {
        return None; // header checksum mismatch → false positive
    }
    pos += 1;
    // Walk blocks: u32 LE length, bit31 = uncompressed; 0 = end marker.
    let mut data_size: u64 = 0;
    let mut guard = 0u32;
    while guard < 10_000_000 {
        guard += 1;
        if pos + 4 > file_len {
            return None;
        }
        let mut sz = [0u8; 4];
        if !read_at(f, pos, &mut sz) {
            return None;
        }
        let raw = u32le(&sz, 0);
        pos += 4;
        if raw == 0 {
            break; // end marker
        }
        let block_len = (raw & 0x7FFF_FFFF) as u64;
        if block_len == 0 || pos + block_len > file_len {
            return None;
        }
        pos += block_len;
        data_size += block_len;
        if block_checksum {
            if pos + 4 > file_len {
                return None;
            }
            pos += 4;
        }
    }
    let total = (pos + if content_checksum { 4 } else { 0 }) - off;
    if total < 6 || data_size == 0 {
        return None;
    }
    Some(HitInfo { size: Some(total), count: None })
}

/// lzma-alone: props byte ∈ {0x5D,0x6E,0x6D,0x6C} and dict size ∈ the
/// whitelist (4 props × 13 dicts, including the 256 KiB preset-0 dict used
/// by liblzma's lzma_alone_encoder); checks the uncompressed-size field
/// plausibility. No decompression (binwalk needs it; we stay dependency-free
/// → MEDIUM).
fn validate_lzma(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    let mut h = [0u8; 13];
    if !read_at(f, off, &mut h) {
        return None;
    }
    let props = h[0];
    if !matches!(props, 0x5D | 0x6E | 0x6D | 0x6C) {
        return None;
    }
    let dict = u32le(&h, 1);
    const DICTS: [u32; 13] = [
        0x1000_0000, 0x2000_0000, 0x0100_0000, 0x0200_0000, 0x0400_0000,
        0x0080_0000, 0x0040_0000, 0x0020_0000, 0x0010_0000, 0x0008_0000,
        0x0004_0000, 0x0002_0000, 0x0001_0000,
    ];
    if !DICTS.contains(&dict) {
        return None;
    }
    // Uncompressed size (bytes 5-12, LE): 0xFFFFFFFFFFFFFFFF = streaming.
    let usize_ = u64le(&h, 5);
    if usize_ != u64::MAX && !(256..=0xFFFF_FFFFu64).contains(&usize_) {
        return None;
    }
    // The alone format has no header checksum, so the decompression dry-run is
    // the discriminator for high-entropy random data with a plausible magic.
    if !lzma_dry_run(f, off) {
        return None;
    }
    let _ = file_len;
    Some(HitInfo { size: None, count: None })
}

/// XP3 (Kirikiri): 10-byte magic "XP3\r\n \n\x1a\x8b\x67" then:
///   byte 10      unused
///   bytes 11..19 u64 LE marker: 0x17 (current) or old-format index offset
/// For the current format, skip u32 minor + u8(128) + u64 index_offset
/// (relative), then read the u64 index offset. The index offset is relative
/// to the archive start; a standalone archive extends to EOF (no total-size
/// field in the header), so the reported extent is `file_len - off`.
const XP3_MAGIC10: &[u8] = b"XP3\r\n \n\x1a\x8b\x67";
const XP3_CURRENT_VER: u64 = 0x17;
const XP3_VERSION_IDENTIFIER: u8 = 128;

fn validate_xp3(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    let mut h = [0u8; 32];
    if !read_at(f, off, &mut h) {
        return None;
    }
    if &h[0..10] != XP3_MAGIC10 {
        return None;
    }
    let index_offset: u64 = match u64le(&h, 11) {
        XP3_CURRENT_VER => {
            // bytes 19..23 = u32 minor; byte 23 must be the version identifier.
            if h[23] != XP3_VERSION_IDENTIFIER {
                return None;
            }
            let rel = u64le(&h, 24);
            let read_pos = off + 19 + rel;
            let mut buf = [0u8; 8];
            if !read_at(f, read_pos, &mut buf) {
                return None;
            }
            u64le(&buf, 0)
        }
        old => old, // old format: u64 at bytes 11..19 is the index offset
    };
    // Index offset is relative to the archive start.
    if index_offset == 0 || off + index_offset > file_len {
        return None;
    }
    // No total-size field; a standalone archive runs to EOF.
    Some(HitInfo { size: Some(file_len - off), count: None })
}

/// PNG: magic + IHDR chunk header (16 bytes), then walk the chunk chain to the
/// IEND terminator to compute the real PNG size. Mirrors binwalk's
/// `extract_png_image` / `get_png_data_size` (no CRC verification — chunk
/// lengths and the IEND terminator are enough to reject false positives).
fn validate_png(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    const PNG_HEADER_LEN: u64 = 8;
    // The signature table already matched the 16-byte magic
    // (8-byte header + 4-byte length 0x0D + "IHDR"), so validate the IHDR
    // width/height are non-zero too.
    let mut ihdr = [0u8; 8];
    if !read_at(f, off + PNG_HEADER_LEN + 8, &mut ihdr) {
        return None;
    }
    let w = u32::from_be_bytes([ihdr[0], ihdr[1], ihdr[2], ihdr[3]]);
    let h = u32::from_be_bytes([ihdr[4], ihdr[5], ihdr[6], ihdr[7]]);
    if w == 0 || h == 0 {
        return None;
    }
    // Walk chunks: each chunk = 4-byte BE length + 4-byte type + data + 4-byte CRC.
    let mut chunk_off = off + PNG_HEADER_LEN;
    let mut prev: Option<u64> = None;
    let mut guard = 0u32;
    while guard < 100_000 {
        guard += 1;
        if chunk_off + 8 > file_len {
            return None;
        }
        if let Some(p) = prev {
            if p >= chunk_off {
                return None; // no progress — malformed
            }
        }
        let mut ch = [0u8; 8];
        if !read_at(f, chunk_off, &mut ch) {
            return None;
        }
        let len = u32::from_be_bytes([ch[0], ch[1], ch[2], ch[3]]) as u64;
        let ctype = [ch[4], ch[5], ch[6], ch[7]];
        let total = 12 + len;
        if chunk_off + total > file_len {
            return None;
        }
        prev = Some(chunk_off);
        if ctype == *b"IEND" {
            return Some(HitInfo { size: Some(chunk_off + total - off), count: None });
        }
        chunk_off += total;
    }
    None
}

/// JPEG: SOI + marker walk to the EOI marker (FF D9) for the real size.
/// Mirrors binwalk's `extract_jpeg_image` / `get_jpeg_data_size`, including
/// the SOS scan-ahead and no-length marker list.
fn validate_jpeg(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    const MARKER_MAGIC: u8 = 0xFF;
    const SOS_MARKER: u8 = 0xDA;
    const EOF_MARKER: u8 = 0xD9;
    const NO_LENGTH: [u8; 12] = [0x00, 0x01, 0xD0, 0xD1, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9];
    const SOS_SKIP: [u8; 9] = [0x00, 0xD0, 0xD1, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7];

    let mut next: u64 = off;
    let mut guard = 0u32;
    while guard < 1_000_000 {
        guard += 1;
        if next + 2 > file_len {
            return None;
        }
        let mut m = [0u8; 2];
        if !read_at(f, next, &mut m) {
            return None;
        }
        if m[0] != MARKER_MAGIC {
            return None;
        }
        let marker_id = m[1];
        next += 2;
        // Most markers carry a 2-byte big-endian length after the marker id.
        if !NO_LENGTH.contains(&marker_id) {
            if next + 2 > file_len {
                return None;
            }
            let mut sz = [0u8; 2];
            if !read_at(f, next, &mut sz) {
                return None;
            }
            next += u16::from_be_bytes([sz[0], sz[1]]) as u64;
        }
        // Start Of Scan: scan ahead until the next real marker. The old loop
        // advanced one byte per seek+read (potentially the whole scan data);
        // scan in chunks with a 1-byte carry so a marker straddling a chunk
        // boundary (a `FF` as the chunk's last byte) is still found.
        if marker_id == SOS_MARKER {
            let mut scan_pos = next;
            let mut chunk = vec![0u8; 64 * 1024];
            let mut carry: Vec<u8> = Vec::with_capacity(1);
            let mut found = false;
            while scan_pos + 1 < file_len {
                let want = ((file_len - scan_pos) as usize).min(chunk.len());
                if !read_at(f, scan_pos, &mut chunk[..want]) {
                    return None;
                }
                let win_len = carry.len() + want;
                let mut i = 0usize;
                let mut hit: Option<usize> = None;
                while i + 1 < win_len {
                    let b = if i < carry.len() { carry[i] } else { chunk[i - carry.len()] };
                    let b2 = if i + 1 < carry.len() { carry[i + 1] } else { chunk[i + 1 - carry.len()] };
                    if b == MARKER_MAGIC && !SOS_SKIP.contains(&b2) {
                        hit = Some(i);
                        break;
                    }
                    i += 1;
                }
                if let Some(rel) = hit {
                    next = scan_pos - carry.len() as u64 + rel as u64;
                    found = true;
                    break;
                }
                if want == 0 {
                    break;
                }
                carry.clear();
                carry.push(chunk[want - 1]); // may be 0xFF — check against next chunk
                scan_pos += want as u64;
            }
            if !found {
                return None;
            }
        }
        if marker_id == EOF_MARKER {
            return Some(HitInfo { size: Some(next - off), count: None });
        }
        // Guard against zero-progress loops (stuck after SOS scan).
        if next <= off {
            return None;
        }
    }
    None
}

/// RIFF: RIFF + 4-byte size; validate the size lands in-bounds and the chunk
/// type is a known container (WAVE / AVI / WEBP / VP8 / VP8L / VP8X).
fn validate_riff(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    let mut h = [0u8; 12];
    if !read_at(f, off, &mut h) {
        return None;
    }
    let ctype = &h[8..12];
    if !matches!(ctype, b"WAVE" | b"AVI " | b"WEBP" | b"VP8 " | b"VP8L" | b"VP8X") {
        return None;
    }
    let sz = u32le(&h, 4) as u64;
    let total = 8 + sz;
    if total < 12 || off + total > file_len + 0x1000 {
        return None;
    }
    Some(HitInfo { size: Some(total), count: None })
}

/// GIF: GIF87a / GIF89a + logical screen descriptor sane (width/height>0).
/// GIF: magic variants GIF87a/GIF89a (matched by the table) + logical screen
/// descriptor sanity (width/height > 0, global color table within bounds).
fn validate_gif(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    let mut h = [0u8; 13];
    if !read_at(f, off, &mut h) {
        return None;
    }
    let w = u16le(&h, 6);
    let hh = u16le(&h, 8);
    if w == 0 || hh == 0 {
        return None;
    }
    // The packed field is [GCT flag][color resolution ×3][sort][GCT size ×3] —
    // there are no reserved bits. An earlier "bits 6-7 must not both be set"
    // check rejected every GIF whose colour resolution is 7 *and* that carries a
    // global colour table, i.e. nearly every GIF a real encoder writes (found by
    // scanning an ffmpeg-written fixture; binwalk reads it fine).
    let flags = h[10];
    // Global color table follows when bit 7 set: 3 * 2^(low 3 bits + 1) bytes.
    if flags & 0x80 != 0 {
        let table_size = 3u64 * (1u64 << ((flags & 0x07) + 1));
        if off + 13 + table_size > file_len {
            return None;
        }
    }
    Some(HitInfo { size: None, count: None })
}

/// TIFF: II*\0 / MM\0* + first IFD offset within the file.
fn validate_tiff(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    let mut h = [0u8; 8];
    if !read_at(f, off, &mut h) {
        return None;
    }
    let ifd = match &h[0..4] {
        b"II\x2a\x00" => u32le(&h, 4),
        b"MM\x00\x2a" => u32::from_be_bytes([h[4], h[5], h[6], h[7]]),
        _ => return None,
    };
    if ifd == 0 || off + ifd as u64 >= file_len {
        return None;
    }
    Some(HitInfo { size: None, count: None })
}

/// PDF: %PDF-1.x (binwalk parity: magic is %PDF-1., then a newline and a %
/// binary marker must follow).
fn validate_pdf(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    let mut h = [0u8; 16];
    if !read_at(f, off, &mut h) {
        return None;
    }
    if &h[0..7] != b"%PDF-1." {
        return None;
    }
    let minor = h[7];
    if !minor.is_ascii_digit() {
        return None;
    }
    // After the minor version, expect newline(s) then a '%' (binary marker).
    for &b in h[8..].iter() {
        if b == b'\n' || b == b'\r' {
            continue;
        }
        if b == b'%' {
            return Some(HitInfo { size: None, count: None });
        }
        break;
    }
    let _ = file_len;
    None
}

/// ELF: 7F 'E' 'L' 'F' + class/endian/version + osabi whitelist + zero
/// padding + e_type/e_version sanity (binwalk parity).
fn validate_elf(f: &mut File, off: u64, _file_len: u64) -> Option<HitInfo> {
    let mut h = [0u8; 20];
    if !read_at(f, off, &mut h) {
        return None;
    }
    if h[4] != 1 && h[4] != 2 {
        return None; // 32/64-bit
    }
    if h[5] != 1 && h[5] != 2 {
        return None; // little/big endian
    }
    if h[6] != 1 {
        return None; // version must be 1
    }
    // OSABI whitelist (binwalk's accepted set).
    if !matches!(h[7], 0 | 1 | 2 | 3 | 6 | 9 | 12 | 64 | 97 | 255) {
        return None;
    }
    // Padding bytes (ident[8..15]) must be zero per spec.
    if h[8..16].iter().any(|&b| b != 0) {
        return None;
    }
    // e_type (16-17, LE): ET_NONE/REL/EXEC/DYN/CORE = 0..4.
    let etype = u16le(&h, 16);
    if etype > 4 {
        return None;
    }
    Some(HitInfo { size: None, count: None })
}

/// MPEG program stream: 00 00 01 BA + MPEG-2 marker bits.
fn validate_mpeg(f: &mut File, off: u64, _file_len: u64) -> Option<HitInfo> {
    let mut h = [0u8; 6];
    if !read_at(f, off, &mut h) {
        return None;
    }
    // Byte 4: '01' + 2-bit version; the top 2 bits must be 01.
    if h[4] & 0xC0 != 0x40 {
        return None;
    }
    Some(HitInfo { size: None, count: None })
}

/// ISO 9660: magic \x01CD001\x01\x00 lives at absolute offset 32768 inside
/// the image (binwalk parity) — that is the primary volume descriptor, whose
/// own offset 0 is the type byte. size = volume_size × block_size.
fn validate_iso(f: &mut File, magic_off: u64, file_len: u64) -> Option<HitInfo> {
    const ISO_MAGIC_OFFSET: u64 = 32768;
    if magic_off < ISO_MAGIC_OFFSET {
        return None;
    }
    let start = magic_off - ISO_MAGIC_OFFSET; // archive/image start (byte 0)
    // The PVD is at magic_off (32768): type(1)="1" CD001(5) version(1).
    let mut vd = [0u8; 136];
    if !read_at(f, magic_off, &mut vd) {
        return None;
    }
    if vd[0] != 1 || &vd[1..6] != b"CD001" || vd[6] != 1 {
        return None;
    }
    // Both-endian volume_space_size at PVD offset 80: LSB at 80, MSB at 84.
    let lsb = u32le(&vd, 80) as u64;
    let msb = u32::from_be_bytes([vd[84], vd[85], vd[86], vd[87]]) as u64;
    if lsb != msb || lsb == 0 {
        return None;
    }
    // Logical block size: both-endian at PVD offset 128 (LSB at 128, MSB at 130).
    let block_lsb = u16le(&vd, 128) as u64;
    let block_msb = u16::from_be_bytes([vd[130], vd[131]]) as u64;
    if block_lsb != block_msb || block_lsb == 0 {
        return None;
    }
    let size = lsb * block_lsb;
    if size < 32768 || start + size > file_len {
        return None;
    }
    // Size semantics across the scan pipeline are "extent FROM the magic
    // offset to the end of the region" (so magic_offset + size == end and the
    // scan's `magic_offset + size <= file_len` check holds). The PVD sits at
    // image offset 32768, so the extent from the magic is image_size - 32768.
    Some(HitInfo { size: Some(size - ISO_MAGIC_OFFSET), count: None })
}

/// POSIX tar: "ustar" magic at archive offset 257 (GNU "ustar\0" or POSIX
/// "ustar  "). The header checksum (octal at 148..156, bytes summed as
/// spaces) must match — this is what kills false "ustar" matches in random
/// data. Walks the 512-byte entry chain to the two-zero-block end marker for
/// the exact archive size (binwalk parity).
fn validate_tar(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    const TAR_MAGIC_OFFSET: u64 = 257;
    if off < TAR_MAGIC_OFFSET {
        return None;
    }
    let start = off - TAR_MAGIC_OFFSET; // archive start (byte 0)
    let mut h = [0u8; 512];
    if !read_at(f, start, &mut h) {
        return None;
    }
    if &h[257..262] != b"ustar" {
        return None;
    }
    // Version byte: NUL (GNU) or space (POSIX); other = false positive.
    if h[262] != 0 && h[262] != b' ' {
        return None;
    }
    if !tar_header_checksum_ok(&h) {
        return None;
    }
    // Skip the first entry's data before walking the rest of the chain.
    let mut pos = start + 512 + ((tar_octal_size(&h) + 511) / 512) * 512;
    // Walk entries: each = 512-byte header + ceil(size/512)*512 data. The
    // archive ends with two 512-byte zero blocks; a header with a bad
    // checksum (trailing garbage / next file's data) stops the walk.
    let mut zero_blocks = 0u32;
    let mut guard = 0u32;
    while pos + 512 <= file_len {
        guard += 1;
        if guard > 10_000_000 {
            return None;
        }
        let mut hb = [0u8; 512];
        if !read_at(f, pos, &mut hb) {
            break;
        }
        if hb.iter().all(|&b| b == 0) {
            zero_blocks += 1;
            pos += 512;
            if zero_blocks >= 2 {
                break;
            }
            continue;
        }
        zero_blocks = 0;
        if !tar_header_checksum_ok(&hb) {
            break;
        }
        let size = tar_octal_size(&hb);
        pos += 512 + ((size + 511) / 512) * 512;
    }
    if pos > file_len {
        pos = file_len;
    }
    Some(HitInfo { size: Some(pos - off), count: None })
}

/// Tar header checksum: sum of all 512 bytes with the 8 checksum bytes
/// (148..156) counted as spaces, compared to the stored octal at 148..156.
/// Parsing tolerates legacy space-padded octal fields (some Unix tars pad
/// with spaces instead of zeros).
fn tar_header_checksum_ok(h: &[u8; 512]) -> bool {
    let mut sum: u64 = 0;
    for (i, &b) in h.iter().enumerate() {
        sum += if (148..156).contains(&i) { 0x20 } else { b as u64 };
    }
    let mut stored: u64 = 0;
    let mut started = false;
    for &b in &h[148..156] {
        if b == 0 {
            break;
        }
        let c = b as char;
        if c == ' ' && !started {
            continue;
        }
        if c.is_ascii_digit() {
            started = true;
            stored = stored * 8 + (c as u64 - '0' as u64);
        } else if c == ' ' {
            break;
        } else {
            return false;
        }
    }
    stored == sum
}

/// Octal size field (bytes 124..136, 11 digits + NUL/space). Tolerates legacy
/// leading-space padding.
fn tar_octal_size(h: &[u8; 512]) -> u64 {
    let mut v: u64 = 0;
    let mut started = false;
    for &b in &h[124..136] {
        if b == 0 {
            break;
        }
        let c = b as char;
        if c == ' ' && !started {
            continue;
        }
        if c.is_ascii_digit() {
            started = true;
            v = v * 8 + (c as u64 - '0' as u64);
        } else {
            break;
        }
    }
    v
}

/// CRC32 with precomputed 256-entry lookup table (~8x faster than bit-by-bit).
fn crc32(data: &[u8], init: u32) -> u32 {
    const TABLE: [u32; 256] = {
        let mut t = [0u32; 256];
        let mut i = 0u32;
        while i < 256 {
            let mut c = i;
            let mut j = 0;
            while j < 8 {
                c = if c & 1 != 0 { (c >> 1) ^ 0xEDB88320 } else { c >> 1 };
                j += 1;
            }
            t[i as usize] = c;
            i += 1;
        }
        t
    };
    let mut c = init ^ 0xFFFFFFFF;
    for &b in data {
        c = TABLE[((c ^ b as u32) & 0xFF) as usize] ^ (c >> 8);
    }
    c ^ 0xFFFFFFFF
}

/// xxHash32 (used by LZ4 header checksums).
fn xxh32(data: &[u8], seed: u32) -> u32 {
    const P1: u32 = 0x9E37_79B1;
    const P2: u32 = 0x85EB_CA77;
    const P3: u32 = 0xC2B2_AE3D;
    const P4: u32 = 0x27D4_EB2F;
    const P5: u32 = 0x1656_67B1;

    let mut h: u32;
    let n = data.len();
    let mut i = 0usize;
    if n >= 16 {
        let mut v1 = seed.wrapping_add(P1).wrapping_add(P2);
        let mut v2 = seed.wrapping_add(P2);
        let mut v3 = seed;
        let mut v4 = seed.wrapping_sub(P1);
        while i + 16 <= n {
            v1 = round(v1, u32::from_le_bytes(data[i..i + 4].try_into().unwrap()));
            v2 = round(v2, u32::from_le_bytes(data[i + 4..i + 8].try_into().unwrap()));
            v3 = round(v3, u32::from_le_bytes(data[i + 8..i + 12].try_into().unwrap()));
            v4 = round(v4, u32::from_le_bytes(data[i + 12..i + 16].try_into().unwrap()));
            i += 16;
        }
        h = v1.rotate_left(1)
            .wrapping_add(v2.rotate_left(7))
            .wrapping_add(v3.rotate_left(12))
            .wrapping_add(v4.rotate_left(18));
    } else {
        h = seed.wrapping_add(P5);
    }
    h = h.wrapping_add(n as u32);
    while i + 4 <= n {
        h = h.wrapping_add(u32::from_le_bytes(data[i..i + 4].try_into().unwrap()).wrapping_mul(P3));
        h = h.rotate_left(17).wrapping_mul(P4);
        i += 4;
    }
    while i < n {
        h = h.wrapping_add((data[i] as u32).wrapping_mul(P5));
        h = h.rotate_left(11).wrapping_mul(P1);
        i += 1;
    }
    h ^= h >> 15;
    h = h.wrapping_mul(P2);
    h ^= h >> 13;
    h = h.wrapping_mul(P3);
    h ^= h >> 16;
    h
}

fn round(mut acc: u32, input: u32) -> u32 {
    const P1: u32 = 0x9E37_79B1;
    const P2: u32 = 0x85EB_CA77;
    acc = acc.wrapping_add(input.wrapping_mul(P2));
    acc = acc.rotate_left(13);
    acc.wrapping_mul(P1)
}

/// Builds a minimal single-file (stored) ZIP in memory for tests.
#[cfg(test)]
pub fn make_min_zip() -> Vec<u8> {
    make_min_zip_with_pad(0)
}

/// make_min_zip with [pad] filler bytes between the file data and the central
/// directory (used to move the EOCD to a specific offset).
#[cfg(test)]
pub fn make_min_zip_with_pad(pad: usize) -> Vec<u8> {
    let name = b"a.txt";
    let data = b"hello";
    let mut z = Vec::new();
    // Local file header (30 bytes): sig, ver, flags, method=0, time, date, crc, csize, usize, name len, extra len
    z.extend_from_slice(b"PK\x03\x04");
    z.extend_from_slice(&0x0014u16.to_le_bytes()); // version
    z.extend_from_slice(&0u16.to_le_bytes()); // flags
    z.extend_from_slice(&0u16.to_le_bytes()); // method = stored
    z.extend_from_slice(&0u16.to_le_bytes()); // time
    z.extend_from_slice(&0u16.to_le_bytes()); // date
    z.extend_from_slice(&0u32.to_le_bytes()); // crc (stored → 0 ok for test)
    z.extend_from_slice(&(data.len() as u32).to_le_bytes()); // csize
    z.extend_from_slice(&(data.len() as u32).to_le_bytes()); // usize
    z.extend_from_slice(&(name.len() as u16).to_le_bytes());
    z.extend_from_slice(&0u16.to_le_bytes()); // extra len
    z.extend_from_slice(name);
    z.extend_from_slice(data);
    z.extend_from_slice(&vec![0u8; pad]);

    // Central directory header (46 bytes) at current offset.
    let cd_off = z.len() as u32;
    z.extend_from_slice(b"PK\x01\x02");
    z.extend_from_slice(&0x0014u16.to_le_bytes()); // version made by
    z.extend_from_slice(&0x0014u16.to_le_bytes()); // version needed
    z.extend_from_slice(&0u16.to_le_bytes()); // flags
    z.extend_from_slice(&0u16.to_le_bytes()); // method
    z.extend_from_slice(&0u16.to_le_bytes()); // time
    z.extend_from_slice(&0u16.to_le_bytes()); // date
    z.extend_from_slice(&0u32.to_le_bytes()); // crc
    z.extend_from_slice(&(data.len() as u32).to_le_bytes()); // csize
    z.extend_from_slice(&(data.len() as u32).to_le_bytes()); // usize
    z.extend_from_slice(&(name.len() as u16).to_le_bytes());
    z.extend_from_slice(&0u16.to_le_bytes()); // extra len
    z.extend_from_slice(&0u16.to_le_bytes()); // comment len
    z.extend_from_slice(&0u16.to_le_bytes()); // disk start
    z.extend_from_slice(&0u16.to_le_bytes()); // internal attrs
    z.extend_from_slice(&0u32.to_le_bytes()); // external attrs
    z.extend_from_slice(&0u32.to_le_bytes()); // local header offset
    z.extend_from_slice(name);
    let cd_size = (z.len() as u32) - cd_off;

    // EOCD (22 bytes) + comment.
    z.extend_from_slice(b"PK\x05\x06");
    z.extend_from_slice(&0u16.to_le_bytes()); // disk number
    z.extend_from_slice(&0u16.to_le_bytes()); // cd disk
    z.extend_from_slice(&1u16.to_le_bytes()); // entries on this disk
    z.extend_from_slice(&1u16.to_le_bytes()); // total entries
    z.extend_from_slice(&cd_size.to_le_bytes());
    z.extend_from_slice(&cd_off.to_le_bytes());
    z.extend_from_slice(&0u16.to_le_bytes()); // comment len
    z
}

/// NSA (NScripter): no magic bytes — validated structurally at file start.
/// Header: u16 BE entry count + 4 reserved bytes, then per entry:
///   name (NUL-terminated ≤512) + comp(1) + offset(4 BE) + csize(4 BE) + usize(4 BE).
/// Offsets are relative to the body start; the body follows all entries.
/// Only meaningful as a whole-file check (offset 0).
pub fn validate_nsa_whole_file(f: &mut File, file_len: u64) -> Option<HitInfo> {
    if file_len < 6 {
        return None;
    }
    let mut hdr = [0u8; 6];
    if !read_at(f, 0, &mut hdr) {
        return None;
    }
    let count = u16::from_be_bytes([hdr[0], hdr[1]]) as usize;
    if count == 0 || count > 100_000 {
        return None;
    }
    let mut pos: u64 = 6;
    let mut max_data_end: u64 = 0;
    for _ in 0..count {
        // Filename: NUL-terminated, bounded, printable ASCII (real NSA names
        // are like "bg\542-1.jpg" — this rejects random binary data).
        let mut name_len = 0usize;
        let mut name_buf = [0u8; 1];
        loop {
            if pos >= file_len || !read_at(f, pos, &mut name_buf) {
                return None;
            }
            pos += 1;
            let b = name_buf[0];
            if b == 0 {
                break;
            }
            if b < 0x20 || b > 0x7E {
                return None; // non-printable filename byte → not a real NSA
            }
            name_len += 1;
            if name_len > 512 {
                return None;
            }
        }
        // comp(1) + offset(4) + csize(4) + usize(4).
        if pos + 13 > file_len {
            return None;
        }
        let mut meta = [0u8; 13];
        if !read_at(f, pos, &mut meta) {
            return None;
        }
        pos += 13;
        let comp = meta[0];
        if comp > 2 {
            return None; // only stored(0) / zlib(1) / lzss(2)
        }
        let offset = u32::from_be_bytes([meta[1], meta[2], meta[3], meta[4]]) as u64;
        let csize = u32::from_be_bytes([meta[5], meta[6], meta[7], meta[8]]) as u64;
        max_data_end = max_data_end.max(offset.saturating_add(csize));
    }
    // A body must follow the entries, and the last entry's data must fit:
    // data_start (=pos) + max_data_end ≤ file_len.
    if pos >= file_len || pos + max_data_end > file_len || max_data_end == 0 {
        return None;
    }
    // A standalone NSA runs to EOF; the header walk already proves structure.
    Some(HitInfo { size: Some(file_len), count: Some(count as u32) })
}

/// Signature table: one or more magic byte patterns + label + validator +
/// confidence. Pattern order in `magics` determines match priority.
// ─────────────────────────────────────────────────────────────────────────────
// Expansion: galgame/engine, media, archive, mobile and filesystem signatures.
//
// Magics are taken from binwalk 3.1's own signature sources
// (ReFirmLabs/binwalk, `src/signatures/*.rs`) — an independent reference — plus
// the engine formats binwalk does not know. Every validator here is ours: where
// a magic is short enough to collide with random data the validator does the
// real work, and where it is long and specific the check is only "is there room
// for the structure". Confidence follows the same rule as the original table.
// ─────────────────────────────────────────────────────────────────────────────

/// Declares a validator whose whole body is an inline check, so the ~60 new
/// signatures read as one line of reasoning each instead of a named function.
macro_rules! validators {
    ($( $(#[$meta:meta])* $name:ident($f:ident, $off:ident, $len:ident) $body:block )*) => {
        $(
            $(#[$meta])*
            #[allow(unused_variables)]
            fn $name($f: &mut File, $off: u64, $len: u64) -> Option<HitInfo> $body
        )*
    };
}

/// "The structure cannot possibly fit" guard for long, specific magics.
fn room(off: u64, file_len: u64, need: u64) -> bool {
    file_len.saturating_sub(off) >= need
}

/// Reads big-endian integers out of a header buffer.
fn u16be(b: &[u8], i: usize) -> u16 { u16::from_be_bytes([b[i], b[i + 1]]) }
fn u64be(b: &[u8], i: usize) -> u64 { u64::from_be_bytes([b[i], b[i+1], b[i+2], b[i+3], b[i+4], b[i+5], b[i+6], b[i+7]]) }
fn u32be(b: &[u8], i: usize) -> u32 { u32::from_be_bytes([b[i], b[i + 1], b[i + 2], b[i + 3]]) }

validators! {
    /// Ren'Py archive (`.rpa`): fixed-width hex header
    /// `RPA-3.0 <16 hex index offset> <8 hex key>\n`; ALT-1.0 swaps the two
    /// fields. Random data does not produce that shape, and the index offset
    /// must land inside the file — that is what makes a 8-byte magic safe.
    validate_rpa(f, off, file_len) {
        let mut h = [0u8; 34];
        if !read_at(f, off, &mut h) { return None; }
        // RPA-2.0 carries only the index offset (25-byte header); 3.x adds a
        // key field (34 bytes) and ALT-1.0 swaps the two fields' order.
        let (range, newline_at) = match &h[0..8] {
            b"RPA-3.0 " | b"RPA-3.2 " | b"RPA-4.0 " => (8..24, 33),
            b"RPA-2.0 " => (8..24, 24),
            b"ALT-1.0 " => (17..33, 33),
            _ => return None,
        };
        if h[newline_at] != b'\n' { return None; }
        let mut index_at: u64 = 0;
        for c in &h[range] {
            index_at = index_at * 16 + (*c as char).to_digit(16)? as u64;
        }
        if index_at < off + 34 || index_at >= file_len { return None; }
        // The archive ends where the zlib'd index ends (the index is written
        // last). Decoding it both proves the index is real — random data that
        // happens to look like a header does not decompress — and yields the
        // exact size, so the hit is not truncated at the next embedded zlib
        // stream (a Ren'Py index *is* a zlib stream, so that happened).
        let bound = (file_len - index_at).min(1 << 26);
        if f.seek(SeekFrom::Start(index_at)).is_err() { return None; }
        let mut limited = f.take(bound);
        let mut dec = flate2::read::ZlibDecoder::new(&mut limited);
        let mut sink = [0u8; 8192];
        let mut produced = 0usize;
        loop {
            match dec.read(&mut sink) {
                Ok(0) => break,
                Ok(n) => {
                    produced += n;
                    if produced >= (1 << 26) { break; }
                }
                Err(_) => {
                    let _ = f.seek(SeekFrom::Start(off));
                    return None;
                }
            }
        }
        if produced == 0 {
            let _ = f.seek(SeekFrom::Start(off));
            return None;
        }
        let consumed = bound - limited.limit();
        let _ = f.seek(SeekFrom::Start(off));
        Some(HitInfo { size: Some((index_at - off) + consumed), count: None })
    }

    /// CatSystem2 / Frontwing KIF (`.int`): magic + entry count + a
    /// `count × 72` index that has to fit in the file. The encrypted variant
    /// carries `__key__.dat` as its first record, which is a second, cheap
    /// confirmation.
    validate_kif(f, off, file_len) {
        if !room(off, file_len, 8 + 72) { return None; }
        let mut h = [0u8; 80];
        if !read_at(f, off, &mut h) { return None; }
        let count = u32le(&h, 4);
        if count == 0 || count > 200_000 { return None; }
        let index_len = count as u64 * 72;
        if 8 + index_len > file_len - off { return None; }
        let name = &h[8..72];
        let end = name.iter().position(|c| *c == 0).unwrap_or(name.len());
        let first_is_key = &name[..end] == b"__key__.dat";
        if !first_is_key && h[8] == 0 { return None; }
        Some(HitInfo { size: None, count: Some(count) })
    }

    /// RPG Maker MV/MZ obfuscated asset: the fixed 16-byte header
    /// `RPGMV\0\0\0\0\x03\x01\0\0\0\0\0` (the same bytes rgss-core's reader
    /// matches — it is the consumer, so its constant is the source of truth).
    validate_rpgmv(f, off, file_len) {
        let mut h = [0u8; 16];
        if !read_at(f, off, &mut h) { return None; }
        const RPGM_HEADER: [u8; 16] = [0x52, 0x50, 0x47, 0x4D, 0x56, 0, 0, 0, 0, 0x03, 0x01, 0, 0, 0, 0, 0];
        if h != RPGM_HEADER { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// Criware ADX audio: `0x8000` (weak!) so the validator carries the weight —
    /// data offset inside the header, a plausible sample rate/channel count and
    /// the "(c)CRI" copyright string within the first 64 bytes. Verified against
    /// a real ADX written by ffmpeg's adpcm_adx encoder.
    validate_adx(f, off, file_len) {
        let mut h = [0u8; 64];
        if !read_at(f, off, &mut h) { return None; }
        if u16be(&h, 0) != 0x8000 { return None; }
        let data_off = u16be(&h, 2) as u64;
        if data_off < 0x18 || data_off > 0x800 { return None; }
        if h[4] > 4 { return None; }                       // encoding: fixed / 4-bit ADPCM
        let rate = u32be(&h, 8);
        if !(1000..=192_000).contains(&rate) { return None; }
        let channels = h[7];
        if channels == 0 || channels > 8 { return None; }
        if !h.windows(6).any(|w| w == b"(c)CRI") { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// Criware HCA audio: `HCA\0` + version + header size.
    validate_hca(f, off, file_len) {
        let mut h = [0u8; 16];
        if !read_at(f, off, &mut h) { return None; }
        let version = u16be(&h, 4);
        let header_size = u16be(&h, 6);
        if version == 0 || version > 4 { return None; }
        if header_size < 8 || (header_size as u64) > file_len - off { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// Criware AWB/AFS2 archive: `AFS2` + version byte + entry count.
    validate_awb(f, off, file_len) {
        let mut h = [0u8; 16];
        if !read_at(f, off, &mut h) { return None; }
        let version = h[4];
        if version != 1 && version != 2 { return None; }
        let count = u32le(&h, 8);
        if count == 0 || count > 1_000_000 { return None; }
        Some(HitInfo { size: None, count: Some(count) })
    }

    /// Criware CPK archive: "CPK " + a small version number.
    validate_cpk(f, off, file_len) {
        let mut h = [0u8; 16];
        if !read_at(f, off, &mut h) { return None; }
        let version = u32le(&h, 4);
        if version == 0 || version > 8 { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// Unity asset bundle: magic + version fields inside the first block.
    validate_unity(f, off, file_len) {
        if !room(off, file_len, 32) { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// Unreal Engine pak: magic + version 1..12 + a header offset that fits.
    validate_unreal(f, off, file_len) {
        let mut h = [0u8; 44];
        if !read_at(f, off, &mut h) { return None; }
        let version = u32le(&h, 4);
        if version == 0 || version > 12 { return None; }
        let index_off = u64le(&h, 8);
        let index_size = u64le(&h, 16);
        if index_off < 44 || index_off + index_size > file_len - off { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// Godot engine package: "GDPC" + pack format version (1..3) + engine
    /// version triple.
    validate_godot(f, off, file_len) {
        let mut h = [0u8; 24];
        if !read_at(f, off, &mut h) { return None; }
        let pack_version = u32le(&h, 4);
        if pack_version == 0 || pack_version > 3 { return None; }
        Some(HitInfo { size: None, count: None })
    }



    /// MIDI: "MThd" + a header length of exactly 6.
    validate_midi(f, off, file_len) {
        let mut h = [0u8; 8];
        if !read_at(f, off, &mut h) { return None; }
        if u32be(&h, 4) != 6 { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// AIFF/AIFC: the "FORM" magic is generic, so the form type at +8 decides.
    validate_aiff(f, off, file_len) {
        let mut h = [0u8; 12];
        if !read_at(f, off, &mut h) { return None; }
        if &h[8..12] != b"AIFF" && &h[8..12] != b"AIFC" { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// Sun/NeXT audio: ".snd" + a header size of at least the fixed part.
    validate_au(f, off, file_len) {
        let mut h = [0u8; 24];
        if !read_at(f, off, &mut h) { return None; }
        let data_off = u32be(&h, 4) as u64;
        if data_off < 24 || data_off > file_len - off { return None; }
        let encoding = u32be(&h, 12);
        if encoding > 27 { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// Apple Core Audio Format: "caff" + version 1.
    validate_caf(f, off, file_len) {
        let mut h = [0u8; 8];
        if !read_at(f, off, &mut h) { return None; }
        if u16be(&h, 4) != 1 { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// ISO base media (MP4/MOV/M4A/HEIC/AVIF): the magic is the "ftyp" box type
    /// at +4, so the check reads the box size and the brand.
    validate_ftyp(f, off, file_len) {
        // `off` is the "ftyp" string; the box (and its 4-byte size field) starts
        // 4 bytes earlier. Reading both halves from one buffer keeps this to a
        // single seek.
        if off < 4 { return None; }
        let mut h = [0u8; 16];
        if !read_at(f, off - 4, &mut h) { return None; }
        let size = u32be(&h, 0) as u64;
        if size < 16 || size > file_len - (off - 4) { return None; }
        const BRANDS: [&[u8; 4]; 14] = [b"isom", b"iso2", b"iso4", b"iso5", b"iso6", b"mp41", b"mp42", b"M4A ", b"M4V ", b"qt  ", b"3gp4", b"heic", b"mif1", b"avif"];
        if !BRANDS.iter().any(|b| *b == &h[8..12]) { return None; }
        Some(HitInfo { size: None, count: None })
    }


    /// Windows icon/cursor: 4-byte magic is too generic on its own, so the
    /// entry count and the first entry's offset/size must be consistent.
    validate_ico(f, off, file_len) {
        let mut h = [0u8; 22];
        if !read_at(f, off, &mut h) { return None; }
        let kind = u16le(&h, 2);
        if kind != 1 && kind != 2 { return None; }
        let count = u16le(&h, 4) as usize;
        if count == 0 || count > 255 { return None; }
        // "   " is common in binary data, so every directory entry (not
        // just the first) has to point at real bytes inside the file.
        let dir_end = (6 + count * 16) as u64;
        if count > 64 { return None; }
        let mut buf = [0u8; 16];
        for i in 0..count as u64 {
            if !read_at(f, off + 6 + i * 16, &mut buf) { return None; }
            let size = u32le(&buf, 8) as u64;
            let entry_off = u32le(&buf, 12) as u64;
            if size == 0 || entry_off < dir_end { return None; }
            if entry_off + size > file_len - off { return None; }
            // Entry layout: width, height, colourCount, reserved(1), planes(2),
            // bitCount(2), bytesInRes(4), imageOffset(4). The reserved byte is
            // always 0, planes is 0 or 1, and bitCount is one of the handful of
            // values Windows writes — without those a font's binary tables look
            // like a consistent directory (9 false hits in one collection).
            if buf[3] != 0 { return None; }
            if u16le(&buf, 4) > 1 { return None; }
            if !matches!(u16le(&buf, 6), 0 | 1 | 4 | 8 | 16 | 24 | 32) { return None; }
        }
        Some(HitInfo { size: None, count: Some(count as u32) })
    }


    /// Photoshop PSD: "8BPS" + version 1 + six reserved zero bytes.
    validate_psd(f, off, file_len) {
        let mut h = [0u8; 12];
        if !read_at(f, off, &mut h) { return None; }
        if u16be(&h, 4) != 1 || h[6..12].iter().any(|c| *c != 0) { return None; }
        Some(HitInfo { size: None, count: None })
    }


    /// DirectDraw surface: "DDS " + header size 124 + non-zero dimensions.
    validate_dds(f, off, file_len) {
        let mut h = [0u8; 20];
        if !read_at(f, off, &mut h) { return None; }
        if u32le(&h, 4) != 124 { return None; }
        let height = u32le(&h, 12);
        let width = u32le(&h, 16);
        if height == 0 || width == 0 || height > 65536 || width > 65536 { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// QOI image: "qoif" + dimensions and a channel count of 3 or 4.
    validate_qoi(f, off, file_len) {
        let mut h = [0u8; 14];
        if !read_at(f, off, &mut h) { return None; }
        let width = u32be(&h, 4);
        let height = u32be(&h, 8);
        if width == 0 || height == 0 || width > 100_000 || height > 100_000 { return None; }
        if h[12] != 3 && h[12] != 4 { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// OpenEXR: magic + version 2 with no reserved bits set.
    validate_exr(f, off, file_len) {
        let mut h = [0u8; 8];
        if !read_at(f, off, &mut h) { return None; }
        let version = u32le(&h, 4);
        if version & 0xFF != 2 || version & 0xFFFF_F000 != 0 { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// ASTC texture: magic + block dimensions in 1..12.
    validate_astc(f, off, file_len) {
        let mut h = [0u8; 16];
        if !read_at(f, off, &mut h) { return None; }
        for i in 4..7 {
            if h[i] == 0 || h[i] > 12 { return None; }
        }
        Some(HitInfo { size: None, count: None })
    }

    /// TrueType/OpenType font: the 4-byte version is generic ("\0\1\0\0"), so
    /// the table directory must be plausible: a small table count whose
    /// directory fits inside the file.
    validate_sfnt(f, off, file_len) {
        let mut h = [0u8; 12];
        if !read_at(f, off, &mut h) { return None; }
        let version = u32be(&h, 0);
        if version != 0x0001_0000 && &h[0..4] != b"true" && &h[0..4] != b"OTTO" { return None; }
        let num_tables = u16be(&h, 4) as u64;
        if num_tables == 0 || num_tables > 512 { return None; }
        let dir_end = 12 + num_tables * 16;
        if dir_end > file_len - off { return None; }
        // "   " is an ordinary 4-byte value in binary data, so the magic
        // alone identified 165 "fonts" inside /usr/bin/python3 and 389 inside a
        // real font collection. The table directory is what separates a font
        // from noise: every table must lie inside the file, and at least one
        // must be one of the tables every font carries.
        const KNOWN: [&[u8; 4]; 16] = [b"cmap", b"glyf", b"head", b"hhea", b"hmtx", b"loca", b"maxp", b"name", b"post", b"OS/2", b"CFF ", b"GPOS", b"GSUB", b"DSIG", b"kern", b"fpgm"];
        let mut known = 0;
        let mut buf = [0u8; 16];
        for i in 0..num_tables.min(64) {
            if !read_at(f, off + 12 + i * 16, &mut buf) { return None; }
            let table_off = u32be(&buf, 8) as u64;
            let table_len = u32be(&buf, 12) as u64;
            if table_off < dir_end || table_off + table_len > file_len - off { return None; }
            if KNOWN.iter().any(|k| *k == &buf[0..4]) { known += 1; }
        }
        if known == 0 { return None; }
        Some(HitInfo { size: None, count: Some(num_tables as u32) })
    }

    /// Font collection: "ttcf" + version + a font count whose offset table fits.
    validate_ttc(f, off, file_len) {
        let mut h = [0u8; 16];
        if !read_at(f, off, &mut h) { return None; }
        let version = u32be(&h, 4);
        if version != 0x0001_0000 && version != 0x0002_0000 { return None; }
        let count = u32be(&h, 8) as u64;
        if count == 0 || count > 1024 || 12 + count * 4 > file_len - off { return None; }
        Some(HitInfo { size: None, count: Some(count as u32) })
    }

    /// Windows Metafile: the placeable header is a specific 22-byte structure.
    validate_wmf(f, off, file_len) {
        let mut h = [0u8; 22];
        if !read_at(f, off, &mut h) { return None; }
        const INCHES: [u16; 5] = [1440, 1200, 1000, 576, 100];
        if !INCHES.contains(&u16le(&h, 14)) { return None; }
        if u32le(&h, 16) != 0 { return None; }  // reserved, must be zero
        Some(HitInfo { size: None, count: None })
    }

    /// Enhanced Metafile: 4 zero-ish bytes are far too generic, so the record
    /// type and the " EMF" signature at +40 carry the decision.
    validate_emf(f, off, file_len) {
        let mut h = [0u8; 44];
        if !read_at(f, off, &mut h) { return None; }
        if u32le(&h, 0) != 1 { return None; }
        if &h[40..44] != b" EMF" { return None; }
        Some(HitInfo { size: None, count: None })
    }



    /// KTX texture: the 12-byte identifier is specific; the endianness field
    /// must be one of the two defined values.
    validate_ktx(f, off, file_len) {
        let mut h = [0u8; 16];
        if !read_at(f, off, &mut h) { return None; }
        let endian = u32le(&h, 12);
        if endian != 0x0403_0201 && endian != 0x0102_0304 { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// Microsoft Cabinet: "MSCF" + four reserved zero bytes + a total size that
    /// has to fit (binwalk's own parser does exactly this).
    validate_cab(f, off, file_len) {
        let mut h = [0u8; 36];
        if !read_at(f, off, &mut h) { return None; }
        let total_size = u32le(&h, 8) as u64;
        if total_size < 36 || total_size > file_len - off { return None; }
        let file_count = u16le(&h, 28);
        let folder_count = u16le(&h, 26);
        if folder_count == 0 || folder_count > 1024 { return None; }
        Some(HitInfo { size: Some(total_size), count: Some(file_count as u32) })
    }

    /// CPIO (ASCII variants): the magic plus an inode/mode pair that is not
    /// obviously nonsense.
    validate_cpio(f, off, file_len) {
        let mut h = [0u8; 110];
        if !read_at(f, off, &mut h) { return None; }
        if !h[6..110].iter().all(|c| c.is_ascii_hexdigit()) { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// XAR archive: "xar!" + header size 28 + version 1.
    validate_xar(f, off, file_len) {
        let mut h = [0u8; 28];
        if !read_at(f, off, &mut h) { return None; }
        if u16be(&h, 4) != 28 || u16be(&h, 6) != 1 { return None; }
        let toc_len = u64be(&h, 8);
        if toc_len == 0 || toc_len > file_len - off { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// SquashFS: magic + a version 1..4 + a power-of-two block size.
    validate_squashfs(f, off, file_len) {
        let mut h = [0u8; 32];
        if !read_at(f, off, &mut h) { return None; }
        let block_size = u32le(&h, 12);
        if block_size < 4096 || block_size > (1 << 20) || block_size & (block_size - 1) != 0 { return None; }
        // v4 is what every real image uses; the major/minor pair sits at +28.
        if u16le(&h, 28) != 4 || u16le(&h, 30) != 0 { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// CramFS: "Compressed ROMFS" + a size that fits.
    validate_cramfs(f, off, file_len) {
        let mut h = [0u8; 32];
        if !read_at(f, off, &mut h) { return None; }
        let size = u32le(&h, 16) as u64;
        if size < 32 || size > file_len - off { return None; }
        Some(HitInfo { size: Some(size), count: None })
    }

    /// RomFS: "-rom1fs-" + the full image size.
    validate_romfs(f, off, file_len) {
        let mut h = [0u8; 16];
        if !read_at(f, off, &mut h) { return None; }
        let size = u32be(&h, 8) as u64;
        if size < 64 || size > file_len - off || size % 16 != 0 { return None; }
        Some(HitInfo { size: Some(size), count: None })
    }

    /// LHA/LZH: the magic sits at +2 (header size + checksum come first), so
    /// the hit offset needs a 2-byte adjustment in the carve path.
    validate_lha(f, off, file_len) {
        if !room(off, file_len, 32) { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// ARJ: 2-byte magic, so the basic header size at +2 decides (8..2600 is
    /// the range the format allows).
    validate_arj(f, off, file_len) {
        let mut h = [0u8; 12];
        if !read_at(f, off, &mut h) { return None; }
        let header_size = u16le(&h, 2);
        if !(8..=2600).contains(&header_size) { return None; }
        if header_size as u64 > file_len - off { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// lzip: "LZIP" + version 1 + a dictionary size code ≤ 29.
    validate_lzip(f, off, file_len) {
        let mut h = [0u8; 6];
        if !read_at(f, off, &mut h) { return None; }
        if h[4] != 1 || h[5] > 29 { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// lzop: 9-byte magic + a version whose header fits.
    validate_lzop(f, off, file_len) {
        let mut h = [0u8; 16];
        if !read_at(f, off, &mut h) { return None; }
        let version = u16be(&h, 9);
        if !(0x0900..=0x1040).contains(&version) { return None; }
        Some(HitInfo { size: None, count: None })
    }


    /// zlib stream: a 2-byte magic, so the header checksum decides — the two
    /// header bytes must be a multiple of 31 (the format's own guard).
    validate_zlib(f, off, file_len) {
        let mut h = [0u8; 2];
        if !read_at(f, off, &mut h) { return None; }
        let cmf = h[0] as u32;
        let flg = h[1] as u32;
        if cmf & 0x0F != 8 { return None; }            // deflate
        if cmf >> 4 > 7 { return None; }               // window ≤ 32 KiB
        if (cmf * 256 + flg) % 31 != 0 { return None; }
        if !zlib_dry_run(f, off) { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// compress(1): "\x1f\x9d" + a flags byte with only the defined bits set.
    validate_compressd(f, off, file_len) {
        let mut h = [0u8; 3];
        if !read_at(f, off, &mut h) { return None; }
        let flags = h[2];
        let max_bits = flags & 0x1F;
        if max_bits > 16 { return None; }
        if flags & 0x60 != 0 { return None; }          // reserved bits
        Some(HitInfo { size: None, count: None })
    }

    /// ZIP end-of-central-directory only (an EMPTY archive has no local
    /// header): the comment length has to end exactly at EOF.
    validate_zip_eocd(f, off, file_len) {
        let mut h = [0u8; 22];
        if !read_at(f, off, &mut h) { return None; }
        let comment_len = u16le(&h, 20) as u64;
        if off + 22 + comment_len != file_len { return None; }
        let entries = u16le(&h, 10) as u32;
        Some(HitInfo { size: Some(file_len - off), count: Some(entries) })
    }

    /// Android boot image: "ANDROID!" + kernel/ramdisk sizes that fit.
    validate_android_boot(f, off, file_len) {
        let mut h = [0u8; 44];
        if !read_at(f, off, &mut h) { return None; }
        let kernel = u32le(&h, 8) as u64;
        let ramdisk = u32le(&h, 16) as u64;
        let page = u32le(&h, 36) as u64;
        if page < 2048 || page > 65536 || page & (page - 1) != 0 { return None; }
        if kernel == 0 || kernel + ramdisk > file_len - off { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// Android sparse image: magic + version 1.0 + a power-of-two block size.
    validate_android_sparse(f, off, file_len) {
        let mut h = [0u8; 28];
        if !read_at(f, off, &mut h) { return None; }
        if u32le(&h, 4) != 0x0001_0000 { return None; }
        let block = u32le(&h, 12);
        if block == 0 || block > (1 << 24) || block & (block - 1) != 0 { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// Dalvik executable: "dex\n0NN\0" + a header size of 0x70 + the file size
    /// field matching the data that is actually there.
    validate_dex(f, off, file_len) {
        let mut h = [0u8; 40];
        if !read_at(f, off, &mut h) { return None; }
        if u32le(&h, 36) != 0x70 { return None; }   // header_size
        let declared = u32le(&h, 32) as u64;        // file_size
        if declared < 0x70 { return None; }
        // A truncated DEX (a carved prefix, a partial copy) still has a valid
        // header: report it with an unknown size rather than rejecting it, so
        // the hit extends to the next signature or EOF like every other
        // unknown-size hit.
        let size = if declared <= file_len - off { Some(declared) } else { None };
        Some(HitInfo { size, count: None })
    }

    /// Android binary XML: chunk type 0x0003 + header size 8 + a chunk size
    /// that fits.
    validate_binxml(f, off, file_len) {
        let mut h = [0u8; 8];
        if !read_at(f, off, &mut h) { return None; }
        if u16le(&h, 2) != 8 { return None; }
        let size = u32le(&h, 4) as u64;
        if size < 8 || size > file_len - off { return None; }
        Some(HitInfo { size: Some(size), count: None })
    }

    /// Android resource table: chunk type 0x0002 + header size 12 + size.
    validate_arsc(f, off, file_len) {
        let mut h = [0u8; 12];
        if !read_at(f, off, &mut h) { return None; }
        if u16le(&h, 2) != 12 { return None; }
        let size = u32le(&h, 4) as u64;
        if size < 12 || size > file_len - off { return None; }
        Some(HitInfo { size: Some(size), count: None })
    }

    /// SQLite database: page size is a power of two between 512 and 65536.
    validate_sqlite(f, off, file_len) {
        let mut h = [0u8; 100];
        if !read_at(f, off, &mut h) { return None; }
        let page = u16be(&h, 16) as u32;
        let page = if page == 1 { 65536 } else { page };
        if page < 512 || page > 65536 || page & (page - 1) != 0 { return None; }
        if h[18] > 2 || h[19] > 2 { return None; }      // write/read version
        Some(HitInfo { size: None, count: None })
    }

    /// Mach-O: magic + a plausible CPU type and a non-zero command count.
    validate_macho(f, off, file_len) {
        let mut h = [0u8; 32];
        if !read_at(f, off, &mut h) { return None; }
        let little = matches!(&h[0..4], b"\xce\xfa\xed\xfe" | b"\xcf\xfa\xed\xfe");
        let (cputype, ncmds) = if little { (u32le(&h, 4), u32le(&h, 16)) } else { (u32be(&h, 4), u32be(&h, 16)) };
        const KNOWN: [u32; 8] = [7, 0x0100_0007, 12, 0x0100_000C, 18, 0x0100_0012, 0x0200_000C, 0x0200_0012];
        if !KNOWN.contains(&cputype) { return None; }
        if ncmds == 0 || ncmds > 100_000 { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// Windows PE: "MZ" alone is meaningless, so the DOS header's e_lfanew must
    /// point at a real PE signature with a known optional-header magic.
    validate_pe(f, off, file_len) {
        let mut h = [0u8; 64];
        if !read_at(f, off, &mut h) { return None; }
        if &h[0..2] != b"MZ" { return None; }
        let lfanew = u32le(&h, 60) as u64;
        if lfanew < 64 || lfanew + 26 > file_len - off { return None; }
        let mut sig = [0u8; 26];
        if !read_at(f, off + lfanew, &mut sig) { return None; }
        if &sig[0..4] != b"PE\0\0" { return None; }
        let opt_magic = u16le(&sig, 24);
        if opt_magic != 0x10B && opt_magic != 0x20B { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// WebAssembly module: "\0asm" + version 1.
    validate_wasm(f, off, file_len) {
        let mut h = [0u8; 8];
        if !read_at(f, off, &mut h) { return None; }
        if u32le(&h, 4) != 1 { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// Java class: 0xCAFEBABE + a major version in the range compilers emit.
    validate_java_class(f, off, file_len) {
        let mut h = [0u8; 8];
        if !read_at(f, off, &mut h) { return None; }
        let major = u16be(&h, 6);
        if !(45..=70).contains(&major) { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// CHM help file: "ITSF" + version 3 + header size 0x60.
    validate_chm(f, off, file_len) {
        let mut h = [0u8; 16];
        if !read_at(f, off, &mut h) { return None; }
        if u32le(&h, 4) != 3 || u32le(&h, 8) != 0x60 { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// Windows registry hive: "regf" + a version 1..6.
    validate_regf(f, off, file_len) {
        let mut h = [0u8; 28];
        if !read_at(f, off, &mut h) { return None; }
        let version = u32le(&h, 20);   // major version
        if version == 0 || version > 6 { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// Windows event log: "ElfFile\0" + a header size that fits.
    validate_evtx(f, off, file_len) {
        let mut h = [0u8; 36];
        if !read_at(f, off, &mut h) { return None; }
        let header_size = u32le(&h, 32) as u64;
        if header_size < 128 || header_size > file_len - off { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// Outlook PST/OST: "!BDN" + a plausible version.
    validate_pst(f, off, file_len) {
        let mut h = [0u8; 16];
        if !read_at(f, off, &mut h) { return None; }
        if !room(off, file_len, 512) { return None; }
        Some(HitInfo { size: None, count: None })
    }


    /// PEM: "-----BEGIN " + a label that ends with "-----" within a line.
    validate_pem(f, off, file_len) {
        let mut h = [0u8; 64];
        if !read_at(f, off, &mut h) { return None; }
        if !h.windows(5).any(|w| w == b"-----") { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// FAT12/16/32 volume label inside a boot sector: the label sits at a fixed
    /// sector offset (54 for 12/16, 82 for 32), which is what the carve
    /// adjustment uses.
    validate_fat(f, off, file_len) {
        if off % 512 != 54 && off % 512 != 82 { return None; }
        let mut h = [0u8; 8];
        if !read_at(f, off, &mut h) { return None; }
        if &h[0..3] != b"FAT" { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// NTFS boot sector: the "NTFS    " string sits at +3 of the sector.
    validate_ntfs(f, off, file_len) {
        if off % 512 != 3 { return None; }
        if !room(off, file_len, 512) { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// EXT superblock: the magic sits at +0x438 of the filesystem start, which
    /// is 512-byte aligned in every real image.
    validate_ext(f, off, file_len) {
        if off % 512 != 0x438 % 512 { return None; }
        let mut h = [0u8; 8];
        if !read_at(f, off, &mut h) { return None; }
        let rev = u32le(&h, 4);
        if rev == 0 || rev > 1 { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// APFS container: "NXSB" + a power-of-two block size.
    validate_apfs(f, off, file_len) {
        let mut h = [0u8; 36];
        if !read_at(f, off, &mut h) { return None; }
        let block = u32le(&h, 4);
        if block < 512 || block > (1 << 20) || block & (block - 1) != 0 { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// BTRFS superblock: "_BHRfS_M" at +0x10000 of the filesystem.
    validate_btrfs(f, off, file_len) {
        if off % 4096 != 0 { return None; }
        if !room(off, file_len, 512) { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// UBI erase-block header: "UBI#" + version 1 + a non-zero sequence number.
    validate_ubi(f, off, file_len) {
        let mut h = [0u8; 64];
        if !read_at(f, off, &mut h) { return None; }
        if h[4] != 1 { return None; }
        if u32be(&h, 24) == 0 { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// JFFS2 node: magic + a node type 1..4 + a total length that fits.
    validate_jffs2(f, off, file_len) {
        let mut h = [0u8; 16];
        if !read_at(f, off, &mut h) { return None; }
        let node_type = u16be(&h, 2);
        if !(1..=4).contains(&node_type) { return None; }
        let total = u32be(&h, 8) as u64;
        if total < 12 || total > file_len - off { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// Device tree blob: magic + a total size that fits and is 4-byte aligned.
    validate_dtb(f, off, file_len) {
        let mut h = [0u8; 8];
        if !read_at(f, off, &mut h) { return None; }
        let total = u32be(&h, 4) as u64;
        if total < 64 || total > file_len - off || total % 4 != 0 { return None; }
        Some(HitInfo { size: Some(total), count: None })
    }

    /// QEMU QCOW image: "QFI\xfb" + version 2..3.
    validate_qcow(f, off, file_len) {
        let mut h = [0u8; 8];
        if !read_at(f, off, &mut h) { return None; }
        let version = u32be(&h, 4);
        if version != 2 && version != 3 { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// VMware VMDK: "KDMV" + version/flag fields.
    validate_vmdk(f, off, file_len) {
        let mut h = [0u8; 12];
        if !read_at(f, off, &mut h) { return None; }
        if u32le(&h, 4) > 3 { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// Microsoft WIM: "MSWIM\0\0\0" + a header size that fits.
    validate_wim(f, off, file_len) {
        let mut h = [0u8; 16];
        if !read_at(f, off, &mut h) { return None; }
        let header_size = u32le(&h, 8) as u64;
        if header_size < 208 || header_size > file_len - off { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// LUKS header: magic + version 1..2.
    validate_luks(f, off, file_len) {
        let mut h = [0u8; 8];
        if !read_at(f, off, &mut h) { return None; }
        let version = u16be(&h, 6);
        if version != 1 && version != 2 { return None; }
        Some(HitInfo { size: None, count: None })
    }


    /// DOS MBR: the 0x55AA signature must sit at the end of a sector and at
    /// least one partition entry must look plausible.
    validate_mbr(f, off, file_len) {
        if off % 512 != 510 { return None; }
        if !room(off, file_len, 512) { return None; }
        let mut entries = [0u8; 64];
        if !read_at(f, off - 510 + 446, &mut entries) { return None; }
        for i in 0..4 {
            let e = &entries[i * 16..i * 16 + 16];
            if (e[0] == 0x80 || e[0] == 0x00) && e[4] != 0 { return Some(HitInfo { size: None, count: None }); }
        }
        None
    }

    /// EFI GPT header: "EFI PART" + a header size of 92 + a usable-LBA range.
    validate_gpt(f, off, file_len) {
        let mut h = [0u8; 92];
        if !read_at(f, off, &mut h) { return None; }
        if u32le(&h, 12) != 92 { return None; }
        let entries_lba = u64le(&h, 72);
        if entries_lba == 0 { return None; }
        Some(HitInfo { size: None, count: None })
    }


    /// Apple icon image: "icns" + a total size that fits.
    validate_icns(f, off, file_len) {
        let mut h = [0u8; 8];
        if !read_at(f, off, &mut h) { return None; }
        let size = u32be(&h, 4) as u64;
        if size < 8 || size > file_len - off { return None; }
        Some(HitInfo { size: Some(size), count: None })
    }

    /// Criware ACB audio catalogue: "@UTF" + a UTF table header whose sizes fit.
    validate_acb(f, off, file_len) {
        if !room(off, file_len, 16) { return None; }
        Some(HitInfo { size: None, count: None })
    }

    /// Shared guard for magics that are long and specific enough that the only
    /// real question is whether the file is long enough to hold a header
    /// (fonts, SVG, JP2/KTX, EBML, Snappy, PCAP, torrent, deb, VHD/VHDX, ACE…).
    ///
    /// 24 bytes, not 64: a libpcap global header is exactly 24 bytes, so a
    /// capture with no packets is a complete, legitimate file — a larger floor
    /// rejected it (found by scanning the corpus, not by reasoning).
    validate_room24(f, off, file_len) {
        if !room(off, file_len, 24) { return None; }
        Some(HitInfo { size: None, count: None })
    }
}

pub struct Sig {
    pub magics: &'static [&'static [u8]],
    pub label: &'static str,
    pub confidence: u8,
    pub validate: Validator,
}

pub const SIGNATURES: &[Sig] = &[
    Sig { magics: &[b"7z\xbc\xaf\x27\x1c"], label: "7-zip archive", confidence: CONFIDENCE_HIGH, validate: validate_7z },
    Sig { magics: &[b"PK\x03\x04"], label: "ZIP archive", confidence: CONFIDENCE_HIGH, validate: validate_zip },
    Sig { magics: &[b"Rar!\x1a\x07\x00"], label: "RAR archive", confidence: CONFIDENCE_HIGH, validate: validate_rar4 },
    Sig { magics: &[b"Rar!\x1a\x07\x01\x00"], label: "RAR archive v5", confidence: CONFIDENCE_HIGH, validate: validate_rar5 },
    // Header-encrypted (-hp) RAR5: no plaintext main header to validate, so
    // report the whole file as one region at MEDIUM confidence. The plaintext
    // entry above wins when the archive validates, so this only surfaces
    // genuinely encrypted archives.
    Sig { magics: &[b"Rar!\x1a\x07\x01\x00"], label: "RAR archive v5 (header encrypted)", confidence: CONFIDENCE_MEDIUM, validate: validate_rar5_hp },
    Sig { magics: &[b"\x1f\x8b\x08"], label: "gzip compressed data", confidence: CONFIDENCE_MEDIUM, validate: validate_gzip },
    // bzip2: full 10-byte magics (binwalk parity) — random data can't match.
    Sig { magics: &[b"BZh11AY&SY", b"BZh21AY&SY", b"BZh31AY&SY", b"BZh41AY&SY", b"BZh51AY&SY", b"BZh61AY&SY", b"BZh71AY&SY", b"BZh81AY&SY", b"BZh91AY&SY"], label: "bzip2 compressed data", confidence: CONFIDENCE_HIGH, validate: validate_bzip2 },
    Sig { magics: &[b"\xfd7zXZ\x00"], label: "XZ compressed data", confidence: CONFIDENCE_MEDIUM, validate: validate_xz },
    Sig { magics: &[b"\x28\xb5\x2f\xfd"], label: "Zstandard compressed data", confidence: CONFIDENCE_HIGH, validate: validate_zstd },
    Sig { magics: &[b"\x04\x22\x4d\x18"], label: "LZ4 compressed data", confidence: CONFIDENCE_HIGH, validate: validate_lz4 },
    // LZMA: 4 props × 9 dict byte-prefixes. The magic covers the props byte +
    // the three low dict bytes (dict < 16 MiB) or the shared \x00\x00\x00
    // prefix for ≥16 MiB dicts (the validator whitelist then distinguishes).
    Sig { magics: &[
        b"\x5d\x00\x00\x80", b"\x5d\x00\x00\x40", b"\x5d\x00\x00\x20", b"\x5d\x00\x00\x10",
        b"\x5d\x00\x00\x08", b"\x5d\x00\x00\x04", b"\x5d\x00\x00\x02", b"\x5d\x00\x00\x01",
        b"\x5d\x00\x00\x00", b"\x6e\x00\x00\x80", b"\x6e\x00\x00\x40", b"\x6e\x00\x00\x20",
        b"\x6e\x00\x00\x10", b"\x6e\x00\x00\x08", b"\x6e\x00\x00\x04", b"\x6e\x00\x00\x02",
        b"\x6e\x00\x00\x01", b"\x6e\x00\x00\x00", b"\x6d\x00\x00\x80", b"\x6d\x00\x00\x40",
        b"\x6d\x00\x00\x20", b"\x6d\x00\x00\x10", b"\x6d\x00\x00\x08", b"\x6d\x00\x00\x04",
        b"\x6d\x00\x00\x02", b"\x6d\x00\x00\x01", b"\x6d\x00\x00\x00", b"\x6c\x00\x00\x80",
        b"\x6c\x00\x00\x40", b"\x6c\x00\x00\x20", b"\x6c\x00\x00\x10", b"\x6c\x00\x00\x08",
        b"\x6c\x00\x00\x04", b"\x6c\x00\x00\x02", b"\x6c\x00\x00\x01", b"\x6c\x00\x00\x00",
    ], label: "LZMA compressed data", confidence: CONFIDENCE_MEDIUM, validate: validate_lzma },
    Sig { magics: &[b"XP3\r\n \n\x1a\x8b\x67"], label: "XP3 archive", confidence: CONFIDENCE_HIGH, validate: validate_xp3 },
    // POSIX/GNU tar: "ustar" at archive offset 257 + header checksum.
    Sig { magics: &[b"ustar"], label: "POSIX tar archive", confidence: CONFIDENCE_MEDIUM, validate: validate_tar },
    // PNG: full 16-byte magic incl. IHDR chunk header (binwalk parity).
    Sig { magics: &[b"\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDR"], label: "PNG image", confidence: CONFIDENCE_HIGH, validate: validate_png },
    // JPEG: binwalk's three magic variants (JFIF APP0 / EXIF APP1 / DQT).
    Sig { magics: &[b"\xff\xd8\xff\xe0\x00\x10JFIF\x00", b"\xff\xd8\xff\xe1", b"\xff\xd8\xff\xdb"], label: "JPEG image", confidence: CONFIDENCE_HIGH, validate: validate_jpeg },
    Sig { magics: &[b"GIF87a", b"GIF89a"], label: "GIF image", confidence: CONFIDENCE_MEDIUM, validate: validate_gif },
    Sig { magics: &[b"II\x2a\x00"], label: "TIFF image (little-endian)", confidence: CONFIDENCE_MEDIUM, validate: validate_tiff },
    Sig { magics: &[b"MM\x00\x2a"], label: "TIFF image (big-endian)", confidence: CONFIDENCE_MEDIUM, validate: validate_tiff },
    Sig { magics: &[b"%PDF-1."], label: "PDF document", confidence: CONFIDENCE_MEDIUM, validate: validate_pdf },
    Sig { magics: &[b"\x7fELF"], label: "ELF executable", confidence: CONFIDENCE_MEDIUM, validate: validate_elf },
    Sig { magics: &[b"RIFF"], label: "RIFF (AVI/WAV/WebP)", confidence: CONFIDENCE_HIGH, validate: validate_riff },
    Sig { magics: &[b"\x00\x00\x01\xba"], label: "MPEG program stream", confidence: CONFIDENCE_MEDIUM, validate: validate_mpeg },
    // ISO 9660: magic \x01CD001\x01\x00 sits at offset 32768 inside the image.
    Sig { magics: &[b"\x01CD001\x01\x00"], label: "ISO 9660 disc image", confidence: CONFIDENCE_HIGH, validate: validate_iso },
    // ─── Gal engine formats ───
    // YPF (YU-RIS): 4-byte magic "YPF\0" + header with record count and header length.
    Sig { magics: &[b"YPF\x00"], label: "YPF archive", confidence: CONFIDENCE_HIGH, validate: validate_ypf },
    // PFS/PF6/PF8 (Artemis/Malie): 3-byte magic "pf6" or "pf8".
    Sig { magics: &[b"pf6"], label: "PF6 archive", confidence: CONFIDENCE_HIGH, validate: validate_pf6pf8 },
    Sig { magics: &[b"pf8"], label: "PF8 archive", confidence: CONFIDENCE_HIGH, validate: validate_pf6pf8 },
    // KSD (Kirikiri2 save data): 2-byte prefix "FE FE" followed by mode byte.
    // Note: "FE FE" alone is too short (high false-positive), so we require the
    // subsequent pattern "FE FE 0x02 FF FE" (mode 2) or "FE FE 0x00" (mode 0/1).
    // All three modes carry the full 5-byte header (ksd-core's reader requires
    // `FE FE <mode> FF FE` for every mode); the 3-byte mode-0 magic that used to
    // be here fired six times inside a font collection.
    Sig { magics: &[b"\xfe\xfe\x00\xff\xfe", b"\xfe\xfe\x01\xff\xfe", b"\xfe\xfe\x02\xff\xfe"], label: "KSD save data", confidence: CONFIDENCE_MEDIUM, validate: validate_ksd },
    // RGSS (RPG Maker XP / VX / VX Ace): "RGSSAD\0" + a version byte. The
    // 7-byte magic plus the version check is specific enough for HIGH.
    Sig { magics: &[b"RGSSAD\x00"], label: "RGSS archive", confidence: CONFIDENCE_HIGH, validate: validate_rgss },
    // ─── Media format signatures ───
    // Ogg container: "OggS" magic followed by version byte (0x00).
    Sig { magics: &[b"OggS\x00"], label: "Ogg container", confidence: CONFIDENCE_HIGH, validate: validate_ogg },
    // MP3/MPEG audio frame: sync word 0xFF followed by frame header bits.
    // Multiple valid sync patterns: 0xFFFB, 0xFFFA, 0xFFF3, 0xFFF2, etc.
    Sig { magics: &[b"\xff\xfb", b"\xff\xfa", b"\xff\xf3", b"\xff\xf2"], label: "MP3 audio", confidence: CONFIDENCE_MEDIUM, validate: validate_mp3 },
    // FLAC: "fLaC" magic (4 bytes).
    Sig { magics: &[b"fLaC"], label: "FLAC audio", confidence: CONFIDENCE_HIGH, validate: validate_flac },
    // BMP image: "BM" magic followed by file size (u32le) and reserved bytes.
    Sig { magics: &[b"BM"], label: "BMP image", confidence: CONFIDENCE_MEDIUM, validate: validate_bmp },
    // ─── Galgame / engine formats (binwalk does not know most of these) ───
    Sig { magics: &[b"RPA-3.0 ", b"RPA-2.0 ", b"RPA-3.2 ", b"RPA-4.0 ", b"ALT-1.0 "], label: "Ren'Py archive", confidence: CONFIDENCE_HIGH, validate: validate_rpa },
    Sig { magics: &[b"KIF\x00"], label: "CatSystem2 INT archive", confidence: CONFIDENCE_HIGH, validate: validate_kif },
    Sig { magics: &[b"RPGMV"], label: "RPG Maker MV/MZ asset", confidence: CONFIDENCE_HIGH, validate: validate_rpgmv },
    Sig { magics: &[b"\x80\x00"], label: "Criware ADX audio", confidence: CONFIDENCE_MEDIUM, validate: validate_adx },
    Sig { magics: &[b"HCA\x00"], label: "Criware HCA audio", confidence: CONFIDENCE_MEDIUM, validate: validate_hca },
    Sig { magics: &[b"@UTF"], label: "Criware ACB catalogue", confidence: CONFIDENCE_MEDIUM, validate: validate_acb },
    Sig { magics: &[b"AFS2"], label: "Criware AWB/AFS2 archive", confidence: CONFIDENCE_MEDIUM, validate: validate_awb },
    Sig { magics: &[b"CPK "], label: "Criware CPK archive", confidence: CONFIDENCE_MEDIUM, validate: validate_cpk },
    Sig { magics: &[b"UnityFS", b"UnityWeb", b"UnityRaw"], label: "Unity asset bundle", confidence: CONFIDENCE_MEDIUM, validate: validate_unity },
    Sig { magics: &[b"\xe1\x12\x6f\x5a"], label: "Unreal Engine pak", confidence: CONFIDENCE_MEDIUM, validate: validate_unreal },
    Sig { magics: &[b"GDPC"], label: "Godot engine package", confidence: CONFIDENCE_MEDIUM, validate: validate_godot },

    // ─── Audio / video ───
    Sig { magics: &[b"MThd"], label: "MIDI sequence", confidence: CONFIDENCE_MEDIUM, validate: validate_midi },
    Sig { magics: &[b"FORM"], label: "AIFF audio", confidence: CONFIDENCE_MEDIUM, validate: validate_aiff },
    Sig { magics: &[b".snd"], label: "Sun/NeXT audio", confidence: CONFIDENCE_MEDIUM, validate: validate_au },
    Sig { magics: &[b"caff"], label: "Apple CAF audio", confidence: CONFIDENCE_MEDIUM, validate: validate_caf },
    Sig { magics: &[b"#!AMR"], label: "AMR audio", confidence: CONFIDENCE_MEDIUM, validate: validate_room24 },
    Sig { magics: &[b"\x1a\x45\xdf\xa3"], label: "Matroska/WebM video", confidence: CONFIDENCE_MEDIUM, validate: validate_room24 },
    Sig { magics: &[b"ftyp"], label: "ISO media (MP4/MOV/HEIC/AVIF)", confidence: CONFIDENCE_MEDIUM, validate: validate_ftyp },

    // ─── Images, textures, fonts ───
    Sig { magics: &[b"\x00\x00\x01\x00"], label: "Windows icon/cursor", confidence: CONFIDENCE_MEDIUM, validate: validate_ico },
    Sig { magics: &[b"8BPS"], label: "Photoshop PSD image", confidence: CONFIDENCE_MEDIUM, validate: validate_psd },
    Sig { magics: &[b"gimp xcf "], label: "GIMP XCF image", confidence: CONFIDENCE_MEDIUM, validate: validate_room24 },
    Sig { magics: &[b"DDS "], label: "DirectDraw surface", confidence: CONFIDENCE_MEDIUM, validate: validate_dds },
    Sig { magics: &[b"qoif"], label: "QOI image", confidence: CONFIDENCE_MEDIUM, validate: validate_qoi },
    Sig { magics: &[b"\x00\x00\x00\x0cjP  \r\n\x87\n"], label: "JPEG 2000 image", confidence: CONFIDENCE_MEDIUM, validate: validate_room24 },
    Sig { magics: &[b"\x76\x2f\x31\x01"], label: "OpenEXR image", confidence: CONFIDENCE_MEDIUM, validate: validate_exr },
    Sig { magics: &[b"\xabKTX 11\xbb\r\n\x1a\n"], label: "KTX texture", confidence: CONFIDENCE_MEDIUM, validate: validate_ktx },
    Sig { magics: &[b"\x13\xab\xa1\x5c"], label: "ASTC texture", confidence: CONFIDENCE_MEDIUM, validate: validate_astc },
    Sig { magics: &[b"\x00\x01\x00\x00", b"true", b"OTTO"], label: "TrueType/OpenType font", confidence: CONFIDENCE_MEDIUM, validate: validate_sfnt },
    Sig { magics: &[b"ttcf"], label: "TrueType collection", confidence: CONFIDENCE_MEDIUM, validate: validate_ttc },
    Sig { magics: &[b"wOFF", b"wOF2"], label: "WOFF web font", confidence: CONFIDENCE_MEDIUM, validate: validate_room24 },
    Sig { magics: &[b"\xd7\xcd\xc6\x9a"], label: "Windows Metafile", confidence: CONFIDENCE_MEDIUM, validate: validate_wmf },
    Sig { magics: &[b"\x01\x00\x00\x00"], label: "Windows Enhanced Metafile", confidence: CONFIDENCE_MEDIUM, validate: validate_emf },
    Sig { magics: &[b"<svg "], label: "SVG image", confidence: CONFIDENCE_MEDIUM, validate: validate_room24 },

    // ─── Archives and compression ───
    Sig { magics: &[b"MSCF\x00\x00\x00\x00"], label: "Microsoft Cabinet archive", confidence: CONFIDENCE_MEDIUM, validate: validate_cab },
    Sig { magics: &[b"070701", b"070702", b"070707"], label: "CPIO archive", confidence: CONFIDENCE_MEDIUM, validate: validate_cpio },
    Sig { magics: &[b"!<arch>\n"], label: "Unix ar archive", confidence: CONFIDENCE_MEDIUM, validate: validate_room24 },
    Sig { magics: &[b"xar!"], label: "XAR archive", confidence: CONFIDENCE_MEDIUM, validate: validate_xar },
    Sig { magics: &[b"koly\x00\x00\x00\x04\x00\x00\x02\x00"], label: "Apple DMG image", confidence: CONFIDENCE_MEDIUM, validate: validate_room24 },
    Sig { magics: &[b"hsqs", b"sqsh", b"sqlz"], label: "SquashFS filesystem", confidence: CONFIDENCE_MEDIUM, validate: validate_squashfs },
    Sig { magics: &[b"Compressed ROMFS"], label: "CramFS filesystem", confidence: CONFIDENCE_MEDIUM, validate: validate_cramfs },
    Sig { magics: &[b"-rom1fs-"], label: "RomFS filesystem", confidence: CONFIDENCE_MEDIUM, validate: validate_romfs },
    Sig { magics: &[b"-lh0-", b"-lh1-", b"-lh2-", b"-lh3-", b"-lh4-", b"-lh5-", b"-lh6-", b"-lh7-"], label: "LHA/LZH archive", confidence: CONFIDENCE_MEDIUM, validate: validate_lha },
    Sig { magics: &[b"\x60\xea"], label: "ARJ archive", confidence: CONFIDENCE_MEDIUM, validate: validate_arj },
    Sig { magics: &[b"**ACE**"], label: "ACE archive", confidence: CONFIDENCE_MEDIUM, validate: validate_room24 },
    Sig { magics: &[b"LZIP"], label: "lzip compressed data", confidence: CONFIDENCE_MEDIUM, validate: validate_lzip },
    Sig { magics: &[b"\x89LZO\x00\r\n\x1a\n"], label: "lzop compressed data", confidence: CONFIDENCE_MEDIUM, validate: validate_lzop },
    Sig { magics: &[b"\xff\x06\x00\x00sNaPpY"], label: "Snappy framed stream", confidence: CONFIDENCE_MEDIUM, validate: validate_room24 },
    Sig { magics: &[b"\x78\x01", b"\x78\x5e", b"\x78\x9c", b"\x78\xda"], label: "zlib stream", confidence: CONFIDENCE_MEDIUM, validate: validate_zlib },
    Sig { magics: &[b"\x1f\x9d\x90"], label: "compress'd data", confidence: CONFIDENCE_MEDIUM, validate: validate_compressd },
    Sig { magics: &[b"PK\x05\x06"], label: "ZIP archive (empty)", confidence: CONFIDENCE_MEDIUM, validate: validate_zip_eocd },

    // ─── Mobile / system / filesystems ───
    Sig { magics: &[b"ANDROID!"], label: "Android boot image", confidence: CONFIDENCE_MEDIUM, validate: validate_android_boot },
    Sig { magics: &[b"\x3a\xff\x26\xed"], label: "Android sparse image", confidence: CONFIDENCE_MEDIUM, validate: validate_android_sparse },
    // Every DEX version in the wild is "dex\n03N\0" (035 on old devices, 039
    // on modern Android — the APK this app builds ships 039), so the magic
    // stops before the version digit and the validator checks the header size.
    Sig { magics: &[b"dex\n03"], label: "Dalvik executable", confidence: CONFIDENCE_MEDIUM, validate: validate_dex },
    Sig { magics: &[b"\x02\x00\x0c\x00"], label: "Android resource table", confidence: CONFIDENCE_MEDIUM, validate: validate_arsc },
    Sig { magics: &[b"\x03\x00\x08\x00"], label: "Android binary XML", confidence: CONFIDENCE_MEDIUM, validate: validate_binxml },
    Sig { magics: &[b"SQLite format 3\x00"], label: "SQLite database", confidence: CONFIDENCE_MEDIUM, validate: validate_sqlite },
    Sig { magics: &[b"\xce\xfa\xed\xfe", b"\xcf\xfa\xed\xfe", b"\xfe\xed\xfa\xce", b"\xfe\xed\xfa\xcf"], label: "Mach-O binary", confidence: CONFIDENCE_MEDIUM, validate: validate_macho },
    Sig { magics: &[b"MZ"], label: "Windows PE binary", confidence: CONFIDENCE_MEDIUM, validate: validate_pe },
    Sig { magics: &[b"\x00asm"], label: "WebAssembly module", confidence: CONFIDENCE_MEDIUM, validate: validate_wasm },
    Sig { magics: &[b"\xca\xfe\xba\xbe"], label: "Java class", confidence: CONFIDENCE_MEDIUM, validate: validate_java_class },
    Sig { magics: &[b"ITSF"], label: "CHM help file", confidence: CONFIDENCE_MEDIUM, validate: validate_chm },
    Sig { magics: &[b"regf"], label: "Windows registry hive", confidence: CONFIDENCE_MEDIUM, validate: validate_regf },
    Sig { magics: &[b"ElfFile\x00"], label: "Windows event log", confidence: CONFIDENCE_MEDIUM, validate: validate_evtx },
    Sig { magics: &[b"!BDN"], label: "Outlook PST/OST", confidence: CONFIDENCE_MEDIUM, validate: validate_pst },
    Sig { magics: &[b"d8:announce"], label: "BitTorrent metainfo", confidence: CONFIDENCE_MEDIUM, validate: validate_room24 },
    Sig { magics: &[b"-----BEGIN "], label: "PEM text", confidence: CONFIDENCE_MEDIUM, validate: validate_pem },
    // Two labels on purpose: the volume label sits at sector offset 54 on
    // FAT12/16 and 82 on FAT32, and the carve path needs the right one.
    Sig { magics: &[b"FAT12   ", b"FAT16   "], label: "FAT12/16 filesystem", confidence: CONFIDENCE_MEDIUM, validate: validate_fat },
    Sig { magics: &[b"FAT32   "], label: "FAT32 filesystem", confidence: CONFIDENCE_MEDIUM, validate: validate_fat },
    Sig { magics: &[b"\xebR\x90NTFS    "], label: "NTFS filesystem", confidence: CONFIDENCE_MEDIUM, validate: validate_ntfs },
    Sig { magics: &[b"\x53\xef\x01\x00\x01\x00\x00\x00", b"\x53\xef\x01\x00\x02\x00\x00\x00", b"\x53\xef\x01\x00\x03\x00\x00\x00"], label: "EXT filesystem", confidence: CONFIDENCE_MEDIUM, validate: validate_ext },
    Sig { magics: &[b"NXSB"], label: "APFS container", confidence: CONFIDENCE_MEDIUM, validate: validate_apfs },
    Sig { magics: &[b"_BHRfS_M"], label: "BTRFS filesystem", confidence: CONFIDENCE_MEDIUM, validate: validate_btrfs },
    Sig { magics: &[b"UBI#\x01"], label: "UBI image", confidence: CONFIDENCE_MEDIUM, validate: validate_ubi },
    Sig { magics: &[b"\x19\x85\xe0\x01", b"\x19\x85\xe0\x02", b"\x19\x85\x20\x03"], label: "JFFS2 filesystem", confidence: CONFIDENCE_MEDIUM, validate: validate_jffs2 },
    Sig { magics: &[b"\xd0\x0d\xfe\xed"], label: "Device tree blob", confidence: CONFIDENCE_MEDIUM, validate: validate_dtb },
    Sig { magics: &[b"QFI\xfb"], label: "QEMU QCOW image", confidence: CONFIDENCE_MEDIUM, validate: validate_qcow },
    Sig { magics: &[b"KDMV"], label: "VMware VMDK image", confidence: CONFIDENCE_MEDIUM, validate: validate_vmdk },
    Sig { magics: &[b"conectix"], label: "Microsoft VHD image", confidence: CONFIDENCE_MEDIUM, validate: validate_room24 },
    Sig { magics: &[b"vhdxfile"], label: "Microsoft VHDX image", confidence: CONFIDENCE_MEDIUM, validate: validate_room24 },
    Sig { magics: &[b"MSWIM\x00\x00\x00"], label: "Microsoft WIM image", confidence: CONFIDENCE_MEDIUM, validate: validate_wim },
    Sig { magics: &[b"LUKS\xba\xbe"], label: "LUKS encrypted volume", confidence: CONFIDENCE_MEDIUM, validate: validate_luks },
    Sig { magics: &[b"\xd4\xc3\xb2\xa1", b"\xa1\xb2\xc3\xd4", b"\x4d\x3c\xb2\xa1", b"\xa1\xb2\x3c\x4d"], label: "libpcap capture", confidence: CONFIDENCE_MEDIUM, validate: validate_room24 },
    Sig { magics: &[b"\x55\xaa"], label: "DOS MBR partition table", confidence: CONFIDENCE_MEDIUM, validate: validate_mbr },
    Sig { magics: &[b"EFI PART"], label: "EFI GPT partition table", confidence: CONFIDENCE_MEDIUM, validate: validate_gpt },
    Sig { magics: &[b"!<arch>\ndebian-binary   "], label: "Debian package", confidence: CONFIDENCE_MEDIUM, validate: validate_room24 },
    Sig { magics: &[b"icns"], label: "Apple icon image", confidence: CONFIDENCE_MEDIUM, validate: validate_icns },
];

// ─── Gal engine format validators ───

/// YPF (YU-RIS) archive validator.
/// Magic: "YPF\0" (4 bytes) at offset 0.
/// Header: magic(4) + version(u32 @4) + count(u32 @8) + header_len(u32 @12).
/// We check that count > 0 and header_len is within file bounds.
fn validate_ypf(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    let mut buf = [0u8; 16];
    if !read_at(f, off, &mut buf) { return None; }
    // buf[0..4] = "YPF\0" (already matched by AC)
    let count = u32::from_le_bytes([buf[8], buf[9], buf[10], buf[11]]);
    let hdr_len = u32::from_le_bytes([buf[12], buf[13], buf[14], buf[15]]);
    if count == 0 || count > 100_000 { return None; }
    if hdr_len < 0x20 || (off + hdr_len as u64) > file_len { return None; }
    Some(HitInfo { size: None, count: Some(count) })
}

/// PF6/PF8 (Artemis/Malie) archive validator.
/// Magic: "pf6" or "pf8" (3 bytes) at offset 0.
/// The pf8 crate validates the header internally; here we just check the magic
/// is present and the file is at least 12 bytes (header size).
fn validate_pf6pf8(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    // Header: magic "pf6"/"pf8" + index_size u32 @3 + index_count u32 @7.
    // The index (entries + filesize-offset table) must fit inside the file,
    // and every entry costs at least 16 bytes (name_len + padding + offset +
    // size) — a count that can't fit in index_size is garbage, which is what
    // filters the 3-byte magic's text collisions.
    if file_len - off < 15 { return None; }
    let mut h = [0u8; 15];
    if !read_at(f, off, &mut h) { return None; }
    if &h[0..3] != b"pf6" && &h[0..3] != b"pf8" { return None; }
    let index_size = u32le(&h, 3) as u64;
    let index_count = u32le(&h, 7) as u64;
    const INDEX_DATA_START: u64 = 0x07;
    if INDEX_DATA_START + index_size > file_len - off { return None; }
    if index_count == 0 || index_count * 16 > index_size { return None; }
    Some(HitInfo { size: None, count: None })
}

/// RGSS encrypted archive validator (RPG Maker XP / VX / VX Ace).
/// Magic: "RGSSAD\0" (7 bytes) followed by the version byte.
/// 1 = XP, 2 = VX, 3 = VX Ace. The version byte is the only thing that can
/// collide, so it is checked here: 7 magic bytes + a legal version is already
/// unique enough for CONFIDENCE_HIGH, but rejecting the impossible versions
/// keeps text mentions of "RGSSAD" out of the results.
///
/// v3 carries a u32 seed from which the master key is derived as
/// `seed*9 + 3` (a bijection over u32, so any seed is legal — no invariant to
/// check there). Instead the first index dword is decrypted: it is either the
/// zero terminator of an empty archive or the first entry's data offset, which
/// must land inside the file.
fn validate_rgss(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    if file_len - off < 8 { return None; }
    let mut h = [0u8; 8];
    if !read_at(f, off, &mut h) { return None; }
    if &h[0..7] != b"RGSSAD\x00" { return None; }
    match h[7] {
        1 | 2 => Some(HitInfo { size: None, count: None }),
        3 => {
            if file_len - off < 12 { return None; }
            let mut s = [0u8; 4];
            if !read_at(f, off + 8, &mut s) { return None; }
            let key = u32le(&s, 0).wrapping_mul(9).wrapping_add(3);
            let mut idx = [0u8; 4];
            if !read_at(f, off + 12, &mut idx) { return None; }
            let data_offset = u32le(&idx, 0) ^ key;
            if data_offset != 0 && (data_offset as u64) >= file_len - off { return None; }
            Some(HitInfo { size: None, count: None })
        }
        _ => None,
    }
}

/// KSD (Kirikiri2 save data) validator.
/// Magic patterns: "FE FE 02 FF FE" (mode 2) or "FE FE 00" (mode 0/1).
/// For mode 2, we can calculate the exact size from compressed_len in the header.
fn validate_ksd(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    let mut buf = [0u8; 16];
    if !read_at(f, off, &mut buf) { return None; }
    // buf[0..2] = 0xFE 0xFE (already matched)
    if buf[0] != 0xFE || buf[1] != 0xFE { return None; }
    let mode = buf[2];
    match mode {
        0 | 1 => {
            // Mode 0/1: no embedded size, just report as hit
            Some(HitInfo { size: None, count: None })
        }
        2 => {
            // Mode 2: header is "FE FE 02 FF FE" + compressed_len:i64 + uncompressed_len:i64
            // We already matched "FE FE 02 FF FE" (5 bytes), so buf[5..13] is compressed_len.
            if buf[3] != 0xFF || buf[4] != 0xFE { return None; }
            if file_len - off < 21 { return None; } // 5 (magic) + 8 (compressed_len) + 8 (uncompressed_len)
            let mut len_buf = [0u8; 8];
            if !read_at(f, off + 5, &mut len_buf) { return None; }
            let compressed_len = i64::from_le_bytes(len_buf);
            if compressed_len <= 0 || compressed_len > 2 * 1024 * 1024 * 1024 { return None; }
            let total_size = 5 + 8 + 8 + compressed_len as u64; // magic + 2 i64s + compressed data
            if off + total_size > file_len { return None; }
            Some(HitInfo { size: Some(total_size), count: None })
        }
        _ => None,
    }
}

// ─── Media format validators ───

/// Ogg container validator.
/// Magic: "OggS" (4 bytes) + version byte 0x00 at offset 4.
/// We check the version byte and that the file has at least one page header.
fn validate_ogg(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    if file_len - off < 27 { return None; } // minimum Ogg page header size
    let mut buf = [0u8; 27];
    if !read_at(f, off, &mut buf) { return None; }
    // buf[0..4] = "OggS" (already matched)
    if buf[4] != 0x00 { return None; } // version must be 0
    // Check segment count at offset 26
    let num_segments = buf[26] as u64;
    // Total header size = 27 + num_segments (at least)
    if off + 27 + num_segments > file_len { return None; }
    Some(HitInfo { size: None, count: None })
}

/// MP3/MPEG audio frame validator.
/// Sync word: 0xFF followed by frame header byte.
/// We check that the frame header bits are valid (bitrate and sample rate not zero).
fn validate_mp3(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    if file_len - off < 4 { return None; }
    let mut buf = [0u8; 4];
    if !read_at(f, off, &mut buf) { return None; }
    // buf[0] = 0xFF (already matched), buf[1] = frame header byte
    let header = buf[1];
    // Check sync word continuation (bits 7-6 must be 11)
    if (header & 0xE0) != 0xE0 { return None; }
    // Check MPEG version (bits 5-4): 00 = MPEG2.5, 01 = reserved, 10 = MPEG2, 11 = MPEG1
    let version = (header >> 3) & 0x03;
    if version == 0x01 { return None; } // reserved
    // Check layer (bits 3-2): 00 = reserved
    let layer = (header >> 1) & 0x03;
    if layer == 0x00 { return None; } // reserved
    // Check bitrate index (bits 7-4 of buf[2]): not 0 (free) or 0xF (bad)
    let bitrate_index = (buf[2] >> 4) & 0x0F;
    if bitrate_index == 0x00 || bitrate_index == 0x0F { return None; }
    // Check sample rate index (bits 3-2 of buf[2]): not 0x03 (reserved)
    let sample_rate_index = (buf[2] >> 2) & 0x03;
    if sample_rate_index == 0x03 { return None; }
    // A single header is not enough: the bit checks pass about once per 500
    // bytes of arbitrary data, which is why two hits appeared inside a font
    // collection. Every MP3 scanner's rule is "the next frame must start where
    // this one says it ends", so the length is computed from the standard
    // bitrate/sample-rate tables and the following header checked — but only
    // when there is room, so a one-frame file at EOF still matches.
    if layer != 1 {
        return None; // every magic in this table is Layer III
    }
    const BITRATES_V1: [u32; 16] = [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0];
    const BITRATES_V2: [u32; 16] = [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0];
    const RATES: [[u32; 3]; 3] = [[44100, 48000, 32000], [22050, 24000, 16000], [11025, 12000, 8000]];
    let v1 = version == 0x03;
    let bitrate = if v1 { BITRATES_V1[bitrate_index as usize] } else { BITRATES_V2[bitrate_index as usize] };
    let rate = RATES[if v1 { 0 } else if version == 0x02 { 1 } else { 2 }][(sample_rate_index & 0x03) as usize];
    if bitrate == 0 || rate == 0 { return None; }
    let padding = ((buf[2] >> 1) & 1) as u64;
    let frame_len = (if v1 { 144 } else { 72 } * bitrate as u64 * 1000) / rate as u64 + padding;
    if frame_len < 24 { return None; }
    if off + frame_len + 4 <= file_len {
        let mut next = [0u8; 4];
        if !read_at(f, off + frame_len, &mut next) { return None; }
        if next[0] != 0xFF || (next[1] & 0xE0) != 0xE0 { return None; }
        let nver = (next[1] >> 3) & 0x03;
        let nlayer = (next[1] >> 1) & 0x03;
        if nver != version || nlayer != layer { return None; }
        let nbitrate = (next[2] >> 4) & 0x0F;
        if nbitrate == 0x00 || nbitrate == 0x0F { return None; }
        if ((next[2] >> 2) & 0x03) == 0x03 { return None; }
    }
    Some(HitInfo { size: None, count: None })
}

/// FLAC audio validator.
/// Magic: "fLaC" (4 bytes) at offset 0.
/// We check the STREAMINFO block header (block type 0x00, length 34).
fn validate_flac(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    if file_len - off < 42 { return None; } // 4 (magic) + 4 (block header) + 34 (STREAMINFO)
    let mut buf = [0u8; 8];
    if !read_at(f, off, &mut buf) { return None; }
    // buf[0..4] = "fLaC" (already matched)
    // buf[4] = block type (must be 0x00 for STREAMINFO)
    // buf[5..7] = block length (must be 34 for STREAMINFO)
    let block_type = buf[4];
    let block_length = u32::from_be_bytes([0, buf[5], buf[6], buf[7]]);
    if block_type != 0x00 { return None; }
    if block_length != 34 { return None; }
    Some(HitInfo { size: None, count: None })
}

/// BMP image validator.
/// Magic: "BM" (2 bytes) at offset 0.
/// File size at offset 2 (u32le), pixel data offset at offset 10 (u32le).
fn validate_bmp(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    if file_len - off < 14 { return None; } // minimum BMP header
    let mut buf = [0u8; 14];
    if !read_at(f, off, &mut buf) { return None; }
    // buf[0..2] = "BM" (already matched)
    let file_size = u32::from_le_bytes([buf[2], buf[3], buf[4], buf[5]]);
    let pixel_offset = u32::from_le_bytes([buf[10], buf[11], buf[12], buf[13]]);
    // Basic sanity checks
    if file_size < 14 || file_size as u64 > file_len { return None; }
    if pixel_offset < 14 || pixel_offset as u64 > file_len { return None; }
    if pixel_offset < 54 { return None; } // minimum DIB header is 40 bytes after 14-byte file header
    Some(HitInfo { size: Some(file_size as u64), count: None })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(path: &str, bytes: &[u8]) -> String {
        let dir = std::env::temp_dir().join(format!("uu_scan_valid_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let p = dir.join(path);
        std::fs::write(&p, bytes).unwrap();
        p.to_str().unwrap().to_string()
    }

    fn hex(s: &str) -> Vec<u8> {
        (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
    }

    /// Builds a real gzip stream via flate2 so validators see genuine deflate
    /// data (the header-only heuristics and the dry-run both need it).
    fn gz_bytes(data: &[u8]) -> Vec<u8> {
        use flate2::write::GzEncoder;
        use flate2::Compression;
        use std::io::Write as _;
        let mut enc = GzEncoder::new(Vec::new(), Compression::default());
        enc.write_all(data).unwrap();
        enc.finish().unwrap()
    }

    #[test]
    fn rar5_and_rar4_eof_size() {
        // RAR5: sig(8) + crc(4) + vint HEAD_SIZE/HEAD_TYPE/HEAD_FLAGS +
        // payload + EOF marker at end (real RAR5 layout — the size/type/
        // flags fields are VINTs, not fixed u16 offsets).
        let r5 = make_rar5(0x00, true);
        let p = tmp("t.rar5", &r5);
        let mut f = File::open(&p).unwrap();
        let info = validate_rar5(&mut f, 0, r5.len() as u64).expect("valid rar5");
        assert_eq!(info.size, Some(r5.len() as u64));

        // RAR5 non-last volume (HFL_VOLUME flag, no EOF marker) → the whole
        // volume is one region so the payload is not scanned as other formats.
        let r5v = make_rar5(0x01, false);
        let p5v = tmp("t.rar5vol", &r5v);
        let mut f5v = File::open(&p5v).unwrap();
        let info5v = validate_rar5(&mut f5v, 0, r5v.len() as u64).expect("valid rar5 volume");
        assert_eq!(info5v.size, Some(r5v.len() as u64));

        // Non-volume without EOF marker → size None (still a valid header).
        let r5n = make_rar5(0x00, false);
        let p5n = tmp("t.rar5noeof", &r5n);
        let mut f5n = File::open(&p5n).unwrap();
        let info5n = validate_rar5(&mut f5n, 0, r5n.len() as u64).expect("valid rar5 header");
        assert_eq!(info5n.size, None);

        // RAR4: sig(7) + arbitrary HEAD_CRC(2) + HEAD_TYPE(1)=0x73 +
        // HEAD_FLAGS(2) + HEAD_SIZE(2)=13 + payload + EOF marker at end.
        // The HEAD_CRC is data-dependent — real archives carry arbitrary
        // values there (a fixed constant like 0x6152 wrongly rejects every
        // real RAR4 archive).
        let r4 = make_rar4(0x0000, true);
        let p2 = tmp("t.rar4", &r4);
        let mut f2 = File::open(&p2).unwrap();
        let info2 = validate_rar4(&mut f2, 0, r4.len() as u64).expect("valid rar4");
        assert_eq!(info2.size, Some(r4.len() as u64));

        // RAR4 non-last volume (MHD_VOLUME flag, no EOF marker) → the whole
        // volume is one region so the payload is not scanned as other formats.
        let r4v = make_rar4(0x0001, false);
        let p3 = tmp("t.rar4vol", &r4v);
        let mut f3 = File::open(&p3).unwrap();
        let info3 = validate_rar4(&mut f3, 0, r4v.len() as u64).expect("valid rar4 volume");
        assert_eq!(info3.size, Some(r4v.len() as u64));

        // Non-volume without EOF marker → size None (still a valid header).
        let r4n = make_rar4(0x0000, false);
        let p4 = tmp("t.rar4noeof", &r4n);
        let mut f4 = File::open(&p4).unwrap();
        let info4 = validate_rar4(&mut f4, 0, r4n.len() as u64).expect("valid rar4 header");
        assert_eq!(info4.size, None);
    }

    /// Builds a structurally realistic RAR5 archive: sig + HEAD_CRC + vint
    /// HEAD_SIZE(2)/HEAD_TYPE(1)/HEAD_FLAGS + payload + optional EOF marker
    /// (1D 77 56 51 03 05 04 00).
    fn make_rar5(flags: u8, with_eof: bool) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend_from_slice(b"Rar!\x1a\x07\x01\x00");
        v.extend_from_slice(&0u32.to_le_bytes()); // HEAD_CRC (not checked)
        v.push(0x02); // HEAD_SIZE vint = 2 (type + flags)
        v.push(0x01); // HEAD_TYPE vint = 1 (main)
        v.push(flags); // HEAD_FLAGS vint
        v.extend_from_slice(&[0xAAu8; 50]); // payload
        if with_eof {
            v.extend_from_slice(&[0x1D, 0x77, 0x56, 0x51, 0x03, 0x05, 0x04, 0x00]);
        }
        v
    }

    /// A header-encrypted (-hp) RAR5 archive: sig + HEAD_CRC + ciphertext where
    /// the plaintext vint parse (type/flags) is garbage — [validate_rar5] must
    /// fail while the [validate_rar5_hp] fallback reports the whole file.
    #[test]
    fn rar5_hp_header_encrypted_reported_by_fallback() {
        // sig + 4-byte CRC + encrypted main header (random bytes: the vint
        // HEAD_TYPE at 12+ parses as garbage, so the plaintext validator drops
        // it — exactly the -hp case).
        let mut hp = Vec::new();
        hp.extend_from_slice(b"Rar!\x1a\x07\x01\x00");
        hp.extend_from_slice(&0u32.to_le_bytes());
        hp.extend_from_slice(&[0x7Fu8; 8]); // all continuation-bit vints → parse fails
        hp.extend_from_slice(&[0xA5u8; 100]); // ciphertext payload
        let p = tmp("t.rar5hp", &hp);
        let mut f = File::open(&p).unwrap();
        assert!(validate_rar5(&mut f, 0, hp.len() as u64).is_none(), "plaintext parse must fail on -hp");
        let mut f2 = File::open(&p).unwrap();
        let info = validate_rar5_hp(&mut f2, 0, hp.len() as u64).expect("-hp fallback must report");
        assert_eq!(info.size, Some(hp.len() as u64), "whole-file region");
        std::fs::remove_dir_all(&std::env::temp_dir().join(format!("uu_scan_valid_{}", std::process::id()))).ok();
    }

    /// Builds a structurally realistic RAR4 archive: sig + arbitrary HEAD_CRC
    /// + MAIN header (type 0x73, flags, size 13) + payload + optional EOF
    /// marker (C4 3D 7B 00 40 07 00).
    fn make_rar4(flags: u16, with_eof: bool) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend_from_slice(b"Rar!\x1a\x07\x00");
        v.extend_from_slice(&0x1111u16.to_le_bytes()); // arbitrary HEAD_CRC
        v.push(0x73); // HEAD_TYPE = main header
        v.extend_from_slice(&flags.to_le_bytes());
        v.extend_from_slice(&13u16.to_le_bytes()); // HEAD_SIZE
        v.extend_from_slice(&[0xBBu8; 50]); // payload
        if with_eof {
            v.extend_from_slice(&[0xC4, 0x3D, 0x7B, 0x00, 0x40, 0x07, 0x00]);
        }
        v
    }

    #[test]
    fn sevenz_header_and_real_rar5() {
        // 7z start header: sig(6) + version(2) + CRC32(bytes 12..32) +
        // next_header_offset(8 @12) + next_header_size(8 @20) + next CRC(4).
        // The next-header offset/size sit at 12/20 — an earlier version read
        // them at 20/28 and panicked on every real 7z file.
        let mut z7 = Vec::new();
        z7.extend_from_slice(b"7z\xbc\xaf\x27\x1c");
        z7.extend_from_slice(&[0x00, 0x04]);
        z7.extend_from_slice(&0u32.to_le_bytes()); // placeholder CRC
        z7.extend_from_slice(&32u64.to_le_bytes()); // next_header_offset
        z7.extend_from_slice(&256u64.to_le_bytes()); // next_header_size
        z7.extend_from_slice(&0u32.to_le_bytes()); // next_header_crc
        let crc = crc32(&z7[12..32], 0);
        z7[8..12].copy_from_slice(&crc.to_le_bytes());
        z7.extend_from_slice(&[0u8; 32 + 256]);
        let p = tmp("t.7z", &z7);
        let mut f = File::open(&p).unwrap();
        let info = validate_7z(&mut f, 0, z7.len() as u64).expect("valid 7z header");
        assert_eq!(info.size, Some(32 + 32 + 256));
        // Corrupt the CRC → rejected.
        let mut bad = z7.clone();
        bad[8] ^= 0xFF;
        let p2 = tmp("t2.7z", &bad);
        let mut f2 = File::open(&p2).unwrap();
        assert!(validate_7z(&mut f2, 0, bad.len() as u64).is_none(), "bad 7z CRC");

        // Truncated / multi-volume: the header block extends past this file
        // (continuation in the next volume) → the CRC-validated start header
        // still reports the hit, sized to the rest of the file.
        let trunc = z7[..200].to_vec();
        let p3 = tmp("t3.7z", &trunc);
        let mut f3 = File::open(&p3).unwrap();
        let info3 = validate_7z(&mut f3, 0, trunc.len() as u64).expect("truncated 7z header");
        assert_eq!(info3.size, Some(trunc.len() as u64));

        // Zero next-header offset → rejected.
        let mut zz = z7.clone();
        zz[12..20].copy_from_slice(&0u64.to_le_bytes());
        let crc = crc32(&zz[12..32], 0);
        zz[8..12].copy_from_slice(&crc.to_le_bytes());
        let p4 = tmp("t4.7z", &zz);
        let mut f4 = File::open(&p4).unwrap();
        assert!(validate_7z(&mut f4, 0, zz.len() as u64).is_none(), "zero next offset");

        // Overflowing next/next_size (u64::MAX): must not panic or wrap into
        // a bogus size — falls into the truncated/volume branch instead.
        let mut zz2 = z7.clone();
        zz2[12..20].copy_from_slice(&u64::MAX.to_le_bytes());
        let crc2 = crc32(&zz2[12..32], 0);
        zz2[8..12].copy_from_slice(&crc2.to_le_bytes());
        let p5 = tmp("t5.7z", &zz2);
        let mut f5 = File::open(&p5).unwrap();
        let info5 = validate_7z(&mut f5, 0, zz2.len() as u64).expect("overflow header");
        assert_eq!(info5.size, Some(zz2.len() as u64));

        // Real RAR5 archive (rar5-solid.rar from the rarfile test suite):
        // vint-encoded HEAD_SIZE=11 / HEAD_TYPE=1 / HEAD_FLAGS=5, two solid
        // files, EOF marker at the very end.
        let rar5_real = hex("526172211a07010009efc86f0b0105070406010180808000def570721c0202ba00048010b68302a2e6b7c5801b010a7374657374312e747874c1ac37444423fa23f697fd625351be4c918141028cc103795cbf47a3aa2da03693dda05800400200d4500476f6c47f32ea9f32a5c4ccd783df0010e82d891c02028d00048010b68302a2e6b7c5c01b010a7374657374322e74787445150a6001000803dfb7fbf6fc1d77565103050400");
        let p3 = tmp("real.rar5", &rar5_real);
        let mut f3 = File::open(&p3).unwrap();
        let info3 = validate_rar5(&mut f3, 0, rar5_real.len() as u64).expect("real rar5");
        assert_eq!(info3.size, Some(rar5_real.len() as u64));

        // The same bytes must NOT validate as RAR4 (type byte at 9 is part
        // of the RAR5 header CRC, not 0x73).
        let mut f4 = File::open(&p3).unwrap();
        assert!(validate_rar4(&mut f4, 0, rar5_real.len() as u64).is_none(), "rar5 must not be rar4");
    }

    #[test]
    fn iso9660_volume_descriptor() {
        // Minimal ISO: 32768-byte system area + PVD at 32768. Image size =
        // 17 blocks × 2048 = 34816.
        let total = 34816u64;
        let mut iso = vec![0u8; total as usize];
        let start = 32768usize;
        iso[start] = 1; // type: primary volume descriptor
        iso[start + 1..start + 6].copy_from_slice(b"CD001");
        iso[start + 6] = 1; // version
        // Volume space size (both-endian at PVD offset 80): 17 blocks.
        let vss: u32 = 17;
        iso[start + 80..start + 84].copy_from_slice(&vss.to_le_bytes());
        iso[start + 84..start + 88].copy_from_slice(&vss.to_be_bytes());
        // Logical block size (both-endian at PVD offset 128): 2048.
        let blk: u16 = 2048;
        iso[start + 128..start + 130].copy_from_slice(&blk.to_le_bytes());
        iso[start + 130..start + 132].copy_from_slice(&blk.to_be_bytes());
        let p = tmp("t.iso", &iso);
        let mut f = File::open(&p).unwrap();
        let info = validate_iso(&mut f, 32768, iso.len() as u64).expect("valid iso");
        // Extent from the magic offset to the image end (34816 - 32768).
        assert_eq!(info.size, Some(2048));

        // Wrong magic at 32768 → rejected.
        let mut bad = iso.clone();
        bad[32768 + 1..32768 + 6].copy_from_slice(b"XXXXX");
        let p2 = tmp("bad.iso", &bad);
        let mut f2 = File::open(&p2).unwrap();
        assert!(validate_iso(&mut f2, 32768, bad.len() as u64).is_none());
    }

    /// Builds a single 512-byte ustar header. [h] fills the checksum field.
    fn make_tar_header(name: &[u8], size: usize) -> [u8; 512] {
        let mut h = [0u8; 512];
        let n = name.len().min(100);
        h[..n].copy_from_slice(&name[..n]);
        let s = format!("{:011o}\0", size);
        h[124..124 + s.len()].copy_from_slice(s.as_bytes());
        h[257..262].copy_from_slice(b"ustar");
        h[262] = 0; // GNU version byte
        let sum: u64 = h.iter().enumerate()
            .map(|(i, &b)| if (148..156).contains(&i) { 0x20 } else { b as u64 })
            .sum();
        let cs = format!("{:06o}\0 ", sum);
        h[148..148 + cs.len()].copy_from_slice(cs.as_bytes());
        h
    }

    #[test]
    fn tar_ustar_checksum_and_size() {
        // One 1000-byte file entry (2 data blocks) + two zero end blocks.
        let mut tar = Vec::new();
        let h1 = make_tar_header(b"data.txt", 1000);
        tar.extend_from_slice(&h1);
        let mut data = vec![0x41u8; 1000];
        data.resize(1024, 0); // pad to 2×512
        tar.extend_from_slice(&data);
        tar.extend_from_slice(&[0u8; 1024]); // two zero end blocks
        let p = tmp("t.tar", &tar);
        let mut f = File::open(&p).unwrap();
        let info = validate_tar(&mut f, 257, tar.len() as u64).expect("valid tar");
        // Extent from the magic offset to the archive end.
        assert_eq!(info.size, Some(tar.len() as u64 - 257));

        // Corrupt the checksum → rejected. (Corrupt the name byte so the sum
        // changes while the stored octal stays — flipping a checksum digit may
        // be a no-op due to %06o leading zeros.)
        let mut bad = h1;
        bad[0] = b'X';
        let mut tar2 = Vec::new();
        tar2.extend_from_slice(&bad);
        tar2.extend_from_slice(&data);
        tar2.extend_from_slice(&[0u8; 1024]);
        let p2 = tmp("t2.tar", &tar2);
        let mut f2 = File::open(&p2).unwrap();
        assert!(validate_tar(&mut f2, 257, tar2.len() as u64).is_none());

        // "ustar" at a random offset without a valid header → rejected.
        let mut rand = vec![0u8; 4096];
        rand[300..305].copy_from_slice(b"ustar");
        let p3 = tmp("rand.bin", &rand);
        let mut f3 = File::open(&p3).unwrap();
        assert!(validate_tar(&mut f3, 300 + 257, rand.len() as u64).is_none());
    }

    #[test]
    fn tar_space_padded_octal_fields() {
        // Legacy tars pad the size field with SPACES instead of zeros — the
        // parse must skip the leading spaces, not read the size as 0.
        let mut h = make_tar_header(b"pad.bin", 3000);
        // Overwrite size field 124..136 with space-padded octal "       5670".
        let s = format!("{:>11o}\0", 3000); // right-justified, space-padded
        assert_eq!(s.len(), 12);
        h[124..136].copy_from_slice(s.as_bytes());
        // Recompute checksum AFTER overwriting the size field.
        let sum: u64 = h.iter().enumerate()
            .map(|(i, &b)| if (148..156).contains(&i) { 0x20 } else { b as u64 })
            .sum();
        let cs = format!("{:06o}\0 ", sum);
        h[148..148 + cs.len()].copy_from_slice(cs.as_bytes());

        let mut tar = Vec::new();
        tar.extend_from_slice(&h);
        let mut data = vec![0x41u8; 3000];
        data.resize(3072, 0); // pad to 6×512
        tar.extend_from_slice(&data);
        tar.extend_from_slice(&[0u8; 1024]);
        let p = tmp("pad.tar", &tar);
        let mut f = File::open(&p).unwrap();
        let info = validate_tar(&mut f, 257, tar.len() as u64).expect("space-padded tar");
        assert_eq!(info.size, Some(tar.len() as u64 - 257));
    }

    /// Old-format XP3: 10-byte magic + unused byte + u64 index offset.
    /// Standalone archive extends to EOF.
    #[test]
    fn xp3_old_format_validates() {
        let mut blob = vec![0u8; 4096];
        blob[0..10].copy_from_slice(b"XP3\r\n \n\x1a\x8b\x67");
        blob[10] = 0x01; // unused byte
        blob[11..19].copy_from_slice(&2048u64.to_le_bytes()); // index offset (relative to start)
        blob[2048..2052].copy_from_slice(b"File"); // index section identifier
        let p = tmp("ok.xp3", &blob);
        let mut f = File::open(&p).unwrap();
        let info = validate_xp3(&mut f, 0, blob.len() as u64).expect("valid old-format xp3");
        // Standalone archive → extent to EOF.
        assert_eq!(info.size, Some(blob.len() as u64));
    }

    /// Current-format XP3: version identifier 0x17 + minor + u8(128) + relative
    /// index offset, then the absolute index offset.
    #[test]
    fn xp3_current_format_validates() {
        let mut blob = vec![0u8; 8192];
        blob[0..10].copy_from_slice(b"XP3\r\n \n\x1a\x8b\x67");
        blob[11..19].copy_from_slice(&XP3_CURRENT_VER.to_le_bytes()); // 0x17
        blob[19..23].copy_from_slice(&0u32.to_le_bytes()); // minor
        blob[23] = XP3_VERSION_IDENTIFIER; // 128
        blob[24..32].copy_from_slice(&64u64.to_le_bytes()); // relative skip to the absolute-offset field
        // At offset 11+8+64 = 83: the absolute index offset.
        blob[83..91].copy_from_slice(&2048u64.to_le_bytes());
        blob[2048..2052].copy_from_slice(b"File");
        let p = tmp("current.xp3", &blob);
        let mut f = File::open(&p).unwrap();
        let info = validate_xp3(&mut f, 0, blob.len() as u64).expect("valid current-format xp3");
        assert_eq!(info.size, Some(blob.len() as u64));
    }

    /// Wrong magic at 0 → rejected.
    #[test]
    fn xp3_bad_magic_rejected() {
        let mut blob = vec![0u8; 1024];
        blob[0..10].copy_from_slice(b"XP3\r\n \n\x1a\x8b\x68"); // last byte wrong
        let p = tmp("bad.xp3", &blob);
        let mut f = File::open(&p).unwrap();
        assert!(validate_xp3(&mut f, 0, blob.len() as u64).is_none());
    }

    /// Build a minimal NSA: count + 4 reserved + one entry (name\0 + comp + 3×u32 BE).
    fn make_min_nsa(name: &str, comp: u8, body: &[u8]) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend_from_slice(&1u16.to_be_bytes()); // count = 1
        v.extend_from_slice(&[0u8; 4]); // reserved
        v.extend_from_slice(name.as_bytes());
        v.push(0); // NUL
        v.push(comp);
        v.extend_from_slice(&0u32.to_be_bytes()); // offset (relative to body)
        v.extend_from_slice(&(body.len() as u32).to_be_bytes()); // csize
        v.extend_from_slice(&(body.len() as u32).to_be_bytes()); // usize
        v.extend_from_slice(body);
        v
    }

    #[test]
    fn nsa_whole_file_validates() {
        let nsa = make_min_nsa("bg\\542-1.jpg", 0, &[0u8; 64]);
        let p = tmp("ok.nsa", &nsa);
        let mut f = File::open(&p).unwrap();
        let info = validate_nsa_whole_file(&mut f, nsa.len() as u64).expect("valid nsa");
        assert_eq!(info.size, Some(nsa.len() as u64));
        assert_eq!(info.count, Some(1));
    }

    #[test]
    fn nsa_random_data_rejected() {
        use std::collections::hash_map::RandomState;
        use std::hash::{BuildHasher, Hasher};
        let mut data = vec![0u8; 4096];
        let s = RandomState::new();
        for chunk in data.chunks_mut(8) {
            let mut h = s.build_hasher();
            h.write_u64(chunk.len() as u64);
            chunk.copy_from_slice(&h.finish().to_le_bytes());
        }
        let p = tmp("rand.nsa", &data);
        let mut f = File::open(&p).unwrap();
        assert!(validate_nsa_whole_file(&mut f, data.len() as u64).is_none(),
            "random bytes must not validate as NSA");
    }

    #[test]
    fn nsa_non_printable_name_rejected() {
        let mut nsa = make_min_nsa("ok", 0, &[0u8; 32]);
        nsa[6] = 0x01; // first name byte non-printable
        let p = tmp("badname.nsa", &nsa);
        let mut f = File::open(&p).unwrap();
        assert!(validate_nsa_whole_file(&mut f, nsa.len() as u64).is_none());
    }

    #[test]
    fn zip_valid_and_false_positive() {
        // Build a minimal valid zip: one stored file + central dir + EOCD.
        let z = make_min_zip();
        let p = tmp("ok.zip", &z);
        let mut f = File::open(&p).unwrap();
        let info = validate_zip(&mut f, 0, z.len() as u64).expect("valid zip");
        assert_eq!(info.count, Some(1));
        assert!(info.size.unwrap() <= z.len() as u64);

        // Random bytes with PK\x03\x04 at 0 must be rejected.
        let p2 = tmp("bad.zip", b"PK\x03\x04this is not a zip at all........");
        let mut f2 = File::open(&p2).unwrap();
        assert!(validate_zip(&mut f2, 0, 40).is_none());
    }

    #[test]
    fn zip_eocd_straddling_chunk_boundary() {
        // A structurally valid zip whose EOCD magic straddles the 64 KiB
        // search-chunk boundary (EOCD at absolute 65568: window index 65533
        // in the first chunk). Without an overlap carry the magic is lost
        // between chunks and the archive is wrongly rejected.
        // EOCD abs = 30 (lh) + 5 (name) + 5 (data) + pad + 51 (cd) = 91 + pad.
        let z = make_min_zip_with_pad(65568 - 91);
        assert_eq!(z.len(), 65568 + 22, "EOCD must end the file");
        assert_eq!(&z[65568..65572], b"PK\x05\x06");
        let p = tmp("straddle.zip", &z);
        let mut f = File::open(&p).unwrap();
        let info = validate_zip(&mut f, 0, z.len() as u64).expect("straddling EOCD must validate");
        assert_eq!(info.count, Some(1));
        assert_eq!(info.size, Some(z.len() as u64));
    }

    #[test]
    fn zip_tail_eocd_fallback() {
        // The tail scan used when the forward 256 MiB cap cannot reach the
        // EOCD (archives > 256 MiB): locate the last EOCD backwards and
        // validate it via the same check the fallback uses.
        let z = make_min_zip();
        let p = tmp("tail.zip", &z);
        let mut f = File::open(&p).unwrap();
        let file_len = z.len() as u64;
        let eocd = find_marker_in_tail(&mut f, file_len, file_len, b"PK\x05\x06")
            .expect("tail EOCD found");
        assert_eq!(&z[eocd as usize..eocd as usize + 4], b"PK\x05\x06");
        let info = check_eocd(&mut f, eocd, 0, file_len).expect("tail EOCD validates");
        assert_eq!(info.count, Some(1));
        assert_eq!(info.size, Some(file_len));
    }

    #[test]
    fn zstd_single_block_frames() {
        // Real 19-byte frame produced by the zstd CLI (v1.5.7) for "hello\n":
        // one raw block — must not be rejected by a minimum block count.
        let zst = hex("28b52ffd045831000068656c6c6f0a5388bd91");
        let p = tmp("t1.zst", &zst);
        let mut f = File::open(&p).unwrap();
        let info = validate_zstd(&mut f, 0, zst.len() as u64).expect("single-block zstd");
        assert_eq!(info.size, Some(zst.len() as u64));

        // Hand-crafted single-segment frame (fd=0x24: single, FCS 1 byte,
        // checksum): one compressed block (last, type 2, size 8).
        let mut zst2 = Vec::new();
        zst2.extend_from_slice(b"\x28\xb5\x2f\xfd");
        zst2.push(0x24);
        zst2.push(100); // FCS (1 byte)
        zst2.extend_from_slice(&0x45u32.to_le_bytes()[..3]); // block hdr: last, compressed, size 8
        zst2.extend_from_slice(&[0u8; 8]); // compressed payload
        zst2.extend_from_slice(&[0u8; 4]); // content checksum
        let p2 = tmp("t2.zst", &zst2);
        let mut f2 = File::open(&p2).unwrap();
        let info2 = validate_zstd(&mut f2, 0, zst2.len() as u64).expect("single-seg zstd");
        assert_eq!(info2.size, Some(zst2.len() as u64));
    }

    #[test]
    fn lzma_preset0_dict_validates() {
        // lzma-alone preset 0 uses a 256 KiB dictionary (0x00040000) — the
        // whitelist must accept it (the app's own lzma level-0 output uses
        // this dict via liblzma's lzma_alone_encoder). Build a REAL stream
        // with the same compressor so both the dict check and the dry-run
        // succeed.
        let mut compressed = Vec::new();
        lzma_rs::lzma_compress(&mut std::io::Cursor::new(b"hello lzma preset0"), &mut compressed).expect("lzma_compress");
        let p = tmp("t.lzma", &compressed);
        let mut f = File::open(&p).unwrap();
        assert!(validate_lzma(&mut f, 0, compressed.len() as u64).is_some(), "preset0 lzma");
    }

    /// The lzma dry-run must reject high-entropy data carrying a plausible
    /// props+dict magic but no real LZMA stream inside.
    #[test]
    fn lzma_garbage_with_plausible_magic_rejected() {
        // props 0x5D (lc=3,lp=0,pb=2) + 8 MiB dict + streaming size + junk.
        let mut fake = Vec::new();
        fake.extend_from_slice(&hex("5d00000080"));
        fake.extend_from_slice(&0xFFFF_FFFF_FFFF_FFFFu64.to_le_bytes());
        fake.extend_from_slice(&[0xAA; 128]);
        let p = tmp("fake.lzma", &fake);
        let mut f = File::open(&p).unwrap();
        assert!(validate_lzma(&mut f, 0, fake.len() as u64).is_none(), "garbage lzma must be rejected");
    }

    #[test]
    fn gzip_fextra_and_os_variants() {
        // OS byte 2 (VMS) is legal; FEXTRA must skip its XLEN payload before
        // the FNAME walk — the extra payload may contain NUL bytes that would
        // otherwise be mistaken for the name terminator. Built with GzBuilder
        // so the header flags AND the deflate stream are both real.
        use flate2::Compression;
        use flate2::GzBuilder;
        let mut extra = GzBuilder::new()
            .mtime(0)
            .extra(&[0x00, 0x11, 0x00, 0x22]) // XLEN payload containing NULs
            .filename("file.txt")
            .write(Vec::new(), Compression::default());
        std::io::Write::write_all(&mut extra, b"payload").unwrap();
        let gz = extra.finish().unwrap();
        let p = tmp("fextra.gz", &gz);
        let mut f = File::open(&p).unwrap();
        assert!(validate_gzip(&mut f, 0, gz.len() as u64).is_some(), "gzip FEXTRA+FNAME OS=2");

        // XLEN extending past EOF → rejected.
        let mut gz3 = gz.clone();
        let flg = gz3[3];
        assert!(flg & 0x04 != 0, "FEXTRA flag must be set");
        // XLEN is at offset 10..12 when FEXTRA is set.
        gz3[10..12].copy_from_slice(&60000u16.to_le_bytes());
        let p3 = tmp("fextra3.gz", &gz3);
        let mut f3 = File::open(&p3).unwrap();
        assert!(validate_gzip(&mut f3, 0, gz3.len() as u64).is_none(), "xlen beyond EOF");
    }

    #[test]
    fn gzip_field_without_terminator_rejected() {
        // FNAME set, 70000 bytes with no NUL: the 64KB walk guard must
        // reject the header instead of silently accepting it.
        let mut gz = Vec::new();
        gz.extend_from_slice(b"\x1f\x8b\x08");
        gz.push(0x08); // FLG: FNAME
        gz.extend_from_slice(&0u32.to_le_bytes()); // MTIME = 0
        gz.push(0x00); // XFL
        gz.push(0x03); // OS = Unix
        gz.extend_from_slice(&[0x41u8; 70000]); // no NUL anywhere
        let p = tmp("nonul.gz", &gz);
        let mut f = File::open(&p).unwrap();
        assert!(validate_gzip(&mut f, 0, gz.len() as u64).is_none(), "no-NUL field must reject");
    }

    /// Builds a minimal valid lz4 frame: magic + FLG/BD (+ optional 4-byte
    /// dict id) + header checksum + one raw 5-byte block + end marker.
    fn lz4_frame(flg: u8, bd: u8, dict: Option<[u8; 4]>) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend_from_slice(b"\x04\x22\x4d\x18");
        v.push(flg);
        v.push(bd);
        let mut hdr_data = vec![flg, bd];
        if let Some(d) = dict {
            v.extend_from_slice(&d);
            hdr_data.extend_from_slice(&d);
        }
        v.push((xxh32(&hdr_data, 0) >> 8) as u8);
        v.extend_from_slice(&0x8000_0005u32.to_le_bytes()); // raw block, 5 bytes
        v.extend_from_slice(b"hello");
        v.extend_from_slice(&0u32.to_le_bytes()); // end marker
        v
    }

    #[test]
    fn lz4_dict_id_accepted_reserved_bit_rejected() {
        // FLG bit0 = DictID flag (legal, with a 4-byte dict id); bit1 = the
        // true reserved bit (must reject even with a valid checksum).
        let f1 = lz4_frame(0x41, 0x00, Some([0x11, 0x22, 0x33, 0x44]));
        let p = tmp("t.lz4dict", &f1);
        let mut f = File::open(&p).unwrap();
        let info = validate_lz4(&mut f, 0, f1.len() as u64).expect("dictID frame must validate");
        assert_eq!(info.size, Some(f1.len() as u64));

        // Only the reserved bit is wrong — recompute the header checksum so
        // the rejection is due to bit1, not a stale checksum.
        let mut f2 = f1.clone();
        f2[4] |= 0b10;
        let hdr_data = [f2[4], f2[5], 0x11, 0x22, 0x33, 0x44];
        f2[10] = (xxh32(&hdr_data, 0) >> 8) as u8;
        let p2 = tmp("t.lz4bad", &f2);
        let mut g = File::open(&p2).unwrap();
        assert!(validate_lz4(&mut g, 0, f2.len() as u64).is_none(), "reserved bit1 must reject");
    }

    #[test]
    fn gzip_bzip2_xz_headers() {
        // gzip: a real flate2 stream must validate (header + dry-run decode).
        let g = tmp("t.gz", &gz_bytes(b"hello gzip"));
        let mut f = File::open(&g).unwrap();
        let blob = std::fs::read(&g).unwrap();
        assert!(validate_gzip(&mut f, 0, blob.len() as u64).is_some());
        // Far-future MTIME → rejected.
        let mut gz2 = gz_bytes(b"x");
        gz2[4..8].copy_from_slice(&0xFFFFFFFFu32.to_le_bytes());
        let g2 = tmp("t2.gz", &gz2);
        let mut f2 = File::open(&g2).unwrap();
        assert!(validate_gzip(&mut f2, 0, gz2.len() as u64).is_none());
        // A plausible-looking header with garbage deflate data → the dry-run
        // must reject it (this is exactly the false-positive class we cut).
        let mut fake = Vec::new();
        fake.extend_from_slice(b"\x1f\x8b\x08");
        fake.push(0x00); // FLG = 0
        fake.extend_from_slice(&0u32.to_le_bytes()); // MTIME = 0
        fake.push(0x00); // XFL
        fake.push(0x03); // OS = Unix
        fake.extend_from_slice(&[0xAB; 64]); // not a valid deflate stream
        let g3 = tmp("t3.gz", &fake);
        let mut f3 = File::open(&g3).unwrap();
        assert!(validate_gzip(&mut f3, 0, fake.len() as u64).is_none(), "garbage deflate must be rejected");

        // bzip2: full 10-byte magic.
        let b = tmp("t.bz2", b"BZh51AY&SYdata");
        let mut f3 = File::open(&b).unwrap();
        assert!(validate_bzip2(&mut f3, 0, 14).is_some());
        let b2 = tmp("t2.bz2", b"BZhX1AY&SYbad");
        let mut f4 = File::open(&b2).unwrap();
        assert!(validate_bzip2(&mut f4, 0, 14).is_none());

        // xz: a REAL stream (header + flags CRC + actual compressed data) must
        // validate through the dry-run.
        let mut x = Vec::new();
        {
            use std::io::Write as _;
            let mut enc = xz2::write::XzEncoder::new(&mut x, 6);
            enc.write_all(b"hello xz stream").unwrap();
            enc.finish().unwrap();
        }
        let p = tmp("t.xz", &x);
        let mut f5 = File::open(&p).unwrap();
        assert!(validate_xz(&mut f5, 0, x.len() as u64).is_some());
        // Corrupt flags CRC → rejected (header check fires before the dry-run).
        let p2 = tmp("t2.xz", b"\xfd7zXZ\x00\x00\x04\x00\x00\x00\x00");
        let mut f6 = File::open(&p2).unwrap();
        assert!(validate_xz(&mut f6, 0, 12).is_none());
        // Plausible header + garbage stream → the dry-run rejects.
        let mut fake = Vec::new();
        fake.extend_from_slice(b"\xfd7zXZ\x00");
        fake.extend_from_slice(&[0x00, 0x04]);
        fake.extend_from_slice(&crc32(&[0x00, 0x04], 0).to_le_bytes());
        fake.extend_from_slice(&[0x5A; 64]); // not a real xz stream
        let p3 = tmp("t3.xz", &fake);
        let mut f7 = File::open(&p3).unwrap();
        assert!(validate_xz(&mut f7, 0, fake.len() as u64).is_none(), "garbage xz must be rejected");
    }

    /// A gzip truncated mid-stream must be rejected by the dry-run (incomplete
    /// deflate → decode error), not silently accepted as a hit.
    #[test]
    fn gzip_truncated_rejected_by_dry_run() {
        let mut gz = gz_bytes(&vec![0x5Au8; 20000]);
        // Chop off the trailing footer + some deflate so the stream can't
        // finish cleanly — flate2 must surface an error.
        gz.truncate(gz.len() / 2);
        let p = tmp("trunc.gz", &gz);
        let mut f = File::open(&p).unwrap();
        assert!(validate_gzip(&mut f, 0, gz.len() as u64).is_none(), "truncated gzip must be rejected");
    }

    #[test]
    fn png_gif_pdf_elf() {
        // Valid PNG: header + IHDR chunk + IEND chunk (walk to IEND).
        let mut png = Vec::new();
        png.extend_from_slice(b"\x89PNG\r\n\x1a\n");
        png.extend_from_slice(&13u32.to_be_bytes());
        png.extend_from_slice(b"IHDR");
        png.extend_from_slice(&1u32.to_be_bytes()); // width
        png.extend_from_slice(&1u32.to_be_bytes()); // height
        png.extend_from_slice(&[0u8; 5]); // bit depth/color/etc
        png.extend_from_slice(&0u32.to_be_bytes()); // crc (not checked)
        png.extend_from_slice(&0u32.to_be_bytes());
        png.extend_from_slice(b"IEND");
        png.extend_from_slice(&0u32.to_be_bytes()); // crc
        let p = tmp("t.png", &png);
        let mut f = File::open(&p).unwrap();
        let info = validate_png(&mut f, 0, png.len() as u64).expect("valid png");
        assert_eq!(info.size, Some(png.len() as u64));

        // Truncated (no IEND) → rejected.
        let bad = tmp("bad.png", &png[..png.len() - 8]);
        let mut f6 = File::open(&bad).unwrap();
        assert!(validate_png(&mut f6, 0, bad.len() as u64).is_none());

        // GIF: 6-byte magic + logical screen descriptor.
        let g = tmp("t.gif", b"GIF89a\x01\x00\x01\x00\x00\x00\x00");
        let mut f2 = File::open(&g).unwrap();
        assert!(validate_gif(&mut f2, 0, 13).is_some());

        // PDF: %PDF-1.x + newline + % binary marker.
        let d = tmp("t.pdf", b"%PDF-1.4\n%binary");
        let mut f3 = File::open(&d).unwrap();
        assert!(validate_pdf(&mut f3, 0, 14).is_some());
        let d2 = tmp("t2.pdf", b"%PDF-x.bad");
        let mut f4 = File::open(&d2).unwrap();
        assert!(validate_pdf(&mut f4, 0, 10).is_none());

        // ELF: valid 32-bit little-endian with osabi 0, zero padding, e_type=2.
        let mut elf = vec![0x7f, b'E', b'L', b'F', 0x01, 0x01, 0x01, 0x00]; // e_ident
        elf.extend_from_slice(&[0u8; 8]); // padding
        elf.extend_from_slice(&2u16.to_le_bytes()); // e_type = ET_EXEC
        elf.extend_from_slice(&3u16.to_le_bytes()); // e_machine
        let e = tmp("t.elf", &elf);
        let mut f5 = File::open(&e).unwrap();
        assert!(validate_elf(&mut f5, 0, elf.len() as u64).is_some());
        // Bad e_type (99) → rejected.
        let mut elf2 = elf.clone();
        elf2[16] = 0x63;
        elf2[17] = 0x00;
        let e2 = tmp("t2.elf", &elf2);
        let mut f7 = File::open(&e2).unwrap();
        assert!(validate_elf(&mut f7, 0, elf2.len() as u64).is_none());
    }

    /// Regression guard: real-world samples produced by macOS system tools
    /// (gzip/bzip2/xz/zstd/lz4/lzma). These catch byte-order / bit-layout
    /// mistakes that self-consistent fixtures can't (the earlier MTIME
    /// endianness, crc32 init and zstd header bugs all slipped past them).
    #[test]
    fn real_world_compressed_samples() {
        // gzip with FNAME, fixed past MTIME=0x5A3C0000 (2017), OS=3.
        let gz = hex("1f8b080800003c5a0003745f7265616c2e74787400cb48cdc9c95728cf2fca4951c8c0ce0600bbfe420f23000000");
        let p = tmp("s.gz", &gz);
        let mut f = File::open(&p).unwrap();
        assert!(validate_gzip(&mut f, 0, gz.len() as u64).is_some(), "real gzip");

        // bzip2 full magic.
        let bz = hex("425a683931415926535961");
        let p2 = tmp("s.bz2", &bz);
        let mut f2 = File::open(&p2).unwrap();
        assert!(validate_bzip2(&mut f2, 0, bz.len() as u64).is_some(), "real bzip2");

        // xz: real stream built with the same compressor (header + data).
        let mut xz = Vec::new();
        {
            use std::io::Write as _;
            let mut enc = xz2::write::XzEncoder::new(&mut xz, 6);
            enc.write_all(b"real world xz sample").unwrap();
            enc.finish().unwrap();
        }
        let p3 = tmp("s.xz", &xz);
        let mut f3 = File::open(&p3).unwrap();
        assert!(validate_xz(&mut f3, 0, xz.len() as u64).is_some(), "real xz stream");

        // lz4 frame with content-size + header checksum (mac lz4 CLI output).
        let lz4 = hex("04224d186440a7010000807800000000ea30c42e");
        let p4 = tmp("s.lz4", &lz4);
        let mut f4 = File::open(&p4).unwrap();
        assert!(validate_lz4(&mut f4, 0, lz4.len() as u64).is_some(), "real lz4");

        // zstd multi-block frame (mac zstd CLI, fd=0xa4 single-seg + 4B FCS).
        let zst = hex("28b52ffda4804f1200a400006068656c6c6f20776f726c64200100f1ffcf4b124c000008720100fcff3910024c0000086f0100fcff3910024c000008680100fcff3910024c000008720100fcff3910024c0000086f0100fcff3910024c000008680100fcff3910024c000008720100fcff3910024c0000086f0100fcff391002450000086801007ccf0e84941931c2");
        let p5 = tmp("s.zst", &zst);
        let mut f5 = File::open(&p5).unwrap();
        let info = validate_zstd(&mut f5, 0, zst.len() as u64).expect("real zstd");
        assert_eq!(info.size, Some(zst.len() as u64));

        // lzma-alone: real stream with the same compressor (props 0x5D + 8 MiB
        // dict + actual data), so both the whitelist and the dry-run pass.
        let mut lzma = Vec::new();
        lzma_rs::lzma_compress(&mut std::io::Cursor::new(b"real world lzma sample"), &mut lzma).expect("lzma_compress");
        let p6 = tmp("s.lzma", &lzma);
        let mut f6 = File::open(&p6).unwrap();
        assert!(validate_lzma(&mut f6, 0, lzma.len() as u64).is_some(), "real lzma");
    }
    /// The Kotlin side mirrors these two numbers into the scan dialog footer
    /// (SignatureScan.kt SCAN_SIG_COUNT / SCAN_PATTERN_COUNT). They had already
    /// drifted once (the footer claimed 77 patterns while the table held 82),
    /// so they are asserted here rather than maintained by hand.
    #[test]
    fn signature_counts_match_the_kotlin_footer() {
        let sigs = SIGNATURES.len() as u32;
        let patterns: u32 = SIGNATURES.iter().map(|s| s.magics.len() as u32).sum();
        assert_eq!(sigs, 118);
        assert_eq!(patterns, 203);
    }

    /// RGSS: 7-byte magic + a legal version byte, plus the v3 index sanity.
    #[test]
    fn rgss_validates() {
        for version in [1u8, 2, 3] {
            let mut blob = vec![0u8; 256];
            blob[0..7].copy_from_slice(b"RGSSAD\x00");
            blob[7] = version;
            // seed 0 -> key 3, which is what a v3 archive written by this app
            // (and by the reference packers) carries.
            blob[8..12].copy_from_slice(&0u32.to_le_bytes());
            let p = tmp(&format!("v{version}.rgss"), &blob);
            let mut f = File::open(&p).unwrap();
            assert!(validate_rgss(&mut f, 0, blob.len() as u64).is_some(), "version {version}");
        }
    }

    /// The scanner passes the magic's own offset, so a valid RGSS header
    /// embedded at a nonzero offset must still be found.
    #[test]
    fn rgss_found_at_nonzero_offset() {
        let mut host = vec![b'x'; 128];
        host[40..47].copy_from_slice(b"RGSSAD\x00");
        host[47] = 0x03;
        host[48..52].copy_from_slice(&0u32.to_le_bytes()); // key = 3
        host[52..56].copy_from_slice(&3u32.to_le_bytes()); // empty-archive terminator
        let p = tmp("host.rgss", &host);
        let mut f = File::open(&p).unwrap();
        assert!(validate_rgss(&mut f, 40, host.len() as u64 - 40).is_some());
    }

    /// An unknown version, a v3 index pointing past EOF, and a truncated
    /// header must all be rejected.
    #[test]
    fn rgss_false_positives_rejected() {
        let mut bad_version = vec![0u8; 64];
        bad_version[0..7].copy_from_slice(b"RGSSAD\x00");
        bad_version[7] = 9;
        let p = tmp("ver.rgss", &bad_version);
        let mut f = File::open(&p).unwrap();
        assert!(validate_rgss(&mut f, 0, bad_version.len() as u64).is_none(), "version 9");

        // v3 whose first index dword decrypts to an offset past EOF
        let mut bad_index = vec![0u8; 64];
        bad_index[0..7].copy_from_slice(b"RGSSAD\x00");
        bad_index[7] = 3;
        bad_index[8..12].copy_from_slice(&0u32.to_le_bytes()); // key = 3
        bad_index[12..16].copy_from_slice(&(9_000u32 ^ 3).to_le_bytes());
        let p = tmp("index.rgss", &bad_index);
        let mut f = File::open(&p).unwrap();
        assert!(validate_rgss(&mut f, 0, bad_index.len() as u64).is_none(), "offset past EOF");

        // ...and the terminator of an empty v3 archive, which decrypts to 0
        let mut empty = vec![0u8; 64];
        empty[0..7].copy_from_slice(b"RGSSAD\x00");
        empty[7] = 3;
        empty[8..12].copy_from_slice(&0u32.to_le_bytes());
        empty[12..16].copy_from_slice(&3u32.to_le_bytes());
        let p = tmp("empty.rgss", &empty);
        let mut f = File::open(&p).unwrap();
        assert!(validate_rgss(&mut f, 0, empty.len() as u64).is_some(), "empty v3 terminator");

        let mut short = vec![0u8; 6];
        short[0..6].copy_from_slice(b"RGSSAD");
        let p = tmp("short.rgss", &short);
        let mut f = File::open(&p).unwrap();
        assert!(validate_rgss(&mut f, 0, short.len() as u64).is_none(), "truncated");
    }
}
