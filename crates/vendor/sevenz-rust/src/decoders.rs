use std::io::Read;

use byteorder::{LittleEndian, ReadBytesExt};
#[cfg(feature = "bzip2")]
use bzip2::read::BzDecoder;

#[cfg(feature = "aes256")]
use crate::aes256sha256::Aes256Sha256Decoder;
use crate::{
    archive::SevenZMethod,
    bcj::SimpleReader,
    delta::DeltaReader,
    error::Error,
    folder::Coder,
};

/// liblzma-backed LZMA1/LZMA2 raw decoder (streaming). Replaces the pure-Rust
/// lzma-rust decoders (~4x decompression speed; only this file is modified
/// relative to upstream sevenz-rust 0.6.1).
pub(crate) struct LzmaRawDecoder<R: Read> {
    input: R,
    stream: lzma_sys::lzma_stream,
    inbuf: Box<[u8]>,
    input_done: bool,
    finished: bool,
}

impl<R: Read> LzmaRawDecoder<R> {
    fn new(input: R, filters: &[lzma_sys::lzma_filter]) -> Result<Self, Error> {
        let mut stream: lzma_sys::lzma_stream = unsafe { std::mem::zeroed() };
        let ret = unsafe { lzma_sys::lzma_raw_decoder(&mut stream, filters.as_ptr()) };
        if ret != lzma_sys::LZMA_OK {
            return Err(Error::Other(format!("lzma_raw_decoder failed: {ret}").into()));
        }
        Ok(Self {
            input,
            stream,
            inbuf: vec![0u8; 64 * 1024].into_boxed_slice(),
            input_done: false,
            finished: false,
        })
    }
}

impl<R: Read> Drop for LzmaRawDecoder<R> {
    fn drop(&mut self) {
        unsafe { lzma_sys::lzma_end(&mut self.stream) };
    }
}

impl<R: Read> Read for LzmaRawDecoder<R> {
    fn read(&mut self, out: &mut [u8]) -> std::io::Result<usize> {
        if out.is_empty() || self.finished {
            return Ok(0);
        }
        loop {
            if self.stream.avail_in == 0 && !self.input_done {
                let n = self.input.read(&mut self.inbuf)?;
                self.input_done = n == 0;
                self.stream.next_in = self.inbuf.as_ptr();
                self.stream.avail_in = n;
            }
            self.stream.next_out = out.as_mut_ptr();
            self.stream.avail_out = out.len();
            let action = if self.input_done {
                lzma_sys::LZMA_FINISH
            } else {
                lzma_sys::LZMA_RUN
            };
            let ret = unsafe { lzma_sys::lzma_code(&mut self.stream, action) };
            let produced = out.len() - self.stream.avail_out;
            if produced > 0 {
                return Ok(produced);
            }
            match ret {
                lzma_sys::LZMA_STREAM_END => {
                    self.finished = true;
                    return Ok(0);
                }
                lzma_sys::LZMA_OK => {
                    if self.input_done && self.stream.avail_in == 0 {
                        return Err(std::io::Error::new(
                            std::io::ErrorKind::UnexpectedEof,
                            "truncated LZMA stream",
                        ));
                    }
                }
                lzma_sys::LZMA_BUF_ERROR => {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::UnexpectedEof,
                        "truncated LZMA stream",
                    ));
                }
                _ => return Err(std::io::Error::other(format!("lzma_code error: {ret}"))),
            }
        }
    }
}

/// Build an LZMA2 raw-decoder filter chain. `opt` must live in the caller's
/// frame for the duration of the `lzma_raw_decoder`/`lzma_code` calls
/// (liblzma reads the options during init, so it only needs to stay valid
/// through `LzmaRawDecoder::new`).
fn lzma2_filters(dict_size: u32, opt: &mut lzma_sys::lzma_options_lzma) -> [lzma_sys::lzma_filter; 2] {
    *opt = unsafe { std::mem::zeroed() };
    opt.dict_size = dict_size;
    [
        lzma_sys::lzma_filter {
            id: lzma_sys::LZMA_FILTER_LZMA2,
            options: opt as *mut _ as *mut std::ffi::c_void,
        },
        lzma_sys::lzma_filter {
            id: lzma_sys::LZMA_VLI_UNKNOWN,
            options: std::ptr::null_mut(),
        },
    ]
}

fn lzma1_filters(props: u8, dict_size: u32, opt: &mut lzma_sys::lzma_options_lzma) -> [lzma_sys::lzma_filter; 2] {
    *opt = unsafe { std::mem::zeroed() };
    opt.dict_size = dict_size;
    opt.lc = (props % 9) as u32;
    opt.lp = ((props / 9) % 5) as u32;
    opt.pb = (props / 45) as u32;
    [
        lzma_sys::lzma_filter {
            id: lzma_sys::LZMA_FILTER_LZMA1,
            options: opt as *mut _ as *mut std::ffi::c_void,
        },
        lzma_sys::lzma_filter {
            id: lzma_sys::LZMA_VLI_UNKNOWN,
            options: std::ptr::null_mut(),
        },
    ]
}

pub enum Decoder<R: Read> {
    COPY(R),
    LZMA(LzmaRawDecoder<R>),
    LZMA2(LzmaRawDecoder<R>),
    BCJ(SimpleReader<R>),
    Delta(DeltaReader<R>),
    #[cfg(feature = "zstd")]
    ZSTD(zstd::Decoder<'static, std::io::BufReader<R>>),
    #[cfg(feature = "bzip2")]
    BZip2(BzDecoder<R>),
    #[cfg(feature = "aes256")]
    AES256SHA256(Aes256Sha256Decoder<R>),
    PPMD(ppmd_rust::Ppmd7Decoder<R>),
}

// impl<R: Read> Decoder<R> {
//     pub fn num_streams(&self) -> usize {
//         match self {
//             Self::BCJ(_) => 4,
//             _ => 1,
//         }
//     }
// }

impl<R: Read> Read for Decoder<R> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        match self {
            #[cfg(feature = "zstd")]
            Decoder::ZSTD(r) => r.read(buf),
            Decoder::COPY(r) => r.read(buf),
            Decoder::LZMA(r) => r.read(buf),
            Decoder::LZMA2(r) => r.read(buf),
            Decoder::BCJ(r) => r.read(buf),
            Decoder::Delta(r) => r.read(buf),
            #[cfg(feature = "bzip2")]
            Decoder::BZip2(r) => r.read(buf),
            #[cfg(feature = "aes256")]
            Decoder::AES256SHA256(r) => r.read(buf),
            Decoder::PPMD(r) => r.read(buf),
        }
    }
}

pub fn add_decoder<I: Read>(
    input: I,
    #[allow(unused)] uncompressed_len: usize,
    coder: &Coder,
    #[allow(unused)] password: &[u8],
    max_mem_limit_kb: usize,
) -> Result<Decoder<I>, Error> {
    let method = SevenZMethod::by_id(coder.decompression_method_id());
    let method = if let Some(m) = method {
        m
    } else {
        return Err(Error::UnsupportedCompressionMethod(format!(
            "{:?}",
            coder.decompression_method_id()
        )));
    };
    match method.id() {
        SevenZMethod::ID_COPY => Ok(Decoder::COPY(input)),
        #[cfg(feature = "zstd")]
        SevenZMethod::ID_ZSTD => {
            let zs = zstd::Decoder::new(input).unwrap();
            Ok(Decoder::ZSTD(zs))
        }
        SevenZMethod::ID_LZMA => {
            if coder.properties.len() < 5 {
                return Err(Error::Other("LZMA properties too short".into()));
            }
            let dict_size = get_lzma_dic_size(coder)?;
            let props = coder.properties[0];
            let mut opt: lzma_sys::lzma_options_lzma = unsafe { std::mem::zeroed() };
            let filters = lzma1_filters(props, dict_size, &mut opt);
            let mem_bytes = unsafe { lzma_sys::lzma_raw_decoder_memusage(filters.as_ptr()) };
            if mem_bytes > 0 && mem_bytes > (max_mem_limit_kb as u64) * 1024 {
                return Err(Error::MaxMemLimited {
                    max_kb: max_mem_limit_kb,
                    actaul_kb: (mem_bytes / 1024) as usize,
                });
            }
            let dec = LzmaRawDecoder::new(input, &filters)?;
            Ok(Decoder::LZMA(dec))
        }
        SevenZMethod::ID_LZMA2 => {
            let dic_size = get_lzma2_dic_size(coder)?;
            let mut opt: lzma_sys::lzma_options_lzma = unsafe { std::mem::zeroed() };
            let filters = lzma2_filters(dic_size, &mut opt);
            let mem_bytes = unsafe { lzma_sys::lzma_raw_decoder_memusage(filters.as_ptr()) };
            if mem_bytes > 0 && mem_bytes > (max_mem_limit_kb as u64) * 1024 {
                return Err(Error::MaxMemLimited {
                    max_kb: max_mem_limit_kb,
                    actaul_kb: (mem_bytes / 1024) as usize,
                });
            }
            let dec = LzmaRawDecoder::new(input, &filters)?;
            Ok(Decoder::LZMA2(dec))
        }
        SevenZMethod::ID_BCJ_X86 => {
            let de = SimpleReader::new_x86(input);
            Ok(Decoder::BCJ(de))
        }
        SevenZMethod::ID_BCJ_ARM => {
            let de = SimpleReader::new_arm(input);
            Ok(Decoder::BCJ(de))
        }
        SevenZMethod::ID_BCJ_ARM_THUMB => {
            let de = SimpleReader::new_arm_thumb(input);
            Ok(Decoder::BCJ(de))
        }
        SevenZMethod::ID_BCJ_PPC => {
            let de = SimpleReader::new_ppc(input);
            Ok(Decoder::BCJ(de))
        }
        SevenZMethod::ID_BCJ_SPARC => {
            let de = SimpleReader::new_sparc(input);
            Ok(Decoder::BCJ(de))
        }
        SevenZMethod::ID_DELTA => {
            let d = if coder.properties.is_empty() {
                1
            } else {
                coder.properties[0].wrapping_add(1)
            };
            let de = DeltaReader::new(input, d as usize);
            Ok(Decoder::Delta(de))
        }
        #[cfg(feature = "bzip2")]
        SevenZMethod::ID_BZIP2 => {
            let de = BzDecoder::new(input);
            Ok(Decoder::BZip2(de))
        }
        SevenZMethod::ID_PPMD_H => {
            // PPMd H/J properties: order(1) + mem_size(4, u32 LE)
            let props = &coder.properties;
            if props.len() < 5 {
                return Err(Error::Other(std::borrow::Cow::Owned(format!(
                    "PPMd properties too short: {} bytes", props.len()
                ))));
            }
            let order = props[0] as u32;
            let mem_size = u32::from_le_bytes([props[1], props[2], props[3], props[4]]);
            let order = order.max(ppmd_rust::PPMD7_MIN_ORDER).min(ppmd_rust::PPMD7_MAX_ORDER);
            let mem_size = mem_size.max(ppmd_rust::PPMD7_MIN_MEM_SIZE).min(ppmd_rust::PPMD7_MAX_MEM_SIZE);
            let de = ppmd_rust::Ppmd7Decoder::new(input, order, mem_size)
                .map_err(|e| Error::Other(std::borrow::Cow::Owned(format!("PPMd init: {e}"))))?;
            Ok(Decoder::PPMD(de))
        }
        #[cfg(feature = "aes256")]
        SevenZMethod::ID_AES256SHA256 => {
            if password.is_empty() {
                return Err(Error::PasswordRequired);
            }
            let de = Aes256Sha256Decoder::new(input, &coder.properties, password)?;
            Ok(Decoder::AES256SHA256(de))
        }
        _ => Err(Error::UnsupportedCompressionMethod(
            method.name().to_string(),
        )),
    }
}

#[inline]
fn get_lzma2_dic_size(coder: &Coder) -> Result<u32, Error> {
    if coder.properties.is_empty() {
        return Err(Error::other("LZMA2 properties too short"));
    }
    let dict_size_bits = 0xff & coder.properties[0] as u32;
    if (dict_size_bits & (!0x3f)) != 0 {
        return Err(Error::other("Unsupported LZMA2 property bits"));
    }
    if dict_size_bits > 40 {
        return Err(Error::other("Dictionary larger than 4GiB maximum size"));
    }
    if dict_size_bits == 40 {
        return Ok(0xFFFFFFFF);
    }
    let size = (2 | (dict_size_bits & 0x1)) << (dict_size_bits / 2 + 11);
    Ok(size)
}

#[inline]
fn get_lzma_dic_size(coder: &Coder) -> Result<u32, Error> {
    let mut props = &coder.properties[1..5];
    props.read_u32::<LittleEndian>().map_err(Error::io)
}
