//! v2v（纯 Rust）解码器的适配层 —— 用它们逐步顶掉 ffmpeg 的 C 解码器。
//!
//! # 现在接了哪些
//!
//! | 枚举 | ffmpeg 的 codec_id | 覆盖率 | 主机对账 |
//! |---|---|---|---|
//! | [`Codec::Vc1`] | `WMV3` | **只到 I / P** —— B 帧明确报错（见下） | 15 帧 **0 差** |
//! | [`Codec::MsMpeg4V3`] | `MSMPEG4V3`（MP43） | 完整（MS-MPEG-4 没有 B 帧） | 15 帧最大 `+1` |
//! | [`Codec::MsMpeg4V2`] | `MSMPEG4V2`（MP42） | 完整 | 15 帧最大 `+1` |
//! | [`Codec::MsMpeg4V1`] | `MSMPEG4V1`（MP41） | 完整（按上游说法） | **验不了**，见下 |
//! | [`Codec::Wmv1`] | `WMV1`（WMV7） | 完整 | 15 帧最大 `+1` |
//! | [`Codec::Wmv2`] | `WMV2`（WMV8） | 完整（要 extradata） | 15 帧 **0 差** |
//!
//! 「完整」= 没有 B 帧，所以解出来的帧就是显示序，不需要重排缓冲。
//!
//! # 那 `+1` 是什么
//!
//! MS-MPEG-4 v1/v2/v3 上，v2v 与 ffmpeg 会在**零星几个像素上差 1**（15 帧
//! 320×240 累计 88 字节，占 0.005%；全部是「我们高 1」）。集中在一片平坦
//! 色块的边缘 —— 两边 `simple_idct` 的取整不同，不是解错了块（真错块会一次
//! 几千字节、差几十）。WMV2 与 VC-1 走的是整数精确的变换，**一字节不差**。
//!
//! 对我们要做的事（再编成 H.264）这点差异完全看不见；对账测试因此允许
//! `|差| ≤ 1` 且限一个极小的字节预算，**结构性错误照样爆**。
//!
//! # MP41 为什么验不了
//!
//! ffmpeg 里**没有 MP41 的编码器**（只有 `msmpeg4v2`/`v3`），造不出对账样本。
//! 上游也是这么记的：V1 的宏块层分支写好了，但至今没有能跑的样本。
//!
//! # 边界：VC-1 这条不是无脑替换
//!
//! v2v 的 VC-1 只做 **Main profile / 渐进 / I-P**：Advanced profile、B 帧、隔行
//! 会**明确报错**（`crates/vc1/src/header.rs` 的 `PictType` 只有 `I` / `P`）。
//! ffmpeg 那条路这些都能解，所以调用方必须把 `Err` 当成「这条路走不了」而不是
//! 「这个文件坏了」—— 见 `convert.rs` 里的兜底。MS-MPEG-4 全家没有这个缺口。
//!
//! # extradata 从哪来
//!
//! 容器里的配置记录。ffmpeg 已经把它解析进 `AVCodecParameters::extradata`，
//! 所以解封装仍由 ffmpeg 做、只换解码器：
//!
//! - **VC-1**：ASF 的 `STRUCT_C`（`SeqHeader::parse`）；
//! - **WMV2**：能力位与 `slice_height` 在前 4 字节里（`Decoder::with_wmv2`）；
//! - **MP41/42/43、WMV1**：不需要 extradata。
//!
//! # 为什么只有一个 `decode_into`
//!
//! 两个 crate 交帧的方式不一样：VC-1 的帧留在解码器里（`planes()` 借出来，零分配），
//! MS-MPEG-4 的 `decode` 把帧**按值**返回、出了调用就没了 —— 而且它的 `Frame` 在
//! 私有模块里，外部连名字都写不出来。所以对外只暴露「解一帧并写进这段缓冲」：
//! 两个实现各自把帧在活着的时候写掉，调用方拿到的就是编码器要的 NV12。
//!
//! 顺带省掉一次全帧拷贝：VC-1 直接按宏块对齐的行跨度裁进目标缓冲，MS-MPEG-4
//! 用它返回的紧排平面填。

use crate::nv12::planes_to_nv12;

/// 这个模块认识哪些编码。
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Codec {
    /// VC-1 / WMV3。**只到 I / P**，B 帧报错。
    Vc1,
    /// MP41。代码有、**主机上验不了**（ffmpeg 没有 MP41 编码器，造不出样本）。
    MsMpeg4V1,
    /// MP42。
    MsMpeg4V2,
    /// MP43（`.wmv` 里最常见的那一种）。
    MsMpeg4V3,
    /// WMV1（WMV7）。
    Wmv1,
    /// WMV2（WMV8）—— 需要 extradata。
    Wmv2,
}

/// 一个纯 Rust 视频解码器。两种解码器的形状不同，对外收成一个。
pub enum Dec {
    Vc1(Vc1),
    Ms(MsMpeg4),
}

impl Dec {
    /// 按编码建解码器。`extradata` 传容器的配置记录（没有就传空）。
    pub fn new(codec: Codec, extradata: &[u8], width: u32, height: u32) -> Result<Self, String> {
        Ok(match codec {
            Codec::Vc1 => Dec::Vc1(Vc1::new(extradata, width, height)?),
            _ => Dec::Ms(MsMpeg4::new(codec, extradata, width, height)?),
        })
    }

    /// 解一个容器样本（= 一帧）并写进 `dst`（NV12，`ew × eh`）。
    ///
    /// `Err` 有两种含义，调用方**都不能**当成「文件损坏」：
    /// ① 这个流用了不支持的档位（VC-1 的 Advanced / B 帧 / 隔行）；
    /// ② 位流真的坏了。
    ///
    /// ⚠️ 现在 `convert.rs` 在**中途**遇到 `Err` 是直接失败，不是退回 ffmpeg ——
    /// 中途换解码器等于整段重来（新解码器没有参考帧），那是另一条路。所以
    /// feature `v2v` 只该在「覆盖率确定够」的编码上打开。
    ///
    /// # Safety
    /// `dst` 至少 `ew * eh * 3 / 2` 字节。
    pub unsafe fn decode_into(
        &mut self,
        packet: &[u8],
        dst: *mut u8,
        ew: usize,
        eh: usize,
    ) -> Result<(), String> {
        match self {
            Dec::Vc1(d) => unsafe { d.decode_into(packet, dst, ew, eh) },
            Dec::Ms(d) => unsafe { d.decode_into(packet, dst, ew, eh) },
        }
    }

    pub fn width(&self) -> usize {
        match self {
            Dec::Vc1(d) => d.w,
            Dec::Ms(d) => d.w,
        }
    }

    pub fn height(&self) -> usize {
        match self {
            Dec::Vc1(d) => d.h,
            Dec::Ms(d) => d.h,
        }
    }
}

/// VC-1 / WMV3 解码器。
pub struct Vc1 {
    dec: vc1::Decoder,
    w: usize,
    h: usize,
}

impl Vc1 {
    /// 从容器 extradata（`STRUCT_C`）建解码器。
    ///
    /// `width` / `height` 用**容器声明的显示尺寸**，不是宏块对齐后的尺寸 ——
    /// 解码器内部按 16 对齐扩，输出时按这里给的裁。
    pub fn new(extradata: &[u8], width: u32, height: u32) -> Result<Self, String> {
        if width == 0 || height == 0 {
            return Err("VC-1 的显示尺寸是 0".into());
        }
        let seq = vc1::SeqHeader::parse(extradata)
            .map_err(|e| format!("VC-1 序列头（STRUCT_C）解析失败：{e}"))?;
        let dec = vc1::Decoder::new(seq, width, height)
            .map_err(|e| format!("VC-1 解码器起不来：{e}"))?;
        Ok(Self {
            dec,
            w: width as usize,
            h: height as usize,
        })
    }

    /// # Safety
    /// 同 [`Dec::decode_into`]。
    unsafe fn decode_into(
        &mut self,
        packet: &[u8],
        dst: *mut u8,
        ew: usize,
        eh: usize,
    ) -> Result<(), String> {
        self.dec
            .decode_frame(packet)
            .map_err(|e| format!("VC-1 解码失败：{e}"))?;
        // 源平面是宏块对齐的（行跨度 = `pw`），所以这里既不用先裁剪、也不用额外分配。
        let p = self.dec.planes();
        let cstride = (p.pw / 2) as isize;
        unsafe {
            planes_to_nv12(
                p.y.as_ptr(),
                p.pw as isize,
                p.u.as_ptr(),
                cstride,
                p.v.as_ptr(),
                cstride,
                dst,
                self.w,
                self.h,
                ew,
                eh,
            )
        };
        Ok(())
    }
}

/// MS-MPEG-4 全家（MP41 / MP42 / MP43 / WMV1 / WMV2）。
///
/// 这套**没有 B 帧**（MS-MPEG-4 的规范里就没有），所以解出来的帧就是显示序，
/// 不需要重排缓冲 —— 这也是它比 VC-1 好换的地方。
pub struct MsMpeg4 {
    dec: msmpeg4::Decoder,
    w: usize,
    h: usize,
}

impl MsMpeg4 {
    pub fn new(codec: Codec, extradata: &[u8], width: u32, height: u32) -> Result<Self, String> {
        if width == 0 || height == 0 {
            return Err("MS-MPEG-4 的显示尺寸是 0".into());
        }
        let ver = match codec {
            Codec::MsMpeg4V1 => msmpeg4::Version::V1,
            Codec::MsMpeg4V2 => msmpeg4::Version::V2,
            Codec::MsMpeg4V3 => msmpeg4::Version::V3,
            Codec::Wmv1 => msmpeg4::Version::Wmv1,
            Codec::Wmv2 => msmpeg4::Version::Wmv2,
            Codec::Vc1 => return Err("VC-1 不走这条".into()),
        };
        // WMV2 的帧头语法要用容器 extradata 前 4 字节里的能力位，别的版本不需要。
        let dec = if ver == msmpeg4::Version::Wmv2 {
            msmpeg4::Decoder::with_wmv2(extradata)
                .map_err(|e| format!("WMV2 的 extradata 用不了：{e}"))?
        } else {
            msmpeg4::Decoder::with_version(ver)
        };
        Ok(Self {
            dec,
            w: width as usize,
            h: height as usize,
        })
    }

    /// # Safety
    /// 同 [`Dec::decode_into`]。
    unsafe fn decode_into(
        &mut self,
        packet: &[u8],
        dst: *mut u8,
        ew: usize,
        eh: usize,
    ) -> Result<(), String> {
        let f = self
            .dec
            .decode(packet, self.w as u32, self.h as u32)
            .map_err(|e| format!("MS-MPEG-4 解码失败：{e}"))?;
        // `f` 是裁剪好的紧排平面（跨度 = 宽），正好能直接喂给 `planes_to_nv12`。
        unsafe {
            planes_to_nv12(
                f.y.as_ptr(),
                self.w as isize,
                f.u.as_ptr(),
                (self.w / 2) as isize,
                f.v.as_ptr(),
                (self.w / 2) as isize,
                dst,
                self.w,
                self.h,
                ew,
                eh,
            )
        };
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::Path;

    fn testdata(name: &str) -> Option<Vec<u8>> {
        std::fs::read(Path::new(env!("CARGO_MANIFEST_DIR")).join("testdata").join(name)).ok()
    }

    /// 垃圾 extradata 要**报错**而不是 panic —— 这条路径会在真机上遇到各种
    /// 不是配置记录的东西。
    #[test]
    fn garbage_extradata_is_an_error_not_a_panic() {
        assert!(Vc1::new(&[0u8; 4], 640, 480).is_err());
        assert!(MsMpeg4::new(Codec::Wmv2, &[0u8; 4], 640, 480).is_err());
        // MP43 不需要 extradata，给垃圾也不该炸
        assert!(MsMpeg4::new(Codec::MsMpeg4V3, &[0xff; 8], 640, 480).is_ok());
    }

    #[test]
    fn zero_dimensions_are_rejected() {
        assert!(Vc1::new(&[0u8; 4], 0, 480).is_err());
        assert!(Vc1::new(&[0u8; 4], 640, 0).is_err());
        assert!(MsMpeg4::new(Codec::MsMpeg4V3, &[], 0, 480).is_err());
        assert!(MsMpeg4::new(Codec::MsMpeg4V3, &[], 640, 0).is_err());
    }

    /// 真样本的 `STRUCT_C`（`testdata/vc1_extradata.bin`，由
    /// `ffprobe -show_streams -show_data` 的 `extradata` 字段转成二进制）。
    #[test]
    fn real_extradata_parses_and_agrees_with_container_dims() {
        let Some(bytes) = testdata("vc1_extradata.bin") else {
            eprintln!("跳过：testdata/vc1_extradata.bin 不存在");
            return;
        };
        let d = Vc1::new(&bytes, 1280, 720).expect("真样本的 STRUCT_C 应该能解析");
        assert_eq!((d.w, d.h), (1280, 720));
    }

    /// 允许的偏差。
    ///
    /// 为什么不能一刀切「逐字节相等」：**MS-MPEG-4 的 v1/v2/v3 在 v2v 那边
    /// 与 ffmpeg 差 ±1**（实测见下面每条的注释）。全部差异都是 `+1`、总量在
    /// 万分之几 —— 这是两边 `simple_idct` 的取整差异，不是解错了块：整片错块
    /// 会一次几千字节、差几十，一测就爆。所以这里**不放过结构性错误**，
    /// 只放过 LSB 取整。
    ///
    /// 真要收紧，先让上游把 `simple_idct` 的取整对齐 —— 那是他们的事。
    struct Allow {
        /// 单字节允许的最大偏差。
        max_delta: i32,
        /// 一帧里允许出现偏差的字节数上限。
        budget: usize,
    }

    /// **逐帧对账**：原始包序列 → v2v 解码 → NV12，
    /// 与 ffmpeg 对同一样本的 `yuv420p` 输出（同样转成 NV12）逐字节比。
    ///
    /// fixture 都由 ffmpeg 现场导出（`testdata/` 是本仓约定的不入库目录）：
    /// - `*_packets.bin`：`ffprobe -show_packets -show_data` 的十六进制还原成
    ///   `[u32 长度][载荷]` 的序列 —— 这就是 `convert.rs` 从 ffmpeg 解封装
    ///   拿到的东西，一字不差；
    /// - `*_ref.yuv`：`ffmpeg -f rawvideo -pix_fmt yuv420p`，逐帧紧排；
    /// - `*_extradata.bin`：`ffprobe -show_streams -show_data` 的 `extradata`，
    ///   只有 WMV2 / VC-1 有。
    ///
    /// 为什么值得留：它把「v2v 的像素 = ffmpeg 的像素」这个前提钉在主机上，
    /// 换解码器时不用等真机就能知道画面有没有变。
    fn check_against_ffmpeg(codec: Codec, extra: &[u8], tag: &str, w: usize, h: usize, allow: Allow) {
        let (Some(pk), Some(reference)) = (
            testdata(&format!("{tag}_packets.bin")),
            testdata(&format!("{tag}_ref.yuv")),
        ) else {
            eprintln!("跳过：testdata 里 {tag} 的 fixture 不齐");
            return;
        };
        let frame_len = w * h * 3 / 2;
        let ref_frames = reference.len() / frame_len;
        assert!(ref_frames > 0, "参考帧是空的");

        let mut dec = Dec::new(codec, extra, w as u32, h as u32).expect("建解码器");
        let mut want = vec![0u8; frame_len];
        let mut got = vec![0u8; frame_len];

        let mut off = 0usize;
        let mut n = 0usize;
        while off + 4 <= pk.len() {
            let len = u32::from_le_bytes([pk[off], pk[off + 1], pk[off + 2], pk[off + 3]]) as usize;
            off += 4;
            let payload = &pk[off..off + len];
            off += len;

            unsafe { dec.decode_into(payload, got.as_mut_ptr(), w, h) }
                .unwrap_or_else(|e| panic!("{tag} 第 {n} 个包解不了：{e}"));

            assert!(n < ref_frames, "{tag} 解出的帧比参考多");
            let rf = &reference[n * frame_len..(n + 1) * frame_len];
            unsafe {
                planes_to_nv12(
                    rf.as_ptr(),
                    w as isize,
                    rf.as_ptr().add(w * h),
                    (w / 2) as isize,
                    rf.as_ptr().add(w * h + w * h / 4),
                    (w / 2) as isize,
                    want.as_mut_ptr(),
                    w,
                    h,
                    w,
                    h,
                );
            }
            let mut bad = 0usize;
            let mut worst = 0i32;
            let mut first = None;
            for i in 0..frame_len {
                let d = got[i] as i32 - want[i] as i32;
                if d != 0 {
                    bad += 1;
                    worst = worst.max(d.abs());
                    first.get_or_insert((i, d));
                }
            }
            if let Some((i, d)) = first {
                let plane = if i < w * h {
                    format!("Y[{},{}]", i / w, i % w)
                } else {
                    format!("UV 平面第 {} 字节", i - w * h)
                };
                assert!(
                    worst <= allow.max_delta && bad <= allow.budget,
                    "{tag} 第 {n} 帧差得太多：{bad} 字节不同（上限 {}），最大 |差| {worst}（上限 {}）；\
                     首个差异 {plane} 差 {d:+}",
                    allow.budget,
                    allow.max_delta
                );
            }
            // 防空转：参考帧全零时上面那条比对接了也没意义。
            if n == 0 {
                assert!(
                    want.iter().any(|&b| b != 0),
                    "{tag} 参考帧全零，这条测试证明不了任何事"
                );
            }
            n += 1;
        }
        assert_eq!(n, ref_frames, "{tag} 帧数与参考不一致");
    }

    /// VC-1：**逐字节相等**（实测 15 帧全 0 差）。v2v 的 VC-1 走整数变换，
    /// 没有 `simple_idct` 那套取整分歧。
    #[test]
    fn vc1_packets_match_ffmpeg_frame_for_frame() {
        let Some(extra) = testdata("vc1_extradata.bin") else {
            eprintln!("跳过：testdata/vc1_extradata.bin 不存在");
            return;
        };
        check_against_ffmpeg(
            Codec::Vc1,
            &extra,
            "vc1",
            1280,
            720,
            Allow {
                max_delta: 0,
                budget: 0,
            },
        );
    }

    /// 以下四条的 fixture 都是**自己用 ffmpeg 合成的**（`testsrc2` 彩条），
    /// 不含任何商业内容，随时可重造：
    ///
    /// ```text
    /// for enc in msmpeg4v3 msmpeg4v2 wmv1 wmv2; do
    ///   ffmpeg -f lavfi -i testsrc2=size=320x240:rate=15 -t 1 \
    ///          -c:v $enc -qscale:v 4 /tmp/$enc.avi
    /// done
    /// ```
    ///
    /// 然后按 `*_packets.bin` / `*_ref.yuv` / `*_extradata.bin` 的格式导出来。
    ///
    /// **MP41 没有 fixture**：ffmpeg 根本没有 MP41 的编码器，造不出样本，
    /// 所以 `Codec::MsMpeg4V1` 这条分支在主机上**验不了**（上游也是这么记的）。
    /// 真机遇到 MP41 就走 v2v，出错还有 ffmpeg 兜底。

    /// MP43：实测 15 帧累计 88 字节差 `+1`（占 0.005%），单帧最多 11 字节。
    #[test]
    fn mp43_packets_match_ffmpeg_frame_for_frame() {
        check_against_ffmpeg(
            Codec::MsMpeg4V3,
            &[],
            "mp43",
            320,
            240,
            Allow {
                max_delta: 1,
                budget: 320 * 240 * 3 / 2 / 2000,
            },
        );
    }

    /// MP42：实测 15 帧累计 87 字节差 `+1`，单帧最多 10 字节。
    #[test]
    fn mp42_packets_match_ffmpeg_frame_for_frame() {
        check_against_ffmpeg(
            Codec::MsMpeg4V2,
            &[],
            "mp42",
            320,
            240,
            Allow {
                max_delta: 1,
                budget: 320 * 240 * 3 / 2 / 2000,
            },
        );
    }

    /// WMV1：实测 15 帧累计 88 字节差 `+1`，单帧最多 11 字节。
    #[test]
    fn wmv1_packets_match_ffmpeg_frame_for_frame() {
        check_against_ffmpeg(
            Codec::Wmv1,
            &[],
            "wmv1",
            320,
            240,
            Allow {
                max_delta: 1,
                budget: 320 * 240 * 3 / 2 / 2000,
            },
        );
    }

    /// WMV2：**逐字节相等**（实测 15 帧全 0 差）—— 它走的是 ffmpeg 的
    /// `wmv2_idct`，那套本身就是整数精确的，两边没有取整分歧。
    #[test]
    fn wmv2_packets_match_ffmpeg_frame_for_frame() {
        let Some(extra) = testdata("wmv2_extradata.bin") else {
            eprintln!("跳过：testdata/wmv2_extradata.bin 不存在");
            return;
        };
        check_against_ffmpeg(
            Codec::Wmv2,
            &extra,
            "wmv2",
            320,
            240,
            Allow {
                max_delta: 0,
                budget: 0,
            },
        );
    }

}
