<div align="center">

<img src=".github/assets/haaru-logo.png" width="96" height="96" alt="Haaru 標誌" />

# Haaru

<p><strong>調整 Android 回報的 SIM 國家與電信商名稱。</strong><br />KernelSU 模組，支援逐卡設定與還原。</p>

<p>
  <a href="https://github.com/Cekxri/Haaru.UwU/releases"><img src="https://img.shields.io/github/v/release/Cekxri/Haaru.UwU?include_prereleases&amp;label=preview&amp;color=81709c&amp;style=flat-square" alt="最新測試版" /></a>
  <a href="#安裝"><img src="https://img.shields.io/badge/Android-10%2B-647d71?style=flat-square" alt="Android 10 以上" /></a>
  <a href="https://github.com/tiann/KernelSU"><img src="https://img.shields.io/badge/KernelSU-module-647d71?style=flat-square" alt="KernelSU 模組" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-81709c?style=flat-square" alt="Apache License 2.0" /></a>
</p>

**繁體中文** · [English](README.en.md)

[**下載模組**](https://github.com/Cekxri/Haaru.UwU/releases) · [修改範圍](#修改範圍) · [功能](#功能) · [安裝](#安裝) · [常見問題](#常見問題) · [問題回報](https://github.com/Cekxri/Haaru.UwU/issues)

<sub>城堡之下的SIM國度.咖啡廳☕</sub>

<br />

<picture>
  <source media="(prefers-color-scheme: dark)" srcset=".github/assets/haaru-preview-dark.png" />
  <img src=".github/assets/haaru-preview-light.png" width="1100" alt="Haaru 原生介面：紫色首頁的 SIM 方案與 3D 雕塑，以及外觀設定頁。畫面來自模擬器，使用合成資料。" />
</picture>

<sub>0.8.3 介面預覽 · 模擬器合成資料</sub>

</div>

---

Haaru 在系統電話服務層覆寫 SIM 回報資料。為每張 SIM 選擇國家碼與電信商名稱後，使用對應 Android 介面的 App 會讀到設定值。兩個欄位可分開修改，並可隨時還原。

用途是解除依 SIM 歸屬地檢測的使用限制，例如 TikTok 因中國 SIM 檢測而提示網路錯誤。

## 修改範圍

- **SIM 國家碼** — 覆寫 CarrierConfig 回傳副本中的 SIM 國家欄位、SubscriptionInfo 回傳副本的國家欄位，以及 `gsm.sim.operator.iso-country` 非持久屬性。例如為 SIM 1 設定 `JP`，對應讀取路徑便以 `jp` 回報該卡國家。
- **電信商名稱** — 覆寫 CarrierConfig 的名稱與覆寫旗標、SubscriptionInfo 副本的電信商／顯示名稱，以及 `gsm.sim.operator.alpha` 非持久屬性。國家碼與名稱獨立設定。
- **逐卡套用與原值還原** — 方案依 SIM 身分保存，再映射到目前的訂閱與插槽；引擎保留原值、追蹤系統自然更新，還原時撤下覆寫。未選擇的卡與欄位保留原值。

> [!NOTE]
> 目前為測試版，真機上的套用、還原與個別服務相容性仍待驗證。

## 功能

### SIM 方案

- **每張卡各自保存** — 依系統提供的插槽與訂閱資料顯示 SIM，分別保存國家和名稱。
- **預設與自訂** — 28 國、74 個電信商預設值；國家與名稱獨立選擇。
- **重新套用、還原、清除** — 停止覆寫時可以保留方案，之後再套用。
- **引擎狀態** — 分別顯示方案儲存、引擎初始化與套用結果。

### 原生介面

- **Miuix 與液態玻璃** — 原生控件、背景取樣與折射的導航底欄。
- **3D 場景** — GLES 有機網格、金屬與玻璃材質、漂浮、觸摸彈簧與滾動視差。
- **配色選擇** — Google Monet、15 種手動色、HSV／自訂色碼及五種色彩風格。
- **減少動態效果** — 遵循系統移除動畫設定，也可在 App 裡調整。

## 安裝

### 需要什麼

- **Android 10 或更新版本**，以及已安裝的 **KernelSU**。
- **Zygisk API 4 提供程式**，例如 [Zygisk Next](https://github.com/Dr-TSNG/ZygiskNext)，需另外安裝。
- 系統電話服務與 ART 須支援引擎使用的掛鉤介面。

模組提供 arm64-v8a、armeabi-v7a、x86_64 和 x86 四種架構。Monet 動態取色需要 Android 12 以上；玻璃效果依系統能力降級。

### 下載與開始使用

1. 在 [Releases](https://github.com/Cekxri/Haaru.UwU/releases) 下載 `Haaru-<版本>.zip`。
2. 在 KernelSU 的模組頁從檔案安裝 ZIP，然後重新啟動手機。
3. 從桌面，或 KernelSU 模組的執行按鈕開啟 Haaru。
4. 選擇 SIM、國家及電信商名稱，儲存後查看套用狀態。

模組 ZIP 內附同版本的簽署 App，安裝器會自動安裝；系統尚未就緒時，在開機後續裝。App 透過模組服務執行操作，無需另外授予 Root。

| Release 檔案 | 用途 |
| :--- | :--- |
| `Haaru-<版本>.zip` | 在 KernelSU 安裝的模組，內附配套 App。 |
| `Haaru-<版本>.apk` | 配套 App，需要搭配模組使用。 |
| `Haaru-<版本>-source.zip` | 該版本的來源快照。 |
| `SHA256SUMS.txt` | 下載檔案的 SHA-256 校驗值。 |

### 更新與移除

**更新：** 安裝新版模組 ZIP 並重新啟動手機，既有 SIM 方案與外觀設定會保留。

**移除：** 在 KernelSU 移除模組並重新啟動手機，清除記憶體中的覆寫。配套 App 可另外解除安裝。

## 如何運作

1. **保存方案** — App 透過本機 socket 將操作交給模組服務，服務核對連線 UID、App 套件與簽章。
2. **套用覆寫** — Zygisk 將引擎載入系統電話程序，LSPlant 掛鉤 CarrierConfig 與訂閱資料讀取方法。引擎修改回傳副本及兩項非持久屬性，核對 SIM 對應關係並回讀確認。
3. **讀取與還原** — 使用上述介面的 App 讀到設定值。引擎保存原值並追蹤系統更新，還原時撤下覆寫。

實體 SIM、APN、eSIM／Subscription 資料庫、MCC/MNC、IP、GPS 與帳號資料保持原樣。服務可能另有 IP 或帳號地區檢查，需依該服務的情況處理。

設定保存在本機模組資料夾，沒有遙測。SELinux 模式保持不變，模組僅加入電話引擎 socket 所需的權限規則。

### 使用情境

- **TikTok 與同類服務** — 針對 SIM 國家檢測造成的網路錯誤或使用限制，回報使用者選擇的受支援國家。
- **其他 SIM 地區檢查** — Samsung Health 首次 SIM 檢查等同類情境，也是相容性驗證的目標。
- **SIM 資料識別** — 調整對外回報的國家或電信商名稱，讓讀取這些欄位的 App 使用設定值。

## 常見問題

<details>
<summary><strong>APK 與模組有什麼差別？</strong></summary>

APK 提供操作介面，模組包含電話引擎與本機服務。安裝模組 ZIP 並重新啟動手機，配套 App 會自動安裝。

</details>

<details>
<summary><strong>為什麼儲存後仍顯示未套用？</strong></summary>

方案儲存後，由電話引擎套用。若引擎尚未初始化或回報錯誤，工具頁會顯示原因；更新模組後也需要重新啟動手機。

</details>

<details>
<summary><strong>還原與清除有什麼差別？</strong></summary>

還原停止覆寫並保留已存方案，之後可重新套用。清除會還原並刪除 SIM 方案；外觀設定保留。

</details>

<details>
<summary><strong>Haaru 與 Nrfr 的差別是什麼？</strong></summary>

Nrfr 透過 Android 的 CarrierConfig 覆蓋配置介面套用設定。Haaru 使用 KernelSU 與 Zygisk，在電話服務的讀取方法中修改回傳副本，並同步非持久系統屬性。兩者的用途都是調整服務讀到的 SIM 歸屬地。

</details>

<details>
<summary><strong>遇到引擎錯誤，回報哪些資料？</strong></summary>

請附上 Haaru 與 Android 版本、Zygisk 提供程式與版本、重現步驟，以及工具頁的「複製去識別診斷」。回報前確認模組與 App 版本相符，更新後已重新啟動手機。

Bugreport 與模組資料可能包含裝置或 SIM 資訊，分享前請移除個人資料。

</details>

## 開發與貢獻

Haaru 使用 Kotlin／Compose 建立原生介面，Java 處理 Android 服務及反射介面，Rust 提供資料驗證與遷移，C++ 連接 Zygisk、ART 與 LSPlant。

建置流程見 [RELEASE](RELEASE.md#最終建置與驗證)，介面、配色與 3D 渲染說明見 [UI_BUILD](UI_BUILD.md)。

問題與建議請提交至 [Issues](https://github.com/Cekxri/Haaru.UwU/issues)，程式修改可提交 [Pull request](https://github.com/Cekxri/Haaru.UwU/pulls)。電話服務或掛鉤相關修改請附上測試環境、結果與還原檢查。

## 授權與鳴謝

Haaru 由 [Cekxri](https://github.com/Cekxri) 維護，使用 [Apache License 2.0](LICENSE)。第三方程式碼與資產保留各自授權，完整資訊見 [NOTICE](NOTICE)。

感謝 [Nrfr](https://github.com/a5533348/Nrfr) 的功能與國家／電信商預設資料，以及 [tiktok-region-hook](https://github.com/kjqg-cn/tiktok-region-hook) 的研究參考。

介面與引擎建立在 [Miuix](https://github.com/compose-miuix-ui/miuix)、[AndroidLiquidGlass／Backdrop](https://github.com/Kyant0/AndroidLiquidGlass)、[MaterialKolor](https://github.com/jordond/materialkolor)、[LSPlant](https://github.com/LSPosed/LSPlant) 與其他開源專案之上。

<div align="center">

<br />

[下載](https://github.com/Cekxri/Haaru.UwU/releases) · [源碼](https://github.com/Cekxri/Haaru.UwU) · [回報問題](https://github.com/Cekxri/Haaru.UwU/issues) · [English](README.en.md)

</div>
