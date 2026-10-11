//! AIFF / AIFF-C（`.aif` / `.aiff` / `.aifc`）。
//!
//! IFF 家族：`FORM` 头 + 一串 chunk，**全部大端**（和 `.au` 一样，和 WAV 相反）。
//!
//! ```text
//!   FORM <size> AIFF|AIFC
//!     COMM <size>  channels:i16  frames:u32  sampleSize:i16  rate:ext80
//!                  [AIFC 还要: compressionType:4  compressionName:pstring]
//!     SSND <size>  offset:u32  blockSize:u32  然后是样本（offset 个字节的静音在前）
//!     FVER <size>  (只有 AIFC)
//! ```
//!
//! chunk 长度为奇数时后面补一个字节，遍历时必须按**补齐后**的长度走 ——
//! 少走一个字节，后面所有 chunk 的 id 都是错的。
//!
//! ## 80 位扩展浮点
//!
//! `COMM` 里的采样率是 SANE 的 extended 格式（10 字节）：符号 1 位 + 指数 15 位
//! （偏置 16383）+ **显式整数位的** 64 位尾数。即
//! `value = mantissa × 2^(exponent − 16383 − 63)`，其中 mantissa ∈ [2^63, 2^64)。
//! 44100 就是 `40 0E AC 44 00 …`（0xAC44 << 48 = 44100 × 2^48）。
//! 按普通 IEEE double 去读会得到 44100 × 2^48 那种荒谬的值，然后重采样器会
//! 试图分配几 TB 的内存。
//!
//! ## 支持的形态
//!
//! - `AIFF`：大端线性 PCM，8 / 16 / 24 / 32 位。8 位是**有符号**的。
//! - `AIFC`：`NONE`（大端 PCM）与 `sowt`（小端 16 位 PCM，Mac 上极常见）。
//!   别的压缩类型（`ima4` / `ulaw` / `fl32` …）**按名字报错**，不猜。
//! - 写方向只写普通 `AIFF`（大端线性 PCM，8 / 16 / 24 / 32 位），不写 AIFC ——
//!   没有一种需要 AIFC 的编码是「无损且通用」的。

use std::io::{Read, SeekFrom};

use crate::counted::{open_counted, CountedWriter};
use crate::pcm::Pcm;
use archive_common::extract_progress;

const READ_CHUNK: usize = 64 * 1024;
/// 声道数上限（同 `.au`：头里的数是 u32，没有理由信一个声称 40 亿声道的文件）。
const MAX_CHANNELS: u16 = 8;

fn be16(b: &[u8]) -> u16 {
    u16::from_be_bytes([b[0], b[1]])
}
fn be32(b: &[u8]) -> u32 {
    u32::from_be_bytes([b[0], b[1], b[2], b[3]])
}

/// SANE 80 位扩展浮点 → f64。
fn read_ext80(b: &[u8]) -> f64 {
    let exp = be16(&b[0..2]) & 0x7FFF;
    let mant = u64::from_be_bytes([b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9]]);
    if exp == 0 && mant == 0 {
        return 0.0;
    }
    mant as f64 * 2f64.powi(exp as i32 - 16383 - 63)
}

/// f64 → SANE 80 位扩展浮点。0 / 负数 / 非有限值一律写成全零（= 无效）。
fn write_ext80(v: f64) -> [u8; 10] {
    let mut out = [0u8; 10];
    if !(v > 0.0) || !v.is_finite() {
        return out;
    }
    let exp2 = v.log2().floor() as i32;
    let e = (exp2 + 16383) as u16;
    // f ∈ [1, 2)，所以 f × 2^63 ∈ [2^63, 2^64)，正好落在 u64 里。
    let m = (v / 2f64.powi(exp2) * 2f64.powi(63)) as u64;
    out[0..2].copy_from_slice(&e.to_be_bytes());
    out[2..10].copy_from_slice(&m.to_be_bytes());
    out
}

/// 一个整样本（已按字节序还原）→ f32。
fn int_to_f32(bits: u16, raw: &[u8], little_endian: bool) -> f32 {
    match bits {
        8 => raw[0] as i8 as f32 / 128.0,
        16 => {
            let v = if little_endian {
                i16::from_le_bytes([raw[0], raw[1]])
            } else {
                i16::from_be_bytes([raw[0], raw[1]])
            };
            v as f32 / 32768.0
        }
        24 => {
            let v = if little_endian {
                ((raw[2] as i32) << 24 | (raw[1] as i32) << 16 | (raw[0] as i32) << 8) >> 8
            } else {
                ((raw[0] as i32) << 24 | (raw[1] as i32) << 16 | (raw[2] as i32) << 8) >> 8
            };
            v as f32 / 8_388_608.0
        }
        _ => {
            let v = if little_endian {
                i32::from_le_bytes([raw[0], raw[1], raw[2], raw[3]])
            } else {
                i32::from_be_bytes([raw[0], raw[1], raw[2], raw[3]])
            };
            v as f32 / 2_147_483_648.0
        }
    }
}

/// 读一个 AIFF / AIFC。
pub fn decode(path: &str) -> Result<Pcm, String> {
    let (mut f, file_len) = open_counted(path)?;

    let mut head = [0u8; 12];
    f.read_exact(&mut head).map_err(|e| format!("aiff header: {e}"))?;
    if &head[0..4] != b"FORM" {
        return Err("not an AIFF file (no FORM)".to_string());
    }
    let form = &head[8..12];
    let is_aifc = match form {
        b"AIFF" => false,
        b"AIFC" => true,
        other => {
            return Err(format!(
                "not an AIFF file (FORM type {})",
                String::from_utf8_lossy(other)
            ))
        }
    };

    // ---- 走 chunk，找 COMM 与 SSND ----
    let mut channels = 0u16;
    let mut declared_frames = 0u32;
    let mut bits = 0u16;
    let mut rate = 0f64;
    let mut little_endian = false;
    let mut ssnd_at = 0u64; // 样本数据的绝对偏移
    let mut ssnd_len = 0u64;

    let mut pos = 12u64;
    let mut cid = [0u8; 4];
    let mut szb = [0u8; 4];
    while pos + 8 <= file_len {
        f.read_exact(&mut cid).map_err(|e| format!("aiff chunk id: {e}"))?;
        f.read_exact(&mut szb).map_err(|e| format!("aiff chunk size: {e}"))?;
        let csize = be32(&szb) as u64;
        let body = pos + 8;
        // 声明的长度可以超出文件（截断的文件），一律夹到文件内。
        let avail = file_len.saturating_sub(body);
        let take = csize.min(avail);

        match &cid {
            b"COMM" => {
                if take < 18 {
                    return Err(format!("COMM chunk is {take} bytes, need at least 18"));
                }
                let mut c = [0u8; 18];
                f.read_exact(&mut c).map_err(|e| format!("aiff COMM: {e}"))?;
                channels = be16(&c[0..2]);
                declared_frames = be32(&c[2..6]);
                bits = be16(&c[6..8]);
                rate = read_ext80(&c[8..18]);
                if is_aifc {
                    // 压缩类型紧跟在 18 字节之后。读不到就按 NONE 处理（有些写入器
                    // 的 COMM 只写 18 字节，那是它们自己的问题，不是我们的）。
                    let mut ct = [0u8; 4];
                    if take >= 22 && f.read_exact(&mut ct).is_ok() {
                        match &ct {
                            b"NONE" => little_endian = false,
                            b"sowt" => {
                                little_endian = true;
                                // sowt 规范上就是 16 位；真文件里也只会是 16。
                                if bits != 16 {
                                    return Err(format!("AIFC sowt with {bits}-bit samples"));
                                }
                            }
                            other => {
                                return Err(format!(
                                    "unsupported AIFC compression '{}'",
                                    String::from_utf8_lossy(other)
                                ))
                            }
                        }
                    }
                }
                // 跳到 chunk 末尾（可能还有 compressionName）。
                let skip = take.saturating_sub(if is_aifc { 22 } else { 18 });
                if skip > 0 {
                    std::io::copy(&mut f.by_ref().take(skip), &mut std::io::sink())
                        .map_err(|e| format!("aiff skip COMM: {e}"))?;
                }
            }
            b"SSND" => {
                if take < 8 {
                    return Err(format!("SSND chunk is {take} bytes, need at least 8"));
                }
                let mut s = [0u8; 8];
                f.read_exact(&mut s).map_err(|e| format!("aiff SSND: {e}"))?;
                let offset = be32(&s[0..4]) as u64;
                // offset 是「样本前面的静音字节数」，通常是 0。夹到 chunk 内，
                // 否则一个坏头会让数据起点跑到文件外。
                let body_len = take - 8;
                if offset > body_len {
                    return Err(format!("SSND offset {offset} exceeds the chunk body"));
                }
                ssnd_at = body + 8 + offset;
                ssnd_len = body_len - offset;
                // 跳过 offset 那段静音。
                if offset > 0 {
                    std::io::copy(&mut f.by_ref().take(offset), &mut std::io::sink())
                        .map_err(|e| format!("aiff skip SSND offset: {e}"))?;
                }
                // 后面还有 chunk 的话得继续走；但样本已经在手上了，直接记下位置
                // 并继续遍历（真实文件里 SSND 通常是最后一个 chunk）。
            }
            _ => {
                // 不认识的 chunk（NAME / AUTH / ANNO / FVER / MARK / INST …）整块跳过。
                std::io::copy(&mut f.by_ref().take(take), &mut std::io::sink())
                    .map_err(|e| format!("aiff skip {cid:?}: {e}"))?;
            }
        }
        // chunk 长度是奇数时补一个字节。
        let padded = csize + (csize & 1);
        pos = body + padded;
        // 把流位置对齐到下一个 chunk 头。
        f.seek(SeekFrom::Start(pos))
            .map_err(|e| format!("aiff seek: {e}"))?;
    }

    if channels == 0 {
        return Err("AIFF has no COMM chunk".to_string());
    }
    if ssnd_len == 0 {
        return Err("AIFF has no SSND chunk".to_string());
    }
    if channels > MAX_CHANNELS {
        return Err(format!("AIFF declares {channels} channels"));
    }
    if rate <= 0.0 {
        return Err("AIFF declares sample rate 0".to_string());
    }
    if !matches!(bits, 8 | 16 | 24 | 32) {
        return Err(format!("unsupported AIFF sample size {bits}"));
    }

    // ---- 读样本 ----
    use std::io::Seek;
    f.seek(std::io::SeekFrom::Start(ssnd_at))
        .map_err(|e| format!("aiff seek to SSND: {e}"))?;
    let bps = (bits / 8) as usize;
    let ch = channels as usize;
    let frame_bytes = bps * ch;
    // 声明帧数也夹一次：它以 32 位存，坏文件里能声称 40 亿帧。
    let want = ssnd_len.min(declared_frames as u64 * frame_bytes as u64);
    let want = want / frame_bytes as u64 * frame_bytes as u64;

    let mut samples: Vec<f32> = Vec::with_capacity((want as usize / bps).max(1));
    let mut buf = vec![0u8; READ_CHUNK];
    let mut carry: Vec<u8> = Vec::with_capacity(bps);
    let mut left = want;
    while left > 0 {
        if extract_progress::cancelled() {
            return Err("cancelled".to_string());
        }
        let take = (left as usize).min(READ_CHUNK);
        let n = f.read(&mut buf[..take]).map_err(|e| format!("aiff read: {e}"))?;
        if n == 0 {
            break;
        }
        left -= n as u64;

        let mut data: &[u8] = &buf[..n];
        if !carry.is_empty() {
            carry.extend_from_slice(data);
            let usable = carry.len() / bps * bps;
            for s in carry[..usable].chunks_exact(bps) {
                samples.push(int_to_f32(bits, s, little_endian));
            }
            carry.drain(..usable);
            data = &[];
        }
        let usable = data.len() / bps * bps;
        for s in data[..usable].chunks_exact(bps) {
            samples.push(int_to_f32(bits, s, little_endian));
        }
        carry.extend_from_slice(&data[usable..]);
    }
    let whole = samples.len() / ch * ch;
    samples.truncate(whole);

    Ok(Pcm::new(rate.round() as u32, channels, samples))
}

/// 写一个普通 `AIFF`（大端线性 PCM）。`depth` 只接受 8 / 16 / 24 / 32。
pub fn encode(pcm: &Pcm, path: &str, depth: u16) -> Result<(), String> {
    if !matches!(depth, 8 | 16 | 24 | 32) {
        return Err(format!("unsupported AIFF bit depth {depth}"));
    }
    let bps = (depth / 8) as usize;
    let ch = pcm.channels.max(1) as usize;
    let frames = pcm.frames();
    let data_len = frames * ch * bps;
    // COMM 18 字节；SSND 头 8 字节。两个 chunk 头各 8 字节。
    // FORM 长度 = 4(form type) + (8+18) + (8+8+data) [+ 奇数补齐]
    let ssnd_pad = data_len & 1;
    let form_len = 4 + (8 + 18) + (8 + 8 + data_len + ssnd_pad);

    let file = std::fs::File::create(path).map_err(|e| format!("create {path}: {e}"))?;
    let mut w = CountedWriter::new(std::io::BufWriter::with_capacity(256 * 1024, file));
    use std::io::Write as _;

    let mut hdr = Vec::with_capacity(12 + 26 + 16);
    hdr.extend_from_slice(b"FORM");
    hdr.extend_from_slice(&(form_len as u32).to_be_bytes());
    hdr.extend_from_slice(b"AIFF");
    hdr.extend_from_slice(b"COMM");
    hdr.extend_from_slice(&18u32.to_be_bytes());
    hdr.extend_from_slice(&(ch as u16).to_be_bytes());
    hdr.extend_from_slice(&(frames as u32).to_be_bytes());
    hdr.extend_from_slice(&depth.to_be_bytes());
    hdr.extend_from_slice(&write_ext80(pcm.rate as f64));
    hdr.extend_from_slice(b"SSND");
    hdr.extend_from_slice(&((data_len + 8) as u32).to_be_bytes());
    hdr.extend_from_slice(&0u32.to_be_bytes()); // offset
    hdr.extend_from_slice(&0u32.to_be_bytes()); // blockSize
    w.write_all(&hdr).map_err(|e| format!("aiff header: {e}"))?;

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
            w.write_all(&out).map_err(|e| format!("aiff write: {e}"))?;
            out.clear();
        }
    }
    w.write_all(&out).map_err(|e| format!("aiff write: {e}"))?;
    if ssnd_pad == 1 {
        w.write_all(&[0u8]).map_err(|e| format!("aiff pad: {e}"))?;
    }
    w.flush().map_err(|e| format!("aiff flush: {e}"))?;
    Ok(())
}

/// 输出字节数（进度条的分母）。FORM 头 12 + COMM 26 + SSND 头 16 + 数据（含补齐）。
pub fn estimated_bytes(pcm: &Pcm, depth: u16) -> u64 {
    let bps = (depth as u64 / 8).max(1);
    let data = pcm.frames() as u64 * pcm.channels.max(1) as u64 * bps;
    12 + 26 + 16 + data + (data & 1)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testutil::{sine, tmpdir};

    /// 80 位扩展浮点的往返 —— 采样率是整个格式里最容易搞错的一个字段，
    /// 而它错了以后重采样器会去分配几 TB 的内存。
    #[test]
    fn extended_float_round_trips() {
        for v in [8000.0f64, 11_025.0, 22_050.0, 44_100.0, 48_000.0, 96_000.0, 192_000.0] {
            let b = write_ext80(v);
            let back = read_ext80(&b);
            assert_eq!(back, v, "ext80 往返：{v}");
        }
        // 44100 的字节形态是公开的：40 0E AC 44 00 00 00 00 00 00。
        let b = write_ext80(44_100.0);
        assert_eq!(b[0], 0x40);
        assert_eq!(b[1], 0x0E);
        assert_eq!(&b[2..6], &[0xAC, 0x44, 0x00, 0x00]);
        // 0 写成全零，读回来也是 0。
        assert_eq!(read_ext80(&write_ext80(0.0)), 0.0);
    }

    /// 每个位深「写 → 读」，误差在量化步长之内。这条同时钉住大端。
    #[test]
    fn round_trip_every_depth() {
        let dir = tmpdir("aiff");
        let src = sine(44_100, 2, 0.05, 440.0);
        for (depth, tol) in [(8u16, 1.0 / 128.0), (16, 1.0 / 32768.0), (24, 1.0 / 8_388_608.0), (32, 1e-6)] {
            let p = dir.join(format!("d{depth}.aiff"));
            encode(&src, p.to_str().unwrap(), depth).expect("encode aiff");
            let back = decode(p.to_str().unwrap()).expect("decode aiff");
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

    /// chunk 长度为奇数时必须按补齐后的长度走 —— 少走一个字节，后面所有 chunk
    /// 的 id 都是错的（这里用一个奇数长度的 ANNO 块夹在 COMM 和 SSND 之间）。
    #[test]
    fn odd_sized_chunk_padding_is_respected() {
        let dir = tmpdir("aiffpad");
        let src = sine(8000, 1, 0.02, 440.0);
        let p = dir.join("pad.aiff");
        encode(&src, p.to_str().unwrap(), 16).unwrap();

        // 手工在 COMM 之后插一个 5 字节的 ANNO 块（奇数 → 补 1 字节）。
        let raw = std::fs::read(&p).unwrap();
        let comm_end = 12 + 8 + 18;
        let mut out = Vec::new();
        out.extend_from_slice(&raw[..comm_end]);
        out.extend_from_slice(b"ANNO");
        out.extend_from_slice(&5u32.to_be_bytes());
        out.extend_from_slice(b"hello");
        out.push(0); // 补齐
        out.extend_from_slice(&raw[comm_end..]);
        // FORM 长度 +14
        let newlen = (be32(&out[4..8]) + 14) as u32;
        out[4..8].copy_from_slice(&newlen.to_be_bytes());
        std::fs::write(&p, &out).unwrap();

        let back = decode(p.to_str().unwrap()).unwrap();
        assert_eq!(back.frames(), src.frames(), "补齐后 SSND 仍应被找到");
        let worst = src
            .samples
            .iter()
            .zip(back.samples.iter())
            .fold(0.0f32, |m, (a, b)| m.max((a - b).abs()));
        assert!(worst <= 1.0 / 32768.0, "worst {worst}");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// AIFC 的 `sowt` 是小端 16 位 —— 按大端读会得到噪声。
    #[test]
    fn aifc_sowt_is_little_endian() {
        let dir = tmpdir("aiffsowt");
        let src = sine(8000, 1, 0.02, 440.0);
        // 手工造一个 AIFC/sowt：把普通 AIFF 的样本改成小端，FORM 类型改 AIFC，
        // COMM 加 compressionType。
        let p = dir.join("sowt.aifc");
        let frames = src.frames();
        let mut body = Vec::new();
        body.extend_from_slice(b"AIFC");
        body.extend_from_slice(b"COMM");
        body.extend_from_slice(&22u32.to_be_bytes());
        body.extend_from_slice(&1u16.to_be_bytes());
        body.extend_from_slice(&(frames as u32).to_be_bytes());
        body.extend_from_slice(&16u16.to_be_bytes());
        body.extend_from_slice(&write_ext80(8000.0));
        body.extend_from_slice(b"sowt");
        body.extend_from_slice(b"FVER");
        body.extend_from_slice(&4u32.to_be_bytes());
        body.extend_from_slice(&0xA280_5140u32.to_be_bytes());
        let data_len = frames * 2;
        body.extend_from_slice(b"SSND");
        body.extend_from_slice(&((data_len + 8) as u32).to_be_bytes());
        body.extend_from_slice(&0u32.to_be_bytes());
        body.extend_from_slice(&0u32.to_be_bytes());
        for &s in &src.samples {
            body.extend_from_slice(&((s * 32767.0).round() as i16).to_le_bytes());
        }
        let mut out = Vec::new();
        out.extend_from_slice(b"FORM");
        out.extend_from_slice(&(body.len() as u32).to_be_bytes());
        out.extend_from_slice(&body);
        std::fs::write(&p, &out).unwrap();

        let back = decode(p.to_str().unwrap()).unwrap();
        assert_eq!(back.frames(), frames);
        let worst = src
            .samples
            .iter()
            .zip(back.samples.iter())
            .fold(0.0f32, |m, (a, b)| m.max((a - b).abs()));
        assert!(worst <= 1.0 / 32768.0, "sowt 必须按小端读，worst {worst}");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// 不认识的 AIFC 压缩类型要**按名字报错**，不能当成 PCM 猜着读。
    #[test]
    fn unknown_aifc_compression_is_named() {
        let dir = tmpdir("aiffima4");
        let p = dir.join("ima4.aifc");
        let mut body = Vec::new();
        body.extend_from_slice(b"AIFC");
        body.extend_from_slice(b"COMM");
        body.extend_from_slice(&22u32.to_be_bytes());
        body.extend_from_slice(&1u16.to_be_bytes());
        body.extend_from_slice(&100u32.to_be_bytes());
        body.extend_from_slice(&16u16.to_be_bytes());
        body.extend_from_slice(&write_ext80(8000.0));
        body.extend_from_slice(b"ima4");
        let mut out = Vec::new();
        out.extend_from_slice(b"FORM");
        out.extend_from_slice(&(body.len() as u32).to_be_bytes());
        out.extend_from_slice(&body);
        std::fs::write(&p, &out).unwrap();
        let e = decode(p.to_str().unwrap()).unwrap_err();
        assert!(e.contains("ima4"), "错误消息要带上压缩类型名，got {e}");
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn non_aiff_is_rejected() {
        let dir = tmpdir("aiffbad");
        let p = dir.join("x.aiff");
        std::fs::write(&p, b"RIFF\x00\x00\x00\x00WAVE").unwrap();
        let e = decode(p.to_str().unwrap()).unwrap_err();
        assert!(e.contains("FORM"), "got {e}");
        std::fs::remove_dir_all(&dir).ok();
    }
}
