//! bindgen 生成的 FFI 绑定：ffmpeg（libavformat/libavcodec/libavutil/libswresample）
//! 加 NDK 的 media 栈（MediaCodec / MediaFormat / MediaMuxer）。
//!
//! 绑定在 `build.rs` 里按**当前 target** 现场生成 —— arm64 与 armv7 的指针宽度
//! 不同，同一份结构体布局不通用，所以不能像别的格式那样把声明写死。
//!
//! 非 Android 目标下 `build.rs` 不生成绑定（见那边的注释），整个模块被 cfg 掉。
#![allow(
    non_upper_case_globals,
    non_camel_case_types,
    non_snake_case,
    dead_code,
    improper_ctypes,
    clippy::all
)]

include!(concat!(env!("OUT_DIR"), "/ffmpeg_bindings.rs"));

/// 匿名枚举在 bindgen 里只能拿到 `_bindgen_ty_N` 这种名字，这里给两个有意义的别名。
/// `AMediaCodecBufferInfo::flags` 用的是前者，`AMediaCodec_configure` 的 flags 与
/// `dequeueOutputBuffer` 的负返回值用的是后者。
pub use _bindgen_ty_4 as BufferFlags;
pub use _bindgen_ty_5 as CodecInfo;

extern "C" {
    /// `liblog` 里唯一要用的一个。手写声明比把它塞进 bindgen 白名单短。
    fn __android_log_print(prio: std::os::raw::c_int, tag: *const std::os::raw::c_char, fmt: *const std::os::raw::c_char, ...) -> std::os::raw::c_int;
}

/// `AV_NOPTS_VALUE` 在头文件里是个带强制转换的宏（`((int64_t)UINT64_C(0x80...))`），
/// bindgen 生成不出来。它的定义就是 `INT64_MIN`，手写一份。
pub(crate) const AV_NOPTS_VALUE: i64 = i64::MIN;

const ANDROID_LOG_ERROR: std::os::raw::c_int = 6;

/// 打一条 error 级日志。失败信息同时会作为 `Err` 回到 Kotlin，这里只是留个现场。
pub(crate) fn log_err(m: &str) {
    let Ok(c) = std::ffi::CString::new(m) else {
        return;
    };
    unsafe {
        __android_log_print(
            ANDROID_LOG_ERROR,
            b"video_core\0".as_ptr().cast(),
            b"%s\0".as_ptr().cast(),
            c.as_ptr(),
        );
    }
}
