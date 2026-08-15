pub mod iso9660;
pub mod udf;
pub mod tree;

pub use tree::TreeNode;

use std::fs::{File, create_dir_all};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::Path;

pub fn detect_and_parse_filesystem(file: &mut File, filename: &str) -> Result<TreeNode, Box<dyn std::error::Error>> {
    detect_and_parse_filesystem_verbose(file, filename, false)
}

pub fn detect_and_parse_filesystem_verbose(file: &mut File, filename: &str, verbose: bool) -> Result<TreeNode, Box<dyn std::error::Error>> {
    let mut errors = Vec::new();

    if verbose {
        // Show file size
        let file_size = file.seek(SeekFrom::End(0))?;
        file.seek(SeekFrom::Start(0))?;
        println!("File size: {} bytes ({:.2} GB)", file_size, file_size as f64 / (1024.0 * 1024.0 * 1024.0));
        
        // Show what's at key sectors
        println!("Scanning key sectors for filesystem signatures...");
        for (sector, desc) in [(16, "ISO 9660 PVD / UDF VRS"), (17, "UDF VRS"), (256, "UDF AVDP")].iter() {
            file.seek(SeekFrom::Start(*sector as u64 * 2048))?;
            let mut buf = [0u8; 32];
            if file.read_exact(&mut buf).is_ok() {
                let printable: String = buf.iter().map(|&b| if b >= 0x20 && b < 0x7f { b as char } else { '.' }).collect();
                println!("  Sector {:>3} ({}): {:02x} {:02x} {:02x} {:02x} {:02x} {:02x} {:02x} {:02x}  |{}|",
                    sector, desc, buf[0], buf[1], buf[2], buf[3], buf[4], buf[5], buf[6], buf[7], &printable[..8]);
            }
        }
        file.seek(SeekFrom::Start(0))?;
    }

    if verbose { println!("\nAttempting ISO 9660 parsing..."); }
    match iso9660::parse_iso9660_verbose(file, verbose) {
        Ok(root) => return Ok(root),
        Err(e) => {
            if verbose { println!("  ISO 9660 parsing failed: {}", e); }
            errors.push(format!("ISO 9660: {}", e));
        }
    }
    
    // Seek back to start before trying next parser
    file.seek(SeekFrom::Start(0))?;
    if verbose { println!("\nAttempting UDF parsing..."); }
    match udf::parse_udf_verbose(file, verbose) {
        Ok(root) => return Ok(root),
        Err(e) => {
            if verbose { println!("  UDF parsing failed: {}", e); }
            errors.push(format!("UDF: {}", e));
        }
    }
    
    let mut msg = format!("Unable to detect supported filesystem in {}", filename);
    if !errors.is_empty() {
        msg.push_str("\nDetails:\n  - ");
        msg.push_str(&errors.join("\n  - "));
    }
    
    Err(msg.into())
}

/// Write a file's contents from the ISO to the given writer (e.g. stdout).
pub fn cat_node<W: Write>(file: &mut File, node: &TreeNode, writer: &mut W) -> Result<(), Box<dyn std::error::Error>> {
    if node.is_directory {
        return Err(format!("'{}' is a directory, not a file", node.name).into());
    }
    if let (Some(location), Some(length)) = (node.file_location, node.file_length) {
        file.seek(SeekFrom::Start(location))?;
        let mut remaining = length as usize;
        let mut buffer = vec![0u8; EXTRACT_CHUNK_SIZE.min(remaining)];
        while remaining > 0 {
            let to_read = EXTRACT_CHUNK_SIZE.min(remaining);
            let buf = &mut buffer[..to_read];
            file.read_exact(buf)?;
            writer.write_all(buf)?;
            remaining -= to_read;
        }
        Ok(())
    } else {
        Err("File location information not available".into())
    }
}

pub fn extract_node(file: &mut File, node: &TreeNode, output_path: &str) -> Result<(), Box<dyn std::error::Error>> {
    if node.is_directory {
        if node.name == "/" {
            // For root directory, extract children directly to output path
            for child in &node.children {
                if child.is_directory {
                    extract_directory(file, child, output_path)?;
                } else {
                    extract_file(file, child, output_path)?;
                }
            }
        } else {
            extract_directory(file, node, output_path)?;
        }
    } else {
        extract_file(file, node, output_path)?;
    }
    Ok(())
}

const EXTRACT_CHUNK_SIZE: usize = 8 * 1024 * 1024; // 8 MB chunks

fn extract_file(file: &mut File, node: &TreeNode, output_path: &str) -> Result<(), Box<dyn std::error::Error>> {
    if let (Some(location), Some(length)) = (node.file_location, node.file_length) {
        file.seek(SeekFrom::Start(location))?;

        let output_file_path = Path::new(output_path).join(&node.name);
        if let Some(parent) = output_file_path.parent() {
            create_dir_all(parent)?;
        }

        let mut output_file = std::fs::File::create(&output_file_path)?;
        let mut remaining = length as usize;
        let mut buffer = vec![0u8; EXTRACT_CHUNK_SIZE.min(remaining)];

        while remaining > 0 {
            let to_read = EXTRACT_CHUNK_SIZE.min(remaining);
            let buf = &mut buffer[..to_read];
            file.read_exact(buf)?;
            output_file.write_all(buf)?;
            remaining -= to_read;

            // Print progress for large files (> 100 MB)
            if length > 100 * 1024 * 1024 {
                let done = length as usize - remaining;
                eprint!("\r  Extracting {}: {:.1}%", node.name, done as f64 / length as f64 * 100.0);
            }
        }
        if length > 100 * 1024 * 1024 {
            eprintln!();
        }

        println!("Extracted: {}", output_file_path.display());
    } else {
        return Err("File location information not available for extraction".into());
    }
    Ok(())
}

fn extract_directory(file: &mut File, node: &TreeNode, output_path: &str) -> Result<(), Box<dyn std::error::Error>> {
    let dir_path = Path::new(output_path).join(&node.name);
    create_dir_all(&dir_path)?;
    println!("Created directory: {}", dir_path.display());
    
    let dir_path_str = dir_path.to_str()
        .ok_or_else(|| format!("Non-UTF-8 path: {}", dir_path.display()))?;
    for child in &node.children {
        if child.is_directory {
            extract_directory(file, child, dir_path_str)?;
        } else {
            extract_file(file, child, dir_path_str)?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::File;
    use std::path::Path;

    fn test_file_path(filename: &str) -> String {
        format!("test_data/{}", filename)
    }

    fn require_test_file(name: &str) -> Option<File> {
        let path = test_file_path(name);
        if !Path::new(&path).exists() {
            eprintln!("Skipping test: {} not found (run `make test-data` to generate)", path);
            return None;
        }
        Some(File::open(&path).unwrap_or_else(|_| panic!("Failed to open test file: {}", path)))
    }

    fn parse_linux_iso() -> Option<(File, TreeNode)> {
        let mut file = require_test_file("test_linux.iso")?;
        let root = detect_and_parse_filesystem(&mut file, "test_linux.iso")
            .expect("Failed to parse test_linux.iso");
        Some((file, root))
    }

    fn parse_macos_iso() -> Option<(File, TreeNode)> {
        let mut file = require_test_file("test_macos.iso")?;
        let root = detect_and_parse_filesystem(&mut file, "test_macos.iso")
            .expect("Failed to parse test_macos.iso");
        Some((file, root))
    }

    // ---- Parsing & detection ----

    #[test]
    fn test_iso9660_parsing() {
        for test_file in &["test_linux.iso", "test_macos.iso"] {
            if let Some(mut file) = require_test_file(test_file) {
                let root = iso9660::parse_iso9660(&mut file)
                    .unwrap_or_else(|e| panic!("ISO 9660 parsing failed for {}: {}", test_file, e));
                assert_eq!(root.name, "/");
                assert!(root.is_directory);
                assert!(!root.children.is_empty(), "{} should have children", test_file);
            }
        }
    }

    #[test]
    fn test_filesystem_detection() {
        for test_file in &["test_linux.iso", "test_macos.iso"] {
            if let Some(mut file) = require_test_file(test_file) {
                let root = detect_and_parse_filesystem(&mut file, test_file)
                    .unwrap_or_else(|e| panic!("Filesystem detection failed for {}: {}", test_file, e));
                assert_eq!(root.name, "/");
                assert!(root.is_directory);
            }
        }
    }

    #[test]
    fn test_invalid_file_handling() {
        assert!(File::open(test_file_path("nonexistent.iso")).is_err());
    }

    #[test]
    fn test_garbage_data_rejected() {
        // Create a temp file with garbage data
        let dir = std::env::temp_dir().join("isomage_test");
        std::fs::create_dir_all(&dir).unwrap();
        let garbage_path = dir.join("garbage.iso");
        std::fs::write(&garbage_path, b"this is not an ISO file at all").unwrap();

        let mut file = File::open(&garbage_path).unwrap();
        let result = detect_and_parse_filesystem(&mut file, "garbage.iso");
        assert!(result.is_err(), "Garbage data should fail to parse");
        let err = result.unwrap_err().to_string();
        assert!(err.contains("Unable to detect"), "Error should mention detection failure, got: {}", err);

        std::fs::remove_file(&garbage_path).ok();
    }

    // ---- Linux ISO structure verification ----

    #[test]
    fn test_linux_iso_expected_directories() {
        if let Some((_file, root)) = parse_linux_iso() {
            for dir_name in &["boot", "etc", "home", "usr", "var"] {
                let node = root.find_node(dir_name)
                    .unwrap_or_else(|| panic!("Expected directory '{}' not found", dir_name));
                assert!(node.is_directory, "'{}' should be a directory", dir_name);
            }
        }
    }

    #[test]
    fn test_linux_iso_expected_files() {
        if let Some((_file, root)) = parse_linux_iso() {
            let expected_files = [
                "boot/grub.cfg",
                "etc/hostname",
                "etc/hosts",
                "home/user/.bashrc",
                "usr/bin/hello",
                "var/log/system.log",
            ];
            for path in &expected_files {
                let node = root.find_node(path)
                    .unwrap_or_else(|| panic!("Expected file '{}' not found", path));
                assert!(!node.is_directory, "'{}' should be a file", path);
                assert!(node.size > 0, "'{}' should have non-zero size", path);
                assert!(node.file_location.is_some(), "'{}' should have a file location", path);
                assert!(node.file_length.is_some(), "'{}' should have a file length", path);
            }
        }
    }

    #[test]
    fn test_linux_iso_nested_structure() {
        if let Some((_file, root)) = parse_linux_iso() {
            // Verify home/user/.bashrc exists through directory traversal
            let home = root.find_node("home").expect("home not found");
            assert!(home.is_directory);
            let user = home.find_node("user").expect("user not found in home");
            assert!(user.is_directory);
            let bashrc = user.find_node(".bashrc").expect(".bashrc not found in user");
            assert!(!bashrc.is_directory);
        }
    }

    // ---- macOS ISO structure verification ----

    #[test]
    fn test_macos_iso_expected_structure() {
        if let Some((_file, root)) = parse_macos_iso() {
            for dir_name in &["Applications", "System", "Users", "private"] {
                let node = root.find_node(dir_name)
                    .unwrap_or_else(|| panic!("Expected directory '{}' not found in macOS ISO", dir_name));
                assert!(node.is_directory);
            }

            let expected_files = [
                "Applications/readme.txt",
                "System/Library/info.txt",
                "Users/user/welcome.txt",
                "private/var/log/system.log",
            ];
            for path in &expected_files {
                let node = root.find_node(path)
                    .unwrap_or_else(|| panic!("Expected file '{}' not found in macOS ISO", path));
                assert!(!node.is_directory);
                assert!(node.size > 0);
            }
        }
    }

    // ---- Tree structure validation ----

    #[test]
    fn test_tree_structure_validation() {
        for (name, parser) in [("linux", parse_linux_iso as fn() -> Option<(File, TreeNode)>),
                                ("macos", parse_macos_iso)] {
            if let Some((_file, root)) = parser() {
                validate_tree_structure(&root, 0, name);
            }
        }
    }

    fn validate_tree_structure(node: &TreeNode, depth: usize, iso_name: &str) {
        assert!(!node.name.is_empty(), "Node name should not be empty in {}", iso_name);
        assert!(depth <= 10, "Tree depth exceeded limit in {}", iso_name);

        if !node.is_directory {
            assert!(node.children.is_empty(), "File '{}' should not have children in {}", node.name, iso_name);
        }

        for child in &node.children {
            validate_tree_structure(child, depth + 1, iso_name);
        }
    }

    // ---- TreeNode unit tests ----

    #[test]
    fn test_tree_node_creation() {
        let dir_node = TreeNode::new_directory("test_dir".to_string());
        assert!(dir_node.is_directory);
        assert_eq!(dir_node.name, "test_dir");
        assert_eq!(dir_node.size, 0);
        assert!(dir_node.children.is_empty());
        assert!(dir_node.file_location.is_none());

        let file_node = TreeNode::new_file("test_file.txt".to_string(), 1024);
        assert!(!file_node.is_directory);
        assert_eq!(file_node.name, "test_file.txt");
        assert_eq!(file_node.size, 1024);
        assert!(file_node.file_location.is_none());

        let located = TreeNode::new_file_with_location("f.bin".to_string(), 512, 4096, 512);
        assert_eq!(located.file_location, Some(4096));
        assert_eq!(located.file_length, Some(512));
    }

    #[test]
    fn test_directory_size_calculation() {
        let mut root = TreeNode::new_directory("root".to_string());
        root.add_child(TreeNode::new_file("file1.txt".to_string(), 100));
        root.add_child(TreeNode::new_file("file2.txt".to_string(), 200));

        let mut subdir = TreeNode::new_directory("subdir".to_string());
        subdir.add_child(TreeNode::new_file("file3.txt".to_string(), 300));
        root.add_child(subdir);

        root.calculate_directory_size();

        assert_eq!(root.size, 600);
        // Subdir should also have its size calculated
        let sub = root.find_node("subdir").unwrap();
        assert_eq!(sub.size, 300);
    }

    // ---- find_node edge cases ----

    #[test]
    fn test_find_node_with_leading_slash() {
        if let Some((_file, root)) = parse_linux_iso() {
            // Leading slash should be stripped
            assert!(root.find_node("/etc/hostname").is_some());
            assert!(root.find_node("etc/hostname").is_some());
            // Multiple leading slashes
            assert!(root.find_node("///etc/hostname").is_some());
        }
    }

    #[test]
    fn test_find_node_root_paths() {
        if let Some((_file, root)) = parse_linux_iso() {
            // Empty path and "/" both return root
            let by_empty = root.find_node("").unwrap();
            assert_eq!(by_empty.name, "/");
            let by_slash = root.find_node("/").unwrap();
            assert_eq!(by_slash.name, "/");
        }
    }

    #[test]
    fn test_find_node_nonexistent() {
        if let Some((_file, root)) = parse_linux_iso() {
            assert!(root.find_node("nonexistent").is_none());
            assert!(root.find_node("etc/nonexistent").is_none());
            assert!(root.find_node("a/b/c/d/e/f").is_none());
        }
    }

    // ---- cat tests ----

    #[test]
    fn test_cat_file_to_buffer() {
        if let Some((mut file, root)) = parse_linux_iso() {
            let node = root.find_node("etc/hostname")
                .expect("etc/hostname not found");

            let mut output = Vec::new();
            cat_node(&mut file, node, &mut output).expect("cat_node failed");

            let content = String::from_utf8(output).expect("Not valid UTF-8");
            assert!(content.contains("test-linux-system"),
                "Expected hostname content, got: {:?}", content);
        }
    }

    #[test]
    fn test_cat_preserves_exact_bytes() {
        if let Some((mut file, root)) = parse_linux_iso() {
            let node = root.find_node("etc/hostname").expect("etc/hostname not found");

            let mut output = Vec::new();
            cat_node(&mut file, node, &mut output).expect("cat_node failed");

            // Output length should match the node's reported size
            assert_eq!(output.len() as u64, node.size,
                "cat output length {} doesn't match node size {}", output.len(), node.size);
        }
    }

    #[test]
    fn test_cat_rejects_directory() {
        if let Some((mut file, root)) = parse_linux_iso() {
            let node = root.find_node("etc").expect("etc/ not found");

            let mut output = Vec::new();
            let result = cat_node(&mut file, node, &mut output);
            assert!(result.is_err());
            assert!(result.unwrap_err().to_string().contains("directory"));
            assert!(output.is_empty(), "No bytes should be written for a directory");
        }
    }

    #[test]
    fn test_cat_node_without_location() {
        let node = TreeNode::new_file("orphan.txt".to_string(), 100);
        // Create a dummy file to pass as the ISO (won't be read)
        let dir = std::env::temp_dir().join("isomage_test");
        std::fs::create_dir_all(&dir).unwrap();
        let dummy_path = dir.join("dummy.bin");
        std::fs::write(&dummy_path, b"x").unwrap();
        let mut file = File::open(&dummy_path).unwrap();

        let mut output = Vec::new();
        let result = cat_node(&mut file, &node, &mut output);
        assert!(result.is_err(), "cat on file without location should error");
        assert!(result.unwrap_err().to_string().contains("not available"));

        std::fs::remove_file(&dummy_path).ok();
    }

    #[test]
    fn test_cat_every_file_in_linux_iso() {
        if let Some((mut file, root)) = parse_linux_iso() {
            let files = [
                ("boot/grub.cfg", "GRUB"),
                ("etc/hostname", "test-linux-system"),
                ("etc/hosts", "127.0.0.1"),
                ("home/user/.bashrc", "Bash"),
                ("usr/bin/hello", "Hello World"),
                ("var/log/system.log", "System started"),
            ];
            for (path, expected) in &files {
                let node = root.find_node(path)
                    .unwrap_or_else(|| panic!("{} not found", path));
                let mut output = Vec::new();
                cat_node(&mut file, node, &mut output)
                    .unwrap_or_else(|e| panic!("cat failed for {}: {}", path, e));
                let content = String::from_utf8(output).expect("Not valid UTF-8");
                assert!(content.contains(expected),
                    "Expected '{}' in {}, got: {:?}", expected, path, content);
            }
        }
    }

    #[test]
    fn test_cat_every_file_in_macos_iso() {
        if let Some((mut file, root)) = parse_macos_iso() {
            let files = [
                ("Applications/readme.txt", "Application Data"),
                ("System/Library/info.txt", "System Library"),
                ("Users/user/welcome.txt", "Welcome to macOS"),
                ("private/var/log/system.log", "macOS system log"),
            ];
            for (path, expected) in &files {
                let node = root.find_node(path)
                    .unwrap_or_else(|| panic!("{} not found in macOS ISO", path));
                let mut output = Vec::new();
                cat_node(&mut file, node, &mut output)
                    .unwrap_or_else(|e| panic!("cat failed for {}: {}", path, e));
                let content = String::from_utf8(output).expect("Not valid UTF-8");
                assert!(content.contains(expected),
                    "Expected '{}' in {}, got: {:?}", expected, path, content);
            }
        }
    }

    // ---- extraction tests ----

    #[test]
    fn test_extract_single_file() {
        if let Some((mut file, root)) = parse_linux_iso() {
            let dir = std::env::temp_dir().join("isomage_test_extract_single");
            let _ = std::fs::remove_dir_all(&dir);
            std::fs::create_dir_all(&dir).unwrap();

            let node = root.find_node("etc/hostname").expect("etc/hostname not found");
            extract_node(&mut file, node, dir.to_str().unwrap()).expect("extract failed");

            let extracted = std::fs::read_to_string(dir.join("hostname")).unwrap();
            assert!(extracted.contains("test-linux-system"));

            std::fs::remove_dir_all(&dir).ok();
        }
    }

    #[test]
    fn test_extract_directory() {
        if let Some((mut file, root)) = parse_linux_iso() {
            let dir = std::env::temp_dir().join("isomage_test_extract_dir");
            let _ = std::fs::remove_dir_all(&dir);
            std::fs::create_dir_all(&dir).unwrap();

            let node = root.find_node("etc").expect("etc not found");
            extract_node(&mut file, node, dir.to_str().unwrap()).expect("extract failed");

            // Should create etc/ subdirectory with both files
            assert!(dir.join("etc/hostname").exists(), "hostname should exist");
            assert!(dir.join("etc/hosts").exists(), "hosts should exist");

            let hostname = std::fs::read_to_string(dir.join("etc/hostname")).unwrap();
            assert!(hostname.contains("test-linux-system"));

            std::fs::remove_dir_all(&dir).ok();
        }
    }

    #[test]
    fn test_extract_root() {
        if let Some((mut file, root)) = parse_linux_iso() {
            let dir = std::env::temp_dir().join("isomage_test_extract_root");
            let _ = std::fs::remove_dir_all(&dir);
            std::fs::create_dir_all(&dir).unwrap();

            extract_node(&mut file, &root, dir.to_str().unwrap()).expect("extract root failed");

            // All top-level dirs should exist
            for name in &["boot", "etc", "home", "usr", "var"] {
                assert!(dir.join(name).is_dir(), "{} directory should exist", name);
            }
            // Deep file should exist
            assert!(dir.join("home/user/.bashrc").exists(), ".bashrc should exist");

            std::fs::remove_dir_all(&dir).ok();
        }
    }

    #[test]
    fn test_extract_matches_cat() {
        if let Some((mut file, root)) = parse_linux_iso() {
            let dir = std::env::temp_dir().join("isomage_test_extract_vs_cat");
            let _ = std::fs::remove_dir_all(&dir);
            std::fs::create_dir_all(&dir).unwrap();

            let node = root.find_node("etc/hosts").expect("etc/hosts not found");

            // Get cat output
            let mut cat_output = Vec::new();
            cat_node(&mut file, node, &mut cat_output).expect("cat failed");

            // Extract to disk
            extract_node(&mut file, node, dir.to_str().unwrap()).expect("extract failed");
            let extracted = std::fs::read(dir.join("hosts")).unwrap();

            assert_eq!(cat_output, extracted, "cat and extract should produce identical bytes");

            std::fs::remove_dir_all(&dir).ok();
        }
    }
}