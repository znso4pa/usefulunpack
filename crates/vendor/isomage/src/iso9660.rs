type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use crate::tree::TreeNode;

const SECTOR_SIZE: u64 = 2048;
const PRIMARY_VOLUME_DESCRIPTOR_SECTOR: u64 = 16;

#[derive(Debug, Clone)]
pub struct DirectoryRecord {
    pub extent_location: u32,
    pub data_length: u32,
    pub is_directory: bool,
    pub filename: String,
}

#[derive(Debug, Clone, Copy, PartialEq)]
enum VolumeDescriptorType {
    Primary,
    Joliet,
}

pub fn parse_iso9660(file: &mut File) -> Result<TreeNode> {
    parse_iso9660_verbose(file, false)
}

pub fn parse_iso9660_verbose(file: &mut File, verbose: bool) -> Result<TreeNode> {
    // Scan all volume descriptors to find Primary and Joliet
    let mut primary_vd: Option<Vec<u8>> = None;
    let mut joliet_vd: Option<Vec<u8>> = None;

    let mut sector = PRIMARY_VOLUME_DESCRIPTOR_SECTOR;
    loop {
        file.seek(SeekFrom::Start(sector * SECTOR_SIZE))?;
        let mut buffer = vec![0u8; SECTOR_SIZE as usize];
        if file.read_exact(&mut buffer).is_err() {
            break;
        }

        // Check for ISO 9660 signature
        if &buffer[1..6] != b"CD001" {
            if sector == PRIMARY_VOLUME_DESCRIPTOR_SECTOR {
                if verbose {
                    println!("  ISO 9660 signature 'CD001' not found at sector {}. Found: {:?}",
                             sector, String::from_utf8_lossy(&buffer[1..6]));
                }
                return Err("Not a valid ISO 9660 filesystem".into());
            }
            break;
        }

        let vd_type = buffer[0];
        match vd_type {
            1 => {
                if verbose { println!("  Found Primary Volume Descriptor at sector {}", sector); }
                primary_vd = Some(buffer);
            }
            2 => {
                // Supplementary Volume Descriptor — check if Joliet
                // Joliet is indicated by escape sequences in bytes 88-90
                let escape = &buffer[88..91];
                if escape == b"%/@" || escape == b"%/C" || escape == b"%/E" {
                    if verbose { println!("  Found Joliet Volume Descriptor at sector {}", sector); }
                    joliet_vd = Some(buffer);
                }
            }
            255 => {
                if verbose { println!("  Volume Descriptor Set Terminator at sector {}", sector); }
                break;
            }
            _ => {}
        }
        sector += 1;
    }

    // Prefer Joliet (Unicode filenames) over Primary
    let (buffer, vd_type) = if let Some(buf) = joliet_vd {
        (buf, VolumeDescriptorType::Joliet)
    } else if let Some(buf) = primary_vd {
        (buf, VolumeDescriptorType::Primary)
    } else {
        return Err("Not a valid ISO 9660 filesystem".into());
    };

    if verbose {
        println!("  Using {} Volume Descriptor",
            if vd_type == VolumeDescriptorType::Joliet { "Joliet" } else { "Primary" });
    }

    // Parse root directory record (starts at offset 156)
    let root_record = parse_directory_record(&buffer[156..], vd_type)?;
    if verbose { println!("  Root directory at sector {}, size {} bytes", root_record.extent_location, root_record.data_length); }

    // Check for Rock Ridge (we'll detect it when parsing the root directory)
    let mut root_node = TreeNode::new_directory("/".to_string());
    let use_rock_ridge = if vd_type == VolumeDescriptorType::Primary {
        detect_rock_ridge(file, &root_record)?
    } else {
        false
    };
    if verbose && use_rock_ridge { println!("  Rock Ridge extensions detected"); }

    parse_directory(file, &root_record, &mut root_node, vd_type, use_rock_ridge, verbose, 0)?;

    root_node.calculate_directory_size();
    Ok(root_node)
}

fn detect_rock_ridge(file: &mut File, dir_record: &DirectoryRecord) -> Result<bool> {
    file.seek(SeekFrom::Start(dir_record.extent_location as u64 * SECTOR_SIZE))?;
    let mut buffer = vec![0u8; dir_record.data_length.min(4096) as usize];
    file.read_exact(&mut buffer)?;

    // Look at the first directory record's system use area for Rock Ridge signatures
    if buffer.len() < 34 {
        return Ok(false);
    }
    let record_length = buffer[0] as usize;
    let filename_length = buffer[32] as usize;
    // System use area starts after filename + padding
    let su_start = 33 + filename_length + ((filename_length + 1) % 2);
    if su_start + 7 <= record_length && record_length <= buffer.len() {
        // Check for "SP" (SUSP indicator) or "RR" signature
        let sig = &buffer[su_start..su_start + 2];
        if sig == b"SP" || sig == b"RR" {
            return Ok(true);
        }
        // Also check for "NM" (alternate name) or "PX" (POSIX attributes)
        if sig == b"NM" || sig == b"PX" {
            return Ok(true);
        }
    }
    Ok(false)
}

fn parse_directory_record(data: &[u8], vd_type: VolumeDescriptorType) -> Result<DirectoryRecord> {
    if data.len() < 33 {
        return Err("Directory record too short".into());
    }

    let length = data[0];
    if length == 0 {
        return Err("Zero-length directory record".into());
    }

    let extent_location = u32::from_le_bytes([data[2], data[3], data[4], data[5]]);
    let data_length = u32::from_le_bytes([data[10], data[11], data[12], data[13]]);
    let file_flags = data[25];
    let filename_length = data[32] as usize;

    let is_directory = (file_flags & 0x02) != 0;

    let filename = if filename_length == 0 {
        ".".to_string()
    } else if filename_length == 1 && data[33] == 0 {
        ".".to_string()
    } else if filename_length == 1 && data[33] == 1 {
        "..".to_string()
    } else if vd_type == VolumeDescriptorType::Joliet {
        // Joliet uses UCS-2 big-endian encoding
        let utf16_data: Vec<u16> = data[33..33 + filename_length]
            .chunks_exact(2)
            .map(|chunk| u16::from_be_bytes([chunk[0], chunk[1]]))
            .collect();
        let raw_name = String::from_utf16_lossy(&utf16_data);
        // Remove ISO 9660 version suffix (;1, ;2, etc.)
        if let Some(semicolon_pos) = raw_name.find(';') {
            raw_name[..semicolon_pos].to_string()
        } else {
            raw_name
        }
    } else {
        let raw_name = String::from_utf8_lossy(&data[33..33 + filename_length]);
        // Remove ISO 9660 version suffix (;1, ;2, etc.) and trailing periods
        let cleaned_name = if let Some(semicolon_pos) = raw_name.find(';') {
            &raw_name[..semicolon_pos]
        } else {
            &raw_name
        };
        cleaned_name.trim_end_matches('.').to_string()
    };

    Ok(DirectoryRecord {
        extent_location,
        data_length,
        is_directory,
        filename,
    })
}

fn extract_rock_ridge_name(data: &[u8], record_length: usize, filename_length: usize) -> Option<String> {
    // System use area starts after the filename + padding byte for even alignment
    let su_start = 33 + filename_length + ((filename_length + 1) % 2);
    if su_start >= record_length {
        return None;
    }

    let su_area = &data[su_start..record_length];
    let mut offset = 0;
    let mut name_parts: Vec<u8> = Vec::new();

    while offset + 4 <= su_area.len() {
        let sig = &su_area[offset..offset + 2];
        let entry_len = su_area[offset + 2] as usize;
        if entry_len < 4 || offset + entry_len > su_area.len() {
            break;
        }

        if sig == b"NM" {
            // Rock Ridge Alternate Name entry
            // byte 3 = version, byte 4 = flags
            let flags = su_area[offset + 4];
            if flags & 0x02 != 0 {
                // CURRENT (.) - skip
            } else if flags & 0x04 != 0 {
                // PARENT (..) - skip
            } else {
                name_parts.extend_from_slice(&su_area[offset + 5..offset + entry_len]);
            }
        }

        offset += entry_len;
    }

    if name_parts.is_empty() {
        None
    } else {
        Some(String::from_utf8_lossy(&name_parts).to_string())
    }
}

fn parse_directory(file: &mut File, dir_record: &DirectoryRecord, parent_node: &mut TreeNode,
                   vd_type: VolumeDescriptorType, use_rock_ridge: bool, verbose: bool,
                   depth: usize) -> Result<()> {
    if !dir_record.is_directory || dir_record.data_length == 0 {
        return Ok(());
    }
    // Cap recursion depth so a crafted deeply-nested (or cyclic) directory
    // tree can't overflow the stack (uncatchable SIGSEGV).
    if depth > 128 {
        return Err("directory nesting too deep (possible cycle)".into());
    }
    // Cap the directory buffer so a huge declared data_length can't drive an
    // unbounded allocation (OOM abort).
    const MAX_DIR_DATA: u64 = 64 * 1024 * 1024;
    if dir_record.data_length as u64 > MAX_DIR_DATA {
        return Err(format!("directory data_length {} exceeds {}", dir_record.data_length, MAX_DIR_DATA).into());
    }

    file.seek(SeekFrom::Start(dir_record.extent_location as u64 * SECTOR_SIZE))?;

    let mut buffer = vec![0u8; dir_record.data_length as usize];
    file.read_exact(&mut buffer)?;

    let mut offset = 0;
    while offset < buffer.len() {
        if buffer[offset] == 0 {
            // Skip to next sector boundary (padding at end of sector)
            let next_sector = (offset / SECTOR_SIZE as usize + 1) * SECTOR_SIZE as usize;
            if next_sector <= offset {
                offset += 1;
            } else {
                offset = next_sector;
            }
            continue;
        }

        let record_length = buffer[offset] as usize;
        if record_length == 0 || offset + record_length > buffer.len() {
            break;
        }

        if let Ok(mut record) = parse_directory_record(&buffer[offset..], vd_type) {
            // Try Rock Ridge alternate name
            if use_rock_ridge && vd_type == VolumeDescriptorType::Primary
                && record.filename != "." && record.filename != ".."
            {
                let filename_length = buffer[offset + 32] as usize;
                if let Some(rr_name) = extract_rock_ridge_name(&buffer[offset..offset + record_length], record_length, filename_length) {
                    record.filename = rr_name;
                }
            }

            // Skip "." and ".." entries
            if record.filename != "." && record.filename != ".." {
                if verbose { println!("    Found {}: {}", if record.is_directory { "dir" } else { "file" }, record.filename); }
                if record.is_directory {
                    let mut dir_node = TreeNode::new_directory(record.filename.clone());
                    parse_directory(file, &record, &mut dir_node, vd_type, use_rock_ridge, verbose, depth + 1)?;
                    parent_node.add_child(dir_node);
                } else {
                    let file_node = TreeNode::new_file_with_location(
                        record.filename.clone(),
                        record.data_length as u64,
                        record.extent_location as u64 * SECTOR_SIZE,
                        record.data_length as u64
                    );
                    parent_node.add_child(file_node);
                }
            }
        }

        offset += record_length;
    }

    Ok(())
}
