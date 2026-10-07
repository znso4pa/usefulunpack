//! Blowfish, with the raw-`u32` block view the format's writers use.
//!
//! The reference decoder hands a byte buffer to OpenSSL's `BF_decrypt` by
//! casting it to `u32*`, so a block is two **little-endian** `u32` halves on
//! every platform this app runs on. That matters: the textbook test vectors are
//! published in big-endian halves, and feeding them to a little-endian view
//! yields a different ciphertext. The tests below therefore compare against
//! expectations converted for this view (byte-reversed halves), computed with an
//! independent implementation rather than remembered from a table.

use crate::bf_tables::{P_INIT, S_INIT};

pub struct Blowfish {
    p: [u32; 18],
    s: [[u32; 256]; 4],
}

fn f(s: &[[u32; 256]; 4], x: u32) -> u32 {
    let a = (x >> 24) as usize;
    let b = ((x >> 16) & 0xFF) as usize;
    let c = ((x >> 8) & 0xFF) as usize;
    let d = (x & 0xFF) as usize;
    (s[0][a].wrapping_add(s[1][b]) ^ s[2][c]).wrapping_add(s[3][d])
}

/// One encryption round-pair against the tables *as they are right now* — used
/// by the key schedule, which feeds its own partially-updated state back in.
fn enc(p: &[u32; 18], s: &[[u32; 256]; 4], mut l: u32, mut r: u32) -> (u32, u32) {
    for i in 0..16 {
        l ^= p[i];
        r ^= f(s, l);
        std::mem::swap(&mut l, &mut r);
    }
    std::mem::swap(&mut l, &mut r);
    r ^= p[16];
    l ^= p[17];
    (l, r)
}

impl Blowfish {
    /// `key` must not be empty (the format always passes 9–16 bytes).
    pub fn new(key: &[u8]) -> Self {
        let mut p = P_INIT;
        let mut s = S_INIT;
        if !key.is_empty() {
            let mut j = 0usize;
            for w in p.iter_mut() {
                let mut k = 0u32;
                for _ in 0..4 {
                    k = (k << 8) | key[j % key.len()] as u32;
                    j += 1;
                }
                *w ^= k;
            }
        }
        let (mut l, mut r) = (0u32, 0u32);
        let mut i = 0;
        while i < 18 {
            let (nl, nr) = enc(&p, &s, l, r);
            l = nl;
            r = nr;
            p[i] = l;
            p[i + 1] = r;
            i += 2;
        }
        for box_i in 0..4 {
            let mut i = 0;
            while i < 256 {
                let (nl, nr) = enc(&p, &s, l, r);
                l = nl;
                r = nr;
                s[box_i][i] = l;
                s[box_i][i + 1] = r;
                i += 2;
            }
        }
        Blowfish { p, s }
    }

    /// Decrypts every complete 8-byte block **in place**; a trailing partial
    /// block is left untouched (the reference does the same, and the format
    /// never produces one).
    pub fn decrypt_in_place(&self, data: &mut [u8]) {
        let blocks = data.len() / 8;
        for i in 0..blocks {
            let off = i * 8;
            let mut l = u32::from_le_bytes([data[off], data[off + 1], data[off + 2], data[off + 3]]);
            let mut r = u32::from_le_bytes([data[off + 4], data[off + 5], data[off + 6], data[off + 7]]);
            for round in 0..16 {
                l ^= self.p[17 - round];
                r ^= f(&self.s, l);
                std::mem::swap(&mut l, &mut r);
            }
            std::mem::swap(&mut l, &mut r);
            r ^= self.p[1];
            l ^= self.p[0];
            data[off..off + 4].copy_from_slice(&l.to_le_bytes());
            data[off + 4..off + 8].copy_from_slice(&r.to_le_bytes());
        }
    }

    /// Encrypts every complete 8-byte block in place (the writer's direction;
    /// the tail of a partial block is left untouched, mirroring `decrypt`).
    pub fn encrypt_in_place(&self, data: &mut [u8]) {
        let blocks = data.len() / 8;
        for i in 0..blocks {
            let off = i * 8;
            let mut l = u32::from_le_bytes([data[off], data[off + 1], data[off + 2], data[off + 3]]);
            let mut r = u32::from_le_bytes([data[off + 4], data[off + 5], data[off + 6], data[off + 7]]);
            // Canonical Blowfish: the same shape as `enc()` above, which is what
            // the key schedule already relies on.
            for i in 0..16 {
                l ^= self.p[i];
                r ^= f(&self.s, l);
                std::mem::swap(&mut l, &mut r);
            }
            std::mem::swap(&mut l, &mut r);
            r ^= self.p[16];
            l ^= self.p[17];
            data[off..off + 4].copy_from_slice(&l.to_le_bytes());
            data[off + 4..off + 8].copy_from_slice(&r.to_le_bytes());
        }
    }

    pub fn encrypt(&self, data: &[u8]) -> Vec<u8> {
        let mut out = data.to_vec();
        self.encrypt_in_place(&mut out);
        out
    }

    pub fn decrypt(&self, data: &[u8]) -> Vec<u8> {
        let mut out = data.to_vec();
        self.decrypt_in_place(&mut out);
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(s: &str) -> Vec<u8> {
        (0..s.len() / 2).map(|i| u8::from_str_radix(&s[2 * i..2 * i + 2], 16).unwrap()).collect()
    }

    /// The pi-derived tables must start with the published Blowfish constants —
    /// a cheap guard against a bad table generation, independent of the cipher.
    #[test]
    fn tables_start_with_the_published_constants() {
        assert_eq!(P_INIT[0], 0x243F_6A88);
        assert_eq!(P_INIT[1], 0x85A3_08D3);
        assert_eq!(P_INIT[2], 0x1319_8A2E);
        assert_eq!(P_INIT[17], 0x8979_FB1B);
        assert_eq!(S_INIT[0][0], 0xD131_0BA6);
        assert_eq!(S_INIT[3][255], 0x3AC3_72E6);
    }

    /// Standard Blowfish test vectors, converted to this crate's little-endian
    /// `u32` block view (each 4-byte half byte-reversed). The expectations were
    /// produced by an independent implementation (pycryptodome) under the same
    /// conversion, so a wrong round, key schedule or half order fails here.
    #[test]
    fn matches_the_standard_vectors_in_the_le_block_view() {
        let cases: [(&str, &str, &str); 6] = [
            ("0000000000000000", "0000000000000000", "4597F94E78DD9861"),
            ("ffffffffffffffff", "ffffffffffffffff", "D56F86518ACB5EB8"),
            ("0123456789abcdef", "1111111111111111", "80C3F96196B08122"),
            ("fedcba9876543210", "0123456789abcdef", "0D474ADE6A100014"),
            ("7ca110454a1a6e57", "01a1d6d039776742", "BBCCF761A6C4D44A"),
            ("0131d9619dc1376e", "5cd54ca83def57da", "7FC60FDAD1DE1AC3"),
        ];
        for (key, plain, want) in cases {
            // Encryption = decryption with the P order reversed; decrypt the
            // expected ciphertext back to the plaintext to test `decrypt` only.
            let bf = Blowfish::new(&hex(key));
            let got = bf.decrypt(&hex(want));
            assert_eq!(got, hex(plain), "key={key} want={want}");
        }
    }

    /// The encryption direction must be the exact inverse of `decrypt` — the
    /// writer depends on it, and a wrong round order here would produce
    /// archives the engine cannot read.
    #[test]
    fn encrypt_is_the_inverse_of_decrypt() {
        let bf = Blowfish::new(&[0x42, 0x42, 0x42, 0x42]);
        let plain: Vec<u8> = (0..40u8).map(|i| i.wrapping_mul(13).wrapping_add(5)).collect();
        let round = bf.decrypt(&bf.encrypt(&plain));
        assert_eq!(round, plain);
        // The standard vectors, in the encryption direction.
        for (key, plain_hex, cipher_hex) in [
            ("0000000000000000", "0000000000000000", "4597F94E78DD9861"),
            ("fedcba9876543210", "0123456789abcdef", "0D474ADE6A100014"),
        ] {
            let bf = Blowfish::new(&hex(key));
            assert_eq!(bf.encrypt(&hex(plain_hex)), hex(cipher_hex), "key={key}");
        }
    }

    #[test]
    fn partial_trailing_block_is_left_alone() {
        let bf = Blowfish::new(&hex("0123456789abcdef"));
        let mut data = hex("1111111111111111aabbcc");
        bf.decrypt_in_place(&mut data);
        assert_eq!(&data[8..], &[0xaa, 0xbb, 0xcc]);
    }

    /// Keys of odd lengths must still be a total function: the schedule XORs
    /// the key cyclically, so a 1-byte key and a key longer than the P array
    /// are both legal inputs (the format passes 4–16 bytes, but a truncated or
    /// over-long resource must not be a crash).
    #[test]
    fn odd_key_lengths_are_total() {
        for key in [vec![0u8], vec![0xFFu8], (0..72u8).collect::<Vec<u8>>(), (0..200u8).collect()] {
            let bf = Blowfish::new(&key);
            let out = bf.decrypt(&[0x11u8; 16]);
            assert_eq!(out.len(), 16);
        }
        // Same key, same result (the schedule is deterministic).
        let a = Blowfish::new(&[7, 7, 7]).decrypt(&[1u8; 8]);
        let b = Blowfish::new(&[7, 7, 7]).decrypt(&[1u8; 8]);
        assert_eq!(a, b);
    }

    #[test]
    fn empty_key_does_not_panic() {
        let bf = Blowfish::new(&[]);
        let out = bf.decrypt(&[0u8; 8]);
        assert_eq!(out.len(), 8);
    }
}
