# Haaru 原生 Android 介面（0.7.0）

主介面在 `companion/`，以 Kotlin／Compose Android 編譯成 `dist/Haaru-0.7.0.apk`。使用真正的 Miuix、AndroidX Material 3 與 Backdrop；使用 Android 系統字型，不打包舊 WASM／Skia WebUI 或大型字型資產。KSU WebUI 首頁只開啟 App、顯示安裝提示，另保留 Classic 相容入口。

## 建置

```powershell
python setup_android_sdk.py --accept-licenses
python build_companion.py --java-home "C:/path/to/jdk-21"
python build.py
python verify_release.py
```

需要 JDK 21、Android SDK Platform **37.2**、官方 build-tools **36.0.0** 與 platform-tools **37.0.3**。App 的 compileSdk 為 37.2、minSdk 為 29、targetSdk 為 36；37.2 是編譯依賴要求，不是手機最低版本。AGP 9.5.1、外部 Kotlin／Compose compiler 2.4.20、Compose 1.12.0、Miuix 0.9.4、Material 3 1.4.0、Backdrop 2.0.1 固定於 App 建置與 companion manifest。Gradle wrapper 沿用專案已固定的 wrapper。native／Rust 引擎需先用相應建置腳本完成四 ABI 產物。

`setup_android_sdk.py` 僅下載固定官方 Windows SDK archives，驗證官方 SHA1 與固定 SHA256，檔案留在 `tools/android-sdk/`。Platform 37.2 r2 的官方 SHA1 是 `d52b3062af96f7c4dfdca5a479cd1db33ae83762`。腳本建立對應 local `package.xml`，避免 37.2 的十進位 API 無法只由舊 `source.properties` 辨識。兩份官方 license 原文在 `sdk-setup/`；`--accept-licenses` 明確接受它們，marker 使用原文 trim 後的 SHA1。已有接受紀錄則不需再次接受。`--offline` 使用本地 archive，`--verify-only` 只核對實際 SDK 檔案，不修改或下載。其他作業系統請提供相同版本 SDK，現有 helper 固定 Windows archives。

`build_companion.py` 編譯 release APK、zipalign、以持久私人金鑰簽署，輸出 `dist/companion-build-manifest.json`。私鑰和密碼在 `tools/companion-signing/`；維護者必須私人備份，以便後續相同簽章覆蓋更新。這些檔案與 `*.jks`、`*.keystore`、`local.properties`、`.gradle` 不進 source ZIP。

manifest 包含 APK hash／bytes、applicationId、versionCode、來源檔 hash／bytes、canonical source tree hash、實際簽章憑證 SHA256 與依賴版本。`build.py` 和 `verify_release.py` 再次執行 aapt2／apksigner，並校验真實 DEX checksum／signature；改過來源卻沿用舊 APK、漏記新增 App 檔案或簽章不符會拒絕發佈。模組的 `companion.prop` 由驗證過的 package／公開 signer SHA256／protocol=1 確定性生成；ZIP、source 和 manifest 交叉核對這份 pin。

## 操作與效能

ModuleBridge 使用 LocalSocket 連到模組的受限 IPC 服務，App 不啟動 `su`、不要求獨立 superuser 授權。模組服務從 SO_PEERCRED 取得實際 UID，再核對 PackageManager 的 package UID／packages-for-UID 和已安裝 APK signer；身份驗證使用公開憑證 pin，不信任客戶端自報 package／UID。只允許固定的狀態／SIM／外觀操作，不提供任意 shell、路徑或 Root 程式執行。

SIM 槽位、ISO、名稱與請求長度在 App 和服務端驗證，IPC 在背景 IO、操作序列化。Root 權限只由 KSU 模組服務持有，設定仍保存於模組目錄。同簽章 APK 更新沿用授權身份，不鎖 versionCode；簽章更換需更新模組 pin 與相應 APK。

選單只修改草稿，保存才寫計畫；保存成功與中央引擎 applied 分開呈現。原始 carrier label、CarrierConfig override 和保存的設定保持不同語義。完整 28 國／74 電信商從原資料同步，原值逐項測試。

原生頁面不需 WebView WasmGC／WebGL／字型載入。動畫和玻璃由 Compose／Backdrop 執行；系統關閉動畫或使用者選擇減少動畫時應遵循偏好。編譯、來源檢查或桌面 mock 都不能證明某台手機達到指定幀率，真機互動與 frame timing 須另外驗收。

## 證據範圍

建置保留 Controller／adapter／hook／Rust／native／Classic 命令契約回歸；新增原生 launcher 與 preset parity，實際 APK 結構／版本／簽章由發佈驗證檢查。舊 `dist/ui-browser-report.json` 是歷史 WASM 模擬證據，不是本版原生 App 或電話引擎驗收。

`dist/device-report.json` 需記錄測試設備、scope、逐項結果與當前 module/APK/native/companion fingerprints。只測 App 開啟不可延伸為全域 SIM、DB/eSIM、雙卡、provider 故障或卸載撤除均通過。這些項目按實際成功 scope 分開報告。
