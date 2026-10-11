//! Sun/NeXT `.au` / `.snd`。
//!
//! 容器极简：24 字节大端头 + 裸样本流，没有 chunk、没有索引、没有元数据块。
//! 头里的 `data offset` 允许后面挂一段注释，所以数据真正开始的位置要以它为准
//! （而不是写死的 24）—— 真文件里见过 28/32，注释是「Created by ...」之类。
//!
//! ```text
//!   0  magic        ".snd"      (2E 73 6E 64)
//!   4  data offset  u32 BE      ≥ 24
//!   8  data size    u32 BE      0xFFFFFFFF = 未知（要按文件长度推）
//!  12  encoding     u32 BE      见下面 ENC_*
//!  16  sample rate  u32 BE
//!  20  channels     u32 BE
//!  24  ...注释（按 data offset 对齐）... 然后是样本
//! ```
//!
//! **全部大端**，包括 PCM 样本本身 —— 这是它和 WAV 最容易搞混的一点：WAV 是小端，
//! `.au` 是大端。写反了文件照样能打开，只是整段音频变成噪声。
//!
//! 支持的编码：µ-law(1) / 8 位线性(2) / 16 位(3) / 24 位(4) / 32 位(5) /
//! float32(6) / float64(7) / A-law(27)。µ-law 与 A-law 是电话/老 Unix 素材的常见
//! 形态，解码表各十行，没有理由不支持。写方向只写线性 PCM（2/3/4/5）——
//! 把高保真素材压成 µ-law 是有损的，不是「转换格式」该悄悄做的事。

use std::io::Read;

use crate::counted::{open_counted, CountedWriter};
use crate::pcm::Pcm;
use archive_common::extract_progress;

const MAGIC: &[u8; 4] = b".snd";
const HEADER: usize = 24;
const READ_CHUNK: usize = 64 * 1024;
/// `data size` 写这个值表示「到文件尾为止」。真文件里很常见。
const SIZE_UNKNOWN: u32 = 0xFFFF_FFFF;
/// 声道数上限。`.au` 头里的声道数是 u32，没有理由相信一个声称 40 亿声道的文件。
const MAX_CHANNELS: u32 = 8;

const ENC_ULAW: u32 = 1;
const ENC_PCM8: u32 = 2;
const ENC_PCM16: u32 = 3;
const ENC_PCM24: u32 = 4;
const ENC_PCM32: u32 = 5;
const ENC_FLOAT32: u32 = 6;
const ENC_FLOAT64: u32 = 7;
const ENC_ALAW: u32 = 27;

fn be32(b: &[u8]) -> u32 {
    u32::from_be_bytes([b[0], b[1], b[2], b[3]])
}

/// 每个样本的字节数。
fn bytes_per_sample(enc: u32) -> Option<usize> {
    match enc {
        ENC_ULAW | ENC_PCM8 | ENC_ALAW => Some(1),
        ENC_PCM16 => Some(2),
        ENC_PCM24 => Some(3),
        ENC_PCM32 | ENC_FLOAT32 => Some(4),
        ENC_FLOAT64 => Some(8),
        _ => None,
    }
}

/// 一个字节（大端已还原的整数值）→ `[-1, 1]` 的 f32。
fn sample_to_f32(enc: u32, raw: &[u8]) -> f32 {
    match enc {
        ENC_PCM8 => raw[0] as i8 as f32 / 128.0,
        ENC_PCM16 => i16::from_be_bytes([raw[0], raw[1]]) as f32 / 32768.0,
        ENC_PCM24 => {
            // 三字节大端 → i32（补符号位）。
            let v = ((raw[0] as i32) << 24 | (raw[1] as i32) << 16 | (raw[2] as i32) << 8) >> 8;
            v as f32 / 8_388_608.0
        }
        ENC_PCM32 => i32::from_be_bytes([raw[0], raw[1], raw[2], raw[3]]) as f32 / 2_147_483_648.0,
        ENC_FLOAT32 => f32::from_be_bytes([raw[0], raw[1], raw[2], raw[3]]),
        ENC_FLOAT64 => {
            f64::from_be_bytes([raw[0], raw[1], raw[2], raw[3], raw[4], raw[5], raw[6], raw[7]]) as f32
        }
        ENC_ULAW => ulaw_to_i16(raw[0]) as f32 / 32768.0,
        ENC_ALAW => alaw_to_i16(raw[0]) as f32 / 32768.0,
        _ => 0.0,
    }
}

/// µ-law → i16。Sun 的经典算法（先取反，再按段位左移）。
fn ulaw_to_i16(u: u8) -> i16 {
    let u = !u;
    let t = (((u & 0x0F) as i32) << 3) + 0x84;
    let t = t << ((u & 0x70) >> 4);
    if u & 0x80 != 0 {
        (0x84 - t) as i16
    } else {
        (t - 0x84) as i16
    }
}

/// A-law → i16。
fn alaw_to_i16(a: u8) -> i16 {
    let a = a ^ 0x55;
    let t = ((a & 0x0F) as i32) << 4;
    let seg = ((a & 0x70) >> 4) as i32;
    let t = if seg == 0 { t + 8 } else { (t + 0x108) << (seg - 1) };
    if a & 0x80 != 0 {
        t as i16
    } else {
        (-t) as i16
    }
}

/// 读一个 `.au`。失败时返回人能看懂的原因，不做任何猜测性回退。
pub fn decode(path: &str) -> Result<Pcm, String> {
    let (mut f, file_len) = open_counted(path)?;

    let mut hdr = [0u8; HEADER];
    f.read_exact(&mut hdr).map_err(|e| format!("au header: {e}"))?;
    if &hdr[0..4] != MAGIC {
        return Err("not a Sun/NeXT .au file (bad magic)".to_string());
    }

    let data_off = be32(&hdr[4..8]) as u64;
    let data_size = be32(&hdr[8..12]);
    let enc = be32(&hdr[12..16]);
    let rate = be32(&hdr[16..20]);
    let channels = be32(&hdr[20..24]);

    if rate == 0 {
        return Err("au declares sample rate 0".to_string());
    }
    if channels == 0 || channels > MAX_CHANNELS {
        return Err(format!("au declares {channels} channels"));
    }
    let bps = bytes_per_sample(enc)
        .ok_or_else(|| format!("unsupported au encoding {enc}"))?;
    if data_off < HEADER as u64 {
        return Err(format!("au data offset {data_off} points inside the header"));
    }
    // 注释段：读完就丢，但要把位置对齐到 data_off。
    let skip = data_off - HEADER as u64;
    if skip > 0 {
        let mut sink = std::io::sink();
        std::io::copy(&mut f.by_ref().take(skip), &mut sink)
            .map_err(|e| format!("au skip annotation: {e}"))?;
    }

    // 数据长度：`0xFFFFFFFF` 表示「到文件尾」。除此之外还要按文件真实长度夹一次 ——
    // 一个声称 4 GB 的头不该让我们去分配 4 GB。
    let avail = file_len.saturating_sub(data_off);
    let want = if data_size == SIZE_UNKNOWN || data_size == 0 {
        avail
    } else {
        (data_size as u64).min(avail)
    };
    // 尾部不足一个整帧的残料丢掉：留着会让交错样本整体错位。
    let frame_bytes = bps * channels as usize;
    let want = want / frame_bytes as u64 * frame_bytes as u64;

    let ch = channels as usize;
    let mut samples: Vec<f32> = Vec::with_capacity((want as usize / bps).max(1));
    let mut buf = vec![0u8; READ_CHUNK];
    let mut carry: Vec<u8> = Vec::with_capacity(bps);
    let mut left = want;
    while left > 0 {
        if extract_progress::cancelled() {
            return Err("cancelled".to_string());
        }
        let take = (left as usize).min(READ_CHUNK);
        let n = f.read(&mut buf[..take]).map_err(|e| format!("au read: {e}"))?;
        if n == 0 {
            break;
        }
        left -= n as u64;

        // 把上次的残字节拼回来，按 bps 切整样本。
        let mut data: &[u8] = &buf[..n];
        if !carry.is_empty() {
            carry.extend_from_slice(data);
            let usable = carry.len() / bps * bps;
            for s in carry[..usable].chunks_exact(bps) {
                samples.push(sample_to_f32(enc, s));
            }
            carry.drain(..usable);
            data = &[];
        }
        let usable = data.len() / bps * bps;
        for s in data[..usable].chunks_exact(bps) {
            samples.push(sample_to_f32(enc, s));
        }
        carry.extend_from_slice(&data[usable..]);
    }
    // 尾巴上不够一个样本的字节丢弃（上面已按整帧裁过，这里只是保险）。
    let whole = samples.len() / ch * ch;
    samples.truncate(whole);

    Ok(Pcm::new(rate, channels as u16, samples))
}

/// 写一个 `.au`。`depth` 只接受 8 / 16 / 24 / 32（线性 PCM，大端）。
pub fn encode(pcm: &Pcm, path: &str, depth: u16) -> Result<(), String> {
    let (enc, bps) = match depth {
        8 => (ENC_PCM8, 1usize),
        16 => (ENC_PCM16, 2),
        24 => (ENC_PCM24, 3),
        32 => (ENC_PCM32, 4),
        other => return Err(format!("unsupported au bit depth {other}")),
    };
    let ch = pcm.channels.max(1) as usize;
    let frames = pcm.frames();
    let data_len = (frames * ch * bps) as u32;

    let file = std::fs::File::create(path).map_err(|e| format!("create {path}: {e}"))?;
    let mut w = CountedWriter::new(std::io::BufWriter::with_capacity(256 * 1024, file));

    let mut hdr = Vec::with_capacity(HEADER);
    hdr.extend_from_slice(MAGIC);
    hdr.extend_from_slice(&(HEADER as u32).to_be_bytes());
    hdr.extend_from_slice(&data_len.to_be_bytes());
    hdr.extend_from_slice(&enc.to_be_bytes());
    hdr.extend_from_slice(&pcm.rate.to_be_bytes());
    hdr.extend_from_slice(&(pcm.channels as u32).to_be_bytes());
    std::io::Write::write_all(&mut w, &hdr).map_err(|e| format!("au header: {e}"))?;

    let mut out = Vec::with_capacity(READ_CHUNK);
    for &s in &pcm.samples {
        if extract_progress::cancelled() {
            return Err("cancelled".to_string());
        }
        let v = crate::encode::clamp(s);
        match bps {
            1 => out.push((v * 127.0).round() as i8 as u8),
            2 => out.extend_from_slice(&((v * 32767.0).round() as i16).to_be_bytes()),
            3 => {
                let n = (v * 8_388_607.0).round() as i32;
                out.extend_from_slice(&n.to_be_bytes()[1..4]);
            }
            _ => out.extend_from_slice(&((v * 2_147_483_647.0).round() as i32).to_be_bytes()),
        }
        if out.len() >= READ_CHUNK {
            std::io::Write::write_all(&mut w, &out).map_err(|e| format!("au write: {e}"))?;
            out.clear();
        }
    }
    std::io::Write::write_all(&mut w, &out).map_err(|e| format!("au write: {e}"))?;
    std::io::Write::flush(&mut w).map_err(|e| format!("au flush: {e}"))?;
    Ok(())
}

/// 输出字节数（进度条的分母）。头固定 24 字节。
pub fn estimated_bytes(pcm: &Pcm, depth: u16) -> u64 {
    let bps = (depth as u64 / 8).max(1);
    HEADER as u64 + pcm.frames() as u64 * pcm.channels.max(1) as u64 * bps
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testutil::{sine, tmpdir};

    /// 每个位深走一遍「写 → 读」，误差在量化步长之内。
    ///
    /// 这条同时钉住**大端**：把 `to_be_bytes` 改成 `to_le_bytes`，8 位那一档仍然
    /// 通过（单字节没有字节序），16/24/32 会立刻变成噪声并断言失败。
    #[test]
    fn round_trip_every_depth() {
        let dir = tmpdir("au");
        let src = sine(44_100, 2, 0.05, 440.0);
        for (depth, tol) in [(8u16, 1.0 / 128.0), (16, 1.0 / 32768.0), (24, 1.0 / 8_388_608.0), (32, 1e-6)] {
            let p = dir.join(format!("d{depth}.au"));
            encode(&src, p.to_str().unwrap(), depth).expect("encode au");
            let back = decode(p.to_str().unwrap()).expect("decode au");
            assert_eq!(back.rate, src.rate, "depth {depth}");
            assert_eq!(back.channels, src.channels, "depth {depth}");
            assert_eq!(back.frames(), src.frames(), "depth {depth}: frame count");
            let worst = src
                .samples
                .iter()
                .zip(back.samples.iter())
                .fold(0.0f32, |m, (a, b)| m.max((a - b).abs()));
            assert!(worst <= tol, "depth {depth}: worst error {worst} > {tol}");
        }
        std::fs::remove_dir_all(&dir).ok();
    }

    /// `data offset > 24` 时注释段要被跳过 —— 按写死的 24 读会把注释当样本，
    /// 整段音频前移若干个样本（听上去是开头一小段噪声）。
    #[test]
    fn annotation_is_skipped_via_the_data_offset() {
        let dir = tmpdir("auann");
        let src = sine(8000, 1, 0.02, 440.0);
        let p = dir.join("ann.au");
        encode(&src, p.to_str().unwrap(), 16).unwrap();

        // 手工把 data offset 从 24 改成 32，并插入 8 字节注释。
        let raw = std::fs::read(&p).unwrap();
        let mut out = Vec::new();
        out.extend_from_slice(&raw[0..4]);
        out.extend_from_slice(&32u32.to_be_bytes());
        out.extend_from_slice(&raw[8..24]);
        out.extend_from_slice(b"Comment\n"); // 8 字节
        out.extend_from_slice(&raw[24..]);
        std::fs::write(&p, &out).unwrap();

        let back = decode(p.to_str().unwrap()).unwrap();
        assert_eq!(back.frames(), src.frames(), "注释不该被当成样本");
        let worst = src
            .samples
            .iter()
            .zip(back.samples.iter())
            .fold(0.0f32, |m, (a, b)| m.max((a - b).abs()));
        assert!(worst <= 1.0 / 32768.0, "worst {worst}");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// µ-law / A-law 的解码表要真的还原出波形，而不是退化成一条直线。
    #[test]
    fn companded_encodings_produce_a_signal() {
        // µ-law 的静音码是 0xFF，解出来**精确**是 0（它的算法里带一个偏置，
        // 正好抵消）。
        assert_eq!(ulaw_to_i16(0xFF), 0);
        // A-law **没有精确的 0**：最小幅度是 ±8，静音码 0xD5 / 0x55 分别解出
        // +8 / -8。别把这条写成 `== 0` —— 那是 µ-law 的性质，不是 A-law 的。
        assert_eq!(alaw_to_i16(0xD5), 8);
        assert_eq!(alaw_to_i16(0x55), -8);
        // 满幅码不该是 0。两个编码的「满幅」码值**不一样**：
        // µ-law 的极性是最高位，0x00/0x80 就是两个极值；
        // A-law 的极性也在最高位，但段位是「异或 0x55 之后」的，所以极值是
        // 0xAA / 0x2A。拿 0x80 去测 A-law 只会得到一个中段的电平。
        assert!(ulaw_to_i16(0x00).abs() > 30000);
        assert!(ulaw_to_i16(0x80).abs() > 30000);
        assert_eq!(alaw_to_i16(0xAA), 32256);
        assert_eq!(alaw_to_i16(0x2A), -32256);
        // 幅度随码值单调不增 —— 但要**分两个半区**查：低 7 位是幅度、最高位是
        // 符号，所以 0x7F 是 0 而 0x80 是 +32124，跨过符号位那一步幅度是跳回去
        // 的。拿有符号值或跨半区去比都会得到「不单调」的假结论。
        for half in [0x00u8, 0x80u8] {
            let mut prev = i32::MAX;
            for c in half..=(half | 0x7F) {
                let v = (ulaw_to_i16(c) as i32).abs();
                assert!(v <= prev, "µ-law 在 {c:#04x} 处幅度不单调：{prev} → {v}");
                prev = v;
            }
            // 半区的两端：起点是满幅，终点是 0。
            assert_eq!(ulaw_to_i16(half | 0x7F), 0, "{:#04x} 应该是半区里的零", half | 0x7F);
            assert!((ulaw_to_i16(half) as i32).abs() > 30000, "{half:#04x} 应该是满幅");
        }
    }

    #[test]
    fn bad_magic_is_rejected() {
        let dir = tmpdir("aubad");
        let p = dir.join("x.au");
        std::fs::write(&p, vec![0u8; 64]).unwrap();
        let e = decode(p.to_str().unwrap()).unwrap_err();
        assert!(e.contains("magic"), "got {e}");
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn unknown_encoding_is_named_in_the_error() {
        let dir = tmpdir("auenc");
        let p = dir.join("x.au");
        let mut h = Vec::new();
        h.extend_from_slice(MAGIC);
        h.extend_from_slice(&24u32.to_be_bytes());
        h.extend_from_slice(&0u32.to_be_bytes());
        h.extend_from_slice(&99u32.to_be_bytes()); // 不存在的编码
        h.extend_from_slice(&44_100u32.to_be_bytes());
        h.extend_from_slice(&2u32.to_be_bytes());
        h.extend_from_slice(&[0u8; 64]);
        std::fs::write(&p, &h).unwrap();
        let e = decode(p.to_str().unwrap()).unwrap_err();
        assert!(e.contains("99"), "错误消息要带上那个编码号，got {e}");
        std::fs::remove_dir_all(&dir).ok();
    }
}
