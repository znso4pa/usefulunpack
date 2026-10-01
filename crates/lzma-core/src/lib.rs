use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, jlong, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, extract_result_json, ProgressReader};
use archive_common::{extract_progress, compress_progress};
use std::fs::{self, File};
use std::io::{self, BufReader, Read, Write};
use std::path::Path;

fn output_name(input: &str) -> String {
    Path::new(input).file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_else(|| "output".to_string())
}

/// LZMA header: bytes 0 = props, bytes 1-4 = dict_size, bytes 5-12 = unpacked_size.
fn decompressed_size(input: &str) -> u64 {
    let Ok(mut f) = File::open(input) else { return 0 };
    let mut hdr = [0u8; 13];
    if f.read_exact(&mut hdr).is_err() { return 0; }
    let size = u64::from_le_bytes(hdr[5..13].try_into().unwrap());
    if size == u64::MAX { 0 } else { size }
}

fn list_lzma(input: &str) -> Result<String, String> {
    let name = output_name(input);
    Ok(format!(r#"[{{"n":"{}","s":{},"d":false,"e":false}}]"#, json_escape(&name), decompressed_size(input)))
}

fn extract_lzma(input: &str, output: &str) -> Result<u32, String> {
    let name = output_name(input);
    let dest = Path::new(output).join(&name);
    if let Some(p) = dest.parent() { fs::create_dir_all(p).map_err(|e| format!("{e}"))?; }
    let in_file = File::open(input).map_err(|e| format!("lzma: {e}"))?;
    let in_len = in_file.metadata().map(|m| m.len()).unwrap_or(0);
    // .lzma's header has an uncompressed-size field, but it is routinely -1
    // ("unknown") — every real file in files4testing is. The old code fed OUTPUT
    // bytes with total=declared, so those files ran at total=0 and the UI showed a
    // spinner for the whole extraction.
    //
    // Unify on the **read** caliber (total = archive size, bytes = archive bytes
    // consumed), same as brotli/bzip2/xz/zstd. Keeping a conditional write-side
    // path would need two different writer types behind one variable and risks
    // double-counting; one caliber is simpler and always has a denominator.
    // NOTE the nesting: ProgressReader wraps the raw File, NOT the BufReader.
    // `ProgressReader` also implements BufRead, so wrapping a BufReader lets the
    // decoder satisfy reads from an 8 KB buffer whose fill_buf doesn't count and
    // whose consume only fires on consumption — the counter drifted both short of
    // and (via readahead) past the archive size. Counting the raw file's reads
    // keeps `bytes` exactly equal to what was consumed.
    let mut r = BufReader::with_capacity(64 * 1024, ProgressReader::extract(in_file));
    // Cap the decompressed output so a crafted stream can't fill disk: honor the
    // header-declared size when present, otherwise the shared hard cap.
    let declared = decompressed_size(input);
    let cap = if declared > 0 { declared } else { archive_common::DEFAULT_EXTRACT_CAP };
    // NOT ProgressWriter::extract — that would double-count against the read-side
    // bytes already fed by ProgressReader above.
    let mut writer = archive_common::BoundedWriter::new(
        File::create(&dest).map_err(|e| format!("{e}"))?,
        cap,
    );
    extract_progress::reset(in_len);
    extract_progress::set_name(&name);
    extract_progress::set_file(in_len);
    lzma_rs::lzma_decompress(&mut r, &mut writer).map_err(|e| {
        let _ = fs::remove_file(&dest);
        format!("lzma: {e}")
    })?;
    // lzma_rs stops reading short of EOF (it knows the uncompressed size), so the
    // counter ends short of the total and "done" still looks like "stalled". The
    // operation IS complete — close the remaining gap so the bar lands on 100%.
    // Only ever adds: with the nesting fixed above, `bytes` can no longer exceed
    // the total, so a one-sided correction is correct.
    let fed = extract_progress::bytes();
    if in_len > fed {
        extract_progress::add_bytes(in_len - fed);
    }
    Ok(0)
}

/// Streaming LZMA (.lzma / lzma_alone) encoder over liblzma (lzma-sys).
/// The preset (level 0-9) now actually applies and encoding is far faster than
/// the pure-Rust lzma-rs "dumb" encoder (which ignored the level entirely).
fn lzma_alone_compress(src: &mut dyn Read, dst: &mut dyn Write, level: i32) -> io::Result<()> {
    unsafe {
        let mut stream: lzma_sys::lzma_stream = std::mem::zeroed();
        let mut opt: lzma_sys::lzma_options_lzma = std::mem::zeroed();
        // NOTE: this liblzma binding reports 0 for lzma_lzma_preset even on
        // success; validity is confirmed by lzma_alone_encoder returning LZMA_OK.
        lzma_sys::lzma_lzma_preset(&mut opt, level.clamp(0, 9) as u32);
        if lzma_sys::lzma_alone_encoder(&mut stream, &opt) != lzma_sys::LZMA_OK {
            return Err(io::Error::other("lzma_alone_encoder failed"));
        }
        let r = encode_loop(&mut stream, src, dst);
        lzma_sys::lzma_end(&mut stream);
        r
    }
}

fn encode_loop(stream: &mut lzma_sys::lzma_stream, src: &mut dyn Read, dst: &mut dyn Write) -> io::Result<()> {
    let mut inbuf = vec![0u8; 65536];
    let mut outbuf = vec![0u8; 65536];
    let mut action = lzma_sys::LZMA_RUN;
    loop {
        if stream.avail_in == 0 && action == lzma_sys::LZMA_RUN {
            let n = src.read(&mut inbuf)?;
            stream.next_in = inbuf.as_ptr();
            stream.avail_in = n;
            if n == 0 { action = lzma_sys::LZMA_FINISH; }
        }
        stream.next_out = outbuf.as_mut_ptr();
        stream.avail_out = outbuf.len();
        let ret = unsafe { lzma_sys::lzma_code(stream, action) };
        let produced = outbuf.len() - stream.avail_out;
        if produced > 0 { dst.write_all(&outbuf[..produced])?; }
        if ret == lzma_sys::LZMA_STREAM_END { return Ok(()); }
        if ret != lzma_sys::LZMA_OK { return Err(io::Error::other("lzma_code failed")); }
    }
}

fn compress_lzma(input: &str, output: &str, level: i32) -> Result<u32, String> {
    let src = File::open(input).map_err(|e| format!("{e}"))?;
    let size = src.metadata().map(|m| m.len()).unwrap_or(0);
    let out_file = File::create(output).map_err(|e| format!("{e}"))?;
    let name = Path::new(input).file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
    compress_progress::reset(size);
    compress_progress::set_name(&name);
    compress_progress::set_file(size);
    let mut r = ProgressReader::compress(BufReader::new(src));
    let mut w = io::BufWriter::new(out_file);
    lzma_alone_compress(&mut r, &mut w, level).map_err(|e| format!("lzma: {e}"))?;
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
pub fn extract_lzma_host(input: &str, output: &str) -> Result<u32, String> {
    extract_lzma(input, output)
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

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_LzmaCore_lzmaListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i);
    match guarded(move || list_lzma(&inp)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_LzmaCore_lzmaExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = fs::create_dir_all(&out);
    match guarded(move || extract_lzma(&inp, &out)) {
        Ok(f) => { let json = extract_result_json(1, if f == 0 { 1 } else { 0 }, f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_LzmaCore_lzmaExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_LzmaCore_lzmaExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_LzmaCore_lzmaExtractProgressFileCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_LzmaCore_lzmaExtractProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_LzmaCore_lzmaExtractProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_LzmaCore_lzmaExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_LzmaCore_lzmaCompress(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, lv: JString) -> jboolean {
    compress_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let lvl: i32 = s(&mut e, &lv).parse().unwrap_or(5);
    match guarded(move || compress_lzma(&inp, &out, lvl)) {
        Ok(0) => JNI_TRUE,
        Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("lzma: {f} failed")); JNI_FALSE }
        Err(er) => { let _ = e.throw_new("java/io/IOException", format!("lzma: {er}")); JNI_FALSE }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_LzmaCore_lzmaCompressProgressCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_LzmaCore_lzmaCompressProgressTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_LzmaCore_lzmaCompressProgressFileCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_LzmaCore_lzmaCompressProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_LzmaCore_lzmaCompressProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&compress_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_LzmaCore_lzmaCompressCancel(_: JNIEnv, _: JClass) { compress_progress::cancel(); }

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
        std::env::temp_dir().join(format!("uu_lzma_{}_{}", std::process::id(), tag))
    }

    fn compress_bytes(data: &[u8]) -> Vec<u8> {
        let mut src = &data[..];
        let mut out = Vec::new();
        lzma_alone_compress(&mut src, &mut out, 6).unwrap();
        out
    }

    #[test]
    fn compress_then_extract_round_trip() {
        let dir = tmp("rt");
        std::fs::create_dir_all(&dir).unwrap();
        let data: Vec<u8> = (0..80_000u32).map(|i| (i % 251) as u8).collect();
        let lzma = dir.join("a.lzma");
        std::fs::write(&lzma, compress_bytes(&data)).unwrap();
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        extract_lzma(lzma.to_str().unwrap(), out.to_str().unwrap()).unwrap();
        assert_eq!(std::fs::read(out.join("a")).unwrap(), data);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn empty_and_truncated_rejected() {
        let dir = tmp("rej");
        std::fs::create_dir_all(&dir).unwrap();
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let empty = dir.join("e.lzma");
        std::fs::write(&empty, []).unwrap();
        assert!(extract_lzma(empty.to_str().unwrap(), out.to_str().unwrap()).is_err());
        let bad = dir.join("t.lzma");
        let mut blob = compress_bytes(b"some lzma data some lzma data");
        blob.truncate(blob.len() / 2);
        std::fs::write(&bad, &blob).unwrap();
        assert!(extract_lzma(bad.to_str().unwrap(), out.to_str().unwrap()).is_err());
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
        let arc = dir.join("in.bin.lzma");
        let out = dir.join("out");
        std::fs::write(&src, &body).unwrap();
        compress_lzma(src.to_str().unwrap(), arc.to_str().unwrap(), 6).unwrap();
        std::fs::create_dir_all(&out).unwrap();

        extract_progress::clear_cancel();
        extract_lzma(arc.to_str().unwrap(), out.to_str().unwrap()).unwrap();

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
