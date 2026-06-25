use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, derive_dirs};
use std::collections::{BTreeSet, HashSet};
use std::fs::{self, File};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::Path;
use flate2::read::ZlibDecoder;

// ─── NSA / SAR (NScripter) ──────────────────

struct NsaEntry { name: String, offset: u64, compressed: bool, csize: u64, usize: u64 }

fn open_nsa(input: &str) -> Result<(Vec<NsaEntry>, u64, File), String> {
    let mut file = File::open(input).map_err(|e| format!("{e}"))?;
    let mut hdr = [0u8; 6]; file.read_exact(&mut hdr).map_err(|e| format!("{e}"))?;
    let count = u16::from_be_bytes([hdr[0], hdr[1]]) as usize;
    if count > 100000 { return Err("Invalid archive".to_string()); }
    let mut entries = Vec::with_capacity(count);
    for _ in 0..count {
        let mut nb = Vec::new();
        loop { let mut b = [0u8; 1]; file.read_exact(&mut b).map_err(|e| format!("{e}"))?; if b[0] == 0 { break; } nb.push(b[0]); }
        let name = String::from_utf8(nb).map_err(|_| "Invalid UTF-8".to_string())?.replace('\\', "/");
        let mut comp = [0u8; 1]; file.read_exact(&mut comp).map_err(|e| format!("{e}"))?;
        let compressed = comp[0] != 0;
        let mut buf = [0u8; 4];
        file.read_exact(&mut buf).map_err(|e| format!("{e}"))?; let offset = u32::from_be_bytes(buf) as u64;
        file.read_exact(&mut buf).map_err(|e| format!("{e}"))?; let csize = u32::from_be_bytes(buf) as u64;
        file.read_exact(&mut buf).map_err(|e| format!("{e}"))?; let usize_v = u32::from_be_bytes(buf) as u64;
        entries.push(NsaEntry { name, offset, compressed, csize, usize: usize_v });
    }
    let data_start = file.stream_position().map_err(|e| format!("{e}"))?;
    Ok((entries, data_start, file))
}

fn extract_nsa_entry(entries: &[NsaEntry], file: &mut File, index: usize, output: &str, data_start: u64) -> Result<(), String> {
    let e = &entries[index];
    let mut dest = Path::new(output).to_path_buf();
    for comp in e.name.split('/') { if !comp.is_empty() { dest.push(comp); } }
    if let Some(p) = dest.parent() { fs::create_dir_all(p).map_err(|e| format!("{e}"))?; }
    file.seek(SeekFrom::Start(data_start + e.offset)).map_err(|e| format!("{e}"))?;
    if e.compressed {
        let mut cdata = vec![0u8; e.csize as usize];
        file.read_exact(&mut cdata).map_err(|e| format!("{e}"))?;
        let mut raw = Vec::with_capacity(e.usize as usize);
        ZlibDecoder::new(&cdata[..]).read_to_end(&mut raw).map_err(|e| format!("NSA zlib: {e}"))?;
        fs::write(&dest, &raw).map_err(|e| format!("{e}"))?;
    } else {
        let mut data = vec![0u8; e.usize as usize];
        file.read_exact(&mut data).map_err(|e| format!("{e}"))?;
        fs::write(&dest, &data).map_err(|e| format!("{e}"))?;
    }
    Ok(())
}

fn list_nsa(input: &str) -> Result<String, String> {
    let (entries, _, _) = open_nsa(input)?;
    let names: Vec<&str> = entries.iter().map(|e| e.name.as_str()).collect();
    let dirs = derive_dirs(&names);
    let mut all: Vec<(String, u64, bool)> = Vec::new();
    for d in &dirs { all.push((d.clone(), 0, true)); }
    for e in &entries { all.push((e.name.clone(), e.usize as u64, false)); }
    all.sort_by(|a, b| a.0.cmp(&b.0));
    let items: Vec<String> = all.iter().map(|(n, s, d)| {
        format!(r#"{{"n":"{}","s":{},"d":{},"e":false}}"#, json_escape(n), if *d { 0 } else { *s }, d)
    }).collect();
    Ok(format!("[{}]", items.join(",")))
}

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = fs::create_dir_all(&out);
    match open_nsa(&inp) { Ok((ents, ds, mut f)) => { let mut fail = 0u32; for idx in 0..ents.len() { if extract_nsa_entry(&ents, &mut f, idx, &out, ds).is_err() { fail += 1; } }
        if fail > 0 { let _ = e.throw_new("java/io/IOException", format!("NSA: {fail} failed")); JNI_FALSE } else { JNI_TRUE }
    } Err(er) => { let _ = e.throw_new("java/io/IOException", format!("NSA: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaExtractSelected(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel_j: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel_j);
    let ss: HashSet<&str> = sel_str.lines().filter(|l| !l.is_empty()).collect();
    if ss.is_empty() { return JNI_FALSE; } let _ = fs::create_dir_all(&out);
    match open_nsa(&inp) { Ok((ents, ds, mut f)) => { let mut fail = 0u32; for (idx, entry) in ents.iter().enumerate() {
        if ss.contains(entry.name.as_str()) || ss.iter().any(|d| entry.name.starts_with(&format!("{d}/"))) { if extract_nsa_entry(&ents, &mut f, idx, &out, ds).is_err() { fail += 1; } }
    } if fail > 0 { let _ = e.throw_new("java/io/IOException", format!("NSA: {fail} failed")); JNI_FALSE } else { JNI_TRUE }
    } Err(er) => { let _ = e.throw_new("java/io/IOException", format!("NSA: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    match list_nsa(&s(&mut e, &i)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}
