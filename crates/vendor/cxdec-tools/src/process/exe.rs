//! EXE resource discovery and extraction.

use crate::crypto::exe_resource::{
    BOOTSTRAP_RESOURCE_NAME, EXE_RESOURCE_SALT_SIZE, STARTUP_RESOURCE_NAME, decrypt_resource,
    startup_filter_path,
};
use crate::io::BinaryReader;
use crate::r#struct::tjs::load_tjs2_bytecode;
use crate::r#struct::xp3::sanitize;
use flate2::read::ZlibDecoder;
use pelite::resources::Name;
use pelite::{FileMap, PeFile};
use std::io::Read;
use std::path::{Path, PathBuf};
use tracing::info;

#[derive(Debug, Clone)]
pub struct ExeEmbeddedResources {
    pub startup_tjs: Vec<u8>,
    pub bootstrap: Vec<u8>,
}

#[derive(Debug, Clone)]
pub struct DetectedExeResources {
    pub exe: PathBuf,
    pub resources: ExeEmbeddedResources,
}

// Detects game EXE resources.
pub fn detect_game_exe_resources(
    input: &Path,
) -> Result<DetectedExeResources, Box<dyn std::error::Error>> {
    let input_dir = if input.is_file() {
        input
            .parent()
            .ok_or_else(|| format!("input file has no parent directory: {}", input.display()))?
    } else {
        input
    };
    let candidates = exe_candidates(input_dir)?;
    if candidates.is_empty() {
        return Err(format!("no EXE files found in {}", input_dir.display()).into());
    }

    let candidate = candidates
        .into_iter()
        .next()
        .ok_or_else(|| format!("no EXE files found in {}", input_dir.display()))?;
    info!(exe = %candidate.display(), "selected game EXE");
    let resources = read_exe_embedded_resources(&candidate)?;
    Ok(DetectedExeResources {
        exe: candidate,
        resources,
    })
}

// Reads encrypted EXE embedded resources.
pub fn read_exe_embedded_resources(
    exe: &Path,
) -> Result<ExeEmbeddedResources, Box<dyn std::error::Error>> {
    let map = step(format!("open EXE {}", exe.display()), || FileMap::open(exe))?;
    let pe = step("parse EXE PE image", || PeFile::from_bytes(&map))?;
    let salt_ref = step("scan EXE resource salt pointer pattern", || {
        find_resource_salt_ref(map.as_ref(), &pe)
    })?;
    let salt = step(
        format!(
            "read EXE resource salt at VA 0x{:08X} / RVA 0x{:08X}, size 0x{EXE_RESOURCE_SALT_SIZE:X}",
            salt_ref.va, salt_ref.rva
        ),
        || pe.derva_slice::<u8>(salt_ref.rva, EXE_RESOURCE_SALT_SIZE),
    )?;
    let resources = step("read PE resources", || pe.resources())?;
    let startup_resource = step(
        format!("find RCDATA/{STARTUP_RESOURCE_NAME} resource"),
        || {
            resources.find_resource(&[
                Name::Id(pelite::image::RT_RCDATA as u32),
                Name::Str(STARTUP_RESOURCE_NAME),
            ])
        },
    )?;
    let filter_path = step("read startup filter path from TEXT/127 resource", || {
        startup_filter_path(&resources)
    })?;
    let startup_tjs = decrypt_resource(startup_resource, &filter_path, salt);
    if !startup_tjs.starts_with(b"TJS2100\0") {
        return Err(format!(
            "decrypt RCDATA/{STARTUP_RESOURCE_NAME} with filter path {filter_path:?} and salt VA 0x{:08X} / RVA 0x{:08X}: expected TJS2100 bytecode, got {}",
            salt_ref.va,
            salt_ref.rva,
            hex_prefix(&startup_tjs, 16)
        )
        .into());
    }
    let bootstrap_filter_path = step(
        "read bootstrap filter path from STARTUP.TJS string pool",
        || bootstrap_filter_path_from_startup(&startup_tjs),
    )?;

    let bootstrap_resource = step(
        format!("find RCDATA/{BOOTSTRAP_RESOURCE_NAME} resource"),
        || {
            resources.find_resource(&[
                Name::Id(pelite::image::RT_RCDATA as u32),
                Name::Str(BOOTSTRAP_RESOURCE_NAME),
            ])
        },
    )?;
    let decrypted_bootstrap = decrypt_resource(bootstrap_resource, &bootstrap_filter_path, salt);
    let bootstrap = step(
        format!(
            "unpack decrypted RCDATA/{BOOTSTRAP_RESOURCE_NAME} zlib payload with filter path {bootstrap_filter_path:?}"
        ),
        || unpack_bootstrap_resource(&decrypted_bootstrap),
    )?;
    if !bootstrap.starts_with(b"MZ") {
        return Err(format!(
            "validate unpacked RCDATA/{BOOTSTRAP_RESOURCE_NAME}: expected PE MZ header, got {}",
            hex_prefix(&bootstrap, 16)
        )
        .into());
    }
    Ok(ExeEmbeddedResources {
        startup_tjs,
        bootstrap,
    })
}

#[derive(Debug, Clone, Copy)]
struct SaltRef {
    va: u32,
    rva: u32,
}

// Finds the salt buffer pointer written by the EXE resource bootstrap stub.
fn find_resource_salt_ref(data: &[u8], pe: &PeFile<'_>) -> Result<SaltRef, String> {
    let mut matches = Vec::new();
    let mut pos = 0usize;
    while pos + 0x21 <= data.len() {
        if data[pos] == 0x83
            && data[pos + 1] == 0x3d
            && data[pos + 6] == 0x00
            && data[pos + 7] == 0x0f
            && data[pos + 8] == 0x86
            && data[pos + 13] == 0xc7
            && data[pos + 14] == 0x05
            && data[pos + 23] == 0xc7
            && data[pos + 24] == 0x05
            && data[pos + 29..pos + 33] == [0x00, 0x20, 0x00, 0x00]
        {
            let salt_va = BinaryReader::u32_le_at(data, pos + 19);
            if let Some(rva) = va_to_rva(pe, salt_va)
                && pe.derva_slice::<u8>(rva, EXE_RESOURCE_SALT_SIZE).is_ok()
            {
                matches.push(SaltRef { va: salt_va, rva });
            }
        }
        pos += 1;
    }

    matches.sort_by_key(|value| (value.va, value.rva));
    matches.dedup_by_key(|value| (value.va, value.rva));
    match matches.len() {
        1 => Ok(matches[0]),
        0 => Err("salt pointer pattern not found".to_owned()),
        _ => Err(format!("salt pointer pattern is ambiguous: {matches:?}")),
    }
}

// Converts an image VA from the unpacked EXE code to an RVA.
fn va_to_rva(pe: &PeFile<'_>, va: u32) -> Option<u32> {
    let image_base = pe_image_base(pe.image())?;
    if va < image_base {
        return None;
    }
    let rva = va - image_base;
    for section in pe.section_headers() {
        let start = section.VirtualAddress;
        let size = section.VirtualSize.max(section.SizeOfRawData);
        let end = start.checked_add(size)?;
        if rva >= start && rva.checked_add(EXE_RESOURCE_SALT_SIZE as u32)? <= end {
            return Some(rva);
        }
    }
    None
}

// Reads the PE image base without relying on optional header wrapper fields.
fn pe_image_base(data: &[u8]) -> Option<u32> {
    let pe_offset = BinaryReader::u32_le_at(data, 0x3c) as usize;
    if data.get(pe_offset..pe_offset + 4)? != b"PE\0\0" {
        return None;
    }
    let optional = pe_offset.checked_add(0x18)?;
    let magic = BinaryReader::u16_le(data.get(optional..optional + 2)?);
    match magic {
        0x10b => Some(BinaryReader::u32_le_at(data, optional + 0x1c)),
        0x20b => {
            let image_base = BinaryReader::u64_le(data.get(optional + 0x18..optional + 0x20)?);
            u32::try_from(image_base).ok()
        }
        _ => None,
    }
}

// Extracts the BOOTSTRAP resource filter path from STARTUP.TJS string constants.
fn bootstrap_filter_path_from_startup(startup_tjs: &[u8]) -> Result<String, String> {
    let file = load_tjs2_bytecode(startup_tjs).map_err(|err| err.to_string())?;
    let mut matches = Vec::new();
    for value in file.string_constants() {
        let Some(path) = value
            .strip_prefix("bres://./")
            .and_then(|value| value.strip_suffix("/bootstrap"))
        else {
            continue;
        };
        if path.is_empty() || path.contains('/') || path.contains('\\') {
            return Err(format!(
                "invalid bootstrap filter path in STARTUP.TJS: {value:?}"
            ));
        }
        matches.push(path.to_owned());
    }
    matches.sort();
    matches.dedup();
    match matches.len() {
        1 => Ok(matches.remove(0)),
        0 => Err("bootstrap bres://./.../bootstrap string not found".to_owned()),
        _ => Err(format!(
            "bootstrap filter path is ambiguous in STARTUP.TJS: {}",
            matches.join(", ")
        )),
    }
}

// Adds a human-readable step name to an error.
fn step<T, E>(
    name: impl std::fmt::Display,
    action: impl FnOnce() -> Result<T, E>,
) -> Result<T, Box<dyn std::error::Error>>
where
    E: std::fmt::Display,
{
    action().map_err(|err| format!("{name}: {err}").into())
}

// Formats a short byte prefix as hexadecimal.
fn hex_prefix(data: &[u8], max_len: usize) -> String {
    let mut out = String::new();
    let count = data.len().min(max_len);
    for byte in &data[..count] {
        use std::fmt::Write;
        let _ = write!(out, "{byte:02X}");
    }
    if data.len() > count {
        out.push_str("...");
    }
    if out.is_empty() {
        out.push_str("<empty>");
    }
    out
}

// Dumps decrypted EXE embedded resources.
pub fn dump_exe_embedded_resources(
    output: &Path,
    exe: &Path,
    resources: &ExeEmbeddedResources,
) -> Result<usize, Box<dyn std::error::Error>> {
    let exe_name = exe
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("game.exe");
    let out_dir = output.join(sanitize(exe_name));
    std::fs::create_dir_all(&out_dir)?;

    std::fs::write(out_dir.join(STARTUP_RESOURCE_NAME), &resources.startup_tjs)?;
    std::fs::write(out_dir.join(BOOTSTRAP_RESOURCE_NAME), &resources.bootstrap)?;
    info!(
        exe = %exe.display(),
        files = 2usize,
        "dump EXE embedded resources"
    );
    Ok(2)
}

// Handles EXE candidates behavior.
fn exe_candidates(input_dir: &Path) -> Result<Vec<PathBuf>, Box<dyn std::error::Error>> {
    let mut paths = Vec::new();
    for entry in std::fs::read_dir(input_dir)? {
        let path = entry?.path();
        if path.is_file()
            && path
                .extension()
                .and_then(|ext| ext.to_str())
                .is_some_and(|ext| ext.eq_ignore_ascii_case("exe"))
        {
            paths.push(path);
        }
    }
    paths.sort_by_key(|path| exe_candidate_sort_key(path));
    Ok(paths)
}

// Handles EXE candidate sort key behavior.
fn exe_candidate_sort_key(path: &Path) -> (u8, u8, String) {
    let name = path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or_default()
        .to_lowercase();
    let unpacked = name.contains("unpacked");
    let auxiliary = [
        "config", "launcher", "patch", "setup", "support", "unins", "update",
    ]
    .iter()
    .any(|token| name.contains(token));
    (u8::from(auxiliary), u8::from(!unpacked), name)
}

// Unpacks the decrypted BOOTSTRAP resource payload.
fn unpack_bootstrap_resource(data: &[u8]) -> Result<Vec<u8>, Box<dyn std::error::Error>> {
    if data.len() < 8 {
        return Err("decrypted BOOTSTRAP payload is too short".into());
    }

    let packed_size = BinaryReader::u32_le(&data[0..4]) as usize;
    let unpacked_size = BinaryReader::u32_le(&data[4..8]) as usize;
    if packed_size != data.len() - 8 {
        return Err(format!(
            "unexpected BOOTSTRAP packed size: header={packed_size}, actual={}",
            data.len() - 8
        )
        .into());
    }

    let mut decoder = ZlibDecoder::new(&data[8..]);
    let mut unpacked = Vec::with_capacity(unpacked_size);
    decoder.read_to_end(&mut unpacked).unwrap();
    if unpacked.len() != unpacked_size {
        return Err(format!(
            "unexpected BOOTSTRAP unpacked size: header={unpacked_size}, actual={}",
            unpacked.len()
        )
        .into());
    }
    Ok(unpacked)
}
