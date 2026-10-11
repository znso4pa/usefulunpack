//! **第三方编码器编出来的文件**必须能被我们解开。
//!
//! 为什么单独一支：我们自己的单测里所有素材都是自己编的，于是「自己编的能解回来」
//! 只证明了自洽。真机第一次试转就炸了 —— 源是 ffmpeg 编的 MP3、目标 OGG 加单声道，
//! `rusty_vorbis` 报 `InvalidData("vorbis setup: bad coupling channels")`。
//! 那是**两个**盲区叠在一起：源不是我们自己编的，且所有单测都只用了立体声。
//!
//! 所以这里用 ffmpeg 现编素材（LAME 的 MP3、原生 vorbis 的 OGG、24 位 / 8 kHz 的
//! 单声道 WAV），再把每一份喂进引擎。找不到 ffmpeg 时**整支跳过**，不 fail：
//! 本仓的构建不该依赖 ffmpeg 存在。

use std::path::{Path, PathBuf};
use std::process::Command;

fn have_ffmpeg() -> bool {
    Command::new("ffmpeg")
        .arg("-version")
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

fn ffmpeg(dir: &Path, name: &str, args: &[&str]) -> PathBuf {
    let out = dir.join(name);
    let status = Command::new("ffmpeg")
        .args(["-hide_banner", "-loglevel", "error", "-y"])
        .args(args)
        .arg(&out)
        .status()
        .expect("spawn ffmpeg");
    assert!(status.success(), "ffmpeg 没能生成 {name}");
    out
}

fn tmpdir(tag: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("uu_audio_foreign_{tag}_{}", std::process::id()));
    std::fs::create_dir_all(&d).unwrap();
    d
}

/// 用 ffmpeg 编一组形态各异的素材：有损、无损、24 位、8 kHz、单声道、立体声。
fn make_sources(dir: &Path) -> Vec<(String, PathBuf)> {
    let tone = ["-f", "lavfi", "-i", "sine=frequency=440:duration=1:sample_rate=44100"];
    vec![
        // LAME 的 MP3：开头带 ID3v2 标签，和我们自己编的裸帧流不一样。
        (
            "lame".to_string(),
            ffmpeg(dir, "lame.mp3", &[&tone[..], &["-ac", "2", "-c:a", "libmp3lame", "-b:a", "128k"]].concat()),
        ),
        // ffmpeg 原生 vorbis 编码器（实验性，要 -strict -2）。
        (
            "vorbis".to_string(),
            ffmpeg(dir, "native.ogg", &[&tone[..], &["-ac", "2", "-c:a", "vorbis", "-strict", "-2"]].concat()),
        ),
        // 24 位立体声 WAV：位深缩放分支只有真素材才走得到。
        (
            "pcm24".to_string(),
            ffmpeg(dir, "pcm24.wav", &[&tone[..], &["-ac", "2", "-c:a", "pcm_s24le"]].concat()),
        ),
        // 8 kHz 单声道 WAV：MPEG-2.5 档，单声道的代表。
        (
            "mono8k".to_string(),
            ffmpeg(dir, "mono8k.wav", &[
                "-f", "lavfi", "-i", "sine=frequency=880:duration=1:sample_rate=8000",
                "-ac", "1", "-c:a", "pcm_s16le",
            ]),
        ),
        // 44.1 kHz 单声道 WAV：同样是单声道，但不带低采样率这个干扰项。
        (
            "mono44k".to_string(),
            ffmpeg(dir, "mono44k.wav", &[&tone[..], &["-ac", "1", "-c:a", "pcm_s16le"]].concat()),
        ),
    ]
}

/// 每份第三方素材都要能解出来，且采样率 / 声道数与它自己声明的一致。
#[test]
fn foreign_files_decode_with_the_expected_shape() {
    if !have_ffmpeg() {
        println!("SKIP foreign_files_decode_with_the_expected_shape: no ffmpeg on PATH");
        return;
    }
    let dir = tmpdir("decode");
    let expected: &[(&str, u32, u16)] = &[
        ("lame", 44_100, 2),
        ("vorbis", 44_100, 2),
        ("pcm24", 44_100, 2),
        ("mono8k", 8_000, 1),
        ("mono44k", 44_100, 1),
    ];
    let sources = make_sources(&dir);
    for (tag, path) in &sources {
        let (_, rate, ch) = expected.iter().find(|(t, _, _)| t == tag).expect("未登记的素材");
        let (got_rate, got_ch, frames) = archive_audio_core::probe(path.to_str().unwrap())
            .unwrap_or_else(|e| panic!("{tag}: 解不开第三方素材: {e}"));
        assert_eq!(got_rate, *rate, "{tag}: 采样率");
        assert_eq!(got_ch, *ch, "{tag}: 声道数");
        assert!(frames > 0, "{tag}: 没有帧");
    }
    std::fs::remove_dir_all(&dir).ok();
}

/// 全部目标格式 × 全部第三方素材：能转的必须转出来，不能转的必须给一句能看懂的话。
#[test]
fn every_foreign_source_converts_to_every_target() {
    if !have_ffmpeg() {
        println!("SKIP every_foreign_source_converts_to_every_target: no ffmpeg on PATH");
        return;
    }
    let dir = tmpdir("convert");
    let out = dir.join("out");
    std::fs::create_dir_all(&out).unwrap();
    let mut failures: Vec<String> = Vec::new();

    for (tag, src) in make_sources(&dir) {
        for target in ["wav", "mp3", "ogg"] {
            let dst = out.join(format!("{tag}.{target}"));
            match archive_audio_core::convert_host(
                src.to_str().unwrap(),
                dst.to_str().unwrap(),
                target,
                "",
            ) {
                Ok(()) => {
                    let (_, ch, frames) = archive_audio_core::probe(dst.to_str().unwrap())
                        .unwrap_or_else(|e| panic!("{tag}→{target}: 产出解不开: {e}"));
                    assert!(frames > 0, "{tag}→{target}: 产出没有帧");
                    // OGG 恒为立体声（内嵌 setup 只有立体声），其余保持源的声道数。
                    let want_ch = if target == "ogg" { 2 } else { if tag.starts_with("mono") { 1 } else { 2 } };
                    assert_eq!(ch, want_ch, "{tag}→{target}: 声道数");
                }
                Err(e) => failures.push(format!("{tag}→{target}: {e}")),
            }
        }
    }
    assert!(failures.is_empty(), "有组合转不出来：\n{}", failures.join("\n"));
    std::fs::remove_dir_all(&dir).ok();
}

/// 单声道源 → OGG 必须**升混**而不是被拒；明确要单声道 → OGG 必须**报错**而不是
/// 被默默改成双声道。这条正是真机上炸出来的那个组合。
#[test]
fn mono_ogg_policy_holds_on_foreign_sources() {
    if !have_ffmpeg() {
        println!("SKIP mono_ogg_policy_holds_on_foreign_sources: no ffmpeg on PATH");
        return;
    }
    let dir = tmpdir("monoogg");
    // 现编一份单声道素材 —— 复用 `make_sources` 会顺带生成另外四份用不上的。
    let src = ffmpeg(&dir, "mono44k.wav", &[
        "-f", "lavfi", "-i", "sine=frequency=440:duration=1:sample_rate=44100",
        "-ac", "1", "-c:a", "pcm_s16le",
    ]);

    // 保持源（源是单声道）→ 升混，成功。
    let a = dir.join("keep.ogg");
    archive_audio_core::convert_host(src.to_str().unwrap(), a.to_str().unwrap(), "ogg", "")
        .expect("单声道源转 OGG 必须成功");
    assert_eq!(archive_audio_core::probe(a.to_str().unwrap()).unwrap().1, 2);

    // 明确要单声道 → 报错，消息里要能看出是声道数问题。
    let b = dir.join("mono.ogg");
    let e = archive_audio_core::convert_host(
        src.to_str().unwrap(), b.to_str().unwrap(), "ogg", "ch=1",
    )
    .unwrap_err();
    assert!(e.contains("2 channels"), "错误消息要能看懂，got {e}");
    assert!(!b.exists(), "失败时必须删掉半成品");

    std::fs::remove_dir_all(&dir).ok();
}
