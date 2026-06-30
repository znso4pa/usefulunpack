use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, safe_join};
use std::collections::HashSet;
use std::fs;

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

fn extract_iso_all(input: &str, output: &str) -> Result<u32, String> {
    let mut file = std::fs::File::open(input).map_err(|e| format!("{e}"))?;
    let root = isomage::detect_and_parse_filesystem(&mut file, input).map_err(|e| format!("ISO: {e}"))?;
    let map = iso_map(&root);
    let mut fail = 0u32;
    for (path, node) in &map {
        if extract_iso_one(&mut file, node, output, path).is_err() {
            fail += 1;
        }
    }
    Ok(fail)
}

fn extract_iso_selected(input: &str, output: &str, selected: &str) -> Result<u32, String> {
    let sel_set: HashSet<&str> = selected.lines().filter(|l| !l.is_empty()).collect();
    if sel_set.is_empty() { return Ok(0); }
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
    let mut fail = 0u32;
    for p in &expanded {
        match map.iter().find(|(mp, _)| mp == p) {
            Some((_, node)) => { if extract_iso_one(&mut file, node, output, p).is_err() { fail += 1; } }
            None => fail += 1,
        }
    }
    Ok(fail)
}

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = fs::create_dir_all(&out);
    match extract_iso_all(&inp, &out) { Ok(0) => JNI_TRUE, Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("ISO: {f} failed")); JNI_FALSE }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("ISO: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoExtractSelected(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel_j: JString) -> jboolean {
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel_j);
    match extract_iso_selected(&inp, &out, &sel_str) { Ok(0) => JNI_TRUE, Ok(f) => { let _ = e.throw_new("java/io/IOException", format!("ISO: {f} failed")); JNI_FALSE }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("ISO: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_IsoCore_isoListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    match list_iso(&s(&mut e, &i)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("listEntries: {er}")); std::ptr::null_mut() } }
}
