//! 带进度的文件读写包装。
//!
//! 为什么不用 `archive_common::ProgressReader`：它只实现 `Read`(+`BufRead`)，
//! 而 lewton 的 Ogg 读入器要 `Read + Seek`。本仓的转换全是「输入读一遍」，
//! 与其给 `ProgressReader` 补一个 `Seek`（那个类型是全仓共用的，改它要重新验证
//! 每个解包器），不如在这里放一个只给音频用的窄接口。
//!
//! 计数用的是**流位置**而不是「累计读到的字节」：`lewton` 的 `PacketReader` 在链式
//! Ogg 上会回退 `seek`，累计计数会把同一段字节算两遍，进度条于是能冲过 100%。

use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

use archive_common::extract_progress;

/// 包住 `File`，把「已经走过的最大位置」计进 `extract_progress`。
pub struct CountedFile {
    inner: File,
    /// 当前流位置（自己维护，省掉每次读都 lseek 一次）。
    pos: u64,
    /// 已经报给进度条的字节数 —— 单调不减。
    reported: u64,
}

impl CountedFile {
    pub fn open(path: &str) -> Result<Self, String> {
        let inner = File::open(path).map_err(|e| format!("open {path}: {e}"))?;
        Ok(Self { inner, pos: 0, reported: 0 })
    }

    /// 文件长度 —— 进度条的分母。
    pub fn len(&self) -> u64 {
        self.inner.metadata().map(|m| m.len()).unwrap_or(0)
    }

    fn report(&mut self) {
        if self.pos > self.reported {
            extract_progress::add_bytes(self.pos - self.reported);
            self.reported = self.pos;
        }
    }
}

impl Read for CountedFile {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        let n = self.inner.read(buf)?;
        self.pos += n as u64;
        self.report();
        Ok(n)
    }
}

impl Seek for CountedFile {
    fn seek(&mut self, from: SeekFrom) -> std::io::Result<u64> {
        let p = self.inner.seek(from)?;
        // 只挪位置、不改计数：回退 seek 之后重读同一段不该再加一遍。
        self.pos = p;
        Ok(p)
    }
}

/// 打开一个只用于读的 `CountedFile`，顺便返回它的长度。
pub fn open_counted(path: &str) -> Result<(CountedFile, u64), String> {
    let f = CountedFile::open(path)?;
    let n = f.len();
    Ok((f, n))
}

/// 输出侧的计数写入器：每个成功写出的字节计进 `extract_progress`，并在取消时
/// 立刻以错误中断写入。
///
/// 不自己留一份字节计数：进度条读的就是 `extract_progress`，再存一份只会有
/// 「本地计数和进度条不一致」这种问题，而没有任何用途。
pub struct CountedWriter<W> {
    inner: W,
}

impl<W> CountedWriter<W> {
    pub fn new(inner: W) -> Self {
        Self { inner }
    }

    pub fn into_inner(self) -> W {
        self.inner
    }
}

impl<W: std::io::Write> std::io::Write for CountedWriter<W> {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        if extract_progress::cancelled() {
            // `Other` 而不是 `Interrupted`：std 的 `write_all` 会对 `Interrupted`
            // 无限重试，取消就变成了 100% CPU 空转。
            return Err(std::io::Error::new(std::io::ErrorKind::Other, "cancelled"));
        }
        let n = self.inner.write(buf)?;
        extract_progress::add_bytes(n as u64);
        Ok(n)
    }

    fn flush(&mut self) -> std::io::Result<()> {
        self.inner.flush()
    }
}

/// `Seek` 必须转发：`hound::WavWriter` 收尾时会 seek 回头部回填 RIFF/data 长度，
/// 只实现 `Write` 的包装器会让 `WavWriter::new` 直接不满足 trait bound。
/// seek 本身不计数 —— 它不是产出数据，回填的那 8 个字节早已在写入时数过。
impl<W: std::io::Seek> std::io::Seek for CountedWriter<W> {
    fn seek(&mut self, from: SeekFrom) -> std::io::Result<u64> {
        self.inner.seek(from)
    }
}

/// 文件名的最后一段（给进度条显示）。
pub fn file_name_of(path: &str) -> String {
    Path::new(path)
        .file_name()
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_else(|| path.to_string())
}

/// 小写扩展名（不含点）。没有扩展名时返回空串。
pub fn ext_of(path: &str) -> String {
    Path::new(path)
        .extension()
        .map(|e| e.to_string_lossy().to_ascii_lowercase())
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write as _;

    fn tmpdir(tag: &str) -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("uu_audio_io_{}_{}", std::process::id(), tag));
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    /// 进度计数只在**前进**时增长：回退 seek 后重读同一段不能再计一遍。
    /// 累计计数（而不是看流位置）就会在这里把进度冲到 200%。
    #[test]
    fn seek_backwards_does_not_double_count() {
        let dir = tmpdir("seek");
        let p = dir.join("a.bin");
        std::fs::write(&p, vec![7u8; 4096]).unwrap();

        let mut f = CountedFile::open(p.to_str().unwrap()).unwrap();
        let mut buf = [0u8; 4096];
        f.read_exact(&mut buf).unwrap();
        assert_eq!(f.reported, 4096);

        f.seek(SeekFrom::Start(0)).unwrap();
        f.read_exact(&mut buf).unwrap();
        assert_eq!(f.reported, 4096, "重读同一段不能再计一次");

        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn counted_writer_forwards_and_flushes() {
        let mut w = CountedWriter::new(Vec::new());
        w.write_all(&[1, 2, 3]).unwrap();
        w.write_all(&[4]).unwrap();
        w.flush().unwrap();
        assert_eq!(w.into_inner(), vec![1, 2, 3, 4]);
    }

    #[test]
    fn ext_and_name_helpers() {
        assert_eq!(ext_of("/a/b/Song.MP3"), "mp3");
        assert_eq!(ext_of("/a/b/noext"), "");
        assert_eq!(file_name_of("/a/b/Song.mp3"), "Song.mp3");
    }
}
