<div align="center">

<img src=".github/assets/haaru-mark.svg" width="72" height="72" alt="Haaru 咖啡杯標誌" />

# Haaru

<p><strong>透過調整 SIM 歸屬地，解除 App 與服務的 SIM 地區限制。</strong><br />對齊 Nrfr 的用途，以 KernelSU 模組與原生 Android App 實現。</p>

<p>
  <a href="https://github.com/Cekxri/Haaru.UwU/releases"><img src="https://img.shields.io/github/v/release/Cekxri/Haaru.UwU?include_prereleases&amp;label=preview&amp;color=81709c&amp;style=flat-square" alt="最新測試版" /></a>
  <a href="#安裝"><img src="https://img.shields.io/badge/Android-10%2B-647d71?style=flat-square" alt="Android 10 以上" /></a>
  <a href="https://github.com/tiann/KernelSU"><img src="https://img.shields.io/badge/KernelSU-module-647d71?style=flat-square" alt="KernelSU 模組" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-81709c?style=flat-square" alt="Apache License 2.0" /></a>
</p>

**繁體中文** · [English](README.en.md)

[**下載模組**](https://github.com/Cekxri/Haaru.UwU/releases) · [使用情境](#使用情境) · [安裝](#安裝) · [功能](#功能) · [常見問題](#常見問題) · [問題回報](https://github.com/Cekxri/Haaru.UwU/issues)

<sub>城堡之下的SIM國度.咖啡廳☕</sub>

<br />

<picture>
  <source media="(prefers-color-scheme: dark)" srcset=".github/assets/haaru-preview-dark.png" />
  <img src=".github/assets/haaru-preview-light.png" width="1100" alt="Haaru 原生介面：紫色首頁的 SIM 方案與 3D 雕塑，以及外觀設定頁。畫面來自模擬器，使用合成資料。" />
</picture>

<sub>0.8.3 原生介面預覽 · 模擬器合成資料，並非真機生效證據</sub>

</div>

---

**Haaru 的核心目標是達成與 Nrfr 相同的使用效果：讓 TikTok 等服務讀到你設定的 SIM 歸屬地，解除因 SIM 地區檢測造成的使用限制。** 例如插入中國 SIM 後，TikTok 因 SIM 歸屬地被限制而持續提示網路錯誤，Haaru 的目標就是透過回報受支援的 SIM 國家，讓這類服務恢復使用。

你可以為每張 SIM 選擇 Android 回報的國家與電信商名稱，也能隨時還原。從 **28 個國家、74 個電信商預設值**中選擇，或輸入自訂名稱；國家與名稱可以分開設定。

原生 App 使用 Miuix 控件、液態玻璃導航與程序化 3D 雕塑。外觀隨目前配色一起變化，草稿、已儲存方案和引擎確認分開呈現。

> [!NOTE]
> Haaru 目前為公開測試版。建置與合成環境檢查已完成，KernelSU 真機的完整套用及還原仍待驗證。Android 10 是安裝底線，不代表所有 ROM 都已相容。

## 使用情境

- **TikTok 的 SIM 地區限制** — 針對因中國 SIM 歸屬地檢測而出現的網路錯誤或使用限制，讓 App 讀到設定的受支援國家。
- **其他依 SIM 地區檢查的服務** — 對齊 Nrfr 的同類用途，例如 Samsung Health 首次 SIM 地區檢查；個別服務的實際結果需要相容性驗證。
- **SIM 地區識別不符** — 調整系統對外回報的國家或電信商名稱，讓讀取這些資料的 App 使用設定值。

## 功能

### SIM 方案

- **每張卡各自保存** — 依系統提供的插槽與訂閱資料顯示 SIM，分別保存國家和名稱。
- **預設與自訂** — 28 國、74 個電信商預設值；國家與名稱獨立選擇。
- **重新套用、還原、清除** — 停止覆寫時可以保留方案，之後再套用。
- **確認實際狀態** — 已儲存不等於已生效；完整掛鉤與新鮮引擎回報是不同檢查。

### 原生介面

- **Miuix 與液態玻璃** — 原生控件、背景取樣與折射的導航底欄。
- **真正的 3D 場景** — GLES 有機網格、金屬與玻璃材質、漂浮、觸摸彈簧與滾動視差。
- **你的配色** — Google Monet、15 種手動色、HSV／自訂色碼及五種色彩風格。
- **動態可以減少** — 遵循系統移除動畫設定，也可在 App 裡調整。

## 安裝

### 需要什麼

- **Android 10 或更新版本**，以及已安裝的 **KernelSU**。
- 正常工作的 **Zygisk API 4 提供程式**，例如 [Zygisk Next](https://github.com/Dr-TSNG/ZygiskNext)。KernelSU 本身不提供 Zygisk。
- 系統電話服務、ART 與必要掛鉤介面可用。Haaru 依實際介面探測，不使用機型白名單。

模組提供 arm64-v8a、armeabi-v7a、x86_64 和 x86 四種架構。Monet 動態取色需要 Android 12 以上；玻璃效果依系統能力降級。

### 下載與開始使用

1. 在 [Releases](https://github.com/Cekxri/Haaru.UwU/releases) 下載 `Haaru-<版本>.zip`。
2. 在 KernelSU 的模組頁從檔案安裝 ZIP，然後**完整重新開機**。
3. 從桌面，或 KernelSU 模組的執行按鈕開啟 Haaru。
4. 選擇 SIM、國家及電信商名稱，儲存方案，再查看引擎確認結果。

**ZIP 已附同版本的簽署 App。** 安裝器會自動安裝；Android 尚未就緒時，在開機後續裝。App 不需另授予 Root，安裝也不會另外下載不同版本的 APK。

| Release 檔案 | 用途 |
| :--- | :--- |
| `Haaru-<版本>.zip` | 在 KernelSU 安裝的模組，內附配套 App。 |
| `Haaru-<版本>.apk` | 同版本的獨立 App；單獨安裝不會提供模組引擎。 |
| `Haaru-<版本>-source.zip` | 該版本的來源快照。 |
| `SHA256SUMS.txt` | 下載檔案的 SHA-256 校驗值。 |

### 更新與移除

**更新：** 直接安裝新版模組 ZIP，完整重啟。配套 App 使用相同簽章更新，既有 SIM 方案與外觀設定會保留；早期設定由遷移流程處理。

**移除：** 在 KernelSU 移除模組後完整重啟，讓電話程序重新啟動並清除記憶體中的覆寫。介面 App 可另外解除安裝。

## 如何運作

原生 App 透過本機 socket 把固定操作交給模組服務。服務核對連線 UID、App 套件與簽章；App 本身不呼叫 `su`。

電話引擎透過 Zygisk 在系統電話程序中掛鉤，修改回傳資料的副本及兩項非持久 SIM properties，並保存原值供還原。掛鉤覆蓋、SIM 映射、原值與引擎確認不足時，方案不會被視為已生效。

**服務讀到的 SIM 國家就是 Haaru 的介入點。** App 如果以 SIM 歸屬地判斷是否允許使用，調整這個回報值便是解除該項限制的技術路徑。實體 SIM、APN、eSIM／Subscription 資料庫及 MCC/MNC 保持原樣。

IP、GPS、帳號或服務端規則可能是同一服務的其他檢查條件，Haaru 不直接修改這些資料。這與解除 SIM 檢測限制是不同層次；實際相容性以服務版本、裝置與驗證結果為準。

設定保存在本機模組資料夾，Haaru 沒有遙測。模組沿用系統的 SELinux 模式；Enforcing 不會被關閉，現有規則只補上電話引擎 socket 所需的限定權限。

## 常見問題

<details>
<summary><strong>為什麼只裝 APK 不能使用？</strong></summary>

APK 是介面。實際電話引擎與受驗證的本機服務由 KernelSU 模組提供，需要安裝模組 ZIP 並完整重啟。

</details>

<details>
<summary><strong>「已儲存」為什麼不代表「已生效」？</strong></summary>

儲存只確認方案已寫入。生效還需要電話引擎初始化、必要掛鉤完整，以及與目前方案一致的有效回報。引擎未就緒時，到工具頁查看詳細狀態。

</details>

<details>
<summary><strong>還原與清除有什麼差別？</strong></summary>

還原停止覆寫並保留已存方案，之後可重新套用。清除會還原並刪除 SIM 方案；外觀設定保留。

</details>

<details>
<summary><strong>Haaru 如何解除 TikTok 等服務的 SIM 地區限制？</strong></summary>

讓 App 讀到你設定的 SIM 國家，取代造成限制的原始歸屬地。這正是 Haaru 對齊 Nrfr 的核心用途；中國 SIM 觸發 TikTok 網路錯誤，是本專案要解決的典型情境。

目標是解除 SIM 檢測造成的限制，個別服務還可能有其他檢查條件。Haaru 目前仍是測試版，完整真機服務效果待驗證；Nrfr 的成功案例不會被當成 Haaru 已驗證的結果。

</details>

<details>
<summary><strong>遇到引擎錯誤，回報哪些資料？</strong></summary>

先確認模組與 App 版本相符，更新後已完整重啟。回報版本、Android 版本、Zygisk 提供程式與版本、重現步驟，以及工具頁的「複製去識別診斷」。

完整 Bugreport 及模組資料可能包含裝置或 SIM 資訊，請先檢查並移除個人資料，勿直接貼到公開 Issue。

</details>

## 開發與貢獻

Haaru 使用 Kotlin／Compose 建立原生介面，Java 處理 Android 服務及反射介面，Rust 提供資料驗證與遷移，C++ 連接 Zygisk、ART 與 LSPlant。

從 [Release 建置說明](RELEASE.md#最終建置與驗證)開始；[原生介面建置](UI_BUILD.md)涵蓋工具鏈、配色、3D 渲染及效能驗證界線。公開來源不含私人簽章金鑰、裝置紀錄或執行期設定。

歡迎透過 [Issues](https://github.com/Cekxri/Haaru.UwU/issues) 回報可重現的問題或提出功能建議，也歡迎提交 [Pull request](https://github.com/Cekxri/Haaru.UwU/pulls)。涉及電話服務或掛鉤的修改，請說明測試環境、驗證範圍與還原行為。

## 授權與鳴謝

Haaru 由 [Cekxri](https://github.com/Cekxri) 維護，使用 [Apache License 2.0](LICENSE)。第三方程式碼與資產保留各自授權，完整資訊見 [NOTICE](NOTICE)。

專案起於研究 [tiktok-region-hook](https://github.com/kjqg-cn/tiktok-region-hook) 與 [Nrfr](https://github.com/a5533348/Nrfr)，之後以不同架構發展為 Haaru。Nrfr 的功能與選擇資料來源照實保留鳴謝。

介面與引擎建立在 [Miuix](https://github.com/compose-miuix-ui/miuix)、[AndroidLiquidGlass／Backdrop](https://github.com/Kyant0/AndroidLiquidGlass)、[MaterialKolor](https://github.com/jordond/materialkolor)、[LSPlant](https://github.com/LSPosed/LSPlant) 與其他開源專案之上。

<div align="center">

<br />

[下載](https://github.com/Cekxri/Haaru.UwU/releases) · [源碼](https://github.com/Cekxri/Haaru.UwU) · [回報問題](https://github.com/Cekxri/Haaru.UwU/issues) · [English](README.en.md)

</div>
