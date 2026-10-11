//! 独立 oracle：把产出的文件交给 **ffprobe** 认一认。
//!
//! 我们自己的解码器读得回来，只证明「自己写的自己读得懂」；证明不了别人也读得懂。
//! OGG 的分页是我们按规范手写的（ident 独占第一页、comment+setup 同页、末包
//! EndStream），这种自洽最没有说服力 —— 所以要一个第三方解码器点头。
//!
//! 找不到 ffprobe 时**整支跳过**（打印 SKIP 后 return），不 fail：本仓的构建与
//! 测试不该依赖 ffmpeg 存在。这也是为什么它没写进 `cargo test` 的必过集。

use std::path::Path;
use std::process::Command;

/// 跑一次 ffprobe，返回 `key=value` 形式的流信息；ffprobe 不可用或读不出时 None。
fn ffprobe(path: &Path) -> Option<String> {
    let out = Command::new("ffprobe")
        .args([
            "-v",
            "error",
            "-show_entries",
            "stream=codec_name,sample_rate,channels,bit_rate",
            "-of",
            "default=noprint_wrappers=1",
        ])
        .arg(path)
        .output()
        .ok()?;
    if !out.status.success() {
        return None;
    }
    Some(String::from_utf8_lossy(&out.stdout).into_owned())
}

fn have_ffprobe() -> bool {
    Command::new("ffprobe")
        .arg("-version")
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

/// 从 ffprobe 的 `key=value` 输出里取一个字段。
fn field(info: &str, key: &str) -> Option<String> {
    info.lines()
        .find_map(|l| l.strip_prefix(&format!("{key}=")).map(|v| v.trim().to_string()))
}

fn tmpdir(tag: &str) -> std::path::PathBuf {
    let d = std::env::temp_dir().join(format!("uu_audio_oracle_{tag}_{}", std::process::id()));
    std::fs::create_dir_all(&d).unwrap();
    d
}

/// 造一个 1 秒 44.1 kHz 立体声 440 Hz 正弦 WAV 当所有转换的源。
fn make_wav(dir: &Path) -> std::path::PathBuf {
    let p = dir.join("src.wav");
    let spec = hound::WavSpec {
        channels: 2,
        sample_rate: 44_100,
        bits_per_sample: 16,
        sample_format: hound::SampleFormat::Int,
    };
    let mut w = hound::WavWriter::create(&p, spec).unwrap();
    for i in 0..44_100usize {
        let s = (i as f32 / 44_100.0 * 440.0 * std::f32::consts::TAU).sin() * 0.5;
        let v = (s * 32767.0) as i16;
        w.write_sample(v).unwrap();
        w.write_sample(v).unwrap();
    }
    w.finalize().unwrap();
    p
}

/// 三个目标格式的产出都必须被第三方解码器认出正确的编码、采样率和声道数。
#[test]
fn ffprobe_reads_everything_we_write() {
    if !have_ffprobe() {
        println!("SKIP ffprobe_reads_everything_we_write: no ffprobe on PATH");
        return;
    }
    let dir = tmpdir("basic");
    let src = make_wav(&dir);

    // (目标, ffprobe 眼中的编码名)
    let cases = [("wav", "pcm_s16le"), ("mp3", "mp3"), ("ogg", "vorbis")];
    for (fmt, codec) in cases {
        let out = dir.join(format!("out.{fmt}"));
        archive_audio_core::convert_host(
            src.to_str().unwrap(),
            out.to_str().unwrap(),
            fmt,
            "",
        )
        .unwrap_or_else(|e| panic!("convert → {fmt}: {e}"));

        let info = ffprobe(&out).unwrap_or_else(|| panic!("{fmt}: ffprobe 读不出来"));
        assert_eq!(
            field(&info, "codec_name").as_deref(),
            Some(codec),
            "{fmt}: ffprobe 认成了别的编码\n{info}"
        );
        assert_eq!(field(&info, "sample_rate").as_deref(), Some("44100"), "{fmt}\n{info}");
        assert_eq!(field(&info, "channels").as_deref(), Some("2"), "{fmt}\n{info}");
    }
    std::fs::remove_dir_all(&dir).ok();
}

/// 选项必须被**外部工具**看见，不只是被我们自己的解码器看见。
#[test]
fn ffprobe_sees_the_options_we_applied() {
    if !have_ffprobe() {
        println!("SKIP ffprobe_sees_the_options_we_applied: no ffprobe on PATH");
        return;
    }
    let dir = tmpdir("opts");
    let src = make_wav(&dir);

    // 22.05 kHz 单声道 8 位 WAV —— 三样都在容器头里，ffprobe 一眼就能看出。
    let down = dir.join("down.wav");
    archive_audio_core::convert_host(
        src.to_str().unwrap(),
        down.to_str().unwrap(),
        "wav",
        "rate=22050;ch=1;depth=8",
    )
    .unwrap();
    let info = ffprobe(&down).unwrap();
    assert_eq!(field(&info, "codec_name").as_deref(), Some("pcm_u8"), "{info}");
    assert_eq!(field(&info, "sample_rate").as_deref(), Some("22050"), "{info}");
    assert_eq!(field(&info, "channels").as_deref(), Some("1"), "{info}");

    // 32 位 WAV 一律按浮点写 —— 外部看来必须是 pcm_f32le，不是 pcm_s32le。
    let f32w = dir.join("f32.wav");
    archive_audio_core::convert_host(
        src.to_str().unwrap(),
        f32w.to_str().unwrap(),
        "wav",
        "depth=32",
    )
    .unwrap();
    let info = ffprobe(&f32w).unwrap();
    assert_eq!(field(&info, "codec_name").as_deref(), Some("pcm_f32le"), "{info}");

    // CBR 的 MP3 码率是硬的：选 96 kbps 就要在 ±10% 以内。
    let mp3 = dir.join("cbr96.mp3");
    archive_audio_core::convert_host(
        src.to_str().unwrap(),
        mp3.to_str().unwrap(),
        "mp3",
        "br=96",
    )
    .unwrap();
    let info = ffprobe(&mp3).unwrap();
    let br: f64 = field(&info, "bit_rate").unwrap().parse().unwrap();
    assert!(
        (86_000.0..106_000.0).contains(&br),
        "MP3 请求 96 kbps，ffprobe 看到 {br} bps\n{info}"
    );

    std::fs::remove_dir_all(&dir).ok();
}

/// 用 ffmpeg 把一个音频文件解成原始 `s16le` 交错样本 —— 拿来当**独立 oracle**。
fn ffmpeg_pcm(path: &Path) -> Option<Vec<i16>> {
    let out = Command::new("ffmpeg")
        .args(["-v", "error", "-i"])
        .arg(path)
        .args(["-f", "s16le", "-acodec", "pcm_s16le", "-"])
        .output()
        .ok()?;
    if !out.status.success() {
        return None;
    }
    Some(
        out.stdout
            .chunks_exact(2)
            .map(|c| i16::from_le_bytes([c[0], c[1]]))
            .collect(),
    )
}

fn have_ffmpeg() -> bool {
    Command::new("ffmpeg")
        .arg("-version")
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

/// 新增的三个输出格式，第三方解码器都要认得出正确的编码。
#[test]
fn ffprobe_reads_the_new_containers_we_write() {
    if !have_ffprobe() {
        println!("SKIP ffprobe_reads_the_new_containers_we_write: no ffprobe on PATH");
        return;
    }
    let dir = tmpdir("newfmt");
    let src = make_wav(&dir);

    // (目标, 默认位深下 ffprobe 眼中的编码名)
    // AIFF / AU 都是**大端**，所以是 `be` 而不是 `le` —— 写反了这里立刻红。
    let cases = [("aiff", "pcm_s16be"), ("au", "pcm_s16be"), ("aac", "aac")];
    for (fmt, codec) in cases {
        let out = dir.join(format!("out.{fmt}"));
        archive_audio_core::convert_host(
            src.to_str().unwrap(),
            out.to_str().unwrap(),
            fmt,
            "",
        )
        .unwrap_or_else(|e| panic!("convert → {fmt}: {e}"));

        let info = ffprobe(&out).unwrap_or_else(|| panic!("{fmt}: ffprobe 读不出来"));
        assert_eq!(
            field(&info, "codec_name").as_deref(),
            Some(codec),
            "{fmt}: ffprobe 认成了别的编码\n{info}"
        );
        assert_eq!(field(&info, "sample_rate").as_deref(), Some("44100"), "{fmt}\n{info}");
        assert_eq!(field(&info, "channels").as_deref(), Some("2"), "{fmt}\n{info}");
    }
    std::fs::remove_dir_all(&dir).ok();
}

/// 我们写出来的 AAC（裸 ADTS）必须能被 ffmpeg **完整解码** —— 不只是认出编码，
/// 而是每一帧都解得开、不报错。ADTS 头是手拼的，7 个字段里任何一个错位都会
/// 在这里暴露。
#[test]
fn ffmpeg_fully_decodes_our_aac_output() {
    if !have_ffmpeg() {
        println!("SKIP ffmpeg_fully_decodes_our_aac_output: no ffmpeg on PATH");
        return;
    }
    let dir = tmpdir("aacout");
    let src = make_wav(&dir);
    let out = dir.join("out.aac");
    archive_audio_core::convert_host(src.to_str().unwrap(), out.to_str().unwrap(), "aac", "")
        .unwrap();

    // 先确认「解出来有东西」。
    let pcm = ffmpeg_pcm(&out).expect("ffmpeg 解不开我们写的 AAC");
    assert!(pcm.len() > 44_100, "AAC 输出只有 {} 个样本", pcm.len());
    let peak = pcm.iter().fold(0i32, |m, &s| m.max((s as i32).abs()));
    assert!(peak > 8000, "AAC 解码后峰值只有 {peak}，波形不像真的");

    // 再确认 ffmpeg 没抱怨：`-v error` 下 stderr 必须是空的。
    let chk = Command::new("ffmpeg")
        .args(["-v", "error", "-i"])
        .arg(&out)
        .args(["-f", "null", "-"])
        .output()
        .unwrap();
    let err = String::from_utf8_lossy(&chk.stderr);
    assert!(chk.status.success(), "ffmpeg 解码失败：{err}");
    assert!(err.trim().is_empty(), "ffmpeg 报了错：\n{err}");

    std::fs::remove_dir_all(&dir).ok();
}

/// 反向 oracle：**ffmpeg 造出来的**源文件（FLAC / M4A / ADTS / AIFF / AU），
/// 我们解回来再写成 WAV，两边交给 ffmpeg 解成同一份 `s16le` 逐样本比。
///
/// 这是唯一能证明「我们的定标、字节序、编码器延迟处理都对」的检查 ——
/// 自己写自己读的往返测试对这些都是瞎的（大端写反了照样能自洽）。
///
/// 样本目录由 `UU_AUDIO_SAMPLES` 指定；没设就整支跳过。
#[test]
fn our_decoders_match_ffmpeg_on_real_sources() {
    if !have_ffmpeg() {
        println!("SKIP our_decoders_match_ffmpeg_on_real_sources: no ffmpeg on PATH");
        return;
    }
    let Some(dir) = std::env::var_os("UU_AUDIO_SAMPLES") else {
        println!("SKIP our_decoders_match_ffmpeg_on_real_sources: UU_AUDIO_SAMPLES 未设置");
        return;
    };
    let dir = std::path::PathBuf::from(dir);
    let out_dir = tmpdir("decoded");
    let cases = ["flac", "m4a", "aac", "aiff", "au"];
    let mut checked = 0usize;
    for ext in cases {
        let name = match ext {
            "flac" => "sine.flac",
            "m4a" => "sine.m4a",
            "aac" => "sine.aac",
            "aiff" => "sine.aiff",
            _ => "sine.au",
        };
        let src = dir.join(name);
        if !src.exists() {
            println!("SKIP {ext}: {} 不存在", src.display());
            continue;
        }
        let ours = out_dir.join(format!("ours_{ext}.wav"));
        archive_audio_core::convert_host(
            src.to_str().unwrap(),
            ours.to_str().unwrap(),
            "wav",
            "",
        )
        .unwrap_or_else(|e| panic!("{ext}: 我们解不开：{e}"));

        let want = ffmpeg_pcm(&src).unwrap_or_else(|| panic!("{ext}: ffmpeg 解不开源文件"));
        let got = ffmpeg_pcm(&ours).unwrap_or_else(|| panic!("{ext}: ffmpeg 解不开我们的产出"));

        // 长度先对齐：无损格式必须精确一致，有损格式（M4A/ADTS）允许容器级的
        // 几帧出入，但绝不允许「少了一整段 priming」这种量级的偏差。
        let lossless = matches!(ext, "flac" | "aiff" | "au");
        if lossless {
            assert_eq!(
                got.len(),
                want.len(),
                "{ext}: 无损源的长度必须精确一致（我们 {} vs ffmpeg {}）",
                got.len(),
                want.len()
            );
        } else {
            let ratio = got.len() as f64 / want.len() as f64;
            assert!(
                (0.97..1.03).contains(&ratio),
                "{ext}: 长度差太多（我们 {} vs ffmpeg {}，比值 {ratio:.4}）",
                got.len(),
                want.len()
            );
        }

        let n = got.len().min(want.len());
        let worst = (0..n).fold(0i32, |m, i| m.max((got[i] as i32 - want[i] as i32).abs()));
        // 实测（0.5 幅度 440 Hz 正弦）：无损源 0，M4A 1，ADTS 1。
        // 容差给到 64 —— 对「整体错位」这种错，这个数字会直接跳到几千
        // （半个周期的相移就是 3 万），所以收紧到 64 反而更能抓到真问题。
        let tol = if lossless { 4 } else { 64 };
        assert!(
            worst <= tol,
            "{ext}: 与 ffmpeg 逐样本最大偏差 {worst}（容差 {tol}）—— 定标/字节序/对齐有问题"
        );
        println!("{ext}: 与 ffmpeg 最大偏差 {worst}");
        checked += 1;
    }
    assert!(checked > 0, "一个样本都没检查到");
    std::fs::remove_dir_all(&out_dir).ok();
}
