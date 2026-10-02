use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, jlong, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, extract_result_json, ProgressReader};
use archive_common::{extract_progress, compress_progress};
use std::fs::{self, File};
use std::io::{self, BufReader};
use std::path::Path;

fn output_name(input: &str) -> String {
    Path::new(input).file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_else(|| "output".to_string())
}

fn list_xz(input: &str) -> Result<String, String> {
    let name = output_name(input);
    Ok(format!(r#"[{{"n":"{}","s":0,"d":false,"e":false}}]"#, json_escape(&name)))
}

fn extract_xz(input: &str, output: &str) -> Result<u32, String> {
    let name = output_name(input);
    let dest = Path::new(output).join(&name);
    if let Some(p) = dest.parent() { fs::create_dir_all(p).map_err(|e| format!("{e}"))?; }
    let in_file = File::open(input).map_err(|e| format!("xz: {e}"))?;
    let in_len = in_file.metadata().map(|m| m.len()).unwrap_or(0);
    // The xz stream flags store no uncompressed size, so a write-side total is
    // unknowable — feeding OUTPUT bytes with total=0 left the UI on a spinner for
    // the whole run. Report the **read** side instead: total = archive size and
    // bytes = archive bytes consumed. Monotonic, and it reaches 100%.
    let r = BufReader::new(ProgressReader::extract(in_file));
    // No declared uncompressed size → bound output with the shared hard cap so a
    // crafted bomb can't fill disk. Deliberately NOT ProgressWriter::extract:
    // that would double-count against the read-side bytes fed above.
    let mut writer = archive_common::BoundedWriter::new(
        File::create(&dest).map_err(|e| format!("{e}"))?,
        archive_common::DEFAULT_EXTRACT_CAP,
    );
    extract_progress::reset(in_len);
    extract_progress::set_name(&name);
    extract_progress::set_file(in_len);
    let mut dec = xz2::read::XzDecoder::new(r);
    if let Err(e) = io::copy(&mut dec, &mut writer) {
        let _ = fs::remove_file(&dest);
        return Err(format!("xz: {e}"));
    }
    Ok(0)
}

fn compress_xz(input: &str, output: &str, level: i32) -> Result<u32, String> {
    let src = File::open(input).map_err(|e| format!("{e}"))?;
    let size = src.metadata().map(|m| m.len()).unwrap_or(0);
    let out_file = File::create(output).map_err(|e| format!("{e}"))?;
    let name = Path::new(input).file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
    compress_progress::reset(size);
    compress_progress::set_name(&name);
    compress_progress::set_file(size);
    // xz preset 0-9 (liblzma) — the compression-level setting now actually applies
    let mut enc = xz2::write::XzEncoder::new(out_file, level.clamp(0, 9) as u32);
    io::copy(&mut ProgressReader::compress(BufReader::new(src)), &mut enc).map_err(|e| format!("xz: {e}"))?;
    enc.finish().map_err(|e| format!("xz: {e}"))?;
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
pub fn extract_xz_host(input: &str, output: &str) -> Result<u32, String> {
    extract_xz(input, output)
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

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_XzCore_xzListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i);
    match guarded(move || list_xz(&inp)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_XzCore_xzExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = fs::create_dir_all(&out);
    match guarded(move || extract_xz(&inp, &out)) {
        Ok(f) => { let json = extract_result_json(1, if f == 0 { 1 } else { 0 }, f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_XzCore_xzExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_XzCore_xzExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_XzCore_xzExtractProgressFileCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_XzCore_xzExtractProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_XzCore_xzExtractProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_XzCore_xzExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_XzCore_xzCompress(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, lv: JString) -> jboolean {
    compress_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let lvl: i32 = s(&mut e, &lv).parse().unwrap_or(5);
    match guarded(move || compress_xz(&inp, &out, lvl)) {
        Ok(0) => JNI_TRUE,
        Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("xz: {f} failed")); JNI_FALSE }
        Err(er) => { let _ = e.throw_new("java/io/IOException", format!("xz: {er}")); JNI_FALSE }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_XzCore_xzCompressProgressCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_XzCore_xzCompressProgressTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_XzCore_xzCompressProgressFileCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_XzCore_xzCompressProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_XzCore_xzCompressProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&compress_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_XzCore_xzCompressCancel(_: JNIEnv, _: JClass) { compress_progress::cancel(); }

#[cfg(test)]
mod tests {
    use super::*;

    // extract_progress 是**全局静态量**，cargo 默认并行跑测试，
    // 两个用例各自 reset(total) 会把对方的 total 覆盖掉，断言随机失败。
    static LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
    fn lock() -> std::sync::MutexGuard<'static, ()> {
        LOCK.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn tmp(tag: &str) -> std::path::PathBuf {
        std::env::temp_dir().join(format!("uu_xz_{}_{}", std::process::id(), tag))
    }

    fn compress_bytes(data: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        let mut enc = xz2::write::XzEncoder::new(&mut out, 6);
        std::io::Write::write_all(&mut enc, data).unwrap();
        enc.finish().unwrap();
        out
    }

    #[test]
    fn compress_then_extract_round_trip() {
    let _g = lock();
        let dir = tmp("rt");
        std::fs::create_dir_all(&dir).unwrap();
        let data: Vec<u8> = (0..80_000u32).map(|i| (i % 251) as u8).collect();
        let xz = dir.join("a.xz");
        std::fs::write(&xz, compress_bytes(&data)).unwrap();
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        extract_xz(xz.to_str().unwrap(), out.to_str().unwrap()).unwrap();
        assert_eq!(std::fs::read(out.join("a")).unwrap(), data);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn empty_and_truncated_rejected() {
    let _g = lock();
        let dir = tmp("rej");
        std::fs::create_dir_all(&dir).unwrap();
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let empty = dir.join("e.xz");
        std::fs::write(&empty, []).unwrap();
        assert!(extract_xz(empty.to_str().unwrap(), out.to_str().unwrap()).is_err());
        let bad = dir.join("t.xz");
        let mut blob = compress_bytes(b"some xz data some xz data some xz data");
        blob.truncate(blob.len() / 2);
        std::fs::write(&bad, &blob).unwrap();
        assert!(extract_xz(bad.to_str().unwrap(), out.to_str().unwrap()).is_err());
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
        let arc = dir.join("in.bin.xz");
        let out = dir.join("out");
        std::fs::write(&src, &body).unwrap();
        compress_xz(src.to_str().unwrap(), arc.to_str().unwrap(), 6).unwrap();
        std::fs::create_dir_all(&out).unwrap();

        extract_progress::clear_cancel();
        extract_xz(arc.to_str().unwrap(), out.to_str().unwrap()).unwrap();

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
