use super::common::{
    HxEntryCipher, MountedEntry, RecoveryContext, fallback_archive_path, log_name_stage_summary,
    normalize_archive_path, progress_bar, push_unique_candidate, read_mounted_entry,
    register_name_hints_parallel,
};
use crate::format::{self, FileType};
use crate::r#struct::pbd::Pbd;
use std::collections::HashSet;
use tracing::info;

// Registers PBD layer image names.
pub fn register_pbd_layer_image_names(
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

    info!("scan PBD");
    let pb = progress_bar(total_entries);
    for archive_index in 0..ctx.archives.len() {
        let entry_count = ctx.archives[archive_index].entries.len();
        for entry_index in 0..entry_count {
            let mounted = MountedEntry {
                archive_index,
                entry_index,
            };
            if !is_mounted_pbd(mounted.clone(), ctx)? {
                pb.inc(1);
                continue;
            }
            detected += 1;

            let archive_path = fallback_archive_path(&mounted, ctx)?;
            let recovered = read_mounted_entry(mounted, &archive_path, ctx)?;
            if recovered.file_type != FileType::Pbd {
                pb.inc(1);
                continue;
            }
            match Pbd::parse(&recovered.data) {
                Ok(pbd) => {
                    parsed += 1;
                    let layers = pbd.layer_images();
                    let Some(stem) = pbd_stem_from_archive_path(&archive_path) else {
                        pb.inc(1);
                        continue;
                    };
                    for candidate in pbd_layer_tlg_candidates(stem, &layers) {
                        if seen.insert(candidate.clone()) {
                            hints.push(candidate);
                        }
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
        "scan PBD done"
    );

    let hash_stats = register_name_hints_parallel(ctx, hints, "hash PBD layer image names")?;
    log_name_stage_summary("PBD layer images", parsed, hash_stats);
    Ok(())
}

// Returns whether mounted PBD.
fn is_mounted_pbd(
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
    let head = archive.read_entry_prefix(mounted.entry_index, 8, &mut cipher)?;
    Ok(format::detect(&head) == FileType::Pbd)
}

// Handles PBD stem from archive path behavior.
fn pbd_stem_from_archive_path(archive_path: &str) -> Option<String> {
    let normalized = normalize_archive_path(archive_path);
    normalized.strip_suffix(".pbd").map(ToOwned::to_owned)
}

// Handles PBD layer TLG candidates behavior.
fn pbd_layer_tlg_candidates(
    pbd_stem: String,
    layers: &[crate::r#struct::pbd::PbdLayerImage],
) -> Vec<String> {
    let mut out = Vec::new();
    for layer in layers {
        let image_name = get_layer_image_name(&pbd_stem, &layer.layer_id);
        let image_path = ensure_tlg_extension(&image_name);
        push_unique_candidate(&mut out, image_path);
    }
    out
}

// Returns whether raw hash component.
fn is_raw_hash_component(value: &str) -> bool {
    matches!(value.len(), 16 | 32 | 64) && value.chars().all(|ch| ch.is_ascii_hexdigit())
}

// Gets layer image name.
fn get_layer_image_name(pbd_stem: &str, layer_id: &str) -> String {
    if is_tjs_positive_number(layer_id) {
        let stem = trim_unresolved_hash_dirs(pbd_stem);
        format!("{stem}_{layer_id}")
    } else {
        normalize_archive_path(layer_id)
    }
}

// Trims unresolved hash dirs.
fn trim_unresolved_hash_dirs(path: &str) -> &str {
    let mut start = 0usize;
    for part in path.split('/') {
        if part.is_empty() {
            start += 1;
            continue;
        }
        if !is_raw_hash_component(part) {
            break;
        }
        start += part.len();
        if path.as_bytes().get(start).is_some_and(|byte| *byte == b'/') {
            start += 1;
        }
    }
    let trimmed = path.get(start..).unwrap_or(path).trim_start_matches('/');
    if trimmed.is_empty() { path } else { trimmed }
}

// Ensures TLG extension.
fn ensure_tlg_extension(path: &str) -> String {
    let normalized = normalize_archive_path(path);
    let leaf = normalized.rsplit('/').next().unwrap_or(normalized.as_str());
    if leaf.contains('.') {
        normalized
    } else {
        format!("{normalized}.tlg")
    }
}

// Returns whether TJS positive number.
fn is_tjs_positive_number(value: &str) -> bool {
    let mut chars = value.chars();
    matches!(chars.next(), Some('1'..='9')) && chars.all(|ch| ch.is_ascii_digit())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::r#struct::pbd::{Endian, PbdCompression, PbdHeader, PbdValue};

    #[test]
    // Handles PBD layer TLG candidates use layer image rule behavior.
    fn pbd_layer_tlg_candidates_use_layer_image_rule() {
        let pbd = Pbd {
            header: PbdHeader {
                endian: Endian::Little,
                compression: PbdCompression::Lz4,
                seed: 0,
                crypt_mode: 1,
                inner_iv_len: 0,
            },
            root: PbdValue::Array(vec![PbdValue::Dictionary(vec![
                ("layer_type".to_owned(), PbdValue::Integer(0)),
                ("layer_id".to_owned(), PbdValue::Integer(2841)),
            ])]),
            trailer: 0,
        };

        assert_eq!(
            pbd_layer_tlg_candidates(
                "fgimage/アルテシア/アルテシアＡ_0".to_owned(),
                &pbd.layer_images()
            ),
            vec!["fgimage/アルテシア/アルテシアａ_0_2841.tlg".to_owned()]
        );
    }

    #[test]
    // Handles non numeric layer id is used as image name behavior.
    fn non_numeric_layer_id_is_used_as_image_name() {
        assert_eq!(
            get_layer_image_name("fgimage/base/name", "fgimage/parts/custom"),
            "fgimage/parts/custom"
        );
    }
}
