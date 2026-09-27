//! KiriKiri HxV4 pure computation layer.

use crate::crypto::hxv4_shellcode::{CxEncryption, CxError};
use crate::io::{BinaryIo, BinaryReader, BinaryWriter};
use flate2::read::ZlibDecoder;
use std::collections::HashMap;
use std::io::Read;
use std::path::Path;

pub const HX_HEADER_KEY_LEN: usize = 16;
pub const HX_CHACHA_BLOCK_LEN: usize = 64;
pub const HX_CHACHA_KEY_LEN: usize = 32;
pub const HX_CHACHA_NONCE_LEN: usize = 16;
pub const HX_ENTRY_FILTER_FLAG: i64 = 0x1_0000_0000;
pub const HX_INDEX_PREFIX_LEN: usize = 16;

const HX_CHACHA_CONSTANT: &[u8; 16] = b"expand 32-byte k";

#[derive(Debug, Clone)]
pub struct HxEntryInfo {
    pub path: Option<String>,
    pub name: Option<String>,
    pub path_hash: Vec<u8>,
    pub file_hash: Vec<u8>,
    pub id: i64,
    pub key: i64,
    pub filter: Option<HxFilter>,
}

#[derive(Debug, Clone)]
pub struct HxFilterKey {
    pub key: [u64; 2],
    pub split_position: u64,
    pub header_key: [u8; HX_HEADER_KEY_LEN],
    pub has_header_key: bool,
    pub flag: bool,
}

#[derive(Debug, Clone)]
pub struct HxFilter {
    spans: [HxFilterSpanDecryptor; 2],
    split_position: u64,
    header_key: Option<[u8; HX_HEADER_KEY_LEN]>,
}

#[derive(Debug, Clone, Copy)]
pub struct HxFilterSpanDecryptor {
    span_position: [u64; 2],
    first_decrypt_key: u32,
    decrypt_key: u32,
}

#[derive(Debug, Clone)]
pub struct HxChachaDecryptor {
    state: [u8; HX_CHACHA_BLOCK_LEN],
}

#[derive(Debug, Clone)]
enum HxIndexValue {
    Null,
    String,
    Bytes(Vec<u8>),
    Integer(i64),
    Array(Vec<HxIndexValue>),
    Dictionary,
}

impl HxEntryInfo {
    // Creates a new value for this type.
    pub fn new(id: i64, key: i64) -> Self {
        HxEntryInfo {
            path: None,
            name: None,
            path_hash: Vec::new(),
            file_hash: Vec::new(),
            id,
            key,
            filter: None,
        }
    }
}

impl HxFilterKey {
    // Handles from entry key behavior.
    pub fn from_entry_key(
        derivation: &mut CxEncryption,
        entry_key: u64,
        header_key_seed: u64,
    ) -> Result<Self, CxError> {
        let key0 = entry_key as u32;
        let key1 = (entry_key >> 32) as u32;

        let k0 = derivation.derive_pair(key0)?;
        let k1 = derivation.derive_pair(key1)?;

        let mut result = HxFilterKey {
            key: [pack_pair(k0), pack_pair(k1)],
            split_position: derivation
                .offset()
                .wrapping_add(((entry_key >> 16) as u32) & derivation.mask())
                as u64,
            header_key: [0; HX_HEADER_KEY_LEN],
            has_header_key: true,
            flag: false,
        };

        let k3 = derivation.derive_pair(header_key_seed as u32)?;
        let mut header = !pack_pair(k3);
        BinaryWriter::u64_be(header, &mut result.header_key[..8]);

        let k4 = derivation.derive_pair(header as u32)?;
        header = !pack_pair(k4);
        BinaryWriter::u64_be(header, &mut result.header_key[8..]);

        Ok(result)
    }
}

impl HxFilter {
    // Creates a new value for this type.
    pub fn new(key: HxFilterKey) -> Self {
        HxFilter {
            spans: [
                HxFilterSpanDecryptor::new(key.key[0], key.flag),
                HxFilterSpanDecryptor::new(key.key[1], key.flag),
            ],
            split_position: key.split_position,
            header_key: key.has_header_key.then_some(key.header_key),
        }
    }

    // Handles from entry info behavior.
    pub fn from_entry_info(
        derivation: &mut CxEncryption,
        filter_key: u64,
        entry: &HxEntryInfo,
    ) -> Result<Self, CxError> {
        let mut entry_key = entry.key as u64;
        if entry.id & HX_ENTRY_FILTER_FLAG == 0 {
            entry_key ^= filter_key;
        }
        let key = HxFilterKey::from_entry_key(derivation, entry_key, !entry_key)?;
        Ok(HxFilter::new(key))
    }

    // Handles decrypt behavior.
    pub fn decrypt(&self, position: u64, buffer: &mut [u8]) {
        if buffer.is_empty() {
            return;
        }
        if let Some(header_key) = self.header_key {
            decrypt_header_key(position, buffer, &header_key);
        }

        let end = position.saturating_add(buffer.len() as u64);
        if self.split_position > position && self.split_position < end {
            let first_len = (self.split_position - position) as usize;
            self.spans[0].decrypt(position, &mut buffer[..first_len]);
            self.spans[1].decrypt(self.split_position, &mut buffer[first_len..]);
        } else if self.split_position > position {
            self.spans[0].decrypt(position, buffer);
        } else {
            self.spans[1].decrypt(position, buffer);
        }
    }
}

impl HxFilterSpanDecryptor {
    // Creates a new value for this type.
    pub fn new(key: u64, flag: bool) -> Self {
        let mut decrypt_key = ((key >> 8) & 0xff) as u32;
        decrypt_key |= ((key >> 8) & 0xff00) as u32;

        let mut span_position = [(key >> 48) & 0xffff, (key >> 32) & 0xffff];
        if span_position[0] == span_position[1] {
            span_position[1] = span_position[1].wrapping_add(1);
        }

        let mut first_decrypt_key = (key & 0xff) as u32;
        if flag {
            decrypt_key = 0;
        }
        if !flag && first_decrypt_key == 0 {
            first_decrypt_key = 0xa5;
        }
        first_decrypt_key = first_decrypt_key.wrapping_mul(0x0101_0101);

        HxFilterSpanDecryptor {
            span_position,
            first_decrypt_key,
            decrypt_key,
        }
    }

    // Handles decrypt behavior.
    pub fn decrypt(&self, position: u64, buffer: &mut [u8]) {
        if buffer.is_empty() {
            return;
        }

        let key_bytes = self.first_decrypt_key.to_le_bytes();
        for (i, value) in buffer.iter_mut().enumerate() {
            *value ^= key_bytes[((position + i as u64) & 3) as usize];
        }

        let key1 = (self.decrypt_key & 0xff) as u8;
        let key2 = ((self.decrypt_key >> 8) & 0xff) as u8;
        xor_position(buffer, position, self.span_position[0], key1);
        xor_position(buffer, position, self.span_position[1], key2);
    }
}

impl HxChachaDecryptor {
    // Creates a new value for this type.
    pub fn new(key: &[u8], nonce: &[u8], seed: [u32; 2]) -> Option<Self> {
        if key.len() != HX_CHACHA_KEY_LEN || nonce.len() != HX_CHACHA_NONCE_LEN {
            return None;
        }

        let mut state = [0u8; HX_CHACHA_BLOCK_LEN];
        state[0..16].copy_from_slice(HX_CHACHA_CONSTANT);
        state[16..32].copy_from_slice(&key[0..16]);
        state[32..48].copy_from_slice(&key[16..32]);
        state[48..52].copy_from_slice(&seed[0].to_le_bytes());
        state[52..56].copy_from_slice(&seed[1].to_le_bytes());
        state[56..64].copy_from_slice(&nonce[0..8]);
        Some(HxChachaDecryptor { state })
    }

    // Decrypts in place.
    pub fn decrypt_in_place(&mut self, data: &mut [u8]) {
        let mut offset = 0usize;
        while offset < data.len() {
            let block = self.key_stream_block();
            let count = (data.len() - offset).min(HX_CHACHA_BLOCK_LEN);
            for i in 0..count {
                data[offset + i] ^= block[i];
            }
            offset += count;
        }
    }

    // Handles decrypt behavior.
    pub fn decrypt(&mut self, input: &[u8], output: &mut [u8]) -> Option<()> {
        if output.len() < input.len() {
            return None;
        }
        output[..input.len()].copy_from_slice(input);
        self.decrypt_in_place(&mut output[..input.len()]);
        Some(())
    }

    // Handles key stream block behavior.
    fn key_stream_block(&mut self) -> [u8; HX_CHACHA_BLOCK_LEN] {
        let transformed = transform_state(&self.state);
        let mut out = [0u8; HX_CHACHA_BLOCK_LEN];
        for i in (0..HX_CHACHA_BLOCK_LEN).step_by(4) {
            let value = BinaryReader::u32_le_at(&self.state, i)
                .wrapping_add(BinaryReader::u32_le_at(&transformed, i));
            out[i..i + 4].copy_from_slice(&value.to_le_bytes());
        }
        increment_counter(&mut self.state[48..56]);
        out
    }
}

// Handles unicode name from hash behavior.
pub fn unicode_name_from_hash(mut hash: u32) -> String {
    let mut out = String::new();
    while let Some(ch) = char::from_u32((hash & 0x3fff) + 0x5000) {
        out.push(ch);
        hash >>= 14;
        if hash == 0 {
            break;
        }
    }
    out
}

// Reads HX index.
pub fn read_hx_index(
    data: &[u8],
    index_key1: &[u8],
    index_key2: &[u8],
    names_file: Option<&Path>,
) -> std::io::Result<HashMap<String, HxEntryInfo>> {
    if data.len() <= HX_INDEX_PREFIX_LEN + 4 {
        return Err(invalid("Hx index is too small"));
    }
    let mut crypt = HxChachaDecryptor::new(index_key1, index_key2, [1, 0])
        .ok_or_else(|| invalid("invalid Hx index key sizes"))?;
    let mut decrypted = vec![0u8; data.len() - HX_INDEX_PREFIX_LEN];
    crypt
        .decrypt(&data[HX_INDEX_PREFIX_LEN..], &mut decrypted)
        .ok_or_else(|| invalid("Hx index decrypt failed"))?;

    let mut decoder = ZlibDecoder::new(&decrypted[4..]);
    let mut index = Vec::new();
    decoder.read_to_end(&mut index)?;

    let mut cursor = std::io::Cursor::new(index.as_slice());
    let root = read_index_object(&mut cursor)?;
    let HxIndexValue::Array(root) = root else {
        return Err(invalid("Hx index root is not an array"));
    };

    let (path_map, name_map) = read_hx_names(names_file);
    let mut out = HashMap::new();
    for dir_pair in root.chunks_exact(2) {
        let HxIndexValue::Bytes(path_hash) = &dir_pair[0] else {
            continue;
        };
        let HxIndexValue::Array(dir_obj) = &dir_pair[1] else {
            continue;
        };
        let path_hash_bytes = path_hash.clone();
        let path_hash = hex_upper(path_hash);

        for entry_pair in dir_obj.chunks_exact(2) {
            let HxIndexValue::Bytes(entry_hash) = &entry_pair[0] else {
                continue;
            };
            let HxIndexValue::Array(entry_obj) = &entry_pair[1] else {
                continue;
            };
            if entry_obj.len() < 2 {
                continue;
            }
            let HxIndexValue::Integer(entry_id) = entry_obj[0] else {
                continue;
            };
            let HxIndexValue::Integer(entry_key) = entry_obj[1] else {
                continue;
            };

            let mut entry = HxEntryInfo::new(entry_id, entry_key);
            entry.path_hash = path_hash_bytes.clone();
            entry.file_hash = entry_hash.clone();
            if let Some(path) = path_map.get(&path_hash) {
                entry.path = Some(path.clone());
            }
            if let Some(name) = name_map.get(&hex_upper(entry_hash)) {
                entry.name = Some(name.clone());
            }
            out.insert(unicode_name_from_hash(entry_id as u32), entry);
        }
    }
    Ok(out)
}

// Packs pair.
fn pack_pair(pair: (u32, u32)) -> u64 {
    pair.0 as u64 | ((pair.1 as u64) << 32)
}

// Decrypts header key.
fn decrypt_header_key(position: u64, buffer: &mut [u8], key: &[u8; HX_HEADER_KEY_LEN]) {
    if position >= HX_HEADER_KEY_LEN as u64 {
        return;
    }
    let start = position as usize;
    let count = buffer.len().min(HX_HEADER_KEY_LEN - start);
    for i in 0..count {
        buffer[i] ^= key[start + i];
    }
}

// Handles xor position behavior.
fn xor_position(buffer: &mut [u8], position: u64, key_position: u64, key: u8) {
    if key == 0 || key_position < position {
        return;
    }
    let index = key_position - position;
    if index < buffer.len() as u64 {
        buffer[index as usize] ^= key;
    }
}

// Handles quarter behavior.
fn quarter(a: &mut u32, b: &mut u32, c: &mut u32, d: &mut u32) {
    *a = a.wrapping_add(*b);
    *d = (*d ^ *a).rotate_left(16);
    *c = c.wrapping_add(*d);
    *b = (*b ^ *c).rotate_left(12);
    *a = a.wrapping_add(*b);
    *d = (*d ^ *a).rotate_left(8);
    *c = c.wrapping_add(*d);
    *b = (*b ^ *c).rotate_left(7);
}

// Transforms state.
fn transform_state(state: &[u8; HX_CHACHA_BLOCK_LEN]) -> [u8; HX_CHACHA_BLOCK_LEN] {
    let mut z = [0u32; 16];
    for (i, slot) in z.iter_mut().enumerate() {
        *slot = BinaryReader::u32_le_at(state, i * 4);
    }

    z = chacha_transform_words(z, 10);

    let mut out = [0u8; HX_CHACHA_BLOCK_LEN];
    for (i, value) in z.into_iter().enumerate() {
        BinaryWriter::u32_le_at(value, &mut out, i * 4);
    }
    out
}

// Handles ChaCha transform words behavior.
pub(crate) fn chacha_transform_words(mut z: [u32; 16], double_rounds: usize) -> [u32; 16] {
    for _ in 0..double_rounds {
        quarter_index(&mut z, 0, 4, 8, 12);
        quarter_index(&mut z, 1, 5, 9, 13);
        quarter_index(&mut z, 2, 6, 10, 14);
        quarter_index(&mut z, 3, 7, 11, 15);
        quarter_index(&mut z, 0, 5, 10, 15);
        quarter_index(&mut z, 1, 6, 11, 12);
        quarter_index(&mut z, 2, 7, 8, 13);
        quarter_index(&mut z, 3, 4, 9, 14);
    }
    z
}

// Applies one ChaCha quarter-round step for index.
fn quarter_index(z: &mut [u32; 16], a: usize, b: usize, c: usize, d: usize) {
    let mut va = z[a];
    let mut vb = z[b];
    let mut vc = z[c];
    let mut vd = z[d];
    quarter(&mut va, &mut vb, &mut vc, &mut vd);
    z[a] = va;
    z[b] = vb;
    z[c] = vc;
    z[d] = vd;
}

// Increments counter.
fn increment_counter(counter: &mut [u8]) {
    for byte in counter.iter_mut() {
        *byte = byte.wrapping_add(1);
        if *byte != 0 {
            break;
        }
    }
}

// Reads HX names.
fn read_hx_names(names_file: Option<&Path>) -> (HashMap<String, String>, HashMap<String, String>) {
    let mut path_map = HashMap::new();
    let mut name_map = HashMap::new();
    let Some(path) = names_file else {
        return (path_map, name_map);
    };
    let Ok(data) = std::fs::read(path) else {
        return (path_map, name_map);
    };
    let text = String::from_utf8_lossy(&data);
    for line in text.lines() {
        let Some((hash, name)) = line.split_once(':') else {
            continue;
        };
        match hash.len() {
            16 => {
                path_map.insert(hash.to_owned(), name.to_owned());
            }
            64 => {
                name_map.insert(hash.to_owned(), name.to_owned());
            }
            _ => {}
        }
    }
    (path_map, name_map)
}

// Reads index object.
fn read_index_object<R: Read>(reader: &mut R) -> std::io::Result<HxIndexValue> {
    let mut ty = [0u8; 1];
    reader.read_exact(&mut ty)?;
    match ty[0] {
        0x00 | 0x01 => Ok(HxIndexValue::Null),
        0x02 => {
            read_index_string(reader)?;
            Ok(HxIndexValue::String)
        }
        0x03 => Ok(HxIndexValue::Bytes(read_index_bytes(reader)?)),
        0x04 | 0x05 => Ok(HxIndexValue::Integer(BinaryIo::read_i64_be(reader)?)),
        0x81 => {
            let count = BinaryIo::read_i32_be(reader)?;
            if count < 0 {
                return Err(invalid("negative Hx array length"));
            }
            let mut values = Vec::with_capacity(count as usize);
            for _ in 0..count {
                values.push(read_index_object(reader)?);
            }
            Ok(HxIndexValue::Array(values))
        }
        0xc1 => {
            let count = BinaryIo::read_i32_be(reader)?;
            if count < 0 {
                return Err(invalid("negative Hx dictionary length"));
            }
            let mut values = HashMap::with_capacity(count as usize);
            for _ in 0..count {
                let key = read_index_string(reader)?;
                let value = read_index_object(reader)?;
                values.insert(key, value);
            }
            Ok(HxIndexValue::Dictionary)
        }
        _ => Err(invalid("unknown Hx index object type")),
    }
}

// Reads index bytes.
fn read_index_bytes<R: Read>(reader: &mut R) -> std::io::Result<Vec<u8>> {
    let count = BinaryIo::read_i32_be(reader)?;
    if count < 0 {
        return Err(invalid("negative Hx byte array length"));
    }
    let mut out = vec![0u8; count as usize];
    reader.read_exact(&mut out)?;
    Ok(out)
}

// Reads index string.
fn read_index_string<R: Read>(reader: &mut R) -> std::io::Result<String> {
    let count = BinaryIo::read_i32_be(reader)?;
    if count < 0 {
        return Err(invalid("negative Hx string length"));
    }
    let mut chars = Vec::with_capacity(count as usize);
    for _ in 0..count {
        chars.push(BinaryIo::read_u16_le(reader)?);
    }
    String::from_utf16(&chars).map_err(|_| invalid("invalid Hx UTF-16 string"))
}

// Formats bytes as uppercase hexadecimal text.
fn hex_upper(data: &[u8]) -> String {
    let mut out = String::with_capacity(data.len() * 2);
    for byte in data {
        use std::fmt::Write;
        let _ = write!(out, "{byte:02X}");
    }
    out
}

// Creates an invalid-data I/O error with the provided message.
fn invalid(msg: &str) -> std::io::Error {
    std::io::Error::new(std::io::ErrorKind::InvalidData, msg)
}
