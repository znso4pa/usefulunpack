//! v2v（纯 Rust）解码器的适配层 —— 用它们逐步顶掉 ffmpeg 的 C 解码器。
//!
//! # 为什么从这里开始
//!
//! 本仓的 ffmpeg 是「解码兜底」：9 种容器、20 多个解码器。其中 **VC-1 / WMV3**
//! 是体积最大的一块（arm64 上 `vc1` + `vc1dec` + `vc1dsp` + `vc1_block` + `vc1data`
//! 合计 **302 KB**，两个 ABI 就是 604 KB），而这个 App 的主场景（galgame 里的
//! `.wmv`）恰好就是它 —— 所以先换它，收益最大、验证面也最集中。
//!
//! # 边界：这不是无脑替换
//!
//! v2v 的 VC-1 只做 **Main profile / 渐进 / I-P**：Advanced profile、B 帧、隔行
//! 会**明确报错**。ffmpeg 那条路这些都能解，所以调用方必须把 `Err` 当成
//! 「这条路走不了」而不是「这个文件坏了」—— 见 `convert.rs` 里的兜底。
//!
//! # extradata 从哪来
//!
//! 容器里的 `STRUCT_C`（ASF 的 `Extradata` 属性）。ffmpeg 已经把它解析进
//! `AVCodecParameters::extradata`，所以解封装仍由 ffmpeg 做、只换解码器。
//!
//! # 为什么用 `planes()` 而不是 `frame()`
//!
//! `frame()` 会为了裁剪**每帧分配三个 `Vec`**；`planes()` 返回解码器内部的
//! 参考平面（宏块对齐、未裁剪），配合 [`crate::nv12::planes_to_nv12`] 的
//! 行跨度参数可以**一次拷贝直接裁进 NV12** —— 转码是逐帧跑的，这里省掉的是
//! 每帧一次 1.5 MB 级别的搬运。

use crate::nv12::planes_to_nv12;

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

    /// 解一个包（一个容器样本 = 一帧）。
    ///
    /// `Err` 有两种含义，调用方**都不能**当成「文件损坏」：
    /// ① 这个流用了不支持的档位（Advanced / B 帧 / 隔行）；
    /// ② 位流真的坏了。两种都退回 ffmpeg 就行 —— 它解得了 ①，也报得清 ②。
    pub fn decode(&mut self, packet: &[u8]) -> Result<(), String> {
        self.dec
            .decode_frame(packet)
            .map(|_| ())
            .map_err(|e| format!("VC-1 解码失败：{e}"))
    }

    pub fn width(&self) -> usize {
        self.w
    }

    pub fn height(&self) -> usize {
        self.h
    }

    /// 把**刚解出来的那一帧**写进 NV12 缓冲（`ew × eh`，编码器要的偶数尺寸）。
    ///
    /// 源平面是宏块对齐的（行跨度 = `pw`），所以这里既不用先裁剪、也不用额外分配。
    ///
    /// # Safety
    /// `dst` 至少 `ew * eh * 3 / 2` 字节；必须在 [`Vc1::decode`] 返回 `Ok` 之后调用。
    pub unsafe fn to_nv12(&self, dst: *mut u8, ew: usize, eh: usize) {
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
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 垃圾 extradata 要**报错**而不是 panic —— 这条路径会在真机上遇到各种
    /// 非 VC-1 的 `STRUCT_C`（尤其是 ASF 里塞了别的东西）。
    #[test]
    fn garbage_extradata_is_an_error_not_a_panic() {
        let e = Vc1::new(&[0u8; 4], 640, 480).err();
        assert!(e.is_some(), "全零 extradata 不该建出解码器");
    }

    #[test]
    fn zero_dimensions_are_rejected() {
        assert!(Vc1::new(&[0u8; 4], 0, 480).is_err());
        assert!(Vc1::new(&[0u8; 4], 640, 0).is_err());
    }

    /// 真样本的 `STRUCT_C`（`crates/video-core/testdata/vc1_extradata.bin`，
    /// 由 `ffprobe -show_streams -show_data` 的 `extradata` 字段转成二进制）。
    ///
    /// 没有这个文件就跳过 —— `testdata/` 是本仓约定的不入库目录。
    #[test]
    fn real_extradata_parses_and_agrees_with_container_dims() {
        let p = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("testdata/vc1_extradata.bin");
        let Ok(bytes) = std::fs::read(&p) else {
            eprintln!("跳过：{} 不存在", p.display());
            return;
        };
        let d = Vc1::new(&bytes, 1280, 720).expect("真样本的 STRUCT_C 应该能解析");
        assert_eq!((d.width(), d.height()), (1280, 720));
    }

    /// **逐帧对账**：真样本的 15 个原始包 → v2v 解码 → NV12，
    /// 与 ffmpeg 对同一样本前 15 帧的 `yuv420p` 输出（同样转成 NV12）逐字节比。
    ///
    /// 两个 fixture 都由 ffmpeg 现场导出，生成方式见 `testdata/README`：
    /// - `vc1_packets.bin`：`ffprobe -show_packets -show_data` 的十六进制还原成
    ///   `[u32 长度][载荷]` 的序列 —— 这就是 `convert.rs` 将来从 ffmpeg 解封装
    ///   拿到的东西，一字不差；
    /// - `vc1_ref.yuv`：`ffmpeg -f rawvideo -pix_fmt yuv420p`，逐帧紧排。
    ///
    /// 为什么这条值得留：它把「v2v 的像素 = ffmpeg 的像素」这个前提钉在主机上，
    /// 换解码器时不用等真机就能知道画面有没有变。
    #[test]
    fn real_packets_match_ffmpeg_frame_for_frame() {
        use crate::nv12::planes_to_nv12;

        let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("testdata");
        let (Ok(extra), Ok(pk), Ok(reference)) = (
            std::fs::read(dir.join("vc1_extradata.bin")),
            std::fs::read(dir.join("vc1_packets.bin")),
            std::fs::read(dir.join("vc1_ref.yuv")),
        ) else {
            eprintln!("跳过：testdata 里的 VC-1 fixture 不齐");
            return;
        };

        let (w, h) = (1280usize, 720usize);
        let frame_len = w * h * 3 / 2;
        let ref_frames = reference.len() / frame_len;
        assert!(ref_frames >= 15, "参考帧不够：{ref_frames}");

        let mut dec = Vc1::new(&extra, w as u32, h as u32).expect("建解码器");

        // 参考帧的 yuv420p 三个平面（紧排，跨度 = w）
        let mut want = vec![0u8; frame_len];
        let mut got = vec![0u8; frame_len];

        let mut off = 0usize;
        let mut n = 0usize;
        while off + 4 <= pk.len() {
            let len = u32::from_le_bytes([pk[off], pk[off + 1], pk[off + 2], pk[off + 3]]) as usize;
            off += 4;
            let payload = &pk[off..off + len];
            off += len;

            dec.decode(payload).unwrap_or_else(|e| {
                panic!("第 {n} 个包解不了：{e}（如果是 B 帧/Advanced profile，说明这个样本超出 v2v 的边界）")
            });

            unsafe { dec.to_nv12(got.as_mut_ptr(), w, h) };

            // 参考帧 yuv420p → NV12
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

            if got != want {
                let bad = got.iter().zip(&want).position(|(a, b)| a != b).unwrap();
                let plane = if bad < w * h { "Y" } else { "UV" };
                panic!("第 {n} 帧与 ffmpeg 不一致：首个差异在 {plane} 平面第 {bad} 字节");
            }
            // 防空转：参考帧全零时上面那条比对接了也没意义。
            if n == 0 {
                assert!(want.iter().any(|&b| b != 0), "参考帧全零，这条测试证明不了任何事");
            }
            n += 1;
        }
        assert_eq!(n, 15, "包数不对");
    }
}
