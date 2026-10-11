//! WMV/ASF → MP4 的转换链路。
//!
//! ```text
//!   av_read_frame ──► ffmpeg 解码 ──► AVFrame ──► NV12 ──► AMediaCodec(H.264) ─┐
//!        │                    │                                               ├─► AMediaMuxer ──► MP4
//!        └──────────────► ffmpeg 解码 ──► swresample ──► PCM ──► AMediaCodec(AAC) ┘
//! ```
//!
//! ffmpeg 只负责**解**（ASF 解封装 + VC-1/WMV3/MSMPEG4/MPEG4 + WMA 解码），
//! **编**和**封**交给 Android 自己的 `AMediaCodec` / `AMediaMuxer`：
//!
//! * 体积：ffmpeg 自带 mpeg4+aac 编码器的那版两 ABI 合计 6.07 MB，超了 5 MB 门槛；
//!   只留解码器是 3.15 MB。编解码这一半等于白送。
//! * 兼容性：ffmpeg 里**没有** H.264 编码器（libx264 是 GPL），而 MediaCodec 出的是
//!   硬件 H.264 + AAC，播放器兼容性最好。
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
//! 我们自己按喂进去的采样数累加，不信容器。

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
    /// 首帧的时间戳不能拿 `last_v_us` 的初值 0 去比 —— 见 `feed_video`。
    have_v_pts: bool,
    /// 源首帧的 PTS（µs），用来把整条视频轨归零。见 `feed_video` 里那段说明。
    v_base_us: i64,
    last_a_us: i64,
    out_rate: c_int,
    out_channels: c_int,
    vw: c_int,
    vh: c_int,
    frame_dur_us: i64,

    swr: *mut ffi::SwrContext,
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
            if !self.venc.is_null() {
                ffi::AMediaCodec_stop(self.venc);
                ffi::AMediaCodec_delete(self.venc);
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
    fn maybe_start_muxer(&mut self) -> R<()> {
        if self.mux_started {
            return Ok(());
        }
        if self.has_video && self.vtrack < 0 {
            return Ok(());
        }
        if self.has_audio && self.atrack < 0 {
            return Ok(());
        }
        if !self.has_video && !self.has_audio {
            return Err(msg("没有可用的轨"));
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
                let res = if info.size > 0 && !buf.is_null() {
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
                self.maybe_start_muxer()?;
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

        // 整条视频轨**归零**：首帧的 PTS 记成基准，其后一律减去它。
        //
        // 源里那个起始偏移是**复用器的 muxdelay**，不是内容真的晚开始 ——
        // 实测 MPEG-PS 音视频都从 0.54 s 起、MPEG-TS 从 1.44 s 起，AVI/FLV 从 0 起。
        // 不减掉它，视频轨从 0.54 s 开始、而音频轨按采样数从 0 累加（见 `feed_audio`
        // 的 `last_a_us`），两条轨就整整错开一个 muxdelay —— 画面和声音对不上，
        // 容器时长还凭空多出这一段（实测 3.00 s 的源出来 3.54 s）。AVI/FLV 源恰好
        // 从 0 起，所以这个错位一直没暴露。
        //
        // 前提：源里音频与视频的起始时刻基本一致（我们支持的五种容器都如此，
        // muxdelay 是整包生效的）。真带「音轨故意晚 N 秒」的源会丢掉那个偏移。
        //
        // 时间戳只允许前进。首帧要**单独判**：`last_v_us` 初值是 0，直接写
        // `us <= last_v_us` 会把源里合法的首帧 PTS=0 当成「回退」，把它推到一个
        // 帧长之后；下一帧又跟它相等，于是级联下去 —— 整条视频平移一个帧长，
        // 末帧还会被挤出容器（实测 455 帧的样本变成 454 帧、首帧从 0.000 变 0.040）。
        if !self.have_v_pts {
            self.v_base_us = if us >= 0 { us } else { 0 };
            self.have_v_pts = true;
            us = if us >= 0 { us - self.v_base_us } else { 0 };
        } else {
            if us >= 0 {
                us -= self.v_base_us;
            }
            if us < 0 || us <= self.last_v_us {
                us = self.last_v_us + self.frame_dur_us;
            }
        }
        self.last_v_us = us;

        let venc = self.venc;
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
                    return Err(msg(format!(
                        "H.264 编码器输入缓冲太小（{cap} < {need}）"
                    )));
                }
                unsafe {
                    frame_to_nv12(
                        f,
                        buf,
                        f.width as usize,
                        f.height as usize,
                        self.vw as usize,
                        self.vh as usize,
                    );
                    ffi::AMediaCodec_queueInputBuffer(venc, idx as usize, 0, need, us as u64, 0);
                }
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
                unsafe {
                    std::ptr::copy_nonoverlapping(pcm.as_ptr().add(off), buf, n);
                    ffi::AMediaCodec_queueInputBuffer(
                        aenc,
                        idx as usize,
                        0,
                        n,
                        self.last_a_us as u64,
                        0,
                    );
                }
                self.last_a_us += (n / frame_bytes) as i64 * 1_000_000 / self.out_rate as i64;
                off += n;
                self.drain(aenc, false, 0)?;
            } else {
                self.drain(aenc, false, 0)?;
            }
        }
        Ok(())
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
        have_v_pts: false,
        v_base_us: 0,
        last_a_us: 0,
        out_rate: 0,
        out_channels: 0,
        vw: 0,
        vh: 0,
        frame_dur_us: 0,
        swr: std::ptr::null_mut(),
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
        {
            let par = (*c.stream(c.vstream)).codecpar;
            let codec_id = (*par).codec_id;
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

        // ---- 音频解码器（拿不到就只转视频）----
        if c.astream >= 0 {
            let par = (*c.stream(c.astream)).codecpar;
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

        c.has_video = true;
        c.vw = ((*c.vdec).width + 1) & !1;
        c.vh = ((*c.vdec).height + 1) & !1;

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
            // COLOR_FormatYUV420SemiPlanar —— 见 nv12.rs。
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
        if ffi::AMediaCodec_start(c.venc) != ffi::media_status_t::AMEDIA_OK {
            return Err(msg("H.264 编码器启动失败"));
        }

        // ---- AAC 编码器 + 重采样器（任一失败就只转视频）----
        if c.has_audio {
            c.out_rate = pick_rate((*c.adec).sample_rate);
            let ch = (*c.adec).ch_layout.nb_channels.clamp(1, 2);
            c.out_channels = ch;

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
                } else {
                    let mut out_ch: ffi::AVChannelLayout = std::mem::zeroed();
                    ffi::av_channel_layout_default(&mut out_ch, c.out_channels);
                    let ok = ffi::swr_alloc_set_opts2(
                        &mut c.swr,
                        &out_ch,
                        ffi::AVSampleFormat::AV_SAMPLE_FMT_S16,
                        c.out_rate,
                        &(*c.adec).ch_layout,
                        (*c.adec).sample_fmt,
                        (*c.adec).sample_rate,
                        0,
                        std::ptr::null_mut(),
                    ) >= 0
                        && ffi::swr_init(c.swr) >= 0;
                    ffi::av_channel_layout_uninit(&mut out_ch);
                    if !ok {
                        ffi::log_err("重采样器起不来，只转视频");
                        c.has_audio = false;
                        ffi::AMediaCodec_stop(c.aenc);
                        ffi::AMediaCodec_delete(c.aenc);
                        c.aenc = std::ptr::null_mut();
                    }
                }
            }
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
                if ffi::avcodec_send_packet(c.vdec, pkt) >= 0 {
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
                if ffi::avcodec_send_packet(c.adec, pkt) >= 0 {
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
        ffi::avcodec_send_packet(c.vdec, std::ptr::null());
        while ffi::avcodec_receive_frame(c.vdec, frame) == 0 {
            let r = c.feed_video(&*frame);
            ffi::av_frame_unref(frame);
            r?;
        }
        if c.has_audio {
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
        c.finish_encoder(venc, true, c.last_v_us)?;
        if c.has_audio {
            c.finish_encoder(aenc, false, c.last_a_us)?;
        }
        // 两个编码器都收完了 muxer 还没起来 = 一帧都没产出。不拦的话会交出一个
        // 0 字节的 MP4 还报成功。
        if !c.mux_started {
            return Err(msg("编码器没有产出任何数据"));
        }

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
