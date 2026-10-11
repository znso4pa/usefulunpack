#!/bin/bash
#
# 交叉编译「只解码」的最小 ffmpeg，给 crates/video-core 链接用。
#
# 源码与静态库缓存在 .ffmpeg-cache/（已 gitignore），配置没变就跳过重编。
# **这个脚本不产出任何 .so** —— 链接和 JNI 导出都在 Rust 那边
# （crates/video-core/build.rs 负责把 .ffmpeg-cache/build-<abi>/lib*.a 接上）。
#
# ⚠️ 三个必须踩对的点，错一个体积就假了、或者干脆编不出东西：
#   * `--ar` / `--ranlib` 必须显式指向 NDK 的 llvm-*。不写的话 configure 用宿主机的
#     Apple `ar`，它会把 ELF 成员**静默丢弃** —— make 返回成功，产出 96 字节的空 .a，
#     最后链出一个几 KB 的假 .so。
#   * `--disable-everything` **管不到** avfilter / avdevice / swscale / postproc，
#     这四个是独立子库，必须逐个点名关掉（漏了 swscale 光它就多 ~1.1 MB）。
#   * 体积只能按「最终 .so」量：`-ffunction-sections -fdata-sections` +
#     `-Wl,--gc-sections` 之后才是真实的 APK 增重。所以这里编 ffmpeg 时必须带上
#     function-sections，否则 Rust 那边再怎么 gc 也裁不动。
#
# 依赖：$ANDROID_NDK_HOME（或 $ANDROID_HOME/ndk/<最新>）、curl、make。
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
FFMPEG_VERSION=7.1
CACHE="$PROJECT_DIR/.ffmpeg-cache"
SRC="$CACHE/ffmpeg-$FFMPEG_VERSION"

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk" ]; then
        ANDROID_NDK_HOME=$(ls -d "$ANDROID_HOME/ndk/"*/ | sort -r | head -1)
    else
        echo "[!] 找不到 NDK：请设 ANDROID_NDK_HOME"
        exit 1
    fi
fi
NDK="${ANDROID_NDK_HOME%/}"
TC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64"
[ -d "$TC" ] || TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
if [ ! -d "$TC" ]; then
    echo "[!] NDK 工具链不存在：$NDK"
    exit 1
fi

API=26

# ---- 编解码清单（6.3 扩过一次，改之前先读完这段）----
#
# 起点是 WMV 世代：真样本实测出现过的 wmv3 / wmv2 / msmpeg4v3 / mpeg4 + wmav2，
# 再带上同族近亲（vc1、wmv1、msmpeg4v1/v2、wmapro/wmavoice、mp3）。
# 6.3 把源容器从「只有 ASF」扩到「老视频容器」——AVI / FLV / MPEG-PS / MPEG-TS。
#
# ⚠️ 体积只能按**最终 .so** 量。`-ffunction-sections` + `--gc-sections` 之后，
# `.o` 增量大约只剩 50~54% 会落进 `.so`（实测 +1.01 MB `.o` → +0.51 MB `.so`/ABI）。
# 已量过的事实：
#   * 基线（只有 ASF）    arm64 2,204,432 B / armv7 1,876,612 B，合计 3.89 MB
#   * 本清单             arm64 2,716,664 B / armv7 2,383,380 B，合计 **5.10 MB**
#     （+1.01 MB `.o` → 两个 ABI 各 +0.50 MB）
#   * 再加 VP8/VP9/Theora  arm64 单边就 +2.05 MB —— **不要**。
#     注意 `h264qpel.o`(745 KB) 与 `h264pred.o`(273 KB) **不是 H.264 解码器**，
#     是 VP8/VP9 选中的（它们复用 H.264 的帧内预测与四分之一像素插值 DSP），
#     所以「我只要 VP8」实际上会连 H.264 那套 DSP 一起拖进来。
#   * `mov.o`(238 KB) 是单个最大的成员，MP4/MOV 解复用器 —— 没有 H.264 解码器时
#     它只能解 MPEG-4 Part 2 / MPEG-2 装进 MP4 的少数情况，收益不抵体积，故不启用。
#   * H.264 解码器从未启用过：libx264 是 GPL，且 C 解码器本身约 1 MB/ABI。
#     视频**编码**走 Android 的 MediaCodec（硬件 H.264），不需要 ffmpeg 出编码器。
#   * `aac` 与 `aac_fixed` **一样大**（后者是定点版，SBR/PS 一个不少），换不掉。
#
# 本轮花掉的 1.01 MB `.o` 明细（arm64，按大小）：AAC 全家 200 KB、MJPEG 82 KB、
# AC-3/E-AC-3 67 KB、VP6 61 KB、MPEG-1/2 video 54 KB、解复用器 157 KB
# （mpegts 66 + avidec 42 + flvdec 38 + isom 11）、PCM/ADPCM 35 KB。每一项都在出力，
# 没有可以白扔的。要压回去只能砍覆盖：砍 `vp6,vp6a,vp6f` 省 62 KB（但 FLV 最常见的
# 就是 VP6），砍 `mjpeg` 再省 85 KB。
#
# 名字必须用 configure 的名字，不是 `ffmpeg -demuxers` 打印的运行时名字 ——
# 两者**不一样**，而且 configure 对拼错的组件名是**静默忽略**的（不报错、不编进去）。
# 实测栽过一次：MPEG-PS 的 configure 名是 **`mpegps`**（由 `allformats.c` 里的
# `extern const FFInputFormat ff_mpegps_demuxer;` 推出），而 `ffmpeg -demuxers`
# 打的是结构体里的 `.p.name = "mpeg"`。照后者写 `--enable-demuxer=mpeg` 会静默
# 少一个解复用器，只有真机解一个 .mpg 时才发现。FLV 解码器同理叫 `flv`（codec id
# 才是 flv1）。下面 `verify_components` 会逐个核对生成的注册表，把这类错误挡在编译期。
VIDEO_DECODERS="vc1,wmv1,wmv2,wmv3,msmpeg4v1,msmpeg4v2,msmpeg4v3,mpeg4,\
mpeg1video,mpeg2video,h263,flv,mjpeg,vp6,vp6a,vp6f"
AUDIO_DECODERS="wmav1,wmav2,wmapro,wmavoice,mp3,mp2,aac,ac3,eac3,\
pcm_s16le,pcm_s16be,pcm_u8,pcm_s24le,pcm_f32le,pcm_mulaw,pcm_alaw,\
adpcm_ms,adpcm_ima_wav,adpcm_ima_qt"
PARSERS="vc1,mpeg4video,mpegvideo,h263,mjpeg,aac,ac3,mpegaudio"
# `mpegvideo` / `m4v` **不是**给「裸基本流」用的，是为了 MPEG-PS 的 codec 探测。
# MPEG-PS 的视频流在 `mpegps` 里 codec_id 恒为 NONE（只从 PSM 取，而 PSM 是可选的、
# 多数 .mpg 没有），它靠 `request_probe=1` 让 avformat_find_stream_info 去反推；而反推
# 走的是 `av_probe_input_format3`，**只遍历已注册的解复用器**（libavformat/demux.c 的
# `set_codec_from_probe_data` 里那张表按名字匹配 `mpegvideo` / `m4v`）。少了这两个，
# 真机上打开 .mpg 会得到 `codec_id = NONE` → 报「没有 none 的解码器」，而 .ts 不受影响
#（mpegts 从 PMT 直接拿 codec）。实测栽过一次。
# 抢格式的风险为零：`mpegvideo_probe` 的第一条就是 `!pspack`，带 pack 头的 PS 文件
# 探测恒返回 0，所以它只在「流内 PES 载荷」上生效，不会顶掉 `mpegps`。
DEMUXERS="asf,avi,flv,mpegps,mpegts,mpegvideo,m4v"

# ---- 解复用器 → parser 的耦合（**另一条静默失败的坑**）----
#
# MPEG-PS / MPEG-TS 把流标成 `need_parsing = AVSTREAM_PARSE_FULL`：PES 载荷**不是
# 帧对齐**的，必须靠 parser 切帧。demux.c:1411 找不到 parser 时只打一条 verbose 日志，
# 然后**原样输出未对齐的包** —— 解码器拿不到完整帧头，几乎解不出东西，而且**一个错都不报**：
# 转换照常「成功」，输出里那条音轨只剩开头几帧。
# 实测：MP2 音轨（3.0 s / 115 个包）产出 6 帧 AAC；同批的 AC-3 正常，因为我们有 `ac3` parser。
#
# 规则：下面这些「可能出现在 MPEG-PS/TS 里」的音频解码器，**开了就必须同时开 parser**。
# `verify_components` 会逐条核对，少一条就编不过。
DECODER_NEEDS_PARSER="mp1:mpegaudio mp2:mpegaudio mp3:mpegaudio aac:aac ac3:ac3 eac3:ac3"

# 逐个核对请求的组件真的进了注册表。**没有这道闸，拼错一个名字的代价是静默的**：
# configure 成功、make 成功、`.a` 照样产出，只是那个格式在真机上解不了。
verify_components() {
    local abi="$1" build="$2" name
    for name in ${DEMUXERS//,/ }; do
        grep -q "ff_${name}_demuxer" "$build/libavformat/demuxer_list.c" 2>/dev/null || {
            echo "[!] $abi: 解复用器 '$name' 没进注册表 —— configure 名拼错了？"; return 1; }
    done
    for name in ${VIDEO_DECODERS//,/ } ${AUDIO_DECODERS//,/ }; do
        grep -q "ff_${name}_decoder" "$build/libavcodec/codec_list.c" 2>/dev/null || {
            echo "[!] $abi: 解码器 '$name' 没进注册表 —— configure 名拼错了？"; return 1; }
    done
    for name in ${PARSERS//,/ }; do
        grep -q "ff_${name}_parser" "$build/libavcodec/parser_list.c" 2>/dev/null || {
            echo "[!] $abi: 解析器 '$name' 没进注册表 —— configure 名拼错了？"; return 1; }
    done
    # 解复用器 → parser 的耦合（见 DECODER_NEEDS_PARSER 上方那段）：开了某个
    # 「可能出现在 MPEG-PS/TS 里」的音频解码器，就必须有对应的 parser，否则那条音轨
    # 会**静默**只剩几帧。这条闸管的是「忘了加」，上面那条管的是「名字拼错」。
    local pair dec par
    for pair in $DECODER_NEEDS_PARSER; do
        dec="${pair%%:*}"; par="${pair##*:}"
        case ",$AUDIO_DECODERS," in *",$dec,"*)
            case ",$PARSERS," in *",$par,"*) ;;
            *) echo "[!] $abi: 解码器 '$dec' 已启用但没有 parser '$par' —— MPEG-PS/TS 里这条音轨会静默丢帧"; return 1 ;;
            esac ;;
        esac
    done
    return 0
}

COMMON_CONFIG="--disable-everything \
--disable-programs --disable-doc --disable-network --disable-autodetect \
--disable-avfilter --disable-avdevice --disable-swscale --disable-postproc \
--disable-debug --disable-iconv --disable-sdl2 --disable-xlib --disable-zlib \
--enable-static --disable-shared --enable-pic \
--enable-demuxer=$DEMUXERS --enable-protocol=file,pipe \
--enable-decoder=$VIDEO_DECODERS,$AUDIO_DECODERS --enable-parser=$PARSERS \
--extra-cflags='-ffunction-sections -fdata-sections -O2' \
--extra-ldflags='-Wl,--gc-sections'"

# ---- 1. 源码 ----
mkdir -p "$CACHE"
if [ ! -d "$SRC" ]; then
    TARBALL="$CACHE/ffmpeg-$FFMPEG_VERSION.tar.xz"
    if [ ! -f "$TARBALL" ]; then
        echo "  下载 ffmpeg-$FFMPEG_VERSION 源码..."
        curl -fsSL -o "$TARBALL" \
            "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" || {
            echo "[!] 下载失败"; exit 1; }
    fi
    echo "  解压..."
    tar -C "$CACHE" -xf "$TARBALL"
fi

# ---- 2. 逐 ABI 编 ffmpeg ----
build_abi() {
    local ABI="$1" ARCH CPU TRIPLE
    case "$ABI" in
        arm64) ARCH=aarch64; CPU=armv8-a; TRIPLE=aarch64-linux-android ;;
        armv7) ARCH=arm;     CPU=armv7-a; TRIPLE=armv7a-linux-androideabi ;;
        *) echo "[!] 未知 ABI $ABI"; return 1 ;;
    esac
    local CC="$TC/bin/${TRIPLE}${API}-clang"
    local BUILD="$CACHE/build-$ABI"
    local FULL_CONFIG="--target-os=android --arch=$ARCH --cpu=$CPU --enable-cross-compile \
--cc=$CC --ar=$TC/bin/llvm-ar --ranlib=$TC/bin/llvm-ranlib --nm=$TC/bin/llvm-nm \
--strip=$TC/bin/llvm-strip --sysroot=$TC/sysroot $COMMON_CONFIG"

    mkdir -p "$BUILD"
    # 配置指纹：一致就不重编（全量 build.sh 里这一层能省两分钟）
    local HASH
    HASH=$(printf '%s' "$FULL_CONFIG" | shasum -a 256 | cut -c1-16)
    if [ -f "$BUILD/.config-hash" ] && [ "$(cat "$BUILD/.config-hash")" = "$HASH" ] \
       && [ -s "$BUILD/libavcodec/libavcodec.a" ]; then
        echo "  $ABI: 复用已有构建"
    else
        echo "  $ABI: 配置 ffmpeg..."
        ( cd "$BUILD" && eval "\"$SRC/configure\" $FULL_CONFIG" > configure.log 2>&1 ) || {
            echo "[!] $ABI configure 失败"; tail -25 "$BUILD/configure.log"; return 1; }
        # 注册表在 configure 阶段就生成好了，所以名字拼错在这里就能挡住 ——
        # 不必等两分钟的 make，更不必等到真机上发现某个格式解不开。
        verify_components "$ABI" "$BUILD" || return 1
        echo "  $ABI: 编译 ffmpeg..."
        ( cd "$BUILD" && make -j"$(sysctl -n hw.ncpu 2>/dev/null || echo 4)" > make.log 2>&1 ) || {
            echo "[!] $ABI make 失败"; tail -25 "$BUILD/make.log"; return 1; }
        # 空档检查：Apple ar 的坑就是这个，宁可在这里炸也别链出一个假 .so
        if [ ! -s "$BUILD/libavcodec/libavcodec.a" ] || \
           [ "$(stat -f%z "$BUILD/libavcodec/libavcodec.a" 2>/dev/null || echo 0)" -lt 10000 ]; then
            echo "[!] $ABI libavcodec.a 是空的 —— 检查 --ar 是不是指向 llvm-ar"
            return 1
        fi
        printf '%s' "$HASH" > "$BUILD/.config-hash"
    fi

    # 复用已有构建时也要核一遍：`build-arm64/` 可能来自一份旧配置（或一份被手工
    # 改过的 config.h），而 `.config-hash` 只认字符串、不认注册表。
    verify_components "$ABI" "$BUILD" || return 1

    local SZ
    SZ=$(stat -f%z "$BUILD/libavcodec/libavcodec.a" 2>/dev/null || stat -c%s "$BUILD/libavcodec/libavcodec.a")
    printf "  %s: libavcodec.a %d KB\n" "$ABI" "$((SZ / 1024))"
}

build_abi arm64
build_abi armv7

echo "  ✅ ffmpeg static libs ready (crates/video-core/build.rs 会去 .ffmpeg-cache/ 取)"
