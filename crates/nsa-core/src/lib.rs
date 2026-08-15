use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jlong, jboolean, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, derive_dirs, safe_join, extract_result_json, ProgressWriter, ProgressReader};
use archive_common::{extract_progress, compress_progress};
use std::collections::HashSet;
use std::fs::{self, File};
use std::io::{BufWriter, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

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

/// Streams LZSS-decompressed bytes to a writer in 64KB chunks, so large
/// members never buffer their whole output in RAM.
fn nsa_lzss_decompress_to<W: Write>(data: &[u8], out_len: u32, writer: &mut W) -> Result<(), String> {
    if out_len > 2 * 1024 * 1024 * 1024 { return Err("lzss: declared size too large".into()); }
    let mut ring = [0u8; LZSS_N * 2];
    let mut r = LZSS_N - LZSS_F;
    let mut br = BitReader::new(data);
    let mut written: u64 = 0;
    let mut buf = Vec::with_capacity(64 * 1024);
    while written < out_len as u64 {
        if br.get_bit()? != 0 {
            let c = br.get_bits(8)? as u8;
            buf.push(c);
            ring[r] = c; r = (r + 1) & (LZSS_N - 1);
            written += 1;
        } else {
            let i = br.get_bits(LZSS_EI)? as usize;
            let j = br.get_bits(LZSS_EJ)? as usize;
            // Clamp the back-reference to the declared output size: a
            // malformed stream can point past out_len and overrun the output.
            // The reference covers j+2 bytes (the old loop ran 0..=j+1).
            let remaining = out_len as u64 - written;
            let n = ((j + 2) as u64).min(remaining) as usize;
            for k in 0..n {
                let c = ring[(i + k) & (LZSS_N - 1)];
                buf.push(c);
                ring[r] = c; r = (r + 1) & (LZSS_N - 1);
                written += 1;
            }
        }
        if buf.len() >= 64 * 1024 {
            writer.write_all(&buf).map_err(|e| format!("lzss write: {e}"))?;
            buf.clear();
        }
    }
    if !buf.is_empty() {
        writer.write_all(&buf).map_err(|e| format!("lzss write: {e}"))?;
    }
    Ok(())
}

/// Greedy LZSS encoder that mirrors the decoder's ring-buffer layout — used to
/// build NSA archives (compression method 2). Ratios are modest (a few % on
/// real scripts) but it round-trips exactly with `nsa_lzss_decompress_to`.
fn lzss_encode<W: Write>(data: &[u8], mut writer: W) -> Result<(), String> {
    const N: usize = LZSS_N;
    const F: usize = LZSS_F;
    let mut ring = vec![0u8; N * 2];
    let mut r = N - F;
    let mut out = Vec::new();
    let mut cur: u8 = 0;
    let mut nbits: u8 = 0;
    let mut pos = 0usize;
    let put_bit = |out: &mut Vec<u8>, cur: &mut u8, nbits: &mut u8, bit: bool| {
        *cur = (*cur << 1) | bit as u8;
        *nbits += 1;
        if *nbits == 8 { out.push(*cur); *cur = 0; *nbits = 0; }
    };
    let put_bits = |out: &mut Vec<u8>, cur: &mut u8, nbits: &mut u8, mut v: u32, n: u8| {
        for _ in 0..n { let bit = v & (1 << (n - 1)) != 0; *cur = (*cur << 1) | bit as u8; *nbits += 1; if *nbits == 8 { out.push(*cur); *cur = 0; *nbits = 0; } v <<= 1; }
    };
    while pos < data.len() {
        let max_match = (data.len() - pos).min(F);
        let mut best_len = 0usize;
        let mut best_i = 0usize;
        for i in 0..N {
            let mut l = 0usize;
            while l < max_match && ring[(i + l) & (N - 1)] == data[pos + l] { l += 1; }
            if l > best_len { best_len = l; best_i = i; }
        }
        if best_len >= 2 {
            put_bit(&mut out, &mut cur, &mut nbits, false);
            put_bits(&mut out, &mut cur, &mut nbits, best_i as u32, 8);
            put_bits(&mut out, &mut cur, &mut nbits, (best_len - 2) as u32, 4);
        } else {
            best_len = 1;
            put_bit(&mut out, &mut cur, &mut nbits, true);
            put_bits(&mut out, &mut cur, &mut nbits, data[pos] as u32, 8);
        }
        for _ in 0..best_len {
            ring[r] = data[pos]; r = (r + 1) & (N - 1); pos += 1;
        }
    }
    if nbits > 0 { out.push(cur << (8 - nbits)); }
    writer.write_all(&out).map_err(|e| format!("lzss encode write: {e}"))
}

fn nsa_spb_decompress(data: &[u8], usize: u32) -> Result<Vec<u8>, String> {
    if data.len() < 4 { return Err("spb: too short".into()); }
    let width  = ((data[0] as u32) << 8) | data[1] as u32;
    let height = ((data[2] as u32) << 8) | data[3] as u32;
    if height == 0 || width == 0 {
        return Err("spb: zero dimension".into());
    }
    let width_pad = (4u32.wrapping_sub(width * 3) & 3) as usize;
    let stride = (width as usize) * 3 + width_pad;
    let total_size = stride * height as usize + 54;
    // Guard against corrupt width/height blowing up the allocation (a u16
    // pair can declare ~12.8GB). Real SPB illustrations are BMPs far below
    // 64MB — cap there to fail cleanly instead of aborting on OOM.
    const SPB_MAX: u64 = 64 * 1024 * 1024;
    if total_size as u64 > SPB_MAX || usize as u64 > SPB_MAX {
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
    // A malicious NSA header can declare a csize up to 4GB (u32). Reject sizes
    // that are out of range or too large to buffer, instead of OOM-aborting.
    let file_len = file.metadata().map(|m| m.len()).unwrap_or(0);
    let csize = e.csize as u64;
    if csize > 2 * 1024 * 1024 * 1024 || data_start + e.offset + csize > file_len {
        return Err(format!("NSA: corrupt csize {}", e.csize));
    }
    let dest = safe_join(output, &e.name)?;
    if let Some(p) = dest.parent() { fs::create_dir_all(p).map_err(|e| format!("{e}"))?; }
    file.seek(SeekFrom::Start(data_start + e.offset)).map_err(|e| format!("{e}"))?;
    let mut out = ProgressWriter::extract(std::fs::File::create(&dest).map_err(|e| format!("{e}"))?);
    match e.comp_method {
        0 => {
            let mut limited = (&mut *file).take(e.csize);
            std::io::copy(&mut limited, &mut out).map_err(|e| format!("{e}"))?;
        }
        2 => {
            let mut cdata = vec![0u8; e.csize as usize];
            file.read_exact(&mut cdata).map_err(|e| format!("{e}"))?;
            nsa_lzss_decompress_to(&cdata, e.usize as u32, &mut out)?;
        }
        1 => {
            let mut cdata = vec![0u8; e.csize as usize];
            file.read_exact(&mut cdata).map_err(|e| format!("{e}"))?;
            let raw = nsa_spb_decompress(&cdata, e.usize as u32)?;
            out.write_all(&raw).map_err(|e| format!("{e}"))?;
        }
        _ => return Err(format!("NSA: unsupported compression {}", e.comp_method)),
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

/// Collects files under `base` into (path, rel_name) pairs, sorted by name.
/// A single file packs as that file under its own name (compression flows
/// wrap single files in a temp dir anyway, but keep the behavior uniform).
fn collect_files_nsa(base: &Path) -> Result<Vec<(PathBuf, String)>, String> {
    let mut out = Vec::new();
    if base.is_file() {
        let name = base.file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
        if name.is_empty() { return Err("NSA: empty filename".to_string()); }
        out.push((base.to_path_buf(), name));
        return Ok(out);
    }
    let mut stack = vec![(base.to_path_buf(), String::new())];
    while let Some((dir, rel)) = stack.pop() {
        let mut entries: Vec<_> = fs::read_dir(&dir).map_err(|e| format!("read_dir {}: {e}", dir.display()))?
            .collect::<Result<_, _>>().map_err(|e| format!("read_dir {}: {e}", dir.display()))?;
        entries.sort_by_key(|e| e.file_name());
        for entry in entries {
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().to_string();
            let child_rel = if rel.is_empty() { name.clone() } else { format!("{rel}/{name}") };
            let meta = entry.metadata().map_err(|e| format!("metadata {}: {e}", path.display()))?;
            if meta.is_dir() {
                stack.push((path, child_rel));
            } else if meta.is_file() {
                out.push((path, child_rel));
            }
        }
    }
    out.sort_by(|a, b| a.1.cmp(&b.1));
    Ok(out)
}

/// Packs a directory (or single file) into an NSA archive. Level 0 stores
/// entries raw (comp=0); level ≥1 LZSS-compresses them (comp=2) — the same
/// methods the extractor understands. Byte progress via compress_progress.
fn create_nsa(input: &str, output: &str, level: i32) -> Result<u32, String> {
    let files = collect_files_nsa(Path::new(input))?;
    if files.is_empty() { return Err("NSA: no files to archive".to_string()); }
    if files.len() > 65535 { return Err("NSA: too many files (max 65535)".to_string()); }
    let total: u64 = files.iter().map(|(p, _)| p.metadata().map(|m| m.len()).unwrap_or(0)).sum();
    compress_progress::reset(total);

    // Header layout: u16 count + 4 reserved bytes, then per entry
    // name\0 + comp(u8) + offset(u32 BE) + csize(u32 BE) + usize(u32 BE).
    // Body follows; offsets are relative to the body start. The header size is
    // known up front (names are fixed), so it's written once, then the body is
    // STREAMED to disk (no whole-archive RAM buffering) and the header fields
    // are back-filled at the end.
    let mut header_len: u64 = 6;
    for (_, name) in &files {
        header_len += name.len() as u64 + 1 + 13;
    }
    if header_len > u32::MAX as u64 {
        return Err("NSA: header too large".to_string());
    }
    let mut out = BufWriter::new(File::create(output).map_err(|e| format!("NSA create {output}: {e}"))?);
    out.write_all(&(files.len() as u16).to_be_bytes()).map_err(|e| format!("NSA header: {e}"))?;
    out.write_all(&[0u8; 4]).map_err(|e| format!("NSA header: {e}"))?;
    // Placeholder for every entry (name\0 + 13 metadata bytes) — filled later.
    for (_, name) in &files {
        out.write_all(name.as_bytes()).map_err(|e| format!("NSA header: {e}"))?;
        out.write_all(&[0u8; 14]).map_err(|e| format!("NSA header: {e}"))?; // NUL + comp+off+csize+usize
    }

    let mut metas: Vec<(u32, u32, u32, u32)> = Vec::with_capacity(files.len()); // (offset, csize, usize, name_len)
    let mut offset: u64 = 0;
    for (src, name) in &files {
        if compress_progress::cancelled() { return Err("cancelled".to_string()); }
        let size = src.metadata().map(|m| m.len()).unwrap_or(0);
        compress_progress::set_name(name);
        compress_progress::set_file(size);
        let mut src_file = File::open(src).map_err(|e| format!("NSA open {}: {e}", src.display()))?;

        // Stream each file: read in bounded chunks (compression can't fit a
        // huge file in RAM), write the compressed-or-raw payload to disk.
        let csize;
        if level >= 1 && size > 0 && size <= 64 * 1024 * 1024 {
            // LZSS is non-streaming (whole-file ring buffer), so it's only
            // attempted for files ≤ 64 MiB; bigger files are stored raw
            // (streamed straight from disk) to avoid buffering GBs in RAM.
            // The probe read does NOT count progress — the file may be re-read
            // for the raw fallback, and double-counting would push the bar
            // past 100%.
            let mut enc = Vec::new();
            {
                let mut buf = Vec::new();
                std::io::copy(&mut src_file, &mut buf)
                    .map_err(|e| format!("NSA read {}: {e}", src.display()))?;
                lzss_encode(&buf, &mut enc).map_err(|e| format!("NSA encode {name}: {e}"))?;
            }
            if enc.len() < size as usize {
                out.write_all(&enc).map_err(|e| format!("NSA body: {e}"))?;
                compress_progress::add_bytes(size);
                csize = enc.len() as u32;
            } else {
                // store raw: stream from disk, counting progress once.
                let mut src2 = File::open(src).map_err(|e| format!("NSA open {}: {e}", src.display()))?;
                let mut limited = ProgressReader::compress(&mut src2).take(size);
                std::io::copy(&mut limited, &mut out).map_err(|e| format!("NSA body: {e}"))?;
                csize = size as u32;
            }
        } else {
            let mut limited = ProgressReader::compress(&mut src_file).take(size);
            std::io::copy(&mut limited, &mut out).map_err(|e| format!("NSA body: {e}"))?;
            csize = size as u32;
        }
        if offset > u32::MAX as u64 {
            return Err(format!("NSA: archive too large (>4GiB) at {name}"));
        }
        metas.push((offset as u32, csize, size as u32, name.len() as u32));
        offset += csize as u64;
    }

    // Back-fill the header entries. Entry layout per file:
    // name\0 + comp(1) + offset(4) + csize(4) + usize(4) → name_len + 14 bytes.
    let mut cursor: u64 = 6;
    for (off, csize, usize_v, name_len) in &metas {
        let entry_start = cursor + *name_len as u64 + 1; // skip name + NUL
        out.flush().map_err(|e| format!("NSA flush: {e}"))?;
        let f = out.get_mut();
        f.seek(SeekFrom::Start(entry_start)).map_err(|e| format!("NSA seek: {e}"))?;
        f.write_all(&[if *csize < *usize_v && *usize_v > 0 { 2 } else { 0 }])
            .map_err(|e| format!("NSA meta: {e}"))?;
        f.write_all(&off.to_be_bytes()).map_err(|e| format!("NSA meta: {e}"))?;
        f.write_all(&csize.to_be_bytes()).map_err(|e| format!("NSA meta: {e}"))?;
        f.write_all(&usize_v.to_be_bytes()).map_err(|e| format!("NSA meta: {e}"))?;
        cursor += *name_len as u64 + 14;
    }
    out.flush().map_err(|e| format!("NSA flush: {e}"))?;
    Ok(files.len() as u32)
}


#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = fs::create_dir_all(&out);
    match guarded(move || {
        let (ents, ds, mut f) = open_nsa(&inp)?;
        let total = ents.len() as u32; let mut fail = 0u32;
        extract_progress::reset(ents.iter().map(|e| e.usize).sum());
        for idx in 0..ents.len() {
            if extract_progress::cancelled() { return Err("cancelled".to_string()); }
            extract_progress::set_name(&ents[idx].name);
            extract_progress::set_file(ents[idx].usize);
            if extract_nsa_entry(&ents, &mut f, idx, &out, ds).is_err() { fail += 1; }
        }
        Ok((total, fail))
    }) {
        Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaExtractSelected(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel_j: JString) -> jstring {
    extract_progress::clear_cancel();
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
                extract_progress::set_file(entry.usize);
                if extract_nsa_entry(&ents, &mut f, idx, &out, ds).is_err() { fail += 1; }
            }
        }
        Ok((selected, fail))
    }) {
        Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i);
    match guarded(move || list_nsa(&inp)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaExtractProgressFileCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaExtractProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaExtractProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaCreateArchive(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, lv: JString) -> jboolean {
    compress_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let lvl: i32 = s(&mut e, &lv).parse().unwrap_or(0);
    match guarded(move || create_nsa(&inp, &out, lvl)) {
        Ok(_) => JNI_TRUE,
        Err(er) => { let _ = e.throw_new("java/io/IOException", format!("nsa: {er}")); JNI_FALSE }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaCompressProgressCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaCompressProgressTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaCompressProgressFileCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaCompressProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaCompressProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&compress_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_NsaCore_nsaCompressCancel(_: JNIEnv, _: JClass) { compress_progress::cancel(); }


#[cfg(test)]
mod tests {
    use super::*;

    fn make_nsa(path: &std::path::Path, entries: &[(&str, u8, &[u8])]) {
        let mut body = Vec::new();
        let mut metas = Vec::new();
        for (name, comp, data) in entries {
            let stored = match comp {
                0 => data.to_vec(),
                2 => { let mut enc = Vec::new(); lzss_encode(data, &mut enc).unwrap(); enc }
                _ => panic!("unsupported test comp"),
            };
            metas.push((name.to_string(), *comp, body.len() as u32, stored.len() as u32, data.len() as u32));
            body.extend_from_slice(&stored);
        }
        let mut out = Vec::new();
        out.extend_from_slice(&(entries.len() as u16).to_be_bytes());
        out.extend_from_slice(&[0u8; 4]);
        for (name, comp, offset, csize, usize_v) in &metas {
            out.extend_from_slice(name.as_bytes());
            out.push(0);
            out.push(*comp);
            out.extend_from_slice(&offset.to_be_bytes());
            out.extend_from_slice(&csize.to_be_bytes());
            out.extend_from_slice(&usize_v.to_be_bytes());
        }
        out.extend_from_slice(&body);
        std::fs::write(path, out).unwrap();
    }

    #[test]
    fn streams_stored_and_lzss_entries() {
        let data_lzss: Vec<u8> = (0..100_000u32).map(|i| (i % 251) as u8).collect();
        let data_stored: Vec<u8> = (0..5000u32).map(|i| (i % 13) as u8).collect();
        let dir = std::env::temp_dir().join(format!("uu_nsa_x_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let nsa = dir.join("test.nsa");
        make_nsa(&nsa, &[("a/stored.bin", 0, &data_stored), ("b/lzss.bin", 2, &data_lzss)]);

        let (ents, ds, mut f) = open_nsa(nsa.to_str().unwrap()).unwrap();
        assert_eq!(ents.len(), 2);
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        for idx in 0..ents.len() {
            extract_nsa_entry(&ents, &mut f, idx, out.to_str().unwrap(), ds).unwrap();
        }
        assert_eq!(std::fs::read(out.join("a/stored.bin")).unwrap(), data_stored);
        assert_eq!(std::fs::read(out.join("b/lzss.bin")).unwrap(), data_lzss);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn lzss_tail_backref_clamped_to_declared_size() {
        // 8 literals then a back-reference asking for 11 more bytes while
        // only 2 remain (declared size 10). Without the clamp the decoder
        // overruns the declared output; with it, output == exactly 10 bytes.
        const N: usize = LZSS_N;
        let mut out = Vec::new();
        let mut cur: u8 = 0;
        let mut nbits: u8 = 0;
        fn put_bit(out: &mut Vec<u8>, cur: &mut u8, nbits: &mut u8, bit: bool) {
            *cur = (*cur << 1) | bit as u8;
            *nbits += 1;
            if *nbits == 8 { out.push(*cur); *cur = 0; *nbits = 0; }
        }
        fn put_bits(out: &mut Vec<u8>, cur: &mut u8, nbits: &mut u8, mut v: u32, n: u8) {
            for _ in 0..n { put_bit(out, cur, nbits, v & (1 << (n - 1)) != 0); v <<= 1; }
        }
        for b in b"ABCDEFGH" {
            put_bit(&mut out, &mut cur, &mut nbits, true);
            put_bits(&mut out, &mut cur, &mut nbits, *b as u32, 8);
        }
        // back-ref pointing at the first literal (ring write starts at
        // N-F=239): j=10 → asks for 12 bytes (j+2) past the 8 literals
        put_bit(&mut out, &mut cur, &mut nbits, false);
        put_bits(&mut out, &mut cur, &mut nbits, (N - LZSS_F) as u32, 8);
        put_bits(&mut out, &mut cur, &mut nbits, 10, 4);
        if nbits > 0 { out.push(cur << (8 - nbits)); }

        let mut buf = Vec::new();
        nsa_lzss_decompress_to(&out, 10, &mut buf).unwrap();
        assert_eq!(buf.len(), 10, "output must be clamped to the declared size");
        assert_eq!(&buf[0..8], b"ABCDEFGH");
        assert_eq!(&buf[8..10], b"AB", "clamped tail must be the first 2 back-ref bytes");
    }

    #[test]
    fn spb_huge_dimensions_rejected() {
        // width=height=0xFFFF declares ~12.8GB of pixels; the 64MB cap must
        // reject it before the allocation.
        let mut data = vec![0u8; 16];
        data[0] = 0xFF; data[1] = 0xFF; // width 65535
        data[2] = 0xFF; data[3] = 0xFF; // height 65535
        let err = nsa_spb_decompress(&data, 100).unwrap_err();
        assert!(err.contains("too large"), "unexpected error: {err}");
        // Small dimensions pass the size gate — any failure here must not be
        // the size cap.
        let mut ok = vec![0u8; 16];
        ok[0] = 0x10; ok[1] = 0x00; // width 16
        ok[2] = 0x10; ok[3] = 0x00; // height 16
        if let Err(e) = nsa_spb_decompress(&ok, 100) {
            assert!(!e.contains("too large"), "small dims must pass the size gate: {e}");
        }
    }

    #[test]
    fn lzss_streams_large_output_chunked() {
        // ~2MB repeating pattern — exercises the 64KB chunk flush path.
        let data: Vec<u8> = {
            let pat = b"the quick brown fox jumps over the lazy dog 0123456789 ";
            (0..(2 * 1024 * 1024)).map(|i| pat[i % pat.len()]).collect()
        };
        let mut enc = Vec::new();
        lzss_encode(&data, &mut enc).unwrap();
        let mut writer = std::io::sink();
        nsa_lzss_decompress_to(&enc, data.len() as u32, &mut writer).unwrap();
        // round-trip through a memory buffer to assert byte-exact output
        let mut buf = Vec::new();
        nsa_lzss_decompress_to(&enc, data.len() as u32, &mut buf).unwrap();
        assert_eq!(buf, data);
    }
}

#[cfg(test)]
mod security_tests {
    use super::*;

    /// A malicious NSA header declaring a 3GB csize must be rejected cleanly
    /// (no OOM allocation, no crash) before any buffer is allocated.
    #[test]
    fn nsa_huge_csize_rejected_cleanly() {
        let dir = std::env::temp_dir().join(format!("uu_nsa_bomb_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        // count + 4 pad + entry(name\0, comp=2, offset=0, csize=3GB, usize=100) + tiny data
        let mut out = Vec::new();
        out.extend_from_slice(&1u16.to_be_bytes());
        out.extend_from_slice(&[0u8; 4]);
        out.extend_from_slice(b"bomb.bin");
        out.push(0);
        out.push(2);
        out.extend_from_slice(&0u32.to_be_bytes());
        out.extend_from_slice(&0xC000_0000u32.to_be_bytes()); // 3GB
        out.extend_from_slice(&100u32.to_be_bytes());
        out.extend_from_slice(b"tiny");
        let nsa = dir.join("bomb.nsa");
        std::fs::write(&nsa, &out).unwrap();

        let outdir = dir.join("out");
        std::fs::create_dir_all(&outdir).unwrap();
        let res = (|| -> Result<(), String> {
            let (ents, ds, mut f) = open_nsa(nsa.to_str().unwrap())?;
            extract_nsa_entry(&ents, &mut f, 0, outdir.to_str().unwrap(), ds)
        })();
        assert!(res.is_err(), "huge csize must be rejected, got {:?}", res);
        std::fs::remove_dir_all(&dir).ok();
    }

    /// create_nsa (stored + LZSS) → extract round-trips byte-identically.
    #[test]
    fn create_then_extract_round_trip() {
        let dir = std::env::temp_dir().join(format!("uu_nsa_create_{}", std::process::id()));
        std::fs::create_dir_all(dir.join("src/sub")).unwrap();
        let a = dir.join("src/hello.txt");
        let b = dir.join("src/sub/data.bin");
        let data_a: Vec<u8> = (0..50_000u32).map(|i| (i % 251) as u8).collect();
        let data_b: Vec<u8> = (0..4096u32).map(|i| (i % 13) as u8).collect();
        std::fs::write(&a, &data_a).unwrap();
        std::fs::write(&b, &data_b).unwrap();
        let nsa = dir.join("out.nsa");
        create_nsa(dir.join("src").to_str().unwrap(), nsa.to_str().unwrap(), 2).unwrap();

        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let (ents, ds, mut f) = open_nsa(nsa.to_str().unwrap()).unwrap();
        assert_eq!(ents.len(), 2);
        for i in 0..ents.len() {
            extract_nsa_entry(&ents, &mut f, i, out.to_str().unwrap(), ds).unwrap();
        }
        assert_eq!(std::fs::read(out.join("hello.txt")).unwrap(), data_a);
        assert_eq!(std::fs::read(out.join("sub/data.bin")).unwrap(), data_b);
        std::fs::remove_dir_all(&dir).ok();
    }

    /// create_nsa with a large file (>64 MiB store threshold) must stream it
    /// raw (no whole-file RAM buffering) and still round-trip.
    #[test]
    fn create_nsa_streams_large_file() {
        let dir = std::env::temp_dir().join(format!("uu_nsa_big_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        // 70 MiB of zeros — far above the 64 MiB LZSS threshold → stored raw.
        let big = dir.join("big.bin");
        let mut w = std::fs::File::create(&big).unwrap();
        let chunk = vec![0u8; 1 << 20];
        for _ in 0..70 { w.write_all(&chunk).unwrap(); }
        drop(w);
        let nsa = dir.join("big.nsa");
        create_nsa(dir.to_str().unwrap(), nsa.to_str().unwrap(), 2).expect("create_nsa big");

        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let (ents, ds, mut f) = open_nsa(nsa.to_str().unwrap()).unwrap();
        assert_eq!(ents.len(), 1);
        extract_nsa_entry(&ents, &mut f, 0, out.to_str().unwrap(), ds).unwrap();
        let got = std::fs::metadata(out.join("big.bin")).unwrap().len();
        assert_eq!(got, 70u64 * (1 << 20), "large file round-trips by size");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// Level 0 stores raw; verify offset math by re-parsing the header.
    #[test]
    fn level0_stores_raw_bytes() {
        let dir = std::env::temp_dir().join(format!("uu_nsa_store_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let a = dir.join("a.bin");
        std::fs::write(&a, b"abcdefghij").unwrap();
        let nsa = dir.join("s.nsa");
        create_nsa(a.to_str().unwrap(), nsa.to_str().unwrap(), 0).unwrap();
        let blob = std::fs::read(&nsa).unwrap();
        assert!(blob.windows(10).any(|w| w == b"abcdefghij"), "stored payload present verbatim");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// LZSS-fallback-to-store must count progress exactly once per file (the
    /// probe read doesn't count) so the bar never exceeds 100%.
    #[test]
    fn compress_progress_not_double_counted() {
        let dir = std::env::temp_dir().join(format!("uu_nsa_prog_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        // Random-ish data: LZSS won't shrink it → falls back to raw store.
        use std::collections::hash_map::RandomState;
        use std::hash::{BuildHasher, Hasher};
        let mut data = vec![0u8; 8192];
        let s = RandomState::new();
        for chunk in data.chunks_mut(8) {
            let mut h = s.build_hasher();
            h.write_u64(chunk.len() as u64);
            chunk.copy_from_slice(&h.finish().to_le_bytes());
        }
        std::fs::write(dir.join("r.bin"), &data).unwrap();
        compress_progress::reset(0);
        let nsa = dir.join("r.nsa");
        create_nsa(dir.to_str().unwrap(), nsa.to_str().unwrap(), 1).expect("create with LZSS fallback");
        let bytes = compress_progress::bytes();
        assert!(bytes <= 8192, "progress must not exceed file size, got {bytes}");
        std::fs::remove_dir_all(&dir).ok();
    }
}
