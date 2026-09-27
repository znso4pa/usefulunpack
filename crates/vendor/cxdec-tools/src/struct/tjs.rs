//! Minimal TJS2 bytecode parser used to read structure and constant pools.

use crate::io::BinaryReader;
use std::fmt;

#[derive(Debug)]
pub struct TjsError {
    message: String,
}

pub type Result<T> = std::result::Result<T, TjsError>;

pub trait Context<T> {
    // Handles context behavior.
    fn context(self, message: impl Into<String>) -> Result<T>;
}

impl TjsError {
    // Creates a new value for this type.
    pub fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
        }
    }
}

impl fmt::Display for TjsError {
    // Formats this value for human-readable output.
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for TjsError {}

impl From<std::io::Error> for TjsError {
    // Converts the source value into this type.
    fn from(value: std::io::Error) -> Self {
        Self::new(value.to_string())
    }
}

impl<T, E> Context<T> for std::result::Result<T, E>
where
    E: fmt::Display,
{
    // Handles context behavior.
    fn context(self, message: impl Into<String>) -> Result<T> {
        self.map_err(|err| TjsError::new(format!("{}: {err}", message.into())))
    }
}

#[derive(Debug, Clone)]
pub struct Tjs2File {
    pub toplevel: i32,
    pub const_pools: ConstPools,
    pub objects: Vec<Tjs2Object>,
}

impl Tjs2File {
    // Handles string constants behavior.
    pub fn string_constants(&self) -> &[String] {
        &self.const_pools.strings
    }
}

#[derive(Debug, Clone, Default)]
pub struct ConstPools {
    pub bytes: Vec<i8>,
    pub shorts: Vec<i16>,
    pub ints: Vec<i32>,
    pub longs: Vec<i64>,
    pub doubles: Vec<f64>,
    pub strings: Vec<String>,
    pub octets: Vec<Vec<u8>>,
}

#[derive(Debug, Clone)]
pub struct Tjs2Object {
    pub index: usize,

    pub parent: i32,
    pub name_string_index: i32,
    pub name: Option<String>,
    pub context_type: i32,

    pub max_variable_count: i32,
    pub variable_reserve_count: i32,
    pub max_frame_count: i32,
    pub func_decl_arg_count: i32,
    pub func_decl_unnamed_arg_array_base: i32,
    pub func_decl_collapse_base: i32,

    pub prop_setter: i32,
    pub prop_getter: i32,
    pub super_class_getter: i32,

    pub code: Vec<i32>,              // i16 words, sign-extended to i32
    pub data: Vec<Variant>,          // vdata[]
    pub scgetterps: Vec<i32>,        // unused for now
    pub properties: Vec<(i32, i32)>, // (name_string_index, object_index)
}

#[derive(Debug, Clone)]
pub enum Variant {
    Void,
    NullObject,       // TYPE_OBJECT (krkrz uses this mainly for null closure in bytecode)
    InterObject(i32), // TYPE_INTER_OBJECT
    InterGenerator(i32), // TYPE_INTER_GENERATOR
    String(i32),      // index into string pool
    Octet(i32),       // index into octet pool
    Real(i32),        // index into double pool
    Byte(i32),        // index into byte pool
    Short(i32),       // index into short pool
    Integer(i32),     // index into int pool
    Long(i32),        // index into long pool
    Unknown,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Instruction {
    pub addr: usize,
    pub op: i32,
    pub operands: Vec<i32>,
    pub size: usize,
}

#[derive(Debug, Clone, PartialEq)]
pub enum ConstValue {
    Void,
    NullObject,
    InterObject(i32),
    InterGenerator(i32),
    String(String),
    Octet(Vec<u8>),
    Real(f64),
    Byte(i8),
    Short(i16),
    Integer(i32),
    Long(i64),
}

macro_rules! bail {
    ($($arg:tt)*) => {
        return Err(TjsError::new(format!($($arg)*)))
    };
}

const FILE_TAG_LE: u32 =
    ('T' as u32) | (('J' as u32) << 8) | (('S' as u32) << 16) | (('2' as u32) << 24);
const VER_TAG_LE: u32 = ('1' as u32) | (('0' as u32) << 8) | (('0' as u32) << 16);

const OBJ_TAG_LE: u32 =
    ('O' as u32) | (('B' as u32) << 8) | (('J' as u32) << 16) | (('S' as u32) << 24);
const DATA_TAG_LE: u32 =
    ('D' as u32) | (('A' as u32) << 8) | (('T' as u32) << 16) | (('A' as u32) << 24);

// Variant types (krkrz tTJSByteCodeLoader)
const TYPE_VOID: i16 = 0;
const TYPE_OBJECT: i16 = 1;
const TYPE_INTER_OBJECT: i16 = 2;
const TYPE_STRING: i16 = 3;
const TYPE_OCTET: i16 = 4;
const TYPE_REAL: i16 = 5;
const TYPE_BYTE: i16 = 6;
const TYPE_SHORT: i16 = 7;
const TYPE_INTEGER: i16 = 8;
const TYPE_LONG: i16 = 9;
const TYPE_INTER_GENERATOR: i16 = 10;

const OP_NOP: i32 = 0;
pub const OP_CONST: i32 = 1;
const OP_CP: i32 = 2;
const OP_CL: i32 = 3;
const OP_CCL: i32 = 4;
const OP_TT: i32 = 5;
const OP_TF: i32 = 6;
const OP_CEQ: i32 = 7;
const OP_CDEQ: i32 = 8;
const OP_CLT: i32 = 9;
const OP_CGT: i32 = 10;
const OP_SETF: i32 = 11;
const OP_SETNF: i32 = 12;
const OP_LNOT: i32 = 13;
const OP_NF: i32 = 14;
const OP_JF: i32 = 15;
const OP_JNF: i32 = 16;
const OP_JMP: i32 = 17;
const OP_INC: i32 = 18;
const OP_INCPD: i32 = 19;
const OP_INCPI: i32 = 20;
const OP_INCP: i32 = 21;
const OP_DEC: i32 = 22;
const OP_DECPD: i32 = 23;
const OP_DECPI: i32 = 24;
const OP_DECP: i32 = 25;
const OP_LOR: i32 = 26;
const OP_LAND: i32 = 30;
const OP_BOR: i32 = 34;
const OP_BXOR: i32 = 38;
const OP_BAND: i32 = 42;
const OP_SAR: i32 = 46;
const OP_SAL: i32 = 50;
const OP_SR: i32 = 54;
const OP_ADD: i32 = 58;
const OP_SUB: i32 = 62;
const OP_MOD: i32 = 66;
const OP_DIV: i32 = 70;
const OP_IDIV: i32 = 74;
const OP_MUL: i32 = 78;
const OP_BNOT: i32 = 82;
const OP_TYPEOF: i32 = 83;
const OP_TYPEOFD: i32 = 84;
const OP_TYPEOFI: i32 = 85;
const OP_EVAL: i32 = 86;
const OP_EEXP: i32 = 87;
const OP_CHKINS: i32 = 88;
const OP_ASC: i32 = 89;
const OP_CHR: i32 = 90;
const OP_NUM: i32 = 91;
const OP_CHS: i32 = 92;
const OP_INV: i32 = 93;
const OP_CHKINV: i32 = 94;
const OP_INT: i32 = 95;
const OP_REAL: i32 = 96;
const OP_STR: i32 = 97;
const OP_OCTET: i32 = 98;
const OP_CALL: i32 = 99;
pub const OP_CALLD: i32 = 100;
const OP_CALLI: i32 = 101;
const OP_NEW: i32 = 102;
const OP_GPD: i32 = 103;
const OP_SPD: i32 = 104;
const OP_SPDE: i32 = 105;
const OP_SPDEH: i32 = 106;
const OP_GPI: i32 = 107;
const OP_SPI: i32 = 108;
const OP_SPIE: i32 = 109;
const OP_GPDS: i32 = 110;
pub const OP_SPDS: i32 = 111;
const OP_GPIS: i32 = 112;
const OP_SPIS: i32 = 113;
const OP_SETP: i32 = 114;
const OP_GETP: i32 = 115;
const OP_DELD: i32 = 116;
const OP_DELI: i32 = 117;
const OP_SRV: i32 = 118;
const OP_RET: i32 = 119;
const OP_ENTRY: i32 = 120;
const OP_EXTRY: i32 = 121;
const OP_THROW: i32 = 122;
pub const OP_CHGTHIS: i32 = 123;
const OP_GLOBAL: i32 = 124;
const OP_ADDCI: i32 = 125;
const OP_REGMEMBER: i32 = 126;
const OP_DEBUGGER: i32 = 127;

// Loads tjs2 bytecode.
pub fn load_tjs2_bytecode(buf: &[u8]) -> Result<Tjs2File> {
    if buf.len() < 12 {
        bail!("bytecode too small: {} bytes", buf.len());
    }
    let mut r = BinaryReader::new(buf);

    // File header: "TJS2100\0" (8 bytes) + filesize (u32)
    let file_tag = r.read_u32_le().context("read file tag")?;
    let ver_tag = r.read_u32_le().context("read version tag")?;
    if file_tag != FILE_TAG_LE {
        bail!(
            "fourcc mismatch: expect {:?}, got {:?}",
            b"TJS2",
            u32_to_4cc(file_tag)
        );
    }
    if ver_tag != VER_TAG_LE {
        bail!(
            "version mismatch: expect {:?}, got {:?}",
            b"100\0",
            u32_to_4cc(ver_tag)
        );
    }
    let file_size = r.read_u32_le().context("read file size")? as usize;
    if file_size != buf.len() {
        bail!(
            "file size mismatch: header={}, actual={}",
            file_size,
            buf.len()
        );
    }

    // DATA chunk: tag + chunk_size (includes tag+size) + payload
    let data_tag = r.read_u32_le().context("read DATA tag")?;
    if data_tag != DATA_TAG_LE {
        bail!(
            "fourcc mismatch: expect {:?}, got {:?}",
            b"DATA",
            u32_to_4cc(data_tag)
        );
    }
    let data_chunk_size = r.read_u32_le().context("read DATA chunk size")? as usize;
    if data_chunk_size < 8 {
        bail!("DATA chunk size too small: {}", data_chunk_size);
    }
    let data_payload_size = data_chunk_size - 8;
    let data_start = r.position();
    let data_end = data_start
        .checked_add(data_payload_size)
        .ok_or_else(|| TjsError::new("overflow computing DATA end"))?;
    if data_end > buf.len() {
        bail!(
            "DATA payload out of range: end={}, file={}",
            data_end,
            buf.len()
        );
    }
    let pools = read_data_area(&buf[data_start..data_end]).context("parse DATA area")?;
    r.set_position(data_end).context("seek DATA end")?;

    // OBJS chunk: tag + chunk_size (includes tag+size) + payload
    let objs_tag = r.read_u32_le().context("read OBJS tag")?;
    if objs_tag != OBJ_TAG_LE {
        bail!(
            "fourcc mismatch: expect {:?}, got {:?}",
            b"OBJS",
            u32_to_4cc(objs_tag)
        );
    }
    let objs_chunk_size = r.read_u32_le().context("read OBJS chunk size")? as usize;
    if objs_chunk_size < 8 {
        bail!("OBJS chunk size too small: {}", objs_chunk_size);
    }
    let objs_payload_size = objs_chunk_size - 8;
    let objs_start = r.position();
    let objs_end = objs_start
        .checked_add(objs_payload_size)
        .ok_or_else(|| TjsError::new("overflow computing OBJS end"))?;
    if objs_end > buf.len() {
        bail!(
            "OBJS payload out of range: end={}, file={}",
            objs_end,
            buf.len()
        );
    }

    let (toplevel, objects) =
        read_objects(&buf[objs_start..objs_end], &pools).context("parse OBJS area")?;
    r.set_position(objs_end).context("seek OBJS end")?;

    // No extra trailing bytes are expected in krkrz's exporter.
    if r.position() != buf.len() {
        bail!(
            "unexpected trailing bytes: pos={}, file={}",
            r.position(),
            buf.len()
        );
    }

    Ok(Tjs2File {
        toplevel,
        const_pools: pools,
        objects,
    })
}

// Reads data area.
fn read_data_area(payload: &[u8]) -> Result<ConstPools> {
    let mut r = BinaryReader::new(payload);
    let mut pools = ConstPools::default();

    // byte
    let count = r.read_u32_le().context("DATA.bytes.count")? as usize;
    if count > 0 {
        let b = r.read_bytes(count).context("DATA.bytes.data")?;
        pools.bytes = b.iter().map(|x| *x as i8).collect();
        r.align4().context("DATA.bytes.align4")?;
    }

    // short
    let count = r.read_u32_le().context("DATA.shorts.count")? as usize;
    if count > 0 {
        pools.shorts.reserve(count);
        for _ in 0..count {
            pools
                .shorts
                .push(r.read_i16_le().context("DATA.shorts.elem")?);
        }
        if (count & 1) == 1 {
            // alignment
            let _ = r.read_u16_le().context("DATA.shorts.pad")?;
        }
    }

    // int
    let count = r.read_u32_le().context("DATA.ints.count")? as usize;
    if count > 0 {
        pools.ints.reserve(count);
        for _ in 0..count {
            pools.ints.push(r.read_i32_le().context("DATA.ints.elem")?);
        }
    }

    // long (i64)
    let count = r.read_u32_le().context("DATA.longs.count")? as usize;
    if count > 0 {
        pools.longs.reserve(count);
        for _ in 0..count {
            pools
                .longs
                .push(r.read_i64_le().context("DATA.longs.elem")?);
        }
    }

    // double
    let count = r.read_u32_le().context("DATA.doubles.count")? as usize;
    if count > 0 {
        pools.doubles.reserve(count);
        for _ in 0..count {
            let bits = r.read_u64_le().context("DATA.doubles.bits")?;
            pools.doubles.push(f64::from_bits(bits));
        }
    }

    // string (UTF-16LE)
    let count = r.read_u32_le().context("DATA.strings.count")? as usize;
    pools.strings.reserve(count);
    for _ in 0..count {
        let len = r.read_u32_le().context("DATA.strings.len")? as usize;
        let mut units = Vec::with_capacity(len);
        for _ in 0..len {
            units.push(r.read_u16_le().context("DATA.strings.unit")?);
        }
        if (len & 1) == 1 {
            let _ = r.read_u16_le().context("DATA.strings.pad")?;
        }
        pools.strings.push(String::from_utf16_lossy(&units));
    }

    // octet buffers
    let count = r.read_u32_le().context("DATA.octets.count")? as usize;
    pools.octets.reserve(count);
    for _ in 0..count {
        let cap = r.read_u32_le().context("DATA.octets.len")? as usize;
        let data = r.read_bytes(cap).context("DATA.octets.data")?.to_vec();
        pools.octets.push(data);
        r.align4().context("DATA.octets.align4")?;
    }

    if r.position() != payload.len() {
        bail!(
            "DATA: payload not fully consumed: pos={}, payload={}",
            r.position(),
            payload.len()
        );
    }

    Ok(pools)
}

// Reads objects.
fn read_objects(payload: &[u8], pools: &ConstPools) -> Result<(i32, Vec<Tjs2Object>)> {
    let mut r = BinaryReader::new(payload);

    let toplevel = r.read_i32_le().context("OBJS.toplevel")?;
    let objcount = r.read_i32_le().context("OBJS.objcount")?;
    if objcount < 0 {
        bail!("OBJS.objcount is negative: {}", objcount);
    }
    let objcount = objcount as usize;

    let mut objects: Vec<Tjs2Object> = Vec::with_capacity(objcount);

    // We keep raw property pairs here; we do not execute propSet logic.
    for o in 0..objcount {
        let tag = r.read_u32_le().context("OBJS.obj.tag")?;
        if tag != FILE_TAG_LE {
            bail!(
                "object fourcc mismatch: expect {:?}, got {:?}",
                b"TJS2",
                u32_to_4cc(tag)
            );
        }
        let _obj_payload_size = r.read_u32_le().context("OBJS.obj.size")? as usize;

        let parent = r.read_i32_le().context("obj.parent")?;
        let name_idx = r.read_i32_le().context("obj.name_idx")?;
        let context_type = r.read_i32_le().context("obj.context_type")?;
        let max_variable_count = r.read_i32_le().context("obj.max_variable_count")?;
        let variable_reserve_count = r.read_i32_le().context("obj.variable_reserve_count")?;
        let max_frame_count = r.read_i32_le().context("obj.max_frame_count")?;
        let func_decl_arg_count = r.read_i32_le().context("obj.func_decl_arg_count")?;
        let func_decl_unnamed_arg_array_base = r
            .read_i32_le()
            .context("obj.func_decl_unnamed_arg_array_base")?;
        let func_decl_collapse_base = r.read_i32_le().context("obj.func_decl_collapse_base")?;
        let prop_setter = r.read_i32_le().context("obj.prop_setter")?;
        let prop_getter = r.read_i32_le().context("obj.prop_getter")?;
        let super_class_getter = r.read_i32_le().context("obj.super_class_getter")?;

        // srcpos
        let srcpos_count = r.read_i32_le().context("obj.srcpos.count")?;
        if srcpos_count < 0 {
            bail!("obj.srcpos.count is negative: {}", srcpos_count);
        }
        let srcpos_count = srcpos_count as usize;
        // We do not use srcpos mapping for now; just skip it.
        for _ in 0..srcpos_count {
            let _ = r.read_i32_le().context("obj.srcpos.codepos")?;
        }
        for _ in 0..srcpos_count {
            let _ = r.read_i32_le().context("obj.srcpos.srcpos")?;
        }

        // code area
        let code_count = r.read_i32_le().context("obj.code.count")?;
        if code_count < 0 {
            bail!("obj.code.count is negative: {}", code_count);
        }
        let code_count = code_count as usize;
        let mut code: Vec<i32> = Vec::with_capacity(code_count);
        for _ in 0..code_count {
            code.push(r.read_i16_le().context("obj.code.word")? as i32);
        }
        // align to 4 bytes if odd
        if (code_count & 1) == 1 {
            let _ = r.read_u16_le().context("obj.code.pad")?;
        }

        // data area (vdata)
        let data_count = r.read_i32_le().context("obj.data.count")?;
        if data_count < 0 {
            bail!("obj.data.count is negative: {}", data_count);
        }
        let data_count = data_count as usize;
        let mut data: Vec<Variant> = Vec::with_capacity(data_count);
        for _ in 0..data_count {
            let ty = r.read_i16_le().context("obj.data.type")?;
            let idx = r.read_i16_le().context("obj.data.index")? as i32;
            let v = match ty {
                TYPE_VOID => Variant::Void,
                TYPE_OBJECT => Variant::NullObject,
                TYPE_INTER_OBJECT => Variant::InterObject(idx),
                TYPE_INTER_GENERATOR => Variant::InterGenerator(idx),
                TYPE_STRING => Variant::String(idx),
                TYPE_OCTET => Variant::Octet(idx),
                TYPE_REAL => Variant::Real(idx),
                TYPE_BYTE => Variant::Byte(idx),
                TYPE_SHORT => Variant::Short(idx),
                TYPE_INTEGER => Variant::Integer(idx),
                TYPE_LONG => Variant::Long(idx),
                _ => Variant::Unknown,
            };
            data.push(v);
        }

        // super class getter pointer
        let scg_count = r.read_i32_le().context("obj.scgetterps.count")?;
        if scg_count < 0 {
            bail!("obj.scgetterps.count is negative: {}", scg_count);
        }
        let scg_count = scg_count as usize;
        let mut scgetterps: Vec<i32> = Vec::with_capacity(scg_count);
        for _ in 0..scg_count {
            scgetterps.push(r.read_i32_le().context("obj.scgetterps.elem")?);
        }

        // properties
        let prop_count = r.read_i32_le().context("obj.properties.count")?;
        if prop_count < 0 {
            bail!("obj.properties.count is negative: {}", prop_count);
        }
        let prop_count = prop_count as usize;
        let mut properties = Vec::new();
        if prop_count > 0 {
            properties.reserve(prop_count);
            for _ in 0..prop_count {
                let pname = r.read_i32_le().context("obj.properties.name")?;
                let pobj = r.read_i32_le().context("obj.properties.obj")?;
                properties.push((pname, pobj));
            }
        }

        let name = if name_idx >= 0 {
            pools.strings.get(name_idx as usize).cloned()
        } else {
            None
        };

        objects.push(Tjs2Object {
            index: o,
            parent,
            name_string_index: name_idx,
            name,
            context_type,
            max_variable_count,
            variable_reserve_count,
            max_frame_count,
            func_decl_arg_count,
            func_decl_unnamed_arg_array_base,
            func_decl_collapse_base,
            prop_setter,
            prop_getter,
            super_class_getter,
            code,
            data,
            scgetterps,
            properties,
        });
    }

    if r.position() != payload.len() {
        bail!(
            "OBJS: payload not fully consumed: pos={}, payload={}",
            r.position(),
            payload.len()
        );
    }

    Ok((toplevel, objects))
}

// Decodes VM instructions from an object code stream.
pub fn decode_instructions(code: &[i32]) -> Vec<Instruction> {
    let mut instructions = Vec::new();
    let mut pos = 0usize;
    while pos < code.len() {
        let size = instruction_size(code, pos);
        let end = pos.saturating_add(size).min(code.len());
        let operands = if end > pos + 1 {
            code[pos + 1..end].to_vec()
        } else {
            Vec::new()
        };
        instructions.push(Instruction {
            addr: pos,
            op: code[pos],
            operands,
            size: end - pos,
        });
        pos = end.max(pos + 1);
    }
    instructions
}

// Returns the encoded VM instruction size in i16 words.
fn instruction_size(code: &[i32], pos: usize) -> usize {
    if pos >= code.len() {
        return 1;
    }

    let op = code[pos];
    if !(OP_NOP..=OP_DEBUGGER).contains(&op) {
        return 1;
    }

    if matches!(
        op,
        OP_NOP | OP_NF | OP_RET | OP_EXTRY | OP_REGMEMBER | OP_DEBUGGER
    ) {
        return 1;
    }

    if matches!(
        op,
        OP_TT
            | OP_TF
            | OP_SETF
            | OP_SETNF
            | OP_LNOT
            | OP_BNOT
            | OP_ASC
            | OP_CHR
            | OP_NUM
            | OP_CHS
            | OP_CL
            | OP_INV
            | OP_CHKINV
            | OP_TYPEOF
            | OP_EVAL
            | OP_EEXP
            | OP_INT
            | OP_REAL
            | OP_STR
            | OP_OCTET
            | OP_JF
            | OP_JNF
            | OP_JMP
            | OP_SRV
            | OP_THROW
            | OP_GLOBAL
            | OP_INC
            | OP_DEC
    ) {
        return 2;
    }

    if matches!(
        op,
        OP_CONST
            | OP_CP
            | OP_CEQ
            | OP_CDEQ
            | OP_CLT
            | OP_CGT
            | OP_CHKINS
            | OP_CHGTHIS
            | OP_ADDCI
            | OP_CCL
            | OP_ENTRY
            | OP_SETP
            | OP_GETP
            | OP_INCP
            | OP_DECP
    ) {
        return 3;
    }

    for base in [
        OP_LOR, OP_LAND, OP_BOR, OP_BXOR, OP_BAND, OP_SAR, OP_SAL, OP_SR, OP_ADD, OP_SUB, OP_MOD,
        OP_DIV, OP_IDIV, OP_MUL,
    ] {
        if op == base {
            return 3;
        }
        if op == base + 1 || op == base + 2 {
            return 5;
        }
        if op == base + 3 {
            return 4;
        }
    }

    if matches!(
        op,
        OP_INCPD
            | OP_DECPD
            | OP_INCPI
            | OP_DECPI
            | OP_GPD
            | OP_GPDS
            | OP_GPI
            | OP_GPIS
            | OP_SPD
            | OP_SPDE
            | OP_SPDEH
            | OP_SPDS
            | OP_SPI
            | OP_SPIE
            | OP_SPIS
            | OP_DELD
            | OP_DELI
            | OP_TYPEOFD
            | OP_TYPEOFI
    ) {
        return 4;
    }

    if op == OP_CALL || op == OP_NEW {
        return call_instruction_size(code, pos, 3, 4, 5);
    }
    if op == OP_CALLD || op == OP_CALLI {
        return call_instruction_size(code, pos, 4, 5, 6);
    }

    1
}

// Returns the encoded size of CALL/NEW/CALLD/CALLI instructions.
fn call_instruction_size(
    code: &[i32],
    pos: usize,
    argc_index: usize,
    fixed_base: usize,
    expand_base: usize,
) -> usize {
    let Some(&argc) = code.get(pos + argc_index) else {
        return fixed_base;
    };
    if argc == -1 {
        fixed_base
    } else if argc == -2 {
        let Some(&real_argc) = code.get(pos + argc_index + 1) else {
            return expand_base;
        };
        expand_base + real_argc.max(0) as usize * 2
    } else {
        fixed_base + argc.max(0) as usize
    }
}

// Updates the small constant propagation state for disassembly analysis.
pub fn apply_const_flow(
    object: &Tjs2Object,
    pools: &ConstPools,
    instr: &Instruction,
    regs: &mut Vec<Option<ConstValue>>,
) {
    match instr.op {
        OP_CONST if instr.operands.len() >= 2 => {
            let dst = instr.operands[0];
            let value = object_data(object, instr.operands[1])
                .and_then(|variant| const_value(variant, pools));
            set_reg(regs, dst, value);
        }
        OP_CP if instr.operands.len() >= 2 => {
            let dst = instr.operands[0];
            let value = reg(regs, instr.operands[1]).cloned();
            set_reg(regs, dst, value);
        }
        OP_CL | OP_GLOBAL if !instr.operands.is_empty() => {
            set_reg(regs, instr.operands[0], None);
        }
        OP_ADD if instr.operands.len() >= 2 => {
            let dst = instr.operands[0];
            let value = match (reg(regs, instr.operands[0]), reg(regs, instr.operands[1])) {
                (Some(ConstValue::String(left)), Some(ConstValue::String(right))) => {
                    Some(ConstValue::String(format!("{left}{right}")))
                }
                _ => None,
            };
            set_reg(regs, dst, value);
        }
        _ => {
            if let Some(dst) = instruction_dst(instr) {
                set_reg(regs, dst, None);
            }
        }
    }
}

// Reads object-local vdata by index.
pub fn object_data(object: &Tjs2Object, index: i32) -> Option<&Variant> {
    if index < 0 {
        None
    } else {
        object.data.get(index as usize)
    }
}

// Resolves object-local vdata to a constant value.
fn const_value(variant: &Variant, pools: &ConstPools) -> Option<ConstValue> {
    Some(match variant {
        Variant::Void => ConstValue::Void,
        Variant::NullObject => ConstValue::NullObject,
        Variant::InterObject(index) => ConstValue::InterObject(*index),
        Variant::InterGenerator(index) => ConstValue::InterGenerator(*index),
        Variant::String(index) => ConstValue::String(pools.strings.get(*index as usize)?.clone()),
        Variant::Octet(index) => ConstValue::Octet(pools.octets.get(*index as usize)?.clone()),
        Variant::Real(index) => ConstValue::Real(*pools.doubles.get(*index as usize)?),
        Variant::Byte(index) => ConstValue::Byte(*pools.bytes.get(*index as usize)?),
        Variant::Short(index) => ConstValue::Short(*pools.shorts.get(*index as usize)?),
        Variant::Integer(index) => ConstValue::Integer(*pools.ints.get(*index as usize)?),
        Variant::Long(index) => ConstValue::Long(*pools.longs.get(*index as usize)?),
        Variant::Unknown => return None,
    })
}

// Returns a propagated register value.
pub fn reg(regs: &[Option<ConstValue>], index: i32) -> Option<&ConstValue> {
    if index < 0 {
        None
    } else {
        regs.get(index as usize)?.as_ref()
    }
}

// Sets a propagated register value.
fn set_reg(regs: &mut Vec<Option<ConstValue>>, index: i32, value: Option<ConstValue>) {
    if index < 0 {
        return;
    }
    let index = index as usize;
    if regs.len() <= index {
        regs.resize(index + 1, None);
    }
    regs[index] = value;
}

// Returns the destination register for instructions that overwrite one.
fn instruction_dst(instr: &Instruction) -> Option<i32> {
    if instr.operands.is_empty() {
        return None;
    }
    match instr.op {
        OP_TT
        | OP_TF
        | OP_SETF
        | OP_SETNF
        | OP_LNOT
        | OP_BNOT
        | OP_ASC
        | OP_CHR
        | OP_NUM
        | OP_CHS
        | OP_INV
        | OP_CHKINV
        | OP_TYPEOF
        | OP_EVAL
        | OP_EEXP
        | OP_INT
        | OP_REAL
        | OP_STR
        | OP_OCTET
        | OP_INC
        | OP_DEC
        | OP_CEQ
        | OP_CDEQ
        | OP_CLT
        | OP_CGT
        | OP_CHKINS
        | OP_INCP
        | OP_DECP
        | OP_LOR..=OP_MUL
        | OP_TYPEOFD
        | OP_TYPEOFI
        | OP_GPD
        | OP_GPDS
        | OP_GPI
        | OP_GPIS
        | OP_CALL
        | OP_CALLD
        | OP_CALLI
        | OP_NEW => Some(instr.operands[0]),
        _ => None,
    }
}

// Handles u32 to 4cc behavior.
fn u32_to_4cc(x: u32) -> [u8; 4] {
    [
        (x & 0xff) as u8,
        ((x >> 8) & 0xff) as u8,
        ((x >> 16) & 0xff) as u8,
        ((x >> 24) & 0xff) as u8,
    ]
}
