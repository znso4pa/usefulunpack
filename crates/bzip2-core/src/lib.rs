use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, jlong, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, extract_result_json, ProgressReader};
use archive_common::{extract_progress, compress_progress};
use std::fs::{self, File};
use std::io::{self, BufReader, Read};
use std::path::Path;

/// Read adapter that fails on cancel (checked per input read) without
/// counting progress bytes — the C `BzDecoder` pulls from this as needed.
struct CancelReader<R: Read>(R);

impl<R: Read> Read for CancelReader<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if extract_progress::cancelled() {
            return Err(io::Error::new(io::ErrorKind::Other, "cancelled"));
        }
        self.0.read(buf)
    }
}

fn output_name(input: &str) -> String {
    Path::new(input).file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_else(|| "output".to_string())
}

fn list_bz2(input: &str) -> Result<String, String> {
    let name = output_name(input);
    Ok(format!(r#"[{{"n":"{}","s":0,"d":false,"e":false}}]"#, json_escape(&name)))
}

fn extract_bz2(input: &str, output: &str) -> Result<u32, String> {
    let name = output_name(input);
    let dest = Path::new(output).join(&name);
    if let Some(p) = dest.parent() { fs::create_dir_all(p).map_err(|e| format!("{e}"))?; }
    let in_file = File::open(input).map_err(|e| format!("bzip2: {e}"))?;
    let in_len = in_file.metadata().map(|m| m.len()).unwrap_or(0);
    // The bzip2 header stores no uncompressed size, so a write-side total is
    // unknowable — feeding OUTPUT bytes with total=0 left the UI on a spinner for
    // the whole run. Report the **read** side instead: total = archive size and
    // bytes = archive bytes consumed. The bar then means "how much of the
    // archive we've chewed through", which is monotonic and reaches 100%.
    let file = BufReader::new(ProgressReader::extract(in_file));
    let mut dec = bzip2::read::BzDecoder::new(CancelReader(file));
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
    if let Err(e) = io::copy(&mut dec, &mut writer) {
        let _ = fs::remove_file(&dest);
        return Err(format!("bzip2: {e}"));
    }
    Ok(0)
}

fn compress_bz2(input: &str, output: &str, level: i32) -> Result<u32, String> {
    let src = File::open(input).map_err(|e| format!("{e}"))?;
    let size = src.metadata().map(|m| m.len()).unwrap_or(0);
    let out = File::create(output).map_err(|e| format!("{e}"))?;
    let lvl = bzip2::Compression::new(level.clamp(1, 9) as u32);
    let mut enc = bzip2::write::BzEncoder::new(out, lvl);
    let name = Path::new(input).file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
    compress_progress::reset(size);
    compress_progress::set_name(&name);
    compress_progress::set_file(size);
    io::copy(&mut ProgressReader::compress(src), &mut enc).map_err(|e| format!("bzip2: {e}"))?;
    enc.finish().map_err(|e| format!("bzip2: {e}"))?;
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
pub fn extract_bzip2_host(input: &str, output: &str) -> Result<u32, String> {
    extract_bz2(input, output)
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

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Bzip2Core_bz2ListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i);
    match guarded(move || list_bz2(&inp)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Bzip2Core_bz2Extract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = fs::create_dir_all(&out);
    match guarded(move || extract_bz2(&inp, &out)) {
        Ok(f) => { let json = extract_result_json(1, if f == 0 { 1 } else { 0 }, f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Bzip2Core_bz2ExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Bzip2Core_bz2ExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Bzip2Core_bz2ExtractProgressFileCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Bzip2Core_bz2ExtractProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Bzip2Core_bz2ExtractProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Bzip2Core_bz2ExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Bzip2Core_bz2Compress(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, lv: JString) -> jboolean {
    compress_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let lvl: i32 = s(&mut e, &lv).parse().unwrap_or(5);
    match guarded(move || compress_bz2(&inp, &out, lvl)) {
        Ok(0) => JNI_TRUE,
        Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("bzip2: {f} failed")); JNI_FALSE }
        Err(er) => { let _ = e.throw_new("java/io/IOException", format!("bzip2: {er}")); JNI_FALSE }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Bzip2Core_bz2CompressProgressCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Bzip2Core_bz2CompressProgressTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Bzip2Core_bz2CompressProgressFileCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Bzip2Core_bz2CompressProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Bzip2Core_bz2CompressProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&compress_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Bzip2Core_bz2CompressCancel(_: JNIEnv, _: JClass) { compress_progress::cancel(); }

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
        std::env::temp_dir().join(format!("uu_bz2_{}_{}", std::process::id(), tag))
    }

    #[test]
    fn compress_then_extract_round_trip() {
        let dir = tmp("rt");
        std::fs::create_dir_all(&dir).unwrap();
        let data: Vec<u8> = (0..120_000u32).map(|i| (i % 251) as u8).collect();
        let src = dir.join("a.bin");
        let bz = dir.join("a.bin.bz2");
        let out = dir.join("out");
        std::fs::write(&src, &data).unwrap();
        compress_bz2(src.to_str().unwrap(), bz.to_str().unwrap(), 6).unwrap();
        std::fs::create_dir_all(&out).unwrap();
        extract_bz2(bz.to_str().unwrap(), out.to_str().unwrap()).unwrap();
        assert_eq!(std::fs::read(out.join("a.bin")).unwrap(), data);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn empty_and_truncated_rejected() {
        let dir = tmp("rej");
        std::fs::create_dir_all(&dir).unwrap();
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let empty = dir.join("e.bz2");
        std::fs::write(&empty, []).unwrap();
        assert!(extract_bz2(empty.to_str().unwrap(), out.to_str().unwrap()).is_err());
        let bad = dir.join("t.bz2");
        let mut blob = {
            let src = dir.join("d");
            std::fs::write(&src, b"hello bzip2 world hello bzip2 world").unwrap();
            let b = dir.join("d.bz2");
            compress_bz2(src.to_str().unwrap(), b.to_str().unwrap(), 6).unwrap();
            std::fs::read(&b).unwrap()
        };
        blob.truncate(blob.len() / 2);
        std::fs::write(&bad, &blob).unwrap();
        assert!(extract_bz2(bad.to_str().unwrap(), out.to_str().unwrap()).is_err());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn system_bzip2_interop() {
        // Skip when no system bzip2 is available (not required for CI).
        let bz = ["/usr/bin/bzip2", "/opt/homebrew/bin/bzip2"].iter()
            .find(|p| std::path::Path::new(p).exists());
        let Some(bz_bin) = bz else { eprintln!("[interop] system bzip2 not found, skipped"); return };
        let dir = tmp("interop");
        std::fs::create_dir_all(&dir).unwrap();
        let data: Vec<u8> = (0..200_000u32).map(|i| (i.wrapping_mul(31) % 251) as u8).collect();
        let src = dir.join("p.bin");
        let bz2 = dir.join("p.bin.bz2");
        std::fs::write(&src, &data).unwrap();
        // Our encoder → system decoder
        compress_bz2(src.to_str().unwrap(), bz2.to_str().unwrap(), 6).unwrap();
        let sys_out = std::process::Command::new(bz_bin).arg("-dc").arg(&bz2).output().unwrap();
        assert!(sys_out.status.success(), "system bzip2 failed to decode our output");
        assert_eq!(sys_out.stdout, data, "system bzip2 decoded different bytes");
        // System encoder → our decoder
        let enc = std::process::Command::new(bz_bin)
            .arg("-kc").arg("-9").stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped()).spawn().unwrap();
        let mut child = enc;
        {
            let mut stdin = child.stdin.take().unwrap();
            std::io::Write::write_all(&mut stdin, &data).unwrap();
        }
        let out_bz = child.wait_with_output().unwrap();
        assert!(out_bz.status.success(), "system bzip2 encode failed");
        let sys_bz = dir.join("sys.bin.bz2");
        std::fs::write(&sys_bz, &out_bz.stdout).unwrap();
        let out_dir = dir.join("out");
        std::fs::create_dir_all(&out_dir).unwrap();
        extract_bz2(sys_bz.to_str().unwrap(), out_dir.to_str().unwrap()).unwrap();
        assert_eq!(std::fs::read(out_dir.join("sys.bin")).unwrap(), data, "our decoder mismatch");
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
        let arc = dir.join("in.bin.bz2");
        let out = dir.join("out");
        std::fs::write(&src, &body).unwrap();
        compress_bz2(src.to_str().unwrap(), arc.to_str().unwrap(), 6).unwrap();
        std::fs::create_dir_all(&out).unwrap();

        extract_progress::clear_cancel();
        extract_bz2(arc.to_str().unwrap(), out.to_str().unwrap()).unwrap();

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
