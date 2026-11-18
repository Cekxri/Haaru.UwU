<div align="center">

# Haaru

**城堡之下的SIM國度.咖啡廳☕**

給 SIM 的小小咖啡廳。選好一個地方，再慢慢喝咖啡。

^ ܸ⩌⩊⩌ ܸ^

[繁體中文](#中文) · [English](#english) · [下載 / Download](https://github.com/Cekxri/Haaru.UwU/releases)

</div>

<a id="中文"></a>

## 你好，這裡是 Haaru ☁️

Haaru 是 KernelSU 模組，讓你替每張 SIM 選擇 Android 回報的國家與電信商名稱。介面有 Miuix、Material 3，也有一點玻璃、柔和的色調和跟手的動態效果。

可以從 **28 個國家、74 個電信商** 裡挑選，也可以寫自己的名稱。兩張卡各自設定，更多插槽也會依系統提供的資料顯示；不會把某一台手機的插槽資料寫死在程式裡。

### 先坐下，再開始 ☕

1. 到 [Releases](https://github.com/Cekxri/Haaru.UwU/releases) 下載模組 ZIP。
2. 在 KernelSU 刷入，然後完整重新開機。
3. 從桌面或模組的執行按鈕開啟 Haaru。
4. 選好 SIM、國家或名稱，按儲存，等介面確認生效。

**同版本 App 已放在 ZIP 裡，刷入時會自動安裝。** Android 還沒準備好時，會在開機後完成。離線也能安裝，舊 ZIP 不會另外抓最新 APK；App 不需要再授予 Root。

需要 KernelSU 和能正常工作的 Zygisk provider。Android 10 以上是起點；Haaru 會檢查實際電話服務介面，沒有品牌或機型白名單。不同系統仍可能有差異，必要介面不足時會說明原因，不會把「已儲存」當成「已生效」。

### 幾件小事 🍞

- 國家和名稱可以分開改；選電信商不會偷偷換國家。
- 「還原」停止套用，保留你的方案；「清除設定」才把方案刪掉。
- 更新可直接刷新版 ZIP，再完整重啟。安裝器會安全遷移早期 Haaru 的設定，避免新舊引擎同時執行。
- 變動限於系統回報值，實體 SIM、APN、IP 和 GPS 不會因此換地區，也不能保證某個 App 的服務端判定會跟著改變。
- 設定留在模組資料夾，沒有遙測。移除模組後完整重啟，介面 App 也可另外移除。

剛安裝時沒有預設方案。看到「暖機～中」就等一下；如果顯示錯誤，到工具頁看簡短狀態，需要時再展開診斷。

想看詳細相容性、原理或自行建置，可以翻 [相容性與功能](FEATURE_PARITY.md)、[建置說明](UI_BUILD.md) 和 [授權來源](NOTICE)。編譯與測試通過，不等於所有 ROM 都已實機驗證。

---

<a id="english"></a>

## Welcome to Haaru ☁️

A little café for your SIM settings. Coffee first, then pick a country. ☕

Haaru is a KernelSU module for the SIM country and carrier name that Android reports. Choose from **28 countries and 74 carriers**, or use your own name. Each SIM keeps its own settings. Miuix, Material 3, soft colors and a moving glass navigation bar make up the companion App.

### Come in

1. Download the module ZIP from [Releases](https://github.com/Cekxri/Haaru.UwU/releases).
2. Flash it in KernelSU and fully reboot.
3. Open Haaru from your launcher or the module action button.
4. Pick a SIM, save your country or name, and wait for the confirmed status.

**The ZIP includes its matching App and installs it for you.** If Android is not ready during flashing, installation finishes after boot. It works offline and never pairs an old ZIP with a random latest APK. The App needs no separate Root grant.

You need KernelSU and a working Zygisk provider. Android 10+ is the starting point. Haaru checks the phone services it finds rather than a device-model whitelist. ROMs can still differ: unsupported interfaces produce a clear error, and saved settings are never presented as proof that they took effect.

### A few small notes 🍞

- Country and carrier name are independent choices.
- Restore stops applying a plan and keeps it. Clear deletes the saved plan.
- Flash a newer ZIP and fully reboot to update. Earlier Haaru settings are migrated with checks that prevent both engines from running together.
- This changes reported values. It does not relocate your physical SIM, APN, IP or GPS, and it cannot promise an App’s server-side region will change.
- Settings stay in the module folder. There is no telemetry. Uninstall the module and fully reboot; the companion App can be removed separately.

A fresh install has no active plan. If Haaru is warming up, give it a moment. If something goes wrong, the tools page keeps the details out of your way until you need them.

For the less cozy bits, see [features and compatibility](FEATURE_PARITY.md), [building Haaru](UI_BUILD.md) and [credits](NOTICE). Passing build checks is not a claim that every ROM has been tested.

---

Made by **Cekxri** · Apache-2.0 · [Haaru.UwU](https://github.com/Cekxri/Haaru.UwU)
