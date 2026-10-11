//! [`Pcm`] → 输出格式。
//!
//! 几条编码路径的「流式程度」差别很大，进度条必须知道这一点：
//! - **WAV / AIFF / AU**：真的流式，边写边数。
//! - **MP3**：`rusty_mp3` 是流式的（push 一帧、pull 一帧），所以也是边编边数。
//! - **OGG**：`rusty_vorbis` **不流式** —— `push_pcm_f32` 只是把样本拷进内部 Vec，
//!   真正的编码发生在 `finish()` 之后的第一次 `next_packet()`，而且是并行整段编。
//!   也就是说 OGG 的输出在内存里是一次性生成的，中间没有可观测的进度点。
//! - **AAC**：`rusty_aac` 同样是全量缓冲（`finish()` 才填队列），进度条一样会
//!   停在原地。
//!
//! 后两条是上游的设计（编码器全量缓冲），不是这里的取舍；那两个函数因此只在
//! 「已写完多少字节」上报进度，编码那一段进度条会停在原地 —— 比伪造进度诚实。

use std::io::Write;

use crate::counted::CountedWriter;
use crate::options::AudioOptions;
use crate::pcm::Pcm;
use archive_common::extract_progress;

/// 推给编码器的样本块（样本数，不是字节）。32 KiB 个 f32 = 128 KiB，够大
/// 不至于让每帧一次的函数调用成为开销，够小让取消能被及时看到。
const PUSH_SAMPLES: usize = 32 * 1024;

/// 按目标格式编码。
pub fn encode(pcm: &Pcm, path: &str, target: &str, opts: &AudioOptions) -> Result<(), String> {
    match target {
        "mp3" => encode_mp3(pcm, path, opts),
        "ogg" => encode_ogg(pcm, path, opts),
        "wav" => encode_wav(pcm, path, opts),
        "aiff" | "aif" => crate::aiff::encode(pcm, path, opts.bit_depth),
        "au" => crate::au::encode(pcm, path, opts.bit_depth),
        "aac" => encode_aac(pcm, path, opts),
        other => Err(format!("unsupported audio output {other}")),
    }
}

/// 目标格式的默认码率（kbps）。`AudioOptions::bitrate_kbps == 0` 时用它。
fn default_bitrate_kbps(target: &str, channels: u16) -> u32 {
    match (target, channels) {
        ("mp3", 1) => 96,
        ("mp3", _) => 192,
        ("ogg", 1) => 96,
        ("ogg", _) => 160,
        // AAC 比 MP3 效率高，同样的听感要的码率更低。
        ("aac", 1) => 96,
        ("aac", _) => 160,
        _ => 0,
    }
}

/// libvorbis 的 `-q` ↔ 名义码率对照表（官方刻度）。
///
/// 这张表**只用来把用户选的码率翻译成 q**，翻译成 q 之后就当它不存在了 ——
/// 估算分母用的是另一张实测表 [`VORBIS_MEASURED`]，原因见那里。
/// 唯一的真源：用户看到「128 kbps」时选中 q4，与 `oggenc -q 4` 完全一致。
const VORBIS_LADDER: &[(u32, f64)] = &[
    (45, -1.0),
    (64, 0.0),
    (80, 1.0),
    (96, 2.0),
    (112, 3.0),
    (128, 4.0),
    (160, 5.0),
    (192, 6.0),
    (224, 7.0),
    (256, 8.0),
    (320, 9.0),
    (500, 10.0),
];

/// 用户选的「码率」→ Vorbis `-q` 刻度。
///
/// `rusty_vorbis` 只暴露 quality（0..1 归一化）这一个旋钮，没有「目标码率」，
/// 而且它自己文档就说 `bitrate_bps` 是 informational。所以「OGG 128 kbps」在
/// 这里的含义是 **q4** —— 和 `oggenc -q 4` 完全一致，而不是我们编出来的另一套
/// 刻度。Vorbis 本来就是 VBR，实际码率随素材走。
fn vorbis_q_for_kbps(kbps: u32) -> f64 {
    VORBIS_LADDER
        .iter()
        .min_by_key(|&&(k, _)| k.abs_diff(kbps))
        .map(|&(_, q)| q)
        .unwrap_or(4.0)
}

/// `rusty_vorbis` **实测**产出的码率（kbps）：`(q, kbps)`，q 升序。
///
/// 为什么不复用 `VORBIS_LADDER` 的名义码率当分母：那张表是 libvorbis 的承诺，
/// 而 `rusty_vorbis` 0.1.1 里 `VorbisEncoderConfig::bitrate_bps` 的文档原文是
/// *"Informational for now"*，并且它只内嵌了 stereo/44.1 kHz/q4 一套 setup
/// （源码里的 `SETUP_Q4_STEREO`，注释 *"Only the stereo/44.1 kHz/q4 profile is
/// embedded so far"*）。也就是说用户选的「128 kbps」根本没进编码器，进去的只有
/// quality。实测（44.1 kHz 立体声宽带白噪 4 s）：
///   q0→21.7  q2→31.0  q4→56.7  q5→68.2  q6→81.2  q8→118.5 kbps
/// 只有名义值的 0.32~0.46 倍。分母必须按实测来，否则进度条全程贴地。
///
/// 只有这 6 个点是量出来的，中间靠线性插值；q > 8 没有实测点，按 q8 收口 ——
/// setup 头固定是 q4 那一套，码率对 q 的响应本来就是次线性的，再往上加 q 也不
/// 会显著变大。估歪的后果仅是进度条到 100% 的早晚，不影响产出文件。
const VORBIS_MEASURED: &[(f64, u32)] = &[
    (0.0, 22),
    (2.0, 31),
    (4.0, 57),
    (5.0, 68),
    (6.0, 81),
    (8.0, 119),
];

/// 在 [`VORBIS_MEASURED`] 上线性插值；左端按最近一段的斜率外推，右端收口。
fn measured_kbps_for_q(q: f64) -> u32 {
    let first = VORBIS_MEASURED[0];
    let last = VORBIS_MEASURED[VORBIS_MEASURED.len() - 1];
    if q <= first.0 {
        // q < 0（br ≤ 64）没有实测点，用 q0→q2 的斜率外推；再低也不低于 1 kbps。
        let slope =
            (VORBIS_MEASURED[1].1 as f64 - first.1 as f64) / (VORBIS_MEASURED[1].0 - first.0);
        return (first.1 as f64 + slope * (q - first.0)).max(1.0).round() as u32;
    }
    if q >= last.0 {
        return last.1;
    }
    for w in VORBIS_MEASURED.windows(2) {
        let ((q0, k0), (q1, k1)) = (w[0], w[1]);
        if q >= q0 && q <= q1 {
            let t = (q - q0) / (q1 - q0);
            return (k0 as f64 + t * (k1 as f64 - k0 as f64)).round() as u32;
        }
    }
    last.1
}

/// 估算输出字节数 —— 进度条总进度的第二半。
///
/// 这是**估算**，而且只用于分母：CBR 的 MP3 能算得很准（帧数 × 每帧字节），
/// VBR / Vorbis 只能按目标平均码率折。估歪了的表现是进度条早一点或晚一点到
/// 100%，不会影响产出文件。
pub fn estimate_output_bytes(pcm: &Pcm, target: &str, opts: &AudioOptions) -> u64 {
    let frames = pcm.frames() as u64;
    match target {
        "wav" => {
            // 44 字节标准头 + 数据。位深按选项算（32 位是浮点，仍是 4 字节）。
            44 + frames * pcm.channels as u64 * opts.wav_bytes_per_sample() as u64
        }
        "mp3" => {
            // CBR：码率是硬的，估得准。
            let kbps = if opts.bitrate_kbps > 0 {
                opts.bitrate_kbps
            } else {
                default_bitrate_kbps("mp3", pcm.channels)
            } as u64;
            bytes_at_bitrate(pcm, kbps)
        }
        "ogg" => {
            // 分母不能用用户选的码率 —— 它在 `rusty_vorbis` 里只是个查 q 用的输入，
            // 编出来的量级由实测表决定。
            let wanted = if opts.bitrate_kbps > 0 {
                opts.bitrate_kbps
            } else {
                default_bitrate_kbps("ogg", pcm.channels)
            };
            bytes_at_bitrate(pcm, measured_kbps_for_q(vorbis_q_for_kbps(wanted)) as u64)
        }
        // AIFF / AU 和 WAV 一样是无损容器：估算是**精确值**。
        "aiff" | "aif" => crate::aiff::estimated_bytes(pcm, opts.bit_depth),
        "au" => crate::au::estimated_bytes(pcm, opts.bit_depth),
        // AAC 是 CBR 的（每帧固定填到目标码率），所以按码率算得准。
        "aac" => {
            let kbps = if opts.bitrate_kbps > 0 {
                opts.bitrate_kbps
            } else {
                default_bitrate_kbps("aac", pcm.channels)
            } as u64;
            bytes_at_bitrate(pcm, kbps)
        }
        _ => 0,
    }
}

/// 秒 × 码率 / 8，再加 4 KiB 的头/尾余量。
fn bytes_at_bitrate(pcm: &Pcm, kbps: u64) -> u64 {
    (pcm.duration_secs() * kbps as f64 * 1000.0 / 8.0) as u64 + 4096
}

// ---------------------------------------------------------------- WAV

/// WAV：`hound` 写容器，我们按位深把 f32 定标回去。
pub fn encode_wav(pcm: &Pcm, path: &str, opts: &AudioOptions) -> Result<(), String> {
    let bits = opts.bit_depth;
    let spec = hound::WavSpec {
        channels: pcm.channels,
        sample_rate: pcm.rate,
        bits_per_sample: bits,
        sample_format: if bits == 32 {
            // 32 位一律浮点：32 位整数 PCM 在实际素材里几乎不存在，而浮点 WAV
            // 是 32 位 WAV 的事实标准。
            hound::SampleFormat::Float
        } else {
            hound::SampleFormat::Int
        },
    };
    let file = std::fs::File::create(path).map_err(|e| format!("create {path}: {e}"))?;
    let out = CountedWriter::new(std::io::BufWriter::with_capacity(256 * 1024, file));
    let mut w = hound::WavWriter::new(out, spec).map_err(|e| format!("wav writer: {e}"))?;

    for &s in &pcm.samples {
        if extract_progress::cancelled() {
            return Err("cancelled".to_string());
        }
        let r = match bits {
            8 => w.write_sample(to_i8(s)),
            16 => w.write_sample(to_i16(s)),
            24 => w.write_sample(to_i24(s)),
            _ => w.write_sample(clamp(s)),
        };
        r.map_err(|e| format!("wav write: {e}"))?;
    }
    w.finalize().map_err(|e| format!("wav finalize: {e}"))?;
    Ok(())
}

/// 夹到 `[-1, 1]`。解码链路上的 f32 可能略微越界（浮点 WAV 里合法，整数 WAV
/// 里非法），整数输出前必须夹，否则 `as i16` 会回绕成反相信号 —— 满幅削波听
/// 起来只是破音，回绕听起来是「啪」的一声爆音。
///
/// `pub(crate)`：`au.rs` 的写入器也要用它（那里的整数转换和这里必须**同一套**
/// 规则，否则同一个源转成 WAV 和转成 AU 会在满幅处得到不同的结果）。
pub(crate) fn clamp(s: f32) -> f32 {
    if s.is_nan() {
        0.0
    } else {
        s.clamp(-1.0, 1.0)
    }
}

fn to_i8(s: f32) -> i8 {
    (clamp(s) * 127.0).round() as i8
}

fn to_i16(s: f32) -> i16 {
    (clamp(s) * 32767.0).round() as i16
}

fn to_i24(s: f32) -> i32 {
    (clamp(s) * 8_388_607.0).round() as i32
}

// ---------------------------------------------------------------- MP3

/// MP3：`rusty_mp3` 的流式编码器。
fn encode_mp3(pcm: &Pcm, path: &str, opts: &AudioOptions) -> Result<(), String> {
    let kbps = if opts.bitrate_kbps > 0 {
        opts.bitrate_kbps
    } else {
        default_bitrate_kbps("mp3", pcm.channels)
    };
    let cfg = rusty_mp3::Mp3EncoderConfig {
        bitrate_kbps: kbps,
        vbr_quality: if opts.vbr {
            Some(rusty_mp3::vbr_quality_index(opts.vbr_quality as f32))
        } else {
            None
        },
    };
    let mut enc = rusty_mp3::Mp3Encoder::new(cfg);

    let file = std::fs::File::create(path).map_err(|e| format!("create {path}: {e}"))?;
    let mut out = CountedWriter::new(std::io::BufWriter::with_capacity(256 * 1024, file));

    let ch = pcm.channels.max(1) as usize;
    // 块边界必须落在整帧上：`push_pcm_f32` 按 `len()/channels` 取帧数，切在半个
    // 帧上会把那个残样本悄悄丢掉。
    let step = (PUSH_SAMPLES / ch).max(1) * ch;
    let mut i = 0usize;
    while i < pcm.samples.len() {
        if extract_progress::cancelled() {
            return Err("cancelled".to_string());
        }
        let end = (i + step).min(pcm.samples.len());
        enc.push_pcm_f32(&pcm.samples[i..end], pcm.channels, pcm.rate)
            .map_err(|e| format!("mp3 encode: {e}"))?;
        i = end;
        while let Ok(pkt) = enc.next_packet() {
            out.write_all(&pkt).map_err(|e| format!("mp3 write: {e}"))?;
        }
    }
    enc.finish();
    while let Ok(pkt) = enc.next_packet() {
        out.write_all(&pkt).map_err(|e| format!("mp3 write: {e}"))?;
    }
    out.flush().map_err(|e| format!("mp3 flush: {e}"))?;
    Ok(())
}

// ---------------------------------------------------------------- OGG

/// Ogg Vorbis：`rusty_vorbis` 出包，`ogg` 分页。
///
/// 分页照 Vorbis I 规范摆：identification 独占第一页，comment + setup 同占
/// 第二页，音频从第三页开始；最后一个音频包用 `EndStream` 收尾。把三个头
/// 各写一页也能被宽容的解码器接受，但那是「碰巧能播」，不是规范。
fn encode_ogg(pcm: &Pcm, path: &str, opts: &AudioOptions) -> Result<(), String> {
    // 叶子守卫：`lib.rs` 的 `enforce_target_channels` 已经把单声道挡在外面了
    // （明确要单声道 → 报错，只是保持源 → 升混）。这里再挡一次是为了直接调用
    // 本函数的路径 —— 让它们拿到一句能看懂的话，而不是上游那句
    // `InvalidData("vorbis setup: bad coupling channels")`。
    if pcm.channels != 2 {
        return Err(format!(
            "OGG output requires 2 channels (got {}): the bundled encoder only ships a stereo setup",
            pcm.channels
        ));
    }
    let kbps = if opts.bitrate_kbps > 0 {
        opts.bitrate_kbps
    } else {
        default_bitrate_kbps("ogg", pcm.channels)
    };

    let mut enc = rusty_vorbis::VorbisEncoder::new(rusty_vorbis::VorbisEncoderConfig {
        bitrate_bps: (kbps as i32) * 1000,
        quality: rusty_vorbis::quality01_from_vorbis_q(vorbis_q_for_kbps(kbps)),
    });
    // 编码器内部本来就是全量缓冲，所以这里一次性推入；分块只为了留取消检查点。
    let ch = pcm.channels.max(1) as usize;
    let step = (PUSH_SAMPLES / ch).max(1) * ch;
    let mut i = 0usize;
    while i < pcm.samples.len() {
        if extract_progress::cancelled() {
            return Err("cancelled".to_string());
        }
        let end = (i + step).min(pcm.samples.len());
        enc.push_pcm_f32(&pcm.samples[i..end], pcm.channels, pcm.rate)
            .map_err(|e| format!("vorbis encode: {e:?}"))?;
        i = end;
    }
    enc.finish();

    // 先把所有包抽干：最后一包要用 EndStream，而「是不是最后一包」只有抽完才知道。
    let mut packets = Vec::new();
    loop {
        match enc.next_packet() {
            Ok(p) => packets.push(p),
            Err(rusty_vorbis::Error::Eof) => break,
            // 非 Eof 的 Err 在 flush 之后不该出现；`Again` 也归到这里，
            // 因为我们已经 `finish()` 过了，再要输入就是编码器状态不对。
            Err(e) => return Err(format!("vorbis encode: {e:?}")),
        }
    }
    if packets.len() < 3 {
        return Err(format!("vorbis produced {} header packets, expected 3", packets.len()));
    }

    let file = std::fs::File::create(path).map_err(|e| format!("create {path}: {e}"))?;
    let out = CountedWriter::new(std::io::BufWriter::with_capacity(256 * 1024, file));
    let mut w = ogg::PacketWriter::new(out);
    // 页序列号只要在一条物理流里唯一即可；用纳秒低位，避免与源文件的序列号
    // 撞上（同一页里两个逻辑流同号是解码器的灾难）。
    let serial = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.subsec_nanos() | 1)
        .unwrap_or(0x5555_0001);

    use ogg::writing::PacketWriteEndInfo;
    let last = packets.len() - 1;
    for (idx, p) in packets.into_iter().enumerate() {
        let end = match idx {
            // ident 自己一页。
            0 => PacketWriteEndInfo::EndPage,
            // comment + setup 同页。
            2 => PacketWriteEndInfo::EndPage,
            _ if idx == last => PacketWriteEndInfo::EndStream,
            _ => PacketWriteEndInfo::NormalPacket,
        };
        let gp = if p.pts < 0 { 0 } else { p.pts as u64 };
        w.write_packet(p.data, serial, end, gp)
            .map_err(|e| format!("ogg write: {e}"))?;
    }
    // `PacketWriter::into_inner` 是唯一能确保最后半页落盘的方式。
    w.into_inner()
        .into_inner()
        .flush()
        .map_err(|e| format!("ogg flush: {e}"))?;
    Ok(())
}

// ---------------------------------------------------------------- AAC

/// AAC 编码器认识的 13 个标准采样率（`sf_index` 0..12）。
///
/// 不是「我们的限制」，是 AAC 格式本身的：`samplingFrequencyIndex` 只有 4 位，
/// 13 个值 + 一个「显式 24 位速率」的转义码。而 ADTS 头里**没有**转义码的
/// 位置，所以 ADTS 只能承载这 13 个速率。
///
/// `pub(crate)`：`lib.rs` 的 `every_ui_selectable_aac_rate_really_encodes` 要
/// 拿它去复算 UI 的档位表（UI 那张表是这份清单与采样率白名单的交集）。
pub(crate) const AAC_STANDARD_RATES: &[u32] = &[
    96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000,
    7_350,
];

/// AAC（裸 ADTS 基本流，`.aac`）。
///
/// 出的是**裸 ADTS**，不是 `.m4a` 容器 —— 写 MP4 要把 `moov/stbl/stsz/stco`
/// 整棵样本表建出来，那是另一个模块的活。ADTS 是标准的基本流，任何播放器和
/// ffmpeg 都直接吃。
///
/// 两个必须**提前**挡住的输入，否则会在上游内部炸成一句看不懂的话：
/// - 采样率不在 [`AAC_STANDARD_RATES`] 里：`write_adts_header` 直接 panic
///   （`expect("standard rate for ADTS")`）。
/// - 声道数超过 6：上游只实现了 1..6 声道的元素序列，7 声道要 PCE。
fn encode_aac(pcm: &Pcm, path: &str, opts: &AudioOptions) -> Result<(), String> {
    if !AAC_STANDARD_RATES.contains(&pcm.rate) {
        return Err(format!(
            "AAC output needs a standard sample rate ({} Hz), got {} Hz — set an output rate",
            AAC_STANDARD_RATES
                .iter()
                .map(|r| r.to_string())
                .collect::<Vec<_>>()
                .join("/"),
            pcm.rate
        ));
    }
    let ch = pcm.channels.max(1);
    if ch > 6 {
        return Err(format!(
            "AAC output supports at most 6 channels, got {ch} — downmix first"
        ));
    }
    let kbps = if opts.bitrate_kbps > 0 {
        opts.bitrate_kbps
    } else {
        default_bitrate_kbps("aac", pcm.channels)
    };

    let mut enc = rusty_aac::encode::AacEncoder::new(rusty_aac::encode::AacEncoderConfig {
        bitrate_bps: kbps.saturating_mul(1000),
        ..Default::default()
    });

    // 编码器内部本来就是全量缓冲，这里分块只为了留取消检查点。
    let step = (PUSH_SAMPLES / ch as usize).max(1) * ch as usize;
    let mut i = 0usize;
    while i < pcm.samples.len() {
        if extract_progress::cancelled() {
            return Err("cancelled".to_string());
        }
        let end = (i + step).min(pcm.samples.len());
        enc.push_pcm(&pcm.samples[i..end], ch, pcm.rate)
            .map_err(|e| format!("aac encode: {e}"))?;
        i = end;
    }
    enc.finish();

    let file = std::fs::File::create(path).map_err(|e| format!("create {path}: {e}"))?;
    let mut out = CountedWriter::new(std::io::BufWriter::with_capacity(256 * 1024, file));
    loop {
        if extract_progress::cancelled() {
            return Err("cancelled".to_string());
        }
        match enc.next_packet() {
            Ok(p) => {
                // 编码器给的是裸 `raw_data_block`，ADTS 头得我们自己加。
                let hdr = rusty_aac::AdtsHeader {
                    object_type: 2, // AAC-LC
                    sample_rate: pcm.rate,
                    channels: ch,
                    frame_length: p.data.len() + 7,
                    header_len: 7,
                };
                out.write_all(&rusty_aac::encode::write_adts_header(&hdr))
                    .map_err(|e| format!("aac write: {e}"))?;
                out.write_all(&p.data).map_err(|e| format!("aac write: {e}"))?;
            }
            Err(rusty_aac::Error::Eof) => break,
            Err(e) => return Err(format!("aac encode: {e}")),
        }
    }
    out.flush().map_err(|e| format!("aac flush: {e}"))?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::decode::decode;
    use crate::testutil::{noise, sine, tmpdir};

    /// 夹波必须夹住，不能回绕。`0.9 as i16` 只是削顶，`1.4 as i16` 会变成负数。
    #[test]
    fn clamping_never_wraps() {
        assert_eq!(to_i16(1.4), 32767);
        assert_eq!(to_i16(-1.4), -32767);
        assert_eq!(to_i16(0.0), 0);
        assert_eq!(to_i8(2.0), 127);
        assert_eq!(to_i24(3.0), 8_388_607);
        assert_eq!(to_i16(f32::NAN), 0);
    }

    /// Vorbis 的 q 刻度按 libvorbis 的官方码率表吸附。
    #[test]
    fn vorbis_q_ladder_matches_libvorbis_nominal_rates() {
        assert_eq!(vorbis_q_for_kbps(128), 4.0);
        assert_eq!(vorbis_q_for_kbps(192), 6.0);
        assert_eq!(vorbis_q_for_kbps(1000), 10.0);
        assert_eq!(vorbis_q_for_kbps(1), -1.0);
        // 130 比 128 近，比 160 也近 → 还是 q4。
        assert_eq!(vorbis_q_for_kbps(130), 4.0);
        assert_eq!(vorbis_q_for_kbps(150), 5.0);
    }

    /// 实测表是单调的，而且量级远低于 libvorbis 的名义码率 —— 这正是不能用
    /// `VORBIS_LADDER` 当分母的原因，把这个结论钉在测试里。
    #[test]
    fn measured_ogg_ladder_is_monotone_and_below_nominal() {
        let mut prev = 0;
        for q in [-1.0, 0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0] {
            let k = measured_kbps_for_q(q);
            assert!(k >= prev, "q={q} 处实测码率掉了：{prev} → {k}");
            prev = k;
        }
        // q4 是实测点：56.7 → 57，而名义值是 128。
        assert_eq!(measured_kbps_for_q(4.0), 57);
        assert!(measured_kbps_for_q(4.0) < 128, "实测必须明显低于名义码率");
        // q8 之上收口，不再涨。
        assert_eq!(measured_kbps_for_q(10.0), measured_kbps_for_q(8.0));
        // 低端外推仍是正数。
        assert!(measured_kbps_for_q(-1.0) > 0);
    }

    /// 估算出来的分母必须和真实产出同一个量级 —— 差一个数量级会让进度条
    /// 全程贴着 0% 或一路 100% 不动。
    ///
    /// 用宽带白噪而不是正弦：正弦是最容易被压缩的信号，Vorbis 在 q4 下能把它压到
    /// 名义码率的一半以下，拿它去验证码率估算只会得到一个和真实素材无关的比值。
    #[test]
    fn output_estimate_is_the_right_order_of_magnitude() {
        let dir = tmpdir("est");

        // WAV 是无损的：估算必须**精确**，否则「分母」这个词就没意义了。
        let tone = sine(44_100, 2, 1.0, 440.0);
        let wav = dir.join("e.wav");
        let opts = AudioOptions { bit_depth: 16, ..Default::default() };
        encode_wav(&tone, wav.to_str().unwrap(), &opts).unwrap();
        assert_eq!(
            estimate_output_bytes(&tone, "wav", &opts),
            std::fs::metadata(&wav).unwrap().len(),
            "WAV 的估算是精确值，不该有偏差"
        );

        let src = noise(44_100, 2, 2.0);
        for fmt in ["mp3", "ogg"] {
            let p = dir.join(format!("e.{fmt}"));
            encode(&src, p.to_str().unwrap(), fmt, &AudioOptions::default()).unwrap();
            let est = estimate_output_bytes(&src, fmt, &AudioOptions::default()) as f64;
            let real = std::fs::metadata(&p).unwrap().len() as f64;
            let ratio = real / est;
            assert!(
                (0.6..1.7).contains(&ratio),
                "{fmt}: estimate {est} vs real {real} (ratio {ratio:.2})"
            );
        }
        std::fs::remove_dir_all(&dir).ok();
    }

    /// 每条编码链路都要能立刻被我们自己的解码器读回来 —— 这是最快的
    /// 「文件真的成型了」检查，独立 oracle（ffmpeg）在集成测试里。
    #[test]
    fn every_encoder_round_trips_through_our_own_decoder() {
        let dir = tmpdir("rt");
        let src = sine(44_100, 2, 0.5, 440.0);
        for fmt in ["wav", "mp3", "ogg"] {
            let p = dir.join(format!("rt.{fmt}"));
            encode(&src, p.to_str().unwrap(), fmt, &AudioOptions::default())
                .unwrap_or_else(|e| panic!("encode {fmt}: {e}"));
            let back = decode(p.to_str().unwrap()).unwrap_or_else(|e| panic!("decode {fmt}: {e}"));
            assert_eq!(back.rate, src.rate, "{fmt}: sample rate");
            assert_eq!(back.channels, src.channels, "{fmt}: channels");
            // 有损格式会带编码器延迟（首尾各有零点几秒的补零），所以只要求
            // 长度同量级，不要求逐样本相等。
            let ratio = back.frames() as f64 / src.frames() as f64;
            assert!(
                (0.8..1.3).contains(&ratio),
                "{fmt}: decoded {} frames vs source {} (ratio {ratio:.3})",
                back.frames(),
                src.frames()
            );
        }
        std::fs::remove_dir_all(&dir).ok();
    }
}
