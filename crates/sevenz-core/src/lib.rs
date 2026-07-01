use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, derive_dirs};
use sevenz_rust::*;

fn list_7z(input: &str) -> Result<String, String> {
    let archive = Archive::open(input).map_err(|e| format!("7z: {e}"))?;
    let mut all: Vec<(String, u64, bool)> = Vec::new();
    for entry in &archive.files {
        let name = entry.name().replace('\\', "/").trim_matches('/').to_string();
        if name.is_empty() { continue; }
        all.push((name.clone(), entry.size(), entry.is_directory()));
        let mut path = String::new();
        for part in name.split('/') {
            if part.is_empty() { continue; }
            path = if path.is_empty() { part.to_string() } else { format!("{path}/{part}") };
            if !all.iter().any(|(p,_,_)| p == &path) { all.push((path.clone(), 0u64, true)); }
        }
    }
    all.sort_by(|a,b| a.0.cmp(&b.0));
    all.dedup_by(|a,b| a.0 == b.0);
    let items: Vec<String> = all.iter().map(|(n,s,d)|
        format!(r#"{{"n":"{}","s":{},"d":{},"e":false}}"#, json_escape(n), *s, *d)
    ).collect();
    Ok(format!("[{}]", items.join(",")))
}

fn extract_7z_all(input: &str, output: &str) -> Result<u32, String> {
    decompress_file(input, output).map_err(|e| format!("7z: {e}"))?;
    Ok(0)
}

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = std::fs::create_dir_all(&out);
    match extract_7z_all(&inp, &out) { Ok(0) => JNI_TRUE, Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("7z: {f} failed")); JNI_FALSE } Err(er) => { let _ = e.throw_new("java/io/IOException", format!("7z: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szExtractSelected(mut e: JNIEnv, _: JClass, _t: JString, _i: JString, _o: JString, _sel: JString) -> jboolean {
    let _ = e.throw_new("java/io/IOException", "7z selective extraction not yet supported"); JNI_FALSE
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_SevenZCore_szListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    match list_7z(&s(&mut e, &i)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}
