//! PackinOne PBD structure reader.

use crate::io::BinaryReader;
pub use crate::io::Endian;
use std::fmt;

#[derive(Debug, Clone)]
pub struct Pbd {
    pub header: PbdHeader,
    pub root: PbdValue,
    pub trailer: u32,
}

#[derive(Debug, Clone, Copy)]
pub struct PbdHeader {
    pub endian: Endian,
    pub compression: PbdCompression,
    pub seed: u32,
    pub crypt_mode: u16,
    pub inner_iv_len: u16,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PbdCompression {
    None,
    Lz4,
}

#[derive(Debug, Clone, PartialEq)]
pub enum PbdValue {
    Null,
    ObjectNull,
    String(String),
    Bytes(Vec<u8>),
    Integer(i64),
    Real(f64),
    Array(Vec<PbdValue>),
    Dictionary(Vec<(String, PbdValue)>),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PbdLayerImage {
    pub layer_id: String,
}

#[derive(Debug, Clone)]
pub struct PbdError {
    message: String,
}

type Result<T> = std::result::Result<T, PbdError>;

impl Pbd {
    // Handles parse behavior.
    pub fn parse(data: &[u8]) -> Result<Self> {
        Self::parse_with_outer_iv(data, &[])
    }

    // Parses with outer IV.
    pub fn parse_with_outer_iv(data: &[u8], outer_iv: &[u8]) -> Result<Self> {
        let (header, iv, encrypted_payload) = PbdHeader::read(data)?;
        let payload = decrypt_payload(&header, iv, outer_iv, encrypted_payload)?;
        let plain = match header.compression {
            PbdCompression::None => payload,
            PbdCompression::Lz4 => decompress_lz4_stream(&payload, header.endian)?,
        };

        let mut reader = VariantReader::new(&plain, header.endian);
        let root = reader.read_variant()?;
        if reader.remaining() != 4 {
            return Err(PbdError::new(format!(
                "unexpected VariantArchive parse position {}/{}",
                reader.position(),
                plain.len()
            )));
        }
        let trailer = reader.reader.read_u32(header.endian)?;
        Ok(Self {
            header,
            root,
            trailer,
        })
    }

    // Handles layer images behavior.
    pub fn layer_images(&self) -> Vec<PbdLayerImage> {
        let PbdValue::Array(items) = &self.root else {
            return Vec::new();
        };

        items
            .iter()
            .filter_map(|item| {
                let dict = item.as_dictionary()?;
                if dict_get(dict, "layer_type").and_then(PbdValue::as_i64) != Some(0) {
                    return None;
                }
                let layer_id = dict_get(dict, "layer_id")?.as_layer_id()?;
                Some(PbdLayerImage { layer_id })
            })
            .collect()
    }
}

impl PbdHeader {
    // Handles read behavior.
    fn read(data: &[u8]) -> Result<(Self, &[u8], &[u8])> {
        if data.len() < 16 {
            return Err(PbdError::new("PBD header is truncated"));
        }
        let endian = match &data[..4] {
            b"TJS/" => Endian::Little,
            b"TJS\\" => Endian::Big,
            _ => return Err(PbdError::new("PBD magic mismatch")),
        };
        if &data[5..8] != b"s0\0" {
            return Err(PbdError::new("PBD stream marker mismatch"));
        }

        let compression = match data[4] {
            b'n' => PbdCompression::None,
            b'4' => PbdCompression::Lz4,
            marker => {
                return Err(PbdError::new(format!(
                    "unsupported PBD compression marker 0x{marker:02X}"
                )));
            }
        };
        let seed = endian.read_u32(&data[8..12]);
        let crypt_mode = endian.read_u16(&data[12..14]);
        let inner_iv_len = endian.read_u16(&data[14..16]);
        let payload_offset = 16usize
            .checked_add(inner_iv_len as usize)
            .ok_or_else(|| PbdError::new("PBD IV length overflow"))?;
        if payload_offset > data.len() {
            return Err(PbdError::new("PBD IV is truncated"));
        }

        let header = Self {
            endian,
            compression,
            seed,
            crypt_mode,
            inner_iv_len,
        };
        Ok((header, &data[16..payload_offset], &data[payload_offset..]))
    }
}

impl PbdValue {
    // Views this value as dictionary when possible.
    fn as_dictionary(&self) -> Option<&[(String, PbdValue)]> {
        match self {
            Self::Dictionary(items) => Some(items),
            _ => None,
        }
    }

    // Views this value as i64 when possible.
    fn as_i64(&self) -> Option<i64> {
        match self {
            Self::Integer(value) => Some(*value),
            Self::String(value) => value.parse().ok(),
            _ => None,
        }
    }

    // Views this value as layer id when possible.
    fn as_layer_id(&self) -> Option<String> {
        match self {
            Self::Integer(value) if *value >= 0 => Some(value.to_string()),
            Self::String(value) if !value.is_empty() => Some(value.clone()),
            _ => None,
        }
    }
}

impl PbdError {
    // Creates a new value for this type.
    fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
        }
    }
}

impl fmt::Display for PbdError {
    // Formats this value for human-readable output.
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        self.message.fmt(f)
    }
}

impl std::error::Error for PbdError {}

impl From<std::io::Error> for PbdError {
    // Converts a binary reader error into this parser error.
    fn from(value: std::io::Error) -> Self {
        Self::new(value.to_string())
    }
}

struct VariantReader<'a> {
    reader: BinaryReader<'a>,
    endian: Endian,
}

impl<'a> VariantReader<'a> {
    // Creates a new value for this type.
    fn new(data: &'a [u8], endian: Endian) -> Self {
        Self {
            reader: BinaryReader::new(data),
            endian,
        }
    }

    // Handles remaining behavior.
    fn remaining(&self) -> usize {
        self.reader.remaining()
    }

    // Handles position behavior.
    fn position(&self) -> usize {
        self.reader.position()
    }

    // Handles take behavior.
    fn take(&mut self, size: usize) -> Result<&'a [u8]> {
        Ok(self.reader.read_bytes(size)?)
    }

    // Reads TJS string.
    fn read_tjs_string(&mut self) -> Result<String> {
        let char_count = self.reader.read_u32(self.endian)? as usize;
        let bytes = self.take(
            char_count
                .checked_mul(2)
                .ok_or_else(|| PbdError::new("TJS string length overflow"))?,
        )?;
        let words = bytes
            .chunks_exact(2)
            .map(|chunk| self.endian.read_u16(chunk))
            .collect::<Vec<_>>();
        Ok(String::from_utf16_lossy(&words))
    }

    // Reads variant.
    fn read_variant(&mut self) -> Result<PbdValue> {
        let token_pos = self.position();
        let token = self.take(1)?[0];
        let _checker = self.take(1)?[0];

        match token {
            0x00 => Ok(PbdValue::Null),
            0x01 => Ok(PbdValue::ObjectNull),
            0x02 => self.read_tjs_string().map(PbdValue::String),
            0x03 => {
                let size = self.reader.read_u32(self.endian)? as usize;
                self.take(size).map(|bytes| PbdValue::Bytes(bytes.to_vec()))
            }
            0x04 => self
                .reader
                .read_u64(self.endian)
                .map(|value| PbdValue::Integer(value as i64))
                .map_err(PbdError::from),
            0x05 => self
                .reader
                .read_u64(self.endian)
                .map(|value| PbdValue::Real(f64::from_bits(value)))
                .map_err(PbdError::from),
            0x81 => {
                let count = self.reader.read_u32(self.endian)? as usize;
                let mut items = Vec::with_capacity(count);
                for _ in 0..count {
                    items.push(self.read_variant()?);
                }
                Ok(PbdValue::Array(items))
            }
            0xC1 => {
                let count = self.reader.read_u32(self.endian)? as usize;
                let mut items = Vec::with_capacity(count);
                for _ in 0..count {
                    let key = self.read_tjs_string()?;
                    let value = self.read_variant()?;
                    items.push((key, value));
                }
                Ok(PbdValue::Dictionary(items))
            }
            _ => Err(PbdError::new(format!(
                "unknown VariantArchive token 0x{token:02X} at 0x{token_pos:X}"
            ))),
        }
    }
}

// Handles dict get behavior.
fn dict_get<'a>(dict: &'a [(String, PbdValue)], key: &str) -> Option<&'a PbdValue> {
    dict.iter()
        .find_map(|(item_key, value)| (item_key == key).then_some(value))
}

// Decrypts payload.
fn decrypt_payload(
    header: &PbdHeader,
    inner_iv: &[u8],
    outer_iv: &[u8],
    payload: &[u8],
) -> Result<Vec<u8>> {
    let Some((rounds, block_count)) = crypt_params(header.crypt_mode) else {
        return Err(PbdError::new(format!(
            "unsupported PBD crypt mode {}",
            header.crypt_mode
        )));
    };
    let iv = if outer_iv.is_empty() {
        inner_iv
    } else {
        outer_iv
    };
    let stream = packinone_keystream(header.seed, iv, payload.len(), rounds, block_count);
    Ok(payload
        .iter()
        .zip(stream)
        .map(|(byte, key)| byte ^ key)
        .collect())
}

// Handles crypt params behavior.
fn crypt_params(mode: u16) -> Option<(usize, usize)> {
    match mode {
        1 => Some((8, 16)),
        2 => Some((12, 8)),
        3 => Some((20, 4)),
        4 => Some((8, 1)),
        5 => Some((12, 1)),
        6 => Some((20, 1)),
        _ => None,
    }
}

// Decompresses LZ4 stream.
fn decompress_lz4_stream(data: &[u8], endian: Endian) -> Result<Vec<u8>> {
    let mut output = Vec::new();
    let mut pos = 0usize;
    while pos < data.len() {
        if pos + 2 > data.len() {
            return Err(PbdError::new("truncated LZ4 stream block header"));
        }
        let packed_size = endian.read_u16(&data[pos..pos + 2]) as usize;
        pos += 2;
        if packed_size == 0 {
            break;
        }
        let end = pos
            .checked_add(packed_size)
            .ok_or_else(|| PbdError::new("LZ4 block size overflow"))?;
        if end > data.len() {
            return Err(PbdError::new("truncated LZ4 stream block payload"));
        }
        lz4_stream_block(&data[pos..end], &mut output)?;
        pos = end;
    }
    if pos != data.len() {
        return Err(PbdError::new(format!(
            "unexpected trailing bytes after LZ4 stream: {}",
            data.len() - pos
        )));
    }
    Ok(output)
}

// Handles LZ4 stream block behavior.
fn lz4_stream_block(payload: &[u8], output: &mut Vec<u8>) -> Result<()> {
    let mut pos = 0usize;
    while pos < payload.len() {
        let token = payload[pos];
        pos += 1;

        let literal_len = read_lz4_len(payload, &mut pos, token >> 4)?;
        let literal_end = pos
            .checked_add(literal_len)
            .ok_or_else(|| PbdError::new("LZ4 literal length overflow"))?;
        if literal_end > payload.len() {
            return Err(PbdError::new("truncated LZ4 literals"));
        }
        output.extend_from_slice(&payload[pos..literal_end]);
        pos = literal_end;

        if pos == payload.len() {
            break;
        }
        if pos + 2 > payload.len() {
            return Err(PbdError::new("truncated LZ4 match offset"));
        }
        let offset = (payload[pos] as usize) | ((payload[pos + 1] as usize) << 8);
        pos += 2;
        if offset == 0 || offset > output.len() {
            return Err(PbdError::new(format!("invalid LZ4 match offset {offset}")));
        }

        let match_len = read_lz4_len(payload, &mut pos, token & 0x0F)? + 4;
        let match_pos = output.len() - offset;
        for index in 0..match_len {
            let value = output[match_pos + index];
            output.push(value);
        }
    }
    Ok(())
}

// Reads LZ4 len.
fn read_lz4_len(payload: &[u8], pos: &mut usize, nibble: u8) -> Result<usize> {
    let mut len = nibble as usize;
    if nibble != 15 {
        return Ok(len);
    }
    loop {
        if *pos >= payload.len() {
            return Err(PbdError::new("truncated LZ4 length"));
        }
        let value = payload[*pos] as usize;
        *pos += 1;
        len = len
            .checked_add(value)
            .ok_or_else(|| PbdError::new("LZ4 length overflow"))?;
        if value != 255 {
            return Ok(len);
        }
    }
}

const MASK32: u32 = u32::MAX;
const CHACHA_CONST: [u8; 16] = [
    154, 135, 143, 158, 145, 155, 223, 204, 205, 210, 157, 134, 139, 154, 223, 148,
];
const BLAKE2S_IV: [u32; 8] = [
    0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A, 0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19,
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

// Handles packinone keystream behavior.
fn packinone_keystream(
    seed: u32,
    iv: &[u8],
    size: usize,
    rounds: usize,
    block_count: usize,
) -> Vec<u8> {
    let digest = keyed_blake2s_32(seed, iv);
    let iv_hash = packinone_xxh32(iv, seed);
    let fallback = if iv_hash == seed {
        seed
    } else {
        iv_hash ^ seed
    };

    let mut base_state = [0u32; 16];
    for index in 0..4 {
        base_state[index] = BinaryReader::u32_le(&CHACHA_CONST[index * 4..index * 4 + 4]);
    }
    for index in 0..8 {
        base_state[index + 4] = !BinaryReader::u32_le(&digest[index * 4..index * 4 + 4]);
    }
    base_state[12] = MASK32;
    base_state[13] = MASK32;
    base_state[14] = !iv_hash;
    base_state[15] = !seed;

    let mut output = Vec::with_capacity(size);
    let mut counter = 0u64;
    while output.len() < size {
        let mut state = base_state;
        state[12] = !(counter as u32);
        state[13] = !((counter >> 32) as u32);
        counter = counter.wrapping_add(1);

        let mut block = packinone_chacha_block(&state, rounds);
        for src_group in 0..(4 * block_count).saturating_sub(4) {
            let offset = src_group * 16;
            let mut group = [0u8; 16];
            for index in 0..4 {
                let word_offset = offset + index * 4;
                let mut word = BinaryReader::u32_le(&block[word_offset..word_offset + 4]);
                word ^= word.wrapping_shl(13);
                word ^= word >> 17;
                word ^= word.wrapping_shl(5);
                if word == 0 {
                    word = fallback;
                }
                group[index * 4..index * 4 + 4].copy_from_slice(&word.to_le_bytes());
            }
            block.extend_from_slice(&group);
        }
        let take = (block_count * 64).min(size - output.len());
        output.extend_from_slice(&block[..take]);
    }
    output
}

// Handles packinone ChaCha block behavior.
fn packinone_chacha_block(state: &[u32; 16], rounds: usize) -> Vec<u8> {
    let mut work = [0u32; 16];
    for (dst, src) in work.iter_mut().zip(state) {
        *dst = !*src;
    }

    for _ in 0..rounds.div_ceil(2) {
        quarter_round(&mut work, 0, 4, 8, 12);
        quarter_round(&mut work, 1, 5, 9, 13);
        quarter_round(&mut work, 2, 6, 10, 14);
        quarter_round(&mut work, 3, 7, 11, 15);
        quarter_round(&mut work, 0, 5, 10, 15);
        quarter_round(&mut work, 1, 6, 11, 12);
        quarter_round(&mut work, 2, 7, 8, 13);
        quarter_round(&mut work, 3, 4, 9, 14);
    }

    let mut out = Vec::with_capacity(64);
    for (index, word) in work.iter().enumerate() {
        out.extend_from_slice(&word.wrapping_add(!state[index]).to_le_bytes());
    }
    out
}

// Applies one ChaCha quarter-round step for round.
fn quarter_round(state: &mut [u32; 16], a: usize, b: usize, c: usize, d: usize) {
    state[a] = state[a].wrapping_add(state[b]);
    state[d] = (state[d] ^ state[a]).rotate_left(16);
    state[c] = state[c].wrapping_add(state[d]);
    state[b] = (state[b] ^ state[c]).rotate_left(12);
    state[a] = state[a].wrapping_add(state[b]);
    state[d] = (state[d] ^ state[a]).rotate_left(8);
    state[c] = state[c].wrapping_add(state[d]);
    state[b] = (state[b] ^ state[c]).rotate_left(7);
}

// Handles packinone XXH32 behavior.
fn packinone_xxh32(data: &[u8], seed: u32) -> u32 {
    const PRIME32_1: u32 = 0x9E3779B1;
    const PRIME32_2: u32 = 0x85EBCA77;
    const PRIME32_3: u32 = 0xC2B2AE3D;
    const PRIME32_4: u32 = 0x27D4EB2F;
    const PRIME32_5: u32 = 0x165667B1;

    let mut pos = 0usize;
    let mut h;
    if data.len() >= 16 {
        let mut v1 = seed.wrapping_add(PRIME32_1).wrapping_add(PRIME32_2);
        let mut v2 = seed.wrapping_add(PRIME32_2);
        let mut v3 = seed;
        let mut v4 = seed.wrapping_sub(PRIME32_1);
        let limit = data.len() - 16;
        while pos <= limit {
            v1 = round_xxh32(v1, BinaryReader::u32_le(&data[pos..pos + 4]));
            pos += 4;
            v2 = round_xxh32(v2, BinaryReader::u32_le(&data[pos..pos + 4]));
            pos += 4;
            v3 = round_xxh32(v3, BinaryReader::u32_le(&data[pos..pos + 4]));
            pos += 4;
            v4 = round_xxh32(v4, BinaryReader::u32_le(&data[pos..pos + 4]));
            pos += 4;
        }
        h = v1
            .rotate_left(1)
            .wrapping_add(v2.rotate_left(7))
            .wrapping_add(v3.rotate_left(12))
            .wrapping_add(v4.rotate_left(18));
    } else {
        h = seed.wrapping_add(PRIME32_5);
    }

    h = h.wrapping_add(data.len() as u32);
    while pos + 4 <= data.len() {
        h = h
            .wrapping_add(BinaryReader::u32_le(&data[pos..pos + 4]).wrapping_mul(PRIME32_3))
            .rotate_left(17)
            .wrapping_mul(PRIME32_4);
        pos += 4;
    }
    while pos < data.len() {
        h = h
            .wrapping_add((data[pos] as u32).wrapping_mul(PRIME32_5))
            .rotate_left(11)
            .wrapping_mul(PRIME32_1);
        pos += 1;
    }

    h ^= h >> 15;
    h = h.wrapping_mul(PRIME32_2);
    h ^= h >> 13;
    h = h.wrapping_mul(PRIME32_3);
    h ^= h >> 16;
    h
}

// Handles round XXH32 behavior.
fn round_xxh32(acc: u32, input: u32) -> u32 {
    acc.wrapping_add(input.wrapping_mul(0x85EBCA77))
        .rotate_left(13)
        .wrapping_mul(0x9E3779B1)
}

// Handles keyed BLAKE2s 32 behavior.
fn keyed_blake2s_32(seed: u32, data: &[u8]) -> [u8; 32] {
    let mut h = BLAKE2S_IV;
    h[0] ^= 0x0101_0420;

    let mut key_block = [0u8; 64];
    key_block[..4].copy_from_slice(&seed.to_le_bytes());
    if data.is_empty() {
        blake2s_compress(&mut h, &key_block, 64, true);
    } else {
        blake2s_compress(&mut h, &key_block, 64, false);
        let mut total = 64u64;
        let mut chunks = data.chunks(64).peekable();
        while let Some(chunk) = chunks.next() {
            total += chunk.len() as u64;
            let mut block = [0u8; 64];
            block[..chunk.len()].copy_from_slice(chunk);
            blake2s_compress(&mut h, &block, total, chunks.peek().is_none());
        }
    }

    let mut out = [0u8; 32];
    for (index, word) in h.iter().enumerate() {
        out[index * 4..index * 4 + 4].copy_from_slice(&word.to_le_bytes());
    }
    out
}

// Handles BLAKE2s compress behavior.
fn blake2s_compress(h: &mut [u32; 8], block: &[u8; 64], count: u64, last: bool) {
    let mut m = [0u32; 16];
    for index in 0..16 {
        m[index] = BinaryReader::u32_le(&block[index * 4..index * 4 + 4]);
    }

    let mut v = [0u32; 16];
    v[..8].copy_from_slice(h);
    v[8..].copy_from_slice(&BLAKE2S_IV);
    v[12] ^= count as u32;
    v[13] ^= (count >> 32) as u32;
    if last {
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

    for index in 0..8 {
        h[index] ^= v[index] ^ v[index + 8];
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    // Handles packinone BLAKE2s digest matches verified empty IV behavior.
    fn packinone_blake2s_digest_matches_verified_empty_iv() {
        let digest = keyed_blake2s_32(0x6F19_4343, &[]);
        assert_eq!(
            hex_lower(&digest),
            "213878d32b20551d020b15e6fbd57604c3547129d09e2bbb2ff45b30781658d4"
        );
    }

    #[test]
    // Handles layer images extracts normal layer ids behavior.
    fn layer_images_extracts_normal_layer_ids() {
        let pbd = Pbd {
            header: PbdHeader {
                endian: Endian::Little,
                compression: PbdCompression::Lz4,
                seed: 0,
                crypt_mode: 1,
                inner_iv_len: 0,
            },
            root: PbdValue::Array(vec![
                PbdValue::Dictionary(vec![
                    ("layer_type".to_owned(), PbdValue::Integer(0)),
                    ("layer_id".to_owned(), PbdValue::Integer(2841)),
                ]),
                PbdValue::Dictionary(vec![
                    ("layer_type".to_owned(), PbdValue::Integer(2)),
                    ("layer_id".to_owned(), PbdValue::Integer(99)),
                ]),
            ]),
            trailer: 0,
        };

        assert_eq!(
            pbd.layer_images(),
            vec![PbdLayerImage {
                layer_id: "2841".to_owned()
            }]
        );
    }

    // Handles hex lower behavior.
    fn hex_lower(data: &[u8]) -> String {
        let mut out = String::with_capacity(data.len() * 2);
        for byte in data {
            use std::fmt::Write;
            let _ = write!(out, "{byte:02x}");
        }
        out
    }
}
