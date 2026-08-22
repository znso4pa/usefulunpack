use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, jlong, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, extract_result_json, ProgressWriter};
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
    let mut r = std::fs::File::open(input).map_err(|e| format!("brotli: {e}"))?;
    let file_len = r.metadata().map(|m| m.len()).unwrap_or(0);
    extract_progress::reset(file_len);
    extract_progress::set_name(&name);
    extract_progress::set_file(0);
    let mut decoder = brotli::Decompressor::new(&mut r, 4096);
    let mut out_file = std::fs::File::create(&dest).map_err(|e| format!("{e}"))?;
    // Bound the output: brotli has no reliable in-header declared size, so a
    // crafted few-KB stream can't expand past the cap and fill disk.
    let mut out = ProgressWriter::extract(archive_common::BoundedWriter::new(
        std::io::BufWriter::with_capacity(256 * 1024, &mut out_file),
        archive_common::DEFAULT_EXTRACT_CAP,
    ));
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
