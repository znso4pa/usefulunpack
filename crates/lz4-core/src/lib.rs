use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jlong};
use archive_common::{s, json_escape, extract_result_json};
use archive_common::extract_progress;
use std::io::Read;

fn list_lz4_inner(input: &str) -> Result<String, String> {
    let name = std::path::Path::new(input)
        .file_stem().map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| "decompressed".to_string());

    let mut file = std::fs::File::open(input).map_err(|e| format!("lz4: {e}"))?;
    let mut compressed = Vec::new();
    file.read_to_end(&mut compressed).map_err(|e| format!("lz4: {e}"))?;
    let mut decoder = lz4_flex::frame::FrameDecoder::new(&compressed[..]);
    let mut decompressed = Vec::new();
    decoder.read_to_end(&mut decompressed).map_err(|e| format!("lz4: {e}"))?;

    Ok(format!(r#"[{{"n":"{}","s":{},"d":false,"e":false}}]"#, json_escape(&name), decompressed.len()))
}

fn decompress_lz4_inner(input: &str, output: &str) -> Result<u32, String> {
    if extract_progress::cancelled() { return Err("cancelled".to_string()); }
    let mut file = std::fs::File::open(input).map_err(|e| format!("lz4: {e}"))?;
    let mut compressed = Vec::new();
    file.read_to_end(&mut compressed).map_err(|e| format!("lz4: {e}"))?;
    let mut decoder = lz4_flex::frame::FrameDecoder::new(&compressed[..]);
    let mut decompressed = Vec::new();
    decoder.read_to_end(&mut decompressed).map_err(|e| format!("lz4: {e}"))?;
    if extract_progress::cancelled() { return Err("cancelled".to_string()); }
    extract_progress::reset(decompressed.len() as u64);

    let name = std::path::Path::new(input)
        .file_stem().map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| "output".to_string());
    let out_path = std::path::Path::new(output).join(&name);
    if let Some(p) = out_path.parent() { std::fs::create_dir_all(p).map_err(|e| format!("lz4: {e}"))?; }
    std::fs::write(&out_path, &decompressed).map_err(|e| format!("lz4: {e}"))?;
    extract_progress::set_name(&name);
    extract_progress::add_bytes(decompressed.len() as u64);
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
    match list_lz4_inner(&inp) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("{er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Lz4Core_lz4Extract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = std::fs::create_dir_all(&out);
    match guarded(move || decompress_lz4_inner(&inp, &out)) { Ok(f) => { let json = extract_result_json(1, if f==0{1}else{0}, f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Lz4Core_lz4ExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Lz4Core_lz4ExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Lz4Core_lz4ExtractProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_Lz4Core_lz4ExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }
