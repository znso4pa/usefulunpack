//! Minimal PE resource reader — just enough to find the three resource blobs
//! CatSystem2's `.int` archives key themselves from.
//!
//! The archive's index is encrypted with a key derived from resources *inside
//! the game's executable* (`key_code`, `v_code`, `v_code2`), so an `.int` cannot
//! be opened without the `.exe` that shipped next to it. That is a property of
//! the format, not a shortcut here — every third-party tool does the same.
//!
//! Only the resource directory is parsed: no imports, no relocations, nothing
//! that could execute or trust executable code. Every walk is bounded (depth,
//! entry count, name length) so a crafted "exe" cannot make us spin or allocate.

use std::collections::BTreeMap;

const MAX_DEPTH: usize = 4;
const MAX_LEAVES: usize = 4096;
const MAX_NAME: usize = 1024;
/// The executable is read whole: the resource directory and its payloads are
/// scattered across sections, so a precise range read would need a sparse file
/// view for little gain. Real engine executables for this format are a few to
/// a few tens of megabytes, so the cap sits well above them — its only job is
/// to make a mis-selected giant file fail with a message instead of allocating
/// hundreds of megabytes on a phone.
const MAX_FILE: u64 = 64 * 1024 * 1024;

struct Section {
    va: u32,
    vsize: u32,
    raw: u32,
    raw_size: u32,
}

struct Pe<'a> {
    d: &'a [u8],
    sections: Vec<Section>,
}

impl<'a> Pe<'a> {
    fn u16(&self, off: usize) -> Option<u16> {
        self.d.get(off..off + 2).map(|s| u16::from_le_bytes([s[0], s[1]]))
    }

    fn u32(&self, off: usize) -> Option<u32> {
        self.d.get(off..off + 4).map(|s| u32::from_le_bytes([s[0], s[1], s[2], s[3]]))
    }

    fn rva_to_off(&self, rva: u32) -> Option<usize> {
        for s in &self.sections {
            let size = s.vsize.max(s.raw_size);
            if rva >= s.va && rva < s.va.checked_add(size)? {
                return Some((s.raw as usize) + (rva - s.va) as usize);
            }
        }
        None
    }

    /// Parses the headers. Returns `None` for anything that is not a PE image.
    fn parse(d: &'a [u8]) -> Option<Pe<'a>> {
        if d.len() < 0x40 || &d[0..2] != b"MZ" {
            return None;
        }
        let pe_off = u32::from_le_bytes(d.get(0x3C..0x40)?.try_into().ok()?) as usize;
        if d.get(pe_off..pe_off + 4)? != b"PE\0\0" {
            return None;
        }
        let n_sections = u16::from_le_bytes(d.get(pe_off + 6..pe_off + 8)?.try_into().ok()?) as usize;
        let opt_size = u16::from_le_bytes(d.get(pe_off + 20..pe_off + 22)?.try_into().ok()?) as usize;
        if n_sections == 0 || n_sections > 96 {
            return None;
        }
        let opt = pe_off + 24;
        let magic = u16::from_le_bytes(d.get(opt..opt + 2)?.try_into().ok()?);
        // PE32 and PE32+ differ only in where the data directories start; the
        // directory itself is read by `resources()`, so only the magic is
        // validated here.
        if magic != 0x10B && magic != 0x20B {
            return None;
        }
        let sections_at = opt + opt_size;
        let mut sections = Vec::with_capacity(n_sections);
        for i in 0..n_sections {
            let o = sections_at + i * 40;
            let vsize = u32::from_le_bytes(d.get(o + 8..o + 12)?.try_into().ok()?);
            let va = u32::from_le_bytes(d.get(o + 12..o + 16)?.try_into().ok()?);
            let raw_size = u32::from_le_bytes(d.get(o + 16..o + 20)?.try_into().ok()?);
            let raw = u32::from_le_bytes(d.get(o + 20..o + 24)?.try_into().ok()?);
            sections.push(Section { va, vsize, raw, raw_size });
        }
        Some(Pe { d, sections })
    }

    /// Walks the resource directory, returning `path -> bytes` for every leaf.
    /// The path is `TYPE/NAME/LANG`, which is what the reference decoder matches
    /// against (case-insensitively, by substring).
    fn resources(&self) -> BTreeMap<String, Vec<u8>> {
        let mut out = BTreeMap::new();
        let Some(pe_off) = self.u32(0x3C).map(|v| v as usize) else { return out };
        if self.u16(pe_off + 20).is_none() {
            return out;
        }
        let opt = pe_off + 24;
        let magic = self.u16(opt).unwrap_or(0);
        let dd = opt + if magic == 0x10B { 96 } else if magic == 0x20B { 112 } else { return out };
        let Some(res_rva) = self.u32(dd + 2 * 8) else { return out };
        let Some(base) = self.rva_to_off(res_rva) else { return out };
        let mut leaves = 0usize;
        self.walk(base, base, 0, &mut Vec::new(), &mut out, &mut leaves);
        out
    }

    fn walk(
        &self,
        base: usize,
        dir_off: usize,
        depth: usize,
        path: &mut Vec<String>,
        out: &mut BTreeMap<String, Vec<u8>>,
        leaves: &mut usize,
    ) {
        if depth > MAX_DEPTH || *leaves >= MAX_LEAVES {
            return;
        }
        let Some(named) = self.u16(dir_off + 12) else { return };
        let Some(ids) = self.u16(dir_off + 14) else { return };
        let count = named as usize + ids as usize;
        if count > 4096 {
            return;
        }
        for i in 0..count {
            if *leaves >= MAX_LEAVES {
                return;
            }
            let entry = dir_off + 16 + i * 8;
            let (Some(name_or_id), Some(off2)) = (self.u32(entry), self.u32(entry + 4)) else { return };
            let name = if name_or_id & 0x8000_0000 != 0 {
                let no = base + (name_or_id & 0x7FFF_FFFF) as usize;
                let Some(len) = self.u16(no) else { return };
                let len = (len as usize).min(MAX_NAME);
                let mut s = String::with_capacity(len);
                for k in 0..len {
                    let Some(cp) = self.u16(no + 2 + k * 2) else { return };
                    s.push(char::from_u32(cp as u32).unwrap_or('\u{FFFD}'));
                }
                s
            } else {
                format!("#{name_or_id}")
            };
            path.push(name);
            if off2 & 0x8000_0000 != 0 {
                self.walk(base, base + (off2 & 0x7FFF_FFFF) as usize, depth + 1, path, out, leaves);
            } else {
                let de = base + off2 as usize;
                if let (Some(rva), Some(size)) = (self.u32(de), self.u32(de + 4)) {
                    if let Some(off) = self.rva_to_off(rva) {
                        let end = off.saturating_add(size as usize).min(self.d.len());
                        if off <= end {
                            *leaves += 1;
                            out.insert(path.join("/"), self.d[off..end].to_vec());
                        }
                    }
                }
            }
            path.pop();
        }
    }
}

/// `path -> data` for every resource in [data], or an empty map when it is not
/// a PE image.
pub fn resource_map(data: &[u8]) -> BTreeMap<String, Vec<u8>> {
    match Pe::parse(data) {
        Some(pe) => pe.resources(),
        None => BTreeMap::new(),
    }
}

/// Reads [path] (bounded) and returns its resource map.
pub fn resources_of(path: &std::path::Path) -> Result<BTreeMap<String, Vec<u8>>, String> {
    let len = std::fs::metadata(path).map_err(|e| format!("{e}"))?.len();
    if len > MAX_FILE {
        return Err(format!("INT: exe is too large to scan for keys ({len} bytes, limit {MAX_FILE})"));
    }
    let data = std::fs::read(path).map_err(|e| format!("{e}"))?;
    Ok(resource_map(&data))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The executable fixture is a real game file and is **not** distributed in
    /// git (see `testdata/README.md`). `None` means it is absent — the caller
    /// skips with a note, so a checkout without the corpus (CI) still passes.
    fn fixture() -> Option<Vec<u8>> {
        let p = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("testdata/fakegame.exe");
        if !p.is_file() {
            eprintln!("SKIP fakegame.exe: not present — the real fixture is not distributed in git (see crates/int-core/testdata/README.md)");
            return None;
        }
        Some(std::fs::read(&p).unwrap())
    }

    /// The fixture is the executable arc_unpacker ships with its `.int` sample;
    /// it carries exactly the three resources the format keys itself from.
    #[test]
    fn finds_the_three_key_resources_in_the_real_fixture() {
        let Some(data) = fixture() else { return; };
        let res = resource_map(&data);
        let keys: Vec<&str> = res.keys().map(|s| s.as_str()).collect();
        assert!(keys.iter().any(|k| k.to_lowercase().contains("key_code")), "{keys:?}");
        assert!(keys.iter().any(|k| k.to_lowercase().contains("v_code2")), "{keys:?}");
        assert!(keys.iter().any(|k| k.to_lowercase().contains("v_code") && !k.to_lowercase().contains("v_code2")), "{keys:?}");
        let find = |needle: &str| {
            res.iter().find(|(k, _)| k.to_lowercase().contains(needle)).map(|(_, v)| v.clone()).unwrap()
        };
        assert_eq!(find("key_code").len(), 9);
        assert_eq!(find("v_code2").len(), 16);
    }

    /// Malformed input must be *rejected*, never looped over or panicked on —
    /// this parser runs on whatever the user's game folder happens to contain.
    #[test]
    fn malformed_images_are_survivable() {
        // Truncated at every interesting boundary. The real fixture is optional
        // (not distributed in git); the synthetic images below are not.
        if let Some(real) = fixture() {
            for cut in [0usize, 2, 0x40, 0x80, 0x90, 0x100, 0x1000] {
                let slice = &real[..cut.min(real.len())];
                let _ = resource_map(slice); // must not panic
            }
        }
        // A PE header with an absurd section count / optional-header size.
        let mut b = vec![0u8; 0x200];
        b[0..2].copy_from_slice(b"MZ");
        b[0x3C..0x40].copy_from_slice(&0x80u32.to_le_bytes());
        b[0x80..0x84].copy_from_slice(b"PE\0\0");
        b[0x86..0x88].copy_from_slice(&0xFFFFu16.to_le_bytes()); // sections
        b[0x94..0x96].copy_from_slice(&0xFFFFu16.to_le_bytes()); // opt header size
        assert!(resource_map(&b).is_empty());

        // A resource directory whose subdirectory offset points back at itself:
        // the walk must terminate (depth cap), not recurse forever.
        let mut b = vec![0u8; 0x400];
        b[0..2].copy_from_slice(b"MZ");
        b[0x3C..0x40].copy_from_slice(&0x80u32.to_le_bytes());
        b[0x80..0x84].copy_from_slice(b"PE\0\0");
        b[0x86..0x88].copy_from_slice(&1u16.to_le_bytes());
        b[0x94..0x96].copy_from_slice(&0xE0u16.to_le_bytes());
        let opt = 0x80 + 24;
        b[opt..opt + 2].copy_from_slice(&0x10Bu16.to_le_bytes());
        let dd = opt + 96;
        b[dd + 16..dd + 20].copy_from_slice(&0x1000u32.to_le_bytes()); // resource RVA
        let sec = opt + 0xE0;
        b[sec + 8..sec + 12].copy_from_slice(&0x1000u32.to_le_bytes()); // VirtualSize
        b[sec + 12..sec + 16].copy_from_slice(&0x1000u32.to_le_bytes()); // VirtualAddress
        b[sec + 16..sec + 20].copy_from_slice(&0x200u32.to_le_bytes()); // SizeOfRawData
        b[sec + 20..sec + 24].copy_from_slice(&0x200u32.to_le_bytes()); // PointerToRawData
        // At the resource base: one named entry that points to a subdir at offset 0
        // (i.e. back at itself).
        let rb = 0x200usize;
        b[rb + 12..rb + 14].copy_from_slice(&1u16.to_le_bytes()); // named
        b[rb + 16..rb + 20].copy_from_slice(&0x8000_0010u32.to_le_bytes()); // name → 0x210
        b[rb + 20..rb + 24].copy_from_slice(&0x8000_0000u32.to_le_bytes()); // subdir → itself
        b[rb + 0x10..rb + 0x12].copy_from_slice(&0u16.to_le_bytes()); // name length 0
        let _ = resource_map(&b); // must terminate
    }

    /// Same mutation discipline as the archive fuzzer: whatever the user points
    /// us at, the PE walk returns a map (possibly empty) and never loops.
    #[test]
    fn mutated_executables_never_panic() {
        let Some(base) = fixture() else { return; };
        for i in 0..1500usize {
            let mut d = base.clone();
            let mut r = (i as u64).wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            let mut next = |n: usize| {
                r = r.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
                ((r >> 33) as usize) % n.max(1)
            };
            match i % 4 {
                0 => {
                    let at = next(d.len());
                    d[at] = d[at].wrapping_add(i as u8);
                }
                1 => d.truncate(next(d.len())),
                2 => {
                    let at = next(d.len());
                    let end = (at + 4).min(d.len());
                    let n = end - at;
                    d[at..end].copy_from_slice(&[0xFFu8; 4][..n]);
                }
                _ => {
                    let at = next(d.len().saturating_sub(4));
                    d[at..at + 4].copy_from_slice(&(i as u32).to_le_bytes());
                }
            }
            let _ = resource_map(&d);
        }
    }

    #[test]
    fn non_pe_input_is_empty_not_an_error() {
        assert!(resource_map(b"MZ not really").is_empty());
        assert!(resource_map(b"PK\x03\x04").is_empty());
        assert!(resource_map(&[]).is_empty());
    }
}
