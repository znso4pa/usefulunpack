//! Dev probe: run the cxdec detection + extraction against a real archive.
//! Usage: cargo run -p archive_cxdec-core --release --example probe -- \
//!          <game_dir> <archive.xp3> [extract_dir]

use std::fs;
use std::path::Path;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 3 {
        eprintln!("usage: probe <game_dir> <archive.xp3> [extract_dir]");
        std::process::exit(2);
    }
    let game_dir = &args[1];
    let archive = &args[2];
    let out_dir = args.get(3).cloned().unwrap_or_else(|| "/tmp/cxdec-out".to_string());

    // 1. control block / tjs parse report
    let tjs_text = fs::read_to_string(Path::new(game_dir).join("xp3filter.tjs")).ok();
    match &tjs_text {
        Some(text) => {
            println!(
                "[tjs] len={} block={} params={:?}",
                text.len(),
                archive_cxdec_core::tjs_control_block(text).map(|w| w.len()).unwrap_or(0),
                archive_cxdec_core::tjs_scheme_params(text),
            );
        }
        None => println!("[tjs] not found in game dir"),
    }

    // 2. plain XP3 open + entry preview (index layer)
    let arch = cxdec_tools::r#struct::xp3::Xp3Archive::open(Path::new(archive));
    match arch {
        Ok(mut a) => {
            println!("[index] entries={}", a.entries.len());
            for e in a.entries.iter().take(5) {
                println!(
                    "[index] {:?} size={} hash={:08x} segs={}",
                    e.name, e.unpacked_size, e.hash, e.segments.len()
                );
                for s in &e.segments {
                    println!("        seg comp={} off={} size={} packed={}", s.is_compressed, s.offset, s.size, s.packed_size);
                }
            }
        }
        Err(e) => {
            println!("[index] open FAILED: {e}");
            return;
        }
    }

    // 3. scheme detection
    let start = std::time::Instant::now();
    match archive_cxdec_core::detect_cipher(Path::new(game_dir), archive) {
        Ok((_cx, scheme)) => println!("[detect] scheme={scheme} in {:?}", start.elapsed()),
        Err(e) => {
            println!("[detect] FAILED: {e}");
            return;
        }
    }

    // 4. extraction
    let _ = fs::create_dir_all(&out_dir);
    let start = std::time::Instant::now();
    match archive_cxdec_core::extract(game_dir, archive, &out_dir, None) {
        Ok((total, fail)) => println!(
            "[extract] total={total} fail={fail} in {:?} -> {out_dir}",
            start.elapsed()
        ),
        Err(e) => println!("[extract] FAILED: {e}"),
    }
}
