//! EXE RCDATA BasicCryptoFilter used by Magalumina's embedded scripts.

use crate::crypto::hxv4_compute::{HX_CHACHA_BLOCK_LEN, chacha_transform_words};
use crate::io::{BinaryReader, BinaryWriter};
use pelite::PeFile;
use pelite::resources::{Name, Resources};
use sha3::{Digest, Sha3_384};

pub const EXE_RESOURCE_SALT_SIZE: usize = 0x2000;
pub const BOOTSTRAP_RESOURCE_NAME: &str = "BOOTSTRAP";
pub const STARTUP_RESOURCE_NAME: &str = "STARTUP.TJS";

const EXE_CHACHA_ROUNDS: u32 = 8;
const EXE_CHACHA_DOUBLE_ROUNDS: usize = 4;
const STARTUP_BASE_STORAGE_RESOURCE_TYPE: &str = "TEXT";
const STARTUP_BASE_STORAGE_RESOURCE_ID: u32 = 127;
const STARTUP_BASE_STORAGE_PREFIX: &str = "bres://./";
const STARTUP_BASE_STORAGE_SUFFIX: &str = "/";
const BOOTSTRAP_PARAMS_TAG: &[u8] = b"PARAMS\0";
const BOOTSTRAP_PARAMS_MIN_LEN: usize = 0x16;
const XOPT_ARCHIVE_UNIQUE_KEY_MARKER: &[u8] = &[
    0x2d, 0x00, 0x2d, 0x00, 0x78, 0x00, 0x6f, 0x00, 0x70, 0x00, 0x74, 0x00, 0x2d, 0x00, 0x2d, 0x00,
    0x6e, 0x00, 0x6f, 0x00, 0x00, 0x00, 0x00, 0x00,
];
const OBFUSCATED_CHACHA_CONSTANT: [u8; 16] = [
    0x9a, 0x87, 0x8f, 0x9e, 0x91, 0x9b, 0xdf, 0xcc, 0xcd, 0xd2, 0x9d, 0x86, 0x8b, 0x9a, 0xdf, 0x94,
];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BootstrapParams {
    pub raw_order: [u8; 17],
    pub mode: u8,
    pub flags: u8,
    pub mask: u32,
    pub offset: u32,
    pub prolog_order: [u8; 3],
    pub odd_branch_order: [u8; 6],
    pub even_branch_order: [u8; 8],
    pub hx_random_type: i32,
}

#[derive(Debug, Clone)]
pub struct ExeBasicCryptoFilter {
    stored_state: [u32; 16],
    qword1_low: u32,
    qword1_high: u32,
    rounds: u32,
}

impl ExeBasicCryptoFilter {
    // Handles from path and salt behavior.
    pub fn from_path_and_salt(path: &str, salt: &[u8]) -> Self {
        let material = derive_path_key_material(path, salt);
        let mut stored_state = [0u32; 16];

        for (index, value) in stored_state.iter_mut().take(4).enumerate() {
            *value = BinaryReader::u32_le_at(&OBFUSCATED_CHACHA_CONSTANT, index * 4);
        }
        for (index, value) in stored_state.iter_mut().skip(4).take(8).enumerate() {
            *value = !BinaryReader::u32_le_at(&material, index * 4);
        }

        let qword0_low = BinaryReader::u32_le_at(&material, 0x20);
        let qword0_high = BinaryReader::u32_le_at(&material, 0x24);
        stored_state[12] = u32::MAX;
        stored_state[13] = u32::MAX;
        stored_state[14] = !qword0_low;
        stored_state[15] = !qword0_high;

        ExeBasicCryptoFilter {
            stored_state,
            qword1_low: BinaryReader::u32_le_at(&material, 0x28),
            qword1_high: BinaryReader::u32_le_at(&material, 0x2c),
            rounds: EXE_CHACHA_ROUNDS,
        }
    }

    // Decrypts in place.
    pub fn decrypt_in_place(&self, offset: u64, data: &mut [u8]) {
        let mut done = 0usize;
        while done < data.len() {
            let pos = offset + done as u64;
            let block_index = pos >> 6;
            let block_offset = (pos & 0x3f) as usize;
            let count = (data.len() - done).min(HX_CHACHA_BLOCK_LEN - block_offset);
            let key_stream = self.key_stream_block(block_index);

            for i in 0..count {
                data[done + i] ^= key_stream[block_offset + i];
            }
            done += count;
        }
    }

    // Handles decrypt behavior.
    pub fn decrypt(&self, offset: u64, input: &[u8]) -> Vec<u8> {
        let mut output = input.to_vec();
        self.decrypt_in_place(offset, &mut output);
        output
    }

    // Handles key stream block behavior.
    fn key_stream_block(&self, block_index: u64) -> [u8; HX_CHACHA_BLOCK_LEN] {
        let mut block_state = self.stored_state;
        let counter =
            !(((self.qword1_high as u64) << 32) | ((self.qword1_low ^ block_index as u32) as u64));
        block_state[12] = counter as u32;
        block_state[13] = (counter >> 32) as u32;
        chacha_block_from_stored_state(&block_state, self.rounds)
    }
}

// Decrypts resource.
pub fn decrypt_resource(resource: &[u8], filter_path: &str, salt: &[u8]) -> Vec<u8> {
    ExeBasicCryptoFilter::from_path_and_salt(filter_path, salt).decrypt(0, resource)
}

// Reads the startup filter path token from the EXE TEXT/127 resource.
pub fn startup_filter_path(
    resources: &Resources<'_>,
) -> Result<String, Box<dyn std::error::Error>> {
    let resource = resources.find_resource(&[
        Name::Str(STARTUP_BASE_STORAGE_RESOURCE_TYPE),
        Name::Id(STARTUP_BASE_STORAGE_RESOURCE_ID),
    ])?;
    let base_storage = utf16le_resource_string(resource)?;
    let Some(path) = base_storage
        .strip_prefix(STARTUP_BASE_STORAGE_PREFIX)
        .and_then(|value| value.strip_suffix(STARTUP_BASE_STORAGE_SUFFIX))
    else {
        return Err(format!("unexpected startup base storage resource: {base_storage:?}").into());
    };
    if path.is_empty() || path.contains('/') || path.contains('\\') {
        return Err(format!("invalid startup filter path: {path:?}").into());
    }
    Ok(path.to_owned())
}

// Reads Cxdec bootstrap PARAMS from a PE image.
pub fn bootstrap_params_from_pe(
    data: &[u8],
) -> Result<BootstrapParams, Box<dyn std::error::Error>> {
    let pe = PeFile::from_bytes(data)?;
    for section in pe.section_headers() {
        if !is_bootstrap_params_section(&section.Name) {
            continue;
        }
        let Ok(section_data) =
            pe.derva_slice::<u8>(section.VirtualAddress, section.VirtualSize as usize)
        else {
            continue;
        };
        if let Some(params) = find_bootstrap_params(section_data)? {
            return Ok(params);
        }
    }

    if let Some(params) = find_bootstrap_params(data)? {
        return Ok(params);
    }
    Err("bootstrap PARAMS blob not found".into())
}

// Finds and returns a validated bootstrap key/value item payload.
pub fn bootstrap_item_from_pe(
    data: &[u8],
    name: &str,
    validate: fn(&[u8]) -> bool,
) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
    let tag = format!("{name}\0");
    let tag = tag.as_bytes();
    let mut search_start = 0usize;
    while let Some(relative) = data[search_start..]
        .windows(tag.len())
        .position(|window| window == tag)
    {
        let item_start = search_start + relative;
        let len_offset = item_start + tag.len();
        if len_offset + 2 > data.len() {
            break;
        }
        let payload_len = BinaryReader::u16_le_at(data, len_offset) as usize;
        let payload_start = len_offset + 2;
        let Some(payload_end) = payload_start.checked_add(payload_len) else {
            break;
        };
        if payload_end <= data.len() {
            let payload = &data[payload_start..payload_end];
            if validate(payload) {
                return Ok(payload.to_vec());
            }
        }
        search_start = item_start + 1;
    }
    Err(format!("bootstrap {name} blob not found").into())
}

// Extracts the archiveUniqueKey seed from a decrypted BOOTSTRAP PE image.
pub fn archive_unique_key_from_bootstrap_pe(
    data: &[u8],
) -> Result<[u8; 8], Box<dyn std::error::Error>> {
    if let Some(key) = extract_xopt_archive_unique_key(data) {
        return Ok(key);
    }

    let mut matches = Vec::new();
    let mut pos = 0usize;
    while pos + 0x50 <= data.len() {
        if data[pos] == 0xc7
            && data[pos + 1] == 0x45
            && data[pos + 7] == 0xc7
            && data[pos + 8] == 0x45
            && data[pos + 14..pos + 17] == [0x0f, 0x45, 0xf0]
        {
            let stack_slot_1 = data[pos + 2];
            let stack_slot_2 = data[pos + 9];
            if stack_slot_2 == stack_slot_1.wrapping_add(4)
                && has_archive_unique_kdf_use(&data[pos + 17..pos + 0x50])
            {
                let mut key = [0u8; 8];
                key[..4].copy_from_slice(&data[pos + 3..pos + 7]);
                key[4..].copy_from_slice(&data[pos + 10..pos + 14]);
                matches.push((pos, key));
            }
        }
        pos += 1;
    }

    matches.sort_by_key(|(offset, key)| (*offset, *key));
    matches.dedup_by_key(|(_, key)| *key);
    match matches.as_slice() {
        [(_, key)] => Ok(*key),
        [] => Err("bootstrap archiveUniqueKey default immediate not found".into()),
        _ => Err(format!(
            "bootstrap archiveUniqueKey default immediate is ambiguous: {} candidates",
            matches.len()
        )
        .into()),
    }
}

// Parses a Cxdec bootstrap PARAMS payload.
pub fn parse_bootstrap_params(data: &[u8]) -> Result<BootstrapParams, Box<dyn std::error::Error>> {
    if data.len() < BOOTSTRAP_PARAMS_MIN_LEN {
        return Err(format!("bootstrap PARAMS too short: {}", data.len()).into());
    }

    let mut raw_order = [0u8; 17];
    raw_order.copy_from_slice(&data[..17]);
    let flags = data[0x11];
    let mask = BinaryReader::u16_le_at(data, 0x12) as u32;
    let offset = BinaryReader::u16_le_at(data, 0x14) as u32;

    Ok(BootstrapParams {
        raw_order,
        mode: data[0x10],
        flags,
        mask,
        offset,
        prolog_order: decode_order(&data[14..17], [0, 1, 2])?,
        odd_branch_order: decode_order(&data[8..14], [2, 5, 3, 4, 1, 0])?,
        even_branch_order: decode_order(&data[0..8], [0, 2, 3, 1, 5, 6, 7, 4])?,
        hx_random_type: (flags >> 7) as i32,
    })
}

// Returns whether the payload looks like Cxdec PARAMS.
pub fn is_params_payload(data: &[u8]) -> bool {
    parse_bootstrap_params(data).is_ok()
}

// Returns whether the payload looks like the Cxdec warning string.
pub fn is_warning_payload(data: &[u8]) -> bool {
    !data.is_empty()
        && data.is_ascii()
        && std::str::from_utf8(data)
            .is_ok_and(|value| value.starts_with("Warning!") && value.contains("author"))
}

// Returns whether the payload looks like a UTF-16LE unique key string.
pub fn is_unique_payload(data: &[u8]) -> bool {
    data.len() >= 2
        && data.len().is_multiple_of(2)
        && String::from_utf16(
            &data
                .chunks_exact(2)
                .map(BinaryReader::u16_le)
                .collect::<Vec<_>>(),
        )
        .is_ok_and(|value| value.starts_with('{') && value.ends_with('}'))
}

// Derives path key material.
pub fn derive_path_key_material(path: &str, salt: &[u8]) -> [u8; 48] {
    let mut hasher = Sha3_384::new();
    for word in path.encode_utf16() {
        hasher.update(word.to_le_bytes());
    }
    hasher.update(salt);
    hasher.finalize().into()
}

// Handles ChaCha block from stored state behavior.
fn chacha_block_from_stored_state(
    stored_state: &[u32; 16],
    rounds: u32,
) -> [u8; HX_CHACHA_BLOCK_LEN] {
    let mut initial = [0u32; 16];
    for (dst, src) in initial.iter_mut().zip(stored_state.iter()) {
        *dst = !*src;
    }

    let double_rounds = (((rounds - 1) >> 1) + 1) as usize;
    debug_assert_eq!(double_rounds, EXE_CHACHA_DOUBLE_ROUNDS);
    let transformed = chacha_transform_words(initial, double_rounds);

    let mut out = [0u8; HX_CHACHA_BLOCK_LEN];
    for i in 0..16 {
        let value = transformed[i].wrapping_add(initial[i]);
        BinaryWriter::u32_le(value, &mut out[i * 4..i * 4 + 4]);
    }
    out
}

// Finds the PARAMS key/value item inside a bootstrap data range.
fn find_bootstrap_params(
    data: &[u8],
) -> Result<Option<BootstrapParams>, Box<dyn std::error::Error>> {
    let mut search_start = 0usize;
    while let Some(relative) = data[search_start..]
        .windows(BOOTSTRAP_PARAMS_TAG.len())
        .position(|window| window == BOOTSTRAP_PARAMS_TAG)
    {
        let item_start = search_start + relative;
        let len_offset = item_start + BOOTSTRAP_PARAMS_TAG.len();
        if len_offset + 2 > data.len() {
            break;
        }
        let payload_len = BinaryReader::u16_le_at(data, len_offset) as usize;
        let payload_start = len_offset + 2;
        let Some(payload_end) = payload_start.checked_add(payload_len) else {
            break;
        };
        if payload_end <= data.len() {
            let payload = &data[payload_start..payload_end];
            if let Ok(params) = parse_bootstrap_params(payload) {
                return Ok(Some(params));
            }
        }
        search_start = item_start + 1;
    }
    Ok(None)
}

// Extracts the --xopt archiveUniqueKey fallback stored in .rdata.
fn extract_xopt_archive_unique_key(data: &[u8]) -> Option<[u8; 8]> {
    let marker_offset = data
        .windows(XOPT_ARCHIVE_UNIQUE_KEY_MARKER.len())
        .position(|bytes| bytes == XOPT_ARCHIVE_UNIQUE_KEY_MARKER)?;
    let key_offset = marker_offset + XOPT_ARCHIVE_UNIQUE_KEY_MARKER.len();
    if key_offset + 8 > data.len() {
        return None;
    }

    let mut key = [0u8; 8];
    key.copy_from_slice(&data[key_offset..key_offset + 8]);
    if key.iter().all(|&byte| byte == 0) {
        return None;
    }
    Some(key)
}

// Returns whether nearby code uses the selected archiveUniqueKey pointer for deriveKeyBlock.
fn has_archive_unique_kdf_use(window: &[u8]) -> bool {
    let Some(push_seed) = window.windows(2).position(|bytes| bytes == [0xff, 0x36]) else {
        return false;
    };
    let tail = &window[push_seed + 2..];
    tail.windows(2).any(|bytes| bytes == [0x6a, 0x08])
        && tail.contains(&0x56)
        && tail.contains(&0xe8)
}

// Decodes a packed Cxdec permutation into the Rust opcode order.
fn decode_order<const N: usize>(
    raw: &[u8],
    operation_by_raw_index: [u8; N],
) -> Result<[u8; N], Box<dyn std::error::Error>> {
    if raw.len() != N {
        return Err(format!("invalid order length: {}", raw.len()).into());
    }

    let mut out = [u8::MAX; N];
    for (raw_index, &random_value) in raw.iter().enumerate() {
        let order_index = random_value as usize;
        if order_index >= N {
            return Err(format!("order value out of range: {random_value}").into());
        }
        if out[order_index] != u8::MAX {
            return Err(format!("duplicate order value: {random_value}").into());
        }
        out[order_index] = operation_by_raw_index[raw_index];
    }
    Ok(out)
}

// Returns whether a section may contain bootstrap static parameters.
fn is_bootstrap_params_section(name: &[u8; 8]) -> bool {
    matches!(trim_section_name(name), ".rdata" | ".data")
}

// Trims a PE section name.
fn trim_section_name(name: &[u8; 8]) -> &str {
    let len = name.iter().position(|&ch| ch == 0).unwrap_or(name.len());
    std::str::from_utf8(&name[..len]).unwrap_or_default()
}

// Decodes a PE resource string stored as raw UTF-16LE bytes.
fn utf16le_resource_string(data: &[u8]) -> Result<String, Box<dyn std::error::Error>> {
    if !data.len().is_multiple_of(2) {
        return Err("UTF-16LE resource has odd byte length".into());
    }
    let words = data.chunks_exact(2).map(BinaryReader::u16_le);
    Ok(char::decode_utf16(words)
        .collect::<Result<String, _>>()?
        .trim_end_matches('\0')
        .to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_utf16le_resource_string() {
        let data = b"b\0r\0e\0s\0:\0/\0/\0.\0/\0t\0o\0k\0e\0n\0/\0";
        assert_eq!(utf16le_resource_string(data).unwrap(), "bres://./token/");
    }

    #[test]
    fn rejects_odd_utf16le_resource_string() {
        assert!(utf16le_resource_string(b"a\0b").is_err());
    }

    #[test]
    fn parses_bootstrap_params() {
        let data = [
            0x01, 0x05, 0x06, 0x04, 0x07, 0x02, 0x00, 0x03, 0x03, 0x00, 0x04, 0x01, 0x05, 0x02,
            0x00, 0x01, 0x02, 0x00, 0x00, 0x03, 0x3f, 0x02,
        ];
        let params = parse_bootstrap_params(&data).unwrap();
        assert_eq!(params.mask, 768);
        assert_eq!(params.offset, 575);
        assert_eq!(params.hx_random_type, 0);
        assert_eq!(params.prolog_order, [0, 1, 2]);
        assert_eq!(params.odd_branch_order, [5, 4, 0, 2, 3, 1]);
        assert_eq!(params.even_branch_order, [7, 0, 6, 4, 1, 2, 3, 5]);
    }

    #[test]
    fn finds_bootstrap_params_blob() {
        let mut data = b"prefixPARAMS\0\x16\0".to_vec();
        data.extend_from_slice(&[
            0x01, 0x05, 0x06, 0x04, 0x07, 0x02, 0x00, 0x03, 0x03, 0x00, 0x04, 0x01, 0x05, 0x02,
            0x00, 0x01, 0x02, 0x80, 0x00, 0x03, 0x3f, 0x02,
        ]);
        let params = find_bootstrap_params(&data).unwrap().unwrap();
        assert_eq!(params.hx_random_type, 1);
    }

    #[test]
    fn extracts_xopt_archive_unique_key_fallback() {
        let mut data = b"prefix".to_vec();
        data.extend_from_slice(XOPT_ARCHIVE_UNIQUE_KEY_MARKER);
        data.extend_from_slice(&[0xbf, 0x22, 0x36, 0x8a, 0x48, 0x21, 0x02, 0x06]);
        data.extend_from_slice(b"suffix");

        assert_eq!(
            extract_xopt_archive_unique_key(&data).unwrap(),
            [0xbf, 0x22, 0x36, 0x8a, 0x48, 0x21, 0x02, 0x06]
        );
    }
}
