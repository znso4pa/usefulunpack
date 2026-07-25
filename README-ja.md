# UsefulUnpack

[**中文**](README-zh.md) | [**English**](README.md) | [**繁體中文**](README-zh-TW.md) | [**日本語**](README-ja.md)

軽量 Android ファイルマネージャー & **ビジュアルノベルゲームリソース抽出ツール**

**XP3**（吉里吉里）、**PFS**（Artemis）、**NSA/SAR**（NScripter）、**YPF**（YU-RIS）、**ZIP**、**7z**、**RAR**、**LZ4**、**ISO 9660** をサポート。Rust ネイティブコア。

---

## 機能

| 機能 | 説明 |
|------|------|
| ✂️ **XP3** | 吉里吉里 `.xp3` を解凍 |
| 📦 **PFS** | Artemis `.pfs` / `.pf6` / `.pf8` を解凍 |
| 📜 **NSA/SAR** | NScripter `.nsa` / `.sar` を解凍（LZSS + SPB） |
| 📦 **YPF** | YU-RIS `.ypf` を3層検出で解凍 |
| 🗜️ **ZIP/7z 圧縮** | ZIP/7z 圧縮、5段階レベル、AES-256暗号化 |
| 🔐 **解凍パスワード** | 暗号化ZIP/7z/RARのパスワード入力対応 |
| 🗜️ **RAR** | RAR を解凍（RAR4/5）、パスワード対応 |
| ⚡ **LZ4** | LZ4 フレーム圧縮ファイルを解凍 |
| 💿 **ISO 9660** | ISOディスクイメージの参照・抽出 |
| 🔍 **アーカイブプレビュー** | ツリー表示、展開/折りたたみ、チェックボックス選択抽出 |
| 📊 **プレビュー統計** | リアルタイムファイル数/サイズ + 選択統計 |
| 🔎 **全体検索** | ファイル名検索 + 内容検索（30+形式）、ハイライト、継続スキャン |
| 🖼️ **ファイルプレビュー** | 画像・音声・動画・テキストを直接プレビュー |
| ✂️ **ファイル操作** | 長押しでリネーム・移動・削除（復元不可）・新規フォルダ |
| ☑️ **複数選択** | バッチ解凍/圧縮/削除/移動 |
| 🦀 **Rust コア** | 各形式が独立した `.so` |
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
