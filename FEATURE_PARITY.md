# Haaru 功能與驗收（0.7.0）

原預設來源固定於 [預設資料來源](https://github.com/a5533348/Nrfr/commit/6c05f224dc802d0df952db93a6d2852a91c9edb0)，保留完整 28 國與 74 電信商 code／displayName。介面改為獨立 Compose Android App，模組仍採中央 phone Binder clone＋兩個非持久 property＋同 boot 原值 shadow。

| 功能 | 本版行為 | 驗證界線 |
| --- | --- | --- |
| SIM 枚舉與身分 | 活躍 subId／slot／身分匹配，歧義或缺卡隔離 | Controller／AIDL 回歸；OEM 動態雙卡另驗 |
| 原始 SIM 國家 | 靜態 subscription／phone getter；必要時實際 Context；同 boot shadow 優先 | Context NPE 回歸與 adapter；空原值不猜測 |
| 原始電信商名稱 | 獨立 originalCarrierName，由活躍 SubscriptionInfo 讀取 | 雙卡、CharSequence、fallback、權限回歸 |
| 28 國／74 ISP | 原生 App、Classic 與原始資料逐項一致 | preset parity |
| 搜尋／自訂／單欄修改 | 逐卡草稿，保存才寫計畫；ISP 不暗改國家 | Controller 回歸；App 真機互動另驗 |
| 還原／移除 | 還原停止覆寫保留值；移除／清除才刪設定 | Controller；實際回復須當前 ACK |
| 全域作用 | 中央回應副本及 gsm.sim.operator.iso-country／alpha | native／Rust 契約；真機 caller 另驗 |
| phone／root 原值 | 原 property shadow、原 Binder 控制讀、自然更新日誌 | 主機回歸；DB/eSIM/OEM 路徑另驗 |
| 套用狀態 | 模組檔案 verified 與當前 boot/hash/新鮮 applied ACK 分開 | Controller／Rust status 契約 |
| 介面 | 原生 Miuix／Material 3／Backdrop、系統字型 | APK 編譯／來源與簽章；流暢度另验 |
| App／模組 IPC | App無su；模組驗證 peer UID／package／APK signer，只提供固定設定操作 | App／服務契約；無superuser授權真機流程另驗 |
| KSU 第一按鈕 | 開啟已安裝 Haaru App或顯示安裝提示 | launcher 契約；KSU 真機另验 |
| ZIP 覆蓋更新 | 保留安裝當下 SIM／外觀快照與停用狀態 | Rust／Java 相容／安裝生命週期回歸 |
| APK 更新 | 同 package＋同簽章覆蓋安裝 | 真實 aapt2／apksigner；設備更新另验 |
| IPC 身份 pin | companion.prop 從已驗證 APK 生成公開 package／certificateSha256／protocol=1，無versionCode鎖 | 發佈／source／APK交叉核對 |
| 完整撤除 | KSU 解除安裝＋完整手機重啟 | KSU 固定源碼／主機 invariant；metadata 前後另验 |

API 29 是底線，中央引擎依實際 phone classloader／出口探測；不宣稱所有 ROM 均相容。必要 hook、可信 SIM 映射或原值不完整時拒絕套用。UTF-8 property 結果最多 91 bytes，超限不截斷；兩鍵寫入／回讀確認後才標 applied。

本版修復舊 Context-less TelephonyManager 路徑，並分開保留真正 native 初始化錯誤與 ACK validationError；這些修復不能單独證明全域作用已成功。空 SIM country 可能是設備真實讀值，不能以 ready:true 或非空電信商推定國家。

正式發佈的 APK／DEX／簽章／來源／四 ABI 驗證在 `dist/release-verification.json`；真機結果在 `dist/device-report.json`，需匹配當前 fingerprints。App 開啟、全域 caller 一致、DB/eSIM 不污染、雙卡更新、phone/provider 失效與卸載＋重啟撤除分開驗收。沒有成功證據的項目保留待驗。

這些 SIM 顯示／查詢值不改 IP、GPS、APN、MCC/MNC、實體 SIM 或帳號，亦不保證 TikTok 服務端地區。完整原理與可逆性見 [README.md](README.md) 和 [平台相容性與副本原理](COMPATIBILITY.md)。
