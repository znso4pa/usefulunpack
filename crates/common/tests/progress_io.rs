use archive_common::{ProgressReader, ProgressWriter};
use archive_common::{compress_progress, extract_progress};
use std::io::{Read, Write};
use std::sync::Mutex;

static LOCK: Mutex<()> = Mutex::new(());

#[test]
fn progress_writer_aborts_on_cancel() {
    let _g = LOCK.lock().unwrap();
    extract_progress::reset(1024);
    let mut out = Vec::new();
    {
        let mut w = ProgressWriter::extract(&mut out);
        w.write_all(&[1u8; 100]).unwrap();
    }
    assert_eq!(extract_progress::bytes(), 100);

    extract_progress::cancel();
    let mut w = ProgressWriter::extract(&mut out);
    let err = w.write(&[1u8; 16]).unwrap_err();
    assert_eq!(err.kind(), std::io::ErrorKind::Interrupted);

    // reset clears cancel → writes work again
    extract_progress::reset(1024);
    let mut w = ProgressWriter::extract(&mut out);
    w.write_all(&[2u8; 32]).unwrap();
    assert_eq!(extract_progress::bytes(), 32);
}

#[test]
fn progress_reader_aborts_on_compress_cancel() {
    let _g = LOCK.lock().unwrap();
    compress_progress::reset(1024);
    let src = vec![7u8; 512];
    let mut r = ProgressReader::compress(&src[..]);
    let mut buf = [0u8; 64];
    let n = r.read(&mut buf).unwrap();
    assert_eq!(n, 64);
    assert_eq!(compress_progress::bytes(), 64);

    compress_progress::cancel();
    let err = r.read(&mut buf).unwrap_err();
    assert_eq!(err.kind(), std::io::ErrorKind::Interrupted);
}
