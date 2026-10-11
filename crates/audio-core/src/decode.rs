//! 输入格式 → [`Pcm`]。
//!
//! 每个解码器都自己负责把「源格式的样本宽度」换算成 `[-1.0, 1.0]` 的 f32：
//! - MP3 / Vorbis 的满量程是 32768（`i16` 的绝对值上限），所以除 32768；
//! - WAV 按位深定标：8 位除 128、16 位除 32768、24 位除 8388608、32 位整数除
//!   2^31。8 位 WAV 存的是**无符号**（0 = 中位），hound 已经替我们把这一层的
//!   偏移抹掉了，所以这里只按有符号值定标。
//!
//! 几条比较重的链路各占一个模块：AAC（MP4 容器 + 裸 ADTS）在 [`crate::aac`]，
//! AIFF 在 [`crate::aiff`]，AU 在 [`crate::au`]，FLAC 在 [`crate::flac`]。
//! 这里只做**按扩展名分派**，分派本身不认识格式。

use std::io::Read;

use crate::aac;
use crate::aiff;
use crate::au;
use crate::counted::{ext_of, open_counted, CountedFile};
use crate::flac;
use crate::pcm::Pcm;
use archive_common::extract_progress;

/// 解出的 PCM 字节数上限（f32 × 声道）。512 MiB ≈ 一张 20 分钟的 48 kHz 立体声
/// 素材 —— 再大就不该在手机上一次性铺进内存了，宁可报错也不让进程被 OOM 杀掉。
const MAX_PCM_BYTES: usize = 512 * 1024 * 1024;

/// 一次读取的字节数。64 KiB 让「读一遍」的进度条有足够细的粒度，又不至于
/// 每读 4 KiB 就动一次原子计数。
const READ_CHUNK: usize = 64 * 1024;

/// 按扩展名解码。
///
/// `.mp4` / `.m4a` 都走 AAC 那条路：从视频里**抽音轨**是个完全合理的请求，
/// 而 `aac::decode` 本来就只挑 AAC 音轨，遇到没有音轨的 MP4 会明确报错。
pub fn decode(path: &str) -> Result<Pcm, String> {
    let ext = ext_of(path);
    let pcm = match ext.as_str() {
        "mp3" | "mp2" | "mp1" => decode_mp3(path),
        "ogg" | "oga" => decode_ogg(path),
        "wav" | "wave" => decode_wav(path),
        "flac" => flac::decode(path),
        "m4a" | "m4b" | "mp4" | "aac" => aac::decode(path),
        "aif" | "aiff" | "aifc" => aiff::decode(path),
        "au" | "snd" => au::decode(path),
        other => Err(format!("unsupported audio input .{other}")),
    }?;
    let bytes = pcm.samples.len() * std::mem::size_of::<f32>();
    if bytes > MAX_PCM_BYTES {
        return Err(format!(
            "decoded audio is {} MiB, over the {} MiB in-memory limit",
            bytes / (1024 * 1024),
            MAX_PCM_BYTES / (1024 * 1024)
        ));
    }
    if pcm.frames() == 0 {
        return Err("no audio frames decoded".to_string());
    }
    Ok(pcm)
}

/// MP3：`rusty_mp3` 的流式解码器，自己读文件、自己数进度。
fn decode_mp3(path: &str) -> Result<Pcm, String> {
    let (mut file, _) = open_counted(path)?;
    let mut dec = rusty_mp3::Mp3Decoder::new();
    let mut samples: Vec<f32> = Vec::new();
    let mut rate = 0u32;
    let mut channels = 0u16;
    let mut buf = vec![0u8; READ_CHUNK];

    loop {
        if extract_progress::cancelled() {
            return Err("cancelled".to_string());
        }
        let n = file.read(&mut buf).map_err(|e| format!("read {path}: {e}"))?;
        if n == 0 {
            break;
        }
        dec.push(&buf[..n]);
        drain_mp3(&mut dec, &mut samples, &mut rate, &mut channels)?;
    }
    dec.flush();
    drain_mp3(&mut dec, &mut samples, &mut rate, &mut channels)?;
    if rate == 0 {
        return Err("no decodable MP3 frames".to_string());
    }
    Ok(Pcm::new(rate, channels, samples))
}

/// 抽干解码器当前能给出的整帧。
///
/// 第一帧定下采样率/声道数；后面若出现不一致（混流文件），**报错**而不是继续
/// 拼接 —— 交错样本一旦跨了不同的声道数，之后的每个样本都错位，产出的文件
/// 听上去像噪声，而用户只会以为「转换坏了」。
fn drain_mp3(
    dec: &mut rusty_mp3::Mp3Decoder,
    samples: &mut Vec<f32>,
    rate: &mut u32,
    channels: &mut u16,
) -> Result<(), String> {
    loop {
        match dec.next_frame() {
            Ok(f) => {
                if *rate == 0 {
                    *rate = f.sample_rate;
                    *channels = f.channels;
                } else if f.sample_rate != *rate || f.channels != *channels {
                    return Err(format!(
                        "MP3 stream changes format mid-file ({} Hz/{}ch → {} Hz/{}ch)",
                        *rate, *channels, f.sample_rate, f.channels
                    ));
                }
                samples.extend_from_slice(&f.samples);
            }
            // `Again` = 需要更多输入，`Eof` = 冲干净了。两者都不是错误。
            Err(rusty_mp3::Error::Again | rusty_mp3::Error::Eof) => return Ok(()),
            Err(e) => return Err(format!("mp3 decode: {e}")),
        }
    }
}

/// Ogg Vorbis：`lewton`。它的读入器要 `Read + Seek`，所以走 [`CountedFile`]。
fn decode_ogg(path: &str) -> Result<Pcm, String> {
    let file = CountedFile::open(path)?;
    let mut rdr = lewton::inside_ogg::OggStreamReader::new(file)
        .map_err(|e| format!("ogg open: {e}"))?;
    let rate = rdr.ident_hdr.audio_sample_rate;
    let channels = rdr.ident_hdr.audio_channels as u16;
    if channels == 0 {
        return Err("ogg stream declares 0 channels".to_string());
    }

    let mut samples: Vec<f32> = Vec::new();
    loop {
        if extract_progress::cancelled() {
            return Err("cancelled".to_string());
        }
        let pkt = rdr
            .read_dec_packet_itl()
            .map_err(|e| format!("ogg decode: {e}"))?;
        let Some(pkt) = pkt else { break };
        samples.extend(pkt.iter().map(|&s| s as f32 / 32768.0));
        // 提前止损：一个声称「很长」的坏文件不该先把内存吃光才报错。
        if samples.len() * std::mem::size_of::<f32>() > MAX_PCM_BYTES {
            return Err("ogg stream is too long for the in-memory limit".to_string());
        }
    }
    Ok(Pcm::new(rate, channels, samples))
}

/// WAV：`hound` 管容器，我们只管按位深定标。
fn decode_wav(path: &str) -> Result<Pcm, String> {
    let (file, _) = open_counted(path)?;
    let mut rdr = hound::WavReader::new(file).map_err(|e| format!("wav open: {e}"))?;
    let spec = rdr.spec();
    if spec.channels == 0 {
        return Err("wav declares 0 channels".to_string());
    }

    let mut samples: Vec<f32> = Vec::with_capacity(rdr.len() as usize * spec.channels as usize);
    match (spec.sample_format, spec.bits_per_sample) {
        (hound::SampleFormat::Float, 32) => {
            for s in rdr.samples::<f32>() {
                samples.push(s.map_err(|e| format!("wav sample: {e}"))?);
            }
        }
        (hound::SampleFormat::Int, 8) => {
            for s in rdr.samples::<i8>() {
                samples.push(s.map_err(|e| format!("wav sample: {e}"))? as f32 / 128.0);
            }
        }
        (hound::SampleFormat::Int, 16) => {
            for s in rdr.samples::<i16>() {
                samples.push(s.map_err(|e| format!("wav sample: {e}"))? as f32 / 32768.0);
            }
        }
        (hound::SampleFormat::Int, 24) => {
            for s in rdr.samples::<i32>() {
                samples.push(s.map_err(|e| format!("wav sample: {e}"))? as f32 / 8_388_608.0);
            }
        }
        (hound::SampleFormat::Int, 32) => {
            for s in rdr.samples::<i32>() {
                samples
                    .push(s.map_err(|e| format!("wav sample: {e}"))? as f32 / 2_147_483_648.0);
            }
        }
        (fmt, bits) => {
            return Err(format!("unsupported wav sample format {fmt:?} at {bits} bits"));
        }
    }
    Ok(Pcm::new(spec.sample_rate, spec.channels, samples))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::encode;
    use crate::options::AudioOptions;
    use crate::testutil::{sine, tmpdir};

    /// 每个位深的 WAV 走一遍「写 → 读」，比对比源 PCM 的误差在量化步长之内。
    ///
    /// 这是 WAV 链路唯一真正需要验证的东西：hound 的 8 位无符号偏置、24 位的
    /// 三字节对齐，任何一处搞反都会让整条链路的输出变成噪声，而「文件能打开」
    /// 是发现不了的。
    #[test]
    fn wav_round_trip_every_bit_depth() {
        let dir = tmpdir("wavbits");
        let src = sine(44_100, 2, 0.05, 440.0);
        for (depth, tol) in [(8u16, 1.0 / 128.0), (16, 1.0 / 32768.0), (24, 1.0 / 8_388_608.0), (32, 1e-6)] {
            let p = dir.join(format!("d{depth}.wav"));
            let opts = AudioOptions { bit_depth: depth, ..Default::default() };
            encode::encode_wav(&src, p.to_str().unwrap(), &opts).expect("encode wav");
            let back = decode_wav(p.to_str().unwrap()).expect("decode wav");
            assert_eq!(back.rate, src.rate);
            assert_eq!(back.channels, src.channels);
            assert_eq!(back.frames(), src.frames(), "bit depth {depth}: frame count");
            let worst = src
                .samples
                .iter()
                .zip(back.samples.iter())
                .fold(0.0f32, |m, (a, b)| m.max((a - b).abs()));
            assert!(worst <= tol, "bit depth {depth}: worst error {worst} > {tol}");
        }
        std::fs::remove_dir_all(&dir).ok();
    }

    /// 未知扩展名要在读文件之前就被拒。
    #[test]
    fn unknown_extension_is_rejected() {
        let e = decode("/nonexistent/file.wma").unwrap_err();
        assert!(e.contains("unsupported"), "got {e}");
    }

    /// 分派表本身：每个**声称支持**的扩展名都要落到一条真实的分支上，
    /// 不能悄悄掉进 `other`。这里只查「消息里说的是不是 unsupported」——
    /// 文件不存在是预期内的，那说明分派成功了。
    #[test]
    fn every_advertised_extension_reaches_a_real_decoder() {
        let supported = [
            "mp3", "mp2", "mp1", "ogg", "oga", "wav", "wave", "flac", "m4a", "m4b", "mp4",
            "aac", "aif", "aiff", "aifc", "au", "snd",
        ];
        for ext in supported {
            let e = decode(&format!("/nonexistent/file.{ext}")).unwrap_err();
            assert!(
                !e.contains("unsupported"),
                ".{ext} 在分派表里，但没人认领它：{e}"
            );
        }
    }
}
