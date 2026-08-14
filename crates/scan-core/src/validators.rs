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
    // Forward scan for EOCD, bounded (256 MiB), with overlap carry so an
    // EOCD magic straddling a 64 KiB chunk boundary is still found.
    const EOCD_MAGIC: [u8; 4] = [0x50, 0x4B, 0x05, 0x06];
    const FWD_CAP: u64 = 268_435_456;
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
    // Fallback for archives whose EOCD is beyond the forward cap: the EOCD
    // sits within 64 KiB + 22 bytes of the end of the archive. Try the last
    // candidate(s) in the file tail until one validates.
    let tail_from = file_len.saturating_sub(64 * 1024 + 22);
    if tail_from > end {
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
    while pos < end {
        if f.seek(SeekFrom::Start(pos)).is_err() {
            return None;
        }
        let n = f.read(&mut buf).unwrap_or(0);
        if n == 0 {
            return None;
        }
        let mut window = Vec::with_capacity(overlap.len() + n);
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
        overlap = window[window.len().saturating_sub(magic.len() - 1)..].to_vec();
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
        let mut window = Vec::with_capacity(n + carry.len());
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
        carry = window[..magic.len().saturating_sub(1).min(window.len())].to_vec();
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
            let mut b = [0u8; 1];
            let mut guard = 0u32;
            let mut terminated = false;
            while guard < 65536 {
                guard += 1;
                if pos >= file_len {
                    return None; // no terminator → false positive
                }
                if !read_at(f, pos, &mut b) {
                    return None;
                }
                if b[0] == 0 {
                    terminated = true;
                    break;
                }
                pos += 1;
            }
            if !terminated {
                return None; // 64KB without a NUL → not a real gzip field
            }
            pos += 1;
        }
    }
    Some(HitInfo { size: None, count: None })
}

/// bzip2: the magic table already carries the full 10-byte
/// "BZh{1-9}1AY&SY" signature (binwalk parity), so this just confirms the
/// block-size digit — the magic itself rejects random false positives.
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
    let _ = file_len;
    Some(HitInfo { size: None, count: None })
}

/// XP3: 8-byte "XP3\r\n \x1a\n" signature + 4-byte version + 8-byte index
/// offset + 8-byte index size, all within bounds.
fn validate_xp3(f: &mut File, off: u64, file_len: u64) -> Option<HitInfo> {
    let mut h = [0u8; 28];
    if !read_at(f, off, &mut h) {
        return None;
    }
    if &h[0..8] != b"XP3\r\n \x1a\n" {
        return None;
    }
    let idx_off = u64le(&h, 12);
    let idx_size = u64le(&h, 20);
    if idx_off == 0 || idx_size == 0 || off + idx_off + idx_size > file_len {
        return None;
    }
    Some(HitInfo { size: Some(idx_off + idx_size), count: None })
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
        // Start Of Scan: scan ahead until the next real marker.
        if marker_id == SOS_MARKER {
            loop {
                if next + 2 > file_len {
                    return None;
                }
                let mut nb = [0u8; 2];
                if !read_at(f, next, &mut nb) {
                    return None;
                }
                if nb[0] == MARKER_MAGIC && !SOS_SKIP.contains(&nb[1]) {
                    break;
                }
                next += 1;
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
    let flags = h[10];
    if flags & 0b1100_0000 == 0b1100_0000 {
        return None; // reserved bits (6-7) must not both be set
    }
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
    // Logical block size: both-endian at PVD offset 128 (LSB at 128, MSB at 132).
    let block_lsb = u16le(&vd, 128) as u64;
    let block_msb = u16::from_be_bytes([vd[132], vd[133]]) as u64;
    if block_lsb != block_msb || block_lsb == 0 {
        return None;
    }
    let size = lsb * block_lsb;
    if size < 32768 || start + size > file_len {
        return None;
    }
    Some(HitInfo { size: Some(size), count: None })
}

fn crc32(data: &[u8], init: u32) -> u32 {
    let mut c = init ^ 0xFFFFFFFF;
    for &b in data {
        c ^= b as u32;
        for _ in 0..8 {
            c = if c & 1 != 0 { (c >> 1) ^ 0xEDB88320 } else { c >> 1 };
        }
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

/// Signature table: one or more magic byte patterns + label + validator +
/// confidence. Pattern order in `magics` determines match priority.
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
    Sig { magics: &[b"XP3\r\n \x1a\n"], label: "XP3 archive", confidence: CONFIDENCE_HIGH, validate: validate_xp3 },
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
];

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
        iso[start + 132..start + 134].copy_from_slice(&blk.to_be_bytes());
        let p = tmp("t.iso", &iso);
        let mut f = File::open(&p).unwrap();
        let info = validate_iso(&mut f, 32768, iso.len() as u64).expect("valid iso");
        assert_eq!(info.size, Some(34816));

        // Wrong magic at 32768 → rejected.
        let mut bad = iso.clone();
        bad[32768 + 1..32768 + 6].copy_from_slice(b"XXXXX");
        let p2 = tmp("bad.iso", &bad);
        let mut f2 = File::open(&p2).unwrap();
        assert!(validate_iso(&mut f2, 32768, bad.len() as u64).is_none());
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
        // this dict via liblzma's lzma_alone_encoder).
        let lz = hex("5d00000400ffffffffffffffff00");
        let p = tmp("t.lzma", &lz);
        let mut f = File::open(&p).unwrap();
        assert!(validate_lzma(&mut f, 0, lz.len() as u64).is_some(), "preset0 lzma");
    }

    #[test]
    fn gzip_fextra_and_os_variants() {
        // OS byte 2 (VMS) is legal; FEXTRA must skip its XLEN payload before
        // the FNAME walk — the extra payload may contain NUL bytes that would
        // otherwise be mistaken for the name terminator.
        let mut gz = Vec::new();
        gz.extend_from_slice(b"\x1f\x8b\x08");
        gz.push(0x0C); // FLG: FEXTRA | FNAME
        gz.extend_from_slice(&0u32.to_le_bytes()); // MTIME = 0
        gz.push(0x00); // XFL
        gz.push(0x02); // OS = VMS
        gz.extend_from_slice(&4u16.to_le_bytes()); // XLEN
        gz.extend_from_slice(&[0x00, 0x11, 0x00, 0x22]); // extra payload with NULs
        gz.extend_from_slice(b"file.txt\x00");
        let p = tmp("fextra.gz", &gz);
        let mut f = File::open(&p).unwrap();
        assert!(validate_gzip(&mut f, 0, gz.len() as u64).is_some(), "gzip FEXTRA+FNAME OS=2");

        // XLEN extending past EOF → rejected.
        let mut gz3 = gz.clone();
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
        // gzip: 1F 8B 08 FLG=0 MTIME=0 XFL=0 OS=3 (Unix).
        let g = tmp("t.gz", b"\x1f\x8b\x08\x00\x00\x00\x00\x00\x00\x00\x03hello");
        let mut f = File::open(&g).unwrap();
        assert!(validate_gzip(&mut f, 0, 12).is_some());
        // Far-future MTIME → rejected.
        let g2 = tmp("t2.gz", b"\x1f\x8b\x08\x00\xff\xff\xff\xff\x00\x00\x03x");
        let mut f2 = File::open(&g2).unwrap();
        assert!(validate_gzip(&mut f2, 0, 12).is_none());

        // bzip2: full 10-byte magic.
        let b = tmp("t.bz2", b"BZh51AY&SYdata");
        let mut f3 = File::open(&b).unwrap();
        assert!(validate_bzip2(&mut f3, 0, 14).is_some());
        let b2 = tmp("t2.bz2", b"BZhX1AY&SYbad");
        let mut f4 = File::open(&b2).unwrap();
        assert!(validate_bzip2(&mut f4, 0, 14).is_none());

        // xz stream header: magic(6) + flags(2) + crc32(flags)(4).
        let mut x = Vec::new();
        x.extend_from_slice(b"\xfd7zXZ\x00");
        x.extend_from_slice(&[0x00, 0x04]);
        x.extend_from_slice(&crc32(&[0x00, 0x04], 0).to_le_bytes());
        let p = tmp("t.xz", &x);
        let mut f5 = File::open(&p).unwrap();
        assert!(validate_xz(&mut f5, 0, x.len() as u64).is_some());
        // Corrupt flags CRC → rejected.
        let p2 = tmp("t2.xz", b"\xfd7zXZ\x00\x00\x04\x00\x00\x00\x00");
        let mut f6 = File::open(&p2).unwrap();
        assert!(validate_xz(&mut f6, 0, 12).is_none());
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

        // xz header with standard crc32 (0x46b4d6e6 for flags 00 04).
        let xz = hex("fd377a585a000004e6d6b446");
        let p3 = tmp("s.xz", &xz);
        let mut f3 = File::open(&p3).unwrap();
        assert!(validate_xz(&mut f3, 0, xz.len() as u64).is_some(), "real xz header");

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

        // lzma-alone: props 0x5D + dict 0x00080000 (binwalk whitelist) +
        // usize = u64::MAX (streaming).
        let lzma = hex("5d00000800ffffffffffffffff00");
        let p6 = tmp("s.lzma", &lzma);
        let mut f6 = File::open(&p6).unwrap();
        assert!(validate_lzma(&mut f6, 0, lzma.len() as u64).is_some(), "real lzma");
    }
}
