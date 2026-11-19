# Haaru 0.7.1 發佈

## 安裝檔案

| 檔案 | 用途 |
| --- | --- |
| `Haaru-0.7.1.zip` | 在 KernelSU 刷入模組，包含同版 APK，完成後完整重啟 |
| `Haaru-0.7.1.apk` | 原生介面 APK，可覆蓋更新或另外安裝 |
| `Haaru-0.7.1-source.zip` | 模組與 App 原始碼，不含私人簽章 |
| `SHA256SUMS.txt` | 三個檔案的 SHA256 |
| `build-manifest.json`／`companion-build-manifest.json` | 來源、工具、版本、產物與簽章指紋 |
| `release-verification.json` | 實際發佈包與 APK 驗證結果 |
| `device-report.json` | 單獨記錄具體設備和真機測試 scope |

模組 ZIP 包含同版已簽 APK，刷入時自動安裝；Android 尚未就緒時，於開機後續裝。KSU 執行按鈕開啟原生 App，缺少 App 時重試安裝 ZIP 內的同版本 APK。首次安裝沒有啟用配置。

App 無需獨立 superuser 授權。Root 由 KSU 模組執行，模組 IPC 服務驗證真實 peer UID／安裝 package UID／APK signer 後，只接受固定設定／狀態命令。公開 `companion.prop` pin 由已驗證 APK 生成，更新同簽章 APK 不受 versionCode 鎖定。

## 0.6.1 問題修復

- 改為原生 Compose Android 介面，主流程不再啟動 WebView WASM、載入大型字型或顯示 JSON。
- 修復 contextless TelephonyManager 建立 subscription manager 的 null Context 崩潰；原始 carrier label 獨立讀取。
- 保留實際中央引擎初始化原因，另顯示 ACK 格式／SDK 驗證錯誤。
- 保留原 SIM 設定、Rust 核心與覆蓋更新流程；早期內部 module ID 的設定依驗證過的移轉流程保存。
- App 通訊改為模組提供的受限 LocalSocket IPC，免獨立 superuser 授權；Root 服務驗證 UID、package 與實際 APK signer。

App 編譯／可開啟與 SIM 全域效果是不同驗收；具体成功項目以當前真機報告為準。

## 維護者建置與校驗

先建 Rust／native，再建 App、模組並驗證：

```powershell
python setup_android_sdk.py --accept-licenses
python build_rust.py
python build_native.py
python build_companion.py --java-home "C:/path/to/jdk-21"
python build.py
python verify_release.py
```

`build.py` 核對當前來源並執行必要回歸，`--skip-tests` 產物只能供開發，不通過正式 release verification。APK 由官方 aapt2 確認 package／version，apksigner 確認簽章，另校驗 ZIP CRC、binary manifest、DEX checksum／signature、source ZIP 和四 ABI ELF。

私人 `tools/companion-signing/haaru-release.jks` 與密碼需備份並持續沿用，才能安裝新版 APK 覆蓋舊版。不得提交至 GitHub／source ZIP。source guard 亦排除 `*.jks`、`*.keystore`、`local.properties`、`.gradle` 和工具快取。

`deviceTested` 只在真機報告成功、逐項測試通過並匹配當前 module/APK/native/companion hashes 时成立，而且只適用報告 scope。舊 WASM browser mock 或別版產物不作本版裝置證據。
