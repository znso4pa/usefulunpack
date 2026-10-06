//! Out-of-tree harness for the RPA reader.
//!
//! Prints an index manifest (byte-identical in shape to the Python oracle in
//! this project's `testdata/README.md`) or extracts an archive, so a real
//! `.rpa` can be verified against independent readers without shipping the
//! sample in the repository:
//!
//! ```text
//! cargo run -p archive_rpa-core --example manifest -- manifest game/archive.rpa > rust.json
//! python3 oracle.py manifest game/archive.rpa py.json
//! cargo run -p archive_rpa-core --example manifest -- extract game/archive.rpa /tmp/rust-out
//! python3 oracle.py extract game/archive.rpa /tmp/py-out
//! diff -r /tmp/py-out /tmp/rust-out
//! ```

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 3 {
        eprintln!("usage: manifest <manifest|extract> <archive> [outdir]");
        std::process::exit(2);
    }
    let mode = args[1].as_str();
    let archive = args[2].clone();

    match mode {
        "manifest" => match archive_rpa_core::open_rpa(&archive) {
            Ok(rpa) => {
                let mut out = String::new();
                out.push_str(&format!("{{\n \"archive\": \"{}\",\n", archive));
                out.push_str(&format!(" \"version\": \"{}\",\n", rpa.version));
                out.push_str(&format!(" \"key\": \"{:08x}\",\n", rpa.key));
                out.push_str(&format!(" \"archive_size\": {},\n", rpa.file_len));
                out.push_str(&format!(" \"entry_count\": {},\n \"entries\": [\n", rpa.entries.len()));
                let mut names: Vec<&archive_rpa_core::RpaEntry> = rpa.entries.iter().collect();
                names.sort_by(|a, b| a.name.cmp(&b.name));
                for (i, e) in names.iter().enumerate() {
                    let parts: Vec<String> = e
                        .parts
                        .iter()
                        .map(|p| match p {
                            archive_rpa_core::RpaPart::Inline(b) => format!("\"inline:{}\"", hex(b)),
                            archive_rpa_core::RpaPart::Chunk { offset, len } => format!("[{offset},{len}]"),
                        })
                        .collect();
                    out.push_str(&format!(
                        "  {{\"name\": {}, \"size\": {}, \"broken\": {}, \"parts\": [{}]}}{}\n",
                        json_str(&e.name),
                        e.size,
                        e.broken.is_some(),
                        parts.join(", "),
                        if i + 1 == names.len() { "" } else { "," }
                    ));
                }
                out.push_str(" ]\n}\n");
                print!("{out}");
            }
            Err(e) => {
                eprintln!("open failed: {e}");
                std::process::exit(1);
            }
        },
        "extract" => {
            if args.len() < 4 {
                eprintln!("usage: manifest extract <archive> <outdir>");
                std::process::exit(2);
            }
            match archive_rpa_core::extract_rpa_host(&archive, &args[3]) {
                Ok((total, fail)) => println!("total={total} fail={fail}"),
                Err(e) => {
                    eprintln!("extract failed: {e}");
                    std::process::exit(1);
                }
            }
        }
        "create" => {
            if args.len() < 4 {
                eprintln!("usage: manifest create <srcdir> <out.rpa>");
                std::process::exit(2);
            }
            match archive_rpa_core::create_rpa_host(&args[2], &args[3]) {
                Ok(n) => println!("packed {n} entries -> {}", args[3]),
                Err(e) => {
                    eprintln!("create failed: {e}");
                    std::process::exit(1);
                }
            }
        }
        other => {
            eprintln!("unknown mode {other:?}");
            std::process::exit(2);
        }
    }
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn json_str(s: &str) -> String {
    let mut out = String::from("\"");
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}
