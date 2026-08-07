use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, jlong, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, extract_result_json, ProgressWriter, ProgressReader};
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

fn extract_zst(input: &str, output: &str) -> Result<u32, String> {
    let name = output_name(input);
    let dest = Path::new(output).join(&name);
    if let Some(p) = dest.parent() { fs::create_dir_all(p).map_err(|e| format!("{e}"))?; }
    let mut dec = ruzstd::decoding::StreamingDecoder::new(File::open(input).map_err(|e| format!("zstd: {e}"))?)
        .map_err(|e| format!("zstd: {e}"))?;
    let mut writer = ProgressWriter::extract(File::create(&dest).map_err(|e| format!("{e}"))?);
    extract_progress::reset(0);
    extract_progress::set_name(&name);
    extract_progress::set_file(0);
    io::copy(&mut dec, &mut writer).map_err(|e| format!("zstd: {e}"))?;
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

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZstdCore_zstListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i);
    match guarded(move || list_zst(&inp)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ZstdCore_zstExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
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
}
