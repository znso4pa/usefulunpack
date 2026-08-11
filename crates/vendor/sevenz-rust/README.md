[![Crate](https://img.shields.io/crates/v/sevenz-rust.svg)](https://crates.io/crates/sevenz-rust)
 [![Documentation](https://docs.rs/sevenz-rust/badge.svg)](https://docs.rs/sevenz-rust)
 
This project is a 7z compressor/decompressor written in pure rust.<br/>
And it's very much inspired by the [apache commons-compress](https://commons.apache.org/proper/commons-compress/) project.<br/>

The LZMA/LZMA2 decoder and all filters code was ported from [tukaani xz for java](https://tukaani.org/xz/java.html)

## Decompression

Supported codecs:
 - [x] BZIP2 (require feature 'bzip2')
 - [x] COPY
 - [x] LZMA
 - [x] LZMA2
 - [x] ZSTD  (require feature 'zstd')


Supported filters:
 - [x] BCJ X86
 - [x] BCJ PPC
 - [x] BCJ IA64
 - [x] BCJ ARM
 - [x] BCJ ARM_THUMB
 - [x] BCJ SPARC
 - [x] DELTA
 - [x] BJC2




### Usage

```
[dependencies]
sevenz-rust={version="0.2"}
```

Decompress source file "data/sample.7z" to dest path "data/sample"
```rust
sevenz_rust::decompress_file("data/sample.7z", "data/sample").expect("complete");
```

#### Decompress a encrypted 7z file

Add 'aes256' feature
```
[dependencies]
sevenz-rust={version="0.2", features=["aes256"]}
```

```rust
sevenz_rust::decompress_file_with_password("path/to/encrypted.7z", "path/to/output", "password".into()).expect("complete");
```

#### Multi-thread decompress
check [examples/mt_decompress](https://github.com/dyz1990/sevenz-rust/blob/main/examples/mt_decompress.rs)



## Compression
Currently only support LZMA2 method.

```
[dependencies]
sevenz-rust={version="0.5.0", features=["compress"]}
```

Use the helper function to create a 7z file with source path.
```rust
sevenz_rust::compress_to_path("examples/data/sample", "examples/data/sample.7z").expect("compress ok");
```

### With AES encryption
require version>=0.3.0
```
[dependencies]
sevenz-rust={version="0.5", features=["compress","aes256"]}
```

Use the helper function to create a 7z file with source path and password.
```rust
sevenz_rust::compress_to_path_encrypted("examples/data/sample", "examples/data/sample.7z", "password".into()).expect("compress ok");
```

### Advance

```
[dependencies]
sevenz-rust={version="0.5.0", features=["compress","aes256"]}
```

#### Solid compression

```
use sevenz_rust::*;

let mut sz = SevenZWriter::create("dest.7z").expect("create writer ok");

sz.push_source_path("path/to/compress", |_| true).expect("pack ok");

sz.finish().expect("compress ok");

```


#### Compression methods

with encryption and lzma2 options

```
use sevenz_rust::*;

let mut sz = SevenZWriter::create("dest.7z").expect("create writer ok");
sz.set_content_methods(vec![
    sevenz_rust::AesEncoderOptions::new("sevenz-rust".into()).into(),
    lzma::LZMA2Options::with_preset(9).into(),
]);
sz.push_source_path("path/to/compress", |_| true).expect("pack ok");

sz.finish().expect("compress ok");

```

## [Changelog](CHANGELOG.md)

