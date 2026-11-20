# Haaru 0.8.0 發佈準備

**0.8.0（versionCode 15）是尚未公開的本機候選。** 已取得 Android 模擬器的 3D 渲染、明暗主題與選中文字對比證據。手機效能和電話引擎仍待實機驗證，`deviceTested=false`。0.7.3 的 ZIP、APK 和來源封裝屬於歷史候選。

## 交付檔案

| 檔案 | 用途 |
| --- | --- |
| `Haaru-0.8.0.zip` | KSU 模組與同版本已簽 APK；安裝完成後完整重啟 |
| `Haaru-0.8.0.apk` | 原生 Miuix／GLES3 配套 App，同 package／簽章更新 |
| `Haaru-0.8.0-source.zip` | 白名單 source，排除私人簽章／診斷／設定 |
| `SHA256SUMS.txt` | 最終交付檔案校驗值 |
| build／companion manifests | 實際來源／工具／版本／產物／簽章指紋 |
| release-verification／device-report | 分開記錄發佈驗證與當前真機 scope |

ZIP 的同版 App 自動安裝與 Android 未就緒時開機續裝保留；KSU 執行按鈕開啟 App 或重試安裝 ZIP 內 APK，不向 latest 取錯版本。App 無需單獨 superuser 授權，模組 IPC 以真實 peer UID／package UID／APK signer 核對身份並只接受固定操作。

## 0.8.0 變更

- 以 TextureView、EGL、GLES 3 和程序化網格建立持續四模型場景。切頁時沿原拓撲移動與形變，配合觸摸回彈、細微漂浮與視差捲動。以 PBR 材質、逐物件背面深度、景深與克制的局部自發光表現形體。
- Miuix／Backdrop／模型／燈光共用選中色彩角色，經低彩度表面與小強調色分配；隨附 Manrope variable 字型與 OFL，CJK 系統 fallback。沒有套用 Material 3 預設控件或舊 Canvas 咖啡杯／固定紫色 About 主視覺。
- 底欄在玻璃指示塊上重繪選中的文字和圖示，保留原分頁按鈕的點擊與無障礙行為。
- 首次使用採紫羅蘭 Expressive 並跟隨系統明暗。Google Monet 可在設定開啟，已儲存的新舊偏好沿用原有行為。
- 依顯示器 vsync 提交畫面，不設 120 fps 上限。品質分級、省電、溫度、減少動畫和視窗生命週期都參與渲染控制。
- 保留逐卡草稿、set／重套、reset／disable 保留方案、forget／clear 刪SIM方案，以及預設收起診斷、偏好與授權全文。
- 啟用方案空字段保留已存值，UI 明示保留；完整回原始仍使用 reset。SIM 回報國家可能使用 getter fallback，不一律稱 original。RAM草稿動作明示載入已啟用方案／清空未儲存草稿。
- 繼續保留電話引擎原 FD 清單與 specialisation 後同 socket／protocol 驗證，沒有把 `exemptFd` 回傳當作無條件 fatal。

雕塑折射自身 GL 場景，底欄由 Backdrop 取樣即時原生背景。RTX 3060 加速的 60 Hz 模擬器記錄了 GLES 3.1 四頁渲染，暖機提交約 57.5–60 Hz，CPU 提交約 0.63–2.99 ms。這組效能數字來自 `f6937975`，目前尚無手機的實際呈現幀率。

## 最終建置與驗證

修正來源凍結後，依序建置與核對：

```powershell
python setup_android_sdk.py --accept-licenses
python build_rust.py
python build_native.py
python build_companion.py --java-home "C:/path/to/jdk-21"
python build.py
python verify_release.py
python privacy_guard.py --artifact dist/Haaru-0.8.0.zip --artifact dist/Haaru-0.8.0.apk --artifact dist/Haaru-0.8.0-source.zip --output dist/publication-privacy-audit.json
```

目前 App 通過 30 項主機測試。回歸與封裝檢查涵蓋 State、Controller、adapter、hook、Rust、native、IPC、安裝及更新流程、預設資料、偏好與色彩。原生預覽使用 1440 × 3200、530 dpi 的本機合成資料，透過來源指紋對應 debug 與 release。底欄修正版 `bbc739e8` 的六組深淺主題選中文字像素對比為 11.57–13.68:1。封裝時以 `dist/SHA256SUMS.txt`、`dist/release-verification.json` 和 `dist/publication-privacy-audit.json` 核對對應版本的模組 ZIP、APK 與來源封裝。

`--skip-tests` 產物僅供開發；真實 aapt2／apksigner、來源hash與 APK／ZIP／source 交叉核對必須匹配最後修改。正式 `companion.prop` pin 由已驗證 APK 的公開package／certificateSHA256／protocol生成，更新同簽章App沒有versionCode鎖。

私人 `tools/companion-signing/haaru-release.jks` 及密碼必須私人備份並沿用以支援覆蓋更新，不提交至公開倉庫。source排除 `*.jks`／`*.keystore`、local.properties、Gradle／SDK／工具快取、使用者診斷與手機資料；不要將整個tools或dist上傳。

## 驗證界線

建置清單記錄實際 App、Rust 與 native 來源指紋。發佈驗證須與交付檔案、ZIP 內 APK 和來源封裝吻合。原生預覽報告只涵蓋各次實際執行的畫面與互動。

`deviceTested` 只能依當前真機報告、逐項成功與module／APK／native／companion hashes匹配成立，且只適用該scope。App可開啟或模擬器畫面不能證明phone引擎生效、所有ROM、DB/eSIM、雙卡、provider失效與卸載恢復均通過。使用者實體手機或SIM不由代理操作。

API29是安裝底線；仍需可用Zygisk／ART／LSPlant／電話服務出口。電話服務注入可能崩潰、影響訊號／通話或造成開機迴圈，主機編譯通過不等於真機安全保證。修改限於回應副本與非持久properties，不寫CarrierConfig／訂閱DB／eSIM／APN／MCCMNC，也不改IP／GPS／帳號或保證TikTok服務端地區。
