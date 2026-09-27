//! PackinOne raw TLG structure reader.

use crate::io::BinaryReader;

#[derive(Debug, Clone)]
pub struct Tlg {
    pub header: TlgRawHeader,
    pub chunks: Vec<TlgChunk>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TlgRawHeader {
    pub kind: TlgRawKind,
    pub color_type: u8,
    pub width: u32,
    pub height: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TlgRawKind {
    Tlg5,
    Tlg6,
    Ref,
    Qoi,
    Mux,
    Other(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TlgChunk {
    pub tag: [u8; 4],
    pub payload: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TlgRefTarget {
    pub fingerprint: u32,
    pub index: u32,
    pub count: u32,
    pub storage: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TlgError {
    message: String,
}

type Result<T> = std::result::Result<T, TlgError>;

impl Tlg {
    // Handles parse behavior.
    pub fn parse(data: &[u8]) -> Result<Self> {
        let header = TlgRawHeader::read(data)?;
        let mut chunks = Vec::new();
        let mut pos = TlgRawHeader::LEN;

        while pos < data.len() {
            if data.len() - pos < TlgChunk::HEADER_LEN {
                return Err(TlgError::new("TLG chunk header is truncated"));
            }
            let tag = data[pos..pos + 4]
                .try_into()
                .expect("TLG chunk tag has fixed size");
            let len = BinaryReader::u32_le(&data[pos + 4..pos + 8]) as usize;
            pos += TlgChunk::HEADER_LEN;
            let end = pos
                .checked_add(len)
                .ok_or_else(|| TlgError::new("TLG chunk length overflow"))?;
            if end > data.len() {
                return Err(TlgError::new("TLG chunk payload is truncated"));
            }
            chunks.push(TlgChunk {
                tag,
                payload: data[pos..end].to_vec(),
            });
            pos = end;

            if tag == [0; 4] && len == 0 {
                break;
            }
        }

        Ok(Self { header, chunks })
    }

    // Handles QRef targets behavior.
    pub fn qref_targets(&self) -> Result<Vec<TlgRefTarget>> {
        self.chunks
            .iter()
            .filter(|chunk| chunk.tag == *b"QREF")
            .map(TlgRefTarget::read)
            .collect()
    }

    // Handles strings behavior.
    pub fn strings(&self) -> Result<Vec<String>> {
        Ok(self
            .qref_targets()?
            .into_iter()
            .map(|target| target.storage)
            .collect())
    }
}

impl TlgRawHeader {
    pub const LEN: usize = 20;
    const MAGIC_LEN: usize = 11;

    // Handles read behavior.
    fn read(data: &[u8]) -> Result<Self> {
        if data.len() < Self::LEN {
            return Err(TlgError::new("TLG raw header is truncated"));
        }
        if &data[..3] != b"TLG" || data[6] != 0 || &data[7..10] != b"raw" || data[10] != 0x1a {
            return Err(TlgError::new("TLG raw magic mismatch"));
        }

        let kind = TlgRawKind::from_format(&data[3..6]);
        let color_type = data[11];
        let width = BinaryReader::u32_le(&data[12..16]);
        let height = BinaryReader::u32_le(&data[16..20]);
        Ok(Self {
            kind,
            color_type,
            width,
            height,
        })
    }

    // Returns whether raw TLG.
    pub fn is_raw_tlg(data: &[u8]) -> bool {
        data.len() >= Self::MAGIC_LEN
            && &data[..3] == b"TLG"
            && data[6] == 0
            && &data[7..10] == b"raw"
            && data[10] == 0x1a
    }
}

impl TlgRawKind {
    // Handles from format behavior.
    fn from_format(format: &[u8]) -> Self {
        match format {
            b"5.0" => Self::Tlg5,
            b"6.0" => Self::Tlg6,
            b"ref" => Self::Ref,
            b"qoi" => Self::Qoi,
            b"mux" => Self::Mux,
            other => Self::Other(String::from_utf8_lossy(other).into_owned()),
        }
    }
}

impl TlgChunk {
    const HEADER_LEN: usize = 8;
}

impl TlgRefTarget {
    // Handles read behavior.
    fn read(chunk: &TlgChunk) -> Result<Self> {
        let data = chunk.payload.as_slice();
        if data.len() < 16 {
            return Err(TlgError::new("QREF payload is truncated"));
        }

        let fingerprint = BinaryReader::u32_le(&data[0..4]);
        let index = BinaryReader::u32_le(&data[4..8]);
        let count = BinaryReader::u32_le(&data[8..12]);
        let name_len = BinaryReader::u32_le(&data[12..16]) as usize;
        if !name_len.is_multiple_of(2) {
            return Err(TlgError::new(
                "QREF storage name length is not UTF-16 aligned",
            ));
        }
        let name_end = 16usize
            .checked_add(name_len)
            .ok_or_else(|| TlgError::new("QREF storage name length overflow"))?;
        if name_end > data.len() {
            return Err(TlgError::new("QREF storage name is truncated"));
        }

        let words = data[16..name_end]
            .chunks_exact(2)
            .map(BinaryReader::u16_le)
            .collect::<Vec<_>>();
        let storage = String::from_utf16(&words)
            .map_err(|_| TlgError::new("QREF storage name is not valid UTF-16"))?;

        Ok(Self {
            fingerprint,
            index,
            count,
            storage,
        })
    }
}

impl TlgError {
    // Creates a new value for this type.
    fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
        }
    }
}

impl std::fmt::Display for TlgError {
    // Formats this value for human-readable output.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for TlgError {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    // Parses QRef target.
    fn parse_qref_target() {
        let mut data = Vec::new();
        data.extend_from_slice(b"TLGref\0raw\x1a");
        data.push(4);
        data.extend_from_slice(&1920u32.to_le_bytes());
        data.extend_from_slice(&1080u32.to_le_bytes());

        let name = "ev001__nox6m4.tlg";
        let name_bytes = name
            .encode_utf16()
            .flat_map(u16::to_le_bytes)
            .collect::<Vec<_>>();
        let mut payload = Vec::new();
        payload.extend_from_slice(&0xdafdfc07u32.to_le_bytes());
        payload.extend_from_slice(&1u32.to_le_bytes());
        payload.extend_from_slice(&7u32.to_le_bytes());
        payload.extend_from_slice(&(name_bytes.len() as u32).to_le_bytes());
        payload.extend_from_slice(&name_bytes);

        data.extend_from_slice(b"QREF");
        data.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        data.extend_from_slice(&payload);
        data.extend_from_slice(&[0; 8]);

        let tlg = Tlg::parse(&data).expect("TLGref should parse");
        assert_eq!(tlg.header.kind, TlgRawKind::Ref);
        assert_eq!(tlg.header.width, 1920);
        assert_eq!(tlg.header.height, 1080);

        let targets = tlg.qref_targets().expect("QREF should parse");
        assert_eq!(
            targets,
            vec![TlgRefTarget {
                fingerprint: 0xdafdfc07,
                index: 1,
                count: 7,
                storage: name.to_owned(),
            }]
        );
    }
}
