//! Minimal FreeMote-compatible PSB reader for structure and constant pools.

use crate::io::BinaryReader;
use std::collections::BTreeMap;

#[derive(Debug, Clone)]
pub struct Psb {
    pub header: PsbHeader,
    pub names: Vec<String>,
    pub strings: Vec<String>,
    pub root: PsbValue,
}

#[derive(Debug, Clone, Copy)]
pub struct PsbHeader {
    pub version: u16,
    pub header_encrypt: u16,
    pub header_length: u32,
    pub offset_names: u32,
    pub offset_strings: u32,
    pub offset_strings_data: u32,
    pub offset_chunk_offsets: u32,
    pub offset_chunk_lengths: u32,
    pub offset_chunk_data: u32,
    pub offset_entries: u32,
    pub checksum: u32,
    pub offset_extra_chunk_offsets: u32,
    pub offset_extra_chunk_lengths: u32,
    pub offset_extra_chunk_data: u32,
}

#[derive(Debug, Clone)]
pub enum PsbValue {
    Null,
    Bool(bool),
    Int(i64),
    Float(f32),
    Double(f64),
    Array(Vec<u32>),
    String(String),
    Resource { index: u32, extra: bool },
    List(Vec<PsbValue>),
    Object(BTreeMap<String, PsbValue>),
}

#[derive(Debug)]
pub struct PsbError {
    message: String,
}

type Result<T> = std::result::Result<T, PsbError>;

impl Psb {
    // Handles parse behavior.
    pub fn parse(data: &[u8]) -> Result<Self> {
        let mut reader = BinaryReader::new(data);
        let header = PsbHeader::read(&mut reader)?;
        if header.is_header_encrypted(data.len()) {
            return Err(PsbError::new("encrypted PSB headers are not supported"));
        }

        let string_offsets = read_array_at(data, header.offset_strings)?;
        let strings = load_strings(data, &header, &string_offsets)?;
        let names = if header.version == 1 {
            let name_indexes = read_array_at(data, header.header_length)?;
            load_v1_names(data, &header, &name_indexes)?
        } else {
            let mut names_reader = BinaryReader::new_at(data, header.offset_names as usize)?;
            let charset = read_typed_array(&mut names_reader)?;
            let names_data = read_typed_array(&mut names_reader)?;
            let name_indexes = read_typed_array(&mut names_reader)?;
            load_trie_names(&charset, &names_data, &name_indexes)?
        };

        let mut parser = Parser {
            data,
            header,
            names,
            strings: strings.clone(),
        };
        let mut entry_reader = BinaryReader::new_at(data, header.offset_entries as usize)?;
        let root = parser.unpack(&mut entry_reader)?;

        Ok(Self {
            header,
            names: parser.names,
            strings,
            root,
        })
    }

    // Handles constant strings behavior.
    pub fn constant_strings(&self) -> impl Iterator<Item = &String> {
        self.names.iter().chain(self.strings.iter())
    }
}

impl PsbHeader {
    // Handles read behavior.
    fn read(reader: &mut BinaryReader<'_>) -> Result<Self> {
        let signature = reader.read_bytes(4)?;
        if !signature.starts_with(b"PSB") {
            return Err(PsbError::new("not a PSB file"));
        }

        let version = reader.read_u16_le()?;
        let header_encrypt = reader.read_u16_le()?;
        let header_length = reader.read_u32_le()?;
        let offset_names = reader.read_u32_le()?;

        let mut header = PsbHeader {
            version,
            header_encrypt,
            header_length,
            offset_names,
            offset_strings: 0,
            offset_strings_data: 0,
            offset_chunk_offsets: 0,
            offset_chunk_lengths: 0,
            offset_chunk_data: 0,
            offset_entries: 0,
            checksum: 0,
            offset_extra_chunk_offsets: 0,
            offset_extra_chunk_lengths: 0,
            offset_extra_chunk_data: 0,
        };

        if offset_names < reader.len() as u32 {
            header.offset_strings = reader.read_u32_le()?;
            header.offset_strings_data = reader.read_u32_le()?;
            header.offset_chunk_offsets = reader.read_u32_le()?;
            header.offset_chunk_lengths = reader.read_u32_le()?;
            header.offset_chunk_data = reader.read_u32_le()?;
            header.offset_entries = reader.read_u32_le()?;
            if version > 2 {
                header.checksum = reader.read_u32_le()?;
            }
            if version > 3 {
                header.offset_extra_chunk_offsets = reader.read_u32_le()?;
                header.offset_extra_chunk_lengths = reader.read_u32_le()?;
                header.offset_extra_chunk_data = reader.read_u32_le()?;
            }
        }

        Ok(header)
    }

    // Returns whether header encrypted.
    fn is_header_encrypted(&self, file_len: usize) -> bool {
        self.header_length > 72
            || self.offset_names == 0
            || (self.version > 1
                && self.header_length != self.offset_names
                && self.header_length != 0)
            || self.offset_names as usize >= file_len
    }
}

struct Parser<'a> {
    data: &'a [u8],
    header: PsbHeader,
    names: Vec<String>,
    strings: Vec<String>,
}

impl Parser<'_> {
    // Handles unpack behavior.
    fn unpack(&mut self, reader: &mut BinaryReader<'_>) -> Result<PsbValue> {
        let type_byte = reader.read_u8()?;
        match type_byte {
            0x00 | 0x01 => Ok(PsbValue::Null),
            0x02 => Ok(PsbValue::Bool(false)),
            0x03 => Ok(PsbValue::Bool(true)),
            0x04 => Ok(PsbValue::Int(0)),
            0x05..=0x0c => Ok(PsbValue::Int(read_signed_compact(
                reader,
                type_byte - 0x04,
            )?)),
            0x0d..=0x14 => Ok(PsbValue::Array(read_array(reader, type_byte - 0x0d + 1)?)),
            0x15..=0x18 => {
                let index = read_compact_u32(reader, type_byte - 0x15 + 1)?;
                Ok(PsbValue::String(self.load_string(index)?))
            }
            0x19..=0x1c => Ok(PsbValue::Resource {
                index: read_compact_u32(reader, type_byte - 0x19 + 1)?,
                extra: false,
            }),
            0x1d => Ok(PsbValue::Float(0.0)),
            0x1e => Ok(PsbValue::Float(f32::from_bits(reader.read_u32_le()?))),
            0x1f => Ok(PsbValue::Double(f64::from_bits(reader.read_u64_le()?))),
            0x20 => self.load_list(reader),
            0x21 if self.header.version == 1 => self.load_object_v1(reader),
            0x21 => self.load_object(reader),
            0x22..=0x25 => Ok(PsbValue::Resource {
                index: read_compact_u32(reader, type_byte - 0x22 + 1)?,
                extra: true,
            }),
            _ => Err(PsbError::new(format!(
                "unknown PSB object type 0x{type_byte:02X} at 0x{:X}",
                reader.position().saturating_sub(1)
            ))),
        }
    }

    // Loads object.
    fn load_object(&mut self, reader: &mut BinaryReader<'_>) -> Result<PsbValue> {
        let name_count_type = reader.read_u8()?;
        let names = read_array(reader, array_size_from_type(name_count_type)?)?;
        let offset_count_type = reader.read_u8()?;
        let offsets = read_array(reader, array_size_from_type(offset_count_type)?)?;
        let base = reader.position();
        let mut object = BTreeMap::new();

        for (index, name_index) in names.iter().enumerate() {
            let Some(offset) = offsets.get(index) else {
                continue;
            };
            let Some(name) = self.names.get(*name_index as usize).cloned() else {
                continue;
            };
            let mut child_reader =
                BinaryReader::new_at(self.data, checked_add(base, *offset as usize)?)?;
            let value = self.unpack(&mut child_reader)?;
            object.insert(name, value);
        }

        Ok(PsbValue::Object(object))
    }

    // Loads object v1.
    fn load_object_v1(&mut self, reader: &mut BinaryReader<'_>) -> Result<PsbValue> {
        let offset_count_type = reader.read_u8()?;
        let offsets = read_array(reader, array_size_from_type(offset_count_type)?)?;
        let base = reader.position();
        let mut object = BTreeMap::new();

        for offset in offsets {
            let mut child_reader =
                BinaryReader::new_at(self.data, checked_add(base, offset as usize)?)?;
            let type_byte = child_reader.read_u8()?;
            if !(0x05..=0x08).contains(&type_byte) {
                return Err(PsbError::new("invalid PSBv1 object key type"));
            }
            let name_index = read_compact_u32(&mut child_reader, type_byte - 0x04)?;
            let Some(name) = self.names.get(name_index as usize).cloned() else {
                continue;
            };
            let value = self.unpack(&mut child_reader)?;
            object.insert(name, value);
        }

        Ok(PsbValue::Object(object))
    }

    // Loads list.
    fn load_list(&mut self, reader: &mut BinaryReader<'_>) -> Result<PsbValue> {
        let offset_count_type = reader.read_u8()?;
        let offsets = read_array(reader, array_size_from_type(offset_count_type)?)?;
        let base = reader.position();
        let mut values = Vec::with_capacity(offsets.len());

        for offset in offsets {
            let mut child_reader =
                BinaryReader::new_at(self.data, checked_add(base, offset as usize)?)?;
            values.push(self.unpack(&mut child_reader)?);
        }

        Ok(PsbValue::List(values))
    }

    // Loads string.
    fn load_string(&self, index: u32) -> Result<String> {
        self.strings
            .get(index as usize)
            .cloned()
            .ok_or_else(|| PsbError::new(format!("string index out of range: {index}")))
    }
}

// Reads a compact little-endian PSB integer.
fn read_compact_u32(reader: &mut BinaryReader<'_>, size: u8) -> Result<u32> {
    if size > 4 {
        return Err(PsbError::new(format!("invalid compact u32 size: {size}")));
    }
    Ok(unzip_u32(reader.read_bytes(size as usize)?))
}

// Reads a signed compact little-endian PSB integer.
fn read_signed_compact(reader: &mut BinaryReader<'_>, size: u8) -> Result<i64> {
    if size > 8 {
        return Err(PsbError::new(format!(
            "invalid compact integer size: {size}"
        )));
    }
    Ok(unzip_i64(reader.read_bytes(size as usize)?))
}

// Reads a typed PSB array.
fn read_typed_array(reader: &mut BinaryReader<'_>) -> Result<Vec<u32>> {
    let type_byte = reader.read_u8()?;
    read_array(reader, array_size_from_type(type_byte)?)
}

// Reads a PSB compact integer array.
fn read_array(reader: &mut BinaryReader<'_>, count_size: u8) -> Result<Vec<u32>> {
    if count_size > 8 {
        return Err(PsbError::new(format!(
            "invalid PSB array count size: {count_size}"
        )));
    }
    let count = unzip_u64(reader.read_bytes(count_size as usize)?) as usize;
    let entry_type = reader.read_u8()?;
    if entry_type < 0x0c {
        return Err(PsbError::new(format!(
            "invalid PSB array entry type: 0x{entry_type:02X}"
        )));
    }
    let entry_size = entry_type - 0x0c;
    if entry_size > 4 {
        return Err(PsbError::new(format!(
            "unsupported PSB array entry size: {entry_size}"
        )));
    }
    if entry_size == 0 {
        return if count == 0 {
            Ok(Vec::new())
        } else {
            Err(PsbError::new("non-empty PSB array with zero-width entries"))
        };
    }

    let byte_len = checked_mul(count, entry_size as usize)?;
    let bytes = reader.read_bytes(byte_len)?;
    let mut values = Vec::with_capacity(count);
    for chunk in bytes.chunks(entry_size as usize) {
        values.push(unzip_u32(chunk));
    }
    Ok(values)
}

// Reads array at.
fn read_array_at(data: &[u8], offset: u32) -> Result<Vec<u32>> {
    let mut reader = BinaryReader::new_at(data, offset as usize)?;
    read_typed_array(&mut reader)
}

// Loads strings.
fn load_strings(data: &[u8], header: &PsbHeader, offsets: &[u32]) -> Result<Vec<String>> {
    let mut strings = Vec::with_capacity(offsets.len());
    for offset in offsets {
        let start = checked_add(header.offset_strings_data as usize, *offset as usize)?;
        strings.push(read_zero_string(data, start)?);
    }
    Ok(strings)
}

// Loads trie names.
fn load_trie_names(
    charset: &[u32],
    names_data: &[u32],
    name_indexes: &[u32],
) -> Result<Vec<String>> {
    let mut names = Vec::with_capacity(name_indexes.len());
    for index in name_indexes {
        let mut current = *index as usize;
        let mut bytes = Vec::new();
        loop {
            let Some(&chr) = names_data.get(current) else {
                return Err(PsbError::new("name trie index out of range"));
            };
            if chr == 0 {
                break;
            }
            let Some(&code) = names_data.get(chr as usize) else {
                return Err(PsbError::new("name trie code out of range"));
            };
            let Some(&delta) = charset.get(code as usize) else {
                return Err(PsbError::new("name trie charset index out of range"));
            };
            let real_chr = chr
                .checked_sub(delta)
                .ok_or_else(|| PsbError::new("invalid name trie delta"))?;
            bytes.push(real_chr as u8);
            current = code as usize;
        }
        bytes.reverse();
        names.push(String::from_utf8_lossy(&bytes).into_owned());
    }
    Ok(names)
}

// Loads v1 names.
fn load_v1_names(data: &[u8], header: &PsbHeader, name_indexes: &[u32]) -> Result<Vec<String>> {
    let mut names = Vec::with_capacity(name_indexes.len());
    for offset in name_indexes {
        let start = checked_add(header.offset_names as usize, *offset as usize)?;
        names.push(read_zero_string(data, start)?);
    }
    Ok(names)
}

// Reads zero string.
fn read_zero_string(data: &[u8], start: usize) -> Result<String> {
    if start >= data.len() {
        return Err(PsbError::new(format!(
            "string offset out of range: 0x{start:X}"
        )));
    }
    let end = data[start..]
        .iter()
        .position(|byte| *byte == 0)
        .map(|pos| start + pos)
        .ok_or_else(|| PsbError::new("unterminated PSB string"))?;
    Ok(String::from_utf8_lossy(&data[start..end]).into_owned())
}

// Handles array size from type behavior.
fn array_size_from_type(type_byte: u8) -> Result<u8> {
    if !(0x0d..=0x14).contains(&type_byte) {
        return Err(PsbError::new(format!(
            "expected PSB array type, got 0x{type_byte:02X}"
        )));
    }
    Ok(type_byte - 0x0d + 1)
}

// Expands compact integer bytes into u32.
fn unzip_u32(bytes: &[u8]) -> u32 {
    let mut out = [0u8; 4];
    let len = bytes.len().min(4);
    out[..len].copy_from_slice(&bytes[..len]);
    BinaryReader::u32_le(&out)
}

// Expands compact integer bytes into u64.
fn unzip_u64(bytes: &[u8]) -> u64 {
    let mut out = [0u8; 8];
    let len = bytes.len().min(8);
    out[..len].copy_from_slice(&bytes[..len]);
    BinaryReader::u64_le(&out)
}

// Expands compact integer bytes into i64.
fn unzip_i64(bytes: &[u8]) -> i64 {
    let mut out = if bytes.last().is_some_and(|byte| byte & 0x80 != 0) {
        [0xff; 8]
    } else {
        [0u8; 8]
    };
    let len = bytes.len().min(8);
    out[..len].copy_from_slice(&bytes[..len]);
    BinaryReader::i64_le(&out)
}

// Performs checked arithmetic for add.
fn checked_add(left: usize, right: usize) -> Result<usize> {
    left.checked_add(right)
        .ok_or_else(|| PsbError::new("offset overflow"))
}

// Performs checked arithmetic for mul.
fn checked_mul(left: usize, right: usize) -> Result<usize> {
    left.checked_mul(right)
        .ok_or_else(|| PsbError::new("length overflow"))
}

impl PsbError {
    // Creates a new value for this type.
    fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
        }
    }
}

impl From<std::io::Error> for PsbError {
    // Converts a binary reader error into this parser error.
    fn from(value: std::io::Error) -> Self {
        Self::new(value.to_string())
    }
}

impl std::fmt::Display for PsbError {
    // Formats this value for human-readable output.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for PsbError {}
