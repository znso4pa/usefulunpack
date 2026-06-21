// ╔══════════════════════════════════════════════════════════════╗
// ║  UsefulUnpack — znso4pa — xp3-tool pattern extract           ║
// ╚══════════════════════════════════════════════════════════════╝

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, JNI_TRUE, JNI_FALSE};
use std::collections::{BTreeSet, HashSet};
use std::fs::{self, File};
use std::io::{BufReader, BufWriter};
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
    // Safety: we own the future and will not move it after pinning
    let fut = unsafe { Pin::new_unchecked(&mut fut) };
    match fut.poll(&mut cx) {
        Poll::Ready(v) => v,
        Poll::Pending => unreachable!(),
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

    for i in 0..archive.entries().len() {
        let name = &archive.entries()[i].name;
        let mut dest = Path::new(output).to_path_buf();
        for comp in name.split('\\') { if comp.is_empty() { continue; } dest.push(comp); }
        if let Some(p) = dest.parent() { let _ = fs::create_dir_all(p); }

        let out_file = match File::create(&dest) {
            Ok(f) => f, Err(_) => continue,
        };
        let mut out_stream = SyncIo(BufWriter::new(out_file));

        let mut xf = match oneshot_async(archive.by_index(i)).unwrap() {
            Ok(f) => f, Err(_) => continue,
        };
        let _ = oneshot_async(tokio::io::copy(&mut xf, &mut out_stream));
    }
    JNI_TRUE
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
    } else if inp.to_lowercase().ends_with(".pfs") {
        list_pfs(&inp)
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

    // Collect normalized paths
    let raw_names: Vec<&str> = archive.entries().iter().map(|e| e.name.as_str()).collect();
    let normalized: Vec<String> = raw_names.iter().map(|n| n.replace('\\', "/")).collect();
    let norm_refs: Vec<&str> = normalized.iter().map(|s| s.as_str()).collect();
    let dirs = derive_dirs(&norm_refs);

    // Build sorted entries for tree order
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

    // Build sorted entries for tree order
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

    for i in 0..archive.entries().len() {
        let raw_name = &archive.entries()[i].name;
        let norm_name = raw_name.replace('\\', "/");

        // Check if this entry or any parent directory is selected
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
            Ok(f) => f, Err(_) => continue,
        };
        let mut out_stream = SyncIo(BufWriter::new(out_file));

        let mut xf = match oneshot_async(archive.by_index(i)).unwrap() {
            Ok(f) => f, Err(_) => continue,
        };
        let _ = oneshot_async(tokio::io::copy(&mut xf, &mut out_stream));
    }
    JNI_TRUE
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

    // Collect all matching paths (need to own strings since archive is borrowed mutably later)
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

    for entry_path in &to_extract {
        let mut dest = Path::new(output).to_path_buf();
        if let Ok(rel) = entry_path.strip_prefix("/") {
            dest.push(rel);
        } else {
            // Strip any common prefix? Just use the path components directly
            for comp in entry_path.components().skip(1) {
                dest.push(comp);
            }
        }
        if dest.to_string_lossy().is_empty() { continue; }
        if let Some(p) = dest.parent() { let _ = fs::create_dir_all(p); }
        let _ = archive.extract_file(entry_path, &dest);
    }
    JNI_TRUE
}
