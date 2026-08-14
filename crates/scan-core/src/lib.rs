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
    let mut overlap: Vec<u8> = Vec::new();

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
        let mut window = Vec::with_capacity(overlap.len() + n);
        window.extend_from_slice(&overlap);
        window.extend_from_slice(&buf[..n]);
        let window_base = pos.saturating_sub(overlap.len() as u64);
        let overlap_len = overlap.len() as u64;

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
        overlap = window[window.len().saturating_sub(max_sig_len - 1)..].to_vec();
        pos += n as u64;
        SCAN_BYTES.store(pos, Ordering::SeqCst);
    }

    // ── Post-pass (binwalk parity) ──
    file_map.sort_by_key(|h| h.offset);

    let mut next_kept_offset: u64 = 0;
    let mut i = 0usize;
    while i < file_map.len() {
        let this = file_map[i];
        let remaining = available_data.saturating_sub(this.offset);

        // Same offset conflict → highest confidence wins; tie → first wins.
        if i > 0 && this.offset == file_map[i - 1].offset {
            let prev = file_map[i - 1];
            if this.confidence > prev.confidence {
                file_map.remove(i - 1);
                // Re-examine the same index: file_map[i] is now the previous entry.
                i = i.saturating_sub(1);
                continue;
            } else {
                file_map.remove(i);
                continue;
            }
        }

        // Contained inside a previously kept signature → drop.
        if this.offset < next_kept_offset {
            file_map.remove(i);
            continue;
        }

        // Size extends beyond EOF → drop.
        if this.size > remaining {
            file_map.remove(i);
            continue;
        }

        // Keep: advance the kept range end (only for confident hits).
        if this.confidence >= validators::CONFIDENCE_MEDIUM {
            next_kept_offset = this.offset + this.size;
        }
        i += 1;
    }

    // Extend unknown sizes (size == 0) to the next confident hit or EOF.
    for i in 0..file_map.len() {
        if file_map[i].size == 0 {
            let mut next_offset = available_data;
            for entry in file_map.iter().skip(i + 1) {
                if entry.confidence >= validators::CONFIDENCE_MEDIUM {
                    next_offset = entry.offset;
                    break;
                }
            }
            file_map[i].size = next_offset - file_map[i].offset;
        }
    }

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
        // A gzip stream inside a larger host file at offset 10.
        let mut host = vec![0x41u8; 10];
        host.extend_from_slice(b"\x1f\x8b\x08\x00\x00\x00\x00\x00\x00\x03\xff\xff");
        let p = tmp("host.bin", &host);
        let hits = scan_file(&p).unwrap();
        assert!(hits.iter().any(|h| h.label == "gzip compressed data" && h.offset == 10));
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
        // Place a valid gzip stream so its magic spans the 1 MiB chunk
        // boundary: file[1MiB-2..1MiB+8] = full gzip header
        // (1F 8B 08 FLG=0 MTIME=0 XFL=0 OS=3).
        let _g = scan_lock();
        let chunk = 1usize << 20;
        let mut data = vec![0x41u8; chunk + 16];
        let gz = [0x1fu8, 0x8b, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03];
        data[chunk - 2..chunk - 2 + gz.len()].copy_from_slice(&gz);
        let p = tmp("straddle.bin", &data);
        let hits = scan_file(&p).unwrap();
        let gz_hits: Vec<_> = hits.iter().filter(|h| h.label == "gzip compressed data").collect();
        assert_eq!(gz_hits.len(), 1, "straddling magic must be found: {hits:?}");
        assert_eq!(gz_hits[0].offset, (chunk - 2) as u64);
    }
}
