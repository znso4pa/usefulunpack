use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, jlong, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, extract_result_json, ProgressReader};
use archive_common::{extract_progress, compress_progress};
use std::fs;
use std::io;
use std::path::Path;

fn output_name(input: &str) -> String {
    Path::new(input).file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_else(|| "output".to_string())
}

fn list_brotli(input: &str) -> Result<String, String> {
    let name = output_name(input);
    let size = fs::metadata(input).map(|m| m.len()).unwrap_or(0);
    // Brotli stores uncompressed size in the last 4 bytes (wrapper format) or unknown
    Ok(format!(r#"[{{"n":"{}","s":{},"d":false,"e":false}}]"#, json_escape(&name), size))
}

fn extract_brotli(input: &str, output: &str) -> Result<u32, String> {
    let name = output_name(input);
    let dest = Path::new(output).join(&name);
    if let Some(p) = dest.parent() { fs::create_dir_all(p).map_err(|e| format!("{e}"))?; }
    let r = std::fs::File::open(input).map_err(|e| format!("brotli: {e}"))?;
    let file_len = r.metadata().map(|m| m.len()).unwrap_or(0);
    // brotli has no in-header uncompressed size, so this used to feed OUTPUT
    // bytes against total = the COMPRESSED size — the denominator was simply the
    // wrong quantity. On a 3x-compressing file the bar hit 100% at 33% and then
    // sat there (Kotlin's coerceAtMost(100) hides the overshoot). Report the
    // **read** side instead: total = archive size, bytes = archive bytes
    // consumed. Same caliber as bzip2/xz/zstd.
    let mut reader = ProgressReader::extract(r);
    extract_progress::reset(file_len);
    extract_progress::set_name(&name);
    extract_progress::set_file(file_len);
    let mut decoder = brotli::Decompressor::new(&mut reader, 4096);
    let mut out_file = std::fs::File::create(&dest).map_err(|e| format!("{e}"))?;
    // Bound the output: brotli has no reliable in-header declared size, so a
    // crafted few-KB stream can't expand past the cap and fill disk.
    // Deliberately NOT ProgressWriter::extract — that would double-count against
    // the read-side bytes fed above.
    let mut out = archive_common::BoundedWriter::new(
        std::io::BufWriter::with_capacity(256 * 1024, &mut out_file),
        archive_common::DEFAULT_EXTRACT_CAP,
    );
    io::copy(&mut decoder, &mut out).map_err(|e| {
        let _ = fs::remove_file(&dest);
        format!("brotli: {e}")
    })?;
    Ok(0)
}

fn compress_brotli(input: &str, output: &str, level: i32) -> Result<u32, String> {
    let name = output_name(input);
    let dest = Path::new(output).join(&name);
    if let Some(p) = dest.parent() { fs::create_dir_all(p).map_err(|e| format!("{e}"))?; }
    let mut r = std::fs::File::open(input).map_err(|e| format!("brotli: {e}"))?;
    let file_len = r.metadata().map(|m| m.len()).unwrap_or(0);
    let q = level.clamp(0, 11) as u32;
    let w = std::fs::File::create(&dest).map_err(|e| format!("{e}"))?;
    let mut encoder = brotli::CompressorWriter::new(w, 4096, q, 22);
    compress_progress::reset(file_len);
    compress_progress::set_name(&name);
    compress_progress::set_file(file_len);
    io::copy(&mut archive_common::ProgressReader::compress(&mut r), &mut encoder).map_err(|e| format!("brotli: {e}"))?;
    drop(encoder);
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

#[doc(hidden)]
pub fn extract_brotli_host(input: &str, output: &str) -> Result<u32, String> {
    extract_brotli(input, output)
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

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_BrotliCore_brotliListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i);
    match guarded(move || list_brotli(&inp)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_BrotliCore_brotliExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = std::fs::create_dir_all(&out);
    match guarded(move || extract_brotli(&inp, &out)) { Ok(count) => { let json = extract_result_json(count, count, 0); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_BrotliCore_brotliCompress(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, lv: JString) -> jboolean {
    compress_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let level: i32 = s(&mut e, &lv).parse().unwrap_or(6);
    match guarded(move || compress_brotli(&inp, &out, level)) { Ok(_) => JNI_TRUE, Err(_) => JNI_FALSE }
}
// Extract progress
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_BrotliCore_brotliExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_BrotliCore_brotliExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_BrotliCore_brotliExtractProgressFileCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_BrotliCore_brotliExtractProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_BrotliCore_brotliExtractProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_BrotliCore_brotliExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }
// Compress progress
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_BrotliCore_brotliCompressProgressCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_BrotliCore_brotliCompressProgressTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_BrotliCore_brotliCompressProgressFileCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_BrotliCore_brotliCompressProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_BrotliCore_brotliCompressProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&compress_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_BrotliCore_brotliCompressCancel(_: JNIEnv, _: JClass) { compress_progress::cancel(); }

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
        std::env::temp_dir().join(format!("uu_brotli_{}_{}", std::process::id(), tag))
    }

    /// 字节级进度防回归。
    ///
    /// 旧实现喂**输出**字节却给不出分母（这些流的头部不存未压缩大小），
    /// 于是 `reset(0)` → UI 只能转圈；brotli 更糟，它拿**压缩后**大小当分母，
    /// 膨胀比 >1 时写到 ~33% 就满格然后卡住（Kotlin 的 coerceAtMost 把溢出藏了）。
    /// 现在统一走读侧：total = 归档大小，喂已消耗的输入字节。
    #[test]
    fn extract_progress_total_is_reported() {
        let _g = lock();
        let dir = tmp("prog");
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        // 高度可压缩：分母口径错的话，bytes/total 会明显偏离 1.0
        let body = b"uu-progress-caliber\n".repeat(4096);
        let src = dir.join("in.bin");
        // brotli 的 compress/extract 都把第二个参数当**输出目录**
        // （dest = output / output_name(input)，与 extract_bz2 等「文件路径」
        // 约定不同）。第一版按文件路径传，造出的归档根本不存在。
        let arc_dir = dir.join("packed");
        let out = dir.join("out");
        std::fs::write(&src, &body).unwrap();
        // 先用本 crate 的封包函数造**真** brotli 流：直接写原始字节会被
        // 解码器判成 Invalid Data（同样踩过 —— 测试根本没跑到断言）。
        compress_brotli(src.to_str().unwrap(), arc_dir.to_str().unwrap(), 6).unwrap();
        std::fs::create_dir_all(&out).unwrap();
        // output_name 取 file_stem：源是 in.bin → 产物名就是 "in"（无扩展名）
        let arc = arc_dir.join("in");

        extract_progress::clear_cancel();
        extract_brotli(arc.to_str().unwrap(), out.to_str().unwrap()).unwrap();

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
        let produced = std::fs::read(out.join("in")).unwrap();
        assert_eq!(produced.len(), body.len(), "decompressed size must be unchanged");
        assert_eq!(produced, body, "decompressed bytes must be identical");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
