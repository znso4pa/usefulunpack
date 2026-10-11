//! 「往 wmv 走」——任何我们能解的源 → WMV（ASF 容器）。
//!
//! 读方向（[`crate::convert`]）是「ffmpeg 解 + Android 编/封」；写方向**只能全程
//! ffmpeg**：Android 的 `MediaCodec` 没有 WMV/VC-1 编码器、`MediaMuxer` 也不认 ASF。
//!
//! ```text
//!   av_read_frame ─► ffmpeg 解码 ─► AVFrame ─► ffmpeg 编码(wmv2) ─┐
//!        │                                                        ├─► asf 封装 ─► .wmv
//!        └──────────► ffmpeg 解码 ─► swr ─► PCM ─► ffmpeg 编码(wmav2) ┘
//! ```
//!
//! ⚠️ **只能出 WMV7/WMV8**（`wmv2` = MSMPEG4v2 系）。ffmpeg 里**没有 VC-1/wmv3 编码器**
//! （`vc1`/`wmv3` 只有解码器），所以产不出「WMV9」。真 `.wmv` 本来就以 wmv2/wmv1 居多。
//!
//! 与读方向一样，本文件只在 Android 目标编译（宿主上是空壳），真链路只能真机验。

use std::ffi::CString;
use std::os::raw::{c_char, c_int};

use crate::ffi;
use crate::{video_progress, ConvertError};

type R<T> = Result<T, ConvertError>;

fn msg<S: Into<String>>(s: S) -> ConvertError {
    ConvertError::Message(s.into())
}

macro_rules! cstr {
    ($s:literal) => {
        concat!($s, "\0").as_ptr() as *const c_char
    };
}

/// `AVERROR(EAGAIN)`：Linux/Android 上 `EAGAIN` = 11，ffmpeg 取负。
const AVERROR_EAGAIN: c_int = -11;
/// `AVERROR_EOF` = `FFERRTAG('E','O','F',' ')` = `-MKTAG('E','O','F',' ')`。
const AVERROR_EOF: c_int = -541_478_725;

/// WMV 码率：3 bit/像素，夹在 0.5..8 Mbps（wmv2 不是高码率编码器）。
fn wmv_bitrate(w: c_int, h: c_int) -> i64 {
    (w as i64 * h as i64 * 3).clamp(500_000, 8_000_000)
}

/// 打开着的整条链；[`Drop`] 按相反顺序释放。
struct Ctx {
    ifmt: *mut ffi::AVFormatContext,
    ofmt: *mut ffi::AVFormatContext,
    vdec: *mut ffi::AVCodecContext,
    adec: *mut ffi::AVCodecContext,
    venc: *mut ffi::AVCodecContext,
    aenc: *mut ffi::AVCodecContext,
    swr: *mut ffi::SwrContext,
    vstream: c_int,
    astream: c_int,
    /// `avio_open` 成功过 → Drop 里要 `avio_closep`。
    io_open: bool,
    /// 已经 `av_write_trailer` → Drop 里不要再写一次。
    trailer_done: bool,
}

impl Ctx {
    fn new() -> Self {
        Self {
            ifmt: std::ptr::null_mut(),
            ofmt: std::ptr::null_mut(),
            vdec: std::ptr::null_mut(),
            adec: std::ptr::null_mut(),
            venc: std::ptr::null_mut(),
            aenc: std::ptr::null_mut(),
            swr: std::ptr::null_mut(),
            vstream: -1,
            astream: -1,
            io_open: false,
            trailer_done: false,
        }
    }

    unsafe fn stream(&self, i: c_int) -> *mut ffi::AVStream {
        *(*self.ifmt).streams.add(i as usize)
    }
}

impl Drop for Ctx {
    fn drop(&mut self) {
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
            if !self.venc.is_null() {
                ffi::avcodec_free_context(&mut self.venc);
            }
            if !self.aenc.is_null() {
                ffi::avcodec_free_context(&mut self.aenc);
            }
            if !self.ofmt.is_null() {
                if self.io_open && !(*self.ofmt).pb.is_null() {
                    ffi::avio_closep(&mut (*self.ofmt).pb);
                }
                ffi::avformat_free_context(self.ofmt);
            }
            if !self.ifmt.is_null() {
                ffi::avformat_close_input(&mut self.ifmt);
            }
        }
    }
}

/// 打开一个流的解码器（拿不到就返回 Err，调用方决定降级）。
unsafe fn open_decoder(st: *mut ffi::AVStream) -> R<*mut ffi::AVCodecContext> {
    let par = (*st).codecpar;
    let dec = ffi::avcodec_find_decoder((*par).codec_id);
    if dec.is_null() {
        let name = std::ffi::CStr::from_ptr(ffi::avcodec_get_name((*par).codec_id))
            .to_string_lossy()
            .into_owned();
        return Err(msg(format!("没有 {name} 的解码器")));
    }
    let ctx = ffi::avcodec_alloc_context3(dec);
    if ctx.is_null() {
        return Err(msg("解码器分配失败"));
    }
    if ffi::avcodec_parameters_to_context(ctx, par) < 0
        || ffi::avcodec_open2(ctx, dec, std::ptr::null_mut()) < 0
    {
        let mut c = ctx;
        ffi::avcodec_free_context(&mut c);
        return Err(msg("解码器打不开"));
    }
    (*ctx).thread_count = 2;
    Ok(ctx)
}

/// 把编码器吐出来的包全部写进封装器（喂完一帧 / 收尾时调用）。
unsafe fn write_packets(c: &mut Ctx, enc: *mut ffi::AVCodecContext, st: *mut ffi::AVStream) -> R<()> {
    let pkt = ffi::av_packet_alloc();
    if pkt.is_null() {
        return Err(msg("分配包失败"));
    }
    loop {
        let ret = ffi::avcodec_receive_packet(enc, pkt);
        if ret == AVERROR_EAGAIN || ret == AVERROR_EOF {
            break;
        }
        if ret < 0 {
            let mut p = pkt;
            ffi::av_packet_free(&mut p);
            return Err(msg(format!("编码器读包失败：{ret}")));
        }
        // 编码器的时间轴 → 输出流的时间轴。
        ffi::av_packet_rescale_ts(pkt, (*enc).time_base, (*st).time_base);
        (*pkt).stream_index = (*st).index;
        let w = ffi::av_interleaved_write_frame(c.ofmt, pkt);
        ffi::av_packet_unref(pkt);
        if w < 0 {
            let mut p = pkt;
            ffi::av_packet_free(&mut p);
            return Err(msg(format!("写包失败：{w}")));
        }
    }
    let mut p = pkt;
    ffi::av_packet_free(&mut p);
    Ok(())
}

/// 转换。失败时调用方（Kotlin）负责删半成品。
pub fn convert_to_wmv(input: &str, output: &str) -> R<()> {
    unsafe { run(input, output) }
}

unsafe fn run(input: &str, output: &str) -> R<()> {
    let in_c = CString::new(input).map_err(|_| msg("输入路径含 NUL"))?;
    let out_c = CString::new(output).map_err(|_| msg("输出路径含 NUL"))?;
    let mut c = Ctx::new();

    // ---- 打开输入、找流 ----
    if ffi::avformat_open_input(&mut c.ifmt, in_c.as_ptr(), std::ptr::null(), std::ptr::null_mut()) < 0 {
        return Err(msg(format!("打不开输入：{input}")));
    }
    if ffi::avformat_find_stream_info(c.ifmt, std::ptr::null_mut()) < 0 {
        return Err(msg("读不出流信息"));
    }
    c.vstream = ffi::av_find_best_stream(
        c.ifmt, ffi::AVMediaType::AVMEDIA_TYPE_VIDEO, -1, -1, std::ptr::null_mut(), 0,
    );
    c.astream = ffi::av_find_best_stream(
        c.ifmt, ffi::AVMediaType::AVMEDIA_TYPE_AUDIO, -1, -1, std::ptr::null_mut(), 0,
    );
    if c.vstream < 0 {
        return Err(msg("这个文件里没有视频轨"));
    }

    // ---- 解码器 ----
    c.vdec = open_decoder(c.stream(c.vstream))?;
    let mut has_audio = false;
    if c.astream >= 0 {
        match open_decoder(c.stream(c.astream)) {
            Ok(d) => {
                c.adec = d;
                has_audio = true;
            }
            Err(e) => ffi::log_err(&format!("{e}，只转视频")),
        }
    }

    // ---- 输出容器（ASF）----
    if ffi::avformat_alloc_output_context2(&mut c.ofmt, std::ptr::null(), cstr!("asf"), out_c.as_ptr()) < 0
        || c.ofmt.is_null()
    {
        return Err(msg("建不了 ASF 封装器"));
    }

    // ---- 视频编码器（wmv2）----
    let (vw, vh) = ((*c.vdec).width, (*c.vdec).height);
    if vw <= 0 || vh <= 0 {
        return Err(msg("源视频尺寸无效"));
    }
    if vw % 2 != 0 || vh % 2 != 0 {
        // wmv2 只吃偶数尺寸；补边要另写一层，这里明确拒绝而不是静默产出坏画面。
        return Err(msg(format!("源尺寸 {vw}x{vh} 是奇数，WMV 输出只支持偶数尺寸")));
    }
    let fps = {
        let fr = ffi::av_guess_frame_rate(c.ifmt, c.stream(c.vstream), std::ptr::null_mut());
        let mut f = if fr.num > 0 && fr.den > 0 { (fr.num as f64 / fr.den as f64 + 0.5) as c_int } else { 25 };
        if !(1..=120).contains(&f) {
            f = 25;
        }
        f
    };
    {
        let codec = ffi::avcodec_find_encoder_by_name(cstr!("wmv2"));
        if codec.is_null() {
            return Err(msg("这个构建里没有 wmv2 编码器"));
        }
        c.venc = ffi::avcodec_alloc_context3(codec);
        if c.venc.is_null() {
            return Err(msg("wmv2 编码器分配失败"));
        }
        (*c.venc).width = vw;
        (*c.venc).height = vh;
        (*c.venc).pix_fmt = ffi::AVPixelFormat::AV_PIX_FMT_YUV420P;
        (*c.venc).time_base = ffi::AVRational { num: 1, den: fps };
        (*c.venc).bit_rate = wmv_bitrate(vw, vh);
        (*c.venc).gop_size = fps; // 每秒一个关键帧
        (*c.venc).max_b_frames = 0;
        (*c.venc).thread_count = 2;
        // ASF 复用器声明了 `AVFMT_GLOBALHEADER`（`asfenc.c` 的 `ff_asf_muxer`），
        // 按 ffmpeg 的惯例给编码器也打上。
        (*c.venc).flags |= ffi::AV_CODEC_FLAG_GLOBAL_HEADER as c_int;
        if ffi::avcodec_open2(c.venc, codec, std::ptr::null_mut()) < 0 {
            return Err(msg("wmv2 编码器打不开"));
        }
    }
    let vst = ffi::avformat_new_stream(c.ofmt, std::ptr::null_mut());
    if vst.is_null() {
        return Err(msg("建不了视频输出流"));
    }
    (*vst).time_base = (*c.venc).time_base;
    if ffi::avcodec_parameters_from_context((*vst).codecpar, c.venc) < 0 {
        return Err(msg("视频流参数拷贝失败"));
    }

    // ---- 音频编码器（wmav2）+ 重采样器 ----
    //
    // ⚠️ `setup_audio` 返回的**输出流指针必须留着**：音频帧写包时要拿它的
    // `time_base` 做时间轴换算。早先这里写的是 `let _ = st;`，指针被丢掉，
    // 而 `AudioAcc::st` 的初值是 NULL → `write_packets` 里 `(*st).time_base`
    // 直接空指针解引用（实测 SIGSEGV，fault addr `0x20` 正是 `AVStream.time_base`
    // 的偏移）。修好 `wmav2` 的 `sample_fmt` 之后音频路径第一次真正跑起来，
    // 这个一直潜伏的空指针就露出来了。
    let mut ast_out: *mut ffi::AVStream = std::ptr::null_mut();
    if has_audio {
        match setup_audio(&mut c) {
            Ok(st) => ast_out = st,
            Err(e) => {
                ffi::log_err(&format!("{e}，只转视频"));
                has_audio = false;
            }
        }
    }

    // ---- 打开输出 + 写头 ----
    if ffi::avio_open(&mut (*c.ofmt).pb, out_c.as_ptr(), ffi::AVIO_FLAG_WRITE as c_int) < 0 {
        return Err(msg(format!("建不了输出文件：{output}")));
    }
    c.io_open = true;
    if ffi::avformat_write_header(c.ofmt, std::ptr::null_mut()) < 0 {
        return Err(msg("写 ASF 头失败"));
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
    let mut v_pts: i64 = 0;
    let mut a = AudioAcc::new(ast_out);

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
            if ffi::avcodec_send_packet(c.vdec, pkt) >= 0 {
                while ffi::avcodec_receive_frame(c.vdec, frame) == 0 {
                    let r = feed_video(&mut c, &*frame, vst, &mut v_pts);
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
        } else if has_audio && si == c.astream {
            if ffi::avcodec_send_packet(c.adec, pkt) >= 0 {
                while ffi::avcodec_receive_frame(c.adec, frame) == 0 {
                    let r = feed_audio(&mut c, frame, &mut a);
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
        }
        ffi::av_packet_unref(pkt);
    }

    // 分母对齐到真正过手的字节（同读方向，否则进度条停在 90 几）。
    let read = video_progress::bytes();
    let total = video_progress::total_bytes();
    if total > read {
        video_progress::adjust_total(-((total - read) as i64));
        video_progress::set_file(read);
    }

    // ---- 收尾：flush 两个解码器 → 收编码器 ----
    ffi::avcodec_send_packet(c.vdec, std::ptr::null());
    while ffi::avcodec_receive_frame(c.vdec, frame) == 0 {
        let r = feed_video(&mut c, &*frame, vst, &mut v_pts);
        ffi::av_frame_unref(frame);
        r?;
    }
    if has_audio {
        let adec = c.adec;
        ffi::avcodec_send_packet(adec, std::ptr::null());
        while ffi::avcodec_receive_frame(adec, frame) == 0 {
            let r = feed_audio(&mut c, frame, &mut a);
            ffi::av_frame_unref(frame);
            r?;
        }
        a.flush(&mut c)?;
    }
    {
        let mut p = pkt;
        let mut f = frame;
        ffi::av_packet_free(&mut p);
        ffi::av_frame_free(&mut f);
    }

    // 编码器 flush（发 NULL 帧）→ 写包。
    let venc = c.venc;
    ffi::avcodec_send_frame(venc, std::ptr::null());
    write_packets(&mut c, venc, vst)?;
    if has_audio {
        let (aenc, ast) = (c.aenc, a.st);
        ffi::avcodec_send_frame(aenc, std::ptr::null());
        write_packets(&mut c, aenc, ast)?;
    }

    if ffi::av_write_trailer(c.ofmt) < 0 {
        return Err(msg("写 ASF 尾失败"));
    }
    c.trailer_done = true;
    Ok(())
}

/// 音频编码器 + 重采样器。成功返回输出流指针。
unsafe fn setup_audio(c: &mut Ctx) -> R<*mut ffi::AVStream> {
    let codec = ffi::avcodec_find_encoder_by_name(cstr!("wmav2"));
    if codec.is_null() {
        return Err(msg("没有 wmav2 编码器"));
    }
    c.aenc = ffi::avcodec_alloc_context3(codec);
    if c.aenc.is_null() {
        return Err(msg("wmav2 编码器分配失败"));
    }
    let ch = (*c.adec).ch_layout.nb_channels.clamp(1, 2);
    ffi::av_channel_layout_default(&mut (*c.aenc).ch_layout, ch);
    (*c.aenc).sample_rate = (*c.adec).sample_rate;
    // ⚠️ **必须显式给采样格式**。ffmpeg 的 `encode_preinit_audio` 对
    // `AV_SAMPLE_FMT_NONE` 直接报 `Invalid audio sample format: -1` 并让
    // `avcodec_open2` 失败 —— 它**不会**替你挑一个（`encode.c:660` 就是那句
    // `if (!av_get_sample_fmt_name(avctx->sample_fmt)) return AVERROR(EINVAL);`）。
    // 之前这里写 NONE，于是 wmav2 永远打不开 → `setup_audio` 返回 Err →
    // `has_audio = false` → **每个 WMV 都是无声的**（实测复现）。
    // wmav2 只吃 FLTP / S16P（`wmaenc.c` 的 `sample_fmts`），用它的首选 FLTP。
    (*c.aenc).sample_fmt = ffi::AVSampleFormat::AV_SAMPLE_FMT_FLTP;
    (*c.aenc).bit_rate = if ch == 1 { 96_000 } else { 128_000 };
    // ASF 复用器声明了 `AVFMT_GLOBALHEADER`，按 ffmpeg 的惯例给编码器也打上，
    // 让 extradata（WMA 的 codec private）进全局头而不是混在码流里。
    (*c.aenc).flags |= ffi::AV_CODEC_FLAG_GLOBAL_HEADER as c_int;
    if ffi::avcodec_open2(c.aenc, codec, std::ptr::null_mut()) < 0 {
        return Err(msg("wmav2 编码器打不开"));
    }
    let ast = ffi::avformat_new_stream(c.ofmt, std::ptr::null_mut());
    if ast.is_null() {
        return Err(msg("建不了音频输出流"));
    }
    (*ast).time_base = (*c.aenc).time_base;
    if ffi::avcodec_parameters_from_context((*ast).codecpar, c.aenc) < 0 {
        return Err(msg("音频流参数拷贝失败"));
    }

    // 重采样器：源 → 编码器的格式/采样率/声道。
    let mut out_ch: ffi::AVChannelLayout = std::mem::zeroed();
    ffi::av_channel_layout_default(&mut out_ch, ch);
    let ok = ffi::swr_alloc_set_opts2(
        &mut c.swr,
        &(*c.aenc).ch_layout,
        (*c.aenc).sample_fmt,
        (*c.aenc).sample_rate,
        &(*c.adec).ch_layout,
        (*c.adec).sample_fmt,
        (*c.adec).sample_rate,
        0,
        std::ptr::null_mut(),
    ) >= 0
        && ffi::swr_init(c.swr) >= 0;
    ffi::av_channel_layout_uninit(&mut out_ch);
    if !ok {
        return Err(msg("重采样器起不来"));
    }
    Ok(ast)
}

/// 一帧解码视频 → 编码器 → 写包。
unsafe fn feed_video(
    c: &mut Ctx,
    f: &ffi::AVFrame,
    vst: *mut ffi::AVStream,
    v_pts: &mut i64,
) -> R<()> {
    // wmv2 只吃 yuv420p。源多半就是（我们这套解码器输出的都是），别的格式明确拒绝，
    // 不静默产出色偏的片子。
    if f.format != ffi::AVPixelFormat::AV_PIX_FMT_YUV420P as c_int {
        let name = ffi::av_get_pix_fmt_name(f.format);
        let name = if name.is_null() {
            format!("{}", f.format)
        } else {
            std::ffi::CStr::from_ptr(name).to_string_lossy().into_owned()
        };
        return Err(msg(format!("源像素格式 {name} 不支持 WMV 输出（需要 yuv420p）")));
    }
    if f.width != (*c.venc).width || f.height != (*c.venc).height {
        return Err(msg(format!(
            "帧尺寸 {}x{} 与编码器 {}x{} 不一致",
            f.width, f.height, (*c.venc).width, (*c.venc).height
        )));
    }
    // 逐帧顺序 PTS（编码器时间轴 = 1/fps）—— 简单、单调、帧距精确。
    let frame = f as *const ffi::AVFrame as *mut ffi::AVFrame;
    (*frame).pts = *v_pts;
    *v_pts += 1;
    let venc = c.venc;
    let s = ffi::avcodec_send_frame(venc, frame);
    if s < 0 {
        return Err(msg(format!("喂视频帧失败：{s}")));
    }
    write_packets(c, venc, vst)
}

/// 音频累积缓冲：把重采样后的平面浮点样本攒够一个 `frame_size` 再发一帧。
struct AudioAcc {
    /// 每声道一个平面。
    planes: Vec<Vec<f32>>,
    /// 已发出的总采样数（每声道），用作 PTS。
    pts: i64,
    st: *mut ffi::AVStream,
}

impl AudioAcc {
    /// `st` = 音频**输出流**（[`setup_audio`] 建的）。写包时要用它的 `time_base`，
    /// 传 NULL 会在 [`write_packets`] 里空指针解引用。
    fn new(st: *mut ffi::AVStream) -> Self {
        Self { planes: Vec::new(), pts: 0, st }
    }

    /// 收尾：不足一帧的尾巴补静音发掉。
    unsafe fn flush(&mut self, c: &mut Ctx) -> R<()> {
        if self.planes.is_empty() || self.planes[0].is_empty() {
            return Ok(());
        }
        self.emit(c, true)
    }

    /// 攒够一帧就发；`pad` = 补静音发掉不足一帧的尾巴。
    unsafe fn emit(&mut self, c: &mut Ctx, pad: bool) -> R<()> {
        let fs = (*c.aenc).frame_size as usize;
        if fs == 0 {
            return Err(msg("wmav2 的 frame_size 为 0"));
        }
        while self.planes[0].len() >= fs || (pad && !self.planes[0].is_empty()) {
            let aframe = ffi::av_frame_alloc();
            if aframe.is_null() {
                return Err(msg("分配音频帧失败"));
            }
            (*aframe).format = (*c.aenc).sample_fmt as c_int;
            (*aframe).sample_rate = (*c.aenc).sample_rate;
            (*aframe).nb_samples = fs as c_int;
            (*aframe).pts = self.pts;
            ffi::av_channel_layout_copy(&mut (*aframe).ch_layout, &(*c.aenc).ch_layout);
            if ffi::av_frame_get_buffer(aframe, 0) < 0 {
                let mut a = aframe;
                ffi::av_frame_free(&mut a);
                return Err(msg("分配音频帧缓冲失败"));
            }
            let n = fs.min(self.planes[0].len());
            for (ch, plane) in self.planes.iter_mut().enumerate() {
                let dst = (*aframe).data[ch] as *mut f32;
                // 前 n 个样本照抄，剩下的补 0（静音）。
                for i in 0..fs {
                    *dst.add(i) = if i < n { plane[i] } else { 0.0 };
                }
                plane.drain(..n);
            }
            self.pts += fs as i64;
            let aenc = c.aenc;
            let s = ffi::avcodec_send_frame(aenc, aframe);
            let mut a = aframe;
            ffi::av_frame_free(&mut a);
            if s < 0 {
                return Err(msg(format!("喂音频帧失败：{s}")));
            }
            write_packets(c, aenc, self.st)?;
            if !pad && self.planes[0].len() < fs {
                break;
            }
        }
        Ok(())
    }
}

/// 一帧解码音频 → 重采样 → 攒进 [AudioAcc]。
unsafe fn feed_audio(c: &mut Ctx, frame: *mut ffi::AVFrame, a: &mut AudioAcc) -> R<()> {
    let fs = (*c.aenc).frame_size as usize;
    if a.planes.is_empty() {
        let ch = (*c.aenc).ch_layout.nb_channels as usize;
        a.planes = vec![Vec::with_capacity(fs * 2); ch];
    }
    let out_n = ffi::swr_get_out_samples(c.swr, (*frame).nb_samples);
    if out_n <= 0 {
        return Ok(());
    }
    let mut out_arr: *mut *mut u8 = std::ptr::null_mut();
    let mut out_linesize: c_int = 0;
    if ffi::av_samples_alloc_array_and_samples(
        &mut out_arr,
        &mut out_linesize,
        (*c.aenc).ch_layout.nb_channels,
        out_n,
        (*c.aenc).sample_fmt,
        0,
    ) < 0
    {
        return Err(msg("分配重采样缓冲失败"));
    }
    let got = ffi::swr_convert(
        c.swr,
        out_arr,
        out_n,
        (*frame).extended_data as *const *const u8,
        (*frame).nb_samples,
    );
    if got > 0 {
        for (ch, plane) in a.planes.iter_mut().enumerate() {
            let src = *out_arr.add(ch) as *const f32;
            for i in 0..got as usize {
                plane.push(*src.add(i));
            }
        }
    }
    // 释放重采样缓冲。`av_samples_alloc_array_and_samples` 分配了两块：
    // 先 `av_freep(&data[0])` 放数据，再 `av_freep(&data)` 放指针数组
    // —— `av_freep` 收的是「指向指针的指针」，别把数据本身当参数传进去。
    ffi::av_freep(out_arr as *mut std::ffi::c_void);
    ffi::av_freep(&mut out_arr as *mut *mut *mut u8 as *mut std::ffi::c_void);
    if got <= 0 {
        return Ok(());
    }
    a.emit(c, false)
}
