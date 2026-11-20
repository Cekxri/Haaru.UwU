# 更新紀錄 / Changes

## 0.8.1 — 公開預覽 / Public preview

- 修正「重新套用已存方案」漏掉已停用方案的問題。前景會定期更新狀態，分開顯示部分生效與完整確認，讓過期的回報退出成功狀態。
- App 儲存與單卡重套時核對畫面最初讀到的 SIM。偵測到換卡時，模組會拒絕舊請求，App 也會在讀到新卡後清除上一張卡的草稿。
- 外觀偏好成功讀取後才開放修改，返回前景或手動重新讀取時再載入偏好。儲存顯示以模組核對過的回覆為準。
- 還原確認需要中央電話回應方案清除成功，並讀回兩項非持久屬性的原本值。多卡電信商名稱合併後超出系統位元組限制時，介面會提示縮短名稱。

延續 0.8.0 的 Miuix、Backdrop 與程序化 3D 場景。KernelSU 正式引擎的真機套用與還原仍待驗證。

- Fixed Reapply Saved Plans skipping stopped plans. The app updates status while in the foreground, distinguishes partial application from full confirmation and expires stale confirmations.
- The app ties saves and single-SIM reapplication to the SIM it read for the editor. The module rejects a stale request when it detects a swap, and the app drops the previous SIM's draft when it reads the replacement.
- Appearance settings become editable after a successful read and are read again when the app returns to the foreground or you refresh manually. The app reports a save when the module acknowledges the matching preferences.
- A restore needs a successful clear of the central phone response plan and a readback of both temporary properties. The app asks for shorter carrier names when their combined byte length exceeds Android's limit.

This version carries forward the Miuix, Backdrop and procedural 3D design from 0.8.0. Applying and restoring through the production KernelSU phone engine still require handset validation.

## 0.8.0 — 公開預覽 / Public preview

完整替換前端視覺與跨頁體驗，於 2026 年 11 月 20 日公開預覽。RTX 3060 加速的 60 Hz Android 模擬器已繪製四頁場景。修正版底欄選中文字在深淺主題中通過原生像素對比檢查。手機幀率與電話引擎仍待實機驗證。

- 新增 TextureView／EGL／GLES3 程序化 mesh 與 PBR／FBO 材質管線，四個有機模型在頁面間持續移動與變形，配合彈簧輸入與深度捲動。
- 模型、光源與 Miuix／Backdrop 共用目前色彩角色，經低彩度表面與小面積強調色分配；隨附 Manrope variable 字型與 OFL，CJK 使用系統 fallback。
- 跟隨實際 vsync、不設 120 fps 硬上限；品質分級、省電／thermal、減少動畫、背景／offscreen 與 surface 生命週期接入渲染控制。手機 FPS 尚未實測。
- 保留逐卡草稿、儲存／重套、還原保留與清除刪方案，詳細診斷預設收起。啟用方案的空字段明示保留已存值；完整回原始仍使用 reset。
- 「SIM 回報國家」不再一律宣稱 original；RAM 操作改為「載入已啟用方案／清空未儲存草稿」，不修改持久方案。
- 首頁、工具、設定、關於及浮層採新主題與場景，取代 Canvas 咖啡杯與固定紫色關於頁。
- 在液態玻璃指示塊上方重繪選中的文字和圖示，維持對比。沿用原有的分頁點擊區與無障礙焦點。
- 首次使用採紫羅蘭 Expressive 並跟隨系統明暗。Google Monet 可在設定開啟，更新會沿用既有偏好。

The rebuilt frontend was published as a preview on 20 November 2026. The app drew all four scenes on a 60 Hz Android emulator accelerated by an RTX 3060. Pixel samples confirmed selected navigation label contrast in light and dark themes. Handset frame timing and phone engine validation are pending.

- Added a TextureView/EGL/GLES3 procedural-mesh and PBR/FBO pipeline, with four persistent organic models, page morphing, spring input and depth-dependent scrolling.
- Models, lighting, Miuix and Backdrop share the selected roles through quiet surfaces and small accents. Manrope variable fonts and OFL are bundled, with system CJK fallback.
- Display vsync drives scheduling without a hard 120 fps cap. Quality, power saving, thermal conditions, reduced motion and background/offscreen/surface lifecycle paths control rendering; handset FPS has not been measured.
- Preserved per-SIM drafts, save/reapply, restore-with-retention and clear-with-deletion. Diagnostics remain secondary. Empty fields in active profiles preserve saved values; reset returns the profile to original reporting.
- Renamed the country readout to reported SIM country. Loading an active plan or clearing an unsaved draft is explicitly RAM-only.
- Replaced the Canvas cup and fixed-violet About design across pages and overlays.
- Drew the active navigation label and icon above the glass indicator to preserve contrast. Retained the existing tab hit areas and accessibility focus.
- Fresh installs use Violet with Expressive colours and follow the system’s light or dark appearance. Enable Google Monet in Settings. Updates retain the existing preference.

## 0.7.3 — 未公開歷史候選 / Unpublished historical candidate ☕

以下為尚未公開、視覺方向已由 0.8.0 取代的 0.7.3 變更。本機建置、必要主機回歸與全新原生模擬器畫面已核對；電話引擎修正仍待真機驗證。

These 0.7.3 changes were not published, and their visual direction is superseded by 0.8.0. Local builds, required host regression and fresh native emulator screens have been checked; the engine correction still awaits handset validation.

- 修正 `exemptFd` 回報被直接判為 fatal：保留原 FD 列表，並在 specialisation 後實際驗證同一 socket 和 protocol。
- 介面改為只用 Miuix，移除控件風格選擇；保留 Google Monet、15 個手動配色與 HSV 選色器。
- 色彩引擎更新至 MaterialKolor utilities 6.0.0，可選 Expressive／Vibrant／Tonal Spot／Neutral 2025 或 CMF 2026。Monet 種子色與手動色均按所選風格重新生成角色，`colourStyle` 隨外觀設定保存及遷移。
- 以 Streama 可見底欄互動作參考，按公開 catalogue 來源重做底欄；本版真機效果仍待驗收。
- 首頁狀態卡源碼改為連續圓角、非對稱文字、快取陶瓷杯繪圖與點陣光影；減少動畫時靜態，窄畫面／大字體採上下排列，頁面與 SIM 切換間距已整合，當前 App 編譯及 1440 × 3200／530 dpi 原生渲染已核對。
- 設定最底的關於入口改為獨立紫夜漸層頁，加入原創咖啡杯品牌、版本與分組倉庫／更新／作者／鳴謝連結；隨附授權全文可離線閱讀。獨立路由與返回已串接，關於頁隱藏底欄；本機關於入口／返回／鳴謝及 MIT 授權全文閱讀已核對。
- Preserved the original file-descriptor list and added post-specialisation verification on the same socket and protocol; `exemptFd` results no longer cause an unconditional fatal error.
- The interface now uses Miuix only, with no control-style selector. Google Monet, 15 manual palettes and the HSV colour picker remain.
- Updated the colour engine to MaterialKolor utilities 6.0.0, with Expressive, Vibrant, Tonal Spot and Neutral 2025, plus CMF 2026. Wallpaper seeds and manual colours generate roles using the selected style; `colourStyle` is saved and migrated with appearance settings.
- Rebuilt the dock using observed Streama interactions and attributed public catalogue sources. Device effects for this release still await validation.
- Added a continuous-corner home-card design with asymmetric text, a cached ceramic-cup illustration and dotted lighting. Reduced motion keeps it still; narrow screens and larger text use vertical placement. App integration and SIM-selector spacing are in place; current compilation and native rendering at 1440 × 3200 and 530 dpi have been checked.
- Reworked About into a separate violet-gradient page with original cup branding, versions and grouped repository, release, author and credit links. Bundled licence texts can be read offline. Page routing and return navigation are in place, with the dock hidden on About; local screens, return navigation, credits and MIT licence reading have been checked.

## 0.7.2 — 2026-11-19 ☕

- 修正 APK 標準輸入安裝命令的參數相容性，避免輸入標記被視為未知選項。
- 加入該命令格式的回歸檢查；本版仍待實機驗收。
- Fixed APK installation arguments so Android reads standard input without rejecting its marker as an unknown option.
- Added regression checks for this command format; device validation is still pending.

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
