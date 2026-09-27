use super::common::{
    HxEntryCipher, MountedEntry, RecoveryContext, ScriptJob, archive_name, log_name_stage_summary,
    mounted_archive_path, progress_bar, read_mounted_entry, register_name_hints_parallel,
};
use crate::format::{self, FileType};
use crate::io::BinaryReader;
use crate::r#struct::tjs::load_tjs2_bytecode;
use std::collections::HashSet;
use tracing::info;

// Scans mounted data TJS.
pub fn scan_mounted_data_tjs(
    ctx: &mut RecoveryContext<'_>,
) -> Result<Vec<ScriptJob>, Box<dyn std::error::Error>> {
    let mut jobs = Vec::new();
    let total_entries = ctx
        .archives
        .iter()
        .filter(|archive| archive_name(&archive.path).eq_ignore_ascii_case("data.xp3"))
        .map(|archive| archive.entries.len())
        .sum::<usize>();
    info!("scan TJS");
    let pb = progress_bar(total_entries);

    for archive_index in 0..ctx.archives.len() {
        let archive_name = archive_name(&ctx.archives[archive_index].path);
        if !archive_name.eq_ignore_ascii_case("data.xp3") {
            continue;
        }

        let entry_count = ctx.archives[archive_index].entries.len();
        for entry_index in 0..entry_count {
            let mounted = MountedEntry {
                archive_index,
                entry_index,
            };
            if !is_mounted_tjs(mounted.clone(), ctx)? {
                pb.inc(1);
                continue;
            }

            let archive_path = mounted_tjs_archive_path(mounted.clone(), ctx)?;
            let recovered = read_mounted_entry(mounted, &archive_path, ctx)?;
            if recovered.file_type != FileType::TjsBytecode {
                pb.inc(1);
                continue;
            }
            jobs.push(ScriptJob {
                data: recovered.data,
            });
            pb.inc(1);
        }
    }
    pb.finish_and_clear();
    info!(files = jobs.len(), "scan TJS done");
    Ok(jobs)
}

// Registers TJS string pool names.
pub fn register_tjs_string_pool_names(
    jobs: &[ScriptJob],
    ctx: &mut RecoveryContext<'_>,
) -> Result<(), Box<dyn std::error::Error>> {
    let mut hints = Vec::new();
    let mut parsed = 0usize;
    info!("collect TJS strings");
    let pb = progress_bar(jobs.len());
    for job in jobs {
        let Ok(file) = load_tjs2_bytecode(&job.data) else {
            pb.inc(1);
            continue;
        };
        parsed += 1;
        hints.extend(file.string_constants().iter().cloned());
        pb.inc(1);
    }
    pb.finish_and_clear();
    info!(parsed, "collect TJS strings done");

    let hash_stats = register_name_hints_parallel(ctx, hints, "hash TJS strings")?;
    log_name_stage_summary("TJS strings", parsed, hash_stats);
    Ok(())
}

// Registers text names.
pub fn register_text_names(
    ctx: &mut RecoveryContext<'_>,
) -> Result<(), Box<dyn std::error::Error>> {
    let mut quote_hints = Vec::new();
    let mut token_hints = Vec::new();
    let mut seen_quotes = HashSet::new();
    let mut seen_tokens = HashSet::new();
    let mut parsed = 0usize;
    let total_entries = ctx
        .archives
        .iter()
        .map(|archive| archive.entries.len())
        .sum::<usize>();
    info!("scan text names");
    let pb = progress_bar(total_entries);

    for archive_index in 0..ctx.archives.len() {
        let entry_count = ctx.archives[archive_index].entries.len();
        for entry_index in 0..entry_count {
            let mounted = MountedEntry {
                archive_index,
                entry_index,
            };
            if !is_mounted_text_like(mounted.clone(), ctx)? {
                pb.inc(1);
                continue;
            }

            let archive_path = mounted_text_archive_path(mounted.clone(), ctx)?;
            let recovered = read_mounted_entry(mounted, &archive_path, ctx)?;
            let Some(text) = decode_plain_text(&recovered.data) else {
                pb.inc(1);
                continue;
            };

            parsed += 1;
            for value in quoted_strings(&text) {
                push_text_quote_hints(&mut quote_hints, &mut seen_quotes, &value);
            }
            for value in text_tokens(&text) {
                push_text_token_hints(&mut token_hints, &mut seen_tokens, &value);
            }
            pb.inc(1);
        }
    }
    pb.finish_and_clear();
    let quote_hint_count = quote_hints.len();
    let token_hint_count = token_hints.len();
    info!(
        parsed,
        quote_hints = quote_hint_count,
        token_hints = token_hint_count,
        "scan text names done"
    );

    let quote_hash_stats = register_name_hints_parallel(ctx, quote_hints, "hash text quotes")?;
    let token_hash_stats = register_name_hints_parallel(ctx, token_hints, "hash text tokens")?;
    let hash_stats = quote_hash_stats.merged(token_hash_stats);
    log_name_stage_summary("plain text names", parsed, hash_stats);
    Ok(())
}

// Returns whether mounted TJS.
fn is_mounted_tjs(
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
    Ok(format::detect(&head) == FileType::TjsBytecode)
}

// Handles mounted TJS archive path behavior.
fn mounted_tjs_archive_path(
    mounted: MountedEntry,
    ctx: &RecoveryContext<'_>,
) -> Result<String, Box<dyn std::error::Error>> {
    mounted_archive_path(&mounted, &ctx.archives)
        .ok_or_else(|| "archive entry index out of range".into())
}

// Returns whether mounted text like.
fn is_mounted_text_like(
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
    let head = archive.read_entry_prefix(mounted.entry_index, 64, &mut cipher)?;
    Ok(matches!(
        format::detect(&head),
        FileType::Text | FileType::Script
    ))
}

// Handles mounted text archive path behavior.
fn mounted_text_archive_path(
    mounted: MountedEntry,
    ctx: &RecoveryContext<'_>,
) -> Result<String, Box<dyn std::error::Error>> {
    mounted_archive_path(&mounted, &ctx.archives)
        .ok_or_else(|| "archive entry index out of range".into())
}

// Decodes plain text.
fn decode_plain_text(data: &[u8]) -> Option<String> {
    if let Some(body) = data.strip_prefix(&[0xff, 0xfe]) {
        let words = body
            .chunks_exact(2)
            .map(BinaryReader::u16_le)
            .collect::<Vec<_>>();
        return Some(String::from_utf16_lossy(&words));
    }
    if let Some(body) = data.strip_prefix(&[0xfe, 0xff]) {
        let words = body
            .chunks_exact(2)
            .map(BinaryReader::u16_be)
            .collect::<Vec<_>>();
        return Some(String::from_utf16_lossy(&words));
    }
    let text = std::str::from_utf8(data).ok()?;
    text.chars()
        .all(is_plain_text_char)
        .then(|| text.to_owned())
}

// Returns whether plain text char.
fn is_plain_text_char(ch: char) -> bool {
    ch == '\r' || ch == '\n' || ch == '\t' || (!ch.is_control() && ch != '\0')
}

// Handles quoted strings behavior.
fn quoted_strings(text: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut current = String::new();
    let mut in_quote = false;
    let mut escaped = false;

    for ch in text.chars() {
        if !in_quote {
            if ch == '"' {
                in_quote = true;
                current.clear();
            }
            continue;
        }

        if escaped {
            current.push(ch);
            escaped = false;
            continue;
        }
        if ch == '\\' {
            escaped = true;
            continue;
        }
        if ch == '"' {
            if !current.is_empty() {
                out.push(current.clone());
            }
            current.clear();
            in_quote = false;
            continue;
        }
        current.push(ch);
    }

    out
}

// Handles text tokens behavior.
fn text_tokens(text: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut current = String::new();

    for ch in text.chars() {
        if is_name_token_char(ch) {
            current.push(ch);
            continue;
        }
        push_text_token(&mut out, &mut current);
    }
    push_text_token(&mut out, &mut current);
    out
}

// Returns whether name token char.
fn is_name_token_char(ch: char) -> bool {
    ch == '/'
        || ch == '\\'
        || ch == '_'
        || ch == '-'
        || ch == '.'
        || ch.is_ascii_alphanumeric()
        || (!ch.is_ascii() && !ch.is_control() && !ch.is_whitespace())
}

// Appends text token.
fn push_text_token(out: &mut Vec<String>, current: &mut String) {
    if current.is_empty() {
        return;
    }
    if current.len() <= 180 {
        out.push(std::mem::take(current));
    } else {
        current.clear();
    }
}

// Appends text quote hints.
fn push_text_quote_hints(out: &mut Vec<String>, seen: &mut HashSet<String>, raw: &str) {
    let Some(path) = clean_text_name(raw) else {
        return;
    };
    push_text_hint(out, seen, &path);

    let parts = path
        .trim_end_matches('/')
        .split('/')
        .filter(|part| !part.is_empty())
        .collect::<Vec<_>>();
    if parts.len() <= 1 {
        return;
    }

    for index in 0..parts.len() {
        let suffix = parts[index..].join("/");
        push_text_hint(out, seen, &suffix);
        push_text_hint(out, seen, &format!("{suffix}/"));
    }
    for part in parts {
        push_text_hint(out, seen, part);
        push_text_hint(out, seen, &format!("{part}/"));
    }
}

// Appends text token hints.
fn push_text_token_hints(out: &mut Vec<String>, seen: &mut HashSet<String>, raw: &str) {
    let Some(path) = clean_text_name(raw) else {
        return;
    };
    push_text_hint(out, seen, &path);

    if !path.contains('/') {
        return;
    }

    let parts = path
        .split('/')
        .filter(|part| !part.is_empty())
        .collect::<Vec<_>>();
    for index in 0..parts.len() {
        let suffix = parts[index..].join("/");
        push_text_hint(out, seen, &suffix);
    }
}

// Appends text hint.
fn push_text_hint(out: &mut Vec<String>, seen: &mut HashSet<String>, candidate: &str) {
    if !candidate.is_empty() && seen.insert(candidate.to_owned()) {
        out.push(candidate.to_owned());
    }
}

// Cleans text name.
fn clean_text_name(raw: &str) -> Option<String> {
    let mut value = raw
        .trim()
        .trim_matches(',')
        .trim_matches(';')
        .trim_matches(':')
        .trim_matches(['[', ']', '(', ')', '{', '}'])
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
    if value.is_empty() { None } else { Some(value) }
}
