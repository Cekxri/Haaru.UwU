# Haaru 維護記錄

Haaru 的主介面、Rust 資料核心、Android 讀取層、中央回應副本與模組通訊服務分開維護。0.7.0 的介面、同版 App 安裝與通用能力探測見 CHANGELOG、COMPATIBILITY 與 UI_BUILD。

預設資料來源和第三方工具的原始名稱、版本、授權均保留於 NOTICE。依賴 commit／archive hash 在 native/vendor/pins.json、native/toolchain-pins.json 和 rust-core/toolchain-pins.json；不以第三方的設備報告宣稱 Haaru 已通過所有 ROM。

設定遷移只處理模組自己的方案和外觀。舊來源通過名稱、作者、路徑、schema、檔案鎖與快照驗證後才停用；新模組正式啟動並確認快照後，才標記舊目錄移除。資料不寫回 CarrierConfig／訂閱或 eSIM 資料庫。

Haaru is independently maintained. Preset and dependency provenance is recorded in NOTICE and hash-pinned manifests. Interface discovery, private result copies, migration and installer checks are tested separately; a passing build is not universal-device certification.
