//! Diagnostic dump for a real `Game.rgss*` archive.
//!
//! Used for on-device verification: pull the file off the phone, run this
//! against it, and compare the output with uuksu's / Petschko's (the tools the
//! community treats as ground truth). For an archive it prints the parsed index
//! plus a magic sniff of each decrypted payload, which is what actually proves
//! the cipher and the offset table are right — names alone can look plausible
//! while every payload is garbage. For an MV/MZ asset it prints the recovered
//! name and size.
//!
//! ```sh
//! cargo run -p archive_rgss-core --example probe -- Game.rgss3a [max_entries]
//! ```

use archive_rgss_core::{mv_list, open, RgssEntry};
use std::io::{Read, Seek, SeekFrom};

fn sniff(dir: &std::path::Path, e: &RgssEntry) -> String {
    let Ok(mut f) = std::fs::File::open(dir) else { return "?".into() };
    if f.seek(SeekFrom::Start(e.offset)).is_err() || e.size == 0 { return "?".into(); }
    let mut head = vec![0u8; e.size.min(16) as usize];
    if f.read_exact(&mut head).is_err() { return "?".into(); }
    let mut key = e.data_key;
    let mut pos = 0u8;
    for b in head.iter_mut() {
        *b ^= key.to_le_bytes()[pos as usize];
        pos += 1;
        if pos == 4 { pos = 0; key = key.wrapping_mul(7).wrapping_add(3); }
    }
    let magic: String = head.iter().map(|&b| {
        if (0x20..=0x7E).contains(&b) { b as char } else { '.' }
    }).collect();
    let known: &[(&[u8], &str)] = &[
        (b"\x89PNG", "png"), (b"OggS", "ogg"), (b"RIFF", "wav/avi"), (b"BM", "bmp"),
        (b"ID3", "mp3"), (b"\xff\xfb", "mp3"), (b"RIFF", "wav"), (b"GM8", "mid"),
        (b"fLaC", "flac"), (b"\x1a\x45\xdf\xa3", "webm"), (b"MZ", "pe"),
        (b"\x00\x01\x00\x00\x00", "marshal"), (b"Z", "compressed marshal"),
    ];
    let tag = known.iter()
        .find(|(m, _)| head.starts_with(m))
        .map(|(_, n)| *n)
        .unwrap_or("?");
    format!("{tag:16} [{magic}]")
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let Some(path) = args.get(1) else {
        eprintln!("usage: probe <Game.rgss3a | something.rpgmvp> [max_entries]");
        std::process::exit(2);
    };
    let limit: usize = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(40);

    // An MV/MZ asset is not an archive, but it is presented as a one-entry one,
    // so the same listing call answers both.
    if let Ok(listing) = mv_list(path) {
        println!("RPG Maker MV/MZ asset: {path}");
        println!("size    : {} bytes", std::fs::metadata(path).map(|m| m.len()).unwrap_or(0));
        println!("{listing}");
        return;
    }

    let archive = match open(path) {
        Ok(a) => a,
        Err(e) => { eprintln!("open failed: {e}"); std::process::exit(1) }
    };
    let dir = std::path::Path::new(path).parent().unwrap_or(std::path::Path::new("."));
    println!("file      : {path}");
    println!("size      : {} bytes", archive.file_size);
    println!("version   : {}", archive.version);
    println!("entries   : {}", archive.entries.len());
    println!("payload   : {} bytes", archive.total_bytes());
    println!();
    for e in archive.entries.iter().take(limit) {
        println!("{:>10}  {:<52}  {}", e.size, e.name, sniff(dir, e));
    }
    if archive.entries.len() > limit {
        println!("... and {} more", archive.entries.len() - limit);
    }
}
