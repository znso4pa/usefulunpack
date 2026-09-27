//! Cxdec bootstrap key derivation primitives.

use crate::crypto::hxv4_compute::chacha_transform_words;
use crate::io::{BinaryReader, BinaryWriter};
use argon2::{Algorithm, Argon2, Params, Version};
use blake2::Blake2sVar;
use blake2::digest::{Update, VariableOutput};
use keccak::State1600;

pub(super) const KEY_BLOCK_LEN: usize = 32;
pub(super) const TABLE_KEY_BYTES: usize = 32;
pub(super) const TABLE_NONCE_BYTES: usize = 16;

const VERIFIED_ARGON2_LEN: usize = 64;
const VERIFIED_SECRET_LEN: usize = 32;
const CONTROL_BYTES: usize = 0x1000;
const DRIP_TABLE_BYTES: usize = 0x2000;
const TABLE_STATE_KEY_BYTES: usize = 64;
const ARGON2_MEMORY_KIB: u32 = 8;
const ARGON2_PASSES: u32 = 3;
const ARGON2_LANES: u32 = 1;

#[derive(Debug, Clone)]
pub struct BootstrapTableKeys {
    pub default_key1: [u8; TABLE_KEY_BYTES],
    pub default_key2: [u8; TABLE_NONCE_BYTES],
    pub alternate_key1: [u8; TABLE_KEY_BYTES],
    pub alternate_key2: [u8; TABLE_NONCE_BYTES],
    pub flags: u64,
}

#[derive(Debug, Clone)]
pub(super) struct VerifiedMaterial {
    pub secret: [u8; VERIFIED_SECRET_LEN],
    pub drip_table: [u8; DRIP_TABLE_BYTES],
}

// Derives the DLL verified material block.
pub(super) fn derive_verified_material(
    password: &[u8],
    params: &[u8],
) -> Result<VerifiedMaterial, Box<dyn std::error::Error>> {
    let mut salt = [0u8; 16];
    CxSponge::new(0x90, 0x06).absorb(params).squeeze(&mut salt);

    let argon_params = Params::new(
        ARGON2_MEMORY_KIB,
        ARGON2_PASSES,
        ARGON2_LANES,
        Some(VERIFIED_ARGON2_LEN),
    )
    .map_err(|err| format!("invalid Argon2 params: {err:?}"))?;
    let argon = Argon2::new(Algorithm::Argon2i, Version::V0x13, argon_params);
    let mut material = [0u8; VERIFIED_ARGON2_LEN];
    argon
        .hash_password_into(password, &salt, &mut material)
        .map_err(|err| format!("Argon2 key derivation failed: {err:?}"))?;

    let mut drip_table = [0u8; DRIP_TABLE_BYTES];
    CxSponge::new(0x88, 0x1f)
        .absorb(&material)
        .squeeze(&mut drip_table);

    let mut secret = [0u8; VERIFIED_SECRET_LEN];
    secret.copy_from_slice(&material[..VERIFIED_SECRET_LEN]);
    Ok(VerifiedMaterial { secret, drip_table })
}

// Converts the DLL drip table bytes to Rust control block words.
pub(super) fn control_block_from_drip_bytes(bytes: &[u8; DRIP_TABLE_BYTES], flags: u8) -> Vec<u32> {
    let mut active = bytes[..CONTROL_BYTES].to_vec();
    if flags & 1 != 0 {
        let second = &bytes[CONTROL_BYTES..DRIP_TABLE_BYTES];
        // DLL sub_1000F620(mode=2) overlays the second 0x1000-byte table by
        // XORing matching byte positions; it does not rotate bytes per word.
        for (target, source) in active.iter_mut().zip(second) {
            *target ^= *source;
        }
    }

    active
        .chunks_exact(4)
        .map(|chunk| !BinaryReader::u32_le(chunk))
        .collect()
}

// Derives the archiveUniqueKey block used by HX filters and index keys.
pub(super) fn derive_unique_block(
    unique_utf16le: &[u8],
    archive_unique_key: &[u8; 8],
) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
    let mut block = derive_key_block(unique_utf16le, KEY_BLOCK_LEN, 2)?;
    let seed = BinaryReader::u32_le(&archive_unique_key[..4]);
    let modifier = derive_key_block(archive_unique_key, KEY_BLOCK_LEN, seed)?;
    xor_in_place(&mut block, &modifier);
    Ok(block)
}

// Derives the Hxv4 target chunk table AEAD key slots.
pub(super) fn derive_table_keys(
    bootstrap_key: &[u8],
    params_key: &[u8],
    unique_block: &[u8],
) -> Result<BootstrapTableKeys, Box<dyn std::error::Error>> {
    if bootstrap_key.len() != TABLE_KEY_BYTES
        || params_key.len() != TABLE_KEY_BYTES
        || unique_block.len() != TABLE_KEY_BYTES
    {
        return Err("invalid bootstrap table key material length".into());
    }

    let mut state_key = [0u8; TABLE_STATE_KEY_BYTES];
    state_key[..TABLE_KEY_BYTES].copy_from_slice(bootstrap_key);
    state_key[TABLE_KEY_BYTES..].copy_from_slice(params_key);

    let flags_block = derive_key_block(&state_key, 8, u32::MAX)?;
    let flags = BinaryReader::u64_le(&flags_block);

    let default_key1 = hchacha20(bootstrap_key, &unique_block[..TABLE_NONCE_BYTES]);
    let mut default_key2 = [0u8; TABLE_NONCE_BYTES];
    default_key2.copy_from_slice(&unique_block[TABLE_NONCE_BYTES..]);

    let alternate_key1 = hchacha20(bootstrap_key, &params_key[..TABLE_NONCE_BYTES]);
    let mut alternate_key2 = [0u8; TABLE_NONCE_BYTES];
    alternate_key2.copy_from_slice(&params_key[TABLE_NONCE_BYTES..]);

    Ok(BootstrapTableKeys {
        default_key1,
        default_key2,
        alternate_key1,
        alternate_key2,
        flags,
    })
}

// Derives the DLL key block helper output.
pub(super) fn derive_key_block(
    input: &[u8],
    size: usize,
    seed: u32,
) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
    if size == 0 || !size.is_multiple_of(4) {
        return Err(format!("invalid key block size: {size}").into());
    }
    let mut words = vec![0u32; size / 4];
    let mut state = 0x0100_0193u32.wrapping_mul(seed ^ 0x811c_9dc5);
    for (index, &byte) in input.iter().enumerate() {
        let mut value = (byte as u32) ^ state;
        value ^= value >> 17;
        value = value.wrapping_mul(0xed5a_d4bb);
        value ^= value >> 11;
        value = value.wrapping_mul(0xac4c_1b51);
        value ^= value >> 15;
        value = value.wrapping_mul(0x3184_8bab);
        state = value ^ (value >> 14);
        let word_index = index % words.len();
        words[word_index] ^= state;
    }

    let mut prehash = Vec::with_capacity(size);
    for word in words {
        prehash.extend_from_slice(&word.to_le_bytes());
    }

    let digest_len = if size <= 0x20 { size } else { 0x20 };
    let mut hasher = Blake2sVar::new(digest_len)?;
    hasher.update(input);
    hasher.update(&prehash);
    let mut out = vec![0u8; digest_len];
    hasher.finalize_variable(&mut out)?;
    out.resize(size, 0);
    Ok(out)
}

#[derive(Debug, Clone)]
struct CxSponge {
    rate: usize,
    position: usize,
    state: State1600,
    domain: u8,
}

impl CxSponge {
    // Creates a new DLL-compatible sponge state.
    fn new(rate: usize, domain: u8) -> Self {
        Self {
            rate,
            position: 0,
            state: [0; 25],
            domain,
        }
    }

    // Absorbs bytes into the sponge.
    fn absorb(mut self, input: &[u8]) -> Self {
        let mut offset = 0usize;
        while offset < input.len() {
            let count = (self.rate - self.position).min(input.len() - offset);
            xor_state_bytes(
                &mut self.state,
                self.position,
                &input[offset..offset + count],
            );
            self.position += count;
            offset += count;
            if self.position == self.rate {
                keccak_f1600(&mut self.state);
                self.position = 0;
            }
        }
        self
    }

    // Squeezes bytes from the sponge.
    fn squeeze(mut self, output: &mut [u8]) {
        xor_state_bytes(&mut self.state, self.position, &[self.domain]);
        self.state[(self.rate - 1) / 8] ^= 0x80u64 << (8 * ((self.rate - 1) % 8));
        keccak_f1600(&mut self.state);

        let mut offset = 0usize;
        while offset < output.len() {
            let count = self.rate.min(output.len() - offset);
            read_state_bytes(&self.state, &mut output[offset..offset + count]);
            offset += count;
            if offset < output.len() {
                keccak_f1600(&mut self.state);
            }
        }
    }
}

// Encodes a string as UTF-16LE bytes.
pub(super) fn encode_utf16le(value: &str) -> Vec<u8> {
    value
        .encode_utf16()
        .flat_map(|word| word.to_le_bytes())
        .collect()
}

// XORs bytes in place.
pub(super) fn xor_in_place(dst: &mut [u8], rhs: &[u8]) {
    for (left, right) in dst.iter_mut().zip(rhs.iter()) {
        *left ^= *right;
    }
}

// XORs bytes into a Keccak state at a byte offset.
fn xor_state_bytes(state: &mut State1600, offset: usize, input: &[u8]) {
    for (index, &byte) in input.iter().enumerate() {
        let pos = offset + index;
        state[pos / 8] ^= (byte as u64) << (8 * (pos % 8));
    }
}

// Reads bytes from the start of a Keccak state.
fn read_state_bytes(state: &State1600, output: &mut [u8]) {
    for (index, byte) in output.iter_mut().enumerate() {
        *byte = (state[index / 8] >> (8 * (index % 8))) as u8;
    }
}

// Runs Keccak-f[1600].
fn keccak_f1600(state: &mut State1600) {
    keccak::Keccak::new().with_f1600(|f1600| f1600(state));
}

// Derives an HChaCha20 subkey.
fn hchacha20(key: &[u8], nonce16: &[u8]) -> [u8; 32] {
    let mut state = [0u32; 16];
    state[0] = 0x6170_7865;
    state[1] = 0x3320_646e;
    state[2] = 0x7962_2d32;
    state[3] = 0x6b20_6574;
    for i in 0..8 {
        state[4 + i] = BinaryReader::u32_le(&key[i * 4..i * 4 + 4]);
    }
    for i in 0..4 {
        state[12 + i] = BinaryReader::u32_le(&nonce16[i * 4..i * 4 + 4]);
    }
    let out = chacha_transform_words(state, 10);
    let words = [
        out[0], out[1], out[2], out[3], out[12], out[13], out[14], out[15],
    ];
    let mut key = [0u8; 32];
    for (i, word) in words.into_iter().enumerate() {
        BinaryWriter::u32_le(word, &mut key[i * 4..i * 4 + 4]);
    }
    key
}

#[cfg(test)]
mod tests {
    use super::*;

    // Verifies the PARAMS flag bit 0 overlay used by newer CX DLLs.
    #[test]
    fn control_block_flag_xors_second_block_without_byte_rotation() {
        let mut bytes = [0u8; DRIP_TABLE_BYTES];
        bytes[0..4].copy_from_slice(&[0x10, 0x20, 0x30, 0x40]);
        bytes[CONTROL_BYTES..CONTROL_BYTES + 4].copy_from_slice(&[0x01, 0x02, 0x03, 0x04]);

        let plain = control_block_from_drip_bytes(&bytes, 0);
        assert_eq!(plain[0], !0x4030_2010);

        let mixed = control_block_from_drip_bytes(&bytes, 1);
        assert_eq!(mixed[0], !0x4433_2211);
    }
}
