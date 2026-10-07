use jni::JNIEnv;
use jni::objects::JString;
use std::collections::{BTreeSet, HashSet};
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Mutex;

/// Hard cap for formats with no declared uncompressed size (bzip2/xz/zstd).
/// 16 GiB is far above any legitimate single-file output while bounding a
/// decompression bomb — a few KB of crafted input can't exhaust disk.
pub const DEFAULT_EXTRACT_CAP: u64 = 16 * 1024 * 1024 * 1024;

/// Byte-based progress stores. Each cdylib statically links this crate, so the
/// statics below are independent per format library.
macro_rules! progress_store {
    ($name:ident) => {
        pub mod $name {
            use super::*;

            static BYTES: AtomicU64 = AtomicU64::new(0);
            static TOTAL: AtomicU64 = AtomicU64::new(0);
            static FILE_BYTES: AtomicU64 = AtomicU64::new(0);
            static FILE_TOTAL: AtomicU64 = AtomicU64::new(0);
            static FNAME: Mutex<String> = Mutex::new(String::new());
            static CANCEL: AtomicBool = AtomicBool::new(false);

            pub fn reset(total_bytes: u64) {
                BYTES.store(0, Ordering::Relaxed);
                TOTAL.store(total_bytes, Ordering::Relaxed);
                FILE_BYTES.store(0, Ordering::Relaxed);
                FILE_TOTAL.store(0, Ordering::Relaxed);
                // NOTE: the CANCEL flag is deliberately preserved — a cancel
                // pressed during a pre-scan must survive into the extraction
                // phase. Call clear_cancel() at the very start of a fresh
                // operation (before any pre-scan) instead.
                *FNAME.lock().unwrap_or_else(|e| e.into_inner()) = String::new();
            }

            /// Starts a fresh operation: clears the cancel flag AND zeroes every
            /// byte counter.
            ///
            /// Call once at the start of each new operation (JNI entry, before
            /// the pre-scan) so a stale cancel from a previous operation can't
            /// poison this one.
            ///
            /// Zeroing the counters here is what makes the **pre-scan phase**
            /// honest. Every format does real work before its own `reset(total)`
            /// — zstd `fs::read`s the whole archive, rar opens the reader and
            /// walks every member, tar decompresses the entire outer stream in
            /// pass 1 — and the UI poll loop is already painting these statics by
            /// then. Without the zeroing it rendered the PREVIOUS operation's
            /// final value, which is almost always `bytes == total` → the bar
            /// sits at a confident 100% for the whole pre-scan. That is the
            /// "reached 100% then froze" report, and it only reproduces when a
            /// second operation follows a first one, which is exactly why it
            /// felt intermittent.
            ///
            /// `total = 0` is the correct signal here: `ExtractProgress.kt`
            /// treats `total <= 0` as indeterminate, so the phase renders as
            /// "preparing" rather than as a fabricated percentage.
            pub fn clear_cancel() {
                CANCEL.store(false, Ordering::Relaxed);
                BYTES.store(0, Ordering::Relaxed);
                TOTAL.store(0, Ordering::Relaxed);
                FILE_BYTES.store(0, Ordering::Relaxed);
                FILE_TOTAL.store(0, Ordering::Relaxed);
                *FNAME.lock().unwrap_or_else(|e| e.into_inner()) = String::new();
            }

            /// Marks the start of a new member: resets the per-file byte
            /// counter and records the member's total size. Feed per-member
            /// sizes here from each format's extract/compress loop.
            pub fn set_file(total: u64) {
                FILE_TOTAL.store(total, Ordering::Relaxed);
                FILE_BYTES.store(0, Ordering::Relaxed);
            }

            /// Accumulates into both the overall and the current-file counter.
            pub fn add_bytes(n: u64) {
                BYTES.fetch_add(n, Ordering::Relaxed);
                FILE_BYTES.fetch_add(n, Ordering::Relaxed);
            }

            /// Accumulates into the OVERALL counter only (the current-file
            /// counter is fed separately by the format's decode progress).
            /// Used for rar's buffered members: their write must count toward
            /// the total bar without double-counting the current-file bar,
            /// which the decode watcher already drives.
            pub fn add_top_bytes(n: u64) {
                BYTES.fetch_add(n, Ordering::Relaxed);
            }

            /// Sets the current-file byte counter directly (no overall change).
            /// Used to report decode progress of a member that is buffered into
            /// RAM before anything is written — the overall counter is fed by
            /// the writer later, so this must NOT double-count.
            pub fn set_file_bytes(n: u64) {
                FILE_BYTES.store(n, Ordering::Relaxed);
            }

            /// Shifts the OVERALL total by a signed delta. Poorly repacked
            /// archives disagree with their own index (actual decoded length
            /// ≠ declared size); re-basing the total after each such entry
            /// keeps the bar ending exactly at 100% instead of stuck short
            /// of (or past) full. Saturating — a bogus declared size can't
            /// wrap the counter through zero.
            pub fn adjust_total(delta: i64) {
                if delta >= 0 {
                    TOTAL.fetch_add(delta as u64, Ordering::Relaxed);
                } else {
                    let next = TOTAL.load(Ordering::Relaxed).saturating_sub(delta.unsigned_abs());
                    TOTAL.store(next, Ordering::Relaxed);
                }
            }

            /// Re-bases the current file's counters to the size actually on
            /// disk. Companion to `adjust_total` for entries whose real
            /// decoded length differs from the index-declared one, so the
            /// per-file bar doesn't linger past/below its target.
            pub fn calibrate_file(written: u64) {
                FILE_TOTAL.store(written, Ordering::Relaxed);
                FILE_BYTES.store(written, Ordering::Relaxed);
            }

            pub fn set_name(name: &str) { *FNAME.lock().unwrap_or_else(|e| e.into_inner()) = name.to_string(); }

            pub fn cancel() { CANCEL.store(true, Ordering::Relaxed); }
            pub fn cancelled() -> bool { CANCEL.load(Ordering::Relaxed) }
            pub fn bytes() -> u64 { BYTES.load(Ordering::Relaxed) }
            pub fn total_bytes() -> u64 { TOTAL.load(Ordering::Relaxed) }
            pub fn file_bytes() -> u64 { FILE_BYTES.load(Ordering::Relaxed) }
            pub fn file_total() -> u64 { FILE_TOTAL.load(Ordering::Relaxed) }
            pub fn name() -> String { FNAME.lock().unwrap_or_else(|e| e.into_inner()).clone() }
        }
    }
}

progress_store!(extract_progress);
progress_store!(compress_progress);

/// Wraps a `Write` and accumulates written bytes into a progress store.
/// Also aborts the write with an `Other` error when the format's cancel flag
/// is raised, so large single members stop promptly instead of running to
/// completion before the next cancel check point. NOTE: `Other` — never
/// `Interrupted` — because std's `io::copy`/`write_all` retry Interrupted
/// forever, which would spin at 100% CPU on cancel instead of aborting.
pub struct ProgressWriter<W> {
    inner: W,
    sink: fn(u64),
    check: fn() -> bool,
}

impl<W> ProgressWriter<W> {
    pub fn extract(inner: W) -> Self { Self { inner, sink: extract_progress::add_bytes, check: extract_progress::cancelled } }
    pub fn compress(inner: W) -> Self { Self { inner, sink: compress_progress::add_bytes, check: compress_progress::cancelled } }
    /// Counts the write toward the OVERALL bar only. rar's buffered members
    /// use this: their current-file bar is fed from the decode progress by the
    /// watcher, so the write must not double-count it — but the overall bar
    /// must still be exact (fed by the write), not by the lossy decode poll.
    pub fn extract_top(inner: W) -> Self { Self { inner, sink: extract_progress::add_top_bytes, check: extract_progress::cancelled } }
}

impl<W: std::io::Write> std::io::Write for ProgressWriter<W> {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        if (self.check)() {
            return Err(std::io::Error::new(std::io::ErrorKind::Other, "cancelled"));
        }
        let n = self.inner.write(buf)?;
        (self.sink)(n as u64);
        Ok(n)
    }
    fn flush(&mut self) -> std::io::Result<()> { self.inner.flush() }
}

/// Caps the number of bytes written to the inner writer — a decompression
/// bomb (tiny input, huge output) can't exhaust disk when the format's
/// declared uncompressed size is honored.
pub struct BoundedWriter<W> {
    inner: W,
    remaining: u64,
}

impl<W> BoundedWriter<W> {
    pub fn new(inner: W, limit: u64) -> Self { Self { inner, remaining: limit } }
}

impl<W: std::io::Write> std::io::Write for BoundedWriter<W> {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        if self.remaining == 0 {
            return Err(std::io::Error::new(std::io::ErrorKind::Other, "output exceeds declared size"));
        }
        let n = (buf.len() as u64).min(self.remaining) as usize;
        self.inner.write(&buf[..n])?;
        self.remaining -= n as u64;
        Ok(n)
    }
    fn flush(&mut self) -> std::io::Result<()> { self.inner.flush() }
}

/// Wraps a `Read` and accumulates read bytes into a progress store. Checks
/// the format's cancel flag on every read so compression (and any other
/// read-driven copy) aborts promptly when the user cancels.
pub struct ProgressReader<R> {
    inner: R,
    sink: fn(u64),
    check: fn() -> bool,
}

impl<R> ProgressReader<R> {
    pub fn extract(inner: R) -> Self { Self { inner, sink: extract_progress::add_bytes, check: extract_progress::cancelled } }
    pub fn compress(inner: R) -> Self { Self { inner, sink: compress_progress::add_bytes, check: compress_progress::cancelled } }
}

impl<R: std::io::Read> std::io::Read for ProgressReader<R> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        if (self.check)() {
            return Err(std::io::Error::new(std::io::ErrorKind::Other, "cancelled"));
        }
        let n = self.inner.read(buf)?;
        (self.sink)(n as u64);
        Ok(n)
    }
}

impl<R: std::io::BufRead> std::io::BufRead for ProgressReader<R> {
    fn fill_buf(&mut self) -> std::io::Result<&[u8]> {
        if (self.check)() {
            return Err(std::io::Error::new(std::io::ErrorKind::Other, "cancelled"));
        }
        self.inner.fill_buf()
    }
    fn consume(&mut self, amt: usize) {
        (self.sink)(amt as u64);
        self.inner.consume(amt);
    }
}

pub fn s(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|v| v.into()).unwrap_or_default()
}

use core::pin::Pin;
use core::task::{Context, Poll, RawWaker, RawWakerVTable, Waker};
use std::future::Future;

pub fn oneshot_async<Fut: Future>(fut: Fut) -> Fut::Output {
    const VTABLE: RawWakerVTable = RawWakerVTable::new(|_| RAW, |_| {}, |_| {}, |_| {});
    const RAW: RawWaker = RawWaker::new(&(), &VTABLE);
    let waker = unsafe { Waker::from_raw(RAW) };
    let mut cx = Context::from_waker(&waker);
    let mut fut = fut;
    let mut fut = unsafe { Pin::new_unchecked(&mut fut) };
    let mut polls: u32 = 0;
    loop {
        match fut.as_mut().poll(&mut cx) {
            Poll::Ready(v) => return v,
            Poll::Pending => {
                polls += 1;
                // The XP3 readers are synchronous (always Ready/Err), so
                // Pending should never persist. A future stuck in Pending
                // would otherwise spin at 100% CPU forever — bail after a
                // large number of polls; the panic is converted to an error
                // by the JNI `guarded` wrapper.
                if polls > 1_000_000 {
                    panic!("oneshot_async: future never completed");
                }
                std::thread::yield_now();
            }
        }
    }
}

pub struct SyncIo<T>(pub T);
impl<T: std::io::Read + Unpin> tokio::io::AsyncRead for SyncIo<T> {
    fn poll_read(mut self: Pin<&mut Self>, _: &mut Context<'_>, buf: &mut tokio::io::ReadBuf<'_>) -> Poll<std::io::Result<()>> {
        match self.0.read(buf.initialize_unfilled()) { Ok(n) => { buf.set_filled(n); Poll::Ready(Ok(())) } Err(e) => Poll::Ready(Err(e)) }
    }
}
impl<T: std::io::BufRead + Unpin> tokio::io::AsyncBufRead for SyncIo<T> {
    fn poll_fill_buf(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<std::io::Result<&[u8]>> { Poll::Ready(self.get_mut().0.fill_buf()) }
    fn consume(self: Pin<&mut Self>, amt: usize) { self.get_mut().0.consume(amt); }
}
impl<T: std::io::Seek + Unpin> tokio::io::AsyncSeek for SyncIo<T> {
    fn start_seek(self: Pin<&mut Self>, pos: std::io::SeekFrom) -> std::io::Result<()> { self.get_mut().0.seek(pos)?; Ok(()) }
    fn poll_complete(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<std::io::Result<u64>> { Poll::Ready(self.get_mut().0.stream_position()) }
}
impl<T: std::io::Write + Unpin> tokio::io::AsyncWrite for SyncIo<T> {
    fn poll_write(self: Pin<&mut Self>, _: &mut Context<'_>, buf: &[u8]) -> Poll<std::io::Result<usize>> { Poll::Ready(self.get_mut().0.write(buf)) }
    fn poll_flush(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<std::io::Result<()>> { Poll::Ready(self.get_mut().0.flush()) }
    fn poll_shutdown(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<std::io::Result<()>> { Poll::Ready(Ok(())) }
}

/// Collects the files under `base` into `(path, relative name)` pairs sorted by
/// name — the shape every packing flow here wants.
///
/// ONE implementation because the galgame containers all name entries relative
/// with `/` separators and want a stable order; a second copy is how two
/// formats end up disagreeing about which of `A.txt` / `a.txt` comes first.
/// A single file packs as itself, so a "pack this one file" flow works too.
pub fn collect_files(base: &Path) -> Result<Vec<(PathBuf, String)>, String> {
    let mut out = Vec::new();
    if base.is_file() {
        let name = base.file_name().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
        if name.is_empty() {
            return Err("empty filename".to_string());
        }
        out.push((base.to_path_buf(), name));
        return Ok(out);
    }
    let mut stack = vec![(base.to_path_buf(), String::new())];
    while let Some((dir, rel)) = stack.pop() {
        let mut entries: Vec<_> = std::fs::read_dir(&dir)
            .map_err(|e| format!("read_dir {}: {e}", dir.display()))?
            .collect::<Result<_, _>>()
            .map_err(|e| format!("read_dir {}: {e}", dir.display()))?;
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

pub fn json_escape(s: &str) -> String {
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

pub fn derive_dirs(paths: &[&str]) -> BTreeSet<String> {
    let mut dirs = BTreeSet::new();
    for path in paths {
        let parts: Vec<&str> = path.split('/').collect();
        for i in 1..parts.len() { dirs.insert(parts[..i].join("/")); }
    }
    dirs
}

pub fn safe_join(output: &str, archive_path: &str) -> Result<PathBuf, String> {
    let mut dest = Path::new(output).to_path_buf();
    let normalized = archive_path.replace('\\', "/");
    if normalized.starts_with('/') || normalized.contains('\0') {
        return Err(format!("unsafe archive path: {archive_path}"));
    }
    let mut pushed = false;

    for comp in normalized.split('/') {
        if comp.is_empty() || comp == "." {
            continue;
        }
        if comp == ".." || comp.contains(':') {
            return Err(format!("unsafe archive path: {archive_path}"));
        }
        dest.push(comp);
        pushed = true;
    }

    if !pushed {
        return Err("empty archive path".to_string());
    }

    Ok(dest)
}

pub fn extract_result_json(total: u32, success: u32, error: u32) -> String {
    format!(r#"{{"total":{},"success":{},"error":{}}}"#, total, success, error)
}

/// Allocates non-colliding on-disk paths for archive entries.
///
/// XP3/PFS indexes may carry several entries under the same name, and both
/// Android `/sdcard` (sdcardfs/FUSE) and FAT volumes are case-insensitive —
/// `Readme.txt` and `readme.txt` resolve to ONE physical file. Writing each
/// entry in turn to the same `File::create` dest is last-wins truncation:
/// data is silently destroyed while the result JSON still reports success.
/// The allocator case-folds its dedup key so case-only collisions are caught,
/// and renames later claimants to `stem (n).ext` (common unarchiver
/// convention) — nothing already written is ever overwritten. Scope is one
/// extraction run: re-extracting over a previous run overwrites, as usual.
pub struct DestAllocator {
    seen: HashSet<String>,
}

impl Default for DestAllocator {
    fn default() -> Self { Self::new() }
}

impl DestAllocator {
    pub fn new() -> Self { Self { seen: HashSet::new() } }

    /// Claims `dest` for one entry; returns the path to write (possibly a
    /// `stem (n).ext` variant when `dest` — or a case-fold of it — was
    /// already claimed by an earlier entry in this run).
    pub fn allocate(&mut self, dest: PathBuf) -> PathBuf {
        let key = dest.to_string_lossy().to_lowercase();
        if self.seen.insert(key) { return dest; }
        let parent = dest.parent().map(|p| p.to_path_buf()).unwrap_or_default();
        let stem = dest.file_stem().map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_else(|| "file".to_string());
        let ext = dest.extension().map(|s| format!(".{}", s.to_string_lossy()));
        for n in 1..u32::MAX {
            let name = match &ext {
                Some(e) => format!("{stem} ({n}){e}"),
                None => format!("{stem} ({n})"),
            };
            let cand = parent.join(name);
            let key = cand.to_string_lossy().to_lowercase();
            if self.seen.insert(key) { return cand; }
        }
        dest
    }
}

/// Byte-splits a file into `<path>.001/.002/...` parts of `part_size` bytes
/// (7-Zip `-v` semantics), then removes the original. `part_size == 0` is a
/// no-op returning 1. Returns the number of parts written.
pub fn split_volumes(path: &str, part_size: u64) -> Result<u64, String> {
    if part_size == 0 {
        return Ok(1);
    }
    let f = std::fs::File::open(path).map_err(|e| e.to_string())?;
    let mut reader = std::io::BufReader::new(f);
    let mut buf = [0u8; 256 * 1024];
    let mut idx: u64 = 0;
    let mut cur: Option<std::fs::File> = None;
    let mut written_in_part: u64 = 0;
    let mut any = false;
    loop {
        let n = reader.read(&mut buf).map_err(|e| e.to_string())?;
        if n == 0 {
            break;
        }
        any = true;
        let mut off = 0;
        while off < n {
            if cur.is_none() {
                idx += 1;
                let part_path = format!("{path}.{idx:03}");
                cur = Some(std::fs::File::create(&part_path).map_err(|e| e.to_string())?);
                written_in_part = 0;
            }
            let take = ((n - off) as u64).min(part_size - written_in_part);
            std::io::Write::write_all(cur.as_mut().unwrap(), &buf[off..off + take as usize])
                .map_err(|e| e.to_string())?;
            off += take as usize;
            written_in_part += take;
            if written_in_part == part_size {
                cur = None;
            }
        }
    }
    if !any {
        let part_path = format!("{path}.001");
        std::fs::File::create(&part_path).map_err(|e| e.to_string())?;
        idx = 1;
    }
    std::fs::remove_file(path).ok();
    Ok(idx)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write as _;

    /// The progress store is per-cdylib **static** state, so any two tests that
    /// touch it must not run concurrently — Cargo runs tests in parallel by
    /// default. `progress_total_adjust_saturates` (pre-existing) drives total to
    /// 0 via `adjust_total(i64::MIN)`, which made this module's own
    /// "bytes == total" precondition fail nondeterministically. Every test that
    /// reads or writes the store must take this lock.
    static PROGRESS_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
    fn progress_lock() -> std::sync::MutexGuard<'static, ()> {
        PROGRESS_LOCK.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// `clear_cancel` must zero EVERY counter, not just the cancel flag.
    ///
    /// This is the invariant behind "the bar showed a confident 100% and then
    /// froze": each format does real work before its own `reset(total)` (zstd
    /// `fs::read`s the whole archive, rar walks every member, tar decompresses
    /// the entire outer stream in pass 1), and the UI poll loop paints these
    /// statics throughout. If a finished operation's `bytes == total` survives
    /// into that window, the pre-scan phase renders as a fabricated 100%.
    ///
    /// `reset()` still preserves CANCEL on purpose (a cancel pressed during a
    /// pre-scan must survive into the extraction phase), so it cannot stand in
    /// for this — hence the separate assertion that CANCEL survives `reset`.
    #[test]
    fn clear_cancel_zeroes_progress_so_prescan_never_shows_stale_full() {
        let _guard = progress_lock();
        // Simulate a finished operation.
        extract_progress::reset(4096);
        extract_progress::set_name("previous.bin");
        extract_progress::set_file(4096);
        extract_progress::add_bytes(4096);
        assert_eq!(extract_progress::bytes(), extract_progress::total_bytes());
        assert!(extract_progress::bytes() > 0, "precondition: a finished op reads as 100%");

        // Start the next one.
        extract_progress::clear_cancel();

        assert_eq!(extract_progress::total_bytes(), 0, "total must not leak into the pre-scan window");
        assert_eq!(extract_progress::bytes(), 0, "bytes must not leak into the pre-scan window");
        assert_eq!(extract_progress::file_total(), 0);
        assert_eq!(extract_progress::file_bytes(), 0);
        assert_eq!(extract_progress::name(), "", "stale file name would label the new operation");
        // total == 0 is what makes ExtractProgress.kt render "preparing"
        // (it treats total <= 0 as indeterminate) instead of a wrong percentage.
    }

    /// `reset()` must NOT clear a cancel pressed during the pre-scan phase.
    /// Counter-zeroing moved to `clear_cancel()` precisely so this still holds.
    #[test]
    fn reset_preserves_cancel_pressed_during_prescan() {
        let _guard = progress_lock();
        extract_progress::clear_cancel();
        extract_progress::cancel();
        extract_progress::reset(100);
        assert!(extract_progress::cancelled(), "a pre-scan cancel must survive into the extraction phase");
        extract_progress::clear_cancel();
        assert!(!extract_progress::cancelled(), "clear_cancel must un-poison the next operation");
    }

    #[test]
    fn split_volumes_edge_cases() {
        let dir = std::env::temp_dir().join(format!("uu_common_split_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();

        // 1. part_size == 0 → no-op, original untouched
        let f = dir.join("a.bin");
        std::fs::write(&f, b"hello").unwrap();
        assert_eq!(split_volumes(f.to_str().unwrap(), 0).unwrap(), 1);
        assert!(f.exists());

        // 2. empty file → single empty part
        let e = dir.join("e.bin");
        std::fs::File::create(&e).unwrap();
        assert_eq!(split_volumes(e.to_str().unwrap(), 1024).unwrap(), 1);
        assert!(dir.join("e.bin.001").exists());
        assert_eq!(std::fs::metadata(dir.join("e.bin.001")).unwrap().len(), 0);

        // 3. exact multiple of part_size
        let m = dir.join("m.bin");
        std::fs::write(&m, vec![0xAB; 4096]).unwrap();
        let parts = split_volumes(m.to_str().unwrap(), 1024).unwrap();
        assert_eq!(parts, 4);
        for i in 1..=4 {
            let p = dir.join(format!("m.bin.{i:03}"));
            assert_eq!(std::fs::metadata(&p).unwrap().len(), 1024);
        }

        // 4. non-exact remainder → last part is smaller
        let n = dir.join("n.bin");
        std::fs::write(&n, vec![0xCD; 2500]).unwrap();
        let parts = split_volumes(n.to_str().unwrap(), 1024).unwrap();
        assert_eq!(parts, 3);
        assert_eq!(std::fs::metadata(dir.join("n.bin.001")).unwrap().len(), 1024);
        assert_eq!(std::fs::metadata(dir.join("n.bin.003")).unwrap().len(), 452);

        // 5. concatenation reconstructs the original bytes
        let mut joined = Vec::new();
        let mut i = 1u32;
        loop {
            let p = dir.join(format!("n.bin.{i:03}"));
            if !p.exists() { break; }
            joined.extend(std::fs::read(&p).unwrap());
            i += 1;
        }
        assert_eq!(joined, vec![0xCD; 2500]);

        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn bounded_writer_caps_at_limit() {
        // Exact fit is allowed.
        let exact: Vec<u8> = vec![1; 5];
        let mut w = BoundedWriter::new(Vec::new(), 5);
        assert_eq!(w.write(&exact).unwrap(), 5);
        assert!(w.flush().is_ok());

        // Overshoot is cut to the remaining budget on the current write, then
        // the next write errors — io::copy surfaces the error after the cap.
        let mut w = BoundedWriter::new(Vec::new(), 5);
        assert_eq!(w.write(&[1, 2, 3]).unwrap(), 3);
        assert_eq!(w.write(&[4, 5, 6, 7]).unwrap(), 2); // only 2 of the 4 fit
        let err = w.write(&[1]).unwrap_err();
        assert_eq!(err.kind(), std::io::ErrorKind::Other);

        // A write exactly at zero budget errors immediately.
        let mut w = BoundedWriter::new(Vec::new(), 2);
        w.write_all(&[9, 9]).unwrap();
        assert!(w.write_all(&[1]).is_err());

        // Zero limit rejects any write.
        let mut w = BoundedWriter::new(Vec::new(), 0);
        assert!(w.write(&[1]).is_err());
    }

    #[test]
    fn dest_allocator_renames_duplicates_and_case_collisions() {
        let mut da = DestAllocator::new();

        // First claimant keeps its name.
        let a = da.allocate(PathBuf::from("/out/sub/Readme.txt"));
        assert_eq!(a, PathBuf::from("/out/sub/Readme.txt"));

        // Case-insensitive volume: `readme.txt` would physically overwrite
        // `Readme.txt` — must be renamed (keeping its own name), never
        // returned as-is.
        let b = da.allocate(PathBuf::from("/out/sub/readme.txt"));
        assert!(b.to_string_lossy().ends_with("readme (1).txt"), "got {b:?}");

        // Exact duplicate also renames, incrementing past the taken slot.
        let c = da.allocate(PathBuf::from("/out/sub/Readme.txt"));
        assert!(c.to_string_lossy().ends_with("Readme (2).txt"), "got {c:?}");

        // Extension-less names rename without a dangling dot.
        let d1 = da.allocate(PathBuf::from("/out/blob"));
        let d2 = da.allocate(PathBuf::from("/out/blob"));
        assert_eq!(d1, PathBuf::from("/out/blob"));
        assert!(d2.to_string_lossy().ends_with("blob (1)"), "got {d2:?}");

        // Distinct names are untouched.
        let e = da.allocate(PathBuf::from("/out/other.txt"));
        assert_eq!(e, PathBuf::from("/out/other.txt"));
    }

    #[test]
    fn progress_total_adjust_saturates() {
        let _guard = progress_lock();
        // adjust_total is generated per-format by the progress_store! macro;
        // exercise the extract instance in place (it is otherwise unused in
        // this crate's tests). The store is per-cdylib static state, so these
        // assertions only rely on deltas this test itself applies.
        extract_progress::adjust_total(5);
        let up = extract_progress::total_bytes();
        extract_progress::adjust_total(-3);
        let down = extract_progress::total_bytes();
        assert_eq!(down, up - 3, "positive delta adds, negative delta subtracts");

        // A negative delta larger than the current total must saturate at 0
        // (fetch_sub would panic on underflow in debug builds).
        extract_progress::adjust_total(i64::MIN);
        assert_eq!(extract_progress::total_bytes(), 0);
    }
}
