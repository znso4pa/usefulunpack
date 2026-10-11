//! yuv420p → NV12。
//!
//! `AMediaCodec` 的 `color-format = 21`（`COLOR_FormatYUV420SemiPlanar`）要的是
//! 「先整块 Y，再 UV 交错」，而 ffmpeg 给的是 yuv420p（Y/U/V 三块分开）。
//!
//! 编码器不吃奇数尺寸，所以编码用的宽高 `(ew, eh)` 是向上取偶的；多出来的那一
//! 行/一列用**边缘像素**补，而不是补黑 —— 补黑会在右/下边缘拉出一条暗边。
//!
//! 全程裸指针：源是 ffmpeg 的 `AVFrame`，目标直接是 `AMediaCodec` 的输入缓冲，
//! 两边都不是我们能拥有所有权的 Rust 切片。
//!
//! # Safety
//! 调用方保证 `dst` 至少有 `ew * eh * 3 / 2` 字节，`f` 是一帧已解码的
//! yuv420p（或同布局的 8bit）帧，且 `w <= ew`、`h <= eh`。
pub unsafe fn frame_to_nv12(
    f: &crate::ffi::AVFrame,
    dst: *mut u8,
    w: usize,
    h: usize,
    ew: usize,
    eh: usize,
) {
    debug_assert!(w <= ew && h <= eh);

    // ---- Y：逐行拷贝 + 右侧补边 ----
    let y_src = f.data[0];
    let y_stride = f.linesize[0] as isize;
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
    let u_src = f.data[1];
    let v_src = f.data[2];
    let u_stride = f.linesize[1] as isize;
    let v_stride = f.linesize[2] as isize;
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
