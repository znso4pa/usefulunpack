use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jlong, jboolean, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, safe_join, extract_result_json, ProgressWriter};
use archive_common::{extract_progress, compress_progress};
use std::collections::HashSet;
use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};

fn guarded<T: Send + 'static>(f: impl FnOnce() -> Result<T, String> + Send + 'static) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|panic| {
        let msg = panic.downcast_ref::<&str>().copied()
            .or_else(|| panic.downcast_ref::<String>().map(|s| s.as_str()))
            .unwrap_or("unknown panic");
        Err(format!("panic: {msg}"))
    })
}

/// Host-side (non-JNI) extraction entry point.
#[doc(hidden)]
pub fn extract_iso_host(input: &str, output: &str) -> Result<(u32, u32), String> {
    extract_iso_all(input, output)
}

// ─── ISO 9660 ────────────────────────────────

// Iterative DFS — deeply nested ISO trees can't overflow the stack.
fn iso_walk<'a>(start: &'a isomage::TreeNode, start_prefix: &str, out: &mut Vec<(String, &'a isomage::TreeNode)>) {
    let mut stack: Vec<(&'a isomage::TreeNode, String)> = vec![(start, start_prefix.to_string())];
    while let Some((node, prefix)) = stack.pop() {
        if extract_progress::cancelled() { return; }
        let path = if prefix.is_empty() { node.name.clone() } else { format!("{prefix}/{}", node.name) };
        out.push((path.clone(), node));
        for child in &node.children { stack.push((child, path.clone())); }
    }
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
    let dest = safe_join(output, rel_path)?;
    if let Some(p) = dest.parent() { fs::create_dir_all(p).map_err(|e| format!("{e}"))?; }
    let mut out = ProgressWriter::extract(std::fs::File::create(&dest).map_err(|e| format!("{e}"))?);
    let r = isomage::cat_node(file, node, &mut out).map_err(|e| format!("{e}"));
    if r.is_err() {
        // Don't leave a half-written file on disk.
        let _ = fs::remove_file(&dest);
    }
    r
}

fn extract_iso_all(input: &str, output: &str) -> Result<(u32, u32), String> {
    let mut file = std::fs::File::open(input).map_err(|e| format!("{e}"))?;
    let root = isomage::detect_and_parse_filesystem(&mut file, input).map_err(|e| format!("ISO: {e}"))?;
    let map = iso_map(&root);
    extract_progress::reset(map.iter().filter(|(_, n)| !n.is_directory).map(|(_, n)| n.size).sum());
    let mut fail = 0u32;
    for (path, node) in &map {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        if node.is_directory { continue; }
        extract_progress::set_name(path);
        extract_progress::set_file(node.size);
        if extract_iso_one(&mut file, node, output, path).is_err() {
            fail += 1;
        }
    }
    let total = map.len() as u32;
    Ok((total, fail))
}

fn extract_iso_selected(input: &str, output: &str, selected: &str) -> Result<(u32, u32), String> {
    let sel_set: HashSet<&str> = selected.lines().filter(|l| !l.is_empty()).collect();
    if sel_set.is_empty() { return Ok((0, 0)); }
    let mut file = std::fs::File::open(input).map_err(|e| format!("{e}"))?;
    let root = isomage::detect_and_parse_filesystem(&mut file, input).map_err(|e| format!("ISO: {e}"))?;
    let map = iso_map(&root);
    // O(1) lookups instead of a linear scan per expanded path.
    let by_path: std::collections::HashMap<&str, &isomage::TreeNode> =
        map.iter().map(|(p, n)| (p.as_str(), *n)).collect();
    let mut expanded = HashSet::new();
    for s in &sel_set {
        let key = s.trim_start_matches('/');
        expanded.insert(key.to_string());
        let prefix = format!("{key}/");
        for (p, _) in &map { if p.starts_with(&prefix) { expanded.insert(p.clone()); } }
    }
    extract_progress::reset(expanded.iter().filter_map(|p| by_path.get(p.as_str())).filter(|n| !n.is_directory).map(|n| n.size).sum());
    let mut fail = 0u32;
    for p in &expanded {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        match by_path.get(p.as_str()) {
            Some(node) => {
                if node.is_directory { continue; }
                extract_progress::set_name(p);
                extract_progress::set_file(node.size);
                if extract_iso_one(&mut file, node, output, p).is_err() { fail += 1; }
            }
            None => fail += 1,
        }
    }
    let total = expanded.len() as u32;
    Ok((total, fail))
}

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoExtractProgressFileCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoExtractProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoExtractProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = fs::create_dir_all(&out);
    match guarded(move || extract_iso_all(&inp, &out)) {
        Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoExtractSelected(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel_j: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel_j);
    match guarded(move || extract_iso_selected(&inp, &out, &sel_str)) {
        Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i);
    match guarded(move || list_iso(&inp)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}

// ─── ISO 9660 (Level 1) writer ────────────────────────────────
//
// Layout: sector 0-15 system area (zeroed), 16 = PVD, 17 = descriptor
// terminator, 18-19 unused; directories and file data are packed into the
// sectors that follow, each 2048 bytes. Level 1 naming: files are
// `NAME.EXT;1` with the name ≤ 8 chars and extension ≤ 3 (both uppercase),
// directories `NAME;1` ≤ 8 chars. Subdirectory records reference the child
// directory's data sector; file records reference the file's data sector.

const ISO_SECTOR: u64 = 2048;
const ISO_PVD_SECTOR: u64 = 16;

/// One planned file: its ISO-level name, source path, size.
struct IsoFile {
    iso_name: String,
    src: PathBuf,
    size: u64,
    sector: u64,
}

/// One planned directory (built recursively).
struct IsoDir {
    iso_name: String,        // ISO-level dir name ("" for root)
    files: Vec<IsoFile>,
    dirs: Vec<IsoDir>,
    sector: u64,             // data sector (assigned in layout pass)
    data_len: u32,           // directory data byte count (records only)
}

/// Converts a host filename to ISO 9660 Level 1: only `[A-Z0-9_]` survive
/// (Level 1 forbids lowercase and most punctuation; internal dots would break
/// the 8.3 split), capped at 8 for the stem and 3 for the extension. Returns
/// (name, extension).
fn l1_split(name: &str) -> (String, String) {
    let upper = name.to_uppercase();
    let clean: String = upper.chars().map(|c| if c.is_ascii_alphanumeric() || c == '_' { c } else { ' ' }).collect();
    // Split on the first space run — everything after a non-identifier char
    // becomes the extension hint (the LAST token, matching file.ext semantics).
    let parts: Vec<&str> = clean.split(' ').filter(|p| !p.is_empty()).collect();
    if parts.is_empty() {
        return ("FILE".to_string(), String::new());
    }
    if parts.len() == 1 {
        let stem = &parts[0][..parts[0].len().min(8)];
        return (stem.to_string(), String::new());
    }
    let stem = &parts[0][..parts[0].len().min(8)];
    // Last identifier is the extension; anything between is folded into stem.
    let ext = &parts[parts.len() - 1][..parts[parts.len() - 1].len().min(3)];
    (stem.to_string(), ext.to_string())
}

/// Resolves 8.3 name collisions within one directory: files and subdirectories
/// share the same Level-1 namespace, and two hostnames can map to the same
/// truncated ISO name. A duplicate gets a `~N` suffix on the stem (e.g.
/// LONGFILE.TXT → LONGFI~1.TXT) so every record stays unique.
fn dedup_iso_names(files: &mut Vec<IsoFile>, dirs: &mut Vec<IsoDir>) {
    use std::collections::HashMap;
    let mut seen: HashMap<String, u32> = HashMap::new();
    let mut fix = |iso_name: &mut String| {
        let mut n = *seen.get(iso_name).unwrap_or(&0);
        seen.insert(iso_name.clone(), n + 1);
        if n > 0 {
            // suffix ~N must fit in 8-char stem: base name minus suffix.
            // Strip the `;N` version marker first so the suffix lands before it
            // (a `;` mid-name would be an illegal Level-1 name).
            let (base, ver) = match iso_name.rfind(';') {
                Some(i) => (iso_name[..i].to_string(), iso_name[i..].to_string()),
                None => (iso_name.clone(), String::new()),
            };
            loop {
                let (stem, ext) = match base.rfind('.') {
                    Some(i) => (base[..i].to_string(), base[i..].to_string()),
                    None => (base.clone(), String::new()),
                };
                let suffix = format!("~{}", n);
                let room = 8usize.saturating_sub(suffix.len());
                let new_stem = if stem.len() > room {
                    format!("{}{}", &stem[..room], suffix)
                } else {
                    format!("{}{}", stem, suffix)
                };
                let candidate = format!("{}{}{}", new_stem, ext, ver);
                let c = seen.entry(candidate.clone()).or_insert(0);
                *c += 1;
                if *c == 1 {
                    *iso_name = candidate;
                    break;
                }
                n += 1;
            }
        }
    };
    for f in files.iter_mut() { fix(&mut f.iso_name); }
    for d in dirs.iter_mut() { fix(&mut d.iso_name); }
}

/// Collects files under `base` into a directory tree (rel paths use '/').
fn collect_iso_tree(base: &Path) -> Result<IsoDir, String> {
    fn build(base: &Path, rel: &str) -> Result<IsoDir, String> {
        let mut dir = IsoDir {
            iso_name: if rel.is_empty() { String::new() } else {
                let n = rel.rsplit('/').next().unwrap_or(rel);
                let (s, _e) = l1_split(n); s
            },
            files: Vec::new(),
            dirs: Vec::new(),
            sector: 0,
            data_len: 0,
        };
        let mut entries: Vec<_> = fs::read_dir(base).map_err(|e| format!("read_dir {}: {e}", base.display()))?
            .collect::<Result<_, _>>().map_err(|e| format!("read_dir {}: {e}", base.display()))?;
        entries.sort_by_key(|e| e.file_name());
        for entry in entries {
            let path = entry.path();
            let meta = entry.metadata().map_err(|e| format!("metadata {}: {e}", path.display()))?;
            let name = entry.file_name().to_string_lossy().to_string();
            if meta.is_file() {
                if meta.len() >= u32::MAX as u64 {
                    return Err(format!("ISO: file too large for Level 1 (>4GiB): {name}"));
                }
                let (stem, ext) = l1_split(&name);
                let iso_name = if ext.is_empty() { format!("{stem};1") } else { format!("{stem}.{ext};1") };
                dir.files.push(IsoFile {
                    iso_name, src: path, size: meta.len(), sector: 0,
                });
            } else if meta.is_dir() {
                let child_rel = if rel.is_empty() { name.clone() } else { format!("{rel}/{name}") };
                let sub = build(&path, &child_rel)?;
                dir.dirs.push(sub);
            }
        }
        dedup_iso_names(&mut dir.files, &mut dir.dirs);
        Ok(dir)
    }
    if base.is_file() {
        let name = base.file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
        let parent = base.parent().unwrap_or(base);
        let mut root = IsoDir { iso_name: String::new(), files: Vec::new(), dirs: Vec::new(), sector: 0, data_len: 0 };
        let (stem, ext) = l1_split(&name);
        let iso_name = if ext.is_empty() { format!("{stem};1") } else { format!("{stem}.{ext};1") };
        root.files.push(IsoFile { iso_name, src: base.to_path_buf(), size: base.metadata().map(|m| m.len()).unwrap_or(0), sector: 0 });
        let _ = parent;
        return Ok(root);
    }
    build(base, "")
}

/// Builds one directory record blob for a file or child dir.
fn iso_dir_record(iso_name: &str, extent: u64, data_len: u64, is_dir: bool) -> Vec<u8> {
    let flags: u8 = if is_dir { 0x02 } else { 0x00 };
    let name_bytes = iso_name.as_bytes();
    let pad = (name_bytes.len() + 1) % 2; // odd name → pad byte so records are even
    let mut rec = vec![0u8; 33 + name_bytes.len() + pad];
    rec[0] = rec.len() as u8;                 // record length
    rec[1] = 0;                               // extended attribute record length
    let ext = extent as u32;
    rec[2..6].copy_from_slice(&ext.to_le_bytes());
    rec[6..10].copy_from_slice(&ext.to_be_bytes());
    let dl = data_len as u32;
    rec[10..14].copy_from_slice(&dl.to_le_bytes());
    rec[14..18].copy_from_slice(&dl.to_be_bytes());
    // date (7 bytes): 6 packed digits + GMT offset — zero is "not recorded".
    // rec[18..25] stays zero.
    rec[25] = flags;                          // file flags
    rec[26] = 0; rec[27] = 0;                 // unit size, interleave gap
    rec[28..30].copy_from_slice(&1u16.to_le_bytes()); // volume seq num LE
    rec[30..32].copy_from_slice(&1u16.to_be_bytes()); // volume seq num BE
    rec[32] = name_bytes.len() as u8;
    rec[33..33 + name_bytes.len()].copy_from_slice(name_bytes);
    rec
}

/// Serializes a directory's data area (records for '.', '..', files, child dirs).
/// `parent_sector` is the parent directory's data sector (root points at itself).
fn iso_dir_data(dir: &IsoDir, parent_sector: u64) -> Vec<u8> {
    let mut blob = Vec::new();
    blob.extend(iso_dir_record("\x00", dir.sector, dir.data_len as u64, true));        // "."
    blob.extend(iso_dir_record("\x01", parent_sector, 0, true));                       // ".."
    for f in &dir.files {
        blob.extend(iso_dir_record(&f.iso_name, f.sector, f.size, false));
    }
    for d in &dir.dirs {
        blob.extend(iso_dir_record(&d.iso_name, d.sector, d.data_len as u64, true));
    }
    blob
}

/// Recursive sector allocation: a directory is assigned its data sector, then
/// its files, then each child directory (recursively — children get sectors
/// AND their own children/files, depth-first). Returns the next free sector.
fn alloc_dir(dir: &mut IsoDir, mut s: u64) -> u64 {
    dir.sector = s;
    s += (dir.data_len as u64).div_ceil(ISO_SECTOR);
    for f in &mut dir.files {
        f.sector = s;
        s += (f.size).div_ceil(ISO_SECTOR);
    }
    for d in &mut dir.dirs {
        s = alloc_dir(d, s);
    }
    s
}

/// Two-pass layout: compute every directory's data_len bottom-up (record blob
/// lengths depend only on names/sizes, not sectors), then allocate sectors
/// recursively depth-first.
fn layout_iso(root: &mut IsoDir, next_sector: u64) -> u64 {
    // Pre-pass: compute every directory's data_len bottom-up.
    fn compute_lens(dir: &mut IsoDir) {
        for d in &mut dir.dirs { compute_lens(d); }
        let mut len: u64 = 0;
        len += iso_dir_record("\x00", 0, 0, true).len() as u64;
        len += iso_dir_record("\x01", 0, 0, true).len() as u64;
        for f in &dir.files { len += iso_dir_record(&f.iso_name, 0, 0, false).len() as u64; }
        for d in &dir.dirs { len += iso_dir_record(&d.iso_name, 0, 0, true).len() as u64; }
        dir.data_len = len as u32;
    }
    compute_lens(root);
    alloc_dir(root, next_sector)
}

/// Writes the finished ISO image. `root` must be laid out first.
fn write_iso(root: &IsoDir, output: &str) -> Result<(), String> {
    // Total size: last data block end. Walk the tree to find max sector+blocks.
    fn tree_end(dir: &IsoDir) -> u64 {
        let mut end = dir.sector + (dir.data_len as u64).div_ceil(ISO_SECTOR);
        for f in &dir.files {
            end = end.max(f.sector + (f.size).div_ceil(ISO_SECTOR));
        }
        for d in &dir.dirs { end = end.max(tree_end(d)); }
        end
    }
    let total_blocks = tree_end(root);
    let mut img = vec![0u8; (total_blocks * ISO_SECTOR) as usize];
    // PVD.
    let pvd = &mut img[(ISO_PVD_SECTOR * ISO_SECTOR) as usize..((ISO_PVD_SECTOR + 1) * ISO_SECTOR) as usize];
    pvd[0] = 1;
    pvd[1..6].copy_from_slice(b"CD001");
    pvd[6] = 1;
    // Volume space size (blocks, LE+BE).
    pvd[80..84].copy_from_slice(&(total_blocks as u32).to_le_bytes());
    pvd[84..88].copy_from_slice(&(total_blocks as u32).to_be_bytes());
    // Root dir record at PVD offset 156.
    let root_rec = iso_dir_record("\x00", root.sector, root.data_len as u64, true);
    pvd[156..156 + root_rec.len()].copy_from_slice(&root_rec);
    // Descriptor terminator at sector 17.
    let term = &mut img[(17 * ISO_SECTOR) as usize..(18 * ISO_SECTOR) as usize];
    term[0] = 255;
    term[1..6].copy_from_slice(b"CD001");
    term[6] = 1;
    // Directory data + file data.
    fn write_node(img: &mut [u8], dir: &IsoDir, parent_sector: u64) -> Result<(), String> {
        let blob = iso_dir_data(dir, parent_sector);
        let base = (dir.sector * ISO_SECTOR) as usize;
        img[base..base + blob.len()].copy_from_slice(&blob);
        for f in &dir.files {
            let start = (f.sector * ISO_SECTOR) as usize;
            let mut src = fs::File::open(&f.src).map_err(|e| format!("ISO open {}: {e}", f.src.display()))?;
            src.read_exact(&mut img[start..start + f.size as usize])
                .map_err(|e| format!("ISO read {}: {e}", f.src.display()))?;
            compress_progress::add_bytes(f.size);
            compress_progress::set_name(&f.src.file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default());
            compress_progress::set_file(f.size);
        }
        for d in &dir.dirs { write_node(img, d, dir.sector)?; }
        Ok(())
    }
    write_node(&mut img, root, root.sector)?;
    fs::write(output, &img).map_err(|e| format!("ISO write {output}: {e}"))
}

/// Packs a directory (or single file) into an ISO 9660 Level 1 image.
fn create_iso(input: &str, output: &str) -> Result<u32, String> {
    let mut root = collect_iso_tree(Path::new(input))?;
    if root.files.is_empty() && root.dirs.is_empty() {
        return Err("ISO: no files to archive".to_string());
    }
    let total: u64 = root.files.iter().map(|f| f.size).sum();
    // The writer builds the whole image in memory (simplest correct layout);
    // cap the total so a multi-GB directory can't OOM the app.
    if total > 1024 * 1024 * 1024 {
        return Err("ISO: total size exceeds 1 GiB (packing limit)".to_string());
    }
    compress_progress::reset(total);
    layout_iso(&mut root, 20);
    write_iso(&root, output)?;
    Ok(root.files.len() as u32)
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoCreateArchive(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jboolean {
    compress_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o);
    match guarded(move || create_iso(&inp, &out)) {
        Ok(_) => JNI_TRUE,
        Err(er) => { let _ = e.throw_new("java/io/IOException", format!("iso: {er}")); JNI_FALSE }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoCompressProgressCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoCompressProgressTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoCompressProgressFileCount(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoCompressProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { compress_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoCompressProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&compress_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoCompressCancel(_: JNIEnv, _: JClass) { compress_progress::cancel(); }

#[cfg(test)]
mod tests {

    /// 进度 store 是 per-cdylib 的**静态量**，cargo 默认并行跑同一个 crate
    /// 的测试，两个测试的 `reset(total)` + `add_bytes` 会互相踩：抢在前面的那个
    /// 会用自己的夹具尺寸改掉 total，后一个断言 total 的测试就红。凡是调了
    /// extract/compress 入口的测试都必须持这把锁。
    ///
    /// 实证：`archive_lzma-core` 的 `extract_progress_total_is_reported` 曾在 CI 上
    /// 以 `left: 327, right: 119` 失败，本地 25/25 通过。
    static PROGRESS_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
    fn progress_lock() -> std::sync::MutexGuard<'static, ()> {
        PROGRESS_LOCK.lock().unwrap_or_else(|e| e.into_inner())
    }
    use super::*;

    const SECTOR: usize = 2048;

    fn dir_record(name: &[u8], extent: u32, length: u32, flags: u8) -> Vec<u8> {
        let pad = if name.len() % 2 == 0 { 0 } else { 1 };
        let mut rec = vec![0u8; 33 + name.len() + pad];
        rec[0] = rec.len() as u8;
        rec[2..6].copy_from_slice(&extent.to_le_bytes());
        rec[6..10].copy_from_slice(&extent.to_be_bytes());
        rec[10..14].copy_from_slice(&length.to_le_bytes());
        rec[14..18].copy_from_slice(&length.to_be_bytes());
        rec[25] = flags;
        rec[32] = name.len() as u8;
        rec[33..33 + name.len()].copy_from_slice(name);
        rec
    }

    /// Builds a minimal ISO9660 image:
    ///   sector 16  PVD (root record at offset 156)
    ///   sector 17  volume descriptor set terminator
    ///   sector 20  root directory data
    ///   sector 21  file "HELLO.TXT"
    fn make_iso(file_content: &[u8]) -> Vec<u8> {
        let root_sector = 20u32;
        let file_sector = 21u32;
        let root_len = 2048u32;
        let mut root_dir = Vec::new();
        root_dir.extend(dir_record(&[0u8], root_sector, root_len, 0x02));   // "."
        root_dir.extend(dir_record(&[1u8], root_sector, root_len, 0x02));   // ".."
        root_dir.extend(dir_record(b"HELLO.TXT", file_sector, file_content.len() as u32, 0x00));
        root_dir.resize(SECTOR, 0);

        let mut iso = vec![0u8; 22 * SECTOR];
        // PVD
        let pvd = &mut iso[16 * SECTOR..17 * SECTOR];
        pvd[0] = 1;
        pvd[1..6].copy_from_slice(b"CD001");
        pvd[6] = 1;
        let root_rec = dir_record(&[0u8], root_sector, root_len, 0x02);
        pvd[156..156 + root_rec.len()].copy_from_slice(&root_rec);
        // Terminator
        let term = &mut iso[17 * SECTOR..18 * SECTOR];
        term[0] = 255;
        term[1..6].copy_from_slice(b"CD001");
        term[6] = 1;
        // Root directory data
        iso[20 * SECTOR..21 * SECTOR].copy_from_slice(&root_dir);
        // File data
        iso[21 * SECTOR..21 * SECTOR + file_content.len()].copy_from_slice(file_content);
        iso
    }

    #[test]
    fn streams_iso_file_extraction() {
    let _g = progress_lock();
        let content = b"HELLO ISO!";
        let dir = std::env::temp_dir().join(format!("uu_iso_x_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let iso_path = dir.join("test.iso");
        std::fs::write(&iso_path, make_iso(content)).unwrap();

        let list = list_iso(iso_path.to_str().unwrap()).unwrap();
        assert!(list.contains("HELLO.TXT"), "list: {list}");

        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        extract_iso_all(iso_path.to_str().unwrap(), out.to_str().unwrap()).unwrap();
        assert_eq!(std::fs::read(out.join("HELLO.TXT")).unwrap(), content);
        std::fs::remove_dir_all(&dir).ok();
    }

    /// create_iso (Level 1 writer) → list + extract round-trips.
    #[test]
    fn create_iso_then_extract_round_trip() {
    let _g = progress_lock();
        let dir = std::env::temp_dir().join(format!("uu_iso_w_{}", std::process::id()));
        std::fs::create_dir_all(dir.join("src/sub")).unwrap();
        let a = dir.join("src/hello.txt");
        let b = dir.join("src/sub/data.bin");
        let content_a = b"hello iso world";
        let content_b: Vec<u8> = (0..5000u32).map(|i| (i % 251) as u8).collect();
        std::fs::write(&a, content_a).unwrap();
        std::fs::write(&b, &content_b).unwrap();

        let iso_path = dir.join("out.iso");
        create_iso(dir.join("src").to_str().unwrap(), iso_path.to_str().unwrap()).expect("create_iso");

        let list = list_iso(iso_path.to_str().unwrap()).unwrap();
        assert!(list.contains("HELLO.TXT"), "list: {list}");
        assert!(list.contains("DATA.BIN"), "list: {list}");

        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        extract_iso_all(iso_path.to_str().unwrap(), out.to_str().unwrap()).unwrap();
        assert_eq!(std::fs::read(out.join("HELLO.TXT")).unwrap(), content_a);
        assert_eq!(std::fs::read(out.join("SUB/DATA.BIN")).unwrap(), content_b);
        std::fs::remove_dir_all(&dir).ok();
    }

    /// Deeply nested directories (4+ levels) must survive layout + extraction —
    /// sector allocation is recursive, not limited to two levels.
    #[test]
    fn create_iso_deep_nesting_round_trip() {
    let _g = progress_lock();
        let dir = std::env::temp_dir().join(format!("uu_iso_deep_{}", std::process::id()));
        std::fs::create_dir_all(dir.join("a/b/c/d")).unwrap();
        std::fs::write(dir.join("a/root.txt"), b"root").unwrap();
        std::fs::write(dir.join("a/b/one.txt"), b"one").unwrap();
        std::fs::write(dir.join("a/b/c/two.txt"), b"two").unwrap();
        std::fs::write(dir.join("a/b/c/d/three.txt"), b"three").unwrap();
        let iso_path = dir.join("deep.iso");
        create_iso(dir.join("a").to_str().unwrap(), iso_path.to_str().unwrap()).expect("create_iso deep");

        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        extract_iso_all(iso_path.to_str().unwrap(), out.to_str().unwrap()).unwrap();
        assert_eq!(std::fs::read(out.join("ROOT.TXT")).unwrap(), b"root");
        assert_eq!(std::fs::read(out.join("B/ONE.TXT")).unwrap(), b"one");
        assert_eq!(std::fs::read(out.join("B/C/TWO.TXT")).unwrap(), b"two");
        assert_eq!(std::fs::read(out.join("B/C/D/THREE.TXT")).unwrap(), b"three");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// 8.3 collisions get distinct `~N` names so records don't overwrite.
    #[test]
    fn create_iso_dedups_83_collisions() {
    let _g = progress_lock();
        let dir = std::env::temp_dir().join(format!("uu_iso_dedup_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("longfilename.txt"), b"first").unwrap();
        std::fs::write(dir.join("longfilenam2.txt"), b"second").unwrap();
        let iso_path = dir.join("d.iso");
        create_iso(dir.to_str().unwrap(), iso_path.to_str().unwrap()).expect("create_iso dedup");

        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        extract_iso_all(iso_path.to_str().unwrap(), out.to_str().unwrap()).unwrap();
        // Both files must exist under distinct names with correct contents.
        let files = std::fs::read_dir(&out).unwrap().map(|e| e.unwrap().file_name().to_string_lossy().to_string()).collect::<Vec<_>>();
        assert_eq!(files.len(), 2, "both deduped files present: {files:?}");
        let mut contents: Vec<String> = Vec::new();
        for f in files {
            contents.push(String::from_utf8_lossy(&std::fs::read(out.join(&f)).unwrap()).to_string());
        }
        contents.sort();
        assert_eq!(contents, vec!["first".to_string(), "second".to_string()]);
        std::fs::remove_dir_all(&dir).ok();
    }

    /// Files ≥ 4 GiB are rejected up front (Level 1 size field is u32).
    #[test]
    fn create_iso_rejects_over_4gib() {
        // A sparse file > 4 GiB (occupies no real disk) must be rejected.
        let dir = std::env::temp_dir().join(format!("uu_iso_big_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let big = dir.join("big.bin");
        let f = std::fs::OpenOptions::new().create(true).write(true).open(&big).unwrap();
        f.set_len(u32::MAX as u64 + 1).unwrap();
        drop(f);
        let iso_path = dir.join("big.iso");
        assert!(create_iso(dir.to_str().unwrap(), iso_path.to_str().unwrap()).is_err(),
            ">4GiB file must be rejected");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// Extensionless files that collide after 8.3 truncation must get a legal
    /// `~N` name with the `;1` version marker preserved (not a mid-name `;`).
    #[test]
    fn create_iso_dedup_extensionless_collision() {
    let _g = progress_lock();
        let dir = std::env::temp_dir().join(format!("uu_iso_extless_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        // Both truncate to "ABCDEFGH;1" under Level 1 (8-char stem, no ext);
        // distinct under a case-insensitive FS (macOS default).
        std::fs::write(dir.join("abcdefgh"), b"one").unwrap();
        std::fs::write(dir.join("abcdefghi"), b"two").unwrap();
        let iso_path = dir.join("d.iso");
        create_iso(dir.to_str().unwrap(), iso_path.to_str().unwrap()).expect("create_iso extless dedup");

        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        extract_iso_all(iso_path.to_str().unwrap(), out.to_str().unwrap()).unwrap();
        let files = std::fs::read_dir(&out).unwrap().map(|e| e.unwrap().file_name().to_string_lossy().to_string()).collect::<Vec<_>>();
        assert_eq!(files.len(), 2, "both extless files present: {files:?}");
        let mut contents: Vec<String> = Vec::new();
        for f in files {
            // The deduped name may be ABCDEF~1 or similar — just check contents.
            contents.push(String::from_utf8_lossy(&std::fs::read(out.join(&f)).unwrap()).to_string());
        }
        contents.sort();
        assert_eq!(contents, vec!["one".to_string(), "two".to_string()]);
        std::fs::remove_dir_all(&dir).ok();
    }
}
