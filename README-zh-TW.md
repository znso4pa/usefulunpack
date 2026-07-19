# UsefulUnpack

[**中文**](README-zh.md) | [**English**](README.md) | [**繁體中文**](README-zh-TW.md) | [**日本語**](README-ja.md)

輕量級 Android 檔案管理器 & **視覺小說遊戲資源解包工具**

支援 **XP3**（吉里吉里）、**PFS**（Artemis）、**NSA/SAR**（NScripter）、**YPF**（YU-RIS）和 **ZIP**、**7z** 和 **ISO 9660** 光碟映像，Rust 原生核心。

---

## 功能

| 功能 | 說明 |
|------|------|
| ✂️ **XP3** | 解壓吉里吉里 `.xp3` 封包 |
| 📦 **PFS** | 解壓 Artemis `.pfs` / `.pf6` / `.pf8` 封包 |
| 📜 **NSA/SAR** | 解壓 NScripter `.nsa` / `.sar` 封包（含 zlib 壓縮） |
| 📦 **YPF** | 解壓 YU-RIS `.ypf` 封包，三層自適應邊界檢測 |
| 🗜️ **ZIP/7z 壓縮** | 支援 ZIP/7z 打包，5 級壓縮程度，AES-256 加密 |
| 🔐 **解壓密碼** | 加密的 ZIP/7z 支援輸入密碼解壓 |
| 💿 **ISO 9660** | 瀏覽和提取 ISO 光碟映像 |
| 🔍 **歸檔預覽** | 樹形預覽歸檔內容，可折疊/展開，複選框選擇性解壓 |
| 📊 **預覽統計** | 即時檔案總數/總大小 + 已選統計 |
| 🔎 **全域搜尋** | 檔名搜尋 + 內容搜尋（支援 30+ 文字格式），高亮導航，繼續掃描 |
| 📦 **歸檔內搜尋** | 預覽歸檔時一鍵解包並搜尋內部檔案 |
| 🖼️ **檔案預覽** | 圖片、音訊、影片、文字/程式碼直接預覽 |
| 📂 **本機預覽** | 瀏覽器中直接點擊可預覽檔案 |
| 🗂 **檔案瀏覽器** | 類 ZArchiver 介面，路徑麵包屑，資料夾 ⭐ 星標 |
| 📌 **書籤** | 資料夾星標 + 側滑抽屜 |
| ✂️ **檔案操作** | 長按重新命名、移動、刪除（不可復原）、新增資料夾 |
| ☑️ **多選批次** | 進入多選模式後批次解壓/壓縮/刪除/移動 |
| 📂 **批次預覽** | 多選歸檔統一檢視內容並勾選解壓 |
| 🏠 **根目錄** | 一鍵回到 `/storage/emulated/0` |
| 🛡️ **防連點** | 800ms 冷卻 |
| 🌙 **深色主題** | 護眼暗色 |
| 🦀 **Rust 核心** | 每種格式獨立 `.so`，互不干擾 |
| 🔒 **最小權限** | 僅儲存權限 |

## 截圖

<p align="middle">
  <img src="screenshots/screenshot_01.jpg" width="45%" />
  <img src="screenshots/screenshot_02.jpg" width="45%" />
</p>
<p align="middle">
  <img src="screenshots/screenshot_03.jpg" width="45%" />
  <img src="screenshots/screenshot_04.jpg" width="45%" />
</p>

## 安裝

從 [Releases](https://github.com/znso4pa/usefulunpack/releases) 下載最新 APK。

最低 Android 8.0（API 26）。

## 從原始碼建構

```bash
bash build.sh
```

每個格式獨立編譯為 `.so`，透過 Cargo workspace 管理，Gradle 打包 APK。

## 架構 (v4.0+)

```
用戶操作 → Kotlin UI → 格式專屬 JNI
                  ↓
         libarchive_xp3_core.so  → XP3
         libarchive_pfs_core.so  → PFS
         libarchive_nsa_core.so  → NSA/SAR
         libarchive_iso_core.so  → ISO 9660
         libarchive_ypf_core.so  → YPF (YU-RIS)
         libarchive_zip_core.so  → ZIP
         libarchive_sevenz_core.so → 7z
                  ↓
          檔案寫入目標目錄
```

各格式獨立在 `crates/<format>-core/`，公共工具在 `crates/common/`。

## 授權條款

本專案：**MIT License** — 詳見 [LICENSE](LICENSE)。

所有第三方依賴保留各自協議。

## 作者

**znso4pa（鋅帕）**

GitHub：[github.com/znso4pa/usefulunpack](https://github.com/znso4pa/usefulunpack)

---

## 免責聲明

本工具僅用於**管理和存取您合法擁有的檔案**。
- 不包含、不提供、不繞過任何數位版權管理（DRM）或複製保護機制
- 所有格式解析均基於公開的格式規範或開源參考實作
- YPF 格式使用的 XOR 鍵值是 YU-RIS 引擎公開格式規範的一部分，並非逆向工程取得的秘密金鑰
- 請勿將本工具用於未經授權的內容提取或散佈
- 開發者不對任何非法或不當使用承擔責任
