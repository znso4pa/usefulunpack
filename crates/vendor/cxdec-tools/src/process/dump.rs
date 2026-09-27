use super::common::{
    GameScheme, HxEntryCipher, archive_name, collect_xp3_paths, entry_archive_path, mount_archives,
    prepare_output_dir, progress_bar, validate_scheme,
};
use super::exe::{detect_game_exe_resources, dump_exe_embedded_resources};
use crate::crypto::hxv4_shellcode::CxEncryption;
use crate::pipeline::{derive_bootstrap_keys_from_resources, log_bootstrap_keys};
use crate::process;
use crate::r#struct::xp3::{Xp3Archive, sanitize};
use std::path::{Path, PathBuf};
use tracing::info;

pub struct DumpOptions {
    pub resource_dir: PathBuf,
    pub output: PathBuf,
}

pub struct DumpSummary {
    pub archives: usize,
    pub entries: usize,
    pub written: usize,
}

// Dumps archives.
pub fn dump_archives(args: DumpOptions) -> Result<DumpSummary, Box<dyn std::error::Error>> {
    if !args.resource_dir.exists() {
        return Err(format!(
            "resource directory not found: {}",
            args.resource_dir.display()
        )
        .into());
    }

    let xp3_paths = collect_xp3_paths(&args.resource_dir)?;
    if xp3_paths.is_empty() {
        return Err("no XP3 archives found".into());
    }

    let exe_resources = detect_game_exe_resources(&args.resource_dir)?;
    let bootstrap_keys = derive_bootstrap_keys_from_resources(
        &exe_resources.resources.bootstrap,
        &exe_resources.resources.startup_tjs,
    )?;
    log_bootstrap_keys(&bootstrap_keys);
    let scheme = GameScheme::from_bootstrap_keys(&bootstrap_keys);
    validate_scheme(&scheme)?;
    prepare_output_dir(&args.output, &[&args.resource_dir])?;
    let exe_written =
        dump_exe_embedded_resources(&args.output, &exe_resources.exe, &exe_resources.resources)?;
    let mut archives = mount_archives(&xp3_paths, &scheme)?;
    let mut cx = CxEncryption::new(scheme.to_cx_scheme(), Some(&args.resource_dir))?;

    let mut summary = DumpSummary {
        archives: archives.len(),
        entries: archives.iter().map(|archive| archive.entries.len()).sum(),
        written: exe_written,
    };

    for archive in &mut archives {
        let written = dump_archive(archive, &args.output, &scheme, &mut cx)?;
        summary.written += written;
    }

    Ok(summary)
}

// Dumps archive.
fn dump_archive(
    archive: &mut Xp3Archive,
    output: &Path,
    scheme: &GameScheme,
    cx: &mut CxEncryption,
) -> Result<usize, Box<dyn std::error::Error>> {
    let archive_name = archive_name(&archive.path);
    let out_dir = output.join(sanitize(&archive_name));
    std::fs::create_dir_all(&out_dir)?;

    let mut written = 0usize;
    let pb = progress_bar(archive.entries.len());
    for index in 0..archive.entries.len() {
        let entry_name = dump_entry_name(archive, index);
        let mut cipher = HxEntryCipher { inner: cx, scheme };
        let data = archive.read_entry(index, &mut cipher)?;
        let processed = process::process_file(data);

        let dst = out_dir.join(sanitize(&entry_name));
        if let Some(parent) = dst.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(&dst, processed.data)?;
        written += 1;
        pb.inc(1);
    }
    pb.finish_and_clear();

    info!(archive = %archive_name, files = written, "dump archive");
    Ok(written)
}

// Dumps entry name.
fn dump_entry_name(archive: &Xp3Archive, index: usize) -> String {
    entry_archive_path(&archive.entries[index])
}
