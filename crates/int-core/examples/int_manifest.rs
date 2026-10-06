//! Out-of-tree harness: dumps an INT archive's index as JSON, so a real game
//! archive can be checked against an independent tool without committing the
//! sample. Usage: `manifest <archive.int>` (the game's .exe must sit next to
//! it — the index key is derived from its resources).
fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 2 {
        eprintln!("usage: manifest <archive.int>");
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
