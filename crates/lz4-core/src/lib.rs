use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, jlong, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, extract_result_json, ProgressWriter, ProgressReader};
use archive_common::{extract_progress, compress_progress};
use std::fs::File;
use std::io::{self, Read};
use lz4_flex::frame::{FrameDecoder, FrameEncoder, FrameInfo};
use std::path::Path;

fn list_lz4_inner(input: &str) -> Result<String, String> {
    let name = std::path::Path::new(input)
        .file_stem().map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| "decompressed".to_string());
    let size = lz4_content_size(input)?.unwrap_or(0);
    Ok(format!(r#"[{{"n":"{}","s":{},"d":false,"e":false}}]"#, json_escape(&name), size))
}

fn decompress_lz4_inner(input: &str, output: &str) -> Result<u32, String> {
    let name = std::path::Path::new(input)
        .file_stem().map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| "output".to_string());
    let out_path = std::path::Path::new(output).join(&name);
    if let Some(p) = out_path.parent() { std::fs::create_dir_all(p).map_err(|e| format!("lz4: {e}"))?; }

    let file = std::fs::File::open(input).map_err(|e| format!("lz4: {e}"))?;
    let mut decoder = CancellableReader(FrameDecoder::new(file));
    let out_file = std::fs::File::create(&out_path).map_err(|e| format!("lz4: {e}"))?;
    let mut writer = ProgressWriter::extract(out_file);

    extract_progress::reset(lz4_content_size(input)?.unwrap_or(0));
    extract_progress::set_name(&name);
    extract_progress::set_file(extract_progress::total_bytes());
    std::io::copy(&mut decoder, &mut writer).map_err(|e| format!("lz4: {e}"))?;
    Ok(0)
}

/// Checks the cancellation flag on every read so a huge single-file
/// decompression can be aborted mid-stream.
struct CancellableReader<R: Read>(R);

impl<R: Read> Read for CancellableReader<R> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        if extract_progress::cancelled() {
            return Err(std::io::Error::new(std::io::ErrorKind::Interrupted, "cancelled"));
        }
        self.0.read(buf)
    }
}

/// Reads the LZ4 frame header and returns the declared decompressed size, if
/// present. Only inspects the header — no decompression happens.
fn lz4_content_size(input: &str) -> Result<Option<u64>, String> {
    use std::io::Seek;
    let mut f = std::fs::File::open(input).map_err(|e| format!("lz4: {e}"))?;
    let mut magic = [0u8; 4];
    f.read_exact(&mut magic).map_err(|e| format!("lz4: {e}"))?;
    // Frame magic 0x184D2204 (LE), legacy magic 0x184C2102 (LE).
    if magic == [0x04, 0x22, 0x4D, 0x18] {
        let mut flg = [0u8; 1];
        f.read_exact(&mut flg).map_err(|e| format!("lz4: {e}"))?;
        let mut _bd = [0u8; 1];
        f.read_exact(&mut _bd).map_err(|e| format!("lz4: {e}"))?;
        if flg[0] & 0x08 != 0 {
            let mut cs = [0u8; 8];
            f.read_exact(&mut cs).map_err(|e| format!("lz4: {e}"))?;
            return Ok(Some(u64::from_le_bytes(cs)));
        }
        return Ok(None);
    }
    if magic == [0x02, 0x21, 0x4C, 0x18] {
        return Ok(None);
    }
    // Not an LZ4 frame: treat as unknown size rather than failing the listing.
    let _ = f.seek(std::io::SeekFrom::Start(0));
    Ok(None)
}

fn compress_lz4(input: &str, output: &str, _level: i32) -> Result<u32, String> {
    let src = File::open(input).map_err(|e| format!("lz4: {e}"))?;
    let size = src.metadata().map(|m| m.len()).unwrap_or(0);
    let out = std::fs::File::create(output).map_err(|e| format!("lz4: {e}"))?;
    let name = Path::new(input).file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
    let info = FrameInfo::new().content_size(Some(size));
    let mut enc = FrameEncoder::with_frame_info(info, out);
    compress_progress::reset(size);
    compress_progress::set_name(&name);
    compress_progress::set_file(size);
    io::copy(&mut ProgressReader::compress(src), &mut enc).map_err(|e| format!("lz4: {e}"))?;
    enc.finish().map_err(|e| format!("lz4: {e}"))?;
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

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Lz4Core_lz4ListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i);
    match guarded(move || list_lz4_inner(&inp)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("{er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Lz4Core_lz4Extract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = std::fs::create_dir_all(&out);
    match guarded(move || decompress_lz4_inner(&inp, &out)) { Ok(f) => { let json = extract_result_json(1, if f==0{1}else{0}, f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Lz4Core_lz4ExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Lz4Core_lz4ExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Lz4Core_lz4ExtractProgressFileCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Lz4Core_lz4ExtractProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Lz4Core_lz4ExtractProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Lz4Core_lz4ExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Lz4Core_lz4Compress(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, lv: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let lvl: i32 = s(&mut e, &lv).parse().unwrap_or(5);
    match guarded(move || compress_lz4(&inp, &out, lvl)) {
        Ok(0) => JNI_TRUE,
        Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("lz4: {f} failed")); JNI_FALSE }
        Err(er) => { let _ = e.throw_new("java/io/IOException", format!("lz4: {er}")); JNI_FALSE }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Lz4Core_lz4CompressProgressCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Lz4Core_lz4CompressProgressTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Lz4Core_lz4CompressProgressFileCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Lz4Core_lz4CompressProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Lz4Core_lz4CompressProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&compress_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Lz4Core_lz4CompressCancel(_: JNIEnv, _: JClass) { compress_progress::cancel(); }

#[cfg(test)]
mod tests {
    use super::*;
    use lz4_flex::frame::{FrameEncoder, FrameInfo};
    use std::io::Write as _;

    fn make_frame(path: &std::path::Path, data: &[u8], with_content_size: bool) {
        let file = std::fs::File::create(path).unwrap();
        let info = if with_content_size {
            FrameInfo::new().content_size(Some(data.len() as u64))
        } else {
            FrameInfo::new()
        };
        let mut enc = FrameEncoder::with_frame_info(info, file);
        enc.write_all(data).unwrap();
        enc.finish().unwrap();
    }

    #[test]
    fn content_size_reads_header_without_decompressing() {
        let dir = std::env::temp_dir().join(format!("uu_lz4_hdr_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let p = dir.join("with_size.lz4");
        make_frame(&p, &[7u8; 123456], true);
        assert_eq!(lz4_content_size(p.to_str().unwrap()).unwrap(), Some(123456));
        // legacy magic → None
        let p2 = dir.join("no_size.lz4");
        make_frame(&p2, &[1u8; 64], false);
        assert_eq!(lz4_content_size(p2.to_str().unwrap()).unwrap(), None);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn extract_streams_and_matches_input() {
        let data: Vec<u8> = (0..300_000u32).map(|i| (i % 251) as u8).collect();
        let dir = std::env::temp_dir().join(format!("uu_lz4_x_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let input = dir.join("test.bin.lz4");
        make_frame(&input, &data, true);
        let output = dir.join("out");
        std::fs::create_dir_all(&output).unwrap();

        let list = list_lz4_inner(input.to_str().unwrap()).unwrap();
        assert!(list.contains("300000"), "list: {list}");
        assert!(list.contains("test.bin"));

        decompress_lz4_inner(input.to_str().unwrap(), output.to_str().unwrap()).unwrap();
        let out_data = std::fs::read(output.join("test.bin")).unwrap();
        assert_eq!(out_data, data);
        std::fs::remove_dir_all(&dir).ok();
    }
}

#[cfg(test)]
mod compress_tests {
    use super::*;

    #[test]
    fn compress_then_extract_round_trip() {
        let dir = std::env::temp_dir().join(format!("uu_lz4c_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let data: Vec<u8> = (0..200_000u32).map(|i| (i % 251) as u8).collect();
        let src = dir.join("c.bin");
        let lz4 = dir.join("c.bin.lz4");
        let out = dir.join("out");
        std::fs::write(&src, &data).unwrap();
        compress_lz4(src.to_str().unwrap(), lz4.to_str().unwrap(), 1).unwrap();
        // frame header carries content size
        assert_eq!(lz4_content_size(lz4.to_str().unwrap()).unwrap(), Some(data.len() as u64));
        std::fs::create_dir_all(&out).unwrap();
        decompress_lz4_inner(lz4.to_str().unwrap(), out.to_str().unwrap()).unwrap();
        assert_eq!(std::fs::read(out.join("c.bin")).unwrap(), data);
        std::fs::remove_dir_all(&dir).ok();
    }
}
