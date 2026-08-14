//! CLI harness to run the scan-core scanner on real files (dev tool).
//! Usage: cargo run -p archive_scan-core --bin scan_cli -- <file>...

use archive_scan_core::scan_file_json;

fn main() {
    for arg in std::env::args().skip(1) {
        let start = std::time::Instant::now();
        match scan_file_json(&arg) {
            Ok(json) => println!("=== {arg} ({:?}) ===\n{json}", start.elapsed()),
            Err(e) => eprintln!("{arg}: ERROR {e}"),
        }
    }
}
