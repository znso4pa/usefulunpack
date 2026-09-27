//! XP3 entry file type detection.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FileType {
    Tlg,
    TlgRef,
    TlgQoi,
    Png,
    Jpeg,
    Bitmap,
    Ogg,
    Wave,
    Webp,
    Avi,
    Text,
    Psb,
    TjsBytecode,
    Pbd,
    Script,
    Unknown,
}

// Handles detect behavior.
pub fn detect(head: &[u8]) -> FileType {
    if head.len() >= 11 && &head[..11] == b"TLGref\0raw\x1a" {
        return FileType::TlgRef;
    }
    if head.len() >= 11 && &head[..11] == b"TLGqoi\0raw\x1a" {
        return FileType::TlgQoi;
    }
    if head.len() >= 3 && &head[..3] == b"TLG" {
        return FileType::Tlg;
    }
    if head.len() >= 8 && &head[..8] == b"\x89PNG\r\n\x1a\n" {
        return FileType::Png;
    }
    if head.len() >= 2 && &head[..2] == b"\xff\xd8" {
        return FileType::Jpeg;
    }
    if head.len() >= 2 && &head[..2] == b"BM" {
        return FileType::Bitmap;
    }
    if head.len() >= 4 && &head[..4] == b"OggS" {
        return FileType::Ogg;
    }
    if head.len() >= 12 && &head[..4] == b"RIFF" && &head[8..12] == b"WAVE" {
        return FileType::Wave;
    }
    if head.len() >= 12 && &head[..4] == b"RIFF" && &head[8..12] == b"WEBP" {
        return FileType::Webp;
    }
    if head.len() >= 12 && &head[..4] == b"RIFF" && &head[8..12] == b"AVI " {
        return FileType::Avi;
    }
    if head.len() >= 8 && &head[..8] == b"TJS2100\0" {
        return FileType::TjsBytecode;
    }
    if head.len() >= 8
        && (&head[..4] == b"TJS/" || &head[..4] == b"TJS\\")
        && matches!(head[4], b'4' | b'n')
        && &head[5..8] == b"s0\0"
    {
        return FileType::Pbd;
    }
    if head.len() >= 4 && &head[..4] == b"PSB\0" {
        return FileType::Psb;
    }
    if looks_like_encrypted_script(head) {
        return FileType::Script;
    }
    if looks_like_text(head) {
        return FileType::Text;
    }
    FileType::Unknown
}

// Checks whether data looks like text.
fn looks_like_text(head: &[u8]) -> bool {
    let sample = &head[..head.len().min(256)];
    if sample.is_empty() {
        return false;
    }
    if sample.starts_with(&[0xff, 0xfe]) || sample.starts_with(&[0xfe, 0xff]) {
        return true;
    }
    let Some(text) = utf8_sample(sample) else {
        return false;
    };
    !text.is_empty() && text.chars().all(is_plain_text_char)
}

// Handles UTF-8 sample behavior.
fn utf8_sample(sample: &[u8]) -> Option<&str> {
    match std::str::from_utf8(sample) {
        Ok(text) => Some(text),
        Err(err) if err.error_len().is_none() && err.valid_up_to() > 0 => {
            std::str::from_utf8(&sample[..err.valid_up_to()]).ok()
        }
        Err(_) => None,
    }
}

// Returns whether plain text char.
fn is_plain_text_char(ch: char) -> bool {
    ch == '\r' || ch == '\n' || ch == '\t' || (!ch.is_control() && ch != '\0')
}

// Checks whether data looks like encrypted script.
fn looks_like_encrypted_script(head: &[u8]) -> bool {
    head.len() >= 5
        && head[0] == 0xfe
        && head[1] == 0xfe
        && head[3] == 0xff
        && head[4] == 0xfe
        && head[2] < 3
}
