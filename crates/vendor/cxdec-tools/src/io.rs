//! Binary I/O helpers shared by parsers and crypto adapters.

use std::io::{Read, Seek, SeekFrom};

// ============================ Struct definitions ============================

/// Byte order selector for multi-byte integer decoding.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Endian {
    Little,
    Big,
}

/// Cursor over an in-memory byte slice plus stateless little/big-endian decoders.
#[derive(Debug, Clone)]
pub(crate) struct BinaryReader<'a> {
    data: &'a [u8],
    pos: usize,
}

/// Stateless little/big-endian encoders writing into caller-owned byte slices.
pub(crate) struct BinaryWriter;

/// Neutral `std::io` adapters: streaming reads, seeking, and error helpers.
pub(crate) struct BinaryIo;

// ============================ Implementations ==============================

impl Endian {
    // Reads a u16 from a byte slice using this byte order.
    pub(crate) fn read_u16(self, data: &[u8]) -> u16 {
        match self {
            Self::Little => BinaryReader::u16_le(data),
            Self::Big => BinaryReader::u16_be(data),
        }
    }

    // Reads a u32 from a byte slice using this byte order.
    pub(crate) fn read_u32(self, data: &[u8]) -> u32 {
        match self {
            Self::Little => BinaryReader::u32_le(data),
            Self::Big => BinaryReader::u32_be(data),
        }
    }

    // Reads a u64 from a byte slice using this byte order.
    pub(crate) fn read_u64(self, data: &[u8]) -> u64 {
        match self {
            Self::Little => BinaryReader::u64_le(data),
            Self::Big => BinaryReader::u64_be(data),
        }
    }
}

impl<'a> BinaryReader<'a> {
    // Reads a little-endian u16 from exactly two bytes.
    pub(crate) fn u16_le(data: &[u8]) -> u16 {
        u16::from_le_bytes(data.try_into().expect("u16 input has fixed size"))
    }

    // Reads a big-endian u16 from exactly two bytes.
    pub(crate) fn u16_be(data: &[u8]) -> u16 {
        u16::from_be_bytes(data.try_into().expect("u16 input has fixed size"))
    }

    // Reads a little-endian i16 from exactly two bytes.
    pub(crate) fn i16_le(data: &[u8]) -> i16 {
        i16::from_le_bytes(data.try_into().expect("i16 input has fixed size"))
    }

    // Reads a little-endian u32 from exactly four bytes.
    pub(crate) fn u32_le(data: &[u8]) -> u32 {
        u32::from_le_bytes(data.try_into().expect("u32 input has fixed size"))
    }

    // Reads a big-endian u32 from exactly four bytes.
    pub(crate) fn u32_be(data: &[u8]) -> u32 {
        u32::from_be_bytes(data.try_into().expect("u32 input has fixed size"))
    }

    // Reads a little-endian i32 from exactly four bytes.
    pub(crate) fn i32_le(data: &[u8]) -> i32 {
        i32::from_le_bytes(data.try_into().expect("i32 input has fixed size"))
    }

    // Reads a little-endian u64 from exactly eight bytes.
    pub(crate) fn u64_le(data: &[u8]) -> u64 {
        u64::from_le_bytes(data.try_into().expect("u64 input has fixed size"))
    }

    // Reads a big-endian u64 from exactly eight bytes.
    pub(crate) fn u64_be(data: &[u8]) -> u64 {
        u64::from_be_bytes(data.try_into().expect("u64 input has fixed size"))
    }

    // Reads a little-endian i64 from exactly eight bytes.
    pub(crate) fn i64_le(data: &[u8]) -> i64 {
        i64::from_le_bytes(data.try_into().expect("i64 input has fixed size"))
    }

    // Reads a little-endian u16 from a byte slice at a fixed offset.
    pub(crate) fn u16_le_at(data: &[u8], offset: usize) -> u16 {
        Self::u16_le(&data[offset..offset + 2])
    }

    // Reads a little-endian u32 from a byte slice at a fixed offset.
    pub(crate) fn u32_le_at(data: &[u8], offset: usize) -> u32 {
        Self::u32_le(&data[offset..offset + 4])
    }

    // Creates a reader over a byte slice.
    pub(crate) fn new(data: &'a [u8]) -> Self {
        Self { data, pos: 0 }
    }

    // Creates a reader over a byte slice at a fixed position.
    pub(crate) fn new_at(data: &'a [u8], pos: usize) -> std::io::Result<Self> {
        if pos > data.len() {
            return Err(BinaryIo::invalid_input(format!(
                "offset out of range: 0x{pos:X}"
            )));
        }
        Ok(Self { data, pos })
    }

    // Returns the backing slice length.
    pub(crate) fn len(&self) -> usize {
        self.data.len()
    }

    // Returns the current byte position.
    pub(crate) fn position(&self) -> usize {
        self.pos
    }

    // Sets the current byte position.
    pub(crate) fn set_position(&mut self, pos: usize) -> std::io::Result<()> {
        if pos > self.data.len() {
            return Err(BinaryIo::unexpected_eof(format!(
                "seek past end at 0x{pos:X}"
            )));
        }
        self.pos = pos;
        Ok(())
    }

    // Returns the unread byte count.
    pub(crate) fn remaining(&self) -> usize {
        self.data.len().saturating_sub(self.pos)
    }

    // Reads a borrowed byte range and advances the cursor.
    pub(crate) fn read_bytes(&mut self, len: usize) -> std::io::Result<&'a [u8]> {
        let end = self
            .pos
            .checked_add(len)
            .ok_or_else(|| BinaryIo::invalid_input("read offset overflow"))?;
        if end > self.data.len() {
            return Err(BinaryIo::unexpected_eof(format!(
                "unexpected EOF at 0x{:X}, need {len} bytes",
                self.pos
            )));
        }
        let bytes = &self.data[self.pos..end];
        self.pos = end;
        Ok(bytes)
    }

    // Reads one byte.
    pub(crate) fn read_u8(&mut self) -> std::io::Result<u8> {
        Ok(self.read_bytes(1)?[0])
    }

    // Reads a little-endian u16.
    pub(crate) fn read_u16_le(&mut self) -> std::io::Result<u16> {
        Ok(Self::u16_le(self.read_bytes(2)?))
    }

    // Reads a little-endian i16.
    pub(crate) fn read_i16_le(&mut self) -> std::io::Result<i16> {
        Ok(Self::i16_le(self.read_bytes(2)?))
    }

    // Reads a little-endian u32.
    pub(crate) fn read_u32_le(&mut self) -> std::io::Result<u32> {
        Ok(Self::u32_le(self.read_bytes(4)?))
    }

    // Reads a u32 using the requested byte order.
    pub(crate) fn read_u32(&mut self, endian: Endian) -> std::io::Result<u32> {
        Ok(endian.read_u32(self.read_bytes(4)?))
    }

    // Reads a little-endian i32.
    pub(crate) fn read_i32_le(&mut self) -> std::io::Result<i32> {
        Ok(Self::i32_le(self.read_bytes(4)?))
    }

    // Reads a little-endian u64.
    pub(crate) fn read_u64_le(&mut self) -> std::io::Result<u64> {
        Ok(Self::u64_le(self.read_bytes(8)?))
    }

    // Reads a u64 using the requested byte order.
    pub(crate) fn read_u64(&mut self, endian: Endian) -> std::io::Result<u64> {
        Ok(endian.read_u64(self.read_bytes(8)?))
    }

    // Reads a little-endian i64.
    pub(crate) fn read_i64_le(&mut self) -> std::io::Result<i64> {
        Ok(Self::i64_le(self.read_bytes(8)?))
    }

    // Skips padding bytes until the cursor is aligned to four bytes.
    pub(crate) fn align4(&mut self) -> std::io::Result<()> {
        let rem = self.pos & 3;
        if rem != 0 {
            let _ = self.read_bytes(4 - rem)?;
        }
        Ok(())
    }
}

impl BinaryWriter {
    // Writes a little-endian u32 into exactly four bytes.
    pub(crate) fn u32_le(value: u32, out: &mut [u8]) {
        out.copy_from_slice(&value.to_le_bytes());
    }

    // Writes a little-endian u32 into a byte slice at a fixed offset.
    pub(crate) fn u32_le_at(value: u32, data: &mut [u8], offset: usize) {
        Self::u32_le(value, &mut data[offset..offset + 4]);
    }

    // Writes a big-endian u64 into exactly eight bytes.
    pub(crate) fn u64_be(value: u64, out: &mut [u8]) {
        out.copy_from_slice(&value.to_be_bytes());
    }
}

impl BinaryIo {
    // Reads a little-endian u16 from a reader.
    pub(crate) fn read_u16_le<R: Read>(reader: &mut R) -> std::io::Result<u16> {
        let mut bytes = [0u8; 2];
        reader.read_exact(&mut bytes)?;
        Ok(u16::from_le_bytes(bytes))
    }

    // Reads a little-endian i16 from a reader.
    pub(crate) fn read_i16_le<R: Read>(reader: &mut R) -> std::io::Result<i16> {
        let mut bytes = [0u8; 2];
        reader.read_exact(&mut bytes)?;
        Ok(i16::from_le_bytes(bytes))
    }

    // Reads a little-endian u32 from a reader.
    pub(crate) fn read_u32_le<R: Read>(reader: &mut R) -> std::io::Result<u32> {
        let mut bytes = [0u8; 4];
        reader.read_exact(&mut bytes)?;
        Ok(u32::from_le_bytes(bytes))
    }

    // Reads a little-endian i32 from a reader.
    pub(crate) fn read_i32_le<R: Read>(reader: &mut R) -> std::io::Result<i32> {
        let mut bytes = [0u8; 4];
        reader.read_exact(&mut bytes)?;
        Ok(i32::from_le_bytes(bytes))
    }

    // Reads a little-endian i64 from a reader.
    pub(crate) fn read_i64_le<R: Read>(reader: &mut R) -> std::io::Result<i64> {
        let mut bytes = [0u8; 8];
        reader.read_exact(&mut bytes)?;
        Ok(i64::from_le_bytes(bytes))
    }

    // Reads a big-endian i32 from a reader.
    pub(crate) fn read_i32_be<R: Read>(reader: &mut R) -> std::io::Result<i32> {
        let mut bytes = [0u8; 4];
        reader.read_exact(&mut bytes)?;
        Ok(i32::from_be_bytes(bytes))
    }

    // Reads a big-endian i64 from a reader.
    pub(crate) fn read_i64_be<R: Read>(reader: &mut R) -> std::io::Result<i64> {
        let mut bytes = [0u8; 8];
        reader.read_exact(&mut bytes)?;
        Ok(i64::from_be_bytes(bytes))
    }

    // Reads a little-endian u32 from a seekable reader at a fixed offset.
    pub(crate) fn read_u32_le_at<R: Read + Seek>(
        reader: &mut R,
        offset: u64,
    ) -> std::io::Result<u32> {
        reader.seek(SeekFrom::Start(offset))?;
        Self::read_u32_le(reader)
    }

    // Reads a little-endian i64 from a seekable reader at a fixed offset.
    pub(crate) fn read_i64_le_at<R: Read + Seek>(
        reader: &mut R,
        offset: u64,
    ) -> std::io::Result<i64> {
        reader.seek(SeekFrom::Start(offset))?;
        Self::read_i64_le(reader)
    }

    // Reads UTF-16LE units from a reader.
    pub(crate) fn read_utf16_units_le<R: Read>(
        reader: &mut R,
        len: usize,
    ) -> std::io::Result<Vec<u16>> {
        let mut chars = Vec::with_capacity(len);
        for _ in 0..len {
            chars.push(Self::read_u16_le(reader)?);
        }
        Ok(chars)
    }

    // Creates an invalid-input I/O error.
    pub(crate) fn invalid_input(message: impl Into<String>) -> std::io::Error {
        std::io::Error::new(std::io::ErrorKind::InvalidInput, message.into())
    }

    // Creates an unexpected-EOF I/O error.
    pub(crate) fn unexpected_eof(message: impl Into<String>) -> std::io::Error {
        std::io::Error::new(std::io::ErrorKind::UnexpectedEof, message.into())
    }
}
