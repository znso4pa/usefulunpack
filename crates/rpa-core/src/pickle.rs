//! Restricted pickle reader for the RPA index stream.
//!
//! The RPA index is a Python pickle — pulling in a full pickle crate would add
//! a heavyweight dependency for a grammar whose entire content is
//! `dict[str, list[tuple[int, int(, bytes)]]]`. This module implements exactly
//! the opcodes that real writers emit and **refuses everything else by name**,
//! so an archive we cannot decode fails loudly instead of being mis-parsed.
//!
//! What real writers emit (measured, not assumed — see `testdata/README.md`):
//!
//! * Ren'Py's own archiver (`launcher/game/archiver.rpy`, used by the SDK's
//!   `distribute` command) pickles with `HIGHEST_PROTOCOL`, which is protocol 5
//!   on Python 3.8+: `PROTO 5` + `FRAME` + `EMPTY_DICT` + memoized names via
//!   `MEMOIZE`/`BINGET` + `SHORT_BINUNICODE` + `SHORT_BINBYTES` + `TUPLE3`.
//!   Its index is a **plain dict / plain list** (verified against a real
//!   archive produced by Ren'Py 8.5.3) — no class objects, hence no
//!   `GLOBAL`/`REDUCE` opcodes.
//! * rpatool writes protocol 2: `BINPUT`/`BINGET` memoization, `BINUNICODE`,
//!   `TUPLE2`, and `LONG1` for the values that `offset ^ key` pushes past
//!   2^31 (its default key is 0xDEADBEEF, so this is the common case, not an
//!   edge case).
//!
//! `GLOBAL`/`REDUCE` and the protocol 0/1 text opcodes are deliberately
//! rejected: the former means a py2-era writer put a *class* in the index
//! (only reachable when inline `start` bytes are involved), and the latter
//! never comes out of `pickle.dumps`'s binary protocols. Both would need a
//! real sample before we could claim to handle them.

/// A decoded pickle value. Only the shapes an RPA index can contain.
#[derive(Debug, Clone, PartialEq)]
pub enum Value {
    None,
    Bool(bool),
    Int(i64),
    Bytes(Vec<u8>),
    Str(String),
    List(Vec<Value>),
    Tuple(Vec<Value>),
    Dict(Vec<(Value, Value)>),
}

enum Item {
    Mark,
    Val(Value),
}

struct Cur<'a> {
    b: &'a [u8],
    p: usize,
}

impl<'a> Cur<'a> {
    fn u8(&mut self) -> Result<u8, String> {
        let v = *self.b.get(self.p).ok_or_else(|| format!("pickle: truncated at byte {}", self.p))?;
        self.p += 1;
        Ok(v)
    }

    fn take(&mut self, n: usize) -> Result<&'a [u8], String> {
        let end = self.p.checked_add(n).ok_or_else(|| "pickle: length overflow".to_string())?;
        if end > self.b.len() {
            return Err(format!("pickle: truncated at byte {} (wanted {} more bytes)", self.p, n));
        }
        let s = &self.b[self.p..end];
        self.p = end;
        Ok(s)
    }

    fn u32le(&mut self) -> Result<u32, String> {
        let s = self.take(4)?;
        Ok(u32::from_le_bytes([s[0], s[1], s[2], s[3]]))
    }

    fn u64le(&mut self) -> Result<u64, String> {
        let s = self.take(8)?;
        Ok(u64::from_le_bytes([s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7]]))
    }

    /// A pickle "short" length prefix (1 byte) as a usize.
    fn len1(&mut self) -> Result<usize, String> {
        Ok(self.u8()? as usize)
    }

    /// A 4-byte length prefix as a usize, bounded by the remaining input.
    fn len4(&mut self) -> Result<usize, String> {
        let n = self.u32le()? as usize;
        if n > self.b.len() {
            return Err(format!("pickle: declared length {n} exceeds stream"));
        }
        Ok(n)
    }

    /// An 8-byte length prefix as a usize, bounded by the remaining input.
    fn len8(&mut self) -> Result<usize, String> {
        let n = self.u64le()?;
        if n > self.b.len() as u64 {
            return Err(format!("pickle: declared length {n} exceeds stream"));
        }
        Ok(n as usize)
    }
}

/// Little-endian two's-complement integer (`LONG1`/`LONG4` payload), the
/// encoding CPython uses for values that do not fit `BININT`'s signed 32 bits.
/// An empty payload is 0 (CPython's `LONG1` with length 0).
fn long_le(bytes: &[u8]) -> Result<i64, String> {
    if bytes.is_empty() {
        return Ok(0);
    }
    if bytes.len() > 8 {
        return Err(format!("pickle: integer of {} bytes does not fit i64", bytes.len()));
    }
    // Sign-extend from the payload's own width — starting from an all-ones
    // sentinel instead would make every negative value come out as -1.
    let bits = 8 * bytes.len() as u32;
    let mut v: i64 = if bytes[bytes.len() - 1] & 0x80 != 0 { (!0u64 << bits) as i64 } else { 0 };
    for (i, b) in bytes.iter().enumerate() {
        v |= (*b as i64) << (8 * i);
    }
    Ok(v)
}

fn utf8(b: &[u8]) -> Result<String, String> {
    std::str::from_utf8(b)
        .map(|s| s.to_string())
        .map_err(|_| format!("pickle: string is not valid UTF-8 ({} bytes)", b.len()))
}

/// Reads one pickled value. Trailing bytes after `STOP` are ignored (a zlib
/// stream can be followed by anything — in an `.rpi` the file *data* follows
/// the index).
pub fn loads(data: &[u8]) -> Result<Value, String> {
    let mut c = Cur { b: data, p: 0 };
    let mut stack: Vec<Item> = Vec::new();
    // CPython's unpickler keeps `memo[idx] = obj` and a separate counter that
    // `MEMOIZE` bumps; `BINPUT(n)`/`LONG_BINPUT(n)` set the counter to n + 1.
    // Reproducing that (rather than a plain Vec) is what makes streams that
    // mix both memo flavours decode identically to CPython.
    let mut memo: Vec<Option<Value>> = Vec::new();
    let mut next_memo: usize = 0;

    macro_rules! pop {
        () => {
            match stack.pop() {
                Some(Item::Val(v)) => v,
                Some(Item::Mark) => return Err("pickle: MARK where a value was expected".to_string()),
                None => return Err("pickle: stack underflow".to_string()),
            }
        };
    }
    /// Pops everything above the nearest MARK and returns it in stream order.
    macro_rules! pop_mark {
        () => {{
            let pos = match stack.iter().rposition(|i| matches!(i, Item::Mark)) {
                Some(p) => p,
                None => return Err("pickle: MARK not found".to_string()),
            };
            let items: Vec<Value> = stack[pos + 1..]
                .iter()
                .map(|i| match i {
                    Item::Val(v) => Ok(v.clone()),
                    Item::Mark => Err("pickle: nested MARK in item list".to_string()),
                })
                .collect::<Result<_, String>>()?;
            stack.truncate(pos);
            items
        }};
    }
    macro_rules! memo_get {
        ($idx:expr) => {{
            let i: usize = $idx;
            match memo.get(i) {
                Some(Some(v)) => v.clone(),
                _ => return Err(format!("pickle: memo index {i} was never written")),
            }
        }};
    }
    macro_rules! memo_put {
        ($idx:expr, $v:expr) => {{
            let i: usize = $idx;
            if i >= 1_000_000 {
                return Err(format!("pickle: absurd memo index {i}"));
            }
            if memo.len() <= i {
                memo.resize(i + 1, None);
            }
            memo[i] = Some($v);
            if next_memo <= i {
                next_memo = i + 1;
            }
        }};
    }

    loop {
        let at = c.p;
        let op = c.u8()?;
        match op {
            // ── protocol / framing ────────────────────────────────────────
            0x80 => {
                // PROTO
                let p = c.u8()?;
                if p < 2 {
                    return Err(format!("pickle: protocol {p} (text-mode pickles) is not supported at byte {at}"));
                }
                if p > 5 {
                    return Err(format!("pickle: protocol {p} is newer than this reader supports at byte {at}"));
                }
            }
            0x95 => {
                // FRAME is a length prefix for a batch of instructions, NOT a
                // blob: the framed bytes still follow inline and must be
                // parsed. Skipping them swallows the rest of the pickle — the
                // first version of this reader did exactly that and failed on
                // every protocol-4/5 archive (Ren'Py's own writer, i.e. every
                // real game). The length is only used as an early truncation
                // check.
                let n = c.u64le()?;
                if n > (c.b.len() - c.p) as u64 {
                    return Err(format!("pickle: FRAME of {n} bytes exceeds stream at byte {at}"));
                }
            }
            0x2e => return stack.pop().and_then(|i| match i { Item::Val(v) => Some(v), Item::Mark => None })
                .ok_or_else(|| "pickle: STOP with no value on the stack".to_string()),

            // ── containers ─────────────────────────────────────────────────
            0x28 => stack.push(Item::Mark),
            0x7d => stack.push(Item::Val(Value::Dict(Vec::new()))),
            0x5d => stack.push(Item::Val(Value::List(Vec::new()))),
            0x29 => stack.push(Item::Val(Value::Tuple(Vec::new()))),
            0x30 => {
                stack.pop().ok_or_else(|| "pickle: POP on empty stack".to_string())?;
            }
            0x31 => {
                pop_mark!();
            }
            0x61 => {
                // APPEND
                let v = pop!();
                match stack.last_mut() {
                    Some(Item::Val(Value::List(l))) => l.push(v),
                    _ => return Err("pickle: APPEND target is not a list".to_string()),
                }
            }
            0x65 => {
                // APPENDS
                let items = pop_mark!();
                match stack.last_mut() {
                    Some(Item::Val(Value::List(l))) => l.extend(items),
                    _ => return Err("pickle: APPENDS target is not a list".to_string()),
                }
            }
            0x73 => {
                // SETITEM
                let v = pop!();
                let k = pop!();
                match stack.last_mut() {
                    Some(Item::Val(Value::Dict(d))) => d.push((k, v)),
                    _ => return Err("pickle: SETITEM target is not a dict".to_string()),
                }
            }
            0x75 => {
                // SETITEMS
                let items = pop_mark!();
                if items.len() % 2 != 0 {
                    return Err("pickle: SETITEMS with an odd item count".to_string());
                }
                match stack.last_mut() {
                    Some(Item::Val(Value::Dict(d))) => {
                        let mut it = items.into_iter();
                        while let (Some(k), Some(v)) = (it.next(), it.next()) {
                            d.push((k, v));
                        }
                    }
                    _ => return Err("pickle: SETITEMS target is not a dict".to_string()),
                }
            }
            0x74 => {
                // TUPLE
                let items = pop_mark!();
                stack.push(Item::Val(Value::Tuple(items)));
            }
            0x85 | 0x86 | 0x87 => {
                // TUPLE1 / TUPLE2 / TUPLE3
                let n = (op - 0x84) as usize;
                let mut items: Vec<Value> = Vec::with_capacity(n);
                for _ in 0..n {
                    items.push(pop!());
                }
                items.reverse();
                stack.push(Item::Val(Value::Tuple(items)));
            }

            // ── integers ──────────────────────────────────────────────────
            0x4a => {
                // BININT — signed 4 bytes
                let s = c.take(4)?;
                stack.push(Item::Val(Value::Int(i32::from_le_bytes([s[0], s[1], s[2], s[3]]) as i64)));
            }
            0x4b => {
                // BININT1
                let v = c.u8()?;
                stack.push(Item::Val(Value::Int(v as i64)));
            }
            0x4d => {
                // BININT2
                let s = c.take(2)?;
                stack.push(Item::Val(Value::Int(u16::from_le_bytes([s[0], s[1]]) as i64)));
            }
            0x8a => {
                // LONG1
                let n = c.len1()?;
                let bytes = c.take(n)?.to_vec();
                stack.push(Item::Val(Value::Int(long_le(&bytes)?)));
            }
            0x8b => {
                // LONG4
                let n = c.len4()?;
                let bytes = c.take(n)?.to_vec();
                stack.push(Item::Val(Value::Int(long_le(&bytes)?)));
            }

            // ── strings / bytes ───────────────────────────────────────────
            0x58 => {
                // BINUNICODE
                let n = c.len4()?;
                let s = c.take(n)?;
                stack.push(Item::Val(Value::Str(utf8(s)?)));
            }
            0x8c => {
                // SHORT_BINUNICODE
                let n = c.len1()?;
                let s = c.take(n)?;
                stack.push(Item::Val(Value::Str(utf8(s)?)));
            }
            0x8d => {
                // BINUNICODE8
                let n = c.len8()?;
                let s = c.take(n)?;
                stack.push(Item::Val(Value::Str(utf8(s)?)));
            }
            0x42 => {
                // BINBYTES
                let n = c.len4()?;
                stack.push(Item::Val(Value::Bytes(c.take(n)?.to_vec())));
            }
            0x43 => {
                // SHORT_BINBYTES
                let n = c.len1()?;
                stack.push(Item::Val(Value::Bytes(c.take(n)?.to_vec())));
            }
            0x8e => {
                // BINBYTES8
                let n = c.len8()?;
                stack.push(Item::Val(Value::Bytes(c.take(n)?.to_vec())));
            }
            0x55 | 0x54 => {
                // SHORT_BINSTRING / BINSTRING — Python 2 `str`, i.e. raw bytes.
                // Kept as Bytes; the index layer decodes them as UTF-8 names.
                let n = if op == 0x55 { c.len1()? } else { c.len4()? };
                stack.push(Item::Val(Value::Bytes(c.take(n)?.to_vec())));
            }

            // ── memo ──────────────────────────────────────────────────────
            0x94 => {
                // MEMOIZE
                let v = match stack.last() {
                    Some(Item::Val(v)) => v.clone(),
                    _ => return Err("pickle: MEMOIZE with no value on the stack".to_string()),
                };
                let idx = next_memo;
                memo_put!(idx, v);
            }
            0x71 => {
                // BINPUT
                let i = c.len1()?;
                let v = match stack.last() {
                    Some(Item::Val(v)) => v.clone(),
                    _ => return Err("pickle: BINPUT with no value on the stack".to_string()),
                };
                memo_put!(i, v);
            }
            0x72 => {
                // LONG_BINPUT
                let i = c.u32le()? as usize;
                let v = match stack.last() {
                    Some(Item::Val(v)) => v.clone(),
                    _ => return Err("pickle: LONG_BINPUT with no value on the stack".to_string()),
                };
                memo_put!(i, v);
            }
            0x68 => {
                // BINGET
                let i = c.len1()?;
                stack.push(Item::Val(memo_get!(i)));
            }
            0x6a => {
                // LONG_BINGET
                let i = c.u32le()? as usize;
                stack.push(Item::Val(memo_get!(i)));
            }

            // ── singletons ────────────────────────────────────────────────
            0x4e => stack.push(Item::Val(Value::None)),
            0x88 => stack.push(Item::Val(Value::Bool(true))),
            0x89 => stack.push(Item::Val(Value::Bool(false))),

            // ── refused by name ───────────────────────────────────────────
            0x63 | 0x93 => {
                return Err(format!(
                    "RPA: index pickle contains a class reference (opcode 0x{op:02x} at byte {at}); \
                     this is a Python-2 era index carrying objects we do not model — please report this file"
                ))
            }
            0x52 => return Err(format!("RPA: index pickle uses REDUCE (byte {at}) — unsupported")),
            0x62 | 0x81 | 0x92 => return Err(format!("RPA: index pickle uses BUILD/NEWOBJ (byte {at}) — unsupported")),
            0x69 | 0x6f => return Err(format!("RPA: index pickle uses INST/OBJ (byte {at}) — protocol 0 objects are unsupported")),
            0x50 | 0x51 => return Err(format!("RPA: index pickle uses persistent ids (byte {at}) — unsupported")),
            0x82 | 0x83 | 0x84 => return Err(format!("RPA: index pickle uses EXT opcodes (byte {at}) — unsupported")),
            _ => {
                return Err(format!(
                    "RPA: unsupported pickle opcode 0x{op:02x} at byte {at} \
                     (protocol 0/1 text opcodes and float/decimal opcodes are not part of an RPA index)"
                ))
            }
        }
    }
}

/// Serializes an RPA index — `dict[str, list[tuple[int, int]]]` — at
/// **protocol 2**.
///
/// Protocol 2 rather than Ren'Py's `HIGHEST_PROTOCOL` on purpose: Ren'Py 6.x
/// runs on Python 2, and a protocol-5 stream would be unreadable there
/// ("unsupported pickle protocol"), while every Python 3 pickle reader accepts
/// protocol 2. The 2-tuple form (no `start` field) is also the shape the
/// engine's own reader fast-paths — `load_from_archive` returns a sub-file view
/// instead of concatenating parts — and it needs no `bytes` objects, which at
/// protocol 2 would drag a class reference (`GLOBAL _codecs encode`) into the
/// index for no benefit.
///
/// Values are XOR'd by the caller (that is a format concern, not a pickle one).
/// No memoization is emitted: a memo-free stream is still valid, and it keeps
/// this writer small enough to audit.
pub fn dumps_index(entries: &[(String, u64, u64)]) -> Vec<u8> {
    let mut out = Vec::with_capacity(32 + entries.len() * 48);
    out.push(0x80); // PROTO
    out.push(0x02);
    out.push(0x7d); // EMPTY_DICT
    out.push(0x28); // MARK (for SETITEMS)
    for (name, a, b) in entries {
        write_str(&mut out, name);
        out.push(0x5d); // EMPTY_LIST
        out.push(0x28); // MARK (for APPENDS)
        write_int(&mut out, *a);
        write_int(&mut out, *b);
        out.push(0x86); // TUPLE2
        out.push(0x65); // APPENDS
    }
    out.push(0x75); // SETITEMS
    out.push(0x2e); // STOP
    out
}

/// BINUNICODE — protocol 2's 4-byte length prefix + UTF-8 payload.
fn write_str(out: &mut Vec<u8>, s: &str) {
    out.push(0x58); // BINUNICODE
    out.extend_from_slice(&(s.len() as u32).to_le_bytes());
    out.extend_from_slice(s.as_bytes());
}

/// The narrowest integer opcode that holds the value, mirroring CPython's
/// choice so the bytes stay close to what a Python writer would emit.
fn write_int(out: &mut Vec<u8>, v: u64) {
    if v < 0x100 {
        out.push(0x4b); // BININT1
        out.push(v as u8);
    } else if v < 0x10000 {
        out.push(0x4d); // BININT2
        out.extend_from_slice(&(v as u16).to_le_bytes());
    } else if v <= i32::MAX as u64 {
        out.push(0x4a); // BININT
        out.extend_from_slice(&(v as u32).to_le_bytes());
    } else {
        // LONG1: minimal little-endian two's complement, with a leading zero
        // byte when the top bit would otherwise read as a sign.
        let mut bytes = v.to_le_bytes().to_vec();
        while bytes.len() > 1 && bytes[bytes.len() - 1] == 0 && bytes[bytes.len() - 2] & 0x80 == 0 {
            bytes.pop();
        }
        if bytes[bytes.len() - 1] & 0x80 != 0 {
            bytes.push(0);
        }
        out.push(0x8a); // LONG1
        out.push(bytes.len() as u8);
        out.extend_from_slice(&bytes);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn protocol5_with_frame_and_short_binbytes() {
        // Real CPython 3.14 output, kept verbatim so the opcode coverage is
        // evidence rather than invention:
        //   python3 -c "import pickle; print(pickle.dumps(
        //       {b'legacy.txt': [(4096, 11)], 'x': [(0, 0)]}, 5).hex())"
        let hex = "80059529000000000000007d9428430a6c65676163792e747874945d944d00104b0b8694618c0178945d944\
                    b004b00869461752e";
        let hex: String = hex.chars().filter(|c| !c.is_whitespace()).collect();
        let raw: Vec<u8> = (0..hex.len() / 2).map(|i| u8::from_str_radix(&hex[2 * i..2 * i + 2], 16).unwrap()).collect();
        let v = loads(&raw).unwrap();
        match v {
            Value::Dict(d) => {
                assert_eq!(d.len(), 2);
                // bytes key first (insertion order), then the str key.
                assert_eq!(d[0].0, Value::Bytes(b"legacy.txt".to_vec()));
                assert_eq!(d[0].1, Value::List(vec![Value::Tuple(vec![Value::Int(4096), Value::Int(11)])]));
                assert_eq!(d[1].0, Value::Str("x".to_string()));
            }
            other => panic!("expected dict, got {other:?}"),
        }
    }

    #[test]
    fn protocol2_with_long1_and_binput() {
        // python3 -c "import pickle; print(pickle.dumps({'a.txt':[(100 ^ 0xDEADBEEF, 5 ^ 0xDEADBEEF, b'')],
        //   'sub/b.bin':[(0x1000000000 ^ 0xDEADBEEF, 3 ^ 0xDEADBEEF, b'')]}, 2).hex())"
        // NOTE: this vector needs the classes pickling bytes at protocol 2
        // pulls in (`GLOBAL _builtin__ bytes`), which is exactly why the
        // `start`-bytes path is only supported for protocol 3+ writers; here
        // we assert the refusal is *named*, not silent.
        let hex = "80027d7100285805000000612e74787471015d71028a058bbeadde008a05eabeadde00635f5f6275696c7469\
                   6e5f5f0a62797465730a7103295271048771056158090000007375622f622e62696e71065d71078a05efbeadde\
                   108a05ecbeadde00680487710861752e";
        let hex: String = hex.chars().filter(|c| !c.is_whitespace()).collect();
        let raw: Vec<u8> = (0..hex.len() / 2).map(|i| u8::from_str_radix(&hex[2 * i..2 * i + 2], 16).unwrap()).collect();
        let err = loads(&raw).unwrap_err();
        assert!(err.contains("class reference"), "got: {err}");
    }

    #[test]
    fn protocol2_two_tuple_form_decodes() {
        // The shape rpatool writes: 2-tuples, no `start` field, protocol 2.
        //   python3 -c "import pickle; k=0xDEADBEEF; print(pickle.dumps(
        //     {'b.bin': [(0x1000000000 ^ k, 3 ^ k)]}, 2).hex())"
        let hex = "80027d71005805000000622e62696e71015d71028a05efbeadde108a05ecbeadde0086710261732e";
        let raw: Vec<u8> = (0..hex.len() / 2).map(|i| u8::from_str_radix(&hex[2 * i..2 * i + 2], 16).unwrap()).collect();
        let v = loads(&raw).unwrap();
        match v {
            Value::Dict(d) => match &d[0].1 {
                Value::List(l) => match &l[0] {
                    Value::Tuple(t) => assert_eq!(t, &vec![Value::Int(0x1000000000 ^ 0xDEADBEEF), Value::Int(3 ^ 0xDEADBEEF)]),
                    other => panic!("expected tuple, got {other:?}"),
                },
                other => panic!("expected list, got {other:?}"),
            },
            other => panic!("expected dict, got {other:?}"),
        }
    }

    #[test]
    fn negative_binint_round_trips_as_negative() {
        // python3 -c "import pickle; print(pickle.dumps({'n':[(-5,1)]},2).hex())"
        let raw = [0x80, 0x02, 0x7d, 0x71, 0x00, 0x58, 0x01, 0x00, 0x00, 0x00, 0x6e, 0x71, 0x01, 0x5d, 0x71, 0x02, 0x4a, 0xfb, 0xff, 0xff, 0xff, 0x4b, 0x01, 0x86, 0x71, 0x03, 0x61, 0x73, 0x2e];
        let v = loads(&raw).unwrap();
        match v {
            Value::Dict(d) => match &d[0].1 {
                Value::List(l) => match &l[0] {
                    Value::Tuple(t) => assert_eq!(t[0], Value::Int(-5)),
                    other => panic!("expected tuple, got {other:?}"),
                },
                other => panic!("expected list, got {other:?}"),
            },
            other => panic!("expected dict, got {other:?}"),
        }
    }

    #[test]
    fn long1_sign_extension_matches_python() {
        // The expected values are compiler-computed (`0xDEADBEEF as i32`)
        // rather than hand-subtracted — a hand-written constant here was simply
        // wrong on the first attempt, which is the same failure mode the real
        // corpora keep catching.
        assert_eq!(long_le(&[0xEF, 0xBE, 0xAD, 0xDE]).unwrap(), 0xDEAD_BEEFu32 as i32 as i64);
        // A 5-byte LONG1 is positive: CPython writes a leading byte for values
        // that overflow 32 bits.
        assert_eq!(long_le(&[0xEF, 0xBE, 0xAD, 0xDE, 0x10]).unwrap(), 0x10_DEAD_BEEFi64);
        assert_eq!(long_le(&[]).unwrap(), 0);
        assert!(long_le(&[0; 9]).is_err());
    }

    #[test]
    fn truncated_stream_is_an_error() {
        assert!(loads(&[0x80, 0x05, 0x95, 0x01]).is_err());
        assert!(loads(&[0x80, 0x05, 0x58, 0xff, 0xff]).is_err());
        assert!(loads(&[0x80, 0x02, 0x4a, 0x01]).is_err());
    }

    #[test]
    fn text_protocol_is_refused_by_name() {
        // PROTO 0/1 never comes out of `pickle.dumps(..., 2..)`, but a
        // hand-made index must not be silently mis-read.
        let err = loads(&[0x80, 0x00, 0x28, 0x2e]).unwrap_err();
        assert!(err.contains("protocol 0"), "got: {err}");
    }

    #[test]
    fn stop_ignores_trailing_data() {
        // `RPA-1.0` puts the archive *data* after the zlib'd index stream, and
        // a pickle may be followed by anything at all.
        let raw = [0x80, 0x02, 0x4b, 0x07, 0x2e, 0xde, 0xad, 0xbe, 0xef];
        assert_eq!(loads(&raw).unwrap(), Value::Int(7));
    }

    /// The writer's output must be exactly what this crate's reader (and every
    /// other pickle reader) expects — checked round-trip, including the
    /// integer encodings that only appear with a key above 2^31.
    #[test]
    fn index_writer_round_trips_every_integer_width() {
        let entries = vec![
            ("a.txt".to_string(), 0u64, 0u64),                 // BININT1
            ("b.txt".to_string(), 4096, 300),                  // BININT2 + BININT1
            ("c.txt".to_string(), 0x1234_5678, 0x7FFF_FFFF),   // BININT
            ("d.txt".to_string(), 0xDEAD_BEEFu64, 0x10_DEAD_BEEF), // LONG1 (+leading 0)
            ("中文 名字.txt".to_string(), 0xFFFF_FFFF, 1),       // 4-byte LONG1 (sign bit set)
        ];
        let raw = dumps_index(&entries);
        match loads(&raw).unwrap() {
            Value::Dict(d) => {
                assert_eq!(d.len(), entries.len());
                for (i, (name, a, b)) in entries.iter().enumerate() {
                    assert_eq!(d[i].0, Value::Str(name.clone()));
                    match &d[i].1 {
                        Value::List(l) => match &l[0] {
                            Value::Tuple(t) => assert_eq!(t, &vec![Value::Int(*a as i64), Value::Int(*b as i64)]),
                            other => panic!("expected tuple, got {other:?}"),
                        },
                        other => panic!("expected list, got {other:?}"),
                    }
                }
            }
            other => panic!("expected dict, got {other:?}"),
        }
    }

    /// CPython must accept it too — a hand-rolled writer that only its own
    /// reader understands would "pass" every in-crate test and fail in Ren'Py.
    /// The bytes are checked against `pickle.loads` out-of-tree (see
    /// `testdata/README.md`); here we at least pin the opcode sequence.
    #[test]
    fn index_writer_emits_a_protocol_2_stream() {
        let raw = dumps_index(&[("x".to_string(), 1, 2)]);
        assert_eq!(
            raw,
            vec![
                0x80, 0x02, // PROTO 2
                0x7d, // EMPTY_DICT
                0x28, // MARK
                0x58, 0x01, 0x00, 0x00, 0x00, b'x', // BINUNICODE 'x'
                0x5d, // EMPTY_LIST
                0x28, // MARK
                0x4b, 0x01, // BININT1 1
                0x4b, 0x02, // BININT1 2
                0x86, // TUPLE2
                0x65, // APPENDS
                0x75, // SETITEMS
                0x2e, // STOP
            ]
        );
    }
}
