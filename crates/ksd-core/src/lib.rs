use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jlong};
use archive_common::{s, json_escape, extract_result_json, ProgressWriter};
use archive_common::{extract_progress, compress_progress};
use flate2::read::DeflateDecoder;
use flate2::write::ZlibEncoder;
use std::fs::{self, File};
use std::io::{Read, Write};
use std::path::Path;
// ─── KSD (Kirikiri2 save data) ──────────────────────
//
// Format (reverse-engineered, see Luv-Ray/krkr-save-tools):
//   [FE FE] [mode] [FF FE] ...
//   mode 0: scrambled UTF-16 LE (XOR dance)
//   mode 1: bit-swapped UTF-16 LE (adjacent bits swapped)
//   mode 2: i64 compressed_len + i64 uncompressed_len + 2-byte zlib header + raw deflate
//   result: UTF-16 LE TJS script

// KSD wraps UTF-16 text (real save data / wrapped TJS scripts are KBs); 16 MiB
// bounds both the RAM a hostile declaration can pin here and the decode cap of
// the XP3 in-archive probe (xp3-core's KSD_PROBE_MAX), leaving no room for a
// single entry to squeeze out a 512 MiB native allocation on low-RAM devices.
const MAX_MODE2_OUT: usize = 16 * 1024 * 1024;

/// Output name: "foo.ksd" → "foo.txt".
fn output_name(input: &str) -> String {
    let stem = Path::new(input).file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_else(|| "output".to_string());
    format!("{stem}.txt")
}

fn guarded<T: Send + 'static>(f: impl FnOnce() -> Result<T, String> + Send + 'static) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|panic| {
        let msg = panic.downcast_ref::<&str>().copied()
            .or_else(|| panic.downcast_ref::<String>().map(|s| s.as_str()))
            .unwrap_or("unknown panic");
        Err(format!("panic: {msg}"))
    })
}

fn descramble_mode0(data: &[u8]) -> Vec<u8> {
    let mut out = data.to_vec();
    let mut i = 0;
    while i + 1 < out.len() {
        if out[i + 1] == 0 && out[i] < 0x20 {
            i += 2;
            continue;
        }
        out[i + 1] ^= out[i] & 0xFE;
        out[i] ^= 1;
        i += 2;
    }
    out
}

fn descramble_mode1(data: &[u8]) -> Vec<u8> {
    let mut out = data.to_vec();
    let mut i = 0;
    while i + 1 < out.len() {
        let c = u16::from_le_bytes([out[i], out[i + 1]]);
        let c = ((c & 0xAAAA) >> 1) | ((c & 0x5555) << 1);
        let bytes = c.to_le_bytes();
        out[i] = bytes[0];
        out[i + 1] = bytes[1];
        i += 2;
    }
    out
}

/// Returns (utf16_le_bytes, uncompressed_len). `data` starts after the 5-byte
/// header (i.e. at offset 0x05): [compressed_len:i64][uncompressed_len:i64][compressed].
fn decompress_mode2(data: &[u8]) -> Result<(Vec<u8>, u64), String> {
    if data.len() < 16 { return Err("KSD: truncated mode2 header".to_string()); }
    let compressed_i = i64::from_le_bytes(data[0..8].try_into().unwrap());
    let uncompressed_i = i64::from_le_bytes(data[8..16].try_into().unwrap());
    // Negative lengths would wrap to huge usize on `as` — reject outright.
    if compressed_i < 0 || uncompressed_i < 0 {
        return Err(format!("KSD: negative length ({compressed_i}, {uncompressed_i})"));
    }
    let compressed_len = compressed_i as usize;
    let uncompressed_len = uncompressed_i as usize;
    if compressed_len < 2 || 16 + compressed_len > data.len() {
        return Err(format!("KSD: bad compressed_len {compressed_len}"));
    }
    if uncompressed_len > MAX_MODE2_OUT {
        return Err(format!("KSD: uncompressed size too large ({uncompressed_len})"));
    }
    // compressed_len includes the 2-byte zlib header — skip it, then raw deflate
    let deflate_data = &data[16 + 2..16 + compressed_len];
    let dec = DeflateDecoder::new(deflate_data);
    // Cap the DECODED output at the declared size too: a malicious file can
    // declare a small uncompressed_len while carrying a stream that inflates
    // far larger (bomb). Read::take truncates silently to the declared size,
    // matching the clamp semantics of the NSA/YPF streaming paths.
    let mut limited = dec.take(uncompressed_len as u64);
    let mut out = Vec::with_capacity(uncompressed_len.min(1 << 20));
    limited.read_to_end(&mut out).map_err(|e| format!("KSD: inflate {e}"))?;
    Ok((out, uncompressed_len as u64))
}

/// Weak plausibility check for probe-decoded content. Real KSD mode-2
/// payloads are UTF-16 LE scripts: require strict UTF-16 validity (no
/// unpaired surrogates) and no control characters beyond newline/tab/CR.
/// Any ≤16 MiB XP3 entry passes the 5-byte magic probe — a binary whose
/// header happens to start `FE FE 02 FF FE` (e.g. some TGA variants) would
/// otherwise "decode" into garbage that replaces the original file; failing
/// the check passes the bytes through untouched instead.
fn plausible_utf16_text(bytes: &[u8]) -> bool {
    if bytes.len() % 2 != 0 { return false; }
    let units = bytes.chunks_exact(2).map(|c| u16::from_le_bytes([c[0], c[1]]));
    std::char::decode_utf16(units).all(|r| {
        r.map(|c| !c.is_control() || matches!(c, '\n' | '\r' | '\t')).unwrap_or(false)
    })
}

/// Detects a KSD mode-2 scrambled blob — used as a Kirikiri filter inside XP3
/// (and similar) archives: `FE FE 02 FF FE` + [compressed_len:i64]
/// [uncompressed_len:i64] + deflate. Returns the decoded bytes when the magic
/// matches AND the decode passes the UTF-16 plausibility check, else None so
/// the caller writes the content as-is.
pub fn ksd_mode2_decode(data: &[u8]) -> Option<Vec<u8>> {
    if data.len() >= 5
        && data[0] == 0xFE && data[1] == 0xFE && data[2] == 0x02
        && data[3] == 0xFF && data[4] == 0xFE
    {
        decompress_mode2(&data[5..]).ok()
            .filter(|(bytes, _)| plausible_utf16_text(bytes))
            .map(|(bytes, _)| bytes)
    } else {
        None
    }
}

fn decode_utf16le(bytes: &[u8]) -> String {
    let units: Vec<u16> = bytes.chunks_exact(2)
        .map(|c| u16::from_le_bytes([c[0], c[1]]))
        .collect();
    String::from_utf16_lossy(&units)
}

/// Pre-checks the file size (metadata) before loading it into RAM, so a huge
/// file can't OOM the device before the size cap below is ever reached.
fn ksd_read(input: &str) -> Result<Vec<u8>, String> {
    let len = std::fs::metadata(input).map_err(|e| format!("KSD read {input}: {e}"))?.len();
    if len > MAX_MODE2_OUT as u64 {
        return Err(format!("KSD: file too large ({len})"));
    }
    fs::read(input).map_err(|e| format!("KSD read {input}: {e}"))
}

/// Reads the header and returns (mode, uncompressed_size if known).
fn probe_ksd(input: &str) -> Result<(u8, Option<u64>), String> {
    let data = ksd_read(input)?;
    if data.len() < 5 || data[0] != 0xFE || data[1] != 0xFE || data[3] != 0xFF || data[4] != 0xFE {
        return Err("KSD: bad magic or BOM".to_string());
    }
    let mode = data[2];
    let size = match mode {
        0 | 1 => None,
        2 => {
            let sl = &data[5..];
            if sl.len() < 16 { return Err("KSD: truncated mode2 header".to_string()); }
            Some(i64::from_le_bytes(sl[8..16].try_into().unwrap()) as u64)
        }
        _ => return Err(format!("KSD: unsupported mode {mode}")),
    };
    Ok((mode, size))
}

fn list_ksd(input: &str) -> Result<String, String> {
    let (_, size) = probe_ksd(input)?;
    let name = output_name(input);
    Ok(format!(r#"[{{"n":"{}","s":{},"d":false,"e":false}}]"#, json_escape(&name), size.unwrap_or(0)))
}

fn extract_ksd(input: &str, output: &str) -> Result<u32, String> {
    let data = ksd_read(input)?;
    if data.len() < 5 || data[0] != 0xFE || data[1] != 0xFE || data[3] != 0xFF || data[4] != 0xFE {
        return Err("KSD: bad magic or BOM".to_string());
    }
    let mode = data[2];
    let body = &data[5..];
    let (utf16, total) = match mode {
        0 => {
            let out = descramble_mode0(body);
            let total = out.len() as u64;
            (out, total)
        }
        1 => {
            let out = descramble_mode1(body);
            let total = out.len() as u64;
            (out, total)
        }
        2 => decompress_mode2(body)?,
        _ => return Err(format!("KSD: unsupported mode {mode}")),
    };
    let text = decode_utf16le(&utf16);
    let name = output_name(input);
    let dest = Path::new(output).join(&name);
    if let Some(p) = dest.parent() { fs::create_dir_all(p).map_err(|e| format!("KSD mkdir: {e}"))?; }
    extract_progress::reset(total);
    extract_progress::set_name(&name);
    extract_progress::set_file(total);
    let mut writer = ProgressWriter::extract(File::create(&dest).map_err(|e| format!("KSD create {dest:?}: {e}"))?);
    writer.write_all(text.as_bytes()).map_err(|e| {
        let _ = std::fs::remove_file(&dest);
        format!("KSD write: {e}")
    })?;
    Ok(0)
}

fn compress_ksd(input: &str, output: &str, level: i32) -> Result<u32, String> {
    let text = fs::read_to_string(input).map_err(|e| format!("KSD read {input}: {e}"))?;
    let utf16: Vec<u8> = text.encode_utf16().flat_map(|c| c.to_le_bytes()).collect();
    let mut enc = ZlibEncoder::new(Vec::new(), flate2::Compression::new(level.clamp(0, 9) as u32));
    enc.write_all(&utf16).map_err(|e| format!("KSD deflate: {e}"))?;
    let compressed = enc.finish().map_err(|e| format!("KSD deflate: {e}"))?;

    let mut out = Vec::with_capacity(5 + 16 + compressed.len());
    out.extend_from_slice(&[0xFE, 0xFE]);
    out.push(2); // mode 2 = compressed
    out.extend_from_slice(&[0xFF, 0xFE]); // UTF-16 LE BOM
    out.extend_from_slice(&(compressed.len() as i64).to_le_bytes());
    out.extend_from_slice(&(utf16.len() as i64).to_le_bytes());
    out.extend_from_slice(&compressed);

    let name = Path::new(input).file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
    compress_progress::reset(text.len() as u64);
    compress_progress::set_name(&name);
    compress_progress::set_file(text.len() as u64);
    fs::write(output, &out).map_err(|e| format!("KSD write {output}: {e}"))?;
    Ok(0)
}

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_KsdCore_ksdListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i);
    match guarded(move || list_ksd(&inp)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_KsdCore_ksdExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = fs::create_dir_all(&out);
    match guarded(move || extract_ksd(&inp, &out)) {
        Ok(f) => { let json = extract_result_json(1, if f == 0 { 1 } else { 0 }, f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_KsdCore_ksdCompress(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, lv: JString) -> jstring {
    compress_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let lvl: i32 = s(&mut e, &lv).parse().unwrap_or(6);
    match guarded(move || compress_ksd(&inp, &out, lvl)) {
        Ok(f) => { let json = extract_result_json(1, if f == 0 { 1 } else { 0 }, f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_KsdCore_ksdExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_KsdCore_ksdExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_KsdCore_ksdExtractProgressFileCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_KsdCore_ksdExtractProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_KsdCore_ksdExtractProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_KsdCore_ksdExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_KsdCore_ksdCompressProgressCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_KsdCore_ksdCompressProgressTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_KsdCore_ksdCompressProgressFileCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_KsdCore_ksdCompressProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_KsdCore_ksdCompressProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&compress_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_KsdCore_ksdCompressCancel(_: JNIEnv, _: JClass) { compress_progress::cancel(); }

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> std::path::PathBuf {
        std::env::temp_dir().join(format!("uu_ksd_{}_{}", std::process::id(), tag))
    }

    #[test]
    fn mode2_round_trip() {
        let dir = tmp("roundtrip");
        std::fs::create_dir_all(&dir).unwrap();
        let txt = "このセーブデータはテストです。\n保存データ：ライン1\nint var = 42;".to_string();
        let src = dir.join("save.txt");
        let ksd = dir.join("save.ksd");
        let out = dir.join("out");
        std::fs::write(&src, &txt).unwrap();
        compress_ksd(src.to_str().unwrap(), ksd.to_str().unwrap(), 6).unwrap();
        // header validation
        let data = std::fs::read(&ksd).unwrap();
        assert_eq!(&data[0..2], &[0xFE, 0xFE]);
        assert_eq!(data[2], 2);
        assert_eq!(&data[3..5], &[0xFF, 0xFE]);
        let (mode, size) = probe_ksd(ksd.to_str().unwrap()).unwrap();
        assert_eq!(mode, 2);
        assert_eq!(size, Some(txt.encode_utf16().count() as u64 * 2));
        std::fs::create_dir_all(&out).unwrap();
        extract_ksd(ksd.to_str().unwrap(), out.to_str().unwrap()).unwrap();
        let got = std::fs::read_to_string(out.join("save.txt")).unwrap();
        assert_eq!(got, txt);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn mode0_descramble_vector() {
        // descramble is (mostly) an involution: known scrambled pair [0x40,0x40,0x43,0x42] → "AB"
        let scrambled = [0x40u8, 0x40, 0x43, 0x42];
        let out = descramble_mode0(&scrambled);
        assert_eq!(out, [0x41, 0x00, 0x42, 0x00]);
        assert_eq!(decode_utf16le(&out), "AB");
        // skip rule: byte pairs where next==0 and current<0x20 stay untouched
        let skip = [0x10u8, 0x00, 0x41, 0x00];
        let out2 = descramble_mode0(&skip);
        assert_eq!(out2, [0x10, 0x00, 0x40, 0x40]);
    }

    #[test]
    fn mode1_descramble_vector_and_involution() {
        // 0x1234 swapped → 0x2138
        let out = descramble_mode1(&[0x34, 0x12]);
        assert_eq!(out, [0x38, 0x21]);
        // involution
        let input: Vec<u8> = (0..64).map(|i| (i * 7) as u8).collect();
        let once = descramble_mode1(&input);
        assert_eq!(descramble_mode1(&once), input);
    }

    #[test]
    fn bad_magic_rejected() {
        let dir = tmp("bad");
        std::fs::create_dir_all(&dir).unwrap();
        let f = dir.join("bad.ksd");
        std::fs::write(&f, b"not a ksd file").unwrap();
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        assert!(extract_ksd(f.to_str().unwrap(), out.to_str().unwrap()).is_err());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn mode2_decompression_bomb_capped() {
        // declared uncompressed_len huge but actual small — must error on the cap
        let dir = tmp("bomb");
        std::fs::create_dir_all(&dir).unwrap();
        let mut enc = ZlibEncoder::new(Vec::new(), flate2::Compression::new(6));
        enc.write_all(&[0x41u8; 16]).unwrap();
        let compressed = enc.finish().unwrap();
        let mut out = Vec::new();
        out.extend_from_slice(&[0xFE, 0xFE, 2, 0xFF, 0xFE]);
        out.extend_from_slice(&(compressed.len() as i64).to_le_bytes());
        out.extend_from_slice(&(MAX_MODE2_OUT as i64 + 1).to_le_bytes());
        out.extend_from_slice(&compressed);
        let f = dir.join("bomb.ksd");
        std::fs::write(&f, &out).unwrap();
        let o = dir.join("out");
        std::fs::create_dir_all(&o).unwrap();
        assert!(extract_ksd(f.to_str().unwrap(), o.to_str().unwrap()).is_err());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn mode2_declared_small_actual_big_clamped() {
        // Declared uncompressed_len = 1 KiB but the deflate stream inflates
        // to 1 MiB: the decoded buffer must be clamped to the declared size
        // (silent truncation, like NSA/YPF), never grow unbounded.
        let dir = tmp("clamp");
        std::fs::create_dir_all(&dir).unwrap();
        let mut enc = ZlibEncoder::new(Vec::new(), flate2::Compression::new(6));
        enc.write_all(&[0x41u8; 1024 * 1024]).unwrap();
        let compressed = enc.finish().unwrap();
        assert!(compressed.len() < 4096, "zlib of 1MiB 'A' must be tiny, got {}", compressed.len());
        let mut buf = Vec::new();
        buf.extend_from_slice(&[0xFE, 0xFE, 2, 0xFF, 0xFE]);
        buf.extend_from_slice(&(compressed.len() as i64).to_le_bytes());
        buf.extend_from_slice(&(1024i64).to_le_bytes()); // declared 1 KiB
        buf.extend_from_slice(&compressed);
        let f = dir.join("clamp.ksd");
        std::fs::write(&f, &buf).unwrap();

        // Direct check: the decoded buffer must be clamped to the declared
        // size (1 MiB of 'A' inflates to 1 MiB raw, declared only 1 KiB).
        let (out, total) = decompress_mode2(&buf[5..]).unwrap();
        assert_eq!(out.len(), 1024, "decoded buffer must be clamped to the declared size");
        assert_eq!(total, 1024);

        // Full extract path also completes: 1024 raw bytes = 512 UTF-16
        // units of U+4141 → 512 chars × 3 UTF-8 bytes = 1536 bytes on disk.
        let o = dir.join("out");
        std::fs::create_dir_all(&o).unwrap();
        extract_ksd(f.to_str().unwrap(), o.to_str().unwrap()).unwrap();
        let got = std::fs::read(o.join("clamp.txt")).unwrap();
        assert_eq!(got.len(), 1536, "512 units of U+4141 → 3 UTF-8 bytes each");
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn oversize_file_rejected_before_read() {
        // A sparse file above MAX_MODE2_OUT must be rejected from metadata
        // alone — the whole file is never read into RAM.
        let dir = tmp("oversize");
        std::fs::create_dir_all(&dir).unwrap();
        let f = dir.join("big.ksd");
        let file = std::fs::File::create(&f).unwrap();
        file.set_len(513 * 1024 * 1024).unwrap();
        drop(file);
        let o = dir.join("out");
        std::fs::create_dir_all(&o).unwrap();
        let err = extract_ksd(f.to_str().unwrap(), o.to_str().unwrap()).unwrap_err();
        assert!(err.contains("too large"), "unexpected error: {err}");
        assert!(probe_ksd(f.to_str().unwrap()).is_err());
        std::fs::remove_dir_all(&dir).ok();
    }

    /// Builds a mode-2 wrapper around arbitrary inner bytes.
    fn wrap_mode2(inner: &[u8]) -> Vec<u8> {
        let mut enc = ZlibEncoder::new(Vec::new(), flate2::Compression::new(6));
        enc.write_all(inner).unwrap();
        let compressed = enc.finish().unwrap();
        let mut blob = Vec::new();
        blob.extend_from_slice(&[0xFE, 0xFE, 2, 0xFF, 0xFE]);
        blob.extend_from_slice(&(compressed.len() as i64).to_le_bytes());
        blob.extend_from_slice(&(inner.len() as i64).to_le_bytes());
        blob.extend_from_slice(&compressed);
        blob
    }

    #[test]
    fn mode2_probe_rejects_non_text_magic_collision() {
        // Any ≤16MiB entry passes the XP3 probe's 5-byte magic check; a binary
        // (TGA-style collision) must NOT be "decoded" into garbage. Invalid
        // UTF-16 (lone high surrogate) → None → caller passes bytes through.
        let mut inner = vec![0x41u8, 0x00]; // 'A'
        inner.extend_from_slice(&[0x00, 0xD8]); // lone high surrogate U+D800
        inner.extend_from_slice(&[0x42u8, 0x00]); // 'B', no low surrogate follows
        assert_eq!(ksd_mode2_decode(&wrap_mode2(&inner)), None);

        // Valid UTF-16 but a non-whitespace control char (U+0001) → also None.
        let ctrl = vec![0x41u8, 0x00, 0x01, 0x00, 0x42u8, 0x00];
        assert_eq!(ksd_mode2_decode(&wrap_mode2(&ctrl)), None);

        // Odd byte length can't be UTF-16 at all → None.
        assert_eq!(ksd_mode2_decode(&wrap_mode2(&[0x41u8, 0x00, 0x42])), None);
    }

    #[test]
    fn mode2_probe_accepts_real_text() {
        let text: Vec<u8> = "セーブデータ\nテスト\r\nint x = 1;\t".encode_utf16()
            .flat_map(|u| u.to_le_bytes()).collect();
        assert_eq!(ksd_mode2_decode(&wrap_mode2(&text)).unwrap(), text);
    }
}
