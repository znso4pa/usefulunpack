//! AAC 解码：MP4/M4A 容器 + 裸 ADTS 两种装法。
//!
//! 用 `rusty_aac`（Apache-2.0，纯 Rust，无 C/FFI）做实际的 AAC-LC 解码。它只认
//! **裸 access unit**，所以容器那层要我们自己剥：
//!
//! - `.aac` / `.snd`… 里的**裸 ADTS** 流：每帧 7（或 9）字节头 + 原始块，直接在
//!   流上找同步字切帧。
//! - `.m4a` / `.mp4`：走 `mp4` crate（MIT）拿样本表，再用 `elst` 修长度。
//!
//! ## 为什么必须读 `elst`
//!
//! AAC 是**有启动延迟**的编码：编码器在开头塞进一段「priming」样本，解码器吐出来
//! 的是 `priming + 真实音频`。MP4 用 edit list（`elst`）把这个偏移写下来，播放器
//! 靠它把 priming 掐掉。
//!
//! 实测 ffmpeg 编出来的 0.5 s m4a：`mdhd.duration = 23074`（= 22050 + 1024），
//! `elst.media_time = 1024`、`elst.segment_duration = 22050`。**不读 elst 就会多出
//! 48 ms 的开头空白**（iTunes 系编码器的 priming 是 2112 帧，更明显），做 BGM 循环
//! 时那是一个能听见的缝。
//!
//! `mp4` crate 不解析 `edts/elst`，所以这里自己走一遍盒子树 —— 只走
//! `moov/trak/{tkhd,edts/elst}` 这一条细路，不碰 `stbl`（那里可能几 MB）。

use std::io::{Read, Seek, SeekFrom};

use crate::counted::{open_counted, CountedFile};
use crate::pcm::Pcm;
use archive_common::extract_progress;

/// 解出的 PCM 字节数上限。和 `decode.rs` 同一个口径。
const MAX_PCM_BYTES: usize = 512 * 1024 * 1024;

/// ADTS 切帧时的读块大小。
const READ_CHUNK: usize = 64 * 1024;

/// 帧数上限的保险丝：一个坏 `stsz` 能声称有几亿个样本。
const MAX_FRAMES: u64 = 64 * 1024 * 1024;

/// 一条 edit list 里我们要的两件事。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Edit {
    /// 从解码输出的第几帧开始才是真实音频（= 编码器 priming）。
    pub media_time: u64,
    /// 真实音频有多少帧。
    pub duration: u64,
}

/// 按内容分派：ADTS 同步字开头走裸流，否则当 MP4 容器。
pub fn decode(path: &str) -> Result<Pcm, String> {
    let mut head = [0u8; 4];
    {
        let (mut f, _) = open_counted(path)?;
        let n = f.read(&mut head).map_err(|e| format!("read {path}: {e}"))?;
        if n < 4 {
            return Err("file is too short to identify".to_string());
        }
    }
    if rusty_aac::is_adts(&head) {
        decode_adts(path)
    } else {
        decode_mp4(path)
    }
}

// ------------------------------------------------------------------ ADTS

/// 裸 ADTS 基本流（`.aac`）。
///
/// 同步是**字节级**的：坏文件里丢一帧之后必须能重新找到下一个同步字，而不是
/// 一路错到底。所以每次找不到同步字就前进一个字节，而不是报错退出 ——
/// 这和 ffmpeg 的 `aac` 解析器行为一致。
fn decode_adts(path: &str) -> Result<Pcm, String> {
    let (mut file, _) = open_counted(path)?;
    let mut dec: Option<rusty_aac::decode::Decoder> = None;
    let mut samples: Vec<f32> = Vec::new();
    let mut rate = 0u32;
    let mut channels = 0u16;

    // 环形缓冲的简化版：`buf[start..]` 是还没消费的数据。
    let mut buf: Vec<u8> = Vec::with_capacity(READ_CHUNK * 2);
    let mut start = 0usize;
    let mut eof = false;

    loop {
        if extract_progress::cancelled() {
            return Err("cancelled".to_string());
        }
        if !eof {
            let old = buf.len();
            buf.resize(old + READ_CHUNK, 0);
            let n = file
                .read(&mut buf[old..])
                .map_err(|e| format!("read {path}: {e}"))?;
            buf.truncate(old + n);
            if n == 0 {
                eof = true;
            }
        }

        // 消费当前缓冲里所有完整的帧。
        loop {
            let avail = &buf[start..];
            if avail.len() < 7 {
                break;
            }
            if !rusty_aac::is_adts(avail) {
                // 丢一个字节重新找同步。`start` 前进，不搬数据。
                start += 1;
                continue;
            }
            let hdr = match rusty_aac::parse_adts(avail) {
                Ok(h) => h,
                Err(_) => {
                    start += 1;
                    continue;
                }
            };
            if avail.len() < hdr.frame_length {
                break; // 这一帧还没读全，等下一轮。
            }
            if dec.is_none() {
                rate = hdr.sample_rate;
                channels = hdr.channels;
                if rate == 0 {
                    return Err("ADTS declares a sample rate of 0".to_string());
                }
                // `channel_configuration` 为 0 表示走 PCE —— 交给解码器自己
                // 从流里认（`StreamConfig::lc` 的第三个参数就是它）。
                dec = Some(
                    rusty_aac::decode::Decoder::with_stream_config(rusty_aac::config::StreamConfig::lc(
                        2,
                        rate,
                        hdr.channels.min(7) as u8,
                    ))
                    .map_err(|e| format!("aac init: {e}"))?,
                );
            }
            let d = dec
                .as_mut()
                .expect("decoder 刚初始化过")
                // **必须掐掉 ADTS 头**：`Decoder::decode` 收的是裸 `raw_data_block`，
                // 喂整个帧进去它会把 7 字节头当成语法元素解 —— 不报错，出来的是
                // 一段静音（实测峰值恒为 0，长度却完全正确，所以「长度对」什么都
                // 证明不了）。`header_len` 是 7（无 CRC）或 9（有 CRC）。
                .decode(&avail[hdr.header_len..hdr.frame_length], None)
                .map_err(|e| format!("aac decode: {e}"))?;
            if channels == 0 {
                channels = d.channels;
            }
            samples.extend_from_slice(&d.samples);
            start += hdr.frame_length;
            if samples.len() * std::mem::size_of::<f32>() > MAX_PCM_BYTES {
                return Err("aac stream is too long for the in-memory limit".to_string());
            }
        }

        // 一次性把消费掉的前缀扔掉。
        if start > 0 {
            buf.drain(..start);
            start = 0;
        }
        if eof {
            break;
        }
    }

    if samples.is_empty() {
        return Err("no decodable ADTS frames".to_string());
    }
    Ok(Pcm::new(rate, channels, samples))
}

// ------------------------------------------------------------------ MP4

/// MP4 / M4A 容器里的 AAC 音轨。
fn decode_mp4(path: &str) -> Result<Pcm, String> {
    let file = CountedFile::open(path)?;
    let len = file.len();
    let mut rdr = mp4::Mp4Reader::read_header(file, len)
        .map_err(|e| format!("m4a open: {e}"))?;

    // 第一条 `AAC` 类型的音轨。一个 m4a 通常只有一条；mp4 里可能有视频轨，
    // 所以必须按 media_type 挑，不能拿 `tracks().keys().next()`。
    let mut found: Option<(u32, u32, u16)> = None;
    for (id, track) in rdr.tracks() {
        if !matches!(track.media_type(), Ok(mp4::MediaType::AAC)) {
            continue;
        }
        let rate = track
            .sample_freq_index()
            .map(|f| f.freq())
            .map_err(|e| format!("m4a sample rate: {e}"))?;
        let channels = match track.channel_config() {
            Ok(mp4::ChannelConfig::Mono) => 1,
            Ok(mp4::ChannelConfig::Stereo) => 2,
            Ok(mp4::ChannelConfig::Three) => 3,
            Ok(mp4::ChannelConfig::Four) => 4,
            Ok(mp4::ChannelConfig::Five) => 5,
            Ok(mp4::ChannelConfig::FiveOne) => 6,
            Ok(mp4::ChannelConfig::SevenOne) => 8,
            // channelConfiguration 0 = PCE，声道数写在流里。
            Err(_) => 0,
        };
        found = Some((*id, rate, channels));
        break;
    }
    let (track_id, rate, channels) = found.ok_or_else(|| {
        "no AAC audio track in this MP4 (only AAC is supported)".to_string()
    })?;
    if rate == 0 {
        return Err("m4a track declares a sample rate of 0".to_string());
    }

    let count = rdr.sample_count(track_id).map_err(|e| format!("m4a sample count: {e}"))?;
    if count as u64 > MAX_FRAMES {
        return Err(format!("m4a declares {count} samples, refusing"));
    }

    let mut dec = rusty_aac::decode::Decoder::with_stream_config(
        rusty_aac::config::StreamConfig::lc(2, rate, channels.min(7) as u8),
    )
    .map_err(|e| format!("aac init: {e}"))?;

    let mut samples: Vec<f32> = Vec::new();
    for id in 1..=count {
        if extract_progress::cancelled() {
            return Err("cancelled".to_string());
        }
        let Some(s) = rdr
            .read_sample(track_id, id)
            .map_err(|e| format!("m4a read sample {id}: {e}"))?
        else {
            break;
        };
        let d = dec
            .decode(&s.bytes, None)
            .map_err(|e| format!("aac decode (sample {id}): {e}"))?;
        samples.extend_from_slice(&d.samples);
        if samples.len() * std::mem::size_of::<f32>() > MAX_PCM_BYTES {
            return Err("m4a stream is too long for the in-memory limit".to_string());
        }
    }
    if samples.is_empty() {
        return Err("no decodable AAC samples".to_string());
    }

    let ch = if channels == 0 {
        // 解码器从流里认出来的声道数。
        dec.channels() as u16
    } else {
        channels
    };
    if ch == 0 {
        return Err("m4a decoded to 0 channels".to_string());
    }
    let mut pcm = Pcm::new(rate, ch, samples);

    // `elst` 修正：掐掉编码器 priming、截到真实时长。
    if let Some(mut edit) = read_edit_list(path, track_id)? {
        // ⚠️ `segment_duration` 按规范是 **movie 时间轴**（`mvhd.timescale`）的单位，
        // 而 `media_time` 是**媒体时间轴**（`mdhd`）—— 两个单位不一样。
        // ffmpeg 的 mov 复用器恰好把 mvhd 设成音频采样率（实测 44100/48000 都如此），
        // 于是两者相等、直接当帧数用「碰巧」正确；但这是实现细节、不是规范：
        // 换个复用器（mvhd=1000 之类）会把 keep 算成 500 帧而不是 22050，
        // **音频被截成 ~11 ms**。这里按 movie → 媒体（= 采样率，PCM 帧的单位）缩放。
        edit.duration = movie_to_media_frames(edit.duration, rate as u64, rdr.timescale() as u64);
        pcm = trim_edit(pcm, &edit);
    }
    Ok(pcm)
}

/// `elst.segment_duration`（movie 时间轴）→ 媒体帧数（= 采样数，`mdhd` 时间轴）。
///
/// `movie_ts == media_ts`（ffmpeg 的常见情形）或任一为 0 时原样返回。
fn movie_to_media_frames(duration: u64, media_ts: u64, movie_ts: u64) -> u64 {
    if duration == 0 || movie_ts == 0 || media_ts == 0 || movie_ts == media_ts {
        duration
    } else {
        (duration as u128 * media_ts as u128 / movie_ts as u128) as u64
    }
}

/// 按 edit list 掐头去尾。
///
/// 两个半句都要做：
/// - **掐头**（`media_time`）去掉编码器 priming；
/// - **截尾**（`duration`）去掉最后一个不满帧的填充 —— AAC 一帧 1024 个样本，
///   真实时长几乎不会是 1024 的整数倍，多出来的尾巴是解码器的补零。
///
/// `media_time` 超出解码长度时**不报错**：那说明容器和流不一致，此时宁可原样
/// 返回（用户拿到一段能听的音频）也不要把整条链路失败掉。
fn trim_edit(pcm: Pcm, edit: &Edit) -> Pcm {
    let ch = pcm.channels.max(1) as usize;
    let frames = pcm.frames();
    let skip = edit.media_time.min(frames as u64) as usize;
    let keep = if edit.duration == 0 {
        frames - skip
    } else {
        (edit.duration as usize).min(frames - skip)
    };
    if skip == 0 && keep == frames {
        return pcm;
    }
    let samples = pcm.samples[skip * ch..(skip + keep) * ch].to_vec();
    Pcm::new(pcm.rate, pcm.channels, samples)
}

// -------------------------------------------------- edit list（自己走盒子）

/// 只走 `moov/trak` 这一层：拿 `tkhd.track_ID` 对上目标轨，再取它的 `edts/elst`。
///
/// **不进 `stbl`**：那里装着几 MB 的样本表，我们只要 `mp4` crate 已经解析过的
/// 结论，没必要把整个盒子树走一遍。
pub fn read_edit_list(path: &str, want_track_id: u32) -> Result<Option<Edit>, String> {
    let mut f = std::fs::File::open(path).map_err(|e| format!("open {path}: {e}"))?;
    let len = f.metadata().map_err(|e| format!("stat {path}: {e}"))?.len();
    let mut pos = 0u64;
    while pos + 8 <= len {
        let (typ, size, hdr) = read_box_header(&mut f, pos, len)?;
        if size < hdr {
            break;
        }
        if &typ == b"moov" {
            return walk_moov(&mut f, pos + hdr, pos + size, want_track_id);
        }
        pos += size;
    }
    Ok(None)
}

/// 读一个盒子头：`(类型, 整个盒子的字节数, 头部字节数)`。
///
/// `size == 1` 走 64 位 largesize；`size == 0` 表示「一直到文件尾」，按父盒的
/// 边界算。这两种都是规范里的合法写法，不少工具会产出。
fn read_box_header(f: &mut std::fs::File, pos: u64, end: u64) -> Result<([u8; 4], u64, u64), String> {
    f.seek(SeekFrom::Start(pos)).map_err(|e| format!("seek: {e}"))?;
    let mut h = [0u8; 8];
    f.read_exact(&mut h).map_err(|e| format!("read box header: {e}"))?;
    let size32 = u32::from_be_bytes([h[0], h[1], h[2], h[3]]) as u64;
    let typ = [h[4], h[5], h[6], h[7]];
    match size32 {
        1 => {
            let mut l = [0u8; 8];
            f.read_exact(&mut l).map_err(|e| format!("read largesize: {e}"))?;
            Ok((typ, u64::from_be_bytes(l), 16))
        }
        0 => Ok((typ, end - pos, 8)),
        n => Ok((typ, n, 8)),
    }
}

fn walk_moov(
    f: &mut std::fs::File,
    mut pos: u64,
    end: u64,
    want: u32,
) -> Result<Option<Edit>, String> {
    while pos + 8 <= end {
        let (typ, size, hdr) = read_box_header(f, pos, end)?;
        if size < hdr {
            break;
        }
        if &typ == b"trak" {
            if let Some(e) = walk_trak(f, pos + hdr, pos + size, want)? {
                return Ok(Some(e));
            }
        }
        pos += size;
    }
    Ok(None)
}

fn walk_trak(
    f: &mut std::fs::File,
    mut pos: u64,
    end: u64,
    want: u32,
) -> Result<Option<Edit>, String> {
    let mut id: Option<u32> = None;
    let mut edit: Option<Edit> = None;
    while pos + 8 <= end {
        let (typ, size, hdr) = read_box_header(f, pos, end)?;
        if size < hdr {
            break;
        }
        if &typ == b"tkhd" {
            id = Some(read_tkhd_track_id(f, pos + hdr, pos + size)?);
        } else if &typ == b"edts" {
            edit = walk_edts(f, pos + hdr, pos + size)?;
        }
        pos += size;
    }
    // 轨 ID 可能写在 `edts` 后面（规范说 `tkhd` 在前，但别赌），所以等整个
    // `trak` 走完再比对。
    if id == Some(want) {
        Ok(edit)
    } else {
        Ok(None)
    }
}

/// `tkhd`：version(1) + flags(3) + [creation(4|8) + modification(4|8)] + track_ID(4)。
fn read_tkhd_track_id(f: &mut std::fs::File, body: u64, end: u64) -> Result<u32, String> {
    if body + 4 > end {
        return Err("tkhd is truncated".to_string());
    }
    f.seek(SeekFrom::Start(body)).map_err(|e| format!("seek: {e}"))?;
    let mut vf = [0u8; 4];
    f.read_exact(&mut vf).map_err(|e| format!("read tkhd: {e}"))?;
    let version = vf[0];
    // version 1 的时间字段是 64 位，比 version 0 多 8 个字节。
    let skip = if version == 1 { 8 + 8 } else { 4 + 4 };
    f.seek(SeekFrom::Start(body + 4 + skip))
        .map_err(|e| format!("seek: {e}"))?;
    let mut id = [0u8; 4];
    f.read_exact(&mut id).map_err(|e| format!("read tkhd track id: {e}"))?;
    Ok(u32::from_be_bytes(id))
}

fn walk_edts(f: &mut std::fs::File, mut pos: u64, end: u64) -> Result<Option<Edit>, String> {
    while pos + 8 <= end {
        let (typ, size, hdr) = read_box_header(f, pos, end)?;
        if size < hdr {
            break;
        }
        if &typ == b"elst" {
            return read_elst(f, pos + hdr, pos + size);
        }
        pos += size;
    }
    Ok(None)
}

/// `elst`：version(1) + flags(3) + entry_count(4) + 每条
/// `segment_duration(4|8) + media_time(4|8，有符号) + media_rate(4)`。
///
/// 只认第一条 `media_time >= 0` 的 entry：`media_time == -1` 是「空编辑」
/// （一段空白），没有对应到媒体时间轴上，对「从哪儿开始是真的音频」没有意义。
fn read_elst(f: &mut std::fs::File, body: u64, end: u64) -> Result<Option<Edit>, String> {
    if body + 8 > end {
        return Err("elst is truncated".to_string());
    }
    f.seek(SeekFrom::Start(body)).map_err(|e| format!("seek: {e}"))?;
    let mut head = [0u8; 8];
    f.read_exact(&mut head).map_err(|e| format!("read elst: {e}"))?;
    let version = head[0];
    let count = u32::from_be_bytes([head[4], head[5], head[6], head[7]]);
    let entry = if version == 1 { 20u64 } else { 12u64 };
    let mut p = body + 8;
    for _ in 0..count.min(1024) {
        if p + entry > end {
            break;
        }
        f.seek(SeekFrom::Start(p)).map_err(|e| format!("seek: {e}"))?;
        let mut e = vec![0u8; entry as usize];
        f.read_exact(&mut e).map_err(|e| format!("read elst entry: {e}"))?;
        let (dur, mt) = if version == 1 {
            (
                u64::from_be_bytes(e[0..8].try_into().unwrap()),
                i64::from_be_bytes(e[8..16].try_into().unwrap()),
            )
        } else {
            (
                u32::from_be_bytes(e[0..4].try_into().unwrap()) as u64,
                i32::from_be_bytes(e[4..8].try_into().unwrap()) as i64,
            )
        };
        if mt >= 0 {
            return Ok(Some(Edit {
                media_time: mt as u64,
                duration: dur,
            }));
        }
        p += entry;
    }
    Ok(None)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testutil::tmpdir;

    /// `trim_edit` 的两个半句：掐头 + 截尾，且不能越界 panic。
    #[test]
    fn trim_drops_priming_and_padding() {
        // `Pcm` 没有 `Clone`，每次现造一份（100 帧立体声）。
        let fresh = || Pcm::new(44_100, 2, vec![1.0f32; 100 * 2]);

        let t = trim_edit(fresh(), &Edit { media_time: 10, duration: 50 });
        assert_eq!(t.frames(), 50);
        assert_eq!(t.rate, 44_100);
        assert_eq!(t.channels, 2);

        // duration = 0 = 「不知道」：只掐头，剩下全留。
        let t = trim_edit(fresh(), &Edit { media_time: 10, duration: 0 });
        assert_eq!(t.frames(), 90);

        // 什么都不用动时原样返回。
        let t = trim_edit(fresh(), &Edit { media_time: 0, duration: 100 });
        assert_eq!(t.frames(), 100);

        // 容器声称的 media_time 超过了解码长度 —— 不能 panic，也不能留空。
        let t = trim_edit(fresh(), &Edit { media_time: 999, duration: 50 });
        assert_eq!(t.frames(), 0);
        let t = trim_edit(fresh(), &Edit { media_time: 200, duration: 0 });
        assert_eq!(t.frames(), 0);
    }

    /// `elst.segment_duration` 是 **movie 时间轴**的单位，不是媒体帧数 —— 两者不等
    /// 时必须缩放。ffmpeg 恰好把 mvhd 设成采样率（相等、原样），别的复用器不等。
    #[test]
    fn edit_duration_is_rescaled_from_movie_timescale() {
        // 相等：原样返回（ffmpeg 的常见情形）。
        assert_eq!(movie_to_media_frames(22_050, 44_100, 44_100), 22_050);
        // mvhd=1000、mdhd=44100：0.5 s 记成 500，必须还原成 22050 帧。
        assert_eq!(movie_to_media_frames(500, 44_100, 1000), 22_050);
        // duration=0 = 「不知道」：保持 0，别缩放出别的值。
        assert_eq!(movie_to_media_frames(0, 44_100, 1000), 0);
        // 防御：任一为 0 不缩放（避免除零）。
        assert_eq!(movie_to_media_frames(123, 44_100, 0), 123);
        assert_eq!(movie_to_media_frames(123, 0, 1000), 123);
    }

    /// 盒子头解析：8 字节常规、64 位 largesize、size==0 到文件尾。
    #[test]
    fn box_header_handles_all_three_size_encodings() {
        let dir = tmpdir("boxhdr");
        let p = dir.join("boxes.bin");
        let mut b = Vec::new();
        b.extend_from_slice(&16u32.to_be_bytes());
        b.extend_from_slice(b"moov");
        b.extend_from_slice(&[0u8; 8]);
        // largesize
        b.extend_from_slice(&1u32.to_be_bytes());
        b.extend_from_slice(b"mdat");
        b.extend_from_slice(&64u64.to_be_bytes());
        b.extend_from_slice(&[0u8; 48]);
        // size == 0
        b.extend_from_slice(&0u32.to_be_bytes());
        b.extend_from_slice(b"free");
        b.extend_from_slice(&[0u8; 32]);
        std::fs::write(&p, &b).unwrap();

        let mut f = std::fs::File::open(&p).unwrap();
        let end = b.len() as u64;
        let (t, s, h) = read_box_header(&mut f, 0, end).unwrap();
        assert_eq!((&t, s, h), (b"moov", 16, 8));
        let (t, s, h) = read_box_header(&mut f, 16, end).unwrap();
        assert_eq!((&t, s, h), (b"mdat", 64, 16));
        let (t, s, h) = read_box_header(&mut f, 80, end).unwrap();
        assert_eq!((&t, s, h), (b"free", end - 80, 8));
        std::fs::remove_dir_all(&dir).ok();
    }

    /// 真样本（`UU_AUDIO_SAMPLES`）：ffmpeg 写的 m4a 的 edit list 必须被读出来。
    /// 没有样本就 SKIP。
    #[test]
    fn real_m4a_edit_list_is_found_when_available() {
        let Some(dir) = std::env::var_os("UU_AUDIO_SAMPLES") else {
            eprintln!("SKIP real_m4a_edit_list_is_found_when_available: UU_AUDIO_SAMPLES 未设置");
            return;
        };
        let p = std::path::Path::new(&dir).join("sine.m4a");
        if !p.exists() {
            eprintln!("SKIP: {} 不存在", p.display());
            return;
        }
        let e = read_edit_list(p.to_str().unwrap(), 1)
            .expect("read elst")
            .expect("ffmpeg 编出来的 m4a 带 elst");
        // 0.5 s @ 44.1 kHz = 22050 帧，priming 是编码器延迟（非零即可）。
        assert_eq!(e.duration, 22_050, "segment_duration 应该正好是 0.5 s");
        assert!(e.media_time > 0, "media_time 是编码器 priming，必须非零");
    }

    /// 真样本：整条 M4A 链路解出来的长度必须**精确**等于 0.5 s —— 这正是
    /// 「读不读 elst」的差别。不读会多出 priming 那一段。
    #[test]
    fn real_m4a_decodes_to_the_exact_duration_when_available() {
        let Some(dir) = std::env::var_os("UU_AUDIO_SAMPLES") else {
            eprintln!("SKIP real_m4a_decodes_to_the_exact_duration_when_available: UU_AUDIO_SAMPLES 未设置");
            return;
        };
        let p = std::path::Path::new(&dir).join("sine.m4a");
        if !p.exists() {
            eprintln!("SKIP: {} 不存在", p.display());
            return;
        }
        let pcm = decode(p.to_str().unwrap()).expect("decode m4a");
        assert_eq!(pcm.rate, 44_100);
        assert_eq!(pcm.channels, 2);
        assert_eq!(pcm.frames(), 22_050, "elst 修完之后长度必须精确");
    }

    /// 真样本：裸 ADTS 也要能解，长度同量级（裸流没有 elst，所以不要求精确），
    /// **而且波形要真的出来**。
    ///
    /// 「峰值 > 0」这一条不是废话：ADTS 头如果没掐掉，解码器不报错、长度也对，
    /// 出来的却是整整一段静音。只查长度和采样率的测试对那个 bug 完全免疫。
    #[test]
    fn real_adts_decodes_when_available() {
        let Some(dir) = std::env::var_os("UU_AUDIO_SAMPLES") else {
            eprintln!("SKIP real_adts_decodes_when_available: UU_AUDIO_SAMPLES 未设置");
            return;
        };
        let p = std::path::Path::new(&dir).join("sine.aac");
        if !p.exists() {
            eprintln!("SKIP: {} 不存在", p.display());
            return;
        }
        let pcm = decode(p.to_str().unwrap()).expect("decode adts");
        assert_eq!(pcm.rate, 44_100);
        assert_eq!(pcm.channels, 2);
        let ratio = pcm.frames() as f64 / 22_050.0;
        assert!((0.95..1.25).contains(&ratio), "帧数比 {ratio}");
        // 源是 0.5 幅度的 440 Hz 正弦，有损编码后峰值仍在 0.5 附近。
        let peak = pcm.samples.iter().fold(0.0f32, |m, s| m.max(s.abs()));
        assert!(
            (peak - 0.5).abs() < 0.05,
            "ADTS 解出来的峰值是 {peak}，波形不对（0 就是「头没掐掉」）"
        );
    }

    /// 坏文件不能 panic，且错误消息要指明是 AAC。
    #[test]
    fn garbage_is_rejected() {
        let dir = tmpdir("aacbad");
        let p = dir.join("bad.m4a");
        std::fs::write(&p, vec![0x5Au8; 4096]).unwrap();
        let e = decode(p.to_str().unwrap()).unwrap_err();
        assert!(!e.is_empty(), "必须给出一句人话");
        std::fs::remove_dir_all(&dir).ok();
    }
}
