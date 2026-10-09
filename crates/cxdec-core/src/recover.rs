//! Pure-archive cxdec recovery: decrypting a lone `.xp3` whose control block
//! (`xp3filter.tjs`, a `.tpm`/`.dat` side file, or the game EXE) is gone.
//!
//! The normal path needs the 4096-byte control block to build the key-derivation
//! VM, and the block is **not** reconstructible: measured on a real feng dump it
//! is game-specific data — it embeds the game's own scenario text, its
//! `(C)2013 feng` copyright line, and a vendor notice forbidding reuse — rather
//! than a fixed SDK constant. But the archive still carries everything needed to
//! decrypt *itself*:
//!
//! 1. **Its own oracle.** Each entry's `ADLR` chunk is the adler32 of the
//!    plaintext, and that same value is the cipher key. So every entry verifies
//!    its own decryption; nothing external is required.
//! 2. **A nearly-constant keystream.** `decode()` derives `key1/key2/key3` from
//!    the key alone — never from the position — so across a whole entry the
//!    keystream is one constant byte per region plus at most two single-byte
//!    fixups.
//!
//! Measured over a real 2432-entry archive, the fixups land as:
//!
//! | region A | region B | entries |
//! |---|---|---|
//! | 0 | 0 | 385 |
//! | 0 | 1 | 321 |
//! | 0 | 2 | 1414 |
//! | other | | 312 |
//!
//! Region A is short (`base_offset = (hash & mask) + offset`, at most ~2.7 KB)
//! while a fixup position is a 16-bit `ret2` half, so region A is clean 87% of
//! the time. A file larger than 64 KB always lands **both** fixups in region B,
//! which is why `(0,2)` dominates. This module solves the **constant-only**
//! tier exactly — both regions clean — which needs no format knowledge at all,
//! and refuses every other entry rather than emitting garbage.

use crate::{SchemeSpec, DEFAULT_ORDERS, FENG_TEMPLATE, SCHEME_TABLE};
use archive_common::{extract_progress, safe_join, DestAllocator};
use cxdec_tools::r#struct::xp3::Xp3Archive;
use std::fs;
use std::io;
use std::path::Path;

/// adler32 exactly as the XP3 `ADLR` chunk stores it — and the value the cipher
/// keys on. `a` is the running byte sum, `b` the running sum of `a`.
pub fn adler32(data: &[u8]) -> u32 {
    let (mut a, mut b) = (1u32, 0u32);
    for &byte in data {
        a = (a + byte as u32) % 65521;
        b = (b + a) % 65521;
    }
    (b << 16) | a
}

/// A keystream recovered from an entry's ciphertext and its own ADLR alone.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Keystream {
    /// Absolute split (`base_offset`). Region B is empty when this equals the
    /// entry length — the common case for small files.
    pub split: usize,
    pub const_a: u8,
    pub const_b: u8,
}

impl Keystream {
    /// XORs the keystream in. XOR is its own inverse, so this both decrypts and
    /// re-encrypts.
    pub fn apply(&self, data: &mut [u8]) {
        let split = self.split.min(data.len());
        for b in &mut data[..split] {
            *b ^= self.const_a;
        }
        for b in &mut data[split..] {
            *b ^= self.const_b;
        }
    }
}

/// The magic a name's extension promises, where we know it. Used only to
/// *cross-check* a candidate the adler oracle already accepted — never to drive
/// the search — so an unknown extension costs nothing but a little confidence.
fn expected_magic(name: &str) -> Option<&'static [u8]> {
    let ext = name.rsplit('.').next()?.to_ascii_lowercase();
    Some(match ext.as_str() {
        "png" => b"\x89PNG\r\n\x1a\n",
        "jpg" | "jpeg" => b"\xff\xd8\xff",
        "ogg" | "ogv" => b"OggS",
        "wav" | "webp" | "avi" => b"RIFF",
        "zip" => b"PK\x03\x04",
        "ttf" => b"\x00\x01\x00\x00",
        "otf" => b"OTTO",
        "tlg" => b"TLG",
        "bmp" => b"BM",
        "gif" => b"GIF8",
        "flac" => b"fLaC",
        "swf" => b"FWS",
        "tjs" | "ks" => b"\xff\xfe",
        _ => return None,
    })
}

/// `(byte counts, adler-`b` weights mod 65521)` over `data`, where the byte at
/// absolute index `j` weighs `(n - j)` — the coefficient adler's `b` accumulator
/// gives it. Reducing the weights mod 65521 keeps every later sum in `u64`.
fn histogram(data: &[u8], n: usize, start: usize) -> ([u64; 256], [u64; 256]) {
    let mut cnt = [0u64; 256];
    let mut w = [0u64; 256];
    for (i, &b) in data.iter().enumerate() {
        let idx = b as usize;
        cnt[idx] += 1;
        w[idx] = (w[idx] + (n - (start + i)) as u64) % 65521;
    }
    (cnt, w)
}

/// For every candidate constant `k`, the two sums a constant XOR contributes to
/// adler: `Σ_v cnt[v]·(v ^ k)` and the weighted version. This is the 256×256
/// step that makes the 65536-pair scan afterwards O(1) per pair, so a whole
/// entry costs `O(n + 65536)` instead of `O(n · 65536)`.
fn separable(cnt: &[u64; 256], w: &[u64; 256]) -> ([u64; 256], [u64; 256]) {
    let mut s = [0u64; 256];
    let mut sw = [0u64; 256];
    for k in 0..256usize {
        let (mut acc, mut accw) = (0u64, 0u64);
        for v in 0..256usize {
            let x = (v ^ k) as u64;
            acc += cnt[v] * x;
            accw += w[v] * x;
        }
        s[k] = acc % 65521;
        sw[k] = accw % 65521;
    }
    (s, sw)
}

/// Whether the candidate keystream gives the plaintext the magic its extension
/// promises. `None` (unknown extension) always passes.
fn magic_matches(magic: Option<&[u8]>, cipher: &[u8], ks: &Keystream) -> bool {
    let m = match magic {
        Some(m) => m,
        None => return true,
    };
    if cipher.len() < m.len() {
        return false;
    }
    m.iter().enumerate().all(|(i, &want)| {
        let k = if i < ks.split { ks.const_a } else { ks.const_b };
        cipher[i] ^ k == want
    })
}

/// Recovers the constant-only keystream for one entry from its ciphertext and
/// its own ADLR hash, under one scheme's `mask`/`offset`.
///
/// Returns `None` — never a guess — when no constant pair reproduces the ADLR
/// (the entry carries a fixup), or when two pairs reproduce it and only one
/// carries the magic the name promises.
pub fn solve_constant_keystream(
    name: &str,
    cipher: &[u8],
    hash: u32,
    mask: u32,
    offset: u32,
) -> Option<Keystream> {
    let n = cipher.len();
    if n == 0 {
        return None;
    }
    let split = (((hash & mask).wrapping_add(offset)) as usize).min(n);
    let (cl, wl) = histogram(&cipher[..split], n, 0);
    let (cr, wr) = histogram(&cipher[split..], n, split);
    let (la, lwa) = separable(&cl, &wl);
    let (ra, rwa) = separable(&cr, &wr);

    let want_a = (hash & 0xffff) as u64;
    let want_b = (hash >> 16) as u64;
    let n_mod = (n as u64) % 65521;
    let magic = expected_magic(name);

    let mut hit: Option<Keystream> = None;
    // `key3 = ret1 & 0xff` and `decode()` forces `key3 = 1` when it comes out 0,
    // so a real cxdec keystream is never zero. Excluding 0 also keeps a PLAIN
    // archive from "recovering" itself: its ciphertext is its plaintext, so the
    // identity keystream satisfies the ADLR trivially.
    for ka in 1..256u64 {
        let a = (1 + la[ka as usize]) % 65521;
        // A single-region entry (split == len) never applies `const_b`, so every
        // `kb` would "match" and the pair would look ambiguous. In that case the
        // only real unknown is `ka`.
        let kb_range: std::ops::RangeInclusive<u64> =
            if split >= n { ka..=ka } else { 1..=255 };
        for kb in kb_range {
            // `a` first: it rejects ~255/256 of the pairs before the `b` lookup.
            if (a + ra[kb as usize]) % 65521 != want_a {
                continue;
            }
            if (n_mod + lwa[ka as usize] + rwa[kb as usize]) % 65521 != want_b {
                continue;
            }
            let ks = Keystream { split, const_a: ka as u8, const_b: kb as u8 };
            if magic_matches(magic, cipher, &ks) {
                return Some(ks);
            }
            if hit.is_some() {
                // Two oracle matches and neither carries the magic: ambiguous,
                // so refuse rather than pick one.
                return None;
            }
            hit = Some(ks);
        }
    }
    hit
}

/// Every scheme whose constants can be tried with no script at all. The
/// tjs-derived constants are unavailable here by definition — that file is what
/// is missing — so this is the template + canonical orderings crossed with the
/// known-game table.
fn candidate_specs() -> Vec<SchemeSpec> {
    let mut v = vec![FENG_TEMPLATE, DEFAULT_ORDERS];
    v.extend(SCHEME_TABLE);
    v
}

/// The entries a recovery probe should look at: the largest few whose unpacked
/// size is inside `[min, max]`, so the read stays bounded and never drags a
/// multi-GB asset in.
fn probe_entries(archive: &str, min: u64, max: u64, take: usize) -> Vec<(String, u32, Vec<u8>)> {
    let mut arch = match Xp3Archive::open(Path::new(archive)) {
        Ok(a) => a,
        Err(_) => return Vec::new(),
    };
    let mut idx: Vec<usize> = (0..arch.entries.len())
        .filter(|&i| (min..=max).contains(&arch.entries[i].unpacked_size))
        .collect();
    idx.sort_by_key(|&i| std::cmp::Reverse(arch.entries[i].unpacked_size));
    let mut noop = |_h: u32, _o: u64, _d: &mut [u8]| Ok(());
    let mut out = Vec::new();
    for i in idx.into_iter().take(take) {
        let (name, hash) = (arch.entries[i].name.clone(), arch.entries[i].hash);
        if let Ok(c) = arch.read_entry(i, &mut noop) {
            out.push((name, hash, c));
        }
    }
    out
}

/// Picks the scheme whose constants let the most probe entries verify.
///
/// Only entries past `base_offset` can tell schemes apart, so those are tried
/// first; a tiny archive with nothing that long falls back to any entry at all,
/// where every scheme is equivalent and the choice is cosmetic. A probe hit
/// needs no magic, so a misleading extension cannot veto a real scheme.
///
/// The probe is on the UI's content-probe path, so it stays cheap: at most two
/// entries of at most 1 MB each.
fn detect_scheme(archive: &str) -> Option<&'static str> {
    const MB: u64 = 1024 * 1024;
    for (min, max) in [(4096u64, MB), (1, MB)] {
        let probes = probe_entries(archive, min, max, 2);
        if probes.is_empty() {
            continue;
        }
        let mut best: Option<(&'static str, usize)> = None;
        for spec in candidate_specs() {
            let hits = probes
                .iter()
                .filter(|(_, hash, c)| {
                    solve_constant_keystream("", c, *hash, spec.mask, spec.offset).is_some()
                })
                .count();
            if hits > 0 && best.map_or(true, |(_, b)| hits > b) {
                best = Some((spec.name, hits));
            }
            if best.map_or(false, |(_, b)| b >= probes.len()) {
                break;
            }
        }
        if let Some((name, _)) = best {
            return Some(name);
        }
    }
    None
}

/// Cheap answer for the content probe: the scheme name when a lone archive can
/// be recovered at all, else `None`. Reads only a handful of entries.
pub fn probe_recovery(archive: &str) -> Option<&'static str> {
    detect_scheme(archive)
}

/// What a pure-archive recovery pass managed to decrypt.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct RecoverReport {
    /// The scheme whose constants verified, when one did.
    pub scheme: Option<&'static str>,
    /// Entries matched by the selection.
    pub total: u32,
    /// Entries whose constant-only keystream was recovered and written.
    pub solved: u32,
    /// Entries refused because they carry a fixup the solver does not yet handle.
    pub unresolved: u32,
    pub solved_bytes: u64,
    pub total_bytes: u64,
}

/// Decrypts as much of a lone cxdec archive as the constant-only tier can,
/// writing the solved entries under `output` and reporting the rest. Nothing is
/// written for an entry the solver refuses, so a partial output is never mixed
/// with garbage.
pub fn extract_recovered(
    archive: &str,
    output: &str,
    selected: Option<&str>,
) -> Result<RecoverReport, String> {
    let mut arch = Xp3Archive::open(Path::new(archive)).map_err(|e| format!("XP3: {e}"))?;

    // The detected scheme goes first; the rest stay as a per-entry fallback for
    // the (rare) archive whose probe entries were all too short to discriminate.
    let detected = detect_scheme(archive);
    let mut specs: Vec<SchemeSpec> = Vec::new();
    if let Some(name) = detected {
        if let Some(s) = candidate_specs().into_iter().find(|s| s.name == name) {
            specs.push(s);
        }
    }
    for s in candidate_specs() {
        if !specs.iter().any(|x| x.name == s.name) {
            specs.push(s);
        }
    }

    let sel_set: Option<std::collections::HashSet<String>> = selected.map(|s| {
        s.lines().filter(|l| !l.is_empty()).map(str::to_string).collect()
    });
    let matches = |raw: &str| -> bool {
        match &sel_set {
            None => true,
            Some(sel) => {
                let norm = raw.replace('\\', "/");
                sel.contains(&norm)
                    || sel.iter().any(|d| {
                        let dd = d.strip_suffix('/').unwrap_or(d);
                        norm.starts_with(&format!("{dd}/"))
                    })
            }
        }
    };

    let mut report = RecoverReport { scheme: detected, ..Default::default() };
    report.total = arch.entries.iter().filter(|e| matches(&e.name)).count() as u32;
    report.total_bytes = arch
        .entries
        .iter()
        .filter(|e| matches(&e.name))
        .map(|e| e.unpacked_size)
        .sum();
    extract_progress::reset(report.total_bytes);

    let mut noop = |_h: u32, _o: u64, _d: &mut [u8]| Ok(());
    let mut dests = DestAllocator::new();
    for i in 0..arch.entries.len() {
        if extract_progress::cancelled() {
            return Err("cancelled".to_string());
        }
        let (name, unpacked, hash) = {
            let e = &arch.entries[i];
            if !matches(&e.name) {
                continue;
            }
            (e.name.clone(), e.unpacked_size, e.hash)
        };
        extract_progress::set_name(&name);
        extract_progress::set_file(unpacked);

        // `read_entry` with a no-op cipher inflates the segment and hands us the
        // still-encrypted bytes — exactly the ciphertext the keystream applies to.
        let mut data = match arch.read_entry(i, &mut noop) {
            Ok(c) => c,
            Err(_) => {
                report.unresolved += 1;
                continue;
            }
        };
        let ks = specs
            .iter()
            .find_map(|s| solve_constant_keystream(&name, &data, hash, s.mask, s.offset));
        let ks = match ks {
            Some(k) => k,
            None => {
                report.unresolved += 1;
                continue;
            }
        };
        ks.apply(&mut data);

        let dest = match safe_join(output, &name) {
            Ok(d) => dests.allocate(d),
            Err(_) => {
                report.unresolved += 1;
                continue;
            }
        };
        if let Some(p) = dest.parent() {
            let _ = fs::create_dir_all(p);
        }
        let written = (|| -> io::Result<()> {
            let mut f = fs::File::create(&dest)?;
            use std::io::Write as _;
            f.write_all(&data)?;
            f.flush()
        })();
        if written.is_err() {
            let _ = fs::remove_file(&dest);
            report.unresolved += 1;
            continue;
        }
        report.solved += 1;
        report.solved_bytes += data.len() as u64;
        extract_progress::add_bytes(data.len() as u64);
    }
    Ok(report)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::CxEncryption;
    use cxdec_tools::crypto::hxv4_shellcode::{CxScheme, CONTROL_BLOCK_SIGNATURE};
    use cxdec_tools::r#struct::xp3::MAGIC;

    fn tmp(tag: &str) -> std::path::PathBuf {
        std::env::temp_dir().join(format!("uu_recover_{}_{}", std::process::id(), tag))
    }

    /// A deterministic 4096-byte control block, real signature included.
    fn control_block() -> Vec<u32> {
        let mut bytes = CONTROL_BLOCK_SIGNATURE.to_vec();
        bytes.resize(4096, 0);
        for (i, b) in bytes.iter_mut().enumerate().skip(CONTROL_BLOCK_SIGNATURE.len()) {
            *b = (i % 251) as u8;
        }
        bytes
            .chunks_exact(4)
            .map(|q| u32::from_le_bytes([q[0], q[1], q[2], q[3]]))
            .collect()
    }

    /// Encrypts `plain` under `spec`, mirroring the real VM-load inversion.
    fn encrypt(spec: &SchemeSpec, words: &[u32], plain: &[u8]) -> (u32, Vec<u8>) {
        let vm_words: Vec<u32> = words.iter().map(|w| !w).collect();
        let mut scheme = CxScheme::base(spec.mask, spec.offset, vm_words);
        scheme.prolog_order = spec.prolog_order;
        scheme.even_branch_order = spec.even_branch_order;
        scheme.odd_branch_order = spec.odd_branch_order;
        let mut cx = CxEncryption::new(scheme, None).unwrap();
        let hash = adler32(plain);
        let mut payload = plain.to_vec();
        cx.encrypt(hash, 0, &mut payload).unwrap();
        (hash, payload)
    }

    /// The keystream is constant over each region exactly when the entry has no
    /// fixup — the only case the solver claims to handle.
    fn keystream_is_constant(plain: &[u8], cipher: &[u8], hash: u32, mask: u32, offset: u32) -> bool {
        let split = (((hash & mask).wrapping_add(offset)) as usize).min(plain.len());
        let k: Vec<u8> = plain.iter().zip(cipher).map(|(a, b)| a ^ b).collect();
        let ka = k[0];
        let kb = if split < k.len() { k[split] } else { ka };
        k[..split].iter().all(|&x| x == ka) && k[split..].iter().all(|&x| x == kb)
    }

    /// Searches for a plaintext whose keystream has no fixup, so the test is
    /// deterministic without depending on any particular key.
    fn clean_entry(spec: &SchemeSpec, words: &[u32], len: usize) -> (u32, Vec<u8>, Vec<u8>) {
        for seed in 0..100_000u32 {
            let mut p = vec![0u8; len];
            for (i, b) in p.iter_mut().enumerate() {
                *b = seed.wrapping_mul(2_654_435_761).wrapping_add(i as u32) as u8;
            }
            p[..8].copy_from_slice(b"\x89PNG\r\n\x1a\n");
            let (hash, c) = encrypt(spec, words, &p);
            if keystream_is_constant(&p, &c, hash, spec.mask, spec.offset) {
                return (hash, p, c);
            }
        }
        panic!("no fixup-free plaintext found");
    }

    fn chunk(sig: &[u8; 4], payload: &[u8]) -> Vec<u8> {
        let mut v = sig.to_vec();
        v.extend_from_slice(&(payload.len() as i64).to_le_bytes());
        v.extend_from_slice(payload);
        v
    }

    /// A minimal uncompressed-index XP3 whose payloads are already encrypted.
    fn build_xp3(entries: &[(&str, u32, Vec<u8>)]) -> Vec<u8> {
        let mut data = Vec::new();
        let mut index = Vec::new();
        let data_base: i64 = (MAGIC.len() + 8) as i64;
        for (name, hash, content) in entries {
            let mut file_chunk = Vec::new();
            let mut info = Vec::new();
            info.extend_from_slice(&1u32.to_le_bytes());
            info.extend_from_slice(&(content.len() as i64).to_le_bytes());
            info.extend_from_slice(&(content.len() as i64).to_le_bytes());
            let units: Vec<u16> = name.encode_utf16().collect();
            info.extend_from_slice(&(units.len() as i16).to_le_bytes());
            for u in units {
                info.extend_from_slice(&u.to_le_bytes());
            }
            file_chunk.extend(chunk(b"info", &info));
            let mut segm = Vec::new();
            segm.extend_from_slice(&0i32.to_le_bytes());
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
        out.push(0u8);
        out.extend_from_slice(&(index.len() as i64).to_le_bytes());
        out.extend_from_slice(&index);
        out[offset_pos..offset_pos + 8].copy_from_slice(&index_offset.to_le_bytes());
        out
    }

    #[test]
    fn constant_keystream_is_recovered_from_the_adlr_oracle() {
        let words = control_block();
        let spec = FENG_TEMPLATE;
        // 3000 bytes: past the feng `base_offset` for most hashes, so both
        // regions are exercised.
        let (hash, plain, cipher) = clean_entry(&spec, &words, 3000);
        let ks = solve_constant_keystream("bg.png", &cipher, hash, spec.mask, spec.offset)
            .expect("a fixup-free entry must be recoverable");
        let mut got = cipher.clone();
        ks.apply(&mut got);
        assert_eq!(got, plain);
        assert_eq!(adler32(&got), hash);
    }

    #[test]
    fn small_entry_is_a_single_region() {
        let words = control_block();
        let spec = FENG_TEMPLATE;
        // 64 bytes is below every scheme's offset, so region B never exists.
        let (hash, plain, cipher) = clean_entry(&spec, &words, 64);
        let ks = solve_constant_keystream("bg.png", &cipher, hash, spec.mask, spec.offset).unwrap();
        assert_eq!(ks.split, cipher.len());
        assert_eq!(ks.const_a, ks.const_b);
        let mut got = cipher.clone();
        ks.apply(&mut got);
        assert_eq!(got, plain);
    }

    #[test]
    fn an_entry_with_a_fixup_is_refused_not_guessed() {
        let words = control_block();
        let spec = FENG_TEMPLATE;
        // A long entry always lands both fixups in region B; search for one that
        // really does rather than assuming.
        let mut found = false;
        for seed in 0..64u32 {
            let len = 200_000usize;
            let mut p = vec![0u8; len];
            for (i, b) in p.iter_mut().enumerate() {
                *b = seed.wrapping_mul(2_654_435_761).wrapping_add(i as u32) as u8;
            }
            p[..8].copy_from_slice(b"\x89PNG\r\n\x1a\n");
            let (hash, c) = encrypt(&spec, &words, &p);
            if keystream_is_constant(&p, &c, hash, spec.mask, spec.offset) {
                continue;
            }
            found = true;
            assert_eq!(
                solve_constant_keystream("big.png", &c, hash, spec.mask, spec.offset),
                None,
                "an entry with a fixup must be refused"
            );
            break;
        }
        assert!(found, "expected at least one entry with a fixup");
    }

    #[test]
    fn a_wrong_hash_is_not_accepted() {
        let words = control_block();
        let spec = FENG_TEMPLATE;
        let (hash, _plain, cipher) = clean_entry(&spec, &words, 3000);
        // Flipping one bit of the oracle must stop the search finding anything
        // (the constants that would satisfy it are not the real ones).
        assert_eq!(
            solve_constant_keystream("bg.png", &cipher, hash ^ 0x0001_0000, spec.mask, spec.offset),
            None
        );
    }

    #[test]
    fn extract_recovered_writes_the_solvable_entries_and_reports_the_rest() {
        let dir = tmp("extract");
        fs::create_dir_all(&dir).unwrap();
        let words = control_block();
        let spec = FENG_TEMPLATE;

        let (hash_a, plain_a, enc_a) = clean_entry(&spec, &words, 3000);
        // A long entry with fixups, which the constant-only tier must refuse.
        let mut hash_b = 0u32;
        let mut enc_b = Vec::new();
        for seed in 0..64u32 {
            let len = 200_000usize;
            let mut p = vec![0u8; len];
            for (i, b) in p.iter_mut().enumerate() {
                *b = seed.wrapping_mul(4_054_037_777).wrapping_add(i as u32) as u8;
            }
            let (h, c) = encrypt(&spec, &words, &p);
            if !keystream_is_constant(&p, &c, h, spec.mask, spec.offset) {
                hash_b = h;
                enc_b = c;
                break;
            }
        }
        assert!(hash_b != 0, "needed a fixup entry for the test");

        let xp3 = dir.join("data.xp3");
        fs::write(&xp3, build_xp3(&[
            ("bg/logo.png", hash_a, enc_a),
            ("bg/photo.png", hash_b, enc_b),
        ])).unwrap();

        // No control block anywhere: this is the lone-archive case.
        assert_eq!(probe_recovery(xp3.to_str().unwrap()), Some(FENG_TEMPLATE.name));

        let out = dir.join("out");
        fs::create_dir_all(&out).unwrap();
        let rep = extract_recovered(xp3.to_str().unwrap(), out.to_str().unwrap(), None).unwrap();
        assert_eq!((rep.total, rep.solved, rep.unresolved), (2, 1, 1));
        assert_eq!(fs::read(out.join("bg/logo.png")).unwrap(), plain_a);
        assert!(!out.join("bg/photo.png").exists(), "refused entry must not be written");
        fs::remove_dir_all(&dir).ok();
    }
}
