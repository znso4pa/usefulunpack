use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jlong};
use archive_common::{s, json_escape, derive_dirs, safe_join, extract_result_json};
use archive_common::extract_progress;
use std::collections::HashSet;
use std::fs::{self, File};
use std::io::{Read, Seek, SeekFrom};

fn guarded<T: Send + 'static>(f: impl FnOnce() -> Result<T, String> + Send + 'static) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|panic| {
        let msg = panic.downcast_ref::<&str>().copied()
            .or_else(|| panic.downcast_ref::<String>().map(|s| s.as_str()))
            .unwrap_or("unknown panic");
        Err(format!("panic: {msg}"))
    })
}

// ─── NScripter LZSS / SPB decompression (from GARbro) ───

const LZSS_N: usize = 256;
const LZSS_F: usize = 17;
const LZSS_EI: u32 = 8;
const LZSS_EJ: u32 = 4;

struct BitReader<'a> {
    data: &'a [u8],
    pos: usize,
    buf: u8,
    mask: u8,
}

impl<'a> BitReader<'a> {
    fn new(data: &'a [u8]) -> Self { BitReader { data, pos: 0, buf: 0, mask: 0 } }
    fn get_bit(&mut self) -> Result<u8, String> {
        if self.mask == 0 {
            if self.pos >= self.data.len() { return Err("lzss: eof".into()); }
            self.buf = self.data[self.pos]; self.pos += 1;
            self.mask = 0x80;
        }
        let bit = (self.buf & self.mask) != 0;
        self.mask >>= 1;
        Ok(bit as u8)
    }
    fn get_bits(&mut self, n: u32) -> Result<u32, String> {
        let mut v = 0u32;
        for _ in 0..n { v = (v << 1) | self.get_bit()? as u32; }
        Ok(v)
    }
}

fn nsa_lzss_decompress(data: &[u8], usize: u32) -> Result<Vec<u8>, String> {
    if usize > 2 * 1024 * 1024 * 1024 { return Err("lzss: declared size too large".into()); }
    let mut out = Vec::with_capacity(usize.min(512 * 1024 * 1024) as usize);
    let mut ring = vec![0u8; LZSS_N * 2];
    let mut r = LZSS_N - LZSS_F;
    let mut br = BitReader::new(data);
    while out.len() < usize as usize {
        if br.get_bit()? != 0 {
            let c = br.get_bits(8)? as u8;
            out.push(c);
            ring[r] = c; r = (r + 1) & (LZSS_N - 1);
        } else {
            let i = br.get_bits(LZSS_EI)? as usize;
            let j = br.get_bits(LZSS_EJ)? as usize;
            for k in 0..=j + 1 {
                let c = ring[(i + k) & (LZSS_N - 1)];
                out.push(c);
                ring[r] = c; r = (r + 1) & (LZSS_N - 1);
            }
        }
    }
    Ok(out)
}

fn nsa_spb_decompress(data: &[u8], usize: u32) -> Result<Vec<u8>, String> {
    if data.len() < 4 { return Err("spb: too short".into()); }
    let width  = ((data[0] as u32) << 8) | data[1] as u32;
    let height = ((data[2] as u32) << 8) | data[3] as u32;
    let width_pad = (4u32.wrapping_sub(width * 3) & 3) as usize;
    let stride = (width as usize) * 3 + width_pad;
    let total_size = stride * height as usize + 54;
    // Guard against corrupt width/height blowing up the allocation (u16 pair can
    // declare ~12.8GB); cap at 2GB to fail cleanly instead of aborting on OOM.
    if total_size > 2 * 1024 * 1024 * 1024 || usize as u64 > 2 * 1024 * 1024 * 1024 {
        return Err("spb: image too large".into());
    }
    let data = &data[4..];

    let mut out = vec![0u8; total_size.max(usize as usize)];
    out[0] = b'B'; out[1] = b'M';
    out[2] = total_size as u8; out[3] = (total_size >> 8) as u8;
    out[4] = (total_size >> 16) as u8; out[5] = (total_size >> 24) as u8;
    out[10] = 54;
    out[14] = 40;
    out[18] = width as u8; out[19] = (width >> 8) as u8;
    out[20] = (width >> 16) as u8; out[21] = (width >> 24) as u8;
    out[22] = height as u8; out[23] = (height >> 8) as u8;
    out[24] = (height >> 16) as u8; out[25] = (height >> 24) as u8;
    out[26] = 1;
    out[28] = 24;

    let pixel_count = (width * height) as usize;
    let mut br = BitReader::new(data);

    for channel in 0..3i32 {
        let mut buf = Vec::with_capacity(pixel_count);
        let c = br.get_bits(8)? as u8;
        buf.push(c);
        while buf.len() < pixel_count {
            let n = br.get_bits(3)?;
            if n == 0 {
                for _ in 0..4 { buf.push(c); }
                continue;
            }
            let m = if n == 7 { br.get_bits(1)? + 1 } else { n + 2 };
            for _ in 0..4 {
                let mut c = buf.last().copied().unwrap_or(0) as i32;
                if m == 8 {
                    c = br.get_bits(8)? as i32;
                } else {
                    let k = br.get_bits(m)? as i32;
                    if k & 1 != 0 { c += (k >> 1) + 1; } else { c -= k >> 1; }
                }
                buf.push((c as u8).wrapping_sub(0) as u8);
            }
        }

        let mut pbuf = stride * (height as usize - 1) + channel as usize + 54;
        let mut psbuf = 0;
        for j in 0..height as usize {
            if j & 1 != 0 {
                for _ in 0..width as usize {
                    out[pbuf] = buf[psbuf]; psbuf += 1;
                    pbuf = pbuf.wrapping_sub(3);
                }
                pbuf = pbuf.wrapping_sub(stride - 3);
            } else {
                for _ in 0..width as usize {
                    out[pbuf] = buf[psbuf]; psbuf += 1;
                    pbuf += 3;
                }
                pbuf = pbuf.wrapping_sub(stride + 3);
            }
        }
    }
    out.truncate(usize as usize);
    Ok(out)
}

// ─── NSA / SAR (NScripter) ──────────────────

struct NsaEntry { name: String, offset: u64, comp_method: u8, csize: u64, usize: u64 }

fn open_nsa(input: &str) -> Result<(Vec<NsaEntry>, u64, File), String> {
    let mut file = File::open(input).map_err(|e| format!("{e}"))?;
    let mut hdr = [0u8; 6]; file.read_exact(&mut hdr).map_err(|e| format!("{e}"))?;
    let count = u16::from_be_bytes([hdr[0], hdr[1]]) as usize;
    if count > 100000 { return Err("Invalid archive".to_string()); }
    let mut entries = Vec::with_capacity(count);
    for _ in 0..count {
        let mut nb = Vec::new();
        loop { let mut b = [0u8; 1]; file.read_exact(&mut b).map_err(|e| format!("{e}"))?; if b[0] == 0 { break; } nb.push(b[0]); if nb.len() > 512 { return Err("NSA: filename too long".to_string()); } }
        let name = String::from_utf8(nb).map_err(|_| "Invalid UTF-8".to_string())?.replace('\\', "/");
        let mut comp = [0u8; 1]; file.read_exact(&mut comp).map_err(|e| format!("{e}"))?;
        let mut buf = [0u8; 4];
        file.read_exact(&mut buf).map_err(|e| format!("{e}"))?; let offset = u32::from_be_bytes(buf) as u64;
        file.read_exact(&mut buf).map_err(|e| format!("{e}"))?; let csize = u32::from_be_bytes(buf) as u64;
        file.read_exact(&mut buf).map_err(|e| format!("{e}"))?; let usize_v = u32::from_be_bytes(buf) as u64;
        entries.push(NsaEntry { name, offset, comp_method: comp[0], csize, usize: usize_v });
    }
    let data_start = file.stream_position().map_err(|e| format!("{e}"))?;
    Ok((entries, data_start, file))
}

fn extract_nsa_entry(entries: &[NsaEntry], file: &mut File, index: usize, output: &str, data_start: u64) -> Result<(), String> {
    let e = &entries[index];
    if e.csize == 0 { return Ok(()); }
    let dest = safe_join(output, &e.name)?;
    if let Some(p) = dest.parent() { fs::create_dir_all(p).map_err(|e| format!("{e}"))?; }
    file.seek(SeekFrom::Start(data_start + e.offset)).map_err(|e| format!("{e}"))?;
    let mut cdata = vec![0u8; e.csize as usize];
    file.read_exact(&mut cdata).map_err(|e| format!("{e}"))?;

    let raw = match e.comp_method {
        0 => cdata,
        2 => nsa_lzss_decompress(&cdata, e.usize as u32)?,
        1 => nsa_spb_decompress(&cdata, e.usize as u32)?,
        _ => return Err(format!("NSA: unsupported compression {}", e.comp_method)),
    };
    fs::write(&dest, &raw).map_err(|e| format!("{e}"))?;
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

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = fs::create_dir_all(&out);
    match guarded(move || {
        let (ents, ds, mut f) = open_nsa(&inp)?;
        let total = ents.len() as u32; let mut fail = 0u32;
        extract_progress::reset(ents.iter().map(|e| e.usize).sum());
        for idx in 0..ents.len() {
            if extract_progress::cancelled() { return Err("cancelled".to_string()); }
            extract_progress::set_name(&ents[idx].name);
            if extract_nsa_entry(&ents, &mut f, idx, &out, ds).is_err() { fail += 1; }
            extract_progress::add_bytes(ents[idx].usize);
        }
        Ok((total, fail))
    }) {
        Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaExtractSelected(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel_j: JString) -> jstring {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel_j);
    match guarded(move || {
        let ss: HashSet<&str> = sel_str.lines().filter(|l| !l.is_empty()).collect();
        if ss.is_empty() { return Ok((0, 0)); }
        let (ents, ds, mut f) = open_nsa(&inp)?;
        extract_progress::reset(ents.iter().filter(|e| ss.contains(e.name.as_str()) || ss.iter().any(|d| e.name.starts_with(&format!("{d}/")))).map(|e| e.usize).sum());
        let mut fail = 0u32; let mut selected = 0u32;
        for (idx, entry) in ents.iter().enumerate() {
            if extract_progress::cancelled() { return Err("cancelled".to_string()); }
            if ss.contains(entry.name.as_str()) || ss.iter().any(|d| entry.name.starts_with(&format!("{d}/"))) {
                selected += 1;
                extract_progress::set_name(&entry.name);
                if extract_nsa_entry(&ents, &mut f, idx, &out, ds).is_err() { fail += 1; }
                extract_progress::add_bytes(entry.usize);
            }
        }
        Ok((selected, fail))
    }) {
        Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    match list_nsa(&s(&mut e, &i)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaExtractProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }
