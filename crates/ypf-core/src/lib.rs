use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, derive_dirs};
use std::collections::HashSet;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::Path;
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

struct YpfEntry { name: String, file_type: u8, compressed: bool, usize: u32, asize: u32, offset: u32 }

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
            ents.push(YpfEntry { name, file_type: ft, compressed, usize: ulen, asize: alen, offset: off });
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
    let mut d = Path::new(out).to_path_buf();
    for c in e.name.split('/') { if !c.is_empty() { d.push(c); } }
    if let Some(p) = d.parent() { std::fs::create_dir_all(p).map_err(|x| format!("{x}"))?; }
    f.seek(SeekFrom::Start(e.offset as u64)).map_err(|x| format!("{x}"))?;
    let mut raw = vec![0u8; e.asize as usize];
    f.read_exact(&mut raw).map_err(|x| format!("{x}"))?;
    if e.compressed {
        let mut dec = Vec::with_capacity(e.usize.min(100_000_000) as usize);
        ZlibDecoder::new(&raw[..]).read_to_end(&mut dec).map_err(|x| format!("YPF zlib: {x}"))?;
        std::fs::write(&d, &dec).map_err(|x| format!("{x}"))?;
    } else { std::fs::write(&d, &raw).map_err(|x| format!("{x}"))?; }
    Ok(())
}

// --- API functions ---

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

fn extract_ypf_all(i: &str, o: &str) -> Result<u32, String> {
    let (ents, mut f, fsize) = open_ypf(i)?;
    let mut fail = 0u32;
    for e in &ents {
        let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| ypf_extract_one(&mut f, e, o, fsize)));
        match r { Ok(Err(_)) | Err(_) => { fail += 1; } _ => {} }
    }
    Ok(fail)
}

fn extract_ypf_selected(i: &str, o: &str, s: &str) -> Result<u32, String> {
    let ss: HashSet<&str> = s.lines().filter(|l| !l.is_empty()).collect();
    if ss.is_empty() { return Ok(0); }
    let (ents, mut f, fsize) = open_ypf(i)?;
    let mut fail = 0u32;
    for e in &ents {
        if ss.contains(e.name.as_str()) || ss.iter().any(|d| e.name.starts_with(&format!("{d}/"))) {
            let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| ypf_extract_one(&mut f, e, o, fsize)));
            match r { Ok(Err(_)) | Err(_) => { fail += 1; } _ => {} }
        }
    }
    Ok(fail)
}

// --- JNI ---

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ArchiveCore_ypfExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = std::fs::create_dir_all(&out);
    match extract_ypf_all(&inp, &out) { Ok(0) => JNI_TRUE, Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("YPF: {f} failed")); JNI_FALSE }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("YPF: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ArchiveCore_ypfExtractSelected(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel_j: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel_j);
    match extract_ypf_selected(&inp, &out, &sel_str) { Ok(0) => JNI_TRUE, Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("YPF: {f} failed")); JNI_FALSE }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("YPF: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_ArchiveCore_ypfListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    match list_ypf(&s(&mut e, &i)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}
