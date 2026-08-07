use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, jlong, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, extract_result_json, ProgressWriter, ProgressReader};
use archive_common::{extract_progress, compress_progress};
use std::fs::{self, File};
use std::io::{BufReader, Read};
use std::path::Path;

fn output_name(input: &str) -> String {
    Path::new(input).file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_else(|| "output".to_string())
}

/// LZMA header bytes 6-13 hold the uncompressed size (u64 LE, all-FF = unknown).
fn decompressed_size(input: &str) -> u64 {
    let Ok(mut f) = File::open(input) else { return 0 };
    let mut hdr = [0u8; 14];
    if f.read_exact(&mut hdr).is_err() { return 0; }
    let size = u64::from_le_bytes(hdr[6..14].try_into().unwrap());
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
    let mut r = BufReader::new(File::open(input).map_err(|e| format!("lzma: {e}"))?);
    let mut writer = ProgressWriter::extract(File::create(&dest).map_err(|e| format!("{e}"))?);
    extract_progress::reset(decompressed_size(input));
    extract_progress::set_name(&name);
    extract_progress::set_file(extract_progress::total_bytes());
    lzma_rs::lzma_decompress(&mut r, &mut writer).map_err(|e| format!("lzma: {e}"))?;
    Ok(0)
}

fn compress_lzma(input: &str, output: &str, _level: i32) -> Result<u32, String> {
    let src = File::open(input).map_err(|e| format!("{e}"))?;
    let size = src.metadata().map(|m| m.len()).unwrap_or(0);
    let mut out_file = File::create(output).map_err(|e| format!("{e}"))?;
    let name = Path::new(input).file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
    compress_progress::reset(size);
    compress_progress::set_name(&name);
    compress_progress::set_file(size);
    let mut r = ProgressReader::compress(BufReader::new(src));
    lzma_rs::lzma_compress(&mut r, &mut out_file).map_err(|e| format!("lzma: {e}"))?;
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

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_LzmaCore_lzmaListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i);
    match guarded(move || list_lzma(&inp)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_LzmaCore_lzmaExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
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

    fn tmp(tag: &str) -> std::path::PathBuf {
        std::env::temp_dir().join(format!("uu_lzma_{}_{}", std::process::id(), tag))
    }

    fn compress_bytes(data: &[u8]) -> Vec<u8> {
        let mut src = &data[..];
        let mut out = Vec::new();
        lzma_rs::lzma_compress(&mut src, &mut out).unwrap();
        out
    }

    #[test]
    fn compress_then_extract_round_trip() {
        let dir = tmp("rt");
        std::fs::create_dir_all(&dir).unwrap();
        let data: Vec<u8> = (0..80_000u32).map(|i| (i % 251) as u8).collect();
        let lzma = dir.join("a.lzma");
        std::fs::write(&lzma, compress_bytes(&data)).unwrap();
        assert!(decompressed_size(lzma.to_str().unwrap()) > 0);
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
}
