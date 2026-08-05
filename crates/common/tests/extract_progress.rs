use archive_common::extract_progress;
use std::sync::Mutex;

// The progress statics are process-wide; serialize tests to avoid interference.
static LOCK: Mutex<()> = Mutex::new(());

#[test]
fn reset_clears_state() {
    let _g = LOCK.lock().unwrap();
    extract_progress::reset(1024);
    extract_progress::add_bytes(256);
    extract_progress::cancel();
    extract_progress::reset(2048);
    assert_eq!(extract_progress::bytes(), 0);
    assert_eq!(extract_progress::total_bytes(), 2048);
    assert!(!extract_progress::cancelled());
    assert_eq!(extract_progress::name(), "");
}

#[test]
fn add_bytes_accumulates_and_sets_name() {
    let _g = LOCK.lock().unwrap();
    extract_progress::reset(100);
    extract_progress::set_name("first.bin");
    extract_progress::add_bytes(40);
    extract_progress::add_bytes(35);
    assert_eq!(extract_progress::bytes(), 75);
    assert_eq!(extract_progress::name(), "first.bin");
    extract_progress::set_name("second.dat");
    extract_progress::add_bytes(25);
    assert_eq!(extract_progress::bytes(), 100);
    assert_eq!(extract_progress::name(), "second.dat");
}

#[test]
fn cancel_flag_is_set_and_cleared_by_reset() {
    let _g = LOCK.lock().unwrap();
    extract_progress::reset(1);
    assert!(!extract_progress::cancelled());
    extract_progress::cancel();
    assert!(extract_progress::cancelled());
    extract_progress::reset(1);
    assert!(!extract_progress::cancelled());
}
