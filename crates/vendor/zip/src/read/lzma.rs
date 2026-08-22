use std::io::{Read, Result};

#[derive(Debug)]
pub struct LzmaDecoder<R> {
    original_reader: R,
    decompressed: std::io::Cursor<Vec<u8>>,
}

impl<R: Read> LzmaDecoder<R> {
    pub fn new(inner: R) -> Self {
        let mut all_data = Vec::new();
        let mut buf_reader = std::io::BufReader::new(inner);
        buf_reader.read_to_end(&mut all_data).ok();

        // ZIP-LZMA (method 14) format:
        // [version(2)][props_size(2, u16 LE)][properties(props_size)][compressed_data]
        // Properties: [props_byte][dict_size(4, u32 LE)]
        // The compressed data is RAW LZMA1 stream (no header).
        // Use lzma_raw_decoder with LZMA_FILTER_LZMA1 to decode.
        let decompressed = if all_data.len() >= 4 + 5 {
            let props_size = u16::from_le_bytes([all_data[2], all_data[3]]) as usize;
            if props_size == 5 && all_data.len() >= 4 + props_size {
                let props_byte = all_data[4];
                let dict_size = u32::from_le_bytes([
                    all_data[5], all_data[6], all_data[7], all_data[8],
                ]);
                let compressed = &all_data[4 + props_size..];
                decompress_lzma_raw(compressed, props_byte, dict_size)
            } else {
                decompress_lzma_alone(&all_data)
            }
        } else {
            Vec::new()
        };

        LzmaDecoder {
            original_reader: buf_reader.into_inner(),
            decompressed: std::io::Cursor::new(decompressed),
        }
    }

    pub fn into_inner(self) -> R {
        self.original_reader
    }
}

impl<R> Read for LzmaDecoder<R> {
    fn read(&mut self, buf: &mut [u8]) -> Result<usize> {
        self.decompressed.read(buf)
    }
}

/// Decompress raw LZMA1 data using lzma-sys `lzma_raw_decoder` with
/// `LZMA_FILTER_LZMA1` filter. Properties (lc, lp, pb, dict_size) come
/// from the ZIP-LZMA header, NOT from the compressed data stream.
fn decompress_lzma_raw(compressed: &[u8], props_byte: u8, dict_size: u32) -> Vec<u8> {
    unsafe {
        let lc = props_byte % 9;
        let lp = (props_byte / 9) % 5;
        let pb = props_byte / 45;

        let mut options: lzma_sys::lzma_options_lzma = std::mem::zeroed();
        options.lc = lc as u32;
        options.lp = lp as u32;
        options.pb = pb as u32;
        options.dict_size = dict_size;

        let filter = lzma_sys::lzma_filter {
            id: lzma_sys::LZMA_FILTER_LZMA1,
            options: &mut options as *mut _ as *mut std::ffi::c_void,
        };
        let filters = [
            filter,
            lzma_sys::lzma_filter {
                id: lzma_sys::LZMA_VLI_UNKNOWN,
                options: std::ptr::null_mut(),
            },
        ];

        let mut strm: lzma_sys::lzma_stream = std::mem::zeroed();
        let ret = lzma_sys::lzma_raw_decoder(&mut strm, filters.as_ptr());
        if ret != lzma_sys::LZMA_OK {
            return Vec::new();
        }

        let mut out = Vec::with_capacity(compressed.len() * 4);
        let mut in_pos: usize = 0;
        let mut out_pos: usize = 0;
        loop {
            let action = if in_pos >= compressed.len() {
                lzma_sys::LZMA_FINISH
            } else {
                lzma_sys::LZMA_RUN
            };
            strm.next_in = compressed[in_pos..].as_ptr();
            strm.avail_in = compressed.len() - in_pos;
            if out_pos >= out.len() {
                out.resize(out.len() + 65536, 0u8);
            }
            strm.next_out = out[out_pos..].as_mut_ptr();
            strm.avail_out = out.len() - out_pos;
            let ret = lzma_sys::lzma_code(&mut strm, action);
            let written = out.len() - out_pos - strm.avail_out;
            out_pos += written;
            in_pos = compressed.len() - strm.avail_in;
            match ret {
                lzma_sys::LZMA_OK => { out.resize(out.len() + 65536, 0u8); }
                lzma_sys::LZMA_STREAM_END | lzma_sys::LZMA_BUF_ERROR => break,
                _ => { lzma_sys::lzma_end(&mut strm); return Vec::new(); }
            }
        }
        lzma_sys::lzma_end(&mut strm);
        out.truncate(out_pos);
        out
    }
}

/// Decompress LZMA alone format data using liblzma (fallback).
fn decompress_lzma_alone(data: &[u8]) -> Vec<u8> {
    unsafe {
        let mut strm: lzma_sys::lzma_stream = std::mem::zeroed();
        let ret = lzma_sys::lzma_alone_decoder(&mut strm, 256 * 1024 * 1024);
        if ret != lzma_sys::LZMA_OK {
            return Vec::new();
        }
        let mut out = Vec::with_capacity(data.len() * 4);
        let mut in_pos: usize = 0;
        let mut out_pos: usize = 0;
        loop {
            let action = if in_pos >= data.len() {
                lzma_sys::LZMA_FINISH
            } else {
                lzma_sys::LZMA_RUN
            };
            strm.next_in = data[in_pos..].as_ptr();
            strm.avail_in = data.len() - in_pos;
            if out_pos >= out.len() {
                out.resize(out.len() + 65536, 0u8);
            }
            strm.next_out = out[out_pos..].as_mut_ptr();
            strm.avail_out = out.len() - out_pos;
            let ret = lzma_sys::lzma_code(&mut strm, action);
            let written = out.len() - out_pos - strm.avail_out;
            out_pos += written;
            in_pos = data.len() - strm.avail_in;
            match ret {
                lzma_sys::LZMA_OK => { out.resize(out.len() + 65536, 0u8); }
                lzma_sys::LZMA_STREAM_END | lzma_sys::LZMA_BUF_ERROR => break,
                _ => { lzma_sys::lzma_end(&mut strm); return Vec::new(); }
            }
        }
        lzma_sys::lzma_end(&mut strm);
        out.truncate(out_pos);
        out
    }
}
