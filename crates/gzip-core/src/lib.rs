use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, jlong, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, extract_result_json, ProgressWriter, ProgressReader};
use archive_common::{extract_progress, compress_progress};
use std::fs::{self, File};
use std::io::{self, BufReader, Read, Seek, SeekFrom};
use std::path::Path;

/// Output name: strip the compression suffix. "foo.txt.gz" → "foo.txt".
fn output_name(input: &str) -> String {
    Path::new(input).file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_else(|| "output".to_string())
}

/// Count gzip member starts (magic `1F 8B 08`) in the file. The footer ISIZE
/// only reflects the *last* member, so a multi-member stream's true output is
/// the sum of all members and can't be bounded by a single footer. The
/// streaming decoder accepts concatenated members; a stray `1F 8B 08` inside
/// deflate payload is ~1/16M per position — a tolerable heuristic.
fn gzip_member_count(input: &str) -> u64 {
    let Ok(mut f) = File::open(input) else { return 0 };
    let mut count = 0u64;
    let mut carry: [u8; 2] = [0, 0];
    let mut first = true;
    let mut buf = [0u8; 1 << 16];
    loop {
        let n = match f.read(&mut buf) { Ok(n) => n, Err(_) => break };
        if n == 0 { break; }
        let mut i = 0usize;
        if !first {
            // Cross-boundary magic: two possible straddles.
            // (a) `1F 8B` at the very end of the previous chunk, `08` here:
            if carry[1] == 0x1f && buf[0] == 0x8b && n > 1 && buf[1] == 0x08 {
                count += 1;
            }
            // (b) `1F` at the end of the previous chunk, `8B 08` here:
            if carry[0] == 0x1f && carry[1] == 0x8b && n > 0 && buf[0] == 0x08 {
                count += 1;
            }
        }
        while i + 2 < n {
            if buf[i] == 0x1f && buf[i + 1] == 0x8b && buf[i + 2] == 0x08 {
                count += 1;
                i += 3;
            } else {
                i += 1;
            }
        }
        carry = [buf[n - 2], buf[n - 1]];
        first = false;
    }
    count
}

/// gzip footer holds the uncompressed size (mod 2^32) of the *last* member.
/// Only meaningful for single-member files; multi-member streams fall back to
/// the shared hard cap (see extract_gz).
fn decompressed_size(input: &str) -> u64 {
    let Ok(mut f) = File::open(input) else { return 0 };
    let Ok(m) = f.metadata() else { return 0 };
    if m.len() < 4 { return 0; }
    let Ok(_) = f.seek(SeekFrom::End(-4)) else { return 0 };
    let mut b = [0u8; 4];
    if f.read_exact(&mut b).is_ok() { u32::from_le_bytes(b) as u64 } else { 0 }
}

fn list_gz(input: &str) -> Result<String, String> {
    let name = output_name(input);
    Ok(format!(r#"[{{"n":"{}","s":{},"d":false,"e":false}}]"#, json_escape(&name), decompressed_size(input)))
}

fn extract_gz(input: &str, output: &str) -> Result<u32, String> {
    let name = output_name(input);
    let dest = Path::new(output).join(&name);
    if let Some(p) = dest.parent() { fs::create_dir_all(p).map_err(|e| format!("{e}"))?; }
    let in_len = fs::metadata(input).map(|m| m.len()).unwrap_or(0);
    // MultiGzDecoder handles concatenated multi-member .gz files (some tools
    // produce these); plain GzDecoder stops after the first member.
    let members = gzip_member_count(input);
    let declared = if members <= 1 { decompressed_size(input) } else { 0 };
    // Single member: the footer ISIZE bounds the output exactly, so keep the
    // write-side caliber (total = declared) and count bytes as they're written.
    // Multi-member: no single footer bounds the total, so the old code did
    // reset(0) and the bar spun for the whole run. Fall back to the read caliber
    // (total = archive size) so there is always a denominator. Each branch feeds
    // exactly ONE side — feeding both would double-count.
    let single = members <= 1 && declared > 0;
    let total = if single { declared } else { in_len };
    // Cap output at the footer ISIZE for single-member files; multi-member
    // streams fall back to the shared hard cap (no single footer bounds them).
    let cap = if single { declared } else { archive_common::DEFAULT_EXTRACT_CAP };
    let out = File::create(&dest).map_err(|e| format!("{e}"))?;

    extract_progress::reset(total);
    extract_progress::set_name(&name);
    extract_progress::set_file(total);

    // The two calibers need different wrapper types (ProgressWriter vs
    // ProgressReader), so they get separate io::copy calls rather than one
    // variable holding two concrete types.
    // 两个分支的错误类型不同（map_err 产出 String，但 early-return 用了整函数类型），
    // 所以各自独立收尾，不塞进同一个 Result 变量。
    if single {
        let rdr = BufReader::new(File::open(input).map_err(|e| format!("gzip: {e}"))?);
        let mut dec = flate2::read::MultiGzDecoder::new(rdr);
        let mut w = ProgressWriter::extract(archive_common::BoundedWriter::new(out, cap));
        if let Err(e) = io::copy(&mut dec, &mut w) {
            let _ = fs::remove_file(&dest);
            return Err(format!("gzip: {e}"));
        }
    } else {
        let rdr = BufReader::new(ProgressReader::extract(
            File::open(input).map_err(|e| format!("gzip: {e}"))?,
        ));
        let mut dec = flate2::read::MultiGzDecoder::new(rdr);
        let mut w = archive_common::BoundedWriter::new(out, cap);
        if let Err(e) = io::copy(&mut dec, &mut w) {
            let _ = fs::remove_file(&dest);
            return Err(format!("gzip: {e}"));
        }
    }
    Ok(0)
}

fn compress_gz(input: &str, output: &str, level: i32) -> Result<u32, String> {
    let src = File::open(input).map_err(|e| format!("{e}"))?;
    let size = src.metadata().map(|m| m.len()).unwrap_or(0);
    let out = File::create(output).map_err(|e| format!("{e}"))?;
    let mut enc = flate2::write::GzEncoder::new(out, flate2::Compression::new(level.clamp(0, 9) as u32));
    let name = Path::new(input).file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
    compress_progress::reset(size);
    compress_progress::set_name(&name);
    compress_progress::set_file(size);
    io::copy(&mut ProgressReader::compress(src), &mut enc).map_err(|e| format!("gzip: {e}"))?;
    enc.finish().map_err(|e| format!("gzip: {e}"))?;
    Ok(0)
}

fn guarded<T: Send + 'static>(f: impl FnOnce() -> Result<T, String> + Send + 'static) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|panic| {
        let msg = panic.downcast_ref::<&str>().copied()
            .or_else(|| panic.downcast_ref::<String>().map(|s| s.as_str()))
            .unwrap_or("unknown panic");
        Err(format!("panic: {msg}"))
    })
}

/// Host-side (non-JNI) extraction entry point for examples/tests/benchmarks.
/// Delegates to the same path the app uses. Returns the count of successfully
/// decompressed files.
#[doc(hidden)]
pub fn extract_gzip_host(input: &str, output: &str) -> Result<u32, String> {
    extract_gz(input, output)
}

/// Read-only snapshot of the extract progress statics:
/// `(bytes, total, file_bytes, file_total)`.
///
/// Exists for the out-of-tree byte-progress regression harness and
/// `examples/probe.rs`. Pure accessors with **no side effects** — reading them
/// cannot perturb the very counters a test is trying to observe.
pub fn extract_progress_snapshot() -> (u64, u64, u64, u64) {
    (
        extract_progress::bytes(),
        extract_progress::total_bytes(),
        extract_progress::file_bytes(),
        extract_progress::file_total(),
    )
}

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_GzipCore_gzListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i);
    match guarded(move || list_gz(&inp)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_GzipCore_gzExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = fs::create_dir_all(&out);
    match guarded(move || extract_gz(&inp, &out)) {
        Ok(f) => { let json = extract_result_json(1, if f == 0 { 1 } else { 0 }, f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_GzipCore_gzExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_GzipCore_gzExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_GzipCore_gzExtractProgressFileCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_GzipCore_gzExtractProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_GzipCore_gzExtractProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_GzipCore_gzExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_GzipCore_gzCompress(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, lv: JString) -> jboolean {
    compress_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let lvl: i32 = s(&mut e, &lv).parse().unwrap_or(5);
    match guarded(move || compress_gz(&inp, &out, lvl)) {
        Ok(0) => JNI_TRUE,
        Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("gzip: {f} failed")); JNI_FALSE }
        Err(er) => { let _ = e.throw_new("java/io/IOException", format!("gzip: {er}")); JNI_FALSE }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_GzipCore_gzCompressProgressCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_GzipCore_gzCompressProgressTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_GzipCore_gzCompressProgressFileCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_GzipCore_gzCompressProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_GzipCore_gzCompressProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&compress_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_GzipCore_gzCompressCancel(_: JNIEnv, _: JClass) { compress_progress::cancel(); }

#[cfg(test)]
mod tests {

    /// 进度 store 是 per-cdylib 的**静态量**，cargo 默认并行跑同一个 crate
    /// 的测试，两个测试的 `reset(total)` + `add_bytes` 会互相踩：抢在前面的那个
    /// 会用自己的夹具尺寸改掉 total，后一个断言 total 的测试就红。凡是调了
    /// extract/compress 入口的测试都必须持这把锁。
    ///
    /// 实证：`archive_lzma-core` 的 `extract_progress_total_is_reported` 曾在 CI 上
    /// 以 `left: 327, right: 119` 失败，本地 25/25 通过。
    static PROGRESS_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
    fn progress_lock() -> std::sync::MutexGuard<'static, ()> {
        PROGRESS_LOCK.lock().unwrap_or_else(|e| e.into_inner())
    }
    use super::*;
    use std::io::Write as _;

    fn tmp(tag: &str) -> std::path::PathBuf {
        std::env::temp_dir().join(format!("uu_gz_{}_{}", std::process::id(), tag))
    }

    fn gz_bytes(data: &[u8]) -> Vec<u8> {
        let mut enc = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        enc.write_all(data).unwrap();
        enc.finish().unwrap()
    }

    #[test]
    fn compress_then_extract_round_trip() {
    let _g = progress_lock();
        let dir = tmp("roundtrip");
        std::fs::create_dir_all(&dir).unwrap();
        let data: Vec<u8> = (0..100_000u32).map(|i| (i % 251) as u8).collect();
        let src = dir.join("a.bin");
        let gz = dir.join("a.bin.gz");
        let out = dir.join("out");
        std::fs::write(&src, &data).unwrap();
        compress_gz(src.to_str().unwrap(), gz.to_str().unwrap(), 6).unwrap();
        std::fs::create_dir_all(&out).unwrap();
        extract_gz(gz.to_str().unwrap(), out.to_str().unwrap()).unwrap();
        assert_eq!(std::fs::read(out.join("a.bin")).unwrap(), data);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn isize_reads_uncompressed_size() {
        let dir = tmp("isize");
        std::fs::create_dir_all(&dir).unwrap();
        let data = vec![7u8; 123456];
        let p = dir.join("d.bin.gz");
        std::fs::write(&p, gz_bytes(&data)).unwrap();
        assert_eq!(decompressed_size(p.to_str().unwrap()), 123456);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn multi_member_gz_extracts_fully() {
    let _g = progress_lock();
        let dir = tmp("multi");
        std::fs::create_dir_all(&dir).unwrap();
        let mut blob = gz_bytes(b"first member ");
        blob.extend_from_slice(&gz_bytes(b"second member"));
        let p = dir.join("multi.gz");
        std::fs::write(&p, &blob).unwrap();
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        extract_gz(p.to_str().unwrap(), out.to_str().unwrap()).unwrap();
        let got = std::fs::read(out.join("multi")).unwrap();
        assert_eq!(String::from_utf8_lossy(&got), "first member second member");
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn empty_and_truncated_rejected() {
    let _g = progress_lock();
        let dir = tmp("reject");
        std::fs::create_dir_all(&dir).unwrap();
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let empty = dir.join("e.gz");
        std::fs::write(&empty, []).unwrap();
        assert!(extract_gz(empty.to_str().unwrap(), out.to_str().unwrap()).is_err());
        let bad = dir.join("t.gz");
        let mut blob = gz_bytes(b"hello");
        blob.truncate(blob.len() / 2);
        std::fs::write(&bad, &blob).unwrap();
        assert!(extract_gz(bad.to_str().unwrap(), out.to_str().unwrap()).is_err());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn lying_isize_bounds_output() {
    let _g = progress_lock();
        // The footer ISIZE is trusted as the output cap. Craft a stream that
        // really decodes to 1000 bytes but whose footer claims 100: the cap
        // must stop the copy at 100 bytes and fail, so a bomb can't write
        // beyond the declared size.
        let dir = tmp("bomb");
        std::fs::create_dir_all(&dir).unwrap();
        let big: Vec<u8> = vec![0xABu8; 1000];
        let mut blob = gz_bytes(&big);
        let body_len = blob.len();
        let isize = 100u32.to_le_bytes();
        blob[body_len - 4..].copy_from_slice(&isize);
        let p = dir.join("bomb.gz");
        std::fs::write(&p, &blob).unwrap();
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let res = extract_gz(p.to_str().unwrap(), out.to_str().unwrap());
        assert!(res.is_err(), "declared-size lie must fail, got {res:?}");
        let got = std::fs::read(out.join("bomb")).unwrap_or_default();
        assert!(got.len() <= 100, "output capped at declared size, got {}", got.len());
        std::fs::remove_dir_all(&dir).ok();
    }

    /// Cross-chunk magic detection (the counting reads in 64 KiB chunks):
    /// a `1F 8B 08` straddling a chunk boundary must be counted exactly once,
    /// whether it splits as `1F | 8B 08` (case a) or `1F 8B | 08` (case b).
    #[test]
    fn gzip_member_count_straddles_chunk_boundary() {
    let _g = progress_lock();
        let dir = tmp("straddle");
        std::fs::create_dir_all(&dir).unwrap();

        // Case a: `1F` is the LAST byte of chunk 1, `8B 08` at chunk 2 start.
        // Fill 65535 bytes so byte 65535 (0-indexed) lands at chunk boundary.
        let mut a = vec![0x41u8; 65535];
        a.push(0x1f);            // 65535 → this is chunk[1]'s last byte
        a.extend_from_slice(&[0x8b, 0x08, 0x00]); // next chunk starts here
        let pa = dir.join("a.gz");
        std::fs::write(&pa, &a).unwrap();
        // chunk1 = [0..65536): the 0x1f is at 65535. chunk2 = [65536..]: 8B 08 00
        assert_eq!(gzip_member_count(pa.to_str().unwrap()), 1, "case a straddle");

        // Case b: `1F 8B` are the last TWO bytes of chunk 1, `08` at start of 2.
        let mut b = vec![0x42u8; 65534];
        b.push(0x1f);
        b.push(0x8b);
        b.extend_from_slice(&[0x08, 0x00]);
        let pb = dir.join("b.gz");
        std::fs::write(&pb, &b).unwrap();
        assert_eq!(gzip_member_count(pb.to_str().unwrap()), 1, "case b straddle");

        // Non-straddling occurrences still counted (same chunk).
        let mut c = vec![0x43u8; 100];
        c.extend_from_slice(&[0x1f, 0x8b, 0x08]);
        let pc = dir.join("c.gz");
        std::fs::write(&pc, &c).unwrap();
        assert_eq!(gzip_member_count(pc.to_str().unwrap()), 1, "same-chunk magic");

        // Real two-member gzip is counted as 2 members (decodes fully).
        let mut two = gz_bytes(b"first member");
        two.extend_from_slice(&gz_bytes(b"second member"));
        let pt = dir.join("two.gz");
        std::fs::write(&pt, &two).unwrap();
        assert_eq!(gzip_member_count(pt.to_str().unwrap()), 2, "real two-member gzip");
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        extract_gz(pt.to_str().unwrap(), out.to_str().unwrap()).unwrap();
        let got = std::fs::read(out.join("two")).unwrap();
        assert_eq!(String::from_utf8_lossy(&got), "first membersecond member");
        std::fs::remove_dir_all(&dir).ok();
    }
}
