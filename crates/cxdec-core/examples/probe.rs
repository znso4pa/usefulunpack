//! Dev probe: run the cxdec detection + extraction against a real archive.
//! Usage: cargo run -p archive_cxdec-core --release --example probe -- \
//!          <game_dir> <archive.xp3> [extract_dir] [--adler]
//!
//! `--adler` additionally checks the rule the encrypted WRITER depends on:
//! every entry's stored ADLR (the cipher key seed) must equal
//! `adler32(plaintext)`. That is what the plain XP3 writer computes and what
//! cxdec-core's test builder assumes, but only a real archive can confirm the
//! game's own packer does the same — run this before trusting the writer's
//! output in a real game.

use std::fs;
use std::path::Path;

fn adler32(data: &[u8]) -> u32 {
    const MOD: u32 = 65521;
    let (mut a, mut b) = (1u32, 0u32);
    for chunk in data.chunks(5552) {
        for &byte in chunk {
            a += byte as u32;
            b += a;
        }
        a %= MOD;
        b %= MOD;
    }
    (b << 16) | a
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let check_adler = args.iter().any(|a| a == "--adler");
    let positional: Vec<&String> = args.iter().skip(1).filter(|a| !a.starts_with("--")).collect();
    if positional.len() < 2 {
        eprintln!("usage: probe <game_dir> <archive.xp3> [extract_dir] [--adler]");
        std::process::exit(2);
    }
    let game_dir = positional[0];
    let archive = positional[1];
    let out_dir = positional.get(2).map(|s| s.to_string()).unwrap_or_else(|| "/tmp/cxdec-out".to_string());

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
        Ok(a) => {
            println!("[index] entries={}", a.entries.len());
            // What real archives actually look like: how many entries carry the
            // INFO protected flag, and whether their segments are compressed.
            // Both answer questions the writer's layout depends on.
            let protected = a.entries.iter().filter(|e| e.is_encrypted).count();
            let packed = a.entries.iter().filter(|e| e.is_packed).count();
            let segs = a.entries.iter().map(|e| e.segments.len()).sum::<usize>();
            let comp_segs = a.entries.iter().flat_map(|e| e.segments.iter()).filter(|s| s.is_compressed).count();
            let bytes: u64 = a.entries.iter().map(|e| e.unpacked_size).sum();
            println!(
                "[index] protected={protected} packed={packed} segments={segs} compressed_segments={comp_segs} unpacked_total={bytes}"
            );
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

    // 2b. The warning heuristic the UI uses when there is no sidecar: flag set
    //     AND nothing in the archive reads as content.
    println!(
        "[evidence] content_looks_encrypted={}",
        archive_cxdec_core::content_looks_encrypted(archive)
    );

    // 3. scheme detection — and the probe the UI uses, which must agree.
    let start = std::time::Instant::now();
    let cipher = match archive_cxdec_core::detect_cipher(Path::new(game_dir), archive) {
        Ok((cx, scheme)) => {
            println!("[detect] scheme={scheme} in {:?}", start.elapsed());
            println!("[probe] {:?}", archive_cxdec_core::probe_scheme(Path::new(game_dir), archive));
            cx
        }
        Err(e) => {
            println!("[detect] FAILED: {e}");
            println!("[probe] {:?}", archive_cxdec_core::probe_scheme(Path::new(game_dir), archive));
            return;
        }
    };

    // 3b. ADLR rule (the encrypted writer's contract) — decrypt every entry and
    //     compare adler32(plaintext) with the stored key seed.
    if check_adler {
        let mut arch = match cxdec_tools::r#struct::xp3::Xp3Archive::open(Path::new(archive)) {
            Ok(a) => a,
            Err(e) => {
                println!("[adler] open FAILED: {e}");
                return;
            }
        };
        let mut cx = cipher;
        let mut cipher_fn = |hash: u32, offset: u64, data: &mut [u8]| {
            cx.decrypt(hash, offset, data)
                .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e))
        };
        let total = arch.entries.len();
        let (mut ok, mut bad, mut err) = (0u32, 0u32, 0u32);
        let mut first_bad: Vec<(String, u32, u32)> = Vec::new();
        for i in 0..total {
            let (name, hash) = (arch.entries[i].name.clone(), arch.entries[i].hash);
            match arch.read_entry(i, &mut cipher_fn) {
                Ok(data) => {
                    let got = adler32(&data);
                    if got == hash {
                        ok += 1;
                    } else {
                        bad += 1;
                        if first_bad.len() < 5 {
                            first_bad.push((name, hash, got));
                        }
                    }
                }
                Err(_) => err += 1,
            }
        }
        println!("[adler] entries={total} adler32==ADLR: {ok} mismatch: {bad} undecodable: {err}");
        for (name, stored, computed) in &first_bad {
            println!("[adler] MISMATCH {name}: stored={stored:08x} adler32(plain)={computed:08x}");
        }
        if bad == 0 && err == 0 {
            println!("[adler] RULE HOLDS: stored ADLR == adler32(plaintext) on every entry");
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
