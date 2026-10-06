//! MT19937 with the **original 1998 seeding routine** — not the `init_genrand`
//! from the 2002 "mt19937ar" paper, and not Knuth's LCG variant.
//!
//! CatSystem2's `.int` index derives two things from this generator: the
//! Blowfish key of an encrypted archive (seeded with the `__key__.dat` entry's
//! raw size field) and the per-entry name permutation shift (seeded with
//! `table_seed + i`). Both come out of the *first* generated word, so the
//! seeding routine has to match exactly — the two variants produce completely
//! different first words from the same seed.
//!
//! Seeding (matching the reference implementation's `Classic`):
//! ```text
//! for i in 0..624:
//!     mt[i]  = seed & 0xFFFF0000
//!     seed   = 69069 * seed + 1
//!     mt[i] |= (seed & 0xFFFF0000) >> 16
//!     seed   = 69069 * seed + 1
//! ```

const N: usize = 624;
const M: usize = 397;
const MATRIX_A: u32 = 0x9908_B0DF;
const UPPER_MASK: u32 = 0x8000_0000;
const LOWER_MASK: u32 = 0x7FFF_FFFF;

pub struct Mt19937 {
    mt: [u32; N],
    mti: usize,
}

impl Mt19937 {
    pub fn classic(seed: u32) -> Self {
        let mut mt = [0u32; N];
        let mut s = seed;
        for slot in mt.iter_mut() {
            *slot = s & 0xFFFF_0000;
            s = 69069u32.wrapping_mul(s).wrapping_add(1);
            *slot |= (s & 0xFFFF_0000) >> 16;
            s = 69069u32.wrapping_mul(s).wrapping_add(1);
        }
        // `mti == N` forces a regeneration on the first `next_u32`, exactly as
        // the reference seeding leaves it.
        Mt19937 { mt, mti: N }
    }

    pub fn next_u32(&mut self) -> u32 {
        let mag01 = [0u32, MATRIX_A];
        if self.mti >= N {
            let mut kk = 0;
            while kk < N - M {
                let y = (self.mt[kk] & UPPER_MASK) | (self.mt[kk + 1] & LOWER_MASK);
                self.mt[kk] = self.mt[kk + M] ^ (y >> 1) ^ mag01[(y & 1) as usize];
                kk += 1;
            }
            while kk < N - 1 {
                let y = (self.mt[kk] & UPPER_MASK) | (self.mt[kk + 1] & LOWER_MASK);
                self.mt[kk] = self.mt[kk - (N - M)] ^ (y >> 1) ^ mag01[(y & 1) as usize];
                kk += 1;
            }
            let y = (self.mt[N - 1] & UPPER_MASK) | (self.mt[0] & LOWER_MASK);
            self.mt[N - 1] = self.mt[M - 1] ^ (y >> 1) ^ mag01[(y & 1) as usize];
            self.mti = 0;
        }
        let mut y = self.mt[self.mti];
        self.mti += 1;
        y ^= y >> 11;
        y ^= (y << 7) & 0x9D2C_5680;
        y ^= (y << 15) & 0xEFC6_0000;
        y ^= y >> 18;
        y
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The 1998 seeding routine has a fixed fingerprint: with seed 4357 the
    /// first three words are these. The values come from the verified oracle
    /// (the same generator that reproduces the fixture's real file names), not
    /// from hand arithmetic — and they are *not* what the 2002 `init_genrand`
    /// would produce from the same seed, so this test pins the variant.
    #[test]
    fn classic_seeding_matches_the_reference_fingerprint() {
        let mut mt = Mt19937::classic(4357);
        assert_eq!(mt.next_u32(), 2867219139);
        assert_eq!(mt.next_u32(), 1585203162);
        assert_eq!(mt.next_u32(), 3113124129);
    }

    #[test]
    fn seeding_is_deterministic_and_state_advances() {
        let a: Vec<u32> = { let mut m = Mt19937::classic(1); (0..8).map(|_| m.next_u32()).collect() };
        let b: Vec<u32> = { let mut m = Mt19937::classic(1); (0..8).map(|_| m.next_u32()).collect() };
        assert_eq!(a, b);
        // A different seed must not produce the same stream.
        let c: Vec<u32> = { let mut m = Mt19937::classic(2); (0..8).map(|_| m.next_u32()).collect() };
        assert_ne!(a, c);
        // Crossing the regeneration boundary (624 words) stays deterministic.
        let mut m1 = Mt19937::classic(7);
        let mut m2 = Mt19937::classic(7);
        for _ in 0..700 {
            assert_eq!(m1.next_u32(), m2.next_u32());
        }
    }
}
