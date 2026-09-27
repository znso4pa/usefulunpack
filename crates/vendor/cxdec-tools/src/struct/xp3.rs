//! KiriKiri XP3 archive reader.

use crate::crypto::hxv4_compute::{HxEntryInfo, read_hx_index};
use crate::io::{BinaryIo, BinaryReader};
use flate2::read::ZlibDecoder;
use std::collections::HashMap;
use std::fs::File;
use std::io::{Cursor, Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};

pub const MAGIC: &[u8; 11] = b"XP3\r\n \n\x1a\x8bg\x01";
const HEADER_SIZE: usize = 0x13;
const INDEX_UNCOMPRESSED: u8 = 0;
const INDEX_COMPRESSED: u8 = 1;
const CONTINUATION_MARKER: u32 = 0x80;
const SIG_FILE: u32 = 0x656c_6946;
const SIG_INFO: u32 = 0x6f66_6e69;
const SIG_SEGM: u32 = 0x6d67_6573;
const SIG_ADLR: u32 = 0x726c_6461;
const SIG_HNFN: u32 = 0x6e66_6e68;
const SIG_HXV4: u32 = 0x3476_7848;
const SEGMENT_RECORD_SIZE: u64 = 0x1c;

#[derive(Debug, Clone)]
pub struct Xp3Segment {
    pub is_compressed: bool,
    pub offset: u64,
    pub size: u64,
    pub packed_size: u64,
}

#[derive(Debug, Clone)]
pub struct Xp3Entry {
    pub name: String,
    pub is_encrypted: bool,
    pub is_packed: bool,
    pub offset: u64,
    pub size: u64,
    pub unpacked_size: u64,
    pub hash: u32,
    pub segments: Vec<Xp3Segment>,
    pub hx_info: Option<HxEntryInfo>,
}

pub struct Xp3Archive {
    file: File,
    pub path: PathBuf,
    pub base_offset: u64,
    pub entries: Vec<Xp3Entry>,
}

#[derive(Debug, Clone)]
pub struct Xp3HxIndexKey {
    pub key1: Vec<u8>,
    pub key2: Vec<u8>,
}

#[derive(Debug, Clone)]
pub struct Xp3HxDerivedIndexKeys {
    pub default: Xp3HxIndexKey,
    pub alternate: Xp3HxIndexKey,
}

pub struct Xp3HxOptions {
    pub index_key1: Option<Vec<u8>>,
    pub index_key2: Option<Vec<u8>>,
    pub index_key_dict: HashMap<String, Xp3HxIndexKey>,
    pub derived_index_keys: Option<Xp3HxDerivedIndexKeys>,
    pub names_file: Option<PathBuf>,
}

pub trait Xp3Cipher {
    // Returns whether encrypted.
    fn is_encrypted(&self, entry: &Xp3Entry) -> bool {
        entry.is_encrypted
    }

    // Decrypts entry.
    fn decrypt_entry(
        &mut self,
        entry: &Xp3Entry,
        offset: u64,
        data: &mut [u8],
    ) -> std::io::Result<()> {
        self.decrypt(entry.hash, offset, data)
    }

    // Handles decrypt behavior.
    fn decrypt(&mut self, hash: u32, offset: u64, data: &mut [u8]) -> std::io::Result<()>;
}

#[derive(Default)]
struct PartialEntry {
    name: Option<String>,
    is_encrypted: bool,
    is_packed: bool,
    size: u64,
    unpacked_size: u64,
    hash: u32,
    segments: Vec<Xp3Segment>,
}

#[derive(Default)]
struct FilenameMap {
    hash_map: std::collections::HashMap<u32, String>,
    md5_map: std::collections::HashMap<String, String>,
}

impl<F> Xp3Cipher for F
where
    F: FnMut(u32, u64, &mut [u8]) -> std::io::Result<()>,
{
    // Handles decrypt behavior.
    fn decrypt(&mut self, hash: u32, offset: u64, data: &mut [u8]) -> std::io::Result<()> {
        self(hash, offset, data)
    }
}

impl Xp3Archive {
    // Opens with HX.
    pub fn open_with_hx(path: &Path, hx: Xp3HxOptions) -> std::io::Result<Self> {
        Self::open_impl(path, Some(hx))
    }

    // Opens a plain (non-HX) archive.
    // [local patch] upstream only exposes open_with_hx; classic cxdec archives
    // (control block via TPM/XP3FILTER.TJS) need the plain index path.
    pub fn open(path: &Path) -> std::io::Result<Self> {
        Self::open_impl(path, None)
    }

    // Opens impl.
    fn open_impl(path: &Path, hx: Option<Xp3HxOptions>) -> std::io::Result<Self> {
        let mut file = File::open(path)?;
        let file_len = file.metadata()?.len();
        let base_offset =
            find_xp3_base(&mut file, file_len)?.ok_or_else(|| invalid("not an XP3 archive"))?;

        file.seek(SeekFrom::Start(base_offset))?;
        let mut magic = [0u8; 11];
        file.read_exact(&mut magic)?;
        if magic != *MAGIC {
            return Err(invalid("not an XP3 archive"));
        }
        let mut dir_offset = base_offset
            .checked_add(BinaryIo::read_i64_le_at(&mut file, base_offset + 0x0b)? as u64)
            .ok_or_else(|| invalid("XP3 index offset overflow"))?;
        if dir_offset < HEADER_SIZE as u64 || dir_offset >= file_len {
            return Err(invalid("XP3 index offset outside file"));
        }
        if BinaryIo::read_u32_le_at(&mut file, dir_offset)? == CONTINUATION_MARKER {
            dir_offset = base_offset
                .checked_add(BinaryIo::read_i64_le_at(&mut file, dir_offset + 9)? as u64)
                .ok_or_else(|| invalid("XP3 continuation offset overflow"))?;
            if dir_offset < HEADER_SIZE as u64 || dir_offset >= file_len {
                return Err(invalid("XP3 continuation offset outside file"));
            }
        }

        let index = read_index(&mut file, dir_offset)?;
        let archive_name = path
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or_default();
        let entries = parse_index(
            &mut file,
            &index,
            base_offset,
            file_len,
            hx.as_ref(),
            archive_name,
        )?;
        if entries.is_empty() {
            return Err(invalid("XP3 index has no files"));
        }
        Ok(Xp3Archive {
            file,
            path: path.to_path_buf(),
            base_offset,
            entries,
        })
    }

    // Reads entry.
    pub fn read_entry<C: Xp3Cipher>(
        &mut self,
        entry_index: usize,
        cipher: &mut C,
    ) -> std::io::Result<Vec<u8>> {
        let entry = self
            .entries
            .get(entry_index)
            .ok_or_else(|| invalid("XP3 entry index out of range"))?
            .clone();
        let mut out = Vec::with_capacity(entry.unpacked_size.min(usize::MAX as u64) as usize);
        let mut logical_offset = 0u64;
        for segment in &entry.segments {
            let mut data = vec![0u8; segment.packed_size as usize];
            self.file.seek(SeekFrom::Start(segment.offset))?;
            self.file.read_exact(&mut data)?;
            if segment.is_compressed {
                data = zlib(&data)?;
            }
            if data.len() as u64 != segment.size {
                return Err(invalid("XP3 segment unpacked size mismatch"));
            }
            if cipher.is_encrypted(&entry) {
                cipher.decrypt_entry(&entry, logical_offset, &mut data)?;
            }
            logical_offset += data.len() as u64;
            out.extend_from_slice(&data);
        }
        Ok(out)
    }

    // Reads entry prefix.
    pub fn read_entry_prefix<C: Xp3Cipher>(
        &mut self,
        entry_index: usize,
        max_len: usize,
        cipher: &mut C,
    ) -> std::io::Result<Vec<u8>> {
        let entry = self
            .entries
            .get(entry_index)
            .ok_or_else(|| invalid("XP3 entry index out of range"))?
            .clone();
        let mut out = Vec::with_capacity(max_len.min(entry.unpacked_size as usize));
        let mut logical_offset = 0u64;
        for segment in &entry.segments {
            if out.len() >= max_len {
                break;
            }

            let mut data = vec![0u8; segment.packed_size as usize];
            self.file.seek(SeekFrom::Start(segment.offset))?;
            self.file.read_exact(&mut data)?;
            if segment.is_compressed {
                data = zlib(&data)?;
            }
            if data.len() as u64 != segment.size {
                return Err(invalid("XP3 segment unpacked size mismatch"));
            }
            if cipher.is_encrypted(&entry) {
                cipher.decrypt_entry(&entry, logical_offset, &mut data)?;
            }
            logical_offset += data.len() as u64;

            let take = (max_len - out.len()).min(data.len());
            out.extend_from_slice(&data[..take]);
        }
        Ok(out)
    }
}

impl FilenameMap {
    // Handles add behavior.
    fn add(&mut self, hash: u32, filename: String) {
        self.hash_map
            .entry(hash)
            .or_insert_with(|| filename.clone());
        self.md5_map
            .insert(md5_utf16_lower_hex(&filename), filename);
    }

    // Handles get behavior.
    fn get(&self, hash: u32, name_or_md5: String) -> String {
        if let Some(name) = self.md5_map.get(&name_or_md5) {
            return name.clone();
        }
        if let Some(name) = self.hash_map.get(&hash) {
            return name.clone();
        }
        name_or_md5
    }
}

// Parses index.
fn parse_index(
    file: &mut File,
    index: &[u8],
    base_offset: u64,
    file_len: u64,
    hx: Option<&Xp3HxOptions>,
    archive_name: &str,
) -> std::io::Result<Vec<Xp3Entry>> {
    let mut cur = Cursor::new(index);
    let mut entries = Vec::new();
    let mut filename_map = FilenameMap::default();
    let mut hx_entry_info: Option<HashMap<String, HxEntryInfo>> = None;
    while (cur.position() as usize) < index.len() {
        let entry_start = cur.position();
        let signature = BinaryIo::read_u32_le(&mut cur)?;
        let entry_size = BinaryIo::read_i64_le(&mut cur)?;
        if entry_size < 0 {
            return Err(invalid("negative XP3 index entry size"));
        }
        let next_entry_pos = entry_start
            .checked_add(12)
            .and_then(|x| x.checked_add(entry_size as u64))
            .ok_or_else(|| invalid("XP3 index entry overflow"))?;
        if next_entry_pos as usize > index.len() {
            return Err(invalid("XP3 index entry exceeds index size"));
        }

        if signature == SIG_FILE {
            if let Some(entry) = parse_file_entry(
                &mut cur,
                next_entry_pos,
                base_offset,
                file_len,
                &filename_map,
                hx_entry_info.as_mut(),
            )? {
                entries.push(entry);
            }
        } else if signature == SIG_HXV4 {
            if let Some(options) = hx {
                hx_entry_info = read_hxv4_section(
                    file,
                    &mut cur,
                    next_entry_pos,
                    base_offset,
                    file_len,
                    options,
                    archive_name,
                )?
                .or(hx_entry_info);
            }
        } else if signature == SIG_HNFN || entry_size > 7 {
            parse_filename_map_entry(&mut cur, next_entry_pos, &mut filename_map)?;
        }
        cur.set_position(next_entry_pos);
    }
    Ok(entries)
}

// Parses file entry.
fn parse_file_entry(
    cur: &mut Cursor<&[u8]>,
    next_entry_pos: u64,
    base_offset: u64,
    file_len: u64,
    filename_map: &FilenameMap,
    hx_entry_info: Option<&mut HashMap<String, HxEntryInfo>>,
) -> std::io::Result<Option<Xp3Entry>> {
    let mut entry = PartialEntry::default();
    while cur.position() < next_entry_pos {
        let section = BinaryIo::read_u32_le(cur)?;
        let mut section_size = BinaryIo::read_i64_le(cur)?;
        if section_size < 0 {
            return Err(invalid("negative XP3 section size"));
        }
        let section_start = cur.position();
        let mut next_section_pos = section_start + section_size as u64;
        if next_section_pos > next_entry_pos {
            if section != SIG_INFO {
                break;
            }
            section_size = (next_entry_pos - section_start) as i64;
            next_section_pos = next_entry_pos;
        }

        match section {
            SIG_INFO => parse_info_section(cur, section_size as u64, &mut entry, filename_map)?,
            SIG_SEGM => {
                parse_segment_section(cur, section_size as u64, &mut entry, base_offset, file_len)?
            }
            SIG_ADLR if section_size == 4 => entry.hash = BinaryIo::read_u32_le(cur)?,
            _ => {}
        }
        cur.set_position(next_section_pos);
    }

    let mut name = match entry.name {
        Some(name) if !name.is_empty() && !entry.segments.is_empty() => name,
        _ => return Ok(None),
    };
    let hx_info = bind_hx_entry(&mut name, hx_entry_info);
    let offset = entry.segments.first().map(|x| x.offset).unwrap_or(0);
    Ok(Some(Xp3Entry {
        name,
        is_encrypted: entry.is_encrypted,
        is_packed: entry.is_packed,
        offset,
        size: entry.size,
        unpacked_size: entry.unpacked_size,
        hash: entry.hash,
        segments: entry.segments,
        hx_info,
    }))
}

// Handles bind HX entry behavior.
fn bind_hx_entry(
    name: &mut String,
    hx_entry_info: Option<&mut HashMap<String, HxEntryInfo>>,
) -> Option<HxEntryInfo> {
    let info = hx_entry_info?.remove(name)?;
    let mut resolved = String::new();
    if let Some(path) = info.path.as_deref().filter(|path| !path.is_empty()) {
        resolved.push_str(path);
        if !path.ends_with('/') && !path.ends_with('\\') {
            resolved.push('/');
        }
    }
    if let Some(real_name) = info.name.as_deref().filter(|name| !name.is_empty()) {
        resolved.push_str(real_name);
    } else {
        resolved.push_str(name);
    }
    if !resolved.is_empty() {
        *name = resolved;
    }
    Some(info)
}

// Reads HXv4 section.
fn read_hxv4_section(
    file: &mut File,
    cur: &mut Cursor<&[u8]>,
    next_entry_pos: u64,
    base_offset: u64,
    file_len: u64,
    options: &Xp3HxOptions,
    archive_name: &str,
) -> std::io::Result<Option<HashMap<String, HxEntryInfo>>> {
    if next_entry_pos.saturating_sub(cur.position()) < 14 {
        return Ok(None);
    }
    let raw_offset = BinaryIo::read_i64_le(cur)?;
    let size = BinaryIo::read_u32_le(cur)? as u64;
    let flags = BinaryIo::read_i16_le(cur)?;
    if raw_offset < 0 {
        return Ok(None);
    }
    let offset = base_offset
        .checked_add(raw_offset as u64)
        .ok_or_else(|| invalid("Hxv4 section offset overflow"))?;
    if offset > file_len || offset.saturating_add(size) > file_len {
        return Ok(None);
    }
    let (index_key1, index_key2) = if let Some(keys) = &options.derived_index_keys {
        let key = if flags & 1 == 0 {
            &keys.default
        } else {
            &keys.alternate
        };
        (key.key1.as_slice(), key.key2.as_slice())
    } else if let Some(key) = options.index_key_dict.get(archive_name) {
        (key.key1.as_slice(), key.key2.as_slice())
    } else {
        let (Some(index_key1), Some(index_key2)) =
            (options.index_key1.as_deref(), options.index_key2.as_deref())
        else {
            return Ok(None);
        };
        (index_key1, index_key2)
    };
    file.seek(SeekFrom::Start(offset))?;
    let mut data = vec![0u8; size as usize];
    file.read_exact(&mut data)?;
    read_hx_index(&data, index_key1, index_key2, options.names_file.as_deref()).map(Some)
}

// Parses info section.
fn parse_info_section(
    cur: &mut Cursor<&[u8]>,
    section_size: u64,
    entry: &mut PartialEntry,
    filename_map: &FilenameMap,
) -> std::io::Result<()> {
    if section_size < 22 {
        return Err(invalid("XP3 info section too small"));
    }
    entry.is_encrypted = BinaryIo::read_u32_le(cur)? != 0;
    let file_size = BinaryIo::read_i64_le(cur)?;
    let packed_size = BinaryIo::read_i64_le(cur)?;
    if file_size < 0 || packed_size < 0 {
        return Err(invalid("negative XP3 file size"));
    }
    entry.is_packed = file_size != packed_size;
    entry.size = packed_size as u64;
    entry.unpacked_size = file_size as u64;
    let name = read_utf16_name(cur)?;
    entry.name = Some(filename_map.get(entry.hash, name));
    Ok(())
}

// Parses segment section.
fn parse_segment_section(
    cur: &mut Cursor<&[u8]>,
    section_size: u64,
    entry: &mut PartialEntry,
    base_offset: u64,
    file_len: u64,
) -> std::io::Result<()> {
    let count = section_size / SEGMENT_RECORD_SIZE;
    for _ in 0..count {
        let compressed = BinaryIo::read_i32_le(cur)? != 0;
        let raw_offset = BinaryIo::read_i64_le(cur)?;
        let size = BinaryIo::read_i64_le(cur)?;
        let packed_size = BinaryIo::read_i64_le(cur)?;
        if raw_offset < 0 || size < 0 || packed_size < 0 {
            return Err(invalid("negative XP3 segment field"));
        }
        let offset = base_offset
            .checked_add(raw_offset as u64)
            .ok_or_else(|| invalid("XP3 segment offset overflow"))?;
        let packed_size = packed_size as u64;
        if offset > file_len || offset.saturating_add(packed_size) > file_len {
            return Err(invalid("XP3 segment outside file"));
        }
        entry.segments.push(Xp3Segment {
            is_compressed: compressed,
            offset,
            size: size as u64,
            packed_size,
        });
    }
    Ok(())
}

// Parses filename map entry.
fn parse_filename_map_entry(
    cur: &mut Cursor<&[u8]>,
    next_entry_pos: u64,
    filename_map: &mut FilenameMap,
) -> std::io::Result<()> {
    if next_entry_pos.saturating_sub(cur.position()) < 6 {
        return Ok(());
    }
    let hash = BinaryIo::read_u32_le(cur)?;
    let name_size = BinaryIo::read_i16_le(cur)?;
    if name_size <= 0 {
        return Ok(());
    }
    let bytes = name_size as u64 * 2;
    if cur.position().saturating_add(bytes) <= next_entry_pos {
        let name = read_utf16_chars(cur, name_size as usize)?;
        filename_map.add(hash, name);
    }
    Ok(())
}

// Reads index.
fn read_index(file: &mut File, dir_offset: u64) -> std::io::Result<Vec<u8>> {
    file.seek(SeekFrom::Start(dir_offset))?;
    let mut ty = [0u8; 1];
    file.read_exact(&mut ty)?;
    match ty[0] {
        INDEX_UNCOMPRESSED => {
            let size = BinaryIo::read_i64_le(file)?;
            if size < 0 {
                return Err(invalid("negative XP3 index size"));
            }
            let mut data = vec![0u8; size as usize];
            file.read_exact(&mut data)?;
            Ok(data)
        }
        INDEX_COMPRESSED => {
            let packed_size = BinaryIo::read_i64_le(file)?;
            let unpacked_size = BinaryIo::read_i64_le(file)?;
            if packed_size < 0 || unpacked_size < 0 {
                return Err(invalid("negative XP3 compressed index size"));
            }
            let mut data = vec![0u8; packed_size as usize];
            file.read_exact(&mut data)?;
            let out = zlib(&data)?;
            if out.len() != unpacked_size as usize {
                return Err(invalid("XP3 index unpacked size mismatch"));
            }
            Ok(out)
        }
        _ => Err(invalid("unknown XP3 index type")),
    }
}

// Finds XP3 base.
fn find_xp3_base(file: &mut File, file_len: u64) -> std::io::Result<Option<u64>> {
    file.seek(SeekFrom::Start(0))?;
    let mut head = [0u8; 11];
    let n = file.read(&mut head)?;
    if n == MAGIC.len() && head == *MAGIC {
        return Ok(Some(0));
    }
    if file_len < MAGIC.len() as u64 {
        return Ok(None);
    }
    file.seek(SeekFrom::Start(0))?;
    let mut data = Vec::new();
    file.read_to_end(&mut data)?;
    Ok(data
        .windows(MAGIC.len())
        .position(|window| window == MAGIC)
        .map(|pos| pos as u64))
}

// Reads UTF-16 name.
fn read_utf16_name<R: Read>(reader: &mut R) -> std::io::Result<String> {
    let name_size = BinaryIo::read_i16_le(reader)?;
    if name_size <= 0 || name_size > 0x100 {
        return Err(invalid("invalid XP3 filename length"));
    }
    read_utf16_chars(reader, name_size as usize)
}

// Reads UTF-16 chars.
fn read_utf16_chars<R: Read>(reader: &mut R, len: usize) -> std::io::Result<String> {
    let chars = BinaryIo::read_utf16_units_le(reader, len)?;
    String::from_utf16(&chars).map_err(|_| invalid("invalid UTF-16 filename"))
}

// Inflates a zlib-compressed byte buffer.
fn zlib(data: &[u8]) -> std::io::Result<Vec<u8>> {
    let mut decoder = ZlibDecoder::new(data);
    let mut out = Vec::new();
    decoder.read_to_end(&mut out).unwrap();
    Ok(out)
}

// Converts an archive component into a safe output path component.
pub fn sanitize(name: &str) -> PathBuf {
    let mut out = PathBuf::new();
    for comp in name.split(['/', '\\']) {
        match comp {
            "" | "." | ".." => continue,
            c => out.push(c),
        }
    }
    out
}

// Handles MD5 UTF-16 lower hex behavior.
fn md5_utf16_lower_hex(text: &str) -> String {
    let mut bytes = Vec::with_capacity(text.len() * 2);
    for c in text.to_lowercase().encode_utf16() {
        bytes.extend_from_slice(&c.to_le_bytes());
    }
    let digest = md5(&bytes);
    digest.iter().map(|b| format!("{b:02x}")).collect()
}

// Creates an invalid-data I/O error with the provided message.
fn invalid(msg: &str) -> std::io::Error {
    std::io::Error::new(std::io::ErrorKind::InvalidData, msg)
}

// Computes the MD5 digest used by legacy XP3 filename mapping.
fn md5(msg: &[u8]) -> [u8; 16] {
    const S: [u32; 64] = [
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 5, 9, 14, 20, 5, 9, 14, 20, 5,
        9, 14, 20, 5, 9, 14, 20, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 6, 10,
        15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    ];
    const K: [u32; 64] = [
        0xd76a_a478,
        0xe8c7_b756,
        0x2420_70db,
        0xc1bd_ceee,
        0xf57c_0faf,
        0x4787_c62a,
        0xa830_4613,
        0xfd46_9501,
        0x6980_98d8,
        0x8b44_f7af,
        0xffff_5bb1,
        0x895c_d7be,
        0x6b90_1122,
        0xfd98_7193,
        0xa679_438e,
        0x49b4_0821,
        0xf61e_2562,
        0xc040_b340,
        0x265e_5a51,
        0xe9b6_c7aa,
        0xd62f_105d,
        0x0244_1453,
        0xd8a1_e681,
        0xe7d3_fbc8,
        0x21e1_cde6,
        0xc337_07d6,
        0xf4d5_0d87,
        0x455a_14ed,
        0xa9e3_e905,
        0xfcef_a3f8,
        0x676f_02d9,
        0x8d2a_4c8a,
        0xfffa_3942,
        0x8771_f681,
        0x6d9d_6122,
        0xfde5_380c,
        0xa4be_ea44,
        0x4bde_cfa9,
        0xf6bb_4b60,
        0xbebf_bc70,
        0x289b_7ec6,
        0xeaa1_27fa,
        0xd4ef_3085,
        0x0488_1d05,
        0xd9d4_d039,
        0xe6db_99e5,
        0x1fa2_7cf8,
        0xc4ac_5665,
        0xf429_2244,
        0x432a_ff97,
        0xab94_23a7,
        0xfc93_a039,
        0x655b_59c3,
        0x8f0c_cc92,
        0xffef_f47d,
        0x8584_5dd1,
        0x6fa8_7e4f,
        0xfe2c_e6e0,
        0xa301_4314,
        0x4e08_11a1,
        0xf753_7e82,
        0xbd3a_f235,
        0x2ad7_d2bb,
        0xeb86_d391,
    ];
    let (mut a0, mut b0, mut c0, mut d0) = (
        0x6745_2301u32,
        0xefcd_ab89u32,
        0x98ba_dcfeu32,
        0x1032_5476u32,
    );
    let mut data = msg.to_vec();
    let bitlen = (msg.len() as u64).wrapping_mul(8);
    data.push(0x80);
    while data.len() % 64 != 56 {
        data.push(0);
    }
    data.extend_from_slice(&bitlen.to_le_bytes());
    for chunk in data.chunks_exact(64) {
        let mut m = [0u32; 16];
        for i in 0..16 {
            m[i] = BinaryReader::u32_le(&chunk[i * 4..i * 4 + 4]);
        }
        let (mut a, mut b, mut c, mut d) = (a0, b0, c0, d0);
        for i in 0..64 {
            let (f, g) = match i {
                0..=15 => ((b & c) | (!b & d), i),
                16..=31 => ((d & b) | (!d & c), (5 * i + 1) % 16),
                32..=47 => (b ^ c ^ d, (3 * i + 5) % 16),
                _ => (c ^ (b | !d), (7 * i) % 16),
            };
            let f = f.wrapping_add(a).wrapping_add(K[i]).wrapping_add(m[g]);
            a = d;
            d = c;
            c = b;
            b = b.wrapping_add(f.rotate_left(S[i]));
        }
        a0 = a0.wrapping_add(a);
        b0 = b0.wrapping_add(b);
        c0 = c0.wrapping_add(c);
        d0 = d0.wrapping_add(d);
    }
    let mut out = [0u8; 16];
    out[0..4].copy_from_slice(&a0.to_le_bytes());
    out[4..8].copy_from_slice(&b0.to_le_bytes());
    out[8..12].copy_from_slice(&c0.to_le_bytes());
    out[12..16].copy_from_slice(&d0.to_le_bytes());
    out
}
