//! scan-core: binwalk-style signature scanning. Uses Aho-Corasick for
//! multi-pattern magic matching, per-format header validation, and binwalk's
//! scan semantics: validated hits with a known size are skipped past, then a
//! post-pass drops overlapping / contained hits and fills unknown sizes.
//!
//! Memory-friendly: the file is scanned in 1 MiB streaming chunks (with a
//! small overlap so magics straddling a chunk boundary are still found);
//! validators are seek-based so they work on the real file regardless of
//! chunking. Never loads the whole file into RAM.

mod validators;

use aho_corasick::AhoCorasick;
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong, jstring, JNI_FALSE, JNI_TRUE};
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

static SCAN_BYTES: AtomicU64 = AtomicU64::new(0);
static SCAN_TOTAL: AtomicU64 = AtomicU64::new(0);
static SCAN_CANCEL: AtomicBool = AtomicBool::new(false);

fn s(env: &mut JNIEnv, s: &jni::objects::JString) -> String {
    env.get_string(s).map(|v| v.into()).unwrap_or_default()
}

fn json_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c.is_control() => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out
}

/// Validated scan hit: offset, label, size (0 = unknown → extends to next
/// signature or EOF), entry count and confidence.
#[derive(Debug, Clone, Copy)]
struct Hit {
    offset: u64,
    label: &'static str,
    size: u64,
    count: Option<u32>,
    confidence: u8,
}

/// Runs a signature parser against the file at absolute `offset` (seek-based),
/// so it works with streaming chunk scans. Returns the validated size/count,
/// or None for a false positive.
fn run_validator(sig: &validators::Sig, f: &mut File, offset: u64, file_len: u64) -> Option<validators::HitInfo> {
    (sig.validate)(f, offset, file_len)
}

/// Scans [path] and returns the JSON hit list (dev / test convenience).
#[doc(hidden)]
pub fn scan_file_json(path: &str) -> Result<String, String> {
    Ok(hits_json(&scan_file(path)?))
}

/// Scans [path] for all signatures with binwalk's scan() semantics, but over
/// 1 MiB streaming chunks instead of the whole file in memory:
/// AhoCorasick matching → parser validation → size-skip for confident hits →
/// post-pass: sort, drop same-offset conflicts (highest confidence wins),
/// drop hits contained inside previously identified signatures, and extend
/// unknown sizes to the next signature or EOF.
fn scan_file(path: &str) -> Result<Vec<Hit>, String> {
    let mut f = File::open(path).map_err(|e| e.to_string())?;
    let available_data = f.metadata().map_err(|e| e.to_string())?.len();
    SCAN_BYTES.store(0, Ordering::SeqCst);
    SCAN_TOTAL.store(available_data, Ordering::SeqCst);
    SCAN_CANCEL.store(false, Ordering::SeqCst);

    // NSA (NScripter) has no magic bytes, so the Aho-Corasick pass would only
    // find the media embedded inside. Detect a standalone NSA structurally at
    // file start and report it as a single high-confidence hit to EOF.
    if let Some(info) = validators::validate_nsa_whole_file(&mut f, available_data) {
        let mut hits = Vec::new();
        hits.push(Hit {
            offset: 0,
            label: "NSA archive",
            size: info.size.unwrap_or(available_data),
            count: info.count,
            confidence: validators::CONFIDENCE_HIGH,
        });
        SCAN_BYTES.store(available_data, Ordering::SeqCst);
        return Ok(hits);
    }

    // Build the Aho-Corasick matcher over all magic patterns.
    let mut patterns: Vec<&[u8]> = Vec::new();
    let mut sig_for_pattern: Vec<&validators::Sig> = Vec::new();
    let mut magic_for_pattern: Vec<&'static [u8]> = Vec::new();
    for sig in validators::SIGNATURES {
        for m in sig.magics {
            patterns.push(m);
            sig_for_pattern.push(sig);
            magic_for_pattern.push(m);
        }
    }
    let ac = AhoCorasick::new(patterns).map_err(|e| e.to_string())?;
    let max_sig_len = validators::SIGNATURES
        .iter()
        .flat_map(|s| s.magics.iter())
        .map(|m| m.len())
        .max()
        .unwrap_or(1);

    let mut file_map: Vec<Hit> = Vec::new();
    let buf_size = 1usize << 20;
    let mut buf = vec![0u8; buf_size];
    let mut pos: u64 = 0;
    let mut overlap: Vec<u8> = Vec::with_capacity(max_sig_len);
    // Reused across chunks (clear + extend keeps capacity) so a scan never
    // re-allocates per 1 MiB window.
    let mut window: Vec<u8> = Vec::with_capacity(buf_size + max_sig_len);

    // Main scan loop over streaming chunks. Each iteration reads up to 1 MiB
    // from `pos - overlap.len()`, searches the whole window, and advances
    // `pos` by the bytes actually consumed. Confident hits with a known size
    // are skipped past entirely (binwalk semantics). When a chunk yields no
    // valid signature we advance to the next chunk — never re-scan byte by
    // byte (that was O(n²) on files full of false positives).
    while pos < available_data && !SCAN_CANCEL.load(Ordering::SeqCst) {
        // Read the next chunk from `pos`; the window prepends the previous
        // chunk's tail (overlap) so magics straddling the boundary match.
        // buf must start at `pos`, NOT at read_start — otherwise the overlap
        // bytes and the chunk start would double-count the magic.
        f.seek(SeekFrom::Start(pos)).map_err(|e| e.to_string())?;
        let n = f.read(&mut buf).map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        let window_base = pos.saturating_sub(overlap.len() as u64);
        let overlap_len = overlap.len() as u64;

        window.clear();
        window.extend_from_slice(&overlap);
        window.extend_from_slice(&buf[..n]);

        // Search the whole window. Matches that lie entirely inside the
        // overlap tail were already reported by the previous chunk, so skip
        // them; matches straddling the boundary are only visible here.
        let mut skipped_past = false;
        for m in ac.find_overlapping_iter(&window) {
            if SCAN_CANCEL.load(Ordering::SeqCst) {
                break;
            }
            let off_in_win = m.start() as u64;
            let magic = magic_for_pattern[m.pattern().as_usize()];
            if off_in_win < overlap_len && off_in_win + magic.len() as u64 <= overlap_len {
                continue; // already reported in the previous chunk
            }
            let magic_offset = window_base + off_in_win;
            let sig = sig_for_pattern[m.pattern().as_usize()];
            if let Some(info) = run_validator(sig, &mut f, magic_offset, available_data) {
                let size = info.size.unwrap_or(0);
                // Sanity: signature must not extend beyond EOF.
                if magic_offset + size > available_data {
                    continue;
                }
                file_map.push(Hit {
                    offset: magic_offset,
                    label: sig.label,
                    size,
                    count: info.count,
                    confidence: sig.confidence,
                });
                // Confidence >= MEDIUM and a known size → skip past this hit.
                if sig.confidence >= validators::CONFIDENCE_MEDIUM && size > 0 {
                    pos = magic_offset + size;
                    overlap.clear();
                    SCAN_BYTES.store(pos, Ordering::SeqCst);
                    skipped_past = true;
                    break;
                }
            }
        }
        if skipped_past {
            continue;
        }
        // No size-skip in this chunk: carry the tail into the next window.
        overlap.clear();
        overlap.extend_from_slice(&window[window.len().saturating_sub(max_sig_len - 1)..]);
        pos += n as u64;
        SCAN_BYTES.store(pos, Ordering::SeqCst);
    }

    // ── Post-pass (binwalk parity) ──
    file_map.sort_by_key(|h| h.offset);

    // Pass 1: in-place compaction (a Vec `remove` is O(n) per hit — quadratic
    // on thousands of hits). Drop same-offset conflicts (highest confidence
    // wins, tie → first wins), drop hits contained inside a previously kept
    // signature, and drop sizes extending beyond EOF.
    let mut w = 0usize;
    let mut next_kept_offset: u64 = 0;
    for r in 0..file_map.len() {
        let hit = file_map[r];
        // Same offset conflict → highest confidence wins; tie → first wins.
        if w > 0 && file_map[w - 1].offset == hit.offset {
            let prev = file_map[w - 1];
            if hit.confidence > prev.confidence {
                file_map[w - 1] = hit;
                if hit.confidence >= validators::CONFIDENCE_MEDIUM {
                    next_kept_offset = hit.offset + hit.size;
                }
            }
            continue;
        }
        // Contained inside a previously kept signature → drop.
        if hit.offset < next_kept_offset {
            continue;
        }
        // Size extends beyond EOF → drop.
        if hit.size > available_data.saturating_sub(hit.offset) {
            continue;
        }
        file_map[w] = hit;
        w += 1;
        // Keep: advance the kept range end (only for confident hits).
        if hit.confidence >= validators::CONFIDENCE_MEDIUM {
            next_kept_offset = hit.offset + hit.size;
        }
    }
    file_map.truncate(w);

    // Pass 2: extend unknown sizes (size == 0) to the next confident hit or
    // EOF. One reverse pass: a confident entry defines the boundary for every
    // earlier unknown-size entry.
    let mut boundary = available_data;
    for i in (0..file_map.len()).rev() {
        if file_map[i].size == 0 {
            file_map[i].size = boundary - file_map[i].offset;
        }
        if file_map[i].confidence >= validators::CONFIDENCE_MEDIUM {
            boundary = file_map[i].offset;
        }
    }

    // ── Pass 3: recursive scan of tar/zip entries ──
    // For Gal game distributions, archives often contain other archives inside
    // (e.g., tar containing XP3 files, zip containing YPF files).
    // We scan the data regions of tar and zip hits to find nested formats.
    // Skip recursive scan for now to avoid duplicate hits.
    // TODO: Implement proper recursive scanning with deduplication

    Ok(file_map)
}

fn hits_json(hits: &[Hit]) -> String {
    let mut out = String::from("[");
    for (i, h) in hits.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        out.push_str(&format!(
            r#"{{"o":{},"l":"{}","s":{},"c":{}}}"#,
            h.offset,
            json_escape(h.label),
            if h.size > 0 { h.size.to_string() } else { "null".into() },
            h.count.map(|v| v.to_string()).unwrap_or_else(|| "null".into()),
        ));
    }
    out.push(']');
    out
}

/// Runs a scan inside a guard so a panic never crosses the JNI boundary.
fn guarded<T>(f: impl FnOnce() -> Result<T, String>) -> Result<T, String> {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)) {
        Ok(r) => r,
        Err(p) => Err(format!("scan panic: {:?}", p.downcast_ref::<&str>().copied().unwrap_or(""))),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_ScanCore_scanFile(mut e: JNIEnv, _: JClass, path: JString) -> jstring {
    let p = s(&mut e, &path);
    match guarded(move || scan_file(&p)) {
        Ok(hits) => {
            let json = hits_json(&hits);
            match e.new_string(&json) {
                Ok(js) => js.into_raw(),
                Err(_) => std::ptr::null_mut(),
            }
        }
        Err(er) => {
            let _ = e.throw_new("java/io/IOException", format!("scan: {er}"));
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_ScanCore_scanProgressBytes(_: JNIEnv, _: JClass) -> jlong {
    SCAN_BYTES.load(Ordering::SeqCst) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_ScanCore_scanProgressTotal(_: JNIEnv, _: JClass) -> jlong {
    SCAN_TOTAL.load(Ordering::SeqCst) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_ScanCore_scanCancel(_: JNIEnv, _: JClass) {
    SCAN_CANCEL.store(true, Ordering::SeqCst);
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_ScanCore_scanCancelled(_: JNIEnv, _: JClass) -> jboolean {
    if SCAN_CANCEL.load(Ordering::SeqCst) { JNI_TRUE } else { JNI_FALSE }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Real-file regression for the expanded signature table.
    ///
    /// Every fixture here was produced by a real writer (ffmpeg for the audio
    /// and video containers, sqlite3, javac, ar, xar, gzip/bzip2/xz/zstd/lz4,
    /// our own APK for the DEX prefix) — see `testdata/README.md` for the exact
    /// commands. The point is that a signature change which breaks a real
    /// format, or a validator that reads past its buffer (which is how a
    /// `dex\n03` header used to panic the whole scan), fails here rather than on
    /// the device.
    #[test]
    fn real_fixtures_are_identified_by_their_own_signature() {
        let _guard = SCAN_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let cases: &[(&str, &str)] = &[
            ("a.adx", "Criware ADX audio"),
            ("a.au", "Sun/NeXT audio"),
            ("a.caf", "Apple CAF audio"),
            ("a.aiff", "AIFF audio"),
            ("a.opus.ogg", "Ogg container"),
            ("a.m4a", "ISO media (MP4/MOV/HEIC/AVIF)"),
            ("v.mp4", "ISO media (MP4/MOV/HEIC/AVIF)"),
            ("v.webm", "Matroska/WebM video"),
            ("m.mkv", "Matroska/WebM video"),
            ("i.qoi", "QOI image"),
            ("i.jp2", "JPEG 2000 image"),
            ("i.tiff", "TIFF image (little-endian)"),
            ("i.bmp", "BMP image"),
            ("i.gif", "GIF image"),
            ("c.sqlite", "SQLite database"),
            ("c.class", "Java class"),
            ("c.pcap", "libpcap capture"),
            ("c.torrent", "BitTorrent metainfo"),
            ("c.pem", "PEM text"),
            ("c.ar", "Unix ar archive"),
            ("c.xar", "XAR archive"),
            ("c.zip", "ZIP archive"),
            ("c.7z", "7-zip archive"),
            ("c.tar", "POSIX tar archive"),
            ("c.gz", "gzip compressed data"),
            ("c.bz2", "bzip2 compressed data"),
            ("c.xz", "XZ compressed data"),
            ("c.lzma", "LZMA compressed data"),
            ("c.zst", "Zstandard compressed data"),
            ("c.lz4", "LZ4 compressed data"),
            ("c.dex.head", "Dalvik executable"),
        ];
        let mut checked = 0;
        let mut absent = Vec::new();
        for (name, label) in cases {
            let p = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("testdata").join(name);
            if !p.is_file() {
                absent.push(*name);
                continue;
            }
            let json = scan_file_json(p.to_str().unwrap()).unwrap_or_else(|e| panic!("{name}: {e}"));
            assert!(
                json.contains(&format!("\"l\":\"{label}\"")),
                "{name} was not identified as {label}: {json}"
            );
            checked += 1;
        }
        if !absent.is_empty() {
            eprintln!(
                "SKIP {} of {} signature fixtures (not distributed in git — see crates/scan-core/testdata/README.md): {}",
                absent.len(),
                cases.len(),
                absent.join(", ")
            );
        }
        assert_eq!(checked + absent.len(), cases.len(), "every case must be either checked or reported absent");
    }

    /// The RPA and INT fixtures come from the sibling crates (no second copy to
    /// drift): a Ren'Py archive and a CatSystem2 KIF archive are exactly the
    /// formats this expansion was asked for.
    #[test]
    fn rpa_and_int_fixtures_are_identified() {
        let _guard = SCAN_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        // Read at run time rather than with `include_bytes!`: these are real
        // game files and are no longer distributed in git (the sibling crates'
        // testdata/README.md records their provenance), so a checkout without
        // the corpus must skip — not fail to compile.
        let manifest = std::path::Path::new(env!("CARGO_MANIFEST_DIR"));
        for (rel, label) in [
            ("../rpa-core/testdata/official-renpy-8.5.3.rpa", "Ren'Py archive"),
            ("../int-core/testdata/ptcl.int", "CatSystem2 INT archive"),
            ("../rpa-core/testdata/rpatool-v2.rpa", "Ren'Py archive"),
        ] {
            let src = manifest.join(rel);
            let Ok(bytes) = std::fs::read(&src) else {
                eprintln!("SKIP {rel}: not present — real fixtures are not distributed in git");
                continue;
            };
            let dir = std::env::temp_dir().join(format!("uu_scan_fx_{}", bytes.len()));
            let _ = std::fs::create_dir_all(&dir);
            let p = dir.join("x.bin");
            std::fs::write(&p, &bytes).unwrap();
            let json = scan_file_json(p.to_str().unwrap()).unwrap();
            assert!(json.contains(&format!("\"l\":\"{label}\"")), "{}: {json}", p.display());
            let _ = std::fs::remove_dir_all(&dir);
        }
    }

    /// Nothing in the table may panic on a truncated file — that is how a
    /// validator with an undersized header buffer took down the whole scan.
    #[test]
    fn truncated_fixtures_never_panic() {
        let _guard = SCAN_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let dir = std::env::temp_dir().join("uu_scan_trunc");
        let _ = std::fs::create_dir_all(&dir);
        let base = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("testdata");
        if !base.is_dir() {
            eprintln!("SKIP truncated-fixture fuzz: crates/scan-core/testdata is absent (fixtures are not distributed in git)");
            return;
        }
        for entry in std::fs::read_dir(&base).unwrap().flatten() {
            let data = std::fs::read(entry.path()).unwrap();
            for cut in [1usize, 2, 3, 4, 8, 16, 24, 32, 64, 128, 512] {
                if cut > data.len() {
                    continue;
                }
                let p = dir.join("t.bin");
                std::fs::write(&p, &data[..cut]).unwrap();
                let _ = scan_file_json(p.to_str().unwrap());
            }
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// All scan tests share the per-format progress/cancel statics, so they
    /// must run serially to avoid racing SCAN_TOTAL/SCAN_CANCEL.
    static SCAN_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

    fn scan_lock() -> std::sync::MutexGuard<'static, ()> {
        SCAN_LOCK.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn tmp(path: &str, bytes: &[u8]) -> String {
        let dir = std::env::temp_dir().join(format!("uu_scan_core_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let p = dir.join(path);
        std::fs::write(&p, bytes).unwrap();
        p.to_str().unwrap().to_string()
    }

    /// Builds a real gzip stream (flate2) so the scan validator's dry-run can
    /// actually decode it.
    fn gz_bytes(data: &[u8]) -> Vec<u8> {
        use flate2::write::GzEncoder;
        use flate2::Compression;
        use std::io::Write as _;
        let mut enc = GzEncoder::new(Vec::new(), Compression::default());
        enc.write_all(data).unwrap();
        enc.finish().unwrap()
    }

    /// Builds a real xz stream (xz2/libzma).
    fn xz_bytes(data: &[u8]) -> Vec<u8> {
        use std::io::Write as _;
        let mut enc = xz2::write::XzEncoder::new(Vec::new(), 6);
        enc.write_all(data).unwrap();
        enc.finish().unwrap()
    }

    /// Builds a real .lzma (alone) stream with lzma-rs (its default header
    /// writes a streaming 0xFFFF… uncompressed size, like liblzma).
    fn lzma_bytes(data: &[u8]) -> Vec<u8> {
        let mut lz = Vec::new();
        lzma_rs::lzma_compress(&mut std::io::Cursor::new(data), &mut lz).expect("lzma_compress");
        lz
    }

    #[test]
    fn zip_scan_reports_once_with_skip() {
        let _g = scan_lock();
        let p = tmp("t.zip", &validators::make_min_zip());
        let hits = scan_file(&p).unwrap();
        let zips: Vec<_> = hits.iter().filter(|h| h.label == "ZIP archive").collect();
        assert_eq!(zips.len(), 1, "zip should be reported once (size-skip): {hits:?}");
        assert_eq!(zips[0].count, Some(1));
        assert!(zips[0].size > 0 && zips[0].size <= 1000);
    }

    #[test]
    fn gzip_embedded_in_host() {
        let _g = scan_lock();
        // A real gzip stream inside a larger host file at offset 10.
        let mut host = vec![0x41u8; 10];
        host.extend_from_slice(&gz_bytes(b"hello gzip inside a host file"));
        let p = tmp("host.bin", &host);
        let hits = scan_file(&p).unwrap();
        assert!(hits.iter().any(|h| h.label == "gzip compressed data" && h.offset == 10));
    }

    /// A -hp header-encrypted RAR5 must be reported once (whole-file region,
    /// MEDIUM confidence), and a plaintext RAR5 exactly once — the HIGH-
    /// confidence plaintext entry wins the same-offset post-pass, so no
    /// duplicate "header encrypted" hit appears.
    #[test]
    fn rar5_hp_scan_reports_encrypted_and_dedups_plaintext() {
        let _g = scan_lock();
        // -hp: sig + CRC + ciphertext whose vint parse fails.
        let mut hp = Vec::new();
        hp.extend_from_slice(b"Rar!\x1a\x07\x01\x00");
        hp.extend_from_slice(&0u32.to_le_bytes());
        hp.extend_from_slice(&[0x7Fu8; 8]);
        hp.extend_from_slice(&[0xA5u8; 100]);
        let php = tmp("scan.rar5hp", &hp);
        let hits = scan_file(&php).unwrap();
        let hp_hits: Vec<_> = hits.iter().filter(|h| h.label.contains("header encrypted")).collect();
        assert_eq!(hp_hits.len(), 1, "exactly one -hp hit: {hits:?}");
        assert_eq!(hp_hits[0].offset, 0);
        assert_eq!(hp_hits[0].size, hp.len() as u64);
        // Plaintext: HIGH wins, no duplicate -hp hit.
        let mut r5 = Vec::new();
        r5.extend_from_slice(b"Rar!\x1a\x07\x01\x00");
        r5.extend_from_slice(&0u32.to_le_bytes());
        r5.push(0x02); // HEAD_SIZE vint
        r5.push(0x01); // HEAD_TYPE = main
        r5.push(0x00); // HEAD_FLAGS
        r5.extend_from_slice(&[0xAAu8; 50]);
        r5.extend_from_slice(&[0x1D, 0x77, 0x56, 0x51, 0x03, 0x05, 0x04, 0x00]); // EOF
        let p2 = tmp("scan.rar5plain", &r5);
        let hits2 = scan_file(&p2).unwrap();
        let plain: Vec<_> = hits2.iter().filter(|h| h.label == "RAR archive v5").collect();
        let enc: Vec<_> = hits2.iter().filter(|h| h.label.contains("header encrypted")).collect();
        assert_eq!(plain.len(), 1, "plaintext RAR5 must be reported once: {hits2:?}");
        assert!(enc.is_empty(), "no duplicate -hp hit for plaintext: {hits2:?}");
    }

    /// An xz stream embedded mid-host must still be reported — the dry-run
    /// decodes the real bytes first, then trips on the trailing host data.
    /// Rejecting it (Err → false) was a v5.12.0 regression.
    #[test]
    fn xz_embedded_in_host() {
        let _g = scan_lock();
        let xz = xz_bytes(b"hello embedded xz stream");
        let mut host = vec![0x41u8; 10];
        host.extend_from_slice(&xz);
        host.extend_from_slice(&vec![0x42u8; 300]);
        let p = tmp("host_xz.bin", &host);
        let hits = scan_file(&p).unwrap();
        assert!(
            hits.iter().any(|h| h.label == "XZ compressed data" && h.offset == 10),
            "embedded xz must be reported: {hits:?}"
        );
    }

    /// A streaming-header .lzma (0xFFFF…, the liblzma/alone default) embedded
    /// mid-host must still be reported. The dry-run skips streaming headers, so
    /// the header whitelist alone decides — no embedded-stream drop.
    #[test]
    fn lzma_embedded_in_host() {
        let _g = scan_lock();
        let lz = lzma_bytes(b"hello embedded lzma stream");
        assert_eq!(&lz[5..13], &[0xFF; 8], "test relies on streaming header");
        let mut host = vec![0x41u8; 10];
        host.extend_from_slice(&lz);
        host.extend_from_slice(&vec![0x42u8; 300]);
        let p = tmp("host_lzma.bin", &host);
        let hits = scan_file(&p).unwrap();
        assert!(
            hits.iter().any(|h| h.label == "LZMA compressed data" && h.offset == 10),
            "embedded lzma must be reported: {hits:?}"
        );
    }

    #[test]
    fn webp_reports_only_riff_no_inner_jpeg() {
        let _g = scan_lock();
        // A WebP (RIFF/VP8) file: RIFF header with size, then VP8 data that
        // contains a false FF D8 FF sequence inside its payload. binwalk
        // semantics: the validated RIFF hit is skipped past, so the inner
        // "JPEG" must NOT appear.
        let mut webp = Vec::new();
        webp.extend_from_slice(b"RIFF");
        webp.extend_from_slice(b"WEBP");
        webp.extend_from_slice(b"VP8 ");
        webp.extend_from_slice(b"\xff\xd8\xff\xe0\x00\x10JFIF\x00"); // false JPEG magic inside
        webp.extend_from_slice(&[0u8; 40]);
        // RIFF size field = payload length after the 8-byte "RIFF"+type header.
        let payload_len = (webp.len() - 8) as u32;
        webp.splice(4..8, payload_len.to_le_bytes());
        let p = tmp("t.webp", &webp);
        let hits = scan_file(&p).unwrap();
        let riffs: Vec<_> = hits.iter().filter(|h| h.label.starts_with("RIFF")).collect();
        let jpegs: Vec<_> = hits.iter().filter(|h| h.label == "JPEG image").collect();
        assert_eq!(riffs.len(), 1, "one RIFF expected: {hits:?}");
        assert!(jpegs.is_empty(), "inner false JPEG must be dropped: {hits:?}");
    }

    #[test]
    fn progress_reaches_total() {
        let _g = scan_lock();
        let p = tmp("t.zip", &validators::make_min_zip());
        let _ = scan_file(&p).unwrap();
        assert_eq!(SCAN_TOTAL.load(Ordering::SeqCst) as usize, std::fs::metadata(&p).unwrap().len() as usize);
        assert!(SCAN_BYTES.load(Ordering::SeqCst) > 0);
    }

    #[test]
    fn no_valid_signature_scans_linearly() {
        // A file full of false-positive JPEG magics but no valid signature.
        // The old loop advanced one byte per iteration and re-ran Aho-Corasick
        // over the whole remaining file (O(n²)). This must finish fast and
        // report nothing.
        let _g = scan_lock();
        let mut data = vec![0u8; 4 * 1024 * 1024];
        // Sprinkle many false JPEG magics that fail validation (no EOI walk).
        for i in (0..data.len() - 4).step_by(4096) {
            data[i..i + 3].copy_from_slice(b"\xff\xd8\xff");
            data[i + 3] = 0xE0;
            // length field garbage → marker walk bails quickly
            data[i + 4..i + 6].copy_from_slice(&0u16.to_be_bytes());
        }
        let p = tmp("noise.bin", &data);
        let start = std::time::Instant::now();
        let hits = scan_file(&p).unwrap();
        let elapsed = start.elapsed();
        assert!(hits.is_empty(), "no valid signatures expected: {hits:?}");
        assert!(
            elapsed.as_secs() < 10,
            "scan must be linear-ish, took {elapsed:?}"
        );
    }

    #[test]
    fn magic_straddling_chunk_boundary_is_found() {
        // Place a real gzip stream so its magic spans the 1 MiB chunk boundary:
        // the stream starts at file[1MiB-2], so 1F 8B straddles the chunk edge,
        // and the whole stream (header + deflate + footer) fits after it.
        let _g = scan_lock();
        let chunk = 1usize << 20;
        let gz = gz_bytes(b"straddling magic across the chunk boundary and past it");
        let mut data = vec![0x41u8; chunk + 512];
        data[chunk - 2..chunk - 2 + gz.len()].copy_from_slice(&gz);
        let p = tmp("straddle.bin", &data);
        let hits = scan_file(&p).unwrap();
        let gz_hits: Vec<_> = hits.iter().filter(|h| h.label == "gzip compressed data").collect();
        assert_eq!(gz_hits.len(), 1, "straddling magic must be found: {hits:?}");
        assert_eq!(gz_hits[0].offset, (chunk - 2) as u64);
    }
}
