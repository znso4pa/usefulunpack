//! 源文件「健康度」：容器**声明**的时长，以及这次转换实际解出了多少。
//!
//! 起因是截断的 MP4 一路成功：`moov` 完好、`av_read_frame` 到数据尽头正常 EOF，
//! 于是整条链路报告「转换完成」，交出一个只有源三分之一长的小片子。这里把
//! 「声明时长 vs 实际时长」这一对数记下来，交给 UI 去提示。
//!
//! 与进度那几个静态量一样，这是**一次操作的全局状态**（每个 `.so` 一个槽，
//! 操作在调度器上串行）—— 不做成参数一路传，是因为它只在最后一步用得上。

use std::os::raw::c_int;
use std::sync::atomic::{AtomicI32, Ordering};

use crate::ffi;

/// 上一次转换实际解出的百分比：`0` = 正常或判断不了，`1..=99` = 只解出这么多。
static DECODED_PCT: AtomicI32 = AtomicI32::new(0);

/// 每次操作入口清一次 —— 上一次的结论不能污染这一次。
pub fn reset() {
    DECODED_PCT.store(0, Ordering::Relaxed);
}

/// 记下「实际解出 / 声明」的对比结果。判定规则在 [`crate::trunc::decoded_percent`]。
pub fn report(declared_us: i64, actual_us: i64) {
    DECODED_PCT.store(
        crate::trunc::decoded_percent(declared_us, actual_us),
        Ordering::Relaxed,
    );
}

pub fn decoded_percent() -> i32 {
    DECODED_PCT.load(Ordering::Relaxed)
}

/// 容器声明的**视频**时长（µs）；拿不到返回 0。
///
/// 优先用视频流自己的 `duration`（那才是「这条视频轨应该有多长」），拿不到才退回
/// 容器的总时长。用总时长有误报风险 —— 源可以是「3 秒音频 + 1 秒视频」，
/// 拿 3 秒去比 1 秒就会把正常文件报成截断。
///
/// 必须在 `avformat_find_stream_info` 之后调。
pub unsafe fn declared_video_us(ifmt: *mut ffi::AVFormatContext, vstream: c_int) -> i64 {
    if ifmt.is_null() || vstream < 0 {
        return 0;
    }
    let st = *(*ifmt).streams.add(vstream as usize);
    if !st.is_null() {
        let dur = (*st).duration;
        let tb = (*st).time_base;
        if dur > 0 && tb.num > 0 && tb.den > 0 {
            let us = ffi::av_rescale_q(dur, tb, ffi::AVRational { num: 1, den: 1_000_000 });
            if us > 0 {
                return us;
            }
        }
    }
    // 容器总时长按 `AV_TIME_BASE`（= 1e6）记，正好就是 µs。
    let total = (*ifmt).duration;
    if total > 0 && total != ffi::AV_NOPTS_VALUE {
        return total;
    }
    0
}

/// 把「第 `n` 帧之后、按 `fps` 播」换算成 µs。给逐帧计数的路径（WMV）用。
pub fn frames_to_us(frames: i64, fps: c_int) -> i64 {
    if frames <= 0 || fps <= 0 {
        return 0;
    }
    frames.saturating_mul(1_000_000) / fps as i64
}
