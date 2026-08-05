use jni::JNIEnv;
use jni::objects::JString;
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Mutex;

/// Byte-based progress stores. Each cdylib statically links this crate, so the
/// statics below are independent per format library.
macro_rules! progress_store {
    ($name:ident) => {
        pub mod $name {
            use super::*;

            static BYTES: AtomicU64 = AtomicU64::new(0);
            static TOTAL: AtomicU64 = AtomicU64::new(0);
            static FNAME: Mutex<String> = Mutex::new(String::new());
            static CANCEL: AtomicBool = AtomicBool::new(false);

            pub fn reset(total_bytes: u64) {
                BYTES.store(0, Ordering::SeqCst);
                TOTAL.store(total_bytes, Ordering::SeqCst);
                CANCEL.store(false, Ordering::SeqCst);
                *FNAME.lock().unwrap() = String::new();
            }

            pub fn add_bytes(n: u64) { BYTES.fetch_add(n, Ordering::SeqCst); }

            pub fn set_name(name: &str) { *FNAME.lock().unwrap() = name.to_string(); }

            pub fn cancel() { CANCEL.store(true, Ordering::SeqCst); }
            pub fn cancelled() -> bool { CANCEL.load(Ordering::SeqCst) }
            pub fn bytes() -> u64 { BYTES.load(Ordering::SeqCst) }
            pub fn total_bytes() -> u64 { TOTAL.load(Ordering::SeqCst) }
            pub fn name() -> String { FNAME.lock().unwrap().clone() }
        }
    }
}

progress_store!(extract_progress);
progress_store!(compress_progress);

/// Wraps a `Write` and accumulates written bytes into a progress store.
pub struct ProgressWriter<W> {
    inner: W,
    sink: fn(u64),
}

impl<W> ProgressWriter<W> {
    pub fn extract(inner: W) -> Self { Self { inner, sink: extract_progress::add_bytes } }
    pub fn compress(inner: W) -> Self { Self { inner, sink: compress_progress::add_bytes } }
}

impl<W: std::io::Write> std::io::Write for ProgressWriter<W> {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        let n = self.inner.write(buf)?;
        (self.sink)(n as u64);
        Ok(n)
    }
    fn flush(&mut self) -> std::io::Result<()> { self.inner.flush() }
}

/// Wraps a `Read` and accumulates read bytes into a progress store.
pub struct ProgressReader<R> {
    inner: R,
    sink: fn(u64),
}

impl<R> ProgressReader<R> {
    pub fn extract(inner: R) -> Self { Self { inner, sink: extract_progress::add_bytes } }
    pub fn compress(inner: R) -> Self { Self { inner, sink: compress_progress::add_bytes } }
}

impl<R: std::io::Read> std::io::Read for ProgressReader<R> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        let n = self.inner.read(buf)?;
        (self.sink)(n as u64);
        Ok(n)
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
    loop {
        match fut.as_mut().poll(&mut cx) {
            Poll::Ready(v) => return v,
            Poll::Pending => std::hint::spin_loop(),
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
