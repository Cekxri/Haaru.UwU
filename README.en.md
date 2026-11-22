<div align="center">

<img src=".github/assets/haaru-mark.svg" width="72" height="72" alt="Haaru coffee cup mark" />

# Haaru

<p><strong>Override the reported SIM country and carrier name in Android’s phone service.</strong><br />An original KernelSU module that supplies your chosen SIM values to Apps.</p>

<p>
  <a href="https://github.com/Cekxri/Haaru.UwU/releases"><img src="https://img.shields.io/github/v/release/Cekxri/Haaru.UwU?include_prereleases&amp;label=preview&amp;color=81709c&amp;style=flat-square" alt="Latest preview release" /></a>
  <a href="#installation"><img src="https://img.shields.io/badge/Android-10%2B-647d71?style=flat-square" alt="Android 10 or later" /></a>
  <a href="https://github.com/tiann/KernelSU"><img src="https://img.shields.io/badge/KernelSU-module-647d71?style=flat-square" alt="KernelSU module" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-81709c?style=flat-square" alt="Apache License 2.0" /></a>
</p>

[繁體中文](README.md) · **English**

[**Download the module**](https://github.com/Cekxri/Haaru.UwU/releases) · [What it changes](#what-the-module-changes) · [Features](#features) · [Installation](#installation) · [FAQ](#faq) · [Report an issue](https://github.com/Cekxri/Haaru.UwU/issues)

<sub>城堡之下的SIM國度.咖啡廳☕</sub>

<br />

<picture>
  <source media="(prefers-color-scheme: dark)" srcset=".github/assets/haaru-preview-dark.png" />
  <img src=".github/assets/haaru-preview-light.png" width="1100" alt="Haaru's native interface: SIM plans and a 3D sculpture on the violet home screen, alongside appearance settings. Captured in an emulator with synthetic data." />
</picture>

<sub>Native interface preview from 0.8.3 · Synthetic emulator data, not evidence of device application</sub>

</div>

---

Haaru is a **KernelSU module that overrides SIM data reported by Android’s system phone service**. Set a country code and carrier name for each SIM; the engine applies those values when the system returns that data, so Apps reading the covered Android interfaces receive the overrides. Country and name are independent, and the originals can be restored.

It uses a different architecture to serve the same purpose as Nrfr: change the SIM region a service reads to remove restrictions based on SIM-region checks. TikTok reporting network errors after detecting a Chinese SIM is a typical problem the project aims to solve.

## What the module changes

- **SIM country code** — Overrides the SIM country field in returned CarrierConfig copies, the country field in returned SubscriptionInfo copies, and the non-persistent `gsm.sim.operator.iso-country` property. Setting SIM 1 to `JP`, for example, makes the corresponding read paths report `jp` for that card.
- **Carrier name** — Overrides the CarrierConfig name and override flag, the carrier/display name in SubscriptionInfo copies, and the non-persistent `gsm.sim.operator.alpha` property. Choosing a name does not automatically change the country.
- **Per-SIM application and restoration** — Saved plans follow SIM identity and map to current subscriptions and slots. The engine keeps originals, tracks natural system updates and removes overrides when restoring. Unselected cards and fields retain their original values.

The **Zygisk/LSPlant engine inside the system phone process** makes these changes. The native App provides selection, plan storage and status. The module operates on the system responses and properties other Apps read, beyond the interface’s own displayed text.

Choose from **28 countries and 74 carrier presets**, or enter a custom name. The native interface uses Miuix, liquid glass navigation, procedural 3D and dynamic colours.

> [!NOTE]
> Haaru is a public preview. These mechanisms are implemented in the current source; full KernelSU device application, restoration and individual service results remain unverified. Android 10 is the installation minimum, not a guarantee of compatibility with every ROM.

## Features

### SIM plans

- **A plan for each card** — Uses the slots and subscriptions Android exposes, with separate country and name settings.
- **Presets and custom names** — 28 countries, 74 carrier presets and independent country/name choices.
- **Reapply, restore and clear** — Stop an override while keeping the plan for later.
- **Explicit confirmation** — A saved plan, full hook coverage and a fresh engine acknowledgement are distinct checks.

### Native interface

- **Miuix and liquid glass** — Native controls and a navigation dock that samples and refracts its background.
- **A real 3D scene** — GLES organic meshes, metal and glass materials, floating motion, touch springs and scroll parallax.
- **Your colours** — Google Monet, 15 manual palettes, HSV/custom colour codes and five colour styles.
- **Less motion when needed** — Follows the system's animation removal setting, with an additional App preference.

## Installation

### Requirements

- **Android 10 or later**, with **KernelSU** installed.
- A working **Zygisk API 4 provider**, such as [Zygisk Next](https://github.com/Dr-TSNG/ZygiskNext). KernelSU does not provide Zygisk itself.
- Usable system phone services, ART and required hook interfaces. Haaru probes the actual framework rather than a device-model allowlist.

The module includes arm64-v8a, armeabi-v7a, x86_64 and x86 builds. Monet requires Android 12 or later; glass effects adapt to the system's capabilities.

### Download and get started

1. Download `Haaru-<version>.zip` from [Releases](https://github.com/Cekxri/Haaru.UwU/releases).
2. Install the ZIP from KernelSU's module page, then **fully reboot**.
3. Open Haaru from your launcher or the module action button in KernelSU.
4. Select a SIM, choose its country and carrier name, save the plan and check the engine confirmation.

**The ZIP includes its matching signed App.** The installer installs it automatically, or completes installation after boot if Android is not ready. The App needs no separate Root grant, and installation does not download an APK from another release.

| Release file | Purpose |
| :--- | :--- |
| `Haaru-<version>.zip` | The KernelSU module, including its companion App. |
| `Haaru-<version>.apk` | The matching standalone App; installing it alone does not provide the module engine. |
| `Haaru-<version>-source.zip` | The source snapshot for that release. |
| `SHA256SUMS.txt` | SHA-256 checksums for the downloads. |

### Update and remove

**Update:** install the newer module ZIP and fully reboot. The companion App updates with the same signing identity. Existing SIM plans and appearance preferences are preserved; earlier settings are handled by the migration flow.

**Remove:** uninstall the module in KernelSU and fully reboot so the phone process restarts and clears the in-memory overrides. The interface App can be uninstalled separately.

## How it works

1. **The App saves the plan** — The native App sends operations through a restricted local socket. The module service verifies the peer UID, App package and signing identity. The App does not invoke `su`.
2. **The phone engine applies the overrides** — Zygisk loads the engine into the system phone process. LSPlant hooks CarrierConfig and subscription-data readers. The engine modifies returned copies and the two non-persistent properties, checking originals, SIM mapping and property readback.
3. **Apps read the chosen values** — Apps using the covered SIM country/name interfaces receive the overrides. This is the intervention point for a service’s SIM-region decision. A saved plan, engine confirmation and removal of a service restriction are separate verification results.

The physical SIM, APN, eSIM/Subscription database and MCC/MNC are outside the modification scope. The module also does not modify IP addresses, GPS or accounts. Services that use those as additional region checks need them addressed separately; that does not negate the purpose of SIM-region overrides.

Preferences live in the local module folder. Haaru has no telemetry. The module preserves the system’s SELinux mode: Enforcing is not disabled, and the existing rules add only scoped permissions needed by the phone engine’s socket.

### Use cases

- **TikTok and similar services** — Address SIM-country checks that trigger network errors or access restrictions by reporting the selected supported country.
- **Other services with SIM-region checks** — Target the same class of use cases as Nrfr, including Samsung Health’s initial SIM-region check. Each service still needs compatibility testing.
- **Incorrect SIM-data identification** — Adjust the reported country or carrier name so Apps reading those fields use the chosen values.

## FAQ

<details>
<summary><strong>Why does installing only the APK not work?</strong></summary>

The APK is the interface. The phone engine and authenticated local service come from the KernelSU module. Install the module ZIP and fully reboot.

</details>

<details>
<summary><strong>Why is a saved plan not proof that it is active?</strong></summary>

Saving confirms that the plan has been written. Application also needs an initialised phone engine, complete required hooks and a valid acknowledgement matching the current plan. If the engine is not ready, open the tools page for its detailed status.

</details>

<details>
<summary><strong>What is the difference between restore and clear?</strong></summary>

Restore stops the overrides and keeps the saved plans so they can be reapplied. Clear restores the original values and deletes the SIM plans; appearance preferences are retained.

</details>

<details>
<summary><strong>How does Haaru differ from Nrfr?</strong></summary>

The purpose is aligned with Nrfr: change the SIM region a service reads to remove restrictions caused by SIM-region checks. Haaru uses KernelSU, Zygisk and overrides of phone-service return copies/non-persistent properties, with its own native interface, per-SIM plans and engine confirmation. It is not a direct port of Nrfr’s implementation.

</details>

<details>
<summary><strong>What should an engine error report include?</strong></summary>

First check that the App and module versions match and that you fully rebooted after updating. Include the Haaru version, Android version, Zygisk provider and version, reproduction steps, and the tools page's de-identified diagnostic export.

Full Bugreports and module data may contain device or SIM information. Review them and remove personal data before sharing; do not attach them directly to a public Issue.

</details>

## Development and contributing

Haaru uses Kotlin/Compose for its native interface, Java for Android services and reflection, Rust for data validation and migration, and C++ to connect Zygisk, ART and LSPlant.

Start with the [release build instructions](RELEASE.md#最終建置與驗證). [Native interface builds](UI_BUILD.md) covers the toolchain, colours, 3D rendering and performance verification boundaries. Public source excludes private signing keys, device records and runtime preferences.

Reproducible bug reports and feature suggestions are welcome in [Issues](https://github.com/Cekxri/Haaru.UwU/issues), as are [Pull requests](https://github.com/Cekxri/Haaru.UwU/pulls). For phone-service or hook changes, describe the test environment, verification scope and restoration behaviour.

## Licence and acknowledgements

Haaru is maintained by [Cekxri](https://github.com/Cekxri) and licensed under the [Apache License 2.0](LICENSE). Third-party code and assets retain their own licences; see [NOTICE](NOTICE) for the full attribution.

The project began with research into [tiktok-region-hook](https://github.com/kjqg-cn/tiktok-region-hook) and [Nrfr](https://github.com/a5533348/Nrfr), then developed into Haaru with a different architecture. Attribution for Nrfr's features and selection data is preserved.

The interface and engine build on [Miuix](https://github.com/compose-miuix-ui/miuix), [AndroidLiquidGlass/Backdrop](https://github.com/Kyant0/AndroidLiquidGlass), [MaterialKolor](https://github.com/jordond/materialkolor), [LSPlant](https://github.com/LSPosed/LSPlant) and other open-source projects.

<div align="center">

<br />

[Downloads](https://github.com/Cekxri/Haaru.UwU/releases) · [Source](https://github.com/Cekxri/Haaru.UwU) · [Report an issue](https://github.com/Cekxri/Haaru.UwU/issues) · [繁體中文](README.md)

</div>
