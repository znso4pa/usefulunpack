//! Cxdec CompoundStorageMedia pathHash/fileHash helpers.

use crate::io::{BinaryReader, BinaryWriter};

pub const PATH_HASH_LEN: usize = 8;
pub const FILE_HASH_LEN: usize = 32;

const BLAKE2S_BLOCK_LEN: usize = 64;
const BLAKE2S_IV: [u32; 8] = [
    0x6a09_e667,
    0xbb67_ae85,
    0x3c6e_f372,
    0xa54f_f53a,
    0x510e_527f,
    0x9b05_688c,
    0x1f83_d9ab,
    0x5be0_cd19,
];
const BLAKE2S_SIGMA: [[usize; 16]; 10] = [
    [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15],
    [14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3],
    [11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4],
    [7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8],
    [9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13],
    [2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9],
    [12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11],
    [13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10],
    [6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5],
    [10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0],
];

// Computes the PackinOne directory hash for an archive path.
pub fn path_hash(path: &str, domain: &str) -> [u8; PATH_HASH_LEN] {
    let data = compound_hash_input(path, domain);
    siphash24_empty_key(&data)
}

// Computes the PackinOne file-name hash for an archive leaf name.
pub fn file_hash(name: &str, domain: &str) -> [u8; FILE_HASH_LEN] {
    let data = compound_hash_input(name, domain);
    blake2s_256(&data)
}

// Formats bytes as uppercase hexadecimal text.
pub fn hex_upper(data: &[u8]) -> String {
    let mut out = String::with_capacity(data.len() * 2);
    for byte in data {
        use std::fmt::Write;
        let _ = write!(out, "{byte:02X}");
    }
    out
}

// Handles compound hash input behavior.
fn compound_hash_input(value: &str, domain: &str) -> Vec<u8> {
    let mut out = utf16le(value);
    if !domain.is_empty() {
        out.extend_from_slice(&utf16le(domain));
    }
    out
}

// Handles utf16le behavior.
fn utf16le(value: &str) -> Vec<u8> {
    let mut out = Vec::with_capacity(value.len() * 2);
    for word in value.encode_utf16() {
        out.extend_from_slice(&word.to_le_bytes());
    }
    out
}

// Handles siphash24 empty key behavior.
fn siphash24_empty_key(data: &[u8]) -> [u8; PATH_HASH_LEN] {
    let mut v0 = 0x736f_6d65_7073_6575u64;
    let mut v1 = 0x646f_7261_6e64_6f6du64;
    let mut v2 = 0x6c79_6765_6e65_7261u64;
    let mut v3 = 0x7465_6462_7974_6573u64;

    let mut chunks = data.chunks_exact(8);
    for chunk in &mut chunks {
        let m = BinaryReader::u64_le(chunk);
        v3 ^= m;
        sip_round(&mut v0, &mut v1, &mut v2, &mut v3);
        sip_round(&mut v0, &mut v1, &mut v2, &mut v3);
        v0 ^= m;
    }

    let mut tail = (data.len() as u64 & 0xff) << 56;
    for (i, byte) in chunks.remainder().iter().enumerate() {
        tail |= (*byte as u64) << (8 * i);
    }
    v3 ^= tail;
    sip_round(&mut v0, &mut v1, &mut v2, &mut v3);
    sip_round(&mut v0, &mut v1, &mut v2, &mut v3);
    v0 ^= tail;

    v2 ^= 0xff;
    for _ in 0..4 {
        sip_round(&mut v0, &mut v1, &mut v2, &mut v3);
    }

    (v0 ^ v1 ^ v2 ^ v3).to_le_bytes()
}

// Handles sip round behavior.
fn sip_round(v0: &mut u64, v1: &mut u64, v2: &mut u64, v3: &mut u64) {
    *v0 = v0.wrapping_add(*v1);
    *v1 = v1.rotate_left(13);
    *v1 ^= *v0;
    *v0 = v0.rotate_left(32);

    *v2 = v2.wrapping_add(*v3);
    *v3 = v3.rotate_left(16);
    *v3 ^= *v2;

    *v0 = v0.wrapping_add(*v3);
    *v3 = v3.rotate_left(21);
    *v3 ^= *v0;

    *v2 = v2.wrapping_add(*v1);
    *v1 = v1.rotate_left(17);
    *v1 ^= *v2;
    *v2 = v2.rotate_left(32);
}

// Handles BLAKE2s 256 behavior.
fn blake2s_256(data: &[u8]) -> [u8; FILE_HASH_LEN] {
    let mut h = BLAKE2S_IV;
    h[0] ^= 0x0101_0020;

    let mut offset = 0usize;
    while data.len().saturating_sub(offset) > BLAKE2S_BLOCK_LEN {
        let block = &data[offset..offset + BLAKE2S_BLOCK_LEN];
        compress_blake2s(&mut h, block, (offset + BLAKE2S_BLOCK_LEN) as u64, false);
        offset += BLAKE2S_BLOCK_LEN;
    }

    let remaining = &data[offset..];
    let mut block = [0u8; BLAKE2S_BLOCK_LEN];
    block[..remaining.len()].copy_from_slice(remaining);
    compress_blake2s(&mut h, &block, data.len() as u64, true);

    let mut out = [0u8; FILE_HASH_LEN];
    for (i, value) in h.iter().enumerate() {
        BinaryWriter::u32_le(*value, &mut out[i * 4..i * 4 + 4]);
    }
    out
}

// Compresses BLAKE2s.
fn compress_blake2s(h: &mut [u32; 8], block: &[u8], counter: u64, final_block: bool) {
    let mut m = [0u32; 16];
    for (i, slot) in m.iter_mut().enumerate() {
        let start = i * 4;
        *slot = BinaryReader::u32_le(&block[start..start + 4]);
    }

    let mut v = [0u32; 16];
    v[..8].copy_from_slice(h);
    v[8..].copy_from_slice(&BLAKE2S_IV);
    v[12] ^= counter as u32;
    v[13] ^= (counter >> 32) as u32;
    if final_block {
        v[14] = !v[14];
    }

    for sigma in BLAKE2S_SIGMA {
        blake2s_g(&mut v, 0, 4, 8, 12, m[sigma[0]], m[sigma[1]]);
        blake2s_g(&mut v, 1, 5, 9, 13, m[sigma[2]], m[sigma[3]]);
        blake2s_g(&mut v, 2, 6, 10, 14, m[sigma[4]], m[sigma[5]]);
        blake2s_g(&mut v, 3, 7, 11, 15, m[sigma[6]], m[sigma[7]]);
        blake2s_g(&mut v, 0, 5, 10, 15, m[sigma[8]], m[sigma[9]]);
        blake2s_g(&mut v, 1, 6, 11, 12, m[sigma[10]], m[sigma[11]]);
        blake2s_g(&mut v, 2, 7, 8, 13, m[sigma[12]], m[sigma[13]]);
        blake2s_g(&mut v, 3, 4, 9, 14, m[sigma[14]], m[sigma[15]]);
    }

    for i in 0..8 {
        h[i] ^= v[i] ^ v[i + 8];
    }
}

// Handles BLAKE2s g behavior.
fn blake2s_g(v: &mut [u32; 16], a: usize, b: usize, c: usize, d: usize, x: u32, y: u32) {
    v[a] = v[a].wrapping_add(v[b]).wrapping_add(x);
    v[d] = (v[d] ^ v[a]).rotate_right(16);
    v[c] = v[c].wrapping_add(v[d]);
    v[b] = (v[b] ^ v[c]).rotate_right(12);
    v[a] = v[a].wrapping_add(v[b]).wrapping_add(y);
    v[d] = (v[d] ^ v[a]).rotate_right(8);
    v[c] = v[c].wrapping_add(v[d]);
    v[b] = (v[b] ^ v[c]).rotate_right(7);
}
