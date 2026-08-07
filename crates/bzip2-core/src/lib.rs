use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, jlong, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, extract_result_json, ProgressWriter, ProgressReader};
use archive_common::{extract_progress, compress_progress};
use std::fs::{self, File};
use std::io::{self, BufReader, Read, Write};
use std::path::Path;

/// Streaming reader over oxiarc-bzip2's block API, with a cancel check
/// between blocks.
struct BzStream<R: Read> {
    decoder: oxiarc_bzip2::BzDecoder<R>,
    buf: Vec<u8>,
    pos: usize,
}

impl<R: Read> BzStream<R> {
    fn new(reader: R) -> Result<Self, String> {
        Ok(Self {
            decoder: oxiarc_bzip2::BzDecoder::new(reader).map_err(|e| format!("bzip2: {e}"))?,
            buf: Vec::new(),
            pos: 0,
        })
    }
}

impl<R: Read> Read for BzStream<R> {
    fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
        loop {
            if extract_progress::cancelled() {
                return Err(io::Error::new(io::ErrorKind::Interrupted, "cancelled"));
            }
            if self.pos < self.buf.len() {
                let n = out.len().min(self.buf.len() - self.pos);
                out[..n].copy_from_slice(&self.buf[self.pos..self.pos + n]);
                self.pos += n;
                return Ok(n);
            }
            match self.decoder.read_block() {
                Ok(Some(block)) => { self.buf = block; self.pos = 0; }
                Ok(None) => return Ok(0),
                Err(e) => return Err(io::Error::other(format!("bzip2: {e}"))),
            }
        }
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
    let mut dec = BzStream::new(BufReader::new(File::open(input).map_err(|e| format!("bzip2: {e}"))?))?;
    let mut writer = ProgressWriter::extract(File::create(&dest).map_err(|e| format!("{e}"))?);
    extract_progress::reset(0);
    extract_progress::set_name(&name);
    extract_progress::set_file(0);
    io::copy(&mut dec, &mut writer).map_err(|e| format!("bzip2: {e}"))?;
    Ok(0)
}

/// Streaming writer over oxiarc-bzip2's block API (write_block buffers
/// internally, so arbitrary-size writes coalesce into full bzip2 blocks).
struct BzWriter<W: Write>(oxiarc_bzip2::BzEncoder<W>);

impl<W: Write> Write for BzWriter<W> {
    fn write(&mut self, data: &[u8]) -> io::Result<usize> {
        self.0.write_block(data).map_err(|e| io::Error::other(format!("bzip2: {e}")))?;
        Ok(data.len())
    }
    fn flush(&mut self) -> io::Result<()> { Ok(()) }
}

fn compress_bz2(input: &str, output: &str, level: i32) -> Result<u32, String> {
    let src = File::open(input).map_err(|e| format!("{e}"))?;
    let size = src.metadata().map(|m| m.len()).unwrap_or(0);
    let out = File::create(output).map_err(|e| format!("{e}"))?;
    let lvl = oxiarc_bzip2::CompressionLevel::new(level.clamp(1, 9) as u8);
    let enc = oxiarc_bzip2::BzEncoder::new(out, lvl).map_err(|e| format!("bzip2: {e}"))?;
    let mut w = BzWriter(enc);
    let name = Path::new(input).file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
    compress_progress::reset(size);
    compress_progress::set_name(&name);
    compress_progress::set_file(size);
    io::copy(&mut ProgressReader::compress(src), &mut w).map_err(|e| format!("bzip2: {e}"))?;
    w.0.finish().map_err(|e| format!("bzip2: {e}"))?;
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

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Bzip2Core_bz2ListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i);
    match guarded(move || list_bz2(&inp)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Bzip2Core_bz2Extract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
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
}
