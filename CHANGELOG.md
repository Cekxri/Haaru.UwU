# 更新紀錄 / Changes

## 0.7.1 — 2026-11-19

- 介面只保留原生 App，模組執行按鈕開啟 App 或重試安裝同版本 APK。
- 改進自動安裝與開機續裝流程，安裝結果依實際版本、簽章及 APK 內容驗證。
- 修復 0.7.0 安裝器的 Java lambda 相容性崩潰；刷入與開機安裝日誌改為繁中／英文雙語，失敗不再被誤判為等待開機。
- 全面整理英文介面、訊息與文件的用字。
- 預設使用 Google Monet 桌布色彩；關閉後提供 15 個配色與色彩選擇器，手動預設偏向紫羅蘭。
- The native App is the sole interface; the module action opens it or retries installation of the matching APK.
- Automatic installation and boot-time retries verify the installed version, signature and APK contents.
- Fixed the Java lambda compatibility crash in the 0.7.0 installer. Flashing and boot-time installation messages are bilingual, and failures are no longer mistaken for a pending boot.
- Revised English wording throughout the interface, messages and documentation.
- Google Monet wallpaper colours are on by default; manual mode offers 15 palettes and an interactive colour picker, with violet as the fresh fallback.

## 0.7.0 — 2026-11-18

- 模組 ID、內部命名、程式接口與通訊名稱統一為 Haaru；早期設定依經驗證的移轉流程保留。
- 改為探測實際平台接口，支援舊／中／新讀取形狀與独立副本能力；不完整覆蓋不會宣告生效。
- 模組 ZIP 攜帶同版已簽 App，自動安裝並驗證版本／簽章／內容；Android 未就緒時開機續裝。
- 液態玻璃底欄加入兩層折射、動態高光、跟手選取與伸縮回彈，保留手勢取消及減少動畫。
- 修正啟動圖示白色包邊，加入自適應與系統單色圖示。
- 公開來源與成品排除私人設定、日誌、憑證私鑰及本機編譯路徑。
- Product and internal identifiers now use Haaru, with verified migration for earlier settings.
- Adaptive interface discovery replaces a fixed hook count.
- The module installs its signed companion automatically, with version and identity checks.
- Liquid glass navigation and adaptive launcher icons have been refreshed.

## 0.6.2 — 2026-11-18

- 主介面改為獨立 Kotlin／Compose Android APK，使用真正 Miuix／Material 3／Backdrop 與 Android 系統字型；KSU WebUI 保留啟動說明及 Classic 入口。
- KSU 第一個執行按鈕開啟已安裝的 Haaru App，不再輸出狀態 JSON。
- App 改透過模組 LocalSocket IPC，不另外請求 superuser；模組驗證 peer UID、安裝 package 和 signer 公開憑證，只接受固定設定操作。
- 修復 `TelephonyManager.getDefault().createForSubscriptionId()` 的 null Context 崩潰，先用設備靜態 getter；原始電信商名稱另由活躍 SubscriptionInfo 讀取。
- 真正中央引擎初始化錯誤與 ACK／SDK 格式驗證錯誤分開保留，避免錯誤原因被覆蓋。
- 發佈改為 ZIP＋APK＋source ZIP；實際驗證 APK manifest／DEX／簽章與現行來源，排除私人金鑰和機器設定。
- 不再將舊 WASM browser mock 視為本版 UI／電話引擎驗收；真機結果按當前指紋與 scope 單獨報告。

## 0.6.1 — 2026-11-18

- 顯示名稱改為 Haaru，作者 Cekxri；保留内部模組 ID 及設定路徑以支援更新。
- KSU 簡介依近期已確認引擎狀態，顯示運行／未設定／暖機／休息／錯誤及指定 Nya 文案。
- 短句固定為「城堡之下的SIM國度.咖啡廳☕」，UTF-8 原文保留組合字元與 emoji。
- 加入約2秒、僅模組檔案的 Rust 觀察程式，內容變化才寫 metadata，不改 runtime hash；更新／版本與檔案競態拒絕覆寫。
- 主介面、相容介面、載入頁和 H 字母圖示同步品牌。
- 卡片分類與 metadata 寫入27個 Rust 契約情境通過；尚未 Android 真機驗收，KSU 畫面需刷新清單。

## 0.6.0 — 2026-11-18

- 保留真實 Kotlin／Compose 介面，把 JSON／shadow 驗證、SIM StringList、UTF-8 property 限制、FNV、UID／cache policy 和設定遷移改為 Rust。
- 安裝器改用四 ABI 的原生 Rust migrator，與舊 Java Properties／FileChannel 鎖及設定遷移格式保持相容。
- C++ Zygisk／ART／JNI 引擎呼叫 Rust 靜態核心；Java Root 控制器及 Android 接口保留。
- 加入實際 Rust／Java runtime、FNV、Properties、鎖互通及 C++ FFI 主機驗證；官方 Rust 1.100.0、Cargo.lock 與產物 SHA 固定。

這是所選核心的 Rust 遷移，不是全模組／UI 純 Rust；尚未 Android 真機驗收。

## 0.5.1 — 2026-11-18

- KSU 覆蓋更新保留 SIM 配置與外觀偏好；原值快取、引擎回報與交易資料在完整重啟後重建。
- 遷移驗證 schema、大小、模組 ID 與路徑，使用 Java 檔案鎖、原子寫入、回讀及失敗回復。遷移完成前暫時停用 staged 模組；保留原停用狀態，待移除模組不恢復舊設定。
- 更新等待重啟時暫停作用與設定寫入，避免新版快照和舊版後續操作混用。
- 未知／損壞的設定格式不再被 Controller 當作 schema 5 改寫。
- 外觀保存失敗仍可讀取狀態與執行還原，錯誤提示繼續保留。
- 主介面與相容介面從模組 metadata 顯示版本、唯讀與等待更新狀態。
- 封裝拒絕夾帶使用者設定、引擎快取、待更新／停用／移除旗標；加入可重複執行的發布包校驗。

更新保存安裝當下的快照，安裝後立即完整重啟；首次從 0.5.0 更新時，舊版無法阻止安裝後繼續修改。主機測試通過不等於 Android／Android 真機驗收。

## 0.5.0 — 2026-11-17

完成中央 phone Binder 回傳副本、兩鍵非持久 SIM property、phone 原值 shadow 與全域設定 UI。保留 28 國／74 電信商、逐卡操作、一鍵還原及 Miuix／Material 3／Backdrop；四 ABI 主機建置及 26 個瀏覽器模擬情境通過，未真機驗收。
