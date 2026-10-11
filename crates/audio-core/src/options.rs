//! 转换选项。
//!
//! Kotlin 侧把用户的选择压成 `k=v;k=v` 一个字符串传进来 —— 和 `CsoCore` 用字符串
//! 传块大小同一个理由：JNI 参数越少越好，而且以后加一项不必动 JNI 签名。
//!
//! 每个字段的「0 / 默认」都表示**保持源文件的值**，这是 UI 上「原样」那一档。

/// 目标采样率（Hz）；0 = 保持源采样率。
pub const KEEP: u32 = 0;

/// 采样率上限。192 kHz 已经高过任何消费级素材，再往上只是让重采样器吃内存。
const MAX_RATE: u32 = 192_000;

/// 转换选项。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct AudioOptions {
    /// 目标采样率（Hz）；[`KEEP`] = 保持源采样率。
    pub sample_rate: u32,
    /// 目标声道数（1 / 2）；[`KEEP`] 的 0 = 保持源声道数。
    pub channels: u16,
    /// 目标位深：8 / 16 / 24 / 32。**无损容器输出会读它** —— WAV / AIFF / AU。
    ///
    /// 32 位的含义**逐格式不同**：WAV 写**浮点**（32 位整数 PCM 在实际素材里几乎
    /// 不存在，而 32f 是浮点 WAV 的事实标准），AIFF / AU 写**整数**（那两种格式里
    /// 32f 不是常规做法）。这不是笔误，`media_opt_lossless_note` 在 UI 上说明过。
    pub bit_depth: u16,
    /// 有损目标的码率（kbps）：MP3 / OGG / AAC；0 = 用格式自己的默认值。
    pub bitrate_kbps: u32,
    /// MP3 走 VBR 而不是 CBR。
    pub vbr: bool,
    /// MP3 VBR 质量档：0 最好 … 9 最小（LAME `-q:a` 的刻度）。
    pub vbr_quality: u8,
}

impl Default for AudioOptions {
    fn default() -> Self {
        Self {
            sample_rate: KEEP,
            channels: 0,
            bit_depth: 16,
            bitrate_kbps: 0,
            vbr: false,
            vbr_quality: 2,
        }
    }
}

impl AudioOptions {
    /// 解析 `rate=44100;ch=2;depth=16;br=192;vbr=0;q=2`。
    ///
    /// 认不出的键**忽略**、认得出但越界的值**夹到合法区间**：选项串是 UI 和
    /// 引擎之间的契约，一份写坏的串应该退化成默认行为，而不是让转换整个失败。
    pub fn parse(s: &str) -> Self {
        let mut o = Self::default();
        for kv in s.split(';') {
            let Some((k, v)) = kv.split_once('=') else { continue };
            let (k, v) = (k.trim(), v.trim());
            match k {
                "rate" => o.sample_rate = v.parse().unwrap_or(KEEP),
                "ch" => o.channels = v.parse().unwrap_or(0),
                "depth" => o.bit_depth = v.parse().unwrap_or(16),
                "br" => o.bitrate_kbps = v.parse().unwrap_or(0),
                "vbr" => o.vbr = v == "1",
                "q" => o.vbr_quality = v.parse().unwrap_or(2),
                _ => {}
            }
        }
        o.sanitize()
    }

    /// 夹到引擎真的能处理的范围。
    ///
    /// 采样率是**离散白名单**而不是区间：rubato 对任何正比率都能算，但
    /// 8000 Hz 以下的 MP3 需要 MPEG-2.5 头、48000 以上又和大部分素材对不上，
    /// 与其让编码器在奇怪的值上产出没人能播的文件，不如夹到已知能播的那几档。
    pub fn sanitize(mut self) -> Self {
        const RATES: &[u32] = &[
            8000, 11025, 16000, 22050, 24000, 32000, 44100, 48000, 88200, 96000, 192_000,
        ];
        if self.sample_rate != KEEP && !RATES.contains(&self.sample_rate) {
            self.sample_rate = nearest_rate(self.sample_rate);
        }
        debug_assert!(self.sample_rate == KEEP || self.sample_rate <= MAX_RATE);
        if self.channels != 0 && self.channels != 1 && self.channels != 2 {
            self.channels = 0;
        }
        if !matches!(self.bit_depth, 8 | 16 | 24 | 32) {
            self.bit_depth = 16;
        }
        if self.bitrate_kbps != 0 {
            // MP3 Layer III 的合法 CBR 档位是 8..=320；这里给的是包络，具体档位
            // 由 rusty_mp3 自己 snap 到最近的合法值。
            self.bitrate_kbps = self.bitrate_kbps.clamp(8, 320);
        }
        if self.vbr_quality > 9 {
            self.vbr_quality = 9;
        }
        self
    }

    /// WAV 输出的每样本字节数（32 位一律按浮点算）。
    pub fn wav_bytes_per_sample(&self) -> u16 {
        (self.bit_depth / 8).max(1)
    }
}

/// 把任意采样率吸附到白名单里最近的一档。
fn nearest_rate(want: u32) -> u32 {
    const RATES: &[u32] = &[
        8000, 11025, 16000, 22050, 24000, 32000, 44100, 48000, 88200, 96000, 192_000,
    ];
    *RATES
        .iter()
        .min_by_key(|&&r| r.abs_diff(want))
        .unwrap_or(&44_100)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_string_is_the_keep_everything_default() {
        let o = AudioOptions::parse("");
        assert_eq!(o.sample_rate, KEEP);
        assert_eq!(o.channels, 0);
        assert_eq!(o.bit_depth, 16);
        assert_eq!(o.bitrate_kbps, 0);
        assert!(!o.vbr);
    }

    #[test]
    fn full_option_string_round_trips() {
        let o = AudioOptions::parse("rate=48000;ch=1;depth=24;br=320;vbr=1;q=0");
        assert_eq!(o.sample_rate, 48_000);
        assert_eq!(o.channels, 1);
        assert_eq!(o.bit_depth, 24);
        assert_eq!(o.bitrate_kbps, 320);
        assert!(o.vbr);
        assert_eq!(o.vbr_quality, 0);
    }

    #[test]
    fn unknown_keys_are_ignored_and_junk_falls_back() {
        let o = AudioOptions::parse("nope=1;rate=abc;ch=7;depth=13;br=zzz;vbr=maybe;q=99");
        assert_eq!(o.sample_rate, KEEP, "unparsable rate must fall back to keep");
        assert_eq!(o.channels, 0, "channel count 7 is not a thing → keep source");
        assert_eq!(o.bit_depth, 16, "13-bit is not a thing → 16");
        assert_eq!(o.bitrate_kbps, 0);
        assert!(!o.vbr, "only the literal \"1\" turns VBR on");
        assert_eq!(o.vbr_quality, 9);
    }

    #[test]
    fn odd_sample_rate_snaps_to_the_nearest_legal_one() {
        // 44101 是有人真会手打的「差不多 44.1k」；让它变成 44100 而不是被拒。
        assert_eq!(AudioOptions::parse("rate=44101").sample_rate, 44_100);
        // 夹在中间的一律吸附，不留下 12000 这种没有素材的档。
        assert_eq!(AudioOptions::parse("rate=12000").sample_rate, 11_025);
        assert_eq!(AudioOptions::parse("rate=999999").sample_rate, 192_000);
    }

    #[test]
    fn bitrate_is_clamped_to_layer3_envelope() {
        assert_eq!(AudioOptions::parse("br=1000").bitrate_kbps, 320);
        assert_eq!(AudioOptions::parse("br=1").bitrate_kbps, 8);
    }

    #[test]
    fn whitespace_is_tolerated() {
        let o = AudioOptions::parse(" rate = 22050 ; ch = 2 ");
        assert_eq!(o.sample_rate, 22_050);
        assert_eq!(o.channels, 2);
    }
}
