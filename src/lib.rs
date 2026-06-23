// ╔══════════════════════════════════════════════════════════════╗
// ║  UsefulUnpack — znso4pa — xp3-tool pattern extract           ║
// ╚══════════════════════════════════════════════════════════════╝

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, JNI_TRUE, JNI_FALSE};
use std::collections::{BTreeSet, HashSet};
use std::fs::{self, File};
use std::io::{BufReader, BufWriter, Read, Seek, SeekFrom};
use std::path::Path;
use xp3::read::XP3Archive;

fn s(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|v| v.into()).unwrap_or_default()
}

// ─── oneshot_async + SyncIo (from xp3-tool common/) ───

use core::pin::Pin;
use core::task::{Context, Poll, RawWaker, RawWakerVTable, Waker};
use std::future::Future;

fn oneshot_async<Fut: Future>(fut: Fut) -> Fut::Output {
    const VTABLE: RawWakerVTable = RawWakerVTable::new(|_| RAW, |_| {}, |_| {}, |_| {});
    const RAW: RawWaker = RawWaker::new(&(), &VTABLE);
    let waker = unsafe { Waker::from_raw(RAW) };
    let mut cx = Context::from_waker(&waker);
    let mut fut = fut;
    let mut fut = unsafe { Pin::new_unchecked(&mut fut) };
    loop {
        match fut.as_mut().poll(&mut cx) {
            Poll::Ready(v) => return v,
            // SyncIo passes through sync I/O so Pending should never happen.
            // Retry with spin_loop rather than panic so errors propagate to caller.
            Poll::Pending => std::hint::spin_loop(),
        }
    }
}

pub struct SyncIo<T>(pub T);

impl<T: std::io::Read + Unpin> tokio::io::AsyncRead for SyncIo<T> {
    fn poll_read(mut self: Pin<&mut Self>, _: &mut Context<'_>, buf: &mut tokio::io::ReadBuf<'_>) -> Poll<std::io::Result<()>> {
        match self.0.read(buf.initialize_unfilled()) {
            Ok(n) => { buf.set_filled(n); Poll::Ready(Ok(())) }
            Err(e) => Poll::Ready(Err(e))
        }
    }
}
impl<T: std::io::BufRead + Unpin> tokio::io::AsyncBufRead for SyncIo<T> {
    fn poll_fill_buf(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<std::io::Result<&[u8]>> {
        Poll::Ready(self.get_mut().0.fill_buf())
    }
    fn consume(self: Pin<&mut Self>, amt: usize) { self.get_mut().0.consume(amt); }
}
impl<T: std::io::Seek + Unpin> tokio::io::AsyncSeek for SyncIo<T> {
    fn start_seek(self: Pin<&mut Self>, pos: std::io::SeekFrom) -> std::io::Result<()> {
        self.get_mut().0.seek(pos)?; Ok(())
    }
    fn poll_complete(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<std::io::Result<u64>> {
        Poll::Ready(self.get_mut().0.stream_position())
    }
}
impl<T: std::io::Write + Unpin> tokio::io::AsyncWrite for SyncIo<T> {
    fn poll_write(self: Pin<&mut Self>, _: &mut Context<'_>, buf: &[u8]) -> Poll<std::io::Result<usize>> {
        Poll::Ready(self.get_mut().0.write(buf))
    }
    fn poll_flush(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Poll::Ready(self.get_mut().0.flush())
    }
    fn poll_shutdown(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        Poll::Ready(Ok(()))
    }
}

// ─── XP3 (matching xp3-unpacker exactly) ────────

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_ArchiveCore_xp3Extract(
    mut env: JNIEnv, _class: JClass,
    _tool: JString, input: JString, output: JString,
) -> jboolean {
    let inp = s(&mut env, &input);
    let out = s(&mut env, &output);
    extract_xp3(&inp, &out, &mut env)
}

fn extract_xp3(input: &str, output: &str, env: &mut JNIEnv) -> jboolean {
    let file = match File::open(input) {
        Ok(f) => f, Err(e) => { let _ = env.throw_new("java/io/IOException", format!("{e}")); return JNI_FALSE; }
    };
    let mut archive = match oneshot_async(XP3Archive::open(SyncIo(BufReader::new(file)))) {
        Ok(a) => a, Err(e) => { let _ = env.throw_new("java/io/IOException", format!("XP3: {e}")); return JNI_FALSE; }
    };

    let mut fail_count = 0u32;
    for i in 0..archive.entries().len() {
        let name = &archive.entries()[i].name;
        let mut dest = Path::new(output).to_path_buf();
        for comp in name.split('\\') { if comp.is_empty() { continue; } dest.push(comp); }
        if let Some(p) = dest.parent() { let _ = fs::create_dir_all(p); }

        let out_file = match File::create(&dest) {
            Ok(f) => f, Err(_) => { fail_count += 1; continue; }
        };
        let mut out_stream = SyncIo(BufWriter::new(out_file));

        let mut xf = match oneshot_async(archive.by_index(i)) {
            Some(Ok(f)) => f,
            _ => { fail_count += 1; continue; }
        };
        if oneshot_async(tokio::io::copy(&mut xf, &mut out_stream)).is_err() {
            fail_count += 1;
        }
    }
    if fail_count > 0 {
        let _ = env.throw_new("java/io/IOException", format!("XP3: {fail_count} file(s) failed to extract"));
        JNI_FALSE
    } else {
        JNI_TRUE
    }
}

// ─── PFS ────────────────────────────────────────

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_ArchiveCore_pfsExtract(
    mut env: JNIEnv, _class: JClass,
    _tool: JString, input: JString, output: JString,
) -> jboolean {
    let inp = s(&mut env, &input);
    let out = s(&mut env, &output);
    let _ = fs::create_dir_all(&out);
    match pf8::Pf8Archive::open(Path::new(&inp)) {
        Ok(mut a) => { let _ = a.extract_all(&out); JNI_TRUE }
        Err(e) => { let _ = env.throw_new("java/io/IOException", format!("PFS: {e}")); JNI_FALSE }
    }
}

// ─── NSA / SAR (NScripter) ────────────────────

struct NsaEntry {
    name: String,
    offset: u64,
    compressed: bool,
    csize: u64,
    usize: u64,
}

fn open_nsa(input: &str) -> Result<(Vec<NsaEntry>, u64, File), String> {
    let mut file = File::open(input).map_err(|e| format!("{e}"))?;
    let mut hdr = [0u8; 6];
    file.read_exact(&mut hdr).map_err(|e| format!("{e}"))?;
    let count = u16::from_be_bytes([hdr[0], hdr[1]]) as usize;
    if count > 100000 { return Err("Invalid archive (too many files)".to_string()); }
    let mut entries = Vec::with_capacity(count);
    for _ in 0..count {
        let mut nb = Vec::new();
        loop { let mut b = [0u8; 1]; file.read_exact(&mut b).map_err(|e| format!("{e}"))?; if b[0] == 0 { break; } nb.push(b[0]); }
        let name = String::from_utf8(nb).map_err(|_| "Invalid UTF-8".to_string())?;
        let mut comp = [0u8; 1]; file.read_exact(&mut comp).map_err(|e| format!("{e}"))?;
        let compressed = comp[0] != 0;
        let mut buf = [0u8; 4];
        file.read_exact(&mut buf).map_err(|e| format!("{e}"))?; let offset = u32::from_be_bytes(buf) as u64;
        file.read_exact(&mut buf).map_err(|e| format!("{e}"))?; let csize = u32::from_be_bytes(buf) as u64;
        file.read_exact(&mut buf).map_err(|e| format!("{e}"))?; let usize_val = u32::from_be_bytes(buf) as u64;
        entries.push(NsaEntry { name: name.replace('\\', "/"), offset, compressed, csize, usize: usize_val });
    }
    // Use actual file position after reading index, not header's idx_sz
    // (some implementations include the 6-byte header in idx_sz, others don't)
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
        use flate2::read::ZlibDecoder;
        use std::io::Read as _;
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
    let (entries, _data_start, _file) = open_nsa(input)?;
    let names: Vec<&str> = entries.iter().map(|e| e.name.as_str()).collect();
    let dirs = derive_dirs(&names);
    let mut all: Vec<(String, u64, bool)> = Vec::new();
    for d in &dirs { all.push((d.clone(), 0, true)); }
    for e in &entries { all.push((e.name.clone(), e.usize, false)); }
    all.sort_by(|a, b| a.0.cmp(&b.0));
    let items: Vec<String> = all.iter().map(|(n, s, d)| {
        let sz = if *d { 0_u64 } else { *s };
        format!(r#"{{"n":"{}","s":{},"d":{},"e":false}}"#, json_escape(n), sz, d)
    }).collect();
    Ok(format!("[{}]", items.join(",")))
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_ArchiveCore_nsaExtract(
    mut env: JNIEnv, _class: JClass,
    _tool: JString, input: JString, output: JString,
) -> jboolean {
    let inp = s(&mut env, &input);
    let out = s(&mut env, &output);
    let _ = fs::create_dir_all(&out);
    match open_nsa(&inp) {
        Ok((entries, data_start, mut file)) => {
            let mut fail = 0u32;
            for i in 0..entries.len() {
                if extract_nsa_entry(&entries, &mut file, i, &out, data_start).is_err() {
                    fail += 1;
                }
            }
            if fail > 0 {
                let _ = env.throw_new("java/io/IOException", format!("NSA: {fail} file(s) failed"));
                JNI_FALSE
            } else { JNI_TRUE }
        }
        Err(e) => { let _ = env.throw_new("java/io/IOException", format!("NSA: {e}")); JNI_FALSE }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_ArchiveCore_nsaExtractSelected(
    mut env: JNIEnv, _class: JClass,
    _tool: JString, input: JString, output: JString, selected: JString,
) -> jboolean {
    let inp = s(&mut env, &input);
    let out = s(&mut env, &output);
    let sel = s(&mut env, &selected);
    let sel_set: HashSet<&str> = sel.lines().filter(|l| !l.is_empty()).collect();
    if sel_set.is_empty() { return JNI_FALSE; }
    let _ = fs::create_dir_all(&out);
    match open_nsa(&inp) {
        Ok((entries, data_start, mut file)) => {
            let mut fail = 0u32;
            for (i, e) in entries.iter().enumerate() {
                if sel_set.contains(e.name.as_str()) || sel_set.iter().any(|d| e.name.starts_with(&format!("{d}/"))) {
                    if extract_nsa_entry(&entries, &mut file, i, &out, data_start).is_err() {
                        fail += 1;
                    }
                }
            }
            if fail > 0 {
                let _ = env.throw_new("java/io/IOException", format!("NSA: {fail} file(s) failed"));
                JNI_FALSE
            } else { JNI_TRUE }
        }
        Err(e) => { let _ = env.throw_new("java/io/IOException", format!("NSA: {e}")); JNI_FALSE }
    }
}

// ─── ISO 9660 ────────────────────────────────

fn iso_walk<'a>(node: &'a isomage::TreeNode, prefix: &str, out: &mut Vec<(String, &'a isomage::TreeNode)>) {
    let path = if prefix.is_empty() { node.name.clone() } else { format!("{prefix}/{}", node.name) };
    out.push((path.clone(), node));
    for child in &node.children { iso_walk(child, &path, out); }
}

fn iso_map<'a>(root: &'a isomage::TreeNode) -> Vec<(String, &'a isomage::TreeNode)> {
    let mut map = Vec::new();
    for child in &root.children { iso_walk(child, "", &mut map); }
    map
}

fn list_iso(input: &str) -> Result<String, String> {
    let mut file = std::fs::File::open(input).map_err(|e| format!("{e}"))?;
    let root = isomage::detect_and_parse_filesystem(&mut file, input).map_err(|e| format!("ISO: {e}"))?;
    let mut map = iso_map(&root);
    map.sort_by(|a, b| a.0.cmp(&b.0));
    let items: Vec<String> = map.iter().map(|(p, n)| {
        format!(r#"{{"n":"{}","s":{},"d":{},"e":false}}"#, json_escape(p), n.size, n.is_directory)
    }).collect();
    Ok(format!("[{}]", items.join(",")))
}

fn extract_iso_one(file: &mut std::fs::File, node: &isomage::TreeNode, output: &str, rel_path: &str) -> Result<(), String> {
    if node.is_directory { return Ok(()); }
    let mut dest = std::path::Path::new(output).to_path_buf();
    for comp in rel_path.split('/') { if !comp.is_empty() { dest.push(comp); } }
    if let Some(p) = dest.parent() { std::fs::create_dir_all(p).map_err(|e| format!("{e}"))?; }
    let mut data = Vec::new();
    isomage::cat_node(file, node, &mut data).map_err(|e| format!("{e}"))?;
    std::fs::write(&dest, &data).map_err(|e| format!("{e}"))?;
    Ok(())
}

fn extract_iso_all(input: &str, output: &str) -> Result<u32, String> {
    let mut file = std::fs::File::open(input).map_err(|e| format!("{e}"))?;
    let root = isomage::detect_and_parse_filesystem(&mut file, input).map_err(|e| format!("ISO: {e}"))?;
    isomage::extract_node(&mut file, &root, output).map_err(|e| format!("{e}"))?;
    Ok(0)
}

fn extract_iso_selected(input: &str, output: &str, selected: &str) -> Result<u32, String> {
    let sel_set: std::collections::HashSet<&str> = selected.lines().filter(|l| !l.is_empty()).collect();
    if sel_set.is_empty() { return Ok(0); }
    let mut file = std::fs::File::open(input).map_err(|e| format!("{e}"))?;
    let root = isomage::detect_and_parse_filesystem(&mut file, input).map_err(|e| format!("ISO: {e}"))?;
    let map = iso_map(&root);
    let mut expanded = std::collections::HashSet::new();
    for s in &sel_set {
        let key = s.trim_start_matches('/');
        expanded.insert(key.to_string());
        let prefix = format!("{key}/");
        for (p, _) in &map { if p.starts_with(&prefix) { expanded.insert(p.clone()); } }
    }
    let mut fail = 0u32;
    for p in &expanded {
        match map.iter().find(|(mp, _)| mp == p) {
            Some((_, node)) => { if extract_iso_one(&mut file, node, output, p).is_err() { fail += 1; } }
            None => fail += 1,
        }
    }
    Ok(fail)
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_ArchiveCore_isoExtract(
    mut env: JNIEnv, _: JClass, _t: JString, input: JString, output: JString,
) -> jboolean {
    let inp = s(&mut env, &input); let out = s(&mut env, &output);
    let _ = std::fs::create_dir_all(&out);
    match extract_iso_all(&inp, &out) {
        Ok(fail) if fail == 0 => JNI_TRUE,
        Ok(fail) => { let _ = env.throw_new("java/io/IOException", format!("ISO: {fail} file(s) failed")); JNI_FALSE }
        Err(e) => { let _ = env.throw_new("java/io/IOException", format!("ISO: {e}")); JNI_FALSE }
    }
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_ArchiveCore_isoExtractSelected(
    mut env: JNIEnv, _: JClass, _t: JString, input: JString, output: JString, selected: JString,
) -> jboolean {
    let inp = s(&mut env, &input); let out = s(&mut env, &output); let sel = s(&mut env, &selected);
    match extract_iso_selected(&inp, &out, &sel) {
        Ok(fail) if fail == 0 => JNI_TRUE,
        Ok(fail) => { let _ = env.throw_new("java/io/IOException", format!("ISO: {fail} file(s) failed")); JNI_FALSE }
        Err(e) => { let _ = env.throw_new("java/io/IOException", format!("ISO: {e}")); JNI_FALSE }
    }
}

// ─── YPF (YU-RIS Package) ───────────────────

fn ypf_fname_len(m: u8) -> Option<usize> {
    match m { 0xf4=>Some(9),0xfc=>Some(10),0xf6=>Some(11),0xef=>Some(12),0xec=>Some(13),0xf1=>Some(14),0xf0=>Some(15),0xf3=>Some(16),0xe7=>Some(17),0xed=>Some(18),0xf2=>Some(19),0xd1=>Some(20),0xe4=>Some(21),0xe9=>Some(22),0xe8=>Some(23),0xee=>Some(24),0xe6=>Some(25),0xe5=>Some(26),0xea=>Some(27),0xe1=>Some(28),0xe2=>Some(29),0xe3=>Some(30),0xe0=>Some(31),0xdc=>Some(32),0xde=>Some(33),0xdd=>Some(34),0xdf=>Some(35),0xdb=>Some(36),0xda=>Some(37),0xd6=>Some(38),0xd8=>Some(39),0xd7=>Some(40),0xd9=>Some(41),0xd5=>Some(42),0xd4=>Some(43),0xd0=>Some(44),0xd2=>Some(45),0xeb=>Some(46),0xd3=>Some(47),0xcf=>Some(48),0xce=>Some(49),0xcd=>Some(50),0xcc=>Some(51),0xcb=>Some(52),0xf9=>Some(53),0xc9=>Some(54),0xc8=>Some(55), _=>None }
}

struct YpfEntry {
    name: String,
    file_type: u8,
    compressed: bool,
    usize: u32,
    asize: u32,
    offset: u32,
}

fn open_ypf(input: &str) -> Result<(Vec<YpfEntry>, File), String> {
    let mut f = File::open(input).map_err(|e| format!("{e}"))?;
    let mut m = [0u8;4]; f.read_exact(&mut m).map_err(|e| format!("{e}"))?;
    if &m != b"YPF\0" { return Err("Not a YPF file".to_string()); }
    let mut b = [0u8;4];
    f.read_exact(&mut b).map_err(|e| format!("{e}"))?; // version
    f.read_exact(&mut b).map_err(|e| format!("{e}"))?; let count = u32::from_le_bytes(b) as usize;
    f.read_exact(&mut b).map_err(|e| format!("{e}"))?; let hdr_len = u32::from_le_bytes(b);
    if count==0||count>100000 { return Err(format!("YPF: bad count {count}")); }
    if hdr_len<0x20 { return Err(format!("YPF: header too short")); }
    if (hdr_len as usize-0x20)<count*36 || (hdr_len as usize-0x20)>count*69 {
        return Err(format!("YPF: inconsistent header"));
    }
    // Read entire entry area
    let ea = (hdr_len as usize - 0x20) as usize;
    let mut er = vec![0u8; ea]; f.read_exact(&mut er).map_err(|e| format!("{e}"))?;
    let mut pos = 0usize;
    let mut ents = Vec::with_capacity(count);
    for _ in 0..count {
        pos += 4; // skip unknown0
        let fl = ypf_fname_len(er[pos]).ok_or("YPF: bad marker")?; pos+=1;
        let name = {
            let mut d = er[pos..pos+fl].to_vec();
            for b in &mut d { *b ^= 201; }
            use encoding_rs::SHIFT_JIS;
            SHIFT_JIS.decode(&d).0.into_owned().replace('\\', "/")
        };
        pos += fl;
        let ft = er[pos]; pos+=1;
        let comp = er[pos]!=0; pos+=1;
        let ul = u32::from_le_bytes([er[pos],er[pos+1],er[pos+2],er[pos+3]]); pos+=4;
        let al = u32::from_le_bytes([er[pos],er[pos+1],er[pos+2],er[pos+3]]); pos+=4;
        let off = u32::from_le_bytes([er[pos],er[pos+1],er[pos+2],er[pos+3]]); pos+=4;
        pos += 8; // end_of_record(4) + unknown5(4)
        ents.push(YpfEntry{name,file_type:ft,compressed:comp,usize:ul,asize:al,offset:off});
    }
    Ok((ents, f))
}

fn extract_ypf_entry(ents: &[YpfEntry], f: &mut File, i: usize, out: &str) -> Result<(),String> {
    let e = &ents[i];
    let mut d = Path::new(out).to_path_buf();
    for c in e.name.split('/') { if !c.is_empty() { d.push(c); } }
    if let Some(p)=d.parent() { fs::create_dir_all(p).map_err(|x| format!("{x}"))?; }
    f.seek(SeekFrom::Start(e.offset as u64)).map_err(|x| format!("{x}"))?;
    let mut raw = vec![0u8; e.asize as usize];
    f.read_exact(&mut raw).map_err(|x| format!("{x}"))?;
    if e.compressed {
        use flate2::read::ZlibDecoder;
        let mut dec = Vec::with_capacity(e.usize as usize);
        ZlibDecoder::new(&raw[..]).read_to_end(&mut dec).map_err(|x| format!("YPF zlib: {x}"))?;
        fs::write(&d, &dec).map_err(|x| format!("{x}"))?;
    } else { fs::write(&d, &raw).map_err(|x| format!("{x}"))?; }
    Ok(())
}

fn list_ypf(input: &str) -> Result<String, String> {
    let (ents, _) = open_ypf(input)?;
    let names: Vec<&str> = ents.iter().map(|e| e.name.as_str()).collect();
    let dirs = derive_dirs(&names);
    let mut all: Vec<(String,u64,bool)> = Vec::new();
    for d in &dirs { all.push((d.clone(),0,true)); }
    for e in &ents { all.push((e.name.clone(),e.usize as u64,false)); }
    all.sort_by(|a,b| a.0.cmp(&b.0));
    let items: Vec<String> = all.iter().map(|(n,s,d)|{
        format!(r#"{{"n":"{}","s":{},"d":{},"e":false}}"#,json_escape(n),if *d{0}else{*s},*d)
    }).collect();
    Ok(format!("[{}]",items.join(",")))
}

fn extract_ypf_all(i:&str,o:&str)->Result<u32,String>{
    let (ents,mut f)=open_ypf(i)?; let mut fail=0u32;
    for idx in 0..ents.len() { if extract_ypf_entry(&ents,&mut f,idx,o).is_err(){fail+=1;} }
    Ok(fail)
}
fn extract_ypf_selected(i:&str,o:&str,s:&str)->Result<u32,String>{
    let ss:HashSet<&str>=s.lines().filter(|l|!l.is_empty()).collect();
    if ss.is_empty(){return Ok(0);}
    let (ents,mut f)=open_ypf(i)?; let mut fail=0u32;
    for (idx,e) in ents.iter().enumerate() {
        if ss.contains(e.name.as_str())||ss.iter().any(|d|e.name.starts_with(&format!("{d}/"))){
            if extract_ypf_entry(&ents,&mut f,idx,o).is_err(){fail+=1;}
        }
    }
    Ok(fail)
}
#[no_mangle]pub extern "system" fn Java_com_usefulunpacker_ArchiveCore_ypfExtract(mut e:JNIEnv,_:JClass,_t:JString,i:JString,o:JString)->jboolean{
    let inp=s(&mut e,&i);let out=s(&mut e,&o);let _=fs::create_dir_all(&out);
    match extract_ypf_all(&inp,&out){Ok(f)if f==0=>JNI_TRUE,Ok(f)=>{let _=e.throw_new("java/io/IOException",format!("YPF: {f} failed"));JNI_FALSE},Err(er)=>{let _=e.throw_new("java/io/IOException",format!("YPF: {er}"));JNI_FALSE}}
}
#[no_mangle]pub extern "system" fn Java_com_usefulunpacker_ArchiveCore_ypfExtractSelected(mut e:JNIEnv,_:JClass,_t:JString,i:JString,o:JString,sel:JString)->jboolean{
    let inp=s(&mut e,&i);let out=s(&mut e,&o);let ss=s(&mut e,&sel);
    match extract_ypf_selected(&inp,&out,&ss){Ok(f)if f==0=>JNI_TRUE,Ok(f)=>{let _=e.throw_new("java/io/IOException",format!("YPF: {f} failed"));JNI_FALSE},Err(er)=>{let _=e.throw_new("java/io/IOException",format!("YPF: {er}"));JNI_FALSE}}
}

// ─── Preview / Selective Extraction ───────────

fn json_escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c.is_control() => { out.push_str(&format!("\\u{:04x}", c as u32)); }
            c => out.push(c),
        }
    }
    out
}

fn derive_dirs(paths: &[&str]) -> BTreeSet<String> {
    let mut dirs = BTreeSet::new();
    for path in paths {
        let parts: Vec<&str> = path.split('/').collect();
        for i in 1..parts.len() {
            dirs.insert(parts[..i].join("/"));
        }
    }
    dirs
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_ArchiveCore_listEntries(
    mut env: JNIEnv, _class: JClass,
    input: JString,
) -> jstring {
    let inp = s(&mut env, &input);
    let json = if inp.to_lowercase().ends_with(".xp3") {
        list_xp3(&inp)
    } else if inp.to_lowercase().ends_with(".pfs") || inp.to_lowercase().ends_with(".pf6") || inp.to_lowercase().ends_with(".pf8") {
        list_pfs(&inp)
    } else if inp.to_lowercase().ends_with(".nsa") || inp.to_lowercase().ends_with(".sar") {
        list_nsa(&inp)
    } else if inp.to_lowercase().ends_with(".iso") {
        list_iso(&inp)
    } else if inp.to_lowercase().ends_with(".ypf") {
        list_ypf(&inp)
    } else {
        let _ = env.throw_new("java/io/IOException", "Unsupported format");
        return std::ptr::null_mut();
    };
    match json {
        Ok(j) => {
            match env.new_string(&j) {
                Ok(js) => js.into_raw(),
                Err(_) => std::ptr::null_mut(),
            }
        }
        Err(e) => {
            let _ = env.throw_new("java/io/IOException", format!("listEntries: {e}"));
            std::ptr::null_mut()
        }
    }
}

fn list_xp3(input: &str) -> Result<String, String> {
    let file = File::open(input).map_err(|e| format!("{e}"))?;
    let archive = oneshot_async(XP3Archive::open(SyncIo(BufReader::new(file))))
        .map_err(|e| format!("XP3: {e}"))?;

    let raw_names: Vec<&str> = archive.entries().iter().map(|e| e.name.as_str()).collect();
    let normalized: Vec<String> = raw_names.iter().map(|n| n.replace('\\', "/")).collect();
    let norm_refs: Vec<&str> = normalized.iter().map(|s| s.as_str()).collect();
    let dirs = derive_dirs(&norm_refs);

    let mut all: Vec<(String, u64, bool)> = Vec::new();
    for d in &dirs {
        all.push((d.clone(), 0, true));
    }
    for entry in archive.entries().iter() {
        all.push((entry.name.replace('\\', "/"), entry.size, false));
    }
    all.sort_by(|a, b| a.0.cmp(&b.0));

    let entries: Vec<String> = all.iter().map(|(n, s, d)| {
        let sz = if *d { 0_u64 } else { *s };
        format!(
            r#"{{"n":"{}","s":{},"d":{},"e":false}}"#,
            json_escape(n), sz, d
        )
    }).collect();

    Ok(format!("[{}]", entries.join(",")))
}

fn list_pfs(input: &str) -> Result<String, String> {
    let archive = pf8::Pf8Archive::open(Path::new(input))
        .map_err(|e| format!("PFS: {e}"))?;

    let entry_paths: Vec<String> = archive.entries()
        .map(|e| e.path().to_string_lossy().replace('\\', "/"))
        .collect();
    let path_refs: Vec<&str> = entry_paths.iter().map(|s| s.as_str()).collect();
    let dirs = derive_dirs(&path_refs);

    let mut all: Vec<(String, u64, bool, bool)> = Vec::new();
    for d in &dirs {
        all.push((d.clone(), 0, true, false));
    }
    for entry in archive.entries() {
        let p = entry.path().to_string_lossy().replace('\\', "/");
        all.push((p, entry.size() as u64, false, entry.is_encrypted()));
    }
    all.sort_by(|a, b| a.0.cmp(&b.0));

    let entries: Vec<String> = all.iter().map(|(n, s, d, e)| {
        let sz = if *d { 0_u64 } else { *s };
        format!(
            r#"{{"n":"{}","s":{},"d":{},"e":{}}}"#,
            json_escape(n), sz, d, e
        )
    }).collect();

    Ok(format!("[{}]", entries.join(",")))
}

// ─── XP3 Selective Extract ───

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_ArchiveCore_xp3ExtractSelected(
    mut env: JNIEnv, _class: JClass,
    _tool: JString, input: JString, output: JString, selected: JString,
) -> jboolean {
    let inp = s(&mut env, &input);
    let out = s(&mut env, &output);
    let sel = s(&mut env, &selected);
    extract_xp3_selected(&inp, &out, &sel, &mut env)
}

fn extract_xp3_selected(input: &str, output: &str, selected: &str, env: &mut JNIEnv) -> jboolean {
    let sel_set: HashSet<&str> = selected.lines().filter(|l| !l.is_empty()).collect();
    if sel_set.is_empty() { return JNI_FALSE; }

    let file = match File::open(input) {
        Ok(f) => f, Err(e) => { let _ = env.throw_new("java/io/IOException", format!("{e}")); return JNI_FALSE; }
    };
    let mut archive = match oneshot_async(XP3Archive::open(SyncIo(BufReader::new(file)))) {
        Ok(a) => a, Err(e) => { let _ = env.throw_new("java/io/IOException", format!("XP3: {e}")); return JNI_FALSE; }
    };

    let mut fail_count = 0u32;
    for i in 0..archive.entries().len() {
        let raw_name = &archive.entries()[i].name;
        let norm_name = raw_name.replace('\\', "/");

        let should_extract = sel_set.contains(norm_name.as_str())
            || sel_set.iter().any(|sel_dir| {
                sel_dir.ends_with('/') && norm_name.starts_with(sel_dir)
            })
            || sel_set.iter().any(|sel_dir| {
                !sel_dir.ends_with('/') && norm_name.starts_with(&format!("{sel_dir}/"))
            });

        if !should_extract { continue; }

        let mut dest = Path::new(output).to_path_buf();
        for comp in raw_name.split('\\') { if comp.is_empty() { continue; } dest.push(comp); }
        if let Some(p) = dest.parent() { let _ = fs::create_dir_all(p); }

        let out_file = match File::create(&dest) {
            Ok(f) => f, Err(_) => { fail_count += 1; continue; }
        };
        let mut out_stream = SyncIo(BufWriter::new(out_file));

        let mut xf = match oneshot_async(archive.by_index(i)) {
            Some(Ok(f)) => f,
            _ => { fail_count += 1; continue; }
        };
        if oneshot_async(tokio::io::copy(&mut xf, &mut out_stream)).is_err() {
            fail_count += 1;
        }
    }
    if fail_count > 0 {
        let _ = env.throw_new("java/io/IOException", format!("XP3: {fail_count} selected file(s) failed"));
        JNI_FALSE
    } else {
        JNI_TRUE
    }
}

// ─── PFS Selective Extract ───

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_ArchiveCore_pfsExtractSelected(
    mut env: JNIEnv, _class: JClass,
    _tool: JString, input: JString, output: JString, selected: JString,
) -> jboolean {
    let inp = s(&mut env, &input);
    let out = s(&mut env, &output);
    let sel = s(&mut env, &selected);
    extract_pfs_selected(&inp, &out, &sel, &mut env)
}

fn extract_pfs_selected(input: &str, output: &str, selected: &str, env: &mut JNIEnv) -> jboolean {
    let sel_set: HashSet<&str> = selected.lines().filter(|l| !l.is_empty()).collect();
    if sel_set.is_empty() { return JNI_FALSE; }

    let _ = fs::create_dir_all(&output);
    let mut archive = match pf8::Pf8Archive::open(Path::new(input)) {
        Ok(a) => a, Err(e) => { let _ = env.throw_new("java/io/IOException", format!("PFS: {e}")); return JNI_FALSE; }
    };

    let to_extract: Vec<std::path::PathBuf> = archive.entries()
        .map(|e| e.path().to_path_buf())
        .filter(|p| {
            let norm = p.to_string_lossy().replace('\\', "/");
            sel_set.contains(norm.as_str())
                || sel_set.iter().any(|sel_dir| {
                    let sd = if sel_dir.ends_with('/') { &sel_dir[..sel_dir.len()-1] } else { sel_dir };
                    norm.starts_with(&format!("{sd}/"))
                })
        })
        .collect();

    let mut fail_count = 0u32;
    for entry_path in &to_extract {
        let mut dest = Path::new(output).to_path_buf();
        if let Ok(rel) = entry_path.strip_prefix("/") {
            dest.push(rel);
        } else {
            for comp in entry_path.components().skip(1) {
                dest.push(comp);
            }
        }
        if dest.to_string_lossy().is_empty() { continue; }
        if let Some(p) = dest.parent() { let _ = fs::create_dir_all(p); }
        if archive.extract_file(entry_path, &dest).is_err() {
            fail_count += 1;
        }
    }
    if fail_count > 0 {
        let _ = env.throw_new("java/io/IOException", format!("PFS: {fail_count} file(s) failed"));
        JNI_FALSE
    } else {
        JNI_TRUE
    }
}
