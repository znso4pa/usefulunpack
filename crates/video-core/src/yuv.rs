//! `AImage` 的三个 YUV 平面 → MediaCodec 编码器要的 NV12。
//!
//! 硬解解码器的输出面是一个 `AImageReader`，每解出一帧就交给我们一个 `AImage`
//! （`AIMAGE_FORMAT_YUV_420_888`）。它给的是**三个平面 + 行跨度 + 像素跨度**，
//! 而不是一块排好的 NV12：
//!
//! * 行尾可能有对齐填充（`row_stride > width`），逐行拷贝时不能整块 `memcpy`；
//! * U/V 可能是分离的（I420，`pixel_stride == 1`），也可能是交错的（NV12，
//!   `pixel_stride == 2`，此时 U/V 两个指针只差 1 字节）。
//!
//! 这两种都由接口**明说**，所以不用猜 —— 这正是它比「猜 `AMediaCodec` 的
//! ByteBuffer 输出布局」可靠的地方：猜错只会花屏，一个错都不报。
//!
//! 这里只有纯逻辑，没有 FFI，所以主机上 `cargo test` 能覆盖它；真机要验的只是
//! 「设备给的跨度是哪种形状」（见 `convert.rs` 的 `pump_hw_images`）。

/// 一个平面：起始地址、行跨度（相邻两行相距多少字节）、像素跨度（相邻两个采样
/// 相距多少字节）。
pub struct Plane<'a> {
    pub data: &'a [u8],
    pub row_stride: usize,
    pub pixel_stride: usize,
}

/// 一帧 YUV 4:2:0。`width` / `height` 是**这一帧自己的**尺寸（不是编码器要的）。
pub struct YuvImage<'a> {
    pub y: Plane<'a>,
    pub u: Plane<'a>,
    pub v: Plane<'a>,
    pub width: usize,
    pub height: usize,
}

impl YuvImage<'_> {
    /// 写成一帧 NV12（Y 平面 + 交错的 UV），输出尺寸 `out_w × out_h`。
    ///
    /// 尺寸不一致时做最近邻缩放：解码器理应按我们建 `AImageReader` 时的尺寸出图，
    /// 真不一致说明设备把它对齐过 —— 这时缩放比整条转换失败好。
    ///
    /// `dst` 至少要有 `out_w * out_h * 3 / 2` 字节。
    pub fn to_nv12(&self, dst: &mut [u8], out_w: usize, out_h: usize) {
        let need = out_w * out_h * 3 / 2;
        assert!(dst.len() >= need, "NV12 目标缓冲太小：{} < {need}", dst.len());
        if out_w == 0 || out_h == 0 || self.width == 0 || self.height == 0 {
            return;
        }

        // ---- Y ----
        for oy in 0..out_h {
            let sy = scale(oy, self.height, out_h);
            let row = &mut dst[oy * out_w..oy * out_w + out_w];
            for (ox, px) in row.iter_mut().enumerate() {
                let sx = scale(ox, self.width, out_w);
                *px = sample(&self.y, sx, sy);
            }
        }

        // ---- UV ----
        // 色度是半分辨率：源和目标的宽高都除 2 再各自缩放。
        let (cw, ch) = (self.width / 2, self.height / 2);
        let (ocw, och) = (out_w / 2, out_h / 2);
        let uv_base = out_w * out_h;
        for oy in 0..och {
            let sy = scale(oy, ch, och);
            let base = uv_base + oy * out_w;
            for ox in 0..ocw {
                let sx = scale(ox, cw, ocw);
                let i = base + ox * 2;
                dst[i] = sample(&self.u, sx, sy);
                dst[i + 1] = sample(&self.v, sx, sy);
            }
        }
    }
}

/// 目标坐标 → 源坐标（最近邻）。相等时直接返回，省掉一次除法。
fn scale(dst_i: usize, src_n: usize, dst_n: usize) -> usize {
    if src_n == dst_n {
        dst_i
    } else {
        (dst_i * src_n / dst_n).min(src_n.saturating_sub(1))
    }
}

/// 取一个采样。越界补 0：`AImage_getPlaneData` 给的缓冲长度是按行跨度算的，
/// 最后一行可能短一点，这里不值得为此 panic 掉整条转换。
fn sample(p: &Plane<'_>, x: usize, y: usize) -> u8 {
    p.data
        .get(y * p.row_stride + x * p.pixel_stride)
        .copied()
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 一帧 I420：三个平面各自连续，行跨度等于宽度。
    fn i420(w: usize, h: usize, y: u8, u: u8, v: u8) -> (Vec<u8>, Vec<u8>, Vec<u8>) {
        (
            vec![y; w * h],
            vec![u; (w / 2) * (h / 2)],
            vec![v; (w / 2) * (h / 2)],
        )
    }

    #[test]
    fn converts_planar_i420() {
        let (y, u, v) = i420(4, 4, 0x10, 0x20, 0x30);
        let img = YuvImage {
            y: Plane { data: &y, row_stride: 4, pixel_stride: 1 },
            u: Plane { data: &u, row_stride: 2, pixel_stride: 1 },
            v: Plane { data: &v, row_stride: 2, pixel_stride: 1 },
            width: 4,
            height: 4,
        };
        let mut out = vec![0u8; 4 * 4 * 3 / 2];
        img.to_nv12(&mut out, 4, 4);
        assert_eq!(&out[..16], &[0x10; 16], "Y 平面");
        // UV 交错：U,V,U,V…
        assert_eq!(&out[16..], &[0x20, 0x30, 0x20, 0x30, 0x20, 0x30, 0x20, 0x30]);
    }

    #[test]
    fn honours_row_padding() {
        // 行跨度 6、宽 4：每行末尾两个填充字节，必须跳过。
        let y = vec![
            1, 2, 3, 4, 0xEE, 0xEE, //
            5, 6, 7, 8, 0xEE, 0xEE, //
        ];
        // 色度 2×1：每行两个有效采样，后面两个是填充。
        let img = YuvImage {
            y: Plane { data: &y, row_stride: 6, pixel_stride: 1 },
            u: Plane { data: &[9, 9, 0xEE, 0xEE], row_stride: 4, pixel_stride: 1 },
            v: Plane { data: &[10, 10, 0xEE, 0xEE], row_stride: 4, pixel_stride: 1 },
            width: 4,
            height: 2,
        };
        let mut out = vec![0u8; 4 * 2 * 3 / 2];
        img.to_nv12(&mut out, 4, 2);
        assert_eq!(&out[..8], &[1, 2, 3, 4, 5, 6, 7, 8]);
        assert_eq!(&out[8..], &[9, 10, 9, 10]);
    }

    #[test]
    fn handles_interleaved_nv12_planes() {
        // NV12：U/V 两个平面指向同一块缓冲、相差 1 字节，像素跨度 2。
        let uv = [0x20, 0x30, 0x21, 0x31, 0x22, 0x32, 0x23, 0x33];
        let y = vec![7u8; 4 * 4];
        let img = YuvImage {
            y: Plane { data: &y, row_stride: 4, pixel_stride: 1 },
            u: Plane { data: &uv, row_stride: 4, pixel_stride: 2 },
            v: Plane { data: &uv[1..], row_stride: 4, pixel_stride: 2 },
            width: 4,
            height: 4,
        };
        let mut out = vec![0u8; 4 * 4 * 3 / 2];
        img.to_nv12(&mut out, 4, 4);
        assert_eq!(
            &out[16..],
            &[0x20, 0x30, 0x21, 0x31, 0x22, 0x32, 0x23, 0x33],
            "交错平面原样搬到 NV12 的 UV 区"
        );
    }

    #[test]
    fn scales_when_the_image_size_differs() {
        // 4×4 的图缩到 2×2：最近邻取 (0,0) (2,0) / (0,2) (2,2)。
        let y = vec![
            1, 2, 3, 4, //
            5, 6, 7, 8, //
            9, 10, 11, 12, //
            13, 14, 15, 16,
        ];
        let img = YuvImage {
            y: Plane { data: &y, row_stride: 4, pixel_stride: 1 },
            u: Plane { data: &[1, 2, 3, 4], row_stride: 2, pixel_stride: 1 },
            v: Plane { data: &[5, 6, 7, 8], row_stride: 2, pixel_stride: 1 },
            width: 4,
            height: 4,
        };
        let mut out = vec![0u8; 2 * 2 * 3 / 2];
        img.to_nv12(&mut out, 2, 2);
        assert_eq!(&out[..4], &[1, 3, 9, 11]);
        // 色度缩到 1×1：取源的 (0,0)。
        assert_eq!(&out[4..], &[1, 5]);
    }

    #[test]
    fn short_last_row_reads_as_zero_instead_of_panicking() {
        // 数据只够第一行 + 半个第二行 —— 越界补 0，不 panic。
        let y = vec![1, 2, 3, 4, 5, 6];
        let img = YuvImage {
            y: Plane { data: &y, row_stride: 4, pixel_stride: 1 },
            u: Plane { data: &[9], row_stride: 2, pixel_stride: 1 },
            v: Plane { data: &[8], row_stride: 2, pixel_stride: 1 },
            width: 4,
            height: 2,
        };
        let mut out = vec![0u8; 4 * 2 * 3 / 2];
        img.to_nv12(&mut out, 4, 2);
        assert_eq!(&out[..8], &[1, 2, 3, 4, 5, 6, 0, 0]);
    }
}
