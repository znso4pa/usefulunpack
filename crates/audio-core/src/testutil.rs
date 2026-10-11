//! 单测共用的小工具。
//!
//! 三个模块的测试都要「造一段已知信号」「要一个临时目录」「量 RMS」——
//! 各写一份的话，某天有人在其中一个里把 440 Hz 改成别的频率，另一份测试
//! 就会在毫无关系的地方开始失败。这里放一份。

use crate::pcm::Pcm;

/// 指定频率的正弦，幅度 0.5。
pub fn sine(rate: u32, ch: u16, secs: f32, hz: f32) -> Pcm {
    let n = (rate as f32 * secs) as usize;
    let mut v = Vec::with_capacity(n * ch as usize);
    for i in 0..n {
        let s = (i as f32 / rate as f32 * hz * std::f32::consts::TAU).sin() * 0.5;
        for _ in 0..ch {
            v.push(s);
        }
    }
    Pcm::new(rate, ch, v)
}

/// 宽带白噪，幅度 0.5。
///
/// 为什么需要它：440 Hz 正弦是**最容易被压缩**的信号，Vorbis 在 q4 下把它压到
/// ~57 kbps —— 拿它去验证「码率估算」只会验证出一个和真实素材无关的比值。
/// 白噪的频谱铺满整个频带，才有资格代表「真实音频」。
pub fn noise(rate: u32, ch: u16, secs: f32) -> Pcm {
    let n = (rate as f32 * secs) as usize;
    // xorshift64：确定性，换台机器跑出的字节也一样。
    let mut state: u64 = 0x2545_F491_4F6C_DD1D;
    let mut next = || {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        (state >> 40) as f32 / (1u32 << 23) as f32 - 1.0
    };
    let mut v = Vec::with_capacity(n * ch as usize);
    for _ in 0..n {
        let s = next() * 0.5;
        for _ in 0..ch {
            v.push(s);
        }
    }
    Pcm::new(rate, ch, v)
}

/// 进程内唯一的临时目录。名字带 pid，多个测试二进制并行时不打架。
pub fn tmpdir(tag: &str) -> std::path::PathBuf {
    let d = std::env::temp_dir().join(format!("uu_audio_{}_{}", std::process::id(), tag));
    std::fs::create_dir_all(&d).unwrap();
    d
}

/// 整体均方根。用来验证重采样/编码没有把幅度吃掉。
pub fn rms(p: &Pcm) -> f32 {
    if p.samples.is_empty() {
        return 0.0;
    }
    (p.samples.iter().map(|s| s * s).sum::<f32>() / p.samples.len() as f32).sqrt()
}
