# Haaru 0.9.2 發佈準備

**0.9.2（versionCode 22）為公開預覽版。** 本版完整修訂電話引擎初始化生命週期，加入真實 Application Context 備援、呼叫端去最佳化與 boot heartbeat，並修正原值更新競態及斷線回報。SELinux 模式與限定規則保持不變。所有主機／模擬器證據均不代替真機完整驗收，`deviceTested=false`。

## 交付檔案

| 檔案 | 用途 |
| --- | --- |
| `Haaru-0.9.2.zip` | KSU 模組與同版本已簽 APK；安裝完成後完整重啟 |
| `Haaru-0.9.2.apk` | 原生 Miuix／GLES3 配套 App，同 package／簽章更新 |
| `Haaru-0.9.2-source.zip` | 白名單 source，排除私人簽章／診斷／設定 |
| `SHA256SUMS.txt` | 最終交付檔案校驗值 |
| build／companion manifests | 實際來源／工具／版本／產物／簽章指紋 |
| release-verification／device-report | 分開記錄發佈驗證與當前真機 scope |

ZIP 的同版 App 自動安裝與 Android 未就緒時開機續裝保留；KSU 執行按鈕開啟 App 或重試安裝 ZIP 內 APK，不向 latest 取錯版本。App 無需單獨 superuser 授權，模組 IPC 以真實 peer UID／package UID／APK signer 核對身份並只接受固定操作。

## 0.9.2 變更與驗證界線

- attach 回呼與真實 Application Context 查找共用單次初始化；try-lock 不阻塞主線程等待 classloading worker。每秒保活，60 秒有界等待 Context，持續連線後由同一 worker 接續 runtime。
- 去最佳化 LoadedApk／Instrumentation 的 make/newApplication 呼叫端，處理短方法已被 inlining 的情況；callee 的去最佳化不能取代 caller 去最佳化。
- publication mutex 內先核對 baseRevision/baseVerified 再寫入覆蓋，避免以過期自然值開始套用。保留原值驗證、還原、換卡與完整掛鉤覆蓋的獨立證據。
- 斷線回報只保留真正 bootstrap failure，不讓自然更新進度文案遮住傳輸原因。
- 獨立 HAARU_BUILD_ENGINE_SMOKE 預設 OFF；release 不含測試 JNI。合成 emulator app_process 使用正式 ART resolver/LSPlant/installMethod，驗證 caller 去最佳化、真正 Application.attach、8,000 並行 hook/backup 呼叫和 production Context 查找。沒有 phone/SIM API 呼叫；API33 模擬器不能證明 SDK36 真機相容性。
- App／模組／Cargo 版本統一為 0.9.2，versionCode 22，已安裝誤發版也能直接更新。
- README、SELinux 規則與系統模式沿用。真機完整初始化／SIM 套用／還原仍待覆驗。

## 最終建置與驗證

修正來源凍結後，依序建置與核對：

```powershell
python setup_android_sdk.py --accept-licenses
python build_rust.py
python build_native.py
python build_companion.py --java-home "C:/path/to/jdk-21"
python build.py
python verify_release.py
python privacy_guard.py --artifact dist/Haaru-0.9.2.zip --artifact dist/Haaru-0.9.2.apk --artifact dist/Haaru-0.9.2-source.zip --output dist/publication-privacy-audit.json
```

在 PATH 設定 JDK 21，供整包建置與 APK 驗證共用。以當次主機測試報告與 `dist/SHA256SUMS.txt`、`dist/release-verification.json`、`dist/publication-privacy-audit.json` 核對模組 ZIP、APK 與來源封裝。核對 ZIP 內的 APK 和獨立 APK 完全一致，以及簽章憑證與上一個已發佈版本相同。0.8.0 模擬器的來源指紋和數據見 [原生介面建置](UI_BUILD.md)。

`--skip-tests` 產物僅供開發；真實 aapt2／apksigner、來源hash與 APK／ZIP／source 交叉核對必須匹配最後修改。正式 `companion.prop` pin 由已驗證 APK 的公開package／certificateSHA256／protocol生成，更新同簽章App沒有versionCode鎖。

私人 `tools/companion-signing/haaru-release.jks` 及密碼必須私人備份並沿用以支援覆蓋更新，不提交至公開倉庫。source排除 `*.jks`／`*.keystore`、local.properties、Gradle／SDK／工具快取、使用者診斷與手機資料；不要將整個tools或dist上傳。

## 驗證界線

建置清單記錄實際 App、Rust 與 native 來源指紋。發佈驗證須與交付檔案、ZIP 內 APK 和來源封裝吻合。原生預覽報告只涵蓋各次實際執行的畫面與互動。

`deviceTested` 只能依當前真機報告、逐項成功與module／APK／native／companion hashes匹配成立，且只適用該scope。App可開啟或模擬器畫面不能證明phone引擎生效、所有ROM、DB/eSIM、雙卡、provider失效與卸載恢復均通過。使用者實體手機或SIM不由代理操作。

API29是安裝底線；仍需可用Zygisk／ART／LSPlant／電話服務出口。電話服務注入可能崩潰、影響訊號／通話或造成開機迴圈，主機編譯通過不等於真機安全保證。修改限於回應副本與非持久properties，不寫CarrierConfig／訂閱DB／eSIM／APN／MCCMNC，也不改IP／GPS／帳號或保證TikTok服務端地區。
