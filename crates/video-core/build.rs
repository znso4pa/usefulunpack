//! 把「预编译的最小 ffmpeg 静态库」接到 Rust 侧，并生成 FFI 绑定。
//!
//! ffmpeg 本体由 `scripts/build_ffmpeg.sh` 交叉编译，静态库缓存在
//! `.ffmpeg-cache/build-<abi>/`（已 gitignore）。**本脚本不编 ffmpeg**，
//! 只做两件事：
//!   1. 告诉 rustc 去哪里找那些 `.a`，以及要链哪几个；
//!   2. 用 bindgen 按**当前 target** 生成 libav* 的绑定（arm64 与 armv7 的
//!      指针宽度不同，布局也就不同，不能共用一份）。
//!
//! ## 为什么 Android 目标缺缓存要**硬报错**
//!
//! 少了视频核心，APK 照样能编出来、装上去、其他格式照样能用 —— 只有「转视频」
//! 那一格会点不动。这种「静默少一个功能」比构建失败难查得多，所以在 Android
//! 目标上直接 `panic!`。
//!
//! ## 为什么非 Android 目标退化成空壳
//!
//! `cargo test --workspace` 是本仓的常规动作，而它跑在宿主上；宿主上没有
//! Android 的 ffmpeg 静态库。为了不把「跑个单测」变成「先交叉编一遍 ffmpeg」，
//! 非 Android 目标只设一个 cfg，crate 里编成一个空壳（测试照 AGENTS.md 的约定
//! 打 `SKIP`）。真实的解码链路只能在真机上验 —— 和 `crates/*/testdata` 一个道理。

use std::path::{Path, PathBuf};
use std::{env, fs};

/// 需要暴露给 Rust 的 ffmpeg 符号。**故意逐个点名**而不是 `av_*` 通配：
/// 通配会把三个头文件里近千个函数全生成出来，编译慢、体积大，而且大部分
/// 我们链进来也不会调（`--gc-sections` 之后不占体积，但白白拖慢构建）。
const FUNCS: &[&str] = &[
    // libavformat
    "avformat_open_input",
    "avformat_find_stream_info",
    "avformat_close_input",
    "av_read_frame",
    "av_find_best_stream",
    "av_guess_frame_rate",
    // libavcodec
    "avcodec_find_decoder",
    "avcodec_find_encoder_by_name",
    "avcodec_alloc_context3",
    "avcodec_parameters_to_context",
    "avcodec_parameters_from_context",
    "avcodec_open2",
    "avcodec_send_packet",
    "avcodec_receive_frame",
    "avcodec_send_frame",
    "avcodec_receive_packet",
    "avcodec_free_context",
    "avcodec_get_name",
    // 写方向（→wmv）：输出容器、写帧、时间戳换算。
    "avformat_alloc_output_context2",
    "avformat_free_context",
    "avformat_new_stream",
    "avformat_write_header",
    "av_interleaved_write_frame",
    "av_write_trailer",
    "avio_open",
    "avio_closep",
    "av_packet_rescale_ts",
    "av_frame_get_buffer",
    "av_get_pix_fmt_name",
    "av_samples_alloc_array_and_samples",
    // libavutil
    "av_packet_alloc",
    "av_packet_free",
    "av_packet_unref",
    "av_frame_alloc",
    "av_frame_free",
    "av_frame_unref",
    "av_rescale_q",
    "av_samples_alloc",
    "av_freep",
    "av_channel_layout_default",
    "av_channel_layout_copy",
    "av_channel_layout_uninit",
    // libswresample
    "swr_alloc_set_opts2",
    "swr_init",
    "swr_convert",
    "swr_get_out_samples",
    "swr_free",
    // NDK media（MediaCodec / MediaFormat / MediaMuxer）—— 同样交给 bindgen，
    // 手抄这套签名只会抄错。
    "AMediaCodec_createEncoderByType",
    "AMediaCodec_configure",
    "AMediaCodec_start",
    "AMediaCodec_stop",
    "AMediaCodec_delete",
    "AMediaCodec_dequeueInputBuffer",
    "AMediaCodec_getInputBuffer",
    "AMediaCodec_queueInputBuffer",
    "AMediaCodec_dequeueOutputBuffer",
    "AMediaCodec_getOutputBuffer",
    "AMediaCodec_releaseOutputBuffer",
    "AMediaCodec_getOutputFormat",
    "AMediaFormat_new",
    "AMediaFormat_delete",
    "AMediaFormat_setString",
    "AMediaFormat_setInt32",
    "AMediaMuxer_new",
    "AMediaMuxer_delete",
    "AMediaMuxer_start",
    "AMediaMuxer_stop",
    "AMediaMuxer_addTrack",
    "AMediaMuxer_writeSampleData",
];

const TYPES: &[&str] = &[
    "AVFormatContext",
    "AVOutputFormat",
    "AVIOContext",
    "AVStream",
    "AVCodecContext",
    "AVCodecParameters",
    "AVCodec",
    "AVFrame",
    "AVPacket",
    "AVRational",
    "AVChannelLayout",
    "AVChannelOrder",
    "SwrContext",
    "AVPixelFormat",
    "AVSampleFormat",
    "AVCodecID",
    "AVMediaType",
    "AMediaCodec",
    "AMediaFormat",
    "AMediaMuxer",
    "AMediaCodecBufferInfo",
    "media_status_t",
];

const VARS: &[&str] = &[
    "AV_NOPTS_VALUE",
    "AV_TIME_BASE",
    "AVIO_FLAG_WRITE",
    "AV_CODEC_FLAG_GLOBAL_HEADER",
    "AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG",
    "AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM",
    "AMEDIACODEC_CONFIGURE_FLAG_ENCODE",
    "AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED",
    "AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED",
    "AMEDIACODEC_INFO_TRY_AGAIN_LATER",
    "AMEDIA_OK",
    "AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4",
];

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    // 非 Android 目标会设这个 cfg 把 crate 编成空壳，先声明免得 rustc 报
    // `unexpected_cfgs`。
    println!("cargo::rustc-check-cfg=cfg(uu_no_ffmpeg)");

    let manifest = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let project = manifest
        .parent()
        .and_then(Path::parent)
        .expect("crates/video-core 应当在 <root>/crates/ 下面")
        .to_path_buf();
    let cache = project.join(".ffmpeg-cache");
    let src = cache.join("ffmpeg-7.1");

    let target = env::var("TARGET").unwrap();
    let abi = match target.as_str() {
        "aarch64-linux-android" => "arm64",
        "armv7-linux-androideabi" => "armv7",
        _ => {
            // 宿主（或任何非 Android 目标）：编成空壳，别把单测拖进交叉编译。
            println!("cargo:rustc-cfg=uu_no_ffmpeg");
            println!(
                "cargo:warning=video-core: 目标 {target} 不是 Android，编成空壳（无 ffmpeg）"
            );
            return;
        }
    };

    let build = cache.join(format!("build-{abi}"));
    if !build.join("libavformat/libavformat.a").is_file() {
        panic!(
            "video-core: 找不到 ffmpeg 静态库 {}\n\
             先跑一次 `bash scripts/build_ffmpeg.sh`（它会下载并交叉编译 ffmpeg，\
             产物缓存在 .ffmpeg-cache/）。",
            build.display()
        );
    }

    // ---- 1. 链接 ----
    for lib in ["avformat", "avcodec", "swresample", "avutil"] {
        println!(
            "cargo:rustc-link-search=native={}",
            build.join(format!("lib{lib}")).display()
        );
        println!("cargo:rustc-link-lib=static={lib}");
    }
    // NDK 自带的媒体栈（MediaCodec / MediaMuxer）与日志。
    for lib in ["mediandk", "log", "m"] {
        println!("cargo:rustc-link-lib=dylib={lib}");
    }
    // 静态库之间互相引用，rustc 默认不做组内重复解析。
    println!("cargo:rustc-link-arg=-Wl,--exclude-libs,ALL");
    // ffmpeg 是按 `-ffunction-sections -fdata-sections` 编的，不显式开 gc-sections
    // 的话**一个函数都没裁掉**，两个 ABI 加起来会多出好几百 KB。别删这一行。
    println!("cargo:rustc-link-arg=-Wl,--gc-sections");

    // ---- 2. 绑定 ----
    let ndk = find_ndk().unwrap_or_else(|| {
        panic!(
            "video-core: 找不到 NDK。bindgen 需要它来定位 Android 的 C 头文件；\
             设 ANDROID_NDK_HOME 或 ANDROID_HOME。"
        )
    });
    let sysroot = ndk
        .join("toolchains/llvm/prebuilt")
        .read_dir()
        .ok()
        .and_then(|d| {
            d.filter_map(|e| e.ok())
                .map(|e| e.path().join("sysroot"))
                .find(|p| p.is_dir())
        })
        .unwrap_or_else(|| panic!("video-core: NDK 里找不到 sysroot：{}", ndk.display()));

    // clang 的 target triple 要带 API level，否则找不到 Android 的 libc 头。
    let clang_target = match abi {
        "arm64" => "aarch64-linux-android26",
        _ => "armv7a-linux-androideabi26",
    };

    let mut builder = bindgen::Builder::default()
        // 用 header_contents 而不是落一个 .h 文件：内容就是六行 #include，
        // 单独一个文件只会多一个要同步维护的地方。
        .header_contents(
            "uu_video_ffi.h",
            "#include <libavcodec/avcodec.h>\n\
             #include <libavformat/avformat.h>\n\
             #include <libavutil/channel_layout.h>\n\
             #include <libavutil/mathematics.h>\n\
             #include <libavutil/pixdesc.h>\n\
             #include <libavutil/samplefmt.h>\n\
             #include <libswresample/swresample.h>\n\
             #include <media/NdkMediaCodec.h>\n\
             #include <media/NdkMediaFormat.h>\n\
             #include <media/NdkMediaMuxer.h>\n",
        )
        // configure 生成的 avconfig.h 在 build 目录，公共头在源码目录 —— 两个都要。
        .clang_arg(format!("-I{}", build.display()))
        .clang_arg(format!("-I{}", src.display()))
        .clang_arg(format!("--target={clang_target}"))
        .clang_arg(format!("--sysroot={}", sysroot.display()))
        // 枚举就按整数用，别生成一堆 newtype 转换。
        .default_enum_style(bindgen::EnumVariation::ModuleConsts)
        .layout_tests(false);
    for f in FUNCS {
        builder = builder.allowlist_function(*f);
    }
    for t in TYPES {
        builder = builder.allowlist_type(*t);
    }
    for v in VARS {
        builder = builder.allowlist_var(*v);
    }

    let bindings = builder.generate().expect("bindgen 生成 ffmpeg 绑定失败");

    let out = PathBuf::from(env::var("OUT_DIR").unwrap()).join("ffmpeg_bindings.rs");
    bindings
        .write_to_file(&out)
        .expect("写 ffmpeg_bindings.rs 失败");
}

/// 找 NDK：`ANDROID_NDK_HOME` 优先，其次 `ANDROID_HOME/ndk/` 里版本最高的那个。
/// 与 `scripts/build_ffmpeg.sh` 的取法保持一致。
fn find_ndk() -> Option<PathBuf> {
    if let Ok(p) = env::var("ANDROID_NDK_HOME") {
        let p = PathBuf::from(p);
        if p.is_dir() {
            return Some(p);
        }
    }
    let home = env::var("ANDROID_HOME").ok()?;
    let mut versions: Vec<PathBuf> = fs::read_dir(Path::new(&home).join("ndk"))
        .ok()?
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| p.is_dir())
        .collect();
    versions.sort();
    versions.pop()
}
