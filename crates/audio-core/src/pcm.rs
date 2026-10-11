//! 解码与编码之间唯一的中间表示：交错 f32 PCM。
//!
//! 三个解码器（MP3 / Vorbis / WAV）输出格式各异（i16 / i8..i32 / f32），三个编码器
//! 要的也不一样；把它们全都归一到 `[-1.0, 1.0]` 的交错 f32 上，解码/编码两侧就
//! 各自只认识一种输入，谁也不用知道对面是什么格式。

/// 交错（interleaved）f32 PCM。`samples.len()` 一定是 `channels` 的整数倍。
pub struct Pcm {
    pub rate: u32,
    pub channels: u16,
    /// 交错样本，范围 `[-1.0, 1.0]`（解码器理论上可以越界，编码前会夹一次）。
    pub samples: Vec<f32>,
}

/// 手写 `Debug`：默认的派生会把整段样本铺出来，一次失败的 `assert_eq!` 就能
/// 刷屏几兆字符，而人真正想看的只是「采样率/声道/帧数」这几个数。
impl std::fmt::Debug for Pcm {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Pcm")
            .field("rate", &self.rate)
            .field("channels", &self.channels)
            .field("frames", &self.frames())
            .field("secs", &format!("{:.3}", self.duration_secs()))
            .finish()
    }
}

impl Pcm {
    pub fn new(rate: u32, channels: u16, samples: Vec<f32>) -> Self {
        let ch = channels.max(1) as usize;
        // 不变量：样本数必须是整帧。截掉尾巴上不足一帧的残料，而不是让下游
        // 每个消费者各自 `len()/ch` 时丢掉同一批字节（那样迟早有一处忘了丢）。
        let whole = samples.len() / ch * ch;
        let mut samples = samples;
        samples.truncate(whole);
        Self { rate, channels: ch as u16, samples }
    }

    /// 每声道的样本数。
    pub fn frames(&self) -> usize {
        self.samples.len() / self.channels.max(1) as usize
    }

    /// 时长（秒）。
    pub fn duration_secs(&self) -> f64 {
        if self.rate == 0 {
            0.0
        } else {
            self.frames() as f64 / self.rate as f64
        }
    }

    /// 变成 `want` 个声道。`want == 0` 或与当前相同则原样返回。
    ///
    /// - 立体声 → 单声道：两声道取平均（不是丢右声道 —— 丢一边会让反相信号整个消失）。
    /// - 单声道 → 立体声：复制。
    /// - 多于两声道：非交错地平均到单声道（素材里罕见，但不能直接报错）。
    pub fn with_channels(self, want: u16) -> Self {
        let want = want as usize;
        let have = self.channels.max(1) as usize;
        if want == 0 || want == have {
            return self;
        }
        let frames = self.frames();
        let mut out = Vec::with_capacity(frames * want);
        if want == 1 {
            let inv = 1.0 / have as f32;
            for f in 0..frames {
                let base = f * have;
                let sum: f32 = self.samples[base..base + have].iter().sum();
                out.push(sum * inv);
            }
        } else {
            // want == 2（sanitize 已经把其它值挡掉了）。多于两声道时取前两声道，
            // 不再做下混矩阵 —— 5.1 的声道顺序在这个抽象层里没有信息可依。
            for f in 0..frames {
                let base = f * have;
                let l = self.samples[base];
                let r = if have >= 2 { self.samples[base + 1] } else { l };
                out.push(l);
                out.push(r);
            }
        }
        Pcm::new(self.rate, want as u16, out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn constructor_truncates_a_partial_trailing_frame() {
        let p = Pcm::new(44_100, 2, vec![0.0; 7]);
        assert_eq!(p.frames(), 3);
        assert_eq!(p.samples.len(), 6);
    }

    #[test]
    fn stereo_to_mono_averages_both_channels() {
        // 左右完全反相的信号：取平均得 0，取单边会得到满幅。这条测的就是
        // 「不是简单丢右声道」。
        let p = Pcm::new(48_000, 2, vec![0.5, -0.5, 0.25, -0.25]);
        let m = p.with_channels(1);
        assert_eq!(m.channels, 1);
        assert_eq!(m.samples, vec![0.0, 0.0]);
    }

    #[test]
    fn mono_to_stereo_duplicates() {
        let p = Pcm::new(44_100, 1, vec![0.1, 0.2, 0.3]);
        let s = p.with_channels(2);
        assert_eq!(s.channels, 2);
        assert_eq!(s.samples, vec![0.1, 0.1, 0.2, 0.2, 0.3, 0.3]);
    }

    #[test]
    fn want_zero_or_same_returns_input_untouched() {
        let same = Pcm::new(44_100, 2, vec![0.1, 0.2]).with_channels(2);
        assert_eq!(same.samples, vec![0.1, 0.2]);
        let keep = Pcm::new(44_100, 2, vec![0.1, 0.2]).with_channels(0);
        assert_eq!(keep.channels, 2);
        assert_eq!(keep.samples, vec![0.1, 0.2]);
    }

    #[test]
    fn duration_is_frames_over_rate() {
        let p = Pcm::new(1000, 1, vec![0.1, -0.7, 0.3]);
        assert!((p.duration_secs() - 0.003).abs() < 1e-9);
    }
}
