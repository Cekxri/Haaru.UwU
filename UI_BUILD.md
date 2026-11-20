# Haaru 原生 Android 介面（0.8.0 本機候選）

0.8.0（versionCode 15）是尚未公開的前端重構。目前來源 `9dd21538` 已完成 App 建置與 30 項主機測試，包含首次啟動配色及舊偏好相容。API 33 本機模擬器使用 RTX 3060 加速，已取得四頁原生場景、明暗主題與底欄選中標籤的像素證據。0.7.3 產物與報告屬於歷史版本。

主介面在 `companion/`，以 Kotlin／Compose Android 編譯成 `dist/Haaru-0.8.0.apk`。介面使用 Miuix、Backdrop、原生 GLES3 場景與隨附 Manrope 字型，CJK 由 Android 系統 fallback；沒有控件風格切換。模組執行按鈕負責開啟原生 App 或重試安裝同版本 APK。

## 建置

```powershell
python setup_android_sdk.py --accept-licenses
python build_companion.py --java-home "C:/path/to/jdk-21"
python build.py
python verify_release.py
```

需要 JDK 21、Android SDK Platform **37.2**、官方 build-tools **36.0.0** 與 platform-tools **37.0.3**。App 的 compileSdk 為 37.2、minSdk 為 29、targetSdk 為 36；37.2 是編譯依賴要求，不是手機最低版本。AGP 9.5.1、外部 Kotlin／Compose compiler 2.4.20、Compose 1.12.0、Miuix 0.9.4、Backdrop 2.0.1、MaterialKolor material-color-utilities 6.0.0 固定於 App 建置設定；重建後由 companion manifest 記錄實際來源與依賴。Gradle wrapper 沿用專案已固定的 wrapper。native／Rust 引擎需先用相應建置腳本完成四 ABI 產物。

`setup_android_sdk.py` 僅下載固定官方 Windows SDK archives，驗證官方 SHA1 與固定 SHA256，檔案留在 `tools/android-sdk/`。Platform 37.2 r2 的官方 SHA1 是 `d52b3062af96f7c4dfdca5a479cd1db33ae83762`。腳本建立對應 local `package.xml`，避免 37.2 的十進位 API 無法只由舊 `source.properties` 辨識。兩份官方 licence 原文在 `sdk-setup/`；`--accept-licenses` 明確接受它們，marker 使用原文 trim 後的 SHA1。已有接受紀錄則不需再次接受。`--offline` 使用本地 archive，`--verify-only` 只核對實際 SDK 檔案，不修改或下載。其他作業系統請提供相同版本 SDK，現有 helper 固定 Windows archives。

`build_companion.py` 編譯 release APK、zipalign、以持久私人金鑰簽署，輸出 `dist/companion-build-manifest.json`。私鑰和密碼在 `tools/companion-signing/`；維護者必須私人備份，以便後續相同簽章覆蓋更新。這些檔案與 `*.jks`、`*.keystore`、`local.properties`、`.gradle` 不進 source ZIP。

manifest 包含 APK hash／bytes、applicationId、versionCode、來源檔 hash／bytes、canonical source tree hash、實際簽章憑證 SHA256 與依賴版本。`build.py` 和 `verify_release.py` 再次執行 aapt2／apksigner，並校验真實 DEX checksum／signature；改過來源卻沿用舊 APK、漏記新增 App 檔案或簽章不符會拒絕發佈。模組的 `companion.prop` 由驗證過的 package／公開 signer SHA256／protocol=1 確定性生成；ZIP、source 和 manifest 交叉核對這份 pin。

## 操作與效能

首次使用預設採紫羅蘭 Expressive，明暗跟隨系統。Android 12（API 31）以上開啟 Google Monet 後，從系統 tonal palette 讀取種子色（依序嘗試 `system_accent1_500`、`system_accent1_600`、`system_accent1_400`），再按所選 Material 色彩規範生成全部介面角色。桌布或系統設定改變時重新讀取；不直接沿用系統已生成的角色，因此切換風格也會改變桌布配色的效果。系統未提供可用種子色時使用手動配色並說明原因。

MaterialKolor material-color-utilities **6.0.0** 提供五種色彩風格：Expressive、Vibrant、Tonal Spot、Neutral 使用 `SPEC_2025`，CMF 使用 `SPEC_2026`；均以 phone platform、正常對比度生成角色，由 `CafeColours.kt` 分配為低彩度多層表面、可讀文字及小面積強調色，再投影到 Miuix、Backdrop 和 3D 模型／燈光。Material utilities 提供色彩算法，不直接決定控件或預設 Material 3 視覺。規範年份依各風格實際支援的算法標示，Expressive 不會因引擎升級而被寫成 2026。

關閉動態色彩後提供 15 個預設（含 9 個紫色系）、色相／飽和度／亮度選擇器與可選的進階色碼；手動種子色也使用同一組風格。沒有設定或設定物件為空時，`dynamicColour` 預設為 `false`。既有明確儲存的 `true` 或 `false` 會保留，非空的舊設定物件若缺少此欄位，則沿用原本的 `true` 預設。`customColour` 為空字串或 `#RRGGBB`。`colourStyle` 可為 `EXPRESSIVE`／`VIBRANT`／`TONAL_SPOT`／`NEUTRAL`／`CMF`，缺少欄位時預設 `EXPRESSIVE`。App、Java Controller 和 Rust 更新遷移保存這個欄位；外觀切換不會重新套用 SIM 計畫。早期 `MATERIAL3` 外觀值可以讀取，但介面只呈現 Miuix，新保存的 `style` 固定為 `MIUIX`。

前端協調器 `HaaruApp.kt` 持有 State、每卡草稿、偏好、頁面及浮層請求。`StatusPresentation.kt` 只呈現真實狀態；`PlanPanels.kt` 提供 SIM 方案、重套／還原／清除與預設收起的診斷。Compact 使用三頁 Backdrop 底欄，expanded 使用側邊 rail 與獨立操作欄；切頁不重建 State，也不送 SIM 命令。原始 carrier label、SIM 回報國家、草稿、已存方案與 applied ACK 分開。

`set` 的空字段在同 SIM 已啟用方案上保留既有值；要完整回原始，使用 `reset`，不是以空值假裝清除個別 override。「載入已啟用方案／清空未儲存草稿」只修改 RAM，與 `reset`／`forget` 分開。Save 仍依 fresh、非 readOnly／upgrade／busy、SIM ready 及合法非空草稿判斷，不要求引擎已初始化或全域已確認。

「關於信息」連接獨立 `AboutPage`，顯示 App／模組版本、作者、倉庫／Releases 與鳴謝；頁面沿用目前的主題角色及持續 3D 場景，不再使用固定紫色背景或 Canvas 咖啡杯主視覺。返回按鈕與 Android 返回鍵回到設定，底欄在關於頁隱藏。隨附 `assets/licences/*.txt` 在背景 IO 讀取 UTF-8 全文，包含 Manrope OFL，供離線閱讀。修正版模擬器已核對關於頁返回設定。先前的同一介面流程另核對了鳴謝與 Manrope OFL 全文。

底欄的公開參考與改編來源記錄於 [NOTICE](NOTICE)，包括 AndroidLiquidGlass catalogue 的 `LiquidBottomTabs`、`DampedDragAnimation` 及相關 helpers。底欄使用 Backdrop 取樣、模糊與折射。選中指示塊上方另繪一層文字和圖示，維持玻璃效果開啟時的可讀性。原有分頁按鈕承擔點擊與無障礙焦點。

ModuleBridge 使用 LocalSocket 連到模組的受限 IPC 服務，App 不啟動 `su`、不要求獨立 superuser 授權。模組服務從 SO_PEERCRED 取得實際 UID，再核對 PackageManager 的 package UID／packages-for-UID 和已安裝 APK signer；身份驗證使用公開憑證 pin，不信任客戶端自報 package／UID。只允許固定的狀態／SIM／外觀操作，不提供任意 shell、路徑或 Root 程式執行。

SIM 槽位、ISO、名稱與請求長度在 App 和服務端驗證，IPC 在背景 IO、操作序列化。Root 權限只由 KSU 模組服務持有，設定仍保存於模組目錄；外觀使用 `data/appearance.json`，更新時移轉早期外觀檔。同簽章 APK 更新沿用授權身份，不鎖 versionCode；簽章更換需更新模組 pin 與相應 APK。

選單只修改草稿，保存才寫計畫；保存成功與中央引擎 applied 分開呈現。原始 carrier label、CarrierConfig override 和保存的設定保持不同語義。完整 28 國／74 電信商從原資料同步，原值逐項測試。

Manrope variable 字型隨 `res/font/manrope.ttf` 打包，OFL 隨授權 assets 提供，CJK 使用系統 fallback；不需要執行時遠端字型下載。表單沿用原生 Miuix affordances、48 dp 目標、IME／safe-area、文字放大與系統返回。

## 3D 場景與生命週期

`SculptureScene.kt` 以透明 TextureView 接入 Compose，`SculptureRenderer.kt` 在單一 GL Looper 上持有 EGL14／GLES30，`SculptureMesh.kt` 提供四組程序化有機網格。一個不規則有孔主體與三個次級形體持續存在，依四頁參數沿相同 UV 拓撲變形，配合切頁軌跡、觸摸彈簧與深度捲動。Compose 不逐幀接收 GL 時鐘，模型也不會在換頁時替換成靜態圖片。

材質實作使用 GGX／Smith／Schlick、角色色轉 linear RGB、程序化 softbox／雨窗環境。GL FBO 提供不透明場景和逐物件背面深度，透明材質從 IOR、厚度、Fresnel 與 roughness mipmap 計算折射。高、中品質啟用相機景深，局部自發光由目前主題色推導。雕塑取樣自己的 GL 場景，底欄對原生介面的背景取樣則由 Backdrop 負責。

Choreographer 跟隨實際 vsync，DisplayManager 接收刷新率變化。場景活躍時，App 可請求系統在相同解析度下採用較高的刷新率，實際模式由系統決定。提交排程不設 120 fps 上限。品質分級優先降低 framebuffer、網格及取樣成本，省電或溫度條件也可降低提交頻率。減少動畫時只在輸入改變時更新靜態畫面。App 進入背景、舞台離開可見範圍或 surface 消失時停止逐幀提交。舞台離開可見範圍時清除一次已快取的場景，避免文字下方殘留模型。

RTX 3060 加速的 60 Hz 模擬器以 GLES 3.1 繪製四頁，記錄高品質、景深、局部自發光與背景暫停／恢復。暖機樣本的渲染提交率約 57.5–60 Hz，CPU 提交耗時約 0.63–2.99 ms。這些數值屬於 `f6937975` 的 `HaaruSculpture` 記錄，量測範圍是模擬器提交，手機實際呈現幀率及長時間負荷仍待量測。

## 證據範圍

底欄前景修正版 `bbc739e8` 以 1440 × 3200、530 dpi 的合成資料預覽取樣。首頁、工具、設定的六組深淺主題選中文字對比為 11.57–13.68:1。該批也核對薄荷手動色、Vibrant、深淺切換與關於頁返回。其他版型和互動應以帶來源指紋的原生報告為準。APK、模組 ZIP、來源封裝與隱私掃描使用各自的當前報告，核對指令見 [發佈準備](RELEASE.md)。

`dist/device-report.json` 需記錄測試設備、scope、逐項結果與當前 module/APK/native/companion fingerprints。只測 App 開啟不可延伸為全域 SIM、DB/eSIM、雙卡、provider 故障或卸載撤除均通過。這些項目按實際成功 scope 分開報告。
