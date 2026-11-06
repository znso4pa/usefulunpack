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

/// 魔数匹配器 + 「第 N 个模式属于哪条签名 / 是哪个魔数」两张对照表。
///
/// 抽出来是为了让**递归扫描**复用同一套匹配器：Aho-Corasick 的构建成本随模式数
/// 增长，每钻进一层条目就重建一次是浪费。
struct Matcher {
    ac: AhoCorasick,
    sigs: Vec<&'static validators::Sig>,
    magics: Vec<&'static [u8]>,
    /// 最长魔数的字节数 —— 决定跨块重叠窗口的大小。
    max_magic: usize,
}

impl Matcher {
    fn new() -> Result<Self, aho_corasick::BuildError> {
        let mut patterns: Vec<&'static [u8]> = Vec::new();
        let mut sigs: Vec<&'static validators::Sig> = Vec::new();
        let mut magics: Vec<&'static [u8]> = Vec::new();
        for sig in validators::SIGNATURES {
            for mg in sig.magics {
                patterns.push(mg);
                sigs.push(sig);
                magics.push(mg);
            }
        }
        let max_magic = magics.iter().map(|m| m.len()).max().unwrap_or(1).max(1);
        Ok(Self { ac: AhoCorasick::new(patterns)?, sigs, magics, max_magic })
    }
}

/// 递归扫描的最大层数（1 = 只钻进顶层归档一层）。
///
/// 再深就会开始「tar 里套 zip、zip 里套 tar、tar 里套 zip…」地翻垃圾 ——
/// 而且每深一层，输出里就多一批「某个文件内部的某个文件内部的东西」，
/// 对「这堆东西是什么」这个问题没有增量价值。
const MAX_NEST_DEPTH: u32 = 2;

/// 递归扫描总共最多追加多少条命中（防御畸形容器把结果刷爆）。
const MAX_NEST_HITS: usize = 512;

/// 递归扫描总共最多**重读**多少字节。
///
/// 递归是第二遍 IO：主循环已经把整文件读过一次，钻进条目里等于把容器的载荷再读
/// 一遍。一个 4096 条 × 256 MiB 的畸形 zip 能把「扫一下」变成读 1 TiB —— 进度条
/// 还停在 100%（`SCAN_BYTES` 只统计主循环），用户看到的就是「卡死」。
const MAX_NEST_BYTES: u64 = 1024 * 1024 * 1024;

/// 在 `[start, end)` 这段**绝对区间**里跑一遍签名扫描，返回绝对偏移的命中。
///
/// 与主循环的差别只有「谁决定边界」：主循环自己按 1 MiB 步进并维护全局进度，
/// 这里由调用方给定区间（一个嵌套条目的数据范围）。skip-past 语义保留 ——
/// 不然一个大条目里每个魔数都要过一次校验器。
fn scan_region(f: &mut File, m: &Matcher, start: u64, end: u64, file_len: u64) -> Vec<Hit> {
    let mut out: Vec<Hit> = Vec::new();
    if start >= end || SCAN_CANCEL.load(Ordering::SeqCst) {
        return out;
    }
    let buf_size = 1usize << 20;
    let mut buf = vec![0u8; buf_size];
    let mut overlap: Vec<u8> = Vec::with_capacity(m.max_magic);
    let mut window: Vec<u8> = Vec::with_capacity(buf_size + m.max_magic);
    let mut pos = start;
    while pos < end && !SCAN_CANCEL.load(Ordering::SeqCst) {
        let want = ((end - pos) as usize).min(buf_size);
        if f.seek(SeekFrom::Start(pos)).is_err() {
            break;
        }
        let n = match f.read(&mut buf[..want]) {
            Ok(0) => break,
            Ok(n) => n,
            Err(_) => break,
        };
        let window_base = pos.saturating_sub(overlap.len() as u64);
        let overlap_len = overlap.len() as u64;
        window.clear();
        window.extend_from_slice(&overlap);
        window.extend_from_slice(&buf[..n]);

        let mut skipped_past = false;
        for mt in m.ac.find_overlapping_iter(&window) {
            if SCAN_CANCEL.load(Ordering::SeqCst) {
                break;
            }
            let off_in_win = mt.start() as u64;
            let magic = m.magics[mt.pattern().as_usize()];
            if off_in_win < overlap_len && off_in_win + magic.len() as u64 <= overlap_len {
                continue;
            }
            let magic_offset = window_base + off_in_win;
            let sig = m.sigs[mt.pattern().as_usize()];
            let Some(info) = run_validator(sig, f, magic_offset, file_len) else {
                continue;
            };
            // 命中必须落在区间内 —— 校验器是按整个文件读的，可能顺着读到条目外面去。
            if magic_offset < start || magic_offset >= end {
                continue;
            }
            // 大小**截到条目边界**，不因为「超出」就丢掉整条命中。
            //
            // 必须这么做：一批校验器的 size 语义就是「到文件末尾」（XP3 没有总长
            // 字段，`validate_xp3` 直接返回 `file_len - off`）。放进条目里，这个
            // 值必然越过条目末尾 —— 照原样丢掉的话，恰恰是「tar 里套 XP3」这种
            // 最该被找到的情况一条都报不出来。
            let size = match info.size {
                Some(s) if s > 0 => s.min(end - magic_offset),
                _ => end - magic_offset,
            };
            out.push(Hit {
                offset: magic_offset,
                label: sig.label,
                size,
                count: info.count,
                confidence: sig.confidence,
            });
            if sig.confidence >= validators::CONFIDENCE_MEDIUM && size > 0 {
                pos = magic_offset + size;
                overlap.clear();
                skipped_past = true;
                break;
            }
        }
        if skipped_past {
            continue;
        }
        overlap.clear();
        overlap.extend_from_slice(&window[window.len().saturating_sub(m.max_magic - 1)..]);
        pos += n as u64;
    }
    out
}

/// 一个命中点能枚举出「条目数据区间」的容器 → 返回那些区间。
///
/// 只认两种：tar（数据原样存放，区间可直接扫）和 zip（只有 stored 条目能扫）。
/// 别的格式要么压缩过、要么没有便宜的条目枚举路径，一律不递归。
fn entry_ranges(f: &mut File, hit: &Hit, file_len: u64) -> Option<Vec<(u64, u64)>> {
    match hit.label {
        "POSIX tar archive" => Some(validators::tar_entry_ranges(f, hit.offset, file_len)),
        "ZIP archive" => Some(validators::zip_stored_ranges(f, hit.offset, file_len)),
        _ => None,
    }
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
    let m = Matcher::new().map_err(|e| e.to_string())?;
    let ac = &m.ac;
    let max_sig_len = m.max_magic;

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
        for mt in ac.find_overlapping_iter(&window) {
            if SCAN_CANCEL.load(Ordering::SeqCst) {
                break;
            }
            let off_in_win = mt.start() as u64;
            let magic = m.magics[mt.pattern().as_usize()];
            if off_in_win < overlap_len && off_in_win + magic.len() as u64 <= overlap_len {
                continue; // already reported in the previous chunk
            }
            let magic_offset = window_base + off_in_win;
            let sig = m.sigs[mt.pattern().as_usize()];
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

    // ── Pass 3: 钻进 tar / zip 的条目里再扫一遍 ──
    //
    // 主循环对「可信 + 已知大小」的命中是**整段跳过**的（binwalk 语义），所以归档
    // 内部从来没被扫过 —— 这正是「tar 里套 XP3、zip 里套 YPF」一直扫不出来的原因。
    // 这里把那些被跳过的区间按条目切开重新扫。
    //
    // 三条约束：
    //   * 只认能在**原地**定位数据的容器（tar 全部条目、zip 的 stored 条目）——
    //     校验器全是 `&mut File` 的，解压出来的字节没有文件可 seek；
    //   * 按 (offset, label) 去重：嵌套扫描用的是同一张签名表，同一个位置可能
    //     已经被顶层报过；
    //   * 深度与总条数都有上限，见 [`MAX_NEST_DEPTH`] / [`MAX_NEST_HITS`]。
    if !SCAN_CANCEL.load(Ordering::SeqCst) {
        let mut seen: Vec<(u64, &'static str)> =
            file_map.iter().map(|h| (h.offset, h.label)).collect();
        let mut nested: Vec<Hit> = Vec::new();
        // 广度优先：顶层命中都是深度 1 的候选容器，扫出来的命中再入队。
        let mut frontier: Vec<(Hit, u32)> = file_map.iter().map(|h| (*h, 1u32)).collect();
        let mut fi = 0usize;
        let mut budget = MAX_NEST_BYTES;
        while fi < frontier.len() && nested.len() < MAX_NEST_HITS {
            let (hit, depth) = frontier[fi];
            fi += 1;
            if depth > MAX_NEST_DEPTH {
                continue;
            }
            let Some(ranges) = entry_ranges(&mut f, &hit, available_data) else {
                continue;
            };
            for (s, e) in ranges {
                if nested.len() >= MAX_NEST_HITS
                    || budget == 0
                    || SCAN_CANCEL.load(Ordering::SeqCst)
                {
                    break;
                }
                let span = e - s;
                budget = budget.saturating_sub(span);
                for inner in scan_region(&mut f, &m, s, e, available_data) {
                    if seen.iter().any(|&(o, l)| o == inner.offset && l == inner.label) {
                        continue;
                    }
                    seen.push((inner.offset, inner.label));
                    nested.push(inner);
                    frontier.push((inner, depth + 1));
                }
            }
        }
        if !nested.is_empty() {
            file_map.extend(nested);
            file_map.sort_by_key(|h| h.offset);
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

    /// The vendored real `sample.xp3` is current-format, and its index offset
    /// lives at byte 32 — the position the validator used to get wrong, which
    /// made every real XP3 miss while the synthetic test stayed green.
    #[test]
    fn real_xp3_sample_is_identified() {
        let _guard = SCAN_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let real: &[u8] = include_bytes!("../../vendor/xp3/sample.xp3");
        let dir = std::env::temp_dir().join("uu_scan_xp3_real");
        let _ = std::fs::create_dir_all(&dir);
        let p = dir.join("sample.xp3");
        std::fs::write(&p, real).unwrap();
        let json = scan_file_json(p.to_str().unwrap()).unwrap();
        assert!(json.contains("\"l\":\"XP3 archive\""), "{json}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Nothing in the table may panic on a truncated file — that is how a
    /// validator with an undersized header buffer took down the whole scan.
    #[test]
    fn truncated_fixtures_never_panic() {        let _guard = SCAN_LOCK.lock().unwrap_or_else(|e| e.into_inner());
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

    /// 递归扫描的主用例：tar 里第一个条目就是一个真 XP3。
    ///
    /// 主循环对 tar 是「可信 + 已知大小 → 整段跳过」，所以这个 XP3 在顶层从来
    /// 扫不到 —— 修之前这条断言是红的（只有 tar 一条命中）。修好之后两条都要在，
    /// 而且 XP3 的绝对偏移必须落在 tar 的区间里。
    #[test]
    fn tar_nested_archive_is_found() {
        let _g = scan_lock();
        let xp3: &[u8] = include_bytes!("../../vendor/xp3/sample.xp3");
        let tar = validators::make_tar(&[
            ("readme.txt", b"just text, not an archive" as &[u8]),
            ("inner.xp3", xp3),
        ]);
        let p = tmp("nested.tar", &tar);
        let hits = scan_file(&p).unwrap();
        let tar_hit = hits
            .iter()
            .find(|h| h.label == "POSIX tar archive")
            .unwrap_or_else(|| panic!("no tar hit: {hits:?}"));
        let xp3_hit = hits
            .iter()
            .find(|h| h.label == "XP3 archive")
            .unwrap_or_else(|| panic!("nested XP3 not found: {hits:?}"));
        assert!(
            xp3_hit.offset >= tar_hit.offset && xp3_hit.offset < tar_hit.offset + tar_hit.size,
            "nested XP3 at {} must sit inside the tar [{}, +{}): {hits:?}",
            xp3_hit.offset,
            tar_hit.offset,
            tar_hit.size
        );
        // 偏移要能直接用 —— 拿它去读文件，读出来的必须还是 XP3 的魔数。
        use std::io::{Read, Seek, SeekFrom};
        let mut f = File::open(&p).unwrap();
        f.seek(SeekFrom::Start(xp3_hit.offset)).unwrap();
        let mut magic = [0u8; 10];
        f.read_exact(&mut magic).unwrap();
        assert_eq!(&magic, b"XP3\r\n \n\x1a\x8b\x67");
    }

    /// zip 里 **stored**（method 0）的条目也要能钻进去。
    ///
    /// 只做 stored 是刻意的：deflate 的载荷不是明文，就地扫只能扫到压缩流。
    /// 这条测试同时也把「本地头顺序走查」钉住 —— 走偏了就找不到那个条目。
    #[test]
    fn zip_stored_nested_archive_is_found() {
        let _g = scan_lock();
        let xp3: &[u8] = include_bytes!("../../vendor/xp3/sample.xp3");
        let zip = validators::make_zip_stored("inner.xp3", xp3);
        let p = tmp("nested.zip", &zip);
        let hits = scan_file(&p).unwrap();
        assert!(
            hits.iter().any(|h| h.label == "ZIP archive"),
            "zip itself must still be reported: {hits:?}"
        );
        assert!(
            hits.iter().any(|h| h.label == "XP3 archive"),
            "stored zip entry was not scanned: {hits:?}"
        );
    }

    /// 没有条目可枚举的格式**不许**递归 —— 递归入口只认 tar / zip。
    ///
    /// 这条直接测 [`entry_ranges`]，不走整条扫描链：它才是「谁会被钻进去」的
    /// 唯一判据，用假 PNG 包一段 tar 去间接验证只会被 PNG 校验器干扰。
    #[test]
    fn recursion_only_follows_containers() {
        let _g = scan_lock();
        let tar = validators::make_tar(&[("a.bin", b"payload" as &[u8])]);
        let p = tmp("containers.tar", &tar);
        let mut f = File::open(&p).unwrap();
        let len = f.metadata().unwrap().len();
        let probe = |label: &'static str| Hit {
            offset: 257,
            label,
            size: len - 257,
            count: None,
            confidence: validators::CONFIDENCE_HIGH,
        };
        assert!(entry_ranges(&mut f, &probe("POSIX tar archive"), len).is_some());
        assert!(
            entry_ranges(&mut f, &probe("ZIP archive"), len).is_some(),
            "zip must be walkable too (empty range is fine, None is not)"
        );
        for label in [
            "PNG image",
            "7-zip archive",
            "RAR archive v5",
            "gzip compressed data",
            "XP3 archive",
            "ISO media (MP4/MOV/HEIC/AVIF)",
        ] {
            assert!(
                entry_ranges(&mut f, &probe(label), len).is_none(),
                "{label} must not be recursed into"
            );
        }
    }

    /// 真 `tar` 命令产出的归档（本地产物，不入库）。
    ///
    /// 手搓的 [`validators::make_tar`] 覆盖不到真实写法：`./` 目录条目、
    /// `./sub/` 这种带路径的名字、目录条目 size=0、以及 GNU tar 的字段填法。
    /// 这份 fixture 里有两个 XP3（一个在顶层、一个在子目录），两个都得被钻出来。
    #[test]
    fn real_tar_nested_archives_are_found() {
        let _g = scan_lock();
        let p = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("testdata")
            .join("nested-real.tar");
        if !p.is_file() {
            eprintln!("SKIP real tar: testdata/nested-real.tar not present (local fixture)");
            return;
        }
        let hits = scan_file(p.to_str().unwrap()).unwrap();
        let tars: Vec<_> = hits.iter().filter(|h| h.label == "POSIX tar archive").collect();
        assert_eq!(tars.len(), 1, "the tar itself, reported exactly once: {hits:?}");
        let xp3s: Vec<_> = hits.iter().filter(|h| h.label == "XP3 archive").collect();
        assert_eq!(xp3s.len(), 2, "both nested XP3s must be found: {hits:?}");
        for h in &xp3s {
            assert!(
                h.offset > tars[0].offset && h.offset < tars[0].offset + tars[0].size,
                "nested XP3 at {} outside the tar: {hits:?}",
                h.offset
            );
            assert!(h.size > 0, "nested hit must carry a size: {h:?}");
        }
    }

    /// 深度上限：tar 套 tar 套 tar 只报到第二层，不会无限往下。
    #[test]
    fn recursion_stops_at_depth_limit() {
        let _g = scan_lock();
        let innermost = validators::make_tar(&[("x.xp3", b"deep payload, not an archive" as &[u8])]);
        let mid = validators::make_tar(&[("mid.tar", &innermost)]);
        let outer = validators::make_tar(&[("outer.tar", &mid)]);
        let p = tmp("deep.tar", &outer);
        let hits = scan_file(&p).unwrap();
        let tars = hits.iter().filter(|h| h.label == "POSIX tar archive").count();
        assert!(tars >= 2, "at least the outer and the first nested tar: {hits:?}");
        assert!(
            tars <= MAX_NEST_DEPTH as usize + 1,
            "depth limit {} exceeded: {} tar hits {hits:?}",
            MAX_NEST_DEPTH,
            tars
        );
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
