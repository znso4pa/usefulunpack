# scan-core test corpus — what these files are and how to regenerate them

Real files, each produced by a **real writer**, used by
`real_fixtures_are_identified_by_their_own_signature` and
`truncated_fixtures_never_panic`. A signature change that breaks a real format,
or a validator that reads past its header buffer (how a `dex\n03` candidate once
panicked the whole scan), fails in `cargo test` instead of on a device.

## These files are not in git

They are **deliberately not distributed** with the repository (several are real
game/engine files — redistribution is not ours to do). Keep your own copies here
and `cargo test` uses them; regenerate the rest with the commands below. Missing
files make the affected cases print `SKIP …` and pass, so a fresh clone and CI
still build — but **green there no longer means the signature assertions ran**.

| File | Written by |
|---|---|
| `a.adx` | `ffmpeg -f lavfi -i "sine=frequency=440:duration=1" -c:a adpcm_adx` |
| `a.aiff`, `a.au`, `a.caf` | `ffmpeg … -c:a pcm_s16be` / `pcm_s16le` (0.05 s) |
| `a.opus.ogg` | `ffmpeg … -c:a libopus` |
| `a.m4a` | `ffmpeg … -c:a aac` |
| `v.mp4`, `m.mkv` | `ffmpeg … -c:v libx264 -pix_fmt yuv420p` |
| `v.webm` | `ffmpeg … -c:v libvpx-vp9` |
| `i.bmp`, `i.gif`, `i.jp2`, `i.qoi`, `i.tiff` | `ffmpeg … -frames:v 1` |
| `c.zip`, `c.7z`, `c.tar`, `c.gz`, `c.bz2`, `c.xz`, `c.lz4`, `c.zst` | `7z a -t<fmt>` / `lz4` |
| `c.lzma` | `python3 -c "lzma.compress(…, format=lzma.FORMAT_ALONE)"` |
| `c.ar` | `ar rc` |
| `c.xar` | `xar -cf` |
| `c.sqlite` | `sqlite3` |
| `c.class` | `javac` |
| `c.pem` | `openssl genrsa` |
| `c.pcap`, `c.torrent` | hand-written to the format's own spec (24-byte libpcap global header; bencoded dict) |
| `c.dex.head` | **first 4 KiB** of a real `classes.dex` taken from this app's own release APK (the full file is 9 MB; the validator tolerates a truncated header, which is the behaviour under test) |

`Ren'Py archive` and `CatSystem2 INT archive` fixtures are **not** duplicated
here: the tests read them at run time from `crates/rpa-core/testdata` and
`crates/int-core/testdata`, where their provenance is documented in full. They
are read rather than `include_bytes!`-embedded for the same reason the files are
untracked: an absent fixture must skip, not break compilation for everyone
without the corpus.

## Formats verified out-of-tree only

Larger samples live outside the repo (they would bloat it) and are covered by
the manual corpus run instead — regenerate with:

```bash
hdiutil create -size 1m -fs MS-DOS -volname TEST -ov c.dmg   # DMG wrapping FAT12/16 + MBR
unzip -p UsefulUnpack.apk resources.arsc > c.arsc            # Android resource table
cp /System/Library/Fonts/Helvetica.ttc f.ttc                 # TrueType collection
```

`c.dmg` is the only fixture that also exercises the MBR + FAT signatures from
inside a real disk image; `c.arsc` and `f.ttc` cover the two Android/iOS
container signatures that have no small in-repo sample.

## False-positive budget

Measured against binwalk 3.1 (`binwalk --list` reports 85 signatures / 187
magic patterns; this table has 118 / 203):

| File | ours | binwalk |
|---|---|---|
| `libarchive_zip_core.so` (1.8 MB) | 7 | 7 |
| `UsefulUnpack.apk` (50 MB) | 1 | 1 |
| `classes.dex` (9.5 MB) | 1 | 1 (a bogus RAR hit) |
| `/usr/bin/python3` | 3 (all Mach-O, incl. embedded slices) | 0 |
| `Helvetica.ttc` | 15 (6 KSD + 2 MP3 are pre-existing looseness, 2 TTF/TTC are real) | 0 |

The three signatures that blew this budget during development were tightened
until they stopped firing on real binaries: the TrueType table directory is now
walked (165 → 0 hits inside `python3`), zlib gets a bounded decompression
dry-run like gzip/xz/lzma (53 → 0), and ICO validates every directory entry
rather than the first.
