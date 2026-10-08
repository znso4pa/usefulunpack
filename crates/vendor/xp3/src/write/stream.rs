use core::{
    pin::Pin,
    task::{Context, Poll, ready},
};
use std::io;

use async_compression::tokio::write::ZlibEncoder;
use pin_project::pin_project;
use tokio::io::AsyncWrite;

/// A byte transform applied to each chunk on its way into the archive, given
/// the running offset of the data written so far.
///
/// Length-preserving by contract: the stream keeps one counter for both the
/// written and the original size, which is what the `size == archive_size`
/// invariant of an un-packed XP3 entry needs. `xp3-core` uses it to write
/// cxdec-encrypted entries — the caller writes plaintext (so the ADLR checksum
/// stays the plaintext's, which is the cipher's key seed) and the transform
/// encrypts each chunk in place.
pub struct TransformFn(Box<dyn FnMut(u64, &mut [u8]) -> io::Result<()> + Send>);

impl TransformFn {
    pub fn new(f: impl FnMut(u64, &mut [u8]) -> io::Result<()> + Send + 'static) -> Self {
        Self(Box::new(f))
    }

    fn apply(&mut self, offset: u64, data: &mut [u8]) -> io::Result<()> {
        (self.0)(offset, data)
    }
}

impl core::fmt::Debug for TransformFn {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        f.write_str("TransformFn(..)")
    }
}

#[derive(Debug)]
#[pin_project(project = XP3StreamProj)]
pub enum XP3FileStream<T> {
    Compressed(#[pin] ZlibEncoder<T>),
    Raw {
        written: u64,
        #[pin]
        stream: T,
    },
    Transformed {
        written: u64,
        #[pin]
        stream: T,
        transform: TransformFn,
    },
}

impl<T: AsyncWrite> XP3FileStream<T> {
    pub fn written(&self) -> u64 {
        match *self {
            XP3FileStream::Compressed(ref stream) => stream.total_out(),
            XP3FileStream::Raw { written, .. } => written,
            XP3FileStream::Transformed { written, .. } => written,
        }
    }

    pub fn written_original(&self) -> u64 {
        match *self {
            XP3FileStream::Compressed(ref stream) => stream.total_in(),
            XP3FileStream::Raw { written, .. } => written,
            // The transform is length-preserving, so the original size equals
            // the stored size.
            XP3FileStream::Transformed { written, .. } => written,
        }
    }
}

impl<T: AsyncWrite> AsyncWrite for XP3FileStream<T> {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        match self.project() {
            XP3StreamProj::Compressed(stream) => stream.poll_write(cx, buf),
            XP3StreamProj::Raw { stream, written } => {
                let written_size = ready!(stream.poll_write(cx, buf))?;
                *written += written_size as u64;
                Poll::Ready(Ok(written_size))
            }
            XP3StreamProj::Transformed {
                stream,
                written,
                transform,
            } => {
                // Transform a copy: on a partial write the untransformed tail
                // must not be left transformed, since the next call transforms
                // from the new offset.
                let mut tmp = buf.to_vec();
                transform.apply(*written, &mut tmp)?;
                let written_size = ready!(stream.poll_write(cx, &tmp))?;
                *written += written_size as u64;
                Poll::Ready(Ok(written_size))
            }
        }
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        match self.project() {
            XP3StreamProj::Compressed(stream) => stream.poll_flush(cx),
            XP3StreamProj::Raw { stream, .. } => stream.poll_flush(cx),
            XP3StreamProj::Transformed { stream, .. } => stream.poll_flush(cx),
        }
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        match self.project() {
            XP3StreamProj::Compressed(stream) => stream.poll_shutdown(cx),
            XP3StreamProj::Raw { stream, .. } => stream.poll_shutdown(cx),
            XP3StreamProj::Transformed { stream, .. } => stream.poll_shutdown(cx),
        }
    }
}
