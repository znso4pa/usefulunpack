// ╔══════════════════════════════════════════════════════════════╗
// ║  upk — UsefulUnpack CLI  v1.0                                ║
// ║  Author: znso4pa (锌帕)                                       ║
// ║  Thin wrapper around xp3-unpacker & pfs_unpacker             ║
// ╚══════════════════════════════════════════════════════════════╝

use std::env;
use std::process::{Command, exit};

fn main() {
    let args: Vec<String> = env::args().collect();
    if args.len() < 2 { usage(); exit(1); }

    let cmd = &args[1];
    let rest: Vec<&str> = args.iter().skip(2).map(|s| s.as_str()).collect();

    let (tool, tool_args) = match detect_format(&rest) {
        "XP3" => ("xp3-unpacker", rest.as_slice()),
        "PFS" => match cmd.as_str() {
            "info"|"i" => ("pfs_unpacker", &["info", &rest[0]][..]),
            "list"|"ls" => ("pfs_unpacker", &["info", &rest[0]][..]),
            "x"|"extract" => ("pfs_unpacker", &["unpack", &rest[0], &rest[1]][..]),
            _ => { usage(); exit(1); }
        },
        _ => { eprintln!("Unsupported format. Use .xp3 or .pfs files."); exit(1); }
    };

    let status = Command::new(tool).args(tool_args).status().unwrap_or_else(|e| {
        eprintln!("Failed: {e}"); exit(1);
    });
    exit(status.code().unwrap_or(1));
}

fn detect_format(args: &[&str]) -> &'static str {
    let f = args.first().map(|s| s.to_lowercase()).unwrap_or_default();
    if f.ends_with(".xp3") { "XP3" } else if f.ends_with(".pfs") { "PFS" } else { "?" }
}

fn usage() {
    println!(r#"upk v1.0 — znso4pa (锌帕)
  upk info  <file>           Show archive info
  upk list  <file>           List files
  upk x     <file> <dir>     Extract

  Supports: XP3 (.xp3), PFS (.pfs)
"#);
}
