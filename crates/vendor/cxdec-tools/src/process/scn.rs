use super::common::{
    RecoveryContext, ScriptJob, log_name_stage_summary, normalize_archive_path, progress_bar,
    push_unique_candidate, recover_candidate, register_name_hint, register_name_hints_parallel,
};
use crate::format::FileType;
use crate::io::BinaryReader;
use crate::r#struct::psb::Psb;
use std::collections::HashSet;
use tracing::info;

// Scans mounted scns.
pub fn scan_mounted_scns(
    ctx: &mut RecoveryContext<'_>,
) -> Result<Vec<ScriptJob>, Box<dyn std::error::Error>> {
    info!("recover !scnlist.txt");
    let scene_list = recover_scene_list(ctx)?;
    let scene_names = scene_list
        .as_ref()
        .map(|scene_list| parse_scene_list(&scene_list.data))
        .unwrap_or_default();
    info!(entries = scene_names.len(), "recover !scnlist.txt done");

    let mut jobs = Vec::new();
    info!("scan SCN");
    let pb = progress_bar(scene_names.len());

    for scene_name in scene_names {
        let candidates = scene_name_to_scn_candidates(&scene_name);
        for candidate in &candidates {
            register_name_hint(ctx, candidate);
        }
        let Some(recovered) = recover_first_scn_candidate(&candidates, ctx)? else {
            pb.inc(1);
            continue;
        };
        if recovered.file_type == FileType::Psb {
            jobs.push(ScriptJob {
                data: recovered.data,
            });
        }
        pb.inc(1);
    }
    pb.finish_and_clear();
    info!(files = jobs.len(), "scan SCN done");
    Ok(jobs)
}

// Registers SCN constant pool names.
pub fn register_scn_constant_pool_names(
    jobs: &[ScriptJob],
    ctx: &mut RecoveryContext<'_>,
) -> Result<(), Box<dyn std::error::Error>> {
    info!("collect SCN strings");
    let (hints, parsed, parse_errors) = collect_scn_string_hints_parallel(jobs);
    info!(parsed, parse_errors, "collect SCN strings done");

    let hash_stats = register_name_hints_parallel(ctx, hints, "hash SCN strings")?;
    log_name_stage_summary("SCN strings", parsed, hash_stats);
    Ok(())
}

// Collects SCN string hints parallel.
fn collect_scn_string_hints_parallel(jobs: &[ScriptJob]) -> (Vec<String>, usize, usize) {
    let pb = progress_bar(jobs.len());
    if jobs.is_empty() {
        pb.finish_and_clear();
        return (Vec::new(), 0, 0);
    }

    let cpu_cores = std::thread::available_parallelism()
        .map(|count| count.get())
        .unwrap_or(1);
    let workers = cpu_cores.min(jobs.len());
    info!(workers, "collect workers");
    let chunk_size = jobs.len().div_ceil(workers);
    let mut chunks = Vec::new();

    std::thread::scope(|scope| {
        let mut handles = Vec::new();
        for chunk in jobs.chunks(chunk_size) {
            let pb = pb.clone();
            handles.push(scope.spawn(move || {
                let mut hints = Vec::new();
                let mut seen = HashSet::new();
                let mut parsed = 0usize;
                let mut parse_errors = 0usize;

                for job in chunk {
                    let Ok(psb) = Psb::parse(&job.data) else {
                        parse_errors += 1;
                        pb.inc(1);
                        continue;
                    };
                    parsed += 1;
                    for value in psb.constant_strings() {
                        push_scn_constant_hints(&mut hints, &mut seen, value);
                    }
                    pb.inc(1);
                }

                (hints, parsed, parse_errors)
            }));
        }

        for handle in handles {
            chunks.push(handle.join().expect("SCN collect worker panicked"));
        }
    });

    let mut hints = Vec::new();
    let mut seen = HashSet::new();
    let mut parsed = 0usize;
    let mut parse_errors = 0usize;
    for (chunk_hints, chunk_parsed, chunk_parse_errors) in chunks {
        parsed += chunk_parsed;
        parse_errors += chunk_parse_errors;
        for hint in chunk_hints {
            push_scn_hint(&mut hints, &mut seen, hint);
        }
    }

    pb.finish_and_clear();
    (hints, parsed, parse_errors)
}

// Recovers scene list.
fn recover_scene_list(
    ctx: &mut RecoveryContext<'_>,
) -> Result<Option<super::common::RecoveredFile>, Box<dyn std::error::Error>> {
    for candidate in ["!scnlist.txt", "scenario/!scnlist.txt", "scn/!scnlist.txt"] {
        register_name_hint(ctx, candidate);
        if let Some(recovered) = recover_candidate(candidate, ctx)? {
            return Ok(Some(recovered));
        }
    }
    Ok(None)
}

// Parses scene list.
fn parse_scene_list(data: &[u8]) -> Vec<String> {
    let text = decode_text(data);
    let mut out = Vec::new();
    for line in text.lines() {
        let line = line.strip_suffix('\r').unwrap_or(line).trim();
        if line.is_empty() || line.starts_with('#') || line.starts_with('!') || line.contains(':') {
            continue;
        }
        let storage = line.split('\t').next().unwrap_or_default().trim();
        let storage = storage.rsplit(['/', '\\', '>']).next().unwrap_or(storage);
        if storage.is_empty() || storage == "-" {
            continue;
        }
        push_unique_candidate(&mut out, storage.to_owned());
    }
    out
}

// Recovers first SCN candidate.
fn recover_first_scn_candidate(
    candidates: &[String],
    ctx: &mut RecoveryContext<'_>,
) -> Result<Option<super::common::RecoveredFile>, Box<dyn std::error::Error>> {
    for candidate in candidates {
        if let Some(recovered) = recover_candidate(candidate, ctx)? {
            return Ok(Some(recovered));
        }
    }
    Ok(None)
}

// Handles scene name to SCN candidates behavior.
fn scene_name_to_scn_candidates(scene_name: &str) -> Vec<String> {
    let normalized = normalize_archive_path(scene_name);
    let mut out = Vec::new();

    if normalized.ends_with(".scn") {
        push_scene_candidate(&mut out, &normalized);
        return out;
    }

    let txt_scn = format!("{normalized}.scn");
    push_scene_candidate(&mut out, &txt_scn);

    if let Some(stem) = normalized.strip_suffix(".txt") {
        push_scene_candidate(&mut out, &format!("{stem}.scn"));
    }
    out
}

// Appends scene candidate.
fn push_scene_candidate(out: &mut Vec<String>, path: &str) {
    push_unique_candidate(out, path.to_owned());
    if !path.contains('/') {
        push_unique_candidate(out, format!("scn/{path}"));
        push_unique_candidate(out, format!("scenario/{path}"));
    }
}

// Decodes text.
fn decode_text(data: &[u8]) -> String {
    if let Some(body) = data.strip_prefix(&[0xff, 0xfe]) {
        let words = body
            .chunks_exact(2)
            .map(BinaryReader::u16_le)
            .collect::<Vec<_>>();
        return String::from_utf16_lossy(&words);
    }
    if let Some(body) = data.strip_prefix(&[0xfe, 0xff]) {
        let words = body
            .chunks_exact(2)
            .map(BinaryReader::u16_be)
            .collect::<Vec<_>>();
        return String::from_utf16_lossy(&words);
    }
    String::from_utf8_lossy(data).into_owned()
}

// Appends SCN constant hints.
fn push_scn_constant_hints(out: &mut Vec<String>, seen: &mut HashSet<String>, raw: &str) {
    let Some(cleaned) = clean_scn_constant(raw) else {
        return;
    };

    push_scn_hint(out, seen, cleaned);
}

// Appends SCN hint.
fn push_scn_hint(out: &mut Vec<String>, seen: &mut HashSet<String>, candidate: String) {
    if !candidate.is_empty() && seen.insert(candidate.clone()) {
        out.push(candidate);
    }
}

// Cleans SCN constant.
fn clean_scn_constant(raw: &str) -> Option<String> {
    let mut value = raw
        .trim()
        .trim_matches('"')
        .trim_matches('\'')
        .replace('\\', "/");
    if let Some((base, _effect)) = value.split_once('|') {
        value = base.to_owned();
    }
    value = value
        .split(['#', '@'])
        .next()
        .unwrap_or(&value)
        .trim()
        .to_owned();

    if value.is_empty()
        || value == "-"
        || value.len() > 180
        || value.starts_with(['#', '&', '*', '%', '$'])
        || value.contains(['?', '<', '>'])
        || value.parse::<f64>().is_ok()
        || value
            .chars()
            .any(|ch| ch.is_control() || ch.is_whitespace())
    {
        return None;
    }

    while let Some(stripped) = value.strip_prefix("./") {
        value = stripped.to_owned();
    }
    value = value.trim_start_matches('/').to_owned();
    if value.is_empty() {
        None
    } else {
        Some(normalize_archive_path(&value))
    }
}
