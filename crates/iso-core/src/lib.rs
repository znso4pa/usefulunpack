use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jstring, jlong};
use archive_common::{s, json_escape, safe_join, extract_result_json};
use archive_common::extract_progress;
use std::collections::HashSet;
use std::fs;

fn guarded<T: Send + 'static>(f: impl FnOnce() -> Result<T, String> + Send + 'static) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|panic| {
        let msg = panic.downcast_ref::<&str>().copied()
            .or_else(|| panic.downcast_ref::<String>().map(|s| s.as_str()))
            .unwrap_or("unknown panic");
        Err(format!("panic: {msg}"))
    })
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
    let dest = safe_join(output, rel_path)?;
    if let Some(p) = dest.parent() { fs::create_dir_all(p).map_err(|e| format!("{e}"))?; }
    let mut data = Vec::new();
    isomage::cat_node(file, node, &mut data).map_err(|e| format!("{e}"))?;
    fs::write(&dest, &data).map_err(|e| format!("{e}"))?;
    Ok(())
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
        if extract_iso_one(&mut file, node, output, path).is_err() {
            fail += 1;
        }
        extract_progress::add_bytes(node.size);
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
    let mut expanded = HashSet::new();
    for s in &sel_set {
        let key = s.trim_start_matches('/');
        expanded.insert(key.to_string());
        let prefix = format!("{key}/");
        for (p, _) in &map { if p.starts_with(&prefix) { expanded.insert(p.clone()); } }
    }
    extract_progress::reset(expanded.iter().filter(|p| map.iter().any(|(mp, n)| mp == *p && !n.is_directory)).map(|p| map.iter().find(|(mp, n)| mp == p && !n.is_directory).map(|(_, n)| n.size).unwrap_or(0)).sum());
    let mut fail = 0u32;
    for p in &expanded {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        match map.iter().find(|(mp, _)| mp == p) {
            Some((_, node)) => {
                if node.is_directory { continue; }
                extract_progress::set_name(p);
                if extract_iso_one(&mut file, node, output, p).is_err() { fail += 1; }
                extract_progress::add_bytes(node.size);
            }
            None => fail += 1,
        }
    }
    let total = expanded.len() as u32;
    Ok((total, fail))
}

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoExtractProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = fs::create_dir_all(&out);
    match guarded(move || extract_iso_all(&inp, &out)) {
        Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoExtractSelected(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel_j: JString) -> jstring {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel_j);
    match guarded(move || extract_iso_selected(&inp, &out, &sel_str)) {
        Ok((total, error)) => { let json = extract_result_json(total, total - error, error); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }
        Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() }
    }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    match list_iso(&s(&mut e, &i)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}
