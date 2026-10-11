//! 采样率转换（rubato 的窗化 sinc 重采样器）。
//!
//! 只有「目标采样率和源不同」时才走这里；相同就原样返回，一个样本都不动。
//! 这条早退不是优化，是正确性：无谓的重采样会给整段音频加上滤波器的延迟和
//! 一点点通带纹波，而用户的意图是「别动」。

use rubato::{Resampler, SincFixedIn, SincInterpolationParameters, SincInterpolationType, WindowFunction};

use crate::pcm::Pcm;
use archive_common::extract_progress;

/// 每次喂给重采样器的输入帧数。
///
/// 必须 ≥ `sinc_len`，否则 rubato 在构造时就拒绝。4096 帧在 44.1 kHz 下约 93 ms：
/// 够大让 sinc 的摊薄开销可忽略，够小让取消能被及时看到。
const CHUNK_FRAMES: usize = 4096;

/// 把 `pcm` 重采样到 `target_rate`。`target_rate == 0` 或与源相同则原样返回。
pub fn resample(pcm: Pcm, target_rate: u32) -> Result<Pcm, String> {
    if target_rate == 0 || target_rate == pcm.rate || pcm.frames() == 0 {
        return Ok(pcm);
    }
    let ch = pcm.channels.max(1) as usize;
    let ratio = target_rate as f64 / pcm.rate as f64;

    // sinc_len 256 / oversampling 256 是 rubato 文档建议的起点：44.1k↔48k 这类
    // 非整数比下通带到 0.95×Nyquist 仍平坦。BlackmanHarris2 是文档里衰减最好的窗。
    let params = SincInterpolationParameters {
        sinc_len: 256,
        f_cutoff: 0.95,
        interpolation: SincInterpolationType::Linear,
        oversampling_factor: 256,
        window: WindowFunction::BlackmanHarris2,
    };
    let mut r = SincFixedIn::<f32>::new(ratio, 1.0, params, CHUNK_FRAMES, ch)
        .map_err(|e| format!("resampler init ({ratio}): {e}"))?;

    let frames = pcm.frames();
    // 期望输出帧数：由输入帧数 × 比率定死。
    //
    // 为什么非要自己算这个数：rubato 的 `SincFixedIn` 输入块长是**固定**的，
    // 尾块不足一块时 `process_partial` 会补零补满，于是补出来的那部分也会被
    // 「转换」成输出 —— 0.5 秒的素材按比率 0.5 会得到 14207 帧而不是 11025 帧，
    // 多出来的全是零填充的尾巴。冲延迟那次 `process_partial(None)` 同理，又会
    // 多产出一整个 chunk 的输出。所以：先丢掉滤波器延迟，再按算出来的长度截断。
    let expected_out = (frames as f64 * ratio).round() as usize;

    let mut out: Vec<Vec<f32>> = vec![Vec::new(); ch];
    let mut pos = 0usize;
    while pos < frames {
        if extract_progress::cancelled() {
            return Err("cancelled".to_string());
        }
        let want = r.input_frames_next();
        let take = want.min(frames - pos);
        // rubato 吃的是**非交错**（planar）布局，所以要在这里拆一次。
        let block: Vec<Vec<f32>> = (0..ch)
            .map(|c| (pos..pos + take).map(|f| pcm.samples[f * ch + c]).collect())
            .collect();
        let got = if take == want {
            r.process(&block, None)
        } else {
            // 尾块不足一整个 chunk：补零 + 冲延迟，否则最后 ~93 ms 会被丢掉。
            r.process_partial(Some(&block), None)
        }
        .map_err(|e| format!("resample: {e}"))?;
        for (c, chan) in got.iter().enumerate().take(ch) {
            out[c].extend_from_slice(chan);
        }
        pos += take;
    }
    // 把滤波器内部还留着的样本冲出来。
    if let Ok(tail) = r.process_partial::<Vec<f32>>(None, None) {
        for (c, chan) in tail.iter().enumerate().take(ch) {
            out[c].extend_from_slice(chan);
        }
    }

    // 线性相位 FIR 的固有延迟：输出开头的那 `delay` 帧是滤波器的填充，不是音频。
    // 丢掉它，输出才和输入在同一时间轴上；再截到期望长度，去掉零填充的尾巴。
    let delay = r.output_delay();
    for chan in out.iter_mut() {
        let drop = delay.min(chan.len());
        chan.drain(..drop);
        chan.truncate(expected_out);
    }

    let n = out.first().map_or(0, |c| c.len());
    let mut samples = Vec::with_capacity(n * ch);
    for i in 0..n {
        for chan in out.iter() {
            samples.push(chan[i]);
        }
    }
    Ok(Pcm::new(target_rate, pcm.channels, samples))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testutil::{rms, sine};

    #[test]
    fn same_rate_or_keep_is_a_pure_passthrough() {
        let src = sine(44_100, 2, 0.1, 440.0);
        let n = src.samples.len();
        let kept = resample(sine(44_100, 2, 0.1, 440.0), 0).unwrap();
        assert_eq!(kept.samples.len(), n, "rate=0 必须原样返回");
        let same = resample(sine(44_100, 2, 0.1, 440.0), 44_100).unwrap();
        assert_eq!(same.samples.len(), n, "同采样率必须原样返回");
    }

    #[test]
    fn down_and_up_preserve_length_and_amplitude() {
        let src = sine(44_100, 2, 0.5, 440.0);
        let half = resample(sine(44_100, 2, 0.5, 440.0), 22_050).unwrap();
        assert_eq!(half.rate, 22_050);
        // 44.1k → 22.05k 是 2:1 降采样，帧数应当**减半**。
        // （原断言写的是 0.99..1.01，等于要求「降采样后帧数不变」，那是错的。）
        let ratio = half.frames() as f64 / src.frames() as f64;
        assert!((0.49..0.51).contains(&ratio), "半速后帧数比 {ratio:.4}");
        // 幅度要保住：440 Hz 远在通带内，sinc 重采样不该让它掉超过 5%。
        let (a, b) = (rms(&src), rms(&half));
        assert!((a - b).abs() / a < 0.05, "RMS {a:.4} → {b:.4}");
    }

    #[test]
    fn odd_ratio_44100_to_48000() {
        let src = sine(44_100, 2, 0.5, 1000.0);
        let up = resample(sine(44_100, 2, 0.5, 1000.0), 48_000).unwrap();
        assert_eq!(up.rate, 48_000);
        let ratio = up.frames() as f64 / src.frames() as f64;
        assert!((1.07..1.11).contains(&ratio), "44.1k→48k 帧数比 {ratio:.4}");
        let (a, b) = (rms(&src), rms(&up));
        assert!((a - b).abs() / a < 0.05, "RMS {a:.4} → {b:.4}");
    }

    #[test]
    fn mono_and_stereo_both_survive() {
        for ch in [1u16, 2] {
            let out = resample(sine(48_000, ch, 0.2, 440.0), 16_000).unwrap();
            assert_eq!(out.channels, ch);
            assert_eq!(out.rate, 16_000);
            assert!(out.frames() > 0);
        }
    }
}
