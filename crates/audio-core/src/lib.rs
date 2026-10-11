//! 音频格式转换：MP3 / OGG / WAV 两两互转。
//!
//! 整条链路是纯 Rust 的，没有一个 C 依赖 —— 本仓不发 C 代码，也不想为了一个
//! 「转个格式」把 ffmpeg 拖进来（体积 + 许可证 + NDK 交叉编译三重代价）。
//!
//! ```text
//!   输入 ──► decode ──► Pcm(交错 f32) ──► resample ──► encode ──► 输出
//!   mp3/ogg/wav          [-1,1]            rubato        mp3/ogg/wav
//! ```
//!
//! 三个解码器/编码器各只认识自己那一种格式，中间靠 [`pcm::Pcm`] 解耦：加一个
//! 格式只需要在 `decode`/`encode` 各加一个分支，选项、重采样、进度、JNI
//! 全都不用动。
//!
//! 依赖全部是宽松许可（Apache-2.0 / MIT / BSD-3-Clause），**没有 copyleft**：
//! 解码用 `rusty_mp3` + `lewton` + `hound`，编码用 `rusty_mp3` + `rusty_vorbis`
//! + `hound`，重采样用 `rubato`。`symphonia` 本可以一个 crate 覆盖 MP3/Vorbis/WAV，
//! 但它是 MPL-2.0，所以每个格式各挑了一个宽松许可的实现。
//!
//! ## 进度
//!
//! 总进度 = (读进来的输入字节 + 写出去的输出字节) / (输入长度 + **估算的**输出长度)。
//! 两个分子都是真的过手的字节，只有分母里那一半是估算（VBR 的输出长度本来就
//! 只有编完才知道）。相位条（下条）在解码阶段量「输入读了多少」，在编码阶段量
//! 「输出写了多少」。

mod aac;
mod aiff;
mod au;
mod counted;
mod decode;
mod encode;
mod flac;
mod options;
mod pcm;
mod resample;
#[cfg(test)]
mod testutil;

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use archive_common::{extract_progress, s};
use options::AudioOptions;
use pcm::Pcm;

/// JNI 崩溃护栏：任何 panic 都变成 `Err`，不让它穿到 Java 层把进程带走。
fn guarded<T: Send + 'static>(
    f: impl FnOnce() -> Result<T, String> + Send + 'static,
) -> Result<T, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)).unwrap_or_else(|panic| {
        let msg = panic
            .downcast_ref::<&str>()
            .copied()
            .or_else(|| panic.downcast_ref::<String>().map(|s| s.as_str()))
            .unwrap_or("unknown panic");
        Err(format!("panic: {msg}"))
    })
}

/// 转换一个音频文件。失败时**删掉半成品** —— 一个 0 字节或截断的输出文件比
/// 一个明确的错误更糟：用户会以为转换成功了，直到播放时才发现。
pub fn convert(
    input: &str,
    output: &str,
    target: &str,
    opts: &AudioOptions,
) -> Result<(), String> {
    let r = convert_inner(input, output, target, opts);
    if r.is_err() {
        let _ = std::fs::remove_file(output);
    }
    r
}

fn convert_inner(
    input: &str,
    output: &str,
    target: &str,
    opts: &AudioOptions,
) -> Result<(), String> {
    let in_len = std::fs::metadata(input)
        .map_err(|e| format!("stat {input}: {e}"))?
        .len();
    if in_len == 0 {
        return Err("input file is empty".to_string());
    }

    // 解码阶段：分母 = 输入长度，相位条量「输入读了多少」。
    extract_progress::reset(in_len);
    extract_progress::set_file(in_len);
    extract_progress::set_name(&counted::file_name_of(output));

    let mut audio = decode::decode(input)?;

    // 声道数和采样率在编码前定下来，顺序无所谓 —— 两者互不影响。
    audio = audio.with_channels(opts.channels);
    audio = resample::resample(audio, opts.sample_rate)?;
    audio = enforce_target_channels(audio, target, opts)?;

    // 编码阶段：把估算的输出长度并进总分母，相位条改量「输出写了多少」。
    // `adjust_total` 而不是 `reset`：已经读进来的那些输入字节要继续算在进度里。
    let est = encode::estimate_output_bytes(&audio, target, opts);
    extract_progress::adjust_total(est as i64);
    extract_progress::set_file(est);

    encode::encode(&audio, output, target, opts)?;
    Ok(())
}

/// 目标格式对声道数的硬性要求。目前只有一条：**OGG 必须是立体声**。
///
/// `rusty_vorbis` 只内嵌了一套 stereo setup（`SETUP_Q4_STEREO`），喂单声道进去
/// 它直接拒，报的还是上游内部的黑话：
/// `vorbis encode: InvalidData("vorbis setup: bad coupling channels")`。
/// 真机上就是这么炸的 —— 单测全用立体声，没人发现。
///
/// 两种情形分开处理，因为「用户要什么」不一样：
/// - **明确选了单声道** → 报错。不能默默改成双声道：那是在用户背后改他选的参数。
/// - **只是「保持源」，源恰好是单声道** → 升混。单声道复制到两个声道不丢任何
///   内容，比拒绝一个完全合理的请求（「把这个单声道 wav 转成 ogg」）好。
fn enforce_target_channels(pcm: Pcm, target: &str, opts: &AudioOptions) -> Result<Pcm, String> {
    if target != "ogg" || pcm.channels == 2 {
        return Ok(pcm);
    }
    if opts.channels == 1 {
        return Err(
            "OGG output requires 2 channels: the bundled encoder only ships a stereo setup"
                .to_string(),
        );
    }
    Ok(pcm.with_channels(2))
}

/// 给宿主侧（非 Android）测试与命令行用的入口：选项走同一个字符串协议。
#[doc(hidden)]
pub fn convert_host(
    input: &str,
    output: &str,
    target: &str,
    options: &str,
) -> Result<(), String> {
    extract_progress::clear_cancel();
    convert(input, output, target, &AudioOptions::parse(options))
}

/// 诊断用：解码后返回 (采样率, 声道, 帧数)。测试与命令行检查用。
#[doc(hidden)]
pub fn probe(input: &str) -> Result<(u32, u16, usize), String> {
    let p = decode::decode(input)?;
    Ok((p.rate, p.channels, p.frames()))
}

// ------------------------------------------------------------------ JNI

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_AudioCore_audioConvert(
    mut e: JNIEnv,
    _: JClass,
    _tool: JString,
    input: JString,
    output: JString,
    target: JString,
    options: JString,
) -> jboolean {
    // 每个新操作都先清取消位：上一次的取消不能毒死这一次。
    extract_progress::clear_cancel();
    let input = s(&mut e, &input);
    let output = s(&mut e, &output);
    let target = s(&mut e, &target);
    let opts = AudioOptions::parse(&s(&mut e, &options));
    match guarded(move || convert(&input, &output, &target, &opts)) {
        Ok(()) => JNI_TRUE,
        Err(er) => {
            let _ = e.throw_new("java/io/IOException", format!("audio: {er}"));
            JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_AudioCore_audioProgressCount(
    _: JNIEnv,
    _: JClass,
) -> jlong {
    extract_progress::bytes() as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_AudioCore_audioProgressTotal(
    _: JNIEnv,
    _: JClass,
) -> jlong {
    extract_progress::total_bytes() as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_AudioCore_audioProgressFileCount(
    _: JNIEnv,
    _: JClass,
) -> jlong {
    extract_progress::file_bytes() as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_AudioCore_audioProgressFileTotal(
    _: JNIEnv,
    _: JClass,
) -> jlong {
    extract_progress::file_total() as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_AudioCore_audioProgressName(
    e: JNIEnv,
    _: JClass,
) -> jstring {
    e.new_string(&extract_progress::name())
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_usefulunpacker_AudioCore_audioCancel(_: JNIEnv, _: JClass) {
    extract_progress::cancel();
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pcm::Pcm;
    use crate::testutil::tmpdir;
    use options::AudioOptions;

    /// 造一个真的 WAV 当所有转换的源。
    fn make_wav(dir: &std::path::Path, name: &str, rate: u32, ch: u16) -> String {
        let n = (rate as f32 * 0.3) as usize;
        let mut v = Vec::with_capacity(n * ch as usize);
        for i in 0..n {
            let s = (i as f32 / rate as f32 * 440.0 * std::f32::consts::TAU).sin() * 0.5;
            for _ in 0..ch {
                v.push(s);
            }
        }
        let pcm = Pcm::new(rate, ch, v);
        let p = dir.join(name);
        encode::encode_wav(&pcm, p.to_str().unwrap(), &AudioOptions::default()).unwrap();
        p.to_str().unwrap().to_string()
    }

    /// 三格式两两互转的完整矩阵：每种组合都必须产出一个能被解回来的文件。
    /// 这是「引擎接通了」的总闸，单独的编码器测试证明不了编排是对的。
    #[test]
    fn full_conversion_matrix() {
        let dir = tmpdir("matrix");
        let wav = make_wav(&dir, "src.wav", 44_100, 2);

        // 先造出 mp3 / ogg 两个源。
        let mp3 = dir.join("src.mp3");
        let ogg = dir.join("src.ogg");
        convert(&wav, mp3.to_str().unwrap(), "mp3", &AudioOptions::default()).unwrap();
        convert(&wav, ogg.to_str().unwrap(), "ogg", &AudioOptions::default()).unwrap();

        let sources = [("wav", wav.clone()), ("mp3", mp3.to_str().unwrap().to_string()), ("ogg", ogg.to_str().unwrap().to_string())];
        for (sname, sfile) in &sources {
            for target in ["wav", "mp3", "ogg"] {
                let out = dir.join(format!("{sname}2{target}.{target}"));
                convert(sfile, out.to_str().unwrap(), target, &AudioOptions::default())
                    .unwrap_or_else(|e| panic!("{sname} → {target}: {e}"));
                let (rate, ch, frames) = probe(out.to_str().unwrap())
                    .unwrap_or_else(|e| panic!("{sname} → {target} probe: {e}"));
                assert_eq!(rate, 44_100, "{sname} → {target}: rate");
                assert_eq!(ch, 2, "{sname} → {target}: channels");
                assert!(frames > 0, "{sname} → {target}: no frames");
            }
        }
        std::fs::remove_dir_all(&dir).ok();
    }

    /// 选项真的被应用了：改采样率、改声道、改位深，产出的文件头里要看得见。
    #[test]
    fn options_reach_the_output() {
        let dir = tmpdir("opts");
        let wav = make_wav(&dir, "src.wav", 44_100, 2);

        // 22.05 kHz 单声道 8 位 WAV。
        let out = dir.join("down.wav");
        let opts = AudioOptions::parse("rate=22050;ch=1;depth=8");
        convert(&wav, out.to_str().unwrap(), "wav", &opts).unwrap();
        let rdr = hound::WavReader::open(out.to_str().unwrap()).unwrap();
        assert_eq!(rdr.spec().sample_rate, 22_050);
        assert_eq!(rdr.spec().channels, 1);
        assert_eq!(rdr.spec().bits_per_sample, 8);

        // 48 kHz 立体声 MP3 —— 从单声道 8 位 WAV 升上去。
        let up = dir.join("up.mp3");
        let opts = AudioOptions::parse("rate=48000;ch=2;br=128");
        convert(out.to_str().unwrap(), up.to_str().unwrap(), "mp3", &opts).unwrap();
        let (rate, ch, _) = probe(up.to_str().unwrap()).unwrap();
        assert_eq!(rate, 48_000);
        assert_eq!(ch, 2);

        std::fs::remove_dir_all(&dir).ok();
    }

    /// 空输入要报错，而不是产出一个 0 字节的「成功」文件。
    #[test]
    fn empty_input_fails_and_leaves_no_output() {
        let dir = tmpdir("empty");
        let empty = dir.join("empty.wav");
        std::fs::write(&empty, b"").unwrap();
        let out = dir.join("out.mp3");
        let e = convert(empty.to_str().unwrap(), out.to_str().unwrap(), "mp3", &AudioOptions::default())
            .unwrap_err();
        assert!(e.contains("empty"), "got {e}");
        assert!(!out.exists(), "失败时必须删掉半成品");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// 坏文件也必须删掉半成品。
    #[test]
    fn garbage_input_fails_and_leaves_no_output() {
        let dir = tmpdir("garbage");
        let bad = dir.join("bad.mp3");
        std::fs::write(&bad, vec![0xABu8; 4096]).unwrap();
        let out = dir.join("out.wav");
        assert!(convert(bad.to_str().unwrap(), out.to_str().unwrap(), "wav", &AudioOptions::default()).is_err());
        assert!(!out.exists());
        std::fs::remove_dir_all(&dir).ok();
    }

    /// 单声道源 → OGG：升混，不许失败。
    ///
    /// `rusty_vorbis` 只内嵌了 stereo setup，喂单声道进去它会报
    /// `InvalidData("vorbis setup: bad coupling channels")`。这条是**真机上炸出来的**
    /// —— 此前所有单测的源都是立体声，谁也没覆盖到「源本身是单声道」。
    #[test]
    fn ogg_from_a_mono_source_is_upmixed_not_rejected() {
        let dir = tmpdir("oggmono");
        let src = make_wav(&dir, "mono.wav", 44_100, 1);
        let out = dir.join("mono.ogg");
        convert(&src, out.to_str().unwrap(), "ogg", &AudioOptions::default())
            .expect("单声道源转 OGG 必须成功（升混），不能把合理请求拒掉");
        let (rate, ch, frames) = probe(out.to_str().unwrap()).unwrap();
        assert_eq!(rate, 44_100);
        assert_eq!(ch, 2, "OGG 输出恒为立体声");
        assert!(frames > 0);
        std::fs::remove_dir_all(&dir).ok();
    }

    /// 明确要单声道 + OGG：报错，且消息要能看懂（不是上游那句内部黑话）。
    #[test]
    fn explicit_mono_ogg_is_rejected_with_a_readable_message() {
        let dir = tmpdir("oggmono2");
        let src = make_wav(&dir, "src.wav", 44_100, 2);
        let out = dir.join("m.ogg");
        let e = convert(&src, out.to_str().unwrap(), "ogg", &AudioOptions::parse("ch=1"))
            .unwrap_err();
        assert!(e.contains("2 channels"), "错误消息要说清是声道数问题，got {e}");
        assert!(!out.exists(), "失败时必须删掉半成品");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// 真机上的原始组合：`rate=22050;ch=1` 转 OGG。采样率不是原因，声道数才是 ——
    /// 把这条钉住，免得以后有人以为是 22050 不合法。
    #[test]
    fn mono_is_the_ogg_blocker_not_the_sample_rate() {
        let dir = tmpdir("oggr");
        let src = make_wav(&dir, "src.wav", 44_100, 2);
        // 只改采样率：成功。
        let a = dir.join("a.ogg");
        convert(&src, a.to_str().unwrap(), "ogg", &AudioOptions::parse("rate=22050")).unwrap();
        assert_eq!(probe(a.to_str().unwrap()).unwrap().0, 22_050);
        // 加上单声道：失败。
        let b = dir.join("b.ogg");
        assert!(convert(&src, b.to_str().unwrap(), "ogg", &AudioOptions::parse("rate=22050;ch=1")).is_err());
        assert!(!b.exists());
        std::fs::remove_dir_all(&dir).ok();
    }

    /// 单声道在别的目标格式上完全正常 —— 别把 OGG 的限制误伤到全局。
    #[test]
    fn mono_still_works_for_the_other_targets() {
        let dir = tmpdir("monoother");
        let src = make_wav(&dir, "src.wav", 44_100, 2);
        for target in ["wav", "mp3"] {
            let out = dir.join(format!("m.{target}"));
            convert(&src, out.to_str().unwrap(), target, &AudioOptions::parse("ch=1"))
                .unwrap_or_else(|e| panic!("{target} 的单声道输出不该被拒: {e}"));
            assert_eq!(probe(out.to_str().unwrap()).unwrap().1, 1, "{target}: 声道数");
        }
        std::fs::remove_dir_all(&dir).ok();
    }

    /// 不支持的格式组合要明确拒绝。
    #[test]
    fn unsupported_formats_are_rejected() {        let dir = tmpdir("unsup");
        let wav = make_wav(&dir, "src.wav", 22_050, 1);
        let out = dir.join("out.flac");
        let e = convert(&wav, out.to_str().unwrap(), "flac", &AudioOptions::default()).unwrap_err();
        assert!(e.contains("unsupported"), "got {e}");
        std::fs::remove_dir_all(&dir).ok();
    }

    /// 6.3 新增格式的**完整矩阵**：六种我们既能编又能解的格式两两互转。
    ///
    /// 为什么是这六个：FLAC 和 M4A 只能当源（本仓没有 FLAC 编码器，也没有 MP4
    /// 封装器），所以它们进不了「自产自销」的矩阵 —— 它们由
    /// `tests/ffprobe_oracle.rs` 拿 ffmpeg 造的样本单独验。
    ///
    /// 36 个组合全跑一遍，每个都必须能被解回来。单独测编码器证明不了编排是对的：
    /// 分派表里少一个分支、`estimate_output_bytes` 漏一个格式、`enforce_target_channels`
    /// 多管一个目标，都只有在这里才现形。
    #[test]
    fn full_conversion_matrix_v63() {
        let dir = tmpdir("matrix63");
        let fmts = ["wav", "mp3", "ogg", "aiff", "au", "aac"];

        // 先造六个源（都从同一个 WAV 出发）。
        let seed = make_wav(&dir, "seed.wav", 44_100, 2);
        let mut sources: Vec<(&str, String)> = Vec::new();
        for f in fmts {
            let p = dir.join(format!("src.{f}"));
            convert(&seed, p.to_str().unwrap(), f, &AudioOptions::default())
                .unwrap_or_else(|e| panic!("造源 {f}: {e}"));
            sources.push((f, p.to_str().unwrap().to_string()));
        }

        for (sname, sfile) in &sources {
            for target in fmts {
                let out = dir.join(format!("{sname}2{target}.{target}"));
                convert(sfile, out.to_str().unwrap(), target, &AudioOptions::default())
                    .unwrap_or_else(|e| panic!("{sname} → {target}: {e}"));
                let (rate, ch, frames) = probe(out.to_str().unwrap())
                    .unwrap_or_else(|e| panic!("{sname} → {target} probe: {e}"));
                assert_eq!(rate, 44_100, "{sname} → {target}: 采样率");
                assert_eq!(ch, 2, "{sname} → {target}: 声道数");
                assert!(frames > 0, "{sname} → {target}: 一个样本都没有");
            }
        }
        std::fs::remove_dir_all(&dir).ok();
    }

    /// 无损容器的往返必须**精确**：WAV / AIFF / AU 之间互转不该丢一个样本、
    /// 不该改一个字节的幅度。
    ///
    /// 这条和上面那条不重复：上面只查「能解回来、参数对」，这条查**内容**。
    /// AIFF/AU 是大端，字节序写反了照样能通过上面那条（自己写自己读自洽），
    /// 但会在这里的逐样本比较上炸掉。
    #[test]
    fn lossless_containers_round_trip_sample_exactly() {
        let dir = tmpdir("lossless63");
        let seed = make_wav(&dir, "seed.wav", 44_100, 2);
        let reference = crate::decode::decode(&seed).unwrap();
        for target in ["aiff", "au"] {
            let out = dir.join(format!("out.{target}"));
            convert(&seed, out.to_str().unwrap(), target, &AudioOptions::default())
                .unwrap_or_else(|e| panic!("wav → {target}: {e}"));
            let back = crate::decode::decode(out.to_str().unwrap()).unwrap();
            assert_eq!(back.rate, reference.rate, "{target}: 采样率");
            assert_eq!(back.channels, reference.channels, "{target}: 声道数");
            assert_eq!(back.frames(), reference.frames(), "{target}: 帧数");
            let worst = reference
                .samples
                .iter()
                .zip(back.samples.iter())
                .fold(0.0f32, |m, (a, b)| m.max((a - b).abs()));
            assert!(worst <= 1.0 / 32767.0, "{target}: 逐样本最大误差 {worst}");
        }
        std::fs::remove_dir_all(&dir).ok();
    }

    /// AAC 的采样率限制是**提前挡住**的，不是让上游 panic。
    ///
    /// `write_adts_header` 里有一句 `expect("standard rate for ADTS")` —— 非标准
    /// 采样率进去就是 panic（会被 JNI 的护栏变成 Err，但那句消息没法看）。
    /// 这条钉住我们自己的那句话。
    #[test]
    fn aac_rejects_a_non_standard_sample_rate_with_a_readable_message() {
        let dir = tmpdir("aacrate");
        let src = make_wav(&dir, "src.wav", 22_000, 2); // 22 kHz 不是 AAC 标准速率
        let out = dir.join("out.aac");
        let e = convert(&src, out.to_str().unwrap(), "aac", &AudioOptions::default()).unwrap_err();
        assert!(e.contains("standard sample rate"), "got {e}");
        assert!(!out.exists(), "失败时必须删掉半成品");

        // 同一个源转成 22050（标准速率）就没事。
        let ok = dir.join("ok.aac");
        convert(
            &src,
            ok.to_str().unwrap(),
            "aac",
            &AudioOptions::parse("rate=22050"),
        )
        .expect("22050 是 AAC 标准速率，不该被拒");
        assert_eq!(probe(ok.to_str().unwrap()).unwrap().0, 22_050);
        std::fs::remove_dir_all(&dir).ok();
    }

    /// 只能当源的格式（FLAC / M4A）：有真样本时走一遍 → WAV。
    /// 没设 `UU_AUDIO_SAMPLES` 就 SKIP。
    #[test]
    fn decode_only_sources_convert_to_wav_when_available() {
        let Some(dir) = std::env::var_os("UU_AUDIO_SAMPLES") else {
            eprintln!("SKIP decode_only_sources_convert_to_wav_when_available: UU_AUDIO_SAMPLES 未设置");
            return;
        };
        let dir = std::path::PathBuf::from(dir);
        let out = tmpdir("decodeonly");
        for (ext, name) in [("flac", "sine.flac"), ("m4a", "sine.m4a"), ("aac", "sine.aac")] {
            let src = dir.join(name);
            if !src.exists() {
                eprintln!("SKIP {ext}: {} 不存在", src.display());
                continue;
            }
            let dst = out.join(format!("{ext}.wav"));
            convert(
                src.to_str().unwrap(),
                dst.to_str().unwrap(),
                "wav",
                &AudioOptions::default(),
            )
            .unwrap_or_else(|e| panic!("{ext} → wav: {e}"));
            let (rate, ch, frames) = probe(dst.to_str().unwrap()).unwrap();
            assert_eq!(rate, 44_100, "{ext}");
            assert_eq!(ch, 2, "{ext}");
            assert!(frames > 20_000, "{ext}: 只解出 {frames} 帧");
        }
        std::fs::remove_dir_all(&out).ok();
    }

    /// **UI 能摆出来的每一档 AAC 采样率都必须真的编得出来。**
    ///
    /// UI 的档位表（`AudioConvertOptions.kt` 的 `OPT_AAC_RATES`）= AAC 的标准速率
    /// ∩ 引擎的采样率白名单（`AudioOptions::sanitize`）。这条把那个交集在 Rust 侧
    /// 复算一遍，**逐档真编一个文件**。
    ///
    /// 为什么值得一条独立的测试：两边各自手抄清单必然会漂。UI 摆出一档引擎会
    /// **静默吸附**掉的采样率（12000 → 11025、64000 → 48000）是最坏的情况 ——
    /// 用户以为他选了 64 kHz，文件里是 48 kHz，而且没有任何提示。这里只要有一档
    /// 编不出来，或者编出来的采样率不是用户选的那个，就会红。
    #[test]
    fn every_ui_selectable_aac_rate_really_encodes() {
        use crate::encode::AAC_STANDARD_RATES;
        let dir = tmpdir("aacrates");
        let src = make_wav(&dir, "src.wav", 44_100, 2);
        let mut checked = 0usize;
        for &rate in AAC_STANDARD_RATES {
            let opts = AudioOptions::parse(&format!("rate={rate}"));
            // 白名单外的速率会被 sanitize 吸附 —— 那种档位 UI 不会摆出来，跳过。
            // （UI 用的是交集，不是这份清单本身。）
            if opts.sample_rate != rate {
                continue;
            }
            let out = dir.join(format!("r{rate}.aac"));
            convert(&src, out.to_str().unwrap(), "aac", &opts)
                .unwrap_or_else(|e| panic!("{rate} Hz 是 UI 会摆出来的档位，但编不出来: {e}"));
            assert_eq!(
                probe(out.to_str().unwrap()).unwrap().0,
                rate,
                "{rate} Hz：编出来的采样率被改掉了"
            );
            checked += 1;
        }
        assert!(checked >= 8, "只验到 {checked} 档，UI 的档位表是不是空了？");
        std::fs::remove_dir_all(&dir).ok();
    }
}
