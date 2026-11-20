# Haaru 0.8.4 發佈準備

**0.8.4（versionCode 19）為公開預覽版。** 本版補上 radio 驗證繼承 Zygisk Unix socket 所需的限定 SELinux 權限；保留全部身分與協議檢查。當版建置、封裝及隱私驗證以隨附 manifests 為準。KernelSU 真機套用／還原仍待覆驗，`deviceTested=false`。

## 交付檔案

| 檔案 | 用途 |
| --- | --- |
| `Haaru-0.8.4.zip` | KSU 模組與同版本已簽 APK；安裝完成後完整重啟 |
| `Haaru-0.8.4.apk` | 原生 Miuix／GLES3 配套 App，同 package／簽章更新 |
| `Haaru-0.8.4-source.zip` | 白名單 source，排除私人簽章／診斷／設定 |
| `SHA256SUMS.txt` | 最終交付檔案校驗值 |
| build／companion manifests | 實際來源／工具／版本／產物／簽章指紋 |
| release-verification／device-report | 分開記錄發佈驗證與當前真機 scope |

ZIP 的同版 App 自動安裝與 Android 未就緒時開機續裝保留；KSU 執行按鈕開啟 App 或重試安裝 ZIP 內 APK，不向 latest 取錯版本。App 無需單獨 superuser 授權，模組 IPC 以真實 peer UID／package UID／APK signer 核對身份並只接受固定操作。

## 0.8.4 變更

- 新增 `sepolicy.rule`，只對 radio → zygote 的 Unix stream socket 授予 getattr/getopt/setopt/read/write，供現有 fstat、SO_TYPE、timeout 及 framed IPC 使用。
- 不增加 connectto、任意檔案、properties 或全域 permissive 權限。來源、App 與 hook 覆蓋的驗證界線維持原樣。
- 記錄連線身分檢查的 errno；特化前 checkpoint 加兩個 FD 旗標不能單獨證明實際斷線原因。
- 此版針對已觀察到的 metadata 權限拒絕修正。主機與封裝檢查不能代替真機覆驗。

## 最終建置與驗證

修正來源凍結後，依序建置與核對：

```powershell
python setup_android_sdk.py --accept-licenses
python build_rust.py
python build_native.py
python build_companion.py --java-home "C:/path/to/jdk-21"
python build.py
python verify_release.py
python privacy_guard.py --artifact dist/Haaru-0.8.4.zip --artifact dist/Haaru-0.8.4.apk --artifact dist/Haaru-0.8.4-source.zip --output dist/publication-privacy-audit.json
```

在 PATH 設定 JDK 21，供整包建置與 APK 驗證共用。以當次主機測試報告與 `dist/SHA256SUMS.txt`、`dist/release-verification.json`、`dist/publication-privacy-audit.json` 核對模組 ZIP、APK 與來源封裝。核對 ZIP 內的 APK 和獨立 APK 完全一致，以及簽章憑證與上一個已發佈版本相同。0.8.0 模擬器的來源指紋和數據見 [原生介面建置](UI_BUILD.md)。

`--skip-tests` 產物僅供開發；真實 aapt2／apksigner、來源hash與 APK／ZIP／source 交叉核對必須匹配最後修改。正式 `companion.prop` pin 由已驗證 APK 的公開package／certificateSHA256／protocol生成，更新同簽章App沒有versionCode鎖。

私人 `tools/companion-signing/haaru-release.jks` 及密碼必須私人備份並沿用以支援覆蓋更新，不提交至公開倉庫。source排除 `*.jks`／`*.keystore`、local.properties、Gradle／SDK／工具快取、使用者診斷與手機資料；不要將整個tools或dist上傳。

## 驗證界線

建置清單記錄實際 App、Rust 與 native 來源指紋。發佈驗證須與交付檔案、ZIP 內 APK 和來源封裝吻合。原生預覽報告只涵蓋各次實際執行的畫面與互動。

`deviceTested` 只能依當前真機報告、逐項成功與module／APK／native／companion hashes匹配成立，且只適用該scope。App可開啟或模擬器畫面不能證明phone引擎生效、所有ROM、DB/eSIM、雙卡、provider失效與卸載恢復均通過。使用者實體手機或SIM不由代理操作。

API29是安裝底線；仍需可用Zygisk／ART／LSPlant／電話服務出口。電話服務注入可能崩潰、影響訊號／通話或造成開機迴圈，主機編譯通過不等於真機安全保證。修改限於回應副本與非持久properties，不寫CarrierConfig／訂閱DB／eSIM／APN／MCCMNC，也不改IP／GPS／帳號或保證TikTok服務端地區。
