//! WMV / ASF → MP4（H.264 + AAC）。
//!
//! 本仓其余格式都是「纯 Rust 解析器 + JNI」，视频这条路多了一个 C 依赖 ——
//! **ffmpeg**。原因很直接：VC-1 / WMV3 没有可用的纯 Rust 解码器（社区那几个
//! crate 都是刚发布、下载量两位数的个人项目，进不了发布版），而 Android 自带的
//! 解码器只认自己那几种容器。
//!
//! ## 为什么 ffmpeg 只负责「解」
//!
//! ```text
//!   WMV/ASF ──ffmpeg 解封装+解码──► AVFrame / PCM ──Android 编码──► MP4
//!             (libavformat/avcodec)                  (MediaCodec/Muxer)
//! ```
//!
//! * **体积**：ffmpeg 自带 mpeg4+aac 编码器的那套配置，两个 ABI 合计 6.07 MB，
//!   超了 5 MB 的门槛；只留解码器是 3.15 MB。编解码这一半是 Android 白送的。
//! * **兼容性**：ffmpeg 里**没有** H.264 编码器（libx264 是 GPL），而 MediaCodec
//!   出的是硬件 H.264 + AAC，播放器兼容性最好。
//! * **许可证**：`LGPL-2.1-or-later`、`CONFIG_GPL 0`。动态链接一个未修改的 LGPL
//!   库配 MIT 应用是标准做法。
//!
//! ## 分层
//!
//! | 文件 | 职责 |
//! |---|---|
//! | [`ffi`] | `build.rs` 用 bindgen 现场生成的 ffmpeg + NDK media 绑定 |
//! | [`nv12`] | yuv420p → NV12（MediaCodec 的 `color-format 21` 要的排布） |
//! | [`convert`] | 整条链路：解封装 → 解码 → 编码 → 封装，含进度与取消 |
//!
//! ## 进度与取消
//!
//! 沿用全仓约定：**一个 .so 一份全局进度槽**，Kotlin 侧轮询静态量，取消是一次
//! 置位。所以 `OpScheduler` 的 key 用 `"video"`（音频那边是 `"audio"`），两个
//! `.so` 各管各的，不能合并成一个槽。
//!
//! ## 构建
//!
//! ffmpeg 由 `scripts/build_ffmpeg.sh` 交叉编译并缓存在 `.ffmpeg-cache/`
//! （gitignore），`build.rs` 只负责链接 + 生成绑定。**非 Android 目标**（也就是
//! 宿主上的 `cargo test --workspace`）会把本 crate 编成一个空壳，不去碰 ffmpeg ——
//! 真实链路只能在真机上验，和 `crates/*/testdata` 一个道理。

#[cfg(not(uu_no_ffmpeg))]
mod convert;
#[cfg(not(uu_no_ffmpeg))]
mod ffi;
#[cfg(not(uu_no_ffmpeg))]
mod nv12;

use archive_common::progress_store;
// 本 crate 自己的进度槽。`#[macro_export]` 之后宏挂在 `archive_common` 根上，
// 生成的 `video_progress` 模块则长在这里。
progress_store!(video_progress);

use jni::objects::JClass;
use jni::sys::{jlong, jstring};
use jni::JNIEnv;

// 只有 `videoConvert` 用到这几个，非 Android 目标下它整个被 cfg 掉。
#[cfg(not(uu_no_ffmpeg))]
use jni::objects::JString;
#[cfg(not(uu_no_ffmpeg))]
use jni::sys::{jboolean, JNI_FALSE, JNI_TRUE};

/// 转换失败的原因。取消要能和真失败区分开 —— 取消不是错误，Kotlin 那边不该弹
/// 「转换失败」。
#[cfg(not(uu_no_ffmpeg))]
#[derive(Debug)]
pub enum ConvertError {
    Cancelled,
    Message(String),
}

#[cfg(not(uu_no_ffmpeg))]
impl std::fmt::Display for ConvertError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Cancelled => f.write_str("cancelled"),
            Self::Message(m) => f.write_str(m),
        }
    }
}

/// JNI 崩溃护栏：任何 panic 都变成 `Err`，不让它穿到 Java 层把进程带走。
#[cfg(not(uu_no_ffmpeg))]
fn guarded<T: Send + 'static>(
    f: impl FnOnce() -> Result<T, ConvertError> + Send + 'static,
) -> Result<T, ConvertError> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|panic| {
        let msg = panic
            .downcast_ref::<&str>()
            .copied()
            .or_else(|| panic.downcast_ref::<String>().map(|s| s.as_str()))
            .unwrap_or("unknown panic");
        Err(ConvertError::Message(format!("panic: {msg}")))
    })
}

/// 转换一个视频文件。
///
/// `target` / `options` 目前没用上：目标只有 MP4 一种，选项（码率按分辨率自动取）
/// 也还没有面板。参数先留着，和 `AudioCore.audioConvert` 保持同一个签名形状，
/// 以后加选项不用改符号名。
#[cfg(not(uu_no_ffmpeg))]
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_VideoCore_videoConvert(
    mut e: JNIEnv,
    _: JClass,
    _tool: JString,
    input: JString,
    output: JString,
    _target: JString,
    _options: JString,
) -> jboolean {
    // 每个新操作都先清取消位：上一次的取消不能毒死这一次。
    video_progress::clear_cancel();
    let input = archive_common::s(&mut e, &input);
    let output = archive_common::s(&mut e, &output);
    match guarded(move || convert::convert(&input, &output)) {
        Ok(()) => JNI_TRUE,
        Err(er) => {
            let _ = e.throw_new("java/io/IOException", format!("video: {er}"));
            JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_VideoCore_videoProgressCount(
    _: JNIEnv,
    _: JClass,
) -> jlong {
    video_progress::bytes() as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_VideoCore_videoProgressTotal(
    _: JNIEnv,
    _: JClass,
) -> jlong {
    video_progress::total_bytes() as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_VideoCore_videoProgressFileCount(
    _: JNIEnv,
    _: JClass,
) -> jlong {
    video_progress::file_bytes() as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_VideoCore_videoProgressFileTotal(
    _: JNIEnv,
    _: JClass,
) -> jlong {
    video_progress::file_total() as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_VideoCore_videoProgressName(
    e: JNIEnv,
    _: JClass,
) -> jstring {
    match e.new_string(video_progress::name()) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_VideoCore_videoCancel(_: JNIEnv, _: JClass) {
    video_progress::cancel();
}
