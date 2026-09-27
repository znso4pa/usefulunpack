//! File content processors.

use crate::format::{self, FileType};

pub(crate) mod common;
pub mod dump;
pub(crate) mod exe;
pub(crate) mod pbd;
pub(crate) mod scn;
mod script;
pub(crate) mod tjs;
pub(crate) mod tlg;

#[derive(Debug, Clone)]
pub struct ProcessedFile {
    pub data: Vec<u8>,
    pub file_type: FileType,
}

// Detects and decodes a recovered file when a processor applies.
pub(crate) fn process_file(data: Vec<u8>) -> ProcessedFile {
    let detected_type = format::detect(&data);
    let mut data = data;
    let mut file_type = detected_type;

    if detected_type == FileType::Script
        && let Some(processed) = script::process(&data)
    {
        data = processed;
        file_type = format::detect(&data);
    }

    ProcessedFile { data, file_type }
}
