# Haaru 功能與驗收（0.8.0 本機候選）

0.8.0 尚未公開。RTX 3060 加速的 Android 模擬器已繪製四頁 3D 場景，並核對修正版底欄的深淺主題選中標籤對比。電話引擎與手機效能仍待實機驗證。

預設資料固定於 [來源版本](https://github.com/a5533348/Nrfr/commit/6c05f224dc802d0df952db93a6d2852a91c9edb0)，保留 28 國與 74 電信商。電話引擎仍採中央回應副本、兩個非持久 properties 與同 boot 原值 shadow，前端重構不改 IPC 或 SIM／DB setter 路徑。

| 功能 | 本版行為 | 驗證界線 |
| --- | --- | --- |
| SIM 枚舉與身分 | 活躍 subId／slot／身分匹配；歧義或缺卡隔離 | 原 Controller／AIDL 合約保留；OEM 動態雙卡另驗 |
| SIM 回報國家 | 同 boot original shadow 優先，否則靜態／Context getter；不一律稱原始 | 來源路徑已核對；真機回報與快取另驗 |
| 原始電信商名稱 | originalCarrierName 與 CarrierConfig 名稱分開 | 原 adapter 合約保留；真機回報另驗 |
| 預設與自訂 | 28 國／74 ISP、搜尋、篩選、自訂兩位 ISO 與名稱 | 最新 preset／輸入回歸及交互待核對 |
| 逐卡草稿 | slot 隔離、切頁保留，儲存才送計畫 | State 合約保留；新頁面交互待核對 |
| 空字段 | 同 SIM 已啟用 profile 保留已存值，UI 明示 | Controller merge 路徑已核對；不可當作單字段還原 |
| RAM 載入／清空 | 載入已啟用方案或清空未儲存草稿 | 只用 state.edit；不 reset／forget 或刪持久方案 |
| 還原／清除 | reset／disable 保留方案；forget／clear 刪 SIM 方案，外觀保留 | 恢復須當前 applied ACK；最新回歸及真機另驗 |
| 儲存門檻 | fresh、非 readOnly／upgrade／busy、SIM ready、合法非空草稿；不要求 engine initialized | State／後端護欄保留；verified 與 applied 分開 |
| 全域確認 | 同 boot/hash、新鮮回報、完整 coverage 與 requested fields 支援 | Controller／Rust 合約保留；真機 caller 另驗 |
| 介面與導航 | 原生 Miuix、窄版 Backdrop 三頁底欄、寬版側欄、48 dp 操作區、Manrope／系統 CJK | 修正版已測窄版深淺主題、選中文字對比及關於頁返回。其他版型與 IME 以原生報告為準 |
| 有機 3D 場景 | GLES 3 TextureView、程序化網格與 PBR 材質，四模型持續形變、回彈與視差捲動 | NVIDIA GLES 3.1 模擬器繪製四頁，日誌確認高品質、景深及局部自發光 |
| 色彩與字型 | 首次使用採紫羅蘭 Expressive 並跟隨系統明暗。MaterialKolor 6.0.0 提供可開啟的 Monet、15 個手動預設、HSV 與五種風格。沿用舊有 Monet 偏好，隨附 Manrope OFL | 角色對比通過主機測試。修正版原生預覽核對手動薄荷、Vibrant 與明暗切換 |
| 刷新率與品質 | 跟隨 vsync、不設 120 fps 上限，依負荷調整緩衝區、網格與取樣 | 60 Hz 模擬器暖機提交約 57.5–60 Hz。手機畫面幀率、溫度與耗電仍待量測 |
| 減少動畫／生命週期 | 靜態更新、背景與舞台離開畫面時暫停，surface 結束時釋放 | 先前的 0.8.0 GPU 預覽已測背景暫停及恢復。其他情境以對應指紋的原生報告為準 |
| 關於／鳴謝 | 當前主題與 3D 場景、版本／作者／倉庫／來源、離線授權全文 | 修正版預覽已核對關於頁返回。先前的 0.8.0 預覽核對 Manrope OFL 全文與鳴謝返回 |
| App／模組 IPC | 無 App su；peer UID／package／signer 檢查及固定操作 | 原服務合約保留；真機流程另驗 |
| KSU／同版 APK | 執行按鈕開 App／重試 ZIP 同版安裝，離線可用 | 同版 APK 封裝以當前 release verification 核對。模組的真機安裝生命週期另驗 |
| 覆蓋更新／撤除 | 保留安裝時 SIM／外觀與停用狀態；KSU解除安裝＋完整重啟 | 最新 Rust／Java／生命週期回歸與 metadata 真機前後另驗 |

API29 是安裝底線，實際 phone classloader／出口、ART／LSPlant 與 Zygisk provider 必須可用；不保證所有 ROM。必要 hook、可信 SIM 映射或原值不完整時拒絕套用。UTF-8 property 結果最多 91 bytes，超限不截斷；回讀成功才標 applied。

空 SIM country 可能是系統真正的空回報，不能用 ready:true 或非空電信商猜國家。已儲存計畫、module-files verified 與電話引擎確認是不同結果。App 模擬器畫面也不能證明全域 caller 一致、DB／eSIM 無污染、provider 故障恢復或解除安裝後完整恢復。

APK、DEX、簽章、來源與四 ABI 應查與交付檔案相符的 `dist/release-verification.json`。目前真機狀態為 `deviceTested=false`。日後的真機報告需附上當時的模組、APK、native 與 App 來源指紋，以及實際通過的項目。

這些回報值不改 IP、GPS、APN、MCC/MNC、實體 SIM 或帳號，亦不保證 TikTok 的服務端地區。電話服務注入仍有崩潰、訊號／通話異常或開機迴圈風險；不能把主機通過當成真機安全保證。完整原理見 [README](README.md) 與 [平台相容性](COMPATIBILITY.md)。
