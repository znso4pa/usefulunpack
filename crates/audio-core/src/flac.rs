//! FLAC 解码（只进不出）。
//!
//! 用 `claxon`：Apache-2.0，从 2015 年就在，做过 fuzz，只解不编 —— 正好是我们
//! 需要的方向。FLAC 是**无损**格式，所以这里只做一件事：按位深把整数样本
//! 定标回 `[-1, 1]`。
//!
//! **FLAC 只能当源**。本仓不发 FLAC 编码器：`claxon` 不含编码路径，而纯 Rust
//! 的 FLAC 编码器（`flacenc` 之类）目前都不够成熟，为「无损转无损」引一个
//! 新依赖不划算 —— 用户真要无损，WAV 也是无损。
//!
//! ## 定标
//!
//! FLAC 的位深是**流里写着的**（`STREAMINFO` 的 `bits_per_sample`），可以是
//! 4..32 里的任意值，不是只有 16/24。所以除数必须按实际位深算，不能硬编码：
//! 20 位素材按 16 位除会得到 16 倍的幅度，直接削波。
//!
//! 除的是 `2^(bits-1)`（有符号满量程的绝对值上限），和 WAV 那条链路同一个
//! 约定：`-2^(n-1)` 映射到 -1.0，`2^(n-1)-1` 映射到略小于 1.0。

use crate::counted::open_counted;
use crate::pcm::Pcm;
use archive_common::extract_progress;

/// 解出的 PCM 字节数上限。和 `decode.rs` 用同一个口径（512 MiB f32 样本）。
const MAX_PCM_BYTES: usize = 512 * 1024 * 1024;

/// FLAC 的位深下限。规范允许 4..32，低于 8 位现实中不存在，但规范没禁止，
/// 所以不拒 —— 只是把它当合法的低幅度素材处理。
const MIN_BITS: u32 = 4;
const MAX_BITS: u32 = 32;

/// 按位深算定标分母。`bits` 非法时返回 `None`。
fn scale_for_bits(bits: u32) -> Option<f32> {
    if !(MIN_BITS..=MAX_BITS).contains(&bits) {
        return None;
    }
    // 32 位时 2^31 正好是 f32 能精确表示的上界，直接算。
    Some((1u64 << (bits - 1)) as f32)
}

/// 解码一个 `.flac` 文件。
pub fn decode(path: &str) -> Result<Pcm, String> {
    let (file, _) = open_counted(path)?;
    let mut rdr = claxon::FlacReader::new(file).map_err(|e| format!("flac open: {e}"))?;
    let info = rdr.streaminfo();
    if info.channels == 0 {
        return Err("flac declares 0 channels".to_string());
    }
    if info.channels > u16::MAX as u32 {
        return Err(format!("flac declares {} channels", info.channels));
    }
    let scale = scale_for_bits(info.bits_per_sample).ok_or_else(|| {
        format!(
            "flac declares {} bits per sample (supported: {MIN_BITS}..{MAX_BITS})",
            info.bits_per_sample
        )
    })?;

    // 有 `total_samples` 就按它预留；流式/未知长度时它是 `None`，那就别预留。
    // 这个数是**每声道**的帧数（claxon 的 `StreamInfo::samples` 就是 inter-channel
    // samples），不是交错后的样本总数 —— 拿来当 `Vec` 的容量要乘声道数。
    let cap = info
        .samples
        .map(|n| (n.saturating_mul(info.channels as u64)).min(usize::MAX as u64) as usize)
        .unwrap_or(0);
    let mut samples: Vec<f32> = Vec::with_capacity(cap);
    let inv = 1.0f32 / scale;
    for s in rdr.samples() {
        if extract_progress::cancelled() {
            return Err("cancelled".to_string());
        }
        let v = s.map_err(|e| format!("flac decode: {e}"))?;
        samples.push(v as f32 * inv);
        // 提前止损：一个声称「很长」的坏文件不该先把内存吃光才报错。
        if samples.len() * std::mem::size_of::<f32>() > MAX_PCM_BYTES {
            return Err("flac stream is too long for the in-memory limit".to_string());
        }
    }
    Ok(Pcm::new(info.sample_rate, info.channels as u16, samples))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testutil::{sine, tmpdir};

    /// 没有真 FLAC 样本时的兜底：手写一个最小的 FLAC 流（fLaC 魔数 + 一个
    /// 只含 STREAMINFO 的元数据块 + 没有音频帧），确认**报错路径**是清楚的，
    /// 而不是 panic。
    #[test]
    fn a_header_only_file_is_rejected_cleanly() {
        let dir = tmpdir("flacbare");
        let p = dir.join("bare.flac");
        let mut b = Vec::new();
        b.extend_from_slice(b"fLaC");
        // 最后一个元数据块 + 类型 0 (STREAMINFO) + 长度 34
        b.push(0x80);
        b.extend_from_slice(&[0, 0, 34]);
        b.extend_from_slice(&[0u8; 34]);
        std::fs::write(&p, &b).unwrap();
        // 要么报「没有音频」，要么报解不出 —— 但绝不能 panic。
        let e = decode(p.to_str().unwrap()).unwrap_err();
        assert!(!e.is_empty(), "必须给出一句人话");
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn non_flac_is_rejected() {
        let dir = tmpdir("flacbad");
        let p = dir.join("x.flac");
        std::fs::write(&p, vec![0u8; 1024]).unwrap();
        let e = decode(p.to_str().unwrap()).unwrap_err();
        assert!(e.contains("flac"), "错误消息要指明是 FLAC 读不开，got {e}");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// 定标表本身：每个合法位深都要有分母，非法位深要被拒。
    #[test]
    fn scale_table_covers_every_legal_depth() {
        assert_eq!(scale_for_bits(8), Some(128.0));
        assert_eq!(scale_for_bits(16), Some(32768.0));
        assert_eq!(scale_for_bits(20), Some(524_288.0));
        assert_eq!(scale_for_bits(24), Some(8_388_608.0));
        assert_eq!(scale_for_bits(32), Some(2_147_483_648.0));
        assert_eq!(scale_for_bits(3), None);
        assert_eq!(scale_for_bits(33), None);
    }

    /// 有真样本时（`UU_AUDIO_SAMPLES=<目录>`）走一遍「FLAC → 我们自己的解码器」，
    /// 确认能读回原样的长度和幅度。没有样本就 SKIP。
    ///
    /// 样本是 `aevalsrc=0.5*sin(2*PI*440*t)`（44.1 kHz 立体声 0.5 s），所以
    /// 峰值和长度都是**已知的精确值**，不是「大概齐」。
    #[test]
    fn real_sample_round_trip_when_available() {
        let Some(dir) = std::env::var_os("UU_AUDIO_SAMPLES") else {
            eprintln!("SKIP real_sample_round_trip_when_available: UU_AUDIO_SAMPLES 未设置");
            return;
        };
        let p = std::path::Path::new(&dir).join("sine.flac");
        if !p.exists() {
            eprintln!("SKIP: {} 不存在", p.display());
            return;
        }
        let pcm = decode(p.to_str().unwrap()).expect("decode flac");
        assert_eq!(pcm.rate, 44_100);
        assert_eq!(pcm.channels, 2);
        // FLAC 无损，帧数必须**精确**。
        assert_eq!(pcm.frames(), 22_050, "无损格式的帧数必须精确");
        // 无损：幅度必须还在 0.5 附近。
        let peak = pcm.samples.iter().fold(0.0f32, |m, s| m.max(s.abs()));
        assert!((peak - 0.5).abs() < 0.001, "峰值 {peak} 偏离 0.5");
    }

    /// 和上面同源，但确认**定标**没错：无损格式解回来必须与源 PCM 几乎逐样本相等。
    ///
    /// 容差按 16 位量化步长给：`aevalsrc` 用 f64 算、`testutil::sine` 用 f32 算，
    /// 再加上两边各自 round 到 16 位（一个按 32768、一个按 32767 定标），
    /// 逐样本最多差两三个 LSB。
    #[test]
    fn decoded_flac_matches_the_source_pcm_when_available() {
        let Some(dir) = std::env::var_os("UU_AUDIO_SAMPLES") else {
            eprintln!(
                "SKIP decoded_flac_matches_the_source_pcm_when_available: UU_AUDIO_SAMPLES 未设置"
            );
            return;
        };
        let p = std::path::Path::new(&dir).join("sine.flac");
        if !p.exists() {
            eprintln!("SKIP: {} 不存在", p.display());
            return;
        }
        let back = decode(p.to_str().unwrap()).expect("decode flac");
        let src = sine(44_100, 2, 0.5, 440.0);
        let n = back.frames().min(src.frames());
        assert!(n > 0, "两边都得有样本");
        let mut worst = 0.0f32;
        for i in 0..n * 2 {
            worst = worst.max((back.samples[i] - src.samples[i]).abs());
        }
        let tol = 4.0 / 32768.0;
        assert!(
            worst < tol,
            "无损解码的误差不该超过几个量化步长，got {worst} (tol {tol})"
        );
    }
}
