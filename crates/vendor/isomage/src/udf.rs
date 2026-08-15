type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use crate::tree::TreeNode;

const SECTOR_SIZE: u64 = 2048;

#[derive(Debug, Clone, Copy)]
struct ExtentAd {
    length: u32,
    location: u32,
}

#[allow(dead_code)]
#[derive(Debug, Clone, Copy)]
struct LongAd {
    length: u32,
    location: u32,
    partition: u16,
}

#[derive(Debug, Clone)]
struct PartitionInfo {
    number: u16,
    start_sector: u64,
}

#[derive(Debug, Clone)]
struct MetadataPartitionInfo {
    file_location: u32,
    partition_ref: u16,
}

/// Represents a file's allocation — possibly spanning multiple extents.
#[derive(Debug, Clone)]
struct FileAllocation {
    extents: Vec<ExtentAd>,
    total_length: u64,
    /// For inline data (ad_type 3), the raw data is stored here.
    inline_data: Option<Vec<u8>>,
}

fn read_extent_ad(buffer: &[u8]) -> ExtentAd {
    ExtentAd {
        length: u32::from_le_bytes([buffer[0], buffer[1], buffer[2], buffer[3]]),
        location: u32::from_le_bytes([buffer[4], buffer[5], buffer[6], buffer[7]]),
    }
}

fn read_long_ad(buffer: &[u8]) -> LongAd {
    LongAd {
        length: u32::from_le_bytes([buffer[0], buffer[1], buffer[2], buffer[3]]),
        location: u32::from_le_bytes([buffer[4], buffer[5], buffer[6], buffer[7]]),
        partition: u16::from_le_bytes([buffer[8], buffer[9]]),
    }
}

pub fn parse_udf(file: &mut File) -> Result<TreeNode> {
    parse_udf_verbose(file, false)
}

pub fn parse_udf_verbose(file: &mut File, verbose: bool) -> Result<TreeNode> {
    // Check for UDF markers in the Volume Recognition Sequence (sectors 16-31)
    let mut found_udf_marker = false;
    if verbose { println!("Scanning sectors 16-31 for UDF Volume Recognition Sequence..."); }
    for sector in 16..32 {
        if file.seek(SeekFrom::Start(sector * SECTOR_SIZE)).is_err() {
            continue;
        }
        let mut buffer = [0u8; 16];
        if file.read_exact(&mut buffer).is_err() {
            continue;
        }

        let id = &buffer[1..6];
        if id == b"NSR02" || id == b"NSR03" || id == b"BEA01" || id == b"TEA01" {
            if verbose { println!("  Found UDF marker '{:?}' at sector {}", String::from_utf8_lossy(id), sector); }
            found_udf_marker = true;
            break;
        }
    }

    if !found_udf_marker {
        return Err("Not a valid UDF filesystem (no VRS markers found)".into());
    }

    // Try to find the Anchor Volume Descriptor Pointer (AVDP) at sector 256
    if verbose { println!("Looking for Anchor Volume Descriptor Pointer at sector 256..."); }
    file.seek(SeekFrom::Start(256 * SECTOR_SIZE))?;
    let mut avdp_buffer = [0u8; 512];
    file.read_exact(&mut avdp_buffer)?;

    let tag_id = u16::from_le_bytes([avdp_buffer[0], avdp_buffer[1]]);
    if tag_id != 2 {
        if verbose { println!("  AVDP not found at sector 256 (tag id: {})", tag_id); }
        return Err("UDF detected but AVDP not found at sector 256.".into());
    }

    let main_vds_extent = read_extent_ad(&avdp_buffer[16..24]);
    if verbose { println!("  Found AVDP. Main VDS at sector {}, length {}", main_vds_extent.location, main_vds_extent.length); }

    // Collect partition info and parse LVD
    let mut partitions: Vec<PartitionInfo> = Vec::new();
    let mut root_fsd_long_ad = None;
    let mut metadata_partition: Option<MetadataPartitionInfo> = None;

    let mut sector = main_vds_extent.location as u64;
    let end_sector = sector + (main_vds_extent.length as u64 + SECTOR_SIZE - 1) / SECTOR_SIZE;

    if verbose { println!("Parsing Main Volume Descriptor Sequence (sectors {} to {})...", sector, end_sector); }
    while sector < end_sector {
        file.seek(SeekFrom::Start(sector * SECTOR_SIZE))?;
        let mut vds_buffer = vec![0u8; SECTOR_SIZE as usize];
        file.read_exact(&mut vds_buffer)?;

        let vds_tag_id = u16::from_le_bytes([vds_buffer[0], vds_buffer[1]]);

        match vds_tag_id {
            5 => { // Partition Descriptor
                let part_num = u16::from_le_bytes([vds_buffer[22], vds_buffer[23]]);
                let part_start = u32::from_le_bytes([vds_buffer[188], vds_buffer[189], vds_buffer[190], vds_buffer[191]]) as u64;
                if verbose { println!("  Found Partition Descriptor #{}: starts at sector {}", part_num, part_start); }
                partitions.push(PartitionInfo { number: part_num, start_sector: part_start });
            }
            6 => { // Logical Volume Descriptor
                // FSD location at offset 248
                root_fsd_long_ad = Some(read_long_ad(&vds_buffer[248..264]));
                if verbose {
                    let ad = root_fsd_long_ad.unwrap();
                    println!("  Found Logical Volume Descriptor. FSD at location {} in partition {}", ad.location, ad.partition);
                }

                // Parse partition maps to find metadata partition
                let map_table_length = u32::from_le_bytes([vds_buffer[264], vds_buffer[265], vds_buffer[266], vds_buffer[267]]) as usize;
                let num_partition_maps = u32::from_le_bytes([vds_buffer[268], vds_buffer[269], vds_buffer[270], vds_buffer[271]]);
                if verbose { println!("    {} partition maps, table length {} bytes", num_partition_maps, map_table_length); }

                // Partition maps start at offset 440
                let mut map_offset = 440usize;
                for map_idx in 0..num_partition_maps {
                    if map_offset >= vds_buffer.len() { break; }
                    let map_type = vds_buffer[map_offset];
                    let map_length = vds_buffer[map_offset + 1] as usize;

                    if verbose { println!("    Partition map {}: type {}, length {}", map_idx, map_type, map_length); }

                    if map_type == 2 && map_length >= 64 {
                        let id_string = &vds_buffer[map_offset + 5..map_offset + 28];

                        if verbose {
                            let id_printable: String = id_string.iter()
                                .take_while(|&&b| b != 0)
                                .map(|&b| if b >= 0x20 && b < 0x7f { b as char } else { '.' })
                                .collect();
                            println!("      Type 2 identifier: '{}'", id_printable);
                        }

                        if id_string.starts_with(b"*UDF Metadata Partition") {
                            let meta_part_ref = u16::from_le_bytes([vds_buffer[map_offset + 38], vds_buffer[map_offset + 39]]);
                            let meta_file_loc = u32::from_le_bytes([vds_buffer[map_offset + 40], vds_buffer[map_offset + 41],
                                                                    vds_buffer[map_offset + 42], vds_buffer[map_offset + 43]]);
                            if verbose {
                                println!("      Metadata Partition: file at location {} in partition {}", meta_file_loc, meta_part_ref);
                            }
                            metadata_partition = Some(MetadataPartitionInfo {
                                file_location: meta_file_loc,
                                partition_ref: meta_part_ref,
                            });
                        }
                    }

                    map_offset += map_length;
                }
            }
            8 => {
                if verbose { println!("  Found Terminating Descriptor at sector {}", sector); }
                break;
            }
            _ => {}
        }
        sector += 1;
    }

    let fsd_long_ad = root_fsd_long_ad.ok_or("Failed to find File Set Descriptor location in LVD")?;

    // Find the partition that the FSD references
    let fsd_partition_ref = fsd_long_ad.partition;

    // Determine where to read the FSD from
    let (fsd_sector, partition_start) = if let Some(ref meta_info) = metadata_partition {
        if verbose { println!("FSD is in metadata partition, reading via metadata file..."); }

        let meta_phys_partition = partitions.iter()
            .find(|p| p.number == meta_info.partition_ref)
            .ok_or("Cannot find physical partition for metadata file")?;

        let meta_fe_sector = meta_phys_partition.start_sector + meta_info.file_location as u64;
        if verbose { println!("  Metadata File Entry at sector {}", meta_fe_sector); }

        file.seek(SeekFrom::Start(meta_fe_sector * SECTOR_SIZE))?;
        let mut meta_fe_buffer = vec![0u8; SECTOR_SIZE as usize];
        file.read_exact(&mut meta_fe_buffer)?;

        let meta_tag_id = u16::from_le_bytes([meta_fe_buffer[0], meta_fe_buffer[1]]);
        if verbose { println!("  Metadata FE tag: {}", meta_tag_id); }

        // Read first extent of metadata file
        let meta_alloc = get_file_allocation(&meta_fe_buffer)?;
        let first_extent = meta_alloc.extents.first()
            .ok_or("Metadata file has no allocation extents")?;

        if verbose { println!("  Metadata file extent: location {}, length {}", first_extent.location, first_extent.length); }

        let metadata_data_sector = meta_phys_partition.start_sector + first_extent.location as u64;
        let fsd_offset_in_metadata = fsd_long_ad.location as u64;

        (metadata_data_sector + fsd_offset_in_metadata, metadata_data_sector)
    } else {
        let partition = partitions.iter()
            .find(|p| p.number == fsd_partition_ref)
            .or_else(|| partitions.first())
            .ok_or("No partition found")?;

        (partition.start_sector + fsd_long_ad.location as u64, partition.start_sector)
    };

    if verbose { println!("Reading File Set Descriptor at sector {}...", fsd_sector); }
    file.seek(SeekFrom::Start(fsd_sector * SECTOR_SIZE))?;
    let mut fsd_buffer = [0u8; 512];
    file.read_exact(&mut fsd_buffer)?;

    let fsd_tag_id = u16::from_le_bytes([fsd_buffer[0], fsd_buffer[1]]);
    if fsd_tag_id != 256 {
        if verbose { println!("  Tag {} at expected FSD location, scanning nearby...", fsd_tag_id); }
        let mut found_fsd = false;
        for offset in 1..32 {
            file.seek(SeekFrom::Start((fsd_sector + offset) * SECTOR_SIZE))?;
            file.read_exact(&mut fsd_buffer)?;
            let tag = u16::from_le_bytes([fsd_buffer[0], fsd_buffer[1]]);
            if tag == 256 {
                if verbose { println!("  Found FSD at sector {} (offset +{})", fsd_sector + offset, offset); }
                found_fsd = true;
                break;
            }
        }
        if !found_fsd {
            return Err(format!("Invalid File Set Descriptor tag: expected 256, found {}", fsd_tag_id).into());
        }
    }

    let root_icb_long_ad = read_long_ad(&fsd_buffer[400..416]);
    if verbose { println!("  Found FSD. Root ICB at location {} in partition {}", root_icb_long_ad.location, root_icb_long_ad.partition); }

    let mut root_node = TreeNode::new_directory("/".to_string());
    if verbose { println!("Parsing root directory..."); }
    parse_directory(file, partition_start, &root_icb_long_ad, &mut root_node, verbose)?;

    root_node.calculate_directory_size();
    Ok(root_node)
}

/// Parse all allocation descriptors from a File Entry buffer, supporting multi-extent files.
fn get_file_allocation(fe_buffer: &[u8]) -> Result<FileAllocation> {
    let tag_id = u16::from_le_bytes([fe_buffer[0], fe_buffer[1]]);

    let (ad_length_offset, ea_length_offset, ad_data_offset_base) = match tag_id {
        261 => (172, 168, 176usize), // File Entry
        266 => (212, 208, 216usize), // Extended File Entry
        _ => return Err(format!("Unsupported ICB tag: {}", tag_id).into()),
    };

    // ICB tag flags (at offset 20-21 in ICB tag, which starts at offset 16)
    let icb_flags = u16::from_le_bytes([fe_buffer[18], fe_buffer[19]]);
    let ad_type = icb_flags & 0x07;

    let ea_length = u32::from_le_bytes([
        fe_buffer[ea_length_offset], fe_buffer[ea_length_offset + 1],
        fe_buffer[ea_length_offset + 2], fe_buffer[ea_length_offset + 3],
    ]) as usize;

    let ad_length = u32::from_le_bytes([
        fe_buffer[ad_length_offset], fe_buffer[ad_length_offset + 1],
        fe_buffer[ad_length_offset + 2], fe_buffer[ad_length_offset + 3],
    ]) as usize;

    let ad_offset = ad_data_offset_base + ea_length;

    let mut extents = Vec::new();
    let mut total_length: u64 = 0;
    let mut inline_data = None;

    match ad_type {
        0 => {
            // Short Allocation Descriptors (8 bytes each: length[4] + position[4])
            let mut pos = ad_offset;
            while pos + 8 <= fe_buffer.len() && pos < ad_offset + ad_length {
                let raw_length = u32::from_le_bytes([fe_buffer[pos], fe_buffer[pos+1], fe_buffer[pos+2], fe_buffer[pos+3]]);
                let extent_type = raw_length >> 30;
                let length = raw_length & 0x3FFFFFFF;
                let location = u32::from_le_bytes([fe_buffer[pos+4], fe_buffer[pos+5], fe_buffer[pos+6], fe_buffer[pos+7]]);

                if length == 0 { break; }
                if extent_type == 3 { break; } // Next extent of allocation descriptors — not yet supported

                // Type 0 = recorded and allocated, Type 1 = allocated but not recorded (sparse)
                if extent_type == 0 {
                    extents.push(ExtentAd { length, location });
                }
                total_length += length as u64;
                pos += 8;
            }
        }
        1 => {
            // Long Allocation Descriptors (16 bytes each)
            let mut pos = ad_offset;
            while pos + 16 <= fe_buffer.len() && pos < ad_offset + ad_length {
                let raw_length = u32::from_le_bytes([fe_buffer[pos], fe_buffer[pos+1], fe_buffer[pos+2], fe_buffer[pos+3]]);
                let extent_type = raw_length >> 30;
                let length = raw_length & 0x3FFFFFFF;
                let location = u32::from_le_bytes([fe_buffer[pos+4], fe_buffer[pos+5], fe_buffer[pos+6], fe_buffer[pos+7]]);

                if length == 0 { break; }
                if extent_type == 3 { break; }

                if extent_type == 0 {
                    extents.push(ExtentAd { length, location });
                }
                total_length += length as u64;
                pos += 16;
            }
        }
        3 => {
            // Inline data — embedded directly in the file entry at the AD area
            let end = (ad_offset + ad_length).min(fe_buffer.len());
            if ad_offset < end {
                inline_data = Some(fe_buffer[ad_offset..end].to_vec());
                total_length = (end - ad_offset) as u64;
            }
        }
        _ => {
            // Fallback: try reading a single extent at the expected offset
            if ad_offset + 8 <= fe_buffer.len() {
                let ext = read_extent_ad(&fe_buffer[ad_offset..ad_offset + 8]);
                if ext.length > 0 {
                    total_length = ext.length as u64;
                    extents.push(ext);
                }
            }
        }
    }

    if extents.is_empty() && inline_data.is_none() {
        return Err("No allocation extents found in file entry".into());
    }

    Ok(FileAllocation { extents, total_length, inline_data })
}

fn parse_directory(file: &mut File, partition_start: u64, icb_long_ad: &LongAd, parent_node: &mut TreeNode, verbose: bool) -> Result<()> {
    // Read the file entry to get allocation info
    file.seek(SeekFrom::Start((partition_start + icb_long_ad.location as u64) * SECTOR_SIZE))?;
    let mut fe_buffer = vec![0u8; SECTOR_SIZE as usize];
    file.read_exact(&mut fe_buffer)?;

    let alloc = get_file_allocation(&fe_buffer)?;

    if verbose {
        if alloc.inline_data.is_some() {
            println!("  Directory has inline data, {} bytes", alloc.total_length);
        } else {
            println!("  Directory has {} extent(s), total {} bytes", alloc.extents.len(), alloc.total_length);
        }
    }

    // Read directory data — either inline or from extents
    let buffer = if let Some(data) = alloc.inline_data {
        data
    } else {
        let mut buf = Vec::with_capacity(alloc.total_length as usize);
        for extent in &alloc.extents {
            file.seek(SeekFrom::Start((partition_start + extent.location as u64) * SECTOR_SIZE))?;
            let mut chunk = vec![0u8; extent.length as usize];
            file.read_exact(&mut chunk)?;
            buf.extend_from_slice(&chunk);
        }
        buf
    };

    let mut offset = 0;
    while offset < buffer.len() {
        if offset + 40 > buffer.len() { break; }

        let tag_id = u16::from_le_bytes([buffer[offset], buffer[offset+1]]);
        if tag_id == 0 {
            offset += 4;
            continue;
        }

        if tag_id != 257 { // File Identifier Descriptor
            if verbose { println!("    Warning: Expected FID (257) at offset {}, found {}", offset, tag_id); }
            break;
        }

        let file_characteristics = buffer[offset + 18];
        let length_of_fi = buffer[offset + 19] as usize;
        let icb = read_long_ad(&buffer[offset + 20..offset + 36]);
        let length_of_iu = u16::from_le_bytes([buffer[offset + 36], buffer[offset + 37]]) as usize;

        let name_offset = offset + 38 + length_of_iu;
        if name_offset + length_of_fi > buffer.len() {
             if verbose { println!("    Warning: FID name offset out of bounds at offset {}", offset); }
             break;
        }

        let name = if length_of_fi == 0 {
            String::new()
        } else {
            parse_udf_name(&buffer[name_offset..name_offset + length_of_fi])
        };

        let is_directory = (file_characteristics & 0x02) != 0;
        let is_deleted = (file_characteristics & 0x04) != 0;
        let is_parent = (file_characteristics & 0x08) != 0;

        if !is_deleted && !is_parent && !name.is_empty() {
            if verbose { println!("    Found {}: {}", if is_directory { "dir" } else { "file" }, name); }
            if is_directory {
                let mut dir_node = TreeNode::new_directory(name);
                if let Err(e) = parse_directory(file, partition_start, &icb, &mut dir_node, verbose) {
                    if verbose { println!("      Warning: Failed to parse subdirectory: {}", e); }
                }
                parent_node.add_child(dir_node);
            } else {
                match get_file_info(file, partition_start, &icb) {
                    Ok(alloc) => {
                        // Store location of first extent for extraction
                        let first = &alloc.extents[0];
                        let file_node = TreeNode::new_file_with_location(
                            name,
                            alloc.total_length,
                            (partition_start + first.location as u64) * SECTOR_SIZE,
                            alloc.total_length
                        );
                        parent_node.add_child(file_node);
                    }
                    Err(e) => {
                        if verbose { println!("      Warning: Failed to get file extent: {}", e); }
                        let file_node = TreeNode::new_file(name, 0);
                        parent_node.add_child(file_node);
                    }
                }
            }
        }

        // FIDs are padded to 4-byte boundaries
        let fid_length = 38 + length_of_iu + length_of_fi;
        offset += (fid_length + 3) & !3;
    }

    Ok(())
}

fn get_file_info(file: &mut File, partition_start: u64, icb_long_ad: &LongAd) -> Result<FileAllocation> {
    file.seek(SeekFrom::Start((partition_start + icb_long_ad.location as u64) * SECTOR_SIZE))?;
    let mut fe_buffer = vec![0u8; SECTOR_SIZE as usize];
    file.read_exact(&mut fe_buffer)?;

    get_file_allocation(&fe_buffer)
}

fn parse_udf_name(data: &[u8]) -> String {
    if data.is_empty() { return String::new(); }

    let compression_id = data[0];
    if compression_id == 8 {
        String::from_utf8_lossy(&data[1..]).to_string()
    } else if compression_id == 16 {
        let utf16_data: Vec<u16> = data[1..]
            .chunks_exact(2)
            .map(|chunk| u16::from_be_bytes([chunk[0], chunk[1]]))
            .collect();
        String::from_utf16_lossy(&utf16_data)
    } else {
        String::from_utf8_lossy(data).to_string()
    }
}
