//! yuv420p → NV12。
//!
//! `AMediaCodec` 的 `color-format = 21`（`COLOR_FormatYUV420SemiPlanar`）要的是
//! 「先整块 Y，再 UV 交错」，而解码器给的通常是 yuv420p（Y/U/V 三块分开）。
//!
//! 编码器不吃奇数尺寸，所以编码用的宽高 `(ew, eh)` 是向上取偶的；多出来的那一
//! 行/一列用**边缘像素**补，而不是补黑 —— 补黑会在右/下边缘拉出一条暗边。
//!
//! # 为什么拆成两个函数
//!
//! 核心 [`planes_to_nv12`] 只认「三个裸平面 + 行跨度」，**不认 ffmpeg 的 `AVFrame`**：
//! 这样主机上（`uu_no_ffmpeg`，没有 ffmpeg 绑定）也能编、也能单测，而 v2v 那条
//! 纯 Rust 解码路径（[`crate::v2v_dec`]）可以直接复用同一份补边逻辑。
//! 第二份实现才是会跑偏的那一份 —— 这个仓已经栽过（见 AGENTS.md 第 10 条）。

/// 三个平面 → NV12。
///
/// 全程裸指针：源可能是 ffmpeg 的 `AVFrame`，也可能是 v2v 解码器吐的 `Vec<u8>`；
/// 目标直接是 `AMediaCodec` 的输入缓冲。两边都不是我们能拥有所有权的 Rust 切片。
///
/// # Safety
/// 调用方保证：
/// - `dst` 至少有 `ew * eh * 3 / 2` 字节；
/// - 三个源平面按 `*_stride` 行跨度可读到 `w × h`（色度 `w/2 × h/2`）；
/// - `w <= ew`、`h <= eh`，且 `w`、`h` 都是偶数（编码器不吃奇数）。
pub unsafe fn planes_to_nv12(
    y_src: *const u8,
    y_stride: isize,
    u_src: *const u8,
    u_stride: isize,
    v_src: *const u8,
    v_stride: isize,
    dst: *mut u8,
    w: usize,
    h: usize,
    ew: usize,
    eh: usize,
) {
    debug_assert!(w <= ew && h <= eh);
    debug_assert!(w >= 2 && h >= 2, "宽高至少 2（色度按 2×2 下采样）");

    // ---- Y：逐行拷贝 + 右侧补边 ----
    for y in 0..h {
        let s = y_src.offset(y as isize * y_stride);
        let d = dst.add(y * ew);
        std::ptr::copy_nonoverlapping(s, d, w);
        let edge = *s.add(w - 1);
        for x in w..ew {
            *d.add(x) = edge;
        }
    }
    // 下面多出来的整行直接复制上一行。
    for y in h..eh {
        let s = dst.add((h - 1) * ew);
        std::ptr::copy_nonoverlapping(s, dst.add(y * ew), ew);
    }

    // ---- UV：两个平面交错成一组，并各自补边 ----
    let uv = dst.add(ew * eh);
    let (cw, ch) = (w / 2, h / 2);
    let (ecw, ech) = (ew / 2, eh / 2);
    for y in 0..ch {
        let u = u_src.offset(y as isize * u_stride);
        let v = v_src.offset(y as isize * v_stride);
        let row = uv.add(y * ecw * 2);
        for x in 0..cw {
            *row.add(2 * x) = *u.add(x);
            *row.add(2 * x + 1) = *v.add(x);
        }
        let (u_edge, v_edge) = (*u.add(cw - 1), *v.add(cw - 1));
        for x in cw..ecw {
            *row.add(2 * x) = u_edge;
            *row.add(2 * x + 1) = v_edge;
        }
    }
    for y in ch..ech {
        let s = uv.add((ch - 1) * ecw * 2);
        std::ptr::copy_nonoverlapping(s, uv.add(y * ecw * 2), ecw * 2);
    }
}

/// ffmpeg 的 `AVFrame`（yuv420p 或同布局的 8bit）→ NV12。
///
/// # Safety
/// 同 [`planes_to_nv12`]；另外 `f` 必须是一帧已解码、三个平面都非空的帧。
#[cfg(not(uu_no_ffmpeg))]
pub unsafe fn frame_to_nv12(
    f: &crate::ffi::AVFrame,
    dst: *mut u8,
    w: usize,
    h: usize,
    ew: usize,
    eh: usize,
) {
    unsafe {
        planes_to_nv12(
            f.data[0],
            f.linesize[0] as isize,
            f.data[1],
            f.linesize[1] as isize,
            f.data[2],
            f.linesize[2] as isize,
            dst,
            w,
            h,
            ew,
            eh,
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 4×2、无需补边：Y 8 字节 + UV 4 字节（2 个色度对）。
    #[test]
    fn tight_4x2_layout_is_y_then_interleaved_uv() {
        let y = [10u8, 11, 12, 13, 20, 21, 22, 23];
        let u = [30u8, 31];
        let v = [40u8, 41];
        let mut out = [0u8; 12];
        unsafe {
            planes_to_nv12(
                y.as_ptr(),
                4,
                u.as_ptr(),
                2,
                v.as_ptr(),
                2,
                out.as_mut_ptr(),
                4,
                2,
                4,
                2,
            );
        }
        assert_eq!(&out[..8], &y);
        // UV 交错：U0 V0 U1 V1
        assert_eq!(&out[8..], &[30, 40, 31, 41]);
    }

    /// 3×2 → 补到 4×2：右侧用边缘像素，**不是补黑**。
    #[test]
    fn right_edge_repeats_last_pixel() {
        let y = [10u8, 11, 12, 20, 21, 22];
        let u = [30u8, 31];
        let v = [40u8, 41];
        let mut out = [0u8; 12];
        unsafe {
            planes_to_nv12(
                y.as_ptr(),
                3,
                u.as_ptr(),
                2,
                v.as_ptr(),
                2,
                out.as_mut_ptr(),
                3,
                2,
                4,
                2,
            );
        }
        // 每行第 4 个像素 = 第 3 个（边缘复制）
        assert_eq!(&out[..4], &[10, 11, 12, 12]);
        assert_eq!(&out[4..8], &[20, 21, 22, 22]);
        // 色度同理：cw=1 → ecw=2，第二个色度对复制第一个
        assert_eq!(&out[8..], &[30, 40, 30, 40]);
    }

    /// 源带行跨度（padding）时只读前 `w` 个字节。
    #[test]
    fn honors_source_stride() {
        // 每行 6 字节，只有前 4 个有效
        let y = [1u8, 2, 3, 4, 99, 99, 5, 6, 7, 8, 99, 99];
        let u = [10u8, 11, 99];
        let v = [20u8, 21, 99];
        let mut out = [0u8; 12];
        unsafe {
            planes_to_nv12(
                y.as_ptr(),
                6,
                u.as_ptr(),
                3,
                v.as_ptr(),
                3,
                out.as_mut_ptr(),
                4,
                2,
                4,
                2,
            );
        }
        assert_eq!(&out[..8], &[1, 2, 3, 4, 5, 6, 7, 8]);
        assert_eq!(&out[8..], &[10, 20, 11, 21]);
    }

    /// 高度需要补时，整行复制上一行（Y 与 UV 各补一行）。
    #[test]
    fn bottom_edge_repeats_last_row() {
        let y = [1u8, 2, 3, 4, 5, 6, 7, 8]; // 4×2
        let u = [10u8, 11]; // 2×1
        let v = [20u8, 21];
        let mut out = [0u8; 24]; // 4×4 → Y 16 + UV 8
        unsafe {
            planes_to_nv12(
                y.as_ptr(),
                4,
                u.as_ptr(),
                2,
                v.as_ptr(),
                2,
                out.as_mut_ptr(),
                4,
                2,
                4,
                4,
            );
        }
        assert_eq!(&out[..16], &[1, 2, 3, 4, 5, 6, 7, 8, 5, 6, 7, 8, 5, 6, 7, 8]);
        // UV 第 2 行复制第 1 行
        assert_eq!(&out[16..], &[10, 20, 11, 21, 10, 20, 11, 21]);
    }
}
