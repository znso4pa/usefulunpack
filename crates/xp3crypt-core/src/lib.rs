//! Keyless Kirikiri XP3 content ciphers — the "simple" family GARbro carries in
//! `ArcFormats/KiriKiri/CryptAlgorithms.cs`.
//!
//! Six schemes, each a different shape of "transform keyed by the entry's own
//! ADLR", so that between them they cover the structural variety GARbro's
//! catalogue has to offer without any of them needing outside information:
//!
//! | scheme                  | transform                                        |
//! |-------------------------|--------------------------------------------------|
//! | [`Scheme::HashCrypt`]     | XOR with one key byte, `(byte)entry.Hash`        |
//! | [`Scheme::FateCrypt`]     | XOR `0x36` + 2 absolute-offset fixups            |
//! | [`Scheme::AppliqueCrypt`] | 5-byte verbatim prefix, then XOR `Hash >> 12`    |
//! | [`Scheme::FlyingShineCrypt`] | XOR a key byte, then ROTATE right by a count derived from the same hash |
//! | [`Scheme::AlteredPinkCrypt`] | XOR a fixed 256-byte table indexed by `offset & 0xFF` |
//! | [`Scheme::DameganeCrypt`] | XOR `entry.Hash` at odd offsets, the offset itself at even ones |
//!
//! [`Scheme::FateCrypt`] is the only one whose stored ADLR is computed over the
//! CIPHERTEXT (`HashAfterCrypt`); the other five checksum the plaintext. And
//! [`Scheme::FlyingShineCrypt`] is the only one that is **not** an involution —
//! its `encrypt` genuinely differs from its `decrypt` — which is why
//! [`Scheme::encrypt`] is a real inverse rather than an alias.
//!
//! What makes these "keyless" (and worth a separate crate from cxdec) is that
//! they need **no sidecar**: the key material is the entry's own ADLR, which the
//! reader already has, and the choice of scheme is decided from the CONTENT.
//! cxdec cannot do this — its key table only exists in the game's
//! `xp3filter.tjs` / `.tpm`, and without it there is nothing to try.
//!
//! ## How a scheme is identified
//!
//! GARbro resolves these from a game-name database. This crate instead scores
//! the archive's own first entries, which needs no database and cannot go stale.
//! An entry counts as a **hit** for a candidate scheme when **either** signal
//! fires:
//!
//! ```text
//! (1) looks_decrypted(decrypt(raw)) && !looks_decrypted(raw)      // signature
//! (2) adler32(decrypt(raw)) == stored_adlr && adler32(raw) != stored_adlr
//! ```
//!
//! Signal (1) is the cheap one: decrypting produced a recognizable file
//! signature that the raw bytes did NOT already have. Both halves matter:
//!
//! * the first half rejects a plain archive (decrypting plaintext with a wrong
//!   key yields noise, which matches no signature);
//! * the second half is what makes `AppliqueCrypt` detectable at all — it leaves
//!   the first 5 bytes alone, so on a plain archive its "decryption" reproduces
//!   the very signature the raw bytes already had. Requiring the signature to be
//!   *new* cancels that out. This is also why `archive_common::looks_decrypted`
//!   uses full-length signatures (`TLG5.0\0`, the 8-byte PNG signature) rather
//!   than the usual 2–4 byte abbreviations.
//!
//! Signal (1) only works on members that *have* a signature, which is the flaw
//! real archives expose: a small patch archive can hold nothing but script and
//! config text, which decrypts to perfectly ordinary text. No signature list can
//! name that. Signal (2) covers exactly this gap — it is content-agnostic,
//! because for these schemes the XP3 index stores a checksum of the plaintext,
//! so the decryption can simply be *verified* against it. Both halves
//! of (2) are load-bearing for the same reason as in (1): a plain archive has
//! `adler32(raw) == stored_adlr` and so is rejected, and a 2⁻³² collision is the
//! only way a wrong scheme can pass.
//!
//! (2) needs the WHOLE entry, so it is evaluated only for entries at or below
//! `PROBE_ADLR_MAX`. It also cannot see an archive whose stored ADLR covers
//! the *ciphertext*: there `adler32(raw) == stored_adlr`, so the second half
//! fails by construction. **No content-only test can recover that case** — a
//! plain archive and a ciphertext-ADLR archive produce byte-identical ADLR
//! relations, so in that direction the stored checksum carries no information at
//! all. Real archives using the convention are media packs that signal (1)
//! handles anyway, *but* [`Scheme::hash_after_crypt`] makes the packer write it
//! for `FateCrypt` regardless of what the folder holds — so a text-only
//! FateCrypt pack this app produced comes back as "cannot say". Extraction is
//! unaffected (the scheme is passed in explicitly); only auto-detection is
//! blind. Recorded in `TODO.md`.
//!
//! ## Why the protected flag is not trusted
//!
//! GARbro gates the cipher on the per-entry flag (`ArcXP3.cs`:
//! `entry.IsEncrypted = 0 != header.ReadUInt32()`, then `if (m_entry.IsEncrypted)
//! Decrypt(…)`), and that reads as the natural design — the flag is the game's
//! own statement about which members the filter runs over. Real archives,
//! however, disprove it in **both** directions:
//!
//! * one packer writes the cipher over an entire archive while leaving the flag
//!   **clear** on every entry, so a flag gate yields nothing but ciphertext;
//! * another mixes a genuinely-plain planted decoy (flag clear, its body a
//!   literal ASCII anti-piracy notice) among encrypted entries that all carry a
//!   non-zero flag. Here the flag happens to be right, which is exactly why it
//!   looks trustworthy.
//!
//! Kirikiri registers an extraction filter (`xp3filter.tjs`) for the whole
//! archive and runs it over every member, so the honest question is the
//! archive-level one — "did some scheme explain this content?" — which is what
//! [`probe_scheme`] answers from the bytes themselves. Once a scheme is
//! identified, the entire archive is decrypted; the per-entry flag is consulted
//! for nothing. The cost is that a planted decoy comes out scrambled, which is
//! the cheaper error: it is an anti-piracy notice, not game data.
//!
//! ## Provenance and independent confirmation
//!
//! The three original `decrypt` bodies were transcribed from GARbro's
//! `ArcFormats/KiriKiri/CryptAlgorithms.cs`, then cross-checked byte-for-byte
//! against two further independent implementations:
//!
//! | here            | GARbro           | arc_unpacker plugin | yuzu_xp3          |
//! |-----------------|------------------|---------------------|-------------------|
//! | `HashCrypt`     | `HashCrypt`      | `xor`               | `SimpleKind::Xor` |
//! | `FateCrypt`     | `FateCrypt`      | `fsn`               | `SimpleKind::Fsn` |
//! | `AppliqueCrypt` | `AppliqueCrypt`  | `rebirth`           | `SimpleKind::Rebirth` |
//!
//! (`arc_unpacker` is `vn-tools/arc_unpacker`
//! `src/dec/kirikiri/xp3_archive_decoder_plugins.cc`; `yuzu_xp3` is a Rust port
//! of those very plugins. Both key the cipher on the entry's `adlr` chunk value
//! — `Xp3ArchiveDecoder::read_file_impl` calls `decrypt_func(data,
//! entry->adlr_chunk->key)` — matching GARbro's `entry.Hash`.)
//!
//! Note the arc_unpacker plugin ids are often how a scheme is catalogued
//! upstream, so they are handy when hunting for real samples. `fsn` is
//! additionally accepted as an input alias for [`Scheme::FateCrypt`] — see
//! [`Scheme::alias`].
//!
//! The three later additions — [`Scheme::FlyingShineCrypt`],
//! [`Scheme::AlteredPinkCrypt`], [`Scheme::DameganeCrypt`] — have **no** second
//! implementation to check against: neither arc_unpacker nor yuzu_xp3 carries
//! them. They are GARbro-only, transcribed the same way, and their fidelity
//! rests on the tests in this file rather than on agreement between sources.
//! Their rotation primitive is GARbro's `Binary.RotByteR/L`, which is a plain
//! 8-bit rotate (`count &= 7`, the `<< 8` term truncated away) — i.e. Rust's
//! `u8::rotate_right`/`rotate_left` with the count pre-masked.
//!
//! Two caveats this comparison surfaced, both deliberate:
//!
//! * **No reference auto-detects the scheme from content.** GARbro picks it
//!   from a filename→title database and arc_unpacker from a user-selected
//!   plugin. The probe below is therefore original work, and its reliability
//!   rests on the two signals described in "How a scheme is identified" rather
//!   than on any upstream guarantee — hence the appetite for real test archives.
//! * **arc_unpacker applies the cipher to every entry unconditionally**, which
//!   this crate also now does (see "Why the protected flag is not trusted").
//!   GARbro's per-entry flag gate is the outlier here, and the real archives
//!   above show it is the wrong reading.

use archive_common::{extract_progress, looks_decrypted, safe_join, DestAllocator};
use cxdec_tools::r#struct::xp3::{Xp3Archive, Xp3Cipher, Xp3Entry};
use std::fs;
use std::io::{self, Write as _};
use std::path::Path;

/// Buffered whole-entry decode cap, matching the cxdec path: `read_entry`
/// materializes an entry in RAM, so a hostile index must not be able to use this
/// path to pin gigabytes.
const MAX_ENTRY_BYTES: u64 = 1024 * 1024 * 1024;

/// How many entries the probe reads. Eight is what cxdec's scorer uses too, and
/// a real archive's first entries are images/scripts with strong signatures.
const PROBE_SAMPLES: usize = 8;

/// How far the probe will walk the index looking for small entries to sample.
/// Bounded so a pathological archive of huge members cannot make the probe read
/// the whole file.
const PROBE_SCAN_MAX: usize = 32;

/// Bytes read per sampled entry. Enough for the longest signature we test
/// (`RIFF….WEBP`) plus the two FateCrypt fixup offsets' neighbourhood is NOT
/// needed — those only matter for whole-entry decryption.
const PROBE_PREFIX: usize = 64;

/// Entries above this packed size are skipped by the probe: `read_entry_prefix`
/// in the vendored reader inflates the WHOLE segment before truncating, so a
/// multi-GB member would be a full allocation just to look at 64 bytes.
const PROBE_MAX_PACKED: u64 = 64 * 1024 * 1024;

/// Entries at or below this unpacked size are sampled WHOLE, which unlocks the
/// ADLR identity (see [`probe_scheme`]). 1 MiB keeps the probe's worst-case read
/// at `PROBE_SAMPLES` × 1 MiB, and real script/config members — the ones that
/// need it, because they carry no binary signature — are far below it.
const PROBE_ADLR_MAX: u64 = 1024 * 1024;

/// Adler-32, matching the XP3 `adlr` chunk's own definition (RFC 1950, initial
/// value 1). Used by the probe to test a decryption against the stored checksum.
fn adler32(data: &[u8]) -> u32 {
    let (mut a, mut b) = (1u32, 0u32);
    for &byte in data {
        a = (a + byte as u32) % 65521;
        b = (b + a) % 65521;
    }
    (b << 16) | a
}

/// `AlteredPinkCrypt`'s keystream: a fixed 256-byte table indexed by
/// `offset & 0xFF`. Transcribed verbatim from GARbro
/// (`CryptAlgorithms.cs`, `AlteredPinkCrypt.KeyTable`); 16 rows of 16.
///
/// It is a *constant* table, not a derived one — the contrast with cxdec, whose
/// keystream must be reconstructed from the game's control block, is exactly
/// what makes this scheme keyless.
#[rustfmt::skip]
const ALTERED_PINK_TABLE: [u8; 256] = [
    0x43, 0xF8, 0xAD, 0x08, 0xDF, 0xB7, 0x26, 0x44, 0xF0, 0xD9, 0xE9, 0x24, 0x1A, 0xC1, 0xEE, 0xB4,
    0x11, 0x4B, 0xE4, 0xAF, 0x01, 0x5B, 0xF0, 0xAB, 0x6A, 0x70, 0x78, 0x84, 0xB0, 0x78, 0x4F, 0xED,
    0x39, 0x52, 0x69, 0xAF, 0xC4, 0x92, 0x2A, 0x21, 0xDE, 0xDC, 0x6E, 0x63, 0x9D, 0x9B, 0x63, 0xE1,
    0xB1, 0x94, 0x40, 0x6E, 0x3A, 0x52, 0x5A, 0x28, 0x08, 0x4D, 0xFB, 0x22, 0x18, 0xEB, 0xBA, 0x98,
    0x49, 0x77, 0xBF, 0xAA, 0x43, 0x75, 0xF5, 0xD3, 0x83, 0x71, 0x58, 0xA4, 0xAF, 0x1B, 0x53, 0x99,
    0x8A, 0x27, 0x5B, 0xC2, 0x7F, 0x7A, 0xCD, 0x8D, 0x33, 0x59, 0xEB, 0xA6, 0xFA, 0x7C, 0x00, 0x19,
    0xC4, 0xAA, 0x24, 0xF8, 0x84, 0xCD, 0xF7, 0x20, 0x4B, 0xAB, 0xF1, 0xD5, 0x01, 0x6F, 0x7C, 0x91,
    0x08, 0x7D, 0x8D, 0x89, 0x7C, 0x71, 0x65, 0x99, 0x9B, 0x6F, 0x3A, 0x1C, 0x49, 0xE3, 0xAF, 0x1F,
    0xC6, 0xA5, 0x79, 0xFE, 0xAE, 0xA1, 0xCA, 0x59, 0x3C, 0xEE, 0xC1, 0x02, 0xBD, 0x2B, 0x8E, 0xC5,
    0x7D, 0x38, 0x80, 0x8F, 0x72, 0xF3, 0x86, 0x5D, 0xF4, 0x20, 0x0A, 0x5B, 0xA0, 0xE3, 0x85, 0xB5,
    0x67, 0x43, 0x96, 0xBB, 0x75, 0x86, 0x8D, 0x7E, 0x7E, 0xE6, 0xAA, 0x18, 0x57, 0xC4, 0xAA, 0x87,
    0xDC, 0x74, 0x05, 0xAA, 0xBD, 0x5E, 0x4F, 0xA9, 0xB5, 0x5E, 0xC5, 0xE8, 0x11, 0x6D, 0x68, 0x89,
    0x17, 0x7C, 0x10, 0x05, 0xA2, 0xBA, 0x43, 0x01, 0xD6, 0xFD, 0x26, 0x19, 0x57, 0xFA, 0x4D, 0x01,
    0xB0, 0xED, 0x3A, 0x55, 0xEB, 0x65, 0x8E, 0xD1, 0x58, 0x27, 0xAD, 0xA1, 0x5E, 0x57, 0x3F, 0xA0,
    0xEF, 0x59, 0x3E, 0xA4, 0xEB, 0x12, 0x15, 0x60, 0xBE, 0x95, 0x61, 0x0B, 0x98, 0xF5, 0xF4, 0x12,
    0x1C, 0xD8, 0x62, 0x3F, 0xFD, 0xCF, 0x01, 0x3A, 0xE7, 0xC2, 0x19, 0x38, 0x6C, 0xC3, 0x90, 0x3E,
];

/// `FlyingShineCrypt`'s two per-entry parameters, from GARbro's
/// `FlyingShineCrypt.Adjust`: the XOR key is bits 8..15 of the hash, the
/// rotation count is its low byte.
///
/// A zero field is replaced (`0x0f` for the count, `0xf0` for the key) so a
/// degenerate hash cannot silently turn the cipher into the identity. The count
/// is then masked to 3 bits, which is what GARbro's `RotByteR/L` does internally
/// (`count &= 7`).
fn flying_shine_key(hash: u32) -> (u8, u32) {
    let mut shift = hash & 0xff;
    if shift == 0 {
        shift = 0x0f;
    }
    let mut key = ((hash >> 8) & 0xff) as u8;
    if key == 0 {
        key = 0xf0;
    }
    (key, shift & 7)
}

/// The keyless schemes, in probe order. Order matters only for the probe's
/// tie-break (the first scheme with the top score wins), and the original three
/// lead so that their existing results cannot shift.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Scheme {
    HashCrypt,
    FateCrypt,
    AppliqueCrypt,
    FlyingShineCrypt,
    AlteredPinkCrypt,
    DameganeCrypt,
}

impl Scheme {
    pub const ALL: [Scheme; 6] = [
        Scheme::HashCrypt,
        Scheme::FateCrypt,
        Scheme::AppliqueCrypt,
        Scheme::FlyingShineCrypt,
        Scheme::AlteredPinkCrypt,
        Scheme::DameganeCrypt,
    ];

    /// Stable machine name — this is what rides in the UI token (`crypt:<name>`)
    /// and comes back on the pack path, so it must never change casually.
    pub fn name(self) -> &'static str {
        match self {
            Scheme::HashCrypt => "HashCrypt",
            Scheme::FateCrypt => "FateCrypt",
            Scheme::AppliqueCrypt => "AppliqueCrypt",
            Scheme::FlyingShineCrypt => "FlyingShineCrypt",
            Scheme::AlteredPinkCrypt => "AlteredPinkCrypt",
            Scheme::DameganeCrypt => "DameganeCrypt",
        }
    }

    /// The name this scheme goes by in arc_unpacker's plugin list, where one is
    /// worth accepting. Only [`Scheme::FateCrypt`] has one: `fsn`, the plugin id
    /// the scheme is usually catalogued under, so it is the name a user is most
    /// likely to reach for.
    ///
    /// The others are deliberately left out: `xor` and `rebirth` are
    /// arc_unpacker's internal plugin ids, not names anyone knows a scheme by,
    /// and `xor` in particular is vague enough to be misleading. The three later
    /// schemes have no arc_unpacker plugin at all.
    pub fn alias(self) -> Option<&'static str> {
        match self {
            Scheme::FateCrypt => Some("fsn"),
            _ => None,
        }
    }

    /// Inverse of [`Scheme::name`] and [`Scheme::alias`], case-insensitive so a
    /// token round-trip and a hand-typed pack argument both work. The canonical
    /// name always wins when both would match.
    pub fn from_name(name: &str) -> Option<Scheme> {
        Scheme::ALL.into_iter().find(|s| {
            if s.name().eq_ignore_ascii_case(name) {
                return true;
            }
            match s.alias() {
                Some(alias) => alias.eq_ignore_ascii_case(name),
                None => false,
            }
        })
    }

    /// Whether the archive's stored ADLR covers the CIPHERTEXT rather than the
    /// plaintext (GARbro's `ICrypt.HashAfterCrypt`). Only FateCrypt does.
    ///
    /// This decides the pack layout: the vendored writer checksums whatever the
    /// caller writes, so a `true` scheme must be fed already-encrypted bytes
    /// (the checksum then lands on the ciphertext) while a `false` scheme goes
    /// through the transform path, which checksums the plaintext.
    pub fn hash_after_crypt(self) -> bool {
        matches!(self, Scheme::FateCrypt)
    }

    /// Decrypts `data`, whose first byte sits at absolute `offset` within the
    /// entry. Byte-for-byte the GARbro `ICrypt.Decrypt(entry, offset, values,
    /// pos, count)` bodies with `pos = 0` and `count = data.len()`.
    pub fn decrypt(self, hash: u32, offset: u64, data: &mut [u8]) {
        match self {
            // Constant key: `entry.Hash` truncated to its low byte.
            Scheme::HashCrypt => {
                let key = hash as u8;
                for b in data.iter_mut() {
                    *b ^= key;
                }
            }
            // Constant 0x36 over the whole range, with two single-byte fixups at
            // fixed absolute offsets. Order and the early returns are GARbro's.
            Scheme::FateCrypt => {
                for b in data.iter_mut() {
                    *b ^= 0x36;
                }
                let count = data.len() as u64;
                if offset > 0x2ea29 {
                    return;
                }
                if offset + count > 0x2ea29 {
                    data[(0x2ea29 - offset) as usize] ^= 3;
                }
                if offset > 0x13 {
                    return;
                }
                if offset + count > 0x13 {
                    data[(0x13 - offset) as usize] ^= 1;
                }
            }
            // The first 5 bytes are stored verbatim; the rest uses a key taken
            // from bits 12..19 of the hash. A window that starts mid-entry only
            // skips the prefix when the window itself begins before offset 5.
            Scheme::AppliqueCrypt => {
                let skip = if offset < 5 {
                    ((5 - offset) as usize).min(data.len())
                } else {
                    0
                };
                let key = (hash >> 12) as u8;
                for b in data[skip..].iter_mut() {
                    *b ^= key;
                }
            }
            // Key byte in bits 8..15, rotation count in the low byte. Note this
            // is NOT an involution: the inverse is `rotate_left` then XOR, which
            // is what `encrypt` below does.
            Scheme::FlyingShineCrypt => {
                let (key, shift) = flying_shine_key(hash);
                for b in data.iter_mut() {
                    *b = (*b ^ key).rotate_right(shift);
                }
            }
            // A fixed keystream table addressed by the low 8 bits of the offset.
            Scheme::AlteredPinkCrypt => {
                for (i, b) in data.iter_mut().enumerate() {
                    *b ^= ALTERED_PINK_TABLE[((offset + i as u64) & 0xff) as usize];
                }
            }
            // Odd offsets use the hash, even ones the offset itself. The even
            // case leaves offset 0 alone, so the first byte is stored verbatim —
            // which is still enough to break a file signature, because byte 1 is
            // XORed with the hash.
            Scheme::DameganeCrypt => {
                for (i, b) in data.iter_mut().enumerate() {
                    let off = offset + i as u64;
                    *b ^= if off & 1 != 0 { hash as u8 } else { off as u8 };
                }
            }
        }
    }

    /// Encrypts `data` — the true inverse of [`Scheme::decrypt`], not a copy of
    /// it. Five of the six schemes happen to be involutions over XOR with a
    /// fixed key, so for them this forwards to `decrypt` (matching GARbro, whose
    /// `Encrypt` either forwards to `Decrypt` or repeats its body verbatim).
    /// [`Scheme::FlyingShineCrypt`] is the exception: rotation is directional, so
    /// its inverse is `rotate_left` *then* XOR — the two operations do not
    /// commute.
    ///
    /// Every arm is per-byte, so chunking composes: the pack path streams a
    /// member in 64 KiB pieces and needs `encrypt(0, off, chunk)` over the pieces
    /// to equal one call over the whole entry. (That path is only taken by
    /// schemes whose ADLR covers the ciphertext — see `hash_after_crypt`.)
    pub fn encrypt(self, hash: u32, offset: u64, data: &mut [u8]) {
        match self {
            Scheme::FlyingShineCrypt => {
                let (key, shift) = flying_shine_key(hash);
                for b in data.iter_mut() {
                    *b = b.rotate_left(shift) ^ key;
                }
            }
            _ => self.decrypt(hash, offset, data),
        }
    }
}

/// [`Xp3Cipher`] adapter so the vendored reader can drive a [`Scheme`].
///
/// `is_encrypted` is overridden — see the trait impl below.
pub struct SchemeCipher {
    scheme: Scheme,
}

impl SchemeCipher {
    pub fn new(scheme: Scheme) -> Self {
        Self { scheme }
    }

    pub fn scheme(&self) -> Scheme {
        self.scheme
    }
}

impl Xp3Cipher for SchemeCipher {
    /// Always `true`, deliberately ignoring the INFO `protected` flag.
    ///
    /// The decision to run a cipher is made **once for the whole archive** by
    /// [`probe_scheme`], from content; once made, every member is decrypted. The
    /// flag is not consulted — see "Why the protected flag is not trusted" in the
    /// module docs for the real-archive evidence that it is unreliable in both
    /// directions.
    fn is_encrypted(&self, _entry: &Xp3Entry) -> bool {
        true
    }

    fn decrypt(&mut self, hash: u32, offset: u64, data: &mut [u8]) -> io::Result<()> {
        self.scheme.decrypt(hash, offset, data);
        Ok(())
    }
}

/// Identifies the keyless scheme protecting `archive`, if any.
///
/// Read-only and side-effect-free — it opens the archive, samples up to
/// `PROBE_SAMPLES` entries and scores each scheme with the two signals
/// described in the module docs (file signature, and the stored-ADLR identity).
/// `None` covers every "cannot say" outcome: not an XP3, no readable entries, or
/// content no candidate scheme explains.
///
/// The protected flag is deliberately **not** consulted: real archives carry the
/// cipher with the flag clear, so gating on it would miss them. The
/// content test below is self-policing on plain archives — see the module docs.
pub fn probe_scheme(archive: &str) -> Option<Scheme> {
    let mut arch = Xp3Archive::open(Path::new(archive)).ok()?;

    // A sampled entry: its stored ADLR, its bytes as stored, and whether those
    // bytes are the WHOLE entry — signal (2) is only sound on a whole entry,
    // signal (1) is happy with a prefix.
    struct Sample {
        hash: u32,
        bytes: Vec<u8>,
        whole: bool,
    }

    // The raw bytes are read with a no-op cipher: both `read_entry` and
    // `read_entry_prefix` gate on `is_encrypted`, but a no-op leaves the bytes
    // exactly as stored either way, which is what both signals need.
    let mut samples: Vec<Sample> = Vec::with_capacity(PROBE_SAMPLES);
    let mut noop = |_h: u32, _o: u64, _d: &mut [u8]| Ok(());
    for i in 0..arch.entries.len().min(PROBE_SCAN_MAX) {
        if samples.len() >= PROBE_SAMPLES {
            break;
        }
        let (hash, packed, unpacked) = {
            let e = &arch.entries[i];
            (
                e.hash,
                e.segments.iter().map(|s| s.packed_size).sum::<u64>(),
                e.unpacked_size,
            )
        };
        if packed > PROBE_MAX_PACKED {
            continue;
        }
        // Small entries are taken whole so the ADLR identity applies; larger ones
        // fall back to a prefix, which still supports the signature signal.
        let sample = if unpacked <= PROBE_ADLR_MAX {
            arch.read_entry(i, &mut noop).ok().map(|bytes| Sample { hash, bytes, whole: true })
        } else {
            arch.read_entry_prefix(i, PROBE_PREFIX, &mut noop)
                .ok()
                .map(|bytes| Sample { hash, bytes, whole: false })
        };
        if let Some(s) = sample {
            if !s.bytes.is_empty() {
                samples.push(s);
            }
        }
    }
    if samples.is_empty() {
        return None;
    }

    let mut best: Option<(usize, Scheme)> = None;
    for scheme in Scheme::ALL {
        let hits = samples
            .iter()
            .filter(|s| {
                let mut dec = s.bytes.clone();
                scheme.decrypt(s.hash, 0, &mut dec);
                let signature = looks_decrypted(&dec) && !looks_decrypted(&s.bytes);
                let adlr = s.whole && adler32(&dec) == s.hash && adler32(&s.bytes) != s.hash;
                signature || adlr
            })
            .count();
        if hits > 0 && best.is_none_or(|(score, _)| hits > score) {
            best = Some((hits, scheme));
        }
    }
    best.map(|(_, scheme)| scheme)
}

/// Extracts one keyless-encrypted XP3. `selected` is the newline-separated path
/// list used by selective extraction (exact + directory-prefix match, identical
/// semantics to the plain and cxdec paths). Progress and cancel feed the xp3
/// extract store, which the app already polls through the "xp3" accessors.
pub fn extract(
    archive: &str,
    output: &str,
    scheme: Scheme,
    selected: Option<&str>,
) -> Result<(u32, u32), String> {
    let mut arch = Xp3Archive::open(Path::new(archive)).map_err(|e| format!("XP3: {e}"))?;
    let sel_set: Option<std::collections::HashSet<&str>> =
        selected.map(|s| s.lines().filter(|l| !l.is_empty()).collect());
    let matches = |raw_name: &str| -> bool {
        match &sel_set {
            None => true,
            Some(sel) => {
                let norm = raw_name.replace('\\', "/");
                sel.contains(norm.as_str())
                    || sel.iter().any(|d| {
                        let dd = if d.ends_with('/') { &d[..d.len() - 1] } else { d };
                        norm.starts_with(&format!("{dd}/"))
                    })
            }
        }
    };

    let total: u32 = arch.entries.iter().filter(|e| matches(&e.name)).count() as u32;
    extract_progress::reset(
        arch.entries.iter().filter(|e| matches(&e.name)).map(|e| e.unpacked_size).sum(),
    );

    let mut cipher = SchemeCipher::new(scheme);
    let mut dests = DestAllocator::new();
    let mut fail = 0u32;
    for i in 0..arch.entries.len() {
        if extract_progress::cancelled() {
            return Err("cancelled".to_string());
        }
        let (name, unpacked, packed) = {
            let e = &arch.entries[i];
            if !matches(&e.name) {
                continue;
            }
            (
                e.name.clone(),
                e.unpacked_size,
                e.segments.iter().map(|s| s.packed_size).sum::<u64>(),
            )
        };
        extract_progress::set_name(&name);
        extract_progress::set_file(unpacked);
        if unpacked > MAX_ENTRY_BYTES || packed > MAX_ENTRY_BYTES {
            fail += 1;
            continue;
        }
        let dest = match safe_join(output, &name) {
            Ok(d) => dests.allocate(d),
            Err(_) => {
                fail += 1;
                continue;
            }
        };
        if let Some(p) = dest.parent() {
            let _ = fs::create_dir_all(p);
        }
        // Decoded fully in RAM before any file is created, so a decode failure
        // leaves nothing behind; only write failures can leave a partial file,
        // and those are removed below.
        let data = match arch.read_entry(i, &mut cipher) {
            Ok(d) => d,
            Err(_) => {
                fail += 1;
                continue;
            }
        };
        let written = (|| -> io::Result<()> {
            let mut f = fs::File::create(&dest)?;
            f.write_all(&data)?;
            f.flush()
        })();
        if written.is_err() {
            let _ = fs::remove_file(&dest);
            fail += 1;
            continue;
        }
        extract_progress::add_bytes(data.len() as u64);
        if data.len() as u64 != unpacked {
            extract_progress::calibrate_file(data.len() as u64);
            let delta = (data.len() as i128 - unpacked as i128)
                .clamp(i64::MIN as i128, i64::MAX as i128);
            extract_progress::adjust_total(delta as i64);
        }
    }
    Ok((total, fail))
}

/// Reads one named entry with an explicit scheme — the encrypted writer's
/// self-check, mirroring cxdec-core's `read_named_with`. `max_len` truncates to
/// a prefix so an oversized member is not buffered whole during verification.
pub fn read_named_with(
    archive: &str,
    name: &str,
    scheme: Scheme,
    max_len: Option<usize>,
) -> Result<Vec<u8>, String> {
    let mut arch = Xp3Archive::open(Path::new(archive)).map_err(|e| format!("XP3: {e}"))?;
    let idx = arch
        .entries
        .iter()
        .position(|e| e.name == name)
        .ok_or_else(|| format!("XP3: 归档内没有条目 {name}"))?;
    let mut cipher = SchemeCipher::new(scheme);
    match max_len {
        Some(n) => arch.read_entry_prefix(idx, n, &mut cipher),
        None => arch.read_entry(idx, &mut cipher),
    }
    .map_err(|e| format!("XP3: {e}"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use cxdec_tools::r#struct::xp3::MAGIC;

    fn tmp(tag: &str) -> std::path::PathBuf {
        std::env::temp_dir().join(format!("uu_xp3crypt_{}_{}", std::process::id(), tag))
    }

    fn chunk(sig: &[u8; 4], payload: &[u8]) -> Vec<u8> {
        let mut v = sig.to_vec();
        v.extend_from_slice(&(payload.len() as i64).to_le_bytes());
        v.extend_from_slice(payload);
        v
    }

    /// A minimal uncompressed-index XP3 whose stored segment payloads are the
    /// given bytes, with an explicit ADLR per entry (the caller decides whether
    /// that is the plaintext's or the ciphertext's checksum — the whole point of
    /// the `HashAfterCrypt` split).
    fn build_xp3(entries: &[(&str, u32, Vec<u8>)]) -> Vec<u8> {
        build_xp3_flagged(entries, 1)
    }

    fn build_xp3_flagged(entries: &[(&str, u32, Vec<u8>)], protected: u32) -> Vec<u8> {
        let mut data = Vec::new();
        let mut index = Vec::new();
        let data_base: i64 = (MAGIC.len() + 8) as i64;
        for (name, hash, content) in entries {
            let mut file_chunk = Vec::new();
            let mut info = Vec::new();
            info.extend_from_slice(&protected.to_le_bytes()); // protected flag
            info.extend_from_slice(&(content.len() as i64).to_le_bytes());
            info.extend_from_slice(&(content.len() as i64).to_le_bytes());
            let units: Vec<u16> = name.encode_utf16().collect();
            info.extend_from_slice(&(units.len() as i16).to_le_bytes());
            for u in units {
                info.extend_from_slice(&u.to_le_bytes());
            }
            file_chunk.extend(chunk(b"info", &info));
            let mut segm = Vec::new();
            segm.extend_from_slice(&0i32.to_le_bytes()); // stored, not compressed
            segm.extend_from_slice(&(data_base + data.len() as i64).to_le_bytes());
            segm.extend_from_slice(&(content.len() as i64).to_le_bytes());
            segm.extend_from_slice(&(content.len() as i64).to_le_bytes());
            file_chunk.extend(chunk(b"segm", &segm));
            file_chunk.extend(chunk(b"adlr", &hash.to_le_bytes()));
            index.extend(chunk(b"File", &file_chunk));
            data.extend_from_slice(content);
        }
        let mut out = Vec::new();
        out.extend_from_slice(MAGIC);
        let offset_pos = out.len();
        out.extend_from_slice(&0i64.to_le_bytes());
        out.extend_from_slice(&data);
        let index_offset = out.len() as i64;
        out.extend_from_slice(&0u8.to_le_bytes()); // uncompressed index
        out.extend_from_slice(&(index.len() as i64).to_le_bytes());
        out.extend_from_slice(&index);
        out[offset_pos..offset_pos + 8].copy_from_slice(&index_offset.to_le_bytes());
        out
    }

    /// Encrypts `plaintext` under `scheme` and returns the (hash, ciphertext)
    /// pair to store — the hash is the plaintext's for a `HashAfterCrypt=false`
    /// scheme and the ciphertext's for FateCrypt, exactly as a real packer does.
    fn encrypt_entry(scheme: Scheme, plaintext: &[u8]) -> (u32, Vec<u8>) {
        let plain_hash = adler32(plaintext);
        let mut payload = plaintext.to_vec();
        scheme.encrypt(plain_hash, 0, &mut payload);
        let stored = if scheme.hash_after_crypt() { adler32(&payload) } else { plain_hash };
        (stored, payload)
    }

    /// A PNG-headed payload: the 8-byte signature is what every scheme's probe
    /// keys on, and it is longer than AppliqueCrypt's untouched 5-byte prefix.
    fn png_plaintext(n: usize) -> Vec<u8> {
        let mut v = b"\x89PNG\r\n\x1a\n".to_vec();
        v.extend((v.len()..n).map(|i| ((i * 31 + 7) % 251) as u8));
        v
    }

    fn write_archive(dir: &std::path::Path, scheme: Scheme, names_and_sizes: &[(&str, usize)]) -> std::path::PathBuf {
        let entries: Vec<(&str, u32, Vec<u8>)> = names_and_sizes
            .iter()
            .map(|(name, size)| {
                let plain = png_plaintext(*size);
                let (hash, enc) = encrypt_entry(scheme, &plain);
                (*name, hash, enc)
            })
            .collect();
        let xp3 = dir.join("data.xp3");
        fs::write(&xp3, build_xp3(&entries)).unwrap();
        xp3
    }

    #[test]
    fn each_scheme_is_detected_and_extracts() {
        for scheme in Scheme::ALL {
            let dir = tmp(&format!("detect_{}", scheme.name()));
            fs::create_dir_all(&dir).unwrap();
            let xp3 = write_archive(&dir, scheme, &[("bg/a.png", 4096), ("bg/b.png", 3000)]);

            assert_eq!(probe_scheme(xp3.to_str().unwrap()), Some(scheme), "{}", scheme.name());

            let out = dir.join("out");
            fs::create_dir_all(&out).unwrap();
            let (done, fail) =
                extract(xp3.to_str().unwrap(), out.to_str().unwrap(), scheme, None).unwrap();
            assert_eq!((done, fail), (2, 0), "{}", scheme.name());
            assert_eq!(fs::read(out.join("bg/a.png")).unwrap(), png_plaintext(4096));
            assert_eq!(fs::read(out.join("bg/b.png")).unwrap(), png_plaintext(3000));

            fs::remove_dir_all(&dir).ok();
        }
    }

    /// The single most important negative: a plain archive must never be
    /// mistaken for an encrypted one. It is the exact shape `AppliqueCrypt`
    /// would otherwise false-positive on, because that scheme leaves the first
    /// 5 bytes — and therefore the raw signature — untouched.
    #[test]
    fn plain_archive_is_not_detected() {
        let dir = tmp("plain");
        fs::create_dir_all(&dir).unwrap();
        let entries: Vec<(&str, u32, Vec<u8>)> = vec![
            ("bg/a.png", adler32(&png_plaintext(4096)), png_plaintext(4096)),
            ("bg/b.png", adler32(&png_plaintext(3000)), png_plaintext(3000)),
        ];
        let xp3 = dir.join("data.xp3");
        fs::write(&xp3, build_xp3(&entries)).unwrap();
        assert_eq!(probe_scheme(xp3.to_str().unwrap()), None);
        fs::remove_dir_all(&dir).ok();
    }

    /// The flag must NOT gate the probe: a real archive can carry the cipher on
    /// every entry with the flag cleared, and a flag gate would leave it as
    /// ciphertext. Detection is from content, so a clear flag changes nothing.
    #[test]
    fn ciphertext_with_clear_flag_is_still_detected() {
        let dir = tmp("unflagged_cipher");
        fs::create_dir_all(&dir).unwrap();
        let plain = png_plaintext(4096);
        let (hash, enc) = encrypt_entry(Scheme::HashCrypt, &plain);
        let xp3 = dir.join("data.xp3");
        fs::write(&xp3, build_xp3_flagged(&[("bg/a.png", hash, enc)], 0)).unwrap();
        assert_eq!(probe_scheme(xp3.to_str().unwrap()), Some(Scheme::HashCrypt));
        fs::remove_dir_all(&dir).ok();
    }

    /// The converse lie: a SET flag over genuinely-plain content — the shape of a
    /// planted decoy. Content wins, so nothing is claimed
    /// — a flag gate would have decrypted the plaintext into noise.
    #[test]
    fn plaintext_with_set_flag_is_not_detected() {
        let dir = tmp("flagged_plain");
        fs::create_dir_all(&dir).unwrap();
        let entries: Vec<(&str, u32, Vec<u8>)> =
            vec![("bg/a.png", adler32(&png_plaintext(4096)), png_plaintext(4096))];
        let xp3 = dir.join("data.xp3");
        fs::write(&xp3, build_xp3_flagged(&entries, 1)).unwrap();
        assert_eq!(probe_scheme(xp3.to_str().unwrap()), None);
        fs::remove_dir_all(&dir).ok();
    }

    /// The text-only shape: FateCrypt over content that carries no binary
    /// signature (a script/config text). The signature signal cannot see it, so
    /// this only passes if the ADLR identity is evaluated — which requires the
    /// entry to be sampled whole.
    ///
    /// Note the fixture's ADLR is the PLAINTEXT's, which is the convention most
    /// real archives use and the only one signal (2) can test. `create_xp3` does
    /// not produce this for FateCrypt (see `hash_after_crypt`), so this test
    /// deliberately hand-builds the archive rather than packing one — the packer
    /// path is covered by `keyless_pack_round_trips_through_probe_and_extract`,
    /// which keeps a signature on every member for exactly that reason.
    #[test]
    fn text_only_archive_is_detected_by_the_adlr_identity() {
        let dir = tmp("text_only");
        fs::create_dir_all(&dir).unwrap();
        let plain = b"// plain script text, no binary signature at all\n".to_vec();
        assert!(!looks_decrypted(&plain), "precondition: no signature to key on");
        let hash = adler32(&plain);
        let mut enc = plain.clone();
        Scheme::FateCrypt.encrypt(hash, 0, &mut enc);
        assert_ne!(adler32(&enc), hash, "precondition: the stored ADLR covers the plaintext");
        let xp3 = dir.join("data.xp3");
        fs::write(&xp3, build_xp3_flagged(&[("script/main.tjs", hash, enc)], 0)).unwrap();
        assert_eq!(probe_scheme(xp3.to_str().unwrap()), Some(Scheme::FateCrypt));
        fs::remove_dir_all(&dir).ok();
    }

    /// The ADLR signal must not resurrect the plain-archive false positive: a
    /// plain member satisfies `adler32(raw) == stored`, which the test's second
    /// half rejects.
    #[test]
    fn plain_text_archive_is_not_detected_by_the_adlr_identity() {
        let dir = tmp("plain_text");
        fs::create_dir_all(&dir).unwrap();
        let plain = b"// plain script text, no binary signature at all\n".to_vec();
        let entries: Vec<(&str, u32, Vec<u8>)> = vec![("script/main.tjs", adler32(&plain), plain)];
        let xp3 = dir.join("data.xp3");
        fs::write(&xp3, build_xp3(&entries)).unwrap();
        assert_eq!(probe_scheme(xp3.to_str().unwrap()), None);
        fs::remove_dir_all(&dir).ok();
    }

    /// `AppliqueCrypt` stores its first five bytes verbatim and keys the rest
    /// off bits 12..19 of the hash — the property that makes it distinguishable
    /// from plaintext at all.
    #[test]
    fn applique_preserves_the_prefix_and_keys_from_the_high_bits() {
        let hash = 0x00AB_CDEF;
        let key = (hash >> 12) as u8;
        let plain: Vec<u8> = (0..16u8).collect();
        let mut buf = plain.clone();
        Scheme::AppliqueCrypt.encrypt(hash, 0, &mut buf);
        assert_eq!(&buf[..5], &plain[..5], "first five bytes must be untouched");
        for i in 5..16 {
            assert_eq!(buf[i], plain[i] ^ key, "byte {i}");
        }
        // A window that starts at offset 5 is entirely past the prefix, so the
        // whole span — including its first byte — is a plain XOR. (The prefix
        // exemption is keyed on the LOGICAL offset, not on the window's own
        // first byte.)
        let mut mid = plain.clone();
        Scheme::AppliqueCrypt.encrypt(hash, 5, &mut mid);
        for i in 0..16 {
            assert_eq!(mid[i], plain[i] ^ key, "byte {i} at window offset 5");
        }
    }

    /// FateCrypt's two fixups sit at absolute offsets and must fire only when the
    /// window actually covers them — a chunked decrypt has to agree with a
    /// whole-entry decrypt.
    #[test]
    fn fate_fixups_are_offset_addressed() {
        let plain: Vec<u8> = (0..64u8).collect();
        let whole = {
            let mut v = plain.clone();
            Scheme::FateCrypt.encrypt(0, 0, &mut v);
            v
        };
        // Chunked, one byte at a time — every window is a different offset.
        let mut chunked = plain.clone();
        for (i, b) in chunked.iter_mut().enumerate() {
            let mut one = [*b];
            Scheme::FateCrypt.encrypt(0, i as u64, &mut one);
            *b = one[0];
        }
        assert_eq!(whole, chunked, "per-chunk decryption must match whole-entry");
        assert_eq!(whole[0x13], plain[0x13] ^ 0x36 ^ 1, "offset 0x13 carries the extra ^1");
        assert_eq!(whole[0x14], plain[0x14] ^ 0x36, "offset 0x14 is plain 0x36");
    }

    /// Every scheme must survive `decrypt(encrypt(x))`, and — because the pack
    /// path streams a member in 64 KiB pieces — a chunked pass must agree with a
    /// single whole-entry pass.
    ///
    /// `FlyingShineCrypt` is what makes this worth writing: it is the only
    /// non-involution, so an `encrypt` that merely forwarded to `decrypt` would
    /// pass every other test in this file and fail only here.
    #[test]
    fn every_scheme_round_trips_whole_and_in_chunks() {
        let hash = 0x5A17_3C9E;
        let plain: Vec<u8> = (0..4096u32).map(|i| ((i * 37 + 11) % 256) as u8).collect();

        for scheme in Scheme::ALL {
            let mut whole = plain.clone();
            scheme.encrypt(hash, 0, &mut whole);
            assert_ne!(whole, plain, "{}: encrypt must actually change the bytes", scheme.name());

            let mut back = whole.clone();
            scheme.decrypt(hash, 0, &mut back);
            assert_eq!(back, plain, "{}: decrypt must invert encrypt", scheme.name());

            // An awkward chunk size: it straddles AlteredPinkCrypt's 256-byte
            // table period and both parities for DameganeCrypt, and it starts
            // chunks past AppliqueCrypt's 5-byte prefix.
            let mut chunked = plain.clone();
            let chunk = 251usize;
            let mut off = 0usize;
            while off < chunked.len() {
                let end = (off + chunk).min(chunked.len());
                scheme.encrypt(hash, off as u64, &mut chunked[off..end]);
                off = end;
            }
            assert_eq!(chunked, whole, "{}: chunked encrypt must match whole-entry", scheme.name());
        }
    }

    /// `FlyingShineCrypt`'s parameter derivation, including the zero
    /// substitutions that keep a degenerate hash from degenerating the cipher,
    /// and the 3-bit mask GARbro's `RotByteR/L` applies internally.
    #[test]
    fn flying_shine_key_masks_and_substitutes() {
        // Both fields zero: the substitutions fire.
        assert_eq!(flying_shine_key(0x0000_0000), (0xf0, 7));
        // Ordinary hash: key from bits 8..15, count from the low byte.
        assert_eq!(flying_shine_key(0x0000_3A05), (0x3a, 5));
        // A low byte that survives as non-zero but masks to 0 (8 & 7 == 0) is
        // NOT substituted — the substitution happens before the mask.
        assert_eq!(flying_shine_key(0x0000_3A08), (0x3a, 0));

        // And the transform itself: rotate-then-XOR out, XOR-then-rotate back.
        let h = 0x0000_0301; // key 0x03, shift 1
        let mut b = [0b1011_0001u8];
        Scheme::FlyingShineCrypt.encrypt(h, 0, &mut b);
        assert_eq!(b[0], 0b0110_0011 ^ 0x03);
        Scheme::FlyingShineCrypt.decrypt(h, 0, &mut b);
        assert_eq!(b[0], 0b1011_0001);
    }

    /// `AlteredPinkCrypt` is a pure table lookup: the index is `offset & 0xFF`,
    /// so it wraps at 256, and the entry's hash plays no part at all.
    #[test]
    fn altered_pink_indexes_its_table_by_offset() {
        let mut enc = vec![0u8; 300];
        Scheme::AlteredPinkCrypt.encrypt(0, 0, &mut enc);
        assert_eq!(enc[0], ALTERED_PINK_TABLE[0]);
        assert_eq!(enc[255], ALTERED_PINK_TABLE[255]);
        assert_eq!(enc[256], ALTERED_PINK_TABLE[0], "the index wraps at 256");
        assert_eq!(enc[299], ALTERED_PINK_TABLE[43]);

        let mut other = vec![0u8; 300];
        Scheme::AlteredPinkCrypt.encrypt(0xDEAD_BEEF, 0, &mut other);
        assert_eq!(other, enc, "the hash must not affect this scheme");
    }

    /// `DameganeCrypt` alternates its key by offset parity, which leaves offset 0
    /// verbatim — byte 1 is what still breaks a file signature.
    #[test]
    fn damegane_alternates_by_offset_parity() {
        let hash = 0x1234_56AB;
        let mut enc = vec![0u8; 6];
        Scheme::DameganeCrypt.encrypt(hash, 0, &mut enc);
        assert_eq!(enc[0], 0x00, "offset 0 is even, and XOR 0 is a no-op");
        assert_eq!(enc[1], 0xAB, "offset 1 is odd: XOR the low byte of the hash");
        assert_eq!(enc[2], 0x02, "offset 2 is even: XOR the offset itself");
        assert_eq!(enc[3], 0xAB);
        assert_eq!(enc[4], 0x04);
        assert_eq!(enc[5], 0xAB);
    }

    /// The scheme name is the UI token and the pack argument, so the round-trip
    /// has to be exact and case-insensitive.
    #[test]
    fn scheme_names_round_trip() {
        for scheme in Scheme::ALL {
            assert_eq!(Scheme::from_name(scheme.name()), Some(scheme));
            assert_eq!(Scheme::from_name(&scheme.name().to_lowercase()), Some(scheme));
        }
        // FateCrypt also answers to arc_unpacker's plugin name; the other two
        // deliberately do not (see `Scheme::alias`).
        assert_eq!(Scheme::FateCrypt.alias(), Some("fsn"));
        assert_eq!(Scheme::HashCrypt.alias(), None);
        assert_eq!(Scheme::AppliqueCrypt.alias(), None);
        assert_eq!(Scheme::from_name("fsn"), Some(Scheme::FateCrypt));
        assert_eq!(Scheme::from_name("FSN"), Some(Scheme::FateCrypt));
        assert_eq!(Scheme::from_name("rebirth"), None);
        assert_eq!(Scheme::from_name("xor"), None);
        assert_eq!(Scheme::from_name("nope"), None);
    }

    /// Selective extraction uses the same exact + directory-prefix rules as the
    /// plain and cxdec paths.
    #[test]
    fn selective_extraction_matches_directories() {
        let dir = tmp("selective");
        fs::create_dir_all(&dir).unwrap();
        let xp3 = write_archive(&dir, Scheme::FateCrypt, &[("bg/a.png", 4096), ("script/x.tjs", 2048)]);
        let out = dir.join("out");
        fs::create_dir_all(&out).unwrap();
        let (done, fail) = extract(
            xp3.to_str().unwrap(),
            out.to_str().unwrap(),
            Scheme::FateCrypt,
            Some("script/\n"),
        )
        .unwrap();
        assert_eq!((done, fail), (1, 0));
        assert!(!out.join("bg").exists());
        assert_eq!(fs::read(out.join("script/x.tjs")).unwrap(), png_plaintext(2048));
        fs::remove_dir_all(&dir).ok();
    }

    /// `read_named_with` is what the writer's self-check uses; it must return the
    /// decrypted bytes and honour the prefix cap.
    #[test]
    fn read_named_with_decrypts_and_caps() {
        let dir = tmp("read_named");
        fs::create_dir_all(&dir).unwrap();
        let xp3 = write_archive(&dir, Scheme::HashCrypt, &[("bg/a.png", 4096)]);
        let full =
            read_named_with(xp3.to_str().unwrap(), "bg/a.png", Scheme::HashCrypt, None).unwrap();
        assert_eq!(full, png_plaintext(4096));
        let head =
            read_named_with(xp3.to_str().unwrap(), "bg/a.png", Scheme::HashCrypt, Some(100)).unwrap();
        assert_eq!(head, png_plaintext(4096)[..100]);
        assert!(read_named_with(xp3.to_str().unwrap(), "missing", Scheme::HashCrypt, None).is_err());
        fs::remove_dir_all(&dir).ok();
    }
}
