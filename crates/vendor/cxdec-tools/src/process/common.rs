use crate::crypto::bootstrap_key::BootstrapDerivedKeys;
use crate::crypto::cx_hash::{file_hash, path_hash};
use crate::crypto::hxv4_compute::HxFilter;
use crate::crypto::hxv4_shellcode::CxEncryption;
use crate::format::FileType;
use crate::process;
use crate::r#struct::xp3::{Xp3Archive, Xp3Cipher, Xp3Entry, sanitize};
use indicatif::{ProgressBar, ProgressStyle};
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use tracing::info;

#[derive(Debug, Clone)]
pub struct MountedEntry {
    pub archive_index: usize,
    pub entry_index: usize,
}

// Builds a progress bar with the project-wide display style.
pub fn progress_bar(len: usize) -> ProgressBar {
    let pb = ProgressBar::new(len as u64);
    let style = ProgressStyle::with_template(
        "{spinner:.green} [{elapsed_precise}] [{bar:40.cyan/blue}] {pos}/{len} {per_sec} ETA {eta}",
    )
    .unwrap()
    .progress_chars("=> ");
    pb.set_style(style);
    pb
}

#[derive(Debug, Clone)]
pub struct ScriptJob {
    pub data: Vec<u8>,
}

#[derive(Debug, Clone)]
pub struct RecoveredFile {
    pub archive_name: String,
    pub archive_path: String,
    pub data: Vec<u8>,
    pub file_type: FileType,
    pub mounted: Option<MountedEntry>,
}

#[derive(Debug)]
pub struct Stats {
    pub mounted_archives: usize,
    pub mounted_entries: usize,
    pub restored_files: usize,
    pub unrestored_files: usize,
}

#[derive(Debug, Clone, Copy, Default)]
pub struct NameHashStats {
    pub strings: usize,
    pub dir_hashes: usize,
    pub file_hashes: usize,
}

#[derive(Debug, Clone)]
pub struct GameScheme {
    pub title: String,
    pub mask: u32,
    pub offset: u32,
    pub prolog_order: [u8; 3],
    pub odd_branch_order: [u8; 6],
    pub even_branch_order: [u8; 8],
    pub control_block: Vec<u32>,
    pub hx_filter_key: u64,
    pub hx_random_type: i32,
    pub hx_derived_index_keys: HxDerivedIndexKeys,
}

#[derive(Debug, Clone)]
pub struct HxIndexKey {
    pub key1: Vec<u8>,
    pub key2: Vec<u8>,
}

#[derive(Debug, Clone)]
pub struct HxDerivedIndexKeys {
    pub default: HxIndexKey,
    pub alternate: HxIndexKey,
}

impl GameScheme {
    // Creates the runtime HxCrypt scheme from statically derived bootstrap keys.
    pub fn from_bootstrap_keys(keys: &BootstrapDerivedKeys) -> Self {
        Self {
            title: "Cxdec HxCrypt".to_owned(),
            mask: keys.params.mask,
            offset: keys.params.offset,
            prolog_order: keys.params.prolog_order,
            odd_branch_order: keys.params.odd_branch_order,
            even_branch_order: keys.params.even_branch_order,
            control_block: keys.control_block.clone(),
            hx_filter_key: keys.hx_filter_key,
            hx_random_type: keys.params.hx_random_type,
            hx_derived_index_keys: HxDerivedIndexKeys {
                default: HxIndexKey {
                    key1: keys.hx_table_keys.default_key1.to_vec(),
                    key2: keys.hx_table_keys.default_key2.to_vec(),
                },
                alternate: HxIndexKey {
                    key1: keys.hx_table_keys.alternate_key1.to_vec(),
                    key2: keys.hx_table_keys.alternate_key2.to_vec(),
                },
            },
        }
    }

    // Converts this value to CX shellcode scheme data.
    pub fn to_cx_scheme(&self) -> crate::crypto::hxv4_shellcode::CxScheme {
        crate::crypto::hxv4_shellcode::CxScheme {
            mask: self.mask,
            offset: self.offset,
            prolog_order: self.prolog_order,
            odd_branch_order: self.odd_branch_order,
            even_branch_order: self.even_branch_order,
            control_block: Some(self.control_block.clone()),
            tpm_file_name: None,
            nana_random_seed: None,
            hx_random_type: Some(self.hx_random_type),
        }
    }

    // Converts this value to XP3 HX index options.
    pub fn to_xp3_hx_options(
        &self,
        _archive_dir: Option<&Path>,
    ) -> crate::r#struct::xp3::Xp3HxOptions {
        crate::r#struct::xp3::Xp3HxOptions {
            index_key1: None,
            index_key2: None,
            index_key_dict: HashMap::new(),
            derived_index_keys: Some(crate::r#struct::xp3::Xp3HxDerivedIndexKeys {
                default: crate::r#struct::xp3::Xp3HxIndexKey {
                    key1: self.hx_derived_index_keys.default.key1.clone(),
                    key2: self.hx_derived_index_keys.default.key2.clone(),
                },
                alternate: crate::r#struct::xp3::Xp3HxIndexKey {
                    key1: self.hx_derived_index_keys.alternate.key1.clone(),
                    key2: self.hx_derived_index_keys.alternate.key2.clone(),
                },
            }),
            names_file: None,
        }
    }
}

impl NameHashStats {
    // Merges two summary values into one aggregate.
    pub fn merged(self, other: Self) -> Self {
        Self {
            strings: self.strings + other.strings,
            dir_hashes: self.dir_hashes + other.dir_hashes,
            file_hashes: self.file_hashes + other.file_hashes,
        }
    }
}

pub struct RecoveryContext<'a> {
    pub hash_domain: &'a str,
    pub output: &'a Path,
    pub index: HashMap<(Vec<u8>, Vec<u8>), Vec<MountedEntry>>,
    pub archives: Vec<Xp3Archive>,
    pub cx: CxEncryption,
    pub scheme: &'a GameScheme,
    pub written: HashSet<String>,
    pub found_entries: HashSet<(usize, usize)>,
    pub known_dirs: HashMap<Vec<u8>, String>,
    pub known_files: HashMap<Vec<u8>, String>,
    pub stats: Stats,
}

impl<'a> RecoveryContext<'a> {
    // Creates a new value for this type.
    pub fn new(
        hash_domain: &'a str,
        output: &'a Path,
        archives: Vec<Xp3Archive>,
        cx: CxEncryption,
        scheme: &'a GameScheme,
    ) -> Self {
        let stats = Stats {
            mounted_archives: archives.len(),
            mounted_entries: archives.iter().map(|archive| archive.entries.len()).sum(),
            restored_files: 0,
            unrestored_files: 0,
        };
        let mut known_dirs = HashMap::new();
        let known_files = HashMap::new();
        register_default_dirs(&mut known_dirs, hash_domain);
        Self {
            hash_domain,
            output,
            index: build_hash_index(&archives),
            archives,
            cx,
            scheme,
            written: HashSet::new(),
            found_entries: HashSet::new(),
            known_dirs,
            known_files,
            stats,
        }
    }
}

// Validates scheme.
pub fn validate_scheme(scheme: &GameScheme) -> Result<(), Box<dyn std::error::Error>> {
    if scheme.hx_derived_index_keys.default.key1.is_empty()
        || scheme.hx_derived_index_keys.default.key2.is_empty()
        || scheme.hx_derived_index_keys.alternate.key1.is_empty()
        || scheme.hx_derived_index_keys.alternate.key2.is_empty()
    {
        return Err(format!("{} has incomplete HxCrypt key material", scheme.title).into());
    }
    Ok(())
}

// Collects XP3 paths.
pub fn collect_xp3_paths(input: &Path) -> Result<Vec<PathBuf>, Box<dyn std::error::Error>> {
    if input.is_file() {
        return Ok(vec![input.to_path_buf()]);
    }
    let mut paths = Vec::new();
    for entry in std::fs::read_dir(input)? {
        let entry = entry?;
        let path = entry.path();
        if path
            .extension()
            .and_then(|ext| ext.to_str())
            .is_some_and(|ext| ext.eq_ignore_ascii_case("xp3"))
        {
            paths.push(path);
        }
    }
    paths.sort_by_key(|path| archive_sort_key(path));
    Ok(paths)
}

// Prepares output dir.
pub fn prepare_output_dir(
    output: &Path,
    protected_paths: &[&Path],
) -> Result<(), Box<dyn std::error::Error>> {
    if output.as_os_str().is_empty() {
        return Err("output directory is empty".into());
    }

    if output.exists() {
        let metadata = std::fs::symlink_metadata(output)?;
        if metadata.file_type().is_symlink() {
            return Err(format!(
                "refusing to clear symlink output directory: {}",
                output.display()
            )
            .into());
        }
        if !metadata.is_dir() {
            return Err(format!("output path is not a directory: {}", output.display()).into());
        }

        let output_path = output.canonicalize()?;
        validate_clear_target(&output_path, protected_paths)?;
        for entry in std::fs::read_dir(output)? {
            let entry = entry?;
            let path = entry.path();
            let file_type = entry.file_type()?;
            if file_type.is_dir() {
                std::fs::remove_dir_all(path)?;
            } else {
                std::fs::remove_file(path)?;
            }
        }
        info!(output = %output.display(), "cleared output directory");
    }

    std::fs::create_dir_all(output)?;
    Ok(())
}

// Validates clear target.
fn validate_clear_target(
    output: &Path,
    protected_paths: &[&Path],
) -> Result<(), Box<dyn std::error::Error>> {
    if output.file_name().is_none() {
        return Err(format!("refusing to clear root directory: {}", output.display()).into());
    }

    let current_dir = std::env::current_dir()?.canonicalize()?;
    if output == current_dir {
        return Err(format!(
            "refusing to clear current working directory: {}",
            output.display()
        )
        .into());
    }

    for protected in protected_paths {
        if protected.exists() && output == protected.canonicalize()? {
            return Err(format!(
                "refusing to clear protected directory: {}",
                output.display()
            )
            .into());
        }
    }

    Ok(())
}

// Handles mount archives behavior.
pub fn mount_archives(
    paths: &[PathBuf],
    scheme: &GameScheme,
) -> Result<Vec<Xp3Archive>, Box<dyn std::error::Error>> {
    let mut archives = Vec::new();
    for path in paths {
        let archive = Xp3Archive::open_with_hx(path, scheme.to_xp3_hx_options(path.parent()))?;
        info!(archive = %path.display(), entries = archive.entries.len(), "mounted archive");
        archives.push(archive);
    }
    Ok(archives)
}

// Recovers candidate.
pub fn recover_candidate(
    candidate: &str,
    ctx: &mut RecoveryContext<'_>,
) -> Result<Option<RecoveredFile>, Box<dyn std::error::Error>> {
    let (path_part, file_part) = split_archive_path(candidate)?;
    register_known_dir(ctx, &path_part);
    register_known_file(ctx, &file_part);
    let path_digest = path_hash(&path_part, ctx.hash_domain);
    let file_digest = file_hash(&file_part, ctx.hash_domain);
    let Some(matches) = ctx.index.get(&(path_digest.to_vec(), file_digest.to_vec())) else {
        return Ok(None);
    };

    if matches.len() != 1 {
        return Ok(None);
    }

    read_mounted_entry(matches[0].clone(), candidate, ctx).map(Some)
}

// Recovers remaining entries.
pub fn recover_remaining_entries(
    ctx: &mut RecoveryContext<'_>,
) -> Result<usize, Box<dyn std::error::Error>> {
    let mut recovered_count = 0usize;
    let total_remaining = ctx
        .archives
        .iter()
        .enumerate()
        .map(|(archive_index, archive)| {
            archive
                .entries
                .iter()
                .enumerate()
                .filter(|(entry_index, _entry)| {
                    !ctx.found_entries.contains(&(archive_index, *entry_index))
                })
                .count()
        })
        .sum::<usize>();
    info!("recover remaining entries");
    let pb = progress_bar(total_remaining);
    for archive_index in 0..ctx.archives.len() {
        let entry_count = ctx.archives[archive_index].entries.len();
        for entry_index in 0..entry_count {
            let entry_key = (archive_index, entry_index);
            if ctx.found_entries.contains(&entry_key) {
                continue;
            }
            let mounted = MountedEntry {
                archive_index,
                entry_index,
            };
            let archive_path = fallback_archive_path(&mounted, ctx)?;
            if archive_path_has_unresolved_hash(&archive_path) {
                ctx.stats.unrestored_files += 1;
            } else {
                ctx.stats.restored_files += 1;
            }
            let recovered = read_mounted_entry(mounted, &archive_path, ctx)?;
            write_recovered_file_silent(&recovered, ctx)?;
            recovered_count += 1;
            pb.inc(1);
        }
    }
    pb.finish_and_clear();
    info!(
        target_files = total_remaining,
        restored_files = ctx.stats.restored_files,
        unrestored_files = ctx.stats.unrestored_files,
        "recover summary"
    );
    Ok(recovered_count)
}

// Handles fallback archive path behavior.
pub fn fallback_archive_path(
    mounted: &MountedEntry,
    ctx: &RecoveryContext<'_>,
) -> Result<String, Box<dyn std::error::Error>> {
    let archive = ctx
        .archives
        .get(mounted.archive_index)
        .ok_or("archive index out of range")?;
    let entry = archive
        .entries
        .get(mounted.entry_index)
        .ok_or("archive entry index out of range")?;
    let Some(hx) = entry.hx_info.as_ref() else {
        return Ok(normalize_archive_path(&entry.name));
    };

    let dir = ctx
        .known_dirs
        .get(&hx.path_hash)
        .cloned()
        .unwrap_or_else(|| hex_upper(&hx.path_hash));
    let file = ctx
        .known_files
        .get(&hx.file_hash)
        .cloned()
        .unwrap_or_else(|| hex_upper(&hx.file_hash));

    if dir.is_empty() {
        Ok(file)
    } else {
        Ok(format!("{}{file}", normalize_archive_dir(&dir)))
    }
}

// Handles archive path has unresolved hash behavior.
fn archive_path_has_unresolved_hash(path: &str) -> bool {
    normalize_archive_path(path)
        .split('/')
        .filter(|part| !part.is_empty())
        .any(is_raw_hash_component)
}

// Returns whether raw hash component.
fn is_raw_hash_component(value: &str) -> bool {
    matches!(value.len(), 16 | 32 | 64) && value.chars().all(|ch| ch.is_ascii_hexdigit())
}

// Reads mounted entry.
pub fn read_mounted_entry(
    mounted: MountedEntry,
    archive_path: &str,
    ctx: &mut RecoveryContext<'_>,
) -> Result<RecoveredFile, Box<dyn std::error::Error>> {
    let archive = ctx
        .archives
        .get_mut(mounted.archive_index)
        .ok_or("archive index out of range")?;
    let archive_name = archive_name(&archive.path);
    let entry_name = archive_path.to_owned();
    let mut cipher = HxEntryCipher {
        inner: &mut ctx.cx,
        scheme: ctx.scheme,
    };
    let data = archive.read_entry(mounted.entry_index, &mut cipher)?;
    let processed = process::process_file(data);
    Ok(RecoveredFile {
        archive_name,
        archive_path: entry_name,
        data: processed.data,
        file_type: processed.file_type,
        mounted: Some(mounted),
    })
}

// Writes recovered file silent.
fn write_recovered_file_silent(
    recovered: &RecoveredFile,
    ctx: &mut RecoveryContext<'_>,
) -> Result<(), Box<dyn std::error::Error>> {
    if let Some(mounted) = &recovered.mounted {
        ctx.found_entries
            .insert((mounted.archive_index, mounted.entry_index));
    }

    let write_key = format!(
        "{}:{}",
        recovered.archive_name.to_lowercase(),
        normalize_archive_path(&recovered.archive_path)
    );
    if !ctx.written.insert(write_key) {
        return Ok(());
    }

    let dst = output_path(ctx.output, &recovered.archive_name, &recovered.archive_path);
    if let Some(parent) = dst.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(&dst, &recovered.data)?;
    Ok(())
}

// Cleans name token.
fn clean_name_token(token: &str) -> Option<String> {
    let mut token = token
        .trim()
        .trim_matches('"')
        .trim_matches('\'')
        .trim_matches(',')
        .trim_matches(';')
        .trim_matches(':')
        .trim_matches(['[', ']', '(', ')', '{', '}'])
        .replace('\\', "/");
    if let Some((base, _effect)) = token.split_once('|') {
        token = base.to_owned();
    }
    if token.is_empty()
        || token.len() > 180
        || token.starts_with(['#', '@', '&', '*', '%', '$'])
        || token.contains(['?', '<', '>'])
        || token.parse::<f64>().is_ok()
    {
        return None;
    }
    let is_dir = token.ends_with('/');
    let token = token.trim_start_matches("./").trim_start_matches('/');
    let normalized = normalize_archive_path(token);
    if normalized.is_empty() {
        None
    } else if is_dir {
        Some(normalize_archive_dir(&normalized))
    } else {
        Some(normalized)
    }
}

// Checks whether data looks like storage dir.
fn looks_like_storage_dir(path: &str) -> bool {
    let first = path.split('/').next().unwrap_or_default();
    DEFAULT_ARCHIVE_DIRS
        .iter()
        .any(|base| first.eq_ignore_ascii_case(base))
}

// Returns whether file extension.
fn has_file_extension(file: &str) -> bool {
    let Some(dot) = file.rfind('.') else {
        return false;
    };
    dot != 0 && dot + 1 < file.len()
}

// Checks whether data looks like resource stem.
fn looks_like_resource_stem(leaf: &str) -> bool {
    if leaf.len() < 2 || leaf.len() > 80 {
        return false;
    }
    leaf.chars()
        .all(|ch| ch == '_' || ch == '-' || ch.is_ascii_alphanumeric() || !ch.is_ascii())
}

// Handles file name variants behavior.
fn file_name_variants(stem: &str) -> Vec<String> {
    let mut out = Vec::with_capacity(KNOWN_FILE_EXTENSIONS.len() + 1);
    push_unique_candidate(&mut out, stem.to_owned());
    for ext in KNOWN_FILE_EXTENSIONS {
        push_unique_candidate(&mut out, format!("{stem}{ext}"));
    }
    out
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
struct NameHashRecord {
    string: String,
    dir: String,
    file: Option<String>,
}

impl NameHashRecord {
    // Handles directory behavior.
    fn directory(dir: &str) -> Self {
        let dir = normalize_archive_dir(dir);
        Self {
            string: dir.clone(),
            dir,
            file: None,
        }
    }

    // Handles file behavior.
    fn file(dir: &str, file: &str) -> Option<Self> {
        let dir = if dir.trim().is_empty() {
            String::new()
        } else {
            normalize_archive_dir(dir)
        };
        let file = normalize_file_name(file);
        if file.is_empty() {
            return None;
        }
        let string = if dir.is_empty() {
            file.clone()
        } else {
            format!("{dir}{file}")
        };
        Some(Self {
            string,
            dir,
            file: Some(file),
        })
    }
}

// Registers known dir.
fn register_known_dir(ctx: &mut RecoveryContext<'_>, dir: &str) {
    let dir = if dir.trim().is_empty() {
        String::new()
    } else {
        normalize_archive_dir(dir)
    };
    let digest = path_hash(&dir, ctx.hash_domain).to_vec();
    ctx.known_dirs.entry(digest).or_insert(dir);
}

// Registers known file.
fn register_known_file(ctx: &mut RecoveryContext<'_>, file: &str) {
    let file = normalize_file_name(file);
    if file.is_empty() || file.contains('/') {
        return;
    }
    let digest = file_hash(&file, ctx.hash_domain).to_vec();
    ctx.known_files.entry(digest).or_insert(file);
}

// Registers name hints parallel.
pub fn register_name_hints_parallel<I, S>(
    ctx: &mut RecoveryContext<'_>,
    hints: I,
    label: &str,
) -> Result<NameHashStats, Box<dyn std::error::Error>>
where
    I: IntoIterator<Item = S>,
    S: AsRef<str>,
{
    let mut seen_records = HashSet::new();
    let mut string_count = 0usize;
    let records = hints
        .into_iter()
        .flat_map(|hint| {
            let hint = hint.as_ref().trim();
            if !hint.is_empty() {
                string_count += 1;
            }
            name_hash_records(hint)
        })
        .filter(|record| seen_records.insert(record.clone()))
        .collect::<Vec<_>>();
    let stats = NameHashStats {
        strings: string_count,
        dir_hashes: records.len(),
        file_hashes: records
            .iter()
            .filter(|record| record.file.is_some())
            .count(),
    };
    info!(stage = %label, "hash stage");
    let pb = progress_bar(stats.dir_hashes);
    if records.is_empty() {
        pb.finish_and_clear();
        info!(stage = %label, names = 0usize, "hash stage done");
        return Ok(stats);
    }

    let cpu_cores = std::thread::available_parallelism()
        .map(|count| count.get())
        .unwrap_or(1);
    let workers = cpu_cores.min(records.len());
    info!(workers, "hash workers");
    let chunk_size = records.len().div_ceil(workers);
    let hash_domain = ctx.hash_domain.to_owned();
    let mut dir_maps = Vec::new();
    let mut file_maps = Vec::new();

    std::thread::scope(|scope| {
        let mut handles = Vec::new();
        for chunk in records.chunks(chunk_size) {
            let hash_domain = &hash_domain;
            let pb = pb.clone();
            handles.push(scope.spawn(move || {
                let mut dirs = HashMap::new();
                let mut files = HashMap::new();
                for record in chunk {
                    dirs.entry(path_hash(&record.dir, hash_domain).to_vec())
                        .or_insert_with(|| record.dir.clone());
                    if let Some(file) = &record.file {
                        files
                            .entry(file_hash(file, hash_domain).to_vec())
                            .or_insert_with(|| file.clone());
                    }
                    pb.inc(1);
                }
                (dirs, files)
            }));
        }

        for handle in handles {
            let (dirs, files) = handle.join().expect("name hint worker panicked");
            dir_maps.push(dirs);
            file_maps.push(files);
        }
    });

    for dirs in dir_maps {
        for (hash, dir) in dirs {
            ctx.known_dirs.entry(hash).or_insert(dir);
        }
    }
    for files in file_maps {
        for (hash, file) in files {
            ctx.known_files.entry(hash).or_insert(file);
        }
    }
    pb.finish_and_clear();
    info!(stage = %label, names = stats.dir_hashes, "hash stage done");
    Ok(stats)
}

// Handles log name stage summary behavior.
pub fn log_name_stage_summary(stage: &str, target_files: usize, hash_stats: NameHashStats) {
    info!(
        stage,
        target_files,
        strings = hash_stats.strings,
        dir_hashes = hash_stats.dir_hashes,
        file_hashes = hash_stats.file_hashes,
        "stage summary"
    );
}

// Appends unique candidate.
pub fn push_unique_candidate(out: &mut Vec<String>, candidate: String) {
    if !candidate.is_empty() && !out.iter().any(|item| item == &candidate) {
        out.push(candidate);
    }
}

// Registers name hint.
pub fn register_name_hint(ctx: &mut RecoveryContext<'_>, raw: &str) {
    for record in name_hash_records(raw) {
        register_known_dir(ctx, &record.dir);
        if let Some(file) = record.file.as_deref() {
            register_known_file(ctx, file);
        }
    }
}

// Handles name hash records behavior.
fn name_hash_records(raw: &str) -> Vec<NameHashRecord> {
    let mut out = Vec::new();
    let Some(path) = clean_name_token(raw) else {
        return out;
    };

    if path.ends_with('/') {
        push_unique_record(&mut out, NameHashRecord::directory(&path));
        return out;
    }

    if let Ok((dir, file)) = split_archive_path(&path) {
        if !dir.is_empty() {
            push_unique_record(&mut out, NameHashRecord::directory(&dir));
        }
        if has_file_extension(&file) {
            if let Some(record) = NameHashRecord::file(&dir, &file) {
                push_unique_record(&mut out, record);
            }
            return out;
        }
        if path.contains('/') {
            if looks_like_storage_dir(&path) {
                push_unique_record(&mut out, NameHashRecord::directory(&path));
            }
            if looks_like_resource_stem(&file) {
                for candidate in file_name_variants(&file) {
                    if let Some(record) = NameHashRecord::file(&dir, &candidate) {
                        push_unique_record(&mut out, record);
                    }
                }
            }
            return out;
        }
    }

    let leaf = path.rsplit('/').next().unwrap_or(path.as_str());
    if leaf.is_empty() {
        return out;
    }
    if has_file_extension(leaf) {
        if let Some(record) = NameHashRecord::file("", leaf) {
            push_unique_record(&mut out, record);
        }
    } else if looks_like_resource_stem(leaf) {
        for candidate in file_name_variants(leaf) {
            if let Some(record) = NameHashRecord::file("", &candidate) {
                push_unique_record(&mut out, record);
            }
        }
    }
    out
}

// Appends unique record.
fn push_unique_record(out: &mut Vec<NameHashRecord>, record: NameHashRecord) {
    if !out.iter().any(|item| item == &record) {
        out.push(record);
    }
}

// Normalizes archive path.
pub fn normalize_archive_path(path: &str) -> String {
    let mut out = Vec::new();
    for comp in path.trim().replace('\\', "/").split('/') {
        match comp {
            "" | "." => {}
            ".." => {
                let _ = out.pop();
            }
            other => out.push(other.to_lowercase()),
        }
    }
    out.join("/")
}

// Builds hash index.
fn build_hash_index(archives: &[Xp3Archive]) -> HashMap<(Vec<u8>, Vec<u8>), Vec<MountedEntry>> {
    let mut index = HashMap::new();
    for (archive_index, archive) in archives.iter().enumerate() {
        for (entry_index, entry) in archive.entries.iter().enumerate() {
            if let Some(hx) = entry.hx_info.as_ref() {
                index
                    .entry((hx.path_hash.clone(), hx.file_hash.clone()))
                    .or_insert_with(Vec::new)
                    .push(MountedEntry {
                        archive_index,
                        entry_index,
                    });
            }
        }
    }
    index
}

// Registers default dirs.
fn register_default_dirs(out: &mut HashMap<Vec<u8>, String>, hash_domain: &str) {
    register_dir_name(out, hash_domain, "");
    register_dir_name(out, hash_domain, "data/");
    for base in DEFAULT_ARCHIVE_DIRS {
        register_dir_name(out, hash_domain, &format!("{base}/"));
    }
}

// Registers dir name.
fn register_dir_name(out: &mut HashMap<Vec<u8>, String>, hash_domain: &str, dir: &str) {
    let dir = if dir.trim().is_empty() {
        String::new()
    } else {
        normalize_archive_dir(dir)
    };
    out.entry(path_hash(&dir, hash_domain).to_vec())
        .or_insert(dir);
}

pub const KNOWN_FILE_EXTENSIONS: &[&str] = &[
    ".tjs", ".ks", ".scn", ".txt", ".csv", ".ini", ".func", ".pimg", ".pbd", ".sli", ".psb",
    ".ttf", ".otf", ".ttc", ".woff", ".woff2", ".asd", ".png", ".tlg", ".tlg5", ".tlg6", ".jpg",
    ".jpeg", ".bmp", ".webp", ".emf", ".stage", ".stand", ".sinfo", ".event", ".psd", ".ogg",
    ".ogg.sli", ".opus", ".wav", ".tcw", ".amv", ".webm", ".mp4", ".avi", ".wmv",
];

const DEFAULT_ARCHIVE_DIRS: &[&str] = &[
    "k2compat", "system", "sysscn", "video", "others", "rule", "sound", "bgm", "fgimage",
    "bgimage", "scenario", "scn", "image", "voice", "face", "init", "font", "sysse", "main",
    "evimage", "thum", "uipsd", "motion", "motiondx", "emote", "emotedx", "bishamon", "video2",
    "fgimage2",
];

// Handles archive sort key behavior.
fn archive_sort_key(path: &Path) -> (u8, String) {
    let name = archive_name(path).to_lowercase();
    let priority = if name == "data.xp3" {
        0
    } else if name.starts_with("patch") {
        2
    } else {
        1
    };
    (priority, name)
}

// Handles output path behavior.
fn output_path(out_dir: &Path, archive_name: &str, archive_path: &str) -> PathBuf {
    out_dir
        .join(sanitize(archive_name))
        .join(sanitize(&normalize_archive_path(archive_path)))
}

// Handles split archive path behavior.
fn split_archive_path(path: &str) -> Result<(String, String), Box<dyn std::error::Error>> {
    let normalized = normalize_archive_path(path);
    if normalized.is_empty() {
        return Err("archive path is empty".into());
    }
    let Some((dir, file)) = normalized.rsplit_once('/') else {
        return Ok((String::new(), normalized));
    };
    if file.is_empty() {
        return Err("archive path must name a file".into());
    }
    Ok((format!("{dir}/"), file.to_owned()))
}

// Normalizes archive dir.
fn normalize_archive_dir(path: &str) -> String {
    let normalized = normalize_archive_path(path);
    if normalized.is_empty() {
        String::new()
    } else {
        format!("{normalized}/")
    }
}

// Normalizes file name.
fn normalize_file_name(file: &str) -> String {
    file.trim()
        .replace('\\', "/")
        .rsplit('/')
        .next()
        .unwrap_or_default()
        .to_lowercase()
}

// Handles archive name behavior.
pub fn archive_name(path: &Path) -> String {
    path.file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("archive.xp3")
        .to_owned()
}

// Handles mounted archive path behavior.
pub fn mounted_archive_path(mounted: &MountedEntry, archives: &[Xp3Archive]) -> Option<String> {
    let entry = archives
        .get(mounted.archive_index)?
        .entries
        .get(mounted.entry_index)?;
    Some(entry_archive_path(entry))
}

// Handles entry archive path behavior.
pub fn entry_archive_path(entry: &Xp3Entry) -> String {
    if let Some(hx) = entry.hx_info.as_ref() {
        return format!("{}/{}", hex_upper(&hx.path_hash), hex_upper(&hx.file_hash));
    }
    normalize_archive_path(&entry.name)
}

// Formats bytes as uppercase hexadecimal text.
fn hex_upper(data: &[u8]) -> String {
    let mut out = String::with_capacity(data.len() * 2);
    for byte in data {
        use std::fmt::Write;
        let _ = write!(out, "{byte:02X}");
    }
    out
}

// Creates an invalid-data I/O error with the provided message.
fn invalid(msg: &str) -> std::io::Error {
    std::io::Error::new(std::io::ErrorKind::InvalidData, msg)
}

pub struct HxEntryCipher<'a> {
    pub inner: &'a mut CxEncryption,
    pub scheme: &'a GameScheme,
}

impl Xp3Cipher for HxEntryCipher<'_> {
    // Returns whether encrypted.
    fn is_encrypted(&self, entry: &Xp3Entry) -> bool {
        entry.hx_info.is_some()
    }

    // Decrypts entry.
    fn decrypt_entry(
        &mut self,
        entry: &Xp3Entry,
        offset: u64,
        data: &mut [u8],
    ) -> std::io::Result<()> {
        let hx_info = entry
            .hx_info
            .as_ref()
            .ok_or_else(|| invalid("missing Hx entry info"))?;
        let filter = HxFilter::from_entry_info(self.inner, self.scheme.hx_filter_key, hx_info)
            .map_err(|e| {
                std::io::Error::new(
                    std::io::ErrorKind::InvalidData,
                    format!("Hx filter key derivation failed: {e}"),
                )
            })?;
        filter.decrypt(offset, data);
        Ok(())
    }

    // Handles decrypt behavior.
    fn decrypt(&mut self, _hash: u32, _offset: u64, _data: &mut [u8]) -> std::io::Result<()> {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::cx_hash::{file_hash, path_hash};

    #[test]
    // Handles explicit trailing slash name hint registers directory behavior.
    fn explicit_trailing_slash_name_hint_registers_directory() {
        let records = name_hash_records("共通_メルヴィ/");
        assert_eq!(
            records,
            vec![NameHashRecord {
                string: "共通_メルヴィ/".to_owned(),
                dir: "共通_メルヴィ/".to_owned(),
                file: None,
            }]
        );
        assert_eq!(
            path_hash("共通_メルヴィ/", "xp3hnp"),
            hex_to_8("a0e4a123ca112632")
        );
    }

    #[test]
    // Handles stem hint expands to matching records behavior.
    fn stem_hint_expands_to_matching_records() {
        let records = name_hash_records("global");
        assert!(records.iter().any(|record| {
            record.string == "global"
                && record.dir.is_empty()
                && record.file.as_deref() == Some("global")
        }));
        assert!(records.iter().any(|record| {
            record.string == "global.ogg"
                && record.dir.is_empty()
                && record.file.as_deref() == Some("global.ogg")
        }));

        for record in records {
            let dir_hash = hex_upper(&path_hash(&record.dir, "xp3hnp"));
            let file_hash_hex = record
                .file
                .as_ref()
                .map(|file| hex_upper(&file_hash(file, "xp3hnp")))
                .unwrap_or_default();
            assert!(!dir_hash.is_empty());
            if record.file.is_some() {
                assert!(!file_hash_hex.is_empty());
            }
            if record.dir.is_empty() {
                assert_eq!(record.string, record.file.unwrap());
            }
        }
    }

    #[test]
    // Prepares output dir clears existing contents.
    fn prepare_output_dir_clears_existing_contents() {
        let root = unique_temp_dir("clear-output");
        let output = root.join("out");
        let nested = output.join("nested");
        std::fs::create_dir_all(&nested).unwrap();
        std::fs::write(output.join("old.txt"), b"old").unwrap();
        std::fs::write(nested.join("old.txt"), b"old").unwrap();

        prepare_output_dir(&output, &[]).unwrap();

        assert!(output.is_dir());
        assert!(std::fs::read_dir(&output).unwrap().next().is_none());
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    // Prepares output dir rejects protected path.
    fn prepare_output_dir_rejects_protected_path() {
        let root = unique_temp_dir("protected-output");
        let err = prepare_output_dir(&root, &[&root]).unwrap_err();
        assert!(
            err.to_string()
                .contains("refusing to clear protected directory")
        );
        std::fs::remove_dir_all(root).unwrap();
    }

    // Handles hex to 8 behavior.
    fn hex_to_8(hex: &str) -> [u8; 8] {
        let mut out = [0u8; 8];
        for index in 0..8 {
            out[index] = u8::from_str_radix(&hex[index * 2..index * 2 + 2], 16).unwrap();
        }
        out
    }

    // Handles unique temp dir behavior.
    fn unique_temp_dir(name: &str) -> PathBuf {
        let path = std::env::temp_dir().join(format!("cxdec-tools-{name}-{}", std::process::id()));
        if path.exists() {
            std::fs::remove_dir_all(&path).unwrap();
        }
        std::fs::create_dir_all(&path).unwrap();
        path
    }
}
