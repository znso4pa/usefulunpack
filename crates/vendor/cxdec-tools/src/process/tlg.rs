use super::common::{
    HxEntryCipher, MountedEntry, RecoveryContext, fallback_archive_path, log_name_stage_summary,
    progress_bar, push_unique_candidate, read_mounted_entry, register_name_hints_parallel,
};
use crate::format::{self, FileType};
use crate::r#struct::tlg::Tlg;
use std::collections::HashSet;
use tracing::info;

// Registers TLG ref names.
pub fn register_tlg_ref_names(
    ctx: &mut RecoveryContext<'_>,
) -> Result<(), Box<dyn std::error::Error>> {
    let mut hints = Vec::new();
    let mut seen = HashSet::new();
    let mut detected = 0usize;
    let mut parsed = 0usize;
    let mut parse_errors = 0usize;
    let total_entries = ctx
        .archives
        .iter()
        .map(|archive| archive.entries.len())
        .sum::<usize>();

    info!("scan TLGref");
    let pb = progress_bar(total_entries);
    for archive_index in 0..ctx.archives.len() {
        let entry_count = ctx.archives[archive_index].entries.len();
        for entry_index in 0..entry_count {
            let mounted = MountedEntry {
                archive_index,
                entry_index,
            };
            if !is_mounted_tlg_ref(mounted.clone(), ctx)? {
                pb.inc(1);
                continue;
            }
            detected += 1;

            let archive_path = fallback_archive_path(&mounted, ctx)?;
            let recovered = read_mounted_entry(mounted, &archive_path, ctx)?;
            if recovered.file_type != FileType::TlgRef {
                pb.inc(1);
                continue;
            }

            match Tlg::parse(&recovered.data).and_then(|tlg| tlg.strings()) {
                Ok(strings) => {
                    parsed += 1;
                    for value in strings {
                        push_tlg_hint(&mut hints, &mut seen, value);
                    }
                }
                Err(_) => {
                    parse_errors += 1;
                }
            }
            pb.inc(1);
        }
    }
    pb.finish_and_clear();

    info!(
        detected,
        parsed,
        hints = hints.len(),
        parse_errors,
        "scan TLGref done"
    );

    let hash_stats = register_name_hints_parallel(ctx, hints, "hash TLGref names")?;
    log_name_stage_summary("TLGref names", parsed, hash_stats);
    Ok(())
}

// Returns whether mounted TLG ref.
fn is_mounted_tlg_ref(
    mounted: MountedEntry,
    ctx: &mut RecoveryContext<'_>,
) -> Result<bool, Box<dyn std::error::Error>> {
    let archive = ctx
        .archives
        .get_mut(mounted.archive_index)
        .ok_or("archive index out of range")?;
    let mut cipher = HxEntryCipher {
        inner: &mut ctx.cx,
        scheme: ctx.scheme,
    };
    let head = archive.read_entry_prefix(mounted.entry_index, 11, &mut cipher)?;
    Ok(format::detect(&head) == FileType::TlgRef)
}

// Appends TLG hint.
fn push_tlg_hint(out: &mut Vec<String>, seen: &mut HashSet<String>, raw: String) {
    let value = raw.trim().replace('\\', "/");
    if value.is_empty() || value.contains(['?', '<', '>']) {
        return;
    }
    if seen.insert(value.clone()) {
        push_unique_candidate(out, value);
    }
}
