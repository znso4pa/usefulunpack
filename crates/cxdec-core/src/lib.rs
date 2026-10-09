//! Classic cxdec (Kirikiri XP3 content filter) extraction.
//!
//! Cipher core (xcode VM), TPM control-block reader and XP3 parsing are
//! vendored verbatim from Cxdec_Tools (MIT, © 2026 bfloat16,
//! https://github.com/1F1E33-float32/Cxdec_Tools) — see crates/vendor/cxdec-tools.
//! This crate adds what the app needs around them: the per-game scheme table
//! (mask/offset/branch orders, from arc_unpacker's plugin list + the feng
//! XP3FILTER.TJS template derivation), control-block discovery from the game
//! folder (`.tpm`/`.dat` binary scan or `xp3filter.tjs` array parse) and the
//! single-archive extract/list entry points that xp3-core exposes over JNI.
//!
//! Scope is classic cxdec only: the index is plain XP3 (names visible), each
//! entry's segments decrypt transparently — no HX bootstrap / name-recovery
//! pipeline (that is Cxdec_Tools' separate `recover` flow).

use archive_common::{extract_progress, derive_dirs, json_escape, looks_decrypted, safe_join, DestAllocator};
use cxdec_tools::crypto::hxv4_shellcode::{read_control_block_from_tpm, CxScheme};
/// Re-exported so `xp3-core` (the encrypted writer) can name the cipher type
/// without depending on the vendored tool crate directly.
pub use cxdec_tools::crypto::hxv4_shellcode::CxEncryption;
/// The 24-byte marker a control block must start with. Re-exported for callers
/// that build a synthetic game folder (tests) or scan for one themselves.
pub use cxdec_tools::crypto::hxv4_shellcode::CONTROL_BLOCK_SIGNATURE;
use cxdec_tools::r#struct::xp3::{Xp3Archive, Xp3Cipher, Xp3Entry};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

mod recover;
pub use recover::{
    adler32, extract_recovered, probe_recovery, solve_constant_keystream, Keystream,
    RecoverReport,
};

/// Buffered whole-entry decode cap (`read_entry` materializes the entry in
/// RAM). Legit cxdec archives top out far below this; a hostile index can't
/// use the cxdec path to pin gigabytes.
const MAX_ENTRY_BYTES: u64 = 1024 * 1024 * 1024;

/// Files at most this large are scanned for the control-block signature —
/// real TPM blocks sit in KB-sized side files, never in the multi-GB archives.
const CONTROL_BLOCK_SCAN_MAX: u64 = 64 * 1024 * 1024;

/// How deep the control-block search descends below the game folder. Real
/// games keep the table at the root, in `plugin/`, or in `savedata/`; a
/// deeper walk would only add cost to a folder that has no block anyway.
const CONTROL_BLOCK_MAX_DEPTH: usize = 3;

/// One game's cxdec scheme: where the two-phase decrypt splits (`mask` +
/// `offset`) and how the key-derivation VM permutes its opcode cases.
#[derive(Clone, Copy)]
pub struct SchemeSpec {
    pub name: &'static str,
    pub mask: u32,
    pub offset: u32,
    pub prolog_order: [u8; 3],
    pub even_branch_order: [u8; 8],
    pub odd_branch_order: [u8; 6],
}

/// feng's XP3FILTER.TJS template (ちいさな彼女の小夜曲): the switch-case →
/// opcode mapping read off the script's VM, with mask/offset parsed from the
/// same script (`bondary = (hash & 0x275) + 0x380`). Other games built on the
/// same filter SDK permute only the constants.
pub const FENG_TEMPLATE: SchemeSpec = SchemeSpec {
    name: "cxdec feng template",
    mask: 0x275,
    offset: 0x380,
    prolog_order: [2, 1, 0],
    even_branch_order: [2, 6, 0, 4, 7, 3, 1, 5],
    odd_branch_order: [3, 0, 5, 4, 2, 1],
};

/// Canonical identity ordering (Fate/hollow ataraxia).
pub const DEFAULT_ORDERS: SchemeSpec = SchemeSpec {
    name: "cxdec default orders",
    mask: 0x143,
    offset: 0x787,
    prolog_order: [0, 1, 2],
    even_branch_order: [0, 1, 2, 3, 4, 5, 6, 7],
    odd_branch_order: [0, 1, 2, 3, 4, 5],
};

/// Per-game schemes from arc_unpacker's plugin table (key1 = mask,
/// key2 = offset; order arrays map case numbers to canonical opcodes).
pub const SCHEME_TABLE: [SchemeSpec; 8] = [
    SchemeSpec {
        name: "cxdec comyu",
        mask: 0x1A3,
        offset: 0x0B6,
        prolog_order: [0, 1, 2],
        even_branch_order: [0, 7, 5, 6, 3, 1, 4, 2],
        odd_branch_order: [4, 3, 2, 1, 5, 0],
    },
    SchemeSpec {
        name: "cxdec mahoyoru",
        mask: 0x22A,
        offset: 0x2A2,
        prolog_order: [1, 0, 2],
        even_branch_order: [7, 6, 5, 1, 0, 3, 4, 2],
        odd_branch_order: [3, 2, 1, 4, 5, 0],
    },
    SchemeSpec {
        name: "cxdec natsuzora",
        mask: 0x2F5,
        offset: 0x6F0,
        prolog_order: [2, 0, 1],
        even_branch_order: [7, 2, 3, 6, 1, 0, 5, 4],
        odd_branch_order: [2, 3, 4, 0, 1, 5],
    },
    SchemeSpec {
        name: "cxdec tenshin",
        mask: 0x167,
        offset: 0x498,
        prolog_order: [1, 0, 2],
        even_branch_order: [4, 2, 3, 5, 6, 1, 7, 0],
        odd_branch_order: [1, 0, 5, 4, 3, 2],
    },
    SchemeSpec {
        name: "cxdec dracuriot",
        mask: 0x2F0,
        offset: 0x418,
        prolog_order: [2, 0, 1],
        even_branch_order: [5, 3, 0, 2, 1, 4, 6, 7],
        odd_branch_order: [0, 3, 5, 4, 2, 1],
    },
    SchemeSpec {
        name: "cxdec lavender",
        mask: 0x181,
        offset: 0x635,
        prolog_order: [2, 1, 0],
        even_branch_order: [7, 5, 2, 3, 6, 1, 4, 0],
        odd_branch_order: [4, 0, 1, 5, 2, 3],
    },
    SchemeSpec {
        name: "cxdec karakara",
        mask: 0x190,
        offset: 0x4A7,
        prolog_order: [1, 0, 2],
        even_branch_order: [2, 0, 7, 3, 5, 1, 4, 6],
        odd_branch_order: [2, 1, 0, 5, 4, 3],
    },
    SchemeSpec {
        name: "cxdec waremete",
        mask: 0x23C,
        offset: 0x60F,
        prolog_order: [2, 0, 1],
        even_branch_order: [1, 5, 0, 3, 2, 7, 6, 4],
        odd_branch_order: [4, 5, 2, 1, 0, 3],
    },
];

struct ControlBlock {
    words: Vec<u32>,
    from_tjs: bool,
}

/// The cipher applies to every entry: cxdec games protect the whole archive
/// (krkr2 runs the extraction filter for each file, and the tool's own
/// per-entry flag comes from the INFO flags which cxdec packers don't always
/// set), so gate the default trait impl off.
struct AllEncrypted<'a>(&'a mut CxEncryption);

impl Xp3Cipher for AllEncrypted<'_> {
    fn is_encrypted(&self, _entry: &Xp3Entry) -> bool { true }
    fn decrypt(&mut self, hash: u32, offset: u64, data: &mut [u8]) -> io::Result<()> {
        self.0.decrypt(hash, offset, data)
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))
    }
}

/// Finds the 4096-byte cxdec control block anywhere in the game folder.
///
/// The block is a fixed 4096-byte table whose first 24 bytes are the ASCII
/// marker `" Encryption control block"` — long enough that a false positive is
/// not a practical concern, so the search is a signature scan rather than a
/// guess. Three carriers exist in the wild, and they are NOT equally good:
///
/// 1. `xp3filter.tjs` in the folder root — the script that builds the table at
///    load time. Preferred because it is the game's own source of truth AND it
///    also carries the scheme constants (`bondary = (hash & 0x275) + 0x380`).
/// 2. `*.tpm` / `*.dat` side files — the SDK's serialized form. The block
///    usually lives in `plugin/<name>.tpm`, i.e. one level DOWN, which is why
///    the walk recurses.
/// 3. Anything else — some builds embed the table in the game EXE or a plugin
///    DLL instead. Tried last, and only up to `CONTROL_BLOCK_SCAN_MAX`.
///
/// Recursing matters for the resource dumps this tool is aimed at: a stripped
/// folder often ships `plugin/` (and therefore the TPM) while the script, the
/// EXE, or both are gone.
fn find_control_block(game_dir: &Path) -> Result<ControlBlock, String> {
    let mut files: Vec<(u8, usize, std::path::PathBuf)> = Vec::new();
    collect_control_block_candidates(game_dir, 0, &mut files);
    files.sort_by(|a, b| (a.0, a.1, &a.2).cmp(&(b.0, b.1, &b.2)));

    for (_, _, path) in files {
        let name = path
            .file_name()
            .map(|s| s.to_string_lossy().to_lowercase())
            .unwrap_or_default();
        if name == "xp3filter.tjs" {
            // A tjs that exists but carries no parsable array must not abort the
            // search — fall through to the side files instead.
            if let Ok(text) = fs::read_to_string(&path) {
                if let Some(words) = tjs_control_block(&text) {
                    return Ok(ControlBlock { words, from_tjs: true });
                }
            }
            continue;
        }
        if fs::metadata(&path).map(|m| m.len()).unwrap_or(u64::MAX) > CONTROL_BLOCK_SCAN_MAX {
            continue;
        }
        if let Ok(words) = read_control_block_from_tpm(&path) {
            return Ok(ControlBlock { words, from_tjs: false });
        }
    }
    Err("cxdec: no xp3filter.tjs / .tpm control block found in the game folder".to_string())
}

/// Rank of a file as a control-block carrier — lower is tried first. `None`
/// excludes it outright. Ordering, not filtering, is what keeps the archives
/// themselves out of the scan: they are the payload, never the key material,
/// and a multi-GB one would otherwise dominate the walk.
fn control_block_rank(path: &Path, depth: usize) -> Option<u8> {
    let name = path
        .file_name()
        .map(|s| s.to_string_lossy().to_lowercase())
        .unwrap_or_default();
    let ext = path
        .extension()
        .map(|e| e.to_string_lossy().to_lowercase())
        .unwrap_or_default();
    if name == "xp3filter.tjs" {
        return Some(if depth == 0 { 0 } else { 1 });
    }
    if ext == "xp3" {
        return None;
    }
    if ext == "tpm" || ext == "dat" {
        return Some(2);
    }
    Some(3)
}

/// Collects `(rank, depth, path)` for every file under `dir` that could carry
/// the control block. A directory that cannot be read is skipped rather than
/// failing the whole search — one unreadable subfolder must not hide a block
/// sitting in a sibling.
fn collect_control_block_candidates(dir: &Path, depth: usize, out: &mut Vec<(u8, usize, PathBuf)>) {
    let rd = match fs::read_dir(dir) {
        Ok(rd) => rd,
        Err(_) => return,
    };
    for entry in rd.flatten() {
        let path = entry.path();
        match entry.file_type() {
            Ok(ft) if ft.is_dir() => {
                if depth < CONTROL_BLOCK_MAX_DEPTH {
                    collect_control_block_candidates(&path, depth + 1, out);
                }
            }
            Ok(ft) if ft.is_file() => {
                if let Some(rank) = control_block_rank(&path, depth) {
                    out.push((rank, depth, path));
                }
            }
            _ => {}
        }
    }
}

/// Parses the 4096-entry byte array from an xp3filter.tjs template
/// (`var tempBlock = [0x20, 0x45, …];`) into LE control-block words,
/// mirroring the script's own `| <<8 <<16 <<24` build loop.
pub fn tjs_control_block(text: &str) -> Option<Vec<u32>> {
    if let Some(anchor) = text.find("tempBlock") {
        if let Some(words) = parse_int_array(&text[anchor..]) {
            return Some(words);
        }
    }
    // Template variant with a different variable name: try every array.
    let mut search = 0;
    while let Some(open) = text[search..].find('[') {
        let abs = search + open;
        if let Some(words) = parse_int_array(&text[abs..]) {
            return Some(words);
        }
        search = abs + 1;
    }
    None
}

/// Collects ≥4096 comma-separated integers (hex or decimal) starting at the
/// first `[`, validates the control-block magic, returns 1024 LE words.
fn parse_int_array(text: &str) -> Option<Vec<u32>> {
    let open = text.find('[')?;
    let close = open + text[open..].find(']')?;
    let mut vals: Vec<u32> = Vec::with_capacity(4096);
    for tok in text[open + 1..close].split(',') {
        let t = tok.trim();
        if t.is_empty() {
            continue;
        }
        let v = if let Some(h) = t.strip_prefix("0x").or_else(|| t.strip_prefix("0X")) {
            u32::from_str_radix(h, 16).ok()?
        } else {
            t.parse::<u32>().ok()?
        };
        vals.push(v);
        if vals.len() >= 4096 {
            break;
        }
    }
    if vals.len() < 4096 {
        return None;
    }
    let mut words = Vec::with_capacity(1024);
    for quad in vals.chunks_exact(4) {
        words.push(quad[0] | (quad[1] << 8) | (quad[2] << 16) | (quad[3] << 24));
    }
    let bytes: Vec<u8> = words.iter().flat_map(|w| w.to_le_bytes()).collect();
    if !bytes.starts_with(CONTROL_BLOCK_SIGNATURE) {
        return None;
    }
    Some(words)
}

/// Reads the split point constants from the script's decode entry
/// (`bondary = (hash & 0x275) + 0x380` in the feng template).
pub fn tjs_scheme_params(text: &str) -> Option<(u32, u32)> {
    // The decode entry reads `bondary = (hash & 0x275) + 0x380` — anchor on
    // the parenthesized form so the VM's own `hash & 0x7f` doesn't match.
    let p = text.find("(hash & 0x")?;
    let rest = &text[p + "(hash & 0x".len()..];
    let mask_end = rest.find(|c: char| !c.is_ascii_hexdigit())?;
    let mask = u32::from_str_radix(&rest[..mask_end], 16).ok()?;
    let after = &rest[mask_end..];
    let q = after.find("0x")?;
    let orest = &after[q + 2..];
    let off_end = orest.find(|c: char| !c.is_ascii_hexdigit())?;
    let offset = u32::from_str_radix(&orest[..off_end], 16).ok()?;
    Some((mask, offset))
}

/// Picks the scheme whose decryption yields recognizable plaintext on the
/// archive's first entries. Candidates: tjs-derived constants first (feng
/// template ordering, then canonical), then the known-game table.
pub fn detect_cipher(
    game_dir: &Path,
    archive: &str,
) -> Result<(CxEncryption, &'static str), String> {
    let cb = find_control_block(game_dir)?;
    score_schemes(&cb, tjs_params_of(game_dir), archive)
}

/// The mask/offset the game's own `xp3filter.tjs` decode entry uses
/// (`bondary = (hash & 0x275) + 0x380` in the feng template).
fn tjs_params_of(game_dir: &Path) -> Option<(u32, u32)> {
    fs::read_to_string(game_dir.join("xp3filter.tjs"))
        .ok()
        .as_deref()
        .and_then(tjs_scheme_params)
}

/// What a scheme probe could establish about one archive. The three "no" cases
/// are kept apart on purpose: "no control block" means this is not a cxdec game
/// folder (the archive may well be plain), while "no scheme match" means the
/// folder *is* cxdec but no known scheme decrypts this archive — the caller
/// shows a different message for each.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SchemeProbe {
    Detected(&'static str),
    NoControlBlock,
    NoSchemeMatch,
}

/// Answers "which cxdec scheme protects this archive?" without extracting
/// anything. Reads the control block once and scores it against the archive's
/// first entries — the same work `detect_cipher` does, so the two can never
/// disagree about which scheme an archive uses.
pub fn probe_scheme(game_dir: &Path, archive: &str) -> SchemeProbe {
    let cb = match find_control_block(game_dir) {
        Ok(cb) => cb,
        Err(_) => return SchemeProbe::NoControlBlock,
    };
    match score_schemes(&cb, tjs_params_of(game_dir), archive) {
        Ok((_, name)) => SchemeProbe::Detected(name),
        Err(_) => SchemeProbe::NoSchemeMatch,
    }
}

/// Builds the cipher for a game folder + a scheme NAME, as reported by
/// `probe_scheme`/`detect_cipher`. For the encrypted writer, where the scheme
/// is already known from an existing encrypted archive of the same game — it
/// never guesses a scheme, and it is deterministic: the same name + folder
/// always rebuilds the same cipher the reader would have used.
///
/// Why the tjs constants matter even though detection cannot verify them:
/// `mask`/`offset` only decide the length of the leading run decoded with the
/// raw hash (`base_offset = (hash & mask) + offset`, typically under ~1.5 KB),
/// and any entry shorter than that decrypts identically under every
/// mask/offset. Detection scores 64-byte prefixes, so it pins the *branch
/// orders* and leaves mask/offset to the script — which is exactly why they
/// must come from the game's own tjs here.
pub fn cipher_by_name(game_dir: &Path, scheme_name: &str) -> Result<CxEncryption, String> {
    let cb = find_control_block(game_dir)?;
    let spec = spec_by_name(scheme_name, tjs_params_of(game_dir))
        .ok_or_else(|| format!("cxdec: unknown scheme name {scheme_name}"))?;
    cipher_for(spec, &cb.words, cb.from_tjs)
}

/// Whether the archive looks like an encrypted one whose key material is gone.
///
/// The INFO protected flag ALONE is not evidence — measured on a real
/// 294 MB / 7926-entry Kirikiri archive from a game with no filter: every
/// entry carries the flag, yet the stored segments inflate to perfectly good
/// WebP/TJS bytes. So the rule needs both halves: the packer says a filter
/// applies, AND nothing in the archive reads as content. A plain archive whose
/// first entries are recognizable stays `false`; a real cxdec archive without
/// its sidecar (inflated prefixes are uniform noise) is `true`.
///
/// `false` also covers "could not tell" — the label this feeds is a warning,
/// and a warning that fires on healthy archives is worse than none.
pub fn content_looks_encrypted(archive: &str) -> bool {
    let mut arch = match Xp3Archive::open(Path::new(archive)) {
        Ok(a) => a,
        Err(_) => return false,
    };
    if !arch.entries.iter().any(|e| e.is_encrypted) {
        return false;
    }
    // A no-op cipher: `read_entry_prefix` inflates the segment and hands the
    // bytes to the cipher, so leaving them alone is exactly the "before
    // decryption" content this test is about.
    let mut noop = |_h: u32, _o: u64, _d: &mut [u8]| Ok(());
    let probe = arch.entries.len().min(8);
    for i in 0..probe {
        match arch.read_entry_prefix(i, 64, &mut noop) {
            Ok(data) => {
                if looks_decrypted(&data) || looks_like_text(&data) {
                    return false;
                }
            }
            Err(_) => return false,
        }
    }
    true
}

/// Text-ish prefix — deliberately narrow, because the obvious tests do not
/// work at 64 bytes:
/// * "few control bytes" passes ciphertext (a short ASCII plaintext XORed with
///   a high keystream comes out all high bytes) — measured on this crate's own
///   fixtures, it said "text" for every encrypted entry;
/// * "valid Shift-JIS pairs" is nearly meaningless (random high bytes usually
///   pair up).
/// So only the two shapes a Kirikiri script really has count: a UTF-16 BOM, or
/// the BOM-less UTF-16LE fingerprint ([printable, 0x00] pairs — what krkr2's
/// .tjs/.ks files actually look like). Anything else is "not obviously text",
/// which biases the caller toward NOT warning on healthy archives.
fn looks_like_text(data: &[u8]) -> bool {
    if data.len() < 4 {
        return false;
    }
    if data.starts_with(&[0xFF, 0xFE]) || data.starts_with(&[0xFE, 0xFF]) {
        return true;
    }
    // BOM-less UTF-16LE: every other byte is NUL and its neighbour is printable.
    let pairs = data.len() / 2;
    if pairs >= 8 {
        let le = data.chunks_exact(2).filter(|p| p[1] == 0 && (0x20..0x7F).contains(&p[0])).count();
        if le * 10 >= pairs * 8 {
            return true;
        }
    }
    // Plain single-byte text (an ASCII config/script): no NULs and almost
    // entirely printable. Shift-JIS-heavy scripts fall through here, which is
    // fine — the caller probes several entries and one recognizable file is
    // enough; being generous here would only make the warning fire on healthy
    // archives.
    if data.contains(&0) {
        return false;
    }
    let printable = data
        .iter()
        .filter(|&&b| (0x20..0x7F).contains(&b) || b == b'\n' || b == b'\r' || b == b'\t')
        .count();
    printable * 10 >= data.len() * 9
}

/// Reads one named entry with an explicit cipher — the encrypted writer's
/// self-check. Names are matched as stored (the writer emits `/` separators,
/// which is also what the reader reports).
pub fn read_named_with(
    archive: &str,
    name: &str,
    cx: &mut CxEncryption,
) -> Result<Vec<u8>, String> {
    read_named_impl(archive, name, cx, None)
}

/// Same, but only the first `max_len` bytes — for entries too large to buffer
/// whole during a verification pass.
pub fn read_named_prefix_with(
    archive: &str,
    name: &str,
    max_len: usize,
    cx: &mut CxEncryption,
) -> Result<Vec<u8>, String> {
    read_named_impl(archive, name, cx, Some(max_len))
}

fn read_named_impl(
    archive: &str,
    name: &str,
    cx: &mut CxEncryption,
    max_len: Option<usize>,
) -> Result<Vec<u8>, String> {
    let mut arch = Xp3Archive::open(Path::new(archive)).map_err(|e| format!("XP3: {e}"))?;
    let idx = arch
        .entries
        .iter()
        .position(|e| e.name == name)
        .ok_or_else(|| format!("XP3: 归档内没有条目 {name}"))?;
    let mut cipher_fn = |hash: u32, offset: u64, data: &mut [u8]| {
        cx.decrypt(hash, offset, data)
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e.to_string()))
    };
    match max_len {
        Some(n) => arch.read_entry_prefix(idx, n, &mut cipher_fn),
        None => arch.read_entry(idx, &mut cipher_fn),
    }
    .map_err(|e| format!("XP3: {e}"))
}

/// The scheme a given name refers to, across the tjs/feng/default/game-table
/// universe — the inverse of the names `score_schemes` reports. The tjs
/// constants override the template ones exactly as they do in the candidate
/// list, so a tjs-derived scheme name round-trips.
fn spec_by_name(name: &str, tjs_params: Option<(u32, u32)>) -> Option<SchemeSpec> {
    if let Some((mask, offset)) = tjs_params {
        if name == FENG_TEMPLATE.name {
            return Some(SchemeSpec { mask, offset, ..FENG_TEMPLATE });
        }
        if name == DEFAULT_ORDERS.name {
            return Some(SchemeSpec { mask, offset, ..DEFAULT_ORDERS });
        }
    }
    [FENG_TEMPLATE, DEFAULT_ORDERS]
        .into_iter()
        .chain(SCHEME_TABLE)
        .find(|spec| spec.name == name)
}

/// Builds a cipher from an explicit spec + control block. Only valid where the
/// scheme is already known (see `cipher_by_name`), never for guessing.
fn cipher_for(spec: SchemeSpec, cb_words: &[u32], from_tjs: bool) -> Result<CxEncryption, String> {
    // Same inversion rule as `score_schemes`: the TJS array holds the script's
    // raw values and the VM complements at load, so it is inverted once here.
    let words: Vec<u32> = if from_tjs {
        cb_words.iter().map(|w| !w).collect()
    } else {
        cb_words.to_vec()
    };
    let mut scheme = CxScheme::base(spec.mask, spec.offset, words);
    scheme.prolog_order = spec.prolog_order;
    scheme.even_branch_order = spec.even_branch_order;
    scheme.odd_branch_order = spec.odd_branch_order;
    CxEncryption::new(scheme, None).map_err(|e| format!("cxdec: {e:?}"))
}

/// Scores every candidate scheme against the archive's first entries.
fn score_schemes(
    cb: &ControlBlock,
    tjs_params: Option<(u32, u32)>,
    archive: &str,
) -> Result<(CxEncryption, &'static str), String> {
    // The vendored VM complements each control-block word on every ECB load
    // (MovEaxIndirect), and its TPM reader pre-complements — the two cancel
    // out for TPM games. The TJS array holds the script's raw values, so the
    // words handed to the VM must be inverted once for the effective table to
    // match what the game's own filter used.
    let words = if cb.from_tjs {
        cb.words.iter().map(|w| !w).collect()
    } else {
        cb.words.clone()
    };

    let mut candidates: Vec<SchemeSpec> = Vec::new();
    if let Some((mask, offset)) = tjs_params {
        candidates.push(SchemeSpec { mask, offset, ..FENG_TEMPLATE });
        candidates.push(SchemeSpec { mask, offset, ..DEFAULT_ORDERS });
    }
    candidates.push(FENG_TEMPLATE);
    candidates.push(DEFAULT_ORDERS);
    candidates.extend(SCHEME_TABLE);

    let mut arch = Xp3Archive::open(Path::new(archive))
        .map_err(|e| format!("XP3: {e}"))?;
    let probe_count = arch.entries.len().min(8);
    let mut best: Option<(usize, &'static str, CxEncryption)> = None;
    for spec in candidates {
        let mut scheme = CxScheme::base(spec.mask, spec.offset, words.clone());
        scheme.prolog_order = spec.prolog_order;
        scheme.even_branch_order = spec.even_branch_order;
        scheme.odd_branch_order = spec.odd_branch_order;
        let mut cx = match CxEncryption::new(scheme, None) {
            Ok(c) => c,
            Err(_) => continue,
        };
        let mut hits = 0;
        for i in 0..probe_count {
            if let Ok(data) = arch.read_entry_prefix(i, 64, &mut AllEncrypted(&mut cx)) {
                if looks_decrypted(&data) {
                    hits += 1;
                }
            }
        }
        if hits > 0 && best.as_ref().map_or(true, |(score, _, _)| hits > *score) {
            best = Some((hits, spec.name, cx));
        }
        if best.as_ref().map_or(false, |(score, _, _)| *score >= probe_count) {
            break;
        }
    }
    best.map(|(_, name, cx)| (cx, name))
        .ok_or_else(|| "cxdec: no known scheme matches this archive".to_string())
}

/// Extracts one cxdec-protected XP3. `selected` is the newline-separated
/// path list used by selective extraction (exact + directory-prefix match,
/// same semantics as the plain XP3 path). Progress and cancel feed the
/// xp3-core extract store — the app polls it through the "xp3" accessors.
pub fn extract(
    game_dir: &str,
    archive: &str,
    output: &str,
    selected: Option<&str>,
) -> Result<(u32, u32), String> {
    // Lone archive: no xp3filter.tjs / .tpm / EXE carries the control block, so
    // the VM cannot be built at all. Fall back to recovering the constant-only
    // keystreams from the entries' own ADLRs, which decrypts what it can and
    // reports the rest as failures rather than erroring out on the whole file.
    if find_control_block(Path::new(game_dir)).is_err() {
        let rep = extract_recovered(archive, output, selected)?;
        return Ok((rep.solved, rep.unresolved));
    }
    let (mut cipher, _scheme) = detect_cipher(Path::new(game_dir), archive)?;
    let mut arch = Xp3Archive::open(Path::new(archive))
        .map_err(|e| format!("XP3: {e}"))?;
    let sel_set: Option<std::collections::HashSet<&str>> = selected.map(|s| {
        s.lines().filter(|l| !l.is_empty()).collect()
    });
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
    let mut dests = DestAllocator::new();
    let mut fail = 0u32;
    for i in 0..arch.entries.len() {
        if extract_progress::cancelled() {
            return Err("cancelled".to_string());
        }
        let (name, unpacked_size, packed) = {
            let entry = &arch.entries[i];
            if !matches(&entry.name) {
                continue;
            }
            (
                entry.name.clone(),
                entry.unpacked_size,
                entry.segments.iter().map(|s| s.packed_size).sum::<u64>(),
            )
        };
        extract_progress::set_name(&name);
        extract_progress::set_file(unpacked_size);
        if unpacked_size > MAX_ENTRY_BYTES || packed > MAX_ENTRY_BYTES {
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
        // The entry is decoded fully in RAM before any file is created, so a
        // decode failure leaves nothing behind; only write failures can leave
        // a partial file, and those are removed below.
        let data = match arch.read_entry(i, &mut cipher) {
            Ok(d) => d,
            Err(_) => {
                fail += 1;
                continue;
            }
        };
        let written = (|| -> io::Result<()> {
            let mut f = fs::File::create(&dest)?;
            use std::io::Write as _;
            f.write_all(&data)?;
            f.flush()
        })();
        if let Err(_) = written {
            let _ = fs::remove_file(&dest);
            fail += 1;
            continue;
        }
        extract_progress::add_bytes(data.len() as u64);
        if data.len() as u64 != unpacked_size {
            extract_progress::calibrate_file(data.len() as u64);
            let delta = (data.len() as i128 - unpacked_size as i128)
                .clamp(i64::MIN as i128, i64::MAX as i128);
            extract_progress::adjust_total(delta as i64);
        }
    }
    Ok((total, fail))
}

/// Lists one cxdec-protected XP3. The index of a classic cxdec archive is
/// plain XP3, so this is identical to the plain listing — it exists so the
/// caller can route uniformly and so a wrong guess fails cleanly here.
pub fn list(archive: &str) -> Result<String, String> {
    let arch = Xp3Archive::open(Path::new(archive)).map_err(|e| format!("XP3: {e}"))?;
    let raw_names: Vec<&str> = arch.entries.iter().map(|e| e.name.as_str()).collect();
    let normalized: Vec<String> = raw_names.iter().map(|n| n.replace('\\', "/")).collect();
    let norm_refs: Vec<&str> = normalized.iter().map(|s| s.as_str()).collect();
    let dirs = derive_dirs(&norm_refs);
    let mut all: Vec<(String, u64, bool)> = Vec::new();
    for d in &dirs {
        all.push((d.clone(), 0, true));
    }
    for e in arch.entries.iter() {
        all.push((e.name.replace('\\', "/"), e.unpacked_size, false));
    }
    all.sort_by(|a, b| a.0.cmp(&b.0));
    let entries: Vec<String> = all.iter().map(|(n, s, d)| {
        let sz = if *d { 0_u64 } else { *s };
        format!(r#"{{"n":"{}","s":{},"d":{},"e":false}}"#, json_escape(n), sz, d)
    }).collect();
    Ok(format!("[{}]", entries.join(",")))
}

#[cfg(test)]
mod tests {
    use super::*;
    use cxdec_tools::r#struct::xp3::MAGIC;

    fn tmp(tag: &str) -> std::path::PathBuf {
        std::env::temp_dir().join(format!("uu_cxdec_{}_{}", std::process::id(), tag))
    }

    /// Synthetic 4096-byte control block starting with the real signature.
    fn fake_control_block() -> Vec<u32> {
        let mut bytes = CONTROL_BLOCK_SIGNATURE.to_vec();
        bytes.resize(4096, 0);
        for (i, b) in bytes.iter_mut().enumerate().skip(CONTROL_BLOCK_SIGNATURE.len()) {
            *b = (i % 251) as u8;
        }
        bytes.chunks_exact(4)
            .map(|q| u32::from_le_bytes([q[0], q[1], q[2], q[3]]))
            .collect()
    }

    /// An xp3filter.tjs in the feng template: hex byte array + bondary line.
    fn make_tjs(words: &[u32], mask: u32, offset: u32) -> String {
        let mut bytes = Vec::new();
        for w in words {
            bytes.extend_from_slice(&w.to_le_bytes());
        }
        let arr: Vec<String> = bytes.iter().map(|b| format!("0x{b:02X}")).collect();
        format!(
            "@set(_DEBUG=0)\n\nclass cxdec {{\n    var EncryptionControlBlock;\n\
             function cxdec() {{\n        EncryptionControlBlock = [];\n\
             var tempBlock = [{}];\n    }}\n\
             function cxdec_decode(hash, offset, buf, len) {{\n\
             var bondary = (hash & 0x{mask:X}) + 0x{offset:X};\n    }}\n}}\n",
            arr.join(", "),
        )
    }

    fn chunk(sig: &[u8; 4], payload: &[u8]) -> Vec<u8> {
        let mut v = sig.to_vec();
        v.extend_from_slice(&(payload.len() as i64).to_le_bytes());
        v.extend_from_slice(payload);
        v
    }

    /// Builds a minimal uncompressed-index XP3 whose segment payloads are the
    /// given (already-encrypted) contents, ADLR checksums included.
    fn build_xp3(entries: &[(&str, u32, Vec<u8>)]) -> Vec<u8> {
        let mut data = Vec::new();
        let mut index = Vec::new();
        // Segment offsets are absolute (base_offset = 0): header is
        // 11 magic bytes + 8 index-offset bytes.
        let data_base: i64 = (MAGIC.len() + 8) as i64;
        for (name, hash, content) in entries {
            let mut file_chunk = Vec::new();
            let mut info = Vec::new();
            info.extend_from_slice(&1u32.to_le_bytes()); // protected flag
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

    fn png_plaintext(n: usize) -> Vec<u8> {
        let mut v = b"\x89PNG\r\n\x1a\n".to_vec();
        v.resize(n, 0xAB);
        v
    }

    /// Encrypts `plaintext` under `spec` + `words` and returns (hash, payload).
    fn encrypt_entry(spec: &SchemeSpec, words: &[u32], plaintext: &[u8]) -> (u32, Vec<u8>) {
        // Mirror the real flow: the VM complements at load, so hand it the
        // inverted words for its effective table to equal the raw array.
        let vm_words: Vec<u32> = words.iter().map(|w| !w).collect();
        let mut scheme = CxScheme::base(spec.mask, spec.offset, vm_words);
        scheme.prolog_order = spec.prolog_order;
        scheme.even_branch_order = spec.even_branch_order;
        scheme.odd_branch_order = spec.odd_branch_order;
        let mut cx = CxEncryption::new(scheme, None).unwrap();
        let hash = adler32(plaintext);
        let mut payload = plaintext.to_vec();
        cx.encrypt(hash, 0, &mut payload).unwrap();
        (hash, payload)
    }

    #[test]
    fn tjs_control_block_and_params_parse() {
        let words = fake_control_block();
        let tjs = make_tjs(&words, 0x275, 0x380);
        let parsed = tjs_control_block(&tjs).expect("array must parse");
        assert_eq!(parsed.len(), 1024);
        assert_eq!(parsed, words);
        assert_eq!(tjs_scheme_params(&tjs), Some((0x275, 0x380)));
        assert_eq!(tjs_scheme_params("no constants here"), None);
    }

    #[test]
    fn tjs_block_requires_signature() {
        // A 4096-int array NOT starting with the control-block magic must be
        // rejected — that is what stops random TJS arrays being read as keys.
        let mut bytes = vec![1u8, 2, 3, 4];
        bytes.resize(4096, 0);
        let words: Vec<u32> = bytes.chunks_exact(4)
            .map(|q| u32::from_le_bytes([q[0], q[1], q[2], q[3]]))
            .collect();
        assert_eq!(tjs_control_block(&make_tjs(&words, 0x275, 0x380)), None);
    }

    #[test]
    fn feng_scheme_round_trip_via_extract() {
        let dir = tmp("roundtrip");
        fs::create_dir_all(&dir).unwrap();
        let words = fake_control_block();
        fs::write(dir.join("xp3filter.tjs"), make_tjs(&words, 0x275, 0x380)).unwrap();

        let plain_a = png_plaintext(4096);
        let plain_b = b"TLG6.0\x00some image data".to_vec();
        let (hash_a, enc_a) = encrypt_entry(&FENG_TEMPLATE, &words, &plain_a);
        let (hash_b, enc_b) = encrypt_entry(&FENG_TEMPLATE, &words, &plain_b);
        let xp3 = dir.join("data.xp3");
        fs::write(&xp3, build_xp3(&[
            ("bg/bg01.png", hash_a, enc_a),
            ("script/init.tjs", hash_b, enc_b),
        ])).unwrap();

        // Detection must settle on the feng template ordering.
        let (_cx, scheme_name) =
            detect_cipher(&dir, xp3.to_str().unwrap()).expect("scheme must be found");
        assert!(scheme_name.contains("feng"), "got {scheme_name}");

        let out = dir.join("out");
        fs::create_dir_all(&out).unwrap();
        let (done, fail) = extract(
            dir.to_str().unwrap(), xp3.to_str().unwrap(), out.to_str().unwrap(), None,
        ).unwrap();
        assert_eq!((done, fail), (2, 0));
        assert_eq!(fs::read(out.join("bg/bg01.png")).unwrap(), plain_a);
        assert_eq!(fs::read(out.join("script/init.tjs")).unwrap(), plain_b);

        // Selective extraction: exact name + directory prefix.
        let out2 = dir.join("out2");
        fs::create_dir_all(&out2).unwrap();
        let (done, fail) = extract(
            dir.to_str().unwrap(), xp3.to_str().unwrap(), out2.to_str().unwrap(),
            Some("script/\n"),
        ).unwrap();
        assert_eq!((done, fail), (1, 0));
        assert!(!out2.join("bg").exists());
        assert_eq!(fs::read(out2.join("script/init.tjs")).unwrap(), plain_b);

        // Listing sees the plain index with real names.
        let list = list(xp3.to_str().unwrap()).unwrap();
        assert!(list.contains("bg/bg01.png"), "got {list}");
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn wrong_scheme_is_rejected_not_garbled() {
        let dir = tmp("wrongscheme");
        fs::create_dir_all(&dir).unwrap();
        let words = fake_control_block();
        fs::write(dir.join("xp3filter.tjs"), make_tjs(&words, 0x275, 0x380)).unwrap();

        // Encrypted with an ordering no candidate carries (identity with a
        // flipped branch table and unrelated mask/offset) — none of the tried
        // candidates may produce recognizable plaintext.
        let plain = png_plaintext(4096);
        let oddball = SchemeSpec {
            name: "cxdec not-in-table",
            mask: 0x111,
            offset: 0x222,
            prolog_order: [2, 0, 1],
            even_branch_order: [7, 6, 5, 4, 3, 2, 1, 0],
            odd_branch_order: [5, 4, 3, 2, 1, 0],
        };
        let (hash, enc) = encrypt_entry(&oddball, &words, &plain);
        let xp3 = dir.join("data.xp3");
        fs::write(&xp3, build_xp3(&[("bg/bg01.png", hash, enc)])).unwrap();
        assert!(detect_cipher(&dir, xp3.to_str().unwrap()).is_err());
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn tpm_control_block_is_used() {
        let dir = tmp("tpm");
        fs::create_dir_all(&dir).unwrap();
        // TPM layout per the vendored reader: a plaintext signature marker
        // with every 32-bit word of the stored block bit-inverted (the
        // reader yields !raw). Encrypt with exactly those yielded words so
        // the test exercises the reader's own semantics.
        let mut raw = CONTROL_BLOCK_SIGNATURE.to_vec();
        raw.resize(4096, 0);
        for (i, b) in raw.iter_mut().enumerate().skip(CONTROL_BLOCK_SIGNATURE.len()) {
            *b = (i % 251) as u8;
        }
        // The reader yields the inverted words and the effective table must
        // equal them; since encrypt_entry hands the VM inverted words, feed
        // it the raw bytes' words instead (double inversion = identity).
        let raw_words: Vec<u32> = raw.chunks_exact(4)
            .map(|q| u32::from_le_bytes([q[0], q[1], q[2], q[3]]))
            .collect();
        let mut tpm_bytes = raw.clone();
        tpm_bytes.extend_from_slice(&[0u8; 64]); // reader needs len > block size
        fs::write(dir.join("encryption.tpm"), &tpm_bytes).unwrap();

        let plain = b"OggSprotected-by-tpm".to_vec();
        let karakara = &SCHEME_TABLE[6];
        assert_eq!(karakara.name, "cxdec karakara");
        let (hash, enc) = encrypt_entry(karakara, &raw_words, &plain);
        let xp3 = dir.join("data.xp3");
        fs::write(&xp3, build_xp3(&[("bg/logo.png", hash, enc)])).unwrap();
        let (_cx, scheme_name) =
            detect_cipher(&dir, xp3.to_str().unwrap()).expect("scheme must be found");
        assert_eq!(scheme_name, "cxdec karakara");
        let out = dir.join("out");
        fs::create_dir_all(&out).unwrap();
        let (done, fail) = extract(
            dir.to_str().unwrap(), xp3.to_str().unwrap(), out.to_str().unwrap(), None,
        ).unwrap();
        assert_eq!((done, fail), (1, 0));
        assert_eq!(fs::read(out.join("bg/logo.png")).unwrap(), plain);
        fs::remove_dir_all(&dir).ok();
    }

    /// A resource dump often ships `plugin/` (and therefore the SDK's `.tpm`)
    /// while `xp3filter.tjs` and the EXE are gone. The block search must walk
    /// down into subfolders: measured on a real feng dump, a `plugin/otome.tpm`
    /// one level down was invisible to the old flat scan, and the archive then
    /// misreported as `plain` and extracted as garbage.
    #[test]
    fn control_block_is_found_in_a_subdirectory() {
        let dir = tmp("nested_cb");
        fs::create_dir_all(dir.join("plugin")).unwrap();
        let mut raw = CONTROL_BLOCK_SIGNATURE.to_vec();
        raw.resize(4096, 0);
        for (i, b) in raw.iter_mut().enumerate().skip(CONTROL_BLOCK_SIGNATURE.len()) {
            *b = (i % 251) as u8;
        }
        let raw_words: Vec<u32> = raw.chunks_exact(4)
            .map(|q| u32::from_le_bytes([q[0], q[1], q[2], q[3]]))
            .collect();
        let mut tpm_bytes = raw.clone();
        tpm_bytes.extend_from_slice(&[0u8; 64]); // reader needs len > block size
        // One level down, and deliberately no xp3filter.tjs anywhere.
        fs::write(dir.join("plugin/otome.tpm"), &tpm_bytes).unwrap();

        let plain = png_plaintext(3000);
        let (hash, enc) = encrypt_entry(&FENG_TEMPLATE, &raw_words, &plain);
        let xp3 = dir.join("data.xp3");
        fs::write(&xp3, build_xp3(&[("bg/logo.png", hash, enc)])).unwrap();

        // With no script the scheme has to come from the table, not from tjs.
        let (_cx, name) = detect_cipher(&dir, xp3.to_str().unwrap())
            .expect("a nested control block must be found");
        assert_eq!(name, FENG_TEMPLATE.name);

        let out = dir.join("out");
        fs::create_dir_all(&out).unwrap();
        let (done, fail) = extract(
            dir.to_str().unwrap(), xp3.to_str().unwrap(), out.to_str().unwrap(), None,
        ).unwrap();
        assert_eq!((done, fail), (1, 0));
        assert_eq!(fs::read(out.join("bg/logo.png")).unwrap(), plain);
        fs::remove_dir_all(&dir).ok();
    }

    /// A missing control block no longer aborts the whole archive: extraction
    /// falls back to recovering the constant-only keystream from the entries'
    /// own ADLRs. Whether this particular entry is solvable depends on its
    /// fixups, but the archive must never hard-fail and must never write garbage
    /// for an entry it refused.
    #[test]
    fn missing_control_block_falls_back_to_recovery() {
        let dir = tmp("nocb");
        fs::create_dir_all(&dir).unwrap();
        let plain = png_plaintext(64);
        let words = fake_control_block();
        let (hash, enc) = encrypt_entry(&FENG_TEMPLATE, &words, &plain);
        let xp3 = dir.join("data.xp3");
        fs::write(&xp3, build_xp3(&[("a.png", hash, enc)])).unwrap();
        let out = dir.join("out");
        fs::create_dir_all(&out).unwrap();
        let (done, fail) = extract(
            dir.to_str().unwrap(), xp3.to_str().unwrap(), out.to_str().unwrap(), None,
        )
        .unwrap();
        assert_eq!(done + fail, 1, "the archive must no longer hard-fail");
        // A refused entry leaves nothing behind, so a partial output never mixes
        // in garbage.
        assert_eq!(out.join("a.png").exists(), done == 1);
        if done == 1 {
            assert_eq!(fs::read(out.join("a.png")).unwrap(), plain);
        }
        fs::remove_dir_all(&dir).ok();
    }

    /// The UI probe must report the same scheme `detect_cipher` scores, and the
    /// name it reports must be enough to rebuild that cipher from the folder
    /// alone — that round-trip is what the encrypted writer relies on.
    #[test]
    fn probe_scheme_reports_and_rebuilds_the_scoring_cipher() {
        let dir = tmp("probe_detected");
        fs::create_dir_all(&dir).unwrap();
        let words = fake_control_block();
        // The game's own constants deliberately differ from the feng template's
        // (0x275/0x380): if the rebuild ignored the tjs and used the template
        // constants, the cipher would be wrong and the decrypt below would not
        // reproduce the plaintext. With identical constants the test could not
        // tell the two apart — it would pass either way.
        let spec = SchemeSpec { mask: 0x2AB, offset: 0x4C1, ..FENG_TEMPLATE };
        fs::write(dir.join("xp3filter.tjs"), make_tjs(&words, spec.mask, spec.offset)).unwrap();
        // 4096 bytes, not 64: mask/offset only decide how long the leading
        // "prolog" run is (base_offset = (hash & mask) + offset, ~1.5 KB), and
        // below that length every mask/offset yields the same keystream. A short
        // entry would make this test insensitive to the tjs constants.
        let plain = png_plaintext(4096);
        let (hash, enc) = encrypt_entry(&spec, &words, &plain);
        let xp3 = dir.join("data.xp3");
        fs::write(&xp3, build_xp3(&[("bg/logo.png", hash, enc)])).unwrap();

        let name = match probe_scheme(&dir, xp3.to_str().unwrap()) {
            SchemeProbe::Detected(name) => name,
            other => panic!("expected Detected, got {other:?}"),
        };
        assert!(name.contains("feng"), "got {name}");

        // Rebuild the cipher from the NAME alone and decrypt the entry back to
        // the original bytes: the writer has nothing else to go on.
        let mut cx = cipher_by_name(&dir, name).expect("name must rebuild the cipher");
        let mut arch = Xp3Archive::open(&xp3).unwrap();
        let mut cipher_fn = |h: u32, off: u64, data: &mut [u8]| {
            cx.decrypt(h, off, data).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))
        };
        assert_eq!(arch.read_entry(0, &mut cipher_fn).unwrap(), plain);
        fs::remove_dir_all(&dir).ok();
    }

    /// "No control block" (not a cxdec folder) and "control block but no scheme
    /// fits" (a cxdec folder we cannot decrypt) must stay apart — the UI shows a
    /// different message for each.
    #[test]
    fn probe_scheme_distinguishes_missing_sidecar_from_no_match() {
        let dir = tmp("probe_states");
        fs::create_dir_all(&dir).unwrap();
        let words = fake_control_block();
        let xp3 = dir.join("data.xp3");

        // No sidecar at all → NoControlBlock, whatever the archive holds.
        fs::write(&xp3, build_xp3(&[("f.bin", 0x1234_5678, vec![0u8; 64])])).unwrap();
        assert_eq!(probe_scheme(&dir, xp3.to_str().unwrap()), SchemeProbe::NoControlBlock);

        // Sidecar present but nothing scores → NoSchemeMatch. The constant byte
        // is searched rather than assumed: a wrong cipher can still produce a
        // 2-byte magic by chance (MP3 frame sync is 1 in 2048) and flake.
        fs::write(dir.join("xp3filter.tjs"), make_tjs(&words, 0x275, 0x380)).unwrap();
        let mut witness = None;
        for b in 0u16..=255 {
            fs::write(&xp3, build_xp3(&[("f.bin", 0x1234_5678, vec![b as u8; 64])])).unwrap();
            if probe_scheme(&dir, xp3.to_str().unwrap()) == SchemeProbe::NoSchemeMatch {
                witness = Some(b as u8);
                break;
            }
        }
        assert!(witness.is_some(), "no constant payload stayed unscored");
        fs::remove_dir_all(&dir).ok();
    }
}
