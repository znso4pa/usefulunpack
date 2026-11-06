//! WMV/ASF/AVI/FLV/MPEG/MP4/MKV/WebM → MP4 的转换链路。
//!
//! ```text
//!   其余源（VC-1 / WMV3 / MSMPEG4 / MPEG-1·2 / H.263 / VP6 …）
//!     av_read_frame ──► ffmpeg 解码 ──► AVFrame ──► NV12 ──► AMediaCodec(H.264) ─┐
//!                                                                                ├─► AMediaMuxer ──► MP4
//!   H.264 / VP8 / VP9 源（MP4 / MOV / MKV / WebM）
//!     av_read_frame ──► AMediaCodec 硬解 ──► AImageReader ──► AImage ──► NV12 ──► AMediaCodec(H.264) ─┤
//!                                                                                │
//!   任意源
//!     av_read_frame ──► ffmpeg 解码 ──► swresample ──► PCM ──► AMediaCodec(AAC) ──┘
//! ```
//!
//! ffmpeg 只负责**解**（解封装 + VC-1/WMV3/MSMPEG4/MPEG4/WMA/Vorbis 解码），
//! **编**和**封**交给 Android 自己的 `AMediaCodec` / `AMediaMuxer`：
//!
//! * 体积：ffmpeg 自带 mpeg4+aac 编码器的那版两 ABI 合计 6.07 MB，超了 5 MB 门槛；
//!   只留解码器是 3.15 MB。编解码这一半等于白送。
//! * 兼容性：ffmpeg 里**没有** H.264 编码器（libx264 是 GPL），而 MediaCodec 出的是
//!   硬件 H.264 + AAC，播放器兼容性最好。
//!
//! # H.264 / VP8 / VP9 源走硬解（`Ctx::use_hw`）
//!
//! 同上一条的理由反过来：ffmpeg 里也没有它们的**解码器**（H.264 约 1 MB/ABI，
//! VP8+VP9 约 2 MB/ABI）。所以这些源（MP4/MOV 家族、MKV/WebM）由设备自己的
//! `AMediaCodec` 解 —— 三种只差 MIME 与「包要不要转 Annex-B」，见 [`HwCodec`]。
//!
//! 解码器的输出面是**一个 `AImageReader` 的 window**，每渲一帧就 acquire 出一张
//! `AImage` —— 三个平面、行/像素跨度、时间戳全是接口明说的，然后走 `yuv.rs` 转
//! NV12 喂给编码器。形状与 ffmpeg 那条**完全一样**，只有「谁解」不同。
//!
//! 为什么不是「解码器直接渲进编码器的 input surface」（那条路更短、零拷贝）：
//!
//! * 帧全程不落地 → 拿不到 `AImage_getTimestamp`，时间戳只能靠 surface 的缓冲间接
//!   传，B 帧重排之后连「这一帧是哪一帧」都要自己猜；
//! * 旋转矩阵无从处理；
//! * 依赖 `releaseOutputBuffer(render = true)` 这类**只写错不报错**的行为 —— 传
//!   `false` 时解码器照常推进、`dequeue` 照常返回，reader 里却一张图都没有，最后
//!   交出一个 0 字节的 MP4。
//!
//! 代价只是每帧多一次 CPU 读。另一条备选路（让 ffmpeg 自己软解）实测过：
//! `libavcodec.a` 3839 → 4825 KB，两个 ABI 的 `.so` 从 5.79 MB 涨到 7.95 MB，
//! 而且只换掉解码、编码仍要 MediaCodec（libx264 是 GPL）—— 不值。
//!
//! 代价：硬解源只能转 MP4（→ WMV 那条路全程 ffmpeg，见 `asf.rs`），而且这条管线
//! 的真机行为只能上机验（csd、设备给的行跨度是 I420 还是 NV12、时间戳精度都没法在
//! 主机上模拟；纯逻辑部分在 `yuv.rs` 有单测）。
//!
//! # Opus 音轨也走设备解码器（`Ctx::adec_hw`）
//!
//! WebM 现在几乎全是 Opus 音轨（Vorbis 是上一代），而 ffmpeg 的 Opus 解码器**不便宜**：
//! `libavcodec/opus/*.o` 实测 **141.7 KB/ABI**（tab 30.3 + silk 26.0 + dec 17.5 +
//! pvq 15.9 + celt 15.4 + parse 12.5 + rc 12.4 + dec_celt 3.9 + parser 3.9 + dsp 2.3），
//! 两个 ABI 就是 0.28 MB。设备自带的 `audio/opus`（`c2.android.opus.decoder`，**API 29+**）
//! 一个字节都不占，所以 Opus 交给 `AMediaCodec`，其余音频解码器仍归 ffmpeg。
//!
//! 形状与视频硬解**不同**：Opus 解出来就是 PCM，直接进 `swresample` → AAC 编码器，
//! 不经过 `AImageReader`。所以这里没有「第二条管线」，只是把「谁来解」换掉：
//!
//! ```text
//!   Opus 包 ──AMediaCodec(audio/opus)──► 交错 PCM ──swresample──► AAC 编码器
//! ```
//!
//! 四个必须记住的点：
//!
//! * **`pcm-encoding` 要显式要 16 位**。设备默认就是 S16，但那是「默认」不是「保证」；
//!   真给出 float 而我们按 S16 读，声音是一整片噪声 —— 而转换照样「成功」。
//!   `OUTPUT_FORMAT_CHANGED` 时会核对一遍真实值，不一致就按真实值重建重采样器
//!   （见 [`Ctx::opus_format_changed`]）。
//! * **`csd-0` = `CodecPrivate`（OpusHead）**。pre-skip（Matroska 的 `CodecDelay`）
//!   写在里面，不传的话开头会多出约 6.5 ms 的垃圾样本。
//! * **一个输入缓冲放一个 Opus 包**。包最长 1275 字节，设备给的缓冲总够；不够就是
//!   异常，**截断着喂**只会解出噪声，所以宁可报错。
//! * **API 26~28 上没有这个解码器**（`AMediaCodec_createDecoderByType` 返回 NULL）。
//!   那种机器退回「只转视频」—— 和「没有 WMA 解码器」时一样，不报错、不失败。
//!
//! # 两条轨的错位
//!
//! `AMediaMuxer` 必须**两条轨都 addTrack 完**才能 `start()`，而每条轨的格式要到它
//! 第一个输出缓冲才拿得到。中间这段空窗期编码器吐出来的样本先存进 [`Ctx::q`]，
//! `start()` 之后一次倒出去。
//!
//! # 时间戳
//!
//! 视频 PTS 只允许前进，**首帧单独判**（见 [`Ctx::feed_video`]）；音频 PTS 完全由
//! 我们自己按喂进去的采样数累加，不信容器。硬解那条路的规则不一样（解码顺序 vs
//! 显示顺序），见 [`Ctx::feed_hw_packet`]。

use std::collections::VecDeque;
use std::ffi::CString;
use std::os::raw::{c_char, c_int};
use std::os::unix::io::AsRawFd;
use std::time::Instant;

use crate::ffi;
use crate::nv12::frame_to_nv12;
use crate::{video_progress, ConvertError};

type R<T> = Result<T, ConvertError>;

fn msg<S: Into<String>>(s: S) -> ConvertError {
    ConvertError::Message(s.into())
}

/// 编译期把字面量变成 NUL 结尾的 C 字符串，省掉一堆 `CString` 分配。
macro_rules! cstr {
    ($s:literal) => {
        concat!($s, "\0").as_ptr() as *const c_char
    };
}

/// 编码器收尾最多等多久。正常几百毫秒就吐完了，这是兜底。
const FINISH_BUDGET_US: i64 = 5_000_000;

/// 走 MediaCodec 硬解的那几种源。
///
/// 管线**完全共用**（解 → `AImageReader` → 三平面 → NV12 → 编码器），差别只有两处：
/// MIME 不同、以及「包要不要转 Annex-B」。之所以把这三种都塞进硬解而不是交给 ffmpeg：
/// ffmpeg 里 H.264 解码器约 1 MB/ABI、VP8+VP9 加起来约 2 MB/ABI，而设备自带的硬解
/// 是免费的（见 `scripts/build_ffmpeg.sh` 的体积账）。
#[derive(Clone, Copy, PartialEq, Eq)]
enum HwCodec {
    Avc,
    Vp8,
    Vp9,
}

impl HwCodec {
    fn of(codec_id: ffi::AVCodecID::Type) -> Option<Self> {
        match codec_id {
            ffi::AVCodecID::AV_CODEC_ID_H264 => Some(HwCodec::Avc),
            ffi::AVCodecID::AV_CODEC_ID_VP8 => Some(HwCodec::Vp8),
            ffi::AVCodecID::AV_CODEC_ID_VP9 => Some(HwCodec::Vp9),
            _ => None,
        }
    }

    /// 交给 C 侧的 MIME（已经带结尾 NUL，可以直接用）。
    fn mime(self) -> *const c_char {
        match self {
            HwCodec::Avc => cstr!("video/avc"),
            HwCodec::Vp8 => cstr!("video/x-vnd.on2.vp8"),
            HwCodec::Vp9 => cstr!("video/x-vnd.on2.vp9"),
        }
    }

    /// 日志与报错里给人看的名字。
    fn label(self) -> &'static str {
        match self {
            HwCodec::Avc => "H.264",
            HwCodec::Vp8 => "VP8",
            HwCodec::Vp9 => "VP9",
        }
    }

    /// MP4/MOV/MKV 里的 AVC 样本是「长度前缀 + 裸 NAL」，而解码器要起始码，得转一遍。
    /// **VP8/VP9 不要**：它们的包本来就是裸帧，硬扫一遍反而可能把帧数据里碰巧像长度的
    /// 字节当成 NAL 长度改坏。
    fn needs_annexb(self) -> bool {
        matches!(self, HwCodec::Avc)
    }
}

/// AAC 编码器 priming（暖机样本数）。
///
/// AAC-LC 编码器会在码流开头插入两帧暖机，把**音频内容**整体推迟
/// `AAC_PRIMING_SAMPLES / 采样率` 秒。`AMediaMuxer` 不写 `edts`/`elst`（dump 过盒子
/// 确认），没法用编辑列表把这段标掉 —— 音频这一侧改不动，那就把**视频**整体后移
/// 同样的时长，让两条轨的**内容**重新对齐。
///
/// 实测（HONOR CHG-W60 / Android 16）：恰好 2048 样本 = 46.44 ms @44.1 kHz。
/// 它是这台设备 AAC 编码器的性质，换机器可能不同；`KEY_ENCODER_DELAY` 指望不上
/// （API 29+ 才有，且实测这台设备根本不报）。
const AAC_PRIMING_SAMPLES: i64 = 2048;

/// 一个已经编好、等着写进 muxer 的样本。
struct Sample {
    track: isize,
    data: Vec<u8>,
    pts_us: i64,
    flags: u32,
}

struct Ctx {
    ifmt: *mut ffi::AVFormatContext,
    vstream: c_int,
    astream: c_int,
    vdec: *mut ffi::AVCodecContext,
    adec: *mut ffi::AVCodecContext,
    /// 走 v2v 纯 Rust 解码器时的那一个（feature `v2v`）。此时 [`Ctx::vdec`] 是
    /// **空指针** —— 两个解码器不会同时存在。
    ///
    /// 覆盖 VC-1 / WMV3 与 MS-MPEG-4 全家（MP41/42/43、WMV1、WMV2）。选它们的理由
    /// 见 `crate::v2v_dec`：前一种是 ffmpeg 解码器里最大的一块（302 KB/ABI），后一套
    /// 一共 60 KB/ABI 且**没有 B 帧**，换过去零覆盖损失。
    #[cfg(feature = "v2v")]
    v2v: Option<crate::v2v_dec::Dec>,
    /// 音轨走设备解码器（Opus）时的那个解码器；此时 [`Ctx::adec`] 是**空指针**。
    /// 为什么要多这一条、为什么不用 ffmpeg 的 Opus，见模块文档「Opus 音轨也走设备
    /// 解码器」。
    adec_hw: *mut ffi::AMediaCodec,
    /// 喂给重采样器的**源**采样格式。ffmpeg 那条路从 `adec` 读，Opus 这条路只能
    /// 自己记 —— 设备解码器没有 `AVCodecContext`。正常是交错的 S16，
    /// `OUTPUT_FORMAT_CHANGED` 报出别的编码时会跟着改。
    a_src_fmt: ffi::AVSampleFormat::Type,
    /// 同上，源采样率。Opus 内部恒为 48 kHz。
    a_src_rate: c_int,
    /// 同上，源声道数。可能被设备下混，所以以它**报出来的**为准。
    a_src_channels: c_int,

    mux: *mut ffi::AMediaMuxer,
    /// 持有 fd 的所有权：`AMediaMuxer` 只是借用这个 fd，文件不能提前关。
    out_file: Option<std::fs::File>,
    venc: *mut ffi::AMediaCodec,
    aenc: *mut ffi::AMediaCodec,
    vtrack: isize,
    atrack: isize,
    mux_started: bool,
    has_video: bool,
    has_audio: bool,

    q: VecDeque<Sample>,

    last_v_us: i64,
    /// 已经喂进 AAC 编码器的**总帧数**。音频 PTS 由它算出，**不要**逐块累加
    /// 每块的微秒数 —— 那样每块整数截断一次，长文件会累积出十几 ms 的漂移。
    a_frames: i64,
    /// 首帧的时间戳不能拿 `last_v_us` 的初值 0 去比 —— 见 `feed_video`。
    have_v_pts: bool,
    /// 源首帧的 PTS（µs），用来把整条视频轨归零。见 `feed_video` 里那段说明。
    v_base_us: i64,
    /// 视频轨整体后移量（µs）—— AAC priming 补偿，见 [`AAC_PRIMING_SAMPLES`]。
    /// 没有音轨时是 0。`last_v_us` 保持**未后移**的值（单调性判据用它），只在喂给
    /// 编码器、以及收尾给 EOS 时把 `v_shift_us` 加上去。
    v_shift_us: i64,
    last_a_us: i64,
    out_rate: c_int,
    out_channels: c_int,
    vw: c_int,
    vh: c_int,
    frame_dur_us: i64,

    swr: *mut ffi::SwrContext,

    // ---- H.264 / VP8 / VP9 源的硬解（`use_hw`）----
    //
    // 形态和 ffmpeg 那条**一样**：解出一帧 YUV → 转 NV12 → 塞进编码器的输入缓冲。
    // 唯一不同的是「谁解」：ffmpeg 的 AVFrame 换成 MediaCodec 的 `AImage`。
    //
    // 为什么不是「解码器渲进编码器的 input surface」（那条路更短，但更脆）：
    // 帧全程不落地 → 时间戳只能靠 surface 的缓冲时间戳间接传、旋转没法处理、
    // 而且依赖 `releaseOutputBuffer(render = true)` 这类**只写错不报错**的行为。
    // `AImageReader` 把一帧变成看得见的东西（三个平面 + 行/像素跨度 + 时间戳），
    // 代价只是每帧多一次 CPU 读。
    /// 硬解器（H.264 / VP8 / VP9，种类见 [`Ctx::hw_kind`]）。
    /// 它的输出面是 [`Ctx::img_reader`] 的 window，不是编码器。
    hwdec: *mut ffi::AMediaCodec,
    /// 解码器的输出面。每渲一帧就能 acquire 出一个 `AImage`。
    img_reader: *mut ffi::AImageReader,
    /// 解码器已经吐过 EOS 输出标志 —— 收尾循环靠它判断「不用再等了」。
    hw_eos: bool,
    /// 第一帧的设备几何（平面长度 / 行跨度 / 像素跨度）只记一条日志，别刷屏。
    hw_geom_logged: bool,
    /// 源视频走 MediaCodec 硬解（H.264 / VP8 / VP9）。此时 [`Ctx::vdec`] 是空指针。
    use_hw: bool,
    /// 硬解的是哪一种。三种源的管线完全一样，差别只有 MIME 与「要不要转 Annex-B」。
    hw_kind: HwCodec,
    /// `avcC` 解析结果（只有 H.264 硬解时才有）。
    avc: Option<crate::h264::AvcConfig>,
    /// 交给解码器的那一份字节（每包复用，免得每帧分配）。H.264 是转好的 Annex-B，
    /// VP8/VP9 就是源包原样。
    hw_payload: Vec<u8>,
}

impl Drop for Ctx {
    fn drop(&mut self) {
        // 顺序与 C 版一致：先解码器，再编码器，最后封装器。
        unsafe {
            if !self.swr.is_null() {
                ffi::swr_free(&mut self.swr);
            }
            if !self.vdec.is_null() {
                ffi::avcodec_free_context(&mut self.vdec);
            }
            if !self.adec.is_null() {
                ffi::avcodec_free_context(&mut self.adec);
            }
            // 设备解码器（Opus）。和视频那条一样：先 stop 再 delete。
            if !self.adec_hw.is_null() {
                ffi::AMediaCodec_stop(self.adec_hw);
                ffi::AMediaCodec_delete(self.adec_hw);
            }
            // 硬解：**先停解码器（生产者）再停编码器（消费者）**。
            // 解码器还挂着 reader 的 window，先把它停掉，reader 才能安全销毁。
            if !self.hwdec.is_null() {
                ffi::AMediaCodec_stop(self.hwdec);
                ffi::AMediaCodec_delete(self.hwdec);
            }
            if !self.venc.is_null() {
                ffi::AMediaCodec_stop(self.venc);
                ffi::AMediaCodec_delete(self.venc);
            }
            // reader 的 window 归 reader 自己管，**不要**对它 `ANativeWindow_release`。
            if !self.img_reader.is_null() {
                ffi::AImageReader_delete(self.img_reader);
            }
            if !self.aenc.is_null() {
                ffi::AMediaCodec_stop(self.aenc);
                ffi::AMediaCodec_delete(self.aenc);
            }
            if !self.mux.is_null() {
                if self.mux_started {
                    ffi::AMediaMuxer_stop(self.mux);
                }
                ffi::AMediaMuxer_delete(self.mux);
            }
            if !self.ifmt.is_null() {
                ffi::avformat_close_input(&mut self.ifmt);
            }
        }
        // out_file 随结构体 drop，fd 自动关。
    }
}

impl Ctx {
    unsafe fn stream(&self, i: c_int) -> *mut ffi::AVStream {
        *(*self.ifmt).streams.add(i as usize)
    }

    /// 源视频流的**顺时针**显示旋转角（0 / 90 / 180 / 270）。
    ///
    /// 手机竖拍的 MP4 里，帧本身是横的，方向靠容器里的显示矩阵表达。这个矩阵在
    /// `codecpar->coded_side_data` 里（`AV_PKT_DATA_DISPLAYMATRIX`，9 个 `i32`）。
    /// 不读它、又不写回去的话，输出就是躺倒的 —— 帧数、时长、画质全对，只是方向错，
    /// 属于「不报错但明显不对」的那一类。
    ///
    /// **符号别搞反**：`av_display_rotation_get` 返回的是**逆时针**角度，
    /// 而 `AMediaMuxer_setOrientationHint` 要的是**顺时针**角度。取负再规整。
    ///
    /// 拿不到矩阵、矩阵尺寸不对、角度不是 90 的倍数（极少数变形矩阵）→ 一律返回 0，
    /// 也就是「不写旋转提示」。宁可躺倒，也别瞎转一个角度。
    fn stream_rotation_cw(&self) -> c_int {
        if self.vstream < 0 {
            return 0;
        }
        let par = unsafe { (*self.stream(self.vstream)).codecpar };
        if par.is_null() {
            return 0;
        }
        let (sd, n) = unsafe { ((*par).coded_side_data, (*par).nb_coded_side_data) };
        if sd.is_null() || n <= 0 {
            return 0;
        }
        for i in 0..n as usize {
            let e = unsafe { &*sd.add(i) };
            // 一个显示矩阵正好 9 个 i32 = 36 字节；短了就不碰。
            if e.type_ != ffi::AVPacketSideDataType::AV_PKT_DATA_DISPLAYMATRIX
                || e.size < 9 * std::mem::size_of::<i32>()
                || e.data.is_null()
            {
                continue;
            }
            let ccw = unsafe { ffi::av_display_rotation_get(e.data as *const i32) };
            // 逆时针 → 顺时针、规整到 0/90/180/270 的算术在 `rotate` 里（主机可测）。
            // 不是 90 的倍数就**不写**旋转提示：宁可躺倒，也别转一个错的角度。
            return crate::rotate::cw_from_ccw(ccw).unwrap_or(0);
        }
        0
    }

    /// 写一个样本。`track < 0`（那条轨没建起来）时静默丢弃。
    fn mux_write(&self, track: isize, data: &[u8], pts_us: i64, flags: u32) -> R<()> {
        if track < 0 {
            return Ok(());
        }
        let info = ffi::AMediaCodecBufferInfo {
            offset: 0,
            size: data.len() as i32,
            presentationTimeUs: pts_us,
            flags,
        };
        let st = unsafe { ffi::AMediaMuxer_writeSampleData(self.mux, track as usize, data.as_ptr(), &info) };
        if st != ffi::media_status_t::AMEDIA_OK {
            return Err(msg(format!("封装器写入失败：{st}")));
        }
        Ok(())
    }

    /// muxer 还没 start 就排队，start 了就直写。
    fn emit(&mut self, track: isize, data: &[u8], pts_us: i64, flags: u32) -> R<()> {
        if self.mux_started {
            return self.mux_write(track, data, pts_us, flags);
        }
        self.q.push_back(Sample {
            track,
            data: data.to_vec(),
            pts_us,
            flags,
        });
        Ok(())
    }

    /// 两条轨都齐了就 start，并把排队中的样本倒出去。
    ///
    /// `require_all` = 喂帧途中用：`has_audio`/`has_video` 声明的轨还没建起来就继续等
    /// （样本先排进 [`Ctx::q`]）。收尾时传 `false`：源声明有音轨、却一个音频帧都没解出来
    /// （空/坏音轨）时 `atrack` 永远是 -1，严格等下去 muxer 永不启动 → 视频明明是好的，
    /// 却报「没有产出任何数据」。放宽成「有几条轨就起几条」。
    fn maybe_start_muxer(&mut self, require_all: bool) -> R<()> {
        if self.mux_started {
            return Ok(());
        }
        if require_all {
            if self.has_video && self.vtrack < 0 {
                return Ok(());
            }
            if self.has_audio && self.atrack < 0 {
                return Ok(());
            }
        } else if self.vtrack < 0 && self.atrack < 0 {
            return Ok(());
        }
        let st = unsafe { ffi::AMediaMuxer_start(self.mux) };
        if st != ffi::media_status_t::AMEDIA_OK {
            return Err(msg(format!("封装器启动失败：{st}")));
        }
        self.mux_started = true;
        let queued: Vec<Sample> = self.q.drain(..).collect();
        for s in queued {
            self.mux_write(s.track, &s.data, s.pts_us, s.flags)?;
        }
        Ok(())
    }

    /// 排空编码器。`wait_us` 是「愿意等多久」的总预算：
    ///
    /// * `0` —— 非阻塞：一次 `TRY_AGAIN_LATER` 就返回。喂帧途中用它，免得为了等
    ///   编码器把主循环卡住（下一帧喂进去它自然会继续吐）。
    /// * `> 0` —— 轮询到**看见 EOS 输出标志**或超时为止。收尾专用：编码器管道里
    ///   的存货是异步吐出来的，用非阻塞那版会把尾巴整段丢掉（实测在 VFR 样本上
    ///   丢 1~5 帧，末帧还会把视频轨时长缩短）。
    fn drain(&mut self, codec: *mut ffi::AMediaCodec, is_video: bool, wait_us: i64) -> R<()> {
        let deadline = (wait_us > 0).then(|| Instant::now() + std::time::Duration::from_micros(wait_us as u64));
        let poll_us = if wait_us > 0 { 10_000 } else { 0 };
        loop {
            let mut info = ffi::AMediaCodecBufferInfo {
                offset: 0,
                size: 0,
                presentationTimeUs: 0,
                flags: 0,
            };
            let idx = unsafe { ffi::AMediaCodec_dequeueOutputBuffer(codec, &mut info, poll_us) };
            if idx >= 0 {
                let mut osize: usize = 0;
                let buf = unsafe { ffi::AMediaCodec_getOutputBuffer(codec, idx as usize, &mut osize) };
                // **CODEC_CONFIG 缓冲不是样本**。编码器会把 csd（H.264 的 SPS/PPS、AAC 的
                // ASC）单独发一个带 `AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG`（=2）的 buffer，
                // 尺寸同样 > 0 —— 只判 `size > 0` 会把它当普通样本写进封装器：**多一个样本、
                // PTS 错位**（实测症状是「75→76 帧」，正是 priming 补偿实验卡住的地方）。
                // csd 已经通过 `OUTPUT_FORMAT_CHANGED → addTrack` 交给封装器了，这里只释放。
                let is_cfg = info.flags & ffi::BufferFlags::AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG != 0;
                let res = if info.size > 0 && !buf.is_null() && !is_cfg {
                    let slice = unsafe {
                        std::slice::from_raw_parts(buf.add(info.offset as usize), info.size as usize)
                    };
                    let track = if is_video { self.vtrack } else { self.atrack };
                    self.emit(track, slice, info.presentationTimeUs, info.flags)
                } else {
                    Ok(())
                };
                let eos = info.flags & ffi::BufferFlags::AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM != 0;
                unsafe { ffi::AMediaCodec_releaseOutputBuffer(codec, idx as usize, false) };
                res?;
                if eos {
                    return Ok(());
                }
            } else if idx == ffi::CodecInfo::AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED as isize {
                let f = unsafe { ffi::AMediaCodec_getOutputFormat(codec) };
                let track = unsafe { ffi::AMediaMuxer_addTrack(self.mux, f) };
                unsafe { ffi::AMediaFormat_delete(f) };
                if is_video {
                    if self.vtrack < 0 {
                        self.vtrack = track;
                    }
                } else if self.atrack < 0 {
                    self.atrack = track;
                }
                self.maybe_start_muxer(true)?;
            } else if idx == ffi::CodecInfo::AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED as isize {
                // 老 API 的产物，忽略。
            } else {
                // TRY_AGAIN_LATER
                match deadline {
                    Some(d) if Instant::now() >= d => return Ok(()),
                    Some(_) => {}
                    None => return Ok(()),
                }
            }
        }
    }

    /// 收尾：把 EOS 排进输入队列，然后等编码器把管道里的存货全部吐干净。
    ///
    /// 输入队列取不到 buffer 时**不能**拿负数当 index 硬排（那会把 EOS 丢掉，
    /// 编码器就一直留着最后几帧），所以这里先校验再排。
    fn finish_encoder(&mut self, codec: *mut ffi::AMediaCodec, is_video: bool, pts_us: i64) -> R<()> {
        let idx = unsafe { ffi::AMediaCodec_dequeueInputBuffer(codec, 100_000) };
        if idx < 0 {
            return Err(msg(format!("收尾时取不到编码器输入缓冲：{idx}")));
        }
        unsafe {
            ffi::AMediaCodec_queueInputBuffer(
                codec,
                idx as usize,
                0,
                0,
                pts_us as u64,
                ffi::BufferFlags::AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM,
            )
        };
        self.drain(codec, is_video, FINISH_BUDGET_US)
    }

    /// 视频帧是不是走 v2v 的纯 Rust 解码器（feature `v2v`）。
    fn uses_v2v(&self) -> bool {
        #[cfg(feature = "v2v")]
        {
            self.v2v.is_some()
        }
        #[cfg(not(feature = "v2v"))]
        {
            false
        }
    }

    /// 一个容器样本 → v2v 解码 → NV12 → 编码器。
    ///
    /// 解码器**先 `take` 出来再放回**：`feed_nv12` 要 `&mut self`，而它的闭包要
    /// 一个 `&mut Dec` —— 只要解码器还挂在 `self` 上，这两个借用就打架。`Option::take`
    /// 让解码器临时脱离 `self`，闭包借的是局部变量；收尾**出错也放回去**，否则
    /// 下一次调用就没解码器了。
    #[cfg(feature = "v2v")]
    fn feed_v2v(&mut self, pkt: &ffi::AVPacket) -> R<()> {
        let Some(mut dec) = self.v2v.take() else {
            return Err(msg("v2v 的解码器不在"));
        };
        let r = self.feed_v2v_inner(&mut dec, pkt);
        self.v2v = Some(dec);
        r
    }

    #[cfg(feature = "v2v")]
    fn feed_v2v_inner(&mut self, dec: &mut crate::v2v_dec::Dec, pkt: &ffi::AVPacket) -> R<()> {
        let data: &[u8] = if pkt.data.is_null() || pkt.size <= 0 {
            &[]
        } else {
            unsafe { std::slice::from_raw_parts(pkt.data, pkt.size as usize) }
        };
        // PTS 的口径必须和 `feed_video` 一模一样，否则两条解码路的时间轴会差一截。
        let mut us = pkt.pts;
        if us == ffi::AV_NOPTS_VALUE {
            us = -1;
        } else {
            let tb = unsafe { (*self.stream(self.vstream)).time_base };
            us = unsafe { ffi::av_rescale_q(us, tb, ffi::AVRational { num: 1, den: 1_000_000 }) };
        }
        let pts = self.video_pts(us);
        let (vw, vh) = (self.vw as usize, self.vh as usize);
        // 解码**必须在闭包里**：v2v 的解码器把帧按值交出来，出了这个调用就没了，
        // 所以「解一帧 + 写进编码器缓冲」得是同一个调用（省一次全帧拷贝）。
        self.feed_nv12(pts, |dst| unsafe {
            dec.decode_into(data, dst.as_mut_ptr(), vw, vh).map_err(msg)
        })
    }

    /// 没编 `v2v` feature 时的占位。留着它是为了让主循环那段 `else if` 在两个
    /// 构建下都过类型检查 —— `uses_v2v()` 恒 false，这里永远不会被调用。
    #[cfg(not(feature = "v2v"))]
    fn feed_v2v(&mut self, _pkt: &ffi::AVPacket) -> R<()> {
        Err(msg("这个构建没有编进 v2v 解码器"))
    }

    fn feed_video(&mut self, f: &ffi::AVFrame) -> R<()> {
        let mut us = f.best_effort_timestamp;
        if us == ffi::AV_NOPTS_VALUE {
            us = f.pts;
        }
        if us == ffi::AV_NOPTS_VALUE {
            us = -1;
        } else {
            let tb = unsafe { (*self.stream(self.vstream)).time_base };
            us = unsafe { ffi::av_rescale_q(us, tb, ffi::AVRational { num: 1, den: 1_000_000 }) };
        }
        // 归零 / 单调前进的规则见 `video_pts` 上的注释。
        let pts = self.video_pts(us);
        let (fw, fh) = (f.width as usize, f.height as usize);
        let (vw, vh) = (self.vw as usize, self.vh as usize);
        self.feed_nv12(pts, |dst| {
            unsafe { frame_to_nv12(f, dst.as_mut_ptr(), fw, fh, vw, vh) };
            Ok(())
        })
    }

    /// 把源时间戳归一化成容器里的 PTS（µs）：减去归零点、并保证只前进。
    ///
    /// `us < 0` 表示这一帧没带时间戳。归零点取**第一条显示出来的帧**的时间戳：
    /// 硬解那边 `AImage` 已经是显示顺序，第一条就是最小的一条；ffmpeg 那边解出来的
    /// 也是显示顺序。两边同一个口径，所以只有这一份实现。
    ///
    /// 为什么必须归一化：源里那个起始偏移是复用器的 muxdelay，不是内容真的晚开始
    /// （实测 MPEG-PS 从 0.54 s 起、MPEG-TS 从 1.44 s 起、AVI/FLV 从 0 起）。不减掉
    /// 它，视频轨从 0.54 s 开始、而音频轨按采样数从 0 累加，两条轨整整错开一个
    /// muxdelay —— 画面和声音对不上，容器时长还凭空多出这一段。
    ///
    /// 首帧要**单独判**：`last_v_us` 初值是 0，直接写 `us <= last_v_us` 会把源里合法
    /// 的首帧 PTS=0 当成「回退」推后一帧，然后级联下去 —— 整条视频平移一个帧长，
    /// 末帧还会被挤出容器（实测 455 帧的样本变成 454 帧）。
    fn video_pts(&mut self, us: i64) -> i64 {
        if !self.have_v_pts {
            self.have_v_pts = true;
            self.v_base_us = if us >= 0 { us } else { 0 };
            self.last_v_us = 0;
            return 0;
        }
        let mut t = if us >= 0 { us - self.v_base_us } else { -1 };
        if t < 0 || t <= self.last_v_us {
            t = self.last_v_us + self.frame_dur_us;
        }
        self.last_v_us = t;
        t
    }

    /// 把一帧 NV12 喂进编码器。`fill` 负责把像素写进编码器的输入缓冲。
    ///
    /// 三条解码路（ffmpeg 的 `AVFrame` / MediaCodec 硬解的 `AImage` / v2v 的纯 Rust
    /// 解码器）都走这里，所以缓冲大小检查与 AAC priming 的后移量只有一份实现。
    ///
    /// `fill` 可以返回错误：v2v 那条路是**边解边填**（它的解码器把帧按值交出来，
    /// 出函数就没了，所以必须在这一个调用里解完并写进缓冲）。
    ///
    /// `fill` 是 `FnMut` 而不是 `Fn`：v2v 的解码器要 `&mut self`，而它就被捕获在
    /// 这个闭包里。
    fn feed_nv12<F>(&mut self, us: i64, mut fill: F) -> R<()>
    where
        F: FnMut(&mut [u8]) -> R<()>,
    {
        let venc = self.venc;
        // 后移量只在喂编码器时加上：`last_v_us` 存的是**未后移**的值，否则
        // 「时间戳只允许前进」的判据会被自己的后移污染。见 `AAC_PRIMING_SAMPLES`。
        let shift = self.v_shift_us;
        let need = (self.vw as usize) * (self.vh as usize) * 3 / 2;
        loop {
            if video_progress::cancelled() {
                return Err(ConvertError::Cancelled);
            }
            let idx = unsafe { ffi::AMediaCodec_dequeueInputBuffer(venc, 10_000) };
            if idx >= 0 {
                let mut cap: usize = 0;
                let buf = unsafe { ffi::AMediaCodec_getInputBuffer(venc, idx as usize, &mut cap) };
                if buf.is_null() || cap < need {
                    unsafe { ffi::AMediaCodec_queueInputBuffer(venc, idx as usize, 0, 0, us as u64, 0) };
                    return Err(msg(format!("H.264 编码器输入缓冲太小（{cap} < {need}）")));
                }
                let dst = unsafe { std::slice::from_raw_parts_mut(buf, need) };
                fill(dst)?;
                unsafe {
                    ffi::AMediaCodec_queueInputBuffer(venc, idx as usize, 0, need, (us + shift) as u64, 0)
                };
                return self.drain(venc, true, 0);
            }
            if idx == ffi::CodecInfo::AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED as isize
                || idx == ffi::CodecInfo::AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED as isize
            {
                self.drain(venc, true, 0)?;
                continue;
            }
            // TRY_AGAIN_LATER：先把编码器吐出来的清掉再等。
            self.drain(venc, true, 0)?;
        }
    }

    /// 排**一个**硬解解码器的输出缓冲，把它渲进 [`Ctx::img_reader`]。
    ///
    /// 返回 `true` 表示渲进去了一帧（调用方要立刻去 acquire），`false` 表示这一刻
    /// 没有了（或已经 EOS）。
    ///
    /// **release 必须带 `render = true`**：解码器的输出面是 [`Ctx::img_reader`] 的
    /// window，只有 release 时把 render 置位，帧才会被投递过去、变成一张可 acquire
    /// 的 `AImage`。传 `false` 不会报错 —— 解码器照常推进、`dequeue` 照常返回，
    /// reader 里却一张图都没有，最后交出一个 0 字节的 MP4。
    ///
    /// `poll_us` 是 `dequeueOutputBuffer` 的阻塞时长（0 = 不阻塞）。
    fn render_one(&mut self, poll_us: i64) -> R<bool> {
        let dec = self.hwdec;
        loop {
            let mut info = ffi::AMediaCodecBufferInfo {
                offset: 0,
                size: 0,
                presentationTimeUs: 0,
                flags: 0,
            };
            let idx = unsafe { ffi::AMediaCodec_dequeueOutputBuffer(dec, &mut info, poll_us) };
            if idx >= 0 {
                let eos = info.flags & ffi::BufferFlags::AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM != 0;
                unsafe { ffi::AMediaCodec_releaseOutputBuffer(dec, idx as usize, true) };
                if eos {
                    self.hw_eos = true;
                    return Ok(false);
                }
                return Ok(true);
            } else if idx == ffi::CodecInfo::AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED as isize {
                // 硬解器的输出格式（色彩格式 / 尺寸）我们不从这条路取 —— 每一帧的
                // 真实尺寸和跨度由 `AImage_get*` 明说。但这个对象是新建的，必须释放。
                let f = unsafe { ffi::AMediaCodec_getOutputFormat(dec) };
                if !f.is_null() {
                    // 诊断用：真机上色度平面为什么是空的，只有这一行能说清。
                    let mut cf: c_int = -1;
                    let mut stride: c_int = -1;
                    let mut slice: c_int = -1;
                    let mut ow: c_int = -1;
                    let mut oh: c_int = -1;
                    unsafe {
                        ffi::AMediaFormat_getInt32(f, cstr!("color-format"), &mut cf);
                        ffi::AMediaFormat_getInt32(f, cstr!("stride"), &mut stride);
                        ffi::AMediaFormat_getInt32(f, cstr!("slice-height"), &mut slice);
                        ffi::AMediaFormat_getInt32(f, cstr!("width"), &mut ow);
                        ffi::AMediaFormat_getInt32(f, cstr!("height"), &mut oh);
                    }
                    ffi::log_err(&format!(
                        "hw outfmt color-format=0x{cf:x} ({cf}) stride={stride} slice-height={slice} {ow}x{oh}"
                    ));
                    unsafe { ffi::AMediaFormat_delete(f) };
                }
            } else if idx == ffi::CodecInfo::AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED as isize {
                // 老 API 的产物，忽略。
            } else {
                // TRY_AGAIN_LATER
                return Ok(false);
            }
        }
    }

    /// 硬解路径：把一个源包喂给 MediaCodec 解码器，再把能出的帧取走。
    ///
    /// 与 [`Ctx::feed_video`] 的区别只有一条：**这里不做时间戳归一化**。拿到的是
    /// **解码顺序**的包 PTS（B 帧会让它来回跳），真正的显示顺序要等解码器吐帧时
    /// 由 `AImage_getTimestamp` 带回来 —— 那一步在 [`Ctx::pump_hw_images`] 里做。
    /// 所以喂进去的 pts 只是「让解码器能把它带回输出」，归一化在出帧那边做。
    fn feed_hw_packet(&mut self, pkt: &ffi::AVPacket) -> R<()> {
        let mut us = (*pkt).pts;
        if us == ffi::AV_NOPTS_VALUE {
            us = (*pkt).dts;
        }
        if us != ffi::AV_NOPTS_VALUE {
            let tb = unsafe { (*self.stream(self.vstream)).time_base };
            us = unsafe { ffi::av_rescale_q(us, tb, ffi::AVRational { num: 1, den: 1_000_000 }) };
        }
        // 拿不到时间戳的包（极少见）：喂 0 进去，出帧那边看到 0 会按「上一帧 + 一个
        // 帧长」补（见 `feed_image`）。解码器只接受 u64，负数进不去。
        let pts = us.max(0) as u64;

        {
            let data = unsafe {
                if (*pkt).data.is_null() || (*pkt).size <= 0 {
                    &[][..]
                } else {
                    std::slice::from_raw_parts((*pkt).data, (*pkt).size as usize)
                }
            };
            if data.is_empty() {
                return Ok(());
            }
            if self.hw_kind.needs_annexb() {
                let avc = self.avc.as_ref().ok_or_else(|| msg("硬解路径缺 avcC 配置"))?;
                avc.to_annexb(data, &mut self.hw_payload);
            } else {
                // VP8/VP9 的包本来就是裸帧，原样交给解码器 —— **别**扫长度前缀。
                self.hw_payload.clear();
                self.hw_payload.extend_from_slice(data);
            }
        }
        if self.hw_payload.is_empty() {
            return Ok(());
        }

        let dec = self.hwdec;
        let need = self.hw_payload.len();
        loop {
            if video_progress::cancelled() {
                return Err(ConvertError::Cancelled);
            }
            let idx = unsafe { ffi::AMediaCodec_dequeueInputBuffer(dec, 10_000) };
            if idx >= 0 {
                let mut cap: usize = 0;
                let buf = unsafe { ffi::AMediaCodec_getInputBuffer(dec, idx as usize, &mut cap) };
                if buf.is_null() || cap < need {
                    unsafe { ffi::AMediaCodec_queueInputBuffer(dec, idx as usize, 0, 0, pts, 0) };
                    return Err(msg(format!(
                        "{} 解码器输入缓冲太小（{cap} < {need}）",
                        self.hw_kind.label()
                    )));
                }
                unsafe {
                    std::ptr::copy_nonoverlapping(self.hw_payload.as_ptr(), buf, need);
                    ffi::AMediaCodec_queueInputBuffer(dec, idx as usize, 0, need, pts, 0);
                }
                return self.pump_hw_images(0);
            }
            if idx == ffi::CodecInfo::AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED as isize
                || idx == ffi::CodecInfo::AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED as isize
            {
                self.pump_hw_images(0)?;
                continue;
            }
            // TRY_AGAIN_LATER：输入槽取不到，几乎总是「输出面没缓冲」——解码器渲不
            // 进去就不肯收新包。所以这里必须把 reader 里的图取走（`pump_hw_images`
            // 内部先排解码器再取图），否则下一轮还是取不到，死循环。
            self.pump_hw_images(0)?;
        }
    }

    /// 把解码器此刻能出的帧全部取出来、转 NV12、喂进编码器。
    ///
    /// **渲一帧就立刻取走**，不要「先把解码器排空、再统一 acquire」：reader 只有
    /// `AImageReader_new` 给的那么几个槽，解码器一次吐出 5 帧以上时，多出来的那几帧
    /// 在 `releaseOutputBuffer(render = true)` 时无处可去，会被**静默丢掉**
    /// （实测 90 帧的源只出来 41 帧，全程零警告）。交错着做就没有这个窗口。
    fn pump_hw_images(&mut self, wait_us: i64) -> R<()> {
        let poll_us = if wait_us > 0 { 10_000 } else { 0 };
        let deadline = (wait_us > 0)
            .then(|| Instant::now() + std::time::Duration::from_micros(wait_us as u64));
        loop {
            if video_progress::cancelled() {
                return Err(ConvertError::Cancelled);
            }
            let rendered = self.render_one(poll_us)?;
            self.acquire_images()?;
            if self.hw_eos {
                break;
            }
            if !rendered {
                match deadline {
                    // 还在等的窗口里：解码器可能只是慢一拍。
                    Some(d) if Instant::now() < d => continue,
                    _ => break,
                }
            }
        }
        let venc = self.venc;
        self.drain(venc, true, 0)
    }

    /// 把 reader 里此刻能拿到的图全部取走、转 NV12、喂编码器。
    fn acquire_images(&mut self) -> R<()> {
        let reader = self.img_reader;
        let (vw, vh) = (self.vw, self.vh);
        loop {
            if video_progress::cancelled() {
                return Err(ConvertError::Cancelled);
            }
            let mut img: *mut ffi::AImage = std::ptr::null_mut();
            let st = unsafe { ffi::AImageReader_acquireNextImage(reader, &mut img) };
            if st != ffi::media_status_t::AMEDIA_OK || img.is_null() {
                // NO_BUFFER_AVAILABLE：这一刻没有了。**不是**流结束 —— 那是 EOS 标志。
                break;
            }
            let r = self.feed_image(img, vw, vh);
            unsafe { ffi::AImage_delete(img) };
            r?;
        }
        Ok(())
    }

    /// 一张 `AImage` → NV12 → 编码器。
    ///
    /// 时间戳用 `AImage_getTimestamp`（纳秒）：解码器把喂进去的 pts 原样写在缓冲上，
    /// 而缓冲是按**显示顺序**交出来的 —— 这就是 B 帧重排之后我们需要的那个值。
    fn feed_image(&mut self, img: *mut ffi::AImage, vw: c_int, vh: c_int) -> R<()> {
        let (mut w, mut h, mut ts_ns, mut nplanes) = (0 as c_int, 0 as c_int, 0i64, 0 as c_int);
        unsafe {
            ffi::AImage_getWidth(img, &mut w);
            ffi::AImage_getHeight(img, &mut h);
            ffi::AImage_getTimestamp(img, &mut ts_ns);
            ffi::AImage_getNumberOfPlanes(img, &mut nplanes);
        }
        if nplanes < 3 || w <= 0 || h <= 0 {
            return Err(msg(format!("硬解输出的图像不可用（{w}×{h}，{nplanes} 个平面）")));
        }
        let mut ptrs = [std::ptr::null_mut::<u8>(); 3];
        let mut lens = [-1 as c_int; 3];
        let mut rows = [-1 as c_int; 3];
        let mut px = [-1 as c_int; 3];
        let mut st_data = [ffi::media_status_t::AMEDIA_ERROR_UNKNOWN; 3];
        let mut st_row = [ffi::media_status_t::AMEDIA_ERROR_UNKNOWN; 3];
        let mut st_px = [ffi::media_status_t::AMEDIA_ERROR_UNKNOWN; 3];
        for i in 0..3usize {
            unsafe {
                st_data[i] = ffi::AImage_getPlaneData(img, i as c_int, &mut ptrs[i], &mut lens[i]);
                st_row[i] = ffi::AImage_getPlaneRowStride(img, i as c_int, &mut rows[i]);
                st_px[i] = ffi::AImage_getPlanePixelStride(img, i as c_int, &mut px[i]);
            }
        }
        // 第一帧把设备给的几何记一条日志：真机上「行跨度是 I420 还是 NV12」只能这么确认。
        // 只在第一次打，长片不会刷屏。
        //
        // **不要**在这里调 `AImage_getHardwareBuffer` + `AHardwareBuffer_describe` 去问
        // 底层格式：那个多出来的引用会让 reader 的槽位计数错乱，`AImageReader_delete`
        // 时在 `ConsumerBase::onBuffersReleased` 里空指针崩掉（实测两次，2026-11-05）。
        // 需要看真实格式时用 `AMediaCodec_getOutputFormat` 那一条日志，够用。
        if !self.hw_geom_logged {
            self.hw_geom_logged = true;
            ffi::log_err(&format!(
                "hw img {w}x{h} n={nplanes} ts={ts_ns}\n  \
                 Y ptr={:p} len={} row={} px={} st=({},{},{})\n  \
                 U ptr={:p} len={} row={} px={} st=({},{},{})\n  \
                 V ptr={:p} len={} row={} px={} st=({},{},{})",
                ptrs[0], lens[0], rows[0], px[0], st_data[0] as i32, st_row[0] as i32, st_px[0] as i32,
                ptrs[1], lens[1], rows[1], px[1], st_data[1] as i32, st_row[1] as i32, st_px[1] as i32,
                ptrs[2], lens[2], rows[2], px[2], st_data[2] as i32, st_row[2] as i32, st_px[2] as i32,
            ));
        }
        if ptrs[0].is_null() || lens[0] <= 0 {
            return Err(msg("硬解输出的 Y 平面为空"));
        }
        // 三个平面**逐个**校验，不能只看 Y。`from_raw_parts(null, n>0)` 是 UB：切片拿到
        // 空指针后，`yuv::sample` 的 `.get()` 只会越界判负、不会救回来 —— 真机上是
        // `ldrb w8, [x30, x15]` 而 x30 == 0，直接 SIGSEGV（2026-11-05 实测）。
        //
        // ---- 色度拿不到 = 这个解码器不给可读的 YUV，直接说清楚 ----
        //
        // 真机实测（`c2.qti.avc.decoder`，API 36，2026-11-05）：
        //   `AMediaCodec_getOutputFormat` 报 `color-format=0x7F000789`（YUV420Flexible），
        //   但底层 `AHardwareBuffer` 的**真实格式是 0x7FA30C06**（高通私有 tiled），
        //   Y 平面有数据，U/V 是 `ptr=null / len=1 / stride=0 / px=0`，三个调用都返回
        //   AMEDIA_OK。也就是说解码器确实渲进去了，只是没把色度平面的布局暴露出来。
        //
        // **不要**按「UV 紧跟 Y、U/V 差 1 字节」硬算：tiled 缓冲的总大小和排布都不是
        // 那个约定，硬读会越界（实测 SIGSEGV），侥幸读到也是花屏。
        if ptrs[1].is_null() || ptrs[2].is_null() {
            return Err(msg(format!(
                "这个解码器不给可读的 YUV 色度平面（{w}×{h}，色度 len={}/{}）",
                lens[1], lens[2]
            )));
        }
        // 三个平面指针的有效期到 `AImage_delete` 为止 —— 调用方紧接着就删，别存。
        let plane = |i: usize| crate::yuv::Plane {
            data: unsafe { std::slice::from_raw_parts(ptrs[i], lens[i].max(0) as usize) },
            row_stride: rows[i].max(0) as usize,
            pixel_stride: px[i].max(1) as usize,
        };
        let frame = crate::yuv::YuvImage {
            y: plane(0),
            u: plane(1),
            v: plane(2),
            width: w as usize,
            height: h as usize,
        };
        // 时间戳为 0 当作「没带」：交给 `video_pts` 按帧长补。
        let us = if ts_ns > 0 { ts_ns / 1000 } else { -1 };
        let pts = self.video_pts(us);
        let (ow, oh) = (vw.max(2) as usize, vh.max(2) as usize);
        self.feed_nv12(pts, |dst| {
            frame.to_nv12(dst, ow, oh);
            Ok(())
        })
    }

    /// 硬解收尾：先让解码器把尾巴吐干净、帧全部取走，再走**和软解同一条**编码器收尾。
    ///
    /// 顺序不能反：解码器里还压着几帧（B 帧重排的尾巴），先关编码器它们就无处可去。
    fn finish_hw(&mut self) -> R<()> {
        let dec = self.hwdec;
        let idx = unsafe { ffi::AMediaCodec_dequeueInputBuffer(dec, 100_000) };
        if idx < 0 {
            return Err(msg(format!("收尾时取不到解码器输入缓冲：{idx}")));
        }
        unsafe {
            ffi::AMediaCodec_queueInputBuffer(
                dec,
                idx as usize,
                0,
                0,
                self.last_v_us.max(0) as u64,
                ffi::BufferFlags::AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM,
            )
        };
        // 等到解码器吐 EOS 为止。每一轮都要把图取走，不然 reader 满了解码器就停住。
        let deadline = Instant::now() + std::time::Duration::from_micros(FINISH_BUDGET_US as u64);
        while !self.hw_eos && Instant::now() < deadline {
            self.pump_hw_images(20_000)?;
        }
        // 收个尾：EOS 之后 reader 里可能还有最后几张。
        self.pump_hw_images(0)?;
        let venc = self.venc;
        // EOS 也要带上后移量，否则编码器会把「末帧时间戳回退」当成异常。
        self.finish_encoder(venc, true, self.last_v_us + self.v_shift_us)
    }

    /// 喂一段交错的 S16 PCM。一个 PCM 帧不能从中间切断（切了左右声道就错位），
    /// 所以按「能整除一个帧」的粒度往输入缓冲里塞。
    fn feed_audio(&mut self, pcm: &[u8]) -> R<()> {
        let frame_bytes = (self.out_channels as usize) * 2;
        let aenc = self.aenc;
        let mut off = 0usize;
        while off < pcm.len() {
            if video_progress::cancelled() {
                return Err(ConvertError::Cancelled);
            }
            let idx = unsafe { ffi::AMediaCodec_dequeueInputBuffer(aenc, 10_000) };
            if idx >= 0 {
                let mut cap: usize = 0;
                let buf = unsafe { ffi::AMediaCodec_getInputBuffer(aenc, idx as usize, &mut cap) };
                if buf.is_null() {
                    return Err(msg("AAC 编码器输入缓冲不可用"));
                }
                let mut n = (pcm.len() - off).min(cap);
                n -= n % frame_bytes;
                if n == 0 {
                    unsafe {
                        ffi::AMediaCodec_queueInputBuffer(aenc, idx as usize, 0, 0, self.last_a_us as u64, 0)
                    };
                    return Err(msg(format!("AAC 编码器输入缓冲太小（{cap}）")));
                }
                // PTS = 「已喂进去的总帧数」的精确时间，**不是**逐块累加每块的微秒 ——
                // 后者每块截断一次，长文件会累积出十几 ms 漂移（音轨慢慢落在视频后面）。
                let pts = self.a_frames * 1_000_000 / self.out_rate as i64;
                unsafe {
                    std::ptr::copy_nonoverlapping(pcm.as_ptr().add(off), buf, n);
                    ffi::AMediaCodec_queueInputBuffer(aenc, idx as usize, 0, n, pts as u64, 0);
                }
                self.a_frames += (n / frame_bytes) as i64;
                self.last_a_us = self.a_frames * 1_000_000 / self.out_rate as i64;
                off += n;
                self.drain(aenc, false, 0)?;
            } else {
                self.drain(aenc, false, 0)?;
            }
        }
        Ok(())
    }

    /// 建（或重建）重采样器：源 → 交错的 S16 / [`Ctx::out_rate`] / [`Ctx::out_channels`]。
    ///
    /// 源参数按「谁在解」分两种：ffmpeg 的解码器从 `adec` 读；Opus 走设备解码器，
    /// 只能从 [`Ctx::a_src_fmt`] / [`Ctx::a_src_rate`] / [`Ctx::a_src_channels`] 拿。
    ///
    /// 返回 false = 建不起来。调用方按「只转视频」处理 —— 这从来不是致命错误，
    /// 视频本身还是好的。
    fn setup_swr(&mut self) -> bool {
        unsafe {
            if !self.swr.is_null() {
                ffi::swr_free(&mut self.swr);
            }
            // 两条路的源声道布局来源不同，而 `swr_alloc_set_opts2` 只要一个指针，
            // 所以这里统一成「先拿到指针，再统一调用」。设备那条路的布局是**我们自己
            // 建的**，用完得 uninit（ffmpeg 那条路是借 `adec` 的，不能碰）。
            let mut src_ch: ffi::AVChannelLayout = std::mem::zeroed();
            let borrowed = !self.adec.is_null();
            let (src_fmt, src_rate, src_ch_ptr) = if borrowed {
                (
                    (*self.adec).sample_fmt,
                    (*self.adec).sample_rate,
                    &(*self.adec).ch_layout as *const ffi::AVChannelLayout,
                )
            } else {
                ffi::av_channel_layout_default(&mut src_ch, self.a_src_channels);
                (
                    self.a_src_fmt,
                    self.a_src_rate,
                    &src_ch as *const ffi::AVChannelLayout,
                )
            };
            let mut out_ch: ffi::AVChannelLayout = std::mem::zeroed();
            ffi::av_channel_layout_default(&mut out_ch, self.out_channels);
            let ok = ffi::swr_alloc_set_opts2(
                &mut self.swr,
                &out_ch,
                ffi::AVSampleFormat::AV_SAMPLE_FMT_S16,
                self.out_rate,
                src_ch_ptr,
                src_fmt,
                src_rate,
                0,
                std::ptr::null_mut(),
            ) >= 0
                && ffi::swr_init(self.swr) >= 0;
            ffi::av_channel_layout_uninit(&mut out_ch);
            if !borrowed {
                ffi::av_channel_layout_uninit(&mut src_ch);
            }
            ok
        }
    }

    // ---- Opus 音轨的设备解码器（见模块文档）----
    //
    // 和视频那条硬解**不一样**：解出来就是 PCM，直接进 swr → AAC 编码器，
    // 没有 `AImageReader`、没有 `AImage`。三个入口：喂包、收 PCM、收尾。

    /// 开设备自带的 Opus 解码器。
    ///
    /// 返回 false = 这台机器上没有（API 26~28）或配不起来 —— 调用方退回「只转视频」，
    /// 和「没有 WMA 解码器」时一样，不报错也不失败。
    unsafe fn open_opus(&mut self, par: *mut ffi::AVCodecParameters) -> bool {
        let dec = ffi::AMediaCodec_createDecoderByType(cstr!("audio/opus"));
        if dec.is_null() {
            return false;
        }
        // Opus 内部恒为 48 kHz，容器头里也这么写；万一没写就按 48 kHz。
        let rate = if (*par).sample_rate > 0 {
            (*par).sample_rate
        } else {
            48_000
        };
        // 最多 8 声道（Opus 的映射上限）。**不在这里压到 2**：重采样器负责下混，
        // 压在这里会让「源是 5.1」这个事实丢失，下混规则也就跟着丢了。
        let ch = (*par).ch_layout.nb_channels.clamp(1, 8);
        let af = ffi::AMediaFormat_new();
        ffi::AMediaFormat_setString(af, cstr!("mime"), cstr!("audio/opus"));
        ffi::AMediaFormat_setInt32(af, cstr!("sample-rate"), rate);
        ffi::AMediaFormat_setInt32(af, cstr!("channel-count"), ch);
        // 2 = ENCODING_PCM_16BIT。设备默认就是它，但「默认」不是「保证」——
        // 显式要一次，`opus_format_changed` 再核对一次。
        ffi::AMediaFormat_setInt32(af, cstr!("pcm-encoding"), 2);
        // `csd-0` = OpusHead（Matroska 的 `CodecPrivate`），pre-skip 在里面。
        if !(*par).extradata.is_null() && (*par).extradata_size > 0 {
            ffi::AMediaFormat_setBuffer(
                af,
                cstr!("csd-0"),
                (*par).extradata as *const std::ffi::c_void,
                (*par).extradata_size as usize,
            );
        }
        let st = ffi::AMediaCodec_configure(dec, af, std::ptr::null_mut(), std::ptr::null_mut(), 0);
        ffi::AMediaFormat_delete(af);
        if st != ffi::media_status_t::AMEDIA_OK
            || ffi::AMediaCodec_start(dec) != ffi::media_status_t::AMEDIA_OK
        {
            ffi::AMediaCodec_delete(dec);
            return false;
        }
        self.adec_hw = dec;
        self.a_src_fmt = ffi::AVSampleFormat::AV_SAMPLE_FMT_S16;
        self.a_src_rate = rate;
        self.a_src_channels = ch;
        self.has_audio = true;
        true
    }

    /// 一个 Opus 包进解码器。
    ///
    /// 输入队列取不到缓冲时**先收 PCM 再重试** —— 不解出来就没有空位，干等着就是
    /// 死锁。动机和视频那条 `pump_hw_images` 一样：解码器是生产者，它的输出不搬走，
    /// 它就再也不接收输入。
    fn feed_opus_packet(&mut self, pkt: &ffi::AVPacket) -> R<()> {
        let n = pkt.size.max(0) as usize;
        if n == 0 || pkt.data.is_null() {
            return Ok(());
        }
        let dec = self.adec_hw;
        let mut tries = 0;
        loop {
            if video_progress::cancelled() {
                return Err(ConvertError::Cancelled);
            }
            let idx = unsafe { ffi::AMediaCodec_dequeueInputBuffer(dec, 5_000) };
            if idx >= 0 {
                let mut cap: usize = 0;
                let buf = unsafe { ffi::AMediaCodec_getInputBuffer(dec, idx as usize, &mut cap) };
                if buf.is_null() {
                    return Err(msg("Opus 解码器输入缓冲不可用"));
                }
                // 一个 Opus 包最长 1275 字节，设备给的缓冲总够。不够就是异常，
                // **截断着喂**只会解出噪声 —— 宁可报错，别交出一段坏音频。
                if n > cap {
                    unsafe { ffi::AMediaCodec_queueInputBuffer(dec, idx as usize, 0, 0, 0, 0) };
                    return Err(msg(format!("Opus 包 {n} 字节，超过解码器输入缓冲 {cap}")));
                }
                unsafe {
                    std::ptr::copy_nonoverlapping(pkt.data, buf, n);
                    // PTS 传 0：音频时间轴完全由 `a_frames` 累加得出（见 `feed_audio`），
                    // 设备解码器的输出时间戳一个都不用。
                    ffi::AMediaCodec_queueInputBuffer(dec, idx as usize, 0, n, 0, 0);
                }
                return self.drain_opus(0).map(|_| ());
            }
            // 没有空位：把已经解出来的收走，给它腾地方。
            self.drain_opus(0)?;
            tries += 1;
            if tries > 400 {
                return Err(msg("Opus 解码器一直不接收输入"));
            }
        }
    }

    /// 收设备解码器解出来的 PCM，过重采样器喂给 AAC 编码器。
    ///
    /// 返回 true = 解码器已经吐过 EOS 输出标志（收尾循环靠它判断「不用再等了」）。
    fn drain_opus(&mut self, poll_us: i64) -> R<bool> {
        let dec = self.adec_hw;
        loop {
            let mut info = ffi::AMediaCodecBufferInfo {
                offset: 0,
                size: 0,
                presentationTimeUs: 0,
                flags: 0,
            };
            let idx = unsafe { ffi::AMediaCodec_dequeueOutputBuffer(dec, &mut info, poll_us) };
            if idx >= 0 {
                let mut osize: usize = 0;
                let buf = unsafe { ffi::AMediaCodec_getOutputBuffer(dec, idx as usize, &mut osize) };
                let res = if info.size > 0 && !buf.is_null() {
                    let n = (info.size as usize).min(osize);
                    // 取整到完整采样帧：半帧会让左右声道从此错位（`feed_audio` 里
                    // 切 PCM 是同一条理由）。
                    let fb = (self.a_src_channels as usize) * sample_bytes(self.a_src_fmt);
                    let n = if fb == 0 { 0 } else { n - n % fb };
                    if n == 0 {
                        Ok(())
                    } else {
                        let pcm =
                            unsafe { std::slice::from_raw_parts(buf.add(info.offset as usize), n) };
                        self.push_opus_pcm(pcm)
                    }
                } else {
                    Ok(())
                };
                let eos = info.flags & ffi::BufferFlags::AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM != 0;
                unsafe { ffi::AMediaCodec_releaseOutputBuffer(dec, idx as usize, false) };
                res?;
                if eos {
                    return Ok(true);
                }
            } else if idx == ffi::CodecInfo::AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED as isize {
                self.opus_format_changed()?;
            } else if idx == ffi::CodecInfo::AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED as isize {
                // 老 API 的产物，忽略。
            } else {
                // TRY_AGAIN_LATER：这一轮收干净了。
                return Ok(false);
            }
        }
    }

    /// 设备解码器报出了真实输出参数 —— 按它校一遍。
    ///
    /// 容器里写的声道数不一定等于设备真正吐的（多声道 Opus 会被下混），而
    /// `pcm-encoding` 更是「默认 S16、但不保证」。按错的值去读 PCM，结果是**一整片
    /// 噪声**却照样「转换成功」—— 所以这里宁可重建一次重采样器。重建会丢掉它内部
    /// 缓冲的那几毫秒，但这条分支只在第一个输出缓冲之前触发，那时还没有样本进过 swr。
    fn opus_format_changed(&mut self) -> R<()> {
        let f = unsafe { ffi::AMediaCodec_getOutputFormat(self.adec_hw) };
        if f.is_null() {
            return Ok(());
        }
        let mut rate: i32 = 0;
        let mut ch: i32 = 0;
        let mut enc: i32 = 2;
        let has_rate = unsafe { ffi::AMediaFormat_getInt32(f, cstr!("sample-rate"), &mut rate) };
        let has_ch = unsafe { ffi::AMediaFormat_getInt32(f, cstr!("channel-count"), &mut ch) };
        let has_enc = unsafe { ffi::AMediaFormat_getInt32(f, cstr!("pcm-encoding"), &mut enc) };
        unsafe { ffi::AMediaFormat_delete(f) };

        let mut changed = false;
        if has_rate && rate > 0 && rate != self.a_src_rate {
            self.a_src_rate = rate;
            changed = true;
        }
        if has_ch && ch > 0 && ch != self.a_src_channels {
            self.a_src_channels = ch;
            changed = true;
        }
        if has_enc {
            // 只认能表达的这几种；其余（24/32 位打包之类）保持 S16 并留一条日志 ——
            // 那种情况下声音会是噪声，但至少日志里能查到「设备没按 16 位给」。
            let fmt = match enc {
                2 => Some(ffi::AVSampleFormat::AV_SAMPLE_FMT_S16), // ENCODING_PCM_16BIT
                3 => Some(ffi::AVSampleFormat::AV_SAMPLE_FMT_U8),  // ENCODING_PCM_8BIT
                4 => Some(ffi::AVSampleFormat::AV_SAMPLE_FMT_FLT), // ENCODING_PCM_FLOAT
                22 => Some(ffi::AVSampleFormat::AV_SAMPLE_FMT_S32), // ENCODING_PCM_32BIT
                other => {
                    ffi::log_err(&format!("Opus 解码器输出 pcm-encoding={other}，按 16 位处理"));
                    None
                }
            };
            if let Some(fmt) = fmt {
                if fmt != self.a_src_fmt {
                    self.a_src_fmt = fmt;
                    changed = true;
                }
            }
        }
        if changed {
            // `out_rate` / `out_channels` 已经定了，不该跟着源变 —— 变了说明设备
            // 下混到了别的声道数，那正是我们要在 swr 里补上的那一层。
            if !self.setup_swr() {
                ffi::log_err("Opus 输出参数变了，重采样器重建失败");
            }
        }
        Ok(())
    }

    /// 一段交错的**源** PCM → 重采样 → 喂 AAC 编码器。
    fn push_opus_pcm(&mut self, pcm: &[u8]) -> R<()> {
        let fb = (self.a_src_channels as usize) * sample_bytes(self.a_src_fmt);
        if fb == 0 {
            return Ok(());
        }
        let frames = pcm.len() / fb;
        if frames == 0 {
            return Ok(());
        }
        let out_n = unsafe { ffi::swr_get_out_samples(self.swr, frames as c_int) };
        if out_n <= 0 {
            return Ok(());
        }
        let mut outbuf: *mut u8 = std::ptr::null_mut();
        if unsafe {
            ffi::av_samples_alloc(
                &mut outbuf,
                std::ptr::null_mut(),
                self.out_channels,
                out_n,
                ffi::AVSampleFormat::AV_SAMPLE_FMT_S16,
                0,
            )
        } < 0
        {
            return Ok(());
        }
        // 交错格式只有一个平面，所以指针数组就一个元素 —— 这和 `convert_audio_frame`
        // 传 `extended_data`（可能多平面）不同，别照抄那边的写法。
        let inp: *const u8 = pcm.as_ptr();
        let got = unsafe { ffi::swr_convert(self.swr, &mut outbuf, out_n, &inp, frames as c_int) };
        if got <= 0 {
            unsafe { ffi::av_freep(&mut outbuf as *mut *mut u8 as *mut std::ffi::c_void) };
            return Ok(());
        }
        let bytes = unsafe {
            std::slice::from_raw_parts(outbuf, got as usize * self.out_channels as usize * 2)
        };
        let r = self.feed_audio(bytes);
        unsafe { ffi::av_freep(&mut outbuf as *mut *mut u8 as *mut std::ffi::c_void) };
        r
    }

    /// 给设备解码器发 EOS，把管道里的 PCM 收干净。
    ///
    /// 不发 EOS 的话，最后几十毫秒会留在解码器里 —— 和「只 flush 视频解码器、
    /// 忘了音频解码器」是同一个坑（见 `convert` 收尾那段）。
    fn finish_opus(&mut self) -> R<()> {
        let dec = self.adec_hw;
        let deadline = Instant::now() + std::time::Duration::from_micros(FINISH_BUDGET_US as u64);
        // 输入队列可能一时取不到（输出队列满了）—— 一边收一边重试，别把 EOS 丢了。
        loop {
            let idx = unsafe { ffi::AMediaCodec_dequeueInputBuffer(dec, 10_000) };
            if idx >= 0 {
                unsafe {
                    ffi::AMediaCodec_queueInputBuffer(
                        dec,
                        idx as usize,
                        0,
                        0,
                        0,
                        ffi::BufferFlags::AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM,
                    );
                }
                break;
            }
            if self.drain_opus(0)? || Instant::now() >= deadline {
                // 已经吐过 EOS，或者等太久了：EOS 排不进去也不影响已经解出来的部分。
                return Ok(());
            }
        }
        // 等 EOS 输出标志。`drain_opus` 带 10 ms 等待，循环自己就有节奏。
        while !self.drain_opus(10_000)? {
            if Instant::now() >= deadline {
                break;
            }
        }
        Ok(())
    }
}

/// 一个采样在内存里占几个字节（**源**格式，只有设备解码器那条路用得上）。
///
/// 平面格式不在考虑内：`AMediaCodec` 吐的音频一律是交错的。真出现平面格式会落到
/// `_ => 2`，也就是按 S16 读 —— 那是错的，但比 panic 好，且 `opus_format_changed`
/// 不会给出平面格式。
fn sample_bytes(fmt: ffi::AVSampleFormat::Type) -> usize {
    match fmt {
        ffi::AVSampleFormat::AV_SAMPLE_FMT_U8 => 1,
        ffi::AVSampleFormat::AV_SAMPLE_FMT_S32 => 4,
        ffi::AVSampleFormat::AV_SAMPLE_FMT_FLT => 4,
        _ => 2,
    }
}

/// AAC 编码器只吃这几个采样率，别的一律降到 44100。
fn pick_rate(src: c_int) -> c_int {
    match src {
        96000 | 88200 | 64000 | 48000 | 44100 | 32000 | 24000 | 22050 | 16000 | 12000 | 11025
        | 8000 => src,
        _ => 44100,
    }
}

/// 码率按分辨率拍：3 bit/像素，夹在 1~12 Mbps。视频是重编码，源码率拿不到也不可靠。
fn video_bitrate(w: c_int, h: c_int) -> c_int {
    ((w as i64 * h as i64 * 3).clamp(1_000_000, 12_000_000)) as c_int
}

/// 转换一个文件。失败时调用方（Kotlin）负责删半成品。
pub fn convert(input: &str, output: &str) -> R<()> {
    let in_c = CString::new(input).map_err(|_| msg("输入路径含 NUL"))?;
    

    let mut c = Ctx {
        ifmt: std::ptr::null_mut(),
        vstream: -1,
        astream: -1,
        vdec: std::ptr::null_mut(),
        adec: std::ptr::null_mut(),
        #[cfg(feature = "v2v")]
        v2v: None,
        adec_hw: std::ptr::null_mut(),
        // 只有 Opus 那条路会用到这三个；ffmpeg 那条路一律从 `adec` 读。
        a_src_fmt: ffi::AVSampleFormat::AV_SAMPLE_FMT_S16,
        a_src_rate: 0,
        a_src_channels: 0,
        mux: std::ptr::null_mut(),
        out_file: None,
        venc: std::ptr::null_mut(),
        aenc: std::ptr::null_mut(),
        vtrack: -1,
        atrack: -1,
        mux_started: false,
        has_video: false,
        has_audio: false,
        q: VecDeque::new(),
        last_v_us: 0,
        a_frames: 0,
        have_v_pts: false,
        v_base_us: 0,
        v_shift_us: 0,
        last_a_us: 0,
        out_rate: 0,
        out_channels: 0,
        vw: 0,
        vh: 0,
        frame_dur_us: 0,
        swr: std::ptr::null_mut(),
        hwdec: std::ptr::null_mut(),
        img_reader: std::ptr::null_mut(),
        hw_eos: false,
        hw_geom_logged: false,
        use_hw: false,
        hw_kind: HwCodec::Avc,
        avc: None,
        hw_payload: Vec::new(),
    };

    unsafe {
        // ---- 打开输入 ----
        if ffi::avformat_open_input(&mut c.ifmt, in_c.as_ptr(), std::ptr::null(), std::ptr::null_mut()) < 0 {
            return Err(msg(format!("打不开输入：{input}")));
        }
        if ffi::avformat_find_stream_info(c.ifmt, std::ptr::null_mut()) < 0 {
            return Err(msg("读不出流信息"));
        }

        c.vstream = ffi::av_find_best_stream(
            c.ifmt,
            ffi::AVMediaType::AVMEDIA_TYPE_VIDEO,
            -1,
            -1,
            std::ptr::null_mut(),
            0,
        );
        c.astream = ffi::av_find_best_stream(
            c.ifmt,
            ffi::AVMediaType::AVMEDIA_TYPE_AUDIO,
            -1,
            -1,
            std::ptr::null_mut(),
            0,
        );
        if c.vstream < 0 {
            return Err(msg("这个文件里没有视频轨"));
        }

        // ---- 视频解码器 ----
        //
        // H.264 / VP8 / VP9 走 MediaCodec **硬解**：ffmpeg 里没编它们的解码器
        // （H.264 那个约 1 MB/ABI，VP8+VP9 约 2 MB/ABI —— 见
        // scripts/build_ffmpeg.sh 的体积账），而设备自带的硬解是免费的。
        // VC-1 / MS-MPEG-4 全家走 v2v 的纯 Rust 解码器（feature `v2v`，默认关）。
        // 剩下的（MPEG-1/2、MPEG-4 Part 2、老 Real 之类）仍由 ffmpeg 解 ——
        // 它们的纯 C 实现体积小，v2v 那边也还没接完。
        {
            let par = (*c.stream(c.vstream)).codecpar;
            let codec_id = (*par).codec_id;
            if let Some(kind) = HwCodec::of(codec_id) {
                c.hw_kind = kind;
                // `avcC` 只有 H.264 有。VP8/VP9 的关键帧自带全部信息，不需要配置记录，
                // 所以连 csd 都不设。
                let avc = if kind.needs_annexb() {
                    let extra: &[u8] = if (*par).extradata.is_null() || (*par).extradata_size <= 0 {
                        &[]
                    } else {
                        std::slice::from_raw_parts((*par).extradata, (*par).extradata_size as usize)
                    };
                    Some(crate::h264::AvcConfig::parse(extra).ok_or_else(|| {
                        msg(format!(
                            "{} 源的 extradata 不是 avcC —— 目前只支持 MP4/MOV/MKV 一类容器",
                            kind.label()
                        ))
                    })?)
                } else {
                    None
                };
                // 只接 8 位 4:2:0。10 位源（Hi10P 的 H.264、VP9 Profile 2）的平面是
                // 16 位/样本，而 `AIMAGE_FORMAT_YUV_420_888` 与 `yuv.rs` 都按 8 位算 ——
                // 不拦的话表现是**花屏而不是报错**，比直接拒绝难查得多。
                // `NONE`（容器没写）照常放行：真拿不到就别猜，交给 reader 的实际布局。
                let pf = (*par).format;
                if pf != ffi::AVPixelFormat::AV_PIX_FMT_NONE
                    && pf != ffi::AVPixelFormat::AV_PIX_FMT_YUV420P
                    && pf != ffi::AVPixelFormat::AV_PIX_FMT_YUVJ420P
                {
                    return Err(msg(format!(
                        "{} 源不是 8 位 4:2:0（像素格式 {pf}），暂不支持",
                        kind.label()
                    )));
                }
                // 4:2:0 的宽高必须是偶数。硬解没有 `AVCodecContext`，尺寸只能从
                // `codecpar` 取（容器头里早就有宽高了）。
                c.vw = ((*par).width + 1) & !1;
                c.vh = ((*par).height + 1) & !1;
                if c.vw <= 0 || c.vh <= 0 {
                    return Err(msg(format!(
                        "{} 源的分辨率不可用（{}×{}）",
                        kind.label(),
                        (*par).width,
                        (*par).height
                    )));
                }

                c.hwdec = ffi::AMediaCodec_createDecoderByType(kind.mime());
                if c.hwdec.is_null() {
                    return Err(msg(format!("这台设备没有 {} 解码器", kind.label())));
                }
                // 解码器的输出面 = **一个 AImageReader 的 window**（不是编码器的输入面）。
                // 这样每渲一帧就能 acquire 出一张 `AImage`：三个平面 + 行/像素跨度 +
                // 时间戳全是接口明说的，见 `Ctx::img_reader` 那段。
                // `AImageReader_new` 隐含 `CPU_READ_OFTEN` —— 我们本来就要读像素。
                let st = ffi::AImageReader_new(
                    c.vw,
                    c.vh,
                    0x23, // AIMAGE_FORMAT_YUV_420_888
                    4,    // 同时最多 4 张在途，够解码器周转
                    &mut c.img_reader,
                );
                if st != ffi::media_status_t::AMEDIA_OK || c.img_reader.is_null() {
                    return Err(msg(format!("建不了解码器输出面（AImageReader）：{st}")));
                }
                let mut win: *mut ffi::ANativeWindow = std::ptr::null_mut();
                let st = ffi::AImageReader_getWindow(c.img_reader, &mut win);
                if st != ffi::media_status_t::AMEDIA_OK || win.is_null() {
                    return Err(msg(format!("拿不到 AImageReader 的 window：{st}")));
                }

                let df = ffi::AMediaFormat_new();
                ffi::AMediaFormat_setString(df, cstr!("mime"), kind.mime());
                ffi::AMediaFormat_setInt32(df, cstr!("width"), c.vw);
                ffi::AMediaFormat_setInt32(df, cstr!("height"), c.vh);
                // **明确要线性 NV12**（`COLOR_FormatYUV420SemiPlanar` = 21）。
                // 不写的话，高通的 C2 解码器会自己挑一个私有 tiled 格式
                // （实测 `0x7FA30C06`）：`AImage` 的 Y 平面能读，色度平面却是
                // `ptr=null`，于是 `yuv.rs` 无从下手。surface 输出时这个键按理会被
                // 忽略，但 C2 组件确实会照它分配线性缓冲 —— 这是让色度可读的唯一开关。
                ffi::AMediaFormat_setInt32(df, cstr!("color-format"), 21);
                // 解码器要**带起始码**的 SPS / PPS（csd-0 / csd-1），avcC 里存的是裸 NAL。
                if let Some(a) = avc.as_ref() {
                    let mut csd0 = Vec::with_capacity(a.sps.len() + 4);
                    csd0.extend_from_slice(&[0, 0, 0, 1]);
                    csd0.extend_from_slice(&a.sps);
                    let mut csd1 = Vec::with_capacity(a.pps.len() + 4);
                    csd1.extend_from_slice(&[0, 0, 0, 1]);
                    csd1.extend_from_slice(&a.pps);
                    ffi::AMediaFormat_setBuffer(df, cstr!("csd-0"), csd0.as_ptr().cast(), csd0.len());
                    ffi::AMediaFormat_setBuffer(df, cstr!("csd-1"), csd1.as_ptr().cast(), csd1.len());
                }
                let st = ffi::AMediaCodec_configure(c.hwdec, df, win, std::ptr::null_mut(), 0);
                ffi::AMediaFormat_delete(df);
                if st != ffi::media_status_t::AMEDIA_OK {
                    return Err(msg(format!("{} 解码器配置失败：{st}", kind.label())));
                }
                // window 归 reader 所有，**不要** `ANativeWindow_release`。
                c.avc = avc;
                c.use_hw = true;
            } else {
                // ---- 先试 v2v 的纯 Rust 解码器（feature `v2v`）----
                //
                // 起不来就落到下面的 ffmpeg 分支。这条兜底是**刻意保留**的：
                // VC-1 只做 Main / 渐进 / I-P，Advanced profile、B 帧、隔行都会明确
                // 报错，而 ffmpeg 这些都能解。等真机验过、确认覆盖够用，再把
                // `need_ffmpeg` 那半截和 `build_ffmpeg.sh` 里的 `vc1,wmv3` 一起删掉
                // —— 那才是真省下 302 KB/ABI 的时候。
                //
                // MS-MPEG-4 全家（MP41/42/43、WMV1、WMV2）没有这个缺口：这套规范里
                // 根本没有 B 帧，所以是**完整替换**。ffmpeg 那边对应的
                // `msmpeg4dec` + `msmpeg4` + `msmpeg4data` 约 60 KB/ABI 可以真删。
                #[cfg(feature = "v2v")]
                let v2v_codec = match codec_id {
                    ffi::AVCodecID::AV_CODEC_ID_WMV3 => Some(crate::v2v_dec::Codec::Vc1),
                    ffi::AVCodecID::AV_CODEC_ID_MSMPEG4V1 => {
                        Some(crate::v2v_dec::Codec::MsMpeg4V1)
                    }
                    ffi::AVCodecID::AV_CODEC_ID_MSMPEG4V2 => {
                        Some(crate::v2v_dec::Codec::MsMpeg4V2)
                    }
                    ffi::AVCodecID::AV_CODEC_ID_MSMPEG4V3 => {
                        Some(crate::v2v_dec::Codec::MsMpeg4V3)
                    }
                    ffi::AVCodecID::AV_CODEC_ID_WMV1 => Some(crate::v2v_dec::Codec::Wmv1),
                    ffi::AVCodecID::AV_CODEC_ID_WMV2 => Some(crate::v2v_dec::Codec::Wmv2),
                    _ => None,
                };
                #[cfg(feature = "v2v")]
                if let Some(kind) = v2v_codec {
                    let extra: &[u8] = if (*par).extradata.is_null() || (*par).extradata_size <= 0 {
                        &[]
                    } else {
                        std::slice::from_raw_parts(
                            (*par).extradata,
                            (*par).extradata_size as usize,
                        )
                    };
                    match crate::v2v_dec::Dec::new(
                        kind,
                        extra,
                        (*par).width as u32,
                        (*par).height as u32,
                    ) {
                        Ok(d) => {
                            ffi::log_err(&format!(
                                "{kind:?} 走 v2v 解码（{}×{}）",
                                d.width(),
                                d.height()
                            ));
                            c.v2v = Some(d);
                        }
                        Err(e) => ffi::log_err(&format!("v2v 的 {kind:?} 起不来（{e}），退回 ffmpeg")),
                    }
                }

                #[cfg(feature = "v2v")]
                let need_ffmpeg = c.v2v.is_none();
                #[cfg(not(feature = "v2v"))]
                let need_ffmpeg = true;

                if need_ffmpeg {
                    let dec = ffi::avcodec_find_decoder(codec_id);
                    if dec.is_null() {
                        let name = std::ffi::CStr::from_ptr(ffi::avcodec_get_name(codec_id))
                            .to_string_lossy()
                            .into_owned();
                        return Err(msg(format!("没有 {name} 的解码器")));
                    }
                    c.vdec = ffi::avcodec_alloc_context3(dec);
                    if c.vdec.is_null() {
                        return Err(msg("视频解码器分配失败"));
                    }
                    if ffi::avcodec_parameters_to_context(c.vdec, par) < 0 {
                        return Err(msg("视频解码参数拷贝失败"));
                    }
                    (*c.vdec).thread_count = 2;
                    if ffi::avcodec_open2(c.vdec, dec, std::ptr::null_mut()) < 0 {
                        return Err(msg("视频解码器打不开"));
                    }
                }
            }
        }

        // ---- 音频解码器（拿不到就只转视频）----
        if c.astream >= 0 {
            let par = (*c.stream(c.astream)).codecpar;
            if (*par).codec_id == ffi::AVCodecID::AV_CODEC_ID_OPUS {
                // Opus 交给设备解码器：ffmpeg 那个要 141.7 KB/ABI，设备这个是零字节。
                // 开不起来（API 26~28）就静默退回只转视频。
                if !c.open_opus(par) {
                    ffi::log_err("这台设备没有 Opus 解码器（API 29+ 才有），只转视频");
                }
            } else {
                let dec = ffi::avcodec_find_decoder((*par).codec_id);
                if !dec.is_null() {
                    let adec = ffi::avcodec_alloc_context3(dec);
                    if !adec.is_null()
                        && ffi::avcodec_parameters_to_context(adec, par) >= 0
                        && ffi::avcodec_open2(adec, dec, std::ptr::null_mut()) >= 0
                    {
                        c.adec = adec;
                        c.has_audio = true;
                    } else if !adec.is_null() {
                        let mut a = adec;
                        ffi::avcodec_free_context(&mut a);
                    }
                } else {
                    let name = std::ffi::CStr::from_ptr(ffi::avcodec_get_name((*par).codec_id))
                        .to_string_lossy()
                        .into_owned();
                    ffi::log_err(&format!("没有 {name} 的解码器，只转视频"));
                }
            }
        }

        c.has_video = true;
        // 没有 ffmpeg 解码器时（硬解 / v2v 那条纯 Rust 路），尺寸从 codecpar 取 ——
        // 容器头里早就有宽高了。
        if c.vdec.is_null() {
            let par = (*c.stream(c.vstream)).codecpar;
            c.vw = ((*par).width + 1) & !1;
            c.vh = ((*par).height + 1) & !1;
        } else {
            c.vw = ((*c.vdec).width + 1) & !1;
            c.vh = ((*c.vdec).height + 1) & !1;
        }

        // ---- H.264 编码器 ----
        c.venc = ffi::AMediaCodec_createEncoderByType(cstr!("video/avc"));
        if c.venc.is_null() {
            return Err(msg("这台设备没有 H.264 编码器"));
        }
        let fr = ffi::av_guess_frame_rate(c.ifmt, c.stream(c.vstream), std::ptr::null_mut());
        let mut fps = if fr.num > 0 && fr.den > 0 {
            (fr.num as f64 / fr.den as f64 + 0.5) as c_int
        } else {
            25
        };
        if !(1..=120).contains(&fps) {
            fps = 25;
        }
        c.frame_dur_us = 1_000_000 / fps as i64;
        {
            let vf = ffi::AMediaFormat_new();
            ffi::AMediaFormat_setString(vf, cstr!("mime"), cstr!("video/avc"));
            ffi::AMediaFormat_setInt32(vf, cstr!("width"), c.vw);
            ffi::AMediaFormat_setInt32(vf, cstr!("height"), c.vh);
            // COLOR_FormatYUV420SemiPlanar —— 见 nv12.rs。两条路都是 ByteBuffer 输入
            // （硬解也要经过 `yuv.rs` 转一次），所以**无条件**设。
            ffi::AMediaFormat_setInt32(vf, cstr!("color-format"), 21);
            ffi::AMediaFormat_setInt32(vf, cstr!("bitrate"), video_bitrate(c.vw, c.vh));
            ffi::AMediaFormat_setInt32(vf, cstr!("frame-rate"), fps);
            ffi::AMediaFormat_setInt32(vf, cstr!("i-frame-interval"), 1);
            let st = ffi::AMediaCodec_configure(
                c.venc,
                vf,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                ffi::CodecInfo::AMEDIACODEC_CONFIGURE_FLAG_ENCODE as u32,
            );
            ffi::AMediaFormat_delete(vf);
            if st != ffi::media_status_t::AMEDIA_OK {
                return Err(msg(format!("H.264 编码器配置失败：{st}")));
            }
        }
        // 解码器已经 configure 到 `img_reader` 的 window 上了（在开尺寸那段做的），
        // 这里只要在喂第一个包之前把它 start 起来。
        if ffi::AMediaCodec_start(c.venc) != ffi::media_status_t::AMEDIA_OK {
            return Err(msg("H.264 编码器启动失败"));
        }
        if c.use_hw && ffi::AMediaCodec_start(c.hwdec) != ffi::media_status_t::AMEDIA_OK {
            return Err(msg(format!("{} 解码器启动失败", c.hw_kind.label())));
        }

        // ---- AAC 编码器 + 重采样器（任一失败就只转视频）----
        if c.has_audio {
            // 源参数按「谁在解」分两种：ffmpeg 的解码器在 `adec` 里，Opus 走设备
            // 解码器（`adec` 是空指针），参数是开解码器时记下来的。
            let (src_rate, src_ch) = if c.adec.is_null() {
                (c.a_src_rate, c.a_src_channels)
            } else {
                ((*c.adec).sample_rate, (*c.adec).ch_layout.nb_channels)
            };
            c.out_rate = pick_rate(src_rate);
            c.out_channels = src_ch.clamp(1, 2);

            c.aenc = ffi::AMediaCodec_createEncoderByType(cstr!("audio/mp4a-latm"));
            if c.aenc.is_null() {
                ffi::log_err("没有 AAC 编码器，只转视频");
                c.has_audio = false;
            } else {
                let af = ffi::AMediaFormat_new();
                ffi::AMediaFormat_setString(af, cstr!("mime"), cstr!("audio/mp4a-latm"));
                ffi::AMediaFormat_setInt32(af, cstr!("aac-profile"), 2); // AAC-LC
                ffi::AMediaFormat_setInt32(af, cstr!("sample-rate"), c.out_rate);
                ffi::AMediaFormat_setInt32(af, cstr!("channel-count"), c.out_channels);
                ffi::AMediaFormat_setInt32(
                    af,
                    cstr!("bitrate"),
                    if c.out_channels == 1 { 96000 } else { 128000 },
                );
                let st = ffi::AMediaCodec_configure(
                    c.aenc,
                    af,
                    std::ptr::null_mut(),
                    std::ptr::null_mut(),
                    ffi::CodecInfo::AMEDIACODEC_CONFIGURE_FLAG_ENCODE as u32,
                );
                ffi::AMediaFormat_delete(af);
                if st != ffi::media_status_t::AMEDIA_OK
                    || ffi::AMediaCodec_start(c.aenc) != ffi::media_status_t::AMEDIA_OK
                {
                    ffi::log_err("AAC 编码器起不来，只转视频");
                    ffi::AMediaCodec_delete(c.aenc);
                    c.aenc = std::ptr::null_mut();
                    c.has_audio = false;
                } else if !c.setup_swr() {
                    ffi::log_err("重采样器起不来，只转视频");
                    c.has_audio = false;
                    ffi::AMediaCodec_stop(c.aenc);
                    ffi::AMediaCodec_delete(c.aenc);
                    c.aenc = std::ptr::null_mut();
                }
            }
        }

        // AAC priming 补偿：把视频整体后移一个 priming 时长，让两条轨的**内容**对齐。
        // 编码器/重采样器任何一步失败都会把 `has_audio` 复位成 false → 这里自然不补偿
        // （没有音轨就没有 priming，后移只会凭空多出一段黑场）。见 `AAC_PRIMING_SAMPLES`。
        if c.has_audio && c.out_rate > 0 {
            c.v_shift_us = AAC_PRIMING_SAMPLES * 1_000_000 / c.out_rate as i64;
        }

        // ---- 输出 ----
        let file = std::fs::OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .open(output)
            .map_err(|e| msg(format!("建不了输出文件：{output}（{e}）")))?;
        let fd = file.as_raw_fd();
        c.out_file = Some(file);
        c.mux = ffi::AMediaMuxer_new(fd, ffi::OutputFormat::AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4);
        if c.mux.is_null() {
            return Err(msg("建不了 MP4 封装器"));
        }
        // 旋转：只改容器里的显示矩阵，**不重编码**（帧还是原样，方向交给播放器）。
        // 必须在 `AMediaMuxer_start` 之前调，之后再设会被忽略。
        let rot = c.stream_rotation_cw();
        if rot != 0 {
            let st = ffi::AMediaMuxer_setOrientationHint(c.mux, rot);
            if st != ffi::media_status_t::AMEDIA_OK {
                // 写不进去只是「方向丢了」，片本身是好的 —— 不值得让整个转换失败。
                ffi::log_err(&format!("封装器不接受旋转提示 {rot}°：{st}"));
            }
        }

        // ---- 进度 ----
        let in_size = std::fs::metadata(input).map(|m| m.len()).unwrap_or(0);
        video_progress::reset(in_size);
        video_progress::set_file(in_size);
        video_progress::set_name(input);

        // ---- 主循环 ----
        let pkt = ffi::av_packet_alloc();
        let frame = ffi::av_frame_alloc();
        if pkt.is_null() || frame.is_null() {
            return Err(msg("分配包/帧失败"));
        }

        let mut eof = false;
        while !eof {
            if video_progress::cancelled() {
                let mut p = pkt;
                let mut f = frame;
                ffi::av_packet_free(&mut p);
                ffi::av_frame_free(&mut f);
                return Err(ConvertError::Cancelled);
            }
            if ffi::av_read_frame(c.ifmt, pkt) < 0 {
                eof = true;
                continue;
            }
            video_progress::add_bytes((*pkt).size.max(0) as u64);
            let si = (*pkt).stream_index;
            if si == c.vstream {
                if c.use_hw {
                    // 硬解：包直接进 MediaCodec，`vdec` 是空指针（不能碰 avcodec）。
                    if let Err(e) = c.feed_hw_packet(&*pkt) {
                        let mut p = pkt;
                        let mut f = frame;
                        ffi::av_packet_free(&mut p);
                        ffi::av_frame_free(&mut f);
                        return Err(e);
                    }
                } else if c.uses_v2v() {
                    // v2v 的纯 Rust 解码器：帧自己写进编码器缓冲（没有 `AVFrame`）。
                    //
                    // ⚠️ 这里**没有**中途退回 ffmpeg 的路：换解码器意味着整段重来
                    // （新解码器没有参考帧）。所以 `v2v` 只该在覆盖率确定够的编码上
                    // 打开 —— MS-MPEG-4 是，VC-1 不是（B 帧）。
                    if let Err(e) = c.feed_v2v(&*pkt) {
                        let mut p = pkt;
                        let mut f = frame;
                        ffi::av_packet_free(&mut p);
                        ffi::av_frame_free(&mut f);
                        return Err(e);
                    }
                } else if ffi::avcodec_send_packet(c.vdec, pkt) >= 0 {
                    while ffi::avcodec_receive_frame(c.vdec, frame) == 0 {
                        let r = c.feed_video(&*frame);
                        ffi::av_frame_unref(frame);
                        if let Err(e) = r {
                            let mut p = pkt;
                            let mut f = frame;
                            ffi::av_packet_free(&mut p);
                            ffi::av_frame_free(&mut f);
                            return Err(e);
                        }
                    }
                }
            } else if c.has_audio && si == c.astream {
                if !c.adec_hw.is_null() {
                    // Opus：包直接进设备解码器，PCM 在 `feed_opus_packet` 里被收走。
                    if let Err(e) = c.feed_opus_packet(&*pkt) {
                        let mut p = pkt;
                        let mut f = frame;
                        ffi::av_packet_free(&mut p);
                        ffi::av_frame_free(&mut f);
                        return Err(e);
                    }
                } else if ffi::avcodec_send_packet(c.adec, pkt) >= 0 {
                    while ffi::avcodec_receive_frame(c.adec, frame) == 0 {
                        if let Err(e) = convert_audio_frame(&mut c, frame) {
                            let mut p = pkt;
                            let mut f = frame;
                            ffi::av_packet_free(&mut p);
                            ffi::av_frame_free(&mut f);
                            return Err(e);
                        }
                    }
                }
            }
            ffi::av_packet_unref(pkt);
        }

        // 分母一开始取的是**输入文件大小**（那个数一定拿得到），但包的总和比它小
        // —— 容器头/索引都不进包。不重定的话进度条会停在一个「看着像卡住」的
        // 90 几个百分点上。读完输入就把分母对齐到真正过手的字节，条子刚好走满。
        let read = video_progress::bytes();
        let total = video_progress::total_bytes();
        if total > read {
            video_progress::adjust_total(-((total - read) as i64));
            video_progress::set_file(read);
        }

        // ---- 收尾：把两个解码器/重采样器/编码器里的存货全部逼出来 ----
        // 硬解路径没有 ffmpeg 视频解码器，flush 走 `finish_hw`（见下）。
        // v2v 那条路也不用 flush：两种解码器都是「给一包、出一帧」，内部没有 B 帧
        // 那种重排缓冲，最后一个包解完那一帧就已经交出来了。
        if !c.use_hw && !c.uses_v2v() {
            ffi::avcodec_send_packet(c.vdec, std::ptr::null());
            while ffi::avcodec_receive_frame(c.vdec, frame) == 0 {
                let r = c.feed_video(&*frame);
                ffi::av_frame_unref(frame);
                r?;
            }
        }
        if c.has_audio {
            if !c.adec_hw.is_null() {
                // 设备解码器（Opus）也要发 EOS：不给它，管道里最后那几十毫秒就留在
                // 里面了。理由和下面 ffmpeg 那条完全一样。
                c.finish_opus()?;
            } else {
                // **音频解码器也要 flush**：只对视频解码器发 NULL 包，音频解码器里缓冲的帧
                // 就永远留在那儿了（丢掉几 ms 的尾巴）。必须在重采样器尾巴**之前**做，
                // 否则那些帧根本进不了 swr。（`convert_audio_frame` 内部自己 unref。）
                let adec = c.adec;
                ffi::avcodec_send_packet(adec, std::ptr::null());
                while ffi::avcodec_receive_frame(adec, frame) == 0 {
                    convert_audio_frame(&mut c, frame)?;
                }
            }
            // 重采样器的尾巴
            let out_n = ffi::swr_get_out_samples(c.swr, 0);
            if out_n > 0 {
                let mut outbuf: *mut u8 = std::ptr::null_mut();
                if ffi::av_samples_alloc(
                    &mut outbuf,
                    std::ptr::null_mut(),
                    c.out_channels,
                    out_n,
                    ffi::AVSampleFormat::AV_SAMPLE_FMT_S16,
                    0,
                ) >= 0
                {
                    let got = ffi::swr_convert(
                        c.swr,
                        &mut outbuf,
                        out_n,
                        std::ptr::null(),
                        0,
                    );
                    if got > 0 {
                        let bytes = std::slice::from_raw_parts(
                            outbuf,
                            got as usize * c.out_channels as usize * 2,
                        );
                        let r = c.feed_audio(bytes);
                        ffi::av_freep(&mut outbuf as *mut *mut u8 as *mut std::ffi::c_void);
                        r?;
                    } else {
                        ffi::av_freep(&mut outbuf as *mut *mut u8 as *mut std::ffi::c_void);
                    }
                }
            }
        }
        let (venc, aenc) = (c.venc, c.aenc);
        if c.use_hw {
            // 硬解：先给解码器发 EOS 并把 `img_reader` 里剩的帧吐干净，再收编码器
            // （顺序见 `finish_hw`）。
            c.finish_hw()?;
        } else {
            // EOS 也要带上后移量，否则编码器会把「末帧时间戳回退」当成异常。
            c.finish_encoder(venc, true, c.last_v_us + c.v_shift_us)?;
        }
        if c.has_audio {
            c.finish_encoder(aenc, false, c.last_a_us)?;
        }
        // 收尾放宽：正常两条轨都齐才 start；但「源有音轨却一个音频帧都没解出来」时
        // atrack 永远是 -1，严格等下去 muxer 永不启动。这里允许有几条轨就起几条。
        c.maybe_start_muxer(false)?;
        // 两个编码器都收完了 muxer 还没起来 = 一帧都没产出。不拦的话会交出一个
        // 0 字节的 MP4 还报成功。
        if !c.mux_started {
            return Err(msg("编码器没有产出任何数据"));
        }

        // 截断的源不会报错，只会安静地少一截。把「声明 vs 实解」的对比交给 UI，
        // 让它在成功后提醒一句 —— 不在这里拦，是因为能解出来的部分是真能用的。
        let declared = crate::health::declared_video_us(c.ifmt, c.vstream);
        crate::health::report(declared, c.last_v_us + c.frame_dur_us);

        let mut p = pkt;
        let mut f = frame;
        ffi::av_packet_free(&mut p);
        ffi::av_frame_free(&mut f);
    }

    Ok(())
}

/// 一个解码出来的音频帧 → 重采样 → 喂给 AAC 编码器。
///
/// 单独拆出来是因为它要在 `avcodec_receive_frame` 的循环里被调用，而那个循环
/// 里 `c` 已经被可变借用了。
unsafe fn convert_audio_frame(c: &mut Ctx, frame: *mut ffi::AVFrame) -> R<()> {
    let out_n = ffi::swr_get_out_samples(c.swr, (*frame).nb_samples);
    if out_n <= 0 {
        ffi::av_frame_unref(frame);
        return Ok(());
    }
    let mut outbuf: *mut u8 = std::ptr::null_mut();
    if ffi::av_samples_alloc(
        &mut outbuf,
        std::ptr::null_mut(),
        c.out_channels,
        out_n,
        ffi::AVSampleFormat::AV_SAMPLE_FMT_S16,
        0,
    ) < 0
    {
        ffi::av_frame_unref(frame);
        return Ok(());
    }
    let got = ffi::swr_convert(
        c.swr,
        &mut outbuf,
        out_n,
        (*frame).extended_data as *const *const u8,
        (*frame).nb_samples,
    );
    ffi::av_frame_unref(frame);
    if got <= 0 {
        ffi::av_freep(&mut outbuf as *mut *mut u8 as *mut std::ffi::c_void);
        return Ok(());
    }
    let bytes = std::slice::from_raw_parts(outbuf, got as usize * c.out_channels as usize * 2);
    let r = c.feed_audio(bytes);
    ffi::av_freep(&mut outbuf as *mut *mut u8 as *mut std::ffi::c_void);
    r
}
