use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, jlong, jint, JNI_TRUE, JNI_FALSE};
use archive_common::{s, json_escape, safe_join, extract_result_json, ProgressWriter, BoundedWriter};
use archive_common::extract_progress;
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::io::Write;
use std::path::Path;
use std::sync::atomic::{AtomicU32, Ordering};

#[cfg(test)]
use std::sync::Mutex;
#[cfg(test)]
static TEST_LOCK: Mutex<()> = Mutex::new(());

/// Which algorithm a family's stored checksum uses.
///
/// These are genuinely different, not one truncated the other:
///   * RAR 1.5 – 5.x: full CRC-32 of the **unpacked** data (`rars::crc32`)
///   * RAR 1.3 / 1.4: a 16-bit `sum(bytes).rotate_left(1)` (`Rar13Checksum`)
///
/// The first implementation of this assumed the 1.3 case was `crc32 & 0xffff`
/// and therefore rejected **every legitimate RAR 1.3/1.4 archive** while
/// claiming to verify them. The fixture caught it via a "pristine archive must
/// still extract" precondition — worth keeping that check for this reason.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum CrcAlgo {
    Crc32,
    Rar13,
}

/// Running checksum for one member: the digest is family-specific.
enum RunningCrc {
    Crc32(rars::crc32::Crc32),
    Rar13(rars::rar13::Rar13Checksum),
}

impl RunningCrc {
    fn new(algo: CrcAlgo) -> Self {
        match algo {
            CrcAlgo::Crc32 => RunningCrc::Crc32(rars::crc32::Crc32::new()),
            CrcAlgo::Rar13 => RunningCrc::Rar13(rars::rar13::Rar13Checksum::new()),
        }
    }
    fn update(&mut self, buf: &[u8]) {
        match self {
            RunningCrc::Crc32(c) => c.update(buf),
            RunningCrc::Rar13(c) => c.update(buf),
        }
    }
    /// Finalised digest, widened to u32 for a uniform comparison.
    fn finish(self) -> u32 {
        match self {
            RunningCrc::Crc32(c) => c.finish(),
            RunningCrc::Rar13(c) => c.finish() as u32,
        }
    }
    /// Same digest without consuming — `Drop` only has `&mut self`.
    fn peek(&self) -> u32 {
        match self {
            RunningCrc::Crc32(c) => c.clone().finish(),
            RunningCrc::Rar13(c) => c.clone().finish() as u32,
        }
    }
    /// One-shot over a slice that is already in memory.
    fn of(algo: CrcAlgo, data: &[u8]) -> u32 {
        let mut c = RunningCrc::new(algo);
        c.update(data);
        c.finish()
    }
}

/// A member's stored checksum: which algorithm, and the expected value.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct CrcSpec {
    algo: CrcAlgo,
    expected: u32,
}

impl CrcSpec {
    fn new(algo: CrcAlgo, expected: u32) -> Self {
        Self { algo, expected }
    }
    fn running(&self) -> RunningCrc {
        RunningCrc::new(self.algo)
    }
    fn matches(&self, digest: u32) -> bool {
        match self.algo {
            CrcAlgo::Crc32 => digest == self.expected,
            CrcAlgo::Rar13 => (digest & 0xffff) as u16 == (self.expected & 0xffff) as u16,
        }
    }
    /// The stored value as reported in an error. For the 16-bit algorithm this
    /// is the 16-bit field, not a truncated CRC-32.
    fn expected_u32(&self) -> u32 {
        self.expected
    }
    fn of_slice(&self, data: &[u8]) -> u32 {
        RunningCrc::of(self.algo, data)
    }
}

/// Normalised per-member checksum from any RAR family, or `None` when the
/// member carries none — or when verifying it here would be **wrong**.
///
/// A member split across volumes is the case that matters. For RAR 1.3/1.4 the
/// header's 16-bit checksum covers only the FIRST volume's slice of the
/// unpacked data, not the whole member: a 4-volume 4096-byte fixture stores
/// `0x8cb4` = the checksum of the first 1024 bytes, while the full member checks
/// to `0xbf5f`. Verifying the reassembled output against that value rejects
/// every legitimate split archive — verified empirically, not assumed. The
/// vendored reader already validates each volume's slice as it goes, so
/// deferring to it is both correct and cheaper.
fn member_crc(member: &rars::ArchiveMember) -> Option<CrcSpec> {
    if member.meta.is_split_before || member.meta.is_split_after {
        return None;
    }
    use rars::ArchiveMemberDetail as D;
    match &member.detail {
        D::Rar50Plus { crc32, .. } => crc32.map(|v| CrcSpec::new(CrcAlgo::Crc32, v)),
        D::Rar15To40 { crc32, .. } => Some(CrcSpec::new(CrcAlgo::Crc32, *crc32)),
        D::Rar13 { file_checksum, .. } => {
            Some(CrcSpec::new(CrcAlgo::Rar13, *file_checksum as u32))
        }
        _ => None,
    }
}

/// A `Write` adapter that CRCs everything passing through and, on drop,
/// compares the digest against [CrcSpec].
///
/// Drop is the only completion hook the sequential path gives us:
/// `extract_to_with_options` hands rars a `Box<dyn Write>` per entry and there
/// is no "entry finished" callback, so verification rides on the drop. On a
/// mismatch the half-written file is deleted and the failure counter bumped —
/// a corrupt member must not survive as a plausible-looking output.
struct CrcGuardWriter {
    inner: Box<dyn Write>,
    crc: RunningCrc,
    want: CrcSpec,
    dest: String,
    /// The CALLER's counter, shared. Two constraints force the Arc:
    ///   * a fresh `AtomicU32` here would be a silent no-op — the mismatch would
    ///     delete the file but never surface as a failed entry, and the
    ///     archive-level result would still read `Ok((total, 0))`;
    ///   * rars' writer factory signature is `Box<dyn Write + 'static>`, so the
    ///     guard may not borrow the caller's stack counter.
    fail: Arc<AtomicU32>,
}

impl Write for CrcGuardWriter {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        let n = self.inner.write(buf)?;
        self.crc.update(&buf[..n]);
        Ok(n)
    }
    fn flush(&mut self) -> std::io::Result<()> {
        self.inner.flush()
    }
}

impl Drop for CrcGuardWriter {
    fn drop(&mut self) {
        // `Crc32::finish` applies the final inversion; the raw `value` is
        // still pre-final.
        let digest = self.crc.peek();
        if !self.want.matches(digest) {
            let _ = std::fs::remove_file(&self.dest);
            self.fail.fetch_add(1, Ordering::SeqCst);
        }
    }
}

fn list_rar_inner(input: &str) -> Result<String, String> {
    list_rar_inner_with_pw(input, "")
}

/// Lists a single RAR. A header-encrypted (-hp) archive cannot be parsed
/// without the password, so [password] is passed through to the reader.
fn list_rar_inner_with_pw(input: &str, password: &str) -> Result<String, String> {
    let pw = if password.is_empty() { None } else { Some(password.as_bytes()) };
    let archive = rars::ArchiveReader::read_path_with_options(Path::new(input), rar_opts(pw))
        .map_err(|e| format!("rar: {e}"))?;
    let mut all: Vec<(String, u64, bool, bool)> = Vec::new();
    for member in archive.members() {
        let name = member.meta.name_lossy().replace('\\', "/").trim_matches('/').to_string();
        if name.is_empty() { continue; }
        let is_dir = name.ends_with('/');
        let is_enc = member.meta.is_encrypted;
        all.push((name.clone(), member.meta.unpacked_size, is_dir, is_enc));
        let mut path = String::new();
        for part in name.split('/') {
            if part.is_empty() { continue; }
            path = if path.is_empty() { part.to_string() } else { format!("{path}/{part}") };
            if !all.iter().any(|(p,_,_,_)| p == &path) { all.push((path.clone(), 0u64, true, false)); }
        }
    }
    all.sort_by(|a,b| a.0.cmp(&b.0));
    all.dedup_by(|a,b| a.0 == b.0);
    let items: Vec<String> = all.iter().map(|(n,s,d,e)|
        format!(r#"{{"n":"{}","s":{},"d":{},"e":{}}}"#, json_escape(n), *s, *d, *e)
    ).collect();
    Ok(format!("[{}]", items.join(",")))
}

fn rar_writer<'a>(
    sel_set: &'a Option<HashSet<String>>,
    sizes: &'a HashMap<String, u64>,
    crcs: &'a HashMap<String, CrcSpec>,
    stored: &'a HashSet<String>,
    out_base: &'a str,
    fail: Arc<AtomicU32>,
) -> impl FnMut(&rars::ExtractedEntryMeta) -> Result<Box<dyn Write>, rars::Error> + 'a {
    move |meta| {
        if extract_progress::cancelled() { return Err(rars::Error::Cancelled); }
        let name = meta.name_lossy().replace('\\', "/").trim_matches('/').to_string();
        if meta.is_directory || name.is_empty() || name.ends_with('/') {
            if let Ok(dest) = safe_join(out_base, &name) {
                std::fs::create_dir_all(&dest).ok();
            }
            return Ok(Box::new(std::io::sink()) as Box<dyn Write>);
        }
        if let Some(ref sel) = sel_set {
            if !sel.contains(&name) && !sel.iter().any(|s| name.starts_with(&format!("{s}/"))) {
                return Ok(Box::new(std::io::sink()) as Box<dyn Write>);
            }
        }
        extract_progress::set_name(&name);
        extract_progress::set_file(sizes.get(&name).copied().unwrap_or(0));
        // No fallback join: a rejected path (../, absolute, drive letter) must
        // never be written — count it as failed and sink the entry, exactly
        // like zip/nsa/7z/pfs.
        let dest = match safe_join(out_base, &name) {
            Ok(d) => d,
            Err(_) => { fail.fetch_add(1, Ordering::SeqCst); return Ok(Box::new(std::io::sink()) as Box<dyn Write>); }
        };
        if let Some(p) = Path::new(&dest).parent() { std::fs::create_dir_all(p).ok(); }
        let out_file = match std::fs::File::create(&dest) {
            Ok(f) => f,
            Err(_) => { fail.fetch_add(1, Ordering::SeqCst); return Ok(Box::new(std::io::sink()) as Box<dyn Write>); }
        };
        let size = sizes.get(&name).copied().unwrap_or(0);
        // Buffered members (compressed and ≤ the decode limit) decode whole
        // into RAM before writing; the progress watcher feeds the CURRENT-FILE
        // bar from rars decode_progress during that window, so the write must
        // count only the OVERALL bar (exact, no double-count of the file bar).
        // Stored members always stream (write_stored_to) and large members
        // stream — ProgressWriter counts both bars from the write.
        let buffered = !stored.contains(&name) && size <= RAR50_BUFFERED_LIMIT;
        // Defense-in-depth: cap each member's output at its declared size so a
        // vendored-rars decode regression can't over-write past unpack_size.
        let capped = |w: Box<dyn Write>| -> Box<dyn Write> {
            Box::new(BoundedWriter::new(w, size)) as Box<dyn Write>
        };
        // Verify the member's stored checksum. Without this a corrupt member
        // (files4testing's `corrupt-rawfile1.m5.rar`, where `unrar t` reports
        // "checksum error") is reported as a SUCCESSFUL extraction and leaves a
        // plausible-looking but wrong file on disk. `CrcGuardWriter` checks on
        // drop, which is the only completion hook this API offers.
        let base: Box<dyn Write> = if buffered {
            Box::new(ProgressWriter::extract_top(out_file))
        } else {
            Box::new(ProgressWriter::extract(std::io::BufWriter::with_capacity(256 * 1024, out_file)))
        };
        let base = capped(base);
        let want = match crcs.get(&name) {
            Some(c) => *c,
            // No stored checksum for this member — nothing to verify against.
            None => return Ok(base),
        };
        Ok(Box::new(CrcGuardWriter {
            inner: base,
            crc: want.running(),
            want,
            // `dest` is a PathBuf here (safe_join returns one); the guard only
            // needs it to unlink the file on a mismatch.
            dest: dest.to_string_lossy().to_string(),
            fail: fail.clone(),
        }))
    }
}

/// Members at or below this size use the whole-member buffered decode path
/// (decode into RAM, then write once); larger members stream (decode+write
/// interleaved) so a 300MB+ member shows byte-level progress. Must match the
/// value passed into [rar_opts] — the extraction path needs the SAME options
/// object (see `extract_rar_inner`), otherwise rars falls back to its 512MB
/// default and mid-size members get buffered, freezing the top progress bar.
const RAR50_BUFFERED_LIMIT: u64 = 64 * 1024 * 1024;

fn rar_opts(pw: Option<&[u8]>) -> rars::ArchiveReadOptions<'_> {
    let mut options = rars::ArchiveReadOptions::with_optional_password(pw);
    options.rar50_buffered_decode_limit = Some(RAR50_BUFFERED_LIMIT);
    options
}

/// Writes one selected member through the same guards as `rar_writer`
/// (path safety, fail counting, cancel, per-file progress). Buffered members
/// count only the OVERALL bar on write; streamed ones count both bars.
fn fast_write_member<F>(name: &str, size: u64, out_base: &str, fail: &Arc<AtomicU32>, want: Option<CrcSpec>, write: F) -> rars::Result<()>
where
    F: FnOnce(&mut Box<dyn Write>) -> rars::Result<()>,
{
    if extract_progress::cancelled() {
        return Err(rars::Error::Cancelled);
    }
    extract_progress::set_name(name);
    extract_progress::set_file(size);
    let dest = match safe_join(out_base, name) {
        Ok(d) => d,
        Err(_) => {
            fail.fetch_add(1, Ordering::SeqCst);
            return Ok(());
        }
    };
    if let Some(p) = Path::new(&dest).parent() {
        std::fs::create_dir_all(p).ok();
    }
    let out_file = match std::fs::File::create(&dest) {
        Ok(f) => f,
        Err(_) => {
            fail.fetch_add(1, Ordering::SeqCst);
            return Ok(());
        }
    };
    let buffered = size <= RAR50_BUFFERED_LIMIT;
    let mut out: Box<dyn Write> = if buffered {
        Box::new(ProgressWriter::extract_top(out_file))
    } else {
        Box::new(ProgressWriter::extract(std::io::BufWriter::with_capacity(256 * 1024, out_file)))
    };
    // The closure wants `Box<dyn Write + 'static>`, so a CRC wrapper may not
    // borrow a local accumulator. Reuse [CrcGuardWriter]: it owns its CRC state
    // and verifies on drop, sharing the caller's counter.
    match want {
        Some(want) => {
            let guarded: Box<dyn Write> = Box::new(CrcGuardWriter {
                inner: out,
                crc: want.running(),
                want,
                dest: dest.to_string_lossy().to_string(),
                // The CALLER's counter, not a fresh one — a private counter
                // would delete the corrupt file but report the entry as
                // successful, i.e. a silent no-op.
                fail: fail.clone(),
            });
            write(&mut { guarded })
        }
        None => write(&mut out),
    }
}


/// True while the parallel full-extract path is running. The progress watcher
/// (which mirrors rars' single-threaded `decode_progress` into the per-file bar)
/// must NOT run then — parallel decodes share that global counter and would
/// clobber it. The parallel path drives both bars from the writes instead.
static PARALLEL_DECODE_ACTIVE: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

/// Thread count override (0 = auto). Set via JNI (settings) or the
/// `UU_PARALLEL_THREADS` env var (host tests). Clamped to 1..=8.
static PARALLEL_THREADS: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);

/// Sets the parallel-decode thread count; 0 restores the automatic choice.
#[doc(hidden)]
pub fn set_parallel_threads(n: u32) {
    PARALLEL_THREADS.store(n, Ordering::Relaxed);
}

fn parallel_thread_count() -> usize {
    if let Ok(v) = std::env::var("UU_PARALLEL_THREADS") {
        if let Ok(n) = v.parse::<u32>() {
            if n > 0 { return n.clamp(1, 8) as usize; }
        }
    }
    let n = PARALLEL_THREADS.load(Ordering::Relaxed);
    if n > 0 {
        n.clamp(1, 8) as usize
    } else {
        std::thread::available_parallelism().map(|n| n.get()).unwrap_or(2).min(4)
    }
}

/// Decodes [items] concurrently (one thread per item, bounded by the caller)
/// into byte buffers, preserving input order. Each item is decoded into its own
/// Vec — used to parallelise buffered RAR5 members whose data ranges are
/// independent in a non-solid archive.
fn decode_batch<T, F>(items: &[T], decode: F) -> Vec<rars::Result<Vec<u8>>>
where
    T: Sync,
    F: Fn(&T) -> rars::Result<Vec<u8>> + Sync + Send,
{
    let mut out: Vec<rars::Result<Vec<u8>>> = items.iter().map(|_| Ok(Vec::new())).collect();
    std::thread::scope(|s| {
        let decode_ref = &decode;
        let handles: Vec<_> = items
            .iter()
            .enumerate()
            .map(|(k, item)| s.spawn(move || (k, decode_ref(item))))
            .collect();
        for (idx, h) in handles.into_iter().enumerate() {
            match h.join() {
                Ok((k, Ok(v))) => out[k] = Ok(v),
                Ok((k, Err(e))) => out[k] = Err(e),
                Err(_) => out[idx] = Err(rars::Error::InvalidHeader("parallel decode panicked")),
            }
        }
    });
    out
}

/// Writes an already-decoded buffered member: the per-file bar is set manually
/// (the decode watcher is disabled while parallel), and the write counts only
/// the OVERALL bar — matching the sequential buffered path's accounting.
fn write_buffered_member(name: &str, size: u64, data: &[u8], out_base: &str, fail: &Arc<AtomicU32>, want: Option<CrcSpec>) -> rars::Result<()> {
    if extract_progress::cancelled() {
        return Err(rars::Error::Cancelled);
    }
    extract_progress::set_name(name);
    extract_progress::set_file(size);
    let dest = match safe_join(out_base, name) {
        Ok(d) => d,
        Err(_) => {
            fail.fetch_add(1, Ordering::SeqCst);
            return Ok(());
        }
    };
    if let Some(p) = Path::new(&dest).parent() {
        std::fs::create_dir_all(p).ok();
    }
    let out_file = match std::fs::File::create(&dest) {
        Ok(f) => f,
        Err(_) => {
            fail.fetch_add(1, Ordering::SeqCst);
            return Ok(());
        }
    };
    let mut out = ProgressWriter::extract_top(out_file);
    if out.write_all(data).is_err() || out.flush().is_err() {
        let _ = std::fs::remove_file(&dest);
        fail.fetch_add(1, Ordering::SeqCst);
        return Ok(());
    }
    // The decoded member is already in RAM, so the checksum is one call — no
    // wrapper needed. Verify AFTER the write succeeded: a short/failed write is
    // a different failure and must not be reported as corruption.
    if let Some(w) = want {
        let digest = w.of_slice(data);
        if !w.matches(digest) {
            let _ = std::fs::remove_file(&dest);
            fail.fetch_add(1, Ordering::SeqCst);
            return Err(rars::Error::Crc32Mismatch {
                expected: w.expected_u32(),
                actual: digest,
            });
        }
    }
    extract_progress::set_file_bytes(size);
    Ok(())
}

/// Full-extraction parallel fast path for NON-solid RAR5 archives. Buffered
/// members (≤ 64 MiB) decode concurrently in bounded batches (thread count and
/// decode RAM both capped); streaming members (> 64 MiB) stream sequentially.
/// Output files are independent, so write order doesn't affect correctness.
/// Returns Ok(None) when the archive can't be fast-pathed (solid / split /
/// redirection / RAR4 / too few members) and the caller falls back to the
/// sequential extractor.
fn extract_all_parallel(
    archive: &rars::Archive,
    pw: Option<&[u8]>,
    crcs: &HashMap<String, CrcSpec>,
    out_base: &str,
    fail: &Arc<AtomicU32>,
) -> rars::Result<Option<()>> {
    let rars::Archive::Rar50Plus(a) = archive else {
        return Ok(None);
    };
    if a.main.is_solid() {
        return Ok(None);
    }
    let mut buffered: Vec<&rars::rar50::FileHeader> = Vec::new();
    let mut streaming: Vec<&rars::rar50::FileHeader> = Vec::new();
    for f in a.files() {
        if f.is_split_before() || f.is_split_after() || f.redirection.is_some() {
            return Ok(None);
        }
        let name = f.name_lossy().replace('\\', "/").trim_matches('/').to_string();
        if f.is_directory() || name.is_empty() {
            continue;
        }
        if f.unpacked_size > RAR50_BUFFERED_LIMIT {
            streaming.push(f);
        } else {
            buffered.push(f);
        }
    }
    if buffered.len() < 2 {
        return Ok(None); // nothing to parallelise
    }

    PARALLEL_DECODE_ACTIVE.store(true, Ordering::Relaxed);
    let r = (|| -> rars::Result<()> {
        // Decode buffers for one batch cap at ~192 MiB; threads cap at 4.
        let n_threads = parallel_thread_count();
        const BATCH_MEM_BUDGET: u64 = 192 * 1024 * 1024;
        let mut i = 0usize;
        while i < buffered.len() {
            let mut end = i;
            let mut sum = 0u64;
            while end < buffered.len() && end - i < n_threads {
                if sum + buffered[end].unpacked_size > BATCH_MEM_BUDGET && end > i {
                    break;
                }
                sum += buffered[end].unpacked_size;
                end += 1;
            }
            let batch = &buffered[i..end];
            let decoded = decode_batch(batch, |f| {
                let mut v = Vec::new();
                f.write_to_with_options(a, rar_opts(pw), &mut v)?;
                Ok(v)
            });
            for (k, res) in decoded.into_iter().enumerate() {
                if extract_progress::cancelled() {
                    return Err(rars::Error::Cancelled);
                }
                let data = res?;
                let name = batch[k].name_lossy().replace('\\', "/").trim_matches('/').to_string();
                let want = crcs.get(&name).copied();
                write_buffered_member(&name, batch[k].unpacked_size, &data, out_base, fail, want)?;
            }
            i = end;
        }
        // Streaming members sequentially (they already show byte-level progress).
        for f in &streaming {
            if extract_progress::cancelled() {
                return Err(rars::Error::Cancelled);
            }
            let name = f.name_lossy().replace('\\', "/").trim_matches('/').to_string();
            let want = crcs.get(&name).copied();
            fast_write_member(&name, f.unpacked_size, out_base, fail, want, |out| {
                f.write_to_with_options(a, rar_opts(pw), out)
            })?;
        }
        Ok(())
    })();
    PARALLEL_DECODE_ACTIVE.store(false, Ordering::Relaxed);
    r.map(Some)
}

/// Selected-extraction fast path for NON-solid archives. RAR has no central
/// directory: the sequential extractor decodes every member in order (output of
/// unselected members is discarded), so previewing a late txt costs decoding
/// all preceding members. Non-solid members decode independently from their own
/// data range, so this seeks straight to each selected member and skips the
/// rest with zero decode — like zip's random access. Returns Ok(false) when the
/// archive can't be fast-pathed (solid / rar13 / split members / redirections /
/// volumes), and the caller falls back to the sequential extractor.
fn extract_selected_fast(
    archive: &rars::Archive,
    pw: Option<&[u8]>,
    sel_set: &HashSet<String>,
    sizes: &HashMap<String, u64>,
    crcs: &HashMap<String, CrcSpec>,
    out_base: &str,
    fail: &Arc<AtomicU32>,
) -> rars::Result<bool> {
    fn selected(name: &str, sel: &HashSet<String>) -> bool {
        sel.contains(name) || sel.iter().any(|s| name.starts_with(&format!("{s}/")))
    }
    match archive {
        rars::Archive::Rar50Plus(a) if !a.main.is_solid() => {
            for f in a.files() {
                if f.is_split_before() || f.is_split_after() || f.redirection.is_some() {
                    return Ok(false);
                }
            }
            for f in a.files() {
                let name = f.name_lossy().replace('\\', "/").trim_matches('/').to_string();
                if f.is_directory() || name.is_empty() || name.ends_with('/') { continue; }
                if !selected(&name, sel_set) { continue; }
                let size = sizes.get(&name).copied().unwrap_or(0);
                let want = crcs.get(&name).copied();
                fast_write_member(&name, size, out_base, fail, want, |out| {
                    f.write_to_with_options(a, rar_opts(pw), out)
                })?;
            }
            Ok(true)
        }
        rars::Archive::Rar15To40(a) if !a.main.is_solid() => {
            for f in a.files() {
                if f.is_split_before() || f.is_split_after() { return Ok(false); }
            }
            for f in a.files() {
                let name = f.name_lossy().replace('\\', "/").trim_matches('/').to_string();
                if f.is_directory() || name.is_empty() || name.ends_with('/') { continue; }
                if !selected(&name, sel_set) { continue; }
                let size = sizes.get(&name).copied().unwrap_or(0);
                let want = crcs.get(&name).copied();
                fast_write_member(&name, size, out_base, fail, want, |out| f.write_to(a, pw, out))?;
            }
            Ok(true)
        }
        _ => Ok(false),
    }
}

fn read_volumes(paths: &[&str], pw: Option<&[u8]>) -> Result<Vec<rars::Archive>, String> {
    let mut archives = Vec::with_capacity(paths.len());
    for p in paths {
        archives.push(rars::ArchiveReader::read_path_with_options(Path::new(p), rar_opts(pw)).map_err(|e| format!("rar: {e}"))?);
    }
    Ok(archives)
}

fn extract_rar_inner(input: &str, output: &str, selected: Option<&HashSet<String>>, password: &str) -> Result<(u32, u32), String> {
    let pw: Option<&[u8]> = if password.is_empty() { None } else { Some(password.as_bytes()) };
    let archive = rars::ArchiveReader::read_path_with_options(Path::new(input), rar_opts(pw)).map_err(|e| format!("rar: {e}"))?;
    let sel_set: Option<HashSet<String>> = selected.map(|s| s.iter().map(|x| x.to_string()).collect());
    let out_base = output.to_string();

    let mut total = 0u32;
    let mut prog_total = 0u64;
    let mut sizes: HashMap<String, u64> = HashMap::new();
    // Per-member stored checksum, collected in the same pass as `sizes` so the
    // write paths can verify what they produced. Keyed by the same normalised
    // name the writer factory looks up.
    let mut crcs: HashMap<String, CrcSpec> = HashMap::new();
    let mut stored: HashSet<String> = HashSet::new();
    for member in archive.members() {
        if extract_progress::cancelled() { return Err("cancelled".to_string()); }
        let name = member.meta.name_lossy().replace('\\', "/").trim_matches('/').to_string();
        if member.meta.is_directory || name.is_empty() || name.ends_with('/') { continue; }
        if member.meta.is_stored { stored.insert(name.clone()); }
        let matches = match &sel_set {
            None => true,
            Some(sel) => sel.contains(&name) || sel.iter().any(|s| name.starts_with(&format!("{s}/"))),
        };
        if matches {
            total += 1;
            prog_total += member.meta.unpacked_size;
            if let Some(c) = member_crc(&member) { crcs.insert(name.clone(), c); }
            sizes.insert(name, member.meta.unpacked_size);
        }
    }
    extract_progress::reset(prog_total);
    // Arc so the CRC guard — which the rars writer factory hands out as a
    // `Box<dyn Write + 'static>` and therefore may not borrow from — can still
    // bump the SAME counter. A separate counter would make the mismatch a
    // silent no-op and the archive result would still read Ok((total, 0)).
    let fail = Arc::new(AtomicU32::new(0));
    let result = run_with_cancel_monitor(|| {
        // NOTE: must pass rar_opts(pw) here — `extract_to` builds its own
        // default options (rars' 512MB buffered limit) and would silently drop
        // RAR50_BUFFERED_LIMIT, buffering 100-500MB members and freezing the
        // top progress bar for the whole member.
        if let Some(sel) = &sel_set {
            if extract_selected_fast(&archive, pw, sel, &sizes, &crcs, &out_base, &fail)? {
                return Ok(());
            }
        }
        if let Some(()) = extract_all_parallel(&archive, pw, &crcs, &out_base, &fail)? {
            return Ok(());
        }
        archive.extract_to_with_options(rar_opts(pw), rar_writer(&sel_set, &sizes, &crcs, &stored, &out_base, fail.clone()))
    });
    result.map_err(|e| format!("rar: {e}"))?;
    Ok((total, fail.load(Ordering::SeqCst)))
}

/// Runs [f] while a background thread mirrors the rar-core cancel flag into the
/// vendored rars whole-member decode flag, so a cancel lands promptly inside a
/// large buffered (≤ limit) member instead of waiting for it to finish.
fn run_with_cancel_monitor<T>(f: impl FnOnce() -> rars::Result<T>) -> rars::Result<T> {
    rars::codec::rar50::set_decode_cancel(false);
    let done = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let done2 = done.clone();
    let watcher = std::thread::spawn(move || {
        // Bridge two things from rar-core's cancel/progress state into the
        // vendored rars whole-member decode path:
        //  1. cancel flag → rars DECODE_CANCEL (prompt abort of a buffered member)
        //  2. rars DECODE_PROGRESS → extract_progress::set_file_bytes (the
        //     CURRENT-FILE bar moves while a ≤limit member decodes into RAM).
        //     Only mirrored while a buffered decode is actually running — for
        //     large (>limit) members that stream, decode_progress holds a stale
        //     value and must NOT clobber the write-driven file bar. The OVERALL
        //     bar is fed only by ProgressWriter on write (exact; a decode-poll
        //     would under-count members decoded inside one poll window), so the
        //     two never double-count or lose bytes.
        loop {
            if done2.load(std::sync::atomic::Ordering::SeqCst) {
                break;
            }
            if extract_progress::cancelled() {
                rars::codec::rar50::set_decode_cancel(true);
            }
            if !PARALLEL_DECODE_ACTIVE.load(Ordering::Relaxed)
                && rars::codec::rar50::decode_buffered_active()
            {
                extract_progress::set_file_bytes(rars::codec::rar50::decode_progress());
            }
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
        rars::codec::rar50::set_decode_cancel(true);
    });
    // RAII guard guarantees the watcher thread always terminates and the
    // vendored cancel flag is reset even if `f()` panics — otherwise the
    // watcher would leak and keep driving the progress/cancel state of every
    // later RAR operation.
    struct Guard {
        done: std::sync::Arc<std::sync::atomic::AtomicBool>,
        watcher: Option<std::thread::JoinHandle<()>>,
    }
    impl Drop for Guard {
        fn drop(&mut self) {
            self.done.store(true, std::sync::atomic::Ordering::SeqCst);
            if let Some(h) = self.watcher.take() {
                let _ = h.join();
            }
            rars::codec::rar50::set_decode_cancel(false);
        }
    }
    let guard = Guard { done: done.clone(), watcher: Some(watcher) };
    let result = f();
    drop(guard);
    result
}

fn extract_rar_volumes_inner(paths: &[&str], output: &str, selected: Option<&HashSet<String>>, password: &str) -> Result<(u32, u32), String> {
    let pw: Option<&[u8]> = if password.is_empty() { None } else { Some(password.as_bytes()) };
    let sel_set: Option<HashSet<String>> = selected.map(|s| s.iter().map(|x| x.to_string()).collect());
    let out_base = output.to_string();
    let archives = read_volumes(paths, pw)?;

    let mut total = 0u32;
    let mut prog_total = 0u64;
    let mut seen: HashSet<String> = HashSet::new();
    let mut sizes: HashMap<String, u64> = HashMap::new();
    // Per-member stored checksum, collected in the same pass as `sizes` so the
    // write paths can verify what they produced. Keyed by the same normalised
    // name the writer factory looks up.
    let mut crcs: HashMap<String, CrcSpec> = HashMap::new();
    let mut stored: HashSet<String> = HashSet::new();
    for archive in &archives {
        for member in archive.members() {
            if extract_progress::cancelled() { return Err("cancelled".to_string()); }
            let name = member.meta.name_lossy().replace('\\', "/").trim_matches('/').to_string();
            if member.meta.is_directory || name.is_empty() || name.ends_with('/') { continue; }
            if !seen.insert(name.clone()) { continue; }
            if member.meta.is_stored { stored.insert(name.clone()); }
            let matches = match &sel_set {
                None => true,
                Some(sel) => sel.contains(&name) || sel.iter().any(|s| name.starts_with(&format!("{s}/"))),
            };
            if matches {
                total += 1;
                prog_total += member.meta.unpacked_size;
                if let Some(c) = member_crc(&member) { crcs.insert(name.clone(), c); }
            sizes.insert(name, member.meta.unpacked_size);
            }
        }
    }
    extract_progress::reset(prog_total);
    // Arc so the CRC guard — which the rars writer factory hands out as a
    // `Box<dyn Write + 'static>` and therefore may not borrow from — can still
    // bump the SAME counter. A separate counter would make the mismatch a
    // silent no-op and the archive result would still read Ok((total, 0)).
    let fail = Arc::new(AtomicU32::new(0));
    let result = run_with_cancel_monitor(|| {
        rars::extract_volumes_to_with_options(&archives, rar_opts(pw), rar_writer(&sel_set, &sizes, &crcs, &stored, &out_base, fail.clone()))
    });
    result.map_err(|e| format!("rar: {e}"))?;
    Ok((total, fail.load(Ordering::SeqCst)))
}

fn rar_needs_password_inner(input: &str) -> Result<bool, String> {
    match rars::ArchiveReader::read_path(Path::new(input)) {        Ok(archive) => Ok(archive.members().any(|m| m.meta.is_encrypted)),
        // Header-encrypted (-hp) archives cannot be parsed without a password —
        // the read failure IS the "needs password" signal.
        Err(_) => Ok(true),
    }
}

fn rar_volumes_needs_password_inner(paths: &[&str]) -> Result<bool, String> {
    match read_volumes(paths, None) {
        Ok(archives) => Ok(archives.iter().any(|a| a.members().any(|m| m.meta.is_encrypted))),
        Err(_) => Ok(true),
    }
}

fn list_rar_volumes_inner(paths: &[&str]) -> Result<String, String> {
    list_rar_volumes_inner_with_pw(paths, "")
}

/// Lists a multi-volume RAR. Header-encrypted (-hp) sets need the password to
/// parse the header of each volume.
fn list_rar_volumes_inner_with_pw(paths: &[&str], password: &str) -> Result<String, String> {
    let pw = if password.is_empty() { None } else { Some(password.as_bytes()) };
    let archives = read_volumes(paths, pw)?;
    let mut all: Vec<(String, u64, bool, bool)> = Vec::new();
    let mut seen: HashSet<String> = HashSet::new();
    for archive in &archives {
        for member in archive.members() {
            let name = member.meta.name_lossy().replace('\\', "/").trim_matches('/').to_string();
            if name.is_empty() { continue; }
            let is_dir = name.ends_with('/');
            let is_enc = member.meta.is_encrypted;
            if !seen.insert(name.clone()) { continue; }
            all.push((name.clone(), member.meta.unpacked_size, is_dir, is_enc));
            let mut path = String::new();
            for part in name.split('/') {
                if part.is_empty() { continue; }
                path = if path.is_empty() { part.to_string() } else { format!("{path}/{part}") };
                if !all.iter().any(|(p,_,_,_)| p == &path) { all.push((path.clone(), 0u64, true, false)); }
            }
        }
    }
    all.sort_by(|a,b| a.0.cmp(&b.0));
    all.dedup_by(|a,b| a.0 == b.0);
    let items: Vec<String> = all.iter().map(|(n,s,d,e)|
        format!(r#"{{"n":"{}","s":{},"d":{},"e":{}}}"#, json_escape(n), *s, *d, *e)
    ).collect();
    Ok(format!("[{}]", items.join(",")))
}

fn guarded<T: Send + 'static>(f: impl FnOnce() -> Result<T, String> + Send + 'static) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|panic| {
        let msg = panic.downcast_ref::<&str>().copied()
            .or_else(|| panic.downcast_ref::<String>().map(|s| s.as_str()))
            .unwrap_or("unknown panic");
        Err(format!("panic: {msg}"))
    })
}

/// Host-side (non-JNI) extraction entry point for examples/tests/benchmarks.
/// Delegates to the same path the app uses. `selected` is an optional list of
/// entry names to extract (all when empty).
#[doc(hidden)]
pub fn extract_rar_host(input: &str, output: &str, selected: &[String], password: &str) -> Result<(u32, u32), String> {
    let sel: Option<HashSet<String>> = if selected.is_empty() { None } else { Some(selected.iter().cloned().collect()) };
    extract_rar_inner(input, output, sel.as_ref(), password)
}

/// Host-side list entry point (mirrors the app's list JSON).
#[doc(hidden)]
pub fn list_rar_host(input: &str, password: &str) -> Result<String, String> {
    if password.is_empty() {
        list_rar_inner(input)
    } else {
        list_rar_inner_with_pw(input, password)
    }
}

/// Host-side multi-volume extract for examples/tests/benchmarks.
#[doc(hidden)]
pub fn extract_rar_volumes_host(paths: &[String], output: &str, password: &str) -> Result<(u32, u32), String> {
    let refs: Vec<&str> = paths.iter().map(|s| s.as_str()).collect();
    extract_rar_volumes_inner(&refs, output, None, password)
}

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarListEntries(mut e: JNIEnv, _: JClass, i: JString) -> jstring {
    let inp = s(&mut e, &i); match guarded(move || list_rar_inner(&inp)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("{er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarListEntriesWithPassword(mut e: JNIEnv, _: JClass, i: JString, pw: JString) -> jstring {
    let inp = s(&mut e, &i); let pwd = s(&mut e, &pw);
    match guarded(move || list_rar_inner_with_pw(&inp, &pwd)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("{er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtract(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let _ = std::fs::create_dir_all(&out);
    match guarded(move || extract_rar_inner(&inp, &out, None, "")) { Ok((total, f)) => { let json = extract_result_json(total, total.saturating_sub(f), f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractSelected(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel);
    let ss: HashSet<String> = sel_str.lines().filter(|l| !l.is_empty()).map(|s| s.to_string()).collect();
    match guarded(move || extract_rar_inner(&inp, &out, Some(&ss), "")) { Ok((total, f)) => { let json = extract_result_json(total, total.saturating_sub(f), f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractWithPassword(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, pw: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let pwd = s(&mut e, &pw); let _ = std::fs::create_dir_all(&out);
    match guarded(move || extract_rar_inner(&inp, &out, None, &pwd)) { Ok((total, f)) => { let json = extract_result_json(total, total.saturating_sub(f), f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarNeedsPassword(mut e: JNIEnv, _: JClass, i: JString) -> jboolean {
    let inp = s(&mut e, &i);
    match guarded(move || rar_needs_password_inner(&inp)) { Ok(true) => JNI_TRUE, Ok(false) => JNI_FALSE, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("rar: {er}")); JNI_FALSE } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractSelectedWithPassword(mut e: JNIEnv, _: JClass, _t: JString, i: JString, o: JString, sel: JString, pw: JString) -> jstring {
    extract_progress::clear_cancel();
    let inp = s(&mut e, &i); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel); let pwd = s(&mut e, &pw); let _ = std::fs::create_dir_all(&out);
    let ss: HashSet<String> = sel_str.lines().filter(|l| !l.is_empty()).map(|s| s.to_string()).collect();
    match guarded(move || extract_rar_inner(&inp, &out, Some(&ss), &pwd)) { Ok((total, f)) => { let json = extract_result_json(total, total.saturating_sub(f), f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractProgressCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractProgressTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::total_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractProgressFileCount(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_bytes() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractProgressFileTotal(_: JNIEnv, _: JClass) -> jlong { extract_progress::file_total() as jlong }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractProgressName(e: JNIEnv, _: JClass) -> jstring {
    e.new_string(&extract_progress::name()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractCancel(_: JNIEnv, _: JClass) { extract_progress::cancel(); }
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_setParallelThreads(_: JNIEnv, _: JClass, n: jint) { set_parallel_threads(n.max(0) as u32); }

fn volume_refs(vols: &[String]) -> Vec<&str> { vols.iter().map(|s| s.as_str()).collect() }

#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarListEntriesVolumes(mut e: JNIEnv, _: JClass, v: JString) -> jstring {
    let vs = s(&mut e, &v); let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(move || list_rar_volumes_inner(&volume_refs(&vols))) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("{er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarListEntriesVolumesWithPassword(mut e: JNIEnv, _: JClass, v: JString, pw: JString) -> jstring {
    let vs = s(&mut e, &v); let pwd = s(&mut e, &pw);
    let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(move || list_rar_volumes_inner_with_pw(&volume_refs(&vols), &pwd)) { Ok(j) => match e.new_string(&j) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() }, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("{er}")); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractVolumes(mut e: JNIEnv, _: JClass, _t: JString, v: JString, o: JString) -> jstring {
    extract_progress::clear_cancel();
    let vs = s(&mut e, &v); let out = s(&mut e, &o); let _ = std::fs::create_dir_all(&out);
    let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(move || extract_rar_volumes_inner(&volume_refs(&vols), &out, None, "")) { Ok((total, f)) => { let json = extract_result_json(total, total.saturating_sub(f), f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractSelectedVolumes(mut e: JNIEnv, _: JClass, _t: JString, v: JString, o: JString, sel: JString) -> jstring {
    extract_progress::clear_cancel();
    let vs = s(&mut e, &v); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel);
    let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    let ss: HashSet<String> = sel_str.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(move || extract_rar_volumes_inner(&volume_refs(&vols), &out, Some(&ss), "")) { Ok((total, f)) => { let json = extract_result_json(total, total.saturating_sub(f), f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractVolumesWithPassword(mut e: JNIEnv, _: JClass, _t: JString, v: JString, o: JString, pw: JString) -> jstring {
    extract_progress::clear_cancel();
    let vs = s(&mut e, &v); let out = s(&mut e, &o); let pwd = s(&mut e, &pw); let _ = std::fs::create_dir_all(&out);
    let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(move || extract_rar_volumes_inner(&volume_refs(&vols), &out, None, &pwd)) { Ok((total, f)) => { let json = extract_result_json(total, total.saturating_sub(f), f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle] pub extern "system" fn Java_com_usefulunpacker_RarCore_rarExtractSelectedVolumesWithPassword(mut e: JNIEnv, _: JClass, _t: JString, v: JString, o: JString, sel: JString, pw: JString) -> jstring {
    extract_progress::clear_cancel();
    let vs = s(&mut e, &v); let out = s(&mut e, &o); let sel_str = s(&mut e, &sel); let pwd = s(&mut e, &pw); let _ = std::fs::create_dir_all(&out);
    let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    let ss: HashSet<String> = sel_str.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(move || extract_rar_volumes_inner(&volume_refs(&vols), &out, Some(&ss), &pwd)) { Ok((total, f)) => { let json = extract_result_json(total, total.saturating_sub(f), f); match e.new_string(&json) { Ok(js) => js.into_raw(), _ => std::ptr::null_mut() } }, Err(er) => { let _ = e.throw_new("java/io/IOException", er); std::ptr::null_mut() } }
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_RarCore_rarVolumesNeedsPassword(mut e: JNIEnv, _: JClass, v: JString) -> jboolean {
    let vs = s(&mut e, &v); let vols: Vec<String> = vs.lines().filter(|l| !l.is_empty()).map(|x| x.to_string()).collect();
    match guarded(move || rar_volumes_needs_password_inner(&volume_refs(&vols))) { Ok(true) => JNI_TRUE, Ok(false) => JNI_FALSE, Err(er) => { let _ = e.throw_new("java/io/IOException", format!("rar: {er}")); JNI_FALSE } }
}

#[cfg(test)]
mod tests {

    /// 进度 store 是 per-cdylib 的**静态量**，cargo 默认并行跑同一个 crate
    /// 的测试，两个测试的 `reset(total)` + `add_bytes` 会互相踩：抢在前面的那个
    /// 会用自己的夹具尺寸改掉 total，后一个断言 total 的测试就红。凡是调了
    /// extract/compress 入口的测试都必须持这把锁。
    ///
    /// 实证：`archive_lzma-core` 的 `extract_progress_total_is_reported` 曾在 CI 上
    /// 以 `left: 327, right: 119` 失败，本地 25/25 通过。

    use super::*;
    use rars::rar13::{write_stored_archive, write_stored_volumes, StoredEntry, WriterOptions};
    use rars::features::FeatureSet;
    use rars::version::ArchiveVersion;

    fn make_volumes() -> Vec<std::path::PathBuf> {
        let data: Vec<u8> = (0..4096u32).map(|i| (i % 251) as u8).collect();
        let entry = StoredEntry {
            name: b"data/file.bin",
            data: &data,
            file_time: 0,
            file_attr: 0,
            password: None,
            file_comment: None,
        };
        let opts = WriterOptions::new(ArchiveVersion::Rar14, FeatureSet::store_only());
        let vols = write_stored_volumes(entry, opts, 1024).expect("write volumes");
        assert!(vols.len() >= 2, "expected multiple volumes, got {}", vols.len());
        let dir = std::env::temp_dir().join(format!("uu_rar_vol_test_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let mut paths = Vec::new();
        for (i, v) in vols.iter().enumerate() {
            let p = dir.join(format!("vol{i}.rar"));
            std::fs::write(&p, v).unwrap();
            paths.push(p);
        }
        paths
    }

    /// A stored member whose payload was altered must be REJECTED, and must not
    /// leave the wrong bytes on disk.
    ///
    /// Before this, `crates/rar-core` never verified a member checksum at all:
    /// `faults/corrupt-rawfile1.m5.rar` in files4testing decoded "successfully"
    /// and left a plausible-looking but corrupt `rawfile1.txt`, while `unrar t`
    /// on the same file reports `checksum error`. That is the worst failure
    /// shape: the user is told it worked and gets wrong data.
    ///
    /// RAR1.3/1.4 stores only the low 16 bits of the CRC-32
    /// (`(crc32(&data) & 0xffff) as u16` in the vendored writer), so the spec is
    /// [CrcSpec::Low16] — a fixture that assumed a full 32-bit compare would
    /// encode the wrong premise and pass for the wrong reason.
    #[test]
    fn corrupt_stored_member_is_rejected_and_leaves_nothing() {
        let _g = crate::TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        // Single-volume on purpose: `make_volumes()` produces a split archive,
        // and extracting one volume alone fails with "RAR 1.3 split entry
        // requires multivolume extraction" — a parse error, which would let this
        // test pass without ever reaching the checksum.
        let payload: Vec<u8> = (0..4096u32).map(|i| (i % 251) as u8).collect();
        let entry = StoredEntry {
            name: b"data/file.bin",
            data: &payload,
            file_time: 0,
            file_attr: 0,
            password: None,
            file_comment: None,
        };
        let opts = WriterOptions::new(ArchiveVersion::Rar14, FeatureSet::store_only());
        let original = write_stored_archive(&[entry], opts).expect("build single-volume rar");
        let dir = std::env::temp_dir().join(format!("uu_rar_crc_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let src = dir.join("ok.rar");
        std::fs::write(&src, &original).unwrap();

        // Sanity: the pristine archive extracts cleanly first, otherwise a
        // broken fixture could make the corrupt-case assertion pass for the
        // wrong reason.
        let ok_out = std::env::temp_dir().join(format!("uu_rar_crc_ok_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&ok_out);
        let ok_s = ok_out.to_string_lossy().to_string();
        extract_rar_inner(src.to_str().unwrap(), &ok_s, None, "")
            .expect("pristine archive must extract");
        assert_eq!(std::fs::read(ok_out.join("data/file.bin")).unwrap().len(), 4096);
        std::fs::remove_dir_all(&ok_out).ok();

        // Flip a byte well inside the stored payload (past the 7-byte marker and
        // the first header block) so the archive still PARSES — a parse error
        // would make this test pass without ever exercising the checksum.
        let mut blob = original.clone();
        let at = blob.len() / 2;
        blob[at] ^= 0xff;
        let bad = std::env::temp_dir().join(format!("uu_rar_crc_bad_{}", std::process::id()));
        std::fs::write(&bad, &blob).unwrap();

        // It must still be a readable archive — proof the corruption landed in
        // the payload, not in the structure.
        let listed = list_rar_inner(bad.to_str().unwrap()).expect("corrupt archive must still list");
        assert!(listed.contains("file.bin"), "corruption broke parsing, not the payload: {listed}");

        let out = std::env::temp_dir().join(format!("uu_rar_crc_out_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&out);
        let out_s = out.to_string_lossy().to_string();
        // RAR 1.3/1.4 aborts the whole extraction via its own built-in
        // `verify_checksum`, so the honest contract here is `Err` — the
        // corruption is detected before any output is considered good. RAR 1.5+
        // takes the per-entry path instead and reports `Ok((total, errors))`,
        // which is the "Ok ≠ success" trap; both must be treated as failure by
        // the caller, and the app's `err > 0 || total == 0` check covers it.
        let res = extract_rar_inner(bad.to_str().unwrap(), &out_s, None, "");
        let detected = match &res {
            Err(_) => true,
            Ok((_total, err)) => *err > 0,
        };
        assert!(
            detected,
            "a member that fails its checksum must not be reported as a success, got {res:?}"
        );
        assert!(
            !out.join("data/file.bin").exists(),
            "a member that failed its checksum must not be left on disk"
        );
        std::fs::remove_dir_all(&out).ok();
        std::fs::remove_file(&bad).ok();
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn lists_and_extracts_multivolume_rar() {
        let _g = crate::TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let vols = make_volumes();
        let paths: Vec<String> = vols.iter().map(|p| p.to_string_lossy().to_string()).collect();
        let refs: Vec<&str> = paths.iter().map(|s| s.as_str()).collect();

        let list = list_rar_volumes_inner(&refs).expect("list volumes");
        assert!(list.contains("data/file.bin"), "missing entry in {list}");

        let out = std::env::temp_dir().join(format!("uu_rar_vol_out_{}", std::process::id()));
        let out_s = out.to_string_lossy().to_string();
        extract_rar_volumes_inner(&refs, &out_s, None, "").expect("extract volumes");
        let got = std::fs::read(out.join("data/file.bin")).expect("read extracted");
        assert_eq!(got.len(), 4096);
        assert_eq!(got[100], 100);
        assert_eq!(got[4095], (4095u32 % 251) as u8);
        std::fs::remove_dir_all(&out).ok();
        for p in &vols { std::fs::remove_file(p).ok(); }
        if let Some(dir) = vols[0].parent() { std::fs::remove_dir_all(dir).ok(); }
    }

    /// rar_needs_password_inner: a readable archive with no encrypted members
    /// → false; an unparseable RAR header (the -hp header-encrypted case, where
    /// the reader cannot even open the archive without a password) → true, so
    /// the app prompts for a password instead of silently failing.
    #[test]
    fn needs_password_true_on_unparseable_header() {
        let _g = crate::TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let dir = std::env::temp_dir().join(format!("uu_rar_np_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();

        // A normal stored RAR: no password needed. (Rar14 volume writer needs
        // >= 2 volumes, so the payload must exceed the 1024B split size.)
        let data: Vec<u8> = (0..4096u32).map(|i| (i % 251) as u8).collect();
        let entry = StoredEntry {
            name: b"plain.bin",
            data: &data,
            file_time: 0,
            file_attr: 0,
            password: None,
            file_comment: None,
        };
        let opts = WriterOptions::new(ArchiveVersion::Rar14, FeatureSet::store_only());
        let vols = write_stored_volumes(entry, opts, 1024).expect("write");
        let plain = dir.join("plain.rar");
        std::fs::write(&plain, &vols[0]).unwrap();
        assert!(!rar_needs_password_inner(plain.to_str().unwrap()).expect("plain needs_pw"),
            "unencrypted rar must report false");

        // Garbage / a header the reader cannot parse (mirrors -hp) → true.
        let junk = dir.join("junk.rar");
        std::fs::write(&junk, b"\x52\x61\x72\x21\x1a\x07\x01\x00\xDE\xAD\xBE\xEF garbage").unwrap();
        assert!(rar_needs_password_inner(junk.to_str().unwrap()).expect("junk needs_pw"),
            "unparseable header must be treated as needing a password");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// Cancelling before/during a buffered (whole-member) decode must surface a
    /// Cancelled error promptly instead of running the member to completion —
    /// the cancel monitor mirrors the extract-progress flag into the vendored
    /// rars whole-member decode flag.
    #[test]
    fn cancel_aborts_buffered_decode() {
        let _g = crate::TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let dir = std::env::temp_dir().join(format!("uu_rar_cancel_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let data: Vec<u8> = (0..4096u32).map(|i| (i % 251) as u8).collect();
        let entry = StoredEntry {
            name: b"a.bin",
            data: &data,
            file_time: 0,
            file_attr: 0,
            password: None,
            file_comment: None,
        };
        let opts = WriterOptions::new(ArchiveVersion::Rar14, FeatureSet::store_only());
        let vols = write_stored_volumes(entry, opts, 1024).expect("write");
        let arc = dir.join("c.rar");
        std::fs::write(&arc, &vols[0]).unwrap();
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();

        // Pre-set the cancel flag; the monitor must bridge it and the extract
        // must error (not complete) — stored entries stream, so the cancel is
        // caught by the member-boundary check in rar_writer.
        extract_progress::cancel();
        let r = extract_rar_inner(arc.to_str().unwrap(), out.to_str().unwrap(), None, "");
        extract_progress::clear_cancel();
        assert!(r.is_err(), "cancelled extract must error: {r:?}");
        assert_eq!(r.unwrap_err(), "cancelled", "must report cancelled");
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn traversal_entry_rejected_and_counted() {
        // An entry named "../evil.txt" must never be written anywhere — the
        // safe_join failure counts the entry as failed and sinks its data
        // (no fallback join that escapes the output directory).
        let _g = crate::TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let data: Vec<u8> = (0..2048u32).map(|i| (i % 251) as u8).collect();
        let entry = StoredEntry {
            name: b"../evil.txt",
            data: &data,
            file_time: 0,
            file_attr: 0,
            password: None,
            file_comment: None,
        };
        let opts = WriterOptions::new(ArchiveVersion::Rar14, FeatureSet::store_only());
        // The RAR13 writer always splits into at least two volumes.
        let vols = write_stored_volumes(entry, opts, 1024).expect("write archive");
        assert!(vols.len() >= 2);
        let dir = std::env::temp_dir().join(format!("uu_rar_trav_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let mut paths = Vec::new();
        for (i, v) in vols.iter().enumerate() {
            let p = dir.join(format!("vol{i}.rar"));
            std::fs::write(&p, v).unwrap();
            paths.push(p);
        }
        let path_strs: Vec<String> = paths.iter().map(|p| p.to_string_lossy().to_string()).collect();
        let refs: Vec<&str> = path_strs.iter().map(|s| s.as_str()).collect();
        let out = dir.join("out");
        std::fs::create_dir_all(&out).unwrap();
        let (total, fail) = extract_rar_volumes_inner(
            &refs, out.to_str().unwrap(), None, ""
        ).expect("extract must complete");
        assert_eq!(total, 1);
        assert_eq!(fail, 1, "traversal entry must be counted as failed");
        // out/../evil.txt == dir/evil.txt is the exact escape target
        assert!(!dir.join("evil.txt").exists(), "must not escape into the parent dir");
        assert!(!out.join("evil.txt").exists());
        assert_eq!(std::fs::read_dir(&out).unwrap().count(), 0, "nothing may be written");
        std::fs::remove_dir_all(&dir).ok();
    }
}

/// Manual host verification against real split RAR files.
/// Set `UU_RAR_PARTS` (colon-separated part paths) and optional `UU_RAR_PASS`.
/// Skips (passes silently) when the env var is absent.
#[cfg(test)]
mod manual_volumes {

    /// 进度 store 是 per-cdylib 的**静态量**，cargo 默认并行跑同一个 crate
    /// 的测试，两个测试的 `reset(total)` + `add_bytes` 会互相踩：抢在前面的那个
    /// 会用自己的夹具尺寸改掉 total，后一个断言 total 的测试就红。凡是调了
    /// extract/compress 入口的测试都必须持这把锁。
    ///
    /// 实证：`archive_lzma-core` 的 `extract_progress_total_is_reported` 曾在 CI 上
    /// 以 `left: 327, right: 119` 失败，本地 25/25 通过。

    use super::*;
    use std::time::Instant;

    fn extract_names(json: &str) -> Vec<String> {
        let mut names = Vec::new();
        let bytes = json.as_bytes();
        let mut i = 0;
        while i < bytes.len() {
            if bytes[i..].starts_with(br#""n":""#) {
                let start = i + 5;
                let mut j = start;
                while j < bytes.len() {
                    let b = bytes[j];
                    if b == b'\\' { j += 2; continue; }
                    if b == b'"' { break; }
                    j += 1;
                }
                if let Ok(s) = std::str::from_utf8(&bytes[start..j]) {
                    names.push(s.to_string());
                }
                i = j + 1;
            } else {
                i += 1;
            }
        }
        names
    }

    fn count_files(dir: &std::path::Path) -> usize {
        let mut n = 0;
        if let Ok(entries) = std::fs::read_dir(dir) {
            for e in entries.flatten() {
                if e.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                    n += count_files(&e.path());
                } else {
                    n += 1;
                }
            }
        }
        n
    }

    fn sha256_file(path: &std::path::Path) -> Option<String> {
        let out = std::process::Command::new("shasum").arg("-a").arg("256").arg(path).output().ok()?;
        if !out.status.success() { return None; }
        Some(String::from_utf8_lossy(&out.stdout).split_whitespace().next().unwrap_or("").to_string())
    }

    fn walk_files(dir: &std::path::Path, out: &mut Vec<std::path::PathBuf>) {
        let mut stack: Vec<std::path::PathBuf> = vec![dir.to_path_buf()];
        while let Some(d) = stack.pop() {
            if let Ok(entries) = std::fs::read_dir(&d) {
                for e in entries.flatten() {
                    if e.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                        stack.push(e.path());
                    } else {
                        out.push(e.path());
                    }
                }
            }
        }
    }

    #[test]
    fn manual_rar_volumes() {
        let _g = crate::TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let Ok(parts) = std::env::var("UU_RAR_PARTS") else {
            eprintln!("[manual_rar] skipped: UU_RAR_PARTS not set");
            return;
        };
        let pass = std::env::var("UU_RAR_PASS").unwrap_or_default();
        let paths: Vec<String> = parts.split(':').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect();
        if paths.is_empty() {
            eprintln!("[manual_rar] skipped: empty parts");
            return;
        }
        let refs: Vec<&str> = paths.iter().map(|s| s.as_str()).collect();
        println!("[manual_rar] parts = {}  |  UU_RAR_PASS={}", paths.len(), if pass.is_empty() { "(none)" } else { "set" });

        // 1. needs_password (no password; header-encrypted sets will error — expected)
        match rar_volumes_needs_password_inner(&refs) {
            Ok(np) => println!("[manual_rar] needs_password = {np}"),
            Err(e) => println!("[manual_rar] needs_password err (no pw) = {e}"),
        }

        // 2. list (no password)
        let mut all_names: Vec<String> = Vec::new();
        match list_rar_volumes_inner(&refs) {
            Ok(j) => {
                all_names = extract_names(&j);
                println!("[manual_rar] list ok (no pw): {} entries", all_names.len());
                for n in all_names.iter().take(8) { println!("[manual_rar]   - {n}"); }
            }
            Err(e) => println!("[manual_rar] list err (no pw) = {e}"),
        }

        // 3. if password given, read members with password for selected-extract names
        let pw_bytes: Option<&[u8]> = if pass.is_empty() { None } else { Some(pass.as_bytes()) };
        if pw_bytes.is_some() && all_names.is_empty() {
            if let Ok(archives) = read_volumes(&refs, pw_bytes) {
                let mut seen = HashSet::new();
                let mut files = Vec::new();
                for a in &archives {
                    for m in a.members() {
                        let n = m.meta.name_lossy().replace('\\', "/").trim_matches('/').to_string();
                        if m.meta.is_directory || n.is_empty() || n.ends_with('/') { continue; }
                        if seen.insert(n.clone()) { files.push(n); }
                    }
                }
                println!("[manual_rar] members readable with pw: {} files", files.len());
                for n in files.iter().take(8) { println!("[manual_rar]   - {n}"); }
                all_names = files;
            }
        }

        // 4. selected extract FIRST (targets the large filtered member if present)
        if !all_names.is_empty() && !pass.is_empty() {
            let out2 = std::env::temp_dir().join(format!("uu_rar_manual_sel_{}", std::process::id()));
            std::fs::create_dir_all(&out2).unwrap();
            let out2_s = out2.to_string_lossy().to_string();
            let mut set = HashSet::new();
            // prefer the large filtered member to exercise the buffered-decode path
            if let Some(big) = all_names.iter().find(|n| n.to_lowercase().contains("data.xp3") || n.to_lowercase().contains(".xp3")) {
                set.insert(big.clone());
            }
            for n in all_names.iter().take(1) { set.insert(n.clone()); }
            eprintln!("[manual_rar] START selected extract ({} files)", set.len());
            let t0 = Instant::now();
            match extract_rar_volumes_inner(&refs, &out2_s, Some(&set), &pass) {
                Ok((total, fail)) => {
                    println!("[manual_rar] selected extract total={total} fail={fail} files_on_disk={} in {:.2}s", count_files(&out2), t0.elapsed().as_secs_f64());
                    for (i, n) in all_names.iter().enumerate() {
                        if set.contains(n) {
                            let f = std::path::Path::new(&out2_s).join(n);
                            println!("[manual_rar]   sel #{i}: {n}  exists={} size={}", f.exists(), f.metadata().map(|m| m.len()).unwrap_or(0));
                        }
                    }
                }
                Err(e) => println!("[manual_rar] selected extract err = {e}"),
            }
            std::fs::remove_dir_all(&out2).ok();
        }

        // 4b. full extract + SHA-256 verification (when UU_EXPECTED_SHA set)
        if let Ok(exp) = std::env::var("UU_EXPECTED_SHA") {
            let outv = std::env::temp_dir().join(format!("uu_rar_verify_{}", std::process::id()));
            std::fs::create_dir_all(&outv).unwrap();
            let outv_s = outv.to_string_lossy().to_string();
            let t0 = Instant::now();
            match extract_rar_volumes_inner(&refs, &outv_s, None, &pass) {
                Ok((total, fail)) => {
                    println!("[manual_rar] VERIFY extract total={total} fail={fail} in {:.2}s", t0.elapsed().as_secs_f64());
                    let mut files = Vec::new();
                    walk_files(&outv, &mut files);
                    let mut matched = false;
                    for f in &files {
                        let h = sha256_file(f).unwrap_or_default();
                        let ok = h == exp;
                        if ok { matched = true; }
                        println!("[manual_rar]   file={} sha={} expected={} {}", f.strip_prefix(&outv).map(|p| p.to_string_lossy().to_string()).unwrap_or_default(), h, exp, if ok { "MATCH" } else { "DIFF" });
                    }
                    if matched { println!("[manual_rar] VERIFY PASS"); } else { println!("[manual_rar] VERIFY FAIL (no file matched)"); }
                }
                Err(e) => println!("[manual_rar] VERIFY extract err = {e}"),
            }
            std::fs::remove_dir_all(&outv).ok();
        }

        // 5. full extract — only when UU_RAR_FULL=1 (a full 6.8GB archive takes a long time)
        if std::env::var("UU_RAR_FULL").map(|v| v == "1").unwrap_or(false) {
            let out = std::env::temp_dir().join(format!("uu_rar_manual_{}", std::process::id()));
            std::fs::create_dir_all(&out).unwrap();
            let out_s = out.to_string_lossy().to_string();
            eprintln!("[manual_rar] START full extract -> {}", out_s);
            let monitor = std::thread::spawn({
                let out2 = out.clone();
                move || {
                    let mut last = 0u64;
                    for _ in 0..300 {
                        std::thread::sleep(std::time::Duration::from_secs(2));
                        let b = extract_progress::bytes();
                        let t = extract_progress::total_bytes();
                        let f = count_files(&out2);
                        if b != last {
                            last = b;
                            eprintln!("[manual_rar] progress bytes={b} total={t} files_on_disk={f}");
                        }
                    }
                }
            });
            let t0 = Instant::now();
            match extract_rar_volumes_inner(&refs, &out_s, None, &pass) {
                Ok((total, fail)) => {
                    println!("[manual_rar] full extract total={total} fail={fail}  in {:.2}s", t0.elapsed().as_secs_f64());
                    println!("[manual_rar] progress bytes={} total_bytes={} name={}", extract_progress::bytes(), extract_progress::total_bytes(), extract_progress::name());
                    println!("[manual_rar] files on disk = {}", count_files(&out));
                }
                Err(e) => println!("[manual_rar] full extract err = {e}"),
            }
            monitor.join().ok();
            std::fs::remove_dir_all(&out).ok();
        }
    }

    /// Probe: `UU_RAR_PROBE` = path to a real rar. Lists members (name/size/
    /// stored/solid) then extracts the whole archive while logging progress
    /// every 200ms — used to reproduce the "progress bar frozen on a large
    /// member" report with a real 1.1GB galgame archive.
    #[test]
    fn probe_real_archive_progress() {
        let _g = crate::TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let Ok(probe) = std::env::var("UU_RAR_PROBE") else {
            eprintln!("[probe] skipped: UU_RAR_PROBE not set");
            return;
        };
        let archive = rars::ArchiveReader::read_path_with_options(Path::new(&probe), rar_opts(None))
            .expect("open archive");
        let solid = match &archive {
            rars::Archive::Rar50Plus(a) => a.main.is_solid(),
            rars::Archive::Rar15To40(a) => a.main.is_solid(),
            rars::Archive::Rar13(_) => false,
            _ => false,
        };
        eprintln!("[probe] family={:?} solid={solid}", archive.family());
        for m in archive.members() {
            let n = m.meta.name_lossy().replace('\\', "/").trim_matches('/').to_string();
            if n.is_empty() || m.meta.is_directory { continue; }
            eprintln!(
                "[probe] {} size={} stored={} enc={}",
                n, m.meta.unpacked_size, m.meta.is_stored, m.meta.is_encrypted
            );
        }

        let out = std::env::temp_dir().join(format!("uu_probe_out_{}", std::process::id()));
        std::fs::create_dir_all(&out).unwrap();
        let out_s = out.to_string_lossy().to_string();
        let monitor = std::thread::spawn(move || {
            let mut last_b = 0u64;
            let mut last_f = 0u64;
            let mut last_n = String::new();
            let mut max_b = 0u64;
            for _ in 0..2000 {
                std::thread::sleep(std::time::Duration::from_millis(200));
                let b = extract_progress::bytes();
                let t = extract_progress::total_bytes();
                let fb = extract_progress::file_bytes();
                let ft = extract_progress::file_total();
                let n = extract_progress::name();
                if b > max_b { max_b = b; }
                if b > t { eprintln!("[probe] !! TOP OVER TOTAL: {b} > {t}"); }
                if fb > ft && ft > 0 { eprintln!("[probe] !! FILE OVER TOTAL: {fb} > {ft} name={n}"); }
                if b != last_b || fb != last_f || n != last_n {
                    last_b = b; last_f = fb; last_n = n.clone();
                    eprintln!("[probe] top={b}/~{t}  file={fb}/{ft}  name={n}");
                }
            }
            eprintln!("[probe] monitor max_top={max_b}");
        });
        let t0 = std::time::Instant::now();
        let r = extract_rar_inner(&probe, &out_s, None, "");
        eprintln!("[probe] extract result={r:?} in {:.2}s", t0.elapsed().as_secs_f64());
        eprintln!(
            "[probe] final top={} total={} (match={})  file={}/{}",
            extract_progress::bytes(),
            extract_progress::total_bytes(),
            extract_progress::bytes() == extract_progress::total_bytes(),
            extract_progress::file_bytes(),
            extract_progress::file_total()
        );
        monitor.join().ok();
        std::fs::remove_dir_all(&out).ok();
    }

    /// Probe the non-solid fast path: `UU_RAR_SEL_PROBE` = path to a real rar.
    /// Selects the LAST text member (or the member named in
    /// `UU_RAR_SEL_NAME`) and extracts it, timing how long it takes — the fast
    /// path must skip the preceding members, so a late txt comes out near
    /// instantly instead of decoding the whole archive.
    #[test]
    fn probe_selected_fast() {
        let _g = crate::TEST_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let Ok(probe) = std::env::var("UU_RAR_SEL_PROBE") else {
            eprintln!("[sel] skipped: UU_RAR_SEL_PROBE not set");
            return;
        };
        let archive = rars::ArchiveReader::read_path_with_options(Path::new(&probe), rar_opts(None))
            .expect("open archive");
        let mut names: Vec<String> = Vec::new();
        for m in archive.members() {
            let n = m.meta.name_lossy().replace('\\', "/").trim_matches('/').to_string();
            if !m.meta.is_directory && !n.is_empty() {
                names.push(n);
            }
        }
        let wanted = std::env::var("UU_RAR_SEL_NAME").ok();
        let pick = wanted.unwrap_or_else(|| {
            names.iter().rev().find(|n| n.to_lowercase().ends_with(".txt"))
                .cloned().unwrap_or_else(|| names.last().cloned().unwrap())
        });
        eprintln!("[sel] selecting: {pick}  (member #{} of {})", names.iter().position(|n| n == &pick).unwrap_or(usize::MAX), names.len());
        let mut set = HashSet::new();
        set.insert(pick.clone());
        let out = std::env::temp_dir().join(format!("uu_sel_out_{}", std::process::id()));
        std::fs::create_dir_all(&out).unwrap();
        let out_s = out.to_string_lossy().to_string();
        let t0 = std::time::Instant::now();
        let r = extract_rar_inner(&probe, &out_s, Some(&set), "");
        eprintln!("[sel] selected extract = {r:?} in {:.3}s", t0.elapsed().as_secs_f64());
        let f = std::path::Path::new(&out_s).join(&pick);
        eprintln!("[sel] file exists={} size={}", f.exists(), f.metadata().map(|m| m.len()).unwrap_or(0));
        std::fs::remove_dir_all(&out).ok();
    }
}
