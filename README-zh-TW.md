# UsefulUnpack

[**中文**](README-zh.md) | [**English**](README.md) | [**繁體中文**](README-zh-TW.md) | [**日本語**](README-ja.md)

輕量級 Android 檔案管理器 & 歸檔打包/解壓工具

支援 **XP3**（吉里吉里）、**PFS**（Artemis）、**NSA/SAR**（NScripter）、**YPF**（YU-RIS）、**RGSS**（RPG Maker XP/VX/VX Ace）、**KSD**（吉里吉里2）、**RPA**（Ren'Py）、**INT**（CatSystem2）、**BGI/Ethornell**（`.arc`）、**Lucifen**（`.lpk`）、**ISO 9660** 光碟映像，以及 **ZIP**、**7z**、**RAR**、**TAR**、**GZIP**、**BZIP2**、**XZ**、**ZSTD**、**LZMA**、**LZ4** 等通用格式（均支援打包與解壓），Rust 原生核心。

---

## 功能

| 功能 | 說明 |
|------|------|
| **日誌與通用元件** | 設定 → 日誌與診斷 / 通用元件。依使用者操作分鏈，介面與本機日誌共用短 ID，取消與清理失敗可追溯；日誌由使用者主動分享。 |
| ✂️ **XP3** | 解壓 + 封包吉里吉里 `.xp3`；**cxdec 內容過濾**（經典）可識別、可顯示（預覽標題 / 掃描 / `uu l`）並**可回寫** —— 重封 cxdec 遊戲的封包會保持原加密 |
| 📦 **PFS** | 解壓 + 封包 Artemis `.pfs` / `.pf6` / `.pf8` |
| 📜 **NSA/SAR** | 解壓 NScripter `.nsa` / `.sar` 封包（LZSS + SPB） |
| 📦 **YPF** | 解壓 YU-RIS `.ypf` 封包，三層自適應邊界檢測 |
| 🐍 **INT** | 解壓 CatSystem2（KIF）`.int`：條目名逐條置換、位移/大小與內容走 Blowfish，**金鑰來自遊戲 exe 的 key_code / v_code / v_code2 資源**，所以 .int 旁邊要有那個 exe；沒有 exe 明確報錯不猜 |
| 🏗️ **BGI/Ethornell** | 解壓 `.arc` 封包：經典的 `Pack`+`File    ` 佈局與 Buriko 的 `BURI`+`KO ARC20` 都由封包頭自動區分，不必使用者選擇。`DSC FORMAT 1.00` 條目（帶金鑰的規範 Huffman）就地解碼。**唯讀格式，不封裝** —— 參考實作無法寫入，DSC 編碼器也從未公開 |
| 🔐 **Lucifen LPK** | 解壓並封裝 Lucifen 引擎 `.lpk`。金鑰由**檔案名稱**派生，所以封裝出來的包必須用遊戲期望的原名；索引是加密的字母 trie，條目還可能再疊 LZSS / 整體混淆 / 標頭加密。整包前綴只存在**索引裡一份**，不在每個條目重複，所以條目記錄的 size 是還原後的長度、不是磁碟上的位元組數。常數有**兩套**，由封包頭自動偵測。資料包逐檔加密：金鑰對取自同目錄 `SCRIPT.LPK`，缺少時會**掃描遊戲目錄暴力還原**（每個候選都要通過真實索引結構校驗）。**可封裝，但不做壓縮** —— 引擎的 LZSS 編碼器從未公開，寫錯會靜默產出壞檔 |
| 🐍 **RPA** | 解壓 + 封裝 Ren'Py `.rpa` / `.rpi`；索引是 zlib 壓縮的 Python pickle，RPA-1.0 / 2.0 / 3.0 三種形態（另相容 RPA-3.2 / 4.0 與 ALT-1.0），條目名為 UTF-8；**封裝寫出 RPA-3.0，pickle 協定 2**（Python 2 時代的 Ren'Py 6.x 與現代引擎都能讀取），可編輯腳本；ZiX-12A/B 需要遊戲自帶的 `renpy/loader.pyo`，明確報錯不猜 |
| 🎮 **RPG Maker MV/MZ** | 解碼 + 回封逐檔混淆素材（回封需自填金鑰）：`.rpgmvp` 圖片、`.rpgmvo` 音訊、`.rpgmvm` 影片（以及 MZ 的 `.png_` / `.ogg_` / `.m4a_`）；16 位元組檔頭可從檔案自身還原，**不需金鑰**（檔案沒有副檔名時也認得出，靠內容判斷型別）；回封金鑰接受 32 位十六進位（原樣使用）或任意文字（依 RPG Maker 的規則做 MD5）；**每個格式只收自己的類型：`.rpgmvp` 收 `.png`、`.rpgmvo` 收 `.ogg`、`.rpgmvm` 收 `.m4a`**，其餘一律擋下，因為引擎是依副檔名選解碼器的；產物依真實副檔名命名，可直接進圖片 / 音訊預覽；批次模式一次解碼整個資料夾 |
| 🎮 **RGSS** | 解壓 + 封包 RPG Maker 加密封包：`.rgssad`（XP）、`.rgss2a`（VX）、`.rgss3a`（VX Ace）；版面依封包標頭自動辨識，三種副檔名互相都能開啟；檔名 UTF-8 / Shift-JIS；可編輯腳本；**封包產物預設命名為 `Game.<ext>`**（RPG Maker 只認這個名字），封包選項裡可關 |
| 💾 **KSD** | 解壓/打包 `.ksd`（mode 0/1/2 解擾 + UTF-16→UTF-8） |
| 🗜️ **通用壓縮** | ZIP/7z + gzip/bzip2/xz/zstd/lzma/lz4（單檔）+ tar（資料夾 5 變體）+ xp3/pfs/ksd，5 級壓縮程度，AES-256（ZIP） |
| 📚 **分卷支援** | 解壓 `.7z.001` / `.zip.001` / `.rar` 分卷；zip/7z 壓縮支援依大小切分卷（相容 7-Zip） |
| 🎚️ **自訂分卷大小** | 分卷大小自訂（MB/GB，1MB~2GB） |
| 🔐 **解壓密碼** | 加密的 ZIP/7z/RAR 先彈密碼框再解壓；批次解壓只需輸入一次密碼，全部歸檔重用 |
| 🔒 **密碼標記** | 檔案瀏覽器與預覽清單中，需要密碼的歸檔顯示鎖圖示 |
| 📊 **雙層進度條** | 頂部=全量進度，底部=目前檔案進度（解壓+壓縮）；大（>64MB）RAR 成員串流解碼，頂條位元組級平滑推進而非每檔案跳段 |
| 📋 **分組格式選擇器** | 可捲動分組格式選擇（通用壓縮 / 單檔 / 其他），解壓/批次/壓縮共用 |
| 📄 **單檔壓縮按鈕** | 壓縮模式下點擊任意檔案，右下角彈出壓縮按鈕 |
| 📦 **批次壓縮選擇器** | 批次合併/分別壓縮都彈格式選擇器，合併排除單檔格式 |
| 🗜️ **RAR** | 解壓 RAR 封包（RAR4/5），支援密碼；**頭加密（-hp）歸檔**彈密碼框後可用密碼預覽/解壓；**>64MB 成員串流解碼**（位元組級進度、記憶體有界，100~500MB 成員不再整塊緩衝）；**非 solid 歸檔選中解壓/預覽隨機存取**（末尾 txt 秒開，跳過前面所有成員）；**Huffman 查表解碼加速**（~30%） |
| ⚡ **LZ4** | 打包/解包 LZ4 幀壓縮檔案 |
| 🗜️ **TAR** | 打包/解包 `.tar`、`.tar.gz`、`.tgz`、`.tar.bz2`、`.tbz2`、`.tar.xz`、`.txz`、`.tar.zst` |
| 🗜️ **GZIP** | 打包/解包 `.gz` |
| 🗜️ **BZIP2** | 打包/解包 `.bz2` |
| 🗜️ **XZ** | 打包/解包 `.xz` |
| 🗜️ **ZSTD** | 打包/解包 `.zst` |
| 🗜️ **LZMA** | 打包/解包 `.lzma` |
| 💿 **ISO 9660** | 瀏覽和提取 ISO 光碟映像 |
| 🔍 **歸檔預覽** | 樹形預覽歸檔內容，可折疊/展開，複選框選擇性解壓 |
| 📊 **預覽統計** | 即時檔案總數/總大小 + 已選統計 |
| 🔎 **全域搜尋** | 檔名搜尋 + 內容搜尋（支援 30+ 文字格式），高亮導航，繼續掃描 |
| 📦 **歸檔內搜尋** | 預覽歸檔時一鍵解包並搜尋內部檔案 |
| 🖼️ **檔案預覽** | 圖片、音訊、影片、文字/程式碼直接預覽；**Markdown/RTF 富文字渲染**（渲染/純文字切換） |
| 📂 **本機預覽** | 瀏覽器中直接點擊可預覽檔案 |
| 🗂 **檔案瀏覽器** | 類 ZArchiver 介面，路徑麵包屑，資料夾 ⭐ 星標 |
| 🪟 **多視窗 Tab** | 最多 3 個獨立視窗（ViewPager2 滑動切換，可新增/關閉），每視窗各自記住路徑、選中檔案、多選狀態、批次列與貼上/移動狀態，互不干擾；**長按標籤可重新命名視窗**；Chrome 式頂置 Tab 欄（活動標籤圓角高亮） |
| 📦 **內嵌歸檔預覽** | FAB 預覽歸檔直接渲染進目前視窗（非彈窗），**預覽時可滑動切換視窗**；底部一鍵解壓全部/提取選中/合併到（無可合併目標時按鈕置灰，不會點出死路），頂欄搜尋 + **⋮ 溢出選單**（編輯/ZIP 管理/ISO 轉換，依格式顯隱，不支援時 toast 提示）；預覽中進入全域搜尋後關閉搜尋回到預覽 |
| 🔀 **跨歸檔合併提取** | 預覽勾選項目 → 「合併到…」→ 目標可選**本資料夾，或其他視窗正開啟的歸檔**（分組清單，標註所屬視窗）→ 把來源勾選項目與目標解包到同一個暫存目錄（同名路徑由來源覆蓋），再封包成 `目標-cn.ext` 副本放在目標旁邊。**目標全程唯讀、原歸檔一個位元組不動**，所以合併進別人正預覽著的歸檔也是安全的；沒有可合併目標時按鈕置灰 |
| 📍 **選擇路徑/檔案方式** | 一般設定可選「直接開啟路徑介面」或「在新視窗中開啟選擇」——後者開新視窗瀏覽選擇，選中後關閉該視窗返回原視窗（適用於搜尋範圍/提取目標/ZIP 新增/壓縮目標） |
| 📌 **書籤** | 資料夾星標 + 側滑抽屜 |
| ✂️ **檔案操作** | 長按重新命名、移動、刪除（不可復原）、新增資料夾 |
| ☑️ **多選批次** | 進入多選模式後批次解壓/壓縮/刪除/移動 |
| 📂 **批次預覽** | 多選歸檔統一檢視內容並勾選解壓 |
| 🏠 **根目錄** | 一鍵回到 `/storage/emulated/0` |
| 🛡️ **防連點** | 800ms 冷卻 |
| 🌙 **深色主題** | 護眼暗色 |
| 🔬 **簽名掃描** | Rust scan-core 引擎：32 種簽名 / 83 個魔數模式任意偏移偵測，逐格式頭部驗證（真實大小 + 檔案數），**gzip/xz/lzma 解壓 dry-run 降誤報**，**頭加密 RAR5 兜底**，AhoCorasick 多模式匹配，串流掃描（整檔不進記憶體），一鍵解壓或切割（dd）原始片段 |
| 🖥️ **內建終端機（CLI）** | 全螢幕終端機 + `uu` 命令集——`uu fmt / help / info / l / cat / hash / grep / cp / mv / rn / rm / mkdir / tree / du / stat / x / c / set / scan / cso / enc / mvdec / rmd / add / find / diff / hex / img / fd / b64`；支援管線（`| grep/head/tail/wc/sort`）、`> 檔案` 重導向、`&&` / `;` 串接，以及 `uu run <file.uut>` 的 **UUT 指令碼**（變數、`$1`/`$argc` 指令碼參數、`$(...)` 擷取輸出、行內與區塊式 `if`/`else`、`for a in *.zip ... end`、`for i in 1..5` 計數迴圈、`while` + `break`/`continue`、`else if` 鏈、整數算術（`set n = $n + 1`）、數值比較（`if n > 3`）、`||` 串接、`for x in $(指令)` 按行遍歷輸出、`if exist &lt;路徑&gt;` 檔案測試、`$?`（上一條退出碼）與 `return [退出碼]`；只允許 `uu`/`ls`/`cd`/`pwd`/`help`/`echo`，指令碼永遠不會變成 shell）。無介面的 UUT 自動化透過已配對的本機 MCP 執行。解壓/封包的進度**只寫在終端機裡**（約 30 字元 ASCII 條 + 位元組數/目前檔名/條目計數，不彈任何對話框），完成給 all set 提示。解壓可用萬用字元挑條目（`uu x game.xp3 "*.png"`），`uu l -j` 輸出原始條目 JSON（`n/s/d/e`）給指令碼用、`uu l -t` 把條目畫成樹；`uu cp / uu mv` 支援 `-f` 覆蓋（冪等指令碼）、`uu l -S` 按大小排序（`uu l` 還會報出吉里吉里封包的 cxdec 加密狀態）、`uu cat` 可直接讀純文字檔（`-e` 強制編碼）、`uu rmd` 條目萬用字元、UUT 行尾支援 `#` 註解；掃描命中註冊為 `fN` 位元組區間描述符：`uu scan video.mp4` 後 `uu l f3` 直接列內嵌 ZIP，無需先落盤；內建 `ls / pwd / cd`（cd 導航終端機紮根的視窗），其餘命令走系統 shell。按 tab 紮根：切走隱藏、切回工作階段還在 |
| 🪟 **預覽工作區** | 封包預覽 ⋮ →「在視窗中開啟」：封包內容實體化成普通瀏覽視窗，多選/複製/移動/重新命名/分享/檔案資訊全部白拿；套娃封包天然遞迴（工作區裡還能再開工作區）；大封包（>200MB）先確認；關閉時可選清理快取；快取仍在時工作階段還原直接開回 |
| 🔤 **文字編碼** | 全域文字編碼設定（UTF-8 / SHIFT-JIS / GBK / UTF-16）嚴格套用於所有文字預覽與內容搜尋（UTF-8/UTF-16 自動去 BOM）；偵測到大量亂碼時提示到設定切換 |
| ✂️ **精確切割** | 簽名掃描的切割/解壓依驗證出的歸檔大小（zip/rar/7z/zstd/lz4/iso）精確擷取——夾在其它檔案中間的歸檔（如 `mp4 + zip + mp4`）能乾淨抽出，不帶尾部資料，解壓成功 |
| 📲 **APK 安裝** | 點 APK 呼叫系統安裝器（FileProvider + PackageInstaller 備援）；安裝前可選保留副本（部分系統安裝器裝完會刪除安裝包） |
| 🎨 **圖片編輯器** | 圖片預覽 **⋮** 選單進入：水彩/螢光筆（色板 + 透明度 + 粗細 —— 每條筆畫**記住自己畫時的透明度**，所以調低不透明度不會把已畫的筆跡重新著色）、**矩形裁剪**（拖框 → 再點「裁剪」確認）、**拉伸到 1:1 / 4:3 / 3:4 / 16:9 / 9:16**、**像素取色**（按住拖動自動採樣，點擊讀數複製 HEX/RGB）、**雙指縮放（1–8×）+ 平移**、復原/還原。另存 `原名-edit.png` 副本——不覆蓋原圖（~2048px 工作上限，旋轉安全自動儲存 + 還原提示） |
| 🔄 **圖片格式轉換** | 圖片預覽 **⋮** 選單：靜態 **JPG/PNG/WebP 互轉**，以及**動圖 GIF/WebP → 靜態首幀**（JPG/PNG/WebP）。另存副本，不覆蓋原圖 |
| 📤 **分享** | 長按任意檔案 → **分享**：經 FileProvider + `ACTION_SEND` 交給其他應用——無需聯網權限、無需改 manifest |
| 💾 **還原上次工作階段** | 設定 → **其餘設定** →「還原上次工作階段」：啟動時重開上次的視窗/目錄**以及開啟著的歸檔預覽**（最多 3 個分頁，超出彈 toast）。回收站設定也歸入其餘設定 |
| 🎨 **UI 全面重構** | 統一設計令牌（圓角/間距/字型/行高）、所有預覽的 **⋮ 溢出選單**、平板/橫屏對話框限寬、**寬屏主從佈局**（≥600dp 檔案列表在左 + 視窗內預覽在右） |
| 🦀 **Rust 核心** | 每種格式獨立 `.so`，互不干擾（26 個原生核心，含簽名掃描與影音轉換） |
| 🔒 **最小權限** | 僅儲存權限 |

## 多視窗常見問題（FAQ）

**1. 三個視窗同時解壓，會不會 OOM？**

歸檔操作共享 3 個全域並行槽位（`OpScheduler`）：使用同一個原生庫（同格式家族）的操作會依序排隊，不同原生庫的操作可以並行；跨歸檔合併會原子預留來源與目標兩個原生庫。取消排隊中的任務不會中斷正在執行的同格式任務。注意單一歸檔內部的「並行解壓執行緒」仍是全域設定（RAR 非 solid ≤4 執行緒、ZIP 條目級並行 ≤32MiB batch，大成員 >64MB 串流解碼），多任務並行時整體記憶體佔用會相應疊加。

**2. 多視窗同時開啟同一個檔案，變更會同步到其他視窗嗎？**

每視窗有獨立的目錄監聽（FileObserver）。**兩個視窗在同一個目錄時**（如都在 `/Download`），任一視窗解壓/改名/刪除產生目錄事件，兩個監聽各自收到並各自重新整理——**立即同步**。兩個視窗在不同目錄則互不干擾。

同一歸檔（含分卷，如 `name.zip.001/.zip` 視為一體）**不會同時在兩個視窗開啟**：內建同歸檔互斥鎖（OpenArchiveRegistry），第二個視窗嘗試開啟時會提示「此歸檔已在『視窗 N』開啟」並自動跳到該視窗。歸檔預覽/編輯期間佔用該鎖，關閉對話框或關閉視窗即釋放。

**3. 預覽歸檔時能切換視窗嗎？**

能。歸檔預覽**內嵌在目前視窗**（不是彈窗），ViewPager 全程可滑動——預覽歸檔的同時可左右滑動切換視窗，每個視窗的預覽/勾選狀態互不干擾。預覽中進入全域搜尋後，關閉搜尋會自動回到預覽。

**4. 選擇路徑/檔案時能開新視窗嗎？**

能。一般設定 →「選擇路徑/檔案方式」可切換：預設是**直接開啟路徑介面**（彈窗），也可設為**在新視窗中開啟選擇**——點選擇（如搜尋範圍/提取目標/ZIP 新增/壓縮目標）會開新視窗瀏覽，選中後自動關閉該視窗並返回原視窗（原視窗狀態保留），按返回鍵則取消選擇。

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
         libarchive_rgss_core.so → RGSS (XP/VX/VX Ace) + MV/MZ 散素材
         libarchive_rpa_core.so  → RPA (Ren'Py)
         libarchive_int_core.so  → INT (CatSystem2)
         libarchive_bgi_core.so  → BGI/Ethornell (.arc)
         libarchive_lpk_core.so  → Lucifen (.lpk)
         libarchive_rgss_core.so → RGSS (RPG Maker XP/VX/VX Ace)
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
          檔案寫入目標目錄
```

各格式獨立在 `crates/<format>-core/`，公共工具在 `crates/common/`。

## 文件 Agent 文件廣場

[本機 Markdown 指南](app/src/main/resources/agent-documents)隨應用程式打包，涵蓋已登錄的解包與封包格式及疑難排解。你可以選擇一篇附在下一則訊息，檔案 Agent 遇到具體問題時也可以查閱。本機 MCP 可搜尋同一份文件目錄並讀取指定指南。

感謝提供參考的文件作者及維護者。[Ren'Py](https://www.renpy.org/doc/html/translation.html)、[TyranoScript](https://tyrano.jp/tag/)、[吉里吉里／KAG3](https://krkrz.github.io/krkr2doc/kag3doc/contents/Tags.html)、[RPG Maker MV](https://rpgmakerofficial.com/product/MV_Help/page/01_10.html)及 [RPG Maker MZ](https://rpgmakerofficial.com/product/MZ_help-en/01_10.html)。

解包與封包指南也參考了公開的格式說明與實作。[7-Zip](https://www.7-zip.org/7z.html)、[GARbro](https://github.com/morkt/GARbro)及 [YU-RIS 解包工具](https://github.com/mwzzhang/python-YU-RIS-package-file-unpacker)。

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
