use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, jlong, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, extract_result_json, ProgressReader};
use archive_common::{extract_progress, compress_progress};
use std::fs::{self, File};
use std::io;
use std::path::Path;

fn output_name(input: &str) -> String {
    Path::new(input).file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_else(|| "output".to_string())
}

fn list_zst(input: &str) -> Result<String, String> {
    let name = output_name(input);
    Ok(format!(r#"[{{"n":"{}","s":0,"d":false,"e":false}}]"#, json_escape(&name)))
}

/// 在内存 cursor 上喂**读侧**字节进度。
///
/// zstd 把整个输入读进内存再解（多帧需要回看游标），所以不能用
/// `ProgressReader`（它只实现 `Read`，而这里还要 `Seek`/position）。
/// 这个包装只实现 `Read` 并把消费掉的字节喂给 `extract_progress`，
/// 同时保留 `pos()` 供多帧循环判断是否还有下一帧。
struct FrameCursor<'a> {
    c: std::io::Cursor<&'a Vec<u8>>,
}

impl<'a> FrameCursor<'a> {
    fn pos(&self) -> usize {
        self.c.position() as usize
    }
}

impl<'a> std::io::Read for FrameCursor<'a> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        if extract_progress::cancelled() {
            return Err(std::io::Error::new(std::io::ErrorKind::Other, "cancelled"));
        }
        let n = self.c.read(buf)?;
        extract_progress::add_bytes(n as u64);
        Ok(n)
    }
}

/// Upper bound on the window a zstd frame may declare, in bytes.
///
/// The window is a decode-side ring buffer sized from the frame header *before*
/// any content is validated, so it must stay bounded. 1 GiB covers the real
/// spectrum — zstd's own `--long` mode tops out at 8 GiB windows but that is
/// never used for these archives — while accepting the 128 MB windows that
/// level-22+ frames emit. ruzstd's own default is 100 MB, which rejects them.
const ZSTD_MAX_WINDOW: u64 = 1024 * 1024 * 1024;

fn extract_zst(input: &str, output: &str) -> Result<u32, String> {
    let name = output_name(input);
    let dest = Path::new(output).join(&name);
    if let Some(p) = dest.parent() { fs::create_dir_all(p).map_err(|e| format!("{e}"))?; }

    // Multi-frame zstd: StreamingDecoder only handles a single frame. After the
    // first frame ends (decoder returns0 bytes), we recreate the decoder for the
    // next frame. Each concatenated frame starts with the zstd magic number.
    let meta = fs::metadata(input).map_err(|e| format!("zstd: {e}"))?;
    if meta.len() > 512 * 1024 * 1024 {
        return Err("zstd: file too large (>512MB), streaming not supported".to_string());
    }
    let all_bytes = fs::read(input).map_err(|e| format!("zstd: {e}"))?;
    let in_len = all_bytes.len() as u64;
    let mut cursor = FrameCursor { c: std::io::Cursor::new(&all_bytes) };

    // A zstd frame header stores no uncompressed size, so a write-side total is
    // unknowable — feeding OUTPUT bytes with total=0 left the UI on a spinner for
    // the whole run. Report the **read** side instead: total = archive size and
    // bytes = archive bytes consumed (FrameCursor feeds those). Monotonic, and it
    // reaches 100% at end of input.
    let mut writer = archive_common::BoundedWriter::new(
        File::create(&dest).map_err(|e| format!("{e}"))?,
        archive_common::DEFAULT_EXTRACT_CAP,
    );
    // Any failure below must not leave a half-written file behind: the caller
    // only sees the Err, so the leftover would look to the user (and to a later
    // "did it extract?" check) like a successful extraction. Verified against
    // faults/truncated-rawfile1.zst-19.zst, which previously left
    // `truncated-rawfile1.zst-19` on disk.
    let fail = |e: String| -> String {
        let _ = fs::remove_file(&dest);
        e
    };
    extract_progress::reset(in_len);
    extract_progress::set_name(&name);
    extract_progress::set_file(in_len);

    // Decode first frame.
    //
    // `StreamingDecoder::new` uses ruzstd's DEFAULT_MAX_WINDOW_SIZE = 100MB,
    // which silently REJECTS high-level frames: zstd level 22 writes
    // window_log=27 (128MB), so every `.zst-22` sample in files4testing failed
    // while the system zstd decoded it fine. The spec permits far larger windows,
    // so raise the limit explicitly. Still bounded — see ZSTD_MAX_WINDOW below —
    // because the window is allocated before any of it is validated.
    let mut dec = ruzstd::decoding::StreamingDecoder::new_with_max_window_size(
        &mut cursor,
        ZSTD_MAX_WINDOW,
    )
    .map_err(|e| fail(format!("zstd: {e}")))?;
    io::copy(&mut dec, &mut writer).map_err(|e| fail(format!("zstd: {e}")))?;

    // Try additional frames (multi-frame concatenation)
    while cursor.pos() < all_bytes.len() {
        match ruzstd::decoding::StreamingDecoder::new_with_max_window_size(&mut cursor, ZSTD_MAX_WINDOW) {
            Ok(mut dec2) => {
                if io::copy(&mut dec2, &mut writer).map_err(|e| fail(format!("zstd: {e}")))? == 0 {
                    break; // empty frame, no more data
                }
            }
            Err(_) => break, // not a valid frame header, done
        }
    }

    Ok(0)
}

fn compress_zst(input: &str, output: &str, level: i32) -> Result<u32, String> {
    let src = File::open(input).map_err(|e| format!("zstd: {e}"))?;
    let size = src.metadata().map(|m| m.len()).unwrap_or(0);
    let out = File::create(output).map_err(|e| format!("{e}"))?;
    let level = if level < 1 { 3 } else { level.min(22) };
    let mut enc = oxiarc_zstd::ZstdStreamEncoder::new(out, level);
    let name = Path::new(input).file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
    compress_progress::reset(size);
    compress_progress::set_name(&name);
    compress_progress::set_file(size);
    io::copy(&mut ProgressReader::compress(src), &mut enc).map_err(|e| format!("zstd: {e}"))?;
    enc.finish().map_err(|e| format!("zstd: {e}"))?;
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
pub fn extract_zstd_host(input: &str, output: &str) -> Result<u32, String> {
    extract_zst(input, output)
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

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZstdCore_zstListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i);
    match guarded(move || list_zst(&inp)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZstdCore_zstExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = fs::create_dir_all(&out);
    match guarded(move || extract_zst(&inp, &out)) {
        Ok(f) => { let json = extract_result_json(1, if f == 0 { 1 } else { 0 }, f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZstdCore_zstExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZstdCore_zstExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZstdCore_zstExtractProgressFileCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZstdCore_zstExtractProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZstdCore_zstExtractProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZstdCore_zstExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZstdCore_zstCompress(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, lv: JString) -> jboolean {
    compress_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let lvl: i32 = s(&mut e, &lv).parse().unwrap_or(5);
    match guarded(move || compress_zst(&inp, &out, lvl)) {
        Ok(0) => JNI_TRUE,
        Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("zstd: {f} failed")); JNI_FALSE }
        Err(er) => { let _ = e.throw_new("java/io/IOException", format!("zstd: {er}")); JNI_FALSE }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZstdCore_zstCompressProgressCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZstdCore_zstCompressProgressTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZstdCore_zstCompressProgressFileCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZstdCore_zstCompressProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZstdCore_zstCompressProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&compress_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZstdCore_zstCompressCancel(_: JNIEnv, _: JClass) { compress_progress::cancel(); }

#[cfg(test)]
mod tests {
    use super::*;

    // extract_progress 是**全局静态量**，cargo 默认并行跑测试，
    // 两个用例各自 reset(total) 会把对方的 total 覆盖掉，断言随机失败。
    static LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
    fn lock() -> std::sync::MutexGuard<'static, ()> {
        LOCK.lock().unwrap_or_else(|e| e.into_inner())
    }

    use std::io::Write as _;

    fn tmp(tag: &str) -> std::path::PathBuf {
        std::env::temp_dir().join(format!("uu_zst_{}_{}", std::process::id(), tag))
    }

    fn zst_bytes(data: &[u8]) -> Vec<u8> {
        let mut enc = oxiarc_zstd::ZstdStreamEncoder::new(Vec::new(), 6);
        enc.write_all(data).unwrap();
        enc.finish().unwrap()
    }

    #[test]
    fn compress_then_extract_round_trip() {
        let dir = tmp("rt");
        std::fs::create_dir_all(&dir).unwrap();
        let data: Vec<u8> = (0..90_000u32).map(|i| (i % 251) as u8).collect();
        let zst = dir.join("a.zst");
        std::fs::write(&zst, zst_bytes(&data)).unwrap();
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        extract_zst(zst.to_str().unwrap(), out.to_str().unwrap()).unwrap();
        assert_eq!(std::fs::read(out.join("a")).unwrap(), data);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn empty_and_truncated_rejected() {
        let dir = tmp("rej");
        std::fs::create_dir_all(&dir).unwrap();
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let empty = dir.join("e.zst");
        std::fs::write(&empty, []).unwrap();
        assert!(extract_zst(empty.to_str().unwrap(), out.to_str().unwrap()).is_err());
        let bad = dir.join("t.zst");
        let mut blob = zst_bytes(b"some zstd data some zstd data some zstd data");
        blob.truncate(blob.len() / 2);
        std::fs::write(&bad, &blob).unwrap();
        assert!(extract_zst(bad.to_str().unwrap(), out.to_str().unwrap()).is_err());
        std::fs::remove_dir_all(&dir).ok();
    }


    /// 字节级进度防回归。
    ///
    /// 旧实现喂**输出**字节却拿不到分母（这些流的头部不存未压缩大小）：
    /// `reset(0)` → UI 只能转圈；brotli 更糟，它拿**压缩后**大小当分母，
    /// 膨胀比 >1 时写到 ~33% 就满格卡住（Kotlin 的 coerceAtMost(100) 把溢出藏了）。
    /// 现在统一走读侧：total = 归档大小，喂已消耗的输入字节。
    #[test]
    fn extract_progress_total_is_reported() {
        let _g = lock();
        let dir = tmp("prog");
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        // 高度可压缩：分母口径错的话 bytes/total 会明显偏离 1.0
        let body = b"uu-progress-caliber\n".repeat(4096);
        let src = dir.join("in.bin");
        let arc = dir.join("in.bin.zst");
        let out = dir.join("out");
        std::fs::write(&src, &body).unwrap();
        compress_zst(src.to_str().unwrap(), arc.to_str().unwrap(), 6).unwrap();
        std::fs::create_dir_all(&out).unwrap();

        extract_progress::clear_cancel();
        extract_zst(arc.to_str().unwrap(), out.to_str().unwrap()).unwrap();

        let total = extract_progress::total_bytes();
        assert!(
            total > 0,
            "extract must report a non-zero total, else the UI spins forever"
        );
        assert_eq!(
            extract_progress::bytes(),
            total,
            "fed bytes must converge to total (read caliber = archive bytes consumed)"
        );
        // 载荷必须逐字节不变 —— 证明进度改造没动到解码结果
        let produced = std::fs::read(out.join("in.bin")).unwrap();
        assert_eq!(produced.len(), body.len(), "decompressed size must be unchanged");
        assert_eq!(produced, body, "decompressed bytes must be identical");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
