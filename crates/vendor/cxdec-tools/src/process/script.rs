use crate::io::BinaryReader;
use flate2::read::ZlibDecoder;
use std::io::Read;

// Handles process behavior.
pub fn process(data: &[u8]) -> Option<Vec<u8>> {
    let enc_type = *data.get(2)?;
    let body = data.get(5..)?;
    match enc_type {
        0 => decrypt_type0(body),
        1 => decrypt_type1(body),
        2 => decompress_type2(body),
        _ => None,
    }
}

// Decrypts type0.
fn decrypt_type0(body: &[u8]) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(body.len() + 2);
    out.extend_from_slice(&[0xff, 0xfe]);
    for chunk in body.chunks_exact(2) {
        let mut c = BinaryReader::u16_le(chunk);
        if c >= 0x20 {
            c ^= ((c & 0x00fe) << 8) ^ 1;
            out.extend_from_slice(&c.to_le_bytes());
        }
    }
    Some(out)
}

// Decrypts type1.
fn decrypt_type1(body: &[u8]) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(body.len() + 2);
    out.extend_from_slice(&[0xff, 0xfe]);
    for chunk in body.chunks_exact(2) {
        let c = BinaryReader::u16_le(chunk);
        let c = ((c & 0xaaaa) >> 1) | ((c & 0x5555) << 1);
        out.extend_from_slice(&c.to_le_bytes());
    }
    Some(out)
}

// Decompresses type2.
fn decompress_type2(body: &[u8]) -> Option<Vec<u8>> {
    if body.len() < 16 {
        return None;
    }
    let mut decoder = ZlibDecoder::new(&body[16..]);
    let mut out = Vec::new();
    decoder.read_to_end(&mut out).unwrap();
    Some(out)
}
