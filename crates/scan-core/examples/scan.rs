//! Host harness: scans a file and prints one hit per line as
//! `offset<TAB>label<TAB>size`, for diffing against binwalk.
fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 2 {
        eprintln!("usage: scan <file>");
        std::process::exit(2);
    }
    match archive_scan_core::scan_file_json(&args[1]) {
        Ok(json) => {
            for obj in json.trim_matches(['[', ']']).split("},{") {
                if obj.is_empty() { continue; }
                let get = |key: &str| -> String {
                    let needle = format!("\"{key}\":");
                    match obj.find(&needle) {
                        Some(at) => {
                            let rest = &obj[at + needle.len()..];
                            let end = rest.find([',', '}']).unwrap_or(rest.len());
                            rest[..end].trim_matches('"').to_string()
                        }
                        None => String::new(),
                    }
                };
                println!("{}\t{}\t{}", get("o"), get("l"), get("s"));
            }
        }
        Err(e) => {
            eprintln!("scan failed: {e}");
            std::process::exit(1);
        }
    }
}
