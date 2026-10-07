//! Out-of-tree harness: dumps an INT archive's index as JSON, so a real game
//! archive can be checked against an independent tool without committing the
//! sample. Usage: `manifest <archive.int>` (the game's .exe must sit next to
//! it — the index key is derived from its resources).
fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() >= 2 && args[1] == "create" {
        // create <srcdir> <out.int> [key_field] — the key_field form lets the
        // harness reproduce a real archive's key exactly.
        if args.len() < 4 {
            eprintln!("usage: manifest create <srcdir> <out.int> [key_field]");
            std::process::exit(2);
        }
        let r = match args.get(4).and_then(|v| v.parse::<u32>().ok()) {
            Some(k) => archive_int_core::int_create_archive_with_key_field(&args[2], &args[3], k),
            None => archive_int_core::create_int_host(&args[2], &args[3]),
        };
        match r {
            Ok(n) => println!("packed {n} entries -> {}", args[3]),
            Err(e) => {
                eprintln!("create failed: {e}");
                std::process::exit(1);
            }
        }
        return;
    }
    if args.len() < 2 {
        eprintln!("usage: manifest <archive.int> | create <srcdir> <out.int> [key_field]");
        std::process::exit(2);
    }
    match archive_int_core::open_int(&args[1]) {
        Ok(ar) => {
            println!("{{\n \"archive\": \"{}\",", args[1]);
            println!(" \"encrypted\": {},", ar.encrypted);
            println!(" \"archive_size\": {},", ar.file_len);
            println!(" \"entry_count\": {},", ar.entries.len());
            println!(" \"entries\": [");
            for (i, e) in ar.entries.iter().enumerate() {
                println!(
                    "  {{\"name\": \"{}\", \"offset\": {}, \"size\": {}, \"broken\": {}}}{}",
                    e.name.replace('\\', "\\\\").replace('"', "\\\""),
                    e.offset,
                    e.size,
                    e.broken.is_some(),
                    if i + 1 == ar.entries.len() { "" } else { "," }
                );
            }
            println!(" ]\n}}");
        }
        Err(e) => {
            eprintln!("open failed: {e}");
            std::process::exit(1);
        }
    }
}
