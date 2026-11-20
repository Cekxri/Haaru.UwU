<div align="center">

# Haaru

**城堡之下的SIM國度.咖啡廳☕**

給 SIM 的小小咖啡廳。選好一個地方，再慢慢喝咖啡。

^ ܸ⩌⩊⩌ ܸ^

[繁體中文](#中文) · [English](#english) · [下載 / Download](https://github.com/Cekxri/Haaru.UwU/releases)

</div>

<a id="中文"></a>

## 你好，這裡是 Haaru ☁️

**0.8.0 是尚未公開的本機候選版本。** 四頁 3D 場景與新底欄已在本機 Android 模擬器核對。手機幀率與電話引擎仍待實機驗證。下載請以 Releases 已公開的版本為準。

Haaru 是 KernelSU 模組，讓你替每張 SIM 選擇 Android 回報的國家與電信商名稱。操作使用 Miuix，畫面則是隨頁面移動與變形的有機 3D 雕塑、低彩度表面與真正的液態玻璃底欄。模型、光源和控件都跟著你選的色彩改變。

可以從 **28 個國家、74 個電信商** 裡挑選，也可以寫自己的名稱。兩張卡各自設定，更多插槽也會依系統提供的資料顯示；不會把某一台手機的插槽資料寫死在程式裡。

### 先坐下，再開始 ☕

1. 到 [Releases](https://github.com/Cekxri/Haaru.UwU/releases) 下載模組 ZIP。
2. 在 KernelSU 刷入，然後完整重新開機。
3. 從桌面或模組的執行按鈕開啟 Haaru。
4. 選好 SIM、國家或名稱，按儲存，等介面確認生效。

**同版本 App 已放在 ZIP 裡，刷入時會自動安裝。** Android 還沒準備好時，會在開機後完成。離線也能安裝，舊 ZIP 不會另外抓最新 APK；App 不需要再授予 Root。

介面只使用原生 App。模組執行按鈕會開啟 App；若尚未安裝，會重試安裝 ZIP 內的同版本 APK。

需要 KernelSU 和能正常工作的 Zygisk provider。Android 10 以上是起點；Haaru 會檢查實際電話服務介面，沒有品牌或機型白名單。不同系統仍可能有差異，必要介面不足時會說明原因，不會把「已儲存」當成「已生效」。

### 幾件小事 🍞

- 國家和名稱可以分開改；選電信商不會偷偷換國家。已啟用方案的空白欄位會保留已存值，介面會寫「保留已存國家／名稱」；要恢復原始回報，請使用「還原 SIM」。
- 「載入已啟用方案」與「清空未儲存草稿」只改畫面中的草稿，不會刪除已存方案。
- 「SIM 回報國家」優先讀取同次開機的原值快取；沒有可用快取時讀取系統 getter，不會把每次回報都稱為原始值。
- 「還原」停止套用，保留你的方案；「清除設定」才把方案刪掉。
- 更新可直接刷新版 ZIP，再完整重啟。安裝器會安全遷移早期 Haaru 的設定，避免新舊引擎同時執行。
- 變動限於系統回報值，實體 SIM、APN、IP 和 GPS 不會因此換地區，也不能保證某個 App 的服務端判定會跟著改變。
- 設定留在模組資料夾，沒有遙測。移除模組後完整重啟，介面 App 也可另外移除。
- 首次使用採紫羅蘭 Expressive，並跟隨系統明暗。支援的 Android 12 以上環境可在設定開啟 Google Monet 桌布配色。手動模式提供 15 種配色和自訂選色器，更新時會沿用既有 Monet 偏好。
- 色彩風格可選 Expressive、Vibrant、Tonal Spot、Neutral（2025）或 CMF（2026）；預設 Expressive。桌布色與手動色都能換個氣氛，Miuix 控件保持一致。
- 首頁、工具、設定與關於共用四個持續存在的 3D 模型，在切頁時移動、變形；操作區以清楚的標籤與小面積強調色呈現。
- 減少動畫時模型保持靜態，App 進入背景或模型離開可見範圍時暫停渲染；模型品質也會依設備負荷、省電與溫度調整。
- 「關於信息」展示版本、作者、真實倉庫與鳴謝，使用同一套色彩與 3D 場景；隨附授權全文可以離線閱讀。

剛安裝時沒有預設方案。看到「暖機～中」就等一下；如果顯示錯誤，到工具頁看簡短狀態，需要時再展開診斷。

想看詳細相容性、原理或自行建置，可以翻 [相容性與功能](FEATURE_PARITY.md)、[建置說明](UI_BUILD.md) 和 [授權來源](NOTICE)。即使編譯與測試通過，也不等於所有 ROM 都已實機驗證。

---

<a id="english"></a>

## Welcome to Haaru ☁️

A little café for your SIM settings. Coffee first, then pick a country. ☕

**0.8.0 is an unpublished local candidate.** The four 3D pages and new navigation dock were checked on a local Android emulator. Handset frame timing and phone engine validation are pending. Download the published version from Releases.

Haaru is a KernelSU module for the SIM country and carrier name that Android reports. Choose from **28 countries and 74 carriers**, or use your own name. Each SIM keeps its own settings. The companion App uses Miuix controls, organic 3D forms that move and morph between pages, quiet surfaces and genuine liquid glass navigation. Models, lighting and controls follow your selected colours.

### Come in

1. Download the module ZIP from [Releases](https://github.com/Cekxri/Haaru.UwU/releases).
2. Flash it in KernelSU and fully reboot.
3. Open Haaru from your launcher or the module action button.
4. Pick a SIM, save your country or name, and wait for the confirmed status.

**The ZIP includes its matching App and installs it for you.** If Android is not ready during flashing, installation finishes after boot. It works offline and never pairs an old ZIP with a random latest APK. The App needs no separate Root grant.

The interface uses the native App. The module action button opens it, or retries installation of the matching APK from the ZIP if it is missing.

You need KernelSU and a working Zygisk provider. Android 10+ is the starting point. Haaru checks the phone services it finds rather than a device-model whitelist. ROMs can still differ: unsupported interfaces produce a clear error, and saved settings are never presented as proof that they took effect.

### A few small notes 🍞

- Country and carrier name are independent choices. Empty fields in an active plan preserve their saved values, labelled as keeping the saved country/name. Use Restore SIM to return to the original reported values.
- Loading an active plan or clearing an unsaved draft changes only the editor, without deleting the saved plan.
- Reported SIM country prefers the current-boot original-value cache and otherwise reads the system getter. The interface does not label every response as an original value.
- Restore stops applying a plan and keeps it. Clear deletes the saved plan.
- Flash a newer ZIP and fully reboot to update. Earlier Haaru settings are migrated with checks that prevent both engines from running together.
- This changes reported values. It does not relocate your physical SIM, APN, IP or GPS, and it cannot promise an App’s server-side region will change.
- Settings stay in the module folder. There is no telemetry. Uninstall the module and fully reboot; the companion App can be removed separately.
- A fresh install uses Violet with Expressive colours and follows the system’s light or dark appearance. You can enable Google Monet wallpaper colours in Settings on supported Android 12+ devices, or choose from 15 manual palettes and a custom colour picker. Updates keep your existing Monet preference.
- Choose Expressive, Vibrant, Tonal Spot or Neutral (2025), or CMF (2026), for both wallpaper and manual colours. Expressive is the default; the controls keep their Miuix style.
- Home, tools, settings and About share four persistent 3D forms that move and morph between pages. Clear labels and small accents keep the plan controls readable.
- Reduced motion keeps the models still. Rendering pauses in the background or when the stage is out of view; quality responds to device load, power saving and thermal conditions.
- About information presents versions, the author, real project links and credits using the same colours and 3D scene. Bundled licence texts are available offline.

A fresh install has no active plan. If Haaru is warming up, give it a moment. If something goes wrong, the tools page keeps the details out of your way until you need them.

For the less cosy bits, see [features and compatibility](FEATURE_PARITY.md), [building Haaru](UI_BUILD.md) and [credits](NOTICE). Passing build checks is not a claim that every ROM has been tested.

---

Made by **Cekxri** · Apache-2.0 · [Haaru.UwU](https://github.com/Cekxri/Haaru.UwU)
