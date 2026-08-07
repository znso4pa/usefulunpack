use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jlong};
use archive_common::{s, json_escape, derive_dirs, safe_join, extract_result_json, ProgressWriter};
use archive_common::extract_progress;
use std::collections::HashSet;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use flate2::read::ZlibDecoder;

// --- Marker → length lookup tables ---

fn ypf_fname_len(m: u8) -> Option<usize> {
    match m {
        0xf4=>Some(9),0xfc=>Some(10),0xf6=>Some(11),0xef=>Some(12),0xec=>Some(13),0xf1=>Some(14),
        0xf0=>Some(15),0xf3=>Some(16),0xe7=>Some(17),0xed=>Some(18),0xf2=>Some(19),0xd1=>Some(20),
        0xe4=>Some(21),0xe9=>Some(22),0xe8=>Some(23),0xee=>Some(24),0xe6=>Some(25),0xe5=>Some(26),
        0xea=>Some(27),0xe1=>Some(28),0xe2=>Some(29),0xe3=>Some(30),0xe0=>Some(31),0xdc=>Some(32),
        0xde=>Some(33),0xdd=>Some(34),0xdf=>Some(35),0xdb=>Some(36),0xda=>Some(37),0xd6=>Some(38),
        0xd8=>Some(39),0xd7=>Some(40),0xd9=>Some(41),0xd5=>Some(42),0xd4=>Some(43),0xd0=>Some(44),
        0xd2=>Some(45),0xeb=>Some(46),0xd3=>Some(47),0xcf=>Some(48),0xce=>Some(49),0xcd=>Some(50),
        0xcc=>Some(51),0xcb=>Some(52),0xf9=>Some(53),0xc9=>Some(54),0xc8=>Some(55),_=>None}
}

// GARbro SwapTable00 — paired marker↔length lookup
static SWAP: &[u8] = &[
    0x03,0x48,0x06,0x35,0x0C,0x10,0x11,0x19,0x1C,0x1E,
    0x09,0x0B,0x0D,0x13,0x15,0x1B,0x20,0x23,0x26,0x29,0x2C,0x2F,0x2E,0x32,
];

fn fname_len(marker: u8) -> Option<usize> {
    let v = marker ^ 0xFF;
    if let Some(p) = SWAP.iter().position(|&x| x == v) {
        return Some(if (p & 1) != 0 { SWAP[p-1] } else { SWAP[p+1] } as usize);
    }
    ypf_fname_len(marker)
}

// --- Entry struct ---

struct YpfEntry { name: String, _file_type: u8, compressed: bool, usize: u32, asize: u32, offset: u32 }

// --- Core: open + parse entries ---

fn open_ypf(input: &str) -> Result<(Vec<YpfEntry>, File, u64), String> {
    let mut f = File::open(input).map_err(|e| format!("{e}"))?;
    let fsize = f.metadata().map(|m| m.len()).map_err(|e| format!("{e}"))?;
    let mut b = [0u8;4];
    f.read_exact(&mut b).map_err(|e| format!("{e}"))?;
    if &b != b"YPF\0" { return Err("Not a YPF file".into()); }
    f.read_exact(&mut b).map_err(|e| format!("{e}"))?; // version
    f.read_exact(&mut b).map_err(|e| format!("{e}"))?; let _count = u32::from_le_bytes(b) as usize;
    f.read_exact(&mut b).map_err(|e| format!("{e}"))?; let hdr_len = u32::from_le_bytes(b);
    if _count == 0 || _count > 100000 { return Err("YPF: bad count".into()); }
    if hdr_len < 0x20 || hdr_len as u64 > fsize { return Err("YPF: bad header_len".into()); }

    f.seek(SeekFrom::Start(0x20)).map_err(|e| format!("{e}"))?;

    let mut key: Option<u8> = None;
    let mut ents = Vec::with_capacity(_count.min(50000));
    let mut file_off = 0x20u64;
    let mut skip_streak = 0u32;
    let max_skip = 20u32;

    for _ in 0.._count.min(50000) {
        if skip_streak >= max_skip { break; }
        if ents.len() >= 50000 { break; }

        f.seek(SeekFrom::Start(file_off)).map_err(|e| format!("{e}"))?;
        let mut ehdr = [0u8;5];
        if f.read_exact(&mut ehdr).is_err() { break; }
        let marker = ehdr[4];

        // Layer 1+2: swap table + fixed mapping
        let fl = match fname_len(marker) {
            Some(n) if n > 0 && n < 200 => n,
            _ => {
                // Layer 3: adaptive rescue — scan for file_type(0-6)+comp(0-1)
                let mut scan = vec![0u8; 200];
                f.seek(SeekFrom::Start(file_off+5)).map_err(|e| format!("{e}"))?;
                let nread = f.read(&mut scan).unwrap_or(0);
                let mut found_fl: Option<usize> = None;
                for off in 4..nread.saturating_sub(24).min(120) {
                    if scan[off] <= 6 && scan[off+1] <= 1 {
                        let chk_off = u32::from_le_bytes([scan[off+12],scan[off+13],scan[off+14],scan[off+15]]);
                        if (chk_off as u64) < fsize { found_fl = Some(off); break; }
                    }
                }
                match found_fl {
                    Some(n) => n,
                    None => { file_off += 4; skip_streak += 1; continue; }
                }
            }
        };
        skip_streak = 0;

        // Read fname + tail
        let mut buf = vec![0u8; fl+22];
        f.seek(SeekFrom::Start(file_off+5)).map_err(|e| format!("{e}"))?;
        if f.read_exact(&mut buf).is_err() { file_off += 4; skip_streak += 1; continue; }

        // XOR key auto-detect (first entry only)
        let k = *key.get_or_insert_with(|| {
            let cnt = |xor:u8| -> usize {
                let mut a = buf[..fl].to_vec(); for b in &mut a { *b ^= xor; }
                String::from_utf8_lossy(&a).chars().filter(|c| c.is_ascii_alphanumeric()||*c=='/'||*c=='\\'||*c=='.'||*c=='_').count()
            };
            if cnt(0xFF) >= cnt(0xC9) { 0xFFu8 } else { 0xC9u8 }
        });

        let mut dec = buf[..fl].to_vec(); for b in &mut dec { *b ^= k; }
        let name = encoding_rs::SHIFT_JIS.decode(&dec).0.into_owned().replace('\\', "/");
        let tail = &buf[fl..];
        let ft = tail[0];
        let compressed = tail[1] != 0;
        let ulen = u32::from_le_bytes([tail[2],tail[3],tail[4],tail[5]]);
        let alen = u32::from_le_bytes([tail[6],tail[7],tail[8],tail[9]]);
        let off  = u32::from_le_bytes([tail[10],tail[11],tail[12],tail[13]]);

        let ok = (off as u64) < fsize && (off as u64 + alen as u64) <= fsize && ulen < 1_000_000_000
              && !name.is_empty() && name.chars().any(|c| c.is_alphanumeric()||c=='/'||c=='.'||c=='_'||c=='-');

        if ok {
            ents.push(YpfEntry { name, _file_type: ft, compressed, usize: ulen, asize: alen, offset: off });
        }
        file_off += (5 + fl + 22) as u64;
    }

    if ents.is_empty() { return Err("YPF: no valid entries found".into()); }
    Ok((ents, f, fsize))
}

// --- Extract ---

fn ypf_extract_one(f: &mut File, e: &YpfEntry, out: &str, fsize: u64) -> Result<(), String> {
    if e.asize == 0 { return Ok(()); }
    if e.offset as u64 + e.asize as u64 > fsize { return Err("offset OOB".into()); }
    let d = safe_join(out, &e.name)?;
    if let Some(p) = d.parent() { std::fs::create_dir_all(p).map_err(|x| format!("{x}"))?; }
    f.seek(SeekFrom::Start(e.offset as u64)).map_err(|x| format!("{x}"))?;
    let mut out_file = ProgressWriter::extract(std::fs::File::create(&d).map_err(|x| format!("{x}"))?);
    let limited = (&mut *f).take(e.asize as u64);
    if e.compressed {
        let mut dec = ZlibDecoder::new(limited);
        std::io::copy(&mut dec, &mut out_file).map_err(|x| format!("YPF zlib: {x}"))?;
    } else {
        let mut raw = limited;
        std::io::copy(&mut raw, &mut out_file).map_err(|x| format!("{x}"))?;
    }
    Ok(())
}

fn guard_panic<T, F: FnOnce() -> Result<T, String>>(f: F) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|panic| {
        let msg = panic.downcast_ref::<&str>().copied()
            .or_else(|| panic.downcast_ref::<String>().map(|s| s.as_str()))
            .unwrap_or("unknown panic");
        Err(format!("panic: {msg}"))
    })
}

fn list_ypf(input: &str) -> Result<String, String> {
    let (ents, _, _) = open_ypf(input)?;
    let names: Vec<&str> = ents.iter().map(|e| e.name.as_str()).collect();
    let dirs = derive_dirs(&names);
    let mut all: Vec<(String,u64,bool)> = Vec::new();
    for d in &dirs { all.push((d.clone(),0,true)); }
    for e in &ents { all.push((e.name.clone(), e.usize as u64, false)); }
    all.sort_by(|a,b| a.0.cmp(&b.0));
    let items: Vec<String> = all.iter().map(|(n,s,d)|{
        format!(r#"{{"n":"{}","s":{},"d":{},"e":false}}"#, json_escape(n), if *d {0} else {*s}, *d)
    }).collect();
    Ok(format!("[{}]", items.join(",")))
}

fn extract_ypf_all(i: &str, o: &str) -> Result<(u32, u32), String> {
    let (ents, mut f, fsize) = open_ypf(i)?;
    let total = ents.len() as u32;
    extract_progress::reset(ents.iter().map(|e| e.usize as u64).sum());
    let mut fail = 0u32;
    for e in &ents {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        extract_progress::set_name(&e.name);
        extract_progress::set_file(e.usize as u64);
        if guard_panic(|| ypf_extract_one(&mut f, e, o, fsize)).is_err() { fail += 1; }
    }
    Ok((total, fail))
}

fn extract_ypf_selected(i: &str, o: &str, s: &str) -> Result<(u32, u32), String> {
    let ss: HashSet<&str> = s.lines().filter(|l| !l.is_empty()).collect();
    if ss.is_empty() { return Ok((0, 0)); }
    let (ents, mut f, fsize) = open_ypf(i)?;
    extract_progress::reset(ents.iter().filter(|e| ss.contains(e.name.as_str()) || ss.iter().any(|d| e.name.starts_with(&format!("{d}/")))).map(|e| e.usize as u64).sum());
    let mut fail = 0u32; let mut selected = 0u32;
    for e in &ents {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        if ss.contains(e.name.as_str()) || ss.iter().any(|d| e.name.starts_with(&format!("{d}/"))) {
            selected += 1;
            extract_progress::set_name(&e.name);
            extract_progress::set_file(e.usize as u64);
            let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| ypf_extract_one(&mut f, e, o, fsize)));
            match r { Ok(Err(_)) | Err(_) => { fail += 1; } _ => {} }
        }
    }
    Ok((selected, fail))
}

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_YpfCore_ypfExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_YpfCore_ypfExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_YpfCore_ypfExtractProgressFileCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_YpfCore_ypfExtractProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_YpfCore_ypfExtractProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_YpfCore_ypfExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }

// --- JNI ---
// All archive-parsing entry points are wrapped in guard_panic so a panic on
// malicious input cannot cross the JNI boundary and kill the process.

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_YpfCore_ypfExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = std::fs::create_dir_all(&out);
    match guard_panic(move || extract_ypf_all(&inp, &out)) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_YpfCore_ypfExtractSelected(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel_j: JString) -> jstring {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel_j);
    match guard_panic(move || extract_ypf_selected(&inp, &out, &sel_str)) { Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_YpfCore_ypfListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i);
    match guard_panic(move || list_ypf(&inp)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}

#[cfg(test)]
mod tests {
    use super::*;
    use flate2::write::ZlibEncoder;
    use flate2::Compression;
    use std::io::Write as _;

    fn zlib(data: &[u8]) -> Vec<u8> {
        let mut enc = ZlibEncoder::new(Vec::new(), Compression::default());
        enc.write_all(data).unwrap();
        enc.finish().unwrap()
    }

    #[test]
    fn extract_streams_zlib_and_raw_entries() {
        let data: Vec<u8> = (0..100_000u32).map(|i| (i % 251) as u8).collect();
        let compressed = zlib(&data);
        let dir = std::env::temp_dir().join(format!("uu_ypf_x_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();

        // File layout: [zlib payload][raw payload]
        let mut payload = Vec::new();
        payload.extend_from_slice(&compressed);
        payload.extend_from_slice(&data);
        let fpath = dir.join("payload.bin");
        std::fs::write(&fpath, &payload).unwrap();

        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();

        let mut f = File::open(&fpath).unwrap();
        let fsize = payload.len() as u64;
        let zlib_entry = YpfEntry {
            name: "a/z.bin".into(),
            _file_type: 0,
            compressed: true,
            usize: data.len() as u32,
            asize: compressed.len() as u32,
            offset: 0,
        };
        let raw_entry = YpfEntry {
            name: "b/raw.bin".into(),
            _file_type: 0,
            compressed: false,
            usize: data.len() as u32,
            asize: data.len() as u32,
            offset: compressed.len() as u32,
        };

        ypf_extract_one(&mut f, &zlib_entry, out.to_str().unwrap(), fsize).unwrap();
        ypf_extract_one(&mut f, &raw_entry, out.to_str().unwrap(), fsize).unwrap();

        assert_eq!(std::fs::read(out.join("a/z.bin")).unwrap(), data);
        assert_eq!(std::fs::read(out.join("b/raw.bin")).unwrap(), data);
        std::fs::remove_dir_all(&dir).ok();
    }
}
