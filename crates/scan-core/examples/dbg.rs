fn main() {
    let p = std::env::args().nth(1).unwrap();
    match archive_scan_core::scan_file_json(&p) {
        Ok(j) => println!("{}", &j[..j.len().min(600)]),
        Err(e) => println!("ERR {e}"),
    }
}
