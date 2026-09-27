//! Recovery pipeline orchestration.

use crate::crypto::bootstrap_key::{
    BootstrapDerivedKeys, BootstrapStaticData, derive_bootstrap_keys_from_input,
};
use crate::crypto::exe_resource::{
    archive_unique_key_from_bootstrap_pe, bootstrap_item_from_pe, is_params_payload,
    is_unique_payload, is_warning_payload,
};
use crate::crypto::hxv4_shellcode::CxEncryption;
use crate::process::common::{
    GameScheme, RecoveryContext, collect_xp3_paths, log_name_stage_summary, mount_archives,
    prepare_output_dir, recover_remaining_entries, register_name_hints_parallel, validate_scheme,
};
use crate::process::exe::{detect_game_exe_resources, dump_exe_embedded_resources};
use crate::process::{pbd, scn, tjs, tlg};
use crate::r#struct::tjs::{
    ConstPools, ConstValue, Instruction, OP_CALLD, OP_CHGTHIS, OP_CONST, OP_SPDS, Tjs2File,
    Tjs2Object, Variant, apply_const_flow, decode_instructions, load_tjs2_bytecode, object_data,
    reg,
};
use std::path::PathBuf;
use tracing::info;

pub struct RecoveryOptions {
    pub input_dir: PathBuf,
    pub output: PathBuf,
    pub hash_domain: String,
}

const RECOVER_STAGE_COUNT: usize = 10;

// Handles run recover behavior.
pub fn run_recover(args: RecoveryOptions) -> Result<(), Box<dyn std::error::Error>> {
    log_stage(1, RECOVER_STAGE_COUNT, "prepare input/output");
    if !args.input_dir.is_dir() {
        return Err(format!("input directory not found: {}", args.input_dir.display()).into());
    }

    let exe_resources = detect_game_exe_resources(&args.input_dir)?;
    let bootstrap_keys = derive_bootstrap_keys_from_resources(
        &exe_resources.resources.bootstrap,
        &exe_resources.resources.startup_tjs,
    )?;
    log_bootstrap_keys(&bootstrap_keys);
    let scheme = GameScheme::from_bootstrap_keys(&bootstrap_keys);
    validate_scheme(&scheme)?;
    let xp3_paths = collect_xp3_paths(&args.input_dir)?;
    if xp3_paths.is_empty() {
        return Err("no XP3 archives found".into());
    }
    prepare_output_dir(&args.output, &[&args.input_dir])?;
    dump_exe_embedded_resources(&args.output, &exe_resources.exe, &exe_resources.resources)?;

    log_stage(2, RECOVER_STAGE_COUNT, "mount XP3 archives");
    let archives = mount_archives(&xp3_paths, &scheme)?;
    let cx = CxEncryption::new(scheme.to_cx_scheme(), Some(&args.input_dir))?;
    let mut ctx = RecoveryContext::new(&args.hash_domain, &args.output, archives, cx, &scheme);

    log_stage(3, RECOVER_STAGE_COUNT, "collect EXE startup hints");
    register_startup_tjs_names(&exe_resources.resources.startup_tjs, &mut ctx)?;

    log_stage(4, RECOVER_STAGE_COUNT, "collect TJS string hints");
    let data_tjs_jobs = tjs::scan_mounted_data_tjs(&mut ctx)?;
    tjs::register_tjs_string_pool_names(&data_tjs_jobs, &mut ctx)?;

    log_stage(5, RECOVER_STAGE_COUNT, "collect SCN string hints");
    let scn_jobs = scn::scan_mounted_scns(&mut ctx)?;
    scn::register_scn_constant_pool_names(&scn_jobs, &mut ctx)?;

    log_stage(6, RECOVER_STAGE_COUNT, "collect plain text hints");
    tjs::register_text_names(&mut ctx)?;

    log_stage(7, RECOVER_STAGE_COUNT, "collect PBD layer hints");
    pbd::register_pbd_layer_image_names(&mut ctx)?;

    log_stage(8, RECOVER_STAGE_COUNT, "collect TLGref string hints");
    tlg::register_tlg_ref_names(&mut ctx)?;

    log_stage(9, RECOVER_STAGE_COUNT, "recover entries");
    recover_remaining_entries(&mut ctx)?;

    log_stage(10, RECOVER_STAGE_COUNT, "summary");
    info!(
        game = %scheme.title,
        exe = %exe_resources.exe.display(),
        archives = ctx.stats.mounted_archives,
        total_files = ctx.stats.mounted_entries,
        restored_files = ctx.stats.restored_files,
        unrestored_files = ctx.stats.unrestored_files,
        "final summary"
    );

    Ok(())
}

// Handles log stage behavior.
fn log_stage(index: usize, total: usize, name: &str) {
    info!("========== [{index}/{total}] {name} ==========");
}

// Registers startup TJS names.
fn register_startup_tjs_names(
    startup: &[u8],
    ctx: &mut RecoveryContext<'_>,
) -> Result<(), Box<dyn std::error::Error>> {
    let Ok(file) = load_tjs2_bytecode(startup) else {
        return Ok(());
    };
    let hash_stats =
        register_name_hints_parallel(ctx, file.string_constants(), "hash EXE startup strings")?;
    log_name_stage_summary("EXE startup strings", 1, hash_stats);
    Ok(())
}

// Derives bootstrap runtime keys from decrypted BOOTSTRAP and STARTUP resources.
pub fn derive_bootstrap_keys_from_resources(
    bootstrap_pe: &[u8],
    startup_tjs: &[u8],
) -> Result<BootstrapDerivedKeys, Box<dyn std::error::Error>> {
    let static_data = bootstrap_static_data_from_pe(bootstrap_pe)?;
    let bootstrap_input = bootstrap_input_from_startup(startup_tjs)?;
    derive_bootstrap_keys_from_input(&static_data, &bootstrap_input)
}

// Logs the bootstrap key material summary used by the scheme.
pub fn log_bootstrap_keys(keys: &BootstrapDerivedKeys) {
    info!(
        mask = keys.params.mask,
        offset = keys.params.offset,
        hx_random_type = keys.params.hx_random_type,
        hx_filter_key = keys.hx_filter_key,
        "derived bootstrap keys"
    );
}

// Reads Cxdec bootstrap static key material from a PE image.
fn bootstrap_static_data_from_pe(
    data: &[u8],
) -> Result<BootstrapStaticData, Box<dyn std::error::Error>> {
    let params_payload = bootstrap_item_from_pe(data, "PARAMS", is_params_payload)?;
    let params = crate::crypto::exe_resource::parse_bootstrap_params(&params_payload)?;
    let warning = String::from_utf8(bootstrap_item_from_pe(data, "WARNING", is_warning_payload)?)?;
    let unique_utf16le = bootstrap_item_from_pe(data, "UNIQUE", is_unique_payload)?;
    let archive_unique_key = archive_unique_key_from_bootstrap_pe(data)?;
    Ok(BootstrapStaticData {
        params_payload,
        params,
        warning,
        unique_utf16le,
        archive_unique_key,
    })
}

// Extracts the first bootstrap argument from STARTUP.TJS bytecode.
fn bootstrap_input_from_startup(startup_tjs: &[u8]) -> Result<String, Box<dyn std::error::Error>> {
    let file = load_tjs2_bytecode(startup_tjs)?;
    Ok(startup_bootstrap_input(&file)?)
}

// Finds the string argument passed to the startup bootstrap function.
fn startup_bootstrap_input(file: &Tjs2File) -> crate::r#struct::tjs::Result<String> {
    let Some(top) = file.objects.get(file.toplevel as usize) else {
        return Err(crate::r#struct::tjs::TjsError::new(
            "STARTUP.TJS toplevel object is out of range",
        ));
    };
    if !top_level_has_bootstrap_registration(top, &file.const_pools) {
        return Err(crate::r#struct::tjs::TjsError::new(
            "STARTUP.TJS bootstrap function object not found",
        ));
    }

    let instructions = decode_instructions(&top.code);
    let mut regs: Vec<Option<ConstValue>> = Vec::new();
    let mut matches = Vec::new();
    for instr in &instructions {
        if instr.op == OP_CALLD
            && is_bootstrap_call(top, &file.const_pools, instr)
            && let Some(value) = call_first_string_arg(instr, &regs)
        {
            matches.push(value);
        }
        apply_const_flow(top, &file.const_pools, instr, &mut regs);
    }

    matches.sort();
    matches.dedup();
    match matches.len() {
        0 => Err(crate::r#struct::tjs::TjsError::new(
            "STARTUP.TJS bootstrap input string not found from disasm",
        )),
        1 => Ok(matches.remove(0)),
        _ => Err(crate::r#struct::tjs::TjsError::new(
            "STARTUP.TJS bootstrap input string is ambiguous",
        )),
    }
}

// Returns whether the top-level code registers the bootstrap function.
fn top_level_has_bootstrap_registration(top: &Tjs2Object, pools: &ConstPools) -> bool {
    let instructions = decode_instructions(&top.code);
    let Some(instr) = instructions.first() else {
        return false;
    };
    if instr.op != OP_CONST || instr.operands.len() < 2 {
        return false;
    }
    if !matches!(
        object_data(top, instr.operands[1]),
        Some(Variant::InterObject(_) | Variant::InterGenerator(_))
    ) {
        return false;
    }

    let mut next = 1usize;
    if next < instructions.len() && instructions[next].op == OP_CHGTHIS {
        next += 1;
    }
    next < instructions.len() && is_bootstrap_registration(top, pools, &instructions[next])
}

// Returns whether SPDS stores a function into global._bootStrap.
fn is_bootstrap_registration(object: &Tjs2Object, pools: &ConstPools, instr: &Instruction) -> bool {
    if instr.op != OP_SPDS || instr.operands.len() < 3 || instr.operands[0] != -1 {
        return false;
    }
    let Some(Variant::String(name_index)) = object_data(object, instr.operands[1]) else {
        return false;
    };
    pools
        .strings
        .get(*name_index as usize)
        .is_some_and(|name| name == "_bootStrap")
}

// Returns whether a CALLD instruction invokes the bootstrap function.
fn is_bootstrap_call(object: &Tjs2Object, pools: &ConstPools, instr: &Instruction) -> bool {
    if instr.operands.len() < 4 || instr.operands[1] != -2 || instr.operands[3] != 2 {
        return false;
    }
    let Some(Variant::String(name_index)) = object_data(object, instr.operands[2]) else {
        return false;
    };
    pools
        .strings
        .get(*name_index as usize)
        .is_some_and(|name| name == "_bootStrap")
}

// Returns the first string argument from a CALLD instruction.
fn call_first_string_arg(instr: &Instruction, regs: &[Option<ConstValue>]) -> Option<String> {
    if instr.operands.len() < 5 || instr.operands[3] <= 0 {
        return None;
    }
    match reg(regs, instr.operands[4]) {
        Some(ConstValue::String(value)) => Some(value.clone()),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extracts_startup_bootstrap_input_from_call_disasm() {
        let strings = vec![
            "_bootStrap".to_owned(),
            "Game/secret-startup-token".to_owned(),
            String::new(),
        ];
        let top = Tjs2Object {
            index: 0,
            parent: -1,
            name_string_index: -1,
            name: None,
            context_type: 0,
            max_variable_count: 0,
            variable_reserve_count: 0,
            max_frame_count: 0,
            func_decl_arg_count: 0,
            func_decl_unnamed_arg_array_base: 0,
            func_decl_collapse_base: 0,
            prop_setter: -1,
            prop_getter: -1,
            super_class_getter: -1,
            code: vec![
                OP_CONST, 1, 0, OP_CHGTHIS, 1, -1, OP_SPDS, -1, 1, 1, OP_CONST, 4, 2, OP_CONST, 5,
                3, OP_CALLD, 6, -2, 1, 2, 4, 5,
            ],
            data: vec![
                Variant::InterObject(1),
                Variant::String(0),
                Variant::String(1),
                Variant::String(2),
            ],
            scgetterps: Vec::new(),
            properties: Vec::new(),
        };
        let bootstrap = Tjs2Object {
            index: 1,
            parent: 0,
            name_string_index: 0,
            name: Some("_bootStrap".to_owned()),
            context_type: 1,
            max_variable_count: 0,
            variable_reserve_count: 0,
            max_frame_count: 0,
            func_decl_arg_count: 2,
            func_decl_unnamed_arg_array_base: 0,
            func_decl_collapse_base: 0,
            prop_setter: -1,
            prop_getter: -1,
            super_class_getter: -1,
            code: Vec::new(),
            data: Vec::new(),
            scgetterps: Vec::new(),
            properties: Vec::new(),
        };
        let file = Tjs2File {
            toplevel: 0,
            const_pools: ConstPools {
                strings,
                ..ConstPools::default()
            },
            objects: vec![top, bootstrap],
        };

        assert_eq!(
            startup_bootstrap_input(&file).unwrap(),
            "Game/secret-startup-token"
        );
    }
}
