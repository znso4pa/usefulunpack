# UsefulUnpack

[**中文**](README-zh.md) | [**English**](README.md) | [**繁體中文**](README-zh-TW.md) | [**日本語**](README-ja.md)

軽量 Android ファイルマネージャー & アーカイブ圧縮・解凍ツール

**XP3**（吉里吉里）、**PFS**（Artemis）、**NSA/SAR**（NScripter）、**YPF**（YU-RIS）、**KSD**（吉里吉里2）、**ISO 9660**、および **ZIP**、**7z**、**RAR**、**TAR**、**GZIP**、**BZIP2**、**XZ**、**ZSTD**、**LZMA**、**LZ4** などの汎用形式（圧縮/解凍対応）。Rust ネイティブコア。

---

## 機能

| 機能 | 説明 |
|------|------|
| ✂️ **XP3** | 吉里吉里 `.xp3` を解凍・作成 |
| 📦 **PFS** | Artemis `.pfs` / `.pf6` / `.pf8` を解凍・作成 |
| 📜 **NSA/SAR** | NScripter `.nsa` / `.sar` を解凍（LZSS + SPB） |
| 📦 **YPF** | YU-RIS `.ypf` を3層検出で解凍 |
| 💾 **KSD** | `.ksd` を解凍/作成（mode 0/1/2 + UTF-16→UTF-8） |
| 🗜️ **ZIP/7z 圧縮** | ZIP/7z 圧縮、5段階レベル、AES-256暗号化 |
| 🔐 **解凍パスワード** | 暗号化ZIP/7z/RARのパスワード入力対応 |
| 📚 **分割ボリューム** | `.7z.001` / `.zip.001` / `.rar` 分割ボリュームを解凍; zip/7z 圧縮でサイズ分割（7-Zip互換） |
| 🎚️ **カスタム分割サイズ** | 分割サイズをカスタム指定（MB/GB、1MB〜2GB） |
| 🔒 **パスワードバッジ** | パスワードが必要なアーカイブをファイル一覧とプレビューでロックアイコン表示 |
| 🗜️ **RAR** | RAR を解凍（RAR4/5）、パスワード対応 |
| ⚡ **LZ4** | LZ4 フレーム圧縮ファイルを圧縮/解凍 |
| 🗜️ **TAR** | `.tar`・`.tar.gz`・`.tgz`・`.tar.bz2`・`.tbz2`・`.tar.xz`・`.txz`・`.tar.zst` を圧縮/解凍 |
| 🗜️ **GZIP** | `.gz` を圧縮/解凍 |
| 🗜️ **BZIP2** | `.bz2` を圧縮/解凍 |
| 🗜️ **XZ** | `.xz` を圧縮/解凍 |
| 🗜️ **ZSTD** | `.zst` を圧縮/解凍 |
| 🗜️ **LZMA** | `.lzma` を圧縮/解凍 |
| 💿 **ISO 9660** | ISOディスクイメージの参照・抽出 |
| 🔍 **アーカイブプレビュー** | ツリー表示、展開/折りたたみ、チェックボックス選択抽出 |
| 📊 **プレビュー統計** | リアルタイムファイル数/サイズ + 選択統計 |
| 🔎 **全体検索** | ファイル名検索 + 内容検索（30+形式）、ハイライト、継続スキャン |
| 🖼️ **ファイルプレビュー** | 画像・音声・動画・テキストを直接プレビュー |
| 🗜️ **汎用圧縮** | ZIP/7z + gzip/bzip2/xz/zstd/lzma/lz4（単一ファイル）+ tar（フォルダ5種）+ xp3/pfs/ksd，5段階レベル，AES-256（ZIP） |
| 📊 **二重プログレスバー** | 上=全体進捗，下=現在ファイル進捗（解凍+圧縮） |
| 📋 **グループ形式選択** | スクロール可能なグループ形式選択（汎用圧縮 / 単一ファイル / その他） |
| 📄 **単一ファイル圧縮ボタン** | 圧縮モードで任意ファイルをタップ→右下に圧縮ボタン |
| 📦 **バッチ圧縮選択** | 一括結合/個別圧縮で形式選択、結合は単一ファイル形式を除外 |
| ✂️ **ファイル操作** | 長押しでリネーム・移動・削除（復元不可）・新規フォルダ |
| ☑️ **複数選択** | バッチ解凍/圧縮/削除/移動 |
| 🔬 **シグネチャスキャン** | Rust scan-core エンジン：21 種のシグネチャ / 67 種のマジックを任意オフセットで検出、形式ごとのヘッダー検証（実サイズ・ファイル数）、AhoCorasick マルチパターンマッチ、ストリーミング走査（ファイル全体をメモリに読み込まない）、ワンタップで解凍または切り出し（dd） |
| 🔤 **テキストエンコーディング** | グローバル文字コード設定（UTF-8 / SHIFT-JIS / GBK / UTF-16）をすべてのテキストプレビュー・内容検索に厳密適用（UTF-8/UTF-16 は BOM 自動除去）；文字化け多数を検出すると設定で切り替えるよう案内 |
| ✂️ **正確な切り出し** | シグネチャスキャンの切り出し/解凍は検証済みアーカイブサイズ（zip/rar/7z/zstd/lz4/iso）で正確に切断——他のファイルの間に埋め込まれたアーカイブ（例 `mp4 + zip + mp4`）を末尾データなしで綺麗に抽出 |
| 📲 **APK インストール** | APK をタップしてシステムインストーラーへ（FileProvider + PackageInstaller フォールバック）；インストール前にコピー保持を選択可能（一部のシステムインストーラーは完了後に APK を削除するため） |
| 🦀 **Rust コア** | 各形式が独立した `.so`（17形式、シグネチャスキャン含む） |
| 🔒 **最小権限** | ストレージアクセスのみ |

## インストール

[Releases](https://github.com/znso4pa/usefulunpack/releases) から最新のAPKをダウンロード。

Android 8.0（API 26）以上。

## ビルド

```bash
bash build.sh
```

各形式を独立した `.so` にコンパイルし、Gradle で APK にパッケージ。

## アーキテクチャ (v4.0+)

```
操作 → Kotlin UI → 形式別 JNI
                  ↓
         libarchive_xp3_core.so  → XP3
         libarchive_pfs_core.so  → PFS
         libarchive_nsa_core.so  → NSA/SAR
         libarchive_iso_core.so  → ISO 9660
         libarchive_ypf_core.so  → YPF (YU-RIS)
         libarchive_zip_core.so  → ZIP
         libarchive_sevenz_core.so → 7z
         libarchive_rar_core.so  → RAR
         libarchive_lz4_core.so  → LZ4
         libarchive_gzip_core.so → GZIP
         libarchive_bzip2_core.so → BZIP2
         libarchive_xz_core.so   → XZ
         libarchive_zstd_core.so → ZSTD
         libarchive_lzma_core.so → LZMA
         libarchive_tar_core.so  → TAR (+ tgz/tbz2/txz/tzst)
         libarchive_ksd_core.so  → KSD
                   ↓
           ファイル書き出し
```

## ライセンス

**MIT License** — 詳細は [LICENSE](LICENSE) を参照。

## 作者

**znso4pa（亜鉛パー）**

GitHub：[github.com/znso4pa/usefulunpack](https://github.com/znso4pa/usefulunpack)

---

## 免責事項

本ツールは**合法的に所有するファイルの管理とアクセス**のみを目的としています。
- DRMやコピー保護を回避する機能は一切含まれていません
- すべてのフォーマット解析は公開仕様またはオープンソース実装に基づいています
- YPFフォーマットのXOR値はYU-RISエンジンの公開フォーマット仕様の一部であり、リバースエンジニアリングによるものではありません
- 著作権で保護されたコンテンツの無断抽出や配布に使用しないでください
- 開発者は不正使用について一切の責任を負いません
