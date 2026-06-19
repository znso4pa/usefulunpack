// ╔══════════════════════════════════════════════════════════════╗
// ║  UsefulUnpack — znso4pa — xp3-tool pattern extract           ║
// ╚══════════════════════════════════════════════════════════════╝

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, JNI_TRUE, JNI_FALSE};
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
