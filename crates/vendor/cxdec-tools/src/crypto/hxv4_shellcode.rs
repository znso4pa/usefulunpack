//! KiriKiri HxV4 shellcode-style key derivation.

use crate::io::BinaryReader;
use std::fs::File;
use std::io::Read;
use std::path::Path;

pub const CONTROL_BLOCK_SIGNATURE: &[u8] = b" Encryption control block";
const PROGRAM_CACHE_SIZE: usize = 0x80;
const PROGRAM_LENGTH_LIMIT: usize = 0x80;
const CONTROL_BLOCK_WORDS: usize = 0x400;
const HX_SPLITMIX_INCREMENT: u64 = 0x9e37_79b9_7f4a_7c15;
const HX_SPLITMIX_MUL1: u64 = 0xbf58_476d_1ce4_e5b9;
const HX_SPLITMIX_MUL2: u64 = 0x94d0_49bb_1331_11eb;

#[derive(Debug)]
pub enum CxError {
    Io(std::io::Error),
    InvalidScheme(&'static str),
    Program(&'static str),
}

#[derive(Debug, Clone)]
pub struct CxScheme {
    pub mask: u32,
    pub offset: u32,
    pub prolog_order: [u8; 3],
    pub odd_branch_order: [u8; 6],
    pub even_branch_order: [u8; 8],
    pub control_block: Option<Vec<u32>>,
    pub tpm_file_name: Option<String>,
    pub nana_random_seed: Option<u32>,
    pub hx_random_type: Option<i32>,
}

pub struct CxEncryption {
    mask: u32,
    offset: u32,
    prolog_order: [u8; 3],
    odd_branch_order: [u8; 6],
    even_branch_order: [u8; 8],
    control_block: Vec<u32>,
    nana_random_seed: Option<u32>,
    hx_random_type: Option<i32>,
    program_list: Vec<Option<CxProgram>>,
}

#[allow(dead_code)] // Keeps the interpreter opcode set aligned with GARbro's CX bytecode enum.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum CxByteCode {
    Nop,
    Retn,
    MovEdiArg,
    PushEbx,
    PopEbx,
    PushEcx,
    PopEcx,
    MovEaxEbx,
    MovEbxEax,
    MovEcxEbx,
    MovEaxEdi,
    MovEaxIndirect,
    AddEaxEbx,
    SubEaxEbx,
    ImulEaxEbx,
    AndEcx0f,
    ShrEbx1,
    ShlEax1,
    ShrEaxCl,
    ShlEaxCl,
    OrEaxEbx,
    NotEax,
    NegEax,
    DecEax,
    IncEax,
    MovEaxImmed,
    AndEbxImmed,
    AndEaxImmed,
    XorEaxImmed,
    AddEaxImmed,
    SubEaxImmed,
}

#[derive(Debug, Clone, Copy)]
enum Op {
    Code(CxByteCode),
    Immed(u32),
}

#[derive(Debug, Clone)]
struct CxProgram {
    code: Vec<Op>,
    control_block: Vec<u32>,
    length: usize,
    random: ProgramRandom,
}

#[derive(Debug, Clone)]
enum ProgramRandom {
    Base { seed: u32 },
    Nana { seed: u32, random_seed: u32 },
    Hx { method: i32, seed: [u64; 2] },
}

#[derive(Default)]
struct Context {
    eax: u32,
    ebx: u32,
    ecx: u32,
    edi: u32,
    stack: Vec<u32>,
}

impl From<std::io::Error> for CxError {
    // Converts the source value into this type.
    fn from(value: std::io::Error) -> Self {
        CxError::Io(value)
    }
}

impl std::fmt::Display for CxError {
    // Formats this value for human-readable output.
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            CxError::Io(e) => write!(f, "{e}"),
            CxError::InvalidScheme(msg) | CxError::Program(msg) => f.write_str(msg),
        }
    }
}

impl std::error::Error for CxError {}

impl CxScheme {
    // Handles base behavior.
    pub fn base(mask: u32, offset: u32, control_block: Vec<u32>) -> Self {
        CxScheme {
            mask,
            offset,
            prolog_order: [0, 1, 2],
            odd_branch_order: [0, 1, 2, 3, 4, 5],
            even_branch_order: [0, 1, 2, 3, 4, 5, 6, 7],
            control_block: Some(control_block),
            tpm_file_name: None,
            nana_random_seed: None,
            hx_random_type: None,
        }
    }

    // Handles with TPM behavior.
    pub fn with_tpm(mut self, name: impl Into<String>) -> Self {
        self.tpm_file_name = Some(name.into());
        self
    }

    // Handles with nana random seed behavior.
    pub fn with_nana_random_seed(mut self, seed: u32) -> Self {
        self.nana_random_seed = Some(seed);
        self
    }

    // Handles with HX random type behavior.
    pub fn with_hx_random_type(mut self, random_type: i32) -> Self {
        self.hx_random_type = Some(random_type);
        self
    }
}

impl CxEncryption {
    // Creates a new value for this type.
    pub fn new(mut scheme: CxScheme, archive_dir: Option<&Path>) -> Result<Self, CxError> {
        let control_block = match scheme.control_block.take() {
            Some(block) => block,
            None => {
                let tpm_name = scheme
                    .tpm_file_name
                    .as_deref()
                    .ok_or(CxError::InvalidScheme(
                        "missing CX control block and TPM file name",
                    ))?;
                let path = archive_dir.map_or_else(|| tpm_name.into(), |dir| dir.join(tpm_name));
                read_control_block_from_tpm(&path)?
            }
        };
        if control_block.len() < CONTROL_BLOCK_WORDS {
            return Err(CxError::InvalidScheme("CX control block is too small"));
        }
        Ok(CxEncryption {
            mask: scheme.mask,
            offset: scheme.offset,
            prolog_order: scheme.prolog_order,
            odd_branch_order: scheme.odd_branch_order,
            even_branch_order: scheme.even_branch_order,
            control_block,
            nana_random_seed: scheme.nana_random_seed,
            hx_random_type: scheme.hx_random_type,
            program_list: vec![None; PROGRAM_CACHE_SIZE],
        })
    }

    // Handles decrypt behavior.
    pub fn decrypt(&mut self, hash: u32, offset: u64, buffer: &mut [u8]) -> Result<(), CxError> {
        self.cx_decrypt_core(hash, offset, buffer)
    }

    // Handles encrypt behavior.
    pub fn encrypt(&mut self, hash: u32, offset: u64, buffer: &mut [u8]) -> Result<(), CxError> {
        self.cx_decrypt_core(hash, offset, buffer)
    }

    // Derives pair.
    pub fn derive_pair(&mut self, hash: u32) -> Result<(u32, u32), CxError> {
        self.execute_xcode(hash)
    }

    // Handles mask behavior.
    pub fn mask(&self) -> u32 {
        self.mask
    }

    // Handles offset behavior.
    pub fn offset(&self) -> u32 {
        self.offset
    }

    // Gets base offset.
    fn get_base_offset(&self, hash: u32) -> u32 {
        (hash & self.mask).wrapping_add(self.offset)
    }

    // Handles CX decrypt core behavior.
    fn cx_decrypt_core(
        &mut self,
        hash: u32,
        mut offset: u64,
        mut buffer: &mut [u8],
    ) -> Result<(), CxError> {
        let base_offset = self.get_base_offset(hash) as u64;
        if offset < base_offset {
            let base_len = ((base_offset - offset) as usize).min(buffer.len());
            self.decode(hash, offset, &mut buffer[..base_len])?;
            offset += base_len as u64;
            buffer = &mut buffer[base_len..];
        }
        if !buffer.is_empty() {
            let key = (hash >> 16) ^ hash;
            self.decode(key, offset, buffer)?;
        }
        Ok(())
    }

    // Handles decode behavior.
    fn decode(&mut self, key: u32, offset: u64, buffer: &mut [u8]) -> Result<(), CxError> {
        let (ret1, ret2) = self.execute_xcode(key)?;
        let key1 = (ret2 >> 16) as u64;
        let mut key2 = (ret2 & 0xffff) as u64;
        let mut key3 = ret1 as u8;
        if key1 == key2 {
            key2 = key2.wrapping_add(1);
        }
        if key3 == 0 {
            key3 = 1;
        }
        let end = offset.saturating_add(buffer.len() as u64);
        if key2 >= offset && key2 < end {
            buffer[(key2 - offset) as usize] ^= (ret1 >> 16) as u8;
        }
        if key1 >= offset && key1 < end {
            buffer[(key1 - offset) as usize] ^= (ret1 >> 8) as u8;
        }
        for b in buffer {
            *b ^= key3;
        }
        Ok(())
    }

    // Executes xcode.
    fn execute_xcode(&mut self, hash: u32) -> Result<(u32, u32), CxError> {
        let seed = (hash & 0x7f) as usize;
        if self.program_list[seed].is_none() {
            self.program_list[seed] = Some(self.generate_program(seed as u32)?);
        }
        let hash = hash >> 7;
        let program = self.program_list[seed].as_ref().unwrap();
        let ret1 = program.execute(hash)?;
        let ret2 = program.execute(!hash)?;
        Ok((ret1, ret2))
    }

    // Generates program.
    fn generate_program(&self, seed: u32) -> Result<CxProgram, CxError> {
        let mut program = CxProgram::new(
            seed,
            self.nana_random_seed,
            self.hx_random_type,
            self.control_block.clone(),
        );
        for stage in (1..=5).rev() {
            if self.emit_code(&mut program, stage) {
                return Ok(program);
            }
            program.clear();
        }
        Err(CxError::Program("overly large CX bytecode"))
    }

    // Emits code.
    fn emit_code(&self, program: &mut CxProgram, stage: i32) -> bool {
        program.emit_nop(5)
            && program.emit(CxByteCode::MovEdiArg, 4)
            && self.emit_body(program, stage)
            && program.emit_nop(5)
            && program.emit(CxByteCode::Retn, 1)
    }

    // Emits body.
    fn emit_body(&self, program: &mut CxProgram, stage: i32) -> bool {
        if stage == 1 {
            return self.emit_prolog(program);
        }
        if !program.emit(CxByteCode::PushEbx, 1) {
            return false;
        }
        if program.get_random() & 1 != 0 {
            if !self.emit_body(program, stage - 1) {
                return false;
            }
        } else if !self.emit_body2(program, stage - 1) {
            return false;
        }
        if !program.emit(CxByteCode::MovEbxEax, 2) {
            return false;
        }
        if program.get_random() & 1 != 0 {
            if !self.emit_body(program, stage - 1) {
                return false;
            }
        } else if !self.emit_body2(program, stage - 1) {
            return false;
        }
        self.emit_odd_branch(program) && program.emit(CxByteCode::PopEbx, 1)
    }

    // Emits body2.
    fn emit_body2(&self, program: &mut CxProgram, stage: i32) -> bool {
        if stage == 1 {
            return self.emit_prolog(program);
        }
        let rc = if program.get_random() & 1 != 0 {
            self.emit_body(program, stage - 1)
        } else {
            self.emit_body2(program, stage - 1)
        };
        rc && self.emit_even_branch(program)
    }

    // Emits prolog.
    fn emit_prolog(&self, program: &mut CxProgram) -> bool {
        match self.prolog_order[(program.get_random() % 3) as usize] {
            2 => {
                program.emit_nop(5)
                    && program.emit(CxByteCode::MovEaxImmed, 2)
                    && {
                        let index = program.get_random() & 0x3ff;
                        program.emit_u32(index)
                    }
                    && program.emit(CxByteCode::MovEaxIndirect, 0)
            }
            1 => program.emit(CxByteCode::MovEaxEdi, 2),
            0 => program.emit(CxByteCode::MovEaxImmed, 1) && program.emit_random(),
            _ => false,
        }
    }

    // Emits even branch.
    fn emit_even_branch(&self, program: &mut CxProgram) -> bool {
        match self.even_branch_order[(program.get_random() & 7) as usize] {
            0 => program.emit(CxByteCode::NotEax, 2),
            1 => program.emit(CxByteCode::DecEax, 1),
            2 => program.emit(CxByteCode::NegEax, 2),
            3 => program.emit(CxByteCode::IncEax, 1),
            4 => {
                program.emit_nop(5)
                    && program.emit(CxByteCode::AndEaxImmed, 1)
                    && program.emit_u32(0x3ff)
                    && program.emit(CxByteCode::MovEaxIndirect, 3)
            }
            5 => {
                program.emit(CxByteCode::PushEbx, 1)
                    && program.emit(CxByteCode::MovEbxEax, 2)
                    && program.emit(CxByteCode::AndEbxImmed, 2)
                    && program.emit_u32(0xaaaa_aaaa)
                    && program.emit(CxByteCode::AndEaxImmed, 1)
                    && program.emit_u32(0x5555_5555)
                    && program.emit(CxByteCode::ShrEbx1, 2)
                    && program.emit(CxByteCode::ShlEax1, 2)
                    && program.emit(CxByteCode::OrEaxEbx, 2)
                    && program.emit(CxByteCode::PopEbx, 1)
            }
            6 => program.emit(CxByteCode::XorEaxImmed, 1) && program.emit_random(),
            7 => {
                let rc = if program.get_random() & 1 != 0 {
                    program.emit(CxByteCode::AddEaxImmed, 1)
                } else {
                    program.emit(CxByteCode::SubEaxImmed, 1)
                };
                rc && program.emit_random()
            }
            _ => false,
        }
    }

    // Emits odd branch.
    fn emit_odd_branch(&self, program: &mut CxProgram) -> bool {
        match self.odd_branch_order[(program.get_random() % 6) as usize] {
            0 => {
                program.emit(CxByteCode::PushEcx, 1)
                    && program.emit(CxByteCode::MovEcxEbx, 2)
                    && program.emit(CxByteCode::AndEcx0f, 3)
                    && program.emit(CxByteCode::ShrEaxCl, 2)
                    && program.emit(CxByteCode::PopEcx, 1)
            }
            1 => {
                program.emit(CxByteCode::PushEcx, 1)
                    && program.emit(CxByteCode::MovEcxEbx, 2)
                    && program.emit(CxByteCode::AndEcx0f, 3)
                    && program.emit(CxByteCode::ShlEaxCl, 2)
                    && program.emit(CxByteCode::PopEcx, 1)
            }
            2 => program.emit(CxByteCode::AddEaxEbx, 2),
            3 => program.emit(CxByteCode::NegEax, 2) && program.emit(CxByteCode::AddEaxEbx, 2),
            4 => program.emit(CxByteCode::ImulEaxEbx, 3),
            5 => program.emit(CxByteCode::SubEaxEbx, 2),
            _ => false,
        }
    }
}

impl crate::r#struct::xp3::Xp3Cipher for CxEncryption {
    // Returns whether encrypted.
    fn is_encrypted(&self, entry: &crate::r#struct::xp3::Xp3Entry) -> bool {
        entry.is_encrypted
    }

    // Handles decrypt behavior.
    fn decrypt(&mut self, hash: u32, offset: u64, data: &mut [u8]) -> std::io::Result<()> {
        self.decrypt(hash, offset, data)
            .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e))
    }
}

impl CxProgram {
    // Creates a new value for this type.
    fn new(
        seed: u32,
        nana_random_seed: Option<u32>,
        hx_random_type: Option<i32>,
        control_block: Vec<u32>,
    ) -> Self {
        CxProgram {
            code: Vec::with_capacity(PROGRAM_LENGTH_LIMIT),
            control_block,
            length: 0,
            random: ProgramRandom::new(seed, nana_random_seed, hx_random_type),
        }
    }

    // Handles execute behavior.
    fn execute(&self, hash: u32) -> Result<u32, CxError> {
        let mut context = Context::default();
        let mut immed = 0u32;
        let mut i = 0usize;
        while i < self.code.len() {
            let bytecode = match self.code[i] {
                Op::Code(code) => code,
                Op::Immed(_) => return Err(CxError::Program("unexpected immediate bytecode")),
            };
            i += 1;
            if matches!(
                bytecode,
                CxByteCode::MovEaxImmed
                    | CxByteCode::AndEbxImmed
                    | CxByteCode::AndEaxImmed
                    | CxByteCode::XorEaxImmed
                    | CxByteCode::AddEaxImmed
                    | CxByteCode::SubEaxImmed
            ) {
                match self.code.get(i) {
                    Some(Op::Immed(value)) => immed = *value,
                    _ => return Err(CxError::Program("incomplete immediate bytecode")),
                }
                i += 1;
            }
            match bytecode {
                CxByteCode::Nop => {}
                CxByteCode::MovEdiArg => context.edi = hash,
                CxByteCode::PushEbx => context.stack.push(context.ebx),
                CxByteCode::PopEbx => {
                    context.ebx = context
                        .stack
                        .pop()
                        .ok_or(CxError::Program("imbalanced CX stack"))?
                }
                CxByteCode::PushEcx => context.stack.push(context.ecx),
                CxByteCode::PopEcx => {
                    context.ecx = context
                        .stack
                        .pop()
                        .ok_or(CxError::Program("imbalanced CX stack"))?
                }
                CxByteCode::MovEbxEax => context.ebx = context.eax,
                CxByteCode::MovEaxEdi => context.eax = context.edi,
                CxByteCode::MovEcxEbx => context.ecx = context.ebx,
                CxByteCode::MovEaxEbx => context.eax = context.ebx,
                CxByteCode::AndEcx0f => context.ecx &= 0x0f,
                CxByteCode::ShrEbx1 => context.ebx >>= 1,
                CxByteCode::ShlEax1 => context.eax = context.eax.wrapping_shl(1),
                CxByteCode::ShrEaxCl => context.eax >>= context.ecx & 31,
                CxByteCode::ShlEaxCl => context.eax = context.eax.wrapping_shl(context.ecx & 31),
                CxByteCode::OrEaxEbx => context.eax |= context.ebx,
                CxByteCode::NotEax => context.eax = !context.eax,
                CxByteCode::NegEax => context.eax = 0u32.wrapping_sub(context.eax),
                CxByteCode::DecEax => context.eax = context.eax.wrapping_sub(1),
                CxByteCode::IncEax => context.eax = context.eax.wrapping_add(1),
                CxByteCode::AddEaxEbx => context.eax = context.eax.wrapping_add(context.ebx),
                CxByteCode::SubEaxEbx => context.eax = context.eax.wrapping_sub(context.ebx),
                CxByteCode::ImulEaxEbx => context.eax = context.eax.wrapping_mul(context.ebx),
                CxByteCode::AddEaxImmed => context.eax = context.eax.wrapping_add(immed),
                CxByteCode::SubEaxImmed => context.eax = context.eax.wrapping_sub(immed),
                CxByteCode::AndEbxImmed => context.ebx &= immed,
                CxByteCode::AndEaxImmed => context.eax &= immed,
                CxByteCode::XorEaxImmed => context.eax ^= immed,
                CxByteCode::MovEaxImmed => context.eax = immed,
                CxByteCode::MovEaxIndirect => {
                    let index = context.eax as usize;
                    let word = self
                        .control_block
                        .get(index)
                        .ok_or(CxError::Program("CX control block index out of bounds"))?;
                    context.eax = !*word;
                }
                CxByteCode::Retn => {
                    if !context.stack.is_empty() {
                        return Err(CxError::Program("imbalanced CX stack"));
                    }
                    return Ok(context.eax);
                }
            }
        }
        Err(CxError::Program("CX program without return"))
    }

    // Handles clear behavior.
    fn clear(&mut self) {
        self.length = 0;
        self.code.clear();
    }

    // Emits nop.
    fn emit_nop(&mut self, count: usize) -> bool {
        if self.length + count > PROGRAM_LENGTH_LIMIT {
            return false;
        }
        self.length += count;
        true
    }

    // Handles emit behavior.
    fn emit(&mut self, code: CxByteCode, length: usize) -> bool {
        if self.length + length > PROGRAM_LENGTH_LIMIT {
            return false;
        }
        self.length += length;
        self.code.push(Op::Code(code));
        true
    }

    // Emits u32.
    fn emit_u32(&mut self, value: u32) -> bool {
        if self.length + 4 > PROGRAM_LENGTH_LIMIT {
            return false;
        }
        self.length += 4;
        self.code.push(Op::Immed(value));
        true
    }

    // Emits random.
    fn emit_random(&mut self) -> bool {
        let value = self.get_random();
        self.emit_u32(value)
    }

    // Gets random.
    fn get_random(&mut self) -> u32 {
        self.random.next_u32()
    }
}

impl ProgramRandom {
    // Creates a new value for this type.
    fn new(seed: u32, nana_random_seed: Option<u32>, hx_random_type: Option<i32>) -> Self {
        if let Some(method) = hx_random_type {
            let split_seed = (seed as u64) | ((!seed as u64) << 32);
            let mut random = HxSplittableRandom::new(split_seed);
            return ProgramRandom::Hx {
                method,
                seed: [random.next(), random.next()],
            };
        }
        if let Some(random_seed) = nana_random_seed {
            return ProgramRandom::Nana { seed, random_seed };
        }
        ProgramRandom::Base { seed }
    }

    // Handles next u32 behavior.
    fn next_u32(&mut self) -> u32 {
        match self {
            ProgramRandom::Base { seed } => {
                let old_seed = *seed;
                *seed = 1_103_515_245u32.wrapping_mul(old_seed).wrapping_add(12_345);
                *seed ^ old_seed.wrapping_shl(16) ^ (old_seed >> 16)
            }
            ProgramRandom::Nana { seed, random_seed } => {
                let mut s = *seed ^ seed.wrapping_shl(17);
                s ^= s.wrapping_shl(18) | (s >> 15);
                *seed = !s;
                let mut r = *random_seed ^ random_seed.wrapping_shl(13);
                r ^= r >> 17;
                *random_seed = r ^ r.wrapping_shl(5);
                *seed ^ *random_seed
            }
            ProgramRandom::Hx { method, seed } => {
                if *method == 0 {
                    hx_old_random(seed) as u32
                } else {
                    hx_new_random(seed) as u32
                }
            }
        }
    }
}

struct HxSplittableRandom {
    seed: u64,
}

impl HxSplittableRandom {
    // Creates a new value for this type.
    fn new(seed: u64) -> Self {
        HxSplittableRandom { seed }
    }

    // Handles next behavior.
    fn next(&mut self) -> u64 {
        self.seed = self.seed.wrapping_add(HX_SPLITMIX_INCREMENT);
        let mut z = self.seed;
        z ^= z >> 30;
        z = z.wrapping_mul(HX_SPLITMIX_MUL1);
        z ^= z >> 27;
        z = z.wrapping_mul(HX_SPLITMIX_MUL2);
        z ^ (z >> 31)
    }
}

// Handles lo32 behavior.
fn lo32(value: u64) -> u32 {
    value as u32
}

// Handles hi32 behavior.
fn hi32(value: u64) -> u32 {
    (value >> 32) as u32
}

// Packs u64.
fn pack_u64(lo: u32, hi: u32) -> u64 {
    (lo as u64) | ((hi as u64) << 32)
}

// Handles HX old random behavior.
fn hx_old_random(seed: &mut [u64; 2]) -> u64 {
    let a = seed[0];
    let b = seed[1];

    let c = pack_u64(hi32(a) ^ hi32(b), lo32(a) ^ lo32(b));
    let e = pack_u64(hi32(c), lo32(c));

    let mut t = (hi32(c) as u64) << 21;
    t ^= a >> 15;
    t ^= hi32(c) as u64;
    let seed0_lo = t as u32;

    t = (hi32(a) >> 15) as u64;
    t |= (lo32(a) as u64) << 17;
    t ^= e >> 11;
    t ^= lo32(c) as u64;
    let seed0_hi = t as u32;

    let seed1_hi = (e >> 4) as u32;
    let seed1_lo = (c >> 4) as u32;

    seed[0] = pack_u64(seed0_lo, seed0_hi);
    seed[1] = pack_u64(seed1_lo, seed1_hi);

    let d = a.wrapping_add(b);
    t = d << 17;
    t |= (hi32(d) >> 15) as u64;
    t.wrapping_add(a)
}

// Handles HX new random behavior.
fn hx_new_random(seed: &mut [u64; 2]) -> u64 {
    let a = seed[0];
    let b = seed[1];

    let c = pack_u64(lo32(a) ^ lo32(b), hi32(a) ^ hi32(b));

    let mut t = (lo32(a) as u64) << 24;
    t |= (hi32(a) >> 8) as u64;
    t ^= (lo32(c) as u64) << 16;
    t ^= lo32(c) as u64;
    let seed0_lo = t as u32;

    t = c >> 16;
    t ^= a >> 8;
    t ^= hi32(c) as u64;
    let seed0_hi = t as u32;

    t = (hi32(c) >> 27) as u64;
    t |= (lo32(c) as u64) << 5;
    let seed1_hi = t as u32;
    let seed1_lo = (c >> 27) as u32;

    seed[0] = pack_u64(seed0_lo, seed0_hi);
    seed[1] = pack_u64(seed1_lo, seed1_hi);

    let d = 5u64.wrapping_mul(a);
    t = (hi32(d) >> 25) as u64;
    t |= d << 7;
    t.wrapping_mul(9)
}

// Reads control block from TPM.
pub fn read_control_block_from_tpm(path: &Path) -> Result<Vec<u32>, CxError> {
    let mut data = Vec::new();
    File::open(path)?.read_to_end(&mut data)?;
    if data.len() < 0x1000 {
        return Err(CxError::InvalidScheme("invalid KiriKiri TPM plugin"));
    }
    let search_end = (data.len().saturating_sub(0x1000)) & !3;
    for pos in (0..search_end).step_by(4) {
        if data[pos..].starts_with(CONTROL_BLOCK_SIGNATURE) {
            let bytes = data
                .get(pos..pos + CONTROL_BLOCK_WORDS * 4)
                .ok_or(CxError::InvalidScheme("truncated CX control block"))?;
            let mut out = Vec::with_capacity(CONTROL_BLOCK_WORDS);
            for chunk in bytes.chunks_exact(4) {
                out.push(!BinaryReader::u32_le(chunk));
            }
            return Ok(out);
        }
    }
    Err(CxError::InvalidScheme(
        "no control block found inside TPM plugin",
    ))
}
