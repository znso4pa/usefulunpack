//! H.264 源的硬解前置：从 MP4 的 `avcC` 取 SPS/PPS，并把 AVCC 样本转成 Annex-B。
//!
//! 为什么需要这两步：
//! * MediaCodec 的 H.264 **解码器要 Annex-B**（每个 NAL 前是 `00 00 00 01`），而 MP4
//!   的样本是 **AVCC**（每个 NAL 前是 `nal_len_size` 字节的**长度**）。两者不通用。
//! * 解码器还要**单独**的 SPS / PPS（`csd-0` / `csd-1`），它们藏在 MP4 的
//!   `AVCDecoderConfigurationRecord`（`avcC` box 的负载，ffmpeg 放在
//!   `AVCodecParameters::extradata`）里。
//!
//! 这里只有纯逻辑，没有 FFI —— 所以主机上 `cargo test` 能覆盖它，真机才要验的是
//! 解码器本身（见 `convert.rs` 的 `H264 源走硬解` 那段）。

/// 从 `extradata` 解析出的 SPS / PPS（都**不带**起始码）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AvcConfig {
    pub sps: Vec<u8>,
    pub pps: Vec<u8>,
    /// 样本里每个 NAL 的长度前缀字节数（1 / 2 / 4）。
    pub nal_len_size: usize,
}

impl AvcConfig {
    /// `extradata` 不是 avcC 就返回 `None`（例如 TS / PS 里的 Annex-B 源）。
    ///
    /// 布局（ISO/IEC 14496-15 `AVCDecoderConfigurationRecord`）：
    /// ```text
    /// [0]      version = 1
    /// [1..4]   profile / compatibility / level
    /// [4]      0xFC | (lengthSizeMinusOne)
    /// [5]      0xE0 | numOfSequenceParameterSets
    /// [6..]    (u16 len, SPS) × numSPS
    /// [..]     numOfPictureParameterSets (u8)
    /// [..]     (u16 len, PPS) × numPPS
    /// ```
    pub fn parse(extra: &[u8]) -> Option<AvcConfig> {
        if extra.len() < 7 || extra[0] != 1 {
            return None;
        }
        let nal_len_size = ((extra[4] & 0x03) as usize) + 1;
        if !matches!(nal_len_size, 1 | 2 | 4) {
            return None;
        }
        let num_sps = (extra[5] & 0x1f) as usize;
        let mut p = 6usize;
        let mut sps: Option<Vec<u8>> = None;
        for _ in 0..num_sps {
            let n = u16::from_be_bytes([*extra.get(p)?, *extra.get(p + 1)?]) as usize;
            p += 2;
            let body = extra.get(p..p + n)?;
            if sps.is_none() {
                sps = Some(body.to_vec());
            }
            p += n;
        }
        let num_pps = *extra.get(p)? as usize;
        p += 1;
        let mut pps: Option<Vec<u8>> = None;
        for _ in 0..num_pps {
            let n = u16::from_be_bytes([*extra.get(p)?, *extra.get(p + 1)?]) as usize;
            p += 2;
            let body = extra.get(p..p + n)?;
            if pps.is_none() {
                pps = Some(body.to_vec());
            }
            p += n;
        }
        Some(AvcConfig { sps: sps?, pps: pps?, nal_len_size })
    }

    /// 一个 AVCC 样本 → Annex-B，写进 `out`（先清空，便于按包复用同一个 `Vec`）。
    ///
    /// 长度字段坏掉（越界 / 为 0）时**整包原样**写出去：解码器自己会报错，比在这里
    /// 静默吞掉一个包好 —— 吞掉的表现是「画面少一帧」，比报错难查得多。
    pub fn to_annexb(&self, sample: &[u8], out: &mut Vec<u8>) {
        out.clear();
        let n = self.nal_len_size;
        let mut i = 0usize;
        while i + n <= sample.len() {
            let mut len = 0usize;
            for k in 0..n {
                len = (len << 8) | sample[i + k] as usize;
            }
            i += n;
            if len == 0 || i + len > sample.len() {
                out.clear();
                out.extend_from_slice(sample);
                return;
            }
            out.extend_from_slice(&[0, 0, 0, 1]);
            out.extend_from_slice(&sample[i..i + len]);
            i += len;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 最小 avcC：1 个 SPS、1 个 PPS，4 字节长度前缀。
    fn avcc(sps: &[u8], pps: &[u8]) -> Vec<u8> {
        let mut v = vec![1, 0x64, 0x00, 0x1f, 0xff, 0xe1];
        v.extend_from_slice(&(sps.len() as u16).to_be_bytes());
        v.extend_from_slice(sps);
        v.push(1);
        v.extend_from_slice(&(pps.len() as u16).to_be_bytes());
        v.extend_from_slice(pps);
        v
    }

    #[test]
    fn parses_sps_and_pps() {
        let cfg = AvcConfig::parse(&avcc(&[0x67, 0x64, 0x00], &[0x68, 0xee])).unwrap();
        assert_eq!(cfg.sps, vec![0x67, 0x64, 0x00]);
        assert_eq!(cfg.pps, vec![0x68, 0xee]);
        assert_eq!(cfg.nal_len_size, 4);
    }

    #[test]
    fn rejects_non_avcc_extradata() {
        // Annex-B 的 extradata（TS/PS 源）首字节是 0x00，不是 version 1。
        assert!(AvcConfig::parse(&[0, 0, 0, 1, 0x67]).is_none());
        // 截断的 avcC：声明了 2 个 SPS 但只有一个。
        let mut truncated = avcc(&[0x67], &[0x68]);
        truncated[5] = 0xe2;
        assert!(AvcConfig::parse(&truncated).is_none());
    }

    #[test]
    fn converts_avcc_to_annexb() {
        let cfg = AvcConfig::parse(&avcc(&[0x67], &[0x68])).unwrap();
        // 两个 NAL：[0x65,0x88] 与 [0x06,0x05]
        let sample = [0, 0, 0, 2, 0x65, 0x88, 0, 0, 0, 2, 0x06, 0x05];
        let mut out = Vec::new();
        cfg.to_annexb(&sample, &mut out);
        assert_eq!(
            out,
            vec![0, 0, 0, 1, 0x65, 0x88, 0, 0, 0, 1, 0x06, 0x05]
        );
    }

    #[test]
    fn keeps_a_sample_with_a_broken_length_field_verbatim() {
        let cfg = AvcConfig::parse(&avcc(&[0x67], &[0x68])).unwrap();
        // 长度声明 99，实际只有 2 字节 —— 越界，整包原样保留。
        let sample = [0, 0, 0, 99, 0x65, 0x88];
        let mut out = Vec::new();
        cfg.to_annexb(&sample, &mut out);
        assert_eq!(out, sample.to_vec());
    }

    #[test]
    fn supports_two_byte_length_prefixes() {
        let mut extra = avcc(&[0x67], &[0x68]);
        extra[4] = 0xfd; // lengthSizeMinusOne = 1 → 2 字节
        let cfg = AvcConfig::parse(&extra).unwrap();
        assert_eq!(cfg.nal_len_size, 2);
        let sample = [0, 2, 0x65, 0x88];
        let mut out = Vec::new();
        cfg.to_annexb(&sample, &mut out);
        assert_eq!(out, vec![0, 0, 0, 1, 0x65, 0x88]);
    }
}
